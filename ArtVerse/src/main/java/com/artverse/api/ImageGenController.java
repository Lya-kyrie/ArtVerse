package com.artverse.api;

import com.artverse.application.ImageGenService;
import com.artverse.application.ApiKeyService;
import com.artverse.common.BusinessException;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import cn.dev33.satoken.stp.StpUtil;
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
    private final UserRepository userRepository;

    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, Object> body) {
        String prompt = body.get("prompt") != null ? body.get("prompt").toString() : "";
        @SuppressWarnings("unchecked")
        List<String> referenceImages = (List<String>) body.get("reference_images");
        User user = currentUser();
        String imageApiKey = apiKeyService.getDecryptedKey(user, "image2");
        return imageGenService.generate(prompt, referenceImages, imageApiKey);
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
        Long userId = StpUtil.getLoginIdAsLong();
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
    }
}
