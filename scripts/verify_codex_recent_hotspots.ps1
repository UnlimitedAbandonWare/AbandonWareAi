<#
.SYNOPSIS
  verify_codex_recent_hotspots.ps1 — 단발성 코덱스 7일치 핫스팟 검증기 (PowerShell)
.DESCRIPTION
  최근 7일간 코덱스 179개 세션 분석 기반 핵심 5대 핫스팟 계약을 한 방에 검증합니다.
.PARAMETER Suite
  검증 스위트: 'fast' (스크립트/설정 초고속), 'java' (Java 핵심 계약), 'all' (전체)
.PARAMETER Json
  에이전트 헤드리스용 JSON 출력 여부
.EXAMPLE
  .\scripts\verify_codex_recent_hotspots.ps1 -Suite fast
  .\scripts\verify_codex_recent_hotspots.ps1 -Json
#>
[CmdletBinding()]
param(
    [ValidateSet('all', 'fast', 'java')]
    [string]$Suite = 'fast',
    [switch]$Json,
    [switch]$StrictWatchdog
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
$PyScript = Join-Path $ScriptDir "verify_codex_recent_hotspots.py"

$ArgsList = @("-B", $PyScript, "--suite", $Suite)
if ($Json -or $env:AWX_AGENT -eq '1') {
    $ArgsList += "--json"
}
if ($StrictWatchdog) {
    $ArgsList += "--strict-watchdog"
}

$proc = Start-Process -FilePath "python" -ArgumentList $ArgsList -WorkingDirectory $RepoRoot -NoNewWindow -PassThru -Wait
exit $proc.ExitCode
