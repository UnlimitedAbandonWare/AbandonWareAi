---
name: demo1-core-request-router
description: Use when a demo-1 user request may touch Meta Ray-Ban Display, core RAG/LLM logic, or provider/API wiring — classify once, one primary skill per phase
---

# Demo1 Core Request Router

Too many docs already exist. Do **not** create another long guide. Classify once. Load one primary skill per **phase** (+ at most one guard). A pasted brief with independent seams → `$demo1-devin-source-orchestrator` (`scripts/devin_task_orchestrate.py plan`) then edit source one phase at a time.

## Classify (pick one primary **per phase**)

| Signal in the user ask | Primary skill | Optional guard |
|---|---|---|
| Ray-Ban / Meta Display / lens / Fold6 caption / conversate on glasses | `$demo1-meta-display-simple-caption` for **lens text**; `$demo1-meta-display-webapp` only for authorized Display implementation roles | `$demo1-evidence-debugging` if broken behavior |
| Fold 안경 표시 설정 / 전사·힌트 유지시간 / 페이지 전환 간격 / **힌트 생성 주기**(20 s hold, ~2 s page floor, 2.5 s quiet, 10 s cooldown, 180 s force) | `$demo1-meta-display-simple-caption` **authorized source patch** on existing `#lens-display` + `LensDisplayPrefs` + cue knobs. Persist last saved prefs; the live cycle must change. Implement, do not report-only | skip triad / three-way — settings-driven, not fixed |
| Past-context **input** window / 지금부터 새 맥락 / old-topic mix / late fallback overwrite (not output length, not display TTL) | `$demo1-conversate-hint-context` after `devin_task_orchestrate.py plan` when the brief also has listen/display seams | skip triad / three-way; `$demo1-evidence-debugging` for path trace |
| Fold background listen / other tab / 수음 1–2분 끊김 / visibilitychange vs user-stop | `frontend-display-debug` after the same `plan` when the brief also has hint-context | `$demo1-evidence-debugging`; do not steal capture ownership because a display tab opened |
| Conversate / Fold6 **hint** stuck on evidence-refusal, empty search wiping answers, definition Q forced into retrieval | `$demo1-conversate-hint-evidence` (+ `$demo1-meta-display-simple-caption` for lens text) | `$demo1-agent-api-spend-guard` when stopping after green tests |
| `노바` wake word / **Nova Focus** / 집중 대화 모드 (existing transcript → focused Q/A → persistent room → lens sequential answer display) | `$demo1-nova-focus` — digest + phase order; spec copies in `agent-prompts/nova-focus/` (addendum's sequential flow supersedes the base doc's paged answer + first-render timer) | `$demo1-agent-api-spend-guard` for live LLM checks; skip triad — contract is explicit |
| RAG / retrieval / PromptBuilder / rerank / GraphRAG / fail-soft search | `$demo1-rag-platform` (strategy: `$demo1-rag-strategy-orchestration`, resilience: `$demo1-rag-resilience-observability`) | `$demo1-api-routing-inventory` |
| LLM / Ollama / model lock / provider / embedding / ASR / search keys | `$demo1-api-routing-inventory` + `$demo1-local-llm-gpu-gateway` | `$demo1-api-spec-drift-guard` when docs/code disagree with live APIs |
| Debug from logs / duplicate actions / hidden triggers | `$demo1-evidence-debugging` or `$demo1-debugging-with-two-tools` | `$demo1-invisible-eye` when unexplained |
| Post-change skill/family cleanup | `$demo1-skill-family-postprocessor` / `$demo1-agentic-chat-postprocess` | — |

## Tri-System Disambiguation Matrix

Three systems share one pipeline (`ChatWorkflow` → `StandardPromptBuilder`). Classify the
**surface** first — same words ("answer length", "search off") mean different owners per
system. Invariants: `docs/agents-rules/DEMO1-TRI-SYSTEM-SEAM-ISOLATION.md` +
`docs/design/TRI_SYSTEM_ISOLATION_SPEC.md`.

| System | Signals | Primary | Optional guard |
|---|---|---|---|
| Meta Display / Nova Focus | Ray-Ban, 안경, 렌즈, Fold6 caption, Nova Focus, 노바, Display | `$demo1-meta-display-simple-caption` (Nova Focus 계약은 `$demo1-nova-focus`) | `$demo1-evidence-debugging` |
| Dynamic RAG platform | RAG, 검색, 임베딩, GraphRAG, 벡터, 인용, rerank | `$demo1-rag-platform` | `$demo1-api-routing-inventory` |
| Main `/chat` chatbot | 챗봇, /chat, 세션 목록, 스트리밍, 모바일 레이아웃 | `$demo1-api-first-routing-assist` | `$demo1-codex-browser-agent` |

- One primary skill per phase is binding here too — a brief touching two systems is two
  phases via `$demo1-devin-source-orchestrator`, never one mixed patch.
- Surface check before patching: Display prompt/flags live inside
  `NovaFocusAnswerService` + `PromptContext.focusAnswerLengthChars`; they must never
  mask ordinary `/chat` `minWordCount`/`sectionSpec` or global RAG defaults.
- `/interview` screen failures are auxiliary evidence, never a main-surface verdict.

## Hard rules

1. **One primary skill per phase.** Do not stack Display + DAT + custom relay + Web App fixes in one change. Independent seams from one pasted brief are sequential phases from `$demo1-devin-source-orchestrator`, not one mixed patch.
2. **Source of truth order:** live probe / current code seam → `configs/api-routing.yaml` → `docs/API_ROUTING_SPEC.md` → old Markdown/comments. Stale docs lose.
3. **Lens output:** conversation + short hints only (`$demo1-meta-display-simple-caption`). Fold6/web owns mic/controls. Display **and** cue/generation timings follow persisted Fold settings; YAML 20 s / 2.5 s / 10 s / 180 s are defaults, not locks.
4. **RAG/LLM edits:** stay on existing seams (`PromptBuilder`, provider guards, KeyResolver, Conversate, embedding adapters). No parallel stacks.
5. **API mismatch:** before coding to an old comment/example, run `$demo1-api-spec-drift-guard` and update inventory + routing YAML, then code.
6. **After patch:** run the smallest verification already required by AGENTS; then skill-family postprocessor only if skills/prompts changed.

## Exit report (short)

`route=<primary>`, `guard=<optional|none>`, `files=...`, `driftChecked=true|false`, `lensContract=simple-caption|n/a`.

## Reachability-first pointer (grokbot 2026-10-09)

- Display / Nova Focus / model selection / `provider_not_configured` / "여러 번 고쳤는데 그대로": load `$demo1-reachability-first-debug` **before** the primary skill above and before choosing any file.
- It locates the failing stage from runtime evidence, reads the effective saved profile, and spawns `assist_runtime_skeptic` in parallel; a brief whose path is unreachable gets a CHALLENGE, not a patch.
