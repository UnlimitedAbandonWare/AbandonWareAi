# Fold listen: synthetic cjs green ≠ device background-listen 성공 — 성공 조건 2개는 별개
- card-id: fold-listen-matrix-h22l
- kind: checklist
- status: 확인 필요 (device grid 미검증 — 30s/2min/5min × 4 상황 전부 unverified)
- date: 2026-09-30 KST
- evidence: data/agent-handoff/codex-autonomy/hint-context-verify-handoff-888c01f0/listen-matrix.md ; scripts/fold6_display_capture_tests.cjs
- reverify: `node scripts\fold6_display_capture_tests.cjs` (synthetic 한정; device 판정 아님)

## 근거
- 성공 조건 1(away 연속 listen): 다른 탭/앱/잠금 동안 프레임이 계속 오고 server last-audio
  timestamp가 진행 — Chrome freeze/discard가 timer·callback을 멈추므로 현재 Fold 웹 범위에서
  불가능할 수 있음(native mic helper 필요).
- 성공 조건 2(복귀 회복): 같은 capture session/server segment, `resume()`은 suspended만,
  live 중 재 getUserMedia/beginVoice 금지, user-stop은 자동 재시작 안 함.
- display-voice.js 현재 코드: visibilitychange hidden=status만, visible=resume; pagehide=stop,
  pageshow persisted+frozenCapture=재시작 → 명시적 stop 후 복귀 회복이지 연속 listen 아님.
- silence with frames increasing ≠ missing frames (`audio_waiting`).
