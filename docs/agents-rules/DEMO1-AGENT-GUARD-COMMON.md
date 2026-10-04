<!-- moved-from: AGENTS.md L58-L66 sha256=61fe60ffc21c4f97abbd03a4375481e381d6c43aaad9caf04c6b0abb6f14b84f movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-AGENT-GUARD-COMMON -->
## Common Guard Entry Points (Codex / Grok / Devin / Cline)
- Task entry: `python -B scripts/agent_preflight.py --root .` (MCP `guard_status`). Skill routing: `demo1_vibe_skill_router.py resolve "<ask>"` -> `.agents/skills-intent-index.yaml` (DEMO1-VIBE-SKILL-ROUTER). Path/retry brake: `agent_work_guard.py check|status`.
- Bounded write/recovery: `codex_work_checkpoint.py apply|restore --run <cycle>` refuses unless the target still equals the recorded preimage/postimage — a mid-work foreign change is a conflict, never a silent overwrite.
- Recording/handoff: `status_doc.py --expect-sha256`, `run_verified_command.py` (unconfirmed run is never a pass), `work_journal.py handoff`, `awx_session_evidence.py`, `meta_display_db_export.py` (`$demo1-meta-display-db-export`; never live-JDBC the H2 file while the JVM holds it), `codex_home_quarantine.py` hash-bound apply. Watch/cleanup: `agent_session_watch.py` / `Watch-Agents.bat` (`agent-session-watchdog`); `Safe-Cleanup.bat` WhatIf-first (`$demo1-safe-cleanup`); copy-residue plan/apply/restore `scripts/copy_residue_cleanup.py` (`$demo1-copy-residue-cleanup`).
- Conditional local Git (this root only, user authorization 2026-09-23): `$demo1-conditional-local-git` / `$demo1-git-secret-guard` / `$demo1-git-vibe-workflow`; once an agent judges owned work committable, the single entry point is `python -B scripts/agent_git_vibe_commit.py --repo . --path <owned>... --message-file <file>` (JSON `committed=<sha>`/`deferred=<reason>`) — allow/forbid lines in `DEMO1-GIT-LOCAL-FIRST`.
- Hooks are advisory detection only; the checkpoint apply/restore path is the enforcement. A hook or MCP failure must stay visible and the guarded path still refuses when safety is unproven.
- A guard/scanner false positive (e.g. checkpoint secret-scan flagging a Java local variable) is fixed in the scanner with its regression test kept (`scripts/test_codex_work_checkpoint_source_expressions.py`, `test_checkpoint_java_call_args.py`) — never evaded by renaming or mangling source semantics.

### 보호 범위 해석 (protected scope interpretation)

- (1) 보호 대상은 지시서의 "변경 금지(보호 대상)" 줄이나 Acceptance 괄호에 이름이 적힌 것뿐이다. 열거되지 않은 것은 보호 대상이 아니며, 세션이 스스로 만든 해시 스냅샷은 보호 범위를 정하지 않는다.
- (2) "파일 X의 Y 변경 금지"는 Y만 보호한다. 파일 X 전체로 넓히지 않는다.
- (3) 명시 허용(예: 스캐너 오탐 수정, R21)과 열거 없는 "보호 파일" 문구가 겹치면 명시 허용이 이긴다. checkpoint를 남기고 진행하며, ledger에 `SCOPE_INTERPRETATION: <문구> → <해석> (근거 file:line)` 한 줄을 남긴다.
- (4) ASK_ONCE는 둘 다 명시적으로 열거된 두 줄이 정면으로 충돌할 때만 쓴다. 이때도 질문 1개 + 기본 답 1개를 FOR_CODEX.md에 쓰고, 같은 질문으로 다시 감사하지 않는다.
- (5) 허용 목록 적용(`configs\git-guard-allow.json`의 {path, rule, oid, reason}이 정확히 일치하는 항목을 허용)은 가드 완화가 아니다. 완화 = 탐지 regex·skip 목록·차단 경로 변경, 검토 없이 허용 항목 추가, `--no-verify`·훅 끄기. "fixture가 진짜 무효인지 증명 못 함"은 HOLD 사유가 아니다(oid 검토가 그 증명이다).
<!-- END DEMO1-AGENT-GUARD-COMMON -->
