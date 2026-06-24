package com.artverse.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "story_asset_groups")
@Getter
@Setter
public class StoryAssetGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @JsonIgnore
    private Story story;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "character_profiles", columnDefinition = "TEXT")
    private String characterProfiles;

    @ManyToMany
    @JoinTable(name = "asset_group_characters",
            joinColumns = @JoinColumn(name = "asset_group_id"),
            inverseJoinColumns = @JoinColumn(name = "character_profile_id"))
    @JsonIgnore
    private Set<CharacterProfile> characters = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}