# FOR_CODEX — checkpoint scanner Java-lambda false positive (F06 unblock)

Date: 2026-10-02 KST · Agent: Devin · Task: `devin-checkpoint-lambda-fp-b86ba2af`
Cycles: `data/agent-handoff/codex-autonomy/devin-checkpoint-lambda-fp-b86ba2af/cycle-01..03`

## Cause (confirmed read-only via new explain tool)

- File: `main/java/com/example/lms/uaw/autolearn/UawAutolearnOrchestrator.java`
  (sha256 `69f8dc8627df…`, bytes unchanged before/after — verified).
- Single blocking hit: **line 148**, identifier `token`, rhsKind **lambda** —
  a `PreemptionToken` typed local declaration bound to an empty-parameter
  lambda (`() -> …`). No credential value on the line.
- The other `token` occurrences (lines 151, 169, 187, 192) are plain
  identifier uses — call arguments and method receivers — never matched.
- Matches `GATE_HOLD.json` f06: surface "checkpoint secret_free read-only
  validation", reasonCode `secret-pattern`.

## Patch — `scripts/codex_work_checkpoint.py`

One general rule appended to the Java branch of `nonliteral_ui_expressions()`
(+7 lines, comments included): a typed Java declaration
`<Type> <name> = <lambda>` where `<name>` is a secret-keyword identifier
(`password|passwd|pwd|clientSecret|client_secret|apiKey|api_key|token`,
case-insensitive) and the lambda is `() ->`, `(a, b) ->`, or `x ->` (typed
parameters and block bodies included). Only the label span is masked; every
RHS byte stays under the existing value scan.

- A quoted-literal lambda body is excluded by a lookahead, so a supplier
  returning a string literal still holds even when the literal is not a
  prefixed credential form.
- No path-scoped exemption, no exact-string exemption, no flag or
  environment-variable bypass. No existing rule weakened or removed.
- The 171-line foreign uncommitted diff already in the file is preserved
  byte-for-byte (diff stat 171 -> 178 = foreign + mine only).

## Verification — all green, 2026-10-02 KST

- `python -B -m unittest scripts.test_checkpoint_secret_explain scripts.test_codex_work_checkpoint_java_lambda scripts.test_codex_work_checkpoint scripts.test_codex_work_checkpoint_selfask_fixtures scripts.test_codex_work_checkpoint_source_expressions`
  → **77 tests OK** (1 environment-conditional skip:
  `test_symlink_target_is_rejected`, "symlink privilege unavailable" —
  pre-existing, unrelated).
- Explain tool on the orchestrator file: verdict `hold` -> `pass`,
  sha256 `69f8dc8627df…` unchanged. Run it yourself:
  `python -B scripts/checkpoint_secret_explain.py --path <repo-relative> [--json]`
  (exit 0 pass / 6 hold; emits `{path, sha256_12, verdict, hits:[{line,
  identifier, rhsKind, blocking}]}` — matched bytes are never printed).
- Positive controls still hold: quoted-literal assignments, prefixed `sk-`
  values, a lambda returning a quoted literal, bare (untyped) keyword
  assignment, private-key block, `.properties` credential line, comment /
  string / text-block mimics, non-Java paths, and mid-line declarations
  (exemptions stay line-anchored like every existing pattern).

## For Codex — resume F06

- The Java edit is yours: goal = `AutolearnOutcomePropagationTest
  .incompleteIngestCannotResetBudgetFailureBackoff` GREEN (expected 1,
  actual 0). `checkpoint begin` on the orchestrator file now passes
  `secret_free`.
- Keep the scanner-rule freeze on your side — do not edit
  `codex_work_checkpoint.py`; file new false-positive reports as fixtures in
  `scripts/test_codex_work_checkpoint_java_lambda.py` style instead.

## Next candidates (out of scope, noted only)

- The exemption list is still mostly exact-path + exact-string; a
  generalization pass (value-shape based detection instead of per-fixture
  masking) remains future work.
- Top10 leftovers (uncommitted checkpoints, zombie journal sweep) were not
  touched by this task.
