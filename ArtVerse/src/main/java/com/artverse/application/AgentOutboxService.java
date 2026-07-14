package com.artverse.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Transactional outbox entrypoint for agent-side projections and extraction. */
@Service
public class AgentOutboxService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentOutboxService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void enqueue(String aggregateType, String aggregateId, String eventType,
                        Map<String, Object> payload) {
        jdbcTemplate.update("""
                INSERT INTO agent_outbox_events(aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, ?, CAST(? AS jsonb))
                """, aggregateType, aggregateId, eventType, toJson(payload));
    }

    @Transactional
    public List<OutboxEvent> claimBatch(String instanceId, int batchSize, int leaseSeconds) {
        return jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT id
                    FROM agent_outbox_events
                    WHERE available_at <= now()
                      AND (status = 'PENDING'
                           OR (status = 'PROCESSING' AND lease_until <= now()))
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                ), claimed AS (
                    UPDATE agent_outbox_events event
                    SET status = 'PROCESSING', owner_instance_id = ?,
                        lease_until = now() + (? * interval '1 second'),
                        fencing_token = fencing_token + 1,
                        attempts = attempts + 1
                    FROM candidates
                    WHERE event.id = candidates.id
                    RETURNING event.id, event.aggregate_type, event.aggregate_id,
                              event.event_type, event.payload::text, event.attempts,
                              event.fencing_token, event.lease_until
                )
                SELECT * FROM claimed ORDER BY id
                """, (rs, row) -> new OutboxEvent(
                rs.getLong("id"), rs.getString("aggregate_type"),
                rs.getString("aggregate_id"), rs.getString("event_type"),
                readPayload(rs.getString("payload")), rs.getInt("attempts"),
                rs.getLong("fencing_token"), rs.getObject("lease_until", OffsetDateTime.class)),
                Math.max(1, batchSize), instanceId, Math.max(30, leaseSeconds));
    }

    public boolean renew(OutboxEvent event, String instanceId, int leaseSeconds) {
        return jdbcTemplate.update("""
                UPDATE agent_outbox_events
                SET lease_until = now() + (? * interval '1 second')
                WHERE id = ? AND status = 'PROCESSING'
                  AND owner_instance_id = ? AND fencing_token = ?
                """, Math.max(30, leaseSeconds), event.id(), instanceId, event.fencingToken()) == 1;
    }

    public boolean markPublished(OutboxEvent event, String instanceId) {
        return jdbcTemplate.update("""
                UPDATE agent_outbox_events
                SET status = 'PUBLISHED', published_at = now(), lease_until = NULL,
                    owner_instance_id = NULL, last_error = NULL
                WHERE id = ? AND status = 'PROCESSING'
                  AND owner_instance_id = ? AND fencing_token = ?
                """, event.id(), instanceId, event.fencingToken()) == 1;
    }

    public boolean markFailed(OutboxEvent event, String instanceId, Throwable error, int maxAttempts) {
        int backoffSeconds = Math.min(900, 1 << Math.min(10, Math.max(0, event.attempts() - 1)));
        String message = error == null || error.getMessage() == null
                ? "Outbox handler failed"
                : clip(error.getMessage(), 1000);
        return jdbcTemplate.update("""
                UPDATE agent_outbox_events
                SET status = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'PENDING' END,
                    available_at = CASE WHEN attempts >= ? THEN available_at
                                        ELSE now() + (? * interval '1 second') END,
                    lease_until = NULL, owner_instance_id = NULL, last_error = ?
                WHERE id = ? AND status = 'PROCESSING'
                  AND owner_instance_id = ? AND fencing_token = ?
                """, Math.max(1, maxAttempts), Math.max(1, maxAttempts), backoffSeconds,
                message, event.id(), instanceId, event.fencingToken()) == 1;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize agent outbox event", error);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception error) {
            throw new IllegalStateException("Invalid agent outbox payload", error);
        }
    }

    private String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record OutboxEvent(
            long id,
            String aggregateType,
            String aggregateId,
            String eventType,
            Map<String, Object> payload,
            int attempts,
            long fencingToken,
            OffsetDateTime leaseUntil
    ) {
    }
}
