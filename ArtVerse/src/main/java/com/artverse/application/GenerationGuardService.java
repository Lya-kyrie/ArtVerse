package com.artverse.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class GenerationGuardService {

    private final IdempotencyService idempotencyService;
    private final GenerationRequestKeyBuilder keyBuilder;

    public Map<String, Object> executeImageGeneration(Long userId, String prompt, java.util.List<String> referenceImages,
                                                      Callable<Map<String, Object>> leader) {
        return idempotencyService.executeHttp(
                "image-gen",
                userKey(userId),
                keyBuilder.imageGeneration(userId, prompt, referenceImages),
                leader
        );
    }

    public Map<String, Object> executeSceneGeneration(Long userId, Long chapterId,
                                                      Callable<Map<String, Object>> leader) {
        return idempotencyService.executeHttp(
                "generate-scenes",
                userKey(userId),
                keyBuilder.sceneGeneration(userId, chapterId),
                leader
        );
    }

    public Map<String, Object> executeImageRegeneration(Long userId, Long chapterId, int imageNumber, String prompt,
                                                        Callable<Map<String, Object>> leader) {
        return idempotencyService.executeHttp(
                "regenerate-image",
                userKey(userId),
                keyBuilder.imageRegeneration(userId, chapterId, imageNumber, prompt),
                leader
        );
    }

    public MangaStreamGuard guardMangaStream(Long userId, Long chapterId) {
        Map<String, Object> canonical = keyBuilder.mangaGeneration(userId, chapterId);
        idempotencyService.rejectIfProcessing("generate-manga", userKey(userId), canonical);
        idempotencyService.markProcessing("generate-manga", userKey(userId), canonical);
        return new MangaStreamGuard(
                () -> idempotencyService.markSucceeded("generate-manga", userKey(userId), canonical, Map.of("chapter_id", chapterId)),
                error -> idempotencyService.markFailed("generate-manga", userKey(userId), canonical, error)
        );
    }

    private String userKey(Long userId) {
        return "u" + userId;
    }

    public record MangaStreamGuard(Runnable onComplete, Consumer<String> onError) {
    }
}
