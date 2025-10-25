package com.example.lms.vector;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Primary;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


import dev.langchain4j.store.embedding.filter.Filter;;
/**
 * FederatedEmbeddingStore composes multiple {@link EmbeddingStore} instances
 * into a single store.  During search operations it consults the
 * {@link TopicRoutingSettings} to determine how many results to request
 * from each store based on the desired topK and the inferred topic.  It
 * then merges, normalises and deduplicates the results to produce a
 * unified list.  Write operations fan-out to all stores but failures in
 * any individual store are suppressed (fail-soft).
 */
@RequiredArgsConstructor
@Component
@Primary
public class FederatedEmbeddingStore implements EmbeddingStore<TextSegment> {
    private static final Logger log = LoggerFactory.getLogger(FederatedEmbeddingStore.class);
    /** Simple wrapper of a named EmbeddingStore. */
    public record NamedStore(String id, EmbeddingStore<TextSegment> store) {}

    private final List<NamedStore> stores;
    private final TopicRoutingSettings routing;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @Override
    public String add(dev.langchain4j.data.embedding.Embedding embedding) {
        String id = UUID.randomUUID().toString();
        addAll(List.of(embedding), List.of(TextSegment.from("", Metadata.from(Collections.emptyMap()))));
        return id;
    }

    @Override
    public void add(String id, dev.langchain4j.data.embedding.Embedding embedding) {
        add(id, embedding, null);
    }

    @Override
    public String add(dev.langchain4j.data.embedding.Embedding embedding, TextSegment embedded) {
        String id = UUID.randomUUID().toString();
        addAll(List.of(id), List.of(embedding), List.of(embedded));
        return id;
    }

    @Override
    public List<String> addAll(List<dev.langchain4j.data.embedding.Embedding> embeddings) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        addAll(ids, embeddings, Collections.nCopies(embeddings.size(), null));
        return ids;
    }

    @Override
    public List<String> addAll(List<dev.langchain4j.data.embedding.Embedding> embeddings, List<TextSegment> segments) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        addAll(ids, embeddings, segments);
        return ids;
    }

    private void add(String id, dev.langchain4j.data.embedding.Embedding embedding, TextSegment segment) {
        addAll(List.of(id), List.of(embedding), List.of(segment));
    }
    @Override
    public void addAll(List<String> ids, List<dev.langchain4j.data.embedding.Embedding> embeddings, List<TextSegment> segments) {
        for (NamedStore ns : stores) {
            try {
                ns.store().addAll(embeddings, segments);
            } catch (Exception e) {
                log.warn("Federated addAll fail-soft on store {}: {}", ns.id(), e.toString());
            }
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest req) {
        String topic = extractTopicFromFilter(req.filter()).orElse("default");

        Map<String, Double> weights = routing.weightsFor(topic);
        int k = Math.max(1, req.maxResults());
        Map<String, Integer> split = allocateK(weights, k, routing.minPerStore());
        List<Future<EmbeddingSearchResult<TextSegment>>> futures = new ArrayList<>();
        for (NamedStore ns : stores) {
            int ki = split.getOrDefault(ns.id(), 0);
            if (ki <= 0) continue;
            EmbeddingSearchRequest subReq = EmbeddingSearchRequest.builder()
                    .queryEmbedding(req.queryEmbedding())
                    .maxResults(ki)
                    .minScore(req.minScore())
                    .filter(req.filter())
                    .build();
            futures.add(pool.submit(() -> {
                try {
                    return ns.store().search(subReq);
                } catch (Exception e) {
                    log.warn("Federated search fail-soft on store {}: {}", ns.id(), e.toString());
                    return new EmbeddingSearchResult<>(Collections.emptyList());
                }
            }));
        }
        List<EmbeddingMatch<TextSegment>> merged = new ArrayList<>();
        for (Future<EmbeddingSearchResult<TextSegment>> f : futures) {
            try {
                EmbeddingSearchResult<TextSegment> r = f.get(5, TimeUnit.SECONDS);
                if (r != null && r.matches() != null) {
                    merged.addAll(r.matches());
                }
            } catch (Exception ignored) {
                // ignore timeouts and failures
            }
        }
        if (merged.isEmpty()) {
            log.info("ROUTE_LABEL topic={} weights={} split={} k={} stores={}", topic, weights, split, k, stores.stream().map(NamedStore::id).toList());
            return new EmbeddingSearchResult<>(Collections.emptyList());
        }
        double min = merged.stream().mapToDouble(EmbeddingMatch::score).min().orElse(0.0);
        double max = merged.stream().mapToDouble(EmbeddingMatch::score).max().orElse(1.0);
        Map<String, EmbeddingMatch<TextSegment>> dedup = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> m : merged) {
            double norm = (max > min) ? (m.score() - min) / (max - min) : m.score();
            String key = keyOf(m.embedded());
            EmbeddingMatch<TextSegment> prev = dedup.get(key);
            if (prev == null || norm > prev.score()) {
                dedup.put(key, new EmbeddingMatch<>(norm, m.embeddingId(), m.embedding(), m.embedded()));
            }
        }
        List<EmbeddingMatch<TextSegment>> sorted = dedup.values().stream()
                .sorted(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed())
                .limit(k)
                .collect(Collectors.toList());
        log.info("ROUTE_LABEL topic={} weights={} split={} k={} stores={}", topic, weights, split, k, stores.stream().map(NamedStore::id).toList());
        return new EmbeddingSearchResult<>(sorted);
    }

    private Map<String, Integer> allocateK(Map<String, Double> weights, int k, int minPerStore) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (weights == null || weights.isEmpty()) {
            int each = Math.max(minPerStore, k / Math.max(1, stores.size()));
            for (NamedStore ns : stores) {
                out.put(ns.id(), each);
            }
        } else {
            double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
            int assigned = 0;
            for (NamedStore ns : stores) {
                double w = weights.getOrDefault(ns.id(), 0.0);
                int v = (int) Math.round(k * (total > 0 ? (w / total) : 0));
                out.put(ns.id(), v);
                assigned += v;
            }
            while (assigned < k) {
                String best = weights.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(stores.get(0).id());
                out.put(best, out.getOrDefault(best, 0) + 1);
                assigned++;
            }
            while (assigned > k) {
                String worst = weights.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(stores.get(0).id());
                int cur = out.getOrDefault(worst, 0);
                if (cur > 0) {
                    out.put(worst, cur - 1);
                    assigned--;
                } else {
                    break;
                }
            }
        }
        if (minPerStore > 0) {
            int sum = out.values().stream().mapToInt(Integer::intValue).sum();
            for (NamedStore ns : stores) {
                out.put(ns.id(), Math.max(minPerStore, out.getOrDefault(ns.id(), 0)));
            }
            sum = out.values().stream().mapToInt(Integer::intValue).sum();
            while (sum > k) {
                String target = weights.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(stores.get(0).id());
                int cur = out.getOrDefault(target, 0);
                if (cur > minPerStore) {
                    out.put(target, cur - 1);
                    sum--;
                } else {
                    Optional<String> alt = out.entrySet().stream().filter(e -> e.getValue() > minPerStore).map(Map.Entry::getKey).findFirst();
                    if (alt.isPresent()) {
                        out.put(alt.get(), out.get(alt.get()) - 1);
                        sum--;
                    } else {
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static String keyOf(TextSegment seg) {
        if (seg == null) return "";
        String text = seg.text();
        String source = "";
        try {
            if (seg.metadata() != null) {
                source = Optional.ofNullable(seg.metadata().getString("source")).orElse("");
            }
        } catch (Exception ignored) {
        }
        return Integer.toHexString(Objects.hash(text, source));
    }
    /**
     * Safely extracts the value of a key (e.g., "topic") from a simple ComparisonFilter.
     * This helper method avoids ClassCastExceptions and handles nulls gracefully.
     * NOTE: It currently supports simple "key = value" filters and not complex logical operators.
     * @param filter The filter to inspect.
     * @return An Optional containing the value if found, otherwise empty.
     */
    /**
     * [HIGH-RISK] Extracts a metadata value from a LangChain4j 1.0.1 Filter object by parsing its toString() representation.
     * This is a brittle workaround due to the lack of introspection APIs for Filter objects in this specific library version.
     * It assumes a filter created like: {@code metadataKey("topic").isEqualTo("some-value")}.
     *
     * @param filter The Filter object to inspect.
     * @return An Optional containing the value for the 'topic' key if found.
     */
    private Optional<String> extractTopicFromFilter(Filter filter) {
        if (filter == null) {
            return Optional.empty();
        }

        // This regex pattern is designed to match the typical toString() output of a simple metadata filter in LangChain4j v1.0.1.
        // Example: "MetadataFilter { key = 'topic', condition = EQUAL_TO, value = 'some-value' }"
        Pattern pattern = Pattern.compile("key\\s*=\\s*'topic'\\s*,\\s*.*?value\\s*=\\s*'(.*?)'");
        Matcher matcher = pattern.matcher(filter.toString());

        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }

        return Optional.empty();
    }
}