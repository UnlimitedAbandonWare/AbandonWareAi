# RAG Final Verification Release Gate Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task by task. Repository evidence and the guarded `Y:\` source-owner workflow remain authoritative.

**Goal:** Prevent a final verifier result marked unknown, insufficient, rejected, or internally inconsistent from releasing the unchanged draft as the visible chat answer.

**Architecture:** Keep prompt construction on `PromptBuilder.build(PromptContext)` and keep `FinalAnswerPostProcessor` as the single final sanitization boundary. Add one package-private deterministic release decision inside `ChatWorkflow`, immediately after the sole final-verifier call and before evidence appendix packaging. The decision consumes only existing verifier outputs, changes no public API, performs no model/network calls, and emits low-cardinality release telemetry.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Gradle Wrapper, repository-owned SMB lease/CAS guard.

---

## Frozen evidence and three-way verdict

- EvidenceSnapshot SHA-256: `C2DE8C51EF1DFF8A046B95B1357A07F5DD9694340DEF06367043888D6BB0A087` (19 rows).
- Design meta-grader: `APPLY`, score `100.0`, hard gates `0`, secret hits `0`.
- Goal score inputs: evidence `0.90`, causal `0.85`, verification feasibility `0.88`, user value `0.90`, reversibility `0.95`, cost efficiency `0.90`, time fit `0.90`, blast radius `0.15`, ambiguity `0.15`, authority/safety expansion `0.10`; clamped score `82`.

### POSITIVE_QUERY / PositivePacket

- `candidateGoal`: add a deterministic final-verification release gate without changing prompt construction, verifier sampling, or public APIs.
- `validatedAssumptions`: S1 unknown/insufficient verifier paths can return the draft; S2 the main workflow already owns status/outcome/acceptance signals; S3 a private gate plus focused tests is sufficient.
- `reusableAssets`: `finalVerificationStatus`, `finalVerificationOutcomeKnown`, `finalVerificationAcceptedForMemory`, `TraceStore`, existing integrity contract tests.
- `expectedUserValue`: unsupported drafts become an explicit evidence-needed or rejection response instead of appearing verified.
- `minimalVerification`: focused RED/GREEN unit test plus source-order integrity assertions.
- `evidenceIds`: E08-E16.
- `unknowns`: Desktop runtime/provider lineage remains unproved.

### NEGATIVE_QUERY / NegativePacket

- `challengedGoal`: reject any design that edits `FactVerifierService` fail-soft semantics, changes sampling temperatures, bypasses `PromptBuilder`, or changes public records/APIs.
- `falsifiers`: S1 unknown or insufficient still exposes the draft; S2 no-verification and accepted-verification paths change; S3 the source lease/CAS guard, focused tests, or secret scan fails.
- `counterExamples`: direct/fallback chat paths legitimately have no final verification and must remain releasable; accepted corrected content must not be replaced.
- `authorityRisks`: one pre-existing lease directory must be adjudicated by the repository guard; Notebook proof is not Desktop final proof.
- `safetyRisks`: broad mutation of the 12k-line workflow, raw prompt/query telemetry, or a public API expansion.
- `missingEvidence`: live Desktop/provider lineage.
- `smallestDisconfirmingProbe`: repository guard `Prepare` then `Verify` for the exact three-file target set.
- `evidenceIds`: E03-E07, E11-E19.

### NEUTRAL_QUERY / NeutralVerdict

- `verdict`: `APPLY` to the existing source-owner guard.
- `selectedOrRewrittenGoal`: implement only the package-private deterministic release decision and its tests; preserve all existing prompt, verifier, memory, and public API boundaries.
- `goalScore`: `82`.
- `decisiveEvidence`: E03, E05-E06, E10-E16.
- `rejectedClaims`: temperature changes are necessary; memory denial alone prevents visible hallucinations; Notebook tests prove Desktop runtime lineage.
- `orderStable`: `true` for A-B and B-A comparison.
- `nextSingleProof`: guarded `Prepare`/`Verify` succeeds for the declared target set.
- `confidence`: `H` for the source defect and minimal patch; `M` for end-to-end runtime effect until Desktop proof.

## Task 1: Acquire guarded source ownership

**Files:**

- Modify: none.
- Guard targets: `main/java/com/example/lms/service/ChatWorkflow.java`
- Guard targets: `src/test/java/com/example/lms/service/ChatWorkflowFinalVerificationReleaseGateTest.java`
- Guard targets: `src/test/java/com/example/lms/service/postprocess/FinalAnswerPostprocessIntegrityContractTest.java`

- [ ] Run `Prepare` with run ID `rag-final-release-gate-20260731-1255`, owner `codex-notebook-rag-release-gate`, boundary `build.gradle.kts`, watch roots `main/java/com/example/lms/service` and `src/test/java/com/example/lms/service`.
- [ ] Stop with `HOLD` if the guard reports a lease, preimage, patch queue, root, index-lock, or reparse conflict.
- [ ] Run `Verify` immediately before the first source `apply_patch`; require `authorized=true`.

## Task 2: Write the failing release-policy tests

**Files:**

- Create: `src/test/java/com/example/lms/service/ChatWorkflowFinalVerificationReleaseGateTest.java`
- Modify: `src/test/java/com/example/lms/service/postprocess/FinalAnswerPostprocessIntegrityContractTest.java`

- [ ] Test that no-verification responses and known accepted `pass`/`corrected` responses preserve content.
- [ ] Test that `unknown` and `insufficient` responses remove the original draft and return deterministic `evidence_needed` messages.
- [ ] Test that `rejected` removes the draft and returns a deterministic unavailable response.
- [ ] Test that inconsistent positive telemetry fails closed.
- [ ] Add source-contract assertions that the release gate is after `verifyDetailed`, before the appendix and postprocessor, receives all four existing verifier signals, and writes low-cardinality trace keys.
- [ ] Run the focused tests and record the expected RED caused by the missing `applyFinalVerificationReleaseGate` contract.

Command:

```powershell
$env:JAVA_HOME = '<verified temporary Temurin 17 root>'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'notebook-rag-release-gate'
$env:GRADLE_USER_HOME = "$env:LOCALAPPDATA\Codex\gradle-rag-release-gate"
.\gradlew.bat --project-cache-dir "$env:LOCALAPPDATA\Codex\project-cache-rag-release-gate" test --tests com.example.lms.service.ChatWorkflowFinalVerificationReleaseGateTest --tests com.example.lms.service.postprocess.FinalAnswerPostprocessIntegrityContractTest
```

## Task 3: Implement the smallest deterministic release gate

**Files:**

- Modify: `main/java/com/example/lms/service/ChatWorkflow.java`

- [ ] Add a package-private static `FinalVerificationReleaseDecision` record with `content`, `releaseStatus`, `reasonCode`, and `releaseAllowed`.
- [ ] Add a package-private static pure function that normalizes status with `Locale.ROOT` and applies this table:

| Verification state | Visible result | Release status | Allowed |
|---|---|---|---|
| not required | original candidate | `NOT_REQUIRED` | true |
| known + accepted + `pass`/`corrected` | original candidate | `APPROVE` | true |
| unknown outcome | deterministic evidence-needed message | `HOLD` | false |
| known `insufficient` | deterministic evidence-needed message | `HOLD` | false |
| known `rejected` | deterministic unavailable message | `REJECT` | false |
| inconsistent/other required state | deterministic evidence-needed message | `HOLD` | false |

- [ ] Invoke it after the sole final-verifier call and before `appendFinalEvidenceOnce`.
- [ ] Make the released content the memory candidate and visible candidate consumed by the existing postprocessor.
- [ ] Trace only `finalAnswer.releaseStatus`, `finalAnswer.releaseReason`, and `finalAnswer.releaseAllowed`; do not trace raw answer/query/evidence.
- [ ] Do not alter `PromptBuilder`, `FactVerifierService`, verifier temperatures, provider calls, public constructors/records, or memory policy.

## Task 4: Prove GREEN and complete the guarded session

**Files:**

- Create: `data/agent-handoff/macsrc-smb-direct/rag-final-release-gate-20260731-1255/verification.json`

- [ ] Run the two focused tests and require exit code `0`.
- [ ] Run `compileJava` and require exit code `0`.
- [ ] Run `:app:classes` and require exit code `0` if the repository project graph exposes `:app`.
- [ ] Count-only scan the three declared targets for secret patterns; require zero hits.
- [ ] Hash every declared target after GREEN.
- [ ] Write guard verification JSON with the exact successful command, session SHA-256, real exit code, and all target postimages.
- [ ] Run guard `Complete`; require `changedFileCount=3`, `undeclaredSourceChangeCount=0`, `rawSecretPatternHits=0`, and `leaseReleased=true`.
- [ ] Re-run the focused tests after completion and retain `desktopFinalProof=evidence_needed`.

## Rollback

- Restore the two pre-existing files to their recorded preimage hashes and remove the newly created test file only if GREEN or `Complete` fails.
- Re-run the same focused tests against the restored tree.
- Invoke guard `Abort` only after target and watch-root manifests match the session baseline; otherwise retain the lease and report `rollback-required`.

