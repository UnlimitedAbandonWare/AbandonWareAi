package com.example.lms.transform;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

public final class QueryTransformerSubQueryFallback {

    private static final List<BranchSpec> SUPER_BRANCHES = List.of(
            new BranchSpec("definition", "core-focus", "definition scope constraints"),
            new BranchSpec("alias", "alias-map", "alias variant path spelling"),
            new BranchSpec("relation", "failure-path", "relation failure hypothesis counterexample"));

    private static final List<Pattern> PRUNE_PATTERNS = List.of(
            Pattern.compile("(?i)\\bignore\\s+previous\\s+instructions\\b"),
            Pattern.compile("(?i)\\bownerToken\\s*=\\s*\\S+"),
            Pattern.compile("(?i)\\b(api[_-]?key|authorization|cookie|client[_-]?secret)\\s*[:=]\\s*\\S+"),
            Pattern.compile("(?i)\\bsb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}\\b"));

    private QueryTransformerSubQueryFallback() {
    }

    public static List<String> threeAxisFallback(String question, String reason, int max) {
        PrunedSeed seed = pruneSeed(question);
        String q = seed.text();
        if (q.isEmpty() || max <= 0) {
            traceFallback(q, reason, List.of(), seed.prunedTokenCount());
            return List.of();
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (BranchSpec branch : SUPER_BRANCHES) {
            out.add(appendUniqueToken(q + " " + branch.suffix(), branch.superToken()));
        }

        List<String> limited = out.stream()
                .limit(Math.max(1, Math.min(3, max)))
                .toList();
        traceFallback(q, reason, limited, seed.prunedTokenCount());
        return limited;
    }

    static List<String> refineParsedSubQueries(List<String> candidates, String question, String reason, int max) {
        if (candidates == null || candidates.isEmpty() || max <= 0) {
            traceRefined(question, reason, List.of(), 0, 0, 0);
            return List.of();
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        int pruned = 0;
        int duplicatePruned = 0;
        for (String candidate : candidates) {
            PrunedSeed seed = pruneSeed(candidate);
            pruned += seed.prunedTokenCount();
            String lane = seed.text();
            if (lane.isBlank()) {
                continue;
            }
            if (out.contains(lane)) {
                duplicatePruned++;
                continue;
            }
            if (out.size() < max) {
                out.add(lane);
            }
        }

        List<String> refined = new ArrayList<>(out);
        int padded = padMissingBranches(refined, question, max);
        traceRefined(question, reason, refined, pruned, duplicatePruned, padded);
        return refined;
    }

    private static PrunedSeed pruneSeed(String question) {
        String out = question == null ? "" : question.replaceAll("[\\r\\n]+", " ").trim();
        int pruned = 0;
        for (Pattern pattern : PRUNE_PATTERNS) {
            String before = out;
            out = pattern.matcher(out).replaceAll(" ");
            if (!before.equals(out)) {
                pruned++;
            }
        }
        out = out.replaceAll("\\s{2,}", " ").trim();
        return new PrunedSeed(out, pruned);
    }

    private static String appendUniqueToken(String text, String token) {
        String base = text == null ? "" : text.trim();
        String safeToken = SafeRedactor.traceLabelOrFallback(token, "branch-token");
        if (base.isBlank()) {
            return safeToken;
        }
        if (containsBranchToken(base, safeToken)) {
            return base;
        }
        return base + " " + safeToken;
    }

    private static void traceFallback(String query, String reason, List<String> generated, int prunedTokenCount) {
        try {
            int count = safeSize(generated);
            TraceStore.put("queryTransformer.subQueries.fallback", true);
            TraceStore.put("queryTransformer.subQueries.fallback.reason",
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            TraceStore.put("queryTransformer.subQueries.fallback.count", count);
            TraceStore.put("queryTransformer.subQueries.fallback.axes",
                    List.of("definition", "alias", "relation"));
            TraceStore.put("queryTransformer.subQueries.fallback.pruned", prunedTokenCount > 0);
            TraceStore.put("queryTransformer.subQueries.fallback.prunedTokenCount", prunedTokenCount);
            TraceStore.put("queryTransformer.subQueries.fallback.targetReached", count >= 3);
            TraceStore.put("queryTransformer.subQueries.fallback.queryHash12", SafeRedactor.hash12(query));
            TraceStore.put("queryTransformer.subQueries.fallback.queryLength", query == null ? 0 : query.length());
            traceSuperTokens(query, reason, count, generated);
            traceCoverage("fallback", generated);
        } catch (Throwable ignore) {
            traceSuppressed("fallbackTrace", ignore);
        }
    }

    private static int padMissingBranches(List<String> refined, String question, int max) {
        if (refined == null || max <= 0 || refined.size() >= max) {
            return 0;
        }
        PrunedSeed seed = pruneSeed(question);
        String q = seed.text();
        if (q.isBlank()) {
            return 0;
        }

        int padded = 0;
        for (BranchSpec branch : SUPER_BRANCHES) {
            if (refined.size() >= max) {
                break;
            }
            if (branchAlreadyCovered(refined, branch)) {
                continue;
            }
            String candidate = appendUniqueToken(q + " " + branch.suffix(), branch.superToken());
            if (candidate.isBlank() || refined.contains(candidate)) {
                continue;
            }
            refined.add(candidate);
            padded++;
        }
        return padded;
    }

    private static boolean branchAlreadyCovered(List<String> refined, BranchSpec branch) {
        return branchCoveredByAnyQuery(refined, branch);
    }

    private static boolean branchCoveredByAnyQuery(List<String> queries, BranchSpec branch) {
        if (queries == null || queries.isEmpty() || branch == null) {
            return false;
        }
        for (String query : queries) {
            if (branchCoveredByQuery(query, branch)) {
                return true;
            }
        }
        return false;
    }

    private static boolean branchCoveredByQuery(String query, BranchSpec branch) {
        return containsBranchToken(query, branch.axis()) || containsBranchToken(query, branch.superToken());
    }

    private static boolean containsBranchToken(String query, String token) {
        if (query == null || query.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        for (String variant : tokenVariants(token)) {
            if (matchesBranchToken(query, variant)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesBranchToken(String query, String token) {
        return Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(token) + "(?![A-Za-z0-9_])")
                .matcher(query)
                .find();
    }

    private static List<String> tokenVariants(String token) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.isBlank()) {
            return List.of();
        }
        variants.add(trimmed);
        if (trimmed.contains("-")) {
            variants.add(trimmed.replace('-', ' '));
        }
        if (trimmed.contains(" ")) {
            variants.add(trimmed.replace(' ', '-'));
        }
        return List.copyOf(variants);
    }

    private static void traceRefined(
            String query,
            String reason,
            List<String> refinedQueries,
            int prunedTokenCount,
            int duplicatePrunedCount,
            int paddedCount) {
        try {
            int count = safeSize(refinedQueries);
            TraceStore.put("queryTransformer.subQueries.refined", true);
            TraceStore.put("queryTransformer.subQueries.refined.reason",
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            TraceStore.put("queryTransformer.subQueries.refined.count", count);
            TraceStore.put("queryTransformer.subQueries.refined.pruned", prunedTokenCount > 0);
            TraceStore.put("queryTransformer.subQueries.refined.prunedTokenCount", prunedTokenCount);
            TraceStore.put("queryTransformer.subQueries.refined.duplicatePrunedCount", duplicatePrunedCount);
            TraceStore.put("queryTransformer.subQueries.refined.paddedCount", Math.max(0, paddedCount));
            TraceStore.put("queryTransformer.subQueries.refined.queryHash12", SafeRedactor.hash12(query));
            TraceStore.put("queryTransformer.subQueries.refined.queryLength", query == null ? 0 : query.length());
            TraceStore.put("queryTransformer.subQueries.convergence.targetReached", count >= 3);
            TraceStore.put("queryTransformer.subQueries.convergence.rounds", count >= 3 ? 2 : 1);
            traceSuperTokens(query, reason, count, refinedQueries);
            traceCoverage("refined", refinedQueries);
        } catch (Throwable ignore) {
            traceSuppressed("refinedTrace", ignore);
        }
    }

    private static void traceCoverage(String stage, List<String> queries) {
        List<String> coveredAxes = coveredAxes(queries);
        int axisCount = SUPER_BRANCHES.size();
        TraceStore.put("queryTransformer.subQueries.coverage.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("queryTransformer.subQueries.coverage.axisCount", axisCount);
        TraceStore.put("queryTransformer.subQueries.coverage.coveredAxisCount", coveredAxes.size());
        TraceStore.put("queryTransformer.subQueries.coverage.missingAxisCount",
                Math.max(0, axisCount - coveredAxes.size()));
        TraceStore.put("queryTransformer.subQueries.coverage.complete", coveredAxes.size() >= axisCount);
        TraceStore.put("queryTransformer.subQueries.coverage.coveredAxes", coveredAxes);
    }

    private static List<String> coveredAxes(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        return SUPER_BRANCHES.stream()
                .filter(branch -> branchCoveredByAnyQuery(queries, branch))
                .map(BranchSpec::axis)
                .toList();
    }

    private static int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static void traceSuperTokens(String query, String reason, int count, List<String> branchQueries) {
        String titleSeed = superTitleSeed(query);
        int branchCount = Math.max(0, Math.min(count, SUPER_BRANCHES.size()));
        TraceStore.put("queryTransformer.subQueries.superTokens.enabled", branchCount > 0);
        TraceStore.put("queryTransformer.subQueries.superTokens.reason",
                SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        TraceStore.put("queryTransformer.subQueries.superTokens.branchCount", branchCount);
        TraceStore.put("queryTransformer.subQueries.superTokens.tokenCount", branchCount);
        TraceStore.put("queryTransformer.subQueries.superTokens.subModelCount", branchCount);
        List<String> subModelIds = SUPER_BRANCHES.stream().limit(branchCount).map(BranchSpec::subModelId).toList();
        TraceStore.put("queryTransformer.subQueries.superTokens.subModelIds", subModelIds);
        TraceStore.put("queryTransformer.subQueries.superTokens.subModelAssignmentCount", subModelIds.size());
        List<String> branchTitleHashes = branchTitleHashes(titleSeed, branchCount);
        List<Integer> branchTitleLengths = branchTitleLengths(titleSeed, branchCount);
        List<Integer> branchTitleTermCounts = branchTitleTermCounts(titleSeed, branchCount);
        List<String> branchQueryHashes = branchQueryHashes(branchQueries, branchCount);
        List<Integer> branchQueryLengths = branchQueryLengths(branchQueries, branchCount);
        List<Integer> branchQueryTermCounts = branchQueryTermCounts(branchQueries, branchCount);
        TraceStore.put("queryTransformer.subQueries.superTokens.branchTitleCount", branchTitleHashes.size());
        TraceStore.put("queryTransformer.subQueries.superTokens.branchTitleHashCount", branchTitleHashes.size());
        TraceStore.put("queryTransformer.subQueries.superTokens.branchTitleHashes", branchTitleHashes);
        TraceStore.put("queryTransformer.subQueries.superTokens.branchTitleMetadataCount",
                Math.min(branchTitleHashes.size(), Math.min(branchTitleLengths.size(), branchTitleTermCounts.size())));
        TraceStore.put("queryTransformer.subQueries.superTokens.branchTitleLengths", branchTitleLengths);
        TraceStore.put("queryTransformer.subQueries.superTokens.branchTitleTermCounts", branchTitleTermCounts);
        TraceStore.put("queryTransformer.subQueries.superTokens.branchQueryMetadataCount",
                Math.min(branchQueryHashes.size(), Math.min(branchQueryLengths.size(), branchQueryTermCounts.size())));
        TraceStore.put("queryTransformer.subQueries.superTokens.branchQueryHashes", branchQueryHashes);
        TraceStore.put("queryTransformer.subQueries.superTokens.branchQueryLengths", branchQueryLengths);
        TraceStore.put("queryTransformer.subQueries.superTokens.branchQueryTermCounts", branchQueryTermCounts);
        TraceStore.put("queryTransformer.subQueries.superTokens.branchQueryCoverageComplete",
                branchCount > 0 && branchCount == branchQueryHashes.size());
        TraceStore.put("queryTransformer.subQueries.superTokens.titlePresent", !titleSeed.isBlank());
        TraceStore.put("queryTransformer.subQueries.superTokens.titleHash12", SafeRedactor.hash12(titleSeed));
        TraceStore.put("queryTransformer.subQueries.superTokens.titleTokenCount", titleTokenCount(titleSeed));
        TraceStore.put("queryTransformer.subQueries.superTokens.titleLength", titleSeed.length());
        TraceStore.put("queryTransformer.subQueries.superTokens.branchTitleCoverageComplete",
                branchCount > 0 && branchCount == branchTitleHashes.size());
        List<String> axes = SUPER_BRANCHES.stream().limit(branchCount).map(BranchSpec::axis).toList();
        TraceStore.put("queryTransformer.subQueries.superTokens.axisCount", axes.size());
        TraceStore.put("queryTransformer.subQueries.superTokens.axes", axes);
        TraceStore.put("queryTransformer.subQueries.superTokens.coverageComplete",
                branchCount > 0 && branchCount == subModelIds.size() && branchCount == axes.size());
        traceRequestedQueryRewriteProfile(branchCount);
    }

    private static void traceRequestedQueryRewriteProfile(int branchCount) {
        Object profile = TraceStore.get("web.query.rewrite.requestedTemperatureProfile");
        Object validationTemperature = TraceStore.get("web.query.rewrite.requestedValidationTemperature");
        Object explorationTemperature = TraceStore.get("web.query.rewrite.requestedExplorationTemperature");
        Object explorationRate = TraceStore.get("web.query.rewrite.requestedExplorationRate");
        if (profile == null && validationTemperature == null && explorationTemperature == null && explorationRate == null) {
            if (branchCount <= 0) {
                return;
            }
            profile = "balanced";
            validationTemperature = 0.15d;
            explorationTemperature = 0.55d;
            explorationRate = 0.35d;
        }

        String safeProfile = SafeRedactor.traceLabelOrFallback(profile, "unknown");
        TraceStore.putIfAbsent("web.query.rewrite.temperatureProfile", safeProfile);
        putDoubleIfPresent("web.query.rewrite.validationTemperature", validationTemperature);
        putDoubleIfPresent("web.query.rewrite.explorationTemperature", explorationTemperature);
        putDoubleIfPresent("web.query.rewrite.explorationRate", explorationRate);

        int verificationLaneCount = branchCount > 0 ? 1 : 0;
        int explorationLaneCount = Math.max(0, branchCount - verificationLaneCount);
        TraceStore.putIfAbsent("web.query.rewrite.verificationLaneCount", verificationLaneCount);
        TraceStore.putIfAbsent("web.query.rewrite.explorationLaneCount", explorationLaneCount);
        List<String> laneLabels = new ArrayList<>();
        if (verificationLaneCount > 0) {
            laneLabels.add("verification");
        }
        for (int i = 0; i < explorationLaneCount; i++) {
            laneLabels.add("exploration");
        }
        TraceStore.putIfAbsent("web.query.rewrite.laneLabels", laneLabels);
        TraceStore.putIfAbsent("web.query.rewrite.laneSummary",
                "verification:" + verificationLaneCount + " exploration:" + explorationLaneCount + " profile:" + safeProfile);
    }

    private static void putDoubleIfPresent(String key, Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isFinite(d)) {
                TraceStore.putIfAbsent(key, d);
            }
            return;
        }
        if (value == null) {
            return;
        }
        try {
            double d = Double.parseDouble(String.valueOf(value).trim());
            if (Double.isFinite(d)) {
                TraceStore.putIfAbsent(key, d);
            }
        } catch (NumberFormatException ignore) {
            TraceStore.put("queryTransformer.subQueries.superTokens.profileTraceSuppressed", "invalid_number");
        }
    }

    private static List<String> branchTitleHashes(String titleSeed, int branchCount) {
        if (titleSeed == null || titleSeed.isBlank() || branchCount <= 0) {
            return List.of();
        }
        return SUPER_BRANCHES.stream()
                .limit(Math.min(branchCount, SUPER_BRANCHES.size()))
                .map(branch -> SafeRedactor.hash12(titleSeed + "|" + branch.axis() + "|" + branch.superToken()))
                .toList();
    }

    private static List<String> branchQueryHashes(List<String> queries, int branchCount) {
        return safeBranchQueries(queries, branchCount).stream()
                .map(SafeRedactor::hash12)
                .toList();
    }

    private static List<Integer> branchQueryLengths(List<String> queries, int branchCount) {
        return safeBranchQueries(queries, branchCount).stream()
                .map(String::length)
                .toList();
    }

    private static List<Integer> branchQueryTermCounts(List<String> queries, int branchCount) {
        return safeBranchQueries(queries, branchCount).stream()
                .map(QueryTransformerSubQueryFallback::titleTokenCount)
                .toList();
    }

    private static List<String> safeBranchQueries(List<String> queries, int branchCount) {
        if (queries == null || branchCount <= 0) {
            return List.of();
        }
        return queries.stream()
                .filter(query -> query != null && !query.isBlank())
                .limit(Math.min(branchCount, SUPER_BRANCHES.size()))
                .map(String::trim)
                .toList();
    }

    private static List<Integer> branchTitleLengths(String titleSeed, int branchCount) {
        return branchTitleSeeds(titleSeed, branchCount).stream()
                .map(String::length)
                .toList();
    }

    private static List<Integer> branchTitleTermCounts(String titleSeed, int branchCount) {
        return branchTitleSeeds(titleSeed, branchCount).stream()
                .map(QueryTransformerSubQueryFallback::titleTokenCount)
                .toList();
    }

    private static List<String> branchTitleSeeds(String titleSeed, int branchCount) {
        if (titleSeed == null || titleSeed.isBlank() || branchCount <= 0) {
            return List.of();
        }
        return SUPER_BRANCHES.stream()
                .limit(Math.min(branchCount, SUPER_BRANCHES.size()))
                .map(branch -> titleSeed + " " + branch.superToken())
                .toList();
    }

    private static int titleTokenCount(String titleSeed) {
        if (titleSeed == null || titleSeed.isBlank()) {
            return 0;
        }
        return (int) Pattern.compile("\\s+")
                .splitAsStream(titleSeed.trim())
                .filter(token -> !token.isBlank())
                .count();
    }

    private static String superTitleSeed(String query) {
        PrunedSeed seed = pruneSeed(query);
        if (seed.text().isBlank()) {
            return "";
        }
        return Pattern.compile("\\s+")
                .splitAsStream(seed.text())
                .filter(token -> !token.isBlank())
                .limit(5)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(failure);
        TraceStore.put("qtx.subQueries.suppressed.stage", safeStage);
        TraceStore.put("qtx.subQueries.suppressed.errorType", safeErrorType);
        TraceStore.put("qtx.subQueries.suppressed." + safeStage, true);
        TraceStore.put("qtx.subQueries.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    private record PrunedSeed(String text, int prunedTokenCount) {
    }

    private record BranchSpec(String axis, String superToken, String suffix) {
        private String subModelId() {
            return axis + "-model";
        }
    }
}
