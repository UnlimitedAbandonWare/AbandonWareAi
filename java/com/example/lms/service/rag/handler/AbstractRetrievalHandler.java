package com.example.lms.service.rag.handler;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * 책임 연쇄 패턴을 위한 추상 베이스 클래스입니다.
 * <p>
 * 템플릿 메서드 패턴을 사용하여 예외 발생 시에도 체인이 끊기지 않는 안정적인 실행을 보장합니다.
 * </p>
 */
public abstract class AbstractRetrievalHandler implements RetrievalHandler {
    private static final Logger log = LoggerFactory.getLogger(AbstractRetrievalHandler.class);

    private RetrievalHandler next;

    /**
     * 다음 핸들러를 체인에 연결하고, 연결된 핸들러를 반환하여 유연한 체인 구성을 지원합니다.
     * 예: handler1.linkWith(handler2).linkWith(handler3);
     */
    @Override
    public RetrievalHandler linkWith(RetrievalHandler nextHandler) {
        this.next = nextHandler;
        return nextHandler;
    }

    /**
     * 핸들러의 실행 흐름을 제어하는 템플릿 메서드입니다. (수정 불가)
     * <p>
     * 이 메서드는 하위 클래스의 {@code doHandle}을 호출하고, 예외를 안전하게 처리하며,
     * 다음 핸들러를 자동으로 호출하는 역할을 합니다.
     * </p>
     */
    @Override
    public final void handle(Query query, List<Content> accumulator) {
        boolean shouldContinue = true;
        try {
            // 1. 하위 클래스에 실제 로직 실행을 위임
            //    doHandle의 반환값에 따라 체인 지속 여부 결정
            shouldContinue = doHandle(query, accumulator);
        } catch (Throwable t) {
            // 2. 예외 발생 시, 로그를 남기고 체인은 계속 진행되도록 보장 (안정성 핵심)
            log.warn("[RetrievalChain] Handler '{}' failed, but the chain will continue. Error: {}",
                    this.getClass().getSimpleName(), t.getMessage());
            shouldContinue = true; // 예외 시에도 다음 핸들러는 실행
        }

        // 3. 체인을 계속 진행해야 하고, 다음 핸들러가 존재하면 실행
        if (shouldContinue && next != null) {
            next.handle(query, accumulator);
        }
    }

    /**
     * 하위 클래스에서 실제 검색 로직을 구현해야 하는 추상 메서드입니다.
     *
     * @param query       사용자 쿼리
     * @param accumulator 검색 결과를 추가할 리스트
     * @return 체인을 계속 진행하려면 {@code true}, 중단하려면 {@code false}를 반환합니다.
     */
    protected abstract boolean doHandle(Query query, List<Content> accumulator);
}