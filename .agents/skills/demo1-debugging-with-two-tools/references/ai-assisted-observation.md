# AI-Assisted Observation

Reuse `tools/ai_debug_assist.py` as the observation slot. It connects the
existing local miner, owned STDIO `RuntimeToolkit`, and read-only Ox Alpha
delegate; it never runs model-proposed commands or patches. This route is for
an explicit build/boot log and one to eight exact source files. It does not
trigger on ordinary wording edits or authorize a recursive source upload.

From the confirmed root (Notebook `Y:\`), select explicit relative files:

```powershell
python -B tools\ai_debug_assist.py --log <existing-log> --source tools/build_error_miner.py --out <new-report.json> --timeout-seconds 30
```

The log is bounded to 1,000,000 bytes. Reports contain content SHA-256 and
class counts, never log examples. Source SHA-256 values bind the candidate
to the current files. MCP must pass initialization, known-count canary,
missing-input error, and actual snapshot checks in its owned process. Its
scope is classification only; catalog presence, `primaryClass`, and the
legacy path-derived `logHash` cannot prove root cause or content identity.
A failed MCP check uses local classification with an explicit fallback.

The existing delegate owns source allowlisting, secret rejection, temporary
copies, subprocess timeout/containment, and before/after checks. The adapter
allows a 1–60 second delegate timeout (default 30); MCP uses at most three
calls with 5-second execution limits and closes only its owned child.
Filesystem operations are bounded in size, not a hard wall-clock deadline.
No API key, model configuration, persistent environment, or application
source is changed. The JSON report is capped at 64 KiB and requires a new
output path. Failure returns a fixed reason without raw exception text.

If external AI is unavailable, continue with the current Codex analysis.
Run the same command with `--ai off` to obtain the source/log snapshot.
Codex can write a bounded candidate JSON and pass `--proposal <candidate.json>`
on the next call; this skips the external model. Candidate input is at most
16 KiB and uses the existing delegate's model-result validator:

```text
logSha256: exact snapshot logSha256
sourceHashes: exact snapshot map of relative files to SHA-256
summary: nonblank string, at most 500 characters
findings: 1–3 descriptive objects; each description at most 300 characters
evidence: 3–6 unique {file, line, description} entries in the allowlist
proposedTests: 1–4 nonblank descriptions, at most 300 characters each
```

Supplied candidates are labelled `candidateOrigin=supplied_candidate` and
`model=caller_supplied`, never as proof of a provider invocation. Stale hashes,
secrets, invalid file/line references, and changed sources suppress findings.
An external delegate's exit code zero alone is insufficient: require
`status=ok`, `fallbackUsed=false`, no changed files, and valid evidence.
All accepted candidates retain `claimsVerified=false`. Select exactly one
hypothesis and independently run its narrow counterexample test in the
verification slot before claiming a cause or entering the existing edit gate.

For randomized tests, use local `random.Random(seed)` and synthetic inputs.
Record seed, case ordinal/count, and input SHA-256 on failure. Do not change
production values or call random external endpoints. A shuffled candidate
ledger is not evidence that input fuzz tests ran. The existing miner's
focused verifier includes 512 cases with seed `20260912`:

```powershell
python -B scripts\test_build_error_miner.py
python -B scripts\test_ai_debug_assist.py
```

The falsifiers are count/cap/redaction failures, an accepted stale candidate,
unvalidated MCP output, or AI claims being promoted without focused evidence.
Rollback is the exact-file backup plus removal of the newly introduced
adapter/test when requested. Keep existing delegate, MCP, and miner assets;
this adapter fills their missing connection rather than replacing them.

