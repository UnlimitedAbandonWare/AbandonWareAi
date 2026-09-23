# Y-Drive SMB Canonical Workspace Design

**Date:** 2026-07-31  
**Status:** Implemented; final handoff blocked on current successor ownership and guard-exit evidence  
**Scope:** Notebook global agent policy, repository agent policy, and the two SMB routing skills that interpret those policies

## Problem

Notebook agents currently describe the active source workspace as the `MacSrc` share and treat `Y:\` as an acceptable alias. That reverses the user's operating model. The user works in `Y:\`; the resolved UNC share name is transport and identity evidence, not the user-facing workspace or a destination that agents should prefer.

Live evidence on 2026-07-31:

- `Get-SmbMapping -LocalPath Y:` reports `Status=OK`.
- `Get-PSDrive Y` reports `Root=Y:\` and `DisplayRoot=\\DESKTOP-M5NOV6K\MacSrc`.
- `Y:\.git\index.lock` is absent.

The design must preserve the security value of checking `DisplayRoot` without causing agents to redirect work away from `Y:\`.

## Chosen Model

`Y:\` is the canonical Notebook SMB workspace and the canonical path agents use for repository reads, approved writes, verification, and trace locations.

The resolved UNC value is a backing-share identity observation. It is used only to detect an unexpected remap. It is not:

- the canonical workspace name;
- a preferred source path;
- a reason to rewrite `Y:\` paths to UNC paths;
- a reason to copy the repository to OneDrive or a local clone;
- independent evidence of mutation authority.

Mutation authority remains conditional on user intent, the active source boundary, index-lock and overlap checks, and the repository-owned lease/compare-and-swap guard.

## Terminology and Compatibility

The policy-facing mode becomes `YDRIVE_SMB_GUARDED_DIRECT`.

`MACSRC_SMB_DIRECT` remains an input-only compatibility alias while existing guard scripts, traces, tests, or stored packets still use it. New policy output and new instructions must emit `YDRIVE_SMB_GUARDED_DIRECT`.

The existing skill and script filenames are not renamed in this change. Renaming them would expand the change into a migration across manifests, tests, traces, and prompt packs without improving path safety. Their introductory policy text will instead state that they guard direct work in the canonical `Y:\` workspace and that the historical `macsrc` token is a compatibility identifier.

## Policy Changes

### Personal global policy

`C:\Users\nninn\.codex\AGENTS.md` will declare:

- `notebookCanonicalWorkspace=Y:\`;
- mapped-drive `DisplayRoot` is checked as backing-share identity only;
- demo-1 guarded direct edits are routed through the repository guard without asking the agent to switch away from `Y:\`;
- `YDRIVE_SMB_GUARDED_DIRECT` is the canonical emitted mode;
- `MACSRC_SMB_DIRECT` is accepted only for compatibility.

### Repository policy

`Y:\AGENTS.md` will replace the current alias-first wording with Y-drive-first rules:

- explicit approved Notebook source work in this checkout targets the proven `Y:\` root;
- a live `DisplayRoot` mismatch is a stop condition because the mapping identity changed;
- evidence and session traces remain relative to `Y:\`;
- agents do not silently substitute UNC, OneDrive, Desktop-canonical, PatchDrop, or local-clone paths unless another explicit execution mode requires it;
- legacy names remain documented only where compatibility is relevant.

### Routing skills

The generic `notebook-smb-network-workspace` skill will route this repository by canonical workspace plus repository-owned guard, rather than calling the workspace "MacSrc."

The existing direct-patch skill will preserve its filename and mechanical guard contract but describe `Y:\` as the workspace. Its normalized path containment checks will be defined against the proven `Y:\` root; the UNC value is checked separately as the expected mapping identity.

## Autograder Contract

A focused policy test will fail if any active policy reintroduces the reversed relationship. It will assert:

1. personal and repository policies declare `Y:\` as the canonical Notebook workspace;
2. new policy output uses `YDRIVE_SMB_GUARDED_DIRECT`;
3. the old mode is explicitly compatibility-only;
4. `DisplayRoot` is retained as an identity check;
5. active policy does not say that `Y:\` is merely an alias for the UNC share;
6. the routing skills do not instruct the agent to replace `Y:\` with the UNC path;
7. no application-source, SMB mapping, credential, or global Git trust mutation is included.

The test is static and deterministic. Live `Get-SmbMapping` output remains runtime evidence and is not baked into a pass result beyond the expected relationship schema.

## Verification

Verification proceeds from narrowest to broadest:

1. run the new focused policy test and observe RED before policy edits;
2. edit the four declared policy/skill files only;
3. rerun the focused test and observe GREEN;
4. run the relevant skill validator for each changed skill;
5. run prompt/policy lint tests that cover `AGENTS.md` routing if present;
6. rescan active files for contradictory `Y:\ is an alias` or MacSrc-first instructions;
7. record post-edit SHA-256 hashes and count-only secret-scan results.

Notebook verification is sufficient for this prompt/skill-only change. No application runtime lineage claim is made.

## Mutation Surface

Authorized:

- `C:\Users\nninn\.codex\AGENTS.md`
- `Y:\AGENTS.md`
- `C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md`
- `Y:\.agents\skills\demo1-macsrc-smb-direct-patch\SKILL.md`
- one focused policy test and this design/implementation documentation

Prohibited:

- application source under active Java/resource source sets;
- SMB share or drive mapping configuration;
- Git `safe.directory`, branch, commit, push, or deployment state;
- credentials, provider settings, database state, or PatchDrop queue state;
- broad renaming of historical scripts, trace directories, manifests, or skill identifiers.

## Failure and Rollback

Fail closed if `Y:` is missing, its `DisplayRoot` changes unexpectedly, a target policy file is replaced during the edit, or focused verification fails.

Before editing each personal/global or skill file, save a timestamped backup outside application source and record its hash. Repository files are additionally recoverable from the exact preimage captured for the guarded edit. Rollback restores only the files changed by this task and reruns the focused policy test.

## Success Criteria

- Agents consistently treat `Y:\` as the Notebook's canonical SMB workspace.
- The backing UNC name remains available only for remap detection and audit evidence.
- Existing guard tooling remains callable without a broad identifier migration.
- The autograder rejects any future prompt that demotes `Y:\` to a mere alias.
- No application source or external system state changes.
