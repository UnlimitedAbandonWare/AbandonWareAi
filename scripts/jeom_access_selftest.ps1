# jeom_access_selftest.ps1 — 점(dot)/Codex 보조의 PC 접근 자가 점검 (읽기 위주)
# Contract: DEMO1-DEVIN-CODEX-JEOM-GROKBOT-ACCESS-20261002
# 사용: powershell -NoProfile -File scripts\jeom_access_selftest.ps1 [-Json] [-Root <dir>] [-DownloadsDir <dir>]
# 결과: 항목 | PASS/FAIL/SKIP | 메모  표 + (선택) JSON.
# FAIL 메모는 "권한 꺼짐 모양"과 "범위 밖 의도 차단"을 구분한다.
# 비밀 파일은 존재 여부만 본다. 자기가 만든 임시 파일만 지운다. 외부 API 호출 없음.
[CmdletBinding()]
param(
    [switch]$Json,
    [string]$Root = '',
    [string]$DownloadsDir = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'

# --- helpers ---------------------------------------------------------------
$script:Rows = New-Object System.Collections.Generic.List[object]
function Add-Row([string]$Item, [string]$Status, [string]$Note) {
    $script:Rows.Add([pscustomobject]@{ item = $Item; status = $Status; note = $Note }) | Out-Null
}
function Get-Sha12([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.Substring(0, 12).ToLower()
}

if (-not $Root) {
    $Root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
}
if (-not $DownloadsDir) {
    $DownloadsDir = Join-Path $env:USERPROFILE 'Downloads'
}
$Root = $Root.TrimEnd('\', '/')

# --- 1. 프로젝트 루트 읽기 ----------------------------------------------------
try {
    $need = @('AGENTS.md', 'gradlew.bat', 'main\java')
    $missing = @($need | Where-Object { -not (Test-Path -LiteralPath (Join-Path $Root $_)) })
    if ($missing.Count -eq 0) {
        $probe = Join-Path $Root 'AGENTS.md'
        [void][IO.File]::ReadAllText($probe, [Text.Encoding]::UTF8)
        Add-Row '프로젝트 루트 읽기' 'PASS' $Root
    } else {
        Add-Row '프로젝트 루트 읽기' 'FAIL' ("없음: " + ($missing -join ', ') + ' — 권한 꺼짐 모양이거나 잘못된 -Root')
    }
} catch {
    Add-Row '프로젝트 루트 읽기' 'FAIL' ("읽기 거부 — 권한 꺼짐 모양일 가능성: " + $_.Exception.Message.Substring(0, [Math]::Min(80, $_.Exception.Message.Length)))
}

# --- 2. git HEAD -------------------------------------------------------------
$GitReg = $null
try { . (Join-Path $PSScriptRoot 'AwxPaths.ps1'); $GitReg = Resolve-AwxPath -Key 'git.exe' } catch { }
$git = $null
foreach ($cand in @('git.exe', 'git', $GitReg)) {
    try {
        if (-not $cand) { continue }
        if ($cand -match '\\') { if (Test-Path -LiteralPath $cand) { $git = $cand; break } }
        else { $cmd = Get-Command $cand -ErrorAction Stop; $git = $cmd.Source; break }
    } catch { }
}
if (-not $git) {
    Add-Row 'git HEAD' 'SKIP' 'git 실행 파일을 못 찾음 — 권한 문제가 아니라 도구 부재'
} else {
    try {
        $head = & $git -C $Root rev-parse --short=12 HEAD 2>$null
        if ($LASTEXITCODE -eq 0 -and $head) { Add-Row 'git HEAD' 'PASS' ([string]$head).Trim() }
        else { Add-Row 'git HEAD' 'FAIL' 'rev-parse 실패 — 저장소 아님이거나 권한 꺼짐 모양' }
    } catch {
        Add-Row 'git HEAD' 'FAIL' ("실행 거부 — 권한 꺼짐 모양 가능: " + $_.Exception.Message.Substring(0, [Math]::Min(60, $_.Exception.Message.Length)))
    }
}

# --- 3. lease who 요약 ---------------------------------------------------------
$whoScript = Join-Path $Root 'scripts\agent_scope_lease.py'
if (-not (Test-Path -LiteralPath $whoScript)) {
    Add-Row 'lease who' 'SKIP' 'agent_scope_lease.py 없음 — 루트가 아니거나 파일 부재'
} else {
    try {
        $whoJson = & python -B $whoScript 'who' 2>$null
        if ($LASTEXITCODE -eq 0 -and $whoJson) {
            $w = $whoJson | ConvertFrom-Json
            $lc = $w.leaseCounts
            Add-Row 'lease who' 'PASS' ("active=$($lc.active) expired=$($lc.expired) blocking=$($lc.blocking)")
        } else {
            Add-Row 'lease who' 'FAIL' "exit=$LASTEXITCODE — python 부재이거나 권한 꺼짐 모양"
        }
    } catch {
        Add-Row 'lease who' 'FAIL' ("실행 거부 — 권한 꺼짐 모양 가능: " + $_.Exception.Message.Substring(0, [Math]::Min(60, $_.Exception.Message.Length)))
    }
}

# --- 4. Downloads 최신 PASTE_*.txt 3개 -----------------------------------------
try {
    $pastes = @(Get-ChildItem -LiteralPath $DownloadsDir -Filter 'PASTE_*.txt' -File -ErrorAction Stop |
                Sort-Object LastWriteTime -Descending | Select-Object -First 3)
    if ($pastes.Count -gt 0) {
        $names = ($pastes | ForEach-Object { "$($_.Name)($($_.Length)B)" }) -join '; '
        Add-Row 'Downloads PASTE 읽기' 'PASS' $names
    } else {
        Add-Row 'Downloads PASTE 읽기' 'PASS' 'PASTE_*.txt 없음 — 읽기는 됨'
    }
} catch {
    Add-Row 'Downloads PASTE 읽기' 'FAIL' ("읽기 거부 — 권한 꺼짐 모양이거나 Downloads 범위 밖: " + $_.Exception.Message.Substring(0, [Math]::Min(60, $_.Exception.Message.Length)))
}

# --- 5. Downloads 쓰기 (자기 파일만) -------------------------------------------
$selfDir = Join-Path $DownloadsDir '_jeom_selftest'
$selfFile = Join-Path $selfDir ("jeom_selftest_" + $PID + '.tmp')
$madeDir = $false
try {
    if (-not (Test-Path -LiteralPath $selfDir)) { New-Item -ItemType Directory -Path $selfDir -Force | Out-Null; $madeDir = $true }
    $payload = "jeom-selftest " + (Get-Date -Format 'o')
    [IO.File]::WriteAllText($selfFile, $payload, [Text.UTF8Encoding]::new($false))
    $sha = Get-Sha12 $selfFile
    Add-Row 'Downloads 쓰기' 'PASS' ("sha12=" + $sha)
} catch {
    Add-Row 'Downloads 쓰기' 'FAIL' ("쓰기 거부 — 권한 꺼짐 모양이거나 쓰기 범위 밖(권장: Downloads는 쓰기 허용): " + $_.Exception.Message.Substring(0, [Math]::Min(60, $_.Exception.Message.Length)))
} finally {
    if (Test-Path -LiteralPath $selfFile) { Remove-Item -LiteralPath $selfFile -Force -ErrorAction SilentlyContinue }
    if ($madeDir -and (Test-Path -LiteralPath $selfDir)) {
        if (@(Get-ChildItem -LiteralPath $selfDir -Force -ErrorAction SilentlyContinue).Count -eq 0) {
            Remove-Item -LiteralPath $selfDir -Force -ErrorAction SilentlyContinue
        }
    }
}

# --- 6. agent-prompts 쓰기 ------------------------------------------------------
$apDir = Join-Path $Root 'agent-prompts'
$apFile = Join-Path $apDir ("jeom_selftest_" + $PID + '.tmp')
try {
    [IO.File]::WriteAllText($apFile, 'probe', [Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $apFile -Force -ErrorAction Stop
    Add-Row 'agent-prompts 쓰기' 'PASS' '임시 파일 생성·삭제 됨'
} catch {
    Add-Row 'agent-prompts 쓰기' 'FAIL' ("쓰기 거부 — 권한 꺼짐 모양이거나 쓰기 범위 밖(권장: agent-prompts는 쓰기 허용): " + $_.Exception.Message.Substring(0, [Math]::Min(60, $_.Exception.Message.Length)))
    if (Test-Path -LiteralPath $apFile) { Remove-Item -LiteralPath $apFile -Force -ErrorAction SilentlyContinue }
}

# --- 7. 인계 팩 sha12 ------------------------------------------------------------
$packDir = Join-Path $Root 'agent-prompts\devin-agy-grokbot-upgrade-20261002\handover'
$expected = [ordered]@{
    'HANDOVER.md' = '85636aee2695'
    'demo1-agent-brief-writer.md' = '5d983db409c0'
    'demo1-agent-report-review.md' = '696949b9ef79'
    'demo1-multi-agent-handoff.md' = '1fed5fb1693f'
    'demo1-top10-the-one-probe.md' = '92458244ebb0'
}
if (-not (Test-Path -LiteralPath $packDir)) {
    Add-Row '인계 팩 sha12' 'FAIL' 'handover 폴더 없음 — 경로 오류이거나 권한 꺼짐 모양'
} else {
    $bad = @()
    foreach ($k in $expected.Keys) {
        $p = Join-Path $packDir $k
        if (-not (Test-Path -LiteralPath $p)) { $bad += "$k 없음"; continue }
        if ((Get-Sha12 $p) -ne $expected[$k]) { $bad += "$k 불일치" }
    }
    if ($bad.Count -eq 0) { Add-Row '인계 팩 sha12' 'PASS' '5개 일치' }
    else { Add-Row '인계 팩 sha12' 'FAIL' ($bad -join '; ') }
}

# --- 8. .secrets 존재만 ----------------------------------------------------------
$secDir = Join-Path $Root '.secrets'
try {
    if (Test-Path -LiteralPath $secDir) {
        Add-Row '.secrets 존재' 'PASS' '존재 확인만 — 내용 미열람(의도)'
    } else {
        Add-Row '.secrets 존재' 'SKIP' '없음 — 이 루트에 비밀 디렉터리 없음'
    }
} catch {
    Add-Row '.secrets 존재' 'SKIP' '확인 불가 — 내용은 어떤 경우에도 읽지 않음(의도)'
}

# --- 출력 ----------------------------------------------------------------------
$failCount = @($script:Rows | Where-Object { $_.status -eq 'FAIL' }).Count
if ($Json) {
    $script:Rows | ConvertTo-Json -Depth 3
} else {
    '{0} | {1} | {2}' -f '항목', '결과', '메모'
    '{0} | {1} | {2}' -f ('-' * 20), ('-' * 6), ('-' * 40)
    foreach ($r in $script:Rows) {
        '{0} | {1} | {2}' -f $r.item, $r.status, $r.note
    }
    ''
    "합계: PASS $(@($script:Rows | Where-Object status -eq 'PASS').Count) / FAIL $failCount / SKIP $(@($script:Rows | Where-Object status -eq 'SKIP').Count)"
    '구분 안내: FAIL 메모의 "권한 꺼짐 모양"은 점의 local-computer permission(점 프로필 → Computers → Allow access)을 확인. "범위 밖"은 의도된 차단.'
}
exit ($(if ($failCount -gt 0) { 2 } else { 0 }))
