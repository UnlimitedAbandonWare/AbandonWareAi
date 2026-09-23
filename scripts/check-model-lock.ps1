#requires -Version 5.1
<#
.SYNOPSIS
  Verify demo-1 Ollama model lock vs live inventory + SSOT files.
  Never prints API keys.
#>
$ErrorActionPreference = 'Continue'
$Root = if ($PSScriptRoot) {
  (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
} else {
  'C:\AbandonWare\demo-1\demo-1\src'
}
if (-not (Test-Path -LiteralPath (Join-Path $Root 'AGENTS.md'))) {
  $cand = 'C:\AbandonWare\demo-1\demo-1\src'
  if (Test-Path -LiteralPath (Join-Path $cand 'AGENTS.md')) { $Root = $cand }
}

$Allowed = @(
  'smtek/Qwen3.8-27B:Q3_K_XL',
  'qwen3-embedding:4b',
  'qwen3.8:27b',
  'gemma4:31b',
  'qwen3.6:27b',
  'gemma4:12b',
  'qwen3.5:9b',
  'gemma4:latest',
  'gemma4:26b',
  'nomic-embed-text:latest',
  'qwen3-embedding:latest',
  'bge-m3:latest',
  'qwen3-vl:8b'
)

$BannedDefaults = @(
  'qwen3:8b',
  'qwen3:30b',
  'qwen3-coder:30b',
  'gemma3:27b',
  'gemma3:4b',
  'qwen2.5:7b-instruct',
  'qwen2.5-7b-instruct'
)

$RoleExpect = @{
  fast   = 'qwen3.5:9b'
  chat   = 'gemma4:26b'
  judge  = 'smtek/Qwen3.8-27B:Q3_K_XL'
  coder  = 'smtek/Qwen3.8-27B:Q3_K_XL'
  vision = 'qwen3-vl:8b'
  embed  = 'qwen3-embedding:4b'
}

$fail = 0
function Fail([string]$msg) { $script:fail++; Write-Host "[FAIL] $msg" -ForegroundColor Red }
function Ok([string]$msg) { Write-Host "[OK] $msg" -ForegroundColor Green }
function Info([string]$msg) { Write-Host "[INFO] $msg" }

Write-Host "=== check-model-lock ==="
Write-Host "Root=$Root"
Write-Host ("generated=" + (Get-Date -Format o))

# 1) Live ollama ls
$live = @()
try {
  $raw = & ollama ls 2>&1 | Out-String
  Info "ollama ls:`n$raw"
  foreach ($line in ($raw -split "`r?`n")) {
    if ($line -match '^\s*NAME\b') { continue }
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $name = ($line -split '\s+')[0]
    if ($name) { $live += $name }
  }
} catch {
  Fail "ollama ls failed: $($_.Exception.Message)"
}

foreach ($m in $Allowed) {
  if ($live.Count -gt 0 -and ($live -notcontains $m)) {
    Fail "allowed model missing from live ollama ls: $m"
  }
}
foreach ($m in $live) {
  if ($Allowed -notcontains $m) {
    Info "live model not in lock allowlist (add if intentional): $m"
  }
}
if ($live.Count -gt 0) { Ok ("live model count=" + $live.Count) }

# 2) Probe tags APIs (optional)
foreach ($port in 11434, 11435) {
  try {
    $r = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$port/api/tags" -TimeoutSec 2
    $names = @($r.models | ForEach-Object { $_.name })
    Info ("api/tags :" + $port + " count=" + $names.Count)
  } catch {
    Info ("api/tags :" + $port + " unreachable")
  }
}

# 3) Scan SSOT files for banned Spring defaults
$scanFiles = @(
  'docs\API_ROUTING_SPEC.md',
  'configs\api-routing.yaml',
  'main\resources\configs\api-routing.yaml',
  'main\resources\application-llm.yaml',
  'main\resources\application-local.yml',
  'main\resources\application.yml',
  'main\resources\application-machine.yml',
  'main\resources\application-meta-display.yml',
  'app\src\main\resources\configs\models.manifest.yaml',
  'configs\models.manifest.yaml',
  'AGENTS.md',
  'scripts\check-model-lock.ps1'
) | ForEach-Object { Join-Path $Root $_ } | Where-Object { Test-Path -LiteralPath $_ }

# Patterns that look like Spring default wiring of banned tags
$defaultPatterns = @(
  'LLM_FAST_MODEL:qwen3:8b',
  'LLM_JUDGE_MODEL:qwen3:30b',
  'LLM_CODER_MODEL:qwen3-coder:30b',
  'LLM_CHAT_MODEL:gemma3:27b',
  'chat-model: \$\{LLM_CHAT_MODEL:gemma3:27b\}',
  'chat-model: gemma3:27b',
  'LLM_GEMMA3_4B_MODEL:gemma3:4b',
  'model: \$\{LLM_FAST_MODEL:qwen3:8b\}',
  'model: \$\{LLM_JUDGE_MODEL:qwen3:30b\}',
  'model: \$\{LLM_CODER_MODEL:qwen3-coder:30b\}'
)

foreach ($f in $scanFiles) {
  $rel = $f.Substring($Root.Length).TrimStart('\')
  $text = Get-Content -LiteralPath $f -Raw -ErrorAction SilentlyContinue
  if (-not $text) { continue }

  # Allow alias_to_installed / banned lists / docs tables mentioning dead tags
  $isAliasDoc = ($rel -match 'api-routing\.yaml|API_ROUTING_SPEC|AGENTS\.md|check-model-lock')

  foreach ($b in $BannedDefaults) {
    # Spring default pattern: ${ENV:banned} or bare "chat-model: banned"
    $rxDefault = [regex]::Escape('${') + '[A-Z0-9_]+:' + [regex]::Escape($b) + [regex]::Escape('}')
    if ([regex]::IsMatch($text, $rxDefault)) {
      Fail "$rel wires banned default via env fallback: $b"
    }
  }
  if ($text -match '(?m)^\s*chat-model:\s*gemma3:27b\s*$') {
    Fail "$rel has bare chat-model: gemma3:27b"
  }
  if ($text -match '(?m)^\s*model:\s*qwen3:8b\s*$') {
    Fail "$rel has bare model: qwen3:8b"
  }

  # Required installed defaults present in llm yaml
  if ($rel -match 'application-llm\.yaml') {
    foreach ($pair in @(
      @{k='fast'; v='qwen3.5:9b'},
      @{k='judge'; v='smtek/Qwen3.8-27B:Q3_K_XL'},
      @{k='coder'; v='smtek/Qwen3.8-27B:Q3_K_XL'},
      @{k='chat'; v='gemma4:26b'},
      @{k='vision'; v='qwen3-vl:8b'}
    )) {
      if ($pair.k -eq 'fast' -and $text -notmatch [regex]::Escape('LLM_FAST_MODEL:qwen3.5:9b')) {
        Fail "$rel missing LLM_FAST_MODEL:qwen3.5:9b"
      }
      if ($pair.k -eq 'judge' -and $text -notmatch [regex]::Escape('LLM_JUDGE_MODEL:smtek/Qwen3.8-27B:Q3_K_XL')) {
        Fail "$rel missing LLM_JUDGE_MODEL:smtek/Qwen3.8-27B:Q3_K_XL"
      }
      if ($pair.k -eq 'coder' -and $text -notmatch [regex]::Escape('LLM_CODER_MODEL:smtek/Qwen3.8-27B:Q3_K_XL')) {
        Fail "$rel missing LLM_CODER_MODEL:smtek/Qwen3.8-27B:Q3_K_XL"
      }
      if ($pair.k -eq 'chat' -and $text -notmatch [regex]::Escape('LLM_CHAT_MODEL:gemma4:26b') -and $text -notmatch [regex]::Escape('chat-model: ${LLM_CHAT_MODEL:gemma4:26b}')) {
        # soft: chat often already gemma4:26b
        if ($text -notmatch 'gemma4:26b') { Fail "$rel missing gemma4:26b chat default" }
      }
      if ($pair.k -eq 'vision' -and $text -notmatch [regex]::Escape('qwen3-vl:8b')) {
        Fail "$rel missing vision qwen3-vl:8b"
      }
    }
    Ok "$rel role defaults checked"
  }

  if ($rel -match 'api-routing\.yaml') {
    if ($text -notmatch [regex]::Escape('smtek/Qwen3.8-27B:Q3_K_XL')) {
      Fail "$rel missing Q3_K_XL"
    } else {
      Ok "$rel contains Q3_K_XL"
    }
  }
}

# 4) AGENTS lock marker
$agents = Join-Path $Root 'AGENTS.md'
if (Test-Path -LiteralPath $agents) {
  $at = Get-Content -LiteralPath $agents -Raw
  if ($at -match 'DEMO1-OLLAMA-MODEL-LOCK') { Ok 'AGENTS.md has DEMO1-OLLAMA-MODEL-LOCK' }
  else { Fail 'AGENTS.md missing DEMO1-OLLAMA-MODEL-LOCK section' }
} else {
  Fail 'AGENTS.md not found'
}

if ($fail -eq 0) {
  Write-Host "RESULT=PASS" -ForegroundColor Green
  exit 0
} else {
  Write-Host "RESULT=FAIL count=$fail" -ForegroundColor Red
  exit 1
}
