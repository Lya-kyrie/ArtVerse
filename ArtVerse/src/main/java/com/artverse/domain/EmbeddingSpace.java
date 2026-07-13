package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

@Entity @Table(name = "embedding_spaces") @Getter @Setter
public class EmbeddingSpace {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) private User user;
    @ManyToOne(fetch = FetchType.EAGER) @JoinColumn(name = "config_id", nullable = false) private UserEmbeddingConfig config;
    @Column(name = "config_version", nullable = false) private Integer configVersion;
    @Column(name = "model_identifier", nullable = false) private String modelIdentifier;
    @Column(nullable = false) private Integer dimensions;
    @Column(nullable = false) private String status = "READY";
    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @PrePersist void onCreate() { createdAt = OffsetDateTime.now(); }
}
