# Desktop 후처리 소스 수정 지시서

이 지시서는 demo-1 Desktop canonical root에서 HYPERNOVA/TWPM 계열의
고위험 멱평균 파라미터를 직접 신뢰하지 않고, bounded probe와 재현 가능한
증거를 통과한 경우에만 최소 수정하도록 한다.

## 1. 목적

- 체크인된 기본값, 설계 문구, 단일 평균, 외부 에이전트 추천값을 정답으로
  취급하지 않는다.
- `p0`, `alphaTwpm`, `pMax`, `alphaCvar`, `lambdaCvar`, `tailFraction`,
  `tailBoost`, `maxAdjustment`, `bodeC`, `tempSoftmax` 중 한 번에 하나만
  bounded probe한다.
- 9시간은 최대 에이전트 노력 예산이며 자동 성공 판정이나 제품 런타임이
  아니다. 증거가 충분하면 조기 종료하고, 부족하면 `evidence_needed`로
  종료한다.

## 2. 절대 규칙

- Desktop canonical root는 `C:\AbandonWare\demo-1\demo-1\src`이다.
- Notebook과 Mac mini는 Desktop canonical source를 직접 수정하지 않는다.
- 현재 source, `AGENTS.md`, 기존 skill/prompt, Gradle sourceSet, 실제 명령
  출력 순서로 증거를 읽는다.
- secret, env 값, API key, Authorization, cookie, raw prompt, raw query,
  raw evidence payload를 출력하거나 수정하지 않는다.
- active sourceSet 밖의 mirror, archive, backup, generated output은 수정하지
  않는다.
- 첫 2시간 상당의 판단 구간은 source-only다. 충분한 증거 없이 값부터
  바꾸지 않는다.
- 한 번의 cycle에서 한 파라미터만 바꾼다. 여러 파라미터를 함께 바꾼
  결과는 인과 증거로 인정하지 않는다.
- 기본값은 incumbent일 뿐 hard cap의 증거가 아니다.
- broad build green은 파라미터 적정성 증거가 아니다.
- Browser, Computer, Supabase, producer proof는 현재 변경 표면이 요구할
  때만 별도 supporting lane으로 실행한다.

## 3. Source-Only Intake

### 3.1 Desktop preflight

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Set-Location $Root
Get-Location
$indexOp = (Test-Path ".git\MERGE_HEAD") -or (Test-Path ".git\CHERRY_PICK_HEAD") -or (Test-Path ".git\rebase-merge") -or (Test-Path ".git\rebase-apply")
if ($indexOp) {
  Write-Error "[AWX][desktop] index-operation-active"
  exit 1
}
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
```

If janitor inventory is unavailable, do not silently assume the queue is empty.
Use `source_scan` as a read-only cross-check and keep the missing janitor result
as `evidence_needed`.

```powershell
'{"nodeRole":"desktop","root":".","requestId":"bounded-hypernova-source-scan","sessionId":"bounded-hypernova"}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - source_scan
```

Stop production edits for a real index/ref operation in progress
(`index-lock-conflict`), a pending top-level PatchDrop
patch, unprovable active sourceSets, or unsafe overlap in a target file. A nested
pending producer artifact is supporting evidence and does not authorize a
Desktop edit.

### 3.2 Canonical owner map

Confirm these paths in current source; do not trust this list if live evidence
contradicts it.

| Concern | Candidate owner |
| --- | --- |
| TWPM score and tail weighting | `main/java/com/example/lms/service/rag/fusion/TailWeightedPowerMeanFuser.java` |
| Dynamic p, CVaR blend, guard band, DPP handoff | `main/java/com/nova/protocol/fusion/NovaNextFusionService.java` |
| Bound properties and checked-in incumbents | `main/java/com/nova/protocol/properties/NovaNextProperties.java` |
| Profile defaults | `main/resources/application-nova-next.yml` |
| Plan defaults | `main/resources/plans/hyper_nova.v1.yaml` |
| Focused TWPM test | `src/test/java/com/example/lms/service/rag/fusion/TailWeightedPowerMeanFuserTest.java` |
| Focused fusion test | `src/test/java/com/nova/protocol/fusion/NovaNextFusionServiceTest.java` |

```powershell
rg -n "p0|alphaTwpm|pMax|maxAdjustment|alphaCvar|lambdaCvar|tailFraction|tailBoost|bodeC|tempSoftmax|TWPM|powerMean" `
  main\java\com\example\lms\service\rag\fusion `
  main\java\com\nova\protocol `
  main\resources\application-nova-next.yml `
  main\resources\plans\hyper_nova.v1.yaml `
  src\test\java\com\example\lms\service\rag\fusion `
  src\test\java\com\nova\protocol -S
```

### 3.3 Goal table

Complete every row before a patch:

| Field | Required evidence |
| --- | --- |
| `target_metric` | name, unit, direction, and acceptance threshold |
| `incumbent` | current bound value and exact owner |
| `observed_current` | focused test or probe output |
| `gap` | target minus observed, with unit |
| `suspected_causal_chain` | symptom -> direct numerical cause -> owner candidate |
| `active_surface` | exact file and method/property |
| `active_sourceSet` | `source_scan` or Gradle proof |
| `counterexample` | fixture that can reject the proposed value |
| `proof_command` | narrowest command that falsifies the change |

If one row is missing, stop the patch and write:

```text
evidence_needed: <missing artifact> / verify with <exact command>
```

## 4. Nine-Hour Maximum-Effort Routine

The time boxes are ceilings. Do not wait merely to consume them.

| Window | Mode | Required outcome | Early stop |
| --- | --- | --- | --- |
| 00:00-00:45 | preflight | root, Git, PatchDrop, sourceSet, target overlap | blocker classified |
| 00:45-02:00 | source-only | owner/caller/property/clamp/trace/test map and goal table | `no_patch_needed` or missing evidence |
| 02:00-04:00 | RED characterization | incumbent fixture, objective, hard gates, rejecting counterexample | test fails for expected reason |
| 04:00-06:00 | bounded probe | one parameter, incumbent plus at most two candidates, at most three fixture families | candidate rejected or one winner remains |
| 06:00-07:30 | falsification | repeat winner, mixed-scale/contradiction counterexample, deterministic aggregate check | regression or variance invalidates winner |
| 07:30-08:30 | minimal patch | one normalization/property/config seam only after promotion gate | failure class changes |
| 08:30-09:00 | Desktop proof | focused test, sourceSet/purity, compile if source changed, redacted report | verified or `evidence_needed` |

At the end of each phase choose exactly one:

```text
continue_same_hypothesis
reject_candidate
promote_candidate
no_patch_needed
evidence_needed
stop_failure_class_changed
```

## 5. Bounded Probe Contract

### 5.1 Candidate matrix

- Probe one named parameter per cycle.
- Include the incumbent and at most two candidates.
- Derive every candidate from an existing source bound, a failing test, or a
  declared policy. Do not derive caps from the checked-in default alone.
- Use at most three fixed in-memory fixture families:
  `sparse_upper_tail`, `contradiction_heavy`, `mixed_score_scale`.
- Keep fixture IDs and aggregates. Do not log raw prompts, queries, evidence
  lists, or full score vectors.
- Do not call network providers, Supabase, Browser, Computer, Mac mini, or
  Notebook for the numerical unit probe.

### 5.2 Hard gates

Every case must prove:

- all outputs are finite;
- every normalized/final score stays within its source-owned range;
- adjusted scores remain inside the existing guard band;
- no unexpected `nova.next.failSoft` event occurs;
- required traces exist: `hypernova.twpmP`, `hypernova.twpmP.max`,
  `hypernova.cvarPhi`, `hypernova.clampApplied`;
- mixed-scale input is rejected or falls back with the documented reason;
- Risk-K allocation remains within its declared total when the allocator is
  active;
- the focused test is deterministic across the declared repeats.

### 5.3 Promotion gate

Return `promote_candidate` only when:

1. the target metric, unit, direction, and tolerance were declared before the
   run;
2. every hard gate passes for the candidate and incumbent;
3. the candidate improves the declared target over the incumbent;
4. no counterexample fixture crosses the declared regression tolerance;
5. repeated results stay inside the declared variance tolerance;
6. the candidate changes one parameter only;
7. the exact production seam and rollback value are known.

If the highest average violates any gate, reject it. If no source-backed upper
policy exists for `pMax` or `alphaTwpm`, characterize the risk but do not invent
a cap.

### 5.4 Evidence artifact

Persist only bounded, redacted fields when a durable report is requested:

```text
schemaVersion
runIdHash
parameter
incumbent
candidateCount
fixtureFamilyCount
repeatCount
hardGateFailureCount
objectiveMetric
objectiveDirection
aggregateDelta
worstCaseDelta
rankFlipCount
requiredTraceKeysPresent
decision
evidenceNeeded
```

Do not persist raw inputs, raw queries, raw prompts, Authorization headers,
cookies, DB URLs, environment dumps, or secret values.

## 6. File-By-File Patch Blocks

Apply only the first block whose RED test proves the seam. Do not apply all
blocks by default.

### Block A - Focused test first

- File: `src/test/java/com/nova/protocol/fusion/NovaNextFusionServiceTest.java`
- Before: current tests prove guard-band, trace, DPP, mixed-scale, and fail-soft
  behavior but do not necessarily prove a task-specific candidate envelope.
- After intent: add one parameterized characterization for the selected
  parameter, fixed fixtures, hard gates, and counterexample.
- Why this file only: it already owns end-to-end S06 fusion behavior without
  network or UI dependencies.
- Rollback: remove only the new test method/fixture after reverting the paired
  behavior change; never weaken existing assertions.

### Block B - Existing normalization boundary

- File: `main/java/com/nova/protocol/fusion/NovaNextFusionService.java`
- Before: the service normalizes several values and clamps adjustment output,
  while some upper policy bounds may remain task-dependent.
- After intent: only after a RED proof, enforce the smallest source-backed
  envelope at the current normalization line and emit stable effective-value
  traces. Preserve fail-soft pass-through and existing defaults unless the
  probe specifically proves a default change.
- Why this file only: it is the active consumer and current dynamic-p/CVaR
  normalization boundary.
- Rollback: restore the prior expression and incumbent value; rerun the RED
  fixture and focused fusion test.

### Block C - Direct fuser boundary

- File: `main/java/com/example/lms/service/rag/fusion/TailWeightedPowerMeanFuser.java`
- Before: the fuser clamps scores, tail fraction, and tail boost for direct
  calls.
- After intent: edit only if a RED test proves direct callers can bypass the
  service envelope. Keep score clamping and count-only traces.
- Why this file only: it owns direct TWPM call safety, not higher-level policy.
- Rollback: revert only the new direct-call guard and its paired test.

### Block D - Property or checked-in default

- Files: `main/java/com/nova/protocol/properties/NovaNextProperties.java`,
  `main/resources/application-nova-next.yml`, or
  `main/resources/plans/hyper_nova.v1.yaml`.
- Before: properties and YAML provide incumbents; incumbents are not proof of
  policy ceilings.
- After intent: change exactly one property/default only after the bounded
  winner passes promotion and a binding/wiring test proves the effective value.
- Why these files only: they own configuration, not fusion math.
- Rollback: restore the recorded incumbent and rerun the wiring plus focused
  fusion test.

## 7. Modification Prohibitions

Do not modify in this pass:

- `app/src/main/java`, `project/src/main/java`, `demo-1`, `lms-core`, archives,
  backups, build output, or generated prompt output;
- `apikey.txt`, `apikey.ps1`, `.env*`, shell profiles, OAuth/MCP auth,
  `openssl`, or `opnessl` names/values;
- `PromptBuilder` or final prompt assembly;
- Supabase schema, migrations, RLS, auth, storage, or service-role setup;
- Browser/Computer automation or UI labels when only numerical Java behavior is
  under test;
- PatchDrop janitor/apply safety scripts;
- LangChain4j versions;
- a second HYPERNOVA/PowerMean implementation or a new orchestration framework.

## 8. Desktop Setup And Verification Commands

### 8.1 Cache isolation

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
```

### 8.2 Focused RED/GREEN gate

```powershell
.\gradlew.bat test `
  --tests "com.example.lms.service.rag.fusion.TailWeightedPowerMeanFuserTest" `
  --tests "com.nova.protocol.fusion.NovaNextFusionServiceTest" `
  --no-daemon --project-cache-dir $pcd
```

Expected:

- RED fails for the intended missing envelope or unsafe counterexample, not a
  typo, missing class, stale cache, or network error.
- GREEN passes the new test and all pre-existing assertions.

### 8.3 Source and compile gates

Run only after production Java or active resources change:

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene `
  --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
```

Run `bootJar -x test` only when the patch crosses runtime wiring or resource
binding. Do not run boot, Browser, or Computer smoke for a test-only numerical
characterization.

### 8.4 Count-only secret scan

Scan only changed files and print a count, never matching values:

```powershell
$changed = @(
  'main/java/com/example/lms/service/rag/fusion/*',
  'main/java/com/nova/protocol/*',
  'main/resources/application-nova-next.yml',
  'main/resources/plans/hyper_nova.v1.yaml',
  'src/test/java/com/example/lms/service/rag/fusion/*',
  'src/test/java/com/nova/protocol/*'
) | Where-Object { Test-Path $_ }
$pattern = 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}'
$hits = @($changed | ForEach-Object {
  Select-String -Path $_ -Pattern $pattern -ErrorAction SilentlyContinue
})
Write-Host "[AWX][desktop][security] changedFileSecretHits=$($hits.Count)"
```

## 9. External Evidence Boundaries

- Browser: `demand_driven_not_required` unless a DOM/UI diagnostic changed.
- Computer: `demand_driven_not_required` unless Windows UI behavior changed or
  the user explicitly requests visible UI proof.
- Supabase: run only the read-only context probe when project-scoped database
  evidence is explicitly needed. Missing project ref/auth remains
  `read_only_evidence_needed` and never authorizes mutation.
- Superpowers: may structure RED/GREEN and verification, but current repo
  evidence and Desktop command output remain authoritative.
- Notebook/Mac mini: supporting producer evidence only; no direct Desktop
  canonical source edits.

## 10. Failure Classification And Retry

Choose one primary class:

```text
index-lock-conflict
patch-drop-pending
worktree-overlap
wrong-sourceset
parameter-owner-missing
parameter-envelope-unproven
probe-objective-undefined
probe-counterexample-missing
probe-nondeterministic
probe-hard-gate-failed
guard-band-regression
score-scale-mismatch
langchain4j-version-purity
secret-leak-risk
cannot-find-symbol
gradle-cache-collision
unrelated-dirty-worktree-failure
external-evidence-overrequired
other
```

Retry once only after a specific change that addresses the same class. Stop
when the class changes. Roll back by restoring the recorded incumbent and the
single changed normalization/config seam; never revert unrelated dirty files.

## 11. Desktop Application Checklist

- [ ] Declared-target preimage hashes captured; target-file overlap reviewed.
- [ ] Desktop root, real index/ref operation state, and top-level PatchDrop
  queue checked.
- [ ] Active sourceSets proven by current `source_scan` or Gradle output.
- [ ] Existing S06 skill, prompt, owners, callers, clamps, traces, and tests read.
- [ ] One parameter, target metric, unit, direction, tolerance, and incumbent
  recorded.
- [ ] RED fixture fails for the expected numerical reason.
- [ ] Candidate matrix is bounded and includes a rejecting counterexample.
- [ ] Promotion gate passes or the patch is held with `evidence_needed`.
- [ ] Focused tests observed, not inferred.
- [ ] SourceSet/LangChain4j/compile gates observed when production files changed.
- [ ] Changed-file secret scan reports a count only.
- [ ] Browser/Computer/Supabase lanes reported separately.
- [ ] Final Desktop proof records commands, observed result, failure class,
  retry decision, rollback value, and remaining evidence.

## 12. Final Report Format

```md
## 요약
- 실제 수정 또는 no-op, 선택 파라미터, probe 결정, 검증 상태.

## do01 / Observation
- root/branch/sourceSets/Git/PatchDrop.
- live owner and incumbent.
- decomposition decision.
- Browser/Computer/Supabase/Superpowers lane status.
- evidence_needed.

## do02 / Bounded Probe
| parameter | incumbent | candidates | fixtures | objective | hard gates | counterexample | decision |

## do03 / Patch Blocks
파일별:
- Observation.
- Before intent.
- After intent.
- Minimal unified diff or `no_patch_needed`.
- Why this file only.
- Secret masking.
- Rollback value and command.

## do04 / Setup Commands
- exact Desktop commands and cache isolation.
- network/cache dependent commands separated.
- external proof required/not-required split.

## do05 / Verification
| command | expected | observed | failure class | retry decision |

## do06 / Risks & Next Steps
- 최대 5개.
- counterexample/limitation 1개.
- decision factors 최대 3개.
- confidence: L/M/H.
- next single most urgent patch or evidence action.
```

Never claim a value is safe, a build passed, or a probe succeeded without the
current command output that proves that exact claim.
