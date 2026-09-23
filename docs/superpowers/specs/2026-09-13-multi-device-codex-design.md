# Shared multi-device Codex design

Approved by the user with “Yes to the design” and four binding guardrails on 2026-09-13. The approval includes immediate implementation; no additional design approval is required.

Shared definitions remain in the current src checkout. Host-specific executable paths, credentials, runtime state, backups and cache directories stay in host-local storage. Existing Notebook Y-drive identity/target lease rules and Mac independent-worktree/PatchDrop rules remain authoritative.

## Preservation contract

Before changing an existing target, retain its exact bytes in a host-local `.bak_<UTC timestamp>` backup. For a new target, record original absence before creation. Parse JSON and TOML into key/value trees and merge missing keys without replacing existing values. A conflicting value prevents the complete installation stage. Python/code/skill documents are indivisible semantic assets: differing local modifications cause a conflict rather than a speculative AST rewrite. Unmodified previously installed files can be updated using their recorded baseline. Personal skills are never rewritten by the registry.

## Components

- `awx_shared_state.py`: staged writes, exclusive cooperative locks, preimage comparisons, local backups, journal and rollback; JSON/TOML semantic merging.
- `awx_host_runtime.py`: OS/role detection, local paths and executable resolution; runtime launcher with environment scoped to its child process; shared hook entry point.
- `awx_skill_registry.py`: shared/personal front-matter name and content-hash inventory, case/Unicode normalized collisions and explicit personal priority in the registry; no implicit assertion that Codex itself changes precedence.
- `awx_mcp_safe_install.py`: validate complete kit, plan all files and local configuration before mutation, reuse the transaction helper and emit a receipt.
- Existing node setup and installer renderers call these components. Both OS launchers share one Python implementation of installation safety.

## Isolation and activation

Default local root: LocalAppData/AWX on Windows, Library/Application Support/AWX on macOS, XDG state location on other POSIX. Derive a stable host identifier and separate workspace identity. Reject local state under a source root, mapped/network storage or symlink/reparse traversal. Never put auth values in generated shared definitions. Keep the user's CODEX_HOME local; do not move or copy existing sessions or authentication.

Shared config contains portable behavior only. Migrate the existing host-specific MCP table into the local user configuration using semantic merge and exact backups before removing its project override. Missing Java/JAR is a named readiness failure; never start another model or use a stale guessed JAR. The launcher may use an explicitly pinned local JAR or one unambiguous local build output.

## Verification and limits

Use real temporary files for merge, backup, conflict and rollback tests; simulate OS selection with explicit facts rather than changing the executing host. Exercise generated installers and hooks through subprocesses. Validate the resulting TOML/JSON and preservation of existing keys. Record real Desktop command evidence separately from simulated Mac/Notebook cases. External hardware success remains unproven until those hosts run the supplied commands.

Atomicity is per filesystem replace within a staged transaction, with rollback and durable recovery evidence across multiple files. No cross-host filesystem transaction is claimed. Existing source-owner leases coordinate cooperating writers; changed preimages and unknown locks fail closed.
