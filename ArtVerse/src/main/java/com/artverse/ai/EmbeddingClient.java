package com.artverse.ai;

import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import com.artverse.security.ProviderEndpointPolicy;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI-compatible /embeddings client. It never logs request text, secrets, or vectors. */
@Component
@RequiredArgsConstructor
public class EmbeddingClient {
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final ProviderEndpointPolicy endpointPolicy;

    public float[] embed(String baseUrl, String apiKey, String model, String headersJson, String input) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
            throw new BusinessException(400, "Embedding Base URL, API key, and model are required.");
        }
        endpointPolicy.requireSafeBaseUrl(baseUrl);
        try {
            WebClient.RequestBodySpec request = webClientBuilder.baseUrl(stripTrailingSlash(baseUrl)).build()
                    .post().uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
            parseHeaders(headersJson).forEach(request::header);
            String payload = objectMapper.writeValueAsString(Map.of("model", model, "input", input));
            String response = request.bodyValue(payload).retrieve().bodyToMono(String.class).block();
            JsonNode values = objectMapper.readTree(response == null ? "" : response).path("data").path(0).path("embedding");
            if (!values.isArray() || values.isEmpty()) {
                throw new BusinessException(502, "Embedding API returned no vector.");
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                if (!values.get(i).isNumber()) throw new BusinessException(502, "Embedding API returned a non-numeric vector.");
                vector[i] = values.get(i).floatValue();
            }
            return vector;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "Embedding API request failed: " + safeMessage(e));
        }
    }

    public List<String> discoverModels(String baseUrl, String apiKey, String headersJson) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(400, "Embedding Base URL and API key are required to fetch models.");
        }
        endpointPolicy.requireSafeBaseUrl(baseUrl);
        try {
            WebClient.RequestHeadersSpec<?> request = webClientBuilder.baseUrl(stripTrailingSlash(baseUrl)).build()
                    .get().uri("/models")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            parseHeaders(headersJson).forEach(request::header);
            String response = request.retrieve().bodyToMono(String.class).block();
            JsonNode data = objectMapper.readTree(response == null ? "" : response).path("data");
            if (!data.isArray()) {
                throw new BusinessException(502, "Provider returned an invalid OpenAI-compatible model list.");
            }
            List<String> models = new ArrayList<>();
            data.forEach(item -> {
                String id = item.path("id").asText("").trim();
                if (!id.isBlank() && !models.contains(id)) models.add(id);
            });
            return models;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new BusinessException(401, "Embedding API key is invalid or expired.");
            }
            throw new BusinessException(e.getStatusCode().value(), "Fetching embedding models failed (" + e.getStatusCode() + ").");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "Fetching embedding models failed: " + safeMessage(e));
        }
    }

    private Map<String, String> parseHeaders(String headersJson) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (headersJson == null || headersJson.isBlank() || "{}".equals(headersJson.trim())) return headers;
        try {
            JsonNode node = objectMapper.readTree(headersJson);
            if (!node.isObject()) throw new BusinessException(400, "Custom embedding headers must be a JSON object.");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!field.getKey().equalsIgnoreCase(HttpHeaders.AUTHORIZATION) && field.getValue().isValueNode()) {
                    headers.put(field.getKey(), field.getValue().asText());
                }
            }
            endpointPolicy.validateCustomHeaders(headers);
            return headers;
        } catch (BusinessException e) { throw e;
        } catch (Exception e) { throw new BusinessException(400, "Custom embedding headers must be valid JSON."); }
    }

    private static String stripTrailingSlash(String value) { return value.trim().replaceFirst("/+$", ""); }
    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "connection error" : message.replaceAll("(?i)Bearer\\s+[^\\s]+", "Bearer [redacted]");
    }
}
