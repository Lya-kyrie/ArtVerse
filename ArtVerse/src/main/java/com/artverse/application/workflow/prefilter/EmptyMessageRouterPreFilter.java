package com.artverse.application.workflow.prefilter;

import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.RoutingDecision;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class EmptyMessageRouterPreFilter implements RouterPreFilter {

    @Override
    public Optional<RoutingDecision> filter(String message) {
        if (message == null || message.isBlank()) {
            return Optional.of(RoutingDecision.fixed(MangaWorkflowRoute.CONVERSATION, "empty_message"));
        }
        return Optional.empty();
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
