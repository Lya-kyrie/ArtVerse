package com.artverse.api;

import com.artverse.api.dto.AuthDtos.*;
import com.artverse.application.ApiKeyService;
import com.artverse.application.ApiKeyService.KeyInfo;
import com.artverse.application.ApiKeyService.ProviderInfo;
import com.artverse.application.CurrentUserService;
import com.artverse.application.UserProviderConfig;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final CurrentUserService currentUserService;
    private final ApiKeyService apiKeyService;

    @GetMapping("/me")
    public ResponseEntity<UserInfo> me() {
        User user = currentUser();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new UserInfo(user.getId(), user.getUsername(), user.getEmail()));
    }

    @GetMapping("/api-keys")
    public ResponseEntity<List<ApiKeyResponse>> listKeys() {
        User user = currentUser();
        List<KeyInfo> keys = apiKeyService.getKeys(user);
        return ResponseEntity.ok(keys.stream()
                .map(k -> new ApiKeyResponse(k.provider(), k.apiKeyMasked()))
                .toList());
    }

    @PutMapping("/api-keys")
    public ResponseEntity<ApiKeyResponse> saveKey(@RequestBody ApiKeyRequest req) {
        User user = currentUser();
        apiKeyService.saveKey(user, req.provider(), req.apiKey());
        List<KeyInfo> keys = apiKeyService.getKeys(user);
        KeyInfo saved = keys.stream()
                .filter(k -> k.provider().equals(req.provider()))
                .findFirst()
                .orElseThrow();
        return ResponseEntity.ok(new ApiKeyResponse(saved.provider(), saved.apiKeyMasked()));
    }

    @DeleteMapping("/api-keys/{provider}")
    public ResponseEntity<Void> deleteKey(@PathVariable String provider) {
        User user = currentUser();
        apiKeyService.deleteKey(user, provider);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/provider-configs")
    public ResponseEntity<List<ProviderInfo>> getProviderConfigs() {
        User user = currentUser();
        return ResponseEntity.ok(apiKeyService.getProviderConfigs(user));
    }

    @GetMapping("/provider-configs/{configId}/api-key")
    public ResponseEntity<Map<String, String>> getProviderApiKey(@PathVariable Long configId) {
        String apiKey = apiKeyService.getProviderApiKey(currentUser(), configId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("api_key", apiKey));
    }

    @PutMapping("/provider-configs")
    public ResponseEntity<ProviderInfo> saveProviderConfig(@RequestBody Map<String, Object> body) {
        User user = currentUser();
        UserProviderConfig config = new UserProviderConfig(
                str(body, "slot"),
                str(body, "provider"),
                str(body, "label"),
                str(body, "api_key"),
                str(body, "base_url"),
                str(body, "model")
        );
        return ResponseEntity.ok(apiKeyService.saveProviderConfig(user, longValue(body, "config_id"), config, boolValue(body, "active")));
    }

    @DeleteMapping("/provider-configs/{configId}")
    public ResponseEntity<Void> deleteProviderConfig(@PathVariable Long configId) {
        apiKeyService.deleteProviderConfig(currentUser(), configId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/provider-configs/{configId}/activate")
    public ResponseEntity<ProviderInfo> activateProviderConfig(@PathVariable Long configId) {
        return ResponseEntity.ok(apiKeyService.activateProviderConfig(currentUser(), configId));
    }

    @PostMapping("/provider-configs/{configId}/deactivate")
    public ResponseEntity<ProviderInfo> deactivateProviderConfig(@PathVariable Long configId) {
        return ResponseEntity.ok(apiKeyService.deactivateProviderConfig(currentUser(), configId));
    }

    @PostMapping("/provider-models/discover")
    public ResponseEntity<Map<String, Object>> discoverModels(@RequestBody Map<String, Object> body) {
        List<String> models = apiKeyService.discoverModels(
                currentUser(),
                str(body, "slot"),
                str(body, "provider"),
                str(body, "api_key"),
                str(body, "base_url"),
                longValue(body, "config_id")
        );
        return ResponseEntity.ok(Map.of("models", models));
    }

    private User currentUser() {
        return currentUserService.requireCurrentUser();
    }

    private static String str(Map<String, Object> body, String key) {
        Object val = body.get(key);
        return val == null ? "" : val.toString();
    }

    private static Long longValue(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private static boolean boolValue(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value != null && Boolean.parseBoolean(value.toString());
    }
}
