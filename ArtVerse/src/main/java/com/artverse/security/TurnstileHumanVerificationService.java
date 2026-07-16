package com.artverse.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.artverse.config.ArtVerseProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class TurnstileHumanVerificationService implements HumanVerificationService {

    private static final String SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final ArtVerseProperties properties;
    private final WebClient webClient;

    public TurnstileHumanVerificationService(ArtVerseProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public VerificationResult verify(String action, String token, String remoteIp) {
        if (!isEnabled()) {
            return VerificationResult.unavailable("challenge-disabled");
        }
        if (token == null || token.isBlank()) {
            return VerificationResult.failure(List.of("missing-input-response"));
        }
        if (token.length() > 2048) {
            return VerificationResult.failure(List.of("invalid-input-response"));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("secret", properties.getAuth().getChallenge().getSecretKey());
        payload.put("response", token);
        payload.put("idempotency_key", UUID.randomUUID().toString());
        if (remoteIp != null && !remoteIp.isBlank()) {
            payload.put("remoteip", remoteIp);
        }

        try {
            SiteVerifyResponse result = webClient.post()
                    .uri(SITEVERIFY_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .exchangeToMono(response -> mapResponse(response.statusCode(), response))
                    .block(Duration.ofMillis(properties.getAuth().getChallenge().getVerifyTimeoutMs()));
            if (result == null) {
                return VerificationResult.unavailable("challenge-empty-response");
            }
            if (!result.success()) {
                return VerificationResult.failure(normalizeErrorCodes(result.errorCodes()));
            }
            if (!action.equals(result.action())) {
                return VerificationResult.failure(List.of("action-mismatch"));
            }
            if (!isAllowedHostname(result.hostname())) {
                return VerificationResult.failure(List.of("hostname-mismatch"));
            }
            return VerificationResult.success(result.action(), result.hostname());
        } catch (ChallengeServiceUnavailableException ex) {
            return VerificationResult.unavailable(ex.getMessage());
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode().is5xxServerError()) {
                return VerificationResult.unavailable("challenge-upstream-5xx");
            }
            return VerificationResult.failure(List.of("challenge-http-" + ex.getStatusCode().value()));
        } catch (WebClientRequestException ex) {
            return VerificationResult.unavailable("challenge-network-error");
        } catch (Exception ex) {
            return VerificationResult.unavailable("challenge-internal-error");
        }
    }

    @Override
    public boolean isEnabled() {
        return properties.getAuth().getChallenge().getMode() != ArtVerseProperties.ChallengeMode.DISABLED
                && !siteKey().isBlank()
                && !properties.getAuth().getChallenge().getSecretKey().isBlank();
    }

    @Override
    public String provider() {
        return properties.getAuth().getChallenge().getProvider();
    }

    @Override
    public String siteKey() {
        return properties.getAuth().getChallenge().getSiteKey() == null
                ? ""
                : properties.getAuth().getChallenge().getSiteKey().trim();
    }

    private Mono<SiteVerifyResponse> mapResponse(HttpStatusCode statusCode, org.springframework.web.reactive.function.client.ClientResponse response) {
        if (statusCode.is5xxServerError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> Mono.error(new ChallengeServiceUnavailableException("challenge-upstream-5xx")));
        }
        if (!statusCode.is2xxSuccessful() && !statusCode.is4xxClientError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> Mono.error(new ChallengeServiceUnavailableException("challenge-unexpected-status")));
        }
        return response.bodyToMono(SiteVerifyResponse.class);
    }

    private boolean isAllowedHostname(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return false;
        }
        return properties.getAuth().getChallenge().getAllowedHostnames().stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .anyMatch(value -> value.equals(hostname.trim().toLowerCase(Locale.ROOT)));
    }

    private List<String> normalizeErrorCodes(List<String> errorCodes) {
        if (errorCodes == null || errorCodes.isEmpty()) {
            return List.of("challenge-validation-failed");
        }
        List<String> normalized = new ArrayList<>();
        errorCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .forEach(normalized::add);
        return normalized.isEmpty() ? List.of("challenge-validation-failed") : List.copyOf(normalized);
    }

    private record SiteVerifyResponse(
            boolean success,
            String action,
            String hostname,
            @JsonProperty("error-codes")
            List<String> errorCodes
    ) {
    }

    private static final class ChallengeServiceUnavailableException extends RuntimeException {
        private ChallengeServiceUnavailableException(String message) {
            super(message);
        }
    }
}
