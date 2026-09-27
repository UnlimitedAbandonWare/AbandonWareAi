# Desktop Local Model Benchmark v2 Implementation Plan

> **MODEL LOCK (2026-09-17, noted 2026-09-19):** This dated document may reference retired model tags (`qwen3:8b`, `qwen3:30b`, `qwen3-coder:*`, `gemma3:*`, `qwen2.5:7b-instruct`). Do not execute or wire those tags — live SoT: repo `AGENTS.md` model lock + `configs/api-routing.yaml` + `ollama ls`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a deterministic, answer-leakage-resistant, privacy-safe benchmark that compares the approved RTX 3060 fast and RTX 3090 main model matrices with typed envelopes, separate cold/warm measurements, and recommendation-only output.

**Architecture:** Keep the model request corpus and scorer oracle in separate JSON files. Put canonical hashing, contract validation, schema/semantic scoring, statistics, redaction, and live Ollama adapters in one focused PowerShell module; keep the CLI script as a thin serial orchestrator. The implementation calls only loopback Ollama endpoints, never changes role bindings, and emits only bounded hashes, counts, reason codes, timings, and GPU lane labels.

**Tech Stack:** Windows PowerShell 5.1, Pester 5.5.0 or newer, native Ollama `/api/chat`, `/api/tags`, `/api/ps`, `nvidia-smi`, JSON Schema-shaped response formats, SHA-256.

## Global Constraints

- The authoritative specification is `docs/superpowers/specs/2026-08-01-desktop-local-model-benchmark-v2-design.md` with SHA-256 `81E108EC4A250A131AF7C5E347FF41F6B93DA5F22F6E2888710EA631AF1C6B9B`.
- Family A assumes a truthful local runner. Its unkeyed canonical SHA-256 values bind integrity and current-run identity only; they do not authenticate a producer, prove provenance, or prove command execution.
- This plan supersedes only the schema-v1 benchmark portion of `docs/superpowers/plans/2026-07-31-desktop-ollama-model-tooling-implementation.md`; it does not authorize its download, promotion, Browser UI, or application-integration tasks.
- Create or modify only the five declared tooling files. Do not modify `main/java`, `main/resources`, `src/test/java`, `app/src/main/java_clean`, or `app/src/main/resources`.
- Current target files are absent. Recheck exact target ownership, branch, `.git/index.lock`, PatchDrop queue, and source-edit leases immediately before the first implementation edit.
- Pester must be `>= 5.5.0`. CurrentUser Pester `5.5.0` is installed on the verified host; invoke its Windows PowerShell checks with process-scoped `powershell.exe -ExecutionPolicy Bypass` and still stop with `pester-version-insufficient` if the fresh process cannot import `>= 5.5.0`. This does not read or change persistent execution-policy settings.
- Do not silently rewrite tests for Pester 3.4 compatibility and do not install a module without separate explicit approval.
- Allowed endpoints are exactly `http://127.0.0.1:11434` and `http://127.0.0.1:11435`.
- Run one model block at a time across both endpoints. Never parallelize model inference.
- Exact request options are `temperature=0`, `seed=42`, `num_ctx=8192`, `num_predict=192`, `stream=false`, `think=false`, and `keep_alive=5m`.
- Fast minimum loaded free VRAM is `1536MiB`; main minimum loaded free VRAM is `2048MiB`; GPU resident ratio must be at least `0.99`.
- Each model gets one cold request and `3 cases * 7 repetitions = 21` warm requests. Promotion latency uses only the nearest-rank p95 of the 21 warm requests.
- Model block timeout is `1800` seconds, portfolio timeout is `7200` seconds, cold request timeout is `300` seconds, and warm request timeout is `120` seconds.
- Do not persist raw prompts, raw model responses, GPU UUIDs, full error bodies, credentials, cookies, authorization headers, model-store paths, or environment dumps.
- The benchmark may emit `keep-baseline` or a recommendation. It must perform zero user/machine environment writes and zero model pull/remove operations.
- `LLM_CODER_MODEL` remains unchanged. Coder benchmarking and generated-code execution remain outside this plan.
- Do not stage, commit, or push in the current dirty worktree without separate Git authorization. Each task lists a conditional checkpoint command for use only after that authorization.

## File Structure

- Create `scripts/config/desktop-ollama-model-benchmark.json`: request-only schemaVersion 2 corpus, exact prompts, options, and complete ontologies.
- Create `scripts/config/desktop-ollama-model-benchmark-oracle.json`: scorer-only expected values keyed by the same six case IDs.
- Create `scripts/modules/DesktopOllamaModelTools.psm1`: pure validation/scoring/statistics helpers, atomic redacted report writer, Ollama/GPU adapters, and serialized portfolio function.
- Create `scripts/desktop_ollama_model_benchmark.ps1`: thin validate-only/live CLI with the exact six-model matrix.
- Create `scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1`: Pester 5.5 RED/GREEN coverage using `$TestDrive` and injected fake adapters; no real model calls.

---

### Task 0: Execution and Toolchain Gate

**Files:**
- Inspect only: `docs/superpowers/specs/2026-08-01-desktop-local-model-benchmark-v2-design.md`
- Inspect only: the five target paths in the File Structure section

**Interfaces:**
- Consumes: current Git/worktree/lock/PatchDrop/lease state and installed Pester modules.
- Produces: a bounded `ready` or `pester-version-insufficient` preflight decision. It writes no project file.

- [ ] **Step 1: Reconfirm the exact mutation boundary**

Run:

```powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
Set-Location -LiteralPath $root
$targets = @(
  'scripts\config\desktop-ollama-model-benchmark.json',
  'scripts\config\desktop-ollama-model-benchmark-oracle.json',
  'scripts\modules\DesktopOllamaModelTools.psm1',
  'scripts\desktop_ollama_model_benchmark.ps1',
  'scripts\tests\DesktopOllamaModelBenchmark.Tests.ps1'
)
[pscustomobject]@{
  branch = git branch --show-current
  worktrees = @(git worktree list --porcelain)
  indexLock = Test-Path -LiteralPath (git rev-parse --git-path index.lock)
  targetStatus = @(git status --short -- $targets)
  targetExists = @($targets | ForEach-Object { [pscustomobject]@{ path = $_; exists = Test-Path -LiteralPath $_ } })
  topLevelPatchCount = @(Get-ChildItem -LiteralPath '.\__patch_drop__' -File -Filter '*.patch' -ErrorAction SilentlyContinue).Count
  sourceLeaseCount = @(Get-ChildItem -LiteralPath '.\data\agent-handoff\source-edit-leases' -File -ErrorAction SilentlyContinue).Count
} | ConvertTo-Json -Depth 5
```

Expected before first edit: `indexLock=false`, no target status/exists entry, no active top-level patch, and no source-edit lease. Any overlap returns `HOLD`; do not select a different file path.

- [ ] **Step 2: Enforce the Pester floor before creating tests**

Run:

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "`$pester=Get-Module -ListAvailable Pester|Sort-Object Version -Descending|Select-Object -First 1;if(`$null -eq `$pester -or `$pester.Version -lt [version]'5.5.0'){throw 'pester-version-insufficient'};Import-Module Pester -MinimumVersion 5.5.0 -Force -ErrorAction Stop;(Get-Module Pester).Version.ToString()"
```

Expected on the current host: the fresh Windows PowerShell process imports CurrentUser Pester `5.5.0`. `-ExecutionPolicy Bypass` applies only to that process and is not permission to mutate persistent policy.

- [ ] **Step 3: Keep installation as a future missing-tool contingency only**

Skip this step on the verified current host. If a future fresh host fails Step 2, do not execute installation from the present spec/plan approval; after the user explicitly approves a user-scope Pester install, run:

```powershell
Install-Module -Name Pester -RequiredVersion 5.5.0 -Scope CurrentUser -Force
Import-Module Pester -RequiredVersion 5.5.0 -Force
if ((Get-Module Pester).Version -ne [version]'5.5.0') { throw 'pester-version-insufficient' }
```

Expected: imported version is exactly `5.5.0`. Never remove the built-in Pester 3.4.0 module.

- [ ] **Step 4: Re-run Steps 1 and 2**

Expected: mutation boundary remains clear and Pester gate reports `5.5.0`.

---

### Task 1: Request-Only Corpus and Scorer-Only Oracle

**Files:**
- Create: `scripts/config/desktop-ollama-model-benchmark.json`
- Create: `scripts/config/desktop-ollama-model-benchmark-oracle.json`
- Create: `scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1`

**Interfaces:**
- Consumes: exact prompts, ontologies, options, and hidden expected values from the approved v2 spec.
- Produces: request corpus object `{schemaVersion,corpusId,options,ontologies,cases}` and oracle object `{schemaVersion,corpusId,cases}` with exactly six matching case IDs.

- [ ] **Step 1: Write the failing configuration contract tests**

Create the Pester file with this first test block:

```powershell
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

BeforeAll {
    $script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $script:CorpusPath = Join-Path $script:RepoRoot 'scripts\config\desktop-ollama-model-benchmark.json'
    $script:OraclePath = Join-Path $script:RepoRoot 'scripts\config\desktop-ollama-model-benchmark-oracle.json'
}

Describe 'benchmark v2 configuration' {
    It 'keeps request corpus and scorer oracle physically separate' {
        Test-Path -LiteralPath $script:CorpusPath | Should -BeTrue
        Test-Path -LiteralPath $script:OraclePath | Should -BeTrue
        $requestText = Get-Content -LiteralPath $script:CorpusPath -Raw -Encoding UTF8
        $requestText | Should -Not -Match '(?i)"(expected|oracle|correctAnswer)"\s*:'
    }

    It 'pins the exact six request case ids and deterministic options' {
        $corpus = Get-Content -LiteralPath $script:CorpusPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $corpus.schemaVersion | Should -Be 2
        $corpus.corpusId | Should -BeExactly 'awx.desktop-ollama-benchmark.v2'
        @($corpus.cases.id) | Should -Be @(
            'fast-rewrite-ko','fast-json-extract','fast-instruction',
            'main-grounded-ko','main-uncertainty-ko','main-policy-ko'
        )
        $corpus.options.temperature | Should -Be 0
        $corpus.options.seed | Should -Be 42
        $corpus.options.num_ctx | Should -Be 8192
        $corpus.options.num_predict | Should -Be 192
        $corpus.options.stream | Should -BeFalse
        $corpus.options.think | Should -BeFalse
        $corpus.options.keep_alive | Should -BeExactly '5m'
    }

    It 'pins a matching scorer-only oracle case set' {
        $corpus = Get-Content -LiteralPath $script:CorpusPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $oracle = Get-Content -LiteralPath $script:OraclePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $oracle.schemaVersion | Should -Be 2
        $oracle.corpusId | Should -BeExactly $corpus.corpusId
        @($oracle.cases.id) | Should -Be @($corpus.cases.id)
        @($oracle.cases | Where-Object { $null -eq $_.expected }).Count | Should -Be 0
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "Import-Module Pester -MinimumVersion 5.5.0 -Force -ErrorAction Stop;Invoke-Pester -Path 'C:\AbandonWare\demo-1\demo-1\src\scripts\tests\DesktopOllamaModelBenchmark.Tests.ps1' -Output Detailed"
```

Expected: FAIL because the request and oracle JSON files do not exist. A Pester syntax/import failure is not an acceptable RED.

- [ ] **Step 3: Create the exact request-only corpus**

Create `scripts/config/desktop-ollama-model-benchmark.json` with exactly this JSON:

```json
{
  "schemaVersion": 2,
  "corpusId": "awx.desktop-ollama-benchmark.v2",
  "options": {
    "temperature": 0,
    "seed": 42,
    "num_ctx": 8192,
    "num_predict": 192,
    "stream": false,
    "think": false,
    "keep_alive": "5m"
  },
  "ontologies": {
    "gpu": ["RTX 3060", "RTX 3090"],
    "role": ["fast", "main", "high", "coder", "rewrite", "explore", "vision", "embedding"],
    "port": [11434, 11435],
    "instructionToken": ["PASS", "FAIL", "HOLD"],
    "meaningUnit": ["DOWNLOAD_MODEL", "VERIFY_BEFORE_DEFAULT_CHANGE", "CHANGE_DEFAULT_ONLY_AFTER_PASS", "CHANGE_DEFAULT_BEFORE_VERIFY", "AUTO_DELETE_BASELINE"],
    "groundedClaim": ["QWEN35_IS_3060_CANDIDATE", "GPU_LOAD_CHECK_BEFORE_PROMOTION", "QWEN35_IS_3090_BASELINE", "PROMOTION_BEFORE_GPU_CHECK"],
    "evidenceState": ["SUPPORTED", "INSUFFICIENT", "CONFLICTING"],
    "answerKind": ["NUMERIC", "NO_VALUE"],
    "unit": ["MIB", "GIB", "NONE"],
    "uncertaintyReason": ["EVIDENCE_MISSING", "EVIDENCE_CONFLICT", "EVIDENCE_PRESENT", "OUT_OF_SCOPE"],
    "promotionDecision": ["PROMOTE", "HOLD", "REJECT"],
    "promotionReason": ["CPU_OFFLOAD", "QUALITY_REGRESSION", "DIGEST_MISMATCH", "INSUFFICIENT_VRAM", "LATENCY_REGRESSION", "LINEAGE_MISSING", "ALL_GATES_PASS"]
  },
  "cases": [
    {
      "id": "fast-rewrite-ko",
      "role": "fast",
      "metrics": ["rewriteAndExtractionQuality"],
      "prompt": "다음 문장을 의미를 유지한 한 문장으로 간결하게 고쳐라: 시스템은 모델을 다운로드한 뒤 검증을 통과한 경우에만 기본값을 바꾼다. rewrite에는 고친 문장을, meaningUnits에는 문장이 보존한 의미 단위를 선택해라.",
      "responseSchema": {
        "type": "object",
        "additionalProperties": false,
        "required": ["rewrite", "meaningUnits"],
        "properties": {
          "rewrite": {"type": "string", "minLength": 1, "maxLength": 160},
          "meaningUnits": {"type": "array", "minItems": 1, "maxItems": 5, "uniqueItems": true, "items": {"type": "string", "enum": ["DOWNLOAD_MODEL", "VERIFY_BEFORE_DEFAULT_CHANGE", "CHANGE_DEFAULT_ONLY_AFTER_PASS", "CHANGE_DEFAULT_BEFORE_VERIFY", "AUTO_DELETE_BASELINE"]}}
        }
      }
    },
    {
      "id": "fast-json-extract",
      "role": "fast",
      "metrics": ["rewriteAndExtractionQuality", "toolAndStructuredOutput"],
      "prompt": "문장 'RTX 3060은 fast 역할이고 포트는 11435다'에서 gpu, role, port를 추출하라.",
      "responseSchema": {
        "type": "object",
        "additionalProperties": false,
        "required": ["gpu", "role", "port"],
        "properties": {
          "gpu": {"type": "string", "enum": ["RTX 3060", "RTX 3090"]},
          "role": {"type": "string", "enum": ["fast", "main", "high", "coder", "rewrite", "explore", "vision", "embedding"]},
          "port": {"type": "integer", "enum": [11434, 11435]}
        }
      }
    },
    {
      "id": "fast-instruction",
      "role": "fast",
      "metrics": ["instructionFollowing"],
      "prompt": "오직 PASS라는 의미를 token field로 표현하라.",
      "responseSchema": {
        "type": "object",
        "additionalProperties": false,
        "required": ["token"],
        "properties": {
          "token": {"type": "string", "enum": ["PASS", "FAIL", "HOLD"]}
        }
      }
    },
    {
      "id": "main-grounded-ko",
      "role": "main",
      "metrics": ["koreanRagQuality", "factualityAndCitationDiscipline"],
      "prompt": "근거 [A]: qwen3.5:9b는 3060 후보이다. 근거 [B]: 승격 전 GPU 적재를 확인한다. 근거만 사용해 summary를 작성하고, claims에는 각 주장과 그 근거 label을 연결하라.",
      "responseSchema": {
        "type": "object",
        "additionalProperties": false,
        "required": ["summary", "claims"],
        "properties": {
          "summary": {"type": "string", "minLength": 1, "maxLength": 400},
          "claims": {
            "type": "array",
            "minItems": 1,
            "maxItems": 4,
            "uniqueItems": true,
            "items": {
              "type": "object",
              "additionalProperties": false,
              "required": ["claimCode", "citation"],
              "properties": {
                "claimCode": {"type": "string", "enum": ["QWEN35_IS_3060_CANDIDATE", "GPU_LOAD_CHECK_BEFORE_PROMOTION", "QWEN35_IS_3090_BASELINE", "PROMOTION_BEFORE_GPU_CHECK"]},
                "citation": {"type": "string", "enum": ["A", "B", "NONE"]}
              }
            }
          }
        }
      }
    },
    {
      "id": "main-uncertainty-ko",
      "role": "main",
      "metrics": ["factualityAndCitationDiscipline"],
      "prompt": "제공된 근거에는 RTX 3060의 실제 VRAM 용량이 없다. evidenceState, answerKind, value, unit, reasonCodes로 답하라. 근거가 지원하는 숫자만 value에 넣어라.",
      "responseSchema": {
        "type": "object",
        "additionalProperties": false,
        "required": ["evidenceState", "answerKind", "value", "unit", "reasonCodes"],
        "properties": {
          "evidenceState": {"type": "string", "enum": ["SUPPORTED", "INSUFFICIENT", "CONFLICTING"]},
          "answerKind": {"type": "string", "enum": ["NUMERIC", "NO_VALUE"]},
          "value": {"anyOf": [{"type": "number"}, {"type": "null"}]},
          "unit": {"type": "string", "enum": ["MIB", "GIB", "NONE"]},
          "reasonCodes": {"type": "array", "minItems": 1, "maxItems": 4, "uniqueItems": true, "items": {"type": "string", "enum": ["EVIDENCE_MISSING", "EVIDENCE_CONFLICT", "EVIDENCE_PRESENT", "OUT_OF_SCOPE"]}}
        }
      }
    },
    {
      "id": "main-policy-ko",
      "role": "main",
      "metrics": ["instructionFollowing", "codingAndToolUse"],
      "prompt": "다운로드 성공, CPU offload 발견, 기준선보다 품질 하락이라는 조건에서 승격 결정을 decision과 reasonCodes로 답하라.",
      "responseSchema": {
        "type": "object",
        "additionalProperties": false,
        "required": ["decision", "reasonCodes"],
        "properties": {
          "decision": {"type": "string", "enum": ["PROMOTE", "HOLD", "REJECT"]},
          "reasonCodes": {"type": "array", "minItems": 1, "maxItems": 7, "uniqueItems": true, "items": {"type": "string", "enum": ["CPU_OFFLOAD", "QUALITY_REGRESSION", "DIGEST_MISMATCH", "INSUFFICIENT_VRAM", "LATENCY_REGRESSION", "LINEAGE_MISSING", "ALL_GATES_PASS"]}}
        }
      }
    }
  ]
}
```

- [ ] **Step 4: Create the exact scorer-only oracle**

Create `scripts/config/desktop-ollama-model-benchmark-oracle.json` with exactly this JSON:

```json
{
  "schemaVersion": 2,
  "corpusId": "awx.desktop-ollama-benchmark.v2",
  "cases": [
    {"id": "fast-rewrite-ko", "expected": {"meaningUnits": ["DOWNLOAD_MODEL", "VERIFY_BEFORE_DEFAULT_CHANGE", "CHANGE_DEFAULT_ONLY_AFTER_PASS"]}},
    {"id": "fast-json-extract", "expected": {"gpu": "RTX 3060", "role": "fast", "port": 11435}},
    {"id": "fast-instruction", "expected": {"token": "PASS"}},
    {"id": "main-grounded-ko", "expected": {"claims": [{"claimCode": "QWEN35_IS_3060_CANDIDATE", "citation": "A"}, {"claimCode": "GPU_LOAD_CHECK_BEFORE_PROMOTION", "citation": "B"}]}},
    {"id": "main-uncertainty-ko", "expected": {"evidenceState": "INSUFFICIENT", "answerKind": "NO_VALUE", "value": null, "unit": "NONE", "reasonCodes": ["EVIDENCE_MISSING"]}},
    {"id": "main-policy-ko", "expected": {"decision": "REJECT", "reasonCodes": ["CPU_OFFLOAD", "QUALITY_REGRESSION"]}}
  ]
}
```

- [ ] **Step 5: Run GREEN for configuration only**

Run the Step 2 command. Expected: all three configuration tests PASS.

- [ ] **Step 6: Record the conditional checkpoint**

Run `git diff --check --` for the three declared paths and inspect their exact status. If Git authorization exists, the suggested commit is:

```powershell
git add -- scripts/config/desktop-ollama-model-benchmark.json scripts/config/desktop-ollama-model-benchmark-oracle.json scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1
git commit -m "test: add benchmark v2 corpus and oracle"
```

Without Git authorization, do not stage; record `commit=not-authorized` in the task handoff.

---

### Task 2: Canonical Hashing, Contract Validation, and Pure Request Builder

**Files:**
- Create: `scripts/modules/DesktopOllamaModelTools.psm1`
- Modify: `scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1`

**Interfaces:**
- Consumes: parsed request corpus, parsed oracle, one request case, exact model tag, and corpus options.
- Produces: `ConvertTo-AwxCanonicalJson`, `Get-AwxUtf8Sha256`, `Get-AwxFileSha256`, `Assert-AwxBenchmarkContract`, `Assert-AwxLoopbackEndpoint`, and `New-AwxBenchmarkRequestBody`.

- [ ] **Step 1: Add failing leakage and request-boundary tests**

Append this block to the Pester file:

```powershell
Describe 'benchmark v2 request contract' {
    BeforeAll {
        $script:ModulePath = Join-Path $script:RepoRoot 'scripts\modules\DesktopOllamaModelTools.psm1'
        Import-Module $script:ModulePath -Force
        $script:Corpus = Get-Content -LiteralPath $script:CorpusPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $script:Oracle = Get-Content -LiteralPath $script:OraclePath -Raw -Encoding UTF8 | ConvertFrom-Json
    }

    It 'accepts the approved corpus and oracle' {
        { Assert-AwxBenchmarkContract -Corpus $script:Corpus -Oracle $script:Oracle } | Should -Not -Throw
    }

    It 'rejects const, single enums, reordered ontologies, and answer cardinality' {
        $coerciveVersion = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json;$coerciveVersion.schemaVersion='2'
        { Assert-AwxBenchmarkContract -Corpus $coerciveVersion -Oracle $script:Oracle } | Should -Throw '*benchmark-contract-invalid*'
        $constCorpus = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $constCorpus.cases[0].responseSchema | Add-Member -NotePropertyName const -NotePropertyValue 'leak'
        { Assert-AwxBenchmarkContract -Corpus $constCorpus -Oracle $script:Oracle } | Should -Throw '*const-forbidden*'

        $singleCorpus = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $singleCorpus.cases[1].responseSchema.properties.gpu.enum = @('RTX 3060')
        { Assert-AwxBenchmarkContract -Corpus $singleCorpus -Oracle $script:Oracle } | Should -Throw '*single-valued-enum*'

        $reorderedCorpus = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $reorderedCorpus.cases[1].responseSchema.properties.gpu.enum = @('RTX 3090','RTX 3060')
        { Assert-AwxBenchmarkContract -Corpus $reorderedCorpus -Oracle $script:Oracle } | Should -Throw '*ontology-narrowed*'

        $cardinalityCorpus = $script:Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $cardinalityCorpus.cases[0].responseSchema.properties.meaningUnits.minItems = 3
        $cardinalityCorpus.cases[0].responseSchema.properties.meaningUnits.maxItems = 3
        { Assert-AwxBenchmarkContract -Corpus $cardinalityCorpus -Oracle $script:Oracle } | Should -Throw '*answer-cardinality-leaked*'
    }

    It 'keeps oracle-only mutations out of the outbound request' {
        $case = $script:Corpus.cases | Where-Object id -eq 'fast-json-extract'
        $body1 = New-AwxBenchmarkRequestBody -Case $case -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options
        $mutatedOracle = $script:Oracle | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $mutatedOracle.cases[1].expected.gpu = 'ORACLE_SENTINEL_NEVER_SEND'
        $body2 = New-AwxBenchmarkRequestBody -Case $case -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options
        (Get-AwxUtf8Sha256 $body1) | Should -BeExactly (Get-AwxUtf8Sha256 $body2)
        $body2 | Should -Not -Match 'ORACLE_SENTINEL_NEVER_SEND|"expected"|"oracle"'
        { Assert-AwxOutboundBodyClean '{"model":"qwen3.5:9b","expected":"ORACLE_SENTINEL_NEVER_SEND"}' } | Should -Throw '*request-body-oracle-contamination*'
    }

    It 'places the schema in format and maps options to the native Ollama shape' {
        $case = $script:Corpus.cases | Where-Object id -eq 'fast-json-extract'
        $body = New-AwxBenchmarkRequestBody -Case $case -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options | ConvertFrom-Json
        $body.model | Should -BeExactly 'qwen3.5:9b'
        $body.messages[0].content | Should -BeExactly $case.prompt
        $body.format.properties.gpu.enum | Should -Be @('RTX 3060','RTX 3090')
        $body.stream | Should -BeFalse
        $body.think | Should -BeFalse
        $body.keep_alive | Should -BeExactly '5m'
        $body.options.seed | Should -Be 42
        $body.options.num_ctx | Should -Be 8192
        $rawBody = New-AwxBenchmarkRequestBody -Case $case -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options
        $rawBody | Should -Match '"messages":\[\{'
        $rawBody | Should -Match '"required":\["gpu","role","port"\]'
        $singleRequiredCase = $script:Corpus.cases | Where-Object id -eq 'fast-instruction'
        (New-AwxBenchmarkRequestBody -Case $singleRequiredCase -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options) | Should -Match '"required":\["token"\]'
    }

    It 'changes the request hash when the response ontology changes' {
        $case1 = $script:Corpus.cases | Where-Object id -eq 'fast-json-extract'
        $case2 = $case1 | ConvertTo-Json -Depth 40 | ConvertFrom-Json
        $case2.responseSchema.properties.gpu.enum = @('RTX 3060','RTX 3090','RTX FUTURE')
        $hash1 = Get-AwxUtf8Sha256 (New-AwxBenchmarkRequestBody -Case $case1 -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options)
        $hash2 = Get-AwxUtf8Sha256 (New-AwxBenchmarkRequestBody -Case $case2 -ModelTag 'qwen3.5:9b' -Options $script:Corpus.options)
        $hash2 | Should -Not -Be $hash1
    }
}
```

- [ ] **Step 2: Run RED**

Run the focused Pester file. Expected: FAIL because the module and exported functions do not exist.

- [ ] **Step 3: Implement canonical JSON and full SHA-256**

Start the module with these implementations. Do not reuse the 12-hex-character `Get-ShortHash` from `smoke_local_llm_generation.ps1`.

```powershell
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:Utf8NoBom = [Text.UTF8Encoding]::new($false)
$script:AllowedEndpoints = @('http://127.0.0.1:11434','http://127.0.0.1:11435')

function ConvertTo-AwxCanonicalNode {
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [string] -or $Value -is [bool] -or $Value -is [byte] -or
        $Value -is [int16] -or $Value -is [int32] -or $Value -is [int64] -or
        $Value -is [single] -or $Value -is [double] -or $Value -is [decimal]) {
        return $Value
    }
    if ($Value -is [System.Collections.IDictionary]) {
        $ordered = [ordered]@{}
        foreach ($key in @($Value.Keys | ForEach-Object { [string]$_ } | Sort-Object -CaseSensitive)) {
            $ordered[$key] = ConvertTo-AwxCanonicalNode $Value[$key]
        }
        return [pscustomobject]$ordered
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        $items = [Collections.Generic.List[object]]::new()
        foreach ($item in $Value) { $items.Add((ConvertTo-AwxCanonicalNode $item)) }
        return ,([object[]]$items.ToArray())
    }
    $properties = @($Value.PSObject.Properties | Where-Object MemberType -in @('NoteProperty','Property'))
    $object = [ordered]@{}
    foreach ($name in @($properties.Name | Sort-Object -CaseSensitive)) {
        $object[$name] = ConvertTo-AwxCanonicalNode $Value.$name
    }
    return [pscustomobject]$object
}

function ConvertTo-AwxCanonicalJson {
    param([Parameter(Mandatory)]$Value)
    $node = ConvertTo-AwxCanonicalNode $Value
    return (ConvertTo-Json -InputObject $node -Depth 40 -Compress)
}

function Get-AwxUtf8Sha256 {
    param([AllowEmptyString()][string]$Text)
    if ($null -eq $Text) { $Text = '' }
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $script:Utf8NoBom.GetBytes($Text)
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','').ToLowerInvariant()
    } finally { $sha.Dispose() }
}

function Get-AwxFileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-AwxLoopbackEndpoint {
    param([Parameter(Mandatory)][string]$Endpoint)
    if ($Endpoint -cnotin $script:AllowedEndpoints) { throw 'endpoint-not-allowlisted' }
}
```

- [ ] **Step 4: Implement the fail-closed contract validator**

Add these exact constants and helpers:

```powershell
function Get-AwxRequiredOntologies {
    return [ordered]@{
        gpu = @('RTX 3060','RTX 3090')
        role = @('fast','main','high','coder','rewrite','explore','vision','embedding')
        port = @(11434,11435)
        instructionToken = @('PASS','FAIL','HOLD')
        meaningUnit = @('DOWNLOAD_MODEL','VERIFY_BEFORE_DEFAULT_CHANGE','CHANGE_DEFAULT_ONLY_AFTER_PASS','CHANGE_DEFAULT_BEFORE_VERIFY','AUTO_DELETE_BASELINE')
        groundedClaim = @('QWEN35_IS_3060_CANDIDATE','GPU_LOAD_CHECK_BEFORE_PROMOTION','QWEN35_IS_3090_BASELINE','PROMOTION_BEFORE_GPU_CHECK')
        evidenceState = @('SUPPORTED','INSUFFICIENT','CONFLICTING')
        answerKind = @('NUMERIC','NO_VALUE')
        unit = @('MIB','GIB','NONE')
        uncertaintyReason = @('EVIDENCE_MISSING','EVIDENCE_CONFLICT','EVIDENCE_PRESENT','OUT_OF_SCOPE')
        promotionDecision = @('PROMOTE','HOLD','REJECT')
        promotionReason = @('CPU_OFFLOAD','QUALITY_REGRESSION','DIGEST_MISMATCH','INSUFFICIENT_VRAM','LATENCY_REGRESSION','LINEAGE_MISSING','ALL_GATES_PASS')
        citation = @('A','B','NONE')
    }
}

function Test-AwxExactSequence {
    param($Actual,$Expected)
    $a = @($Actual); $e = @($Expected)
    if ($a.Count -ne $e.Count) { return $false }
    for ($i = 0; $i -lt $a.Count; $i++) {
        if ((ConvertTo-AwxCanonicalJson $a[$i]) -cne (ConvertTo-AwxCanonicalJson $e[$i])) { return $false }
    }
    return $true
}

function Test-AwxObjectNode {
    param($Value)
    if ($null -eq $Value -or $Value -is [string] -or $Value -is [ValueType]) { return $false }
    return ($Value -is [Collections.IDictionary] -or @($Value.PSObject.Properties | Where-Object MemberType -in @('NoteProperty','Property')).Count -gt 0)
}

function Get-AwxNodeProperties {
    param($Node)
    if (-not (Test-AwxObjectNode $Node)) { return @() }
    return @($Node.PSObject.Properties | Where-Object MemberType -in @('NoteProperty','Property'))
}

function Assert-AwxSchemaNode {
    param([Parameter(Mandatory)]$Node,[Parameter(Mandatory)]$KnownOntologies)
    if (-not (Test-AwxObjectNode $Node)) { throw 'benchmark-contract-invalid' }
    foreach ($property in @(Get-AwxNodeProperties $Node)) {
        if ($property.Name -cin @('const','default','examples')) { throw ($property.Name + '-forbidden') }
        if ($property.Name -cin @('pattern','minimum','maximum','description')) { throw 'benchmark-contract-invalid' }
        if ($property.Name -ceq 'enum') {
            $values = @($property.Value)
            if ($values.Count -lt 2) { throw 'single-valued-enum' }
            $matches = @($KnownOntologies.GetEnumerator() | Where-Object { Test-AwxExactSequence $values $_.Value })
            if ($matches.Count -ne 1) { throw 'ontology-narrowed' }
        }
        if ($property.Name -ceq 'minItems' -and $Node.PSObject.Properties['maxItems'] -and
            [int]$property.Value -eq [int]$Node.maxItems) { throw 'answer-cardinality-leaked' }
        $value = $property.Value
        if ($value -is [Collections.IEnumerable] -and $value -isnot [string] -and $value -isnot [Collections.IDictionary]) {
            foreach ($child in @($value)) { if (Test-AwxObjectNode $child) { Assert-AwxSchemaNode -Node $child -KnownOntologies $KnownOntologies } }
        } elseif (Test-AwxObjectNode $value) {
            Assert-AwxSchemaNode -Node $value -KnownOntologies $KnownOntologies
        }
    }
}

function Assert-AwxNoForbiddenRequestKey {
    param($Node)
    if (-not (Test-AwxObjectNode $Node)) { return }
    foreach ($property in @(Get-AwxNodeProperties $Node)) {
        if ($property.Name -cin @('expected','oracle','correctAnswer')) { throw 'request-body-oracle-contamination' }
        $value = $property.Value
        if ($value -is [Collections.IEnumerable] -and $value -isnot [string] -and $value -isnot [Collections.IDictionary]) {
            foreach ($child in @($value)) { if (Test-AwxObjectNode $child) { Assert-AwxNoForbiddenRequestKey $child } }
        } elseif (Test-AwxObjectNode $value) { Assert-AwxNoForbiddenRequestKey $value }
    }
}

function Assert-AwxBenchmarkContract {
    param([Parameter(Mandatory)]$Corpus,[Parameter(Mandatory)]$Oracle)
    if (-not (Test-AwxStrictIntegerRange $Corpus.schemaVersion 2 2) -or -not (Test-AwxStrictIntegerRange $Oracle.schemaVersion 2 2) -or
        [string]$Corpus.corpusId -cne 'awx.desktop-ollama-benchmark.v2' -or
        [string]$Oracle.corpusId -cne [string]$Corpus.corpusId -or $Corpus.cases -isnot [System.Array] -or $Oracle.cases -isnot [System.Array]) { throw 'benchmark-contract-invalid' }
    $caseIds = @('fast-rewrite-ko','fast-json-extract','fast-instruction','main-grounded-ko','main-uncertainty-ko','main-policy-ko')
    if (-not (Test-AwxExactSequence @($Corpus.cases.id) $caseIds) -or
        -not (Test-AwxExactSequence @($Oracle.cases.id) $caseIds)) { throw 'benchmark-contract-invalid' }
    Assert-AwxNoForbiddenRequestKey $Corpus
    $known = Get-AwxRequiredOntologies
    foreach ($name in @($known.Keys | Where-Object { $_ -cne 'citation' })) {
        if (-not (Test-AwxExactSequence @($Corpus.ontologies.$name) @($known[$name]))) { throw 'ontology-narrowed' }
    }
    foreach ($case in @($Corpus.cases)) { Assert-AwxSchemaNode -Node $case.responseSchema -KnownOntologies $known }
    if ((Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.options)) -cne 'e33d12218521f60ee293d79c26438a37d91a1f79a882f096a99d1703b098ee9f' -or
        (Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.ontologies)) -cne '6e3521a689e2d7921b62b7068ca5eb56f94a8ea48cef2ed9dbb86c0f281bd35b') {
        throw 'benchmark-contract-invalid'
    }
    $caseContractHashes = [ordered]@{
        'fast-rewrite-ko'='c7e34b7d8f339c1ad19048145db7d3a3b97a7e6197985c644dd5882c45614862'
        'fast-json-extract'='b04f428ed77d45ab55e684e9b9cb10563dd8c39dbb85f879bc46bf6c25bf06c8'
        'fast-instruction'='4d9c7a00dcdd2396077ef577cf8ba26cb42166ea961acf49542004fbe68440db'
        'main-grounded-ko'='ded91b7d5126bb2da7ffeeea179931369a6040ed25750e90527c30ebba473579'
        'main-uncertainty-ko'='33f23c9ef4359e3f3ef00f351f63aa7bdfcc946da49925c1215262b83c8af26c'
        'main-policy-ko'='abf1e3a6d6fb303226d1d1ad2c54c24c1862d2feb740c58bd8cafc5aae4b547b'
    }
    foreach ($case in @($Corpus.cases)) {
        $contract = [ordered]@{id=$case.id;role=$case.role;metrics=@($case.metrics);prompt=$case.prompt;responseSchema=$case.responseSchema}
        if ((Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $contract)) -cne $caseContractHashes[[string]$case.id]) { throw 'benchmark-contract-invalid' }
    }
    if ((Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Oracle)) -cne 'bb2a2df286b2a023735f1d30b5da8b1db0739f9385a95194f79285936dc23c0b') {
        throw 'benchmark-contract-invalid'
    }
}
```

The recursive functions must receive only parsed JSON objects. The hard-coded canonical hashes pin exact options, prompt text, role, metric list, schema shape, field-to-ontology mapping, and oracle values without placing expected values in any request builder. They must not log the oracle.

- [ ] **Step 5: Implement the pure native request builder**

```powershell
function Assert-AwxOutboundBodyClean {
    param([Parameter(Mandatory)][string]$Body)
    if ($Body -match '(?i)ORACLE_SENTINEL_NEVER_SEND|"(expected|oracle|correctAnswer)"\s*:') { throw 'request-body-oracle-contamination' }
}

function New-AwxBenchmarkRequestBody {
    param(
        [Parameter(Mandatory)]$Case,
        [Parameter(Mandatory)][string]$ModelTag,
        [Parameter(Mandatory)]$Options
    )
    if ([string]::IsNullOrWhiteSpace($ModelTag) -or $ModelTag -match '(?i)(^|:)latest$') {
        throw 'candidate-not-allowlisted'
    }
    $body = [ordered]@{
        model = $ModelTag
        messages = @([ordered]@{ role = 'user'; content = [string]$Case.prompt })
        stream = [bool]$Options.stream
        think = [bool]$Options.think
        format = $Case.responseSchema
        keep_alive = [string]$Options.keep_alive
        options = [ordered]@{
            temperature = [double]$Options.temperature
            seed = [int]$Options.seed
            num_ctx = [int]$Options.num_ctx
            num_predict = [int]$Options.num_predict
        }
    }
    $json = ConvertTo-AwxCanonicalJson $body
    Assert-AwxOutboundBodyClean $json
    return $json
}

Export-ModuleMember -Function ConvertTo-AwxCanonicalJson,Get-AwxUtf8Sha256,Get-AwxFileSha256,Assert-AwxLoopbackEndpoint,Assert-AwxBenchmarkContract,Assert-AwxOutboundBodyClean,New-AwxBenchmarkRequestBody
```

- [ ] **Step 6: Run GREEN**

Run the focused Pester file. Expected: configuration and request-contract tests PASS with zero failures.

- [ ] **Step 7: Record the conditional checkpoint**

Inspect only the module and test diff. Suggested commit, only with Git authorization:

```powershell
git add -- scripts/modules/DesktopOllamaModelTools.psm1 scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1
git commit -m "feat: validate benchmark v2 request boundary"
```

---

### Task 3: Typed Envelope Validation and Hidden-Oracle Semantic Scoring

**Files:**
- Modify: `scripts/modules/DesktopOllamaModelTools.psm1`
- Modify: `scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1`

**Interfaces:**
- Consumes: one corpus case, its oracle row, exact response model, and one native Ollama response object.
- Produces: `Test-AwxJsonSchemaValue`, `Test-AwxSemanticEnvelope`, and `Test-AwxBenchmarkResponse`. The result contains booleans, hashes, lengths, and one reason code; it never contains response content.

- [ ] **Step 1: Add failing response-contract tests**

Append:

```powershell
Describe 'typed envelope and semantic scoring' {
    BeforeAll {
        $fastCase = $script:Corpus.cases | Where-Object id -eq 'fast-json-extract'
        $fastExpected = ($script:Oracle.cases | Where-Object id -eq 'fast-json-extract').expected
        $policyCase = $script:Corpus.cases | Where-Object id -eq 'main-policy-ko'
        $policyExpected = ($script:Oracle.cases | Where-Object id -eq 'main-policy-ko').expected
    }

    It 'passes a bounded-ontology response with correct hidden semantics' {
        $native = [pscustomobject]@{
            model = 'qwen3.5:9b'; done_reason = 'stop'
            message = [pscustomobject]@{ thinking = ''; content = '{"gpu":"RTX 3060","role":"fast","port":11435}' }
        }
        $result = Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $native
        $result.jsonParsed | Should -BeTrue
        $result.schemaPassed | Should -BeTrue
        $result.semanticPassed | Should -BeTrue
        $result.PSObject.Properties.Name | Should -Not -Contain 'content'
    }

    It 'keeps shape-only JSON as a semantic mismatch' {
        $native = [pscustomobject]@{
            model = 'gemma4:12b'; done_reason = 'stop'
            message = [pscustomobject]@{ thinking = ''; content = '{"gpu":"RTX 3090","role":"main","port":11435}' }
        }
        $result = Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'gemma4:12b' -OllamaResponse $native
        $result.jsonParsed | Should -BeTrue
        $result.schemaPassed | Should -BeTrue
        $result.semanticPassed | Should -BeFalse
        $result.reasonCode | Should -BeExactly 'response-semantic-mismatch'
    }

    It 'rejects invalid JSON, extra properties, thinking, and length termination separately' {
        $badJson = [pscustomobject]@{model='qwen3.5:9b';done_reason='stop';message=[pscustomobject]@{thinking='';content='not-json'}}
        (Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $badJson).reasonCode | Should -BeExactly 'response-json-invalid'
        $extra = [pscustomobject]@{model='qwen3.5:9b';done_reason='stop';message=[pscustomobject]@{thinking='';content='{"gpu":"RTX 3060","role":"fast","port":11435,"extra":true}'}}
        (Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $extra).reasonCode | Should -BeExactly 'response-schema-invalid'
        $thinking = [pscustomobject]@{model='qwen3.5:9b';done_reason='stop';message=[pscustomobject]@{thinking='hidden';content='{"gpu":"RTX 3060","role":"fast","port":11435}'}}
        (Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $thinking).reasonCode | Should -BeExactly 'lineage-missing'
        $truncated = [pscustomobject]@{model='qwen3.5:9b';done_reason='length';message=[pscustomobject]@{thinking='';content='{}'}}
        (Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $truncated).reasonCode | Should -BeExactly 'response-truncated'
        $missingDone = [pscustomobject]@{model='qwen3.5:9b';message=[pscustomobject]@{thinking='';content='{"gpu":"RTX 3060","role":"fast","port":11435}'}}
        (Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $missingDone).reasonCode | Should -BeExactly 'lineage-missing'
        $booleanPort = [pscustomobject]@{model='qwen3.5:9b';done_reason='stop';message=[pscustomobject]@{thinking='';content='{"gpu":"RTX 3060","role":"fast","port":true}'}}
        (Test-AwxBenchmarkResponse -Case $fastCase -Expected $fastExpected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $booleanPort).reasonCode | Should -BeExactly 'response-schema-invalid'
    }

    It 'compares semantic arrays as exact order-independent sets and rejects extras' {
        $pass = [pscustomobject]@{
            model='qwen3.6:27b';done_reason='stop'
            message=[pscustomobject]@{thinking='';content='{"decision":"REJECT","reasonCodes":["QUALITY_REGRESSION","CPU_OFFLOAD"]}'}
        }
        (Test-AwxBenchmarkResponse -Case $policyCase -Expected $policyExpected -ExpectedModel 'qwen3.6:27b' -OllamaResponse $pass).semanticPassed | Should -BeTrue
        $extra = [pscustomobject]@{
            model='qwen3.6:27b';done_reason='stop'
            message=[pscustomobject]@{thinking='';content='{"decision":"REJECT","reasonCodes":["QUALITY_REGRESSION","CPU_OFFLOAD","LINEAGE_MISSING"]}'}
        }
        (Test-AwxBenchmarkResponse -Case $policyCase -Expected $policyExpected -ExpectedModel 'qwen3.6:27b' -OllamaResponse $extra).semanticPassed | Should -BeFalse
    }
}
```

- [ ] **Step 2: Run RED**

Expected: FAIL because response schema and semantic functions are not exported.

- [ ] **Step 3: Implement the controlled JSON Schema evaluator**

Add a recursive evaluator for only the schema keywords present in the approved corpus:

```powershell
function New-AwxCheckResult([bool]$Passed,[string]$ReasonCode) {
    return [pscustomobject]@{ passed = $Passed; reasonCode = $ReasonCode }
}

function Test-AwxNumber {
    param($Value)
    return ($Value -is [byte] -or $Value -is [sbyte] -or $Value -is [int16] -or $Value -is [uint16] -or
        $Value -is [int32] -or $Value -is [uint32] -or $Value -is [int64] -or $Value -is [uint64] -or
        $Value -is [single] -or $Value -is [double] -or $Value -is [decimal])
}

function Test-AwxJsonSchemaValue {
    param($Value,[Parameter(Mandatory)]$Schema)
    if ($Schema.PSObject.Properties['anyOf']) {
        foreach ($candidate in @($Schema.anyOf)) {
            if ((Test-AwxJsonSchemaValue -Value $Value -Schema $candidate).passed) { return New-AwxCheckResult $true 'pass' }
        }
        return New-AwxCheckResult $false 'response-schema-invalid'
    }
    if ($Schema.PSObject.Properties['enum']) {
        $actualJson = ConvertTo-AwxCanonicalJson $Value
        if (@($Schema.enum | Where-Object { (ConvertTo-AwxCanonicalJson $_) -ceq $actualJson }).Count -ne 1) {
            return New-AwxCheckResult $false 'response-schema-invalid'
        }
    }
    $type = [string]$Schema.type
    switch ($type) {
        'null' { if ($null -ne $Value) { return New-AwxCheckResult $false 'response-schema-invalid' } }
        'string' {
            if ($Value -isnot [string]) { return New-AwxCheckResult $false 'response-schema-invalid' }
            if ($Schema.PSObject.Properties['minLength'] -and $Value.Length -lt [int]$Schema.minLength) { return New-AwxCheckResult $false 'response-schema-invalid' }
            if ($Schema.PSObject.Properties['maxLength'] -and $Value.Length -gt [int]$Schema.maxLength) { return New-AwxCheckResult $false 'response-schema-invalid' }
        }
        'integer' {
            if (-not (Test-AwxNumber $Value) -or [double]$Value -ne [Math]::Truncate([double]$Value)) { return New-AwxCheckResult $false 'response-schema-invalid' }
        }
        'number' { if (-not (Test-AwxNumber $Value)) { return New-AwxCheckResult $false 'response-schema-invalid' } }
        'array' {
            if ($Value -isnot [System.Array]) { return New-AwxCheckResult $false 'response-schema-invalid' }
            $items = @($Value)
            if ($Schema.PSObject.Properties['minItems'] -and $items.Count -lt [int]$Schema.minItems) { return New-AwxCheckResult $false 'response-schema-invalid' }
            if ($Schema.PSObject.Properties['maxItems'] -and $items.Count -gt [int]$Schema.maxItems) { return New-AwxCheckResult $false 'response-schema-invalid' }
            if ($Schema.PSObject.Properties['uniqueItems'] -and [bool]$Schema.uniqueItems) {
                $keys = @($items | ForEach-Object { ConvertTo-AwxCanonicalJson $_ })
                if (@($keys | Select-Object -Unique).Count -ne $keys.Count) { return New-AwxCheckResult $false 'response-schema-invalid' }
            }
            foreach ($item in $items) {
                $child = Test-AwxJsonSchemaValue -Value $item -Schema $Schema.items
                if (-not $child.passed) { return $child }
            }
        }
        'object' {
            if ($null -eq $Value -or $Value -is [string] -or @($Value.PSObject.Properties).Count -eq 0) { return New-AwxCheckResult $false 'response-schema-invalid' }
            $names = @($Value.PSObject.Properties.Name)
            foreach ($required in @($Schema.required)) {
                if ($names -cnotcontains [string]$required) { return New-AwxCheckResult $false 'response-schema-invalid' }
            }
            $allowed = @($Schema.properties.PSObject.Properties.Name)
            if (-not [bool]$Schema.additionalProperties -and @($names | Where-Object { $_ -cnotin $allowed }).Count -gt 0) {
                return New-AwxCheckResult $false 'response-schema-invalid'
            }
            foreach ($property in @($Schema.properties.PSObject.Properties)) {
                if ($names -ccontains $property.Name) {
                    $child = Test-AwxJsonSchemaValue -Value $Value.($property.Name) -Schema $property.Value
                    if (-not $child.passed) { return $child }
                }
            }
        }
        default { return New-AwxCheckResult $false 'response-schema-invalid' }
    }
    return New-AwxCheckResult $true 'pass'
}
```

- [ ] **Step 4: Implement exact hidden-oracle scoring**

```powershell
function Test-AwxExactSet {
    param($Actual,$Expected)
    $a = @($Actual | ForEach-Object { ConvertTo-AwxCanonicalJson $_ } | Sort-Object -CaseSensitive)
    $e = @($Expected | ForEach-Object { ConvertTo-AwxCanonicalJson $_ } | Sort-Object -CaseSensitive)
    return Test-AwxExactSequence $a $e
}

function Test-AwxSemanticEnvelope {
    param([Parameter(Mandatory)][string]$CaseId,[Parameter(Mandatory)]$Envelope,[Parameter(Mandatory)]$Expected)
    $pass = switch ($CaseId) {
        'fast-rewrite-ko' {
            $rewrite = ([string]$Envelope.rewrite).Trim()
            $rewrite.Length -ge 1 -and $rewrite.Length -le 160 -and $rewrite -notmatch '[\r\n]' -and
                (Test-AwxExactSet @($Envelope.meaningUnits) @($Expected.meaningUnits))
        }
        'fast-json-extract' {
            [string]$Envelope.gpu -ceq [string]$Expected.gpu -and
            [string]$Envelope.role -ceq [string]$Expected.role -and
            [int]$Envelope.port -eq [int]$Expected.port
        }
        'fast-instruction' { [string]$Envelope.token -ceq [string]$Expected.token }
        'main-grounded-ko' {
            $summary = ([string]$Envelope.summary).Trim()
            $actualPairs = @($Envelope.claims | ForEach-Object { '{0}|{1}' -f $_.claimCode,$_.citation })
            $expectedPairs = @($Expected.claims | ForEach-Object { '{0}|{1}' -f $_.claimCode,$_.citation })
            $summary.Length -ge 1 -and $summary.Length -le 400 -and $summary -match '[가-힣]' -and
                (Test-AwxExactSet $actualPairs $expectedPairs)
        }
        'main-uncertainty-ko' {
            [string]$Envelope.evidenceState -ceq [string]$Expected.evidenceState -and
            [string]$Envelope.answerKind -ceq [string]$Expected.answerKind -and
            $null -eq $Envelope.value -and $null -eq $Expected.value -and
            [string]$Envelope.unit -ceq [string]$Expected.unit -and
            (Test-AwxExactSet @($Envelope.reasonCodes) @($Expected.reasonCodes))
        }
        'main-policy-ko' {
            [string]$Envelope.decision -ceq [string]$Expected.decision -and
            (Test-AwxExactSet @($Envelope.reasonCodes) @($Expected.reasonCodes))
        }
        default { $false }
    }
    return New-AwxCheckResult ([bool]$pass) $(if ($pass) { 'pass' } else { 'response-semantic-mismatch' })
}
```

- [ ] **Step 5: Implement the privacy-safe response pipeline**

```powershell
function Test-AwxBenchmarkResponse {
    param(
        [Parameter(Mandatory)]$Case,
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)][string]$ExpectedModel,
        [Parameter(Mandatory)]$OllamaResponse
    )
    $hasModel = $null -ne $OllamaResponse.PSObject.Properties['model']
    $hasDoneReason = $null -ne $OllamaResponse.PSObject.Properties['done_reason']
    $hasMessage = $null -ne $OllamaResponse.PSObject.Properties['message'] -and $null -ne $OllamaResponse.message
    $hasContent = $hasMessage -and $null -ne $OllamaResponse.message.PSObject.Properties['content'] -and $OllamaResponse.message.content -is [string]
    $content = $(if ($hasContent) {[string]$OllamaResponse.message.content} else {''})
    $thinking = $(if ($hasMessage -and $null -ne $OllamaResponse.message.PSObject.Properties['thinking']) {[string]$OllamaResponse.message.thinking} else {''})
    $safe = [ordered]@{
        transportPassed = $true; lineagePassed = $false; jsonParsed = $false
        schemaPassed = $false; semanticPassed = $false; reasonCode = 'lineage-missing'
        responseSha256 = Get-AwxUtf8Sha256 $content
        responseLength = $content.Length
        tokenCount = $(if ($OllamaResponse.PSObject.Properties['eval_count'] -and (Test-AwxNumber $OllamaResponse.eval_count) -and [int64]$OllamaResponse.eval_count -ge 0) {[int64]$OllamaResponse.eval_count} else {$null})
        thinkingLength = $thinking.Length
        doneReason = $(if ($hasDoneReason) {[string]$OllamaResponse.done_reason} else {''})
    }
    if (-not $hasModel -or -not $hasDoneReason -or -not $hasMessage -or -not $hasContent -or [string]$OllamaResponse.model -cne $ExpectedModel -or $safe.thinkingLength -ne 0) { return [pscustomobject]$safe }
    $safe.lineagePassed = $true
    if ($safe.doneReason -ceq 'length') { $safe.reasonCode = 'response-truncated'; return [pscustomobject]$safe }
    if ($safe.doneReason -cne 'stop') { $safe.reasonCode = 'lineage-missing'; return [pscustomobject]$safe }
    try { $envelope = $content | ConvertFrom-Json }
    catch { $safe.reasonCode = 'response-json-invalid'; return [pscustomobject]$safe }
    $safe.jsonParsed = $true
    $schema = Test-AwxJsonSchemaValue -Value $envelope -Schema $Case.responseSchema
    if (-not $schema.passed) { $safe.reasonCode = $schema.reasonCode; return [pscustomobject]$safe }
    $safe.schemaPassed = $true
    $semantic = Test-AwxSemanticEnvelope -CaseId $Case.id -Envelope $envelope -Expected $Expected
    $safe.semanticPassed = $semantic.passed
    $safe.reasonCode = $semantic.reasonCode
    $envelope = $null
    $content = $null
    return [pscustomobject]$safe
}

Export-ModuleMember -Function ConvertTo-AwxCanonicalJson,Get-AwxUtf8Sha256,Get-AwxFileSha256,Assert-AwxLoopbackEndpoint,Assert-AwxBenchmarkContract,New-AwxBenchmarkRequestBody,Test-AwxJsonSchemaValue,Test-AwxSemanticEnvelope,Test-AwxBenchmarkResponse
```

- [ ] **Step 6: Run GREEN and scan returned field names**

Run Pester, then:

```powershell
rg -n "rawPrompt|rawResponse|responseText|fullError|Authorization|Bearer" scripts/modules/DesktopOllamaModelTools.psm1 scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1
```

Expected: Pester PASS. The scan may find negative-test string literals, but no production result property or log statement may persist them.

- [ ] **Step 7: Record the conditional checkpoint**

Suggested commit, only with authorization:

```powershell
git add -- scripts/modules/DesktopOllamaModelTools.psm1 scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1
git commit -m "feat: score benchmark v2 typed envelopes"
```

---

### Task 4: Warm Statistics, Hard Gates, Role Scores, and Order-Stable Adjudication

**Files:**
- Modify: `scripts/modules/DesktopOllamaModelTools.psm1`
- Modify: `scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1`

**Interfaces:**
- Consumes: one cold result, exactly 21 warm result summaries, strict `HardGateEvidence`, role-relative metrics, six bounded block evidence hashes, exact internal lane evidence, and exact command evidence facts.
- Produces: strict Boolean/integer/finite/exact-property helpers, internal six-decimal ToEven `ConvertTo-AwxScoreMicrounits`, `Get-AwxNearestRank`, `Get-AwxModelMetrics`, `Test-AwxModelHardGate`, `Get-AwxRoleScore`, `Select-AwxRoleRecommendation`, `New-AwxBlockEvidence`, `New-AwxRunBinding`, `New-AwxEvidencePackets`, `New-AwxCommandEvidence`, `Get-AwxNeutralVerdict`, `Get-AwxOrderStableNeutralVerdict`, `ConvertTo-AwxPublicPacket`, and `ConvertTo-AwxPublicNeutral`.
- No function in this task accepts or returns raw prompt or response text.

- [ ] **Step 1: Add failing cold/warm, score, and adjudication tests**

Append:

```powershell
Describe 'Family A statistics, gates, binding, and adjudication' {
    BeforeAll {
        $script:FamilyAMatrix = @(Get-AwxFamilyAModelIdentityMatrix)
        $script:FamilyABlockEnvelopes = @(
            for ($index = 0; $index -lt $script:FamilyAMatrix.Count; $index++) {
                $model = $script:FamilyAMatrix[$index]
                $caseIds = $(if ($model.role -ceq 'fast') {
                    @('fast-rewrite-ko','fast-json-extract','fast-instruction')
                } else {
                    @('main-grounded-ko','main-uncertainty-ko','main-policy-ko')
                })
                $cold = [pscustomobject][ordered]@{
                    caseId=$caseIds[0];requestSha256=Get-AwxUtf8Sha256 ("request|{0}|cold" -f $model.modelTag)
                    responseSha256=Get-AwxUtf8Sha256 ("response|{0}|cold" -f $model.modelTag);latencyMs=[double](100 + $index)
                    transportPassed=$true;lineagePassed=$true;jsonParsed=$true;schemaPassed=$true;semanticPassed=$true;reasonCode='pass'
                }
                $warm = foreach ($caseId in $caseIds) {
                    foreach ($repetition in 1..7) {
                        [pscustomobject][ordered]@{
                            caseId=$caseId;repetition=$repetition
                            requestSha256=Get-AwxUtf8Sha256 ("request|{0}|{1}|{2}" -f $model.modelTag,$caseId,$repetition)
                            responseSha256=Get-AwxUtf8Sha256 ("response|{0}|{1}|{2}" -f $model.modelTag,$caseId,$repetition)
                            latencyMs=[double](200 + $index + $repetition);transportPassed=$true;lineagePassed=$true
                            jsonParsed=$true;schemaPassed=$true;semanticPassed=$true;reasonCode='pass'
                        }
                    }
                }
                $facts = [pscustomobject][ordered]@{
                    modelTag=$model.modelTag;role=$model.role;classification=$model.classification;cold=$cold;warm=@($warm)
                    lane=[pscustomobject][ordered]@{gpuResidentRatio=0.99;loadedFreeMiB=[double]$(if($model.role -ceq 'fast'){1536}else{2048});lanePassed=$true}
                    cleanup=[pscustomobject][ordered]@{psEmpty=$true;gpuReleased=$true;endpointRestarted=$false}
                }
                New-AwxBlockEvidence -BlockFacts $facts
            }
        )
        $blocks = for ($index = 0; $index -lt 6; $index++) {
            [pscustomobject][ordered]@{
                modelTag=$script:FamilyAMatrix[$index].modelTag;digest=$script:FamilyAMatrix[$index].digest
                blockEvidenceSha256=$script:FamilyABlockEnvelopes[$index].blockEvidenceSha256
            }
        }
        $script:FamilyARun = New-AwxRunBinding -BenchmarkId 'awx.desktop-ollama-benchmark.v2' `
            -CorpusSha256 (Get-AwxUtf8Sha256 'corpus-v2') -OracleSha256 (Get-AwxUtf8Sha256 'oracle-v2') `
            -OntologySha256 (Get-AwxUtf8Sha256 'ontology-v2') -SchemaSha256 (Get-AwxUtf8Sha256 'schema-v2') `
            -OptionsSha256 (Get-AwxUtf8Sha256 'options-v2') -ModelBlocks @($blocks)
        $checks = @($script:FamilyAMatrix | ForEach-Object {[pscustomobject][ordered]@{modelTag=$_.modelTag;passed=$true}})
        $script:FamilyAContract = [pscustomobject][ordered]@{
            contractValidated=$true;digestChecks=$checks;hashCompletenessChecks=$checks;lineageChecks=$checks
        }
        $scenarioModels = @($script:FamilyAMatrix | ForEach-Object {
            $ids = $(if ($_.role -ceq 'fast') {@('fast-rewrite-ko','fast-json-extract','fast-instruction')} else {@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
            [pscustomobject][ordered]@{
                role=$_.role;classification=$_.classification;modelTag=$_.modelTag;hardGatePassed=$true;hardGateReasonCodes=@('pass')
                warmRoleP95Ms=[double](1000 + [array]::IndexOf($script:FamilyAMatrix,$_));loadedFreeMiB=[double]$(if($_.role -ceq 'fast'){1536}else{2048})
                balancedScore=[double](80 + [array]::IndexOf($script:FamilyAMatrix,$_))
                semanticRates=@($ids | ForEach-Object {[pscustomobject][ordered]@{caseId=$_;rate=1.0}})
            }
        })
        $script:FamilyAScenario = [pscustomobject][ordered]@{
            models=$scenarioModels
            recommendations=@(
                [pscustomobject][ordered]@{role='fast';status='keep-baseline';modelTag='qwen3:8b';scoreDelta=0.0;reasonCode='no-eligible-candidate'},
                [pscustomobject][ordered]@{role='main';status='keep-baseline';modelTag='gemma4:26b';scoreDelta=0.0;reasonCode='no-eligible-candidate'}
            )
        }
        $script:FamilyAFalsify = [pscustomobject][ordered]@{
            constRejected=$true;shapeOnlyRejected=$true;extraValueRejected=$true;truncationRejected=$true;timeoutRejected=$true
            missingHashRejected=$true;orderRejected=$true;oracleIsolated=$true;p95Correct=$true
        }
        $script:FamilyAPackets = @(New-AwxEvidencePackets -RunBinding $script:FamilyARun `
            -ContractEvidence $script:FamilyAContract -ScenarioEvidence $script:FamilyAScenario -FalsifyEvidence $script:FamilyAFalsify)
        $script:FamilyACommandFacts = [pscustomobject][ordered]@{
            digestChecks=$checks;completedBlockChecks=$checks
            finalEmptyPsChecks=@(
                [pscustomobject][ordered]@{endpointLabel='fast-11435';passed=$true},
                [pscustomobject][ordered]@{endpointLabel='primary-11434';passed=$true}
            )
            privacyProjectionChecked=$true;privacyProjectionPassed=$true;bindingGuardChecked=$true;bindingGuardUnchanged=$true
        }
        $script:FamilyACommand = New-AwxCommandEvidence -RunBinding $script:FamilyARun -Evidence $script:FamilyACommandFacts
    }

    It 'uses warm-only nearest-rank aggregation with the exact case multiset' {
        (Get-AwxNearestRank -Values (1..21) -Percentile 0.95) | Should -Be 20
        (Get-AwxNearestRank -Values (1..7) -Percentile 0.50) | Should -Be 4
        $warm = foreach ($caseId in @('fast-rewrite-ko','fast-json-extract','fast-instruction')) {
            1..7 | ForEach-Object {[pscustomobject]@{caseId=$caseId;repetition=$_;latencyMs=[double](($_-1)*3 + [array]::IndexOf(@('fast-rewrite-ko','fast-json-extract','fast-instruction'),$caseId)+1);transportPassed=$true;lineagePassed=$true;jsonParsed=$true;schemaPassed=$true;semanticPassed=$true}}
        }
        $metrics = Get-AwxModelMetrics -ColdResult ([pscustomobject]@{ latencyMs = 999999 }) -WarmResults $warm -LoadedFreeMiB 2048
        $metrics.coldLoadMs | Should -Be 999999
        $metrics.warmRoleP95Ms | Should -Be 20
        @($metrics.warmLatenciesMs) | Should -Not -Contain 999999
        $metrics.completedWarmCount | Should -Be 21
        $roundRobin = foreach ($repetition in 1..7) { foreach ($caseId in @('fast-rewrite-ko','fast-json-extract','fast-instruction')) { @($warm | Where-Object {$_.caseId -ceq $caseId -and $_.repetition -eq $repetition})[0] } }
        (Get-AwxModelMetrics -ColdResult ([pscustomobject]@{latencyMs=999999}) -WarmResults $roundRobin -LoadedFreeMiB 2048).warmRoleP95Ms | Should -Be 20
        $misrouted = @($warm | ForEach-Object { $_ | Select-Object * })
        $misrouted[20].caseId = 'fast-rewrite-ko'
        { Get-AwxModelMetrics -ColdResult ([pscustomobject]@{latencyMs=1}) -WarmResults $misrouted -LoadedFreeMiB 2048 } | Should -Throw '*warm-sample-insufficient*'
        $duplicate = @($warm | ForEach-Object { $_ | Select-Object * });$duplicate[20].caseId='fast-instruction';$duplicate[20].repetition=6
        { Get-AwxModelMetrics -ColdResult ([pscustomobject]@{latencyMs=1}) -WarmResults $duplicate -LoadedFreeMiB 2048 } | Should -Throw '*warm-sample-insufficient*'
        $coercive = @($warm | ForEach-Object { $_ | Select-Object * });$coercive[0].repetition='1'
        { Get-AwxModelMetrics -ColdResult ([pscustomobject]@{latencyMs=1}) -WarmResults $coercive -LoadedFreeMiB 2048 } | Should -Throw '*warm-sample-insufficient*'
    }

    It 'enforces strict hard-gate types, ranges, counts, and exact thresholds' {
        $fast = [pscustomobject][ordered]@{digestPassed=$true;lanePassed=$true;lineagePassed=$true;hashesComplete=$true;completedColdCount=1;completedWarmCount=21;caseSampleCountsPassed=$true;warmJsonSchemaPassedCount=21;nonEmptyThinkingCount=0;truncatedCount=0;gpuResidentRatio=0.99;loadedFreeMiB=1536.0;endpointRestarted=$false;timeoutCount=0}
        (Test-AwxModelHardGate -Role fast -Evidence $fast).passed | Should -BeTrue
        $main = $fast | ConvertTo-Json -Depth 10 | ConvertFrom-Json; $main.loadedFreeMiB=2048.0
        (Test-AwxModelHardGate -Role main -Evidence $main).passed | Should -BeTrue
        $belowRatio = $fast | ConvertTo-Json -Depth 10 | ConvertFrom-Json; $belowRatio.gpuResidentRatio=0.989999
        (Test-AwxModelHardGate -Role fast -Evidence $belowRatio).reasonCodes | Should -Contain 'cpu-offload-detected'
        $belowFast = $fast | ConvertTo-Json -Depth 10 | ConvertFrom-Json; $belowFast.loadedFreeMiB=1535.999
        (Test-AwxModelHardGate -Role fast -Evidence $belowFast).reasonCodes | Should -Contain 'insufficient-vram'
        $belowMain = $main | ConvertTo-Json -Depth 10 | ConvertFrom-Json; $belowMain.loadedFreeMiB=2047.999
        (Test-AwxModelHardGate -Role main -Evidence $belowMain).reasonCodes | Should -Contain 'insufficient-vram'
        $wrongLane = $fast | ConvertTo-Json -Depth 10 | ConvertFrom-Json;$wrongLane.lanePassed=$false
        (Test-AwxModelHardGate -Role fast -Evidence $wrongLane).reasonCodes | Should -Be @('gpu-lane-evidence-incomplete')
        foreach ($invalid in @(
            $( $x=$fast|ConvertTo-Json|ConvertFrom-Json;$x.digestPassed='true';$x ),
            $( $x=$fast|ConvertTo-Json|ConvertFrom-Json;$x.completedWarmCount=20.5;$x ),
            $( $x=$fast|ConvertTo-Json|ConvertFrom-Json;$x.gpuResidentRatio=[double]::NaN;$x ),
            $( $x=$fast|ConvertTo-Json|ConvertFrom-Json;$x.timeoutCount=22;$x ),
            $( $x=$fast|ConvertTo-Json|ConvertFrom-Json;$x.completedWarmCount=3;$x.warmJsonSchemaPassedCount=4;$x ),
            $( $x=$fast|ConvertTo-Json|ConvertFrom-Json;$x|Add-Member -NotePropertyName extra -NotePropertyValue $true -PassThru )
        )) {
            $result = Test-AwxModelHardGate -Role fast -Evidence $invalid
            $result.passed | Should -BeFalse; $result.reasonCodes | Should -Be @('benchmark-contract-invalid')
        }
        $metrics=[pscustomobject]@{warmRoleP95Ms=1000.0;loadedFreeMiB=2048.0;rewriteAndExtractionQuality=1.0;instructionFollowing=1.0;toolAndStructuredOutput=1.0;runtimeStability=1.0;koreanRagQuality=1.0;codingAndToolUse=1.0;factualityAndCitationDiscipline=1.0}
        (Get-AwxRoleScore -Role fast -Metrics $metrics -MinEligibleWarmRoleP95Ms 1000 -MaxEligibleLoadedFreeMiB 2048).balancedScore | Should -Be 100.0
        { Get-AwxRoleScore -Role fast -Metrics $metrics -MinEligibleWarmRoleP95Ms ([double]::PositiveInfinity) -MaxEligibleLoadedFreeMiB 2048 } | Should -Throw '*benchmark-contract-invalid*'
        foreach($invalidMetric in @([double]::NaN,-0.01,1.01)){$copy=$metrics|Select-Object *;$copy.instructionFollowing=$invalidMetric;{Get-AwxRoleScore -Role fast -Metrics $copy -MinEligibleWarmRoleP95Ms 1000 -MaxEligibleLoadedFreeMiB 2048}|Should -Throw '*benchmark-contract-invalid*'}
    }

    It 'uses baseline-relative thresholds, retains ties, and rejects coercive gate booleans' {
        $fastBaseline = [pscustomobject]@{classification='baseline';modelTag='qwen3:8b';hardGatePassed=$true;balancedScore=80.0;rewriteAndExtractionQuality=0.90;koreanRagQuality=$null;warmRoleP95Ms=1000}
        $fastCandidate = [pscustomobject]@{classification='primary';modelTag='qwen3.5:9b';hardGatePassed=$true;balancedScore=80.01;rewriteAndExtractionQuality=0.90;koreanRagQuality=$null;warmRoleP95Ms=1000}
        (Select-AwxRoleRecommendation -Role fast -ModelRows @($fastBaseline,$fastCandidate)).modelTag | Should -BeExactly 'qwen3.5:9b'
        $fastCandidate.warmRoleP95Ms = 1001
        (Select-AwxRoleRecommendation -Role fast -ModelRows @($fastBaseline,$fastCandidate)).status | Should -BeExactly 'keep-baseline'
        $fastCandidate.warmRoleP95Ms = 1000; $fastCandidate.balancedScore = 80.0
        (Select-AwxRoleRecommendation -Role fast -ModelRows @($fastBaseline,$fastCandidate)).status | Should -BeExactly 'keep-baseline'

        $mainBaseline = [pscustomobject]@{classification='baseline';modelTag='gemma4:26b';hardGatePassed=$true;balancedScore=75.0;rewriteAndExtractionQuality=$null;koreanRagQuality=0.85;warmRoleP95Ms=1000}
        $mainCandidate = [pscustomobject]@{classification='primary';modelTag='qwen3.6:27b';hardGatePassed=$true;balancedScore=80.0;rewriteAndExtractionQuality=$null;koreanRagQuality=0.85;warmRoleP95Ms=1500}
        (Select-AwxRoleRecommendation -Role main -ModelRows @($mainBaseline,$mainCandidate)).modelTag | Should -BeExactly 'qwen3.6:27b'
        $mainCandidate.balancedScore = 79.99
        (Select-AwxRoleRecommendation -Role main -ModelRows @($mainBaseline,$mainCandidate)).status | Should -BeExactly 'keep-baseline'
        $fastCandidate.hardGatePassed = 'true'
        { Select-AwxRoleRecommendation -Role fast -ModelRows @($fastBaseline,$fastCandidate) } | Should -Throw '*benchmark-contract-invalid*'
    }

    It 'replays known bounded Family A hashes and projects a private order-stable APPLY' {
        $script:FamilyABlockEnvelopes[0].blockEvidenceSha256 | Should -BeExactly '2ba0011c2bc6e020a61e8346ae2f974b1fb9f6aa921c86d77513407b021e2cd8'
        $script:FamilyARun.runBindingSha256 | Should -BeExactly '0b49f747e50e23a97d19c4431197d49e545012713b129e26d1caf693e96cc3f2'
        $script:FamilyAPackets[0].evidenceSha256 | Should -BeExactly 'c16a976def5c96431334b5283c0ffddd458e7972f7453114b6e2ab603c23a5e9'
        $script:FamilyAPackets[0].packetSha256 | Should -BeExactly '4f44426e7fcbb385cbfc62b219b0d0e646db45c44329a631f5eb087173d689d3'
        $script:FamilyAPackets[1].evidenceSha256 | Should -BeExactly '528033b8f3d4b983122a1b2c2844ce60c67f62efad29b3ba1b3b13781aae4aa4'
        $script:FamilyAPackets[1].packetSha256 | Should -BeExactly 'f5eba666094b6c5b0bbcb88eb96c970f8521f0dc007403eb9f4707270e2312c2'
        $script:FamilyAPackets[2].evidenceSha256 | Should -BeExactly '03df061039d2efe36f6925776f6d2596fd0f70224a0370a869572ec232aa8fbe'
        $script:FamilyAPackets[2].packetSha256 | Should -BeExactly 'b9b0ec3cea0f275ef51ee41b84e8191284201a5e5f2081303bab10a948d31904'
        $script:FamilyACommand.commandEvidenceSha256 | Should -BeExactly 'e9ae7ae4cb22fb15aac2e9962ac206d588269d92197902126228ffcb7c497900'
        @($script:FamilyAPackets.passed) | Should -Not -Contain $false
        @($script:FamilyAPackets.reasonCodes | ForEach-Object {@($_)}) | Should -Not -Contain 'benchmark-contract-invalid'
        $packetOrder=@($script:FamilyAPackets.name)
        $provisionalFacts=$script:FamilyACommandFacts|ConvertTo-Json -Depth 40|ConvertFrom-Json;$provisionalFacts.privacyProjectionChecked=$false;$provisionalFacts.privacyProjectionPassed=$false
        $provisional=New-AwxCommandEvidence -RunBinding $script:FamilyARun -Evidence $provisionalFacts
        [void](Get-AwxOrderStableNeutralVerdict -Packets $script:FamilyAPackets -CommandEvidence $provisional)
        @($script:FamilyAPackets.name) | Should -Be $packetOrder
        $neutral = Get-AwxOrderStableNeutralVerdict -Packets $script:FamilyAPackets -CommandEvidence $script:FamilyACommand
        @($script:FamilyAPackets.name) | Should -Be $packetOrder
        $neutral.forward | Should -BeExactly 'APPLY'
        $neutral.forwardReasonCode | Should -BeExactly 'all-packets-pass'
        $neutral.reverse | Should -BeExactly $neutral.forward
        $neutral.reverseReasonCode | Should -BeExactly $neutral.forwardReasonCode
        $neutral.orderStable | Should -BeTrue
        $publicPackets = @($script:FamilyAPackets | ForEach-Object {ConvertTo-AwxPublicPacket $_})
        @($publicPackets[0].PSObject.Properties.Name) | Should -Be @('name','complete','passed','reasonCodes','runBindingSha256','evidenceSha256','packetSha256')
        ($publicPackets | ConvertTo-Json -Depth 20) | Should -Not -Match '"evidence"|"runBinding"'
        $publicNeutral = ConvertTo-AwxPublicNeutral $neutral
        @($publicNeutral.PSObject.Properties.Name) | Should -Be @('forward','forwardReasonCode','reverse','reverseReasonCode','orderStable','finalVerdict','finalReasonCode','runBindingSha256','commandEvidenceSha256')
        $inputPath = Join-Path $TestDrive 'family-a-replay-input.json'
        $replayInput=[ordered]@{blockFacts=@($script:FamilyABlockEnvelopes.blockFacts);runBinding=$script:FamilyARun.runBinding;contract=$script:FamilyAContract;scenario=$script:FamilyAScenario;falsify=$script:FamilyAFalsify;commandFacts=$script:FamilyACommandFacts}
        ConvertTo-AwxCanonicalJson $replayInput | Set-Content -LiteralPath $inputPath -Encoding UTF8
        $escapedModule=$script:ModulePath.Replace("'","''");$escapedInput=$inputPath.Replace("'","''")
        $command="Import-Module '$escapedModule' -Force;`$i=Get-Content -Raw -Encoding UTF8 '$escapedInput'|ConvertFrom-Json;`$m=@(Get-AwxFamilyAModelIdentityMatrix);`$b=@(`$i.blockFacts|%{New-AwxBlockEvidence -BlockFacts `$_});`$refs=for(`$n=0;`$n -lt 6;`$n++){[pscustomobject][ordered]@{modelTag=`$m[`$n].modelTag;digest=`$m[`$n].digest;blockEvidenceSha256=`$b[`$n].blockEvidenceSha256}};`$r=New-AwxRunBinding -BenchmarkId `$i.runBinding.benchmarkId -CorpusSha256 `$i.runBinding.corpusSha256 -OracleSha256 `$i.runBinding.oracleSha256 -OntologySha256 `$i.runBinding.ontologySha256 -SchemaSha256 `$i.runBinding.schemaSha256 -OptionsSha256 `$i.runBinding.optionsSha256 -ModelBlocks @(`$refs);`$p=@(New-AwxEvidencePackets -RunBinding `$r -ContractEvidence `$i.contract -ScenarioEvidence `$i.scenario -FalsifyEvidence `$i.falsify);`$c=New-AwxCommandEvidence -RunBinding `$r -Evidence `$i.commandFacts;`$n=Get-AwxOrderStableNeutralVerdict -Packets `$p -CommandEvidence `$c;ConvertTo-AwxCanonicalJson ([ordered]@{blockHashes=@(`$b.blockEvidenceSha256);runBindingSha256=`$r.runBindingSha256;evidenceHashes=@(`$p.evidenceSha256);packetHashes=@(`$p.packetSha256);packetNames=@(`$p.name);commandEvidenceSha256=`$c.commandEvidenceSha256;verdict=`$n.finalVerdict;reasonCode=`$n.finalReasonCode})"
        $replay1=(& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command $command|Out-String).Trim();$replay2=(& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command $command|Out-String).Trim()
        $current=ConvertTo-AwxCanonicalJson ([ordered]@{blockHashes=@($script:FamilyABlockEnvelopes.blockEvidenceSha256);runBindingSha256=$script:FamilyARun.runBindingSha256;evidenceHashes=@($script:FamilyAPackets.evidenceSha256);packetHashes=@($script:FamilyAPackets.packetSha256);packetNames=@($script:FamilyAPackets.name);commandEvidenceSha256=$script:FamilyACommand.commandEvidenceSha256;verdict=$neutral.finalVerdict;reasonCode=$neutral.finalReasonCode})
        $replay1 | Should -BeExactly $replay2; $replay1 | Should -BeExactly $current
    }

    It 'holds every Family A mutation, malformed envelope, reason violation, and order disagreement' {
        $copy={param($value) $value|ConvertTo-Json -Depth 40|ConvertFrom-Json}
        $evidenceHash={param($p) Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-lane-evidence.v2';runBindingSha256=$p.runBindingSha256;name=$p.name;evidence=$p.evidence}))}
        $packetHash={param($p) Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-packet.v2';runBindingSha256=$p.runBindingSha256;name=$p.name;complete=$p.complete;passed=$p.passed;reasonCodes=@($p.reasonCodes);evidenceSha256=$p.evidenceSha256}))}
        $mutations=[Collections.Generic.List[object]]::new()
        $forged=&$copy $script:FamilyAPackets;$forged[0].packetSha256=Get-AwxUtf8Sha256 'forged-format-only';$mutations.Add(@($forged))
        $changed=&$copy $script:FamilyAPackets;$changed[0].evidence.contractValidated=$false;$mutations.Add(@($changed))
        $hashOnly=&$copy $script:FamilyAPackets;$hashOnly[0].evidence.contractValidated=$false;$hashOnly[0].evidenceSha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $hashOnly[0].evidence);$mutations.Add(@($hashOnly))
        $stale=&$copy $script:FamilyAPackets;$stale[0].runBindingSha256=Get-AwxUtf8Sha256 'stale-run';$stale[0].packetSha256=&$packetHash $stale[0];$mutations.Add(@($stale))
        $missing=&$copy $script:FamilyAPackets;$missing[0].evidence.PSObject.Properties.Remove('lineageChecks');$mutations.Add(@($missing))
        $extra=&$copy $script:FamilyAPackets;$extra[0].evidence|Add-Member extraFact $true;$mutations.Add(@($extra))
        $duplicateCheck=&$copy $script:FamilyAPackets;$duplicateCheck[0].evidence.digestChecks[1]=$duplicateCheck[0].evidence.digestChecks[0];$duplicateCheck[0].evidenceSha256=&$evidenceHash $duplicateCheck[0];$duplicateCheck[0].packetSha256=&$packetHash $duplicateCheck[0];$mutations.Add(@($duplicateCheck))
        $duplicateModel=&$copy $script:FamilyAPackets;$duplicateModel[1].evidence.models[1]=$duplicateModel[1].evidence.models[0];$duplicateModel[1].evidenceSha256=&$evidenceHash $duplicateModel[1];$duplicateModel[1].packetSha256=&$packetHash $duplicateModel[1];$mutations.Add(@($duplicateModel))
        $duplicateRecommendation=&$copy $script:FamilyAPackets;$duplicateRecommendation[1].evidence.recommendations[1]=$duplicateRecommendation[1].evidence.recommendations[0];$duplicateRecommendation[1].evidenceSha256=&$evidenceHash $duplicateRecommendation[1];$duplicateRecommendation[1].packetSha256=&$packetHash $duplicateRecommendation[1];$mutations.Add(@($duplicateRecommendation))
        $inconsistentRecommendation=&$copy $script:FamilyAPackets;$inconsistentRecommendation[1].evidence.recommendations[0]=[pscustomobject][ordered]@{role='fast';status='recommend-candidate';modelTag='qwen3.5:9b';scoreDelta=1.0;reasonCode='promotion-approval-required'};$inconsistentRecommendation[1].evidenceSha256=&$evidenceHash $inconsistentRecommendation[1];$inconsistentRecommendation[1].packetSha256=&$packetHash $inconsistentRecommendation[1];$mutations.Add(@($inconsistentRecommendation))
        foreach($candidate in $mutations){(Get-AwxNeutralVerdict -Packets $candidate -CommandEvidence $script:FamilyACommand).verdict|Should -BeExactly 'HOLD'}
        (Get-AwxNeutralVerdict -Packets @($script:FamilyAPackets[0],$script:FamilyAPackets[0],$script:FamilyAPackets[2]) -CommandEvidence $script:FamilyACommand).verdict | Should -BeExactly 'HOLD'
        $badCommand=&$copy $script:FamilyACommand;$badCommand.PSObject.Properties.Remove('evidence');(Get-AwxNeutralVerdict -Packets $script:FamilyAPackets -CommandEvidence $badCommand).verdict|Should -BeExactly 'HOLD'
        $missingCommandHashes=&$copy $script:FamilyACommand;$missingCommandHashes.PSObject.Properties.Remove('evidence');$missingCommandHashes.PSObject.Properties.Remove('runBindingSha256');$missingCommandHashes.PSObject.Properties.Remove('commandEvidenceSha256')
        { $script:FamilyAMalformedOrder=Get-AwxOrderStableNeutralVerdict -Packets $script:FamilyAPackets -CommandEvidence $missingCommandHashes }|Should -Not -Throw
        $script:FamilyAMalformedOrder.finalVerdict|Should -BeExactly 'HOLD';$script:FamilyAMalformedOrder.finalReasonCode|Should -BeExactly 'command-evidence-incomplete'
        ($null -eq $script:FamilyAMalformedOrder.runBindingSha256)|Should -BeTrue;($null -eq $script:FamilyAMalformedOrder.commandEvidenceSha256)|Should -BeTrue
        $duplicateFact=&$copy $script:FamilyABlockEnvelopes[0].blockFacts;$duplicateFact.warm[1]=$duplicateFact.warm[0]
        {New-AwxBlockEvidence -BlockFacts $duplicateFact}|Should -Throw '*benchmark-contract-invalid*'
        $malformedScenario=&$copy $script:FamilyAScenario;$malformedScenario.models[0].hardGatePassed=$false;$malformedScenario.models[0].hardGateReasonCodes=@('benchmark-contract-invalid')
        $malformedScenario.recommendations[0]=[pscustomobject][ordered]@{role='fast';status='HOLD';modelTag=$null;scoreDelta=$null;reasonCode='baseline-evidence-incomplete'}
        $mapped=@(New-AwxEvidencePackets -RunBinding $script:FamilyARun -ContractEvidence $script:FamilyAContract -ScenarioEvidence $malformedScenario -FalsifyEvidence $script:FamilyAFalsify)
        $mapped[1].passed|Should -BeFalse;$mapped[1].reasonCodes|Should -Be @('baseline-evidence-incomplete')
        $selectorRows=@(
            [pscustomobject][ordered]@{classification='baseline';modelTag='qwen3:8b';hardGatePassed=$true;balancedScore=80.0;rewriteAndExtractionQuality=1.0;koreanRagQuality=$null;warmRoleP95Ms=1000.0},
            [pscustomobject][ordered]@{classification='primary';modelTag='qwen3.5:9b';hardGatePassed=$true;balancedScore=81.0;rewriteAndExtractionQuality=1.0;koreanRagQuality=$null;warmRoleP95Ms=1001.0}
        )
        $failedBaseline=&$copy $selectorRows;$failedBaseline[0].hardGatePassed=$false;(Select-AwxRoleRecommendation -Role fast -ModelRows $failedBaseline).reasonCode|Should -BeExactly 'baseline-evidence-incomplete'
        $nullBaselineScore=&$copy $selectorRows;$nullBaselineScore[0].balancedScore=$null;(Select-AwxRoleRecommendation -Role fast -ModelRows $nullBaselineScore).reasonCode|Should -BeExactly 'baseline-evidence-incomplete'
        $nullBaselineQuality=&$copy $selectorRows;$nullBaselineQuality[0].rewriteAndExtractionQuality=$null;(Select-AwxRoleRecommendation -Role fast -ModelRows $nullBaselineQuality).reasonCode|Should -BeExactly 'baseline-evidence-incomplete'
        $missingCandidateMetric=&$copy $selectorRows;$missingCandidateMetric[1].balancedScore=$null;{Select-AwxRoleRecommendation -Role fast -ModelRows $missingCandidateMetric}|Should -Throw '*benchmark-contract-invalid*'
        $mismatchedIdentity=&$copy $selectorRows;$mismatchedIdentity[1].classification='challenger';{Select-AwxRoleRecommendation -Role fast -ModelRows $mismatchedIdentity}|Should -Throw '*benchmark-contract-invalid*'
        $precisionRows=&$copy $selectorRows;$precisionRows[1].balancedScore=80.000001;$precisionRows[1].warmRoleP95Ms=1000.0
        $precisionRecommendation=Select-AwxRoleRecommendation -Role fast -ModelRows $precisionRows
        $precisionRecommendation.status|Should -BeExactly 'recommend-candidate';$precisionRecommendation.scoreDelta|Should -Be 0.000001;([double]$precisionRecommendation.scoreDelta -gt 0)|Should -BeTrue
        $noncanonicalScore=&$copy $precisionRows;$noncanonicalScore[1].balancedScore=80.0000004;{Select-AwxRoleRecommendation -Role fast -ModelRows $noncanonicalScore}|Should -Throw '*benchmark-contract-invalid*'
        $precisionScenario=&$copy $script:FamilyAScenario;$precisionScenario.models[1].balancedScore=80.000001;$precisionScenario.models[1].warmRoleP95Ms=1000.0;$precisionScenario.recommendations[0]=[pscustomobject][ordered]@{role='fast';status='recommend-candidate';modelTag='qwen3.5:9b';scoreDelta=0.000001;reasonCode='promotion-approval-required'}
        $precisionPackets=@(New-AwxEvidencePackets -RunBinding $script:FamilyARun -ContractEvidence $script:FamilyAContract -ScenarioEvidence $precisionScenario -FalsifyEvidence $script:FamilyAFalsify);$precisionPackets[1].passed|Should -BeTrue;{ConvertTo-AwxPublicPacket $precisionPackets[1]}|Should -Not -Throw
        foreach($nonpositiveDelta in @(0.0,-0.000001)){
            $invalidScenario=&$copy $precisionScenario;$invalidScenario.recommendations[0].scoreDelta=$nonpositiveDelta
            {New-AwxEvidencePackets -RunBinding $script:FamilyARun -ContractEvidence $script:FamilyAContract -ScenarioEvidence $invalidScenario -FalsifyEvidence $script:FamilyAFalsify}|Should -Throw '*benchmark-contract-invalid*'
            $invalidRecommendationPacket=&$copy $precisionPackets[1];$invalidRecommendationPacket.evidence.recommendations[0].scoreDelta=$nonpositiveDelta;$invalidRecommendationPacket.evidenceSha256=&$evidenceHash $invalidRecommendationPacket;$invalidRecommendationPacket.packetSha256=&$packetHash $invalidRecommendationPacket
            {ConvertTo-AwxPublicPacket $invalidRecommendationPacket}|Should -Throw '*benchmark-contract-invalid*'
        }
        $reasonCases=[Collections.Generic.List[object]]::new()
        [void]$reasonCases.Add([object[]]@('unknown-code'));[void]$reasonCases.Add([object[]]@('pass','pass'));[void]$reasonCases.Add([object[]]@('Pass'));[void]$reasonCases.Add([object[]]@('warm-sample-insufficient'));[void]$reasonCases.Add([object[]]@('sk-secret-shaped-value'))
        foreach($badReasons in $reasonCases){
            $bad=&$copy $script:FamilyAPackets;$bad[0].reasonCodes=@($badReasons);$bad[0].packetSha256=&$packetHash $bad[0]
            (Get-AwxNeutralVerdict -Packets $bad -CommandEvidence $script:FamilyACommand).verdict|Should -BeExactly 'HOLD'
        }
        $status=&$copy $script:FamilyAPackets;$status[0].passed=$false;$status[0].reasonCodes=@('pass');$status[0].packetSha256=&$packetHash $status[0]
        (Get-AwxNeutralVerdict -Packets $status -CommandEvidence $script:FamilyACommand).verdict|Should -BeExactly 'HOLD'
        $invalidProjectionPacket=&$copy $script:FamilyAPackets[0];$invalidProjectionPacket.evidence.contractValidated=$false
        {ConvertTo-AwxPublicPacket $invalidProjectionPacket}|Should -Throw '*benchmark-contract-invalid*'
        $validNeutral=Get-AwxOrderStableNeutralVerdict -Packets $script:FamilyAPackets -CommandEvidence $script:FamilyACommand
        foreach($invalidReason in @('unknown-code','All-Packets-Pass','sk-secret-shaped-value','rawResponse')){
            $invalidNeutral=&$copy $validNeutral;$invalidNeutral.forwardReasonCode=$invalidReason
            {ConvertTo-AwxPublicNeutral $invalidNeutral}|Should -Throw '*benchmark-contract-invalid*'
        }
        $nullHashNeutral=&$copy $validNeutral;$nullHashNeutral.runBindingSha256=$null;{ConvertTo-AwxPublicNeutral $nullHashNeutral}|Should -Throw '*benchmark-contract-invalid*'
        $extraNeutral=&$copy $validNeutral;$extraNeutral|Add-Member extraFact $true;{ConvertTo-AwxPublicNeutral $extraNeutral}|Should -Throw '*benchmark-contract-invalid*'
        $module=Get-Module DesktopOllamaModelTools
        $orderVerdict=&$module {Resolve-AwxNeutralOrder -Forward ([pscustomobject]@{verdict='APPLY';reasonCode='all-packets-pass'}) -Reverse ([pscustomobject]@{verdict='HOLD';reasonCode='scenario-evidence-incomplete'}) -RunBindingSha256 (Get-AwxUtf8Sha256 'order-run') -CommandEvidenceSha256 (Get-AwxUtf8Sha256 'order-command')}
        $orderReason=&$module {Resolve-AwxNeutralOrder -Forward ([pscustomobject]@{verdict='APPLY';reasonCode='all-packets-pass'}) -Reverse ([pscustomobject]@{verdict='APPLY';reasonCode='scenario-evidence-incomplete'}) -RunBindingSha256 (Get-AwxUtf8Sha256 'order-run') -CommandEvidenceSha256 (Get-AwxUtf8Sha256 'order-command')}
        $orderVerdict.finalReasonCode|Should -BeExactly 'order-unstable';$orderReason.finalReasonCode|Should -BeExactly 'order-unstable'
    }
}
```

- [ ] **Step 2: Run RED**

Expected: FAIL because the statistics, promotion, and adjudication functions are absent.

- [ ] **Step 3: Implement strict scalar/property helpers and warm-only metrics**

Add:

```powershell
function Test-AwxStrictBoolean { param($Value); return $Value -is [System.Boolean] }

function Test-AwxStrictIntegerRange {
    param($Value,[long]$Minimum,[long]$Maximum)
    $integerTypes=@([byte],[sbyte],[int16],[uint16],[int32],[uint32],[int64],[uint64])
    if ($null -eq $Value -or -not ($integerTypes -contains $Value.GetType())) { return $false }
    try { $number=[decimal]$Value } catch { return $false }
    return $number -ge $Minimum -and $number -le $Maximum
}

function Test-AwxFiniteNumberRange {
    param($Value,[double]$Minimum,[double]$Maximum=[double]::PositiveInfinity)
    if (-not (Test-AwxNumber $Value) -or $Value -is [bool]) { return $false }
    try { $number=[double]$Value } catch { return $false }
    return -not [double]::IsNaN($number) -and -not [double]::IsInfinity($number) -and $number -ge $Minimum -and $number -le $Maximum
}

function Assert-AwxExactPropertySet {
    param([Parameter(Mandatory)]$Value,[Parameter(Mandatory)][string[]]$Names,[string]$ReasonCode='benchmark-contract-invalid')
    if ($null -eq $Value -or -not (Test-AwxExactSet @($Value.PSObject.Properties.Name) $Names)) { throw $ReasonCode }
}

function Assert-AwxHash64 { param($Value,[string]$ReasonCode='benchmark-contract-invalid');if($Value -isnot [string] -or $Value -cnotmatch '^[a-f0-9]{64}$'){throw $ReasonCode} }

function Get-AwxNearestRank {
    param([Parameter(Mandatory)][double[]]$Values,[Parameter(Mandatory)][double]$Percentile)
    if ($Values.Count -eq 0 -or -not (Test-AwxFiniteNumberRange $Percentile 0.0 1.0) -or $Percentile -eq 0.0 -or @($Values|Where-Object{-not(Test-AwxFiniteNumberRange $_ 0.0)}).Count -gt 0) { throw 'benchmark-contract-invalid' }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling($Percentile * $sorted.Count) - 1
    return [double]$sorted[$index]
}

function Get-AwxSemanticRate {
    param([Parameter(Mandatory)][object[]]$WarmResults,[Parameter(Mandatory)][string]$CaseId)
    $rows = @($WarmResults | Where-Object { $_.caseId -ceq $CaseId })
    if ($rows.Count -ne 7) { return 0.0 }
    return [double](@($rows | Where-Object semanticPassed).Count / 7.0)
}

function Get-AwxModelMetrics {
    param([Parameter(Mandatory)]$ColdResult,[Parameter(Mandatory)]$WarmResults,[Parameter(Mandatory)]$LoadedFreeMiB)
    $fastIds = @('fast-rewrite-ko','fast-json-extract','fast-instruction')
    $mainIds = @('main-grounded-ko','main-uncertainty-ko','main-policy-ko')
    if($WarmResults -isnot [System.Array] -or -not(Test-AwxFiniteNumberRange $LoadedFreeMiB 0.0) -or -not(Test-AwxFiniteNumberRange $ColdResult.latencyMs 0.0)){throw 'benchmark-contract-invalid'}
    $actualIds = @($WarmResults.caseId)
    $fastOnly = $WarmResults.Count -eq 21 -and @($actualIds | Where-Object {$_ -cnotin $fastIds}).Count -eq 0
    $mainOnly = $WarmResults.Count -eq 21 -and @($actualIds | Where-Object {$_ -cnotin $mainIds}).Count -eq 0
    if ($fastOnly -eq $mainOnly) { throw 'warm-sample-insufficient' }
    $roleIds = $(if ($fastOnly) {$fastIds} else {$mainIds})
    foreach($id in $roleIds){
        $rows=@($WarmResults|Where-Object caseId -CEQ $id)
        if($rows.Count -ne 7 -or @($rows|Where-Object{-not(Test-AwxStrictIntegerRange $_.repetition 1 7)}).Count -gt 0 -or -not(Test-AwxExactSequence @($rows.repetition|Sort-Object) @(1..7))){throw 'warm-sample-insufficient'}
    }
    foreach($row in $WarmResults){if(-not(Test-AwxFiniteNumberRange $row.latencyMs 0.0)){throw 'benchmark-contract-invalid'};foreach($name in @('transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed')){if(-not(Test-AwxStrictBoolean $row.$name)){throw 'benchmark-contract-invalid'}}}
    $rates = [ordered]@{}
    foreach ($id in @('fast-rewrite-ko','fast-json-extract','fast-instruction','main-grounded-ko','main-uncertainty-ko','main-policy-ko')) {
        $rates[$id] = Get-AwxSemanticRate -WarmResults $WarmResults -CaseId $id
    }
    $warmLatencies = @($WarmResults | ForEach-Object { [double]$_.latencyMs })
    $complete = @($WarmResults | Where-Object { $_.transportPassed }).Count
    $caseP50 = [ordered]@{}
    foreach ($id in $roleIds) {
        $latencies = @($WarmResults | Where-Object { $_.caseId -ceq $id } | ForEach-Object { [double]$_.latencyMs })
        $caseP50[$id] = Get-AwxNearestRank -Values $latencies -Percentile 0.50
    }
    return [pscustomobject][ordered]@{
        coldLoadMs = [double]$ColdResult.latencyMs
        warmLatenciesMs = $warmLatencies
        warmRoleP95Ms = $(if ($warmLatencies.Count -eq 21) { Get-AwxNearestRank -Values $warmLatencies -Percentile 0.95 } else { $null })
        warmCaseP50Ms = [pscustomobject]$caseP50
        completedWarmCount = $complete
        runtimeStability = [Math]::Min(1.0,[Math]::Max(0.0,[double]($complete / 21.0)))
        loadedFreeMiB = $LoadedFreeMiB
        semanticRates = [pscustomobject]$rates
        rewriteAndExtractionQuality = [double](($rates['fast-rewrite-ko'] + $rates['fast-json-extract']) / 2.0)
        toolAndStructuredOutput = [double]$rates['fast-json-extract']
        koreanRagQuality = [double]$rates['main-grounded-ko']
        factualityAndCitationDiscipline = [double](($rates['main-grounded-ko'] + $rates['main-uncertainty-ko']) / 2.0)
        instructionFollowing = [double]$(if ($roleIds[0] -ceq 'fast-rewrite-ko') {$rates['fast-instruction']} else {$rates['main-policy-ko']})
        codingAndToolUse = [double]$rates['main-policy-ko']
    }
}
```

The exact case/repetition multiset check accepts grouped and round-robin acquisition while keeping cold data physically outside every warm aggregate and rejecting missing, duplicated, coercive, or misrouted rows.

- [ ] **Step 4: Implement the common hard gate and role scores**

```powershell
function Test-AwxModelHardGate {
    param([Parameter(Mandatory)][ValidateSet('fast','main')][string]$Role,[Parameter(Mandatory)]$Evidence)
    $properties=@('digestPassed','lanePassed','lineagePassed','hashesComplete','completedColdCount','completedWarmCount','caseSampleCountsPassed','warmJsonSchemaPassedCount','nonEmptyThinkingCount','truncatedCount','gpuResidentRatio','loadedFreeMiB','endpointRestarted','timeoutCount')
    try {
        Assert-AwxExactPropertySet $Evidence $properties
        foreach($name in @('digestPassed','lanePassed','lineagePassed','hashesComplete','caseSampleCountsPassed','endpointRestarted')){if(-not(Test-AwxStrictBoolean $Evidence.$name)){throw 'benchmark-contract-invalid'}}
        if(-not(Test-AwxStrictIntegerRange $Evidence.completedColdCount 0 1) -or -not(Test-AwxStrictIntegerRange $Evidence.completedWarmCount 0 21) -or
           -not(Test-AwxStrictIntegerRange $Evidence.warmJsonSchemaPassedCount 0 21) -or -not(Test-AwxStrictIntegerRange $Evidence.nonEmptyThinkingCount 0 21) -or
           -not(Test-AwxStrictIntegerRange $Evidence.truncatedCount 0 21) -or -not(Test-AwxStrictIntegerRange $Evidence.timeoutCount 0 21) -or
           -not(Test-AwxFiniteNumberRange $Evidence.gpuResidentRatio 0.0 1.0) -or -not(Test-AwxFiniteNumberRange $Evidence.loadedFreeMiB 0.0) -or
           [long]$Evidence.warmJsonSchemaPassedCount -gt [long]$Evidence.completedWarmCount -or [long]$Evidence.nonEmptyThinkingCount -gt [long]$Evidence.completedWarmCount -or
           [long]$Evidence.truncatedCount -gt [long]$Evidence.completedWarmCount -or [long]$Evidence.timeoutCount -gt [long]$Evidence.completedWarmCount){throw 'benchmark-contract-invalid'}
    } catch { return [pscustomobject][ordered]@{passed=$false;reasonCodes=@('benchmark-contract-invalid')} }
    $minFree = $(if ($Role -ceq 'fast') { 1536.0 } else { 2048.0 })
    $reasons = [Collections.Generic.List[string]]::new()
    if (-not $Evidence.digestPassed) { $reasons.Add('candidate-digest-changed') }
    if (-not $Evidence.lanePassed) { $reasons.Add('gpu-lane-evidence-incomplete') }
    if (-not $Evidence.lineagePassed -or -not $Evidence.hashesComplete) { $reasons.Add('lineage-missing') }
    if ([int]$Evidence.completedColdCount -ne 1 -or [int]$Evidence.completedWarmCount -ne 21 -or -not [bool]$Evidence.caseSampleCountsPassed) { $reasons.Add('warm-sample-insufficient') }
    if ([int]$Evidence.warmJsonSchemaPassedCount -ne 21) { $reasons.Add('response-schema-invalid') }
    if ([int]$Evidence.nonEmptyThinkingCount -ne 0) { $reasons.Add('lineage-missing') }
    if ([int]$Evidence.truncatedCount -ne 0) { $reasons.Add('response-truncated') }
    if ([double]$Evidence.gpuResidentRatio -lt 0.99) { $reasons.Add('cpu-offload-detected') }
    if ([double]$Evidence.loadedFreeMiB -lt $minFree) { $reasons.Add('insufficient-vram') }
    if ([bool]$Evidence.endpointRestarted -or [int]$Evidence.timeoutCount -ne 0) { $reasons.Add('warm-sample-insufficient') }
    $policy=@(Get-AwxHardGateReasonPolicy | Where-Object {$_ -cne 'pass' -and $_ -cne 'benchmark-contract-invalid'})
    $unique=@($policy|Where-Object{$reasons -ccontains $_})
    return [pscustomobject][ordered]@{passed=($unique.Count -eq 0);reasonCodes=$(if($unique.Count -eq 0){@('pass')}else{$unique})}
}

function Get-AwxRoleScore {
    param(
        [Parameter(Mandatory)][ValidateSet('fast','main')][string]$Role,
        [Parameter(Mandatory)]$Metrics,
        [Parameter(Mandatory)]$MinEligibleWarmRoleP95Ms,
        [Parameter(Mandatory)]$MaxEligibleLoadedFreeMiB
    )
    foreach($value in @($Metrics.warmRoleP95Ms,$Metrics.loadedFreeMiB,$MinEligibleWarmRoleP95Ms,$MaxEligibleLoadedFreeMiB)){if(-not(Test-AwxFiniteNumberRange $value 0.0)){throw 'benchmark-contract-invalid'}}
    $semanticNames=$(if($Role -ceq 'fast'){@('rewriteAndExtractionQuality','instructionFollowing','toolAndStructuredOutput','runtimeStability')}else{@('koreanRagQuality','instructionFollowing','codingAndToolUse','factualityAndCitationDiscipline','runtimeStability')})
    foreach($name in $semanticNames){if(-not(Test-AwxFiniteNumberRange $Metrics.$name 0.0 1.0)){throw 'benchmark-contract-invalid'}}
    if ($Metrics.warmRoleP95Ms -le 0 -or $MinEligibleWarmRoleP95Ms -le 0 -or $MaxEligibleLoadedFreeMiB -le 0) { throw 'benchmark-contract-invalid' }
    $latency = [Math]::Min(1.0,[Math]::Max(0.0,$MinEligibleWarmRoleP95Ms / [double]$Metrics.warmRoleP95Ms))
    $vram = [Math]::Min(1.0,[Math]::Max(0.0,[double]$Metrics.loadedFreeMiB / $MaxEligibleLoadedFreeMiB))
    if ($Role -ceq 'fast') {
        $score = 100.0 * (0.30 * $Metrics.rewriteAndExtractionQuality + 0.30 * $latency +
            0.15 * $Metrics.instructionFollowing + 0.10 * $Metrics.toolAndStructuredOutput +
            0.10 * $vram + 0.05 * $Metrics.runtimeStability)
    } else {
        $score = 100.0 * (0.35 * $Metrics.koreanRagQuality + 0.20 * $Metrics.instructionFollowing +
            0.15 * $Metrics.codingAndToolUse + 0.10 * $Metrics.factualityAndCitationDiscipline +
            0.10 * $latency + 0.05 * $vram + 0.05 * $Metrics.runtimeStability)
    }
    $boundedScore=[Math]::Min(100.0,[Math]::Max(0.0,$score))
    $canonicalScore=[Math]::Round([decimal]$boundedScore,6,[MidpointRounding]::ToEven)
    return [pscustomobject]@{balancedScore=[double]$canonicalScore;latencyEfficiency=$latency;vramHeadroom=$vram}
}
```

- [ ] **Step 5: Implement canonical score microunits and baseline-relative recommendation without voting**

```powershell
function ConvertTo-AwxScoreMicrounits {
    param($Score,[switch]$AllowSigned)
    $minimum=$(if($AllowSigned){-100.0}else{0.0})
    if(-not(Test-AwxFiniteNumberRange -Value $Score -Minimum $minimum -Maximum 100.0)){throw 'benchmark-contract-invalid'}
    try{$decimalScore=[decimal]$Score}catch{throw 'benchmark-contract-invalid'}
    $canonical=[Math]::Round($decimalScore,6,[MidpointRounding]::ToEven)
    if($decimalScore -ne $canonical){throw 'benchmark-contract-invalid'}
    $scaled=[decimal]::Multiply($canonical,[decimal]1000000)
    if($scaled -ne [decimal]::Truncate($scaled) -or $scaled -lt -100000000 -or $scaled -gt 100000000){throw 'benchmark-contract-invalid'}
    return [int64]$scaled
}

function Test-AwxRecommendationMetricSet {
    param($Row,[Parameter(Mandatory)][ValidateSet('fast','main')][string]$Role)
    $qualityName=$(if($Role -ceq 'fast'){'rewriteAndExtractionQuality'}else{'koreanRagQuality'})
    try{[void](ConvertTo-AwxScoreMicrounits -Score $Row.balancedScore)}catch{return $false}
    return (Test-AwxFiniteNumberRange $Row.$qualityName 0.0 1.0) -and
        (Test-AwxFiniteNumberRange $Row.warmRoleP95Ms 0.0) -and [double]$Row.warmRoleP95Ms -gt 0.0
}

function New-AwxBaselineEvidenceIncompleteRecommendation {
    param([Parameter(Mandatory)][ValidateSet('fast','main')][string]$Role)
    return [pscustomobject][ordered]@{role=$Role;status='HOLD';modelTag=$null;scoreDelta=$null;reasonCode='baseline-evidence-incomplete'}
}

function Select-AwxRoleRecommendation {
    param([Parameter(Mandatory)][ValidateSet('fast','main')][string]$Role,[Parameter(Mandatory)][object[]]$ModelRows)
    $rows=@($ModelRows);$roleMatrix=@(Get-AwxFamilyAModelIdentityMatrix|Where-Object role -CEQ $Role)
    if($rows.Count -lt 1 -or $rows.Count -gt 3){throw 'benchmark-contract-invalid'}
    $seen=[Collections.Generic.List[string]]::new()
    foreach($row in $rows){
        Assert-AwxExactPropertySet $row @('classification','modelTag','hardGatePassed','balancedScore','rewriteAndExtractionQuality','koreanRagQuality','warmRoleP95Ms')
        if(-not(Test-AwxStrictBoolean $row.hardGatePassed)){throw 'benchmark-contract-invalid'}
        $identity=@($roleMatrix|Where-Object {$_.modelTag -ceq $row.modelTag})
        if($identity.Count -ne 1 -or $identity[0].classification -cne $row.classification -or $seen -ccontains [string]$row.modelTag){throw 'benchmark-contract-invalid'}
        $seen.Add([string]$row.modelTag)
    }
    $baseline=@($rows|Where-Object classification -CEQ 'baseline')
    if($baseline.Count -ne 1){return New-AwxBaselineEvidenceIncompleteRecommendation -Role $Role}
    $qualityName=$(if($Role -ceq 'fast'){'rewriteAndExtractionQuality'}else{'koreanRagQuality'})
    foreach($row in @($rows|Where-Object classification -CNE 'baseline')){
        if($row.hardGatePassed){
            if(-not(Test-AwxRecommendationMetricSet -Row $row -Role $Role)){throw 'benchmark-contract-invalid'}
        } else {
            if($null -ne $row.balancedScore){[void](ConvertTo-AwxScoreMicrounits -Score $row.balancedScore)}
            if(($null -ne $row.$qualityName -and -not(Test-AwxFiniteNumberRange $row.$qualityName 0.0 1.0)) -or
               ($null -ne $row.warmRoleP95Ms -and (-not(Test-AwxFiniteNumberRange $row.warmRoleP95Ms 0.0) -or [double]$row.warmRoleP95Ms -le 0.0))){throw 'benchmark-contract-invalid'}
        }
    }
    if(-not $baseline[0].hardGatePassed -or -not(Test-AwxRecommendationMetricSet -Row $baseline[0] -Role $Role)){
        return New-AwxBaselineEvidenceIncompleteRecommendation -Role $Role
    }
    $baselineScoreMicros=ConvertTo-AwxScoreMicrounits -Score $baseline[0].balancedScore
    $eligible = foreach ($row in @($rows | Where-Object { $_.classification -cne 'baseline' -and $_.hardGatePassed })) {
        $scoreMicros=ConvertTo-AwxScoreMicrounits -Score $row.balancedScore;$deltaMicros=$scoreMicros-$baselineScoreMicros
        $passes = if ($Role -ceq 'fast') {
            $scoreMicros -ge $baselineScoreMicros -and
            [double]$row.rewriteAndExtractionQuality -ge [double]$baseline[0].rewriteAndExtractionQuality -and
            [double]$row.warmRoleP95Ms -le [double]$baseline[0].warmRoleP95Ms
        } else {
            $deltaMicros -ge 5000000 -and
            [double]$row.koreanRagQuality -ge [double]$baseline[0].koreanRagQuality -and
            [double]$row.warmRoleP95Ms -le (1.5 * [double]$baseline[0].warmRoleP95Ms)
        }
        if ($passes -and $deltaMicros -gt 0) {[pscustomobject]@{row=$row;scoreMicros=$scoreMicros;deltaMicros=$deltaMicros}}
    }
    $ordered = @($eligible | Sort-Object @{Expression={$_.scoreMicros};Descending=$true},@{Expression={$_.row.warmRoleP95Ms};Descending=$false},@{Expression={$_.row.modelTag};Descending=$false})
    if ($ordered.Count -eq 0) { return [pscustomobject][ordered]@{role=$Role;status='keep-baseline';modelTag=$baseline[0].modelTag;scoreDelta=0.0;reasonCode='no-eligible-candidate'} }
    if ($ordered.Count -gt 1 -and [int64]$ordered[0].scoreMicros -eq [int64]$ordered[1].scoreMicros -and
        [double]$ordered[0].row.warmRoleP95Ms -eq [double]$ordered[1].row.warmRoleP95Ms) {
        return [pscustomobject][ordered]@{role=$Role;status='keep-baseline';modelTag=$baseline[0].modelTag;scoreDelta=0.0;reasonCode='candidate-tie'}
    }
    return [pscustomobject][ordered]@{role=$Role;status='recommend-candidate';modelTag=$ordered[0].row.modelTag;scoreDelta=[double]([int64]$ordered[0].deltaMicros/1000000.0);reasonCode='promotion-approval-required'}
}

function Get-AwxFamilyAModelIdentityMatrix {
    return @(
        [pscustomobject][ordered]@{role='fast';classification='baseline';modelTag='qwen3:8b';digest='500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'},
        [pscustomobject][ordered]@{role='fast';classification='primary';modelTag='qwen3.5:9b';digest='6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7'},
        [pscustomobject][ordered]@{role='fast';classification='challenger';modelTag='gemma4:12b';digest='4eb23ef187e2c5462566d6a1d3bbbc2f1346d0b4327cbb66d58fffbcc9b2b05c'},
        [pscustomobject][ordered]@{role='main';classification='baseline';modelTag='gemma4:26b';digest='5571076f3d70050487b26b341705799e0ab29b808164f90d20d4cf84f699d251'},
        [pscustomobject][ordered]@{role='main';classification='primary';modelTag='qwen3.6:27b';digest='a50eda8ed977ab48a12431878896b27ffd5cef552c17af3317d9623b939a7f1e'},
        [pscustomobject][ordered]@{role='main';classification='challenger';modelTag='gemma4:31b';digest='6316f0629137b426c9d9b853ffc4c8209589f30ee39aebede6285096c0ff47e7'}
    )
}

function Get-AwxReasonPolicy {
    param([Parameter(Mandatory)][string]$Lane)
    switch($Lane){
        'SUPPORT_CONTRACT'{@('pass','benchmark-contract-invalid','const-forbidden','single-valued-enum','ontology-narrowed','answer-cardinality-leaked','request-body-oracle-contamination','candidate-digest-changed','lineage-missing')}
        'SUPPORT_SCENARIO'{@('pass','baseline-evidence-incomplete','warm-sample-insufficient','response-json-invalid','response-schema-invalid','response-semantic-mismatch','response-truncated','lineage-missing','cpu-offload-detected','insufficient-vram','portfolio-timeout','model-block-timeout','model-unload-unproven','gpu-lane-evidence-incomplete','gpu-release-unproven','ps-evidence-incomplete','native-probe-timeout','concurrent-model-block-detected')}
        'FALSIFY'{@('pass','benchmark-contract-invalid')}
        'COMMAND'{@('pass','command-evidence-incomplete','candidate-digest-changed','warm-sample-insufficient','model-unload-unproven','report-privacy-invalid','secret-scan-failed','role-binding-mutated')}
        default{throw 'benchmark-contract-invalid'}
    }
}

function Get-AwxHardGateReasonPolicy {
    @('pass','benchmark-contract-invalid','candidate-digest-changed','warm-sample-insufficient','response-schema-invalid','response-truncated','lineage-missing','gpu-lane-evidence-incomplete','cpu-offload-detected','insufficient-vram')
}

function Assert-AwxHardGateReasonEnvelope {
    param([bool]$Passed,$ReasonCodes)
    if($ReasonCodes -isnot [System.Array]){throw 'benchmark-contract-invalid'}
    $codes=@($ReasonCodes);$policy=@(Get-AwxHardGateReasonPolicy);$ordered=@($policy|Where-Object{$codes -ccontains $_})
    if($codes.Count -eq 0 -or @($codes|Select-Object -Unique).Count -ne $codes.Count -or @($codes|Where-Object{$_ -isnot [string] -or $_ -cnotin $policy}).Count -gt 0 -or -not(Test-AwxExactSequence $codes $ordered) -or ($Passed -and -not(Test-AwxExactSequence $codes @('pass'))) -or (-not $Passed -and $codes -ccontains 'pass')){throw 'benchmark-contract-invalid'}
}

function Assert-AwxReasonEnvelope {
    param([string]$Lane,[bool]$Complete,[bool]$Passed,$ReasonCodes)
    if($ReasonCodes -isnot [System.Array]){throw 'benchmark-contract-invalid'}
    $codes=@($ReasonCodes);$policy=@(Get-AwxReasonPolicy $Lane)
    if($codes.Count -eq 0 -or @($codes|Select-Object -Unique).Count -ne $codes.Count -or @($codes|Where-Object{$_ -isnot [string] -or $_ -cnotin $policy -or $_ -match '(?i)(?:sk|pk)-[A-Za-z0-9_-]{8,}|authorization|cookie|credential'}).Count -gt 0){throw 'benchmark-contract-invalid'}
    $ordered=@($policy|Where-Object{$codes -ccontains $_});if(-not(Test-AwxExactSequence $codes $ordered)){throw 'benchmark-contract-invalid'}
    if($Passed -and -not(Test-AwxExactSequence $codes @('pass'))){throw 'benchmark-contract-invalid'}
    if(-not $Passed -and $codes -ccontains 'pass'){throw 'benchmark-contract-invalid'}
    if(-not $Complete -and $Passed){throw 'benchmark-contract-invalid'}
}

function Assert-AwxResultSample {
    param($Sample,[string[]]$AllowedCaseIds,[bool]$Warm)
    $names=@('caseId','requestSha256','responseSha256','latencyMs','transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed','reasonCode');if($Warm){$names=@('caseId','repetition','requestSha256','responseSha256','latencyMs','transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed','reasonCode')}
    Assert-AwxExactPropertySet $Sample $names
    if($Sample.caseId -cnotin $AllowedCaseIds -or -not(Test-AwxFiniteNumberRange $Sample.latencyMs 0.0)){throw 'benchmark-contract-invalid'}
    if($Warm -and -not(Test-AwxStrictIntegerRange $Sample.repetition 1 7)){throw 'benchmark-contract-invalid'}
    Assert-AwxHash64 $Sample.requestSha256;Assert-AwxHash64 $Sample.responseSha256
    foreach($name in @('transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed')){if(-not(Test-AwxStrictBoolean $Sample.$name)){throw 'benchmark-contract-invalid'}}
    if($Sample.reasonCode -isnot [string] -or $Sample.reasonCode -cnotin @('pass','response-json-invalid','response-schema-invalid','response-semantic-mismatch','response-truncated','lineage-missing','model-block-timeout','portfolio-timeout')){throw 'benchmark-contract-invalid'}
}

function New-AwxBlockEvidence {
    param([Parameter(Mandatory)]$BlockFacts)
    Assert-AwxExactPropertySet $BlockFacts @('modelTag','role','classification','cold','warm','lane','cleanup')
    $matrix=@(Get-AwxFamilyAModelIdentityMatrix);$match=@($matrix|Where-Object modelTag -CEQ $BlockFacts.modelTag)
    if($match.Count -ne 1 -or $BlockFacts.role -cne $match[0].role -or $BlockFacts.classification -cne $match[0].classification){throw 'benchmark-contract-invalid'}
    $caseIds=$(if($BlockFacts.role -ceq 'fast'){@('fast-rewrite-ko','fast-json-extract','fast-instruction')}else{@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
    Assert-AwxResultSample $BlockFacts.cold $caseIds $false;if($BlockFacts.cold.caseId -cne $caseIds[0]){throw 'benchmark-contract-invalid'}
    if($BlockFacts.warm -isnot [System.Array] -or @($BlockFacts.warm).Count -ne 21){throw 'benchmark-contract-invalid'}
    foreach($sample in @($BlockFacts.warm)){Assert-AwxResultSample $sample $caseIds $true}
    foreach($id in $caseIds){$rows=@($BlockFacts.warm|Where-Object caseId -CEQ $id);if($rows.Count -ne 7 -or -not(Test-AwxExactSequence @($rows.repetition|Sort-Object) @(1..7))){throw 'benchmark-contract-invalid'}}
    Assert-AwxExactPropertySet $BlockFacts.lane @('gpuResidentRatio','loadedFreeMiB','lanePassed');if(-not(Test-AwxFiniteNumberRange $BlockFacts.lane.gpuResidentRatio 0.0 1.0) -or -not(Test-AwxFiniteNumberRange $BlockFacts.lane.loadedFreeMiB 0.0) -or -not(Test-AwxStrictBoolean $BlockFacts.lane.lanePassed)){throw 'benchmark-contract-invalid'}
    Assert-AwxExactPropertySet $BlockFacts.cleanup @('psEmpty','gpuReleased','endpointRestarted');foreach($name in @('psEmpty','gpuReleased','endpointRestarted')){if(-not(Test-AwxStrictBoolean $BlockFacts.cleanup.$name)){throw 'benchmark-contract-invalid'}}
    $preimage=[ordered]@{hashContract='awx.desktop-ollama-model-block-evidence.v2';blockFacts=$BlockFacts}
    return [pscustomobject][ordered]@{blockFacts=$BlockFacts;blockEvidenceSha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $preimage)}
}

function New-AwxRunBinding {
    param([string]$BenchmarkId,[string]$CorpusSha256,[string]$OracleSha256,[string]$OntologySha256,[string]$SchemaSha256,[string]$OptionsSha256,[object[]]$ModelBlocks)
    if($BenchmarkId -cne 'awx.desktop-ollama-benchmark.v2'){throw 'benchmark-contract-invalid'}
    foreach($hash in @($CorpusSha256,$OracleSha256,$OntologySha256,$SchemaSha256,$OptionsSha256)){Assert-AwxHash64 $hash}
    $matrix=@(Get-AwxFamilyAModelIdentityMatrix)
    if($ModelBlocks.Count -ne 6 -or -not(Test-AwxExactSequence @($ModelBlocks.modelTag) @($matrix.modelTag))){throw 'benchmark-contract-invalid'}
    for($i=0;$i -lt 6;$i++){Assert-AwxExactPropertySet $ModelBlocks[$i] @('modelTag','digest','blockEvidenceSha256');if($ModelBlocks[$i].digest -cne $matrix[$i].digest){throw 'benchmark-contract-invalid'};Assert-AwxHash64 $ModelBlocks[$i].blockEvidenceSha256}
    $binding=[pscustomobject][ordered]@{benchmarkId=$BenchmarkId;corpusSha256=$CorpusSha256;oracleSha256=$OracleSha256;ontologySha256=$OntologySha256;schemaSha256=$SchemaSha256;optionsSha256=$OptionsSha256;modelBlocks=@($ModelBlocks)}
    $hash=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-run-binding.v2';runBinding=$binding}))
    return [pscustomobject][ordered]@{runBinding=$binding;runBindingSha256=$hash}
}

function Assert-AwxRunBindingEnvelope {
    param($Envelope)
    Assert-AwxExactPropertySet $Envelope @('runBinding','runBindingSha256');Assert-AwxHash64 $Envelope.runBindingSha256
    Assert-AwxExactPropertySet $Envelope.runBinding @('benchmarkId','corpusSha256','oracleSha256','ontologySha256','schemaSha256','optionsSha256','modelBlocks')
    $replayed=New-AwxRunBinding -BenchmarkId $Envelope.runBinding.benchmarkId -CorpusSha256 $Envelope.runBinding.corpusSha256 -OracleSha256 $Envelope.runBinding.oracleSha256 -OntologySha256 $Envelope.runBinding.ontologySha256 -SchemaSha256 $Envelope.runBinding.schemaSha256 -OptionsSha256 $Envelope.runBinding.optionsSha256 -ModelBlocks @($Envelope.runBinding.modelBlocks)
    if($replayed.runBindingSha256 -cne $Envelope.runBindingSha256){throw 'benchmark-contract-invalid'}
}

function Assert-AwxFixedChecks {
    param($Checks)
    $matrix=@(Get-AwxFamilyAModelIdentityMatrix);if(@($Checks).Count -ne 6 -or -not(Test-AwxExactSequence @($Checks.modelTag) @($matrix.modelTag))){throw 'benchmark-contract-invalid'}
    foreach($check in @($Checks)){Assert-AwxExactPropertySet $check @('modelTag','passed');if(-not(Test-AwxStrictBoolean $check.passed)){throw 'benchmark-contract-invalid'}}
}

function Get-AwxScenarioSelectorRows {
    param([Parameter(Mandatory)][object[]]$Models,[Parameter(Mandatory)][ValidateSet('fast','main')][string]$Role)
    $fixed=@(Get-AwxFamilyAModelIdentityMatrix|Where-Object role -CEQ $Role);$rows=@($Models|Where-Object role -CEQ $Role)
    if($rows.Count -ne $fixed.Count -or -not(Test-AwxExactSequence @($rows.modelTag) @($fixed.modelTag))){throw 'benchmark-contract-invalid'}
    return @($rows|ForEach-Object {
        $row=$_;$rewriteQuality=$null;$koreanQuality=$null
        if($Role -ceq 'fast'){
            $rewrite=@($row.semanticRates|Where-Object caseId -CEQ 'fast-rewrite-ko');$extract=@($row.semanticRates|Where-Object caseId -CEQ 'fast-json-extract')
            if($rewrite.Count -ne 1 -or $extract.Count -ne 1){throw 'benchmark-contract-invalid'}
            $rewriteQuality=[double](($rewrite[0].rate+$extract[0].rate)/2.0)
        } else {
            $grounded=@($row.semanticRates|Where-Object caseId -CEQ 'main-grounded-ko')
            if($grounded.Count -ne 1){throw 'benchmark-contract-invalid'}
            $koreanQuality=[double]$grounded[0].rate
        }
        [pscustomobject][ordered]@{classification=$row.classification;modelTag=$row.modelTag;hardGatePassed=$row.hardGatePassed;balancedScore=$row.balancedScore;rewriteAndExtractionQuality=$rewriteQuality;koreanRagQuality=$koreanQuality;warmRoleP95Ms=$row.warmRoleP95Ms}
    })
}

function Assert-AwxRecommendationMatch {
    param([Parameter(Mandatory)]$Actual,[Parameter(Mandatory)]$Expected)
    $names=@('role','status','modelTag','scoreDelta','reasonCode');Assert-AwxExactPropertySet $Actual $names;Assert-AwxExactPropertySet $Expected $names
    $actualProjection=[ordered]@{};$expectedProjection=[ordered]@{}
    foreach($name in $names){$actualProjection[$name]=$Actual.$name;$expectedProjection[$name]=$Expected.$name}
    if((ConvertTo-AwxCanonicalJson $actualProjection) -cne (ConvertTo-AwxCanonicalJson $expectedProjection)){throw 'benchmark-contract-invalid'}
}

function Assert-AwxLaneEvidence {
    param([string]$Name,$Evidence)
    $matrix=@(Get-AwxFamilyAModelIdentityMatrix)
    if($Name -ceq 'SUPPORT_CONTRACT'){
        Assert-AwxExactPropertySet $Evidence @('contractValidated','digestChecks','hashCompletenessChecks','lineageChecks');if(-not(Test-AwxStrictBoolean $Evidence.contractValidated)){throw 'benchmark-contract-invalid'}
        Assert-AwxFixedChecks $Evidence.digestChecks;Assert-AwxFixedChecks $Evidence.hashCompletenessChecks;Assert-AwxFixedChecks $Evidence.lineageChecks;return
    }
    if($Name -ceq 'FALSIFY'){
        $names=@('constRejected','shapeOnlyRejected','extraValueRejected','truncationRejected','timeoutRejected','missingHashRejected','orderRejected','oracleIsolated','p95Correct');Assert-AwxExactPropertySet $Evidence $names
        foreach($name in $names){if(-not(Test-AwxStrictBoolean $Evidence.$name)){throw 'benchmark-contract-invalid'}};return
    }
    Assert-AwxExactPropertySet $Evidence @('models','recommendations')
    if(@($Evidence.models).Count -ne 6 -or -not(Test-AwxExactSequence @($Evidence.models.modelTag) @($matrix.modelTag))){throw 'benchmark-contract-invalid'}
    for($i=0;$i -lt 6;$i++){
        $row=$Evidence.models[$i];Assert-AwxExactPropertySet $row @('role','classification','modelTag','hardGatePassed','hardGateReasonCodes','warmRoleP95Ms','loadedFreeMiB','balancedScore','semanticRates')
        if($row.role -cne $matrix[$i].role -or $row.classification -cne $matrix[$i].classification -or $row.modelTag -cne $matrix[$i].modelTag -or -not(Test-AwxStrictBoolean $row.hardGatePassed) -or -not(Test-AwxFiniteNumberRange $row.warmRoleP95Ms 0.0) -or -not(Test-AwxFiniteNumberRange $row.loadedFreeMiB 0.0) -or ($null -ne $row.balancedScore -and -not(Test-AwxFiniteNumberRange $row.balancedScore 0.0 100.0))){throw 'benchmark-contract-invalid'}
        Assert-AwxHardGateReasonEnvelope $row.hardGatePassed $row.hardGateReasonCodes
        $ids=$(if($row.role -ceq 'fast'){@('fast-rewrite-ko','fast-json-extract','fast-instruction')}else{@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
        if(@($row.semanticRates).Count -ne 3 -or -not(Test-AwxExactSequence @($row.semanticRates.caseId) $ids)){throw 'benchmark-contract-invalid'}
        foreach($rate in @($row.semanticRates)){Assert-AwxExactPropertySet $rate @('caseId','rate');if(-not(Test-AwxFiniteNumberRange $rate.rate 0.0 1.0)){throw 'benchmark-contract-invalid'}}
    }
    if(@($Evidence.recommendations).Count -ne 2 -or -not(Test-AwxExactSequence @($Evidence.recommendations.role) @('fast','main'))){throw 'benchmark-contract-invalid'}
    foreach($rec in @($Evidence.recommendations)){Assert-AwxExactPropertySet $rec @('role','status','modelTag','scoreDelta','reasonCode');if($rec.status -cnotin @('keep-baseline','recommend-candidate','HOLD') -or ($null -ne $rec.modelTag -and $rec.modelTag -cnotin @($matrix.modelTag)) -or ($null -ne $rec.scoreDelta -and -not(Test-AwxFiniteNumberRange $rec.scoreDelta ([double]::NegativeInfinity))) -or $rec.reasonCode -cnotin @('baseline-evidence-incomplete','no-eligible-candidate','candidate-tie','promotion-approval-required')){throw 'benchmark-contract-invalid'}}
    foreach($role in @('fast','main')){
        $selectorRows=@(Get-AwxScenarioSelectorRows -Models @($Evidence.models) -Role $role);$derived=Select-AwxRoleRecommendation -Role $role -ModelRows $selectorRows
        $actual=@($Evidence.recommendations|Where-Object role -CEQ $role);if($actual.Count -ne 1){throw 'benchmark-contract-invalid'}
        Assert-AwxRecommendationMatch -Actual $actual[0] -Expected $derived
    }
}

function Get-AwxLaneStatus {
    param([string]$Name,$Evidence)
    Assert-AwxLaneEvidence $Name $Evidence;$reasons=[Collections.Generic.List[string]]::new()
    if($Name -ceq 'SUPPORT_CONTRACT'){
        if(-not $Evidence.contractValidated){$reasons.Add('benchmark-contract-invalid')};if(@($Evidence.digestChecks|Where-Object{-not $_.passed}).Count){$reasons.Add('candidate-digest-changed')};if(@($Evidence.hashCompletenessChecks|Where-Object{-not $_.passed}).Count -or @($Evidence.lineageChecks|Where-Object{-not $_.passed}).Count){$reasons.Add('lineage-missing')}
    }elseif($Name -ceq 'FALSIFY'){if(@($Evidence.PSObject.Properties.Value|Where-Object{-not $_}).Count){$reasons.Add('benchmark-contract-invalid')}}else{
        foreach($row in @($Evidence.models|Where-Object{-not $_.hardGatePassed})){foreach($reason in @($row.hardGateReasonCodes)){if($reason -cin @('benchmark-contract-invalid','candidate-digest-changed')){$reasons.Add('baseline-evidence-incomplete')}elseif($reason -cin (Get-AwxReasonPolicy 'SUPPORT_SCENARIO')){$reasons.Add($reason)}else{throw 'benchmark-contract-invalid'}}}
        if(@($Evidence.recommendations|Where-Object status -CEQ 'HOLD').Count){$reasons.Add('baseline-evidence-incomplete')}
    }
    $policy=@(Get-AwxReasonPolicy $Name);$ordered=@($policy|Where-Object{$reasons -ccontains $_});$passed=$ordered.Count -eq 0
    return [pscustomobject][ordered]@{complete=$true;passed=$passed;reasonCodes=$(if($passed){@('pass')}else{$ordered})}
}

function New-AwxEvidencePackets {
    param([Parameter(Mandatory)]$RunBinding,[Parameter(Mandatory)]$ContractEvidence,[Parameter(Mandatory)]$ScenarioEvidence,[Parameter(Mandatory)]$FalsifyEvidence)
    Assert-AwxRunBindingEnvelope $RunBinding;$definitions=[ordered]@{SUPPORT_CONTRACT=$ContractEvidence;SUPPORT_SCENARIO=$ScenarioEvidence;FALSIFY=$FalsifyEvidence}
    foreach($name in $definitions.Keys){
        $evidence=$definitions[$name];$status=Get-AwxLaneStatus $name $evidence
        $evidenceHash=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-lane-evidence.v2';runBindingSha256=$RunBinding.runBindingSha256;name=$name;evidence=$evidence}))
        $packetHash=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-packet.v2';runBindingSha256=$RunBinding.runBindingSha256;name=$name;complete=$status.complete;passed=$status.passed;reasonCodes=@($status.reasonCodes);evidenceSha256=$evidenceHash}))
        [pscustomobject][ordered]@{name=$name;complete=$status.complete;passed=$status.passed;reasonCodes=@($status.reasonCodes);runBindingSha256=$RunBinding.runBindingSha256;evidence=$evidence;evidenceSha256=$evidenceHash;packetSha256=$packetHash}
    }
}

function New-AwxCommandEvidence {
    param([Parameter(Mandatory)]$RunBinding,[Parameter(Mandatory)]$Evidence)
    Assert-AwxRunBindingEnvelope $RunBinding;Assert-AwxExactPropertySet $Evidence @('digestChecks','completedBlockChecks','finalEmptyPsChecks','privacyProjectionChecked','privacyProjectionPassed','bindingGuardChecked','bindingGuardUnchanged')
    Assert-AwxFixedChecks $Evidence.digestChecks;Assert-AwxFixedChecks $Evidence.completedBlockChecks
    if(@($Evidence.finalEmptyPsChecks).Count -ne 2 -or -not(Test-AwxExactSequence @($Evidence.finalEmptyPsChecks.endpointLabel) @('fast-11435','primary-11434'))){throw 'benchmark-contract-invalid'}
    foreach($check in @($Evidence.finalEmptyPsChecks)){Assert-AwxExactPropertySet $check @('endpointLabel','passed');if(-not(Test-AwxStrictBoolean $check.passed)){throw 'benchmark-contract-invalid'}}
    foreach($name in @('privacyProjectionChecked','privacyProjectionPassed','bindingGuardChecked','bindingGuardUnchanged')){if(-not(Test-AwxStrictBoolean $Evidence.$name)){throw 'benchmark-contract-invalid'}}
    $reasons=[Collections.Generic.List[string]]::new();$complete=[bool]($Evidence.privacyProjectionChecked -and $Evidence.bindingGuardChecked)
    if(-not $complete){$reasons.Add('command-evidence-incomplete')};if(@($Evidence.digestChecks|Where-Object{-not $_.passed}).Count){$reasons.Add('candidate-digest-changed')};if(@($Evidence.completedBlockChecks|Where-Object{-not $_.passed}).Count){$reasons.Add('warm-sample-insufficient')};if(@($Evidence.finalEmptyPsChecks|Where-Object{-not $_.passed}).Count){$reasons.Add('model-unload-unproven')};if($Evidence.privacyProjectionChecked -and -not $Evidence.privacyProjectionPassed){$reasons.Add('report-privacy-invalid')};if($Evidence.bindingGuardChecked -and -not $Evidence.bindingGuardUnchanged){$reasons.Add('role-binding-mutated')}
    $policy=@(Get-AwxReasonPolicy 'COMMAND');$ordered=@($policy|Where-Object{$reasons -ccontains $_});$passed=$complete -and $ordered.Count -eq 0;$codes=$(if($passed){@('pass')}else{$ordered})
    $hash=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-command-evidence.v2';runBindingSha256=$RunBinding.runBindingSha256;complete=$complete;passed=$passed;reasonCodes=@($codes);evidence=$Evidence}))
    return [pscustomobject][ordered]@{complete=$complete;passed=$passed;reasonCodes=@($codes);runBinding=$RunBinding.runBinding;runBindingSha256=$RunBinding.runBindingSha256;evidence=$Evidence;commandEvidenceSha256=$hash}
}

function Assert-AwxPacketEnvelope {
    param($Packet)
    Assert-AwxExactPropertySet $Packet @('name','complete','passed','reasonCodes','runBindingSha256','evidence','evidenceSha256','packetSha256');if($Packet.name -cnotin @('SUPPORT_CONTRACT','SUPPORT_SCENARIO','FALSIFY') -or -not(Test-AwxStrictBoolean $Packet.complete) -or -not(Test-AwxStrictBoolean $Packet.passed)){throw 'benchmark-contract-invalid'}
    Assert-AwxHash64 $Packet.runBindingSha256;Assert-AwxHash64 $Packet.evidenceSha256;Assert-AwxHash64 $Packet.packetSha256
    $status=Get-AwxLaneStatus $Packet.name $Packet.evidence;if($Packet.complete -ne $status.complete -or $Packet.passed -ne $status.passed -or -not(Test-AwxExactSequence @($Packet.reasonCodes) @($status.reasonCodes))){throw 'benchmark-contract-invalid'};Assert-AwxReasonEnvelope $Packet.name $Packet.complete $Packet.passed $Packet.reasonCodes
    $eh=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-lane-evidence.v2';runBindingSha256=$Packet.runBindingSha256;name=$Packet.name;evidence=$Packet.evidence}));if($eh -cne $Packet.evidenceSha256){throw 'benchmark-contract-invalid'}
    $ph=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson ([ordered]@{hashContract='awx.desktop-ollama-packet.v2';runBindingSha256=$Packet.runBindingSha256;name=$Packet.name;complete=$Packet.complete;passed=$Packet.passed;reasonCodes=@($Packet.reasonCodes);evidenceSha256=$Packet.evidenceSha256}));if($ph -cne $Packet.packetSha256){throw 'benchmark-contract-invalid'}
}

function Assert-AwxCommandEnvelope {
    param($Command)
    Assert-AwxExactPropertySet $Command @('complete','passed','reasonCodes','runBinding','runBindingSha256','evidence','commandEvidenceSha256');if(-not(Test-AwxStrictBoolean $Command.complete) -or -not(Test-AwxStrictBoolean $Command.passed)){throw 'benchmark-contract-invalid'};Assert-AwxHash64 $Command.commandEvidenceSha256
    $binding=[pscustomobject][ordered]@{runBinding=$Command.runBinding;runBindingSha256=$Command.runBindingSha256};Assert-AwxRunBindingEnvelope $binding
    $replayed=New-AwxCommandEvidence -RunBinding $binding -Evidence $Command.evidence
    if($replayed.complete -ne $Command.complete -or $replayed.passed -ne $Command.passed -or -not(Test-AwxExactSequence @($replayed.reasonCodes) @($Command.reasonCodes)) -or $replayed.commandEvidenceSha256 -cne $Command.commandEvidenceSha256){throw 'benchmark-contract-invalid'}
    Assert-AwxReasonEnvelope 'COMMAND' $Command.complete $Command.passed $Command.reasonCodes
}

function Get-AwxNeutralVerdict {
    param([Parameter(Mandatory)][object[]]$Packets,[Parameter(Mandatory)]$CommandEvidence)
    try{Assert-AwxCommandEnvelope $CommandEvidence}catch{return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='command-evidence-incomplete'}}
    $required=@('SUPPORT_CONTRACT','SUPPORT_SCENARIO','FALSIFY');if($Packets.Count -ne 3 -or @($required|Where-Object{@($Packets|Where-Object name -CEQ $_).Count -ne 1}).Count){return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='packet-set-invalid'}}
    foreach($packet in $Packets){try{Assert-AwxPacketEnvelope $packet}catch{return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='packet-incomplete'}};if($packet.runBindingSha256 -cne $CommandEvidence.runBindingSha256){return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='packet-set-invalid'}}}
    if(-not $CommandEvidence.complete -or -not $CommandEvidence.passed){return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='command-evidence-incomplete'}}
    $byName=@{};foreach($packet in $Packets){$byName[$packet.name]=$packet};if(@($Packets|Where-Object{-not $_.complete}).Count){return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='packet-incomplete'}}
    if(-not $byName.SUPPORT_CONTRACT.passed -or -not $byName.FALSIFY.passed){return [pscustomobject][ordered]@{verdict='REJECT';reasonCode='contract-or-counterexample-failed'}}
    if(-not $byName.SUPPORT_SCENARIO.passed){return [pscustomobject][ordered]@{verdict='HOLD';reasonCode='scenario-evidence-incomplete'}}
    return [pscustomobject][ordered]@{verdict='APPLY';reasonCode='all-packets-pass'}
}

function Resolve-AwxNeutralOrder {
    param($Forward,$Reverse,$RunBindingSha256,$CommandEvidenceSha256)
    $stable=$Forward.verdict -ceq $Reverse.verdict -and $Forward.reasonCode -ceq $Reverse.reasonCode
    return [pscustomobject][ordered]@{forward=$Forward.verdict;forwardReasonCode=$Forward.reasonCode;reverse=$Reverse.verdict;reverseReasonCode=$Reverse.reasonCode;orderStable=$stable;finalVerdict=$(if($stable){$Forward.verdict}else{'HOLD'});finalReasonCode=$(if($stable){$Forward.reasonCode}else{'order-unstable'});runBindingSha256=$RunBindingSha256;commandEvidenceSha256=$CommandEvidenceSha256}
}

function Test-AwxInternalNeutralPair {
    param($Verdict,$ReasonCode)
    if($Verdict -ceq 'APPLY'){return $ReasonCode -ceq 'all-packets-pass'}
    if($Verdict -ceq 'REJECT'){return $ReasonCode -ceq 'contract-or-counterexample-failed'}
    return $Verdict -ceq 'HOLD' -and $ReasonCode -cin @('packet-set-invalid','packet-incomplete','command-evidence-incomplete','scenario-evidence-incomplete')
}

function Assert-AwxNeutralEnvelope {
    param($Neutral)
    Assert-AwxExactPropertySet $Neutral @('forward','forwardReasonCode','reverse','reverseReasonCode','orderStable','finalVerdict','finalReasonCode','runBindingSha256','commandEvidenceSha256')
    if(-not(Test-AwxStrictBoolean $Neutral.orderStable) -or -not(Test-AwxInternalNeutralPair $Neutral.forward $Neutral.forwardReasonCode) -or -not(Test-AwxInternalNeutralPair $Neutral.reverse $Neutral.reverseReasonCode)){throw 'benchmark-contract-invalid'}
    if($Neutral.orderStable){
        if($Neutral.forward -cne $Neutral.reverse -or $Neutral.forwardReasonCode -cne $Neutral.reverseReasonCode -or $Neutral.finalVerdict -cne $Neutral.forward -or $Neutral.finalReasonCode -cne $Neutral.forwardReasonCode){throw 'benchmark-contract-invalid'}
    } elseif($Neutral.finalVerdict -cne 'HOLD' -or $Neutral.finalReasonCode -cne 'order-unstable' -or ($Neutral.forward -ceq $Neutral.reverse -and $Neutral.forwardReasonCode -ceq $Neutral.reverseReasonCode)){throw 'benchmark-contract-invalid'}
    Assert-AwxHash64 $Neutral.runBindingSha256;Assert-AwxHash64 $Neutral.commandEvidenceSha256
}

function Get-AwxOptionalCommandHash {
    param($CommandEvidence,[Parameter(Mandatory)][string]$Name)
    if($null -eq $CommandEvidence){return $null}
    try{
        $property=$CommandEvidence.PSObject.Properties[$Name]
        if($null -ne $property -and $property.Value -is [string] -and $property.Value -cmatch '^[a-f0-9]{64}$'){return [string]$property.Value}
    }catch{}
    return $null
}

function Get-AwxOrderStableNeutralVerdict {
    param([Parameter(Mandatory)][object[]]$Packets,$CommandEvidence)
    $runBindingSha256=Get-AwxOptionalCommandHash -CommandEvidence $CommandEvidence -Name 'runBindingSha256';$commandEvidenceSha256=Get-AwxOptionalCommandHash -CommandEvidence $CommandEvidence -Name 'commandEvidenceSha256'
    try{$forward=Get-AwxNeutralVerdict -Packets @($Packets) -CommandEvidence $CommandEvidence}catch{$forward=[pscustomobject][ordered]@{verdict='HOLD';reasonCode='command-evidence-incomplete'}}
    [object[]]$reversePackets=[object[]]$Packets.Clone();[array]::Reverse($reversePackets)
    try{$reverse=Get-AwxNeutralVerdict -Packets $reversePackets -CommandEvidence $CommandEvidence}catch{$reverse=[pscustomobject][ordered]@{verdict='HOLD';reasonCode='command-evidence-incomplete'}}
    return Resolve-AwxNeutralOrder -Forward $forward -Reverse $reverse -RunBindingSha256 $runBindingSha256 -CommandEvidenceSha256 $commandEvidenceSha256
}

function ConvertTo-AwxPublicPacket {param($Packet);Assert-AwxPacketEnvelope $Packet;[pscustomobject][ordered]@{name=$Packet.name;complete=$Packet.complete;passed=$Packet.passed;reasonCodes=@($Packet.reasonCodes);runBindingSha256=$Packet.runBindingSha256;evidenceSha256=$Packet.evidenceSha256;packetSha256=$Packet.packetSha256}}
function ConvertTo-AwxPublicNeutral {param($Neutral);Assert-AwxNeutralEnvelope $Neutral;[pscustomobject][ordered]@{forward=$Neutral.forward;forwardReasonCode=$Neutral.forwardReasonCode;reverse=$Neutral.reverse;reverseReasonCode=$Neutral.reverseReasonCode;orderStable=$Neutral.orderStable;finalVerdict=$Neutral.finalVerdict;finalReasonCode=$Neutral.finalReasonCode;runBindingSha256=$Neutral.runBindingSha256;commandEvidenceSha256=$Neutral.commandEvidenceSha256}}
```

All statuses, reasons, recommendations, and hashes above are module-derived. `ConvertTo-AwxScoreMicrounits` rejects noncanonical score precision and makes eligibility, the main five-point gate, score ordering/ties, and emitted positive delta exact integer operations. Scenario validation rebuilds the role-specific quality inputs from the fixed model rows, reruns the selector, and exact-compares all five recommendation fields. NEUTRAL receives the internal packets and internal CommandEvidence, recomputes the run/lane/packet/command hashes, enforces one shared current-run binding, and validates exact schemas and lane reason policies before semantic adjudication. The order-stable wrapper safe-reads optional command hashes through `PSObject.Properties`, retains valid hashes, and returns null hashes with `HOLD/command-evidence-incomplete` instead of throwing for malformed command input. Both public projectors validate the complete internal envelope before copying allowlisted fields. `Get-AwxOrderStableNeutralVerdict` compares both verdict and reason; either disagreement yields `HOLD/order-unstable`. These unkeyed hashes provide canonical integrity and stale-run detection only, not authentication, provenance, or execution proof.

- [ ] **Step 6: Run GREEN and deterministic replay**

Run the focused Pester file twice in fresh PowerShell processes. Expected: the same test count and zero failures both times; the canonical packet hashes and verdicts match exactly.

- [ ] **Step 7: Record the conditional checkpoint**

Suggested commit, only with Git authorization:

```powershell
git add -- scripts/modules/DesktopOllamaModelTools.psm1 scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1
git commit -m "feat: score and adjudicate benchmark v2"
```

---

### Task 5: Strict Loopback Adapters, Dedicated Lease, Atomic Report Write, and Serial Model Blocks

**Files:**
- Modify: `scripts/modules/DesktopOllamaModelTools.psm1`
- Modify: `scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1`

**Interfaces:**
- Consumes: allowlisted endpoint, exact tag/digest/model/GPU-lane contract, corpus/oracle, and injectable synchronous adapters.
- Produces: `Invoke-AwxOllamaHttp`, `Get-AwxOllamaTags`, `Get-AwxOllamaPs`, `Get-AwxGpuSnapshot`, `Get-AwxEndpointProcessIds`, `Get-AwxGpuComputeRows`, `Test-AwxGpuLane`, `Test-AwxGpuRelease`, `Stop-AwxOllamaModel`, `Enter-AwxBenchmarkLease`, `Write-AwxJsonAtomic`, `Invoke-AwxModelBlock`, and `Invoke-AwxBenchmarkPortfolio`. Each successful model block also returns strict bounded `blockFacts` and its module-owned `blockEvidenceSha256`; Task 5 never constructs lane packets or public report DTOs.
- The benchmark lease is `.desktop-ollama-benchmark-v2.lock` in the report directory. It is not a source-edit lease and confers no source-write ownership.

- [ ] **Step 1: Add failing strict-adapter and cleanup tests**

Append:

```powershell
Describe 'strict local runtime adapters' {
    It 'rejects non-loopback endpoints and incomplete ps evidence' {
        { Assert-AwxLoopbackEndpoint 'http://localhost:11434' } | Should -Throw '*endpoint-not-allowlisted*'
        { ConvertFrom-AwxPsResponse -Response ([pscustomobject]@{}) } | Should -Throw '*ps-evidence-incomplete*'
        { ConvertFrom-AwxPsResponse -Response ([pscustomobject]@{models=@([pscustomobject]@{name='qwen3:8b';size=0;size_vram=0;context_length=8192})}) } | Should -Throw '*ps-evidence-incomplete*'
    }

    It 'requires one exact loaded model, full context, and GPU residency' {
        $ps = ConvertFrom-AwxPsResponse -Response ([pscustomobject]@{models=@([pscustomobject]@{name='qwen3:8b';model='qwen3:8b';size=1000;size_vram=995;context_length=8192})})
        $row = Get-AwxLoadedModelEvidence -Rows $ps -ModelTag 'qwen3:8b' -ExpectedContext 8192
        $row.gpuResidentRatio | Should -Be 0.995
        $row.contextLength | Should -Be 8192
        { Get-AwxLoadedModelEvidence -Rows @($ps[0],$ps[0]) -ModelTag 'qwen3:8b' -ExpectedContext 8192 } | Should -Throw '*ps-evidence-incomplete*'
        $other = [pscustomobject]@{modelTag='other:1b';sizeBytes=1000;sizeVramBytes=1000;contextLength=8192}
        { Get-AwxLoadedModelEvidence -Rows @($ps[0],$other) -ModelTag 'qwen3:8b' -ExpectedContext 8192 } | Should -Throw '*ps-evidence-incomplete*'
    }

    It 'proves the exact endpoint process tree is resident on the expected named GPU without UUID output' {
        $snapshot = @(
            [pscustomobject]@{laneLabel='RTX 3060';totalMiB=12288;freeMiB=6000},
            [pscustomobject]@{laneLabel='RTX 3090';totalMiB=24576;freeMiB=22000}
        )
        $compute = @([pscustomobject]@{processId=102;laneLabel='RTX 3060';usedMiB=5000})
        $lane = Test-AwxGpuLane -EndpointProcessIds @(101,102) -ComputeRows $compute -Snapshot $snapshot -ExpectedLane 'RTX 3060' -MinimumFreeMiB 1536
        $lane.passed | Should -BeTrue
        ($lane | ConvertTo-Json -Depth 5) | Should -Not -Match '(?i)uuid|GPU-[0-9a-f-]{16,}'
        (Test-AwxGpuRelease -EndpointProcessIds @(101,102) -ComputeRows @()).passed | Should -BeTrue
        (Test-AwxGpuRelease -EndpointProcessIds @(101,102) -ComputeRows $compute).passed | Should -BeFalse
    }

    It 'takes an exclusive benchmark-only lease and atomically replaces a report' {
        $report = Join-Path $TestDrive 'out\report.json'
        $lease = Enter-AwxBenchmarkLease -OutputPath $report
        try { { Enter-AwxBenchmarkLease -OutputPath $report } | Should -Throw '*benchmark-lock-held*' }
        finally { $lease.Dispose() }
        Write-AwxJsonAtomic -Path $report -Value ([ordered]@{schemaVersion=2;verdict='HOLD'})
        Write-AwxJsonAtomic -Path $report -Value ([ordered]@{schemaVersion=2;verdict='APPLY'})
        (Get-Content -LiteralPath $report -Raw -Encoding UTF8 | ConvertFrom-Json).verdict | Should -BeExactly 'APPLY'
        @(Get-ChildItem -LiteralPath (Split-Path $report) -Filter '*.tmp' -Force).Count | Should -Be 0
    }
}

Describe 'serial model block orchestration' {
    It 'never overlaps model requests and always unloads after a successful block' {
        $script:active = 0; $script:maxActive = 0; $script:unloaded = @()
        $adapters = [pscustomobject]@{
            VerifyInstalled = { param($m) [pscustomobject]@{passed=$true} }
            PsEmpty = { param($m) $true }
            Gpu = { param($phase,$m,$timeout) @([pscustomobject]@{laneLabel=$m.gpuLane;totalMiB=24576;freeMiB=12000}) }
            Loaded = { param($m,$timeout) [pscustomobject]@{gpuResidentRatio=1.0;sizeVramMiB=10000;contextLength=8192;endpointProcessIds=@(101,102);computeRows=@([pscustomobject]@{processId=102;laneLabel=$m.gpuLane;usedMiB=10000})} }
            Chat = {
                param($m,$case,$body,$timeout)
                $script:active++; $script:maxActive = [Math]::Max($script:maxActive,$script:active)
                $content = switch ($case.id) {
                    'fast-rewrite-ko' {'{"rewrite":"검증 후 기본값을 바꾼다.","meaningUnits":["DOWNLOAD_MODEL","VERIFY_BEFORE_DEFAULT_CHANGE","CHANGE_DEFAULT_ONLY_AFTER_PASS"]}'}
                    'fast-json-extract' {'{"gpu":"RTX 3060","role":"fast","port":11435}'}
                    'fast-instruction' {'{"token":"PASS"}'}
                    default { throw 'unexpected-test-case' }
                }
                try { [pscustomobject]@{latencyMs=10;ollamaResponse=[pscustomobject]@{model=$m.modelTag;done_reason='stop';eval_count=8;message=[pscustomobject]@{thinking='';content=$content}}} }
                finally { $script:active-- }
            }
            Unload = { param($m,$timeout) [void]($script:unloaded += $m.modelTag) }
            GpuReleased = { param($m,$processIds,$timeout) $true }
        }
        $model = [pscustomobject]@{role='fast';classification='baseline';modelTag='qwen3:8b';endpoint='http://127.0.0.1:11435';gpuLane='RTX 3060';digest='500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'}
        $portfolioClock = [Diagnostics.Stopwatch]::StartNew()
        $block = Invoke-AwxModelBlock -Model $model -Corpus $script:Corpus -Oracle $script:Oracle -Adapters $adapters -PortfolioClock $portfolioClock -BlockTimeoutSec 1800 -PortfolioTimeoutSec 7200
        $portfolioClock.Stop()
        $script:maxActive | Should -Be 1
        $script:unloaded | Should -Be @('qwen3:8b','qwen3:8b')
        @($block.blockFacts.PSObject.Properties.Name) | Should -Be @('modelTag','role','classification','cold','warm','lane','cleanup')
        $block.blockFacts.lane.lanePassed | Should -BeOfType System.Boolean
        $block.blockFacts.cleanup.psEmpty | Should -BeOfType System.Boolean
        $block.blockEvidenceSha256 | Should -Match '^[a-f0-9]{64}$'
    }

    It 'unloads on failure and does not advance to the next model' {
        $script:calls = @(); $script:unloaded = @()
        $models = @(
            [pscustomobject]@{role='fast';classification='baseline';modelTag='qwen3:8b'},
            [pscustomobject]@{role='fast';classification='primary';modelTag='qwen3.5:9b'}
        )
        $runner = {
            param($model)
            $script:calls += $model.modelTag
            throw 'injected-block-failure'
        }
        $unloader = { param($model) [void]($script:unloaded += $model.modelTag) }
        { Invoke-AwxBenchmarkPortfolio -Models $models -ModelBlockRunner $runner -EmergencyUnloader $unloader -PortfolioTimeoutSec 7200 } | Should -Throw '*benchmark-runtime-failed*'
        $script:calls | Should -Be @('qwen3:8b')
        $script:unloaded | Should -Contain 'qwen3:8b'
        $script:calls | Should -Not -Contain 'qwen3.5:9b'
    }

    It 'runs two successful endpoint blocks strictly in sequence and rejects overlapping intervals' {
        $models = @(
            [pscustomobject]@{modelTag='qwen3:8b';endpoint='http://127.0.0.1:11435'},
            [pscustomobject]@{modelTag='gemma4:26b';endpoint='http://127.0.0.1:11434'}
        )
        $script:events = @(); $script:active = 0; $script:maxActive = 0
        $runner = {
            param($model,$clock,$limit)
            $script:events += ('start:' + $model.modelTag); $script:active++; $script:maxActive=[Math]::Max($script:maxActive,$script:active)
            try { Start-Sleep -Milliseconds 10; [pscustomobject]@{modelTag=$model.modelTag} }
            finally { $script:active--; $script:events += ('end:' + $model.modelTag) }
        }
        $results = @(Invoke-AwxBenchmarkPortfolio -Models $models -ModelBlockRunner $runner -EmergencyUnloader {param($m,$t)} -PortfolioTimeoutSec 7200)
        $results.Count | Should -Be 2
        $script:maxActive | Should -Be 1
        $script:events | Should -Be @('start:qwen3:8b','end:qwen3:8b','start:gemma4:26b','end:gemma4:26b')
        { Assert-AwxNoOverlappingModelBlocks @([pscustomobject]@{startMs=0;endMs=10},[pscustomobject]@{startMs=9;endMs=20}) } | Should -Throw '*concurrent-model-block-detected*'
    }
}
```

The serial-block fixture above uses the exact three fast oracle-matching envelopes and remains a pure fake-adapter test; it must never reach the network.

- [ ] **Step 2: Run RED**

Expected: FAIL because the strict runtime, lease, atomic writer, and serial orchestration functions are absent.

- [ ] **Step 3: Implement bounded loopback HTTP and strict `/api/tags`/`/api/ps` parsing**

```powershell
function Invoke-AwxOllamaHttp {
    param(
        [Parameter(Mandatory)][string]$Endpoint,
        [Parameter(Mandatory)][ValidateSet('/api/tags','/api/ps','/api/chat','/api/generate')][string]$Path,
        [Parameter(Mandatory)][ValidateSet('GET','POST')][string]$Method,
        [string]$Body,
        [Parameter(Mandatory)][ValidateRange(1,1800)][int]$TimeoutSec
    )
    Assert-AwxLoopbackEndpoint $Endpoint
    $invoke = @{Uri=($Endpoint + $Path);Method=$Method;TimeoutSec=$TimeoutSec;ErrorAction='Stop'}
    if ($Method -ceq 'POST') {
        $invoke.ContentType = 'application/json; charset=utf-8'
        $invoke.Body = $script:Utf8NoBom.GetBytes([string]$Body)
    }
    try { return Invoke-RestMethod @invoke }
    catch { throw [InvalidOperationException]::new(('ollama-http-failed:{0}' -f $Path)) }
}

function Get-AwxOllamaTags {
    param([Parameter(Mandatory)][string]$Endpoint,[int]$TimeoutSec=15)
    $response = Invoke-AwxOllamaHttp -Endpoint $Endpoint -Path '/api/tags' -Method GET -TimeoutSec $TimeoutSec
    if ($null -eq $response.PSObject.Properties['models']) { throw 'tags-evidence-incomplete' }
    return @($response.models)
}

function Test-AwxInstalledDigest {
    param([Parameter(Mandatory)][object[]]$Rows,[Parameter(Mandatory)][string]$ModelTag,[Parameter(Mandatory)][string]$ExpectedDigest)
    $matches = @($Rows | Where-Object { [string]$_.name -ceq $ModelTag -or [string]$_.model -ceq $ModelTag })
    if ($matches.Count -ne 1 -or [string]$matches[0].digest -cnotmatch '^[a-f0-9]{64}$') { return [pscustomobject]@{passed=$false;reasonCode='candidate-digest-changed'} }
    return [pscustomobject]@{passed=([string]$matches[0].digest -ceq $ExpectedDigest);reasonCode=$(if ([string]$matches[0].digest -ceq $ExpectedDigest) {'pass'} else {'candidate-digest-changed'})}
}

function ConvertFrom-AwxPsResponse {
    param([Parameter(Mandatory)]$Response)
    if ($null -eq $Response.PSObject.Properties['models']) { throw 'ps-evidence-incomplete' }
    $rows = foreach ($row in @($Response.models)) {
        if ($null -eq $row.PSObject.Properties['size'] -or $null -eq $row.PSObject.Properties['size_vram'] -or
            $null -eq $row.PSObject.Properties['context_length'] -or [double]$row.size -le 0 -or [double]$row.size_vram -lt 0) {
            throw 'ps-evidence-incomplete'
        }
        [pscustomobject]@{modelTag=$(if ($row.PSObject.Properties['name']) {[string]$row.name} else {[string]$row.model});sizeBytes=[double]$row.size;sizeVramBytes=[double]$row.size_vram;contextLength=[int]$row.context_length}
    }
    return @($rows)
}

function Get-AwxOllamaPs {
    param([Parameter(Mandatory)][string]$Endpoint,[int]$TimeoutSec=15)
    return ConvertFrom-AwxPsResponse (Invoke-AwxOllamaHttp -Endpoint $Endpoint -Path '/api/ps' -Method GET -TimeoutSec $TimeoutSec)
}

function Get-AwxLoadedModelEvidence {
    param([Parameter(Mandatory)][object[]]$Rows,[Parameter(Mandatory)][string]$ModelTag,[Parameter(Mandatory)][int]$ExpectedContext)
    $matches = @($Rows | Where-Object modelTag -CEQ $ModelTag)
    if ($Rows.Count -ne 1 -or $matches.Count -ne 1 -or $matches[0].contextLength -ne $ExpectedContext) { throw 'ps-evidence-incomplete' }
    return [pscustomobject]@{modelTag=$ModelTag;contextLength=$matches[0].contextLength;sizeVramMiB=[Math]::Round($matches[0].sizeVramBytes / 1MB,3);gpuResidentRatio=[Math]::Round($matches[0].sizeVramBytes / $matches[0].sizeBytes,6)}
}
```

Do not add a headers parameter. Do not return exception bodies, response bodies, cookies, or authorization metadata.

- [ ] **Step 4: Implement UUID-free GPU evidence and strict unload**

```powershell
function Invoke-AwxNativeBounded {
    param([Parameter(Mandatory)][string]$FilePath,[Parameter(Mandatory)][string]$Arguments,[Parameter(Mandatory)][ValidateRange(1,60)][int]$TimeoutSec)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $FilePath; $start.Arguments = $Arguments; $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true; $start.CreateNoWindow = $true
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'native-probe-failed' }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync(); $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSec * 1000)) { try {$process.Kill()} catch {}; throw 'native-probe-timeout' }
        $stdout = $stdoutTask.GetAwaiter().GetResult(); [void]$stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw 'native-probe-failed' }
        return @($stdout -split '\r?\n' | Where-Object { $_.Length -gt 0 })
    } finally { $process.Dispose() }
}

function Get-AwxGpuSnapshot {
    param([int]$TimeoutSec=15)
    $lines = @(Invoke-AwxNativeBounded -FilePath 'nvidia-smi.exe' -Arguments '--query-gpu=name,memory.total,memory.free --format=csv,noheader,nounits' -TimeoutSec $TimeoutSec)
    $rows = foreach ($line in $lines) {
        $parts = @($line -split ',' | ForEach-Object { $_.Trim() })
        if ($parts.Count -ne 3 -or $parts[0] -notmatch '^NVIDIA GeForce RTX (3060|3090)$' -or $parts[1] -notmatch '^\d+$' -or $parts[2] -notmatch '^\d+$') { throw 'gpu-evidence-incomplete' }
        [pscustomobject]@{laneLabel=($parts[0] -replace '^NVIDIA GeForce ','');totalMiB=[int]$parts[1];freeMiB=[int]$parts[2]}
    }
    if ($rows.Count -ne 2 -or @($rows.laneLabel | Select-Object -Unique).Count -ne 2) { throw 'gpu-evidence-incomplete' }
    return @($rows)
}

function Get-AwxEndpointProcessIds {
    param([Parameter(Mandatory)][string]$Endpoint,[int]$TimeoutSec=15)
    Assert-AwxLoopbackEndpoint $Endpoint
    $port = ([uri]$Endpoint).Port
    $listenerPattern = '^\s*TCP\s+(?:127\.0\.0\.1|0\.0\.0\.0|\[::\]|\[::1\]):' + $port + '\s+\S+\s+LISTENING\s+(\d+)\s*$'
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $listeners = @(Invoke-AwxNativeBounded -FilePath 'netstat.exe' -Arguments '-ano -p tcp' -TimeoutSec $TimeoutSec | ForEach-Object {
        if ($_ -match $listenerPattern) { [int]$Matches[1] }
    } | Select-Object -Unique)
    if ($listeners.Count -ne 1) { throw 'gpu-lane-evidence-incomplete' }
    $remaining = [Math]::Floor($TimeoutSec - $clock.Elapsed.TotalSeconds)
    if ($remaining -lt 1) { throw 'native-probe-timeout' }
    $processes = @(Get-CimInstance Win32_Process -OperationTimeoutSec $remaining -ErrorAction Stop | Select-Object ProcessId,ParentProcessId)
    $ids = [Collections.Generic.HashSet[int]]::new(); [void]$ids.Add([int]$listeners[0])
    do {
        $added = $false
        foreach ($process in $processes) {
            if ($ids.Contains([int]$process.ParentProcessId) -and $ids.Add([int]$process.ProcessId)) { $added = $true }
        }
    } while ($added)
    return @($ids | Sort-Object)
}

function Get-AwxGpuComputeRows {
    param([int]$TimeoutSec=15)
    $lines = @(Invoke-AwxNativeBounded -FilePath 'nvidia-smi.exe' -Arguments '--query-compute-apps=pid,gpu_name --format=csv,noheader,nounits' -TimeoutSec $TimeoutSec)
    return @($lines | ForEach-Object {
        $parts = @($_ -split ',' | ForEach-Object {$_.Trim()})
        if ($parts.Count -ne 2 -or $parts[0] -notmatch '^\d+$' -or $parts[1] -notmatch '^NVIDIA GeForce RTX (3060|3090)$') { throw 'gpu-lane-evidence-incomplete' }
        [pscustomobject]@{processId=[int]$parts[0];laneLabel=($parts[1] -replace '^NVIDIA GeForce ','')}
    })
}

function Test-AwxGpuLane {
    param([Parameter(Mandatory)][int[]]$EndpointProcessIds,[Parameter(Mandatory)][object[]]$ComputeRows,[Parameter(Mandatory)][object[]]$Snapshot,[Parameter(Mandatory)][string]$ExpectedLane,[Parameter(Mandatory)][double]$MinimumFreeMiB)
    $matches = @($ComputeRows | Where-Object { $_.processId -in $EndpointProcessIds })
    $card = @($Snapshot | Where-Object laneLabel -CEQ $ExpectedLane)
    $passed = $matches.Count -gt 0 -and @($matches | Where-Object laneLabel -CNE $ExpectedLane).Count -eq 0 -and $card.Count -eq 1 -and [double]$card[0].freeMiB -ge $MinimumFreeMiB
    return [pscustomobject]@{passed=$passed;reasonCode=$(if($passed){'pass'}elseif($card.Count -eq 1 -and [double]$card[0].freeMiB -lt $MinimumFreeMiB){'insufficient-vram'}else{'gpu-lane-evidence-incomplete'});laneLabel=$ExpectedLane;totalMiB=$(if($card.Count -eq 1){[int]$card[0].totalMiB}else{0});freeMiB=$(if($card.Count -eq 1){[int]$card[0].freeMiB}else{0})}
}

function Test-AwxGpuRelease {
    param([Parameter(Mandatory)][int[]]$EndpointProcessIds,[Parameter(Mandatory)][object[]]$ComputeRows)
    $remaining = @($ComputeRows | Where-Object { $_.processId -in $EndpointProcessIds })
    return [pscustomobject]@{passed=($remaining.Count -eq 0);reasonCode=$(if($remaining.Count -eq 0){'pass'}else{'gpu-release-unproven'})}
}

function Stop-AwxOllamaModel {
    param([Parameter(Mandatory)]$Model,[int]$TimeoutSec=30)
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $body = ConvertTo-AwxCanonicalJson ([ordered]@{model=$Model.modelTag;keep_alive=0})
    Invoke-AwxOllamaHttp -Endpoint $Model.endpoint -Path '/api/generate' -Method POST -Body $body -TimeoutSec $TimeoutSec | Out-Null
    while ($clock.Elapsed.TotalSeconds -lt $TimeoutSec) {
        $remaining = [Math]::Floor($TimeoutSec - $clock.Elapsed.TotalSeconds)
        if ($remaining -lt 1) { break }
        if (@(Get-AwxOllamaPs -Endpoint $Model.endpoint -TimeoutSec ([Math]::Min(5,$remaining))).Count -eq 0) { return }
        Start-Sleep -Milliseconds 250
    }
    throw 'model-unload-unproven'
}
```

The lane check is evidence-based: it maps the exact endpoint listener process tree to `nvidia-smi` compute-app `pid,gpu_name` rows and requires every matching process on the expected uniquely named card, while `/api/ps` separately proves model/context/residency. The host's current `nvidia-smi --help-query-compute-apps` exposes both fields; `used_gpu_memory` is intentionally not queried because NVIDIA documents it as unavailable under Windows WDDM. All native probes have a hard timeout. It never queries, prints, hashes, or stores a GPU UUID. If the driver cannot expose `gpu_name`, fail closed with `gpu-lane-evidence-incomplete` instead of substituting an unapproved memory-delta tolerance.

- [ ] **Step 5: Implement the benchmark-only lease and replace-capable atomic writer**

```powershell
function Enter-AwxBenchmarkLease {
    param([Parameter(Mandatory)][string]$OutputPath)
    $directory = [IO.Path]::GetFullPath((Split-Path -Parent $OutputPath))
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    $path = Join-Path $directory '.desktop-ollama-benchmark-v2.lock'
    try { $stream = [IO.FileStream]::new($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
    catch [IO.IOException] { throw 'benchmark-lock-held' }
    $token = [guid]::NewGuid().ToString('N')
    try {
        $bytes = $script:Utf8NoBom.GetBytes((ConvertTo-AwxCanonicalJson ([ordered]@{schemaVersion=2;pid=$PID;token=$token})))
        $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true)
        $lease = [pscustomobject]@{Path=$path;Stream=$stream;Token=$token}
        $lease | Add-Member ScriptMethod Dispose {
            try { if ($null -ne $this.Stream) { $this.Stream.Dispose() } }
            finally { if (Test-Path -LiteralPath $this.Path -PathType Leaf) { Remove-Item -LiteralPath $this.Path -Force -ErrorAction SilentlyContinue } }
        }
        return $lease
    } catch { $stream.Dispose(); Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue; throw }
}

function Write-AwxJsonAtomic {
    param([Parameter(Mandatory)][string]$Path,[Parameter(Mandatory)]$Value)
    $directory = [IO.Path]::GetFullPath((Split-Path -Parent $Path))
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    $target = [IO.Path]::GetFullPath($Path)
    $temp = Join-Path $directory ('.' + [IO.Path]::GetFileName($target) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    $backup = Join-Path $directory ('.' + [IO.Path]::GetFileName($target) + '.' + [guid]::NewGuid().ToString('N') + '.bak')
    $json = ConvertTo-AwxCanonicalJson $Value
    $bytes = $script:Utf8NoBom.GetBytes($json)
    try {
        $stream = [IO.FileStream]::new($temp,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try { $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
        if ((Get-AwxFileSha256 $temp) -cne (Get-AwxUtf8Sha256 $json)) { throw 'report-temp-hash-mismatch' }
        if (Test-Path -LiteralPath $target -PathType Leaf) { [IO.File]::Replace($temp,$target,$backup,$true); Remove-Item -LiteralPath $backup -Force }
        else { [IO.File]::Move($temp,$target) }
    } catch {
        Remove-Item -LiteralPath $temp,$backup -Force -ErrorAction SilentlyContinue
        throw 'report-write-failed'
    }
}
```

- [ ] **Step 6: Implement one model block with unconditional unload**

Use a synchronous adapter object with exactly `VerifyInstalled`, `PsEmpty`, `Gpu`, `Loaded`, `Chat`, `Unload`, and `GpuReleased` scriptblock properties. Every adapter receives a bounded timeout. The production adapter wraps the functions above; Pester injects fakes.

```powershell
function Get-AwxBoundedReasonCode {
    param([Parameter(Mandatory)][Exception]$Exception)
    $httpMap = @{
        'ollama-http-failed:/api/tags'='tags-evidence-incomplete'
        'ollama-http-failed:/api/ps'='ps-evidence-incomplete'
        'ollama-http-failed:/api/chat'='benchmark-runtime-failed'
        'ollama-http-failed:/api/generate'='model-unload-unproven'
    }
    $allowed = @(
        'candidate-digest-changed','response-json-invalid','response-schema-invalid','response-semantic-mismatch','response-truncated',
        'lineage-missing','cpu-offload-detected','insufficient-vram','warm-sample-insufficient','portfolio-timeout','model-block-timeout',
        'model-unload-unproven','gpu-lane-evidence-incomplete','gpu-release-unproven','ps-evidence-incomplete','tags-evidence-incomplete','native-probe-timeout','role-binding-mutated','concurrent-model-block-detected'
    )
    $message = [string]$Exception.Message
    if ($httpMap.ContainsKey($message)) { return [string]$httpMap[$message] }
    return $(if ($message -cin $allowed) {$message} else {'benchmark-runtime-failed'})
}

function Get-AwxRemainingTimeoutSec {
    param([Parameter(Mandatory)][int]$RequestCapSec,[Parameter(Mandatory)]$BlockClock,[Parameter(Mandatory)][int]$BlockTimeoutSec,[Parameter(Mandatory)]$PortfolioClock,[Parameter(Mandatory)][int]$PortfolioTimeoutSec)
    $blockRemaining = [Math]::Floor($BlockTimeoutSec - $BlockClock.Elapsed.TotalSeconds)
    $portfolioRemaining = [Math]::Floor($PortfolioTimeoutSec - $PortfolioClock.Elapsed.TotalSeconds)
    if ($portfolioRemaining -lt 1) { throw 'portfolio-timeout' }
    if ($blockRemaining -lt 1) { throw 'model-block-timeout' }
    return [int][Math]::Min($RequestCapSec,[Math]::Min($blockRemaining,$portfolioRemaining))
}

function Assert-AwxNoOverlappingModelBlocks {
    param([Parameter(Mandatory)][object[]]$Intervals)
    $ordered = @($Intervals | Sort-Object startMs)
    for ($index=1; $index -lt $ordered.Count; $index++) {
        if ([double]$ordered[$index].startMs -lt [double]$ordered[$index-1].endMs) { throw 'concurrent-model-block-detected' }
    }
}

function Invoke-AwxModelBlock {
    param(
        [Parameter(Mandatory)]$Model,[Parameter(Mandatory)]$Corpus,[Parameter(Mandatory)]$Oracle,[Parameter(Mandatory)]$Adapters,
        [Parameter(Mandatory)]$PortfolioClock,[int]$BlockTimeoutSec=1800,[int]$PortfolioTimeoutSec=7200
    )
    $blockClock = [Diagnostics.Stopwatch]::StartNew()
    $warm = [Collections.Generic.List[object]]::new(); $cold = $null; $loadedGpu = $null; $loaded = $null; $endLoaded = $null; $result = $null
    $primaryReason = $null; $cleanupReason = $null; $cleanupEvidence = $null
    $caseIds = $(if ($Model.role -ceq 'fast') {@('fast-rewrite-ko','fast-json-extract','fast-instruction')} else {@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
    try {
        $timeout = Get-AwxRemainingTimeoutSec 15 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $installed = & $Adapters.VerifyInstalled $Model $timeout
        if (-not $installed.passed) { throw 'candidate-digest-changed' }
        $timeout = Get-AwxRemainingTimeoutSec 30 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        [void](& $Adapters.Unload $Model $timeout)
        $timeout = Get-AwxRemainingTimeoutSec 15 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        if (-not (& $Adapters.PsEmpty $Model $timeout)) { throw 'model-unload-unproven' }
        $coldCase = $Corpus.cases | Where-Object id -CEQ $caseIds[0]
        $coldExpected = ($Oracle.cases | Where-Object id -CEQ $caseIds[0]).expected
        $coldBody = New-AwxBenchmarkRequestBody -Case $coldCase -ModelTag $Model.modelTag -Options $Corpus.options
        $timeout = Get-AwxRemainingTimeoutSec 300 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $coldCall = & $Adapters.Chat $Model $coldCase $coldBody $timeout
        $coldCheck = Test-AwxBenchmarkResponse -Case $coldCase -Expected $coldExpected -ExpectedModel $Model.modelTag -OllamaResponse $coldCall.ollamaResponse
        $cold = [pscustomobject]@{caseId=$caseIds[0];latencyMs=[double]$coldCall.latencyMs;requestSha256=Get-AwxUtf8Sha256 $coldBody;transportPassed=$coldCheck.transportPassed;lineagePassed=$coldCheck.lineagePassed;jsonParsed=$coldCheck.jsonParsed;schemaPassed=$coldCheck.schemaPassed;semanticPassed=$coldCheck.semanticPassed;reasonCode=$coldCheck.reasonCode;responseSha256=$coldCheck.responseSha256;responseLength=$coldCheck.responseLength;tokenCount=$coldCheck.tokenCount;thinkingLength=$coldCheck.thinkingLength;doneReason=$coldCheck.doneReason}
        if (-not $cold.transportPassed -or -not $cold.lineagePassed -or -not $cold.schemaPassed) { throw $coldCheck.reasonCode }
        $timeout = Get-AwxRemainingTimeoutSec 15 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $loaded = & $Adapters.Loaded $Model $timeout
        $timeout = Get-AwxRemainingTimeoutSec 15 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $loadedGpu = @(& $Adapters.Gpu 'loaded' $Model $timeout)
        $minFree = $(if ($Model.role -ceq 'fast') {1536} else {2048})
        $lane = Test-AwxGpuLane -EndpointProcessIds @($loaded.endpointProcessIds) -ComputeRows @($loaded.computeRows) -Snapshot $loadedGpu -ExpectedLane $Model.gpuLane -MinimumFreeMiB $minFree
        if (-not $lane.passed) { throw $lane.reasonCode }
        if ([int]$loaded.contextLength -ne 8192) { throw 'ps-evidence-incomplete' }
        if ([double]$loaded.gpuResidentRatio -lt 0.99) { throw 'cpu-offload-detected' }
        foreach ($caseId in $caseIds) {
            $case = $Corpus.cases | Where-Object id -CEQ $caseId
            $expected = ($Oracle.cases | Where-Object id -CEQ $caseId).expected
            $body = New-AwxBenchmarkRequestBody -Case $case -ModelTag $Model.modelTag -Options $Corpus.options
            foreach ($repetition in 1..7) {
                $timeout = Get-AwxRemainingTimeoutSec 120 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
                $call = & $Adapters.Chat $Model $case $body $timeout
                $check = Test-AwxBenchmarkResponse -Case $case -Expected $expected -ExpectedModel $Model.modelTag -OllamaResponse $call.ollamaResponse
                $warm.Add([pscustomobject]@{caseId=$caseId;repetition=$repetition;latencyMs=[double]$call.latencyMs;requestSha256=Get-AwxUtf8Sha256 $body;transportPassed=$check.transportPassed;lineagePassed=$check.lineagePassed;jsonParsed=$check.jsonParsed;schemaPassed=$check.schemaPassed;semanticPassed=$check.semanticPassed;reasonCode=$check.reasonCode;responseSha256=$check.responseSha256;responseLength=$check.responseLength;tokenCount=$check.tokenCount;thinkingLength=$check.thinkingLength;doneReason=$check.doneReason})
                if (-not $check.transportPassed -or -not $check.lineagePassed -or -not $check.schemaPassed) { throw $check.reasonCode }
                [void](Get-AwxRemainingTimeoutSec 120 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec)
            }
        }
        if ($warm.Count -ne 21) { throw 'warm-sample-insufficient' }
        $timeout = Get-AwxRemainingTimeoutSec 15 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $endLoaded = & $Adapters.Loaded $Model $timeout
        $timeout = Get-AwxRemainingTimeoutSec 15 $blockClock $BlockTimeoutSec $PortfolioClock $PortfolioTimeoutSec
        $endGpu = @(& $Adapters.Gpu 'end' $Model $timeout)
        $endLane = Test-AwxGpuLane -EndpointProcessIds @($endLoaded.endpointProcessIds) -ComputeRows @($endLoaded.computeRows) -Snapshot $endGpu -ExpectedLane $Model.gpuLane -MinimumFreeMiB $minFree
        if (-not $endLane.passed -or [double]$endLoaded.gpuResidentRatio -lt 0.99 -or [int]$endLoaded.contextLength -ne 8192) { throw 'gpu-lane-evidence-incomplete' }
        $endpointRestarted = -not (Test-AwxExactSequence @($loaded.endpointProcessIds) @($endLoaded.endpointProcessIds))
        $result = [pscustomobject]@{modelTag=$Model.modelTag;role=$Model.role;classification=$Model.classification;cold=$cold;warm=@($warm);loaded=$loaded;lane=$lane;endLoaded=$endLoaded;endLane=$endLane;endpointRestarted=$endpointRestarted;blockElapsedMs=[Math]::Round($blockClock.Elapsed.TotalMilliseconds,3)}
    } catch {
        $primaryReason = Get-AwxBoundedReasonCode $_.Exception
    } finally {
        try {
            [void](& $Adapters.Unload $Model 30)
            $psEmpty = [bool](& $Adapters.PsEmpty $Model 15)
            $loadedProcessIds = $(if ($null -ne $loaded -and $loaded.PSObject.Properties['endpointProcessIds']) {@($loaded.endpointProcessIds)} else {@()})
            $endProcessIds = $(if ($null -ne $endLoaded -and $endLoaded.PSObject.Properties['endpointProcessIds']) {@($endLoaded.endpointProcessIds)} else {@()})
            $allProcessIds = @($loadedProcessIds + $endProcessIds | Select-Object -Unique)
            $gpuReleased = [bool](& $Adapters.GpuReleased $Model $allProcessIds 15)
            $postUnloadGpu = @(& $Adapters.Gpu 'post-unload' $Model 15)
            if (-not $psEmpty -or -not $gpuReleased) { $cleanupReason = 'model-unload-unproven' }
            else { $cleanupEvidence = [pscustomobject]@{psEmpty=$true;gpuReleased=$true;postUnloadGpu=$postUnloadGpu} }
        } catch { $cleanupReason = 'model-unload-unproven' }
        $blockClock.Stop()
    }
    if ($cleanupReason) { throw $cleanupReason }
    if ($primaryReason) { throw $primaryReason }
    $result | Add-Member -NotePropertyName cleanup -NotePropertyValue $cleanupEvidence
    $coldFact=[pscustomobject][ordered]@{caseId=$cold.caseId;requestSha256=$cold.requestSha256;responseSha256=$cold.responseSha256;latencyMs=[double]$cold.latencyMs;transportPassed=$cold.transportPassed;lineagePassed=$cold.lineagePassed;jsonParsed=$cold.jsonParsed;schemaPassed=$cold.schemaPassed;semanticPassed=$cold.semanticPassed;reasonCode=$cold.reasonCode}
    $warmFacts=@($warm|ForEach-Object{[pscustomobject][ordered]@{caseId=$_.caseId;repetition=[int]$_.repetition;requestSha256=$_.requestSha256;responseSha256=$_.responseSha256;latencyMs=[double]$_.latencyMs;transportPassed=$_.transportPassed;lineagePassed=$_.lineagePassed;jsonParsed=$_.jsonParsed;schemaPassed=$_.schemaPassed;semanticPassed=$_.semanticPassed;reasonCode=$_.reasonCode}})
    $blockFacts=[pscustomobject][ordered]@{
        modelTag=$Model.modelTag;role=$Model.role;classification=$Model.classification;cold=$coldFact;warm=$warmFacts
        lane=[pscustomobject][ordered]@{gpuResidentRatio=[Math]::Min([double]$loaded.gpuResidentRatio,[double]$endLoaded.gpuResidentRatio);loadedFreeMiB=[Math]::Min([double]$lane.freeMiB,[double]$endLane.freeMiB);lanePassed=[bool]($lane.passed -and $endLane.passed)}
        cleanup=[pscustomobject][ordered]@{psEmpty=[bool]$cleanupEvidence.psEmpty;gpuReleased=[bool]$cleanupEvidence.gpuReleased;endpointRestarted=[bool]$endpointRestarted}
    }
    $blockEnvelope=New-AwxBlockEvidence -BlockFacts $blockFacts
    $result|Add-Member -NotePropertyName blockFacts -NotePropertyValue $blockEnvelope.blockFacts
    $result|Add-Member -NotePropertyName blockEvidenceSha256 -NotePropertyValue $blockEnvelope.blockEvidenceSha256
    return $result
}

function Invoke-AwxBenchmarkPortfolio {
    param([Parameter(Mandatory)][object[]]$Models,[Parameter(Mandatory)][scriptblock]$ModelBlockRunner,[Parameter(Mandatory)][scriptblock]$EmergencyUnloader,[int]$PortfolioTimeoutSec=7200)
    $clock = [Diagnostics.Stopwatch]::StartNew(); $results = [Collections.Generic.List[object]]::new(); $intervals = [Collections.Generic.List[object]]::new()
    foreach ($model in $Models) {
        if ($clock.Elapsed.TotalSeconds -ge $PortfolioTimeoutSec) { throw 'portfolio-timeout' }
        $startMs = $clock.Elapsed.TotalMilliseconds
        try { $results.Add((& $ModelBlockRunner $model $clock $PortfolioTimeoutSec)) }
        catch {
            $reason = Get-AwxBoundedReasonCode $_.Exception
            try { [void](& $EmergencyUnloader $model 30) } catch { throw 'model-unload-unproven' }
            throw $reason
        }
        $intervals.Add([pscustomobject]@{startMs=$startMs;endMs=$clock.Elapsed.TotalMilliseconds})
        Assert-AwxNoOverlappingModelBlocks @($intervals)
        if ($clock.Elapsed.TotalSeconds -ge $PortfolioTimeoutSec) { throw 'portfolio-timeout' }
    }
    $clock.Stop(); return @($results)
}
```

Cleanup uses its own bounded 30/15-second safety caps even after a model/portfolio budget expires; it never launches another model afterward. `model-unload-unproven` takes precedence over the primary bounded reason. Unknown exception text is reduced to `benchmark-runtime-failed`, so a provider/native full error cannot reach the report.

- [ ] **Step 7: Run GREEN and prove no live call occurred**

Run Pester with both Ollama processes stopped or with the endpoint ports blocked in the fake-adapter context. Expected: all Task 5 tests pass because every orchestration test injects scriptblocks. Scan the test transcript for `127.0.0.1:11434` and `127.0.0.1:11435`; endpoint strings may appear as constants, but there must be no HTTP transcript or generated live result.

- [ ] **Step 8: Record the conditional checkpoint**

Suggested commit, only with Git authorization:

```powershell
git add -- scripts/modules/DesktopOllamaModelTools.psm1 scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1
git commit -m "feat: serialize local benchmark model blocks"
```

---

### Task 6: Thin CLI, Exact Six-Model Matrix, Privacy Allowlist, and Recommendation-Only Report

**Files:**
- Modify: `scripts/modules/DesktopOllamaModelTools.psm1`
- Create: `scripts/desktop_ollama_model_benchmark.ps1`
- Modify: `scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1`

**Interfaces:**
- CLI parameters: mandatory `-CorpusPath`, `-OraclePath`, and `-OutputPath`; optional switch `-RunLive`.
- Default mode is validate-only. It validates and hashes local contracts, writes a schemaVersion 2 `HOLD` report, and performs zero HTTP/GPU/model calls.
- Live mode uses the exact six-model matrix below, creates one benchmark lease, executes the serial portfolio, constructs one run binding from the six completed bounded block hashes, calls only module-owned evidence constructors, reuses canonical score microunits for model deltas and recommendation validation, performs provisional then final CommandEvidence privacy/binding verification, projects exact public packet/NEUTRAL DTOs, and changes no environment variable or role binding.

- [ ] **Step 1: Add failing CLI, matrix, redaction, and no-mutation tests**

Append:

```powershell
Describe 'benchmark v2 CLI and public report' {
    BeforeAll { $script:CliPath = Join-Path $script:RepoRoot 'scripts\desktop_ollama_model_benchmark.ps1' }

    It 'pins the exact ordered six-model matrix' {
        $models = @(Get-AwxBenchmarkModelMatrix)
        @($models.modelTag) | Should -Be @('qwen3:8b','qwen3.5:9b','gemma4:12b','gemma4:26b','qwen3.6:27b','gemma4:31b')
        @($models.endpointLabel) | Should -Be @('fast-11435','fast-11435','fast-11435','primary-11434','primary-11434','primary-11434')
        @($models.gpuLane) | Should -Be @('RTX 3060','RTX 3060','RTX 3060','RTX 3090','RTX 3090','RTX 3090')
        @($models.digest) | Should -Be @(
            '500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41',
            '6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7',
            '4eb23ef187e2c5462566d6a1d3bbbc2f1346d0b4327cbb66d58fffbcc9b2b05c',
            '5571076f3d70050487b26b341705799e0ab29b808164f90d20d4cf84f699d251',
            'a50eda8ed977ab48a12431878896b27ffd5cef552c17af3317d9623b939a7f1e',
            '6316f0629137b426c9d9b853ffc4c8209589f30ee39aebede6285096c0ff47e7'
        )
    }

    It 'runs validate-only by default and writes no raw corpus or oracle value' {
        $output = Join-Path $TestDrive 'validate-only.json'
        & $script:CliPath -CorpusPath $script:CorpusPath -OraclePath $script:OraclePath -OutputPath $output
        $reportText = Get-Content -LiteralPath $output -Raw -Encoding UTF8
        $report = $reportText | ConvertFrom-Json
        $report.schemaVersion | Should -Be 2
        $report.mode | Should -BeExactly 'validate-only'
        $report.verdict | Should -BeExactly 'HOLD'
        $report.reasonCodes | Should -Contain 'live-evidence-not-requested'
        $corpus = Get-Content -LiteralPath $script:CorpusPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($case in $corpus.cases) { $reportText | Should -Not -Match ([regex]::Escape([string]$case.prompt)) }
        $oracle = Get-Content -LiteralPath $script:OraclePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $reportText | Should -Not -Match ([regex]::Escape((ConvertTo-AwxCanonicalJson $oracle.cases[0].expected)))
    }

    It 'has no environment, binding, pull, remove, or auth mutation path' {
        $production = (Get-Content -LiteralPath $script:CliPath -Raw -Encoding UTF8) + (Get-Content -LiteralPath $script:ModulePath -Raw -Encoding UTF8)
        $production | Should -Not -Match '(?i)SetEnvironmentVariable|\bsetx(?:\.exe)?\b|Remove-Item\s+Env:|\$env:[A-Za-z_][A-Za-z0-9_]*\s*='
        $production | Should -Not -Match '(?im)^\s*(?:&\s*)?ollama(?:\.exe)?\s+(pull|rm|remove|create|cp)\b'
        $production | Should -Not -Match '(?i)Authorization\s*=|Bearer\s+[A-Za-z0-9._-]+'
    }

    It 'rejects report fields and values outside the public privacy contract' {
        { Assert-AwxReportPrivacy ([pscustomobject]@{schemaVersion=2;rawResponse='secret'}) } | Should -Throw '*report-privacy-invalid*'
        $valid = New-AwxValidateOnlyReport -Corpus $script:Corpus -Oracle $script:Oracle -CorpusPath $script:CorpusPath -OraclePath $script:OraclePath
        { Assert-AwxReportPrivacy $valid } | Should -Not -Throw
        $schemaString = $valid | ConvertTo-Json -Depth 40 | ConvertFrom-Json;$schemaString.schemaVersion='2'
        { Assert-AwxReportPrivacy $schemaString } | Should -Throw '*report-privacy-invalid*'
        $scalarReason = $valid | ConvertTo-Json -Depth 40 | ConvertFrom-Json;$scalarReason.reasonCodes='live-evidence-not-requested'
        { Assert-AwxReportPrivacy $scalarReason } | Should -Throw '*report-privacy-invalid*'
        $multiReason=New-AwxLiveFailureReport -Corpus $script:Corpus -CorpusPath $script:CorpusPath -OraclePath $script:OraclePath -DurationMs 1 -ReasonCode 'candidate-digest-changed';$multiReason.reasonCodes=@('candidate-digest-changed','warm-sample-insufficient')
        { Assert-AwxReportPrivacy $multiReason } | Should -Throw '*report-privacy-invalid*'
        $wrongLane=ConvertTo-AwxPublicPacket $script:FamilyAPackets[0];$wrongLane.passed=$false;$wrongLane.reasonCodes=@('warm-sample-insufficient')
        { Assert-AwxPublicPacketProjection $wrongLane } | Should -Throw '*report-privacy-invalid*'
        $uuid = $valid | ConvertTo-Json -Depth 40 | ConvertFrom-Json; $uuid.benchmarkId = 'GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
        { Assert-AwxReportPrivacy $uuid } | Should -Throw '*report-privacy-invalid*'
        $path = $valid | ConvertTo-Json -Depth 40 | ConvertFrom-Json; $path.corpus.corpusId = 'C:\\private\\model-store'
        { Assert-AwxReportPrivacy $path } | Should -Throw '*report-privacy-invalid*'
    }

    It 'maps HTTP failures to bounded categories and still emits a valid HOLD report' {
        (Get-AwxBoundedReasonCode ([InvalidOperationException]::new('ollama-http-failed:/api/tags'))) | Should -BeExactly 'tags-evidence-incomplete'
        (Get-AwxBoundedReasonCode ([InvalidOperationException]::new('ollama-http-failed:/api/ps'))) | Should -BeExactly 'ps-evidence-incomplete'
        (Get-AwxBoundedReasonCode ([InvalidOperationException]::new('ollama-http-failed:/api/chat'))) | Should -BeExactly 'benchmark-runtime-failed'
        (Get-AwxBoundedReasonCode ([InvalidOperationException]::new('ollama-http-failed:/api/generate'))) | Should -BeExactly 'model-unload-unproven'
        $failure = New-AwxLiveFailureReport -Corpus $script:Corpus -CorpusPath $script:CorpusPath -OraclePath $script:OraclePath -DurationMs 1 -ReasonCode 'tags-evidence-incomplete'
        $failure.verdict | Should -BeExactly 'HOLD'
        { Assert-AwxReportPrivacy $failure } | Should -Not -Throw
    }

    It 'returns exactly nine strict falsification facts for the module-owned packet constructor' {
        $evidence = Invoke-AwxFalsificationProbes -Corpus $script:Corpus -Oracle $script:Oracle
        @($evidence.PSObject.Properties.Name) | Should -Be @('constRejected','shapeOnlyRejected','extraValueRejected','truncationRejected','timeoutRejected','missingHashRejected','orderRejected','oracleIsolated','p95Correct')
        foreach($property in $evidence.PSObject.Properties){$property.Value | Should -BeOfType System.Boolean;$property.Value | Should -BeTrue}
        ($evidence | ConvertTo-Json -Depth 5) | Should -Not -Match 'evidenceSha256|complete|reasonCodes'
    }
}
```

- [ ] **Step 2: Run RED**

Expected: FAIL because the matrix/report helpers and CLI do not exist.

- [ ] **Step 3: Implement the exact fixed model matrix**

```powershell
function Get-AwxBenchmarkModelMatrix {
    return @(
        [pscustomobject][ordered]@{order=1;role='fast';classification='baseline';modelTag='qwen3:8b';endpoint='http://127.0.0.1:11435';endpointLabel='fast-11435';gpuLane='RTX 3060';digest='500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41'},
        [pscustomobject][ordered]@{order=2;role='fast';classification='primary';modelTag='qwen3.5:9b';endpoint='http://127.0.0.1:11435';endpointLabel='fast-11435';gpuLane='RTX 3060';digest='6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7'},
        [pscustomobject][ordered]@{order=3;role='fast';classification='challenger';modelTag='gemma4:12b';endpoint='http://127.0.0.1:11435';endpointLabel='fast-11435';gpuLane='RTX 3060';digest='4eb23ef187e2c5462566d6a1d3bbbc2f1346d0b4327cbb66d58fffbcc9b2b05c'},
        [pscustomobject][ordered]@{order=4;role='main';classification='baseline';modelTag='gemma4:26b';endpoint='http://127.0.0.1:11434';endpointLabel='primary-11434';gpuLane='RTX 3090';digest='5571076f3d70050487b26b341705799e0ab29b808164f90d20d4cf84f699d251'},
        [pscustomobject][ordered]@{order=5;role='main';classification='primary';modelTag='qwen3.6:27b';endpoint='http://127.0.0.1:11434';endpointLabel='primary-11434';gpuLane='RTX 3090';digest='a50eda8ed977ab48a12431878896b27ffd5cef552c17af3317d9623b939a7f1e'},
        [pscustomobject][ordered]@{order=6;role='main';classification='challenger';modelTag='gemma4:31b';endpoint='http://127.0.0.1:11434';endpointLabel='primary-11434';gpuLane='RTX 3090';digest='6316f0629137b426c9d9b853ffc4c8209589f30ee39aebede6285096c0ff47e7'}
    )
}
```

- [ ] **Step 4: Implement a report allowlist and recursive privacy guard**

Top-level fields are exactly:

```text
schemaVersion, benchmarkId, mode, durationMs, corpus, models,
recommendations, packets, neutral, verdict, reasonCodes
```

Nested production DTOs have exact, type-specific shapes:

```text
corpus: corpusId, corpusSha256, ontologySha256, schemaSha256, oracleSha256, optionsSha256, promptHashes
prompt hash: caseId, promptSha256, schemaSha256
model: role, classification, modelTag, digest, endpointLabel, gpuLane, totalMiB, freeMiB,
       gpuResidentRatio, coldLoadMs, warmRoleP95Ms, completedWarmCount, balancedScore,
       scoreDelta, hardGatePassed, cleanupPassed, reasonCodes, caseTimings,
       semanticRates, coldResult, warmResults
case timing: caseId, p50Ms
semantic rate: caseId, rate
cold result: caseId, latencyMs, requestSha256, responseSha256, responseLength, tokenCount,
             transportPassed, lineagePassed, jsonParsed, schemaPassed, semanticPassed,
             thinkingLength, doneReason, reasonCode
warm result: cold-result fields plus repetition
recommendation: role, status, modelTag, scoreDelta, reasonCode
packet: name, complete, passed, reasonCodes, runBindingSha256, evidenceSha256, packetSha256
neutral: forward, forwardReasonCode, reverse, reverseReasonCode, orderStable,
         finalVerdict, finalReasonCode, runBindingSha256, commandEvidenceSha256
```

Implement:

```powershell
function Assert-AwxReportPropertySet {param($Value,[string[]]$Names);Assert-AwxExactPropertySet -Value $Value -Names $Names -ReasonCode 'report-privacy-invalid'}
function Assert-AwxHashValue {param($Value);Assert-AwxHash64 -Value $Value -ReasonCode 'report-privacy-invalid'}
function Assert-AwxFiniteNonNegative {
    param($Value)
    if (-not (Test-AwxFiniteNumberRange $Value 0.0)) { throw 'report-privacy-invalid' }
}

function Assert-AwxPublicReasonArray {
    param($ReasonCodes,[string[]]$Policy)
    if($ReasonCodes -isnot [System.Array]){throw 'report-privacy-invalid'}
    $codes=@($ReasonCodes);$ordered=@($Policy|Where-Object{$codes -ccontains $_})
    if($codes.Count -eq 0 -or @($codes|Select-Object -Unique).Count -ne $codes.Count -or @($codes|Where-Object{$_ -isnot [string] -or $_ -cnotin $Policy}).Count -gt 0 -or -not(Test-AwxExactSequence $codes $ordered)){throw 'report-privacy-invalid'}
}

function Assert-AwxPublicPacketProjection {
    param($Packet)
    Assert-AwxReportPropertySet $Packet @('name','complete','passed','reasonCodes','runBindingSha256','evidenceSha256','packetSha256')
    if($Packet.name -cnotin @('SUPPORT_CONTRACT','SUPPORT_SCENARIO','FALSIFY') -or -not(Test-AwxStrictBoolean $Packet.complete) -or -not(Test-AwxStrictBoolean $Packet.passed)){throw 'report-privacy-invalid'}
    try{Assert-AwxReasonEnvelope $Packet.name $Packet.complete $Packet.passed $Packet.reasonCodes}catch{throw 'report-privacy-invalid'}
    foreach($name in @('runBindingSha256','evidenceSha256','packetSha256')){Assert-AwxHashValue $Packet.$name}
}

function Test-AwxNeutralPair {
    param($Verdict,$ReasonCode)
    if($Verdict -ceq 'APPLY'){return $ReasonCode -ceq 'all-packets-pass'}
    if($Verdict -ceq 'REJECT'){return $ReasonCode -ceq 'contract-or-counterexample-failed'}
    return $Verdict -ceq 'HOLD' -and $ReasonCode -cin @('packet-set-invalid','packet-incomplete','command-evidence-incomplete','scenario-evidence-incomplete')
}

function Assert-AwxPublicNeutralProjection {
    param($Neutral)
    Assert-AwxReportPropertySet $Neutral @('forward','forwardReasonCode','reverse','reverseReasonCode','orderStable','finalVerdict','finalReasonCode','runBindingSha256','commandEvidenceSha256')
    if(-not(Test-AwxStrictBoolean $Neutral.orderStable) -or -not(Test-AwxNeutralPair $Neutral.forward $Neutral.forwardReasonCode) -or -not(Test-AwxNeutralPair $Neutral.reverse $Neutral.reverseReasonCode)){throw 'report-privacy-invalid'}
    if($Neutral.orderStable){if($Neutral.forward -cne $Neutral.reverse -or $Neutral.forwardReasonCode -cne $Neutral.reverseReasonCode -or $Neutral.finalVerdict -cne $Neutral.forward -or $Neutral.finalReasonCode -cne $Neutral.forwardReasonCode){throw 'report-privacy-invalid'}}elseif($Neutral.finalVerdict -cne 'HOLD' -or $Neutral.finalReasonCode -cne 'order-unstable' -or ($Neutral.forward -ceq $Neutral.reverse -and $Neutral.forwardReasonCode -ceq $Neutral.reverseReasonCode)){throw 'report-privacy-invalid'}
    Assert-AwxHashValue $Neutral.runBindingSha256;Assert-AwxHashValue $Neutral.commandEvidenceSha256
}

function Assert-AwxReportPrivacy {
    param([Parameter(Mandatory)]$Report)
    $caseIds = @('fast-rewrite-ko','fast-json-extract','fast-instruction','main-grounded-ko','main-uncertainty-ko','main-policy-ko')
    $modelTags = @('qwen3:8b','qwen3.5:9b','gemma4:12b','gemma4:26b','qwen3.6:27b','gemma4:31b')
    $liveFailureReasons = @('candidate-digest-changed','response-json-invalid','response-schema-invalid','response-semantic-mismatch','response-truncated','lineage-missing','cpu-offload-detected','insufficient-vram','warm-sample-insufficient','portfolio-timeout','model-block-timeout','model-unload-unproven','gpu-lane-evidence-incomplete','gpu-release-unproven','ps-evidence-incomplete','tags-evidence-incomplete','native-probe-timeout','role-binding-mutated','concurrent-model-block-detected','benchmark-runtime-failed')
    Assert-AwxReportPropertySet $Report @('schemaVersion','benchmarkId','mode','durationMs','corpus','models','recommendations','packets','neutral','verdict','reasonCodes')
    if (-not(Test-AwxStrictIntegerRange $Report.schemaVersion 2 2) -or [string]$Report.benchmarkId -cne 'awx.desktop-ollama-benchmark.v2' -or [string]$Report.mode -cnotin @('validate-only','live') -or [string]$Report.verdict -cnotin @('APPLY','HOLD','REJECT') -or $Report.models -isnot [System.Array] -or $Report.recommendations -isnot [System.Array] -or $Report.packets -isnot [System.Array] -or $Report.reasonCodes -isnot [System.Array]) { throw 'report-privacy-invalid' }
    Assert-AwxFiniteNonNegative $Report.durationMs
    Assert-AwxReportPropertySet $Report.corpus @('corpusId','corpusSha256','ontologySha256','schemaSha256','oracleSha256','optionsSha256','promptHashes')
    if ([string]$Report.corpus.corpusId -cne 'awx.desktop-ollama-benchmark.v2') { throw 'report-privacy-invalid' }
    foreach ($name in @('corpusSha256','ontologySha256','schemaSha256','oracleSha256','optionsSha256')) { Assert-AwxHashValue $Report.corpus.$name }
    if (@($Report.corpus.promptHashes).Count -ne 6 -or -not (Test-AwxExactSequence @($Report.corpus.promptHashes.caseId) $caseIds)) { throw 'report-privacy-invalid' }
    foreach ($row in @($Report.corpus.promptHashes)) { Assert-AwxReportPropertySet $row @('caseId','promptSha256','schemaSha256'); Assert-AwxHashValue $row.promptSha256; Assert-AwxHashValue $row.schemaSha256 }
    if ($Report.mode -ceq 'validate-only') {
        if (@($Report.models).Count -ne 0 -or @($Report.recommendations).Count -ne 0 -or @($Report.packets).Count -ne 0 -or $null -ne $Report.neutral -or $Report.verdict -cne 'HOLD') { throw 'report-privacy-invalid' };Assert-AwxPublicReasonArray $Report.reasonCodes @('live-evidence-not-requested')
    } else {
        $liveFailureShape = @($Report.models).Count -eq 0 -and @($Report.recommendations).Count -eq 0 -and @($Report.packets).Count -eq 0 -and $null -eq $Report.neutral -and $Report.verdict -ceq 'HOLD'
        $liveCompleteShape = @($Report.models).Count -eq 6 -and (Test-AwxExactSequence @($Report.models.modelTag) $modelTags)
        if (-not $liveFailureShape -and -not $liveCompleteShape) { throw 'report-privacy-invalid' }
        if($liveFailureShape){if(@($Report.reasonCodes).Count -ne 1){throw 'report-privacy-invalid'};Assert-AwxPublicReasonArray $Report.reasonCodes $liveFailureReasons}
    }
    foreach ($model in @($Report.models)) {
        Assert-AwxReportPropertySet $model @('role','classification','modelTag','digest','endpointLabel','gpuLane','totalMiB','freeMiB','gpuResidentRatio','coldLoadMs','warmRoleP95Ms','completedWarmCount','balancedScore','scoreDelta','hardGatePassed','cleanupPassed','reasonCodes','caseTimings','semanticRates','coldResult','warmResults')
        if ($model.role -cnotin @('fast','main') -or $model.classification -cnotin @('baseline','primary','challenger') -or $model.modelTag -cnotin $modelTags -or $model.endpointLabel -cnotin @('fast-11435','primary-11434') -or $model.gpuLane -cnotin @('RTX 3060','RTX 3090')) { throw 'report-privacy-invalid' }
        $roleIds = $(if ($model.role -ceq 'fast') {@('fast-rewrite-ko','fast-json-extract','fast-instruction')} else {@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
        if ($model.hardGatePassed -isnot [bool] -or $model.cleanupPassed -isnot [bool] -or @($model.coldResult).Count -ne 1 -or $model.coldResult.caseId -cne $roleIds[0] -or $model.warmResults -isnot [System.Array] -or @($model.warmResults).Count -ne 21 -or -not(Test-AwxStrictIntegerRange $model.completedWarmCount 21 21) -or @($model.caseTimings).Count -ne 3 -or -not (Test-AwxExactSequence @($model.caseTimings.caseId) $roleIds) -or @($model.semanticRates).Count -ne 3 -or -not (Test-AwxExactSequence @($model.semanticRates.caseId) $roleIds)) { throw 'report-privacy-invalid' }
        foreach($id in $roleIds){$rows=@($model.warmResults|Where-Object caseId -CEQ $id);if($rows.Count -ne 7 -or @($rows|Where-Object{-not(Test-AwxStrictIntegerRange $_.repetition 1 7)}).Count -gt 0 -or -not(Test-AwxExactSequence @($rows.repetition|Sort-Object) @(1..7))){throw 'report-privacy-invalid'}}
        Assert-AwxHashValue $model.digest
        foreach ($name in @('totalMiB','freeMiB','gpuResidentRatio','coldLoadMs','warmRoleP95Ms','completedWarmCount')) { Assert-AwxFiniteNonNegative $model.$name }
        if(-not(Test-AwxFiniteNumberRange $model.gpuResidentRatio 0.0 1.0)){throw 'report-privacy-invalid'}
        if($null -ne $model.balancedScore){try{[void](ConvertTo-AwxScoreMicrounits -Score $model.balancedScore)}catch{throw 'report-privacy-invalid'}}
        if($null -ne $model.scoreDelta){try{[void](ConvertTo-AwxScoreMicrounits -Score $model.scoreDelta -AllowSigned)}catch{throw 'report-privacy-invalid'}}
        try{Assert-AwxHardGateReasonEnvelope $model.hardGatePassed $model.reasonCodes}catch{throw 'report-privacy-invalid'}
        foreach ($timing in @($model.caseTimings)) { Assert-AwxReportPropertySet $timing @('caseId','p50Ms'); if ($timing.caseId -cnotin $caseIds) {throw 'report-privacy-invalid'}; Assert-AwxFiniteNonNegative $timing.p50Ms }
        foreach ($rate in @($model.semanticRates)) { Assert-AwxReportPropertySet $rate @('caseId','rate'); if ($rate.caseId -cnotin $caseIds -or -not(Test-AwxFiniteNumberRange $rate.rate 0.0 1.0)) {throw 'report-privacy-invalid'} }
        foreach ($sample in @($model.coldResult) + @($model.warmResults)) {
            $names = @('caseId','latencyMs','requestSha256','responseSha256','responseLength','tokenCount','transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed','thinkingLength','doneReason','reasonCode')
            if ($sample.PSObject.Properties['repetition']) { $names += 'repetition' }
            Assert-AwxReportPropertySet $sample $names
            $sampleReasons=@('pass','response-json-invalid','response-schema-invalid','response-semantic-mismatch','response-truncated','lineage-missing','model-block-timeout','portfolio-timeout')
            if ($sample.caseId -cnotin $caseIds -or $sample.doneReason -cnotin @('stop','length') -or $sample.reasonCode -isnot [string] -or $sample.reasonCode -cnotin $sampleReasons) { throw 'report-privacy-invalid' }
            foreach ($name in @('transportPassed','lineagePassed','jsonParsed','schemaPassed','semanticPassed')) { if ($sample.$name -isnot [bool]) {throw 'report-privacy-invalid'} }
            if ($sample.PSObject.Properties['repetition'] -and -not(Test-AwxStrictIntegerRange $sample.repetition 1 7)) { throw 'report-privacy-invalid' }
            if($sample.semanticPassed -and $sample.reasonCode -cne 'pass'){throw 'report-privacy-invalid'}
            if($sample.reasonCode -ceq 'pass' -and (-not $sample.transportPassed -or -not $sample.lineagePassed -or -not $sample.jsonParsed -or -not $sample.schemaPassed -or -not $sample.semanticPassed -or $sample.doneReason -cne 'stop')){throw 'report-privacy-invalid'}
            Assert-AwxHashValue $sample.requestSha256; Assert-AwxHashValue $sample.responseSha256
            Assert-AwxFiniteNonNegative $sample.latencyMs
            foreach ($name in @('responseLength','thinkingLength')) { if(-not(Test-AwxStrictIntegerRange $sample.$name 0 ([long]::MaxValue))){throw 'report-privacy-invalid'} }
            if ($null -ne $sample.tokenCount -and -not(Test-AwxStrictIntegerRange $sample.tokenCount 0 ([long]::MaxValue))) {throw 'report-privacy-invalid'}
        }
    }
    foreach ($recommendation in @($Report.recommendations)) {
        Assert-AwxReportPropertySet $recommendation @('role','status','modelTag','scoreDelta','reasonCode');if($recommendation.role -cnotin @('fast','main')){throw 'report-privacy-invalid'};$baselineTag=$(if($recommendation.role -ceq 'fast'){'qwen3:8b'}else{'gemma4:26b'});$candidateTags=$(if($recommendation.role -ceq 'fast'){@('qwen3.5:9b','gemma4:12b')}else{@('qwen3.6:27b','gemma4:31b')})
        $deltaMicros=$null;if($null -ne $recommendation.scoreDelta){try{$deltaMicros=ConvertTo-AwxScoreMicrounits -Score $recommendation.scoreDelta}catch{throw 'report-privacy-invalid'}}
        if($recommendation.status -ceq 'HOLD'){if($recommendation.reasonCode -cne 'baseline-evidence-incomplete' -or $null -ne $recommendation.modelTag -or $null -ne $recommendation.scoreDelta){throw 'report-privacy-invalid'}}
        elseif($recommendation.status -ceq 'keep-baseline'){if($recommendation.reasonCode -cnotin @('no-eligible-candidate','candidate-tie') -or $recommendation.modelTag -cne $baselineTag -or $deltaMicros -ne 0){throw 'report-privacy-invalid'}}
        elseif($recommendation.status -ceq 'recommend-candidate'){if($recommendation.reasonCode -cne 'promotion-approval-required' -or $recommendation.modelTag -cnotin $candidateTags -or $null -eq $deltaMicros -or $deltaMicros -le 0){throw 'report-privacy-invalid'}}else{throw 'report-privacy-invalid'}
    }
    foreach ($packet in @($Report.packets)) { Assert-AwxPublicPacketProjection $packet }
    if ($Report.mode -ceq 'live' -and @($Report.models).Count -eq 6) {
        if (@($Report.recommendations).Count -ne 2 -or -not (Test-AwxExactSequence @($Report.recommendations.role) @('fast','main')) -or @($Report.packets).Count -ne 3 -or -not (Test-AwxExactSequence @($Report.packets.name) @('SUPPORT_CONTRACT','SUPPORT_SCENARIO','FALSIFY')) -or $null -eq $Report.neutral -or [string]$Report.verdict -cne [string]$Report.neutral.finalVerdict -or @($Report.packets|Where-Object runBindingSha256 -CNE $Report.neutral.runBindingSha256).Count -ne 0 -or @($Report.reasonCodes).Count -ne 1 -or [string]$Report.reasonCodes[0] -cne [string]$Report.neutral.finalReasonCode) { throw 'report-privacy-invalid' }
        Assert-AwxPublicReasonArray $Report.reasonCodes @([string]$Report.neutral.finalReasonCode)
        $fixedModels = @(Get-AwxBenchmarkModelMatrix)
        for ($index=0; $index -lt 6; $index++) {
            foreach ($field in @('role','classification','modelTag','digest','endpointLabel','gpuLane')) {
                if ([string]$Report.models[$index].$field -cne [string]$fixedModels[$index].$field) { throw 'report-privacy-invalid' }
            }
        }
    }
    if ($null -ne $Report.neutral) { Assert-AwxPublicNeutralProjection $Report.neutral }
    $json = ConvertTo-AwxCanonicalJson $Report
    if ($json -match '(?i)raw(prompt|response)|authorization|cookie|credential|owner.?token|model.?store|correctAnswer|GPU-[0-9a-f-]{16,}|\b(?:sk|pk)-[A-Za-z0-9_-]{8,}|[A-Za-z]:\\|\\\\') { throw 'report-privacy-invalid' }
}
```

Do not add a generic `note`, `message`, `error`, `path`, or `details` field. Every failure must be represented by an allowlisted bounded reason code.

- [ ] **Step 5: Build the validate-only and live public DTOs**

Add `New-AwxValidateOnlyReport` and `New-AwxLiveReport` as explicit constructors. The validate-only constructor is exact:

```powershell
function New-AwxValidateOnlyReport {
    param([Parameter(Mandatory)]$Corpus,[Parameter(Mandatory)]$Oracle,[Parameter(Mandatory)][string]$CorpusPath,[Parameter(Mandatory)][string]$OraclePath)
    $schemaHashes = @($Corpus.cases | ForEach-Object { Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $_.responseSchema) })
    $promptHashes = @($Corpus.cases | ForEach-Object { [pscustomobject]@{caseId=$_.id;promptSha256=Get-AwxUtf8Sha256 ([string]$_.prompt);schemaSha256=$schemaHashes[[array]::IndexOf(@($Corpus.cases.id),$_.id)]} })
    return [pscustomobject][ordered]@{
        schemaVersion=2;benchmarkId='awx.desktop-ollama-benchmark.v2';mode='validate-only';durationMs=0
        corpus=[pscustomobject][ordered]@{
            corpusId=$Corpus.corpusId;corpusSha256=Get-AwxFileSha256 $CorpusPath
            ontologySha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.ontologies)
            schemaSha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $schemaHashes)
            oracleSha256=Get-AwxFileSha256 $OraclePath
            optionsSha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.options)
            promptHashes=$promptHashes
        }
        models=@();recommendations=@();packets=@();neutral=$null;verdict='HOLD';reasonCodes=@('live-evidence-not-requested')
    }
}
```

Implement the live constructor as an explicit projection; never attach internal/provider objects wholesale:

```powershell
function New-AwxLiveReport {
    param(
        [Parameter(Mandatory)]$Corpus,[Parameter(Mandatory)][string]$CorpusPath,[Parameter(Mandatory)][string]$OraclePath,
        [Parameter(Mandatory)][object[]]$Models,[Parameter(Mandatory)][object[]]$EnrichedRows,
        [Parameter(Mandatory)][object[]]$Recommendations,[Parameter(Mandatory)][object[]]$Packets,
        [Parameter(Mandatory)]$Neutral,[Parameter(Mandatory)][double]$DurationMs,
        [Parameter(Mandatory)][ValidateSet('APPLY','HOLD','REJECT')][string]$Verdict,[Parameter(Mandatory)][string[]]$ReasonCodes
    )
    $schemaHashes = @($Corpus.cases | ForEach-Object { Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $_.responseSchema) })
    $promptHashes = @($Corpus.cases | ForEach-Object { [pscustomobject]@{caseId=$_.id;promptSha256=Get-AwxUtf8Sha256 ([string]$_.prompt);schemaSha256=$schemaHashes[[array]::IndexOf(@($Corpus.cases.id),$_.id)]} })
    $publicModels = foreach ($fixed in $Models) {
        $match = @($EnrichedRows | Where-Object modelTag -CEQ $fixed.modelTag)
        if ($match.Count -ne 1) { throw 'warm-sample-insufficient' }
        $entry = $match[0]; $run = $entry.run; $metrics = $entry.metrics
        $roleIds = $(if ($fixed.role -ceq 'fast') {@('fast-rewrite-ko','fast-json-extract','fast-instruction')} else {@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
        $caseTimings = foreach ($id in $roleIds) { [pscustomobject]@{caseId=$id;p50Ms=[double]$metrics.warmCaseP50Ms.($id)} }
        $semanticRates = foreach ($id in $roleIds) { [pscustomobject]@{caseId=$id;rate=[double]$metrics.semanticRates.($id)} }
        $cold = [pscustomobject][ordered]@{
            caseId=$run.cold.caseId;latencyMs=$run.cold.latencyMs;requestSha256=$run.cold.requestSha256;responseSha256=$run.cold.responseSha256
            responseLength=$run.cold.responseLength;tokenCount=$run.cold.tokenCount;transportPassed=$run.cold.transportPassed
            lineagePassed=$run.cold.lineagePassed;jsonParsed=$run.cold.jsonParsed;schemaPassed=$run.cold.schemaPassed
            semanticPassed=$run.cold.semanticPassed;thinkingLength=$run.cold.thinkingLength;doneReason=$run.cold.doneReason;reasonCode=$run.cold.reasonCode
        }
        $warm = foreach ($sample in @($run.warm)) {
            [pscustomobject][ordered]@{
                caseId=$sample.caseId;repetition=$sample.repetition;latencyMs=$sample.latencyMs;requestSha256=$sample.requestSha256;responseSha256=$sample.responseSha256
                responseLength=$sample.responseLength;tokenCount=$sample.tokenCount;transportPassed=$sample.transportPassed
                lineagePassed=$sample.lineagePassed;jsonParsed=$sample.jsonParsed;schemaPassed=$sample.schemaPassed
                semanticPassed=$sample.semanticPassed;thinkingLength=$sample.thinkingLength;doneReason=$sample.doneReason;reasonCode=$sample.reasonCode
            }
        }
        [pscustomobject][ordered]@{
            role=$fixed.role;classification=$fixed.classification;modelTag=$fixed.modelTag;digest=$fixed.digest
            endpointLabel=$fixed.endpointLabel;gpuLane=$fixed.gpuLane;totalMiB=[int]$run.lane.totalMiB;freeMiB=[int]$run.lane.freeMiB
            gpuResidentRatio=[double]$run.loaded.gpuResidentRatio;coldLoadMs=[double]$metrics.coldLoadMs;warmRoleP95Ms=[double]$metrics.warmRoleP95Ms
            completedWarmCount=$metrics.completedWarmCount;balancedScore=$entry.balancedScore;scoreDelta=$entry.scoreDelta
            hardGatePassed=[bool]$entry.hardGate.passed;cleanupPassed=[bool]($run.cleanup.psEmpty -and $run.cleanup.gpuReleased)
            reasonCodes=@($entry.hardGate.reasonCodes);caseTimings=@($caseTimings);semanticRates=@($semanticRates);coldResult=$cold;warmResults=@($warm)
        }
    }
    $publicRecommendations = @($Recommendations | ForEach-Object { [pscustomobject][ordered]@{role=$_.role;status=$_.status;modelTag=$_.modelTag;scoreDelta=$_.scoreDelta;reasonCode=$_.reasonCode} })
    $publicPackets = @($Packets | ForEach-Object { ConvertTo-AwxPublicPacket $_ })
    $report = [pscustomobject][ordered]@{
        schemaVersion=2;benchmarkId='awx.desktop-ollama-benchmark.v2';mode='live';durationMs=[Math]::Round($DurationMs,3)
        corpus=[pscustomobject][ordered]@{corpusId=$Corpus.corpusId;corpusSha256=Get-AwxFileSha256 $CorpusPath;ontologySha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.ontologies);schemaSha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $schemaHashes);oracleSha256=Get-AwxFileSha256 $OraclePath;optionsSha256=Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.options);promptHashes=$promptHashes}
        models=@($publicModels);recommendations=$publicRecommendations;packets=$publicPackets
        neutral=ConvertTo-AwxPublicNeutral $Neutral
        verdict=$Verdict;reasonCodes=@($ReasonCodes | Select-Object -Unique)
    }
    Assert-AwxReportPrivacy $report
    return $report
}
```

This constructor publishes all 126 warm case results as bounded DTOs so parse/schema/semantic/reason/hash lineage remains auditable. The one cold result per model is clearly separate and never enters the warm aggregates.

- [ ] **Step 6: Implement the production adapters and pure falsification probes**

```powershell
function Get-AwxLocalRemainingSec {
    param([Parameter(Mandatory)]$Clock,[Parameter(Mandatory)][int]$TimeoutSec)
    $remaining = [Math]::Floor($TimeoutSec - $Clock.Elapsed.TotalSeconds)
    if ($remaining -lt 1) { throw 'native-probe-timeout' }
    return [int]$remaining
}

function Get-AwxUserBindingGuardHash {
    $state = [ordered]@{}
    foreach ($name in @('LLM_FAST_MODEL','LLM_CHAT_MODEL','LLM_HIGH_MODEL','LLM_CODER_MODEL')) {
        $value = [Environment]::GetEnvironmentVariable($name,'User')
        $state[$name] = [ordered]@{present=($null -ne $value);valueSha256=Get-AwxUtf8Sha256 ([string]$value)}
    }
    return Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $state)
}

function New-AwxProductionAdapters {
    return [pscustomobject]@{
        VerifyInstalled = {
            param($model,$timeout)
            Test-AwxInstalledDigest -Rows @(Get-AwxOllamaTags -Endpoint $model.endpoint -TimeoutSec $timeout) -ModelTag $model.modelTag -ExpectedDigest $model.digest
        }
        PsEmpty = { param($model,$timeout) @(Get-AwxOllamaPs -Endpoint $model.endpoint -TimeoutSec $timeout).Count -eq 0 }
        Gpu = { param($phase,$model,$timeout) @(Get-AwxGpuSnapshot -TimeoutSec $timeout) }
        Loaded = {
            param($model,$timeout)
            $clock = [Diagnostics.Stopwatch]::StartNew()
            $remaining = Get-AwxLocalRemainingSec $clock $timeout
            $evidence = Get-AwxLoadedModelEvidence -Rows @(Get-AwxOllamaPs -Endpoint $model.endpoint -TimeoutSec $remaining) -ModelTag $model.modelTag -ExpectedContext 8192
            $remaining = Get-AwxLocalRemainingSec $clock $timeout
            $ids = @(Get-AwxEndpointProcessIds -Endpoint $model.endpoint -TimeoutSec $remaining)
            $remaining = Get-AwxLocalRemainingSec $clock $timeout
            $compute = @(Get-AwxGpuComputeRows -TimeoutSec $remaining)
            $evidence | Add-Member -NotePropertyName endpointProcessIds -NotePropertyValue $ids
            $evidence | Add-Member -NotePropertyName computeRows -NotePropertyValue $compute
            $evidence
        }
        Chat = {
            param($model,$case,$body,$timeout)
            Assert-AwxOutboundBodyClean $body
            $clock = [Diagnostics.Stopwatch]::StartNew()
            $response = Invoke-AwxOllamaHttp -Endpoint $model.endpoint -Path '/api/chat' -Method POST -Body $body -TimeoutSec $timeout
            $clock.Stop()
            [pscustomobject]@{latencyMs=[Math]::Round($clock.Elapsed.TotalMilliseconds,3);ollamaResponse=$response}
        }
        Unload = { param($model,$timeout) Stop-AwxOllamaModel -Model $model -TimeoutSec $timeout }
        GpuReleased = {
            param($model,$processIds,$timeout)
            (Test-AwxGpuRelease -EndpointProcessIds @($processIds) -ComputeRows @(Get-AwxGpuComputeRows -TimeoutSec $timeout)).passed
        }
    }
}

function Test-AwxCompleteHashSet {
    param([Parameter(Mandatory)][object[]]$Hashes,[Parameter(Mandatory)][int]$ExpectedCount)
    $values = @($Hashes)
    return $values.Count -eq $ExpectedCount -and @($values | Where-Object { [string]$_ -cnotmatch '^[a-f0-9]{64}$' }).Count -eq 0
}

function Invoke-AwxFalsificationProbes {
    param([Parameter(Mandatory)]$Corpus,[Parameter(Mandatory)]$Oracle)
    $checks = [ordered]@{
        constRejected=$false;shapeOnlyRejected=$false;extraValueRejected=$false;truncationRejected=$false
        timeoutRejected=$false;missingHashRejected=$false;orderRejected=$false;oracleIsolated=$false;p95Correct=$false
    }
    $mutated = $Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
    $mutated.cases[0].responseSchema | Add-Member -NotePropertyName const -NotePropertyValue 'never-send'
    try { Assert-AwxBenchmarkContract -Corpus $mutated -Oracle $Oracle } catch { $checks.constRejected = $_.Exception.Message -ceq 'const-forbidden' }
    $case = $Corpus.cases | Where-Object id -CEQ 'fast-json-extract'; $expected = ($Oracle.cases | Where-Object id -CEQ 'fast-json-extract').expected
    $shapeOnly = [pscustomobject]@{model='qwen3.5:9b';done_reason='stop';eval_count=8;message=[pscustomobject]@{thinking='';content='{"gpu":"RTX 3090","role":"main","port":11435}'}}
    $shapeResult = Test-AwxBenchmarkResponse -Case $case -Expected $expected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $shapeOnly
    $checks.shapeOnlyRejected = $shapeResult.schemaPassed -and -not $shapeResult.semanticPassed
    $policyCase = $Corpus.cases | Where-Object id -CEQ 'main-policy-ko'; $policyExpected = ($Oracle.cases | Where-Object id -CEQ 'main-policy-ko').expected
    $extra = [pscustomobject]@{model='qwen3.6:27b';done_reason='stop';eval_count=8;message=[pscustomobject]@{thinking='';content='{"decision":"REJECT","reasonCodes":["CPU_OFFLOAD","QUALITY_REGRESSION","LINEAGE_MISSING"]}'}}
    $checks.extraValueRejected = -not (Test-AwxBenchmarkResponse -Case $policyCase -Expected $policyExpected -ExpectedModel 'qwen3.6:27b' -OllamaResponse $extra).semanticPassed
    $truncated = [pscustomobject]@{model='qwen3.5:9b';done_reason='length';eval_count=8;message=[pscustomobject]@{thinking='';content='{"gpu":"RTX 3060","role":"fast","port":11435}'}}
    $checks.truncationRejected = (Test-AwxBenchmarkResponse -Case $case -Expected $expected -ExpectedModel 'qwen3.5:9b' -OllamaResponse $truncated).reasonCode -ceq 'response-truncated'
    $expiredBlock = [pscustomobject]@{Elapsed=[timespan]::FromSeconds(6)}
    $freshPortfolio = [pscustomobject]@{Elapsed=[timespan]::Zero}
    try { [void](Get-AwxRemainingTimeoutSec 120 $expiredBlock 5 $freshPortfolio 7200) } catch { $checks.timeoutRejected = $_.Exception.Message -ceq 'model-block-timeout' }
    $checks.missingHashRejected = -not (Test-AwxCompleteHashSet -Hashes @((Get-AwxUtf8Sha256 'present-bounded-hash'),'missing') -ExpectedCount 2)
    $orderMutated = $Corpus | ConvertTo-Json -Depth 40 | ConvertFrom-Json
    $orderMutated.ontologies.gpu = @('RTX 3090','RTX 3060')
    try { Assert-AwxBenchmarkContract -Corpus $orderMutated -Oracle $Oracle } catch { $checks.orderRejected = $_.Exception.Message -ceq 'ontology-narrowed' }
    $body1 = New-AwxBenchmarkRequestBody -Case $case -ModelTag 'qwen3.5:9b' -Options $Corpus.options
    $changedOracle = $Oracle | ConvertTo-Json -Depth 40 | ConvertFrom-Json; $changedOracle.cases[1].expected.gpu = 'ORACLE_SENTINEL_NEVER_SEND'
    $body2 = New-AwxBenchmarkRequestBody -Case $case -ModelTag 'qwen3.5:9b' -Options $Corpus.options
    $checks.oracleIsolated = (Get-AwxUtf8Sha256 $body1) -ceq (Get-AwxUtf8Sha256 $body2) -and $body2 -notmatch 'ORACLE_SENTINEL_NEVER_SEND'
    $checks.p95Correct = (Get-AwxNearestRank -Values (1..21) -Percentile 0.95) -eq 20
    return [pscustomobject]$checks
}
```

- [ ] **Step 7: Implement the exact live composition and bounded failure report**

```powershell
function New-AwxLiveFailureReport {
    param($Corpus,[string]$CorpusPath,[string]$OraclePath,[double]$DurationMs,[string]$ReasonCode)
    $report = New-AwxValidateOnlyReport -Corpus $Corpus -Oracle ([pscustomobject]@{}) -CorpusPath $CorpusPath -OraclePath $OraclePath
    $report.mode='live';$report.durationMs=[Math]::Round($DurationMs,3);$report.verdict='HOLD';$report.reasonCodes=@($ReasonCode)
    Assert-AwxReportPrivacy $report
    return $report
}

function Invoke-AwxLiveBenchmarkReport {
    param(
        [Parameter(Mandatory)]$Corpus,[Parameter(Mandatory)]$Oracle,[Parameter(Mandatory)][string]$CorpusPath,[Parameter(Mandatory)][string]$OraclePath,
        [Parameter(Mandatory)][object[]]$Models,[int]$PortfolioTimeoutSec=7200
    )
    $runClock = [Diagnostics.Stopwatch]::StartNew(); $adapters = New-AwxProductionAdapters; $bindingBefore = Get-AwxUserBindingGuardHash
    try {
        Assert-AwxBenchmarkContract -Corpus $Corpus -Oracle $Oracle
        foreach ($model in $Models) { if (-not (& $adapters.VerifyInstalled $model 15).passed) {throw 'candidate-digest-changed'} }
        foreach ($model in @($Models | Group-Object endpoint | ForEach-Object {$_.Group[0]})) { if (-not (& $adapters.PsEmpty $model 15)) {throw 'model-unload-unproven'} }
        $runner = { param($model,$portfolioClock,$limit) Invoke-AwxModelBlock -Model $model -Corpus $Corpus -Oracle $Oracle -Adapters $adapters -PortfolioClock $portfolioClock -BlockTimeoutSec 1800 -PortfolioTimeoutSec $limit }
        $emergency = { param($model,$timeout) & $adapters.Unload $model $timeout }
        $runs = @(Invoke-AwxBenchmarkPortfolio -Models $Models -ModelBlockRunner $runner -EmergencyUnloader $emergency -PortfolioTimeoutSec $PortfolioTimeoutSec)
        if ($runs.Count -ne 6) { throw 'warm-sample-insufficient' }
        $staticHashes = @(
            Get-AwxFileSha256 $CorpusPath
            Get-AwxFileSha256 $OraclePath
            Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.ontologies)
            Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.options)
        ) + @($Corpus.cases | ForEach-Object {Get-AwxUtf8Sha256 ([string]$_.prompt);Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $_.responseSchema)})
        $enriched = [Collections.Generic.List[object]]::new()
        foreach ($model in $Models) {
            $run = @($runs | Where-Object modelTag -CEQ $model.modelTag)
            if ($run.Count -ne 1) { throw 'warm-sample-insufficient' }
            $metrics = Get-AwxModelMetrics -ColdResult $run[0].cold -WarmResults @($run[0].warm) -LoadedFreeMiB ([Math]::Min([double]$run[0].lane.freeMiB,[double]$run[0].endLane.freeMiB))
            $hashes = @($run[0].cold.requestSha256,$run[0].cold.responseSha256) + @($run[0].warm | ForEach-Object {$_.requestSha256;$_.responseSha256})
            $evidence = [pscustomobject]@{
                digestPassed=$true;lanePassed=([bool]$run[0].lane.passed -and [bool]$run[0].endLane.passed -and [bool]$run[0].cleanup.psEmpty -and [bool]$run[0].cleanup.gpuReleased)
                lineagePassed=([bool]$run[0].cold.lineagePassed -and @($run[0].warm | Where-Object {-not $_.lineagePassed}).Count -eq 0)
                hashesComplete=((Test-AwxCompleteHashSet -Hashes $staticHashes -ExpectedCount 16) -and (Test-AwxCompleteHashSet -Hashes $hashes -ExpectedCount 44))
                completedColdCount=$(if($run[0].cold.transportPassed){1}else{0});completedWarmCount=@($run[0].warm | Where-Object transportPassed).Count
                caseSampleCountsPassed=$true;warmJsonSchemaPassedCount=@($run[0].warm | Where-Object {$_.jsonParsed -and $_.schemaPassed}).Count
                nonEmptyThinkingCount=@($run[0].warm | Where-Object thinkingLength -ne 0).Count;truncatedCount=@($run[0].warm | Where-Object doneReason -EQ 'length').Count
                gpuResidentRatio=[Math]::Min([double]$run[0].loaded.gpuResidentRatio,[double]$run[0].endLoaded.gpuResidentRatio)
                loadedFreeMiB=[Math]::Min([double]$run[0].lane.freeMiB,[double]$run[0].endLane.freeMiB)
                endpointRestarted=[bool]$run[0].endpointRestarted;timeoutCount=@($run[0].warm | Where-Object reasonCode -in @('model-block-timeout','portfolio-timeout')).Count
            }
            $gate = Test-AwxModelHardGate -Role $model.role -Evidence $evidence
            $enriched.Add([pscustomobject]@{modelTag=$model.modelTag;classification=$model.classification;role=$model.role;run=$run[0];metrics=$metrics;evidence=$evidence;hardGate=$gate;balancedScore=$null;scoreDelta=$null})
        }
        foreach ($role in @('fast','main')) {
            $eligible = @($enriched | Where-Object {$_.role -ceq $role -and $_.hardGate.passed})
            if ($eligible.Count -gt 0) {
                $minP95 = ($eligible.metrics.warmRoleP95Ms | Measure-Object -Minimum).Minimum; $maxFree = ($eligible.metrics.loadedFreeMiB | Measure-Object -Maximum).Maximum
                foreach ($entry in $eligible) { $entry.balancedScore = (Get-AwxRoleScore -Role $role -Metrics $entry.metrics -MinEligibleWarmRoleP95Ms $minP95 -MaxEligibleLoadedFreeMiB $maxFree).balancedScore }
            }
            $baseline = @($enriched | Where-Object {$_.role -ceq $role -and $_.classification -ceq 'baseline'})
            if($baseline.Count -eq 1 -and $null -ne $baseline[0].balancedScore){
                $baselineMicros=ConvertTo-AwxScoreMicrounits -Score $baseline[0].balancedScore
                foreach($entry in @($enriched|Where-Object {$_.role -ceq $role -and $null -ne $_.balancedScore})){
                    $entryMicros=ConvertTo-AwxScoreMicrounits -Score $entry.balancedScore;$entry.scoreDelta=[double](($entryMicros-$baselineMicros)/1000000.0)
                }
            }
        }
        $recommendations = foreach ($role in @('fast','main')) {
            $rows = @($enriched | Where-Object role -CEQ $role | ForEach-Object {[pscustomobject]@{classification=$_.classification;modelTag=$_.modelTag;hardGatePassed=$_.hardGate.passed;balancedScore=$_.balancedScore;rewriteAndExtractionQuality=$_.metrics.rewriteAndExtractionQuality;koreanRagQuality=$_.metrics.koreanRagQuality;warmRoleP95Ms=$_.metrics.warmRoleP95Ms}})
            Select-AwxRoleRecommendation -Role $role -ModelRows $rows
        }
        $schemaHashes=@($Corpus.cases|ForEach-Object{Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $_.responseSchema)})
        $blockRefs=for($index=0;$index -lt 6;$index++){
            if($runs[$index].modelTag -cne $Models[$index].modelTag){throw 'warm-sample-insufficient'}
            [pscustomobject][ordered]@{modelTag=$Models[$index].modelTag;digest=$Models[$index].digest;blockEvidenceSha256=$runs[$index].blockEvidenceSha256}
        }
        $runBinding=New-AwxRunBinding -BenchmarkId 'awx.desktop-ollama-benchmark.v2' -CorpusSha256 (Get-AwxFileSha256 $CorpusPath) -OracleSha256 (Get-AwxFileSha256 $OraclePath) -OntologySha256 (Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.ontologies)) -SchemaSha256 (Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $schemaHashes)) -OptionsSha256 (Get-AwxUtf8Sha256 (ConvertTo-AwxCanonicalJson $Corpus.options)) -ModelBlocks @($blockRefs)
        $digestChecks=@($Models|ForEach-Object{[pscustomobject][ordered]@{modelTag=$_.modelTag;passed=$true}})
        $contractEvidence=[pscustomobject][ordered]@{
            contractValidated=$true;digestChecks=$digestChecks
            hashCompletenessChecks=@($enriched|ForEach-Object{[pscustomobject][ordered]@{modelTag=$_.modelTag;passed=[bool]$_.evidence.hashesComplete}})
            lineageChecks=@($enriched|ForEach-Object{[pscustomobject][ordered]@{modelTag=$_.modelTag;passed=[bool]$_.evidence.lineagePassed}})
        }
        $scenarioModels=@($enriched|ForEach-Object{
            $entry=$_;$ids=$(if($entry.role -ceq 'fast'){@('fast-rewrite-ko','fast-json-extract','fast-instruction')}else{@('main-grounded-ko','main-uncertainty-ko','main-policy-ko')})
            [pscustomobject][ordered]@{role=$entry.role;classification=$entry.classification;modelTag=$entry.modelTag;hardGatePassed=[bool]$entry.hardGate.passed;hardGateReasonCodes=@($entry.hardGate.reasonCodes);warmRoleP95Ms=[double]$entry.metrics.warmRoleP95Ms;loadedFreeMiB=[double]$entry.metrics.loadedFreeMiB;balancedScore=$entry.balancedScore;semanticRates=@($ids|ForEach-Object{[pscustomobject][ordered]@{caseId=$_;rate=[double]$entry.metrics.semanticRates.($_)}})}
        })
        $scenarioEvidence=[pscustomobject][ordered]@{models=$scenarioModels;recommendations=@($recommendations|ForEach-Object{[pscustomobject][ordered]@{role=$_.role;status=$_.status;modelTag=$_.modelTag;scoreDelta=$_.scoreDelta;reasonCode=$_.reasonCode}})}
        $falsifyEvidence=Invoke-AwxFalsificationProbes -Corpus $Corpus -Oracle $Oracle
        $packets=@(New-AwxEvidencePackets -RunBinding $runBinding -ContractEvidence $contractEvidence -ScenarioEvidence $scenarioEvidence -FalsifyEvidence $falsifyEvidence)
        $packetOrder=@('SUPPORT_CONTRACT','SUPPORT_SCENARIO','FALSIFY');if(-not(Test-AwxExactSequence @($packets.name) $packetOrder)){throw 'packet-set-invalid'}
        $completedChecks=@($runs|ForEach-Object{[pscustomobject][ordered]@{modelTag=$_.modelTag;passed=([string]$_.blockEvidenceSha256 -cmatch '^[a-f0-9]{64}$')}})
        $finalEmptyChecks=@(
            [pscustomobject][ordered]@{endpointLabel='fast-11435';passed=[bool](& $adapters.PsEmpty ($Models|Where-Object endpointLabel -CEQ 'fast-11435'|Select-Object -First 1) 15)},
            [pscustomobject][ordered]@{endpointLabel='primary-11434';passed=[bool](& $adapters.PsEmpty ($Models|Where-Object endpointLabel -CEQ 'primary-11434'|Select-Object -First 1) 15)}
        )
        $bindingAfter=Get-AwxUserBindingGuardHash;$bindingUnchanged=$bindingBefore -ceq $bindingAfter
        $provisionalFacts=[pscustomobject][ordered]@{digestChecks=$digestChecks;completedBlockChecks=$completedChecks;finalEmptyPsChecks=$finalEmptyChecks;privacyProjectionChecked=$false;privacyProjectionPassed=$false;bindingGuardChecked=$true;bindingGuardUnchanged=$bindingUnchanged}
        $provisionalCommand=New-AwxCommandEvidence -RunBinding $runBinding -Evidence $provisionalFacts
        $provisionalNeutral=Get-AwxOrderStableNeutralVerdict -Packets $packets -CommandEvidence $provisionalCommand
        if(-not(Test-AwxExactSequence @($packets.name) $packetOrder)){throw 'packet-set-invalid'}
        $draft=New-AwxLiveReport -Corpus $Corpus -CorpusPath $CorpusPath -OraclePath $OraclePath -Models $Models -EnrichedRows @($enriched) -Recommendations @($recommendations) -Packets $packets -Neutral $provisionalNeutral -DurationMs $runClock.Elapsed.TotalMilliseconds -Verdict $provisionalNeutral.finalVerdict -ReasonCodes @($provisionalNeutral.finalReasonCode)
        Assert-AwxReportPrivacy $draft
        $finalFacts=[pscustomobject][ordered]@{digestChecks=$digestChecks;completedBlockChecks=$completedChecks;finalEmptyPsChecks=$finalEmptyChecks;privacyProjectionChecked=$true;privacyProjectionPassed=$true;bindingGuardChecked=$true;bindingGuardUnchanged=$bindingUnchanged}
        $commandEvidence=New-AwxCommandEvidence -RunBinding $runBinding -Evidence $finalFacts
        $neutral=Get-AwxOrderStableNeutralVerdict -Packets $packets -CommandEvidence $commandEvidence
        if(-not(Test-AwxExactSequence @($packets.name) $packetOrder)){throw 'packet-set-invalid'}
        $report=New-AwxLiveReport -Corpus $Corpus -CorpusPath $CorpusPath -OraclePath $OraclePath -Models $Models -EnrichedRows @($enriched) -Recommendations @($recommendations) -Packets $packets -Neutral $neutral -DurationMs $runClock.Elapsed.TotalMilliseconds -Verdict $neutral.finalVerdict -ReasonCodes @($neutral.finalReasonCode)
        return $report
    } catch {
        $reason = Get-AwxBoundedReasonCode $_.Exception
        foreach ($model in $Models) { try {& $adapters.Unload $model 5} catch {} }
        foreach ($model in @($Models | Group-Object endpoint | ForEach-Object {$_.Group[0]})) { try {if (-not (& $adapters.PsEmpty $model 5)) {$reason='model-unload-unproven'}} catch {$reason='model-unload-unproven'} }
        return New-AwxLiveFailureReport -Corpus $Corpus -CorpusPath $CorpusPath -OraclePath $OraclePath -DurationMs $runClock.Elapsed.TotalMilliseconds -ReasonCode $reason
    } finally { $runClock.Stop() }
}
```

The bounded reason-code allowlists above include `role-binding-mutated`, `native-probe-timeout`, and `concurrent-model-block-detected`; no other raw exception text passes through.

- [ ] **Step 8: Implement the thin CLI**

Create `scripts/desktop_ollama_model_benchmark.ps1`:

```powershell
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CorpusPath,
    [Parameter(Mandatory)][string]$OraclePath,
    [Parameter(Mandatory)][string]$OutputPath,
    [switch]$RunLive
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot 'modules\DesktopOllamaModelTools.psm1'
Import-Module $modulePath -Force
$corpus = Get-Content -LiteralPath (Resolve-Path -LiteralPath $CorpusPath) -Raw -Encoding UTF8 | ConvertFrom-Json
$oracle = Get-Content -LiteralPath (Resolve-Path -LiteralPath $OraclePath) -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-AwxBenchmarkContract -Corpus $corpus -Oracle $oracle
$lease = Enter-AwxBenchmarkLease -OutputPath $OutputPath
try {
    if (-not $RunLive) {
        $report = New-AwxValidateOnlyReport -Corpus $corpus -Oracle $oracle -CorpusPath $CorpusPath -OraclePath $OraclePath
    } else {
        $report = Invoke-AwxLiveBenchmarkReport -Corpus $corpus -Oracle $oracle -CorpusPath $CorpusPath -OraclePath $OraclePath -Models @(Get-AwxBenchmarkModelMatrix) -PortfolioTimeoutSec 7200
    }
    Assert-AwxReportPrivacy $report
    Write-AwxJsonAtomic -Path $OutputPath -Value $report
    [pscustomobject]@{schemaVersion=2;mode=$report.mode;verdict=$report.verdict;outputSha256=Get-AwxFileSha256 $OutputPath;reasonCodes=@($report.reasonCodes)}
} finally {
    $lease.Dispose()
}
```

The CLI calls the exact `Invoke-AwxLiveBenchmarkReport` composition above; there is no second live path.

- [ ] **Step 9: Export only the intended public functions**

Replace accumulated `Export-ModuleMember` lines with this one final export at the bottom; recursive walkers and internal DTO projectors remain private:

```powershell
$publicFunctions = @(
    'ConvertTo-AwxCanonicalJson','Get-AwxUtf8Sha256','Get-AwxFileSha256','Test-AwxExactSequence','Test-AwxStrictBoolean','Test-AwxStrictIntegerRange','Test-AwxFiniteNumberRange','Assert-AwxExactPropertySet','Assert-AwxHash64','Assert-AwxLoopbackEndpoint',
    'Assert-AwxBenchmarkContract','Assert-AwxOutboundBodyClean','New-AwxBenchmarkRequestBody',
    'Test-AwxJsonSchemaValue','Test-AwxSemanticEnvelope','Test-AwxBenchmarkResponse',
    'Get-AwxNearestRank','Get-AwxModelMetrics','Test-AwxModelHardGate','Get-AwxRoleScore',
    'Select-AwxRoleRecommendation','Get-AwxFamilyAModelIdentityMatrix','New-AwxBlockEvidence','New-AwxRunBinding','New-AwxEvidencePackets','New-AwxCommandEvidence','Get-AwxNeutralVerdict','Get-AwxOrderStableNeutralVerdict','ConvertTo-AwxPublicPacket','ConvertTo-AwxPublicNeutral',
    'Get-AwxOllamaTags','Test-AwxInstalledDigest','ConvertFrom-AwxPsResponse','Get-AwxOllamaPs','Get-AwxLoadedModelEvidence',
    'Get-AwxGpuSnapshot','Get-AwxEndpointProcessIds','Get-AwxGpuComputeRows','Test-AwxGpuLane','Test-AwxGpuRelease',
    'Stop-AwxOllamaModel','Enter-AwxBenchmarkLease','Write-AwxJsonAtomic','Assert-AwxNoOverlappingModelBlocks',
    'Invoke-AwxModelBlock','Invoke-AwxBenchmarkPortfolio','Get-AwxBenchmarkModelMatrix','Invoke-AwxFalsificationProbes','Assert-AwxPublicPacketProjection','Assert-AwxPublicNeutralProjection','Assert-AwxReportPrivacy',
    'New-AwxValidateOnlyReport','New-AwxLiveFailureReport','Invoke-AwxLiveBenchmarkReport','Get-AwxBoundedReasonCode'
)
Export-ModuleMember -Function $publicFunctions
```

Verify that the module imports under both Windows PowerShell 5.1 and PowerShell 7 when available.

- [ ] **Step 10: Run GREEN and static mutation/privacy scans**

Run:

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "Import-Module Pester -MinimumVersion 5.5.0 -Force -ErrorAction Stop;Invoke-Pester -Path 'C:\AbandonWare\demo-1\demo-1\src\scripts\tests\DesktopOllamaModelBenchmark.Tests.ps1' -Output Detailed"
rg -n 'SetEnvironmentVariable|\bsetx(?:\.exe)?\b|Remove-Item\s+Env:|\$env:[A-Za-z_][A-Za-z0-9_]*\s*=|ollama(?:\.exe)?\s+(pull|rm|remove|create|cp)' scripts\desktop_ollama_model_benchmark.ps1 scripts\modules\DesktopOllamaModelTools.psm1
rg -n 'rawPrompt|rawResponse|Authorization|Bearer|cookie|credential|ownerToken|GPU-[0-9a-f-]{16,}|model.store' scripts\desktop_ollama_model_benchmark.ps1 scripts\modules\DesktopOllamaModelTools.psm1
```

Expected: Pester PASS. The first scan has zero production matches. The second may match denylist regex literals only; manually prove none is a result/report property or emitted value.

- [ ] **Step 11: Record the conditional checkpoint**

Suggested commit, only with Git authorization:

```powershell
git add -- scripts/desktop_ollama_model_benchmark.ps1 scripts/modules/DesktopOllamaModelTools.psm1 scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1
git commit -m "feat: add recommendation-only benchmark v2 CLI"
```

---

### Task 7: Full TDD Gate, Live Six-Model Run, Digest/VRAM/Unload Proof, and Final Audit

**Files:**
- Verify: all five declared tooling files
- Write runtime evidence only: `C:\Users\nninn\.codex\visualizations\2026\07\31\019fb89a-89e2-7af3-9315-f64696278876\sdd\2026-08-01-chat-postprocess-continuation\model-role-benchmark-v2.json`
- Do not modify application source, user environment bindings, model inventory, Browser state, or Supabase state.

- [ ] **Step 1: Re-run the ownership and toolchain gates**

Repeat Task 0. Stop on target overlap, `.git/index.lock`, a top-level PatchDrop patch, an active source-edit lease, or Pester below 5.5.0. Confirm the output directory resolves to the exact visualization path above; do not substitute a repo report path.

- [ ] **Step 2: Run focused tests in Windows PowerShell 5.1**

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "`$ErrorActionPreference = 'Stop'; Import-Module Pester -MinimumVersion 5.5.0 -Force -ErrorAction Stop; `$result = Invoke-Pester -Path 'C:\AbandonWare\demo-1\demo-1\src\scripts\tests\DesktopOllamaModelBenchmark.Tests.ps1' -Output Detailed -PassThru; if (`$result.TotalCount -ne 30 -or `$result.PassedCount -ne 30 -or `$result.FailedCount -ne 0 -or `$result.SkippedCount -ne 0) { exit 1 }"
```

Expected: exactly 30 discovered tests, exactly 30 passed, zero failed, zero skipped, and process exit code 0. Record Pester version and all four counts. Do not claim GREEN from zero discovery, a PowerShell parse/import failure, or a Pester 3.4 fallback.

- [ ] **Step 3: Run validate-only and prove deterministic file replacement**

```powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$validatePath = Join-Path $env:TEMP 'awx-desktop-model-benchmark-v2-validate.json'
& (Join-Path $root 'scripts\desktop_ollama_model_benchmark.ps1') `
  -CorpusPath (Join-Path $root 'scripts\config\desktop-ollama-model-benchmark.json') `
  -OraclePath (Join-Path $root 'scripts\config\desktop-ollama-model-benchmark-oracle.json') `
  -OutputPath $validatePath
$firstHash = (Get-FileHash -LiteralPath $validatePath -Algorithm SHA256).Hash
& (Join-Path $root 'scripts\desktop_ollama_model_benchmark.ps1') `
  -CorpusPath (Join-Path $root 'scripts\config\desktop-ollama-model-benchmark.json') `
  -OraclePath (Join-Path $root 'scripts\config\desktop-ollama-model-benchmark-oracle.json') `
  -OutputPath $validatePath
$secondHash = (Get-FileHash -LiteralPath $validatePath -Algorithm SHA256).Hash
if ($firstHash -cne $secondHash) { throw 'validate-only-nondeterministic' }
```

Expected: identical hashes, `mode=validate-only`, `verdict=HOLD`, and no network/model activity. Remove only this explicitly named temporary file after recording its hash.

- [ ] **Step 4: Capture a hash-only user-binding guard**

Keep this variable in the same PowerShell process through the live run:

```powershell
function Get-AwxUserBindingGuardHash {
    $names = @('LLM_FAST_MODEL','LLM_CHAT_MODEL','LLM_HIGH_MODEL','LLM_CODER_MODEL')
    $state = [ordered]@{}
    foreach ($name in $names) {
        $value = [Environment]::GetEnvironmentVariable($name,'User')
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes([string]$value)
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $valueHash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','') }
        finally { $sha.Dispose() }
        $state[$name] = [ordered]@{present=($null -ne $value);valueSha256=$valueHash}
    }
    $json = $state | ConvertTo-Json -Depth 5 -Compress
    $sha2 = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha2.ComputeHash([Text.UTF8Encoding]::new($false).GetBytes($json)))).Replace('-','') }
    finally { $sha2.Dispose() }
}
$bindingGuardBefore = Get-AwxUserBindingGuardHash
```

Print only the final equality boolean, never the values or per-binding hashes.

- [ ] **Step 5: Revalidate endpoint inventory and exact digests before any chat**

```powershell
Import-Module 'C:\AbandonWare\demo-1\demo-1\src\scripts\modules\DesktopOllamaModelTools.psm1' -Force
$models = @(Get-AwxBenchmarkModelMatrix)
foreach ($endpoint in @('http://127.0.0.1:11435','http://127.0.0.1:11434')) {
    Assert-AwxLoopbackEndpoint $endpoint
    if (@(Get-AwxOllamaPs -Endpoint $endpoint).Count -ne 0) { throw 'preflight-model-still-loaded' }
}
foreach ($model in $models) {
    $digest = Test-AwxInstalledDigest -Rows @(Get-AwxOllamaTags -Endpoint $model.endpoint) -ModelTag $model.modelTag -ExpectedDigest $model.digest
    if (-not $digest.passed) { throw 'candidate-digest-changed' }
}
```

Expected: both `/api/ps` lists are empty and all six exact digests match. A tag-only match is insufficient.

- [ ] **Step 6: Execute the single live serial portfolio**

```powershell
$output = 'C:\Users\nninn\.codex\visualizations\2026\07\31\019fb89a-89e2-7af3-9315-f64696278876\sdd\2026-08-01-chat-postprocess-continuation\model-role-benchmark-v2.json'
& 'C:\AbandonWare\demo-1\demo-1\src\scripts\desktop_ollama_model_benchmark.ps1' `
  -CorpusPath 'C:\AbandonWare\demo-1\demo-1\src\scripts\config\desktop-ollama-model-benchmark.json' `
  -OraclePath 'C:\AbandonWare\demo-1\demo-1\src\scripts\config\desktop-ollama-model-benchmark-oracle.json' `
  -OutputPath $output `
  -RunLive
```

Run it once. Do not retry a failed or timed-out sample to fill the count. A second live portfolio requires a new explicit execution decision because the first result and runtime identity changed.

- [ ] **Step 7: Verify report completeness and privacy without printing raw content**

```powershell
$report = Get-Content -LiteralPath $output -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-AwxReportPrivacy $report
if (-not(Test-AwxStrictIntegerRange $report.schemaVersion 2 2) -or $report.mode -cne 'live') { throw 'report-contract-invalid' }
if (@($report.models).Count -ne 6) { throw 'warm-sample-insufficient' }
if (@($report.models | Where-Object {-not(Test-AwxStrictIntegerRange $_.completedWarmCount 21 21)}).Count -ne 0) { throw 'warm-sample-insufficient' }
if (@($report.recommendations).Count -ne 2 -or -not(Test-AwxExactSequence @($report.recommendations.role) @('fast','main'))) {throw 'report-contract-invalid'}
if (@($report.packets).Count -ne 3 -or -not (Test-AwxExactSequence @($report.packets.name) @('SUPPORT_CONTRACT','SUPPORT_SCENARIO','FALSIFY'))) { throw 'packet-set-invalid' }
foreach($packet in @($report.packets)){
  Assert-AwxPublicPacketProjection $packet
  if([string]$packet.runBindingSha256 -cne [string]$report.neutral.runBindingSha256){throw 'packet-set-invalid'}
}
Assert-AwxPublicNeutralProjection $report.neutral
if (-not $report.neutral.orderStable -or [string]$report.neutral.forward -cne [string]$report.neutral.reverse -or [string]$report.neutral.forwardReasonCode -cne [string]$report.neutral.reverseReasonCode) { throw 'order-unstable' }
if([string]$report.verdict -cne [string]$report.neutral.finalVerdict -or @($report.reasonCodes).Count -ne 1 -or [string]$report.reasonCodes[0] -cne [string]$report.neutral.finalReasonCode){throw 'report-contract-invalid'}
$reportText = Get-Content -LiteralPath $output -Raw -Encoding UTF8
if ($reportText -match '(?i)raw(prompt|response)|authorization|cookie|credential|owner.?token|GPU-[0-9a-f-]{16,}|^[A-Za-z]:\\|\\\\') { throw 'report-privacy-invalid' }
[pscustomobject]@{
  reportSha256=(Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash
  modelCount=@($report.models).Count
  warmCount=@($report.models | Measure-Object completedWarmCount -Sum).Sum
  verdict=$report.verdict
  reasonCodes=@($report.reasonCodes)
}
```

Expected total warm count is `126` (`6 * 21`) and total cold count is exactly `6` in the internal/run summary. Cold data must not be present in any warm p95 input.

- [ ] **Step 8: Prove binding immutability and final unload**

```powershell
$bindingGuardAfter = Get-AwxUserBindingGuardHash
if ($bindingGuardBefore -cne $bindingGuardAfter) { throw 'role-binding-mutated' }
$finalPs = @{}
foreach ($endpoint in @('http://127.0.0.1:11435','http://127.0.0.1:11434')) {
    $rows = @(Get-AwxOllamaPs -Endpoint $endpoint)
    $finalPs[$endpoint] = $rows.Count
    if ($rows.Count -ne 0) { throw 'model-unload-unproven' }
}
[pscustomobject]@{bindingGuardUnchanged=$true;fastEndpointLoadedCount=$finalPs['http://127.0.0.1:11435'];primaryEndpointLoadedCount=$finalPs['http://127.0.0.1:11434']}
```

Expected: binding guard unchanged and both loaded counts zero. GPU recovery is complete only when loaded/end endpoint process-tree rows identify the expected GPU name, cleanup finds no remaining compute row for the union of those process IDs, post-unload `/api/ps` is empty, and the post-unload GPU snapshot succeeds. If that proof cannot be collected without exposing UUIDs, return `HOLD` with `gpu-lane-evidence-incomplete`; do not infer recovery from `/api/ps` alone.

- [ ] **Step 9: Run final source, secret, and diff checks**

```powershell
git diff --check -- scripts/config/desktop-ollama-model-benchmark.json scripts/config/desktop-ollama-model-benchmark-oracle.json scripts/modules/DesktopOllamaModelTools.psm1 scripts/desktop_ollama_model_benchmark.ps1 scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1
rg -n "TODO|TBD|implement later|fill in|placeholder" scripts/config/desktop-ollama-model-benchmark.json scripts/config/desktop-ollama-model-benchmark-oracle.json scripts/modules/DesktopOllamaModelTools.psm1 scripts/desktop_ollama_model_benchmark.ps1 scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1
rg -n '(?i)(api[_-]?key|client[_-]?secret|authorization|bearer|cookie|owner[_-]?token)\s*[:=]\s*[''"]?[^\s,''"]+' scripts/config/desktop-ollama-model-benchmark.json scripts/config/desktop-ollama-model-benchmark-oracle.json scripts/modules/DesktopOllamaModelTools.psm1 scripts/desktop_ollama_model_benchmark.ps1 scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1
git status --short -- scripts/config/desktop-ollama-model-benchmark.json scripts/config/desktop-ollama-model-benchmark-oracle.json scripts/modules/DesktopOllamaModelTools.psm1 scripts/desktop_ollama_model_benchmark.ps1 scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1
```

Expected: `git diff --check` clean; placeholder scan zero; secret scan zero except deliberate denylist test strings reviewed line by line; status contains only the five declared files.

- [ ] **Step 10: Classify the outcome without promotion**

- Report `recommend-candidate` only when the hard gates, role thresholds, both NEUTRAL orders, final unload, privacy scan, and binding guard all pass.
- Report `keep-baseline` when evidence is complete but no candidate strictly wins or candidates tie.
- Report `HOLD` for any missing/incomplete contract, model, digest, lineage, lane, timeout, sample, hash, packet, privacy, or cleanup evidence.
- Report `REJECT` only when complete evidence proves a contract or falsification failure.
- Never write `LLM_FAST_MODEL`, `LLM_CHAT_MODEL`, `LLM_HIGH_MODEL`, or `LLM_CODER_MODEL`. A later binding change requires a separate promotion design and explicit approval.
- Browser response proof remains a separate Browser lane. This plan must not claim Browser success from a benchmark JSON, HTTP 200, or response hash.

- [ ] **Step 11: Record the conditional final checkpoint**

Only if the user separately authorizes Git operations after reviewing the live report:

```powershell
git add -- scripts/config/desktop-ollama-model-benchmark.json scripts/config/desktop-ollama-model-benchmark-oracle.json scripts/modules/DesktopOllamaModelTools.psm1 scripts/desktop_ollama_model_benchmark.ps1 scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1
git commit -m "feat: add deterministic local model benchmark v2"
```

Without that authorization, leave all five files unstaged and report their exact status plus the external report SHA-256.

---

## Plan Completion Gate

The implementation is complete only when all of the following are true in one current execution identity:

- Pester `>= 5.5.0` discovered exactly 30 focused tests and reported exactly 30 passed, zero failed, and zero skipped.
- Request corpus and scorer oracle are physically separate and every answer-leakage negative fixture passes.
- All six exact installed digests match before the first chat.
- Exactly six cold and 126 warm requests ran serially; no retries filled a failed sample.
- Every eligible model has one strict loaded `/api/ps` row, context 8192, GPU resident ratio at least 0.99, and role-specific loaded free VRAM.
- Cold latency is absent from warm p50/p95 and semantic promotion metrics.
- Both role recommendations follow baseline-relative thresholds and preserve baseline on ties.
- Every `balancedScore` is canonical six-decimal ToEven, and selector eligibility, the main five-point threshold, ordering/ties, and emitted deltas use exact signed `Int64` score microunits; every `recommend-candidate` delta is strictly positive.
- Each model block produced strict bounded facts and a module-owned `blockEvidenceSha256`; one `New-AwxRunBinding` call after all six blocks bound the exact static hashes and fixed ordered model identities.
- `SUPPORT_CONTRACT`, `SUPPORT_SCENARIO`, and `FALSIFY` were built only from exact typed evidence by module-owned constructors; no caller-authored status, reason, evidence hash, or packet hash was accepted.
- Provisional CommandEvidence derived `command-evidence-incomplete`, the provisional exact public projection passed privacy validation, and final CommandEvidence was reconstructed with checked/passed privacy and binding facts.
- NEUTRAL recomputed the current run, lane evidence, packet, and command hashes, required all four envelopes to share the current-run binding, and matched both verdict and reason in forward/reverse order.
- The public report exposes only the exact seven-field packet and nine-field NEUTRAL projections, passes lane reason-code policy plus secret/privacy scans, and contains no internal run binding/evidence preimage, raw prompt/response, UUID, credential, path, or full error body.
- User role-binding guard is unchanged, both final `/api/ps` arrays are empty, and GPU-lane recovery evidence is complete.
- Browser, application role promotion, coder sandboxing, model pull/removal, Supabase mutation, and Git publication remain outside the completed claim.

Any missing item yields `HOLD` or `evidence_needed` with one exact next verification action. It does not permit widening scope or reusing schema-v1/bounded-probe evidence as a substitute.

## Execution Handoff

Recommended execution method: use `superpowers:subagent-driven-development` in this task, one implementation task at a time, with a fresh specification-compliance review followed by a code-quality review at each checkpoint. Use `superpowers:executing-plans` only if the user prefers a separate sequential execution task.

Before either method begins, obtain the separate user approval required to install Pester 5.5.0 at CurrentUser scope. Plan approval alone does not authorize that installation, any live six-model run, any role-binding change, or any Git operation.
