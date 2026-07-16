package com.artverse.application;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class NovelBusinessSkillRouter {

    private static final List<String> REVIEW_KEYWORDS = List.of(
            "审", "审查", "点评", "评价", "评估", "检查", "review", "feedback", "问题在哪", "哪里有问题");
    private static final List<String> PROSE_POLISH_KEYWORDS = List.of(
            "润色", "优化文笔", "改文风", "改写得更顺", "重写这段", "polish", "rewrite", "improve prose", "优化这段");
    private static final List<String> CHAPTER_WRITING_KEYWORDS = List.of(
            "写成正文", "写这一章", "写这段", "扩写", "续写", "生成正文", "改成小说", "draft", "write the chapter",
            "write prose", "scene draft", "chapter draft");
    private static final List<String> IDEATION_KEYWORDS = List.of(
            "设定", "人物", "剧情", "大纲", "世界观", "脑洞", "灵感", "思路", "怎么写", "plot", "character",
            "worldbuilding", "outline", "idea", "brainstorm");

    public Decision classify(String latestUserMessage) {
        String normalized = normalize(latestUserMessage);
        if (normalized.isBlank()) {
            return new Decision(NovelBusinessSkillMode.NONE, 0.0, "empty_message");
        }
        if (containsAny(normalized, REVIEW_KEYWORDS)) {
            return new Decision(NovelBusinessSkillMode.REVIEW, 0.88, "review_keywords");
        }
        if (containsAny(normalized, PROSE_POLISH_KEYWORDS)) {
            return new Decision(NovelBusinessSkillMode.PROSE_POLISH, 0.85, "prose_polish_keywords");
        }
        if (containsAny(normalized, CHAPTER_WRITING_KEYWORDS)) {
            return new Decision(NovelBusinessSkillMode.CHAPTER_WRITING, 0.82, "chapter_writing_keywords");
        }
        if (containsAny(normalized, IDEATION_KEYWORDS)) {
            return new Decision(NovelBusinessSkillMode.IDEATION, 0.76, "ideation_keywords");
        }
        return new Decision(NovelBusinessSkillMode.NONE, 0.35, "no_match");
    }

    private boolean containsAny(String normalized, List<String> keywords) {
        return keywords.stream().anyMatch(normalized::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    public record Decision(NovelBusinessSkillMode mode, double confidence, String reason) {
    }
}
