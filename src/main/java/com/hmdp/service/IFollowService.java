package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;

/**
 * @author: wj
 * @create_time: 2023/3/29 11:53
 * @explain:
 */
public interface IFollowService extends IService<Follow> {
    Result follow(Long userId, Boolean followed);

    Result isFollowed(Long userId);

    Result commonFollowed(Long userId);
}
