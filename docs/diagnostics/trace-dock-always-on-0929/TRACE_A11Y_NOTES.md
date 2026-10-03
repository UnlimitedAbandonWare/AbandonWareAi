# TRACE_A11Y_NOTES — always-on dock accessibility (Devin assist rail)

Track TRACE only. Product DOM/JS implementation is Codex-owned; this note is the
guardrail + the honest NOT_RUN record.

## Rules (from Codex contract §8)

- **Do NOT reuse `markChatDiagnosticNode`** (`chat.js:2848-2851` sets
  `aria-hidden="true"`; call sites :2052/:2332/:2844/:3383/:3450/:3694/:3705/:4390/
  :5659/:5704/:6089). That helper is for *diagnostic* nodes — the always-on dock is
  a normal feature region and must stay in the accessibility tree.
- ON: no `aria-hidden` / `inert` / `hidden` / `display:none` on dock roots or
  ancestors. Both docks get `role=region`/`complementary` + accessible names
  `이전 대화의 기억·트레이스 요약` / `현재 실행 트레이스`, and stable selectors
  `data-testid=trace-dock-history|trace-dock-current|trace-dock-toggle`.
- OFF: remove from visual **and** a11y tree (`inert`/`hidden` is correct there) and
  stop optional diagnostic fetches — OFF ≠ cancel generation/search/memory.
- Render structured state as **real text** in the new regions — no cloning of hidden
  diagnostic DOM. Visible text and `data-*` attrs for run/request/session ids and
  updatedAt must agree.
- `aria-live=off` is acceptable (still in a11y tree); keep diagnostics out of the
  answer live region, TTS, clipboard answer, and transcript export.
- No canvas/image/closed-shadow-only rendering for the readable summary.

## Measurement status

- Real browser a11y snapshot / role-locator check: **NOT_RUN** in this session
  (assist rails only — do not claim verified). Codex proves it via the project's
  installed browser automation (`TraceDockAccessibleSnapshot`-class test), not
  screenshots alone.
- Interview-demo branch (`demo.interview.enabled`) runtime value: NOT_RUN here.
