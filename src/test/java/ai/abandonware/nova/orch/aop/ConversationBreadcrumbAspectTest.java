package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationBreadcrumbAspectTest {

    @AfterEach
    void clearTraceState() {
        TraceStore.clear();
        TraceContext.cleanupCurrentThread();
        MDC.clear();
    }

    @Test
    void breadcrumbSurvivesWorkflowTraceResetWithoutReplacingLaterMlaProjection() throws Throwable {
        ConversationBreadcrumbAspect aspect = new ConversationBreadcrumbAspect(new NovaOrchestrationProperties());
        ChatRequestDto request = ChatRequestDto.builder()
                .sessionId(42L)
                .message("redacted test message")
                .build();
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{request});
        when(joinPoint.proceed(any(Object[].class))).thenAnswer(invocation -> {
            TraceStore.clear();
            TraceStore.put("cihRag.breadcrumb.queryRedacted", true);
            TraceStore.put("cihRag.breadcrumb.stage", "downstream_stage");
            TraceStore.put("cihRag.breadcrumb.relevance", 0.75d);
            TraceStore.put("cihRag.breadcrumb.routeDecision", "downstream_route");
            return "ok";
        });

        assertEquals("ok", aspect.aroundContinueChat(joinPoint));

        List<?> breadcrumbs = assertInstanceOf(List.class, TraceStore.get("ml.breadcrumbs.v1"));
        assertEquals(1, breadcrumbs.size());
        Map<?, ?> row = assertInstanceOf(Map.class, breadcrumbs.get(0));
        assertEquals("conversation_sid_applied", row.get("decision"));
        Map<?, ?> data = assertInstanceOf(Map.class, row.get("data"));
        assertEquals(Boolean.TRUE, data.get("queryRedacted"));
        assertEquals("conversation_sid_applied", data.get("routeDecision"));

        assertEquals(Boolean.TRUE, TraceStore.get("cihRag.breadcrumb.queryRedacted"));
        assertEquals("downstream_stage", TraceStore.get("cihRag.breadcrumb.stage"));
        assertEquals(0.75d, TraceStore.get("cihRag.breadcrumb.relevance"));
        assertEquals("downstream_route", TraceStore.get("cihRag.breadcrumb.routeDecision"));
        verify(joinPoint, times(1)).proceed(any(Object[].class));
    }
}
