# Codex export nightly review — local implementation

Contract: `CODEX-SESSIONS-ANTIGRAVITY-NIGHTLY-20261008`. Checked 2026-10-08.
The live implementation is `scripts/codex_nightly_review.py`; focused tests
are `scripts/test_codex_nightly_review.py`. This is a developer tooling lane,
separate from Spring `/chat`, product RAG traces and Meta Display.

## Current boundary

The shipped configuration is disabled. The local runner indexes only files
named in an explicit manifest, creates a morning report plus a bounded analysis
request, verifies output readback and commits an incremental watermark. It has
no agent subprocess, network client, source patch application, PR publisher,
session-message sender or scheduler-registration code. `aiEnabled=true` and
`codexFallback=true` are rejected, including in disabled configurations.

Live AI analysis and Windows scheduling are **NOT_RUN / not implemented**.
An offline AGY stream validator is provided for integration tests, and does
not establish account authentication, billing, sandbox isolation or a live
model call. No native Codex export function or private session location is
assumed. A human-approved export adapter is still required for real records.

The batch reads approved Codex records, prepares local observations and keeps
external AI optional. It never manages other chats, changes original source,
skills or permissions, or falls back to Codex. Missing input, parsing and
delivery remain visible as HOLD with a local report where configuration permits.

## Configuration and invocation

Start from `configs/codex-nightly-review.example.json`. Copy it to an explicitly
chosen configuration path; do not modify the shipped example for real records.
Running the example is a no-op:

```powershell
python -B scripts/codex_nightly_review.py run --config configs/codex-nightly-review.example.json
python -B scripts/test_codex_nightly_review.py
```

`run` also accepts an optional output-cap metrics path:
`--sessions-dir <rollout dir> --days N --json-out <file>`. With no `--config`
the run is `METRICS_LOCAL` — no manifest collection and no state writes. It
counts `Warning: truncated output` markers in raw Codex rollout `*.jsonl`
files (token counts only; content is never retained), and the result JSON
always carries `truncated_output {count, token_sum, median, p90, max,
measured}`. A measured p90 above 10000 tokens prints
`WARN_OUTPUT_CAP: p90=<value>` after the JSON line. Without `--sessions-dir`
the field reports zeros with `measured=false`.

For local-only collection, explicitly supply `enabled=true`, `firstDate`,
absolute `allowedRoots`, `inputManifest`, `outputRoot` and `deliveryRoot`.
`outputRoot` and `deliveryRoot` must stay inside the canonical project root.
Reviews and receipts are not dot final directives and never go to Downloads.
Reuse `docs/reports/agent-reviews/<task-id>/nightly/` for human-readable delivery,
separate from the existing output/state directory. Project-external destinations
are rejected before writing. Null delivery produces a local HOLD report without
committing the watermark. Input, output
and delivery roots cannot overlap. Secret paths, traversal, UNC paths,
symlinks, reparse points and hardlinks are rejected. Original files are never
modified. Do not use an entire user profile as an input root.

Budget fields cap catch-up days, file bytes, aggregate input bytes, event count, retained context,
text, packet bytes and candidate count. Truncated context and omitted candidates
remain explicit. Oversized input or state holds the affected run; reducing
coverage is never reported as full analysis. The state is bounded, and its
collection identity is tied to first date, timezone, roots and manifest path.
Changing that identity requires a new output root or an explicit migration.

## Input contract (our normalized format, not a Codex native format)

The control manifest is `codex-review-input-v1`:

```json
{
  "schemaVersion": "codex-review-input-v1",
  "files": [{
    "fileId": "approved-export-01",
    "path": "C:/approved-export/session.jsonl",
    "sourceType": "codex-session-export",
    "format": "codex-review-events-v1",
    "sizeBytes": 123,
    "sha256": "<actual lowercase SHA-256 of the exact file bytes>"
  }]
}
```

Each NDJSON line is a deliberately prepared and approved summary:

```json
{"timestamp":"2026-10-07T23:59:00+09:00","sessionId":"stable-export-session","eventId":"stable-event-01","kind":"goal","origin":"human","sanitized":true,"text":"Approved task summary"}
```

Kinds: `goal`, `correction`, `cancel`, `tool`, `error`, `change`, `verify`.
Human-request kinds require `origin=human`; the others require `origin=tool`.
`verify` may include explicit `outcome=PASS|FAIL|NOT_RUN|UNKNOWN`. Success is
never inferred from response text or a transport status. Unknown fields or
formats, assistant-origin preferences, reasoning payloads, unapproved summaries
and product RAG files are held. A final newline is required, so a partially
written last record cannot silently count as complete.

`sanitized=true` is the producer's explicit approval claim, not proof that a
regex can discover every private fact. Supply reviewed summaries, not arbitrary
raw conversations, environment dumps, system instructions or reasoning. The
existing pure `log_redact.redact_text` function additionally masks common
credentials. URLs and email addresses are removed. Private-key blocks and
explicit private-reasoning/environment-dump markers fail closed. Commands
within text stay untrusted data and are never executed.

Session IDs, event IDs and file IDs are hashed before persistence. File/line
pointers and event hashes remain attached to observations. Stable event IDs
deduplicate rotation/clones; an ID with conflicting content is a visible error.
Separate retry events retain their identity rather than being collapsed because
their text happens to match. The initial goal and latest correction/cancellation
are retained independently from the bounded context tail. Cross-midnight events
remain in the same session. Prior-day context persists in the local state.

## Recovery and output

The collection interval is `[watermark, end)` in Asia/Seoul, ending no later
than today's midnight and capped by `maxCatchupDays`. Pending extra days are
shown in `catchupRemainingDays`; rerunning advances the next bounded interval.
New historical events older than the committed watermark are HOLD (`late_event`),
requiring an explicit backfill/version policy instead of silently skipping them.
Empty manifests/files, inaccessible input and parsing failure are separate from
a successful no-change run.

An exclusive `run.lock` prevents concurrent execution. A leftover lock is not
deleted based only on age: verify the recorded owner's process and reconcile
the interrupted run before removing it manually. Task Scheduler's `IgnoreNew`
policy is a future supporting guard, not the local lock's replacement.

Each deterministic run stores a transaction, `report.txt`, `coverage.json`,
`analysis-request.json`, receipt and `validation.json` under `outputRoot/runs`.
The transaction preserves the actual start time across interruption. Pending
transactions are checked against the freshly reconstructed observations and
next state; committed transactions are hash-bound to the state. Report
and receipt are written atomically, delivery is reread and checked, and only
then is `state.json` atomically replaced. The delivered text remains
`PREPARED_LOCAL`; its matching `.completed.json` receipt is published only
after the state commit. `validation.json` also appears only after that commit
(or contains an explicit HOLD). A retry reuses verified immutable
outputs. New immutable outputs use exclusive publication, so a concurrent
destination creation cannot be overwritten. Existing output changed by another
writer is rejected, never overwritten.
If completion-receipt publication fails after the state commit, the next run
verifies and finishes that committed run's artifacts before processing any
later date or catch-up interval. Recovery does not replace current state from
a historical transaction.
HOLD observations get a distinct revision when the actual input state changes.
No watermark advancement occurs on parse, save or delivery failure.

Delivered names contain the KST report date and deterministic run ID, so an
explicitly revised report cannot overwrite an earlier version. `COMPLETE_LOCAL`
means local indexing and delivery passed; it never means Antigravity ran. Report
and analysis-request data are not an accepted rule or user preference. The
batch never re-ingests its own outputs, applies proposals, removes providers or
changes skills. Efficiency, false positives, useful-candidate ratio and actual
billing remain `NOT_MEASURED` until observed.

## Antigravity integration boundary

```powershell
python -B scripts/codex_nightly_review.py validate-stream --input <explicit-synthetic-stream.jsonl> --model <explicit-fixed-model> --exit-code 0
```

Official AGY NDJSON uses `event=init|step_update|result` with nested payloads.
`init.model` must match the requested model, exactly one final `SUCCESS` result
and the versioned candidate schema are required, and tool errors are rejected.
Exit 0 alone is insufficient. Only allowlisted usage numbers and sanitized
candidate fields are returned. Usage does not prove which billing account paid.
The validator marks source evidence as unbound; source hashes must be checked
against an approved snapshot before any proposal can be accepted.

Current official documentation supports headless `-p`, explicit `--model`,
`--output-format stream-json` and cached-account authentication. The same docs
state workspace file writes are automatically allowed by default. Therefore
a prompt saying "read-only" is insufficient. Before enabling any live wrapper,
verify an enforced read-only source/input boundary, fixed installed CLI/model,
account route, `useG1Credits=false`, overages disabled, one-call/time/token
budgets and redaction. Do not import existing live AGY helpers or enable a
Codex/OpenAI fallback. This implementation refuses live execution entirely.

Primary references, checked 2026-10-08 (current CLI docs; installed version
not asserted):

- [Headless / event schema](https://antigravity.google/docs/cli/headless/)
- [Credits and fallback billing](https://antigravity.google/docs/cli/credits/)
- [Default workspace permissions](https://antigravity.google/docs/cli/permissions/)
- [Windows StartWhenAvailable](https://learn.microsoft.com/en-us/windows/win32/taskschd/tasksettings-startwhenavailable)

Scheduling requires a separately confirmed time, account/logon/power conditions,
retention and budgets. Future registration should use one scheduler, normal
user permissions, absolute executable paths, a daily trigger plus appropriate
logon catch-up, `StartWhenAvailable` and `IgnoreNew`. `WakeToRun` cannot prove
power-on from a completely shut-down PC. Login/logout/sleep/hibernate/shutdown
recovery, live model/billing and actual approved-session coverage are NOT_RUN.

## Live-source differences from the supplied reference

- `awx_session_evidence.py` is metadata discovery/search with raw snippets, not
  the normalized event parser; the nightly runner does not invoke it.
- `agy_cli.py` and `agy_command_runner.ps1` do not exist in the live checkout.
  Existing `agy_deep.ps1` and `agy_depth_bench.py` have live-call/raw-output or
  write-isolation gaps and are not connected to this runner.
- `chat_session_debug_export.py` remains a product diagnostic exporter; product
  traces do not become Codex sessions. The quarantine extractor is not imported.
- Java 17, Spring Boot 3.3.4 and LangChain4j 1.0.1 are confirmed in live Gradle.
  This tooling does not alter those dependencies or active Java sourceSets.
- The root AGENTS pointer and shared PROJECT_STATUS row are deferred if covered
  by foreign leases. This document and the current task journal remain the
  narrow entry points; no lease is bypassed to add a pointer.
