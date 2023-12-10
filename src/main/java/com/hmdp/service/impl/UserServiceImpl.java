package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.ValidateCodeUtils;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.不符合->返回错误信息
            return Result.fail("手机号错误！");
        }
        //3.符合->生成验证码
        String code = ValidateCodeUtils.generateValidateCode(4).toString();
        log.info("验证码->{}", code);
        //调用阿里云短信服务API
        //SMSUtils.sendMessage("周嘉靖的博客", "SMS_463905651", phone, code);
        //4.保存到session
        session.setAttribute("code", code);
        //5.发送验证码
        return Result.ok("发送成功");
    }

    /**
     * 登录校验
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合
            return Result.fail("手机号输入错误！");
        }
        //2.校验验证码
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            //3.不一致，报错
            return Result.fail("验证码错误！");
        }
        //4.一致，根据手机号查询用户
        LambdaQueryWrapper<User> lqw = new LambdaQueryWrapper<>();
        lqw.eq(User::getPhone, phone);
        User user = getOne(lqw);
        //5.判断用户是否存在
        if (user == null) {
            //6.不存在->创建新用户->保存到数据库->保存到session
            user = createUserWithPhone(phone);
        }
        //7.存在->保存用户到session
        session.setAttribute("user", user);
        return Result.ok("登录成功！");
    }

    /**
     * 创建新用户
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
