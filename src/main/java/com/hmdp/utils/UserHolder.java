package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * 使用了ThreadLocal来保存用户信息;
 * ThreadLocal是一个线程局部变量，它提供了线程的局部变量;
 * 每个访问该变量的线程都会获得其对应的局部变量副本;
 * 可以在整个应用程序中方便地访问和管理用户信息;
 * 而不用担心线程安全性问题，因为每个线程都有自己独立的用户信息副本，互不干扰;
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
