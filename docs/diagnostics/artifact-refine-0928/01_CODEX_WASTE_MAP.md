# 01 — Codex 헛짓 지도 (quarantine-9only)

Contract: `DEMO1-DEVIN-ARTIFACT-REFINE-MEMORY-PERM-20260928`
Evidence source: `scripts/quarantine_seed_mine.py` stream-full scan → `00_SEED_MINE.md` / `.csv`
SEED: `C:\AbandonWare\_rescue\codex-quarantine-9only-20260919` (9 jsonl, 합 161,991,135 B, max 136.6MB — 재검증 일치)
Class (전원 동일): `child-stale-no-evidence`

## 세션별 태그 지도 (top-5 태그)

| session | bytes | lines | toolCalls | doneClaims | top tags (count) | 교훈 1줄 |
|---|---|---|---|---|---|---|
| `01a08a35` | 136,647,778 | 15,769 | 1,426 | 4,648 | lease_collision 695, secret_risk 641, missing_script_path 333/:err 222, wrong_launcher 263/:err 176, status_cas 40, full_suite_burn:gradle 46 | 완료주장이 도구호출의 3.3배 — 대화 길수록 gate·lease·비밀토큰 마찰이 누적 |
| `01a093c3` | 16,361,283 | 847 | 72 | 287 | lease_collision 111, secret_risk 47, missing_script_path 23/:err 12, wrong_launcher 8/:err 8 | 단일 세션도 lease 충돌 111회 — scope 선행 없이 반복 충돌 |
| `01a09465` | 2,266,471 | 163 | 18 | 58 | missing_script_path 17/:err 7, secret_risk 11, lease_collision 4 | 존재하지 않는 scripts 경로를 그대로 호출 → ghost path |
| `01a09467-19e1` | 1,259,315 | 200 | 17 | 55 | secret_risk 22, missing_script_path 17/:err 12, lease_collision 11 | devin_client.py/Invoke-Devin.ps1 유령 경로 중심 세션 |
| `01a09467-becb` | 643,605 | 134 | 12 | 29 | lease_collision 10, secret_risk 4, missing_script_path 3/:err 3 | 소규모에도 lease 충돌 상시 |
| `01a0946b` | 370,832 | 82 | 9 | 30 | secret_risk 18, missing_script_path 7, lease_collision 5 | 짧은 세션도 시크릿 패턴 노출 위험 상존 |
| `01a094ba` | 209,420 | 11 | **0** | 5 | lease_collision 3, secret_risk 3, missing_script_path 3/:err 3 | tool call 0인데 done 주장 5 → `early_done_no_cmd` 전형 |
| `01a094bb` | 2,562,439 | 54 | 5 | 16 | lease_collision 7, secret_risk 7, missing_script_path 5/:err 4 | device_probe 계열 호출 + chat-design-acceptance 유령 스킬 언급 |
| `01a094e1` | 1,242,893 | 125 | 13 | 49 | missing_script_path 31/:err 18, wrong_launcher 14/:err 9, lease_collision 13 | 경로 부재를 확인 없이 반복 호출 |

읽는 법: 태그는 키워드 히트 카운트(과대 포함 가능). `:err` 접미는 오류어("not recognized/missing") 근접 동시 출현 — 실패 확정 신호에 가까움.

## 상위 언급 토큰 → live 상태

| token | kind | mentions | sessions | live |
|---|---|---|---|---|
| `scripts/agent_code_evidence_gate.py` + test | script | 585 | 2 | exists (S급) |
| `.agents/skills/demo1-macsrc-smb-direct-patch` | skill | 282 | 9 | exists (PROTO-LIGHT) |
| `.agents/skills/demo1-agent-code-evidence-gate` | skill | 262 | 1 | exists |
| `scripts/source_health_scorecard.py` + `_validation_loop.py` | script | 342 | 4 | exists (S급) |
| `scripts/chat_ui_stream_contract_tests.js` | script | 193 | 2 | exists |
| `.agents/skills/INDEX` | skill | 89 | 9 | renamed→`INDEX.md` |
| `scripts/next_step.py` | script | 16 | 8 | **skill-local로 이동됨** (`.agents/skills/demo1-meta-display-webapp/scripts/next_step.py`) — AGENTS.md만 미갱신이었음 → 이번 사이클 정정 |
| `scripts/invoke_consolidated_program.ps1` | script | 56 | 1 | root엔 없고 **skill-local 존재** — dangling 아님 |
| `scripts/{new_docker_autograder_job,invoke_docker_autograder}.ps1`, `scripts/docker_autograder.py` | script | 78 | 1 | 전부 `.agents/skills/demo1-docker-autograder/scripts/`에 실존 — dangling 아님 |
| `scripts/{devin_client.py,Invoke-Devin.ps1}` | script | 43 | 1 | 진짜 미존재 — 그러나 참조처가 CLEAN_KICKOFF.md 서술(역사 문서)이라 unlink 불요 |
| `.agents/skills/demo1-chat-design-acceptance` | skill | 10 | 1 | 미존재 — 참조처 docs/superpowers(역사 계획 문서)뿐 |

## W# (live Codex 마찰) ↔ 가드 스크립트 매핑

실측 근거: `data/agent-handoff/codex-autonomy/max-push-b-perf-0928-50df21d2/journal.json` 이벤트 (2026-09-28).

| W# | 패턴 | quarantine 태그 대응 | 오늘의 live 증거 | 막는 가드 |
|---|---|---|---|---|
| W1 | PROJECT_STATUS foreign → CAS hold | `status_cas` | 12:28 "PROJECT_STATUS.md changed externally after checkpoint begin. CAS rejected own append" | `status_doc.py --expect-sha256` + `codex_work_checkpoint.py` preimage — **작동 확인**(기각 후 fresh checkpoint로 rebase) |
| W2 | runtime not_observed인데 진행 연출 | `early_done_no_cmd`, `no_evidence_done` | 12:28/13:05 이벤트가 "runtime not_observed"를 상태로 명시, Verify-RAG exit0+freshness=current 분리 기록 | `run_verified_command.py`(미관측 실행은 pass 아님) + verify-v2 판정 분리 규칙 |
| W3 | fixture-only / arity drift | — | 12:17 "current source uses owner/question 4-arg AttachmentService; fixture still 3-arg" + A2 stale-mock RED5→GREEN58 | focused contract test(`--tests`) + RED→GREEN 순서 강제 |
| W4 | lease 충돌 | `lease_collision` | 12:45 `lease_conflict` on `scripts/codex_work_checkpoint.py` → await_release, bypass 없음 | `agent_scope_lease.py` claim/check + `lease_conflict_autoflow.py` + `source_edit_session.ps1` |
| W5 | ghost path / 잘못된 런처 | `missing_script_path`, `wrong_launcher` | 오늘 세션에서도 `source_edit_session.ps1`이 ExecutionPolicy에 차단(→ `-ExecutionPolicy Bypass` 필요), `agent_scope_lease.py --help`가 cp949 콘솔에서 UnicodeEncodeError | `quarantine_seed_mine.py` live-crosscheck + 이번 grade_scan의 ghost 감사 + WP4 permission spine |
| W6 | no-evidence Done | `early_done_no_cmd` (class와 동일 계열) | 9/9 세션 class=`child-stale-no-evidence`; live에서 `chat-graphrag-repair` "Blocked completion audit" hold 유지 | `agent_done_evidence_guard.py`(전 단계 산출) + `demo1_goal_switch_barrier.py reject-complete`(exit 5) |
| W7 | full-suite 낭비 | `full_suite_burn`(:gradle) | 136MB 세션에서 gradle plain `test` 46회 히트 | `demo1-agent-api-spend-guard` + focused `--tests` + `compile-verify-smoke` 게이트 |
| W8(신규) | stale-buffer edit | — | **오늘 실측**: edit tool이 stale 버퍼(디스크 대비 +5줄)에 쓰고 "success" 보고, 디스크 해시 불변 → cycle-01 empty diff로 감지, checkpoint `apply` 경로로 정상 적용 | `codex_work_checkpoint.py` begin/apply/seal 해시 체인 — **이번 사이클이 실전 증명** |

## 교훈 요약

1. 헛짓의 축은 "증거 없는 진행"(done≫tool, class 전원 child-stale-no-evidence)과 "경로/권한 마찰 반복"(lease·missing path·wrong launcher)이다.
2. 가드 대부분은 이미 존재하고 **오늘도 실제로 작동 중**(CAS 기각, lease await, reject-complete) — 문제는 가드 부재가 아니라 진입 순서와 조건부 적재 → WP3/WP4가 답.
3. 유령 경로는 대부분 스킬 로컬 scripts/로의 이동 흔적 — root `scripts/` 가정이 낡은 문서에 남는다(AGENTS.md 1건 정정 완료).
