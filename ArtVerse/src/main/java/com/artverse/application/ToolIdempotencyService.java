package com.artverse.application;

import com.artverse.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Lightweight Redis-backed idempotency guard for storyboard write tools.
 *
 * <p>Each write tool invocation produces a deterministic key from
 * {@code requestId + toolName + sha256(inputSummary)}. If the same key
 * is seen again within the TTL window, the cached result is returned
 * instead of re-executing the write.</p>
 *
 * <p>This prevents duplicate writes caused by retry storms while still
 * allowing intentional re-execution with different inputs.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolIdempotencyService {

    private static final String KEY_PREFIX = "artverse:tool:idem:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Check whether a tool call with the given parameters has already
     * been executed for this request.
     *
     * @param requestId   the agent run request ID
     * @param toolName    the tool name (e.g. "save_storyboard")
     * @param inputSummary a short summary of input parameters for hashing
     * @return the cached result if present, empty otherwise
     */
    public Optional<Map<String, Object>> lookup(UUID requestId, String toolName, String inputSummary) {
        String key = idemKey(requestId, toolName, inputSummary);
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof Map<?, ?> raw) {
            if ("PROCESSING".equals(raw.get("_status"))) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) raw;
            log.info("Tool idempotency hit: tool={} requestId={}", toolName, requestId);
            return Optional.of(result);
        }
        return Optional.empty();
    }

    /**
     * Store the result of a tool call for future idempotency checks.
     * Only stores metadata (counts, status flags) — not the full content.
     */
    public void store(UUID requestId, String toolName, String inputSummary, Map<String, Object> result) {
        String key = idemKey(requestId, toolName, inputSummary);
        Map<String, Object> cachedResult = new LinkedHashMap<>(result == null ? Map.of() : result);
        cachedResult.put("_status", "COMPLETED");
        cachedResult.put("idempotent", true);
        cachedResult.put("tool", toolName);
        cachedResult.put("requestId", String.valueOf(requestId));
        redisTemplate.opsForValue().set(key, cachedResult, CACHE_TTL);
        log.info("Tool idempotency stored: tool={} requestId={}", toolName, requestId);
    }

    public Map<String, Object> execute(UUID requestId, String toolName, String inputFingerprint,
                                       Callable<Map<String, Object>> action) {
        Optional<Map<String, Object>> cached = lookup(requestId, toolName, inputFingerprint);
        if (cached.isPresent()) {
            return cached.get();
        }

        String key = idemKey(requestId, toolName, inputFingerprint);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key, Map.of("_status", "PROCESSING", "tool", toolName), CACHE_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            cached = lookup(requestId, toolName, inputFingerprint);
            if (cached.isPresent()) {
                return cached.get();
            }
            throw new BusinessException(409, "The same storyboard operation is already running");
        }

        try {
            Map<String, Object> result = action.call();
            store(requestId, toolName, inputFingerprint, result);
            return result;
        } catch (RuntimeException error) {
            redisTemplate.delete(key);
            throw error;
        } catch (Exception error) {
            redisTemplate.delete(key);
            throw new IllegalStateException("Storyboard tool execution failed", error);
        }
    }

    /**
     * Compute a SHA-256 hex hash of the input string for use in the idempotency key.
     */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String idemKey(UUID requestId, String toolName, String inputHash) {
        return KEY_PREFIX + requestId + ":" + toolName + ":" + inputHash;
    }
}
