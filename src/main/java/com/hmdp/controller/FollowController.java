package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author wj
 * @since 2021-12-22
 */
/*
实现关注功能
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    @PutMapping("/{userId}/{followed}")
    public Result follow(@PathVariable Long userId, @PathVariable Boolean followed) {
        return followService.follow(userId, followed);
    }

    @GetMapping("/or/not/{userId}")
    public Result isFollowed(@PathVariable Long userId) {
        return followService.isFollowed(userId);
    }

    @GetMapping("/common/{userId}")
    public Result commonFollowed(@PathVariable Long userId) {
        return followService.commonFollowed(userId);
    }
}
