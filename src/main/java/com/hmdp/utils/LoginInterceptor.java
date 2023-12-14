package com.hmdp.utils;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 根据ThreadLocal是否有该用户信息，判断是否拦截
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.判断是否需要拦截（即判断ThreadLocal中是否有该用户）
        if (UserHolder.getUser() == null) {
            //2.没有->需要拦截
            response.setStatus(401);
            return false;
        }
        //3.有->放行
        return true;
    }
}
