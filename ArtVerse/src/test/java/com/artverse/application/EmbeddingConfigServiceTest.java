package com.artverse.application;

import com.artverse.ai.EmbeddingClient;
import com.artverse.domain.EmbeddingConfigStatus;
import com.artverse.domain.EmbeddingSpace;
import com.artverse.domain.User;
import com.artverse.domain.UserEmbeddingConfig;
import com.artverse.persistence.EmbeddingSpaceRepository;
import com.artverse.persistence.UserEmbeddingConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingConfigServiceTest {

    @Test
    void savePersistsUnverifiedDraftWithoutCallingEmbeddingApi() {
        Fixture fixture = fixture();
        when(fixture.apiKeyService.encryptSecret("sk-test")).thenReturn("encrypted");
        when(fixture.configRepository.save(any(UserEmbeddingConfig.class))).thenAnswer(invocation -> {
            UserEmbeddingConfig config = invocation.getArgument(0);
            config.setId(11L);
            return config;
        });

        EmbeddingConfigService.ConfigInfo saved = fixture.service.save(fixture.user,
                draft(null, "sk-test", "text-embedding-v4"));

        assertThat(saved.id()).isEqualTo(11L);
        assertThat(saved.status()).isEqualTo("UNVERIFIED");
        assertThat(saved.actualDimension()).isNull();
        assertThat(saved.apiKeyMasked()).isNotBlank();
        verify(fixture.embeddingClient, never()).embed(any(), any(), any(), any(), any());
    }

    @Test
    void testSavedConfigPersistsActualDimensionAndEmbeddingSpace() {
        Fixture fixture = fixture();
        UserEmbeddingConfig config = savedConfig(fixture.user);
        when(fixture.configRepository.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(config));
        when(fixture.apiKeyService.decryptSecret("encrypted")).thenReturn("sk-test");
        when(fixture.embeddingClient.embed(any(), any(), any(), any(), any())).thenReturn(new float[1024]);
        when(fixture.spaceRepository.findByConfigIdAndConfigVersion(11L, 1)).thenReturn(Optional.empty());
        when(fixture.spaceRepository.save(any(EmbeddingSpace.class))).thenAnswer(invocation -> {
            EmbeddingSpace space = invocation.getArgument(0);
            space.setId(21L);
            return space;
        });

        EmbeddingConfigService.TestResult result = fixture.service.test(fixture.user, 11L);

        assertThat(result.dimension()).isEqualTo(1024);
        assertThat(result.embeddingSpaceId()).isEqualTo(21L);
        assertThat(config.getStatus()).isEqualTo(EmbeddingConfigStatus.VERIFIED);
        assertThat(config.getActualDimension()).isEqualTo(1024);
        assertThat(config.isActive()).isTrue();
        assertThat(result.config().active()).isTrue();
    }

    @Test
    void activatingVerifiedConfigSwitchesTheUserDefault() {
        Fixture fixture = fixture();
        UserEmbeddingConfig current = verifiedConfig(fixture.user, 10L, "text-embedding-v3", true);
        UserEmbeddingConfig target = verifiedConfig(fixture.user, 11L, "text-embedding-v4", false);
        when(fixture.configRepository.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(target));
        when(fixture.configRepository.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(target, current));

        EmbeddingConfigService.ConfigInfo activated = fixture.service.activate(fixture.user, 11L);

        assertThat(activated.active()).isTrue();
        assertThat(target.isActive()).isTrue();
        assertThat(current.isActive()).isFalse();
        verify(fixture.configRepository).flush();
    }

    @Test
    void dashScopeDiscoveryIncludesVerifiedEmbeddingCatalogWhenModelsEndpointOmitsIt() {
        Fixture fixture = fixture();
        when(fixture.embeddingClient.discoverModels(any(), any(), any())).thenReturn(List.of("qwen-plus", "qwen-max"));

        List<String> models = fixture.service.discoverModels(fixture.user,
                draft(null, "sk-test", ""));

        assertThat(models).containsExactly("text-embedding-v4", "text-embedding-v3", "text-embedding-v2", "text-embedding-v1");
    }

    private static EmbeddingConfigService.Draft draft(Long id, String apiKey, String model) {
        return new EmbeddingConfigService.Draft(id, "Qwen Embedding",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", apiKey, model, "{}");
    }

    private static UserEmbeddingConfig savedConfig(User user) {
        UserEmbeddingConfig config = new UserEmbeddingConfig();
        config.setId(11L);
        config.setUser(user);
        config.setDisplayName("Qwen Embedding");
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setApiKey("encrypted");
        config.setModel("text-embedding-v4");
        config.setCustomHeaders("{}");
        config.setConfigVersion(1);
        return config;
    }

    private static UserEmbeddingConfig verifiedConfig(User user, Long id, String model, boolean active) {
        UserEmbeddingConfig config = savedConfig(user);
        config.setId(id);
        config.setModel(model);
        config.setStatus(EmbeddingConfigStatus.VERIFIED);
        config.setActualDimension(1024);
        config.setActive(active);
        return config;
    }

    private static Fixture fixture() {
        UserEmbeddingConfigRepository configRepository = mock(UserEmbeddingConfigRepository.class);
        EmbeddingSpaceRepository spaceRepository = mock(EmbeddingSpaceRepository.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        User user = new User();
        user.setId(7L);
        EmbeddingConfigService service = new EmbeddingConfigService(configRepository, spaceRepository,
                apiKeyService, embeddingClient, new ObjectMapper(), jdbcTemplate);
        return new Fixture(service, configRepository, spaceRepository, apiKeyService, embeddingClient, user);
    }

    private record Fixture(EmbeddingConfigService service, UserEmbeddingConfigRepository configRepository,
                           EmbeddingSpaceRepository spaceRepository, ApiKeyService apiKeyService,
                           EmbeddingClient embeddingClient, User user) {}
}
