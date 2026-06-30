package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.User;
import com.artverse.domain.UserApiKey;
import com.artverse.persistence.UserApiKeyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    public static final String SLOT_LLM = "llm";
    public static final String SLOT_IMAGE = "image";
    public static final String SLOT_WORKFLOW = "workflow";

    private static final String ALGORITHM = "AES";
    private static final byte[] ENCRYPTION_KEY = "ArtVerse!ApiKey1".getBytes(StandardCharsets.UTF_8);

    private final UserApiKeyRepository repository;
    private final ArtVerseProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public record KeyInfo(String provider, String apiKeyMasked) {}

    public record ProviderInfo(
            String slot,
            String provider,
            String label,
            String apiKeyMasked,
            String baseUrl,
            String model
    ) {}

    @Transactional
    public void saveKey(User user, String provider, String apiKey) {
        UserProviderConfig current = resolveProviderConfig(user, slotFromLegacyProvider(provider));
        saveProviderConfig(user, new UserProviderConfig(
                current.slot(),
                legacyProviderOrDefault(provider, current.provider()),
                current.label(),
                apiKey,
                current.baseUrl(),
                current.model()
        ));
    }

    @Transactional
    public void saveProviderConfig(User user, UserProviderConfig config) {
        String slot = requireSupportedSlot(config.slot());
        UserProviderConfig merged = mergeWithDefaults(slot, config);
        String encrypted = encrypt(merged.apiKey());
        UserApiKey entity = repository.findByUserIdAndSlot(user.getId(), slot)
                .orElseGet(() -> {
                    UserApiKey newKey = new UserApiKey();
                    newKey.setUser(user);
                    newKey.setSlot(slot);
                    return newKey;
                });
        entity.setProvider(merged.provider());
        entity.setLabel(merged.label());
        entity.setApiKey(encrypted);
        entity.setBaseUrl(merged.baseUrl());
        entity.setModel(merged.model());
        repository.save(entity);
    }

    public List<KeyInfo> getKeys(User user) {
        return repository.findByUserId(user.getId()).stream()
                .map(k -> new KeyInfo(k.getProvider(), maskKey(decrypt(k.getApiKey()))))
                .toList();
    }

    public List<ProviderInfo> getProviderConfigs(User user) {
        return List.of(SLOT_LLM, SLOT_IMAGE, SLOT_WORKFLOW).stream()
                .map(slot -> toProviderInfo(resolveProviderConfig(user, slot)))
                .toList();
    }

    public String getDecryptedKey(User user, String provider) {
        String slot = isSupportedSlot(provider) ? provider : slotFromLegacyProvider(provider);
        return resolveProviderConfig(user, slot).apiKey();
    }

    public UserProviderConfig resolveProviderConfig(User user, String slot) {
        String normalizedSlot = requireSupportedSlot(slot);
        UserProviderConfig defaults = defaultConfigForSlot(normalizedSlot);
        return repository.findByUserIdAndSlot(user.getId(), normalizedSlot)
                .map(entity -> mergeWithDefaults(normalizedSlot, new UserProviderConfig(
                        normalizedSlot,
                        entity.getProvider(),
                        entity.getLabel(),
                        decrypt(entity.getApiKey()),
                        entity.getBaseUrl(),
                        entity.getModel()
                )))
                .orElse(defaults);
    }

    public UserProviderConfig requireProviderConfig(User user, String slot, String message) {
        UserProviderConfig config = resolveProviderConfig(user, slot);
        if (config.apiKey().isBlank()) {
            throw new BusinessException(400, message);
        }
        return config;
    }

    @Transactional
    public void deleteKey(User user, String provider) {
        repository.deleteByUserIdAndSlot(user.getId(), slotFromLegacyProvider(provider));
    }

    @Transactional
    public void deleteProviderConfig(User user, String slot) {
        repository.deleteByUserIdAndSlot(user.getId(), requireSupportedSlot(slot));
    }

    public List<String> discoverModels(String slot, String provider, String apiKey, String baseUrl) {
        UserProviderConfig config = mergeWithDefaults(requireSupportedSlot(slot), new UserProviderConfig(
                slot,
                provider,
                "",
                apiKey,
                baseUrl,
                ""
        ));
        if (config.apiKey().isBlank()) {
            throw new BusinessException(400, "Please enter an API key before fetching models.", config.displayName());
        }
        if (SLOT_WORKFLOW.equals(config.slot())) {
            return List.of("workflow");
        }
        try {
            String response = webClientBuilder
                    .baseUrl(config.baseUrl())
                    .build()
                    .get()
                    .uri("/models")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            List<?> items = extractModelItems(response, config);
            List<String> models = items.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(item -> Objects.toString(item.get("id"), "").trim())
                    .filter(id -> !id.isBlank())
                    .distinct()
                    .toList();
            if (models.isEmpty()) {
                throw new BusinessException(502, config.displayName() + " returned no available models.", config.displayName());
            }
            return models;
        } catch (WebClientResponseException e) {
            throw mapHttpError(e, config);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, config.displayName() + " model discovery failed: " + e.getMessage(), config.displayName());
        }
    }

    private List<?> extractModelItems(String response, UserProviderConfig config) {
        try {
            JsonNode root = objectMapper.readTree(response == null ? "" : response);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new BusinessException(502, invalidJsonMessage(config), config.displayName());
            }
            return objectMapper.convertValue(data, List.class);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, describeNonJsonResponse(config, response, "models"), config.displayName());
        }
    }

    private BusinessException mapHttpError(WebClientResponseException ex, UserProviderConfig config) {
        if (ex.getStatusCode().value() == 401) {
            return new BusinessException(401, config.displayName() + " API key is invalid or expired.", config.displayName());
        }
        String body = ex.getResponseBodyAsString();
        if (looksLikeHtml(body)) {
            return new BusinessException(ex.getStatusCode().value(),
                    describeNonJsonResponse(config, body, "models"),
                    config.displayName());
        }
        return new BusinessException(ex.getStatusCode().value(),
                config.displayName() + " model discovery failed (" + ex.getStatusCode() + "): " + compactMessage(body, ex.getMessage()),
                config.displayName());
    }

    private String invalidJsonMessage(UserProviderConfig config) {
        return config.displayName() + " returned JSON, but not an OpenAI-compatible `{\"data\":[{\"id\":\"...\"}]}` model list.";
    }

    private String describeNonJsonResponse(UserProviderConfig config, String body, String endpointName) {
        if (looksLikeHtml(body)) {
            return config.displayName() + " returned HTML instead of JSON for `" + endpointName + "`. Check that Base URL points to the API root such as `https://host/v1`, not a website page or dashboard route.";
        }
        return config.displayName() + " returned a non-JSON response for `" + endpointName + "`. " +
                "Check whether the gateway is OpenAI-compatible and whether Base URL points to the API root. Response starts with: " +
                compactMessage(body, "(empty response)");
    }

    private boolean looksLikeHtml(String body) {
        String trimmed = safe(body);
        return trimmed.startsWith("<!DOCTYPE html")
                || trimmed.startsWith("<html")
                || trimmed.startsWith("<HTML")
                || trimmed.startsWith("<");
    }

    private String compactMessage(String body, String fallback) {
        String trimmed = safe(body).replaceAll("\\s+", " ");
        if (trimmed.isBlank()) {
            return fallback;
        }
        return trimmed.length() > 180 ? trimmed.substring(0, 180) + "..." : trimmed;
    }

    private ProviderInfo toProviderInfo(UserProviderConfig config) {
        return new ProviderInfo(
                config.slot(),
                config.provider(),
                config.displayName(),
                maskKey(config.apiKey()),
                config.baseUrl(),
                config.model()
        );
    }

    private UserProviderConfig mergeWithDefaults(String slot, UserProviderConfig config) {
        UserProviderConfig defaults = defaultConfigForSlot(slot);
        return new UserProviderConfig(
                slot,
                blankToDefault(config.provider(), defaults.provider()),
                blankToDefault(config.label(), defaults.label()),
                config.apiKey(),
                blankToDefault(config.baseUrl(), defaults.baseUrl()),
                blankToDefault(config.model(), defaults.model())
        );
    }

    private UserProviderConfig defaultConfigForSlot(String slot) {
        return switch (slot) {
            case SLOT_LLM -> new UserProviderConfig(
                    SLOT_LLM,
                    "deepseek",
                    "DeepSeek Official",
                    safe(properties.getDeepseek().getApiKey()),
                    safe(properties.getDeepseek().getBaseUrl()),
                    safe(properties.getDeepseek().getModel())
            );
            case SLOT_IMAGE -> new UserProviderConfig(
                    SLOT_IMAGE,
                    "image2",
                    "Image2 Official",
                    safe(properties.getImage().getApiKey()),
                    safe(properties.getImage().getBaseUrl()),
                    safe(properties.getImage().getModel())
            );
            case SLOT_WORKFLOW -> new UserProviderConfig(
                    SLOT_WORKFLOW,
                    "coze",
                    "Coze Official",
                    safe(properties.getCoze().getApiKey()),
                    safe(properties.getCoze().getBaseUrl()),
                    safe(properties.getCoze().getWorkflowId())
            );
            default -> throw new BusinessException(400, "Unsupported provider slot: " + slot);
        };
    }

    private String slotFromLegacyProvider(String provider) {
        return switch (safe(provider)) {
            case "deepseek", SLOT_LLM -> SLOT_LLM;
            case "image2", SLOT_IMAGE -> SLOT_IMAGE;
            case "coze", SLOT_WORKFLOW -> SLOT_WORKFLOW;
            default -> throw new BusinessException(400, "Unsupported provider: " + provider);
        };
    }

    private String legacyProviderOrDefault(String provider, String fallback) {
        return switch (safe(provider)) {
            case "deepseek", "image2", "coze" -> provider.trim();
            default -> fallback;
        };
    }

    private String requireSupportedSlot(String slot) {
        if (!isSupportedSlot(slot)) {
            throw new BusinessException(400, "Unsupported provider slot: " + slot);
        }
        return slot.trim();
    }

    private boolean isSupportedSlot(String slot) {
        return Map.of(SLOT_LLM, true, SLOT_IMAGE, true, SLOT_WORKFLOW, true).containsKey(safe(slot));
    }

    private String encrypt(String plainText) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(ENCRYPTION_KEY, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return Base64.getEncoder().encodeToString(cipher.doFinal(safe(plainText).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt API key", e);
        }
    }

    private String decrypt(String encrypted) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(ENCRYPTION_KEY, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt API key", e);
        }
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "(not set)";
        return key.substring(0, 7) + "****" + key.substring(key.length() - 4);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
