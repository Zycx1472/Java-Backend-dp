package com.hmdp.config;

import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;

@Slf4j
@Configuration
public class PreloadConfig {

    @Bean
    public CommandLineRunner preloadDatabase(DataSource dataSource) {
        return args -> {
            // 项目启动后立即获取一个连接，强制 HikariPool 完成握手和初始化
            try (Connection conn = dataSource.getConnection()) {
                log.info("Starting: 数据库预热完成");
            } catch (Exception e) {
                log.error("Warning: 数据库预热失败", e);
            }
        };
    }
}