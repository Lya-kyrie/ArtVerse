package com.artverse.api;

import com.artverse.application.ImageGenService;
import com.artverse.application.ApiKeyService;
import com.artverse.application.UserProviderConfig;
import com.artverse.guard.GenerationGuardService;
import com.artverse.application.CurrentUserService;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/image-gen")
@RequiredArgsConstructor
public class ImageGenController {

    private final ImageGenService imageGenService;
    private final ApiKeyService apiKeyService;
    private final GenerationGuardService generationGuardService;
    private final CurrentUserService currentUserService;

    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, Object> body) {
        String prompt = body.get("prompt") != null ? body.get("prompt").toString() : "";
        String size = body.get("size") != null ? body.get("size").toString() : "";
        String model = body.get("model") != null ? body.get("model").toString() : "";
        @SuppressWarnings("unchecked")
        List<String> referenceImages = (List<String>) body.get("reference_images");
        User user = currentUser();
        UserProviderConfig imageConfig = overrideModel(
                apiKeyService.resolveProviderConfig(user, ApiKeyService.SLOT_IMAGE),
                model
        );
        return generationGuardService.executeImageGeneration(
                user.getId(),
                prompt,
                referenceImages,
                size,
                imageConfig.primaryModel(),
                () -> imageGenService.generate(prompt, referenceImages, imageConfig, size)
        );
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return imageGenService.listHistory(page, size);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        imageGenService.delete(id);
    }

    private User currentUser() {
        return currentUserService.requireCurrentUser();
    }

    private UserProviderConfig overrideModel(UserProviderConfig config, String model) {
        if (model == null || model.isBlank()) {
            return config;
        }
        return new UserProviderConfig(
                config.slot(),
                config.provider(),
                config.label(),
                config.apiKey(),
                config.baseUrl(),
                model
        );
    }
}
