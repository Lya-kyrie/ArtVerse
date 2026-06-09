package com.artverse.common.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解（Redis 滑动窗口）。
 * <p>
 * 用法：
 * <pre>
 * &#64;RateLimit(windowSeconds = 60, maxRequests = 10, key = "login")
 * public SaTokenInfo login(...) { ... }
 * </pre>
 *
 * 三维键：{@code userId + ":" + endpoint + ":" + key(可选) + ":" + modelId(可选)}<br>
 * - 未登录接口（如 /api/auth/login）：key 用 IP 或注解的 key<br>
 * - 已登录接口：key 用 userId
 *
 * @see <a href="docs/knowledge/modules/auth/references/rate-limit-rules.md">限流规则详情</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 窗口秒数（默认 60s） */
    int windowSeconds() default 60;

    /** 窗口内最大请求数（默认 30） */
    int maxRequests() default 30;

    /** 业务 key（用于多端点区分） */
    String key() default "";

    /** 模型 ID（图像/聊天端点用于按模型分别限流） */
    String modelId() default "";
}
