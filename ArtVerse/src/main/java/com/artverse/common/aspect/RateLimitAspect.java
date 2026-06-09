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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 限流 AOP 实现（Redis 滑动窗口 + Lua 脚本）。
 * <p>
 * 算法：ZSET 滑动窗口<br>
 * - 每次请求：计算窗口内 [now - windowSeconds, now] 的成员数<br>
 * - 超过 maxRequests → 抛 429 Too Many Requests<br>
 * - 否则添加新成员（score = now, member = uuid）并刷新 TTL
 *
 * @see <a href="docs/knowledge/modules/auth/references/rate-limit-rules.md">限流规则详情</a>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ArtVerseProperties properties;

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

        String userKey = resolveUserKey();
        String redisKey = String.format("rl:%s:%s:%s:%s",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                userKey,
                bizKey + (modelId.isEmpty() ? "" : ":" + modelId));

        long now = System.currentTimeMillis();
        long windowStart = now - window * 1000L;
        String member = UUID.randomUUID().toString();

        // Redis 滑动窗口
        ZSetOperations<String, Object> zset = redisTemplate.opsForZSet();
        // 1. 移除窗口外的成员
        zset.removeRangeByScore(redisKey, 0, windowStart);
        // 2. 计数窗口内成员
        Long count = zset.zCard(redisKey);
        if (count != null && count >= maxReq) {
            log.warn("Rate limit hit: key={}, count={}, max={}", redisKey, count, maxReq);
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
        // 3. 添加新成员
        zset.add(redisKey, member, now);
        // 4. 刷新 TTL（窗口长度 + 缓冲）
        redisTemplate.expire(redisKey, java.time.Duration.ofSeconds(window + 5));

        return pjp.proceed();
    }

    private String resolveUserKey() {
        // 已登录：userId；未登录：client IP
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
            }
            return "ip:" + ip;
        }
        return "anon";
    }
}
