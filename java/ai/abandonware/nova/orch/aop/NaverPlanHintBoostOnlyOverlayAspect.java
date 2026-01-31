package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NaverPlanHintBoostOnlyOverlayProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Map;

/**
 * Boost-only overlay for Naver search.
 *
 * <p>
 * safe.v1 계열 plan-hint(officialOnly/domainProfile)이 Naver 내부의
 * strictDomainRequired를 강제하면 후보군 고갈(web starvation) → 재시도/추가 호출이
 * 폭증하는 패턴이 생길 수 있다.
 *
 * <p>
 * 이 Aspect는 Naver 호출 구간에서만 GuardContext를 shallow-copy로 교체하고,
 * officialOnly=false, domainProfile=null 로 완화한 뒤 즉시 복원한다.
 * (민감/의료/위치성 쿼리는 제외)
 */
@Slf4j
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class NaverPlanHintBoostOnlyOverlayAspect {

    private static final String ACTIVE_KEY = "web.naver.planHintBoostOnly.active";

    private final NaverPlanHintBoostOnlyOverlayProperties props;

    public NaverPlanHintBoostOnlyOverlayAspect(NaverPlanHintBoostOnlyOverlayProperties props) {
        // fail-soft default (but normally injected by AutoConfiguration)
        this.props = (props == null) ? new NaverPlanHintBoostOnlyOverlayProperties() : props;
    }

    @Around("execution(* com.example.lms.service.NaverSearchService.searchWithTraceSync(..))" +
            " || execution(* com.example.lms.service.NaverSearchService.searchSnippetsSync(..))")
    public Object aroundNaverSync(ProceedingJoinPoint pjp) throws Throwable {
        // Re-entrancy guard (nested calls / multiple advices on the same thread)
        if (truthy(TraceStore.get(ACTIVE_KEY))) {
            return pjp.proceed();
        }

        GuardContext original;
        try {
            original = GuardContextHolder.get();
        } catch (Throwable t) {
            return pjp.proceed();
        }

        if (original == null) {
            return pjp.proceed();
        }

        String query = extractQuery(pjp.getArgs());

        boolean planHintStrict = original.isOfficialOnly() || hasText(original.getDomainProfile());
        try {
            TraceStore.put("web.naver.planHintBoostOnly.planHintStrict", planHintStrict);
            TraceStore.put("web.naver.planHintBoostOnly.applyOnlyWhenPlanHintStrict",
                    props.isApplyOnlyWhenPlanHintStrict());
        } catch (Throwable ignore) {
            // fail-soft
        }
        if (props.isApplyOnlyWhenPlanHintStrict() && !planHintStrict) {
            try {
                TraceStore.put("web.naver.planHintBoostOnly.applied", false);
                TraceStore.put("web.naver.planHintBoostOnly.decision", "noop:not_strict");
            } catch (Throwable ignore) {
                // fail-soft
            }
            return pjp.proceed();
        }

        String skipReason = computeSkipReason(original, query);
        if (skipReason != null) {
            // Observability for tuning: only record when strict hint was present.
            if (planHintStrict) {
                try {
                    TraceStore.inc("web.naver.planHintBoostOnly.skipped.count");
                    TraceStore.inc("web.naver.planHintBoostOnly.skipped." + skipReason + ".count");
                    TraceStore.put("web.naver.planHintBoostOnly.skipped.reason", skipReason);
                    TraceStore.put("web.naver.planHintBoostOnly.applied", false);
                    TraceStore.put("web.naver.planHintBoostOnly.decision", "skipped:" + skipReason);
                    TraceStore.put("web.naver.planHintBoostOnly.method", pjp.getSignature().getName());
                    TraceStore.put("web.naver.planHintBoostOnly.original.officialOnly", original.isOfficialOnly());
                    TraceStore.put("web.naver.planHintBoostOnly.original.domainProfile", original.getDomainProfile());
                    TraceStore.put("web.naver.planHintBoostOnly.query", query);

                    String rid = firstNonBlank(
                            asString(TraceStore.get("x-request-id")),
                            MDC.get("x-request-id"),
                            asString(TraceStore.get("requestId")),
                            MDC.get("requestId"));
                    String sid = firstNonBlank(
                            asString(TraceStore.get("x-session-id")),
                            MDC.get("x-session-id"),
                            asString(TraceStore.get("sessionId")),
                            MDC.get("sessionId"));
                    TraceStore.put("web.naver.planHintBoostOnly.rid", rid);
                    TraceStore.put("web.naver.planHintBoostOnly.sessionId", sid);
                } catch (Throwable ignore) {
                    // fail-soft
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("[NaverOverlay] skipped: reason={}, planHintStrict={}, query={}", skipReason, planHintStrict,
                        query);
            }
            return pjp.proceed();
        }

        if (!planHintStrict) {
            // If caller disabled applyOnlyWhenPlanHintStrict and context is not strict,
            // there is nothing to relax.
            return pjp.proceed();
        }

        GuardContext overlay = shallowCopy(original);
        overlay.setOfficialOnly(false);
        overlay.setDomainProfile(null);

        // Debug correlation: rid/sessionId for post-mortems
        try {
            TraceStore.inc("web.naver.planHintBoostOnly.count");
            TraceStore.put("web.naver.planHintBoostOnly.applied", true);
            TraceStore.put("web.naver.planHintBoostOnly.decision", "applied");
            TraceStore.put("web.naver.planHintBoostOnly.planHintStrict", planHintStrict);
            TraceStore.put("web.naver.planHintBoostOnly.overlay.officialOnly", overlay.isOfficialOnly());
            TraceStore.put("web.naver.planHintBoostOnly.overlay.domainProfile", overlay.getDomainProfile());

            // Tuning knobs snapshot (so operators can debug without redeploy)
            NaverPlanHintBoostOnlyOverlayProperties.Location lc = props.getLocation();
            TraceStore.put("web.naver.planHintBoostOnly.config.location.weakOnlyPromote.enabled",
                    lc.isWeakOnlyPromoteEnabled());
            TraceStore.put("web.naver.planHintBoostOnly.config.location.weakOnlyPromote.suffixes",
                    lc.getWeakOnlyPromoteSuffixes());
            TraceStore.put("web.naver.planHintBoostOnly.config.location.weakOnlyPromote.minPrefixDefault",
                    lc.getWeakOnlyPromoteMinPrefixChars());
            TraceStore.put("web.naver.planHintBoostOnly.config.location.weakOnlyPromote.minPrefixBySuffix",
                    lc.getWeakOnlyPromoteMinPrefixCharsBySuffix());
            TraceStore.put("web.naver.planHintBoostOnly.config.location.weakOnlyPromote.allowTokens",
                    lc.getWeakOnlyPromoteAllowTokens());
            TraceStore.put("web.naver.planHintBoostOnly.config.location.weakOnlyPromote.denyKeywords",
                    lc.getWeakOnlyPromoteDenyKeywords());
            TraceStore.put("web.naver.planHintBoostOnly.method", pjp.getSignature().getName());
            TraceStore.put("web.naver.planHintBoostOnly.original.officialOnly", original.isOfficialOnly());
            TraceStore.put("web.naver.planHintBoostOnly.original.domainProfile", original.getDomainProfile());
            TraceStore.put("web.naver.planHintBoostOnly.query", query);

            String rid = firstNonBlank(
                    asString(TraceStore.get("x-request-id")),
                    MDC.get("x-request-id"),
                    asString(TraceStore.get("requestId")),
                    MDC.get("requestId"));
            String sid = firstNonBlank(
                    asString(TraceStore.get("x-session-id")),
                    MDC.get("x-session-id"),
                    asString(TraceStore.get("sessionId")),
                    MDC.get("sessionId"));
            TraceStore.put("web.naver.planHintBoostOnly.rid", rid);
            TraceStore.put("web.naver.planHintBoostOnly.sessionId", sid);
        } catch (Throwable ignore) {
            // fail-soft
        }

        if (log.isDebugEnabled()) {
            log.debug("[NaverOverlay] applied (relax officialOnly/domainProfile) query={}", query);
        }
        TraceStore.put(ACTIVE_KEY, true);
        GuardContextHolder.set(overlay);
        try {
            return pjp.proceed();
        } finally {
            try {
                GuardContextHolder.set(original);
            } catch (Throwable ignore) {
                // fail-soft
            }
            TraceStore.put(ACTIVE_KEY, null);
        }
    }

    private static boolean shouldSkip(GuardContext ctx, String query) {
        // High-risk / sensitive topics: never relax strict hint.
        if (ctx.isSensitiveTopic() || ctx.isHighRiskQuery()) {
            return true;
        }
        if (!hasText(query)) {
            return false;
        }
        // Kept for backward compatibility; new logic is in computeSkipReason.
        return false;
    }

    private String computeSkipReason(GuardContext ctx, String query) {
        // High-risk / sensitive topics: never relax strict hint.
        if (ctx.isSensitiveTopic() || ctx.isHighRiskQuery()) {
            return "sensitive";
        }
        if (!hasText(query)) {
            return null;
        }

        if (looksLikeAny(query, props.getMedical().getKeywords())) {
            return "medical";
        }

        if (isLocationQuery(query)) {
            return "location";
        }

        return null;
    }

    private boolean isLocationQuery(String query) {
        if (!hasText(query)) {
            return false;
        }
        String qLower = query.toLowerCase();

        NaverPlanHintBoostOnlyOverlayProperties.Location loc = props.getLocation();

        boolean localIntentHit = loc.isLocalIntentEnabled() && looksLikeAnyLower(qLower, loc.getLocalIntentKeywords());
        if (localIntentHit) {
            // Observability: explain why overlay was skipped as "location"
            try {
                TraceStore.put("web.naver.planHintBoostOnly.location.localIntentHit", true);
                TraceStore.put("web.naver.planHintBoostOnly.location.localIntentKeyword",
                        firstMatchLower(qLower, loc.getLocalIntentKeywords()));
            } catch (Throwable ignore) {
                // fail-soft
            }
            return true;
        }

        // Fast path: no location keywords configured
        if (loc.getKeywords() == null || loc.getKeywords().isEmpty()) {
            return false;
        }

        boolean negativeHit = looksLikeAnyLower(qLower, loc.getNegativeKeywords());
        String negativeKw = negativeHit ? firstMatchLower(qLower, loc.getNegativeKeywords()) : null;

        // Negative keywords present: reduce false positives (ex: "지도학습")
        if (negativeHit && !loc.isNegativeOnlyAffectsWeakKeywords()) {
            // Aggressive mode: negative disables all location detection.
            try {
                TraceStore.put("web.naver.planHintBoostOnly.location.negativeHit", true);
                TraceStore.put("web.naver.planHintBoostOnly.location.negativeKeyword", negativeKw);
                TraceStore.put("web.naver.planHintBoostOnly.location.negativeMode", "disable_all");
            } catch (Throwable ignore) {
                // fail-soft
            }
            return false;
        }

        boolean weakHit = false;
        boolean strongHit = false;
        java.util.List<String> hitWeak = new java.util.ArrayList<>();
        java.util.List<String> hitStrong = new java.util.ArrayList<>();

        for (String kw : safeList(loc.getKeywords())) {
            if (!hasText(kw)) {
                continue;
            }
            if (!qLower.contains(kw.toLowerCase())) {
                continue;
            }
            if (isWeakKeyword(kw, loc.getWeakKeywords())) {
                weakHit = true;
                if (hitWeak.size() < 6) {
                    hitWeak.add(kw);
                }
            } else {
                strongHit = true;
                if (hitStrong.size() < 6) {
                    hitStrong.add(kw);
                }
            }
        }

        if (negativeHit && loc.isNegativeOnlyAffectsWeakKeywords()) {
            // Default mode: ignore weak keyword hits (ex: "지도"/"map"/"어디")
            weakHit = false;
            hitWeak.clear();
        }

        // Observability for tuning (debug UX / chips)
        try {
            TraceStore.put("web.naver.planHintBoostOnly.location.negativeHit", negativeHit);
            if (negativeKw != null) {
                TraceStore.put("web.naver.planHintBoostOnly.location.negativeKeyword", negativeKw);
            }
            TraceStore.put("web.naver.planHintBoostOnly.location.weakHit", weakHit);
            TraceStore.put("web.naver.planHintBoostOnly.location.strongHit", strongHit);
            if (!hitWeak.isEmpty()) {
                TraceStore.put("web.naver.planHintBoostOnly.location.hitWeakKeywords", hitWeak);
            }
            if (!hitStrong.isEmpty()) {
                TraceStore.put("web.naver.planHintBoostOnly.location.hitStrongKeywords", hitStrong);
            }
        } catch (Throwable ignore) {
            // fail-soft
        }

        if (strongHit) {
            return true;
        }

        // Weak-only hits are frequently false positives ("어디서 찾지", "지도학습" etc.)
        // Require at least one strong-hit keyword/local-intent before blocking the
        // overlay.
        if (weakHit) {
            String promoted = matchWeakOnlyPromoteToken(query, loc);
            if (promoted != null) {
                // Observability for tuning (optional rule)
                try {
                    TraceStore.inc("web.naver.planHintBoostOnly.location.weakPromoted.count");
                    TraceStore.put("web.naver.planHintBoostOnly.location.weakPromoted.token", promoted);
                } catch (Throwable ignore) {
                    // fail-soft
                }
                return true;
            }
            try {
                TraceStore.put("web.naver.planHintBoostOnly.location.weakOnlyIgnored", true);
            } catch (Throwable ignore) {
                // fail-soft
            }
            return false;
        }

        return false;
    }

    /**
     * Optional: upgrade weak-only location keyword matches to a real location query
     * when
     * the query contains address-like tokens.
     *
     * <p>
     * Examples that benefit:
     * <ul>
     * <li>"강남역 지도" (역 + 지도)</li>
     * <li>"중구 어디" (short district + 어디)</li>
     * <li>"테헤란로 지도" (로 + 지도)</li>
     * </ul>
     */
    private String matchWeakOnlyPromoteToken(String query, NaverPlanHintBoostOnlyOverlayProperties.Location loc) {
        if (loc == null || !loc.isWeakOnlyPromoteEnabled()) {
            return null;
        }
        if (!hasText(query)) {
            return null;
        }

        // Deny keywords: reduce obvious translation/meaning false positives when suffix
        // rules get looser
        // (ex: "번역 어디" should not be treated as a location query).
        String qLower = query.toLowerCase();
        String denyHit = firstMatchLower(qLower, loc.getWeakOnlyPromoteDenyKeywords());
        if (hasText(denyHit)) {
            try {
                TraceStore.inc("web.naver.planHintBoostOnly.location.weakPromoteDenied.count");
                TraceStore.put("web.naver.planHintBoostOnly.location.weakPromoteDenied.keyword", denyHit);
            } catch (Throwable ignore) {
                // fail-soft
            }
            return null;
        }

        // Tokenize by non-letter/digit (Hangul counts as alphabetic in Unicode)
        String[] tokens = query.split("[^\\p{IsAlphabetic}\\p{IsDigit}]+");

        int minPrefixDefault = Math.max(0, loc.getWeakOnlyPromoteMinPrefixChars());
        java.util.Map<String, Integer> perSuffixMinPrefix = loc.getWeakOnlyPromoteMinPrefixCharsBySuffix();

        java.util.List<String> allow = loc.getWeakOnlyPromoteAllowTokens();
        for (String t : tokens) {
            if (!hasText(t)) {
                continue;
            }

            // Exact allow-list match (for short district tokens like "중구")
            if (containsExact(allow, t)) {
                try {
                    TraceStore.put("web.naver.planHintBoostOnly.location.weakPromoted.kind", "allowToken");
                    TraceStore.put("web.naver.planHintBoostOnly.location.weakPromoted.minPrefix.default",
                            minPrefixDefault);
                } catch (Throwable ignore) {
                    // fail-soft
                }
                return t;
            }

            for (String suffix : safeList(loc.getWeakOnlyPromoteSuffixes())) {
                if (!hasText(suffix)) {
                    continue;
                }
                if (t.length() <= suffix.length()) {
                    continue;
                }
                if (!t.endsWith(suffix)) {
                    continue;
                }

                int requiredMinPrefix = minPrefixDefault;
                if (perSuffixMinPrefix != null && !perSuffixMinPrefix.isEmpty()) {
                    Integer v = perSuffixMinPrefix.get(suffix);
                    if (v != null) {
                        requiredMinPrefix = Math.max(0, v);
                    }
                }

                String prefix = t.substring(0, t.length() - suffix.length());
                int prefixHangul = countHangulSyllables(prefix);
                if (prefixHangul < requiredMinPrefix) {
                    continue;
                }

                try {
                    TraceStore.put("web.naver.planHintBoostOnly.location.weakPromoted.kind", "suffix");
                    TraceStore.put("web.naver.planHintBoostOnly.location.weakPromoted.suffix", suffix);
                    TraceStore.put("web.naver.planHintBoostOnly.location.weakPromoted.prefixHangul", prefixHangul);
                    TraceStore.put("web.naver.planHintBoostOnly.location.weakPromoted.minPrefix.required",
                            requiredMinPrefix);
                    TraceStore.put("web.naver.planHintBoostOnly.location.weakPromoted.minPrefix.default",
                            minPrefixDefault);
                    TraceStore.put("web.naver.planHintBoostOnly.location.weakPromoted.minPrefix.usedOverride",
                            requiredMinPrefix != minPrefixDefault);
                } catch (Throwable ignore) {
                    // fail-soft
                }

                return t;
            }
        }

        return null;
    }

    private static boolean containsExact(java.util.List<String> list, String token) {
        if (!hasText(token) || list == null || list.isEmpty()) {
            return false;
        }
        for (String v : list) {
            if (!hasText(v)) {
                continue;
            }
            if (token.equals(v)) {
                return true;
            }
        }
        return false;
    }

    private static int countHangulSyllables(String s) {
        if (!hasText(s)) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Hangul syllables block: AC00-D7A3
            if (c >= '\uAC00' && c <= '\uD7A3') {
                n++;
            }
        }
        return n;
    }

    private static boolean isWeakKeyword(String kw, java.util.List<String> weakKeywords) {
        if (!hasText(kw)) {
            return false;
        }
        String k = kw.toLowerCase();
        for (String w : safeList(weakKeywords)) {
            if (!hasText(w)) {
                continue;
            }
            if (k.equals(w.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<String> safeList(java.util.List<String> in) {
        return (in == null) ? java.util.Collections.emptyList() : in;
    }

    private static boolean looksLikeAny(String query, java.util.List<String> keywords) {
        if (!hasText(query)) {
            return false;
        }
        return looksLikeAnyLower(query.toLowerCase(), keywords);
    }

    private static boolean looksLikeAnyLower(String qLower, java.util.List<String> keywords) {
        if (!hasText(qLower)) {
            return false;
        }
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) {
                continue;
            }
            if (qLower.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String firstMatchLower(String qLower, java.util.List<String> keywords) {
        if (!hasText(qLower)) {
            return null;
        }
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) {
                continue;
            }
            if (qLower.contains(kw.toLowerCase())) {
                return kw;
            }
        }
        return null;
    }

    private static GuardContext shallowCopy(GuardContext src) {
        GuardContext dst = new GuardContext();

        // Simple scalar fields
        dst.setPlanId(src.getPlanId());
        dst.setMode(src.getMode());
        dst.setEngine(src.getEngine());
        dst.setFusionScore(src.getFusionScore());
        dst.setOnnxScore(src.getOnnxScore());
        dst.setOfficialOnly(src.isOfficialOnly());
        dst.setMinCitations(src.getMinCitations());
        dst.setHighRiskQuery(src.isHighRiskQuery());
        dst.setSensitiveTopic(src.isSensitiveTopic());
        dst.setEntityQuery(src.isEntityQuery());
        dst.setMemoryProfile(src.getMemoryProfile());
        dst.setHeaderMode(src.getHeaderMode());
        dst.setGuardLevel(src.getGuardLevel());
        dst.setWebPrimary(src.getWebPrimary());
        dst.setIrregularityScore(src.getIrregularityScore());
        dst.setCompressionMode(src.isCompressionMode());
        dst.setStrikeMode(src.isStrikeMode());
        dst.setBypassMode(src.isBypassMode());
        dst.setWebRateLimited(src.isWebRateLimited());
        dst.setBypassReason(src.getBypassReason());
        dst.setUserQuery(src.getUserQuery());
        dst.setAuxDegraded(src.isAuxDegraded());
        dst.setAuxHardDown(src.isAuxHardDown());
        dst.setDomainProfile(src.getDomainProfile());

        // Plan overrides (shallow copy)
        try {
            for (Map.Entry<String, Object> e : src.getPlanOverrides().entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                dst.putPlanOverride(e.getKey(), e.getValue());
            }
        } catch (Throwable ignore) {
            // fail-soft
        }

        return dst;
    }

    private static String extractQuery(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Object a0 = args[0];
        if (a0 instanceof String s) {
            return s;
        }
        return null;
    }

    private static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String asString(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
