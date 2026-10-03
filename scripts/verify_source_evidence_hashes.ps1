# scripts/verify_source_evidence_hashes.ps1
# Verify live source identity against an evidence doc's `path` + SHA-256 pairs,
# or against a JSON object map (-HashJson / -HashKey). A MATCH means the
# evidence line anchors are directly usable; a DIFF means re-anchor by
# method/field before any patch (never patch by stale line numbers).
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify_source_evidence_hashes.ps1 `
#     -EvidenceMd docs/codex/M21222AIN_SOURCE_EVIDENCE_2026-09-24.md [-Root .] [-Json]
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify_source_evidence_hashes.ps1 `
#     -HashJson <final-source-hashes.json> [-HashKey sourceHashes] [-Root .] [-Json]
[CmdletBinding(DefaultParameterSetName = 'EvidenceMd')]
param(
  [Parameter(ParameterSetName = 'EvidenceMd', Mandatory = $true)][string]$EvidenceMd,
  [Parameter(ParameterSetName = 'HashJson', Mandatory = $true)][string]$HashJson,
  [Parameter(ParameterSetName = 'HashJson')][string]$HashKey = 'sourceHashes',
  [string]$Root = '.',
  [switch]$Json
)
$ErrorActionPreference = 'Stop'

function ConvertTo-HashRows([object[]]$Pairs, [string]$RootPath) {
  $rows = @()
  $seen = @{}
  foreach ($pair in $Pairs) {
    $rel = [string]$pair.Path
    if ($seen.ContainsKey($rel)) { continue }
    $seen[$rel] = $true
    $exp = ([string]$pair.Expected).ToLower()
    $p = Join-Path $RootPath ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)
    $row = [ordered]@{ path = $rel; expectedSha256 = $exp; actualSha256 = $null; status = '' }
    if ($exp -notmatch '^[0-9a-f]{64}$') {
      $row.status = 'DIFF'
      $row.note = 'hash-malformed'
    } elseif (Test-Path -LiteralPath $p) {
      $act = (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.ToLower()
      $row.actualSha256 = $act
      $row.status = $(if ($act -eq $exp) { 'MATCH' } else { 'DIFF' })
    } else {
      $row.status = 'MISSING'
    }
    $rows += [pscustomobject]$row
  }
  return $rows
}

function Write-HashSummary($rows, $sourceLabel, [switch]$AsJson) {
  $diffRows = @($rows | Where-Object { $_.status -eq 'DIFF' })
  $summary = [ordered]@{
    schemaVersion = 'awx.source-evidence-hash-verify.v1'
    source        = $sourceLabel
    root          = (Resolve-Path $Root).Path
    checkedAtUtc  = (Get-Date).ToUniversalTime().ToString('o')
    pairs         = @($rows).Count
    uniqueFiles   = @($rows).Count
    match         = @($rows | Where-Object { $_.status -eq 'MATCH' }).Count
    diff          = $diffRows.Count
    missing       = @($rows | Where-Object { $_.status -eq 'MISSING' }).Count
    diffPaths     = @($diffRows | ForEach-Object { $_.path })
    note          = 'MATCH = evidence line anchors usable; DIFF = re-anchor by method/field before patching.'
  }
  if ($AsJson) {
    $out = [ordered]@{}
    foreach ($key in $summary.Keys) { $out[$key] = $summary[$key] }
    $out.rows = @($rows)
    $out | ConvertTo-Json -Depth 5
  } else {
    foreach ($r in $rows) { '{0,-8} {1}' -f $r.status, $r.path }
    "summary: pairs=$($summary.pairs) unique=$($summary.uniqueFiles) match=$($summary.match) diff=$($summary.diff) missing=$($summary.missing)"
    if ($summary.diff -gt 0) { "DIFF paths (re-anchor before patching): $($summary.diffPaths -join ', ')" }
  }
}

if ($PSCmdlet.ParameterSetName -eq 'EvidenceMd') {
  if (-not (Test-Path -LiteralPath $EvidenceMd)) { Write-Error "evidence doc not found: $EvidenceMd"; exit 1 }
  $ev = Get-Content -Raw -Encoding UTF8 $EvidenceMd
  $ms = [regex]::Matches($ev, '`(main/[^`]+)`\s+SHA-256: `([0-9a-f]{64})`')
  $pairs = @()
  foreach ($m in $ms) {
    $pairs += [pscustomobject]@{ Path = $m.Groups[1].Value; Expected = $m.Groups[2].Value }
  }
  $rows = ConvertTo-HashRows $pairs $Root
  $diffRows = @($rows | Where-Object { $_.status -eq 'DIFF' })
  $summary = [ordered]@{
    schemaVersion = 'awx.source-evidence-hash-verify.v1'
    evidenceMd    = (Resolve-Path -LiteralPath $EvidenceMd).Path
    root          = (Resolve-Path $Root).Path
    checkedAtUtc  = (Get-Date).ToUniversalTime().ToString('o')
    pairs         = $ms.Count
    uniqueFiles   = @($rows).Count
    match         = @($rows | Where-Object { $_.status -eq 'MATCH' }).Count
    diff          = $diffRows.Count
    missing       = @($rows | Where-Object { $_.status -eq 'MISSING' }).Count
    diffPaths     = @($diffRows | ForEach-Object { $_.path })
    note          = 'MATCH = evidence line anchors usable; DIFF = re-anchor by method/field before patching.'
  }
  if ($Json) {
    $out = [ordered]@{}
    foreach ($key in $summary.Keys) { $out[$key] = $summary[$key] }
    $out.rows = @($rows)
    $out | ConvertTo-Json -Depth 5
  } else {
    foreach ($r in $rows) { '{0,-8} {1}' -f $r.status, $r.path }
    "summary: pairs=$($summary.pairs) unique=$($summary.uniqueFiles) match=$($summary.match) diff=$($summary.diff) missing=$($summary.missing)"
    if ($summary.diff -gt 0) { "DIFF paths (re-anchor before patching): $($summary.diffPaths -join ', ')" }
  }
  exit 0
}

if (-not (Test-Path -LiteralPath $HashJson)) { Write-Error "hash json not found: $HashJson"; exit 1 }
$doc = Get-Content -Raw -Encoding UTF8 $HashJson | ConvertFrom-Json
$prop = $doc.PSObject.Properties[$HashKey]
if (-not $prop) { Write-Error "hash key missing: $HashKey"; exit 1 }
$pairs = @()
foreach ($entry in $prop.Value.PSObject.Properties) {
  $pairs += [pscustomobject]@{ Path = $entry.Name; Expected = [string]$entry.Value }
}
$rows = ConvertTo-HashRows $pairs $Root
Write-HashSummary $rows $HashJson -AsJson:$Json
exit 0
