package com.flashgrid.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Registers the Lua script that performs the whole purchase check
 * (stock check + duplicate-buyer check + decrement) as ONE atomic Redis operation.
 * Redis runs a Lua script without interleaving any other command,
 * which is what prevents overselling under heavy concurrency.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisScript<Long> flashPurchaseScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/flash_purchase.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /** Lua script used by RateLimiterService (fixed-window counter per user). */
    @Bean
    public RedisScript<Long> rateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/rate_limit.lua"));
        script.setResultType(Long.class);
        return script;
    }
}