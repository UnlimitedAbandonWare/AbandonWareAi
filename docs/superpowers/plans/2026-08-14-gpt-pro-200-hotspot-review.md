# GPT Pro 200-Hotspot Source Review Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one verified, copy-paste-ready Markdown handoff containing exactly 200 current-source design hotspots and a Korean GPT Pro adjudication request.

**Architecture:** Freeze one active-checkout evidence snapshot, have six read-only domain tasks return normalized origin-ID packets, and globally merge duplicate root causes before any final ID is assigned. Compute the replacement shortage from the globally unique count, validate exactly that many independent reserve findings, assign `HP-001..HP-200` once in deterministic origin order, and write the final table through one root writer before verifying counts, pointers, prompt coverage, redaction, and snapshot freshness.

**Tech Stack:** Markdown, PowerShell 5.1+, Git read-only inspection, Java/Spring/Gradle source evidence, SHA-256, repository-local `apply_patch`.

## Global Constraints

- Work only in `C:\AbandonWare\demo-1\demo-1\src` and read `AGENTS.md` plus `docs/superpowers/specs/2026-08-14-gpt-pro-200-hotspot-review-design.md` before each task.
- Plan-time identity is branch `codex/owned-runtime-browser-restart`, HEAD `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; refresh and report any change before using a stale line or hash.
- Active production roots are `main/java`, `main/resources`, `app/src/main/java_clean`, and `app/src/main/resources`; active tests are under `src/test/java`. Treat alternate source trees, archives, backups, and generated outputs as inactive unless current Gradle declarations prove otherwise.
- Create only this plan and `verification/gpt-pro-design-review-200.md`. The approved design remains read-only. Do not modify Java, resources, tests, Gradle, manifests, skills, prompt packs, PatchDrop, application data, or runtime memory.
- Use `apply_patch` for every Markdown write. Preserve every unrelated dirty file and hunk.
- Do not stage, commit, push, change branches, deploy, start or stop processes, or call providers, Browser, Supabase, databases, or production systems. This plan has no commit steps because operation-level commit authority was not granted.
- Treat every supplied audit item as a claim requiring current-source validation. Review count is not a vote.
- Use only `CONFIRMED`, `RISK`, or `HYPOTHESIS` for classification and only `P0`, `P1`, `P2`, or `P3` for severity. Classification, severity, and runtime occurrence remain separate.
- `CONFIRMED` requires a current active-source contract or invariant violation; `RISK` requires a concrete source-backed hazard with conditional impact; `HYPOTHESIS` requires named missing evidence.
- Every row contains exactly these semantic fields: `id`, `originId`, `domain`, `classification`, `severity`, `rootCauseKey`, `location`, `symbol`, `observation`, `impact`, and `askGPTPro`.
- Every read-only domain packet uses exactly one physical line per origin in this order: `originId | domain | classification | severity | rootCauseKey | repository-relative file:line | symbol | observation | impact | askGPTPro`.
- `location` contains one canonical repository-relative `file:line`. When a root cause crosses another owner, name the secondary `file:line` in `observation` rather than putting a delimiter-separated list in `location`.
- Final IDs are exactly `HP-001..HP-200`. Rejected rows do not receive an `HP` ID. Merged rows receive one `HP` ID and retain both origin IDs.
- Never split one root cause or restate file size, naming, formatting, or style as separate findings to satisfy the count.
- Normalize every PowerShell whole-file read with `@(Get-Content ...)` before indexing. A scalar character returned from a one-line file is never accepted as file-body evidence.
- Never expose credentials, authorization headers, cookies, private environment values, raw user prompts, provider responses, full error bodies, or share mappings. Secret-shaped checks report counts only.
- If final validation supports fewer than 200 independent rows, stop with `evidence_needed: independent hotspot shortage / verify with a fresh active-source review`; do not pad.
- Before claiming completion, use `superpowers:verification-before-completion` and report only fresh output from the named Markdown validation surfaces.

---

## File Structure

- Read: `AGENTS.md`
  - Owns current authority, active roots, protected invariants, and evidence rules.
- Read: `docs/superpowers/specs/2026-08-14-gpt-pro-200-hotspot-review-design.md`
  - Owns the approved exact-200 schema, GPT Pro verdict contract, validation corrections, and acceptance criteria.
- Create: `docs/superpowers/plans/2026-08-14-gpt-pro-200-hotspot-review.md`
  - Owns this bounded execution sequence only.
- Create: `verification/gpt-pro-design-review-200.md`
  - Owns the refreshed evidence snapshot, Korean master request, all 200 ledger rows, the GPT Pro response schema, coverage stop, Top 20 request, and phased-roadmap request.

No intermediate repository file is created. Read-only subagents may return compact evidence packets in conversation, but the root worker is the sole writer of the final artifact.

---

### Task 1: Freeze the Evidence Snapshot and Create the Final Artifact Contract

**Files:**
- Read: `AGENTS.md`
- Read: `docs/superpowers/specs/2026-08-14-gpt-pro-200-hotspot-review-design.md`
- Create: `verification/gpt-pro-design-review-200.md`

**Interfaces:**
- Consumes: current Git identity, active source roots, Java-file inventories, and approved schema.
- Produces: one non-secret snapshot, fixed headings, fixed table header, and the GPT Pro adjudication preamble used by later synthesis.

- [ ] **Step 1: Recheck repository identity without printing dirty filenames**

Run:

```powershell
$Root = 'C:\AbandonWare\demo-1\demo-1\src'
Set-Location $Root
$snapshot = [ordered]@{
  capturedAt = (Get-Date).ToUniversalTime().ToString('o')
  branch = (git branch --show-current)
  head = (git rev-parse HEAD)
  dirtyEntries = @(git status --short --untracked-files=all).Count
  indexLockPresent = [bool](Test-Path -LiteralPath '.git\index.lock')
  mainJavaFiles = @(
    Get-ChildItem -LiteralPath 'main\java' -Recurse -File -Filter '*.java'
    Get-ChildItem -LiteralPath 'app\src\main\java_clean' -Recurse -File -Filter '*.java'
  ).Count
  testJavaFiles = @(Get-ChildItem -LiteralPath 'src\test\java' -Recurse -File -Filter '*.java').Count
}
$snapshot | ConvertTo-Json -Compress
```

Expected: nonblank branch, 40-character HEAD, `indexLockPresent=false`, `mainJavaFiles=2060`, and `testJavaFiles=972` at the plan-time snapshot. A changed branch, HEAD, or count requires refreshing all dependent rows rather than copying plan-time values.

- [ ] **Step 2: Reconfirm Java and active source ownership from current declarations**

Run:

```powershell
java -version
Select-String -Path 'settings.gradle','build.gradle.kts','app\build.gradle.kts' `
  -Pattern 'include\(|srcDirs|java_clean|main/resources|src/test/java|mainClass'
```

Expected: Java major 17; root and `:app` active roots remain those in Global Constraints. This is declaration evidence only and does not prove runtime activation.

- [ ] **Step 3: Recompute reproducible Java-tree fingerprints**

Run:

```powershell
$Base = (Get-Location).Path.TrimEnd('\') + '\'
function Get-JavaTreeFingerprint([string[]]$Roots, [string]$BasePath) {
  $items = foreach ($root in $Roots) {
    Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.java' | ForEach-Object {
      $rel = $_.FullName.Substring($BasePath.Length).Replace('\','/')
      [pscustomobject]@{
        Rel = $rel
        Hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
      }
    }
  }
  $items = @($items | Sort-Object Rel)
  $manifest = (($items | ForEach-Object { "$($_.Rel)`t$($_.Hash)" }) -join "`n") + "`n"
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($manifest)
    $digest = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','').ToLowerInvariant()
  } finally {
    $sha.Dispose()
  }
  [pscustomobject]@{ Count=$items.Count; Fingerprint=$digest }
}
$main = Get-JavaTreeFingerprint @('main\java','app\src\main\java_clean') $Base
$test = Get-JavaTreeFingerprint @('src\test\java') $Base
[pscustomobject]@{main=$main;test=$test} | ConvertTo-Json -Compress
```

Expected at plan time: main fingerprint `0a20263b9579499343c83963f3059cc98401cce166740cfc8fa8f203919d0281`, test fingerprint `96e89a9238a36be5afea3c7ae6ee3644bf8b62f651dcbb8060459376a6e78357`. Record the fresh values even when they differ, then revalidate affected rows.

- [ ] **Step 4: Create the final document skeleton and fixed table contract**

Use `apply_patch`. The file must contain these headings in this order:

```markdown
# GPT Pro 요청: demo-1 설계 타점 200개 독립 검토

## 사용 방법
## GPT Pro 역할과 금지사항
## 저장소 및 증거 스냅샷
## 분류와 심각도 규칙
## 요청하는 판정
## 필수 응답 스키마
## 도메인 및 ID 범위
## 설계 타점 200개
## 전체 ID 커버리지 중단 조건
## Top 20 및 단계별 로드맵 요청
## 생성 검증 결과
```

Under `설계 타점 200개`, use this exact table header:

```markdown
| ID | Origin | Domain | Class | Severity | Root cause | Location | Symbol | Observation | Impact | Ask GPT Pro |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
```

Do not add empty data rows or provisional IDs.

- [ ] **Step 5: Write the fixed Korean GPT Pro authority preamble**

Use this exact core text under `GPT Pro 역할과 금지사항`:

```markdown
당신은 아래에 제공된 demo-1 정적 소스 감사 스냅샷을 독립적으로 심사하는 GPT Pro 설계 리뷰어다. 200개 항목은 확정 사실 목록이 아니라 검증이 필요한 주장 목록이다. 저장소에 직접 접근할 수 있다고 가정하거나, 제공되지 않은 실행·브라우저·프로바이더·데이터베이스·벤치마크 증거를 만들지 마라. 구현 코드나 패치 diff를 작성하지 말고, 어디를 어떤 계약으로 고치는 편이 좋은지 의견만 제시하라.

모든 HP-001..HP-200에 대해 먼저 ACCEPT, DOWNGRADE, REJECT, MERGE 중 하나를 판정하라. 거절·병합한 ID도 이유와 함께 보존하라. 하나라도 누락되면 context_coverage_incomplete를 출력하고 Top 20 및 로드맵 작성을 중단하라.
```

- [ ] **Step 6: Verify the skeleton without staging**

Run:

```powershell
$path = 'verification\gpt-pro-design-review-200.md'
$text = Get-Content -LiteralPath $path -Raw -Encoding UTF8
$headings = @('사용 방법','GPT Pro 역할과 금지사항','저장소 및 증거 스냅샷','분류와 심각도 규칙','요청하는 판정','필수 응답 스키마','도메인 및 ID 범위','설계 타점 200개','전체 ID 커버리지 중단 조건','Top 20 및 단계별 로드맵 요청','생성 검증 결과')
$missing = @($headings | Where-Object { -not $text.Contains("## $_") })
[pscustomobject]@{exists=(Test-Path $path);missingHeadings=$missing.Count;dataRows=([regex]::Matches($text,'(?m)^\| HP-\d{3} \|')).Count}
git status --short -- $path
```

Expected: file exists, `missingHeadings=0`, `dataRows=0`, and the file is untracked. No source path is staged or written.

---

### Task 2: Validate and Return the 32 Canonical Chat, Prompt, Session, and Response Rows

**Files:**
- Read: `verification/gpt-pro-design-review-200.md` for the fixed schema only; do not write it in this task.
- Read: `main/java/com/example/lms/prompt/PromptContext.java`
- Read: `main/java/com/example/lms/prompt/StandardPromptBuilder.java`
- Read: `main/java/com/example/lms/service/ChatWorkflow.java`
- Read: `main/java/com/example/lms/service/ChatService.java`
- Read: `main/java/com/example/lms/api/ChatApiController.java`
- Read: `main/java/com/example/lms/api/ChatSessionMetaMerger.java`
- Read: `main/java/com/example/lms/service/chat/ChatRunRegistry.java`
- Read: `main/java/com/example/lms/service/chat/ChatStreamEmitter.java`
- Read: `main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java`
- Read: `main/java/com/example/lms/ensemble/EnsembleFinalAnswerService.java`
- Read: `src/test/java/com/example/lms/prompt/PromptBuilderBoundaryTest.java`

**Interfaces:**
- Consumes: Task 1 snapshot/table contract and the validator dispositions below.
- Produces: exactly 32 canonical `DR` packet rows with current lines and proposed root-cause keys; final `HP` IDs remain unassigned.

- [ ] **Step 1: Apply the fixed origin-to-final-ID map**

Use this exact mapping:

```text
retain DR-001..DR-020
reject DR-021
retain DR-022..DR-026
merge DR-027 into DR-028 and record origin DR-027+DR-028
retain DR-029..DR-034
```

Keep `DR-009`, `DR-013`, `DR-018`, `DR-020`, `DR-023`, `DR-024`, `DR-029`, `DR-030`, and `DR-033` as `RISK`. Do not resurrect `DR-021`. Do not split `DR-027` from `DR-028`.

- [ ] **Step 2: Relocate every symbol and re-read the whole source**

Run focused searches, then inspect bounded surrounding lines with array-normalized reads:

```powershell
rg -n 'buildInstructions|buildPostOrchestrationPromptMessages|continueChat|shouldVerify|cacheKey|chatSync|chatStream|beginOrJoin|cancelRun|tryEmitNext|refinementEvidenceReady|triadStartedNanos|tryGenerate' `
  main/java/com/example/lms/prompt `
  main/java/com/example/lms/service `
  main/java/com/example/lms/api `
  main/java/com/example/lms/ensemble `
  src/test/java/com/example/lms/prompt
$lines = @(Get-Content -LiteralPath 'main\java\com\example\lms\service\ChatWorkflow.java' -Encoding UTF8)
```

For each row, verify the line lies within the current array and the observation is visible in that symbol. A missing or changed symbol is rejected or reclassified; it is never copied with a stale line.

- [ ] **Step 3: Return the 32-row normalized packet to the root writer**

Return exactly one physical line per retained origin using:

```text
originId | domain | classification | severity | rootCauseKey | repository-relative file:line | symbol | observation | impact | askGPTPro
```

Replace literal pipes in prose with commas or slashes. `observation` contains source facts only; conditional consequences belong in `impact`. Do not edit any file.

- [ ] **Step 4: Validate the chat packet contract**

The packet summary must report `retained=32`, `uniqueOriginRows=32`, `rejected=DR-021`, `merged=DR-027->DR-028`, and `fileWrites=0`. The root reviewer rejects the packet if any fixed disposition changes without fresh source evidence.

---

### Task 3: Validate and Return the 38 Retrieval, Evidence, Fusion, and Reranking Rows

**Files:**
- Read: `verification/gpt-pro-design-review-200.md` for the fixed schema only; do not write it in this task.
- Read: `main/java/com/example/lms/service/rag/UnifiedRagOrchestrator.java`
- Read: `main/java/com/example/lms/service/rag/HybridWebSearchProvider.java`
- Read: `main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java`
- Read: current active query transformation, citation, evidence-gate, whitelist, retrieval-handler, and fusion owners discovered below.

**Interfaces:**
- Consumes: Task 1 table contract and existing rows' `rootCauseKey` values.
- Produces: exactly 38 normalized `RF-001..RF-038` packet rows; cross-lane duplicates remain flagged for Task 8 and final `HP` IDs remain unassigned.

- [ ] **Step 1: Enumerate only active retrieval/fusion owners**

Run:

```powershell
rg --files main/java app/src/main/java_clean | rg 'UnifiedRagOrchestrator|HybridWebSearchProvider|QueryTransformer|AdaptiveSearchQueryVariants|Citation|Evidence|Whitelist|Fusion|Rerank|Dpp|Retriever|SearchProvider'
rg -n 'whitelistOnly|applyStrikeFilterIfNeeded|filtered|topK|threshold|fallback|return in|return out|citation|evidence|fuse|rerank' `
  main/java/com/example/lms/service/rag `
  main/java/com/nova/protocol/fusion
```

Exclude legacy aliases unless live wiring proves selection. Record one selected owner and one behavior per row.

- [ ] **Step 2: Manually re-read the two mandatory high-risk retrieval candidates**

Confirm or correct these current-source claims:

1. `UnifiedRagOrchestrator` can skip whitelist filtering when `whitelistOnly` is requested but aggressive or memory-profile-none branching applies.
2. `HybridWebSearchProvider.applyStrikeFilterIfNeeded` returns the original input when filtering removes every item.

These claims may receive `P0` only if the active chain and policy consequence remain direct. Otherwise use `P1` or `RISK`.

- [ ] **Step 3: Validate RF-001..RF-038 for root-cause independence**

For every candidate, compare `(active owner, symbol, violated behavior)` with the 32 chat rows. Merge or reject a duplicate and report a lane shortage; do not create a second row for a downstream symptom.

- [ ] **Step 4: Return the normalized RF packet**

Return RF numeric order using the Global Constraints packet schema. Do not edit any file. Explicitly flag suspected overlaps with `MV` or other lanes.

- [ ] **Step 5: Validate the retrieval packet**

The packet summary must report `rows=38`, `uniqueOriginIds=38`, first `RF-001`, last `RF-038`, the exact rejected/merged count, cross-lane duplicate candidates, and `fileWrites=0`. A shortage remains explicit; it is not padded inside this lane.

---

### Task 4: Validate and Return the 36 Orchestration, Concurrency, Resilience, and Observability Rows

**Files:**
- Read: `verification/gpt-pro-design-review-200.md` for the fixed schema only; do not write it in this task.
- Read: `main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java`
- Read: current active `CancelShieldExecutorService`, `SingleFlightManager`, timebox, breaker, trace, debug-event, SSE, executor, cancellation, and orchestration owners.

**Interfaces:**
- Consumes: Task 1 table contract and all existing root-cause keys.
- Produces: exactly 36 normalized `OR-001..OR-036` packet rows; cross-lane duplicates remain flagged and final `HP` IDs remain unassigned.

- [ ] **Step 1: Enumerate active orchestration and lifecycle seams**

Run:

```powershell
rg --files main/java | rg 'CancelShield|ExtremeZ|SingleFlight|Timebox|TimeBudget|Breaker|TraceStore|DebugEvent|Sse|Executor|Orchestr|Retry|Circuit'
rg -n 'cancel\(false\)|cancel\(true\)|future\.get\(|invokeAll|shutdown|shutdownNow|awaitTermination|InterruptedException|ThreadLocal|newFixedThreadPool|newSingleThreadExecutor|catch \(' main/java
```

Separate admission, worker completion, cancellation, wire termination, trace terminal state, and persistence; one does not prove another.

- [ ] **Step 2: Manually re-read the mandatory timed-future candidate**

Trace `CancelShieldExecutorService` from timed `invokeAll` through its timeout cancellation behavior into `ExtremeZSystemHandler` future consumption. Confirm whether `cancel(false)` can leave a non-cancelled future that later reaches an unbounded `get()`. Cite both active symbols in one row rather than splitting the chain.

- [ ] **Step 3: Validate OR-001..OR-036 and return the packet**

Use one root cause per row and return OR numeric order with the Global Constraints packet schema. Do not edit any file. Executor ownership without shutdown is a `RISK` unless active lifecycle and resource growth are direct.

- [ ] **Step 4: Validate the orchestration packet**

The packet summary must report `rows=36`, `uniqueOriginIds=36`, first `OR-001`, last `OR-036`, cross-lane duplicate candidates, and `fileWrites=0`.

---

### Task 5: Validate and Return the 37 Memory, Vector, Embedding, and Learning-Lifecycle Rows

**Files:**
- Read: `verification/gpt-pro-design-review-200.md` for the fixed schema only; do not write it in this task.
- Read: current active `FingerprintAwareEmbeddingStore`, CFVM, vector-store, embedding, memory, archive, AutoLearn, training-ingest, snapshot, and retention owners.

**Interfaces:**
- Consumes: Task 1 table contract and all existing root-cause keys.
- Produces: exactly 37 normalized `MV-001..MV-037` packet rows; cross-lane duplicates remain flagged and final `HP` IDs remain unassigned.

- [ ] **Step 1: Enumerate active memory and vector owners**

Run:

```powershell
rg --files main/java | rg 'FingerprintAwareEmbeddingStore|VectorStore|Embedding|Memory|Cfvm|RawMatrix|RawSlot|FailureRecorder|Auto[Ll]earn|TrainRag|Archive|Snapshot|Retention|Bandit'
rg -n 'fingerprint|dimension|topK|fallback|PENDING|quarantine|checkpoint|flush|snapshot|ttl|evict|delete|remove|enqueue|upsert|transaction' main/java
```

Treat absent runtime datasets, optional beans, vector rows, and database effects as `HYPOTHESIS` unless current execution evidence exists.

- [ ] **Step 2: Manually re-read the mandatory fingerprint-fallback candidate**

Confirm whether `FingerprintAwareEmbeddingStore` falls back to a dominant different fingerprint after a total mismatch and can return raw top-K results when the filtered set is empty. Keep the mismatch selection and raw-result fallback in one root-cause row unless current source proves independent owners and remedies.

- [ ] **Step 3: Validate MV-001..MV-037 and return the packet**

Return MV numeric order with the Global Constraints packet schema. Do not edit any file. Separate file-backed training truth, vector quarantine metadata, and runtime application memory.

- [ ] **Step 4: Validate the memory/vector packet**

The packet summary must report `rows=37`, `uniqueOriginIds=37`, first `MV-001`, last `MV-037`, cross-lane duplicate candidates, and `fileWrites=0`.

---

### Task 6: Validate and Return the 30 API, Security, Configuration, and Provider-Boundary Rows

**Files:**
- Read: `verification/gpt-pro-design-review-200.md` for the fixed schema only; do not write it in this task.
- Read: `main/java/com/example/lms/api/SettingsController.java`
- Read: current active `N8nWebhookController`, `OwnerKeyBootstrapFilter`, `ClientOwnerKeyResolver`, `KakaoMessageService`, `KakaoTriggerController`, security configuration, provider-key, upload, webhook, and settings owners.

**Interfaces:**
- Consumes: Task 1 table contract and all existing root-cause keys.
- Produces: exactly 30 normalized `SC-001..SC-030` packet rows; cross-lane duplicates remain flagged and final `HP` IDs remain unassigned.

- [ ] **Step 1: Enumerate the active API and security boundaries without printing secrets**

Run:

```powershell
rg --files main/java main/resources | rg 'Security|Controller|Filter|Resolver|Webhook|Settings|Provider|Kakao|N8n|OwnerKey|Upload|Cors|Csrf|Auth'
rg -n 'readAllBytes|X-Forwarded-For|setMaxAge|UUID|saveAll|settings|verify|Idempotency-Key|log\.|authorization|api.?key|permitAll|csrf|cors' main/java main/resources
```

Inspect values only in source context necessary to understand flow; the report contains property names, booleans, reason codes, and source locations, never live values.

- [ ] **Step 2: Re-read the known high-value boundary candidates**

At minimum, verify or correct:

- settings read/write endpoints and whether keys/values are allowlisted or redacted;
- webhook body buffering order relative to authentication/signature verification and idempotency;
- unsigned owner-key cookie lifetime and trust boundary;
- first-hop `X-Forwarded-For` trust without an explicit trusted-proxy contract;
- raw user/provider logging and success responses that ignore downstream outcome.

Static exposure or trust ambiguity is `RISK` unless the active route and exploit consequence are direct.

- [ ] **Step 3: Validate SC-001..SC-030 and return the packet**

Return SC numeric order with the Global Constraints packet schema. Do not edit any file. Consolidate multiple endpoints sharing one unrestricted settings owner or one logging policy into one root cause.

- [ ] **Step 4: Validate the API/security packet**

The packet summary must report `rows=30`, `uniqueOriginIds=30`, first `SC-001`, last `SC-030`, cross-lane duplicate candidates, and `fileWrites=0`.

---

### Task 7: Validate and Return the 25 Source-Ownership, Build, Architecture, and Test-Authority Rows

**Files:**
- Read: `verification/gpt-pro-design-review-200.md` for the fixed schema only; do not write it in this task.
- Read: `settings.gradle`
- Read: `settings.gradle.kts`
- Read: `build.gradle.kts`
- Read: `app/build.gradle.kts`
- Read: `scripts/source_health_scorecard.py`
- Read: `scripts/harmony_pressure_report.py`
- Read: `main/java/com/example/lms/service/rag/test_mod.java`
- Read: `main/java/service/rag/rerank/DppDiversityReranker.java`
- Read: structural owners named by the approved design's validation notes.

**Interfaces:**
- Consumes: Task 1 snapshot and all existing root-cause keys.
- Produces: exactly 25 normalized `S-001..S-025` packet rows plus a ranked replacement reserve; final `HP` IDs remain unassigned.

- [ ] **Step 1: Revalidate the fixed structural inventory**

Cover these 25 independent subjects in order:

```text
S-001 divergent settings authority
S-002 root dependency on the adapter-labelled :app module
S-003 active-root duplicate FQCN groups
S-004 non-failing default duplicate handling
S-005 package-less production-tree test residue
S-006 high-similarity ChatOrchestrator forks
S-007 ChatWorkflow cross-subsystem concentration
S-008 ChatApiController responsibility concentration
S-009 API/orchestration import cycle
S-010 component-scan namespace ambiguity
S-011 empty :app test source set
S-012 manually maintained isolated-test lists
S-013 oversized test-class concentration
S-014 source score reports large files without deducting for them
S-015 citation ownership check concatenates test source
S-016 dependency forcing can mask declared-version drift
S-017 dependency locking enabled without observed lockfiles
S-018 token-based coupling metric is not a dependency graph
S-019 equal aspect-order ambiguity is counted as ordered
S-020 narrow test-contamination metric
S-021 broad runtime proof selects the best XML without source hash/freshness
S-022 static executors without a proven shutdown owner
S-023 instance executors internally created without lifecycle ownership
S-024 ThreadLocal contents reset without remove
S-025 no architecture-rule equivalent for the measured boundaries
```

File size alone is not a row. For `S-007`, `S-008`, and `S-013`, state the concrete ownership or verification consequence rather than treating line count as a defect.

- [ ] **Step 2: Apply the two mandatory false-positive corrections**

Read both files as arrays:

```powershell
$testMod = @(Get-Content -LiteralPath 'main\java\com\example\lms\service\rag\test_mod.java' -Encoding UTF8)
$dppMarker = @(Get-Content -LiteralPath 'main\java\service\rag\rerank\DppDiversityReranker.java' -Encoding UTF8)
[pscustomobject]@{testModLines=$testMod.Count;dppMarkerLines=$dppMarker.Count}
```

`test_mod.java` is valid Java and is at most `RISK/P2` for package-less residue. The comment-only DPP deprecation marker is not a compilation defect and does not receive a separate row.

- [ ] **Step 3: Return the structural packet and initial replacement reserve**

Return S numeric order with the Global Constraints packet schema. Also flag the missing wrapper checksum and root-versus-`:app` Java compiler contract as reserve candidates, but do not count them until global dedup computes the shortage. Do not edit any file.

- [ ] **Step 4: Validate the structural packet**

The packet summary must report `rows=25`, `uniqueOriginIds=25`, first `S-001`, last `S-025`, reserve IDs, duplicate-risk pairs, and `fileWrites=0`.

---

### Task 8: Perform Global Root-Cause Deduplication and Fill the Measured Replacement Shortage

**Files:**
- Read: all six normalized evidence packets; do not write the final artifact until the 200-row canonical inventory is frozen.
- Read: `gradle/wrapper/gradle-wrapper.properties`
- Read: `build.gradle.kts`
- Read: `app/build.gradle.kts`
- Read: reserve-candidate owners discovered by the bounded searches below.

**Interfaces:**
- Consumes: 32 DR, 38 RF, 36 OR, 37 MV, 30 SC, and 25 S canonical lane rows, totaling 198 before cross-lane deduplication.
- Produces: one globally unique retained inventory, a disposition for every merged/rejected origin, a measured replacement shortage, and exactly enough validated `RPL` rows to bring the canonical total to 200.

- [ ] **Step 1: Apply confirmed cross-lane merge dispositions**

Re-read both sides, then apply these known overlaps unless current source disproves them:

```text
RF-032 + MV-009 -> one fingerprint-mismatch fail-open root
RF-034 + MV-012 -> retain RF-034 as the narrow generated-ID lineage root; MV-012 is an overlapping umbrella
RF-037 + MV-014 -> one per-store timeout multiplication root
RF-038 + MV-016 -> one cross-backend score-calibration root
```

Keep `RF-036` separate from `RF-034`: one loses caller IDs in the unsupported bulk fallback, while the other returns a newly generated ID that was never passed to fan-out. Record every absorbed origin in the retained row or disposition list.

- [ ] **Step 2: Adjudicate the remaining duplicate-risk pairs by canonical owner and behavior**

Compare every row using `(active owner, symbol, violated behavior, minimal repair seam)`. In particular, examine but do not automatically merge:

- `S-022/S-023` versus `SC-015`, `OR-031`, and other executor rows;
- `DR-012` versus `SC-004` for mutable guard state versus client-controlled policy input;
- `RF-023`, `MV-015`, `OR-015`, and `OR-028` for distinct cancellation owners and terminal effects;
- `OR-005` versus `RF-001/RF-002` for separate active fusion implementations;
- `DR-016` versus specific MV persistence failures for orchestration aggregation versus storage root causes.

Different files alone do not prove independence, and similar themes alone do not prove one root cause. Preserve separate rows only when the minimal owner, broken invariant, and RED assertion differ.

- [ ] **Step 3: Compute the replacement shortage from the adjudicated inventory**

Use these exact equations:

```text
laneCanonicalCount = 32 + 38 + 36 + 37 + 30 + 25 = 198
globalUniqueCount = laneCanonicalCount - crossLaneMergeCount - crossLaneRejectCount
replacementShortage = 200 - globalUniqueCount
```

The four known overlaps make the plan-time shortage at least six. The runtime value may be larger after full adjudication. Record the actual counts and never assume that only two replacements are needed.

- [ ] **Step 4: Validate replacement candidates in priority order**

Start with these exact candidates:

```text
RPL-001 gradle/wrapper/gradle-wrapper.properties:1 | Gradle distribution checksum is not pinned
RPL-002 build.gradle.kts:49 | root Java compiler/release contract differs from :app toolchain ownership
RPL-003 DebugEventStore.byFingerprint | bounded event ring but unbounded fingerprint aggregation ownership
RPL-004 DebugEventStore.ProbeScope | volatile check-then-set permits competing terminal outcomes
RPL-005 LlmTraceAspect | Reactor ON_ERROR is recorded as normal finish/stop
RPL-006 BrainStateChatWorkflowAspect | common-pool capture has no retained future or originating-request cancellation owner
RPL-007 SseTelemetryDiagnosticsController/LoggingSseEventPublisher | global stream admits sessionless or cross-request diagnostics
RPL-008 RagCompressionAspect | pointcut targets retrieve(..) while the active chat path calls retrieveAll
RPL-009 application.properties/application.yml | overlapping owners or conflicting defaults for one effective property contract
RPL-010 :app AutoConfiguration.imports | advertised auto-configurations are owned only by another source root
```

Locate fresh lines with:

```powershell
rg -n 'distributionUrl|distributionSha256Sum' gradle/wrapper/gradle-wrapper.properties
rg -n 'sourceCompatibility|targetCompatibility|toolchain|options\.release|JavaLanguageVersion' build.gradle.kts app/build.gradle.kts
rg -n 'byFingerprint|ProbeScope|ON_ERROR|BrainStateChatWorkflowAspect|SseTelemetryDiagnosticsController|LoggingSseEventPublisher|RagCompressionAspect|retrieveAll|AutoConfiguration.imports' main/java app/src/main/resources
rg -n '^[A-Za-z0-9_.-]+\s*[:=]' main/resources/application.properties main/resources/application.yml
```

Select exactly `replacementShortage` survivors and assign contiguous `RPL-001..RPL-nnn` IDs. If a candidate is disproved or duplicates a retained root, skip it and continue the bounded active-source search; do not relabel the rejected candidate as a lower-confidence row merely to fill the count.

- [ ] **Step 5: Freeze the canonical 200-row origin inventory**

Every retained or replacement row must have one allowed primary `file:line`, symbol, class, severity, unique root-cause key, observation, impact, and GPT Pro question. Completion of this task requires `globalUniqueCount + validatedReplacementCount = 200`, 200 unique root-cause keys, contiguous replacement IDs, and a disposition for every original row not retained independently. Do not edit the final artifact in this task.

---

### Task 9: Complete the GPT Pro Request, Response Schema, Domain Index, and Coverage Contract

**Files:**
- Modify: `verification/gpt-pro-design-review-200.md`

**Interfaces:**
- Consumes: exactly 200 globally unique canonical origin rows and the refreshed snapshot.
- Produces: one standalone GPT Pro handoff requiring complete adjudication, Top 20, and a phased roadmap.

- [ ] **Step 1: Assign final HP IDs once and write all 200 rows**

Sort canonical rows by origin-family order `DR`, `RF`, `OR`, `MV`, `SC`, `S`, `RPL`, then by the first numeric origin ID. A merged row takes the earliest retained origin position. Assign `HP-001..HP-200` sequentially only after this ordering is frozen.

Use the Task 1 table header and `apply_patch`. The root worker is the only writer. Add rows in four reviewed patches of 50 consecutive HP IDs; after each patch, count the current rows and verify the last ID before continuing. Do not derive an HP number from an old lane quota.

Each final row is exactly:

```text
| HP-nnn | origin IDs | domain | CONFIRMED/RISK/HYPOTHESIS | P0/P1/P2/P3 | unique-root-cause-key | repo/path:line | symbol | source-only observation | bounded impact | specific GPT Pro question |
```

- [ ] **Step 2: Write the exact per-row GPT Pro response schema**

Require these keys in this order:

```yaml
id: HP-nnn
verdict: ACCEPT | DOWNGRADE | REJECT | MERGE
reason: concise evidence-based rationale
mergeInto: HP-nnn | null
correctedClass: CONFIRMED | RISK | HYPOTHESIS
correctedSeverity: P0 | P1 | P2 | P3
rootCauseId: stable identifier
canonicalOwner: exact active symbol
suggestedChange: behavioral change only
minimalFiles: bounded list
redTest: focused failing assertion
regressionRisks: bounded list
evidenceNeeded: exact missing artifact and one verification action, or none
```

- [ ] **Step 3: Require all-ID coverage before ranking**

State that GPT Pro must output one accounting row for every `HP-001..HP-200`; merged and rejected IDs remain visible. If any ID is missing, it must output `context_coverage_incomplete` and stop before Top 20 or roadmap generation.

- [ ] **Step 4: Require the Top 20 and phased roadmap**

For each Top 20 item require retained IDs, root cause, canonical owner, corrected class/severity, minimal behavior change, minimal files, RED test, regression risks, dependency, verification class, stop condition, and evidence gap. Require phases in this order: terminal/security integrity, active behavior/policy, evidence and memory correctness, architecture/test authority, then low-risk cleanup. GPT Pro may reorder a row only with an explicit dependency or blast-radius reason.

- [ ] **Step 5: Compute and write domain/class/severity totals**

Run:

```powershell
$path = 'verification\gpt-pro-design-review-200.md'
$rows = @(Get-Content -LiteralPath $path -Encoding UTF8 | Where-Object { $_ -match '^\| HP-\d{3} \|' })
$parsed = foreach ($row in $rows) {
  $parts = @([regex]::Split($row.Trim('|'),'(?<!\\)\|') | ForEach-Object { $_.Trim() })
  [pscustomobject]@{Id=$parts[0];Domain=$parts[2];Class=$parts[3];Severity=$parts[4]}
}
$domainTotals = @($parsed | Group-Object Domain | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Count)" })
$classTotals = @($parsed | Group-Object Class | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Count)" })
$severityTotals = @($parsed | Group-Object Severity | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Count)" })
[pscustomobject]@{
  rows=$parsed.Count
  domains=($domainTotals -join '; ')
  classes=($classTotals -join '; ')
  severities=($severityTotals -join '; ')
}
```

Expected: `rows=200`; each group family sums to 200. Use `apply_patch` to write these actual totals under `도메인 및 ID 범위` and `생성 검증 결과`; do not copy assumed totals.

- [ ] **Step 6: Add explicit no-invention and no-patch stops**

Forbid implementation diffs, source edits, new dependencies/frameworks, duplicate active owners, protected-property renames, version changes, external calls, invented runtime activation, invented provider attempts, invented benchmark gains, and claims that a proposed test passed.

---

### Task 10: Run Exact-200, Source-Pointer, Prompt-Contract, Redaction, and Snapshot Verification

**Files:**
- Verify: `docs/superpowers/specs/2026-08-14-gpt-pro-200-hotspot-review-design.md`
- Verify: `docs/superpowers/plans/2026-08-14-gpt-pro-200-hotspot-review.md`
- Verify: `verification/gpt-pro-design-review-200.md`

**Interfaces:**
- Consumes: completed standalone handoff.
- Produces: fresh acceptance evidence, final SHA-256, scoped Git status, and a user-ready artifact link.

- [ ] **Step 1: Verify exact IDs, row width, allowed enums, and unique root causes**

Run:

```powershell
$path = 'verification\gpt-pro-design-review-200.md'
$rows = @(Get-Content -LiteralPath $path -Encoding UTF8 | Where-Object { $_ -match '^\| HP-\d{3} \|' })
$parsed = foreach ($row in $rows) {
  $parts = @([regex]::Split($row.Trim('|'),'(?<!\\)\|') | ForEach-Object { $_.Trim() })
  [pscustomobject]@{
    Width=$parts.Count; Id=$parts[0]; Origin=$parts[1]; Domain=$parts[2]
    Class=$parts[3]; Severity=$parts[4]; RootCause=$parts[5]
    Location=$parts[6]; Symbol=$parts[7]; Observation=$parts[8]
    Impact=$parts[9]; Ask=$parts[10]
  }
}
$expected = 1..200 | ForEach-Object { 'HP-{0:D3}' -f $_ }
$actual = @($parsed.Id)
$missing = @(Compare-Object $expected $actual)
$badWidth = @($parsed | Where-Object Width -ne 11)
$badClass = @($parsed | Where-Object Class -notin @('CONFIRMED','RISK','HYPOTHESIS'))
$badSeverity = @($parsed | Where-Object Severity -notin @('P0','P1','P2','P3'))
$duplicateRoots = @($parsed | Group-Object RootCause | Where-Object Count -gt 1)
[pscustomobject]@{
  rows=$parsed.Count;uniqueIds=@($actual|Sort-Object -Unique).Count
  setDifference=$missing.Count;badWidth=$badWidth.Count
  badClass=$badClass.Count;badSeverity=$badSeverity.Count
  uniqueRootCauses=@($parsed.RootCause|Sort-Object -Unique).Count
  duplicateRootGroups=$duplicateRoots.Count
}
```

Expected: `rows=200`, `uniqueIds=200`, `setDifference=0`, all bad counts zero, `uniqueRootCauses=200`, `duplicateRootGroups=0`.

- [ ] **Step 2: Verify every repository-relative path and line**

Run:

```powershell
$path = 'verification\gpt-pro-design-review-200.md'
$rows = @(Get-Content -LiteralPath $path -Encoding UTF8 | Where-Object { $_ -match '^\| HP-\d{3} \|' })
$parsed = foreach ($row in $rows) {
  $parts = @([regex]::Split($row.Trim('|'),'(?<!\\)\|') | ForEach-Object { $_.Trim() })
  [pscustomobject]@{Id=$parts[0];Location=$parts[6]}
}
$badPointers = foreach ($row in $parsed) {
  if ($row.Location -notmatch '^(?<path>.+):(?<line>\d+)$') {
    [pscustomobject]@{id=$row.Id;reason='location-format'}
    continue
  }
  $target = $Matches['path']
  $line = [int]$Matches['line']
  if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
    [pscustomobject]@{id=$row.Id;reason='path-missing'}
    continue
  }
  $content = @(Get-Content -LiteralPath $target -Encoding UTF8)
  if ($line -lt 1 -or $line -gt $content.Count) {
    [pscustomobject]@{id=$row.Id;reason='line-out-of-range'}
  }
}
$badPointers
[pscustomobject]@{badPointerCount=@($badPointers).Count}
```

Expected: no row output and `badPointerCount=0`. Manually re-read every `P0` and security-boundary row and record only count/status, not sensitive text.

- [ ] **Step 3: Verify required GPT Pro contract anchors**

Run:

```powershell
$path = 'verification\gpt-pro-design-review-200.md'
$text = Get-Content -LiteralPath $path -Raw -Encoding UTF8
$anchors = @(
  'ACCEPT | DOWNGRADE | REJECT | MERGE',
  'context_coverage_incomplete',
  'HP-001..HP-200',
  'canonicalOwner',
  'suggestedChange',
  'minimalFiles',
  'redTest',
  'regressionRisks',
  'evidenceNeeded',
  'Top 20',
  '단계별 로드맵'
)
$missingAnchors = @($anchors | Where-Object { -not $text.Contains($_) })
[pscustomobject]@{anchorCount=$anchors.Count;missingCount=$missingAnchors.Count;missing=($missingAnchors -join ',')}
```

Expected: `anchorCount=11`, `missingCount=0`.

- [ ] **Step 4: Scan for placeholders and secret-shaped values without printing matches**

Run:

```powershell
$markerWords = @(
  [string]::Concat('T','BD'),
  [string]::Concat('T','ODO'),
  [string]::Concat('F','IXME'),
  [string]::Concat('X','XX')
)
$markerPattern = '(?im)^\s*(' + (($markerWords | ForEach-Object { [regex]::Escape($_) }) -join '|') + ')\b'
$placeholderPatterns = @($markerPattern,'\$\{[^}]+\}',('(?i)<insert[^>]*>|<place' + 'holder[^>]*>'))
$path = 'verification\gpt-pro-design-review-200.md'
$text = Get-Content -LiteralPath $path -Raw -Encoding UTF8
$placeholderCount = 0
foreach ($pattern in $placeholderPatterns) {
  $placeholderCount += ([regex]::Matches($text,$pattern)).Count
}
$secretPattern = '(?i)(sk-[a-z0-9_-]{16,}|bearer\s+[a-z0-9._~+/=-]{12,}|authorization\s*:|client_secret\s*[:=]\s*[^\s]+|api[_-]?key\s*[:=]\s*[a-z0-9_-]{16,})'
$secretShapeCount = ([regex]::Matches($text,$secretPattern)).Count
[pscustomobject]@{placeholderCount=$placeholderCount;secretShapeCount=$secretShapeCount}
```

Expected: both counts zero. If nonzero, report only count and category and repair the Markdown.

- [ ] **Step 5: Recheck snapshot drift and artifact scope**

Run this self-contained refresh:

```powershell
$Root = 'C:\AbandonWare\demo-1\demo-1\src'
Set-Location $Root
$Base = (Get-Location).Path.TrimEnd('\') + '\'
function Get-FinalJavaFingerprint([string[]]$Roots, [string]$BasePath) {
  $items = foreach ($root in $Roots) {
    Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.java' | ForEach-Object {
      $rel = $_.FullName.Substring($BasePath.Length).Replace('\','/')
      [pscustomobject]@{Rel=$rel;Hash=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
  }
  $items = @($items | Sort-Object Rel)
  $manifest = (($items | ForEach-Object { "$($_.Rel)`t$($_.Hash)" }) -join "`n") + "`n"
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($manifest)
    $digest = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','').ToLowerInvariant()
  } finally {
    $sha.Dispose()
  }
  [pscustomobject]@{Count=$items.Count;Fingerprint=$digest}
}
$fresh = [ordered]@{
  capturedAt=(Get-Date).ToUniversalTime().ToString('o')
  branch=(git branch --show-current)
  head=(git rev-parse HEAD)
  indexLockPresent=[bool](Test-Path -LiteralPath '.git\index.lock')
  main=(Get-FinalJavaFingerprint @('main\java','app\src\main\java_clean') $Base)
  test=(Get-FinalJavaFingerprint @('src\test\java') $Base)
}
$fresh | ConvertTo-Json -Compress
```

If branch, HEAD, source count, or fingerprint changed, identify changed target files by hash and revalidate only affected rows before rerunning Steps 1-4.

Run scoped status and hash:

```powershell
$files = @(
  'docs/superpowers/specs/2026-08-14-gpt-pro-200-hotspot-review-design.md',
  'docs/superpowers/plans/2026-08-14-gpt-pro-200-hotspot-review.md',
  'verification/gpt-pro-design-review-200.md'
)
$files | ForEach-Object {
  [pscustomobject]@{path=$_;bytes=(Get-Item -LiteralPath $_).Length;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}
}
git status --short -- $files
```

Expected: all three Markdown files exist with nonzero hashes; the final handoff is untracked or modified only by this task. Do not stage or commit.

- [ ] **Step 6: Perform the completion audit and handoff**

Use `superpowers:verification-before-completion`. Check every item in design Section 15 against fresh Task 10 output. Completion requires exact 200 coverage, valid pointers, zero duplicate roots, all prompt anchors, zero placeholder/secret-shaped counts, and refreshed snapshot identity. Hand the user the absolute Windows path and hash of `verification/gpt-pro-design-review-200.md`, plus classification/severity totals and any accurately labelled `not_observed` evidence gaps.
