package com.artverse.api;

import com.artverse.api.dto.ChapterDto;
import com.artverse.application.ChapterService;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.persistence.ChapterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChapterController {

    private final ChapterService chapterService;
    private final ChapterRepository chapterRepository;

    @GetMapping("/stories/{storyId}/chapters")
    public List<ChapterDto> listByStory(@PathVariable Long storyId) {
        return chapterService.listByStory(storyId).stream()
                .map(ChapterDto::from)
                .toList();
    }

    @PostMapping("/stories/{storyId}/chapters")
    public ChapterDto createNext(@PathVariable Long storyId) {
        return ChapterDto.from(chapterService.createNext(storyId));
    }

    @GetMapping("/chapters/{chapterId}")
    public ChapterDto get(@PathVariable Long chapterId) {
        return ChapterDto.from(chapterService.getRequired(chapterId));
    }

    @DeleteMapping("/chapters/{chapterId}")
    public ResponseEntity<Void> delete(@PathVariable Long chapterId) {
        chapterService.delete(chapterId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/chapters/{chapterId}/color-mode")
    public Map<String, String> getColorMode(@PathVariable Long chapterId) {
        Chapter chapter = chapterService.getRequired(chapterId);
        return Map.of("color_mode", chapter.getColorMode().name().toLowerCase());
    }

    @PutMapping("/chapters/{chapterId}/color-mode")
    public ChapterDto updateColorMode(@PathVariable Long chapterId, @RequestBody Map<String, String> body) {
        return ChapterDto.from(chapterService.updateColorMode(chapterId, ColorMode.valueOf(body.get("color_mode").toUpperCase())));
    }

    @GetMapping("/chapters/{chapterId}/image-count")
    public Map<String, Object> getImageCount(@PathVariable Long chapterId) {
        Chapter chapter = chapterService.getRequired(chapterId);
        return Map.of("image_count", chapter.getImageCount());
    }

    @PutMapping("/chapters/{chapterId}/image-count")
    public ChapterDto updateImageCount(@PathVariable Long chapterId, @RequestBody Map<String, Object> body) {
        Number val = (Number) body.get("image_count");
        return ChapterDto.from(chapterService.updateImageCount(chapterId, val.intValue()));
    }
}