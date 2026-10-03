<!-- moved-from: AGENTS.md L102-L105 sha256=d3c9d916a9f4a8e784947a06ed4d8c858110b42c6e86ebaa25c4640c51348b71 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-TRIAD-DELIBERATION -->
## Triad Deliberation And Safe Integration
- Non-trivial/ambiguous Display/RAG/LLM/API decisions or requested positive/negative/neutral cross-check -> `$demo1-triad-deliberation` (`.agents/skills/demo1-triad-deliberation/SKILL.md`). **Skip** on trivial wording, clear single-seam patches, token-save stops (`$demo1-agent-api-spend-guard`), or when another primary skill owns the seam. One round; **role agreement is not proof** — prefer code/test/live evidence. Paid lanes default ON per `$demo1-agent-api-spend-guard` SSOT (`AWX_AGENT_ALLOW_PAID_MODELS=0` is the kill switch); irreversible/safety constraints never relax.
<!-- END DEMO1-TRIAD-DELIBERATION -->
