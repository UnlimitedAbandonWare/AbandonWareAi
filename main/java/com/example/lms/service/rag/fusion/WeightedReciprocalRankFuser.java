package com.example.lms.service.rag.fusion;

import com.example.lms.service.config.HyperparameterService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.util.MetadataUtils;
import com.nova.protocol.fusion.NovaNextFusionService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.stream.Collectors;




/**
 * A weighted variant of the {@link ReciprocalRankFuser} that allows
 * sources to contribute with different magnitudes.  Each source list in the
 * input corresponds to a separate retrieval strategy (e.g. BM25, embedding
 * similarity, cross-encoder rerankers) and can be assigned a weight via the
 * {@link HyperparameterService}.  The final score for a document is the
 * sum over all sources of {@code weight_i / (k + rank_i)} where {@code rank_i}
 * denotes the 1-based index of the document within the {@code i}th source
 * list.  Documents appearing in multiple sources accumulate their scores.
 *
 * <p>Weights are looked up by logical names rather than numeric indices to
 * decouple the ordering of source lists from the configuration.  By default
 * the following keys are used for the first three sources: {@code w_ce},
 * {@code w_bm25} and {@code w_sem}.  Additional sources fall back to a
 * weight of {@code 1.0}.  Custom keys may be supplied via
 * {@code retrieval.fusion.rrf.weights} (comma-separated list) in
 * {@code application.yml}; see the constructor for details.</p>
 */
@Component
public class WeightedReciprocalRankFuser {
    private static final Logger log = LoggerFactory.getLogger(WeightedReciprocalRankFuser.class);

    /**
     * The default names corresponding to the first few source lists.  These
     * names align with common retrieval strategies: cross-encoder ({@code ce}),
     * BM25 ({@code bm25}) and semantic embedding ({@code sem}).  If a source
     * list index exceeds this array length the weight defaults to 1.0.
     */
    private static final String[] DEFAULT_WEIGHT_KEYS = {"w_ce", "w_bm25", "w_sem"};

    /**
     * The k constant used in the RRF formula.  Defaults to 60 if not
     * overridden via configuration.  A larger k will attenuate the influence
     * of lower ranks while a smaller k emphasises top-ranked items.
     */
    private final int k;

    /**
     * Optional hyperparameter service used to retrieve runtime weight
     * assignments.  When absent or when a key is missing a weight of 1.0
     * is assumed.  Keys are expected to be present without the "w_" prefix
     * (e.g. "ce", "bm25", "sem").  The prefix is added automatically.
     */
    private final HyperparameterService hp;

    /**
     * Comma-separated list of custom weight keys.  When provided the i-th
     * element of this list is used to look up the weight for the i-th source
     * list.  Elements may be empty or undefined; such entries default to
     * {@code 1.0}.  If the list is shorter than the number of source lists
     * additional sources default to a weight of {@code 1.0}.
     */
    private final List<String> configuredKeys;

    @Autowired(required = false)
    private NovaNextFusionService novaNextFusionService;

    @Autowired
    public WeightedReciprocalRankFuser(
            @Value("${retrieval.fusion.rrf.k:60}") int k,
            @Autowired(required = false) HyperparameterService hp,
            @Value("${retrieval.fusion.rrf.weights:}") String weightKeys) {
        this.k = Math.max(1, k);
        this.hp = hp;
        // Parse the configured weight keys from a comma-separated string.
        if (weightKeys != null && !weightKeys.isBlank()) {
            this.configuredKeys = Arrays.stream(weightKeys.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        } else {
            this.configuredKeys = Collections.emptyList();
        }
    }

    /**
     * Fuse multiple ranked lists using a weighted reciprocal rank fusion.  The
     * score for each document is computed as the sum across source lists of
     * {@code w_i / (k + rank_i)}.  Documents not present in a particular
     * source list do not contribute a term for that list.  A document that
     * appears in multiple lists will accumulate contributions from each
     * occurrence.
     *
     * @param sourceLists the lists of documents produced by different retrieval
     *                    strategies.  Each sublist must be sorted in
     *                    descending relevance order.  Null sublists are
     *                    ignored.
     * @param weights     the per-source weights.  If the array is shorter
     *                    than {@code sourceLists.size()} the remaining sources
     *                    default to 1.0.  A null array will cause the method
     *                    to derive weights from the configured keys and
     *                    hyperparameter service.
     * @param topK        the maximum number of results to return.  Negative or
     *                    zero values default to 1.
     * @return a list of fused content ordered by decreasing weighted score.
     */
    public List<Content> fuse(@Nullable List<List<Content>> sourceLists, @Nullable List<Double> weights, int topK) {
        int inputCount = countInputContents(sourceLists);
        if (sourceLists == null || sourceLists.isEmpty()) {
            traceRrf(inputCount, 0, "empty_input");
            return Collections.emptyList();
        }
        // Determine weights for each source list.  If explicit weights are
        // provided use them, otherwise derive from configuration.
        double[] w = new double[sourceLists.size()];
        for (int i = 0; i < w.length; i++) {
            double weight;
            if (weights != null && i < weights.size() && weights.get(i) != null) {
                weight = weights.get(i);
            } else {
                weight = deriveWeightForIndex(i);
            }
            w[i] = (weight > 0.0) ? weight : 1.0;
        }
        Map<String, Double> scores = new HashMap<>();
        Map<String, Content> firstAppearance = new LinkedHashMap<>();
        Map<String, List<Double>> sourceContributions = new LinkedHashMap<>();
        Map<String, Integer> firstRank = new HashMap<>();
        Map<String, String> sourceLabels = new HashMap<>();
        String queryLang = safeQueryLang();
        for (int i = 0; i < sourceLists.size(); i++) {
            List<Content> list = sourceLists.get(i);
            if (list == null) continue;
            int rank = 0;
            double weight = w[i];
            for (Content content : list) {
                if (content == null) continue;
                rank++;
                String key = keyOf(content);
                firstAppearance.putIfAbsent(key, content);
                firstRank.merge(key, rank, Math::min);
                sourceLabels.putIfAbsent(key, safeSourceLabel(content, i));
                double term = (weight / (k + rank)) * (1.0d + langAlignBonus(safeSourceLang(content), queryLang));
                scores.merge(key, term, Double::sum);
                sourceContributions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(term);
            }
        }
        if (scores.isEmpty()) {
            traceRrf(inputCount, 0, "no_scores");
            return Collections.emptyList();
        }
        List<Map.Entry<String, Double>> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(Math.max(1, topK))
                .collect(Collectors.toList());
        ranked = postProcessGrandas(ranked, firstAppearance, sourceContributions, firstRank, sourceLabels);
        List<Content> fused = ranked.stream()
                .map(e -> firstAppearance.get(e.getKey()))
                .collect(Collectors.toList());
        traceRrf(inputCount, fused.size(), fused.isEmpty() ? "empty_output" : "");
        return fused;
    }

    private static int countInputContents(@Nullable List<List<Content>> sourceLists) {
        if (sourceLists == null || sourceLists.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (List<Content> list : sourceLists) {
            if (list == null || list.isEmpty()) {
                continue;
            }
            for (Content content : list) {
                if (content != null) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void traceRrf(int inputCount, int outputCount, String skipReason) {
        try {
            TraceStore.put("rrf.input.count", Math.max(0, inputCount));
            TraceStore.put("rrf.output.count", Math.max(0, outputCount));
            TraceStore.put("rrf.fused.starvation", outputCount <= 0);
            TraceStore.put("rrf.fused.skipReason", SafeRedactor.traceLabelOrFallback(skipReason, ""));
        } catch (Exception ignored) {
            log.debug("[WeightedReciprocalRankFuser] fail-soft stage={}", "rrf.trace");
        }
    }

    private List<Map.Entry<String, Double>> postProcessGrandas(
            List<Map.Entry<String, Double>> ranked,
            Map<String, Content> firstAppearance,
            Map<String, List<Double>> sourceContributions,
            Map<String, Integer> firstRank,
            Map<String, String> sourceLabels) {
        if (ranked == null || ranked.isEmpty() || novaNextFusionService == null) {
            return ranked;
        }
        try {
            List<NovaNextFusionService.ScoredResult> request = new ArrayList<>(ranked.size());
            for (int i = 0; i < ranked.size(); i++) {
                Map.Entry<String, Double> entry = ranked.get(i);
                Content content = firstAppearance.get(entry.getKey());
                Map<String, Object> meta = metadata(content);
                NovaNextFusionService.ScoredResult sr = new NovaNextFusionService.ScoredResult();
                sr.setId(entry.getKey());
                sr.setScore(entry.getValue());
                sr.setBaseScore(entry.getValue());
                sr.setRank(firstRank.getOrDefault(entry.getKey(), i + 1));
                sr.setSource(sourceLabels.getOrDefault(entry.getKey(), "source" + i));
                List<Double> contributions = sourceContributions.getOrDefault(entry.getKey(), List.of(entry.getValue()));
                sr.setSourceScores(contributions);
                sr.setSourceCount(contributions.size());
                sr.setAuthorityAvg(metaDouble(meta, "authorityAvg", "authority_avg", "selfask_lane_gate_authority_avg"));
                sr.setStrongCitationRate(metaDouble(meta, "strongCitationRate", "strong_citation_rate", "selfask_lane_gate_strong_citation_rate"));
                sr.setDuplicateRate(metaDouble(meta, "duplicateRate", "duplicate_rate", "selfask_lane_gate_duplicate_rate"));
                sr.setContradictionRate(metaDouble(meta, "contradictionRate", "contradiction_rate", "selfask_lane_gate_contradiction_rate"));
                sr.setCrossLaneSupportRate(metaDouble(meta, "crossLaneSupportRate", "cross_lane_support_rate", "selfask_lane_gate_cross_lane_support_rate"));
                sr.setGrandasReadiness(metaDouble(meta, "grandasReadiness", "grandas_readiness", "selfask_lane_gate_grandas_readiness"));
                sr.setTailSignal(metaDouble(meta, "tailSignal", "tail_signal", "selfask_lane_gate_tail_signal", "grandas_tail_signal"));
                request.add(sr);
            }
            List<NovaNextFusionService.ScoredResult> fused = novaNextFusionService.fuse(request);
            if (fused == null || fused.isEmpty()) {
                return ranked;
            }
            Map<String, NovaNextFusionService.ScoredResult> byId = new HashMap<>();
            for (NovaNextFusionService.ScoredResult sr : fused) {
                if (sr != null && sr.getId() != null) {
                    byId.put(sr.getId(), sr);
                }
            }
            List<Map.Entry<String, Double>> out = new ArrayList<>(ranked.size());
            for (Map.Entry<String, Double> entry : ranked) {
                NovaNextFusionService.ScoredResult sr = byId.get(entry.getKey());
                if (sr == null) {
                    out.add(entry);
                    continue;
                }
                Content original = firstAppearance.get(entry.getKey());
                firstAppearance.put(entry.getKey(), markGrandas(original, sr));
                out.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), sr.getAdjustedScore()));
            }
            out.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
            return out;
        } catch (Exception e) {
            traceGrandasSkipped(e);
            log.debug("[GRANDAS] fusion post-process skipped: {}", e.getClass().getSimpleName());
            return ranked;
        }
    }

    /**
     * Fuse multiple ranked lists using runtime configured weights.  The weights
     * are determined from configuration and the hyperparameter service.  See
     * {@link #fuse(List, List, int)} for details.
     *
     * @param sourceLists lists of ranked content to be fused
     * @param topK        maximum number of results to return
     * @return fused list of content
     */
    public List<Content> fuse(List<List<Content>> sourceLists, int topK) {
        return fuse(sourceLists, null, topK);
    }

    /**
     * Derive the weight for a particular source index.  The lookup order is:
     * <ol>
     *   <li>Custom keys defined via {@code retrieval.fusion.rrf.weights}, if
     *   present.  The i-th element corresponds to the i-th source list.</li>
     *   <li>The default keys {@code w_ce}, {@code w_bm25}, {@code w_sem} for
     *   indices 0, 1 and 2 respectively.</li>
     *   <li>The hyperparameter service queried with the key (without the
     *   {@code w_} prefix).  For example index 0 would query {@code ce}.</li>
     * </ol>
     * If none of the above yield a value the weight defaults to {@code 1.0}.
     *
     * @param index the index of the source list
     * @return a positive weight
     */
    private double deriveWeightForIndex(int index) {
        // 1) From configured comma-separated keys
        if (index < configuredKeys.size()) {
            String key = configuredKeys.get(index);
            if (key != null && !key.isBlank()) {
                double v = lookupWeight(key);
                if (!Double.isNaN(v)) return v;
            }
        }
        // 2) From default weight keys (w_ce, w_bm25, w_sem)
        if (index < DEFAULT_WEIGHT_KEYS.length) {
            String key = DEFAULT_WEIGHT_KEYS[index];
            double v = lookupWeight(key.substring(2));
            if (!Double.isNaN(v)) return v;
        }
        // 3) Fallback weight
        return 1.0;
    }

    /**
     * Lookup a weight value from the hyperparameter service.  The service
     * expects keys without the "w_" prefix.  If the service is null or the
     * key is missing the method returns {@link Double#NaN} to signal the
     * absence of a configured weight.
     *
     * @param key the logical weight key (e.g. "ce", "bm25")
     * @return the weight value or NaN if unavailable
     */
    private double lookupWeight(String key) {
        if (hp == null || key == null || key.isBlank()) {
            return Double.NaN;
        }
        try {
            double v = hp.getDouble(key);
            return v;
        } catch (Exception ignored) {
            log.debug("[WeightedReciprocalRankFuser] fail-soft stage={}", "lookupWeight");
            return Double.NaN;
        }
    }

    /**
     * Generate a stable key for de-duplication. Prefer source metadata first
     * so the same URL across Self-Ask/Web/Vector lanes fuses together, then
     * fall back to a SHA-256 of normalized text.
     *
     * @param content the content object
     * @return a stable hash key
     */
    private static String keyOf(Content content) {
        try {
            TextSegment segment = content.textSegment();
            if (segment != null && segment.metadata() != null) {
                java.util.Map<String, Object> md = segment.metadata().toMap();
                for (String key : java.util.List.of(
                        "url", "uri", "source_url", "sourceUrl", "link", "href", "canonical", "permalink",
                        "doc_id", "docId", "source")) {
                    Object value = md.get(key);
                    if (value != null) {
                        String s = String.valueOf(value).trim();
                        if (!s.isBlank()) {
                            if ("source".equals(key)
                                    && ("web".equalsIgnoreCase(s) || s.toLowerCase(java.util.Locale.ROOT).startsWith("web:"))) {
                                continue;
                            }
                            return canonicalMetadataPrefix(key) + ":" + canonicalMetadataValue(key, s);
                        }
                    }
                }
            }
        } catch (Exception ignore) {
            // text fallback
            log.debug("[WeightedReciprocalRankFuser] fail-soft stage={}", "key.metadata");
        }
        String text = Optional.ofNullable(content.textSegment())
                .map(TextSegment::text)
                .orElseGet(content::toString);
        String normalized = (text == null) ? "" : text.replaceAll("\\s+", " ").trim();
        return "sha256:" + sha256(normalized.toLowerCase(java.util.Locale.ROOT));
    }

    private static String canonicalMetadataValue(String key, String value) {
        String normalized = value == null ? "" : value.trim();
        if (isUrlMetadataKey(key)) {
            String canonical = RerankCanonicalizer.canonicalKey(normalized);
            return canonical == null || canonical.isBlank() ? normalized : canonical;
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isUrlMetadataKey(String key) {
        return "url".equalsIgnoreCase(key)
                || "uri".equalsIgnoreCase(key)
                || "source_url".equalsIgnoreCase(key)
                || "sourceUrl".equalsIgnoreCase(key)
                || "link".equalsIgnoreCase(key)
                || "href".equalsIgnoreCase(key)
                || "canonical".equalsIgnoreCase(key)
                || "permalink".equalsIgnoreCase(key);
    }

    private static String canonicalMetadataPrefix(String key) {
        return isUrlMetadataKey(key) ? "url" : key;
    }

    private static Content markGrandas(Content content, NovaNextFusionService.ScoredResult sr) {
        if (content == null || content.textSegment() == null || sr == null) {
            return content;
        }
        try {
            TextSegment segment = content.textSegment();
            Map<String, Object> metadata = new LinkedHashMap<>(MetadataUtils.toMap(segment.metadata()));
            metadata.put("grandas_tail_signal", round6(sr.getTailSignal()));
            metadata.put("grandas_base_score", round6(sr.getBaseScore()));
            metadata.put("grandas_adjusted_score", round6(sr.getAdjustedScore()));
            metadata.put("grandas_guard_band", round6(sr.getGuardBand()));
            metadata.put("grandas_reason", safeGrandasReason(sr.getReason()));
            return Content.from(TextSegment.from(segment.text(), Metadata.from(metadata)));
        } catch (Exception ignored) {
            log.debug("[WeightedReciprocalRankFuser] fail-soft stage={}", "enrichGrandasMetadata");
            return content;
        }
    }

    private static String safeGrandasReason(String reason) {
        return SafeRedactor.traceLabelOrFallback(reason, "");
    }

    private static void traceGrandasSkipped(Exception e) {
        try {
            String message = e == null ? null : e.getMessage();
            TraceStore.put("rag.fusion.grandas.skipped", true);
            TraceStore.put("rag.fusion.grandas.skipReason", "nova_next_fusion_failed");
            TraceStore.put("rag.fusion.grandas.errorType",
                    SafeRedactor.traceLabelOrFallback(
                            e == null ? null : e.getClass().getSimpleName(), "unknown"));
            TraceStore.put("rag.fusion.grandas.errorHash", SafeRedactor.hashValue(message));
            TraceStore.put("rag.fusion.grandas.errorLength", message == null ? 0 : message.length());
        } catch (Exception ignored) {
            log.debug("[WeightedReciprocalRankFuser] fail-soft stage={}", "grandas.trace");
        }
    }

    private static String safeSourceLabel(Content content, int index) {
        Map<String, Object> meta = metadata(content);
        for (String key : List.of("retrieval_stage", "source_kind", "provider", "source")) {
            Object value = meta.get(key);
            if (value != null) {
                String s = String.valueOf(value).trim();
                if (!s.isBlank()) {
                    return s.length() > 40 ? s.substring(0, 40) : s;
                }
            }
        }
        return "source" + Math.max(0, index);
    }

    private static String safeQueryLang() {
        return normalizeLang(TraceStore.get("query.lang"));
    }

    private static String safeSourceLang(Content content) {
        Map<String, Object> meta = metadata(content);
        for (String key : List.of("source_lang", "sourceLang", "lang", "provider_lang")) {
            String lang = normalizeLang(meta.get(key));
            if (!lang.isBlank()) {
                return lang;
            }
        }
        Object provider = meta.get("provider");
        if (provider != null) {
            String p = String.valueOf(provider).toLowerCase(Locale.ROOT);
            if (p.contains("naver")) {
                return "ko";
            }
            if (p.contains("brave") || p.contains("google")) {
                return "en";
            }
        }
        return "";
    }

    private static double langAlignBonus(String sourceLang, String queryLang) {
        if (sourceLang == null || sourceLang.isBlank() || queryLang == null || queryLang.isBlank()) {
            return 0.0d;
        }
        return sourceLang.equals(queryLang) ? 0.15d : 0.0d;
    }

    private static String normalizeLang(Object raw) {
        if (raw == null) {
            return "";
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            return "";
        }
        if (s.startsWith("ko") || s.contains("korean")) {
            return "ko";
        }
        if (s.startsWith("en") || s.contains("english")) {
            return "en";
        }
        return s.length() > 12 ? "" : s;
    }

    private static Map<String, Object> metadata(Content content) {
        try {
            if (content != null && content.textSegment() != null) {
                return MetadataUtils.toMap(content.textSegment().metadata());
            }
        } catch (Exception ignored) {
            // empty fallback
            log.debug("[WeightedReciprocalRankFuser] fail-soft stage={}", "metadata.toMap");
        }
        return Map.of();
    }

    private static double metaDouble(Map<String, Object> meta, String... keys) {
        if (meta == null || meta.isEmpty() || keys == null) {
            return 0.0d;
        }
        for (String key : keys) {
            Object value = meta.get(key);
            if (value instanceof Number n) {
                return clamp01(n.doubleValue());
            }
            if (value instanceof String s) {
                try {
                    return clamp01(Double.parseDouble(s.trim()));
                } catch (NumberFormatException ignored) {
                    // try next
                    log.debug("[WeightedReciprocalRankFuser] fail-soft stage={} errorType={}",
                            "metaDouble.parse", "invalid_number");
                }
            }
        }
        return 0.0d;
    }

    private static double round6(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 1_000_000.0d) / 1_000_000.0d;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("[WeightedReciprocalRankFuser] fail-soft stage={}", "sha256");
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }
}
