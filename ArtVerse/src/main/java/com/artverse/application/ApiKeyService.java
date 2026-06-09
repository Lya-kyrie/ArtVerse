package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.User;
import com.artverse.domain.UserApiKey;
import com.artverse.persistence.UserApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String ALGORITHM = "AES";
    private static final byte[] ENCRYPTION_KEY = "ArtVerse!ApiKey1".getBytes(StandardCharsets.UTF_8);

    private final UserApiKeyRepository repository;

    @Transactional
    public void saveKey(User user, String provider, String apiKey) {
        if (!List.of("deepseek", "image2", "coze").contains(provider)) {
            throw new BusinessException(400, "Unsupported provider: " + provider);
        }
        String encrypted = encrypt(apiKey);
        UserApiKey entity = repository.findByUserIdAndProvider(user.getId(), provider)
                .orElseGet(() -> {
                    UserApiKey newKey = new UserApiKey();
                    newKey.setUser(user);
                    newKey.setProvider(provider);
                    return newKey;
                });
        entity.setApiKey(encrypted);
        repository.save(entity);
    }

    public record KeyInfo(String provider, String apiKeyMasked) {}

    public List<KeyInfo> getKeys(User user) {
        return repository.findByUserId(user.getId()).stream()
                .map(k -> {
                    String decrypted = decrypt(k.getApiKey());
                    return new KeyInfo(k.getProvider(), maskKey(decrypted));
                })
                .toList();
    }

    public String getDecryptedKey(User user, String provider) {
        return repository.findByUserIdAndProvider(user.getId(), provider)
                .map(k -> decrypt(k.getApiKey()))
                .orElse(null);
    }

    @Transactional
    public void deleteKey(User user, String provider) {
        repository.findByUserIdAndProvider(user.getId(), provider)
                .ifPresent(repository::delete);
    }

    private String encrypt(String plainText) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(ENCRYPTION_KEY, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));
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
}
