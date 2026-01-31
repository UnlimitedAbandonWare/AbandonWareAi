package com.example.lms.matrix;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;




/**
 * MatrixTransformer - a multi-source context synthesizer.
 *
 * <p>This class ingests snippets from live web search, vector RAG and long-term
 * memory and produces a unified prompt context along with a set of
 * reinforcement candidates. In addition to the conventional novelty scoring
 * and authority ordering, it incorporates machine-learning inspired
 * correction/augmentation functions derived from the formulas presented in
 * {




스터프14}. These functions allow dynamic adjustment of the novelty score
 * based on a second-order correction curve and a distance-based reinforcement
 * term. The implementation exposes tunable coefficients (ALPHA, BETA, GAMMA,
 * MU, LAMBDA, D0) which can be refined based on empirical observations.
 */
@Component("matrixTransformer")public class MatrixTransformer {

// === MOE_PATCH_MINIMAL INJECT START ===
// Non-invasive optional MoE gating over multi-source slices.
// Falls back silently if MoE classes are missing or any error occurs.
static class _MoeHook {
    static com.example.moe.MultiSourceMoE moe = null;
    static {
        try { moe = new com.example.moe.MultiSourceMoE(); moe.tau = 0.7; moe.topK = 2; } catch (Throwable t) { /* ignore */ }
    }
    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    static double safeLog(double v) { return Math.log(Math.max(1e-9, v)); }
}
// === MOE_PATCH_MINIMAL INJECT END ===

// === MOE_PATCH_MINIMAL INJECT2 START ===
private double[] _moeGatesOrNull(double aWeb, double uWeb, double fWeb, double mWeb,
                                 double aRag, double uRag, double fRag, double mRag,
                                 double aMem, double uMem, double fMem, double mMem) {
    try {
        if (_MoeHook.moe == null) return null;
        // Build dummy Q/H_in and sources with meta features only (no token K/V available here).
        double[][] Q = new double[][] {{1,0,0,0}};
        double[][] H = new double[][] {{0,1,0,0}};
        java.util.List<com.example.moe.MultiSourceMoE.Source> sources = new java.util.ArrayList<>();
        // Minimal K/V placeholders (1x4)
        double[][] K = new double[][] {{1,0,0,0}};
        double[][] V = new double[][] {{0,0,1,0}};
        double[][] W = new double[][] {{1,0,0,0},{0,1,0,0},{0,0,1,0},{0,0,0,1}};
        com.example.moe.FeatureCollector fc = new com.example.moe.FeatureCollector();
        sources.add(new com.example.moe.MultiSourceMoE.Source(K, V, W, fc.collect(java.util.Map.of(
            "authority", aWeb, "novelty", uWeb, "correctionFactor", fWeb, "match", mWeb))));
        sources.add(new com.example.moe.MultiSourceMoE.Source(K, V, W, fc.collect(java.util.Map.of(
            "authority", aRag, "novelty", uRag, "correctionFactor", fRag, "match", mRag))));
        sources.add(new com.example.moe.MultiSourceMoE.Source(K, V, W, fc.collect(java.util.Map.of(
            "authority", aMem, "novelty", uMem, "correctionFactor", fMem, "match", mMem))));
        com.example.moe.MultiSourceMoE.Output out = _MoeHook.moe.forward(Q, H, sources);
        double[] g = out.gates;
        // sanity: return array of length 3
        if (g == null || g.length != 3) return null;
        return g;
    } catch (Throwable t) {
        return null;
    }
}
// === MOE_PATCH_MINIMAL INJECT2 END ===


    /** Reinforcement bundle consisting of the source tag, snippet text and its
     *  final score. */
    public record Reinforcement(String sourceTag, String snippet, double score) {}
    /** Result object returned by {@link #transform(String, List, String, String)}. */
    public record MatrixResult(String unifiedContext,
                               List<Reinforcement> reinforcements,
                               int webCount,
                               int ragCount,
                               int memCount) {}

    /* ------------------------------------------------------------------
     * Configuration constants
     *
     * MAX_LINES_PER_SECTION controls the total number of lines allocated
     * across all sections; allocateLines will divvy this up based on the
     * number of selected slices per section. MAX_REINFORCE limits the
     * number of reinforcements returned to the caller.
     */
    private static final int MAX_LINES_PER_SECTION = 12;
    private static final int MAX_REINFORCE = 6;

    /* ------------------------------------------------------------------
     * Machine-learning based correction parameters
     *
     * These values correspond to the α, β, γ, μ, λ and d₀ coefficients from
     * the {스터프14} document. They can be tuned externally to adjust how
     * aggressively the novelty score is modified by the correction term.
     */
    private static final double ALPHA  = 0.05;  // coefficient for d^2 term
    private static final double BETA   = 0.10;  // coefficient for d term
    private static final double GAMMA  = 0.00;  // constant offset
    private static final double MU     = 0.15;  // strength of distance based augmentation
    private static final double LAMBDA = 1.25;  // decay rate for exponential term
    private static final double D0     = 0.50;  // reference distance for weighting

    /**
     * Entry point for the transformer. Accepts session id and context
     * snippets, normalizes and deduplicates them, ranks the slices using
     * novelty scoring and the correction functions, dynamically allocates
     * line limits per section, and returns a unified prompt context.
     *
     * @param sessionId  caller session for potential future use
     * @param webSnippets live web search snippets
     * @param ragCtx     text returned from vector RAG
     * @param memCtx     text returned from long term memory
     * @return a {@link MatrixResult} containing the unified context, a list of
     *         reinforcement candidates and counts per section
     */
    public MatrixResult transform(String sessionId,
                                  List<String> webSnippets,
                                  String ragCtx,
                                  String memCtx) {

        // Normalize and deduplicate input slices
        List<Slice> web = normalize("WEB", webSnippets);
        List<Slice> rag = splitBlock("RAG", ragCtx);
        List<Slice> mem = splitBlock("MEM", memCtx);

        // Accumulate all slices in authority order: web → rag → mem
        List<Slice> all = new ArrayList<>();
        all.addAll(web);
        all.addAll(rag);
        all.addAll(mem);

        // Rank and select up to MAX_LINES_PER_SECTION*3 slices; novelty and
        // correction functions are applied within rankAndSelect
        List<Slice> selected = rankAndSelect(all, MAX_LINES_PER_SECTION * 3);

        // Count slices per tag and compute dynamic line limits
        Map<String, Integer> counts = new HashMap<>();
        counts.put("WEB", (int) selected.stream().filter(s -> "WEB".equals(s.tag)).count());
        counts.put("RAG", (int) selected.stream().filter(s -> "RAG".equals(s.tag)).count());
        counts.put("MEM", (int) selected.stream().filter(s -> "MEM".equals(s.tag)).count());

        int totalLines = MAX_LINES_PER_SECTION * 3;
        int webLines = allocateLines("WEB", counts, totalLines);
        int ragLines = allocateLines("RAG", counts, totalLines);
        int memLines = allocateLines("MEM", counts, totalLines);
        // === MOE_PATCH_MINIMAL LINE ALLOCATION (hybrid) START ===
        try {
            double aWeb = baseWeight("WEB");
            double aRag = baseWeight("RAG");
            double aMem = baseWeight("MEM");

            int totalSel = selected.size();
            double uWeb = (double) counts.getOrDefault("WEB", 0) / Math.max(1, totalSel);
            double uRag = (double) counts.getOrDefault("RAG", 0) / Math.max(1, totalSel);
            double uMem = (double) counts.getOrDefault("MEM", 0) / Math.max(1, totalSel);

            double avgWeb = selected.stream().filter(s -> "WEB".equals(s.tag)).mapToDouble(s -> s.score).average().orElse(0.0);
            double avgRag = selected.stream().filter(s -> "RAG".equals(s.tag)).mapToDouble(s -> s.score).average().orElse(0.0);
            double avgMem = selected.stream().filter(s -> "MEM".equals(s.tag)).mapToDouble(s -> s.score).average().orElse(0.0);

            // Use 1.0 as neutral correction (F) proxy at this stage; real F(d) lives inside ranking
            double fWeb = 1.0, fRag = 1.0, fMem = 1.0;

            // Normalize average scores to [0,1] proxy for match/alignment
            double maxAvg = Math.max(1e-6, Math.max(avgWeb, Math.max(avgRag, avgMem)));
            double mWeb = avgWeb / maxAvg;
            double mRag = avgRag / maxAvg;
            double mMem = avgMem / maxAvg;

            double[] gates = _moeGatesOrNull(aWeb, uWeb, fWeb, mWeb, aRag, uRag, fRag, mRag, aMem, uMem, fMem, mMem);
            if (gates != null) {
                int webByGate = Math.max(4, (int) Math.round(gates[0] * totalLines));
                int ragByGate = Math.max(4, (int) Math.round(gates[1] * totalLines));
                int memByGate = Math.max(4, totalLines - webByGate - ragByGate);
                // Hybrid with count-based allocation
                webLines = Math.max(4, (int) Math.round(0.5 * webLines + 0.5 * webByGate));
                ragLines = Math.max(4, (int) Math.round(0.5 * ragLines + 0.5 * ragByGate));
                memLines = Math.max(4, totalLines - webLines - ragLines);
            }
        } catch (Throwable _ignore) { /* keep original allocation */ }
        // === MOE_PATCH_MINIMAL LINE ALLOCATION (hybrid) END ===


        // Build each section by limiting to the computed line counts
        String webSection = joinSection(selected, "WEB", webLines);
        String ragSection = joinSection(selected, "RAG", ragLines);
        String memSection = joinSection(selected, "MEM", memLines);

        // Build unified context with instructions appended
        String unified = buildUnified(webSection, ragSection, memSection);
        if (isBlank(unified)) {
            return new MatrixResult("", List.of(), 0, 0, 0);
        }

        // Extract reinforcement candidates: take top scoring slices, apply clamp
        List<Reinforcement> reinf = selected.stream()
                .filter(s -> s.score > 0.0)
                .sorted(Comparator.comparingDouble((Slice s) -> s.score).reversed())
                .map(s -> new Reinforcement(s.tag, s.text, clamp01(s.score)))
                .distinct()
                .limit(MAX_REINFORCE)
                .toList();

        return new MatrixResult(
                unified,
                reinf,
                (int) selected.stream().filter(s -> "WEB".equals(s.tag)).count(),
                (int) selected.stream().filter(s -> "RAG".equals(s.tag)).count(),
                (int) selected.stream().filter(s -> "MEM".equals(s.tag)).count());
    }

    /**
     * Computes the number of lines allocated to a given tag based on the
     * proportion of slices it contributes relative to the total and the
     * specified total capacity. A minimum of four lines is always preserved
     * per section to ensure that no section disappears entirely.
     *
     * @param tag      the section tag ("WEB", "RAG" or "MEM")
     * @param counts   map of counts per section
     * @param totalCap total lines available across all sections
     * @return the number of lines allocated to the given tag
     */
    private int allocateLines(String tag, Map<String, Integer> counts, int totalCap) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        int base = counts.getOrDefault(tag, 0);
        if (total == 0) {
            return Math.max(4, totalCap / 3);
        }
        return Math.max(4, (int) ((double) base / total * totalCap));
    }

    /* ------------------------------------------------------------------
     * Internal utilities
     */

    private record Slice(String tag, String text, double score, Set<String> tokens, long ts) {}

    /**
     * Normalize a list of raw snippet lines. Cleans whitespace, filters out
     * empty entries, assigns a base weight based on the tag and collects
     * tokens for similarity computations. Timestamp is captured at the time
     * of normalization.
     */
    private static List<Slice> normalize(String tag, List<String> lines) {
        if (lines == null) return List.of();
        List<Slice> out = new ArrayList<>();
        for (String raw : lines) {
            String t = clean(raw);
            if (isBlank(t)) continue;
            out.add(new Slice(tag, t, baseWeight(tag), toTokens(t), Instant.now().toEpochMilli()));
        }
        return dedup(out);
    }

    /**
     * Splits a block of context into individual slices. For RAG and memory
     * contexts, use double newlines or --- delimiters. Short fragments
     * (<12 characters) are ignored. Cleans whitespace and assigns tokens.
     */
    private static List<Slice> splitBlock(String tag, String block) {
        if (isBlank(block)) return List.of();
        String[] lines = block.split("\\R{2,}|\\n+---\\n+");
        List<Slice> out = new ArrayList<>();
        for (String raw : lines) {
            String t = clean(raw);
            if (t.length() < 12) continue;
            out.add(new Slice(tag, t, baseWeight(tag), toTokens(t), Instant.now().toEpochMilli()));
        }
        return dedup(out);
    }

    /**
     * Rank the provided slices using novelty scoring and apply the machine
     * learning correction function. The first phase computes novelty by
     * comparing token overlap via cosine similarity. The score is then
     * multiplied by both a novelty factor and a correction factor derived from
     * the F(d) formula in {스터프14}.
     *
     * @param all list of all slices across all sections
     * @param cap maximum number of slices to accumulate
     * @return list of slices sorted by authority order, corrected score and
     *         recency
     */
    private static List<Slice> rankAndSelect(List<Slice> all, int cap) {
        List<Slice> acc = new ArrayList<>();
        for (Slice s : all) {
            double novelty = 1.0;
            for (Slice prev : acc) {
                double sim = cosineSimilarity(s.tokens, prev.tokens);
                novelty = Math.min(novelty, 1.0 - sim);
                if (novelty < 0.15) break;
            }
            // Compute a base novelty factor in [0.5, 1.0]
            double noveltyFactor = 0.5 + 0.5 * novelty;
            // Compute correction factor based on distance; here we treat
            // similarity complement as the distance d. This leverages the
            // second-order plus distance augmentation formula.
            double distance = 1.0 - novelty; // small distance → high novelty
            double correction = F(distance);
            // Clamp the correction factor to [0.5, 1.5] to prevent extreme
            // inflation/deflation. This range can be tuned as needed.
            double correctionFactor = Math.max(0.5, Math.min(1.5, correction));
            // Apply both novelty and correction factors to the slice score
            double score = s.score * noveltyFactor * correctionFactor;
            acc.add(new Slice(s.tag, s.text, score, s.tokens, s.ts));
            if (acc.size() >= cap) break;
        }
        return acc.stream()
                .sorted(Comparator
                        .comparingInt((Slice s) -> authorityOrder(s.tag))
                        .thenComparingDouble((Slice s) -> s.score).reversed()
                        .thenComparingLong(s -> s.ts).reversed())
                .toList();
    }

    /**
     * Join all slices of a given tag into a single section. Each slice
     * becomes a bullet line prefixed with "- ". Only up to {@code limit}
     * slices are emitted.
     */
    private static String joinSection(List<Slice> slices, String tag, int limit) {
        return slices.stream()
                .filter(s -> tag.equals(s.tag))
                .limit(limit)
                .map(s -> "- " + s.text)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Build the final unified context. Sections are prefaced with headers,
     * and a final instructions block is appended. If all sections are
     * empty, an empty string is returned.
     */
    private static String buildUnified(String web, String rag, String mem) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(web)) parts.add("### LIVE WEB RESULTS (highest priority)\n" + web);
        if (!isBlank(rag)) parts.add("### VECTOR RAG\n" + rag);
        if (!isBlank(mem)) parts.add("### LONG-TERM MEMORY\n" + mem);
        if (parts.isEmpty()) return "";
        parts.add("""
                ### INSTRUCTIONS
                - Earlier sections have higher authority; cite source titles.
                - If insufficient, reply "정보 없음".
                """.trim());
        return String.join("\n\n", parts);
    }

    /** Base weight per section. Web snippets have highest base weight, then
     *  vector RAG, then memory. */
    private static double baseWeight(String tag) {
        return switch (tag) {
            case "WEB" -> 1.0;
            case "RAG" -> 0.7;
            case "MEM" -> 0.4;
            default -> 0.5;
        };
    }

    /** Authority order: WEB (0) → RAG (1) → MEM (2) → unknown (3). */
    private static int authorityOrder(String tag) {
        return switch (tag) {
            case "WEB" -> 0;
            case "RAG" -> 1;
            case "MEM" -> 2;
            default -> 3;
        };
    }

    /** Tokenize a string into a set of words. Non alphanumeric characters
     *  are replaced with spaces, and tokens of length one are dropped. */
    private static Set<String> toTokens(String s) {
        return Arrays.stream(s.toLowerCase(Locale.ROOT)
                        // Preserve plus (+), hyphens/dashes, apostrophes and slashes when tokenizing.
                        // We replace all other punctuation with a space before splitting.  Note: \\p{L} matches
                        // any kind of letter from any language, and \\p{N} matches any kind of digit.  Slash, hyphen
                        // variations and apostrophes are kept to retain compound names like "a/b+c", "jean-paul's".
                        .replaceAll("[^\\p{L}\\p{N}\\s/\\-+'----]+", " ")
                        .split("\\s+"))
                .filter(t -> t.length() > 1)
                .limit(60)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Cosine similarity between two token sets (set-based approximation). */
    private static double cosineSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = 0;
        for (String t : a) {
            if (b.contains(t)) {
                inter++;
            }
        }
        double denom = Math.sqrt(a.size()) * Math.sqrt(b.size()) + 1e-9;
        return ((double) inter) / denom;
    }

    /** Deduplicate slices by tag and trimmed text. */
    private static List<Slice> dedup(List<Slice> in) {
        LinkedHashMap<String, Slice> m = new LinkedHashMap<>();
        for (Slice s : in) {
            String key = s.tag + "|" + s.text.trim();
            m.putIfAbsent(key, s);
        }
        return new ArrayList<>(m.values());
    }

    /** Clean a raw snippet: collapse whitespace and remove leading bullets. */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ")
                .replaceAll("^[•\\-*]+\\s*", "")
                .trim();
    }

    /** Utility to check if a string is null or blank. */
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** Clamp a double to the open interval (0, 1]. */
    private static double clamp01(double x) { return Math.max(0.0001, Math.min(1.0, x)); }

    /* ------------------------------------------------------------------
     * Machine-learning correction functions from {스터프14}
     *
     * f(d)  = α d^2 + β d + γ
     * phi(d) = μ (d − d0) [ 1 − e^{−λ |d − d0|} ]
     * F(d)  = f(d) + phi(d)
     *
     * These methods implement the formulas described in the user's machine
     * learning correction document. They accept a single distance parameter
     * and return the corresponding correction value. Since novelty scores
     * operate on distances in [0, 1], the caller is responsible for
     * scaling the input appropriately.
     */
    private static double f(double d) {
        return ALPHA * d * d + BETA * d + GAMMA;
    }

    private static double phi(double d) {
        double diff = d - D0;
        return MU * diff * (1.0 - Math.exp(-LAMBDA * Math.abs(diff)));
    }

    private static double F(double d) {
        return f(d) + phi(d);
    }

// ───────────── Nine Art Plate integration: Signals carrier + moeGates wrapper ─────────────
public static record Signals(
    double authorityWeb, double noveltyWeb, double fdWeb, double matchWeb,
    double authorityRag, double noveltyRag, double fdRag, double matchRag,
    double authorityMem, double noveltyMem, double fdMem, double matchMem,
    double authority, int evidenceCount, int sessionRecur, double recallNeed, boolean noisy
) {}

/**
 * Public thin wrapper to expose current MoE gate signals to higher-level plate selector.
 * No changes to existing private logic.
 */
public double[] moeGates(Signals sig) {
    return _moeGatesOrNull(
        sig.authorityWeb(), sig.noveltyWeb(), sig.fdWeb(), sig.matchWeb(),
        sig.authorityRag(), sig.noveltyRag(), sig.fdRag(), sig.matchRag(),
        sig.authorityMem(), sig.noveltyMem(), sig.fdMem(), sig.matchMem()
    );
}
// ───────────── End Nine Art Plate integration ─────────────

}