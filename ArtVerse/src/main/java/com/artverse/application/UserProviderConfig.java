package com.artverse.application;

public record UserProviderConfig(
        String slot,
        String provider,
        String label,
        String apiKey,
        String baseUrl,
        String model
) {
    public UserProviderConfig {
        slot = blankToDefault(slot, "llm");
        provider = blankToDefault(provider, "");
        label = blankToDefault(label, "");
        apiKey = blankToDefault(apiKey, "");
        baseUrl = blankToDefault(baseUrl, "");
        model = blankToDefault(model, "");
    }

    /**
     * The user-facing display name for this provider configuration.
     * Used in error messages and UI labels.
     */
    public String displayName() {
        return label;
    }

    /**
     * Alias for {@link #model()} — used in image generation contexts.
     */
    public String primaryModel() {
        return model;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
