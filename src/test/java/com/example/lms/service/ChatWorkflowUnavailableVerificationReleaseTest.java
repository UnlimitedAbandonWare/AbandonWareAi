package com.example.lms.service;

import com.example.lms.dto.RagEvidenceMetadata;
import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatWorkflowUnavailableVerificationReleaseTest {
    private static final String QUERY = "가상대학병원 홍가람 교수 소개";
    private static final String LINE = "가상대학병원 홍가람 교수";
    private static final String URL = "https://example.org/fictional-doctor";
    private static final String DRAFT = "공개하면 안 되는 합성 초안과 확인되지 않은 추가 경력";

    @AfterEach void clear() { Thread.interrupted(); TraceStore.clear(); }

    @Test void unavailableJudgeReleasesOnlyOneSameSourceExtract() {
        var result = release(base("unknown"), true, raw(LINE), List.of(citation()), true, false, false);
        assertEquals("UNVERIFIED", result.releaseStatus());
        assertEquals("verification_unavailable_excerpt", result.reasonCode());
        assertTrue(result.releaseAllowed());
        assertFalse(result.knowledgeWriteAllowed());
        assertTrue(result.content().contains("> " + LINE));
        assertTrue(result.content().contains("[W1](" + URL + ")"));
        assertFalse(result.content().contains(DRAFT));
    }

    @Test void unavailableWithoutSafeExtractReturnsOnlyGenericGuidance() {
        var result = release(base("unknown"), true, List.of(), List.of(), false, false, false);
        assertEquals("UNVERIFIED", result.releaseStatus());
        assertEquals("verification_unavailable_guidance", result.reasonCode());
        assertTrue(result.releaseAllowed());
        assertFalse(result.knowledgeWriteAllowed());
        assertFalse(result.content().isBlank());
        assertFalse(result.content().contains(DRAFT));
        assertFalse(result.content().contains("홍가람"));
        assertFalse(result.content().contains("[W1]"));
    }

    @Test void plainUnknownAndKnownDenialsCannotBorrowInfrastructurePermission() {
        var unknown = base("unknown");
        assertEquals(unknown, release(unknown, false, raw(LINE), List.of(citation()), true, false, false));
        for (String status : List.of("rejected", "insufficient")) {
            var denied = base(status);
            assertEquals(denied, release(denied, true, raw(LINE), List.of(citation()), true, false, false));
            assertFalse(denied.releaseAllowed());
        }
    }

    @Test void scopedEvidenceAndStrictNoExtractStillHold() {
        var denied = base("unknown");
        assertEquals(denied, release(denied, true, raw(LINE), List.of(citation()), true, false, true));
        assertEquals(denied, release(denied, true, List.of(), List.of(), false, true, false));
        assertEquals(denied, release(denied, true, raw(LINE), List.of(citation()), false, true, false));
    }

    @Test void priorApprovedFallbackIsNeverOverwritten() {
        var fallback = new ChatWorkflow.FinalVerificationReleaseDecision(
                "기존 안전한 대체 안내", "UNVERIFIED", "prior_fallback", true, false, false);
        assertEquals(fallback, release(fallback, true, raw(LINE), List.of(citation()), true, false, false));
    }

    @Test void ordinaryEvidencePolicyPreservesUnavailableReasonAndNoMemory() {
        var partial = release(base("unknown"), true, List.of(), List.of(), false, false, false);
        var result = ChatWorkflow.applyEvidenceReleasePolicy(partial,
                ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE, false, false);
        assertEquals("verification_unavailable_guidance", result.reasonCode());
        assertEquals(partial.content(), result.content());
        assertEquals("UNVERIFIED", result.releaseStatus());
        assertFalse(result.knowledgeWriteAllowed());
    }

    @Test void interruptedTurnCannotReleaseEvenGenericGuidance() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> release(base("unknown"), true, List.of(), List.of(), false, false, false));
        } finally { Thread.interrupted(); }
    }

    @Test void unavailableOrdinaryEntityReleasesSupportedSentenceWithOnlyItsEvidence() {
        String query = "별숲에서 루미단이 뭐야?";
        String line = "루미단은 별숲의 가상 캐릭터이며 근접 공격을 사용한다.";
        var result = releaseForQuery(base("unknown"), true, query, raw(line),
                List.of(citation()), true, false, false);
        assertEquals("verification_unavailable_excerpt", result.reasonCode());
        assertTrue(result.releaseAllowed()); assertFalse(result.knowledgeWriteAllowed());
        assertEquals(List.of(citation()), result.releasedEvidence());
        assertTrue(result.content().contains("> " + line)); assertFalse(result.content().contains(DRAFT));
        var finalPolicy = ChatWorkflow.applyEvidenceReleasePolicy(result,
                ChatWorkflow.EvidenceReleaseState.EVIDENCE_PRESENT, false, false);
        assertEquals(result, finalPolicy);
    }

    @Test void descriptionCannotBypassKnownInsufficientStrictScopeOrUnknownInfrastructure() {
        String query = "별숲에서 루미단이 쎄냐?하늘꽃이 쎄냐?";
        var docs = raw("루미단은 별숲의 가상 캐릭터이며 근접 공격을 사용한다.");
        var unknown = base("unknown");
        assertEquals(unknown, releaseForQuery(unknown, false, query, docs, List.of(citation()), true, false, false));
        assertEquals(unknown, releaseForQuery(unknown, true, query, docs, List.of(citation()), true, true, false));
        assertEquals(unknown, releaseForQuery(unknown, true, query, docs, List.of(citation()), true, false, true));
        for (String verdict : List.of("rejected", "insufficient")) {
            var denied = base(verdict);
            assertEquals(denied, releaseForQuery(denied, true, query, docs, List.of(citation()), true, false, false));
        }
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> releaseForQuery(unknown, true, query, docs, List.of(citation()), true, false, false));
        } finally { Thread.interrupted(); }
    }

    @Test void lateSourceSpanMustReachRenderedPromptAndVerifierContext() throws Exception {
        String query = "별숲에서 루미단이 뭐야?";
        String support = "루미단은 별숲의 가상 캐릭터이며 근접 공격을 사용한다.";
        String body = "Navigation and site notices. ".repeat(60) + "\n" + support;
        var raw = List.of(
                Content.from(TextSegment.from(body, Metadata.from(Map.of("url", URL, "kind", "WEB")))),
                Content.from(TextSegment.from("https://example.org/title-only", Metadata.from(Map.of(
                        "url", "https://example.org/title-only", "title", "루미단 소개", "kind", "WEB")))),
                Content.from(TextSegment.from("별숲 자료 목록", Metadata.from(Map.of(
                        "url", "https://example.org/index-only", "kind", "WEB")))));
        Method filter = ChatWorkflow.class.getDeclaredMethod("filterPromptEligibleSelfAsk", List.class);
        filter.setAccessible(true);
        @SuppressWarnings("unchecked") List<Content> eligible = (List<Content>) filter.invoke(null, raw);
        var composition = new DynamicContextCompressor(new NovaOrchestrationProperties())
                .composeForPrompt(query, eligible, List.of());
        var retained = composition.web();
        assertTrue(retained.stream().anyMatch(c -> c.textSegment().text().contains(support)),
                "first failing boundary: bounded composition dropped the source-linked support span");
        var context = PromptContext.builder().userQuery(query).web(retained).rag(List.of()).build();
        String rendered = new StandardPromptBuilder().build(context);
        String verifier = ChatWorkflow.buildVerifierEvidenceContext(retained, List.of(), List.of());
        assertAll(
                () -> assertTrue(rendered.contains(support), "first failing boundary: PromptBuilder prefix rendering"),
                () -> assertTrue(verifier.contains(support), "separate failing boundary: verifier prefix rendering"));
    }

    private static ChatWorkflow.FinalVerificationReleaseDecision base(String status) {
        return ChatWorkflow.applyFinalVerificationReleaseGate(DRAFT, true, status,
                !"unknown".equals(status), false);
    }

    private static List<Content> raw(String body) {
        return List.of(Content.from(TextSegment.from(body, Metadata.from(Map.of("url", URL, "kind", "WEB")))));
    }

    private static RagEvidenceMetadata citation() {
        return new RagEvidenceMetadata("W1", "WEB", "합성 소개", URL, null, null, null, 1, 1.0, "fixture");
    }

    // Baseline remains compilable: a missing new hook returns the real existing HOLD.
    private static ChatWorkflow.FinalVerificationReleaseDecision release(
            ChatWorkflow.FinalVerificationReleaseDecision base, boolean unavailable,
            List<Content> raw, List<RagEvidenceMetadata> evidence,
            boolean promoted, boolean strict, boolean scoped) {
        return releaseForQuery(base, unavailable, QUERY, raw, evidence, promoted, strict, scoped);
    }

    private static ChatWorkflow.FinalVerificationReleaseDecision releaseForQuery(
            ChatWorkflow.FinalVerificationReleaseDecision base, boolean unavailable, String query,
            List<Content> raw, List<RagEvidenceMetadata> evidence,
            boolean promoted, boolean strict, boolean scoped) {
        try {
            Method method = ChatWorkflow.class.getDeclaredMethod("applyUnavailableVerificationRelease",
                    ChatWorkflow.FinalVerificationReleaseDecision.class, boolean.class, String.class,
                    List.class, List.class, boolean.class, boolean.class, boolean.class);
            method.setAccessible(true);
            return (ChatWorkflow.FinalVerificationReleaseDecision) method.invoke(null,
                    base, unavailable, query, raw, evidence, promoted, strict, scoped);
        } catch (NoSuchMethodException missingBeforePatch) {
            return base;
        } catch (InvocationTargetException invocation) {
            if (invocation.getCause() instanceof RuntimeException failure) throw failure;
            if (invocation.getCause() instanceof Error failure) throw failure;
            throw new AssertionError(invocation.getCause());
        } catch (ReflectiveOperationException reflection) {
            throw new AssertionError(reflection);
        }
    }
}
