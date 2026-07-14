package com.artverse.application.workflow;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * A single step within an {@link ExecutionPlan}.
 * Tracks the route, mutation policy, status, and input/output for one step.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public final class ExecutionStep {
    private final int sequence;
    private final MangaWorkflowRoute route;
    private final String description;
    private final boolean mutating;
    private volatile String status;
    private volatile String inputSummary;
    private volatile String outputSummary;
    private volatile String handoffContext;
    private volatile OffsetDateTime startedAt;
    private volatile OffsetDateTime completedAt;

    @JsonCreator
    public ExecutionStep(@JsonProperty("sequence") int sequence,
                         @JsonProperty("route") MangaWorkflowRoute route,
                         @JsonProperty("description") String description,
                         @JsonProperty("mutating") boolean mutating) {
        if (route == null) {
            throw new IllegalArgumentException("route must not be null");
        }
        this.sequence = sequence;
        this.route = route;
        this.description = description == null ? route.name() : description;
        this.mutating = mutating;
        this.status = "PENDING";
    }

    public int sequence() { return sequence; }
    public MangaWorkflowRoute route() { return route; }
    public String description() { return description; }
    public boolean mutating() { return mutating; }
    public String status() { return status; }
    public String inputSummary() { return inputSummary; }
    public String outputSummary() { return outputSummary; }
    public String handoffContext() { return handoffContext; }
    public OffsetDateTime startedAt() { return startedAt; }
    public OffsetDateTime completedAt() { return completedAt; }

    public void markRunning(String inputSummary) {
        this.status = "RUNNING";
        this.inputSummary = inputSummary;
        this.startedAt = OffsetDateTime.now();
    }

    public void markCompleted(String outputSummary) {
        markCompleted(outputSummary, outputSummary);
    }

    public void markCompleted(String outputSummary, String handoffContext) {
        this.status = "COMPLETED";
        this.outputSummary = outputSummary;
        this.handoffContext = handoffContext;
        this.completedAt = OffsetDateTime.now();
    }

    public void markFailed(String error) {
        this.status = "FAILED";
        this.outputSummary = error;
        this.completedAt = OffsetDateTime.now();
    }

    public void markSkipped(String reason) {
        this.status = "SKIPPED";
        this.outputSummary = reason;
        this.completedAt = OffsetDateTime.now();
    }
}
