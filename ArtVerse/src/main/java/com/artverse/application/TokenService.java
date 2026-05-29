package com.artverse.application;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
public class TokenService {

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    private final SecretKey secretKey;
    private final StringRedisTemplate redis;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public TokenService(
            @Value("${artverse.jwt.secret}") String secret,
            @Value("${artverse.jwt.access-ttl}") String accessTtlRaw,
            @Value("${artverse.jwt.refresh-ttl}") String refreshTtlRaw,
            StringRedisTemplate redis) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.redis = redis;
        this.accessTtl = parseDuration(accessTtlRaw);
        this.refreshTtl = parseDuration(refreshTtlRaw);
    }

    public record TokenPair(String accessToken, String refreshToken) {}

    public TokenPair generateTokens(Long userId, String username) {
        String accessJti = UUID.randomUUID().toString();
        String refreshJti = UUID.randomUUID().toString();

        Date now = new Date();
        Date accessExp = new Date(now.getTime() + accessTtl.toMillis());
        Date refreshExp = new Date(now.getTime() + refreshTtl.toMillis());

        String accessToken = Jwts.builder()
                .id(accessJti)
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("type", "access")
                .issuedAt(now)
                .expiration(accessExp)
                .signWith(secretKey)
                .compact();

        String refreshToken = Jwts.builder()
                .id(refreshJti)
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(refreshExp)
                .signWith(secretKey)
                .compact();

        return new TokenPair(accessToken, refreshToken);
    }

    public Claims verifyAccessToken(String token) {
        Claims claims = parseToken(token);
        if (!"access".equals(claims.get("type"))) {
            throw new JwtException("Not an access token");
        }
        if (isBlacklisted(claims.getId())) {
            throw new JwtException("Token has been revoked");
        }
        return claims;
    }

    public Claims verifyRefreshToken(String token) {
        Claims claims = parseToken(token);
        if (!"refresh".equals(claims.get("type"))) {
            throw new JwtException("Not a refresh token");
        }
        return claims;
    }

    public String refreshAccessToken(String refreshToken) {
        Claims claims = verifyRefreshToken(refreshToken);
        Long userId = Long.parseLong(claims.getSubject());
        String username = claims.get("username", String.class);

        Date now = new Date();
        Date accessExp = new Date(now.getTime() + accessTtl.toMillis());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("type", "access")
                .issuedAt(now)
                .expiration(accessExp)
                .signWith(secretKey)
                .compact();
    }

    public void blacklist(String accessToken) {
        try {
            Claims claims = parseToken(accessToken);
            if (!"access".equals(claims.get("type"))) return;
            String jti = claims.getId();
            long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (remaining > 0) {
                redis.opsForValue().set(BLACKLIST_PREFIX + jti, "1", Duration.ofMillis(remaining));
            }
        } catch (JwtException e) {
            // Token already expired or invalid — no need to blacklist
        }
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(BLACKLIST_PREFIX + jti));
    }

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static Duration parseDuration(String raw) {
        raw = raw.trim().toLowerCase();
        try {
            if (raw.endsWith("m")) return Duration.ofMinutes(Long.parseLong(raw.replace("m", "")));
            if (raw.endsWith("h")) return Duration.ofHours(Long.parseLong(raw.replace("h", "")));
            if (raw.endsWith("d")) return Duration.ofDays(Long.parseLong(raw.replace("d", "")));
            return Duration.ofMinutes(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            log.warn("Invalid duration: {}, defaulting to 30m", raw);
            return Duration.ofMinutes(30);
        }
    }
}
