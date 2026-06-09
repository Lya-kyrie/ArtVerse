package com.artverse.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "chapters",
       uniqueConstraints = @UniqueConstraint(name = "uq_chapters_story_number", columnNames = {"story_id", "chapter_number"}))
@Getter
@Setter
public class Chapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @JsonIgnore
    private Story story;

    @Column(name = "chapter_number", nullable = false)
    private Integer chapterNumber;

    @Column(name = "novel_content", columnDefinition = "TEXT")
    private String novelContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_source", length = 20)
    private ContentSource contentSource;

    @Column(name = "scenes_text", columnDefinition = "TEXT")
    private String scenesText;

    @Column(name = "character_profiles", columnDefinition = "TEXT")
    private String characterProfiles;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_group_id")
    @JsonIgnore
    private StoryAssetGroup assetGroup;

    @Column(name = "ref_image", length = 500)
    private String refImage;

    @Enumerated(EnumType.STRING)
    @Column(name = "color_mode", nullable = false, length = 20)
    private ColorMode colorMode = ColorMode.BW;

    @Column(name = "image_count", nullable = false)
    private Integer imageCount = 10;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ChapterStatus status = ChapterStatus.DRAFT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "chapter", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    @JsonIgnore
    private List<ChatMessage> messages = new ArrayList<>();

    @OneToMany(mappedBy = "chapter", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("imageNumber ASC")
    @JsonIgnore
    private Set<MangaImage> images = new LinkedHashSet<>();

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    public String novelContentOrJoinedMessages() {
        if (novelContent != null && !novelContent.isBlank()) {
            return novelContent;
        }
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            sb.append(m.getRole().name().toLowerCase()).append(": ").append(m.getContent()).append("\n");
        }
        return sb.toString().trim();
    }
}
