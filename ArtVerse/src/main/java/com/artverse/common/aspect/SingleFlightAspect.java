package com.artverse.common.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 单飞（single-flight）幂等 AOP。
 * <p>
 * 实现：
 * - 进程内：ConcurrentHashMap 持有 inflight Future
 * - 分布式：Redis SETNX 占位锁（防止多实例同时跑）
 * - 第一个请求执行业务逻辑；后续相同 key 的请求等待并复用结果
 *
 * @see <a href="docs/knowledge/modules/auth/references/single-flight.md">单飞模式详情</a>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class SingleFlightAspect {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ArtVerseProperties properties;

    // 进程内 inflight：key -> Future
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    @Around("@annotation(com.artverse.common.aspect.SingleFlight)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (!properties.getSingleFlight().isEnabled()) {
            return pjp.proceed();
        }

        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        SingleFlight annotation = method.getAnnotation(SingleFlight.class);

        int ttl = annotation.ttlSeconds() > 0
                ? annotation.ttlSeconds()
                : properties.getSingleFlight().getDefaultTtlSeconds();
        String bizKey = annotation.key();

        String userId = resolveUserId();
        String flightKey = String.format("sf:%s:%s:%s:%s",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                userId,
                bizKey);

        // 1. 进程内快速路径：已经有 inflight → 等待并复用
        CompletableFuture<Object> existing = inflight.get(flightKey);
        if (existing != null) {
            log.info("SingleFlight [in-process] wait: key={}", flightKey);
            return existing.get(ttl, TimeUnit.SECONDS);
        }

        // 2. Redis 分布式锁占位
        String lockKey = flightKey + ":lock";
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(ttl));
        if (Boolean.FALSE.equals(acquired)) {
            // 其他实例正在跑，抛 409 提示客户端重试（避免长轮询）
            log.info("SingleFlight [redis] busy: key={}", flightKey);
            throw new BusinessException(409, "相同请求正在处理中，请稍后重试");
        }

        // 3. 自己是第一个，创建 Future 并执行业务
        CompletableFuture<Object> future = new CompletableFuture<>();
        CompletableFuture<Object> prev = inflight.putIfAbsent(flightKey, future);
        if (prev != null) {
            // 极小概率并发：另一个线程刚 put，复用其 future
            redisTemplate.delete(lockKey);
            return prev.get(ttl, TimeUnit.SECONDS);
        }

        try {
            Object result = pjp.proceed();
            future.complete(result);
            return result;
        } catch (Throwable t) {
            future.completeExceptionally(t);
            throw t;
        } finally {
            inflight.remove(flightKey);
            redisTemplate.delete(lockKey);
        }
    }

    private String resolveUserId() {
        try {
            if (StpUtil.isLogin()) {
                return "u" + StpUtil.getLoginIdAsLong();
            }
        } catch (Exception ignored) {
        }
        return "anon";
    }
}
