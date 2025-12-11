package com.example.lms.service.subject;

import com.example.lms.service.disambiguation.DisambiguationResult;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * QueryDisambiguationService 가 생성한 {@link DisambiguationResult} 를
 * 검색/전략 계층에서 사용하기 좋은 형태로 정리한 뷰 객체입니다.
 */
@Data
@Builder
public class SubjectAnalysis {

    /** 사용자의 원본 질의 */
    private String originalQuery;

    /** DR.rewrittenQuery 기준으로 정규화된 쿼리 */
    private String normalizedQuery;

    /** 상위 주제 카테고리 (코딩, 쇼핑, 게임 등) */
    private SubjectCategory category;

    /** 메인 대상 객체 (예: "Galaxy Fold", "강아지 사료") */
    private String targetObject;

    /** 세부 속성 (브랜드, 가격대, 프레임워크 등) */
    private Map<String, String> attributes;

    /** 질의 의도 (GENERAL_SEARCH / SPECIFIC_ITEM / HOW_TO / DEBUGGING / SHOPPING 등) */
    private String queryIntent;

    /** 검색에 사용할 핵심 키워드 리스트 */
    private List<String> focusKeywords;

    /** 원본 DR (필요 시 추가 필드 접근용) */
    private DisambiguationResult baseAnalysis;
}
