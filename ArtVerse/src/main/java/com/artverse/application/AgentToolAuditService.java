package com.artverse.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Slf4j
@Service
public class AgentToolAuditService {

    public Map<String, Object> around(String toolName, Long userId, Long chapterId, Callable<Map<String, Object>> action) {
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> result = action.call();
            log.info("Agent tool succeeded: {}", event(toolName, userId, chapterId, "succeeded",
                    System.currentTimeMillis() - startedAt, null));
            return result;
        } catch (RuntimeException e) {
            log.warn("Agent tool failed: {}", event(toolName, userId, chapterId, "failed",
                    System.currentTimeMillis() - startedAt, e.getMessage()));
            throw e;
        } catch (Exception e) {
            log.warn("Agent tool failed: {}", event(toolName, userId, chapterId, "failed",
                    System.currentTimeMillis() - startedAt, e.getMessage()));
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> event(String toolName, Long userId, Long chapterId, String status,
                                      long durationMs, String error) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tool", toolName);
        event.put("userId", userId);
        event.put("chapterId", chapterId);
        event.put("status", status);
        event.put("durationMs", durationMs);
        if (error != null && !error.isBlank()) {
            event.put("error", truncate(error, 160));
        }
        return event;
    }

    private String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }
}
