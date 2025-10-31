
        package com.example.lms.service.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@RequiredArgsConstructor
public class UpstashVectorStoreAdapter implements EmbeddingStore<TextSegment> {
    private static final Logger log = LoggerFactory.getLogger(UpstashVectorStoreAdapter.class);

    private final WebClient webClient;

    @Value("${upstash.vector.rest-url:}")
    private String restUrl;
    @Value("${upstash.vector.api-key:}")
    private String apiKey;
    @Value("${upstash.vector.namespace:aw-default}")
    private String namespace;

    private String authHeader() { return "Bearer " + apiKey; }
    private static boolean blank(String s) { return s == null || s.isBlank(); }

    @Override public String add(Embedding embedding) {
        String id = UUID.randomUUID().toString();
        add(id, embedding);
        return id;
    }
    @Override public void add(String id, Embedding embedding) {
        upsertPoints(List.of(id), List.of(embedding), List.of((TextSegment) null));
    }
    @Override public String add(Embedding embedding, TextSegment embedded) {
        String id = idOf(embedded);
        upsertPoints(List.of(id), List.of(embedding), List.of(embedded));
        return id;
    }
    @Override public List<String> addAll(List<Embedding> embeddings) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) ids.add(UUID.randomUUID().toString());
        upsertPoints(ids, embeddings, Collections.nCopies(embeddings.size(), null));
        return ids;
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        if (blank(restUrl) || blank(apiKey)) {
            return new EmbeddingSearchResult<>(List.of()); // 변경됨
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("vector", toList(request.queryEmbedding()));
            body.put("topK", request.maxResults());
            body.put("namespace", namespace);

            String resp = webClient.post().uri(restUrl + "/vectors/query")
                    .header("Authorization", authHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(8))
                    .onErrorReturn("")
                    .block();

            if (resp == null || resp.isBlank()) {
                return new EmbeddingSearchResult<>(List.of());
            }

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var matches = mapper.readTree(resp).path("matches"); // [{id, score, metadata}]
            if (!matches.isArray()) {
                return new EmbeddingSearchResult<>(List.of());
            }

            List<EmbeddingMatch<TextSegment>> out = new ArrayList<>();
            for (var n : matches) {
                String id = n.path("id").asText("");
                double score = n.path("score").asDouble(0);
                var mdNode = n.path("metadata");
                Map<String, Object> md = mapper.convertValue(mdNode, Map.class);
                TextSegment seg = TextSegment.from(
                        (String) md.getOrDefault("text", ""),
                        // Metadata.from(Map) 시그니처 유지
                        dev.langchain4j.data.document.Metadata.from(md)
                );
                out.add(new EmbeddingMatch<>(score, id, request.queryEmbedding(), seg));
            }
            return new EmbeddingSearchResult<>(out); // 변경됨

        } catch (Exception e) {
            log.warn("Upstash query failed", e);
            return new EmbeddingSearchResult<>(List.of()); // 변경됨
        }
    }

    private void upsertPoints(List<String> ids, List<Embedding> embs, List<TextSegment> segs) {
        if (blank(restUrl) || blank(apiKey)) {
            log.warn("Upstash not configured; skip upsert");
            return;
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < embs.size(); i++) {
            Map<String, Object> meta = segs.get(i) == null ? Map.of() : toMeta(segs.get(i));
            points.add(Map.of(
                    "id", ids.get(i),
                    "vector", toList(embs.get(i)),
                    "metadata", meta,
                    "namespace", namespace
            ));
        }
        Map<String, Object> payload = Map.of("vectors", points);
        try {
            webClient.post().uri(restUrl + "/vectors/upsert")
                    .header("Authorization", authHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(8))
                    .onErrorReturn("")
                    .block();
        } catch (Exception e) {
            log.warn("Upstash upsert failed", e);
        }
    }

    private List<Float> toList(Embedding e) {
        float[] arr = e.vector();
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add(v);
        return list;
    }
    private String idOf(TextSegment seg) {
        String raw = seg == null ? UUID.randomUUID().toString() : seg.text();
        return org.apache.commons.codec.digest.DigestUtils.sha1Hex(raw);
    }
    private Map<String, Object> toMeta(TextSegment seg) {
        Map<String, Object> m = new HashMap<>();
        if (seg != null) {
            m.put("text", seg.text());
            // 메타키 추출: asMap()/toMap()/map() 중 존재하는 메서드만 리플렉션으로 사용
            try {
                Object meta = seg.metadata();
                if (meta != null) {
                    for (String fn : List.of("asMap", "toMap", "map")) {
                        try {
                            var method = meta.getClass().getMethod(fn);
                            Object ret = method.invoke(meta);
                            // --- 이 블록이 변경됨 ---
                            if (ret instanceof Map<?, ?> map) {
                                // 키를 문자열로 강제 변환하여 제네릭 경계 충족
                                map.forEach((k, v) -> m.put(String.valueOf(k), v));
                            }
                            break;
                        } catch (NoSuchMethodException ignore) { }
                    }
                }
            } catch (Exception ignore) { }
        }
        return m;
    }
}