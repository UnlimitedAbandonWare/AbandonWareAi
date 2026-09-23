---
name: demo1-patchdrop-manual-default
description: Use when demo-1 Safe Patch work involves PatchDrop, SMB, producer bundles
---

# Demo1 PatchDrop Manual Default

## Core Rule

Keep PatchDrop safety tooling available for explicit handoffs. Do not make an empty PatchDrop queue, missing producer sidecars, or absent Mac mini/Notebook proof a default Desktop-only failure.

## Reference Routing

Load details only for the current need:

| Need | Reference section |
| --- | --- |
| Run queue probes | `Queue Probe Commands` |
| Classify queue state | `Queue Classifier` |
| Consume an explicit handoff | `Manual Handoff Gates` |
| Prove PatchDrop safety | `Verification Commands` |
| Triage test dispatch leftovers | `Fixture Artifact Hygiene` |

Reference file:
`references/patchdrop-manual-default-reference.md`

## Default Classifier

Use current janitor/source-scan evidence, then decide:

- empty top-level queue plus no intersecting or unknown target reservation: continue Desktop-only preflight;
- producer files without a top-level patch: keep as supporting evidence;
- one complete top-level v3 bundle: switch to explicit PatchDrop consumer gates;
- ambiguous or incomplete top-level sidecars: stop with `patch-drop-pending`.

## Default-Loop Guard

When patching source locally, preserve janitor scripts and external audit schemas. Patch only overrequirement such as:

- nested or historical PatchDrop files counted as active queue;
- producer proof treated as a hard blocker for Desktop-only work;
- unrelated source leases treated as a Desktop-wide stop; overlapping or unknown scopes retain their edit gate;
- automatic dispatch or producer kit writes without explicit request.

Preferred labels:

- `patchdropQueue=empty_ok`
- `patchdropMode=manual`
- `producerEvidence=supporting`
- `nextAction=none_for_desktop_only`

## Verification

Use `Verification Commands` in the reference file. Do not delete janitor
scripts to reduce noise. Demote default requirements; preserve explicit
handoff safety.
