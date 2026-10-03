# aw-dev quickstart (local dev dispatcher)

`aw-dev` is a single entry point that **delegates to existing repo tools**
(`scripts/run_verified_command.py`, `agent_preflight.py`, `git_doctor.py`,
`junit_owned_summary.py`, the Jev offline suite). It reimplements none of
them. Offline/mock is the default; no live or paid calls exist in the
registry.

## Invocation

```bat
:: CMD (codepage-safe; works from any directory)
C:\AbandonWare\demo-1\demo-1\src\aw-dev.cmd <action> [options]

:: PowerShell 5.1+ (if ExecutionPolicy blocks it, use aw-dev.cmd;
:: do not relax the policy)
powershell -NoProfile -ExecutionPolicy Bypass -File C:\AbandonWare\demo-1\demo-1\src\aw-dev.ps1 <action> [options]
```

Interpreter resolution order: `AWX_PYTHON` → `var/local_dev/bindings.local.json`
→ `%USERPROFILE%\.local\bin\python3.11.exe` (uv) → `PATH`. `python3` is the
WindowsApps stub and is never used.

## Standard flow

```bat
aw-dev.cmd doctor                        :: env check, exit 0/10
aw-dev.cmd bootstrap --plan              :: read-only plan + planHash
aw-dev.cmd bootstrap --apply --plan-sha <hash>   :: writes bindings.local.json only
aw-dev.cmd smoke --case aw-selftest      :: 34-case dispatcher self-test
aw-dev.cmd smoke --case jev-mock-smoke   :: loopback Jev mock, vercelCalls==0
aw-dev.cmd test --case jev-offline-selftest   :: 4 offline Jev suites
aw-dev.cmd test --case jev-campaign-offline   :: Grok G-1/3/4/7 suites
aw-dev.cmd report --last 10              :: runs table + ledger + pending cases
aw-dev.cmd handoff                       :: writes BUNDLE.json manifest
aw-dev.cmd status --run <runId>
aw-dev.cmd stop  --run <runId>           :: only aw-dev-owned runs
aw-dev.cmd list                          :: registry cases + availability
```

## Exit codes

| code | meaning |
|---|---|
| 0 | PASS / operation ok |
| 2 | usage or refused (bad/injected arg, unregistered case, foreign run) |
| 10 | NOT_RUN_ENV (required tool/file missing, or action on HOLD) |
| 11 | INCONCLUSIVE (0 tests, stale/missing XML, missing evidence) |
| 12 | PENDING_UPSTREAM (registered upstream tool file absent) |
| other | child exit code passed through (`origin:"child"` in result.json) |

`DEFERRED` is never `PASS`. A tool that merely ran is not a verified target.

## Where things live

- runs: `var/local_dev/runs/<runId>/result.json` + `verify/run.json` +
  `command.log` (per run; each step under `steps/<id>/` for multi-step cases)
- reports: `var/local_dev/reports/report-<stamp>.{json,md}`
- bindings: `var/local_dev/bindings.local.json` (written only by
  `bootstrap --apply`; never contains secrets — interpreter/root paths only)
- registry: `tools/local_dev/tool-registry.json` (all cases; unregistered
  args/cases are refused with exit 2)
