package com.example.lms.service.rag;

import com.example.lms.service.onnx.OnnxCrossEncoderReranker;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * HybridReranker combines a lightweight lexical reranker with an optional
 * ONNX cross-encoder reranker. If the smart reranker is available, it is
 * used as a second-stage reranker on top of the lexical scores. When it is
 * not available or fails, the lexical reranker is used as a safe fallback.
 *
 * 기존 SimpleReranker 를 제거하지 않고 옆에 두고, 필요할 경우 언제든지
 * HybridReranker 를 비활성화할 수 있도록 설계되어 있습니다.
 */
@Service
@Primary
public class HybridReranker {

    private static final Logger log = LoggerFactory.getLogger(HybridReranker.class);

    private final LegacyLexicalReranker lexicalFallback;
    private final Optional<OnnxCrossEncoderReranker> smartReranker;

    @Autowired
    public HybridReranker(LegacyLexicalReranker lexical,
                          @Autowired(required = false) OnnxCrossEncoderReranker onnx) {
        this.lexicalFallback = lexical;
        this.smartReranker = Optional.ofNullable(onnx);
    }

    /**
     * Rerank the given candidates using a hybrid strategy:
     *  - First, use the lexical reranker to cheaply trim the list.
     *  - Second, if an ONNX reranker is available, apply it on the trimmed list.
     *  - If anything goes wrong, fall back to the lexical result.
     */
    public List<Content> rerank(String query, List<Content> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }

        // 1차: Lexical 기반 사전 필터링
        List<Content> preFiltered = lexicalFallback.rerank(query, candidates, Math.min(limit * 3, candidates.size()));

        // 2차: ONNX Cross-Encoder 가 있으면 더 정밀한 재랭크 수행
        if (smartReranker.isPresent()) {
            try {
                return smartReranker.get().rerank(query, preFiltered, limit);
            } catch (Exception e) {
                log.warn("Smart rerank failed, falling back to lexical reranker: {}", e.getMessage());
            }
        }

        // 3차: ONNX 미사용 또는 실패 시 lexical 결과 사용
        if (preFiltered.size() > limit) {
            return preFiltered.subList(0, limit);
        }
        return preFiltered;
    }
}
