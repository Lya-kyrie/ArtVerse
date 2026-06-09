package com.artverse.common.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 单飞（single-flight）幂等注解。
 * <p>
 * 同一用户对同一端点的并发请求：第一个执行，其余等待并复用结果。
 * <p>
 * 用法：
 * <pre>
 * &#64;SingleFlight(ttlSeconds = 30, key = "generate-manga")
 * public SseEmitter generateManga(...) { ... }
 * </pre>
 *
 * 适用场景：图像生成、AI 重写分镜、模型推理等昂贵操作。
 *
 * @see <a href="docs/knowledge/modules/auth/references/single-flight.md">单飞模式详情</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SingleFlight {

    /** 锁的 TTL（秒），超过则视为超时释放；默认 30s */
    int ttlSeconds() default 30;

    /** 业务 key（用于多端点区分） */
    String key() default "";
}
