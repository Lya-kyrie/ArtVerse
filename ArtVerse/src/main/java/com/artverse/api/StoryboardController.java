package com.artverse.api;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.application.ApiKeyService;
import com.artverse.application.IdempotencyService;
import com.artverse.application.SceneService;
import com.artverse.common.BusinessException;
import com.artverse.common.aspect.RateLimit;
import com.artverse.domain.User;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/chapters/{chapterId}")
@RequiredArgsConstructor
public class StoryboardController {

    private final SceneService sceneService;
    private final UserRepository userRepository;
    private final ApiKeyService apiKeyService;
    private final IdempotencyService idempotencyService;
    private final ChapterRepository chapterRepository;

    @PostMapping("/generate-scenes")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "scenes")
    public Map<String, Object> generateScenes(@PathVariable Long chapterId) {
        User user = currentUser();
        String cozeApiKey = apiKeyService.getDecryptedKey(user, "coze");
        var chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("action", "generate-scenes");
        canonical.put("userId", user.getId());
        canonical.put("chapterId", chapterId);
        canonical.put("storyId", chapter.getStory().getId());
        canonical.put("imageCount", chapter.getImageCount());
        canonical.put("status", String.valueOf(chapter.getStatus()));
        canonical.put("colorMode", String.valueOf(chapter.getColorMode()));
        canonical.put("assetGroupId", chapter.getAssetGroup() == null ? 0 : chapter.getAssetGroup().getId());
        canonical.put("refImage", chapter.getRefImage() == null ? "" : chapter.getRefImage());
        canonical.put("novelContent", idempotencyService.normalizeText(chapter.getNovelContent()));
        canonical.put("messages", chapter.novelContentOrJoinedMessages());
        canonical.put("scenes", chapter.getScenesText() == null ? "" : chapter.getScenesText());
        return idempotencyService.executeHttp(
                "generate-scenes",
                "u" + user.getId(),
                canonical,
                () -> Map.of("scenes", sceneService.generateScenes(chapterId, cozeApiKey))
        );
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
