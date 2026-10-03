# JevDecisionAdvisor.status()가 표면별 effective mode를 구분하지 않았다 (TOSS-JEVBET-02)
- card-id: jev-status-surface-modes-j02x
- kind: failure-signature
- status: stale (JevEvaluationRuntime.java:122 에 surfaceModes 추가됨 — 2026-10-03 확인)
- date: 2026-09-30 KST
- evidence: data/agent-handoff/clean-jev-bet-assist-20260930/FINDINGS-CLINE.md (§3-2) ; main/java/com/example/lms/assist/JevEvaluationRuntime.java:122
- reverify: `Select-String -Path main\java\com\example\lms\assist\JevEvaluationRuntime.java -Pattern 'surfaceModes'`

## 근거
- 원래 발견: status()가 전역 mode() 하나만 `map.put("mode",mode)` → `demo.jev.mode=shadow` +
  `focus=on`이면 어느 표면이 켜졌는지 관측 불가 (판정 경로가 아닌 관측성 문제).
- 현재(JevEvaluationRuntime.java:122):
  `map.put("surfaceModes", Map.of("focus",policies.resolve("focus").mode(),
  "cue",policies.resolve("cue").mode(),"main",policies.resolve("main").mode()))`
  → 표면별 effective mode 노출됨. 다만 global resolve와의 일치 검증 테스트는 원문 제안
  `statusExposesPerSurfaceEffectiveMode`가 있는지 별도 확인 필요.
