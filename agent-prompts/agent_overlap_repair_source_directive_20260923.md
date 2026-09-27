# SourceDirective

```text
directiveId: SD-AGENT-OVERLAP-20260923
sourceOwner: desktop
originEvidenceRoot: null
canonicalExecutionRoot: C:\AbandonWare\demo-1\demo-1\src
readRoot: C:\AbandonWare\demo-1\demo-1\src
writeRoot: C:\AbandonWare\demo-1\demo-1\src
finalProofRoot: C:\AbandonWare\demo-1\demo-1\src
authorizedMutation: true
userRequest: 2026-09-23 request to draft this packet for Codex to execute
route: $demo1-desktop-canonical-goal-intake
oneDirective: true
```

This file is the selected packet. Do not choose another directive by timestamp.
Execute the declared stages. A report that restates this file is not completion.

Draft observation time: 2026-09-23. Re-read leases and file bytes before each
stage. The observations below are leads, not a fresh status.

## No-change boundary

- No Git read or write. No commit, push, reset, or clean.
- Do not print or copy credentials, session transcripts, or provider payloads.
- Do not change openssl or opnessl names or values. Do not change ports
  18180-18182. Do not kill a process to clear a lock.
- Do not delete a lease directory. Quarantine only through `-Action recover`
  after the Stage 1 rule exists. Receipts stay.
- Do not edit `bin/main`. Do not JDBC the live H2 file.
- Do not merge `~/.codex`, `~/.grok`, or `%APPDATA%\devin` session databases.
  `agent_session_watch.py` stays a health scan.
- Embedding 1536 and the Brave response-shape contract stay closed.
- Do not author a second exact-model design. Stage 2 applies the prepared patch.
- Foreign `in_progress` journals are liveness-unknown. Resume
  `chat-repair-dynamic-ui-0868f566` for Stage 2. Do not open a second writer
  on `main/resources/static/js/chat.js`.

## What is already true

| Item | Evidence | Class |
| --- | --- | --- |
| Ownerless expired lease still blocks the model files | `__patch_drop__/source-edit-locks/madwain-live-verify.lock/lease.json`: `ownerProcessId=0`, `expiresAtUtc=2026-09-22T09:10:33Z`, 13 targets including `DynamicChatModelFactory.java`, `LlmRouterAspect.java`, `docs/PROJECT_STATUS.md` | confirmed |
| Recover will not move pid 0 | `source_edit_lease_contract.ps1` quarantines only `ownerState=dead`. Pid 0 stays `owner-evidence-needed`. `lease-lifecycle.md`: TTL alone is not death proof, and an expired unknown reservation keeps blocking so the owner can renew | confirmed |
| Exact-select patch is written and not applied | `data/agent-handoff/codex-autonomy/chat-repair-dynamic-ui-0868f566/journal.json` hold at 2026-09-23T05:01:31Z. Patch: `pending-exact-model.patch`. `targetConflict.allowed=false`, `conflictingLeaseCount=1` | confirmed |
| UI model is not strict | `chat.js` send payload around the `model: dom.modelSelect` field does not send `strictModelSelection`. `ChatWorkflow` calls `RequestedModelSelection.begin` only when that flag is true. No other Java caller sets the flag | confirmed |
| AOP and factory can still substitute | `LlmRouterAspect.aroundLcWithTimeout` aliases then `bandit.pick` can return before the factory. Factory `sharedLocalFailover` wraps `routeLocalInference`. `ResponseModelVerifyingChatModel` accepts `approved_alias` | confirmed |
| Router exact path exists and is not the hole | `PolicyBasedModelRouter` line that calls `exactRequestedModel` when `RequestedModelSelection.matches`. `ExactRequestedModelTest` covers the router, not the aspect | confirmed, do not redo |
| Cline misses the device bus | `.clineignore` ignores `data/device-resources/`. Events and registry are reference files (`data/device-resources/README.md`). `.secrets/` stays ignored. `var/meta-display-db/export/` is already readable | confirmed |
| Chat and Display are different databases | `application-local.yml` uses `jdbc:h2:mem:lmsdb`. `application-meta-display.yml` uses `jdbc:h2:file:./var/meta-display-db/lmsdb` | confirmed |
| Session DBs are per product | Codex, Grok, Devin, and Cline stores are separate. Sharing them is not the fix | not a defect |
| Latest user text already wins | `AGENTS.md` `DEMO1-STALE-HANDOFF-REFERENCE` | keep; add a machine-readable field only |
| `AGENTS.md` was not edited by this draft | Active lease `meta-display-db-free-view` (`ownerId=devin-meta-db-view`, pid 0, `expiresAtUtc=2026-09-23T07:24:59Z`) includes `agents.md` | skip until a fresh status allows it |

## Stage P — pointer, only if the path is free

Targets: `AGENTS.md` only.

Before editing, run status with a target manifest whose only path is
`AGENTS.md`. Proceed only when `targetConflict.allowed` is true. If
`meta-display-db-free-view` still covers it, skip this stage and continue
Stage 1. Do not wait and do not take the lock.

Insert this block immediately after `<!-- END DEMO1-SOURCE-DIRECTIVE-AUTO -->`:

```text
<!-- BEGIN DEMO1-AGENT-OVERLAP-REPAIR -->
## Agent overlap repair (authorized auto-execute)

- directiveId: SD-AGENT-OVERLAP-20260923
- Packet: agent-prompts/agent_overlap_repair_source_directive_20260923.md
- The user authorized Codex to execute this one packet (2026-09-23). sourceOwner=desktop. originEvidenceRoot=null. Route: $demo1-desktop-canonical-goal-intake. Do not select another directive by timestamp.
- Run the packet stages in order. Stage 2 starts only after Stage 1 is green and the Stage 1 lease is released.
- This block is the handoff pointer. An interim report is not the goal. After sourcePatchCompletion=verified for every required stage, delete this block in the same close-out.
<!-- END DEMO1-AGENT-OVERLAP-REPAIR -->
```

If Stage P was skipped, the same block is still required at close-out once the
path is free. Completion without this cleanup leaves the next session
re-running the packet.

## Stage 1 — ownerless expired lease can be quarantined

Primary lane: tooling. Not application Java.

Targets:

- `__patch_drop__/source_edit_lease_contract.ps1`
- `scripts/test_source_lease_lifecycle.py`
- `scripts/agent_preflight.py` (`lease_guidance.expiredUnknown` text only)
- `.agents/skills/scoped-blocker-recovery/references/lease-lifecycle.md` (one paragraph)

Constant: `ownerlessExpiredGraceMinutes = 360` after `expiresAtUtc`.

`begin`'s existing automatic inspection and `-Action recover` quarantine
(receipt, then move into `__patch_drop__/source-edit-quarantine/<id>/lease`)
when all of these hold:

- same host, not `remote-owner-evidence-needed`
- `ownerProcessId` is 0 or absent
- heartbeat absent, or older than `expiresAtUtc`
- now is at or after expiry plus 360 minutes
- reason string `expired-ownerless-grace`

Leave these in place:

- a live owner
- a proven dead pid (today's immediate recover; do not add the 360 minutes to it)
- a remote owner, even if expiry is years ago
- an expired ownerless lease still inside the 360 minutes, so the owner can renew
- `expiredLeaseCleanupDeletedCount` stays 0. Never delete the directory.

Tests, in a temporary repo only:

- Change `test_expired_unknown_owner_still_blocks_only_its_targets` so expiry
  is about 5 minutes ago. Overlap still exits 7 and the lock file remains.
  A disjoint path still begins. The year-2000 stamp would now be past the
  grace and must not stay as the control.
- Add a test: pid 0, no heartbeat, expiry older than the grace. The next
  begin or recover moves that lock, the receipt reason is
  `expired-ownerless-grace`, a peer begin on that target exits 0, and the
  quarantine directory keeps the original bytes.
- Keep the live-owner test and the remote-expired test green.

Command:

```text
python -B -m unittest discover -s scripts -p test_source_lease_lifecycle.py
```

Exit 0 is Stage 1 GREEN. Then run `-Action recover` once on this checkout.
Expect `madwain-live-verify.lock` to move if it still matches the rule.
Same for `madwain-live-verify-r1.lock` and `madwain-live-verify-asr.lock`
only when each one matches. Record receipt paths. A lock that does not match
stays, and the report names it. Do not recover `meta-display-db-free-view`
while it is inside its own expiry.

Release the Stage 1 lease before Stage 2.

## Stage 2 — apply the prepared exact-model patch

Start only after a fresh scoped status shows `targetConflict.allowed=true`
for both Java files below. Resume task
`chat-repair-dynamic-ui-0868f566`. Notes for this stage go on that task.
Do not open another journal that lists `chat.js`.

Prepared patch, apply as an apply_patch, not as `git apply`:

```text
data/agent-handoff/codex-autonomy/chat-repair-dynamic-ui-0868f566/pending-exact-model.patch
```

Re-read the three existing files first. If the patch context does not match
current bytes, stop that hunk, record `미기록 외부 변경` or context-miss, and
do not regenerate a wider patch.

The patch already does the required behavior:

- `chat.js` send payload sets `strictModelSelection: true` beside the selected
  model. Send already returns until the catalog is ready. Do not set the Java
  DTO default to true. Callers that omit the flag keep alias and failover.
- `DynamicChatModelFactory`: an exact selection keeps the raw id, uses the
  primary local base URL, and does not wrap `routeLocalInference`.
- `LlmRouterAspect`: alias rewrite is skipped for an exact id. An exact
  `llmrouter.<key>` uses that enabled route or throws
  `ModelSelectionException`. It does not evaluate the backup route. A local
  outage or ineligible gateway on an exact id throws instead of falling over.
  One upstream attempt, then failure.

Conditional, only if a new RED test shows it: when exact selection is active,
`verifyResponseModelIfRequired` must not treat `approved_alias` as success.
A response model that differs from the requested id is `mismatch`. If that
case already fails closed, do not edit the verifier.

Do not change alias-map contents, the model lock, or banned tags.

RED/GREEN:

```text
.\gradlew.bat test --tests ai.abandonware.nova.orch.aop.ExactModelGatewayTest --tests com.example.lms.service.routing.ExactRequestedModelTest
```

`ExactRequestedModelTest` is the control that non-exact routing is unchanged
in the router. The new gateway test is the proof that a manual route does not
call the backup.

If an existing Node contract asserts the send-payload keys and fails only
because `strictModelSelection` was added, update that assertion in the same
patch. Do not claim the full legacy stream suite.

Runtime: do not restart the wear server. A dev recycle is required only if
this stage's session already owns the dev runtime and needs live proof.
Unit-test GREEN with runtime not recycled is `sourcePatchCompletion=verified`
for the branch and `desktopFinalProof=unverified` for live generation.
Model generation stays NOT_RUN unless the user asks.

## Stage 3 — shared evidence, disjoint from Stage 2

May start during Stage 1. Do not touch Stage 2 files. `agent_preflight.py`
is also a Stage 1 target: edit the runtime fields only after the Stage 1
lease on that file is released, or put both edits in Stage 1 if you hold
one lease for the whole file.

Targets:

- `.clineignore`
- `scripts/agent_preflight.py`
- `scripts/work_journal.py`
- `scripts/test_agent_guard.py`

`.clineignore`: keep `.secrets/`, `config/secrets/`, and `.env*` ignored.
Before adding negations for `data/device-resources/events/`,
`data/device-resources/registry/`, and `data/device-resources/README.md`,
scan only those trees for credential-like assignments. If any file hits,
do not un-ignore; record the count; leave the existing ignore. If the trees
are clean, add the negations. Display DB evidence stays the export lane and
`scripts/meta_display_db_export.py`. Direct JDBC stays forbidden.

`agent_preflight.py` `collect()` adds `runtimeSurfaces` by reading the two
YAML files as text:

- `publicChat`: profile hint `local`, db class `h2-mem:lmsdb`, page `/chat`
- `metaDisplay`: profile hint `local,meta-display`, db class
  `h2-file:var/meta-display-db/lmsdb`, `directJdbc=false`, evidence directory
  `var/meta-display-db/export`

A `/chat` HTTP 200 is not Display proof. Display ready is not `/chat` proof.

`work_journal.py` handoff packet gains:

- `goalAuthority`: `latest-user-text`
- `interimReportsAreNotGoals`: true

`scripts/test_agent_guard.py` asserts both fields. Do not copy session text
into the packet.

## Completion

`sourcePatchCompletion=verified` only when Stage 1 tests exit 0, recover has
either moved each matching madwain lock or named why it stayed, and Stage 2
Gradle exits 0. Stage 3 is required for the evidence-path half: preflight
JSON shows the two `runtimeSurfaces` db classes as different values, and the
handoff test exits 0.

Then:

- Insert or, if Stage P already inserted it, remove the AGENTS pointer block
  once the packet is verified. Removal is the close-out, so a later session
  does not run the packet again.
- Append one `docs/PROJECT_STATUS.md` row through `scripts/status_doc.py`
  only after that file is not covered by a blocking lease. Use
  `--expect-sha256` from a fresh read.
- Mark live glasses and live model generation `unverified` when they were
  not run.
- Stop. Do not start another feature.

## Report shape

Changed files with hashes. Then four rows: mock or unit, build, live boot,
real model call. Each row is a command plus exit code, or `not_run`.
Remaining blockers name the lease id or the test, not a repository HOLD.
