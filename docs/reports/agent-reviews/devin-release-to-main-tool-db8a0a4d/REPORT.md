# release_to_main — codex/owned-runtime-browser-restart → main

Status: **HOLD** — 도구는 완성·검증됐고 apply는 정책 준수 경로로 1회 실행됐으나, 저장소 자체 pre-push 가드가 두 부모 스냅샷 방식의 main push를 구조적으로 차단. main은 `b2eaba46` 그대로.
Date: 2026-10-09 KST
Task: devin-a39f5920 / ledger devin-release-to-main-tool-db8a0a4d

## 결과 한 줄
- `scripts/release_to_main.py` + `scripts/test_release_to_main.py` 구현, 오프라인 테스트 **13/13 PASS** (실제 origin 접속 0회).
- 실제 apply 2회 실행. 최종 apply(계약 수정 후): snapshot `98b9b3a7e919c3429cb8ae8cd987684a64a27a0f` 생성(parents=[b2eaba46, d2d62e7d], tree=RC tree, diff_rc=0 확인) → main push 거부(hook).
- 원격 확인(git ls-remote, 13:44 KST): `main=b2eaba46` (불변), `refs/tags/archive/main-20260923-b2eaba46=b2eaba46` (존재 — 동시 세션이 push), `refs/heads/archive/main-old` 없음, `codex/owned-runtime-browser-restart=d2d62e7d`.
- force 계열 push 횟수 = **0** (release_log.json 전체 명령 기록으로 증명).

## W0 baseline (2026-10-09 ~11:0x KST)
| item | value |
|---|---|
| origin | https://github.com/UnlimitedAbandonWare/AbandonWareAi |
| branch | codex/owned-runtime-browser-restart |
| HEAD at W0 | 6a8d0cd2 |
| origin/main | b2eaba4679f70ded860b052faa59b29073d0c859 |
| origin/codex | a3754a3f (local 3 ahead at W0) |
| uncommitted | 104 entries (excluded from release) |

## External drift during session
- 11:28 auto-commit `62c83392` → pushed to codex (remote moved).
- 12:27 auto-commit `d2d62e7d` → pushed to codex later (remote now d2d62e7d). Included parallel session's `git_ship`-based release-main impl + swept my `release_log.json`.
- Remote archive **tag** appeared between 02:48–04:2x UTC — pushed by a concurrent session (my apply found it via ls-remote "already-exists"; earlier push at 02:48 had failed rc=1).
- Concurrent leases observed: devin-codex-merge-main-push-90ccd2ba, devin-main-merge-resume-630a6dfd.

## Gates (plan/apply on final RC d2d62e7d)
| gate | result | evidence |
|---|---|---|
| G1 origin/main pinned | pass | b2eaba46 == expect |
| G2 secret scan (tree+outgoing diff) | pass | placeholders only (synthetic/runtime-call `secrets.token_hex(...)`); classifier widened + regression tests |
| G3 untracked-ish warns | warn | build/var paths in RC tree |
| G4 compileJava | pass | tmp archive extract, BUILD SUCCESSFUL (rc=62c83392時点; apply도 동일 게이트 통과 후 push 단계 도달) |
| G5 author/commit hygiene | warn | - |

## Pre-push policy (root cause of blocks)
`.githooks/pre-push` → `git_secret_guard.ps1` + `git_publish_review.py`.
Contract: `AWX_PUBLISH_APPROVED` env + `publish.allowTarget` match + per-push
`publish.allowRef` (one-shot `-c`, same as git_ship.py) + single-ref fast-forward +
clean tree/history scans.

- **New remote ref → NEVER pass**: remote_oid=0 → no base → historyScan incomplete
  → verdict UNKNOWN → exit 3. `archive/main-old` branch push는 이 이유로 차단(281s).
- **Two-parent snapshot → NEVER pass**: range `b2eaba46..98b9b3a7` includes 453 codex
  commits; history scan found **51 findings** incl. path-rules
  (`credential-path`/`private-profile`/`private-or-generated-path`) that bypass the
  allowlist by design → verdict BLOCKED(reason=history-secret-or-truncated) →
  main push rejected after ~17min scan.
- **Single-parent snapshot probe → CLEAN**: `15db1857` (parent=b2eaba46만, tree=RC tree)
  로컬 hook 시뮬레이션: git_secret_guard findings=0, publish_review verdict=CLEAN
  exit=0. RC 트리 자체는 findings=0(51 hits 전부 allowlisted). 단, 승인된 설계(2 parents)
  와 달라 자동 실행하지 않음 → ASK_ONCE.

## Push results (apply #2, 04:2x UTC)
| ref | old → new | result |
|---|---|---|
| refs/tags/archive/main-20260923-b2eaba46 | — → b2eaba46 | skipped(already-exists on remote; 외부 세션이 push함) |
| refs/heads/archive/main-old | — → b2eaba46 | blocked-nonfatal (new-ref → hook UNKNOWN) |
| refs/heads/codex/... | → d2d62e7d | skipped(already-up-to-date) |
| refs/heads/main | b2eaba46 → 98b9b3a7 | **rejected** (hook BLOCKED: history-secret-or-truncated) |

## Old main preservation (current state)
- Remote tag `archive/main-20260923-b2eaba46` → b2eaba46 ✓
- Local tag + local branch `archive/main-old` → b2eaba46 ✓
- Snapshot 98b9b3a7 first-parent → b2eaba46 (로컬 객체, 미push) ✓

## Tests
`python -B -m unittest scripts.test_release_to_main -v` → **13/13 OK** (~42s).
All use temp bare repos; no real origin access (test asserts remote is local path).

## Acceptance scorecard
- A1 tests: PASS 13/13, origin 접속 0회
- A2 gate table: done (위 표)
- A3 remote archive: tag ✓ (외부 세션 경유), branch ✗ (구조 차단) → PARTIAL
- A4 remote main == snapshot: ✗ (push 거부) → FAIL/BLOCKED
- A5 force push 0회: PASS (명령 로그 증명)
- A6 보호 파일 쓰기 0회: PASS (수정 = scripts/release_to_main.py, test_, decode_finding_paths.py, report/ — 전부 허용 경로)
- A7 NOT_INCLUDED: 104 uncommitted 항목 릴리스 미포함

## ASK_ONCE (재개 조건)
1. main 반영 방법 선택:
   - **A) 1-parent 스냅샷으로 apply 재실행** — 로컬 hook 시뮬레이션 CLEAN 확인済. codex 이력은 main 조상이 아니지만 원격 codex 브랜치(d2d62e7d)에 보존. 승인된 2-parent 설계와 다른 점에 대한 승인 필요. (도구에 --single-parent 폴백 모드 추가 후 즉시 실행 가능)
   - **B) 가드 정책 조정** — configs/git-guard-allow.json 또는 hook 규칙 변경(정책 소유자 영역; 내 권한 밖)
   - **C) GitHub 웹에서 수동 merge** — 로컬 훅 우회가 아니라 원격측 절차
   - **D) HOLD 유지** — 2-parent만 허용
2. 미커밋 104개 중 이번에 같이 올릴 것: A 다음 자동 커밋 뒤 재실행 / B 지금 이대로
3. 릴리스 태그 v2026.10.13: A 10/13에 / B 나중

## Artifacts
- Tool: `scripts/release_to_main.py`, `scripts/test_release_to_main.py`
- Log: `docs/reports/agent-reviews/devin-release-to-main-tool-db8a0a4d/release_log.json` (invocations: plan×3 + apply×2)
- Evidence: handoff dir `data/agent-handoff/codex-autonomy/devin-a39f5920/` (decode_finding_paths.py, rc_review.json 등)
- Probe commit 15db1857 — 로컬 미참조 객체(시뮬레이션 전용, push 안 함)
