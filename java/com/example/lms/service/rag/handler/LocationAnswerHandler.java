package com.example.lms.service.rag.handler;

import com.example.lms.location.LocationService;
import com.example.lms.location.intent.LocationIntent;
import com.example.lms.security.UserContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;




@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "abandonware.chain.enabled", havingValue = "true", matchIfMissing = true)
public class LocationAnswerHandler implements RetrievalHandler {

    private final LocationService locationService;

    @Override
    public void handle(Query query, List<Content> accumulator) {
        // 위치 기반 RAG 주입 기능 비활성화
        return;
    }

}