# coop-verify-0928 · 00_PROBE — measured attachment points

Contract: `DEMO1-DEVIN-COOP-VERIFY-RAILS-FOR-CODEX-20260928`
Probe time: 2026-09-28 ~22:4x KST · mode: read-only · agent: devin (journal `coop-verify-0928-f21d329b`)
표기: `사실` = 이 체크아웃에서 직접 확인, `추정` = 미확인/간접.

## 1. 존재 확인 (지시서 §2 목록 재측정)

| 경로 | 상태 | 비고 |
|---|---|---|
| `AGENTS.md` | HIT | sha256 `9acb82…` (preflight protections) |
| `scripts/work_journal.py` | HIT | per-task journal JSON |
| `scripts/agent_work_guard.py` | HIT | `root/path/check/record/hook/advise/status` 액션 |
| `scripts/agent_scope_lease.py` | HIT | `who/check/claim/verify/heartbeat/done/abort/recover/reclaim/show` |
| `scripts/lease_conflict_autoflow.py` | HIT | `scan/plan/reclaim/heartbeat/request-release` |
| `scripts/agent_code_evidence_gate.py` | HIT | `main()` 단일 엔트리(검증 증거 게이트) |
| `scripts/agent_preflight.py` | HIT | 진입 패킷(journal/lease/signals 요약) |
| `scripts/coop_verify.py` | MISS | 이번 신규 생성 대상 |
| `scripts/source_edit_session.ps1` | MISS | 실제 위치는 `__patch_drop__/source_edit_session.ps1` (사실) |
| `__patch_drop__/source_edit_session.ps1` | HIT | `-Action begin|end|status|verify|bind-scope|heartbeat|recover` |
| `.agents/skills/demo1-work-ledger` | HIT | journal/preimage 절차 SSOT |
| `.agents/skills/safe-source-edit` | HIT | |
| `.agents/skills/compile-verify-smoke` | HIT | 별도 wrapper 없음: `gradlew.bat compileJava` + 기존 `smoke_*`/`verify_*` 직접 호출 권고만 |
| `.agents/skills/demo1-lease-conflict-autoflow` | HIT | |
| `.agents/skills/demo1-agent-code-evidence-gate` | HIT | |
| `.agents/skills/awx-cooperative-verification` | MISS | 이번 신규 생성 대상 |
| `.codex/hooks.json` + `.codex/hooks/` | HIT | hooks: `project_capabilities_hook.ps1`, `source_edit_root_hook.ps1`, `source_edit_triage.*` |
| `.codex/config.toml` | HIT | model verbosity만; 훅 관련 없음 |
| `.devin/hooks.v1.json` | HIT | 프로젝트 레벨 Devin 훅 (outer `hooks` 키 없는 v1 형식) |
| `~/.config/devin`, `~/.devin` | MISS | 사용자 레벨 Devin 설정 디렉터리 없음 (사실: 빈 dir 출력) |
| `data/agent-handoff/coop-verify/` | MISS | 기존 ticket/runner 저장소 없음 → 신규 파생 저장소 위치 |

## 2. 기존 상태 저장소 (단일 진실로 유지)

### journal — `data/agent-handoff/codex-autonomy/<taskId>/journal.json` (사실)
- `schemaVersion: awx.work_journal.v1`; 필드: `taskId, agent, purpose, plannedScope[], status(in_progress|closed), result, startedAtUtc, updatedAtUtc, endedAtUtc, events[]`
- 쓰기 원자성: `codex_work_checkpoint.write_json()` = uuid `.tmp` + `os.fsync` + `os.replace` (사실, ck 모듈 L544-556)
- taskId `<slug>-<8hex>`; `PROTECTED_PARTS={.git,.codex,.secrets}`, 비밀 suffix 거부
- 현재 active: 13 foreign `in_progress` (대표: `max-push-b-perf-0928-50df21d2` = 이번 계약이 겨냥하는 "Codex 중간코드 반복빌드" 시나리오의 실재 actor)

### source-edit lease — `__patch_drop__/source-edit-locks/<topic>.lock/lease.json` (사실)
- `.lock`은 **디렉터리**(`type`하면 access denied — 파일 아님)
- 필드: `schemaVersion awx.source_edit_session.lease.v1, leaseId, taskIdHash, ownerHash, ownerHostHash, ownerProcessId, ownerProcessStartedAtUtc, generatedAt, startedAtUtc, topic, role, ownerId("devin:<taskId>"), root, expiresAtUtc, mutationAllowed, targetManifestHash, targetCount, operation("worktree-edit"), targetPaths[], coordinationMode("target-scoped")`
- heartbeat: `source-edit-heartbeats/<leaseId>.json` → `{leaseId, leaseFingerprint, renewedAtUtc, expiresAtUtc}` (TTL ~3h, owner pid는 lease 본체에만)
- scope: `source-edit-scopes/<manifestHash>.json` → `{targets:[{path,sha256}]}`
- events: `source-edit-events/*.jsonl` (~1400건) + `registry.jsonl`; quarantine: `source-edit-quarantine/<hash>/`
- 현재 active lease 1건: `checkpoint-scanner-jackson-read-0928` → `scripts/codex_work_checkpoint.py` + `scripts/test_checkpoint_java_json_field_read.py` (foreign, 만료 2026-09-28T15:25Z). **본 작업에서 이 두 파일 편집 금지.**
- 상태 판독: `source_edit_session.ps1 -Action status -Json` → `sourceLeases[]` + `targetConflict.allowed` (매니페스트 파일 경유)

### claim 레이어 — `scripts/agent_scope_lease.py` (사실)
- `claim` = journal 첨부 + source lease begin + claim 기록을 한 동작으로; `check` = 경로/기능 충돌 exit 7
- lease는 "target-scoped"이지 배타 실행 락이 아님 — writer/verify 상호배제는 coop_verify가 별도 책임

## 3. 훅 표면 (실측)

### Codex `.codex/hooks.json` (사실)
- `UserPromptSubmit`: capabilities hook + source-edit triage root probe
- `PreToolUse`: `apply_patch|write|edit|notebook_edit` → `scripts/devin_pre_edit_guard.ps1` (exit2=block); `Bash` → `scripts/agent_work_guard.ps1`
- `PostToolUse`: `Bash` → `agent_work_guard.ps1`
- **Stop/SessionEnd/SessionStart 훅 없음** — "종료 전 무조건 빌드" 정책은 훅이 아니라 `demo1-work-ledger`/`demo1-goal-complete-stop`/AGENTS 문구(verification-before-completion)에 존재 (사실). FOR_CODEX는 훅 신설보다 **완료정책 문구 + build wrapper 진입점** 연결이 실질 seam.

### Devin `.devin/hooks.v1.json` (사실)
- 동일 구조(v1: 최상위 `hooks` 키 없음): PreToolUse write/edit/apply_patch/notebook_edit → `devin_pre_edit_guard.ps1`; `exec` → `agent_work_guard.ps1`; PostToolUse `exec` 동일
- **Stop/SessionEnd 훅 없음** (사실). Stop 훅 지원 자체는 설치본 확인 필요(추정: v1 스키마는 lifecycle 훅 지원 — docs.devin.ai/cli 기준; Desktop 설치본 미실측)

### `devin_pre_edit_guard.ps1` (사실)
- stdin 훅 이벤트 → 대상 경로 추출 → `__patch_drop__/source_edit_lease_contract.ps1`의 `Get-AwxSourceEditConflictDecision` → foreign lease 겹침 시 `{"decision":"block"}` + exit 2
- 가드 자체는 fail-open; 강제는 `codex_work_checkpoint.py` apply/restore 쪽이 담당

## 4. 검증 실행 경로 (실측)

| 용도 | 실제 엔트리 |
|---|---|
| 컴파일 | `gradlew.bat :compileJava -x test` (+`:processResources`) — PROJECT_STATUS §2 |
| 단위 테스트 | `gradlew.bat test --tests <Fqcn>` |
| Python 테스트 | `python -B -m pytest scripts/test_*.py` (pytest 9.0.2, scripts/test_*.py 120+ 파일이 컨벤션) |
| 기록付 실행 러너 | `scripts/run_verified_command.py --output <dir> [--source <path>] -- <cmd>` — runId/exit/log/소스 identity 보존, `status`/`stop` 서브동작 있음 |
| 라이브 verify | `Debug-RAG.bat -Action verify` / `Debug-Meta-Display.bat -Action verify` (8-check verdict, freshness= stale-candidate 정상탐지) |
| 스모크 | `Verify-RAG.bat`, `scripts/start_rag_stack.ps1` 계열 |

- "build wrapper"의 실체: 별도 wrapper 파일 없음; 사실상 호출 지점 = gradlew/Verify-RAG/Debug-RAG/`run_verified_command.py`. **coop_verify는 `request|run-once`로 이 진입점들 앞에 붙는 조정자**이며 기존 명령을 바꾸지 않는다.

## 5. ticket/runner 기존 흔적

- `data/agent-handoff/` 전수(이름 패턴) 조사: `*ticket*`/`*runner*` 디렉터리·파일 없음 (사실). `verify-*`는 과제별 ad-hoc 로그/스크립트일 뿐 큐가 아님.
- 결론: `data/agent-handoff/coop-verify/{state.json,.coop.lock,receipts/,runner.json}` 신설 — 지시서 §4-C "기존 lease/journal 경로 우선, 없으면 coop-verify/ 아래 ticket/receipt만"에 부합.

## 6. 원자성/잠금 재사용 가능 프리미티브 (사실)

- `codex_work_checkpoint.write_json(path, value)` — uuid tmp + fsync + `os.replace` (임포트로 재사용, 파일 자체는 foreign lease 대상이라 **수정 금지·읽기 전용 임포트만**)
- `codex_work_checkpoint.opened()` 패턴 — `.operation.lock`을 `open("xb")` 배타 생성 + `pid_alive()`로 사망 소유자만 회수. coop_verify는 같은 패턴으로 `.coop.lock`을 구현(모듈 임포트 or 동일 코드 경로)
- `pid_alive(pid)`: Windows `OpenProcess(0x1000)` + `GetExitCodeProcess==259`
- `ck.require/secret_free/digest/relative_path/contents` 재사용 가능

## 7. 설계 적합성 판단 (PROBE → DESIGN)

- writer 단위 = edit_batch: 기존 lease(파일 집합)보다 **논리적 묶음**이 크므로 coop_verify writer는 `edit_batch_id`를 별도 키로 가지고 `--lease-id`로 기존 lease를 *참조*만 한다(진실 이중화 금지).
- 심의 결과(설계/제작): `agent_work_guard.py` 확장이 아니라 **신규 `scripts/coop_verify.py`** 채택 — guard는 "path/retry brake"라는 다른 계약이고 subcommand 세트(status/writer-*/request/run-once/watch/recover)가 그 범위를 넘는다. 다만 잠금/쓰기 프리미티브는 ck 모듈 재사용으로 중복 구현 0.
