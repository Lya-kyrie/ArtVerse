package com.artverse.security;

public final class AuthErrorCodes {

    public static final String AUTH_EXPIRED = "AUTH_EXPIRED";
    public static final String AUTH_RATE_LIMITED = "AUTH_RATE_LIMITED";
    public static final String CHALLENGE_REQUIRED = "CHALLENGE_REQUIRED";
    public static final String CHALLENGE_FAILED = "CHALLENGE_FAILED";
    public static final String CHALLENGE_UNAVAILABLE = "CHALLENGE_UNAVAILABLE";
    public static final String CSRF_REJECTED = "CSRF_REJECTED";

    private AuthErrorCodes() {
    }
}
