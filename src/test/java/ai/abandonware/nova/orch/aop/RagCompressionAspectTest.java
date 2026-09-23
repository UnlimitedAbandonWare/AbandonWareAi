package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.overdrive.AngerOverdriveNarrower;
import com.example.lms.service.rag.overdrive.OverdriveGuard;
import dev.langchain4j.rag.content.Content;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagCompressionAspectTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void planNarrowEnabledRoutesThroughOverdriveNarrowerBeforeCompression() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        RecordingCompressor compressor = new RecordingCompressor(props);
        OverdriveGuard guard = mock(OverdriveGuard.class);
        when(guard.shouldActivate(anyString(), anyList())).thenReturn(true);
        AngerOverdriveNarrower narrower = new AngerOverdriveNarrower(
                (query, candidates, topN) -> new ArrayList<>(candidates));
        RagCompressionAspect aspect = new RagCompressionAspect(
                compressor, new AnchorNarrower(), props, guard, narrower);
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("overdrive.narrow.enabled", true);
        GuardContextHolder.set(ctx);
        List<Content> docs = docs(12);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(docs);
        when(pjp.getArgs()).thenReturn(new Object[]{"low authority contradiction query"});

        Object result = aspect.aroundRetrieve(pjp);

        List<?> out = (List<?>) result;
        assertEquals(8, compressor.lastInputCount);
        assertEquals(8, out.size());
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.narrow.plan.enabled"));
        assertEquals(12, TraceStore.get("overdrive.narrow.input.count"));
        assertEquals(8, TraceStore.get("overdrive.narrow.output.count"));
        String trace = String.valueOf(TraceStore.getAll());
        assertEquals(Boolean.TRUE, TraceStore.get("rag.compress.applied"));
        assertEquals(8, TraceStore.get("rag.compress.afterDocs"));
        assertTrue(String.valueOf(TraceStore.get("rag.compress.anchorHash")).startsWith("hash:"));
        assertEquals(((Number) TraceStore.get("rag.compress.anchorLen")).intValue(), compressor.lastAnchor.length());
        assertFalse(trace.contains("low authority contradiction query"), trace);
    }

    @Test
    void executionPlanPrimaryExtremeZSuppressesAutoOverdriveCompression() throws Throwable {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        RecordingCompressor compressor = new RecordingCompressor(props);
        OverdriveGuard guard = mock(OverdriveGuard.class);
        when(guard.shouldActivate(anyString(), anyList())).thenReturn(true);
        RagCompressionAspect aspect = new RagCompressionAspect(
                compressor, new AnchorNarrower(), props, guard, null);
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("executionPlan.primaryMode", "EXTREMEZ");
        ctx.putPlanOverride("overdrive.enabled", true);
        GuardContextHolder.set(ctx);
        List<Content> docs = docs(4);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(docs);
        when(pjp.getArgs()).thenReturn(new Object[]{"conflicting special modes"});

        Object result = aspect.aroundRetrieve(pjp);

        assertSame(docs, result);
        assertEquals("special_mode_extremez", TraceStore.get("rag.compress.skipReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("specialMode.conflict.overdrive.suppressed"));
    }

    @Test
    void compressionLogsDoNotRenderRawAnchor() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/RagCompressionAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("anchor={})"));
        assertTrue(source.contains("anchorHash={} anchorLength={}"));
        assertTrue(source.contains("TraceStore.put(\"rag.compress.anchorHash\""));
    }

    @Test
    void compressionFailSoftCatchesUseSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/RagCompressionAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"ragCompression.enabledTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"ragCompression.overdriveActivation\", e);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"ragCompression.appliedTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"overdriveNarrow.planEnabledTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"overdriveNarrow.invoke\", e);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"overdriveNarrow.planTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"ragCompression.skipTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"ragCompression.extractQueryText\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"ragCompression.pickAnchor\", ignore);"));
    }

    private static List<Content> docs(int n) {
        List<Content> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Content.from("doc " + i));
        }
        return out;
    }

    private static final class RecordingCompressor extends DynamicContextCompressor {
        int lastInputCount;
        String lastAnchor = "";

        RecordingCompressor(NovaOrchestrationProperties props) {
            super(props);
        }

        @Override
        public List<Content> compress(String anchor, List<Content> docs) {
            lastAnchor = anchor == null ? "" : anchor;
            lastInputCount = docs == null ? 0 : docs.size();
            return docs == null ? List.of() : new ArrayList<>(docs);
        }
    }
}
