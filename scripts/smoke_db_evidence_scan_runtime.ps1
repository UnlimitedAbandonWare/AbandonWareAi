[CmdletBinding()]
param(
    [string]$Root = '',

    [string]$BaseUrl = '',

    [int]$Port = 18080,

    [int]$ManagementPort = 18081,

    [int]$NettyPort = 9092,

    [int]$TimeoutSec = 60,

    [int]$BootTimeoutSec = 120,

    [string]$OutputDir = '',

    [string]$AdminToken = $env:AWX_DB_EVIDENCE_SMOKE_ADMIN_TOKEN,

    [switch]$AssumeRunning,

    [switch]$RequireRemoteSupabaseProof
)

$ErrorActionPreference = 'Stop'

function Test-EnvPresent {
    param([Parameter(Mandatory = $true)][string]$Name)
    return -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name))
}

function Set-ProcessEnv {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Previous,
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Value
    )
    if (-not $Previous.ContainsKey($Name)) {
        $Previous[$Name] = [Environment]::GetEnvironmentVariable($Name)
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Restore-ProcessEnv {
    param([Parameter(Mandatory = $true)][hashtable]$Previous)
    foreach ($name in $Previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $Previous[$name], 'Process')
    }
}

function Wait-ForEndpoint {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [int]$TimeoutSeconds = 120
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = ''
    while ((Get-Date) -lt $deadline) {
        try {
            return Invoke-RestMethod -Method Get -Uri $Uri -Headers $Headers -TimeoutSec 5
        } catch {
            $lastError = $_.Exception.GetType().Name
            Start-Sleep -Seconds 2
        }
    }
    throw "evidence_needed: internal agent endpoint not ready within timeout; lastError=$lastError"
}

function Count-Pattern {
    param(
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern
    )
    if ([string]::IsNullOrEmpty($Text)) {
        return 0
    }
    return ([regex]::Matches($Text, $Pattern)).Count
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        return @(& $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Stop-StartedProcess {
    param([System.Diagnostics.Process]$Process)
    if ($null -eq $Process) {
        return
    }
    try {
        if (-not $Process.HasExited) {
            Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        }
    } catch {
        Write-Host "[AWX][db-evidence-scan][runtime] stop warning type=$($_.Exception.GetType().Name)"
    }
}

function Stop-StartedRuntime {
    param(
        [System.Diagnostics.Process]$Process,
        [int[]]$Ports = @()
    )
    Stop-StartedProcess -Process $Process
    if ($null -eq $Process) {
        return
    }
    foreach ($owningProcessId in @(Get-NetTCPConnection -LocalPort $Ports -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique)) {
        if ($owningProcessId) {
            try {
                Stop-Process -Id $owningProcessId -Force -ErrorAction SilentlyContinue
            } catch {
                Write-Host "[AWX][db-evidence-scan][runtime] child stop warning type=$($_.Exception.GetType().Name)"
            }
        }
    }
}

function Find-BootJar {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)
    $candidates = @()
    foreach ($dir in @(
            (Join-Path $ProjectRoot 'build\desktop\libs'),
            (Join-Path $ProjectRoot 'build\libs'))) {
        if (Test-Path -LiteralPath $dir) {
            $candidates += Get-ChildItem -LiteralPath $dir -Filter '*.jar' -File |
                Where-Object { $_.Name -notmatch '(?i)-plain\.jar$' }
        }
    }
    return $candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    $BaseUrl = "http://127.0.0.1:$Port"
}
if ([string]::IsNullOrWhiteSpace($Root)) {
    $scriptRoot = $PSScriptRoot
    if ([string]::IsNullOrWhiteSpace($scriptRoot)) {
        $scriptRoot = Split-Path -Parent $PSCommandPath
    }
    $Root = (Resolve-Path (Join-Path $scriptRoot '..')).Path
}
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $Root 'var\codex-smoke\db-evidence-scan'
}
if ([string]::IsNullOrWhiteSpace($AdminToken)) {
    $AdminToken = 'awx-local-smoke-token-' + [guid]::NewGuid().ToString('N')
}

$startedProcess = $null
$previousEnv = @{}
$toolsUri = "$BaseUrl/internal/agent/tools"
$invokeUri = "$BaseUrl/internal/agent/tools/db_evidence_scan:invoke"
$repoScanUri = "$BaseUrl/internal/agent/tools/repo.scan:invoke"
$opsSnapshotUri = "$BaseUrl/internal/agent/tools/ops.snapshot:invoke"
$verifyContractUri = "$BaseUrl/internal/agent/tools/verify.contract:invoke"
$agentDbSnapshotToolbox = Join-Path $Root 'scripts\awx_mcp_toolbox.ps1'
$secretPattern = 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Bearer\s+[A-Za-z0-9._-]+'
$windowsPathPattern = '[A-Za-z]:\\(?:[^\\\r\n" ]+\\)*[^\\\r\n" ]+'
$rawUrlPattern = '(?i)\b(?:jdbc|https?)://'
$rawPathKeyPattern = '"path"\s*:'
$artifactPathKeyPattern = '"artifactPath"\s*:'
$resourcePathKeyPattern = '"resourcePath"\s*:'
$manifestResourceKeyPattern = '"manifestResource"\s*:'
$reportPathKeyPattern = '"reportPath"\s*:'

try {
    if (-not (Test-Path -LiteralPath $Root)) {
        Write-Host "[AWX][db-evidence-scan][runtime] evidence_needed: root missing"
        exit 2
    }
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

    $headers = @{
        'X-Admin-Token' = $AdminToken
        'X-Session-Id' = 'codex-db-evidence-runtime-smoke'
        'X-Request-Id' = 'codex-db-evidence-runtime-smoke-001'
        'X-Agent-Tool-Budget-Ms' = '60000'
    }
    Set-ProcessEnv $previousEnv 'AWX_ADMIN_TOKEN' $AdminToken
    Set-ProcessEnv $previousEnv 'AWX_AGENT_DB_CONTEXT_BASE_URL' $BaseUrl
    Set-ProcessEnv $previousEnv 'AWX_TRACE_SNAPSHOT_BASE_URL' $BaseUrl

    if (-not $AssumeRunning) {
        $gradlew = Join-Path $Root 'gradlew.bat'
        if (-not (Test-Path -LiteralPath $gradlew)) {
            Write-Host "[AWX][db-evidence-scan][runtime] evidence_needed: gradlew.bat missing"
            exit 2
        }
        $busyPorts = Get-NetTCPConnection -LocalPort $Port,$ManagementPort,$NettyPort -ErrorAction SilentlyContinue
        if ($busyPorts) {
            Write-Host "[AWX][db-evidence-scan][runtime] evidence_needed: port-conflict port=$Port managementPort=$ManagementPort nettyPort=$NettyPort"
            exit 2
        }

        $gradleUserHome = Join-Path $env:USERPROFILE '.gradle-awx-desktop'
        $projectCache = Join-Path $env:USERPROFILE '.awx-gradle-project-cache\desktop'
        New-Item -ItemType Directory -Force -Path $gradleUserHome,$projectCache | Out-Null

        Set-ProcessEnv $previousEnv 'AWX_AGENT_HOST' 'desktop'
        Set-ProcessEnv $previousEnv 'AWX_SPLIT_BUILD_OUTPUTS' '1'
        Set-ProcessEnv $previousEnv 'AWX_BUILD_HOST_ID' 'desktop'
        Set-ProcessEnv $previousEnv 'GRADLE_USER_HOME' $gradleUserHome
        Set-ProcessEnv $previousEnv 'SPRING_PROFILES_ACTIVE' 'local'
        Set-ProcessEnv $previousEnv 'SERVER_PORT' ([string]$Port)
        Set-ProcessEnv $previousEnv 'MANAGEMENT_SERVER_PORT' ([string]$ManagementPort)
        Set-ProcessEnv $previousEnv 'AGENT_TOOLS_API_ENABLED' 'true'
        Set-ProcessEnv $previousEnv 'DOMAIN_ALLOWLIST_ADMIN_TOKEN' $AdminToken
        Set-ProcessEnv $previousEnv 'DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED' 'true'
        Set-ProcessEnv $previousEnv 'PROBE_SEARCH_ENABLED' 'false'
        Set-ProcessEnv $previousEnv 'GPT_SEARCH_BRAVE_ENABLED' 'false'
        Set-ProcessEnv $previousEnv 'GPT_SEARCH_SERPAPI_ENABLED' 'false'
        Set-ProcessEnv $previousEnv 'RETRIEVAL_KG_NEO4J_ENABLED' 'false'
        Set-ProcessEnv $previousEnv 'LMS_CORPUS_STARTUP_ENABLED' 'false'
        Set-ProcessEnv $previousEnv 'UAW_AUTOLEARN_ENABLED' 'false'

        $bootJarLog = Join-Path $OutputDir 'bootjar.log'
        $bootJarLines = Invoke-NativeCapture -FilePath $gradlew -Arguments @(
            'bootJar',
            '-x', 'test',
            '--no-daemon',
            '--project-cache-dir', $projectCache
        )
        Set-Content -LiteralPath $bootJarLog -Value ($bootJarLines -join "`n") -Encoding UTF8
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[AWX][db-evidence-scan][runtime] evidence_needed: bootJar failed"
            exit 3
        }

        $jar = Find-BootJar -ProjectRoot $Root
        if ($null -eq $jar) {
            Write-Host "[AWX][db-evidence-scan][runtime] evidence_needed: bootJar artifact missing"
            exit 3
        }
        $java = Get-Command java -ErrorAction SilentlyContinue
        if ($null -eq $java) {
            Write-Host "[AWX][db-evidence-scan][runtime] evidence_needed: java command missing"
            exit 2
        }

        $outLog = Join-Path $OutputDir 'runtime.out.log'
        $errLog = Join-Path $OutputDir 'runtime.err.log'
        Remove-Item -LiteralPath $outLog,$errLog -ErrorAction SilentlyContinue
        $startedProcess = Start-Process -FilePath $java.Source `
            -ArgumentList @('-jar', $jar.FullName) `
            -WorkingDirectory $Root `
            -RedirectStandardOutput $outLog `
            -RedirectStandardError $errLog `
            -WindowStyle Hidden `
            -PassThru
        Write-Host "[AWX][db-evidence-scan][runtime] java runtime started processId=$($startedProcess.Id) jarLength=$($jar.FullName.Length) port=$Port managementPort=$ManagementPort nettyPort=$NettyPort"
    } else {
        Write-Host "[AWX][db-evidence-scan][runtime] assumeRunning=true baseUrlLength=$($BaseUrl.Length)"
    }

    $tools = Wait-ForEndpoint -Uri $toolsUri -Headers $headers -TimeoutSeconds $BootTimeoutSec
    $scan = Invoke-RestMethod -Method Post -Uri $invokeUri -Headers $headers -ContentType 'application/json' -Body '{}' -TimeoutSec $TimeoutSec
    $repoScan = Invoke-RestMethod -Method Post -Uri $repoScanUri -Headers $headers -ContentType 'application/json' -Body '{}' -TimeoutSec $TimeoutSec
    $opsSnapshot = Invoke-RestMethod -Method Post -Uri $opsSnapshotUri -Headers $headers -ContentType 'application/json' -Body '{}' -TimeoutSec $TimeoutSec

    $toolsJson = $tools | ConvertTo-Json -Depth 80
    $scanJson = $scan | ConvertTo-Json -Depth 100
    $repoScanJson = $repoScan | ConvertTo-Json -Depth 80
    $opsSnapshotJson = $opsSnapshot | ConvertTo-Json -Depth 80
    $toolsPath = Join-Path $OutputDir 'internal-agent-tools.json'
    $scanPath = Join-Path $OutputDir 'db-evidence-scan.invoke.json'
    $repoScanPath = Join-Path $OutputDir 'repo-scan.invoke.json'
    $opsSnapshotPath = Join-Path $OutputDir 'ops-snapshot.invoke.json'
    Set-Content -LiteralPath $toolsPath -Value $toolsJson -Encoding UTF8
    Set-Content -LiteralPath $scanPath -Value $scanJson -Encoding UTF8
    Set-Content -LiteralPath $repoScanPath -Value $repoScanJson -Encoding UTF8
    Set-Content -LiteralPath $opsSnapshotPath -Value $opsSnapshotJson -Encoding UTF8

    $data = $scan.data.dbEvidenceScan
    $hasDbTool = $false
    $hasRepoScanTool = $false
    $hasOpsSnapshotTool = $false
    $hasVerifyContractTool = $false
    foreach ($tool in @($tools.tools)) {
        if ($tool.id -eq 'db_evidence_scan') {
            $hasDbTool = $true
        }
        if ($tool.id -eq 'repo.scan') {
            $hasRepoScanTool = $true
        }
        if ($tool.id -eq 'ops.snapshot') {
            $hasOpsSnapshotTool = $true
        }
        if ($tool.id -eq 'verify.contract') {
            $hasVerifyContractTool = $true
        }
    }

    $verifyContract = Invoke-RestMethod -Method Post -Uri $verifyContractUri -Headers $headers -ContentType 'application/json' -Body '{}' -TimeoutSec $TimeoutSec
    $verifyContractJson = $verifyContract | ConvertTo-Json -Depth 80
    $verifyContractPath = Join-Path $OutputDir 'verify-contract.invoke.json'
    Set-Content -LiteralPath $verifyContractPath -Value $verifyContractJson -Encoding UTF8

    if (-not (Test-Path -LiteralPath $agentDbSnapshotToolbox)) {
        Write-Host "[AWX][db-evidence-scan][runtime] evidence_needed: scripts\awx_mcp_toolbox.ps1 missing for agent_db_snapshot"
        exit 3
    }
    $agentDbSnapshotPayload = [ordered]@{
        endpoint = 'snapshot'
        base_url = $BaseUrl
        timeout_sec = $TimeoutSec
        nodeRole = 'desktop'
        requestId = 'codex-db-evidence-runtime-smoke-db-context'
        root = $Root
    } | ConvertTo-Json -Depth 8 -Compress
    $agentDbSnapshotRaw = & $agentDbSnapshotToolbox -Root $Root -Tool agent_db_snapshot -InputJson $agentDbSnapshotPayload
    try {
        $agentDbSnapshot = ($agentDbSnapshotRaw -join "`n") | ConvertFrom-Json -ErrorAction Stop
    } catch {
        Write-Host "[AWX][db-evidence-scan][runtime] evidence_needed: invalid agent_db_snapshot JSON"
        exit 3
    }
    $agentDbSnapshotJson = $agentDbSnapshot | ConvertTo-Json -Depth 100
    $agentDbSnapshotPath = Join-Path $OutputDir 'agent-db-snapshot.invoke.json'
    Set-Content -LiteralPath $agentDbSnapshotPath -Value $agentDbSnapshotJson -Encoding UTF8

    $traceSnapshotPayload = [ordered]@{
        base_url = $BaseUrl
        timeout_sec = $TimeoutSec
        limit = 5
        nodeRole = 'desktop'
        requestId = 'codex-db-evidence-runtime-smoke-trace-snapshot'
        root = $Root
    } | ConvertTo-Json -Depth 8 -Compress
    $traceSnapshotRaw = & $agentDbSnapshotToolbox -Root $Root -Tool trace_snapshot_probe -InputJson $traceSnapshotPayload
    try {
        $traceSnapshot = ($traceSnapshotRaw -join "`n") | ConvertFrom-Json -ErrorAction Stop
    } catch {
        Write-Host "[AWX][db-evidence-scan][runtime] evidence_needed: invalid trace_snapshot_probe JSON"
        exit 3
    }
    $traceSnapshotJson = $traceSnapshot | ConvertTo-Json -Depth 100
    $traceSnapshotPath = Join-Path $OutputDir 'trace-snapshot-probe.invoke.json'
    Set-Content -LiteralPath $traceSnapshotPath -Value $traceSnapshotJson -Encoding UTF8

    $combinedJson = $toolsJson + "`n" + $scanJson + "`n" + $repoScanJson + "`n" + $opsSnapshotJson + "`n" + $verifyContractJson + "`n" + $agentDbSnapshotJson + "`n" + $traceSnapshotJson
    $secretPatternHits = Count-Pattern -Text $combinedJson -Pattern $secretPattern
    $windowsAbsPathHits = Count-Pattern -Text $combinedJson -Pattern $windowsPathPattern
    $rawUrlHits = Count-Pattern -Text $combinedJson -Pattern $rawUrlPattern
    $rawPathKeyHits = Count-Pattern -Text $repoScanJson -Pattern $rawPathKeyPattern
    $agentDbSnapshotPathKeyHits = Count-Pattern -Text $agentDbSnapshotJson -Pattern $rawPathKeyPattern
    $traceSnapshotPathKeyHits = Count-Pattern -Text $traceSnapshotJson -Pattern $rawPathKeyPattern
    $artifactPathKeyHits = Count-Pattern -Text $combinedJson -Pattern $artifactPathKeyPattern
    $resourcePathKeyHits = Count-Pattern -Text $combinedJson -Pattern $resourcePathKeyPattern
    $manifestResourceKeyHits = Count-Pattern -Text $opsSnapshotJson -Pattern $manifestResourceKeyPattern
    $reportPathKeyHits = Count-Pattern -Text $verifyContractJson -Pattern $reportPathKeyPattern
    $remoteSqlEvidenceNeeded = $false
    if ($null -ne $data -and $null -ne $data.supabase) {
        $remoteSqlEvidenceNeeded = [bool]$data.supabase.remoteSqlEvidenceNeeded
    }

    Write-Host "[AWX][db-evidence-scan][runtime] toolsOk=$($tools.ok) toolCount=$(@($tools.tools).Count) hasDbEvidenceScan=$hasDbTool hasRepoScan=$hasRepoScanTool hasOpsSnapshot=$hasOpsSnapshotTool hasVerifyContract=$hasVerifyContractTool"
    Write-Host "[AWX][db-evidence-scan][runtime] invokeOk=$($scan.ok) toolId=$($scan.toolId) readOnly=$($scan.readOnly) truncated=$($scan.truncated)"
    Write-Host "[AWX][db-evidence-scan][runtime] repoScanOk=$($repoScan.ok) toolId=$($repoScan.toolId) verifyContractOk=$($verifyContract.ok) rawPathKeyHits=$rawPathKeyHits agentDbSnapshotPathKeyHits=$agentDbSnapshotPathKeyHits traceSnapshotPathKeyHits=$traceSnapshotPathKeyHits artifactPathKeyHits=$artifactPathKeyHits resourcePathKeyHits=$resourcePathKeyHits manifestResourceKeyHits=$manifestResourceKeyHits reportPathKeyHits=$reportPathKeyHits"
    Write-Host "[AWX][db-evidence-scan][runtime] agentDbSnapshotOk=$($agentDbSnapshot.ok) decision=$($agentDbSnapshot.decision) httpStatus=$($agentDbSnapshot.httpStatus) tokenPresented=$($agentDbSnapshot.tokenPresented)"
    Write-Host "[AWX][db-evidence-scan][runtime] traceSnapshotOk=$($traceSnapshot.ok) decision=$($traceSnapshot.decision) httpStatus=$($traceSnapshot.httpStatus) tokenPresented=$($traceSnapshot.tokenPresented) snapshotCount=$($traceSnapshot.snapshotCount)"
    Write-Host "[AWX][db-evidence-scan][runtime] postgresPresent=$($null -ne $data.postgres) supabasePresent=$($null -ne $data.supabase) remoteSqlEvidenceNeeded=$remoteSqlEvidenceNeeded"
    Write-Host "[AWX][db-evidence-scan][runtime] secretPatternHits=$secretPatternHits windowsAbsPathHits=$windowsAbsPathHits rawUrlHits=$rawUrlHits"

    $missingSupabaseEnv = @()
    foreach ($name in @('SUPABASE_PROJECT_REF', 'SUPABASE_ACCESS_TOKEN', 'SUPABASE_DB_URL')) {
        if (-not (Test-EnvPresent $name)) {
            $missingSupabaseEnv += $name
        }
    }
    if ($missingSupabaseEnv.Count -gt 0) {
        Write-Host "[AWX][db-evidence-scan][supabase] evidence_needed: missing env refs: $($missingSupabaseEnv -join ',')"
    }
    if ($RequireRemoteSupabaseProof -and $missingSupabaseEnv.Count -gt 0) {
        exit 2
    }

    if ($tools.ok -ne $true -or -not $hasDbTool -or -not $hasRepoScanTool -or -not $hasOpsSnapshotTool -or -not $hasVerifyContractTool -or $scan.ok -ne $true -or $repoScan.ok -ne $true -or $opsSnapshot.ok -ne $true -or $verifyContract.ok -ne $true -or $agentDbSnapshot.ok -ne $true -or $traceSnapshot.ok -ne $true -or $null -eq $data -or $null -eq $data.postgres -or $null -eq $data.supabase) {
        Write-Host '[AWX][db-evidence-scan][runtime] evidence_needed: runtime response missing required db_evidence_scan fields'
        exit 3
    }
    if ($secretPatternHits -gt 0 -or $windowsAbsPathHits -gt 0 -or $rawUrlHits -gt 0 -or $rawPathKeyHits -gt 0 -or $agentDbSnapshotPathKeyHits -gt 0 -or $traceSnapshotPathKeyHits -gt 0 -or $artifactPathKeyHits -gt 0 -or $resourcePathKeyHits -gt 0 -or $manifestResourceKeyHits -gt 0 -or $reportPathKeyHits -gt 0) {
        Write-Host '[AWX][db-evidence-scan][runtime] secret-leak-risk: response contains forbidden raw pattern'
        exit 4
    }

    Write-Host "[AWX][db-evidence-scan][runtime] ok=true outputDirHashOnly length=$($OutputDir.Length)"
    exit 0
} finally {
    Stop-StartedRuntime -Process $startedProcess -Ports @($Port, $ManagementPort, $NettyPort)
    Restore-ProcessEnv -Previous $previousEnv
}
