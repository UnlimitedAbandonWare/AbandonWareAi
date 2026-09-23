package com.example.lms.service.search;

import com.example.lms.config.ConfigValueGuards;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves Naver credential inputs without logging secret values.
 */
public final class NaverCredentialBridge {

    private NaverCredentialBridge() {
    }

    public record Credential(String id, String secret) {
    }

    public static String resolveKeysCsv(String rawKeys, String clientId, String clientSecret) {
        return resolveKeysCsvFull(rawKeys, null, clientId, clientSecret);
    }

    public static String resolveKeysCsvFull(
            String naverKeysProp,
            String naverKeysEnv,
            String clientId,
            String clientSecret) {
        String prop = validKeysOrEmpty(naverKeysProp);
        if (!prop.isEmpty()) {
            return prop;
        }
        String env = validKeysOrEmpty(naverKeysEnv);
        if (!env.isEmpty()) {
            return env;
        }
        return bridgeClientCredentials(clientId, clientSecret);
    }

    public static String bridgeClientCredentials(String clientId, String clientSecret) {
        if (ConfigValueGuards.isMissing(clientId) || ConfigValueGuards.isMissing(clientSecret)) {
            return "";
        }
        return clientId.trim() + ":" + clientSecret.trim();
    }

    public static int countCredentialPairs(String rawKeys) {
        if (ConfigValueGuards.isMissing(rawKeys)) {
            return 0;
        }
        int count = 0;
        boolean sawBareId = false;
        for (String token : splitCsv(rawKeys)) {
            String part = stripQuotes(token == null ? "" : token.trim()).replace(';', ':');
            if (part.isEmpty()) {
                continue;
            }
            int idx = part.indexOf(':');
            if (idx >= 0) {
                if (validPair(part.substring(0, idx), part.substring(idx + 1))) {
                    count++;
                }
                continue;
            }
            int commaIdx = part.indexOf(',');
            if (commaIdx >= 0) {
                if (validPair(part.substring(0, commaIdx), part.substring(commaIdx + 1))) {
                    count++;
                }
                continue;
            }
            if (sawBareId && !ConfigValueGuards.isMissing(part)) {
                count++;
                sawBareId = false;
            } else {
                sawBareId = !ConfigValueGuards.isMissing(part);
            }
        }
        return count;
    }

    public static boolean hasValidCredentialPair(String rawKeys) {
        return countCredentialPairs(rawKeys) > 0;
    }

    private static String validKeysOrEmpty(String rawKeys) {
        String raw = rawKeys == null ? "" : rawKeys.trim();
        if (raw.isEmpty() || ConfigValueGuards.isMissing(rawKeys) || isAbsentPlaceholder(raw)) {
            return "";
        }
        return hasValidCredentialPair(rawKeys) ? rawKeys : "";
    }

    private static boolean validPair(String id, String secret) {
        return !ConfigValueGuards.isMissing(id == null ? null : id.trim())
                && !ConfigValueGuards.isMissing(secret == null ? null : secret.trim());
    }

    private static boolean isAbsentPlaceholder(String raw) {
        if (raw == null) {
            return true;
        }
        String value = raw.trim();
        return value.contains("${") && value.contains("}")
                || ConfigValueGuards.MISSING_SENTINEL.equalsIgnoreCase(value);
    }

    private static List<String> splitCsv(String value) {
        if (ConfigValueGuards.isMissing(value)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\'') {
                if (!inQuotes) {
                    inQuotes = true;
                    quoteChar = ch;
                } else if (quoteChar == ch) {
                    inQuotes = false;
                    quoteChar = 0;
                }
                current.append(ch);
                continue;
            }
            if (ch == ',' && !inQuotes) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        out.add(current.toString());
        return out;
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1).trim();
        }
        return v;
    }
}
