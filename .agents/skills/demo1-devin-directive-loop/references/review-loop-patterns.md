# Review-Loop Pattern Catalog

Distilled from the madwain (R1–R6) and ma33in directive cycles, 2026-09-21/22.
Each entry: the pattern, why it kept recurring, and the exact check that closes
it in one round. Consult before drafting any DRAFT/REVIEW reply; skip entries
whose premise is absent from the current report. Official-contract notes carry
their 확인일.

## A. Evidence and accounting

1. **Test-count aggregation drift.** "77/77에 85 포함" style contradictions.
   Gradle writes per-class XML; summing runs mixes timestamps and duplicates.
   Close with: per-run command + XML path + exit code + source state; subset
   relations (B ⊂ F) stated explicitly.
2. **Historical results resurfacing as current.** A pre-fix run (e.g. 806)
   reappearing in the final table. Close with: `historical` label + re-verify
   only the affected surface on final source.
3. **Result dirs overwritten by reruns.** Close with: preserve prior XML
   (`xml-pre-*` copies) before any new Gradle run; distinguish UP-TO-DATE.
4. **billing_unverified.** Observed tokens ≠ billing. Never declare free or
   paid without billing evidence; record the label and stop.
5. **baseline_missing vs 선행 결함 단정.** Without a same-condition
   pre-change comparison, an old failure stays `baseline_missing`, not
   "pre-existing defect". File-signature/mtime absence is not proof either way.

## B. Provenance and authority

6. **Reconstructed preimage.** Editing outside lease scope, then deriving the
   "original" backwards. Close with: `reconstructed_preimage` marker file;
   only bytes preserved before the edit count as preimage.
7. **Git creep.** This loop bans Git including read commands; recovery comes
   from checkpoint dirs only. Restate the ban in every handoff — agents drift.

## C. Environment artifacts that masquerade as defects

8. **PowerShell Korean encoding.** PowerShell < 7.4 sends request bodies as
   ASCII by default; Korean queries arrive mojibake and look like server
   faults. Close with: explicit UTF-8 in repro scripts; verify
   transcriptHash == sha256 of the exact submitted string before blaming the
   server. (Microsoft docs, 확인일 2026-09-22.)
9. **Stale TTL cards.** Polling can return a previous session's in-TTL card.
   Close with: requestId match required before claiming delivery.
10. **auth_required lanes.** `/api/diagnostics/*`, `/api/chat/sync`,
    `/api/router/status` need an owner session by design. Anonymous 403 is not
    a defect; classify `auth_blocked`, never weaken security to pass.

## D. Domain contracts already verified — do not re-litigate

11. **Embedding 2560→1536 is by design.** `OllamaEmbeddingModel` slices raw
    2560 to configured 1536 (`slice_to_configured_dim`, fingerprint
    `ollama|qwen3-embedding:4b|1536`, same path for docs and queries). Qwen3
    MRL supports user-defined dims. Not a config error; do not "fix" dims,
    model, or namespace. (확인일 2026-09-22; PROJECT_STATUS.md 2026-09-22 row.)
12. **Brave response shape.** Official schema: top-level `web` is nullable and
    `result_filter` changes included result types; missing `web.results` is
    not automatically a parse error. `Accept-Encoding` unset does NOT imply
    uncompressed (absence means all codings acceptable). Official examples use
    `Accept: application/json`. (확인일 2026-09-22.)
13. **Spring placeholder precedence.** `${...}` references can be the user's
    explicit setting; skipping "placeholder" sources to find a literal value
    misjudges explicitness. Judge by the source that supplied the effective
    value (origin-based), never by value comparison — `warmup.embed-model
    != embedding.model` cannot see "explicitly set to the same value".
    Verified 3-case: default-inherit / explicit-different / explicit-same.
14. **OpenAI alias ≠ destination.** A route named like OpenAI does not prove
    the wire destination; verify the actual URL. A 3310ms TimeoutException was
    a shared TimeBudget slice, not provider failure — separate cancel,
    per-attempt deadline, and client floor.

## E. Loop hygiene

15. **Delta-only discipline.** Each round lists kept items in one or two lines
    and spends the rest only on new gaps. Re-opening a closed item requires
    new evidence, not a new session.
16. **Scope-freeze sentence.** Every follow-up directive includes: "이미
    검증된 항목은 재조사하지 말고, 실패 재현 → 최소 수정 → 해당 실패
    재검증까지만 수행" — blocks both regression and creep.
17. **Tiered final report.** Changed parts / final-source tests / runtime
    reflected / remaining blockers, each mapped to the evidence tiers in
    SKILL.md. A green badge, a report file, or a generated count is not
    recovery proof.

## Precedent records

- `docs/PROJECT_STATUS.md` rows 2026-09-22 ~14:1x / ~15:0x (madwain R1–R6
  verification and gap-verify close-out).
- `__patch_drop__/brief-remaining-gaps.md` (the six follow-up briefs).
- Journals: `madwain-gap-verify-f4e7fe35`, `rag-agent-orchestration-upgrade-*`.
