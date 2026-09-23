---
name: demo1-nova-focus
description: Use when implementing or fixing Nova Focus, the '노바' wake-word focused conversation on Conversate + Meta lens.
---

# Nova Focus — Codex digest of the pasted directive

Feature contract: with the **existing** Fold6 transcription already running, an utterance containing the literal wake token `노바` opens a focused conversation (Fold in-document overlay + Meta lens focus screen), live-updates the question draft, submits the confirmed question **once**, stores Q/A in **one** persistent room, accepts follow-ups without the wake word during a configured idle window, then closes only the focus UI. Transcription, captions, auto-hints, mic/PCM, and room history all survive open/close.

## Spec location and authority order

1. `agent-prompts/nova-focus/` — repo copies of the spec:
   - `Nova_Focus_Source_Modification_Instructions.md` — base contract (phases A–F, tests T01–T48, anchors E01–E28)
   - `Nova_Focus_Text_Flow_Addendum.md` — **supersedes** the base doc's §9 first-render timer and the §10.1/§10.2 *paged* answer display with the sequential typewriter flow
   - `Nova_Focus_Source_Evidence.md` — source anchors from the user's ZIP
2. This SKILL is the digest. On requirement questions the spec wins; on code facts the **live repo source** wins.
3. Path mapping: ZIP `java/com/example/lms/...` → `main/java/com/example/lms/...`; ZIP `resources/...` → `main/resources/...`. Proposed names (`NovaFocusService`, `NovaWakeMatcher`, `ChatExecutionContext`, `display-focus-flow.js`, `NovaFocusProfile/Turn`) are **proposals** — reuse existing equivalents if present; do not assume they exist.

## Non-goals

No new mic/ASR/wake-word engine, no always-on listener, no auth/accounts, no second chat engine, no page-number UI for focus answers, no model/provider/key changes, no changes to general hint paging/TTL/`ld-*` settings, no `git` operations, no new frameworks.

## Resume note (read before opening scope)

Two earlier sessions stalled at registration: journals `nova-focus-17795bf7`, `nova-focus-flow-263cc992` (`in_progress`, 0 events = liveness unknown, not done). Re-read actual files; do not restart scope blindly. Some implementation targets sit under **expired** leases (`main/java/com/example/lms/service/chatworkflow.java` = `rag-chat-path-gate-c3`, `conversatelocalcardgenerator.java` = `madwain-live-verify`): expired + `owner-evidence-needed` still blocks overlap — use `scoped-blocker-recovery` lease lifecycle (proven dead owner → `-Action recover`) or take non-overlapping work first; never delete or steal a lock. `.windsurf/rules/meta-rayban-display-runtime.md` still carries the superseded "do not change force-hint 180 s" lock — `AGENTS.md` (settings-driven display/generation knobs) wins over it.

## Contract checklist (the rules that make or break it)

Input / routing:

1. Wake input = each **newly accepted** `Utterance.text` at the `phone_voice` seam (`ConversateAsrBridge` → `ConversateSessionService.submit`). Never re-scan captions, TXT, history, or model output.
2. Literal wake token `노바` with Unicode letter/number/`_`/combining-mark boundaries: accept `노바`, `노바, 질문`; reject `슈퍼노바`, `노바크`, `노바카인`, `노 바`. Remove the matched token once + boundary punctuation; never global-replace; never apply normalized-string indexes to the original text.
3. Interim results **replace** the current draft (one question being composed, not many messages); only a confirmed question submits once. `노바`→`노바카인` interim retraction = preview rollback, zero LLM/room writes. The quiet timer never promotes unconfirmed interim to final.
4. Focus works even when `hintsEnabled=false` — route the event to NovaFocus **before** the hints-OFF early return; when Focus consumes an event, do NOT also feed the cue path (no double generation).
5. Separate work lifecycle: `cancelWork(s)`, shared `inflight`, accumulated/forced hint paths must not cancel or overwrite a Focus answer. While Focus is active, suppress auto-hint generation/foreground display **without** mutating `hintsEnabled`; on close, reset only the cue baseline position (don't flush focus-era transcript into a new hint).

Room / context:

6. **One** persistent `NOVA_FOCUS` room per verified owner (DB unique); wake/close/reconnect reuses it. IDs stay distinct: `assistId/epoch` (capture), `activationId` (one open), `turnId` (one question), `chatSessionId` (room), `stateVersion`/`settingsVersion`.
7. Model input is explicitly bounded: current question + last 2 completed Q/A **pairs** + cumulative summary + question-relevant history. Roles preserved; token cap vs the routed model; full history stays in DB only.
8. Do **not** stop at `request.setHistory(...)` — E09/E10 show DTO.history may never reach provider messages. Prove the real `ChatWorkflow` final `msgs` with a fake-model capture, including web-search-OFF recall ("아까 정한 이름은?"). A server-internal execution-context overload keeps normal calls delegated with empty context; public JSON must not be able to mint it.
9. Turn idempotency: `(room, idempotencyKey)` unique; same requestId + same body = same result; same requestId + different body = 409; same sentence later = a new normal turn. Crash after a model call → `OUTCOME_UNKNOWN`, never auto re-bill.

Wire / display:

10. Additive optional `focus` field on Snapshot/`View`/`LensText`/relay Event — never stuff focus answers into `hint` (its char cap, TTL, and hints-OFF would hide them). `/lens/text` stays read-only: polling never mutates state, creates rooms, or slides timers.
11. Focus answers render via the **sequential-flow display** (addendum): grapheme-by-grapheme reveal (`Intl.Segmenter` or verified fallback — never UTF-16 index splits; Hangul jamo/emoji must not break), oldest completed line rolls off when full (default ~6 visible lines), last char → `tailHoldMs` (5 s) → `fadeMs` (0.4 s). No page numbers/next/prev for focus answers; general hint paging stays untouched.
12. The followup-idle timer starts at `presentation_done` (= final answer received + display queue drained + last char in DOM), **never** at first render or answer-generated. Generation/streaming time is outside `followupIdleMs` (a 600-grapheme answer at 80 ms ≈ 48 s must not early-close on a 20 s timer); missing receipt → bounded `display_unconfirmed`, never infinite wait or fake success.
13. Escape with focus open = close focus only (`stop()` must not fire); the Fold focus panel is an in-document overlay — never navigate/`pagehide` on entry (that kills voice via `voice.stop()`). Focus close never touches capture/PCM/session/room history.
14. Settings are server-authoritative with `settingsVersion`; Fold localStorage is display cache only — stale cache never silently overwrites (409 → show diff). New keys only: `enabled` (default OFF), `wakeWord` (`노바`), `utteranceQuietMs` 1200 (500–5000), `followupIdleMs` 20000 (5000–120000), `wakeListenTimeoutMs` 8000 (3000–30000), `focus.presentation.*` (`sequentialTextEnabled` ON, `charIntervalMs` 80 (50–160), `maxVisibleLines` 6 (4–8), `autoFadeEnabled` ON, `tailHoldMs` 5000 (2000–15000), `fadeMs` 400 (200–1000)). Do NOT repurpose `ld-quiet`/`ld-cooldown`/`ld-force`/hint TTL.
15. The lens read token never gains write power: settings/input/history = producer+owner verified; render receipt = receipt-only ticket bound to owner/activation/turn/answerVersion — first render vs `presentation_done` recorded separately, each accepted once.

## Phase order (spec §14 → repo steps)

- **A — seams + OFF baseline.** Confirm canonical sourceSets/paths, journal open, lease check for declared targets; write wake-matcher/turn-assembler fake tests first; prove OFF = zero side effects.
- **B — ASR intake + cue isolation.** `ConversateSessionService.submit` ordering, `ConversateQuestionPolicy` revision/dedup vs semantic suppression split. ON/OFF/hints-OFF/dup green on text fixtures before continuing.
- **C — persistent room + model context.** Room get-or-create (unique), turn CAS/idempotency, bounded history → `ChatService`/`ChatWorkflow` overload; fake-model capture proves roles/pairs/summary/current-question-once + web-OFF recall.
- **D — wire + UI.** `focus` field end-to-end (`Snapshot`→`View`→`LensText`→relay + `/lens/text`), Fold overlay + sequential-flow module, `meta/index.html` + `receiver.js` on the real `mountLens` path (not only `clientRole=test`), settings section in existing `advanced-settings`, Escape isolation, `?v=` bump.
- **E — diagnostics + one real request** with an authorized account: mic/PCM continues, follow-up works, lens readable. `$demo1-agent-api-spend-guard` applies; unrun device items stay `unverified`.
- **F — report:** files+hashes, DB apply state, commands+exit codes, per-test results, replayed-full-answer vs real partial-streaming distinction, unverified items, rollback path.

## Must-pass gates (subset of T01–T48 + addendum)

T01 OFF→no focus side effects; T03 hints OFF + Nova ON works; T05 wake retraction zero-cost; T07 wake+question in one utterance; T10 multi-segment question submits once; T14 THINKING barge-in never cancels; T19 no auto-close during generation; T20 idle timer at `presentation_done` only; T22 close keeps mic/transcript; T25 close-vs-completion CAS; T27 read token cannot write; T30 poll side-effect-free; T34 one room per owner; T38 provider messages carry bounded context; T39 web-OFF recall; T45 lens keys drive focus display not hint pages; T48 full OFF regression. Addendum: 600-grapheme @80 ms never early-closes; no split graphemes; repeated snapshot = no replay/timer-slide; hidden→visible resumes mid-position without dumping backlog; expired answers stay dead.

## Guards that still apply

Work-ledger journal + checkpoint cycles; `source_edit_session` lease for app source; `demo1-source-edit-three-way-preflight` for app-source mutation; `AGENTS.md` model lock + `configs/api-routing.yaml` (no new/paid model without approval); no secrets in logs/tests; synthetic fixtures for verification; matching Close/Start wear BAT pair for live Java proof; `$demo1-goal-complete-stop` at acceptance.
