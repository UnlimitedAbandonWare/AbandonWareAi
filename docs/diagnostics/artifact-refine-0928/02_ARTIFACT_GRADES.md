# 02 — live scripts 성유물 등급표

Contract: `DEMO1-DEVIN-ARTIFACT-REFINE-MEMORY-PERM-20260928`
Scanner: `docs/diagnostics/artifact-refine-0928/grade_scan.py` (task artifact, repo는 read-only 스캔)
Detail CSV: `02_ARTIFACT_GRADES.csv` · 요약 JSON: `02_grade_scan_summary.json` · ghost 감사: `02_ghost_refs.json`
Generated: 2026-09-28T13:10:58Z

## 등급 요약

| Grade | 수 | 의미 | 조치 |
|---|---|---|---|
| S | 47 | py CLI + test 보유 + 참조됨 | KEEP + trigger 등록(06 참조) |
| A | 353 | 참조/호출 근거 존재 | KEEP |
| B | 5 | 얇은 ps1 래퍼 | MERGE 후보 (승인 카드) |
| C | 4 | 14일+ 경과 stale-doc 언급만 남은 test 계열 | INDEX only (suite 발견 가능 → 삭제 아님) |
| D | **0** | 3중증명 전부 0 | 없음 — 이동 0건 |
| X | 15 | 활성 lease(11) + Clean Kit `max_push_*`(4) | OWNER_OTHER — 미접촉 |
| 합계 | 424 | (스캔 중 sibling 태스크가 +1 생성; 계약의 ~417과 일치 범위) | |

## 증명 기준 (3중증명 → D 조건)

- p1: `rg` 대응 instructional/code corpus — AGENTS.md, `.agents/**`, `agent-prompts/**`, `docs/**`(diagnostics 제외), `configs/**`, `main/**`, `app/**`, `__patch_drop__/**`, root `*.bat` 등 = **10,197 files**
- p2: 다른 `scripts/*` 파일의 basename 언급 또는 `import/from <stem>` = scripts 424 files 상호 스캔
- p3: 최근 14일 `data/agent-handoff/**` + `docs/diagnostics/**` = **8,452 files**
- p4: 14일 이전 stale diagnostics/handoff = 3,874 files (D 판정엔 미포함, C 근거)

D = p1·p2·p3 **그리고** p4까지 전부 0. 결과 **0건** — ledger 생태계(checkpoint/manifest/저널)가 최근 14일 안에 거의 모든 스크립트 경로를 기록하므로, 엄격 기준으로 죽은 파일은 없음. 과도 삭제 방지가 본 계약의 안전 방향과 일치.

## S (47) — KEEP + trigger

권한/게이트: `agent_preflight.py`(X-leased), `agent_work_guard.py`, `agent_scope_lease.py`, `agent_port_lease.py`, `lease_conflict_autoflow.py`, `agent_git_vibe_commit.py`, `demo1_goal_switch_barrier.py`, `agent_code_evidence_gate.py`, `work_journal.py`, `run_verified_command.py`, `conditional_local_git.py`, `git_doctor.py`, `git_staged_guard.py`, `git_publish_review.py`, `patchdrop_bundle_guard.py`

메모리/컨텍스트 성장: `quarantine_seed_mine.py`(X-leased), `agent_done_evidence_guard.py`/`agent_harmony_status_cas.py`(X-leased, 전 단계 산출), `codex_context_status.py`, `agent_session_watch.py`, `agent_change_plane.py`, `chat_session_debug_export.py`, `agent_recovery_status.py`, `demo1_tool_placement_scan.py`, `awx_skill_router.py`

오케스트레이션/파이프라인: `devin_task_orchestrate.py`, `agent_work_pipeline.py`, `agent_worker_registry.py`, `awx_device_work.py`, `awx_mcp_toolbox.py`, `awx_mcp_http_server.py`, `awx_mcp_node_setup.py`, `awx_mcp_completion_audit.py`, `awx_notebook_source_directive_canary.py`

분석/스캔: `analyze_build_output.py`, `broad_catch_classifier.py`, `build_error_mitigator.py`, `db_gap_scanner.py`, `dynamic_rag_quant_audit.py`, `harmony_pressure_report.py`, `source_health_scorecard.py`, `source_health_validation_loop.py`, `persist_build_error_patterns.py`, `smoke_isolated_jar.py`, `agentic_chat_postprocess.py`, `gpu_power_fallback.py`, `query_flow_notepad_bundle.py`, `random_probe_candidate_ledger.py`, `validate_random_probe_candidate_ledger.py`, `ydrive_smb_workspace_policy_autograder.py`, `autograde_b_rail.py`

## X (15) — OWNER_OTHER (미접촉)

- 활성 lease: `agent_preflight.py`, `agent_machine_context.{py,ps1}` (agent-machine-context-dx-0928), `category_cleanup_scan.py` (category-cleanup-0928), `codex_work_checkpoint.py`, `test_checkpoint_java_json_field_read.py` (checkpoint-scanner-jackson-read-0928, 외국 lease), `quarantine_seed_mine.py`, `agent_done_evidence_guard.py`, `agent_harmony_status_cas.py`, `test_agent_done_evidence_guard.py` (quarantine-seed-harmony-0928), `uaw_spine_probe.py` (uaw-harmony-foundation-cycle02)
- Clean Kit: `max_push_done_guard.py`, `max_push_executor_log_scan.py`, `max_push_kit_env.ps1`, `max_push_skip_inactive.py`

## B (5) — MERGE 후보 (승인 필요, 실행 안 함)

| file | 래퍼 대상 | 비고 |
|---|---|---|
| `agent_scope_lease.ps1` | `agent_scope_lease.py` | 얇은 래퍼 — ps1 진입 필요성 자체는 유효(AGENTS db-agent.ps1 패턴과 동일) |
| `agent_work_guard.ps1` | `agent_work_guard.py` | 동일 패턴 |
| `lease_conflict_autoflow.ps1` | `lease_conflict_autoflow.py` | 동일 패턴 |
| `fix_build.ps1` | `apply_matrix_fixes.py` | 94d, 참조 1 (inventory 성) |
| `install_git_publish_guard.ps1` | `git_secret_guard.ps1` | 94d, 참조 1 (skill doc) |

판정: 네이밍 규칙상 `<name>.ps1 → <name>.py` 래퍼는 이 repo의 표준 진입 패턴이라 **삭제가 아니라 유지**가 맞음. B는 "병합 검토" 큐일 뿐 삭제 큐가 아님.

## C (4) — stale-doc 언급만 / suite-runnable

| file | refs | 비고 |
|---|---|---|
| `smoke_db_evidence_scan_runtime_tests.ps1` | p4=13, 94d | 대상 `smoke_db_evidence_scan_runtime.ps1` 존재 — 짝 유지 |
| `smoke_local_llm_generation_tests.ps1` | p4=13, 94d | 대상 존재 — 짝 유지 |
| `test_build_error_pattern_scanner.py` | p4=10, 94d | 이름 대상 없으나 pytest discoverable — suite 구성원 |
| `test_build_error_scan.py` | p4=10, 94d | 동일 |

## D (0) — 3중증명 통과 없음 → 이동 없음

`_quarantine_pending` 디렉터리는 lease reserve만 확보. 상세는 `03_REMOVE_QUEUE.md`.

## Watchlist (D 근접 — 다음 윈도우에서 재검증)

p1+p2+p3 ≤ 1, 참조가 기계적 inventory/단일 문서에 의존(9):

`apply_patch_samerge16.sh`, `ci-verify.sh`, `create-branches.sh`, `run-tests.sh` (유일 참조 = `agent-prompts/madasin-*/evidence/source_inventory.json` 자동 인벤토리), `README_BUILD_WRAPPER.md` (동일), `db_structure_api.py` (참조 = `agent-prompts/codex_9h_db_structure_patch.md` 1건), `smoke_dataset_api.ps1`, `smoke_graph_rag_brain_state.ps1` (참조 = `RuntimeVerificationRunbookTest.java`), `install_git_publish_guard.ps1` (B와 겹침)

## Ghost 감사 (롤아웃 토큰 → live 교차 확인)

| token | live 판정 | 실태 | 조치 |
|---|---|---|---|
| `scripts/next_step.py` | missing@root | 실물 = `.agents/skills/demo1-meta-display-webapp/scripts/next_step.py` 존재·스킬들도 그 경로 사용. **AGENTS.md 한 줄만 root 경로로 남아 있던 진짜 dangling** | ✅ 수정 완료 (checkpoint cycle-02, sha 9acb8272) |
| `scripts/invoke_consolidated_program.ps1` | missing@root | skill-local 실존(`demo1-consolidating-notebook-directives/scripts/`) + tests는 skill-relative | unlink 불요 |
| `scripts/{new_docker_autograder_job,invoke_docker_autograder}.ps1`, `scripts/docker_autograder.py` | missing/renamed@root | skill-local 실존(`demo1-docker-autograder/scripts/`) | unlink 불요 |
| `scripts/devin_client.py`, `scripts/Invoke-Devin.ps1` | missing | 참조처 = `CLEAN_KICKOFF.md` 서술 문맥(빈도 분석/가정문) — 역사 문서 | unlink 불요 (기록만) |
| `.agents/skills/INDEX` | renamed:INDEX.md | p1=826은 대부분 `INDEX.md`/관련 문자열 부분일치 — path형 `.agents/skills/INDEX` 참조는 미확인 | 관찰만 |
| `.agents/skills/demo1-chat-design-acceptance` | missing | 참조처 docs/superpowers 계획 문서 2건 (역사) | unlink 불요 |

방법론 주의: miner의 `live` 판정은 root `scripts/`·`.agents/skills/`만 확인 — skill-local `scripts/` 파일은 "missing"으로 나올 수 있음. ghost 판정 전 skill-dir 교차확인 필수(이번에 5/9가 skill-local 실존이었음).
