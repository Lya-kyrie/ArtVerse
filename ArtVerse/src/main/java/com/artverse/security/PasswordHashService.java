package com.artverse.security;

import com.artverse.config.BCryptPasswordEncoder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordHashService {

    private static final String DUMMY_PASSWORD = "ArtVerse::dummy-password";

    private final BCryptPasswordEncoder legacyEncoder;
    private final Argon2PasswordEncoder passwordEncoder;
    private final String dummyHash;

    public PasswordHashService(BCryptPasswordEncoder legacyEncoder) {
        this.legacyEncoder = legacyEncoder;
        this.passwordEncoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
        this.dummyHash = passwordEncoder.encode(DUMMY_PASSWORD);
    }

    public String encode(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        if (isArgon2Hash(encodedPassword)) {
            return passwordEncoder.matches(rawPassword, encodedPassword);
        }
        return legacyEncoder.matches(rawPassword, encodedPassword);
    }

    public boolean requiresUpgrade(String encodedPassword) {
        return encodedPassword != null && !encodedPassword.isBlank() && !isArgon2Hash(encodedPassword);
    }

    public void consumeDummyCheck(String rawPassword) {
        passwordEncoder.matches(rawPassword == null ? "" : rawPassword, dummyHash);
    }

    private boolean isArgon2Hash(String encodedPassword) {
        return encodedPassword.startsWith("$argon2");
    }
}
