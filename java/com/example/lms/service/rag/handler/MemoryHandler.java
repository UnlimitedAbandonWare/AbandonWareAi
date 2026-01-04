package com.example.lms.service.rag.handler;

import com.example.lms.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


/**
 * MemoryHandler - 읽기 전담(minimal):
 *  - loadForSession(sessionId): 최근 N턴을 bullet text로 묶어 프롬프트 주입용 문자열 반환(없으면 null)
 *  - 파일 분리 유지, 다른 체인 단계와의 결합 제거(evidence 주입/핸들러 링크 제거)
 */
@Component
@RequiredArgsConstructor
public class MemoryHandler {
    private static final Logger log = LoggerFactory.getLogger(MemoryHandler.class);

    private final ChatHistoryService historyService;

    @Value("${memory.read.max-turns:8}")
    private int maxTurns;
    /** 프롬프트 주입용: 세션의 최근 N턴을 bullet text로 묶어 반환(없으면 null) */
    public String loadForSession(Long sessionId) {
        try {
            if (sessionId == null) return null;
            List<String> hist = historyService.getFormattedRecentHistory(sessionId, Math.max(1, maxTurns));
            if (hist == null || hist.isEmpty()) return null;
            return String.join("\n", hist);
        } catch (Exception e) {
            log.debug("[MemoryHandler] loadForSession 실패: {}", e.toString());
            return null;
        }
    }

}