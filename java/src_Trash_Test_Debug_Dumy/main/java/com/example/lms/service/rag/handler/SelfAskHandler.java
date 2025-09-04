package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Self‑Ask 단계 핸들러(구상 클래스).
 *
 * <p>이 핸들러는 생성자 주입으로 {@link SelfAskWebSearchRetriever}를 받아
 * {@link #doHandle(Query, List)}에서 검색을 수행합니다. 실패가 발생해도
 * 체인 진행을 중단하지 않으며, 항상 {@code false}를 반환하여 다음
 * 핸들러로 처리를 넘깁니다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelfAskHandler extends AbstractRetrievalHandler {

    private final SelfAskWebSearchRetriever retriever;

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            // 기본 구현은 retriever.retrieve(/* TODO */)를 호출하여 검색한다.
            acc.addAll(retriever.retrieve(q));
        } catch (Exception e) {
            // 예외가 발생해도 체인을 중단하지 않고 로그만 남긴다.
            log.warn("[SelfAsk] 웹 검색 중 예외 – 이후 단계 계속 진행", e);
        }
        // false를 반환하여 체인을 계속 진행한다.
        return false;
    }
}
