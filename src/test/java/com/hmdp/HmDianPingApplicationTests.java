package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sound.midi.MidiFileFormat;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserServiceImpl service;
    @Autowired
    private IShopService shopService;

    /**
     * 生成token存入Redis，保存为TXT
     */
    @Test
    void testGetToken() throws IOException {
        for (int i = 1; i < 1011; i++) {
            int id = i;
            User user = service.getById(id);
            if (user != null) {
                String token = UUID.randomUUID().toString(true);
                UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
                Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
                String tokenKey = LOGIN_USER_KEY + token;
                FileWriter fw = new FileWriter("redis.txt", true);
                fw.write(token + "\n");
                fw.close();
                stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
                stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
            }
        }
    }

    @Autowired
    private RedissonClient redissonClient;

    @Test
    void testRedisson() throws InterruptedException {
        //获取锁（可重入），指定锁的名称
        RLock anyLock = redissonClient.getLock("anyLock");
        //尝试获取锁，参数分别是：获取锁的最大等待时间（期间会重试），锁自动释放时间，时间单位
        boolean tryLock = anyLock.tryLock(1, 100, TimeUnit.SECONDS);
        //判断是否成功获取锁
        if (tryLock) {
            try {
                System.out.println("成功->执行业务");
            } finally {
                //释放锁
                anyLock.unlock();
            }
        }
    }

    @Test
    void testLoad() {
        //1.查询店铺信息
        List<Shop> shopList = shopService.list();
        //2.按照typeId对店铺进行分组
        Map<Long, List<Shop>> map = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //3.1获取类型Id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //3.2获取同类型店铺集合
            List<Shop> value = entry.getValue();

            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("test", values);
            }
        }
        System.out.println(stringRedisTemplate.opsForHyperLogLog().size("test"));
    }
}
