package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.mapper.SeckillVoucherMapper;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserServiceImpl service;

    /**
     * 生成token存入Redis，保存为TXT
     */
    @Test
    void testGetToken() throws IOException {
        for (int i = 1; i < 1011; i++) {
            int id = i;
            User user = service.getById(id);
            if (user!=null){
                String token = UUID.randomUUID().toString(true);
                UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
                Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
                String tokenKey = LOGIN_USER_KEY + token;
                FileWriter fw=new FileWriter("redis.txt",true);
                fw.write(token+"\n");
                fw.close();
                stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
                stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
            }
        }
    }
}
