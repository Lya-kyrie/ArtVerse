package com.artverse.agent.gateway;

import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunRequest;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

@Slf4j
@Component
@Primary
public class AgentScopeHarnessAgentGateway {

    private static final String CB_NAME = "manga-agent-llm";
    private static final String RETRY_NAME = "manga-agent-llm";

    private final AgentScopeMessageMapper messageMapper = new AgentScopeMessageMapper();
    private final AgentScopeAgentFactory agentFactory;
    private final AgentScopeRuntimeContextFactory runtimeContextFactory;
    private final ArtVerseProperties properties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    private CircuitBreakerConfig circuitBreakerConfig;
    private RetryConfig retryConfig;

    public AgentScopeHarnessAgentGateway(
            AgentScopeAgentFactory agentFactory,
            AgentScopeRuntimeContextFactory runtimeContextFactory,
            ArtVerseProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry) {
        this.agentFactory = agentFactory;
        this.runtimeContextFactory = runtimeContextFactory;
        this.properties = properties;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
    }

    @PostConstruct
    public void init() {
        ArtVerseProperties.Agent agentProps = properties.getAgent();

        // Shared predicate: only record / retry on transient failures,
        // not on business-logic rejections or CB-internal exceptions.
        Predicate<Throwable> isTransient = throwable ->
                (throwable instanceof IOException)
                        || (throwable instanceof RuntimeException
                        && !(throwable instanceof BusinessException)
                        && !(throwable instanceof CallNotPermittedException));

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(agentProps.getCircuitBreakerSlidingWindowSize())
                .minimumNumberOfCalls(agentProps.getCircuitBreakerFailureThreshold())
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(agentProps.getCircuitBreakerWaitSeconds()))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slowCallDurationThreshold(Duration.ofMillis(agentProps.getCircuitBreakerSlowCallThresholdMs()))
                .slowCallRateThreshold(50)
                .recordException(isTransient)
                .build();
        this.circuitBreakerConfig = cbConfig;

        RetryConfig retryConfig = RetryConfig.<Throwable>custom()
                .maxAttempts(agentProps.getMaxRetries() + 1) // +1 for the initial attempt
                .waitDuration(Duration.ofMillis(agentProps.getRetryMinBackoffMs()))
                .intervalBiFunction((attempt, either) -> {
                    long base = agentProps.getRetryMinBackoffMs();
                    long delay = (long) (base * Math.pow(agentProps.getRetryMultiplier(), attempt));
                    return Math.min(delay, agentProps.getRetryMaxBackoffMs());
                })
                .retryOnException(isTransient)
                .build();
        this.retryConfig = retryConfig;

        log.info("Agent gateway initialized: cb={} retry={} maxRetries={}",
                CB_NAME, RETRY_NAME, agentProps.getMaxRetries());
    }

    public Flux<String> streamChat(AgentRunRequest request) {
        return streamEvents(request)
                .ofType(TextBlockDeltaEvent.class)
                .map(TextBlockDeltaEvent::getDelta)
                .filter(delta -> delta != null && !delta.isEmpty());
    }

    public Flux<AgentEvent> streamEvents(AgentRunRequest request) {
        HarnessAgent agent = agentFactory.getOrCreate(request);
        RuntimeContext ctx = runtimeContextFactory.create(request);
        List<Msg> messages = messageMapper.map(request.messages());

        return agent.streamEvents(messages, ctx)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker(request, "executor")))
                .doOnError(e -> logGatewayError(e, "stream", request.requestId()));
    }

    public Mono<String> generateText(AgentRunRequest request) {
        HarnessAgent agent = agentFactory.getOrCreate(request);
        RuntimeContext ctx = runtimeContextFactory.create(request);
        List<Msg> messages = messageMapper.map(request.messages());

        return agent.call(messages, ctx)
                .map(Msg::getTextContent)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker(request, "executor")))
                .doOnError(e -> logGatewayError(e, "generate", request.requestId()));
    }

    public <T> Mono<T> generateStructured(AgentRunRequest request, Class<T> outputType) {
        HarnessAgent agent = agentFactory.getOrCreate(request);
        RuntimeContext ctx = runtimeContextFactory.create(request);
        List<Msg> messages = messageMapper.map(request.messages());

        return agent.call(messages, outputType, ctx)
                .map(message -> {
                    T result = message.getStructuredData(outputType);
                    if (result == null) {
                        throw new BusinessException(502, "Agent returned empty structured output");
                    }
                    return result;
                })
                .transformDeferred(RetryOperator.of(retry(request, "router")))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker(request, "router")))
                .doOnError(e -> logGatewayError(e, "structured", request.requestId()));
    }

    private CircuitBreaker circuitBreaker(AgentRunRequest request, String role) {
        return circuitBreakerRegistry.circuitBreaker(resilienceName(CB_NAME, request, role), circuitBreakerConfig);
    }

    private Retry retry(AgentRunRequest request, String role) {
        return retryRegistry.retry(resilienceName(RETRY_NAME, request, role), retryConfig);
    }

    private String resilienceName(String prefix, AgentRunRequest request, String role) {
        String provider = request.modelSpec() == null ? "default" : request.modelSpec().provider();
        String baseUrl = request.modelSpec() == null ? "default" : request.modelSpec().baseUrl();
        return prefix + "-" + role + "-" + provider + "-" + AgentModelSpecFactory.shortHash(baseUrl);
    }

    private void logGatewayError(Throwable e, String method, UUID requestId) {
        if (e instanceof CallNotPermittedException) {
            log.warn("Circuit breaker open for agent LLM, fast-failing {} request={}", method, requestId);
        } else {
            log.error("Agent {} failed: requestId={}, error={}", method, requestId, e.getMessage());
        }
    }

}
