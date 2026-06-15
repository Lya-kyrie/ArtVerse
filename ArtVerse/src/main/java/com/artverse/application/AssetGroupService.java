package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.CharacterProfile;
import com.artverse.domain.Story;
import com.artverse.domain.StoryAssetGroup;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.CharacterProfileRepository;
import com.artverse.persistence.StoryAssetGroupRepository;
import com.artverse.persistence.StoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AssetGroupService {

    private final StoryAssetGroupRepository assetGroupRepository;
    private final StoryRepository storyRepository;
    private final ChapterRepository chapterRepository;
    private final CharacterProfileRepository characterProfileRepository;

    @Transactional(readOnly = true)
    public List<StoryAssetGroup> listByStory(Long storyId) {
        List<StoryAssetGroup> groups = assetGroupRepository.findByStoryIdOrderByIdAsc(storyId);
        groups.forEach(g -> g.getCharacters().size());
        return groups;
    }

    @Transactional(readOnly = true)
    public StoryAssetGroup getRequired(Long id) {
        return assetGroupRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Asset group not found"));
    }

    @Transactional
    public StoryAssetGroup create(Long storyId, String name, String description) {
        return create(storyId, name, description, List.of());
    }

    @Transactional
    public StoryAssetGroup create(Long storyId, String name, String description, List<Long> characterIds) {
        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new BusinessException(404, "Story not found"));
        StoryAssetGroup group = new StoryAssetGroup();
        group.setStory(story);
        group.setName(name != null ? name : "");
        group.setDescription(description != null ? description : "");
        setCharacters(group, characterIds);
        return assetGroupRepository.save(group);
    }

    @Transactional
    public StoryAssetGroup update(Long id, String name, String description, List<Long> characterIds) {
        StoryAssetGroup group = getRequired(id);
        if (name != null) group.setName(name);
        if (description != null) group.setDescription(description);
        if (characterIds != null) setCharacters(group, characterIds);
        return assetGroupRepository.save(group);
    }

    private void setCharacters(StoryAssetGroup group, List<Long> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            group.getCharacters().clear();
            return;
        }
        Set<CharacterProfile> profiles = new LinkedHashSet<>();
        for (Long cid : characterIds) {
            CharacterProfile profile = characterProfileRepository.findById(cid)
                    .orElseThrow(() -> new BusinessException(404, "Character profile not found: " + cid));
            if (!profile.getStory().getId().equals(group.getStory().getId())) {
                throw new BusinessException(400, "Character does not belong to the same story");
            }
            profiles.add(profile);
        }
        group.setCharacters(profiles);
    }

    @Transactional
    public void delete(Long id) {
        StoryAssetGroup group = getRequired(id);
        // Clear association from chapters
        for (Chapter ch : group.getStory().getChapters()) {
            if (group.equals(ch.getAssetGroup())) {
                ch.setAssetGroup(null);
            }
        }
        assetGroupRepository.delete(group);
    }

    @Transactional(readOnly = true)
    public StoryAssetGroup getChapterAssetGroup(Long chapterId) {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        return chapter.getAssetGroup();
    }

    @Transactional
    public StoryAssetGroup setChapterAssetGroup(Long chapterId, Long groupId) {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        if (groupId == null) {
            chapter.setAssetGroup(null);
            chapterRepository.save(chapter);
            return null;
        }
        StoryAssetGroup group = assetGroupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(404, "Asset group not found"));
        if (!group.getStory().getId().equals(chapter.getStory().getId())) {
            throw new BusinessException(400, "Asset group does not belong to the same story");
        }
        chapter.setAssetGroup(group);
        chapterRepository.save(chapter);
        return group;
    }
}