package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

@Entity
@Table(name = "user_embedding_configs")
@Getter @Setter
public class UserEmbeddingConfig {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(name = "display_name", nullable = false) private String displayName = "";
    @Column(name = "base_url", nullable = false) private String baseUrl = "";
    @Column(name = "api_key", nullable = false) private String apiKey = "";
    @Column(nullable = false) private String model = "";
    @Column(name = "custom_headers", columnDefinition = "jsonb", nullable = false) private String customHeaders = "{}";
    @Enumerated(EnumType.STRING) @Column(nullable = false) private EmbeddingConfigStatus status = EmbeddingConfigStatus.UNVERIFIED;
    @Column(nullable = false) private boolean active;
    @Column(name = "actual_dimension") private Integer actualDimension;
    @Column(name = "config_version", nullable = false) private Integer configVersion = 1;
    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(name = "verified_at") private OffsetDateTime verifiedAt;
    @PrePersist void onCreate() { createdAt = OffsetDateTime.now(); }
}
