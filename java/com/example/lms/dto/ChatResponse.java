package com.example.lms.dto;


/**
 * 채팅 API 응답 DTO.
 *
 * @param content   LLM 이 생성한 최종 답변
 * @param sessionId 대화 세션 ID (클라이언트는 다음 요청에 그대로 포함)
 * @param modelUsed 실제 사용된 AI 모델 이름
 * @param ragUsed   이번 호출에서 RAG(검색 증강)를 사용했는지 여부
 */
public record ChatResponse(
        String  content,
        Long    sessionId,
        String  modelUsed,
        boolean ragUsed
) { }