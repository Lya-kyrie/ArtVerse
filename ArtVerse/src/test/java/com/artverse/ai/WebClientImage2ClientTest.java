package com.artverse.ai;

import com.artverse.application.UserProviderConfig;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;

class WebClientImage2ClientTest {

    @Test
    void mapsUnauthorizedToClearApiKeyMessage() {
        WebClientImage2Client client = new WebClientImage2Client(new ArtVerseProperties(), new ObjectMapper());

        WebClientResponseException ex = WebClientResponseException.create(
                401,
                "Unauthorized",
                null,
                "{\"error\":{\"message\":\"Invalid token\"}}".getBytes(),
                null
        );

        UserProviderConfig config = new UserProviderConfig("image", "image2", "Image2", "sk-test", "https://api.example.com/v1", "test-model");
        BusinessException mapped = invokeMapHttpError(client, ex, config);

        assertThat(mapped.getStatus()).isEqualTo(401);
        assertThat(mapped.getProvider()).isEqualTo("Image2");
        assertThat(mapped.getMessage()).contains("API key is invalid");
    }

    private BusinessException invokeMapHttpError(WebClientImage2Client client, WebClientResponseException ex, UserProviderConfig config) {
        try {
            var method = WebClientImage2Client.class.getDeclaredMethod("mapHttpError", WebClientResponseException.class, UserProviderConfig.class);
            method.setAccessible(true);
            return (BusinessException) method.invoke(client, ex, config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
