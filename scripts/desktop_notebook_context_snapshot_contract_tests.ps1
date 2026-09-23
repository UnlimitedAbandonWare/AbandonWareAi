$ErrorActionPreference = 'Stop'

function Assert-True([bool]$Value, [string]$Message) {
    if (-not $Value) { throw "assert-true-failed: $Message" }
}

function Assert-False([bool]$Value, [string]$Message) {
    if ($Value) { throw "assert-false-failed: $Message" }
}

function Assert-Equal($Actual, $Expected) {
    if ($Actual -ne $Expected) { throw "assert-equal-failed: expected [$Expected], actual [$Actual]" }
}

function Assert-Contains([string]$Text, [string]$Expected) {
    if (-not $Text.Contains($Expected)) { throw "assert-contains-failed: missing [$Expected]" }
}

function Assert-NotContains([string]$Text, [string]$Unexpected, [string]$Message) {
    if ($Text.Contains($Unexpected)) { throw "assert-not-contains-failed: $Message" }
}

function Assert-Throws([scriptblock]$Action, [string]$ExpectedReason) {
    $thrown = $null
    try {
        & $Action
    } catch {
        $thrown = $_
    }
    if ($null -eq $thrown) { throw "assert-throws-failed: expected [$ExpectedReason]" }
    if ($thrown.Exception.Message -ne $ExpectedReason) { throw "assert-throws-failed: expected [$ExpectedReason]" }
}

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Module = Join-Path $PSScriptRoot 'modules\DesktopNotebookContextSnapshot.psm1'
Import-Module $Module -Force

$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('awx-context-' + [guid]::NewGuid().ToString('N'))
$outside = $null
New-Item -ItemType Directory -Path $TempRoot | Out-Null
try {
    $normalReturnRejected = $false
    try { Assert-Throws { $null } 'expected-test-error' } catch { $normalReturnRejected = $true }
    Assert-True $normalReturnRejected 'Assert-Throws rejects a normal-return action'

    $resolved = Resolve-AwxSnapshotOutputDirectory -Root $TempRoot -RelativePath 'data/agent-handoff/desktop-context'
    Assert-Equal $resolved.FullName (Join-Path $TempRoot 'data\agent-handoff\desktop-context')
    Assert-Throws { Resolve-AwxSnapshotOutputDirectory -Root $TempRoot -RelativePath '..\escape' } 'snapshot-output-outside-handoff'
    Assert-Throws { Resolve-AwxSnapshotOutputDirectory -Root $TempRoot -RelativePath '\\server\share' } 'snapshot-output-unc-forbidden'
    Assert-Throws { Resolve-AwxSnapshotOutputDirectory -Root $TempRoot -RelativePath 'data/agent-handoff/desktop-context:ads' } 'snapshot-output-ads-forbidden'

    $outside = Join-Path ([System.IO.Path]::GetTempPath()) ('awx-outside-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $outside | Out-Null
    $junction = Join-Path $TempRoot 'data\agent-handoff\junction'
    New-Item -ItemType Directory -Path (Split-Path -Parent $junction) -Force | Out-Null
    New-Item -ItemType Junction -Path $junction -Target $outside | Out-Null
    Assert-Throws { Resolve-AwxSnapshotOutputDirectory -Root $TempRoot -RelativePath 'data/agent-handoff/junction/desktop-context' } 'snapshot-output-reparse-forbidden'

    $unsafe = Test-AwxSnapshotText -Text 'password=visible-value'
    Assert-False $unsafe.Safe 'secret text must fail'

    $snapshot = [ordered]@{
        generatedAt = '2026-08-19T00:00:00+09:00'
        snapshotDecision = 'partial'
        java = [ordered]@{ status='ok'; javaHome='C:\jdk\jdk-17.0.13'; version='17.0.13'; dbUser='not-allowed' }
        build = [ordered]@{ status='ok'; gradleVersion='8.x'; rawStdout='not-allowed'; rootDependencySummary='not-allowed'; sourceSetSummary='not-allowed' }
        windows = [ordered]@{ status='ok'; os='Windows'; build='test' }
        hardware = [ordered]@{ status='ok'; cpu='fixture'; logicalProcessors=1; gpuProbeReason='not-allowed' }
        services = @([ordered]@{ port=8080; bindingClass='loopback'; ownerProcessName='fixture'; commandLine='not-allowed' })
        repository = [ordered]@{ status='ok'; branch='codex/fixture'; head='0000000'; dirtyCount=0; environmentDump='not-allowed' }
        smb = [ordered]@{ canonicalWorkspace='Y:\'; backingShareIdentityVerified=$false; backingShareIdentityReason='evidence-needed' }
        mariadb = [ordered]@{ decision='evidence_needed'; rawDbRowStored=$false; jdbcUrlStored=$false; username='not-allowed'; objectCounts=[ordered]@{ schema=2; table=3 } }
        agentDb = [ordered]@{ decision='not_observed'; rawError='not-allowed'; recentFailureReason='not-allowed'; recentFailureHash='not-allowed' }
        evidenceNeeded = @('db-query-timeout:TimeoutException')
    }
    $markdown = ConvertTo-AwxDesktopContextMarkdown -Snapshot $snapshot
    Assert-Contains $markdown '# Desktop Context Snapshot'
    foreach ($heading in @(
        '## Snapshot Status', '## Java and Build', '## Windows and Hardware', '## Local Runtime Services',
        '## Repository and SMB', '## MariaDB Metadata', '## Agent DB Context', '## Evidence Needed',
        '## Integrity and Refresh'
    )) { Assert-Contains $markdown $heading }
    Assert-Contains $markdown 'JAVA_HOME'
    Assert-Contains $markdown 'schema: 2'
    Assert-Contains $markdown 'Branch: codex/fixture'
    Assert-Contains $markdown 'db-query-timeout:TimeoutException'
    Assert-NotContains $markdown 'not-allowed' 'renderer leaked a disallowed field'

    $published = Publish-AwxDesktopContextSnapshot -Markdown $markdown -OutputDirectory $resolved
    Assert-True (Test-Path -LiteralPath $published.ReportPath) 'latest.md exists'
    Assert-Equal (Get-FileHash -Algorithm SHA256 -LiteralPath $published.ReportPath).Hash $published.Sha256
    $reportBytes = [System.IO.File]::ReadAllBytes($published.ReportPath)
    Assert-False ($reportBytes.Length -ge 3 -and $reportBytes[0] -eq 0xEF -and $reportBytes[1] -eq 0xBB -and $reportBytes[2] -eq 0xBF) 'report must be UTF-8 without BOM'
    $sidecarBytes = [System.IO.File]::ReadAllBytes($published.HashPath)
    Assert-Equal ([System.Text.UTF8Encoding]::new($false).GetString($sidecarBytes)) "$($published.Sha256)  latest.md`n"

    $previousMarkdown = [System.IO.File]::ReadAllText($published.ReportPath, [System.Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $published.HashPath -Force
    New-Item -ItemType Directory -Path $published.HashPath | Out-Null
    Assert-Throws { Publish-AwxDesktopContextSnapshot -Markdown ($markdown + "<!-- replacement -->`n") -OutputDirectory $resolved } 'snapshot-sidecar-publish-failed'
    Assert-Equal ([System.IO.File]::ReadAllText($published.ReportPath, [System.Text.UTF8Encoding]::new($false))) $previousMarkdown

    $profile = Join-Path $TempRoot 'main\resources\application-desktop-gpu-node.yml'
    New-Item -ItemType Directory -Path (Split-Path -Parent $profile) -Force | Out-Null
    [System.IO.File]::WriteAllText($profile, @'
spring:
  datasource:
    url: ${DESKTOP_DB_URL:jdbc:mysql://127.0.0.1:3306/fixture}
    username: ${DESKTOP_DB_USERNAME:fixture_user}
    password: ${DESKTOP_DB_PASSWORD:fixture_password}
    driver-class-name: ${DESKTOP_DB_DRIVER:com.mysql.cj.jdbc.Driver}
'@, [System.Text.UTF8Encoding]::new($false))

    $settings = Resolve-AwxDesktopDbSettings -RepoRoot $TempRoot -ProfileConfig $profile
    Assert-True $settings.Resolved 'fallback settings resolve'
    Assert-Equal $settings.Reason 'ok'
    Assert-Equal $settings.ToString() '[desktop-db-settings:redacted]'
    Assert-False (($settings | Select-Object * | ConvertTo-Json -Compress) -match 'fixture_password|jdbc:mysql') 'hidden settings do not serialize secrets'
    $moduleInfo = Get-Module DesktopNotebookContextSnapshot
    $fallbackValuesMatch = & $moduleInfo {
        param($Value)
        return ($Value.Url -eq 'jdbc:mysql://127.0.0.1:3306/fixture' -and
            $Value.Username -eq 'fixture_user' -and
            $Value.Password -eq 'fixture_password' -and
            $Value.Driver -eq 'com.mysql.cj.jdbc.Driver')
    } $settings
    Assert-True $fallbackValuesMatch 'profile fallbacks are parsed from direct datasource children'

    $savedDbEnvironment = @{}
    foreach ($name in @('DESKTOP_DB_URL', 'DESKTOP_DB_USERNAME', 'DESKTOP_DB_PASSWORD', 'DESKTOP_DB_DRIVER')) {
        $savedDbEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    try {
        [Environment]::SetEnvironmentVariable('DESKTOP_DB_URL', 'jdbc:mysql://127.0.0.1:3306/environment_fixture', 'Process')
        [Environment]::SetEnvironmentVariable('DESKTOP_DB_USERNAME', 'environment_user', 'Process')
        [Environment]::SetEnvironmentVariable('DESKTOP_DB_PASSWORD', 'environment_password', 'Process')
        [Environment]::SetEnvironmentVariable('DESKTOP_DB_DRIVER', 'fixture.EnvironmentDriver', 'Process')
        $environmentSettings = Resolve-AwxDesktopDbSettings -RepoRoot $TempRoot -ProfileConfig $profile
        $environmentValuesMatch = & $moduleInfo {
            param($Value)
            return ($Value.Url -eq 'jdbc:mysql://127.0.0.1:3306/environment_fixture' -and
                $Value.Username -eq 'environment_user' -and
                $Value.Password -eq 'environment_password' -and
                $Value.Driver -eq 'fixture.EnvironmentDriver')
        } $environmentSettings
        Assert-True $environmentValuesMatch 'process environment takes precedence over profile fallback'
        Assert-False (($environmentSettings | Select-Object * | ConvertTo-Json -Compress) -match 'environment_password|jdbc:mysql') 'environment settings stay hidden'
    } finally {
        foreach ($name in $savedDbEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $savedDbEnvironment[$name], 'Process')
        }
    }

    $outsideProfile = Join-Path $TempRoot 'application-outside.yml'
    [System.IO.File]::WriteAllText($outsideProfile, 'spring: {}', [System.Text.UTF8Encoding]::new($false))
    Assert-Throws { Resolve-AwxDesktopDbSettings -RepoRoot $TempRoot -ProfileConfig $outsideProfile } 'profile-config-outside-main-resources'

    $nestedProfile = Join-Path $TempRoot 'main\resources\application-nested.yml'
    [System.IO.File]::WriteAllText($nestedProfile, @'
spring:
  datasource:
    url: ${DESKTOP_DB_URL:${NESTED_VALUE}}
    username: ${DESKTOP_DB_USERNAME:fixture_user}
    password: ${DESKTOP_DB_PASSWORD:fixture_password}
    driver-class-name: ${DESKTOP_DB_DRIVER:com.mysql.cj.jdbc.Driver}
'@, [System.Text.UTF8Encoding]::new($false))
    $nestedSettings = Resolve-AwxDesktopDbSettings -RepoRoot $TempRoot -ProfileConfig $nestedProfile
    Assert-False $nestedSettings.Resolved 'nested placeholders are rejected'
    Assert-Equal $nestedSettings.Reason 'db-credentials-unresolved'

    foreach ($probeName in @(
        'Get-AwxJavaFacts', 'Get-AwxWindowsFacts', 'Get-AwxHardwareFacts', 'Get-AwxServiceFacts',
        'Get-AwxRepositoryFacts', 'Get-AwxSmbFacts', 'Get-AwxOllamaFacts', 'Get-AwxAgentDbFacts'
    )) {
        Assert-True ($null -ne (Get-Command -Name $probeName -ErrorAction SilentlyContinue)) "probe exported: $probeName"
    }
    $serviceFacts = @(Get-AwxServiceFacts -TimeoutSeconds 2)
    Assert-Equal (($serviceFacts | ForEach-Object { $_.port }) -join ',') '3306,8080,8081,11434,11435,11436'
    foreach ($serviceFact in $serviceFacts) {
        Assert-False (($serviceFact | ConvertTo-Json -Compress) -match '(?i)commandLine|localAddress|remoteAddress') 'service facts expose allowlisted fields only'
    }

    $agentDbInvokerState = [pscustomobject]@{ Count = 0 }
    $loadedAgentDb = Get-AwxAgentDbFacts -RepoRoot $TempRoot -TimeoutSeconds 2 -ListeningPortsOverride @(8080) -AgentDbInvoker {
        param($Payload)
        $agentDbInvokerState.Count += 1
        return [ordered]@{
            ok = $true
            endpoint = 'snapshot'
            decision = 'agent_db_snapshot_loaded'
            snapshot = [ordered]@{
                memoryStatusCount = 3
                recentFailureReason = 'agent-db-runtime-unavailable'
                recentFailureHash = 'ABCDEF123456'
                strategyAggregateCount = 2
            }
            tokenPresented = $false
            rawStdout = 'not-allowed'
        }
    }.GetNewClosure()
    Assert-Equal $agentDbInvokerState.Count 1
    Assert-Equal $loadedAgentDb.status 'ok'
    Assert-Equal $loadedAgentDb.decision 'agent_db_snapshot_loaded'
    Assert-Equal $loadedAgentDb.memoryStatusCount 3
    Assert-Equal $loadedAgentDb.recentFailureReason 'agent-db-runtime-unavailable'
    Assert-Equal $loadedAgentDb.recentFailureHash 'ABCDEF123456'
    Assert-Equal $loadedAgentDb.strategyAggregateCount 2
    Assert-False (($loadedAgentDb | ConvertTo-Json -Compress) -match '(?i)tokenPresented|rawStdout|endpoint') 'agent db facts expose allowlisted fields only'

    $fallbackAgentDb = Get-AwxAgentDbFacts -RepoRoot $TempRoot -TimeoutSeconds 2 -ListeningPortsOverride @(8081) -AgentDbInvoker {
        param($Payload)
        return [ordered]@{
            ok = $false
            decision = 'agent_db_snapshot_unavailable_with_local_fallback'
            failReason = 'URLError'
            localFallback = [ordered]@{
                failurePatternMemory = [ordered]@{
                    rowCountScanned = 4
                    lastRow = [ordered]@{
                        failureClass = 'agent-db-runtime-unavailable'
                        evidenceHash12 = '1234ABCDEF56'
                    }
                }
                subsystemPersistence = [ordered]@{
                    strategy = [ordered]@{ entityCount = 2 }
                }
            }
        }
    }
    Assert-Equal $fallbackAgentDb.status 'unavailable'
    Assert-Equal $fallbackAgentDb.decision 'agent-db-runtime-unavailable'
    Assert-Equal $fallbackAgentDb.memoryStatusCount 4
    Assert-Equal $fallbackAgentDb.recentFailureReason 'agent-db-runtime-unavailable'
    Assert-Equal $fallbackAgentDb.recentFailureHash '1234ABCDEF56'
    Assert-Equal $fallbackAgentDb.strategyAggregateCount 2

    $injectedProbes = [ordered]@{
        java = [ordered]@{ status='ok'; javaHome='C:\jdk\fixture-17'; 'java.home'='C:\jdk\fixture-17'; version='17.0.13'; vendor='fixture'; executableAvailable=$true }
        build = [ordered]@{ status='ok'; gradleWrapperVersion='8.10'; rootDependencyCount=1; sourceSetCount=4 }
        windows = [ordered]@{ status='ok'; osCaption='Windows fixture'; build='19045'; powerShellVersion='5.1' }
        hardware = [ordered]@{ status='ok'; cpu='fixture-cpu'; logicalProcessors=8; physicalMemoryBytes=17179869184; gpuName='fixture-gpu'; gpuDriverVersion='1.0'; gpuTotalMemoryBytes=12884901888; gpuUsedMemoryBytes=0; drives=@() }
        services = @(
            [ordered]@{ port=3306; bindingClass='loopback'; ownerProcessName='mysqld'; status='ok' },
            [ordered]@{ port=8080; bindingClass='unavailable'; ownerProcessName=''; status='unavailable' }
        )
        repository = [ordered]@{ status='ok'; canonicalRoot=$TempRoot; branch='codex/fixture'; head='0000000'; dirtyCount=0; indexLock=$false; sourceLeaseCount=0; patchDropTopLevelCount=0; patchDropPendingCount=0; activeSourceSetCount=4 }
        smb = [ordered]@{ canonicalWorkspace='Y:\'; backingShareIdentityVerified=$true; backingShareIdentityReason='match' }
        ollama = [ordered]@{ status='ok'; endpointStatus='ok'; modelCount=2; modelIdentifier='qwen3:latest'; port=11435; bindingClass='loopback'; ownerProcessName='ollama' }
        agentDb = $loadedAgentDb
    }
    $orchestrated = Invoke-AwxDesktopNotebookContextSnapshot `
        -RepoRoot $TempRoot `
        -OutputDirectory 'data/agent-handoff/desktop-context-orchestrator' `
        -DatabaseMode Skip `
        -ProfileConfig $profile `
        -DatabaseTimeoutSeconds 1 `
        -MaxMetadataRows 25 `
        -ProbeResults $injectedProbes
    Assert-Equal $orchestrated.Decision 'partial'
    Assert-Equal $orchestrated.MariaDbDecision 'not_observed'
    Assert-Equal $orchestrated.EvidenceNeededCount 1
    Assert-Equal $orchestrated.ReportRelativePath 'data/agent-handoff/desktop-context-orchestrator/latest.md'
    Assert-True (Test-Path -LiteralPath (Join-Path $TempRoot $orchestrated.ReportRelativePath)) 'injected orchestrator publishes latest.md'
    Assert-False (($orchestrated | Select-Object * | ConvertTo-Json -Compress) -match 'fixture_password|fixture_user|jdbc:mysql') 'orchestrator summary contains no DB settings'
    $orchestratedMarkdown = [System.IO.File]::ReadAllText((Join-Path $TempRoot $orchestrated.ReportRelativePath), [System.Text.UTF8Encoding]::new($false))
    Assert-Contains $orchestratedMarkdown '## Snapshot Status'
    Assert-Contains $orchestratedMarkdown '## MariaDB Metadata'
    Assert-Contains $orchestratedMarkdown 'db-metadata-skipped'
    Assert-Contains $orchestratedMarkdown 'Endpoint status: ok'
    Assert-Contains $orchestratedMarkdown 'Model count: 2'
    Assert-Contains $orchestratedMarkdown 'Model identifier: qwen3:latest'
    Assert-Contains $orchestratedMarkdown 'Branch: codex/fixture'
    Assert-Contains $orchestratedMarkdown 'Memory status count: 3'

    $initScriptText = & $moduleInfo { Get-AwxMysqlConnectorInitScriptText }
    foreach ($requiredInitToken in @('awxPrintMysqlConnectorPath', 'runtimeClasspath', 'com.mysql', 'mysql-connector-j')) {
        Assert-Contains $initScriptText $requiredInitToken
    }

    $Wrapper = Join-Path $PSScriptRoot 'desktop_notebook_context_snapshot.ps1'
    Assert-True (Test-Path -LiteralPath $Wrapper) 'thin wrapper exists'
    $wrapperParameters = @((Get-Command -Name $Wrapper).Parameters.Keys | Sort-Object)
    Assert-Equal ($wrapperParameters -join ',') 'DatabaseMode,DatabaseTimeoutSeconds,MaxMetadataRows,OutputDirectory,ProfileConfig'

    $handoffReadme = Join-Path $RepoRoot 'data\agent-handoff\desktop-context\README.md'
    $handoffIgnore = Join-Path $RepoRoot 'data\agent-handoff\desktop-context\.gitignore'
    Assert-True (Test-Path -LiteralPath $handoffReadme -PathType Leaf) 'handoff README exists'
    Assert-True (Test-Path -LiteralPath $handoffIgnore -PathType Leaf) 'handoff .gitignore exists'
    $readmeText = Get-Content -LiteralPath $handoffReadme -Raw
    Assert-Contains $readmeText "Set-Location -LiteralPath 'C:\AbandonWare\demo-1\demo-1\src'"
    Assert-Contains $readmeText "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_notebook_context_snapshot.ps1"
    Assert-Contains $readmeText "`$Snapshot = 'Y:\data\agent-handoff\desktop-context\latest.md'"
    Assert-Contains $readmeText 'Get-Content -LiteralPath $Snapshot -Raw'
    Assert-Contains $readmeText 'Get-FileHash -Algorithm SHA256 -LiteralPath $Snapshot'
    Assert-Contains $readmeText '30 minutes'
    Assert-Contains $readmeText 'must not write a refresh request or source file'
    $gitignoreText = ((Get-Content -LiteralPath $handoffIgnore -Raw) -replace "`r`n", "`n")
    Assert-Equal $gitignoreText "*`n!.gitignore`n!README.md`n"

    $latestRelative = 'data/agent-handoff/desktop-context/latest.md'
    $sidecarRelative = 'data/agent-handoff/desktop-context/latest.sha256.txt'
    $readmeRelative = 'data/agent-handoff/desktop-context/README.md'
    $latestIgnored = @(& git -C $RepoRoot check-ignore -v -- $latestRelative 2>$null)
    $sidecarIgnored = @(& git -C $RepoRoot check-ignore -v -- $sidecarRelative 2>$null)
    $readmeIgnored = @(& git -C $RepoRoot check-ignore -v -- $readmeRelative 2>$null)
    Assert-Contains ($latestIgnored -join "`n") 'data/agent-handoff/desktop-context/.gitignore'
    Assert-Contains ($latestIgnored -join "`n") $latestRelative
    Assert-Contains ($sidecarIgnored -join "`n") 'data/agent-handoff/desktop-context/.gitignore'
    Assert-Contains ($sidecarIgnored -join "`n") $sidecarRelative
    Assert-Equal $readmeIgnored.Count 0

    $blockingSourceRoot = Join-Path $TempRoot 'blocking-driver'
    $blockingPackage = Join-Path $blockingSourceRoot 'fixture'
    $blockingClasses = Join-Path $TempRoot 'blocking-driver-classes'
    New-Item -ItemType Directory -Path $blockingPackage,$blockingClasses -Force | Out-Null
    $blockingSource = Join-Path $blockingPackage 'BlockingDriver.java'
    [System.IO.File]::WriteAllText($blockingSource, @'
package fixture;
public final class BlockingDriver implements java.sql.Driver {
  static {
    for (int i = 0; i < 12000; i++) {
      System.out.print("stdout-fixture-line\\n");
      System.err.print("stderr-fixture-line\\n");
    }
    try { Thread.sleep(60000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
  }
  public java.sql.Connection connect(String url, java.util.Properties info) { return null; }
  public boolean acceptsURL(String url) { return false; }
  public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) { return new java.sql.DriverPropertyInfo[0]; }
  public int getMajorVersion() { return 1; }
  public int getMinorVersion() { return 0; }
  public boolean jdbcCompliant() { return false; }
  public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
}
'@, [System.Text.UTF8Encoding]::new($false))
    & javac -d $blockingClasses $blockingSource
    Assert-Equal $LASTEXITCODE 0 'blocking fixture javac exit'
    $blockingJar = Join-Path $TempRoot 'mysql-connector-j-timeout-fixture.jar'
    & jar --create --file $blockingJar -C $blockingClasses .
    Assert-Equal $LASTEXITCODE 0 'blocking fixture jar exit'
    $blockingProfile = Join-Path $TempRoot 'main\resources\application-blocking.yml'
    [System.IO.File]::WriteAllText($blockingProfile, @'
spring:
  datasource:
    url: ${DESKTOP_DB_URL:jdbc:mysql://127.0.0.1:3306/outer_deadline_fixture}
    username: ${DESKTOP_DB_USERNAME:outer_deadline_user}
    password: ${DESKTOP_DB_PASSWORD:outer_deadline_password}
    driver-class-name: ${DESKTOP_DB_DRIVER:fixture.BlockingDriver}
'@, [System.Text.UTF8Encoding]::new($false))
    $blockingSettings = Resolve-AwxDesktopDbSettings -RepoRoot $TempRoot -ProfileConfig $blockingProfile
    $deadlineWatch = [Diagnostics.Stopwatch]::StartNew()
    $deadlineResult = Invoke-AwxMariaDbMetadataHelper -Settings $blockingSettings -ConnectorJar $blockingJar -TimeoutSeconds 1 -MaxRows 10
    $deadlineWatch.Stop()
    Assert-Equal $deadlineResult.decision 'query_failure'
    Assert-Equal $deadlineResult.evidenceNeeded[0] 'db-query-timeout:TimeoutException'
    Assert-True $deadlineResult.childProcessExited 'timed-out helper child exited after exact-process kill'
    Assert-True ($deadlineWatch.Elapsed.TotalSeconds -lt 4.5) 'outer deadline stays below timeout plus bounded cleanup allowance'
    Assert-False (($deadlineResult | ConvertTo-Json -Depth 8 -Compress) -match 'outer_deadline_password|outer_deadline_user|jdbc:mysql|stdout-fixture|stderr-fixture') 'deadline result is bounded and redacted'

    $JavaSource = Join-Path $PSScriptRoot 'DesktopMariaDbMetadataSnapshot.java'
    Assert-True (Test-Path -LiteralPath $JavaSource) 'Java helper exists'
    $sourceText = Get-Content -LiteralPath $JavaSource -Raw
    foreach ($forbidden in @('executeUpdate(', 'addBatch(', 'prepareCall(', 'INTO OUTFILE', 'LOAD DATA')) {
        Assert-False $sourceText.Contains($forbidden) "forbidden helper token: $forbidden"
    }
    $selfTestRaw = & java $JavaSource --self-test 2>&1
    Assert-Equal $LASTEXITCODE 0 'Java self-test exit'
    $selfTest = $selfTestRaw | ConvertFrom-Json
    Assert-Equal $selfTest.schemaVersion 'awx.desktop.mariadb-metadata.v1'
    Assert-Equal $selfTest.decision 'self_test_passed'
    Assert-True $selfTest.readOnlySession 'self-test read-only flag'
    Assert-False $selfTest.rawDbRowStored 'self-test raw row flag'
    Assert-False $selfTest.jdbcUrlStored 'self-test JDBC URL flag'
    Assert-Equal $selfTest.summary.fingerprintContract.stableAcrossVolatileEstimates $true
    Assert-Equal $selfTest.summary.fingerprintContract.changesForTableSet $true
    Assert-Equal $selfTest.summary.fingerprintContract.changesForColumnType $true
    Assert-Equal $selfTest.summary.fingerprintContract.changesForColumnOrdinal $true
    Assert-Equal $selfTest.summary.fingerprintContract.changesForColumnNullability $true
    Assert-Equal $selfTest.summary.fingerprintContract.changesForIndexColumn $true
    Assert-Equal $selfTest.summary.fingerprintContract.changesForIndexOrder $true
    Assert-Equal $selfTest.summary.fingerprintContract.changesForIndexUniqueness $true
    Assert-Equal $selfTest.summary.fingerprintContract.changesForViewSet $true
    Assert-Equal $selfTest.summary.systemCatalogContract.rejectedCatalogCount 4
    Assert-Equal $selfTest.summary.systemCatalogContract.mixedCaseRejected $true
    Assert-Equal $selfTest.summary.timeoutContract.networkTimeoutMillis 10000
    Assert-Equal $selfTest.summary.timeoutContract.connectTimeoutMillis 10000
    Assert-Equal $selfTest.summary.timeoutContract.socketTimeoutMillis 10000
    Assert-Equal $selfTest.summary.timeoutContract.childProcessDeadlineSeconds 15
    Assert-Equal $selfTest.summary.rowCapContract.maximum 2
    Assert-Equal $selfTest.summary.rowCapContract.acceptedRows 2
    Assert-Equal $selfTest.summary.rowCapContract.limitRaisedAtCap $true
    Assert-Equal $selfTest.summary.rowCapContract.reasonCode 'metadata-row-limit-reached'
    Write-Output 'PASS: desktop notebook context snapshot contract'
} finally {
    Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
    if ($outside) {
        Remove-Item -LiteralPath $outside -Recurse -Force -ErrorAction SilentlyContinue
    }
}
