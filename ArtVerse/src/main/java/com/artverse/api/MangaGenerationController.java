package com.artverse.api;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaGenerationService;
import com.artverse.common.BusinessException;
import com.artverse.common.aspect.SingleFlight;
import com.artverse.domain.MangaImage;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 漫画生成 Controller（Sa-Token 方案）。
 * <p>
 * - /generate-manga-stream：单飞 + SSE 流式
 * - /regenerate-image：单图重生成
 */
@RestController
@RequestMapping("/api/chapters/{chapterId}")
@RequiredArgsConstructor
public class MangaGenerationController {

    private final MangaGenerationService mangaGenerationService;
    private final UserRepository userRepository;
    private final ApiKeyService apiKeyService;

    @PostMapping("/generate-manga-stream")
    @SingleFlight(ttlSeconds = 600, key = "generate-manga")
    public SseEmitter generateMangaStream(@PathVariable Long chapterId) {
        User user = currentUser();
        String imageApiKey = apiKeyService.getDecryptedKey(user, "image2");
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        return mangaGenerationService.generateMangaStream(chapterId, imageApiKey, deepseekApiKey);
    }

    @PostMapping("/regenerate-image/{imageNumber}")
    public MangaImage regenerateImage(@PathVariable Long chapterId,
                                      @PathVariable int imageNumber,
                                      @RequestBody Map<String, String> body) {
        User user = currentUser();
        String imageApiKey = apiKeyService.getDecryptedKey(user, "image2");
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        return mangaGenerationService.regenerateImage(chapterId, imageNumber, body.get("prompt"), imageApiKey, deepseekApiKey);
    }

    private User currentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
    }
}
