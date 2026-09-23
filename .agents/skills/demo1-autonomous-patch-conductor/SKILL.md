---
name: demo1-autonomous-patch-conductor
description: Use when Codex or an Antigravity agent must run a multi-hour autonomous safe-patch
---

# demo1 Autonomous Patch Conductor

Use this for a bounded long-running Safe Patch session in the demo-1 Desktop
canonical root. Take one proven backlog or source-health seam at a time, patch
the smallest active file set, verify it, record it, and stop when proof changes.

## Safety Authority

- Work from `C:\AbandonWare\demo-1\demo-1\src` unless another demo-1 root is
  verified.
- Current repository files and command output are authoritative.
- Patch only active roots unless Gradle proves another root is active.
- Keep LangChain4j `1.0.1` and the `PromptBuilder.build(PromptContext)`
  boundary.
- Use FQCN evidence for duplicate or alias decisions.
- Never print or persist raw secrets, auth headers, cookies, raw env dumps, raw
  prompts, or raw external payloads.
- Include `sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}` and
  `sbp_[A-Za-z0-9_-]{10,}` in count-only secret scans; never print matched text.
- Preserve PatchDrop janitor safety; producer evidence is not a default blocker
  for Desktop-only source work.

## Reference Routing

Read only the sections needed for the current cycle:

| Need | Reference section |
| --- | --- |
| Start a long run | `Session Start Checklist` |
| Execute patch cycles | `Patch Cycle Loop` |
| Choose verification | `Verification Commands` |
| Write the final report | `Session Report Contract` |
| Maintain backlog state | `Patch Backlog Management` |
| Avoid unsafe shortcuts | `Prohibitions` |

Reference file:
`references/autonomous-patch-conductor-reference.md`

## Operating Loop

1. Confirm root, active sourceSets, declared-target preimage state, source-edit
   leases, any real index/ref operation in progress, and PatchDrop state.
2. Do source-only reconnaissance before deleting or disabling behavior.
3. Pick the first proven OPEN backlog item or smallest current failure seam.
4. Patch one intent per cycle, usually at most three files.
5. Verify with the narrowest disproof command.
6. Mark `DONE` only after proof; otherwise mark `SKIP` with `failReason` or
   stop with `evidence_needed`.
7. Stop on changed blocker class, external proof need, hard gate failure, or
   time exhaustion.

## External Evidence Lanes

- Browser and Computer proof are optional unless UI or Windows UI changed.
- Supabase is read-only `evidence_needed` unless current project ref/auth exist.
- Mac mini and Notebook proof is supporting unless explicit handoff is required.
- Superpowers is process support; repo evidence and verification outrank it.

## Skill-Family Verification

For skill-only postprocessing, run `quick_validate.py` on this skill and the
family validator from `demo1-skill-family-postprocessor`. Use `Verification
Commands` in the reference file for runtime Safe Patch Gradle gates.
