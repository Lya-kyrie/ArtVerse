package com.artverse.api;

import com.artverse.application.WorksService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/works")
@RequiredArgsConstructor
public class WorksController {

    private final WorksService worksService;

    @GetMapping
    public List<Map<String, Object>> listMyWorks() {
        return worksService.listMyWorks();
    }
}