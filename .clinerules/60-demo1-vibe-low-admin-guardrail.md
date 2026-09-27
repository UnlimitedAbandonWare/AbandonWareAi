---
trigger: always_on
description: Vibe low-admin guardrail - under demo.auth.proto-open admin 200 (also after logout) is policy, logout-block is N/A-proto-open; no fail-closed gate, CSRF-off, proto-open=false, or Admin* deletion; Read-RAG-Debug before /admin/**
---

# demo1 vibe low admin guardrail (Always On when editing demo-1)

THE ONE = E (`agent-prompts/devin-vibe-admin-surface-20260927/RECOMMENDATION.md`): keep `demo.auth.proto-open` = true, keep every Admin route / guard / Admin\* Java / CSRF behavior as-is, and remove admin authentication from **vibe** success criteria. Policy SSOT stays AGENTS.md `DEMO1-PROTOTYPE-AUTH-LIGHT` + `docs/PROTOTYPE_AUTH.md`; this file is the Cline always-on echo, not a second policy.

- PROTO_OPEN: do not require admin login for operator/debug work.
- Do not treat logout-then-admin-200 as a bug; do not green "logout-block" under proto-open. A vibe Done/PASS never includes "admin login succeeded" or "admin blocked after logout" — that criterion is `N/A-proto-open`, neither pass nor fail.
- Do not add fail-closed admin gates, extra AdminToken checks, or CSRF-off "fixes" — unless the user explicitly says "harden".
- Prefer Read-RAG-Debug / LATEST.json over `/admin/**` as the debug entry: `Read-RAG-Debug.bat` → `var/rag-launcher/LATEST.json` (AGENTS.md `DEMO1-RAG-DEBUG-TRAIL`). Never document `/admin/**` or "operator login" as a required checkpoint in runbooks, skills, or reports.
- Do not set `demo.auth.proto-open=false`; do not mass-delete Admin\* Java; do not disable `/admin/**` routes (madasin `do05` trace-snapshots + ADMIN-gated agent lanes die with them).
- Ignore stale RED recipes that demand "anonymous must get 401/403" unless the user said harden — `agent-prompts/gpt_pro_demo1_source_patch_directives_50_20260823.md` and the `UAW.txt` `do09` ownerToken section (line 1721) carry a `SUPERSEDED for vibe` stamp; ATL-03 (`agent-prompts/agents/demo1_agent_tools_library_patch_9h/system_ko.md`) still keeps its "403 without AdminToken" recipe because an in-file stamp is blocked by the checkpoint `secret-pattern` scan (token-shaped fixtures) — read it the same way: reference, not a vibe goal.
- Hardening-path tests (`AppSecurityConfigContractTest`, `AdminTokenGuardInterceptorTest` and their logout→403 / anonymous→403 cases) are `proto-open=false` fixtures: green there ≠ vibe goal. Never flip their expectations and never add a test that PASSes a logout-block.
- Secrets never print; no push / `git add -A` / `commit -a` without an explicit user ask.
- The only remaining hard stop is deploying or pushing with `proto-open` still enabled.
- Sibling always-on rules: `.clinerules/61-demo1-vibe-agent-auth-relax.md` (agent-side auth/privilege relax) and AGENTS.md `DEMO1-PROTOTYPE-AUTH-LIGHT`. Overlap is intentional; if wording diverges, AGENTS.md wins and this file is trimmed, not the reverse.
