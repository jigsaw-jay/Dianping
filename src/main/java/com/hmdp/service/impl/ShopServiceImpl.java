package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询商铺（添加Redis缓存,并实现缓存穿透+缓存击穿）
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //用互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }
    /**
     * 封装缓存穿透+缓存击穿方法
     */
    public Shop queryWithMutex(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        //1.从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断是否存在(三种情况:1.不为null和"" 2.为null 3.为"")
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在(1.不为null和"")->将Json转为Shop对象->返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否为空值(3.为"")
        if (shopJson != null) {
            //返回错误信息
            return null;
        }
        //实现缓存重建
        //4.未命中->尝试获取互斥锁
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //5.判断是否成功获取互斥锁
            if (!isLock) {
                //6.失败->休眠一段时间->重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //7.成功->再次检测Redis->根据id查询数据库->将数据写入Redis
            String shopJson2 = stringRedisTemplate.opsForValue().get(shopKey);
            if (StrUtil.isNotBlank(shopJson2)) {
                Shop shop2 = JSONUtil.toBean(shopJson2, Shop.class);
                return shop2;
            }
            shop = getById(id);
            //7.1判断数据库中商品是否存在
            if (shop == null) {
                //7.2不存在->将空值写入Redis->返回错误信息
                stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //7.3存在->将商铺数据转为JSON写入Redis,并添加TTL->返回商铺信息
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //8.释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    /**
     * 封装缓存穿透方法
     */
    public Shop queryWithPassThrough(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;
        //1.从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断是否存在(三种情况:1.不为null和"" 2.为null 3.为"")
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在(1.不为null和"")->将Json转为Shop对象->返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否为空值(3.为"")
        if (shopJson != null) {
            //返回错误信息
            return null;
        }
        //4.不存在->根据id查询数据库
        Shop shop = getById(id);
        //5.判断数据库中商品是否存在
        if (shop == null) {
            //6.不存在->将空值写入Redis->返回错误信息
            stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //7.存在->将商铺数据转为JSON写入Redis,并添加TTL->返回商铺信息
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    //创建锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 更新商铺信息，加入缓存更新策略
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺ID不能为空！！");
        }
        String shopKey = CACHE_SHOP_KEY + shop.getId();
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(shopKey);
        return Result.ok();
    }
}
