package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
    @Autowired
    private IFollowService followService;

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
        LambdaQueryWrapper<User> lqw = new LambdaQueryWrapper<>();
        lqw.in(User::getId, ids).orderByDesc(User::getId);
        List<User> users = userService.list(lqw);
        List<UserDTO> userDTOS = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    /**
     * 根据UserId分页查询blog
     *
     * @param id
     * @param current
     * @return
     */
    @Override
    public Result queryBlogByUserId(Long id, Integer current) {
        Page<Blog> pageInfo = new Page(current, SystemConstants.MAX_PAGE_SIZE);
        LambdaQueryWrapper<Blog> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Blog::getUserId, id).orderByDesc(Blog::getCreateTime);
        Page<Blog> page = page(pageInfo, lqw);
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 存储blog到数据库+Redis
     *
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        // 2.保存blog至数据库
        boolean saveSuccess = save(blog);
        if (!saveSuccess) {
            return Result.fail("新增blog失败！");
        }
        // 3.查询blog主的所有关注者
        Long blogId = blog.getId();
        LambdaQueryWrapper<Follow> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Follow::getFollowUserId, userId);
        List<Follow> follows = followService.list(lqw);
        // 4.推送笔记Id给所有关注者
        for (Follow follow : follows) {
            //4.1获取粉丝Id
            Long followUserId = follow.getUserId();
            //4.2推送
            String feedKey = FEED_KEY + followUserId;
            stringRedisTemplate.opsForZSet().add(feedKey, blogId.toString(), System.currentTimeMillis());
        }
        // 5.返回blogId
        return Result.ok(blogId);
    }

    /**
     * 关注推送页面的分页查询(滚动分页)
     *
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result scrollPage(Long max, Integer offset) {
        //1.获取当前登录用户，得到key
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        //2.查询收件箱（feed:id）ZREVRANGEBYSCORE key max min [WITHSCORES] [LIMIT offset count]
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.1判断是否为空->为空直接返回
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //3.2解析收件箱数据（blogId+score+offset）
        ArrayList<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; //设置初始最小时间为0
        int os = 1; //设置初始offset=1，记录最小时间相同的元素个数，第二次分页时跳过
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //4.获取blogId
            ids.add(Long.valueOf(typedTuple.getValue()));
            //5.获取score->时间戳
            long time = typedTuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        //4.根据Id 获取blog->补充blog用户信息+是否被登录用户点赞！！
        LambdaQueryWrapper<Blog> lqw = new LambdaQueryWrapper<>();
        lqw.in(Blog::getId, ids).orderByDesc(Blog::getId);
        List<Blog> blogList = list(lqw);
        for (Blog blog : blogList) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        //5.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
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
