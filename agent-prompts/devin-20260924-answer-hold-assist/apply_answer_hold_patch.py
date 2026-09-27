# -*- coding: utf-8 -*-
"""ChatWorkflow.java 디스크 수술 — answer-release 분리 + explicit search-off 유지.

probe_answer_hold.py 가 요구하는 세 가지 변경:
1) futureTech 휴리스틱은 이미 켜진 web만 선호할 뿐 다시 켜지 않는다.
2) priorFallbackApplied 가 단독으로 releaseAllowed=false 를 만들지 않는다.
3) 응답 공개(releaseAllowed)와 지식 기록(knowledgeWriteAllowed)을 분리한다.
"""
import sys

PATH = r"main/java/com/example/lms/service/ChatWorkflow.java"
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


# P1 — futureTech must not re-enable caller-disabled/unspecified web search
rep(
    """        boolean futureTech = latestTechEnabled && isLatestTechQuery(finalQuery);
        // 사용자가 명시적으로 끈 검색은 휴리스틱이 다시 켜지 못한다.
        boolean webExplicitlyDisabled = req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.OFF
                || Boolean.FALSE.equals(req.getUseWebSearch());
        if (futureTech && latestTechAutoDisableVector && !webExplicitlyDisabled) {
            useWeb = true;
            useRag = false;
            log.info("[FutureTech] Web forced ON, Vector forced OFF. queryHash={}, queryLength={}",
                    SafeRedactor.hash12(finalQuery),
                    finalQuery == null ? 0 : finalQuery.length());
        }""",
    """        boolean futureTech = latestTechEnabled && isLatestTechQuery(finalQuery);
        // 사용자가 명시적으로 끈 검색은 휴리스틱이 다시 켜지 못한다.
        if (futureTech && latestTechAutoDisableVector && useWeb) {
            useRag = false;
            log.info("[FutureTech] Vector forced OFF (web already enabled). queryHash={}, queryLength={}",
                    SafeRedactor.hash12(finalQuery),
                    finalQuery == null ? 0 : finalQuery.length());
        }""")

# P2 — applyEvidenceReleasePolicy: drop priorFallbackApplied param + its release-lock
rep(
    """            boolean evidenceReleaseRequired,
            boolean priorFallbackApplied,
            boolean explicitDirectOff) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(state, "state");
        if (!base.releaseAllowed()) {
            return base;
        }
        if (priorFallbackApplied) {
            return new FinalVerificationReleaseDecision(
                    base.content(),
                    base.releaseStatus(),
                    base.reasonCode(),
                    false,
                    false);
        }
        if (explicitDirectOff""",
    """            boolean evidenceReleaseRequired,
            boolean explicitDirectOff) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(state, "state");
        if (!base.releaseAllowed()) {
            return base;
        }
        if (explicitDirectOff""")

# P3 — call site: drop priorFallbackApplied arg
rep(
    """        releaseDecision = applyEvidenceReleasePolicy(
                baseReleaseDecision,
                evidenceReleaseState,
                evidenceReleaseRequired,
                priorFallbackApplied,
                retrievalReleaseContract.explicitDirectOff());""",
    """        releaseDecision = applyEvidenceReleasePolicy(
                baseReleaseDecision,
                evidenceReleaseState,
                evidenceReleaseRequired,
                retrievalReleaseContract.explicitDirectOff());""")

# P4 — prior-fallback restore: release allowed, evidencePolicyApplied=false,
#       knowledgeWriteAllowed=false (복원된 폴백 본문은 지식으로 기록하지 않는다)
rep(
    """            releaseDecision = new FinalVerificationReleaseDecision(
                    priorFallbackContent,
                    releaseDecision.releaseStatus(),
                    releaseDecision.reasonCode(),
                    true,
                    false);""",
    """            releaseDecision = new FinalVerificationReleaseDecision(
                    priorFallbackContent,
                    releaseDecision.releaseStatus(),
                    releaseDecision.reasonCode(),
                    true,
                    false,
                    false);""")

# P5 — memory deny driven by knowledgeWriteAllowed; fallback flag by evidencePolicyApplied
rep(
    """        if (releaseDecision.evidencePolicyApplied()) {
            finalAnswerMemoryDeniedByPolicy = true;
            finalAnswerFallbackApplied = true;
        }""",
    """        if (!releaseDecision.knowledgeWriteAllowed()) {
            finalAnswerMemoryDeniedByPolicy = true;
        }
        if (releaseDecision.evidencePolicyApplied()) {
            finalAnswerFallbackApplied = true;
        }""")

# P6 — record gains knowledgeWriteAllowed (6th component)
rep(
    """    record FinalVerificationReleaseDecision(
            String content,
            String releaseStatus,
            String reasonCode,
            boolean releaseAllowed,
            boolean evidencePolicyApplied) {
    }""",
    """    record FinalVerificationReleaseDecision(
            String content,
            String releaseStatus,
            String reasonCode,
            boolean releaseAllowed,
            boolean evidencePolicyApplied,
            boolean knowledgeWriteAllowed) {
    }""")

# P7 — constructor sites gain the 6th arg
rep('"verification_not_required",\n                    true,\n                    false);',
    '"verification_not_required",\n                    true,\n                    false,\n                    true);')
rep('"verification_outcome_unknown",\n                    false,\n                    false);',
    '"verification_outcome_unknown",\n                    false,\n                    false,\n                    false);')
rep('"verification_accepted",\n                        true,\n                        false);',
    '"verification_accepted",\n                        true,\n                        false,\n                        true);')
rep('"verification_insufficient",\n                    false,\n                    false);',
    '"verification_insufficient",\n                    false,\n                    false,\n                    false);')
rep('"verification_rejected",\n                    false,\n                    false);',
    '"verification_rejected",\n                    false,\n                    false,\n                    false);')
rep('"verification_state_inconsistent",\n                false,\n                false);',
    '"verification_state_inconsistent",\n                false,\n                false,\n                false);')
rep('"evidence_release_metadata_incomplete",\n                    false,\n                    true);',
    '"evidence_release_metadata_incomplete",\n                    false,\n                    true,\n                    false);')
rep('"evidence_required_empty",\n                    false,\n                    true);',
    '"evidence_required_empty",\n                    false,\n                    true,\n                    false);')

open(PATH, "wb").write(text.encode("utf-8"))
print("WROTE", PATH, len(text))
