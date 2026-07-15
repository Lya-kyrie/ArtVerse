package com.artverse.api;

import com.artverse.application.SquareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import com.artverse.api.dto.SquareDtos;

@RestController
@RequestMapping("/api/square")
@RequiredArgsConstructor
public class SquareController {

    private final SquareService squareService;

    @GetMapping("/stories")
    public SquareDtos.StoryListResponse listStories(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String format) {
        return squareService.listPublishedStories(page, size, search, format);
    }

    @GetMapping("/stories/{id}")
    public SquareDtos.StoryDetail getStory(@PathVariable Long id, @RequestParam(required = false) String format) {
        return squareService.getPublishedStoryDetail(id, format);
    }
}
