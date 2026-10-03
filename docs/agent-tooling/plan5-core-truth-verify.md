# plan5-core-truth-verify — Codex PLAN5 P1 완료 판정 러너 (10줄 요약)

1. 한 줄: `python -B scripts/plan5_core_truth_verify.py --root . --xml-dir build/codex-plan5/test-results --snapshot data/agent-handoff/devin-plan5-assist-685f4d65/foreign-snapshot.json`
2. Gradle·소스 수정·서버 기동 없음 — 기존 JUnit XML·소스 패턴·스냅샷·invariant만 읽는다.
3. 판정: `READY_FOR_REVIEW`(exit 0) / `NOT_READY`(3) / `INCOMPLETE_EVIDENCE`(5) / usage(2).
4. JUnit 단계: `junit_owned_summary`로 5개 필수 클래스(PlanExecutionSpecTest·AngerOverdriveNarrowerTest·ChatStreamSignalBuilderTest·ConversationArchiveIngestServiceTest·MetaDisplayDbQueryGateTest) 집계 — MISSING은 PASS가 아니다.
5. probe 단계: `plan5_core_truth_probe` P1~P9 — STATIC_HINT일 뿐 GREEN 증명이 아님(STILL_PRESENT=차단, UNKNOWN=evidence 부족).
6. NUL 단계: `source_nul_scan` — 게이트는 U+0000뿐, 다른 C0 발견은 informational.
7. foreign-hunk 단계: snapshot 미제공 시 INCOMPLETE; `--expect-chatjs-sha8`로 chat.js 기대값 오버라이드 가능(기본 4225d944).
8. `--out <path>`로 JSON 판정 저장, `--json` stdout.
9. RED 중간 상태(클래스 FAIL·일부 MISSING)는 정상 — 코덱스 완료 후 재실행용 도구.
10. 상세: compare 표 `docs/agent-tooling/plan5-junit-evidence-compare.md`, 보류 항목 `plan5-hold-ledger.md`.
