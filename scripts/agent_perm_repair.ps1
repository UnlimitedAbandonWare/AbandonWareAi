<#
.SYNOPSIS
  Scoped permission repair for agent CLI runtimes (Codex/Grok/Agy/Devin).

.DESCRIPTION
  반복적인 Access Denied로 에이전트 CLI가 중단되는 경우, 프로젝트 내부의
  재생성 가능/에이전트 작업 디렉터리에 한해 현재 사용자 쓰기 권한을 복구한다.
  제품 소스·비밀정보·.git·사용자 데이터는 삭제도 ACL 변경도 하지 않는다.

  복구 순서 (디렉터리별, 1회씩 — 무한 반복 없음):
    1) 쓰기 프로브 (파일 생성→읽기→삭제)
    2) 실패 시 icacls /grant <user>:(OI)(CI)F — 대상 디렉터리만, 전역 아님
    3) 여전히 실패 + 관리자 → takeown 후 icacls 재시도
    4) 여전히 실패 + 재생성 가능 분류 → 삭제 후 재생성
    5) 최종 실패 시 명령/경로/오류를 unresolved로 기록하고 계속

.PARAMETER Root
  프로젝트 루트. 기본값은 이 스크립트의 상위 디렉터리.

.PARAMETER Targets
  루트 기준 상대경로 배열. 생략하면 기본 에이전트 작업 디렉터리 집합.

.PARAMETER PurgeCaches
  지정하면 재생성 가능(regenerable) 대상을 복구 전에 삭제+재생성한다.

.PARAMETER DryRun
  변경 없이 진단만 한다 (프로브 결과 + 적용 예정 액션 출력).

.OUTPUTS
  -Json 지정 시 stdout에 단일 JSON 문서. exit 0=모두 ok/skipped, 1=unresolved 존재.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/agent_perm_repair.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/agent_perm_repair.ps1 -Targets "build","docs/agent" -Json
#>
[CmdletBinding()]
param(
    [string]$Root = '',
    [string[]]$Targets = @(),
    [switch]$PurgeCaches,
    [switch]$DryRun,
    [switch]$Json
)

$ErrorActionPreference = 'Continue'
if (-not $Root) {
    # $PSScriptRoot는 -File 호출/중첩 호스트에서 비어 있을 수 있어 본문에서 해석한다.
    if ($PSScriptRoot) { $Root = Split-Path -Parent $PSScriptRoot }
    elseif ($MyInvocation.MyCommand.Path) {
        $Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
    } else { $Root = (Get-Location).Path }
}
$Root = (Resolve-Path -LiteralPath $Root).ProviderPath

# --- 분류표: 절대 건드리지 않는 영역 / ACL 복구만 / 삭제+재생성 가능 ---
$Protected = @(
    '.git', '.secrets', '.env', '.env.shared', 'apikey.txt',
    'main', 'app', 'src', 'frontend', 'gradle', 'bin', 'config', 'configs',
    'agent-prompts', 'pki-validation', 'toss', 'db-ledger',
    '.codex', '.grok', '.cline', '.clinerules', '.windsurf', '.devin', '.agents',
    '.github', '.githooks', '.well-known', '.env.example'
)
$Regenerable = @(
    'build', '_patch_artifacts', '.pytest_cache', '.gradle', 'tools/agents/.probe'
)
$RegenerableWildcards = @('.gradle-*')   # per-agent Gradle homes
$RepairOnly = @(
    'data', 'data/agent-handoff', 'var', 'logs', 'tools', 'tools/agents', 'scripts',
    'docs/agent', 'docs/diagnostics', '__patch_drop__', '__reports__', 'uploads',
    'autoevolve_debug', 'ui-debug-luna-jev-0926'
)

if (-not $Targets -or $Targets.Count -eq 0) {
    $Targets = @('build', '_patch_artifacts', 'docs/agent', 'docs/diagnostics',
        'data/agent-handoff', 'var', 'logs', 'tools', 'tools/agents', 'scripts',
        '__patch_drop__', '__reports__', 'uploads')
    $Targets += Get-ChildItem -LiteralPath $Root -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like '.gradle*' } | ForEach-Object { $_.Name }
}

$IsElevated = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
$CurrentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name

function Test-ProtectedRel([string]$rel) {
    $norm = $rel -replace '/', '\' 
    $first = ($norm -split '\\')[0]
    return ($Protected -contains $first) -or ($Protected -contains $norm)
}
function Test-Regenerable([string]$rel) {
    $norm = $rel -replace '/', '\'
    $first = ($norm -split '\\')[0]
    if ($Regenerable -contains $norm -or $Regenerable -contains $first) { return $true }
    foreach ($w in $RegenerableWildcards) { if ($first -like $w) { return $true } }
    return $false
}
function Test-RepairOnly([string]$rel) {
    $norm = $rel -replace '/', '\'
    foreach ($r in $RepairOnly) {
        $rn = $r -replace '/', '\'
        if ($norm -eq $rn -or $norm.StartsWith("$rn\")) { return $true }
    }
    return (Test-Regenerable $rel)   # 재생성 가능 대상도 ACL 복구 허용
}

function Write-Probe([string]$dir) {
    # 반환: $null=성공, 문자열=실패 사유
    try {
        $probe = Join-Path $dir ('.__perm_probe_' + [guid]::NewGuid().ToString('N') + '.tmp')
        [IO.File]::WriteAllText($probe, 'probe')
        $back = [IO.File]::ReadAllText($probe)
        [IO.File]::Delete($probe)
        if ($back -ne 'probe') { return 'readback-mismatch' }
        return $null
    } catch {
        return ($_.Exception.GetType().Name + ': ' + $_.Exception.Message)
    }
}

$results = @()
foreach ($rel in $Targets) {
    $rel = $rel.Trim().TrimEnd('/', '\')
    if (-not $rel) { continue }
    $abs = Join-Path $Root $rel
    $row = [ordered]@{ target = $rel; status = 'unknown'; actions = @(); error = $null }

    # 루트 내부 강제: 심볼릭/정션 재parse 지점은 건너뛰고 보호 대상은 즉시 차단
    if ($rel -match '^\.\.' -or [IO.Path]::IsPathRooted($rel)) {
        $row.status = 'skipped:outside-root'; $results += [pscustomobject]$row; continue
    }
    if (Test-ProtectedRel $rel) {
        $row.status = 'skipped:protected'; $results += [pscustomobject]$row; continue
    }

    $item = Get-Item -LiteralPath $abs -Force -ErrorAction SilentlyContinue
    if ($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        $row.status = 'skipped:reparse-point'; $results += [pscustomobject]$row; continue
    }

    if (-not $item) {
        if (-not $DryRun) { New-Item -ItemType Directory -Path $abs -Force | Out-Null }
        $row.actions += 'mkdir'
        $item = Get-Item -LiteralPath $abs -Force -ErrorAction SilentlyContinue
        if (-not $item -and -not $DryRun) {
            $row.status = 'unresolved'; $row.error = 'mkdir-failed'
            $results += [pscustomobject]$row; continue
        }
    }

    $regen = Test-Regenerable $rel
    if ($PurgeCaches -and $regen) {
        if (-not $DryRun) {
            Remove-Item -LiteralPath $abs -Recurse -Force -ErrorAction SilentlyContinue
            New-Item -ItemType Directory -Path $abs -Force | Out-Null
        }
        $row.actions += 'purge-recreate'
    }

    $err = if ($DryRun) { 'dryrun' } else { Write-Probe $abs }
    if (-not $err -or $err -eq 'dryrun') {
        $row.status = 'ok'; if ($err) { $row.actions += 'dryrun-noop' }
        $results += [pscustomobject]$row; continue
    }
    $row.error = $err

    # --- ACL 복구 (대상 디렉터리만) ---
    if (Test-RepairOnly $rel) {
        $icaclsArgs = @($abs, '/grant', "${CurrentUser}:(OI)(CI)F", '/T', '/Q', '/C')
        if (-not $DryRun) {
            $out = & icacls.exe @icaclsArgs 2>&1 | Out-String
            $row.actions += "icacls-grant(exit=$LASTEXITCODE)"
        } else { $row.actions += 'icacls-grant(dryrun)' }

        $err = if ($DryRun) { 'dryrun' } else { Write-Probe $abs }
        if (-not $err -or $err -eq 'dryrun') {
            $row.status = 'ok'; $row.error = $null; $results += [pscustomobject]$row; continue
        }
        $row.error = $err

        if ($IsElevated) {
            if (-not $DryRun) {
                $null = & takeown.exe /F $abs /R /D Y 2>&1
                $row.actions += "takeown(exit=$LASTEXITCODE)"
                $null = & icacls.exe @icaclsArgs 2>&1
                $row.actions += "icacls-grant-2(exit=$LASTEXITCODE)"
            } else { $row.actions += 'takeown+icacls(dryrun)' }
            $err = if ($DryRun) { 'dryrun' } else { Write-Probe $abs }
            if (-not $err -or $err -eq 'dryrun') {
                $row.status = 'ok'; $row.error = $null; $results += [pscustomobject]$row; continue
            }
            $row.error = $err
        } else {
            $row.actions += 'takeown-skipped:not-elevated'
        }
    } else {
        $row.actions += 'acl-repair-not-allowed'
    }

    # --- 재생성 가능 대상만 삭제+재생성 ---
    if ($regen) {
        if (-not $DryRun) {
            Remove-Item -LiteralPath $abs -Recurse -Force -ErrorAction SilentlyContinue
            New-Item -ItemType Directory -Path $abs -Force | Out-Null
            $row.actions += 'delete-recreate'
            $err = Write-Probe $abs
        } else { $row.actions += 'delete-recreate(dryrun)'; $err = 'dryrun' }
        if (-not $err -or $err -eq 'dryrun') {
            $row.status = 'ok'; $row.error = $null; $results += [pscustomobject]$row; continue
        }
        $row.error = $err
    }

    $row.status = 'unresolved'
    $results += [pscustomobject]$row
}

$unresolved = @($results | Where-Object { $_.status -eq 'unresolved' })
$summary = [ordered]@{
    schema      = 'awx.agent-perm-repair.v1'
    root        = $Root
    elevated    = $IsElevated
    user        = $CurrentUser
    dryRun      = [bool]$DryRun
    purgeCaches = [bool]$PurgeCaches
    total       = $results.Count
    ok          = @($results | Where-Object { $_.status -eq 'ok' }).Count
    skipped     = @($results | Where-Object { $_.status -like 'skipped:*' }).Count
    unresolved  = $unresolved.Count
    results     = $results
}

if ($Json) {
    $summary | ConvertTo-Json -Depth 5
} else {
    Write-Output ("[perm-repair] root=$Root elevated=$IsElevated user=$CurrentUser")
    foreach ($r in $results) {
        $act = ($r.actions -join ', ')
        Write-Output ("[perm-repair] {0,-28} {1,-18} {2} {3}" -f $r.target, $r.status, $act, $r.error)
    }
    Write-Output ("[perm-repair] total={0} ok={1} skipped={2} unresolved={3}" -f
        $summary.total, $summary.ok, $summary.skipped, $summary.unresolved)
    if ($unresolved.Count -gt 0 -and -not $IsElevated) {
        Write-Output "[perm-repair] hint: unresolved targets need an elevated shell (takeown/icacls ownership)."
    }
}
if ($unresolved.Count -gt 0) { exit 1 } else { exit 0 }
