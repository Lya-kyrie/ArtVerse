package com.artverse.domain;

import com.artverse.application.workflow.MangaWorkflowRoute;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "manga_agent_run_steps", uniqueConstraints = @UniqueConstraint(
        name = "uk_manga_agent_run_step", columnNames = {"run_id", "plan_id", "step_sequence"}))
@Getter
@Setter
public class MangaAgentRunStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private MangaAgentRun run;

    @Column(name = "plan_id", nullable = false, length = 64)
    private String planId;

    @Column(name = "step_sequence", nullable = false)
    private int stepSequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MangaWorkflowRoute route;

    @Column(nullable = false, length = 24)
    private String status = "PENDING";

    @Column(nullable = false)
    private boolean mutating;

    @Column(name = "skill_key", length = 120)
    private String skillKey;

    @Column(name = "skill_version", length = 32)
    private String skillVersion;

    @Column(name = "input_summary", columnDefinition = "TEXT")
    private String inputSummary;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    private String outputSummary;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
