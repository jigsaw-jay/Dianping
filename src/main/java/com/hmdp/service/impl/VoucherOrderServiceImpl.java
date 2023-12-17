package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //创建代理对象成员变量
    private IVoucherOrderService proxy;

    //在当前类初始化完毕后立马执行
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //创建线程任务
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常：", e);
                }
            }
        }
    }

    //处理订单信息
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户ID
        Long userId = voucherOrder.getUserId();
        //创建Redis锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean tryLock = lock.tryLock();
        //判断是否成功获取锁
        if (!tryLock) {
            //获取失败
            log.error("不允许重复下单");
            return;
        }
        try {
            //如果通过AopContext.currentProxy()直接获取代理对象，无法实现
            //因为handleVoucherOrder是基于子线程实现的，无法从ThreadLocal中取出代理对象
            //所以需要在主线程提前获取->创建成员变量接收
            proxy.createOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    /**
     * 实现优惠券秒杀下单
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取当前时间
        Long currentTime = System.currentTimeMillis() / 1000;
        //1.执行Lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(),currentTime.toString());
        //Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        //2.判断结果是否==0
        int r = result.intValue();
        if (r != 0) {
            //3.否->返回异常信息
            switch (r){
                case 1:
                    log.error("库存不足");
                    return Result.fail("库存不足");
                case 2:
                    log.error("重复下单");
                    return Result.fail("重复下单");
                case 3:
                    log.error("秒杀没开始");
                    return Result.fail("秒杀没开始");
                case 4:
                    log.error("秒杀结束");
                    return Result.fail("秒杀结束");
            }
        }
        //4.是->将优惠券id、用户id和订单id存入阻塞队列
        //4.1.创建订单->返回订单Id
        VoucherOrder voucherOrder = new VoucherOrder();
        //4.2.生成订单ID
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //4.3.设置用户ID
        voucherOrder.setUserId(userId);
        //4.4.设置优惠券ID
        voucherOrder.setVoucherId(voucherId);
        //4.6.放入阻塞队列
        orderTasks.add(voucherOrder);
        //5.获取成功->主线程拿到当前对象（IVoucherOrderService）的代理对象->调用createOrder功能
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //6.返回订单id
        return Result.ok(orderId);
    }
/*    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否正常
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //3.（未开始/结束）-->返回异常结果
            return Result.fail("活动尚未开始！！");
        } else if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("活动已经结束！！");
        }
        //4.是-->判断库存是否充足
        if (voucher.getStock() < 1) {
            //5.否-->返回异常结果
            return Result.fail("库存不足！！");
        }
        //6.是-->根据优惠券id+用户id查询订单->
        Long userId = UserHolder.getUser().getId();
        //创建Redis锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean tryLock = lock.tryLock();
        //判断是否成功获取锁
        if (!tryLock) {
            //获取失败，返回错误or重试
            return Result.fail("一人一单！！");

        }
        try {
            //获取成功->拿到当前对象（IVoucherOrderService）的代理对象->调用createOrder功能
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createOrder(voucherId, userId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            lock.unlock();
        }
    }*/

    /**
     * 创建订单
     */
    @Transactional
    public void createOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //6.1判断订单是否存在--->存在-->返回异常结果
        if (count > 0) {
            log.error("限购一单！！！");
            return;
        }
        //6.2不存在-> 扣减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0).update();
        if (!success) {
            //扣减失败
            log.error("库存不足！！！");
            return;
        }
        this.save(voucherOrder);
    }
/*    @Transactional
    public Result createOrder(Long voucherId, Long userId) {
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //6.1判断订单是否存在--->存在-->返回异常结果
        if (count > 0) {
            return Result.fail("限购一单！！！");
        }
        //6.2不存在-> 扣减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0).update();
        if (!success) {
            //扣减失败
            return Result.fail("库存不足！！！");
        }
        //7.创建订单->返回订单Id
        VoucherOrder voucherOrder = new VoucherOrder();
        //生成订单ID
        long orderId = redisIdWorker.nextId("order");
        //获取用户ID
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        this.save(voucherOrder);
        return Result.ok(orderId);
    }*/
}
