package com.artverse.api;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.application.ApiKeyService;
import com.artverse.application.IdempotencyService;
import com.artverse.application.MangaGenerationService;
import com.artverse.common.BusinessException;
import com.artverse.domain.MangaImage;
import com.artverse.domain.User;
import com.artverse.persistence.ChapterRepository;
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
    private final IdempotencyService idempotencyService;
    private final ChapterRepository chapterRepository;

    @PostMapping("/generate-manga-stream")
    public SseEmitter generateMangaStream(@PathVariable Long chapterId) {
        User user = currentUser();
        var chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        Map<String, Object> canonical = Map.of(
                "action", "generate-manga",
                "userId", user.getId(),
                "chapterId", chapterId,
                "storyId", chapter.getStory().getId(),
                "imageCount", chapter.getImageCount(),
                "colorMode", String.valueOf(chapter.getColorMode()),
                "mangaStyle", chapter.getStory().getMangaStyle() == null ? "" : chapter.getStory().getMangaStyle(),
                "assetGroupId", chapter.getAssetGroup() == null ? 0 : chapter.getAssetGroup().getId(),
                "scenes", chapter.getScenesText() == null ? "" : chapter.getScenesText()
        );
        idempotencyService.rejectIfProcessing("generate-manga", "u" + user.getId(), canonical);
        idempotencyService.markProcessing("generate-manga", "u" + user.getId(), canonical);
        String imageApiKey = apiKeyService.getDecryptedKey(user, "image2");
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        return mangaGenerationService.generateMangaStream(chapterId, imageApiKey, deepseekApiKey,
                () -> idempotencyService.markSucceeded("generate-manga", "u" + user.getId(), canonical, Map.of("chapter_id", chapterId)),
                error -> idempotencyService.markFailed("generate-manga", "u" + user.getId(), canonical, error));
    }

    @PostMapping("/regenerate-image/{imageNumber}")
    public MangaImage regenerateImage(@PathVariable Long chapterId,
                                      @PathVariable int imageNumber,
                                      @RequestBody Map<String, String> body) {
        User user = currentUser();
        String imageApiKey = apiKeyService.getDecryptedKey(user, "image2");
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        String prompt = body.get("prompt");
        var chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        Map<String, Object> canonical = Map.of(
                "action", "regenerate-image",
                "userId", user.getId(),
                "chapterId", chapterId,
                "storyId", chapter.getStory().getId(),
                "imageNumber", imageNumber,
                "prompt", idempotencyService.normalizeText(prompt),
                "colorMode", String.valueOf(chapter.getColorMode()),
                "mangaStyle", chapter.getStory().getMangaStyle() == null ? "" : chapter.getStory().getMangaStyle(),
                "assetGroupId", chapter.getAssetGroup() == null ? 0 : chapter.getAssetGroup().getId()
        );
        Map<String, Object> result = idempotencyService.executeHttp(
                "regenerate-image",
                "u" + user.getId(),
                canonical,
                () -> mangaImageToMap(mangaGenerationService.regenerateImage(chapterId, imageNumber, prompt, imageApiKey, deepseekApiKey))
        );
        return mapToMangaImage(result);
    }

    private Map<String, Object> mangaImageToMap(MangaImage image) {
        return Map.of(
                "id", image.getId(),
                "image_number", image.getImageNumber(),
                "image_path", image.getImagePath(),
                "prompt", image.getPrompt() == null ? "" : image.getPrompt()
        );
    }

    private MangaImage mapToMangaImage(Map<String, Object> map) {
        MangaImage image = new MangaImage();
        image.setId(((Number) map.get("id")).longValue());
        image.setImageNumber(((Number) map.get("image_number")).intValue());
        image.setImagePath(String.valueOf(map.get("image_path")));
        image.setPrompt(String.valueOf(map.getOrDefault("prompt", "")));
        return image;
    }

    private User currentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
    }
}
