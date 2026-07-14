package com.artverse.application;

import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentMessage;
import com.artverse.config.ArtVerseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentBudgetServiceTest {

    @Test
    void stopsRouterAfterHardModelLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(redis.execute(any(), anyList(), any(), any())).thenReturn(1L, 2L, 3L);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getAgent().setRouterMaxModelCalls(2);
        AgentBudgetService service = new AgentBudgetService(redis, jdbc, properties);
        AgentRunRequest request = new AgentRunRequest(
                "1", 2L, 3L, AgentTaskType.MANGA_ROUTER, List.of(), Map.of(),
                null, "key", UUID.randomUUID(), UUID.randomUUID());

        assertThat(service.consumeModelCall(request)).isEqualTo(1);
        assertThat(service.consumeModelCall(request)).isEqualTo(2);
        assertThatThrownBy(() -> service.consumeModelCall(request))
                .isInstanceOf(AgentBudgetExceededException.class)
                .hasMessageContaining("3/2");
    }

    @Test
    void rejectsOversizedInputBeforeAModelCall() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getAgent().setMaxInputTokens(3);
        AgentBudgetService service = new AgentBudgetService(redis, jdbc, properties);
        AgentRunRequest request = new AgentRunRequest(
                "1", 2L, 3L, AgentTaskType.MANGA_CONVERSATION,
                List.of(new AgentMessage("user", "四个汉字")), Map.of("step_id", "direct"),
                null, "key", UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> service.validateAndRecordInput(request))
                .isInstanceOf(AgentBudgetExceededException.class)
                .hasMessageContaining("INPUT_TOKEN");
    }

    @Test
    void enforcesBothOutputTokenAndUtf8ByteLimits() {
        AgentBudgetService service = new AgentBudgetService(
                mock(StringRedisTemplate.class), mock(JdbcTemplate.class), new ArtVerseProperties());
        AgentBudgetService.OutputUsage usage = service.measureOutput("漫画");

        assertThat(usage.estimatedTokens()).isEqualTo(2);
        assertThat(usage.bytes()).isEqualTo(6);

        ArtVerseProperties byteLimited = new ArtVerseProperties();
        byteLimited.getAgent().setMaxOutputBytes(5);
        AgentBudgetService limitedService = new AgentBudgetService(
                mock(StringRedisTemplate.class), mock(JdbcTemplate.class), byteLimited);
        assertThatThrownBy(() -> limitedService.requireOutputWithinLimit(usage))
                .isInstanceOf(AgentBudgetExceededException.class)
                .hasMessageContaining("OUTPUT_BYTE");
    }
}
