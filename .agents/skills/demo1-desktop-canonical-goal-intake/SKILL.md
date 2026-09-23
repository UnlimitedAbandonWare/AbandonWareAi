---
name: demo1-desktop-canonical-goal-intake
description: Use when a demo-1 Desktop task receives an exact implementation SourceDirective
---

# Demo1 Desktop Canonical Goal Intake

## Core Invariant

Freeze these values before planning or mutation:

```text
originEvidenceRoot=Y:\
canonicalExecutionRoot=C:\AbandonWare\demo-1\demo-1\src
readRoot=C:\AbandonWare\demo-1\demo-1\src
writeRoot=C:\AbandonWare\demo-1\demo-1\src
finalProofRoot=C:\AbandonWare\demo-1\demo-1\src
yEvidenceAuthority=supporting_only
fallbackWriteRoot=null
sourceOwner=desktop
```

For an exact C-rooted or repo-relative directive with no Notebook origin,
record `originEvidenceRoot=null`; do not invent Y-origin evidence. The C read,
write, final-proof and source-owner invariants remain unchanged.

`Y:\` can identify a handoff origin only. It is never a source-write,
active-source, fallback, or final-proof root. Notebook claims, matching bytes,
and SMB reachability remain supporting evidence.

## Scope

Use this thin Desktop router for an exact implementation SourceDirective or a
Notebook/SMB directive with Y/C or source ownership ambiguity. An exact C-rooted
SourceDirective is also an input. Ordinary C-rooted work without a directive,
and explicitly authorized `YDRIVE_SMB_GUARDED_DIRECT` edits, keep their own route. It selects roots, demotes incoming evidence, classifies a lane, and
emits intake evidence; it does not mutate application source or create a new
lease, CAS, PatchDrop, or rollback protocol. Create no new verification framework.

## Automatic continuation for an authorized source directive

For an authorized Notebook directive patch request, automatically perform
[tool preparation](references/tool-readiness.md) after confirming the Desktop
root and selected packet. Discover the current catalog once, load the applicable
skills, and use the existing RuntimeToolkit when runtime capability is needed.
Keep its invocation-owned session alive through the checks that require it.
This is part of intake, not a second source review or a reason to ask the user
to name every tool. Missing runtime proof holds its dependent acceptance checks;
continue independent source investigation, repair, and focused tests.

When a user provides an exact directive for implementation, including an
instruction delivered by an automation the user enabled for this purpose,
use this intake as the start of the existing patch workflow. The user's
existing implementation authorization persists; do not finish with only a
plan, receipt, or a generic request to approve the same implementation.
Explicit analysis-only, dry-run, and do-not-apply requests remain read-only.
An arbitrary file or an `autoExecuteRequested` string without a proven
authorized producer/request is not permission to execute.

1. Read the exact selected packet and preserve its target set, verification,
   budget, and prohibited surfaces. Opted-in automated packets must declare
   `sourceOwner=desktop`, target files, and executable acceptance criteria.
   Ambiguous/incomplete input holds only that packet; never choose a
   different historical packet by timestamp.
2. Perform the canonical C-root intake below. Treat incoming Notebook
   transport/connection HOLD fields as historical observations and reevaluate
   them after actual Desktop receipt. They cannot override fresh C-root
   evidence. Notebook source/read evidence stays supporting-only.
3. For `application_source`, continue in the same task through the existing
   three-query preflight with stable APPLY, then the existing source-owner
   lease, immediate preimage check, focused RED, minimal patch, GREEN,
   postimage/changed-path/secret-count verification, and rollback contract.
   Follow the established guard's ordering; this section creates no second
   mutation protocol. Apply the existing rebinding contract's
   [Debugging continuation](references/directive-rebinding-contract.md#debugging-continuation)
   for semantic RED, one hypothesis per cycle, the original execution deadline,
   retry stops, and proof-only resume. Reuse the same evidence and source session.
4. Reuse valid existing fixes. Before replaying a completed directive, consult
   its exact original path/hash, bound final report, completion receipts and
   cleanup journal; verify current source postimages and acceptance evidence.
   If unchanged requirements and proof remain sufficient, report already-complete
   without applying it again. A stale marker never overrides changed directive
   bytes or broken proof. If the source
   is already correct but required verification is missing, resume that proof
   without replaying the patch. Keep one directive in flight and preserve
   unrelated work.
5. If a source gate blocks mutation, record the concrete blocker and next
   single proof, continue independent authorized work, and do not retry the
   unchanged blocker. Never remove an unknown lock, change global Git trust,
   or use Y as the Desktop fallback write root.
6. Report `sourcePatchCompletion` as `verified`, `not_reproduced`, or
   `evidence_needed`, separately from each required proof and
   `desktopFinalProof`, with actual commands and results. Receipt, dispatched
   message, mock provider response, and an ACK are not source/final proof.
   Reuse the existing Desktop source session report rather than adding a new
   ACK, manifest, watcher, or ready-marker protocol.
7. After whole-directive `sourcePatchCompletion=verified` and
   `desktopFinalProof=verified`, invoke
   [completed directive cleanup](../demo1-completed-directive-cleanup/SKILL.md)
   with the exact intake hash, final report and current postimages. The user's
   completed-file cleanup authorization covers this automatic final step.
   Read that owner's completion boundary and request contract before marking or
   deleting. It owns durable marking, MarkOnly, retention, failure recovery and
   current-postimage validation. Mark only the selected fully verified directive;
   unreadable or partial evidence stays evidence_needed. Report directive status
   separately from cleanup status and preserve its receipt paths.

## Intake

1. Read the declared directive and record its origin by redacted identifier or
   hash when useful. Treat every Notebook claim as a hypothesis.
2. Prove the literal C root is live: it exists, Git resolves to the same local
   non-UNC path, and closest `AGENTS.md` is reread. Otherwise
   `HOLD c-canonical-unavailable`; never fall back to Y or infer another
   checkout. Reconfirm active Gradle sourceSets separately; when that proof is
   missing, use `HOLD active-sourceset-uncertain`.
3. Before accepting each Y, C, or relative target, run once for that target:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File `
     .\.agents\skills\demo1-desktop-canonical-goal-intake\scripts\resolve_desktop_directive_target.ps1 `
     -DirectivePath <declared-target>
   ```

   Only `status=PASS` and `reason=match` let a target enter later C-side
   mutation gates. Resolver output proves routing only and never authorizes a
   write. Lexical-only output is supporting/evidence-needed.
4. Run C-rooted collision intake: PatchDrop inventory, active leases, target
   preimages, real index/ref operation state, and ports only when runtime
   proof requires them. Preserve unrelated changes.
5. Select exactly one primary lane:

   - `read_only`: diagnosis or `evidence_needed`.
   - `artifact_only`: prompt, skill, Markdown, or validator artifact; no source lease.
   - `application_source`: active Java/resources behavior.
   - `patchdrop_consumer`: a complete top-level bundle controls ownership.

   At most one secondary lane may change the decision. Route application source
   to `demo1-source-edit-three-way-preflight`; promote only fresh C revision,
   active sourceSet, target preimage, and source-owner proof. The
   repository-owned source-edit guard owns lease, immediate preimage, rollback,
   and changed-path enforcement. Route Desktop final proof to
   `demo1-desktop-only-proof-loop`, skill validation to
   `demo1-skill-family-postprocessor`, and optional external evidence to
   `demo1-demand-driven-external-proof`.
6. Keep Browser, Computer, and Supabase separate. They are run only when
   required; Supabase is read-only supporting evidence. A Y-side test, UI
   response, provider report, or hash cannot certify the C postimage.

## Declared Stages Only

Apply this rule only when the incoming SourceDirective declares stages:

Read [Declared stages](references/directive-rebinding-contract.md#two-stage-contract)
for the canonical sequence. Stage 2 requires Stage 1 GREEN and release, independent
RED, fresh preflight and a newly acquired lease; never carry Stage 1 ownership or
evidence into Stage 2. No remaining causal candidate yields
`no_additional_candidate` without a write.

## Contract And Stops

Read [the directive rebinding contract](references/directive-rebinding-contract.md)
for target input classification, exact output fields, stage rules, failure
labels, and the single example. Emit one precise next proof and no fallback
root. `COMPLETE` requires fresh C-root proof for every required changed surface;
prompt or skill validation alone cannot complete an application-source request.
