package com.artverse.api;

import com.artverse.application.ApiKeyService;
import com.artverse.application.CurrentUserService;
import com.artverse.application.UserProviderConfig;
import com.artverse.guard.GenerationGuardService;
import com.artverse.application.MangaGenerationService;
import com.artverse.domain.MangaImage;
import com.artverse.domain.User;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/chapters/{chapterId}")
@RequiredArgsConstructor
public class MangaGenerationController {

    private final MangaGenerationService mangaGenerationService;
    private final ApiKeyService apiKeyService;
    private final GenerationGuardService generationGuardService;
    private final CurrentUserService currentUserService;

    @PostMapping("/generate-manga-stream")
    public SseEmitter generateMangaStream(@PathVariable Long chapterId,
                                          @RequestBody(required = false) MangaGenerationRequest body) {
        User user = currentUser();
        GenerationGuardService.MangaStreamGuard guard = generationGuardService.guardMangaStream(user.getId(), chapterId);
        UserProviderConfig imageConfig = resolveImageConfig(user, body == null ? null : body.model(), body == null ? null : body.configId());
        Long assetGroupId = body == null ? null : body.assetGroupId();
        return mangaGenerationService.generateMangaStream(chapterId, assetGroupId, user.getId(), imageConfig,
                guard.onComplete(),
                guard.onError());
    }

    @PostMapping("/regenerate-image/{imageNumber}")
    public MangaImage regenerateImage(@PathVariable Long chapterId,
                                      @PathVariable int imageNumber,
                                      @RequestBody RegenerateImageRequest body) {
        User user = currentUser();
        UserProviderConfig imageConfig = resolveImageConfig(user, body == null ? null : body.model(), body == null ? null : body.configId());
        String prompt = body == null ? null : body.prompt();
        Map<String, Object> result = generationGuardService.executeImageRegeneration(
                user.getId(),
                chapterId,
                imageNumber,
                prompt,
                imageConfig.model(),
                () -> mangaImageToMap(mangaGenerationService.regenerateImage(chapterId, imageNumber, prompt, imageConfig))
        );
        return mapToMangaImage(result);
    }

    @GetMapping("/image-request-preview")
    public Map<String, Object> previewImageRequest(@PathVariable Long chapterId,
                                                   @RequestParam(defaultValue = "1") int imageNumber,
                                                   @RequestParam(required = false) Long assetGroupId) {
        User user = currentUser();
        return mangaGenerationService.previewImageRequest(chapterId, assetGroupId, user.getId(), imageNumber);
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
        return currentUserService.requireCurrentUser();
    }

    private UserProviderConfig resolveImageConfig(User user, String model, Long configId) {
        if (model == null || model.isBlank()) {
            return apiKeyService.resolveProviderConfig(user, ApiKeyService.SLOT_IMAGE);
        }
        return apiKeyService.requireProviderConfigForModel(
                user,
                ApiKeyService.SLOT_IMAGE,
                configId,
                model,
                "Image API key is missing. Please set it in Settings."
        );
    }

    private record MangaGenerationRequest(
            Long assetGroupId,
            String model,
            @JsonProperty("config_id") Long configId
    ) {}

    private record RegenerateImageRequest(
            String prompt,
            String model,
            @JsonProperty("config_id") Long configId
    ) {}
}
