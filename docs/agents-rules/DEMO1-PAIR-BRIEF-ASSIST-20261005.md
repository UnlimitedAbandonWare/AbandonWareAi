# DEMO1-PAIR-BRIEF-ASSIST-20261005

Codex patches the two briefs. This rule is the assist boundary.

Contracts:

- `DEMO1-MULTIUSER-RESILIENCE-20261005`
- `DEMO1-SESSION-DATA-CONSENT-20261005`

Detail and commands: `var/codex-assist-pair-brief-20261005/README.md`.
Scanner: `scripts/pair_brief_assist.py`.

## Constraints

- Product source, product resources, product tests, and `chat.js` stay with the Codex writer that already has the briefs.
- The assist scan does not run Gradle, restart the server, call a provider, or read secrets or the live database.
- Exit 0 means the scan was clean. It does not prove the brief, live concurrency, or a consent rollout.
- `PublicChatAdmissionGuard` rejection stays HTTP 429 and `chat_admission_exceeded`. Code defaults stay 64 / 2 / 4096. Filter 503 `chat_admission_unavailable` is a different observation.
- Improvement raw collection stays off when consent is missing, refused, unclear, or the finite retention value is unknown. No historical raw backfill.
- Graph `consentEpoch` does not stand in for improvement consent. `ChatSessionTraceRecorder` does not become a raw-text collector.
- A new evaluation record does not call `applyFeedbackToRatedAssistant`. The existing feedback path stays.
- `updateSessionMeta` exception suppression is not a durable success acknowledgement.
- A live overlapping source lease holds that work package only. Do not force-release it.
- Do not fold this work into RAG context release, GraphRAG, BM25, Jev, Display, or Voice.

`AGENTS.md` was not given a stub. `python -B scripts/agents_md_budget.py check` already returns `SIZE_OVER` (30749 > 30300). Adding a pointer would grow the over-budget file.
