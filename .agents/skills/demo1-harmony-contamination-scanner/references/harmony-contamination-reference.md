# Harmony Contamination Reference

Use this reference after `demo1-harmony-contamination-scanner` triggers and the
task needs keyword groups, HB score deductions, output shape, or verification
commands.

## Subsystem Keywords

| ID | Keywords |
| --- | --- |
| S01 | Overdrive, Anchor, DynamicContextCompressor |
| S02 | CFVM, RawMatrixBuffer, RawTile, RetrievalOrder |
| S03 | MoE, ArtPlate, RgbStrategySelector |
| S04 | Matryoshka, ZCA, Whitening, Embedding |
| S05 | ExtremeZ, CancelShield, TimeBudget |
| S06 | HYPERNOVA, TWPM, CVaR, Risk-K, DPP |
| S07 | CIH-RAG, MLA, Breadcrumb, IQR |
| S08 | LangChain4j, OpenAI adapter, VersionPurity |

## Score Board

Start from 100 and subtract open break scores:

| Break | Score |
| --- | ---: |
| HB-01 silent catch contamination | 35.6 |
| HB-02 RetrievalOrderService stub | 32.0 |
| HB-03 booster trigger conflict | 24.0 |
| HB-04 DPP not integrated into HYPERNOVA | 22.4 |
| HB-05 inline TWPM duplicate | 21.0 |
| HB-06 mixed source score scale | 20.0 |
| HB-07 CancelShield interrupt propagation | 18.0 |
| HB-08 CFVM Boltzmann temperature split | 16.8 |
| HB-09 CfvmRawTileBuilder always disabled | 14.0 |
| HB-10 MoE evolver promptTemplate bypass risk | 12.0 |
| HB-11 TimeBudgetGuard missing trace | 11.2 |
| HB-12 ZCA whitening provider contamination | 10.5 |

## Output Shape

```json
{
  "overallScore": 0.0,
  "silentCatchRatio": 0.0,
  "duplicateFqcn": 0,
  "crossSubsystemFiles": 0,
  "secretPatternHits": 0,
  "harmonyBreaks": [
    {"id": "HB-01", "score": 35.6, "status": "OPEN", "evidence": "file:line or evidence_needed"}
  ]
}
```

Do not infer live Browser, Computer, Supabase, Mac mini, or Notebook evidence
from source scans. Keep those lanes optional, supporting, or `evidence_needed`
until current lane-specific proof exists.

## Verification

Use the narrowest useful proof first, then run:

```powershell
$env:AWX_AGENT_HOST='desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS='1'
$env:AWX_BUILD_HOST_ID='desktop-codex'
$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle-awx-desktop"
$pcd="$env:LOCALAPPDATA\awx-gradle-project-cache\desktop-codex"
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes -x test --no-daemon --project-cache-dir $pcd
```

Secret scans must print counts only.
