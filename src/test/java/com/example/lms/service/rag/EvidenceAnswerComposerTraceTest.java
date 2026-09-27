package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAnswerComposerTraceTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void composeLeavesRedactedPostprocessBreadcrumbs() {
        String rawQuery = "private evidence composer query ownerToken=raw-secret";
        List<EvidenceAwareGuard.EvidenceDoc> evidence = List.of(
                new EvidenceAwareGuard.EvidenceDoc("doc-1", "Title One", "This snippet explains alpha. Another sentence.", "https://example.test/a"),
                new EvidenceAwareGuard.EvidenceDoc("doc-2", "Title Two", "Second snippet explains beta.", "https://example.test/b"),
                new EvidenceAwareGuard.EvidenceDoc("doc-3", "Title Three", "Third snippet explains gamma.", "https://example.test/c"),
                new EvidenceAwareGuard.EvidenceDoc("doc-4", "Title Four", "Fourth snippet explains delta.", "https://example.test/d"),
                new EvidenceAwareGuard.EvidenceDoc("doc-5", "Title Five", "Fifth snippet explains epsilon.", "https://example.test/e"),
                new EvidenceAwareGuard.EvidenceDoc("doc-6", "Title Six", "Sixth snippet should not be listed.", "https://example.test/f")
        );

        String answer = new EvidenceAnswerComposer().compose(rawQuery, evidence, false);

        assertFalse(answer.isBlank());
        assertEquals(Boolean.TRUE, TraceStore.get("evidenceAnswerComposer.composed"));
        assertEquals(6, TraceStore.get("evidenceAnswerComposer.evidenceCount"));
        assertEquals(5, TraceStore.get("evidenceAnswerComposer.usedEvidenceCount"));
        assertEquals(1, TraceStore.get("evidenceAnswerComposer.unusedEvidenceCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("evidenceAnswerComposer.explanationIncluded"));
        assertEquals(SafeRedactor.hash12(rawQuery), TraceStore.get("evidenceAnswerComposer.queryHash12"));
        assertEquals(rawQuery.length(), TraceStore.get("evidenceAnswerComposer.queryLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=raw-secret"));
    }

    @Test
    void composeDoesNotPromoteUnmatchedMetadataOnlySnippetIntoExplanation() {
        String query = "현재 RAG/LIGHT 설정으로 집중을 높이는 방법을 한국어로 세 문장만 답해줘";
        List<EvidenceAwareGuard.EvidenceDoc> evidence = List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://reddit.example/r/local-web-ui",
                        "Is there any local web UI with actually decent RAG features and knowledge base handling?",
                        "Is there any local web UI with actually decent RAG features and knowledge base handling?",
                        "https://reddit.example/r/local-web-ui"));

        new EvidenceAnswerComposer().compose(query, evidence, false);

        assertEquals(Boolean.FALSE, TraceStore.get("evidenceAnswerComposer.explanationIncluded"));
    }

    @Test
    void composeDoesNotPromoteSearchMetadataUnavailableSnippetIntoAnswer() {
        String query = "현재 RAG/LIGHT 설정으로 집중을 높이는 방법을 한국어로 세 문장만 답해줘";
        List<EvidenceAwareGuard.EvidenceDoc> evidence = List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://www.kongju.example/no-description",
                        "공주대학교 페이지",
                        "We cannot provide a description for this page right now URL: https://www.kongju.example/no-description",
                        "https://www.kongju.example/no-description"));

        String answer = new EvidenceAnswerComposer().compose(query, evidence, false);

        assertEquals("검색 결과가 질문과 충분히 맞지 않아 답변을 구성하기 어렵습니다.", answer);
        assertFalse(answer.contains("We cannot provide a description"));
        assertFalse(answer.contains("검색된 자료를 바탕으로"));
        assertEquals(0, TraceStore.get("evidenceAnswerComposer.usableEvidenceCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("evidenceAnswerComposer.composed"));
    }

    @Test
    void composeReturnsEvidenceNeededWhenOfficialPromptHasNoUsableEvidence() {
        String query = "RAG 웹서치 검증: Supabase MCP read_only, 2026년 OpenAI 최신 변경, "
                + "공식 출처 또는 evidence_needed를 표시해줘.";
        List<EvidenceAwareGuard.EvidenceDoc> evidence = List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://www.kongju.example/no-description",
                        "공주대학교 페이지",
                        "We cannot provide a description for this page right now URL: https://www.kongju.example/no-description",
                        "https://www.kongju.example/no-description"));

        String answer = new EvidenceAnswerComposer().compose(query, evidence, false);

        assertTrue(answer.contains("evidence_needed"), answer);
        assertTrue(answer.contains("공식/changelog"), answer);
        assertFalse(answer.contains("We cannot provide a description"), answer);
        assertEquals(0, TraceStore.get("evidenceAnswerComposer.usableEvidenceCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("evidenceAnswerComposer.composed"));
    }

    @Test
    void composePrioritizesOfficialChangelogEvidenceWhenRequested() {
        String query = "OpenAI API 최신 변경사항을 공식 changelog 근거 위주로 알려줘";
        List<EvidenceAwareGuard.EvidenceDoc> evidence = List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://personal.example/openai-api-summary",
                        "개인 블로그 요약",
                        "개인 블로그가 OpenAI API 변경사항을 해설한 글입니다.",
                        "https://personal.example/openai-api-summary"),
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://openai.com/changelog/api-updates",
                        "OpenAI API changelog",
                        "Official changelog entry describing recent OpenAI API updates and release notes.",
                        "https://openai.com/changelog/api-updates"),
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://platform.openai.com/docs/api-reference",
                        "OpenAI API reference",
                        "Official API documentation for current request and response fields.",
                        "https://platform.openai.com/docs/api-reference"));

        String answer = new EvidenceAnswerComposer().compose(query, evidence, false);

        assertTrue(answer.contains("공식/changelog 성격의 근거를 우선"));
        assertTrue(answer.indexOf("OpenAI API changelog") < answer.indexOf("개인 블로그 요약"));
        assertTrue(answer.indexOf("OpenAI API reference") < answer.indexOf("개인 블로그 요약"));
        assertEquals(Boolean.TRUE, TraceStore.get("evidenceAnswerComposer.officialOrChangelogIntent"));
        assertEquals(2, TraceStore.get("evidenceAnswerComposer.priorityEvidenceCount"));
    }
}
