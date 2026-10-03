# 00 INVENTORY — DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929

WP0 재확인 결과. 기준 루트: `C:\AbandonWare\demo-1\demo-1\src` (LIVE, 2026-09-29 UTC).

## 선언

- 제품 Java/UI diff = 0 for this contract (main/java, chat.js, chat-ui.html,
  chat-trace-ui.js, chat-trace.css, chat-style.css 본편집 없음).
- commit/push 0, secrets/DSN 출력 0, DDL apply 0, task_ask/n8n/InMemoryJobQueue/
  AutogradeB/F02 재오픈 0.
- Track 분리: F01B ≠ TRACE. 병합 성공 플래그 없음. `agent_access_bundle.py`는
  두 트랙을 한 명령으로 실행하되 판정·JSON은 트랙별 분리.

## §2-A REUSE (존재 확인 — 복제 금지, 호출/최소 확장만)

| Path | mtime (UTC) | bytes |
|---|---|---|
| scripts/db_gap_scanner.py | 2026-07-12T19:05:17 | 69295 |
| scripts/agent_scope_lease.py | 2026-09-24T03:54:18 | 35657 |
| scripts/lease_conflict_autoflow.py | 2026-09-24T03:58:22 | 47818 |
| scripts/agent_code_evidence_gate.py | 2026-09-10T09:21:19 | 13783 |
| scripts/status_doc.py | 2026-09-23T00:59:04 | 7173 |
| scripts/coop_verify.py | 2026-09-28T14:18:23 | 39261 |
| scripts/codex_work_checkpoint.py | 2026-09-28T14:01:15 | 58775 |
| scripts/work_journal.py | 2026-09-26T11:07:28 | 16039 |
| scripts/agent_preflight.py | 2026-09-28T13:08:00 | 17723 |
| scripts/mgain_trace_smoke.py | 2026-09-26T13:04:54 | 21903 |
| scripts/conditional_local_git.py | 2026-09-27T04:38:40 | 36828 |
| scripts/devin_task_orchestrate.py | 2026-09-24T06:49:24 | 20360 |
| scripts/db_agent.py (live read lane; 계약 §2-A 목록 외 추가 reuse) | 2026-09-28T03:51:01 | 47292 |
| scripts/meta_display_db_export.py (live SQL gate source) | 2026-09-23T04:34:55 | 35528 |

## §2-B MUST-BUILD — 전부 MISS 확인 (이 계약이 생성)

- MISS scripts/f01b_schema_gate.py
- MISS scripts/f01b_tm_probe.py
- MISS scripts/f01b_admission_key_demo.py
- MISS scripts/f01b_evidence_pack.py
- MISS scripts/trace_dock_cost_guard.py
- MISS scripts/trace_dock_a11y_scan.py
- MISS scripts/agent_access_bundle.py
- MISS scripts/test_f01b_schema_gate.py / test_f01b_tm_probe.py /
  test_f01b_admission_key_demo.py / test_f01b_evidence_pack.py /
  test_trace_dock_cost_guard.py / test_trace_dock_a11y_scan.py /
  test_agent_access_bundle.py

## §2-C DDL 파일 (존재 ≠ DB 적용)

| Path | mtime (UTC) | bytes |
|---|---|---|
| main/resources/db/migration/V20260912__durable_jobs.sql | 2026-09-12T02:49:59 | 1157 |
| main/resources/db/migration/V20260912_03__job_idempotency.sql | 2026-09-12T05:32:39 | 446 |

Live probe (db_agent.py `--via auto`, INFORMATION_SCHEMA read-only):
`awx_jobs`, `awx_job_results` → **ABSENT** (rowCount 0, live-http lane ok).
파일 존재 ≠ 적용. GATE0 live 판정 = FAIL 후보(ABSENT) — 스크립트가 다시 측정.

## §2-D 제품 시임 (READ-ONLY — 스캔만, 편집 금지)

| Path | mtime (UTC) | bytes | 관측 포인트 |
|---|---|---|---|
| main/java/com/example/lms/jobs/JdbcJobService.java | 2026-09-12T05:32:39 | 16918 | :43 `new DataSourceTransactionManager` → PRIVATE_TM_SUSPECT 기대값 |
| main/java/com/example/lms/config/JobConfig.java | 2026-09-12T02:49:59 | 975 | jdbc 기본(matchIfMissing) / memory 명시 |
| main/resources/static/js/chat-trace-ui.js | 2026-09-27T12:57:34 | 28520 | :19 `enabled()` = admin diag + toggle.checked; :24 `withDebugQuery` → `debug=true` |
| main/resources/static/js/chat.js | 2026-09-28T09:47:36 | 332453 | :1199 `chatTraceRequestUrl` → `withDebugQuery`; :2848 `markChatDiagnosticNode`(aria-hidden); :6882 stream URL 경유 |
| main/resources/templates/chat-ui.html | 2026-09-28T05:44:28 | 29573 | `data-chat-trace-toggle` 계열 (dock selector 미구현 예상) |
| main/java/com/abandonware/ai/agent/job/InMemoryJobQueue.java | 2026-06-26T08:49:56 | 4016 | F01-B FORBIDDEN store |

## §2-E skills — SECONDARY (성공조건 아님; WP5 optional)

## 기존 외부 파일 (본 세션 생성 아님 — 건드리지 않음)

- `scripts/f01b_schema_gate_probe.py` — superseded assist 계약
  (DEMO1-DEVIN-ASSIST-F01B-TRACE-RAILS-20260929) 산출물. 이번 계약의 정식 이름은
  `f01b_schema_gate.py` (§4.1 명세, exit 0/2/3 체계). probe 측은 exit 4/5 체계라
  공존 시 혼동 가능 — 제거/병합은 별도 지시 필요, 본 세션은 참조만.
- `docs/diagnostics/f01b-narrow-jdbc-0929.md`, `targets-devin-f01b-trace.json`,
  `docs/diagnostics/devin-assist-f01b-trace-rails-0929/` — 같은 superseded 세션 산출물.

## Lease check

`python -B scripts/agent_scope_lease.py check --path scripts` 결과:
- live lease 충돌 1건: `clean-primitive-debug-ai-impl-0926` (topic 스코프 scripts) —
  상태 `expired_unknown`/`stale` (만료 2026-09-27T01:19:27Z, ~175,000s 경과),
  autoflow `staleReclaim` pending 판정. 본 계약 산출물은 전부 **신규 파일**이라
  기존 파일 바이트를 건드리지 않음. 소유권·리스 무력화 없이 진행,
  충돌 상태는 journal에 기록.
- journal-scope-overlap advisory 3건 (codex-chat-graphrag, cline-clean,
  devin-f01b-trace-rails — superseded assist) — 참고만.

## WP0 Done

- [x] §2 경로 LIVE 존재·mtime 한 줄씩 기록
- [x] MISS 목록 = MUST-BUILD 확정
- [x] lease check 실행·결과 기록
- [x] 선언: 제품 Java/UI diff = 0
