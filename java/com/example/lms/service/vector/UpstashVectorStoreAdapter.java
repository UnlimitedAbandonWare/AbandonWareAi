package com.example.lms.service.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Upstash Vector REST API adapter.
 *
 * <p>
 * Upstash Vector REST API generally uses endpoints like {@code /upsert},
 * {@code /query}.
 * Some deployments support namespace-suffixed endpoints like
 * {@code /upsert/{namespace}}, {@code /query/{namespace}}.
 * </p>
 *
 * <p>
 * Behavioral choices:
 * </p>
 * <ul>
 * <li>Reads always request metadata and data so that RAG filtering/traceability
 * can work.</li>
 * <li>Writes are gated by {@code vector.upstash.write-enabled}. When disabled,
 * writes are no-ops (fail-soft).</li>
 * <li>We store segment text as both {@code data} and {@code metadata.text} for
 * compatibility.</li>
 * </ul>
 */
@RequiredArgsConstructor
public class UpstashVectorStoreAdapter implements EmbeddingStore<TextSegment> {

    private static final Logger log = LoggerFactory.getLogger(UpstashVectorStoreAdapter.class);

    private final WebClient webClient;

    @Value("${upstash.vector.rest-url:${vector.upstash.url:}}")
    private String restUrl;

    @Value("${upstash.vector.api-key:${vector.upstash.token:}}")
    private String apiKey;

    /** Upstash namespace. Default namespace in Upstash is empty string "". */
    @Value("${upstash.vector.namespace:${vector.upstash.namespace:${vector.upstash.index:}}}")
    private String namespace;

    /** Enables writes (upserts). Read is always allowed when configured. */
    @Value("${vector.upstash.write-enabled:false}")
    private boolean writeEnabled;

    // ---- Filter support (session isolation)
    // -------------------------------------------------

    /** Enable filter translation for session isolation. */
    @Value("${vector.upstash.filter.enabled:true}")
    private boolean filterEnabled;

    /**
     * If true, translation failure results in no filter (fail-open). Default is
     * fail-closed.
     */
    @Value("${vector.upstash.filter.fail-open:false}")
    private boolean filterFailOpen;

    /** Metadata key used for session isolation. */
    @Value("${vector.upstash.filter.sid-key:sid}")
    private String sidKey;

    /** Default sid used when no filter is provided (or on fail-closed fallback). */
    @Value("${vector.upstash.filter.default-sid:__PRIVATE__}")
    private String defaultSid;

    private static final int MAX_FILTER_CHARS = 4096;

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return false;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "y".equalsIgnoreCase(s);
    }


    private String authHeader() {
        return "Bearer " + apiKey;
    }

    private static String normalizeBaseUrl(String restUrl) {
        if (restUrl == null)
            return "";
        String base = restUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String safeNamespace() {
        return namespace == null ? "" : namespace.trim();
    }

    private String endpoint(String path, boolean includeNamespace) {
        String base = normalizeBaseUrl(restUrl);
        String ns = includeNamespace ? safeNamespace() : "";
        if (!blank(ns)) {
            return base + "/" + path + "/" + ns;
        }
        return base + "/" + path;
    }

    public boolean isConfigured() {
        return !blank(restUrl) && !blank(apiKey);
    }

    public boolean isWriteEnabled() {
        return writeEnabled && isConfigured();
    }

    /** Diagnostics helper (no secrets). */
    public String namespace() {
        return safeNamespace();
    }

    /** Diagnostics helper (no secrets). */
    public String restUrlHost() {
        if (blank(restUrl)) {
            return "";
        }
        try {
            java.net.URI u = java.net.URI.create(restUrl.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            String host = u.getHost() == null ? "" : u.getHost();
            return scheme.isBlank() || host.isBlank() ? restUrl.trim() : (scheme + "://" + host);
        } catch (Exception e) {
            return restUrl.trim();
        }
    }

    /**
     * EmbeddingStore 인터페이스 구현: 기본 add 메서드
     */
    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        if (!isWriteEnabled() || embedding == null || textSegment == null) {
            return "";
        }
        String id = UUID.randomUUID().toString();
        add(id, embedding, textSegment);
        return id;
    }

    /**
     * EmbeddingStore 인터페이스 구현: ID만 있는 add 메서드
     */
    @Override
    public String add(Embedding embedding) {
        if (!isWriteEnabled() || embedding == null) {
            return "";
        }
        String id = UUID.randomUUID().toString();
        try {
            upsertPoints(List.of(id), List.of(embedding), Collections.singletonList(null));
        } catch (Exception e) {
            log.debug("Upstash add(embedding) failed (fail-soft): {}", e.toString());
        }
        return id;
    }

    /**
     * EmbeddingStore 인터페이스 구현: ID와 Embedding만 있는 add 메서드 (필수)
     */
    @Override
    public void add(String id, Embedding embedding) {
        if (!isWriteEnabled() || blank(id) || embedding == null) {
            return;
        }
        try {
            upsertPoints(List.of(id), List.of(embedding), Collections.singletonList(null));
        } catch (Exception e) {
            log.debug("Upstash add(id, embedding) failed (fail-soft): {}", e.toString());
        }
    }

    /**
     * Add with fixed id + metadata (used by writer mirror).
     * Fail-soft: no-op when write is disabled.
     */
    public void add(String id, Embedding embedding, TextSegment embedded) {
        if (!isWriteEnabled()) {
            return;
        }
        if (blank(id) || embedding == null || embedded == null) {
            return;
        }
        try {
            upsertPoints(List.of(id), List.of(embedding), List.of(embedded));
        } catch (Exception e) {
            log.debug("Upstash add(id,embedding,segment) failed (fail-soft): {}", e.toString());
        }
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(embeddings.size());
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        upsertPoints(ids, embeddings, Collections.nCopies(embeddings.size(), null));
        return ids;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        if (embeddings == null || embeddings.isEmpty()) {
            return List.of();
        }
        if (segments == null || segments.size() != embeddings.size()) {
            return addAll(embeddings);
        }
        List<String> ids = new ArrayList<>(embeddings.size());
        for (TextSegment seg : segments) {
            ids.add(idOf(seg));
        }
        upsertPoints(ids, embeddings, segments);
        return ids;
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        if (ids == null || embeddings == null || segments == null) {
            return;
        }
        if (ids.size() != embeddings.size() || ids.size() != segments.size()) {
            return;
        }
        upsertPoints(ids, embeddings, segments);
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        if (!isConfigured()) {
            return new EmbeddingSearchResult<>(List.of());
        }

        Embedding queryEmbedding = request == null ? null : request.queryEmbedding();
        if (queryEmbedding == null || queryEmbedding.vector() == null || queryEmbedding.vector().length == 0) {
            log.warn("Upstash query called with empty embedding; returning empty result");
            return new EmbeddingSearchResult<>(List.of());
        }

        int topK = Math.max(1, request.maxResults());
        double minScore = request.minScore();

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", toList(queryEmbedding));
            body.put("topK", topK);
            body.put("includeMetadata", true);
            body.put("includeData", true);
            body.put("includeVectors", false);

            String filter = buildUpstashFilter(request);
            if (!blank(filter)) {
                body.put("filter", filter);
            }

            String url = endpoint("query", true);

            String resp = webClient.post().uri(url)
                    .header("Authorization", authHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchangeToMono(response -> {
                        if (response.statusCode().isError()) {
                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .doOnNext(b -> log.warn("Upstash query error status={} body={}",
                                            response.statusCode().value(), clipBody(b)))
                                    .thenReturn("");
                        }
                        return response.bodyToMono(String.class);
                    })
                    .timeout(Duration.ofSeconds(5))
                    .onErrorReturn("")
                    .block();

            if (resp == null || resp.isBlank()) {
                return new EmbeddingSearchResult<>(List.of());
            }

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var resultArr = mapper.readTree(resp).path("result");
            if (!resultArr.isArray()) {
                // Some APIs may return {matches:[...]}; best-effort fallback
                resultArr = mapper.readTree(resp).path("matches");
            }
            if (!resultArr.isArray()) {
                return new EmbeddingSearchResult<>(List.of());
            }

            List<EmbeddingMatch<TextSegment>> out = new ArrayList<>();
            for (var n : resultArr) {
                String id = n.path("id").asText("");
                double score = n.path("score").asDouble(0);
                if (minScore > 0 && score < minScore) {
                    continue;
                }

                String data = n.path("data").asText("");
                var mdNode = n.path("metadata");
                @SuppressWarnings("unchecked")
                Map<String, Object> md = mapper.convertValue(mdNode, Map.class);
                if (md == null)
                    md = new HashMap<>();

                String text = !blank(data) ? data : Objects.toString(md.getOrDefault("text", ""), "");

                TextSegment seg = TextSegment.from(
                        text,
                        dev.langchain4j.data.document.Metadata.from(md));

                out.add(new EmbeddingMatch<>(score, id, request.queryEmbedding(), seg));
            }

            return new EmbeddingSearchResult<>(out);

        } catch (Exception e) {
            log.warn("Upstash query failed", e);
            return new EmbeddingSearchResult<>(List.of());
        }
    }

    /**
     * Fetch Upstash index info (vector counts, dimension, namespaces...) for
     * diagnostics.
     * Makes a network call; controller endpoints should secure access.
     */
    public Map<String, Object> indexInfo() {
        if (!isConfigured()) {
            return Map.of();
        }
        try {
            String url = normalizeBaseUrl(restUrl) + "/info";
            String resp = webClient.get().uri(url)
                    .header("Authorization", authHeader())
                    .exchangeToMono(response -> {
                        if (response.statusCode().isError()) {
                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .doOnNext(b -> log.warn("Upstash info error status={} body={}",
                                            response.statusCode().value(), clipBody(b)))
                                    .thenReturn("");
                        }
                        return response.bodyToMono(String.class);
                    })
                    .timeout(Duration.ofSeconds(5))
                    .onErrorReturn("")
                    .block();

            if (resp == null || resp.isBlank()) {
                return Map.of();
            }

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(resp).path("result");
            if (node.isMissingNode() || node.isNull()) {
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> out = mapper.convertValue(node, Map.class);
            return out == null ? Map.of() : out;
        } catch (Exception e) {
            log.debug("Upstash indexInfo failed (fail-soft): {}", e.toString());
            return Map.of();
        }
    }

    public List<String> listNamespaces() {
        if (!isConfigured()) {
            return List.of();
        }
        try {
            String url = endpoint("list-namespaces", false);
            String resp = webClient.get().uri(url)
                    .header("Authorization", authHeader())
                    .exchangeToMono(response -> {
                        if (response.statusCode().isError()) {
                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .doOnNext(b -> log.warn("Upstash list-namespaces error status={} body={}",
                                            response.statusCode().value(), clipBody(b)))
                                    .thenReturn("");
                        }
                        return response.bodyToMono(String.class);
                    })
                    .timeout(Duration.ofSeconds(5))
                    .onErrorReturn("")
                    .block();

            if (resp == null || resp.isBlank()) {
                return List.of();
            }

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var arr = mapper.readTree(resp).path("result");
            if (!arr.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (var n : arr) {
                String ns = n.asText("");
                out.add(ns);
            }
            return out;
        } catch (Exception e) {
            log.debug("Upstash listNamespaces failed (fail-soft): {}", e.toString());
            return List.of();
        }
    }

    private void upsertPoints(List<String> ids, List<Embedding> embs, List<TextSegment> segs) {
        if (!isWriteEnabled()) {
            if (writeEnabled && !isConfigured()) {
                log.warn("Upstash write enabled but not configured; skip upsert");
            }
            return;
        }
        if (ids == null || embs == null || ids.isEmpty() || embs.isEmpty()) {
            return;
        }

        List<Map<String, Object>> points = new ArrayList<>();
        boolean strictWrite = false;
        for (int i = 0; i < embs.size(); i++) {
            Embedding e = embs.get(i);
            if (e == null || e.vector() == null || e.vector().length == 0) {
                log.warn("Skipping Upstash upsert for id={} due to empty embedding",
                        (ids.size() > i ? ids.get(i) : "?"));
                continue;
            }

            TextSegment seg = (segs != null && i < segs.size()) ? segs.get(i) : null;
            Map<String, Object> meta = seg == null ? new HashMap<>() : toMeta(seg);

            // Compatibility: keep "text" in metadata.
            if (seg != null && !blank(seg.text())) {
                meta.putIfAbsent("text", seg.text());
            }

            // Session isolation: ensure sid exists so Upstash `filter` works reliably.
            ensureSid(meta);

            // strict_write: propagate failures to upstream when requested
            if (truthy(meta.get("strict_write")) || truthy(meta.get("strictWrite"))) {
                strictWrite = true;
            }

            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("id", ids.get(i));
            obj.put("vector", toList(e));
            obj.put("metadata", meta);
            if (seg != null && !blank(seg.text())) {
                obj.put("data", seg.text());
            }
            points.add(obj);
        }

        if (points.isEmpty()) {
            log.warn("No valid points to upsert; skipping Upstash upsert");
            return;
        }

        try {
            String url = endpoint("upsert", true);

            if (strictWrite) {
                // strict_write: fail fast so upstream can retry/backoff and avoid silent data loss.
                webClient.post().uri(url)
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        // Upstash batch upsert expects a JSON array.
                        .bodyValue(points)
                        .retrieve()
                        .onStatus(sc -> sc.isError(),
                                resp -> resp.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .flatMap(b -> Mono.error(new IllegalStateException(
                                                "Upstash upsert error status=" + resp.statusCode().value() + " body=" + clipBody(b)
                                        ))))
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(5))
                        .block();
                return;
            }

            webClient.post().uri(url)
                    .header("Authorization", authHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    // Upstash batch upsert expects a JSON array.
                    .bodyValue(points)
                    .exchangeToMono(resp -> {
                        if (resp.statusCode().isError()) {
                            return resp.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .doOnNext(b -> log.warn("Upstash upsert error status={} body={}",
                                            resp.statusCode().value(), clipBody(b)))
                                    .thenReturn(false);
                        }
                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(x -> true);
                    })
                    .timeout(Duration.ofSeconds(5))
                    .onErrorReturn(false)
                    .block();
        } catch (Exception e) {
            if (strictWrite) {
                log.warn("Upstash upsert failed (strict_write): {}", e.toString());
                throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
            }
            log.warn("Upstash upsert failed: {}", e.toString());
        }

    }

    private static String clipBody(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() > 420) {
            return t.substring(0, 420) + "...";
        }
        return t;
    }

    private List<Float> toList(Embedding e) {
        float[] arr = e.vector();
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    private String idOf(TextSegment seg) {
        // Stable-ish ID from segment text. (Used only for addAll(embeddings,segments)
        // overload)
        if (seg == null || seg.text() == null) {
            return UUID.randomUUID().toString();
        }
        return UUID.nameUUIDFromBytes(seg.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private Map<String, Object> toMeta(TextSegment seg) {
        Map<String, Object> m = new HashMap<>();
        if (seg != null) {
            // 메타키 추출: asMap()/toMap()/map() 중 존재하는 메서드만 리플렉션으로 사용
            try {
                Object meta = seg.metadata();
                if (meta != null) {
                    String[] fns = { "asMap", "toMap", "map" };
                    for (String fn : fns) {
                        try {
                            var method = meta.getClass().getMethod(fn);
                            Object ret = method.invoke(meta);
                            if (ret instanceof Map<?, ?> map) {
                                map.forEach((k, v) -> m.put(String.valueOf(k), v));
                                break;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    // ---- Filter translation / session isolation
    // ---------------------------------------------

    private String buildUpstashFilter(EmbeddingSearchRequest request) {
        if (!filterEnabled) {
            return "";
        }

        Filter f = request == null ? null : request.filter();

        // 1) Try structured translation (reflection-based).
        String expr = toUpstashFilter(f);

        // 2) Fallback: extract sid equality values from toString() (brittle but works
        // for many outputs).
        if (blank(expr) && f != null) {
            expr = trySidFilterFromToString(f);
        }

        // 3) Fail-closed default to prevent cross-session leakage.
        if (blank(expr)) {
            if (f == null) {
                return sidEquals(safeSid(defaultSid));
            }
            return filterFailOpen ? "" : sidEquals(safeSid(defaultSid));
        }

        if (expr.length() > MAX_FILTER_CHARS) {
            log.warn("Upstash filter too large ({} chars); falling back to default sid only", expr.length());
            return sidEquals(safeSid(defaultSid));
        }

        return expr;
    }

    private String safeSid(String sid) {
        return sid == null ? "" : sid.trim();
    }

    private void ensureSid(Map<String, Object> meta) {
        if (meta == null) {
            return;
        }
        String key = sanitizeMetaKey(sidKey);
        if (blank(key)) {
            key = "sid";
        }
        Object v = meta.get(key);
        if (v == null) {
            meta.put(key, safeSid(defaultSid));
            return;
        }
        if (v instanceof String s && s.isBlank()) {
            meta.put(key, safeSid(defaultSid));
        }
    }

    private String sidEquals(String sid) {
        String key = sanitizeMetaKey(sidKey);
        if (blank(key)) {
            key = "sid";
        }
        return key + " = " + quoteLiteral(sid);
    }

    /**
     * Extract sid filter values from Filter#toString() and emit either:
     * <ul>
     * <li>{@code sid = '...'} or</li>
     * <li>{@code (sid = '...' OR sid = '...')}</li>
     * </ul>
     *
     * <p>
     * We escape string literals to prevent filter injection.
     * </p>
     */
    private String trySidFilterFromToString(Filter f) {
        String key = sanitizeMetaKey(sidKey);
        if (blank(key) || f == null) {
            return "";
        }
        String s = String.valueOf(f);
        if (blank(s)) {
            return "";
        }

        Pattern p = Pattern.compile("key\\s*=\\s*'" + Pattern.quote(key) + "'\\s*,\\s*.*?value\\s*=\\s*'(.*?)'");
        Matcher m = p.matcher(s);

        Set<String> values = new LinkedHashSet<>();
        while (m.find()) {
            String v = m.group(1);
            if (v != null && !v.isBlank()) {
                values.add(v);
            }
        }

        // Unquoted numeric variant: value = 184
        if (values.isEmpty()) {
            Pattern p2 = Pattern.compile("key\\s*=\\s*'" + Pattern.quote(key) + "'\\s*,\\s*.*?value\\s*=\\s*([^,}]+)");
            Matcher m2 = p2.matcher(s);
            while (m2.find()) {
                String raw = m2.group(1);
                if (raw == null) {
                    continue;
                }
                raw = raw.trim();
                if (raw.startsWith("'") && raw.endsWith("'") && raw.length() >= 2) {
                    raw = raw.substring(1, raw.length() - 1);
                }
                if (!raw.isBlank()) {
                    values.add(raw);
                }
            }
        }

        if (values.isEmpty()) {
            return "";
        }

        if (values.size() == 1) {
            return key + " = " + quoteLiteral(values.iterator().next());
        }

        List<String> parts = new ArrayList<>();
        for (String v : values) {
            parts.add(key + " = " + quoteLiteral(v));
        }
        return "(" + String.join(" OR ", parts) + ")";
    }

    private String toUpstashFilter(Filter filter) {
        if (filter == null) {
            return "";
        }
        try {
            String expr = renderFilter(filter);
            return expr == null ? "" : expr.trim();
        } catch (Exception e) {
            log.debug("Failed to translate LangChain4j filter to Upstash filter (fail-soft): {}", e.toString());
            return "";
        }
    }

    private String renderFilter(Object filter) {
        if (filter == null) {
            return "";
        }

        // 1) MetadataFilter-like record: { key, condition, value }
        Object keyObj = reflect(filter, "key", "metadataKey");
        Object condObj = reflect(filter, "condition", "operator", "comparison", "predicate");
        if (keyObj != null && condObj != null) {
            String key = sanitizeMetaKey(String.valueOf(keyObj));
            if (!blank(key)) {
                String cond = String.valueOf(condObj);
                Object value = reflect(filter, "value", "comparisonValue", "values");
                String rendered = renderComparison(key, cond, value);
                if (!blank(rendered)) {
                    return rendered;
                }
            }
        }

        String simple = filter.getClass().getSimpleName().toLowerCase(Locale.ROOT);

        // 2) Unary NOT-like filter: { filter } / { inner } / { operand }
        if (simple.contains("not")) {
            Object inner = reflect(filter, "filter", "inner", "operand", "child", "target");
            String sub = renderFilter(inner);
            if (!blank(sub)) {
                return "NOT (" + sub + ")";
            }
        }

        // 3) Binary logical filter: { left, right }
        Object left = reflect(filter, "left", "first", "a");
        Object right = reflect(filter, "right", "second", "b");
        if (left != null && right != null) {
            String op = simple.contains("and") ? "AND" : (simple.contains("or") ? "OR" : "");
            if (!blank(op)) {
                String l = renderFilter(left);
                String r = renderFilter(right);
                if (!blank(l) && !blank(r)) {
                    return "(" + l + " " + op + " " + r + ")";
                }
            }
        }

        // 4) N-ary logical filter: { filters = [...] }
        Object filtersObj = reflect(filter, "filters", "operands");
        if (filtersObj instanceof Iterable<?> it) {
            List<String> parts = new ArrayList<>();
            for (Object o : it) {
                String sub = renderFilter(o);
                if (!blank(sub)) {
                    parts.add(sub);
                }
            }
            if (!parts.isEmpty()) {
                String op = simple.contains("and") ? "AND" : (simple.contains("or") ? "OR" : "");
                if (!blank(op)) {
                    return "(" + String.join(" " + op + " ", parts) + ")";
                }
            }
        }

        // Unknown filter type -> empty
        return "";
    }

    private String renderComparison(String key, String condition, Object valueObj) {
        if (blank(key) || blank(condition)) {
            return "";
        }
        String c = condition.toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", "");
        if (blank(c)) {
            return "";
        }

        // Unary predicates (no explicit value)
        if (c.equals("HAS_FIELD")) {
            return "HAS FIELD " + key;
        }
        if (c.equals("HAS_NOT_FIELD") || c.equals("NOT_HAS_FIELD")) {
            return "HAS NOT FIELD " + key;
        }

        String op;
        switch (c) {
            case "EQUAL_TO":
            case "EQUALS":
            case "EQ":
                op = "=";
                break;
            case "NOT_EQUAL_TO":
            case "NOT_EQUALS":
            case "NE":
                op = "!=";
                break;
            case "GREATER_THAN":
            case "GT":
                op = ">";
                break;
            case "GREATER_THAN_OR_EQUAL_TO":
            case "GTE":
                op = ">=";
                break;
            case "LESS_THAN":
            case "LT":
                op = "<";
                break;
            case "LESS_THAN_OR_EQUAL_TO":
            case "LTE":
                op = "<=";
                break;
            case "IN":
                op = "IN";
                break;
            case "NOT_IN":
                op = "NOT IN";
                break;
            case "CONTAINS":
                op = "CONTAINS";
                break;
            case "NOT_CONTAINS":
                op = "NOT CONTAINS";
                break;
            case "GLOB":
                op = "GLOB";
                break;
            case "NOT_GLOB":
                op = "NOT GLOB";
                break;
            default:
                return "";
        }

        if (op.equals("IN") || op.equals("NOT IN")) {
            List<String> lits = toLiteralList(valueObj);
            if (lits.isEmpty()) {
                return "";
            }
            return key + " " + op + " (" + String.join(", ", lits) + ")";
        }

        String lit = literal(valueObj);
        if (blank(lit)) {
            return "";
        }
        return key + " " + op + " " + lit;
    }

    private List<String> toLiteralList(Object valueObj) {
        if (valueObj == null) {
            return List.of();
        }
        if (valueObj instanceof Iterable<?> it) {
            List<String> out = new ArrayList<>();
            for (Object o : it) {
                String lit = literal(o);
                if (!blank(lit)) {
                    out.add(lit);
                }
            }
            return out;
        }
        if (valueObj.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(valueObj);
            List<String> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Object o = java.lang.reflect.Array.get(valueObj, i);
                String lit = literal(o);
                if (!blank(lit)) {
                    out.add(lit);
                }
            }
            return out;
        }
        String lit = literal(valueObj);
        return blank(lit) ? List.of() : List.of(lit);
    }

    private String literal(Object valueObj) {
        if (valueObj == null) {
            return "";
        }
        if (valueObj instanceof String s) {
            return quoteLiteral(s);
        }
        if (valueObj instanceof Number || valueObj instanceof Boolean) {
            return String.valueOf(valueObj).toLowerCase(Locale.ROOT);
        }
        if (valueObj.getClass().isEnum()) {
            return quoteLiteral(String.valueOf(valueObj));
        }
        // Fallback: treat as string
        return quoteLiteral(String.valueOf(valueObj));
    }

    private String quoteLiteral(String raw) {
        if (raw == null) {
            return "''";
        }
        // Escape single quotes by doubling them to prevent filter injection.
        String escaped = raw.replace("'", "''");
        return "'" + escaped + "'";
    }

    private String sanitizeMetaKey(String key) {
        if (blank(key)) {
            return "";
        }
        String k = key.trim();
        // Upstash identifiers commonly support dotted paths; keep it conservative.
        return k.matches("[A-Za-z0-9_\\.]+") ? k : "";
    }

    private Object reflect(Object obj, String... names) {
        if (obj == null || names == null) {
            return null;
        }
        try {
            // Record components first (Java 16+)
            if (obj.getClass().isRecord()) {
                var comps = obj.getClass().getRecordComponents();
                if (comps != null) {
                    for (var rc : comps) {
                        for (String n : names) {
                            if (rc.getName().equals(n)) {
                                return rc.getAccessor().invoke(obj);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }

        for (String n : names) {
            // Try method accessor
            try {
                var m = obj.getClass().getMethod(n);
                return m.invoke(obj);
            } catch (Exception ignore) {
            }
            // Try field
            try {
                var f = obj.getClass().getDeclaredField(n);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Exception ignore) {
            }
        }
        return null;
    }
}
