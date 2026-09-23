# demo1_harmony_9h_autonomous_patch

Target root:

```text
C:\AbandonWare\demo-1\demo-1\src
```

Mode: Desktop Autonomous Safe Patch. The timebox authorizes persistence, not broad rewrites. Patch only active sourceSets and prove every claim with command output.

## Required Skills

- `demo1-harmony-contamination-scanner`
- `demo1-cross-subsystem-guard`
- `demo1-subsystem-patch-directive`
- `demo1-ablation-harmony-tracker`
- `demo1-rag-platform`

## Loop

1. Confirm Desktop root, active sourceSets, PatchDrop state, source leases, and Gradle cache isolation.
2. Run or derive a `harmony-score.json` baseline from live source.
3. Patch P0 breaks first:
   - HB-01 silent catch contamination.
   - HB-02 RetrievalOrderService stub.
   - HB-03 booster trigger conflict.
4. After each patch cycle run focused tests, compile proof, and active-root secret scan.
5. Enter P1 only after P0 is closed:
   - HB-04 DPP in HYPERNOVA.
   - HB-05 TWPM canonical fuser.
   - HB-06 source score scale normalization.
   - HB-07 CancelShield interrupt hygiene.
   - HB-08 CFVM Boltzmann temperature single source.
6. Re-score. If score is below 70, revisit the highest open HB item.
7. Enter P2:
   - HB-09 CfvmRawTileBuilder compact implementation.
   - HB-10 ArtPlateEvolver promptTemplate guard.
   - HB-11 TimeBudgetGuard trace breadcrumbs.
   - HB-12 provider-aware ZCA whitening.
8. Re-score every three cycles or after each phase.
9. Stop only when the target score is reached, the failure class changes, or `evidence_needed` blocks safe edits.

## Non-Negotiables

- No secret file edits.
- No raw secret, prompt, query, cookie, Authorization, or full environment output.
- No LangChain4j version drift from `1.0.1`.
- No PromptBuilder bypass at final LLM call sites.
- No inactive mirror patching.
- No fabricated build, boot, provider, browser, or score success.

## Verification

Use Desktop-isolated Gradle:

```powershell
$env:AWX_AGENT_HOST='desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS='1'
$env:AWX_BUILD_HOST_ID='desktop-codex'
$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle-awx-desktop"
$pcd="$env:LOCALAPPDATA\awx-gradle-project-cache\desktop-codex"
.\gradlew.bat test --no-daemon --project-cache-dir $pcd --tests '*RetrievalOrder*' --tests '*StrategyConflict*' --tests '*ExtremeZ*' --tests '*CancelShield*' --tests '*Cfvm*' --tests '*RawMatrix*' --tests '*NovaNextFusion*' --tests '*TailWeighted*' --tests '*DppDiversity*' --tests '*TimeBudget*' --tests '*ArtPlate*' --tests '*Whitening*'
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes -x test --no-daemon --project-cache-dir $pcd
```

Secret scan must report counts only.
