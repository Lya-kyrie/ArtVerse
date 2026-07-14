package com.artverse.application;

import com.artverse.ai.EmbeddingClient;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.EmbeddingSpaceRepository;
import com.artverse.persistence.UserEmbeddingConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class EmbeddingConfigService {
    private final UserEmbeddingConfigRepository configRepository;
    private final EmbeddingSpaceRepository spaceRepository;
    private final ApiKeyService apiKeyService;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public record Draft(Long configId, String displayName, String baseUrl, String apiKey, String model, String customHeaders) {}
    public record ConfigInfo(Long id, String displayName, String baseUrl, String model, String apiKeyMasked,
                             String customHeaders, String status, Integer actualDimension, Integer configVersion,
                             boolean active, boolean usedByStories, List<String> usedByStoryTitles) {}
    public record TestResult(ConfigInfo config, Long embeddingSpaceId, int dimension) {}

    @Transactional(readOnly = true)
    public List<ConfigInfo> list(User user) {
        return configRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .sorted((left, right) -> Boolean.compare(right.isActive(), left.isActive()))
                .map(this::toInfo)
                .toList();
    }

    @Transactional
    public ConfigInfo save(User user, Draft draft) {
        UserEmbeddingConfig base = findBase(user, draft.configId());
        String baseUrl = first(draft.baseUrl(), base == null ? "" : base.getBaseUrl());
        String model = first(draft.model(), base == null ? "" : base.getModel());
        String displayName = first(draft.displayName(), base == null ? "" : base.getDisplayName());
        String baseHeaders = base == null ? "{}" : decryptedHeaders(base);
        String headers = firstJson(draft.customHeaders(), baseHeaders);
        String apiKey = first(draft.apiKey(), base == null ? "" : apiKeyService.decryptSecret(base.getApiKey()));
        validateDraft(baseUrl, model, apiKey, headers);
        boolean connectionChanged = base == null
                || !Objects.equals(baseUrl, base.getBaseUrl())
                || !Objects.equals(model, base.getModel())
                || !Objects.equals(headers, baseHeaders)
                || (draft.apiKey() != null && !draft.apiKey().isBlank());
        boolean createVersion = base == null || (connectionChanged && isUsed(base));
        UserEmbeddingConfig config = createVersion ? new UserEmbeddingConfig() : base;
        if (createVersion) {
            config.setUser(user);
            config.setConfigVersion(base == null ? 1 : base.getConfigVersion() + 1);
        }
        config.setDisplayName(displayName);
        config.setBaseUrl(baseUrl);
        config.setApiKey(apiKeyService.encryptSecret(apiKey));
        config.setModel(model);
        config.setCustomHeaders(apiKeyService.encryptSecret(headers));
        if (connectionChanged) {
            config.setStatus(EmbeddingConfigStatus.UNVERIFIED);
            config.setActualDimension(null);
            config.setVerifiedAt(null);
            if (!createVersion) config.setActive(false);
        }
        return toInfo(configRepository.save(config));
    }

    @Transactional
    public TestResult test(User user, Long configId) {
        UserEmbeddingConfig config = findBase(user, configId);
        if (config == null) throw new BusinessException(400, "Save the embedding configuration before testing it.");
        String apiKey = apiKeyService.decryptSecret(config.getApiKey());
        String headers = decryptedHeaders(config);
        validateDraft(config.getBaseUrl(), config.getModel(), apiKey, headers);
        float[] vector = embeddingClient.embed(config.getBaseUrl(), apiKey, config.getModel(), headers, "ArtVerse embedding connection test");
        config.setStatus(EmbeddingConfigStatus.VERIFIED);
        config.setActualDimension(vector.length);
        config.setVerifiedAt(OffsetDateTime.now());
        if (configRepository.findByUserIdAndActiveTrue(user.getId()).isEmpty()) {
            config.setActive(true);
        }
        configRepository.save(config);
        EmbeddingSpace space = spaceRepository.findByConfigIdAndConfigVersion(config.getId(), config.getConfigVersion())
                .orElseGet(EmbeddingSpace::new);
        space.setUser(user);
        space.setConfig(config);
        space.setConfigVersion(config.getConfigVersion());
        space.setModelIdentifier(config.getModel());
        space.setDimensions(vector.length);
        space.setStatus("READY");
        spaceRepository.save(space);
        return new TestResult(toInfo(config), space.getId(), vector.length);
    }

    @Transactional
    public ConfigInfo activate(User user, Long configId) {
        UserEmbeddingConfig config = findBase(user, configId);
        if (config == null) throw new BusinessException(404, "Embedding configuration not found");
        if (config.getStatus() != EmbeddingConfigStatus.VERIFIED || config.getActualDimension() == null) {
            throw new BusinessException(400, "Test the embedding configuration before setting it as default.");
        }
        configRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .forEach(item -> item.setActive(false));
        configRepository.flush();
        config.setActive(true);
        return toInfo(config);
    }

    @Transactional
    public ConfigInfo deactivate(User user, Long configId) {
        UserEmbeddingConfig config = findBase(user, configId);
        if (config == null) throw new BusinessException(404, "Embedding configuration not found");
        config.setActive(false);
        configRepository.flush();
        return toInfo(config);
    }

    @Transactional(readOnly = true)
    public List<String> discoverModels(User user, Draft draft) {
        UserEmbeddingConfig base = findBase(user, draft.configId());
        String baseUrl = first(draft.baseUrl(), base == null ? "" : base.getBaseUrl());
        String headers = firstJson(draft.customHeaders(), base == null ? "{}" : decryptedHeaders(base));
        String apiKey = first(draft.apiKey(), base == null ? "" : apiKeyService.decryptSecret(base.getApiKey()));
        validateHeaders(headers);
        List<String> discovered = embeddingClient.discoverModels(baseUrl, apiKey, headers);
        List<String> result = new ArrayList<>();
        String normalizedUrl = baseUrl.toLowerCase(Locale.ROOT);
        if (normalizedUrl.contains("dashscope.aliyuncs.com")) {
            result.addAll(List.of("text-embedding-v4", "text-embedding-v3", "text-embedding-v2", "text-embedding-v1"));
        }
        discovered.stream().filter(EmbeddingConfigService::looksLikeEmbeddingModel).forEach(result::add);
        return result.stream().distinct().toList();
    }

    @Transactional(readOnly = true)
    public UserEmbeddingConfig requireVerified(User user, Long configId) {
        UserEmbeddingConfig config = configRepository.findByIdAndUserId(configId, user.getId())
                .orElseThrow(() -> new BusinessException(404, "Embedding configuration not found"));
        if (config.getStatus() != EmbeddingConfigStatus.VERIFIED || config.getActualDimension() == null) {
            throw new BusinessException(400, "Test the embedding configuration before rebuilding a knowledge base.");
        }
        return config;
    }

    @Transactional(readOnly = true)
    public EmbeddingSpace requireSpace(User user, Long configId) {
        UserEmbeddingConfig config = requireVerified(user, configId);
        return spaceRepository.findByConfigIdAndConfigVersion(config.getId(), config.getConfigVersion())
                .orElseThrow(() -> new BusinessException(400, "Embedding space is unavailable."));
    }

    public String decryptedApiKey(UserEmbeddingConfig config) { return apiKeyService.decryptSecret(config.getApiKey()); }
    public String decryptedHeaders(UserEmbeddingConfig config) {
        String stored = config == null ? "{}" : config.getCustomHeaders();
        if (stored == null || stored.isBlank()) return "{}";
        return stored.startsWith("v2:") ? apiKeyService.decryptSecret(stored) : stored;
    }

    private void validateDraft(String baseUrl, String model, String apiKey, String headers) {
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) throw new BusinessException(400, "Embedding Base URL, API key, and model are required.");
        validateHeaders(headers);
    }
    private void validateHeaders(String headers) {
        try { if (!objectMapper.readTree(headers).isObject()) throw new BusinessException(400, "Custom embedding headers must be a JSON object."); }
        catch (BusinessException e) { throw e; } catch (Exception e) { throw new BusinessException(400, "Custom embedding headers must be valid JSON."); }
    }
    private UserEmbeddingConfig findBase(User user, Long configId) {
        return configId == null ? null : configRepository.findByIdAndUserId(configId, user.getId())
                .orElseThrow(() -> new BusinessException(404, "Embedding configuration not found"));
    }
    private boolean isUsed(UserEmbeddingConfig config) {
        return !jdbcTemplate.queryForList("SELECT 1 FROM story_embedding_spaces ses JOIN embedding_spaces es ON es.id = ses.embedding_space_id WHERE es.config_id = ? LIMIT 1", Integer.class, config.getId()).isEmpty();
    }
    private static boolean looksLikeEmbeddingModel(String model) {
        String value = model.toLowerCase(Locale.ROOT);
        return value.contains("embedding") || value.contains("embed") || value.contains("bge-")
                || value.contains("/bge") || value.contains("gte-") || value.contains("/gte")
                || value.contains("e5-") || value.contains("/e5");
    }
    private ConfigInfo toInfo(UserEmbeddingConfig config) {
        List<String> titles = jdbcTemplate.queryForList("""
                SELECT s.title FROM stories s
                JOIN story_embedding_spaces ses ON ses.story_id = s.id
                JOIN embedding_spaces es ON es.id = ses.embedding_space_id
                WHERE es.config_id = ? AND es.config_version = ? ORDER BY s.title
                """, String.class, config.getId(), config.getConfigVersion());
        boolean used = !titles.isEmpty();
        return new ConfigInfo(config.getId(), config.getDisplayName(), config.getBaseUrl(), config.getModel(),
                config.getApiKey() == null || config.getApiKey().isBlank() ? "" : "(configured)",
                decryptedHeaders(config), config.getStatus().name(), config.getActualDimension(), config.getConfigVersion(),
                config.isActive(), used, titles);
    }
    private static String first(String preferred, String fallback) { return preferred == null || preferred.isBlank() ? fallback : preferred.trim(); }
    private static String firstJson(String preferred, String fallback) { return preferred == null || preferred.isBlank() ? fallback : preferred.trim(); }
}
