# Existing task continuity and delivery contract

The existing `<taskDir>/state.md` is the only task continuity state. A single
`continuity: <compact JSON>` line supplements its human summary; there is no
competing state database, automatic compaction hook or background scheduler.
Keep <=20 lines, with at most 128 KiB total; refusal preserves bytes, not truncation.
Transient JSON update packets and attachment observation receipts are evidence,
not authoritative state or stored permission. Recheck live user authority at action time.

`scripts/checkpoint_doctor.py` owns parsing, revision-checked update and read-only
completion validation. Legacy `--run <cycle>` remains the checkpoint diagnostic
(exit 0 for readable runs), not whole-task completion. Task `--check-complete`
and task-aware `demo1_goal_switch_barrier.py reject-complete` return exit 5 when
required current evidence is missing. `codex_work_checkpoint.py completion_cleanup`
uses the same validator before whole-task receipt creation; an incomplete delivery
does not undo a successful source verification or roll back working code.

## Delivery scope (current user policy)

Downloads is reserved for a dot-role final directive delivered to the user.
Reviews, drafts, logs and intermediate outputs use existing project report paths;
new review folders use `docs/reports/agent-reviews/<task-id>/`. Classify by role,
artifact kind and handoff stage, never model name or filename. See
`DEMO1-DELIVERY-DOWNLOADS.md` for examples and tool gates. This does not add a
Downloads obligation to every deliverable or reactivate old delivery defaults.
Keep exact current authorized destinations in the existing contract. A changed
instruction is reconciled with a new revision/supersedes; do not erase historical
proof or copy/move old Downloads files. Shadow preference candidates never override
this explicit policy. Attachment/local delivery remain independent only when required.

## Schema and example

All top-level fields below are required; unknown fields are refused. IDs are
bounded; revision starts at 1. Lists may be empty when the current task has no such
obligations. Use exact user-selected absolute file paths and the caller-verified
host identity. Do not assume `%USERPROFILE%/Downloads` is the OS Downloads folder;
Windows folder redirection and an explicit destination take precedence.

```json
{
  "schemaVersion": "awx.task-continuity.v1",
  "taskId": "example-task",
  "revision": 1,
  "instructionRef": "user:current-request",
  "observedAt": "2026-10-08T11:00:00Z",
  "goal": "Deliver the current directive",
  "nonGoals": ["Resume cancelled management", "Contact previous agents"],
  "taskStatus": "active",
  "supersedes": null,
  "remaining": [],
  "blockers": [],
  "nextAction": "Verify both delivery methods",
  "accessFailures": [],
  "preferenceEvents": [],
  "deliverables": [{
    "id": "directive", "version": "v1",
    "source": "<absolute current source file>", "sha256": "<64 lowercase hex>",
    "deliveries": [{
      "method": "file", "required": true,
      "destination": "<absolute user-designated OS destination>",
      "environment": "<verified host>", "status": "NOT_STARTED", "evidence": {}
    }, {
      "method": "attachment", "required": true, "destination": "chat:<current chat>",
      "environment": "<verified host>", "status": "NOT_VERIFIED", "evidence": {}
    }]
  }]
}
```

Task statuses: active/paused/cancelled/completed. Delivery statuses: NOT_STARTED,
READY, BLOCKED, VERIFIED, FOUND_VERIFIED, CANCELLED, SUPERSEDED, PARTIAL, NOT_RUN,
UNKNOWN, NOT_VERIFIED. Preserve their meanings through roundtrip. A latest stop
or resume requires a new revision; changed instructionRef must `supersedes` the
old ref. No chronological order creates authority. A cancelled/superseded delivery
must bind `supersededBy` to the independently confirmed current instructionRef.

For VERIFIED/FOUND_VERIFIED, evidence contains `sha256`, `version`, `taskRevision`,
`observedAt` (timezone required, not future), and `environment`. The validator
hashes actual source and destination; paths alone, old evidence, a different host,
or a previous artifact version cannot satisfy delivery. FOUND_VERIFIED reports
already present/matching and does not claim the writer or rewrite the file.

Attachment evidence additionally has an absolute `receipt` path. The bounded JSON
receipt must bind artifactId/method=attachment/destination/sha256/version/taskRevision/
environment/status=delivered/observedAt and a `tool:` sourceRef to the actual delivery
tool observation. This validates the recorded observation, not remote authenticity
or continued availability. Never manufacture receipts from an intention or local link.
If no attachment API is available, report NOT_VERIFIED and the lane-specific limit.
Never retain browser authentication, tokens, raw trace or private conversation here.

Access failures have exactly action (read/write/attach), target, environment,
observedAt and sanitized errorCode. A recorded failed read is not retried by the
completion checker. A supported access-state change or new explicit request may
reconcile it in a new revision. Other targets/actions remain independent. No access
denial bypass through alternate tools, accounts or paths.

## Revision and completion commands

```powershell
python -B scripts/checkpoint_doctor.py --state <taskDir>/state.md --write-contract <input.json> --expected-revision 0
python -B scripts/checkpoint_doctor.py --state <taskDir>/state.md --latest-instruction-ref <latestUserRef> --expected-revision 1
python -B scripts/demo1_goal_switch_barrier.py reject-complete --task <taskId> --text "<concrete claim>" --latest-instruction-ref <latestUserRef> --expected-revision 1 --environment <verifiedHost>
```

Initial legacy upgrade uses expected revision 0; updates must be old+1. The writer
uses an exclusive temporary lock and preimage/revision checks plus staged replacement,
rejecting competing writes. This coordinates cooperating writers; it is not a
filesystem transaction against arbitrary uncooperative mutation. Never copy latest
bindings from stale state instead of reading the current user's request.

New whole-task cleanup requests include instructionRef/taskRevision/environment.
Older checkpoint cycles and prose-only completion calls stay backward compatible;
their success is not proof of required delivery. New/updated tasks must use the
task-aware gate. Skills link DRAFT, resume and final delivery to this exact state.

## Preference shadow evaluation

Minimal events live in the same `preferenceEvents` list, with exactly eventId,
sourceRef, actorVerified, taskId, actionCategory, artifactType, destinationClass,
intentEvidence, outcome, observedAt, supersedes and scope. Do not store whole session
dumps, secret values, raw reasoning or redundant permission snapshots.

Only explicit-user-choice from a verified user: ref in the same exact context counts,
once per independent task. Retry/quote/tool/assistant/silence evidence is excluded.
3-5 choices are an experiment threshold for a shadow candidate, not an empirically
optimized value or automatic approval. Count opportunities and opposing observations;
conflict, correction or cancellation suspends candidates. Current explicit choices
win without relearning. A task exception does not erase preferences globally.
Only report/directive local delivery defaults are candidates; delete/external send/
payment/credential/security/restart are never promoted. `actionAuthorized=false`
always: adoption needs the current authorized task plan and a real delivery obligation
in state.md, which remains incomplete until verified.

`--preference-scope <exact scope> --artifact-type report|directive` is read-only shadow
evaluation. Tests measure support/deduplication/false promotion and preservation of
delivery conditions; no accuracy, token savings or model compaction improvement is
claimed. `instruction_slots()` supports tagged synthetic fixture lines only; it is
not an unrestricted natural-language parser or lossless semantic extraction claim.

## External extension boundary (OFF)

No external compression backend/model/DB/router/timer/key is implemented or called.
A future approved background-summary request must identify service, minimal public
data, retention, cost and current permission. Keep original required slots local;
bind response schemaVersion/taskRevision/source coverage and reject stale/schema/
timeout failures without resetting state. External summaries cannot alter authority,
scope, required slots or verified status. Benchmark need before adding such a backend.

Verification: `python -B scripts/test_checkpoint_continuity_delivery.py` uses synthetic
temporary files and actual CLI/completion hooks; no stored user auth or network calls.
Check related checkpoint/barrier tests after code changes. Real historical conversation
improvement and native compaction remain NOT_PROVEN by synthetic tests alone.
