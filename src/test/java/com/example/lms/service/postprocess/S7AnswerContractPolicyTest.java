package com.example.lms.service.postprocess;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S7AnswerContractPolicyTest {

    private final S7AnswerContractPolicy policy = new S7AnswerContractPolicy();

    private static final String S7_QUERY = "질문을 Self-Ask로 명확히 재작성하고 정확히 A/B/C 후보를 제시해줘. "
            + "각 후보의 지지 근거, 반례, 측정 지표를 비교한 뒤 중립 심판이 하나를 선택하거나 HOLD와 한계를 밝혀줘.";

    private static final String KOREAN_REORDERED = """
            질문 재구성 — 동일 품질에서 지연을 가장 크게 줄이는 선택은 무엇인가?
            후보 C — 빠른 로컬 모델 라우팅
            장점: 추론 시간을 줄일 수 있다.
            위험: 복잡한 질문의 품질이 낮아질 수 있다.
            평가 기준: 계약 통과율과 p95 지연.
            후보 A — 프롬프트 압축
            장점: 입력 처리량을 줄일 수 있다.
            위험: 짧은 입력에서는 효과가 작을 수 있다.
            평가 기준: 입력 토큰 수와 p95 지연.
            후보 B — 검색 후보 축소
            장점: 검색과 재정렬 비용을 줄일 수 있다.
            위험: 필요한 근거가 탈락할 수 있다.
            평가 기준: recall과 p95 지연.
            독립 심사 결과: B
            """;

    private static final String MIXED_LANGUAGE_REORDERED = """
            Self-Ask rewritten question: Which option reduces latency without lowering answer quality?
            Option B: 검색 후보 축소
            support: retrieval and reranking work decreases.
            counterexample: recall can fall when evidence is pruned.
            metric: recall and p95 latency.
            Option A: 프롬프트 압축
            support: fewer input tokens need processing.
            counterexample: short prompts may see no gain.
            metric: input tokens and p95 latency.
            Option C: 빠른 로컬 모델 라우팅
            support: inference time can decrease.
            counterexample: hard questions can lose quality.
            metric: contract pass rate and p95 latency.
            Neutral verdict: C
            """;

    @Test
    void preservesSemanticallyCompleteVariantsWithoutReformatting() {
        for (String answer : List.of(KOREAN_REORDERED, MIXED_LANGUAGE_REORDERED)) {
            S7AnswerContractPolicy.Result result = policy.evaluate(S7_QUERY, answer);

            assertEquals(answer, result.content());
            assertFalse(result.changed());
            assertEquals("complete", result.reasonCode());
        }
    }

    @Test
    void incompleteS7ResponseBecomesExplicitHoldWithoutInventingCandidates() {
        String incomplete = "일반적인 지연 개선 방법을 검토하세요. 환경에 따라 결과는 달라질 수 있습니다.";

        S7AnswerContractPolicy.Result result = policy.evaluate(S7_QUERY, incomplete);

        assertEquals(
                "HOLD\n한계: S7 응답 계약이 완전하지 않아 후보 내용을 자동 생성하지 않았습니다.",
                result.content());
        assertTrue(result.changed());
        assertEquals("incomplete_hold", result.reasonCode());
        assertFalse(result.content().contains("A."));
        assertFalse(result.content().contains("B."));
        assertFalse(result.content().contains("C."));
    }

    @Test
    void ordinaryAnswerRemainsOutsideTheS7Policy() {
        String answer = "캐시 적중률을 먼저 측정하세요.";

        S7AnswerContractPolicy.Result result = policy.evaluate("응답 지연을 줄이는 방법은?", answer);

        assertEquals(answer, result.content());
        assertFalse(result.changed());
        assertEquals("not_applicable", result.reasonCode());
    }

    @Test
    void distinguishesNegationAndQuotedMetaRequestsFromPositiveDoNotOmitControl() {
        String answer = "요청한 형식을 적용하지 않고 개념만 설명하겠습니다.";
        List<String> nonApplicableQueries = List.of(
                "Self-Ask, A/B/C, 지지, 반례, 측정, 중립, HOLD 형식을 사용하지 마. 규칙의 문제만 설명해줘.",
                "'Self-Ask, A/B/C, 지지, 반례, 측정, 중립, HOLD'라는 형식이 무엇인지 설명만 해줘.",
                "Self-Ask, A/B/C, 지지, 반례, 측정, 중립, HOLD 형식을 쓰지 마. 규칙의 문제만 설명해줘.",
                "Do not use the Self-Ask A/B/C support counterexample metric neutral HOLD format; explain it only.");

        for (String query : nonApplicableQueries) {
            S7AnswerContractPolicy.Result result = policy.evaluate(query, answer);

            assertEquals(answer, result.content());
            assertFalse(result.changed());
            assertEquals("not_applicable", result.reasonCode());
        }

        String positiveControl = "Self-Ask로 질문을 재작성하고 A/B/C 후보의 지지, 반례, 측정 지표를 비교해 "
                + "중립 판정 또는 HOLD를 빠뜨리지 마.";
        S7AnswerContractPolicy.Result positive = policy.evaluate(positiveControl, "아직 구조가 없습니다.");

        assertTrue(positive.changed());
        assertEquals("incomplete_hold", positive.reasonCode());
    }

    @Test
    void generatedExplicitHoldIsTerminalAndIdempotent() {
        S7AnswerContractPolicy.Result first = policy.evaluate(S7_QUERY, "아직 구조가 없습니다.");

        S7AnswerContractPolicy.Result second = policy.evaluate(S7_QUERY, first.content());

        assertEquals(first.content(), second.content());
        assertFalse(second.changed());
        assertEquals("complete", second.reasonCode());
    }

}
