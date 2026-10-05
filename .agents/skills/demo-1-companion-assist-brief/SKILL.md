---
name: demo-1-companion-assist-brief
description: >-
  Use this when a Codex source brief is running or queued and the user asks for
  a Devin/Grok CLI/Clean 서브·조수 brief: pick tool archetypes, keep files disjoint,
  and gate timing on the main agent.
---
# demo-1 Companion Assist Brief
## When
A Codex source brief is running or queued and the user asks "데빈/그록/클린한테도 서브로", "도와주게", "조수 지시서".
## Steps
1. Read the main brief + ledger: hot files, WPs, caps (lease who, newest journal).
2. Pick 3–5 artifacts the main agent can just run: anchor map · RED fixtures/staging tests · 127.0.0.1 mock (401/429/slow/cut stream) · call/smoke counter · verify runner (DryRun) · forbidden-line checker + baseline snapshot taken before main creates files · claim-drift/success-mask scan · debug card (3 hypotheses + 1 check) · HOLD ledger · post-run review (PENDING until main reports).
3. Disjoint files: companion writes new files in scripts/, .agents/skills/, var/, its ledger. Gradle/server/smoke only after main's final report.
4. Fit: Grok CLI = tools/mocks + `.grok/rules` pointer ≤5 lines; Devin = harness/rules/reviewer; Clean = red-team + static checks (read-only or within lease; it runs on the original tree).
5. Source only on explicit grant: ≤4 named files, ~110 lines, no shared hot file; else HOLD_FOR_CODEX.
6. Stdlib Python / Node built-ins; wrap existing scripts, never fork.
## Reply
`받는 이 | 파일 | 역할 | 순서`, safe to run together?, 말로 per agent, 한 줄.
예: 「Codex는 고치고, Devin은 채점 도구랑 보류 장부만 만들어요.」
