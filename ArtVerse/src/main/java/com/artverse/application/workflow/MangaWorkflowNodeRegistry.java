package com.artverse.application.workflow;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class MangaWorkflowNodeRegistry {

    private final Map<MangaWorkflowRoute, MangaWorkflowNodeHandler> handlers;

    public MangaWorkflowNodeRegistry(List<MangaWorkflowNodeHandler> handlers) {
        EnumMap<MangaWorkflowRoute, MangaWorkflowNodeHandler> mappedHandlers = new EnumMap<>(MangaWorkflowRoute.class);
        for (MangaWorkflowNodeHandler handler : handlers) {
            mappedHandlers.put(handler.route(), handler);
        }
        this.handlers = Map.copyOf(mappedHandlers);
    }

    public MangaWorkflowNodeHandler handlerFor(MangaWorkflowRoute route) {
        MangaWorkflowNodeHandler handler = handlers.get(route);
        if (handler != null) {
            return handler;
        }
        throw new IllegalStateException("No Manga workflow node handler for route: " + route);
    }
}
