# Feature record and decision table

Copy this structure for each feature. Use explicit unknown/NOT_RUN slots rather than dropping fields. Cite only actual locations/hashes; unavailable values are unknown with a reason. A shared header may hold canonical path, commit and date if every feature references it.

| Axis | Judgment and evidence boundary |
| --- | --- |
| logic | NO_IMPLEMENTATION (bounded behavior/equivalence search finds none), STUB_ONLY, PARTIAL, PRESENT, NOT_OBSERVED. None alone proves a running feature. |
| wiring | CALL_UNCONFIRMED, SOURCE_CONNECTED, RUNTIME_CONNECTED; confirmed edges cite file:line or correlated runtime receipt. Record registration/activation conditions. |
| test | PASS / FAIL / NOT_RUN, command, exit, mock/unit/integration scope, checkedAt, receipt and bound source/test hashes. TEST_VERIFIED applies only to the exercised contract. |
| runtime | RUNTIME_VERIFIED / FAIL / NOT_RUN / NOT_OBSERVED, scenario, surface, request/session, served build/hash, receipt and checkedAt. One scenario proves neither all scenarios nor performance. |
| document | UNREVIEWED / EVIDENCE_NEEDED / STALE / RESOLVED / CONFLICT, reason/date. STALE means a specific old claim is contradicted/obsolete. RESOLVED means current wording now matches bounded evidence after reread (or already matched); it does not mean product repair. |
| edit | PROPOSED / APPLIED / NO_CHANGE / BLOCKED, canonical pre/post hashes and own diff. A proposal cannot mark the actual canonical claim RESOLVED. |

## Required record

```yaml
featureId: <one behavior>
canonical:
  path: C:/AbandonWare/demo-1/demo-1/src/UAW.txt
  sha256: <observed actual document hash>
  checkedAt: <date/time; date-only if that is all evidence provides>
  section: <existing section>
  anchor: <existing heading or identifying text>
  lines: <observed line range bound to hash>
sourceSnapshot:
  commit: <actual commit or unknown>
  dirty: <observed; hash relevant working files>
  activeSourceSetEvidence: <settings/build file:line and hashes>
  files: [{path: <repo-relative>, line: <line>, sha256: <actual hash>}]
  equivalenceSearch: {scope: <inspected roots/symbols/behavior>, result: <matches/unknown>}
implementation:
  logic: <judgment>
  wiring: <judgment>
  conditions: <registration/flag and observed value or unknown>
  path: <entry -> caller -> output; mark unknown edges>
  pathEvidence: <file:line per confirmed edge; sourceSnapshot hash refs>
  test: {status: <PASS/FAIL/NOT_RUN>, command: <exact command or NOT_RUN>, exit: <actual/null>, scope: <mock/unit/integration>, receipt: <ref/none>, checkedAt: <time/unknown>, boundHashes: <refs/unknown>}
  runtime: {status: <RUNTIME_VERIFIED/FAIL/NOT_RUN/NOT_OBSERVED>, scenario: <actual/unknown>, surface: <actual/unknown>, requestSession: <redacted correlation/unknown>, servedBuild: <hash/ref/unknown>, receipt: <ref/none>, checkedAt: <time/unknown>}
document:
  status: <judgment of observed canonical claim>
  reason: <discrepancy/alignment and evidence>
  statusDate: <date of judgment>
  before: <bounded old claim>
  proposedAfter: <current behavior plus uncertainty>
  editStatus: <PROPOSED/APPLIED/NO_CHANGE/BLOCKED>
  preHash: <current preimage or unknown>
  postHash: <actual only after apply; otherwise null>
  diffRef: <own feature diff/proposal ref>
  resolvedAt: <date only after current wording aligned; otherwise null>
decision:
  action: <PRESERVE/CONNECT/INTEGRATE/DEFER>
  reason: <evidence; distinguish investigation from authorized mutation>
  nextMinimalAction: <one bounded observation/action and required authority>
  deletionReview: <not requested; or separate evidence-backed proposal>
recovery: {journal: <ref>, checkpoint: <ref>, ownLease: <ref/state>, currentHashCheck: <ref/result>}
```

## Example: test evidence with unknown wiring

A hypothetical cancellation method exists and its isolated mock test passes at recorded hashes. Entry/callers are uninspected. Record `logic=PRESENT`, `wiring=CALL_UNCONFIRMED`, `test=PASS (mock)`, `runtime=NOT_RUN`. The old "production ready" claim is `EVIDENCE_NEEDED`; rewrite stays `PROPOSED`. Choose `PRESERVE` and next action "inspect the existing cancellation entry/call path". Do not add a handler, claim runtime readiness or call it dead.

If canonical/relevant source hash differs, keep the old dated record. Use document.status=CONFLICT for canonical drift; record stale evidence in recovery.currentHashCheck while retaining logic/wiring/test/runtime judgments as historical observations pending revalidation. CONFLICT is not an implementation-axis value. Reread current bytes and refresh proposal/checkpoint. A live lease on a historical copy is preserved; it never makes the copy canonical. Missing runtime stays NOT_RUN/NOT_OBSERVED even when all available tests pass.

