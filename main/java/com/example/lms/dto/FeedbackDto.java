package com.example.lms.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 프론트엔드에서 전송하는 피드백 데이터를 담는 DTO (Data Transfer Object).
 *
 * @param sessionId 피드백이 발생한 채팅 세션의 ID
 * @param messageId 피드백 대상 assistant 메시지의 영속 ID (구형 클라이언트는 생략 가능)
 * @param message 피드백의 대상이 된 AI 어시스턴트의 답변 전문
 * @param rating "POSITIVE" 또는 "NEGATIVE" 값을 가지는 평가 등급
 * @param corrected (선택 사항) 사용자가 직접 수정한 답변 내용
 */
public record FeedbackDto(
        @NotNull Long sessionId,
        @Positive Long messageId,
        @NotNull @Size(max = 4000) String message,
        @NotNull @Pattern(regexp = "(?i)^(POSITIVE|NEGATIVE)$") String rating,
        @Size(max = 4000) String corrected
) {
    /** Preserve the pre-messageId Java and JSON caller shape. */
    public FeedbackDto(Long sessionId, String message, String rating, String corrected) {
        this(sessionId, null, message, rating, corrected);
    }
}
