package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String PROCESSING_MESSAGE = "请求正在处理中";

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final ArtVerseProperties properties;

    public Map<String, Object> executeHttp(String action, String userId, Map<String, Object> canonicalPayload,
                                           Callable<Map<String, Object>> leader) {
        if (!properties.getIdempotency().isEnabled()) {
            return callLeader(leader);
        }

        incrementStat(action, "total");
        String key = buildKey(action, userId, canonicalPayload);
        String followersKey = key + ":followers";
        String channel = key + ":channel";

        Map<String, Object> existing = readState(key);
        if (isSucceeded(existing)) {
            incrementStat(action, "success_hit");
            return resultWithHit(existing);
        }
        if (isFailed(existing)) {
            incrementStat(action, "failed_hit");
            throw new BusinessException(502, String.valueOf(existing.getOrDefault("error", "Request failed")));
        }
        if (isProcessing(existing)) {
            return follow(action, key, followersKey, channel);
        }

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key + ":lock",
                "1",
                Duration.ofSeconds(properties.getIdempotency().getProcessingTtlSeconds())
        );
        if (!Boolean.TRUE.equals(acquired)) {
            return follow(action, key, followersKey, channel);
        }

        try {
            incrementStat(action, "leader");
            writeState(key, Map.of("status", STATUS_PROCESSING, "startedAt", System.currentTimeMillis()),
                    properties.getIdempotency().getProcessingTtlSeconds());

            Map<String, Object> result = callLeader(leader);
            writeState(key, Map.of("status", STATUS_SUCCEEDED, "finishedAt", System.currentTimeMillis(), "result", result),
                    properties.getIdempotency().getSuccessTtlSeconds());
            publish(channel);
            return result;
        } catch (RuntimeException e) {
            incrementStat(action, "failed");
            writeState(key, Map.of(
                    "status", STATUS_FAILED,
                    "finishedAt", System.currentTimeMillis(),
                    "error", e.getMessage() == null ? "Request failed" : e.getMessage()
            ), properties.getIdempotency().getFailureTtlSeconds());
            publish(channel);
            throw e;
        } finally {
            redisTemplate.delete(key + ":lock");
        }
    }

    public void rejectIfProcessing(String action, String userId, Map<String, Object> canonicalPayload) {
        if (!properties.getIdempotency().isEnabled()) return;
        incrementStat(action, "total");
        String key = buildKey(action, userId, canonicalPayload);
        if (isProcessing(readState(key))) {
            incrementStat(action, "processing_rejected");
            throw new BusinessException(409, PROCESSING_MESSAGE);
        }
    }

    public void markProcessing(String action, String userId, Map<String, Object> canonicalPayload) {
        if (!properties.getIdempotency().isEnabled()) return;
        String key = buildKey(action, userId, canonicalPayload);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key + ":lock",
                "1",
                Duration.ofSeconds(properties.getIdempotency().getProcessingTtlSeconds())
        );
        if (!Boolean.TRUE.equals(acquired)) {
            incrementStat(action, "processing_rejected");
            throw new BusinessException(409, PROCESSING_MESSAGE);
        }
        incrementStat(action, "leader");
        writeState(key, Map.of("status", STATUS_PROCESSING, "startedAt", System.currentTimeMillis()),
                properties.getIdempotency().getProcessingTtlSeconds());
    }

    public void markSucceeded(String action, String userId, Map<String, Object> canonicalPayload, Object result) {
        if (!properties.getIdempotency().isEnabled()) return;
        String key = buildKey(action, userId, canonicalPayload);
        writeState(key, Map.of("status", STATUS_SUCCEEDED, "finishedAt", System.currentTimeMillis(), "result", result),
                properties.getIdempotency().getSuccessTtlSeconds());
        publish(key + ":channel");
        redisTemplate.delete(key + ":lock");
    }

    public void markFailed(String action, String userId, Map<String, Object> canonicalPayload, String error) {
        if (!properties.getIdempotency().isEnabled()) return;
        String key = buildKey(action, userId, canonicalPayload);
        incrementStat(action, "failed");
        writeState(key, Map.of(
                "status", STATUS_FAILED,
                "finishedAt", System.currentTimeMillis(),
                "error", error == null ? "Request failed" : error
        ), properties.getIdempotency().getFailureTtlSeconds());
        publish(key + ":channel");
        redisTemplate.delete(key + ":lock");
    }

    public String imageHash(String base64) {
        if (base64 == null || base64.isBlank()) return "";
        String data = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        byte[] bytes = Base64.getDecoder().decode(data);
        return "sha256:" + sha256Hex(bytes);
    }

    public String normalizeText(String value) {
        if (value == null) return "";
        return value.trim().replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\t ]+", " ");
    }

    private Map<String, Object> follow(String action, String key, String followersKey, String channel) {
        Long followers = redisTemplate.opsForValue().increment(followersKey);
        redisTemplate.expire(followersKey, Duration.ofSeconds(properties.getIdempotency().getFollowerWaitSeconds() + 10L));
        if (followers != null && followers > properties.getIdempotency().getMaxFollowers()) {
            redisTemplate.opsForValue().decrement(followersKey);
            incrementStat(action, "follower_rejected");
            throw new BusinessException(409, PROCESSING_MESSAGE);
        }
        incrementStat(action, "follower");

        try {
            Map<String, Object> current = readState(key);
            if (isSucceeded(current)) {
                incrementStat(action, "success_hit");
                return resultWithHit(current);
            }
            if (isFailed(current)) {
                incrementStat(action, "failed_hit");
                throw new BusinessException(502, String.valueOf(current.getOrDefault("error", "Request failed")));
            }

            CountDownLatch latch = new CountDownLatch(1);
            MessageListener listener = (Message message, byte[] pattern) -> latch.countDown();
            ChannelTopic topic = new ChannelTopic(channel);
            try {
                listenerContainer.addMessageListener(listener, topic);
                current = readState(key);
                if (isSucceeded(current)) {
                    incrementStat(action, "success_hit");
                    return resultWithHit(current);
                }
                if (isFailed(current)) {
                    incrementStat(action, "failed_hit");
                    throw new BusinessException(502, String.valueOf(current.getOrDefault("error", "Request failed")));
                }
                boolean notified = latch.await(properties.getIdempotency().getFollowerWaitSeconds(), TimeUnit.SECONDS);
                if (!notified) {
                    throw new BusinessException(409, PROCESSING_MESSAGE);
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException(409, PROCESSING_MESSAGE);
            } finally {
                listenerContainer.removeMessageListener(listener, topic);
            }

            Map<String, Object> done = readState(key);
            if (isSucceeded(done)) {
                incrementStat(action, "success_hit");
                return resultWithHit(done);
            }
            if (isFailed(done)) {
                incrementStat(action, "failed_hit");
                throw new BusinessException(502, String.valueOf(done.getOrDefault("error", "Request failed")));
            }
            throw new BusinessException(409, PROCESSING_MESSAGE);
        } finally {
            redisTemplate.opsForValue().decrement(followersKey);
        }
    }

    private Map<String, Object> resultWithHit(Map<String, Object> state) {
        Object result = state.get("result");
        Map<String, Object> map = objectMapper.convertValue(result, new TypeReference<>() {});
        map.put("idempotent_hit", true);
        return map;
    }

    private Map<String, Object> callLeader(Callable<Map<String, Object>> leader) {
        try {
            return leader.call();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, e.getMessage() == null ? "Request failed" : e.getMessage());
        }
    }

    private String buildKey(String action, String userId, Map<String, Object> canonicalPayload) {
        return "idem:v1:" + action + ":" + userId + ":" +
                sha256Hex(canonicalJson(canonicalPayload).getBytes(StandardCharsets.UTF_8));
    }

    private String canonicalJson(Map<String, Object> payload) {
        try {
            JsonNode normalized = normalizeNode(objectMapper.valueToTree(payload));
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to normalize idempotency payload");
        }
    }

    private JsonNode normalizeNode(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return node;
        if (node.isArray()) {
            var array = objectMapper.createArrayNode();
            node.forEach(item -> array.add(normalizeNode(item)));
            return array;
        }
        ObjectNode object = objectMapper.createObjectNode();
        node.fieldNames().forEachRemaining(name -> object.set(name, normalizeNode(node.get(name))));
        return object;
    }

    private Map<String, Object> readState(String key) {
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse idempotency state {}: {}", key, e.getMessage());
            return null;
        }
    }

    private void writeState(String key, Map<String, Object> state, int ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(state), Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to write idempotency state");
        }
    }

    private void publish(String channel) {
        redisTemplate.convertAndSend(channel, "done");
    }

    private void incrementStat(String action, String field) {
        try {
            redisTemplate.opsForHash().increment("idem:stats:" + action, field, 1);
        } catch (Exception e) {
            log.debug("Failed to increment idempotency stat {}.{}: {}", action, field, e.getMessage());
        }
    }

    private boolean isProcessing(Map<String, Object> state) {
        return state != null && STATUS_PROCESSING.equals(state.get("status"));
    }

    private boolean isSucceeded(Map<String, Object> state) {
        return state != null && STATUS_SUCCEEDED.equals(state.get("status"));
    }

    private boolean isFailed(Map<String, Object> state) {
        return state != null && STATUS_FAILED.equals(state.get("status"));
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to hash idempotency payload");
        }
    }
}
