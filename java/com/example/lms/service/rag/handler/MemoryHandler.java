// src/main/java/com/example/lms/service/rag/handler/MemoryHandler.java
package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.guard.MemoryAsEvidenceAdapter;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 세션의 "검증된" 메모리 스니펫을 retrieval 파이프 전단에 주입.
 */
@Slf4j
@RequiredArgsConstructor
public class MemoryHandler extends AbstractRetrievalHandler {

    private final MemoryAsEvidenceAdapter memoryAdapter;
    private final Long sessionId;
    private final int lastN;

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            var mem = memoryAdapter.fromSession(sessionId, lastN);
            for (String s : mem) {
                if (s != null && !s.isBlank()) acc.add(Content.from("[MEM] " + s));
            }
        } catch (Exception e) {
            log.debug("[MemoryHandler] skip: {}", e.toString());
        }
        return true; // 다음 핸들러 계속
    }
}
