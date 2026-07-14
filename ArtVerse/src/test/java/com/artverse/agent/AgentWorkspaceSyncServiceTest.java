package com.artverse.agent;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkspaceSyncServiceTest {

    @Test
    void syncMangaDirectorKnowledgeAllowsWorkspaceWrites() throws Exception {
        Transactional transactional = AgentWorkspaceSyncService.class
                .getMethod("syncMangaDirectorKnowledge", Long.class, String.class, Object.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isFalse();
    }
}
