<!-- moved-from: AGENTS.md L215-L218 sha256=d2283a62e0e02ce65d126fb9791dfe61e7c7c255a3239972dacaaf60548ffd20 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-AGENT-PORT-LEASE -->
## Agent dynamic port lease
- Parallel agent servers lease a free port through `scripts/agent_port_lease.py` (`Agent-Port.bat`). Contract: `port.acquire → process.start → health.check → debug.trace → verify → process.stop → port.release`. `--owner`/`--session` required; stop/release touch only that owner/session's pid and lease. Meta Display ports 18180-18182 stay on the Start/Close/Debug BATs. Skill: `$demo1-agent-port-lease`.
<!-- END DEMO1-AGENT-PORT-LEASE -->
