package com.artverse.api;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.application.ApiKeyService;
import com.artverse.application.ChatService;
import com.artverse.common.BusinessException;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
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
    private final UserRepository userRepository;
    private final ApiKeyService apiKeyService;

    @PostMapping("/chat")
    public SseEmitter chat(@PathVariable Long chapterId,
                           @RequestBody Map<String, String> body) {
        String content = body.get("message");

        // Save user message first
        chatService.saveUserMessage(chapterId, content);

        // Get user's DeepSeek API key
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");

        // Stream response with user's API key
        return chatService.streamChat(chapterId, content, deepseekApiKey);
    }

    @GetMapping("/messages")
    public List<ChatMessage> getMessages(@PathVariable Long chapterId) {
        return chatService.getMessages(chapterId);
    }
}
