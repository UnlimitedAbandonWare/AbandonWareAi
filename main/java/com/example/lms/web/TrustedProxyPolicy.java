package com.example.lms.web;

import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicit immediate-peer policy for forwarded client addresses.
 * Empty configuration trusts no proxy.
 */
@Component
public final class TrustedProxyPolicy {

    static final String INVALID_CONFIGURATION = "trusted_proxy_configuration_invalid";
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_CONFIGURATION_LENGTH = 4_096;

    private final List<IpAddressMatcher> trustedPeers;

    @Autowired
    public TrustedProxyPolicy(@Value("${lms.owner.trusted-proxies:}") String configuredPeers) {
        this.trustedPeers = parse(configuredPeers);
    }

    public boolean trusts(String immediatePeer) {
        String validatedPeer = validatedAddress(immediatePeer);
        if (validatedPeer == null) {
            return false;
        }
        for (IpAddressMatcher matcher : trustedPeers) {
            try {
                if (matcher.matches(validatedPeer)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return false;
    }

    static String validatedAddress(String candidate) {
        if (candidate == null) {
            return null;
        }
        String value = candidate.trim();
        if (value.isEmpty() || value.indexOf('/') >= 0 || value.indexOf('%') >= 0) {
            return null;
        }
        return isIpv4Literal(value) || isIpv6Literal(value) ? value : null;
    }

    private static List<IpAddressMatcher> parse(String configuredPeers) {
        if (configuredPeers == null || configuredPeers.trim().isEmpty()) {
            return List.of();
        }
        if (configuredPeers.length() > MAX_CONFIGURATION_LENGTH) {
            throw invalidConfiguration();
        }
        String[] entries = configuredPeers.split(",", -1);
        if (entries.length > MAX_ENTRIES) {
            throw invalidConfiguration();
        }
        List<IpAddressMatcher> matchers = new ArrayList<>(entries.length);
        for (String entry : entries) {
            String value = entry.trim();
            if (!isValidAddressOrCidr(value)) {
                throw invalidConfiguration();
            }
            try {
                matchers.add(new IpAddressMatcher(value));
            } catch (IllegalArgumentException ex) {
                throw invalidConfiguration();
            }
        }
        return List.copyOf(matchers);
    }

    private static boolean isValidAddressOrCidr(String candidate) {
        int slash = candidate.indexOf('/');
        if (slash < 0) {
            return validatedAddress(candidate) != null;
        }
        if (slash == 0 || slash != candidate.lastIndexOf('/') || slash == candidate.length() - 1) {
            return false;
        }
        String address = candidate.substring(0, slash);
        String prefix = candidate.substring(slash + 1);
        if (validatedAddress(address) == null || !prefix.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            int prefixLength = Integer.parseInt(prefix);
            int maxPrefix = address.indexOf(':') >= 0 ? 128 : 32;
            return prefixLength >= 0 && prefixLength <= maxPrefix;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isIpv4Literal(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3
                    || !octet.chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                if (Integer.parseInt(octet) > 255) {
                    return false;
                }
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6Literal(String value) {
        if (value.indexOf(':') < 0
                || !value.chars().allMatch(ch -> Character.digit(ch, 16) >= 0
                || ch == ':' || ch == '.')) {
            return false;
        }
        try {
            return InetAddress.getByName(value) instanceof Inet6Address;
        } catch (Exception ignored) {
            TraceStore.put("web.trustedProxy.validationFallbackReason", "invalid_ipv6");
            TraceStore.inc("web.trustedProxy.validationFallbackCount");
            return false;
        }
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException(INVALID_CONFIGURATION);
    }
}
