package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RedisData工具类，用于存储Redis数据
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
