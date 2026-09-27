# Desktop Opportunistic PatchDrop Pickup Design

**Status:** Superseded before production implementation by the broader existing
`2026-07-31-desktop-patchdrop-autoconsume-design.md`. Retained as decision
history; do not implement this hook-only variant.

## Goal

Make a Notebook PatchDrop handoff visible to the Desktop agent during its next
ordinary `UserPromptSubmit` event, without a separate pickup command and without
letting a Notebook-created marker authorize a Desktop source mutation.

## Decision

Extend the existing `.codex/hooks/source_edit_triage.ps1` command hook with a
bounded, read-only PatchDrop detector. Keep `.codex/hooks.json` unchanged. The
detector injects a short instruction into the Desktop agent's current turn; the
instruction routes the agent through the existing Control Tower audit,
promotion, `desktop-consumer` lease, janitor apply, and verification gates.

The existing Desktop dispatch packet is the preauthorization envelope. A
Notebook manifest or pending marker is never sufficient authority. The full
audit must bind the producer handoff to the Desktop-created dispatch SHA and
`producerCommandHash` before any promotion or application.

“Automatic” therefore means opportunistic processing inside the next normal
Desktop agent command. It does not mean a background watcher, scheduled task,
service, or unconditional `git apply`.

## Frozen EvidenceSnapshot

```text
evidenceSnapshotHash=4A785F7D5CBC90E607931DDF3A780F39E7AFE78AF7A826B867179032F6DB4A51
evidenceRowCount=14
canonicalWorkspace=Y:\
backingShareIdentityVerified=true
backingShareIdentityReason=match
branchEvidence=refs/heads/main
indexLockPresent=false
sourceLeaseBlockingCount=0
topLevelPatchCount=0
classifierSha256=0B9A89CC126571CF48B97B39D2217E8249F6B36CF6DCDC1461CF546BC5DD33ED
hookDefinitionSha256=68490D373586A60C9CA0FF5C31319602C13FE87E5AFC277DD111A4FBB182A357
toolboxSha256=403CA650858DD6EFFDE682E1ACB3BF1833DCD4528A2988EC81B2D2F304D25D0B
```

Git metadata remains read-only and no global trust setting is changed. Direct
writes use the repository-owned shared lease and filesystem compare-and-swap
guard.

## Three-Way Evaluation

### POSITIVE_QUERY

```text
packetType=POSITIVE_QUERY
evidenceSnapshotHash=4A785F7D5CBC90E607931DDF3A780F39E7AFE78AF7A826B867179032F6DB4A51
candidateGoal=extend the existing UserPromptSubmit classifier with read-only Notebook PatchDrop pickup context
scenarioWorlds=S1(valid nested bundle plus matching dispatch); S2(malformed or multiple bundles); S3(already-promoted matching top-level bundle)
validatedAssumptions=the hook already runs on ordinary prompts; existing audit/promotion/lease/janitor assets own mutation
reusableAssets=source_edit_triage.ps1; desktop_dispatch_packet; external_evidence_audit; janitor_promote_producer_pending.ps1; janitor_apply_one.ps1
expectedUserValue=no separate pickup command while preserving Desktop ownership
minimalVerification=fixture-based contract test plus the existing source-edit hook contract suite
evidenceIds=E01-E14
unknowns=Desktop runtime hook trust and final execution remain Desktop evidence
```

### NEGATIVE_QUERY

```text
packetType=NEGATIVE_QUERY
evidenceSnapshotHash=4A785F7D5CBC90E607931DDF3A780F39E7AFE78AF7A826B867179032F6DB4A51
challengedGoal=never treat a Notebook marker as self-authorizing or apply inside the two-second hook
scenarioAttacks=S1(dispatch missing or hash invalid); S2(timestamp selection or malformed manifest accepted); S3(unrelated top-level patch mistaken for the detected topic)
falsifiers=detector mutates files; output selects newest candidate; output authorizes apply without audit; existing source-edit routing regresses
missingEvidence=Desktop hook runtime observation
safetyRisks=authority laundering; queue ambiguity; hook timeout; secret-bearing output
smallestDisconfirmingProbe=run the detector against controlled valid, invalid, multiple, and promoted fixtures
```

### NEUTRAL_QUERY

```text
packetType=NEUTRAL_QUERY
evidenceSnapshotHash=4A785F7D5CBC90E607931DDF3A780F39E7AFE78AF7A826B867179032F6DB4A51
forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]
reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY]
forwardVerdict=APPLY
reverseVerdict=APPLY
forwardDecisiveEvidenceIds=E03,E07,E09,E11
reverseDecisiveEvidenceIds=E03,E07,E09,E11
orderStable=true
verdict=APPLY
selectedOrRewrittenGoal=read-only opportunistic detection with Desktop-dispatch-bound audit and existing mutation gates
scoreInputs=0.94,0.90,0.92,0.90,0.95,0.88,0.85,0.18,0.08,0.08
goalScore=85.0
decisiveEvidence=existing hook plus complete Desktop-owned apply gates; no production watcher exists
rejectedClaims=marker self-authorizes; background watcher is required; hook may apply directly
nextSingleProof=fixture RED/GREEN contract test
confidence=H
nextWorkflow=existing-source-owner-guard
```

## Boundaries

### Detection input

The detector receives an explicit repository root and scans only:

- `__patch_drop__/notebook/*-notebook-v3.manifest.json`
- the exact six nested v3 artifacts for the derived topic
- `__patch_drop__/dispatch/<topic>-desktop-dispatch.json`
- `__patch_drop__/dispatch/<topic>-dispatch.sha256.txt`
- `__patch_drop__/dispatch/<topic>-notebook.commands.txt`
- top-level `__patch_drop__/*.patch` names and counts

It does not read arbitrary files, follow reparse points, validate secrets, or
perform the full SHA audit. Those responsibilities remain in the existing
Desktop audit and janitor tools.

### Candidate rules

A detection candidate must have exactly one manifest whose filename and content
agree on a lower-case topic of at most 64 characters. The manifest must declare
the existing producer-v3 schema, `node=notebook`,
`desktopFinalProof=evidence_needed`, and the existing local-worktree isolation
contract. Every nested artifact and the three dispatch artifacts must exist.

Exactly one valid candidate with no top-level patch emits `detected`. Exactly
one valid candidate with exactly one matching `<topic>-v3.patch` emits
`promoted`. Any malformed, incomplete, multiple, or unrelated queue emits a
bounded `HOLD` context. No candidate emits no pickup context.

### Output

Output remains one compact `UserPromptSubmit` JSON object. Source-edit intent
context and PatchDrop pickup context may coexist, but `additionalContext` stays
within 512 UTF-8 bytes. The pickup context names only a validated topic, counts,
reason codes, required existing gates, and
`desktopFinalProof=evidence_needed`. It never contains patch contents, prompts,
queries, absolute paths, credentials, or environment values.

### Mutation authority

The hook performs no mutation. The receiving Desktop agent must:

1. run the existing external evidence audit for the detected topic;
2. require valid dispatch integrity and producer command-hash binding;
3. require exactly one accepted bundle and an unambiguous top-level queue;
4. use existing promotion when the bundle is still nested;
5. acquire the existing `desktop-consumer` source lease;
6. call the existing janitor apply tool;
7. run focused verification and retain `desktopFinalProof=evidence_needed` until
   Desktop records the result.

Any failed gate is `HOLD`. The hook cannot turn a HOLD into APPLY.

## Files

- Modify `.codex/hooks/source_edit_triage.ps1`: pure detector functions and
  bounded context composition.
- Create `scripts/patchdrop_opportunistic_pickup_contract_tests.ps1`: real
  temporary filesystem fixtures exercising the detector and combined hook
  output.
- Keep `.codex/hooks.json` byte-for-byte unchanged.
- Keep application source, Gradle files, provider configuration, credentials,
  database surfaces, and existing janitor/Control Tower implementation
  unchanged.

## Verification

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\patchdrop_opportunistic_pickup_contract_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
```

The focused test must prove a RED before implementation. The final guard record
binds these commands and target postimage hashes. A live Desktop prompt is still
required for final hook-runtime proof.

## Failure Classification

- `notebook-patchdrop-invalid`: one or more candidate manifests are malformed,
  incomplete, or lack Desktop dispatch evidence.
- `notebook-patchdrop-ambiguous`: more than one valid candidate exists.
- `active-top-level-exists`: the top-level queue is unrelated or contains more
  than one patch.
- `automatic-trigger-missing`: the project hook is absent.
- `hook-trust-missing`: Desktop has not trusted the existing hook definition.
- `source-edit-classifier-timeout`: the bounded hook exceeds its runtime.
- `desktop-final-proof-needed`: Notebook tests pass but Desktop has not observed
  and completed the live command path.

## Rollback

Restore `.codex/hooks/source_edit_triage.ps1` to its recorded preimage and remove
`scripts/patchdrop_opportunistic_pickup_contract_tests.ps1`. Because
`.codex/hooks.json` is unchanged, rollback does not alter the registered command
surface. Existing manual Control Tower and janitor flows remain available.
