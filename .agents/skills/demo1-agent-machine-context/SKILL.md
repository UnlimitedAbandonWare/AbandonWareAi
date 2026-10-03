---
name: demo1-agent-machine-context
description: Use when Devin/Grok/Codex needs Project Root, install paths, env names, tool locations, or DB lane status in one shot.
---

# demo1-agent-machine-context

One-shot machine context for any agent. SSOT is `scripts/agent_machine_context.py`;
the `.ps1` and `Agent-MachineContext.bat` are thin wrappers with the same single-JSON
contract. Contract: `DEMO1-DEVIN-AGENT-MACHINE-CONTEXT-DX-20260928`.

## Entry (always from Project Root)

```
python -B scripts/agent_machine_context.py [--pretty] [--section all|paths|env|tools|db|gpu|git|skills]
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/agent_machine_context.ps1 [-Section tools] [-Pretty]
scripts\Agent-MachineContext.bat   (double-click; --pretty default, pauses unless AWX_RAG_NO_PAUSE)
```

stdout is exactly one JSON document (`awx.agent-machine-context.v1`) — parse it,
never grep it, and never guess paths it did not return. Exit 0 = report emitted;
per-section probe failures are fields (`error`/`evidence_needed`), not exit codes.

## What it reports

- `paths` — project root, Start-RAG.bat, gradlew, var/meta-display-db, agent-handoff, skillsRoot, Downloads hint
- `env` — interesting env **names** + `secretLikeNamesPresent` (names only); `secretValues` is always `REDACTED_POLICY`
- `tools` — git (F:\git preferred), java (+is17, JAVA_HOME), python, node, ollama, gradle-wrapper flag, nvidia-smi
- `db` — lane-A file-H2 via `db_agent.py status` (`locked`/exit 3 is a soft answer: the Start-RAG JVM holds lmsdb.mv.db; live lane may still be reachable; never kill the server), lane-B `explicitOnly`, cheatsheet path
- `gpu` — nvidia-smi index/name/uuid/memory summary (pin by UUID; index 0 = 3060, 1 = 3090 on DESKTOP-M5NOV6K)
- `git` — preferred path/version + branch/HEAD probe, `gitPolicy` (`noPushDefault`, conditional-local-Git pointer)
- `skillsIndex` — skills dir count, router + index presence
- `journals` (section `all` only) — `work_journal list --active` taskIds

## Rules

1. Trust the JSON only — an absent field means `evidence_needed`, not "does not exist".
2. DB **writes** are not this tool's job: delegate to `$demo1-db-agent-cli`
   (`apply --dry-run` then `--i-mean-it`/`--allow-tables`, `upsert-admin`).
3. File writes stay under the existing lease + checkpoint lane (`$demo1-work-ledger`).
   The script itself is read-only; the only write is `--write-report <path>`
   (confined to the project root; canonical report:
   `docs/diagnostics/agent-machine-context-0928/last.json`).
4. cp949 consoles are safe: the script is ASCII-only and `ensure_ascii` output.

## Router registration (intent)

Registered in `.agents/skills-intent-index.yaml` as intent `agent-machine-context`
(added 2026-09-28, task `agent-machine-context-dx-0928-64e6c087`):

```yaml
  - intent: agent-machine-context
    match:
      - agent machine context
      - agent_machine_context
      - machine context
      - 머신 컨텍스트
      - 환경변수
      - 설치경로
      - 설치 경로
      - db status
      - 머신 정보
    primary_skill: demo1-agent-machine-context
    optional_skill: demo1-db-agent-cli
    forbid_families: []
    notes: 원샷 머신 컨텍스트 JSON 진입 (scripts/agent_machine_context.py); env는 이름만, DB 쓰기는 db-agent-cli 위임
```

Reading this doc is intake, not Done — `$demo1-codex-goal-intake-continue`.
