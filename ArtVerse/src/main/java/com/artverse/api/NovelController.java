package com.artverse.api;

import com.artverse.api.dto.ChapterDto;
import com.artverse.application.CurrentUserService;
import com.artverse.application.NovelService;
import com.artverse.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chapters/{chapterId}")
@RequiredArgsConstructor
public class NovelController {

    private final NovelService novelService;
    @SuppressWarnings("unused")
    private final CurrentUserService currentUserService;

    @PostMapping("/generate-novel")
    public Map<String, String> generateNovel(@PathVariable Long chapterId) {
        throw new BusinessException(410,
                "Direct AI novel generation has been retired. Create and commit a novel-content proposal instead.");
    }

    @PostMapping("/import-novel")
    public ChapterDto importNovel(@PathVariable Long chapterId, @RequestBody Map<String, String> body) {
        return ChapterDto.from(novelService.importNovel(chapterId, body.get("content")));
    }
}
