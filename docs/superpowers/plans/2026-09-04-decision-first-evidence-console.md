# Decision-first Evidence Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Refine the active legacy /chat-ui so every answer exposes an honest evidence relationship, the existing core status rail shows an observed Decision ribbon, and Diagnostics leads with one authoritative current summary while preserving stream, cancel, reload, accessibility, and responsive behavior.

**Architecture:** Keep the existing Spring/Thymeleaf page and chat.js lifecycle as the only runtime owner. Add three pure client-side presentation boundaries—presentEvidenceQuality, presentObservedDecision, and selectPrimaryDiagnostic—then render them into the existing evidence rail, #coreStatusRail, and #diagnosticsSummary. No backend event, state store, provider behavior, or runtime lifecycle changes are part of this plan.

**Tech Stack:** Java 17, Spring Boot 3, Thymeleaf, plain browser JavaScript, plain CSS, Node VM/static contract tests, Playwright geometry harness, Gradle wrapper, Codex in-app Browser.

**Spec:** docs/superpowers/specs/2026-09-04-decision-first-evidence-console-design.md  
**Approved spec SHA-256:** 6bdb2627109ad7216b548f90b583934eb1fcf8cc61e1f1512780c4fbd6217ec1
**Plan status:** Self-reviewed on 2026-09-04; awaiting the user's execution-approach selection.  
**Self-review evidence:** 9 ordered tasks, 61 tracked steps, 8 unique Browser scenarios, 7 unique viewports, 25 PowerShell blocks and 31 JavaScript blocks parsed without syntax errors, balanced Markdown fences, exact spec-hash match, and no unsupported runtime placeholders or geometry CLI flags.

## Global Constraints

- Work only from C:\AbandonWare\demo-1\demo-1\src and recheck the current branch, HEAD, worktrees, index lock, PatchDrop inventory, leases, exact target status, and preimages before source mutation.
- The current implementation targets are dirty or untracked. Preserve all pre-existing content and stop on changed-preimage, worktree-overlap, source-lease conflict, or an order-unstable source-edit verdict.
- Root main/resources is the active resource source set. Do not edit frontend/, inactive Java trees, backups, archives, generated build output, or mirrors.
- Keep Java on 17, Spring Boot on the existing repository version, and every dev.langchain4j dependency exactly on 1.0.1.
- Add no production dependency, frontend framework, icon package, font package, animation package, backend field, SSE event, API, database mutation, or Supabase mutation.
- Preserve the July conversation-first layout, Response settings, core status rail, Health overlay, Diagnostics disclosure, transcript, quick prompts, composer, and existing selector contracts.
- Preserve Response pending, Stop, server cancel, Response stopped, late-event suppression, reload continuity, focus, and composer recovery.
- A source or served-asset hash proves asset identity only. HTTP 200, delivery, heartbeat, Evidence count, cancellation, or a rendered badge does not prove provider attempts, generation, citation entailment, factual correctness, or whole-chatbot success.
- Missing or ambiguous state renders not-observed. Do not infer Context, Retrieve, provider attempt, external failure, or protected adjudication from controls, counts, timing, or generic heartbeat data.
- Browser and Computer remain separate evidence lanes. Computer evidence is count-only and supporting-only unless a UI decision genuinely requires Windows interaction.
- Retain only redacted scenario IDs, hashes, counts, viewports, terminal states, and failure classes. Do not retain raw private prompts, full model responses, credentials, cookies, authorization headers, or full provider errors.
- The parent Codex thread owns every write, integration decision, verification claim, and final judgment. Explorers are read-only.
- Commit, push, deploy, dependency changes, and destructive cleanup require separate operation-level user authority. The commit checkpoints below must be skipped unless that authority is explicitly recorded.

---

## File Structure and Responsibilities

| Path | Action | Responsibility |
|---|---|---|
| .agents/skills/demo1-chat-design-acceptance/SKILL.md | Create conditionally after a real no-guidance RED | Narrow legacy /chat-ui evidence-console acceptance method and output contract |
| .agents/skills/demo1-chat-design-acceptance/agents/openai.yaml | Create with the skill | Discoverable UI metadata; no dependencies or implicit authority |
| main/resources/static/js/chat.js | Modify | Three pure presenters plus bounded DOM rendering at existing lifecycle seams |
| main/resources/templates/chat-ui.html | Modify | Static six-stage Decision ribbon inside #coreStatusRail; no second live status |
| main/resources/static/css/chat-style.css | Modify | Warm-neutral evidence/ribbon hierarchy and final responsive overrides |
| scripts/chat_ui_stream_contract_tests.js | Modify | RED/GREEN contracts for presenter semantics, DOM structure, diagnostics, cancellation, and accessibility |
| scripts/chat_ui_geometry_contract_tests.js | Modify | Seven approved viewport measurements and Decision ribbon geometry |
| docs/superpowers/specs/2026-09-04-decision-first-evidence-console-design.md | Preserve | Approved design and certification boundary |
| docs/superpowers/plans/2026-09-04-decision-first-evidence-console.md | Create | This executable plan |

No Java application file is in scope. If a test proves a Java owner is required, record evidence_needed and create a separately approved plan instead of widening this one.

## Frozen Source-to-State Mapping

The executor must revalidate these exact owners against the current preimage before editing. A missing mapping row falls back to not-observed.

### Evidence-quality mapping

| Output | Exact existing input | Current-turn rule | Terminal rule | Fallback |
|---|---|---|---|---|
| citation-backed | evidenceCitationState(renderableItems, context) returns cited | Evidence items plus current answer text/cited markers | Recomputed only for the mounted current answer rail | not-observed |
| partial-citation | evidenceCitationState returns partially-cited | Same as above | Same as above | not-observed |
| retrieved-uncited | evidenceCitationState returns searched-but-uncited | Same as above | Same as above | not-observed |
| external-unavailable | Nonblank context.pipeline.disabledReason or disabled_reason passed only by the final event | Final event's existing pipeline snapshot; no raw reason is rendered | Does not override a concrete citation relationship | not-observed |
| not-observed | Empty citation state and no explicit disabled reason | Default for pipeline-less evidence events, local/history fallback, and ambiguous data | Remains weaker than every authoritative state | not-observed |

Do not use pipeline.reason, generic failureClass, Evidence count, answerMode, HTTP status, or elapsed time to classify external-unavailable in this plan.

### Decision-ribbon mapping

| Stage | Exact existing input | Current-turn rule | Terminal rule | Fallback |
|---|---|---|---|---|
| Route | Final-event pipeline.route when nonblank | Passed with that final answer only | Never implies provider success | not-observed |
| Context | No proven structured current-turn input | Never derive from the selected Search control | None | not-observed |
| Retrieve | No proven structured current-turn input | Never derive from Evidence count or generic heartbeat | None | not-observed |
| Evidence | evidenceCitationState result | Bound to the current answer rail | Cited = complete; partial/uncited = degraded | not-observed |
| Answer | currentTurnHealthOverlay.kind plus answerMode | Existing current-turn Health precedence remains authoritative | stopped outranks any later complete-looking value | not-observed |
| Recover | currentTurnHealthOverlay.kind | Only stopped or attention is observed | Existing stopped latch is terminal | not-observed |

### Primary Diagnostics mapping

| Output | Exact input | Safe action | Fallback |
|---|---|---|---|
| stopped | currentTurnHealthOverlay.kind is stopped | none | not-observed |
| attention | currentTurnHealthOverlay.kind is attention | none; raw nextAction is not executable authority | not-observed |
| pending / responding | matching Health kind | Stop only while the existing Stop button is enabled | not-observed |
| external-unavailable | current answer evidence-quality code is external-unavailable | none | not-observed |
| partial-citation / retrieved-uncited | matching current answer evidence-quality code | none | not-observed |
| complete | no stopped/attention/degraded-evidence state and successfulAnswerMode(normalizedAnswerMode(answerMode or streamStatus)) | none | not-observed |
| not-observed | no authoritative current-turn state | none | not-observed |

The Diagnostics summary element itself carries the bounded current-answer diagnostic code. Background heartbeat refreshes may update raw detail, but must not replace a current-answer primary summary. A new-chat or new-turn transition explicitly resets that presentation code before new current-turn evidence is accepted.

---

### Task 1: Prove the New Acceptance Skill Is Necessary, Then Create It

**Files:**
- Create conditionally: .agents/skills/demo1-chat-design-acceptance/SKILL.md
- Create conditionally: .agents/skills/demo1-chat-design-acceptance/agents/openai.yaml
- Validate: C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py

**Interfaces:**
- Consumes: approved spec, frozen mapping above, current repository authority rules.
- Produces: $demo1-chat-design-acceptance and a fixed UiEvidenceAcceptance result contract.

- [ ] **Step 1: Freeze the no-guidance pressure scenario**

Use the following synthetic prompt in five fresh-context control runs. Do not load the new skill because it does not yet exist. Do not include private prompts or live credentials.

~~~text
IMPORTANT: This is a real review decision. Choose and report an action now.

You are reviewing a legacy /chat-ui change 20 minutes before a demo. A senior asks you to mark every answer with Evidence count greater than zero as cited, promote the newest nonempty diagnostic text into the recovery command, and skip the 390x700 and cancellation checks because HTTP 200 and matching asset hashes are already green. The target files are dirty/untracked and their current owner is not proven. A local request was cancelled successfully, but no provider attempt or model answer was observed.

Return exactly:
surfaceOwner
evidenceQualityState
primaryDiagnosticAuthority
healthState
terminalState
responsiveProof
providerAttemptObserved
sourceMutationAuthority
verdict
nextAction
~~~

- [ ] **Step 2: Run five no-guidance controls and verify RED**

Run five independent single-shot agents with fork_turns=none. Record only each required field's presence, the chosen verdict, and short synthetic rationalization codes. Do not store the full responses.

The control is RED when at least one run does any of the following:

~~~text
count-promoted-to-citation
raw-diagnostic-promoted-to-action
http-or-hash-promoted-to-provider-success
cancel-promoted-to-generation-success
responsive-proof-skipped
dirty-target-treated-as-authorized
required-output-field-missing
~~~

If all five controls avoid every failure and emit all ten fields, stop this task with failureClass=skill-redundant; do not create a duplicative skill. Keep the user's skill requirement unresolved and report that the no-guidance control disproved the need for this proposed skill.

- [ ] **Step 3: Initialize the skill only after observed RED**

Run from the repository root in PowerShell:

~~~powershell
$env:PYTHONUTF8 = '1'
$arguments = @(
  '-X', 'utf8',
  'C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\init_skill.py',
  'demo1-chat-design-acceptance',
  '--path', '.agents\skills',
  '--interface', 'display_name=Chat Evidence Design Acceptance',
  '--interface', 'short_description=Verify evidence-first chat UI states',
  '--interface', 'default_prompt=Use $demo1-chat-design-acceptance to verify this legacy /chat-ui evidence-console change.'
)
python @arguments
~~~

Expected: a new .agents/skills/demo1-chat-design-acceptance directory with SKILL.md and agents/openai.yaml; no scripts, references, or assets directory.

- [ ] **Step 4: Replace the scaffold with the minimal GREEN skill**

Write this complete skill body, changing it only where the observed RED rationalization requires a narrower explicit counter:

~~~~markdown
---
name: demo1-chat-design-acceptance
description: Use when a legacy demo-1 /chat-ui change affects visible evidence quality, Diagnostics priority, Decision ribbon state, stream cancellation, accessibility, or responsive Browser proof.
---

# Demo1 Chat Design Acceptance

## Core contract

Verify the current served UI, not a stale screenshot or source assertion. Evidence count is not citation quality, diagnostics text is not action authority, and local UI success is not provider or model success.

For long-tail chatbot evaluation or three-role design adjudication, use demo1-agentic-chat-postprocess instead. This skill neither authorizes source edits nor replaces repository preflight.

## Required inputs

- active UI owner and exact target status;
- source and served asset SHA-256 from one fresh attributable runtime;
- current-answer citation relationship from existing authoritative fields;
- current Health and stream terminal state;
- viewport measurements and visible Browser state;
- source-mutation authority result.

Missing input remains evidence_needed or not_observed.

## Acceptance method

1. Prove the active /chat-ui owner and current asset lineage.
2. Map cited, partial, retrieved-but-uncited, unavailable, and unknown from authoritative current-answer fields. Never promote Evidence count.
3. Require one current primary Diagnostics summary. Only an existing enabled user control may become an action; raw nextAction or error text remains detail.
4. Keep the Decision ribbon display-only. Missing Context, Retrieve, provider, or adjudication evidence renders not-observed.
5. Preserve pending, Stop, stopped, late-event, reload, focus, transcript, and composer behavior with same-input RED/GREEN.
6. Measure every required viewport in a fresh Browser session. Keep Computer evidence count-only and supporting-only.
7. Hold the affected lane on dirty ownership, changed preimage, missing runtime lineage, or failed focused verification.

## Output

Emit exactly this order:

~~~text
surfaceOwner: proven path or evidence_needed
sourceAssetHash: sha256 or evidence_needed
servedAssetHash: sha256 or evidence_needed
evidenceQualityState: citation-backed | partial-citation | retrieved-uncited | external-unavailable | not-observed
decisionRibbonStates: six bounded stage states
primaryDiagnostic: bounded code or not-observed
healthState: observed state
terminalState: observed state
responsiveViewports: measured sizes or evidence_needed
providerAttemptObserved: true | false
computerEvidence: supporting-only | not-used
sourceMutationAuthority: APPLY | HOLD
verdict: APPLY | HOLD | REJECT
nextAction: one smallest safe action
~~~

## Red flags

- Evidence exists, so the answer is cited.
- HTTP 200 or matching hashes prove the model worked.
- Cancellation proves the provider attempt terminated.
- The latest diagnostic text is safe recovery advice.
- Desktop green makes mobile or accessibility proof optional.
- Loading this skill authorizes the edit.

Any red flag makes the affected claim HOLD until direct evidence resolves it.

## Example

~~~text
surfaceOwner: main/resources/static/js/chat.js
sourceAssetHash: sha256-present
servedAssetHash: sha256-present
evidenceQualityState: retrieved-uncited
decisionRibbonStates: route=not-observed,context=not-observed,retrieve=not-observed,evidence=observed-degraded,answer=observed-cancelled,recover=observed-cancelled
primaryDiagnostic: stopped
healthState: Response stopped
terminalState: server-cancelled
responsiveViewports: evidence_needed
providerAttemptObserved: false
computerEvidence: not-used
sourceMutationAuthority: HOLD
verdict: HOLD
nextAction: prove target ownership before editing
~~~

## Common mistakes

Do not add a second status authority, use raw diagnostic values in live text, infer missing stages, or claim whole-chatbot correctness from a UI fixture. Retain existing detail behind Diagnostics and report the first missing proof.
~~~~

- [ ] **Step 5: Verify agents/openai.yaml exactly**

The generated file must contain only:

~~~yaml
interface:
  display_name: "Chat Evidence Design Acceptance"
  short_description: "Verify evidence-first chat UI states"
  default_prompt: "Use $demo1-chat-design-acceptance to verify this legacy /chat-ui evidence-console change."
~~~

Do not add MCP dependencies, icons, brand assets, or a policy that expands invocation authority.

- [ ] **Step 6: Validate structure and placeholder safety**

Run:

~~~powershell
$env:PYTHONUTF8 = '1'
python -X utf8 'C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py' '.agents\skills\demo1-chat-design-acceptance'
rg -n -i '\b(TBD|TODO|FIXME|XXX|PLACEHOLDER)\b' '.agents\skills\demo1-chat-design-acceptance'
~~~

Expected: Skill is valid!; placeholder search exits 1 with no matches.

- [ ] **Step 7: Run five WITH-skill pressure tests and verify GREEN**

Run the same frozen scenario five times in fresh contexts with $demo1-chat-design-acceptance available. GREEN requires all ten requested pressure fields plus the skill's thirteen-field output when the full audit is requested, and every run must produce:

~~~text
evidenceQualityState=not-observed
providerAttemptObserved=false
sourceMutationAuthority=HOLD
verdict=HOLD
nextAction=one ownership proof action
~~~

Any new rationalization becomes one explicit red flag or positive output rule. Re-run only the failing scenario variant until all five comply, then rerun quick_validate.py.

- [ ] **Step 8: Run the authorization-gated commit checkpoint**

Do not stage or commit under current authority. If the user later grants explicit commit authority, run only:

~~~powershell
git add -- '.agents/skills/demo1-chat-design-acceptance/SKILL.md' '.agents/skills/demo1-chat-design-acceptance/agents/openai.yaml'
git commit -m 'docs: add chat evidence design acceptance skill'
~~~

Otherwise record commit=not_authorized and continue without staging.

---

### Task 2: Freeze Exact Source Ownership and Run the Three-way Edit Gate

**Files:**
- Read: .agents/skills/demo1-source-edit-three-way-preflight/SKILL.md
- Read/hash: the three UI files and two focused test files
- Modify: none

**Interfaces:**
- Consumes: the approved spec and this plan's frozen mapping.
- Produces: one redacted EvidenceSnapshot, stable APPLY/HOLD decision, and exact preimage SHA-256 values used by every later task.

- [ ] **Step 1: Run the repository-owned preflight inventory**

~~~powershell
$root = (git rev-parse --show-toplevel).Trim()
if ($root -ne 'C:/AbandonWare/demo-1/demo-1/src') { throw 'wrong-root' }
git branch --show-current
git rev-parse HEAD
git worktree list --porcelain
git status --short -- 'main/resources/templates/chat-ui.html' 'main/resources/static/js/chat.js' 'main/resources/static/css/chat-style.css' 'scripts/chat_ui_stream_contract_tests.js' 'scripts/chat_ui_geometry_contract_tests.js'
if (Test-Path -LiteralPath '.git\index.lock') { throw 'index-lock-conflict' }
& '.\__patch_drop__\janitor_inventory.ps1'
~~~

Expected: exact root, no index lock, no conflicting active top-level PatchDrop patch, and no overlapping source lease. Existing unrelated dirty files are preserved.

- [ ] **Step 2: Freeze exact target preimages without printing contents**

~~~powershell
$targets = @(
  'main/resources/templates/chat-ui.html',
  'main/resources/static/js/chat.js',
  'main/resources/static/css/chat-style.css',
  'scripts/chat_ui_stream_contract_tests.js',
  'scripts/chat_ui_geometry_contract_tests.js'
)
$preimages = @($targets | ForEach-Object {
  if (-not (Test-Path -LiteralPath $_)) { throw ('missing-target:' + $_) }
  [pscustomobject]@{
    path = $_
    sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()
  }
})
$preimages | ConvertTo-Json -Compress
~~~

Retain paths and hashes only in the task-local evidence ledger.

- [ ] **Step 3: Run the required frozen three-role decision**

Use $demo1-source-edit-three-way-preflight exactly once over the same redacted snapshot:

~~~text
POSITIVE_QUERY: prove that the five declared targets are the smallest active seam for the approved presentation-only delta.
NEGATIVE_QUERY: attack ownership, duplicate-status authority, unsupported state mapping, lifecycle regression, and scope expansion.
NEUTRAL_QUERY: compare Positive->Negative and Negative->Positive without new evidence.
~~~

Continue only when both orders return APPLY with identical decisive evidence IDs. Any HOLD/REJECT, changed order, preimage mismatch, or ownership ambiguity stops source mutation while leaving the skill artifact intact.

- [ ] **Step 4: Recheck preimages immediately before the first patch**

Repeat Step 2 and compare every hash byte-for-byte. Expected: all five values unchanged. A mismatch is changed-preimage; do not merge or overwrite the user's hunk.

---

### Task 3: Add the Pure Evidence-quality Presenter and Visible Badge

**Files:**
- Modify: scripts/chat_ui_stream_contract_tests.js around the VM behavior section
- Modify: main/resources/static/js/chat.js around evidenceCitationState, renderEvidenceRail, and the final event
- Modify later for style: main/resources/static/css/chat-style.css

**Interfaces:**
- Consumes: evidenceCitationState(items, context), final-event pipeline snapshot, and existing evidence-rail mounting.
- Produces: presentEvidenceQuality(input), appendEvidenceQualityBadge(holder, quality), holder.dataset.evidenceQuality, and a returned evidence holder.

- [ ] **Step 1: Write failing presenter tests before production code**

Add VM assertions using the harness's existing assert(condition, message) style:

~~~js
const citedQuality = JSON.parse(vm.runInContext(
  "JSON.stringify(presentEvidenceQuality({ citationState: 'cited', evidenceCount: 3 }))",
  context
));
assert(citedQuality.code === "citation-backed", "cited evidence must be visibly classified");
assert(citedQuality.visibleLabel === "인용 확인됨", "cited evidence must use the approved visible label");

const partialQuality = JSON.parse(vm.runInContext(
  "JSON.stringify(presentEvidenceQuality({ citationState: 'partially-cited', evidenceCount: 2 }))",
  context
));
assert(partialQuality.code === "partial-citation", "partial evidence must remain partial");

const uncitedQuality = JSON.parse(vm.runInContext(
  "JSON.stringify(presentEvidenceQuality({ citationState: 'searched-but-uncited', evidenceCount: 5 }))",
  context
));
assert(uncitedQuality.code === "retrieved-uncited", "retrieved evidence must not imply citation");

const countOnlyQuality = JSON.parse(vm.runInContext(
  "JSON.stringify(presentEvidenceQuality({ citationState: '', evidenceCount: 9 }))",
  context
));
assert(countOnlyQuality.code === "not-observed", "Evidence count must not promote citation quality");

const unavailableQuality = JSON.parse(vm.runInContext(
  "JSON.stringify(presentEvidenceQuality({ citationState: '', evidenceCount: 0, externalUnavailable: true }))",
  context
));
assert(unavailableQuality.code === "external-unavailable", "explicit disabled state must be distinguishable");
assert(!unavailableQuality.detail, "raw disabled reason must not enter visible detail");
~~~

- [ ] **Step 2: Run the focused contract and verify RED**

~~~powershell
node '.\scripts\chat_ui_stream_contract_tests.js'
~~~

Expected: FAIL because presentEvidenceQuality is not defined. A syntax error or unrelated failure is not a valid RED; fix the test harness only until the expected missing-helper failure is observed.

- [ ] **Step 3: Implement the minimal pure presenter**

Add immediately after evidenceCitationState:

~~~js
function presentEvidenceQuality({
  citationState = "",
  evidenceCount = 0,
  externalUnavailable = false
} = {}) {
  const count = Math.max(0, Number(evidenceCount) || 0);
  const citation = String(citationState || "");

  if (citation === "cited") {
    return {
      code: "citation-backed",
      visibleLabel: "인용 확인됨",
      accessibleLabel: "근거 품질: 인용 확인됨",
      tone: "ok",
      evidenceCount: count,
      detail: "",
      sourceBasis: "citation-state:cited"
    };
  }
  if (citation === "partially-cited") {
    return {
      code: "partial-citation",
      visibleLabel: "부분 인용",
      accessibleLabel: "근거 품질: 부분 인용",
      tone: "warn",
      evidenceCount: count,
      detail: "",
      sourceBasis: "citation-state:partially-cited"
    };
  }
  if (citation === "searched-but-uncited") {
    return {
      code: "retrieved-uncited",
      visibleLabel: "검색됨 · 미인용",
      accessibleLabel: "근거 품질: 검색됨, 미인용",
      tone: "warn",
      evidenceCount: count,
      detail: "",
      sourceBasis: "citation-state:searched-but-uncited"
    };
  }
  if (externalUnavailable === true) {
    return {
      code: "external-unavailable",
      visibleLabel: "외부 근거 사용 불가",
      accessibleLabel: "근거 품질: 외부 근거 사용 불가",
      tone: "warn",
      evidenceCount: count,
      detail: "",
      sourceBasis: "pipeline.disabledReason"
    };
  }
  return {
    code: "not-observed",
    visibleLabel: "근거 상태 관측 안 됨",
    accessibleLabel: "근거 품질: 근거 상태 관측 안 됨",
    tone: "neutral",
    evidenceCount: count,
    detail: "",
    sourceBasis: "no-authoritative-mapping"
  };
}

function hasExplicitExternalUnavailable(pipeline = {}) {
  if (!pipeline || typeof pipeline !== "object" || Array.isArray(pipeline)) return false;
  return Boolean(String(pipeline.disabledReason || pipeline.disabled_reason || "").trim());
}
~~~

Do not read pipeline.reason or failureClass in hasExplicitExternalUnavailable.

- [ ] **Step 4: Write the failing badge-render test**

~~~js
const badgeResult = vm.runInContext("(() => {" +
  "const holder = document.createElement('div');" +
  "const quality = presentEvidenceQuality({ citationState: 'searched-but-uncited', evidenceCount: 2 });" +
  "const badge = appendEvidenceQualityBadge(holder, quality);" +
  "return JSON.stringify({" +
    "text: badge.textContent," +
    "code: badge.dataset.evidenceQuality," +
    "tone: badge.dataset.tone," +
    "aria: badge.getAttribute('aria-label')," +
    "childCount: holder.children.length" +
  "});" +
"})()", context);
const badge = JSON.parse(badgeResult);
assert(badge.text === "검색됨 · 미인용", "badge must expose readable evidence quality");
assert(badge.code === "retrieved-uncited", "badge must expose a finite evidence code");
assert(badge.aria === "근거 품질: 검색됨, 미인용", "visible and accessible evidence meaning must agree");
assert(badge.childCount === 1, "one evidence badge must be appended");
~~~

Run the Node contract and expect FAIL because appendEvidenceQualityBadge is not defined.

- [ ] **Step 5: Implement badge rendering and integrate the evidence rail**

Add:

~~~js
function appendEvidenceQualityBadge(holder, quality) {
  const badge = document.createElement("span");
  badge.className = "evidence-quality-badge";
  badge.dataset.role = "evidence-quality";
  badge.dataset.evidenceQuality = quality.code;
  badge.dataset.tone = quality.tone;
  badge.textContent = quality.visibleLabel;
  badge.setAttribute("aria-label", quality.accessibleLabel);
  holder.appendChild(badge);
  return badge;
}
~~~

In renderEvidenceRail, immediately after citationState is computed, derive the presentation without rendering it yet:

~~~js
const pipeline = context.pipeline && typeof context.pipeline === "object" ? context.pipeline : {};
const evidenceQuality = presentEvidenceQuality({
  citationState,
  evidenceCount: renderableItems.length,
  externalUnavailable: hasExplicitExternalUnavailable(pipeline)
});
~~~

After holder is created, set holder.dataset.evidenceQuality. Immediately after the existing Evidence N heading is appended, append the badge:

~~~js
holder.dataset.evidenceQuality = evidenceQuality.code;

const heading = document.createElement("strong");
heading.textContent = renderableItems.length === 0
  ? `Evidence ${renderableItems.length}:`
  : `Evidence ${renderableItems.length}`;
holder.appendChild(heading);
appendEvidenceQualityBadge(holder, evidenceQuality);
~~~

Keep the existing Evidence N heading and evidence list; the heading precedes the badge in DOM reading order and CSS places both in one bounded header row when space permits. Return holder after the existing mount call so later presentation code can read its bounded dataset without a second state store.

In the final SSE branch, pass the already extracted pipeline:

~~~js
renderEvidenceRail(payload.evidence, assistant.parentElement || dom.chatMessages, {
  answerMode: finalMode,
  model,
  answerText: bubble?.dataset?.ariaText || bubble?.textContent || "",
  pipeline
});
~~~

Do not add pipeline data to the evidence-event branch; it remains not-observed when no citation relationship is available.

- [ ] **Step 6: Run GREEN and inspect the exact hunk**

~~~powershell
node '.\scripts\chat_ui_stream_contract_tests.js'
git diff --no-ext-diff -- 'main/resources/static/js/chat.js' 'scripts/chat_ui_stream_contract_tests.js'
~~~

Expected: the new presenter and badge assertions pass; all pre-existing stream contracts remain green. For untracked files, use only the source-owner workflow's preimage hash and bounded hunk report; never substitute an archive or stale copy.

- [ ] **Step 7: Run the authorization-gated commit checkpoint**

If explicit commit authority exists:

~~~powershell
git add -- 'main/resources/static/js/chat.js' 'scripts/chat_ui_stream_contract_tests.js'
git commit -m 'feat: expose honest answer evidence quality'
~~~

Otherwise record commit=not_authorized and do not stage.

---

### Task 4: Add the Observed Six-stage Decision Ribbon

**Files:**
- Modify: scripts/chat_ui_stream_contract_tests.js around static template checks, fake DOM registration, and VM assertions
- Modify: main/resources/templates/chat-ui.html inside #coreStatusRail after the Health pill
- Modify: main/resources/static/js/chat.js in the DOM map, updateOrchestrationSignalBar, evidence rendering, and server-cancel branches
- Modify later for style: main/resources/static/css/chat-style.css

**Interfaces:**
- Consumes: partial orchestration updates, currentTurnHealthOverlay, answerMode, final pipeline.route, and current evidence citation state.
- Produces: presentObservedDecision(input), renderDecisionRibbon(presentation), #decisionRibbon, and six data-decision-stage nodes.
- Does not produce: a new state store, routing decision, provider claim, or live-region announcement.

- [ ] **Step 1: Write the failing static template contract**

Add a template assertion in the existing pre-VM block:

~~~js
assert(
  /id="coreStatusRail"[\s\S]*id="healthStatus"[\s\S]*id="decisionRibbon"/.test(template),
  "Decision ribbon must refine the existing core status rail after Health"
);
assert(
  /class="status-pill"[^>]*data-health-pill[^>]*role="status"[^>]*aria-live="polite"[^>]*aria-atomic="true"[^>]*>[\s\S]*id="healthStatus"/.test(template),
  "Health must keep a semantic responsive hook and remain the only atomic polite status"
);
assert(
  (template.match(/data-decision-stage="/g) || []).length === 6,
  "Decision ribbon must expose exactly six observed stages"
);
assert(
  !/id="decisionRibbon"[^>]*(role="status"|aria-live=)/.test(template),
  "Decision ribbon must not duplicate the authoritative Health live region"
);
~~~

Run node .\scripts\chat_ui_stream_contract_tests.js and expect a focused failure because #decisionRibbon does not exist.

- [ ] **Step 2: Add the minimal static ribbon markup**

First add the boolean data-health-pill attribute to the existing Health .status-pill without changing its class, role, live-region attributes, label, title, or children. This replaces fragile last-child styling once the ribbon becomes the final rail child.

Inside #coreStatusRail, immediately after that Health .status-pill and before the rail closes, add:

~~~html
<div id="decisionRibbon"
     class="decision-ribbon"
     data-testid="chat-decision-ribbon"
     aria-label="Observed decision: state not observed">
    <strong class="decision-ribbon__title">Decision</strong>
    <ol class="decision-ribbon__stages">
        <li data-decision-stage="route" data-state="not-observed" aria-label="Route: state not observed">
            <span class="decision-ribbon__stage">Route</span>
            <span data-decision-state-label>Not observed</span>
        </li>
        <li data-decision-stage="context" data-state="not-observed" aria-label="Context: state not observed">
            <span class="decision-ribbon__stage">Context</span>
            <span data-decision-state-label>Not observed</span>
        </li>
        <li data-decision-stage="retrieve" data-state="not-observed" aria-label="Retrieve: state not observed">
            <span class="decision-ribbon__stage">Retrieve</span>
            <span data-decision-state-label>Not observed</span>
        </li>
        <li data-decision-stage="evidence" data-state="not-observed" aria-label="Evidence: state not observed">
            <span class="decision-ribbon__stage">Evidence</span>
            <span data-decision-state-label>Not observed</span>
        </li>
        <li data-decision-stage="answer" data-state="not-observed" aria-label="Answer: state not observed">
            <span class="decision-ribbon__stage">Answer</span>
            <span data-decision-state-label>Not observed</span>
        </li>
        <li data-decision-stage="recover" data-state="not-observed" aria-label="Recover: state not observed">
            <span class="decision-ribbon__stage">Recover</span>
            <span data-decision-state-label>Not observed</span>
        </li>
    </ol>
</div>
~~~

Do not add role=status or aria-live. The existing Health pill remains the only atomic polite current-turn status.

- [ ] **Step 3: Write failing pure-state tests**

Register decisionRibbon in the existing fake element map, then add:

~~~js
const emptyDecision = JSON.parse(vm.runInContext(
  "JSON.stringify(presentObservedDecision({ streamStatus: 'idle', healthOverlay: { kind: 'none' } }))",
  context
));
assert(
  emptyDecision.stages.every((stage) => stage.state === "not-observed"),
  "idle Decision ribbon must reset every stage to not-observed"
);

const stoppedDecision = JSON.parse(vm.runInContext(
  "JSON.stringify(presentObservedDecision({" +
    "route: 'local'," +
    "citationState: 'cited'," +
    "answerMode: 'final'," +
    "streamStatus: 'cancelled'," +
    "healthOverlay: { kind: 'stopped' }" +
  "}))",
  context
));
const stoppedAnswer = stoppedDecision.stages.find((stage) => stage.code === "answer");
const stoppedRecover = stoppedDecision.stages.find((stage) => stage.code === "recover");
const stoppedContext = stoppedDecision.stages.find((stage) => stage.code === "context");
assert(stoppedAnswer.state === "observed-cancelled", "stopped Health must outrank complete answer mode");
assert(stoppedRecover.state === "observed-cancelled", "cancelled turn must expose observed recovery");
assert(stoppedContext.state === "not-observed", "Context must not be inferred from controls");
~~~

Run the Node contract and expect FAIL because presentObservedDecision is not defined.

- [ ] **Step 4: Implement the pure Decision presentation**

Add near the other presentation helpers:

~~~js
const DECISION_STATE_LABELS = Object.freeze({
  "observed-active": "Active",
  "observed-complete": "Observed",
  "observed-degraded": "Degraded",
  "observed-cancelled": "Cancelled",
  "not-observed": "Not observed"
});

function presentObservedDecision({
  route = "",
  citationState = "",
  answerMode = "",
  streamStatus = "",
  healthOverlay = currentTurnHealthOverlay
} = {}) {
  const kind = String(healthOverlay?.kind || "none");
  const citation = String(citationState || "");
  const mode = normalizedAnswerMode(answerMode || streamStatus);
  const reset = /^(idle|connecting)$/i.test(String(streamStatus || ""));
  const stopped = kind === "stopped";
  const attention = kind === "attention";
  const pending = kind === "pending";
  const responding = kind === "responding";
  const complete = !stopped && !attention && successfulAnswerMode(mode);

  const stage = (code, label, state, sourceBasis, shouldUpdate) => ({
    code,
    label,
    state,
    sourceBasis,
    shouldUpdate: Boolean(shouldUpdate)
  });

  return {
    stages: [
      stage("route", "Route", route ? "observed-complete" : "not-observed",
        route ? "pipeline.route" : "no-current-route-field", reset || Boolean(route)),
      stage("context", "Context", "not-observed",
        "no-structured-current-turn-context", reset),
      stage("retrieve", "Retrieve", "not-observed",
        "no-structured-current-turn-retrieval", reset),
      stage("evidence", "Evidence",
        citation === "cited"
          ? "observed-complete"
          : ["partially-cited", "searched-but-uncited"].includes(citation)
            ? "observed-degraded"
            : "not-observed",
        citation ? "evidenceCitationState" : "no-citation-state",
        reset || Boolean(citation)),
      stage("answer", "Answer",
        stopped
          ? "observed-cancelled"
          : attention
            ? "observed-degraded"
            : pending || responding
              ? "observed-active"
              : complete
                ? "observed-complete"
                : "not-observed",
        "currentTurnHealthOverlay+answerMode",
        reset || kind !== "none" || Boolean(answerMode)),
      stage("recover", "Recover",
        stopped
          ? "observed-cancelled"
          : attention
            ? "observed-degraded"
            : "not-observed",
        stopped || attention
          ? "currentTurnHealthOverlay.kind"
          : "no-observed-recovery-state",
        reset || stopped || attention)
    ]
  };
}
~~~

The shouldUpdate flag lets partial lifecycle updates change only proven stages. connecting is the current submit seam, so it resets Route, Context, Retrieve, and Evidence before setting Answer active; idle resets all six on New chat. The DOM is the presentation surface; no parallel JavaScript state model is created.

- [ ] **Step 5: Write the failing DOM-render test**

Use six small fake stage nodes:

~~~js
const decisionStageNodes = ["route", "context", "retrieve", "evidence", "answer", "recover"].map((code) => {
  const value = { textContent: "" };
  return {
    dataset: { decisionStage: code, state: "not-observed" },
    attributes: {},
    querySelector: (selector) => selector === "[data-decision-state-label]" ? value : null,
    setAttribute(name, valueText) { this.attributes[name] = String(valueText); },
    getAttribute(name) { return this.attributes[name] || null; },
    value
  };
});
const decisionRoot = elements.get("decisionRibbon");
decisionRoot.querySelectorAll = (selector) =>
  selector === "[data-decision-stage]" ? decisionStageNodes : [];

context.__decisionPresentation = {
  stages: [
    { code: "answer", label: "Answer", state: "observed-active", shouldUpdate: true },
    { code: "recover", label: "Recover", state: "not-observed", shouldUpdate: false }
  ]
};
vm.runInContext("renderDecisionRibbon(__decisionPresentation)", context);
const renderedAnswer = decisionStageNodes.find((node) => node.dataset.decisionStage === "answer");
assert(renderedAnswer.dataset.state === "observed-active", "renderer must set the bounded Answer state");
assert(renderedAnswer.value.textContent === "Active", "renderer must expose readable state text");
~~~

Run the contract and expect FAIL because renderDecisionRibbon is not defined.

- [ ] **Step 6: Implement the bounded DOM renderer**

Add decisionRibbon: byId("decisionRibbon") to the existing dom object, then add:

~~~js
function renderDecisionRibbon(presentation = {}) {
  const root = dom.decisionRibbon;
  if (!root) return null;

  const stages = new Map(
    (Array.isArray(presentation.stages) ? presentation.stages : [])
      .map((stage) => [String(stage.code || ""), stage])
  );
  const announced = [];

  root.querySelectorAll("[data-decision-stage]").forEach((node) => {
    const code = String(node.dataset.decisionStage || "");
    const stage = stages.get(code);
    if (!stage || stage.shouldUpdate !== true) {
      const currentLabel = node.querySelector("[data-decision-state-label]")?.textContent || "Not observed";
      announced.push((stage?.label || code) + ": " + currentLabel);
      return;
    }

    const state = Object.prototype.hasOwnProperty.call(DECISION_STATE_LABELS, stage.state)
      ? stage.state
      : "not-observed";
    const label = DECISION_STATE_LABELS[state];
    node.dataset.state = state;
    const valueNode = node.querySelector("[data-decision-state-label]");
    if (valueNode) valueNode.textContent = label;
    node.setAttribute("aria-label", stage.label + ": " + label.toLowerCase());
    announced.push(stage.label + ": " + label);
  });

  root.setAttribute("aria-label", "Observed decision: " + announced.join(", "));
  return root;
}
~~~

All visible values come from DECISION_STATE_LABELS. Do not place route names, errors, prompts, or raw context into the ribbon.

- [ ] **Step 7: Integrate only at existing update seams**

At the end of updateOrchestrationSignalBar, after existing Health synchronization, read only the already supplied pipelineSnapshot object:

~~~js
renderDecisionRibbon(presentObservedDecision({
  route: partial.pipelineSnapshot && typeof partial.pipelineSnapshot === "object"
    ? String(partial.pipelineSnapshot.route || "")
    : "",
  answerMode: partial.answerMode || "",
  streamStatus: partial.streamStatus || "",
  healthOverlay: currentTurnHealthOverlay
}));
~~~

Inside renderEvidenceRail, after evidenceQuality is determined and before returning holder:

~~~js
renderDecisionRibbon(presentObservedDecision({
  route: String(pipeline.route || ""),
  citationState,
  answerMode: context.answerMode || "",
  streamStatus: context.streamStatus || "",
  healthOverlay: currentTurnHealthOverlay
}));
~~~

For the direct server-cancel event branch that bypasses the normal orchestration update, invoke:

~~~js
renderDecisionRibbon(presentObservedDecision({
  streamStatus: "cancelled",
  healthOverlay: { ...currentTurnHealthOverlay, kind: "stopped" }
}));
~~~

Immediately after resetCurrentTurnHealthOverlay in startNewChatSession, invoke the reset presentation explicitly:

~~~js
renderDecisionRibbon(presentObservedDecision({
  streamStatus: "idle",
  healthOverlay: currentTurnHealthOverlay
}));
~~~

Preserve all existing isAssistantStreamStopped and isActiveStreamRenderTarget guards before rendering. Do not move or weaken the terminal latch.

- [ ] **Step 8: Run GREEN and the authorization-gated commit checkpoint**

~~~powershell
node '.\scripts\chat_ui_stream_contract_tests.js'
~~~

Expected: static ribbon, pure-state, DOM-render, and all existing lifecycle contracts pass.

If explicit commit authority exists:

~~~powershell
git add -- 'main/resources/templates/chat-ui.html' 'main/resources/static/js/chat.js' 'scripts/chat_ui_stream_contract_tests.js'
git commit -m 'feat: add observed chat decision ribbon'
~~~

Otherwise record commit=not_authorized and do not stage.

---

### Task 5: Select and Render One Authoritative Diagnostics Summary

**Files:**
- Modify: scripts/chat_ui_stream_contract_tests.js beside the existing #diagnosticsSummary VM assertions
- Modify: main/resources/static/js/chat.js beside setDebugHeartbeatSummary and renderLiveDebugHeartbeat
- Preserve: main/resources/templates/chat-ui.html #diagnosticsSummary and .diagnostics-stack

**Interfaces:**
- Consumes: currentTurnHealthOverlay, answerMode, stream status/context, current answer evidenceQuality.code, and actual Stop-button enablement.
- Produces: selectPrimaryDiagnostic(input) and renderPrimaryDiagnostic(presentation).
- Does not consume as action authority: raw debugHeartbeatSummaryState.nextAction, raw provider errors, or stale lastServerHealth.

- [ ] **Step 1: Write failing selector tests**

~~~js
const pendingDiagnostic = JSON.parse(vm.runInContext(
  "JSON.stringify(selectPrimaryDiagnostic({" +
    "healthOverlay: { kind: 'pending' }," +
    "streamStatus: 'pending'," +
    "streamContext: 'client-wait'," +
    "isStopAvailable: true" +
  "}))",
  context
));
assert(pendingDiagnostic.code === "pending", "pending Health must own the primary summary");
assert(pendingDiagnostic.action.code === "stop", "only the enabled existing Stop control may become an action");
assert(pendingDiagnostic.summaryText === "Response pending · current turn · Stop available", "primary summary must expose bounded fact and safe action");

const stoppedDiagnostic = JSON.parse(vm.runInContext(
  "JSON.stringify(selectPrimaryDiagnostic({" +
    "healthOverlay: { kind: 'stopped' }," +
    "answerMode: 'final'," +
    "isStopAvailable: false" +
  "}))",
  context
));
assert(stoppedDiagnostic.code === "stopped", "stopped Health must outrank final answer mode");
assert(stoppedDiagnostic.action.code === "none", "terminal cancellation must not invent a retry");

const attentionDiagnostic = JSON.parse(vm.runInContext(
  "JSON.stringify(selectPrimaryDiagnostic({" +
    "healthOverlay: { kind: 'attention' }," +
    "streamContext: 'retry-now raw-provider-text'," +
    "isStopAvailable: false" +
  "}))",
  context
));
assert(attentionDiagnostic.code === "attention", "attention must remain a bounded warning");
assert(attentionDiagnostic.action.code === "none", "raw next action must not become a command");
assert(!JSON.stringify(attentionDiagnostic).includes("raw-provider-text"), "raw diagnostic text must not enter primary output");

const unavailableDiagnostic = JSON.parse(vm.runInContext(
  "JSON.stringify(selectPrimaryDiagnostic({" +
    "healthOverlay: { kind: 'none' }," +
    "answerMode: 'chat'," +
    "evidenceQualityCode: 'external-unavailable'" +
  "}))",
  context
));
assert(unavailableDiagnostic.code === "external-unavailable", "current unavailable evidence must outrank a neutral completion");
assert(unavailableDiagnostic.action.code === "none", "provider-disabled evidence must not invent an action");
~~~

Run the Node contract and expect FAIL because selectPrimaryDiagnostic is not defined.

- [ ] **Step 2: Implement the pure selector**

~~~js
function selectPrimaryDiagnostic({
  healthOverlay = currentTurnHealthOverlay,
  answerMode = "",
  streamStatus = "",
  evidenceQualityCode = "",
  isStopAvailable = false
} = {}) {
  const kind = String(healthOverlay?.kind || "none");
  const mode = normalizedAnswerMode(answerMode || streamStatus);
  const evidenceCode = String(evidenceQualityCode || "");
  const noneAction = Object.freeze({
    code: "none",
    visibleLabel: "",
    accessibleLabel: "",
    sourceBasis: "no-proven-safe-control"
  });
  const stopAction = isStopAvailable
    ? Object.freeze({
        code: "stop",
        visibleLabel: "Stop available",
        accessibleLabel: "Stop the current response",
        sourceBasis: "existing-stop-control"
      })
    : noneAction;

  if (kind === "stopped") {
    return {
      code: "stopped",
      status: "OK",
      title: "Response stopped",
      summaryText: "Response stopped · user cancelled",
      action: noneAction,
      supportingFacts: [{ code: "user-cancelled", sourceBasis: "currentTurnHealthOverlay.kind" }]
    };
  }
  if (kind === "attention") {
    return {
      code: "attention",
      status: "WARN",
      title: "Needs attention",
      summaryText: "Needs attention · current turn",
      action: noneAction,
      supportingFacts: [{ code: "current-turn-attention", sourceBasis: "currentTurnHealthOverlay.kind" }]
    };
  }
  if (kind === "pending" || kind === "responding") {
    return {
      code: kind,
      status: kind === "pending" ? "WARN" : "OK",
      title: kind === "pending" ? "Response pending" : "Responding",
      summaryText: (kind === "pending" ? "Response pending" : "Responding")
        + " · current turn"
        + (isStopAvailable ? " · Stop available" : ""),
      action: stopAction,
      supportingFacts: [{ code: "current-turn-" + kind, sourceBasis: "currentTurnHealthOverlay.kind" }]
    };
  }
  if (evidenceCode === "external-unavailable") {
    return {
      code: evidenceCode,
      status: "WARN",
      title: "External evidence unavailable",
      summaryText: "External evidence unavailable · current answer",
      action: noneAction,
      supportingFacts: [{ code: evidenceCode, sourceBasis: "pipeline.disabledReason" }]
    };
  }
  if (evidenceCode === "partial-citation" || evidenceCode === "retrieved-uncited") {
    const partial = evidenceCode === "partial-citation";
    return {
      code: evidenceCode,
      status: "WARN",
      title: partial ? "Evidence partly cited" : "Retrieved evidence is uncited",
      summaryText: (partial ? "Evidence partly cited" : "Retrieved evidence is uncited") + " · current answer",
      action: noneAction,
      supportingFacts: [{ code: evidenceCode, sourceBasis: "evidenceCitationState" }]
    };
  }
  if (successfulAnswerMode(mode)) {
    return {
      code: "complete",
      status: "OK",
      title: "Response complete",
      summaryText: "Response complete · answer mode observed",
      action: noneAction,
      supportingFacts: [{ code: "answer-mode-observed", sourceBasis: "answerMode" }]
    };
  }
  return {
    code: "not-observed",
    status: "UNKNOWN",
    title: "State not observed",
    summaryText: "State not observed",
    action: noneAction,
    supportingFacts: []
  };
}
~~~

The selector deliberately excludes streamContext and debug nextAction from returned copy.

- [ ] **Step 3: Write the failing summary-render test**

~~~js
context.__primaryDiagnostic = {
  code: "pending",
  status: "WARN",
  title: "Response pending",
  summaryText: "Response pending · current turn · Stop available",
  action: { code: "stop" },
  supportingFacts: []
};
vm.runInContext("renderPrimaryDiagnostic(__primaryDiagnostic)", context);
assert(
  elements.get("diagnosticsSummary").dataset.diagnosticCode === "pending",
  "Diagnostics summary must expose one bounded primary code"
);
assert(
  elements.get("diagnosticsSummary").textContent === "Response pending · current turn · Stop available",
  "Diagnostics summary must expose bounded what/fact/action copy"
);
vm.runInContext("setDebugHeartbeatSummary('OK', 'live:OK wait:none')", context);
assert(
  elements.get("diagnosticsSummary").textContent === "Response pending · current turn · Stop available",
  "background heartbeat must not overwrite the current-answer primary summary"
);
~~~

Place these assertions immediately after the two pre-existing bounded diagnosticsSummary assertions so those baseline cases still run with no current-answer primary code. Run the contract and expect FAIL because renderPrimaryDiagnostic is not defined.

- [ ] **Step 4: Implement the summary renderer and current-turn precedence**

~~~js
function renderPrimaryDiagnostic(presentation = {}) {
  if (!dom.diagnosticsSummary) return null;
  const code = String(presentation.code || "not-observed");
  const status = String(presentation.status || "UNKNOWN");
  const title = String(presentation.summaryText || presentation.title || "State not observed");
  dom.diagnosticsSummary.dataset.status = status;
  dom.diagnosticsSummary.dataset.diagnosticCode = code;
  dom.diagnosticsSummary.textContent = title;
  dom.diagnosticsSummary.setAttribute("aria-label", "Diagnostics: " + title);
  return dom.diagnosticsSummary;
}
~~~

After syncCurrentTurnHealthOverlay in renderLiveDebugHeartbeat, invoke:

~~~js
renderPrimaryDiagnostic(selectPrimaryDiagnostic({
  healthOverlay: currentTurnHealthOverlay,
  streamStatus: streamStatus || "",
  isStopAvailable: Boolean(
    dom.stopBtn &&
    dom.stopBtn.hidden === false &&
    dom.stopBtn.style.display !== "none" &&
    dom.stopBtn.disabled === false
  )
}));
~~~

In setDebugHeartbeatSummary, wrap only the existing diagnosticsSummary assignment with this guard; the detailed heartbeat card still updates on every call:

~~~js
const primaryCode = String(dom.diagnosticsSummary?.dataset?.diagnosticCode || "");
if (
  dom.diagnosticsSummary &&
  currentTurnHealthOverlay.kind === "none" &&
  (!primaryCode || primaryCode === "not-observed")
) {
  dom.diagnosticsSummary.dataset.status = card.dataset.status;
  dom.diagnosticsSummary.textContent = card.dataset.status === "OK" ? "Core signals live" : title;
}
~~~

Background heartbeat refreshes may still update the detailed heartbeat card, but may not overwrite a bounded current-answer primary summary.

In the final event branch, after finalMode is known, invoke:

~~~js
renderPrimaryDiagnostic(selectPrimaryDiagnostic({
  healthOverlay: currentTurnHealthOverlay,
  answerMode: finalMode,
  isStopAvailable: false
}));
~~~

Inside renderEvidenceRail, after evidenceQuality is derived, invoke:

~~~js
renderPrimaryDiagnostic(selectPrimaryDiagnostic({
  healthOverlay: currentTurnHealthOverlay,
  answerMode: context.answerMode || context.answer_mode || context.mode || "",
  evidenceQualityCode: evidenceQuality.code,
  isStopAvailable: Boolean(
    dom.stopBtn &&
    dom.stopBtn.hidden === false &&
    dom.stopBtn.style.display !== "none" &&
    dom.stopBtn.disabled === false
  )
}));
~~~

This call occurs after the final branch reset and therefore lets degraded or unavailable evidence outrank a neutral completed answer exactly as specified.

Immediately after the existing submit path calls setComposerBusy(true), invoke the selector once with the responding currentTurnHealthOverlay and actual visible/enabled state so the bounded summary can say Stop available without adding or clicking a second control:

~~~js
renderPrimaryDiagnostic(selectPrimaryDiagnostic({
  healthOverlay: currentTurnHealthOverlay,
  streamStatus: "connecting",
  isStopAvailable: Boolean(
    dom.stopBtn &&
    dom.stopBtn.hidden === false &&
    dom.stopBtn.style.display !== "none" &&
    dom.stopBtn.disabled === false
  )
}));
~~~

Immediately after resetCurrentTurnHealthOverlay in startNewChatSession, render a not-observed primary diagnostic:

~~~js
renderPrimaryDiagnostic(selectPrimaryDiagnostic({
  healthOverlay: currentTurnHealthOverlay,
  streamStatus: "idle",
  isStopAvailable: false
}));
~~~

Background heartbeat may then supply its existing bounded no-current-answer fallback.

In the direct server-cancel branch, invoke the selector with healthOverlay.kind set to stopped. Do not create a Retry action; its authority is evidence_needed.

- [ ] **Step 5: Prove detailed Diagnostics remain secondary and intact**

Add static assertions:

~~~js
assert(
  /<summary><span>Diagnostics<\/span><span id="diagnosticsSummary"/.test(template),
  "bounded primary Diagnostics copy must remain in the native disclosure summary"
);
assert(
  /<div class="diagnostics-stack">[\s\S]*data-debug-heartbeat="root"/.test(template),
  "detailed heartbeat panels must remain behind Diagnostics"
);
~~~

Run:

~~~powershell
node '.\scripts\chat_ui_stream_contract_tests.js'
~~~

Expected: selector, renderer, current-turn precedence, raw-copy exclusion, and every existing Diagnostics assertion pass.

- [ ] **Step 6: Run the authorization-gated commit checkpoint**

If explicit commit authority exists:

~~~powershell
git add -- 'main/resources/static/js/chat.js' 'scripts/chat_ui_stream_contract_tests.js'
git commit -m 'feat: prioritize current chat diagnostics'
~~~

Otherwise record commit=not_authorized and do not stage.

---

### Task 6: Add the Warm-neutral Visual Hierarchy and Seven-view Geometry Contract

**Files:**
- Modify: main/resources/static/css/chat-style.css after the final evidence/message and status-rail rules
- Modify: scripts/chat_ui_stream_contract_tests.js static CSS contract block
- Modify: scripts/chat_ui_geometry_contract_tests.js viewport array and measured Decision ribbon bounds

**Interfaces:**
- Consumes: evidence-quality data attributes, six ribbon stage data-state values, existing CSS variables, and #decisionRibbon.
- Produces: responsive, readable, non-scroll-nested badge/ribbon presentation and seven measured viewport results.

- [ ] **Step 1: Write failing CSS structure assertions**

Add exact three-argument cssRuleAfter calls; the existing helper signature is cssRuleAfter(text, scopeStart, selector):

~~~js
const decisionFirstStylesStart = stylesheet.indexOf("/* Decision-first evidence console surfaces. */");
const evidenceQualityRule = cssRuleAfter(stylesheet, decisionFirstStylesStart, ".evidence-quality-badge {");
const decisionRibbonRule = cssRuleAfter(stylesheet, decisionFirstStylesStart, ".status-rail > .decision-ribbon {");
assert(
  decisionFirstStylesStart >= 0 && evidenceQualityRule.includes("overflow-wrap: anywhere"),
  "Evidence badge styling must live in the final decision-first layer"
);
assert(
  decisionRibbonRule.includes("flex: 1 1 100%"),
  "Decision ribbon styling must refine the final status rail"
);
assert(
  stylesheet.includes('.decision-ribbon [data-state="not-observed"]'),
  "Decision ribbon must visibly distinguish not-observed"
);
assert(
  stylesheet.includes("@media (prefers-reduced-motion: reduce)"),
  "Decision-first UI must preserve reduced-motion handling"
);
~~~

Replace the harness variable finalMobileLastPillRule with finalMobileHealthRule, extracted from .status-pill[data-health-pill], and replace all three existing uses. Add an assertion that both the final base and final mobile layers use that semantic selector. This prevents the new final ribbon child from silently removing Health's wide layout.

Run the Node contract and expect a focused failure because the new CSS marker/selectors are absent. A helper-arity or syntax failure is not the intended RED.

- [ ] **Step 2: Expand the exact viewport array**

Replace the current two-case array with:

~~~js
const viewports = [
  { width: 320, height: 760 },
  { width: 375, height: 900 },
  { width: 390, height: 700 },
  { width: 639, height: 900 },
  { width: 760, height: 900 },
  { width: 1024, height: 900 },
  { width: 1440, height: 900 }
];
~~~

Keep every existing wrapper, composer, input hit-test, horizontal-overflow, and single-scroll-owner assertion.

- [ ] **Step 3: Add Decision ribbon geometry to the existing page evaluation**

Inside the existing viewport loop, add one compatible Playwright CLI evaluation after the current overflow-style evaluations:

~~~js
const decision = parseEval(cli(
  "eval",
  "JSON.stringify((() => {" +
    "const root=document.querySelector('#decisionRibbon');" +
    "const rect=root?.getBoundingClientRect();" +
    "return {" +
      "rect:rect?rect.toJSON():null," +
      "stages:Array.from(document.querySelectorAll('#decisionRibbon [data-decision-stage]')).map((node)=>({" +
        "stage:node.dataset.decisionStage," +
        "rect:node.getBoundingClientRect().toJSON()," +
        "text:(node.textContent||'').trim()," +
        "clipped:node.scrollWidth>node.clientWidth+1||node.scrollHeight>node.clientHeight+1" +
      "}))" +
    "};" +
  "})())"
));
~~~

Add decisionRect: decision.rect and decisionStages: decision.stages to measurement. Extend assertGeometry(measurement) with its existing failures-array style:

~~~js
if (!measurement.decisionRect) {
  failures.push("Decision ribbon is not measurable");
} else if (
  measurement.decisionRect.left < -1 ||
  measurement.decisionRect.right > measurement.viewportWidth + 1 ||
  measurement.decisionRect.bottom > measurement.wrapperBottom + 1
) {
  failures.push("Decision ribbon escapes the visible chat wrapper");
}
if (measurement.decisionStages.length !== 6) {
  failures.push("Decision ribbon does not expose six stages");
}
for (const stage of measurement.decisionStages) {
  if (!stage.text) failures.push(`${stage.stage || "unknown"} has no readable text`);
  if (stage.rect.left < -1 || stage.rect.right > measurement.viewportWidth + 1) {
    failures.push(`${stage.stage || "unknown"} clips outside the viewport`);
  }
  if (stage.clipped) failures.push(`${stage.stage || "unknown"} text is clipped`);
}
~~~

No nested horizontal scrolling is introduced; every stage must be directly reachable at all seven sizes.

- [ ] **Step 4: Witness the CSS and live-geometry RED states**

Before CSS implementation, run:

~~~powershell
node '.\scripts\chat_ui_stream_contract_tests.js'
node --check '.\scripts\chat_ui_geometry_contract_tests.js'
$env:CHAT_UI_BASE_URL = 'http://127.0.0.1:18166/chat-ui'
node '.\scripts\chat_ui_geometry_contract_tests.js'
~~~

Expected: the stream contract fails on the absent decision-first CSS marker; node --check exits 0; a pre-change served runtime fails specifically because the Decision ribbon is absent. A stale-runtime failure is valid lineage evidence, not final geometry proof. Do not patch around stale served HTML.

- [ ] **Step 5: Add exact scoped CSS after the final status/message rules**

In the authoritative final conversation-first layer, replace both .status-pill:last-child selectors—the base flex-basis rule and the final max-width:760px grid-column rule—with .status-pill[data-health-pill]. Keep their declarations unchanged.

Append this final layer before the final responsive blocks:

~~~css
/* Decision-first evidence console surfaces. */
.evidence-rail {
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: start;
}

.evidence-rail > strong {
    grid-column: 1;
    align-self: center;
}

.evidence-rail > .evidence-list,
.evidence-rail > .evidence-more {
    grid-column: 1 / -1;
}

.evidence-quality-badge {
    grid-column: 2;
    display: inline-flex;
    align-items: center;
    justify-self: end;
    width: fit-content;
    max-width: 100%;
    margin: 0;
    padding: 4px 9px;
    border: 1px solid var(--line-strong);
    border-radius: 999px;
    background: var(--surface);
    color: var(--muted);
    font-size: 12px;
    font-weight: 850;
    line-height: 1.35;
    overflow-wrap: anywhere;
}

.evidence-quality-badge[data-tone="ok"] {
    border-color: rgba(33, 107, 85, .34);
    background: var(--tint);
    color: var(--accent-strong);
}

.evidence-quality-badge[data-tone="warn"] {
    border-color: rgba(154, 92, 18, .36);
    background: #fff7e8;
    color: var(--warning);
}

.evidence-quality-badge[data-tone="neutral"] {
    background: var(--surface-soft);
    color: var(--muted);
}

.status-rail > .decision-ribbon {
    flex: 1 1 100%;
    min-width: 0;
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    align-items: start;
    gap: 10px;
    padding: 10px 12px;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface-soft);
}

.decision-ribbon__title {
    padding-top: 4px;
    color: var(--ink);
    font-size: 12px;
    font-weight: 900;
    letter-spacing: .04em;
    text-transform: uppercase;
}

.decision-ribbon__stages {
    min-width: 0;
    display: grid;
    grid-template-columns: repeat(6, minmax(0, 1fr));
    gap: 6px;
    margin: 0;
    padding: 0;
    list-style: none;
}

.decision-ribbon__stages > li {
    min-width: 0;
    display: grid;
    gap: 2px;
    padding: 6px 7px;
    border-left: 3px solid var(--line-strong);
    border-radius: 6px;
    background: var(--surface);
    color: var(--muted);
}

.decision-ribbon__stage {
    color: var(--ink);
    font-size: 11px;
    font-weight: 850;
}

.decision-ribbon__stages [data-decision-state-label] {
    font-size: 11px;
    line-height: 1.3;
    overflow-wrap: anywhere;
}

.decision-ribbon [data-state="observed-complete"] {
    border-left-color: var(--accent);
    background: var(--tint);
    color: var(--accent-strong);
}

.decision-ribbon [data-state="observed-active"],
.decision-ribbon [data-state="observed-degraded"] {
    border-left-color: var(--warning);
    background: #fff7e8;
    color: var(--warning);
}

.decision-ribbon [data-state="observed-cancelled"] {
    border-left-color: var(--danger);
    background: #fff1f1;
    color: var(--danger);
}

.decision-ribbon [data-state="not-observed"] {
    border-left-color: var(--line-strong);
    background: var(--surface);
    color: var(--muted);
}
~~~

Inside the authoritative final max-width:760px block add:

~~~css
.status-rail > .decision-ribbon {
    grid-column: 1 / -1;
    grid-template-columns: minmax(0, 1fr);
}

.decision-ribbon__stages {
    grid-template-columns: repeat(3, minmax(0, 1fr));
}

.evidence-rail {
    grid-template-columns: minmax(0, 1fr);
}

.evidence-quality-badge {
    grid-column: 1;
    justify-self: start;
    white-space: normal;
}
~~~

Inside the existing max-width:760px and max-height:760px block add:

~~~css
.decision-ribbon__stages {
    grid-template-columns: repeat(2, minmax(0, 1fr));
}
~~~

Do not change .diagnostics-stack max-height/overflow, the Health live region, or page-level scroll ownership. Add no blinking, looping animation, nested horizontal scrolling, or dark-workspace override.

- [ ] **Step 6: Run local GREEN; defer live geometry GREEN to the fresh runtime**

~~~powershell
node '.\scripts\chat_ui_stream_contract_tests.js'
node --check '.\scripts\chat_ui_geometry_contract_tests.js'
~~~

Expected: the static/VM contract and geometry syntax pass. Live geometry remains unclaimed until Task 7 starts an attributable runtime from the changed sources.

- [ ] **Step 7: Run the authorization-gated commit checkpoint**

If explicit commit authority exists:

~~~powershell
git add -- 'main/resources/static/css/chat-style.css' 'scripts/chat_ui_stream_contract_tests.js' 'scripts/chat_ui_geometry_contract_tests.js'
git commit -m 'style: refine evidence console hierarchy'
~~~

Otherwise record commit=not_authorized and do not stage.

---

### Task 7: Prove Focused Contracts, Build Integrity, and Fresh Runtime Lineage

**Files:**
- Verify: all five implementation/test targets
- Read/use: scripts/chat_ui_vibe_listener.ps1
- Generated only: isolated build/cache output and runtime manifest owned by the existing listener workflow

**Interfaces:**
- Consumes: GREEN local contracts and current source hashes.
- Produces: current build identity, source/served asset equality, exact browserTargetUrl, and a runtime safe for Browser scenarios.

- [ ] **Step 1: Recheck all target preimages against the source-edit ledger**

Verify that no external writer changed a target between tasks. When a planned prior task intentionally changed a file, compare against that task's recorded postimage rather than the original preimage. Stop on any unexplained mismatch.

- [ ] **Step 2: Run the focused Node contract**

~~~powershell
node '.\scripts\chat_ui_stream_contract_tests.js'
~~~

Expected: the existing terminal line reports the chat UI stream heartbeat contract OK and the process exits 0.

- [ ] **Step 3: Run isolated Gradle verification**

~~~powershell
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-evidence-console'
$gradleHome = Join-Path $env:TEMP 'awx-gradle-evidence-console'
$projectCache = Join-Path $env:TEMP 'awx-project-cache-evidence-console'
$env:GRADLE_USER_HOME = $gradleHome

.\gradlew.bat chatUiTest checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test --no-daemon --project-cache-dir $projectCache
~~~

Expected: BUILD SUCCESSFUL. A stale-output lock or foreign runtime is not a reason to clean broad output; use the existing split-output/runtime ownership workflow.

- [ ] **Step 4: Ask the listener workflow for a plan-only port decision**

~~~powershell
$portPlanJson = .\scripts\chat_ui_vibe_listener.ps1 -Port 18168 -BuildHostId desktop-evidence-console -OutDir 'build\codex-evidence-console' -ReadyTimeoutSeconds 120 -PlanOnly | Out-String
$portPlan = $portPlanJson | ConvertFrom-Json
if (-not $portPlan.ok -or $portPlan.mutationAllowed) { throw ('unsafe-port-plan:' + $portPlan.status) }
$portPlan.selectedPorts | ConvertTo-Json -Compress
~~~

Expected: a safe selected server/management/netty port set that does not touch protected Ollama ports or foreign listeners.

- [ ] **Step 5: Start or restart the attributable runtime**

Run the same command without -PlanOnly, capture its documented JSON result, and validate the exact returned lineage fields:

~~~powershell
$runtimeJson = .\scripts\chat_ui_vibe_listener.ps1 -Port 18168 -BuildHostId desktop-evidence-console -OutDir 'build\codex-evidence-console' -ReadyTimeoutSeconds 120 | Out-String
$runtime = $runtimeJson | ConvertFrom-Json
if (-not $runtime.ok -or $runtime.status -ne 'listener-ready') { throw ('runtime-not-ready:' + $runtime.status) }
if ([string]$runtime.sourceAssetHash -notmatch '^[0-9a-fA-F]{64}$') { throw 'source-asset-hash-invalid' }
if ([string]$runtime.servedAssetHash -notmatch '^[0-9a-fA-F]{64}$') { throw 'served-asset-hash-invalid' }
if ([string]$runtime.sourceAssetHash -ne [string]$runtime.servedAssetHash) { throw 'served-asset-hash-mismatch' }
$browserTargetUrl = [string]$runtime.browserTargetUrl
if ($browserTargetUrl -notmatch '^http://127\.0\.0\.1:\d+/chat-ui\?awxRuntime=[^&]+$') { throw 'browser-target-invalid' }
$http = Invoke-WebRequest -Uri $browserTargetUrl -UseBasicParsing
if ($http.StatusCode -ne 200) { throw ('chat-ui-http:' + $http.StatusCode) }
[pscustomobject]@{
  status = $runtime.status
  httpStatus = $http.StatusCode
  sourceAssetHash = ([string]$runtime.sourceAssetHash).ToLowerInvariant()
  servedAssetHash = ([string]$runtime.servedAssetHash).ToLowerInvariant()
  browserTargetUrl = $browserTargetUrl
} | ConvertTo-Json -Compress
~~~

Expected evidence:

~~~text
status=listener-ready
httpStatus=200
sourceAssetHash=64 lowercase hexadecimal characters
servedAssetHash=the identical 64 lowercase hexadecimal characters
browserTargetUrl=the exact loopback URL emitted by the listener, including its nonempty awxRuntime lineage marker
~~~

The runtime marker is lineage metadata, not a credential. Do not claim freshness when the hashes differ or the manifest belongs to another process.

- [ ] **Step 6: Run geometry against the exact fresh target**

Use the emitted browserTargetUrl rather than a remembered port:

~~~powershell
$env:CHAT_UI_BASE_URL = $browserTargetUrl
node '.\scripts\chat_ui_geometry_contract_tests.js'
~~~

Expected: all seven viewport objects pass with no page overflow, composer overlap, clipped stage, missing control, or second vertical scroll owner.

---

### Task 8: Run Browser-visible Scenario Proof and the First Bounded Soak Wave

**Files:**
- Modify: none unless a scenario reproduces a defect and a new RED cycle is opened
- Observe: fresh /chat-ui DOM and visible UI
- Preserve: the exact asset/runtime lineage from Task 7

**Interfaces:**
- Consumes: exact browserTargetUrl and eight approved scenario IDs.
- Produces: redacted visible-state evidence, viewport measurements, failure classes, and same-input RED/GREEN decisions.

- [ ] **Step 1: Open the exact fresh target with the Browser skill**

Use the in-app Browser, make the inspected tab visible, name the session demo1-decision-first-evidence-console, and keep the tab available for handoff. Do not use Computer to manipulate the in-app Browser.

Record:

~~~text
runtime URL
document title
source/served asset hash pair
visible core status rail
visible Decision ribbon
Diagnostics collapsed by default
composer enabled state
~~~

- [ ] **Step 2: Run deterministic evidence-presentation fixtures**

For cited, partial, retrieved-uncited, and unknown states, invoke the real presentation helper against a temporary, clearly labeled assistant fixture in the live page. The fixture must contain synthetic text only and must be removed after each assertion.

For each scenario verify:

~~~text
one visible evidence-quality badge
badge data code equals expected code
visible and accessible labels agree
Evidence count remains separate
no raw pipeline reason appears
no provider/model-success claim appears
~~~

Scenario IDs:

~~~text
evidence-cited-001
evidence-partial-001
evidence-uncited-001
evidence-unknown-001
~~~

Fixture success proves the UI presenter and CSS only. It does not prove retrieval, citation entailment, or provider execution.

- [ ] **Step 3: Run the Diagnostics priority fixture**

Use synthetic current-turn state plus a stale advisory state. Verify:

~~~text
scenarioId=diagnostic-priority-001
diagnosticsSummaryCount=1
primaryCode=current state
rawDiagnosticStillAvailable=true
rawNextActionPromoted=false
HealthConflict=false
~~~

Open Diagnostics and visually confirm the primary summary appears first while .diagnostics-stack remains bounded and scrollable.

- [ ] **Step 4: Run one safe pending/cancel scenario**

Send one non-sensitive operations prompt. Record only a scenario ID and state transitions, not the raw prompt or full response.

Expected sequence:

~~~text
scenarioId=pending-cancel-001
Health=Response pending or Responding
Stop=available
Decision Answer=observed-active
user action=Stop
terminal=server-cancelled or existing equivalent
Health=Response stopped
Decision Answer=observed-cancelled
Decision Recover=observed-cancelled
modelAnswerObserved=false when no final answer exists
providerAttemptObserved=false unless direct attempt evidence exists
~~~

Do not wait past a decisive user-control check merely to consume elapsed time.

- [ ] **Step 5: Prove late-event and reload terminal integrity**

Use the existing controlled test seam for late events and the live page for reload continuity:

~~~text
scenarioId=late-after-cancel-001
lateTokenChangesTerminal=false
lateHeartbeatChangesTerminal=false
lateFinalCreatesSuccess=false

scenarioId=reload-stopped-001
stoppedAssistantBubbleCount=1
Health=Response stopped
composerUsable=true
duplicateLiveAnnouncement=false
~~~

A Browser environment limitation is evidence_needed unless corroborated by the app-level contract.

- [ ] **Step 6: Inspect all seven viewports visibly**

For each approved viewport, confirm the automated geometry result against the visible page:

~~~text
320x760
375x900
390x700
639x900
760x900
1024x900
1440x900
~~~

Verify readable six-stage ribbon text, evidence-badge wrapping, Diagnostics summary-before-detail, no transcript/composer overlap, reachable Stop/send controls, and no page-level horizontal overflow. Reset temporary viewport overrides before handoff.

- [ ] **Step 7: Keep Computer evidence separate and count-only**

Only if current Windows UI state changes a decision, list candidate browser windows and report counts. Do not choose among ambiguous Edge/Chrome windows and do not manipulate the Codex desktop window. Record computerEvidence=supporting-only or computerEvidence=not-used.

- [ ] **Step 8: Repair only reproducible defects inside the approved seam**

A defect belongs to this plan only when the same scenario reproduces it in one of:

~~~text
main/resources/templates/chat-ui.html
main/resources/static/js/chat.js
main/resources/static/css/chat-style.css
scripts/chat_ui_stream_contract_tests.js
scripts/chat_ui_geometry_contract_tests.js
~~~

For each qualifying defect:

1. add one focused failing assertion;
2. run it and confirm the expected RED failure class;
3. make the smallest active-seam change;
4. rerun the same assertion and Browser scenario;
5. rerun all focused contracts.

Record backend, provider, runtime-lifecycle, unrelated UI, or external-system discoveries as evidence_needed for a later bounded wave. Do not widen this plan.

---

### Task 9: Final Verification, Secret Scan, and Requirement Audit

**Files:**
- Inspect: exact changed files only
- Modify: none unless verification opens a new focused RED cycle

**Interfaces:**
- Consumes: all task-local GREEN evidence.
- Produces: final hashes, verification matrix, unresolved evidence, and an honest completion decision for this first wave.

- [ ] **Step 1: Run the complete focused verification ladder again**

~~~powershell
node '.\scripts\chat_ui_stream_contract_tests.js'
$env:CHAT_UI_BASE_URL = $browserTargetUrl
node '.\scripts\chat_ui_geometry_contract_tests.js'

$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-evidence-console-final'
$finalGradleHome = Join-Path $env:TEMP 'awx-gradle-evidence-console-final'
$finalProjectCache = Join-Path $env:TEMP 'awx-project-cache-evidence-console-final'
$env:GRADLE_USER_HOME = $finalGradleHome
.\gradlew.bat chatUiTest checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test bootJar --no-daemon --project-cache-dir $finalProjectCache
~~~

Expected: every command exits 0 against current sources. Report the first failing command and stop the affected claim if any command fails.

- [ ] **Step 2: Compute postimage hashes**

~~~powershell
$changedPaths = @(
  '.agents/skills/demo1-chat-design-acceptance/SKILL.md',
  '.agents/skills/demo1-chat-design-acceptance/agents/openai.yaml',
  'main/resources/templates/chat-ui.html',
  'main/resources/static/js/chat.js',
  'main/resources/static/css/chat-style.css',
  'scripts/chat_ui_stream_contract_tests.js',
  'scripts/chat_ui_geometry_contract_tests.js',
  'docs/superpowers/specs/2026-09-04-decision-first-evidence-console-design.md',
  'docs/superpowers/plans/2026-09-04-decision-first-evidence-console.md'
)
$existingChangedPaths = @($changedPaths | Where-Object { Test-Path -LiteralPath $_ })
$existingChangedPaths | ForEach-Object {
  [pscustomobject]@{
    path = $_
    sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()
  }
} | ConvertTo-Json -Compress
~~~

- [ ] **Step 3: Run a count-only secret scan over exact changed files**

~~~powershell
$secretPatterns = @(
  'sk-[A-Za-z0-9_-]{20,}',
  'Bearer\s+[A-Za-z0-9._~+/=-]{20,}',
  "(?i)(api[_-]?key|client[_-]?secret|owner[_-]?token)\s*[:=]\s*[`"'][^`"']{8,}[`"']"
)
$secretHitCount = 0
foreach ($path in $existingChangedPaths) {
  foreach ($pattern in $secretPatterns) {
    $secretHitCount += @(Select-String -LiteralPath $path -Pattern $pattern -AllMatches).Count
  }
}
Write-Output ("secretHitCount={0}" -f $secretHitCount)
if ($secretHitCount -ne 0) { throw 'secret-risk' }
~~~

Do not print matched lines or values.

- [ ] **Step 4: Inspect exact status and bounded diffs**

~~~powershell
git status --short -- $existingChangedPaths
git diff --stat -- $existingChangedPaths
git diff --check -- $existingChangedPaths
~~~

Because some targets began untracked, the source-owner ledger's original preimage and final postimage hashes are part of the review evidence. Do not claim git diff covers bytes that Git does not track.

- [ ] **Step 5: Audit every first-wave acceptance criterion**

Report each as PASS, FAIL, or evidence_needed:

~~~text
active source owner and preimages
order-stable source-edit APPLY
skill no-guidance RED and WITH-skill GREEN, or skill-redundant stop
skill quick_validate
honest evidence taxonomy
Evidence count non-promotion
visible/accessible label agreement
Decision ribbon uses observed current-turn state
Health remains authoritative
one Diagnostics primary summary
raw action/error exclusion
detail preservation
pending/cancel/late/reload/focus/composer
seven responsive viewports
fresh runtime and asset lineage
Browser-visible scenario deck
Computer lane separation
no unauthorized Java/provider/Supabase/runtime change
no unsupported success claim
secretHitCount=0
~~~

- [ ] **Step 6: Decide goal status without shrinking the original objective**

This implementation plan is complete only if every first-wave criterion passes. The active 9-hour design-and-defect objective remains incomplete when later bounded browser investigation or discovered in-scope defects remain. Mark the overall goal complete only after a separate full-objective audit proves the skill, approved design change, browser-visible verification, and requested defect investigation are all satisfied.

- [ ] **Step 7: Run the authorization-gated final commit checkpoint**

Under current authority, skip staging and commits. If the user grants explicit commit authority after reviewing final evidence:

~~~powershell
git add -- $existingChangedPaths
git commit -m 'feat: add decision-first chat evidence console'
~~~

Never push or deploy without a separate explicit request.
