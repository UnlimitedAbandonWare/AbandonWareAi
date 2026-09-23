package com.example.lms.ensemble;

import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Demand-driven, advisory-only triadic adjudication over redacted debug
 * fingerprints. SUPPORT and FALSIFY are sampled by the existing dual sampler;
 * the neutral HOLD vote comes from {@link EnsembleJudgeService}. Pure code owns
 * the final APPLY/HOLD/REJECT decision.
 */
@Component
public class EvidenceGroundedTriadicDebugAdjudicator {

    private static final int MAX_FINGERPRINTS = 6;
    private static final BigDecimal MIN_GROUNDING = new BigDecimal("0.70");
    private static final BigDecimal MIN_GAP = new BigDecimal("0.05");
    private static final int MAX_CANDIDATE_SUMMARY_LENGTH = 600;
    private static final int MAX_CANDIDATE_TARGET_LENGTH = 260;
    private static final int MAX_CANDIDATE_TARGETS = 8;
    private static final String SELF_FINGERPRINT = "triadic-debug-adjudication";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SAFE_TARGET_PATH = Pattern.compile("^[A-Za-z0-9._/-]+$");
    private static final List<String> ACTIVE_SOURCE_PREFIXES = List.of(
            "main/java/",
            "main/resources/",
            "src/test/java/",
            "src/test/resources/",
            "app/src/main/java_clean/",
            "app/src/main/resources/");

    private final DiverseSamplingOrchestrator sampler;
    private final EnsembleJudgeService judge;
    private final AtomicReference<Adjudication> latest = new AtomicReference<>(
            Adjudication.hold("not_run", "none", 0, 0, 0.0d, 0.0d, 0));

    @Value("${debug.copilot.triadic.enabled:${DEBUG_COPILOT_TRIADIC_ENABLED:false}}")
    private boolean enabled;

    public EvidenceGroundedTriadicDebugAdjudicator(
            DiverseSamplingOrchestrator sampler,
            EnsembleJudgeService judge) {
        this.sampler = sampler;
        this.judge = judge;
    }

    public Adjudication adjudicate(List<Map<String, Object>> fingerprints, String rid) {
        return adjudicate(fingerprints, null, rid);
    }

    public Adjudication adjudicate(
            List<Map<String, Object>> fingerprints,
            PatchCandidate patchCandidate,
            String rid) {
        if (!enabled) {
            TraceStore.put("debug.triadic.enabled", false);
            return publish(Adjudication.hold("feature_disabled", "none", 0, 0, 0.0d, 0.0d, 0));
        }
        TraceStore.put("debug.triadic.enabled", true);

        CandidateEvidence candidateEvidence = candidateEvidence(patchCandidate);
        if (!candidateEvidence.valid()) {
            return publish(Adjudication.hold(
                    candidateEvidence.reasonCode(),
                    candidateEvidence.candidateHash12(),
                    0,
                    0,
                    0.0d,
                    0.0d,
                    0));
        }

        FingerprintEvidence evidence = fingerprintEvidence(fingerprints);
        if (evidence.rows().size() < 2) {
            return publish(Adjudication.hold(
                    "insufficient_fingerprints",
                    candidateEvidence.candidateHash12(),
                    evidence.rows().size(),
                    0,
                    0.0d,
                    0.0d,
                    0));
        }
        if (evidence.conflictingRouteOutcomes()) {
            return publish(Adjudication.hold(
                    "evidence_conflicted",
                    candidateEvidence.candidateHash12(),
                    evidence.rows().size(),
                    0,
                    0.0d,
                    0.0d,
                    0));
        }

        List<RagEvidenceMetadata> promptEvidence = new ArrayList<>();
        promptEvidence.add(candidateEvidence.row());
        promptEvidence.addAll(evidence.rows());
        PromptContext context = PromptContext.builder()
                .userQuery("Evaluate active-source debug patch candidate "
                        + candidateEvidence.candidateHash12()
                        + " against the supplied failure evidence. The final action is advisory only.")
                .evidence(List.copyOf(promptEvidence))
                .build();
        try {
            TraceStore.put("ensemble.sampling.modelCallCount", 0);
            List<SampledCandidate> candidates = sampler.sampleThreeRoleHypothesesForDebug(context, safeRid(rid));
            int roleCount = candidates == null ? 0 : candidates.size();
            int samplingModelCallCount = samplingModelCallCount(roleCount);
            if (!hasCompleteThreeRoleSet(candidates)) {
                return publish(Adjudication.hold(
                        "sampling_unavailable",
                        candidateEvidence.candidateHash12(),
                        evidence.rows().size(),
                        roleCount,
                        scoreOf(candidates, "support", SampledCandidate.HypothesisDirection.SUPPORT),
                        scoreOf(candidates, "falsify", SampledCandidate.HypothesisDirection.FALSIFY),
                        samplingModelCallCount));
            }

            SampledCandidate support = candidateOf(
                    candidates, "support", SampledCandidate.HypothesisDirection.SUPPORT);
            SampledCandidate falsify = candidateOf(
                    candidates, "falsify", SampledCandidate.HypothesisDirection.FALSIFY);
            EnsembleJudgeService.DebugPatchVote vote = judge.judgeDebugPatch(candidates, context, safeRid(rid));
            return publish(decide(
                    vote,
                    support,
                    falsify,
                    candidateEvidence.candidateHash12(),
                    evidence.rows().size(),
                    roleCount,
                    samplingModelCallCount + (vote == null ? 0 : vote.modelCallCount())));
        } catch (CancellationException cancellation) {
            throw cancellation;
        } catch (RuntimeException failure) {
            CancellationException cancellation = cancellationFrom(failure);
            if (cancellation != null) {
                throw cancellation;
            }
            TraceStore.put("debug.triadic.fail", "adjudication_failed");
            return publish(Adjudication.hold(
                    "adjudication_failed",
                    candidateEvidence.candidateHash12(),
                    evidence.rows().size(),
                    0,
                    0.0d,
                    0.0d,
                    samplingModelCallCount(0)));
        }
    }

    private static CancellationException cancellationFrom(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CancellationException cancellation) {
                return cancellation;
            }
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                CancellationException cancellation = new CancellationException("triadic_sampling_interrupted");
                cancellation.initCause(failure);
                return cancellation;
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        if (Thread.currentThread().isInterrupted()) {
            CancellationException cancellation = new CancellationException("triadic_sampling_interrupted");
            cancellation.initCause(failure);
            return cancellation;
        }
        return null;
    }

    public Adjudication latest() {
        return latest.get();
    }

    private Adjudication decide(
            EnsembleJudgeService.DebugPatchVote vote,
            SampledCandidate support,
            SampledCandidate falsify,
            String candidateHash12,
            int fingerprintCount,
            int roleCount,
            int modelCallCount) {
        double supportScore = support == null ? 0.0d : support.groundingScore();
        double falsifyScore = falsify == null ? 0.0d : falsify.groundingScore();
        EnsembleJudgeService.DebugPatchVote safeVote = vote == null
                ? EnsembleJudgeService.DebugPatchVote.hold("neutral_vote_missing")
                : vote;
        if (!isAdjudicationVoteCoherent(safeVote)) {
            return Adjudication.hold(
                    "neutral_vote_incoherent",
                    candidateHash12,
                    fingerprintCount,
                    roleCount,
                    supportScore,
                    falsifyScore,
                    modelCallCount);
        }
        if (safeVote.decision() == EnsembleJudgeService.DebugPatchDecision.HOLD) {
            return new Adjudication(
                    EnsembleJudgeService.DebugPatchDecision.HOLD,
                    safeVote.confidence(),
                    safeVote.reasonCode(),
                    candidateHash12,
                    fingerprintCount,
                    roleCount,
                    supportScore,
                    falsifyScore,
                    modelCallCount,
                    true);
        }

        SampledCandidate selected = safeVote.decision() == EnsembleJudgeService.DebugPatchDecision.APPLY
                ? support
                : falsify;
        SampledCandidate alternative = safeVote.decision() == EnsembleJudgeService.DebugPatchDecision.APPLY
                ? falsify
                : support;
        String blocker = promotionBlocker(selected, alternative);
        if (blocker != null) {
            return Adjudication.hold(
                    blocker,
                    candidateHash12,
                    fingerprintCount,
                    roleCount,
                    supportScore,
                    falsifyScore,
                    modelCallCount);
        }
        return new Adjudication(
                safeVote.decision(),
                safeVote.confidence(),
                safeVote.reasonCode(),
                candidateHash12,
                fingerprintCount,
                roleCount,
                supportScore,
                falsifyScore,
                modelCallCount,
                true);
    }

    private static String promotionBlocker(SampledCandidate selected, SampledCandidate alternative) {
        if (selected == null || alternative == null) {
            return "incomplete_role_set";
        }
        if (selected.gateResult() != FinalSigmoidGate.GateResult.PASS) {
            return "safety_gate_not_pass";
        }
        if (selected.evidenceStatus() != SampledCandidate.EvidenceStatus.SUFFICIENT) {
            return "insufficient_grounding";
        }
        BigDecimal selectedScore = scoreValue(selected.groundingScore());
        BigDecimal alternativeScore = scoreValue(alternative.groundingScore());
        if (selectedScore.compareTo(MIN_GROUNDING) < 0) {
            return "grounding_below_threshold";
        }
        if (selectedScore.subtract(alternativeScore).compareTo(MIN_GAP) < 0) {
            return selectedScore.compareTo(alternativeScore) >= 0
                    ? "score_gap_too_small"
                    : "neutral_vote_conflicts_with_scores";
        }
        return null;
    }

    private static boolean isAdjudicationVoteCoherent(EnsembleJudgeService.DebugPatchVote vote) {
        if (vote == null || vote.decision() == null) {
            return false;
        }
        if (vote.decision() == EnsembleJudgeService.DebugPatchDecision.HOLD) {
            return !"support_grounded".equals(vote.reasonCode())
                    && !"falsify_grounded".equals(vote.reasonCode());
        }
        return EnsembleJudgeService.isDebugDecisionReasonCoherent(
                vote.decision(), vote.reasonCode());
    }

    private Adjudication publish(Adjudication result) {
        Adjudication safe = result == null
                ? Adjudication.hold("adjudication_missing", "none", 0, 0, 0.0d, 0.0d, 0)
                : result;
        latest.set(safe);
        TraceStore.put("debug.triadic.decision", safe.decision().name());
        TraceStore.put("debug.triadic.confidence", safe.confidence().name());
        TraceStore.put("debug.triadic.reasonCode", safe.reasonCode());
        TraceStore.put("debug.triadic.candidateHash12", safe.candidateHash12());
        TraceStore.put("debug.triadic.fingerprintCount", safe.fingerprintCount());
        TraceStore.put("debug.triadic.roleCount", safe.roleCount());
        TraceStore.put("debug.triadic.supportGrounding", round3(safe.supportGrounding()));
        TraceStore.put("debug.triadic.falsifyGrounding", round3(safe.falsifyGrounding()));
        TraceStore.put("debug.triadic.modelCallCount", safe.modelCallCount());
        TraceStore.put("debug.triadic.advisoryOnly", true);
        return safe;
    }

    private static FingerprintEvidence fingerprintEvidence(List<Map<String, Object>> fingerprints) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        if (fingerprints != null) {
            for (Map<String, Object> row : fingerprints) {
                if (row == null || unique.size() >= MAX_FINGERPRINTS) {
                    continue;
                }
                String rawFingerprint = stringValue(row.get("fingerprint"));
                if (rawFingerprint.isBlank() || SELF_FINGERPRINT.equalsIgnoreCase(rawFingerprint)) {
                    continue;
                }
                String hash = SafeRedactor.hash12(rawFingerprint);
                if (hash != null && !hash.isBlank()) {
                    unique.putIfAbsent(hash, row);
                }
            }
        }
        List<String> hashes = unique.keySet().stream().sorted().toList();
        List<RagEvidenceMetadata> rows = new ArrayList<>();
        boolean routeSuccessSeen = false;
        boolean routeBlankSeen = false;
        int rank = 1;
        for (String hash : hashes) {
            Map<String, Object> row = unique.get(hash);
            RouteOutcome routeOutcome = routeOutcome(row);
            routeSuccessSeen |= routeOutcome.success();
            routeBlankSeen |= routeOutcome.blank();
            long windowCount = nonNegativeLong(row.get("windowCount"));
            long total = nonNegativeLong(row.get("total"));
            long suppressedInWindow = nonNegativeLong(row.get("suppressedInWindow"));
            long totalSuppressed = nonNegativeLong(row.get("totalSuppressed"));
            String title = "failure fingerprint " + hash
                    + " windowCount=" + windowCount
                    + " total=" + total
                    + " suppressedInWindow=" + suppressedInWindow
                    + " totalSuppressed=" + totalSuppressed;
            rows.add(new RagEvidenceMetadata(
                    "D" + rank,
                    "LOCAL_DOC",
                    title,
                    null,
                    "debug/fingerprints/" + hash + ".trace",
                    1,
                    1,
                    rank,
                    1.0d,
                    "debug_event_count"));
            rank++;
        }
        return new FingerprintEvidence(List.copyOf(rows), routeSuccessSeen && routeBlankSeen);
    }

    private static RouteOutcome routeOutcome(Map<String, Object> row) {
        if (row == null) {
            return RouteOutcome.NONE;
        }
        String routeFamily = stringValue(row.get("routeFamily")).toUpperCase(Locale.ROOT);
        String outcome = stringValue(row.get("routeOutcome")).toUpperCase(Locale.ROOT);
        boolean success = "NATIVE_OLLAMA".equals(routeFamily) && "SUCCESS".equals(outcome);
        boolean blank = "OPENAI_COMPATIBLE".equals(routeFamily) && "BLANK".equals(outcome);
        return new RouteOutcome(success, blank);
    }

    private static CandidateEvidence candidateEvidence(PatchCandidate candidate) {
        if (candidate == null) {
            return CandidateEvidence.invalid("patch_candidate_missing");
        }
        String invalidReason = candidate.invalidReason();
        if (invalidReason != null) {
            return CandidateEvidence.invalid(invalidReason);
        }
        String canonical = String.join("|",
                "triadic-patch-v1",
                candidate.summary(),
                String.join(",", candidate.targetFiles()),
                candidate.diffSha256());
        String hash = SafeRedactor.hash12(canonical);
        String candidateHash12 = hash == null || hash.isBlank() ? "none" : hash;
        String title = "patch candidate " + candidateHash12
                + " summary=" + candidate.summary()
                + " targetFiles=" + String.join(",", candidate.targetFiles())
                + " diffSha256=" + candidate.diffSha256();
        RagEvidenceMetadata row = new RagEvidenceMetadata(
                "P1",
                "LOCAL_DOC",
                title,
                null,
                "debug/patch-candidates/" + candidateHash12 + ".patch",
                1,
                1,
                0,
                1.0d,
                "admin_patch_candidate");
        return new CandidateEvidence(true, "candidate_ready", candidateHash12, row);
    }

    private static boolean hasCompleteThreeRoleSet(List<SampledCandidate> candidates) {
        if (candidates == null || candidates.size() != 3) {
            return false;
        }
        long supportCount = candidates.stream()
                .filter(candidate -> candidate != null
                        && candidate.hypothesisDirection() == SampledCandidate.HypothesisDirection.SUPPORT)
                .count();
        long falsifyCount = candidates.stream()
                .filter(candidate -> candidate != null
                        && candidate.hypothesisDirection() == SampledCandidate.HypothesisDirection.FALSIFY)
                .count();
        return supportCount == 2L && falsifyCount == 1L;
    }

    private static SampledCandidate candidateOf(
            List<SampledCandidate> candidates,
            String nodeId,
            SampledCandidate.HypothesisDirection direction) {
        if (candidates == null) {
            return null;
        }
        return candidates.stream()
                .filter(candidate -> candidate != null
                        && nodeId.equals(candidate.nodeId())
                        && candidate.hypothesisDirection() == direction)
                .findFirst()
                .orElse(null);
    }

    private static double scoreOf(
            List<SampledCandidate> candidates,
            String nodeId,
            SampledCandidate.HypothesisDirection direction) {
        SampledCandidate candidate = candidateOf(candidates, nodeId, direction);
        return candidate == null ? 0.0d : candidate.groundingScore();
    }

    private static int samplingModelCallCount(int roleCount) {
        long traced = nonNegativeLong(TraceStore.get("ensemble.sampling.modelCallCount"));
        return (int) Math.min(Integer.MAX_VALUE, Math.max(Math.max(0, roleCount), traced));
    }

    private static BigDecimal scoreValue(double value) {
        if (!Double.isFinite(value)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(Math.max(0.0d, Math.min(1.0d, value)));
    }

    private static double round3(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 1_000.0d) / 1_000.0d;
    }

    private static long nonNegativeLong(Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        try {
            return Math.max(0L, Long.parseLong(stringValue(value)));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String safeRid(String rid) {
        String hash = SafeRedactor.hash12(rid == null ? "unknown" : rid);
        return hash == null || hash.isBlank() ? "unknown" : hash.toLowerCase(Locale.ROOT);
    }

    private record FingerprintEvidence(
            List<RagEvidenceMetadata> rows,
            boolean conflictingRouteOutcomes) {
    }

    private record RouteOutcome(boolean success, boolean blank) {
        private static final RouteOutcome NONE = new RouteOutcome(false, false);
    }

    private record CandidateEvidence(
            boolean valid,
            String reasonCode,
            String candidateHash12,
            RagEvidenceMetadata row) {

        private static CandidateEvidence invalid(String reasonCode) {
            return new CandidateEvidence(false, reasonCode, "none", null);
        }
    }

    public record PatchCandidate(String summary, List<String> targetFiles, String diffSha256) {

        public PatchCandidate {
            summary = sanitizeCandidateSummary(summary);
            targetFiles = normalizeTargetFiles(targetFiles);
            diffSha256 = stringValue(diffSha256).toLowerCase(Locale.ROOT);
        }

        private String invalidReason() {
            if (summary.isBlank() || targetFiles.isEmpty() || diffSha256.isBlank()) {
                return "patch_candidate_missing";
            }
            if (targetFiles.size() > MAX_CANDIDATE_TARGETS
                    || targetFiles.stream().anyMatch(path -> !isActiveSourcePath(path))
                    || !SHA256.matcher(diffSha256).matches()) {
                return "patch_candidate_invalid";
            }
            return null;
        }
    }

    private static String sanitizeCandidateSummary(String value) {
        String masked = SafeRedactor.redact(stringValue(value));
        String singleLine = masked == null ? "" : masked.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() <= MAX_CANDIDATE_SUMMARY_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_CANDIDATE_SUMMARY_LENGTH);
    }

    private static List<String> normalizeTargetFiles(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(EvidenceGroundedTriadicDebugAdjudicator::stringValue)
                .map(value -> value.replace('\\', '/'))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(MAX_CANDIDATE_TARGETS + 1L)
                .toList();
    }

    private static boolean isActiveSourcePath(String path) {
        if (path == null || path.isBlank()
                || path.length() > MAX_CANDIDATE_TARGET_LENGTH
                || !SAFE_TARGET_PATH.matcher(path).matches()
                || path.startsWith("/")
                || path.endsWith("/")
                || path.contains(":")
                || path.contains("//")
                || path.contains("/./")
                || path.endsWith("/.")
                || path.contains("../")
                || path.contains("/../")
                || path.endsWith("/..")) {
            return false;
        }
        String redacted = SafeRedactor.redact(path);
        return path.equals(redacted)
                && ACTIVE_SOURCE_PREFIXES.stream().anyMatch(path::startsWith);
    }

    public record Adjudication(
            EnsembleJudgeService.DebugPatchDecision decision,
            EnsembleJudgeService.DebugPatchConfidence confidence,
            String reasonCode,
            String candidateHash12,
            int fingerprintCount,
            int roleCount,
            double supportGrounding,
            double falsifyGrounding,
            int modelCallCount,
            boolean advisoryOnly) {

        public Adjudication {
            decision = decision == null ? EnsembleJudgeService.DebugPatchDecision.HOLD : decision;
            confidence = confidence == null ? EnsembleJudgeService.DebugPatchConfidence.LOW : confidence;
            reasonCode = safeReason(reasonCode);
            candidateHash12 = candidateHash12 == null || candidateHash12.isBlank()
                    ? "none"
                    : candidateHash12;
            fingerprintCount = Math.max(0, fingerprintCount);
            roleCount = Math.max(0, roleCount);
            supportGrounding = clamp01(supportGrounding);
            falsifyGrounding = clamp01(falsifyGrounding);
            modelCallCount = Math.max(0, modelCallCount);
            advisoryOnly = true;
        }

        public Map<String, Object> toSafeMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("superTitle", "Evidence-Grounded Triadic Debug Adjudicator");
            out.put("decision", decision.name());
            out.put("confidence", confidence.name());
            out.put("reasonCode", reasonCode);
            out.put("candidateHash12", candidateHash12);
            out.put("fingerprintCount", fingerprintCount);
            out.put("roleCount", roleCount);
            out.put("supportGrounding", round3(supportGrounding));
            out.put("falsifyGrounding", round3(falsifyGrounding));
            out.put("modelCallCount", modelCallCount);
            out.put("advisoryOnly", true);
            return Map.copyOf(out);
        }

        public static Adjudication hold(
                String reasonCode,
                String candidateHash12,
                int fingerprintCount,
                int roleCount,
                double supportGrounding,
                double falsifyGrounding,
                int modelCallCount) {
            return new Adjudication(
                    EnsembleJudgeService.DebugPatchDecision.HOLD,
                    EnsembleJudgeService.DebugPatchConfidence.LOW,
                    reasonCode,
                    candidateHash12,
                    fingerprintCount,
                    roleCount,
                    supportGrounding,
                    falsifyGrounding,
                    modelCallCount,
                    true);
        }

        private static String safeReason(String value) {
            String normalized = value == null ? "underdetermined" : value.strip().toLowerCase(Locale.ROOT);
            return normalized.matches("[a-z0-9_]{1,64}") ? normalized : "underdetermined";
        }

        private static double clamp01(double value) {
            if (!Double.isFinite(value)) {
                return 0.0d;
            }
            return Math.max(0.0d, Math.min(1.0d, value));
        }
    }
}
