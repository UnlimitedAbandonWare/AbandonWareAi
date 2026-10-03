# ACCEPTANCE — fake-contention suite (contract DEMO1-DEVIN-COOP-VERIFY-RAILS-FOR-CODEX-20260928)

Runner: `python -B -X utf8 -m pytest scripts/test_coop_verify.py -v`
Result: **11 passed** (2026-09-28, exit 0). All tests run on a `tempfile` root + `tempfile` store — no product files, no Gradle, no live leases, no LLM/API calls.

## Contract rows → test mapping

| # | Contract requirement | Test | Evidence |
|---|---|---|---|
| T1 | Foreign `EDITING` ⇒ heavy verify `DEFERRED` + ticket, zero builds | `test_t1_foreign_editing_defers_and_never_builds` | run-once exit 10 `deferred-writer-active`; counter file absent |
| T2 | ≤1 heavy verification concurrent | `test_t2_second_verifier_is_deferred` | `.verify.lock` held ⇒ exit 10 `verifier-busy` |
| T3 | Duplicate requests merge; oldest `requestedAtUtc` retained | `test_t3_duplicate_requests_merge_keep_oldest` | merged flag, `supersededBy`, timestamp preserved |
| T4 | Turn end = `APPLIED_PENDING_VERIFICATION`; `DEFERRED`≠PASS; no Stop retry loop | `test_t4_turn_end_pending_is_not_pass` | status `turnEnd` block reports pending, never pass |
| T5 | Orphan writer ⇒ `BLOCKED_UNKNOWN_OWNER`; never reclaim/verify-as-PASS; bounded release | `test_t5_orphan_writer_blocks_never_passes` | recover→blockedUnknown; run-once still defers; `--release` frees only after evidence |
| T6 | Scope-overlapping writer change inside verify window ⇒ `INVALIDATED` (incl. A→B→A) | `test_t6_mid_verify_edit_invalidates` | mid-build writer begin/heartbeat/end ⇒ exit 11 |
| T7 | Stable-source failure ⇒ `FAILED`, never fake PASS | `test_t7_stable_failure_records_failed` | exit 20, receipt `verdict=FAILED` |

## Extra rows (beyond contract minimum)

| Test | Covers |
|---|---|
| `test_pass_receipt_and_supersede` | happy-path PASS receipt (`sourceIdentity` hashes), differing-scope tickets don't merge, later request supersedes closed one |
| `test_quiet_window_blocks_then_clears` | `QUIESCING` state while `source_quiet_seconds` not met; clears to eligible after |
| `test_stale_heartbeat_still_defers` | stale-heartbeat live writer still defers (heartbeat expiry ≠ completion) |
| `test_writer_token_mismatch_refused` | token fencing — foreign agent cannot end another's edit_batch |

## Exit-code contract used by tests

0 `VERIFIED_PASS` · 10 `DEFERRED`/`WAITING_FOR_RUNNER`/`QUIESCING`/`verifier-busy`/`locked` · 11 `INVALIDATED` · 20 `FAILED` · 30 `ERROR`/`missing-input`. A wrapper must treat any non-zero as not-verified.

## NOT_RUN (declared, out of scope for this Devin phase)

- `gradlew :compileJava -x test`, `:test`, Start-RAG/Meta-Display smoke, on-glasses checks — belong to the Codex hook/wrapper phase; hooks currently have **no Stop hook**, so completion wiring is Codex-owned (`FOR_CODEX.md`).
- `watch` loop long-run soak — unit-covered via `run-once`; a soak is optional ops, not acceptance.
