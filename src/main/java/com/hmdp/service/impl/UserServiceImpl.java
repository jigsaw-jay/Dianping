package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SMSUtils;
import com.hmdp.utils.ValidateCodeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
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
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     */
    @Override
    public Result sendCode(String phone) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.不符合->返回错误信息
            return Result.fail("手机号错误！");
        }
        //3.符合->生成验证码
        String code = ValidateCodeUtils.generateValidateCode(6).toString();
        log.info("验证码->{}", code);
        //调用阿里云短信服务API
        //SMSUtils.sendMessage("周嘉靖的博客", "SMS_463905651", phone, code);
        //4.保存验证码到redis，并设置有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        return Result.ok("发送成功");
    }

    /**
     * 短信验证码登录功能
     */
    @Override
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        //1.从redis中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        //2.校验手机号和验证码
        if (RegexUtils.isPhoneInvalid(phone)||cacheCode == null || !cacheCode.toString().equals(code)) {
            //3.不一致，报错
            return Result.fail("手机或验证码错误！");
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
        //7.存在->随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //8.将User对象转为userDTO对象并转为Hash存储,自定义copy信息将Long类型的ID转为String类型
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //9.存储到redis,并设置有效期
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
        //10.返回token
        return Result.ok(token);
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
