# display audio reconnect: display-voice.test.cjs 15/15 PASS + DisplayAudioReestablishTest PASS, E07 audioEpoch 7/7 RED fixture
- card-id: display-audio-reconnect-d26e
- kind: measured-number
- status: still-true (역사 측정값 2026-09-26; 패치는 working tree에 이미 반영됨)
- date: 2026-09-26 KST
- evidence: data/agent-handoff/display-debug/devin-evidence-20260926.md ; src/test/js/display-voice.test.cjs
- reverify: `node src\test\js\display-voice.test.cjs`

## 근거
- THE-ONE 3 seams 패치: DisplayConversateController.java, display-voice.js,
  display-conversate.js + 신규 테스트 2종.
- node display-voice.test.cjs = 15/15 (stale_epoch recoverable-wait, bounded drop retries→park,
  drifted-epoch refresh once, restarted capture adopts returned epoch, transient poll conflict).
- gradlew :test --tests DisplayAudioReestablishTest = PASS (25s, 당시).
- E07: red-01 = 7/7 audioEpoch tests RED (proper RED, unpatched NovaFocus 기준).
- Jev gateway smoke = FAIL auth-blocked HTTP 401 (AI_GATEWAY_API_KEY 거부) — 당시 상태.
