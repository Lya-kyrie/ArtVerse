package com.artverse.api;

import com.artverse.application.GuardStatsService;
import com.artverse.application.GuardEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/guard")
@RequiredArgsConstructor
public class GuardStatsController {

    private final GuardStatsService guardStatsService;
    private final GuardEventService guardEventService;

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return guardStatsService.stats();
    }

    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> events = guardEventService.recentEvents(limit);
        return Map.of("events", events);
    }
}
