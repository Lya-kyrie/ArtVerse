package com.artverse.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SlidingWindowRateLimiter {

    private static final DefaultRedisScript<Long> TOUCH_AND_COUNT_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[2])
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return redis.call('ZCARD', KEYS[1])
            """, Long.class);

    private static final DefaultRedisScript<Long> COUNT_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
            return redis.call('ZCARD', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RateLimitResult incrementAndCheck(String key, int windowSeconds, int maxRequests) {
        long count = increment(key, windowSeconds);
        return new RateLimitResult(count, count <= maxRequests);
    }

    public long increment(String key, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowSeconds * 1000L;
        String member = now + ":" + UUID.randomUUID();
        Long count = redisTemplate.execute(
                TOUCH_AND_COUNT_SCRIPT,
                List.of(key),
                String.valueOf(now),
                String.valueOf(windowStart),
                member,
                String.valueOf(windowSeconds + 5)
        );
        return count == null ? 0L : count;
    }

    public long count(String key, int windowSeconds) {
        long windowStart = System.currentTimeMillis() - windowSeconds * 1000L;
        Long count = redisTemplate.execute(
                COUNT_SCRIPT,
                List.of(key),
                String.valueOf(windowStart)
        );
        return count == null ? 0L : count;
    }

    public void clear(String key) {
        redisTemplate.delete(key);
    }

    public record RateLimitResult(long count, boolean allowed) {
    }
}
