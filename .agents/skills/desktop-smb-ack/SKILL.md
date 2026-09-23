---
name: desktop-smb-ack
description: "Use when Desktop Codex needs to receive or acknowledge a Notebook SMB handoff packet"
---

# Desktop SMB ACK

## Scope

Use this skill only to confirm receipt and bounded processing of a Notebook handoff packet under `data/agent-handoff/notebook/`. An ACK does not authorize arbitrary source execution or widen the user's requested scope.

## Guarded routing

For a source directive or source packet, route through `demo1-desktop-canonical-goal-intake`; before any authorized application-source mutation, also run `demo1-source-edit-three-way-preflight`.

For a PatchDrop packet, route through `patchdrop-safe-patch-orchestrator`.

For a Canary packet, route through `demo1-notebook-targeted-directive-canary`.

Source, PatchDrop, and Canary packets use a guarded workflow instead of a completion ACK. The guarded workflow owns source preimage checks, patch application, verification, runtime proof, and retirement decisions.

## Procedure

1. Resolve exactly one requested packet beneath `data/agent-handoff/notebook/`. If none or more than one is eligible, return `status: disconnected` and stop.
2. Read the packet and classify it. Keep receipt and bounded processing separate from any downstream source work.
3. Route guarded packet types as specified above. Do not use this ACK skill to apply a source patch.
4. If packet reading, authorization, routing, or ACK writing fails, return `status: failed` and stop.
5. After successful handoff receipt and bounded processing, write exactly one compact JSON line to `data/agent-handoff/codex/report/<packet-base-name>.ack.json`:

```json
{"ackStatus":"done","completionScope":"handoff-only","sourcePatchCompletion":"evidence_needed"}
```

6. Read the ACK file back and require all three fields and exact values before returning `ACK Done`.

## Completion boundary

`ACK Done` means only that the handoff was received and its bounded processing result was recorded. ACK Done never proves source applied, source verified, runtime completion, commit, or deploy completion.

Downstream source completion remains `evidence_needed` until its guarded workflow records the required Desktop preimage, RED/GREEN verification, postimage, and final proof.

## Result states

| Condition | Result |
| --- | --- |
| Packet missing or ambiguous | `status: disconnected` |
| Read, authorization, routing, or write failure | `status: failed` |
| Exact handoff-only ACK read back | `ACK Done` |
