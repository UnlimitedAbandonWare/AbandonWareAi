---
name: demo1-repairing-from-live-evidence
description: Use when an authorized local demo-1 source repair is blocked solely by a missing
---

# Repairing Demo1 From Live Evidence

- Continue only when the live checkout proves the active target and missing history is optional.
- Treat missing named attachments, PatchDrop/SMB intake, AutoLearn artifacts, required runtime, or acceptance evidence as `HOLD`/`evidence_needed`; never reconstruct them.
- Do not use this skill for read-only review, external-system changes, required-evidence gaps, or PatchDrop/SMB intake.
- Before source mutation, use `demo1-source-edit-three-way-preflight`; require stable `APPLY` plus existing owner, lease, preimage, and PatchDrop gates.
- Let more specific repo skills govern; route PatchDrop work to `patchdrop-safe-patch-orchestrator` and SMB work to `demo1-macsrc-smb-direct-patch`.
