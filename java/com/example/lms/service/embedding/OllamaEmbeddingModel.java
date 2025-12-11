package com.example.lms.service.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Arrays;

/**
 * Ollama /api/embed 기반 EmbeddingModel 구현.
 * embedding.provider=ollama 일 때만 활성화되고,
 * 나머지(provider=openai/hf/none)는 여기서 조용히 패스한다.
 *
 * <p><b>Matryoshka Slicing 지원</b></p>
 * Qwen3-Embedding은 MRL(Matryoshka Representation Learning) 구조로
 * 32~4096 사이의 임의 차원을 지원하며, 앞부분 N차원만 사용해도
 * 정보 손실이 최소화되도록 학습됨. 따라서 Ollama가 4096d를 반환해도
 * 이 클래스는 안전하게 앞 1536d만 슬라이싱하여 사용함.
 *
 * <p><b>Forward Compatibility</b></p>
 * Ollama가 나중에 dimensions 옵션을 제대로 지원하여 1536d를 반환하면,
 * 코드 수정 없이 Matryoshka 경고 없이 정상 동작함.
 */
@Component
@RequiredArgsConstructor
public class OllamaEmbeddingModel implements EmbeddingModel {
private static final int INDEX_DIM = 1536;



@Autowired(required = false)
@Qualifier("backupEmbeddingModel")
private EmbeddingModel backupModel;


    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingModel.class);

    private final WebClient webClient;

    @Value("${embedding.provider:ollama}")
    private String provider;

    @Value("${embedding.base-url:http://localhost:11435/api/embed}")
    private String apiUrl;   // 예: http://localhost:11434/api/embed

    @Value("${embedding.model:qwen3-embedding}")
    private String model;

    @Value("${embedding.timeout-seconds:30}")
    private long timeoutSec;

    @Value("${embedding.dimensions:1536}")
    private int dimensions;

    @Value("${embedding.dimension-guard-mode:WARN_ONLY}")
    private String dimensionGuardMode;

    @Value("${embedding.log-dimension-mismatch:true}")
    private boolean logDimensionMismatch;

    private final AtomicBoolean dimensionWarned = new AtomicBoolean(false);

    private int resolveTargetDim(int actualSize, int configuredDim, String logPrefix) {
        int targetDim = (configuredDim > 0 ? configuredDim : actualSize);

        if (actualSize == targetDim) {
            return targetDim;
        }

        String mode = nullSafe(dimensionGuardMode).trim().toUpperCase();
        if (mode.isEmpty()) {
            mode = "WARN_ONLY";
        }

        if (actualSize < targetDim) {
            String msg = String.format(
                    "[EMBED] Critical Dimension Mismatch (%s): configured=%d, actual=%d",
                    logPrefix, targetDim, actualSize
            );
            if ("DISABLED".equals(mode)) {
                if (logDimensionMismatch) {
                    log.error(msg + " - guard=DISABLED, using actual size");
                }
                return actualSize;
            }
            throw new IllegalStateException(msg);
        }

        // Matryoshka 케이스: actualSize > targetDim
        String msg = String.format(
                "[EMBED] Matryoshka Slicing Active (%s): model=%s, indexDim=%d, nativeResponse=%d " +
                        "(slicing first %d dimensions per MRL design)",
                logPrefix, nullSafe(model), targetDim, actualSize, targetDim
        );

        switch (mode) {
            case "STRICT":
                throw new IllegalStateException(msg);
            case "DISABLED":
                if (logDimensionMismatch) {
                    if (dimensionWarned.compareAndSet(false, true)) {
                        log.info(msg + " (guard=DISABLED, using actual size)");
                    } else if (log.isDebugEnabled()) {
                        log.debug(msg + " (guard=DISABLED, using actual size)");
                    }
                }
                return actualSize;
            case "WARN_ONLY":
            default:
                if (logDimensionMismatch) {
                    if (dimensionWarned.compareAndSet(false, true)) {
                        log.info(msg);
                    } else if (log.isDebugEnabled()) {
                        log.debug(msg);
                    }
                }
                return targetDim;
        }
    }

    @Override
    public Response<Embedding> embed(String text) {
        if (!"ollama".equalsIgnoreCase(nullSafe(provider))) {
            // 다른 provider가 선택된 경우 이 구현은 관여하지 않는다.
            return Response.from(null, null);
        }
        float[] vec = callOllama(text == null ? "" : text);
        return Response.from(Embedding.from(vec), null);
    }

    // TextSegment 편의 오버로드
    public Response<Embedding> embed(TextSegment segment) {
        String txt = (segment == null ? "" : segment.text());
        return embed(txt);
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        if (!"ollama".equalsIgnoreCase(nullSafe(provider))) {
            return Response.from(null, null);
        }
        List<Embedding> out = new ArrayList<>();
        if (segments == null || segments.isEmpty()) {
            return Response.from(out, null);
        }

        // 1) TextSegment → String 리스트로 변환
        List<String> texts = new ArrayList<>(segments.size());
        for (TextSegment s : segments) {
            texts.add(s == null ? "" : s.text());
        }

        // 2) 배치 호출 시도
        List<float[]> batch = callOllamaBatch(texts);

        if (batch != null && !batch.isEmpty() && batch.size() == texts.size()) {
            for (float[] vec : batch) {
                out.add(Embedding.from(vec));
            }
            return Response.from(out, null);
        }

        // 3) 배치 호출 실패 시, 안전망으로 개별 호출
        log.warn("[EMBED] Batch call failed or size mismatch (got {} for {} texts). Falling back to per-item calls.",
                (batch == null ? 0 : batch.size()), texts.size());
        for (String txt : texts) {
            out.add(Embedding.from(callOllama(txt)));
        }
        return Response.from(out, null);
    }

    /**
     * Ollama /api/embed 배치 호출
     *  - input: 문자열 리스트
     *  - options.dimensions: 설정값(dimensions)이 0보다 클 때만 명시
     */
    private List<float[]> callOllamaBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        if (texts == null || texts.isEmpty()) {
            return out;
        }
        try {
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", model);
            body.put("input", texts);

            java.util.Map<String, Object> options = new java.util.HashMap<>();
            if (dimensions > 0) {
                options.put("dimensions", dimensions);
            }
            if (!options.isEmpty()) {
                body.put("options", options);
            }

            String resp = webClient.post()
                    .uri(apiUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .block();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(resp);
            JsonNode embeddings = root.get("embeddings");
            if (embeddings == null || !embeddings.isArray() || embeddings.isEmpty()) {
                return out;
            }

            // dimension guard는 단일 호출과 동일하게 적용
            JsonNode first = embeddings.get(0);
            int actualSize = first.size();

            int targetDim = resolveTargetDim(actualSize, dimensions, "batch");

            // 각 임베딩을 targetDim 길이로 정규화
            for (int idx = 0; idx < embeddings.size(); idx++) {
                JsonNode vecNode = embeddings.get(idx);
                int size = vecNode.size();
                int copy = Math.min(size, targetDim);
                float[] vec = new float[targetDim];
                for (int i = 0; i < copy; i++) {
                    vec[i] = (float) vecNode.get(i).asDouble();
                }
                out.add(vec);
            }

            return out;
        } catch (Exception e) {
            log.warn("[EMBED] Primary (Ollama batch) failed: {}", e.toString());
            // 상위 embedAll(...)에서 개별 callOllama(...) + backupModel로 재시도한다.
            return out;
        }
    }

    private float[] callOllama(String text) {
        try {
            // Ollama Embeddings: POST /api/embed
            // body: { "model": "...", "input": "..." }
            // 응답: { "embeddings": [ [ ..float.. ] ], ... }
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", model);
            body.put("input", text);

            java.util.Map<String, Object> options = new java.util.HashMap<>();
            if (dimensions > 0) {
                options.put("dimensions", dimensions);
            }
            if (!options.isEmpty()) {
                body.put("options", options);
            }

            String resp = webClient.post()
                    .uri(apiUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .block();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(resp);
            JsonNode embeddings = root.get("embeddings");
            if (embeddings == null || !embeddings.isArray() || embeddings.isEmpty()) {
                // 완전 실패: 진짜 임베딩이 없는 케이스 → 빈 벡터로 degrade
                return new float[0];
            }

            JsonNode first = embeddings.get(0);
            int actualSize = first.size();

            // 목표 차원 결정: 설정값이 있으면 그걸, 없으면 실제 길이 사용
            int targetDim = resolveTargetDim(actualSize, dimensions, "single");

float[] vec = new float[targetDim];
            // actual이 더 크면 앞에서부터 잘라 쓰고, 더 작으면 나머지는 0으로 패딩
            int copy = Math.min(actualSize, targetDim);
            for (int i = 0; i < copy; i++) {
                vec[i] = (float) first.get(i).asDouble();
            }
            return vec;
        } catch (Exception e) {
            // 단일 호출용 Fallback 로직만 남김
            log.warn("[EMBED] Primary (Ollama) failed: {}. Trying fallback...", e.getMessage());
            if (backupModel != null) {
                try {
                    log.info("[EMBED_FAILOVER] Switching to backup embedding model...");
                    float[] vec = backupModel.embed(text).content().vector();
                    int targetDim = resolveTargetDim(vec.length, dimensions, "fallback-single");
                    if (vec.length == targetDim) return vec;
                    float[] trimmed = new float[targetDim];
                    System.arraycopy(vec, 0, trimmed, 0, Math.min(vec.length, targetDim));
                    return trimmed;
                } catch (Exception ex) {
                    log.error("[EMBED_FAILOVER] Fallback embedding failed: {}", ex.toString());
                }
            } else {
                log.warn("[EMBED_FAILOVER] No backupEmbeddingModel configured.");
            }
            // 실패 시에는 "빈 벡터"로 degrade → 상위 RAG가 web-only로라도 동작하게
            log.warn("[EMBED] call to Ollama failed: {}", e.toString());
            return new float[0];
        }

    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

public double[] normalizeEmbedding(double[] rawEmbedding) {
    if (rawEmbedding == null || rawEmbedding.length == 0) {
        throw new IllegalArgumentException("Empty embedding");
    }
    int actualDim = rawEmbedding.length;
    if (actualDim >= INDEX_DIM) {
        // 상위 1536차원만 슬라이스
        return Arrays.copyOfRange(rawEmbedding, 0, INDEX_DIM);
    } else {
        // 차원 부족 시: 경고 + zero-padding
        log.warn("[EMBED_DIM] actualDim={} < indexDim={} → zero-pad.", actualDim, INDEX_DIM);
        double[] padded = new double[INDEX_DIM];
        System.arraycopy(rawEmbedding, 0, padded, 0, actualDim);
        return padded;
    }
}

}