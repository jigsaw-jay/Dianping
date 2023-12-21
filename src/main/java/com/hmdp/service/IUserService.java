package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.Date;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {
    Result sendCode(String phone);

    Result login(LoginFormDTO loginForm);

    Result logout(HttpServletRequest request);

    Result queryUserById(Long id);

    Result sign();

    Result reSign(LocalDateTime dateTime);

    Result countSign();
}
