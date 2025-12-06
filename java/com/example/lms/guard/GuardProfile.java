package com.example.lms.guard;

/**
 * Guardrail profile that controls how strict the safety / evidence gates behave.
 *
 * PROFILE_MEMORY / PROFILE_FREE는 RAG 게이트 및 메모리 강화를 위한 신규 프로파일이며,
 * STRICT / NORMAL / SUBCULTURE 값은 기존 프롬프트/도메인 튜닝용 레거시 값입니다.
 */
public enum GuardProfile {

    // 신규 RAG / 메모리 프로파일
    PROFILE_MEMORY,  // 시선1: 기억저장 잼미니 (증거 있으면 적극적으로 답변 + 메모리 저장)
    PROFILE_FREE,    // 시선2: 무메모리 Pro 잼미니 (가드 최소화, 메모리 비활성)

    // 레거시 프로파일 (기존 ChatService / PromptContext 등에서 사용)
    STRICT,          // 공부/민감 주제 - 가장 보수적인 가드
    NORMAL,          // 일반 대화
    SUBCULTURE       // 게임/서브컬처 - 커뮤니티 지식 우대
}
