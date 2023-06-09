package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author wj
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result getBlogById(Long id);

    Result praiseBlog(Long id);

    Result queryHotBlogs(Integer current);

    Result blogLikedList(Long id);


    Result getUserBlog(Long id, Integer current);

    Result saveBlog(Blog blog);

    Result followBlog(Long lastId,Integer offset);

}
