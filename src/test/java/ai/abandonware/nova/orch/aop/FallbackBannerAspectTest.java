package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.service.ChatResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FallbackBannerAspectTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void degradedFallbackKeepsSameChatResultAndPublishesAnswerModeMetadata() throws Throwable {
        FallbackBannerAspect aspect = new FallbackBannerAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String evidenceOnly = "private evidence-only draft";
        ChatResult original = ChatResult.of(evidenceOnly, "fallback:evidence:test-model", true);
        when(pjp.getArgs()).thenReturn(new Object[0]);
        when(pjp.proceed()).thenReturn(original);

        ChatResult result = (ChatResult) aspect.aroundContinueChat(pjp);

        assertSame(original, result);
        assertEquals(evidenceOnly, result.content());
        assertEquals("FALLBACK_EVIDENCE", TraceStore.get("answer.mode"));
        assertFalse(TraceStore.getAll().containsKey("fallbackBanner.bannerPrepended"));
        String publicTrace = TraceStore.getAll().toString();
        assertFalse(publicTrace.contains(evidenceOnly));
        assertFalse(publicTrace.contains("test-model"));
    }

    @Test
    void failSoftFallbacksLeaveRedactedBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/FallbackBannerAspect.java"));

        assertTrue(source.contains("traceSuppressed(\"answer.mode.trace\", ignoreMode);"));
        assertTrue(source.contains("traceSuppressed(\"aux.canServe\", e);"));
        assertTrue(source.contains("traceSuppressed(\"aux.recovery\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"promptBuilder.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"extractRequest.args\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"config.int\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"config.get\", ignore);"));
        assertTrue(source.contains("[nova][fallback-banner] suppressed stage={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
    }

    @Test
    void agentDebugEvidenceSkipsAuxRecoveryAndBanner() throws Throwable {
        FallbackBannerAspect aspect = new FallbackBannerAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String content = "현재 디버그 heartbeat 기준 상태입니다.\n- 브라우저: OK";
        when(pjp.getArgs()).thenReturn(new Object[0]);
        when(pjp.proceed()).thenReturn(ChatResult.of(content, "agent-debug:fallback:evidence", false));

        ChatResult result = (ChatResult) aspect.aroundContinueChat(pjp);

        assertEquals(content, result.content());
        assertEquals("agent-debug:fallback:evidence", result.modelUsed());
        assertEquals(Boolean.TRUE, TraceStore.get("chat.agentDebugEvidence.bannerSkipped"));
    }

    @Test
    void legacyAuxCandidateIsNotInvokedAfterCanonicalFinalization() throws Throwable {
        MockEnvironment env = new MockEnvironment()
                .withProperty("nova.orch.evidence-aux.preferred-model", "aux-good")
                .withProperty("nova.orch.evidence-aux.max-attempts", "1");
        DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class);
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(java.util.List<dev.langchain4j.data.message.ChatMessage> messages) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("Temporarily unavailable. Question was shown as `??`."))
                        .build();
            }
        };
        PromptBuilder promptBuilder = (contexts, question) -> question;
        FallbackBannerAspect aspect = new FallbackBannerAspect(env, null, factory, promptBuilder);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        ChatRequestDto req = new ChatRequestDto();
        req.setMessage("local status check");
        String evidenceOnly = "local evidence-only answer";

        when(factory.canServe("aux-good")).thenReturn(true);
        when(factory.lcWithTimeout(any(), any(), any(), any(), any(Integer.class))).thenReturn(model);
        when(pjp.getArgs()).thenReturn(new Object[] { req });
        ChatResult original = ChatResult.of(evidenceOnly, "fallback:evidence:test-model", true);
        when(pjp.proceed()).thenReturn(original);

        ChatResult result = (ChatResult) aspect.aroundContinueChat(pjp);

        assertSame(original, result);
        assertEquals(evidenceOnly, result.content());
        assertFalse(result.content().contains("Question was shown as `??`"));
        assertFalse(Boolean.TRUE.equals(TraceStore.get("fallbackBanner.auxRecovery.rejected")));
        verify(factory, never()).canServe(any());
        verify(factory, never()).lcWithTimeout(any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void postReturnAuxiliaryRecoveryDoesNotReplaceCanonicalFinalAnswer() throws Throwable {
        MockEnvironment env = new MockEnvironment()
                .withProperty("nova.orch.evidence-aux.preferred-model", "aux-good")
                .withProperty("nova.orch.evidence-aux.max-attempts", "1");
        DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class);
        PromptBuilder promptBuilder = (contexts, question) -> question;
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(java.util.List<dev.langchain4j.data.message.ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(AiMessage.from("unverified recovery")).build();
            }
        };
        FallbackBannerAspect aspect = new FallbackBannerAspect(env, null, factory, promptBuilder);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        ChatRequestDto req = new ChatRequestDto();
        req.setMessage("question");
        when(pjp.getArgs()).thenReturn(new Object[]{req});
        ChatResult original = ChatResult.of(
                "evidence-only answer", "fallback:evidence:test-model", true);
        when(pjp.proceed()).thenReturn(original);

        ChatResult result = (ChatResult) aspect.aroundContinueChat(pjp);

        assertSame(original, result);
        assertEquals("evidence-only answer", result.content());
        assertFalse(result.content().contains("unverified recovery"));
        verify(factory, never()).canServe(any());
        verify(factory, never()).lcWithTimeout(any(), any(), any(), any(), any(Integer.class));
    }
}
