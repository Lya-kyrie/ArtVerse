package com.artverse.agent.gateway;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.application.AgentBudgetService;
import com.artverse.config.ArtVerseProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeHarnessAgentGatewayTest {

    @Test
    void sendsSystemMessagesThroughRuntimeContextInsteadOfPreCallInput() {
        AgentScopeAgentFactory factory = mock(AgentScopeAgentFactory.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        AgentRunRequest request = new AgentRunRequest("1", 2L, 3L, AgentTaskType.MANGA_CONVERSATION,
                List.of(new AgentMessage("system", "chapter rules"), new AgentMessage("user", "hello")),
                Map.of(), null, "key", UUID.randomUUID(), UUID.randomUUID());
        when(factory.getOrCreate(request)).thenReturn(agent);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

        AgentScopeHarnessAgentGateway gateway = new AgentScopeHarnessAgentGateway(
                factory, new AgentScopeRuntimeContextFactory(), new ArtVerseProperties(),
                CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
        gateway.init();

        gateway.streamEvents(request).collectList().block();

        org.mockito.ArgumentCaptor<List<io.agentscope.core.message.Msg>> messages =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<RuntimeContext> context = org.mockito.ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).streamEvents(messages.capture(), context.capture());
        assertThat(messages.getValue()).allMatch(message -> message.getRole() != io.agentscope.core.message.MsgRole.SYSTEM);
        assertThat(messages.getValue()).extracting(io.agentscope.core.message.Msg::getTextContent).containsExactly("hello");
        assertThat(context.getValue().get(AgentScopeSystemPromptContext.class).content()).isEqualTo("chapter rules");
    }

    @Test
    void interruptsTheExactAgentExecutingTheRequestAndRemovesItOnTermination() {
        AgentScopeAgentFactory factory = mock(AgentScopeAgentFactory.class);
        AgentScopeRuntimeContextFactory contexts = mock(AgentScopeRuntimeContextFactory.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        AgentRunRequest request = mock(AgentRunRequest.class);
        UUID requestId = UUID.randomUUID();
        RuntimeContext context = RuntimeContext.builder().userId("1").sessionId("session").build();
        when(request.requestId()).thenReturn(requestId);
        when(request.messages()).thenReturn(List.of(new AgentMessage("user", "hello")));
        when(factory.getOrCreate(request)).thenReturn(agent);
        when(contexts.create(request, "")).thenReturn(context);
        when(agent.streamEvents(anyList(), eq(context))).thenReturn(Flux.<AgentEvent>never());

        AgentScopeHarnessAgentGateway gateway = new AgentScopeHarnessAgentGateway(
                factory, contexts, new ArtVerseProperties(), CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
        gateway.init();

        Disposable subscription = gateway.streamEvents(request).subscribe();
        assertThat(gateway.interrupt(requestId)).isTrue();
        verify(agent).interrupt();

        subscription.dispose();
        assertThat(gateway.interrupt(requestId)).isFalse();
    }

    @Test
    void countsEveryStreamedModelCallIncludingSubagentEvents() {
        AgentScopeAgentFactory factory = mock(AgentScopeAgentFactory.class);
        AgentScopeRuntimeContextFactory contexts = mock(AgentScopeRuntimeContextFactory.class);
        AgentBudgetService budgets = mock(AgentBudgetService.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        AgentRunRequest request = mock(AgentRunRequest.class);
        RuntimeContext context = RuntimeContext.builder().userId("1").sessionId("session").build();
        when(request.requestId()).thenReturn(UUID.randomUUID());
        when(request.taskType()).thenReturn(AgentTaskType.MANGA_REVIEW);
        when(request.messages()).thenReturn(List.of(new AgentMessage("user", "review")));
        when(factory.getOrCreate(request)).thenReturn(agent);
        when(contexts.create(request, "")).thenReturn(context);
        when(agent.streamEvents(anyList(), eq(context))).thenReturn(Flux.just(
                new ModelCallStartEvent("main"), new ModelCallStartEvent("main/camera-reviewer")));

        AgentScopeHarnessAgentGateway gateway = new AgentScopeHarnessAgentGateway(
                factory, contexts, new ArtVerseProperties(), CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults(),
                budgets, null);
        gateway.init();

        gateway.streamEvents(request).collectList().block();

        verify(budgets, times(2)).consumeModelCall(request);
    }
}
