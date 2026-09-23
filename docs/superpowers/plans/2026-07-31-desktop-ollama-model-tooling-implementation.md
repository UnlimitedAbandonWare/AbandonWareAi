# Desktop Ollama Model Tooling Implementation Plan

> **MODEL LOCK (2026-09-17, noted 2026-09-19):** This dated document may reference retired model tags (`qwen3:8b`, `qwen3:30b`, `qwen3-coder:*`, `gemma3:*`, `qwen2.5:7b-instruct`). Do not execute or wire those tags — live SoT: repo `AGENTS.md` model lock + `configs/api-routing.yaml` + `ollama ls`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** RTX 3060/3090 Desktop에서 승인된 Ollama 모델 네 개를 exact tag와 digest로 내려받고, 역할별 A/B를 실행한 뒤 검증된 사용자 범위 model binding을 가역적으로 적용하는 PowerShell 도구를 만든다.

**Architecture:** secret-free JSON catalog와 공통 PowerShell module이 validation, redaction, atomic output, endpoint allowlist를 소유한다. Preflight, download, benchmark, promote 스크립트는 각각 한 가지 mutation 경계만 가지며 JSON evidence로 연결된다. 다운로드와 승격은 서로 독립된 명시 승인 플래그가 없으면 fail-closed다.

**Tech Stack:** Windows PowerShell 5.1+, PowerShell 7 호환 문법, Pester 5.5+, Ollama loopback REST API, `nvidia-smi`, JSON schema version 1.

## Global Constraints

- 실행 소유자는 Desktop이며 canonical source root `C:\AbandonWare\demo-1\demo-1\src`를 실행 직전에 다시 증명한다.
- Notebook `Y:\`는 계획과 supporting evidence만 소유한다. 이 계획 작성 단계에서는 Desktop, `E:\models`, 사용자 환경 변수를 변경하지 않는다.
- 모델 저장소는 `E:\models`, primary endpoint는 `http://127.0.0.1:11434`, fast endpoint는 `http://127.0.0.1:11435`다.
- 허용 모델은 `qwen3.5:9b`, `gemma4:12b`, `qwen3.6:27b`, `gemma4:31b` 네 exact tag뿐이다. `latest`와 임의 tag는 금지한다.
- 첫 wave 시작 전 free disk는 65GiB 이상이어야 한다.
- 3060 후보는 free VRAM 1536MiB 이상, 3090 후보는 2048MiB 이상이며 `size_vram / size >= 0.99`여야 한다.
- 출력에는 원시 prompt, 원시 response, 전체 환경 변수, API key, authorization header, model-store backing path를 넣지 않는다.
- 모델 삭제, machine-scope 환경 변수, DB, credential, embedding model/dimension 변경은 금지한다.
- pull과 promotion은 각각 `-ApproveDownload`, `-ApprovePromotion` 없이는 mutation하지 않는다.
- commit, push, 배포는 별도 사용자 승인이 있을 때만 수행한다. 각 task의 checkpoint는 suggested commit message만 기록한다.
- Desktop 최종 runtime proof가 없으면 `runtimeLineageVerdict=HOLD`를 유지한다.
- `Get-Module -ListAvailable Pester`에서 5.5 이상이 없으면 `pester-missing`으로 HOLD하며 전역 module을 자동 설치하지 않는다.

---

## File Structure

- Create `scripts/config/desktop-ollama-model-candidates.json`: exact model/digest/lane/context catalog.
- Create `scripts/config/desktop-ollama-model-benchmark.json`: deterministic Korean RAG, rewrite, extraction, and coding cases plus scoring weights.
- Create `scripts/modules/DesktopOllamaModelTools.psm1`: shared validation, hashing, API, GPU parsing, lease, atomic JSON, benchmark scoring, and user-env rollback functions.
- Create `scripts/desktop_ollama_model_preflight.ps1`: read-only Desktop inventory.
- Create `scripts/desktop_ollama_model_download.ps1`: one exact-tag approved pull and post-pull verification.
- Create `scripts/desktop_ollama_model_benchmark.ps1`: serial baseline/candidate trials and one recommendation report.
- Create `scripts/desktop_ollama_model_promote.ps1`: report-gated user-scope role binding and rollback.
- Create `scripts/tests/DesktopOllamaModelCatalog.Tests.ps1`: catalog/corpus contract tests.
- Create `scripts/tests/DesktopOllamaModelTools.Tests.ps1`: pure shared-function and redaction tests.
- Create `scripts/tests/DesktopOllamaModelPreflight.Tests.ps1`: fixture-driven inventory tests.
- Create `scripts/tests/DesktopOllamaModelDownload.Tests.ps1`: approval, lease, digest, idempotency tests.
- Create `scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1`: deterministic score and lineage tests.
- Create `scripts/tests/DesktopOllamaModelPromote.Tests.ps1`: user-scope mutation and rollback tests.

## Shared Data Contracts

`preflight.json` must contain only:

```json
{
  "schemaVersion": 1,
  "status": "ready",
  "reason": "ok",
  "generatedAtUtc": "2026-07-31T00:00:00Z",
  "ollamaVersion": "0.0.0",
  "modelStoreConfigured": true,
  "modelStoreExists": true,
  "freeDiskGiB": 100.0,
  "gpus": [],
  "endpoints": {},
  "catalogSha256": "sha256-hex"
}
```

`recommendation.json` must contain only aggregate evidence:

```json
{
  "schemaVersion": 1,
  "status": "ready",
  "reason": "all-required-gates-passed",
  "generatedAtUtc": "2026-07-31T00:00:00Z",
  "catalogSha256": "sha256-hex",
  "preflightSha256": "sha256-hex",
  "runtimeLineageVerdict": "PASS",
  "roles": {
    "fast": {"baseline":"qwen3:8b","winner":"qwen3.5:9b","decision":"promote","scoreDelta":5.0},
    "main": {"baseline":"gemma4:26b","winner":"qwen3.6:27b","decision":"promote","scoreDelta":5.0},
    "high": {"baseline":"gemma4:26b","winner":"qwen3.6:27b","decision":"promote","scoreDelta":5.0},
    "coder": {"baseline":"qwen3-coder:30b","winner":"qwen3-coder:30b","decision":"keep-baseline","scoreDelta":0.0}
  },
  "candidateCount": 4,
  "passedCandidateCount": 2,
  "failedCandidateCount": 2
}
```

Real timestamps and hashes replace the example scalar values. The schema and field names remain exact.

---

### Task 1: Exact Candidate Catalog and Benchmark Corpus

**Files:**
- Create: `scripts/config/desktop-ollama-model-candidates.json`
- Create: `scripts/config/desktop-ollama-model-benchmark.json`
- Create: `scripts/tests/DesktopOllamaModelCatalog.Tests.ps1`

**Interfaces:**
- Consumes: no earlier task.
- Produces: catalog schema version 1 and benchmark schema version 1 consumed by every later tooling task.

- [ ] **Step 1: Write the failing catalog contract test**

```powershell
BeforeAll {
    $script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $script:CatalogPath = Join-Path $RepoRoot 'scripts\config\desktop-ollama-model-candidates.json'
    $script:BenchmarkPath = Join-Path $RepoRoot 'scripts\config\desktop-ollama-model-benchmark.json'
}

It 'pins exactly four approved tags and digest prefixes' {
    $catalog = Get-Content -LiteralPath $CatalogPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $catalog.schemaVersion | Should -Be 1
    ($catalog.candidates.tag -join ',') | Should -Be 'qwen3.5:9b,gemma4:12b,qwen3.6:27b,gemma4:31b'
    ($catalog.candidates.digestPrefix -join ',') | Should -Be '6488c96fa5fa,4eb23ef187e2,a50eda8ed977,6316f0629137'
    $catalog.minimumFreeDiskGiB | Should -Be 65
    @($catalog.candidates | Where-Object { $_.tag -match 'latest' }).Count | Should -Be 0
}

It 'defines bounded role cases without secrets' {
    $benchmark = Get-Content -LiteralPath $BenchmarkPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $benchmark.schemaVersion | Should -Be 1
    @($benchmark.cases | Where-Object role -eq 'fast').Count | Should -BeGreaterOrEqual 3
    @($benchmark.cases | Where-Object role -eq 'main').Count | Should -BeGreaterOrEqual 3
    @($benchmark.cases | Where-Object role -eq 'coder').Count | Should -BeGreaterOrEqual 2
    (Get-Content -LiteralPath $BenchmarkPath -Raw -Encoding UTF8) | Should -Not -Match '(?i)api[_-]?key|bearer\s|owner[_-]?token'
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
Invoke-Pester -Path .\scripts\tests\DesktopOllamaModelCatalog.Tests.ps1 -Output Detailed
```

Expected: FAIL because both JSON files are absent.

- [ ] **Step 3: Add the candidate catalog**

```json
{
  "schemaVersion": 1,
  "minimumFreeDiskGiB": 65,
  "allowedEndpoints": ["http://127.0.0.1:11434", "http://127.0.0.1:11435"],
  "candidates": [
    {
      "tag": "qwen3.5:9b",
      "digestPrefix": "6488c96fa5fa",
      "officialSource": "https://ollama.com/library/qwen3.5",
      "packageGB": 6.6,
      "gpuLane": "rtx3060",
      "endpoint": "http://127.0.0.1:11435",
      "roles": ["fast", "rewrite", "explore"],
      "baselines": {"fast": "qwen3:8b"},
      "trialContexts": [8192, 16384],
      "minimumFreeVramMiB": 1536,
      "priority": "primary"
    },
    {
      "tag": "gemma4:12b",
      "digestPrefix": "4eb23ef187e2",
      "officialSource": "https://ollama.com/library/gemma4",
      "packageGB": 7.6,
      "gpuLane": "rtx3060",
      "endpoint": "http://127.0.0.1:11435",
      "roles": ["fast", "rewrite", "explore"],
      "baselines": {"fast": "qwen3:8b"},
      "trialContexts": [8192, 16384],
      "minimumFreeVramMiB": 1536,
      "priority": "challenger"
    },
    {
      "tag": "qwen3.6:27b",
      "digestPrefix": "a50eda8ed977",
      "officialSource": "https://ollama.com/library/qwen3.6",
      "packageGB": 17.0,
      "gpuLane": "rtx3090",
      "endpoint": "http://127.0.0.1:11434",
      "roles": ["main", "high", "coder"],
      "baselines": {"main": "gemma4:26b", "high": "gemma4:26b", "coder": "qwen3-coder:30b"},
      "trialContexts": [16384, 32768],
      "minimumFreeVramMiB": 2048,
      "priority": "primary"
    },
    {
      "tag": "gemma4:31b",
      "digestPrefix": "6316f0629137",
      "officialSource": "https://ollama.com/library/gemma4",
      "packageGB": 20.0,
      "gpuLane": "rtx3090",
      "endpoint": "http://127.0.0.1:11434",
      "roles": ["main", "high"],
      "baselines": {"main": "gemma4:26b", "high": "gemma4:26b"},
      "trialContexts": [8192],
      "minimumFreeVramMiB": 2048,
      "priority": "challenger"
    }
  ]
}
```

- [ ] **Step 4: Add the deterministic benchmark corpus**

Create schema version 1 with these exact cases:

```json
{
  "schemaVersion": 1,
  "options": {"temperature": 0, "seed": 42},
  "weights": {
    "main": {"koreanRagQuality":0.35,"instructionFollowing":0.20,"codingAndToolUse":0.15,"factualityAndCitationDiscipline":0.10,"latencyEfficiency":0.10,"vramHeadroom":0.05,"runtimeStability":0.05},
    "fast": {"rewriteAndExtractionQuality":0.30,"latencyEfficiency":0.30,"instructionFollowing":0.15,"toolAndStructuredOutput":0.10,"vramHeadroom":0.10,"runtimeStability":0.05},
    "coder": {"repositoryTaskPassRate":0.50,"toolAndStructuredOutput":0.20,"latencyEfficiency":0.15,"vramHeadroom":0.10,"runtimeStability":0.05}
  },
  "cases": [
    {
      "id": "fast-rewrite-ko",
      "role": "fast",
      "metrics": ["rewriteAndExtractionQuality"],
      "prompt": "다음 문장을 의미를 유지한 한 문장으로 간결하게 고쳐라: 시스템은 모델을 다운로드한 뒤 검증을 통과한 경우에만 기본값을 바꾼다.",
      "assertion": {"type": "contains-all", "values": ["다운로드", "검증", "기본값"]}
    },
    {
      "id": "fast-json-extract",
      "role": "fast",
      "metrics": ["rewriteAndExtractionQuality", "toolAndStructuredOutput"],
      "prompt": "문장 'RTX 3060은 fast 역할이고 포트는 11435다'에서 gpu, role, port를 JSON 객체로만 출력하라.",
      "assertion": {"type": "json-equals", "value": {"gpu":"RTX 3060","role":"fast","port":11435}}
    },
    {
      "id": "fast-instruction",
      "role": "fast",
      "metrics": ["instructionFollowing"],
      "prompt": "오직 PASS라는 단어 하나만 출력하라.",
      "assertion": {"type": "exact", "value": "PASS"}
    },
    {
      "id": "main-grounded-ko",
      "role": "main",
      "metrics": ["koreanRagQuality", "factualityAndCitationDiscipline"],
      "prompt": "근거 [A]: qwen3.5:9b는 3060 후보이다. 근거 [B]: 승격 전 GPU 적재를 확인한다. 근거만 사용해 두 문장으로 설명하고 각 문장 끝에 [A] 또는 [B]를 붙여라.",
      "assertion": {"type": "citations", "required": ["[A]", "[B]"], "forbidden": ["인터넷", "추측"]}
    },
    {
      "id": "main-uncertainty-ko",
      "role": "main",
      "metrics": ["factualityAndCitationDiscipline"],
      "prompt": "제공된 근거에는 RTX 3060의 실제 VRAM 용량이 없다. 현재 VRAM 용량을 숫자로 답하라.",
      "assertion": {"type": "contains-any", "values": ["알 수 없", "확인 필요", "근거가 없"]}
    },
    {
      "id": "main-policy-ko",
      "role": "main",
      "metrics": ["instructionFollowing", "codingAndToolUse"],
      "prompt": "다운로드 성공, CPU offload 발견, 기준선보다 품질 하락이라는 조건에서 승격 여부와 이유를 JSON으로만 출력하라. 키는 promote와 reason이다.",
      "assertion": {"type": "json-fields", "fields": {"promote": false}, "reasonContains": ["CPU", "품질"]}
    },
    {
      "id": "coder-powershell-safety",
      "role": "coder",
      "metrics": ["repositoryTaskPassRate", "toolAndStructuredOutput"],
      "prompt": "PowerShell 함수 이름 Test-LoopbackUri를 작성하라. http 스킴과 127.0.0.1 호스트만 true이고 나머지는 false여야 한다. 코드 블록 하나만 출력하라.",
      "assertion": {"type": "contains-all", "values": ["function Test-LoopbackUri", "127.0.0.1", "http"]}
    },
    {
      "id": "coder-java-guard",
      "role": "coder",
      "metrics": ["repositoryTaskPassRate"],
      "prompt": "Java 메서드 boolean promotable(boolean gpuOnly, boolean digestMatch)를 작성하라. 두 값이 모두 true일 때만 true다. 메서드만 출력하라.",
      "assertion": {"type": "contains-all", "values": ["boolean promotable", "gpuOnly", "digestMatch", "&&"]}
    }
  ]
}
```

- [ ] **Step 5: Run GREEN and record the checkpoint**

Run the Pester command from Step 2. Expected: all catalog tests PASS.

Suggested commit message, only after separate approval: `feat: add desktop ollama candidate catalog`.

---

### Task 2: Shared Validation, Redaction, and Atomic Evidence Module

**Files:**
- Create: `scripts/modules/DesktopOllamaModelTools.psm1`
- Create: `scripts/tests/DesktopOllamaModelTools.Tests.ps1`

**Interfaces:**
- Consumes: candidate and benchmark JSON from Task 1.
- Produces: exported functions `Read-AwxModelCatalog`, `Assert-AwxCandidate`, `Assert-AwxLoopbackEndpoint`, `ConvertFrom-AwxNvidiaSmiCsv`, `Get-AwxSha256`, `Write-AwxJsonAtomic`, `Enter-AwxFileLease`, `Exit-AwxFileLease`, `Test-AwxSecretText`. Later tasks append only their own public functions.

- [ ] **Step 1: Write failing pure-function tests**

```powershell
BeforeAll {
    $module = Join-Path $PSScriptRoot '..\modules\DesktopOllamaModelTools.psm1'
    Import-Module $module -Force
    $catalogPath = Join-Path $PSScriptRoot '..\config\desktop-ollama-model-candidates.json'
}

It 'rejects moving and unlisted tags' {
    { Assert-AwxCandidate -CatalogPath $catalogPath -ModelTag 'latest' } | Should -Throw '*candidate-not-allowlisted*'
    { Assert-AwxCandidate -CatalogPath $catalogPath -ModelTag 'llama3:8b' } | Should -Throw '*candidate-not-allowlisted*'
}

It 'accepts only the two loopback origins' {
    { Assert-AwxLoopbackEndpoint 'http://127.0.0.1:11434' } | Should -Not -Throw
    { Assert-AwxLoopbackEndpoint 'http://localhost:11434' } | Should -Throw '*endpoint-not-allowlisted*'
    { Assert-AwxLoopbackEndpoint 'https://127.0.0.1:11434' } | Should -Throw '*endpoint-not-allowlisted*'
}

It 'parses UUID, total, free, and driver without raw stderr' {
    $rows = ConvertFrom-AwxNvidiaSmiCsv @'
GPU-a, NVIDIA GeForce RTX 3060, 12288, 11000, 591.00
GPU-b, NVIDIA GeForce RTX 3090, 24576, 22000, 591.00
'@
    $rows.Count | Should -Be 2
    $rows[0].lane | Should -Be 'rtx3060'
    $rows[1].freeMiB | Should -Be 22000
}

It 'writes JSON atomically and rejects secret-shaped content' {
    $out = Join-Path $TestDrive 'evidence.json'
    Write-AwxJsonAtomic -Path $out -Value ([ordered]@{schemaVersion=1;status='ready'})
    (Get-Content $out -Raw | ConvertFrom-Json).status | Should -Be 'ready'
    { Write-AwxJsonAtomic -Path $out -Value ([ordered]@{message='Authorization Bearer abc'}) } | Should -Throw '*secret-scan-failed*'
}
```

- [ ] **Step 2: Run RED**

```powershell
Invoke-Pester -Path .\scripts\tests\DesktopOllamaModelTools.Tests.ps1 -Output Detailed
```

Expected: FAIL because the module does not exist.

- [ ] **Step 3: Implement strict catalog and endpoint validation**

```powershell
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-AwxModelCatalog {
    param([Parameter(Mandatory)][string]$CatalogPath)
    $catalog = Get-Content -LiteralPath $CatalogPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($catalog.schemaVersion -ne 1 -or @($catalog.candidates).Count -ne 4) {
        throw 'catalog-schema-invalid'
    }
    return $catalog
}

function Assert-AwxCandidate {
    param([Parameter(Mandatory)][string]$CatalogPath,[Parameter(Mandatory)][string]$ModelTag)
    if ($ModelTag -match '(?i)(^|:)latest$') { throw 'candidate-not-allowlisted' }
    $candidate = @((Read-AwxModelCatalog -CatalogPath $CatalogPath).candidates) |
        Where-Object { $_.tag -ceq $ModelTag } |
        Select-Object -First 1
    if ($null -eq $candidate) { throw 'candidate-not-allowlisted' }
    return $candidate
}

function Assert-AwxLoopbackEndpoint {
    param([Parameter(Mandatory)][string]$Endpoint)
    if ($Endpoint -notin @('http://127.0.0.1:11434','http://127.0.0.1:11435')) {
        throw 'endpoint-not-allowlisted'
    }
}
```

- [ ] **Step 4: Implement redaction, hashing, GPU parsing, atomic JSON, and file lease**

Use `SHA256.Create()` over UTF-8/file bytes for `Get-AwxSha256`. `Write-AwxJsonAtomic` must serialize with depth 12, run `Test-AwxSecretText` against `(?i)authorization\s+bearer|api[_-]?key\s*[:=]|client[_-]?secret\s*[:=]|owner[_-]?token\s*[:=]`, write a same-directory `.tmp` file with UTF-8 no BOM, flush/close, re-read and hash it, then `Move-Item -Force` to the final evidence filename. It must never target a model blob.

Implement the GPU parser with the exact input order `uuid,name,memory.total,memory.free,driver_version`:

```powershell
function ConvertFrom-AwxNvidiaSmiCsv {
    param([AllowEmptyString()][string]$Csv)
    $rows = foreach ($line in @($Csv -split '\r?\n')) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $columns = $line.Split(',')
        if ($columns.Count -ne 5) { throw 'gpu-inventory-parse-failed' }
        $name = $columns[1].Trim()
        [pscustomobject]@{
            uuidHash = Get-AwxStringHash -Value ($columns[0].Trim())
            name = $name
            lane = if ($name -match '3090') { 'rtx3090' } elseif ($name -match '3060') { 'rtx3060' } else { 'unsupported' }
            totalMiB = [int]$columns[2]
            freeMiB = [int]$columns[3]
            driverVersion = $columns[4].Trim()
        }
    }
    return @($rows)
}
```

`Enter-AwxFileLease` opens `data\agent-handoff\model-autopilot\model-pull.lease` with `FileShare.None` and returns the open `FileStream`; `Exit-AwxFileLease` disposes only that handle. A collision throws `pull-lease-conflict`. No recursive delete is permitted.

Use these exact hashing and atomic-output implementations:

```powershell
function Get-AwxStringHash {
    param([AllowEmptyString()][string]$Value)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes([string]$Value)))).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-AwxSha256 {
    param([Parameter(Mandatory)][string]$Path)
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Test-AwxSecretText {
    param([AllowEmptyString()][string]$Text)
    return [bool]($Text -match '(?i)authorization\s+bearer|api[_-]?key\s*[:=]|client[_-]?secret\s*[:=]|owner[_-]?token\s*[:=]')
}

function Write-AwxJsonAtomic {
    param([Parameter(Mandatory)][string]$Path,[Parameter(Mandatory)]$Value)
    $json = $Value | ConvertTo-Json -Depth 12
    if (Test-AwxSecretText -Text $json) { throw 'secret-scan-failed' }
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $tempPath = Join-Path $parent ((Split-Path -Leaf $Path) + '.tmp')
    [IO.File]::WriteAllText($tempPath, $json, [Text.UTF8Encoding]::new($false))
    $null = Get-AwxSha256 -Path $tempPath
    Move-Item -LiteralPath $tempPath -Destination $Path -Force
}

function Enter-AwxFileLease {
    param([string]$LeasePath = '.\data\agent-handoff\model-autopilot\model-pull.lease')
    $parent = Split-Path -Parent $LeasePath
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    try { [IO.File]::Open($LeasePath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch [IO.IOException] { throw 'pull-lease-conflict' }
}

function Exit-AwxFileLease {
    param($Lease)
    if ($null -ne $Lease) { $Lease.Dispose() }
}
```

- [ ] **Step 5: Export only the declared functions and run GREEN**

```powershell
Export-ModuleMember -Function Read-AwxModelCatalog,Assert-AwxCandidate,Assert-AwxLoopbackEndpoint,ConvertFrom-AwxNvidiaSmiCsv,Get-AwxSha256,Write-AwxJsonAtomic,Enter-AwxFileLease,Exit-AwxFileLease,Test-AwxSecretText
```

Run the Task 2 Pester command. Expected: all tests PASS.

Suggested commit message, only after separate approval: `feat: add safe ollama tooling primitives`.

---

### Task 3: Read-Only Desktop Preflight

**Files:**
- Create: `scripts/desktop_ollama_model_preflight.ps1`
- Create: `scripts/tests/DesktopOllamaModelPreflight.Tests.ps1`

**Interfaces:**
- Consumes: `Read-AwxModelCatalog`, GPU parser, endpoint allowlist, atomic writer.
- Produces: schema version 1 `preflight.json`; exit 0 only for `status=ready`.

- [ ] **Step 1: Write failing fixture-driven tests**

Test the module-level `New-AwxPreflightReport` function with two GPU rows, two endpoint fixtures, `modelStoreConfigured=true`, and `freeDiskGiB=100`. Assert `status=ready`. Repeat with one missing GPU, store mismatch, and 64.9GiB; assert `desktop-gpu-inventory-missing`, `model-store-mismatch`, and `insufficient-disk` respectively. Assert serialized output contains neither raw GPU UUID nor `E:\models`.

- [ ] **Step 2: Run RED**

```powershell
Invoke-Pester -Path .\scripts\tests\DesktopOllamaModelPreflight.Tests.ps1 -Output Detailed
```

Expected: FAIL because the report builder and script are absent.

- [ ] **Step 3: Add the report builder to the module**

```powershell
function New-AwxPreflightReport {
    param($Gpus,$Endpoints,[bool]$ModelStoreConfigured,[bool]$ModelStoreExists,[double]$FreeDiskGiB,[string]$OllamaVersion,[string]$CatalogSha256)
    $reason = 'ok'
    if (@($Gpus | Where-Object lane -eq 'rtx3060').Count -ne 1 -or @($Gpus | Where-Object lane -eq 'rtx3090').Count -ne 1) { $reason = 'desktop-gpu-inventory-missing' }
    elseif (-not $ModelStoreConfigured -or -not $ModelStoreExists) { $reason = 'model-store-mismatch' }
    elseif ($FreeDiskGiB -lt 65) { $reason = 'insufficient-disk' }
    elseif (@($Endpoints.Values | Where-Object { -not $_.reachable }).Count -gt 0) { $reason = 'ollama-runtime-unavailable' }
    [ordered]@{
        schemaVersion = 1
        status = if ($reason -eq 'ok') { 'ready' } else { 'hold' }
        reason = $reason
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        ollamaVersion = $OllamaVersion
        modelStoreConfigured = $ModelStoreConfigured
        modelStoreExists = $ModelStoreExists
        freeDiskGiB = [Math]::Round($FreeDiskGiB, 2)
        gpus = @($Gpus)
        endpoints = $Endpoints
        catalogSha256 = $CatalogSha256
    }
}
```

Export the function for tests and for the preflight script.

Append `New-AwxPreflightReport` to the module's `Export-ModuleMember` list.

- [ ] **Step 4: Implement the read-only script**

Parameters are exact:

```powershell
[CmdletBinding(SupportsShouldProcess=$true)]
param(
    [string]$PrimaryEndpoint = 'http://127.0.0.1:11434',
    [string]$FastEndpoint = 'http://127.0.0.1:11435',
    [string]$ModelsRoot = 'E:\models',
    [string]$CatalogPath = '.\scripts\config\desktop-ollama-model-candidates.json',
    [Parameter(Mandatory)][string]$OutputPath
)
```

Run `nvidia-smi --query-gpu=uuid,name,memory.total,memory.free,driver_version --format=csv,noheader,nounits`; call `/api/version`, `/api/tags`, and `/api/ps` on both endpoints with a 5-second timeout; compare `[Environment]::GetEnvironmentVariable('OLLAMA_MODELS','User')` internally to `E:\models`; and use `Get-PSDrive E` for free disk. Report endpoint labels `primary` and `fast`, installed model count, running model count, and reachability only. Do not emit model-store path or raw error bodies.

- [ ] **Step 5: Run GREEN and syntax verification**

```powershell
Invoke-Pester -Path .\scripts\tests\DesktopOllamaModelPreflight.Tests.ps1 -Output Detailed
$null = [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path .\scripts\desktop_ollama_model_preflight.ps1), [ref]$null, [ref]$null)
```

Expected: tests PASS and parser reports no errors.

Suggested commit message, only after separate approval: `feat: add desktop ollama preflight`.

---

### Task 4: Approved, Serialized Model Download

**Files:**
- Create: `scripts/desktop_ollama_model_download.ps1`
- Create: `scripts/tests/DesktopOllamaModelDownload.Tests.ps1`
- Modify: `scripts/modules/DesktopOllamaModelTools.psm1`

**Interfaces:**
- Consumes: candidate catalog, ready preflight evidence, file lease, loopback allowlist.
- Produces: one `download-qwen3-5-9b.json`-style sanitized-tag report with tag hash, digest, size, elapsed time, and reason; never changes role bindings.

- [ ] **Step 1: Write failing download tests**

Cover these exact cases with Pester mocks around `Invoke-AwxPullStream` and `Get-AwxInstalledModel`:

- no `-ApproveDownload` returns `download-approval-required` and makes zero pull calls;
- unlisted tag returns `candidate-not-allowlisted`;
- endpoint differs from catalog lane returns `candidate-endpoint-mismatch`;
- matching installed digest returns `already-installed` without pull;
- occupied file lease returns `pull-lease-conflict`;
- pull stream ending without success returns `pull-failed`;
- post-pull digest prefix mismatch returns `digest-mismatch`;
- matching digest returns `downloaded` and does not modify environment variables.

- [ ] **Step 2: Run RED**

```powershell
Invoke-Pester -Path .\scripts\tests\DesktopOllamaModelDownload.Tests.ps1 -Output Detailed
```

Expected: FAIL because download functions and script are absent.

- [ ] **Step 3: Implement bounded pull streaming in the module**

`Invoke-AwxPullStream` must use `.NET HttpClient`, `ResponseHeadersRead`, and line-by-line JSON parsing against `POST /api/pull` with body `{"model":"exact-tag","stream":true}`. It records only status labels, completed/total byte counts, and elapsed milliseconds. It never persists the response body. Set the per-model timeout to 7,200 seconds and cancel the request on timeout.

```powershell
function Invoke-AwxPullStream {
    param([Parameter(Mandatory)][string]$Endpoint,[Parameter(Mandatory)][string]$ModelTag)
    Assert-AwxLoopbackEndpoint -Endpoint $Endpoint
    $client = [System.Net.Http.HttpClient]::new()
    $cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(7200))
    $request = [System.Net.Http.HttpRequestMessage]::new('POST', ($Endpoint.TrimEnd('/') + '/api/pull'))
    $request.Content = [System.Net.Http.StringContent]::new(
        (@{model=$ModelTag;stream=$true} | ConvertTo-Json -Compress),
        [Text.Encoding]::UTF8,
        'application/json')
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $response = $null; $reader = $null
    $lastStatus = 'started'; $completed = 0L; $total = 0L; $success = $false
    try {
        $response = $client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cts.Token).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw 'pull-http-failed' }
        $reader = [IO.StreamReader]::new($response.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
        while (-not $reader.EndOfStream) {
            $line = $reader.ReadLine()
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $event = $line | ConvertFrom-Json
            $lastStatus = [string]$event.status
            if ($null -ne $event.completed) { $completed = [long]$event.completed }
            if ($null -ne $event.total) { $total = [long]$event.total }
            if ($lastStatus -eq 'success') { $success = $true }
        }
        [pscustomobject]@{success=$success;status=$lastStatus;completed=$completed;total=$total;elapsedMs=$watch.ElapsedMilliseconds}
    } catch [OperationCanceledException] {
        throw 'pull-timeout'
    } finally {
        $watch.Stop()
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose(); $cts.Dispose(); $client.Dispose()
    }
}
```

`Get-AwxInstalledModel` calls `/api/tags`, exact-matches `name`, and returns only `name`, `digest`, and `size`. A digest is valid only when `digest.ToLowerInvariant().StartsWith(candidate.digestPrefix)`.

```powershell
function Get-AwxInstalledModel {
    param([Parameter(Mandatory)][string]$Endpoint,[Parameter(Mandatory)][string]$ModelTag)
    Assert-AwxLoopbackEndpoint -Endpoint $Endpoint
    $response = Invoke-RestMethod -Method Get -Uri ($Endpoint.TrimEnd('/') + '/api/tags') -TimeoutSec 10
    $match = @($response.models | Where-Object { $_.name -ceq $ModelTag }) | Select-Object -First 1
    if ($null -eq $match) { return $null }
    [pscustomobject]@{name=[string]$match.name;digest=[string]$match.digest;size=[long]$match.size}
}
```

Append `Invoke-AwxPullStream` and `Get-AwxInstalledModel` to the module's `Export-ModuleMember` list.

- [ ] **Step 4: Implement the thin approved download script**

```powershell
[CmdletBinding(SupportsShouldProcess=$true)]
param(
    [Parameter(Mandatory)][string]$ModelTag,
    [Parameter(Mandatory)][string]$Endpoint,
    [string]$ModelsRoot = 'E:\models',
    [string]$CatalogPath = '.\scripts\config\desktop-ollama-model-candidates.json',
    [switch]$ApproveDownload,
    [Parameter(Mandatory)][string]$OutputPath
)
```

The script validates approval, candidate, endpoint, model store, 65GiB batch precondition for the first absent candidate, lease, pull result, and post-pull digest in that order. `-WhatIf` writes a `planned` evidence report but performs no pull. Its `finally` block disposes only the lease handle.

- [ ] **Step 5: Run GREEN and mutation-surface check**

```powershell
Invoke-Pester -Path .\scripts\tests\DesktopOllamaModelDownload.Tests.ps1 -Output Detailed
rg -n "SetEnvironmentVariable|ollama rm|Remove-Item" .\scripts\desktop_ollama_model_download.ps1 .\scripts\modules\DesktopOllamaModelTools.psm1
```

Expected: Pester PASS; search returns no download-script environment mutation, `ollama rm`, or recursive deletion.

Suggested commit message, only after separate approval: `feat: add approved ollama model downloader`.

---

### Task 5: Deterministic Role Benchmark and Recommendation

**Files:**
- Create: `scripts/desktop_ollama_model_benchmark.ps1`
- Create: `scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1`
- Modify: `scripts/modules/DesktopOllamaModelTools.psm1`

**Interfaces:**
- Consumes: ready preflight JSON, catalog, benchmark corpus, installed exact digests.
- Produces: schema version 1 `recommendation.json` with role winners or `keep-baseline`, aggregate scores, hashes, timing, VRAM headroom, and lineage verdict.

- [ ] **Step 1: Write failing scorer tests**

Test `Test-AwxBenchmarkAssertion` for all eight assertion types used in Task 1. Test `Get-AwxRoleScore` with fixed metrics so the exact results are reproducible. Test `Select-AwxRoleWinner` rules:

- main/high requires score delta at least 5, quality non-regression, p95 latency ratio at most 1.5, 2048MiB free, GPU ratio at least 0.99;
- fast requires score no lower than baseline, p95 latency no higher than baseline, 1536MiB free, GPU ratio at least 0.99;
- coder requires higher code pass rate; tie keeps `qwen3-coder:30b`;
- any prompt/options/response model hash gap yields `runtimeLineageVerdict=HOLD` and no promotion.

- [ ] **Step 2: Run RED**

```powershell
Invoke-Pester -Path .\scripts\tests\DesktopOllamaModelBenchmark.Tests.ps1 -Output Detailed
```

Expected: FAIL because scorer functions and script are absent.

- [ ] **Step 3: Implement response assertions and scoring**

`Test-AwxBenchmarkAssertion` parses JSON only for JSON assertion types and returns `{passed, reason}` without returning raw content. `Get-AwxRoleScore` uses the exact Task 1 weights and normalized 0.0–1.0 metrics. `Select-AwxRoleWinner` sorts by score descending, then catalog priority `primary` before `challenger`, and emits `keep-baseline` unless every hard gate passes.

Append `Test-AwxBenchmarkAssertion`, `Get-AwxRoleScore`, and `Select-AwxRoleWinner` to the module's `Export-ModuleMember` list.

For each request, hash UTF-8 prompt and canonical JSON options. Call `POST /api/chat` with exact model, `stream=false`, `think=false`, `keep_alive="5m"`, `temperature=0`, `seed=42`, and the current trial `num_ctx`. Record returned model hash, response hash, token counts, and timings; discard response text after assertion.

- [ ] **Step 4: Implement GPU/context admission**

After loading a candidate, call `/api/ps`. Exact-match the running model and calculate `gpuResidentRatio = size_vram / size`. Re-run the GPU query and require the lane's free memory threshold. Start only at the first catalog context; advance to the second context once if the first passes. If the second fails, keep the first passing context. Unload the trial model with Ollama `keep_alive=0`; do not remove it from disk.

- [ ] **Step 5: Implement the benchmark script**

```powershell
[CmdletBinding()]
param(
    [string]$CatalogPath = '.\scripts\config\desktop-ollama-model-candidates.json',
    [string]$BenchmarkPath = '.\scripts\config\desktop-ollama-model-benchmark.json',
    [Parameter(Mandatory)][string]$InventoryPath,
    [Parameter(Mandatory)][string]$OutputPath
)
```

Reject a non-ready or older-than-24-hours preflight. Verify the catalog hash against preflight. Run baselines and candidates serially per endpoint, never parallel. Bound each candidate to 1,800 seconds and the whole portfolio to 7,200 seconds. Write only the shared recommendation contract and aggregate candidate counts.

- [ ] **Step 6: Run GREEN and secret-output tests**

```powershell
Invoke-Pester -Path .\scripts\tests\DesktopOllamaModelBenchmark.Tests.ps1 -Output Detailed
rg -n "rawPrompt|rawResponse|responseText|Authorization|Bearer" .\scripts\desktop_ollama_model_benchmark.ps1 .\scripts\modules\DesktopOllamaModelTools.psm1
```

Expected: Pester PASS; source scan finds no persisted raw prompt/response fields or authorization values.

Suggested commit message, only after separate approval: `feat: add gpu-gated model benchmark`.

---

### Task 6: Reversible User-Scope Role Promotion

**Files:**
- Create: `scripts/desktop_ollama_model_promote.ps1`
- Create: `scripts/tests/DesktopOllamaModelPromote.Tests.ps1`
- Modify: `scripts/modules/DesktopOllamaModelTools.psm1`

**Interfaces:**
- Consumes: ready recommendation report no older than 24 hours and exact matching catalog hash.
- Produces: changes only `LLM_FAST_MODEL`, `LLM_CHAT_MODEL`, `LLM_HIGH_MODEL`, `LLM_CODER_MODEL` at user scope; creates atomic rollback and promotion evidence.

- [ ] **Step 1: Write failing promotion tests**

Use Pester mocks for `[Environment]::SetEnvironmentVariable` through module wrappers. Assert:

- no approval performs zero writes and reports `promotion-approval-required`;
- stale, HOLD-lineage, wrong-catalog, or unallowlisted winner reports no writes;
- `keep-baseline` keeps the old role value;
- approved ready report writes only the four allowed names at `User` scope and mirrors values into the current process;
- a simulated third-write failure restores all four previous values;
- rollback JSON contains only those four names and model-tag values;
- `OLLAMA_MODELS`, embedding settings, machine scope, and DB are never touched.

- [ ] **Step 2: Run RED**

```powershell
Invoke-Pester -Path .\scripts\tests\DesktopOllamaModelPromote.Tests.ps1 -Output Detailed
```

Expected: FAIL because promotion functions and script are absent.

- [ ] **Step 3: Implement binding map and rollback functions**

Use this exact role map:

```powershell
$RoleEnvironmentMap = [ordered]@{
    fast = 'LLM_FAST_MODEL'
    main = 'LLM_CHAT_MODEL'
    high = 'LLM_HIGH_MODEL'
    coder = 'LLM_CODER_MODEL'
}
```

`Get-AwxUserModelBindings` reads those four names at `User` scope. `Set-AwxUserModelBindings` validates every value against the union of approved candidates and declared baselines, writes user scope, and sets the same `$env:` value for immediate verification. `Restore-AwxUserModelBindings` restores an old value or removes only that user-scope variable when the snapshot value was absent.

Append `Get-AwxUserModelBindings`, `Set-AwxUserModelBindings`, and `Restore-AwxUserModelBindings` to the module's `Export-ModuleMember` list.

```powershell
function Get-AwxUserModelBindings {
    $out = [ordered]@{}
    foreach ($name in @('LLM_FAST_MODEL','LLM_CHAT_MODEL','LLM_HIGH_MODEL','LLM_CODER_MODEL')) {
        $out[$name] = [Environment]::GetEnvironmentVariable($name, 'User')
    }
    return $out
}

function Set-AwxUserModelBindings {
    param([Parameter(Mandatory)][hashtable]$Bindings,[Parameter(Mandatory)][string[]]$AllowedModelTags)
    foreach ($name in @('LLM_FAST_MODEL','LLM_CHAT_MODEL','LLM_HIGH_MODEL','LLM_CODER_MODEL')) {
        if (-not $Bindings.ContainsKey($name)) { continue }
        $value = [string]$Bindings[$name]
        if ($value -notin $AllowedModelTags) { throw 'promotion-model-not-allowlisted' }
        [Environment]::SetEnvironmentVariable($name, $value, 'User')
        Set-Item -LiteralPath ("Env:{0}" -f $name) -Value $value
    }
}

function Restore-AwxUserModelBindings {
    param([Parameter(Mandatory)][hashtable]$Snapshot)
    foreach ($name in @('LLM_FAST_MODEL','LLM_CHAT_MODEL','LLM_HIGH_MODEL','LLM_CODER_MODEL')) {
        $oldValue = $Snapshot[$name]
        [Environment]::SetEnvironmentVariable($name, $oldValue, 'User')
        if ($null -eq $oldValue) { Remove-Item -LiteralPath ("Env:{0}" -f $name) -ErrorAction SilentlyContinue }
        else { Set-Item -LiteralPath ("Env:{0}" -f $name) -Value ([string]$oldValue) }
    }
}
```

- [ ] **Step 4: Implement the promotion script**

```powershell
[CmdletBinding(SupportsShouldProcess=$true)]
param(
    [Parameter(Mandatory)][string]$RecommendationReport,
    [string]$CatalogPath = '.\scripts\config\desktop-ollama-model-candidates.json',
    [switch]$ApprovePromotion,
    [Parameter(Mandatory)][string]$OutputPath
)
```

Validate approval, schema, age, catalog hash, `status=ready`, `runtimeLineageVerdict=PASS`, and every role decision before taking the snapshot. Save rollback evidence under `data\agent-handoff\model-autopilot\rollback\user-model-bindings.json`. Apply role values in map order. On any error restore all four and emit `promotion-rolled-back`. `-WhatIf` emits planned hashes and zero writes.

- [ ] **Step 5: Run GREEN and forbidden-surface scan**

```powershell
Invoke-Pester -Path .\scripts\tests\DesktopOllamaModelPromote.Tests.ps1 -Output Detailed
rg -n "Machine|OLLAMA_MODELS|EMBED_|Remove-Item.*-Recurse|ollama rm" .\scripts\desktop_ollama_model_promote.ps1 .\scripts\modules\DesktopOllamaModelTools.psm1
```

Expected: tests PASS. Any `Machine`, `OLLAMA_MODELS`, embedding mutation, recursive removal, or `ollama rm` occurrence is a failure requiring removal.

Suggested commit message, only after separate approval: `feat: add reversible model role promotion`.

---

### Task 7: Tooling Verification and Desktop Handoff

**Files:**
- Modify: `scripts/smoke_gpu_gateway_preflight.ps1`
- Create: `data/agent-handoff/model-autopilot/README.md`

**Interfaces:**
- Consumes: Tasks 1–6.
- Produces: one documented Desktop execution sequence and supporting evidence. No real pull occurs without a separate download approval.

- [ ] **Step 1: Add syntax and `-WhatIf` smoke assertions**

Extend the existing smoke script with a model-autopilot section that parses all four scripts and the module, runs preflight against the existing fake gateway fixture, calls download and promote with `-WhatIf`, and asserts no environment changes. Do not start a second `bootRun` or reuse a conflicting Gradle cache.

- [ ] **Step 2: Run all tooling tests**

```powershell
Invoke-Pester -Path .\scripts\tests\DesktopOllamaModelCatalog.Tests.ps1,.\scripts\tests\DesktopOllamaModelTools.Tests.ps1,.\scripts\tests\DesktopOllamaModelPreflight.Tests.ps1,.\scripts\tests\DesktopOllamaModelDownload.Tests.ps1,.\scripts\tests\DesktopOllamaModelBenchmark.Tests.ps1,.\scripts\tests\DesktopOllamaModelPromote.Tests.ps1 -Output Detailed
```

Expected: all tests PASS, zero failed.

- [ ] **Step 3: Run read-only Desktop preflight**

```powershell
$evidenceRoot = Join-Path (Get-Location) 'data\agent-handoff\model-autopilot'
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
powershell -NoProfile -File .\scripts\desktop_ollama_model_preflight.ps1 -PrimaryEndpoint 'http://127.0.0.1:11434' -FastEndpoint 'http://127.0.0.1:11435' -ModelsRoot 'E:\models' -OutputPath (Join-Path $evidenceRoot 'preflight.json')
```

Expected: `status=ready`; otherwise stop with the emitted single reason. Do not download on HOLD.

- [ ] **Step 4: Run mutation-free planned download and promotion**

```powershell
powershell -NoProfile -File .\scripts\desktop_ollama_model_download.ps1 -ModelTag 'qwen3.5:9b' -Endpoint 'http://127.0.0.1:11435' -ModelsRoot 'E:\models' -WhatIf -OutputPath (Join-Path $evidenceRoot 'download-whatif.json')
powershell -NoProfile -File .\scripts\desktop_ollama_model_promote.ps1 -RecommendationReport (Join-Path $evidenceRoot 'recommendation.json') -WhatIf -OutputPath (Join-Path $evidenceRoot 'promote-whatif.json')
```

The promotion `-WhatIf` is expected to HOLD until a real benchmark report exists; zero environment writes is the success condition.

- [ ] **Step 5: Write the Desktop handoff README**

Document the exact Phase 0 preflight, Phase 2 four-model serial download order, Phase 3 benchmark, `-WhatIf` promote, explicit approved promote, rollback file, reason codes, and the rule that downloaded models are never deleted automatically. Include only model names, endpoint labels, hashes, booleans, counts, and commands.

- [ ] **Step 6: Record the checkpoint**

Record test counts, evidence file SHA-256 values, and `runtimeLineageVerdict`. If only fake/read-only evidence exists, the verdict remains HOLD.

Suggested commit message, only after separate approval: `test: verify desktop ollama model tooling`.

---

## Plan Completion Gate

- All six Pester suites pass.
- `-WhatIf` proves zero pull and zero environment mutation.
- Read-only preflight proves exactly one RTX 3060 lane and one RTX 3090 lane, both loopback endpoints, `E:\models`, and at least 65GiB free.
- No output contains raw prompts, responses, credentials, UUIDs, or backing paths.
- A real download remains separately approval-gated.
- Application integration is implemented only through `2026-07-31-desktop-local-model-autopilot-app-integration-implementation.md` after this report schema is stable.
