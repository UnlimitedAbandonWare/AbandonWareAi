package com.example.lms.service.rag.fusion;

import com.example.lms.service.config.HyperparameterService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
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
        if (sourceLists == null || sourceLists.isEmpty()) {
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
                double term = weight / (k + rank);
                scores.merge(key, term, Double::sum);
            }
        }
        if (scores.isEmpty()) {
            return Collections.emptyList();
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(Math.max(1, topK))
                .map(e -> firstAppearance.get(e.getKey()))
                .collect(Collectors.toList());
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
            return Double.NaN;
        }
    }

    /**
     * Generate a stable key for de-duplication.  Mirrors the behaviour of
     * {@link ReciprocalRankFuser#keyOf(Content)} by normalising whitespace
     * and using the hashed text as the key.  This method is replicated here
     * to avoid a direct dependency on the original fuser class.
     *
     * @param content the content object
     * @return a stable hash key
     */
    private static String keyOf(Content content) {
        String text = Optional.ofNullable(content.textSegment())
                .map(TextSegment::text)
                .orElseGet(content::toString);
        String normalized = (text == null) ? "" : text.replaceAll("\\s+", " ").trim();
        return Integer.toHexString(normalized.hashCode());
    }
}