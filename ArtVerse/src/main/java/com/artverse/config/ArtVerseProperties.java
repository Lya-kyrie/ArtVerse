package com.artverse.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "artverse")
public class ArtVerseProperties {

    private List<String> corsOrigins = List.of("http://localhost:5173", "http://127.0.0.1:5173");
    private Storage storage = new Storage();
    private Upload upload = new Upload();
    private ImportConfig importConfig = new ImportConfig();
    private Character character = new Character();
    private Ref ref = new Ref();
    private Manga manga = new Manga();
    private DeepSeek deepseek = new DeepSeek();
    private Image image = new Image();
    private Minio minio = new Minio();

    @Data
    public static class Storage {
        private String root = "./manga_outputs";
    }

    @Data
    public static class Upload {
        private long maxImageBytes = 10485760;
    }

    @Data
    public static class ImportConfig {
        private long maxZipBytes = 524288000;
        private int maxNovelChars = 50000;
    }

    @Data
    public static class Character {
        private int maxChars = 20000;
    }

    @Data
    public static class Ref {
        private int maxImagesPerLevel = 4;
    }

    @Data
    public static class Manga {
        private int defaultImageCount = 10;
        private List<Integer> allowedImageCounts = List.of(4, 6, 8, 10, 12, 15, 20);
    }

    @Data
    public static class DeepSeek {
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-v4-flash";
        private String apiKey = "";
    }

    @Data
    public static class Image {
        private String baseUrl = "https://api.duojie.games/v1";
        private String model = "gpt-image-2";
        private String size = "1024x1536";
        private String apiKey = "";
    }

    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String bucket = "artverse-manga";
        private String region = "us-east-1";
        private String accessKey = "";
        private String secretKey = "";
        private boolean secure = false;
        private String publicBaseUrl = "";
        private int presignedUrlExpireSeconds = 3600;
    }
}
