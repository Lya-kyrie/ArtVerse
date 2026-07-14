package com.artverse.config;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Configuration
public class AgentScopeConfig {

    private static final Path DEFAULT_WORKSPACE = Paths.get(System.getProperty("user.dir", "."), ".agentscope/workspace");

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "(not set)";
        return key.substring(0, 7) + "****" + key.substring(key.length() - 4);
    }

    @Bean
    public Path agentScopeWorkspace() {
        // Managed workspace contents are stored by AgentScope RemoteFilesystem.
        // This path is logical only and is never written by application code.
        return DEFAULT_WORKSPACE;
    }

    @Bean
    public CompactionConfig defaultCompactionConfig() {
        return CompactionConfig.builder()
                .triggerMessages(30)
                .keepMessages(10)
                .flushBeforeCompact(true)
                .build();
    }

    @Bean
    public Model deepSeekModel(ArtVerseProperties properties) {
        String apiKey = properties.getDeepseek().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("DeepSeek API key not configured at system level; per-user keys will be used.");
        }
        log.info("DeepSeek model configured with key: {}", maskKey(apiKey));
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(properties.getDeepseek().getModel())
                .baseUrl(properties.getDeepseek().getBaseUrl())
                .stream(true)
                .build();
    }
}
