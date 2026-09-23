# Conversation-First Chat Operator Console Postprocess Design

Date: 2026-07-23

Status: approved for implementation on 2026-07-24

## Goal

Modernize the legacy `/chat-ui` surface into a conversation-first operator console while preserving its evidence, cancellation, accessibility, and Desktop Safe Patch contracts. Fix the reproduced state-coherence defect where an active client wait is shown as `Model wait: WARN` in the Debug heartbeat while the primary Health pill still says `Live OK`.

The finished surface must make the chat, current response state, and composer immediately understandable. Detailed diagnostics remain available on demand instead of occupying most of the first viewport. Existing two-SUPPORT, one-FALSIFY, and neutral adjudication remains demand-driven and protected; normal chat must not gain an always-on four-call ensemble.

## Current Evidence

- Canonical root: `C:\AbandonWare\demo-1\demo-1\src`
- Branch: `main`
- A zero-byte stale index lock was found during the 2026-07-24 continuation. It had exclusive access and no active Git process, was removed under the user's prior unlock authorization, and was confirmed absent before edits resumed.
- Active top-level PatchDrop patches: 0
- Target UI files and the Node browser contract are currently untracked. `ChatFrontendSecurityTest.java` is already modified. Implementation must preserve these files as the user-approved current baseline and must not stage unrelated work.
- The current mobile layout intentionally puts `.chat-area-wrapper` before the intro and controls using CSS `order` while the DOM remains intro, controls, chat. This preserves chat-first visual placement but creates a different keyboard and screen-reader order.
- The 639 px browser view exposes at least the core rail, seven status pills, multiple evidence cards, a large empty transcript, the project introduction, and controls as separate stacked regions.
- Two live randomized chat probes reproduced the same inconsistency: after about 20 seconds the route showed `client-wait`, the Debug heartbeat showed `Model wait: WARN`, and Health still showed `Live OK / external proof supporting`.
- Stop remained a successful terminal action: route `server cancel`, trace `cancelled`, and Debug heartbeat `Response stopped: OK`. Cancellation must not be reclassified as a model failure.
- The full Java UI class and broad Gradle ladder passed on the current source, but `node scripts\chat_ui_stream_contract_tests.js` reproduced a separate RED fixture conflict: the Node contract still required the superseded `-40px`/`overflow:hidden` CSS while the browser-proven source uses `-20px`/`overflow:clip`. Reconcile that test-only contract before any new CSS work.
- The live 18166 and 8081 servers serve older build artifacts. They prove the behavior exists in deployed local artifacts but cannot prove a current-source fix.
- `EvidenceGroundedTriadicDebugAdjudicator`, `DiverseSamplingOrchestrator`, and `EnsembleJudgeService` already provide protected, advisory SUPPORT/FALSIFY/neutral decisions. The normal `ChatWorkflow` intentionally treats candidate dossiers as untrusted references and does not add a separate ensemble judge.
- Supabase remains read-only evidence debt: CLI missing, MCP reachable with HTTP 403, and `project_ref` missing. Browser and Computer are supporting evidence lanes.

These observations are an intake snapshot. Implementation must recheck the live files and target hashes before editing.

## Approaches Considered

### A. Conversation-first operator console — selected

Keep the chat as the first mobile interaction surface, place response settings next to the conversation, show only the compact core state by default, and move detailed operational evidence into native disclosure regions. Add an empty-state prompt inside the chat experience and modernize spacing, typography, color, and touch targets.

This improves the information hierarchy without deleting evidence or replacing existing selectors. It requires intentional HTML and CSS contract updates plus the focused Health-state JavaScript fix.

### B. Cosmetic-only refresh — rejected

Change colors, radii, and spacing while retaining the current hierarchy. This is lower risk but leaves the keyboard/visual order mismatch, the diagnostics-heavy first viewport, the disconnected controls, and the empty transcript experience intact.

### C. Diagnostics-first maintenance — rejected

Fix only the Health false-green and preserve the dense console. This addresses correctness but does not satisfy the approved UI/UX objective.

## Scope

### In scope

1. Preserve chat-first behavior while aligning DOM, visual, keyboard, and screen-reader order.
2. Put the existing Model, Search, RAG, and New chat controls in a native `Response settings` disclosure adjacent to the chat.
3. Keep the compact core status and primary Health state visible; group secondary heartbeat, matrix, cockpit, mission, flow, proof, and brain-state details in a native `Diagnostics` disclosure.
4. Add a compact chat empty state containing the existing quick-prompt behavior. It disappears when a message exists and returns after New chat.
5. Modernize spacing, typography, colors, borders, radii, shadows, and touch targets without adding a frontend framework.
6. Fix client-wait versus Health state coherence while preserving successful Stop semantics.
7. Verify or refine the existing protected triadic prompt roles without adding a parallel adjudicator.
8. Exercise randomized hypothetical and fact-check scenarios in a current-source Browser run.

### Out of scope

- Replacing the Spring/Thymeleaf UI with React, Next.js, or another framework
- Adding a new agent broker, daemon, event store, ensemble framework, or SMB/PatchDrop service
- Enabling triadic sampling on every normal chat request
- Changing `PromptBuilder.build(PromptContext)` ownership
- Renaming or removing existing public debug fields, test IDs, or evidence schemas
- Supabase SQL, schema, RLS, Auth, migration, Edge Function, or data mutation
- Computer automation as proof of source correctness
- Deleting PatchDrop janitor or Desktop final-proof gates
- Restyling protected admin pages in this pass

## Design

### 1. Conversation-first document structure

The chat workspace becomes the first meaningful region in DOM order and the leading visual region at every viewport. On desktop it occupies the leading, wider grid column while the project introduction occupies the trailing context column; on mobile the chat remains above the context. The public header retains the product identity and model badge. Protected operator links move into a compact `Admin tools` disclosure so they remain available without dominating the primary navigation.

The chat workspace contains, in this order:

1. compact conversation header and core status;
2. `Response settings` disclosure containing the existing controls;
3. always-visible primary status rail, including Route, Model, Context, RAG, Trace, Quality, and Health;
4. `Diagnostics` disclosure containing secondary operational panels;
5. empty state when there are no messages;
6. transcript;
7. sticky composer and Stop control.

Existing element IDs, `data-testid` values, `.qa[data-q]`, form names, labels, `role="log"`, and public URLs remain stable unless a test proves a change is necessary. DOM movement must not duplicate controls or create hidden focusable copies.

### 2. Response settings disclosure

Use native `<details>` and `<summary>` rather than a custom disclosure widget. Model, Search, RAG, and New chat keep their current elements and event listeners. The summary exposes a compact current value such as the selected model plus `Search OFF` and `RAG OFF`; JavaScript may update this text using the existing control-change events.

The disclosure is closed by default at every viewport, and its summary plus the status rail expose the selected values. The implementation uses one deterministic DOM and never creates viewport-specific duplicate controls.

### 3. Diagnostics disclosure

The core status rail and Health pill remain visible at all times. Secondary evidence moves under one native `Diagnostics` disclosure while preserving the underlying DOM nodes and update functions. The disclosure summary shows only bounded counts and the highest current severity, not raw prompts, queries, responses, errors, tokens, URLs, or environment values.

Opening Diagnostics reveals the existing Debug heartbeat, matrix, cockpit, mission, flow, proof, external evidence, and brain-state panels. No data source or backend API changes are required for this layout. Hidden-by-disclosure content must continue receiving state updates so opening it shows current data.

### 4. Empty state and quick prompts

Add a sibling empty-state region immediately before `#chatWindow`. It contains a short description and the existing `.qa[data-q]` operational-stability action plus two bounded scenario starters:

- `Compare claims`: fill a prompt that requests two independent SUPPORT arguments, one FALSIFY argument, and a neutral HOLD decision.
- `Verify facts`: fill an explicit web fact-check prompt while leaving the Search selector authoritative.

Quick actions only fill the composer and update existing local control state. They do not send automatically, enable RAG, change Search mode, or trigger external calls without the user pressing Send. The empty state hides once the transcript contains a user or assistant message and returns after New chat. This may use a small explicit class managed by existing transcript lifecycle code or a supported `:has()` selector; the deterministic contract is more important than avoiding one small JavaScript hook.

### 5. Visual system

Keep the existing bright operator console and refine it into a calm premium surface:

- use a warm-neutral page canvas, a crisp white primary workspace, and one restrained forest-teal accent;
- use only two elevation levels: a soft workspace shadow and a quieter raised-control shadow; avoid ornamental glass, glow, and decorative gradients;
- use consistent 12–16 px component radii, one 22 px workspace radius, and an eight-point spacing rhythm;
- strengthen typography with a compact display heading, readable body text, sentence-case labels, and tabular numerals for bounded operational metrics;
- reduce repeated borders and card chrome while keeping state boundaries and diagnostic groupings explicit;
- keep minimum 44 px pointer targets for interactive header, disclosure, New Chat, and composer controls;
- keep the composer reachable inside the chat surface, with visible focus and sufficient contrast;
- express severity with bounded text or icons as well as color;
- preserve the proven `overflow: clip` wrapper and internally scrolling transcript so New Chat and the composer cannot collapse or leave the viewport;
- allow an opened native disclosure to expand the document instead of clipping its contents;
- prevent horizontal overflow at 320, 375, 390, 639, 760, 1024, and 1440 px widths;
- preserve reduced-motion support for transitions.

The design must not hide operational warnings or turn missing external proof into success. It changes priority and presentation, not truth.

### 6. Health state coherence

Introduce one current-turn Health overlay owned by the existing client stream lifecycle. It contains only a bounded state kind and safe detail string; it does not store response content.

State mapping:

| Client state | Primary Health label | Severity | Meaning |
| --- | --- | --- | --- |
| connecting or ordinary sub-threshold stream | Responding | OK | request is active and has not crossed the wait threshold |
| `model_wait` or stale `client-wait` | Response pending | WARN | the model has not produced new data within the existing stale threshold |
| `cancelled` or `stopped` after successful Stop | Response stopped | OK | user-requested terminal state, not a runtime failure |
| deadline, timeout, terminal SSE error, or send failure | Needs attention | WARN or FAIL | existing error contract remains authoritative |
| accepted final or successful sync fallback | server aggregate Health | existing aggregate | current-turn overlay is cleared |

`renderLiveDebugHeartbeat()` updates the overlay whenever the local stream state changes. `renderDebugHeartbeat()` applies the active overlay after processing the 30-second server payload so a stale server aggregate cannot overwrite current client truth. New chat, a new accepted send, a successful final, and a successful sync fallback clear the prior overlay at their existing lifecycle boundaries.

`compactHealthRailDetail()` recognizes explicit `state:responding`, `state:pending`, and `state:stopped` before the generic `live:OK` branch. External proof remains `supporting`; the fix must not turn `project_ref_missing`, Browser, Computer, Mac mini, or Notebook evidence into runtime failure.

Late token/final events after Stop remain governed by the existing active-stream target and terminal-latch rules. A late event must not clear `Response stopped` unless it is accepted by the current stream lifecycle.

### 7. Triadic prompts and neutral adjudication

Reuse the existing protected triadic path. The intended role contracts are:

1. `SUPPORT`: identify evidence-backed reasons the candidate or claim could be correct, cite only supplied evidence IDs, and state uncertainty.
2. `SUPPORT_ALTERNATIVE`: independently support the claim through a materially different causal path and avoid copying the first SUPPORT rationale.
3. `FALSIFY`: seek the strongest counterexample, boundary condition, missing evidence, or safety regression and cite only supplied evidence IDs.
4. `NEUTRAL`: compare the three dossiers against the evidence table, reject unsupported claims, and return `APPLY`, `HOLD`, or `REJECT` under the existing strict parser.

The neutral result is not majority voting. Missing evidence, malformed output, unresolved contradiction, missing role, or insufficient score gap remains `HOLD`. The result remains advisory, admin-triggered, disabled by default, redacted, and incapable of applying source changes.

Normal `/chat-ui` prompts may ask the primary model to format a hypothetical answer as SUPPORT/FALSIFY/HOLD, but that formatting request must not be represented as the protected multi-agent adjudicator having run.

In addition to the protected runtime path, ship one standalone Korean Codex prompt artifact for manual copy/paste review. It must:

- accept exact claims plus SUPPORT, SUPPORT_ALTERNATIVE, and FALSIFY dossiers;
- normalize them into claim, evidence ID, counterexample, coherence, and missing-proof rows;
- decide only `APPLY`, `HOLD`, or `REJECT`, never by candidate count or confidence tone;
- force `HOLD` for a missing role, missing same-input RED/GREEN evidence, unresolved contradiction, unavailable external scope, or malformed evidence ownership;
- return at most three accepted fixes, at most three unresolved risks, and exactly one next proof;
- distinguish a manual Codex review from the protected runtime adjudicator and never imply that the latter ran;
- keep raw prompts, queries, model answers, credentials, and full provider errors out of the report.

The standalone artifact remains intentionally unregistered unless a live manifest contract proves registration is required.

### 8. Hypothetical and web fact-check scenarios

Browser verification uses three bounded classes:

1. a purely hypothetical policy scenario, which must label assumptions and compare support with counterexamples;
2. a stable factual scenario with an expected source-backed answer;
3. a current or uncertain factual scenario that explicitly requests web verification and must return citations or honest `evidence_needed`/HOLD rather than fabricated certainty.

Web lookup remains demand-driven. `Search OFF` never triggers a provider call. AUTO/LIGHT/DEEP and the existing provider, citation, timeout, and fail-soft gates remain authoritative. Unknown or inaccessible facts are reported as HOLD/evidence-needed. Raw queries, prompts, snippets, and full provider errors stay out of public diagnostics.

### 9. External evidence boundaries

- Browser is required final evidence because HTML, CSS, visible state, streaming, Stop, and responsive behavior change.
- Computer remains supporting-only; it may confirm the Windows app is visible but cannot prove source correctness.
- Supabase remains read-only. Missing project scope or auth is reported as `evidence_needed` and does not block Desktop-only UI completion.
- Mac mini, Notebook, SMB, and PatchDrop producer proof remain manual supporting lanes and are not requested by this patch.

## Error Handling and Rollback

- Native disclosures remain usable without JavaScript; chat submission retains the existing JavaScript requirement.
- Missing optional diagnostics data continues to render a safe WARN/evidence-needed state.
- Client wait never becomes fake OK, while successful user cancellation never becomes fake failure.
- The UI does not expose raw error bodies, prompts, queries, credentials, Authorization headers, cookies, database URLs, or environment dumps.
- If the layout regression cannot be contained to the approved active files and focused tests, stop with `worktree-overlap` rather than rewriting adjacent pages.
- Rollback restores the prior HTML grouping, CSS tokens, and Health overlay branch while leaving existing stream, cancellation, and backend schemas unchanged.

## TDD and Verification Plan

Implementation follows RED, GREEN, then current-source Browser proof.

### Focused RED contracts

1. At the existing 65-second no-data fixture, Health is WARN and says `Response pending`, with raw detail containing `stream:model_wait` and `client-wait:65000ms`.
2. A 30-second backend heartbeat cannot overwrite an active pending overlay with `Live OK`.
3. Successful Stop shows `Response stopped`, keeps the heartbeat cancellation status OK, and never says `Model failed` or `Live OK` for the ended turn.
4. Late token/final events after Stop do not clear the stopped state.
5. Accepted final, sync fallback, New chat, and the next accepted send clear the prior overlay at the correct boundary.
6. DOM and mobile visual order both start with the conversation workspace.
7. Response settings and Diagnostics are native disclosures with accessible names and no duplicated IDs.
8. Existing Model/Search/RAG/New chat, `.qa[data-q]`, composer, Stop, history hydration, protected-link, evidence-card, and debug selectors still work.
9. The empty state hides after a message and returns after New chat.
10. The layout has no horizontal overflow and preserves 44 px interactive targets at the agreed viewport matrix.

### Verification commands

```powershell
node .\scripts\chat_ui_stream_contract_tests.js

$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop-chat-console"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop-chat-console"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop-chat-console"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null

.\gradlew.bat chatUiTest --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
```

Run a changed-file secret scan that reports count only. Run broader Java compilation only if implementation evidence proves the HTML/JS/CSS changes cross a Java contract not already covered by `chatUiTest`.

### Current-source Browser proof

Build or copy the changed resources into an isolated Desktop output and start one new server on a free port. Do not claim the older 18166/8081 servers as current-source proof. Verify:

- desktop and 320/375/639/760 px responsive layouts;
- keyboard order, disclosure names, focus visibility, and composer reachability;
- empty state, quick-prompt fill, New chat, and settings persistence;
- ordinary short response, stale wait, Stop, follow-up send, and reload continuity;
- the three hypothetical/factual scenario classes;
- visible Health/heartbeat agreement throughout each lifecycle.

Browser evidence records selectors, visible bounded text, counts, viewport, and current artifact identity. It does not persist raw prompts or full model responses in public debug artifacts.

## Acceptance Criteria

- The chat and composer are the first clear task at mobile and desktop widths.
- DOM, visual, keyboard, and screen-reader order are coherent.
- Detailed diagnostics are available but collapsed by default; core Health remains visible.
- Current-turn client wait cannot coexist with a primary `Live OK` label.
- Successful Stop remains an OK terminal action labeled `Response stopped`.
- Server polling cannot overwrite an active client-state overlay.
- Existing public selectors, IDs, event wiring, evidence truth, and secret-safety contracts remain intact or have focused compatibility assertions.
- Existing protected SUPPORT, SUPPORT_ALTERNATIVE, FALSIFY, and NEUTRAL adjudication is reused without adding always-on normal-chat calls.
- Fact checking is demand-driven and fail-soft; unknown facts become HOLD/evidence-needed rather than fabricated answers.
- Node and Gradle UI gates pass with real output.
- A new current-source Browser run proves responsive UI, streaming, Stop, follow-up, and randomized scenario behavior.
- Changed-file secret-pattern hits are zero.
- No Supabase mutation, SMB service, producer dispatch, new framework, or unrelated dirty-worktree change is introduced.

## Known Limitations

- Native `<details>` styling differs slightly across browsers; the design prioritizes semantics and predictable keyboard behavior over pixel-identical disclosure chrome.
- Local model latency can still produce legitimate `Response pending`; this patch makes that state honest but does not improve model throughput.
- The protected triadic adjudicator remains admin-only and disabled by default. Public chat scenario formatting is not equivalent to a protected multi-agent decision.
- Live Supabase proof remains unavailable until project-scoped read-only auth exists; it is not required for this Desktop-only UI patch.
