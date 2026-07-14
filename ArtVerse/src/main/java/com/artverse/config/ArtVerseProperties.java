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
    private MangaGeneration mangaGeneration = new MangaGeneration();
    private DeepSeek deepseek = new DeepSeek();
    private Image image = new Image();
    private Minio minio = new Minio();
    private Coze coze = new Coze();
    private Flyway flyway = new Flyway();
    private RateLimit rateLimit = new RateLimit();
    private SingleFlight singleFlight = new SingleFlight();
    private Idempotency idempotency = new Idempotency();
    private Agent agent = new Agent();
    private Secrets secrets = new Secrets();

    @Data
    public static class Storage {
        private String root = "./manga_outputs";
    }

    @Data
    public static class Secrets {
        private String encryptionKey = "ArtVerse!ApiKey1";
        private int activeKeyVersion = 2;
    }

    @Data
    public static class Upload {
        private long maxImageBytes = 10485760;//10MB
    }

    @Data
    public static class ImportConfig {
        private long maxZipBytes = 524288000;//500MB
        private int maxNovelChars = 50000;//50K
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
    public static class MangaGeneration {
        private int maxConcurrentJobs = 4;
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

    @Data
    public static class Coze {
        private String baseUrl = "https://api.coze.cn";
        private String apiKey = "";
        private String workflowId = "7645642109203103763";
    }

    @Data
    public static class Flyway {
        private boolean autoRepairChecksumMismatch = true;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int defaultWindowSeconds = 60;
        private int defaultMaxRequests = 30;
    }

    @Data
    public static class SingleFlight {
        private boolean enabled = true;
        private int defaultTtlSeconds = 30;
    }

    @Data
    public static class Idempotency {
        private boolean enabled = true;
        private int successTtlSeconds = 180;
        private int failureTtlSeconds = 20;
        private int processingTtlSeconds = 600;
        private int followerWaitSeconds = 300;
        private int maxFollowers = 5;
    }

    @Data
    public static class Agent {
        private int firstEventTimeoutSeconds = 90;
        private int modelIdleTimeoutSeconds = 180;
        private int toolIdleTimeoutSeconds = 600;
        private long runWatchdogIntervalMs = 30_000;
        private int maxConcurrentRuns = 32;
        private int maxConcurrentRunsPerUser = 2;
        private int budgetTtlSeconds = 86_400;
        private int routerMaxModelCalls = 2;
        private int conversationMaxModelCalls = 4;
        private int conversationMaxToolCalls = 4;
        private int storyboardMaxModelCalls = 8;
        private int storyboardMaxToolCalls = 6;
        private int reviewMaxModelCalls = 13;
        private int reviewMaxToolCalls = 4;
        private int directorMaxModelCalls = 12;
        private int maxInputTokens = 60_000;
        private int maxOutputTokens = 20_000;
        private int maxOutputBytes = 160_000;
        private int maxSubagents = 4;
        private boolean ragInjectionEnabled = true;
        private boolean skillRegistryEnabled = true;
        private boolean storyboardTwoPhaseEnabled = true;
        private boolean multiInstanceEventBusEnabled = true;
        private boolean outboxWorkerEnabled = true;
        private int outboxBatchSize = 8;
        private int outboxLeaseSeconds = 90;
        private int outboxMaxAttempts = 5;
        private boolean providerAllowHttp = false;
        private List<String> providerAllowedPrivateCidrs = List.of();

        // Automatic multi-agent routing
        private boolean autoRoutingEnabled = true;
        private boolean routingShadowMode = false;
        private double routingDirectThreshold = 0.80;
        private double routingReadOnlyThreshold = 0.55;
        private int agentCacheMaxSize = 256;
        private int agentCacheExpireAfterMinutes = 30;

        // Retry
        private int maxRetries = 2;
        private long retryMinBackoffMs = 1_000;
        private long retryMaxBackoffMs = 10_000;
        private double retryMultiplier = 2.0;

        // Circuit breaker
        private int circuitBreakerFailureThreshold = 5;
        private int circuitBreakerWaitSeconds = 30;
        private int circuitBreakerSlidingWindowSize = 20;
        private int circuitBreakerSlowCallThresholdMs = 120_000;

        // Default LLM provider — used as the system fallback when no user config is present.
        // Operator can set provider to "openai", "openroute", etc. to switch the system default.
        private DefaultLlm defaultLlm = new DefaultLlm();
    }

    @Data
    public static class DefaultLlm {
        private String provider = "deepseek";
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-v4-flash";
        private String apiKey = "";
    }
}
