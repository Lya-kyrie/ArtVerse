package com.artverse.security;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderEndpointPolicyTest {

    @Test
    void rejectsPrivateAddressAndSensitiveHeaders() {
        ProviderEndpointPolicy policy = new ProviderEndpointPolicy(new ArtVerseProperties());

        assertThatThrownBy(() -> policy.requireSafeBaseUrl("https://127.0.0.1/v1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("private or reserved");
        assertThatThrownBy(() -> policy.validateCustomHeaders(Map.of("Host", "internal")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void permitsExplicitPrivateCidrException() {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getAgent().setProviderAllowedPrivateCidrs(List.of("127.0.0.0/8"));
        ProviderEndpointPolicy policy = new ProviderEndpointPolicy(properties);

        assertThat(policy.requireSafeBaseUrl("https://127.0.0.1/v1").getHost())
                .isEqualTo("127.0.0.1");
    }
}
