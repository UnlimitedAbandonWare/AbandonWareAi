package com.example.lms.domain.enums;

/** 컨텍스트 출처 신뢰도 레벨 */
public enum SourceCredibility {
    OFFICIAL,               // 공식 출처 (가중치 높음)
    RELIABLE_WIKI,          // 위키/백과 (중간)
    FAN_MADE_SPECULATION,   // 팬 창작/추측
    CONFLICTING,            // 상충 정보 혼재
    UNKNOWN                 // 판별 불가
}
