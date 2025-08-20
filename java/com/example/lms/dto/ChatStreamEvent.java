package com.example.lms.dto;

/**
 * SSE 이벤트 페이로드.
 * type: "status" | "trace" | "token" | "final" | "error"
 * data: 상태/토큰 문자열
 * html: trace 전용 HTML 조각
 */
public record ChatStreamEvent(
        String type,
        String data,
        String html,
        String modelUsed,
        Boolean ragUsed,
        Long sessionId
) {
        public static ChatStreamEvent status(String msg) {
                return new ChatStreamEvent("status", msg, null, null, null, null);
        }
        public static ChatStreamEvent trace(String html) {
                return new ChatStreamEvent("trace", null, html, null, null, null);
        }
        public static ChatStreamEvent token(String chunk) {
                return new ChatStreamEvent("token", chunk, null, null, null, null);
        }
        public static ChatStreamEvent done(String modelUsed, boolean ragUsed, Long sessionId) {
                return new ChatStreamEvent("final", null, null, modelUsed, ragUsed, sessionId);
        }
        public static ChatStreamEvent error(String msg) {
                return new ChatStreamEvent("error", msg, null, null, null, null);
        }
}
