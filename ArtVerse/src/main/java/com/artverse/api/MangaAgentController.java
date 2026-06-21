package com.artverse.api;

import com.artverse.api.dto.MangaAgentDtos;
import com.artverse.application.CurrentUserService;
import com.artverse.application.MangaAgentService;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

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

    @PostMapping("/ag-ui/run")
    public SseEmitter runAgUi(@PathVariable Long chapterId,
                              @RequestBody MangaAgentDtos.RunRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.runAgUiStream(chapterId, body.message(), body.requestId(), user);
    }

    @GetMapping("/runs/open")
    public MangaAgentDtos.OpenRunResponse openRun(@PathVariable Long chapterId) {
        User user = currentUserService.requireCurrentUser();
        return new MangaAgentDtos.OpenRunResponse(
                mangaAgentService.latestOpenRun(chapterId, user)
                        .map(MangaAgentDtos.RunStateResponse::from)
                        .orElse(null)
        );
    }

    @GetMapping("/runs/{requestId}")
    public MangaAgentDtos.RunStateResponse runState(@PathVariable Long chapterId,
                                                    @PathVariable UUID requestId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.RunStateResponse.from(mangaAgentService.getRun(chapterId, requestId, user));
    }

    @PostMapping("/runs/{requestId}/resume")
    public MangaAgentDtos.RunResponse resume(@PathVariable Long chapterId,
                                             @PathVariable UUID requestId,
                                             @RequestBody MangaAgentDtos.ResumeRequest body) {
        User user = currentUserService.requireCurrentUser();
        MangaAgentService.RunResult result = mangaAgentService.resume(chapterId, requestId, body.answer(), user);
        return new MangaAgentDtos.RunResponse(result.reply(), result.requestId());
    }

    @PostMapping("/runs/{requestId}/resume-stream")
    public SseEmitter resumeStream(@PathVariable Long chapterId,
                                   @PathVariable UUID requestId,
                                   @RequestBody MangaAgentDtos.ResumeRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.resumeStream(chapterId, requestId, body.answer(), user);
    }
}
