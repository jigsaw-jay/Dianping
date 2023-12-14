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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    /**
     * 实现优惠券秒杀下单
     */
    @Override
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
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
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
    }

    /**
     * 创建订单
     */
    @Transactional
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

//        voucher.setStock(voucher.getStock()-1);
//        boolean success = seckillVoucherService.updateById(voucher);

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
    }
}
