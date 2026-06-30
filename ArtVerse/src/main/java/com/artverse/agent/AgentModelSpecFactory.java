package com.artverse.agent;

import com.artverse.application.UserProviderConfig;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@RequiredArgsConstructor
public class AgentModelSpecFactory {

    private static final int HASH_PREFIX_LENGTH = 12;

    private final ArtVerseProperties properties;

    /**
     * Converts a user-saved provider configuration into the agent model spec
     * used by the workflow engine. Resolves the effective API key: the user's
     * own key takes precedence over the server-configured key.
     */
    public AgentModelSpec fromProviderConfig(UserProviderConfig config) {
        String effectiveKey = resolveEffectiveApiKey(config.apiKey(),
                apiKeyForProvider(config.provider()));
        return new AgentModelSpec(
                config.provider(),
                config.baseUrl(),
                config.model(),
                shortHash(effectiveKey)
        );
    }

    private String apiKeyForProvider(String provider) {
        return switch (provider) {
            case "deepseek" -> safe(properties.getDeepseek().getApiKey());
            case "image2" -> safe(properties.getImage().getApiKey());
            case "coze" -> safe(properties.getCoze().getApiKey());
            default -> "";
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public AgentModelSpec deepSeek(String userApiKey) {
        ArtVerseProperties.DeepSeek deepseek = properties.getDeepseek();
        return new AgentModelSpec(
                "deepseek",
                deepseek.getBaseUrl(),
                deepseek.getModel(),
                shortHash(resolveEffectiveApiKey(userApiKey, deepseek.getApiKey()))
        );
    }

    private String resolveEffectiveApiKey(String userApiKey, String configuredApiKey) {
        if (userApiKey != null && !userApiKey.isBlank()) {
            return userApiKey;
        }
        if (configuredApiKey != null && !configuredApiKey.isBlank()) {
            return configuredApiKey;
        }
        return "";
    }

    public static String shortHash(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String hash = sha256Hex(value);
        return hash.substring(0, Math.min(HASH_PREFIX_LENGTH, hash.length()));
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to hash agent model value");
        }
    }
}
