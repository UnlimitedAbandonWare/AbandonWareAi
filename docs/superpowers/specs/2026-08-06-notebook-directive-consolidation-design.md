# Notebook Directive Consolidation and Chat Source Repair Design

Status: Scope A selected; written-design review pending before implementation

Date: 2026-08-06

Owner: Desktop Codex in `C:\AbandonWare\demo-1\demo-1\src`

## 1. Objective

Consolidate the readable Notebook-produced source-edit directives into one Desktop-owned execution program, introduce a repository-local skill that makes future consolidation repeatable and fail-closed, retire only originals proven to be fully absorbed, and then repair the three authorized legacy chat UI files through independently verified work units.

This design deliberately separates four operations:

1. inventory and requirement reconciliation;
2. canonical directive and skill creation;
3. narrow chat source repair;
4. hash-pinned retirement of superseded directives.

No operation implies permission for the next one. Each mutation is gated by current evidence and its own verification result.

## 2. Current Evidence Snapshot

The following facts were rechecked in the live Desktop checkout before this design was written:

- The zero-byte stale `.git/index.lock` was moved to `.git/codex-stale-locks/index.lock-20260805T220502368Z.stale`.
- `.git/index.lock` is now absent, and read-only Git commands succeed.
- The active branch is `main`; the checkout is heavily dirty and contains many untracked files, so broad cleanup or path-glob deletion is unsafe.
- No top-level pending PatchDrop `.patch` exists and no active source-edit lease was observed.
- The three user-authorized source files are currently untracked and match the supplied SHA-256 values:

| File | Authorized preimage SHA-256 |
| --- | --- |
| `main/resources/static/js/chat.js` | `B9B8B850AB28F6FFB8AC96E6FF40FC1230BD3A4420D4C00A17DF0B78A218BD3B` |
| `scripts/chat_ui_stream_contract_tests.js` | `127F0AB0E0DF7F5AFAAB5EB67ACEE289963E50F488678EA86D812E6F460E5D64` |
| `main/resources/templates/chat-ui.html` | `08C8551504464C50A0CE4ACD56D4CF162ED00BDFA4B4DCC9FEFD3C3B6212D9F7` |

- The current Node fixtures pass, but they do not assert the newly recovered Notebook requirements. They are characterization evidence, not proof that the requirements are implemented.
- Fifteen core lineage artifacts are readable and can be reconciled into the canonical program.
- Thirty-seven June 5 report files remain unreadable because of ACL denial. Scope A does not change their ACLs, infer their contents, or delete them.

The Evidence Snapshot is refreshed immediately before each later mutation. A changed preimage, new index lock, source lease collision, pending PatchDrop patch, or changed active-source boundary yields `HOLD` for that work unit.

## 3. Selected Architecture

The implementation produces two durable artifacts and one ephemeral evidence set:

1. Canonical execution program:
   `agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md`
2. Repository-local consolidation skill:
   `.agents/skills/demo1-consolidating-notebook-directives/`
3. Hash-pinned inventory, mapping, validation, and retirement evidence embedded in the canonical program and generated during implementation.

The canonical program is a single document, but it is not a single giant edit session. It contains independent work units. Every source-changing unit must separately execute:

`RED fixture -> three-way preflight -> target lease -> immediate preimage check -> narrow patch -> GREEN verification -> postimage evidence -> lease release`

For each source-changing unit, the three-way preflight freezes one redacted EvidenceSnapshot and runs exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`. The unit may enter the source-owner guard only after a stable `APPLY`; `HOLD` or `REJECT` authorizes no source mutation.

This preserves rollback boundaries and prevents an unrelated deferred directive from authorizing a chat source edit.

## 4. Directive Inventory Boundary

### 4.1 Core readable lineage to reconcile

The initial reconciliation set contains these fifteen artifacts:

1. `data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json`
2. `agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md`
3. `data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md`
4. `__reports__/notebook-risk-utility-triad-2026-08-04.json`
5. `__reports__/desktop-risk-utility-source-directive-2026-08-04.md`
6. `docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json`
7. `docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md`
8. `docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md`
9. `__patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md`
10. `__patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md`
11. `data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md`
12. `agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md`
13. `agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md`
14. `agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md`
15. `__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md`

Every artifact receives a current SHA-256, accessibility status, provenance classification, requirement list, conflict list, destination work-unit mapping, and retirement eligibility result.

### 4.2 Items explicitly excluded from automatic retirement

The following are preserved unless a later, explicit evidence pass proves otherwise:

- all unreadable or ACL-protected files, including the thirty-seven June 5 report files;
- sealed source-directive canaries;
- tracked reusable prompt sources under `agent-prompts/agents/**`;
- PatchDrop bundle bodies, manifests, reports, verification logs, checksums, and applied/rejected/superseded evidence;
- historical stash blobs and the auxiliary prompt-boundary drafts found only in stash history;
- independent directives not completely mapped in this tranche, including:
  - `security-guardrail-audit-desktop-directive.md`;
  - `http-rollback-local-smoke-desktop-directive.md`;
  - `http-rollback-local-smoke-supabase-boundary-desktop-directive-v2.md`;
  - `supabase-readonly-desktop-codex-directive.md`;
- any file whose current hash differs between inventory and retirement time;
- any file with a requirement that is not represented verbatim or semantically in the canonical requirement ledger.

Excluded items may be recorded as `HOLD`, `REFERENCE_ONLY`, `UNREADABLE`, or `UNABSORBED`. They are not silently treated as complete.

## 5. Canonical Program Contract

The consolidated directive contains four machine-checkable sections.

### 5.1 DirectiveInventory

For every discovered candidate:

- repository-relative path;
- current SHA-256 or `unreadable`;
- provenance: Notebook, Desktop, PatchDrop, canary, reusable prompt, stash-only, or unknown;
- lifecycle state;
- accessibility result and reason code;
- inclusion decision;
- retirement eligibility.

### 5.2 RequirementLedger

Each unique requirement receives a stable ID and records:

- originating artifact and source heading or JSON pointer;
- normalized requirement text;
- affected subsystem and active owner;
- conflict or dependency notes;
- destination work unit;
- disposition: `IMPLEMENT_NOW`, `DEFER`, `HOLD`, `ALREADY_SATISFIED`, or `REJECT_WITH_EVIDENCE`;
- RED assertion and required GREEN proof where implementation is authorized.

No requirement disappears merely because a newer directive overlaps it. Conflicts are preserved and decided explicitly using current source evidence.

### 5.3 CanonicalProgramDirective

The program orders independent work units, names their exact targets, and provides stop rules. Readable directives outside the authorized chat tranche remain deferred or held; their inclusion in the program does not authorize their application source changes.

### 5.4 RetirementManifest

For each original proposed for deletion:

- exact path;
- inventory SHA-256;
- pre-retirement SHA-256;
- mapped requirement IDs;
- proof that all unique requirements were absorbed;
- replacement artifact path and hash;
- eligibility verdict and reason.

Only `ELIGIBLE` entries may be deleted, and deletion uses exact paths rather than globs. A changed hash or missing mapping changes the verdict to `HOLD`.

## 6. Authorized Chat Repair Tranche

### 6.1 Source boundary

The only application-source targets in this tranche are:

- `main/resources/static/js/chat.js`;
- `scripts/chat_ui_stream_contract_tests.js`;
- `main/resources/templates/chat-ui.html`, only if the accessible session chooser needs semantic markup or an explicit label.

No Java change is planned because live source already exposes the session list/detail endpoints and emits `scoreDelta`. No CSS change is planned because the existing `.session-mode-list` surface can be reused. New Java or CSS evidence would require a new design decision and fresh authorization.

### 6.2 Work unit W0: fixture instrumentation and characterization

Purpose: make the new behaviors observable before production code changes.

- Preserve fetch `options.cache` in the fixture capture.
- Add focused assertions that fail against the current implementation.
- Confirm existing fixtures still describe currently supported behavior.
- Do not weaken an existing assertion merely to obtain GREEN.

W0 is test-only and must be RED for the intended missing behavior before W1-W3 begin.

### 6.3 Work unit W1: bounded SSE protocol parser

Required behavior:

- decode the response incrementally as UTF-8;
- accept LF, CRLF, and lone CR line endings;
- accumulate all `data:` fields for one event and dispatch only on a blank line;
- join multiple data fields with a newline without trimming payload-significant whitespace;
- default an unnamed event to `message`, then reset event state after dispatch;
- render exactly once per dispatched event;
- discard an incomplete EOF tail rather than manufacturing a final event;
- render top-level `scoreDelta` through the existing safe rendering path;
- omit unknown-event payload content and expose only an allowlisted categorical event name;
- never place raw payloads or exception text in the failure rail.

New boundedness decision: one assembled SSE event is capped at **131,072 UTF-8 bytes**. This is a new repository design choice, not a pre-existing policy inferred from the directives. The first overflow terminates the stream with a rawless categorical `stream_failed` outcome; subsequent bytes are not rendered.

### 6.4 Work unit W2: typed fail-soft transport handling

Required behavior:

- read at most 1,200 decoded characters from a non-2xx response for classification only;
- map status, allowlisted code, and bounded categorical signals into a fixed typed failure schema;
- distinguish HTTP failure, network failure, SSE terminal failure, and malformed terminal-final JSON;
- omit raw body, query, stack, exception, and provider text from UI and diagnostics;
- retain correlation metadata only through existing allowlisted headers or safe locally generated identifiers;
- make `stream_failed` terminal;
- treat malformed terminal `final` JSON as terminal failure rather than rendering the raw text as an answer;
- preserve fail-soft UI recovery without falsely labeling delivery as semantic success.

### 6.5 Work unit W3: session-list restore and refresh

Required behavior:

- use the exact `GET /api/chat/sessions` list route with `cache: "no-store"`;
- carry existing correlation headers without leaking values to public diagnostics;
- validate session IDs as strict positive numeric identifiers;
- cap accepted candidates at twelve;
- represent loading, empty, unavailable, stale, and ready states categorically;
- keep diagnostic rows (`data-session-mode-row`) separate from selectable rows (`data-session-list-row`);
- expose typed list state through `data-session-list-state`;
- render each selection as an accessible button;
- block restore mutations while an active chat run owns the transcript;
- validate the selected detail response before mutation;
- commit a restored transcript atomically;
- coalesce refreshes and use a generation guard so a stale request cannot overwrite newer state.

## 7. Deferred and Held Program Units

The canonical program also records requirements recovered from the non-chat artifacts, but their default disposition in this execution is `DEFER` or `HOLD`. This includes RAG tail/web counter-evidence, prompt assembly, risk/utility review, Next/BFF porting, evidence gates, historical auxiliary-prompt-boundary tests, security, HTTP rollback, and Supabase read-only work.

They remain visible in the RequirementLedger with their original constraints and proposed verification commands. They do not share a lease with W0-W3 and do not expand the authorized source target set.

## 8. Repository-Local Skill Design

Create:

```text
.agents/skills/demo1-consolidating-notebook-directives/
|-- SKILL.md
|-- agents/openai.yaml
|-- references/consolidation-contract.md
`-- tests/pressure-scenarios.md
```

Frontmatter:

```yaml
---
name: demo1-consolidating-notebook-directives
description: Use when two or more Notebook-produced demo-1 source directives or target-specific RED directives overlap, conflict, appear stale, or must become one Desktop-owned execution program before verified retirement.
---
```

OpenAI interface metadata:

```yaml
interface:
  display_name: "Demo1 Notebook Directive Consolidator"
  short_description: "Consolidate Notebook directives safely"
  default_prompt: "Use $demo1-consolidating-notebook-directives to reconcile Notebook source directives into one Desktop-owned execution program and retire only verified superseded files."
```

The first version adds no new execution script. It reuses repository guards for source preflight, leases, PatchDrop inspection, hashing, and verification. This avoids a second source-owner framework.

Implementation uses both the repository skill-creation guidance and `superpowers:writing-skills`; neither skill may loosen the inventory, retirement, source-boundary, or verification contracts in this design.

Skill development follows a RED/GREEN pressure test:

1. Baseline scenario demonstrates that a naive merge would over-delete a canary, reusable prompt, unreadable report, or PatchDrop evidence.
2. The new skill is written to reject those retirements and require exact hash/mapping proof.
3. The same scenario is rerun and must produce only the eligible exact-path retirement set.
4. Skill metadata and family discovery are validated with the repository validators.

## 9. Retirement Procedure

Retirement occurs only after all of these are true:

1. the canonical program exists and passes structural validation;
2. the consolidation skill exists and passes its pressure scenarios and validators;
3. every proposed original is readable as UTF-8 at retirement time;
4. its current SHA-256 matches the inventory and RetirementManifest;
5. every unique requirement is mapped to the replacement artifact;
6. its provenance and lifecycle show that it is a replaceable Notebook/target-specific directive, not durable evidence or a reusable source;
7. the replacement artifact's postimage hash is recorded;
8. an exact-path dry-run shows no excluded file would be removed.

Eligible originals are then deleted with one explicit file entry per patch. Protected, unreadable, changed, unabsorbed, and evidence-sidecar files remain untouched and are reported by reason code.

The user requested deletion, so eligible originals are removed rather than merely archived. Because the checkout is dirty, no recursive delete, wildcard delete, directory cleanup, Git reset, or checkout restoration is allowed.

## 10. Verification Ladder

### 10.1 Consolidation artifacts

- UTF-8 readability and SHA-256 inventory check;
- RequirementLedger coverage: every included source requirement maps exactly once or records an explicit conflict;
- RetirementManifest coverage and exact-path dry-run;
- no unresolved draft markers;
- count-only secret scan;
- skill `quick_validate.py`;
- repository skill-family discovery/validator checks;
- pressure-scenario RED/GREEN evidence.

### 10.2 Chat work units

Run the narrowest fixture after each work unit, then broaden only after the focused proof is GREEN:

1. `node scripts\chat_ui_stream_contract_tests.js`
2. `node scripts\chat_ui_browser_fault_fixture_tests.js`
3. `node scripts\chat_ui_view_layer_contract_tests.js`
4. focused Gradle `chatUiTest`
5. root `classes`
6. `:app:classes`
7. `bootJar`
8. Browser-visible legacy `/chat-ui` proof for session-list geometry, SSE rendering, failure rail, cancel/reload, and absence of raw failure content.

Fresh output is required for every claimed result. A rendered answer or HTTP success alone proves delivery, not semantic correctness.

### 10.3 Final integrity

- active `.git/index.lock` remains absent;
- no pending PatchDrop patch or lease collision was introduced;
- only declared target files, new consolidation artifacts, and eligible exact-path retirements changed;
- postimage hashes are recorded;
- a final count-only secret scan is clean;
- all held/unreadable artifacts are listed without exposing protected contents.

## 11. Failure and Rollback Rules

- If a source preimage changes, stop that work unit at `HOLD`; do not merge around another owner's edit.
- If a RED assertion unexpectedly passes, inspect whether the requirement is already satisfied before patching.
- If focused verification fails, revert only the current work unit using its captured preimage; do not reset the checkout.
- If broad verification exposes an unrelated dirty-tree failure, preserve the narrow proof and report the external blocker without editing unrelated files.
- If an original directive changes or becomes unreadable, remove it from the retirement set.
- If the canonical artifact or skill validation fails, delete no originals.
- If Browser, provider, or Supabase proof requires authority not present in the checkout, record `evidence_needed` with one exact verification action.

## 12. Non-Goals

- changing ACLs or recovering protected report contents;
- cleaning the dirty worktree or normalizing unrelated untracked files;
- applying every deferred Notebook directive in one run;
- modifying Java, CSS, provider credentials, Supabase data, or external systems;
- installing external launchers or creating a second patch orchestration framework;
- committing, pushing, or deploying without a separate explicit request.

## 13. Success Criteria

The implementation is complete only when:

- one canonical directive contains the reconciled readable lineage, explicit conflicts, deferred units, chat execution units, and a verified retirement manifest;
- the new repository-local skill reliably excludes canaries, reusable prompts, unreadable files, and PatchDrop evidence from retirement;
- W0-W3 pass their focused and broad verification ladder against the live source;
- only exact hash-covered, fully absorbed originals are deleted;
- all ACL-protected and otherwise excluded artifacts remain untouched and are reported;
- final source, artifact, deletion, test, build, browser, lock, lease, and secret evidence is current and internally consistent.
