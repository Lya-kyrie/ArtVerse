package com.artverse.api;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.application.ApiKeyService;
import com.artverse.application.SceneService;
import com.artverse.common.BusinessException;
import com.artverse.common.aspect.RateLimit;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chapters/{chapterId}")
@RequiredArgsConstructor
public class StoryboardController {

    private final SceneService sceneService;
    private final UserRepository userRepository;
    private final ApiKeyService apiKeyService;

    @PostMapping("/generate-scenes")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "scenes")
    public Map<String, List<String>> generateScenes(@PathVariable Long chapterId) {
        User user = currentUser();
        String cozeApiKey = apiKeyService.getDecryptedKey(user, "coze");
        List<String> scenes = sceneService.generateScenes(chapterId, cozeApiKey);
        return Map.of("scenes", scenes);
    }

    @GetMapping("/scenes")
    public Map<String, List<String>> getScenes(@PathVariable Long chapterId) {
        List<String> scenes = sceneService.getScenes(chapterId);
        return Map.of("scenes", scenes);
    }

    @PutMapping("/scenes")
    public Map<String, List<String>> updateScenes(@PathVariable Long chapterId, @RequestBody List<String> scenes) {
        List<String> updated = sceneService.updateScenes(chapterId, scenes);
        return Map.of("scenes", updated);
    }

    private User currentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
    }
}
