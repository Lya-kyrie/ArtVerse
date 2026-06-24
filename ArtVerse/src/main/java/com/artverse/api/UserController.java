package com.artverse.api;

import com.artverse.api.dto.AuthDtos.*;
import com.artverse.application.ApiKeyService;
import com.artverse.application.ApiKeyService.KeyInfo;
import com.artverse.application.CurrentUserService;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final CurrentUserService currentUserService;
    private final ApiKeyService apiKeyService;

    @GetMapping("/me")
    public ResponseEntity<UserInfo> me() {
        User user = currentUser();
        return ResponseEntity.ok(new UserInfo(user.getId(), user.getUsername(), user.getEmail()));
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

    private User currentUser() {
        return currentUserService.requireCurrentUser();
    }
}
