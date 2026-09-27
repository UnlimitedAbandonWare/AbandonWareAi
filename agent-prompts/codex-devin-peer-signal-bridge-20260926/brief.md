# Devin 지시서 — Codex↔Devin 공유 메모리 읽기 + 조건부 시그널 브리지 (2026-09-26)

## Goal
Codex와 Devin이 **서로의 공유 상태(저널/핸드오프/리스 해제요청/버스 inbox)** 를 한 패킷으로 읽고, 조건부 시그널을 같은 진입점에서 받게 한다.
**신규 SSOT/제2 ledger/lease를 만들지 말 것.** 기존 seam 조합 + Devin hook 대칭 + AGENTS 마커.

완료 = Acceptance 전부 PASS. “파일만 읽음” ≠ 완료. **push 금지.**

## Project Root
`C:\AbandonWare\demo-1\demo-1\src`

## Shared memory SSOT (이미 있음 — 이걸 씀)
`docs/ai-memory/AGENT_MEMORY_ROUTING.md`:
- 공유 = **repo canonical** (journals, handoff, status_doc, leases, bus)
- **금지:** Codex `~/.codex/memories*.sqlite` / Devin cloud Knowledge dump를 repo로 복사
- 제품 FocusMemory/Nova Focus ≠ 에이전트 peer memory (이번 스코프 밖)

## 이미 있는 seam (reuse — 포크 금지)
| Seam | Path |
|------|------|
| work journal + handoff | `scripts/work_journal.py`, `.agents/skills/demo1-work-ledger/` |
| status doc | `scripts/status_doc.py` |
| scope lease | `scripts/agent_scope_lease.py`, `__patch_drop__/source_edit_session.ps1` |
| lease conflict autoflow | `scripts/lease_conflict_autoflow.py` |
| change plane | `scripts/agent_change_plane.py` |
| device bus | `scripts/awx_device_bus.py` |
| goal-switch barrier | `scripts/demo1_goal_switch_barrier.py` + skill (AGENTS 마커만 없음) |
| preflight | `scripts/agent_preflight.py` |
| Codex bus hook | `.codex/hooks.json` UserPromptSubmit → `awx_device_bus.py hook` |
| Devin hooks | `.devin/hooks.v1.json` — **bus 없음** (비대칭) |

## THE ONE 작업

### A. `agent_preflight`에 `signals` (peer memory read packet)
`scripts/agent_preflight.py` 출력을 확장 (`awx.agent-preflight.v1`):
- `signals.inbox` ← `awx_device_bus.py inbox` refs only
- `signals.releaseRequests` ← 내 task 아래 `LEASE_RELEASE_REQUEST.md` (+ change-plane fallback)
- `signals.peerJournals` ← active journal list + 최근 ≤N 줄 `AUTO:` / lease_conflict / handoff (이미 truncate되는 텍스트만)
- `signals.goalSwitch` ← optional: `--agent` 있을 때 `demo1_goal_switch_barrier.py check` 요약

옵션: `--agent <codex-…|devin-…>` 로 “나한테 온 시그널” 필터.
필요 시 thin wrapper `scripts/demo1_peer_signal_read.py`는 **기존 CLI만 호출**하는 경우만 허용 (제2 SSOT 금지).

**절대 출력 금지:** secret 값, 대화 전문, `~/.codex/memories*`, `.env` 값.

### B. Devin hook 대칭
`.devin/hooks.v1.json`: 기존 triage/pre_edit_guard 유지 + Codex와 같이 UserPromptSubmit(또는 동등)에 `awx_device_bus.py hook` 추가.
JSON 파싱 가능해야 함.

### C. AGENTS 와이어
`AGENTS.md`에 `<!-- BEGIN DEMO1-GOAL-SWITCH -->…<!-- END -->` 추가:
- `$demo1-goal-switch-barrier` / objective-executor / work-ledger 포인터
- reject-complete 전 barrier check
- 행동 포크 없이 discovery만

옵션 1줄: `demo1-work-ledger` / `objective-executor` SKILL에 “진입 시 `agent_preflight` → `signals` 읽기”.

## Must NOT
- work-ledger / lease / PS1 / change-plane **복제·대체**
- 제2 SSOT, CoW alternate-path, 남의 live lease steal
- Codex/Devin personal memory sqlite·cloud dump
- secrets in shared dumps
- push / `add -A` / force-push / reset --hard
- Focus/Nova/RAG를 peer memory로 우회
- proto-open 강화, AbandonWare3 재등록
- mass-close foreign journals

## Soft-auto git
conditional_local_git 정책, foreign staging 보존, selective path only. push 없음.

## Acceptance
- [ ] `python -B scripts/agent_preflight.py --root .` JSON에 `signals.inbox` / `releaseRequests` / `peerJournals` 키
- [ ] unittest (temp root fixtures, no secrets): preflight signals
- [ ] `.devin/hooks.v1.json`에 `awx_device_bus` 포함 + parse OK
- [ ] `AGENTS.md`에 `DEMO1-GOAL-SWITCH` ≥1
- [ ] 기존 green 유지:
  `python -B -m unittest scripts.test_demo1_goal_switch_barrier scripts.test_lease_conflict_autoflow scripts.test_agent_scope_lease scripts.test_work_journal -v`
- [ ] push/secrets/개인메모리 dump 없음

## 검증 노트
명령+결과(secret 없이)를 작업 노트에 남길 것.

## SSOT
이 폴더: `agent-prompts/codex-devin-peer-signal-bridge-20260926/`
