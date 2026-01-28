package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.search.terms.SelectedTerms;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Post-enforce a minimum MUST quota for keyword selection outputs.
 *
 * <p>
 * Why:
 * - Some keywordSelection LLM JSON outputs end up with MUST=0~1, which makes web recall unstable.
 * - Downstream strict-domain plans can starve when MUST is under-specified.
 *
 * <p>
 * What:
 * - Promote SHOULD → MUST until minMust is satisfied.
 * - As a last resort, seed MUST from exact phrases and the conversation tail.
 * - Padding is scoped to an existing anchor token (avoid plain-generic "정보"/"info" which can
 *   unintentionally generalize the user query).
 * - Records observability keys under aux.keywordSelection.forceMinMust.*
 *
 * <p>
 * Notes:
 * - In this codebase KeywordSelectionService.select(...) returns Optional&lt;SelectedTerms&gt;.
 * - This aspect mutates SelectedTerms in-place and returns the original Optional wrapper.
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
public class KeywordSelectionForceMinMustAspect {

    private static final int DEFAULT_MIN_MUST = 2;

    // Keep the list small and conservative: only ultra-generic tokens that tend to "generalize" queries.
    private static final Set<String> KO_GENERIC = Set.of(
            "정보", "설명", "방법", "가이드", "정리", "추천", "후기", "리뷰", "뉴스", "자료", "내용", "링크",
            "도움", "도움말", "도와줘"
    );

    private static final Set<String> EN_GENERIC = Set.of(
            "info", "information", "guide", "how", "what", "tips", "review", "news", "link", "docs", "documentation",
            "help", "please", "explain", "explanation"
    );

    private final Environment env;

    public KeywordSelectionForceMinMustAspect(Environment env) {
        this.env = env;
    }

    @Around("execution(* com.example.lms.search.KeywordSelectionService.select(..))")
    public Object aroundSelect(ProceedingJoinPoint pjp) throws Throwable {
        if (!enabled()) {
            return pjp.proceed();
        }

        final Object[] args0 = pjp.getArgs();
        if (args0 == null || args0.length < 3 || !(args0[0] instanceof String)) {
            return pjp.proceed();
        }
        final String conversation = (String) args0[0];
        final String domainProfile = (args0[1] instanceof String) ? (String) args0[1] : null;
        final int maxMust;
        if (args0[2] instanceof Number n) {
            maxMust = n.intValue();
        } else {
            return pjp.proceed();
        }

        int minMust = env.getProperty("nova.orch.keyword-selection.force-min-must.minMust", Integer.class, DEFAULT_MIN_MUST);
        minMust = Math.max(0, minMust);
        if (minMust <= 0) {
            return pjp.proceed();
        }

        final boolean ko = containsHangul(conversation) || containsHangul(domainProfile);

        // If callers pass maxMust<minMust, the core service normalizer can truncate MUST too aggressively.
        int effectiveMaxMust = Math.max(Math.max(DEFAULT_MIN_MUST, minMust), maxMust);

        Object ret;
        if (effectiveMaxMust != maxMust) {
            // SoT snapshot: clone args once, then proceed(args) exactly once.
            final Object[] args = args0.clone();
            args[2] = effectiveMaxMust;
            ret = pjp.proceed(args);
            try {
                TraceStore.put("aux.keywordSelection.forceMinMust.effectiveMax", effectiveMaxMust);
                TraceStore.put("aux.keywordSelection.forceMinMust.effectiveMax.reason", "maxMust_arg_lt_minMust");
            } catch (Throwable ignore) {
                // best-effort
            }
        } else {
            ret = pjp.proceed();
        }

        Object rawRet = ret;

        SelectedTerms terms = unwrapSelectedTerms(ret);
        boolean seededOnEmptyOptional = false;
        boolean seedOnEmptyOptional = env.getProperty(
                "nova.orch.keyword-selection.force-min-must.seed-on-empty-optional",
                Boolean.class,
                true);

        // When KeywordSelection returns Optional.empty (e.g., parse failure / breaker-open / blank),
        // provide a deterministic seed so downstream query planning is not starved.
        if (terms == null && seedOnEmptyOptional && (rawRet instanceof Optional<?> opt) && opt.isEmpty()) {
            terms = SelectedTerms.builder()
                    .must(new ArrayList<>())
                    .should(new ArrayList<>())
                    .exact(new ArrayList<>())
                    .negative(new ArrayList<>())
                    .build();
            ret = Optional.of(terms);
            seededOnEmptyOptional = true;
            try {
                TraceStore.put("aux.keywordSelection.forceMinMust.seedOnEmptyOptional_used", true);
            } catch (Throwable ignore) {
                // best-effort
            }
        }

        if (terms == null) {
            try {
                TraceStore.put("aux.keywordSelection.forceMinMust.seedOnEmptyOptional_skipped", true);
            } catch (Throwable ignore) {
                // best-effort
            }
            return rawRet;
        }

        int beforeMust = countNonBlank(terms.getMust());

        // Observability: always emit forceMinMust keys so "already satisfied" cases are visible.
        try {
            TraceStore.put("aux.keywordSelection.forceMinMust.minMust", minMust);
            TraceStore.put("aux.keywordSelection.forceMinMust.before", beforeMust);
            TraceStore.put("aux.keywordSelection.forceMinMust.after", beforeMust);
            TraceStore.put("aux.keywordSelection.forceMinMust.applied", false);
            TraceStore.put("aux.keywordSelection.forceMinMust.reason", beforeMust >= minMust ? "alreadySatisfied" : "needsEnforce");
            TraceStore.put("aux.keywordSelection.forceMinMust.promoted", 0);
            TraceStore.put("aux.keywordSelection.forceMinMust.seeded", 0);
            TraceStore.put("aux.keywordSelection.forceMinMust.padded", 0);
            TraceStore.put("aux.keywordSelection.forceMinMust.paddingStrategy", "none");
        } catch (Throwable ignore) {
            // best-effort
        }

        if (beforeMust >= minMust) {
            return ret;
        }

        // Promote SHOULD to MUST.
        List<String> must = terms.getMust();
        List<String> should = terms.getShould();
        List<String> exact = terms.getExact();

        LinkedHashSet<String> mustSet = new LinkedHashSet<>();
        if (must != null) {
            for (String m : must) {
                String t = normalizeToken(m);
                if (t != null) {
                    mustSet.add(t);
                }
            }
        }

        int promoted = 0;
        List<String> newShould = new ArrayList<>();
        if (should != null) {
            for (String s : should) {
                String t = normalizeToken(s);
                if (t == null) {
                    continue;
                }
                // Avoid promoting ultra-generic tokens into MUST (can "generalize" the final query string).
                if (mustSet.size() < minMust && !mustSet.contains(t) && !isGenericToken(t, ko)) {
                    mustSet.add(t);
                    promoted++;
                } else {
                    newShould.add(t);
                }
            }
        }

        // Seed from exact phrases first (usually specific entities/titles).
        int seeded = 0;
        if (mustSet.size() < minMust && exact != null) {
            for (String e : exact) {
                if (mustSet.size() >= minMust) {
                    break;
                }
                String t = normalizeToken(e);
                if (t == null || isGenericToken(t, ko) || mustSet.contains(t)) {
                    continue;
                }
                mustSet.add(t);
                seeded++;
            }
        }

        // Seed from conversation tail (last resort).
        if (mustSet.size() < minMust) {
            String seedQuery = extractSeedQuery(conversation);
            if (seedQuery != null && !seedQuery.isBlank()) {
                for (String seed : splitSeeds(seedQuery, ko)) {
                    if (mustSet.size() >= minMust) {
                        break;
                    }
                    String t = normalizeToken(seed);
                    if (t != null && !mustSet.contains(t) && !isGenericToken(t, ko)) {
                        mustSet.add(t);
                        seeded++;
                    }
                }
            }
        }

        // Scoped padding: do NOT add a plain generic word ("정보"/"info").
        // Instead, build a phrase anchored to an existing specific token.
        int padded = 0;
        String paddingStrategy = "none";
        if (mustSet.size() < minMust) {
            String anchor = findAnchor(mustSet, exact, should, conversation, ko);
            List<String> padCandidates = derivePaddingCandidates(anchor, domainProfile, ko);
            for (String cand : padCandidates) {
                if (mustSet.size() >= minMust) {
                    break;
                }
                String t = normalizeToken(cand);
                if (t == null || mustSet.contains(t)) {
                    continue;
                }
                mustSet.add(t);
                padded++;
                paddingStrategy = (anchor != null && !anchor.isBlank()) ? "anchorScoped" : "domainHint";
            }
        }

        List<String> newMust = new ArrayList<>(mustSet);

        // Keep effectiveMaxMust cap, but never below minMust.
        if (newMust.size() > effectiveMaxMust) {
            newMust = newMust.subList(0, effectiveMaxMust);
        }

        int afterMust = countNonBlank(newMust);

        // If we seeded an empty Optional but still couldn't satisfy minMust, fall back to the original result
        // so the caller can use its own deterministic extractor.
        if (seededOnEmptyOptional && afterMust < minMust) {
            try {
                TraceStore.put("aux.keywordSelection.forceMinMust.seedOnEmptyOptional_failed", true);
            } catch (Throwable ignore) {
                // best-effort
            }
            return rawRet;
        }
        if (afterMust >= minMust && afterMust != beforeMust) {
            terms.setMust(newMust);
            terms.setShould(newShould);
            try {
                TraceStore.put("aux.keywordSelection.forceMinMust.applied", true);
                TraceStore.put("aux.keywordSelection.forceMinMust.reason", "aspect_post_enforce");
                TraceStore.put("aux.keywordSelection.forceMinMust.minMust", minMust);
                TraceStore.put("aux.keywordSelection.forceMinMust.before", beforeMust);
                TraceStore.put("aux.keywordSelection.forceMinMust.after", afterMust);
                TraceStore.put("aux.keywordSelection.forceMinMust.promoted", promoted);
                TraceStore.put("aux.keywordSelection.forceMinMust.seeded", seeded);
                TraceStore.put("aux.keywordSelection.forceMinMust.padded", padded);
                TraceStore.put("aux.keywordSelection.forceMinMust.paddingStrategy", paddingStrategy);
                TraceStore.inc("aux.keywordSelection.forceMinMust.count");
            } catch (Throwable ignore) {
                // best-effort
            }
        }

        return ret;
    }

    private boolean enabled() {
        return env.getProperty("nova.orch.keyword-selection.force-min-must.enabled", Boolean.class, true);
    }

    private static SelectedTerms unwrapSelectedTerms(Object ret) {
        if (ret instanceof SelectedTerms st) {
            return st;
        }
        if (ret instanceof Optional<?> opt) {
            Object v = opt.orElse(null);
            if (v instanceof SelectedTerms st) {
                return st;
            }
        }
        return null;
    }

    private static int countNonBlank(List<String> list) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int c = 0;
        for (String s : list) {
            if (s != null && !s.isBlank()) {
                c++;
            }
        }
        return c;
    }

    private static String normalizeToken(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isBlank()) {
            return null;
        }
        // Avoid adding full sentences.
        if (t.length() > 80) {
            t = t.substring(0, 80).trim();
        }
        return t.isBlank() ? null : t;
    }

    private static boolean isGenericToken(String token, boolean ko) {
        if (token == null) {
            return true;
        }
        String t = token.trim();
        if (t.isBlank()) {
            return true;
        }
        String l = t.toLowerCase(Locale.ROOT);
        if (KO_GENERIC.contains(t) || KO_GENERIC.contains(l)) {
            return true;
        }
        if (EN_GENERIC.contains(l)) {
            return true;
        }
        // Very short tokens are often noise (but length<2 is already filtered elsewhere).
        if (t.length() < 2) {
            return true;
        }
        // Allow numeric tokens (e.g., version numbers) – they are not "generic".
        return false;
    }

    private static String extractSeedQuery(String conversation) {
        if (conversation == null) {
            return null;
        }
        String[] lines = conversation.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            if (line == null) {
                continue;
            }
            String t = line.trim();
            if (t.isBlank()) {
                continue;
            }
            // Common prefixes in logs/prompts.
            t = t.replaceFirst("(?i)^(user|사용자|question|q)\\s*[:：]\\s*", "");
            t = t.replaceFirst("(?i)^(assistant|system)\\s*[:：]\\s*", "");
            t = t.trim();
            if (!t.isBlank()) {
                return t;
            }
        }
        String fallback = conversation.trim();
        return fallback.isBlank() ? null : fallback;
    }

    private static List<String> splitSeeds(String q, boolean ko) {
        if (q == null) {
            return List.of();
        }
        String cleaned = q.replaceAll("[\\p{Punct}]+", " ");
        String[] parts = cleaned.split("\\s+");

        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String t = p.trim();
            if (t.isBlank()) {
                continue;
            }
            // Drop very short tokens (often noise).
            if (t.length() < 2) {
                continue;
            }
            if (isGenericToken(t, ko)) {
                continue;
            }
            out.add(t);
            if (out.size() >= 4) {
                break;
            }
        }

        if (out.isEmpty()) {
            String t = q.trim();
            if (!t.isBlank() && !isGenericToken(t, ko)) {
                out.add(t);
            }
        }
        return out;
    }

    private static String findAnchor(LinkedHashSet<String> mustSet,
                                    List<String> exact,
                                    List<String> should,
                                    String conversation,
                                    boolean ko) {
        if (mustSet != null && !mustSet.isEmpty()) {
            return mustSet.iterator().next();
        }
        if (exact != null) {
            for (String e : exact) {
                String t = normalizeToken(e);
                if (t != null && !isGenericToken(t, ko)) {
                    return t;
                }
            }
        }
        if (should != null) {
            for (String s : should) {
                String t = normalizeToken(s);
                if (t != null && !isGenericToken(t, ko)) {
                    return t;
                }
            }
        }
        String seedQuery = extractSeedQuery(conversation);
        if (seedQuery != null) {
            List<String> seeds = splitSeeds(seedQuery, ko);
            if (!seeds.isEmpty()) {
                return normalizeToken(seeds.get(0));
            }
        }
        return null;
    }

    private static List<String> derivePaddingCandidates(String anchor, String domainProfile, boolean ko) {
        List<String> out = new ArrayList<>();

        String hint = domainHintToken(domainProfile, ko);

        if (anchor != null && !anchor.isBlank()) {
            String a = anchor.trim();

            // Scope padding to an existing specific token to avoid turning the query into a generic "정보/guide" query.
            if (ko) {
                out.add(a + " 공식");
                out.add(a + " 문서");
                out.add(a + " 사이트");
            } else {
                out.add(a + " official");
                out.add(a + " documentation");
                out.add(a + " site");
            }

            if (hint != null && !hint.isBlank() && !a.toLowerCase(Locale.ROOT).contains(hint.toLowerCase(Locale.ROOT))) {
                out.add(a + " " + hint);
            }

            return out;
        }

        // No anchor found → fall back to a domain hint (still better than a plain "정보/info").
        if (hint != null && !hint.isBlank()) {
            String h = hint.trim();
            out.add(h);
            if (ko) {
                // Avoid duplicates when hint itself is already "공식".
                out.add(h.contains("공식") ? "문서" : "공식");
                out.add(h.contains("공식") ? "사이트" : "공식 문서");
            } else {
                String hl = h.toLowerCase(Locale.ROOT);
                out.add(hl.contains("official") ? "official documentation" : "official");
                out.add(hl.contains("official") ? "official site" : "official documentation");
            }
            return out;
        }

        // No hint either → provide deterministic padding that can satisfy minMust>=2.
        if (ko) {
            out.add("공식");
            out.add("공식 문서");
            out.add("공식 사이트");
        } else {
            out.add("official");
            out.add("official documentation");
            out.add("official site");
        }

        return out;
    }

    private static String domainHintToken(String domainProfile, boolean ko) {
        if (domainProfile == null || domainProfile.isBlank()) {
            return "";
        }
        String d = domainProfile.trim().toUpperCase(Locale.ROOT);
        if (d.contains("OFFICIAL")) {
            return ko ? "공식" : "official";
        }
        if (d.contains("NEWS")) {
            return ko ? "뉴스" : "news";
        }
        if (d.contains("TECH")) {
            return ko ? "기술" : "tech";
        }
        return "";
    }

    private static boolean containsHangul(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                return true;
            }
        }
        return false;
    }
}
