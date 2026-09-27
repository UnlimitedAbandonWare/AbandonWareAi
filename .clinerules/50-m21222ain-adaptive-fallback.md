# m21222ain adaptive fallback + release policy (2026-09-24)

- SSOT 지시서: `docs/codex/M21222AIN_SOURCE_FIX_DIRECTIVE_2026-09-24.md`, 증거 스냅샷: `docs/codex/M21222AIN_SOURCE_EVIDENCE_2026-09-24.md`. 요약은 AGENTS.md 블록 `DEMO1-M21222AIN-ADAPTIVE-FALLBACK` 참조.
- 증거 문서의 라인 앵커는 **2026-09-24 스냅샷**이다. 적용 전 반드시 해시 대조: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify_source_evidence_hashes.ps1 -EvidenceMd docs/codex/M21222AIN_SOURCE_EVIDENCE_2026-09-24.md`. `DIFF` 파일은 메서드/필드로 재앵커하고, stale 라인 번호로 패치하지 않는다.
- GPU 정책(확정, 재질의 금지): RTX 3060 = 디스플레이 + 보조 AI(임베딩/리랭크/경량 생성) **유지**, RTX 3090 = 주 생성. 3060 비활성화/제거 금지. 폴백은 기존 `llmroute.gpu.preferred/strict/auto` + `$demo1-gpu-power-fallback` 차선 순서 재사용.
- 공개 정책 경계: 신규 HOLD 로직은 `evidenceReleaseRequired=true` 또는 인용 필수 질의에만 적용. 근거 0 + 비필수 질의는 기존 `DEMO1-EVIDENCE-ZERO-RELEASE` 대로 공개 유지. 단답(1~3줄)은 RAG/검증 활성이어도 명시 요청 없이 확장하지 않는다.
- 구현 스코프(`ChatWorkflow`/`RagEvidenceAttributionService`/`AnswerExpander` + 테스트)는 활성 Devin 레인(`m21222ain-adaptive-release-gpu-0924-cecd9f9e`) 소유 — 다른 에이전트는 rules/docs/tools 만 변경하고 해당 Java 파일은 건드리지 않는다.
- 지시서 패킷의 `acceptance_cases` JSON / source manifest JSON 은 **미수신(evidence_needed)** — 수용 기준 행을 임의로 만들지 않는다.
