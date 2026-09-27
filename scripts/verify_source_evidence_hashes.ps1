# scripts/verify_source_evidence_hashes.ps1
# Verify live source identity against an evidence doc's `path` + SHA-256 pairs.
# A MATCH means the evidence doc's line anchors are directly usable; a DIFF means
# re-anchor by method/field before any patch (never patch by stale line numbers).
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify_source_evidence_hashes.ps1 `
#     -EvidenceMd docs/codex/M21222AIN_SOURCE_EVIDENCE_2026-09-24.md [-Root .] [-Json]
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$EvidenceMd,
  [string]$Root = '.',
  [switch]$Json
)
$ErrorActionPreference = 'Stop'

if (-not (Test-Path $EvidenceMd)) { Write-Error "evidence doc not found: $EvidenceMd"; exit 1 }
$ev = Get-Content -Raw -Encoding UTF8 $EvidenceMd
$ms = [regex]::Matches($ev, '`(main/[^`]+)`\s+SHA-256: `([0-9a-f]{64})`')

$rows = @()
$seen = @{}
foreach ($m in $ms) {
  $rel = $m.Groups[1].Value
  if ($seen.ContainsKey($rel)) { continue }
  $seen[$rel] = $true
  $exp = $m.Groups[2].Value
  $p = Join-Path $Root ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)
  $row = [ordered]@{ path = $rel; expectedSha256 = $exp; actualSha256 = $null; status = '' }
  if (Test-Path $p) {
    $act = (Get-FileHash -Algorithm SHA256 $p).Hash.ToLower()
    $row.actualSha256 = $act
    $row.status = $(if ($act -eq $exp) { 'MATCH' } else { 'DIFF' })
  } else {
    $row.status = 'MISSING'
  }
  $rows += [pscustomobject]$row
}

$diffRows = @($rows | Where-Object { $_.status -eq 'DIFF' })
$summary = [ordered]@{
  schemaVersion = 'awx.source-evidence-hash-verify.v1'
  evidenceMd    = (Resolve-Path $EvidenceMd).Path
  root          = (Resolve-Path $Root).Path
  checkedAtUtc  = (Get-Date).ToUniversalTime().ToString('o')
  pairs         = $ms.Count
  uniqueFiles   = $rows.Count
  match         = @($rows | Where-Object { $_.status -eq 'MATCH' }).Count
  diff          = $diffRows.Count
  missing       = @($rows | Where-Object { $_.status -eq 'MISSING' }).Count
  diffPaths     = @($diffRows | ForEach-Object { $_.path })
  note          = 'MATCH = evidence line anchors usable; DIFF = re-anchor by method/field before patching.'
}

if ($Json) {
  $out = $summary.Clone()
  $out.rows = $rows
  $out | ConvertTo-Json -Depth 5
} else {
  foreach ($r in $rows) { '{0,-8} {1}' -f $r.status, $r.path }
  "summary: pairs=$($summary.pairs) unique=$($summary.uniqueFiles) match=$($summary.match) diff=$($summary.diff) missing=$($summary.missing)"
  if ($summary.diff -gt 0) { "DIFF paths (re-anchor before patching): $($summary.diffPaths -join ', ')" }
}
exit 0
