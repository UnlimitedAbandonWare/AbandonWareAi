package com.example.lms.service.rag.handler.impl;

import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 기본 동작이 없는 Self‑Ask 웹 검색기 구현.
 *
 * <p>스프링 컨텍스트에서 {@link SelfAskWebSearchRetriever} 빈이 존재하지 않을 때
 * 대체로 사용됩니다. 모든 호출에 대해 빈 목록을 반환합니다.</p>
 */
@Component
public class NoopSelfAskWebSearchRetriever implements SelfAskWebSearchRetriever {
    @Override
    public List<Content> askWeb(String question, int topK, Map<String, Object> meta) {
        return Collections.emptyList();
    }
}