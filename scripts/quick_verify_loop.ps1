#requires -Version 5.1
<#
.SYNOPSIS
  quick_verify_loop.ps1 — save->reload->check shortened verification loop
  (directive P7: "수정 내용 10초 안에 확인").

.DESCRIPTION
  Fetches a served demo/display URL, hashes the response, compares with the
  local source file hash, and reports whether the user-visible change is
  live within the latency budget (default 10s). Read-only over the server.

.EXAMPLE
  powershell -NoProfile -File scripts/quick_verify_loop.ps1 `
    -Path /assets/display/meta/receiver.js -SourceFile main/resources/static/assets/display/meta/receiver.js

.PARAMETER Path
  URL path under -BaseUrl (e.g. /chat, /assets/display/meta/index.html).
.PARAMETER SourceFile
  Repo-relative file whose sha256 must match the served bytes.
.PARAMETER TimeoutMs
  Feedback budget; verdict latencyExceeded when the fetch exceeds it.
.PARAMETER ExpectText
  Optional substring that must appear in the served body.

Exit codes: 0 live-match within budget, 4 mismatch/stale/timeout, 2 usage/io.
Output: JSON only on stdout.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18180',
    [Parameter(Mandatory = $true)][string]$Path,
    [string]$SourceFile,
    [string]$ExpectText,
    [int]$TimeoutMs = 10000
)

$ErrorActionPreference = 'Stop'
$result = [ordered]@{
    schemaVersion = 'awx.quick-verify-loop.v1'
    url           = ($BaseUrl.TrimEnd('/') + $Path)
    sourceFile    = $SourceFile
    timeoutMs     = $TimeoutMs
}

$watch = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $response = Invoke-WebRequest -Uri $result.url -UseBasicParsing `
        -TimeoutSec ([math]::Ceiling($TimeoutMs / 1000))
} catch {
    $watch.Stop()
    $result.status = 'unreachable'
    $result.error = $_.Exception.Message
    $result.latencyMs = $watch.ElapsedMilliseconds
    $result.ok = $false
    [Console]::Out.Write(($result | ConvertTo-Json -Compress))
    exit 4
}
$watch.Stop()

$bytes = $response.RawContentStream.ToArray()
if (-not $bytes -or $bytes.Length -eq 0) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($response.Content)
}
$servedHash = (Get-FileHash -InputStream ([System.IO.MemoryStream]::new($bytes)) -Algorithm SHA256).Hash.ToLower()
$body = [System.Text.Encoding]::UTF8.GetString($bytes)

$result.latencyMs = $watch.ElapsedMilliseconds
$result.httpStatus = [int]$response.StatusCode
$result.servedSha256 = $servedHash
$result.withinBudget = ($result.latencyMs -le $TimeoutMs)

if ($SourceFile) {
    if (Test-Path -LiteralPath $SourceFile -PathType Leaf) {
        $sourceHash = (Get-FileHash -LiteralPath $SourceFile -Algorithm SHA256).Hash.ToLower()
        $result.sourceSha256 = $sourceHash
        $result.servedMatch = ($servedHash -eq $sourceHash)
    } else {
        $result.servedMatch = $false
        $result.error = "source-missing:$SourceFile"
    }
}
if ($ExpectText) {
    $result.expectTextFound = $body.Contains($ExpectText)
}

$fail = @()
if ($result.httpStatus -ne 200) { $fail += 'http-not-200' }
if ($SourceFile -and -not $result.servedMatch) { $fail += 'served-stale-or-mismatch' }
if ($ExpectText -and -not $result.expectTextFound) { $fail += 'expect-text-missing' }
if (-not $result.withinBudget) { $fail += 'latency-exceeded' }
$result.failures = $fail
$result.ok = ($fail.Count -eq 0)
$result.status = $(if ($result.ok) { 'live-match' } else { $fail -join '+' })
[Console]::Out.Write(($result | ConvertTo-Json -Compress))
exit $(if ($result.ok) { 0 } else { 4 })
