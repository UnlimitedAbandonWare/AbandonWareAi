# Clean(Cline) 지시서 — 에이전트 로드아웃 조화 (스킬·아이템·조건부 트리거)
날짜: 2026-09-26 KST  
수신: **Clean (Cline)**  
Project Root: `C:\AbandonWare\demo-1\demo-1\src`  
증거 소스: `C:\AbandonWare\_rescue\codex-quarantine-9only-20260919` (읽기 전용)  
Prototype Light. secrets 출력·push/`add -A` 금지. 9only 롤아웃 **sessions 재주입 금지**.

## 비유 (원신 로드아웃)
| 원신 | demo-1 | 예시 |
|---|---|---|
| 캐릭터 스킬/원소전개 | `.agents/skills/*` + `$demo1-*` | `$demo1-dev-reload`, `$demo1-tool-placement-scan` |
| 퀵슬롯 아이템 | root `*.bat` | `Start-RAG.bat`, `Read-RAG-Debug.bat`, `Safe-Cleanup.bat` |
| 무기/재료 | `scripts/*.ps1|*.py` | `start_rag_stack.ps1`, `work_journal.py` |
| 조건부 부패시브 | hooks / watch / router triggers | `.codex/hooks`, `Watch-Agents.bat`, `demo1_tool_placement_scan` misroutes |
| 파티 조합 규칙 | `demo1-codex-plugin-roles` + AGENTS | 작업 타입별 플러그인 on/off |

목표: **조화로운 배치** = 상황 → (스킬) → (bat 아이템) → (script) 한 줄로 이어지고, Codex가 예전처럼 raw `exec`/Devin 난사로 새지 않게 **부족한 슬롯만 보강**.

## Self-Ask
1. **요청:** 스크립트/bat/스킬을 에이전트가 조화롭게 쓰도록, 9only 도구 사용 기록을 보며 재구성·부족분 보강.
2. **증거:** root bat≈19, scripts py≈188+ps1≈155, skills≈124. 이미 `$demo1-tool-placement-scan` / `$demo1-codex-plugin-roles` / vibe-skill-router 존재. 9only 샘플(≤30MB 6개)에서 파일명 히트 상위는 `devin_client.py`·`Invoke-Devin.ps1`·`goal_next_auto*.ps1` 중심이고, **Start-RAG / Verify-RAG / Safe-Cleanup / work_journal / Read-RAG-Debug 히트는 거의 없음**.
3. **모호:** “전면 재배치”는 장점(레일 풍부)을 깨뜨림 → **매트릭스+트리거 보강+얇은 래퍼만**.
4. **금지:** 스킬/스크립트 대량 삭제, 새 라우터 제국, OSTP(Devin)·Codex home quarantine(다른 Clean SSOT)와 범위 충돌 작업, secrets, 제품 RAG 리팩터.
5. **THE ONE:** **Agent Loadout Harmony Pack** — 9only로 갭 표 작성 → `demo1_tool_placement_scan` 트리거/misroute 보강 → `docs/agent-loadout/LOADOUT.md` + AGENTS 포인터 → 부족한 bat/스킬 래퍼만 최소 추가.

---

## 범위 밖 (하지 말 것)
- `clean-codex-paths-hygiene-20260926` (경로/세션 GB) — 별도
- `devin-ops-surface-thin-20260926` (prompts/targets archive) — Devin
- 9only jsonl을 memories/repo에 ingest

## 작업 순서

### 1) 읽기 전용 정찰 (필수)
1. 인벤토리:
   - root `*.bat` 목록
   - `scripts/`에서 bat가 호출하는 ps1/py (각 bat 상단 15줄)
   - `.agents/skills/demo1-tool-placement-scan`, `demo1-codex-plugin-roles`, `demo1-vibe-skill-router`, `demo1-core-request-router`
2. 9only:
   - `apply-9only.jsonl`로 9개 id 확인
   - **30MB 미만** rollout만 샘플링(136MB 1개는 메타만: 크기·mtime).  
     `exec` 페이로드에서 `.bat|.ps1|.py` / Start-RAG|Verify|work_journal|Invoke-Devin|codex_home_quarantine 빈도 표.
3. 산출: `agent-prompts/clean-agent-loadout-harmony-20260926/EVIDENCE.md`  
   열: tool/cmd, count, loadout에 있어야 할 슬롯, 현재 있음?, 갭(missing wrapper / missing trigger / misroute).

### 2) 재구성 설계 (문서 = 파티 프리셋)
작성: `docs/agent-loadout/LOADOUT.md` (또는 `docs/ai-memory/AGENT_LOADOUT.md` — 기존 AGENT_MEMORY_ROUTING 옆에 두면 가독성↑)

섹션:
1. **Quickslots (bat)** — Start/Close/Verify/Debug/Watch/Safe-Cleanup/Agent-Port … 각각 → 실제 script
2. **Talents (skills)** — 상황별 첫 스킬
3. **Passives (조건부)** — placement-scan misroutes 표 + plugin-roles 요약
4. **Anti-combos (금지 조합)** — 9only에서 본 패턴:
   - Display 재시작인데 caption 스킬만
   - RAG 장애인데 Devin/`goal_next_auto`부터
   - 정리인데 safe-cleanup 스킵하고 광역 삭제
   - 검증 없이 Invoke-Devin 연쇄

원칙: **새 도구보다 기존 퀵슬롯을 트리거에 묶기.**

### 3) 코드 보강 (최소 diff) — THE ONE 구현
이미 있는 `scripts/demo1_tool_placement_scan.py` + skill을 **확장**:

1. `list-triggers` / misroutes에 9only 기반 행 추가 예:
   - ask≈ DevIn/goal_next/SWE 위임만 보이는데 RAG launch/debug 맥락 → `useInstead`: `Read-RAG-Debug.bat` 또는 `Status-RAG.bat` / `$demo1-dev-reload`
   - ask≈ cleanup/disk → `Safe-Cleanup.bat` (WhatIf) before any quarantine delete
   - ask≈ multi-agent fog → `Watch-Agents.bat` / `work_journal.py list --active` before new Devin
2. 테스트: `scripts/test_demo1_tool_placement_scan.py`에 케이스 2~4개.
3. **부족한 아이템만** 추가 (필요할 때만):
   - 예: `Invoke-Devin`이 잦은데 root bat이 없으면 `Invoke-Devin.bat` → `scripts/Invoke-Devin.ps1` 얇은 래퍼 (이미 ps1 있으면 bat만).
   - `Ops-Surface-Thin.bat`는 Devin OSTP 후순위 — 스크립트 없으면 **스텁 말고** LOADOUT에 “예정/Devin”으로만 표기.
4. AGENTS에 `<!-- BEGIN DEMO1-AGENT-LOADOUT -->` 짧은 블록: LOADOUT.md 포인터 + “새 작업 시작 시 `$demo1-tool-placement-scan scan \"...\"` 권장”.

### 4) Cline 쪽 조화 (Clean 본인)
- `.clinerules` 또는 프로젝트 Cline 규칙에 LOADOUT 한 줄:  
  “도구 고를 때 placement-scan → bat → script. 9only처럼 Devin-first 금지.”
- 스킬 대량 복사 금지(Devin skills SSOT는 `.agents/skills`).

### 5) 검증
```powershell
cd C:\AbandonWare\demo-1\demo-1\src
python -B scripts/demo1_tool_placement_scan.py list-triggers
python -B scripts/demo1_tool_placement_scan.py --root . scan "ForceRestart Meta Display"
python -B scripts/demo1_tool_placement_scan.py --root . scan "RAG launcher failed debug trail"
python -B scripts/demo1_tool_placement_scan.py --root . scan "disk cleanup stale build"
python -B -m unittest scripts.test_demo1_tool_placement_scan
```
(테스트 모듈 경로가 다르면 기존 테스트 실행법을 따를 것.)

---

## Done when
- [ ] EVIDENCE.md (9only 빈도 + 갭 표)
- [ ] LOADOUT.md 작성
- [ ] placement-scan 트리거/misroute 보강 + 테스트
- [ ] 필요 시 bat 래퍼 ≤3개 (남발 금지)
- [ ] AGENTS loadout 블록
- [ ] 스킬/스크립트 대량 삭제 없음; 9only 미재주입; secrets 미출력

## 보고 형식
```text
DONE|PARTIAL
9only sampled: N files
Top misused: ...
Gaps filled: triggers=N bats=N
LOADOUT: path
Tests: PASS|NOT_RUN
```

## 참조
- `.agents/skills/demo1-tool-placement-scan/SKILL.md`
- `.agents/skills/demo1-codex-plugin-roles/SKILL.md`
- `agent-prompts/tool-placement-scan-20260926/brief.md`
- `docs/ai-memory/AGENT_MEMORY_ROUTING.md`
- quarantine: `...\codex-quarantine-9only-20260919\apply-9only.jsonl`