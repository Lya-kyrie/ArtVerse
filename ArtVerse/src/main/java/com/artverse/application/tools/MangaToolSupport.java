package com.artverse.application.tools;

import com.artverse.agents.AgentRunContext;
import com.artverse.agents.MangaAgentRuntimeContext;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class MangaToolSupport {

    private final AgentRunToolStatus agentRunToolStatus;
    private final String legacyCozeApiKey;
    private final Long legacyChapterId;
    private final Long legacyUserId;

    public MangaAgentRuntimeContext resolveContext(RuntimeContext runtimeContext) {
        MangaAgentRuntimeContext context = runtimeContext == null ? null : runtimeContext.get(MangaAgentRuntimeContext.class);
        if (context != null) {
            return context;
        }
        if (legacyUserId == null || legacyChapterId == null) {
            throw new BusinessException(500, "Manga Agent runtime context is missing user id or chapter id");
        }
        return new MangaAgentRuntimeContext(
                legacyUserId,
                null,
                legacyChapterId,
                null,
                null,
                legacyCozeApiKey == null ? "" : legacyCozeApiKey
        );
    }

    public void requestUserInput(Long userId, Long chapterId, RuntimeContext runtimeContext,
                                 AgentUserInputRequest request) {
        AgentRunContext context = runtimeContext == null ? null : runtimeContext.get(AgentRunContext.class);
        if (context != null && context.requestId() != null) {
            agentRunToolStatus.requestUserInput(userId, chapterId, context.requestId(), request);
            return;
        }
        agentRunToolStatus.requestUserInputForActiveRun(userId, chapterId, request);
    }

    public String chapterDisplayName(Chapter chapter) {
        if (chapter.getDisplayTitle() != null && !chapter.getDisplayTitle().isBlank()) {
            return chapter.getDisplayTitle();
        }
        return "第" + chapter.getChapterNumber() + "话";
    }

    public String excerpt(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...";
    }

    public String optionalText(Object value) {
        return value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
    }

    public List<AgentUserInputRequest.Option> normalizeOptions(Object rawOptions) {
        if (!(rawOptions instanceof List<?> list)) {
            return List.of();
        }
        List<AgentUserInputRequest.Option> result = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            String id = String.valueOf((char) ('a' + Math.min(i, 25)));
            if (item instanceof Map<?, ?> map) {
                String label = optionalText(map.get("label"));
                if (label.isBlank()) {
                    label = optionalText(map.get("title"));
                }
                if (!label.isBlank()) {
                    result.add(new AgentUserInputRequest.Option(
                            optionalText(map.get("id")).isBlank() ? id : optionalText(map.get("id")),
                            label,
                            optionalText(map.get("description")),
                            Boolean.TRUE.equals(map.get("recommended"))
                    ));
                }
            } else {
                String label = optionalText(item);
                if (!label.isBlank()) {
                    result.add(new AgentUserInputRequest.Option(id, label, "", i == 0));
                }
            }
        }
        return result;
    }
}
