<!-- moved-from: AGENTS.md L306-L309 sha256=2b4dcc1bfb9673cd5b8fb81f1fad72e610a3ce00526c19248f9a2bfe84237348 movedAt=2026-10-03T00:10:40.401654+00:00 -->
## AutoLearn Handoff Review
- Before patching AutoLearn failures, read `data/agent-handoff/codex/{manifest.json,cycles.jsonl,rejected.jsonl}` (`accepted.jsonl` = supporting only); as of 2026-09-19 all absent — if still absent report `evidence_needed`, no substitutes.
- `train_rag.jsonl` is the file-backed training SoT (`data/train_rag.jsonl` currently absent; the bundled resource copy is not live). Vector DB rows are staged shadow until promoted. Never overwrite raw JSONL while diagnosing; reports go under `data/agent-handoff/codex/report/`; source fixes stay on existing UAW seams.

