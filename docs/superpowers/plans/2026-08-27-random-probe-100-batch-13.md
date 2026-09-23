# Location Consent Revocation Cache-Purge Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Subagents remain read-only.

**Goal:** Make location-consent withdrawal immediately remove the user's in-memory reverse-geocoded address.

**Architecture:** Use mocked repositories and geocoder to populate the real singleton cache, revoke the existing consent record, and query the cache through the public API. On the production seam, remove only that user's cache entry when `enabled=false`; retain persisted coordinates, grant behavior, controllers, and geocoding.

**Tech Stack:** Java 17, JUnit 5, Mockito, Gradle 8.7.

**Spec:** [random-probe Phase A plan](2026-08-27-random-probe-100-audit.md), issue `RC5-02`, and root [AGENTS.md](../../../AGENTS.md).

## Constraints

- Modify one clean tracked source and create one visible focused test.
- Do not delete database rows, call geocoding/network, change consent persistence, or expose raw user/location values.
- Purge only after a valid nonblank user's disabled consent is saved; granting consent remains unchanged.
- Require focused privacy RED, stable three-way `APPLY`, owned lease, and unchanged preimage before source edit.

### Task 1: Prove post-revocation retention

- [x] Start from enabled consent, save one event with a mocked resolved address, and prove the cache is populated.
- [x] Revoke consent and require `getResolvedAddress(user)` to be empty; current source must fail this assertion.

### Task 2: Repair and verify

- [x] Remove the user's cached address when `setConsent(user, false)` completes.
- [x] Run exact GREEN, location service/controller neighbors, source gates, diff checks, hashes, ledger update, and release the matching lease.
