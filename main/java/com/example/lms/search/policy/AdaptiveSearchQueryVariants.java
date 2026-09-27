package com.example.lms.search.policy;

import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-safe query variant planner.
 *
 * <p>No network, no LLM, and no raw query logging. Providers use the returned
 * counts/reason for redacted telemetry only.
 */
public final class AdaptiveSearchQueryVariants {

    public enum Provider {
        NAVER,
        BRAVE
    }

    public record Options(
            Provider provider,
            boolean enabled,
            int maxQueries,
            long remainingBudgetMs,
            long maxOverallTimeoutMs,
            long perCallFloorMs,
            boolean recallMode,
            boolean providerEmpty,
            boolean afterFilterStarved) {
    }

    public record Plan(
            List<String> queries,
            String triggerReason,
            int sliceCount,
            int expansionCount,
            long budgetMs,
            long perCallMs,
            boolean enabled,
            double validationTemperature,
            double explorationTemperature,
            double explorationRate,
            String temperatureProfile) {

        public List<String> variants() {
            if (queries == null || queries.size() <= 1) {
                return List.of();
            }
            return List.copyOf(queries.subList(1, queries.size()));
        }

        public Diagnostics diagnostics() {
            return Diagnostics.from(queries);
        }

        public List<VariantLaneDiagnostic> variantLaneDiagnostics() {
            List<String> safeQueries = queries == null ? List.of() : queries.stream()
                    .map(AdaptiveSearchQueryVariants::compact)
                    .filter(value -> !value.isBlank())
                    .toList();
            if (safeQueries.size() <= 1) {
                return List.of();
            }
            List<VariantLaneDiagnostic> out = new ArrayList<>();
            for (int i = 1; i < safeQueries.size(); i++) {
                String query = safeQueries.get(i);
                String laneLabel = Diagnostics.laneLabel(query);
                String role = laneRole(laneLabel);
                double temperature = "exploration".equals(role) ? explorationTemperature : validationTemperature;
                out.add(new VariantLaneDiagnostic(
                        i,
                        i - 1,
                        laneLabel,
                        role,
                        temperature,
                        SafeRedactor.hash12(query)));
            }
            return List.copyOf(out);
        }

        public List<String> variantLaneTemperatureHints() {
            return variantLaneDiagnostics().stream()
                    .map(VariantLaneDiagnostic::temperatureHint)
                    .toList();
        }
    }

    public record VariantLaneDiagnostic(
            int queryIndex,
            int variantIndex,
            String laneLabel,
            String role,
            double temperature,
            String queryHash12) {

        public String temperatureHint() {
            return variantIndex + ":" + laneLabel + "@" + temperature + "#" + queryHash12;
        }
    }

    public record Diagnostics(
            int queryCount,
            int variantCount,
            String querySeedHash12,
            String variantSetHash12,
            int verificationLaneCount,
            int explorationLaneCount,
            List<String> laneLabels) {

        public String laneSummary() {
            return laneLabels == null || laneLabels.isEmpty() ? "" : String.join("|", laneLabels);
        }

        private static Diagnostics from(List<String> queries) {
            List<String> safeQueries = queries == null ? List.of() : queries.stream()
                    .map(AdaptiveSearchQueryVariants::compact)
                    .filter(value -> !value.isBlank())
                    .toList();
            List<String> variants = safeQueries.size() <= 1 ? List.of() : safeQueries.subList(1, safeQueries.size());
            List<String> labels = variants.stream()
                    .map(Diagnostics::laneLabel)
                    .toList();
            long verification = labels.stream().filter(label -> label.startsWith("verification:")).count();
            long exploration = labels.stream().filter(label -> label.startsWith("exploration:")).count();
            return new Diagnostics(
                    safeQueries.size(),
                    variants.size(),
                    SafeRedactor.hash12(safeQueries.isEmpty() ? "" : safeQueries.get(0)),
                    SafeRedactor.hash12(String.join("\u001f", variants)),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, verification)),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, exploration)),
                    List.copyOf(labels));
        }

        private static String laneLabel(String query) {
            String q = key(query);
            if (q.endsWith(GrokPromotionDiscovery.DISCOVERY_SUFFIX)) {
                return "exploration:account_promotion";
            }
            if (q.endsWith(GrokPromotionDiscovery.VERIFICATION_SUFFIX)) {
                return "verification:promotion_terms";
            }
            if (q.endsWith(" implementation examples")) {
                return "verification:implementation_examples";
            }
            if (q.endsWith(" official source")) {
                return "verification:official_source";
            }
            if (q.endsWith(" latest update")) {
                return "exploration:latest_update";
            }
            if (q.endsWith(" counterexample")) {
                return "exploration:counterexample";
            }
            if (q.endsWith(" failure modes")) {
                return "exploration:failure_modes";
            }
            if (q.endsWith(" changelog")) {
                return "exploration:changelog";
            }
            if (q.endsWith(" reference docs")) {
                return "exploration:reference_docs";
            }
            if (q.endsWith(" docs") || q.endsWith(" documentation") || q.endsWith(" release notes")
                    || q.endsWith(" spec")) {
                return "verification:authority_reference";
            }
            if (q.endsWith(" guide") || q.endsWith(" usage examples") || q.endsWith(" tutorial")
                    || q.endsWith(" examples")) {
                return "exploration:examples";
            }
            return "other:derived";
        }
    }

    private AdaptiveSearchQueryVariants() {
    }

    public static Plan plan(String originalQuery, List<String> baseCandidates, Options options) {
        Options o = options == null
                ? new Options(Provider.NAVER, false, 1, 0L, 0L, 0L, false, false, false)
                : options;
        int providerCap = o.provider() == Provider.BRAVE ? 3 : 9;
        int maxQueries = clamp(o.maxQueries(), 1, providerCap);
        long maxOverall = Math.max(0L, o.maxOverallTimeoutMs());
        long remaining = o.remainingBudgetMs() > 0 ? o.remainingBudgetMs() : maxOverall;
        long budgetMs = maxOverall > 0 ? Math.min(remaining, maxOverall) : Math.max(0L, remaining);
        long floorMs = Math.max(1L, o.perCallFloorMs());

        String original = compact(originalQuery);
        boolean promotionDiscovery = GrokPromotionDiscovery.matches(original);
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (o.enabled() && promotionDiscovery) {
            // Keep plan/platform/account constraints even when rewrites fill the cap.
            add(out, original);
        }
        addAll(out, baseCandidates);
        add(out, original);

        if (out.isEmpty()) {
            return plan(List.of(), "blank", 0, 0, budgetMs, floorMs, false, SearchPolicyMode.OFF);
        }
        if (!o.enabled()) {
            return plan(List.copyOf(out.values()), "disabled", 0, 0, budgetMs, floorMs, false, SearchPolicyMode.OFF);
        }
        if (maxQueries <= 1 || budgetMs < floorMs * 2) {
            return plan(capped(out, 1), "budget", 0, 0, budgetMs, floorMs, true, SearchPolicyMode.PRECISION);
        }

        if (promotionDiscovery) {
            // A successful list-price result is not exhaustive account-offer evidence.
            // Reserve bounded discovery and verification slots before generic slices.
            int affordableQueries = (int) Math.min(maxQueries, budgetMs / floorMs);
            LinkedHashMap<String, String> promotion = new LinkedHashMap<>();
            add(promotion, original);
            add(promotion, original + GrokPromotionDiscovery.DISCOVERY_SUFFIX);
            if (affordableQueries >= 3) {
                add(promotion, original + GrokPromotionDiscovery.VERIFICATION_SUFFIX);
            }
            List<String> queries = capped(promotion, affordableQueries);
            String reason = "promotion-discovery:" + triggerReason(o, false);
            return plan(queries, reason, 0, queries.size() - 1, budgetMs,
                    budgetMs / queries.size(), true, SearchPolicyMode.BALANCED);
        }

        List<String> slices = QuerySlicer.slice(original, 2, 1, Math.max(1, maxQueries - out.size()));
        boolean compound = isCompound(original, slices);
        String trigger = triggerReason(o, compound);
        SearchPolicyMode mode = o.providerEmpty() || o.afterFilterStarved() || o.recallMode()
                ? SearchPolicyMode.RECALL
                : SearchPolicyMode.BALANCED;
        if ("base-only".equals(trigger)) {
            return plan(capped(out, maxQueries), trigger, 0, 0, budgetMs, floorMs, true, SearchPolicyMode.BALANCED);
        }

        int expansionCount = 0;
        int beforeReservedLanes = out.size();
        if ((mode == SearchPolicyMode.RECALL || "compound-query".equals(trigger)) && out.size() < maxQueries) {
            for (String laneVariant : deterministicLaneVariants(original, mode)) {
                add(out, laneVariant);
                if (out.size() >= maxQueries) {
                    break;
                }
            }
        }
        expansionCount += Math.max(0, out.size() - beforeReservedLanes);

        int beforeSlices = out.size();
        for (String slice : slices) {
            addLooselyDistinct(out, slice);
            if (out.size() >= maxQueries) {
                break;
            }
        }
        int sliceCount = Math.max(0, out.size() - beforeSlices);

        if (out.size() < maxQueries) {
            int beforeMoreExpansions = out.size();
            if (mode != SearchPolicyMode.RECALL) {
                for (String laneVariant : deterministicLaneVariants(original, mode)) {
                    add(out, laneVariant);
                    if (out.size() >= maxQueries) {
                        break;
                    }
                }
            }
            List<String> expansions = StochasticExpander.expand(original, mode, maxQueries - out.size());
            for (String expansion : expansions) {
                addLooselyDistinct(out, expansion);
                if (out.size() >= maxQueries) {
                    break;
                }
            }
            expansionCount += Math.max(0, out.size() - beforeMoreExpansions);
        }
        int queryCount = Math.max(1, Math.min(maxQueries, out.size()));
        long perCallMs = Math.max(floorMs, budgetMs / Math.max(1, queryCount));

        return plan(capped(out, maxQueries), trigger, sliceCount, expansionCount, budgetMs, perCallMs, true, mode);
    }

    private static List<String> deterministicLaneVariants(String query, SearchPolicyMode mode) {
        String q = compact(query);
        if (q.isBlank() || mode == SearchPolicyMode.OFF) {
            return List.of();
        }
        List<String> conservative = List.of(
                q + " official source",
                q + " latest update",
                q + " counterexample");
        if (mode != SearchPolicyMode.RECALL) {
            return conservative;
        }
        List<String> exploration = seedRotatedRecallExplorationLanes(q);
        List<String> recall = new ArrayList<>(2 + exploration.size());
        recall.add(q + " official source");
        if (!exploration.isEmpty()) {
            recall.add(exploration.get(0));
        }
        recall.add(q + " implementation examples");
        for (int i = 1; i < exploration.size(); i++) {
            recall.add(exploration.get(i));
        }
        return List.copyOf(recall);
    }

    private static List<String> seedRotatedRecallExplorationLanes(String query) {
        List<String> lanes = List.of(
                query + " latest update",
                query + " counterexample",
                query + " failure modes",
                query + " changelog",
                query + " reference docs");
        if (lanes.size() <= 1) {
            return lanes;
        }
        int offset = Math.floorMod(("RECALL|" + key(query)).hashCode(), lanes.size());
        List<String> rotated = new ArrayList<>(lanes.size());
        for (int i = 0; i < lanes.size(); i++) {
            rotated.add(lanes.get((offset + i) % lanes.size()));
        }
        if (hasRecencyOrChangelogIntent(query)) {
            return pinFirst(rotated, query + " changelog");
        }
        return List.copyOf(rotated);
    }

    private static boolean hasRecencyOrChangelogIntent(String query) {
        String q = key(query);
        if (q.isBlank()) {
            return false;
        }
        return containsAny(q,
                "changelog",
                "change log",
                "release note",
                "release notes",
                "latest",
                "recent",
                "최신",
                "최근",
                "업데이트",
                "변경",
                "릴리즈")
                || q.matches(".*(?:^|\\D)20\\d{2}(?:\\D|$).*");
    }

    private static List<String> pinFirst(List<String> values, String preferred) {
        String preferredKey = key(preferred);
        if (preferredKey.isBlank()) {
            return List.copyOf(values);
        }
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            if (preferredKey.equals(key(value))) {
                out.add(value);
                break;
            }
        }
        if (out.isEmpty()) {
            return List.copyOf(values);
        }
        for (String value : values) {
            if (!preferredKey.equals(key(value))) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static Plan plan(List<String> queries,
                             String triggerReason,
                             int sliceCount,
                             int expansionCount,
                             long budgetMs,
                             long perCallMs,
                             boolean enabled,
                             SearchPolicyMode mode) {
        RewriteTemperatureProfile profile = rewriteTemperatureProfile(mode, enabled);
        return new Plan(
                queries,
                triggerReason,
                sliceCount,
                expansionCount,
                budgetMs,
                perCallMs,
                enabled,
                profile.validationTemperature(),
                profile.explorationTemperature(),
                profile.explorationRate(),
                profile.label());
    }

    private record RewriteTemperatureProfile(
            String label,
            double validationTemperature,
            double explorationTemperature,
            double explorationRate) {
    }

    private static RewriteTemperatureProfile rewriteTemperatureProfile(SearchPolicyMode mode, boolean enabled) {
        if (!enabled || mode == null || mode == SearchPolicyMode.OFF) {
            return new RewriteTemperatureProfile("disabled", 0.0d, 0.0d, 0.0d);
        }
        return switch (mode) {
            case PRECISION -> new RewriteTemperatureProfile("conservative", 0.12d, 0.22d, 0.08d);
            case BALANCED -> new RewriteTemperatureProfile("balanced", 0.18d, 0.42d, 0.18d);
            case RECALL -> new RewriteTemperatureProfile("exploratory", 0.22d, 0.68d, 0.30d);
            case DISAMBIGUATE -> new RewriteTemperatureProfile("disambiguate", 0.16d, 0.38d, 0.14d);
            case OFF -> new RewriteTemperatureProfile("disabled", 0.0d, 0.0d, 0.0d);
        };
    }

    private static String triggerReason(Options o, boolean compound) {
        if (o.afterFilterStarved()) {
            return "after-filter-starvation";
        }
        if (o.providerEmpty()) {
            return "provider-empty";
        }
        if (o.recallMode()) {
            return "recall-policy";
        }
        if (compound) {
            return "compound-query";
        }
        return "base-only";
    }

    private static boolean isCompound(String query, List<String> slices) {
        String q = compact(query);
        if (!slices.isEmpty()) {
            return true;
        }
        if (q.length() >= 96) {
            return true;
        }
        String[] tokens = q.isBlank() ? new String[0] : q.split("\\s+");
        return tokens.length >= 13;
    }

    private static void addAll(LinkedHashMap<String, String> out, List<String> candidates) {
        if (candidates == null) {
            return;
        }
        for (String candidate : candidates) {
            add(out, candidate);
        }
    }

    private static void add(LinkedHashMap<String, String> out, String candidate) {
        String s = compact(candidate);
        if (s.isBlank()) {
            return;
        }
        String key = key(s);
        if (!key.isBlank()) {
            out.putIfAbsent(key, s);
        }
    }

    private static void addLooselyDistinct(LinkedHashMap<String, String> out, String candidate) {
        String s = compact(candidate);
        if (s.isBlank()) {
            return;
        }
        Set<String> candidateTokens = tokenSet(s);
        for (String existing : out.values()) {
            if (jaccard(candidateTokens, tokenSet(existing)) >= 0.94d) {
                return;
            }
        }
        add(out, s);
    }

    private static List<String> capped(LinkedHashMap<String, String> out, int maxQueries) {
        List<String> values = new ArrayList<>(out.values());
        if (values.size() <= maxQueries) {
            return List.copyOf(values);
        }
        return List.copyOf(values.subList(0, maxQueries));
    }

    private static String compact(String value) {
        return Objects.toString(value, "").replaceAll("\\p{Cntrl}", " ").replaceAll("\\s{2,}", " ").trim();
    }

    private static String key(String value) {
        return compact(value).toLowerCase(Locale.ROOT);
    }

    private static String laneRole(String laneLabel) {
        String safe = SafeRedactor.traceLabelOrFallback(laneLabel, "other");
        int colon = safe.indexOf(':');
        return colon > 0 ? safe.substring(0, colon) : safe;
    }

    private static Set<String> tokenSet(String value) {
        String compact = key(value);
        if (compact.isBlank()) {
            return Set.of();
        }
        return new java.util.LinkedHashSet<>(List.of(compact.split("\\s+")));
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }
        int intersection = 0;
        for (String token : a) {
            if (b.contains(token)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union <= 0 ? 0.0d : (double) intersection / (double) union;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
