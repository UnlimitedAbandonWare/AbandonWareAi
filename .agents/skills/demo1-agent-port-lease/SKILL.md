---
name: demo1-agent-port-lease
description: Use when agents must lease a free port, run an owned server, and release it.
---

# demo1-agent-port-lease

Parallel agents borrow a loopback port, start a server, and return both.
The engine is `scripts/agent_port_lease.py`. `Agent-Port.bat` and
`scripts/agent_port_lease.ps1` call that engine. Records live under
`var/agent-port-lease/` (leases, `traces.jsonl`, logs). That directory is
runtime state, not source.

## Contract

`port.acquire` → `process.start` → `health.check` → `debug.trace` → `verify` → `process.stop` → `port.release`

`run` performs the whole chain. `--keep` stops after a healthy verify and
leaves the lease open. `close` is stop then release.

```powershell
python -B scripts/agent_port_lease.py run --owner grok-1 --session s1 --service demo -- python -u -c "..."
python -B scripts/agent_port_lease.py close --owner grok-1 --session s1 --lease apl-...
python -B scripts/agent_port_lease.py diagnose around --owner grok-1 --session s1 --trace-id trc-...
Agent-Port.bat status --owner grok-1 --session s1
```

PowerShell: `scripts\agent_port_lease.ps1 -Action run -Owner grok-1 -Session s1 -ServerArg python,-u,-c,"..."`.

`--owner` and `--session` are required. Ids match `[A-Za-z0-9][A-Za-z0-9._:-]{0,79}`.
Command argv is a list after `--`, with `{port}` `{lease}` `{owner}` `{session}` replaced.
The child receives `AWX_AGENT_PORT`, `AWX_AGENT_LEASE`, `AWX_AGENT_OWNER`, `AWX_AGENT_SESSION`.
Health URLs must be `http://127.0.0.1:{port}/...` or `localhost` on that same port.

## What this tool will not stop

- A pid whose create-time does not match the lease (`pid-reused`, `identity-unproven`).
- Another owner or session (`owner-mismatch`).
- A listener found only by port scan (`occupied-foreign` is skipped, not killed).
- Meta Display / RAG ports **18180, 18181, 18182**. Those stay on `Start-RAG.bat`, `Close-RAG.bat`, `Debug-RAG.bat`, `Debug-Meta-Display.bat`, and `Debug-Session.bat`.

There is no kill-by-port and no kill-by-image-name switch.

## Failure record and retry

`run` classifies `port-conflict`, `occupied-foreign`, `start-failed`, `http-unreachable`, `http-failed`, and `timeout`, writes a trace, stops the process it started, releases that lease, and tries another port until `--attempts` (1-8, default 3).

Each trace row (`awx.agent-port-trace.v1`) has `traceId`, `leaseId`, `owner`, `session`, `service`, `port`, `pid`, `startedAt`, `endedAt`, `endStatus`, `httpStatus`, `exceptionType`, `cause`, `phase`, `attempt`. Read them back with `trace query` or `diagnose trace`.

## Diagnose

| action | result |
|---|---|
| `diagnose tail --lease ID --follow-seconds N` | owned log, optional bounded follow |
| `diagnose trace --trace-id ID` | one request row |
| `diagnose around --trace-id ID` | row plus log bytes around the recorded offsets |

`trace add` records a request the agent observed while the lease is open.

## Exit codes

`0` ok, `2` usage, `3` refused (protected port, owner mismatch, pid reuse, busy explicit port), `4` health or verify failed after the attempt budget. Stdout is one JSON object. Assignment-shaped credential fragments in stored argv and log excerpts are redacted.

## Limits

Default scan range is `25000-25999` (`--range LO-HI`, span at most 2000). A lease expires (default 1800s); `heartbeat` extends it; `reap` closes only this owner/session's expired leases and still refuses a reused pid. `status` is read-only. This does not replace the work-ledger lease used for source edits.
