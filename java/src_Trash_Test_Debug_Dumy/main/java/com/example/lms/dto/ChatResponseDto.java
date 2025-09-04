// src/main/java/com/example/lms/dto/ChatResponseDto.java
package com.example.lms.dto;

import lombok.Getter;
import java.util.List;

/**
 * 채팅 API 응답 DTO – 프론트엔드 요구 필드 100 % 일치
 */
@Getter
public class ChatResponseDto {

    private final java.util.List<LinkDto> links; // Top fused links
    private final String traceHtml;              // Optional trace HTML (opt-in)

    private final String  content;   // AI가 생성한 답변
    private final Long    sessionId; // 세션 ID
    private final String  modelUsed; // 사용 모델명
    private final boolean ragUsed;   // RAG 사용 여부 (추가)

    public ChatResponseDto(String content,
                           Long   sessionId,
                           String modelUsed,
                           boolean ragUsed) {
        this(content, sessionId, modelUsed, ragUsed, java.util.Collections.emptyList(), null);
    }

    public ChatResponseDto(String content,
                           Long   sessionId,
                           String modelUsed,
                           boolean ragUsed,
                           java.util.List<LinkDto> links,
                           String traceHtml) {
        this.content   = content;
        this.sessionId = sessionId;
        this.modelUsed = modelUsed;
        this.ragUsed   = ragUsed;
        this.links     = links;
        this.traceHtml = traceHtml;
    }
}
