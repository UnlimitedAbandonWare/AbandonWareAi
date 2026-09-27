# Desktop Goal: GPU Pressure Cloud Offload v2

## Classification

- requestClass: `goal_directive`
- executionClass: `desktop_execution_required`
- currentNode: `notebook`
- currentMutationClass: `prompt_skill_tooling_only`
- currentApplicationSourceMutation: `false`
- capabilityDecision: `reuse_then_extend`
- canonicalWorkspace: `Y:\`
- backingShareIdentityVerified: `true`
- backingShareIdentityReason: `match`
- provenNotebookBranch: `main`
- indexLockPresent: `false`
- requiresLiteralSubagents: `false`
- processMode: `single-agent-logical-roles`
- actualAgentCount: `1`
- canonicalQueryCount: `3`

정확한 machine-readable 실행 산출물은 `agent-prompts/codex_gpu_pressure_cloud_offload_goal.run.json`이다. 이 Markdown은 그 판정과 구현 계약을 사람이 실행하기 좋은 형태로 투영한다.

## Immutable EvidenceSnapshot

- evidenceSnapshotHash: `a475d44fd209cacdac08d15de746e680d50d2f3e0bff212cedf56be5c0786dbd`
- observedAt: `2026-08-01T16:51:54+09:00`
- canonical JSON: UTF-8, sorted keys, compact separators

| evidenceId | owner | observation |
| --- | --- | --- |
| E01 | notebook-filesystem | `Y:\` backing identity boolean `true`; raw backing path 비공개 |
| E02 | notebook-design | 기존 v1 설계 SHA-256 `f31a023c18d664801c5b003731e269c8692e9cc4a302be2586fb19ad01c12bfa` |
| E03 | desktop-source | `GpuHardwareDiagnostics.java` SHA-256 `5bfb5946ed0d63cc53869a647b8a1188a5c1e7e9e8ca9d144cc0d72f06ed630a` |
| E04 | desktop-source | 활성 `LlmRouterAspect.java` SHA-256 `1b792feab7fa5e95d6572c9fd9056780d5297a6e10c455f41f0b8520af009ddd` |
| E05 | desktop-source | `LlmGatewayProperties.java` SHA-256 `3cf1452dc68db546a128f98dfad8c4f250866c2e9d43d19438daf80f30de05b7` |
| E06 | desktop-source | root application의 `@EnableScheduling` count `1` |
| E07 | desktop-source | `ModelRuntimeHealthTracker.java` SHA-256 `47bec2aac6ad560a6f12f4f387cf939e4893e6aca25b97f1611b866e20e6e4b9` |
| E08 | desktop-test | `LlmRouterRequestTimelineTest.java` SHA-256 `03a086ecc46a958c1c6f6131660fd6cdc25b561698a9fe6192459cc6cf3cdae3` |
| E09 | notebook-runtime | Java command available `false` |
| E10 | user-request | `system-delegated-policy` |
| E11 | notebook-filesystem | branch `main` from `.git/HEAD` |
| E12 | notebook-filesystem | index lock present `false` |

중요한 증거 해석:

- GPU 진단은 이미 장치별 utilization, VRAM ratio, 3060/3090 role을 제공한다. `[E03]`
- 기존 scheduler를 재사용할 수 있어 새 scheduling framework가 불필요하다. `[E06]`
- route mutation은 활성 `LlmRouterAspect` 한 곳에서만 수행해야 한다. `[E04]`
- 직접 HTTP 성공 row는 `clientHttpExchangeObserved=true`, `clientHttpResponseObserved=true`이면서 `providerAttemptObserved=false`일 수 있다. `[E07,E08]`
- Notebook에는 Java가 없어 Gradle/실제 provider 계보는 증명할 수 없다. `[E09]`

## POSITIVE_QUERY

PositivePacket

- candidateGoal: 예약 GPU 샘플러와 역할별 압력 가드를 기존 단일 라우터에 결합해 새 로컬 채팅 요청만 클라우드로 선제 전환한다.
- scenarioWorlds:
  - `S-FIRST-REQUEST`: startup warm sample과 bounded single-flight로 첫 요청도 hard pressure에서 local model 생성 전에 cloud로 보낸다. `[E03,E06]`
  - `S-DUAL-GPU`: exact route-to-role mapping으로 3060/3090 압력을 서로 분리한다. `[E03,E04]`
  - `S-GOVERNED-OFFLOAD`: 기존 eligibility, route별 quota, cloud-only single attempt, 직접 HTTP lineage를 함께 적용한다. `[E04,E05,E07,E08]`
- validatedAssumptions:
  - 활성 GPU 진단이 역할·사용률·VRAM 신호를 제공한다.
  - 활성 라우터가 local/cloud 선택의 단일 권한 경계다.
  - request timeline이 prompt/options hash와 실제 HTTP 교환·응답을 기록할 수 있다.
- reusableAssets:
  - `GpuHardwareDiagnostics`
  - `LlmRouterAspect`
  - `HybridLlmGatewayProbeService`
  - `ModelRuntimeHealthTracker`
  - `LlmGatewayBreadcrumbPublisher`
  - `demo1-local-llm-gpu-gateway`
- expectedUserValue: 게임·스트리밍 frame 안정성을 우선하면서 정상 상태의 local 비용 이점을 유지한다.
- minimalVerification: 가짜 clock/snapshot으로 상태 전이를 검증하고 loopback server로 local HTTP `0`, cloud HTTP `1`, 정확한 request lineage를 증명한다.
- evidenceIds: `[E03,E04,E05,E06,E07,E08,E10]`
- unknowns: Desktop 실제 GPU sample 분포, usable cloud credential, 게임 중 frame-time delta.

## NEGATIVE_QUERY

NegativePacket

- challengedGoal: 자동 전환이 첫 요청, 다중 GPU, telemetry failure, 비용, cloud error 상황에서도 game-first 원칙을 보장하는지 공격한다.
- scenarioAttacks:
  - `S-FIRST-REQUEST`: scheduler가 아직 실행되지 않았거나 `nvidia-smi`가 멈출 수 있다. 동시 첫 요청 20개에서 외부 프로세스가 한 번보다 많이 실행되면 설계가 반증된다. `[E03,E06]`
  - `S-DUAL-GPU`: 역할 0개/2개 또는 실제 endpoint ownership 불일치가 있을 수 있다. port/index를 추정하면 관련 없는 route가 offload된다. `[E03,E04]`
  - `S-GOVERNED-OFFLOAD`: quota 소진이나 429/timeout 뒤 기존 lazy fallback이 local을 다시 호출할 수 있다. pressure latch에서 local exchange가 하나라도 생기면 설계가 반증된다. `[E04,E05,E07,E08]`
- falsifiers:
  - hard pressure 첫 요청이 local HTTP를 시작한다.
  - 3090 압력이 3060 전용 route에 전파된다.
  - cloud ineligible/quota/error 뒤 local retry가 발생한다.
  - 동일 sample을 읽는 요청 수만큼 hysteresis counter가 증가한다.
  - direct HTTP lineage가 `providerAttemptObserved=true`를 잘못 요구한다.
- authorityRisks: Desktop source root, branch ownership, dirty overlap, source lease는 Desktop 실행 직전에 다시 증명해야 한다.
- safetyRisks: 무제한 paid calls, raw prompt/process/key 노출, route oscillation, scheduler stampede.
- missingEvidence: live Desktop GPU transition, usable cloud credential, actual provider response lineage.
- smallestDisconfirmingProbe: hard pressure 첫 요청 loopback test에서 local exchange `0`, cloud exchange `1`, physical attempt row `1`을 assert한다.
- evidenceIds: `[E03,E04,E05,E06,E07,E08,E09,E11,E12]`

## NEUTRAL_QUERY

NeutralVerdict

- forwardOrder: `[POSITIVE_QUERY, NEGATIVE_QUERY]`
- reverseOrder: `[NEGATIVE_QUERY, POSITIVE_QUERY]`
- forwardVerdict: `APPLY`
- reverseVerdict: `APPLY`
- forwardDecisiveEvidenceIds: `[E03,E04,E06,E07,E08,E10]`
- reverseDecisiveEvidenceIds: `[E03,E04,E06,E07,E08,E10]`
- orderStable: `true`
- verdict: `APPLY`
- selectedOrRewrittenGoal: Desktop이 기존 예약 실행·GPU 진단·단일 라우터·계보 경계 안에서 첫 요청 보호, 역할별 hysteresis, 최소 cloud dwell, 자동 offload 요청 quota, cloud-only 오류 정책을 구현하고 실제 계보를 증명한다.
- goalScore: `79.55`
- decisiveEvidence: `[E03,E04,E06,E07,E08,E10]`
- rejectedClaims:
  - `providerAttemptObserved=true`가 direct HTTP 성공의 필수 조건이다.
  - 게임 프로세스명 감지가 필요하다.
  - telemetry failure 시 자동 paid cloud 전환해야 한다.
  - 새 global skill 또는 두 번째 gateway stack이 필요하다.
- nextSingleProof: Desktop hard-pressure 첫 요청 loopback test로 local HTTP `0`, cloud HTTP `1`, prompt/options hash를 증명한다.
- confidence: `H`

Score inputs:

```text
evidenceStrength=0.91 [E03,E04,E07,E08]
causalStrength=0.89 [E03,E04,E06]
verificationFeasibility=0.84 [E08,E09]
userValue=0.92 [E10]
reversibility=0.96 [E05]
costEfficiency=0.83 [E05,E07]
timeFit=0.78 [E03,E04]
blastRadius=0.35 [E04,E05]
ambiguity=0.12 [E03,E08]
authorityOrSafetyExpansion=0.02 [E10,E11,E12]
goalScore=79.55
```

## GoalContract

- goalId: `gpu-pressure-cloud-offload-20260801-v2`
- rewrittenUserIntent: 사용자의 추가 선택 없이 Desktop RTX 3060/3090 압력을 자동 판정해 새 local LLM 요청을 eligible cloud model로 전환하고, 압력이 회복되면 local로 복귀한다.
- desiredOutcome: startup-safe, role-specific, hysteretic, quota-bounded, request-sticky cloud offload를 기존 gateway boundary 한 곳에 추가한다.
- measurableSuccess:
  - hard threshold 한 sample에서 첫 요청 local HTTP `0`, cloud HTTP `1`
  - soft threshold 3개 고유 sample에서 latch
  - recovery 5개 고유 sample과 30초 dwell 뒤 local 복귀
  - 3060/3090 route 상태 독립
  - cloud unavailable/quota/error에서 local retry `0`
  - 동시 첫 요청 20개에서 hardware runner `1`회 이하
  - direct HTTP request timeline의 prompt/options hash, provider/model/protocol, exchange/response 증명
- nonGoals:
  - game process/window detection
  - local model unload/kill 또는 in-flight migration
  - embedding cloud migration
  - token/금액 billing
  - new provider/dependency/router
  - public API, DB, Supabase, credential mutation
- authorizedMutationSurface: 현재 Notebook turn은 세 Markdown/JSON 산출물만 수정한다. Desktop source 변경은 이 SourceDirective를 받아 별도 구현 세션에서 preflight가 `APPLY`일 때만 허용된다.
- prohibitedSurface: 비활성 mirrors, `app/src/main/java`, `project/src/main/java`, archives/backups/generated outputs, embeddings, public APIs, DB, credentials, provider key names, LangChain4j versions.
- evidenceBaseline: immutable snapshot `a475d44fd209cacdac08d15de746e680d50d2f3e0bff212cedf56be5c0786dbd`, evidence `[E01-E12]`.
- assumptions:
  - Desktop active source is the same revision until preimage recheck.
  - route-to-role defaults match current `application-llm.yaml` ownership.
  - current credential guards reject blank/dummy/test/placeholder values.
- constraints:
  - feature/cloud disabled by default; observe before enforce
  - hard `95%` utilization or `0.92` VRAM ratio
  - soft `85%` or `0.82` for 3 unique samples
  - exit `60%` and `0.70` for 5 unique samples + 30s dwell
  - 2s sampling, 6s stale, 1.1s first-sample wait
  - route별 최대 60 offloads/hour default
  - pressure-selected cloud request has no same-request local fallback
- verificationOwner: `desktop`
- verificationCommands: `docs/superpowers/plans/2026-08-01-gpu-pressure-cloud-offload.md` Task 6의 focused tests, `compileJava`, `:app:classes`, `bootJar`, lineage/rollback/secret-scan 명령.
- rollback: `LLM_GATEWAY_GPU_PRESSURE_ENABLED=false`, 그 뒤 선언된 files만 revert.
- stopConditions:
  - index lock, dirty overlap, lease/preimage conflict
  - wrong sourceSet/mirror 또는 mixed LangChain4j
  - hardware runner stampede
  - pressure request local HTTP exchange
  - observe/disabled route mutation
  - secret/raw-process/prompt leakage
  - missing attempt/response lineage
- timeBudgetMinutes: `360`
- goalScore: `79.55`
- verdict: `APPLY`
- evidence_needed: Desktop source/branch/lease preflight, Java/Gradle output, live 3060/3090 transition, cloud credential presence boolean, actual request timeline rows.

## SourceDirective

- directiveId: `desktop-gpu-pressure-cloud-offload-v2`
- sourceOwner: `desktop`
- provenRoot: `Y:\` is the proven Notebook evidence root; Desktop mutation root must be re-proven before execution.
- provenBranch: `main` from Notebook filesystem evidence; Desktop branch/ownership re-proof required.
- activeSourceSets:
  - root `main/java`
  - root `main/resources`
  - root `src/test/java`
  - `app/src/main/java_clean` and `app/src/main/resources` are active but excluded from this feature
- targetFiles:
  - create `main/java/com/example/lms/llm/gateway/GpuPressureMonitor.java`
  - create `main/java/com/example/lms/llm/gateway/GpuPressureRouteGuard.java`
  - create `main/java/com/example/lms/llm/gateway/CloudOffloadQuotaGuard.java`
  - modify `main/java/com/example/lms/llm/gateway/LlmGatewayProperties.java`
  - modify `main/java/com/example/lms/llm/gateway/LlmGatewayBreadcrumbPublisher.java`
  - modify `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`
  - modify `main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java`
  - modify `main/resources/application-llm.yaml`
  - create `src/test/java/com/example/lms/llm/gateway/LlmGatewayPropertiesGpuPressureTest.java`
  - create `src/test/java/com/example/lms/llm/gateway/GpuPressureMonitorTest.java`
  - create `src/test/java/com/example/lms/llm/gateway/GpuPressureRouteGuardTest.java`
  - create `src/test/java/com/example/lms/llm/gateway/CloudOffloadQuotaGuardTest.java`
  - modify `src/test/java/com/example/lms/llm/gateway/LlmGatewayBreadcrumbPublisherTest.java`
  - modify `src/test/java/ai/abandonware/nova/orch/aop/LlmRouterRequestTimelineTest.java`
- callPathOrBoundary: scheduled `GpuHardwareDiagnostics.snapshot` -> `GpuPressureMonitor` immutable role state -> `DynamicChatModelFactory.lcWithTimeout(..)` -> `LlmRouterAspect.routeWithGateway(..)` -> `GpuPressureRouteGuard.decide(..)` -> existing `fallbackSelection(..)` -> `HybridLlmGatewayProbeService.evaluate(..)` -> `CloudOffloadQuotaGuard.tryAcquire(..)` -> existing model builder -> `ModelRuntimeHealthTracker`.
- beforeBehavior: GPU pressure is not part of local route selection; cloud is used only by existing ineligibility/provider-failure fallback.
- afterBehavior: mapped local route의 fresh hard 또는 sustained soft pressure가 확인되면 local model construction 전에 eligible cloud route만 선택한다. cloud가 unusable/quota-exhausted/error이면 local retry 없이 typed expected/provider failure를 남긴다. clean recovery 뒤 local로 복귀한다.
- excludedFilesAndMirrors:
  - inactive `java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`
  - `app/src/main/java`, `project/src/main/java`, `demo-1`, `lms-core`
  - `GpuHardwareDiagnostics.java` 자체 변경
  - embeddings, model manifests, provider credential configuration
  - backups, archives, `.gradle`, `build*`, `node_modules`
- publicApiChange: `forbidden`
- secretMutation: `forbidden`
- redTest:
  - hard/soft/recovery/distinct-sequence/single-flight tests fail because monitor is absent.
  - guard observe/enforce and quota tests fail because policy classes are absent.
  - hard-pressure first request local `0`/cloud `1` test fails because router does not consume pressure.
  - cloud 429 local-retry `0` test fails because preemptive cloud-only branch is absent.
- greenTest:
  - hard sample immediate latch; soft third distinct sample latch
  - fifth clean sample + 30s dwell recovery
  - 3060/3090 route isolation
  - 20 concurrent first reads -> runner count at most 1
  - enforce pressure -> cloud exchange 1, local exchange 0
  - unavailable/quota exhausted -> both exchange counts 0
  - cloud 429/timeout -> cloud exchange 1, local exchange 0
  - direct HTTP lineage accepts providerAttempt false when client exchange/response and response evidence are true
  - existing lazy fallback tests remain green
- exactVerificationCommands: `docs/superpowers/plans/2026-08-01-gpu-pressure-cloud-offload.md`의 Task 0~6 PowerShell commands를 순서대로 실행한다. host-local Gradle/project cache와 split build outputs를 유지하고 boot processes를 병렬화하지 않는다.
- expectedEvidence:
  - Desktop preimage/postimage SHA-256 and declared-file diff
  - focused test expected/observed result table
  - `compileJava`, `:app:classes`, `bootJar` results
  - sample sequence/counter/latch/dwell/quota bounded trace values
  - local/cloud HTTP exchange counts
  - prompt/options hash presence booleans
  - provider/model/protocol and client exchange/response booleans
  - count-only secret scan `0`
- failureClassifications:
  - `gpu_pressure_hard_latched`
  - `gpu_pressure_soft_pending`
  - `gpu_pressure_soft_latched`
  - `gpu_pressure_recovered`
  - `gpu_telemetry_unavailable_observe`
  - `gpu_telemetry_unavailable_latched`
  - `gpu_role_missing`
  - `gpu_role_ambiguous`
  - `gpu_pressure_cloud_unavailable`
  - `gpu_pressure_cloud_budget_exhausted`
  - existing `AUTH_MISSING`, `RATE_LIMIT_COOLDOWN`, `TIMEOUT_SOFT`, provider failure classes
- rollback: feature flag off -> disabled regression -> declared-file revert only. Provider settings, credentials, existing fallback code는 보존한다.
- patchdropContract: Desktop direct execution에는 불필요하다. 별도 producer lane을 선택할 때만 단일 cumulative v3 bundle과 Desktop consumer verification을 요구한다.
- runtimeLineageVerdict: `HOLD`
- desktopFinalProof: `evidence_needed`

## Capability contract

- asset: project-local GoalContract/SourceDirective + deterministic triad run artifact
- globalSkillDecision: 새 global skill을 만들지 않는다. 설치된 `demo1-local-llm-gpu-gateway`가 trigger와 경계를 이미 소유하므로 이 프로젝트 계약으로 확장한다.
- trigger: Desktop에서 GPU-pressure 기반 local-to-cloud routing을 구현·검증할 때.
- nonTrigger: 일반 provider outage, embeddings, game process detection, model process management, cloud model 추천만 요청할 때.
- owner: Notebook은 contract/docs; Desktop은 application source와 final proof.
- mutationSurface: 위 SourceDirective target files only, Desktop preflight APPLY 후.
- inputSchema: routeKey, provider, stage, immutable role pressure snapshot, enforcement mode, cloud eligibility, route quota.
- outputSchema: `NOT_APPLICABLE | LOCAL_ALLOWED | CLOUD_WOULD_BE_REQUIRED | CLOUD_REQUIRED` + bounded reason/counters; router result는 local, cloud-only, typed expected failure 중 하나.
- timeout: scheduled sample interval 2s, existing hardware timeout 1s, first sample wait 1.1s, stale 6s.
- budget: implementation hard cap 360 minutes; route auto-offload default 60 per route per hour.
- boundedOutput: allowlisted keys와 count/hash/boolean/reason만 기록.
- redaction: key/header/prompt/response/raw process/GPU stdout/environment dump 금지.
- failureMode: latch 전 telemetry failure는 fail-soft local observe; latch 후 telemetry failure와 cloud failure는 game-first로 local inference에 fail-closed.
- rollback: pressure feature flag off, then declared-file revert.
- nonDuplicationEvidence: parser, scheduler, router, eligibility, model builder, failure classifier, breadcrumb redaction, request lineage를 모두 재사용한다.
- falsifyingTest: pressure-selected 요청에서 local `clientHttpExchangeObserved=true` row가 하나라도 나타나거나 cloud physical attempt가 둘 이상이면 capability가 틀렸다.

## Desktop final proof attempt — 2026-08-02

- attemptArtifact: `verification/gpu-pressure-cloud-offload/desktop-final-proof-attempt-2026-08-02.md`
- triadArtifact: `agent-prompts/codex_gpu_pressure_desktop_final_proof.run.json`
- evidenceSnapshotHash: `697892084077d831893b4f36712ba5fe8e0c34873c281f968e025bb2af7d9db7`
- backingShareIdentityVerified: `true`
- desktopRemoteExecutionLaneCount: `0`
- sharedImplementationFileCount: `0`
- sharedFocusedMethodCount: `0`
- desktopProofArtifactCount: `0`
- attemptVerdict: `HOLD`
- failureClassifications:
  - `desktop-execution-channel-unavailable`
  - `focused-test-missing`
  - `desktop-proof-missing`
- runtimeLineageVerdict: `HOLD`
- desktopFinalProof: `evidence_needed`
- holdReleased: `false`
- nextSingleProof: Desktop canonical root에서 `LlmRouterRequestTimelineTest.hardPressureFirstRequestUsesCloudWithoutLocalExchange`를 실행해 local HTTP `0`, cloud HTTP `1`, prompt/options hash presence를 하나의 redacted artifact로 기록한다.
