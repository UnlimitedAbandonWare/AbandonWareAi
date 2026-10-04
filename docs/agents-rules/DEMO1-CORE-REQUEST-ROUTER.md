<!-- moved-from: AGENTS.md L110-L114 sha256=679bbb27ffd4b4f80b3603ace07b1d06942ac8285e4e1d414bb1040ae4908652 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-CORE-REQUEST-ROUTER -->
## Core Request Entry (Display / RAG / LLM)
- Requests that may change Meta Ray-Ban Display behavior, core RAG/LLM logic, or provider/API wiring start with `$demo1-core-request-router` (`.agents/skills/demo1-core-request-router/SKILL.md`); classify once. One primary skill **per phase**. A pasted brief with independent seams is sequenced by `$demo1-devin-source-orchestrator` — do not force the whole brief onto one skill and do not @ every skill. No long new documents.
- Docs/skills disagreeing with live vendor APIs or Ollama inventory -> `$demo1-api-spec-drift-guard`. After skill/prompt family edits: `$demo1-skill-family-postprocessor` (and `$demo1-agentic-chat-postprocess` for agentic chat output). Lens output on `$demo1-meta-display-simple-caption`.
<!-- END DEMO1-CORE-REQUEST-ROUTER -->
