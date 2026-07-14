package com.artverse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.UUID;

@Entity
@Table(name = "manga_agent_run_artifacts", uniqueConstraints = @UniqueConstraint(
        name = "uk_manga_agent_run_artifact_uuid", columnNames = "artifact_uuid"))
@Getter
@Setter
public class MangaAgentRunArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_uuid", nullable = false)
    private UUID artifactUuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private MangaAgentRun run;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id")
    private MangaAgentRunStep step;

    @Column(name = "artifact_type", nullable = false, length = 64)
    private String artifactType;

    @Column(nullable = false, length = 24)
    private String status = "DRAFT";

    @Column(name = "schema_version", nullable = false, length = 32)
    private String schemaVersion = "1";

    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(columnDefinition = "jsonb")
    private String evaluation;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (artifactUuid == null) artifactUuid = UUID.randomUUID();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
