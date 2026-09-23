# Browser Source Change Triage Design

## Objective

Create one personal Codex skill at
`C:\Users\nninn\.codex\skills\triage-browser-source-changes` for cases where
browser or tab activity appears successful but a requested source change is not
proven. The skill classifies the evidence, produces exactly positive, negative,
and neutral decision packets, and hands application-source work to Desktop by a
complete SourceDirective.

The skill is diagnostic and documentary. It does not control model routing,
modify the installed Browser plugin, patch application source, or grant future
authority.

## Evidence Decision

- Neutral verdict: `APPLY`
- Goal score: `80.3`
- Order stable: `true`
- Authorized surface: one personal skill and its scripts, tests, references,
  and UI metadata
- Prohibited surface: application source, vendor plugin cache, global approval
  policy, credentials, databases, commits, pushes, deployments, and external
  messages
- Desktop final proof: `evidence_needed`

## Considered Approaches

1. **Create a personal diagnostic skill — selected.** This is automatically
   discoverable, reversible by deleting one directory, and keeps cross-project
   judgment separate from demo-1 application source.
2. **Extend `demo1-demand-driven-external-proof`.** This reuses repo ownership
   but would mix UI evidence collection with source-mutation adjudication and
   remain demo-1-specific.
3. **Modify the bundled Browser plugin.** Rejected because the plugin owns
   browser control, is vendor-managed, and may be replaced by an update.

## Components

### `SKILL.md`

Define the trigger, evidence-freeze workflow, exact three-packet decision,
failure classes, bounded auto-continuation, Desktop handoff, self-improvement
gate, and red flags. Keep the body under 500 words and move schemas to
references.

### `scripts/triage_browser_source_change.py`

Consume a sanitized JSON trace from a file or standard input and emit one
bounded JSON result. Validate allowlisted fields, reject raw sensitive fields,
derive positive and negative packets from the same normalized trace, compare
neutral outcomes in both orders, calculate the fixed goal score, and select
exactly one next action.

The script performs no network, browser, source, approval, or database
mutation. It has no retry loop and finishes in one pass.

### `scripts/test_triage_browser_source_change.py`

Exercise the real command-line tool with literal fixtures. Cover:

- source-edit intent plus browser success but no postimage proof;
- a reported source change that still requires Desktop final proof;
- stale tab or model-switch lag;
- pending approval;
- duplicate actions;
- duplicate JSON keys and unreadable input paths;
- Notebook-owned application traces and unguarded personal-skill claims;
- reversed equal-priority trace sets;
- raw-sensitive-field rejection;
- stable three-packet output and bounded improvement candidate generation.

### `references/trace-contract.md`

Define the input/output schema, allowed enums, score calculation, failure
priority, redaction, timeout, output-size bound, and falsifying test.

### `references/desktop-source-directive.md`

Provide a PowerShell-first Desktop template containing every GoalContract and
SourceDirective field. Require Desktop root, branch, active sourceSet, dirty
overlap, target path, preimage, lease, RED/GREEN, postimage, rollback, and
runtime lineage evidence before an application-source edit.

### `agents/openai.yaml`

Expose the skill with concise UI metadata. `allow_implicit_invocation` remains
enabled; this affects discovery only and never means mutation approval.

## Data Flow

1. Reuse the installed Browser skill for browser control and export only
   sanitized facts: state enums, booleans, counts, hashes, and evidence IDs.
2. Normalize one immutable trace; reject unknown or sensitive fields.
3. Build one PositivePacket and one NegativePacket from that trace.
4. Compare both orders in one NeutralVerdict. Order instability forces `HOLD`.
5. Emit one failure class, one next action, `mutationAllowed=false`, and a
   Desktop SourceDirective when application source is implicated.
6. Auto-continue only a read-only diagnostic. A personal-skill write proposal
   remains `HOLD` until an independent owner guard proves target containment,
   preimage, rollback, and explicit authority.
7. Generate contracts per intent: read-only uses no source target, personal
   skill work uses a Notebook-local owner guard without PatchDrop, and
   application source uses Desktop with unresolved fields left evidence-needed.

## Failure Priority

Evaluate the earliest proven boundary first:

1. `duplicate-action-risk`
2. `stale-tab-binding`
3. `approval-denied`
4. `approval-gate-pending`
5. `model-route-switch-stall`
6. `source-boundary-unproven`
7. `mutation-not-authorized`
8. `source-change-not-proven`
9. `unclassified-evidence-gap`
10. `skill-local-change-requires-guard`
11. `source-change-proven`
12. `read-only-complete`

Sensitive or structurally invalid input exits through the error schema before
classification and is not part of this ordered list.

Unknown model routing remains evidence-needed and is never asserted as root
cause.

## Automatic Continuation

Allow automatic continuation only when all are true:

- the next action is read-only;
- the operation is bounded, deterministic, and reversible;
- no credential, application source, vendor cache, database, deployment,
  external message, or global policy is involved.

An explicitly requested change to this skill still uses a separate owner guard
and RED-GREEN cycle; classifier facts alone never authorize the write.

`heuristicAutoApproval` remains false. `goal_next_auto` may be referenced as a
fail-closed continuation pattern but is not an approval mechanism.

## Improvement Loop

Accept multiple sanitized traces. Emit an `improvementCandidate` only when the
same unclassified signature appears at least twice. The candidate contains a
hash, count, missing fields, and falsifying test; it contains no raw prompt,
query, tab content, path, credential, or response.

An explicit request to improve this skill starts a new RED-GREEN cycle. The
tool never edits itself.

## Verification

1. Run the CLI tests and observe RED before implementation and GREEN after it.
2. Run `node Y:\scripts\chat_ui_browser_fault_fixture_tests.js` to verify the
   existing late-error/cancel fixture still passes.
3. Run `quick_validate.py` on the skill directory.
4. Run a pressure scenario without and with the skill; require the skill-guided
   response to reject blanket approval and emit one bounded next action.
5. Scan the created directory for secrets, placeholders, absolute backing-share
   paths, and prohibited mutation instructions.

## RED Baseline Observation

Without the candidate skill, a fresh agent correctly chose the bounded HOLD
path and rejected blanket approval. Its artifact nevertheless used a custom
`bounded-source-edit-handoff/v1` shape, returned four failure classes instead
of the earliest single class, omitted the required PositivePacket,
NegativePacket, and ordered NeutralVerdict, and did not emit the complete
GoalContract or Desktop SourceDirective fields.

The baseline did not rationalize unsafe mutation. The teaching target is
therefore structural: prescribe the exact output recipe and use a deterministic
validator rather than adding more prohibitions.

## Rollback

Delete only
`C:\Users\nninn\.codex\skills\triage-browser-source-changes`. The skill does
not modify application source or vendor assets, so no broader rollback is
needed. The design and plan Markdown files may be removed independently.
