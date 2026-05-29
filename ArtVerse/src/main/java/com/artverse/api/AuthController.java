package com.artverse.api;

import com.artverse.api.dto.AuthDtos.*;
import com.artverse.application.AuthService;
import com.artverse.application.TokenService;
import com.artverse.application.TokenService.TokenPair;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;

    @PostMapping("/register")
    public ResponseEntity<TokenResponse> register(@RequestBody RegisterRequest req) {
        TokenPair tokens = authService.register(req.username(), req.email(), req.password());
        Claims claims = tokenService.verifyAccessToken(tokens.accessToken());
        UserInfo user = new UserInfo(
                Long.parseLong(claims.getSubject()),
                claims.get("username", String.class),
                null);
        return ResponseEntity.ok(new TokenResponse(tokens.accessToken(), tokens.refreshToken(), user));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest req) {
        TokenPair tokens = authService.login(req.username(), req.password());
        Claims claims = tokenService.verifyAccessToken(tokens.accessToken());
        UserInfo user = new UserInfo(
                Long.parseLong(claims.getSubject()),
                claims.get("username", String.class),
                null);
        return ResponseEntity.ok(new TokenResponse(tokens.accessToken(), tokens.refreshToken(), user));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(@RequestBody RefreshRequest req) {
        String newAccessToken = tokenService.refreshAccessToken(req.refreshToken());
        return ResponseEntity.ok(Map.of("access_token", newAccessToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.substring(7).trim();
        tokenService.blacklist(token);
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }
}
