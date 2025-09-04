package com.example.lms.service.rag.handler.impl;
import org.springframework.context.annotation.Primary;
import com.example.lms.service.rag.handler.PreconditionCheckHandler;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;
@Primary
@Component
public class PreconditionCheckHandlerImpl extends PreconditionCheckHandler {

    // 상위 클래스의 final handle(..)가 호출하는 훅 메서드
    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        if (q == null) {
            // 더 진행해봤자 의미 없으니 체인 중단(handled=true)
            return true;
        }
        String text = q.text();
        if (text == null || text.isBlank()) {
            // 빈 질의면 이후 단계(web/vector) 스킵
            return true; // handled(=stop)
        }
        // TODO: 필요 시 여기서 기본 메타 세팅, 라우팅 플래그 등 처리
        return false; // 처리 안 함 -> 다음 핸들러로 진행
    }
}