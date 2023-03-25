package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
Redisson配置类
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host}")
    private String redisHost;

    @Value("${spring.redis.port}")
    private Integer redisPort;

    @Value("${spring.redis.password}")
    private String redisPassword;

    @Value("${spring.redis.database}")
    private Integer redisDatabase;

    /*
    当我们使用@Bean注解创建一个Bean时，Spring会将该Bean的生命周期管理交给容器，包括初始化和销毁。
    如果我们在@Bean注解中指定了destroyMethod属性，则Spring会在Bean初始化时动态生成一个代理对象，
    用于管理该Bean的销毁操作。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setPassword(redisPassword)
                .setDatabase(redisDatabase);
        return Redisson.create(config);
    }
}