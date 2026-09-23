#requires -Version 5.1
<#
.SYNOPSIS
  demo-1 Vibe-Max-Agency — Devin/Grok/Codex/Cline이 같은 루트·같은 증거로
  읽기·검증을 최대로 하게 하는 패키지. -Check는 보고만, -Apply만 실제 완화.
.DESCRIPTION
  "최대" = 하드 금지를 제외한 읽기·재기동·lease 범위 쓰기. 하드 금지(완화 불가):
    .secrets/, apikey.txt, .env*/shared.env 값 출력·커밋·프롬프트 첨부,
    openssl 키 이름/값/형식/구조 변경, 타인/만료 불명 lease 강제 삭제,
    사용자 명시 없는 유료 provider 실호출·프로덕션 비밀 배포.
  -Apply가 하는 일(로컬 개발 프로필만):
    1) .clineignore/.ignore/.rgignore에 읽기 예외 추가
       (!data/device-resources/events|registry, !var/debug/**,
        !var/meta-display-db/export, !var/dev-admin-token.txt).
       gitignore 규칙상 부모 dir이 통째로 제외되면 '!' 예외가 먹지 않으므로
       '/var/' → '/var/*', 'data/device-resources/' → 'data/device-resources/*'
       로 최소 재구성한다(커버리지 동일, 예외만 살아남).
       rg 스택(.gitignore→.ignore→.rgignore)은 .gitignore의 '/var/'·
       '/data/device-resources/'·'logs/' 부모-dir 제외를 되돌리는
       '!/var/'·'!/data/device-resources/'·'!/var/meta-display-db/'·'!/logs/'
       재포함 행도 필요 — 자식 '!'는 부모가 제외된 채로는 평가되지 않는다.
       '!/var/meta-display-db/' 재포함과 동시에 '/var/meta-display-db/*'를 걸어
       export 외 자식(lmsdb.mv.db 등 라이브 H2)은 계속 차단한다.
       같은 파일 안에서 예외보다 뒤에 오는 deny가 예외 대상을 shadow하면
       예외를 파일 끝으로 재배치한다(순서 = 적용 순서).
       .clineignore에는 'var/meta-display-db/*.db' deny를 추가해 라이브 H2
       파일을 Cline 읽기에서도 차단한다.
    2) main/resources/application-local.yml에 agent.db-context.enabled: true 보장
       (gitignore된 로컬 전용 파일; application.yml 기본값은 손대지 않음.
        application-meta-display.yml의 enabled:true는 기존 의도적 opt-in).
    3) var/dev-admin-token.txt 존재 보고만(없으면 생성 절차 안내, 값 출력 금지).
  상태 JSON: var/debug/vibe-max-agency-status.json
  AGENTS.md 블록: DEMO1-VIBE-MAX-AGENCY
#>
[CmdletBinding()]
param(
    [switch]$Check,                 # 보고만. 기본 모드.
    [switch]$Apply,                 # 실제 완화 적용. -Check와 동시 지정 시 Check 우선(안전).
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
$mode = if ($Apply -and -not $Check) { 'apply' } else { 'check' }
$script:applied = New-Object 'System.Collections.Generic.List[string]'

# --- gitignore 의미론 (이 파일들에 필요한 범위만; 패턴은 경로 전체 매칭) ---
function ConvertTo-GitIgnoreRegex([string]$pat) {
    $rx = [regex]::Escape($pat)
    $rx = $rx -replace '\\\*\\\*', '.*' -replace '\\\*', '[^/]*' -replace '\\\?', '[^/]'
    return $rx
}
function Test-IgnoreMatch([string]$pat, [string]$path, [bool]$isDir) {
    $p = $pat.Trim()
    if ($p.EndsWith('/')) { if (-not $isDir) { return $false }; $p = $p.TrimEnd('/') }
    if ($p -eq '') { return $false }
    $anchored = $p.StartsWith('/') -or $p.Contains('/')
    if ($p.StartsWith('/')) { $p = $p.Substring(1) }
    $rx = ConvertTo-GitIgnoreRegex $p
    if ($anchored) { return ($path -match "^$rx$") }
    return ($path -match "(^|.+/)$rx$")
}

# --- ignore 파일 규격 (예외 목록은 파일 끝에 이 순서로 append; deny 혼합 허용) ---
$exceptionMarker = '# --- vibe-max-agency read exceptions (AGENTS.md DEMO1-VIBE-MAX-AGENCY) ---'
$rgExceptions = @('!/var/', '!/data/device-resources/', '!/var/meta-display-db/',
                  '/var/meta-display-db/*', '!/var/meta-display-db/export/',
                  '!/data/device-resources/events/', '!/data/device-resources/registry/',
                  '!/var/debug/', '!/var/debug/**',
                  '!/var/dev-admin-token.txt', '!/logs/')
$ignoreSpecs = [ordered]@{
    '.clineignore' = [ordered]@{
        restructure = @(@{ deny = 'data/device-resources/'; star = 'data/device-resources/*' })
        exceptions  = @('!data/device-resources/events/', '!data/device-resources/registry/',
                        'var/meta-display-db/*.db',
                        '!var/debug/', '!var/debug/**',
                        '!var/meta-display-db/export/', '!var/dev-admin-token.txt')
    }
    '.ignore' = [ordered]@{
        restructure = @(@{ deny = '/data/device-resources/'; star = '/data/device-resources/*' },
                        @{ deny = '/var/'; star = '/var/*' })
        exceptions  = $rgExceptions
    }
    '.rgignore' = [ordered]@{
        restructure = @(@{ deny = '/data/device-resources/'; star = '/data/device-resources/*' },
                        @{ deny = '/var/'; star = '/var/*' })
        exceptions  = $rgExceptions
    }
}

function Get-IgnoreIncluded([string[]]$lines, [string]$path, [bool]$isDir) {
    $included = $true   # 마지막 매칭 패턴이 이긴다 ('!' = 포함)
    foreach ($raw in $lines) {
        $line = ([string]$raw).TrimEnd("`r").Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $neg = $line.StartsWith('!')
        $pat = if ($neg) { $line.Substring(1) } else { $line }
        if (Test-IgnoreMatch $pat $path $isDir) { $included = $neg }
    }
    return $included
}
function Test-EffectiveOpen([string[]]$lines, [string]$filePath) {
    # 부모 dir이 제외 상태면 자식 '!' 예외도 무효 — 조상 dir까지 모두 평가.
    if (-not (Get-IgnoreIncluded $lines $filePath $false)) { return $false }
    $parts = $filePath -split '/'
    for ($i = 1; $i -lt $parts.Count; $i++) {
        $dir = ($parts[0..($i - 1)] -join '/')
        if (-not (Get-IgnoreIncluded $lines $dir $true)) { return $false }
    }
    return $true
}
function Read-IgnoreLines([string]$name) {
    $p = Join-Path $rootPath $name
    if (-not (Test-Path -LiteralPath $p)) { return $null }
    return @([IO.File]::ReadAllLines($p))
}
function Write-IgnoreLines([string]$name, [string[]]$lines) {
    $p = Join-Path $rootPath $name
    [IO.File]::WriteAllText($p, (($lines -join "`r`n") + "`r`n"), [Text.UTF8Encoding]::new($false))
}

# --- ignore 파일 적용/평가 ---
function Update-IgnoreFile([string]$name) {
    $spec = $ignoreSpecs[$name]
    $result = [ordered]@{ file = $name; exists = $false; restructured = @(); missingDeny = @(); added = @(); alreadyPresent = @(); reordered = @() }
    $lines = Read-IgnoreLines $name
    if ($null -eq $lines) { $result.missingDeny += 'file-absent'; return $result }
    $result.exists = $true
    $list = New-Object 'System.Collections.Generic.List[string]'
    foreach ($l in $lines) { $list.Add([string]$l) }
    foreach ($r in $spec.restructure) {
        $idx = -1
        for ($i = 0; $i -lt $list.Count; $i++) { if ($list[$i].Trim() -eq $r.deny) { $idx = $i; break } }
        if ($idx -ge 0) {
            if ($mode -eq 'apply') { $list[$idx] = $r.star; $result.restructured += "$($r.deny) -> $($r.star)" }
            else { $result.restructured += "pending: $($r.deny) -> $($r.star)" }
        } else {
            $hasStar = $false
            foreach ($l in $list) { if ($l.Trim() -eq $r.star) { $hasStar = $true; break } }
            if (-not $hasStar) { $result.missingDeny += $r.deny }
        }
    }
    $missing = @()
    foreach ($e in $spec.exceptions) {
        $ei = -1
        for ($i = 0; $i -lt $list.Count; $i++) { if ($list[$i].Trim() -eq $e) { $ei = $i; break } }
        if ($ei -lt 0) { $missing += $e; continue }
        # 순서 검사: 이 예외보다 뒤에 있는 deny가 예외 대상을 shadow하면 끝으로 이동.
        $shadowed = $false
        if (-not $e.Contains('*')) {
            $target = $e.TrimStart('!').TrimStart('/')
            $tIsDir = $e.EndsWith('/')
            $tp = $target.TrimEnd('/')
            for ($i = $ei + 1; $i -lt $list.Count; $i++) {
                $l = $list[$i].Trim()
                if ($l -eq '' -or $l.StartsWith('#') -or $l.StartsWith('!')) { continue }
                if (Test-IgnoreMatch $l $tp $tIsDir) { $shadowed = $true; break }
            }
        }
        if ($shadowed) {
            if ($mode -eq 'apply') { $list.RemoveAt($ei); $missing += $e; $result.reordered += $e }
            else { $result.reordered += "pending: $e" }
        } else { $result.alreadyPresent += $e }
    }
    if ($missing.Count -gt 0 -and $mode -eq 'apply') {
        $hasMarker = $false
        foreach ($l in $list) { if ($l.Trim() -eq $exceptionMarker) { $hasMarker = $true; break } }
        if (-not $hasMarker) { $list.Add(''); $list.Add($exceptionMarker) }
        foreach ($m in $missing) { $list.Add($m); $result.added += $m }
    } elseif ($mode -eq 'check') {
        $result.added = @($missing | ForEach-Object { "pending: $_" })
    }
    if ($mode -eq 'apply') {
        Write-IgnoreLines $name $list.ToArray()
        $script:applied.Add("$name exceptions/restructure applied")
    }
    return $result
}

# --- application-local.yml: agent.db-context.enabled 보장 (로컬 전용) ---
function Get-AgentDbContextState {
    $rel = 'main/resources/application-local.yml'
    $p = Join-Path $rootPath $rel
    $state = [ordered]@{ file = $rel; exists = $false; enabled = $null }
    if (-not (Test-Path -LiteralPath $p)) { return $state }
    $state.exists = $true
    $lines = @([IO.File]::ReadAllLines($p))
    $inAgent = $false; $inCtx = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $t = $lines[$i]
        if ($t -match '^\S') { $inAgent = ($t -match '^agent:\s*$'); $inCtx = $false; continue }
        if ($inAgent -and $t -match '^\s+db-context:\s*$') { $inCtx = $true; continue }
        if ($inCtx -and $t -match '^\s+enabled:\s*(\S+)') { $state.enabled = ($Matches[1] -eq 'true'); break }
        if ($inCtx -and $t -match '^\s+\S' -and $t -notmatch '^\s+enabled:') { $inCtx = $false }
    }
    return $state
}
function Set-AgentDbContextEnabled {
    $rel = 'main/resources/application-local.yml'
    $p = Join-Path $rootPath $rel
    if (-not (Test-Path -LiteralPath $p)) {
        $body = "# Local-only dev profile (gitignored). Vibe-Max-Agency minimum.`r`nagent:`r`n  db-context:`r`n    enabled: true`r`n"
        [IO.File]::WriteAllText($p, $body, [Text.UTF8Encoding]::new($false))
        return 'created'
    }
    $lines = @([IO.File]::ReadAllLines($p))
    $agentIdx = -1; $ctxIdx = -1; $enabledIdx = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $t = $lines[$i]
        if ($t -match '^\S') {
            if ($agentIdx -ge 0) { break }
            if ($t -match '^agent:\s*$') { $agentIdx = $i }
            continue
        }
        if ($agentIdx -ge 0 -and $ctxIdx -lt 0 -and $t -match '^\s+db-context:\s*$') { $ctxIdx = $i; continue }
        if ($ctxIdx -ge 0 -and $t -match '^\s+enabled:\s*\S+') { $enabledIdx = $i; break }
        if ($ctxIdx -ge 0 -and $t -match '^\s+\S' -and $t -notmatch '^\s+enabled:') { break }
    }
    $list = New-Object 'System.Collections.Generic.List[string]'
    foreach ($l in $lines) { $list.Add($l) }
    if ($enabledIdx -ge 0) {
        if ($list[$enabledIdx] -match 'enabled:\s*true') { return 'already-true' }
        $list[$enabledIdx] = ($list[$enabledIdx] -replace 'enabled:\s*\S+', 'enabled: true')
    } elseif ($ctxIdx -ge 0) {
        $list.Insert($ctxIdx + 1, '    enabled: true')
    } elseif ($agentIdx -ge 0) {
        $list.Insert($agentIdx + 1, '  db-context:'); $list.Insert($agentIdx + 2, '    enabled: true')
    } else {
        if ($list.Count -gt 0 -and $list[$list.Count - 1].Trim() -ne '') { $list.Add('') }
        $list.Add('agent:'); $list.Add('  db-context:'); $list.Add('    enabled: true')
    }
    [IO.File]::WriteAllText($p, (($list -join "`r`n").TrimEnd() + "`r`n"), [Text.UTF8Encoding]::new($false))
    return 'enabled'
}
function Get-PublicDbContextState {
    # 공개/기본 프로필의 db-context 상태 보고(이 스크립트는 공개 프로필을 절대 쓰지 않음)
    $rep = [ordered]@{ baseDefault = 'absent-or-false'; optedInProfiles = @() }
    $base = Join-Path $rootPath 'main/resources/application.yml'
    if (Test-Path -LiteralPath $base) {
        $lines = @([IO.File]::ReadAllLines($base))
        $inCtx = $false
        foreach ($t in $lines) {
            if ($t -match '^\s*db-context:\s*$') { $inCtx = $true; continue }
            if ($inCtx -and $t -match '^\s+enabled:\s*true') { $rep.baseDefault = 'ENABLED-IN-BASE'; break }
            if ($inCtx -and $t -match '^\S' ) { $inCtx = $false }
        }
    }
    foreach ($f in 'application-meta-display.yml', 'application-prod.yml', 'application-proj-override.yml') {
        $p = Join-Path $rootPath "main/resources/$f"
        if (-not (Test-Path -LiteralPath $p)) { continue }
        $lines = @([IO.File]::ReadAllLines($p))
        $inCtx = $false
        foreach ($t in $lines) {
            if ($t -match 'db-context:\s*$') { $inCtx = $true; continue }
            if ($inCtx -and $t -match '^\s+enabled:\s*true') { $rep.optedInProfiles += $f; break }
            if ($inCtx -and $t -match '^\s{0,4}\S' -and $t -notmatch 'enabled:') { $inCtx = $false }
        }
    }
    return $rep
}

# --- 외부 명령 JSON ---
function Invoke-PyJson([string[]]$argv, [int]$timeoutSec = 40) {
    try {
        $out = & python -B @argv 2>$null
        $code = $LASTEXITCODE
    } catch { return @{ status = 'unavailable'; reason = $_.Exception.GetType().Name } }
    $data = $null
    $text = ($out | Out-String).Trim()
    if ($text) { try { $data = $text | ConvertFrom-Json } catch { $data = $null } }
    return @{ status = 'ok'; exitCode = $code; result = $data }
}

# --- HTTP 상태 코드만 (본문/토큰 출력 금지) ---
function Get-HttpStatus([string]$url, [hashtable]$headers = @{}, [int]$timeoutSec = 6) {
    try {
        $r = Invoke-WebRequest -Uri $url -Method GET -Headers $headers -TimeoutSec $timeoutSec -UseBasicParsing -ErrorAction Stop
        return @{ status = [int]$r.StatusCode }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) { try { return @{ status = [int]$resp.StatusCode } } catch { return @{ status = 'http-error' } } }
        return @{ status = 'unreachable'; reason = $_.Exception.GetType().Name }
    }
}

# --- evidence path 정의 (프로브는 대표 파일 경로) ---
$evidenceProbes = [ordered]@{
    'data/device-resources/events/'   = 'data/device-resources/events/__probe__.ndjson'
    'data/device-resources/registry/' = 'data/device-resources/registry/__probe__.json'
    'var/debug/'                      = 'var/debug/vibe-max-agency-status.json'
    'var/debug/*.log'                 = 'var/debug/dev-20990101-000000-status.log'
    'var/meta-display-db/export/'     = 'var/meta-display-db/export/__probe__/export.sqlite'
    'var/dev-admin-token.txt'         = 'var/dev-admin-token.txt'
    'logs/debug-events*.ndjson'       = 'logs/debug-events.ndjson'
}
$hardProbes = [ordered]@{
    '.secrets/'  = '.secrets/providers.json'
    'apikey.txt' = 'apikey.txt'
    '.env'       = '.env'
    'shared.env' = 'shared.env'
    'data/device-resources (raw)' = 'data/device-resources/README.md'
    'var/meta-display-db/lmsdb.mv.db' = 'var/meta-display-db/lmsdb.mv.db'
}

function Get-AgentStacks {
    # 에이전트별 읽기 스택. rg 계열(Devin/Codex/Grok)은 .gitignore→.ignore→.rgignore 순 우선순위로 합쳐 평가.
    $cline  = Read-IgnoreLines '.clineignore'
    $gi     = Read-IgnoreLines '.gitignore'
    $ig     = Read-IgnoreLines '.ignore'
    $rg     = Read-IgnoreLines '.rgignore'
    $rgStack = @(); foreach ($s in @($gi, $ig, $rg)) { if ($s) { $rgStack += $s } }
    return @{
        cline = @($cline)
        devin = $rgStack; codex = $rgStack; grok = $rgStack
    }
}
function Get-AgentReport {
    $stacks = Get-AgentStacks
    $agents = [ordered]@{}
    foreach ($a in 'cline', 'devin', 'codex', 'grok') {
        $lines = $stacks[$a]
        $blocked = @(); $open = @()
        foreach ($k in $evidenceProbes.Keys) {
            if ($null -eq $lines) { $blocked += $k; continue }
            if (Test-EffectiveOpen $lines $evidenceProbes[$k]) { $open += $k } else { $blocked += $k }
        }
        $agents[$a] = [ordered]@{ open = $open; blocked = $blocked; blockedCount = $blocked.Count; stackEmpty = ($null -eq $lines) }
    }
    $hard = [ordered]@{}
    foreach ($k in $hardProbes.Keys) {
        $per = [ordered]@{}
        foreach ($a in 'cline', 'devin', 'codex', 'grok') {
            $lines = $stacks[$a]
            $per[$a] = if ($null -eq $lines) { 'unknown' } elseif (Test-EffectiveOpen $lines $hardProbes[$k]) { 'OPEN-LEAK' } else { 'blocked' }
        }
        $hard[$k] = $per
    }
    return @{ agents = $agents; hard = $hard }
}

# --- 체크 수집 ---
function Get-StatusReport {
    $rep = [ordered]@{
        schemaVersion = 'demo1.vibe-max-agency.v1'
        mode = $mode; root = $rootPath
        atUtc = [DateTime]::UtcNow.ToString('o')
        note = 'max = read/restart/lease-scope writes with hard bans unchanged (see AGENTS.md DEMO1-VIBE-MAX-AGENCY)'
    }
    # 1) preflight + device bus
    $pf = Invoke-PyJson @('scripts/agent_preflight.py', '--root', '.') 60
    $rep.preflight = [ordered]@{ ran = ($pf.status -eq 'ok' -and $null -ne $pf.result); exitCode = $pf.exitCode }
    if ($rep.preflight.ran) {
        $r = $pf.result
        $rep.preflight.journalActive = @($r.journals.result.tasks).Count
        $rep.preflight.leaseBlocking = $r.leases.result.sourceLeaseBlockingCount
        $rep.preflight.projectRoot = $r.projectRoot.result
        $rep.deviceBus = [ordered]@{ status = $r.deviceBus.result.status; registryRef = $r.deviceBus.result.registryRef; exitCode = $r.deviceBus.exitCode }
    } else { $rep.deviceBus = [ordered]@{ status = 'not_observed'; exitCode = $pf.exitCode } }
    # 2) agent.db-context 로컬 플래그 + 공개 프로필 상태(정보 보고 — 이 스크립트는 공개 프로필을 쓰지 않음)
    $db = Get-AgentDbContextState
    $pub = Get-PublicDbContextState
    $rep.agentDbContext = [ordered]@{
        localFile = $db.file; localExists = $db.exists; localEnabled = $db.enabled
        baseDefault = $pub.baseDefault; publicOptedInProfiles = @($pub.optedInProfiles)
        publicOptInNote = 'opted-in entries are pre-existing owner config (e.g. meta-display local profile kill-switch comment), not added by this script'
    }
    # 3) meta_display_db status + live status
    $st = Invoke-PyJson @('scripts/meta_display_db_export.py', 'status') 40
    $rep.metaDisplayDb = [ordered]@{ status = [ordered]@{ exitCode = $st.exitCode } }
    if ($st.result) {
        $rep.metaDisplayDb.status.dbLocked = $st.result.dbFile.locked
        $rep.metaDisplayDb.status.exports = @($st.result.exports).Count
        $rep.metaDisplayDb.status.latestRunId = $st.result.latest.runId
    }
    $lv = Invoke-PyJson @('scripts/meta_display_db_export.py', 'live', 'status') 30
    $rep.metaDisplayDb.live = [ordered]@{ exitCode = $lv.exitCode }
    if ($lv.result) {
        $rep.metaDisplayDb.live.httpStatus = $lv.result.httpStatus
        $rep.metaDisplayDb.live.tokenSource = $lv.result.tokenSource   # 소스 이름만 — 값 절대 출력 금지
        $rep.metaDisplayDb.live.endpoint = $lv.result.endpoint
    } else { $rep.metaDisplayDb.live.httpStatus = 'unavailable' }
    # 4) /agent/db-context/pipeline-health (admin 토큰 헤더; 값은 절대 출력하지 않음)
    $tokenPath = Join-Path $rootPath 'var/dev-admin-token.txt'
    $rep.agentDbContext.tokenFile = [ordered]@{ path = 'var/dev-admin-token.txt'; exists = (Test-Path -LiteralPath $tokenPath) }
    if (-not $rep.agentDbContext.tokenFile.exists) {
        $rep.agentDbContext.tokenFile.procedure = 'create var/dev-admin-token.txt with the local admin token (owner action; never print the value)'
    }
    $rep.agentDbContext.http = [ordered]@{ pipelineHealth = @{ status = 'skipped-no-token' } }
    if ($rep.agentDbContext.tokenFile.exists) {
        $hdr = @{ 'X-Admin-Token' = ([IO.File]::ReadAllText($tokenPath)).Trim() }
        $rep.agentDbContext.http.pipelineHealth = Get-HttpStatus 'http://127.0.0.1:18180/agent/db-context/pipeline-health' $hdr
        $hdr = $null
    }
    # 5) ignore 파일 예외 상태
    $rep.ignoreFiles = [ordered]@{}
    foreach ($name in $ignoreSpecs.Keys) {
        $lines = Read-IgnoreLines $name
        $spec = $ignoreSpecs[$name]
        $entry = [ordered]@{
            exists = ($null -ne $lines)
            exceptionsPresent = @(); exceptionsMissing = @()
            denyRawPresent = @(); denyStarPresent = @()
        }
        $rep.ignoreFiles[$name] = $entry
        if ($null -eq $lines) { $entry.exceptionsMissing = @($spec.exceptions); continue }
        foreach ($e in $spec.exceptions) {
            $hit = $false
            foreach ($l in $lines) { if ($l.Trim() -eq $e) { $hit = $true; break } }
            if ($hit) { $entry.exceptionsPresent += $e } else { $entry.exceptionsMissing += $e }
        }
        foreach ($r in $spec.restructure) {
            foreach ($l in $lines) {
                if ($l.Trim() -eq $r.deny) { $entry.denyRawPresent += $r.deny }
                if ($l.Trim() -eq $r.star) { $entry.denyStarPresent += $r.star }
            }
        }
    }
    # 6) 에이전트별 blocked 경로 + 하드 블록 유지 검사
    $ar = Get-AgentReport
    $rep.agents = $ar.agents
    $rep.hardBlocks = [ordered]@{ probes = $ar.hard; intact = $true }
    foreach ($k in $ar.hard.Keys) { foreach ($a in $ar.hard[$k].Keys) { if ($ar.hard[$k][$a] -ne 'blocked') { $rep.hardBlocks.intact = $false } } }
    $rep.blockedTotal = 0
    foreach ($a in 'cline', 'devin', 'codex', 'grok') { $rep.blockedTotal += $rep.agents[$a].blockedCount }
    return $rep
}

# --- 실행 ---
if ($mode -eq 'apply') {
    foreach ($name in $ignoreSpecs.Keys) { [void](Update-IgnoreFile $name) }
    $dbAction = Set-AgentDbContextEnabled
    if ($dbAction -ne 'already-true') { $script:applied.Add("application-local.yml agent.db-context: $dbAction") }
    else { $script:applied.Add('application-local.yml agent.db-context: already-true') }
    $dbg = Join-Path $rootPath 'var/debug'
    if (-not (Test-Path -LiteralPath $dbg)) { New-Item -ItemType Directory -Path $dbg -Force | Out-Null; $script:applied.Add('var/debug created') }
}

$report = Get-StatusReport
$report.appliedActions = @($script:applied)
$reportDir = Join-Path $rootPath 'var/debug'
if (-not (Test-Path -LiteralPath $reportDir)) { New-Item -ItemType Directory -Path $reportDir -Force | Out-Null }
$reportPath = Join-Path $reportDir 'vibe-max-agency-status.json'
($report | ConvertTo-Json -Depth 8) | Set-Content -Encoding utf8 $reportPath

# --- 콘솔 요약 ---
Write-Host ("mode={0} report={1}" -f $mode, $reportPath)
foreach ($a in 'cline', 'devin', 'codex', 'grok') {
    $ag = $report.agents[$a]
    Write-Host ("  {0,-6} open={1} blocked={2}{3}" -f $a, $ag.open.Count, $ag.blockedCount, $(if ($ag.blockedCount) { ' -> ' + ($ag.blocked -join ', ') } else { '' }))
}
Write-Host ("  hardBlocks.intact={0}  dbContext.localEnabled={1} base={2} publicOptIn={3}" -f $report.hardBlocks.intact, $report.agentDbContext.localEnabled, $report.agentDbContext.baseDefault, (@($report.agentDbContext.publicOptedInProfiles) -join ','))
Write-Host ("  metaDisplayDb status.exit={0} live.http={1}  pipelineHealth={2}" -f $report.metaDisplayDb.status.exitCode, $report.metaDisplayDb.live.httpStatus, $report.agentDbContext.http.pipelineHealth.status)
if ($mode -eq 'check') { Write-Host 'check only: apply relaxations via Vibe-Max-Agency.bat -Apply (사용자 지시 시)' }
exit 0
