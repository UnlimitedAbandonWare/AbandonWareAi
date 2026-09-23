// src/main/java/com/example/lms/trace/SafeRedactor.java
package com.example.lms.trace;

import com.example.lms.debug.PromptMasker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Thin wrapper around {@link PromptMasker} to centralise secret redaction
 * for trace logging.  All strings passed through this helper will have
 * API keys, bearer tokens, and other patterns removed before being
 * written to logs.  Additional redaction rules can be added here in
 * future without touching callers.
 */
public final class SafeRedactor {
    private static final System.Logger LOG = System.getLogger(SafeRedactor.class.getName());
    private static final int MAX_ITEMS = 80;
    private static final int MAX_DEPTH = 6;
    private static final Set<String> RETRIEVAL_ORDER_AUTHORITIES = Set.of(
            "DEFAULT", "PLAN_DSL", "CFVM_FAILURE_PATTERN", "CFVM", "MoE");
    private static final Set<String> EXECUTION_PRIMARY_MODES = Set.of(
            "NORMAL", "EXTREMEZ", "OVERDRIVE", "HYPERNOVA");
    private static final Set<String> CFVM_TEMP_SOURCES = Set.of(
            "CfvmKallocLearningProperties",
            "manual_override",
            "runtime_buffer_snapshot",
            "snapshot_restore");

    private SafeRedactor() {}

    /**
     * Redact sensitive information from the given string.  When the input
     * is {@code null}, {@code null} is returned.
     *
     * @param s text to redact
     * @return the redacted text
     */
    public static String redact(String s) {
        if (s == null) return null;
        return PromptMasker.mask(s);
    }

    public static String hashValue(String s) {
        String h = hash12(s);
        return h == null ? null : "hash:" + h;
    }

    public static String hash12(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(t.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(12);
            for (int i = 0; i < bytes.length && out.length() < 12; i++) {
                out.append(String.format("%02x", bytes[i]));
            }
            return out.substring(0, Math.min(12, out.length()));
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "SafeRedactor hash fallback textLength={0} errorType={1}",
                    t.length(), e.getClass().getSimpleName());
            return Integer.toHexString(t.hashCode());
        }
    }

    public static Object diagnosticValue(String key, Object value) {
        return diagnosticValue(key, value, 800);
    }

    public static Object diagnosticValue(String key, Object value, int maxStringLen) {
        return diagnosticValue(key, value, Math.max(64, maxStringLen), 0);
    }

    public static String diagnosticText(String key, String value, int maxStringLen) {
        Object v = diagnosticValue(key, value, maxStringLen);
        return v == null ? null : String.valueOf(v);
    }

    public static String safeMessage(String value, int maxStringLen) {
        if (value == null) return null;
        return limit(PromptMasker.mask(value).replace('\n', ' ').replace('\r', ' ').trim(), maxStringLen);
    }

    public static String traceFlagDetail(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        String text = value == null ? "" : String.valueOf(value).trim().replaceAll("[\\r\\n\\t]+", " ");
        if (text.isBlank()) return "present";
        if (text.matches("[0-9]+(?:\\.[0-9]+)?")) return text;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean sensitive = lower.contains("authorization")
                || lower.contains("owner-token")
                || lower.contains("owner_token")
                || lower.contains("cookie")
                || lower.contains("secret=")
                || lower.contains("token=")
                || lower.contains("api_key=")
                || lower.contains("apikey=")
                || lower.contains("password=")
                || text.matches("(?i).*(sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}).*");
        if (sensitive || !text.matches("[A-Za-z0-9_.:-]{1,80}")) return "present";
        return lower.replaceAll("[^a-z0-9_.:-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }

    public static String traceLabel(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim().replaceAll("[\\r\\n\\t]+", " ");
        if (text.isBlank()) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        boolean sensitive = isSecretFieldLabel(text)
                || lower.contains("authorization")
                || lower.contains("owner-token")
                || lower.contains("owner_token")
                || lower.contains("ownertoken")
                || lower.contains("cookie")
                || lower.contains("secret=")
                || lower.contains("token=")
                || lower.contains("api_key=")
                || lower.contains("apikey=")
                || lower.contains("password=")
                || text.matches("(?i).*(sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}).*");
        if (!sensitive && text.matches("[A-Za-z0-9_.:-]{1,80}")) return text;
        return hashValue(text);
    }

    private static boolean isSecretFieldLabel(String text) {
        String k = normalizeKey(text);
        return k.equals("apikey")
                || k.contains(".apikey")
                || k.equals("clientsecret")
                || k.contains(".clientsecret")
                || k.endsWith("clientsecret")
                || k.equals("servicerolekey")
                || k.contains(".servicerolekey")
                || k.endsWith("servicerolekey")
                || k.equals("openaikey")
                || k.contains(".openaikey")
                || k.endsWith("openaikey")
                || k.equals("accesstoken")
                || k.contains(".accesstoken")
                || k.endsWith("accesstoken")
                || k.equals("refreshtoken")
                || k.contains(".refreshtoken")
                || k.endsWith("refreshtoken")
                || k.equals("bearertoken")
                || k.contains(".bearertoken")
                || k.endsWith("bearertoken");
    }

    public static String traceLabelOrFallback(Object value, String fallback) {
        String label = traceLabel(value);
        return label == null || label.isBlank() ? fallback : label;
    }

    public static boolean isRestrictedKey(String key) {
        String k = normalizeKey(key);
        return isSecretKey(k) || isIdentifierKey(k) || isRawContentKey(k) || isUrlKey(k);
    }

    @SuppressWarnings("unchecked")
    private static Object diagnosticValue(String key, Object value, int maxStringLen, int depth) {
        if (value == null) return null;
        if (depth > MAX_DEPTH) return "(depth-limit)";

        String normalizedKey = normalizeKey(key);
        if (value instanceof String s) {
            return diagnosticString(normalizedKey, s, maxStringLen);
        }
        if (value instanceof Number || value instanceof Boolean) {
            if (isSecretKey(normalizedKey) && !isQueryTransformerSuperTokenDiagnosticKey(normalizedKey)) {
                return "(redacted)";
            }
            if (isIdentifierKey(normalizedKey)
                    || (isRawContentKey(normalizedKey)
                    && !isQueryRewriteDiagnosticKey(normalizedKey)
                    && !isQueryTransformerSuperTokenDiagnosticKey(normalizedKey))) {
                return scalarSummary(String.valueOf(value), false);
            }
            return value;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (i++ >= MAX_ITEMS) {
                    out.put("_truncated", true);
                    break;
                }
                if (e == null || e.getKey() == null) continue;
                String childKey = String.valueOf(e.getKey());
                out.put(traceLabelOrFallback(childKey, "field"),
                        diagnosticValue(joinKey(key, childKey), e.getValue(), maxStringLen, depth + 1));
            }
            return out;
        }
        if (value instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            int i = 0;
            for (Object item : c) {
                if (i++ >= MAX_ITEMS) {
                    out.add("(truncated)");
                    break;
                }
                out.add(diagnosticValue(key, item, maxStringLen, depth + 1));
            }
            return out;
        }
        if (value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int len = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < Math.min(len, MAX_ITEMS); i++) {
                out.add(diagnosticValue(key, java.lang.reflect.Array.get(value, i), maxStringLen, depth + 1));
            }
            if (len > MAX_ITEMS) out.add("(truncated)");
            return out;
        }
        return diagnosticString(normalizedKey, String.valueOf(value), maxStringLen);
    }

    private static Object diagnosticString(String normalizedKey, String raw, int maxStringLen) {
        if (raw == null) return null;
        if (raw.matches("hash:[0-9a-f]{12}")) {
            return raw;
        }
        if (isEvidenceObservedAtKey(normalizedKey) && isIsoInstant(raw)) {
            return raw;
        }
        if (isSecretKey(normalizedKey) && !isQueryTransformerSuperTokenDiagnosticKey(normalizedKey)) {
            return "(redacted)";
        }
        if (isIdentifierKey(normalizedKey)) {
            return hashValue(raw);
        }
        if (isUrlKey(normalizedKey)) {
            return urlSummary(raw);
        }
        if (isQueryRewriteDiagnosticKey(normalizedKey)) {
            return limit(PromptMasker.mask(raw).replace('\n', ' ').replace('\r', ' ').trim(), maxStringLen);
        }
        if (isQueryTransformerSuperTokenDiagnosticKey(normalizedKey)) {
            return limit(PromptMasker.mask(raw).replace('\n', ' ').replace('\r', ' ').trim(), maxStringLen);
        }
        if (isRawContentKey(normalizedKey)) {
            return scalarSummary(raw, true);
        }
        if (isHarmonyAuthorityKey(normalizedKey)) {
            return sanitizeHarmonyAuthorityValue(normalizedKey, raw);
        }
        if (isReasonKey(normalizedKey)) {
            return traceLabelOrFallback(raw, "unknown");
        }
        if (isSafeScalarKey(normalizedKey)) {
            return limit(PromptMasker.mask(raw).replace('\n', ' ').replace('\r', ' ').trim(), maxStringLen);
        }
        String masked = PromptMasker.mask(raw);
        if (!masked.equals(raw)) {
            return limit(masked.replace('\n', ' ').replace('\r', ' ').trim(), maxStringLen);
        }
        return scalarSummary(raw, true);
    }

    private static boolean isEvidenceObservedAtKey(String normalizedKey) {
        return normalizedKey != null
                && (normalizedKey.equals("observedat") || normalizedKey.endsWith(".observedat"));
    }

    private static boolean isIsoInstant(String value) {
        if (value == null || value.length() > 40) {
            return false;
        }
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static Map<String, Object> scalarSummary(String raw, boolean includePreviewSafety) {
        Map<String, Object> out = new LinkedHashMap<>();
        String s = raw == null ? "" : raw;
        String trimmed = s.trim();
        out.put("present", !trimmed.isEmpty());
        out.put("len", s.length());
        out.put("hash12", hash12(s));
        String host = extractHost(trimmed);
        if (host != null && !host.isBlank()) {
            out.put("host", host);
        }
        if (!includePreviewSafety && trimmed.isEmpty()) {
            out.put("empty", true);
        }
        return out;
    }

    private static Map<String, Object> urlSummary(String raw) {
        Map<String, Object> out = scalarSummary(raw, true);
        String host = extractHost(raw);
        if (host != null && !host.isBlank()) {
            out.put("host", host);
        }
        return out;
    }

    private static String extractHost(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Throwable ignore) {
            LOG.log(System.Logger.Level.DEBUG, "SafeRedactor host extraction failed valueLength={0} errorType={1}",
                    value.length(), ignore.getClass().getSimpleName());
            return null;
        }
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static String joinKey(String parent, String child) {
        if (parent == null || parent.isBlank()) return child;
        return parent + "." + child;
    }

    private static boolean isSecretKey(String k) {
        if (k.equals("tokenbucket")
                || k.endsWith(".tokenbucket")
                || k.equals("querytokenbucket")
                || k.endsWith(".querytokenbucket")) {
            return false;
        }
        return k.contains("authorization")
                || k.contains("apikey")
                || k.contains("api.key")
                || (k.contains("servicerole") && k.contains("key"))
                || k.contains("secret")
                || k.contains("token")
                || k.contains("password")
                || k.contains("cookie")
                || k.contains("ownertoken")
                || (k.contains("openai") && k.contains("key"));
    }

    private static boolean isIdentifierKey(String k) {
        return k.equals("sid")
                || k.endsWith(".sid")
                || k.equals("sessionid")
                || k.endsWith(".sessionid")
                || k.equals("datasetpath")
                || k.endsWith(".datasetpath")
                || k.equals("traceid")
                || k.endsWith(".traceid")
                || k.equals("trace")
                || k.endsWith(".trace")
                || k.equals("trace.id")
                || k.endsWith(".trace.id")
                || k.equals("requestid")
                || k.endsWith(".requestid")
                || k.equals("xrequestid")
                || k.endsWith(".xrequestid")
                || k.equals("rid")
                || k.endsWith(".rid");
    }

    private static boolean isRawContentKey(String k) {
        return isPathKey(k)
                || k.contains("rawquery")
                || k.contains("effectivequery")
                || k.endsWith("query")
                || k.contains(".query")
                || k.contains("snippet")
                || k.contains("prompt")
                || k.contains("html")
                || k.contains("httpquery")
                || k.contains("httpua")
                || k.contains("useragent")
                || k.contains("textpreview")
                || k.contains("usermessage")
                || k.contains("rawtext")
                || k.contains("origtext")
                || k.contains("payload");
    }

    private static boolean isQueryRewriteDiagnosticKey(String k) {
        if (k == null || !k.startsWith("web.query.rewrite.")) {
            return false;
        }
        return k.endsWith(".queryseedhash12")
                || k.endsWith(".variantsethash12")
                || k.endsWith(".verificationlanecount")
                || k.endsWith(".explorationlanecount")
                || k.endsWith(".lanelabels")
                || k.endsWith(".lanesummary")
                || k.endsWith(".variantlanetemperaturehints")
                || k.endsWith(".temperatureprofile")
                || k.endsWith(".validationtemperature")
                || k.endsWith(".explorationtemperature")
                || k.endsWith(".explorationrate")
                || k.endsWith(".requestedexplorationrate")
                || k.endsWith(".requestedexplorationtemperature")
                || k.endsWith(".requestedtemperatureprofile");
    }

    private static boolean isQueryTransformerSuperTokenDiagnosticKey(String k) {
        if (k == null || !k.startsWith("querytransformer.subqueries.supertokens.")) {
            return false;
        }
        return k.endsWith(".enabled")
                || k.endsWith(".reason")
                || k.endsWith(".branchcount")
                || k.endsWith(".tokencount")
                || k.endsWith(".submodelcount")
                || k.endsWith(".submodelids")
                || k.endsWith(".submodelassignmentcount")
                || k.endsWith(".branchtitlecount")
                || k.endsWith(".branchtitlehashcount")
                || k.endsWith(".branchtitlehashes")
                || k.endsWith(".branchtitlemetadatacount")
                || k.endsWith(".branchtitlelengths")
                || k.endsWith(".branchtitletermcounts")
                || k.endsWith(".branchquerymetadatacount")
                || k.endsWith(".branchqueryhashes")
                || k.endsWith(".branchquerylengths")
                || k.endsWith(".branchquerytermcounts")
                || k.endsWith(".branchquerycoveragecomplete")
                || k.endsWith(".titlepresent")
                || k.endsWith(".titlehash12")
                || k.endsWith(".titletokencount")
                || k.endsWith(".titlelength")
                || k.endsWith(".branchtitlecoveragecomplete")
                || k.endsWith(".axiscount")
                || k.endsWith(".axes")
                || k.endsWith(".coveragecomplete");
    }

    private static boolean isPathKey(String k) {
        return k.equals("path")
                || k.endsWith(".path")
                || k.endsWith("path");
    }

    private static boolean isUrlKey(String k) {
        return k.equals("url")
                || k.endsWith(".url")
                || k.equals("uri")
                || k.endsWith(".uri")
                || k.equals("link")
                || k.endsWith(".link");
    }

    private static boolean isReasonKey(String k) {
        return k.contains("reason");
    }

    private static boolean isSafeScalarKey(String k) {
        return k.contains("provider")
                || k.contains("routekey")
                || k.equals("model")
                || k.endsWith(".model")
                || k.contains("modelname")
                || k.contains("enabled")
                || k.contains("haskey")
                || k.contains("keysource")
                || k.contains("endpointhost")
                || k.contains("host")
                || k.contains("count")
                || k.contains("size")
                || k.contains("len")
                || k.contains("reason")
                || k.contains("failure")
                || k.contains("toolid")
                || k.contains("planid")
                || k.contains("stage")
                || k.equals("layer")
                || k.endsWith(".layer")
                || k.contains("action")
                || k.contains("outcome")
                || k.contains("exceptiontype")
                || k.contains("nightmarekey")
                || k.contains("tokenbucket")
                || k.equals("target")
                || k.endsWith(".target")
                || k.contains("patternid")
                || k.contains("decision")
                || k.contains("confidence")
                || k.contains("highrisk")
                || k.contains("class")
                || k.contains("status")
                || k.contains("method")
                || k.contains("path")
                || k.contains("domainpolicy")
                || k.contains("datasetfile")
                || k.contains("ratelimit")
                || k.contains("tookms")
                || k.contains("timeoutms")
                || k.contains("score")
                || k.contains("rank")
                || k.contains("stage")
                || k.contains("axis")
                || k.contains("policy")
                || k.contains("lane")
                || k.contains("kind")
                || k.contains("hotspot")
                || k.contains("coverage")
                || k.contains("contaminationscore")
                || k.contains("requeryconfirmed")
                || k.equals("langgraph.invoke.trigger")
                || k.endsWith(".langgraph.invoke.trigger")
                || k.contains("hash");
    }

    private static boolean isHarmonyAuthorityKey(String key) {
        return key.equals("retrievalorder.lastsetby")
                || key.equals("routing.executionplan.primarymode")
                || key.equals("cfvm.tempsource")
                || key.equals("hypernova.whitening.provider");
    }

    private static Object sanitizeHarmonyAuthorityValue(String key, String raw) {
        String value = raw == null ? "" : raw.trim();
        boolean allowed = switch (key) {
            case "retrievalorder.lastsetby" -> RETRIEVAL_ORDER_AUTHORITIES.contains(value);
            case "routing.executionplan.primarymode" -> EXECUTION_PRIMARY_MODES.contains(value);
            case "cfvm.tempsource" -> CFVM_TEMP_SOURCES.contains(value);
            case "hypernova.whitening.provider" -> value.length() <= 64
                    && value.matches("[a-z0-9][a-z0-9_.:-]{0,63}")
                    && value.equals(traceLabel(value));
            default -> false;
        };
        return allowed ? value : scalarSummary(raw, true);
    }

    private static String limit(String s, int max) {
        if (s == null) return null;
        int lim = Math.max(16, max);
        return s.length() <= lim ? s : s.substring(0, lim) + "...";
    }
}
