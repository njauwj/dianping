package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author wj
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        Shop shop = cacheBreakdown(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /*
    逻辑过期解决缓存击穿
     */
    public Shop logicExpire(Long id) {
        //查询redis
        String key = CACHE_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(key);
        //缓存没有返回空
        if (StrUtil.isBlank(s)) {
            return null;
        }
        //有缓存判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(s, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //未过期直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        //已过期，尝试获取锁，未拿到锁返回旧数据，拿到锁，开辟子线程进行缓存重建，主线程直接返回旧数据
        //获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        //已上锁直接返回旧数据
        if (!lock(lockKey)) {
            return shop;
        }
        //未上锁，开启子线程进行缓存重建
        CACHE_REBUILD_EXECUTOR.submit(
                () -> {
                    try {
                        //重建缓存
                        this.saveShopToRedis(id, 20L);

                    } catch (Exception exception) {
                        throw new RuntimeException(exception.getMessage());
                    } finally {
                        //释放锁
                        unlock(lockKey);
                    }
                }
        );
        //主线程返回旧数据
        return shop;
    }

    /*
    缓存预热
     */
    public void saveShopToRedis(Long id, Long expireTime) {
        //查询数据库
        Shop shop = this.getById(id);
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        //保存redis
        String key = CACHE_SHOP_KEY + id;
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /*
    通过互斥锁解决缓存击穿问题
     */
    public Shop cacheBreakdown(Long id) {
        //互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        String key = CACHE_SHOP_KEY + id;
        try {
            //先查询redis
            String cache = stringRedisTemplate.opsForValue().get(key);
            //判断缓存是否为空
            if (StrUtil.isNotBlank(cache)) {
                //有缓存直接返回
                return JSONUtil.toBean(cache, Shop.class);
            }
            if (cache != null) {
                return null;
            }
            //缓存为空时拿到锁准备进行缓存更新
            boolean lock = lock(lockKey);
            if (!lock) {//如果上锁了，就休眠一会重新访问redis
                Thread.sleep(50);
                return cacheBreakdown(id);
            }
            //没有上锁则查询数据库并更新redis缓存
            Shop shop = this.getById(id);
            //模拟重建延迟
            Thread.sleep(200);
            if (shop == null) {
                return null;
            }
            //将查询结果放入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            //返回
            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException(e.getMessage());
        } finally {
            //释放锁
            unlock(lockKey);
        }
    }


    /*
    通过互斥锁解决缓存击穿问题
    判断是否上锁，已上锁返回 false 未上锁返回true
     */
    public boolean lock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        //防止自动拆箱发生空指针
        return BooleanUtil.isTrue(flag);
    }

    /*
    解锁
     */
    public void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    /*
    缓存穿透解决办法，但数据库和缓存中都没有的数据被查询，将空值写入redis并设置过期时间，减少数据库压力
     */
    public Shop cachePierceThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //先查询redis
        String cache = stringRedisTemplate.opsForValue().get(key);
        //判断缓存是否为空
        if (StrUtil.isNotBlank(cache)) {
            //有缓存直接返回
            return JSONUtil.toBean(cache, Shop.class);
        }
        if (cache != null) {//表示数据库和缓存中都不存在该数据
            return null;
        }
        //缓存没有则查询数据库
        Shop shop = this.getById(id);
        if (shop == null) {
            //将空值写入redis，减少缓存穿透的概率
            //缓存穿透就是redis和数据库都没有的数据，请求会直接向数据库不停的访问，造成数据库压力
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //将查询结果放入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return shop;

    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if (shop == null || shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        //先更新数据库在删缓存比先删缓存在更新数据库在高并发的环境下更安全
        this.updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
