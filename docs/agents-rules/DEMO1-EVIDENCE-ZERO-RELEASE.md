<!-- moved-from: AGENTS.md L154-L159 sha256=30c8ce1b6cef2850be16ac761a273fbc6aad7b13290fd76992d41dc89fdcff5a movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-EVIDENCE-ZERO-RELEASE -->
## Answer release: zero citable evidence = publish, not HOLD
- RAG ON이어도 **인용 가능 근거가 0개**이면 모델 최종 답변을 **보류하지 말고 공개**한다 — `evidenceReleaseRequired=false` 또는 일반/개념/대화 모드에서 근거 0은 정상 경로다 (`ChatWorkflow.applyEvidenceReleasePolicy`, `METADATA_INCOMPLETE`/`CONFIRMED_EMPTY`).
- 근거가 있을 때만 인용·verification을 강화한다. 근거 0일 때 `releaseReason`/`evidenceCount=0`/`unverified` 같은 메타데이터로 본문을 막지 않는다; 미검증 공개 답변은 장기 기억 저장을 차단한다(`knowledgeWriteAllowed=false`).
- HOLD는 명시적 `evidence_needed`/must-cite 지시, 미해결 final-verification 실패, 기존 safety 차단에만 남긴다 — "근거 0 = 본문 HOLD"로 되돌리지 말 것.
<!-- END DEMO1-EVIDENCE-ZERO-RELEASE -->
