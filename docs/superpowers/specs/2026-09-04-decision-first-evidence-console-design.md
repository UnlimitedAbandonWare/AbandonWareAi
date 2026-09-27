# Decision-first Evidence Console Design

Date: 2026-09-04  
Status: Written specification approved by the user on 2026-09-04. Implementation planning is authorized; skill authoring and source mutation remain gated by the approved plan, RED evidence, and repository preflight.  
Target: Desktop canonical checkout at `C:\AbandonWare\demo-1\demo-1\src`

## Decision

Refine the existing conversation-first legacy `/chat-ui` into a decision-first evidence console. The change makes the current answer's evidence relationship visible, presents one authoritative diagnostic or recovery summary before raw diagnostics, and renders an observed turn-decision ribbon inside the existing core status rail.

This is a delta over the approved conversation-first console. It is not a replacement layout, a second diagnostics system, a new routing engine, or a new telemetry store.

## Context and current evidence

The active browser surface is the Spring-served legacy `/chat-ui`, owned by:

- `main/resources/templates/chat-ui.html`
- `main/resources/static/js/chat.js`
- `main/resources/static/css/chat-style.css`

A fresh local runtime returned HTTP 200 and served the same `chat.js` asset bytes as the current source. The source and served asset SHA-256 were both:

`3433a089779b404f73b6c7190375125c7a8bfa4c0d02b3de83c4ac147aaa98e3`

Current source and Browser observations establish two design gaps:

1. The client computes `searched-but-uncited`, `partially-cited`, and `cited`, but a sighted user sees only the Evidence count. The more informative state is limited to metadata and accessible labeling.
2. Opening Diagnostics presents heartbeat, matrix, cockpit, mission, flow, and proof surfaces with insufficient priority separation. The highest-value current state and safe next action are not visually dominant.

One browser scenario remained pending for 75,007 ms and then reached the existing `server cancel` / `Response stopped` terminal state after user cancellation. No model answer or provider attempt was observed. This proves the inspected cancellation presentation, not provider generation or semantic-answer success.

The intended UI files and focused test scripts are currently dirty or untracked. No source edit is allowed until their exact preimages and ownership are rechecked and the repository source-edit gate returns a stable APPLY result.

## Relationship to existing designs

This specification extends, and must not duplicate, these existing contracts:

### Conversation-first operator console

`docs/superpowers/specs/2026-07-23-conversation-first-chat-operator-console-design.md` remains authoritative for:

- conversation-first DOM and visual hierarchy;
- Response settings and Diagnostics disclosures;
- the core status rail and Health overlay;
- `Response pending` and `Response stopped` semantics;
- quick prompts, transcript, and composer placement;
- stream, cancel, reload, accessibility, and responsive-browser proof.

This design adds only the evidence taxonomy, the Decision ribbon presentation, and the refined primary Diagnostics summary.

### Owned-runtime browser restart

`docs/superpowers/specs/2026-08-10-owned-runtime-browser-restart-design.md` remains authoritative for:

- starting or reusing an attributable local runtime;
- protected-port and foreign-process handling;
- source-versus-served asset hashing;
- runtime manifests and `browserTargetUrl`;
- Browser handoff to the exact fresh surface.

This design consumes that proof path. It does not modify runtime lifecycle scripts, process ownership, port allocation, or runtime manifests.

## Goals

1. Make evidence quality visible without overstating retrieval, citation, provider, or model success.
2. Put one current, authoritative diagnostic or recovery state before lower-value raw telemetry.
3. Show the observed current-turn path from route through recovery without creating a new decision authority.
4. Preserve the existing conversation-first layout, Health semantics, stream lifecycle, cancellation, reload continuity, keyboard behavior, accessibility, and responsive geometry.
5. Encode the new UI acceptance method in one narrow repository-local skill that cannot grant source or external mutation authority.
6. Support the named first-wave browser scenario deck with reproducible RED/GREEN evidence rather than one-off aesthetic judgment, while preserving the larger active goal for later bounded waves.

## Non-goals

The implementation must not:

- change retrieval, reranking, CitationGate, provider selection, model routing, PromptBuilder, or prompt content;
- add or change a backend API, SSE event, persistence schema, TraceStore, DebugEventStore, or database;
- create a second Health overlay, a second diagnostics dashboard, or a competing status rail;
- infer a provider call, successful generation, citation correctness, or whole-chatbot correctness from HTTP 200, delivery, heartbeat, asset hashes, or cancellation;
- treat local/history fallback as external evidence;
- alter protected SUPPORT/FALSIFY/NEUTRAL adjudication;
- change Supabase state or require Supabase proof for this UI-only decision;
- add a production dependency, new UI framework, icon package, font package, or animation library;
- redesign Response settings, quick prompts, transcript ownership, or composer behavior;
- modify `chat_ui_vibe_listener.ps1`, `chat_ui_vibe_soak.ps1`, runtime ownership, or port policy;
- use elapsed time alone as proof that a provider timed out or failed.

## Experience principles

### Evidence before ornament

Color, shape, motion, and placement express observed state. They must never manufacture confidence or causality. Missing evidence is displayed as missing evidence.

### One answer to each operator question

The console should let a user answer these questions in order:

1. What is happening now?
2. What evidence relationship does the current answer have?
3. Is there a safe action I can take?
4. Which observed details support that summary?

### Progressive disclosure

The Decision ribbon is the only current-turn summary visible without opening Diagnostics. The primary diagnostic/recovery summary is rendered as the first content inside the existing Diagnostics disclosure; it does not duplicate or override the ribbon or Health overlay. Raw heartbeat and subsystem panels remain available below it.

### Fail-soft honesty

`not observed`, disabled, failed, empty, uncited, cancelled, and successful are distinct states. A weaker state must not be upgraded for visual neatness.

### Emergence from real state

The interface may reveal a route-to-recovery pattern only from observed current-turn values. It must not draw decorative stages that imply work happened.

## Information architecture

The existing conversation-first page remains the shell. The refined information hierarchy is:

1. Header.
2. Existing core status rail, containing its current status fields plus the compact Decision ribbon as a display-only subcomponent.
3. Conversation transcript and current answer.
4. Per-answer Evidence count plus visible evidence-quality badge.
5. Existing composer and quick prompts.
6. Existing Diagnostics disclosure containing:
   - one primary current diagnostic/recovery summary;
   - supporting observed facts;
   - existing detailed heartbeat, matrix, cockpit, mission, flow, and proof panels.

No second sidebar or persistent workbench is added.

## Component design

### Evidence-quality presenter

`chat.js` will normalize already available current-answer metadata into a display-only view model. The presenter must not change retrieval or answer data.

The output contract is:

```text
evidenceQuality.code
evidenceQuality.visibleLabel
evidenceQuality.accessibleLabel
evidenceQuality.tone
evidenceQuality.evidenceCount
evidenceQuality.detail
evidenceQuality.sourceBasis
```

`visibleLabel` and `accessibleLabel` must communicate the same state. `detail` may add bounded explanation, but may not contain raw provider errors, raw queries, credentials, or unredacted model content.

The display taxonomy is:

| Display code | Visible label | Required basis | Forbidden interpretation |
|---|---|---|---|
| `citation-backed` | 인용 확인됨 | Existing authoritative `cited` state | Does not prove every answer claim is correct |
| `partial-citation` | 부분 인용 | Existing authoritative `partially-cited` state | Does not imply uncited claims are supported |
| `retrieved-uncited` | 검색됨 · 미인용 | Existing authoritative `searched-but-uncited` state | Does not imply search evidence supports the answer |
| `external-unavailable` | 외부 근거 사용 불가 | Existing authoritative disabled or failure reason that does not assert a successful attempt | Does not prove which provider was attempted |
| `not-observed` | 근거 상태 관측 안 됨 | No mutually exclusive authoritative mapping is available | Must not be upgraded from count, HTTP, heartbeat, or delivery alone |

Precedence is deterministic:

1. Use an authoritative current-answer citation relationship when present.
2. Otherwise use an authoritative disabled/failure classification when present.
3. Otherwise use `not-observed`.

Local documents, history, cache, or fallback origin may be shown as a separate bounded origin note. They do not promote the external evidence-quality state.

If source characterization cannot safely distinguish `external-unavailable` from `not-observed`, implementation must collapse both to `not-observed`. It must not create new backend fields merely to satisfy the visual taxonomy.

### Decision ribbon

The Decision ribbon is a compact, display-only subcomponent of the existing core status rail. It consumes current-turn state already held by the page. It does not route work and does not persist a second state model.

The conceptual stages are:

```text
Route -> Context -> Retrieve -> Evidence -> Answer -> Recover
```

Each stage renders one of:

- `observed-active`
- `observed-complete`
- `observed-degraded`
- `observed-cancelled`
- `not-observed`

Rules:

- A stage becomes complete only from an existing authoritative current-turn signal.
- Missing values render `not-observed`; stages are never auto-filled to make the ribbon continuous.
- `Response pending` keeps Answer active and preserves the existing Stop affordance.
- User cancellation routes the presentation to Recover/Cancelled and must not render Answer as successful.
- A late token, heartbeat, or final event must not overwrite an established stopped or error terminal state.
- The ribbon may summarize a protected adjudication only if the existing UI already carries direct evidence of that run. It must never imply SUPPORT/FALSIFY/NEUTRAL execution from generic diagnostics.
- The ribbon must not conflict with or override the existing Health overlay. Health remains authoritative for the current user-visible response state.

The ribbon is intentionally compact. It does not expose raw timing, provider errors, prompts, or full trace payloads.

### Primary Diagnostics summary

The existing Diagnostics disclosure gains one primary summary before its detailed panels. The summary answers:

```text
What happened?
What observed facts support it?
What safe action is available?
```

A recovery action may be shown only when its source is one of:

1. an existing allowlisted current-turn action;
2. an existing safe user control such as Stop, retry, or open details;
3. a fixed client label mapped to an authoritative reason code.

It must not generate recovery instructions from a raw error string.

Candidate summaries are ordered by:

1. current-turn terminal error or cancellation state;
2. current actionable pending state with an existing user control;
3. current authoritative degraded evidence or provider-disabled state;
4. current neutral or healthy state;
5. `not-observed` when no authoritative summary exists.

Tie-breaking is deterministic:

1. current session and current turn before historical state;
2. explicit terminal state before advisory state;
3. existing severity before presentation order;
4. stable component order when severity is equal;
5. `not-observed` rather than an inferred winner when currentness cannot be established.

Detailed heartbeat, matrix, cockpit, mission, flow, and proof content remains available below the summary. The implementation may reduce their visual weight but must not silently delete diagnostic facts or selector contracts.

### Supporting facts

The primary summary may show a maximum of three compact supporting facts drawn from allowlisted, redacted current-turn fields. Additional facts remain in the existing detailed panels.

Supporting facts must identify their evidence class, for example:

- `route observed`
- `citation relationship observed`
- `user cancelled`
- `provider attempt not observed`

They must not flatten these into a single confidence score.

## State and data flow

Before production editing, the implementation plan must include a source-to-state mapping table for every evidence-quality code and Decision-ribbon stage. Each row must name:

- the exact existing client-side input and code owner;
- the rule that binds the value to the current session and turn;
- the terminal-latch rule where the state can outlive later events;
- the mandatory `not-observed` fallback.

A state without a proven mapping row renders `not-observed`. No new backend field or inferred client state may be introduced to fill a mapping gap.

The implementation remains client-side and presentation-only:

```text
existing HTTP/SSE/DOM state
  -> existing chat.js state handling
  -> pure evidence-quality and diagnostic-summary presenters
  -> existing template containers
  -> visible text + matching accessible semantics
```

The preferred code boundary is a small pure normalization function for evidence quality and a small pure selector for the primary diagnostic. DOM rendering consumes their outputs. Stream lifecycle ownership stays in the current handlers.

The implementation must not introduce a general state-management framework or duplicate the current event reducer.

## Visual system

### Hierarchy

- Keep the existing conversation workspace and page shell, retaining the July-approved warm-neutral page canvas and crisp white primary workspace.
- The Decision-first refinement may adjust hierarchy, semantic accents, and diagnostic density, but must not introduce a dark-workspace redesign.
- Use typography, spacing, and grouping before adding color.
- Give the current primary diagnostic one clear visual level above supporting facts.
- De-emphasize repeated initial WARN/loading cards without hiding their content.
- Keep transcript and composer visually dominant over operational detail.

### Color semantics

Color is constrained to semantic roles:

- verified/available: restrained cyan or teal;
- incomplete/advisory: amber;
- error/cancelled: red used with text and iconography;
- not observed/unknown: neutral gray.

No state may rely on color alone. Existing theme variables should be reused or minimally extended.

### Motion

- Motion may explain a real transition between observed states.
- No continuous shimmer, pulse, or animated route should imply hidden work.
- Pending animation must stop at terminal state.
- `prefers-reduced-motion` must remove nonessential transitions.

### Density

- The visible evidence badge stays legible beside the Evidence count.
- The Decision ribbon uses compact stage labels and may horizontally scroll only when it remains keyboard and touch usable.
- Raw Diagnostics remain secondary and bounded by the existing disclosure.

`No clipping` means that no label is truncated, obscured, or unreachable. At widths where the ribbon scrolls, every stage must remain reachable by keyboard and touch, expose its complete text when focused or selected, and avoid page-level horizontal overflow.

## Responsive behavior

The implementation must preserve the established responsive geometry and add these requirements:

- At desktop width, the Decision ribbon may render horizontally inside the core status rail.
- At tablet and narrow widths, it may wrap into two compact rows without increasing transcript/composer overlap.
- At short-phone widths, it becomes a vertical or horizontally scrollable observed-state list with no clipped labels.
- The evidence badge may wrap below the Evidence count, but must remain associated with the same answer.
- The primary Diagnostics summary appears before detailed panels at every viewport.
- Composer controls retain at least the existing touch-target size.

The Browser matrix must include the existing representative widths:

- 320x760
- 375x900
- 390x700
- 639x900
- 760x900
- 1024x900
- 1440x900

Every viewport must prove no horizontal page overflow, no transcript/composer overlap, reachable controls, and readable badge/ribbon text.

## Accessibility

- Visible evidence labels and accessible labels must agree semantically.
- New state changes must use the existing live-region strategy without announcing raw diagnostic detail.
- A pending update must not repeatedly announce unchanged elapsed time.
- Stop remains keyboard reachable while a response is pending.
- Focus after cancellation returns to the existing intended composer flow.
- Disclosure buttons retain native expanded/collapsed semantics.
- The Decision ribbon uses meaningful text; decorative connectors are hidden from accessibility APIs.
- Cancellation, error, incomplete evidence, and `not-observed` are expressed with text in addition to color.
- DOM, visual, keyboard, and screen-reader order remain coherent.

## Error and recovery behavior

The presentation distinguishes:

| Condition | Required presentation |
|---|---|
| Request active | `Response pending`; Stop remains available |
| Client wait threshold reached | Current wait state plus existing safe wait/stop choices; no provider timeout claim |
| User cancellation | `Response stopped`; Cancelled terminal ribbon state; no success state |
| Provider disabled | Disabled only when an authoritative reason exists |
| Provider attempt absent | `not observed`, not success or failure |
| Search returned evidence but answer did not cite it | `검색됨 · 미인용` |
| Partial citation relationship | `부분 인용` |
| No authoritative evidence mapping | `근거 상태 관측 안 됨` |
| Late event after terminal state | Preserve terminal state; do not resurrect pending or success |
| Restored stopped turn | Exactly one stopped assistant outcome with consistent Health/ribbon state |

The UI must remain usable when evidence and diagnostic presenters receive absent, malformed, or partial optional data. Presentation failure must fall back to existing Health and transcript behavior.

## Repository-local acceptance skill

Create one narrow skill at:

`.agents/skills/demo1-chat-design-acceptance`

Its trigger is a proposed or implemented legacy `/chat-ui` change involving visible evidence relationship, diagnostic priority, Decision ribbon state, stream/cancel accessibility, or responsive Browser proof.

Its non-triggers are:

- generic long-tail chatbot evaluation;
- protected triadic adjudication design;
- provider, prompt, retrieval, or backend algorithm changes;
- runtime process ownership;
- Supabase or database work;
- source mutation authorization.

The skill complements `demo1-agentic-chat-postprocess`; it does not restate its three-role review or final-report schema. It must explicitly retain:

- current-source and served-asset provenance;
- honest `not_observed` semantics;
- same-input RED/GREEN for a claimed UI fix;
- live Browser proof for visible or interaction changes;
- supporting-only Computer evidence;
- dirty-target ownership and source-edit gate boundaries;
- no whole-chatbot, model, provider, or Supabase success claim from local UI proof.

Skill creation follows documentation TDD:

1. Run no-guidance pressure scenarios before the skill exists.
2. Capture exact baseline failures or rationalizations.
3. If the no-guidance control already makes every required decision, stop and do not create a redundant skill.
4. Otherwise initialize the minimum skill with `skill-creator`.
5. Write only guidance that corrects observed failures.
6. Run the same scenarios with the skill.
7. Refactor only for observed loopholes.
8. Validate structure and metadata with `quick_validate.py` under UTF-8.

The pressure scenarios must test at least these temptations:

- using Evidence count as proof of citation quality;
- elevating stale or inferred diagnostics to a recovery command;
- claiming provider or model success from HTTP 200, asset match, heartbeat, or cancellation;
- skipping responsive or cancellation proof under deadline pressure;
- treating the skill itself as source-edit authority.

The skill remains one self-contained `SKILL.md` plus `agents/openai.yaml` unless observed RED behavior proves a focused reference is needed. It adds no script by default.

## Files in the intended implementation scope

Expected UI files:

- `main/resources/templates/chat-ui.html`
- `main/resources/static/js/chat.js`
- `main/resources/static/css/chat-style.css`

Expected proof files:

- `scripts/chat_ui_stream_contract_tests.js`
- `scripts/chat_ui_geometry_contract_tests.js`
- existing focused Java/Gradle UI contracts only when their current assertions require alignment

Expected design/skill files:

- this specification
- `.agents/skills/demo1-chat-design-acceptance/SKILL.md`
- `.agents/skills/demo1-chat-design-acceptance/agents/openai.yaml`

No Java application source is expected to change. A reproducible RED naming an active Java owner plus separate approval would be required before widening into Java.

## Test design

All behavior changes follow RED-GREEN-REFACTOR.

### Evidence taxonomy contracts

Focused tests must fail before implementation and cover:

1. cited state renders `인용 확인됨` visibly and accessibly;
2. partial state renders `부분 인용`;
3. searched-but-uncited renders `검색됨 · 미인용`;
4. authoritative disabled/failure renders `외부 근거 사용 불가` without an attempt claim;
5. absent or ambiguous fields render `근거 상태 관측 안 됨`;
6. Evidence count does not promote any state;
7. local/history fallback does not become external citation proof;
8. visible and accessible meanings agree.

### Diagnostics contracts

Focused tests must fail before implementation and cover:

1. exactly one primary current summary;
2. current terminal state outranks advisory state;
3. active pending state preserves Stop;
4. stale state cannot outrank current-turn state;
5. equal candidates use deterministic ordering;
6. missing currentness or authority falls back to `not-observed`;
7. raw diagnostic detail remains available below the summary;
8. raw provider error text is not promoted into live-region or recovery text.

### Stream and persistence contracts

The existing focused stream contract must continue to prove:

- pending state;
- user Stop;
- `server cancel` / `Response stopped` terminal behavior;
- no success-like rendering when no answer exists;
- late event suppression after a terminal state;
- reload/restore of a stopped turn;
- one stopped assistant bubble rather than duplicates;
- usable composer after cancellation.

### Geometry and accessibility contracts

Browser or geometry tests must measure:

- Decision ribbon bounds;
- evidence badge association with the current answer;
- Diagnostics summary before details;
- transcript/composer non-overlap;
- page horizontal overflow;
- focus and disclosure behavior;
- narrow-width label clipping;
- reduced-motion behavior where automation can observe it reliably.

## Browser scenario deck

Browser proof uses a fresh attributable runtime and records only redacted scenario IDs and terminal states.

| Scenario ID | Input class | Required observation |
|---|---|---|
| `evidence-cited-001` | Deterministic fixture or returned cited metadata | Visible and accessible cited label agree |
| `evidence-partial-001` | Deterministic partial-citation fixture | Partial label without full-support implication |
| `evidence-uncited-001` | Retrieved but uncited fixture | Retrieved/uncited distinction visible |
| `evidence-unknown-001` | Missing optional evidence metadata | `not observed`; no inferred success |
| `diagnostic-priority-001` | Current actionable plus stale advisory state | One current primary summary; detail retained |
| `pending-cancel-001` | Safe response request followed by Stop | Pending -> stopped; no answer-success claim |
| `late-after-cancel-001` | Controlled late event after terminal state | Stopped state remains authoritative |
| `reload-stopped-001` | Reload after cancellation | One consistent stopped outcome |

If deterministic fixtures cannot reach a state without changing backend contracts, that state remains `evidence_needed`; the implementation must not fabricate it through ad hoc production hooks.

## Verification ladder

Verification proceeds from narrowest to broadest:

1. Exact target status, preimage hashes, ownership, index lock, worktrees, source-edit leases, and PatchDrop inventory.
2. Repository-required POSITIVE_QUERY, NEGATIVE_QUERY, and NEUTRAL_QUERY over one frozen source-edit EvidenceSnapshot.
3. Focused RED test for one behavior.
4. Minimal implementation and same-input GREEN.
5. `node scripts\chat_ui_stream_contract_tests.js`.
6. Focused geometry contracts against the intended runtime.
7. `gradlew.bat chatUiTest` with isolated Gradle home, project cache, and split build output.
8. `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, and affected compilation/package gates.
9. Fresh owned runtime with current source/served asset hash equality.
10. Browser matrix and scenario deck against the exact `browserTargetUrl`.
11. Count-only Computer evidence only if it changes a UI decision.
12. Final changed-file diff, focused count-only secret scan, and requirement-by-requirement audit.

A passing local UI test is not a substitute for Browser proof. Browser proof is not a substitute for provider, model, database, or semantic-answer proof.

## Sustained defect-discovery loop

This specification defines the first bounded wave of the larger requested browser investigation. This wave is limited to the eight named Browser scenarios in this specification and one same-input rerun for each reproducible defect. Only defects reproducible in the three approved UI files or their focused contracts belong to this implementation plan. Defects requiring backend, provider, runtime-lifecycle, or unrelated UI work are recorded as `evidence_needed` and may become later bounded waves under the same active goal; they do not expand this plan.

The requested long-running browser work is a bounded maximum-time investigation, not a requirement to delay after decisive proof. Within this first wave:

1. Use a varied, redacted scenario bag rather than repeating one prompt.
2. Separate visible layout, interaction lifecycle, evidence honesty, and semantic-answer observations.
3. Record scenario IDs, viewport, current asset hash, terminal state, and failure class; do not retain raw private prompts or full answers.
4. For each reproducible local defect, add a focused RED test before source repair.
5. Apply the smallest active-seam patch and rerun the same scenario.
6. Stop a lane on decisive pass, no reproducible defect, ownership conflict, unavailable required runtime, or repeated external blocker.
7. Keep the full goal active after this wave whenever later in-scope investigation or separately bounded evidence work remains.

## Rollback and preservation

The intended change is client-side and reversible.

- Preserve existing selectors unless a RED test and migration requirement prove a change is necessary.
- Do not delete raw Diagnostics content; retain it behind the existing disclosure.
- Avoid broad formatting or unrelated CSS normalization.
- Keep changes confined to exact approved hunks in dirty/untracked files.
- Record preimage hashes immediately before editing and postimage hashes after verification.
- If ownership changes or a preimage no longer matches, stop the UI mutation lane with `changed-preimage` or `worktree-overlap`.
- If the new presenter fails, existing transcript and Health behavior must remain usable.

Rollback consists of reverting only the new evidence presenter/rendering, Decision ribbon markup/style, Diagnostics summary refinement, focused tests, and new QA skill. Runtime lifecycle and backend behavior are outside the rollback surface.

## Acceptance criteria

The implementation is accepted only when all of the following are true:

1. The exact current source owner and target preimages were proven before editing.
2. The repository three-way source-edit decision was order-stable APPLY.
3. Every new behavior had an observed focused RED before production changes.
4. Every answer evidence rail visibly communicates one honest evidence-quality state.
5. Evidence count alone never upgrades citation quality.
6. Visible and accessible evidence meanings agree.
7. The Decision ribbon is part of the existing core status rail and uses only observed current-turn state.
8. The Decision ribbon never overrides the Health overlay or implies protected adjudication.
9. Diagnostics presents at most one primary current summary before existing details.
10. Recovery actions come only from allowlisted authoritative sources or existing safe controls.
11. Existing detailed diagnostics and selectors remain available.
12. Pending, cancellation, late-event, stopped, reload, focus, and composer behavior pass focused and live Browser checks.
13. All required viewport checks pass without overflow, overlap, clipping, or inaccessible controls.
14. Fresh runtime and source/served asset lineage are recorded for final Browser proof.
15. The new QA skill either demonstrates a real no-guidance RED followed by GREEN, or is not created if the control proves it redundant.
16. The new QA skill validates structurally and preserves all authorization boundaries.
17. No Java, provider, retrieval, prompt, database, Supabase, or runtime-lifecycle code changed without a separately reproduced need and approval.
18. No claim of model generation, provider wire attempt, statistical uplift, or whole-chatbot semantic correctness is made without direct evidence.
19. Final verification reports actual commands, first failure when present, changed paths, and unresolved `evidence_needed` items.
20. This first-wave implementation plan is complete only when the approved UI delta, conditional skill evaluation, Browser-visible verification, and repair of reproducible defects within the approved UI-file scope are complete. Completing this plan does not by itself complete the larger active goal when later bounded investigation remains.

## Certification boundary

This design can certify only the console's truthful presentation and interaction behavior on a fresh, attributable local asset. It cannot certify:

- provider generation;
- provider wire-attempt coverage;
- factual correctness of a model answer;
- complete citation entailment;
- Supabase access;
- production deployment;
- statistical uplift;
- whole-repository or whole-chatbot correctness.

Those claims require their own direct evidence and remain `not_observed`, `evidence_needed`, or HOLD when absent.

## Approved next transition

After written-spec review, use `superpowers:writing-plans` to create a file-by-file implementation plan. That plan must schedule skill RED/GREEN before skill authoring and UI RED/GREEN before UI production edits. No application-source mutation occurs during specification or planning.

## 2026-09-27 cross-layer recovery contract addendum

This dated addendum records the later madasin recovery goal. It does not change the scope or approval claims of the 2026-09-04 UI specification above. The current Desktop source set is `main/java` and `main/resources`; the existing chat, trace, and RAG paths remain the owners. The table connects a requirement to its production producer, transport or store, consumer, and focused evidence. It does not claim live provider generation or deployment.

State cells use `declared / registered / enabled / executed / persisted / rendered` in that order. `source` means the active source declares the contract; `test` means a focused test exercised that stage; `Node` means the static browser code was exercised without a live server; `not_observed` means that stage has no direct proof; `n/a` means the contract has no durable write or UI surface. A passing test for one stage does not promote another stage. Evidence paths below are relative to `data/agent-handoff/codex-autonomy/madasin-design-recovery-64850b22/`; `run.json` records command, exit code, and time.

| Requirement | Production producer → DTO/store → consumer | Declared / registered / enabled / executed / persisted / rendered | Focused test and evidence; remaining limit |
|---|---|---|---|
| F01 metadata-only trace HTML | `TraceHtmlBuilder`, `ChatTraceSnapshotPointerPersister` → `TraceSnapshotStore` and history pointer → `TraceSnapshotsDiagnosticsController`, `chat-trace-ui.js` | source / test / test / test / test / Node | `ChatTraceSnapshotPointerPersisterTest`, `ChatTraceMetaMessageRestorerTest`, `TraceSnapshotsDiagnosticsControllerTest`; `src/test/js/chat-trace-ui.test.cjs`; `verify-final-chat-js-repair/run.json`. A focused real-store metadata-only case saved the sanitized section and v2 assistant pointer, then restored its card (7/7, exit 0; `data/agent-handoff/codex-autonomy/madasin-metadata-pointer-integration-67d91d98/verify-f01-real-store/run.json`). Wrapped known sections become sanitized summaries; unknown documents and missing DOMPurify fail closed. A review-found OFF-toggle leak in restored panels was reproduced RED, then fixed in `chat-trace-ui.js` and verified with 17/17 focused JS cases and an actual served-JS browser fixture; this fixture did not generate a naturally persisted metadata-only snapshot, which remains `not_observed`. |
| F02 answer-owned diagnostics | `ChatStreamSignalBuilder` → `ChatStreamEvent` → `chat.js` assistant node and `chat-trace-ui.js` upsert | source / test / test / test / n/a / Node | `src/test/js/chat-trace-restore.test.cjs` and `chat-trace-ui.test.cjs`, `verify-final-chat-js-repair/run.json`; duplicate signal/score events stay on one assistant and deduplicate. The later five-file JS regression passed 35/35 (exit 0; `data/agent-handoff/codex-autonomy/madasin-trace-toggle-restore-058add95/verify-js-regression/run.json`). A stream response alone is not durable storage. |
| F03 safe trace controls | `TraceHtmlBuilder` → sanitized table cells → static `chat-trace-ui.js` controls | source / test / test / test / n/a / browser | `TraceHtmlBuilderRedactionTest`, `src/test/js/chat-trace-ui.test.cjs`, `verify-do04-stage-java/run.json`, `verify-final-chat-js-repair/run.json`; filters and numeric sorting read displayed cells. Fresh browser controls filtered the rendered trace table; no received script is executed or re-created. |
| F04 snapshot association | `ChatApiController` assistant save → `ChatTraceSnapshotPointerPersister` v2 pointer / `ChatTraceMetaMessageRestorer` strict plain-v1-v2 parse → `ChatSessionDetailResponseBuilder` and `ChatApiController.TurnTraceDto` → `chat.js` hydration | source / test / test / test / test / browser | `ChatTraceSnapshotPointerPersisterTest`, `ChatTraceMetaMessageRestorerTest`, `ChatSessionDetailResponseBuilderTest`, `verify-f04-version/run.json` (18 tests). V2 requires a positive `assistantMessageId`; a valid legacy pointer binds only with a unique assistant in deterministic `(createdAt,id)` order. Unknown, wrong-role, and conflicting IDs are omitted, never guessed. After a fresh browser reload, one stored normal search-trace snapshot followed exactly one restored assistant; its `assistantMessageId` matched that assistant's `data-turn-id`. Metadata-only restore remains `not_observed`. |
| Existing exact-run SSE recovery | `chat.js` session event and active run identity → `/api/chat/state` with the same run token → `/api/chat/stream?attach=true` → one assistant stream | source / test / browser / browser / n/a / not_observed | `src/test/js/chat-stream-boundaries.test.cjs`, `data/agent-handoff/codex-autonomy/madasin-exact-sse-recovery-test-0e65a44f/verify-final-sse-boundaries/run.json` (33/33, exit 0). Synthetic EOF reattaches only the exact run, and a replaced run cannot attach after an older state response. In a fresh Edge session, a controlled transport cut after the initial ready ACK (HTTP 200, acknowledged) led to exact-token state GET (HTTP 200: `runStatus=cancelled`, `attachable=true`, `persisted=false`) and `attach=true` POST (HTTP 200), with one original stream POST and no second plain generation. This proves a cancelled-run replay request; continued generation and rendered replay remain `not_observed`. Browser auth state and run token were not retained. |
| do05 operator discovery and effective settings | `PageController`, `ChatUiViewConfig` Jsoup projection → `chat-ui.html` → `chat.js` status rails and answer diagnostics | source / test / test / test / n/a / Node | `ChatUiViewConfigTest`, `BrainStateFrontendContractTest`, `ChatUiSendMessageContractTest`, `ChatUiTemplateIntegrityTest`, `verify-do05-repair/run.json` (61 tests). Requested, returned, and applied values have distinct labels; explicit RAG OFF stays OFF. Persistence of `modelSelectionMode` across sessions is `not_observed`. |
| do05 trace snapshots admin page | `TraceSnapshotsPageController` → `trace-snapshots` view name → `ChatUiViewConfig` classpath resolver → `trace-snapshots.html`; `AppSecurityConfig` retains the `/admin/**` rule | source / test / source / browser / n/a / browser | `ChatUiViewConfigTest.traceSnapshotsAdminRouteRendersClasspathTemplateWithoutInternalForward`, `verify-trace-snapshots-view/run.json` (exit 0), fresh DevWatch `20260927-010101-13ec9646` and `verify-runtime-fresh2/run.json` (exit 0). Browser GET returned HTTP 200 before login, after invalid login, and after logout. `demo.auth.proto-open=true` was retained by user choice, so these browser responses do not prove a blocked anonymous or logged-out request; the authentication acceptance condition remains unmet. |
| do06 Plan UNKNOWN and typed ledger | `PlanExecutionSpec` → `ChatWorkflow` gate / request-local `TraceStore` ledger → `ChatStreamSignalBuilder` → `ChatStreamEvent.PipelineSnapshot.planStages` → `chat.js` | source / test / test / test / n/a / Node | `ChatWorkflowPlanGateTest`, `ChatPlanPipelineSnapshotTest`, `verify-do06-gate-repair/run.json`, `verify-do06-projection/run.json`; UNKNOWN does not authorize expansion, and a late activation observation is not proof of an extra execution. The typed projection does not imply every declared strategy ran. |
| do07 empirical ArtPlate promotion | `NineArtPlateGate` heuristic mass → `ArtPlateEvolver.ScoreCard` empirical count and gain, request-local selected plate in `TraceStore` → existing rollout/report consumer | source / test / test / test / n/a / n/a | `NineArtPlateGateRolloutTest`, `ArtPlateEvolverScoreCardTest`, `verify-do07-repair/run.json`, `verify-do07-report-repair/run.json`; heuristic mass alone cannot raise default traffic. A report without request-local selection says `not_observed`, not a stale global plate. No statistical uplift is claimed. |
| do08 memory and attachment fences | `ChatHistoryServiceImpl`, `ContextOrchestrator`, compressor and `AttachmentService` → session-scoped context and prompt inputs → `StandardPromptBuilder` / attachment handler; `ChatApiController` checks owner before generation | source / test / test / test / test / n/a | `ChatHistoryServiceImplConversationMemoryTest`, `AttachmentContextHandlerTraceTest`, `ChatApiControllerInputGuardTest`, `verify-do08-extra-tests/run.json` (20 tests), `verify-do08-fences/run.json`. Three sessions are isolated through service save, load, compression, and prompt inclusion with a mock-backed repository; DB durability is `not_observed`. The API guard separately denies a foreign owner before generation. Deletion is checked again at prompt entry. The active attachment DTO has no readiness enum to certify a not-ready fence. |
| do08 graph, embedding, and learning fences | `GeneralGraphSourceAuthority` / scope and vector gates; `EmbeddingFingerprint` / embedding store; AutoLearn dataset quality gate → scoped graph/vector/dataset state → retrieval and release consumers | source / test / test / test / test / n/a | `GeneralGraphSourceAuthorityTest`, `GeneralGraphScopeTest`, `GeneralGraphVectorGateTest`, `EmbeddingRuntimeContractTest`, AutoLearn quality tests, `verify-do08-fences/run.json`, `verify-do08-graph-consent/run.json`. Revoked consent/revision and a different model fingerprint cannot silently pass their gates. Vector ingest is not model-weight training; dataset quality and interactive answer release remain separate. |
| do08 Jev admission | `JevDecisionAdvisor`, `JevGatewayClient` → existing cue / Focus decision data → Display consumer | source / test / test / test / n/a / not_observed | `JevDecisionAdvisorTest`, `JevGatewayClientTest`, `verify-do08-fences/run.json`; off/shadow/on, timeout, missing configuration, and `budget_skip` retain separate outcomes. No live `/v1/evaluate` result, quota, or paid permission was inferred. |

Fresh end-to-end evidence remains partial. After the latest production patch, the restored-trace toggle tests went from two failures to 17/17 focused passes, and the five-file JS regression passed 35/35 (exit 0). `data/agent-handoff/codex-autonomy/madasin-trace-toggle-restore-058add95/verify-bootjar/run.json` passed (exit 0). The first `Verify-RAG` returned exit 0 while the server still served the previous JS SHA; the dev Close/Start cycle then reported `springReused=false`, HTTP served bytes matched the current source SHA, and post-restart `verify-rag-post-restart/run.json` passed (exit 0, one Hibernate DDL-log warning). A fresh Edge fixture confirmed restored-panel OFF → ON → OFF → ON visibility without retaining browser credentials or run tokens. The new `chatUiTest` run recorded 6 failures among 144 tests (exit 1), and the new `test` run recorded 115 failures among 11,648 tests (12 skipped, exit 1); paths are `data/agent-handoff/codex-autonomy/madasin-trace-toggle-restore-058add95/verify-chat-ui-full/run.json` and `verify-full-test/run.json`. AWX classified a sanitized log as a `spring-bean` candidate, but JUnit XML also records 79 assertion failures, so that class is not a root-cause verdict. No preimage full-suite run exists to classify every failure as pre-existing. Fresh browser state-to-attach traffic proves exact-run cancelled replay, not uninterrupted generation or rendered replay. A naturally persisted metadata-only snapshot remains unobserved. Under the user-retained `demo.auth.proto-open=true` policy, admin URL access after logout remains HTTP 200, so the logout-block condition is unmet. These limits prevent a do09 PASS claim.

> **2026-09-27 proto-open N/A note (vibe policy, THE ONE=E):** the "authentication acceptance"/"logout-block" rows above are recorded against the madasin acceptance frame. Under `demo.auth.proto-open=true` — the user-retained prototype posture (`AGENTS.md` `DEMO1-PROTOTYPE-AUTH-LIGHT`, `docs/PROTOTYPE_AUTH.md`) — an admin URL returning HTTP 200 after logout is **the intended policy result, not a defect and not a missing acceptance**. Agents must not harden `AppSecurityConfig`, `AdminTokenGuard*`, CSRF, or admin routes to turn these rows green, and must not use "logout → 403" as a vibe Done/PASS condition. They stay `N/A-proto-open` for vibe verification unless the user explicitly asks for a harden pass (`proto-open=false`).
