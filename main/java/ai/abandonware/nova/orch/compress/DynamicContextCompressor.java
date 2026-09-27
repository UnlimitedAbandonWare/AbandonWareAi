package ai.abandonware.nova.orch.compress;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Comparator;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * RAG context compressor used in strike/compression modes to reduce prompt size
 * and
 * mitigate LLM timeouts.
 *
 * <p>
 * Design goals:
 * <ul>
 * <li>Fail-soft: if anything goes wrong, return the original list.</li>
 * <li>Keep metadata: url/title/provider etc should be preserved.</li>
 * <li>Keep at least 1 doc when input is non-empty.</li>
 * <li>Never fabricate documents or call external providers; this only reshapes
 * already-collected candidates before PromptBuilder renders them.</li>
 * </ul>
 */
public class DynamicContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(DynamicContextCompressor.class);
    private static final String COMPOSER_VERSION = "ablation-spread-v2";
    private static final double GOLDEN_RATIO_PHI = 0.618d;

    private final NovaOrchestrationProperties props;
    private final AnchorProbeHandler anchorProbeHandler;

    public DynamicContextCompressor(NovaOrchestrationProperties props) {
        this(props, new AnchorProbeHandler());
    }

    public DynamicContextCompressor(NovaOrchestrationProperties props, AnchorProbeHandler anchorProbeHandler) {
        this.props = props;
        this.anchorProbeHandler = anchorProbeHandler == null ? new AnchorProbeHandler() : anchorProbeHandler;
    }

    public record PromptContextComposition(
            List<Content> web,
            List<Content> rag,
            CompositionDecision decision,
            String contextRefinementSummary,
            Map<String, Double> contextRefinementSignals) {

        public PromptContextComposition(List<Content> web, List<Content> rag, CompositionDecision decision) {
            this(web, rag, decision, "", Map.of());
        }

        public PromptContextComposition {
            contextRefinementSummary = contextRefinementSummary == null ? "" : contextRefinementSummary.trim();
            contextRefinementSignals = contextRefinementSignals == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(contextRefinementSignals));
        }
    }

    public record CompositionDecision(
            boolean enabled,
            boolean activated,
            String reason,
            double pressureScore,
            String topFactor,
            String anchorHash,
            int anchorLen,
            int inputWebCount,
            int inputRagCount,
            int outputWebCount,
            int outputRagCount,
            Map<String, Integer> dropCounts,
            boolean failSoft) {

        public Map<String, Object> toTraceMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", COMPOSER_VERSION);
            out.put("enabled", enabled);
            out.put("activated", activated);
            out.put("reason", safeLabel(reason));
            out.put("pressureScore", pressureScore);
            out.put("topFactor", topFactor);
            out.put("anchorHash", anchorHash);
            out.put("anchorLen", anchorLen);
            out.put("inputWebCount", inputWebCount);
            out.put("inputRagCount", inputRagCount);
            out.put("outputWebCount", outputWebCount);
            out.put("outputRagCount", outputRagCount);
            out.put("dropCounts", dropCounts == null ? Map.of() : dropCounts);
            out.put("failSoft", failSoft);
            return out;
        }
    }

    private record ComponentRisk(String topFactor, double topProbability) {
    }

    private record FactorDelta(String factor, double delta) {
    }

    private record MemoryLine(int index, String text, double score, double contaminationScore) {
    }

    private record AppendScoredStats(int accepted, int skippedPromptIneligible) {
    }

    private record DynamicGateResult(
            List<ScoredContent> scored,
            Map<String, Integer> matrixTileCounts,
            int suppressedCount,
            int demotedCount,
            boolean enabled,
            boolean applied,
            String reason) {
    }

    private record BranchQualitySignals(
            String branchId,
            String intentAxis,
            double contextContribution,
            double duplicateRatio,
            double riskPenalty,
            double rrfWeight,
            String action,
            Boolean promptEligible,
            double laneConfidence,
            double strongCitationRate,
            double crossLaneSupportRate,
            double grandasReadiness) {

        static BranchQualitySignals from(Content content) {
            Map<String, Object> meta = metadataOf(content);
            return new BranchQualitySignals(
                    firstNonBlank(value(meta, "branch_quality_branch_id"), value(meta, "branchId"), value(meta, "retrieval_lane")),
                    firstNonBlank(value(meta, "branch_quality_intent_axis"), value(meta, "intentAxis"), value(meta, "retrieval_lane_role")),
                    firstMetadataDouble(meta, "branch_quality_context_contribution"),
                    firstMetadataDouble(meta, "branch_quality_duplicate_ratio", "selfask_lane_gate_duplicate_rate"),
                    firstMetadataDouble(meta, "branch_quality_risk_penalty"),
                    firstMetadataDouble(meta, "branch_quality_rrf_weight", "selfask_lane_gate_grandas_weight"),
                    firstNonBlank(value(meta, "branch_quality_action"), value(meta, "selfask_lane_gate_action")),
                    explicitBoolean(meta.get("promptEligible")),
                    firstMetadataDouble(meta, "selfask_lane_gate_confidence"),
                    firstMetadataDouble(meta, "selfask_lane_gate_strong_citation_rate"),
                    firstMetadataDouble(meta, "selfask_lane_gate_cross_lane_support_rate"),
                    firstMetadataDouble(meta, "selfask_lane_gate_grandas_readiness"));
        }

        boolean promptIneligible() {
            return Boolean.FALSE.equals(promptEligible)
                    || "SUPPRESS".equalsIgnoreCase(action == null ? "" : action.trim());
        }

        double selectionPrior() {
            double contribution = positive(contextContribution);
            double rrf = rrfWeight >= 0.0d ? clamp01(rrfWeight / 1.25d) : 0.0d;
            double laneQuality = max(
                    positive(laneConfidence),
                    positive(strongCitationRate),
                    positive(crossLaneSupportRate),
                    positive(grandasReadiness));
            return clamp01((0.10d * contribution) + (0.06d * rrf) + (0.04d * laneQuality));
        }

        double selectionPenalty() {
            double risk = positive(riskPenalty);
            double duplicate = positive(duplicateRatio);
            double actionPenalty = "SHRINK".equalsIgnoreCase(action == null ? "" : action.trim()) ? 0.04d : 0.0d;
            return clamp01((0.16d * risk) + (0.06d * duplicate) + actionPenalty);
        }

        private static double positive(double value) {
            return value < 0.0d ? 0.0d : clamp01(value);
        }
    }

    private record ContextRefinerDecision(
            boolean enabled,
            boolean activated,
            String reason,
            String disabledReason,
            int candidateCount,
            String selectedCandidate,
            boolean providerDisabled,
            boolean failSoft,
            double contaminationScore,
            double phi,
            double boltzmannTemp,
            Map<String, Double> signals) {
    }

    private record ContextRefinerGateDecision(boolean evidencePresent, boolean passed, String reason) {
    }

    record ScoredContent(
            Content content,
            String bucket,
            int bucketIndex,
            double score,
            boolean anchorHit,
            double penalty,
            String penaltyFactor,
            String anchorKey,
            double authorityScore,
            double noveltyScore,
            double rerankConfidence) {
    }

    public PromptContextComposition composeForPrompt(String query, List<Content> webDocs, List<Content> ragDocs) {
        int inputWebCount = sizeOf(webDocs);
        int inputRagCount = sizeOf(ragDocs);
        NovaOrchestrationProperties.RagCompressorProps cfg = props != null ? props.getRagCompressor() : null;
        ContextRefinerDecision refinerDecision = contextRefinerDecision(cfg, TraceStore.getAll());
        traceContextRefiner(refinerDecision);
        boolean enabled = cfg != null && cfg.isEnabled() && cfg.isAblationGuidedEnabled();
        if (!enabled) {
            CompositionDecision decision = decision(false, false, "disabled", 0.0d, "none", query,
                    inputWebCount, inputRagCount, inputWebCount, inputRagCount, Map.of(), false);
            tracePromptComposition(decision);
            return promptComposition(webDocs, ragDocs, decision, refinerDecision);
        }
        if (inputWebCount + inputRagCount <= 0) {
            CompositionDecision decision = decision(true, false, "empty_input", 0.0d, "none", query,
                    inputWebCount, inputRagCount, inputWebCount, inputRagCount, Map.of(), false);
            tracePromptComposition(decision);
            return promptComposition(webDocs, ragDocs, decision, refinerDecision);
        }

        try {
            Map<String, Object> trace = TraceStore.getAll();
            traceQueryAuxMap(trace, "prompt.context.composer.queryAuxMap");
            ComponentRisk componentRisk = componentRisk(trace);
            double blackboxRisk = maxDouble(trace, "blackbox.risk.riskScore", "blackbox.risk.priorityScore");
            double traceAnchorPressure = max(
                    maxDouble(trace, "ablation.traceAnchor.maxExpectedDelta", "ablation.traceAnchor.routeCorrectionNeed"),
                    nestedDouble(trace, "blackbox.risk.traceAnchor", "routeCorrectionNeed"),
                    nestedDouble(trace, "blackbox.risk.matrix", "q_anchor_drop_pressure"),
                    nestedDouble(trace, "blackbox.risk.matrix", "q_route_correction_need"));
            double overdriveScore = toDouble(trace.get("overdrive.score"), 0.0d);
            double promptOverflow = promptOverflowRatio(webDocs, ragDocs, cfg.getTargetChars());
            boolean starvation = starvationFlag(trace);
            double pressure = clamp01(max(blackboxRisk, componentRisk.topProbability(), overdriveScore,
                    traceAnchorPressure, promptOverflow, starvation ? 0.75d : 0.0d));
            double threshold = clamp01(cfg.getAblationPressureThreshold());
            boolean activated = pressure >= threshold || promptOverflow > 0.0d || traceBoolean(trace, "rag.compress.applied");
            String topFactor = firstNonBlank(nonNone(componentRisk.topFactor()),
                    nonNone(nestedString(trace, "blackbox.risk.traceAnchor", "routeHint")),
                    safeLabel(trace.get("blackbox.risk.dominantFailure")),
                    safeLabel(trace.get("blackbox.risk.hotspot")),
                    "none");
            if (!activated) {
                CompositionDecision decision = decision(true, false, "below_threshold", pressure, topFactor, query,
                        inputWebCount, inputRagCount, inputWebCount, inputRagCount, Map.of(), false);
                tracePromptComposition(decision);
                return promptComposition(webDocs, ragDocs, decision, refinerDecision);
            }

            String anchor = anchorFrom(query);
            List<ScoredContent> scored = new ArrayList<>(inputWebCount + inputRagCount);
            AppendScoredStats webStats = appendScored(scored, webDocs, "web", anchor, topFactor, trace);
            AppendScoredStats ragStats = appendScored(scored, ragDocs, "rag", anchor, topFactor, trace);
            if (scored.isEmpty()) {
                int skippedPromptIneligible = webStats.skippedPromptIneligible() + ragStats.skippedPromptIneligible();
                if (skippedPromptIneligible > 0) {
                    CompositionDecision decision = decision(true, true, "all_prompt_ineligible", pressure, topFactor,
                            query, inputWebCount, inputRagCount, inputWebCount, inputRagCount,
                            Map.of("promptIneligible", skippedPromptIneligible), true);
                    tracePromptComposition(decision);
                    tracePromptIneligibleProbe(cfg, inputWebCount + inputRagCount,
                            inputWebCount + inputRagCount, skippedPromptIneligible);
                    return promptComposition(webDocs, ragDocs, decision, refinerDecision);
                }
                CompositionDecision decision = decision(true, false, "no_valid_candidates", pressure, topFactor, query,
                        inputWebCount, inputRagCount, inputWebCount, inputRagCount, Map.of(), true);
                tracePromptComposition(decision);
                return promptComposition(webDocs, ragDocs, decision, refinerDecision);
            }

            DynamicGateResult dynamicGate = dynamicGateCandidates(scored, cfg);
            scored = new ArrayList<>(dynamicGate.scored());
            traceDynamicGate(dynamicGate);
            if (scored.isEmpty()) {
                CompositionDecision decision = decision(true, true, "dynamic_gate_empty_output_original_returned",
                        pressure, topFactor, query, inputWebCount, inputRagCount, inputWebCount, inputRagCount,
                        Map.of("dynamicGateSuppressed", dynamicGate.suppressedCount()), true);
                tracePromptComposition(decision);
                return promptComposition(webDocs, ragDocs, decision, refinerDecision);
            }

            scored.sort(Comparator
                    .comparingDouble(ScoredContent::score).reversed()
                    .thenComparing(ScoredContent::bucket)
                    .thenComparingInt(ScoredContent::bucketIndex));

            int total = scored.size();
            int maxKeep = Math.min(
                    Math.max(1, cfg.getMaxContents()),
                    Math.max(1, cfg.getMaxDocs()));
            int minKeep = Math.max(1, cfg.getMinDocs());
            int keepN = Math.min(total, Math.max(minKeep, maxKeep));

            List<ScoredContent> baselineSelected = selectBalanced(scored, keepN, inputWebCount > 0, inputRagCount > 0);
            AnchorProbeHandler.Selection anchorProbe = anchorProbeHandler.select(scored, baselineSelected, cfg,
                    inputWebCount > 0, inputRagCount > 0);
            List<ScoredContent> selected = anchorProbe.selected();
            boolean anchorProbeApplied = anchorProbe.applied() && !anchorProbe.failSoft();
            List<Content> outWeb = new ArrayList<>();
            List<Content> outRag = new ArrayList<>();
            int penalized = 0;
            for (ScoredContent candidate : selected) {
                if (candidate.penalty() > 0.0d) {
                    penalized++;
                }
                Content annotated = prepareForPrompt(candidate, anchor, cfg,
                        anchorProbeApplied, anchorProbe.finalCap(), anchorProbeApplied, anchorProbe.probeMode());
                if ("web".equals(candidate.bucket())) {
                    outWeb.add(annotated);
                } else {
                    outRag.add(annotated);
                }
            }

            if (selected.isEmpty()) {
                CompositionDecision decision = decision(true, true, "empty_output_original_returned", pressure, topFactor,
                        query, inputWebCount, inputRagCount, inputWebCount, inputRagCount, Map.of(), true);
                tracePromptComposition(decision);
                anchorProbeHandler.trace(anchorProbe, query, total, 0);
                return promptComposition(webDocs, ragDocs, decision, refinerDecision);
            }

            Map<String, Integer> dropCounts = new LinkedHashMap<>();
            dropCounts.put("web", Math.max(0, inputWebCount - outWeb.size()));
            dropCounts.put("rag", Math.max(0, inputRagCount - outRag.size()));
            dropCounts.put("penalized", Math.max(0, penalized));

            String reason = reasonFor(pressure, promptOverflow, starvation, topFactor);
            CompositionDecision decision = decision(true, true, reason, pressure, topFactor, query,
                    inputWebCount, inputRagCount, outWeb.size(), outRag.size(), dropCounts, false);
            tracePromptComposition(decision);
            anchorProbeHandler.trace(anchorProbe, query, total, selected.size());
            return promptComposition(webDocs == null ? null : outWeb, ragDocs == null ? null : outRag,
                    decision, refinerDecision);
        } catch (Exception e) {
            CompositionDecision decision = decision(true, false, "exception_original_returned", 0.0d, "exception", query,
                    inputWebCount, inputRagCount, inputWebCount, inputRagCount, Map.of(), true);
            tracePromptComposition(decision);
            TraceStore.put("prompt.context.composer.exception", "prompt_context_composer_failed");
            log.debug("[DynamicContextCompressor] composeForPrompt fail-soft errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return promptComposition(webDocs, ragDocs, decision, refinerDecision);
        }
    }

    public String compressMemoryForPrompt(String query, String memoryCtx) {
        int inputLen = memoryCtx == null ? 0 : memoryCtx.length();
        NovaOrchestrationProperties.RagCompressorProps cfg = props != null ? props.getRagCompressor() : null;
        boolean enabled = cfg != null && cfg.isEnabled() && cfg.isMemoryEnabled();
        if (!enabled) {
            traceMemoryCompression(false, false, "disabled", query, inputLen, inputLen, 0, 0.0d);
            return memoryCtx;
        }
        if (memoryCtx == null || memoryCtx.isBlank()) {
            traceMemoryCompression(true, false, "empty_input", query, inputLen, inputLen, 0, 0.0d);
            return memoryCtx;
        }
        try {
            String normalized = memoryCtx.replace("\r\n", "\n").replace('\r', '\n');
            String[] rawLines = normalized.split("\n", -1);
            int maxLines = Math.max(1, cfg.getMemoryMaxLines());
            int maxChars = Math.max(200, cfg.getMemoryMaxChars());
            double threshold = clamp01(cfg.getMemoryContaminationThreshold());
            String anchor = anchorFrom(query);

            List<MemoryLine> candidates = new ArrayList<>();
            int dropped = 0;
            double contaminationMax = 0.0d;
            for (int i = 0; i < rawLines.length; i++) {
                String line = rawLines[i] == null ? "" : rawLines[i].trim();
                if (line.isBlank()) {
                    continue;
                }
                double contamination = memoryLineContaminationScore(line);
                contaminationMax = Math.max(contaminationMax, contamination);
                if (contamination >= Math.max(0.65d, threshold + 0.25d)) {
                    dropped++;
                    continue;
                }
                boolean anchorHit = containsAnchor(line, anchor);
                double score = (anchorHit ? 2.0d : 0.0d)
                        + (isMemoryHeaderLine(line) ? 0.65d : 0.0d)
                        + Math.min(0.35d, line.length() / 600.0d)
                        - contamination;
                candidates.add(new MemoryLine(i, clipMemoryLine(line), score, contamination));
            }

            boolean overflow = inputLen > maxChars || rawLines.length > maxLines;
            boolean contaminated = contaminationMax >= threshold || dropped > 0;
            boolean activated = overflow || contaminated;
            if (!activated) {
                traceMemoryCompression(true, false, "below_threshold", query, inputLen, inputLen,
                        Math.max(0, rawLines.length - candidates.size()), contaminationMax);
                return memoryCtx;
            }
            if (candidates.isEmpty()) {
                traceMemoryCompression(true, true, "all_lines_dropped", query, inputLen, 0,
                        Math.max(0, rawLines.length), contaminationMax);
                return "";
            }

            candidates.sort(Comparator
                    .comparingDouble(MemoryLine::score).reversed()
                    .thenComparingInt(MemoryLine::index));
            List<MemoryLine> selected = new ArrayList<>();
            int chars = 0;
            for (MemoryLine line : candidates) {
                int nextChars = chars + line.text().length() + (selected.isEmpty() ? 0 : 1);
                if (selected.size() >= maxLines || nextChars > maxChars) {
                    dropped++;
                    continue;
                }
                selected.add(line);
                chars = nextChars;
            }
            if (selected.isEmpty()) {
                MemoryLine first = candidates.get(0);
                selected.add(new MemoryLine(first.index(), trimMemoryLine(first.text(), maxChars),
                        first.score(), first.contaminationScore()));
            }
            selected.sort(Comparator.comparingInt(MemoryLine::index));
            String out = String.join("\n", selected.stream().map(MemoryLine::text).toList());
            traceMemoryCompression(true, true, reasonForMemoryCompression(overflow, contaminated), query,
                    inputLen, out.length(), Math.max(0, rawLines.length - selected.size()), contaminationMax);
            return out;
        } catch (Exception ex) {
            traceMemoryCompression(true, false, "exception_original_returned", query, inputLen, inputLen, 0, 0.0d);
            TraceStore.put("prompt.memory.compressor.exception", "memory_compressor_failed");
            log.debug("[DynamicContextCompressor] compressMemoryForPrompt fail-soft errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
            return memoryCtx;
        }
    }

    public List<Content> compress(String anchor, List<Content> docs) {
        NovaOrchestrationProperties.RagCompressorProps cfg = props != null ? props.getRagCompressor() : null;
        int maxContents = (cfg != null ? cfg.getMaxContents() : 8);
        int maxCharsPerContent = (cfg != null ? cfg.getMaxCharsPerContent() : 800);
        int anchorWindowChars = (cfg != null ? cfg.getAnchorWindowChars() : 220);
        return compress(anchor, docs, maxContents, maxCharsPerContent, anchorWindowChars);
    }

    public List<Content> compress(
            String anchor,
            List<Content> docs,
            int maxContents,
            int maxCharsPerContent,
            int anchorWindowChars) {
        if (docs == null || docs.isEmpty()) {
            traceCompression(anchor, docs == null ? 0 : docs.size(), 0, false, "empty_input");
            return docs;
        }
        try {
            String a = anchor == null ? "" : anchor.trim();
            List<Content> sorted = new ArrayList<>(docs);
            sorted.sort((x, y) -> {
                boolean xHas = containsAnchor(textOf(x), a);
                boolean yHas = containsAnchor(textOf(y), a);
                return Boolean.compare(yHas, xHas);
            });

            int keepN = Math.max(1, maxContents);
            int perDocChars = Math.max(80, maxCharsPerContent);
            int windowChars = Math.max(0, anchorWindowChars);

            List<Content> out = new ArrayList<>(Math.min(keepN, sorted.size()));
            Map<String, Integer> perHostCount = new HashMap<>();
            Set<String> seenText = new HashSet<>();

            for (Content c : sorted) {
                if (c == null) {
                    continue;
                }
                if (out.size() >= keepN) {
                    break;
                }

                String host = hostOf(c);
                if (!host.isBlank()) {
                    int n = perHostCount.getOrDefault(host, 0);
                    if (n >= 2) {
                        continue;
                    }
                }

                String text = textOf(c);
                if (text.isBlank()) {
                    continue;
                }
                String normKey = normalizeForDedupe(text);
                if (!seenText.add(normKey)) {
                    continue;
                }

                String trimmed = trimText(text, a, perDocChars, windowChars);
                Content rebuilt = rebuild(c, trimmed, text, a);

                out.add(rebuilt);
                if (!host.isBlank()) {
                    perHostCount.put(host, perHostCount.getOrDefault(host, 0) + 1);
                }
            }
            boolean failSoft = out.isEmpty();
            List<Content> result = failSoft ? docs : out;
            traceCompression(a, docs.size(), result == null ? 0 : result.size(), failSoft,
                    failSoft ? "empty_output_original_returned" : "compressed");
            return result;
        } catch (Exception e) {
            traceCompression(anchor, docs.size(), docs.size(), true, "exception_original_returned");
            TraceStore.put("overdrive.compress.error", e.getClass().getSimpleName());
            TraceStore.put("compress.exception", "compress_failed");
            log.debug("[DynamicContextCompressor] compress fail-soft errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return docs;
        }
    }

    private static AppendScoredStats appendScored(
            List<ScoredContent> out,
            List<Content> docs,
            String bucket,
            String anchor,
            String topFactor,
            Map<String, Object> trace) {
        if (docs == null || docs.isEmpty()) {
            return new AppendScoredStats(0, 0);
        }
        int index = 0;
        int accepted = 0;
        int skippedPromptIneligible = 0;
        for (Content content : docs) {
            if (content == null || textOf(content).isBlank()) {
                index++;
                continue;
            }
            BranchQualitySignals branchQuality = BranchQualitySignals.from(content);
            if (branchQuality.promptIneligible()) {
                skippedPromptIneligible++;
                index++;
                continue;
            }
            boolean anchorHit = containsAnchor(textOf(content), anchor) || containsAnchor(metadataText(content), anchor);
            double baseRank = 1.0d / (1.0d + index);
            double novelty = noveltyScore(content);
            double authority = authorityScore(content, bucket);
            double rerankConfidence = rerankConfidence(content);
            double anchorCoverage = anchorHit ? 1.0d : 0.0d;
            String factor = normalizedFactor(topFactor, trace);
            double branchPenalty = branchQuality.selectionPenalty();
            double penalty = componentPenalty(factor, bucket, content) + branchPenalty;
            double rawScore = (0.25d * clamp01(baseRank))
                    + (0.25d * authority)
                    + (0.20d * rerankConfidence)
                    + (0.20d * novelty)
                    + (0.10d * anchorCoverage)
                    + branchQuality.selectionPrior()
                    - penalty;
            double score = softClamp01(rawScore);
            out.add(new ScoredContent(content, bucket, index, score, anchorHit, penalty,
                    penalty > 0.0d ? (branchPenalty > 0.0d ? "branch_quality_risk" : factor) : "none",
                    anchorKeyFor(content, anchor),
                    authority,
                    novelty,
                    rerankConfidence));
            accepted++;
            index++;
        }
        return new AppendScoredStats(accepted, skippedPromptIneligible);
    }

    private static DynamicGateResult dynamicGateCandidates(
            List<ScoredContent> scored,
            NovaOrchestrationProperties.RagCompressorProps cfg) {
        List<ScoredContent> safe = scored == null ? List.of() : scored;
        Map<String, Integer> tileCounts = matrixTileCounts(safe);
        boolean enabled = cfg != null && cfg.isDynamicGateEnabled();
        if (!enabled) {
            return new DynamicGateResult(new ArrayList<>(safe), tileCounts, 0, 0, false, false, "disabled");
        }
        if (safe.isEmpty()) {
            return new DynamicGateResult(List.of(), tileCounts, 0, 0, true, false, "empty_input");
        }

        double maxShare = clampRange(cfg.getMatrixTileMaxShare(), 0.10d, 1.0d);
        double noiseThreshold = clamp01(cfg.getNoiseRiskThreshold());
        int totalWithTile = tileCounts.entrySet().stream()
                .filter(e -> !"unknown".equals(e.getKey()))
                .mapToInt(Map.Entry::getValue)
                .sum();
        Set<String> overLimitTiles = new HashSet<>();
        if (totalWithTile > 0) {
            for (Map.Entry<String, Integer> entry : tileCounts.entrySet()) {
                if ("unknown".equals(entry.getKey())) {
                    continue;
                }
                double share = entry.getValue() / (double) totalWithTile;
                if (share > maxShare) {
                    overLimitTiles.add(entry.getKey());
                }
            }
        }

        int minRemaining = Math.max(1, cfg.getMinDocs());
        List<ScoredContent> out = new ArrayList<>(safe.size());
        int suppressed = 0;
        int demoted = 0;
        for (ScoredContent candidate : safe) {
            if (candidate == null) {
                continue;
            }
            String tile = matrixTileKey(candidate);
            boolean overTile = !tile.isBlank() && overLimitTiles.contains(tile);
            double risk = dynamicGateNoiseRisk(candidate);
            boolean strong = dynamicGateStrongEvidence(candidate);
            boolean promptEligible = dynamicGatePromptEligible(candidate);
            boolean suppressible = !promptEligible || (!strong && risk >= noiseThreshold);
            int remainingIfSuppressed = safe.size() - suppressed - 1;
            if (suppressible && remainingIfSuppressed >= minRemaining) {
                suppressed++;
                continue;
            }
            if (!strong && (overTile || risk >= noiseThreshold)) {
                out.add(dynamicGateDemote(candidate, overTile, risk));
                demoted++;
            } else {
                out.add(candidate);
            }
        }

        boolean applied = suppressed > 0 || demoted > 0;
        String reason;
        if (applied && suppressed > 0 && demoted > 0) {
            reason = "noise_suppressed_tile_demoted";
        } else if (applied && suppressed > 0) {
            reason = "noise_suppressed";
        } else if (applied) {
            reason = overLimitTiles.isEmpty() ? "noise_demoted" : "matrix_tile_share_demoted";
        } else if (tileCounts.isEmpty() || (tileCounts.size() == 1 && tileCounts.containsKey("unknown"))) {
            reason = "no_matrix_tile_signal";
        } else {
            reason = "balanced";
        }
        return new DynamicGateResult(out, tileCounts, suppressed, demoted, true, applied, reason);
    }

    private static ScoredContent dynamicGateDemote(ScoredContent candidate, boolean overTile, double risk) {
        double riskPenalty = clamp01(risk < 0.0d ? 0.0d : risk);
        double delta = overTile ? 0.20d : 0.12d;
        delta += 0.22d * riskPenalty;
        return new ScoredContent(
                candidate.content(),
                candidate.bucket(),
                candidate.bucketIndex(),
                softClamp01(candidate.score() - delta),
                candidate.anchorHit(),
                candidate.penalty() + delta,
                overTile ? "dynamic_gate_matrix_tile" : "dynamic_gate_noise",
                candidate.anchorKey(),
                candidate.authorityScore(),
                candidate.noveltyScore(),
                candidate.rerankConfidence());
    }

    private static Map<String, Integer> matrixTileCounts(List<ScoredContent> scored) {
        if (scored == null || scored.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ScoredContent candidate : scored) {
            String tile = matrixTileKey(candidate);
            if (tile.isBlank()) {
                tile = "unknown";
            }
            counts.merge(tile, 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    private static String matrixTileKey(ScoredContent candidate) {
        if (candidate == null || candidate.content() == null) {
            return "";
        }
        Map<String, Object> meta = metadataOf(candidate.content());
        Object raw = meta.get("branch_quality_matrix_tile");
        if (raw == null) {
            return "";
        }
        try {
            int tile = (int) Math.round(Double.parseDouble(String.valueOf(raw).trim()));
            return "t" + Math.max(0, Math.min(8, tile));
        } catch (NumberFormatException ignore) {
            traceSkipped("dynamicCompressor.matrixTileKey", ignore);
            return "";
        }
    }

    private static double dynamicGateNoiseRisk(ScoredContent candidate) {
        if (candidate == null || candidate.content() == null) {
            return 0.0d;
        }
        Map<String, Object> meta = metadataOf(candidate.content());
        double riskPenalty = firstMetadataDouble(meta, "branch_quality_risk_penalty");
        double duplicateRatio = firstMetadataDouble(meta, "branch_quality_duplicate_ratio");
        return clamp01(max(riskPenalty, duplicateRatio, 0.0d));
    }

    private static boolean dynamicGatePromptEligible(ScoredContent candidate) {
        if (candidate == null || candidate.content() == null) {
            return false;
        }
        Object raw = metadataOf(candidate.content()).get("promptEligible");
        return raw == null || Boolean.parseBoolean(String.valueOf(raw));
    }

    private static boolean dynamicGateStrongEvidence(ScoredContent candidate) {
        if (candidate == null) {
            return false;
        }
        return candidate.anchorHit()
                || (candidate.authorityScore() >= 0.85d && candidate.rerankConfidence() >= 0.70d)
                || (candidate.authorityScore() >= 0.75d && candidate.rerankConfidence() >= 0.85d);
    }

    private static List<ScoredContent> selectBalanced(
            List<ScoredContent> scored,
            int keepN,
            boolean webEnabled,
            boolean ragEnabled) {
        List<ScoredContent> selected = new ArrayList<>(Math.max(0, keepN));
        Set<ScoredContent> seen = new LinkedHashSet<>();
        if (keepN <= 0 || scored == null || scored.isEmpty()) {
            return selected;
        }
        if (webEnabled && ragEnabled && keepN > 1) {
            addBestBucket(scored, "web", selected, seen);
            addBestBucket(scored, "rag", selected, seen);
        }
        for (ScoredContent candidate : scored) {
            if (selected.size() >= keepN) {
                break;
            }
            if (seen.add(candidate)) {
                selected.add(candidate);
            }
        }
        selected.sort(Comparator
                .comparingDouble(ScoredContent::score).reversed()
                .thenComparing(ScoredContent::bucket)
                .thenComparingInt(ScoredContent::bucketIndex));
        return selected;
    }

    private static void addBestBucket(
            List<ScoredContent> scored,
            String bucket,
            List<ScoredContent> selected,
            Set<ScoredContent> seen) {
        for (ScoredContent candidate : scored) {
            if (bucket.equals(candidate.bucket()) && seen.add(candidate)) {
                selected.add(candidate);
                return;
            }
        }
    }

    private static Content prepareForPrompt(
            ScoredContent candidate,
            String anchor,
            NovaOrchestrationProperties.RagCompressorProps cfg,
            boolean anchorProbeApplied,
            int anchorProbeFinalCap,
            boolean finalEvidence,
            String probeMode) {
        Content original = candidate.content();
        String text = textOf(original);
        Map<String, Object> meta = metadataOf(original);
        boolean alreadyCompressed = "true".equalsIgnoreCase(String.valueOf(meta.get("_nova.compressed")));
        String newText = text;
        if (!alreadyCompressed) {
            int perDocChars = Math.max(80, cfg != null ? cfg.getMaxCharsPerContent() : 800);
            int windowChars = Math.max(0, cfg != null ? cfg.getAnchorWindowChars() : 220);
            newText = trimText(text, anchor, perDocChars, windowChars);
            if (!Objects.equals(newText, text)) {
                putOriginalFingerprint(meta, text);
                meta.put("_nova.compressed", "true");
            }
        }
        putAnchorFingerprint(meta, anchor);
        meta.put("_nova.compositionScore", round4(candidate.score()));
        meta.put("_nova.compositionReason", "SPREAD_PROBE".equals(probeMode) ? "ablation_spread" : "ablation_anchor");
        meta.put("_nova.sourceBucket", candidate.bucket());
        meta.put("_nova.anchorHit", String.valueOf(candidate.anchorHit()));
        meta.put("_nova.penaltyFactor", candidate.penaltyFactor());
        if (candidate.anchorKey() != null && !candidate.anchorKey().isBlank()) {
            meta.put("_nova.anchorKeyHash", safeHash(candidate.anchorKey()));
        }
        meta.put("_nova.authorityScore", round4(candidate.authorityScore()));
        meta.put("_nova.noveltyScore", round4(candidate.noveltyScore()));
        meta.put("_nova.rerankConfidence", round4(candidate.rerankConfidence()));
        if (anchorProbeApplied) {
            meta.put("_nova.probeMode", firstNonBlank(probeMode, "ANCHOR_PROBE"));
            meta.put("_nova.kStage", Math.max(1, anchorProbeFinalCap));
            meta.put("_nova.finalEvidence", String.valueOf(finalEvidence));
        }
        return Content.from(TextSegment.from(newText, Metadata.from(langchainMetadata(meta))));
    }

    private static void putOriginalFingerprint(Map<String, Object> meta, String originalText) {
        if (meta == null || originalText == null || originalText.isBlank()) {
            return;
        }
        meta.putIfAbsent("_nova.origHash", safeHash(originalText));
        meta.putIfAbsent("_nova.origLen", originalText.length());
    }

    private static void putAnchorFingerprint(Map<String, Object> meta, String anchor) {
        if (meta == null || anchor == null || anchor.isBlank()) {
            return;
        }
        meta.putIfAbsent("_nova.anchorHash", safeHash(anchor));
        meta.putIfAbsent("_nova.anchorLen", anchor.length());
    }

    private static ComponentRisk componentRisk(Map<String, Object> trace) {
        List<FactorDelta> deltas = new ArrayList<>();
        collectFactorDeltas(deltas, trace == null ? null : trace.get("ablation.probabilities"));
        collectFactorDeltas(deltas, trace == null ? null : trace.get("orch.debug.ablation.strike"));
        collectFactorDeltas(deltas, trace == null ? null : trace.get("orch.debug.ablation.bypass"));
        if (deltas.isEmpty()) {
            return new ComponentRisk("none", 0.0d);
        }
        double temperature = boltzmannTemperature(trace);
        double maxDelta = deltas.stream().mapToDouble(FactorDelta::delta).max().orElse(0.0d);
        double denom = 0.0d;
        for (FactorDelta delta : deltas) {
            denom += Math.exp((delta.delta() - maxDelta) / temperature);
        }
        String topFactor = "none";
        double topProb = 0.0d;
        for (FactorDelta delta : deltas) {
            double p = denom <= 0.0d ? 0.0d : Math.exp((delta.delta() - maxDelta) / temperature) / denom;
            if (p > topProb) {
                topProb = p;
                topFactor = delta.factor();
            }
        }
        return new ComponentRisk(topFactor, clamp01(topProb));
    }

    @SuppressWarnings("unchecked")
    private static void collectFactorDeltas(List<FactorDelta> out, Object raw) {
        if (out == null || raw == null) {
            return;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectFactorDeltas(out, item);
            }
            return;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        Map<Object, Object> m = (Map<Object, Object>) map;
        String factor = firstNonBlank(
                safeLabel(m.get("factor")),
                safeLabel(m.get("guard")),
                safeLabel(m.get("failureClass")),
                safeLabel(m.get("label")),
                safeLabel(m.get("step")),
                "unknown");
        double delta = max(
                toDouble(m.get("deltaProb"), -1.0d),
                toDouble(m.get("delta"), -1.0d),
                toDouble(m.get("p"), -1.0d));
        if (delta > 0.0d) {
            out.add(new FactorDelta(factor, delta));
        }
    }

    private static double boltzmannTemperature(Map<String, Object> trace) {
        double eventCount = toDouble(trace == null ? null : trace.get("ablation.events.count"), 0.0d);
        if (eventCount <= 0.0d) {
            return 0.75d;
        }
        double t = 1.0d / (Math.log(3.0d + eventCount) / Math.log(2.0d));
        return Math.max(0.20d, Math.min(0.75d, t));
    }

    private static ContextRefinerDecision contextRefinerDecision(
            NovaOrchestrationProperties.RagCompressorProps cfg,
            Map<String, Object> trace) {
        boolean enabled = cfg != null && cfg.isContextRefinerEnabled();
        Map<String, Double> signals = contextRefinerSignals(trace);
        double contaminationScore = signals.getOrDefault("contextContamination", 0.0d);
        double boltzmannTemp = boltzmannTemperature(trace);
        if (!enabled) {
            return new ContextRefinerDecision(false, false, "disabled", "", 0, "none",
                    false, false, contaminationScore, GOLDEN_RATIO_PHI, boltzmannTemp, signals);
        }
        int candidateCount = contextRefinerCandidateCount(trace);
        if (candidateCount <= 0) {
            return new ContextRefinerDecision(true, false, "provider_disabled",
                    contextRefinerDisabledReason(trace), 0, "none",
                    true, true, contaminationScore, GOLDEN_RATIO_PHI, boltzmannTemp, signals);
        }
        boolean contaminated = contaminationScore >= 0.65d;
        if (contaminated) {
            return new ContextRefinerDecision(true, false, "contamination_risk", "",
                    Math.min(candidateCount, 3), "none",
                    false, true, contaminationScore, GOLDEN_RATIO_PHI, boltzmannTemp, signals);
        }
        ContextRefinerGateDecision gateDecision = contextRefinerGateDecision(trace);
        if (!gateDecision.evidencePresent() || !gateDecision.passed()) {
            return new ContextRefinerDecision(true, false, gateDecision.reason(), "",
                    Math.min(candidateCount, 3), "none",
                    false, true, contaminationScore, GOLDEN_RATIO_PHI, boltzmannTemp, signals);
        }
        return new ContextRefinerDecision(true, true, "selected", "",
                Math.min(candidateCount, 3), "ensemble_trace_best",
                false, false, contaminationScore, GOLDEN_RATIO_PHI, boltzmannTemp, signals);
    }

    private static String contextRefinerDisabledReason(Map<String, Object> trace) {
        String raw = firstTraceLabel(trace,
                "ensemble.refiner.disabledReason",
                "ensemble.sampling.skipped",
                "ensemble.bypass.reason",
                "ensemble.judge.skipped",
                "ensemble.judge.fail",
                "openai.models.disabledReason",
                "llm.disabledReason",
                "model.disabledReason");
        String reason = normalizeContextRefinerDisabledReason(raw);
        return reason.isBlank() ? "model_unavailable" : reason;
    }

    private static String normalizeContextRefinerDisabledReason(String raw) {
        String label = safeLabel(raw).toLowerCase(Locale.ROOT);
        if (label.isBlank()) {
            return "";
        }
        if ((label.contains("missing") && label.contains("key")) || label.contains("no_key")) {
            return "missing_key";
        }
        if (label.contains("disabled")) {
            return "provider_disabled";
        }
        if (label.contains("failed") || label.contains("fail")
                || label.contains("blank") || label.contains("unavailable")
                || label.contains("no_candidates")) {
            return "model_unavailable";
        }
        return "provider_disabled";
    }

    private static ContextRefinerGateDecision contextRefinerGateDecision(Map<String, Object> trace) {
        Boolean citationPassed = firstExplicitBoolean(trace,
                "gate.citation.passed",
                "citation.pass",
                "citationPass",
                "citation.gate.pass",
                "rag.evidence.promotion.citationGateMinPassed",
                "rag.evidence.promotion.citationGateSoftPassed");
        Boolean finalGatePassed = contextRefinerFinalGatePassed(trace);
        if (citationPassed == null || finalGatePassed == null) {
            return new ContextRefinerGateDecision(false, false, "gate_evidence_missing");
        }
        if (!citationPassed) {
            return new ContextRefinerGateDecision(true, false, "citation_gate_failed");
        }
        if (!finalGatePassed) {
            return new ContextRefinerGateDecision(true, false, "final_gate_failed");
        }
        return new ContextRefinerGateDecision(true, true, "selected");
    }

    private static Boolean contextRefinerFinalGatePassed(Map<String, Object> trace) {
        Boolean explicit = firstExplicitBoolean(trace,
                "gate.sigmoid.passed",
                "hypernova.finalGatePassed");
        String result = firstTraceLabel(trace,
                "gate.finalSigmoid.result",
                "retrieval.finalSigmoidGate.result",
                "finalSigmoidGate.result");
        if (!result.isBlank()) {
            return "PASS".equalsIgnoreCase(result);
        }
        return explicit;
    }

    private static Boolean firstExplicitBoolean(Map<String, Object> trace, String... keys) {
        if (trace == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || !trace.containsKey(key)) {
                continue;
            }
            Boolean value = explicitBoolean(trace.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstTraceLabel(Map<String, Object> trace, String... keys) {
        if (trace == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (key == null || !trace.containsKey(key)) {
                continue;
            }
            String label = safeLabel(trace.get(key));
            if (!label.isBlank()) {
                return label;
            }
        }
        return "";
    }

    private static int contextRefinerCandidateCount(Map<String, Object> trace) {
        double count = maxDouble(trace,
                "ensemble.refiner.candidateCount",
                "ensemble.candidates.count",
                "ensemble.judge.candidates.count");
        return (int) Math.max(0L, Math.round(count));
    }

    private static double contextRefinerContaminationScore(Map<String, Object> trace) {
        return clamp01(maxDouble(trace,
                "contextContamination",
                "context.contamination",
                "learning.validation.contaminationScore",
                "prompt.memory.compressor.contaminationScore"));
    }

    private static Map<String, Double> contextRefinerSignals(Map<String, Object> trace) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("needleKeptRatio", clamp01(maxDouble(trace, "needle.keptRatio")));
        out.put("tailSignal", clamp01(maxDouble(trace, "tailSignal", "hypernova.tailSignal",
                "selfask.laneGate.tailSignal")));
        out.put("contextContamination", contextRefinerContaminationScore(trace));
        out.put("afterFilterStarvation", starvationFlag(trace)
                ? 1.0d
                : clamp01(maxDouble(trace, "afterFilterStarvation", "rag.eval.afterFilterStarvation")));
        out.put("cfvmBoltzmannWeight", clamp01(maxDouble(trace, "cfvm.boltzmannWeight",
                "cfvm.failureRecovery.boltzmannWeight", "failurePattern.memory.boltzmann.weight")));
        out.put("hypernovaCvarPhi", clamp01(maxDouble(trace, "hypernova.cvarPhi")));
        return Collections.unmodifiableMap(out);
    }

    private static PromptContextComposition promptComposition(
            List<Content> webDocs,
            List<Content> ragDocs,
            CompositionDecision decision,
            ContextRefinerDecision refinerDecision) {
        return new PromptContextComposition(
                webDocs,
                ragDocs,
                decision,
                contextRefinementSummary(refinerDecision),
                roundedSignalMap(refinerDecision == null ? null : refinerDecision.signals()));
    }

    private static String contextRefinementSummary(ContextRefinerDecision decision) {
        if (decision == null || !decision.enabled()) {
            return "";
        }
        return "reason=" + safeLabel(decision.reason())
                + ",disabledReason=" + safeLabel(decision.disabledReason())
                + ",candidates=" + Math.max(0, decision.candidateCount())
                + ",selected=" + safeLabel(decision.selectedCandidate())
                + ",providerDisabled=" + decision.providerDisabled()
                + ",failSoft=" + decision.failSoft()
                + ",contamination=" + round4(decision.contaminationScore())
                + ",phi=" + round4(decision.phi())
                + ",boltzmannTemp=" + round4(decision.boltzmannTemp());
    }

    private static double promptOverflowRatio(List<Content> webDocs, List<Content> ragDocs, int targetChars) {
        int target = Math.max(1, targetChars);
        int chars = totalChars(webDocs) + totalChars(ragDocs);
        if (chars <= target) {
            return 0.0d;
        }
        return clamp01((chars - target) / (double) target);
    }

    private static int totalChars(List<Content> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Content doc : docs) {
            total += textOf(doc).length();
        }
        return total;
    }

    private static boolean starvationFlag(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        Object signals = trace.get("rag.eval.afterFilterStarvationSignals");
        if (signals instanceof Iterable<?> iterable) {
            return iterable.iterator().hasNext();
        }
        if (signals != null && !String.valueOf(signals).isBlank() && !"[]".equals(String.valueOf(signals).trim())) {
            return true;
        }
        String failure = safeLabel(trace.get("blackbox.risk.dominantFailure")).toLowerCase(Locale.ROOT);
        String hotspot = safeLabel(trace.get("blackbox.risk.hotspot")).toLowerCase(Locale.ROOT);
        return failure.contains("starvation") || hotspot.contains("starvation")
                || traceBoolean(trace, "web.failsoft.starvationFallback.used")
                || traceBoolean(trace, "starvationFallback.used");
    }

    private static double componentPenalty(String factor, String bucket, Content content) {
        String f = factor == null ? "" : factor.toLowerCase(Locale.ROOT);
        String meta = metadataText(content).toLowerCase(Locale.ROOT);
        if (f.contains("missing_future") || f.contains("webrate") || f.contains("webboth")
                || f.contains("web_both") || f.contains("provider") || f.contains("rate-limit")
                || f.contains("rate_limit") || f.contains("after_filter") || f.contains("starvation")) {
            return "web".equals(bucket) ? 0.42d : 0.08d;
        }
        if (f.contains("vector") || f.contains("quarantine") || f.contains("contamination")) {
            return "rag".equals(bucket) ? 0.42d : 0.08d;
        }
        if (f.contains("qtx") || f.contains("qtopen") || f.contains("query") || f.contains("transform")
                || f.contains("modelrequired")) {
            return meta.contains("rewrite") || meta.contains("transform") || meta.contains("qtx") ? 0.35d : 0.08d;
        }
        if (f.contains("citation") || f.contains("final")) {
            return hasCitationMetadata(content) ? 0.04d : 0.25d;
        }
        return 0.0d;
    }

    private static double authorityScore(Content content, String bucket) {
        Map<String, Object> meta = metadataOf(content);
        double explicit = firstMetadataDouble(meta,
                "_nova.authorityScore",
                "authorityScore",
                "authority_score",
                "authorityAvg",
                "sourceAuthority",
                "domainAuthority");
        if (explicit >= 0.0d) {
            return clamp01(explicit);
        }
        String host = hostOf(content).toLowerCase(Locale.ROOT);
        if (host.endsWith(".gov") || host.endsWith(".edu") || host.contains(".ac.")
                || host.contains("docs.") || host.contains("developer.") || host.contains("spring.io")) {
            return 0.85d;
        }
        if (hasCitationMetadata(content)) {
            return 0.68d;
        }
        if ("rag".equals(bucket)) {
            return 0.55d;
        }
        return 0.35d;
    }

    private static double noveltyScore(Content content) {
        Map<String, Object> meta = metadataOf(content);
        double explicit = firstMetadataDouble(meta,
                "_nova.noveltyScore",
                "noveltyScore",
                "novelty_score",
                "contextDiversity",
                "context_diversity");
        if (explicit >= 0.0d) {
            return clamp01(explicit);
        }
        double duplicateRatio = firstMetadataDouble(meta, "duplicateRate", "duplicate_rate");
        if (duplicateRatio >= 0.0d) {
            return clamp01(1.0d - duplicateRatio);
        }
        double lengthSignal = Math.min(0.30d, Math.max(0.0d, textOf(content).length() / 4000.0d));
        return clamp01(0.45d + lengthSignal + (hasCitationMetadata(content) ? 0.08d : 0.0d));
    }

    private static double rerankConfidence(Content content) {
        Map<String, Object> meta = metadataOf(content);
        double explicit = firstMetadataDouble(meta,
                "_nova.rerankConfidence",
                "rerankConfidence",
                "rerank_confidence",
                "grandasReadiness",
                "grandas_adjusted_score",
                "grandas_base_score",
                "crossEncoderScore",
                "onnxScore",
                "relevanceScore",
                "score");
        if (explicit >= 0.0d) {
            return clamp01(explicit);
        }
        return hasCitationMetadata(content) ? 0.50d : 0.35d;
    }

    private static String anchorKeyFor(Content content, String anchor) {
        Map<String, Object> meta = metadataOf(content);
        String explicit = firstNonBlank(
                value(meta, "_nova.anchorKey"),
                value(meta, "anchorKey"),
                value(meta, "branch_quality_intent_axis"),
                value(meta, "branch_quality_branch_id"),
                value(meta, "retrieval_lane_role"),
                value(meta, "retrieval_lane"),
                value(meta, "intentAxis"),
                value(meta, "branchId"),
                value(meta, "matrixTile"),
                value(meta, "title"));
        String key = significantToken(explicit, anchor);
        if (!key.isBlank()) {
            return key;
        }
        key = significantToken(textOf(content), anchor);
        if (!key.isBlank()) {
            return key;
        }
        String host = hostOf(content);
        return host == null ? "" : safeLabel(host.toLowerCase(Locale.ROOT));
    }

    private static String significantToken(String text, String anchor) {
        String s = text == null ? "" : text.replaceAll("[\\p{Punct}]+", " ");
        String anchorNorm = normalizeAnchorKey(anchor);
        String best = "";
        for (String token : s.split("\\s+")) {
            String t = normalizeAnchorKey(token);
            if (t.length() < 3 || isStopToken(t) || t.equals(anchorNorm)) {
                continue;
            }
            if (t.length() > best.length()) {
                best = t;
            }
            if (best.length() >= 24) {
                break;
            }
        }
        return best.length() > 64 ? best.substring(0, 64) : best;
    }

    private static String normalizeAnchorKey(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}_:-]", "")
                .trim();
    }

    private static double firstMetadataDouble(Map<String, Object> meta, String... keys) {
        if (meta == null || keys == null) {
            return -1.0d;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object raw = meta.get(key);
            if (raw == null) {
                continue;
            }
            double value = toDouble(raw, -1.0d);
            if (value >= 0.0d) {
                return value;
            }
        }
        return -1.0d;
    }

    private static double softClamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        double bounded = Math.max(-2.0d, Math.min(2.0d, value));
        return clamp01(0.5d + (Math.tanh((bounded - 0.5d) * 1.6d) / 2.0d));
    }

    private static String normalizedFactor(String topFactor, Map<String, Object> trace) {
        return firstNonBlank(
                nonNone(safeLabel(topFactor)),
                safeLabel(trace == null ? null : trace.get("blackbox.risk.dominantFailure")),
                safeLabel(trace == null ? null : trace.get("blackbox.risk.hotspot")),
                "none");
    }

    private static String reasonFor(double pressure, double overflow, boolean starvation, String topFactor) {
        if (overflow > 0.0d) {
            return "prompt_overflow";
        }
        if (starvation) {
            return "after_filter_starvation";
        }
        String factor = safeLabel(topFactor);
        if (!factor.isBlank() && !"none".equalsIgnoreCase(factor)) {
            return "ablation:" + factor;
        }
        return pressure > 0.0d ? "pressure" : "activated";
    }

    private static CompositionDecision decision(
            boolean enabled,
            boolean activated,
            String reason,
            double pressure,
            String topFactor,
            String query,
            int inputWebCount,
            int inputRagCount,
            int outputWebCount,
            int outputRagCount,
            Map<String, Integer> dropCounts,
            boolean failSoft) {
        String anchor = anchorFrom(query);
        return new CompositionDecision(
                enabled,
                activated,
                safeLabel(reason),
                round4(pressure),
                safeLabel(topFactor),
                safeHash(anchor),
                anchor == null ? 0 : anchor.length(),
                Math.max(0, inputWebCount),
                Math.max(0, inputRagCount),
                Math.max(0, outputWebCount),
                Math.max(0, outputRagCount),
                dropCounts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(dropCounts)),
                failSoft);
    }

    private static void tracePromptComposition(CompositionDecision decision) {
        if (decision == null) {
            return;
        }
        try {
            TraceStore.put("prompt.context.composer.version", COMPOSER_VERSION);
            TraceStore.put("prompt.context.composer.enabled", decision.enabled());
            TraceStore.put("prompt.context.composer.activated", decision.activated());
            TraceStore.put("prompt.context.composer.reason", safeLabel(decision.reason()));
            TraceStore.put("prompt.context.composer.pressureScore", decision.pressureScore());
            TraceStore.put("prompt.context.composer.anchor.hash", decision.anchorHash());
            TraceStore.put("prompt.context.composer.anchor.len", decision.anchorLen());
            TraceStore.put("prompt.context.composer.topFactor", decision.topFactor());
            TraceStore.put("prompt.context.composer.input.webCount", decision.inputWebCount());
            TraceStore.put("prompt.context.composer.input.ragCount", decision.inputRagCount());
            TraceStore.put("prompt.context.composer.output.webCount", decision.outputWebCount());
            TraceStore.put("prompt.context.composer.output.ragCount", decision.outputRagCount());
            TraceStore.put("prompt.context.composer.dropCounts", decision.dropCounts());
            TraceStore.put("prompt.context.composer.failSoft", decision.failSoft());
            TraceStore.append("prompt.context.composer.events", decision.toTraceMap());
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("mode", decision.topFactor());
            input.put("requestedTopK", decision.inputWebCount() + decision.inputRagCount());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("returnedCount", decision.inputWebCount() + decision.inputRagCount());
            output.put("selectedCount", decision.outputWebCount() + decision.outputRagCount());
            output.put("dropCounts", decision.dropCounts());
            Map<String, Object> failure = new LinkedHashMap<>();
            if (decision.failSoft()) {
                failure.put("reasonCode", safeLabel(decision.reason()));
                failure.put("failureClass", "fallback");
            }
            Map<String, Object> control = new LinkedHashMap<>();
            if (decision.failSoft()) {
                control.put("action", "fail_soft_fallback");
                control.put("applied", true);
                control.put("reasonCode", safeLabel(decision.reason()));
            }
            OrchEventEmitter.ragEvent(
                    "rag.pipeline",
                    "compression",
                    "prompt_context",
                    "compose",
                    "DynamicContextCompressor",
                    decision.failSoft() ? "fallback" : (decision.activated() ? "ok" : "skipped"),
                    input,
                    output,
                    failure,
                    control);
        } catch (Throwable ignored) {
            traceSkipped("dynamicCompressor.promptContextEvent", ignored);
        }
    }

    private static void traceContextRefiner(ContextRefinerDecision decision) {
        if (decision == null) {
            return;
        }
        try {
            TraceStore.put("prompt.context.refiner.enabled", decision.enabled());
            TraceStore.put("prompt.context.refiner.activated", decision.activated());
            TraceStore.put("prompt.context.refiner.reason", safeLabel(decision.reason()));
            TraceStore.put("prompt.context.refiner.disabledReason", safeLabel(decision.disabledReason()));
            TraceStore.put("prompt.context.refiner.candidateCount", Math.max(0, decision.candidateCount()));
            TraceStore.put("prompt.context.refiner.selectedCandidate", safeLabel(decision.selectedCandidate()));
            TraceStore.put("prompt.context.refiner.providerDisabled", decision.providerDisabled());
            TraceStore.put("prompt.context.refiner.failSoft", decision.failSoft());
            TraceStore.put("prompt.context.refiner.contaminationScore", round4(decision.contaminationScore()));
            TraceStore.put("prompt.context.refiner.phi", round4(decision.phi()));
            TraceStore.put("prompt.context.refiner.boltzmannTemp", round4(decision.boltzmannTemp()));
            TraceStore.put("prompt.context.refiner.signals", roundedSignalMap(decision.signals()));
        } catch (Throwable ignored) {
            traceSkipped("dynamicCompressor.contextRefinerTrace", ignored);
        }
    }

    private static Map<String, Double> roundedSignalMap(Map<String, Double> signals) {
        if (signals == null || signals.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : signals.entrySet()) {
            out.put(safeLabel(entry.getKey()), round4(entry.getValue() == null ? 0.0d : entry.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static void tracePromptIneligibleProbe(
            NovaOrchestrationProperties.RagCompressorProps cfg,
            int inputCount,
            int outputCount,
            int skippedPromptIneligible) {
        try {
            String probeMode = cfg == null ? "SPREAD_PROBE" : String.valueOf(cfg.getProbeMode()).trim();
            boolean spread = !"anchor".equalsIgnoreCase(probeMode);
            String prefix = spread ? "prompt.context.composer.spreadProbe" : "prompt.context.composer.anchorProbe";
            TraceStore.put(prefix + ".enabled", true);
            TraceStore.put(prefix + ".applied", false);
            TraceStore.put(prefix + ".failSoft", true);
            TraceStore.put(prefix + ".reason", "all_prompt_ineligible");
            TraceStore.put(prefix + ".inputCount", Math.max(0, inputCount));
            TraceStore.put(prefix + ".outputCount", Math.max(0, outputCount));
            TraceStore.put(prefix + ".promptIneligibleCount", Math.max(0, skippedPromptIneligible));
        } catch (Throwable ignored) {
            traceSkipped("dynamicCompressor.promptIneligibleProbe", ignored);
        }
    }

    private static void traceDynamicGate(DynamicGateResult result) {
        if (result == null) {
            return;
        }
        try {
            TraceStore.put("prompt.context.composer.dynamicGate.enabled", result.enabled());
            TraceStore.put("prompt.context.composer.dynamicGate.applied", result.applied());
            TraceStore.put("prompt.context.composer.dynamicGate.reason", safeLabel(result.reason()));
            TraceStore.put("prompt.context.composer.dynamicGate.matrixTileCounts",
                    result.matrixTileCounts() == null ? Map.of() : result.matrixTileCounts());
            TraceStore.put("prompt.context.composer.dynamicGate.suppressedCount", Math.max(0, result.suppressedCount()));
            TraceStore.put("prompt.context.composer.dynamicGate.demotedCount", Math.max(0, result.demotedCount()));
        } catch (Throwable ignored) {
            traceSkipped("dynamicCompressor.dynamicGateTrace", ignored);
        }
    }

    static void traceQueryAuxMap(Map<String, Object> trace, String prefix) {
        if (trace == null || prefix == null || prefix.isBlank()
                || !trace.containsKey("retrieval.kg.brainState.queryAnchorMap.applied")) {
            return;
        }
        try {
            TraceStore.put(prefix + ".applied",
                    traceBoolean(trace, "retrieval.kg.brainState.queryAnchorMap.applied"));
            TraceStore.put(prefix + ".source", "kg.brainState");
            TraceStore.put(prefix + ".seedCount",
                    Math.max(0L, Math.round(toDouble(
                            trace.get("retrieval.kg.brainState.queryAnchorMap.seedCount"), 0.0d))));
            TraceStore.put(prefix + ".seedHashes",
                    safeQueryAuxMapSeedHashes(trace.get("retrieval.kg.brainState.queryAnchorMap.seedHashes")));
            TraceStore.put(prefix + ".reason",
                    safeLabel(trace.get("retrieval.kg.brainState.queryAnchorMap.reason")));
        } catch (Throwable ignored) {
            traceSkipped("dynamicCompressor.queryAuxMapTrace", ignored);
        }
    }

    private static List<String> safeQueryAuxMapSeedHashes(Object raw) {
        if (!(raw instanceof Iterable<?> values)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object value : values) {
            String hash = value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            if (!hash.matches("[0-9a-f]{12}") || !seen.add(hash)) {
                continue;
            }
            out.add(hash);
            if (out.size() >= 16) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static boolean containsAnchor(String text, String anchor) {
        if (text == null || text.isBlank() || anchor == null || anchor.isBlank()) {
            return false;
        }
        if (text.contains(anchor)) {
            return true;
        }
        // basic case-insensitive check for ASCII-ish queries
        String lt = text.toLowerCase();
        String la = anchor.toLowerCase();
        return lt.contains(la);
    }

    private static int sizeOf(List<Content> docs) {
        return docs == null ? 0 : docs.size();
    }

    private static double max(double... values) {
        double best = 0.0d;
        if (values == null) {
            return best;
        }
        for (double value : values) {
            if (!Double.isNaN(value) && !Double.isInfinite(value)) {
                best = Math.max(best, value);
            }
        }
        return best;
    }

    private static double maxDouble(Map<String, Object> trace, String... keys) {
        double best = 0.0d;
        if (trace == null || keys == null) {
            return best;
        }
        for (String key : keys) {
            best = Math.max(best, toDouble(trace.get(key), 0.0d));
        }
        return best;
    }

    private static double nestedDouble(Map<String, Object> trace, String mapKey, String valueKey) {
        if (trace == null || mapKey == null || valueKey == null) {
            return 0.0d;
        }
        Object raw = trace.get(mapKey);
        if (!(raw instanceof Map<?, ?> map)) {
            return 0.0d;
        }
        return toDouble(map.get(valueKey), 0.0d);
    }

    private static String nestedString(Map<String, Object> trace, String mapKey, String valueKey) {
        if (trace == null || mapKey == null || valueKey == null) {
            return "";
        }
        Object raw = trace.get(mapKey);
        if (!(raw instanceof Map<?, ?> map)) {
            return "";
        }
        return safeLabel(map.get(valueKey));
    }

    private static double toDouble(Object raw, double fallback) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw == null) {
            return fallback;
        }
        try {
            String s = String.valueOf(raw).trim();
            if (s.isBlank()) {
                return fallback;
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException ignore) {
            traceSkipped("dynamicCompressor.toDouble", ignore);
            return fallback;
        }
    }

    private static Boolean explicitBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        if (s.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        return null;
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
        return Math.round(clampFinite(value) * 10000.0d) / 10000.0d;
    }

    private static double clampFinite(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0.0d : value;
    }

    private static boolean traceBoolean(Map<String, Object> trace, String key) {
        if (trace == null || key == null) {
            return false;
        }
        Object raw = trace.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw != null && Boolean.parseBoolean(String.valueOf(raw));
    }

    private static String safeLabel(Object raw) {
        if (raw == null) {
            return "";
        }
        return SafeRedactor.traceLabelOrFallback(raw, "");
    }

    private static String nonNone(String value) {
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim())) {
            return "";
        }
        return value;
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

    private static String anchorFrom(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return "";
        }
        String normalized = q.replaceAll("[\\p{Punct}]+", " ");
        String best = "";
        double bestScore = Double.NEGATIVE_INFINITY;
        for (String token : normalized.split("\\s+")) {
            String t = token == null ? "" : token.trim();
            if (t.length() < 2 || isStopToken(t)) {
                continue;
            }
            double score = anchorTokenScore(t);
            if (score > bestScore || (Double.compare(score, bestScore) == 0 && t.length() > best.length())) {
                best = t;
                bestScore = score;
            }
        }
        if (best.isBlank()) {
            best = q.length() > 20 ? q.substring(0, 20) : q;
        }
        return best.length() > 32 ? best.substring(0, 32) : best;
    }

    private static boolean isStopToken(String token) {
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return Set.of("the", "and", "for", "with", "this", "that", "what", "how", "why",
                        "이다", "것이", "하는", "있는", "위한", "대한", "있다", "하여",
                        "하고", "하면", "없는", "같은", "다른", "모든", "어떤", "그런", "이런")
                .contains(t);
    }

    private static double anchorTokenScore(String token) {
        String t = token == null ? "" : token.trim();
        if (t.isBlank()) {
            return Double.NEGATIVE_INFINITY;
        }
        double score = 0.0d;
        if (t.matches(".*[A-Z]{2,}.*")) {
            score += 3.0d;
        }
        if (t.matches(".*[\\uAC00-\\uD7A3].*")) {
            score += 2.5d;
        }
        if (t.matches(".*[A-Za-z].*")) {
            score += 0.75d;
        }
        int len = t.length();
        score += Math.min(2.0d, len / 10.0d);
        if (len > 32) {
            score -= 2.0d;
        }
        return score;
    }

    private static double memoryLineContaminationScore(String line) {
        String s = line == null ? "" : line.toLowerCase(Locale.ROOT);
        double score = 0.0d;
        if (s.contains("import ") || s.startsWith("package ") || s.contains(" at com.")
                || s.contains("exception:") || s.contains("stacktrace") || s.contains("build failed")
                || s.contains("build_error")) {
            score += 0.45d;
        }
        if (s.contains("app/src/main/java") || s.contains("src/main/java_clean")
                || s.contains("demo-1/src/main/java") || s.contains("lms-core/src/main/java")
                || s.contains("tool_b") || s.contains("abandonwaretool_v1") || s.contains("backupsxs")) {
            score += 0.35d;
        }
        if (s.contains("api_key") || s.contains("apikey") || s.contains("client-secret")
                || s.contains("ownertoken") || s.contains("bearer ") || s.contains("sk-")) {
            score += 0.45d;
        }
        if (s.contains("raw prompt") || s.contains("raw snippets") || s.contains("orchestration state")
                || s.contains("trace_store") || s.contains("tracestore") || s.contains("생각 중")) {
            score += 0.30d;
        }
        return clamp01(score);
    }

    private static boolean isMemoryHeaderLine(String line) {
        String s = line == null ? "" : line.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("anchor")
                || s.startsWith("important")
                || s.startsWith("memory")
                || s.startsWith("session")
                || s.startsWith("summary")
                || s.startsWith("- ");
    }

    private static String clipMemoryLine(String line) {
        return trimMemoryLine(line, 240);
    }

    private static String trimMemoryLine(String line, int maxChars) {
        String s = line == null ? "" : line.trim();
        int cap = Math.max(32, maxChars);
        return s.length() <= cap ? s : s.substring(0, Math.max(0, cap - 3)) + "...";
    }

    private static String reasonForMemoryCompression(boolean overflow, boolean contaminated) {
        if (overflow && contaminated) {
            return "overflow_and_contamination";
        }
        if (contaminated) {
            return "history_context_contamination";
        }
        return "overflow";
    }

    private static boolean hasCitationMetadata(Content content) {
        Map<String, Object> meta = metadataOf(content);
        return !strongCitationIdentifier(meta).isBlank();
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

    private static String metadataText(Content content) {
        Map<String, Object> meta = metadataOf(content);
        if (meta.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static String value(Map<String, Object> meta, String key) {
        Object raw = meta == null || key == null ? null : meta.get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
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

    private static Content rebuild(Content original, String newText, String originalText, String anchor) {
        Map<String, Object> meta = metadataOf(original);

        try {
            if (originalText != null && !originalText.isBlank()) {
                meta.putIfAbsent("_nova.origHash", safeHash(originalText));
                meta.putIfAbsent("_nova.origLen", originalText.length());
            }
        } catch (Exception ignore) {
            traceSkipped("dynamicCompressor.rebuildMetadata", ignore);
        }
        meta.put("_nova.compressed", "true");
        if (anchor != null && !anchor.isBlank()) {
            meta.putIfAbsent("_nova.anchorHash", safeHash(anchor));
            meta.putIfAbsent("_nova.anchorLen", anchor.length());
        }

        if (meta.isEmpty()) {
            return Content.from(TextSegment.from(newText));
        }
        return Content.from(TextSegment.from(newText, Metadata.from(langchainMetadata(meta))));
    }

    private static Map<String, Object> safeMetadata(Object raw) {
        if (raw instanceof Metadata metadata) {
            try {
                return new HashMap<>(metadata.toMap());
            } catch (Exception ignore) {
                traceSkipped("dynamicCompressor.metadataCopy", ignore);
                return new HashMap<>();
            }
        }
        if (!(raw instanceof Map<?, ?> m)) {
            return new HashMap<>();
        }
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
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
            traceSkipped("dynamicCompressor.hostOf", ignore);
            return s;
        }
    }

    private static Map<String, Object> metadataOf(Content content) {
        if (content == null || content.textSegment() == null) {
            return new HashMap<>();
        }
        return safeMetadata(content.textSegment().metadata());
    }

    private static Map<String, Object> langchainMetadata(Map<String, Object> meta) {
        Map<String, Object> out = new HashMap<>();
        if (meta == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof String
                    || value instanceof UUID
                    || value instanceof Integer
                    || value instanceof Long
                    || value instanceof Float
                    || value instanceof Double) {
                out.put(key, value);
            } else {
                out.put(key, String.valueOf(value));
            }
        }
        return out;
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

    private static String trimText(String text, String anchor, int maxChars, int anchorWindowChars) {
        if (text == null) {
            return "";
        }
        // normalize line-endings so header detection is stable
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        // Preserve citation/source header lines at the top (URL/Source/http...) as much as possible.
        String header = extractSourceHeaderPrefix(normalized, maxChars);
        String body = normalized.substring(Math.min(header.length(), normalized.length()));

        int bodyBudget = maxChars - header.length();
        if (bodyBudget <= 0) {
            return header.substring(0, maxChars);
        }

        String t = body;
        String a = anchor == null ? "" : anchor.trim();
        if (!a.isBlank() && anchorWindowChars > 0) {
            int idx = indexOfIgnoreCase(t, a);
            if (idx >= 0) {
                int half = anchorWindowChars / 2;
                int start = Math.max(0, idx - half);
                int end = Math.min(t.length(), idx + a.length() + half);
                String sub = t.substring(start, end);
                String prefix = start > 0 ? "..." : "";
                String suffix = end < t.length() ? "..." : "";
                t = prefix + sub + suffix;
            }
        }

        if (t.length() > bodyBudget) {
            if (bodyBudget <= 1) {
                t = "";
            } else {
                // [PATCH] Keep a small tail window to preserve sparse evidence near the end of the doc.
                int tail = Math.min(260, Math.max(120, bodyBudget / 3));
                if (tail >= bodyBudget) {
                    tail = Math.max(0, bodyBudget - 1);
                }
                int head = bodyBudget - tail - 1;
                if (head <= 0 || tail <= 0) {
                    t = t.substring(0, bodyBudget - 1) + "...";
                } else {
                    String h = t.substring(0, Math.min(head, t.length()));
                    String tl = t.substring(Math.max(0, t.length() - tail));
                    t = h + "..." + tl;
                }
            }
        }

        String out = header + t;
        if (out.length() > maxChars) {
            out = out.substring(0, maxChars);
        }
        return out;
    }

    private static String extractSourceHeaderPrefix(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // Keep headers conservative so we don't starve body.
        int maxHeaderLines = 6;
        int maxHeaderChars = Math.min(420, Math.max(120, maxChars / 2));
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.length && i < maxHeaderLines; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                break;
            }
            if (!isSourceHeaderLine(trimmed)) {
                break;
            }
            if (sb.length() + line.length() + 1 > maxHeaderChars) {
                break;
            }
            sb.append(line).append("\n");
        }

        return sb.toString();
    }

    private static boolean isSourceHeaderLine(String trimmed) {
        if (trimmed == null || trimmed.isBlank()) {
            return false;
        }
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("url:") || lower.startsWith("source:") || lower.startsWith("출처:") || lower.startsWith("링크:")) {
            return true;
        }
        // raw URL line or embedded URL
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return true;
        }
        return lower.contains("http://") || lower.contains("https://");
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        if (text == null || needle == null || needle.isBlank()) {
            return -1;
        }
        int idx = text.indexOf(needle);
        if (idx >= 0) {
            return idx;
        }
        return text.toLowerCase().indexOf(needle.toLowerCase());
    }

    private static String normalizeForDedupe(String text) {
        if (text == null) {
            return "";
        }
        String s = text
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
        if (s.length() > 240) {
            s = s.substring(0, 240);
        }
        return s;
    }

    private static void traceCompression(String anchor, int inputCount, int outputCount, boolean failSoft, String reason) {
        try {
            String a = anchor == null ? "" : anchor.trim();
            TraceStore.put("compress.anchor.hash", safeHash(a));
            TraceStore.put("compress.anchor.len", a.length());
            TraceStore.put("compress.input.count", Math.max(0, inputCount));
            TraceStore.put("compress.output.count", Math.max(0, outputCount));
            TraceStore.put("compress.failSoft", failSoft);
            TraceStore.put("compress.reason", safeLabel(reason));
            TraceStore.put("overdrive.stagesApplied", failSoft ? 0 : 1);
            TraceStore.put("overdrive.finalCandidateCount", Math.max(0, outputCount));
            TraceStore.put("overdrive.exactPhraseProbeUsed", false);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("returnedCount", Math.max(0, inputCount));
            output.put("selectedCount", Math.max(0, outputCount));
            Map<String, Object> failure = new LinkedHashMap<>();
            if (failSoft) {
                failure.put("reasonCode", safeLabel(reason));
                failure.put("failureClass", "fallback");
            }
            Map<String, Object> control = new LinkedHashMap<>();
            if (failSoft) {
                control.put("action", "fail_soft_fallback");
                control.put("applied", true);
                control.put("reasonCode", safeLabel(reason));
            }
            OrchEventEmitter.ragEvent(
                    "rag.pipeline",
                    "compression",
                    "context",
                    "compress",
                    "DynamicContextCompressor",
                    failSoft ? "fallback" : "ok",
                    Map.of("mode", "compress"),
                    output,
                    failure,
                    control);
        } catch (Throwable ignored) {
            traceSkipped("dynamicCompressor.compressEvent", ignored);
        }
    }

    private static void traceMemoryCompression(boolean enabled,
                                               boolean activated,
                                               String reason,
                                               String query,
                                               int inputLen,
                                               int outputLen,
                                               int lineDropCount,
                                               double contaminationScore) {
        try {
            String anchor = anchorFrom(query);
            TraceStore.put("prompt.memory.compressor.enabled", enabled);
            TraceStore.put("prompt.memory.compressor.activated", activated);
            TraceStore.put("prompt.memory.compressor.reason", safeLabel(reason));
            TraceStore.put("prompt.memory.compressor.inputLen", Math.max(0, inputLen));
            TraceStore.put("prompt.memory.compressor.outputLen", Math.max(0, outputLen));
            TraceStore.put("prompt.memory.compressor.lineDropCount", Math.max(0, lineDropCount));
            TraceStore.put("prompt.memory.compressor.contaminationScore", round4(contaminationScore));
            TraceStore.put("prompt.memory.compressor.anchor.hash", safeHash(anchor));
            TraceStore.put("prompt.memory.compressor.anchor.len", anchor == null ? 0 : anchor.length());
            OrchEventEmitter.ragEvent(
                    "rag.pipeline",
                    "compression",
                    "memory",
                    "compress",
                    "DynamicContextCompressor",
                    activated ? "ok" : "skipped",
                    Map.of("queryHash", safeHash(query), "mode", "memory"),
                    Map.of("returnedCount", Math.max(0, inputLen),
                            "selectedCount", Math.max(0, outputLen),
                            "lineDropCount", Math.max(0, lineDropCount),
                            "contaminationScore", round4(contaminationScore)),
                    Map.of(),
                    Map.of());
        } catch (Throwable ignored) {
            traceSkipped("dynamicCompressor.memoryCompressionTrace", ignored);
        }
    }

    private static String safeHash(String raw) {
        String s = raw == null ? "" : raw;
        if (s.isBlank()) {
            return "";
        }
        try {
            return DigestUtils.sha256Hex(s).substring(0, 16);
        } catch (Throwable ignore) {
            traceSkipped("dynamicCompressor.safeHash", ignore);
            return Integer.toHexString(s.hashCode());
        }
    }

    private static void traceSkipped(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        try {
            TraceStore.put("compress.suppressed.stage", safeStage);
            TraceStore.put("compress.suppressed.errorType", errorType);
            TraceStore.put("compress.suppressed." + safeStage, true);
            TraceStore.put("compress.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            log.debug("[DynamicContextCompressor] trace skipped stage={} errorType={}",
                    safeStage, traceFailure.getClass().getSimpleName());
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

    private static String messageOf(Throwable error) {
        return error == null ? "" : String.valueOf(error.getMessage());
    }

    private static int messageLength(Throwable error) {
        return messageOf(error).length();
    }
}
