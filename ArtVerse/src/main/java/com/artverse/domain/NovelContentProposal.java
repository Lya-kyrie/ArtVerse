package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "novel_content_proposals")
@Getter
@Setter
public class NovelContentProposal {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chapter_id", nullable = false)
    private Chapter chapter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private MangaAgentConversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "through_message_id", nullable = false)
    private ChatMessage throughMessage;

    @Column(name = "base_version", nullable = false)
    private Long baseVersion;

    @Column(name = "generated_content", nullable = false, columnDefinition = "TEXT")
    private String generatedContent;

    @Column(name = "generated_content_hash", nullable = false, length = 64)
    private String generatedContentHash;

    @Column(name = "draft_content", nullable = false, columnDefinition = "TEXT")
    private String draftContent;

    @Column(name = "draft_content_hash", nullable = false, length = 64)
    private String draftContentHash;

    @Column(name = "provider_config_id")
    private Long providerConfigId;

    @Column(name = "model", nullable = false, length = 160)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 80)
    private String promptVersion;

    @Column(name = "skill_versions_json", nullable = false, columnDefinition = "jsonb")
    private String skillVersionsJson = "{}";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NovelContentProposalStatus status = NovelContentProposalStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "committed_at")
    private OffsetDateTime committedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) id = UUID.randomUUID();
        createdAt = now;
        updatedAt = now;
        if (status == null) status = NovelContentProposalStatus.DRAFT;
        if (skillVersionsJson == null || skillVersionsJson.isBlank()) skillVersionsJson = "{}";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
