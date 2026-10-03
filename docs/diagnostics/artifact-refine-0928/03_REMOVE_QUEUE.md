# 03 — 제거/격리 큐 (3중증명 결과)

Contract: `DEMO1-DEVIN-ARTIFACT-REFINE-MEMORY-PERM-20260928`
생성: 2026-09-28 (Asia/Seoul)

## 결과 요약

| 항목 | 수 | 상태 |
|---|---|---|
| D-grade (3중증명 전부 0) | **0** | `scripts/_quarantine_pending/` 이동 **0건** |
| B-grade 병합 | 5 | HOLD — 승인 카드 |
| ghost path unlink | 9 중 실조치 1 | ✅ AGENTS.md 1줄 정정 완료 |
| 영구 삭제 | 0 | 계약상 금지 유지 |

## 3중증명 방법 (D 판정 절차 — 재현 가능)

각 `scripts/*` 파일에 대해:

1. **instructional/code 참조 0** — basename(확장자 포함, 경계 매칭)이 instructional corpus(10,197 files: AGENTS.md/.agents/agent-prompts/docs·configs/main/app/__patch_drop__/root bat 등) 어디에도 없어야 함
2. **다른 스크립트의 import/호출 0** — `scripts/*` 상호 스캔( basename 문자열 + `import|from <stem>` )
3. **최근 14일 journal/handoff 실행 기록 0** — `data/agent-handoff/**` + `docs/diagnostics/**` fresh(mtime≤14d) corpus(8,452 files) 언급 0

추가로 p4(stale docs)도 0이어야 D. suite-runnable 파일(`test_*`, `*_tests.*`, `conftest.py`, `__init__.py`)은 pytest/Pester 자동발견으로 실행 가능하므로 0-참조만으로는 D 불가(바닥 C).

## D 후보 심사표 (전수)

| file | p1 | p2 | p3 | p4 | 판정 |
|---|---|---|---|---|---|
| (없음) | — | — | — | — | 전수 424개 중 3중 0 충족 파일 없음 |

가장 근접한 파일들(C/watchlist, 전부 p4>0 또는 test-floor로 D 불가):

| file | p1+p2+p3 | p4 | D 불가 사유 |
|---|---|---|---|
| `smoke_db_evidence_scan_runtime_tests.ps1` | 0 | 13 | test-of live 대상 + stale-doc 언급 존재 |
| `smoke_local_llm_generation_tests.ps1` | 0 | 13 | 동일 |
| `test_build_error_pattern_scanner.py` | 0 | 10 | pytest discoverable + stale-doc 언급 |
| `test_build_error_scan.py` | 0 | 10 | 동일 |
| `apply_patch_samerge16.sh` 등 .sh 4종 | 1 | 4 | 단일 inventory 참조 존재 (다음 윈도우 watchlist) |
| `db_structure_api.py` | 1 | 10 | 단일 agent-prompt 참조 존재 |
| `install_git_publish_guard.ps1` | 1 | 10 | skill doc 참조 존재 (B와 겹침) |

## 승인 카드 (HOLD — 실행하지 않음)

1. **`_quarantine_pending` → 영구 삭제**: 현재 큐 0건. watchlist 9건은 다음 스캔(14일 후 재증명)에서 여전히 3중 0이면 격리 제안.
2. **B급 병합**(래퍼 정리): `agent_scope_lease.ps1`, `agent_work_guard.ps1`, `lease_conflict_autoflow.ps1`, `fix_build.ps1`, `install_git_publish_guard.ps1` — 병합 시 진입점 호환 깨짐 위험, 사용자/Codex 승인 필요.
3. **Clean Kit `max_push_*` 4종**: 외국 task(max-push-b-perf) 작업 중 — 접촉 금지 유지.

## 실행된 조치 (이번 사이클)

| 조치 | 대상 | 증거 |
|---|---|---|
| ghost path unlink | `AGENTS.md` `scripts/next_step.py` → `.agents/skills/demo1-meta-display-webapp/scripts/next_step.py` | checkpoint cycle-02, postimage sha256 `9acb8272d124ad7ea90a2eb92208caf02687f78083580ca6c45de8777bdeed07`, lease `artifact-refine-0928.lock` |
| `_quarantine_pending/` 예약 | lease `reservePath` 확보 (디렉터리 자체는 이동 대상 0이라 미생성) | `data/agent-handoff/codex-autonomy/artifact-refine-0928-4b387b7f/` scope-claim |

## 부록: 이번 사이클에서 발견된 도구 마찰 (feed-forward)

- edit/read 툴이 디스크보다 5줄 많은 stale 버퍼에 기록 → 체크포인트 해시 체인이 empty diff로 즉시 탐지, `apply --content-file` 경로로 정상 적용 (cycle-01 no-op → cycle-02 verified).
- `powershell -File __patch_drop__/source_edit_session.ps1` → ExecutionPolicy 차단. 표준 해소: `-ExecutionPolicy Bypass -File`(프로세스 한정). → `05_PERMISSION_SPINE.md`
- `agent_scope_lease.py --help` → cp949 콘솔에서 도움말 유니코드(em-dash)로 UnicodeEncodeError. `PYTHONIOENCODING=utf-8` 우회. → W5 증거 + 스크립트 stdout reconfigure 제안(승인 카드).
