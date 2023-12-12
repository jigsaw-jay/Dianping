package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将Java对象序列化为json并存储在String类型中
     * 并且可以设置TTL
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 将任意Java对象序列化为json并存储在string类型的key中
     * 并且可以设置逻辑过期时间
     */
    public void setLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 封装缓存穿透方法
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从Redis查询缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在(三种情况:1.不为null和"" 2.为null 3.为"")
        if (StrUtil.isNotBlank(Json)) {
            //3.存在(1.不为null和"")->将Json转为type对象->返回type信息
            return JSONUtil.toBean(Json, type);
        }
        //判断命中的是否为空值(3.为"")
        if (Json != null) {
            //返回错误信息
            return null;
        }
        //4.不存在->根据id查询数据库
        R apply = dbFallback.apply(id);
        //5.判断数据库中商品是否存在
        if (apply == null) {
            //6.不存在->将空值写入Redis->返回错误信息
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //7.存在->将商铺数据转为JSON写入Redis,并添加TTL->返回商铺信息
        this.set(key, apply, time, unit);
        return apply;
    }

    /**
     * 封装互斥锁方法
     */
    public <R, ID> R queryWithMutex(String keyPrefix,  ID id,
                                    Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String Key = keyPrefix + id;
        String lockKey = LOCK_SHOP_KEY + id;
        //1.从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(Key);
        //2.判断是否存在(三种情况:1.不为null和"" 2.为null 3.为"")
        if (StrUtil.isNotBlank(json)) {
            //3.存在(1.不为null和"")->将Json转为Shop对象->返回商铺信息
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否为空值(3.为"")
        if (json != null) {
            //返回错误信息
            return null;
        }
        //实现缓存重建
        //4.未命中->尝试获取互斥锁
        R apply = null;
        try {
            boolean isLock = tryLock(lockKey);
            //5.判断是否成功获取互斥锁
            if (!isLock) {
                //6.失败->休眠一段时间->重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            //7.成功->再次检测Redis->根据id查询数据库->将数据写入Redis
            String Json2 = stringRedisTemplate.opsForValue().get(Key);
            if (StrUtil.isNotBlank(Json2)) {
                return JSONUtil.toBean(Json2, type);
            }
            apply = dbFallback.apply(id);
            //7.1判断数据库中商品是否存在
            if (apply == null) {
                //7.2不存在->将空值写入Redis->返回错误信息
                stringRedisTemplate.opsForValue().set(Key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //7.3存在->将商铺数据转为JSON写入Redis,并添加TTL->返回商铺信息
            stringRedisTemplate.opsForValue().set(Key, JSONUtil.toJsonStr(apply), time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //8.释放互斥锁
            unLock(lockKey);
        }
        return apply;
    }

    /**
     * 逻辑过期方法
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String Key = keyPrefix + id;
        String lockKey = LOCK_SHOP_KEY + id;
        //1.从Redis查询缓存
        String Json = stringRedisTemplate.opsForValue().get(Key);
        //2.判断缓存是否命中
        if (StrUtil.isBlank(Json)) {
            //3.未命中->返回空->结束
            return null;
        }
        //4.命中->JSON反序列化->判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R bean = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.缓存未过期->返回商铺信息
            return bean;
        }
        //6.缓存过期->尝试获取互斥锁
        boolean isLock = tryLock(lockKey);
        //7.判断是否获取互斥锁
        if (isLock) {
            //8.获取成功->开启独立线程，实现缓存重建->释放锁->返回店铺信息
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    R apply = dbFallback.apply(id);
                    this.setLogicalExpire(Key,apply,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //9.获取失败->返回过期商铺信息
        return bean;
    }

    /**
     * 创建锁
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 创建线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
}
