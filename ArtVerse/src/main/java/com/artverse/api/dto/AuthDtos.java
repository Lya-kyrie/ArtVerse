package com.artverse.api.dto;

public class AuthDtos {

    public record RegisterRequest(String username, String email, String password) {}
    public record LoginRequest(String username, String password) {}
    public record RefreshRequest(String refreshToken) {}
    public record TokenResponse(String accessToken, String refreshToken, UserInfo user) {}
    public record UserInfo(Long id, String username, String email) {}
    public record ApiKeyRequest(String provider, String apiKey) {}
    public record ApiKeyResponse(String provider, String apiKeyMasked) {}
}
