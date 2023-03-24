package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author wj
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        //查询redis中有没有店铺类型缓存
        String s = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE);
        if (s != null) {
            List<ShopType> shopTypes = JSONUtil.toList(s, ShopType.class);
            return Result.ok(shopTypes);
        }
        //缓存中没有查询数据库
        LambdaQueryWrapper<ShopType> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.orderByAsc(ShopType::getSort);
        List<ShopType> shopTypes = this.list(queryWrapper);
        if (shopTypes.size() <= 0) {
            return Result.fail("暂无店铺分类");
        }
        //将店铺分类保存到redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE, JSONUtil.parseArray(shopTypes).toString());
        //返回
        return Result.ok(shopTypes);
    }
}
