package com.artverse.api;

import com.artverse.application.ApiKeyService;
import com.artverse.application.ChatService;
import com.artverse.application.CurrentUserService;
import com.artverse.application.UserProviderConfig;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chapters/{chapterId}")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ApiKeyService apiKeyService;
    private final CurrentUserService currentUserService;

    @PostMapping("/chat")
    public SseEmitter chat(@PathVariable Long chapterId,
                           @RequestBody Map<String, String> body) {
        String content = body.get("message");
        String model = body.get("model");
        chatService.saveUserMessage(chapterId, content);

        User user = currentUserService.requireCurrentUser();
        UserProviderConfig llmConfig = overrideModel(
                apiKeyService.requireProviderConfig(
                        user,
                        ApiKeyService.SLOT_LLM,
                        "Please configure an LLM provider API key in Settings before using story chat."
                ),
                model
        );
        return chatService.streamChat(chapterId, user.getId(), llmConfig);
    }

    @GetMapping("/messages")
    public List<ChatMessage> getMessages(@PathVariable Long chapterId) {
        return chatService.getMessages(chapterId);
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
