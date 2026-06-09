package com.artverse.api;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.api.dto.AuthDtos.*;
import com.artverse.application.ApiKeyService;
import com.artverse.application.ApiKeyService.KeyInfo;
import com.artverse.common.BusinessException;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户 Controller（Sa-Token 方案）。
 * <p>
 * 改用 {@link StpUtil#getLoginIdAsLong()} 获取当前用户 ID，
 * 替代原 SecurityContextHolder 方式。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
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
        Long userId = StpUtil.getLoginIdAsLong();
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
    }
}
