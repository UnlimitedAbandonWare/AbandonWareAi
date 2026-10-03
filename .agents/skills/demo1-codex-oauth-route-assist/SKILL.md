---
name: demo1-codex-oauth-route-assist
description: >-
  Use when assisting Codex on OAuth routing, terminal metadata, or credit-benefit
  work. Prepares read-only anchors, loopback mock samples, config wording maps,
  and a dry-run focused verifier. Product Java stays Codex-owned. A tool PASS is
  not an application completion.
---

# demo1-codex-oauth-route-assist

Assist rail for Codex OAuth complex-route work (R5, 2026-10-01) and the same
seams later: terminal metadata, ChatGPT OAuth exact route, and credit-benefit
wording. This skill does not patch `main/java`, `main/resources`, `src/test`,
or `chat.js`.

## When

Use it when the ask is to prepare or re-check Codex assist material for:

- OAuth routing or `chatgpt-oauth:`
- `LlmResponseTerminalException` metadata
- credit / benefit wording that must stay separate from Jev / Vercel AI Gateway

Skip it for product edits. Those belong to the Codex session that holds the lease.

## Scripts

Run from `<repo>`.

```powershell
python -B scripts/codex_anchor_map.py --root . --out-dir var/codex-assist-20261001 --probe-json var/codex-assist-20261001/engine-slots-probe.json
python -B scripts/codex_config_consumer_map.py --root . --out var/codex-assist-20261001/config-consumers.md
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/codex_mock_sse.ps1 -Action Sample -Root .
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/codex_r5_verify.ps1 -Wp All -DryRun -Root .
```

`codex_r5_verify.ps1` stays a dry-run unless `-Execute` is passed. Do not pass
`-Execute` while another agent is running Gradle. There is no full-suite switch
and no clean switch.

`codex_mock_sse.ps1 -Action Stop` stops only the pid in
`var/codex-assist-20261001/mock-sse.pid.json`, and only when that process
command line is `engine_slots.py mock-server`. It binds through the kit, which
listens on `127.0.0.1`.

## Outputs

Kit extract, probe, self-test, anchor map, staged tests, mock samples, throttle
repro, and the verify JSON live under `var/codex-assist-20261001/`. Re-runs add
`-001` instead of overwriting an existing output name.

The engine-slots kit used for the 2026-10-01 pass is
`var/codex-assist-20261001/kits/engine-slots/CODEX_ENGINE_SLOTS_R5/`. It is not
copied into `tools/`.

## verify-events

After Codex exports observation JSONL from a real focused test:

```powershell
python -B var/codex-assist-20261001/kits/engine-slots/CODEX_ENGINE_SLOTS_R5/tools/engine_slots.py verify-events --events <jsonl> --minimum APP_MOCK --out var/codex-assist-20261001/verify-events.json
```

`fixtures/observations.synthetic.jsonl` with `--minimum SYNTHETIC` is a format
drill. It is not application verification.

## Do not

- Edit product source or product tests. Copy staged tests only inside the Codex lease.
- Run `scripts/chatgpt_oauth_flow.py`.
- Call OpenAI, Vercel, Gemini, or any other authenticated or paid API. This rail is 0 calls.
- Read `.secrets` or `.env`. Do not print tokens or key values.
- `git add`, commit, push, pull, reset, or clean.
- Force-release another session's lease.
- Treat engine-slots self-test, probe, mock samples, or a dry-run verifier as a green product.

## Tool PASS is not app done

`check-package` and `self-test` prove the kit files and the Python tests inside
the kit. `probe` proves selected file hashes. The mock proves a loopback
fixture. None of those boot Spring or prove an OAuth answer.
