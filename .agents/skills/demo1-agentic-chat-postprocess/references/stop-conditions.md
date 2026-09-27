# Stop conditions and failure taxonomy

Stop source edits and issue the structured report when any condition below is true. Do not retry a blocker solely to obtain a more favorable result.

| Condition | Primary failure class | Required action |
|---|---|---|
| A real Git index/ref operation or live git writer is in progress | index-lock-conflict | Hold the affected index-dependent or unscoped edit; scoped declared-target edits may still proceed under the source-owner guard. |
| Dirty target cannot be safely isolated | worktree-overlap | Preserve ownership; request a clean seam. |
| Active sourceSet or authoritative owner is unproven | wrong-sourceset | Request exact source/build proof. |
| Same-input check changes failure type after a patch | changed-failure-class | Stop and report the new blocker. |
| Scenario deck finds no reproducible defect | no-reproducible-defect | Make no speculative patch. |
| Repeated external probe has no new evidence | repeated-external-blocker | Retain evidence_needed; stop probing. |
| The offline grader cannot read or write its bounded input/output | grader-path-outside-run-root, malformed-json, output-nonfinite, or output-size-exceeded | Stop grading; retain the redacted reason code. |
| The grader returns any artifact hard gate | one or more grader reason codes below | Set artifactVerdict=HOLD; do not use a score as runtime proof. |
| The sealed v2 design contract fails its meta-grader or permits candidate-owned baseline, caseCount, or registry | candidate-owned-baseline-or-count, candidate-owned-evidence-registry, or design-meta-gate-failed | Set designVerdict=HOLD; retain the smallest contract proof. |
| Q9 same-family fluent paraphrase reuses a sealed semantic-family ID | claim-semantic-family-duplicate | Set HOLD and emit this canonical reason code exactly. |
| A locked safety-critical regression is observed even with mean uplift | safety-regression-observed | Set statisticalUpliftVerdict=REJECT; do not compensate with an aggregate score. |
| The runtime lineage field is absent | runtime-lineage-missing | Set runtimeLineageVerdict=HOLD. |
| decisionDependsOnSupabase=true but scoped read-only proof is absent or unsafe | supabase-project-ref-missing, supabase-raw-scope-prohibited, or supabase-auth-missing | Keep read-only; HOLD only the dependent decision. |
| SMB affects the task and the root/guard is unsafe | smb-root-unproven, smb-root-identity-changed, source-lease-conflict, changed-preimage, reparse-traversal-risk, or undeclared-source-write | Do not enter MACSRC_SMB_DIRECT; request the smallest missing guard proof. |

The offline grader artifact hard-gate reason codes are:

~~~text
input-size-exceeded
schema-version-invalid
rubric-version-invalid
fixture-deck-hash-invalid
packet-set-invalid
packet-type-invalid
evidence-snapshot-invalid
snapshot-hash-mismatch
positive-worlds-invalid
none-or-unknown-invalid
world-falsifier-missing
scenario-id-invalid
scenario-id-duplicate
attack-scenario-id-invalid
attack-scenario-id-duplicate
scenario-coverage-mismatch
neutral-evidence-invalid
neutral-evidence-unresolved
order-instability
claims-invalid
claim-count-exceeded
claim-evidence-unresolved
secret-like-content
supabase-project-ref-missing
supabase-raw-scope-prohibited
supabase-auth-missing
~~~

Missing Supabase auth or SMB evidence is not a global stop when its corresponding condition is absent from the frozen decision. If a defect is reproducible but needs broader architecture work, return HOLD with the smallest next proof rather than widening the patch.

The separated v2 verdict contract is designVerdict=APPLY|HOLD|REJECT, artifactVerdict=APPLY|HOLD|REJECT, statisticalUpliftVerdict=INCONCLUSIVE|NO_UPLIFT|UPLIFT_CANDIDATE|REJECT, and runtimeLineageVerdict=APPLY|HOLD|REJECT. A passing design meta-grade authorizes only later v2 runtime-grader implementation work; it does not establish live uplift or provider/runtime lineage.
