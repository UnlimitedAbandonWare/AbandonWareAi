# Execution contract

Run the registered prompt pack from the proven Desktop root. Keep the review bounded to 5-9 hours, freeze evidence before query construction, and use the smallest active seam.

## Intake and frozen evidence

1. Verify root, active sourceSet, declared-target ownership (lease/preimage), and absence of a real index/ref operation before reading a mirror or patching.
2. Record one redacted EvidenceSnapshot with current command evidence, source ownership, scenario deck hash, and decisionDependsOnSupabase. Freeze it once and bind every packet to its evidenceSnapshotHash.
3. Build POSITIVE_QUERY, NEGATIVE_QUERY, and NEUTRAL_QUERY only from that snapshot. The negative packet covers each positive scenario ID; the neutral packet receives both orders and performs no evidence acquisition.

Preserve the existing production call tuple unless a distinct RED and new authority prove a runtime change is necessary:

~~~text
auxiliaryCalls=0..3
runtimeEnsembleJudgeCalls=0
finalWrapperCalls=1
finalVerifierCalls=0..1
failoverScope=operational_only
heuristicAutoApproval=false
distributedConsensus=false
leaderReplacement=false
alwaysOnReviewFanout=false
~~~

Operational failover covers connection failures, timeouts, HTTP 429/5xx, empty responses, and explicitly retryable parse failures only. A neutral HOLD, semantic disagreement, confidence, or similarity is not a failover trigger.

## Offline graders

The caller creates a bounded $graderRunRoot and bounded $graderInput before this command pattern. Resolve the run root, input, and output parent; verify the resulting input and output paths remain under the resolved run root. Keep source code and secrets out of both JSON files.

~~~powershell
$graderRunRoot = Join-Path $env:TEMP 'awx-three-way-query'
$graderInput = Join-Path $graderRunRoot 'grader-input.json'
$graderOutput = Join-Path $graderRunRoot 'grader-result.json'
$graderRunRootResolved = (Resolve-Path -LiteralPath $graderRunRoot).Path.TrimEnd('\')
$graderInputResolved = (Resolve-Path -LiteralPath $graderInput).Path
$graderOutputResolved = Join-Path (Resolve-Path -LiteralPath (Split-Path -Parent $graderOutput)).Path (Split-Path -Leaf $graderOutput)
$graderPrefix = $graderRunRootResolved + '\'
if (-not $graderInputResolved.StartsWith($graderPrefix, [StringComparison]::OrdinalIgnoreCase) -or -not $graderOutputResolved.StartsWith($graderPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'grader-path-outside-run-root' }
python scripts\score_three_way_long_tail_query.py --input $graderInput --output $graderOutput
~~~

This is v1 artifact grading. Do not create an unbounded directory, substitute a source path, or include raw prompts, responses, credentials, queries, or provider errors. The grader is offline evidence about its bounded artifact; its artifactVerdict does not establish runtimeLineageVerdict.

For v2 design meta-grading, the caller creates the temporary run root before the command and retains the sealed design contract as read-only input. Resolve the output parent and require it to stay below the caller-created root.

~~~powershell
$designRunRoot = Join-Path $env:TEMP 'awx-three-way-design'
New-Item -ItemType Directory -Force -Path $designRunRoot | Out-Null
$designContract = 'agent-prompts/agents/demo1_three_perspective_chat_postprocess/v2_design_contract.json'
$designResult = Join-Path $designRunRoot 'design-result.json'
$designRunRootResolved = (Resolve-Path -LiteralPath $designRunRoot).Path.TrimEnd('\')
$designResultParent = (Resolve-Path -LiteralPath (Split-Path -Parent $designResult)).Path.TrimEnd('\')
if (-not $designResultParent.StartsWith($designRunRootResolved, [StringComparison]::OrdinalIgnoreCase)) { throw 'design-result-outside-run-root' }
python scripts\score_three_way_long_tail_design.py --input $designContract --output $designResult --run-root $designRunRoot
~~~

When `--output` is present, `--run-root` is required. The output must not alias the input, and an output outside the resolved run root is rejected. Without `--output`, canonical stdout mode remains valid and does not require `--run-root`.

~~~text
outputMayAliasInput=false
outsideRunRootRejected=true
stdoutModeRequiresRunRoot=false
~~~

The v2 result is design evidence only: `designVerdict=APPLY does not auto-promote artifactVerdict, statisticalUpliftVerdict, or runtimeLineageVerdict.` Use fixtureAuthority=sealed, candidateMaySubmitBaseline=false, candidateMaySubmitCaseCount=false, candidateMaySubmitEvidenceRegistry=false, and neutralVerdictDerived=true. The caller may report `statisticalUpliftVerdict=INCONCLUSIVE` without symmetric arm regrading and locked clustered-bootstrap evidence.

## External and SMB lanes

Start a fresh local runtime only when browser-visible proof is needed. Record boot/build identity, asset path or content hash, scenario IDs, visible DOM state, and stream/cancel terminal state.

Treat Supabase as decision-relevant only when the frozen snapshot says decisionDependsOnSupabase=true. Then use project-scoped, read-only proof; otherwise unrelated auth or project-ref absence is not a stop.

Choose an SMB mode only when the task requires an SMB source or runtime boundary. For an explicit source edit on the proven \\desktop-m5nov6k\MacSrc root, MACSRC_SMB_DIRECT requires a declared target set, source lease, preimage verification, focused verification, postimage hashes, and count-only secret scan. Do not infer an SMB restriction from a task with no SMB-dependent decision.

Route every MACSRC_SMB_DIRECT application-source edit through $demo1-macsrc-smb-direct-patch and its repository-owned lease/CAS loop. Do not create an ad hoc or second mutation protocol.

## Patch rule

Require a same-input RED naming the active owner, then apply the smallest patch and GREEN on the same input. For a new tool, reuse the existing gated invoker/controller seam and prove consent, role gate, budget, timeout, redaction, bounded output, and artifact-by-reference.
