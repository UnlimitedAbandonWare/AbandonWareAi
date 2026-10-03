---
name: demo1-uaw-vibe-longrun
description: Use when a demo-1 session runs a multi-hour/long-run autonomous vibe loop where a UAW/audit attachment mixes with product patches — delegates to one harmonizing live skill per phase; clones nothing
---

# demo1 UAW Vibe Longrun

장기 오토 바이브 세션용 **위임형 메타 루프**. 이 스킬은 정책을 새로 만들지 않고
조화가 확인된 live skill/script의 호출 순서만 고정한다. 각 단계의 실제 계약은
링크된 SSOT(AGENTS.md 블록 + 해당 SKILL.md)가 이긴다 — 여기 요약이 stale해지면 링크 우선.

`UAW.txt` 같은 대형 첨부는 **의도 참고만**이다. 클래스명·DONE/LIVE 표기·수치는
live 파일·테스트·Verify로 재검증하고, 충돌 시 live / AGENTS.md / 기존 skill SSOT가 이긴다.

## Entry loop

0. Root 고정 + 진입 패킷: `python -B scripts/agent_preflight.py --root . --agent <name>`
   → `signals`(bus inbox · release-request · peer journals · goalSwitch 요약)를 읽는다.
   파일 변경 작업이면 `$demo1-work-ledger` 순서: `demo1_goal_switch_barrier.py check`
   → `work_journal.py open` → 변경 전 `codex_work_checkpoint.py begin`.
1. 첨부(UAW/감사) = intent only → live Observation으로 치환
   (현재 파일·lease·checkpoint·Verify). 옛 DONE/LIVE 표기를 현재 보증으로 재사용 금지.
2. `python -B scripts/demo1_vibe_skill_router.py resolve "<goal>"` → primary(+≤1 optional).
   no-match면 skill 없이 진행. `$demo1-vibe-skill-router`.
3. 모호한 장기 목표는 `$demo1-long-think-goal-composer`로 좁은 lane 계약부터.
   Display/RAG/LLM 분류가 필요하면 `$demo1-core-request-router`.
4. 독립 seam 2개+ 붙여넣기 브리프 →
   `python -B scripts/devin_task_orchestrate.py plan --brief-file <path>`;
   phase당 1 skill만 (`$demo1-devin-source-orchestrator`).
5. Patch cycle은 `$demo1-autonomous-patch-conductor` 톤:
   Observation → RED test → 최소 diff → focused verify → ledger note.
   `begin` 실패 = 그 변경 시작 금지.
6. 상태/쿼리는 headless로: `scripts/db_agent.py`, `scripts/meta_display_db_export.py`,
   `Verify-RAG.bat` / `debug_rag_stack.ps1` (JSON·NO_PAUSE) — 사람 pause 의존 금지.
   라이브 반영은 사용자가 재시작을 요청했을 때만 `$demo1-dev-reload` / `start-rag-reload`.
7. 목표 회전 감지 → `demo1_goal_switch_barrier.py check|switch`
   (`$demo1-goal-switch-barrier`). 수용 충족 → `reject-complete` exit 0 후
   `$demo1-goal-complete-stop`으로 종료 — 다음 기능 자동 착수 금지.
8. 보고: files · cmds · NOT_RUN · coverage를 분리 — MD-only Done 금지.
   tool-ran / target-verified / build-ran / full-verification 필드를 구분한다
   (`awx.debug.verify.v2`: 401/403 = auth-blocked, suite 실패를 증거 없이
   "pre-existing"이라 부르지 않음).

## Delegates (복제 금지 — 호출만)

| Phase | Live delegate |
| --- | --- |
| 최대 에이전시 범위 | `demo1-vibe-max-agency` + `Vibe-Max-Agency.bat` |
| 의도→skill 1개 | `demo1-vibe-skill-router` + `.agents/skills-intent-index.yaml` |
| 요청 분류(Display/RAG/LLM) | `demo1-core-request-router` |
| 장기 safe-patch cycle | `demo1-autonomous-patch-conductor` |
| 다봉합 brief phase | `demo1-devin-source-orchestrator` + `scripts/devin_task_orchestrate.py` |
| 장시간 목표 분해 | `demo1-long-think-goal-composer` |
| 툴체인/빌드 선택 | `demo1-toolchain-auto-select`, `spring-build-test`, `compile-verify-smoke` |
| lease/ledger/barrier | `agent-scope-lease`, `demo1-work-ledger`, `demo1-goal-switch-barrier`, `demo1-goal-complete-stop` |
| reload/verify | `demo1-dev-reload`, `Verify-RAG.bat`, `scripts/debug_rag_stack.ps1` |
| 쿼리/DB DX | `scripts/db_agent.py`, `scripts/meta_display_db_export.py` |
| spend guard | `demo1-agent-api-spend-guard` + `docs/AGENT_API_SPEND_GUARD.md` |
| Display/Nova seam | `demo1-meta-display-simple-caption`, `demo1-nova-focus` |
| 디버그/증거 복구 | `demo1-evidence-debugging`, `demo1-repairing-from-live-evidence`, `rag-search-diagnosis`, `search-zero-result-recovery` |
| Git soft-auto (조건부 로컬) | `demo1-vibe-git-auto-continue`, `scripts/conditional_local_git.py` |

## UAW themes → live check (의도만; 재확인 후에만 패치)

| UAW 테마 | live 확인 경로 |
| --- | --- |
| canonical vs AgentApplication 레거시 | `main/java/com/example/lms/LmsApplication.java` vs `com/abandonware/ai/agent/AgentApplication.java` + 부팅 로그 `[AWX][runtime-contract]` |
| AutoConfiguration.imports / Nova* autoconfig | `main/resources/META-INF/spring/…AutoConfiguration.imports` + conditions 보고 |
| planDsl.status=not_used 계약 | `UnifiedRagOrchestrator`, `service/rag/plan/PlanDslLoader.java` — 멋대로 활성화 금지 |
| `tools/probe_*.py` before/after | 있으면 재사용; 없으면 새 엔진 강요 금지 |
| `uaw.autolearn.*` | `com/example/lms/uaw/autolearn/` 제품 기능 — 이 스킬이 새로 켜지 않음 |

상세 매핑: `docs/diagnostics/uaw-vibe-longrun-20260928/00_HARMONY_MAP.md`.

## Don't

- UAW 옛 클래스명·DONE 표기를 근거로 새 파일 생성/패치.
- PlanDsl·UnifiedRag "켜기" sprawl, autolearn 신규 활성화.
- 스킬 카탈로그 대량 삭제·복제; delegate 본문을 이 파일에 복사.
- @스킬 5개+ 나열 — primary 1 + optional ≤1 (`$demo1-vibe-skill-router` 계약).
- commit/push; `.secrets`/env 값 출력; foreign live lease 강제 해제;
  유료 web/API 팬아웃 강제 ON (`$demo1-agent-api-spend-guard`).
- 내가 시작하지 않은 Start-RAG/JVM kill.

## Optional status probe

`python -B scripts/demo1_uaw_vibe_longrun_status.py [--ask "<text>"]`
→ read-only JSON: preflight 요약 + lease 상태 + 최근 checkpoint 경로 +
router resolve echo. 새 오케스트레이터가 아니라 상태 스냅샷뿐.
