<!-- moved-from: AGENTS.md L281-L283 sha256=3653f8c06083fd4b28ef966a9e229860ec268c61e1ffd56c4a989db1f253c18a movedAt=2026-10-03T00:10:40.401654+00:00 -->
## Codex Computer And Environment Autostart
- Prefer PowerShell, repo scripts, and file APIs first; Computer Use plugin only when shell/file APIs are insufficient or explicit UI evidence/control is needed. Ollama/local LLM startup: existing `LocalLlmProcessManager` + `LOCAL_LLM_ENABLED`/`LOCAL_LLM_AUTOSTART`/`OLLAMA_HOST`; start wording `ollama serve`; persistent env writes only when the user asked; never persist or print raw keys/tokens/headers/cookies/env dumps; prove autostart with command output.

