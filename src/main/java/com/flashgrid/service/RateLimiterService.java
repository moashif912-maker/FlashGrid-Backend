package com.flashgrid.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private static final String KEY_PREFIX = "flash:ratelimit:user:";
    private static final int MAX_REQUESTS = 5;
    private static final int WINDOW_SECONDS = 10;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> rateLimitScript;

    /** Throws 429 Too Many Requests if the user has exceeded the limit. */
    public void checkUser(Long userId) {
        Long allowed = redis.execute(
                rateLimitScript,
                List.of(KEY_PREFIX + userId),
                String.valueOf(WINDOW_SECONDS),
                String.valueOf(MAX_REQUESTS));

        if (allowed != null && allowed == 0L) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests. Please wait a few seconds and try again.");
        }
    }
}