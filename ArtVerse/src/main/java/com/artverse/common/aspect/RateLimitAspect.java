package com.artverse.common.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.security.ClientIpResolver;
import com.artverse.security.SlidingWindowRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final ArtVerseProperties properties;
    private final SlidingWindowRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

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

        String endpointKey = bizKey == null || bizKey.isBlank()
                ? method.getDeclaringClass().getSimpleName() + ":" + method.getName()
                : bizKey;
        String redisKey = String.format("rl:%s:%s:%s",
                endpointKey,
                resolveUserKey(),
                modelId.isEmpty() ? "default" : modelId);

        SlidingWindowRateLimiter.RateLimitResult result = rateLimiter.incrementAndCheck(redisKey, window, maxReq);
        if (!result.allowed()) {
            log.warn("Rate limit hit: key={}, count={}, max={}", redisKey, result.count(), maxReq);
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
            return "ip:" + clientIpResolver.resolve(req);
        }
        return "anon";
    }
}
