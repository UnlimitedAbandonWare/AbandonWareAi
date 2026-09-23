# Codex Global Instructions Scope Design

## Objective

Replace the demo-1-specific nine-hour `/goal` currently stored in the Windows
Codex global `AGENTS.md` with a compact set of durable personal defaults. Keep
the complete long-running workflow in the demo-1 repository, add an explicit
project pointer, and preserve independent SUPPORT/FALSIFY/NEUTRAL review.

## Current evidence

- Effective Codex home: `C:\Users\nninn\.codex` (`CODEX_HOME` is unset).
- Global instructions: `C:\Users\nninn\.codex\AGENTS.md`, 26,935 bytes.
- Global override: absent.
- Project instructions: `C:\AbandonWare\demo-1\demo-1\src\AGENTS.md`,
  26,886 bytes.
- Default `project_doc_max_bytes`: 32 KiB because `config.toml` does not
  override it.
- Approximate project capacity after the current global file: 5,831 bytes,
  or 21.7 percent of the project file through about line 34.
- Canonical task prompt:
  `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md`.

The current global file therefore applies a repository-specific task to every
workspace and can prevent later project safety and verification rules from
entering the combined instruction chain.

## Approved design: A

### Global layer

Keep `C:\Users\nninn\.codex\AGENTS.md` at or below 3,072 bytes. It contains
only durable Windows 10 and collaboration defaults:

- PowerShell-first local inspection and evidence-backed reporting;
- preserve unrelated worktree changes and use the smallest verified edit;
- never reveal secrets or raw sensitive prompts/responses;
- keep external Browser, Computer, Supabase, and multi-agent lanes
  demand-driven;
- treat long time budgets as maxima with early stop conditions;
- defer repository-specific commands and invariants to the closest project
  `AGENTS.md` and task prompt.

It must not contain `/goal`, demo-1 absolute paths, source snapshots, fixed
Gradle commands, or a mandatory report template.

### Project layer

Add one short pointer to the existing project `AGENTS.md`; do not duplicate the
long prompt. Preserve all existing uncommitted user changes around the insertion.

### Task prompt and review packets

Keep the long task in the canonical prompt. Add a scoped review contract that
creates four independent packets only for decision-sensitive postprocessing:

1. `SUPPORT_CONTRACT` checks schema, invariants, counts, and hashes.
2. `SUPPORT_SCENARIO` checks adverse Korean and English scenarios.
3. `FALSIFY` searches for false greens and mixed request correlation.
4. `NEUTRAL` receives the three packets and current evidence, then returns
   `APPLY`, `HOLD`, or `REJECT` without majority voting.

The packets contain counts, hashes, reason codes, and command evidence only.
They must not contain raw prompts, provider responses, credentials, or full
error bodies.

## Verification contract

- Global `AGENTS.md` is at most 3,072 bytes.
- Global file has zero `/goal`, demo-1 path, nine-hour workflow, and mandatory
  report-template markers.
- Global plus project `AGENTS.md` stays below 32,768 bytes.
- The project pointer resolves to the canonical prompt.
- The canonical prompt contains all four review roles and the non-voting
  `APPLY | HOLD | REJECT` contract.
- Existing prompt audit tests continue to pass.
- Changed instruction files contain zero recognized secret patterns.
- A fresh Codex task or app restart is required to reload the instruction chain.

## Rollback

The removed global body remains recoverable from the canonical project prompt
and its Git history. Reverting the focused project commit restores project
pointer and task-prompt changes; the prior global file can be reconstructed
from the canonical prompt if the compact personal defaults are rejected.
