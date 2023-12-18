package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * Blog分页查询
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 1.根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 2.获取当前页数据
        List<Blog> records = page.getRecords();
        // 3.查询用户 + 查询blog是否被当前用户点过赞
        for (Blog blog : records) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        return Result.ok(records);
    }

    /**
     * 点赞功能
     */
    @Override
    public Result likeBlog(Long id) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //1.1定义key
        String key = BLOG_LIKED_KEY + id;
        //2.判断当前用户是否点赞了
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3.未点赞->可以点赞
        if (score == null) {
            //3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            if (isSuccess) {
                //3.2 保存用户到Redis的sortedSet集合
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            } else {
                return Result.fail("不中勒！");
            }
        } else {
            //4.已点赞->取消点赞
            //4.1数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            if (isSuccess) {
                //4.2将用户从Redis集合删除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            } else {
                return Result.fail("不中勒！");
            }
        }
        return Result.ok();
    }

    /**
     * 查询top5点赞用户
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        //1.定义key
        String key = BLOG_LIKED_KEY + 23;
        //2.查询top5点赞用户 zrange key 0-4,如果为空则返回一个空集合防止空指针
        Set<String> top = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top == null || top.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //3.解析出其中的用户id
        List<Long> ids = top.stream().map(Long::valueOf).collect(Collectors.toList());
        //4.根据id查询用户集合，将user集合复制为userDTO集合，防止信息泄露
        LambdaQueryWrapper<User> lqw=new LambdaQueryWrapper<>();
        lqw.in(User::getId, ids).orderByDesc(User::getId);
        List<User> users = userService.list(lqw);
        List<UserDTO> userDTOS = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    /**
     * 根据id查询，查看笔记
     */
    @Override
    public Result queryBlogById(Long id) {
        //1.查询Blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("不存在该笔记！");
        }
        //2.查询Blog相关用户
        queryBlogUser(blog);
        //3.查询blog是否被当前用户点过赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 查询blog用户信息，并存入blog中
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * isBlogLiked判断blog是否被点过赞
     */
    private void isBlogLiked(Blog blog) {
        //1.获取当前用户
        UserDTO user = UserHolder.getUser();
        //2.是否获取成功，即当前是否登录
        if (user == null) {
            //未登录，无需查询是否点赞
            return;
        }
        Long userId = UserHolder.getUser().getId();
        //3.已登录->判断当前用户是否点过赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
