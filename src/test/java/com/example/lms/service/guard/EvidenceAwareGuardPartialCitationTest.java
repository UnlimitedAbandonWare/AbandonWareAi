package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP3 다이나믹 가드레일: 저위험 질의의 부분 인용(1건 이상, 요구량 미달)은
 * evidence-list degrade 대신 unverified_citation 경고와 함께 원문 방출.
 * 고위험 질의와 근거 0건은 기존 insufficient_citations detour를 유지.
 */
class EvidenceAwareGuardPartialCitationTest {

    private static final String NORMAL_DRAFT =
            "알파 프로젝트는 오픈소스 데이터 파이프라인 프레임워크입니다. "
                    + "핵심 컴포넌트로 수집기와 변환기가 있으며, 설정 파일로 동작을 제어합니다. "
                    + "커뮤니티 문서와 릴리스 노트에서 동작 방식을 확인할 수 있습니다.";

    @AfterEach
    void clear() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    private static List<EvidenceAwareGuard.EvidenceDoc> docs(int n) {
        List<EvidenceAwareGuard.EvidenceDoc> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(new EvidenceAwareGuard.EvidenceDoc(
                    "doc-" + i,
                    "Evidence Doc " + i,
                    "alpha project component description " + i,
                    "https://example.com/doc" + i));
        }
        return out;
    }

    private static GuardContext ctxWithMin(int minCitations, boolean highRisk) {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setMinCitations(minCitations);
        ctx.setHighRiskQuery(highRisk);
        ctx.setUserQuery("알파 프로젝트가 뭐야?");
        GuardContextHolder.set(ctx);
        return ctx;
    }

    @Test
    void lowRiskPartialEvidenceReleasesDraftWithUnverifiedWarning() {
        ctxWithMin(4, false);
        EvidenceAwareGuard guard = new EvidenceAwareGuard();

        EvidenceAwareGuard.GuardDecision decision =
                guard.guardWithEvidence(NORMAL_DRAFT, docs(2), 2);

        assertEquals(NORMAL_DRAFT, decision.finalDraft());
        assertFalse(decision.degradedToEvidence());
        assertEquals(EvidenceAwareGuard.GuardAction.ALLOW_NO_MEMORY, decision.action());
        assertEquals(Boolean.TRUE, TraceStore.get("guard.unverifiedCitation"));
        assertEquals("ALLOW_PARTIAL_CITATIONS", TraceStore.get("guard.final.action"));
        assertEquals("unverified_citation", TraceStore.get("guard.final.action.reason"));
        assertEquals(4, TraceStore.get("guard.minCitations.required"));
        assertEquals(2, TraceStore.get("guard.minCitations.actual"));
        assertNotEquals("insufficient_citations", TraceStore.get("guard.detour"));
    }

    @Test
    void highRiskQueryStillDetoursToEvidenceList() {
        ctxWithMin(4, true);
        EvidenceAwareGuard guard = new EvidenceAwareGuard();

        EvidenceAwareGuard.GuardDecision decision =
                guard.guardWithEvidence(NORMAL_DRAFT, docs(2), 2);

        assertTrue(decision.degradedToEvidence());
        assertEquals(EvidenceAwareGuard.GuardAction.ALLOW_NO_MEMORY, decision.action());
        assertEquals("insufficient_citations", TraceStore.get("guard.detour"));
        assertEquals("DEGRADE_EVIDENCE_LIST", TraceStore.get("guard.detour.route"));
        assertFalse(NORMAL_DRAFT.equals(decision.finalDraft()));
    }

    @Test
    void zeroEvidenceStillDetoursEvenWhenLowRisk() {
        ctxWithMin(4, false);
        EvidenceAwareGuard guard = new EvidenceAwareGuard();

        EvidenceAwareGuard.GuardDecision decision =
                guard.guardWithEvidence(NORMAL_DRAFT, docs(0), 2);

        assertEquals("insufficient_citations", TraceStore.get("guard.detour"));
        assertTrue(decision.degradedToEvidence()
                || decision.action() == EvidenceAwareGuard.GuardAction.ALLOW_NO_MEMORY);
        assertEquals(Boolean.TRUE, TraceStore.get("guard.degradedToEvidence"));
        assertFalse(Boolean.TRUE.equals(TraceStore.get("guard.unverifiedCitation")));
    }

    @Test
    void evasiveTemplateDraftDoesNotGetPartialRelease() {
        ctxWithMin(4, false);
        EvidenceAwareGuard guard = new EvidenceAwareGuard();
        String evasive = "제공된 문서에서 해당 내용을 찾을 수 없습니다.";

        EvidenceAwareGuard.GuardDecision decision =
                guard.guardWithEvidence(evasive, docs(2), 2);

        assertFalse(NORMAL_DRAFT.equals(decision.finalDraft()));
        assertFalse(Boolean.TRUE.equals(TraceStore.get("guard.unverifiedCitation")));
        assertEquals("insufficient_citations", TraceStore.get("guard.detour"));
    }
}
