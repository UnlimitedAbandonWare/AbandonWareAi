package com.example.lms.service.rag.handler;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import java.util.List;



/**
 * 책임 연쇄 패턴(Chain of Responsibility)을 위한 리트리버 핸들러 함수형 인터페이스입니다.
 * <p>
 * 이 인터페이스는 단일 추상 메서드(handle)를 가지므로, 람다 표현식을 통해 간단하게 구현할 수 있습니다.
 * 각 검색 소스(웹, RAG 등)는 이 핸들러를 구현하여 검색 체인의 일부가 됩니다.
 *
 * @see FunctionalInterface
 */
@FunctionalInterface
public interface RetrievalHandler {

    /**
     * 주어진 쿼리에 대한 검색 로직을 수행하고, 결과를 누적기에 추가합니다.
     * 이 인터페이스의 유일한 추상 메서드이므로, 람다 구현의 대상이 됩니다.
     *
     * @param query       사용자 쿼리
     * @param accumulator 검색 결과를 누적할 리스트
     */
    void handle(Query query, List<Content> accumulator);

    /**
     * 현재 핸들러에 다음 핸들러를 연결하여 새로운 체인 핸들러를 생성합니다.
     * <p>
     * 기본(default) 구현이므로 별도로 구현할 필요 없이 바로 사용할 수 있습니다.
     * 예: {@code Handler combined = handler1.linkWith(handler2);}
     *
     * @param next 다음으로 실행될 핸들러
     * @return 현재 핸들러와 다음 핸들러가 순차적으로 연결된 새로운 핸들러
     */
    default RetrievalHandler linkWith(RetrievalHandler next) {
        return (q, acc) -> {
            // 먼저 현재 핸들러의 로직을 실행
            this.handle(q, acc);
            // 다음 핸들러가 존재하면 이어서 실행
            if (next != null) {
                next.handle(q, acc);
            }
        };
    }
}