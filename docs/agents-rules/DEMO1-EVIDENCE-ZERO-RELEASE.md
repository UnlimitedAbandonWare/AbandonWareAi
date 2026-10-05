<!-- moved-from: AGENTS.md L160-L165 sha256=30c8ce1b6cef2850be16ac761a273fbc6aad7b13290fd76992d41dc89fdcff5a movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-EVIDENCE-ZERO-RELEASE -->
## Answer release: zero citable evidence = publish, not HOLD
- RAG ON이어도 **인용 가능 근거가 0개**이면 모델 최종 답변을 **보류하지 말고 공개**한다 — `evidenceReleaseRequired=false` 또는 일반/개념/대화 모드에서 근거 0은 정상 경로다 (`ChatWorkflow.applyEvidenceReleasePolicy`, `METADATA_INCOMPLETE`/`CONFIRMED_EMPTY`).
- 근거가 있을 때만 인용·verification을 강화한다. 근거 0일 때 `releaseReason`/`evidenceCount=0`/`unverified` 같은 메타데이터로 본문을 막지 않는다; 미검증 공개 답변은 장기 기억 저장을 차단한다(`knowledgeWriteAllowed=false`).
- HOLD는 명시적 `evidence_needed`/must-cite 지시, 미해결 final-verification 실패, 기존 safety 차단에만 남긴다 — "근거 0 = 본문 HOLD"로 되돌리지 말 것.

### fail-soft(판정불능) ≠ 검증 실패 — 본문 유지, 메모리만 차단
- `markFailSoft()`·`outcomeKnown=false`(judge 빈 응답·예외·malformed·예산 소진)는 **인프라 판정불능**이지 답변 부정 판정(insufficient/rejected/inconsistent)이 아니다.
- 판정불능이면 초안 본문을 유지해 공개하고 미검증 표기(`releaseReason=verification_unknown_release`)만 단다 — 본문을 HELD_NOTICE/evidence_needed 문구로 바꾸거나 `hardGuardHeld`/`existing_release_guard_hold`로 삼키지 않는다. `knowledgeWriteAllowed=false`는 유지.
- HOLD 유지 조건은 위 줄 그대로다: 명시 `evidence_needed`/must-cite, 실제 부정 판정, 기존 safety 차단뿐.
- 금지 회귀: `!outcomeKnown → HOLD`/`verification_outcome_unknown`/`releaseAllowed=false` + 그 결과가 `existing_release_guard_hold`로 본문 대체되는 경로(2026-10-05 사고 — `ChatWorkflow.applyFinalVerificationReleaseGate` 구계약).
<!-- END DEMO1-EVIDENCE-ZERO-RELEASE -->
