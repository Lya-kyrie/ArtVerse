package com.artverse.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Auth DTO JSON contract")
class AuthDtosTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("accepts the frontend refresh_token field")
    void acceptsSnakeCaseRefreshToken() throws Exception {
        AuthDtos.RefreshRequest request = objectMapper.readValue(
                "{\"refresh_token\":\"token-value\"}",
                AuthDtos.RefreshRequest.class
        );

        assertThat(request.refreshToken()).isEqualTo("token-value");
    }

    @Test
    @DisplayName("keeps accepting the Java refreshToken field")
    void acceptsCamelCaseRefreshToken() throws Exception {
        AuthDtos.RefreshRequest request = objectMapper.readValue(
                "{\"refreshToken\":\"token-value\"}",
                AuthDtos.RefreshRequest.class
        );

        assertThat(request.refreshToken()).isEqualTo("token-value");
    }
}
