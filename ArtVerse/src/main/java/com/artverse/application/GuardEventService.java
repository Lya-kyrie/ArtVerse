package com.artverse.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardEventService {

    private static final String EVENT_LIST_KEY = "idem:events";
    private static final int MAX_LIMIT = 200;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> recentEvents(int limit) {
        int safeLimit = Math.max(1, Math.min(MAX_LIMIT, limit));
        List<String> rawEvents = redisTemplate.opsForList().range(EVENT_LIST_KEY, 0, safeLimit - 1);
        if (rawEvents == null || rawEvents.isEmpty()) {
            return List.of();
        }
        return rawEvents.stream()
                .map(this::parseEvent)
                .filter(event -> !event.isEmpty())
                .toList();
    }

    private Map<String, Object> parseEvent(String raw) {
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("Failed to parse guard event: {}", e.getMessage());
            return Map.of();
        }
    }
}
