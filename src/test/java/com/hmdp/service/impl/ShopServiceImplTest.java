package com.hmdp.service.impl;

import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author: wj
 * @create_time: 2023/3/4 20:59
 * @explain:
 */
@SpringBootTest
class ShopServiceImplTest {

    @Autowired
    private ShopServiceImpl shopService;


    @Test
    void name() {
        shopService.saveShopToRedis(1L,30L);
    }
}