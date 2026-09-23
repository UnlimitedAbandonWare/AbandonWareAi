package ai.abandonware.nova.orch.compress;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.Content;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Focused Anchor-Probe selector for already-scored prompt evidence.
 *
 * <p>The compressor owns scoring and prompt content rebuilds; this handler owns
 * staged K narrowing, dedupe/host caps, final evidence count, and hash-only trace.
 */
public class AnchorProbeHandler {

    private static final System.Logger LOG = System.getLogger(AnchorProbeHandler.class.getName());
    private static final List<Integer> DEFAULT_K_SCHEDULE = List.of(32, 16, 8, 3);
    private static final List<Integer> DEFAULT_SPREAD_K_SCHEDULE = List.of(64, 48, 32, 16, 5);
    private static final int MAX_SCHEDULE_STAGES = 8;
    private static final int HOST_CAP = 2;
    private static final String MODE_ANCHOR = "ANCHOR_PROBE";
    private static final String MODE_SPREAD = "SPREAD_PROBE";

    private final AnchorNarrower anchorNarrower;

    public AnchorProbeHandler() {
        this(new AnchorNarrower());
    }

    public AnchorProbeHandler(AnchorNarrower anchorNarrower) {
        this.anchorNarrower = anchorNarrower == null ? new AnchorNarrower() : anchorNarrower;
    }

    public record Selection(
            List<DynamicContextCompressor.ScoredContent> selected,
            List<Integer> kSchedule,
            List<Integer> stageCounts,
            int finalCap,
            boolean enabled,
            boolean applied,
            boolean failSoft,
            String reason,
            String probeMode,
            double anchorDiversity,
            double authorityAvg,
            double noveltyAvg,
            double rerankConfidenceAvg) {
    }

    private record SpreadMetrics(
            double anchorDiversity,
            double authorityAvg,
            double noveltyAvg,
            double rerankConfidenceAvg) {
    }

    public Selection select(
            List<DynamicContextCompressor.ScoredContent> scored,
            List<DynamicContextCompressor.ScoredContent> fallbackSelected,
            NovaOrchestrationProperties.RagCompressorProps cfg,
            boolean webEnabled,
            boolean ragEnabled) {
        List<DynamicContextCompressor.ScoredContent> fallback =
                fallbackSelected == null ? List.of() : List.copyOf(fallbackSelected);
        if (cfg == null || !cfg.isAnchorProbeEnabled()) {
            return selection(fallback, List.of(), List.of(), 0, false, false, false, "disabled", "DISABLED");
        }

        if ("anchor".equalsIgnoreCase(String.valueOf(cfg.getProbeMode()).trim())) {
            return selectAnchor(scored, fallback, cfg, webEnabled, ragEnabled);
        }
        return selectSpread(scored, fallback, cfg, webEnabled, ragEnabled);
    }

    private Selection selectAnchor(
            List<DynamicContextCompressor.ScoredContent> scored,
            List<DynamicContextCompressor.ScoredContent> fallback,
            NovaOrchestrationProperties.RagCompressorProps cfg,
            boolean webEnabled,
            boolean ragEnabled) {
        List<Integer> schedule = normalizedKSchedule(cfg.getAnchorProbeKSchedule(), DEFAULT_K_SCHEDULE);
        int finalMin = Math.max(1, cfg.getAnchorProbeFinalMinDocs());
        int finalMax = Math.max(finalMin, Math.max(1, cfg.getAnchorProbeFinalMaxDocs()));
        int lastStage = schedule.isEmpty() ? finalMax : schedule.get(schedule.size() - 1);
        int finalCap = Math.max(finalMin, Math.min(finalMax, Math.max(1, lastStage)));

        if (scored == null || scored.isEmpty()) {
            return selection(fallback, schedule, List.of(), finalCap, true, true, true, "empty_input", MODE_ANCHOR);
        }

        List<DynamicContextCompressor.ScoredContent> current = dedupeScored(scored, Integer.MAX_VALUE);
        List<Integer> stageCounts = new ArrayList<>(schedule.size());
        for (Integer rawK : schedule) {
            int cap = Math.max(1, rawK == null ? finalCap : rawK);
            int keep = Math.min(current.size(), cap);
            current = selectBalanced(current, keep, webEnabled, ragEnabled);
            current = dedupeScored(current, cap);
            stageCounts.add(current.size());
            if (current.isEmpty()) {
                break;
            }
        }

        if (current.isEmpty()) {
            return selection(fallback, schedule, List.copyOf(stageCounts), finalCap, true, true, true,
                    "empty_output_fallback", MODE_ANCHOR);
        }

        List<DynamicContextCompressor.ScoredContent> qualityOnly = new ArrayList<>();
        for (DynamicContextCompressor.ScoredContent candidate : current) {
            if (isEvidence(candidate)) {
                qualityOnly.add(candidate);
            }
        }
        if (qualityOnly.size() >= finalMin) {
            current = qualityOnly;
        }

        int keep = Math.min(current.size(), finalCap);
        List<DynamicContextCompressor.ScoredContent> selected = selectBalanced(current, keep, webEnabled, ragEnabled);
        if (selected.isEmpty() || selected.size() < finalMin || selected.stream().noneMatch(AnchorProbeHandler::isEvidence)) {
            return selection(fallback, schedule, List.copyOf(stageCounts), finalCap, true, true, true,
                    "no_anchor_or_citation_fallback", MODE_ANCHOR);
        }

        return selection(List.copyOf(selected), schedule, List.copyOf(stageCounts), finalCap, true, true, false,
                "staged_anchor_probe", MODE_ANCHOR);
    }

    private Selection selectSpread(
            List<DynamicContextCompressor.ScoredContent> scored,
            List<DynamicContextCompressor.ScoredContent> fallback,
            NovaOrchestrationProperties.RagCompressorProps cfg,
            boolean webEnabled,
            boolean ragEnabled) {
        List<Integer> schedule = normalizedKSchedule(cfg.getSpreadProbeKSchedule(), DEFAULT_SPREAD_K_SCHEDULE);
        int finalMin = Math.max(1, cfg.getSpreadProbeFinalMinDocs());
        int finalMax = Math.max(finalMin, Math.max(1, cfg.getSpreadProbeFinalMaxDocs()));
        int lastStage = schedule.isEmpty() ? finalMax : schedule.get(schedule.size() - 1);
        int finalCap = Math.max(finalMin, Math.min(finalMax, Math.max(1, lastStage)));

        if (scored == null || scored.isEmpty()) {
            return selection(fallback, schedule, List.of(), finalCap, true, true, true, "empty_input", MODE_SPREAD);
        }

        int initialCap = schedule.isEmpty() ? 64 : Math.max(finalCap, schedule.get(0));
        List<DynamicContextCompressor.ScoredContent> current = dedupeScored(scored, initialCap);
        List<Integer> stageCounts = new ArrayList<>(schedule.size());
        for (Integer rawK : schedule) {
            int cap = Math.max(1, rawK == null ? finalCap : rawK);
            if (current.size() <= cap) {
                stageCounts.add(current.size());
                if (current.isEmpty()) {
                    break;
                }
                continue;
            }
            int keep = Math.min(current.size(), cap);
            current = selectSpreadPortfolio(current, keep, webEnabled, ragEnabled, cfg.getMatrixTileMaxShare());
            current = dedupeScored(current, cap);
            stageCounts.add(current.size());
            if (current.isEmpty()) {
                break;
            }
        }

        if (current.isEmpty()) {
            return selection(fallback, schedule, List.copyOf(stageCounts), finalCap, true, true, true,
                    "empty_output_fallback", MODE_SPREAD);
        }

        List<DynamicContextCompressor.ScoredContent> qualityOnly = new ArrayList<>();
        for (DynamicContextCompressor.ScoredContent candidate : current) {
            if (isEvidence(candidate)) {
                qualityOnly.add(candidate);
            }
        }
        if (qualityOnly.size() >= finalMin) {
            current = qualityOnly;
        }

        int keep = Math.min(current.size(), finalCap);
        List<DynamicContextCompressor.ScoredContent> selected = selectSpreadPortfolio(current, keep, webEnabled, ragEnabled,
                cfg.getMatrixTileMaxShare());
        SpreadMetrics metrics = metrics(selected);
        double minDiversity = clamp01(cfg.getSpreadProbeMinAnchorDiversity());
        boolean enoughEvidence = selected.stream().anyMatch(AnchorProbeHandler::isEvidence);
        boolean diversityPossible = distinctAnchorKeys(current) > 1 || distinctBuckets(current) > 1;
        boolean diverseEnough = !diversityPossible || metrics.anchorDiversity() >= minDiversity;
        if (selected.isEmpty() || selected.size() < finalMin || !enoughEvidence || !diverseEnough) {
            return selection(fallback, schedule, List.copyOf(stageCounts), finalCap, true, true, true,
                    diverseEnough ? "spread_evidence_fallback" : "spread_diversity_fallback", MODE_SPREAD);
        }

        return selection(List.copyOf(selected), schedule, List.copyOf(stageCounts), finalCap, true, true, false,
                "staged_spread_probe", MODE_SPREAD);
    }

    private static Selection selection(
            List<DynamicContextCompressor.ScoredContent> selected,
            List<Integer> kSchedule,
            List<Integer> stageCounts,
            int finalCap,
            boolean enabled,
            boolean applied,
            boolean failSoft,
            String reason,
            String probeMode) {
        List<DynamicContextCompressor.ScoredContent> safeSelected =
                selected == null ? List.of() : List.copyOf(selected);
        SpreadMetrics metrics = metrics(safeSelected);
        return new Selection(
                safeSelected,
                kSchedule == null ? List.of() : List.copyOf(kSchedule),
                stageCounts == null ? List.of() : List.copyOf(stageCounts),
                finalCap,
                enabled,
                applied,
                failSoft,
                reason,
                probeMode,
                metrics.anchorDiversity(),
                metrics.authorityAvg(),
                metrics.noveltyAvg(),
                metrics.rerankConfidenceAvg());
    }

    private static List<DynamicContextCompressor.ScoredContent> selectSpreadPortfolio(
            List<DynamicContextCompressor.ScoredContent> scored,
            int keepN,
            boolean webEnabled,
            boolean ragEnabled,
            double matrixTileMaxShare) {
        List<DynamicContextCompressor.ScoredContent> selected = new ArrayList<>(Math.max(0, keepN));
        Set<DynamicContextCompressor.ScoredContent> seen = new LinkedHashSet<>();
        if (keepN <= 0 || scored == null || scored.isEmpty()) {
            return selected;
        }
        SpreadSelectionCache cache = new SpreadSelectionCache();
        if (webEnabled && ragEnabled && keepN > 1) {
            addBestBucket(scored, "web", selected, seen);
            addBestBucket(scored, "rag", selected, seen);
        }
        while (selected.size() < keepN) {
            DynamicContextCompressor.ScoredContent best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (DynamicContextCompressor.ScoredContent candidate : scored) {
                if (candidate == null || seen.contains(candidate)) {
                    continue;
                }
                double score = spreadMarginalScore(candidate, selected, cache, matrixTileMaxShare);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best == null) {
                break;
            }
            selected.add(best);
            seen.add(best);
        }
        selected.sort(Comparator
                .comparingDouble(DynamicContextCompressor.ScoredContent::score).reversed()
                .thenComparing(DynamicContextCompressor.ScoredContent::bucket)
                .thenComparingInt(DynamicContextCompressor.ScoredContent::bucketIndex));
        return selected;
    }

    private static double spreadMarginalScore(
            DynamicContextCompressor.ScoredContent candidate,
            List<DynamicContextCompressor.ScoredContent> selected,
            SpreadSelectionCache cache,
            double matrixTileMaxShare) {
        double base = 0.40d * clamp01(candidate.score())
                + 0.22d * clamp01(candidate.authorityScore())
                + 0.18d * clamp01(candidate.rerankConfidence())
                + 0.12d * clamp01(candidate.noveltyScore())
                + (isEvidence(candidate) ? 0.08d : 0.0d);
        double maxSimilarity = maxSimilarity(candidate, selected, cache);
        boolean newAnchor = !candidate.anchorKey().isBlank()
                && selected.stream().noneMatch(s -> candidate.anchorKey().equals(s.anchorKey()));
        boolean newBucket = selected.stream().noneMatch(s -> candidate.bucket().equals(s.bucket()));
        String candidateHost = cache.host(candidate);
        boolean sameHost = !candidateHost.isBlank()
                && selected.stream().anyMatch(s -> candidateHost.equalsIgnoreCase(cache.host(s)));
        double matrixTilePenalty = matrixTileSharePenalty(candidate, selected, matrixTileMaxShare);
        return (0.58d * base)
                - (0.42d * maxSimilarity)
                + (newAnchor ? 0.16d : -0.04d)
                + (newBucket ? 0.08d : 0.0d)
                - (sameHost ? 0.08d : 0.0d)
                - matrixTilePenalty;
    }

    private static double matrixTileSharePenalty(
            DynamicContextCompressor.ScoredContent candidate,
            List<DynamicContextCompressor.ScoredContent> selected,
            double matrixTileMaxShare) {
        if (candidate == null || dynamicGateStrongEvidence(candidate)) {
            return 0.0d;
        }
        String tile = matrixTileKey(candidate);
        if (tile.isBlank()) {
            return 0.0d;
        }
        double maxShare = clampRange(matrixTileMaxShare, 0.10d, 1.0d);
        int sameTile = 1;
        if (selected != null) {
            for (DynamicContextCompressor.ScoredContent existing : selected) {
                if (tile.equals(matrixTileKey(existing))) {
                    sameTile++;
                }
            }
        }
        int projectedTotal = (selected == null ? 0 : selected.size()) + 1;
        double projectedShare = sameTile / (double) Math.max(1, projectedTotal);
        return projectedShare > maxShare ? 0.22d : 0.0d;
    }

    private static double maxSimilarity(
            DynamicContextCompressor.ScoredContent candidate,
            List<DynamicContextCompressor.ScoredContent> selected,
            SpreadSelectionCache cache) {
        if (candidate == null || selected == null || selected.isEmpty()) {
            return 0.0d;
        }
        Set<String> candidateShingles = cache.shingles(candidate);
        double max = 0.0d;
        for (DynamicContextCompressor.ScoredContent existing : selected) {
            max = Math.max(max, setSimilarity(candidateShingles, cache.shingles(existing)));
        }
        return clamp01(max);
    }

    private static final class SpreadSelectionCache {
        private final Map<DynamicContextCompressor.ScoredContent, String> textCache = new IdentityHashMap<>();
        private final Map<DynamicContextCompressor.ScoredContent, Set<String>> shingleCache = new IdentityHashMap<>();
        private final Map<DynamicContextCompressor.ScoredContent, String> hostCache = new IdentityHashMap<>();

        Set<String> shingles(DynamicContextCompressor.ScoredContent candidate) {
            if (candidate == null) {
                return Set.of();
            }
            return shingleCache.computeIfAbsent(candidate, c -> AnchorProbeHandler.shingles(text(c), 3));
        }

        String host(DynamicContextCompressor.ScoredContent candidate) {
            if (candidate == null) {
                return "";
            }
            return hostCache.computeIfAbsent(candidate, c -> hostOf(c.content()));
        }

        private String text(DynamicContextCompressor.ScoredContent candidate) {
            if (candidate == null) {
                return "";
            }
            return textCache.computeIfAbsent(candidate, c -> textOf(c.content()));
        }
    }

    private static SpreadMetrics metrics(List<DynamicContextCompressor.ScoredContent> selected) {
        if (selected == null || selected.isEmpty()) {
            return new SpreadMetrics(0.0d, 0.0d, 0.0d, 0.0d);
        }
        double authority = 0.0d;
        double novelty = 0.0d;
        double confidence = 0.0d;
        for (DynamicContextCompressor.ScoredContent candidate : selected) {
            authority += clamp01(candidate.authorityScore());
            novelty += clamp01(candidate.noveltyScore());
            confidence += clamp01(candidate.rerankConfidence());
        }
        double n = selected.size();
        int denominator = Math.max(1, Math.min(5, selected.size()));
        double anchorRatio = distinctAnchorKeys(selected) / (double) denominator;
        double bucketRatio = distinctBuckets(selected) / (double) denominator;
        return new SpreadMetrics(
                round4(Math.max(anchorRatio, bucketRatio)),
                round4(authority / n),
                round4(novelty / n),
                round4(confidence / n));
    }

    private static int distinctAnchorKeys(List<DynamicContextCompressor.ScoredContent> selected) {
        if (selected == null || selected.isEmpty()) {
            return 0;
        }
        Set<String> keys = new LinkedHashSet<>();
        for (DynamicContextCompressor.ScoredContent candidate : selected) {
            if (candidate != null && candidate.anchorKey() != null && !candidate.anchorKey().isBlank()) {
                keys.add(candidate.anchorKey());
            }
        }
        return keys.size();
    }

    private static int distinctBuckets(List<DynamicContextCompressor.ScoredContent> selected) {
        if (selected == null || selected.isEmpty()) {
            return 0;
        }
        Set<String> buckets = new LinkedHashSet<>();
        for (DynamicContextCompressor.ScoredContent candidate : selected) {
            if (candidate != null && candidate.bucket() != null && !candidate.bucket().isBlank()) {
                buckets.add(candidate.bucket());
            }
        }
        return buckets.size();
    }

    public void trace(Selection selection, String query, int inputCount, int outputCount) {
        if (selection == null) {
            return;
        }
        try {
            String anchor = anchorForTrace(query);
            boolean spread = MODE_SPREAD.equals(selection.probeMode());
            int safeInputCount = Math.max(0, inputCount);
            int safeOutputCount = Math.max(0, outputCount);
            double reductionRatio = reductionRatio(safeInputCount, safeOutputCount);
            TraceStore.put("prompt.context.composer.anchorProbe.enabled", selection.enabled());
            TraceStore.put("prompt.context.composer.anchorProbe.applied", selection.applied());
            TraceStore.put("prompt.context.composer.anchorProbe.failSoft", selection.failSoft());
            TraceStore.put("prompt.context.composer.anchorProbe.reason",
                    SafeRedactor.traceLabelOrFallback(selection.reason(), ""));
            TraceStore.put("prompt.context.composer.anchorProbe.kSchedule", selection.kSchedule());
            TraceStore.put("prompt.context.composer.anchorProbe.stageCounts", selection.stageCounts());
            traceOverdriveStages(selection);
            TraceStore.put("prompt.context.composer.anchorProbe.finalCap", selection.finalCap());
            TraceStore.put("prompt.context.composer.anchorProbe.inputCount", safeInputCount);
            TraceStore.put("prompt.context.composer.anchorProbe.outputCount", safeOutputCount);
            TraceStore.put("prompt.context.composer.anchorProbe.reductionRatio", reductionRatio);
            TraceStore.put("prompt.context.composer.anchorProbe.anchor.hash", SafeRedactor.hash12(anchor));
            TraceStore.put("prompt.context.composer.anchorProbe.anchor.len", anchor == null ? 0 : anchor.length());
            TraceStore.put("prompt.context.composer.anchorProbe.probeMode", selection.probeMode());
            DynamicContextCompressor.traceQueryAuxMap(
                    TraceStore.getAll(),
                    "prompt.context.composer.anchorProbe.queryAuxMap");
            if (spread) {
                TraceStore.put("prompt.context.composer.spreadProbe.enabled", selection.enabled());
                TraceStore.put("prompt.context.composer.spreadProbe.applied", selection.applied());
                TraceStore.put("prompt.context.composer.spreadProbe.failSoft", selection.failSoft());
                TraceStore.put("prompt.context.composer.spreadProbe.reason",
                        SafeRedactor.traceLabelOrFallback(selection.reason(), ""));
                TraceStore.put("prompt.context.composer.spreadProbe.kSchedule", selection.kSchedule());
                TraceStore.put("prompt.context.composer.spreadProbe.stageCounts", selection.stageCounts());
                TraceStore.put("prompt.context.composer.spreadProbe.finalCap", selection.finalCap());
                TraceStore.put("prompt.context.composer.spreadProbe.inputCount", safeInputCount);
                TraceStore.put("prompt.context.composer.spreadProbe.outputCount", safeOutputCount);
                TraceStore.put("prompt.context.composer.spreadProbe.reductionRatio", reductionRatio);
                TraceStore.put("prompt.context.composer.spreadProbe.anchorDiversity", selection.anchorDiversity());
                TraceStore.put("prompt.context.composer.spreadProbe.authorityAvg", selection.authorityAvg());
                TraceStore.put("prompt.context.composer.spreadProbe.noveltyAvg", selection.noveltyAvg());
                TraceStore.put("prompt.context.composer.spreadProbe.rerankConfidenceAvg", selection.rerankConfidenceAvg());
                TraceStore.put("prompt.context.composer.spreadProbe.anchor.hashes", anchorKeyHashes(selection.selected()));
                DynamicContextCompressor.traceQueryAuxMap(
                        TraceStore.getAll(),
                        "prompt.context.composer.spreadProbe.queryAuxMap");
            }
        } catch (Throwable ignored) {
            traceSkipped("anchorProbe.trace", ignored);
        }
    }

    private static void traceOverdriveStages(Selection selection) {
        if (selection == null || selection.kSchedule() == null || selection.kSchedule().isEmpty()) {
            return;
        }
        List<Integer> counts = selection.stageCounts() == null ? List.of() : selection.stageCounts();
        int fallbackCount = selection.selected() == null ? 0 : selection.selected().size();
        for (int i = 0; i < selection.kSchedule().size(); i++) {
            Integer stage = selection.kSchedule().get(i);
            if (stage == null || stage <= 0) {
                continue;
            }
            int count = i < counts.size() && counts.get(i) != null
                    ? Math.max(0, counts.get(i))
                    : Math.max(0, fallbackCount);
            TraceStore.put("overdrive.compress.stage." + stage, count);
        }
    }

    private List<Integer> normalizedKSchedule(List<Integer> raw, List<Integer> defaultSchedule) {
        List<Integer> source = raw == null || raw.isEmpty() ? defaultSchedule : raw;
        List<Integer> out = new ArrayList<>();
        int previous = Integer.MAX_VALUE;
        for (Integer value : source) {
            if (value == null || value <= 0) {
                continue;
            }
            int k = Math.max(1, Math.min(value, previous));
            out.add(k);
            previous = k;
            if (out.size() >= MAX_SCHEDULE_STAGES) {
                break;
            }
        }
        if (out.isEmpty()) {
            out.addAll(defaultSchedule == null || defaultSchedule.isEmpty() ? DEFAULT_K_SCHEDULE : defaultSchedule);
        }
        return List.copyOf(out);
    }

    private static List<String> anchorKeyHashes(List<DynamicContextCompressor.ScoredContent> selected) {
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        List<String> hashes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DynamicContextCompressor.ScoredContent candidate : selected) {
            String key = candidate == null ? "" : candidate.anchorKey();
            if (key == null || key.isBlank() || !seen.add(key)) {
                continue;
            }
            hashes.add(SafeRedactor.hash12(key));
        }
        return List.copyOf(hashes);
    }

    private static List<DynamicContextCompressor.ScoredContent> dedupeScored(
            List<DynamicContextCompressor.ScoredContent> scored,
            int maxItems) {
        if (scored == null || scored.isEmpty()) {
            return List.of();
        }
        int cap = maxItems <= 0 ? Integer.MAX_VALUE : maxItems;
        Map<String, DynamicContextCompressor.ScoredContent> unique = new LinkedHashMap<>();
        Map<String, Integer> perHost = new HashMap<>();
        for (DynamicContextCompressor.ScoredContent candidate : scored) {
            if (candidate == null || candidate.content() == null) {
                continue;
            }
            String key = canonicalEvidenceKey(candidate.content());
            if (key.isBlank() || unique.containsKey(key)) {
                continue;
            }
            String host = hostOf(candidate.content()).toLowerCase(Locale.ROOT);
            if (!host.isBlank()) {
                int n = perHost.getOrDefault(host, 0);
                if (n >= HOST_CAP) {
                    continue;
                }
                perHost.put(host, n + 1);
            }
            unique.put(key, candidate);
            if (unique.size() >= cap) {
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static List<DynamicContextCompressor.ScoredContent> selectBalanced(
            List<DynamicContextCompressor.ScoredContent> scored,
            int keepN,
            boolean webEnabled,
            boolean ragEnabled) {
        List<DynamicContextCompressor.ScoredContent> selected = new ArrayList<>(Math.max(0, keepN));
        Set<DynamicContextCompressor.ScoredContent> seen = new LinkedHashSet<>();
        if (keepN <= 0 || scored == null || scored.isEmpty()) {
            return selected;
        }
        if (webEnabled && ragEnabled && keepN > 1) {
            addBestBucket(scored, "web", selected, seen);
            addBestBucket(scored, "rag", selected, seen);
        }
        for (DynamicContextCompressor.ScoredContent candidate : scored) {
            if (selected.size() >= keepN) {
                break;
            }
            if (seen.add(candidate)) {
                selected.add(candidate);
            }
        }
        selected.sort(Comparator
                .comparingDouble(DynamicContextCompressor.ScoredContent::score).reversed()
                .thenComparing(DynamicContextCompressor.ScoredContent::bucket)
                .thenComparingInt(DynamicContextCompressor.ScoredContent::bucketIndex));
        return selected;
    }

    private static void addBestBucket(
            List<DynamicContextCompressor.ScoredContent> scored,
            String bucket,
            List<DynamicContextCompressor.ScoredContent> selected,
            Set<DynamicContextCompressor.ScoredContent> seen) {
        for (DynamicContextCompressor.ScoredContent candidate : scored) {
            if (bucket.equals(candidate.bucket()) && seen.add(candidate)) {
                selected.add(candidate);
                return;
            }
        }
    }

    private static boolean isEvidence(DynamicContextCompressor.ScoredContent candidate) {
        return candidate != null && (candidate.anchorHit() || hasCitationMetadata(candidate.content()));
    }

    private static boolean dynamicGateStrongEvidence(DynamicContextCompressor.ScoredContent candidate) {
        if (candidate == null) {
            return false;
        }
        return candidate.anchorHit()
                || (candidate.authorityScore() >= 0.85d && candidate.rerankConfidence() >= 0.70d)
                || (candidate.authorityScore() >= 0.75d && candidate.rerankConfidence() >= 0.85d);
    }

    private static String matrixTileKey(DynamicContextCompressor.ScoredContent candidate) {
        if (candidate == null || candidate.content() == null) {
            return "";
        }
        Object raw = metadataOf(candidate.content()).get("branch_quality_matrix_tile");
        if (raw == null) {
            return "";
        }
        try {
            int tile = (int) Math.round(Double.parseDouble(String.valueOf(raw).trim()));
            return "t" + Math.max(0, Math.min(8, tile));
        } catch (NumberFormatException ignore) {
            traceSkipped("anchorProbe.matrixTileKey", ignore);
            return "";
        }
    }

    private String anchorForTrace(String query) {
        try {
            AnchorNarrower.Anchor anchor = anchorNarrower.pick(query, List.of(), List.of());
            if (anchor != null && anchor.term() != null && !anchor.term().isBlank()) {
                return anchor.term();
            }
        } catch (Throwable ignored) {
            traceSkipped("anchorProbe.anchorForTrace", ignored);
            // Use local fallback below.
        }
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return "";
        }
        String normalized = q.replaceAll("[\\p{Punct}]+", " ");
        String best = "";
        for (String token : normalized.split("\\s+")) {
            String t = token == null ? "" : token.trim();
            if (t.length() > best.length()) {
                best = t;
            }
        }
        return best.isBlank() ? q : (best.length() > 64 ? best.substring(0, 64) : best);
    }

    private static boolean hasCitationMetadata(Content content) {
        Map<String, Object> meta = metadataOf(content);
        return !strongCitationIdentifier(meta).isBlank();
    }

    private static String canonicalEvidenceKey(Content content) {
        Map<String, Object> meta = metadataOf(content);
        String source = firstNonBlank(
                value(meta, "url"),
                value(meta, "URL"),
                value(meta, "sourceUrl"),
                value(meta, "source_url"),
                value(meta, "link"),
                value(meta, "href"),
                value(meta, "canonical"),
                value(meta, "permalink"),
                value(meta, "doc_id"),
                value(meta, "docId"));
        if (source != null && !source.isBlank()) {
            return "src:" + source.trim().toLowerCase(Locale.ROOT);
        }
        String sourceLabel = value(meta, "source");
        if (isStrongSourceValue(sourceLabel)) {
            return "src:" + sourceLabel.trim().toLowerCase(Locale.ROOT);
        }
        return "txt:" + normalizeForDedupe(textOf(content));
    }

    private static String strongCitationIdentifier(Map<String, Object> meta) {
        String direct = firstNonBlank(
                value(meta, "url"),
                value(meta, "URL"),
                value(meta, "sourceUrl"),
                value(meta, "source_url"),
                value(meta, "link"),
                value(meta, "href"),
                value(meta, "canonical"),
                value(meta, "permalink"),
                value(meta, "doc_id"),
                value(meta, "docId"));
        if (!direct.isBlank()) {
            return direct;
        }
        String source = value(meta, "source");
        return isStrongSourceValue(source) ? source : "";
    }

    private static boolean isStrongSourceValue(String value) {
        String s = value == null ? "" : value.trim();
        if (s.isBlank()) {
            return false;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("://")
                || lower.startsWith("www.")
                || lower.contains("/")
                || lower.contains("\\")
                || lower.matches("^[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}(:\\d+)?(/.*)?$");
    }

    private static String hostOf(Content c) {
        Map<String, Object> meta = metadataOf(c);
        Object u = firstNonBlank(meta, "url", "URL", "sourceUrl", "source_url", "link", "href", "canonical",
                "permalink");
        if (u == null) {
            return "";
        }
        String s = String.valueOf(u);
        if (s.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(s);
            String host = uri.getHost();
            return host == null ? "" : host;
        } catch (Exception ignore) {
            traceSkipped("anchorProbe.hostOf", ignore);
            return s;
        }
    }

    private static Map<String, Object> metadataOf(Content content) {
        if (content == null || content.textSegment() == null) {
            return new HashMap<>();
        }
        return safeMetadata(content.textSegment().metadata());
    }

    private static Map<String, Object> safeMetadata(Object raw) {
        if (raw instanceof Metadata metadata) {
            try {
                return new HashMap<>(metadata.toMap());
            } catch (Exception ignore) {
                traceSkipped("anchorProbe.metadataCopy", ignore);
                return new HashMap<>();
            }
        }
        if (!(raw instanceof Map<?, ?> m)) {
            return new HashMap<>();
        }
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    private static String normalizeForDedupe(String text) {
        if (text == null) {
            return "";
        }
        String s = text
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (s.length() > 240) {
            s = s.substring(0, 240);
        }
        return s;
    }

    private static String textOf(Content c) {
        if (c == null) {
            return "";
        }
        if (c.textSegment() != null && c.textSegment().text() != null) {
            return c.textSegment().text();
        }
        return String.valueOf(c);
    }

    private static Set<String> shingles(String text, int n) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        int width = Math.max(2, n);
        if (normalized.length() <= width) {
            out.add(normalized);
            return out;
        }
        for (int i = 0; i <= normalized.length() - width; i++) {
            out.add(normalized.substring(i, i + width));
        }
        return out;
    }

    private static double setSimilarity(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0d;
        }
        int intersection = 0;
        for (String item : left) {
            if (right.contains(item)) {
                intersection++;
            }
        }
        return intersection / Math.sqrt((double) left.size() * (double) right.size());
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double clampRange(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double round4(double value) {
        return Math.round(clamp01(value) * 10000.0d) / 10000.0d;
    }

    private static double reductionRatio(int inputCount, int outputCount) {
        if (inputCount <= 0) {
            return 0.0d;
        }
        return round4(1.0d - (Math.max(0, outputCount) / (double) inputCount));
    }

    private static String value(Map<String, Object> meta, String key) {
        Object raw = meta == null || key == null ? null : meta.get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Object firstNonBlank(Map<String, Object> meta, String... keys) {
        if (meta == null || keys == null) {
            return null;
        }
        for (String k : keys) {
            if (k == null) {
                continue;
            }
            Object v = meta.get(k);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v);
            if (!s.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static void traceSkipped(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        try {
            TraceStore.put("prompt.context.composer.anchorProbe.suppressed.stage", safeStage);
            TraceStore.put("prompt.context.composer.anchorProbe.suppressed.errorType", errorType);
            TraceStore.put("prompt.context.composer.anchorProbe.suppressed." + safeStage, true);
            TraceStore.put("prompt.context.composer.anchorProbe.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Anchor probe trace skipped stage=" + safeStage
                            + " errorType=" + traceFailure.getClass().getSimpleName());
        }
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
