// src/main/java/com/example/lms/domain/enums/SourceCredibility.java
package com.example.lms.domain.enums;

/**
 * 컨텍스트 출처 신뢰도 레벨
 *
 * <p>검색/RAG로 수집된 스니펫의 출처 신뢰성을 분류합니다.
 * 모델의 응답 가중치나 후처리 필터링에 활용됩니다.</p>
 */
public enum SourceCredibility {

    /** 정부·제작사·공식 블로그·공식 문서 등 1차 권위 출처 */
    OFFICIAL,

    /** 공식 문서는 아니지만 높은 정확도를 보이는 위키·백과 계열 */
    RELIABLE_WIKI,

    /** 팬 커뮤니티·블로그 등 비공식 창작/추측 */
    FAN_MADE_SPECULATION,

    /** 서로 다른 출처 간 내용이 충돌하거나 상반되는 경우 */
    CONFLICTING,

    /** 신뢰도를 판단할 수 없는 경우 */
    UNKNOWN
}
