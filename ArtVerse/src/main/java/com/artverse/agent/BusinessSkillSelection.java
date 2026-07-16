package com.artverse.agent;

import com.artverse.application.ArtVerseSkillRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BusinessSkillSelection(List<ArtVerseSkillRegistry.SkillManifest> manifests) {

    public BusinessSkillSelection {
        manifests = manifests == null ? List.of() : List.copyOf(manifests);
    }

    public static BusinessSkillSelection empty() {
        return new BusinessSkillSelection(List.of());
    }

    public boolean isEmpty() {
        return manifests.isEmpty();
    }

    public List<String> skillKeys() {
        return manifests.stream().map(ArtVerseSkillRegistry.SkillManifest::skillKey).toList();
    }

    public Map<String, String> skillVersions() {
        LinkedHashMap<String, String> versions = new LinkedHashMap<>();
        for (ArtVerseSkillRegistry.SkillManifest manifest : manifests) {
            versions.put(manifest.skillKey(), manifest.semanticVersion());
        }
        return Map.copyOf(versions);
    }

    public ArtVerseSkillRegistry.SkillManifest primarySkill() {
        return manifests.isEmpty() ? null : manifests.get(0);
    }

    public String primarySkillKey() {
        ArtVerseSkillRegistry.SkillManifest primary = primarySkill();
        return primary == null ? null : primary.skillKey();
    }

    public String cacheKey() {
        if (manifests.isEmpty()) {
            return "none";
        }
        return manifests.stream()
                .map(manifest -> manifest.skillKey() + "@"
                        + manifest.semanticVersion() + "#"
                        + shortChecksum(manifest.checksum()))
                .reduce((left, right) -> left + "+" + right)
                .orElse("none");
    }

    private String shortChecksum(String checksum) {
        if (checksum == null || checksum.isBlank()) {
            return "none";
        }
        return checksum.length() <= 12 ? checksum : checksum.substring(0, 12);
    }
}
