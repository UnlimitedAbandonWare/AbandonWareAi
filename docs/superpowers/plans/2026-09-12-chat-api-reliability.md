# Chat API Reliability — Implementation and Acceptance

User approval: discretionary postprocessing authorized implementation. Executed inline with the existing repository three-query preflight and source-owner leases; parent owned all writes. No commit, production migration, dependency addition or deployment was performed.

## Implemented and locally verified

- [x] JDBC-backed JobService is the default. Persist accepted input before 202, claim work atomically, fence final writes by worker token/lease, separate result bodies from metadata, retain completed records for 24 hours and preserve uncertain outcomes.
- [x] Owner-scoped task/status/result/cancel endpoints preserve administrator and token checks. Restartable handlers invoke existing ChatService. Callback retries are bounded and durable, cannot rerun inference, and cannot be starved by pending generation.
- [x] Shared database tests cover independent services, three JVMs, two forced kills, owner isolation, remote cancellation, retention and exactly one counted fixture execution across workers.
- [x] Strict Redis EVAL transport, token-bucket Lua and a durable idempotency fence guard the three public generation routes. Attach/cancel/ACK are exempt. Duplicate=409, conflicting body=422, limited=429 with Retry-After, unavailable=503.
- [x] Five requests spaced 100ms across two filter instances invoke one downstream fixture. Transport serialization/errors, database fencing, asynchronous uncertain outcomes, expiry and trusted proxy hashing have focused tests.
- [x] Initial and exact-attach SSE use external-client leases. Last interactive disconnect cancels the exact run; internal bridges do not count. Normal completion and terminal replay remain. Client-facing 250ms comments expose idle socket failure without entering saved replay.
- [x] SDK/native/embedding transports hold a real loopback HTTP socket before cancellation, then close it within a combined 1000ms deadline. Existing no-fallback checks pass.
- [x] Final combined verification: 211 tests, zero failures/errors/skips; projects, compileJava, :app:classes and bootJar pass. HTTP transport tests were additionally refreshed with held-socket preconditions.
- [x] Sixteen production paths have original copies, final hashes and a source-only patch. Reverse-apply dry-run passes. Source hash mismatches=0. High-confidence secret-pattern matches in added production lines=0.

## Required external acceptance

- [ ] Apply reviewed SQL to a designated shared nonproduction database; start actual Spring instances A/B using the same Redis; repeat restart/status/result checks through those endpoints.
- [ ] Execute Lua on real Redis and prove instances share user/IP limits. The REST fixture verifies transport, not Lua semantics.
- [ ] Close real initial/attached browser connections while a provider request is active. Correlate backend cancellation with provider-specific termination/token/GPU evidence within one second. Local EOF/reset is not provider computation proof; silent network blackholes have no unconditional one-second detection guarantee.
- [ ] Repeat five requests at 0/100/200/300/400ms and observe one actual model attempt. Current call counts are controlled fixtures.

The prior API-only run was blocked on external acceptance after three audits. The user explicitly resumed and expanded the goal on 2026-09-12. Current execution is ACTIVE under `2026-09-12-conversate-integration.md`; the old audit remains historical, and missing shared infrastructure blocks only that acceptance lane. This API checklist does not certify the full Conversate goal.
