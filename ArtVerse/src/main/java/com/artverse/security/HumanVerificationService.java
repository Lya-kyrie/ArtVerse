package com.artverse.security;

import java.util.List;

public interface HumanVerificationService {

    VerificationResult verify(String action, String token, String remoteIp);

    boolean isEnabled();

    String provider();

    String siteKey();

    record VerificationResult(Status status, List<String> errorCodes, String action, String hostname) {
        public static VerificationResult success(String action, String hostname) {
            return new VerificationResult(Status.SUCCESS, List.of(), action, hostname);
        }

        public static VerificationResult failure(List<String> errorCodes) {
            return new VerificationResult(Status.FAILURE, errorCodes, null, null);
        }

        public static VerificationResult unavailable(String errorCode) {
            return new VerificationResult(Status.UNAVAILABLE, List.of(errorCode), null, null);
        }
    }

    enum Status {
        SUCCESS,
        FAILURE,
        UNAVAILABLE
    }
}
