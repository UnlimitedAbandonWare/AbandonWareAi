# Meta Ray-Ban Display ??Cue LLM / Context Spec (2026-09-18)

Purpose: keep multi-model **fallback**, but normalize **input conversation context** and **hint output caps** so model switches do not suddenly shrink cues.

## Display caps (lens) ??do not fight CSS

| Cap | Value | Where |
|---|---|---|
| Hint page lines | default ~8 per page (`max-height: 1.25em * 8.1`), configurable via dev settings | `meta/index.html` `#hint` |
| Lens line budget | default ~13 rendered lines shared by transcript+hint (measured `scrollHeight`; transcript newest-units-first, default max ~4 beside a hint page), configurable via dev settings | `receiver.js` `LENS_LINE_BUDGET`, `#transcript` |
| Hint chars | hard cap **1180** Unicode (`ConversateSessionService.HINT_TEXT_MAX`), <=**24** `\n` lines (`HINT_LINE_MAX`); default generation target **1000** (`conversate.cue.hint-target-chars`), per-owner adjustable 240–1100 via `relay/lens-settings` `hintTargetChars` | `ConversateCardPrompt.cueHint`, `parseHint`, `Card`, `ConversateCardVerifier`, `shortLensText(...,HINT_TEXT_MAX)`, `receiver.js` `LENS_HINT_CHARS_MAX` |
| Caption visible | `visible-chars` (default 200) rolling store; `lens-conversation-chars` (default 280) lens projection | rolling transcript on lens ??**not** doubled with hint context |
| max-output-tokens | **1536** default (shared for all cue routes) so ~1000-char cues are not cut | `application-meta-display.yml` ??`ConversateApiCueService.limit` |

Hint **display** length stays lens-sized. This change doubles **input recent-turn context** for judgment, not the lens string.

## Input context (sliding window, newest kept)

| Knob | Before | After (~2횞) | Soft max (~3횞 old min) |
|---|---|---|---|
| `conversate.transcript.context-chars` | 1000 | **2000** | rolling clamp **1600??000** |
| Rolling buffer clamp in `remember()` | hard `800??200` (ignored higher YAML) | **1600??000** using `contextChars` | fixes ?쏽AML change did nothing??|
| Non-rolling API cue turns/chars | 6 / 4096 | **12 / 8192** | ??|
| `boundedContext` default | 6 / 4096 | **12 / 8192** | ??|
| Local support fallback bound | 4 / 2048 | **8 / 4096** | ??|

TTL still `context-ttl-ms` (default 120s). Oldest turns drop first.

## Cue models (admission + fallback) ??Meta Display profile

Admission: `ConversateCueRoutingPolicy` + `llmrouter.models.*` (stage=`chat`). Locals (`ollama`/`local`) are **skipped** in cue admission; `ConversateLocalCardGenerator` is **post-cloud** support fallback only.

| Route key | Model (default) | Provider | Role | Gate? | Quality | Est. $/1M in/out | Context window (vendor) | Max out (vendor) | Our sent context | Our out tokens | Fallback notes |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `gemini-cue` | `gemini-3.5-flash-lite` | gemini | Primary cheap cue | yes | 1 | 0.30 / 2.50 | ~1,048,576 | 65,536 | ??2??k chars turns | 640 | Needs `gemini.gateway.purpose.router.enabled` (true in meta-display) |
| `groq-gate` | `openai/gpt-oss-20b` | groq | Free-tier gate/cue | yes | 1 | 0.075 / 0.30 | 131k (Groq) | large | same bound | 640 | `GroqFreeTierGuard`; paid Groq budget 0 |
| `openai-economy` | `gpt-5.6-luna` | openai | Economy fallback | yes | 1 | 0.20 / 1.20 | vendor large | vendor large | same | 640 | credential `OPENAI_API_KEY` |
| `api3` | `openai/gpt-oss-120b` | groq | Stronger Groq | no | 3 | 0.15 / 0.60 | 131k | large | same | 640 | fallback-only weight 0 |
| `gemini-pro` | `gemini-3.8-flash` | gemini | Higher quality | no | 3 | 0.75 / 3.75* | ~1M | 65k | same | 640 | *intro rates thru 2026-12-31 |
| `openai-balanced` | `gpt-5.6-terra` | openai | Mid | no | 3 | 2.00 / 12.00 | large | large | same | 640 | quality?? only |
| `openai-premium` | `gpt-5.6-sol` | openai | Premium | no | 4 | 4.00 / 20.00 | large | large | same | 640 | rare |
| local-support | `conversate.generation.model` | ollama | After null cloud hint | ??| ??| $0 | local VRAM | local | 8 turns / 4096 | model cfg | `conversate.generation.enabled` often false |

Sort: primary providers (openai/gemini) preferred, then cost/success, then latency. Failures cool down route 20??0s.

## Why fallbacks looked ?쐍ot applied??
1. **Rolling clamp 800??200** ignored `CONVERSATE_CONTEXT_CHARS` above 1200.
2. **Shared** `boundedContext` / session windows ??not per-model; short answers more often from **prompt/output caps** or **model verbosity**, not smaller context on Groq vs Gemini.
3. Cue `maxLength` (`HINT_TEXT_MAX` 1180) + `max-output-tokens` shared ??after this patch both stay aligned across routes.

## Change policy for new models

1. Add `llmrouter.models.<key>` + `conversate.cue.routes.<key>` priced-model + USD.
2. Do **not** add a smaller private context window; use session/`boundedContext` SSOT.
3. Keep `max-output-tokens` aligned with the configured target (default 1536 for ~1000-char Korean cues); raise only with `finishReason=length` evidence.
4. Prefer Flash-Lite / free Groq for quality 1; reserve Pro/premium for quality??.
5. Never log secret values.

## Ops

After Java/YAML edits: `Start-RAG` / `start_rag_stack.ps1 -MetaDisplay -ForceRestart`. Static lens uses a measured shared-line budget (default ~13; hint default ~11 lines/page, transcript default max ~4 lines, newest units kept) — all adjustable via dev settings; the settings round trip (UI → server → `lens/text` → lens render) must reach the glasses, and transcript style must stay fixed whether or not a hint is present. Multi-page hints auto-advance by default (5 s; configured interval 1–100 s applied as-is — never shortened to beat a hint's TTL; 0 = off) because the lens has no key input. Transcript hold, hint hold and page interval are independent `LensDisplayPrefs` values (defaults 20 s / 20 s / 5 s) round-tripped UI → `relay/lens-settings` → `lens/text`/relay `display` → `receiver.js`.

## Hint length + force refresh (2026-09-18b)

- Prompt SSOT: `ConversateCardPrompt.cueHint` — default **4–8 lines**, avoid 1–2 line / clarify-only answers except simple questions.
- Shared `max-output-tokens` for all cue routes (default 1536; do not per-model inflate).
- Rolling normal path: deterministic transcript trigger (probability sampling removed) — a final utterance with normalized delta ≥ `trigger-min-delta-chars` (default 120) fires `utterance_end`; accumulated delta ≥120 plus quiet ≥ `trigger-quiet-ms` (YAML factory default 2500ms; **Fold 안경 표시 설정 overrides and persists**) of no transcript change fires `transcript_delta`. Both gated by `cooldown-ms` (factory default 10s, same Fold prefs), inflight/queue, and the `hintHoldUntil` display hold (`display-ttl-ms` factory default 20000; follows the set hint hold).
- Safety path: after `force-after-ms` (YAML factory default 180s; **Fold prefs override and persist** — do not keep 180s frozen) if `transcriptDeltaChars` ≥ `force-min-delta-chars` (50), enqueue `forceHint=true` which upgrades `NO_CUE` → `CUE` once.
- Runtime only: `hintBaselineNorm`, `lastSuccessfulHintAt` (not session memory / ctx.memory).
- Debug events: `transcriptDeltaChars`, `elapsedSinceLastHint`, `normalHintTrigger`, `forcedHintTrigger`, `selectedModel`, `hintGenerated`; every trigger carries `triggerReason` (`utterance_end` / `transcript_delta` / `3min_watchdog` / `manual`) on `CUE_TRIGGERED`, `ACCUM_HINT_TRIGGERED`/`ACCUM_HINT_WATCH`, `FORCE_HINT_TRIGGERED`/`FORCE_HINT_WATCH`/`FORCE_HINT_SKIPPED`, `HINT_GENERATED`; `CUE_SKIPPED` reports `reason` (`context_short` / `display_hold` / `generating` / `cooldown` / `delta_below`).

## FIELD_TESTED hint length (2026-09-18c)

- Generation target: default **1000** Hangul characters (`conversate.cue.hint-target-chars`), per-owner adjustable 240–1100 via `relay/lens-settings` `hintTargetChars`; previous default was 480–540.
- Hard cap: **1180** (`HINT_TEXT_MAX`), ≤24 lines (`HINT_LINE_MAX`). Default; adjustable only through the shared constants.
- On-lens rendering: transcript+hint share a measured shared rendered-line budget (default ~13, configurable; hint default ~11 lines/page; transcript newest-units-first, default max ~4 lines beside a hint) at default **26–30px**; **4–8** lines remains the hint-generation shape, not a display cap — do not equate line count with full char budget. Transcript stays small/gray from first show; its size/color/weight must not change when a hint appears or disappears.
- `max-output-tokens` is API budget (shared cue routes, default **1536**) — not character count; tune further only with `finishReason=length` / measured cuts.
- force-hint factory default **180s / Δ50chars**. The **180s** is a default, not a lock — Fold 안경 표시 설정 must change the live force-after cycle and remember the last saved value. Δ50 stays a char gate unless the user asked to tune it.
