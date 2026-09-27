# -*- coding: utf-8 -*-
"""ChatWorkflow.java 디스크 수술 — 인사 보류 수정 + 조기반환 프레젠테이션 입력 캡처.
edit-tool 오버레이가 디스크와 발산하여 디스크 기준으로 직접 패치한다.
"""
import sys

PATH = r"main/java/com/example/lms/service/ChatWorkflow.java"
raw = open(PATH, "rb").read()
text = raw.decode("utf-8").replace("\r\n", "\n")  # 매칭은 LF 기준, 출력 시 CRLF 복원

def rep(old, new, count=1):
    global text
    n = text.count(old)
    if n != count:
        print(f"FAIL anchor ({n} != {count}): {old[:80]!r}")
        sys.exit(1)
    text = text.replace(old, new, count)
    print(f"ok ({n}) {old[:60]!r}")

# ── P1: 인사·일상 대화 플래그 (blank 체크 직전에 삽입) ──────────────────────────
rep(
"""        final boolean directRetrievalOffMode = req != null
                && (req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.OFF
                || Boolean.FALSE.equals(req.getUseWebSearch()))
                && Boolean.FALSE.equals(req.getUseRag());

        if (userQuery.isBlank()) {""",
"""        final boolean directRetrievalOffMode = req != null
                && (req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.OFF
                || Boolean.FALSE.equals(req.getUseWebSearch()))
                && Boolean.FALSE.equals(req.getUseRag());
        // 인사·일상 대화는 근거 검증이 필요 없다 — AUTO 검색/RAG 실행과 공개 게이트를 함께 생략.
        // 명시적 evidence_needed 지시·강제 검색 모드·직접 OFF 계약은 기존 규칙을 유지한다.
        final boolean casualGreetingNoEvidenceIntent = !evidenceReleaseRequired
                && !directRetrievalOffMode
                && req != null
                && req.getSearchMode() != com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT
                && req.getSearchMode() != com.example.lms.gptsearch.dto.SearchMode.FORCE_DEEP
                && NoEvidenceChatFallback.isGreeting(userQuery);

        if (userQuery.isBlank()) {""")

# ── P2a: 명시적 검색 OFF를 futureTech 휴리스틱이 다시 켜지 못하게 ────────────────
rep(
"""        boolean futureTech = latestTechEnabled && isLatestTechQuery(finalQuery);
        if (futureTech && latestTechAutoDisableVector) {""",
"""        boolean futureTech = latestTechEnabled && isLatestTechQuery(finalQuery);
        // 사용자가 명시적으로 끈 검색은 휴리스틱이 다시 켜지 못한다.
        boolean webExplicitlyDisabled = req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.OFF
                || Boolean.FALSE.equals(req.getUseWebSearch());
        if (futureTech && latestTechAutoDisableVector && !webExplicitlyDisabled) {""")

# ── P2b: 인사 스킵 — planHints 캡 이후·retrievalReleaseContract 이전에 최종 차단 ─
rep(
"""        // plan hints: cap allowWeb/allowRag
        if (planHints != null) {
            if (planHints.allowWeb() != null && !planHints.allowWeb())
                useWeb = false;
            if (planHints.allowRag() != null && !planHints.allowRag())
                useRag = false;
        }
        final RetrievalReleaseContract retrievalReleaseContract = buildRetrievalReleaseContract(""",
"""        // plan hints: cap allowWeb/allowRag
        if (planHints != null) {
            if (planHints.allowWeb() != null && !planHints.allowWeb())
                useWeb = false;
            if (planHints.allowRag() != null && !planHints.allowRag())
                useRag = false;
        }
        // 인사·일상 대화는 검색/RAG를 실행하지 않는다 — AUTO 휴리스틱이 다시 켜지 않도록 최종 차단.
        if (casualGreetingNoEvidenceIntent) {
            useWeb = false;
            useRag = false;
        }
        final RetrievalReleaseContract retrievalReleaseContract = buildRetrievalReleaseContract(""")

# ── P3: verifyAnswer — 인사는 검증 불필요 ──────────────────────────────────────
rep(
"""        boolean verifyAnswer = shouldVerify(verifierEligibilityEvidence, llmReq, sig);""",
"""        boolean verifyAnswer = !casualGreetingNoEvidenceIntent
                && shouldVerify(verifierEligibilityEvidence, llmReq, sig);""")

# ── P4: EvidenceReleaseState — 인사는 NOT_APPLICABLE ────────────────────────────
rep(
"""        FinalVerificationReleaseDecision baseReleaseDecision = releaseDecision;
        EvidenceReleaseState evidenceReleaseState = deriveEvidenceReleaseState(
                promotionResult,""",
"""        FinalVerificationReleaseDecision baseReleaseDecision = releaseDecision;
        EvidenceReleaseState evidenceReleaseState = casualGreetingNoEvidenceIntent
                ? EvidenceReleaseState.NOT_APPLICABLE
                : deriveEvidenceReleaseState(
                promotionResult,""")

# ── P5: 최종 캡처 — verificationRequired(=verifyAnswer)를 RuntimeInput에 전달 ───
rep(
"""        com.example.lms.orchestration.control.RagControlRuntimeAdapter.capturePresentationInput(
                new com.example.lms.orchestration.control.RagControlRuntimeAdapter.RuntimeInput(
                        useWeb || useRag,
                        retrievedCount,
                        citableCount,
                        out == null || out.isBlank(),
                        finalVerificationOutcomeKnown,
                        finalVerificationAcceptedForMemory,
                        releaseDecision.evidencePolicyApplied() || !releaseDecision.releaseAllowed()));""",
"""        com.example.lms.orchestration.control.RagControlRuntimeAdapter.capturePresentationInput(
                new com.example.lms.orchestration.control.RagControlRuntimeAdapter.RuntimeInput(
                        useWeb || useRag,
                        retrievedCount,
                        citableCount,
                        out == null || out.isBlank(),
                        finalVerificationOutcomeKnown,
                        finalVerificationAcceptedForMemory,
                        releaseDecision.evidencePolicyApplied() || !releaseDecision.releaseAllowed(),
                        true,
                        verifyAnswer));""")

# ── P6a: sanitizeFallbackResult null 분기 — 완료 상태로 재캡처 ─────────────────
rep(
"""        if (fallback == null) {
            return ChatResult.of("The fallback result was unavailable. Please retry the request.",
                    "fallback:unavailable", false);
        }""",
"""        if (fallback == null) {
            ChatResult missing = ChatResult.of(
                    "The fallback result was unavailable. Please retry the request.",
                    "fallback:unavailable", false);
            captureFallbackPresentationInput(missing);
            return missing;
        }""")

# ── P6b: sanitizeFallbackResult 정상 반환 — 완료 상태로 재캡처 ─────────────────
rep(
"""        return ChatResult.of(
                sanitized.content(),
                modelUsed,
                fallback.ragUsed(),
                evidence,
                evidenceMetadata);
    }

    private static void recordLocalLlmOperatorAction""",
"""        ChatResult sanitizedResult = ChatResult.of(
                sanitized.content(),
                modelUsed,
                fallback.ragUsed(),
                evidence,
                evidenceMetadata);
        captureFallbackPresentationInput(sanitizedResult);
        return sanitizedResult;
    }

    private static void recordLocalLlmOperatorAction""")

# ── P7: 조기 반환 래핑 — finishEarlyResult ─────────────────────────────────────
early_sites = [
    'return ChatResult.of(earlyRecentHistoryFallback, "history:fallback:recent", false);',
    'return ChatResult.of(earlyDirectLiteralFallback, "direct:literal", false);',
    'return ChatResult.of(earlyCurrentTurnMemoryFallback, "history:fallback:current-turn", false);',
    'return ChatResult.of(earlyExternalProofAnswer, "agent-debug:fallback:evidence", false);',
    'return ChatResult.of(earlySupabaseOperationalAnswer, "agent-debug:supabase-operational:evidence", false);',
    'return ChatResult.of(earlyAgentDebugDirectAnswer, "agent-debug:fallback:evidence", false);',
    'return ChatResult.of(agentDebugDirectAnswer, "agent-debug:fallback:evidence", useRag);',
]
for site in early_sites:
    rep(site, site.replace("return ChatResult.of(", "return finishEarlyResult(ChatResult.of(")[:-1] + ");")

# earlyCurrentModeStatusFallback (멀티라인 — 첫 줄만 바꾸고 닫는 괄호 추가)
rep(
"""            return ChatResult.of(earlyCurrentModeStatusFallback, "ui-mode:local:evidence",
                    req != null && req.isUseRag());""",
"""            return finishEarlyResult(ChatResult.of(earlyCurrentModeStatusFallback, "ui-mode:local:evidence",
                    req != null && req.isUseRag()));""")

# vision unavailable (멀티라인)
rep(
"""                return ChatResult.of(
                        "evidence_needed: vision_model_unavailable / verify with PlanModelResolverTest and active llm profile",
                        "vision:unavailable",
                        false);""",
"""                return finishEarlyResult(ChatResult.of(
                        "evidence_needed: vision_model_unavailable / verify with PlanModelResolverTest and active llm profile",
                        "vision:unavailable",
                        false));""")

# blank query (mojibake 문자열 — 위치 기반 래핑)
blank_line_idx = text.find('if (userQuery.isBlank())')
seg_start = text.find("return ChatResult.of(", blank_line_idx)
seg_end = text.find(");", seg_start)
seg = text[seg_start:seg_end + 2]
assert seg.startswith("return ChatResult.of("), seg[:80]
text = text[:seg_start] + "return finishEarlyResult(" + seg[len("return "):-1] + ")" + text[seg_end + 2:]
print("ok blank-query wrap")

# cancelled / cfg fail — 앵커 기반 래핑
for anchor in ['"cancelled", useRag);', '":fail:" + cfg.getCode(), useRag);']:
    idx = text.find(anchor)
    assert idx > 0, anchor
    start = text.rfind("return ChatResult.of(", 0, idx)
    assert start > 0
    inner_end = text.find(");", start) + 1
    orig = text[start:inner_end + 1]
    wrapped = "return finishEarlyResult(" + orig[len("return "):-1] + ");"
    text = text[:start] + wrapped + text[inner_end + 1:]
    print("ok wrap:", orig[:60])

# ── P8: 헬퍼 정의 — recordLocalLlmOperatorAction 직전에 삽입 ──────────────────
rep(
"""    private static void recordLocalLlmOperatorAction(String triggerReason,""",
"""    private static void captureFallbackPresentationInput(ChatResult result) {
        // 최종 경계 도달 전 반환되는 대체/조기 응답도 프레젠테이션 입력을 정직하게 기록한다.
        // 대체·조기 답변 자체는 근거 검증 대상이 아니므로 verificationRequired=false로 둔다.
        try {
            int metadataCount = result == null || result.evidenceMetadata() == null
                    ? 0
                    : result.evidenceMetadata().size();
            com.example.lms.orchestration.control.RagControlRuntimeAdapter.capturePresentationInput(
                    new com.example.lms.orchestration.control.RagControlRuntimeAdapter.RuntimeInput(
                            result != null && result.ragUsed(),
                            metadataCount,
                            metadataCount,
                            result == null || result.content() == null || result.content().isBlank(),
                            false,
                            false,
                            false,
                            true,
                            false));
        } catch (RuntimeException captureFailure) {
            ChatWorkflowTraceSuppressions.traceSuppressed(
                    "ragControl.presentation.capture", captureFailure);
        }
    }

    private static ChatResult finishEarlyResult(ChatResult result) {
        captureFallbackPresentationInput(result);
        return result;
    }

    private static void recordLocalLlmOperatorAction(String triggerReason,""")

out = text.replace("\n", "\r\n")
open(PATH, "wb").write(out.encode("utf-8"))
print("DONE — bytes:", len(out.encode("utf-8")))
