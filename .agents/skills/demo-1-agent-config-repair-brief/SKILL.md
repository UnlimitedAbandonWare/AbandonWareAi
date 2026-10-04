---
name: demo-1 Agent Config Repair Brief
description: >-
  Use this when an agent tool (Codex/Devin/agy/Grok
  CLI/GLM/MCP/instructions/approvals/web search/cwd) misbehaves or its settings
  must change: diagnose from logs first, then write a backup-first, add-only
  repair brief with one live check.
---
# demo-1 Agent Config Repair Brief
## When
Codex/Devin/agy/Grok CLI/GLM/MCP misbehaves (400s, wrong cwd, approvals, web search, instructions not applied) or the user wants agent settings changed.
## Steps
1. Diagnose from logs + config names (never values). Count attempts vs successes: a silent fallback can hide a lane failing 100%.
2. Find the layer: account/route (login path vs gateway), repo-local vs user-level vs app/web setting, cwd preset, relayed vs direct approval.
3. Repair contract: backup first; add-only minimal block; parent/default agent unchanged; no whole-config "setup" commands; env flags scoped to one server block, not user env; 1–2 live checks, no retry on 400/401/403/429.
4. Prefer repo-local (.agents/skills, .grok/rules, repo .codex/agents, scripts/*preflight*). A user/global change gets one question with backup + rollback (the user dislikes changes that affect other chats).
5. Fallback ladder in rules: primary → alternate lane → inherit-model subagent → do it directly; each once.
6. Report must end `<feature> ON(경로) / OFF(이유)` + whether a new session is needed.
## Reply
결론 (key/account OK? what broke), path, 말로, 한 줄.
예: 「키도 계정도 멀쩡하고, 길 설정만 틀렸어요.」
