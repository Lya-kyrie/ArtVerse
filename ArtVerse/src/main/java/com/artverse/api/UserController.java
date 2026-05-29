package com.artverse.api;

import com.artverse.api.dto.AuthDtos.*;
import com.artverse.application.ApiKeyService;
import com.artverse.application.ApiKeyService.KeyInfo;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final ApiKeyService apiKeyService;

    @GetMapping("/me")
    public ResponseEntity<UserInfo> me() {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        User user = userRepository.findById(userId).orElseThrow();
        return ResponseEntity.ok(new UserInfo(user.getId(), user.getUsername(), user.getEmail()));
    }

    @GetMapping("/api-keys")
    public ResponseEntity<List<ApiKeyResponse>> listKeys() {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        User user = userRepository.findById(userId).orElseThrow();
        List<KeyInfo> keys = apiKeyService.getKeys(user);
        return ResponseEntity.ok(keys.stream()
                .map(k -> new ApiKeyResponse(k.provider(), k.apiKeyMasked()))
                .toList());
    }

    @PutMapping("/api-keys")
    public ResponseEntity<ApiKeyResponse> saveKey(@RequestBody ApiKeyRequest req) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        User user = userRepository.findById(userId).orElseThrow();
        apiKeyService.saveKey(user, req.provider(), req.apiKey());
        List<KeyInfo> keys = apiKeyService.getKeys(user);
        KeyInfo saved = keys.stream()
                .filter(k -> k.provider().equals(req.provider()))
                .findFirst()
                .orElseThrow();
        return ResponseEntity.ok(new ApiKeyResponse(saved.provider(), saved.apiKeyMasked()));
    }
}
