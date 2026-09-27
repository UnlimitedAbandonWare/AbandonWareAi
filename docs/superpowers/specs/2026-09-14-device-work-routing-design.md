# Device-aware shared work routing

Extend the existing host runtime and Control Tower with a cooperative work queue in `data/agent-handoff/device-work`. Preserve the SMB service, source tree, target-scoped source leases, Notebook Y-drive identity guard, and Mac worktree/PatchDrop workflow. This implements the current authorized request; standing autonomous-work instructions replace repeated design approvals.

## Chosen design

A standard-library Python queue stores one immutable specification and a replaceable state record per task. Short exclusive per-task operations serialize state publication; no repository-wide execution lock exists. A claim reserves a host capacity slot and explicitly named resources until terminal completion. Different tasks and device slots proceed independently. Unknown operation locks and interrupted claims are retained for reconciliation, never reclaimed by age or automatic retry. Completed task IDs cannot be replayed; a reused ID with different specification fails.

Target file bytes are hashed at enqueue. Directive/context references are relative paths plus hashes, never copied source or private prompts. Every claim returns those references and an existing-guard target manifest. Source-edit start requires current verification by `source_edit_session.ps1` against that manifest and an existing lease; the queue does not acquire a second source lease or write source. Notebook mutations must prove canonical Y-drive identity. Mac shared-source writes remain unavailable. Complete records changed paths and postimage hashes; changed input for a read task fails completion.

Each host publishes its own bounded CPU, memory, OS/architecture, GPU backend and load snapshot, without raw hostname or environment values. Snapshots expire after five minutes. Missing capability or load is unknown, never zero. Tasks declare minimum CPU/free RAM, GPU backend, OS/architecture, dependencies, comparable workload key, resource keys and a timeout. Roles are initial preferences: Desktop integration/build/test/RAG/orchestration; Notebook directive/design/research/light edits; Mac mini optional ARM/native/support workloads.

Routing filters eligibility before ranking. Completed successful tasks with matching workload/requirements form bounded recent duration samples. At least three samples on each compared host are needed. Mac automatic assignment additionally requires at least 10% lower median elapsed time (including recorded transfer time) than Desktop; native ARM jobs and explicit manual selection may bypass comparative speed, but not missing capacity/OS/capability/source authority. Final integration remains Desktop-owned. No inferred hardware speedups or synthetic samples enter live routing.

The existing toolbox and stdio manifest expose `device_work`. CLI and agent consumers explicitly probe/enqueue/route/claim/start/complete. An idle agent can pull its next eligible task; a shared packet never executes an arbitrary shell command. This is an on-demand work coordinator, not an installed unattended scheduler. Existing host-local runtime launcher supplies isolated build/cache commands.

## Alternatives and boundaries

- A database/broker would add deployment and SMB database-locking obligations; local JSON records fit the existing handoff seam.
- A single execution lock prevents the requested independent work; only task/resource registration and existing overlapping source reservations serialize.
- Automatic expiry/requeue can duplicate a still-running disconnected worker; explicit terminal reconciliation retains uncertainty.

Local multiprocess tests prove cooperative claim behavior and guarded file conflicts. Physical Notebook/SMB and Mac ARM runs are separate acceptance evidence, not implied by simulated OS fixtures. The comparison measures elapsed time, not energy efficiency. Raw logs, credentials and recovered bytes remain local.

## Official protocol context

Microsoft's [SMB2 CREATE contract](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/e8fb45c1-a03d-44ca-b7ae-47385cfd7997) defines create-only behavior that fails if an object exists. Python's [OS file operations](https://docs.python.org/3/library/os.html#os.replace) describe replacement semantics; this design makes no cross-file transaction or all-client cache visibility guarantee. Exclusive cooperative creation and fail-closed interrupted state require physical SMB acceptance on the actual mounts.
