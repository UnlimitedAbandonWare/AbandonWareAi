package com.example.lms.dto;

import jakarta.validation.constraints.NotNull;



/**
 * 프론트엔드에서 전송하는 피드백 데이터를 담는 DTO (Data Transfer Object).
 *
 * @param sessionId 피드백이 발생한 채팅 세션의 ID
 * @param message 피드백의 대상이 된 AI 어시스턴트의 답변 전문
 * @param rating "POSITIVE" 또는 "NEGATIVE" 값을 가지는 평가 등급
 * @param corrected (선택 사항) 사용자가 직접 수정한 답변 내용
 */
public record FeedbackDto(
        @NotNull Long sessionId,
        @NotNull String message,
        @NotNull String rating,
        String corrected
) {}