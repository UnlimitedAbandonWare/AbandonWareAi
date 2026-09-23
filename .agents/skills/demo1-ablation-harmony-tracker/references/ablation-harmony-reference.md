# Ablation Harmony Reference

Use this reference only after `demo1-ablation-harmony-tracker` triggers and the
task needs detailed subsystem ownership, trace-key, verification, or output
contracts.

## Subsystem Map

Use this map for ownership checks before editing:

| ID | Subsystem | Canonical live anchors |
| --- | --- | --- |
| S01 | Overdrive / anchor compression | `OverdriveGuard`, `DynamicContextCompressor` |
| S02 | CFVM / failure pattern memory | `RawMatrixBuffer`, `RawSlotExtractor`, `CfvmFailureRecorder` |
| S03 | MoE strategy selector | `RgbStrategySelector`, `RetrievalOrderService`, MoE gates |
| S04 | Matryoshka slicing | embedding/vector dimension adapters and vector services |
| S05 | ExtremeZ / massive query fan-out | `ExtremeZSystemHandler`, ExtremeZ aspects |
| S06 | HYPERNOVA fusion | `NovaNextFusionService`, `TailWeightedPowerMeanFuser`, `CvarAggregator` |
| S07 | CIH-RAG / MLA breadcrumb | `MlaBreadcrumb`, breadcrumb SSE/telemetry paths |
| S08 | OpenAI adapter / version purity | `OpenAiResponsesChatModel`, model guard, Gradle purity task |

Never merge classes by simple name. Use package, caller, bean registration, and
Gradle sourceSet evidence.

## Harmony Break Board

Classify findings with these IDs. P0 means the next patch must either add
evidence or stop with `evidence_needed`.

| ID | Priority | Required trace or gate |
| --- | --- | --- |
| H01 | P0 | `boosterMode.active`, `boosterMode.excludedModes`, `boosterMode.exclusionReason` |
| H02 | P0 | `extremeZ.cancelShieldWrapped`, `extremeZ.interruptPropagated`, `extremeZ.timeBudgetConsumedMs` |
| H03 | P0 | `hypernova.twpmP`, `hypernova.clampApplied`, `hypernova.cvarPhi` |
| H04 | P0 | `retrievalOrder.lastSetBy=MoE|CFVM|PLAN_DSL|DEFAULT` |
| H05 | P0 | PromptBuilder boundary not bypassed; LangChain4j purity remains PASS |
| H06 | P1 | `cihRag.breadcrumb.queryRedacted=true` |
| H07 | P1 | `moe.evolverPlateRegistered=true|false` |
| H08 | P2 | `cfvm.boltzmannTemp`, `cfvm.tempAnnealApplied=true|false` |

## Optional Aggregate Scores

Optional aggregate score keys may be added by a later scoring patch:

```text
harmony.score.S01_S05
harmony.score.S05_S06
harmony.score.S03_CFVM
harmony.score.overall
```

Do not fabricate these aggregate scores without a source-owned scorer or
reproducible calculation.

## Required Verification

Use Windows PowerShell from Desktop root:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
```

For focused harmony patches, run only the tests that prove the touched seams
first, then broaden.

## Output Format

Return this shape:

```md
## Summary
- Source-backed harmony findings and patch status.

## Observation
- Repo root, sourceSets, branch/Git metadata status, PatchDrop status.
- Decomposition decision.
- Evidence used from attachments or Mac mini.
- `evidence_needed` items.

## Harmony Ledger
| ID | subsystem pair | severity | source evidence | required trace key | decision |

## Patch Directive
| file | minimal change | why this file only | rollback |

## External Lanes
- browser: optional | evidence_needed | verified
- computer: optional | evidence_needed | verified
- supabase: read_only_evidence_needed | verified_project_scoped_readonly

## Verification
| command | expected | observed | failure class | retry |

## Risks & Next
- Up to five risks.
- next single most urgent patch.
- confidence: L/M/H.
```
