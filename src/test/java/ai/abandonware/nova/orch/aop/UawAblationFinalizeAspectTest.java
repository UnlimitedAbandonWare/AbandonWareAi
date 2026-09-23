package ai.abandonware.nova.orch.aop;

import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UawAblationFinalizeAspectTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void blackboxRefreshFailureLeavesRedactedFinalizeBreadcrumb() throws Throwable {
        TraceStore.put("uaw.ablation.bridge", true);
        RagFailureBlackboxService blackbox = mock(RagFailureBlackboxService.class);
        when(blackbox.refresh("UawAblationFinalizeAspect"))
                .thenThrow(new IllegalStateException("raw ownerToken=secret api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        ObjectProvider<RagFailureBlackboxService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(blackbox);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        Object result = new UawAblationFinalizeAspect(new MockEnvironment(), provider)
                .aroundContinueChat(pjp);

        assertEquals("ok", result);
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.ablation.finalize.failed"));
        assertEquals("IllegalStateException", TraceStore.get("uaw.ablation.finalize.errorType"));
        assertTrue(String.valueOf(TraceStore.get("uaw.ablation.finalize.errorHash")).startsWith("hash:"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=secret"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonFiniteBridgeTraceDoesNotFinalizeAblation() throws Throwable {
        TraceStore.put("uaw.ablation.bridge", Double.POSITIVE_INFINITY);
        ObjectProvider<RagFailureBlackboxService> provider = mock(ObjectProvider.class);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        Object result = new UawAblationFinalizeAspect(new MockEnvironment(), provider)
                .aroundContinueChat(pjp);

        assertEquals("ok", result);
        assertFalse(Boolean.TRUE.equals(TraceStore.get("uaw.ablation.finalized")));
        verifyNoInteractions(provider);
    }

    @Test
    void uawAblationFinalizeAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/UawAblationFinalizeAspect.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "UAW ablation finalize paths need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }
}
