<!-- moved-from: AGENTS.md L120-L124 sha256=5238bdcc03b0e3bafdf2e07e3379d397160a386d50d0df4ab9658a80af58885b movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-DEVIN-SOURCE-ORCHESTRATOR -->
## Devin / multi-seam source orchestration
- Pasted multi-seam source briefs (Devin, Grok, Codex): run `python -B scripts/devin_task_orchestrate.py plan --brief-file <path>` first and follow the returned phases. Hint past-context / "지금부터 새 맥락" / late fallback overwrite -> `$demo1-conversate-hint-context` (input window, not display TTL); Fold other-tab / background listen -> `frontend-display-debug`.
- Fold wear-test: `devin_task_orchestrate.py capture --role wear --invoke` copies a **redacted** Debug-Meta-Display status into `data/agent-handoff/display-debug/`; read `latest.json` `patchHints` before choosing a write seam. Does not replace work-ledger, lease, preimage, or Debug BAT. Devin paste: `@objective-executor @demo1-devin-source-orchestrator` plus the brief (`.devin/PROMPTS/`).
<!-- END DEMO1-DEVIN-SOURCE-ORCHESTRATOR -->
