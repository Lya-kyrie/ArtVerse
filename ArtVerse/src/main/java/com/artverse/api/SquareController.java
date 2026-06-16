package com.artverse.api;

import com.artverse.application.SquareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/square")
@RequiredArgsConstructor
public class SquareController {

    private final SquareService squareService;

    @GetMapping("/stories")
    public Map<String, Object> listStories(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String search) {
        return squareService.listPublishedStories(page, size, search);
    }

    @GetMapping("/stories/{id}")
    public Map<String, Object> getStory(@PathVariable Long id) {
        return squareService.getPublishedStoryDetail(id);
    }
}
