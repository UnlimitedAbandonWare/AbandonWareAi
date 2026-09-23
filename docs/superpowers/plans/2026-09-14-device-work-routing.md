# Device work routing implementation plan

> Parent executes with Superpowers executing-plans and test-driven-development; read-only exploration uses one bounded agent. No commit/push/deploy is authorized.

**Goal:** Device-aware independent shared work with persistent handoff, duplicate protection and measured role adaptation.

**Architecture:** Add `awx_device_policy.py` for hardware facts and eligibility/ranking, `awx_device_work.py` for a durable cooperative queue, and register one tool in the existing Control Tower. Reuse source ownership and host-local runtime helpers.

**Tech Stack:** Python 3.11 standard library, existing PowerShell guards, JSON MCP catalog.

**Spec:** `docs/superpowers/specs/2026-09-14-device-work-routing-design.md`.

## Constraints

Preserve existing bytes/preimages and target-scoped leases. No new provider, dependency, database, SMB service, background scheduler, credential or Git writes. Unknown ownership and stale telemetry fail only affected operations. No paid review or fabricated physical-host proof.

## Task 1: Executable acceptance contracts

- [x] Create `scripts/test_awx_device_work.py`: enqueue/replay, changed ID, dependencies, simultaneous claims, capacity, source preimages, source gate, stale telemetry, unknown GPU, role defaults and measured Mac routing.
- [x] Run `python -B -m unittest scripts.test_awx_device_work`; observe missing implementation RED and record it as expected characterization.
- [x] Seal the tests/docs checkpoint and release only its lease.

## Task 2: Queue and policy

- [x] Create `scripts/awx_device_policy.py`: `probe(root, role=None)`, `rank(task, devices, history, now, preferred=None)`.
- [x] Create `scripts/awx_device_work.py`: `WorkQueue(root)`, `publish(device)`, `enqueue(spec)`, `route(task_id, preferred=None)`, `claim(task_id, device_id)`, `start(task_id, device_id, lease=None)`, `complete(task_id, device_id, outcome, transfer_seconds=0)`, `status()` and `device_work(payload)`.
- [x] Require finite bounded values, no duplicate/path-alias/traversal/reparse targets, immutable input hashes, per-task operations and explicit host capacity/resource reservation. Preserve interrupted state.
- [x] Run the acceptance contracts in temporary directories; use real subprocesses for competing claims.

## Task 3: Existing tool integration and delivery

- [x] Add one toolbox wrapper and one stdio handler; add the closed `device_work` manifest contract. Keep shared-read restrictions.
- [x] Run new tests plus affected host-runtime, source-concurrency and stdio schema/catalog tests. Refresh only count assumptions changed by the added tool.
- [x] Probe actual Desktop hardware once and demonstrate an artifact-only handoff through enqueue/claim/start/complete/replay. Keep synthetic comparison samples in test fixtures.
- [x] Write `docs/device-work-routing.md` with Desktop/Notebook/Mac commands and explicit physical acceptance procedure; write task report, postimage hashes, local recovery references and remaining evidence.
- [x] Inspect task diff, seal and finish checkpoints, release owned lease, and retain physical evidence limitations without claiming unseen speedups.
