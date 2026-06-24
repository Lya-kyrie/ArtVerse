package com.artverse.common.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final StringRedisTemplate redisTemplate;
    private final ArtVerseProperties properties;

    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[2])
            local count = redis.call('ZCARD', KEYS[1])
            if count >= tonumber(ARGV[3]) then
                return count
            end
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            return count + 1
            """, Long.class);

    @Around("@annotation(com.artverse.common.aspect.RateLimit)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (!properties.getRateLimit().isEnabled()) {
            return pjp.proceed();
        }

        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        RateLimit annotation = method.getAnnotation(RateLimit.class);

        int window = annotation.windowSeconds() > 0
                ? annotation.windowSeconds()
                : properties.getRateLimit().getDefaultWindowSeconds();
        int maxReq = annotation.maxRequests() > 0
                ? annotation.maxRequests()
                : properties.getRateLimit().getDefaultMaxRequests();
        String bizKey = annotation.key();
        String modelId = annotation.modelId();

        String redisKey = String.format("rl:%s:%s:%s:%s",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                resolveUserKey(),
                bizKey + (modelId.isEmpty() ? "" : ":" + modelId));

        long now = System.currentTimeMillis();
        long windowStart = now - window * 1000L;
        String member = now + ":" + UUID.randomUUID();

        Long count = redisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                List.of(redisKey),
                String.valueOf(now),
                String.valueOf(windowStart),
                String.valueOf(maxReq),
                member,
                String.valueOf(window + 5)
        );

        if (count != null && count > maxReq) {
            log.warn("Rate limit hit: key={}, count={}, max={}", redisKey, count, maxReq);
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }

        return pjp.proceed();
    }

    private String resolveUserKey() {
        try {
            if (StpUtil.isLogin()) {
                return "u" + StpUtil.getLoginIdAsLong();
            }
        } catch (Exception ignored) {
        }
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            String ip = req.getHeader("X-Forwarded-For");
            if (ip == null || ip.isBlank()) {
                ip = req.getRemoteAddr();
            } else {
                ip = ip.split(",")[0].trim();
            }
            return "ip:" + ip;
        }
        return "anon";
    }
}
