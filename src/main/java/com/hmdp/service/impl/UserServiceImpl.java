package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.ValidateCodeUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cglib.core.Local;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
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
        if (RegexUtils.isPhoneInvalid(phone) || cacheCode == null || !cacheCode.toString().equals(code)) {
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
     * 退出登录
     *
     * @return
     */
    @Override
    public Result logout(HttpServletRequest request) {
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        //2.删除ThreadLocal中的user信息
        UserHolder.removeUser();
        //3.判断token是否为null->为null直接返回
        if (token == null) {
            return Result.fail("用户未登录！");
        }
        //4.不为null->去Redis中删除该token
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.delete(tokenKey);
        return Result.ok("退出成功！");
    }

    /**
     * 根据Id查询用户信息
     *
     * @param id
     * @return
     */
    @Override
    public Result queryUserById(Long id) {
        User user = getById(id);
        if (user == null) {
            return Result.fail("查无此人");
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    /**
     * 签到功能
     *
     * @return
     */
    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取当前日期->进行格式转换
        LocalDateTime now = LocalDateTime.now();
        String dateKey = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //3.拼接signKey =USER_SIGN_KEY+日期
        String signKey = USER_SIGN_KEY + userId + dateKey;
        //4.获取今天是本月第几天(1-31 -> 0-30)
        int dayOfMonth = now.getDayOfMonth() - 1;
        //5.写入Redis
        stringRedisTemplate.opsForValue().setBit(signKey, dayOfMonth, true);
        return Result.ok();
    }

    /**
     * 补签功能
     *
     * @param dateTime
     * @return
     */
    @Override
    public Result reSign(LocalDateTime dateTime) {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取要补签的日期->进行格式转换
        String dateKey = dateTime.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //3.拼接signKey =USER_SIGN_KEY+日期
        String signKey = USER_SIGN_KEY + userId + dateKey;
        //4.获取补签的日期是本月第几天(1-31 -> 0-30)
        int dayOfMonth = dateTime.getDayOfMonth() - 1;
        //5.写入Redis
        stringRedisTemplate.opsForValue().setBit(signKey, dayOfMonth, true);
        return Result.ok();
    }

    /**
     * 统计签到功能
     *
     * @return
     */
    @Override
    public Result countSign() {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.获取当前时间
        LocalDateTime now = LocalDateTime.now();
        String dateKey = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //3.拼接Key
        String signKey = USER_SIGN_KEY + userId + dateKey;
        //4.根据key和当前日期获取Redis中十进制数->BITFIELD sign:1010:202312 GET u21 0
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(signKey,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType
                                .unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        //5.取出结果，循环遍历
        Long num = result.get(0);
        if (num == null || num == 0) return Result.ok(0);
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                break;
            } else {
                count++;
            }
            num >>>= 1; //无符号右移
        }
        return Result.ok(count);
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
