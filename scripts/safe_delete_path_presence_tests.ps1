$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$PowerShellExe = $null
try {
    $PowerShellExe = (Get-Process -Id $PID).Path
} catch {
    $PowerShellExe = $null
}
if ([string]::IsNullOrWhiteSpace($PowerShellExe)) {
    $PowerShellExe = 'powershell'
}

$FakeRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-safe-delete-presence-tests-' + [guid]::NewGuid().ToString('N'))
$EscapedOutputPath = Join-Path ([IO.Path]::GetTempPath()) ('awx-safe-delete-escaped-' + [guid]::NewGuid().ToString('N') + '.json')
$AbsoluteOutputPath = Join-Path ([IO.Path]::GetTempPath()) ('awx-safe-delete-absolute-' + [guid]::NewGuid().ToString('N') + '.json')
$Failures = 0

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Name)
    Write-Host "[safe-delete-presence-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[safe-delete-presence-test][FAIL] $Name :: $Message"
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Message = 'assertion failed'
    )
    if ($Condition) { Write-Pass $Name } else { Write-Fail $Name $Message }
}

function Set-TestFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowEmptyString()][string]$Value
    )
    $parent = Split-Path -Parent $Path
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Invoke-SafeDeletePresence {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )
    $scriptPath = Join-Path $script:ScriptsRoot 'safe_delete_path_presence.ps1'
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        $scriptPath,
        '-Root',
        $Root,
        '-OutputPath',
        $OutputPath,
        '-Json'
    )
    $lines = & $script:PowerShellExe @arguments 2>&1 |
        ForEach-Object { $_.ToString() }
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($lines -join "`n")
        Json = if (Test-Path -LiteralPath $OutputPath) {
            try { Get-Content -LiteralPath $OutputPath -Raw | ConvertFrom-Json } catch { $null }
        } else {
            $null
        }
    }
}

try {
    New-Item -ItemType Directory -Force -Path $FakeRoot | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $FakeRoot '__reports__') | Out-Null
    Set-TestFile (Join-Path $FakeRoot 'BackupsXS\index.jsonl') '{"path":"archive/example.md","summary":"fixture"}'
    Set-TestFile (Join-Path $FakeRoot 'apikey.txt') ('sk-' + ('A' * 24))

    $outputPath = Join-Path $FakeRoot 'safe-delete-path-presence.json'
    $result = Invoke-SafeDeletePresence -Root $FakeRoot -OutputPath $outputPath
    $rawArtifact = if (Test-Path -LiteralPath $outputPath) { Get-Content -LiteralPath $outputPath -Raw } else { '' }
    $combinedOutput = $result.Output + "`n" + $rawArtifact

    Assert-True 'safe delete presence script exits zero' ($result.ExitCode -eq 0) "exit=$($result.ExitCode) output=$($result.Output)"
    Assert-True 'safe delete presence writes parseable json' ($null -ne $result.Json) "output=$($result.Output)"

    if ($null -ne $result.Json) {
        Assert-True 'safe delete presence is read-only' ($result.Json.mutationAllowed -eq $false -and $result.Json.deleteCommandEmitted -eq $false) "json=$rawArtifact"
        Assert-True 'safe delete presence records present and absent candidates' ([int]$result.Json.presentCount -ge 3 -and [int]$result.Json.absentCount -ge 1) "json=$rawArtifact"

        $apikey = @($result.Json.candidates | Where-Object { $_.path -eq 'apikey.txt' } | Select-Object -First 1)
        Assert-True 'safe delete presence holds secret path candidates' ($null -ne $apikey -and $apikey.classification -eq 'HOLD_SECRET_PATH' -and $apikey.deleteAllowed -eq $false) "json=$rawArtifact"

        $archiveIndex = @($result.Json.candidates | Where-Object { $_.path -eq 'BackupsXS\index.jsonl' } | Select-Object -First 1)
        Assert-True 'safe delete presence detects archive index separately' ($null -ne $archiveIndex -and $archiveIndex.exists -eq $true -and $archiveIndex.classification -eq 'HOLD_ARCHIVE_INDEX') "json=$rawArtifact"
    }

    Assert-True 'safe delete presence output does not reveal secret-like values' (-not ($combinedOutput -match 'sk-[A-Za-z0-9_-]{20,}|sbp_[A-Za-z0-9_-]{10,}|sb_secret_[A-Za-z0-9_-]{10,}')) "output=$combinedOutput"
    Assert-True 'safe delete presence output emits no remove command' (-not ($combinedOutput -match 'Remove-Item')) "output=$combinedOutput"

    $callerDirectory = Join-Path $FakeRoot 'divergent-caller'
    New-Item -ItemType Directory -Force -Path $callerDirectory | Out-Null
    $relativeOutputPath = 'var\codex-smoke\safe-delete-relative.json'
    $rootAnchoredOutput = Join-Path $FakeRoot $relativeOutputPath
    $callerAnchoredOutput = Join-Path $callerDirectory $relativeOutputPath
    Push-Location $callerDirectory
    try {
        $relativeResult = Invoke-SafeDeletePresence -Root $FakeRoot -OutputPath $relativeOutputPath
    } finally {
        Pop-Location
    }

    Assert-True 'relative evidence output exits zero from divergent cwd' ($relativeResult.ExitCode -eq 0) "exit=$($relativeResult.ExitCode)"
    Assert-True 'relative evidence output is rooted under audited root' (Test-Path -LiteralPath $rootAnchoredOutput -PathType Leaf) 'root-anchored artifact missing'
    Assert-True 'relative evidence output does not escape to caller cwd' (-not (Test-Path -LiteralPath $callerAnchoredOutput)) 'caller-cwd artifact created'

    $traversalOutputPath = Join-Path '..' ([IO.Path]::GetFileName($EscapedOutputPath))
    Push-Location $callerDirectory
    try {
        $traversalResult = Invoke-SafeDeletePresence -Root $FakeRoot -OutputPath $traversalOutputPath
    } finally {
        Pop-Location
    }

    Assert-True 'relative traversal output is rejected' ($traversalResult.ExitCode -ne 0) "exit=$($traversalResult.ExitCode)"
    Assert-True 'relative traversal output creates no parent artifact' (-not (Test-Path -LiteralPath $EscapedOutputPath)) 'parent artifact created'

    $collisionRoot = Join-Path $FakeRoot 'candidate-output-collision'
    New-Item -ItemType Directory -Force -Path $collisionRoot | Out-Null
    $collisionCandidate = Join-Path $collisionRoot 'apikey.txt'
    $collisionMarker = 'benign-preservation-marker'
    Set-TestFile -Path $collisionCandidate -Value $collisionMarker
    Push-Location $collisionRoot
    try {
        $collisionResult = Invoke-SafeDeletePresence -Root $collisionRoot -OutputPath 'apikey.txt'
    } finally {
        Pop-Location
    }
    $collisionAfter = Get-Content -LiteralPath $collisionCandidate -Raw

    Assert-True 'audited candidate output collision is rejected' ($collisionResult.ExitCode -ne 0) "exit=$($collisionResult.ExitCode)"
    Assert-True 'audited candidate output collision preserves bytes' ($collisionAfter -ceq $collisionMarker) 'candidate bytes changed'
    Assert-True 'audited candidate output collision is categorical' ($collisionResult.Output -match 'output_path_conflicts_with_audited_candidate') "output=$($collisionResult.Output)"
    Assert-True 'audited candidate output collision emits no success json' ($collisionResult.Output -notmatch '"mutationAllowed"') "output=$($collisionResult.Output)"

    $hardLinkParent = Join-Path $collisionRoot 'var'
    New-Item -ItemType Directory -Force -Path $hardLinkParent | Out-Null
    $hardLinkOutput = Join-Path $hardLinkParent 'hardlink-report.json'
    New-Item -ItemType HardLink -Path $hardLinkOutput -Target $collisionCandidate | Out-Null
    Push-Location $collisionRoot
    try {
        $hardLinkResult = Invoke-SafeDeletePresence -Root $collisionRoot -OutputPath 'var\hardlink-report.json'
    } finally {
        Pop-Location
    }
    $hardLinkCandidateAfter = Get-Content -LiteralPath $collisionCandidate -Raw

    Assert-True 'hard-link output alias is rejected' ($hardLinkResult.ExitCode -ne 0) "exit=$($hardLinkResult.ExitCode)"
    Assert-True 'hard-link output alias preserves candidate bytes' ($hardLinkCandidateAfter -ceq $collisionMarker) 'candidate bytes changed through hard link'
    Assert-True 'hard-link output alias is categorical' ($hardLinkResult.Output -match 'output_path_link_not_supported') "output=$($hardLinkResult.Output)"
    Assert-True 'hard-link output alias emits no success json' ($hardLinkResult.Output -notmatch '"mutationAllowed"') "output=$($hardLinkResult.Output)"

    Push-Location $callerDirectory
    try {
        $absoluteResult = Invoke-SafeDeletePresence -Root $FakeRoot -OutputPath $AbsoluteOutputPath
    } finally {
        Pop-Location
    }
    Assert-True 'explicit absolute evidence output remains supported' ($absoluteResult.ExitCode -eq 0 -and (Test-Path -LiteralPath $AbsoluteOutputPath -PathType Leaf)) "exit=$($absoluteResult.ExitCode)"
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
} finally {
    if (Test-Path $FakeRoot) {
        Remove-Item -LiteralPath $FakeRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $EscapedOutputPath -PathType Leaf) {
        Remove-Item -LiteralPath $EscapedOutputPath -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $AbsoluteOutputPath -PathType Leaf) {
        Remove-Item -LiteralPath $AbsoluteOutputPath -Force -ErrorAction SilentlyContinue
    }
}

if ($Failures -gt 0) {
    Write-Host "[safe-delete-presence-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[safe-delete-presence-test][SUMMARY] failed=0'
exit 0
