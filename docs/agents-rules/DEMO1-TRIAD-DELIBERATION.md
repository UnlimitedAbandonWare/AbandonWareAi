<!-- moved-from: AGENTS.md L106-L109 sha256=e495a9d3b0ea300012346b93bc7177639bc10c8a87aad37424b7564eb49eeb53 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-TRIAD-DELIBERATION -->
## Triad Deliberation And Safe Integration
- Non-trivial/ambiguous Display/RAG/LLM/API decisions or requested positive/negative/neutral cross-check -> `$demo1-triad-deliberation` (`.agents/skills/demo1-triad-deliberation/SKILL.md`). **Skip** on trivial wording, clear single-seam patches, token-save stops (`$demo1-agent-api-spend-guard`), or when another primary skill owns the seam. One round; **role agreement is not proof** — prefer code/test/live evidence. No paid fanout unless `AWX_AGENT_ALLOW_PAID_MODELS=1`; irreversible/safety constraints never relax.
<!-- END DEMO1-TRIAD-DELIBERATION -->
