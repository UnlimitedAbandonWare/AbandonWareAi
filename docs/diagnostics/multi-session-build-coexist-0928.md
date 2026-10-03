# Multi-session build/test coexistence (DEMO1-MULTI-SESSION-BUILD-20260928)

Contract: `DEMO1-DEVIN-MULTI-SESSION-BUILD-20260928` (2026-09-28).
Scope: Codex / Devin / Grok / Clean이 **같은 checkout**(`C:\AbandonWare\demo-1\demo-1\src`)을 동시에
만질 때 빌드·JVM·GPU·git이 서로 밟지 않게 하는 **운영 규칙 카드**. 제품 기능 패치 없음.
모든 규칙의 normative 문장은 아래 SSOT 표의 기존 파일에 있다 — 이 문서는 **포인터 + 붙여넣기
헤더 + HOLD 체크리스트**만 추가한다. 상세 정책이 여기와 다르면 SSOT 쪽이 이긴다.

## 모든 에이전트 붙여넣기 (헤더)

세션 첫 지시/보고 앞에 그대로 붙인다:

```text
[MULTI-SESSION] Project Root: C:\AbandonWare\demo-1\demo-1\src
AWX_SPLIT_BUILD_OUTPUTS=1; AWX_BUILD_HOST_ID=<me-short>   # 예: devin-r3, codex-b04, grok-review
focused tests only; no clean / no full suite
one Spring/DevWatch owner; no second 18180-18182; no kill others' JVM
lease before edit; skip files already leased
no add -A / push; preserve foreign staging
spend-guard: no replay of a green check; no paid fanout
ambiguous build -> HOLD_AMBIGUOUS_BUILD; don't "fix" product on it
```

PowerShell env (세션 시작 시 1회):

```powershell
$env:AWX_SPLIT_BUILD_OUTPUTS='1'; $env:AWX_BUILD_HOST_ID='devin-<short>'
```

## 규칙 → 기존 SSOT 표 (사실 = 이 체크아웃에서 확인됨)

| 규칙 | 요지 | SSOT (사실: 경로·기능 확인) |
|---|---|---|
| R1 한 머신·한 live RAG | Start-RAG/ForceRestart/DevWatch는 한 세션 소유. 타 세션은 `-CheckOnly`/읽기 검증만. 남의 JVM kill 금지. 18180–18182에 두 번째 Spring 금지. 정적(js/css)만 고쳤으면 재시작 불요. | `.agents/skills/demo1-dev-reload/SKILL.md` (one watcher, no second Spring, static→no restart); AGENTS `DEMO1-AGENT-PORT-LEASE` (`scripts/agent_port_lease.py`, `Agent-Port.bat` — 병렬 에이전트 서버는 포트 리스); AGENTS `DEMO1-VIBE-MAX-AGENCY` (unowned runtime 재기동 금지); `.agents/skills/demo1-agent-api-spend-guard/SKILL.md` §User self-verify stop 3 (내가 띄우지 않은 Spring/RAG stop 금지); wear-runtime port protection은 세션 간에도 적용 (AGENTS `DEMO1-DEVIN-MULTI-SESSION`) |
| R2 Gradle 좁게 | focused `--tests <Fqcn>` 한 묶음. `clean`/`cleanTest`/전체 `build`·전체 `:test` 금지(사용자 명시 시만). 다른 세션이 같은 모듈 `:test` 중이면 기다리거나 다른 suite. 출력·캐시 분리. daemon 정책은 문서화 관례(`--no-daemon --console=plain`)로 통일 — 세션마다 다르게 난사 금지. | `build.gradle.kts` L24-29 (`AWX_SPLIT_BUILD_OUTPUTS`/`AWX_BUILD_HOST_ID` → `build/<hostId>/` 분리); `scripts/awx_host_runtime.py` (동일 env 자동 주입); AGENTS §Evidence L310 (`build\desktop\...` boot proof, `scripts/verify_full_test_refresh.ps1`, same-host/cache `bootRun` 병렬 금지); AGENTS §Workspaces L249 (`GRADLE_USER_HOME`·`--project-cache-dir` host-local 격리); AGENTS `DEMO1-TOOLCHAIN-AUTO-SELECT` (`test --tests <Fqcn>`); `docs/operations/codex-goal-footer-the-one.txt` (no blanket `:test`); AGENTS `DEMO1-BUILD-PRUNE-RULE` (`build/` 삭제는 `scripts/prune_build_artifacts.ps1` 경유만); `scripts/run_verified_command.py --output <fresh dir>` (검증 출력 분리, task dir 아래) |
| R3 lease·파일 소유 | 제품 파일 편집 전 target-scoped lease. 남의 lease 파일은 패치 금지 → skip하고 비겹침 작업 계속 또는 유도 페이스트. | `__patch_drop__/source_edit_session.ps1` (`begin`/`verify`/`end` + `-TargetManifest`); `scripts/agent_scope_lease.py` (`who`/`check`/`claim`/`done`/`abort`/`reclaim`); `scripts/guarded_source_edit.js`, `scripts/devin_pre_edit_guard.ps1` (쓰기 시점 enforcement); AGENTS `DEMO1-LEASE-LIFECYCLE` + `DEMO1-DEVIN-MULTI-SESSION` (foreign live lease = skip that file, never steal; stale은 `lease_conflict_autoflow.py reclaim` quarantine만) |
| R4 git | `add -A`/`add .`/`commit -a`/`--no-verify`/push/pull/fetch/merge 금지. foreign staging 보존. 소유 경로만 선별 add → 스캔 → 로컬 커밋 1회. stale `index.lock`은 hand-delete가 아니라 스크립트 move-aside. | AGENTS `DEMO1-GIT-LOCAL-FIRST` (허용/금지 전표, enforcer `scripts/conditional_local_git.py`); AGENTS `DEMO1-VIBE-GIT-AUTO-CONTINUE` (stale 0-byte `index.lock` — `python -B scripts/conditional_local_git.py lock --repo . --backup-dir data/agent-handoff/<taskId>` → `index.lock.bak-<date>`, "move-aside, never delete", `agent_git_vibe_commit.py --stale-lock-days` 기본 0.25); 커밋 단일 진입 `python -B scripts/agent_git_vibe_commit.py --repo . --path <owned>... --message-file <file>` (preserve-foreign-staged 기본); `scripts/git_doctor.py --root .` (read-only 분류 — lock은 index write만 막지 worktree-edit 전체를 막지 않음) |
| R5 GPU·API 소모 | Ollama/임베딩/유료 API는 검증 필요 시만. 이미 green인 스모크 재실행 금지. 3090 공유 — 장시간 embedding 배치 겹침 주의. 실패 시 API fanout 금지, 분류+재시도≤1 후 라우팅 순서. | `.agents/skills/demo1-agent-api-spend-guard/SKILL.md` + `docs/AGENT_API_SPEND_GUARD.md` + `configs/agent-api-spend-guard.yaml` (`AWX_AGENT_SPEND_GUARD=1`); AGENTS `DEMO1-RTX3090-WATCH` (3090 primary, `scripts/gpu_power_fallback.py decide`, `AWX_AGENT_ALLOW_PAID_MODELS`); `.agents/skills/demo1-gpu-lane-evidence` (증거 체인 — GPU 작업 전 snapshot) |
| R6 애매한 빌드 | 실패를 제품 버그로 단정 전 체크리스트(아래 §조사짐-방지). 못 채우면 `HOLD_AMBIGUOUS_BUILD` 보고, 제품 패치 금지. | `scripts/debug_rag_stack.ps1 -Action verify` (8체크: runtime/ports/http/exceptions/config/**freshness=stale-candidate**/devwatch/shared-ollama, `Debug-RAG.bat`/`Debug-Meta-Display.bat`); AGENTS §Evidence L307-309 (`evidence_needed`, PASS는 실제 돌린 부분만, `pre-existing`은 동일실패 baseline 증거 필요, lane-local hold만); hard-constraints `awx.debug.verify.v2` (tool-ran ≠ target-verified ≠ build-ran ≠ fullVerification; `stale-candidate`=미확증 mtime 의심) |
| R7 역할 분리 | 아래 §역할 표. 플러그인/툴 레인은 작업 유형별로 최소만. | `.agents/skills/demo1-codex-plugin-roles/SKILL.md`; AGENTS `DEMO1-VIBE-MAX-AGENCY` (4-agent 공유 root/evidence/restart 계약); `.clinerules/00-demo1-cline-bridge.md` Runtime protection |

Lease/소유 상태 한 줄 확인 (진단·문서화용):

```powershell
python -B scripts/agent_scope_lease.py who
python -B scripts/work_journal.py list --active
powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__/source_edit_session.ps1 -Action status -Json
```

## 조사짐-방지 체크리스트 (R6, "애매한 빌드" 판정)

RED/빌드 실패/이상 200을 **제품 버그로 패치하기 전에** 순서대로 채운다. 하나라도
`not_observed`면 `HOLD_AMBIGUOUS_BUILD`로 보고하고 제품 파일을 건드리지 않는다.

1. **타 세션 간섭**: `agent_scope_lease.py who` + `work_journal.py list --active` +
   `source_edit_session.ps1 -Action status -Json` — 같은 모듈 `:test`/ForceRestart/
   편집 중인 세션이 있는가? 있으면 그 결과를 내 패치 증거로 쓰지 않는다.
2. **JVM freshness**: `Debug-RAG.bat -Action verify` → `freshness` 필드.
   `stale-candidate`(변경 클래스 mtime > JVM start)는 미확증 의심 — 재기동은 소유
   세션에만 요청하고 내가 남의 JVM을 죽이지 않는다. stale JVM의 200은 증거 아님.
3. **플랫폼 pre-failure 분리**: H2 DDL partial / 사전 존재하던 UI·구조 실패가 섞인
   RED인가? DDL 분류: `scripts/classify_h2_ddl_warnings.py` +
   `docs/diagnostics/h2-ddl-warnings-20260928.md`. 내 diff와 무관한 실패는 별도
   기록(분리 보고)이지 내 패치의 pass/fail에 합산하지 않는다.
4. **fixture/구조검사 STALE**: RED가 이번 변경과 무관한 fixture·구조 assertion의
   STALE인가? `pre-existing` 주장은 동일 실패의 baseline run 증거가 있을 때만
   (AGENTS §Evidence L308) — 없으면 `evidence_needed`로 기록.
5. **빌드 산출물 오염**: `NoClassDefFoundError` 스톰 + 클래스 존재 →
   `scripts/verify_full_test_refresh.ps1` 경로(공유 `build/`·캐시 오염 의심,
   `AWX_SPLIT_BUILD_OUTPUTS` 미적용 세션이 끼어든 흔적 포함).
6. **판정 분리 보고**: "tool ran" / "target verified" / "build ran" /
   "fullVerification"을 분리해서 적는다(`awx.debug.verify.v2`). 401/403 =
   `auth-blocked`(DOWN 아님). 미실행 필수 체크는 `run=skipped|blocked|
   not_observed`로 남기고 pass 카운트에서 제외.

## 역할 분리 (권장 기본값 — 실제 lease/소유가 이기는 규칙을 덮지 않음)

| 역할 | 해도 됨 | 하지 마 |
|---|---|---|
| Codex (제품) | focused test + 최소 패치 + (소유 시) ForceRestart/DevWatch | clean, 전체 suite, 남의 lease 파일, `add -A` |
| Devin (어시스트) | 진단 문서, 재현 패킷, lease/저널 상태 읽기, 유도 페이스트 | 제품 핫파일 동시 편집, ForceRestart, 남의 JVM kill |
| Grok | 지시서·채점·PASTE·리뷰 | 불필요한 live kill, 유료 fanout |
| Clean | 훅/홈 위생, allowlist cleanup | demo-1 제품 소스 |

## 금지 (이 카드 범위에서)

- 이 지시로 제품 기능 리팩터/Java·JS 소스 수정을 섞지 않는다.
- H2 drop/repair, remote 추가·변경, 비밀 값 출력 금지.
- `demo.auth.proto-open` 끄기 / admin harden 변경 금지.
- "빌드 안정화" 명목의 전역 `clean` + 전체 `:test` 금지.

## Done 기준 (이 태스크)

1. 이 문서 존재 + 위 표의 SSOT 경로가 이 체크아웃에서 확인됨(사실 표기).
2. `[MULTI-SESSION]` 헤더 블록이 복사 가능하게 문서 상단에 있음.
3. lease 상태 확인 커맨드 포함.
4. 제품 소스 diff = 0.
