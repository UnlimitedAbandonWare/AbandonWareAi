# demo-1 Desktop Referee Ablation Postprocess Source Patch Directive

대상: Desktop Codex / Claude / Antigravity coding agent

Canonical root:

```text
C:\AbandonWare\demo-1\demo-1\src
```

목표는 Mac mini가 만든 "심판 노드 / 아블레이션 기여분해" 자료를 Desktop에서 후처리하는 것이다. 이 문서는 바로 source를 고치라는 명령이 아니라, Desktop이 충돌 없이 선별, 검증, 적용, 후속 패치를 만들도록 하는 소스수정 지시서다. Mac mini 산출물은 supporting evidence이고, 최종 적용/검증 권한은 Desktop에 있다.

## 0. 현재 입력 요약

사용자가 제공한 Mac mini 로그 기준:

- Mac mini 현재 공유 루트 `/Users/ijun-u/Desktop/WinSrc`는 Git/source root가 아니었다.
- Mac mini는 공유 원본 직접 수정을 거부했고, PatchDrop intent / report / queue matrix / Desktop 전달 프롬프트만 만들었다.
- Mac mini 로그에는 `wrong-root`, `wrong-sourceset`, `patch-drop-pending`가 반복 기록되었다.
- Mac mini가 본 PatchDrop incoming에는 top-level `*-v3.patch`가 184개였다고 보고했다.
- Mac mini가 생성한 핵심 intent packet은 `20260611-1107_macmini-referee-ablation-packet`이다.
- 후보 판단:
  - `unified-rag-orchestrator-log-redaction-v3.patch`: `HOLD_RECONCILE_FIRST`
  - `ollama-embedding-model-log-redaction-v3.patch`: `HOLD_RECONCILE_FIRST`
  - `trace-ablation-attribution-error-summary-v3.patch`: Desktop runtime verifier 완료 후에만 후보
- 아블레이션 source anchor:
  - `AblationContributionTracker`
  - `TraceAblationAttributionService`
  - `FaultMaskAblationPenaltyAspect`
  - `TraceHtmlBuilder`
  - `DynamicContextCompressor`
  - `ExtremeZBurstAspect`

Desktop 현장 재확인 규칙:

- 위 Mac mini 로그를 truth로 취급하지 않는다.
- Desktop `__patch_drop__\intent`에 해당 packet이 없으면 `evidence_needed: macmini intent packet not present on Desktop`.
- Desktop PatchDrop inventory가 Mac mini 로그와 다르면 Desktop inventory가 우선한다.
- Desktop에서 Git metadata가 없거나 unusable이면 `desktop-git-metadata-missing`으로 기록하고, Gradle/sourceSet/PatchDrop janitor evidence로 보강한다. 단, 외부 patch apply는 Git 또는 janitor apply gate가 증명될 때까지 보류한다.

## 1. Authority Order

충돌 시 우선순위:

1. Desktop canonical root의 실제 파일과 실제 명령 출력.
2. Desktop PatchDrop janitor inventory, SHA, manifest, source-edit lease 결과.
3. 사용자가 붙여준 Mac mini 로그와 intent packet 본문.
4. Datadog/Sentry/GitHub/Codex Security 같은 외부 evidence.
5. 오래된 프롬프트, 기억, 추정.

Mac mini 결과가 Desktop output과 다르면 Desktop output을 우선한다. 외부 telemetry는 source patch의 이유를 보강할 수 있지만, Desktop Gradle/source evidence를 대체하지 못한다.

## 2. Operating Mode

```md
Decomposition decision:
- mode: direct by default
- reason: this is a fixed postprocess lane: Desktop gate -> queue matrix -> candidate classify -> one safe apply or hold.
- skipped_axes: broad feature design, provider rewrite, architecture expansion
```

N-way로 확장하는 경우:

- Mac mini 후보가 서로 같은 hot file을 건드린다.
- runtime verifier 결과와 Mac mini packet이 모순된다.
- Datadog/Sentry evidence가 소스 evidence와 충돌한다.
- Codex Security가 diff-linked secret/logging issue를 발견한다.

Safe Patch 원칙:

- source 변경은 가장 작은 confirmed blocker에만 한다.
- Mac mini intent packet 자체는 `git apply`하지 않는다.
- `trace-ablation-attribution-error-summary-v3.patch`도 이름만 보고 적용하지 않는다.
- `unified-rag-orchestrator` / `ollama-embedding-model` redaction bundle은 hot-file overlap 가능성이 높으므로 먼저 HOLD한다.
- `apikey.ps1`, `.env*`, shell profiles, secret setup, `openssl`, `opnessl`는 수정하지 않는다.
- `dev.langchain4j:*`는 `1.0.1` purity를 유지한다.
- raw API key, Authorization, cookie, owner token, raw prompt, full env dump, raw sensitive query를 로그/문서/trace에 남기지 않는다.

## 3. Dynamic Desktop Agent Budget

This pass may use a maximum agent budget, but elapsed time is not the goal. Continue only while the next Desktop review, dry-run, source apply, or verification step clears the `timeAllocationValue` gate.

Do not encode a 9-hour runtime/product patch duration in Java, YAML, schedulers, dashboards, queues, or feature flags. If a Desktop-owned source feature contains fixed "patch for 9 hours" behavior, replace it with resource-aware dynamic budgeting that uses current queue state, source ownership, verification cost, and rollback safety.

Compute `timeAllocationValue` before each apply/review step:

```text
timeAllocationValue =
  0.30 * desktopOwnershipProof
+ 0.25 * patchdropCompleteness
+ 0.20 * verificationReadiness
+ 0.15 * rollbackSafety
+ 0.10 * queueImpact
- riskPenalty
```

Inputs:

| Factor | High value evidence | Low value stop signal |
|---|---|---|
| `desktopOwnershipProof` | root, Git metadata or repository-policy note, index.lock, lease, PatchDrop inventory, active sourceSets proven current | source owner ambiguous or active lease conflict |
| `patchdropCompleteness` | exactly one manifest-pinned complete v3 candidate with SHA, report, verify log, and safe source isolation proof | orphan metadata, multiple candidates, report-only intent, or timestamp-only choice |
| `verificationReadiness` | focused test, sourceSet hygiene, compile/app classes, and bootJar path are available for the touched surface | unavailable Gradle/cache/tooling evidence |
| `rollbackSafety` | narrow patch, dry-run PASS, secret scan PASS, filemode safe, one owner seam | hot-file overlap, broad source rewrite, dependency drift |
| `queueImpact` | applying or rejecting this candidate unblocks the Desktop queue | cosmetic matrix churn or low-value report rewrite |
| `riskPenalty` | add 0.10 to 0.40 for SMB contention, Mac mini/Notebook direct-source ambiguity, hot-file overlap, secret risk, or changed failure class | zero only after Desktop proof is current |

Budget decision:

| `timeAllocationValue` | Action |
|---:|---|
| `>= 0.80` | Promote or dry-run exactly one candidate, then reassess before apply |
| `0.65 - 0.79` | Run one narrow review/apply/verification step only |
| `0.45 - 0.64` | Produce a hold/reject/report matrix; do not edit runtime source |
| `< 0.45` | Stop and return `evidence_needed` or the next single Desktop-owned target |

## 4. Desktop Preflight Commands

PowerShell-first로 실행한다.

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Set-Location $Root

Get-Location
git rev-parse --show-toplevel 2>$null
git worktree list 2>$null
git branch --show-current 2>$null
git status --short 2>$null
if (Test-Path ".git\index.lock") {
  Write-Error "[AWX][desktop-referee] index-lock-conflict"
  exit 1
}

if (Test-Path "__patch_drop__\source-edit-locks") {
  Get-ChildItem "__patch_drop__\source-edit-locks" -File -ErrorAction SilentlyContinue |
    Select-Object Name,LastWriteTime
}

if (Test-Path "__patch_drop__\janitor_inventory.ps1") {
  powershell -NoProfile -ExecutionPolicy Bypass -File "__patch_drop__\janitor_inventory.ps1"
} else {
  Write-Error "[AWX][desktop-referee] evidence_needed: __patch_drop__\janitor_inventory.ps1"
  exit 1
}

if (Test-Path "__patch_drop__\intent") {
  Get-ChildItem "__patch_drop__\intent" -Recurse -File -Depth 3 |
    Select-Object FullName,Length,LastWriteTime
} else {
  Write-Warning "[AWX][desktop-referee] evidence_needed: __patch_drop__\intent missing"
}

$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
```

If `git rev-parse` fails:

- record `desktop-git-metadata-missing`;
- continue read-only source/Gradle/PatchDrop analysis;
- do not apply third-party PatchDrop `.patch` by hand;
- use janitor helpers only if they explicitly support the current canonical root and their dry-run/proof gates pass.

## 5. Runtime Verifier Gate

Run these before any external patch apply:

```powershell
.\gradlew.bat projects --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
```

When the pass touches role split, gateway, learning ops, Mac mini/Notebook topology, or runtime control-plane routing:

```powershell
if (Test-Path ".\scripts\verify_control_plane_topology.ps1") {
  powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\verify_control_plane_topology.ps1"
} else {
  Write-Warning "[AWX][desktop-referee] evidence_needed: scripts\verify_control_plane_topology.ps1"
}
```

Stop conditions:

- `index-lock-conflict`
- `worktree-overlap`
- `source-edit-lease-active`
- `patch-drop-pending` with ambiguous multiple active top-level v3 patches
- `desktop-git-metadata-missing` when external patch apply is requested
- `langchain4j-version-purity`
- `wrong-sourceset`
- `secret-leak-risk`
- `sha-mismatch`
- `filemode-blocked`
- `runtime-verifier-active`
- `port-conflict` for boot/smoke

## 6. Queue Matrix Seed

Create or update a Desktop-owned queue matrix. Use this seed from the user-provided Mac mini log, then overwrite with Desktop evidence.

| queueId | topic | artifactKind | applyability | initial state | conflictClass | nextAction |
| --- | --- | --- | --- | --- | --- | --- |
| `macmini:referee-ablation-packet:20260611-1107` | referee ablation handoff | intent-packet | NON_APPLYABLE_INTENT | MERGE_CANDIDATE | desktop-proof-missing | Read packet only if present under Desktop PatchDrop; do not apply |
| `macmini:trace-ablation-attribution-error-summary:v3` | trace ablation attribution error summary | v3-patch | PROMOTABLE_PATCH? | HOLD_UNTIL_RUNTIME_VERIFIER_COMPLETE | patch-drop-pending,desktop-proof-missing | After runtime verifier, require manifest/SHA/secret/filemode/git apply check/focused test |
| `macmini:unified-rag-orchestrator-log-redaction:v3` | unified RAG orchestrator log redaction | v3-patch | PROMOTABLE_PATCH? | HOLD_RECONCILE_FIRST | hot-file-overlap,patch-drop-pending | Hold until hot-file matrix and current Desktop diff prove no overlap |
| `macmini:ollama-embedding-model-log-redaction:v3` | Ollama embedding model log redaction | v3-patch | PROMOTABLE_PATCH? | HOLD_RECONCILE_FIRST | hot-file-overlap,patch-drop-pending | Hold until hot-file matrix and current Desktop diff prove no overlap |

State transitions:

- `NON_APPLYABLE_INTENT` cannot become `APPLY_AFTER_GATE`. It can only become Desktop-owned patch work.
- `PROMOTABLE_PATCH?` becomes `READY_FOR_DESKTOP_DRYRUN` only if Desktop sees a complete manifest-pinned v3 bundle.
- `READY_FOR_DESKTOP_DRYRUN` becomes `APPLY_AFTER_GATE` only after SHA, secret scan, filemode, sourceSet, and `git apply --check` pass.
- `APPLY_AFTER_GATE` becomes `APPLIED` only after Desktop Gradle/focused verification.

## 7. Ablation Contribution Decomposition

Use the Mac mini source map as a risk map. Verify actual paths before editing.

### Layer A - Collect

Likely source anchors:

- `AblationContributionTracker`
- trace collector / attribution DTOs

Patch intent:

- collect stable counters and reason codes only;
- no raw prompt, raw evidence, raw query, API token, or full exception body;
- prefer `queryHash`, `bodyHash`, `reason`, `stage`, `count`, `tookMs`.

### Layer B - Score / Finalize

Likely source anchors:

- `TraceAblationAttributionService`
- scoring/finalization records

Patch intent:

- classify missing/invalid contribution as explicit reason, not silent zero;
- preserve baseline behavior when contribution data is absent;
- keep failure path fail-soft and testable.

### Layer C - Analyze / Penalty

Likely source anchors:

- `FaultMaskAblationPenaltyAspect`
- dynamic context compression and special-mode aspects

Patch intent:

- separate `timeout`, `cancelled`, `rate-limit`, `provider-disabled`, `zero-result-after-filter`;
- do not merge all failures into one hard-down bucket;
- do not let one global OPEN poison fallback stages.

### Layer D - Render / Control

Likely source anchors:

- `TraceHtmlBuilder`
- `DynamicContextCompressor`
- `ExtremeZBurstAspect`

Patch intent:

- render compact redacted summaries;
- keep normal answer first, debug/trace after or in details;
- do not prepend trace so output appears blank;
- clip line count and char count.

## 8. Candidate Apply Procedure

Only for `trace-ablation-attribution-error-summary-v3.patch`, and only if visible on Desktop as a complete bundle.

```powershell
$PatchName = "trace-ablation-attribution-error-summary-v3.patch"
$PatchPath = Join-Path "__patch_drop__" $PatchName
if (-not (Test-Path $PatchPath)) {
  Write-Error "[AWX][desktop-referee] evidence_needed: $PatchPath missing"
  exit 1
}

$secretHits = Select-String -Path $PatchPath `
  -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" `
  -AllMatches -ErrorAction SilentlyContinue
Write-Host "[AWX][desktop-referee][security] patchSecretHits=$($secretHits.Count)"
if ($secretHits.Count -gt 0) { exit 1 }

if (Test-Path "__patch_drop__\janitor_apply_one.ps1") {
  powershell -NoProfile -ExecutionPolicy Bypass -File "__patch_drop__\janitor_apply_one.ps1" -PatchName $PatchName -WhatIf
} else {
  git apply --check $PatchPath
}
```

If dry-run passes, apply only through the repo-approved janitor helper or a verified `git apply` path. Do not manually copy hunk bodies from the patch unless the patch is being converted into a new Desktop-owned patch after rejection.

Focused verification:

```powershell
.\gradlew.bat test --tests "com.example.lms.trace.attribution.TraceAblationAttributionServiceErrorSummaryTest" --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava testClasses --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $pcd
```

If the focused test class does not exist, classify `missing-task` or `desktop-proof-missing` and inspect the patch report for the exact test name. Do not invent a passing test.

## 9. If No Applyable Patch Exists

If the Desktop does not see the Mac mini packet or patch:

1. Keep all source files unchanged.
2. Produce a Desktop-owned postprocess plan under `__patch_drop__\desktop-postprocess-logs\` or `agent-prompts\out\`.
3. Record:
   - Desktop actual inventory;
   - Mac mini claimed inventory;
   - conflict delta;
   - queue matrix;
   - single next source candidate;
   - exact evidence needed from Mac mini.
4. Ask Mac mini to resubmit a complete v3 bundle or intent packet through the Desktop-visible PatchDrop path.

Evidence needed message:

```text
evidence_needed: Mac mini packet not present under Desktop __patch_drop__\intent.
Ask producer to resubmit 20260611-1107_macmini-referee-ablation-packet with manifest.json, desktop_prompt_ko.md, source_map.md, priority_index.json, verify.log, sha256.txt.
```

## 10. Datadog / Sentry / GitHub / Codex Security

These plugins may assist, but none can replace Desktop source proof.

Datadog:

- read-only by default;
- use only to confirm runtime frequency or regression windows;
- record event/query IDs, counts, and timestamps, not secrets;
- if auth/context is absent, write `evidence_needed: datadog query context`.

Sentry:

- read-only by default;
- use issue/event IDs and sanitized stack class names;
- do not paste raw request bodies, headers, cookies, or user data;
- if auth/context is absent, write `evidence_needed: sentry org/project/query`.

GitHub:

- use for PR/CI context only when repo/PR is known;
- do not push, commit, or open PR unless the user explicitly asks;
- CI evidence is supporting evidence; Desktop Gradle output is final.

Codex Security:

- run a diff-scoped security scan when a candidate touches logging, redaction, HTTP providers, auth, env, secrets, or trace surfaces;
- validate any finding against active source;
- fix only validated or plausible findings with minimal diff;
- never print secret values while reporting.

Computer Use:

- do not use GUI automation for source edits;
- use only if a visible Windows process, file lock, or browser/UI state must be inspected;
- source and PatchDrop proof remain command/file based.

Superpowers:

- use systematic debugging on unexpected test failures;
- use verification-before-completion before claiming success;
- keep all Superpowers workflows subordinate to repo evidence, secret-safety, active sourceSet proof, and Desktop final verification.

## 11. Final Report Format

Return exactly:

```md
## 요약
- 2~5줄. 적용/보류/거부 범위와 검증 상태만.

## do01 / Observation
- 실행한 명령.
- 핵심 로그 최대 10줄.
- Desktop root / Git metadata 상태 / active sourceSets.
- PatchDrop inventory 상태.
- Mac mini evidence 사용 여부.
- Decomposition decision.
- evidence_needed.

## do02 / Patch Blocks
파일별:
- Observation
- Before snippet, 최대 20줄
- After snippet, 최대 20줄
- Minimal unified diff
- Why this file only
- Checkpoint log added, if any
- Secret masking method
- Rollback note

## do03 / Setup Commands
- repo root에서 실행할 정확한 PowerShell 명령.
- Desktop Gradle cache isolation.
- network/cache-dependent 명령 분리.
- external API key 필요한 명령 분리.

## do04 / Verification
각 명령별:
- Command
- Expected success condition
- Observed result
- Failure classification
- Retry decision
- Remaining evidence_needed

## do05 / Risks & Next Steps
- 최대 5개.
- SMB 충돌 위험: H/M/L.
- counterexample/limitation 1개.
- decision factors 최대 3개.
- confidence: L/M/H.
- next single most urgent patch.
```

Never say build passed unless command output proves it. Never say boot passed unless boot command output proves it. Never say provider works unless a real non-secret credential path and response prove it.

## 12. One-Shot Instruction For This User Request

For this exact Mac mini handoff:

1. Treat the pasted Mac mini logs as supporting evidence.
2. Confirm whether Desktop can see `20260611-1107_macmini-referee-ablation-packet`.
3. If not visible, do not apply any Mac mini patch; write `evidence_needed`.
4. Finish any active Desktop runtime verifier before patch apply.
5. HOLD:
   - `unified-rag-orchestrator-log-redaction-v3.patch`
   - `ollama-embedding-model-log-redaction-v3.patch`
6. Consider only:
   - `trace-ablation-attribution-error-summary-v3.patch`
7. Apply it only after:
   - single manifest-pinned v3 evidence;
   - SHA PASS;
   - secret scan count 0;
   - filemode PASS;
   - sourceSet PASS;
   - `git apply --check` or janitor dry-run PASS;
   - focused test target identified.
8. If any condition fails, stop source edits and report the exact blocker.

[DONE]
