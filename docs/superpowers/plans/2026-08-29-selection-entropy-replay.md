# SelectionEntropy Runtime Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve normal live exploration while making application-owned agent selection reproducible, order-stable, private, observable, and Browser/Computer-verifiable for an explicitly authorized request-scoped replay.

**Architecture:** A servlet-independent `com.example.lms.infra.selection` package derives coordinate-keyed entropy and records a bounded hash-only decision ledger. `GuardContext` attaches the entropy and ledger once, existing `ContextPropagation` transports the same references, chat ingress authorizes and parses the ephemeral replay header before work starts, and each stochastic consumer canonicalizes candidates before drawing. Exact allowlists project final state into TraceStore, snapshots, a nullable sync DTO field, typed SSE, and a read-only chat card; standard mode remains the default and provider output or completion order is never described as deterministic.

**Tech Stack:** Java 17, Spring Boot and Spring MVC reactive return types already present in the repository, JDK `HmacSHA256`/`SHA-256`, Reactor, Lombok, JUnit 5, AssertJ, Mockito, Gradle Kotlin DSL, vanilla JavaScript, PowerShell, in-app Browser, and Computer Use. No production dependency is added.

**Spec:** `docs/superpowers/specs/2026-08-29-selection-entropy-replay-design.md`

## Global Constraints

- Work only in the verified Desktop active source roots: root `main/java`, `main/resources`, `src/test/java`, and `src/chatUiTest/java`; `app/src/main/java_clean` remains a separate owner unless Gradle proves it is affected.
- Verify Java 17 before any application-source write.
- Keep every `dev.langchain4j:*` artifact exactly at `1.0.1`; do not change Spring Boot, provider, database, credential, ACL, deployment, or plugin configuration.
- Keep final RAG prompt construction on `PromptBuilder.build(PromptContext)`; this feature must not add prompt concatenation or replay material to a prompt.
- The neutral package is exactly `main/java/com/example/lms/infra/selection/` and must not depend on servlet APIs, `ChatApiController`, providers, `TraceStore`, or UI code.
- The public entropy interface remains exactly `mode()`, `algorithmVersion()`, `unitInterval(SelectionCoordinate)`, and `boundedIndex(SelectionCoordinate,int)`; do not expose `Random`, `Mac`, raw seed bytes, a mutable draw counter, or a candidate list.
- Replay algorithm label is exactly `selection-entropy-v1`; seed size is 16 through 64 bytes; `boundedIndex` accepts 1 through 1,000,000.
- The only HTTP replay input is one `X-AWX-Selection-Replay` header whose value is `v1:` immediately followed by Base64URL text without padding, with a total maximum of 96 characters. No query, cookie, request DTO, environment/property, database, local-storage, or session-storage input is added.
- Detect header presence, authorize with `AdminTokenGuardInterceptor.isPresentedHeaderTokenAuthorized(HttpServletRequest)`, and parse only after authorization. Header absence must not call that authorization seam.
- Accepted replay never degrades to live entropy. Deterministic fail-soft rules may continue; entropy/context/coordinate/stable-key/derivation failures do not trigger an extra provider call.
- Stable exact ties consume no entropy. Canonical ordering precedes every weighted or indexed draw.
- Preserve `DiverseSamplingOrchestrator` first-observed terminal/cancellation behavior; report `completionOrderDeterministic=false` separately from selection coherence.
- Public evidence is limited to fixed enums, booleans, non-negative counts capped at 10,000, a 12-hex fingerprint, a 64-hex decision digest, and closed-set reason codes. Never retain raw seeds, replay headers, tokens, prompts, responses, candidate bodies/IDs, provider errors, or environment values.
- `ChatRequestDto` remains unchanged. Every existing `ChatResponseDto` constructor remains source-compatible; the only additive sync field is nullable, typed, narrowly `NON_NULL`-serialized, and populated only from the safe final projection.
- No replay input, toggle, cookie, local-storage key, or session-storage key is added to chat UI.
- Browser proves fresh-runtime DOM, stream handling, cancellation, reload, and geometry. Computer proves Windows-visible layout only. Neither proves authorization, HMAC derivation, provider generation, wire attempts, or deterministic prose.
- Plugin Management remains installed/enabled as already verified; do not install another plugin or alter permissions/dependencies.
- Preserve every unrelated dirty-tree hunk. Re-read and hash each target immediately before patching; hold only the conflicting lane on owner, lease, or preimage change.
- The nine-hour duration is a maximum. Stop early on complete acceptance evidence or a decisive lane-local/global gate; do not repeat an unchanged external probe.
- No commit is currently authorized. Every commit checkbox below is conditional: stage only the exact task paths if the user later grants commit authority; otherwise record `commit=not_authorized` and leave the verified changes unstaged.

## Execution Process Contract

Every displayed PowerShell fragment is an independent process unless a task explicitly says otherwise. Before each fragment that uses Gradle, `$pcd`, or `$ownerId`, rehydrate this exact non-secret state in that process:

```powershell
Set-Location 'C:\AbandonWare\demo-1\demo-1\src'
$env:AWX_AGENT_HOST = 'desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'selection-entropy'
$env:GRADLE_USER_HOME = 'C:\AbandonWare\gradle-user-home\selection-entropy'
$pcd = 'C:\AbandonWare\gradle-cache\selection-entropy'
$ownerId = 'codex-selection-entropy-01a04ac5'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $pcd | Out-Null
```

Before any later fragment references `$targets`, rerun the exact declaration in Task 0 Step 2 in that same process. Do not assume a prior `exec_command` preserved variables or environment values.

Task 13 Steps 1-10 are the sole exception: start one task-owned interactive `powershell -NoLogo -NoProfile -NoExit` PTY through `exec_command`, feed the shown fragments to that same session with `write_stdin`, interleave Browser/Computer work while it stays open, and execute the cleanup fragment in that same session before exiting. This keeps the ephemeral replay seed/header and owner token process-local without writing them to disk. If that session is lost, stop its task-owned runtime by the recorded PID, release the stable owner ID above from a fresh process, discard the run, and start Task 13 again with a new seed; never reconstruct or persist the lost secret values.

## Locked File Map

| Responsibility | Create | Modify |
|---|---|---|
| Value types and failures | `main/java/com/example/lms/infra/selection/SelectionEntropy.java`, `SelectionCoordinate.java`, `SelectionEntropyMode.java`, `SelectionEntropyCoherence.java`, `SelectionEntropyReason.java`, `SelectionEntropyException.java` | none |
| Live/replay derivation and construction | `LiveSelectionEntropy.java`, `ReplaySelectionEntropy.java`, `SelectionReplaySpec.java`, `SelectionEntropyFactory.java` in the neutral package | none |
| Ledger and safe terminal projection | `SelectionDecisionLedger.java`, `SelectionEntropyProjection.java` in the neutral package | none |
| Request context | none | `main/java/com/example/lms/service/guard/GuardContext.java`; test-only coverage of existing `main/java/com/example/lms/infra/exec/ContextPropagation.java` |
| HTTP replay boundary | `main/java/com/example/lms/api/SelectionReplayRequestResolver.java`, `SelectionReplayRequestException.java`, `SelectionReplayExceptionHandler.java` | `main/java/com/example/lms/api/ChatApiController.java` |
| Router | none | `main/java/ai/abandonware/nova/orch/router/LlmRouterBandit.java`, `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java` |
| Strategy | none | `main/java/com/example/lms/strategy/StrategySelectorService.java` |
| Ensemble | none | `main/java/com/example/lms/ensemble/StochasticParamSampler.java`, `DiverseSamplingOrchestrator.java` |
| Ranking | none | `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`, `main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java` |
| Trace/DTO/SSE | `main/java/com/example/lms/trace/SelectionEntropyTraceSupport.java` | `main/java/com/example/lms/trace/TraceSnapshotStore.java`, `main/java/com/example/lms/service/AgentVisibleDebugEvidenceBuilder.java`, `main/java/com/example/lms/dto/ChatResponseDto.java`, `main/java/com/example/lms/dto/ChatStreamEvent.java`, `main/java/com/example/lms/api/ChatStreamSignalBuilder.java`, `main/java/com/example/lms/api/ChatApiController.java` |
| UI | `src/chatUiTest/java/com/example/lms/web/ChatFrontendSelectionEntropyFocusedTest.java` | `main/resources/static/js/chat.js` |

Files intentionally left unchanged unless fresh compile evidence proves otherwise: `LlmRouterProperties.java`, `RetrievalOrderService.java`, `TraceStore.java`, `AdminTokenGuardInterceptor.java`, `AdminTokenGuardWebMvcConfig.java`, all chat request DTOs, all provider adapters, `PromptBuilder.java`, Gradle dependencies, and application configuration.

---

### Task 0: Freeze Source Evidence and Acquire the Existing Edit Lease

**Files:**
- Read: `AGENTS.md`, `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md`, `.agents/skills/demo1-source-edit-three-way-preflight/references/preflight-contract.md`
- Read: `.agents/skills/demo1-subsystem-patch-directive/SKILL.md`, section `S03 - MoE Strategy Selector` and integration sections of its reference
- Read: `.agents/skills/demo1-cross-subsystem-guard/SKILL.md` and its reference
- Read: `__patch_drop__/source_edit_session.ps1`, `__patch_drop__/janitor_inventory.ps1`
- Evidence only: current Git/Gradle/lease/PatchDrop state; no application-source write in this task

**Interfaces:**
- Consumes: the approved spec and the repository’s current branch, HEAD, source-set, owner, lock, and target preimages.
- Produces: one redacted `EvidenceSnapshot`, exactly three logical packets, stable `APPLY`, declared target list, target hashes, and an active `desktop` source-edit lease for topic `selection-entropy-replay`.

- [ ] **Step 1: Verify Java, active projects, and immutable dependency/source-set gates**

Run from `C:\AbandonWare\demo-1\demo-1\src`:

```powershell
& java -version 2>&1
$env:AWX_AGENT_HOST = 'desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'selection-entropy'
$env:GRADLE_USER_HOME = 'C:\AbandonWare\gradle-user-home\selection-entropy'
$pcd = 'C:\AbandonWare\gradle-cache\selection-entropy'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $pcd | Out-Null
.\gradlew.bat projects checkLangchain4jVersionPurity checkSourceSetHygiene `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: Java reports major version 17; Gradle identifies the root project and `:app`; LangChain4j purity and source-set hygiene pass. A failure is `HOLD` only for source lanes whose ownership or meaningful verification cannot be established.

Use the explicitly requested `$plugin-management:plugin-management` skill in read-only mode to re-check `plugin-management@openai-curated-remote`: record installed/default-enabled/user-enabled booleans and unresolved-app count only. Expected: installed and enabled, unresolved-app count zero, and no install/permission/dependency mutation.

- [ ] **Step 2: Capture branch, HEAD, worktrees, dirty targets, index lock, PatchDrop, lease, and ports**

```powershell
$targets = @(
  'main/java/com/example/lms/infra/selection/SelectionEntropy.java',
  'main/java/com/example/lms/infra/selection/SelectionCoordinate.java',
  'main/java/com/example/lms/infra/selection/SelectionEntropyMode.java',
  'main/java/com/example/lms/infra/selection/SelectionEntropyCoherence.java',
  'main/java/com/example/lms/infra/selection/SelectionEntropyReason.java',
  'main/java/com/example/lms/infra/selection/SelectionEntropyException.java',
  'main/java/com/example/lms/infra/selection/LiveSelectionEntropy.java',
  'main/java/com/example/lms/infra/selection/ReplaySelectionEntropy.java',
  'main/java/com/example/lms/infra/selection/SelectionReplaySpec.java',
  'main/java/com/example/lms/infra/selection/SelectionEntropyFactory.java',
  'main/java/com/example/lms/infra/selection/SelectionDecisionLedger.java',
  'main/java/com/example/lms/infra/selection/SelectionEntropyProjection.java',
  'main/java/com/example/lms/service/guard/GuardContext.java',
  'main/java/com/example/lms/api/SelectionReplayRequestResolver.java',
  'main/java/com/example/lms/api/SelectionReplayRequestException.java',
  'main/java/com/example/lms/api/SelectionReplayExceptionHandler.java',
  'main/java/com/example/lms/api/ChatApiController.java',
  'main/java/com/example/lms/api/ChatStreamSignalBuilder.java',
  'main/java/com/example/lms/dto/ChatResponseDto.java',
  'main/java/com/example/lms/dto/ChatStreamEvent.java',
  'main/java/ai/abandonware/nova/orch/router/LlmRouterBandit.java',
  'main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java',
  'main/java/com/example/lms/strategy/StrategySelectorService.java',
  'main/java/com/example/lms/ensemble/StochasticParamSampler.java',
  'main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java',
  'main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java',
  'main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java',
  'main/java/com/example/lms/trace/SelectionEntropyTraceSupport.java',
  'main/java/com/example/lms/trace/TraceSnapshotStore.java',
  'main/java/com/example/lms/service/AgentVisibleDebugEvidenceBuilder.java',
  'main/resources/static/js/chat.js',
  'src/test/java/com/example/lms/infra/selection/SelectionCoordinateTest.java',
  'src/test/java/com/example/lms/infra/selection/ReplaySelectionEntropyTest.java',
  'src/test/java/com/example/lms/infra/selection/LiveSelectionEntropyTest.java',
  'src/test/java/com/example/lms/infra/selection/SelectionDecisionLedgerTest.java',
  'src/test/java/com/example/lms/infra/selection/SelectionEntropyProjectionTest.java',
  'src/test/java/com/example/lms/service/guard/GuardContextSelectionEntropyTest.java',
  'src/test/java/com/example/lms/infra/exec/ContextPropagationTimeBudgetTest.java',
  'src/test/java/com/example/lms/api/SelectionReplayRequestResolverTest.java',
  'src/test/java/com/example/lms/api/ChatApiControllerSelectionReplayTest.java',
  'src/test/java/ai/abandonware/nova/orch/router/LlmRouterBanditTraceTest.java',
  'src/test/java/ai/abandonware/nova/orch/aop/LlmRouterRequestTimelineTest.java',
  'src/test/java/com/example/lms/strategy/StrategySelectorServiceTest.java',
  'src/test/java/com/example/lms/strategy/RetrievalOrderServiceTest.java',
  'src/test/java/com/example/lms/ensemble/StochasticParamSamplerTest.java',
  'src/test/java/com/example/lms/ensemble/DiverseSamplingOrchestratorTest.java',
  'src/test/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestratorRagEvalTest.java',
  'src/test/java/com/example/lms/service/rag/rerank/DppDiversityRerankerTest.java',
  'src/test/java/com/example/lms/trace/TraceSnapshotSelectionEntropyTest.java',
  'src/test/java/com/example/lms/service/AgentVisibleDebugEvidenceBuilderTest.java',
  'src/test/java/com/example/lms/dto/ChatResponseDtoLearningContextTest.java',
  'src/test/java/com/example/lms/dto/ChatEvidenceMetadataDtoTest.java',
  'src/test/java/com/example/lms/api/ChatStreamSignalBuilderTest.java',
  'src/test/java/com/example/lms/api/ChatApiControllerTraceMetaTest.java',
  'src/chatUiTest/java/com/example/lms/web/ChatFrontendSelectionEntropyFocusedTest.java'
)
git branch --show-current
git rev-parse HEAD
git worktree list --porcelain
git status --short -- $targets
$indexLock = git rev-parse --git-path index.lock
Write-Output ("indexLockPresent={0}" -f (Test-Path -LiteralPath $indexLock).ToString().ToLowerInvariant())
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
  -Action status -Role desktop -Root . -Topic selection-entropy-replay
Get-NetTCPConnection -State Listen -LocalPort 8080,8081,18182,18183,18184,18185,18186,18187,18188,18189,18190,18191,18192 `
  -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,OwningProcess
```

Expected: no index lock, no overlapping active source owner, and no ambiguous active top-level PatchDrop patch. Existing dirty paths are evidence to preserve, not permission to reset or stage them.

- [ ] **Step 3: Freeze exact preimage hashes without printing source or secrets**

```powershell
$preimages = foreach ($path in $targets) {
  if (Test-Path -LiteralPath $path -PathType Leaf) {
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
    [pscustomobject]@{ path = $path; tracked = [bool](git ls-files --error-unmatch -- $path 2>$null); sha256 = $hash }
  } elseif (Test-Path -LiteralPath $path -PathType Container) {
    [pscustomobject]@{ path = $path; tracked = $false; sha256 = 'directory-not-yet-created' }
  } else {
    [pscustomobject]@{ path = $path; tracked = $false; sha256 = 'absent' }
  }
}
$snapshotJson = $preimages | ConvertTo-Json -Depth 3 -Compress
$snapshotHash = [Convert]::ToHexString(
  [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($snapshotJson))
).ToLowerInvariant()
Write-Output ("evidenceSnapshotHash={0}" -f $snapshotHash)
Write-Output ("evidenceRowCount={0}" -f @($preimages).Count)
```

Expected: exactly 55 redacted path/hash rows and one stable snapshot hash. Do not print file contents, environment values, tokens, replay material, prompts, or responses.

- [ ] **Step 4: Run exactly the repository’s three logical queries over that frozen snapshot**

Load `$demo1-source-edit-three-way-preflight` and produce exactly these packet types with the same `evidenceSnapshotHash`:

```text
POSITIVE_QUERY: 2..4 falsifiable scenarioWorlds, evidence IDs, minimal verification
NEGATIVE_QUERY: the exact same scenario IDs, no new evidence, counterexamples and smallest disconfirming probes
NEUTRAL_QUERY: evaluate Positive-Negative and Negative-Positive, no new evidence,
               identical decisive-evidence sets, goalScore >= 50, verdict APPLY|HOLD|REJECT
```

Use `candidateGoal=Implement approved request-scoped SelectionEntropy replay across all required agent-selection, trace, SSE, UI, Browser, and Computer lanes without altering normal exploration or unrelated dirty hunks`. Expected: `canonicalQueryCount=3`, `orderStable=true`, `forwardVerdict=reverseVerdict=verdict=APPLY`, `nextWorkflow=existing-source-owner-guard`. Any `dirty-overlap`, `source-owner-unproven`, `scenario-coverage-mismatch`, `order-unstable`, safety failure, or score below 50 yields a scoped `HOLD` and no application-source write.

- [ ] **Step 5: Acquire the existing source-edit lease only after stable APPLY**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
  -Action begin -Role desktop -Root . -Topic selection-entropy-replay `
  -OwnerId $ownerId -TtlMinutes 540
```

Expected: lease acquired for the verified Desktop root. Preserve `$ownerId` in the current process for the final `-Action end`; do not invent a second lease mechanism.

- [ ] **Step 6: Record the no-commit checkpoint**

```powershell
Write-Output 'task=0 preflight=APPLY sourceLease=acquired commit=not_authorized'
```

If and only if the user later grants commit authority, Task 0 still creates no commit because it changes no implementation file.

### Task 1: Define the Validated Coordinate, Modes, Coherence, and Failure Contract

**Files:**
- Create: `main/java/com/example/lms/infra/selection/SelectionEntropy.java`
- Create: `main/java/com/example/lms/infra/selection/SelectionCoordinate.java`
- Create: `main/java/com/example/lms/infra/selection/SelectionEntropyMode.java`
- Create: `main/java/com/example/lms/infra/selection/SelectionEntropyCoherence.java`
- Create: `main/java/com/example/lms/infra/selection/SelectionEntropyReason.java`
- Create: `main/java/com/example/lms/infra/selection/SelectionEntropyException.java`
- Create: `src/test/java/com/example/lms/infra/selection/SelectionCoordinateTest.java`

**Interfaces:**
- Consumes: Java 17 only.
- Produces: `SelectionEntropy`, `SelectionCoordinate`, wire-safe enums/reasons, and `SelectionEntropyException.reason()` for every later task.

- [ ] **Step 1: Re-check the task paths against the frozen preimage and write the RED coordinate tests**

```java
package com.example.lms.infra.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SelectionCoordinateTest {
    @Test
    void canonicalizesActorAndUsesUnambiguousBigEndianEncoding() {
        SelectionCoordinate coordinate = new SelectionCoordinate(
                "ensemble.profile.temperature", "Node:Support", 7L, 11L);

        byte[] decision = "ensemble.profile.temperature".getBytes(StandardCharsets.UTF_8);
        byte[] actor = "node:support".getBytes(StandardCharsets.UTF_8);
        ByteBuffer expected = ByteBuffer.allocate(4 + decision.length + 4 + actor.length + 16)
                .putInt(decision.length).put(decision)
                .putInt(actor.length).put(actor)
                .putLong(7L).putLong(11L);

        assertThat(coordinate.actorKey()).isEqualTo("node:support");
        assertThat(coordinate.encoded()).containsExactly(expected.array());
    }

    @Test
    void rejectsInvalidDecisionActorAndOrdinalsWithTheFixedReason() {
        assertInvalid(new SelectionCoordinateCall("Upper", "node:support", 0L, 0L));
        assertInvalid(new SelectionCoordinateCall("router.pick", "space actor", 0L, 0L));
        assertInvalid(new SelectionCoordinateCall("router.pick", "route:auto", -1L, 0L));
        assertInvalid(new SelectionCoordinateCall("router.pick", "route:auto", 0L, -1L));
    }

    private static void assertInvalid(SelectionCoordinateCall call) {
        assertThatThrownBy(call::create)
                .isInstanceOf(SelectionEntropyException.class)
                .extracting(error -> ((SelectionEntropyException) error).reason())
                .isEqualTo(SelectionEntropyReason.COORDINATE_INVALID);
    }

    private record SelectionCoordinateCall(String decision, String actor, long attempt, long draw) {
        SelectionCoordinate create() {
            return new SelectionCoordinate(decision, actor, attempt, draw);
        }
    }
}
```

- [ ] **Step 2: Run the coordinate test and confirm RED**

```powershell
.\gradlew.bat test --tests com.example.lms.infra.selection.SelectionCoordinateTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: compilation fails because the neutral selection types do not exist.

- [ ] **Step 3: Add the exact public interface and bounded enum/reason vocabulary**

```java
public interface SelectionEntropy {
    SelectionEntropyMode mode();
    String algorithmVersion();
    double unitInterval(SelectionCoordinate coordinate);
    int boundedIndex(SelectionCoordinate coordinate, int bound);
}
```

```java
public enum SelectionEntropyMode {
    STANDARD("standard"), REPLAY("replay");
    private final String wireValue;
    SelectionEntropyMode(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }
}

public enum SelectionEntropyCoherence {
    NOT_REQUESTED("not_requested"), ACCEPTED("accepted"), MATCHED("matched"),
    PARTIAL("partial"), FAILED("failed"), FORBIDDEN("forbidden"), INVALID("invalid");
    private final String wireValue;
    SelectionEntropyCoherence(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }
}
```

`SelectionEntropyReason` must contain exactly these public codes plus `NONE("")`:

```java
REPLAY_FORBIDDEN("selection_entropy_replay_forbidden"),
REPLAY_INVALID("selection_entropy_replay_invalid"),
ALGORITHM_UNSUPPORTED("selection_entropy_algorithm_unsupported"),
REPLAY_INIT_FAILED("selection_entropy_replay_init_failed"),
CONTEXT_MISSING("selection_entropy_context_missing"),
COORDINATE_INVALID("selection_entropy_coordinate_invalid"),
STABLE_KEY_MISSING("selection_entropy_stable_key_missing"),
DERIVATION_INVALID("selection_entropy_derivation_invalid"),
CANDIDATE_DRIFT("selection_entropy_candidate_drift"),
DECISION_CAP_REACHED("selection_entropy_decision_cap_reached")
```

`SelectionEntropyException` is a final runtime exception with a non-null `SelectionEntropyReason reason` and a message equal only to `reason.code()`; it carries no raw input or cause message into public output.

- [ ] **Step 4: Implement `SelectionCoordinate` with the exact validation and encoding**

```java
public record SelectionCoordinate(
        String decisionKey,
        String actorKey,
        long attemptOrdinal,
        long drawOrdinal) {
    private static final Pattern DECISION = Pattern.compile("[a-z][a-z0-9._-]{0,95}");
    private static final Pattern ACTOR = Pattern.compile("[a-z0-9][a-z0-9._:/-]{0,127}");

    public SelectionCoordinate {
        actorKey = actorKey == null ? null : actorKey.toLowerCase(Locale.ROOT);
        if (decisionKey == null || !DECISION.matcher(decisionKey).matches()
                || actorKey == null || !ACTOR.matcher(actorKey).matches()
                || attemptOrdinal < 0L || drawOrdinal < 0L) {
            throw new SelectionEntropyException(SelectionEntropyReason.COORDINATE_INVALID);
        }
    }

    byte[] encoded() {
        byte[] decision = decisionKey.getBytes(StandardCharsets.UTF_8);
        byte[] actor = actorKey.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + decision.length + 4 + actor.length + 16)
                .putInt(decision.length).put(decision)
                .putInt(actor.length).put(actor)
                .putLong(attemptOrdinal).putLong(drawOrdinal).array();
    }
}
```

Keep `encoded()` package-private so consumers cannot substitute ad hoc encodings and ledger/HMAC code in the same package shares one canonical byte representation.

- [ ] **Step 5: Run the focused test and confirm GREEN**

```powershell
.\gradlew.bat test --tests com.example.lms.infra.selection.SelectionCoordinateTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: `SelectionCoordinateTest` passes.

- [ ] **Step 6: Inspect the exact diff and handle commit authority**

```powershell
git diff --check -- main/java/com/example/lms/infra/selection src/test/java/com/example/lms/infra/selection
git diff --stat -- main/java/com/example/lms/infra/selection src/test/java/com/example/lms/infra/selection
Write-Output 'task=1 tests=pass commit=not_authorized'
```

If commit authority is later granted:

```powershell
git add -- main/java/com/example/lms/infra/selection/SelectionEntropy.java `
  main/java/com/example/lms/infra/selection/SelectionCoordinate.java `
  main/java/com/example/lms/infra/selection/SelectionEntropyMode.java `
  main/java/com/example/lms/infra/selection/SelectionEntropyCoherence.java `
  main/java/com/example/lms/infra/selection/SelectionEntropyReason.java `
  main/java/com/example/lms/infra/selection/SelectionEntropyException.java `
  src/test/java/com/example/lms/infra/selection/SelectionCoordinateTest.java
git commit -m "feat: define selection entropy coordinates"
```

### Task 2: Implement Live and HMAC Replay Entropy with a Frozen Golden Vector

**Files:**
- Create: `main/java/com/example/lms/infra/selection/LiveSelectionEntropy.java`
- Create: `main/java/com/example/lms/infra/selection/ReplaySelectionEntropy.java`
- Create: `main/java/com/example/lms/infra/selection/SelectionReplaySpec.java`
- Create: `main/java/com/example/lms/infra/selection/SelectionEntropyFactory.java`
- Create: `src/test/java/com/example/lms/infra/selection/ReplaySelectionEntropyTest.java`
- Create: `src/test/java/com/example/lms/infra/selection/LiveSelectionEntropyTest.java`

**Interfaces:**
- Consumes: the Task 1 value types and exceptions.
- Produces: `SelectionEntropyFactory.standard()`, `SelectionEntropyFactory.replay(SelectionReplaySpec)`, `SelectionReplaySpec.v1(byte[])`, and the immutable replay implementation’s safe `seedFingerprint()`.

- [ ] **Step 1: Write RED golden-vector, order-independence, validation, and privacy tests**

Use this exact golden fixture:

```java
private static byte[] seedDeckZero() {
    byte[] seed = new byte[32];
    for (int index = 0; index < seed.length; index++) seed[index] = (byte) index;
    return seed;
}

private static final SelectionCoordinate GOLDEN = new SelectionCoordinate(
        "ensemble.profile.temperature", "node:support", 0L, 0L);

@Test
void freezesSelectionEntropyV1GoldenVector() {
    ReplaySelectionEntropy entropy = (ReplaySelectionEntropy)
            SelectionEntropyFactory.replay(SelectionReplaySpec.v1(seedDeckZero()));

    assertThat(entropy.seedFingerprint()).isEqualTo("009e8892e5b3");
    assertThat(entropy.unitInterval(GOLDEN)).isEqualTo(0.5613173776353443d);
    assertThat(entropy.boundedIndex(GOLDEN, 97)).isEqualTo(23);
}

@Test
void evaluationAndSubmissionOrderDoNotChangeCoordinateValues() throws Exception {
    List<SelectionCoordinate> abc = List.of(
            new SelectionCoordinate("ensemble.profile.temperature", "node:support", 0, 0),
            new SelectionCoordinate("ensemble.profile.top-p", "node:support", 0, 0),
            new SelectionCoordinate("ensemble.profile.shuffle", "node:falsify", 0, 3));
    Map<SelectionCoordinate, Double> forward = values(abc);
    List<SelectionCoordinate> reversed = new ArrayList<>(abc);
    Collections.reverse(reversed);
    Map<SelectionCoordinate, Double> reverse = values(reversed);

    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
        List<Future<Map.Entry<SelectionCoordinate, Double>>> futures = new ArrayList<>();
        for (SelectionCoordinate coordinate : reversed) {
            futures.add(pool.submit(() -> Map.entry(coordinate, replay().unitInterval(coordinate))));
        }
        Map<SelectionCoordinate, Double> parallel = new HashMap<>();
        for (Future<Map.Entry<SelectionCoordinate, Double>> future : futures) {
            Map.Entry<SelectionCoordinate, Double> value = future.get(5, TimeUnit.SECONDS);
            parallel.put(value.getKey(), value.getValue());
        }
        assertThat(reverse).isEqualTo(forward);
        assertThat(parallel).isEqualTo(forward);
    } finally {
        pool.shutdownNow();
    }
}

private static ReplaySelectionEntropy replay() {
    return (ReplaySelectionEntropy) SelectionEntropyFactory.replay(
            SelectionReplaySpec.v1(seedDeckZero()));
}

private static Map<SelectionCoordinate, Double> values(List<SelectionCoordinate> coordinates) {
    Map<SelectionCoordinate, Double> result = new HashMap<>();
    ReplaySelectionEntropy entropy = replay();
    for (SelectionCoordinate coordinate : coordinates) {
        result.put(coordinate, entropy.unitInterval(coordinate));
    }
    return result;
}

@Test
void rejectsInvalidSeedBoundAndFourBiasedBlocksWithoutLiveFallback() {
    assertThatThrownBy(() -> SelectionReplaySpec.v1(new byte[15]))
            .isInstanceOf(SelectionEntropyException.class)
            .extracting(error -> ((SelectionEntropyException) error).reason())
            .isEqualTo(SelectionEntropyReason.REPLAY_INVALID);
    assertThatThrownBy(() -> replay().boundedIndex(GOLDEN, 0))
            .isInstanceOf(SelectionEntropyException.class);
    assertThatThrownBy(() -> replay().boundedIndex(GOLDEN, 1_000_001))
            .isInstanceOf(SelectionEntropyException.class);
    ReplaySelectionEntropy rejecting = new ReplaySelectionEntropy(
            seedDeckZero(), (coordinate, block) -> {
                byte[] bytes = new byte[32];
                Arrays.fill(bytes, (byte) 0xff);
                return bytes;
            });
    assertThatThrownBy(() -> rejecting.boundedIndex(GOLDEN, 1_000_000))
            .isInstanceOf(SelectionEntropyException.class)
            .extracting(error -> ((SelectionEntropyException) error).reason())
            .isEqualTo(SelectionEntropyReason.DERIVATION_INVALID);
}

@Test
void factoryInitializationFailureUsesTheFixedReason() {
    SelectionReplaySpec spec = SelectionReplaySpec.v1(seedDeckZero());
    assertThatThrownBy(() -> SelectionEntropyFactory.replay(spec, () -> {
        throw new GeneralSecurityException("fixed-test-init-failure");
    })).isInstanceOf(SelectionEntropyException.class)
            .extracting(error -> ((SelectionEntropyException) error).reason())
            .isEqualTo(SelectionEntropyReason.REPLAY_INIT_FAILED);
}

@Test
void decisionActorAttemptAndDrawFieldsAreDomainSeparated() {
    ReplaySelectionEntropy entropy = replay();
    Set<Double> values = Stream.of(
            new SelectionCoordinate("ensemble.profile.temperature", "node:support", 0, 0),
            new SelectionCoordinate("ensemble.profile.top-p", "node:support", 0, 0),
            new SelectionCoordinate("ensemble.profile.temperature", "node:falsify", 0, 0),
            new SelectionCoordinate("ensemble.profile.temperature", "node:support", 1, 0),
            new SelectionCoordinate("ensemble.profile.temperature", "node:support", 0, 1))
            .map(entropy::unitInterval).collect(Collectors.toSet());
    assertThat(values).hasSize(5);
}

@Test
void publicSurfaceAndStringsNeverExposeSeedBytes() throws Exception {
    byte[] seed = seedDeckZero();
    SelectionReplaySpec spec = SelectionReplaySpec.v1(seed);
    Arrays.fill(seed, (byte) 0x7f);
    assertThat(spec.toString()).doesNotContain("00010203");
    assertThat(Arrays.stream(Introspector.getBeanInfo(SelectionReplaySpec.class)
                    .getPropertyDescriptors())
            .map(PropertyDescriptor::getName))
            .containsExactly("class");
    assertThat(Arrays.stream(SelectionReplaySpec.class.getMethods())
            .noneMatch(method -> method.getReturnType().equals(byte[].class))).isTrue();
    assertThat(replay().unitInterval(GOLDEN)).isEqualTo(0.5613173776353443d);
}
```

The package-private `(byte[], BlockDeriver)` constructor exists only to force the mathematically rare four-rejection branch; `BlockDeriver` is package-private and never leaves tests or this implementation.

- [ ] **Step 2: Run the replay/live tests and confirm RED**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.infra.selection.ReplaySelectionEntropyTest `
  --tests com.example.lms.infra.selection.LiveSelectionEntropyTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: compilation fails because live/replay/factory/spec classes do not exist.

- [ ] **Step 3: Implement `SelectionReplaySpec` and factory without a raw seed getter**

`SelectionReplaySpec` is a final class, not a record. It defensively copies the seed on input, exposes only `algorithmVersion()` and `seedFingerprint()`, returns a package-private defensive copy named `copySeedForConstruction()`, and overrides `toString()` with only algorithm and fingerprint. `SelectionEntropyFactory` is final with static methods:

```java
public static SelectionEntropy standard() {
    return LiveSelectionEntropy.INSTANCE;
}

public static SelectionEntropy replay(SelectionReplaySpec spec) {
    return replay(spec, () -> Mac.getInstance("HmacSHA256"));
}

@FunctionalInterface
interface MacProbe {
    Mac create() throws GeneralSecurityException;
}

static SelectionEntropy replay(SelectionReplaySpec spec, MacProbe probe) {
    if (spec == null || !ReplaySelectionEntropy.ALGORITHM_VERSION.equals(spec.algorithmVersion())) {
        throw new SelectionEntropyException(SelectionEntropyReason.ALGORITHM_UNSUPPORTED);
    }
    try {
        Objects.requireNonNull(probe, "probe").create();
        return new ReplaySelectionEntropy(spec.copySeedForConstruction());
    } catch (GeneralSecurityException failure) {
        throw new SelectionEntropyException(SelectionEntropyReason.REPLAY_INIT_FAILED);
    }
}
```

The fingerprint input is ASCII `awx-selection-fingerprint-v1`, one zero byte, then private seed bytes; render only the first six digest bytes as 12 lowercase hexadecimal characters.

- [ ] **Step 4: Implement live behavior with the same validation boundary**

```java
final class LiveSelectionEntropy implements SelectionEntropy {
    static final LiveSelectionEntropy INSTANCE = new LiveSelectionEntropy();
    private LiveSelectionEntropy() {}
    public SelectionEntropyMode mode() { return SelectionEntropyMode.STANDARD; }
    public String algorithmVersion() { return ReplaySelectionEntropy.ALGORITHM_VERSION; }
    public double unitInterval(SelectionCoordinate coordinate) {
        if (coordinate == null) {
            throw new SelectionEntropyException(SelectionEntropyReason.COORDINATE_INVALID);
        }
        return ThreadLocalRandom.current().nextDouble();
    }
    public int boundedIndex(SelectionCoordinate coordinate, int bound) {
        if (coordinate == null) {
            throw new SelectionEntropyException(SelectionEntropyReason.COORDINATE_INVALID);
        }
        if (bound < 1 || bound > 1_000_000) {
            throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
        }
        return ThreadLocalRandom.current().nextInt(bound);
    }
}
```

`LiveSelectionEntropyTest` asserts 2,000 unit values are finite and in `[0,1)`, 2,000 bounded values are in `[0,bound)`, and invalid bounds use `DERIVATION_INVALID`; it does not assert that two unseeded draws differ.

- [ ] **Step 5: Implement exact HMAC input and unbiased mapping**

```java
public final class ReplaySelectionEntropy implements SelectionEntropy {
    public static final String ALGORITHM_VERSION = "selection-entropy-v1";
    private static final BigInteger TWO_256 = BigInteger.ONE.shiftLeft(256);
    private static final double TWO_53 = 9_007_199_254_740_992.0d;
    private final byte[] seed;
    private final String seedFingerprint;
    private final BlockDeriver deriver;

    @FunctionalInterface
    interface BlockDeriver {
        byte[] derive(SelectionCoordinate coordinate, int blockOrdinal);
    }

    public double unitInterval(SelectionCoordinate coordinate) {
        byte[] digest = checkedDigest(coordinate, 0);
        long first53 = ByteBuffer.wrap(digest, 0, 8).getLong() >>> 11;
        double value = first53 / TWO_53;
        if (!Double.isFinite(value) || value < 0.0d || value >= 1.0d) {
            throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
        }
        return value;
    }

    public int boundedIndex(SelectionCoordinate coordinate, int bound) {
        if (bound < 1 || bound > 1_000_000) {
            throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
        }
        BigInteger divisor = BigInteger.valueOf(bound);
        BigInteger limit = TWO_256.subtract(TWO_256.mod(divisor));
        for (int block = 0; block < 4; block++) {
            BigInteger value = new BigInteger(1, checkedDigest(coordinate, block));
            if (value.compareTo(limit) < 0) return value.mod(divisor).intValueExact();
        }
        throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
    }
}
```

The default deriver creates a fresh `Mac` for every call, initializes it with `new SecretKeySpec(seed, "HmacSHA256")`, and authenticates exactly:

```java
byte[] label = ALGORITHM_VERSION.getBytes(StandardCharsets.UTF_8);
byte[] coordinateBytes = coordinate.encoded();
return mac.doFinal(ByteBuffer.allocate(4 + label.length + coordinateBytes.length + 4)
        .putInt(label.length).put(label)
        .put(coordinateBytes)
        .putInt(blockOrdinal)
        .array());
```

The constructors and checked derivation path are exact:

```java
public ReplaySelectionEntropy(byte[] seed) {
    this(seed, null);
}

ReplaySelectionEntropy(byte[] seed, BlockDeriver testDeriver) {
    if (seed == null || seed.length < 16 || seed.length > 64) {
        throw new SelectionEntropyException(SelectionEntropyReason.REPLAY_INVALID);
    }
    this.seed = Arrays.copyOf(seed, seed.length);
    this.seedFingerprint = fingerprint(this.seed);
    this.deriver = testDeriver == null ? this::deriveHmacBlock : testDeriver;
}

private byte[] checkedDigest(SelectionCoordinate coordinate, int blockOrdinal) {
    if (coordinate == null || blockOrdinal < 0) {
        throw new SelectionEntropyException(SelectionEntropyReason.COORDINATE_INVALID);
    }
    byte[] digest = deriver.derive(coordinate, blockOrdinal);
    if (digest == null || digest.length != 32) {
        throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
    }
    return digest;
}

private byte[] deriveHmacBlock(SelectionCoordinate coordinate, int blockOrdinal) {
    try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(seed, "HmacSHA256"));
        byte[] label = ALGORITHM_VERSION.getBytes(StandardCharsets.UTF_8);
        byte[] encoded = coordinate.encoded();
        return mac.doFinal(ByteBuffer.allocate(4 + label.length + encoded.length + 4)
                .putInt(label.length).put(label).put(encoded).putInt(blockOrdinal).array());
    } catch (GeneralSecurityException failure) {
        throw new SelectionEntropyException(SelectionEntropyReason.REPLAY_INIT_FAILED);
    }
}
```

The golden message is `00000014 || UTF8(selection-entropy-v1) || coordinate.encoded() || 00000000`; its HMAC digest for the fixture is `8fb27ee39ecb0ec5782cca8c911163a0509cc3ae45efe6b1996fb2703f4cb91f`.

- [ ] **Step 6: Run focused tests and confirm GREEN**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.infra.selection.SelectionCoordinateTest `
  --tests com.example.lms.infra.selection.ReplaySelectionEntropyTest `
  --tests com.example.lms.infra.selection.LiveSelectionEntropyTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: all core entropy tests pass and the fixed golden vector matches exactly.

- [ ] **Step 7: Inspect the diff and handle commit authority**

```powershell
git diff --check -- main/java/com/example/lms/infra/selection src/test/java/com/example/lms/infra/selection
Write-Output 'task=2 goldenVector=pass orderIndependent=pass commit=not_authorized'
```

If commit authority is later granted, stage only the four Task 2 production files and two Task 2 test files, then run:

```powershell
git commit -m "feat: add coordinate-keyed selection replay"
```

### Task 3: Add the Bounded Hash-Only Decision Ledger and Safe Projection

**Files:**
- Create: `main/java/com/example/lms/infra/selection/SelectionDecisionLedger.java`
- Create: `main/java/com/example/lms/infra/selection/SelectionEntropyProjection.java`
- Create: `src/test/java/com/example/lms/infra/selection/SelectionDecisionLedgerTest.java`
- Create: `src/test/java/com/example/lms/infra/selection/SelectionEntropyProjectionTest.java`

**Interfaces:**
- Consumes: `SelectionEntropy`, `ReplaySelectionEntropy.seedFingerprint()`, coordinate encoding, coherence, and reasons.
- Produces: thread-safe `SelectionDecisionLedger.record(...)`, `snapshot(...)`, lane counters, drift/cap behavior, and `SelectionEntropyProjection` used by context, trace, DTO, SSE, and UI tasks.

- [ ] **Step 1: Write RED canonical-hash, drift, capacity, concurrency, and projection tests**

```java
@Test
void freezesLedgerHashInputsAndTerminalDigest() {
    SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
    SelectionCoordinate coordinate = new SelectionCoordinate(
            "llm-router.weighted-exploration", "route:auto", 1L, 0L);
    ledger.record(SelectionDecisionLedger.Lane.ROUTER, coordinate,
            List.of("route:a", "route:b"), 1, "", true, false);

    SelectionDecisionLedger.Snapshot snapshot = ledger.snapshot(true);
    assertThat(snapshot.decisionDigest())
            .isEqualTo("7b4cfe15f9aaf721fe73133247ced66ecb89e14dbe1a6a59c0bb33aecc3dc349");
    assertThat(snapshot.decisionCount()).isEqualTo(1);
    assertThat(snapshot.drawCount()).isEqualTo(1);
    assertThat(snapshot.routerDrawCount()).isEqualTo(1);
}

@Test
void candidateDriftMarksPartialWithoutChangingTheFirstDecision() {
    SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
    SelectionCoordinate coordinate = new SelectionCoordinate("strategy.softmax", "strategy:dynamic", 0, 0);
    ledger.record(SelectionDecisionLedger.Lane.STRATEGY, coordinate,
            List.of("web_first", "vector_first"), 0, "", true, false);
    ledger.record(SelectionDecisionLedger.Lane.STRATEGY, coordinate,
            List.of("vector_first", "web_first"), 0, "", true, false);

    SelectionDecisionLedger.Snapshot snapshot = ledger.snapshot(true);
    assertThat(snapshot.coherence()).isEqualTo(SelectionEntropyCoherence.PARTIAL);
    assertThat(snapshot.reason()).isEqualTo(SelectionEntropyReason.CANDIDATE_DRIFT);
    assertThat(snapshot.decisionCount()).isEqualTo(1);
    assertThat(snapshot.candidateDriftCount()).isEqualTo(1);
}

@Test
void capacityAndParallelRecordingNeverChangeSelectionOrLeakKeys() throws Exception {
    SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay(2);
    IntStream.range(0, 3).parallel().forEach(index -> ledger.record(
            SelectionDecisionLedger.Lane.ENSEMBLE,
            new SelectionCoordinate("ensemble.profile.shuffle", "node:support", 0, index),
            List.of("profile:a", "profile:b"), index % 2, "", true, false));
    SelectionDecisionLedger.Snapshot snapshot = ledger.snapshot(true);
    assertThat(snapshot.decisionCount()).isEqualTo(2);
    assertThat(snapshot.coherence()).isEqualTo(SelectionEntropyCoherence.PARTIAL);
    assertThat(snapshot.reason()).isEqualTo(SelectionEntropyReason.DECISION_CAP_REACHED);
    assertThat(snapshot.toString()).doesNotContain("profile:a", "profile:b", "node:support");
}
```

The exact frozen hash fixtures are:

```text
coordinateHash=c7aaf664692a6008095402d4cc5b9895f28c7761c2e107b9410e1e2266cc0af4
candidateKeyHash(route:a)=b134d4de4346c659ff3b786d001232ec6b44089c3225aa3bd2932abb25f825e1
candidateKeyHash(route:b)=dc0b863912d0fec70398f3ea1ae8497c84dd6d95a8fa77a0303c5adb317aa5ae
candidateSetHash=953867729eb6f53641963e19b518c1311582318777e9b60fb8011680ab9c72c3
```

- [ ] **Step 2: Run the ledger/projection tests and confirm RED**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.infra.selection.SelectionDecisionLedgerTest `
  --tests com.example.lms.infra.selection.SelectionEntropyProjectionTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: compilation fails because ledger and projection types do not exist.

- [ ] **Step 3: Implement the ledger API and canonical hash functions**

```java
public final class SelectionDecisionLedger {
    public enum Lane { ROUTER, STRATEGY, ENSEMBLE, RANKING }
    public static final int MAX_DECISIONS = 10_000;

    public static SelectionDecisionLedger forStandard() {
        return new SelectionDecisionLedger(SelectionEntropyCoherence.NOT_REQUESTED, MAX_DECISIONS);
    }
    public static SelectionDecisionLedger forReplay() {
        return new SelectionDecisionLedger(SelectionEntropyCoherence.ACCEPTED, MAX_DECISIONS);
    }
    static SelectionDecisionLedger forReplay(int capacity) {
        if (capacity < 1 || capacity > MAX_DECISIONS) {
            throw new IllegalArgumentException("selection_entropy_ledger_capacity_invalid");
        }
        return new SelectionDecisionLedger(SelectionEntropyCoherence.ACCEPTED, capacity);
    }

    public void record(
            Lane lane,
            SelectionCoordinate coordinate,
            List<String> stableCandidateKeys,
            int selectedIndex,
            String fallbackReason,
            boolean drawConsumed,
            boolean stableTieBreak) {
        recordValidatedDecision(lane, coordinate, stableCandidateKeys, selectedIndex,
                fallbackReason == null ? "" : fallbackReason, drawConsumed, stableTieBreak);
    }

    public void markFailure(SelectionEntropyReason failureReason) {
        if (failureReason == null || failureReason == SelectionEntropyReason.NONE) {
            throw new IllegalArgumentException("selection_entropy_failure_reason_invalid");
        }
        coherence.set(SelectionEntropyCoherence.FAILED);
        reason.set(failureReason);
    }

    public Snapshot snapshot(boolean terminal) {
        SelectionEntropyCoherence visible = coherence.get();
        if (terminal && visible == SelectionEntropyCoherence.ACCEPTED) {
            visible = SelectionEntropyCoherence.MATCHED;
        }
        return snapshotOf(visible, reason.get(), canonicalDecisionDigest());
    }

    public record Snapshot(
            SelectionEntropyCoherence coherence,
            SelectionEntropyReason reason,
            String decisionDigest,
            int decisionCount,
            int drawCount,
            int stableTieBreakCount,
            int candidateDriftCount,
            int routerDrawCount,
            int strategyDrawCount,
            int ensembleDrawCount) {}
}
```

Implement these exact rules:

1. Validate a non-null lane/coordinate, a non-empty candidate list, every nonblank canonical stable key, and an in-range selected index. Missing stable keys throw `STABLE_KEY_MISSING` before recording.
2. `coordinateHash = sha256(coordinate.encoded())`, lowercase 64 hex.
3. Candidate key hash input is ASCII `awx-selection-candidate-key-v1`, one zero byte, canonical UTF-8 key.
4. Candidate-set hash input is four-byte big-endian candidate count followed by ordered 32-byte candidate hashes.
5. Use `ConcurrentHashMap<String,DecisionRow>` keyed by coordinate hash plus atomics for counters and one synchronized insertion/cap/drift section. A repeated identical row is idempotent. A repeated coordinate with another candidate-set hash increments drift once and marks partial without replacing the first row.
6. A new coordinate at capacity records no row, marks partial with `DECISION_CAP_REACHED`, and never changes the already-selected result.
7. Increment draw and lane-draw counters only on the first stored row with `drawConsumed=true`; increment stable tie count only on the first stored row with `stableTieBreak=true`.
8. Terminal rows are sorted by coordinate hash, candidate-set hash, selected index, and fallback reason. Hash UTF-8 lines `coordinateHash|candidateSetHash|selectedIndex|fallbackReason\n`; the empty ledger hashes empty bytes.

Use these concrete private members/helpers so the public methods above are complete and observability cannot affect selection:

```java
private final int capacity;
private final ConcurrentHashMap<String, DecisionRow> rows = new ConcurrentHashMap<>();
private final Set<String> driftedCoordinates = ConcurrentHashMap.newKeySet();
private final AtomicReference<SelectionEntropyCoherence> coherence;
private final AtomicReference<SelectionEntropyReason> reason =
        new AtomicReference<>(SelectionEntropyReason.NONE);
private final AtomicInteger drawCount = new AtomicInteger();
private final AtomicInteger stableTieBreakCount = new AtomicInteger();
private final AtomicInteger candidateDriftCount = new AtomicInteger();
private final AtomicInteger routerDrawCount = new AtomicInteger();
private final AtomicInteger strategyDrawCount = new AtomicInteger();
private final AtomicInteger ensembleDrawCount = new AtomicInteger();

private SelectionDecisionLedger(SelectionEntropyCoherence initial, int capacity) {
    this.coherence = new AtomicReference<>(initial);
    this.capacity = capacity;
}

private synchronized void recordValidatedDecision(
        Lane lane, SelectionCoordinate coordinate, List<String> keys, int selectedIndex,
        String fallbackReason, boolean drawConsumed, boolean stableTieBreak) {
    if (lane == null || coordinate == null || keys == null || keys.isEmpty()
            || selectedIndex < 0 || selectedIndex >= keys.size()
            || keys.stream().anyMatch(key -> key == null || key.isBlank())) {
        throw new SelectionEntropyException(SelectionEntropyReason.STABLE_KEY_MISSING);
    }
    String safeFallback = fallbackReason == null ? "" : fallbackReason;
    if (!safeFallback.matches("[a-z0-9_]{0,64}")) {
        throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
    }
    String coordinateHash = hex(sha256(coordinate.encoded()));
    String candidateSetHash = candidateSetHash(keys);
    DecisionRow proposed = new DecisionRow(coordinateHash, candidateSetHash, selectedIndex,
            safeFallback, lane, drawConsumed, stableTieBreak);
    DecisionRow existing = rows.get(coordinateHash);
    if (existing != null) {
        if (!existing.candidateSetHash().equals(candidateSetHash)
                && driftedCoordinates.add(coordinateHash)) {
            candidateDriftCount.incrementAndGet();
            markPartial(SelectionEntropyReason.CANDIDATE_DRIFT);
        }
        return;
    }
    if (rows.size() >= capacity) {
        markPartial(SelectionEntropyReason.DECISION_CAP_REACHED);
        return;
    }
    rows.put(coordinateHash, proposed);
    if (drawConsumed) {
        drawCount.incrementAndGet();
        switch (lane) {
            case ROUTER -> routerDrawCount.incrementAndGet();
            case STRATEGY -> strategyDrawCount.incrementAndGet();
            case ENSEMBLE -> ensembleDrawCount.incrementAndGet();
            case RANKING -> { }
        }
    }
    if (stableTieBreak) stableTieBreakCount.incrementAndGet();
}

private void markPartial(SelectionEntropyReason partialReason) {
    if (coherence.get() != SelectionEntropyCoherence.FAILED) {
        coherence.set(SelectionEntropyCoherence.PARTIAL);
        reason.compareAndSet(SelectionEntropyReason.NONE, partialReason);
    }
}

private static String candidateSetHash(List<String> keys) {
    ByteBuffer bytes = ByteBuffer.allocate(4 + Math.multiplyExact(keys.size(), 32));
    bytes.putInt(keys.size());
    for (String key : keys) {
        MessageDigest digest = sha256Digest();
        digest.update("awx-selection-candidate-key-v1".getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(key.getBytes(StandardCharsets.UTF_8));
        bytes.put(digest.digest());
    }
    return hex(sha256(bytes.array()));
}

private String canonicalDecisionDigest() {
    String text = rows.values().stream()
            .sorted(Comparator.comparing(DecisionRow::coordinateHash)
                    .thenComparing(DecisionRow::candidateSetHash)
                    .thenComparingInt(DecisionRow::selectedIndex)
                    .thenComparing(DecisionRow::fallbackReason))
            .map(row -> row.coordinateHash() + "|" + row.candidateSetHash() + "|"
                    + row.selectedIndex() + "|" + row.fallbackReason() + "\n")
            .collect(Collectors.joining());
    return hex(sha256(text.getBytes(StandardCharsets.UTF_8)));
}

private Snapshot snapshotOf(
        SelectionEntropyCoherence visible,
        SelectionEntropyReason visibleReason,
        String digest) {
    return new Snapshot(visible, visibleReason, digest, rows.size(), drawCount.get(),
            stableTieBreakCount.get(), candidateDriftCount.get(), routerDrawCount.get(),
            strategyDrawCount.get(), ensembleDrawCount.get());
}

private record DecisionRow(
        String coordinateHash, String candidateSetHash, int selectedIndex,
        String fallbackReason, Lane lane, boolean drawConsumed, boolean stableTieBreak) {}
```

`sha256Digest()` obtains JDK `SHA-256` or throws `SelectionEntropyException(DERIVATION_INVALID)`; `sha256(byte[])` calls it and `hex(byte[])` renders lowercase two-digit hex per byte. Neither helper logs its input.

- [ ] **Step 4: Implement one immutable safe projection shared by all public transports**

```java
public record SelectionEntropyProjection(
        String schema,
        String mode,
        String algorithmVersion,
        boolean replayAccepted,
        String coherenceStatus,
        String seedFingerprint,
        String decisionDigest,
        int decisionCount,
        int drawCount,
        int stableTieBreakCount,
        int candidateDriftCount,
        int routerDrawCount,
        int strategyDrawCount,
        int ensembleDrawCount,
        boolean completionOrderDeterministic,
        String reasonCode) {

    public static SelectionEntropyProjection from(
            SelectionEntropy entropy,
            SelectionDecisionLedger ledger,
            boolean terminal,
            boolean completionOrderDeterministic) {
        Objects.requireNonNull(entropy, "entropy");
        SelectionDecisionLedger.Snapshot value = Objects.requireNonNull(ledger, "ledger").snapshot(terminal);
        String fingerprint = entropy instanceof ReplaySelectionEntropy replay
                ? replay.seedFingerprint() : null;
        return new SelectionEntropyProjection(
                "awx.selection-entropy.v1", entropy.mode().wireValue(), entropy.algorithmVersion(),
                entropy.mode() == SelectionEntropyMode.REPLAY, value.coherence().wireValue(),
                fingerprint, value.decisionDigest(), value.decisionCount(), value.drawCount(),
                value.stableTieBreakCount(), value.candidateDriftCount(), value.routerDrawCount(),
                value.strategyDrawCount(), value.ensembleDrawCount(),
                completionOrderDeterministic, value.reason().code());
    }
}
```

The constructor validates: schema `awx.selection-entropy.v1`; mode `standard|replay`; algorithm `selection-entropy-v1`; coherence in the fixed enum; replay fingerprint either null or `[a-f0-9]{12}`; digest `[a-f0-9]{64}`; every count in `0..10000`; and reason either empty or one of `SelectionEntropyReason`. For replay, `replayAccepted=true`; early clean snapshot is `accepted`; terminal clean snapshot is `matched`. Standard mode is `not_requested`, has no fingerprint, and never claims replay accepted.

- [ ] **Step 5: Run ledger/projection tests and confirm GREEN**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.infra.selection.SelectionDecisionLedgerTest `
  --tests com.example.lms.infra.selection.SelectionEntropyProjectionTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: exact hashes, capacity, drift, concurrency, count bounds, and safe projection tests pass.

- [ ] **Step 6: Inspect the diff and handle commit authority**

```powershell
git diff --check -- main/java/com/example/lms/infra/selection src/test/java/com/example/lms/infra/selection
Write-Output 'task=3 ledger=pass projection=pass commit=not_authorized'
```

If commit authority is later granted, stage only Task 3’s four paths and run:

```powershell
git commit -m "feat: record bounded selection replay evidence"
```

### Task 4: Attach Entropy and Ledger Once to GuardContext and Prove Existing Propagation

**Files:**
- Modify: `main/java/com/example/lms/service/guard/GuardContext.java:125-135,625-690`
- Create: `src/test/java/com/example/lms/service/guard/GuardContextSelectionEntropyTest.java`
- Modify: `src/test/java/com/example/lms/infra/exec/ContextPropagationTimeBudgetTest.java`
- Verify unchanged: `main/java/com/example/lms/infra/exec/ContextPropagation.java`

**Interfaces:**
- Consumes: `SelectionEntropyFactory.standard()`, `SelectionEntropy`, and `SelectionDecisionLedger`.
- Produces: `GuardContext.attachSelectionEntropy(...)`, `selectionEntropy()`, and `selectionDecisionLedger()`; `copy()` carries the same two references.

- [ ] **Step 1: Hash the current dirty `GuardContext` preimage and write RED attach/copy tests**

```java
@Test
void defaultsToStandardAndAttachesReplayOnlyOnce() {
    GuardContext context = GuardContext.defaultContext();
    assertThat(context.selectionEntropy().mode()).isEqualTo(SelectionEntropyMode.STANDARD);

    SelectionEntropy replay = SelectionEntropyFactory.replay(SelectionReplaySpec.v1(new byte[32]));
    SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
    context.attachSelectionEntropy(replay, ledger);
    context.attachSelectionEntropy(replay, ledger);

    assertThat(context.selectionEntropy()).isSameAs(replay);
    assertThat(context.selectionDecisionLedger()).isSameAs(ledger);
    assertThat(context.copy().selectionEntropy()).isSameAs(replay);
    assertThat(context.copy().selectionDecisionLedger()).isSameAs(ledger);
}

@Test
void rejectsReplacementWithAnotherCarrierOrLedger() {
    GuardContext context = GuardContext.defaultContext();
    SelectionEntropy first = SelectionEntropyFactory.replay(SelectionReplaySpec.v1(new byte[32]));
    SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
    context.attachSelectionEntropy(first, ledger);

    byte[] anotherSeed = new byte[32];
    anotherSeed[0] = 1;
    SelectionEntropy second = SelectionEntropyFactory.replay(SelectionReplaySpec.v1(anotherSeed));
    assertThatThrownBy(() -> context.attachSelectionEntropy(second, ledger))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("selection_entropy_context_already_attached");
    assertThatThrownBy(() -> context.attachSelectionEntropy(first, SelectionDecisionLedger.forReplay()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("selection_entropy_context_already_attached");
}
```

- [ ] **Step 2: Extend the existing propagation test for Runnable, Supplier, Callable, nested restore, and exception cleanup**

```java
@Test
void carriesTheSameSelectionReferencesAndRestoresTheWorkerContext() throws Exception {
    GuardContext outer = GuardContext.defaultContext();
    SelectionEntropy replay = SelectionEntropyFactory.replay(SelectionReplaySpec.v1(new byte[32]));
    SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
    outer.attachSelectionEntropy(replay, ledger);
    GuardContextHolder.set(outer);

    Callable<Boolean> wrapped = ContextPropagation.wrapCallable(() ->
            GuardContextHolder.get().selectionEntropy() == replay
                    && GuardContextHolder.get().selectionDecisionLedger() == ledger);
    GuardContext workerPrior = GuardContext.defaultContext();
    GuardContextHolder.set(workerPrior);
    assertThat(wrapped.call()).isTrue();
    assertThat(GuardContextHolder.get()).isSameAs(workerPrior);

    Callable<Void> failing = ContextPropagation.wrapCallable(() -> {
        assertThat(GuardContextHolder.get().selectionEntropy()).isSameAs(replay);
        throw new IllegalStateException("fixed-test-failure");
    });
    assertThatThrownBy(failing::call).hasMessage("fixed-test-failure");
    assertThat(GuardContextHolder.get()).isSameAs(workerPrior);
    GuardContextHolder.clear();
}
```

Add equivalent assertions to the existing wrapped `Runnable` and `Supplier` cases. No test may use a raw seed string or an unseeded random assertion.

- [ ] **Step 3: Run the context tests and confirm RED**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.service.guard.GuardContextSelectionEntropyTest `
  --tests com.example.lms.infra.exec.ContextPropagationTimeBudgetTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: compilation fails on the three new `GuardContext` methods.

- [ ] **Step 4: Add attach-once fields and accessors to `GuardContext` without using `planOverrides`**

```java
private SelectionEntropy selectionEntropy;
private SelectionDecisionLedger selectionDecisionLedger;

public synchronized void attachSelectionEntropy(
        SelectionEntropy entropy,
        SelectionDecisionLedger ledger) {
    Objects.requireNonNull(entropy, "entropy");
    Objects.requireNonNull(ledger, "ledger");
    if (selectionEntropy == null && selectionDecisionLedger == null) {
        selectionEntropy = entropy;
        selectionDecisionLedger = ledger;
        return;
    }
    if (selectionEntropy == entropy && selectionDecisionLedger == ledger) return;
    throw new IllegalStateException("selection_entropy_context_already_attached");
}

public synchronized SelectionEntropy selectionEntropy() {
    return selectionEntropy == null ? SelectionEntropyFactory.standard() : selectionEntropy;
}

public synchronized SelectionDecisionLedger selectionDecisionLedger() {
    if (selectionDecisionLedger == null) {
        selectionDecisionLedger = SelectionDecisionLedger.forStandard();
    }
    return selectionDecisionLedger;
}
```

In `copy()`, assign `copy.selectionEntropy = this.selectionEntropy` and `copy.selectionDecisionLedger = this.selectionDecisionLedger` directly. Do not clone entropy, clone the ledger, add a ThreadLocal, or put either object in `planOverrides`.

- [ ] **Step 5: Run the focused tests and confirm GREEN without editing `ContextPropagation.java`**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.service.guard.GuardContextSelectionEntropyTest `
  --tests com.example.lms.infra.exec.ContextPropagationTimeBudgetTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: attach/copy/propagation/restore tests pass through the existing `ContextPropagation` implementation. If the untouched propagation test fails, stop this task as `evidence_needed: exact lost-context branch / verify with the failing method and worker pre/post context identities`; do not redesign context transport speculatively.

- [ ] **Step 6: Inspect only the owned hunks and handle commit authority**

```powershell
git diff --check -- main/java/com/example/lms/service/guard/GuardContext.java `
  src/test/java/com/example/lms/service/guard/GuardContextSelectionEntropyTest.java `
  src/test/java/com/example/lms/infra/exec/ContextPropagationTimeBudgetTest.java
Write-Output 'task=4 attachOnce=pass propagation=pass commit=not_authorized'
```

If commit authority is later granted, stage exactly those three files and run:

```powershell
git commit -m "feat: propagate request selection entropy"
```

### Task 5: Protect and Parse Replay at Sync and Stream Ingress

**Files:**
- Create: `main/java/com/example/lms/api/SelectionReplayRequestResolver.java`
- Create: `main/java/com/example/lms/api/SelectionReplayRequestException.java`
- Create: `main/java/com/example/lms/api/SelectionReplayExceptionHandler.java`
- Modify: `main/java/com/example/lms/api/ChatApiController.java:950-960,1351-1501,1506-1718,2677-2680,3553-3595`
- Create: `src/test/java/com/example/lms/api/SelectionReplayRequestResolverTest.java`
- Create: `src/test/java/com/example/lms/api/ChatApiControllerSelectionReplayTest.java`
- Verify: `src/test/java/com/example/lms/security/AdminTokenGuardInterceptorTest.java`

**Interfaces:**
- Consumes: `AdminTokenGuardInterceptor.isPresentedHeaderTokenAuthorized(HttpServletRequest)`, the Task 2 factory/spec, Task 3 ledger, and Task 4 attach API.
- Produces: `SelectionReplayRequestResolver.resolve(HttpServletRequest)` returning only entropy plus ledger; fixed JSON error responses; sync and stream attach before workflow/background fan-out.

- [ ] **Step 1: Write RED resolver tests that prove authorization occurs before parsing**

```java
@ExtendWith(MockitoExtension.class)
class SelectionReplayRequestResolverTest {
    @Mock AdminTokenGuardInterceptor guard;

    @Test
    void headerAbsenceUsesStandardWithoutConsultingAdminGuard() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        SelectionReplayRequestResolver.Resolved resolved = resolver().resolve(request);
        assertThat(resolved.entropy().mode()).isEqualTo(SelectionEntropyMode.STANDARD);
        verifyNoInteractions(guard);
    }

    @Test
    void unauthorizedMalformedValueRevealsOnlyForbidden() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SelectionReplayRequestResolver.HEADER, "visibly malformed");
        when(guard.isPresentedHeaderTokenAuthorized(request)).thenReturn(false);
        assertThatThrownBy(() -> resolver().resolve(request))
                .isInstanceOf(SelectionReplayRequestException.class)
                .extracting(error -> ((SelectionReplayRequestException) error).code())
                .isEqualTo("selection_entropy_replay_forbidden");
    }

    @Test
    void authorizedValidHeaderCreatesReplayWithoutRetainingHeaderText() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        request.addHeader(SelectionReplayRequestResolver.HEADER, "v1:" + encoded);
        when(guard.isPresentedHeaderTokenAuthorized(request)).thenReturn(true);
        SelectionReplayRequestResolver.Resolved resolved = resolver().resolve(request);
        assertThat(resolved.entropy().mode()).isEqualTo(SelectionEntropyMode.REPLAY);
        assertThat(resolved.ledger().snapshot(false).coherence())
                .isEqualTo(SelectionEntropyCoherence.ACCEPTED);
        assertThat(resolved.toString()).doesNotContain(encoded);
    }
}
```

Add parameterized cases for duplicate values, blank/whitespace, padding, standard Base64 characters, 15-byte seed, 65-byte seed, over-96 text, and unknown `v2`; verify invalid syntax is 400 `selection_entropy_replay_invalid`, unknown version is 400 `selection_entropy_algorithm_unsupported`, and the guard is called exactly once before either result.

- [ ] **Step 2: Write controller source/behavior RED tests for both entry points and zero downstream calls**

`ChatApiControllerSelectionReplayTest` must assert:

```java
assertThat(controllerSource).contains("selectionReplayRequestResolver.resolve(request)");
assertThat(controllerSource.indexOf("selectionReplayRequestResolver.resolve(request)"))
        .isLessThan(controllerSource.indexOf("ContextPropagation.wrapCallable"));
assertThat(controllerSource).doesNotContain("X-AWX-Selection-Replay\", req");
assertThat(controllerSource).doesNotContain("setSelectionReplay");
```

Its MVC slice cases use a mocked resolver to prove forbidden/invalid requests return only `{"code":"..."}` and verify the mocked workflow, adaptive service, router, provider model, and stream worker have zero interactions. Add one sync and one stream accepted case that capture the state passed into the newly created `GuardContext` and assert identical entropy/ledger references.

- [ ] **Step 3: Run resolver/controller/admin tests and confirm RED**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.api.SelectionReplayRequestResolverTest `
  --tests com.example.lms.api.ChatApiControllerSelectionReplayTest `
  --tests com.example.lms.security.AdminTokenGuardInterceptorTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: new classes and controller calls are absent; existing token-guard tests remain independently green.

- [ ] **Step 4: Implement the servlet-facing resolver with exact parsing order and limits**

```java
@Component
public final class SelectionReplayRequestResolver {
    public static final String HEADER = "X-AWX-Selection-Replay";
    private static final int MAX_HEADER_CHARS = 96;
    private static final Pattern PAYLOAD = Pattern.compile("[A-Za-z0-9_-]+");
    private final AdminTokenGuardInterceptor adminTokenGuard;

    public record Resolved(SelectionEntropy entropy, SelectionDecisionLedger ledger) {
        public Resolved {
            Objects.requireNonNull(entropy, "entropy");
            Objects.requireNonNull(ledger, "ledger");
        }
        public static Resolved standard() {
            return new Resolved(SelectionEntropyFactory.standard(),
                    SelectionDecisionLedger.forStandard());
        }
        @Override public String toString() {
            return "Resolved[mode=" + entropy.mode().wireValue() + "]";
        }
    }

    public Resolved resolve(HttpServletRequest request) {
        List<String> values = request == null || request.getHeaders(HEADER) == null
                ? List.of() : Collections.list(request.getHeaders(HEADER));
        if (values.isEmpty()) return Resolved.standard();
        if (!adminTokenGuard.isPresentedHeaderTokenAuthorized(request)) {
            throw SelectionReplayRequestException.forbidden();
        }
        if (values.size() != 1) throw SelectionReplayRequestException.invalid();
        String value = values.get(0);
        if (value == null || value.length() > MAX_HEADER_CHARS
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw SelectionReplayRequestException.invalid();
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':')) {
            throw SelectionReplayRequestException.invalid();
        }
        String version = value.substring(0, separator);
        if (!"v1".equals(version)) throw SelectionReplayRequestException.unsupported();
        String payload = value.substring(separator + 1);
        if (!PAYLOAD.matcher(payload).matches() || payload.indexOf('=') >= 0) {
            throw SelectionReplayRequestException.invalid();
        }
        byte[] decoded = null;
        try {
            decoded = Base64.getUrlDecoder().decode(payload);
            SelectionReplaySpec spec = SelectionReplaySpec.v1(decoded);
            return new Resolved(SelectionEntropyFactory.replay(spec),
                    SelectionDecisionLedger.forReplay());
        } catch (SelectionEntropyException failure) {
            throw SelectionReplayRequestException.from(failure.reason());
        } catch (IllegalArgumentException failure) {
            throw SelectionReplayRequestException.invalid();
        } finally {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
        }
    }
}
```

Use a `finally` block to zero the temporary decoded array even when construction fails. Do not log the value, decoded bytes, token, or exception message.

- [ ] **Step 5: Implement fixed HTTP errors without changing normal chat response types**

`SelectionReplayRequestException` is final and exposes only `HttpStatus status()` and `String code()`. Factory mapping is exact: forbidden 403; invalid and unsupported 400; factory initialization 500. `SelectionReplayExceptionHandler` is scoped to `ChatApiController`:

```java
@RestControllerAdvice(assignableTypes = ChatApiController.class)
final class SelectionReplayExceptionHandler {
    record ErrorBody(String code) {}

    @ExceptionHandler(SelectionReplayRequestException.class)
    ResponseEntity<ErrorBody> handle(SelectionReplayRequestException failure) {
        return ResponseEntity.status(failure.status()).body(new ErrorBody(failure.code()));
    }
}
```

The body has exactly one property. It never reveals whether token configuration exists or whether unauthorized replay syntax was valid.

- [ ] **Step 6: Attach the resolved state in `/api/chat` and `/api/chat/stream` before work**

Add a non-final required-at-runtime field using the controller’s existing compatibility pattern:

```java
@Autowired(required = false)
private SelectionReplayRequestResolver selectionReplayRequestResolver;
```

At the first safe line of both public methods, call a private helper that fails with `selection_entropy_replay_init_failed` when the bean is absent and a replay header is present, while allowing `Resolved.standard()` for header absence. Capture only `Resolved.entropy()` and `Resolved.ledger()` in asynchronous code.

Extend only the private `handleChat` overload to receive `Resolved selectionState`, then attach immediately after `GuardContext.defaultContext()`:

```java
GuardContext ctx = GuardContext.defaultContext();
ctx.attachSelectionEntropy(selectionState.entropy(), selectionState.ledger());
if (selectionState.entropy().mode() == SelectionEntropyMode.REPLAY
        && ctx.selectionEntropy() != selectionState.entropy()) {
    throw new SelectionEntropyException(SelectionEntropyReason.CONTEXT_MISSING);
}
```

Do the same immediately after the stream worker creates `gctx` and before `GuardContextHolder.set(gctx)`. The closures must not capture `HttpServletRequest`, the replay header, `SelectionReplaySpec`, an admin/owner token, or decoded temporary bytes. Existing `finally` blocks continue clearing `GuardContextHolder`, trace, budget, and run scope.

- [ ] **Step 7: Preserve replay failures through the existing reactive error path**

Before the generic `onErrorResume` formatter, propagate `SelectionReplayRequestException` and map a replay-mode `SelectionEntropyException` to a fixed safe request exception. In the stream worker, emit a typed entropy failure signal plus existing terminal error/cancel semantics; do not attempt a live retry or another provider call.

- [ ] **Step 8: Run focused ingress tests and confirm GREEN**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.api.SelectionReplayRequestResolverTest `
  --tests com.example.lms.api.ChatApiControllerSelectionReplayTest `
  --tests com.example.lms.security.AdminTokenGuardInterceptorTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: auth-before-parse, exact status/code bodies, header-absent compatibility, sync/stream reference identity, zero downstream calls on rejected input, and sentinel-negative checks pass.

- [ ] **Step 9: Inspect owned hunks and handle commit authority**

```powershell
git diff --check -- main/java/com/example/lms/api/SelectionReplayRequestResolver.java `
  main/java/com/example/lms/api/SelectionReplayRequestException.java `
  main/java/com/example/lms/api/SelectionReplayExceptionHandler.java `
  main/java/com/example/lms/api/ChatApiController.java `
  src/test/java/com/example/lms/api/SelectionReplayRequestResolverTest.java `
  src/test/java/com/example/lms/api/ChatApiControllerSelectionReplayTest.java
Write-Output 'task=5 authorization=pass syncAttach=pass streamAttach=pass commit=not_authorized'
```

If commit authority is later granted, stage exactly those six paths and run:

```powershell
git commit -m "feat: authorize request scoped selection replay"
```

### Task 6: Canonicalize LLM Routes and Seed Only Weighted Exploration

**Files:**
- Modify: `main/java/ai/abandonware/nova/orch/router/LlmRouterBandit.java:141-284`
- Modify: `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java:148-170`
- Modify: `src/test/java/ai/abandonware/nova/orch/router/LlmRouterBanditTraceTest.java`
- Modify: `src/test/java/ai/abandonware/nova/orch/aop/LlmRouterRequestTimelineTest.java`
- Verify unchanged: `main/java/ai/abandonware/nova/config/LlmRouterProperties.java`

**Interfaces:**
- Consumes: `GuardContextHolder.getOrDefault()`, Task 1 coordinates, Task 3 ledger, and existing `pick` overloads.
- Produces: canonical cold-start/UCB tie selection, coordinate-keyed weighted fallback, and an additive explicit actor/attempt overload while retaining both existing public router overloads.

- [ ] **Step 1: Hash the dirty router preimage and add RED permutation/tie/replay tests**

```java
@Test
void hashMapInsertionOrderDoesNotChangeColdStartOrExactUcbTie() {
    LlmRouterBandit first = banditWithModels("route-b", "route-a");
    LlmRouterBandit second = banditWithModels("route-a", "route-b");
    assertThat(first.pick("llmrouter.auto").key()).isEqualTo("route-a");
    assertThat(second.pick("llmrouter.auto").key()).isEqualTo("route-a");

    seedEqualArmHistory(first, "route-a", "route-b");
    seedEqualArmHistory(second, "route-a", "route-b");
    assertThat(first.pick("llmrouter.auto").key())
            .isEqualTo(second.pick("llmrouter.auto").key());
}

@Test
void sameReplayAndFixedSeedDeckMakeWeightedSelectionRepeatAndExplore() {
    Set<String> selected = new LinkedHashSet<>();
    for (int seedMarker : List.of(0, 17, 41, 93)) {
        byte[] seed = new byte[32];
        seed[0] = (byte) seedMarker;
        String first = withReplay(seed, () -> weightedChoice(weightedBandit()));
        String second = withReplay(seed, () -> weightedChoice(weightedBandit()));
        selected.add(first);
        assertThat(second).isEqualTo(first);
    }
    assertThat(selected).hasSizeGreaterThan(1);
}

private static LlmRouterBandit banditWithModels(String... keys) {
    LlmRouterProperties properties = new LlmRouterProperties();
    Map<String, ModelConfig> models = new LinkedHashMap<>();
    for (String key : keys) {
        ModelConfig config = new ModelConfig();
        config.setEnabled(true);
        config.setName(key);
        config.setProvider("local");
        config.setWeight(1.0d);
        models.put(key, config);
    }
    properties.setEnabled(true);
    properties.setModels(models);
    return new LlmRouterBandit(properties);
}

private static void seedEqualArmHistory(LlmRouterBandit bandit, String... keys) {
    for (String key : keys) bandit.recordOutcome(key, true, 25L);
}

private static LlmRouterBandit weightedBandit() {
    return banditWithModels("route-a", "route-b", "route-c");
}

private static String weightedChoice(LlmRouterBandit bandit) {
    List<LlmRouterBandit.Candidate> candidates = bandit.candidatesForTest();
    return bandit.pickWeightedRandom(candidates, "route:primary", 0L).key();
}

private static <T> T withReplay(byte[] seed, Supplier<T> action) {
    GuardContext context = GuardContext.defaultContext();
    context.attachSelectionEntropy(SelectionEntropyFactory.replay(SelectionReplaySpec.v1(seed)),
            SelectionDecisionLedger.forReplay());
    GuardContextHolder.set(context);
    try { return action.get(); } finally { GuardContextHolder.clear(); }
}
```

Keep nested `Arm` and `Candidate` package-private rather than public. Add package-private `candidatesForTest()` that canonicalizes the configured enabled candidates without changing arm state, and make the three-argument `pickWeightedRandom(List<Candidate>,String,long)` package-private. These seams expose route configs only inside the existing router package tests; no HTTP/trace/UI surface receives them.

Existing disabled-route, cooldown, eligibility, trace redaction, and direct-route tests remain in the same focused run.

In `LlmRouterRequestTimelineTest`, reuse its count-bearing `FakePjp` and request-attempt ledger. Add a test-only bandit whose replay-aware `pick(..., actorKey, attemptOrdinal)` throws `SelectionEntropyException(DERIVATION_INVALID)` after the test has forced the weighted-exploration branch. Assert the exact exception reason, `pjp.proceedCount()==0`, no routed `ChatModel` is returned, and the request-attempt ledger remains empty. This pins that replay derivation failure does not fall through to the factory, gateway, provider, or wire path and does not trigger the missing-OpenAI fallback.

- [ ] **Step 2: Run router tests and confirm RED on permutation equality**

```powershell
.\gradlew.bat test --tests ai.abandonware.nova.orch.router.LlmRouterBanditTraceTest `
  --tests ai.abandonware.nova.orch.aop.LlmRouterRequestTimelineTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: at least the reversed `HashMap` cold-start/tie case fails or the replay-aware test cannot compile.

- [ ] **Step 3: Add one total candidate comparator and canonical ledger key**

```java
private static final Comparator<Candidate> CANDIDATE_ORDER =
        Comparator.comparing(LlmRouterBandit::normalizedModelKey)
                .thenComparing(LlmRouterBandit::normalizedProviderKey)
                .thenComparing(candidate -> normalizeKey(candidate.key));

private static String ledgerKey(Candidate candidate) {
    return normalizedModelKey(candidate) + '\0'
            + normalizedProviderKey(candidate) + '\0'
            + normalizeKey(candidate.key);
}
```

Normalize with trim plus `Locale.ROOT` lowercase. Route key is mandatory; blank key/config and disabled, ineligible, cooldown, non-finite, or non-positive-weight candidates retain current exclusion behavior. Sort the candidate list once after each candidate-collection pass.

- [ ] **Step 4: Make cold-start and exact UCB ties choose the first canonical candidate**

Cold start becomes the first sorted candidate with zero pulls. UCB replacement occurs when score is greater than the best by more than `1.0e-12`, or within `1.0e-12` and `CANDIDATE_ORDER` ranks the candidate first. Record a `SelectionDecisionLedger.Lane.ROUTER` stable-tie decision with `drawConsumed=false` only when at least two exact candidates competed; never draw entropy for the tie.

- [ ] **Step 5: Route only weighted fallback through SelectionEntropy**

Resolve entropy and ledger once from `GuardContextHolder.getOrDefault()`. For zero valid total weight choose the first canonical candidate without a draw and record fallback reason `zero_weight_first_stable`. Otherwise:

```java
SelectionCoordinate coordinate = new SelectionCoordinate(
        "llm-router.weighted-exploration", "route:auto", 0L, 0L);
double unit = entropy.unitInterval(coordinate);
double target = unit * total;
int selectedIndex = weightedIndex(candidates, target);
ledger.record(SelectionDecisionLedger.Lane.ROUTER, coordinate,
        candidates.stream().map(LlmRouterBandit::ledgerKey).toList(),
        selectedIndex, "", true, false);
return selected(candidates.get(selectedIndex).key,
        candidates.get(selectedIndex).cfg, "explore", 0.0d, "");
```

Keep `pick(String)`, `pick(String,RouteEligibilityFilter)`, `recordOutcome`, cooldowns, weights, and eligibility behavior unchanged. A selection exception in replay mode propagates; standard-mode legacy outer fail-soft behavior remains.

Add this overload while keeping both existing overloads delegating to actor `route:primary`, attempt `0`:

```java
public Selected pick(
        String requestedModelId,
        RouteEligibilityFilter eligibilityFilter,
        String actorKey,
        long attemptOrdinal) {
    return pickResolved(requestedModelId, eligibilityFilter, actorKey, attemptOrdinal);
}
```

Use `actorKey` and `attemptOrdinal` in the weighted coordinate. Update only the two aspect calls: the primary route uses `route:primary,0L`; the missing-OpenAI fallback uses `route:fallback,1L`. This makes the known fallback attempt distinct without adding a global counter; the old overloads remain source-compatible for all other callers.

- [ ] **Step 6: Run router tests and confirm GREEN**

```powershell
.\gradlew.bat test --tests ai.abandonware.nova.orch.router.LlmRouterBanditTraceTest `
  --tests ai.abandonware.nova.orch.aop.LlmRouterRequestTimelineTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: insertion permutations, UCB ties, seeded weighted selection, fixed seed deck, disabled/cooldown/eligibility, and existing trace contracts pass.

- [ ] **Step 7: Inspect the two production-file hunks and handle commit authority**

```powershell
git diff --check -- main/java/ai/abandonware/nova/orch/router/LlmRouterBandit.java `
  main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java `
  src/test/java/ai/abandonware/nova/orch/router/LlmRouterBanditTraceTest.java `
  src/test/java/ai/abandonware/nova/orch/aop/LlmRouterRequestTimelineTest.java
Write-Output 'task=6 routerCanonical=pass routerReplay=pass commit=not_authorized'
```

If commit authority is later granted, stage the four Task 6 paths and run:

```powershell
git commit -m "feat: stabilize llm router selection"
```

### Task 7: Separate Strategy Softmax and Epsilon Coordinates

**Files:**
- Modify: `main/java/com/example/lms/strategy/StrategySelectorService.java:44-113`
- Create: `src/test/java/com/example/lms/strategy/StrategySelectorServiceTest.java`
- Modify: `src/test/java/com/example/lms/strategy/RetrievalOrderServiceTest.java`
- Verify unchanged: `main/java/com/example/lms/strategy/RetrievalOrderService.java`

**Interfaces:**
- Consumes: existing `Strategy` enum/properties, GuardContext entropy/ledger, and fixed retrieval mode in `RetrievalOrderService`.
- Produces: canonical dynamic strategy selection with distinct `strategy.softmax`, `strategy.epsilon-branch`, and `strategy.epsilon-index` coordinates.

- [ ] **Step 1: Write RED tests for fixed zero-draw, replay, invalid distributions, and permutations**

```java
@Test
void sameReplayRepeatsSoftmaxAndEpsilonAcrossRowPermutations() {
    byte[] seed = fixedSeed(29);
    Strategy first = selectWith(seed, rows("VECTOR_FIRST", "WEB_FIRST", "WEB_VECTOR_FUSION"));
    Strategy second = selectWith(seed, rows("WEB_VECTOR_FUSION", "WEB_FIRST", "VECTOR_FIRST"));
    assertThat(second).isEqualTo(first);
}

@Test
void invalidDistributionUsesFirstStableStrategyWithoutRandomFallback() {
    Strategy selected = selectWithInvalidStats(fixedSeed(7));
    assertThat(selected).isEqualTo(Strategy.DEEP_DIVE_SELF_ASK);
    assertThat(lastLedger.snapshot(true).reason())
            .isEqualTo(SelectionEntropyReason.NONE);
}

private SelectionDecisionLedger lastLedger;

private static byte[] fixedSeed(int marker) {
    byte[] seed = new byte[32];
    seed[0] = (byte) marker;
    return seed;
}

private static StatsRow row(String name, long success, long failure, double reward) {
    StatsRow row = mock(StatsRow.class);
    when(row.getStrategyName()).thenReturn(name);
    when(row.getSuccess()).thenReturn(success);
    when(row.getFailure()).thenReturn(failure);
    when(row.getReward()).thenReturn(reward);
    return row;
}

private static List<StatsRow> rows(String... names) {
    return Arrays.stream(names).map(name -> row(name, 3L, 1L, 0.6d)).toList();
}

private Strategy selectWith(byte[] seed, List<StatsRow> rows) {
    QueryComplexityGate gate = mock(QueryComplexityGate.class);
    when(gate.assess(anyString())).thenReturn(QueryComplexityGate.Level.COMPLEX);
    StrategyPerformanceRepository repository = mock(StrategyPerformanceRepository.class);
    when(repository.findStatsByCategory("default")).thenReturn(rows);
    StrategyHyperparams hyper = mock(StrategyHyperparams.class);
    when(hyper.temperature()).thenReturn(1.0d);
    when(hyper.epsilon()).thenReturn(0.25d);
    HyperparameterService hp = mock(HyperparameterService.class);
    when(hp.getDouble("strategy.prior.base", 0.10d)).thenReturn(0.10d);
    when(hp.getDouble("strategy.weight.success_rate", 0.65d)).thenReturn(0.65d);
    when(hp.getDouble("strategy.weight.reward", 0.30d)).thenReturn(0.30d);
    when(hp.getPositiveDouble("strategy.temperature", 1.0d)).thenReturn(1.0d);
    when(hp.getDoubleInRange01("strategy.epsilon", 0.25d)).thenReturn(0.25d);
    GuardContext context = GuardContext.defaultContext();
    lastLedger = SelectionDecisionLedger.forReplay();
    context.attachSelectionEntropy(SelectionEntropyFactory.replay(SelectionReplaySpec.v1(seed)), lastLedger);
    GuardContextHolder.set(context);
    try {
        return new StrategySelectorService(gate, repository, hyper, hp)
                .selectForQuestion("fixed question", null);
    } finally {
        GuardContextHolder.clear();
    }
}

private Strategy selectWithInvalidStats(byte[] seed) {
    return selectWith(seed, List.of(
            row("VECTOR_FIRST", 0L, 0L, Double.NaN),
            row("DEEP_DIVE_SELF_ASK", 0L, 0L, Double.NaN)));
}
```

In `RetrievalOrderServiceTest`, install replay, execute fixed mode, assert the returned fixed order is unchanged and ledger `drawCount()==0`. Add an epsilon-zero case proving only softmax consumes a draw and an epsilon-one case proving branch and bounded index use their own coordinates.

- [ ] **Step 2: Run strategy tests and confirm RED**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.strategy.StrategySelectorServiceTest `
  --tests com.example.lms.strategy.RetrievalOrderServiceTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: replay tests cannot compile or insertion permutations expose the current stochastic path; fixed-mode existing behavior remains characterized.

- [ ] **Step 3: Canonicalize strategies before probability construction**

Build `order` from valid `Strategy` keys sorted by `Strategy.name()`. Exclude non-finite logits/probabilities. If no valid row remains, return the existing complexity-derived base without a draw. If all valid probability mass is zero or non-finite, choose `order.get(0)` and record `zero_weight_first_stable` without entropy.

- [ ] **Step 4: Replace the three direct random calls with separate coordinates**

```java
GuardContext context = GuardContextHolder.getOrDefault();
SelectionEntropy entropy = context.selectionEntropy();
SelectionDecisionLedger ledger = context.selectionDecisionLedger();
List<String> keys = order.stream().map(value -> value.name().toLowerCase(Locale.ROOT)).toList();

SelectionCoordinate softmaxCoordinate = new SelectionCoordinate(
        "strategy.softmax", "strategy:dynamic", 0L, 0L);
int pickedIndex = rouletteIndex(probs, entropy.unitInterval(softmaxCoordinate));
ledger.record(SelectionDecisionLedger.Lane.STRATEGY,
        softmaxCoordinate, keys, pickedIndex, "", true, false);

SelectionCoordinate branchCoordinate = new SelectionCoordinate(
        "strategy.epsilon-branch", "strategy:dynamic", 0L, 0L);
boolean explore = entropy.unitInterval(branchCoordinate) < eps;
ledger.record(SelectionDecisionLedger.Lane.STRATEGY,
        branchCoordinate, List.of("exploit", "explore"),
        explore ? 1 : 0, "", true, false);
if (!explore) return order.get(pickedIndex);

List<Strategy> all = Arrays.stream(Strategy.values()).sorted(Comparator.comparing(Enum::name)).toList();
SelectionCoordinate indexCoordinate = new SelectionCoordinate(
        "strategy.epsilon-index", "strategy:dynamic", 0L, 0L);
int index = entropy.boundedIndex(indexCoordinate, all.size());
ledger.record(SelectionDecisionLedger.Lane.STRATEGY, indexCoordinate,
        all.stream().map(value -> value.name().toLowerCase(Locale.ROOT)).toList(),
        index, "", true, false);
return all.get(index);
```

Preserve `selectForQuestion(String,ChatRequestDto)`, temperature/epsilon property access, complexity base, performance scoring, and null DTO compatibility. Do not put replay state in `ChatRequestDto`.

- [ ] **Step 5: Run focused strategy tests and confirm GREEN**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.strategy.StrategySelectorServiceTest `
  --tests com.example.lms.strategy.RetrievalOrderServiceTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: fixed zero-draw, same-seed, permutation, epsilon, invalid-distribution, and existing retrieval-order tests pass.

- [ ] **Step 6: Inspect owned hunks and handle commit authority**

```powershell
git diff --check -- main/java/com/example/lms/strategy/StrategySelectorService.java `
  src/test/java/com/example/lms/strategy/StrategySelectorServiceTest.java `
  src/test/java/com/example/lms/strategy/RetrievalOrderServiceTest.java
Write-Output 'task=7 strategyReplay=pass fixedModeDraws=0 commit=not_authorized'
```

If commit authority is later granted, stage the three Task 7 paths and run:

```powershell
git commit -m "feat: replay dynamic strategy selection"
```

### Task 8: Route Ensemble Profiles and Shuffle Through the Common Boundary

**Files:**
- Modify: `main/java/com/example/lms/ensemble/StochasticParamSampler.java:18-164`
- Modify: `main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java:191-540`
- Modify: `src/test/java/com/example/lms/ensemble/StochasticParamSamplerTest.java`
- Modify: `src/test/java/com/example/lms/ensemble/DiverseSamplingOrchestratorTest.java`

**Interfaces:**
- Consumes: GuardContext entropy/ledger, existing `DoubleSupplier` test constructor, and existing `ContextPropagation.wrapCallable`.
- Produces: distinct profile/shuffle coordinates, stable node actors, and same-reference worker propagation while retaining every public sampler/orchestrator overload.

- [ ] **Step 1: Hash both dirty production preimages and write RED sampler replay tests**

```java
@Test
void sameReplayRepeatsProfileAndPillCompositionAcrossFreshSamplers() {
    byte[] seed = fixedSeed(13);
    DrawResult first = withReplay(seed, () -> new StochasticParamSampler().draw("trace-a"));
    DrawResult second = withReplay(seed, () -> new StochasticParamSampler().draw("trace-b"));
    assertThat(second).isEqualTo(first);
}

@Test
void injectedDoubleSupplierRemainsTheCompatibilityEntropySource() {
    ArrayDeque<Double> values = new ArrayDeque<>(List.of(
            0.10d, 0.90d, 0.20d, 0.80d, 0.30d, 0.70d,
            0.40d, 0.60d, 0.05d, 0.95d, 0.15d, 0.85d,
            0.25d, 0.75d, 0.35d, 0.65d, 0.45d, 0.55d,
            0.12d, 0.88d, 0.22d, 0.78d, 0.32d, 0.68d,
            0.42d, 0.58d, 0.52d, 0.48d, 0.62d, 0.38d));
    StochasticParamSampler sampler = new StochasticParamSampler(values::removeFirst);
    DrawResult first = sampler.draw("compatibility-a");
    assertThat(first.caffeine()).isBetween(0, 3);
    assertThat(first.theanine()).isEqualTo(3 - first.caffeine());
    assertThat(values).hasSizeLessThan(30);
}

@Test
void profileFieldsUseDistinctCoordinatesAndRemainWithinExistingBounds() {
    withReplay(fixedSeed(51), () -> {
        GuardContext context = GuardContextHolder.get();
        assertThat(new StochasticParamSampler().applyCreativeProfile(context)).isTrue();
        assertThat((Double) context.getPlanOverride("creative.emergence.candidate.temperature"))
                .isBetween(1.10d, 1.50d);
        assertThat((Double) context.getPlanOverride("creative.emergence.final.topP"))
                .isBetween(0.95d, 1.00d);
        assertThat(context.selectionDecisionLedger().snapshot(true).ensembleDrawCount())
                .isGreaterThanOrEqualTo(8);
    });
}

private static byte[] fixedSeed(int marker) {
    byte[] seed = new byte[32];
    seed[0] = (byte) marker;
    return seed;
}

private static <T> T withReplay(byte[] seed, Supplier<T> action) {
    GuardContext context = GuardContext.defaultContext();
    context.attachSelectionEntropy(SelectionEntropyFactory.replay(SelectionReplaySpec.v1(seed)),
            SelectionDecisionLedger.forReplay());
    GuardContextHolder.set(context);
    try { return action.get(); } finally { GuardContextHolder.clear(); }
}

private static void withReplay(byte[] seed, Runnable action) {
    withReplay(seed, () -> { action.run(); return Boolean.TRUE; });
}
```

Use the real existing `GuardContext` plan-override getter signature from the live preimage when writing the test; keep seed material as byte arrays and never stringify it.

- [ ] **Step 2: Add RED orchestration tests for worker propagation and completion-order independence**

Add a fixed executor fixture that completes nodes in forward and reverse order. Assert identical selected profile/parameters and node-spec-ordered successes, while preserving the existing test that the first observed malformed/terminal failure cancels outstanding work. Also assert trace metadata says `completionOrderDeterministic=false`.

Add one fail-before-provider case using the test class's existing `AtomicInteger providerChats` fixture. Attach a test `SelectionEntropy` whose first profile/shuffle derivation throws `SelectionEntropyException(DERIVATION_INVALID)`, invoke the enabled sampling path, and assert the same reason propagates, `providerChats.get()==0`, `ensemble.sampling.modelCallCount==0`, and no fallback sampler or retry is invoked. This complements the ingress zero-call tests by pinning a failure that occurs after request admission but before fan-out.

- [ ] **Step 3: Run ensemble tests and confirm RED**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.ensemble.StochasticParamSamplerTest `
  --tests com.example.lms.ensemble.DiverseSamplingOrchestratorTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: new replay/coordinate/worker assertions fail while current clamp and first-observed characterization remains visible.

- [ ] **Step 4: Preserve constructors and adapt `DoubleSupplier` to `SelectionEntropy`**

Keep `public StochasticParamSampler()` and package-private `StochasticParamSampler(DoubleSupplier)`. Internally store a fallback `SelectionEntropy`: the default uses `SelectionEntropyFactory.standard()`; the test constructor uses a private adapter whose `unitInterval` consumes one validated supplier value and whose `boundedIndex` maps `floor(unit * bound)` after validating `0 <= unit < 1`. Every production draw first uses the explicitly attached GuardContext entropy and otherwise uses this fallback adapter.

- [ ] **Step 5: Give profile components and shuffle independent coordinates**

Use actor `profile:creative-emergence` with decision keys:

```text
ensemble.profile.selector
ensemble.profile.search-temperature
ensemble.profile.search-rate
ensemble.profile.candidate-temperature
ensemble.profile.candidate-top-p
ensemble.profile.final-temperature
ensemble.profile.final-top-p
ensemble.profile.self-ask-temperature
```

Use actor `node:opportunistic` and decision key `ensemble.profile.shuffle`; Fisher-Yates draw ordinal is `pool.length - 1 - i`. Record each draw against canonical non-sensitive slot/profile keys. Preserve current label thresholds, value bounds, two-decimal quantization, pill composition mapping, trace hash, and `requestedOptionsHash` behavior.

- [ ] **Step 6: Resolve once before fan-out and wrap every worker with existing propagation**

At the start of the private `sample(...)` after feature/cancellation gates:

```java
GuardContext selectionContext = GuardContextHolder.getOrDefault();
SelectionEntropy selectionEntropy = selectionContext.selectionEntropy();
SelectionDecisionLedger selectionLedger = selectionContext.selectionDecisionLedger();
```

Pass those exact references to the sampler overload with stable actor `node:opportunistic`. Change completion submission only by wrapping the existing callable:

```java
Future<SampledCandidate> future = completionService.submit(samplingLease.track(
        ContextPropagation.wrapCallable(() -> prepared == null
                ? runNodeWithTraceContext(spec, ctx, workerTraceContext,
                        modelTimeoutSeconds, modelCallGate)
                : runPreparedNodeWithTraceContext(prepared, workerTraceContext, modelCallGate))));
```

Do not replace `ExecutorCompletionService`, reorder terminal handling, change time budgets, add provider calls, or claim deterministic completion order.

- [ ] **Step 7: Run focused ensemble tests and confirm GREEN**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.ensemble.StochasticParamSamplerTest `
  --tests com.example.lms.ensemble.DiverseSamplingOrchestratorTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: profile/pill replay, independent nodes, injected supplier compatibility, clamps, reverse completion order, first-observed terminal, cancellation, budget, and provider-evidence tests pass.

- [ ] **Step 8: Inspect owned hunks and handle commit authority**

```powershell
git diff --check -- main/java/com/example/lms/ensemble/StochasticParamSampler.java `
  main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java `
  src/test/java/com/example/lms/ensemble/StochasticParamSamplerTest.java `
  src/test/java/com/example/lms/ensemble/DiverseSamplingOrchestratorTest.java
Write-Output 'task=8 ensembleReplay=pass firstObservedSemantics=preserved commit=not_authorized'
```

If commit authority is later granted, stage exactly those four paths and run:

```powershell
git commit -m "feat: replay ensemble selection draws"
```

### Task 9: Make RRF and DPP Exact Ties Permutation-Equivalent

**Files:**
- Modify: `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java:153-163,2546-2588,2716-2780`
- Modify: `main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java:44-138`
- Modify: `src/test/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestratorRagEvalTest.java`
- Modify: `src/test/java/com/example/lms/service/rag/rerank/DppDiversityRerankerTest.java`

**Interfaces:**
- Consumes: `UnifiedRagOrchestrator.Doc`, current RRF scores/source cap, current DPP relevance/diversity formulas, and Task 3 ranking ledger.
- Produces: a stable document key priority and an additive DPP overload with a stable-key extractor; all existing constructors and rerank/select overloads remain valid.

- [ ] **Step 1: Hash the dirty orchestrator preimage and write RED RRF permutation tests**

```java
@Test
void exactRrfTiesFollowStableDocumentKeyAcrossInputPermutations() {
    List<Doc> abc = List.of(
            doc("doc-c", "https://example.test/c", "same", "WEB", 0.5d, 1),
            doc("doc-a", "https://example.test/a", "same", "VECTOR", 0.5d, 1),
            doc("doc-b", "https://example.test/b", "same", "BM25", 0.5d, 1));
    List<Doc> cba = List.of(abc.get(2), abc.get(1), abc.get(0));

    assertThat(runSeedOnly(abc).results.stream().map(doc -> doc.id).toList())
            .isEqualTo(runSeedOnly(cba).results.stream().map(doc -> doc.id).toList())
            .containsExactly("doc-a", "doc-b", "doc-c");
}

@Test
void stableKeyFallsBackFromIdToCanonicalUrlSourceAndContentHash() {
    Doc explicit = doc("explicit", null, "body-a", "WEB", 0.5d, 1);
    Doc url = doc(null, "HTTPS://Example.Test/a/../b#fragment", "body-b", "WEB", 0.5d, 1);
    Doc content = doc(null, null, "  SAME\nCONTENT  ", null, 0.5d, 1);
    assertThat(stableKeyForTest(explicit)).startsWith("id:");
    assertThat(stableKeyForTest(url)).isEqualTo("url:https://example.test/b");
    assertThat(stableKeyForTest(content)).startsWith("content:").hasSize(72);
    assertThat(stableKeyForTest(doc(null, null, "", null, 0.5d, 1))).isNull();
}

private static Doc doc(String id, String url, String text, String source, double score, int rank) {
    Doc doc = new Doc();
    doc.id = id;
    doc.title = text;
    doc.snippet = "";
    doc.source = source;
    doc.score = score;
    doc.rank = rank;
    doc.meta = url == null ? new LinkedHashMap<>() : new LinkedHashMap<>(Map.of("url", url));
    return doc;
}

private static QueryResponse runSeedOnly(List<Doc> documents) {
    UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
    QueryRequest request = new QueryRequest();
    request.query = "fixed ranking fixture";
    request.seedOnly = true;
    request.seedMode = "candidates";
    request.seedCandidates = documents;
    request.topK = documents.size();
    request.useWeb = false;
    request.useVector = false;
    request.useKg = false;
    request.useBm25 = false;
    request.enableOnnx = false;
    request.enableBiEncoder = false;
    request.enableDiversity = false;
    return orchestrator.query(request);
}

private static String stableKeyForTest(Doc document) {
    return UnifiedRagOrchestrator.stableDocumentKeyForTest(document);
}
```

Expose `stableDocumentKeyForTest(Doc)` package-private only if the current test package cannot reach a private helper through the seed-only result contract. Never put the returned raw key in trace or UI.

- [ ] **Step 2: Write RED DPP determinant-tie and empty-candidate tests**

```java
@Test
void determinantTiesUseStableKeysNotArrivalOrder() {
    List<Item> forward = List.of(item("c", "same", 0.8d), item("a", "same", 0.8d), item("b", "same", 0.8d));
    List<Item> reverse = List.of(forward.get(2), forward.get(1), forward.get(0));
    List<String> first = reranker.rerank(config, forward, "q", 3,
            Item::text, Item::relevance, Item::id).stream().map(Item::id).toList();
    List<String> second = reranker.rerank(config, reverse, "q", 3,
            Item::text, Item::relevance, Item::id).stream().map(Item::id).toList();
    assertThat(first).isEqualTo(second).containsExactly("a", "b", "c");
}

@Test
void candidatesWithoutAnyRecoverableStableKeyAreExcluded() {
    List<Item> result = reranker.rerank(config,
            List.of(item("", "", 0.9d), item("kept", "body", 0.8d)),
            "q", 2, Item::text, Item::relevance, Item::id);
    assertThat(result).extracting(Item::id).containsExactly("kept");
}

private final DppDiversityReranker reranker = new DppDiversityReranker();
private final DppDiversityReranker.Config config =
        new DppDiversityReranker.Config(0.7d, 3);

private record Item(String id, String text, double relevance) {}

private static Item item(String id, String text, double relevance) {
    return new Item(id, text, relevance);
}
```

- [ ] **Step 3: Run RRF/DPP tests and confirm RED**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.service.rag.orchestrator.UnifiedRagOrchestratorRagEvalTest `
  --tests com.example.lms.service.rag.rerank.DppDiversityRerankerTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: permutation tests expose score-only/input-order tie behavior and the new DPP overload is absent.

- [ ] **Step 4: Implement stable document key priority with no public raw key**

`UnifiedRagOrchestrator.stableDocumentKey(Doc)` returns:

1. `id:` plus trimmed explicit `doc.id` when nonblank.
2. `url:` plus a normalized URI from metadata keys `url`, `URL`, `sourceUrl`, `source_url`, `link`, `href`, `canonical`, `permalink`: lowercase scheme/host, normalized path, original query, no fragment.
3. `source:` plus trimmed/lowercased metadata `sourceId`, `source_id`, `documentId`, or `docId`, prefixed by normalized `doc.source` when available.
4. `content:` plus lowercase SHA-256 of NFKC-normalized, whitespace-collapsed, `Locale.ROOT`-lowercased title and snippet.
5. `null` when all fields are empty.

Use length-aware internal composition rather than a trace-visible delimiter. Filter null-key documents before RRF and keep a local identity map from `Doc` to key. Sort descending RRF score, then ascending stable key. Record exact-score tie count through `SelectionDecisionLedger.Lane.RANKING` with `drawConsumed=false` and `stableTieBreak=true`; do not expose the key.

- [ ] **Step 5: Add a compatible stable-key DPP overload and tie comparator**

Keep every current constructor and overload. Add only:

```java
public <T> List<T> rerank(
        Config callConfig,
        List<T> in,
        String query,
        int k,
        Function<? super T, String> textOf,
        ToDoubleFunction<? super T> relevanceOf,
        Function<? super T, String> stableKeyOf) {
    return rerankInternal(callConfig, in, query, k, textOf, relevanceOf, stableKeyOf);
}
```

Existing overloads delegate with a default stable key equal to lowercase SHA-256 of normalized extracted text. In the active orchestrator call, pass `UnifiedRagOrchestrator::stableDocumentKey`. Before relevance/kernel construction, exclude blank keys and sort by stable key. During greedy selection replace `best` when score exceeds `bestScore + 1.0e-12`, or when `abs(score-bestScore) <= 1.0e-12` and the candidate key is lexicographically smaller. Keep determinant, relevance, Jaccard shingles, lambda, source cap, and diversity trace formulas unchanged.

- [ ] **Step 6: Run RRF/DPP and bridge regressions and confirm GREEN**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.service.rag.orchestrator.UnifiedRagOrchestratorRagEvalTest `
  --tests com.example.lms.service.rag.rerank.DppDiversityRerankerTest `
  --tests '*NovaNextFusion*' `
  --tests '*TailWeighted*' `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: RRF/DPP permutations, stable-key priority, empty exclusion, source caps, diversity formula, and existing Nova fusion ID ordering pass; no second Nova comparator is added.

- [ ] **Step 7: Inspect owned hunks and handle commit authority**

```powershell
git diff --check -- main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java `
  main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java `
  src/test/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestratorRagEvalTest.java `
  src/test/java/com/example/lms/service/rag/rerank/DppDiversityRerankerTest.java
Write-Output 'task=9 rrfPermutation=pass dppPermutation=pass commit=not_authorized'
```

If commit authority is later granted, stage those four paths and run:

```powershell
git commit -m "feat: stabilize rag ranking ties"
```

### Task 10: Project One Safe Shape into Trace, Snapshot, Agent Evidence, Sync DTO, and Typed SSE

**Files:**
- Create: `main/java/com/example/lms/trace/SelectionEntropyTraceSupport.java`
- Modify: `main/java/com/example/lms/trace/TraceSnapshotStore.java:priorityTraceKeys(),sanitizeTrace(...)`
- Modify: `main/java/com/example/lms/service/AgentVisibleDebugEvidenceBuilder.java:94-124,273-304,324-330,471-525,1464-1502`
- Modify: `main/java/com/example/lms/dto/ChatResponseDto.java:8-83`
- Modify: `main/java/com/example/lms/dto/ChatStreamEvent.java:18-196`
- Modify: `main/java/com/example/lms/api/ChatStreamSignalBuilder.java:24-120`
- Modify: `main/java/com/example/lms/api/ChatApiController.java:1695-1718,2275-2412,2592-2677,3999-4106`
- Create: `src/test/java/com/example/lms/trace/TraceSnapshotSelectionEntropyTest.java`
- Modify: `src/test/java/com/example/lms/service/AgentVisibleDebugEvidenceBuilderTest.java`
- Modify: `src/test/java/com/example/lms/dto/ChatResponseDtoLearningContextTest.java`
- Modify: `src/test/java/com/example/lms/dto/ChatEvidenceMetadataDtoTest.java`
- Modify: `src/test/java/com/example/lms/api/ChatStreamSignalBuilderTest.java`
- Modify: `src/test/java/com/example/lms/api/ChatApiControllerTraceMetaTest.java`

**Interfaces:**
- Consumes: Task 3 `SelectionEntropyProjection`, existing `TraceStore.put/getAll`, existing snapshot sanitizer, and current DTO/static SSE factories.
- Produces: exact flat trace keys, fail-closed sanitization, explicit debug fields without fingerprint, additive nullable sync serialization, and `selection_entropy` SSE events.

- [ ] **Step 1: Write RED trace and sanitizer positive/negative tests**

```java
@Test
void snapshotAllowsOnlyExactTypedEntropyKeys() {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("selectionEntropy.schema", "awx.selection-entropy.v1");
    trace.put("selectionEntropy.mode", "replay");
    trace.put("selectionEntropy.seedFingerprint", "009e8892e5b3");
    trace.put("selectionEntropy.decisionCount", 4);
    trace.put("selectionEntropy.completionOrderDeterministic", false);
    trace.put("selectionEntropy.seed", "sentinel-seed-material");
    trace.put("selectionEntropy.unknown", "authorization-shaped-value");
    trace.put("selectionEntropy.drawCount", "4");

    Map<String, Object> safe = capture(trace).trace();
    assertThat(safe).containsEntry("selectionEntropy.mode", "replay")
            .containsEntry("selectionEntropy.decisionCount", 4)
            .containsEntry("selectionEntropy.completionOrderDeterministic", false);
    assertThat(safe).doesNotContainKeys(
            "selectionEntropy.seed", "selectionEntropy.unknown", "selectionEntropy.drawCount");
    assertThat(safe.toString()).doesNotContain("sentinel-seed-material", "authorization-shaped-value");
}
```

Add negative cases for malformed mode/coherence, 11/13-char fingerprint, uppercase/63-char digest, negative/10,001/string counts, string boolean, raw header/token-like keys, and unknown reason.

- [ ] **Step 2: Write RED DTO/SSE builder tests with one shared safe value set**

```java
SelectionEntropyProjection projection = new SelectionEntropyProjection(
        "awx.selection-entropy.v1", "replay", "selection-entropy-v1", true,
        "matched", "009e8892e5b3",
        "7b4cfe15f9aaf721fe73133247ced66ecb89e14dbe1a6a59c0bb33aecc3dc349",
        5, 4, 2, 0, 1, 1, 2, false, "");

@Test
void oldResponseConstructorsRemainNullAndFullConstructorCarriesTypedProjection() {
    assertThat(new ChatResponseDto("ok", 1L, "model", false).getSelectionEntropy()).isNull();
    ChatResponseDto response = responseWith(projection);
    assertThat(response.getSelectionEntropy()).isEqualTo(projection);
    assertThat(objectMapper.writeValueAsString(response))
            .contains("\"selectionEntropy\"")
            .doesNotContain("seedMaterial", "X-AWX-Selection-Replay", "X-Owner-Token");
}

@Test
void buildsTypedSseSignalAndRejectsMapLikeUnknowns() {
    ChatStreamEvent.SelectionEntropySignal signal =
            ChatStreamSignalBuilder.buildSelectionEntropySignal(traceMap(projection));
    assertThat(signal.mode()).isEqualTo("replay");
    assertThat(signal.coherenceStatus()).isEqualTo("matched");
    assertThat(signal.completionOrderDeterministic()).isFalse();
    assertThat(ChatStreamEvent.selectionEntropy(signal).type()).isEqualTo("selection_entropy");
}

private static TraceSnapshotStore.TraceSnapshot capture(Map<String, Object> trace) {
    @SuppressWarnings("unchecked")
    ObjectProvider<TraceHtmlBuilder> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    TraceSnapshotStore store = new TraceSnapshotStore(provider);
    String id = store.captureCustom("selection_entropy_test", "POST", "/api/chat",
            200, null, trace, null);
    return store.get(id).orElseThrow();
}

private static ChatResponseDto responseWith(SelectionEntropyProjection projection) {
    return new ChatResponseDto("ok", 1L, "model", false, "DIRECT", 1L,
            LearningContextMetadata.empty(), List.of(), null, projection);
}

private static Map<String, Object> traceMap(SelectionEntropyProjection projection) {
    TraceStore.clear();
    try {
        SelectionEntropyTraceSupport.write(projection);
        return TraceStore.getAll();
    } finally {
        TraceStore.clear();
    }
}
```

`ChatApiControllerTraceMetaTest` must pin that the final sync projection is built after final entropy trace enrichment and before the ten-argument `new ChatResponseDto(...)`; the stream test pins one early accepted/not-requested event and one terminal matched/partial/failed event before `done`/terminal close.

- [ ] **Step 3: Run trace/DTO/SSE tests and confirm RED**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.trace.TraceSnapshotSelectionEntropyTest `
  --tests com.example.lms.service.AgentVisibleDebugEvidenceBuilderTest `
  --tests com.example.lms.dto.ChatResponseDtoLearningContextTest `
  --tests com.example.lms.dto.ChatEvidenceMetadataDtoTest `
  --tests com.example.lms.api.ChatStreamSignalBuilderTest `
  --tests com.example.lms.api.ChatApiControllerTraceMetaTest `
  --tests com.example.lms.LmsApplicationContextLoadsTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: new trace keys/projection/DTO component/SSE type are absent.

- [ ] **Step 4: Implement exact trace write/read/type allowlists in one support class**

`SelectionEntropyTraceSupport` owns the exact 16 keys from the spec and no wildcard prefix acceptance:

```java
public static void write(SelectionEntropyProjection value) {
    TraceStore.put("selectionEntropy.schema", value.schema());
    TraceStore.put("selectionEntropy.mode", value.mode());
    TraceStore.put("selectionEntropy.algorithmVersion", value.algorithmVersion());
    TraceStore.put("selectionEntropy.replayAccepted", value.replayAccepted());
    TraceStore.put("selectionEntropy.coherenceStatus", value.coherenceStatus());
    TraceStore.put("selectionEntropy.seedFingerprint", value.seedFingerprint());
    TraceStore.put("selectionEntropy.decisionDigest", value.decisionDigest());
    TraceStore.put("selectionEntropy.decisionCount", value.decisionCount());
    TraceStore.put("selectionEntropy.drawCount", value.drawCount());
    TraceStore.put("selectionEntropy.stableTieBreakCount", value.stableTieBreakCount());
    TraceStore.put("selectionEntropy.candidateDriftCount", value.candidateDriftCount());
    TraceStore.put("selectionEntropy.routerDrawCount", value.routerDrawCount());
    TraceStore.put("selectionEntropy.strategyDrawCount", value.strategyDrawCount());
    TraceStore.put("selectionEntropy.ensembleDrawCount", value.ensembleDrawCount());
    TraceStore.put("selectionEntropy.completionOrderDeterministic", value.completionOrderDeterministic());
    TraceStore.put("selectionEntropy.reasonCode", value.reasonCode());
}
```

Also provide `Optional<SelectionEntropyProjection> fromTrace(Map<String,Object>)` and `Object sanitizeExactValue(String,Object)`. Each field checks its exact Java type and closed range/regex; any missing required field or invalid value returns `Optional.empty()`. A null replay-only fingerprint is allowed. Unknown `selectionEntropy.*` keys return null. Do not call `putInternal` with raw seed data.

- [ ] **Step 5: Make `TraceSnapshotStore` fail closed for the entire entropy prefix**

Add only the exact public keys to `priorityTraceKeys()`. At the start of the existing `putSanitizedTraceEntry(Map<String,Object> out, String key, Object value)` helper, before generic sanitization:

```java
if (key != null && key.startsWith("selectionEntropy.")) {
    Object safe = SelectionEntropyTraceSupport.sanitizeExactValue(key, value);
    if (safe != null) {
        out.put(safeTraceKey(key), safe);
    }
    return;
}
```

Both the priority and residual iterations already call this helper, so this one branch covers both paths. An unknown or mistyped entropy key therefore returns without reaching `SafeRedactor.diagnosticValue` as a generic string.

- [ ] **Step 6: Add an explicitly requested, fingerprint-free agent breadcrumb**

Extend `AgentVisibleDebugEvidenceBuilder.Field` with:

```text
SELECTION_ENTROPY_MODE
SELECTION_ENTROPY_ALGORITHM
SELECTION_ENTROPY_COHERENCE
SELECTION_ENTROPY_DECISION_COUNT
SELECTION_ENTROPY_DRAW_COUNT
SELECTION_ENTROPY_STABLE_TIE_COUNT
SELECTION_ENTROPY_CANDIDATE_DRIFT_COUNT
SELECTION_ENTROPY_ROUTER_DRAW_COUNT
SELECTION_ENTROPY_STRATEGY_DRAW_COUNT
SELECTION_ENTROPY_ENSEMBLE_DRAW_COUNT
SELECTION_ENTROPY_COMPLETION_ORDER
SELECTION_ENTROPY_REASON
```

Read only exact safe fields from `TraceStore.getAll()` through `SelectionEntropyTraceSupport.fromTrace`. Append them to the heartbeat and typed `Snapshot` only when the existing `isDebugEvidenceQuery(query)` gate is true. Never add seed fingerprint or decision digest to this agent projection, and never inject it for an ordinary question.

- [ ] **Step 7: Add one non-breaking nullable sync field**

In `ChatResponseDto`:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
private final SelectionEntropyProjection selectionEntropy;
```

Keep all existing 4/5/6/7/8/9-argument constructors and chain them to a new ten-argument terminal constructor with `selectionEntropy=null`. The terminal constructor stores the already validated immutable projection. At final `/api/chat` assembly, create the terminal projection directly from the request entropy/ledger before the response constructor and pass it as argument ten. Header-absent standard responses may leave the field null to preserve existing JSON; accepted replay responses carry it. Do not read `TraceStore` after its existing `clear()`; use the typed request state/ledger and already captured `extraMeta`.

- [ ] **Step 8: Add a compatible typed SSE component and builder**

Append nullable `SelectionEntropySignal selectionEntropySignal` as the last `ChatStreamEvent` record component. Add a delegating constructor with the exact old 16 arguments and `this(..., transformerBlocks, null)` so existing direct construction remains source-compatible. Add:

```java
public record SelectionEntropySignal(
        String schema, String mode, String algorithmVersion, Boolean replayAccepted,
        String coherenceStatus, String replayReference, String decisionDigest,
        Integer decisionCount, Integer drawCount, Integer stableTieBreakCount,
        Integer candidateDriftCount, Integer routerDrawCount, Integer strategyDrawCount,
        Integer ensembleDrawCount, Boolean completionOrderDeterministic, String reasonCode) {}

public static ChatStreamEvent selectionEntropy(SelectionEntropySignal signal) {
    return new ChatStreamEvent("selection_entropy", null, null, null, null, null,
            null, null, null, List.of(), null, null, null, null, null, List.of(), signal);
}
```

Every existing static factory passes null for the new component. `ChatStreamSignalBuilder.buildSelectionEntropySignal(Map<String,Object>)` first calls the exact trace parser and maps only the validated projection; `seedFingerprint` becomes UI-facing field name `replayReference` and remains 12 lowercase hex.

- [ ] **Step 9: Emit early and terminal projections without changing stream terminal meaning**

After sync/stream context attach, write an early standard `not_requested` or replay `accepted` projection. Before sync `extraMeta` capture and before stream `done`, write and emit the terminal projection. On candidate drift/cap, emit `partial`; on selection failure, call `ledger.markFailure(reason)`, emit `failed`, then retain existing HTTP/SSE terminal error behavior. On cancel, emit the current projection plus the separate existing cancelled status. Pass `completionOrderDeterministic=false` to every v1 projection path—standard, accepted, matched, partial, failed, and cancelled—because the field describes completion scheduling rather than whether ensemble happened to run. Extend the controller test to assert `false` on both early and terminal sync/SSE projections. Never turn a delivered SSE event into a provider-success claim.

- [ ] **Step 10: Run all focused trace/DTO/SSE tests and confirm GREEN**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.trace.TraceSnapshotSelectionEntropyTest `
  --tests com.example.lms.service.AgentVisibleDebugEvidenceBuilderTest `
  --tests com.example.lms.dto.ChatResponseDtoLearningContextTest `
  --tests com.example.lms.dto.ChatEvidenceMetadataDtoTest `
  --tests com.example.lms.api.ChatStreamSignalBuilderTest `
  --tests com.example.lms.api.ChatApiControllerTraceMetaTest `
  --tests com.example.lms.api.ChatApiControllerSelectionReplayTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: positive and negative sanitizer cases, agent projection, old constructor compatibility, nullable sync JSON, typed SSE, early/final lifecycle, cancel/error separation, and raw sentinel absence pass.

- [ ] **Step 11: Inspect owned hunks and handle commit authority**

```powershell
git diff --check -- main/java/com/example/lms/trace/SelectionEntropyTraceSupport.java `
  main/java/com/example/lms/trace/TraceSnapshotStore.java `
  main/java/com/example/lms/service/AgentVisibleDebugEvidenceBuilder.java `
  main/java/com/example/lms/dto/ChatResponseDto.java `
  main/java/com/example/lms/dto/ChatStreamEvent.java `
  main/java/com/example/lms/api/ChatStreamSignalBuilder.java `
  main/java/com/example/lms/api/ChatApiController.java `
  src/test/java/com/example/lms/trace/TraceSnapshotSelectionEntropyTest.java `
  src/test/java/com/example/lms/service/AgentVisibleDebugEvidenceBuilderTest.java `
  src/test/java/com/example/lms/dto/ChatResponseDtoLearningContextTest.java `
  src/test/java/com/example/lms/dto/ChatEvidenceMetadataDtoTest.java `
  src/test/java/com/example/lms/api/ChatStreamSignalBuilderTest.java `
  src/test/java/com/example/lms/api/ChatApiControllerTraceMetaTest.java
Write-Output 'task=10 traceAllowlist=pass typedTransport=pass commit=not_authorized'
```

If commit authority is later granted, stage only the listed Task 10 paths and run:

```powershell
git commit -m "feat: expose safe selection replay evidence"
```

### Task 11: Render and Clear the Read-Only Selection Replay Card

**Files:**
- Modify: `main/resources/static/js/chat.js:936,2525-2845,4483-4560`
- Create: `src/chatUiTest/java/com/example/lms/web/ChatFrontendSelectionEntropyFocusedTest.java`
- Verify: `src/chatUiTest/java/com/example/lms/web/ChatFrontendStreamCancellationFocusedTest.java`

**Interfaces:**
- Consumes: SSE type `selection_entropy` and its typed `selectionEntropySignal` object.
- Produces: accessible label/value card, strict client allowlist, and stale-state removal on new request, cancel, error, session reload, and restore.

- [ ] **Step 1: Hash the untracked/dirty `chat.js` preimage and write the RED source/Node contract**

```java
@Test
void rendersOnlyTypedSelectionEntropyFieldsAndDefinesEveryResetPoint() throws Exception {
    String source = Files.readString(CHAT_JS, StandardCharsets.UTF_8);
    assertThat(source).contains(
            "function selectionEntropySignal(payload = {})",
            "function renderSelectionEntropyTrace(payload, bubble)",
            "function clearSelectionEntropyTrace(scope)",
            "type === \"selection_entropy\"");
    assertThat(source).doesNotContain(
            "selectionReplaySeed", "selectionReplayToken", "selectionReplayToggle",
            "localStorage.setItem(\"selection", "sessionStorage.setItem(\"selection");
}
```

The Node fixture dispatches standard/not-requested, replay/matched, replay/partial, replay/failed, malformed, and missing signals. It asserts text labels, `data-status`, no raw unknown field rendering, and removal after new request/cancel/error/reload/restore.

- [ ] **Step 2: Run the focused UI test and confirm RED**

```powershell
.\gradlew.bat chatUiTest `
  --tests com.example.lms.web.ChatFrontendSelectionEntropyFocusedTest `
  --tests com.example.lms.web.ChatFrontendStreamCancellationFocusedTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: the new contract fails because no selection entropy renderer exists; existing cancellation behavior remains characterized.

- [ ] **Step 3: Add a strict typed client parser with no arbitrary enumeration**

```javascript
function selectionEntropySignal(payload = {}) {
  const raw = payload && typeof payload.selectionEntropySignal === "object"
    ? payload.selectionEntropySignal : null;
  if (!raw) return null;
  const modes = new Set(["standard", "replay"]);
  const coherence = new Set(["not_requested", "accepted", "matched", "partial", "failed", "forbidden", "invalid"]);
  const count = value => Number.isInteger(value) && value >= 0 && value <= 10000 ? value : null;
  const mode = modes.has(raw.mode) ? raw.mode : null;
  const state = coherence.has(raw.coherenceStatus) ? raw.coherenceStatus : null;
  if (!mode || !state || raw.algorithmVersion !== "selection-entropy-v1") return null;
  return Object.freeze({
    mode,
    replayAccepted: raw.replayAccepted === true,
    coherenceStatus: state,
    replayReference: /^[a-f0-9]{12}$/.test(raw.replayReference || "") ? raw.replayReference : "",
    algorithmVersion: raw.algorithmVersion,
    decisionDigest: /^[a-f0-9]{64}$/.test(raw.decisionDigest || "") ? raw.decisionDigest : "",
    decisionCount: count(raw.decisionCount),
    drawCount: count(raw.drawCount),
    stableTieBreakCount: count(raw.stableTieBreakCount),
    candidateDriftCount: count(raw.candidateDriftCount),
    routerDrawCount: count(raw.routerDrawCount),
    strategyDrawCount: count(raw.strategyDrawCount),
    ensembleDrawCount: count(raw.ensembleDrawCount),
    completionOrderDeterministic: raw.completionOrderDeterministic === true,
    reasonCode: /^selection_entropy_[a-z0-9_]+$/.test(raw.reasonCode || "") ? raw.reasonCode : ""
  });
}
```

Do not use `Object.keys`, JSON pretty-printing, innerHTML interpolation, or arbitrary event fields.

- [ ] **Step 4: Render an accessible card using text nodes and fixed rows**

`renderSelectionEntropyTrace` creates one `<section class="trace-card selection-entropy-card" data-selection-entropy-card>` with heading `Selection replay` and a `<dl>`. Create `<dt>/<dd>` rows with `textContent` for: Mode, Replay status, Coherence, Replay reference when present, Algorithm, Decisions, Draws, Stable ties, Router draws, Strategy draws, Ensemble draws, Candidate drift, Completion order, and safe reason label when present. Show `Not deterministic` whenever the flag is false; do not rely on color alone. Replace an existing card in the same assistant bubble instead of appending duplicates.

- [ ] **Step 5: Wire event dispatch and all reset points**

In `renderChatEvent(...)`, add:

```javascript
} else if (type === "selection_entropy") {
  renderSelectionEntropyTrace(payload, assistant);
}
```

Call `clearSelectionEntropyTrace(...)` before a fresh `streamChat`, on server cancelled status, local `AbortError`, final error, `startNewChatSession()`, and transcript restore/reconcile before replacement. Clearing must be idempotent and scoped to the current chat/transcript container.

- [ ] **Step 6: Run focused UI and existing stream contracts and confirm GREEN**

```powershell
.\gradlew.bat chatUiTest `
  --tests com.example.lms.web.ChatFrontendSelectionEntropyFocusedTest `
  --tests com.example.lms.web.ChatFrontendStreamCancellationFocusedTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
node .\scripts\chat_ui_stream_contract_tests.js
```

Expected: standard/matched/partial/failed/missing rendering, strict fields, accessibility, cancel/reload cleanup, malformed terminal handling, and existing stream contracts pass.

- [ ] **Step 7: Inspect the two owned paths and handle commit authority**

```powershell
git diff --check -- main/resources/static/js/chat.js `
  src/chatUiTest/java/com/example/lms/web/ChatFrontendSelectionEntropyFocusedTest.java
Write-Output 'task=11 uiContract=pass staleClear=pass commit=not_authorized'
```

If commit authority is later granted, stage those two paths and run:

```powershell
git commit -m "feat: render selection replay evidence"
```

### Task 12: Run the Focused-to-Broad Desktop Verification Ladder

**Files:**
- Verify all Task 1-11 production and test paths
- Verify unchanged: `build.gradle.kts`, `settings.gradle.kts`, `PromptBuilder.java`, `LlmRouterProperties.java`, provider adapters, application configuration
- Evidence: command output, count-only secret result, final diff/stat, and preimage/postimage hashes

**Interfaces:**
- Consumes: every focused test deliverable and the existing Desktop-isolated Gradle environment from Task 0.
- Produces: fresh core/context/ingress/consumer/trace/UI tests, version/source-set/compile/classes/bootJar proof, secret count, and preservation evidence.

- [ ] **Step 1: Run the complete focused root-test set in dependency order**

```powershell
.\gradlew.bat test `
  --tests com.example.lms.infra.selection.SelectionCoordinateTest `
  --tests com.example.lms.infra.selection.ReplaySelectionEntropyTest `
  --tests com.example.lms.infra.selection.LiveSelectionEntropyTest `
  --tests com.example.lms.infra.selection.SelectionDecisionLedgerTest `
  --tests com.example.lms.infra.selection.SelectionEntropyProjectionTest `
  --tests com.example.lms.service.guard.GuardContextSelectionEntropyTest `
  --tests com.example.lms.infra.exec.ContextPropagationTimeBudgetTest `
  --tests com.example.lms.api.SelectionReplayRequestResolverTest `
  --tests com.example.lms.api.ChatApiControllerSelectionReplayTest `
  --tests com.example.lms.security.AdminTokenGuardInterceptorTest `
  --tests ai.abandonware.nova.orch.router.LlmRouterBanditTraceTest `
  --tests com.example.lms.strategy.StrategySelectorServiceTest `
  --tests com.example.lms.strategy.RetrievalOrderServiceTest `
  --tests com.example.lms.ensemble.StochasticParamSamplerTest `
  --tests com.example.lms.ensemble.DiverseSamplingOrchestratorTest `
  --tests com.example.lms.service.rag.orchestrator.UnifiedRagOrchestratorRagEvalTest `
  --tests com.example.lms.service.rag.rerank.DppDiversityRerankerTest `
  --tests com.example.lms.trace.TraceSnapshotSelectionEntropyTest `
  --tests com.example.lms.service.AgentVisibleDebugEvidenceBuilderTest `
  --tests com.example.lms.dto.ChatResponseDtoLearningContextTest `
  --tests com.example.lms.dto.ChatEvidenceMetadataDtoTest `
  --tests com.example.lms.api.ChatStreamSignalBuilderTest `
  --tests com.example.lms.api.ChatApiControllerTraceMetaTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: all named tests pass. Diagnose the first focused failure before broadening; do not hide a missing test pattern or relabel it as success.

- [ ] **Step 2: Run chat UI and deterministic source contracts**

```powershell
.\gradlew.bat chatUiTest `
  --tests com.example.lms.web.ChatFrontendSelectionEntropyFocusedTest `
  --tests com.example.lms.web.ChatFrontendStreamCancellationFocusedTest `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
node .\scripts\chat_ui_stream_contract_tests.js
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local_interaction_smoke_refresh_tests.ps1
```

Expected: focused UI, existing cancel/stream contracts, and the interaction-smoke formatter self-test pass.

- [ ] **Step 3: Run cross-subsystem regressions required by the repository guard**

```powershell
.\gradlew.bat test `
  --tests '*RetrievalOrder*' `
  --tests '*LlmRouter*' `
  --tests '*DppDiversity*' `
  --tests '*NovaNextFusion*' `
  --tests '*TailWeighted*' `
  --tests '*ArtPlate*' `
  --tests '*Moe*' `
  --tests '*RgbStrategy*' `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: every existing matched test passes. If a wildcard matches no test, record `missing-task` for that pattern and rely only on the explicit focused tests that actually executed.

- [ ] **Step 4: Run version, source-set, compile, app, and fresh boot JAR gates**

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
.\gradlew.bat :app:classes bootJar -x test `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Expected: LangChain4j remains exactly 1.0.1; the context-load test proves no servlet request scope is required to construct entropy defaults; active source-set hygiene, root compile, `:app:classes`, and `bootJar` pass with the task-specific output/cache isolation.

- [ ] **Step 5: Separate stale class-output contamination if a broad test shows the known signature**

Only when a broad test reports multiple `NoClassDefFoundError` or `ClassNotFoundException` failures for classes already present under the current host build directory, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify_full_test_refresh.ps1
```

Expected: the script classifies stale output versus a real source failure. Do not use this script to overwrite a focused failing test.

- [ ] **Step 6: Run a count-only secret scan over the exact changed surface**

```powershell
$secretPattern = 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|X-(?:Admin|Owner)-Token\s*[:=]\s*[^\s"'']+'
$secretTargets = @($targets)
$secretHits = @(rg -n --pcre2 $secretPattern -- $secretTargets 2>$null)
Write-Output ("secretPatternHits={0}" -f $secretHits.Count)
```

Expected: `secretPatternHits=0`. Do not print `$secretHits` or any matched value.

- [ ] **Step 7: Compare final target postimages to the frozen ledger and inspect the whole exact diff**

```powershell
$postimages = foreach ($path in $targets) {
  if (Test-Path -LiteralPath $path -PathType Leaf) {
    [pscustomobject]@{ path = $path; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant() }
  }
}
Write-Output ("postimageCount={0}" -f @($postimages).Count)
git diff --check -- $targets
git status --short -- $targets
git diff --stat -- $targets
git diff -- $targets
```

Expected: only declared feature hunks appear in the reviewed target diff; unrelated dirty files/hunks remain untouched and unstaged.

- [ ] **Step 8: Produce the cross-subsystem guard checkpoint**

Record:

```text
affectedSubsystems=S03,cross-subsystem
promptBoundary=unchanged
langchain4jVersion=1.0.1
focusedTests=the integer sum of the tests attributes in the current task-host Gradle XML files
broadGates=checkLangchain4jVersionPurity,checkSourceSetHygiene,compileJava,:app:classes,bootJar
secretPatternHits=0
providerAttempt=not_observed unless a later runtime wire attempt is explicitly observed
commit=not_authorized
```

Compute and emit the numeric test count rather than the descriptive sentence when producing the evidence record:

```powershell
$testXml = Get-ChildItem -LiteralPath (Join-Path 'build' $env:AWX_BUILD_HOST_ID) `
  -Filter 'TEST-*.xml' -File -Recurse -ErrorAction SilentlyContinue
$focusedTestCount = 0
foreach ($file in $testXml) {
  [xml]$suite = Get-Content -LiteralPath $file.FullName -Raw
  $focusedTestCount += [int]$suite.testsuite.tests
}
Write-Output ("focusedTests={0}" -f $focusedTestCount)
```

- [ ] **Step 9: Handle commit authority after all local gates**

Without new authority:

```powershell
Write-Output 'task=12 focused=pass broad=pass secretPatternHits=0 commit=not_authorized'
```

If commit authority is later granted, stage only all declared Task 1-11 production/test paths after the final diff review and make one cohesive commit:

```powershell
git commit -m "feat: add request scoped selection entropy replay"
```

Do not push, deploy, or mutate plugin/database/credential state.

### Task 13: Prove Fresh Runtime, Browser DOM, and Computer-Visible Layout, Then Audit Completion

**Files:**
- Read: fresh task-host boot JAR and embedded `BOOT-INF/classes/static/js/chat.js`
- Read: `C:/Users/nninn/.codex/plugins/cache/openai-bundled/browser/26.814.41957/skills/control-in-app-browser/SKILL.md` and all documentation it requires before Browser actions
- Read: `C:/Users/nninn/.codex/plugins/cache/openai-bundled/computer-use/26.814.41957/skills/computer-use/SKILL.md`, `guidance.md`, `confirmations.md`, and the API sections needed before Computer actions
- Generate through existing scripts: `var/codex-smoke/browser-ui-smoke.json`, `var/codex-smoke/computer-use-smoke.json`, `var/codex-smoke/local-interaction-smoke-refresh.summary.json`
- No production-source write in this task

**Interfaces:**
- Consumes: fresh `bootJar`, safe sync/SSE projection, chat UI renderer, Browser skill, Computer Use policy, and existing smoke formatter.
- Produces: task-owned runtime health, source/JAR/served asset identity, protected replay count/hash proof, Browser DOM/console/geometry proof, Computer-visible proof, final acceptance matrix, lease release, and goal completion only when all rows pass.

- [ ] **Step 1: Resolve and inspect the fresh task-host JAR**

```powershell
$buildRoot = Join-Path 'build' $env:AWX_BUILD_HOST_ID
$jar = Get-ChildItem -LiteralPath (Join-Path $buildRoot 'libs') -Filter '*.jar' -File |
  Where-Object { $_.Name -notmatch '(plain|sources|javadoc)' } |
  Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($null -eq $jar) { throw 'evidence_needed: fresh bootJar / verify with gradlew.bat bootJar' }
$jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar.FullName).Hash.ToLowerInvariant()
$jarEntries = @(& jar tf $jar.FullName)
if ($jarEntries -notcontains 'BOOT-INF/classes/com/example/lms/infra/selection/ReplaySelectionEntropy.class') {
  throw 'evidence_needed: ReplaySelectionEntropy in fresh bootJar / verify jar tf output'
}
if ($jarEntries -notcontains 'BOOT-INF/classes/static/js/chat.js') {
  throw 'evidence_needed: chat.js in fresh bootJar / verify jar tf output'
}
Write-Output ("freshJarHash={0}" -f $jarHash)
Write-Output ("freshJarTimestampUtc={0:o}" -f $jar.LastWriteTimeUtc)
```

- [ ] **Step 2: Choose an unowned isolated port and launch only a task-owned process**

```powershell
$port = 18182..18192 | Where-Object {
  -not (Get-NetTCPConnection -State Listen -LocalPort $_ -ErrorAction SilentlyContinue)
} | Select-Object -First 1
if ($null -eq $port) { throw 'port-conflict: no free port in 18182..18192' }

$ownerTokenWasPresent = Test-Path Env:LLM_OWNER_TOKEN
$ownerTokenOriginal = if ($ownerTokenWasPresent) { $env:LLM_OWNER_TOKEN } else { $null }
if ([string]::IsNullOrWhiteSpace($env:LLM_OWNER_TOKEN)) {
  $ephemeralTokenBytes = New-Object byte[] 32
  [Security.Cryptography.RandomNumberGenerator]::Fill($ephemeralTokenBytes)
  $env:LLM_OWNER_TOKEN = [Convert]::ToBase64String($ephemeralTokenBytes)
  [Array]::Clear($ephemeralTokenBytes, 0, $ephemeralTokenBytes.Length)
}
$stdout = Join-Path $env:TEMP ("awx-selection-entropy-{0}.stdout.log" -f $PID)
$stderr = Join-Path $env:TEMP ("awx-selection-entropy-{0}.stderr.log" -f $PID)
$runtime = Start-Process -FilePath (Get-Command java).Source `
  -ArgumentList @('-jar', $jar.FullName, "--server.port=$port") `
  -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
Write-Output ("runtimePid={0} runtimePort={1}" -f $runtime.Id, $port)
```

Do not print the owner token, process environment, replay header, or log bodies. This current-process value is restored in the final cleanup.

- [ ] **Step 3: Prove health and source/JAR/served asset identity**

```powershell
$healthy = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
  try {
    $health = Invoke-RestMethod -Uri ("http://127.0.0.1:{0}/actuator/health" -f $port) -TimeoutSec 2
    if ($health.status -eq 'UP') { $healthy = $true; break }
  } catch { }
  Start-Sleep -Seconds 1
}
if (-not $healthy) { throw 'evidence_needed: task runtime health / inspect redacted first error class' }

$sourceChatHash = (Get-FileHash -Algorithm SHA256 -LiteralPath 'main/resources/static/js/chat.js').Hash.ToLowerInvariant()
$tempExtract = Join-Path $env:TEMP ("awx-selection-entropy-jar-{0}" -f $PID)
New-Item -ItemType Directory -Force -Path $tempExtract | Out-Null
Push-Location $tempExtract
try { & jar xf $jar.FullName 'BOOT-INF/classes/static/js/chat.js' } finally { Pop-Location }
$jarChatHash = (Get-FileHash -Algorithm SHA256 `
  -LiteralPath (Join-Path $tempExtract 'BOOT-INF/classes/static/js/chat.js')).Hash.ToLowerInvariant()
$servedChat = Invoke-WebRequest -UseBasicParsing -Uri ("http://127.0.0.1:{0}/js/chat.js?cb={1}" -f $port, $sourceChatHash)
$servedChatHash = [Convert]::ToHexString(
  [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($servedChat.Content))
).ToLowerInvariant()
$uniqueChatHashes = @($sourceChatHash, $jarChatHash, $servedChatHash) | Select-Object -Unique
if (@($uniqueChatHashes).Count -ne 1) {
  throw 'evidence_needed: source/JAR/served chat.js identity mismatch / rebuild and restart task runtime'
}
Write-Output ("chatAssetHash={0} assetIdentityMatched=true" -f $sourceChatHash)
```

- [ ] **Step 4: Run two protected replay requests without retaining raw prompt/response**

```powershell
$seed = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($seed)
$payload = [Convert]::ToBase64String($seed).TrimEnd('=').Replace('+','-').Replace('/','_')
$replayHeader = "v1:$payload"
$headers = @{
  'Content-Type' = 'application/json'
  'X-AWX-Selection-Replay' = $replayHeader
  'X-Owner-Token' = $env:LLM_OWNER_TOKEN
}
$body = @{ message = 'selection replay verification'; useWebSearch = $false; useRag = $false } |
  ConvertTo-Json -Compress
$safeRuns = @()
for ($run = 1; $run -le 2; $run++) {
  $headers['X-Request-Id'] = "selection-replay-runtime-proof-$run"
  $response = Invoke-WebRequest -UseBasicParsing -Method Post `
    -Uri ("http://127.0.0.1:{0}/api/chat" -f $port) -Headers $headers -Body $body
  $json = $response.Content | ConvertFrom-Json
  $contentHash = [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes([string]$json.content))
  ).ToLowerInvariant()
  $safeRuns += [pscustomobject]@{
    run = $run
    status = [int]$response.StatusCode
    algorithmVersion = $json.selectionEntropy.algorithmVersion
    seedFingerprint = $json.selectionEntropy.seedFingerprint
    decisionDigest = $json.selectionEntropy.decisionDigest
    decisionCount = $json.selectionEntropy.decisionCount
    drawCount = $json.selectionEntropy.drawCount
    stableTieBreakCount = $json.selectionEntropy.stableTieBreakCount
    candidateDriftCount = $json.selectionEntropy.candidateDriftCount
    coherenceStatus = $json.selectionEntropy.coherenceStatus
    completionOrderDeterministic = $json.selectionEntropy.completionOrderDeterministic
    responseLength = ([string]$json.content).Length
    responseHash = $contentHash
  }
  $response = $null
  $json = $null
}
[Array]::Clear($seed, 0, $seed.Length)
$headers.Clear()
$headers = $null; $payload = $null; $replayHeader = $null; $body = $null
$safeRuns | Select-Object run,status,algorithmVersion,seedFingerprint,decisionDigest,
  decisionCount,drawCount,stableTieBreakCount,candidateDriftCount,coherenceStatus,
  completionOrderDeterministic,responseLength,responseHash | Format-Table -AutoSize
```

Required replay proof: both requests are authorized; both expose only safe fields; same algorithm/fingerprint/digest/counts and `matched` coherence are required for a full runtime match. Response hash equality is recorded separately and is not required or described as model determinism. If candidate state legitimately drifts, report `partial` and keep the runtime acceptance row open rather than retrying until green.

- [ ] **Step 5: Prove forbidden and malformed requests without workflow/provider execution**

Send one request with the replay header but no owner/admin token and one authorized request with `v1:invalid=`. Retain only HTTP status, fixed code, and request/provider-attempt counts from safe trace/testing evidence. Expected: 403 `selection_entropy_replay_forbidden` and 400 `selection_entropy_replay_invalid`; neither produces a selection-success card or an observed provider attempt.

- [ ] **Step 6: Use the in-app Browser for fresh DOM, stream, cancel, reload, and geometry proof**

Before Browser actions, fully read the Browser skill’s required documentation and announce that the skill is being used for localhost UI proof. Initialize the in-app Browser through its prescribed browser client, set `$chatUrl = "http://127.0.0.1:$port/chat-ui?cb=$sourceChatHash"`, and open that exact value.

```text
The URL uses only the selected isolated numeric port and the verified lowercase source asset hash.
```

Verify with DOM-backed checks:

- a normal UI request renders standard/not-requested selection state and a rendered answer/terminal state separately;
- the exact safe replay projection from Step 4 can be passed to the page’s public classic-script `renderChatEvent` as a `selection_entropy` event without passing seed/header/token, and the DOM card shows the same safe fingerprint/digest/counts;
- a fixed safe `partial` projection renders candidate drift and does not claim matched;
- the forbidden/invalid server results do not render a matched card;
- cancel reaches cancelled terminal state and no late token appears after the terminal marker;
- reload and a new session remove stale entropy cards;
- viewport `1280x720` and `390x844` have `scrollWidth <= clientWidth` for page and card containers;
- record console warning/error counts and DOM row counts only.

The correlated safe-projection injection proves the Browser renderer for server-produced safe values; it is not described as Browser proof of header authorization or HMAC derivation.

- [ ] **Step 7: Use Computer Use only for Windows-visible layout proof**

Read `guidance.md`, `confirmations.md`, and the needed API sections before control; announce that Computer Use is proving visible layout only. Inspect the already open localhost page without typing or pasting replay/admin/owner material. Verify card presence, unclipped labels, scrolling, overlays, chat input, cancel controls, narrow-window overflow, and a clean state after reload. Any screenshot must contain only safe projection values and no token, header, seed, raw prompt, or raw response.

- [ ] **Step 8: Refresh count-only Browser/Computer smoke summaries**

After the Browser and Computer observations exist, use `apply_patch` to create the two probe inputs under `var/codex-smoke/selection-entropy/`; do not use shell redirection or `Set-Content`. Values must be copied from the just-observed count/boolean evidence. If a fact was not observed, record its neutral false/zero value and leave that acceptance row open—never write a passing value merely to satisfy the formatter.

The computer probe has exactly these keys: `schemaVersion`, `reachable`, `appCount`, `runningCount`, `targetableWindowCount`, `helperCountOnly`, `countOnly`, `clippedLabelCount`, `overlayObstructionCount`, `narrowOverflowCount`, `cleanReloadCardCount`, `rawAppNamesStored`, `rawWindowTitlesStored`, and `rawSecretPatternHits`. Its schema is `awx.selection-entropy.computer-probe.v1`; the last three raw-data/secret counts or flags must be false/zero.

The Browser probe has exactly these keys: `schemaVersion`, `reachable`, `localhost`, `publicDomain`, `targetHost`, `screenshotCaptured`, `targetContentVisible`, `hasChatCue`, `hasErrorCue`, `statusClass`, `browserSurface`, `selectionCardCount`, `consoleWarningCount`, `consoleErrorCount`, `wideOverflowCount`, `narrowOverflowCount`, `cancelLateTokenCount`, `reloadStaleCardCount`, `rawUrlStored`, `rawTextStored`, `screenshotPathStored`, and `rawSecretPatternHits`. Its schema is `awx.selection-entropy.browser-probe.v1`; `targetHost` is only `localhost`, `127.0.0.1`, or `::1`, and no URL, screenshot path, prompt, response, token, header, or seed is stored.

Validate the exact shapes before invoking the existing formatter:

```powershell
function Read-StrictProbe([string]$path, [string[]]$allowed) {
  $probe = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
  $names = @($probe.PSObject.Properties.Name)
  $unknown = @($names | Where-Object { $_ -notin $allowed })
  $missing = @($allowed | Where-Object { $_ -notin $names })
  if ($unknown.Count -gt 0 -or $missing.Count -gt 0) {
    throw 'probe-schema-invalid: exact allowlist mismatch'
  }
  return $probe
}

$computerProbePath = '.\var\codex-smoke\selection-entropy\computer-probe.json'
$browserProbePath = '.\var\codex-smoke\selection-entropy\browser-probe.json'
$computerAllowed = @(
  'schemaVersion','reachable','appCount','runningCount','targetableWindowCount',
  'helperCountOnly','countOnly','clippedLabelCount','overlayObstructionCount',
  'narrowOverflowCount','cleanReloadCardCount','rawAppNamesStored',
  'rawWindowTitlesStored','rawSecretPatternHits')
$browserAllowed = @(
  'schemaVersion','reachable','localhost','publicDomain','targetHost',
  'screenshotCaptured','targetContentVisible','hasChatCue','hasErrorCue',
  'statusClass','browserSurface','selectionCardCount','consoleWarningCount',
  'consoleErrorCount','wideOverflowCount','narrowOverflowCount',
  'cancelLateTokenCount','reloadStaleCardCount','rawUrlStored','rawTextStored',
  'screenshotPathStored','rawSecretPatternHits')
$computerProbe = Read-StrictProbe $computerProbePath $computerAllowed
$browserProbe = Read-StrictProbe $browserProbePath $browserAllowed
if ($computerProbe.schemaVersion -ne 'awx.selection-entropy.computer-probe.v1' -or
    $computerProbe.rawAppNamesStored -or $computerProbe.rawWindowTitlesStored -or
    [int]$computerProbe.rawSecretPatternHits -ne 0) {
  throw 'computer-probe-privacy-invalid'
}
if ($browserProbe.schemaVersion -ne 'awx.selection-entropy.browser-probe.v1' -or
    $browserProbe.targetHost -notin @('localhost','127.0.0.1','::1') -or
    $browserProbe.rawUrlStored -or $browserProbe.rawTextStored -or
    $browserProbe.screenshotPathStored -or [int]$browserProbe.rawSecretPatternHits -ne 0) {
  throw 'browser-probe-privacy-invalid'
}
```

Then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\refresh_local_interaction_smokes.ps1 `
  -Root . `
  -ComputerProbePath $computerProbePath `
  -BrowserProbePath $browserProbePath
```

Expected outputs are the existing `var/codex-smoke/computer-use-smoke.json`, `browser-ui-smoke.json`, and `local-interaction-smoke-refresh.summary.json`. The strict precheck rejects every unlisted raw field before the formatter runs; the formatter's own secret and raw-artifact flags remain a second boundary.

- [ ] **Step 9: Audit every acceptance row before claiming completion**

Record `PASS`, `HOLD`, or `evidence_needed` against each row:

```text
active surfaces: live call paths + focused test boundaries
standard exploration: header-absent regression
replay core: golden vector + same-seed + order/parallel independence
context/async: attach/copy/restore/fan-out tests
router: cold-start/UCB/weighted fixed-deck tests
strategy: fixed zero-draw + epsilon/softmax tests
ensemble: profile/shuffle + first-observed/cancel tests
RRF/DPP: permutation and key-priority tests
authorization: owner/admin positive + forbidden/invalid zero-call negative
privacy: sanitizer/sentinel tests + secretPatternHits=0
observability: TraceStore + snapshot + agent + sync DTO + typed SSE
UI: Node/source contract + Browser DOM
Windows visibility: Computer evidence
build: purity + source set + compile + tests + app classes + bootJar
plugin boundary: installed/enabled state unchanged; new dependency/permission count=0
preservation: declared diff + preimage/postimage evidence
full objective: no required unresolved lane
```

Do not accept HTTP 200, terminal SSE, one test, a screenshot, fingerprint equality, response hash equality, or a provider availability endpoint as sufficient by itself.

- [ ] **Step 10: Stop only the task runtime, restore process-local state, and release the lease**

Run in a `finally` path whether completion succeeds or a lane holds:

```powershell
$cleanupHold = $null
if ($runtime -and -not $runtime.HasExited) { Stop-Process -Id $runtime.Id -ErrorAction SilentlyContinue }
if ($ownerTokenWasPresent) { $env:LLM_OWNER_TOKEN = $ownerTokenOriginal } else { Remove-Item Env:LLM_OWNER_TOKEN -ErrorAction SilentlyContinue }
if ($tempExtract -and (Test-Path -LiteralPath $tempExtract)) {
  $resolvedTempBase = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')
  $resolvedTempExtract = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $tempExtract).Path)
  $resolvedParent = [IO.Path]::GetDirectoryName($resolvedTempExtract).TrimEnd('\')
  $resolvedLeaf = [IO.Path]::GetFileName($resolvedTempExtract)
  $parentMatches = [string]::Equals(
      $resolvedParent, $resolvedTempBase, [StringComparison]::OrdinalIgnoreCase)
  if ($parentMatches -and $resolvedLeaf -match '^awx-selection-entropy-jar-[0-9]+$') {
    try {
      Remove-Item -LiteralPath $resolvedTempExtract -Recurse -Force -ErrorAction Stop
    } catch {
      $cleanupHold = 'path-cleanup-hold: approved task temp removal failed'
    }
  } else {
    $cleanupHold = 'path-safety-hold: task temp extraction target is outside approved boundary'
  }
}
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
  -Action end -Role desktop -Root . -Topic selection-entropy-replay -OwnerId $ownerId
if ($LASTEXITCODE -ne 0) { throw 'lease-release-hold: source edit lease did not close' }
if ($cleanupHold) { throw $cleanupHold }
```

The parent-directory equality and numeric task-leaf checks execute before the only recursive removal. An unsafe target is not removed, but the source-edit lease is still released before the fixed path-safety hold is raised. Do not stop any process other than `$runtime.Id`.

- [ ] **Step 11: Close the active goal only on complete evidence**

If every acceptance row is current `PASS`, report the exact changed paths, tests, fresh runtime, Browser, Computer, secret count, plugin boundary, provider-attempt status, and `completionOrderDeterministic=false`, then mark the existing goal complete. If a required row remains unresolved, keep the full objective open and report one exact next verification action; do not redefine completion around the local green subset.

Without commit authority, finish with:

```powershell
Write-Output 'task=13 runtime=verified browser=verified computer=verified commit=not_authorized'
```

No push, deploy, database mutation, credential persistence, ACL change, plugin install, or plugin permission change is part of completion.

## Execution Budget and Stop Points

| Maximum elapsed window | Planned work | Early stop |
|---|---|---|
| 00:00-00:40 | Task 0 Java/source/Git/PatchDrop/lease/preimage/three-query gate | stable non-APPLY or unsafe ownership |
| 00:40-02:00 | Tasks 1-3 core derivation and ledger | decisive core invariant failure |
| 02:00-03:20 | Tasks 4-5 context and ingress | replay cannot attach/auth fail closed |
| 03:20-05:10 | Tasks 6-9 router/strategy/ensemble/RRF/DPP | required consumer lane conflict or focused failure |
| 05:10-06:20 | Tasks 10-11 trace/DTO/SSE/UI | public redaction or compatibility failure |
| 06:20-07:30 | Task 12 focused and broad Desktop gates | first unresolved real build/test failure |
| 07:30-08:30 | Task 13 runtime/Browser/Computer | port/owner/runtime or explicit UI-policy blocker |
| 08:30-09:00 | Final diff/secret/rollback/acceptance audit | complete proof; stop early |

The clock is a ceiling, not a reason to keep running after proof or to claim completion when a required lane is still open.

## Spec Coverage Self-Review

| Approved design requirement | Implementing task/evidence |
|---|---|
| Preserve standard exploration and add request replay | Tasks 2, 5, 6, 7, 8 |
| Exact coordinate encoding and HMAC/numeric mapping | Tasks 1-2 golden vector |
| No mutable global draw state; order/parallel independence | Task 2 order and executor tests |
| Hash-only 10,000-decision ledger and candidate drift | Task 3 |
| GuardContext attach-once and existing propagation | Task 4 |
| Non-web/default compatibility | Tasks 4 and 12 context-load/build gates |
| Header constraints, auth-before-parse, exact failures | Task 5 |
| Model/strategy/node/document canonicalization | Tasks 6-9 |
| Retry/fallback actor/attempt separation | Task 6 additive aspect call arguments |
| Deterministic fail-soft and no replay-to-live downgrade | Tasks 2, 3, 5-9 |
| First-observed ensemble terminal semantics | Task 8 regression |
| Exact TraceStore keys and snapshot fail-closed allowlist | Task 10 |
| Fingerprint-free explicit agent breadcrumb | Task 10 |
| Typed sync response and typed SSE compatibility | Task 10 |
| Read-only UI, strict fields, cancel/reload cleanup | Task 11 |
| Java/source/lease/PatchDrop/preimage/three-query gates | Task 0 |
| Focused/broad/app/JAR/secret/diff proof | Task 12 |
| Fresh runtime, Browser DOM, Computer visibility | Task 13 |
| Plugin Management boundary and no new permission/dependency | Tasks 0 and 13 audit |
| Nine-hour ceiling and early stops | execution budget table |
| No commit/push/deploy/database/credential persistence | global constraints and every task checkpoint |

Compatibility reconciliation is explicit: the spec’s typed sync serialization requirement is implemented with one nullable `SelectionEntropyProjection` on `ChatResponseDto`, while `ChatRequestDto`, every existing response constructor, header-absent JSON, and every replay input/storage contract remain unchanged. No approved requirement is omitted, and no provider-output, network-scheduling, or completion-order determinism is claimed.
