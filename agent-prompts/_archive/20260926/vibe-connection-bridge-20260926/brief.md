# Devin 지시서 — 바이브 “연결 안개” 2차: peer-signal + Devin bus + GOAL-SWITCH (2026-09-26)

## 배경 (현재 인상 → 이미 끝난 것 / 남은 것)
인상: **기능은 많은데 연결·환경이 지저분.**
이미 정리된 것(재작업 금지):
- `index.lock` 없음, source_edit lease 0, active journals ≈ 0
- Focus cloud embedding `matchIfMissing=false` (opt-in 완료)

아직 열린 “연결” 문제:
1. `.devin/hooks.v1.json`에 `awx_device_bus` 없음 (Codex만 있음)
2. `AGENTS.md`에 `DEMO1-GOAL-SWITCH` 마커 없음 (스크립트/스킬은 있음)
3. `agent_preflight`에 `signals.*` 패킷 없음 (inbox / releaseRequests / peerJournals)
4. (별도 제품) `/chat` control prefs 아직 sessionStorage — 이번 THE ONE 아님, runner-up

## Goal
장시간 Codex↔Devin 바이브가 **서로의 공유 상태·시그널을 한 패킷으로** 읽게 하고, Devin도 Codex와 같이 bus hook을 타며, 목적 전환 시 barrier를 AGENTS에서 발견 가능하게.
제2 ledger/lease/SSOT 금지. 제품 chat-trace/Focus 재패치 금지.
완료 = Acceptance PASS. push 금지. “읽기만” ≠ 완료.

## Project Root
`C:\AbandonWare\demo-1\demo-1\src`

## Work

### A. `agent_preflight.signals`
`scripts/agent_preflight.py` 확장:
- `signals.inbox` ← `awx_device_bus.py inbox` refs
- `signals.releaseRequests` ← `LEASE_RELEASE_REQUEST.md` (+ change-plane fallback)
- `signals.peerJournals` ← active list + 최근 AUTO:/lease/handoff (truncate)
- `signals.goalSwitch` ← optional `--agent` 시 barrier check 요약
secret/대화전문/sqlite dump 금지.

### B. Devin bus 대칭
`.devin/hooks.v1.json`: 기존 triage/guard 유지 + UserPromptSubmit에 `awx_device_bus.py hook` (Codex parity).

### C. AGENTS GOAL-SWITCH
`<!-- BEGIN DEMO1-GOAL-SWITCH -->…<!-- END -->` + `$demo1-goal-switch-barrier` / objective-executor / work-ledger 포인터.
옵션 1줄: 진입 시 `agent_preflight` → `signals` 읽기.

참고 SSOT: `agent-prompts/codex-devin-peer-signal-bridge-20260926/brief.md` (동일 seam — 중복 구현 말고 이 지시서 기준으로 완료).

## Must NOT
- work-ledger / lease / PS1 복제·대체
- Focus embedding / mgain TraceHtml / query-flow 제품 병행 (다른 세션)
- personal memory dump · secrets · push · add -A
- foreign journal mass-close · live lease steal · CoW
- proto-open 강화 · AbandonWare3

## Acceptance
- [ ] `python -B scripts/agent_preflight.py --root .` JSON에 `signals.inbox` / `releaseRequests` / `peerJournals`
- [ ] unittest (temp fixtures, no secrets)
- [ ] `.devin/hooks.v1.json`에 `awx_device_bus` + parse OK
- [ ] `AGENTS.md`에 `DEMO1-GOAL-SWITCH` ≥1
- [ ] 기존: `unittest` goal_switch_barrier / lease / work_journal 관련 green 유지
- [ ] push/secrets/제품 무관 diff 없음

## Runner-up (이번 스코프 밖 — 물어보면 다음)
`/chat` control prefs → localStorage (`chat.js` persistControlSettings). SSOT: `agent-prompts/chat-control-prefs-localstorage-20260926/`

## SSOT
`agent-prompts/vibe-connection-bridge-20260926/brief.md`
