package com.example.lms.service.vector;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.config.PineconeProps;
import com.example.lms.search.TraceStore;
import com.example.lms.vector.EmbeddingFingerprint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.*;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.*;
import dev.langchain4j.store.embedding.filter.logical.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/** Pinecone dense/cosine REST adapter for the retained LangChain4j 1.0.1 interface.
 * Does not create/resize indexes, change legacy namespaces, or substitute a local write.
 */
public final class PineconeVectorStoreAdapter implements EmbeddingStore<TextSegment> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WebClient http;
    private final PineconeProps props;
    private final EmbeddingFingerprint fingerprint;
    private final Duration timeout;
    private volatile String verifiedHost;

    public PineconeVectorStoreAdapter(WebClient http, PineconeProps props,
                                     EmbeddingFingerprint fingerprint, Duration timeout) {
        this.http = Objects.requireNonNull(http);
        this.props = Objects.requireNonNull(props);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.timeout = timeout;
    }

    private synchronized String host() {
        if (verifiedHost != null) return verifiedHost;
        if (ConfigValueGuards.isMissing(props.getApiKey())) throw failure("missing_api_key");
        String name = props.getIndex();
        if (name == null || !name.matches("[a-z0-9][a-z0-9-]{0,44}")) throw failure("invalid_index_name");
        JsonNode index = request(HttpMethod.GET, "https://api.pinecone.io/indexes/" + name, null, "describe");
        if (!index.path("status").path("ready").asBoolean()) throw failure("index_not_ready");
        if (index.path("dimension").asInt(-1) != fingerprint.dimensions()) throw failure("dimension_mismatch");
        if (!"cosine".equals(index.path("metric").asText())
                || !"dense".equals(index.path("vector_type").asText("dense"))) throw failure("unsupported_index_type");
        String actual = index.path("host").asText();
        if (!actual.matches("[a-z0-9.-]+\\.pinecone\\.io")) throw failure("invalid_index_host");
        String configured = props.getHost();
        if (configured != null && !configured.isBlank()
                && !configured.trim().replaceFirst("^https://", "").replaceFirst("/$", "").equals(actual))
            throw failure("index_host_mismatch");
        verifiedHost = "https://" + actual;
        TraceStore.put("vector.pinecone.dimension", fingerprint.dimensions());
        return verifiedHost;
    }

    private void validate(Embedding embedding) {
        if (embedding == null || embedding.vector().length != fingerprint.dimensions())
            throw failure("query_dimension_mismatch");
        double norm = 0;
        for (float value : embedding.vector()) {
            if (!Float.isFinite(value)) throw failure("invalid_vector");
            norm += (double) value * value;
        }
        if (norm == 0) throw failure("empty_vector");
    }

    @Override public String add(Embedding embedding) {
        String id = UUID.randomUUID().toString(); add(id, embedding); return id;
    }
    @Override public void add(String id, Embedding embedding) {
        addAll(List.of(id), List.of(embedding), null);
    }
    @Override public String add(Embedding embedding, TextSegment segment) {
        return addAll(List.of(embedding), List.of(segment)).get(0);
    }
    @Override public List<String> addAll(List<Embedding> embeddings) { return addAll(embeddings, null); }
    @Override public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        List<String> ids = embeddings.stream().map(e -> UUID.randomUUID().toString()).toList();
        addAll(ids, embeddings, segments); return ids;
    }
    @Override public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        if (ids.size() != embeddings.size() || (segments != null && segments.size() != ids.size()))
            throw failure("batch_size_mismatch");
        List<Map<String, Object>> vectors = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            validate(embeddings.get(i));
            if (ids.get(i) == null || ids.get(i).isBlank()) throw failure("invalid_id");
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (segments != null && segments.get(i) != null) {
                metadata.putAll(segments.get(i).metadata().toMap());
                metadata.put("text_segment", segments.get(i).text());
            }
            Object originalFp = metadata.get(EmbeddingFingerprint.META_EMB_FP);
            if (originalFp != null && !fingerprint.matches(String.valueOf(originalFp))) throw failure("fingerprint_mismatch");
            metadata.put(EmbeddingFingerprint.META_EMB_FP, fingerprint.fingerprint());
            metadata.put(EmbeddingFingerprint.META_EMB_PROVIDER, fingerprint.provider());
            metadata.put(EmbeddingFingerprint.META_EMB_MODEL, fingerprint.model());
            metadata.put(EmbeddingFingerprint.META_EMB_DIM, fingerprint.dimensions());
            metadata.putIfAbsent("sid", "__PRIVATE__");
            metadata.values().removeIf(Objects::isNull);
            vectors.add(Map.of("id", ids.get(i), "values", embeddings.get(i).vectorAsList(), "metadata", metadata));
        }
        if (vectors.isEmpty()) return;
        String endpoint = host() + "/vectors/upsert";
        for (int start = 0; start < vectors.size(); start += 100) {
            List<Map<String, Object>> batch = vectors.subList(start, Math.min(start + 100, vectors.size()));
            JsonNode response = request(HttpMethod.POST, endpoint,
                    Map.of("namespace", namespace(), "vectors", batch), "upsert");
            if (response.path("upsertedCount").asInt(-1) != batch.size()) throw failure("upsert_count_mismatch");
        }
    }

    @Override public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest search) {
        validate(search.queryEmbedding());
        // Enforce both space identity and the caller's ownership filter BEFORE top-K.
        Map<String, Object> caller = search.filter() == null ? comparison("sid", "$eq", "__PRIVATE__") : filter(search.filter(), false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", namespace());
        body.put("vector", search.queryEmbedding().vectorAsList());
        body.put("topK", Math.max(1, Math.min(1000, search.maxResults())));
        body.put("includeMetadata", true);
        body.put("includeValues", false);
        body.put("filter", Map.of("$and", List.of(caller, comparison("emb_fp", "$eq", fingerprint.fingerprint()))));
        JsonNode response = request(HttpMethod.POST, host() + "/query", body, "query");
        if (!response.path("matches").isArray()) throw failure("invalid_query_response");
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        for (JsonNode row : response.path("matches")) {
            JsonNode md = row.path("metadata");
            if (!fingerprint.matches(md.path("emb_fp").asText())) continue;
            String text = md.path("text_segment").asText(md.path("text").asText(""));
            if (text.isBlank() || !row.path("score").isNumber()) continue;
            Map<String, Object> map = JSON.convertValue(md, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            map.remove("text_segment"); map.remove("text");
            TextSegment segment = TextSegment.from(text, Metadata.from(map));
            // Defence in depth against a malformed service response; never relax an unknown filter.
            if (search.filter() != null && !search.filter().test(segment.metadata())) continue;
            double score = RelevanceScore.fromCosineSimilarity(row.path("score").asDouble());
            if (score >= search.minScore()) matches.add(new EmbeddingMatch<>(score, row.path("id").asText(), null, segment));
        }
        TraceStore.put("vector.pinecone.rawMatchCount", response.path("matches").size());
        TraceStore.put("vector.pinecone.matchCount", matches.size());
        return new EmbeddingSearchResult<>(matches);
    }

    @Override public void removeAll(Collection<String> ids) {
        if (!ids.isEmpty()) request(HttpMethod.POST, host() + "/vectors/delete",
                Map.of("namespace", namespace(), "ids", List.copyOf(ids)), "delete_ids");
    }

    private String namespace() { return props.getNamespace() == null ? "" : props.getNamespace(); }

    static Map<String, Object> filter(Filter filter, boolean negated) {
        if (filter instanceof Not f) return filter(f.expression(), !negated);
        if (filter instanceof And f) return Map.of(negated ? "$or" : "$and", List.of(filter(f.left(), negated), filter(f.right(), negated)));
        if (filter instanceof Or f) return Map.of(negated ? "$and" : "$or", List.of(filter(f.left(), negated), filter(f.right(), negated)));
        if (filter instanceof IsEqualTo f) return comparison(f.key(), negated ? "$ne" : "$eq", f.comparisonValue());
        if (filter instanceof IsNotEqualTo f) return comparison(f.key(), negated ? "$eq" : "$ne", f.comparisonValue());
        if (filter instanceof IsGreaterThan f) return comparison(f.key(), negated ? "$lte" : "$gt", f.comparisonValue());
        if (filter instanceof IsGreaterThanOrEqualTo f) return comparison(f.key(), negated ? "$lt" : "$gte", f.comparisonValue());
        if (filter instanceof IsLessThan f) return comparison(f.key(), negated ? "$gte" : "$lt", f.comparisonValue());
        if (filter instanceof IsLessThanOrEqualTo f) return comparison(f.key(), negated ? "$gt" : "$lte", f.comparisonValue());
        if (filter instanceof IsIn f) return comparison(f.key(), negated ? "$nin" : "$in", f.comparisonValues());
        if (filter instanceof IsNotIn f) return comparison(f.key(), negated ? "$in" : "$nin", f.comparisonValues());
        throw failure("unsupported_filter");
    }

    private static Map<String, Object> comparison(String key, String op, Object value) {
        if (key == null || key.isBlank() || key.startsWith("$") || value == null) throw failure("invalid_filter");
        return Map.of(key, Map.of(op, value));
    }

    private JsonNode request(HttpMethod method, String url, Object body, String operation) {
        long began = System.nanoTime();
        TraceStore.inc("vector.pinecone.wireAttemptCount");
        TraceStore.put("vector.pinecone.operation", operation);
        try {
            var request = http.method(method).uri(url).header("Api-Key", props.getApiKey())
                    .header("X-Pinecone-API-Version", "2025-10").contentType(MediaType.APPLICATION_JSON);
            JsonNode response = (body == null ? request : request.bodyValue(body)).retrieve()
                    .onStatus(status -> status.isError(), r -> {
                        int status = r.statusCode().value();
                        return r.releaseBody().then(Mono.error(failure("http_" + status)));
                    }).bodyToMono(JsonNode.class).timeout(timeout).block(timeout.plusMillis(100));
            if (response == null) throw failure("empty_response");
            TraceStore.put("vector.pinecone.status", "ok");
            return response;
        } catch (RuntimeException error) {
            String reason = error instanceof IllegalStateException && error.getMessage() != null
                    && error.getMessage().matches("pinecone_[a-z0-9_]+") ? error.getMessage().substring(9) : "request_failed";
            throw failure(reason);
        } finally {
            long elapsed = (System.nanoTime() - began) / 1_000_000;
            TraceStore.put("vector.pinecone." + operation + ".latencyMs", elapsed);
            org.slf4j.LoggerFactory.getLogger("rag.pipeline").debug(
                    "[rag-pipeline] stage=vector operation={} elapsedMs={}", operation, elapsed);
        }
    }

    private static IllegalStateException failure(String reason) {
        TraceStore.put("vector.pinecone.status", "unavailable");
        TraceStore.put("vector.pinecone.disabledReason", reason);
        return new IllegalStateException("pinecone_" + reason);
    }
}
