package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 拦截一切，token或user为空则直接放行
 * 不为空则将用户信息存入，并刷新token有效期
 */
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        //2.判断token是否为空（StrUtil.isBlank会检测token是否为null或空字符串，或者只包含空格）
        //token.isEmpty()仅仅检查字符串是否为空，不会考虑字符串只包含空格的情况
        if (StrUtil.isBlank(token)) {
            //3.为空直接放行
            return true;
        }
        //4.根据token获取redis中的用户
        String tokenKey =LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        //5.判断用户是否存在
        if (userMap.isEmpty()) {
            //6.不存在->直接放行
            return true;
        }
        //7.将查询到的Hash数据转为UserDto对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //8.存在->保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
        //9.刷新token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL, TimeUnit.SECONDS);
        //10.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex){
        //移除用户
        UserHolder.removeUser();
    }
}
