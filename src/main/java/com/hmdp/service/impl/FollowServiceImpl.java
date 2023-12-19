package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;

    /**
     * 是否关注
     *
     * @param followUserId
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询
        LambdaQueryWrapper<Follow> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Follow::getFollowUserId, followUserId).eq(Follow::getUserId, userId);
        long count = count(lqw);
        return Result.ok(count > 0);
    }

    /**
     * 关注+取关
     *
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取当前登录用户ID,构建key
        Long userId = UserHolder.getUser().getId();
        String followKey = FOLLOW_KEY + userId;
        //1.判断是关注or取关
        if (isFollow) {
            //2.关注->新增
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean saveSuccess = save(follow);
            if (saveSuccess) {
                //3.关注->把关注用户的id放入sortedSet集合 zadd followKey followerUserId currentTime
                stringRedisTemplate.opsForZSet().add(followKey, followUserId.toString(), System.currentTimeMillis());
            }
        } else {
            //4.取关->删除(delete from tb_follow where userId=? and follow user_id=?)
            LambdaQueryWrapper<Follow> lqw = new LambdaQueryWrapper<>();
            lqw.eq(Follow::getFollowUserId, followUserId);
            lqw.eq(Follow::getUserId, userId);
            boolean removeSuccess = remove(lqw);
            if (removeSuccess) {
                //5.取关->把关注的用户ID从redis移除
                stringRedisTemplate.opsForZSet().remove(followKey, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 共同关注
     *
     * @param id
     * @return
     */
    @Override
    public Result commonFollow(Long id) {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取当前用户key,目标用户key
        String userKey = FOLLOW_KEY + userId;
        String targetKey = FOLLOW_KEY + id;
        //3.获取sortedSet集合的交集
        Set<String> intersect = stringRedisTemplate.opsForZSet().intersect(userKey, targetKey);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //4.解析ID
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //5.查询用户
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
