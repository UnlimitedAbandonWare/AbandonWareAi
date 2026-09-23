package com.example.lms.transform;

import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.trace.OrchTrace;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class QueryTransformerVariantSupport {

    private static final Pattern UNWANTED_WORD_PATTERN = Pattern.compile("(?i)(을지대학교|eulji)");
    private static final Pattern DOMAIN_SCOPE_PREFIX = Pattern.compile("(?i)^\\s*(site\\s+)?\\S+\\s+ac\\s+kr\\b");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{IsHangul}\\p{L}\\p{Nd}]+");
    private static final Map<String, Set<String>> PROTECTED_TERMS = Map.of(
            "원신", Set.of("원숭이", "monkey"));

    private QueryTransformerVariantSupport() {
    }

    static List<String> filterUnwantedVariants(List<String> variants, String original) {
        boolean originalContainsUnwanted = UNWANTED_WORD_PATTERN.matcher(original).find();
        return variants.stream()
                .filter(v -> !hasDomainScopePrefix(v))
                .filter(v -> originalContainsUnwanted || !UNWANTED_WORD_PATTERN.matcher(v).find())
                .filter(v -> !violatesProtectedTerms(original, v))
                .toList();
    }

    static List<String> selectLlmVariants(String q, String subject, List<String> raw, int maxVariants) {
        if (isNamedEntityLike(q)) {
            List<String> filtered = raw.stream()
                    .filter(v -> isEntitySafeVariant(q, v))
                    .collect(Collectors.toList());

            if (filtered.isEmpty()) {
                return q == null || q.isBlank()
                        ? List.of()
                        : List.of(q.trim());
            }

            String base = q == null ? "" : q.trim();
            boolean hasOriginal = !base.isEmpty() && filtered.stream()
                    .anyMatch(v -> v.equalsIgnoreCase(base));

            List<String> out = new ArrayList<>();
            if (!base.isEmpty() && !hasOriginal) {
                out.add(base);
            }
            out.addAll(filtered);

            if (subject != null && !subject.isBlank()) {
                Set<String> subjTokens = tokens(subject);
                out = out.stream()
                        .filter(s -> !Collections.disjoint(tokens(s), subjTokens))
                        .collect(Collectors.toList());
            }

            return out.stream()
                    .distinct()
                    .limit(maxVariants)
                    .toList();
        }

        return raw.stream()
                .filter(s -> subject == null || !Collections.disjoint(tokens(s), tokens(subject)))
                .distinct()
                .limit(maxVariants)
                .toList();
    }

    static List<String> cheapVariantsFallback(
            String q,
            String subject,
            boolean cheapFallbackEnabled,
            AnchorNarrower anchorNarrower,
            int cheapVariants,
            int maxVariants,
            BiFunction<String, String, Map<String, Object>> recoveredQueryTraceData) {
        q = sanitize(q);
        subject = sanitize(subject);
        if (q == null || q.isBlank()) {
            try {
                GuardContext gc = GuardContextHolder.getOrDefault();
                String uq = (gc == null) ? null : gc.getUserQuery();
                if (uq != null && !uq.isBlank()) {
                    q = sanitize(uq);
                    TraceStore.put("qtx.cheapFallback.recovered", true);
                    TraceStore.put("qtx.cheapFallback.recovered.source", "guardContext.userQuery");

                    try {
                        OrchTrace.appendEvent(OrchTrace.newEvent(
                                "aux", "queryTransformer", "cheapFallbackRecovered",
                                recoveredQueryTraceData.apply("guardContext.userQuery", q)));
                    } catch (Throwable ignore) {
                        traceSuppressed("recoveredOrchTrace", ignore);
                    }
                }
            } catch (Throwable ignore) {
                traceSuppressed("recoverContext", ignore);
            }

            if (q == null || q.isBlank()) {
                return List.of();
            }
        }

        if (!cheapFallbackEnabled || anchorNarrower == null) {
            return List.of(q);
        }

        int limit = Math.max(1, Math.min(maxVariants, cheapVariants));

        GuardContext gc = GuardContextHolder.get();
        if (gc != null && gc.isAuxDown()) {
            limit = Math.min(limit, 2);
        }

        List<String> protectedTerms;
        try {
            protectedTerms = PROTECTED_TERMS.keySet().stream().filter(q::contains).toList();
        } catch (Exception ignored) {
            traceSuppressed("protectedTerms", ignored);
            protectedTerms = List.of();
        }

        AnchorNarrower.Anchor anchor = anchorNarrower.pick(q, protectedTerms, null);
        List<String> candidates = anchorNarrower.cheapVariants(q, anchor);

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String c : candidates) {
            String s = sanitize(c);
            if (s == null || s.isBlank()) {
                continue;
            }
            if (subject != null && !subject.isBlank() && Collections.disjoint(tokens(s), tokens(subject))) {
                continue;
            }
            out.add(s);
            if (out.size() >= limit) {
                break;
            }
        }
        if (out.isEmpty()) {
            return List.of(q);
        }
        return new ArrayList<>(out);
    }

    static Set<String> tokens(String s) {
        if (s == null) {
            return Set.of();
        }
        return Arrays.stream(
                NON_ALNUM.matcher(s.toLowerCase(Locale.ROOT))
                        .replaceAll(" ")
                        .trim()
                        .split("\\s+"))
                .filter(t -> t.length() > 1)
                .collect(Collectors.toSet());
    }

    static boolean hasDomainScopePrefix(String value) {
        return value != null && DOMAIN_SCOPE_PREFIX.matcher(value).find();
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(failure);
        TraceStore.put("qtx.variant.suppressed.stage", safeStage);
        TraceStore.put("qtx.variant.suppressed.errorType", safeErrorType);
        TraceStore.put("qtx.variant.suppressed." + safeStage, true);
        TraceStore.put("qtx.variant.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    static boolean allowedByUnwantedTerm(String candidate, String original) {
        return candidate != null
                && (!UNWANTED_WORD_PATTERN.matcher(candidate).find()
                        || UNWANTED_WORD_PATTERN.matcher(original).find());
    }

    static boolean overlapsSubject(String candidate, String subject) {
        return subject == null || !Collections.disjoint(tokens(candidate), tokens(subject));
    }

    private static String sanitize(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isNamedEntityLike(String q) {
        if (q == null) {
            return false;
        }
        String trimmed = q.strip();
        if (trimmed.length() < 2) {
            return false;
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        boolean hasWh = lower.contains("왜")
                || lower.contains("어떻게")
                || lower.contains("무엇")
                || lower.contains("무슨")
                || lower.contains("설명")
                || lower.contains("알려줘")
                || lower.startsWith("explain");

        if (hasWh) {
            return false;
        }

        Set<String> tokenSet = tokens(trimmed);
        int tokenCount = tokenSet.size();
        return tokenCount > 0 && tokenCount <= 3;
    }

    private static boolean isEntitySafeVariant(String original, String variant) {
        if (variant == null || variant.isBlank()) {
            return false;
        }
        if (original == null) {
            return true;
        }

        Set<String> origTokens = tokens(original);
        Set<String> varTokens = tokens(variant);

        List<String> risky = List.of(
                "번개", "전기", "얼음", "바람", "물", "불", "빛", "어둠",
                "성우", "더빙", "나레이터",
                "비밀", "조직", "단체", "길드",
                "원소", "속성", "직업", "클래스");

        for (String r : risky) {
            if (varTokens.contains(r) && !origTokens.contains(r)) {
                return false;
            }
        }
        return true;
    }

    private static boolean violatesProtectedTerms(String original, String variant) {
        Set<String> oTok = tokens(original);
        Set<String> vTok = tokens(variant);
        for (var e : PROTECTED_TERMS.entrySet()) {
            if (oTok.contains(e.getKey())) {
                for (String banned : e.getValue()) {
                    if (vTok.contains(banned)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
