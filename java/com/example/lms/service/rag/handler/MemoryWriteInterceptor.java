// src/main/java/com/example/lms/service/rag/handler/MemoryWriteInterceptor.java
package com.example.lms.service.rag.handler;

import com.example.lms.service.MemoryReinforcementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * 답변을 항상 장기 메모리에 강화 저장합니다.
 * ChatService에서 세션키와 함께 호출합니다.
 */
@Component
@RequiredArgsConstructor
public class MemoryWriteInterceptor {
    private static final Logger log = LoggerFactory.getLogger(MemoryWriteInterceptor.class);

    private final MemoryReinforcementService memorySvc;

    /**
     * @param sessionKey  ChatService에서 사용하는 정규화된 세션 키(예: "chat-123")
     * @param query       사용자 질문
     * @param answer      최종 답변(검증/확장 반영)
     * @param score       신뢰도/가중치(0.0~1.0)
     */
    public void save(String sessionKey, String query, String answer, double score) {
        try {
            if (answer == null || answer.isBlank()) return;
            memorySvc.reinforceWithSnippet(sessionKey, query, answer, "ASSISTANT", Math.max(0.0, Math.min(1.0, score)));
        } catch (Throwable t) {
            log.debug("[MemoryWriteInterceptor] reinforce 실패: {}", t.toString());
        }
    }
}