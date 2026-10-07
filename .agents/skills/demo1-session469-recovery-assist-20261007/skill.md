---
name: demo1-session469-recovery-assist-20261007
description: Use during the Codex session469 patch (session469-recovery-52e48f2a) only as its read-only assistant — pin anchors, coverage phrases, leases, one-hypothesis, product-before-RED, proof gaps, masks, focused-test presence, the six-scenario cancel probe, and the A->B receipt. This is not the product patch; exit 0 is never productPass.
---

# demo1-session469-recovery-assist-20261007

Companion rail for the Codex session469 patch. Codex owns `main/java`, `main/resources`,
`scripts/chat_ui_stream_contract_tests.js`, and product tests. This rail reads them and reports;
it never edits, never runs Gradle, never restarts the server, never calls a live model, and never
force-releases a lease — including one whose `expiresAtUtc` is past.

Root: `C:\AbandonWare\demo-1\demo-1\src`. Use PowerShell 5.1 with `;` and `$LASTEXITCODE`. The
output JSON `evidence` is the paste line back to the calling session.

```powershell
python -B scripts/session469_recovery_assist.py pin --root .
python -B scripts/session469_recovery_assist.py cover --root .
python -B scripts/session469_recovery_assist.py scope --root .
python -B scripts/session469_recovery_assist.py hypothesis --root .
python -B scripts/session469_recovery_assist.py next --root .
python -B scripts/session469_recovery_assist.py proof-gap --root .
python -B scripts/session469_recovery_assist.py mask --root . --file <completion.md>
python -B scripts/session469_recovery_assist.py focused-present --root .
python -B scripts/session469_recovery_assist.py cancel-probe --root .
python -B scripts/session469_recovery_assist.py receipt --root . --file <receipt.json>
python -B scripts/session469_recovery_assist.py diff-forbid --root . --diff <owned.diff>
python -B scripts/session469_recovery_assist.py product-gate --root . --diff <owned.diff>
python -B var/codex-assist-session469-recovery-20261007/selftest_spec.py
node scripts/session469_cancel_recovery_probe.js
```

## Command contract

| Command | Exit | Status |
|---|---|---|
| pin | 0 / 4 / 3 | FRESH / ANCHOR_STALE / CONTRACT_GAP |
| cover | 0 / 4 / 3 | COVERED / SEARCH_GAP / MISSING_FILE |
| scope | 0 / 7 | CLEAR / OVERLAP (WAIT unless you are that ownerId) |
| hypothesis | 0 / 3 | NONE or ONE_ACTIVE / HYPOTHESIS_SPREAD |
| proof-gap | 0 / 3 | OPEN or MIXED / PROVEN_UNRECEIPTED or SYNTHETIC_NOT_RECOVERY |
| mask | 0 / 3 | MASK_CLEAR / MASK_HIT (line text never copied) |
| focused-present | 0 / 3 | PRESENT / BASELINE_BLOCKED (files only; Gradle not run) |
| cancel-probe | 0 / 1 / 2 | PROBE_GREEN / PROBE_RED / tool error |
| receipt | 0 / 3 | RECEIPT_OK or RECEIPT_OPEN / RECEIPT_GAP or RECEIPT_LEAK |
| next | 0 | NEXT_WAIT or NEXT_READY |
| any | 2 | tool error |

## Order

1. `scope` — OVERLAP means WAIT unless this session is the printed ownerId. The scope scanner does
   not classify staleness; it only reports live lease targets.
2. `pin` — FRESH is the anchor state at assist open (three paste SHA12s already drifted mid-patch;
   see the pack README table). ANCHOR_STALE means re-read, never restore a hash.
3. `cover` — missing tokens are the fixture phrases the brief wants (mid-table relation,
   same-column relation, tokenless/keepalive stop, state-recheck, late-event scoping). A missing
   phrase is not proof the behavior is absent; a found phrase is not a green test.
4. Write the named test fixture first → `product-gate` must return TEST_ONLY.
5. `cancel-probe` — the source-extracted boundary for W2. `tokenless-stop-stalls-new-send` RED is
   the wedge finding; PASS rows prove the landed contract (exact-run `/state` recheck, retryable
   cancel, single-flight) exists in `chat.js`. PROBE_GREEN is not a served-runtime verdict.
6. Set one hypothesis ACTIVE; copy `RED_BOUNDARY.example.json` to `red-boundary.json` with
   `RED_PINNED` before the first product edit.
7. Product edit inside allowPaths only → `product-gate` returns PRODUCT_SCOPED.
8. `focused-present` → PRESENT before Codex runs its own Gradle lines.
9. `receipt` on the A→B evidence-chain JSON — six stages OBSERVED with `bodyHash12` only; raw
   bodies/prompts/keys are RECEIPT_LEAK.
10. `mask` the completion note — `webCount`/`countReported`/`citable`/`promoted`/HTTP 200/exit 0/
    `정보 없음` on a PASS line is a hit, not a pass.
11. `proof-gap` stays open: synthetic or extracted GREEN is not recovery of stored session469.
    Live A→B and Stop→R2 acceptance belong to Codex, not this rail.

## Hard guards

- Never edit `main/java/**`, `main/resources/**`, `scripts/chat_ui_stream_contract_tests.js`, or
  `src/test/java/**` — Codex lease `session469-recovery-52e48f2a` owns them.
- Never touch `.agents/skills-intent-index.yaml` or `.agents/skills/demo1-search-recovery/` — a
  second live lease owns them; this pack is intentionally unregistered.
- Never put incident entity names (character, weapon, `나비의 우화`, `베스나`) into product code,
  tests, or receipts. Test fixtures use synthetic values only.
- Never mint a fresh Idempotency-Key to retry R2, never cancel a whole session, never remove the
  OUTCOME_UNKNOWN fence.
- A PASS in the probe proves the extracted function exists in `chat.js` — not the browser, not the
  stored session, not the public site.
- `null` in a receipt stage means unobserved, not disabled. Do not report it as fixed.

Pack files (gitignored, write via `[IO.File]::WriteAllText` UTF-8):
`var/codex-assist-session469-recovery-20261007/` — `spec.json`, `hypothesis.json`,
`proof-gaps.json`, `RED_BOUNDARY.example.json`, `README.md`, `HOLD.md`, `DEBUG_CARD.md`,
`REVIEW.md`, `selftest_spec.py`, `selftest-hypothesis-spread.json`.
