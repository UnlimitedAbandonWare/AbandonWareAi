# Y-drive canonical output normalization

## Decision

Notebook-facing reports, commands, paths, and handoff fields use `Y:\` as the
only canonical workspace representation. The live SMB mapping remains an
internal identity check and must not be printed as a raw UNC path or used to
rewrite canonical paths.

This is a prompt, governance, and personal-skill change. It does not rename the
Windows share, remap `Y:`, edit application source, change Git trust, or mutate
Supabase.

## Scope

Update only the policy surfaces that directly control Notebook path reporting:

- `C:\Users\nninn\.codex\AGENTS.md`
- `C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md`
- `Y:\AGENTS.md`

Preserve all unrelated existing modifications in `Y:\AGENTS.md`. Do not edit
runtime Java/resources, manifests, historical guard implementations, scripts,
tests, trace directories, or prompt packs during this change.

## Required behavior

1. Report `canonicalWorkspace`, `provenRoot`, `sourceWriteRoot` when authorized,
   command working directories, and repo-relative evidence paths with `Y:\`.
2. Read the live `DisplayRoot` only to verify that the mapped drive still points
   to the expected backing share.
3. Never print or persist the raw `DisplayRoot`/UNC value in user-facing
   summaries, GoalContracts, SourceDirectives, commands, logs, or handoffs.
4. Report backing identity only as:
   - `backingShareIdentityVerified=true|false`, and
   - a redacted reason code such as `match`, `mismatch`, or `evidence-needed`.
5. A mismatch remains fail-closed as `smb-root-identity-changed`; redaction must
   not weaken the existing write gate.
6. Historical mode and skill identifiers remain accepted internally for
   compatibility, but new user-facing output emits the Y-drive mode and does
   not echo the historical names.
7. `SMB_ACCESS` remains read/audit authority only. Canonical `Y:\` is not itself
   source-write authorization.

## Evidence flow

1. Resolve `Y:` mapping into a PowerShell variable without sending the raw value
   to output.
2. Compare it with the policy-owned expected backing identity.
3. Discard the raw value from report construction.
4. Emit `Y:\`, the verification boolean, and a redacted reason code.
5. Continue with the existing authorization, lease, preimage, verification, and
   rollback gates.

Commands used for evidence collection must avoid printing the result of
`Get-SmbMapping` or a Git top-level path that resolves to UNC. They may emit
only comparison booleans, counts, hashes, and redacted reason codes.

## Failure handling

- Missing mapping: `smb-root-unproven`, verdict `HOLD`.
- Mapping mismatch: `smb-root-identity-changed`, verdict `HOLD`.
- No expected identity baseline: `backing-identity-evidence-needed`, verdict
  `HOLD`.
- Existing target-file content changes between preimage capture and patch:
  `changed-preimage`, stop without overwriting.

No failure may expose the raw share path or authorize a fallback source root.

## Verification

After implementation:

1. Validate the personal skill with the repository-approved skill validator.
2. Search the three changed files to confirm that user-facing examples and
   output schemas do not instruct agents to print the raw backing path.
3. Run the existing Y-drive SMB policy autograder or its focused contract test.
4. Execute a redacted mapping probe and confirm it prints `Y:\`, a boolean, and
   a reason code only.
5. Review the diff to confirm that application source, Supabase, Git config,
   Windows share configuration, and unrelated dirty changes are untouched.

## Success criteria

- A new Notebook GoalContract/SourceDirective reports the workspace as `Y:\`.
- No raw UNC backing-share path appears in the resulting user-facing report.
- Identity mismatch detection and `HOLD` behavior remain intact.
- Existing internal compatibility routing remains functional.
- Only the three declared prompt/governance surfaces change.

## Rollback

Restore only the hunks introduced in the three declared files using their
captured preimage text. Do not reset or discard unrelated working-tree changes.

