package com.artverse.application;

import java.util.List;
import java.util.Map;

public record AgentUserInputRequest(
        String question,
        List<Option> options,
        boolean allowFreeText,
        String reason,
        String purpose
) {
    public AgentUserInputRequest(String question, List<Option> options, boolean allowFreeText, String reason) {
        this(question, options, allowFreeText, reason, "BUSINESS_CONFIRMATION");
    }

    public AgentUserInputRequest {
        options = options == null ? List.of() : List.copyOf(options);
        purpose = purpose == null || purpose.isBlank() ? "BUSINESS_CONFIRMATION" : purpose;
    }

    public Map<String, Object> toEventData() {
        return Map.of(
                "question", question == null ? "" : question,
                "options", options,
                "allowFreeText", allowFreeText,
                "reason", reason == null ? "" : reason,
                "purpose", purpose
        );
    }

    public record Option(String id, String label, String description, boolean recommended) {
    }
}
