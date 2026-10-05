<!-- BEGIN AGY-PROJECT-POINTERS (demo-1, 2026-10-03) -->
# agy on demo-1: pointers (rules live elsewhere; this file stays small)

- Project Root: C:\AbandonWare\demo-1\demo-1\src. Rules SSOT = ./AGENTS.md.
- AGENTS.md is ~29 KB; agy loads only the first ~24,000 bytes (~line 266).
  Rule bodies were moved to `docs/agents-rules/<BLOCK-ID>.md` — each stub in
  AGENTS.md points at its doc. Before work on these topics READ the doc:
  Conditional local Git → `docs/agents-rules/DEMO1-GIT-LOCAL-FIRST.md` ·
  Devin directive loops → `docs/agents-rules/DEMO1-DEVIN-DIRECTIVE-LOOP.md` ·
  Prototype light → `docs/agents-rules/DEMO1-PROTOTYPE-LIGHT.md` ·
  Vercel AI Gateway/Jev → `docs/agents-rules/DEMO1-VERCEL-AI-GATEWAY-CREDIT.md` ·
  Cooperative verification → `docs/agents-rules/DEMO1-COOP-VERIFY-RAILS.md` ·
  Vibe Self-Ask → `docs/agents-rules/DEMO1-VIBE-SELFASK-JUDGE-AUTO.md`.
- "X한테 지시서 써줘" / directive / 지시서 / PASTE_ requests → activate skill
  `demo1-agy-directive-writer` (it composes `demo1-devin-directive-loop`).
- Durable project facts (pointers only): .agents/skills/demo1-agy-directive-writer/references/durable-facts.md
- GrokBot sessions & memory: docs/GROKBOT_MEMORY_INDEX.md + .agents/rules/grokbot-session-memory.md (bridge CLI: python -B scripts/grok_to_agy_memory_bridge.py sync|list|show|search)
- Grok Bot(데스크톱 앱) 규칙·스킬·기록: docs/GROKBOT_BOT_RULES.md + .agents/rules/grokbot-bot-memory.md (가져오기: python -B scripts/grokbot_bot_import.py)
- Korean workflow + GrokBot role handover (agy answers Korean when the user writes Korean; agy runs the former Grok lane — directives + tools/scripts/mocks; ~/.grok is read-only): .agents/rules/agy-korean-grokbot-role.md

## Guardrails (always, even with auto-approve)
- No git commit / push / add -A / add . / commit -a. Read-only git only when AGENTS.md allows.
- No secrets in output or files (env var NAMES only). No ~/.gemini auth/keyring edits, no /logout.
- 유료 API 호출은 지시서의 라이브 호출 상한 안에서만 허용한다. 크레딧 구매·요금제·모델 설정 변경은 사용자에게 묻는다.
- Directive-writer mode edits NO product source; it only writes the directive file (and evidence copies).
- Unsure or destructive → stop and ask with a recommended option.
<!-- END AGY-PROJECT-POINTERS -->
- AGENTS.md 24,000B 이후 절 목록(자동 생성): .agents/rules/agents-md-overflow-index.md
