# db_agent.py agent cheat-sheet — status/tables/schema/query/apply --dry-run, one-line JSON, lock=exit 3
- card-id: db-agent-cheatsheet-d33b
- kind: checklist
- status: still-true
- date: 2026-09-28 KST
- evidence: docs/diagnostics/db-agent-tool-20260928/01_AGENT_CHEATSHEET.md
- reverify: `python -B scripts/db_agent.py status`

## 근거
로컬 H2(lmsdb)를 서버 없이 읽는 CLI. `status` / `tables [--with-counts]` / `schema --table <t>`
/ `query --sql|--file` / `get-user --username` / 쓰기는 명시적 `apply --dry-run` 후 apply.
JVM이 lmsdb 잠금 중이면 exit 3 → 읽기전용 fallback만. 플래그는 서브커맨드 뒤에.
