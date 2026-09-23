package com.example.lms.service.rag.handler;

import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.location.LocationService;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DefaultRetrievalHandlerChainTest {

    @Test
    void abstractRetrievalHandlerHandleCanBeAdvisedByCglibProxies() throws Exception {
        int modifiers = AbstractRetrievalHandler.class
                .getMethod("handle", Query.class, List.class)
                .getModifiers();

        assertFalse(Modifier.isFinal(modifiers),
                "handle(..) must remain non-final so active retrieval diagnostics aspects can advise handler beans");
    }

    @Test
    void searchOffSkipsLegacyWebRetriever() {
        WebSearchRetriever web = mock(WebSearchRetriever.class);
        DefaultRetrievalHandlerChain chain = new DefaultRetrievalHandlerChain(
                mock(MemoryHandler.class),
                mock(SelfAskWebSearchRetriever.class),
                mock(AnalyzeWebSearchRetriever.class),
                mock(AdaptiveWebSearchHandler.class),
                web,
                mock(LangChainRAGService.class),
                mock(EvidenceRepairHandler.class),
                mock(QueryComplexityGate.class),
                mock(LocationService.class));

        Query query = QueryUtils.buildQuery("hello", Map.of(
                "useWebSearch", "false",
                "searchMode", "OFF",
                "useRag", "false"));

        chain.handle(query, new ArrayList<Content>());

        verify(web, never()).retrieve(any());
    }

    @Test
    void abstractHandlerTraceUsesQueryHashWithoutRawQuery() {
        TraceStore.clear();
        String raw = "private chain query";
        AbstractRetrievalHandler handler = new AbstractRetrievalHandler() {
            @Override
            protected boolean doHandle(Query query, java.util.List<Content> accumulator) {
                return false;
            }
        };

        handler.handle(new Query(raw), new ArrayList<>());

        Object eventsObj = TraceStore.get("retrieval.chain.events");
        List<?> events = assertInstanceOf(List.class, eventsObj);
        assertFalse(events.isEmpty());
        Map<?, ?> first = assertInstanceOf(Map.class, events.get(0));
        assertTrue(String.valueOf(first.get("queryHash")).startsWith("hash:"));
        assertEquals(raw.length(), first.get("queryLength"));
        assertEquals(3, first.get("queryTokenEstimate"));
        assertFalse(first.containsKey("q"));
        assertFalse(String.valueOf(eventsObj).contains(raw));
        TraceStore.clear();
    }

    @Test
    void abstractHandlerTraceHashesErrorMessageWithoutRawLeak() {
        TraceStore.clear();
        String rawError = "private retrieval failure fake-token-retrieval-secret";
        AbstractRetrievalHandler handler = new AbstractRetrievalHandler() {
            @Override
            protected boolean doHandle(Query query, java.util.List<Content> accumulator) {
                throw new IllegalStateException(rawError);
            }
        };

        handler.handle(new Query("error handling query"), new ArrayList<>());

        Object eventsObj = TraceStore.get("retrieval.chain.events");
        List<?> events = assertInstanceOf(List.class, eventsObj);
        assertFalse(events.isEmpty());
        Map<?, ?> errorEvent = events.stream()
                .map(event -> assertInstanceOf(Map.class, event))
                .filter(event -> event.containsKey("errMsgHash"))
                .findFirst()
                .orElseThrow();
        assertTrue(String.valueOf(errorEvent.get("errMsgHash")).startsWith("hash:"));
        assertEquals(rawError.length(), errorEvent.get("errMsgLength"));
        assertFalse(errorEvent.containsKey("errMsg"));
        assertFalse(String.valueOf(eventsObj).contains(rawError));
        TraceStore.clear();
    }

    @Test
    void retrievalStageClassifiesCancellationSeparatelyFromSilentFailure() {
        TraceStore.clear();
        DefaultRetrievalHandlerChain chain = new DefaultRetrievalHandlerChain(
                mock(MemoryHandler.class),
                mock(SelfAskWebSearchRetriever.class),
                mock(AnalyzeWebSearchRetriever.class),
                mock(AdaptiveWebSearchHandler.class),
                mock(WebSearchRetriever.class),
                mock(LangChainRAGService.class),
                mock(EvidenceRepairHandler.class),
                mock(QueryComplexityGate.class),
                mock(LocationService.class));

        ReflectionTestUtils.invokeMethod(
                chain,
                "recordRetrievalStage",
                "web",
                true,
                0,
                new java.util.concurrent.CancellationException("cancelled ownerToken abc123"),
                true);

        assertEquals("cancelled", TraceStore.get("retrieval.stage.web.failureClass"));
        assertEquals("CancellationException", TraceStore.get("retrieval.stage.web.exceptionType"));
        assertFalse(String.valueOf(TraceStore.context()).contains("ownerToken abc123"));

        TraceStore.clear();
    }

    @Test
    void defaultRetrievalHandlerChainFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DefaultRetrievalHandlerChain.java"));

        assertDefaultChainStage(source, "session.metadata");
        assertDefaultChainStage(source, "memory");
        assertDefaultChainStage(source, "selfask.gate");
        assertDefaultChainStage(source, "selfask.retrieve");
        assertDefaultChainStage(source, "analyze.gate");
        assertDefaultChainStage(source, "analyze.retrieve");
        assertDefaultChainStage(source, "location.intent");
        assertDefaultChainStage(source, "web.adaptive");
        assertDefaultChainStage(source, "web.skipReason");
        assertDefaultChainStage(source, "web.retrieve");
        assertDefaultChainStage(source, "vector.retrieve");
        assertDefaultChainStage(source, "repair.retrieve");
        assertDefaultChainStage(source, "stage.trace");
        assertDefaultChainStage(source, "stage.monitor");
        assertDefaultChainStage(source, "stage.debugEvent");
        assertDefaultChainStage(source, "metadata.bool");
        assertDefaultChainStage(source, "metadata.string");
        assertTrue(source.contains("log.debug(\"[DefaultRetrievalHandlerChain] fail-soft stage={} err={}"));
        assertFalse(source.contains("catch (Exception ignore) {"));
    }

    private static void assertDefaultChainStage(String source, String stage) {
        assertTrue(source.contains("traceSuppressed(\"" + stage + "\""),
                "DefaultRetrievalHandlerChain fail-soft path needs stage breadcrumb: " + stage);
    }
}
