#requires -Version 5.1
<#
.SYNOPSIS
  demo-1 Ops Surface Thinning (OSTP) — 레일은 두고, 닫힌 종이만 서랍으로.
.DESCRIPTION
  WhatIf(보고만)가 기본이고 -Apply일 때만 이동한다. 삭제가 아니라 Move다.
  대상(보수적):
    root targets-*.json  — targets.json + 최근 -KeepRecentTargets개(mtime)만 유지,
                           나머지 -> agent-prompts/_archive/<yyyyMMdd>/targets/
    agent-prompts/<dir>  — 닫힌 지시서만 archive. 아래는 KEEP:
        * 이름에 날짜(20MMdd)가 없는 구조 디렉토리(agents/data/traits/out 등)
        * ROOM_BLURB* 파일이 있는 디렉토리(다른 에이전트 배달 대기 카드)
        * live 포인터가 참조하는 디렉토리(AGENTS/스킬/문서/스크립트/소스의
          'agent-prompts/<name>' 경로 문자열)
        * in_progress work journal이 이름/slug로 가리키는 디렉토리(in-flight)
        * 닫힘 증거가 없는 신선 디렉토리(애매하면 KEEP)
      닫힘 증거(하나 이상 필요): DONE*/CLOSED*/RETIRED*/STATUS* 마커 파일 또는
        'STATUS: done' 류 행 | 매칭된 저널이 모두 in_progress가 아님 |
        mtime이 -StaleDirDays일 이상.
    빈 leftover dirs(-IncludeEmptyLeftovers 지정 시만): ui-debug-*,
        autoevolve_debug, _patch_artifacts — 파일이 하나라도 있으면 건너뜀.
    여분 .gradle-* 홈: WhatIf에서 나열만. 이동은 -Apply -ApplyGradleHomes 둘 다
        필요(빌드 캐시라 기본 off).
  절대 이동 금지(소스 기준): .agents/ scripts/ main/ app/ configs/ src/ data/
    var/ .secrets/ .git/ __patch_drop__/ frontend/ docs/ build/ logs/
    agent-prompts/_archive/ 자기 자신.
  매니페스트: var/debug/ops-surface-thin-<yyyyMMdd-HHmmss>.json
  -Apply 시 agent-prompts/INDEX.md 재생성(Live vs Archived)과
  _archive/README.md(없을 때만)를 쓴다. 복원 = Move back + INDEX 갱신.
#>
[CmdletBinding()]
param(
    [switch]$Apply,                  # 실이동. 미지정 시 WhatIf 보고만.
    [Alias('WhatIf')]
    [switch]$DryRun,                 # 명시적 드라이런. -Apply 동시 지정 시 우선(안전).
    [int]$KeepRecentTargets = 2,     # root에 남길 최근 targets-*.json 개수(targets.json 별도)
    [int]$StaleDirDays = 14,         # 닫힘 증거로 인정하는 디렉토리 mtime 일수
    [switch]$IncludeEmptyLeftovers,  # 빈 leftover 디렉토리도 대상에 포함
    [switch]$ApplyGradleHomes,       # -Apply와 함께: 여분 .gradle-* 홈도 이동(기본 off)
    [switch]$SkipIndex,              # -Apply 시 INDEX.md 재생성 생략
    [string[]]$KeepDir = @(),        # 추가 KEEP 디렉토리 이름
    [string]$Root = ''               # 기본: 이 스크립트의 상위(프로젝트 루트)
)
$ErrorActionPreference = 'Stop'

# --- 루트 확인 (오경로 방지) ---
if ([string]::IsNullOrWhiteSpace($Root)) { $Root = Split-Path -Parent $PSScriptRoot }
$rootPath = [IO.Path]::GetFullPath($Root)
if (-not (Test-Path -LiteralPath (Join-Path $rootPath 'AGENTS.md')) -or
    -not (Test-Path -LiteralPath (Join-Path $rootPath 'agent-prompts') -PathType Container)) {
    Write-Error "project root sanity failed: $rootPath (AGENTS.md / agent-prompts 없음)"
    exit 2
}
$mode = if ($Apply -and -not $DryRun) { 'apply' } else { 'whatif' }
$stamp = Get-Date -Format 'yyyyMMdd'
$stampFull = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$archiveRoot = Join-Path $rootPath ('agent-prompts/_archive/' + $stamp)
$journalRoot = Join-Path $rootPath 'data/agent-handoff/codex-autonomy'

# --- 소스 기준 절대 이동 금지 ---
$denyRegex = '^(\.agents|scripts|main|app|configs?|src|data|var|\.secrets|\.git|__patch_drop__|frontend|docs|build|logs|agent-prompts/_archive)(/|$)'

function Get-Rel([string]$full) {
    return $full.Substring($rootPath.Length).TrimStart('\', '/') -replace '\\', '/'
}

function Get-Sha256([string]$full) {
    try { return (Get-FileHash -Algorithm SHA256 -LiteralPath $full).Hash.ToLower() }
    catch { return $null }
}

# --- live 포인터 스캔 대상 파일 수집 (agent-prompts/, data/, var/, .git, 산출물 제외) ---
$skipScanDirs = @(
    '.git', '.gradle', '.secrets', '.pytest_cache', '.agents/.cache', 'build', 'logs',
    'var', 'data', 'agent-prompts', '__patch_drop__', 'node_modules', '__reports__',
    'out', '.next', 'target', 'bin', 'obj'
)
$scanExt = @('.md', '.txt', '.ps1', '.bat', '.py', '.json', '.yaml', '.yml', '.java',
             '.html', '.js', '.ts', '.kts', '.xml', '.gradle')
$scanFiles = New-Object 'System.Collections.Generic.List[string]'
foreach ($f in (Get-ChildItem -LiteralPath $rootPath -File -Force -ErrorAction SilentlyContinue)) {
    if ($scanExt -contains $f.Extension.ToLowerInvariant()) { $scanFiles.Add($f.FullName) }
}
$scanStack = New-Object 'System.Collections.Generic.List[string]'
foreach ($top in 'docs', '.agents', 'configs', 'scripts', 'main', 'app', 'frontend', '.devin', '.windsurf', '.grok', '.cline') {
    $p = Join-Path $rootPath $top
    if (Test-Path -LiteralPath $p -PathType Container) { $scanStack.Add($p) }
}
while ($scanStack.Count -gt 0) {
    $cur = $scanStack[$scanStack.Count - 1]; $scanStack.RemoveAt($scanStack.Count - 1)
    try { $sub = (New-Object IO.DirectoryInfo($cur)).EnumerateFileSystemInfos() }
    catch { continue }
    foreach ($it in $sub) {
        if ($it.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
        if ($it.PSIsContainer) {
            if ($skipScanDirs -contains $it.Name.ToLowerInvariant()) { continue }
            $scanStack.Add($it.FullName)
        } elseif ($scanExt -contains $it.Extension.ToLowerInvariant()) {
            $scanFiles.Add($it.FullName)
        }
    }
}

# --- agent-prompts 디렉토리 인벤토리 ---
$promptDirs = @(Get-ChildItem -LiteralPath (Join-Path $rootPath 'agent-prompts') -Directory -Force -ErrorAction SilentlyContinue)

# live 참조: 'agent-prompts/<name>' 경로 문자열이 스캔 파일에 존재하는지 한 번에 검색
$refMap = @{}
foreach ($d in $promptDirs) { $refMap[$d.Name] = New-Object 'System.Collections.Generic.List[string]' }
$names = @($promptDirs | ForEach-Object { [regex]::Escape($_.Name) })
if ($names.Count -gt 0 -and $scanFiles.Count -gt 0) {
    $alt = 'agent-prompts[\\/](?:' + ($names -join '|') + ')(?![A-Za-z0-9_-])'
    $hits = Select-String -Path $scanFiles -Pattern $alt -AllMatches -ErrorAction SilentlyContinue
    foreach ($h in $hits) {
        foreach ($mt in $h.Matches) {
            $dn = $mt.Value -replace '^agent-prompts[\\/]', ''
            if ($refMap.ContainsKey($dn)) {
                $rel = Get-Rel $h.Path
                if (-not $refMap[$dn].Contains($rel)) { $refMap[$dn].Add($rel) }
            }
        }
    }
}

# --- 저널 매치: 이름 직접 언급 또는 정규화 slug 일치/포함 ---
function Get-DirSlug([string]$name) {
    $s = $name -replace '-?20\d{6}.*$', ''
    $s = $s -replace '^(devin|codex|grok|cline)-', ''
    return $s.Trim('-')
}
function Get-TaskNorm([string]$taskId) {
    $t = $taskId -replace '-[0-9a-fA-F]{6,8}$', ''
    $t = $t -replace '-\d{4}(?=-|$)', ''
    $t = $t -replace '^(devin|codex|grok|cline)-', ''
    return $t.Trim('-')
}
$journals = @()
foreach ($jf in (Get-ChildItem -LiteralPath $journalRoot -Recurse -Filter journal.json -Force -ErrorAction SilentlyContinue)) {
    try {
        $raw = Get-Content -LiteralPath $jf.FullName -Raw -ErrorAction Stop
        $j = $raw | ConvertFrom-Json
        $journals += [PSCustomObject]@{
            taskId  = [string]$j.taskId
            status  = [string]$j.status
            purpose = [string]$j.purpose
            raw     = $raw
            norm    = (Get-TaskNorm ([string]$j.taskId))
        }
    } catch { }
}
function Find-Journals([string]$dirName) {
    $slug = Get-DirSlug $dirName
    $found = New-Object 'System.Collections.Generic.List[object]'
    foreach ($j in $journals) {
        $hit = $false
        if ($j.raw -match [regex]::Escape($dirName)) { $hit = $true }
        elseif ($slug.Length -ge 6 -and $j.norm.Length -ge 6 -and
                ($j.norm.Contains($slug) -or $slug.Contains($j.norm))) { $hit = $true }
        elseif ($slug.Length -ge 8 -and $j.purpose -match [regex]::Escape($slug)) { $hit = $true }
        if ($hit) { $found.Add($j) }
    }
    return $found
}

# --- 닫힘 마커 탐지 ---
$doneLineRx = '(?im)^\s*#{0,6}\s*(STATUS\s*[:=]\s*(done|closed|complete|resolved|superseded)|DONE|CLOSED|RESOLVED)\s*[*_`]*\s*$'
function Test-DoneMarker([System.IO.DirectoryInfo]$dir) {
    foreach ($f in (Get-ChildItem -LiteralPath $dir.FullName -File -Force -ErrorAction SilentlyContinue)) {
        if ($f.Name -match '^(DONE|CLOSED|RETIRED|ARCHIVED)(\.|_|$)') { return $f.Name }
        if ($f.Name -match '^(STATUS|ROOM_BLURB|DONE)(\.|_|$)' -or $f.Extension -in '.md', '.txt', '.yaml', '.yml', '.json') {
            try {
                $t = Get-Content -LiteralPath $f.FullName -Raw -ErrorAction Stop
                if ($t -match $doneLineRx) { return ($f.Name + ':status-line') }
            } catch { }
        }
    }
    return $null
}

# --- 분류 ---
$wouldMove = New-Object 'System.Collections.Generic.List[object]'
$kept = New-Object 'System.Collections.Generic.List[object]'
$now = Get-Date

# 1) root targets-*.json
$allTargets = @(Get-ChildItem -LiteralPath $rootPath -File -Filter 'targets*.json' -Force -ErrorAction SilentlyContinue)
$keepTargetNames = @('targets.json')
$stale = @($allTargets | Where-Object { $_.Name -ne 'targets.json' } |
    Sort-Object LastWriteTime -Descending)
$keepRecent = @($stale | Select-Object -First $KeepRecentTargets)
foreach ($k in $keepRecent) { $keepTargetNames += $k.Name }
foreach ($f in $allTargets) {
    $rel = Get-Rel $f.FullName
    if ($keepTargetNames -contains $f.Name) {
        $kept.Add(@{ path = $rel; reason = 'targets-keep'; detail = 'targets.json 또는 최근 ' + $KeepRecentTargets + '개' })
    } else {
        $wouldMove.Add(@{
            from = $rel; to = ('agent-prompts/_archive/' + $stamp + '/targets/' + $f.Name)
            kind = 'file'; reason = 'stale-targets-json'; bytes = $f.Length; sha256 = (Get-Sha256 $f.FullName)
        })
    }
}

# 2) agent-prompts 디렉토리
$keepAlways = @('_archive') + $KeepDir
foreach ($d in $promptDirs) {
    $rel = Get-Rel $d.FullName
    if ($keepAlways -contains $d.Name) { continue }
    $dated = ($d.Name -match '20\d{6}')
    if (-not $dated) {
        $kept.Add(@{ path = $rel; reason = 'structural'; detail = '날짜 없는 구조 디렉토리' }); continue
    }
    $roomBlurb = @(Get-ChildItem -LiteralPath $d.FullName -File -Filter 'ROOM_BLURB*' -Force -ErrorAction SilentlyContinue)
    $doneMarker = Test-DoneMarker $d
    $refs = @($refMap[$d.Name])
    $jm = @(Find-Journals $d.Name)
    $jmInFlight = @($jm | Where-Object { $_.status -eq 'in_progress' })
    $jmClosed = @($jm | Where-Object { $_.status -ne 'in_progress' })
    $ageDays = [math]::Round(($now - $d.LastWriteTime).TotalDays, 1)

    if ($refs.Count -gt 0) {
        $kept.Add(@{ path = $rel; reason = 'referenced'; detail = ($refs -join '; ') }); continue
    }
    if ($jmInFlight.Count -gt 0) {
        $kept.Add(@{ path = $rel; reason = 'in-flight'; detail = ($jmInFlight | ForEach-Object { $_.taskId + ':' + $_.status }) -join '; ' }); continue
    }
    if ($roomBlurb.Count -gt 0 -and -not $doneMarker) {
        $kept.Add(@{ path = $rel; reason = 'pending-recipient'; detail = 'ROOM_BLURB 라우팅 카드' }); continue
    }
    $closeEvidence = $null
    if ($doneMarker) { $closeEvidence = 'done-marker:' + $doneMarker }
    elseif ($jmClosed.Count -gt 0) { $closeEvidence = 'journal-closed:' + (($jmClosed | ForEach-Object { $_.taskId + ':' + $_.status }) -join '; ') }
    elseif ($ageDays -ge $StaleDirDays) { $closeEvidence = 'stale-age:' + $ageDays + 'd' }
    if ($closeEvidence) {
        $wouldMove.Add(@{
            from = $rel; to = ('agent-prompts/_archive/' + $stamp + '/' + $d.Name)
            kind = 'dir'; reason = 'closed-directive'; detail = $closeEvidence
        })
    } else {
        $kept.Add(@{ path = $rel; reason = 'ambiguous'; detail = '닫힘 증거 없음(신선·미참조)' })
    }
}

# 3) 빈 leftover 디렉토리 (opt-in)
$emptyLeftovers = New-Object 'System.Collections.Generic.List[object]'
if ($IncludeEmptyLeftovers) {
    foreach ($pat in 'ui-debug-*', 'autoevolve_debug', '_patch_artifacts', 'output', 'verification', 'pki-validation') {
        foreach ($d in (Get-ChildItem -LiteralPath $rootPath -Directory -Filter $pat -Force -ErrorAction SilentlyContinue)) {
            $rel = Get-Rel $d.FullName
            if ($rel -match $denyRegex) { continue }
            $fc = @(Get-ChildItem -LiteralPath $d.FullName -Recurse -File -Force -ErrorAction SilentlyContinue).Count
            if ($fc -eq 0) {
                $entry = @{ from = $rel; to = ('agent-prompts/_archive/' + $stamp + '/empty-leftovers/' + $d.Name); kind = 'empty-dir'; reason = 'empty-leftover' }
                $wouldMove.Add($entry); $emptyLeftovers.Add($entry)
            } else {
                $kept.Add(@{ path = $rel; reason = 'not-empty'; detail = "files=$fc" })
            }
        }
    }
}

# 4) 여분 .gradle-* 홈 — 나열 전용 (이동은 -Apply -ApplyGradleHomes)
$gradleHomes = New-Object 'System.Collections.Generic.List[object]'
foreach ($d in (Get-ChildItem -LiteralPath $rootPath -Directory -Filter '.gradle-*' -Force -ErrorAction SilentlyContinue)) {
    $fc = @(Get-ChildItem -LiteralPath $d.FullName -Recurse -File -Force -ErrorAction SilentlyContinue).Count
    $g = @{ path = (Get-Rel $d.FullName); files = $fc; action = 'listed-only' }
    if ($Apply -and $ApplyGradleHomes -and -not $DryRun) {
        $g.action = 'move'; $g.to = ('agent-prompts/_archive/' + $stamp + '/gradle-homes/' + $d.Name)
        $wouldMove.Add(@{ from = $g.path; to = $g.to; kind = 'dir'; reason = 'extra-gradle-home' })
    }
    $gradleHomes.Add($g)
}

# --- 이동 실행 (-Apply만) ---
$moved = New-Object 'System.Collections.Generic.List[object]'
$failed = New-Object 'System.Collections.Generic.List[object]'
if ($mode -eq 'apply') {
    foreach ($e in $wouldMove) {
        $src = Join-Path $rootPath ($e.from -replace '/', '\')
        $dst = Join-Path $rootPath ($e.to -replace '/', '\')
        if ($e.from -match $denyRegex) { $failed.Add(@{ entry = $e; error = 'deny-regex' }); continue }
        if (-not (Test-Path -LiteralPath $src)) { $failed.Add(@{ entry = $e; error = 'source-gone' }); continue }
        try {
            $dstParent = Split-Path -Parent $dst
            New-Item -ItemType Directory -Force -Path $dstParent | Out-Null
            $finalDst = $dst; $i = 2
            while (Test-Path -LiteralPath $finalDst) { $finalDst = "$dst-$i"; $i++ }
            Move-Item -LiteralPath $src -Destination $finalDst -ErrorAction Stop
            $m = @{}; $e.Keys | ForEach-Object { $m[$_] = $e[$_] }
            if ($finalDst -ne $dst) { $m.to = (Get-Rel $finalDst) }
            $moved.Add($m)
        } catch {
            $failed.Add(@{ entry = $e; error = $_.Exception.Message })
        }
    }
}

# --- INDEX.md / _archive/README.md (-Apply && !-SkipIndex) ---
$indexPath = $null
if ($mode -eq 'apply' -and -not $SkipIndex) {
    $readme = Join-Path $rootPath 'agent-prompts/_archive/README.md'
    if (-not (Test-Path -LiteralPath $readme)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $readme) | Out-Null
        $readmeText = @"
# agent-prompts/_archive
OSTP(scripts/demo1_ops_surface_thin.ps1)로 이동된 닫힌 지시서/targets 보관.
제품 빌드와 무관. 복원 = 해당 항목을 원래 위치로 Move back + agent-prompts/INDEX.md 갱신.
"@
        [IO.File]::WriteAllText($readme, $readmeText, (New-Object System.Text.UTF8Encoding($false)))
    }
    $liveRows = foreach ($d in (Get-ChildItem -LiteralPath (Join-Path $rootPath 'agent-prompts') -Directory -Force -ErrorAction SilentlyContinue)) {
        if ($d.Name -eq '_archive') { continue }
        $k = ($kept | Where-Object { $_.path -eq ('agent-prompts/' + $d.Name) } | Select-Object -First 1)
        $st = if ($k) { $k.reason } else { 'live' }
        "| ``$($d.Name)`` | $($d.LastWriteTime.ToString('yyyy-MM-dd')) | $st |"
    }
    $archRows = @()
    $ar = Join-Path $rootPath 'agent-prompts/_archive'
    if (Test-Path -LiteralPath $ar) {
        foreach ($day in (Get-ChildItem -LiteralPath $ar -Directory -Force -ErrorAction SilentlyContinue)) {
            foreach ($item in (Get-ChildItem -LiteralPath $day.FullName -Force -ErrorAction SilentlyContinue)) {
                if ($item.PSIsContainer -and $item.Name -in 'targets', 'empty-leftovers', 'gradle-homes') {
                    foreach ($sub in (Get-ChildItem -LiteralPath $item.FullName -Force -ErrorAction SilentlyContinue)) {
                        $archRows += "| ``$($sub.Name)`` | ``_archive/$($day.Name)/$($item.Name)/`` | $($item.Name) |"
                    }
                } else {
                    $archRows += "| ``$($item.Name)`` | ``_archive/$($day.Name)/`` | archived |"
                }
            }
        }
    }
    $idx = @()
    $idx += '# agent-prompts INDEX'
    $idx += ''
    $idx += "generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') by scripts/demo1_ops_surface_thin.ps1 (-Apply). 재생성: Ops-Surface-Thin.bat -Apply"
    $idx += ''
    $idx += '## Live'
    $idx += '| dir | lastWrite | status |'
    $idx += '|---|---|---|'
    $idx += $liveRows
    $idx += ''
    $idx += '## Archived'
    $idx += '| name | 위치 | 분류 |'
    $idx += '|---|---|---|'
    if ($archRows.Count -gt 0) { $idx += $archRows } else { $idx += '| (없음) | | |' }
    $idxPath = 'agent-prompts/INDEX.md'
    [IO.File]::WriteAllText((Join-Path $rootPath 'agent-prompts/INDEX.md'), ($idx -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
}

# --- 매니페스트 ---
$manifest = [ordered]@{
    schemaVersion     = 'awx.ops-surface-thin.v1'
    generatedAtUtc    = (Get-Date).ToUniversalTime().ToString('o')
    root              = $rootPath
    mode              = $mode
    keepRecentTargets = $KeepRecentTargets
    staleDirDays      = $StaleDirDays
    scannedFiles      = $scanFiles.Count
    journalsScanned   = $journals.Count
    wouldMove         = $wouldMove.ToArray()
    moved             = $moved.ToArray()
    failed            = $failed.ToArray()
    kept              = $kept.ToArray()
    gradleHomes       = $gradleHomes.ToArray()
    index             = $indexPath
    summary           = @{
        wouldMove = $wouldMove.Count; moved = $moved.Count
        failed = $failed.Count; kept = $kept.Count
    }
}
$dbgDir = Join-Path $rootPath 'var/debug'
New-Item -ItemType Directory -Force -Path $dbgDir | Out-Null
$manifestPath = Join-Path $dbgDir "ops-surface-thin-$stampFull.json"
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))

Write-Output ("OSTP mode={0} wouldMove={1} moved={2} failed={3} kept={4} manifest={5}" -f
    $mode, $wouldMove.Count, $moved.Count, $failed.Count, $kept.Count, (Get-Rel $manifestPath))
exit 0
