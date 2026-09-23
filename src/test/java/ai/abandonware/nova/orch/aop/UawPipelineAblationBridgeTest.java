package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UawPipelineAblationBridgeTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
    }

    @Test
    void uawPipelineAblationBridgeDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/UawPipelineAblationBridge.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "UAW pipeline ablation bridge needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void ablationBridgeIntegerParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/UawPipelineAblationBridge.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String parserCall = "return Integer.parseInt(String.valueOf(v).trim());";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "UAW ablation integer parser should be locatable");
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                "UAW ablation numeric parser fallback must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "UAW ablation numeric parser fallback should catch only NumberFormatException");
        assertTrue(window.contains("traceSuppressed(\"asInt.parse\");"),
                "UAW ablation numeric parser fallback should leave a fixed-stage breadcrumb");
    }

    @Test
    @SuppressWarnings("unchecked")
    void domainMisroutePenaltyNoteUsesHashOnlyHostAndQuery() throws Throwable {
        String rawHost = "internal.private.example";
        String rawQuery = "Gemini official private-domain-misroute-query";
        TraceStore.put("uaw.ablation.bridge", true);
        TraceStore.put("web.failsoft.domainMisroute.reported", true);
        TraceStore.put("web.failsoft.domainMisroute.host", rawHost);
        TraceStore.put("web.failsoft.domainMisroute.queryHash", "hash:query-secret");
        TraceStore.put("web.failsoft.domainMisroute.queryLength", rawQuery.length());

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        new UawPipelineAblationBridge(new MockEnvironment()).aroundContinueChat(pjp);

        List<Map<String, Object>> penalties = (List<Map<String, Object>>) TraceStore.get("ablation.penalties");
        String payload = String.valueOf(penalties);
        assertTrue(payload.contains("hostHash="), payload);
        assertTrue(payload.contains("queryHash="), payload);
        assertTrue(payload.contains("queryLength=" + rawQuery.length()), payload);
        assertFalse(payload.contains(rawHost), payload);
        assertFalse(payload.contains(rawQuery), payload);
        assertFalse(payload.contains("private-domain-misroute-query"), payload);
        assertFalse(payload.contains(" query="), payload);
    }

    @Test
    @SuppressWarnings("unchecked")
    void starvationFallbackPenaltyUsesCanonicalAlias() throws Throwable {
        TraceStore.put("uaw.ablation.bridge", true);
        TraceStore.put("starvationFallback.used", true);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        new UawPipelineAblationBridge(new MockEnvironment()).aroundContinueChat(pjp);

        List<Map<String, Object>> penalties = (List<Map<String, Object>>) TraceStore.get("ablation.penalties");
        String payload = String.valueOf(penalties);
        assertTrue(payload.contains("step=uaw.web"), payload);
        assertTrue(payload.contains("guard=starvationFallback"), payload);
        assertTrue(payload.contains("web-failsoft starvation fallback used"), payload);
    }

    @Test
    void nonFiniteTraceSignalsDoNotCreateAblationPenalties() throws Throwable {
        TraceStore.put("uaw.ablation.bridge", true);
        TraceStore.put("nightmare.finalRescue.used", Double.POSITIVE_INFINITY);
        TraceStore.put("aux.blocked.count", Double.POSITIVE_INFINITY);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        new UawPipelineAblationBridge(new MockEnvironment()).aroundContinueChat(pjp);

        String payload = String.valueOf(TraceStore.get("ablation.penalties"));
        assertFalse(payload.contains("step=nightmare"), payload);
        assertFalse(payload.contains("guard=silent_failure"), payload);
        assertFalse(payload.contains("step=aux"), payload);
        assertFalse(payload.contains("guard=blocked"), payload);
    }
}
