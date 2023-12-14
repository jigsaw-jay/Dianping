package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import com.hmdp.service.ILock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Redis分布式锁初级
 */
@Slf4j
public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock() {
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取锁
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        //ID_PREFIX+线程标识 组成线程ID
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//防止空指针风险
    }

    @Override
    public void unlock() {
        //调用Lua脚本
        //参数为：脚本 + KEYS[]集合 + ARGS[]集合
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }


    /**
     * 判断线程ID-->释放锁
     */
/*    @Override
    public void unlock() {
        //获取线程ID
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁中的线程ID
        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断是否一致->一致删除不一致不管
        if (threadId.equals(lockId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }else {
            log.info("不是你的锁！");
        }
    }*/
}
