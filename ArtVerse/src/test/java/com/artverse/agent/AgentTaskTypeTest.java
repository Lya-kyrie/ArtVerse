package com.artverse.agent;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTaskTypeTest {

    @Test
    void reviewTaskDeclaresItsReviewSubagents() {
        var declarations = AgentTaskType.MANGA_REVIEW.subagentDeclarations();

        assertThat(declarations).hasSize(4);
        assertThat(declarations.stream().map(declaration -> declaration.getName()).collect(Collectors.toSet()))
                .isEqualTo(Set.of("camera-reviewer", "character-reviewer", "pacing-reviewer", "continuity-reviewer"));
        assertThat(declarations).allSatisfy(declaration -> {
            assertThat(declaration.getTools()).containsExactly("get_chapter_context");
            assertThat(declaration.getMaxIters()).isEqualTo(3);
            assertThat(declaration.getInlineAgentsBody())
                    .contains("审查")
                    .doesNotContain("Ã", "å®");
        });
    }

    @Test
    void otherTaskTypesDoNotDeclareSubagents() {
        assertThat(AgentTaskType.values())
                .filteredOn(taskType -> taskType != AgentTaskType.MANGA_REVIEW)
                .allSatisfy(taskType -> assertThat(taskType.subagentDeclarations()).isEmpty());
    }

    @Test
    void subagentDeclarationsCannotBeMutatedByCallers() {
        assertThatThrownBy(() -> AgentTaskType.MANGA_REVIEW.subagentDeclarations().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
