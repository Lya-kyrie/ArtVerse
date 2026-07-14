package com.artverse.application;

import com.artverse.agent.AgentTaskType;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Platform-controlled, versioned runtime Skill registry. */
@Service
@RequiredArgsConstructor
public class ArtVerseSkillRegistry {

    private static final Map<AgentTaskType, String> TASK_SKILLS = Map.of(
            AgentTaskType.MANGA_ROUTER, "manga.router",
            AgentTaskType.MANGA_CONVERSATION, "manga.conversation",
            AgentTaskType.MANGA_CREATIVE, "manga.creative",
            AgentTaskType.MANGA_STORYBOARD, "manga.storyboard",
            AgentTaskType.MANGA_REVIEW, "manga.review",
            AgentTaskType.MANGA_DIRECTOR, "manga.director"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public record SkillManifest(
            String skillKey,
            String semanticVersion,
            String checksum,
            String status,
            List<String> supportedRoutes,
            List<String> capabilities,
            String promptTemplate,
            String promptVersion,
            List<String> allowedToolGroups,
            Map<String, Object> budgetPolicy,
            String evaluatorKey,
            String hitlPolicy,
            boolean userConfigurable
    ) {
    }

    public record UserSkillView(SkillManifest manifest, boolean enabled) {
    }

    @Transactional(readOnly = true)
    public SkillManifest requireEnabled(Long userId, AgentTaskType taskType) {
        String skillKey = TASK_SKILLS.get(taskType);
        if (skillKey == null) {
            return null;
        }
        SkillManifest manifest = requirePublished(skillKey);
        if (!isEnabled(userId, manifest)) {
            throw new BusinessException(409, "Agent Skill is disabled: " + skillKey);
        }
        return manifest;
    }

    @Transactional(readOnly = true)
    public List<UserSkillView> listForUser(Long userId) {
        return publishedSkills().stream()
                .map(manifest -> new UserSkillView(manifest, isEnabled(userId, manifest)))
                .toList();
    }

    @Transactional
    public UserSkillView setEnabled(Long userId, String skillKey, boolean enabled) {
        SkillManifest manifest = requirePublished(skillKey);
        if (!manifest.userConfigurable()) {
            throw new BusinessException(400, "Core Agent Skill cannot be disabled: " + skillKey);
        }
        jdbcTemplate.update("""
                INSERT INTO user_agent_skill_settings(user_id, skill_key, enabled)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, skill_key)
                DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now()
                """, userId, skillKey, enabled);
        return new UserSkillView(manifest, enabled);
    }

    @Transactional(readOnly = true)
    public List<SkillManifest> publishedSkills() {
        return jdbcTemplate.query("""
                SELECT DISTINCT ON (skill_key)
                       skill_key, semantic_version, checksum, status,
                       supported_routes::text, capabilities::text, prompt_template, prompt_version,
                       allowed_tool_groups::text, budget_policy::text, evaluator_key,
                       hitl_policy, user_configurable
                FROM agent_skill_definitions
                WHERE status = 'PUBLISHED'
                ORDER BY skill_key, published_at DESC NULLS LAST, id DESC
                """, (rs, row) -> new SkillManifest(
                rs.getString("skill_key"), rs.getString("semantic_version"), rs.getString("checksum"),
                rs.getString("status"), readList(rs.getString("supported_routes")),
                readList(rs.getString("capabilities")), rs.getString("prompt_template"),
                rs.getString("prompt_version"), readList(rs.getString("allowed_tool_groups")),
                readMap(rs.getString("budget_policy")), rs.getString("evaluator_key"),
                rs.getString("hitl_policy"), rs.getBoolean("user_configurable")));
    }

    private SkillManifest requirePublished(String skillKey) {
        return publishedSkills().stream()
                .filter(skill -> skill.skillKey().equals(skillKey))
                .findFirst()
                .orElseThrow(() -> new BusinessException(503, "Published Agent Skill is unavailable: " + skillKey));
    }

    private boolean isEnabled(Long userId, SkillManifest manifest) {
        if (!manifest.userConfigurable()) {
            return true;
        }
        List<Boolean> settings = jdbcTemplate.query(
                "SELECT enabled FROM user_agent_skill_settings WHERE user_id = ? AND skill_key = ?",
                (rs, row) -> rs.getBoolean(1), userId, manifest.skillKey());
        return settings.isEmpty() || settings.get(0);
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception error) {
            throw new IllegalStateException("Invalid Skill list manifest", error);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("Invalid Skill budget manifest", error);
        }
    }
}
