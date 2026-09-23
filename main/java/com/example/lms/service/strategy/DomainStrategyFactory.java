package com.example.lms.service.strategy;

import com.example.lms.service.subject.SubjectAnalysis;
import com.example.lms.service.subject.SubjectCategory;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Service;

/**
 * SubjectAnalysis + 도메인 코드를 기반으로
 * 웹 검색 / 벡터 RAG / 시스템 프롬프트 프로필 등을 결정하는 전략 팩토리.
 * <p>
 * 이 팩토리는 상위 오케스트레이터(ChatService 등)가
 * useWeb/useRag/systemPromptProfile 을 일관되게 설정할 수 있도록 돕는다.
 */
@Service
public class DomainStrategyFactory {

    @Data
    @Builder
    public static class SearchStrategy {
        /** 예: "CODING", "SHOPPING", "GENSHIN", "GENERAL" */
        private String searchProfile;

        /** 벡터 스토어(RAG)를 사용할지 여부 */
        private boolean useVectorStore;

        /** 외부 웹 검색(Brave/Naver/Google 등)을 사용할지 여부 */
        private boolean useWebSearch;

        /** 도메인 특화 시스템 프롬프트 프로필 */
        private String systemPromptProfile;
    }

    /**
     * 주제 분석 정보와 도메인 코드를 기반으로 검색 전략을 생성한다.
     *
     * @param analysis   주제 분석 결과
     * @param domainCode UniversalDomainDetector 가 판별한 도메인 코드 (예: GENSHIN, TECH_DEVICE 등)
     * @return 검색 전략
     */
    public SearchStrategy createStrategy(SubjectAnalysis analysis, String domainCode) {
        SubjectCategory category = analysis != null ? analysis.getCategory() : null;
        if (category == null) {
            category = SubjectCategory.GENERAL;
        }

        switch (category) {
            case CODING:
                return SearchStrategy.builder()
                        .searchProfile("CODING")
                        .useVectorStore(true)
                        .useWebSearch(true)
                        .systemPromptProfile("DEV_ASSISTANT")
                        .build();

            case SHOPPING:
                return SearchStrategy.builder()
                        .searchProfile("SHOPPING")
                        // 쇼핑 쿼리는 실시간 가격/재고가 중요하므로 웹 검색 우선
                        .useVectorStore(false)
                        .useWebSearch(true)
                        .systemPromptProfile("SHOPPING_ASSISTANT")
                        .build();

            case REAL_ESTATE:
                return SearchStrategy.builder()
                        .searchProfile("REAL_ESTATE")
                        .useVectorStore(false)
                        .useWebSearch(true)
                        .systemPromptProfile("LIFESTYLE_ADVISOR")
                        .build();

            case GAMING:
                if ("GENSHIN".equalsIgnoreCase(domainCode)) {
                    return SearchStrategy.builder()
                            .searchProfile("GENSHIN")
                            .useVectorStore(true)
                            .useWebSearch(true)
                            .systemPromptProfile("GENSHIN_EXPERT")
                            .build();
                }
                return SearchStrategy.builder()
                        .searchProfile("GAMING")
                        .useVectorStore(true)
                        .useWebSearch(true)
                        .systemPromptProfile("GAMING_HELPER")
                        .build();

            case EDUCATION:
                return SearchStrategy.builder()
                        .searchProfile("EDUCATION")
                        .useVectorStore(true)
                        .useWebSearch(true)
                        .systemPromptProfile("EDU_ADVISOR")
                        .build();

            case GENERAL:
            default:
                return SearchStrategy.builder()
                        .searchProfile("GENERAL")
                        .useVectorStore(true)
                        .useWebSearch(true)
                        .systemPromptProfile("GENERIC_ASSISTANT")
                        .build();
        }
    }
}
