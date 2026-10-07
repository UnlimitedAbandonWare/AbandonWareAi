<!-- BEGIN AGY-PROJECT-POINTERS (demo-1, 2026-10-03) -->
# agy on demo-1: pointers (rules live elsewhere; this file stays small)

- Project Root: C:\AbandonWare\demo-1\demo-1\src. Rules SSOT = ./AGENTS.md.
- Current document/P0 entry: [Primary/P0 entry](docs/PRIMARY_SURFACE.md); implementation status remains [PROJECT_STATUS](docs/PROJECT_STATUS.md).
- Before starting work or judging deadlines and added scope, READ the shared [guard-deadline-scope](docs/agents-rules/DEMO1-DEADLINE-SCOPE-JUDGMENT.md) body.
  Planning margin covers the whole task; preserve existing authority, verification, and the user hard cap.
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
- Devin 지시서 스킬 태그 자동 고정: Devin 지시서 작성 시 첫 줄에 표준 18개 스킬 프리셋(@objective-executor @demo1-devin-source-orchestrator @demo1-vibe-max-agency @demo1-core-request-router @meta-rayban-display @demo1-meta-display-simple-caption @demo1-meta-display-resume @frontend-display-debug @demo1-conversate-hint-context @demo1-evidence-debugging @demo1-repairing-from-live-evidence @rag-search-diagnosis @search-zero-result-recovery @safe-source-edit @compile-verify-smoke @start-rag-reload @positive-negative-neutral-judge @SKILL.md)을 누락 없이 반드시 자동 포함/배치한다.
- Durable project facts (pointers only): .agents/skills/demo1-agy-directive-writer/references/durable-facts.md
- GrokBot sessions & memory: docs/GROKBOT_MEMORY_INDEX.md + .agents/rules/grokbot-session-memory.md (bridge CLI: python -B scripts/grok_to_agy_memory_bridge.py sync|list|show|search)
- Grok Bot(데스크톱 앱) 규칙·스킬·기록: docs/GROKBOT_BOT_RULES.md + .agents/rules/grokbot-bot-memory.md (가져오기: python -B scripts/grokbot_bot_import.py)
- Korean workflow + GrokBot role handover (agy answers Korean when the user writes Korean; agy runs the former Grok lane — directives + tools/scripts/mocks; ~/.grok is read-only): .agents/rules/agy-korean-grokbot-role.md

## Guardrails (always, even with auto-approve)
- No git commit / push / add -A / add . / commit -a. Read-only git only when AGENTS.md allows.
- No secrets in output or files (env var NAMES only). No ~/.gemini auth/keyring edits, no /logout.
- 유료 API 호출은 지시서의 라이브 호출 상한 안에서만 허용한다. 크레딧 구매·요금제·모델 설정 변경은 사용자에게 묻는다.
- Directive-writer mode edits NO product source; it only writes the directive file (and evidence copies).
- 허용된 가역 문서 작업은 불확실성을 밝히고 독립 범위를 계속한다. 실제 차단·불가역·비용·정책 변경 판단은 AGENTS.md의 Self-Ask 규칙을 따른다.
- VIBE_OPEN: `configs/vibe-open.yaml` enabled 동안 보안 검증·접근 인증 질문은 묻지 않고 "개방 유지+DEFERRED_SECURITY"로 자동 처리 — SSOT `docs/security/VIBE_OPEN.md`.
<!-- END AGY-PROJECT-POINTERS -->
- AGENTS.md 24,000B 이후 절 목록(자동 생성): .agents/rules/agents-md-overflow-index.md
