package com.flashgrid.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson client used for distributed locks.
 * Reads the same spring.data.redis.* properties as Spring Data Redis,
 * so Redis is configured in one place (application.yml).
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionPoolSize(32)
                .setConnectionMinimumIdleSize(8)
                .setConnectTimeout(5000)
                .setTimeout(3000);

        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }

        return Redisson.create(config);
    }
}