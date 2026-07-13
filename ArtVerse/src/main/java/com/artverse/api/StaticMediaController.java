package com.artverse.api;

import com.artverse.config.ArtVerseProperties;
import com.artverse.media.MediaStorageService;
import com.artverse.storage.ObjectStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@RestController
@RequestMapping("/static/manga")
@RequiredArgsConstructor
public class StaticMediaController {

    private static final String IMMUTABLE_BROWSER_CACHE = "private, max-age=31536000, immutable";

    private final MediaStorageService mediaStorageService;
    private final ObjectStorageService objectStorageService;
    private final ArtVerseProperties properties;

    @GetMapping("/**")
    public void serveImage(HttpServletRequest request, HttpServletResponse response) {
        String path = request.getRequestURI().substring("/static/manga/".length());

        // Strip _thumb/ prefix — the frontend uses /static/manga/_thumb/... URLs
        // but MinIO object keys do not include the _thumb/ segment.
        if (path.startsWith("_thumb/")) {
            path = path.substring("_thumb/".length());
        }

        mediaStorageService.validateImagePath(path);

        Path localPath = mediaStorageService.resolveRelativePath(path);
        if (localPath != null && Files.exists(localPath)) {
            serveLocalFile(localPath, response);
            return;
        }

        serveMinioObject(path, response);
    }

    private void serveMinioObject(String objectKey, HttpServletResponse response) {
        try (InputStream in = objectStorageService.get(properties.getMinio().getBucket(), objectKey);
             OutputStream out = response.getOutputStream()) {
            response.setContentType(contentTypeFor(objectKey));
            applyImmutableCache(response);
            in.transferTo(out);
        } catch (Exception e) {
            log.warn("Failed to serve MinIO object {}: {}", objectKey, e.getMessage());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    private String contentTypeFor(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG_VALUE;
        if (lower.endsWith(".webp")) return "image/webp";
        return MediaType.IMAGE_PNG_VALUE;
    }

    private void serveLocalFile(Path path, HttpServletResponse response) {
        try {
            String contentType = Files.probeContentType(path);
            if (contentType == null) contentType = "image/png";
            response.setContentType(contentType);
            response.setContentLengthLong(Files.size(path));
            applyImmutableCache(response);
            try (InputStream in = Files.newInputStream(path);
                 OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
        } catch (Exception e) {
            log.warn("Failed to serve file {}: {}", path, e.getMessage());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void applyImmutableCache(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, IMMUTABLE_BROWSER_CACHE);
    }
}
