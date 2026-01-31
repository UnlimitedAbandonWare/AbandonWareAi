package com.example.lms.service.subject;

/**
 * 질의를 큰 범주로 분류하기 위한 주제 카테고리.
 * <p>
 * 실제 라우팅(코딩 도메인용 RAG, 쇼핑 도메인용 웹 검색 등)을 선택할 때 사용된다.
 */
public enum SubjectCategory {
    CODING("기술 문서 및 코드 검색"),
    SHOPPING("제품 가격 및 스펙 비교"),
    REAL_ESTATE("부동산 매물 및 지역 정보"),
    GAMING("게임 공략 및 설정"),
    EDUCATION("교육/학원/국비 정보"),
    GENERAL("일반 상식 및 대화");

    private final String description;

    SubjectCategory(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
