---
name: demo1-settings-routing-assist
description: >-
  Use when Codex is implementing (or reports done) the /settings page +
  six-role routing (chat.settings.routing.enabled, default off) and Devin
  must verify drift — G1~G8 guard, v3/v2 test-matrix coverage, read-only
  live probe; product source under main/ stays Codex-owned.
---

# demo1-settings-routing-assist

## When
A Codex settings/routing work package is in flight (or reported done) and
Devin must mechanically judge whether it crossed the forbidden lines, covered
the declared test matrix, and whether the live surface still works.

## Order (run in this sequence)
1. `python -B scripts/settings_routing_guard.py --snapshot`
   Once, as early as possible — fingerprints the 8 foreign-session M files,
   all forbidden paths, the porcelain set, and `/api/settings` key names into
   `var/settings-guard/baseline-<ts>.json`. `baselineAfterCodexStart=true` is
   recorded honestly when Codex files already exist.
2. `python -B scripts/settings_routing_guard.py --check [--scan-codex] [--json]`
   G1 chat.js / G2 chat-ui ≤2 / G3 foreign hunks / G4 forbidden files /
   G5 flag default / G6 endpoint scope / G7 secrets / G8 tables-DSL.
   `--scan-codex` also scans the files that already existed at baseline when
   `baselineAfterCodexStart` was true. Exit 1 on any FAIL.
3. `python -B scripts/settings_test_matrix_check.py [--json]`
   Maps v3-test-matrix (32) + v2-test-matrix (64) spec IDs and test_names to
   `src/test/java` + `src/test/js`; reads `build/test-results/test/*.xml`
   counts when a run exists. Coverage is a map — uncovered specs are
   reported, not demanded.
4. `python -B scripts/settings_page_probe.py [--baseline <file>] [--json]`
   GET `/settings`, `/chat`, `/api/settings` (key names only) on local 18180,
   GET-only parity on `abandonwareai.kro.kr`. `routing/read`+`preview` POST
   once each **only if** the controller source proves them write-free —
   otherwise NOT_RUN; `save` is never called.

## Verdict reading
- guard: PASS / WARN (new unrelated mappings) / FAIL (drift, forbidden file,
  flag=true, out-of-scope endpoint, secret, entity/sql) — FAIL means hand the
  file:line back to Codex; Devin never patches it.
- probe: SETTINGS_OK / SETTINGS_MISSING (page not built or 4xx) /
  MAIN_BROKEN (chat regressed or interview override) / UNREACHABLE.
- matrix: per-spec covered / excluded / not-covered; a missing class is a
  fact row, not a failure of this tool.

## Constraints
- Tools are read-only: git diff/status/show, file reads, HTTP GET (+ the two
  proven-pure POSTs). No server restarts, no config writes, no Gradle runs.
- Baseline-after-start caveat: drift checks measure *new* changes; Codex's
  earliest files are inside the baseline. `--scan-codex` re-includes them for
  content checks (G6/G7/G8).
- Report to `docs/diagnostics/settings-routing-v3-20261002/DEVIN_VERIFY.md`
  (or the dated dir for the round in play).
