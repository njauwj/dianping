package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_STOCK_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author wj
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;


    /*
    查询博客
     */
    @Override
    public Result getBlogById(Long id) {
        Blog blog = this.getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        handleBlog(blog);
        return Result.ok(blog);
    }

    /*
    点赞博客
    一人只能点一次赞,再次点赞取消点赞，redis set结构能够满足，集合并且唯一
    以blog id作为key，点赞用户id作为集合元素
     */
    @Override
    public Result praiseBlog(Long id) {
        Blog blog = this.getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        //1. 获取登入用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        /*
         2. 判断是否点赞（查询redis）
          - 已点赞 liked - 1 从redis集合移除该用户
          - 未点赞 liked + 1 并保存用户到redis集合
         */
        ZSetOperations<String, String> ops = stringRedisTemplate.opsForZSet();
        String key = BLOG_LIKED_KEY + id;
        Double score = ops.score(key, userId.toString());
        if (score != null) {
            boolean success = this.update().setSql("liked = liked - 1").eq("id", id).update();
            if (success) {
                ops.remove(key, userId.toString());
            }
        } else {
            //update tb_blog set liked = liked + 1 where id = ?
            boolean success = this.update().setSql("liked = liked + 1").eq("id", id).update();
            if (success) {
                ops.add(key, userId.toString(), System.currentTimeMillis());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryHotBlogs(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::handleBlog);
        return Result.ok(records);
    }

    /**
     * 点赞排行榜
     * @param id 博客ID
     * @return
     */
    @Override
    public Result blogLikedList(Long id) {
        String key = BLOG_LIKED_KEY + id;
        ZSetOperations<String, String> ops = stringRedisTemplate.opsForZSet();
        Set<String> members = ops.range(key, 0, 10);
        if (members == null || members.isEmpty()) {
            return Result.ok(new ArrayList<UserDTO>());
        }
        List<User> userList = members.stream()
                .map(Long::valueOf)
                .map(userId -> userService.getById(userId))
                .collect(Collectors.toList());
        List<UserDTO> userDTOs = userList.stream()
                .map(user -> {
                    UserDTO userDTO = new UserDTO();
                    userDTO.setId(user.getId());
                    userDTO.setIcon(user.getIcon());
                    return userDTO;
                })
                .collect(Collectors.toList());
        return Result.ok(userDTOs);
    }

    /**
     * 获取博主的笔记
     *
     * @param id      博主ID
     * @param current 当前页码
     * @return 博客列表
     */
    @Override
    public Result getUserBlog(Long id, Integer current) {
        Page<Blog> page = new Page<>(current, SystemConstants.MAX_PAGE_SIZE);
        List<Blog> blogList = query().eq("user_id", id).page(page).getRecords();
        return Result.ok(blogList);
    }

    /**
     * 保存博客 并推送给关注我的人
     *
     * @param blog 博客
     * @return 博客Id
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        blog.setUserId(userId);
        // 保存探店博文
        save(blog);
        // 返回id
        Long blogId = blog.getId();
        List<Follow> followList = followService.query().eq("user_id", userId).list();
        ZSetOperations<String, String> ops = stringRedisTemplate.opsForZSet();
        for (Follow follow : followList) {
            //推送博客
            Long id = follow.getFollowUserId();
            String key = BLOG_STOCK_KEY + id;
            ops.add(key, blogId.toString(), System.currentTimeMillis());
        }
        return Result.ok(blogId);
    }

    /**
     * 推送博客
     *
     * @param lastId 当前时间戳 : minTime
     * @param offset 偏移量 第一次为0
     * @return 博客列表
     */
    @Override
    public Result followBlog(Long lastId, Integer offset) {
        Long loginUserId = UserHolder.getUser().getId();
        String key = BLOG_STOCK_KEY + loginUserId;
        //记录上次查询的时间lastId
        Set<ZSetOperations.TypedTuple<String>> tupleSet = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, lastId, offset, 3);
        if (tupleSet == null || tupleSet.isEmpty()) {
            return Result.ok();
        }
        //减少扩容机制，提升性能
        List<Long> blogIds = new ArrayList<>(tupleSet.size());
        int os = 1;
        long minTime = 0;
        for (ZSetOperations.TypedTuple<String> tuple : tupleSet) {
            //时间戳
            long curTime = tuple.getScore().longValue();
            if (curTime == minTime) {
                os++;
            } else {
                minTime = curTime;
                os = 1;
            }
            //博客ID
            blogIds.add(Long.valueOf(tuple.getValue()));
        }
        List<Blog> blogList = blogIds.stream().map(this::getById).collect(Collectors.toList());
        blogList.forEach(this::handleBlog);
        ScrollResult scrollResult = new ScrollResult(blogList, minTime, os);
        return Result.ok(scrollResult);
    }

    public void handleBlog(Blog blog) {
        //手动维护 icon  name isLike字段
        UserDTO loginUser = UserHolder.getUser();
        if (loginUser != null) {
            //判断当前用户是否给这篇博客点赞
            String key = BLOG_LIKED_KEY + blog.getId();
            ZSetOperations<String, String> ops = stringRedisTemplate.opsForZSet();
            Double score = ops.score(key, loginUser.getId().toString());
            blog.setIsLike(score != null);
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }
}
