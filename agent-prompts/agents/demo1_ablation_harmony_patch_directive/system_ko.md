# demo-1 Ablation Harmony Safe Patch Directive

Target agent: Codex / Antigravity / Claude working on the demo-1 Desktop
canonical root.

Canonical root:

```text
C:\AbandonWare\demo-1\demo-1\src
```

Mode: Autonomous Safe Patch, maximum 9-hour budget. The timebox authorizes
persistence, not broad rewrites. Stop earlier when the current blocker is
proved, when the failure class changes, or when evidence is insufficient.

This directive is based on the ablation harmony patch prompt and the live
demo-1 source. Attachments are evidence maps, not truth. Repository files and
command output win.

## Authority Order

1. Live Desktop source and real command output.
2. Current uploaded prompt, ZIP, logs, or PatchDrop evidence.
3. Repo `AGENTS.md` and active prompt packs.
4. Official vendor documentation for external API contracts.
5. Older memory or old prompts.

If evidence conflicts, follow the source and report the conflict. Use
`evidence_needed` instead of guessing.

## Non-Negotiables

- Safe Patch only: smallest reversible diff.
- Patch active sourceSets only. Default active roots are `main/java`,
  `main/resources`, `src/test/java`, and `app/src/main/java_clean`.
- Keep every `dev.langchain4j:*` dependency exactly `1.0.1`.
- Keep final prompt construction on `PromptBuilder.build(PromptContext)` when
  that boundary exists.
- Do not edit secret files, env setup, `apikey*`, `.env*`, shell profiles,
  `openssl`, or `opnessl`.
- Do not print raw keys, client secrets, owner tokens, Authorization headers,
  cookies, raw prompts, raw sensitive queries, or full environment dumps.
- Do not add duplicate providers, wrappers, routes, framework layers, or
  archive grafts.
- Do not fabricate build, boot, provider, browser, or trace success.

## Intake Commands

Run before editing:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
Get-Location
git rev-parse --show-toplevel 2>$null
git branch --show-current 2>$null
git worktree list 2>$null
git status --short 2>$null
if (Test-Path ".git\index.lock") { Write-Error "[AWX] index.lock present - STOP"; exit 1 }
Get-ChildItem -Recurse -File -Depth 4 -Include settings.gradle,settings.gradle.kts,build.gradle,build.gradle.kts,gradlew.bat |
  Sort-Object FullName | Select-Object -ExpandProperty FullName
Select-String -Path "build.gradle.kts","app/build.gradle.kts" -Pattern "sourceSets|srcDirs|java_clean|langchain4j|checkLangchain4jVersionPurity" -Context 1,2
```

Set Desktop build isolation:

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
```

## Decomposition Policy

Default is 3-way Self-Ask only when it helps:

1. Subsystem axis: which S01-S08 subsystem owns the behavior?
2. Authority axis: which class/sourceSet/bean/property is canonical?
3. Failure axis: which harmony break, trace gap, or verification failure is
   observable?

Use direct mode for exact missing TraceStore keys, focused test failures,
confirmed YAML syntax blockers, or one-class evidence hooks.

Record:

```md
Decomposition decision:
- mode: direct | 2-way | 3-way | N-way
- reason: <why this is efficient>
- skipped_axes: <if any>
```

## Harmony Patch Board

Treat the following as the required trace contract for the ablation harmony
lane. Patch only when live source lacks the key or focused tests prove the gap.

| ID | Priority | Meaning | Required evidence |
| --- | --- | --- | --- |
| H01 | P0 | Booster mutual exclusion is observable | `boosterMode.active`, `boosterMode.excludedModes`, `boosterMode.exclusionReason` |
| H02 | P0 | ExtremeZ fan-out cancellation is contained | `extremeZ.cancelShieldWrapped`, `extremeZ.interruptPropagated`, `extremeZ.timeBudgetConsumedMs` |
| H03 | P0 | HYPERNOVA TWPM/CVaR/clamp is auditable | `hypernova.twpmP`, `hypernova.clampApplied`, `hypernova.cvarPhi` |
| H04 | P0 | Retrieval order has one last authority | `retrievalOrder.lastSetBy=MoE|CFVM|PLAN_DSL|DEFAULT` |
| H05 | P0 | Prompt and dependency hard gates pass | PromptBuilder boundary, LangChain4j 1.0.1 purity |
| H06 | P1 | MLA breadcrumb is redacted | `cihRag.breadcrumb.queryRedacted=true` |
| H07 | P1 | ArtPlateEvolver registration state is visible | `moe.evolverPlateRegistered=true|false` |
| H08 | P2 | CFVM Boltzmann temperature is visible | `cfvm.boltzmannTemp`, `cfvm.tempAnnealApplied=true|false` |

Do not invent aggregate harmony scores unless a source-owned scorer computes
them. If added later, use trace keys such as `harmony.score.S01_S05`,
`harmony.score.S05_S06`, `harmony.score.S03_CFVM`, and
`harmony.score.overall`.

## Canonical Source Anchors

- Booster resolution: `main/java/com/example/lms/orchestration/StrategyConflictResolver.java`
- ExtremeZ fan-out: `main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java`
- HYPERNOVA fusion: `main/java/com/nova/protocol/fusion/NovaNextFusionService.java`
- Retrieval authority: `main/java/com/example/lms/strategy/RetrievalOrderService.java`
- MLA breadcrumb: `main/java/com/example/lms/telemetry/MlaBreadcrumb.java`
- MoE selector: `main/java/com/example/lms/moe/RgbStrategySelector.java`
- CFVM memory: `main/java/com/example/lms/cfvm/RawMatrixBuffer.java`
- Prompt boundary: `main/java/com/example/lms/prompt/PromptBuilder.java`

Treat similarly named legacy classes as adapters unless callers and Gradle
sourceSets prove they are canonical.

## TDD Patch Loop

For each source gap:

1. Add or update the smallest focused test that asserts the missing trace key.
2. Run the focused test and record RED output.
3. Patch only the live owner class.
4. Rerun the focused test and record GREEN output.
5. Broaden verification only after the seam is green.

Use `evidence_needed` instead of patching when the owner cannot be proven.

## Verification Commands

Focused harmony tests:

```powershell
.\gradlew.bat test --no-daemon --project-cache-dir $pcd `
  --tests "com.example.lms.orchestration.StrategyConflictResolverTest" `
  --tests "com.example.lms.service.rag.burst.ExtremeZTriggerTest" `
  --tests "com.nova.protocol.fusion.NovaNextFusionServiceTest" `
  --tests "com.example.lms.strategy.RetrievalOrderServiceTest" `
  --tests "com.example.lms.telemetry.MlaBreadcrumbTest" `
  --tests "com.example.lms.moe.RgbStrategySelectorTraceTest" `
  --tests "com.example.lms.cfvm.RawMatrixBufferTest"
```

Broaden after focused GREEN:

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $pcd
```

Prompt/skill verification:

```powershell
python agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_ablation_harmony_patch_directive
Select-String -Path ".agents\skills\demo1-ablation-harmony-tracker\SKILL.md","agent-prompts\agents\demo1_ablation_harmony_patch_directive\system_ko.md","agent-prompts\out\demo1_ablation_harmony_patch_directive.prompt" `
  -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" -EA SilentlyContinue
```

PatchDrop janitor regression requested by the repo instructions:

```powershell
.\__patch_drop__\janitor_tests.ps1 -Suite CoreGuards
```

## Failure Classifier

Pick one primary class:

```text
index-lock-conflict
patch-drop-pending
wrong-sourceset
langchain4j-version-purity
prompt-rule-violation
duplicate-class-fqcn
cannot-find-symbol
yaml-parse
spring-bean
spring-bind
timeout
rate-limit
provider-disabled
zero-result-after-filter
secret-leak-risk
gradle-distribution-network-cache
gradle-cache-collision
other
```

Retry once per blocker class, and only after a specific patch.

## Final Report

Return:

```md
## 요약
- 실제 수정 범위와 검증 상태만 2-5줄.

## Observation
- 실행한 명령.
- repo root / active sourceSets / Git metadata state.
- Decomposition decision.
- attachment or Mac mini evidence used.
- evidence_needed.

## Harmony Patch
| ID | file | before gap | after evidence | rollback |

## Verification
| command | expected | observed | failure classification | retry |

## Risks & Next
- up to five risks.
- counterexample or limitation.
- confidence: L/M/H.
- next single most urgent patch.
```

Never say build passed unless the build command output proves it. Never say
boot passed unless a boot command proves it.
