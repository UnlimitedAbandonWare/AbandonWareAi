package com.example.lms.service.rag.overdrive;

import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * AngerOverdriveNarrower
 *
 * <p>
 * 과거 k-schedule(48,32,16,8) 기반의 물량 공세 검색 로직을
 * Cross-Encoder 기반 재랭커로 대체한 경량 버전입니다.
 * </p>
 *
 * <ul>
 * <li>여러 소스에서 모은 후보 문서 리스트(current)를 입력으로 받습니다.</li>
 * <li>CrossEncoderReranker를 사용해 의미적 유사도 점수를 산정합니다.</li>
 * <li>상위 N개(기본 8개)만 남겨 ContextOrchestrator로 반환합니다.</li>
 * <li>예외 발생 시에는 fail-soft 전략으로 기존 리스트를 그대로 돌려줍니다.</li>
 * </ul>
 */
@Component
public class AngerOverdriveNarrower {

    private static final Logger log = LoggerFactory.getLogger(AngerOverdriveNarrower.class);

    private final CrossEncoderReranker reranker;

    public AngerOverdriveNarrower(@Qualifier("crossEncoderReranker") CrossEncoderReranker reranker) {
        this.reranker = reranker;
    }

    /**
     * Cross-Encoder 기반 정밀 축소.
     *
     * @param userQuery 사용자의 원본 질의
     * @param current   현재까지 수집된 후보 컨텍스트 목록
     * @return 재랭킹 후 상위 N개의 컨텍스트 (예외 시 기존 목록)
     */
    public List<Content> narrow(String userQuery, List<Content> current) {
        if (current == null || current.isEmpty()) {
            return Collections.emptyList();
        }
        String query = (userQuery == null) ? "" : userQuery;

        try {
            int topN = Math.min(10, current.size());
            List<Content> reranked = reranker.rerank(query, current, topN);
            if (reranked == null || reranked.isEmpty()) {
                return current;
            }
            int limit = Math.min(8, reranked.size());
            return reranked.subList(0, limit);
        } catch (Exception e) {
            log.warn("[Overdrive] CrossEncoder rerank 실패, 기존 후보 그대로 사용: {}", e.toString());
            return current;
        }
    }
}
