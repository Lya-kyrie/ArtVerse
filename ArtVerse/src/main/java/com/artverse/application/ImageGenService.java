package com.artverse.application;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.ai.GeneratedImage;
import com.artverse.ai.Image2Client;
import com.artverse.ai.ImageGenerationRequest;
import com.artverse.application.UserProviderConfig;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.ImageGenRecord;
import com.artverse.domain.User;
import com.artverse.media.MediaStorageService;
import com.artverse.persistence.ImageGenRecordRepository;
import com.artverse.persistence.UserRepository;
import com.artverse.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenService {

    private final ImageGenRecordRepository recordRepository;
    private final UserRepository userRepository;
    private final Image2Client image2Client;
    private final MediaStorageService mediaStorageService;
    private final ObjectStorageService objectStorageService;
    private final ArtVerseProperties properties;

    private static final int MAX_REF_IMAGES = 3;

    @Transactional
    public Map<String, Object> generate(String prompt, List<String> referenceImagesBase64, UserProviderConfig imageConfig) {
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(401, "User not found"));

        if (referenceImagesBase64 != null && referenceImagesBase64.size() > MAX_REF_IMAGES) {
            throw new BusinessException(400, "Maximum " + MAX_REF_IMAGES + " reference images allowed");
        }

        List<Path> refFiles = new ArrayList<>();
        try {
            if (referenceImagesBase64 != null) {
                for (String b64 : referenceImagesBase64) {
                    if (b64 == null || b64.isBlank()) continue;
                    try {
                        byte[] data = mediaStorageService.decodeBase64Image(b64);
                        mediaStorageService.validateImageBytes(data, properties.getUpload().getMaxImageBytes());
                        Path tmp = Files.createTempFile("artverse-ref-", ".png");
                        mediaStorageService.savePng(data, tmp);
                        refFiles.add(tmp);
                    } catch (java.io.IOException e) {
                        throw new RuntimeException("Failed to process reference image", e);
                    }
                }
            }

            ImageGenerationRequest request = new ImageGenerationRequest(
                    prompt,
                    properties.getImage().getModel(),
                    properties.getImage().getSize(),
                    refFiles.isEmpty() ? null : refFiles,
                    null
            );

            GeneratedImage generated = image2Client.generate(request, imageConfig).block();
            if (generated == null) {
                throw new BusinessException(502, "Image generation returned no result");
            }

            String prefix = "image_gen/" + userId + "/";
            String filename = mediaStorageService.generateUniqueFilename("gen", ".png");
            String objectKey = prefix + filename;

            objectStorageService.putPng(objectKey, generated.localFile(), "image/png");
            try { Files.deleteIfExists(generated.localFile()); } catch (Exception ignored) {}

            ImageGenRecord record = new ImageGenRecord();
            record.setUser(user);
            record.setPrompt(prompt);
            record.setImagePath(objectKey);
            record.setModel(properties.getImage().getModel());
            record.setSize(properties.getImage().getSize());
            record = recordRepository.save(record);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", record.getId());
            result.put("prompt", record.getPrompt());
            result.put("image_url", record.getImagePath());
            result.put("model", record.getModel());
            result.put("size", record.getSize());
            result.put("created_at", record.getCreatedAt().toString());
            return result;

        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(500, "生成失败: " + e.getMessage());
        } finally {
            for (Path f : refFiles) {
                try { Files.deleteIfExists(f); } catch (Exception ignored) {}
            }
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listHistory(int page, int size) {
        Long userId = StpUtil.getLoginIdAsLong();
        Page<ImageGenRecord> result = recordRepository.findByUserId(userId, PageRequest.of(page, size));

        List<Map<String, Object>> content = result.getContent().stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("prompt", r.getPrompt());
                    m.put("image_url", r.getImagePath());
                    m.put("model", r.getModel());
                    m.put("size", r.getSize());
                    m.put("created_at", r.getCreatedAt().toString());
                    return m;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        response.put("total_pages", result.getTotalPages());
        response.put("total_elements", result.getTotalElements());
        return response;
    }

    @Transactional
    public void delete(Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        ImageGenRecord record = recordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Record not found"));
        if (!record.getUser().getId().equals(userId)) {
            throw new BusinessException(403, "Access denied");
        }
        record.setIsDeleted(true);
        recordRepository.save(record);
    }
}
