package com.artverse.security;

import com.artverse.config.ArtVerseProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class AuthStartupValidator {

    private final ArtVerseProperties properties;

    public AuthStartupValidator(ArtVerseProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        if (properties.getAuth().getChallenge().getMode() != ArtVerseProperties.ChallengeMode.ENFORCE) {
            return;
        }
        if (!"turnstile".equalsIgnoreCase(properties.getAuth().getChallenge().getProvider())) {
            throw new IllegalStateException("Only Turnstile challenge provider is supported");
        }
        if (blank(properties.getAuth().getChallenge().getSiteKey())
                || blank(properties.getAuth().getChallenge().getSecretKey())) {
            throw new IllegalStateException("Challenge enforce mode requires site and secret keys");
        }
        if (properties.getAuth().getChallenge().getAllowedHostnames() == null
                || properties.getAuth().getChallenge().getAllowedHostnames().stream().allMatch(this::blank)) {
            throw new IllegalStateException("Challenge enforce mode requires allowed hostnames");
        }
        if (blank(properties.getAuth().getRisk().getHmacKey())) {
            throw new IllegalStateException("Challenge enforce mode requires auth risk HMAC key");
        }
        if (!properties.getAuth().getCookie().isSecure()) {
            throw new IllegalStateException("Challenge enforce mode requires secure auth cookies");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
