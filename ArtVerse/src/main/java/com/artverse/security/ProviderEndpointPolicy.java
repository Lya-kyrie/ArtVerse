package com.artverse.security;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** SSRF and header policy for all user-configured AI provider endpoints. */
@Component
public class ProviderEndpointPolicy {

    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "authorization", "host", "content-length", "connection", "proxy-connection",
            "proxy-authorization", "cookie", "set-cookie", "transfer-encoding", "upgrade",
            "te", "trailer", "keep-alive"
    );

    private final ArtVerseProperties properties;

    public ProviderEndpointPolicy(ArtVerseProperties properties) {
        this.properties = properties;
    }

    public URI requireSafeBaseUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean allowedScheme = "https".equals(scheme)
                    || (properties.getAgent().isProviderAllowHttp() && "http".equals(scheme));
            if (!allowedScheme) {
                throw new BusinessException(400, "Provider Base URL must use HTTPS");
            }
            if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new BusinessException(400, "Provider Base URL is invalid");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isForbidden(address) && !isAllowlisted(address)) {
                    throw new BusinessException(400, "Provider Base URL resolves to a private or reserved address");
                }
            }
            return uri;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(400, "Provider Base URL cannot be resolved safely");
        }
    }

    public void validateCustomHeaders(Map<String, String> headers) {
        headers.keySet().forEach(name -> {
            String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            if (normalized.isBlank() || FORBIDDEN_HEADERS.contains(normalized)
                    || normalized.startsWith("proxy-") || normalized.indexOf('\r') >= 0
                    || normalized.indexOf('\n') >= 0) {
                throw new BusinessException(400, "Custom provider header is not allowed: " + name);
            }
        });
    }

    private boolean isForbidden(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || uniqueLocalV6;
    }

    private boolean isAllowlisted(InetAddress address) {
        return properties.getAgent().getProviderAllowedPrivateCidrs().stream()
                .anyMatch(cidr -> contains(cidr, address));
    }

    private boolean contains(String cidr, InetAddress address) {
        try {
            String[] parts = cidr.trim().split("/", 2);
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            byte[] candidate = address.getAddress();
            if (network.length != candidate.length) return false;
            int bits = parts.length == 1 ? network.length * 8 : Integer.parseInt(parts[1]);
            if (bits < 0 || bits > network.length * 8) return false;
            for (int index = 0; index < network.length; index++) {
                int remaining = bits - index * 8;
                if (remaining <= 0) return true;
                int mask = remaining >= 8 ? 0xff : 0xff << (8 - remaining);
                if ((network[index] & mask) != (candidate[index] & mask)) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
