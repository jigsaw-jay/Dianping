package com.hmdp.config;


import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient() {
        //新建配置类
        Config config = new Config();
        //添加Redis地址，这里添加了单点的地址，也可以使用config.useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://192.168.13.140:6379").setPassword("123258");
        //创建客户端
        return Redisson.create(config);
    }
}
