package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

@Entity @Table(name = "knowledge_index_jobs") @Getter @Setter
public class KnowledgeIndexJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "knowledge_unit_id", nullable = false) private KnowledgeUnit knowledgeUnit;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "embedding_space_id") private EmbeddingSpace embeddingSpace;
    @Column(name = "source_version", nullable = false) private Integer sourceVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private KnowledgeIndexJobStatus status = KnowledgeIndexJobStatus.PENDING;
    @Column(nullable = false) private Integer attempts = 0;
    @Column(name = "next_run_at", nullable = false) private OffsetDateTime nextRunAt = OffsetDateTime.now();
    @Column(name = "last_error") private String lastError;
    @Column(name = "owner_instance_id") private String ownerInstanceId;
    @Column(name = "lease_until") private OffsetDateTime leaseUntil;
    @Column(name = "fencing_token", nullable = false) private Long fencingToken = 0L;
    @Column(name = "tenant_id") private java.util.UUID tenantId;
    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
    @PrePersist void onCreate() { OffsetDateTime now = OffsetDateTime.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
