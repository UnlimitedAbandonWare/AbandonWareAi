param(
  [string]$RepoRoot = ""
)
$ErrorActionPreference = "Stop"
if (-not $RepoRoot) {
  $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..")).Path
  if (-not (Test-Path (Join-Path $RepoRoot "configs\api-routing.yaml"))) {
    $RepoRoot = (Get-Location).Path
  }
}
Set-Location $RepoRoot

$outDir = Join-Path $RepoRoot ".agents\skills\demo1-api-spec-drift-guard\references"
$invDir = Join-Path $RepoRoot ".agents\skills\demo1-api-routing-inventory\references"
New-Item -ItemType Directory -Force -Path $outDir, $invDir | Out-Null

$report = @()
$report += "# API drift report"
$report += ""
$report += "generated: $(Get-Date -Format o)"
$report += "repoRoot: $RepoRoot"
$report += ""

# routing yaml presence
$yaml = Join-Path $RepoRoot "configs\api-routing.yaml"
$spec = Join-Path $RepoRoot "docs\API_ROUTING_SPEC.md"
$report += "## Files"
$report += "- configs/api-routing.yaml present: $(Test-Path $yaml)"
$report += "- docs/API_ROUTING_SPEC.md present: $(Test-Path $spec)"
$report += ""

# Ollama probe (no secrets)
$report += "## Ollama"
$ollama = Get-Command ollama -ErrorAction SilentlyContinue
if ($ollama) {
  try {
    $ls = & ollama ls 2>&1 | Out-String
    $report += '```'
    $report += $ls.TrimEnd()
    $report += '```'
  } catch {
    $report += "ollama ls failed: $($_.Exception.Message)"
  }
} else {
  $report += "ollama CLI not on PATH"
}
$report += ""

# Env key presence only
$report += "## Env key presence (names only)"
$names = @(
  "OPENAI_API_KEY","GROQ_API_KEY","GEMINI_API_KEY","BRAVE_API_KEY","TAVILY_API_KEY",
  "SERPAPI_API_KEY","NAVER_CLIENT_ID","NAVER_CLIENT_SECRET","DEEPGRAM_API_KEY",
  "SONIOX_API_KEY","PINECONE_API_KEY","OLLAMA_HOST","LOCAL_LLM_ENABLED"
)
foreach ($n in $names) {
  $v = [Environment]::GetEnvironmentVariable($n, "Process")
  if (-not $v) { $v = [Environment]::GetEnvironmentVariable($n, "User") }
  if (-not $v) { $v = [Environment]::GetEnvironmentVariable($n, "Machine") }
  $present = -not [string]::IsNullOrWhiteSpace($v)
  $len = if ($present) { $v.Length } else { 0 }
  $report += "- ${n}: present=$present length=$len"
}
$report += ""

# Extract model-ish tokens from routing yaml (best-effort, no parser dependency)
$report += "## api-routing.yaml model/alias hints"
if (Test-Path $yaml) {
  Select-String -LiteralPath $yaml -Pattern "model:|alias|ollama|openai|groq|gemini|endpoint|baseUrl|base_url" |
    Select-Object -First 80 |
    ForEach-Object { $report += ("- L{0}: {1}" -f $_.LineNumber, $_.Line.Trim()) }
} else {
  $report += "- missing yaml"
}
$report += ""
$report += "## Agent action"
$report += "If a skill, comment, or old Codex note names a model/endpoint/field not listed above or rejected by a live probe, update configs/api-routing.yaml and the existing provider seam to the live contract, then fix the stale prose last."

$reportPath = Join-Path $outDir "last-drift-report.md"
$invPath = Join-Path $invDir "live-inventory.md"
$utf8 = New-Object System.Text.UTF8Encoding $false
$text = ($report -join "`r`n") + "`r`n"
[System.IO.File]::WriteAllText($reportPath, $text, $utf8)
[System.IO.File]::WriteAllText($invPath, $text, $utf8)
Write-Output "Wrote $reportPath"
Write-Output "Wrote $invPath"