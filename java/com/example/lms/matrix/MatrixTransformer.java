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
 * MatrixTransformer – a multi‐source context synthesizer.
 *
 * <p>This class ingests snippets from live web search, vector RAG and long‐term
 * memory and produces a unified prompt context along with a set of
 * reinforcement candidates. In addition to the conventional novelty scoring
 * and authority ordering, it incorporates machine‐learning inspired
 * correction/augmentation functions derived from the formulas presented in
 * {스터프14}. These functions allow dynamic adjustment of the novelty score
 * based on a second‐order correction curve and a distance‐based reinforcement
 * term. The implementation exposes tunable coefficients (ALPHA, BETA, GAMMA,
 * MU, LAMBDA, D0) which can be refined based on empirical observations.
 */
@Component
public class MatrixTransformer {

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
     * Machine–learning based correction parameters
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
            // second‐order plus distance augmentation formula.
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
                        .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s]", " ")
                        .split("\\s+"))
                .filter(t -> t.length() > 1)
                .limit(60)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Cosine similarity between two token sets (set–based approximation). */
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
     * Machine‐learning correction functions from {스터프14}
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
}