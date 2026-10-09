---
name: demo1-nightly-review-assist-20261008
description: Read-only pin, coverage, lease-overlap, keep-features guard, scheduler static check, HOLD ledger, env-presence, verify-plan, agy stream-json mock, and selftest for the Codex brief CODEX-SESSIONS-ANTIGRAVITY-NIGHTLY-20261008 (Codex session nightly review via Antigravity, report-only). Product/batch implementation files stay with Codex.
---

# Codex nightly-review assist (2026-10-08)

## When

Codex is implementing `CODEX-SESSIONS-ANTIGRAVITY-NIGHTLY-20261008`
(`codex-nightly-review-cf2492b4`, owns 4 files: `scripts/codex_nightly_review.py`,
`scripts/test_codex_nightly_review.py`, `configs/codex-nightly-review.example.json`,
`docs/agents-rules/DEMO1-CODEX-NIGHTLY-REVIEW.md`) and the assist side needs
anchor pins, named coverage, lease-overlap scope check, the user's
keep-existing-features gate on diffs, a scheduler-registration static check
(logon trigger + 30-minute catch-up delay), the WP0 HOLD ledger, an
assumed-format agy stream-json mock for verdict tests, or the ordered
A1-A10 acceptance plan.

## SSOT

`var/codex-assist-nightly-20261008/README.md` (verified live-tree facts vs
assumptions, Codex-owned file list, user constraint line verbatim,
command card)

## User constraint (attach verbatim to the implementation)

기존 기능 유지 조건: --model은 배치 명령에만 쓰고 agy 전역 설정·모델 자동
추적·G1 크레딧·초과 설정은 읽기만, 격리는 배치 전용 폴더로만, AGENTS.md
문구는 '야간 배치 한정' 5줄 이하, 로그온 보충은 30분 지연.

## Check

```
python -B scripts/nightly_review_assist_20261008.py pin --root .
python -B scripts/nightly_review_assist_20261008.py cover --root .
python -B scripts/nightly_review_assist_20261008.py scope --root .
python -B scripts/nightly_review_assist_20261008.py diff-forbid --root . --diff <d>
python -B scripts/nightly_review_assist_20261008.py guard --diff <d>
python -B scripts/nightly_review_assist_20261008.py sched --file <ps1|xml|json>
python -B scripts/nightly_review_assist_20261008.py hold --root .
python -B scripts/nightly_review_assist_20261008.py env-presence --root .
python -B scripts/nightly_review_assist_20261008.py verify-plan --root .
python -B scripts/nightly_review_assist_20261008.py agy-mock --mode ok|exit0_fail|wrong_schema|auth_required|quota|permission_denied
python -B scripts/nightly_review_assist_20261008.py selftest --root .
```

`guard` enforces the user constraint on added diff lines: `--model` only in
nightly-named/brief-owned files, agy global keys (`useG1Credits`,
credit/overage, model auto-tracking) never written, `AGENTS.md` additions
≤5 lines and the added block must carry a nightly/batch scope word,
no Codex/OpenAI fallback call, no `--dangerously-skip-permissions`, no GUI
automation, no writes under `main/`, gradle files, `api-routing.yaml`,
`.codex/`, `.antigravity/`, or global `settings.json`/`config.toml`.

`sched` requires: logon trigger, 30-minute catch-up delay
(`PT30M`/`Minutes 30`/`delayMinutes: 30`), `StartWhenAvailable`, `IgnoreNew`.
Forbidden: `BootTrigger`/`-AtStartup`, `RunLevel Highest`, `SYSTEM`, stored
password, dangerous-skip. WakeToRun and agy sidecar double-scheduling are
REVIEW signals (need evidence), not auto-fail.

## Do not

1. Edit the 4 Codex-owned files, product code, or existing skills from this
   assist lane — they stay with the Codex session.
2. Treat exit 0 (or the agy-mock) as a real agy call, a real model verdict,
   or proof the batch works. `agy-mock` emits an ASSUMED stream-json shape;
   re-verify against installed `agy --help`/docs before trusting a parser.
3. Register a scheduled task, call `agy`, read `.env`/secret values, or
   write outside the assist pack — env-presence emits booleans only.
4. Force-release a lease reported by `scope` as OVERLAP; wait or coordinate.
5. Count `chat_session_debug_export.py` as a Codex-session input — it reads
   product `chat-session-traces` only. The Codex-session reuse candidate is
   `scripts/codex_session_friction.py` (verify fit before wiring).
6. Open the WP0 HOLD items in `hold.json` as resolved without real evidence
   (path/version/quota/time confirmed).
7. Ship `prod`/public writes: no auto-apply, PR, push, deploy, server
   restart, or skill auto-install — the brief is report-only.
