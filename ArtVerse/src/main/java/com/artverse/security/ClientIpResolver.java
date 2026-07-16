package com.artverse.security;

import com.artverse.config.ArtVerseProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ClientIpResolver {

    private final ArtVerseProperties properties;
    private final IpCidrMatcher cidrMatcher;

    public ClientIpResolver(ArtVerseProperties properties, IpCidrMatcher cidrMatcher) {
        this.properties = properties;
        this.cidrMatcher = cidrMatcher;
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = trimToEmpty(request.getRemoteAddr());
        InetAddress remoteAddress = parseAddress(remoteAddr);
        if (remoteAddress == null || !cidrMatcher.containsAny(properties.getAuth().getProxy().getTrustedCidrs(), remoteAddress)) {
            return remoteAddr;
        }

        List<String> forwardedChain = forwardedChain(request);
        for (int index = forwardedChain.size() - 1; index >= 0; index--) {
            String candidate = forwardedChain.get(index);
            InetAddress address = parseAddress(candidate);
            if (address == null) {
                continue;
            }
            if (!cidrMatcher.containsAny(properties.getAuth().getProxy().getTrustedCidrs(), address)) {
                return candidate;
            }
        }
        return forwardedChain.isEmpty() ? remoteAddr : forwardedChain.get(0);
    }

    private List<String> forwardedChain(HttpServletRequest request) {
        List<String> chain = parseForwarded(request.getHeader("Forwarded"));
        if (!chain.isEmpty()) {
            return chain;
        }
        return parseXForwardedFor(request.getHeader("X-Forwarded-For"));
    }

    private List<String> parseForwarded(String header) {
        List<String> values = new ArrayList<>();
        if (header == null || header.isBlank()) {
            return values;
        }
        String[] entries = header.split(",");
        for (String entry : entries) {
            String[] parts = entry.split(";");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.toLowerCase(Locale.ROOT).startsWith("for=")) {
                    continue;
                }
                String token = trimmed.substring(4).trim();
                if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) {
                    token = token.substring(1, token.length() - 1);
                }
                String candidate = normalizeForwardedAddress(token);
                if (!candidate.isBlank()) {
                    values.add(candidate);
                }
            }
        }
        return values;
    }

    private List<String> parseXForwardedFor(String header) {
        List<String> values = new ArrayList<>();
        if (header == null || header.isBlank()) {
            return values;
        }
        for (String part : header.split(",")) {
            String candidate = normalizeForwardedAddress(part.trim());
            if (!candidate.isBlank()) {
                values.add(candidate);
            }
        }
        return values;
    }

    private String normalizeForwardedAddress(String raw) {
        if (raw == null || raw.isBlank() || "unknown".equalsIgnoreCase(raw)) {
            return "";
        }
        String candidate = raw.trim();
        if (candidate.startsWith("[")) {
            int end = candidate.indexOf(']');
            return end > 0 ? candidate.substring(1, end) : "";
        }
        int firstColon = candidate.indexOf(':');
        int lastColon = candidate.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            return candidate.substring(0, firstColon);
        }
        return candidate;
    }

    private InetAddress parseAddress(String candidate) {
        try {
            return candidate == null || candidate.isBlank() ? null : InetAddress.getByName(candidate);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
