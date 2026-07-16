package com.artverse.security;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Service
public class AuthRiskService {

    private final ArtVerseProperties properties;
    private final SlidingWindowRateLimiter rateLimiter;

    public AuthRiskService(ArtVerseProperties properties, SlidingWindowRateLimiter rateLimiter) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    public LoginRiskDecision recordLoginAttempt(String username, String clientIp) {
        ArtVerseProperties.Risk risk = properties.getAuth().getRisk();
        long ipCount = rateLimiter.increment(key("login-ip", clientIp), risk.getLoginIpWindowSeconds());
        long failureCount = rateLimiter.count(key("login-failure", username), risk.getLoginFailureWindowSeconds());
        boolean hardLimited = ipCount > risk.getLoginIpHardLimit();
        boolean challengeRequired = failureCount >= risk.getLoginFailureChallengeThreshold()
                || ipCount > risk.getLoginIpChallengeThreshold();
        return new LoginRiskDecision(challengeRequired, hardLimited, ipCount, failureCount);
    }

    public void recordLoginFailure(String username) {
        ArtVerseProperties.Risk risk = properties.getAuth().getRisk();
        rateLimiter.increment(key("login-failure", username), risk.getLoginFailureWindowSeconds());
    }

    public void clearLoginFailures(String username) {
        rateLimiter.clear(key("login-failure", username));
    }

    public boolean allowDegradedLogin(String username, String clientIp) {
        ArtVerseProperties.Risk risk = properties.getAuth().getRisk();
        long accountCount = rateLimiter.increment(key("login-degraded-account", username), risk.getDegradedLoginAccountWindowSeconds());
        long ipCount = rateLimiter.increment(key("login-degraded-ip", clientIp), risk.getDegradedLoginIpWindowSeconds());
        return accountCount <= risk.getDegradedLoginAccountLimit() && ipCount <= risk.getDegradedLoginIpLimit();
    }

    public void enforceRegistrationLimit(String clientIp) {
        ArtVerseProperties.Risk risk = properties.getAuth().getRisk();
        long ipCount = rateLimiter.increment(key("register-ip", clientIp), risk.getRegisterIpWindowSeconds());
        if (ipCount > risk.getRegisterIpLimit()) {
            throw BusinessException.withCode(429, "请求过于频繁，请稍后再试", AuthErrorCodes.AUTH_RATE_LIMITED);
        }
    }

    private String key(String category, String value) {
        return "auth:risk:" + category + ":" + hmac(category + ":" + normalize(value));
    }

    private String hmac(String raw) {
        String configuredKey = properties.getAuth().getRisk().getHmacKey();
        String key = configuredKey == null || configuredKey.isBlank()
                ? properties.getSecrets().getEncryptionKey()
                : configuredKey.trim();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash auth risk key", ex);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record LoginRiskDecision(
            boolean challengeRequired,
            boolean hardLimited,
            long ipCount,
            long failureCount
    ) {
    }
}
