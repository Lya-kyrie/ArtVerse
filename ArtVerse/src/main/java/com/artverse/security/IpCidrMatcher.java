package com.artverse.security;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;

@Component
public class IpCidrMatcher {

    public boolean containsAny(List<String> cidrs, InetAddress address) {
        if (cidrs == null || address == null) {
            return false;
        }
        return cidrs.stream().anyMatch(cidr -> contains(cidr, address));
    }

    public boolean contains(String cidr, InetAddress address) {
        try {
            String[] parts = cidr.trim().split("/", 2);
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            byte[] candidate = address.getAddress();
            if (network.length != candidate.length) {
                return false;
            }
            int bits = parts.length == 1 ? network.length * 8 : Integer.parseInt(parts[1]);
            if (bits < 0 || bits > network.length * 8) {
                return false;
            }
            for (int index = 0; index < network.length; index++) {
                int remaining = bits - index * 8;
                if (remaining <= 0) {
                    return true;
                }
                int mask = remaining >= 8 ? 0xff : 0xff << (8 - remaining);
                if ((network[index] & mask) != (candidate[index] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
