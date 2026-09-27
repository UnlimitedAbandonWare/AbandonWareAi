package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.adapters.NovaAnalyzeWebSearchRetriever;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RagCompressionPointcutReachabilityTest {
    private static final Query QUERY = Query.from("bounded compression fixture");
    private static final List<Content> DOCUMENTS = List.of(Content.from("first fixture"), Content.from("second fixture"));
    private static final List<Content> COMPRESSED = List.of(Content.from("compressed fixture"));

    @BeforeEach
    @AfterEach
    void clearThreadState() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    static Stream<Class<? extends ContentRetriever>> retrieverTypes() {
        return Stream.of(AnalyzeWebSearchRetriever.class, NovaAnalyzeWebSearchRetriever.class,
                WebSearchRetriever.class, HybridRetriever.class);
    }

    @ParameterizedTest
    @MethodSource("retrieverTypes")
    void productionExpressionMatchesActualRetrieveSignature(Class<? extends ContentRetriever> type) throws Exception {
        String expression = RagCompressionAspect.class.getMethod("aroundRetrieve", ProceedingJoinPoint.class)
                .getAnnotation(Around.class).value();
        AspectJExpressionPointcut pointcut = new AspectJExpressionPointcut();
        pointcut.setExpression(expression);

        assertTrue(pointcut.getClassFilter().matches(type));
        assertTrue(pointcut.matches(type.getMethod("retrieve", Query.class), type));
    }

    @ParameterizedTest
    @MethodSource("retrieverTypes")
    void classProxyAppliesProductionAdvice(Class<? extends ContentRetriever> type) {
        assertProxyCompression(type, true);
    }

    @ParameterizedTest
    @MethodSource("retrieverTypes")
    void interfaceProxyAppliesProductionAdvice(Class<? extends ContentRetriever> type) {
        assertProxyCompression(type, false);
    }

    @Test
    void disabledFeaturePreservesNovaRetrievalResult() {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getRagCompressor().setEnabled(false);
        DynamicContextCompressor compressor = mock(DynamicContextCompressor.class);
        setCompressionMode();

        ContentRetriever proxy = proxy(NovaAnalyzeWebSearchRetriever.class, true, props, compressor, DOCUMENTS);

        assertSame(DOCUMENTS, proxy.retrieve(QUERY));
        verifyNoInteractions(compressor);
        assertFalse(Boolean.TRUE.equals(TraceStore.get("rag.compress.applied")));
    }

    @Test
    void ordinaryModeWithoutOverdrivePreservesNovaRetrievalResult() {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        DynamicContextCompressor compressor = mock(DynamicContextCompressor.class);

        ContentRetriever proxy = proxy(NovaAnalyzeWebSearchRetriever.class, true, props, compressor, DOCUMENTS);

        assertSame(DOCUMENTS, proxy.retrieve(QUERY));
        verifyNoInteractions(compressor);
        assertFalse(Boolean.TRUE.equals(TraceStore.get("rag.compress.applied")));
    }

    @Test
    void singleDocumentPreservesNovaRetrievalResult() {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        DynamicContextCompressor compressor = mock(DynamicContextCompressor.class);
        List<Content> single = List.of(Content.from("single fixture"));
        setCompressionMode();

        ContentRetriever proxy = proxy(NovaAnalyzeWebSearchRetriever.class, true, props, compressor, single);

        assertSame(single, proxy.retrieve(QUERY));
        verifyNoInteractions(compressor);
        assertFalse(Boolean.TRUE.equals(TraceStore.get("rag.compress.applied")));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "true,true,2,true", "false,true,2,false", "true,false,2,false", "true,true,1,false"
    })
    void exactChatRetrieveAllSignatureUsesCompressionWithExistingGuards(
            boolean enabled, boolean mode, int documentCount, boolean compressed) {
        var props = new NovaOrchestrationProperties();
        props.getRagCompressor().setEnabled(enabled);
        var compressor = mock(DynamicContextCompressor.class);
        when(compressor.compress(anyString(), anyList())).thenReturn(COMPRESSED);
        if (mode) setCompressionMode();
        List<Content> documents = DOCUMENTS.subList(0, documentCount);
        List<String> queries = List.of(QUERY.text());
        java.util.Map<String, Object> metadata = java.util.Map.of("fixture", true);
        HybridRetriever target = mock(HybridRetriever.class);
        when(target.retrieveAll(queries, 4, null, metadata)).thenReturn(documents);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new RagCompressionAspect(compressor, null, props, null));
        HybridRetriever proxy = factory.getProxy();

        assertSame(compressed ? COMPRESSED : documents, proxy.retrieveAll(queries, 4, null, metadata));
        verify(target).retrieveAll(queries, 4, null, metadata);
        verify(target, never()).retrieve(org.mockito.ArgumentMatchers.any(Query.class));
        if (compressed) {
            verify(compressor).compress(eq(QUERY.text()), same(documents));
            assertEquals(Boolean.TRUE, TraceStore.get("rag.compress.applied"));
            assertEquals(2, TraceStore.get("rag.compress.beforeDocs"));
            assertEquals(1, TraceStore.get("rag.compress.afterDocs"));
        } else {
            verifyNoInteractions(compressor);
            assertFalse(Boolean.TRUE.equals(TraceStore.get("rag.compress.applied")));
        }
    }

    @Test
    void nestedChildAndAggregateCompressionEnforceFinalCapWithoutRetrimmingRetainedText() {
        var props = new NovaOrchestrationProperties();
        props.getRagCompressor().setMaxContents(2);
        var compressor = spy(new DynamicContextCompressor(props));
        setCompressionMode();
        List<Content> childInput = List.of(Content.from("child first retained text"),
                Content.from("child second retained text"), Content.from("child third excluded text"));
        ContentRetriever child = proxy(WebSearchRetriever.class, true, props, compressor, childInput);
        List<String> queries = List.of(QUERY.text());
        java.util.Map<String, Object> metadata = java.util.Map.of("fixture", true);
        var childOutput = new java.util.concurrent.atomic.AtomicReference<List<Content>>();
        HybridRetriever target = mock(HybridRetriever.class);
        when(target.retrieveAll(queries, 4, null, metadata)).thenAnswer(call -> {
            List<Content> selectedChild = child.retrieve(QUERY);
            childOutput.set(selectedChild);
            var merged = new java.util.ArrayList<>(selectedChild);
            merged.add(Content.from("aggregate appended candidate"));
            return merged;
        });
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new RagCompressionAspect(compressor, null, props, null));
        HybridRetriever aggregate = factory.getProxy();

        List<Content> result = aggregate.retrieveAll(queries, 4, null, metadata);

        assertEquals(2, childOutput.get().size());
        assertEquals(2, result.size(), "merged aggregate must obey the configured final cap");
        verify(compressor, times(2)).compress(eq(QUERY.text()), anyList());
        verify(target).retrieveAll(queries, 4, null, metadata);
        verify(target, never()).retrieve(org.mockito.ArgumentMatchers.any(Query.class));
        assertEquals(3, TraceStore.get("rag.compress.beforeDocs"));
        assertEquals(2, TraceStore.get("rag.compress.afterDocs"));
        for (int i = 0; i < 2; i++) {
            var before = childOutput.get().get(i).textSegment();
            var after = result.get(i).textSegment();
            assertEquals(before.text(), after.text());
            assertEquals(before.metadata().getString("_nova.origHash"),
                    after.metadata().getString("_nova.origHash"));
            assertEquals("true", after.metadata().getString("_nova.compressed"));
        }
    }

    private static void assertProxyCompression(Class<? extends ContentRetriever> type, boolean classProxy) {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        DynamicContextCompressor compressor = mock(DynamicContextCompressor.class);
        when(compressor.compress(anyString(), anyList())).thenReturn(COMPRESSED);
        setCompressionMode();
        ContentRetriever proxy = proxy(type, classProxy, props, compressor, DOCUMENTS);

        assertSame(COMPRESSED, proxy.retrieve(QUERY));
        verify(compressor).compress(anyString(), same(DOCUMENTS));
        assertEquals(Boolean.TRUE, TraceStore.get("rag.compress.applied"));
        assertEquals(2, TraceStore.get("rag.compress.beforeDocs"));
        assertEquals(1, TraceStore.get("rag.compress.afterDocs"));
    }

    private static ContentRetriever proxy(Class<? extends ContentRetriever> type, boolean classProxy,
                                          NovaOrchestrationProperties props, DynamicContextCompressor compressor,
                                          List<Content> documents) {
        // Constructors and all provider methods are bypassed; only this terminal fixture can execute.
        ContentRetriever target = mock(type);
        when(target.retrieve(QUERY)).thenReturn(documents);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(classProxy);
        factory.setInterfaces(ContentRetriever.class);
        factory.addAspect(new RagCompressionAspect(compressor, null, props, null));
        return (ContentRetriever) factory.getProxy();
    }

    private static void setCompressionMode() {
        GuardContext context = new GuardContext();
        GuardContextHolder.set(context);
        context.setUserQuery(QUERY.text());
        context.setCompressionMode(true);
    }
}
