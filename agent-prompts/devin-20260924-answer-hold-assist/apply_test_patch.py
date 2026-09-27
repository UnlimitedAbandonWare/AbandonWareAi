# -*- coding: utf-8 -*-
"""ChatWorkflowFinalVerificationReleaseGateTest.java 디스크 수술.

1) applyEvidenceReleasePolicy 호출부에서 priorFallbackApplied 인자 제거 (4인자 계약).
2) verifierAndPriorFallbackPrecedeEvidenceReplacement 재작성:
   - 폴백 형태 본문도 evidence-state 홀드는 그대로 적용된다.
   - 이미 공개 허용된 폴백 복원 결정(knowledgeWriteAllowed=false)은 정책을 통과해 공개가 유지되고
     지식 기록만 계속 차단된다 — 공개/기록 분리 계약.
3) knowledgeWriteAllowed 신규 컴포넌트에 대한 최소 단언 추가.
"""
import sys

PATH = r"src/test/java/com/example/lms/service/ChatWorkflowFinalVerificationReleaseGateTest.java"
raw = open(PATH, "rb").read()
text = raw.decode("utf-8").replace("\r\n", "\n")


def rep(old, new, count=1):
    global text
    n = text.count(old)
    if n != count:
        print(f"FAIL anchor ({n} != {count}): {old[:90]!r}")
        sys.exit(1)
    text = text.replace(old, new, count)
    print(f"ok ({n}) {old[:70]!r}")


# ── knowledgeWriteAllowed 최소 단언 (release 허용=기록 허용, 홀드=기록 차단) ──
rep(
    """        assertEquals("verification_not_required", decision.reasonCode());
        assertTrue(decision.releaseAllowed());
    }""",
    """        assertEquals("verification_not_required", decision.reasonCode());
        assertTrue(decision.releaseAllowed());
        assertTrue(decision.knowledgeWriteAllowed());
    }""")

rep(
    """        assertEquals("verification_outcome_unknown", decision.reasonCode());
        assertFalse(decision.releaseAllowed());
    }""",
    """        assertEquals("verification_outcome_unknown", decision.reasonCode());
        assertFalse(decision.releaseAllowed());
        assertFalse(decision.knowledgeWriteAllowed());
    }""")

# ── 4인자 계약: priorFallbackApplied 인자 제거 ────────────────────────────────
rep(
    """        ChatWorkflow.FinalVerificationReleaseDecision decision = ChatWorkflow.applyEvidenceReleasePolicy(
                decide("unsupported draft", false, "not_run", false, false),
                state,
                true,
                false,
                false);""",
    """        ChatWorkflow.FinalVerificationReleaseDecision decision = ChatWorkflow.applyEvidenceReleasePolicy(
                decide("unsupported draft", false, "not_run", false, false),
                state,
                true,
                false);""")

rep(
    """            ChatWorkflow.FinalVerificationReleaseDecision decision = ChatWorkflow.applyEvidenceReleasePolicy(
                    decide("unsupported draft", false, "not_run", false, false),
                    state,
                    false,
                    false,
                    false);""",
    """            ChatWorkflow.FinalVerificationReleaseDecision decision = ChatWorkflow.applyEvidenceReleasePolicy(
                    decide("unsupported draft", false, "not_run", false, false),
                    state,
                    false,
                    false);""")

rep(
    """        ChatWorkflow.FinalVerificationReleaseDecision preservedVerifier = ChatWorkflow.applyEvidenceReleasePolicy(
                verifierDenied,
                ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE,
                true,
                false,
                false);
        assertEquals(verifierDenied, preservedVerifier);""",
    """        ChatWorkflow.FinalVerificationReleaseDecision preservedVerifier = ChatWorkflow.applyEvidenceReleasePolicy(
                verifierDenied,
                ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE,
                true,
                false);
        assertEquals(verifierDenied, preservedVerifier);""")

rep(
    """        ChatWorkflow.FinalVerificationReleaseDecision exactEvidenceHold =
                ChatWorkflow.applyEvidenceReleasePolicy(
                        decide("unsupported draft", false, "not_run", false, false),
                        ChatWorkflow.EvidenceReleaseState.CONFIRMED_EMPTY,
                        true,
                        false,
                        false);""",
    """        ChatWorkflow.FinalVerificationReleaseDecision exactEvidenceHold =
                ChatWorkflow.applyEvidenceReleasePolicy(
                        decide("unsupported draft", false, "not_run", false, false),
                        ChatWorkflow.EvidenceReleaseState.CONFIRMED_EMPTY,
                        true,
                        false);""")

# ── priorFallback 단독 release-lock 제거 계약 재작성 ─────────────────────────
rep(
    """        ChatWorkflow.FinalVerificationReleaseDecision priorFallback = ChatWorkflow.applyEvidenceReleasePolicy(
                decide("safe fallback", false, "not_run", false, false),
                ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE,
                true,
                true,
                false);
        assertEquals("safe fallback", priorFallback.content());
        assertEquals("verification_not_required", priorFallback.reasonCode());
        assertFalse(priorFallback.releaseAllowed());
        assertFalse(priorFallback.evidencePolicyApplied());
    }""",
    """        ChatWorkflow.FinalVerificationReleaseDecision fallbackHold = ChatWorkflow.applyEvidenceReleasePolicy(
                decide("safe fallback", false, "not_run", false, false),
                ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE,
                true,
                false);
        assertEquals("evidence_needed: attribution unavailable / verify retrieval evidence",
                fallbackHold.content());
        assertEquals("evidence_release_metadata_incomplete", fallbackHold.reasonCode());
        assertFalse(fallbackHold.releaseAllowed());
        assertTrue(fallbackHold.evidencePolicyApplied());
        assertFalse(fallbackHold.knowledgeWriteAllowed());

        ChatWorkflow.FinalVerificationReleaseDecision releasedFallback =
                ChatWorkflow.applyEvidenceReleasePolicy(
                        new ChatWorkflow.FinalVerificationReleaseDecision(
                                "safe fallback",
                                "NOT_REQUIRED",
                                "verification_not_required",
                                true,
                                false,
                                false),
                        ChatWorkflow.EvidenceReleaseState.NOT_APPLICABLE,
                        false,
                        false);
        assertEquals("safe fallback", releasedFallback.content());
        assertTrue(releasedFallback.releaseAllowed());
        assertFalse(releasedFallback.knowledgeWriteAllowed());
    }""")

open(PATH, "wb").write(text.encode("utf-8"))
print("WROTE", PATH, len(text))
