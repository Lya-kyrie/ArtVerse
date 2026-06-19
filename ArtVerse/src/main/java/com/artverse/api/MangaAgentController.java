package com.artverse.api;

import com.artverse.api.dto.MangaAgentDtos;
import com.artverse.application.CurrentUserService;
import com.artverse.application.MangaAgentService;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chapters/{chapterId}/manga-agent")
@RequiredArgsConstructor
public class MangaAgentController {

    private final MangaAgentService mangaAgentService;
    private final CurrentUserService currentUserService;

    @GetMapping("/messages")
    public MangaAgentDtos.MessagesResponse messages(@PathVariable Long chapterId) {
        User user = currentUserService.requireCurrentUser();
        return new MangaAgentDtos.MessagesResponse(
                mangaAgentService.listMessages(chapterId, user).stream()
                        .map(MangaAgentDtos.MessageDto::from)
                        .toList()
        );
    }

    @PostMapping("/run")
    public MangaAgentDtos.RunResponse run(@PathVariable Long chapterId,
                                          @RequestBody MangaAgentDtos.RunRequest body) {
        User user = currentUserService.requireCurrentUser();
        MangaAgentService.RunResult result = mangaAgentService.run(chapterId, body.message(), body.requestId(), user);
        return new MangaAgentDtos.RunResponse(result.reply(), result.requestId());
    }

    @PostMapping("/run-stream")
    public SseEmitter runStream(@PathVariable Long chapterId,
                                @RequestBody MangaAgentDtos.RunRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.runStream(chapterId, body.message(), body.requestId(), user);
    }
}
