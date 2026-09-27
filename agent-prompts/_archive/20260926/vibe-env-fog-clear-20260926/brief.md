# Devin 지시서 — 바이브 코딩 환경 안개 제거 (2026-09-26)

## Verdict
**DEGRADED** (BLOCKED 아님). Remote/Java17/Gradle/AGENTS vibe-router/conditional-git는 OK.
장시간 바이브를 막는 건: stale `index.lock` + dead-owner lease 2 + zombie journals ~15 + Devin bus hook 비대칭 + AGENTS GOAL-SWITCH 미배선.

## Goal
제품 소스 수정 없이 **바이브 환경만** 맑게.
완료 = Acceptance PASS. push 금지. “읽기만” ≠ 완료.

## Project Root
`C:\AbandonWare\demo-1\demo-1\src`

## Evidence (점검 시점)
- `origin` = AbandonWareAi (OK)
- `.git/index.lock` = **0-byte**, git writer 없음, age≥6h
- source_edit activeCount=2, ownerProcessId=0 / heartbeat absent: `focus-cloud-embedding-optin`, `mgain-trace-assist-0926`
- `work_journal list --active` ≈ 17 in_progress (~15가 12h+ stale)
- `.codex/hooks.json`에 `awx_device_bus`; `.devin/hooks.v1.json`에는 **없음**
- `AGENTS.md`에 DEMO1-GOAL-SWITCH / goal-switch-barrier 문자열 **0**
- dirty porcelain ~6k (owned-path soft-auto는 가능; `--path` 규율 필요)

## Work (환경만 — 제품 코드 금지)

### A. Stale index.lock soft-clear
```
python -B scripts/conditional_local_git.py lock --repo . --backup-dir data/agent-handoff/<taskId>
```
또는 soft-auto 정책: 0-byte + no git writer → bak 후 remove.
**금지:** live git writer 있을 때 강제 삭제.

### B. Dead-owner lease reclaim
topics: `focus-cloud-embedding-optin`, `mgain-trace-assist-0926`
```
# 권장 경로 (둘 중 프로젝트 SSOT에 맞게)
powershell -File __patch_drop__/source_edit_session.ps1 -Action recover ...
# 또는
python -B scripts/lease_conflict_autoflow.py ... reclaim ...
```
**금지:** live lock 수동 삭제 / CoW alternate-path / 남의 live lease steal.
owner evidence 확인 후 reclaim만.

### C. Zombie journals close (owned only)
오래된 `in_progress` (~15)를 **소유 에이전트/본인 task만** close.
`work_journal.py close` 또는 `demo1_goal_switch_barrier.py` switch/close.
**금지:** foreign journal mass-close.

### D. (권장) Devin bus hook 대칭
`.devin/hooks.v1.json`: Codex와 같이 UserPromptSubmit에 `awx_device_bus.py hook` 추가. 기존 triage/guard 유지.

### E. (권장) AGENTS GOAL-SWITCH 마커
`<!-- BEGIN DEMO1-GOAL-SWITCH -->…<!-- END -->` + `$demo1-goal-switch-barrier` / objective-executor / work-ledger 포인터.

## Must NOT
- `main/java/**`, `chat.js`, TraceHtml, Focus embedding 등 **제품** 패치 (별도 brief)
- push / add -A / force-push / secrets
- live lease 강제 삭제 · foreign journal mass-close
- Start-RAG/ForceRestart/Ollama 재시작 (이번 스코프 아님)
- AbandonWare3 재등록 · proto-open 강화

## Soft-auto git
dirty가 커도 selective `--path`만. foreign staging 보존.

## Acceptance
- [ ] `.git/index.lock` 없음 (또는 live writer 있으면 남김 + 사유 기록)
- [ ] dead-owner 2 leases reclaim/recover 완료 (status에 heartbeat-absent 해당 topic 없음)
- [ ] zombie journals: 본인/허용 범위 stale in_progress 대폭 감소 (남은 건 사유 목록)
- [ ] (D 했다면) `.devin/hooks.v1.json`에 `awx_device_bus` + parse OK
- [ ] (E 했다면) `AGENTS.md`에 `DEMO1-GOAL-SWITCH` ≥1
- [ ] 제품 소스 diff 없음 (또는 훅/AGENTS만)
- [ ] push/secrets 없음

## Residual WARN (이번 THE ONE 아님)
- dirty ~6k → path discipline만
- LATEST.json springPid dead but status ready → Start-RAG는 별도
- Focus cloud opt-in / query-flow / mgain = 제품·다른 lease

## SSOT
`agent-prompts/vibe-env-fog-clear-20260926/brief.md`
