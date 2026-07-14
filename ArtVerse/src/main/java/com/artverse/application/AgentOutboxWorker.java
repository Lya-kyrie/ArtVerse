package com.artverse.application;

import com.artverse.config.ArtVerseProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Multi-instance outbox dispatcher with lease renewal and fencing checks. */
@Slf4j
@Component
@ConditionalOnProperty(name = "artverse.agent.outbox-worker-enabled", havingValue = "true", matchIfMissing = true)
public class AgentOutboxWorker {

    private static final Set<String> EXTRACTION_EVENTS = Set.of(
            "CHAPTER_CONTENT_CHANGED", "STORYBOARD_CHANGED");

    private final String instanceId = "outbox-" + UUID.randomUUID();
    private final AgentOutboxService outboxService;
    private final KnowledgeExtractionService extractionService;
    private final ArtVerseProperties properties;
    private final MeterRegistry meterRegistry;
    private final ScheduledExecutorService leaseRenewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-outbox-lease-renewer");
        thread.setDaemon(true);
        return thread;
    });

    public AgentOutboxWorker(AgentOutboxService outboxService,
                             KnowledgeExtractionService extractionService,
                             ArtVerseProperties properties,
                             MeterRegistry meterRegistry) {
        this.outboxService = outboxService;
        this.extractionService = extractionService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(initialDelay = 5_000, fixedDelayString = "${artverse.agent.outbox-poll-interval-ms:5000}")
    public void dispatch() {
        ArtVerseProperties.Agent config = properties.getAgent();
        if (!config.isOutboxWorkerEnabled()) return;
        var claimed = outboxService.claimBatch(
                instanceId, config.getOutboxBatchSize(), config.getOutboxLeaseSeconds());
        claimed.forEach(this::process);
        if (!claimed.isEmpty()) {
            meterRegistry.counter("artverse.agent.outbox.claimed").increment(claimed.size());
        }
    }

    private void process(AgentOutboxService.OutboxEvent event) {
        long started = System.nanoTime();
        int leaseSeconds = properties.getAgent().getOutboxLeaseSeconds();
        long renewalSeconds = Math.max(10, leaseSeconds / 3L);
        ScheduledFuture<?> renewal = leaseRenewer.scheduleAtFixedRate(
                () -> renewQuietly(event), renewalSeconds, renewalSeconds, TimeUnit.SECONDS);
        try {
            if (EXTRACTION_EVENTS.contains(event.eventType())) {
                int candidateCount = extractionService.extract(event, () -> requireOwnership(event));
                meterRegistry.summary("artverse.agent.knowledge.candidates.extracted",
                        "event_type", event.eventType()).record(candidateCount);
            }
            if (!outboxService.markPublished(event, instanceId)) {
                throw new LostOutboxLeaseException(event.id());
            }
            meterRegistry.counter("artverse.agent.outbox.processed",
                    "event_type", event.eventType(), "outcome", "success").increment();
        } catch (Exception error) {
            boolean updated = outboxService.markFailed(event, instanceId, error,
                    properties.getAgent().getOutboxMaxAttempts());
            String outcome = updated ? "retry_or_failed" : "lease_lost";
            meterRegistry.counter("artverse.agent.outbox.processed",
                    "event_type", event.eventType(), "outcome", outcome).increment();
            if (updated) {
                log.warn("Agent outbox event {} failed on attempt {}: {}",
                        event.id(), event.attempts(), error.getMessage());
            } else {
                log.warn("Agent outbox event {} lost fencing ownership", event.id());
            }
        } finally {
            renewal.cancel(false);
            meterRegistry.timer("artverse.agent.outbox.duration",
                    "event_type", event.eventType()).record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private void requireOwnership(AgentOutboxService.OutboxEvent event) {
        if (!outboxService.renew(event, instanceId, properties.getAgent().getOutboxLeaseSeconds())) {
            throw new LostOutboxLeaseException(event.id());
        }
    }

    private void renewQuietly(AgentOutboxService.OutboxEvent event) {
        try {
            if (!outboxService.renew(event, instanceId, properties.getAgent().getOutboxLeaseSeconds())) {
                log.warn("Unable to renew agent outbox lease for event {}", event.id());
            }
        } catch (Exception error) {
            log.warn("Agent outbox lease renewal failed for event {}", event.id(), error);
        }
    }

    @PreDestroy
    void close() {
        leaseRenewer.shutdownNow();
    }

    private static final class LostOutboxLeaseException extends RuntimeException {
        private LostOutboxLeaseException(long eventId) {
            super("Lost outbox lease for event " + eventId);
        }
    }
}
