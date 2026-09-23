---
name: demo1-notebook-targeted-directive-canary
description: Use when a Notebook must prepare a no-source-change Desktop SourceDirective canary
---

# Notebook Targeted Directive Canary

Prepare or validate one sealed SourceDirective Canary with `scripts/awx_notebook_source_directive_canary.py`. This is Windows-only, no-source-change work - not a general SMB skill or mutation protocol. Reuse `$notebook-goal-directive-generator` for GoalContract/three-role authority decisions and `$notebook-smb-network-workspace` plus `scripts/verify_ydrive_backing_identity.ps1` for public identity evidence. Never create Desktop ACK or proof.

## Use / do not use

Use only for prepare/validate of one Canary from explicit files on `Y:\`.

Do not use for application-source edits, build/runtime proof, PatchDrop, Git trust changes, provider/DB work, ACK creation, non-Windows publication, directory discovery, globs, or recursion. Route explicitly authorized source edits to the existing repository guard.

## Preconditions

- Require Windows, `canonicalWorkspace=Y:\`, and identity output `backingShareIdentityVerified=true`, `backingShareIdentityReason=match`.
- Accept an explicit relative list only under `main/java`, `main/resources`, `app/src/main/java_clean`, or `app/src/main/resources`; allow at most 16 files, an 8 MiB source aggregate, and one 30-second public-call deadline.
- Publish exactly one named packet at `data/agent-handoff/notebook/<packet-name>`: the packet must be one direct child, with no intermediate/session directory. Its fixed five-file aggregate is also limited to 8 MiB. Never publish under an active source root. Keep `targetFiles` empty and `authorizedMutation` false.

## Workflow

1. Run this process-only identity gate before prepare; it does not persist policy and exposes no raw backing path:

   ```powershell
   $identity = powershell.exe -NoProfile -ExecutionPolicy Bypass -File Y:\scripts\verify_ydrive_backing_identity.ps1 -ExpectedSha256 '30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9' | ConvertFrom-Json
   if ($identity.canonicalWorkspace -ne 'Y:\' -or $identity.backingShareIdentityVerified -ne $true -or $identity.backingShareIdentityReason -ne 'match') { throw 'smb-root-identity-changed' }
   ```

   Do not accept or use manually supplied identity values without this check.
2. Reject directories, globs, and inferred paths.
3. Prepare with a repeated `--inspect-file` for every explicit file:

   ```powershell
   python Y:\scripts\awx_notebook_source_directive_canary.py prepare --root Y:\ --output-dir Y:\data\agent-handoff\notebook\source-directive-canary-v1 --directive-id notebook-desktop-canary-20260802-v1 --branch main --identity-verified true --identity-reason match --inspect-file main/java/com/example/lms/LmsApplication.java
   ```

4. Validate the sealed packet:

   ```powershell
   python Y:\scripts\awx_notebook_source_directive_canary.py validate --root Y:\ --packet-dir Y:\data\agent-handoff\notebook\source-directive-canary-v1
   ```

5. Hand it to Desktop with `desktopFinalProof=evidence_needed` and `runtimeLineageVerdict=HOLD`. ACK is external; this tool exposes no ACK command.

## Packet mechanics

- Fixed file set/order: `source-directive.json`, `source-directive.sha256.txt`, `desktop-ack.template.json`, `manifest.json`, `ready`.
- Prepare and validate accept only a packet directory that is exactly one direct child of `data/agent-handoff/notebook`; validation bounds the five-file aggregate and checks the shared deadline around every read chunk.
- `bundleId` is the supplied directive ID, not timestamp, directory, or hash inference.
- Directive bytes feed the sidecar and ACK-template `sourceDirectiveSha256`.
- The manifest hashes directive, sidecar, and template.
- Ready hashes the manifest and is created in staging before pinned-handle no-replace rename.
- Use explicit pinned handles for all source/packet reads; fsync each file. Do not claim remote-SMB power-loss directory durability.

## Fail closed / rollback

Classify: `broad-scan-forbidden`, `target-not-explicit`, `wrong-sourceset`, `reparse-traversal-risk`, `smb-root-identity-changed`, `input-budget-exceeded`, `undeclared-source-write`, `packet-hash-mismatch`, `changed-preimage`, `secret-leak-risk`, `bundle-publication-nonatomic`, `desktop-proof-missing`.

Rollback removes only the verified, named no-change Canary output. No source rollback exists: source writes are forbidden.

| Incorrect rationale | Correction |
| --- | --- |
| The final ACK does not change the sealed packet. | An unsealed template or shared ACK command breaks the contract; template is manifest-hashed and ACK is external. |
| Consumers may act only when a sibling marker exists. | A second marker creates a crash window; ready lives in staging before atomic publication. |
| Notebook-generated ACK is invalid. | Policy text is insufficient if the tool exposes ACK; this tool has no ACK command. |
| "Under the handoff root" allows session-folder nesting. | The packet shape is `data/agent-handoff/notebook/<packet-name>` with exactly one child component. |

Generic deadline, network, and Git-pressure scenarios already passed and are not duplicated.

## Falsifying test

Fresh agents must produce the fixed five-file schema/order with no shared ACK command. If they invent PatchDrop files, a sibling ready marker, source `targetFiles`, or a shared ACK writer, revise this skill.
