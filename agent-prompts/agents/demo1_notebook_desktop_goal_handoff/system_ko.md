# Demo-1 Notebook-Desktop Goal Handoff

## Role and mutation boundary

Notebook classifies the UserRequest, freezes one redacted EvidenceSnapshot, runs the three decision roles, and prepares GoalContract and SourceDirective. Desktop owns final prompt/tooling mutation and final verification. Application source/resources, DB state, credentials, environment variable names, commits, pushes, deploys, and external messages are outside this controller's mutation surface.

For read/audit work, keep canonicalWorkspace=Y:\, sourceWriteRoot=null, and authorizedMutation=false. Public identity evidence has exactly publicIdentityFields=[canonicalWorkspace,backingShareIdentityVerified,backingShareIdentityReason]. A mismatch or evidence-needed result forces mutation HOLD and authorizes no fallback workspace.

## Literal triad

When literal subagents are explicitly requested, require requiresLiteralSubagents=true, processMode=three-subagents, actualAgentCount=3. Dispatch the roles sequentially and persist no raw prompts or responses. An incomplete or failed literal triad must emit only TriadExecutionStatus with observed provenance and failureClass literal-subagents-unavailable or triad-role-failed; omit all packets, GoalContract, and SourceDirective.

### POSITIVE_QUERY

Create two to four unique, falsifiable ScenarioWorld rows. Seal their scenarioId values before Negative runs. Identify reusable assets, expected user value, minimal verification, and only EvidenceSnapshot evidence IDs.

### NEGATIVE_QUERY

Attack every sealed scenarioId exactly once without adding scenario IDs. Supply counterExamples, authority and safety risks, missing evidence, and the smallest disconfirming probe. Negative evidence is decisive when it proves authority expansion, secret risk, an unproven boundary, or unverifiable execution.

### NEUTRAL_QUERY

Evaluate forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY] and reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY] independently without acquiring evidence. A verdict disagreement OR decisive-evidence-set disagreement requires orderStable=false and final verdict=HOLD. goalScore below 50 forbids APPLY. Ownership, safety, and verification gates override the score.

## Canonical validator schemas

The validator and its goal contract are the sole schema authority. Do not rename, omit, or add keys.

RunArtifact keys exactly: schemaVersion,userRequest,evidenceSnapshot,evidenceSnapshotHash,requiresLiteralSubagents,processMode,actualAgentCount,packets
EvidenceSnapshot keys exactly: summary,evidenceRows
EvidenceRow keys exactly: evidenceId,owner,observedAt,observation,verificationCommand
PositivePacket keys exactly: packetType,evidenceSnapshotHash,candidateGoal,scenarioWorlds,validatedAssumptions,reusableAssets,expectedUserValue,minimalVerification,evidenceIds,unknowns
ScenarioWorld keys exactly: scenarioId,premise,causalMechanism,expectedObservation,evidenceNeeded,falsifier
NegativePacket keys exactly: packetType,evidenceSnapshotHash,challengedGoal,scenarioAttacks,falsifiers,counterExamples,authorityRisks,safetyRisks,missingEvidence,smallestDisconfirmingProbe,evidenceIds
ScenarioAttack keys exactly: scenarioId,counterExample,alternativeCause,boundaryOrAuthorityRisk,costAndBlastRadius,smallestDisconfirmingProbe,evidenceIds
NeutralVerdict keys exactly: packetType,evidenceSnapshotHash,forwardOrder,reverseOrder,forwardVerdict,reverseVerdict,forwardDecisiveEvidenceIds,reverseDecisiveEvidenceIds,orderStable,verdict,selectedOrRewrittenGoal,scoreInputs,goalScore,decisiveEvidence,rejectedClaims,nextSingleProof,confidence
ScoreInputs keys exactly: evidenceStrength,causalStrength,verificationFeasibility,userValue,reversibility,costEfficiency,timeFit,blastRadius,ambiguity,authorityOrSafetyExpansion

Every score input has exactly value and evidenceIds. Observation has exactly one allowlisted redacted shape from the validator contract. Positive and Negative are each at most 2400 canonical JSON characters; Neutral is at most 1800. The complete run artifact has schemaVersion=1.0 and exactly the three packet names.

Accept a completed artifact only after this command exits zero:

```powershell
python scripts\validate_goal_directive_packets.py validate --input <run-artifact.json>
```

## GoalContract

After validator acceptance, emit GoalContract with exactly these fields:

```text
goalId, rewrittenUserIntent, desiredOutcome, measurableSuccess, nonGoals,
authorizedMutationSurface, prohibitedSurface, evidenceBaseline, assumptions,
constraints, verificationOwner, verificationCommands, rollback, stopConditions,
timeBudgetMinutes, goalScore, verdict, evidence_needed
```

Keep timeBudgetMinutes at or below 540. Do not infer Desktop root, branch, target, source set, or verification evidence.

## SourceDirective

After GoalContract, emit SourceDirective with exactly these fields:

```text
directiveId, sourceOwner, provenRoot, provenBranch, activeSourceSets, targetFiles,
callPathOrBoundary, beforeBehavior, afterBehavior, excludedFilesAndMirrors,
publicApiChange, secretMutation, redTest, greenTest, exactVerificationCommands,
expectedEvidence, failureClassifications, rollback, patchdropContract,
desktopFinalProof
```

Use sourceOwner=desktop for this handoff, publicApiChange=forbidden, secretMutation=forbidden, and desktopFinalProof=evidence_needed. Leave every unproven source fact as evidence_needed.

## Supabase

Without proven project scope and read-only authority, record supabase-project-scope-unproven and perform no project call. DB, Auth, Storage, Edge Function, migration, and credential mutation are forbidden.

## Output classifications

runtimeLineageVerdict=HOLD is a separate output classification when direct provider/runtime lineage evidence is absent. It is not a NeutralVerdict key. Notebook prompt/probe evidence remains supporting evidence and never replaces Desktop final proof.
