---
name: demo1-conversate-hint-context
description: Use when Fold/Conversate hints drag in old topics and the user wants a settings-driven past-context window, new-context button, and late-response discard without wiping stored transcripts
---

# Conversate hint input context

Authorized **source patch** on the existing Fold developer menu. Not a new page, not report-only, not `$demo1-meta-display-resume`. Skip triad / three-way. Still use work-ledger + lease/preimage.

This is **not** hint **output** length (`hint-target-chars` / 1000자), not display TTL/page interval, not `$demo1-conversate-hint-evidence` (refusal/FAST). Lens text still follows `$demo1-meta-display-simple-caption`.

## Investigate first (hypotheses, in order)

1. Input assembly: cumulative transcript, opener, fixed background, or summary re-inserted stronger than the latest utterance.
2. Re-injection: previous hint or old RAG/search fed back as evidence.
3. Order/display: late fallback, cache, or leftover on-screen hint from an earlier request.

Before patching a Fold/glasses mix-up, run `python -B scripts/devin_task_orchestrate.py capture --role wear --invoke` and read `data/agent-handoff/display-debug/latest.json` `patchHints` (leftover on-screen vs new generation, cue vs hint-generate). Trace only paths that exist. LangChain4j 1.0.1 `TokenWindowChatMemory` keeps system messages and drops whole user/ai messages — a single huge transcript blob will not trim by utterance if you only shrink the window. Do not treat Git history as the live source. Confirm whether `previous_response_id` (or similar server-side conversation) is actually used before changing it.

## Settings (reuse Fold `#lens-display` / `lensSettings` / `saveSetting`)

| Control | Behavior |
|---|---|
| Use past conversation | Off → current utterance span only; required instructions and user-pinned background stay |
| Past window (seconds) | Elapsed wall time, even with no new speech |
| Past max amount | Show chars **and** tokens separately; both limits apply |
| Topic-change shrink | When a clear new topic is confirmed, drop the old topic from the **next** hint input |
| Start new context | Do not stop capture/session; bump a context epoch so later hint inputs exclude prior turns |

Protect the latest utterance and the needed previous sentence, but do not allow a single utterance to ignore the model token cap. Measure current input size and latency before proposing defaults; no preset-only dropdown. Persist so refresh/reconnect restores values **and** the next cue uses them.

## Snapshot and late adopt

One logical cue freezes: latest-utterance cutoff, included/excluded past, settings version, context epoch. Primary / verify / retry / fallback share that snapshot; a smaller-limit fallback may shrink further but must not restore excluded past. Before writing shared state **and** before painting the lens, drop results whose request/epoch/settings no longer match. Reuse `hintId` / `Card.requestId` / existing utterance numbers — do not invent a second versioner.

## Candidates

Default: recent utterance + time/size caps + explicit reset. Compare against “exclude all past” (follow-ups like “그건 얼마야?” break) and “relevance × time decay” (add only if the default still mixes topics). Do not default to rolling summaries.

## Verify

Glasses-pairing → food → settings; follow-up “그건?”; idle then new topic; reset then late fallback; long utterance + small fallback. Record tokens, call count, fallback count, latency, topic drift, missing needed context — separately. Fold diagnostics show **applied** window/amount/epoch, last audio/transcript times, reconnect reason/count, capture owner — never full dialogue or API keys.

## Likely seams (confirm live, then patch)

`ConversateSessionService`, `ConversateCardPrompt`, `ConversateApiCueService`, `DisplayConversateController`, Fold `assets/display` settings (`app.js`, `index.html`). Do not add a parallel hint stack or a new ChatMemory product.
