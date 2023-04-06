package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

/**
 * @author: wj
 * @create_time: 2023/3/29 11:54
 * @explain:
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 实现关注功能
     *
     * @param userId   博主Id
     * @param followed true 关注 false 取消关注
     * @return ok
     */
    @Override
    public Result follow(Long userId, Boolean followed) {
        UserDTO loginUser = UserHolder.getUser();
        if (loginUser == null) {
            return Result.fail("请登入");
        }
        Long loginUserId = loginUser.getId();
        String key = FOLLOW_KEY + loginUserId;
        if (Boolean.FALSE.equals(followed)) {
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, loginUserId);
            boolean success = remove(queryWrapper);
            if (success) {
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        } else {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(loginUserId);
            boolean success = save(follow);
            if (success) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        }
        return Result.ok("关注成功");
    }

    /**
     * @param userId 博主Id
     * @return 是否关注 关注了 true 没关注 false
     */
    @Override
    public Result isFollowed(Long userId) {
        UserDTO loginUser = UserHolder.getUser();
        if (loginUser == null) {
            return Result.ok(false);
        }
        Long loginUserId = loginUser.getId();
        String key = FOLLOW_KEY + loginUserId;
        Boolean result = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        return Result.ok(BooleanUtil.isTrue(result));
    }

    /**
     * @param userId 博主Id
     * @return 共同关注的人 UserDTO
     */
    @Override
    public Result commonFollowed(Long userId) {
        UserDTO loginUser = UserHolder.getUser();
        if (loginUser == null) {
            return Result.ok(new ArrayList<UserDTO>());
        }
        Long loginUserId = loginUser.getId();
        String key1 = FOLLOW_KEY + loginUserId;
        String key2 = FOLLOW_KEY + userId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(new ArrayList<UserDTO>());
        }
        List<User> userList = intersect.stream().map(Long::valueOf).map(id -> userService.getById(id)).collect(Collectors.toList());
        List<UserDTO> userDTOList = userList.stream().map(user -> {
            UserDTO userDTO = new UserDTO();
            BeanUtil.copyProperties(user, userDTO);
            return userDTO;
        }).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }
}
