package com.hmdp;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private RedissonClient redissonClient;
    private RLock lock;

    @BeforeEach
    void setUp() {
        lock = redissonClient.getLock("order");
    }

    @Test
    void method1() throws InterruptedException {
        boolean isLock = lock.tryLock(1L,TimeUnit.SECONDS);
        if (!isLock) {
            log.error("获取锁失败------>1");
            return;
        }
        try {
            log.info("获取锁成功---->1");
            method2();
            log.info("开始执行业务---->1");
        } finally {
            log.warn("准备释放锁---->1");
            lock.unlock();
        }
    }
    void method2() {
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("获取锁失败------>2");
            return;
        }
        try {
            log.info("获取锁成功---->2");
            log.info("开始执行业务---->2");
        } finally {
            log.warn("准备释放锁---->2");
            lock.unlock();
        }
    }
}
