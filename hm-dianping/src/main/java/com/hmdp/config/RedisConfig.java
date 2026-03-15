package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient() {
        // 1. 创建配置对象
        Config config = new Config();

        // 2. 添加单机 Redis 配置
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379");

        // 3. 创建 RedissonClient 实例
        return Redisson.create(config);
    }
}
