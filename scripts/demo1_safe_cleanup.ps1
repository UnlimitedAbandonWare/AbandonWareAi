#requires -Version 5.1
<#
.SYNOPSIS
  demo-1 안전 정리 — WhatIf(보고만)가 기본이고 -Apply일 때만 실제 삭제한다.
.DESCRIPTION
  허용 목록(이번 스프린트 안전 범위)만 대상으로 한다:
    build/, logs/, __reports__/  — 통째로 삭제 대상
    _patch_artifacts/, autoevolve_debug/, output/, verification/, pki-validation/
                                 — 하위에 파일이 하나도 없을 때(빈 잔여)만 삭제
    **/__pycache__/              — 바이트캐시 디렉터리
  모든 후보는 삭제 전 루트 내부 + 거부 목록 검사를 통과해야 한다.
  거부: main/ app/ configs/ config/ src/ data/ var/ .secrets/ .git/
        __patch_drop__/ frontend/ .agents/ (소스 본체·시크릿·DB·리스·handoff 보호)
  결과 JSON: var/debug/safe-cleanup-<yyyyMMdd-HHmmss>.json (디렉터리 없으면 생성).
  .next, __patch_drop__ 산출물, var/*, export, lmsdb*, lease, handoff는 범위 밖.
#>
[CmdletBinding()]
param(
    [switch]$Apply,                 # 실삭제. 미지정 시 WhatIf 보고만.
    [Alias('WhatIf')]
    [switch]$DryRun,                # 명시적 드라이런. -Apply와 동시 지정 시 드라이런이 우선(안전).
    [string]$Root = ''              # 기본: 이 스크립트의 상위(프로젝트 루트)
)
$ErrorActionPreference = 'Stop'

# --- 루트 확인 (오경로 방지) ---
if ([string]::IsNullOrWhiteSpace($Root)) { $Root = Split-Path -Parent $PSScriptRoot }
$rootPath = [IO.Path]::GetFullPath($Root)
if (-not (Test-Path -LiteralPath (Join-Path $rootPath 'AGENTS.md')) -or
    -not (Test-Path -LiteralPath (Join-Path $rootPath 'build.gradle.kts'))) {
    Write-Error "project root sanity failed: $rootPath (AGENTS.md / build.gradle.kts 없음)"
    exit 2
}
$mode = if ($Apply -and -not $DryRun) { 'apply' } else { 'whatif' }

# --- 거부 목록: 후보 상대경로가 이 접두와 일치하면 절대 삭제하지 않는다 ---
$denyRegex = '^(main|app|configs|config|src|data|var|\.secrets|\.git|\.env\.shared|__patch_drop__|frontend|\.agents)(/|$)'

# --- __pycache__ 탐색: 거부·대용량·무관 디렉터리는 내려가지 않고 가지치기 ---
$skipDirs = @(
    '.git','.gradle','.gradle-desktop-nova-focus','.secrets','.pytest_cache','.well-known',
    '.agents','.cline','.clinerules','.codex','.devin','.grok','.windsurf','.env.shared',
    'build','logs','var','data','frontend','docs','agent-prompts','gradle','bin','node_modules',
    '__patch_drop__','__reports__','_patch_artifacts','autoevolve_debug','output','verification','pki-validation'
)
$pycache = New-Object 'System.Collections.Generic.List[string]'
$stack = New-Object 'System.Collections.Generic.List[string]'
$stack.Add($rootPath)
while ($stack.Count -gt 0) {
    $cur = $stack[$stack.Count - 1]; $stack.RemoveAt($stack.Count - 1)
    try { $sub = (New-Object IO.DirectoryInfo($cur)).EnumerateDirectories() }
    catch { continue }
    foreach ($di in $sub) {
        if ($di.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }  # junction 루프 방지
        $name = $di.Name
        if ($name -eq '__pycache__') { $pycache.Add($di.FullName); continue }
        if ($skipDirs -contains $name.ToLowerInvariant()) { continue }
        $stack.Add($di.FullName)
    }
}

# --- 후보 목록 구성 ---
$targets = New-Object 'System.Collections.Generic.List[object]'
foreach ($n in 'build','logs','__reports__') {
    $targets.Add(@{ rel = $n; path = Join-Path $rootPath $n; kind = 'dir'; onlyIfEmpty = $false })
}
foreach ($n in '_patch_artifacts','autoevolve_debug','output','verification','pki-validation') {
    $targets.Add(@{ rel = $n; path = Join-Path $rootPath $n; kind = 'emptyLeftover'; onlyIfEmpty = $true })
}
foreach ($p in $pycache) {
    $rel = $p.Substring($rootPath.Length).TrimStart('\','/') -replace '\\','/'
    $targets.Add(@{ rel = $rel; path = $p; kind = 'pycache'; onlyIfEmpty = $false })
}

function Get-TreeStat([string]$path) {
    # 중첩 거부 디렉터리는 건너뛰고(SilentlyContinue) 파일 바이트/수만 센다
    $items = @(Get-ChildItem -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue)
    $files = @($items | Where-Object { -not $_.PSIsContainer })
    $bytes = 0L; foreach ($f in $files) { $bytes += $f.Length }
    return @{ bytes = $bytes; files = $files.Count; dirs = ($items.Count - $files.Count) }
}

# --- 평가 + (Apply 시) 삭제 ---
$entries = New-Object 'System.Collections.Generic.List[object]'
foreach ($t in $targets) {
    $e = [ordered]@{ path = $t.rel; kind = $t.kind; exists = $false; action = 'skip'; reason = 'absent'; bytes = 0; files = 0 }
    if (-not (Test-Path -LiteralPath $t.path)) { $entries.Add($e); continue }
    $e.exists = $true
    $full = [IO.Path]::GetFullPath($t.path)
    if (-not $full.StartsWith($rootPath, [StringComparison]::OrdinalIgnoreCase)) { $e.reason = 'outside-root'; $entries.Add($e); continue }
    if ($t.rel -match $denyRegex) { $e.action = 'denied'; $e.reason = 'deny-listed-path'; $entries.Add($e); continue }
    $st = Get-TreeStat $t.path
    $e.bytes = $st.bytes; $e.files = $st.files
    if ($t.onlyIfEmpty -and $st.files -gt 0) { $e.reason = "not-empty($($st.files) files)"; $entries.Add($e); continue }
    if ($mode -eq 'apply') {
        try {
            Remove-Item -LiteralPath $t.path -Recurse -Force -ErrorAction Stop
            $e.action = 'deleted'; $e.reason = 'ok'
        } catch { $e.action = 'failed'; $e.reason = $_.Exception.Message }
    } else { $e.action = 'would-delete'; $e.reason = 'whatif' }
    $entries.Add($e)
}

# --- 보고 JSON ---
$reportDir = Join-Path $rootPath 'var/debug'
if (-not (Test-Path -LiteralPath $reportDir)) { New-Item -ItemType Directory -Path $reportDir -Force | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportPath = Join-Path $reportDir "safe-cleanup-$stamp.json"
$sum = @{ candidates = 0; deleted = 0; wouldDelete = 0; skipped = 0; denied = 0; failed = 0; bytes = 0L }
foreach ($e in $entries) {
    $sum.candidates++
    switch ($e.action) {
        'deleted'      { $sum.deleted++;  $sum.bytes += $e.bytes }
        'would-delete' { $sum.wouldDelete++; $sum.bytes += $e.bytes }
        'denied'       { $sum.denied++ }
        'failed'       { $sum.failed++ }
        default        { $sum.skipped++ }
    }
}
$report = [ordered]@{
    schemaVersion = 'demo1.safe-cleanup.v1'
    mode          = $mode
    root          = $rootPath
    startedAtUtc  = [DateTime]::UtcNow.ToString('o')
    note          = 'build/ 삭제 시 다음 Start-RAG에서 재컴파일 필요. lmsdb*/lease/handoff/.secrets는 항상 비대상.'
    totals        = $sum
    targets       = $entries
}
($report | ConvertTo-Json -Depth 6) | Set-Content -Encoding utf8 $reportPath

# --- 콘솔 요약 ---
foreach ($e in $entries) {
    if (-not $e.exists) { continue }
    $tag = switch ($e.action) { 'deleted' {'DEL'} 'would-delete' {'WHATIF'} 'denied' {'DENY'} 'failed' {'FAIL'} default {'SKIP'} }
    Write-Host ("[{0}] {1}  {2:N0} bytes {3} files  ({4})" -f $tag, $e.path, $e.bytes, $e.files, $e.reason)
}
Write-Host ("mode={0} candidates={1} freed={2:N0} bytes report={3}" -f $mode, $sum.candidates, $sum.bytes, $reportPath)
if ($mode -eq 'whatif') { Write-Host 'dry-run only: 실삭제는 Safe-Cleanup.bat -Apply (사용자 승인 후)' }
exit 0
