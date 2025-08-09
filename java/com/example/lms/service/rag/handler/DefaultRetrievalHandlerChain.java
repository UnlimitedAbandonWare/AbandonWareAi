
package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;


/**
 * 검색 핸들러들의 기본 실행 체인(Chain)을 정의하는 구현체입니다.
 * <p>
 * <b>실행 순서:</b> SelfAsk → Analyze → Web → RAG(Vector) 순으로 실행되며,
 * 각 단계에서 수집된 문서 수가 {@code topK}에 도달하면 다음 단계는 생략됩니다.
 * <p>
 * <b>Bean 등록 주의:</b> 기본적으로 {@code @Component}로 자동 등록됩니다.
 * 만약 {@code RetrieverChainConfig} 등 다른 설정 클래스에서 람다(lambda) 형식으로
 * {@code RetrievalHandler} Bean을 직접 생성하는 경우, Bean 충돌이 발생할 수 있습니다.
 * 이 경우, 이 클래스의 {@code @Component}를 제거하거나, 설정 클래스의 {@code @Bean} 정의를 제거하여
 * 하나의 Bean만 등록되도록 관리해야 합니다.
 */

@RequiredArgsConstructor
public class DefaultRetrievalHandlerChain implements RetrievalHandler {

    private final SelfAskWebSearchRetriever selfAsk;
    private final AnalyzeWebSearchRetriever analyze;
    private final WebSearchRetriever web;
    private final LangChainRAGService rag;

    @Value("${pinecone.index.name}")
    private String pineconeIndexName;

    @Value("${rag.search.top-k:5}")
    private int topK;

    /**
     * 정의된 순서에 따라 검색 핸들러들을 순차적으로 실행합니다.
     *
     * @param query       사용자 쿼리
     * @param accumulator 검색 결과를 누적할 리스트 (null이 아니어야 함)
     */
    @Override
    public void handle(Query query, List<Content> accumulator) {
        if (accumulator == null) {
            // 이 메서드는 외부에서 생성된 리스트를 채우는 것을 목적으로 하므로,
            // null 리스트는 처리하지 않음.
            return;
        }

        // 1. Self-Ask 리트리버 실행
        add(accumulator, selfAsk.retrieve(query));

        // 2. 결과가 topK보다 적으면 Analyze 리트리버 실행
        if (accumulator.size() < topK) {
            add(accumulator, analyze.retrieve(query));
        }

        // 3. 결과가 여전히 topK보다 적으면 기본 Web 리트리버 실행
        if (accumulator.size() < topK) {
            add(accumulator, web.retrieve(query));
        }

        // 4. 결과가 여전히 topK보다 적으면 최종적으로 RAG(Vector) 리트리버 실행
        if (accumulator.size() < topK) {
            ContentRetriever vectorRetriever = rag.asContentRetriever(pineconeIndexName);
            add(accumulator, vectorRetriever.retrieve(query));
        }
    }

    /**
     * Null-safe하게 리스트의 내용을 다른 리스트에 추가하는 헬퍼 메서드.
     *
     * @param target  결과를 추가할 대상 리스트
     * @param source  추가할 내용이 담긴 소스 리스트
     */
    private static void add(List<Content> target, List<Content> source) {
        if (source != null && !source.isEmpty()) {
            target.addAll(source);
        }
    }
}