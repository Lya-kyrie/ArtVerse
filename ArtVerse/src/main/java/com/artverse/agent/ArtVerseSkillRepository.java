package com.artverse.agent;

import com.artverse.application.ArtVerseSkillRegistry;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only AgentScope repository backed only by published platform Skills. */
@Component
public class ArtVerseSkillRepository implements AgentSkillRepository {

    /**
     * Repository source identifier used as a filesystem namespace by the AgentScope {@code MarketplaceStager}.
     * Must be a valid filesystem path component on all target platforms (no {@code : * ? " < > |}).
     */
    private static final String SOURCE = "artverse-platform-agent-skills";
    private final ArtVerseSkillRegistry registry;

    public ArtVerseSkillRepository(ArtVerseSkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AgentSkill getSkill(String name) {
        return registry.publishedSkills().stream()
                .filter(manifest -> manifest.skillKey().equals(name))
                .findFirst()
                .map(this::toAgentSkill)
                .orElse(null);
    }

    @Override
    public List<String> getAllSkillNames() {
        return registry.publishedSkills().stream()
                .map(ArtVerseSkillRegistry.SkillManifest::skillKey)
                .toList();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return registry.publishedSkills().stream().map(this::toAgentSkill).toList();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        return false;
    }

    @Override
    public boolean delete(String name) {
        return false;
    }

    @Override
    public boolean skillExists(String name) {
        return getSkill(name) != null;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("artverse-database", SOURCE, false);
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public void setWriteable(boolean writeable) {
        if (writeable) {
            throw new UnsupportedOperationException("ArtVerse platform Skill repository is read-only");
        }
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    private AgentSkill toAgentSkill(ArtVerseSkillRegistry.SkillManifest manifest) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", manifest.skillKey());
        metadata.put("description", "ArtVerse controlled runtime Skill " + manifest.semanticVersion());
        metadata.put("version", manifest.semanticVersion());
        metadata.put("checksum", manifest.checksum());
        metadata.put("routes", manifest.supportedRoutes());
        metadata.put("capabilities", manifest.capabilities());
        metadata.put("allowed_tool_groups", manifest.allowedToolGroups());
        metadata.put("budget", manifest.budgetPolicy());
        return new AgentSkill(metadata, manifest.promptTemplate(), Map.of(), SOURCE);
    }
}
