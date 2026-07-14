package com.artverse.application;

import com.artverse.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolIdempotencyServiceTest {

    @Test
    void executeClaimsKeyAndStoresCompleteResult() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);
        ToolIdempotencyService service = new ToolIdempotencyService(redis);

        Map<String, Object> result = service.execute(
                UUID.randomUUID(), "save_storyboard", "content-hash",
                () -> Map.of("scenes", java.util.List.of("scene-a"), "saved", true));

        assertThat(result).containsEntry("saved", true);
        verify(values).set(anyString(), org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                stored -> stored.containsKey("scenes") && "COMPLETED".equals(stored.get("_status"))),
                any(Duration.class));
    }

    @Test
    void executeRejectsConcurrentDuplicate() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Map.of("_status", "PROCESSING"));
        when(values.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(false);
        ToolIdempotencyService service = new ToolIdempotencyService(redis);

        assertThatThrownBy(() -> service.execute(
                UUID.randomUUID(), "save_storyboard", "same-input", Map::of))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already running");
    }

    @Test
    void fullContentHashesDifferForSameSceneCount() {
        assertThat(ToolIdempotencyService.sha256("[scene-a,scene-b]"))
                .isNotEqualTo(ToolIdempotencyService.sha256("[scene-x,scene-y]"));
    }
}
