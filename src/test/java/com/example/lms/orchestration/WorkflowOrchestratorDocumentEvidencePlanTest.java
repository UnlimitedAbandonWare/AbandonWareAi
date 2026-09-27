package com.example.lms.orchestration;

import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowOrchestratorDocumentEvidencePlanTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void uploadedDocumentQuestionSelectsDocumentEvidencePlanByDefault() {
        WorkflowOrchestrator orchestrator = orchestrator();
        GuardContext ctx = new GuardContext();

        String selected = orchestrator.ensurePlanSelected(
                ctx,
                AnswerMode.BALANCED,
                QueryDomain.GENERAL,
                "summarize the uploaded document",
                true);

        assertEquals("document_evidence.v1", selected);
        assertEquals("document_evidence.v1", ctx.getPlanId());
        assertEquals(Boolean.TRUE, TraceStore.get("plan.documentEvidence"));
    }

    @Test
    void explicitHeaderModeStillWinsOverDocumentAutoSelection() {
        WorkflowOrchestrator orchestrator = orchestrator();
        GuardContext ctx = new GuardContext();
        ctx.setHeaderMode("brave");

        String selected = orchestrator.ensurePlanSelected(
                ctx,
                AnswerMode.BALANCED,
                QueryDomain.GENERAL,
                "summarize the uploaded document",
                true);

        assertEquals("brave.v1", selected);
        assertEquals("brave.v1", ctx.getPlanId());
    }

    @Test
    void freeHeaderModeSelectsCostSaverInsteadOfBraveCreativePlan() {
        WorkflowOrchestrator orchestrator = orchestrator();
        GuardContext ctx = new GuardContext();
        ctx.setHeaderMode("free");

        String selected = orchestrator.ensurePlanSelected(
                ctx,
                AnswerMode.BALANCED,
                QueryDomain.GENERAL,
                "short answer please",
                false);

        assertEquals("ap9_cost_saver.v1", selected);
        assertEquals("ap9_cost_saver.v1", ctx.getPlanId());
    }

    @Test
    void extensionParticlesUseDeclaredUnicodeBoundariesWithoutChangingPlanPriority() {
        WorkflowOrchestrator orchestrator = orchestrator();

        String[] particles = {
                "은", "는", "이", "가", "을", "를", "와", "과", "의",
                "에", "로", "으로", "에서", "에게", "부터", "까지", "만", "도"
        };
        for (String particle : particles) {
            GuardContext ctx = new GuardContext();
            String selected = orchestrator.ensurePlanSelected(
                    ctx, AnswerMode.BALANCED, QueryDomain.GENERAL,
                    "archive.docx" + particle + " 확인", true);

            assertEquals("document_evidence.v1", selected, "particle=" + particle);
            assertEquals("document_evidence.v1", ctx.getPlanId(), "particle=" + particle);
        }

        String[] acceptedDelimiterCases = {
                "archive.docx를 ",
                "archive.docx를\tcheck",
                "archive.docx를\u00A0check",
                "archive.docx를,check",
                "archive.docx를\uD83D\uDE00",
                "archive.xlsx에서 ",
                "archive.jpeg는 "
        };
        for (String query : acceptedDelimiterCases) {
            GuardContext ctx = new GuardContext();
            String selected = orchestrator.ensurePlanSelected(
                    ctx, AnswerMode.BALANCED, QueryDomain.GENERAL, query, true);

            assertEquals("document_evidence.v1", selected, "query=" + query);
            assertEquals("document_evidence.v1", ctx.getPlanId(), "query=" + query);
        }
        assertEquals(Boolean.TRUE, TraceStore.get("plan.documentEvidence"));

        for (String supportedOfficeQuery : new String[] {
                "deck.pptx\uB97C summarize",
                "brief.hwpx\uC5D0 comments",
                "photo.webp\uB97C analyze"
        }) {
            GuardContext office = new GuardContext();
            assertEquals("document_evidence.v1", orchestrator.ensurePlanSelected(
                    office, AnswerMode.BALANCED, QueryDomain.GENERAL, supportedOfficeQuery, true),
                    "query=" + supportedOfficeQuery);
        }

        for (String naturalAttachmentQuery : new String[] {
                "파일내용을 요약해줘",
                "첨부파일내용을 요약해줘",
                "첨부파일좀 확인해줘",
                "전자문서를 요약해줘"
        }) {
            GuardContext natural = new GuardContext();
            assertEquals("document_evidence.v1", orchestrator.ensurePlanSelected(
                    natural, AnswerMode.BALANCED, QueryDomain.GENERAL, naturalAttachmentQuery, true),
                    "query=" + naturalAttachmentQuery);
        }

        String[] rejectedBoundaryCases = {
                "archive.docx를\ncheck",
                "archive.docx를\u0085check",
                "archive.docx를\fcheck",
                "archive.docx를\u2028check",
                "archive.docx\u200Dfoo",
                "archive.docx\u200Cfoo",
                "archive.docx\u203Ffoo",
                "archive.docx\u1105\u1173\u11AF",
                "archive.docx를열어",
                "archive.docx에서도",
                "archive.docx가방",
                "archive.docxé",
                "archive.docx中",
                "archive.docx\u0301",
                "archive.docxfoo",
                "archive.xlsxfoo",
                "archive.jpegfoo",
                "archive.docfoo",
                "파일럿 프로젝트",
                "문서화 전략"
        };
        for (String query : rejectedBoundaryCases) {
            GuardContext ctx = new GuardContext();
            String selected = orchestrator.ensurePlanSelected(
                    ctx, AnswerMode.BALANCED, QueryDomain.GENERAL, query, true);

            assertEquals("safe_autorun.v1", selected, "query=" + query);
            assertEquals("safe_autorun.v1", ctx.getPlanId(), "query=" + query);
        }

        String particleQuery = "archive.docx를 확인";
        GuardContext noEvidence = new GuardContext();
        assertEquals("safe_autorun.v1", orchestrator.ensurePlanSelected(
                noEvidence, AnswerMode.BALANCED, QueryDomain.GENERAL, particleQuery, false));

        GuardContext brave = new GuardContext();
        brave.setHeaderMode("brave");
        assertEquals("brave.v1", orchestrator.ensurePlanSelected(
                brave, AnswerMode.BALANCED, QueryDomain.GENERAL, particleQuery, true));

        GuardContext free = new GuardContext();
        free.setHeaderMode("free");
        assertEquals("ap9_cost_saver.v1", orchestrator.ensurePlanSelected(
                free, AnswerMode.BALANCED, QueryDomain.GENERAL, particleQuery, true));

        GuardContext sensitive = new GuardContext();
        assertEquals("safe_autorun.v1", orchestrator.ensurePlanSelected(
                sensitive, AnswerMode.BALANCED, QueryDomain.SENSITIVE, particleQuery, true));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "채권 용어 설명,ap11_finance_special.v1,true,12",
            "빠르게 형식 확인,ap9_cost_saver.v1,false,6",
            "빠르게 채권 용어 설명,ap9_cost_saver.v1,false,6"
    })
    void financeAndCostOverlapRetainsCurrentPlanAndAppliedFields(
            String query, String expectedPlan, boolean officialOnly, int rerankTopK) {
        WorkflowOrchestrator orchestrator = orchestrator();
        GuardContext context = new GuardContext();
        PlanHintApplier applier = (PlanHintApplier)
                ReflectionTestUtils.getField(orchestrator, "planHintApplier");

        String selected = orchestrator.ensurePlanSelected(
                context, AnswerMode.BALANCED, QueryDomain.GENERAL, query, false);
        var hints = applier.load(selected);
        applier.applyToGuardContext(hints, context);

        assertEquals(expectedPlan, selected);
        assertEquals(expectedPlan, context.getPlanId());
        assertEquals(expectedPlan, hints.planId());
        if (officialOnly) assertEquals(Boolean.TRUE, hints.officialSourcesOnly());
        else org.junit.jupiter.api.Assertions.assertNull(hints.officialSourcesOnly());
        assertEquals(officialOnly, context.isOfficialOnly());
        assertEquals(rerankTopK, context.getPlanOverride("rerank.topK"));
        assertEquals(officialOnly, context.getPlanOverride("rerank.crossEncoder.enabled"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "weather,false", "temperature,false",
            "ETH,true", "PER,true", "Ethereum,true", "NASDAQ100,true", "ETH가격,true"
    })
    void financeAbbreviationsDoNotSelectFinanceInsideOrdinaryWords(String query, boolean finance) {
        WorkflowOrchestrator orchestrator = orchestrator();
        GuardContext context = new GuardContext();

        String selected = orchestrator.ensurePlanSelected(
                context, AnswerMode.BALANCED, QueryDomain.GENERAL, query, false);

        String expected = finance ? "ap11_finance_special.v1" : "safe_autorun.v1";
        assertEquals(expected, selected);
        assertEquals(expected, context.getPlanId());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "breakfast,false", "costume,false", "fast,true", "cost,true",
            "faster,true", "quickly,true", "cheaper,true", "costs,true"
    })
    void costAndSpeedWordsDoNotMatchInsideOrdinaryWords(String query, boolean cost) {
        WorkflowOrchestrator orchestrator = orchestrator();
        GuardContext context = new GuardContext();

        String selected = orchestrator.ensurePlanSelected(
                context, AnswerMode.BALANCED, QueryDomain.GENERAL, query, false);

        String expected = cost ? "ap9_cost_saver.v1" : "safe_autorun.v1";
        assertEquals(expected, selected);
        assertEquals(expected, context.getPlanId());
    }

    private static WorkflowOrchestrator orchestrator() {
        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                new PlanHintApplier(new DefaultResourceLoader()));
        ReflectionTestUtils.setField(orchestrator, "enabled", true);
        ReflectionTestUtils.setField(orchestrator, "defaultPlanId", "safe_autorun.v1");
        ReflectionTestUtils.setField(orchestrator, "safePlanId", "safe_autorun.v1");
        ReflectionTestUtils.setField(orchestrator, "creativePlanId", "brave.v1");
        ReflectionTestUtils.setField(orchestrator, "recencyPlanId", "recency_first.v1");
        ReflectionTestUtils.setField(orchestrator, "entityPlanId", "kg_first.v1");
        ReflectionTestUtils.setField(orchestrator, "documentPlanId", "document_evidence.v1");
        return orchestrator;
    }
}
