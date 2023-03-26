package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * @author: wj
 * @create_time: 2023/3/22 19:13
 * @explain:
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private IVoucherOrderService proxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit((Runnable) () -> {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        });
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        proxy.creatVoucherOrder(voucherOrder);
    }

    @Transactional
    public void creatVoucherOrder(VoucherOrder voucherOrder) {
        Long voucherId = voucherOrder.getVoucherId();
        seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).update();
        this.save(voucherOrder);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1. 执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        //2. 判断返回结果是否为 0
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "限购一单");
        }
        long orderId = redisIdWorker.nextId("order");
        //3. 把下单ID，保存到阻塞队列中(也就是对数据库的操作),开辟子线程异步操作
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4. 返回订单ID 给用户
        return Result.ok(orderId);
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        if (seckillVoucher == null) {
//            return Result.fail("优惠劵不存在");
//        }
//        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
//            return Result.fail("秒杀活动未开始");
//        }
//        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
//            return Result.fail("秒杀活动已结束");
//        }
//        Integer stock = seckillVoucher.getStock();
//        if (stock < 1) {
//            return Result.fail("优惠券不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        // crearVoucherOrder(voucherId);这样子事务是无法生效的，事务是基于动态代理实现的，应该由动态代理对象调用 crearVoucherOrder 事务才能生效
//        // userId.toString()的底层原理还是new String（）所以不能保持锁的对象一致，intern（）是去字符串常量池里取，只要有都是同一个对象
//        //synchronized 一定要包含事务，因为如果在事务的方法体内会导致事务还未提交，但锁已经释放
//        /*
//        synchronized关键字只是在当前JVM实例中起作用，而在集群环境中，不同的JVM实例之间是无法共享同一个锁的。具体来说，当多个请求同时时，
//        如果这些请求被分配到不同的JVM实例中，那么每个JVM实例都会创建自己的锁对象，这样就无法起到同步的作用。
//        因此，在集群高并发模式下，使用synchronized关键字需要格外注意，需要考虑到分布式环境下的并发问题。所以需要分布式锁。
//         */
////        synchronized (userId.toString().intern()) {
////            //拿到代理对象
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.crearVoucherOrder(voucherId);
////        }
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        //获取锁,内部采用hash数据结构存储实现了可重入
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean success = lock.tryLock();
//        if (!success) {
//            return Result.fail("一人限购一单");
//        }
//        //拿到代理对象
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.creatVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }


    @Transactional
    public Result creatVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //一人一单防止黄牛,高并发情况下会出现一人购买多单的情况,使用悲观锁解决
        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(VoucherOrder::getUserId, userId).eq(VoucherOrder::getVoucherId, voucherId);
        int count = this.count(queryWrapper);
        if (count >= 1) {
            return Result.fail("限购一单");
        }
        //更新秒杀卷库存
        //乐观锁解决超卖问题，将stock作为version,cas法
        //悲观锁执行慢，而乐观锁执行成功率低
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            return Result.fail("优惠券不足");
        }
        //更新订单数据
        /*
            id '主键' 采用全剧唯一ID
            user_id '下单的用户id',
            voucher_id '购买的代金券id',
         */
        VoucherOrder voucherOrder = new VoucherOrder();
        long nextId = redisIdWorker.nextId("order");
        voucherOrder.setId(nextId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        this.save(voucherOrder);
        //返回全局唯一ID
        return Result.ok(nextId);
    }

}
