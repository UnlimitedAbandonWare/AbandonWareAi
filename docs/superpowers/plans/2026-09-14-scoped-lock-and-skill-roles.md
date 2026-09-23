# Scoped lock and skill roles implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. The parent owns all edits; delegate only bounded independent reading under the repository routing contract.

**Goal:** Allow independent Desktop/Notebook edits, recover proven abandoned leases safely, expose ownership, and consolidate duplicated skill instructions without losing capabilities.

**Architecture:** Extend the existing target registry and its short publication handle. Bind renewable sidecar heartbeats to immutable lease identities; recover proven dead local owners into retained quarantine. Use separate task artifact coordination for evidence intake.

**Tech Stack:** Windows PowerShell, Python standard library, unittest, existing skill validators.

**Spec:** ../specs/2026-09-14-scoped-lock-and-skill-roles-design.md (approved by user: 설계대로 진행해).

## Global Constraints

Preserve unrelated changes, unknown locks, Git index locks, Notebook identity checks, target preimages and source authority. No application Java/resource changes or commits. Never infer process death from directory age, TTL alone, a remote PID or a transient helper process. Same-file writes remain serialized. Keep legacy records readable and unknown recovery evidence operation-local.

## Tasks

- [x] Add RED integration tests using temporary repositories: active old-directory preservation; expired unknown overlap; live/dead owner; heartbeat/fingerprint; reservations; read-only status and recover receipts.
- [x] Extend `source_edit_lease_contract.ps1` and `source_edit_session.ps1`: process identity, sidecar renewal, canonical reservations, short lifecycle serialization, retained quarantine, redacted diagnostics. Re-run focused tests.
- [x] Route Notebook lifetime and cleanup through the common contract. Add heartbeat and owner-process inputs; preserve session hash and canonical-root checks. Adjust legacy unsafe cleanup expectations and run Notebook contracts.
- [x] Remove source reservation from AWX evidence-only wrapper. Bind evidence output paths to task/topic and serialize only shared artifact publication. Verify generated commands and behavior with focused fixtures.
- [x] Reconcile stale global HOLD wording; move detailed observation/examples to their canonical references and preserve skill entrypoints, roles, metadata and dependencies.
- [x] Run affected integration suites, skill validators and independent bounded review. Inspect owned diffs and pre/post hashes. Record actual evidence and remaining platform limits, update design status and complete the goal only after verification.

Verification and the separate broad-audit baseline failures are recorded in ../specs/2026-09-14-scoped-lock-and-skill-roles-result.md and the task-local verification.json.
