package com.artverse.api.dto;

/**
 * 鉴权 DTO（Sa-Token 方案）。
 * <p>
 * 不再需要 accessToken / refreshToken 字段：Sa-Token 登录后由响应 Header 返回 token。
 * 前端从 Header 读 {@code satoken}。
 */
public class AuthDtos {

    public record RegisterRequest(String username, String email, String password) {}

    public record LoginRequest(String username, String password) {}

    public record UserInfo(Long id, String username, String email) {}

    public record ApiKeyRequest(String provider, String apiKey) {}

    public record ApiKeyResponse(String provider, String apiKeyMasked) {}
}
