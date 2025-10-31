package com.example.lms.dto;


/**
 * SSE 이벤트 페이로드.
 * type: "status" | "trace" | "token" | "final" | "error" | "thought"
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

        /**
         * Create a new SSE event indicating an intermediate thought from the AI agent.
         *
         * @param msg the message to display in the thought process panel
         * @return a ChatStreamEvent with type "thought" and the given data
         */
        public static ChatStreamEvent thought(String msg) {
                return new ChatStreamEvent("thought", msg, null, null, null, null);
        }

        /**
         * Create a new SSE event carrying a structured understanding summary.  The
         * summary should be serialized to JSON and provided in the {@code data}
         * field.  Clients can parse this JSON to render TL;DR, key points and
         * action items.  The event name will be "understanding".
         *
         * @param json the serialized {@link com.example.lms.dto.answer.AnswerUnderstanding}
         * @return a ChatStreamEvent with type "understanding"
         */
        public static ChatStreamEvent understanding(String json) {
                return new ChatStreamEvent("understanding", json, null, null, null, null);
        }
}