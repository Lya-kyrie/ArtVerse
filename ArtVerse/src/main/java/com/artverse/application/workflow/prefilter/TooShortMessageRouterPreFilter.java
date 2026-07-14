package com.artverse.application.workflow.prefilter;

import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.RoutingDecision;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TooShortMessageRouterPreFilter implements RouterPreFilter {

    @Override
    public Optional<RoutingDecision> filter(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        String stripped = message.strip();
        if (stripped.codePointCount(0, stripped.length()) < 2) {
            return Optional.of(RoutingDecision.fixed(MangaWorkflowRoute.CONVERSATION, "too_short"));
        }
        return Optional.empty();
    }

    @Override
    public int getOrder() {
        return 200;
    }
}
