package com.artverse.api;

import com.artverse.application.ArtVerseSkillRegistry;
import com.artverse.application.CurrentUserService;
import com.artverse.common.BusinessException;
import com.artverse.common.aspect.RateLimit;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/agent-skills")
@RequiredArgsConstructor
public class AgentSkillController {

    private final ArtVerseSkillRegistry skillRegistry;
    private final CurrentUserService currentUserService;

    @GetMapping
    public List<ArtVerseSkillRegistry.UserSkillView> list() {
        return skillRegistry.listForUser(currentUserService.requireCurrentUser().getId());
    }

    @PutMapping("/{skillKey}")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "agent-control-write")
    public ArtVerseSkillRegistry.UserSkillView setEnabled(
            @PathVariable String skillKey,
            @RequestBody Map<String, Object> body) {
        Object value = body.get("enabled");
        if (!(value instanceof Boolean enabled)) {
            throw new BusinessException(400, "enabled must be a boolean");
        }
        return skillRegistry.setEnabled(currentUserService.requireCurrentUser().getId(), skillKey, enabled);
    }
}
