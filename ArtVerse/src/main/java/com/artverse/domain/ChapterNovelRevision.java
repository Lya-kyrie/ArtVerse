package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** Immutable snapshot of a chapter's canonical novel text. */
@Entity
@Table(name = "chapter_novel_revisions", uniqueConstraints =
        @UniqueConstraint(name = "uq_chapter_novel_revision", columnNames = {"chapter_id", "revision_number"}))
@Getter
@Setter
public class ChapterNovelRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chapter_id", nullable = false)
    private Chapter chapter;

    @Column(name = "revision_number", nullable = false)
    private Integer revisionNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NovelContentRevisionSource source;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposal_id", unique = true)
    private NovelContentProposal proposal;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_run_artifact_id", unique = true)
    private MangaAgentRunArtifact agentRunArtifact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
