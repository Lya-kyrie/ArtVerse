package com.artverse.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "chat_messages")
@Getter
@Setter
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chapter_id", nullable = false)
    @JsonIgnore
    private Chapter chapter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    @JsonIgnore
    private MangaAgentConversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_status", nullable = false, length = 16)
    private ChatMessageCompletionStatus completionStatus = ChatMessageCompletionStatus.COMPLETE;

    @Column(name = "skill_versions_json", nullable = false, columnDefinition = "jsonb")
    private String skillVersionsJson = "{}";

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        if (requestId == null) requestId = UUID.randomUUID();
        if (completionStatus == null) completionStatus = ChatMessageCompletionStatus.COMPLETE;
        if (skillVersionsJson == null || skillVersionsJson.isBlank()) skillVersionsJson = "{}";
    }
}
