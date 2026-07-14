package com.artverse.application.workflow;

import com.artverse.application.AgentUserInputRequest;
import com.artverse.application.AgentUserInputRequiredException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class MangaWorkflowContextPolicy {

    private static final String FIELD_CHAPTER_SOURCE = "chapter_source_excerpt";
    private static final String FIELD_STORYBOARD = "storyboard_excerpt";

    public List<String> requiredFields(RoutingDecision decision) {
        LinkedHashSet<String> required = new LinkedHashSet<>();
        Set<MangaWorkflowCapability> capabilities = decision == null ? Set.of() : decision.requiredCapabilities();
        if (capabilities.contains(MangaWorkflowCapability.STORYBOARD_WRITE)) {
            required.add(FIELD_CHAPTER_SOURCE);
        }
        if (capabilities.contains(MangaWorkflowCapability.STORYBOARD_REVIEW)) {
            required.add(FIELD_STORYBOARD);
        }
        if (required.isEmpty() && decision != null) {
            if (decision.route() == MangaWorkflowRoute.STORYBOARD) {
                required.add(FIELD_CHAPTER_SOURCE);
            } else if (decision.route() == MangaWorkflowRoute.REVIEW) {
                required.add(FIELD_STORYBOARD);
            }
        }
        return List.copyOf(required);
    }

    public List<String> missingRequiredFields(MangaWorkflowContextSnapshot snapshot) {
        if (snapshot == null || snapshot.requiredFields().isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String field : snapshot.requiredFields()) {
            if (isMissing(snapshot, field)) {
                missing.add(field);
            }
        }
        return List.copyOf(missing);
    }

    public boolean blocksWrite(RoutingDecision decision) {
        if (decision == null) {
            return false;
        }
        return decision.mutating()
                || decision.requiredCapabilities().stream().anyMatch(MangaWorkflowCapability::isMutating);
    }

    public AgentUserInputRequiredException missingContextHitl(MangaWorkflowContextSnapshot snapshot,
                                                              RoutingDecision decision) {
        String labels = describeFields(missingRequiredFields(snapshot));
        return new AgentUserInputRequiredException(new AgentUserInputRequest(
                "当前章节缺少执行写入所需的上下文：" + labels + "。为避免误写，我先暂停写入。你可以改为只读建议，或先补齐章节内容后再试。",
                List.of(
                        new AgentUserInputRequest.Option("cancel", "取消本次写入", "先不继续本次分镜写入", true),
                        new AgentUserInputRequest.Option("advice", "改为只读建议", "只给建议，不执行任何保存或改写", false)
                ),
                false,
                "context_missing:" + String.join(",", missingRequiredFields(snapshot)),
                "CONTEXT_MISSING"
        ));
    }

    public String readOnlyExplanation(MangaWorkflowContextSnapshot snapshot, RoutingDecision decision) {
        String labels = describeFields(missingRequiredFields(snapshot));
        if (decision != null && requiredFields(decision).contains(FIELD_STORYBOARD)) {
            return "当前章节还缺少可供审阅的分镜上下文（" + labels + "），我先不直接执行评审。请先生成或补充分镜内容后再让我继续。";
        }
        return "当前章节缺少必要上下文（" + labels + "），我先保持只读说明，不直接执行写入或依赖缺失数据的操作。";
    }

    private boolean isMissing(MangaWorkflowContextSnapshot snapshot, String field) {
        return switch (field) {
            case FIELD_CHAPTER_SOURCE -> isBlank(snapshot.sourceExcerpt());
            case FIELD_STORYBOARD -> isBlank(snapshot.storyboardExcerpt());
            default -> true;
        };
    }

    private String describeFields(List<String> fields) {
        return fields.stream().map(field -> switch (field) {
            case FIELD_CHAPTER_SOURCE -> "章节正文";
            case FIELD_STORYBOARD -> "分镜内容";
            default -> field;
        }).distinct().reduce((left, right) -> left + "、" + right).orElse("必要上下文");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
