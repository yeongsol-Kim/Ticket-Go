package com.yeongsol.ticketgo.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import redis.embedded.RedisServer;

import java.io.IOException;

/**
 * 로컬 개발용 Embedded Redis 설정
 * Docker 없이 Redis 사용 가능
 */
@Slf4j
@Configuration
@Profile("local")
public class EmbeddedRedisConfig {

    @Value("${spring.data.redis.port:6370}")
    private int redisPort;

    private RedisServer redisServer;

    @PostConstruct
    public void startRedis() throws IOException {
        try {
            redisServer = new RedisServer(redisPort);
            redisServer.start();
            log.info("Embedded Redis started on port {}", redisPort);
        } catch (Exception e) {
            log.warn("Failed to start embedded Redis: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stopRedis() {
        if (redisServer != null && redisServer.isActive()) {
            redisServer.stop();
            log.info("Embedded Redis stopped");
        }
    }
}
