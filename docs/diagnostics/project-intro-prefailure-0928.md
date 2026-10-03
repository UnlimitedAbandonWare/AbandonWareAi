# project-intro legacy UI contract — pre-existing failure memo

Contract: `DEMO1-DEVIN-AUTOGRADE-B-R3-ASSIST-20260928` task B
Agent: devin · journal: `autograde-b-r3-assist-0928-83a88157` · product diff: 0

## Verdict

`node scripts/chat_ui_stream_contract_tests.js` fails on today's tree **and on
pre-patch preimages** — a stale-contract failure, not an R2/R3 regression.
Recommendation: **exclude from Codex R3 Done**; track as a separate ticket.

## Failing check

```
node scripts/chat_ui_stream_contract_tests.js
→ Error: conversation must lead DOM and visual order: chat=4128 intro=-1
  (scripts/chat_ui_stream_contract_tests.js:1494)
```

`chat=4128` = byte offset of `<section class="chat-area-wrapper">` in
`main/resources/templates/chat-ui.html`; `intro=-1` =
`<aside class="project-intro">` absent. The script stops at this first failed
assert (lines 1490–1494 require `projectIntroIndex > chatRegionIndex`).

## Expected vs current markup

| | |
|---|---|
| expected (legacy script) | `<aside class="project-intro" aria-label="Dynamic RAG operation summary">` placed **after** the chat region; CSS asserts `.project-intro{grid-column:2}` and mobile `grid-row:2` (script lines 1667, 1673) |
| current (`chat-ui.html`) | no `project-intro` element at all; layout is `details.conversation-sidebar` + `section.chat-area-wrapper`; intro copy relocated into the sidebar note |

The CSS rules (`.project-intro{grid-column:2}` etc.) still exist in
`chat-style.css`, so the stylesheet asserts would match vacuously — only the
DOM-order assert actually fails.

## Contract conflict (사실)

- Product test `src/test/java/com/example/lms/web/ChatUiTemplateIntegrityTest.java:23`
  asserts `assertFalse(activeUi.contains("project-intro"))` — the intended IA
  already removed the element; the test enforces its absence.
- Legacy `chat_ui_stream_contract_tests.js` still asserts its presence.
- Both cannot pass simultaneously — the legacy script encodes the retired
  two-column layout.
- Pre-change reproduction: `docs/diagnostics/autograde-b-continue-r2-0928.md`
  line 54/65 records the identical failure when the harness read the **preserved
  preimage** files — no template change was made by R2/R3.

## Recommendation

Do **not** patch product UI and do **not** count this in R3 Done. Separate
ticket: retire or update the stale asserts (`chat_ui_stream_contract_tests.js`
~1490–1494 DOM order; re-check downstream CSS asserts after the element
question is decided).
