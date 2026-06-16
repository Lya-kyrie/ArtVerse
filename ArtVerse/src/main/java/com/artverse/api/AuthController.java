package com.artverse.api;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.artverse.api.dto.AuthDtos.*;
import com.artverse.application.AuthService;
import com.artverse.common.BusinessException;
import com.artverse.common.aspect.RateLimit;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 鉴权 Controller（Sa-Token 方案）。
 * <p>
 * Header 约定：{@code satoken: <token>}<br>
 * - 登录：POST /api/auth/login → 返回 SaTokenInfo<br>
 * - 注册：POST /api/auth/register → 返回 SaTokenInfo<br>
 * - 登出：POST /api/auth/logout → 销毁当前 token<br>
 * - 刷新：POST /api/auth/refresh → 同 token 续期（active-timeout=-1 时不需主动刷新）<br>
 * - 踢人：POST /api/auth/kickout（管理员）<br>
 *
 * @see <a href="docs/knowledge/modules/auth/SKILL.md">auth 模块 Skill</a>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 注册（限流：60s 内 5 次）
     */
    @PostMapping("/register")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "register")
    public SaTokenInfo register(@RequestBody RegisterRequest req) {
        User user = authService.register(req.username(), req.email(), req.password());
        StpUtil.login(user.getId(), "PC");
        log.info("User registered: id={}, username={}", user.getId(), user.getUsername());
        return StpUtil.getTokenInfo();
    }

    /**
     * 登录（限流：60s 内 10 次）
     */
    @PostMapping("/login")
    @RateLimit(windowSeconds = 60, maxRequests = 10, key = "login")
    public SaTokenInfo login(@RequestBody LoginRequest req) {
        User user = authService.login(req.username(), req.password());
        StpUtil.login(user.getId(), "PC");
        log.info("User logged in: id={}, username={}", user.getId(), user.getUsername());
        return StpUtil.getTokenInfo();
    }

    /**
     * 登出：仅销毁当前 token（不影响其他端）
     */
    @PostMapping("/logout")
    public SaTokenInfo logout() {
        StpUtil.logout();
        return StpUtil.getTokenInfo();
    }

    /**
     * 刷新 token（active-timeout=-1 时不需调用此端点；保留以备将来扩展）
     */
    @PostMapping("/refresh")
    public SaTokenInfo refresh() {
        if (!StpUtil.isLogin()) {
            throw new BusinessException(401, "未登录");
        }
        // 续期 token
        StpUtil.renewTimeout(3600);
        return StpUtil.getTokenInfo();
    }

    /**
     * 管理员踢人下线（强制销毁该 userId 的所有 token）
     */
    @PostMapping("/kickout")
    public void kickout(@RequestParam Long userId) {
        StpUtil.kickout(userId);
        log.warn("User kicked out: id={}", userId);
    }

    /**
     * 当前用户信息
     */
    @GetMapping("/me")
    public Object me() {
        if (!StpUtil.isLogin()) {
            throw new BusinessException(401, "未登录");
        }
        return StpUtil.getTokenInfo();
    }
}
