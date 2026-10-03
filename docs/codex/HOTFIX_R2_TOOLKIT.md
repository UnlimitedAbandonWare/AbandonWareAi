# Hotfix R2 Toolkit — command index for Codex

Scope: judgement tooling for `CODEX_DEVIN_RUNTIME_HOTFIX_R2_20260930.md` (WP1–WP5)
and `CODEX_DEVIN_JEV_BODY_DEADLINE_20260930.md`. Toolkit only — it never edits
product source. Root for all commands: `C:\AbandonWare\demo-1\demo-1\src`.
Every script accepts `--root <tree>` so a Codex worktree can probe any tree
without writing to it. Skill: `$demo1-codex-tool-router`.

| Step | One tool | Command |
|---|---|---|
| 착수 / baseline | `scripts/baseline_sync_probe.py` | `python -B C:\AbandonWare\demo-1\demo-1\src\scripts\baseline_sync_probe.py --root <tree> --expect-head 4150b2822f2c --expect-branch codex/owned-runtime-browser-restart` → `MATCH/DIVERGED/DETACHED_OTHER` JSON+summary. On mismatch: **stop, report, no checkout/reset/fetch.** |
| 앵커 번역 | `scripts/zip_path_to_sourceset.py` | `python -B ...\zip_path_to_sourceset.py --root <tree> --directive <md> --zip C:\Users\nninn\Downloads\mfwasainx.zip --out anchor_map.json` → live path/line/drift/module/test-task; `ANCHOR_MISSING` = do not guess. |
| RED/GREEN 판정 | `scripts\gradle_truth_gate.ps1` (+ `scripts/test_xml_evidence.py`) | `powershell -NoProfile -ExecutionPolicy Bypass -File ...\gradle_truth_gate.ps1 -Root <tree> -GradleArgs "test --tests <fqcn> --rerun-tasks"` → enum `GREEN/RED/NO_TESTS/BASELINE_BLOCKED/WRAPPER_EXIT_LIE`. `--tests` required; `clean`/whole-suite refused. `-Repeat 20` for stability runs; `-SanitizedLogOut <path>` feeds WP5. |
| 웹 재현 | `scripts/web_repro_matrix.py` | `python -B ...\web_repro_matrix.py --port 18180` → 5-item table into `var/web-repro/<ts>/`; refused connection = `SERVER_DOWN` (do not start the server). Items 3–5 are `OBSERVE_PROTO_OPEN` — record, never FAIL. |
| 로그 정제→AWX | `scripts/log_redact.py` | `python -B ...\log_redact.py --in <raw.log> --out <san.log> --spine-only` then `python -B C:\AbandonWare\demo-1\demo-1\src\tools\build_error_miner.py scan --in <san.log> --out <pfx>`. Only while primary AWX is healthy; no recovery-AWX lane. |
| 수정 후 반박 | glm_worker | Send only sanitized diff + gate verdict — template `docs/codex/glm-rebuttal-review-template.md` (3 fixed questions). |

## Baseline note (observed 2026-09-30)

Canonical root = `MATCH` (HEAD `4150b2822f2c`, branch `codex/owned-runtime-browser-restart`).
Codex worktree `C:\Users\nninn\.codex\worktrees\c1b2\src` = `DETACHED_OTHER`
(HEAD `b6ec55d1`, `JevGatewayClient`/`JevEvaluationRuntime`/`chat.js` absent).
Confirm the intended tree before applying R2 changes.

## Stop conditions (report in place, do not pull the next tool)

`BASELINE_MISMATCH` · `BASELINE_BLOCKED` · `SERVER_DOWN` · Vercel `auth-blocked`.
A nonzero diagnostic verdict is a report, not a crash: emit the JSON + one-line
verdict and stop.
