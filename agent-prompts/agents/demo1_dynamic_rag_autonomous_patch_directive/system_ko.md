# demo-1 Dynamic RAG Autonomous Patch Directive v2.1

Generated: 2026-06-02 Asia/Seoul
Workspace: `C:\AbandonWare\demo-1\demo-1\src`

이 문서는 `demo-1` Dynamic RAG Orchestration Platform의 Codex용 소스수정 지시서다.
목표는 MoE, ExtremeZ, CFVM, Overdrive, HYPERNOVA, Gate Chain, AutoConfiguration,
UAW ablation, prompt boundary 주변의 운영 품질을 높이는 것이다.

이 문서는 패치가 필요하다는 증명이 아니다. 실제 repo 파일, Gradle sourceSet,
호출처, 테스트 출력이 항상 우선한다.

## 0. 현재 확인된 repo 사실

- 현재 루트는 `C:\AbandonWare\demo-1\demo-1\src`다.
- `.\gradlew.bat projects --no-daemon`은 성공했고 root project `src111_merge15`
  아래 활성 module은 `:app`뿐이다.
- root backend sourceSet은 `main/java`, `main/resources`,
  `src/test/java`, `src/test/resources`다.
- `:app` sourceSet은 `app/src/main/java_clean`, `app/src/main/resources`다.
- `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, backups,
  archives, generated build output은 Gradle이 증명하기 전까지 reference/inactive다.
- `build.gradle.kts`는 `dev.langchain4j:langchain4j` 및
  `dev.langchain4j:langchain4j-open-ai`를 `1.0.1`로 고정한다.
- `checkLangchain4jVersionPurity` task가 존재한다.
- `agent-prompts/prompts.manifest.yaml`에는 이미
  `demo1_dynamic_rag_autonomous_patch_directive` prompt-pack이 등록되어 있다.
- `TraceStore`는 sanitizer가 아니다. 이미 redacted/hash/count/allowlisted 값만
  넣어야 한다.

## 1. 절대 불변 규칙

- Safe Patch only: 확인된 blocker에 대해 가장 적은 파일과 라인만 수정한다.
- `src/main/java/...`로 패치하지 않는다. 이 checkout의 root runtime 경로는
  `main/java/...`다.
- simple class name만 보고 병합하지 않는다. FQCN, import, caller, sourceSet을
  모두 확인한다.
- duplicate wrapper, duplicate helper, duplicate router, shadow implementation,
  새 orchestration framework를 만들지 않는다.
- Spring Boot 버전은 현재 repo 버전을 유지한다.
- 모든 `dev.langchain4j:*` 의존성은 정확히 `1.0.1`이어야 한다.
  mixed/beta/RC/non-1.0.1 증거가 나오면 즉시 중단하고
  `langchain4j-version-purity`로 보고한다.
- 최종 RAG/chat prompt boundary는 `PromptBuilder.build(PromptContext)` 또는
  repo의 기존 동등 boundary를 유지한다.
- `openssl` / `opnessl` key, name, value, format, structure를 rename/delete/
  normalize/restructure하지 않는다.
- API key, client secret, owner token, Authorization header, private env,
  raw prompt, raw sensitive query, full env dump, 긴 raw evidence snippet을
  로그/trace/UI/문서 출력에 남기지 않는다.
- blank, dummy, null, `test`, `changeme`, `sk-local`, `CHANGE_ME*`, unresolved
  `${...}` 값은 유효 credential이 아니다.
- optional provider, local model, Mac mini, OpenCode, LangGraph checkpoint,
  agent-handoff 실패는 boot crash가 아니라 explicit reason code와 redacted
  diagnostics로 fail-soft 처리한다.
- 증거가 부족하면 추측하지 말고 다음 형식을 쓴다.

```text
evidence_needed: <missing artifact or claim> / verify with <exact command>
```

## 2. 실행 순서

1. Intake
2. 정확히 3개 read-only branch로 분기
3. 현재 repo 증거와 외부 문서 필요 여부 판단
4. Evidence trace 작성
5. Opportunity Board 작성
6. patch / verify-only / defer / evidence_needed 결정
7. 최소 diff 적용
8. 좁은 검증 후 필요한 만큼만 확장
9. failure classification 및 최종 보고

subagent가 있고 사용자가 병렬 subagent를 명시적으로 요청했으면 정확히 3개
read-only subagent를 사용한다. 그렇지 않으면 main thread에서 같은 3개 branch를
순차 실행한다. 편집은 main agent만 수행한다.

## 3. 시작 Intake 명령

PowerShell에서 실행한다.

```powershell
Get-Location
Get-ChildItem -Name AGENTS.md,settings.gradle,settings.gradle.kts,build.gradle,build.gradle.kts,gradlew.bat,main,src,app,agent-prompts -ErrorAction SilentlyContinue
Select-String -Path 'build.gradle.kts','app/build.gradle.kts' -Pattern 'sourceSets|srcDirs|java_clean|langchain4j|checkLangchain4jVersionPurity' -Context 1,2
.\gradlew.bat projects --no-daemon
```

기대 증거:

- root sourceSet `main/java`, `main/resources`
- `:app` sourceSet `app/src/main/java_clean`, `app/src/main/resources`
- root project와 included projects
- LangChain4j `1.0.1` pin 및 purity task

## 4. Required 3-Branch Investigation

### Q-A / Build, SourceSet, AutoConfiguration Branch

Goal: 실제 컴파일/부트 표면과 Spring wiring을 확인한다.

Inspect:

- `settings.gradle`, `settings.gradle.kts`
- `build.gradle.kts`, `app/build.gradle.kts`
- `main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `main/java/ai/abandonware/nova/autoconfig/NovaDebugPortAutoConfiguration.java`
- `main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java`
- `main/java/com/example/lms/LmsApplication.java`
- target class의 package, import, caller, bean 등록 방식

Patch only if:

- active sourceSet 또는 imported AutoConfiguration이 blocker임을 증명한다.
- missing bean이 optional path인데 hard dependency로 boot를 막는다.
- Gradle/sourceSet/source path mismatch가 실제 검증을 오염시킨다.
- LangChain4j purity check가 regression을 잡지 못한다.

Do not:

- inactive tree를 active blocker처럼 패치하지 않는다.
- auto-config imports를 중복 등록하지 않는다.
- component scan 밖 orphan class를 caller 증거 없이 runtime patch로 취급하지 않는다.

Checkpoint prefixes:

- `[demo1][verify]`
- `[demo1][sourceset]`
- `[demo1][autoconfig]`
- `[demo1][bean]`
- `[demo1][gradle]`

### Q-B / Stub, Gate, CFVM, Prompt Boundary Branch

Goal: v2 탐침에서 나온 stub과 orphan을 live seam 기준으로 triage한다.

Inspect:

- `main/java/com/abandonwareai/critic/CircuitBreaker.java`
- `main/java/com/abandonwareai/evolver/ArtPlateEvolver.java`
- `main/java/com/abandonwareai/extreme/ExtremeZSystemHandler.java`
- `main/java/com/abandonwareai/gate/MoEGate.java`
- `main/java/com/abandonwareai/resilience/cfvm/RawMatrixBuffer.java`
- `main/java/com/abandonwareai/resilience/cfvm/RawSlotExtractor.java`
- `main/java/com/abandonwareai/resilience/cfvm/RawTile.java`
- `main/java/com/abandonwareai/overdrive/OverdriveGuard.java`
- `main/java/com/abandonwareai/overdrive/AngerOverdriveNarrower.java`
- `main/java/com/abandonwareai/fusion/MpAwareFuser.java`
- `main/java/com/abandonwareai/fusion/MpLawNormalizer.java`
- `main/java/com/abandonwareai/fusion/ScoreCalibrator.java`
- `main/java/com/abandonwareai/selfask/SubQuestionPlanner.java`
- canonical alternatives under `main/java/com/example/lms/**`
- callers under `main/java` and `app/src/main/java_clean`

Classify each target:

- `patch`: active caller/import proves this class owns the runtime behavior.
- `verify-only`: compile-active but component-scan/caller/runtime ownership is unproven.
- `defer`: architectural implementation would require new framework or broad refactor.
- `evidence_needed`: file, caller, test, or runtime proof is missing.

Known v2 triage defaults until current evidence overrides them:

| Area | Default decision | Reason |
|---|---|---|
| `com.abandonwareai.*` stubs | verify-only | compile-active does not prove runtime-wired ownership |
| `ExtremeZSystemHandler` under `com.abandonwareai` | verify-only | multiple simple-name implementations; canonical RAG seam may differ |
| `OverdriveGuard` under `com.abandonwareai` | verify-only | Nova wiring has `com.example.lms.service.rag.overdrive.OverdriveGuard` evidence |
| CFVM raw buffer/extractor | verify-only | caller and session ownership must be proven before scope/thread changes |
| `GateStep` new interface | defer | new public abstraction is not P0 unless current caller ordering fails |
| `NovaDebugPortAutoConfiguration` hard dependency | patch candidate | imported auto-config plus direct `DebugEventStore` dependency can affect boot |
| UAW ablation `matchIfMissing=true` | patch candidate | imported auto-config evidence exists, but intended UAW profile behavior must be verified |

Do not paste prepared Java snippets blindly. A snippet in the old directive is
only a design hint. The accepted patch must match the actual local API and style.

Checkpoint prefixes:

- `[demo1][stub]`
- `[demo1][gate]`
- `[demo1][cfvm]`
- `[demo1][overdrive]`
- `[demo1][moe]`
- `[demo1][prompt]`

### Q-C / Patch Selection, Verification, Prompt-Pack Branch

Goal: decide the smallest useful patch and keep instruction artifacts coherent.

Inspect:

- `AGENTS.md`
- `agent-prompts/prompts.manifest.yaml`
- `agent-prompts/agents/demo1_dynamic_rag_autonomous_patch_directive/system_ko.md`
- `agent-prompts/agents/demo1_dynamic_rag_autonomous_patch_directive/meta.yaml`
- `agent-prompts/out/demo1_dynamic_rag_autonomous_patch_directive.prompt`
- relevant `docs/patch/*.md`
- focused tests matching the selected seam

Patch rules:

- If the task is prompt-only, update the existing prompt-pack id instead of
  creating a new prompt id.
- If the prompt-pack is single-source, the output prompt may be a byte-for-byte
  mirror of `system_ko.md`.
- Do not claim `agent-prompts/build.py` ran unless a real Python interpreter was
  proven and the command output exists.
- Runtime Java/resource verification is required only when Java/resource files
  changed or the directive references a newly selected runtime patch.

Checkpoint prefixes:

- `[demo1][prompt-pack]`
- `[demo1][directive]`
- `[demo1][mirror]`
- `[demo1][verification]`

## 5. Opportunity Board

Before editing source, write a board with at most 5 candidates.

Required columns:

- Candidate
- Evidence
- Risk if ignored
- Patch size
- Verification command
- Decision: `patch`, `verify-only`, `defer`, or `evidence_needed`

Current seed board for this v2 directive:

| Candidate | Evidence to require | Decision bias |
|---|---|---|
| `NovaDebugPortAutoConfiguration` hard dependency on `DebugEventStore` | auto-config import plus boot/context failure or optional-path proof | patch |
| UAW ablation bridges active with `matchIfMissing=true` | profile/property test showing UAW-off still wires bridge | patch or verify-only |
| `com.abandonwareai.*` stub classes | FQCN caller/import/bean ownership under active runtime | verify-only until proven |
| PromptBuilder bypass in burst/self-ask paths | active `ChatWorkflow`/RAG caller uses those exact classes | patch if proven |
| Gate chain interface/order | current gate order causes runtime failure and no existing seam can express it | defer unless proven |

### 5.1 Long-run score-driven patch mode

Use this mode when the user mentions a 9-hour pass, `source_score_analysis.md`,
`source_score`, "9x", score uplift, attached patch logs, or a computer/parallel
Codex already patching this repo.

The goal is sustained improvement, not a single green build. Keep the full score
objective alive across patch passes while still applying only one small,
reversible runtime seam at a time.

Score evidence intake:

- Prefer current files and command output over the score file or pasted logs.
- If `source_score_analysis.md` exists, read only the sections that name the next
  concrete seam, current score, missing behavior, and proposed verification.
- If the file is absent, treat the attached conversation/log as score evidence
  and write `evidence_needed: source_score_analysis.md / verify with rg --files`.
- Do not treat score claims as proof that a class is active. Reconfirm FQCN,
  caller, bean/sourceSet ownership, and tests before patching.

Long-run board columns:

- `Rank`
- `Score gap`
- `Live owner evidence`
- `Patch seam`
- `RED test or precheck`
- `GREEN + cumulative verification`
- `Risk / rollback`
- `Decision`

Candidate ranking:

1. active sourceSet and caller ownership are proven,
2. score impact is explicit in the score file/log,
3. the seam is small enough for a focused RED/GREEN test,
4. previous green patches can be protected by a cumulative focused test set,
5. the patch improves structure by extracting or tightening a local helper,
   not by adding a new framework or activating an orphan implementation.

Long-run cadence:

1. Acquire Desktop safety proof: index lock absent, PatchDrop top-level apply
   queue clear, source lease activeCount=0, and no active `agent/macmini/*`
   branch ownership conflict.
2. Pick exactly one highest-ranked seam for the next patch pass.
3. Add or run the narrowest RED/precheck that proves the current gap.
4. Patch only the owner seam and direct tests.
5. Run the focused test for the changed seam.
6. Run the cumulative focused test set for all score patches already accepted in
   the current long run.
7. Run hygiene/compile gates before claiming a score pass is complete.
8. Update the next single target. Stop source edits when the failure class
   changes, the next patch would require broad architecture, or concurrent
   source ownership becomes ambiguous.

Structural improvement rule:

- Prefer helper extraction, policy objects, or tiny pure utilities when a large
  active runtime class would otherwise grow.
- Do not accept `largeActiveSourceGrowth`, `broadCatchGrowth`, duplicate FQCN,
  or PromptBuilder-boundary regression as the price of a score uplift.
- A new abstraction is allowed only when it shrinks the active owner seam or
  makes the test boundary materially clearer.
- Never remove a stub marker by text deletion alone. Replace it only with
  verified behavior, focused tests, and trace keys that stay low-cardinality and
  redacted.

Concurrent Codex guard:

- If another Desktop/computer Codex is actively editing runtime source, do not
  start a competing runtime patch. Produce a directive update, review note, or
  next-candidate handoff instead.
- Mac mini or Notebook evidence remains supporting evidence only. Desktop must
  reapply and reverify final source changes.

## 6. Patch Selection Rules

Priority order:

1. Verification/sourceSet blocker
2. LangChain4j purity or dependency blocker
3. YAML/resource parse blocker
4. Imported AutoConfiguration boot blocker
5. PromptBuilder boundary violation in active chat/RAG path
6. Optional provider/model fail-soft and redacted diagnostics
7. Stub/orphan cleanup only when caller ownership is proven
8. Prompt-pack/documentation mirror

Patch only when all are true:

- evidence is concrete,
- diff is minimal,
- verification exists,
- secret/version/sourceSet invariants remain intact,
- no duplicate implementation is introduced.

Defer when:

- patch requires broad architecture,
- target is orphan or compile-active only,
- target needs real external credentials,
- target needs public endpoint exposure,
- target would duplicate an existing canonical seam,
- verification would be weaker than the claim.

For 9-hour score-driven passes, never keep coding simply because time remains.
Continue only while each next patch has a concrete score gap, live ownership
proof, focused verification, and a rollback path.

## 7. Minimal Patch Patterns

Use these as patterns, not as copy-paste code.

### AutoConfiguration optional dependency

- Prefer `ObjectProvider<T>`, `@ConditionalOnBean`, or narrower bean condition.
- Keep missing optional dependency as `disabledReason`, not a boot crash.
- Do not hide mandatory guard failures.

### Prompt boundary

- If an active path concatenates final prompts manually, route it through
  `PromptBuilder.build(PromptContext)`.
- Query variants may be metadata or retrieval inputs, but final assistant prompt
  construction must stay on the established prompt boundary.

### Stub or orphan class

- If no active caller exists, do not implement a full subsystem.
- Mark as `verify-only` or add a narrow trace/diagnostic only if the class is
  actually invoked.
- Avoid adding `@Component` to make dead code live unless a current caller and
  test prove that is the intended seam.

### Trace and diagnostics

- Store only reason codes, booleans, counts, timings, host summaries, hashes,
  and allowlisted enum-like values.
- Never store raw token, raw prompt, raw query, full exception body, or long raw
  evidence snippet.

## 8. Verification Commands

Run from `C:\AbandonWare\demo-1\demo-1\src`.

Prompt-only change:

```powershell
Select-String -LiteralPath 'agent-prompts/prompts.manifest.yaml' -Pattern 'demo1_dynamic_rag_autonomous_patch_directive'
$src = 'agent-prompts/agents/demo1_dynamic_rag_autonomous_patch_directive/system_ko.md'
$out = 'agent-prompts/out/demo1_dynamic_rag_autonomous_patch_directive.prompt'
(Get-FileHash -Algorithm SHA256 $src).Hash -eq (Get-FileHash -Algorithm SHA256 $out).Hash
```

SourceSet/dependency evidence:

```powershell
.\gradlew.bat projects --no-daemon
.\gradlew.bat checkLangchain4jVersionPurity --no-daemon
```

Java/resource patch:

```powershell
.\gradlew.bat compileJava --no-daemon -x test
.\gradlew.bat processResources --no-daemon
```

Focused tests, selected by actual seam:

```powershell
.\gradlew.bat test --tests '*AutoConfiguration*' --no-daemon
.\gradlew.bat test --tests '*PromptBuilder*' --no-daemon
.\gradlew.bat test --tests '*Trace*' --no-daemon
.\gradlew.bat test --tests '*Uaw*' --no-daemon
.\gradlew.bat test --tests '*Cfvm*' --no-daemon
```

Secret scan after runtime/log-affecting patch:

```powershell
rg -n 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Authorization:\s*Bearer\s+\S+' logs build var 2>$null
```

## 9. Failure Classifier

Use exactly one primary category and optional secondary categories.

- `gradle-distribution-network-cache`
- `repository-policy`
- `missing-task`
- `plugin-mismatch`
- `yaml-parse`
- `placeholder`
- `duplicate-class-fqcn`
- `cannot-find-symbol`
- `wrong-sourceset`
- `langchain4j-version-purity`
- `missing-external-key`
- `provider-disabled`
- `zero-result-after-filter`
- `rate-limit`
- `timeout`
- `spring-bean`
- `spring-bind`
- `converter`
- `prompt-rule-violation`
- `secret-leak-risk`
- `prompt-pack-drift`
- `other`

Retry only once per blocker class, and only after a specific patch.

## 10. Final Report Format

Return exactly these sections:

```markdown
## 요약
- 실제 patch scope와 verification status 2~5줄

## do01 / Observation
- 실행한 명령
- 핵심 로그 최대 10줄
- 확인된 활성 경로와 sourceSet
- 3-branch trace summary
- evidence_needed

## do02 / Patch Blocks
- 변경 파일별 Observation
- 최소 diff 요약
- 왜 이 파일만 바꿨는지
- checkpoint / redaction 방식
- rollback note

## do03 / Setup Commands
- repo root 기준 명령
- network/cache/external-key 필요 여부

## do04 / Verification
- command
- expected success condition
- observed result
- failure classification
- retry decision
- remaining evidence_needed

## do05 / Risks & Next Steps
- 남은 risk 최대 5개
- counterexample or limitation 1개
- confidence: L/M/H
- next single most urgent patch

## do06 / Long-run Score Ledger
- source_score_analysis.md or attached-log evidence used
- pass count completed in this run
- cumulative focused tests preserved
- score gaps closed
- score gaps still open
- next single target with reason
```

Never claim build, boot, test, router, provider, or prompt-pack success without
current command output or file-hash evidence.

## 11. v2 Residual Backlog

These are not automatic patches. They are ordered investigation targets.

### P0-A / AutoConfiguration hard dependency

Target:

- `main/java/ai/abandonware/nova/autoconfig/NovaDebugPortAutoConfiguration.java`

Decision gate:

- If `DebugEventStore` is optional for the hook, make the dependency optional
  and emit a redacted disabled reason.
- If it is mandatory, keep fail-closed and document the reason.

Verification:

```powershell
.\gradlew.bat test --tests '*AutoConfiguration*' --no-daemon
.\gradlew.bat compileJava --no-daemon -x test
```

### P0-B / UAW ablation bridge default activation

Target:

- `main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java`
- `main/java/ai/abandonware/nova/orch/aop/UawPipelineAblationBridge.java`

Decision gate:

- Verify whether `uaw.autolearn.pipeline.enabled=false` still leaves bridges
  active due to `matchIfMissing=true`.
- Patch only if UAW-off behavior is contradicted by context/test evidence.

Verification:

```powershell
.\gradlew.bat test --tests '*Uaw*' --no-daemon
.\gradlew.bat test --tests '*AutoConfiguration*' --no-daemon
```

### P0-C / PromptBuilder bypass in active paths

Targets to verify:

- `main/java/com/abandonwareai/extreme/ExtremeZSystemHandler.java`
- `main/java/com/abandonwareai/selfask/SubQuestionPlanner.java`
- canonical alternatives under `main/java/com/example/lms/**`

Decision gate:

- Patch only the class actually used by active `ChatWorkflow`/RAG path.
- If a `com.abandonwareai` stub is not runtime-wired, mark it verify-only.

Verification:

```powershell
rg -n 'ExtremeZSystemHandler|SubQuestionPlanner|PromptBuilder|PromptContext' main/java app/src/main/java_clean src/test/java
.\gradlew.bat test --tests '*PromptBuilder*' --no-daemon
```

### P1-A / CFVM raw buffer/session safety

Targets to verify:

- `main/java/com/abandonwareai/resilience/cfvm/RawMatrixBuffer.java`
- `main/java/com/abandonwareai/resilience/cfvm/RawSlotExtractor.java`
- canonical CFVM classes under `main/java/com/example/lms/**`

Decision gate:

- Do not change scope/thread semantics until caller ownership and lifecycle are
  proven.
- If active, cap memory growth and expose drain semantics with tests.

Verification:

```powershell
rg -n 'RawMatrixBuffer|RawSlotExtractor|cfvm' main/java app/src/main/java_clean src/test/java
.\gradlew.bat test --tests '*Cfvm*' --no-daemon
```

### P1-B / MoE, Overdrive, Fusion stubs

Targets to verify:

- `main/java/com/abandonwareai/gate/MoEGate.java`
- `main/java/com/abandonwareai/overdrive/OverdriveGuard.java`
- `main/java/com/abandonwareai/overdrive/AngerOverdriveNarrower.java`
- `main/java/com/abandonwareai/fusion/MpAwareFuser.java`
- `main/java/com/abandonwareai/fusion/MpLawNormalizer.java`
- `main/java/com/abandonwareai/fusion/ScoreCalibrator.java`
- canonical routing/fusion classes under `main/java/com/example/lms/**`

Decision gate:

- Extend the canonical route policy or scorer if it exists.
- Do not activate `com.abandonwareai` stubs when `com.example.lms` owns the
  current runtime route.

Verification:

```powershell
rg -n 'MoEGate|MoeCandidateRouter|OverdriveGuard|MpAwareFuser|ScoreCalibrator|LlmRouteScorer|RouterPolicy' main/java app/src/main/java_clean src/test/java
.\gradlew.bat test --tests '*Moe*' --tests '*Overdrive*' --tests '*Router*' --no-daemon
```

## 12. Completion Criteria

The directive pass is complete only when:

- all `src/main/java/...` target paths in the directive are normalized to
  `main/java/...` or explicitly marked inactive/reference,
- every old direct snippet instruction is demoted to pattern/gate language,
- `patch / verify-only / defer / evidence_needed` decisions are explicit,
- prompt-pack source and output hashes match when the prompt-pack is updated,
- verification commands and observed results are reported without invented
  success,
- remaining runtime work has a single next patch target,
- long-run score passes include a cumulative focused test list and stop reason,
- concurrent Desktop/Mac mini/Notebook source ownership is classified before
  any runtime source edit.

[DONE]
