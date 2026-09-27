# Cross Subsystem Guard Reference

Use this reference only after `demo1-cross-subsystem-guard` triggers and the
task needs the detailed test matrix, trace-key list, broad gate command, or
report shape.

## Impact Map

| File pattern | Required focused tests |
| --- | --- |
| `StrategyConflictResolver.java` | `*StrategyConflict*`, `*ExtremeZ*`, `*Overdrive*`, `*Hypernova*` |
| `ExecutionPlan*.java` | all booster mode and strategy conflict tests |
| `ExtremeZ*` | `*ExtremeZ*`, `*CancelShield*`, `*TimeBudget*` |
| `RawSlotExtractor.java`, `RawMatrixBuffer.java`, `Cfvm*` | `*Cfvm*`, `*RawMatrix*`, `*RetrievalOrder*` |
| `NineArtPlateGate.java`, `ArtPlateEvolver.java`, `RgbStrategySelector.java` | `*ArtPlate*`, `*MoE*`, `*RgbStrategy*` |
| `NovaNextFusionService.java` | `*NovaNextFusion*`, `*TailWeighted*`, `*DppDiversity*`, `*CvarAgg*` |
| `LowRankWhiteningTransform.java`, embedding normalizers | `*Whitening*`, `*Matryoshka*`, `*EmbeddingFallback*` |
| `Nova*AutoConfiguration.java`, `AutoConfiguration.imports` | `compileJava`, `:app:classes`, context tests if present |

## Required Trace Keys

For relevant patches, preserve or add low-cardinality breadcrumbs:

- `boosterMode.active`
- `retrievalOrder.lastSetBy`
- `extremeZ.cancelShieldWrapped`
- `extremeZ.timeBudgetConsumedMs`
- `hypernova.cvarPhi`
- `hypernova.dppApplied`
- `hypernova.sourceScoreScaleMismatchCount`
- `cihRag.breadcrumb.queryRedacted`
- `moe.evolverPlateRegistered`
- `cfvm.boltzmannTemp`
- `cfvm.tempSource`
- `hypernova.whitening.provider`

## Broad Gates

Run after focused subsystem proof passes:

```powershell
$env:AWX_AGENT_HOST='desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS='1'
$env:AWX_BUILD_HOST_ID='desktop-codex'
$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle-awx-desktop"
$pcd="$env:LOCALAPPDATA\awx-gradle-project-cache\desktop-codex"
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes -x test --no-daemon --project-cache-dir $pcd
```

## Report Shape

Return:

- files changed,
- affected subsystem IDs,
- focused tests run,
- broad gates run,
- secret count,
- remaining `evidence_needed`.
