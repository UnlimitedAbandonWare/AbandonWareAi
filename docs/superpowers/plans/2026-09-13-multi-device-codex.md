# Shared multi-device Codex Implementation Plan

> **For agentic workers:** Execute inline using superpowers:executing-plans; parent owns all writes, integration and verification. Read-only exploration remains delegated under the repository routing contract.

**Goal:** Share definitions through src while preserving personal settings and isolating every host's runtime state.

**Architecture:** One transaction/merge helper serves the existing config generator and producer installer. Host resolution and skill collision inventory are separate bounded components. Shared hooks and config are wired only after fixture tests pass.

**Tech Stack:** Python standard library (Python 3.11+ for TOML), PowerShell, POSIX shell, existing Codex and AWX tooling.

**Spec:** `docs/superpowers/specs/2026-09-13-multi-device-codex-design.md`

## Global Constraints

- Timestamped exact-byte backups; absence receipts for new files.
- Preserve existing key values and personal skill bytes; conflicts never authorize overwrite.
- Keep auth, sessions, caches and per-host config outside shared source.
- Preserve target-scoped leases, preimage checks and existing Notebook/Mac source authority.
- No commit, push, new production dependency, provider substitution or credential mutation.

## Task 1: Merge and reversible transaction

Files: create `scripts/awx_shared_state.py`, `scripts/test_awx_multi_device.py`; modify node setup and its existing tests.

Interface: `merge_config(existing: bytes, incoming: bytes, kind: str) -> bytes`; `Change(path, before, after)`; `apply_changes(changes, state_root) -> dict`.

- [x] Write tests preserving unrelated JSON/TOML keys, array values and backups; fail on conflicting scalar, invalid parse, duplicate key and changed preimage.
- [x] Characterize merge/conflict failures and run focused regression; retain the application-path RED in `java-red.log`.
- [x] Implement semantic merge and durable stage/backups/journal with rollback.
- [x] Integrate setup and rerun its real subprocess preservation case.

## Task 2: Host runtime and skill registry

Files: create `scripts/awx_host_runtime.py`, `scripts/awx_skill_registry.py`; extend focused tests.

Interfaces: `host_facts(root, system=None, environ=None) -> dict`; `local_state_root(root, ...) -> Path`; `scan_skills(shared, personal_roots) -> dict`.

- [x] Test Windows Desktop, Y-drive Notebook and macOS role/path selection using literal fixtures.
- [x] Reject network/shared state and shared CODEX_HOME; generate child-only environment/cache flags.
- [x] Test equal-name/equal-hash and equal-name/different-hash skills with personal bytes preserved.
- [x] Resolve Python/Java/JAR from host facts and explicit environment; validate exact files before launch.

## Task 3: Installer and hook integration

Files: create `scripts/awx_mcp_safe_install.py`; modify toolbox, setup, generated launcher tests, shared config/hooks and hook wrappers.

- [x] Test source manifest tampering, case aliases, traversal, local edits and no partial mutation.
- [x] Replace generated force-copy loops with the shared Python installer.
- [x] Include all shared skill definitions and required references through explicit validated manifest entries.
- [x] Move host-specific project MCP settings to local config only after successful semantic merge/backups.
- [x] Run Windows hook behavior and POSIX command/branch fixtures; preserve the existing source-edit intent gate.

## Task 4: Current checkout proof and delivery

- [x] Run focused unittest and existing affected PowerShell contracts.
- [x] Validate actual TOML/JSON; compare prior personal config keys and unchanged personal skill hashes.
- [x] Inspect task-only diffs against backed-up preimages and run count-only secret screening.
- [x] Write implementation report with actual commands, failures, backups and remaining external-device proof.
- [x] Release only the owned source lease. Keep the full goal incomplete while real required host proof is absent.

## Remaining physical-device acceptance

- [ ] Collect actual Notebook Y-drive setup/status and stdio initialize/tools-list after backing identity verification.
- [ ] Collect actual MacBook setup/status and stdio initialize/tools-list in its local clone or explicit shared-read mount.

Local implementation and 277 automated checks passed. The owned lease was released. Goal remains active because current connected-host inventory exposes only Desktop. See `docs/reports/2026-09-13-multi-device-codex-implementation.md`.
