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
import com.example.lms.search.TraceStore;
import org.springframework.stereotype.Component;




/**
 * MatrixTransformer - a multi-source context synthesizer.
 *
 * <p>This class ingests snippets from live web search, vector RAG and long-term
 * memory and produces a unified prompt context along with a set of
 * reinforcement candidates. It uses deterministic authority ordering,
 * novelty scoring, and bounded correction multipliers without assembling the
 * final chat prompt.
 */
@Component("matrixTransformer")
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

    /**
     * Entry point for the transformer. Accepts session id and context
     * snippets, normalizes and deduplicates them, ranks the slices using
     * novelty scoring, dynamically allocates line limits per section, and
     * returns a context-only bundle.
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
        Map<String, Integer> allocations = allocateLineBudget(counts, totalLines, 4);
        int webLines = allocations.getOrDefault("WEB", 0);
        int ragLines = allocations.getOrDefault("RAG", 0);
        int memLines = allocations.getOrDefault("MEM", 0);
        traceGateContract();

        // Build each section by limiting to the computed line counts
        String webSection = joinSection(selected, "WEB", webLines);
        String ragSection = joinSection(selected, "RAG", ragLines);
        String memSection = joinSection(selected, "MEM", memLines);

        // Build unified context sections only; final prompt assembly belongs to PromptBuilder.
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
     * Computes deterministic line budgets for active sections. The sum never
     * exceeds {@code totalCap}; inactive sections stay at zero.
     *
     * @param counts   map of counts per section
     * @param totalCap total lines available across all sections
     * @param minEach  preferred minimum for active sections when capacity allows
     * @return ordered budgets for WEB, RAG and MEM
     */
    static Map<String, Integer> allocateLineBudget(Map<String, Integer> counts, int totalCap, int minEach) {
        List<String> tags = List.of("WEB", "RAG", "MEM");
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (String tag : tags) {
            out.put(tag, 0);
        }
        if (counts == null || totalCap <= 0) {
            return out;
        }

        List<String> active = tags.stream()
                .filter(tag -> counts.getOrDefault(tag, 0) > 0)
                .toList();
        if (active.isEmpty()) {
            return out;
        }

        int totalCount = active.stream().mapToInt(tag -> counts.getOrDefault(tag, 0)).sum();
        int floor = Math.max(0, Math.min(minEach, totalCap / active.size()));
        int used = 0;
        for (String tag : active) {
            int value = Math.min(floor, totalCap - used);
            out.put(tag, value);
            used += value;
        }

        int remaining = Math.max(0, totalCap - used);
        Map<String, Double> remainders = new HashMap<>();
        for (String tag : active) {
            double exact = totalCount == 0 ? 0.0d : ((double) counts.getOrDefault(tag, 0) / totalCount) * remaining;
            int whole = (int) Math.floor(exact);
            out.put(tag, out.get(tag) + whole);
            remainders.put(tag, exact - whole);
        }

        int allocated = out.values().stream().mapToInt(Integer::intValue).sum();
        int leftover = Math.max(0, totalCap - allocated);
        active.stream()
                .sorted(Comparator.<String>comparingDouble(remainders::get).reversed()
                        .thenComparingInt(tags::indexOf))
                .limit(leftover)
                .forEach(tag -> out.put(tag, out.get(tag) + 1));

        int sum = out.values().stream().mapToInt(Integer::intValue).sum();
        while (sum > totalCap) {
            String last = tags.stream()
                    .filter(tag -> out.getOrDefault(tag, 0) > 0)
                    .reduce((a, b) -> b)
                    .orElse(null);
            if (last == null) {
                break;
            }
            out.put(last, out.get(last) - 1);
            sum--;
        }
        return out;
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
     * Rank the provided slices using novelty scoring and a bounded neutral
     * correction multiplier. Authority order is preserved before score and
     * recency ordering.
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
            double correction = correctionMultiplier(novelty);
            double correctionFactor = correction;
            // Apply both novelty and correction factors to the slice score
            double score = s.score * noveltyFactor * correctionFactor;
            acc.add(new Slice(s.tag, s.text, score, s.tokens, s.ts));
            if (acc.size() >= cap) break;
        }
        return acc.stream()
                .sorted(Comparator
                        .comparingInt((Slice s) -> authorityOrder(s.tag))
                        .thenComparing(Comparator.comparingDouble((Slice s) -> s.score).reversed())
                        .thenComparing(Comparator.comparingLong((Slice s) -> s.ts).reversed()))
                .toList();
    }

    static double correctionMultiplier(double novelty) {
        double n = clamp01(novelty);
        double factor = 1.0d + 0.20d * (n - 0.50d);
        if (!Double.isFinite(factor)) {
            return 1.0d;
        }
        return Math.max(0.75d, Math.min(1.25d, factor));
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
     * Build context sections only. Final prompt assembly belongs to
     * PromptBuilder.build(PromptContext).
     */
    private static String buildUnified(String web, String rag, String mem) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(web)) parts.add("### LIVE WEB RESULTS (highest priority)\n" + web);
        if (!isBlank(rag)) parts.add("### VECTOR RAG\n" + rag);
        if (!isBlank(mem)) parts.add("### LONG-TERM MEMORY\n" + mem);
        if (parts.isEmpty()) return "";
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

    private static void traceGateContract() {
        TraceStore.put("matrix.gate.mode", "deterministic_source_gate");
        TraceStore.put("matrix.gate.disabledReason", "not_applicable");
        TraceStore.put("matrix.prompt.contract", "sections_only");
    }

    /** Utility to check if a string is null or blank. */
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** Clamp a double to the closed interval [0, 1]. */
    private static double clamp01(double x) {
        if (!Double.isFinite(x)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, x));
    }

}
