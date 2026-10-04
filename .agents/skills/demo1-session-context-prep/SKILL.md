---
name: demo1-session-context-prep
description: "use this when preparing offline session-jsonl fixtures, leak checks, or task-context contract evidence for the owning Codex session."
---

# Session context preparation

- Fixture (new var tree only): `python -B scripts/chat_trace_fixture_synth.py --seed 7 --out var/codex-assist-session-context/fixture-seed7`
- Leak scan (read-only, hashed names): `python -B scripts/chat_export_leak_scan.py --export-root var/debug/chat-session-traces/export`
- Handoff map (read-only sources): `python -B scripts/handoff_contract_probe.py --out var/codex-assist-session-context/contract-map.json`
- Result review (exact final report, one attempt): `python -B scripts/session_jsonl_result_review.py --run --ledger <exact-ledger> --report <exact-final-report>`
- Each tool supports `--help` and `--self-test`; fixture `--bench 1|10` is opt-in.
- Artifacts: `var/codex-assist-session-context/`; prep ledger: `data/agent-handoff/codex-sol-prep-73e61c19/`.
- astra/task-context target files are read-only here; do not edit them.
- No external API, daemon, watcher, HTTP server, DB migration, Gradle, or full test suite.
- Benchmark sizes are synthetic approximations of the supplied statistics; no performance/cold-cache verdict.
