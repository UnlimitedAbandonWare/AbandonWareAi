# AutoGrade B R3 — Devin assist notes (no product diff)

Contract: `DEMO1-DEVIN-AUTOGRADE-B-R3-ASSIST-20260928` tasks C/D/E
Agent: devin · journal: `autograde-b-r3-assist-0928-83a88157` · product diff: 0

## C — Codex R3 status (read-only observation)

Journal `autograde-b-r3-0928-6251af7f` is **closed** (~2026-09-28T12:01Z) and
reports:

- T-STRUCT `strictSingleAttempt` OR-assignment structural assertion repaired → 18 GREEN
- M5 `memoryMode` session-meta merge → 66 focused tests GREEN (8 seam suites)
- Smoke: `ChatApiAgentPromptEvidenceTest` 13 + `chat-search-outcome.test.cjs` 5
- Fresh runtime JVM PID 10188, Verify-RAG exit 0; platform stays `partial`

Attribution detail (사실): `verify-m5-green` (109 tests) failed on
`ChatWorkflowFinalVerificationReleaseGateTest` ×5, which auto-rolled back
cycle-03; `verify-release-preimage` reproduced **the same 5 failures on the
pre-patch preimage** — baseline failures, not regressions:

- `evidenceZeroWithoutDirectivePublishesDraftButSkipsDurableMemory`
- `evidenceReleaseHoldWithFullMemoryNeverInvokesDurableWriters`
- mode=enforce — expected `memory_policy_denied`, got `verification_outcome_unknown`
- mode=shadow — expected `none`, got `verification_outcome_unknown`
- `mismatchedWorkflowOwnerProducesNoPromptLocalDocuments` — AttachmentOwnerIdentity hash-arg mismatch

R3 re-ran the seam subset (GREEN 66) and closed with the 5 recorded as
platform-partial baseline. Verdict separation holds: seam pass ≠ suite pass ≠
full verification; those 5 need their own ticket.

Post-close note: `ChatSessionMetaMerger.java` changed again ~12:15 UTC — line
52 now calls `uiReq.isSearchModeExplicit()` and the file hash differs from the
R3 postimage. This is the M9 seam landing under a **different** journal
(likely `max-push-b-perf-0928-*`, scope `main/java`); attribution unconfirmed —
do not count it as R3 evidence.

### Guidance drafts (for user paste)

1. If a session re-opens R3 items:
   "M5 GREEN is the 66-test seam subset; the 5
   `ChatWorkflowFinalVerificationReleaseGateTest` failures reproduce on the
   recorded preimage — file as baseline ticket, don't re-litigate."
2. If M9 verification is claimed GREEN:
   "The probe (`RagMemSteerDynamicToggleProbeTest`) records *observed* behavior —
   GREEN only counts if the m9/m1 flip assertions were updated to expect the
   fixed semantics; a pass without flipped assertions proves nothing."
3. Standing re-reminders: no full-suite Done claims; no global HOLD/RuleBreak;
   no admin-hardening; no commit/push; on cleanup-io-failure verify
   `deletedCount=0` only — never force deletion.

## D — M1 / M3 residual (read-only)

Measured matrix: `docs/diagnostics/rag-mem-steer-20260928.md` (17 probe tests
recording observed behavior; product diff 0).

- **M1** (RAG ON→OFF next turn): already measured **PASS** (`m1_*` probes;
  `useRag=false` clamps `isUseRag()||isUseVectorStore()`). Covered — no action.
- **M3** (web.search OFF but agent searches): mechanism is the **M9 defect** —
  a turn omitting `searchMode` overwrote stored OFF/FORCE_* with AUTO, so later
  turns searched again. Root cause: `ChatRequestDto.getSearchMode()` effective
  default AUTO made the merger treat omission as explicit and write meta every
  turn, leaving the restore branch unreachable.
  - Fix status: live tree now carries `isSearchModeExplicit()`
    (`ChatRequestDto.java:233`) + merger switch
    (`ChatSessionMetaMerger.java:52`) — **verification pending**; probe
    assertions must flip before it counts.
  - Client side: `chat.js:1324` still sends `searchMode: ... || "AUTO"` —
    phantom-AUTO only when the select has no value; harmless once omission is
    honored separately.
- Verdict: **NOT_RUN** for new repro — existing matrix evidence + in-tree M9
  seam already covers it; no Devin product patch prepared (seam owned by Codex
  lane).

## E — goal / attachment hygiene

- R3 journal shows real plan→change→verify→report work — not a stale
  `goal-objective.md` stall; no `goal-objective*` files found in the root tree.
- User guidance (one line): keep pasting the **current CONTINUE body** as the
  goal; older reports/briefs go as reference attachments only — never the goal.
