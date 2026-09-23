package com.example.lms.service.rag.handler;

import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.service.rag.RelevanceScoringService;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import com.example.lms.service.rag.extract.PageContentScraper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AdaptiveWebStageHandlerTest {

    @Test
    void delegatesWithoutMutatingSharedAdaptiveHandlerChain() {
        RecordingAdaptiveWebSearchHandler adaptive = new RecordingAdaptiveWebSearchHandler();
        RecordingHandler next = new RecordingHandler();
        AdaptiveWebStageHandler stage = new AdaptiveWebStageHandler(adaptive);
        stage.linkWith(next);

        List<Content> accumulator = new ArrayList<>();
        stage.handle(new Query("compare retrieval providers"), accumulator);

        assertEquals(1, adaptive.calls);
        assertEquals(1, next.calls);
        assertEquals(1, accumulator.size());

        adaptive.handle(new Query("direct adaptive call"), new ArrayList<>());

        assertEquals(2, adaptive.calls);
        assertEquals(1, next.calls);
    }

    private static final class RecordingAdaptiveWebSearchHandler extends AdaptiveWebSearchHandler {
        int calls;

        RecordingAdaptiveWebSearchHandler() {
            super(
                    new SearchDecisionService(),
                    List.of(),
                    mock(PageContentScraper.class),
                    mock(RelevanceScoringService.class),
                    mock(DomainProfileLoader.class)
            );
        }

        @Override
        protected boolean doHandle(Query q, List<Content> acc) {
            calls += 1;
            acc.add(Content.from("adaptive-web-evidence"));
            return true;
        }
    }

    private static final class RecordingHandler extends AbstractRetrievalHandler {
        int calls;

        @Override
        protected boolean doHandle(Query query, List<Content> accumulator) {
            calls += 1;
            return true;
        }
    }
}
