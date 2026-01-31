package com.example.lms.uaw.autolearn;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Slf4j
@Component
public class UawDatasetTrainingDataFilter {

    private static final Pattern LEGACY_DEGRADED_MODE_BANNER = Pattern.compile(
            "^\\s*※\\s*\\[DEGRADED\\s+MODE\\]\\s*([\\s\\S]*?)^\\s*※\\s*\\[/DEGRADED\\s+MODE\\]\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final Pattern LEGACY_FALLBACK_EVIDENCE_MARKER = Pattern.compile(
            "fallback:evidence",
            Pattern.CASE_INSENSITIVE);

    private static final String METRIC_DECISIONS = "uaw.autolearn.dataset_filter.decisions";
    private static final String METRIC_OVERRIDES = "uaw.autolearn.dataset_filter.overrides";
    private static final String METRIC_OVERRIDE_BLOCKED = "uaw.autolearn.dataset_filter.override_blocked";

    // Dashboard-friendly (low-cardinality) summaries
    private static final String METRIC_DECISION_TOTAL = "uaw.autolearn.dataset_filter.decision_total";
    private static final String METRIC_EXCLUDE_RULE_TOTAL = "uaw.autolearn.dataset_filter.exclude_rule_total";
    private static final String METRIC_EXCLUDE_GROUP_TOTAL = "uaw.autolearn.dataset_filter.exclude_group_total";
    private static final String METRIC_ALLOW_RULE_TOTAL = "uaw.autolearn.dataset_filter.allow_rule_total";

    // Override-matrix config quality monitoring
    private static final String METRIC_OVERRIDE_MATRIX_TIE_TOTAL = "uaw.autolearn.dataset_filter.override_matrix_tie_total";

    /**
     * Guardrail against warn-log spam if a misconfiguration causes high tie
     * frequency.
     * Not configurable for now; metrics remain the primary signal.
     */
    private static final long OVERRIDE_MATRIX_TIE_WARN_INTERVAL_MS = 60_000L;
    private static final AtomicLong LAST_OVERRIDE_MATRIX_TIE_WARN_AT_MS = new AtomicLong(0L);

    private final UawDatasetFilterProperties props;

    /** Optional – may be null in tests or minimal deployments. */
    private final MeterRegistry meterRegistry;

    private final boolean strictAllowOverrides;
    private final List<Pattern> allowOverrideGroupMatchers;
    private final List<AllowGroupOverrideEntry> allowGroupOverrideMatrixMatchers;
    private final UawDatasetFilterProperties.OverridePolicy.AllowGroupOverrideMatrixMatchPolicy allowGroupOverrideMatrixMatchPolicy;
    private final UawDatasetFilterProperties.OverridePolicy.AllowGroupOverrideMatrixTiePolicy allowGroupOverrideMatrixTiePolicy;
    private final boolean includeOverrideSourceInDecisionMetrics;
    private final boolean includeOverrideMatrixResolutionInDecisionMetrics;
    private final boolean warnOnOverrideMatrixTie;

    private final List<CompiledRule> hardExcludes;
    private final List<CompiledRule> allowRules;
    private final List<CompiledRule> softExcludes;

    public UawDatasetTrainingDataFilter(
            UawDatasetFilterProperties props,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.props = Objects.requireNonNull(props, "props");
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();

        var overridePolicy = props.getOverridePolicy();

        this.strictAllowOverrides = overridePolicy != null && overridePolicy.isStrictAllowOverrides();
        this.allowOverrideGroupMatchers = compileGroupMatchers(
                overridePolicy == null ? List.of() : overridePolicy.getAllowOverridesSoftExcludeGroups());
        this.allowGroupOverrideMatrixMatchers = compileAllowGroupOverrideMatrix(
                overridePolicy == null ? Map.of() : overridePolicy.getAllowGroupOverrideMatrix());

        this.allowGroupOverrideMatrixMatchPolicy = overridePolicy == null
                ? UawDatasetFilterProperties.OverridePolicy.AllowGroupOverrideMatrixMatchPolicy.MOST_SPECIFIC
                : overridePolicy.getAllowGroupOverrideMatrixMatchPolicy();
        this.allowGroupOverrideMatrixTiePolicy = overridePolicy == null
                ? UawDatasetFilterProperties.OverridePolicy.AllowGroupOverrideMatrixTiePolicy.PICK_LEXICOGRAPHIC
                : overridePolicy.getAllowGroupOverrideMatrixTiePolicy();
        this.includeOverrideSourceInDecisionMetrics = overridePolicy != null
                && overridePolicy.isIncludeOverrideSourceInDecisionMetrics();
        this.includeOverrideMatrixResolutionInDecisionMetrics = overridePolicy != null
                && overridePolicy.isIncludeOverrideMatrixResolutionInDecisionMetrics();
        this.warnOnOverrideMatrixTie = overridePolicy != null && overridePolicy.isWarnOnOverrideMatrixTie();

        // Compile & sort rules once.
        var compiled = props.getRules().stream()
                .map(this::compileRule)
                .toList();

        var priorityOrder = Comparator
                .comparingInt((CompiledRule r) -> r.rule().getPriority()).reversed()
                .thenComparing(r -> r.rule().getName());

        this.hardExcludes = compiled.stream()
                .filter(r -> r.rule().getAction() == UawDatasetFilterProperties.Action.EXCLUDE && r.rule().isHard())
                .sorted(priorityOrder)
                .toList();

        this.allowRules = compiled.stream()
                .filter(r -> r.rule().getAction() == UawDatasetFilterProperties.Action.ALLOW)
                .sorted(priorityOrder)
                .toList();

        this.softExcludes = compiled.stream()
                .filter(r -> r.rule().getAction() == UawDatasetFilterProperties.Action.EXCLUDE && !r.rule().isHard())
                .sorted(priorityOrder)
                .toList();

        if (log.isInfoEnabled()) {
            var lines = new ArrayList<String>();
            lines.add("UAW dataset training-data filter initialized: enabled=" + props.isEnabled());
            lines.add("overridePolicy.strictAllowOverrides=" + strictAllowOverrides);
            lines.add("overridePolicy.allowOverridesSoftExcludeGroups=" +
                    (props.getOverridePolicy() == null ? "[]"
                            : props.getOverridePolicy().getAllowOverridesSoftExcludeGroups()));
            lines.add("overridePolicy.allowGroupOverrideMatrix=" +
                    (props.getOverridePolicy() == null ? "{}"
                            : props.getOverridePolicy().getAllowGroupOverrideMatrix()));
            lines.add("trace.enabled=" + (props.getTrace() != null && props.getTrace().isEnabled()));
            lines.add(
                    "trace.includeSampleHash=" + (props.getTrace() != null && props.getTrace().isIncludeSampleHash()));

            lines.add("hardExcludes(" + hardExcludes.size() + "):");
            for (var r : hardExcludes) {
                lines.add("  - " + r.rule().getName() + " [group=" + r.rule().getGroup() + ", scope="
                        + r.rule().getScope().key() + ", priority=" + r.rule().getPriority() + "]");
            }

            lines.add("allowRules(" + allowRules.size() + "):");
            for (var r : allowRules) {
                lines.add("  - " + r.rule().getName() + " [group=" + r.rule().getGroup() + ", scope="
                        + r.rule().getScope().key() + ", priority=" + r.rule().getPriority() + "]");
            }

            lines.add("softExcludes(" + softExcludes.size() + "):");
            for (var r : softExcludes) {
                lines.add("  - " + r.rule().getName() + " [group=" + r.rule().getGroup() + ", scope="
                        + r.rule().getScope().key() + ", priority=" + r.rule().getPriority() + "]");
            }

            log.info("{}", String.join("\n", lines));
        }
    }

    /**
     * Returns true if a sample should be excluded from training.
     *
     * <p>
     * This method is used as the final safety gate right before writing to the
     * dataset.
     */
    public boolean shouldExclude(String question, String answer, String modelUsed) {
        var decision = filter(question, answer, modelUsed);

        if (!decision.accept()) {
            // Log the decisive rule. (Do not log raw content.)
            log.info(
                    "Training data sample excluded: decisionType={}, reasonKey={}, reasonDetail={}, rule={}, allowRule={} (strictAllowOverrides={})",
                    decision.decisionType().metricValue(),
                    decision.reasonKey(),
                    decision.reasonDetail(),
                    decision.decisiveRule() == null ? "none" : decision.decisiveRule().name(),
                    decision.allowRule() == null ? "none" : decision.allowRule().name(),
                    decision.strictAllowOverrides());
            return true;
        }

        return false;
    }

    /**
     * Legacy helper used by older callsites.
     *
     * @deprecated Prefer {@link #shouldExclude(String, String, String)}.
     */
    @Deprecated
    public boolean shouldExcludeDeprecated(String question, String answer, String modelUsed) {
        var q = question == null ? "" : question;
        var a = answer == null ? "" : answer;

        if (LEGACY_DEGRADED_MODE_BANNER.matcher(a).find()) {
            log.info("Training data excluded (legacy): degraded mode banner detected.");
            return true;
        }
        if (LEGACY_FALLBACK_EVIDENCE_MARKER.matcher(a).find()) {
            log.info("Training data excluded (legacy): fallback:evidence marker detected.");
            return true;
        }
        return false;
    }

    public FilterDecision filter(String question, String answer, String modelUsed) {
        var q = question == null ? "" : question;
        var a = answer == null ? "" : answer;
        var m = modelUsed == null ? "" : modelUsed;

        // For sample-level traces (DEBUG-only). Metrics are always recorded.
        var overriddenSoftExcludes = new ArrayList<MatchedRule>();
        MatchedRule blockedSoftExclude = null;

        if (!props.isEnabled()) {
            var decision = FilterDecision.accept(DecisionType.ACCEPT_DISABLED, null, null, strictAllowOverrides);
            recordDecisionMetrics(decision);
            traceDecision(decision, q, a, m, overriddenSoftExcludes, blockedSoftExclude);
            return decision;
        }

        // 1) Hard excludes – always win.
        for (var hardExclude : hardExcludes) {
            if (hardExclude.matches(q, a, m)) {
                var decisive = MatchedRule.from(hardExclude.rule());
                var decision = FilterDecision.exclude(DecisionType.EXCLUDE_HARD, decisive, null, strictAllowOverrides);
                recordDecisionMetrics(decision);
                traceDecision(decision, q, a, m, overriddenSoftExcludes, blockedSoftExclude);
                return decision;
            }
        }

        // 2) Find first matching allow rule (if any) – used for override decisions.
        CompiledRule allowMatch = null;
        for (var allow : allowRules) {
            if (allow.matches(q, a, m)) {
                allowMatch = allow;
                break;
            }
        }

        MatchedRule allowRule = allowMatch == null ? null : MatchedRule.from(allowMatch.rule());
        OverrideSource overrideSource = null;
        MatrixMatch overrideMatrixMatch = null;

        // 3) Soft excludes, potentially overridden by allow.
        boolean overrodeAnySoft = false;
        for (var softExclude : softExcludes) {
            if (!softExclude.matches(q, a, m)) {
                continue;
            }

            OverrideCheck overrideCheck = allowMatch == null
                    ? OverrideCheck.globalDeny()
                    : canAllowOverrideSoftExclude(allowMatch, softExclude);
            if (overrideSource == null && allowMatch != null) {
                overrideSource = overrideCheck.source();
                overrideMatrixMatch = overrideCheck.matrixMatch();
            }

            boolean canOverride = allowMatch != null && overrideCheck.allowed();

            if (canOverride) {
                overrodeAnySoft = true;
                recordOverrideMetrics(allowMatch, softExclude, overrideCheck.source());
                overriddenSoftExcludes.add(MatchedRule.from(softExclude.rule()));
                continue;
            }

            // Not overridden -> excluded.
            if (allowMatch != null) {
                recordOverrideBlockedMetrics(allowMatch, softExclude, overrideCheck.source());
            }

            blockedSoftExclude = MatchedRule.from(softExclude.rule());
            var decisive = blockedSoftExclude;
            var type = allowMatch == null ? DecisionType.EXCLUDE_SOFT_NO_ALLOW
                    : DecisionType.EXCLUDE_SOFT_ALLOW_BLOCKED;
            var decision = FilterDecision.exclude(type, decisive, allowRule, strictAllowOverrides, overrideSource,
                    overrideMatrixMatch);
            recordDecisionMetrics(decision);
            traceDecision(decision, q, a, m, overriddenSoftExcludes, blockedSoftExclude);
            return decision;
        }

        // 4) If allow matched, accept (and it may have overridden one or more soft
        // excludes).
        if (allowMatch != null) {
            var type = overrodeAnySoft ? DecisionType.ACCEPT_ALLOW_OVERRIDE : DecisionType.ACCEPT_ALLOW;
            var decision = FilterDecision.accept(type, MatchedRule.from(allowMatch.rule()), allowRule,
                    strictAllowOverrides, overrideSource, overrideMatrixMatch);
            recordDecisionMetrics(decision);
            traceDecision(decision, q, a, m, overriddenSoftExcludes, blockedSoftExclude);
            return decision;
        }

        // 5) Default accept.
        var decision = FilterDecision.accept(DecisionType.ACCEPT_DEFAULT, null, null, strictAllowOverrides);
        recordDecisionMetrics(decision);
        traceDecision(decision, q, a, m, overriddenSoftExcludes, blockedSoftExclude);
        return decision;
    }

    private OverrideCheck canAllowOverrideSoftExclude(CompiledRule allowRule, CompiledRule softExclude) {
        // Legacy behavior: allow overrides any soft exclude.
        if (!strictAllowOverrides) {
            return OverrideCheck.allowed(OverrideSource.GLOBAL, null);
        }

        if (allowRule == null || softExclude == null) {
            return OverrideCheck.denied(OverrideSource.GLOBAL, null);
        }

        var softExcludeGroup = normalizeGroup(softExclude.rule().getGroup());
        var overrideMatchers = resolveAllowedOverrideMatchersForAllow(allowRule);
        if (overrideMatchers.matchers().isEmpty()) {
            // No configured override matchers at any level. Treat as global deny.
            return OverrideCheck.denied(overrideMatchers.source(), overrideMatchers.matrixMatch());
        }

        for (var matcher : overrideMatchers.matchers()) {
            if (matcher.matcher(softExcludeGroup).matches()) {
                return OverrideCheck.allowed(overrideMatchers.source(), overrideMatchers.matrixMatch());
            }
        }
        return OverrideCheck.denied(overrideMatchers.source(), overrideMatchers.matrixMatch());
    }

    private OverrideMatchers resolveAllowedOverrideMatchersForAllow(CompiledRule allowRule) {
        if (allowRule == null) {
            return new OverrideMatchers(OverrideSource.GLOBAL, List.of(), null);
        }

        // 1) Per-allow-rule override list (most specific)
        var perRule = allowRule.overrideSoftExcludeGroupMatchers();
        if (perRule != null && !perRule.isEmpty()) {
            return new OverrideMatchers(OverrideSource.RULE, perRule, null);
        }

        // 2) Per-allow-group override matrix (allow-group key supports glob)
        var allowGroup = normalizeGroup(allowRule.rule().getGroup());
        var matchedEntries = new ArrayList<AllowGroupOverrideEntry>();
        for (var entry : allowGroupOverrideMatrixMatchers) {
            if (entry.allowGroupMatcher().matcher(allowGroup).matches()) {
                matchedEntries.add(entry);
            }
        }

        if (!matchedEntries.isEmpty()) {
            return resolveFromAllowGroupOverrideMatrix(allowGroup, matchedEntries);
        }

        // 3) Global fallback
        return new OverrideMatchers(OverrideSource.GLOBAL, allowOverrideGroupMatchers, null);
    }

    private OverrideMatchers resolveFromAllowGroupOverrideMatrix(String allowGroup,
            List<AllowGroupOverrideEntry> matchedEntries) {
        var matchPolicy = allowGroupOverrideMatrixMatchPolicy == null
                ? UawDatasetFilterProperties.OverridePolicy.AllowGroupOverrideMatrixMatchPolicy.MOST_SPECIFIC
                : allowGroupOverrideMatrixMatchPolicy;
        var tiePolicy = allowGroupOverrideMatrixTiePolicy == null
                ? UawDatasetFilterProperties.OverridePolicy.AllowGroupOverrideMatrixTiePolicy.PICK_LEXICOGRAPHIC
                : allowGroupOverrideMatrixTiePolicy;

        var matchedAllowGroupGlobs = matchedEntries.stream()
                .sorted(Comparator.comparingInt(AllowGroupOverrideEntry::order))
                .map(AllowGroupOverrideEntry::allowGroupGlob)
                .toList();

        List<Pattern> allowedMatchers;
        String selectedAllowGroupGlob;
        String resolution;

        switch (matchPolicy) {
            case MERGE_ALL_MATCHES -> {
                allowedMatchers = mergeAllowedSoftExcludeMatchers(matchedEntries);
                selectedAllowGroupGlob = matchedEntries.size() == 1
                        ? matchedEntries.get(0).allowGroupGlob()
                        : "<merged_all>";
                resolution = matchedEntries.size() == 1 ? "single" : "multi_merge_all";
            }
            case FIRST_MATCH -> {
                var selected = matchedEntries.stream()
                        .min(Comparator.comparingInt(AllowGroupOverrideEntry::order))
                        .orElse(matchedEntries.get(0));
                allowedMatchers = selected.allowedSoftExcludeGroupMatchers();
                selectedAllowGroupGlob = selected.allowGroupGlob();
                resolution = matchedEntries.size() == 1 ? "single" : "multi_first_match";
            }
            case MOST_SPECIFIC -> {
                var maxSpec = matchedEntries.stream()
                        .mapToInt(AllowGroupOverrideEntry::allowGroupSpecificity)
                        .max()
                        .orElse(0);

                var bestCandidates = matchedEntries.stream()
                        .filter(e -> e.allowGroupSpecificity() == maxSpec)
                        .toList();

                if (bestCandidates.size() == 1) {
                    var selected = bestCandidates.get(0);
                    allowedMatchers = selected.allowedSoftExcludeGroupMatchers();
                    selectedAllowGroupGlob = selected.allowGroupGlob();
                    resolution = matchedEntries.size() == 1 ? "single" : "multi_most_specific";
                } else {
                    // Tie among equally-specific keys.
                    switch (tiePolicy) {
                        case MERGE_TIED -> {
                            allowedMatchers = mergeAllowedSoftExcludeMatchers(bestCandidates);
                            selectedAllowGroupGlob = "<merged_tied>";
                            resolution = "multi_tie_merge";
                        }
                        case DENY_TIED -> {
                            allowedMatchers = List.of();
                            selectedAllowGroupGlob = "<deny_tied>";
                            resolution = "multi_tie_deny";
                        }
                        case PICK_FIRST_IN_CONFIG -> {
                            var selected = bestCandidates.stream()
                                    .min(Comparator.comparingInt(AllowGroupOverrideEntry::order))
                                    .orElse(bestCandidates.get(0));
                            allowedMatchers = selected.allowedSoftExcludeGroupMatchers();
                            selectedAllowGroupGlob = selected.allowGroupGlob();
                            resolution = "multi_tie_first";
                        }
                        case PICK_LEXICOGRAPHIC -> {
                            var selected = bestCandidates.stream()
                                    .min(Comparator.comparing(AllowGroupOverrideEntry::allowGroupGlob))
                                    .orElse(bestCandidates.get(0));
                            allowedMatchers = selected.allowedSoftExcludeGroupMatchers();
                            selectedAllowGroupGlob = selected.allowGroupGlob();
                            resolution = "multi_tie_lex";
                        }
                        default -> {
                            var selected = bestCandidates.stream()
                                    .min(Comparator.comparing(AllowGroupOverrideEntry::allowGroupGlob))
                                    .orElse(bestCandidates.get(0));
                            allowedMatchers = selected.allowedSoftExcludeGroupMatchers();
                            selectedAllowGroupGlob = selected.allowGroupGlob();
                            resolution = "multi_tie_lex";
                        }
                    }
                }
            }
            default -> {
                var selected = matchedEntries.stream()
                        .min(Comparator.comparingInt(AllowGroupOverrideEntry::order))
                        .orElse(matchedEntries.get(0));
                allowedMatchers = selected.allowedSoftExcludeGroupMatchers();
                selectedAllowGroupGlob = selected.allowGroupGlob();
                resolution = matchedEntries.size() == 1 ? "single" : "multi_first_match";
            }
        }

        var matrixMatch = new MatrixMatch(
                allowGroup,
                matchedAllowGroupGlobs,
                selectedAllowGroupGlob,
                resolution,
                matchPolicy.name(),
                tiePolicy.name());

        // Config-quality monitoring: ties only happen in MOST_SPECIFIC when multiple
        // equally-specific
        // allowGroup glob keys match the same allowGroup.
        if (resolution != null && resolution.startsWith("multi_tie_")) {
            recordOverrideMatrixTieMetrics(matchPolicy, tiePolicy, resolution);
            maybeWarnOverrideMatrixTie(matrixMatch);
        }
        return new OverrideMatchers(OverrideSource.MATRIX, allowedMatchers, matrixMatch);
    }

    private void recordOverrideMatrixTieMetrics(
            UawDatasetFilterProperties.OverridePolicy.AllowGroupOverrideMatrixMatchPolicy matchPolicy,
            UawDatasetFilterProperties.OverridePolicy.AllowGroupOverrideMatrixTiePolicy tiePolicy,
            String resolution) {
        // Keep tags low-cardinality (policy + resolution only).
        var tags = Tags.of(
                Tag.of("match_policy", safeTagValue(matchPolicy.name().toLowerCase(Locale.ROOT))),
                Tag.of("tie_policy", safeTagValue(tiePolicy.name().toLowerCase(Locale.ROOT))),
                Tag.of("resolution", safeTagValue(resolution)));
        meterRegistry.counter(METRIC_OVERRIDE_MATRIX_TIE_TOTAL, tags).increment();
    }

    private void maybeWarnOverrideMatrixTie(MatrixMatch matrixMatch) {
        if (!warnOnOverrideMatrixTie) {
            return;
        }
        if (matrixMatch == null) {
            return;
        }
        // Avoid log spam if a misconfiguration hits many samples.
        long now = System.currentTimeMillis();
        long last = LAST_OVERRIDE_MATRIX_TIE_WARN_AT_MS.get();
        if (now - last < OVERRIDE_MATRIX_TIE_WARN_INTERVAL_MS) {
            return;
        }
        if (!LAST_OVERRIDE_MATRIX_TIE_WARN_AT_MS.compareAndSet(last, now)) {
            return;
        }

        log.warn(
                "UAW dataset-filter override-matrix tie detected (throttled={}ms). allowGroup={} matchPolicy={} tiePolicy={} resolution={} selectedAllowGroupGlob={} matchedAllowGroupGlobs={}",
                OVERRIDE_MATRIX_TIE_WARN_INTERVAL_MS,
                matrixMatch.allowGroup(),
                matrixMatch.matchPolicy(),
                matrixMatch.tiePolicy(),
                matrixMatch.resolution(),
                matrixMatch.selectedAllowGroupGlob(),
                matrixMatch.matchedAllowGroupGlobs());
    }

    private static List<Pattern> mergeAllowedSoftExcludeMatchers(List<AllowGroupOverrideEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        var dedup = new LinkedHashMap<String, Pattern>();
        for (var entry : entries) {
            if (entry == null || entry.allowedSoftExcludeGroupMatchers() == null) {
                continue;
            }
            for (var p : entry.allowedSoftExcludeGroupMatchers()) {
                if (p == null) {
                    continue;
                }
                dedup.putIfAbsent(p.pattern(), p);
            }
        }

        return List.copyOf(dedup.values());
    }

    private static String normalizeGroup(String group) {
        if (group == null) {
            return "default";
        }
        var g = group.trim();
        return g.isEmpty() ? "default" : g;
    }

    private void traceDecision(FilterDecision decision,
            String question,
            String answer,
            String modelUsed,
            List<MatchedRule> overriddenSoftExcludes,
            MatchedRule blockedSoftExclude) {
        var trace = props.getTrace();
        if (trace == null || !trace.isEnabled()) {
            return;
        }
        if (!log.isDebugEnabled()) {
            return;
        }

        String sampleHash = "-";
        if (trace.isIncludeSampleHash()) {
            sampleHash = sampleHash(question, answer, modelUsed);
        }

        log.debug(
                "UAW dataset-filter trace: outcome={} decisionType={} decisive={} allow={} overriddenSoftExcludes={} blockedSoftExclude={} overrideSource={} overrideMatrixMatch={} modelUsed={} promptChars={} answerChars={} strictAllowOverrides={} sampleHash={}",
                decision == null ? "unknown" : (decision.accept() ? "accept" : "exclude"),
                decision == null ? "unknown" : decision.decisionType().metricValue(),
                formatMatchedRule(decision == null ? null : decision.decisiveRule()),
                formatMatchedRule(decision == null ? null : decision.allowRule()),
                formatMatchedRules(overriddenSoftExcludes),
                formatMatchedRule(blockedSoftExclude),
                decision == null || decision.overrideSource() == null ? "none"
                        : decision.overrideSource().metricValue(),
                formatMatrixMatch(decision == null ? null : decision.overrideMatrixMatch()),
                safeTagValue(modelUsed),
                question == null ? 0 : question.length(),
                answer == null ? 0 : answer.length(),
                decision != null && decision.strictAllowOverrides(),
                sampleHash);
    }

    private static String formatMatrixMatch(MatrixMatch m) {
        if (m == null) {
            return "none";
        }
        return safeTagValue(m.allowGroup()) +
                "{selectedKey=" + safeTagValue(m.selectedAllowGroupGlob()) +
                ",resolution=" + safeTagValue(m.resolution()) +
                ",matchPolicy=" + safeTagValue(m.matchPolicy()) +
                ",tiePolicy=" + safeTagValue(m.tiePolicy()) +
                ",matchedKeys=" + formatStringList(m.matchedAllowGroupGlobs()) +
                "}";
    }

    private static String formatStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        var sb = new StringBuilder("[");
        int count = 0;
        for (var v : values) {
            if (v == null) {
                continue;
            }
            if (count > 0) {
                sb.append(", ");
            }
            sb.append(safeTagValue(v));
            count++;
            if (count >= 8) {
                sb.append(", ...");
                break;
            }
        }
        return sb.append("]").toString();
    }

    private static String formatMatchedRule(MatchedRule r) {
        if (r == null) {
            return "none";
        }
        return safeTagValue(r.name()) +
                "{group=" + safeTagValue(r.group()) +
                ",scope=" + (r.scope() == null ? "none" : r.scope().key()) +
                ",action=" + (r.action() == null ? "none" : r.action().name().toLowerCase(Locale.ROOT)) +
                ",hard=" + r.hard() +
                "}";
    }

    private static String formatMatchedRules(List<MatchedRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return "[]";
        }
        var sb = new StringBuilder("[");
        int count = 0;
        for (var r : rules) {
            if (r == null) {
                continue;
            }
            if (count > 0) {
                sb.append(", ");
            }
            sb.append(safeTagValue(r.name())).append("{")
                    .append("group=").append(safeTagValue(r.group()))
                    .append(",scope=").append(r.scope() == null ? "none" : r.scope().key())
                    .append("}");
            count++;
            if (count >= 8) {
                sb.append(", ...");
                break;
            }
        }
        return sb.append("]").toString();
    }

    private static String sampleHash(String question, String answer, String modelUsed) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update((question == null ? "" : question).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update((answer == null ? "" : answer).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update((modelUsed == null ? "" : modelUsed).getBytes(StandardCharsets.UTF_8));
            var digest = md.digest();
            // 8 bytes (16 hex chars) is enough to correlate in logs without exposing
            // content.
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "sha256_unavailable";
        }
    }

    private void recordDecisionMetrics(FilterDecision decision) {
        if (meterRegistry == null || decision == null) {
            return;
        }

        var decisive = decision.decisiveRule();
        var allow = decision.allowRule();

        var tags = Tags.of(
                "outcome", decision.accept() ? "accept" : "exclude",
                "decision_type", decision.decisionType().metricValue(),
                "rule", decisive == null ? "none" : safeTagValue(decisive.name()),
                "group", decisive == null ? "none" : safeTagValue(decisive.group()),
                "action", decisive == null ? "none" : decisive.action().name().toLowerCase(Locale.ROOT),
                "scope", decisive == null ? "none" : decisive.scope().key(),
                "hard", decisive == null ? "none" : String.valueOf(decisive.hard()),
                "allow_rule", allow == null ? "none" : safeTagValue(allow.name()),
                "allow_group", allow == null ? "none" : safeTagValue(allow.group()),
                "strict_allow_overrides", String.valueOf(decision.strictAllowOverrides()),
                "overrode_soft", decision.decisionType() == DecisionType.ACCEPT_ALLOW_OVERRIDE ? "true" : "false");

        if (includeOverrideSourceInDecisionMetrics) {
            tags = tags.and(
                    "override_source",
                    decision.overrideSource() == null ? "none" : decision.overrideSource().metricValue());
        }

        if (includeOverrideMatrixResolutionInDecisionMetrics) {
            tags = tags.and(
                    "override_matrix_resolution",
                    decision.overrideMatrixMatch() == null
                            ? "none"
                            : safeTagValue(decision.overrideMatrixMatch().resolution()));
        }

        meterRegistry.counter(METRIC_DECISIONS, tags).increment();

        // Dashboard-friendly (low-cardinality) metrics.
        var totalTags = Tags.of(
                "outcome", decision.accept() ? "accept" : "exclude",
                "decision_type", decision.decisionType().metricValue());
        if (includeOverrideSourceInDecisionMetrics) {
            totalTags = totalTags.and(
                    "override_source",
                    decision.overrideSource() == null ? "none" : decision.overrideSource().metricValue());
        }
        if (includeOverrideMatrixResolutionInDecisionMetrics) {
            totalTags = totalTags.and(
                    "override_matrix_resolution",
                    decision.overrideMatrixMatch() == null
                            ? "none"
                            : safeTagValue(decision.overrideMatrixMatch().resolution()));
        }
        meterRegistry.counter(METRIC_DECISION_TOTAL, totalTags).increment();

        if (decisive != null && decisive.action() == UawDatasetFilterProperties.Action.EXCLUDE) {
            meterRegistry.counter(
                    METRIC_EXCLUDE_RULE_TOTAL,
                    Tags.of(
                            "rule", safeTagValue(decisive.name()),
                            "group", safeTagValue(decisive.group()),
                            "hard", String.valueOf(decisive.hard()),
                            "decision_type", decision.decisionType().metricValue()))
                    .increment();

            meterRegistry.counter(
                    METRIC_EXCLUDE_GROUP_TOTAL,
                    Tags.of(
                            "group", safeTagValue(decisive.group()),
                            "hard", String.valueOf(decisive.hard()),
                            "decision_type", decision.decisionType().metricValue()))
                    .increment();
        }

        if (allow != null) {
            meterRegistry.counter(
                    METRIC_ALLOW_RULE_TOTAL,
                    Tags.of(
                            "rule", safeTagValue(allow.name()),
                            "group", safeTagValue(allow.group()),
                            "outcome", decision.accept() ? "accept" : "exclude",
                            "decision_type", decision.decisionType().metricValue(),
                            "overrode_soft",
                            decision.decisionType() == DecisionType.ACCEPT_ALLOW_OVERRIDE ? "true" : "false"))
                    .increment();
        }
    }

    private void recordOverrideMetrics(CompiledRule allow, CompiledRule softExclude, OverrideSource source) {
        if (meterRegistry == null) {
            return;
        }

        var tags = Tags.of(
                "allow_rule", safeTagValue(allow.rule().getName()),
                "exclude_rule", safeTagValue(softExclude.rule().getName()),
                "exclude_group", safeTagValue(normalizeGroup(softExclude.rule().getGroup())),
                "exclude_scope", softExclude.rule().getScope().key(),
                "override_source", source.metricValue());
        meterRegistry.counter(METRIC_OVERRIDES, tags).increment();
    }

    private void recordOverrideBlockedMetrics(CompiledRule allow, CompiledRule softExclude, OverrideSource source) {
        if (meterRegistry == null) {
            return;
        }

        var tags = Tags.of(
                "allow_rule", safeTagValue(allow.rule().getName()),
                "exclude_rule", safeTagValue(softExclude.rule().getName()),
                "exclude_group", safeTagValue(normalizeGroup(softExclude.rule().getGroup())),
                "exclude_scope", softExclude.rule().getScope().key(),
                "override_source", source.metricValue());
        meterRegistry.counter(METRIC_OVERRIDE_BLOCKED, tags).increment();
    }

    private static String safeTagValue(String value) {
        if (value == null) {
            return "none";
        }

        var v = value.trim();
        if (v.isEmpty()) {
            return "none";
        }

        // Keep label values bounded and readable.
        if (v.length() > 120) {
            v = v.substring(0, 120);
        }

        return v;
    }

    private CompiledRule compileRule(UawDatasetFilterProperties.Rule rule) {
        var patterns = rule.getPatterns().stream()
                .filter(p -> p != null && !p.isBlank())
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE))
                .toList();

        var overrideSoftExcludeGroupMatchers = rule.getAction() == UawDatasetFilterProperties.Action.ALLOW
                ? compileGroupMatchers(rule.getOverrideSoftExcludeGroups())
                : List.<Pattern>of();

        return new CompiledRule(rule, patterns, overrideSoftExcludeGroupMatchers);
    }

    private static List<Pattern> compileGroupMatchers(List<String> globs) {
        if (globs == null || globs.isEmpty()) {
            return List.of();
        }

        return globs.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UawDatasetTrainingDataFilter::globToRegexPattern)
                .toList();
    }

    private static List<AllowGroupOverrideEntry> compileAllowGroupOverrideMatrix(Map<String, List<String>> matrix) {
        if (matrix == null || matrix.isEmpty()) {
            return List.of();
        }

        var entries = new ArrayList<AllowGroupOverrideEntry>();
        int order = 0;
        for (var e : matrix.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                continue;
            }

            String allowGroupGlob = e.getKey().trim();
            entries.add(new AllowGroupOverrideEntry(
                    allowGroupGlob,
                    globToRegexPattern(allowGroupGlob),
                    globSpecificity(allowGroupGlob),
                    order++,
                    compileGroupMatchers(e.getValue())));
        }
        return List.copyOf(entries);
    }

    private static int globSpecificity(String glob) {
        int score = 0;
        for (int i = 0; i < glob.length(); i++) {
            if (glob.charAt(i) != '*') {
                score++;
            }
        }
        return score;
    }

    /**
     * Simple glob-to-regex conversion:
     * <ul>
     * <li>'*' matches any substring</li>
     * <li>all other characters are treated literally</li>
     * </ul>
     */
    private static Pattern globToRegexPattern(String glob) {
        // Split on '*' and quote the literal segments, then re-join with ".*".
        var parts = Arrays.asList(glob.split("\\*", -1));
        var sb = new StringBuilder();
        sb.append("^");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(".*");
            }
            sb.append(Pattern.quote(parts.get(i)));
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }

    public enum DecisionType {
        ACCEPT_DISABLED,
        ACCEPT_DEFAULT,
        ACCEPT_ALLOW,
        ACCEPT_ALLOW_OVERRIDE,
        EXCLUDE_HARD,
        EXCLUDE_SOFT_NO_ALLOW,
        EXCLUDE_SOFT_ALLOW_BLOCKED;

        public String metricValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record MatchedRule(
            String name,
            String group,
            UawDatasetFilterProperties.Action action,
            UawDatasetFilterProperties.Scope scope,
            boolean hard) {
        public static MatchedRule from(UawDatasetFilterProperties.Rule r) {
            if (r == null) {
                return null;
            }
            return new MatchedRule(
                    r.getName(),
                    normalizeGroup(r.getGroup()),
                    r.getAction(),
                    r.getScope(),
                    r.isHard());
        }
    }

    /**
     * Captures how an allow-group override matrix entry was resolved for a given
     * allow rule group.
     *
     * <p>
     * This is primarily used for sampled decision traces (debug) and can optionally
     * be surfaced
     * via decision metrics tags while keeping cardinality low.
     */
    public record MatrixMatch(
            String allowGroup,
            List<String> matchedAllowGroupGlobs,
            String selectedAllowGroupGlob,
            String resolution,
            String matchPolicy,
            String tiePolicy) {
    }

    public record FilterDecision(
            boolean accept,
            DecisionType decisionType,
            String reasonKey,
            String reasonDetail,
            MatchedRule decisiveRule,
            MatchedRule allowRule,
            boolean strictAllowOverrides,
            OverrideSource overrideSource,
            MatrixMatch overrideMatrixMatch) {
        public static FilterDecision accept(DecisionType type, MatchedRule decisiveRule, MatchedRule allowRule,
                boolean strictAllowOverrides) {
            return accept(type, decisiveRule, allowRule, strictAllowOverrides, null, null);
        }

        public static FilterDecision accept(
                DecisionType type,
                MatchedRule decisiveRule,
                MatchedRule allowRule,
                boolean strictAllowOverrides,
                OverrideSource overrideSource,
                MatrixMatch overrideMatrixMatch) {
            return new FilterDecision(
                    true,
                    type,
                    decisiveRule == null ? "none" : decisiveRule.scope().key(),
                    decisiveRule == null ? "accepted" : decisiveRule.name(),
                    decisiveRule,
                    allowRule,
                    strictAllowOverrides,
                    overrideSource,
                    overrideMatrixMatch);
        }

        public static FilterDecision accept(DecisionType type, MatchedRule allowRule, boolean strictAllowOverrides) {
            return accept(type, allowRule, allowRule, strictAllowOverrides);
        }

        public static FilterDecision exclude(DecisionType type, MatchedRule decisiveRule, MatchedRule allowRule,
                boolean strictAllowOverrides) {
            return exclude(type, decisiveRule, allowRule, strictAllowOverrides, null, null);
        }

        public static FilterDecision exclude(
                DecisionType type,
                MatchedRule decisiveRule,
                MatchedRule allowRule,
                boolean strictAllowOverrides,
                OverrideSource overrideSource,
                MatrixMatch overrideMatrixMatch) {
            return new FilterDecision(
                    false,
                    type,
                    decisiveRule == null ? "none" : decisiveRule.scope().key(),
                    decisiveRule == null ? "excluded" : decisiveRule.name(),
                    decisiveRule,
                    allowRule,
                    strictAllowOverrides,
                    overrideSource,
                    overrideMatrixMatch);
        }
    }

    public enum OverrideSource {
        RULE("rule"),
        MATRIX("matrix"),
        GLOBAL("global");

        private final String metricValue;

        OverrideSource(String metricValue) {
            this.metricValue = metricValue;
        }

        public String metricValue() {
            return metricValue;
        }
    }

    private record OverrideCheck(boolean allowed, OverrideSource source, MatrixMatch matrixMatch) {
        static OverrideCheck allowed(OverrideSource source, MatrixMatch matrixMatch) {
            return new OverrideCheck(true, source, matrixMatch);
        }

        static OverrideCheck denied(OverrideSource source, MatrixMatch matrixMatch) {
            return new OverrideCheck(false, source, matrixMatch);
        }

        static OverrideCheck globalDeny() {
            return denied(OverrideSource.GLOBAL, null);
        }
    }

    private record OverrideMatchers(OverrideSource source, List<Pattern> matchers, MatrixMatch matrixMatch) {
    }

    private record AllowGroupOverrideEntry(
            String allowGroupGlob,
            Pattern allowGroupMatcher,
            int allowGroupSpecificity,
            int order,
            List<Pattern> allowedSoftExcludeGroupMatchers) {
    }

    private record CompiledRule(UawDatasetFilterProperties.Rule rule,
            List<Pattern> patterns,
            List<Pattern> overrideSoftExcludeGroupMatchers) {
        boolean matches(String question, String answer, String modelUsed) {
            var haystack = switch (rule.getScope()) {
                case PROMPT -> question;
                case ANSWER -> answer;
                case MODEL_USED -> modelUsed;
            };

            if (haystack == null || haystack.isBlank()) {
                return false;
            }

            for (var p : patterns) {
                if (p.matcher(haystack).find()) {
                    return true;
                }
            }
            return false;
        }
    }
}
