package com.nova.protocol.fusion;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.fusion.TailWeightedPowerMeanFuser;
import com.example.lms.service.rag.fusion.WeightedPowerMeanFuser;
import com.example.lms.service.rag.rerank.DppDiversityReranker;
import com.example.lms.trace.SafeRedactor;
import com.nova.protocol.alloc.RiskKAllocator;
import com.nova.protocol.properties.NovaNextProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NovaNextFusionService {

    private static final double MAX_BOUNDED_TWPM_P = 8.0d;

    private final NovaNextProperties props;
    private final RiskKAllocator riskKAllocator;
    private final DppDiversityReranker dppReranker;
    private final TailWeightedPowerMeanFuser twpmFuser;
    private final CvarAggregator cvarAggregator;

    public NovaNextFusionService(NovaNextProperties props) {
        this(props, null);
    }

    public NovaNextFusionService(NovaNextProperties props, RiskKAllocator riskKAllocator) {
        this(props, riskKAllocator, null, null);
    }

    public NovaNextFusionService(NovaNextProperties props,
                                 RiskKAllocator riskKAllocator,
                                 DppDiversityReranker dppReranker,
                                 TailWeightedPowerMeanFuser twpmFuser) {
        this(props, riskKAllocator, dppReranker, twpmFuser, null);
    }

    public NovaNextFusionService(NovaNextProperties props,
                                 RiskKAllocator riskKAllocator,
                                 DppDiversityReranker dppReranker,
                                 TailWeightedPowerMeanFuser twpmFuser,
                                 CvarAggregator cvarAggregator) {
        this.props = props == null ? new NovaNextProperties() : props;
        this.riskKAllocator = riskKAllocator;
        this.dppReranker = dppReranker == null ? new DppDiversityReranker() : dppReranker;
        this.twpmFuser = twpmFuser == null
                ? new TailWeightedPowerMeanFuser(new WeightedPowerMeanFuser())
                : twpmFuser;
        this.cvarAggregator = cvarAggregator == null ? new CvarAggregator() : cvarAggregator;
    }

    public NovaNextFusionService() {
        this(new NovaNextProperties(), null);
    }

    public static class ScoredResult {
        private String id;
        private double score;
        private double baseScore;
        private double adjustedScore;
        private int rank;
        private String source;
        private int sourceCount;
        private List<Double> sourceScores = new ArrayList<>();
        private double authorityAvg;
        private double strongCitationRate;
        private double duplicateRate;
        private double contradictionRate;
        private double crossLaneSupportRate;
        private double grandasReadiness;
        private double tailSignal;
        private double guardBand;
        private int riskKAllocation;
        private String reason = "pass_through";

        public ScoredResult() {}

        public ScoredResult(double s) {
            setScore(s);
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public double getScore() { return adjustedScore; }
        public void setScore(double score) {
            double s = finiteOr(score, 0.0d);
            this.score = s;
            this.baseScore = s;
            this.adjustedScore = s;
        }
        public double getBaseScore() { return baseScore; }
        public void setBaseScore(double baseScore) { this.baseScore = finiteOr(baseScore, this.score); }
        public double getAdjustedScore() { return adjustedScore; }
        public void setAdjustedScore(double adjustedScore) { this.adjustedScore = finiteOr(adjustedScore, this.baseScore); }
        public int getRank() { return rank; }
        public void setRank(int rank) { this.rank = Math.max(0, rank); }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public int getSourceCount() { return sourceCount; }
        public void setSourceCount(int sourceCount) { this.sourceCount = Math.max(0, sourceCount); }
        public List<Double> getSourceScores() { return sourceScores; }
        public void setSourceScores(List<Double> sourceScores) {
            this.sourceScores = sourceScores == null ? new ArrayList<>() : new ArrayList<>(sourceScores);
        }
        public double getAuthorityAvg() { return authorityAvg; }
        public void setAuthorityAvg(double authorityAvg) { this.authorityAvg = clamp01(authorityAvg); }
        public double getStrongCitationRate() { return strongCitationRate; }
        public void setStrongCitationRate(double strongCitationRate) { this.strongCitationRate = clamp01(strongCitationRate); }
        public double getDuplicateRate() { return duplicateRate; }
        public void setDuplicateRate(double duplicateRate) { this.duplicateRate = clamp01(duplicateRate); }
        public double getContradictionRate() { return contradictionRate; }
        public void setContradictionRate(double contradictionRate) { this.contradictionRate = clamp01(contradictionRate); }
        public double getCrossLaneSupportRate() { return crossLaneSupportRate; }
        public void setCrossLaneSupportRate(double crossLaneSupportRate) { this.crossLaneSupportRate = clamp01(crossLaneSupportRate); }
        public double getGrandasReadiness() { return grandasReadiness; }
        public void setGrandasReadiness(double grandasReadiness) { this.grandasReadiness = clamp01(grandasReadiness); }
        public double getTailSignal() { return tailSignal; }
        public void setTailSignal(double tailSignal) { this.tailSignal = clamp01(tailSignal); }
        public double getGuardBand() { return guardBand; }
        public void setGuardBand(double guardBand) { this.guardBand = Math.max(0.0d, finiteOr(guardBand, 0.0d)); }
        public int getRiskKAllocation() { return riskKAllocation; }
        public void setRiskKAllocation(int riskKAllocation) { this.riskKAllocation = Math.max(0, riskKAllocation); }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason == null ? "" : reason; }
    }

    public List<ScoredResult> fuse(List<ScoredResult> in) {
        if (in == null) {
            TraceStore.put("hypernova.active", false);
            return List.of();
        }
        if (in.isEmpty() || props == null || !props.isEnabled()) {
            TraceStore.put("hypernova.active", false);
            return in;
        }
        TraceStore.put("hypernova.activated", true);
        TraceStore.put("hypernova.active", true);
        TraceStore.put("hypernova.finalGatePassed", false);
        TraceStore.put("hypernova.finalGateDisabledReason", "downstream_gate_callback_pending");
        try {
            return fuseInternal(in);
        } catch (Exception ex) {
            TraceStore.put("nova.next.failSoft.stage", "fuse");
            recordFailSoft(ex);
            return in;
        }
    }

    private List<ScoredResult> fuseInternal(List<ScoredResult> in) {
        List<Double> baseScores = new ArrayList<>();
        double minBase = Double.POSITIVE_INFINITY;
        double maxBase = Double.NEGATIVE_INFINITY;
        for (ScoredResult sr : in) {
            if (sr == null) {
                continue;
            }
            double base = finiteOr(sr.getBaseScore(), finiteOr(sr.score, 0.0d));
            sr.setBaseScore(base);
            sr.setAdjustedScore(base);
            baseScores.add(base);
            minBase = Math.min(minBase, base);
            maxBase = Math.max(maxBase, base);
        }
        if (baseScores.isEmpty()) {
            return in;
        }
        boolean signedBaseDomain = minBase < 0.0d;
        if (!signedBaseDomain && maxBase <= 0.0d) {
            return in;
        }
        double adjustmentScale = signedBaseDomain
                ? Math.max(1.0e-9d, Math.max(Math.abs(minBase), Math.abs(maxBase)))
                : maxBase;

        double alpha = clamp(props.getAlphaCvar(), 0.50d, 0.99d);
        double tailCut = percentile(baseScores, alpha);
        double tailNorm = normalizeBaseScore(tailCut, minBase, maxBase, signedBaseDomain);
        double p0 = Math.max(1.05d, finiteOr(props.getP0(), 1.20d));
        double alphaTwpm = Math.max(0.0d, finiteOr(props.getAlphaTwpm(), 0.80d));
        double lambdaCvar = clamp01(finiteOr(props.getLambdaCvar(), 0.35d));
        NovaNextProperties.Grandas grandas = props.getGrandas() == null
                ? new NovaNextProperties.Grandas()
                : props.getGrandas();
        double configuredPMax = grandas.getPMax();
        double pMax = clamp(finiteOr(configuredPMax, 3.0d), 1.05d, MAX_BOUNDED_TWPM_P);
        boolean pMaxBounded = !Double.isFinite(configuredPMax)
                || Double.compare(configuredPMax, pMax) != 0;
        double maxAdjustment = clamp(finiteOr(grandas.getMaxAdjustment(), 0.18d), 0.0d, 0.50d);
        double bodeC = Math.max(0.0d, finiteOr(grandas.getBodeC(), 1.6d));
        double maxObservedP = 0.0d;
        double maxObservedFused = 0.0d;
        boolean clampApplied = false;
        int sourceScoreScaleMismatchCount = 0;

        for (ScoredResult sr : in) {
            if (sr == null) {
                continue;
            }
            double base = finiteOr(sr.getBaseScore(), 0.0d);
            double norm = normalizeBaseScore(base, minBase, maxBase, signedBaseDomain);
            double tailDen = Math.max(1.0e-9d, 1.0d - tailNorm);
            double tailExcess = clamp01((norm - tailNorm) / tailDen);
            double p = clamp(p0 + (alphaTwpm * tailExcess), 1.05d, pMax);

            SourceScoreNormalization normalized = normalizeSourceScores(sr.getSourceScores(), norm);
            sourceScoreScaleMismatchCount += normalized.scaleMismatchCount();
            double twpm = twpmFuser.fuseUpperTail(normalized.scores(), p, null, 0.25d, 2.0d);
            double cvar = cvarAggregator.cvarAtQuantile(normalized.scores(), alpha);
            maxObservedP = Math.max(maxObservedP, p);
            double fusedNorm = ((1.0d - lambdaCvar) * twpm) + (lambdaCvar * cvar);
            maxObservedFused = Math.max(maxObservedFused, fusedNorm);
            double smoothNorm = sigmoid((fusedNorm - 0.50d) * 4.0d);
            double tailSignal = Math.max(sr.getTailSignal(), tailSignal(sr, tailExcess));
            double delta = (0.30d * tailSignal)
                    + (0.20d * sr.getGrandasReadiness())
                    + (0.15d * sr.getAuthorityAvg())
                    + (0.10d * sr.getStrongCitationRate())
                    + (0.10d * sr.getCrossLaneSupportRate())
                    - (0.15d * sr.getDuplicateRate())
                    - (0.20d * sr.getContradictionRate());
            double projected = clamp01((0.70d * norm) + (0.30d * smoothNorm));
            projected = projected * (1.0d + (0.25d * Math.tanh(delta)));
            double deltaNorm = projected - norm;
            double deltaMagnitude = Math.abs(deltaNorm);
            double clampedMagnitude = BodeClamp.applyTraced(deltaMagnitude, bodeC, "nova.next");
            double clampedDeltaNorm = Math.signum(deltaNorm) * clampedMagnitude;
            if (Math.abs(clampedMagnitude - deltaMagnitude) > 1.0e-9d) {
                clampApplied = true;
            }
            double target = base + (clampedDeltaNorm * adjustmentScale);
            double guardBand = Math.max(1.0e-12d, Math.abs(base) * maxAdjustment);
            double adjusted = clamp(target, base - guardBand, base + guardBand);

            sr.setTailSignal(tailSignal);
            sr.setGuardBand(guardBand);
            sr.setAdjustedScore(adjusted);
            sr.setReason(reason(base, adjusted, tailSignal, guardBand));
        }
        TraceStore.put("hypernova.twpmP", round6(maxObservedP));
        TraceStore.put("hypernova.cvarFusedScore", round6(maxObservedFused));
        TraceStore.put("hypernova.cvarAlpha", round6(alpha));
        TraceStore.put("hypernova.cvarPhi", round6(lambdaCvar));
        TraceStore.put("hypernova.clampApplied", clampApplied);
        TraceStore.put("hypernova.sourceScoreScaleMismatchCount", sourceScoreScaleMismatchCount);
        if (sourceScoreScaleMismatchCount > 0) {
            TraceStore.put("hypernova.sourceScoreScaleMismatchPolicy", "fallback_to_base_norm");
        }
        List<ScoredResult> ordered = orderByAdjustedScore(in);
        allocateRiskK(ordered, minBase, maxBase, signedBaseDomain);
        TraceStore.put("hypernova.twpmP.max", round6(pMax));
        TraceStore.put("hypernova.twpmP.maxBounded", pMaxBounded);
        return applyDpp(ordered);
    }

    private void allocateRiskK(List<ScoredResult> in,
                               double minBase,
                               double maxBase,
                               boolean signedBaseDomain) {
        if (riskKAllocator == null || in == null || in.isEmpty()) {
            TraceStore.put("nova.hypernova.riskK.used", false);
            TraceStore.put("hypernova.riskKAlloc", Map.of("used", false));
            return;
        }
        List<ScoredResult> candidates = new ArrayList<>();
        for (ScoredResult sr : in) {
            if (sr != null) {
                candidates.add(sr);
            }
        }
        if (candidates.isEmpty()) {
            TraceStore.put("nova.hypernova.riskK.used", false);
            TraceStore.put("hypernova.riskKAlloc", Map.of("used", false));
            return;
        }

        double[] logits = new double[candidates.size()];
        double[] risk = new double[candidates.size()];
        int totalK = Math.max(1, props.getKTotal());
        for (int i = 0; i < candidates.size(); i++) {
            ScoredResult sr = candidates.get(i);
            logits[i] = normalizeBaseScore(
                    finiteOr(sr.getAdjustedScore(), sr.getBaseScore()),
                    minBase,
                    maxBase,
                    signedBaseDomain);
            risk[i] = clamp01(Math.max(sr.getContradictionRate(), sr.getDuplicateRate()));
        }

        int[] allocation = riskKAllocator.alloc(
                logits,
                risk,
                totalK,
                Math.max(1.0e-6d, finiteOr(props.getTempSoftmax(), 1.0d)),
                null);
        int sum = 0;
        for (int i = 0; i < candidates.size(); i++) {
            int k = allocation == null || i >= allocation.length ? 0 : Math.max(0, allocation[i]);
            candidates.get(i).setRiskKAllocation(k);
            sum += k;
        }
        TraceStore.put("nova.hypernova.riskK.used", true);
        TraceStore.put("nova.hypernova.riskK.candidateCount", candidates.size());
        TraceStore.put("nova.hypernova.riskK.totalK", totalK);
        TraceStore.put("nova.hypernova.riskK.alloc.sum", sum);
        Map<String, Object> riskKTrace = new LinkedHashMap<>();
        riskKTrace.put("used", true);
        riskKTrace.put("candidateCount", candidates.size());
        riskKTrace.put("totalK", totalK);
        riskKTrace.put("sum", sum);
        TraceStore.put("hypernova.riskKAlloc", riskKTrace);
    }

    private static SourceScoreNormalization normalizeSourceScores(List<Double> scores, double fallback) {
        if (scores == null || scores.isEmpty()) {
            return new SourceScoreNormalization(List.of(fallback), 0);
        }
        List<Double> out = new ArrayList<>();
        int scaleMismatchCount = 0;
        for (Double score : scores) {
            double s = finiteOr(score == null ? 0.0d : score, Double.NaN);
            if (!Double.isFinite(s)) {
                continue;
            }
            if (s < 0.0d || s > 1.0d) {
                scaleMismatchCount++;
                continue;
            }
            out.add(s);
        }
        if (scaleMismatchCount > 0) {
            return new SourceScoreNormalization(List.of(fallback), scaleMismatchCount);
        }
        return new SourceScoreNormalization(out.isEmpty() ? List.of(fallback) : out, 0);
    }

    private record SourceScoreNormalization(List<Double> scores, int scaleMismatchCount) {
    }

    private static double normalizeBaseScore(double value,
                                             double minBase,
                                             double maxBase,
                                             boolean signedBaseDomain) {
        if (!signedBaseDomain) {
            return clamp01(value / Math.max(1.0e-9d, maxBase));
        }
        double range = maxBase - minBase;
        if (!Double.isFinite(range) || range <= 1.0e-9d) {
            return 0.50d;
        }
        return clamp01((value - minBase) / range);
    }

    private static List<ScoredResult> orderByAdjustedScore(List<ScoredResult> in) {
        List<ScoredResult> ordered = new ArrayList<>(in);
        ordered.sort((left, right) -> {
            if (left == right) {
                return 0;
            }
            if (left == null) {
                return 1;
            }
            if (right == null) {
                return -1;
            }
            int byScore = Double.compare(right.getAdjustedScore(), left.getAdjustedScore());
            if (byScore != 0) {
                return byScore;
            }
            String leftId = left.getId() == null ? "" : left.getId();
            String rightId = right.getId() == null ? "" : right.getId();
            return leftId.compareTo(rightId);
        });
        return ordered;
    }

    private List<ScoredResult> applyDpp(List<ScoredResult> in) {
        if (dppReranker == null) {
            traceDppDisabled("bean_not_available", in == null ? 0 : in.size(), in == null ? 0 : in.size());
            return in;
        }
        if (in == null || in.size() < 4) {
            int count = in == null ? 0 : in.size();
            traceDppDisabled("too_few_results", count, count);
            return in;
        }
        int inputCount = in.size();
        try {
            List<ScoredResult> out = dppReranker.rerank(
                    in,
                    "hypernova",
                    inputCount,
                    NovaNextFusionService::dppText,
                    ScoredResult::getAdjustedScore);
            if (out == null || out.size() != inputCount) {
                traceDppDisabled("reranker_invalid_output", inputCount, out == null ? 0 : out.size());
                return in;
            }
            TraceStore.put("hypernova.dppApplied", true);
            TraceStore.put("hypernova.dppInputCount", inputCount);
            TraceStore.put("hypernova.dppOutputCount", out.size());
            TraceStore.put("hypernova.dppDisabledReason", "");
            return out;
        } catch (RuntimeException ex) {
            TraceStore.put("hypernova.dppErrorType", ex.getClass().getSimpleName());
            traceDppDisabled("reranker_failed", inputCount, inputCount);
            return in;
        }
    }

    private static void traceDppDisabled(String reason, int inputCount, int outputCount) {
        TraceStore.put("hypernova.dppApplied", false);
        TraceStore.put("hypernova.dppInputCount", Math.max(0, inputCount));
        TraceStore.put("hypernova.dppOutputCount", Math.max(0, outputCount));
        TraceStore.put("hypernova.dppDisabledReason", reason);
    }

    private static String dppText(ScoredResult sr) {
        if (sr == null) {
            return "";
        }
        return String.join(" ",
                sr.getId() == null ? "" : sr.getId(),
                sr.getSource() == null ? "" : sr.getSource(),
                sr.getReason() == null ? "" : sr.getReason());
    }

    private static double tailSignal(ScoredResult sr, double tailExcess) {
        double quality = (0.25d * sr.getStrongCitationRate())
                + (0.20d * sr.getAuthorityAvg())
                + (0.15d * sr.getGrandasReadiness())
                + (0.10d * sr.getCrossLaneSupportRate())
                + (0.15d * (1.0d - sr.getDuplicateRate()))
                + (0.15d * (1.0d - sr.getContradictionRate()));
        return clamp01((0.55d * tailExcess) + (0.45d * quality));
    }

    private static String reason(double base, double adjusted, double tailSignal, double guardBand) {
        double diff = adjusted - base;
        if (Math.abs(diff) <= Math.max(1.0e-12d, guardBand * 0.05d)) {
            return "smooth_hold";
        }
        if (diff > 0.0d && tailSignal >= 0.70d) {
            return "tail_boost";
        }
        if (diff > 0.0d) {
            return "soft_boost";
        }
        return "soft_dampen";
    }

    private static double percentile(List<Double> values, double q) {
        if (values == null || values.isEmpty()) {
            return 0.0d;
        }
        List<Double> copy = new ArrayList<>();
        for (Double value : values) {
            copy.add(finiteOr(value == null ? 0.0d : value, 0.0d));
        }
        copy.sort(Double::compareTo);
        if (copy.size() == 1) {
            return copy.get(0);
        }
        double pos = clamp01(q) * (copy.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return copy.get(lo);
        }
        double frac = pos - lo;
        return copy.get(lo) + ((copy.get(hi) - copy.get(lo)) * frac);
    }

    public static Map<String, Object> traceFields(ScoredResult sr) {
        if (sr == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", round6(sr.getAdjustedScore()));
        out.put("baseScore", round6(sr.getBaseScore()));
        out.put("tailSignal", round6(sr.getTailSignal()));
        out.put("guardBand", round6(sr.getGuardBand()));
        out.put("riskKAllocation", sr.getRiskKAllocation());
        out.put("reason", sr.getReason());
        if (sr.getSource() != null && !sr.getSource().isBlank()) {
            out.put("source", sr.getSource().toLowerCase(Locale.ROOT));
        }
        out.put("sourceCount", sr.getSourceCount());
        return out;
    }

    private static double sigmoid(double x) {
        return 1.0d / (1.0d + Math.exp(-x));
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000.0d) / 1_000_000.0d;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0d, 1.0d);
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double finiteOr(double value, double fallback) {
        return (Double.isNaN(value) || Double.isInfinite(value)) ? fallback : value;
    }

    private static void recordFailSoft(Exception ex) {
        TraceStore.put("nova.next.failSoft", true);
        TraceStore.put("nova.next.failSoft.errorType",
                ex == null ? "unknown" : ex.getClass().getSimpleName());
        TraceStore.put("nova.next.failSoft.errorMessageHash", SafeRedactor.hashValue(messageOf(ex)));
        TraceStore.put("nova.next.failSoft.errorMessageLength", messageLength(ex));
    }

    private static String messageOf(Throwable t) {
        return t == null || t.getMessage() == null ? "" : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        return messageOf(t).length();
    }
}
