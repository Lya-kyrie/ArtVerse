package com.artverse.application;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final String TOKEN_PREFIX = "rt:token:";
    private static final String USER_PREFIX = "rt:user:";
    private static final String USED_PREFIX = "rt:used:";
    private static final String LEGACY_PREFIX = "rt:";
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofHours(12);
    private static final long REFRESH_TOKEN_TIMEOUT_SECONDS = 12 * 3600;

    private final StringRedisTemplate redis;

    public RefreshTokenService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String issue(long userId) {
        String token = UUID.randomUUID().toString();
        String tokenHash = hash(token);
        redis.opsForValue().set(tokenKey(tokenHash), Long.toString(userId), REFRESH_TOKEN_TTL);
        redis.opsForSet().add(userKey(userId), tokenHash);
        redis.expire(userKey(userId), REFRESH_TOKEN_TTL);
        return token;
    }

    public Consumption consume(String token, Long authenticatedUserId) {
        if (token == null || token.isBlank()) {
            return Consumption.invalid();
        }

        String tokenHash = hash(token);
        String storedUserId = redis.opsForValue().getAndDelete(tokenKey(tokenHash));

        // Tokens issued before the reverse index was introduced can still be
        // rotated while their access token remains valid.
        if (storedUserId == null && authenticatedUserId != null) {
            Boolean deleted = redis.delete(LEGACY_PREFIX + authenticatedUserId + ":" + token);
            if (Boolean.TRUE.equals(deleted)) {
                markUsed(tokenHash, authenticatedUserId);
                return Consumption.valid(authenticatedUserId);
            }
        }

        if (storedUserId == null) {
            Long reusedBy = parseUserId(redis.opsForValue().get(USED_PREFIX + tokenHash));
            return reusedBy == null ? Consumption.invalid() : Consumption.reused(reusedBy);
        }

        Long userId = parseUserId(storedUserId);
        if (userId == null) {
            return Consumption.invalid();
        }
        markUsed(tokenHash, userId);
        redis.opsForSet().remove(userKey(userId), tokenHash);
        return Consumption.valid(userId);
    }

    public void revokeAll(long userId) {
        Set<String> tokenHashes = redis.opsForSet().members(userKey(userId));
        List<String> keys = new ArrayList<>();
        if (tokenHashes != null) {
            tokenHashes.forEach(tokenHash -> keys.add(tokenKey(tokenHash)));
        }
        keys.add(userKey(userId));
        redis.delete(keys);

        // Remove tokens created by the previous storage layout during the
        // migration window. This path disappears once all 12-hour tokens age out.
        Set<String> legacyKeys = redis.keys(LEGACY_PREFIX + userId + ":*");
        if (legacyKeys != null && !legacyKeys.isEmpty()) {
            redis.delete(legacyKeys);
        }
    }

    public long getTimeoutSeconds() {
        return REFRESH_TOKEN_TIMEOUT_SECONDS;
    }

    private void markUsed(String tokenHash, long userId) {
        redis.opsForValue().set(USED_PREFIX + tokenHash, Long.toString(userId), REFRESH_TOKEN_TTL);
    }

    private static String tokenKey(String tokenHash) {
        return TOKEN_PREFIX + tokenHash;
    }

    private static String userKey(long userId) {
        return USER_PREFIX + userId;
    }

    private static Long parseUserId(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public enum ConsumptionStatus {
        VALID,
        REUSED,
        INVALID
    }

    public record Consumption(ConsumptionStatus status, Long userId) {
        public static Consumption valid(long userId) {
            return new Consumption(ConsumptionStatus.VALID, userId);
        }

        public static Consumption reused(long userId) {
            return new Consumption(ConsumptionStatus.REUSED, userId);
        }

        public static Consumption invalid() {
            return new Consumption(ConsumptionStatus.INVALID, null);
        }
    }
}
