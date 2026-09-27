param(
    [string]$OutputDirectory = 'data/agent-handoff/desktop-notebook-pack',
    [ValidateSet('Metadata', 'Skip')][string]$DatabaseMode = 'Metadata',
    [ValidateRange(1, 120)][int]$DatabaseTimeoutSeconds = 5,
    [ValidateRange(1, 10000)][int]$MaxMetadataRows = 10000,
    [string]$BaseUrl = '',
    [ValidateRange(1, 65535)][int]$HttpPort = 8080,
    [ValidateRange(1, 120)][int]$HttpTimeoutSeconds = 20,
    [ValidateRange(0, 120)][int]$PingPongMinutes = 10,
    [ValidateRange(0, 24)][int]$PingPongHours = 0,
    [ValidateRange(1, 300)][int]$PingIntervalSeconds = 60,
    [ValidateRange(1, 400)][int]$PingPongRounds = 1
)

$ErrorActionPreference = 'Stop'
$runStartTime = Get-Date

function Get-AwxSha256Prefix {
    param([Parameter(Mandatory)][string]$Text)

    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return [System.BitConverter]::ToString($hash).Replace('-', '').Substring(0, 16)
}

function ConvertTo-ValueForHandoff {
    param(
        [Parameter(Mandatory)][string]$Name,
        [AllowNull()]$Value
    )

    if ($null -eq $Value) { return '<null>' }
    $raw = [string]$Value
    if ([string]::IsNullOrWhiteSpace($raw)) { return '<empty>' }

    $sensitive = $Name -match '(?i)(api.?key|private|secret|password|token|auth|credential|bearer|cookie|conn(ection)?string|jdbc|db.*pass|db.*user|access.?key|refresh|oauth|(?:^|_)keys?$|client.?id)'
    if ($sensitive) {
        return ('<redacted len={0} sha256={1}>' -f $raw.Length, (Get-AwxSha256Prefix -Text $raw))
    }
    return $raw
}

function Get-RelPath {
    param(
        [Parameter(Mandatory)][string]$BasePath,
        [Parameter(Mandatory)][string]$FullPath
    )

    $base = (Resolve-Path $BasePath).Path.TrimEnd('\')
    $candidate = (Resolve-Path $FullPath).Path
    if ($candidate.Length -le $base.Length) { return $candidate.Replace('\', '/') }
    return $candidate.Substring($base.Length + 1).Replace('\', '/')
}

function Get-FileRecord {
    param(
        [Parameter(Mandatory)][string]$BasePath,
        [Parameter(Mandatory)][string]$Path
    )
    if (-not (Test-Path -LiteralPath $Path)) { return $null }

    $item = Get-Item -LiteralPath $Path
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    return ('{0}|size={1}|sha256={2}|mtime_utc={3}' -f (Get-RelPath -BasePath $BasePath -FullPath $Path), $item.Length, $hash, $item.LastWriteTimeUtc.ToString('o'))
}

function Write-TextOrFailure {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string]$Content
    )

    Set-Content -LiteralPath $Path -Value $Content -Encoding UTF8
    Write-Host ("{0}: {1}" -f $Label, $Path)
}

function Get-PacketCollectedFiles {
    param([Parameter(Mandatory)][string]$PackRoot)

    if (-not (Test-Path -LiteralPath $PackRoot)) { return @() }
    $resolved = (Resolve-Path $PackRoot).Path.TrimEnd('\')
    return Get-ChildItem -Path $PackRoot -Recurse -File |
        Sort-Object FullName |
        ForEach-Object {
            $full = $_.FullName
            if ($full.Length -le $resolved.Length) {
                return $_.Name
            }
            $rel = $full.Substring($resolved.Length + 1).Replace('\', '/')
            return $rel
        }
}

function Format-PreviewText {
    param(
        [Parameter(Mandatory)][AllowNull()]$Text,
        [int]$MaxLength = 180
    )

    if ($null -eq $Text) { return '<none>' }
    $value = [string]$Text
    if ([string]::IsNullOrWhiteSpace($value)) { return '<empty>' }
    if ($value.Length -le $MaxLength) { return $value }
    return ($value.Substring(0, [Math]::Max(1, $MaxLength)) + ' ...')
}

function Get-EndpointProbeSuccessStatus {
    param(
        [Parameter(Mandatory)][AllowNull()]$ProbeStatus
    )

    $value = if ($null -eq $ProbeStatus) { '' } else { [string]$ProbeStatus.ToLowerInvariant() }
    return @('ok', 'empty_valid') -contains $value
}

function Get-EndpointProbeStatusFromHttp {
    param(
        [Parameter(Mandatory)][int]$HttpStatus,
        [Parameter(Mandatory)][bool]$BodyLooksEmpty
    )

    if ($HttpStatus -eq 404) { return 'route_missing_404' }
    if ($HttpStatus -eq 401) { return 'unauthorized_401' }
    if ($HttpStatus -eq 403) { return 'forbidden_403' }
    if ($HttpStatus -eq 0) { return 'server_unreachable' }
    if ($HttpStatus -eq 204 -or $BodyLooksEmpty) {
        if ($HttpStatus -ge 200 -and $HttpStatus -lt 300) { return 'empty_valid' }
    }
    if ($HttpStatus -ge 200 -and $HttpStatus -lt 300) { return 'ok' }
    if ($HttpStatus -ge 500 -and $HttpStatus -le 599) { return 'server_error_5xx' }
    if ($HttpStatus -ge 400 -and $HttpStatus -le 499) { return 'evidence_needed' }

    return 'evidence_needed'
}

function Get-EndpointProbeFailureStatus {
    param(
        [Parameter(Mandatory)]$ErrorRecord
    )

    $message = if ($null -eq $ErrorRecord.Exception.Message) { '' } else { [string]$ErrorRecord.Exception.Message }
    $messageLower = $message.ToLowerInvariant()
    $httpStatus = 0

    if ($ErrorRecord.Exception -is [System.Net.WebException] -and $ErrorRecord.Exception.Response) {
        try {
            $httpStatus = [int]$ErrorRecord.Exception.Response.StatusCode
        }
        catch {
            $httpStatus = 0
        }
    }

    if ($httpStatus -eq 404) { return 'route_missing_404' }
    if ($httpStatus -eq 401) { return 'unauthorized_401' }
    if ($httpStatus -eq 403) { return 'forbidden_403' }
    if ($httpStatus -ge 500 -and $httpStatus -le 599) { return 'server_error_5xx' }

    if ($messageLower -match 'timed out|timeout') { return 'timeout' }
    if ($messageLower -match 'actively refused|connection refused') { return 'connection_refused' }
    if ($messageLower -match 'could not resolve|name resolution|name or service not known|a connection attempt failed|unable to connect') { return 'server_unreachable' }
    if ($messageLower -match 'communicationslinkfailure|communications link failure|database.*error|db.*error|could not connect to .*database') { return 'db_unreachable' }
    if ($messageLower -match 'access denied|authentication failed|password') { return 'db_auth_failure' }
    if ($messageLower -match 'sql.*syntax|query failed|invalid.*query') { return 'db_query_failure' }

    return 'evidence_needed'
}

function Convert-FeedbackRecordFromLines {
    param(
        [Parameter(Mandatory)][string[]]$Lines,
        [string]$SourceFile = ''
    )

    if ($Lines.Count -eq 0) { return $null }
    $record = [ordered]@{
        SourceFile = $SourceFile
    }

    foreach ($line in $Lines) {
        if ($line -match '^\s*([A-Za-z0-9_.-]+)\s*[:=]\s*(.*)$') {
            $record[$matches[1]] = $matches[2].Trim()
        }
    }

    if ($record.Count -le 1) { return $null }
    return [pscustomobject]$record
}

function Get-FeedbackRecordsFromPackFiles {
    param([Parameter(Mandatory)][string]$Path)

    $records = @()
    if (-not (Test-Path -LiteralPath $Path)) { return $records }

    $jsonlPath = Join-Path $Path 'feedback-queue.jsonl'
    if (Test-Path -LiteralPath $jsonlPath) {
        try {
            foreach ($line in Get-Content -LiteralPath $jsonlPath) {
                $trimmed = $line.Trim()
                if ([string]::IsNullOrWhiteSpace($trimmed)) { continue }
                try {
                    $record = $trimmed | ConvertFrom-Json -ErrorAction Stop
                    if ($record) { $records += $record }
                }
                catch {
                }
            }
        }
        catch {
        }
    }

    $feedbackFiles = Get-ChildItem -Path $Path -Recurse -File -Include *.txt, *.md -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notlike '*\feedback-queue.jsonl' -and $_.FullName -notlike '*\feedback-queue.md' }
    foreach ($file in $feedbackFiles) {
        $hasFeedback = Select-String -Path $file.FullName -Pattern '^\s*#\s*FEEDBACK\b' -Quiet -ErrorAction SilentlyContinue
        if (-not $hasFeedback) { continue }

        $lines = Get-Content -LiteralPath $file.FullName -ErrorAction SilentlyContinue
        $feedbackMode = $false
        $block = @()
        foreach ($line in $lines) {
            if ($line -match '^\s*#\s*FEEDBACK\b') {
                if ($feedbackMode -and $block.Count -gt 0) {
                    $feedbackRecord = Convert-FeedbackRecordFromLines -Lines $block -SourceFile $file.FullName
                    if ($feedbackRecord) { $records += $feedbackRecord }
                }
                $feedbackMode = $true
                $block = @()
                continue
            }

            if (-not $feedbackMode) { continue }
            if ($line -match '^\s*#\s' -and -not ($line -match '^\s*#\s*FEEDBACK\b')) {
                if ($block.Count -gt 0) {
                    $feedbackRecord = Convert-FeedbackRecordFromLines -Lines $block -SourceFile $file.FullName
                    if ($feedbackRecord) { $records += $feedbackRecord }
                }
                $feedbackMode = $false
                $block = @()
                continue
            }

            if (-not [string]::IsNullOrWhiteSpace($line)) {
                $block += $line
            }
        }
        if ($feedbackMode -and $block.Count -gt 0) {
            $feedbackRecord = Convert-FeedbackRecordFromLines -Lines $block -SourceFile $file.FullName
            if ($feedbackRecord) { $records += $feedbackRecord }
        }
    }

    return $records
}

function Get-FeedbackRecordId {
    param([Parameter(Mandatory)]$Record)

    if ($null -eq $Record) { return $null }
    if ($Record.feedback_id) { return ('feedback_id::{0}' -f [string]$Record.feedback_id) }
    if ($Record.source_row_id) { return ('source_row_id::{0}' -f [string]$Record.source_row_id) }

    $source = if ($Record.SourceFile) { [string]$Record.SourceFile } else { '' }
    $owner = if ($Record.owner) { [string]$Record.owner } else { '' }
    $file = if ($Record.file) { [string]$Record.file } else { '' }
    $note = if ($Record.note) { [string]$Record.note } else { '' }
    $action = if ($Record.action) { [string]$Record.action } else { '' }
    return ('fallback::{0}' -f (Get-AwxSha256Prefix -Text (('{0}|{1}|{2}|{3}|{4}' -f $source, $owner, $file, $note, $action)))
}

function Get-FeedbackDateValue {
    param([Parameter(Mandatory)]$Record, [Parameter(Mandatory)][string]$Field)

    if (-not $Record.$Field) { return $null }
    try {
        return [DateTime]::Parse([string]$Record.$Field)
    }
    catch {
        return $null
    }
}

function Get-FeedbackStatusWeight {
    param([string]$Status)

    $normalized = if ([string]::IsNullOrWhiteSpace($Status)) { '' } else { $Status.ToLowerInvariant() }
    switch ($normalized) {
        'done' { return 100 }
        'blocked' { return 50 }
        'todo' { return 10 }
        default { return 0 }
    }
}

function Merge-FeedbackRecords {
    param(
        [Parameter(Mandatory)]$ExistingRecords,
        [Parameter(Mandatory)]$IncomingRecords
    )

    $merged = [ordered]@{}
    $conflicts = @()

    $allRecords = @($ExistingRecords) + @($IncomingRecords)
    foreach ($record in $allRecords) {
        if ($null -eq $record) { continue }
        $key = Get-FeedbackRecordId -Record $record
        if ([string]::IsNullOrWhiteSpace($key)) { continue }

        if (-not $merged.ContainsKey($key)) {
            $merged[$key] = $record
            continue
        }

        $current = $merged[$key]
        $currentStatus = if ($current.status) { [string]$current.status } else { '' }
        $incomingStatus = if ($record.status) { [string]$record.status } else { '' }
        $currentUpdated = Get-FeedbackDateValue -Record $current -Field 'acknowledged_at'
        if (-not $currentUpdated) { $currentUpdated = Get-FeedbackDateValue -Record $current -Field 'updated_at' }
        $incomingUpdated = Get-FeedbackDateValue -Record $record -Field 'acknowledged_at'
        if (-not $incomingUpdated) { $incomingUpdated = Get-FeedbackDateValue -Record $record -Field 'updated_at' }

        $shouldReplace = $false
        if ($incomingUpdated -and $currentUpdated -and $incomingUpdated -gt $currentUpdated) { $shouldReplace = $true }
        if (-not $shouldReplace -and (Get-FeedbackStatusWeight -Status $incomingStatus) -gt (Get-FeedbackStatusWeight -Status $currentStatus)) { $shouldReplace = $true }

        if ($shouldReplace) {
            $merged[$key] = $record
            continue
        }

        $conflict = [ordered]@{
            feedback_id = $key
            current_status = $currentStatus
            incoming_status = $incomingStatus
            current_file = (if ($current.SourceFile) { [string]$current.SourceFile } else { 'unknown' })
            incoming_file = (if ($record.SourceFile) { [string]$record.SourceFile } else { 'unknown' })
        }
        $conflicts += [pscustomobject]$conflict
    }

    return [pscustomobject]@{
        Records = @($merged.Values)
        Conflicts = $conflicts
    }
}

function Invoke-DesktopContextSnapshot {
    param([string]$OutputDirectoryRelative)

    Write-Host "[PACK] run desktop_notebook_context_snapshot.ps1"
    $result = & "$PSScriptRoot\desktop_notebook_context_snapshot.ps1" `
        -OutputDirectory $OutputDirectoryRelative `
        -DatabaseMode $DatabaseMode `
        -DatabaseTimeoutSeconds $DatabaseTimeoutSeconds `
        -MaxMetadataRows $MaxMetadataRows
    return [string]$result
}

function Parse-DesktopSnapshotResult {
    param([Parameter(Mandatory)][string]$ResultText)

    $out = [ordered]@{
        decision = 'unknown'
        mariadb = 'unknown'
        reportRelativePath = 'data/agent-handoff/desktop-context/latest.md'
        sha256 = 'missing'
        evidenceNeededCount = 'unknown'
    }

    if ($ResultText -match 'decision=([^\s]+)') { $out.decision = $matches[1] }
    if ($ResultText -match 'mariadb=([^\s]+)') { $out.mariadb = $matches[1] }
    if ($ResultText -match 'report=([^\s]+)') { $out.reportRelativePath = $matches[1] }
    if ($ResultText -match 'sha256=([A-F0-9]{64})') { $out.sha256 = $matches[1] }
    if ($ResultText -match 'evidenceNeededCount=(\d+)') { $out.evidenceNeededCount = $matches[1] }

    return [pscustomobject]$out
}

function Invoke-EndpointProbe {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$Route,
        [Parameter(Mandatory)][string]$OutputPath,
        [Parameter(Mandatory)][int]$TimeoutSeconds,
        [Parameter(Mandatory)][int]$Round,
        [string]$Group = 'unknown'
    )

    try {
        $response = Invoke-WebRequest -Uri ($BaseUrl + $Route) -UseBasicParsing -TimeoutSec $TimeoutSeconds
        $status = [int]$response.StatusCode
        $bodyText = [string]$response.Content
        $bodyPreview = if ($bodyText.Length -gt 120000) { $bodyText.Substring(0, 120000) } else { $bodyText }
        $bodyLooksEmpty = [string]::IsNullOrWhiteSpace($bodyText)
        $probeStatus = Get-EndpointProbeStatusFromHttp -HttpStatus $status -BodyLooksEmpty $bodyLooksEmpty
        $outputStatusLine = "status={0} status_code={1}" -f $probeStatus, $status
        if ($response.Headers.'Content-Type' -match 'application/json') {
            try {
                $payload = $response.Content | ConvertFrom-Json -ErrorAction Stop
                if ($null -eq $payload) {
                    $probeStatus = Get-EndpointProbeStatusFromHttp -HttpStatus $status -BodyLooksEmpty $true
                } elseif ($payload -is [System.Collections.ICollection] -and $payload.Count -eq 0) {
                    $probeStatus = 'empty_valid'
                }
                $output = @(
                    $outputStatusLine
                    '---- BEGIN JSON ----'
                    ($payload | ConvertTo-Json -Depth 20)
                    '---- END JSON ----'
                )
                Write-TextOrFailure -Path $OutputPath -Label ("[OK] {0}" -f $Route) -Content ($output -join "`r`n")
            } catch {
                $probeStatus = 'parse_error'
                $output = @(
                    "status=$probeStatus",
                    ('status_code={0}' -f $status),
                    ('parse_error={0}' -f $_.Exception.Message),
                    $response.Content
                )
                Write-TextOrFailure -Path $OutputPath -Label ("[FAIL] {0}" -f $Route) -Content ($output -join "`r`n")
                return [pscustomobject][ordered]@{
                    Round = $Round
                    Group = $Group
                    Route = $Route
                    File = $OutputPath
                    Status = 'fail'
                    ProbeStatus = $probeStatus
                    FailureReason = $probeStatus
                    HttpCode = $status
                    Error = $_.Exception.Message
                    Body = $bodyPreview
                }
            }
        } else {
            $textLines = @(
                $outputStatusLine,
                ("content-type: {0}" -f $response.Headers.'Content-Type'),
                $response.Content
            )
            Write-TextOrFailure -Path $OutputPath -Label ("[OK] {0}" -f $Route) -Content ($textLines -join "`r`n")
        }
        $isSuccess = Get-EndpointProbeSuccessStatus -ProbeStatus $probeStatus
        $failureReason = if ($isSuccess) { 'none' } else { $probeStatus }
        return [pscustomobject][ordered]@{
            Round = $Round
            Group = $Group
            Route = $Route
            File = $OutputPath
            Status = ($(if ($isSuccess) { 'ok' } else { 'fail' }))
            ProbeStatus = $probeStatus
            FailureReason = $failureReason
            HttpCode = $status
            Error = $null
            Body = $bodyPreview
        }
    } catch {
        $errorText = $_.Exception.Message -replace "`r|`n", ' '
        $probeStatus = Get-EndpointProbeFailureStatus -ErrorRecord $_
        $probeFailureLines = @(
            ("status={0}" -f $probeStatus),
            ('status_code={0}' -f 0),
            ('failure_reason={0}' -f $probeStatus),
            ("route={0}" -f $Route),
            ("error={0}" -f $errorText)
        )
        Write-TextOrFailure -Path $OutputPath -Label ("[FAIL] {0}" -f $Route) -Content ($probeFailureLines -join "`r`n")
        return [pscustomobject][ordered]@{
            Round = $Round
            Group = $Group
            Route = $Route
            File = $OutputPath
            Status = 'fail'
            ProbeStatus = $probeStatus
            FailureReason = $probeStatus
            HttpCode = 0
            Error = $errorText
            Body = $null
        }
    }
}

function Invoke-RouteProbeBatch {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][array]$Routes,
        [Parameter(Mandatory)][int]$Round,
        [Parameter(Mandatory)][string]$ProbeRoot,
        [Parameter(Mandatory)][int]$TimeoutSeconds
    )

    $roundRoot = Join-Path $ProbeRoot ('round-{0:D2}' -f $Round)
    New-Item -ItemType Directory -Force -Path $roundRoot | Out-Null

    $results = @()
    foreach ($entry in $Routes) {
        $routeName = [string]$entry.Name
        $routePath = [string]$entry.Route
        $routeGroup = if ($entry.Group) { [string]$entry.Group } else { 'unknown' }
        $outputPath = Join-Path $roundRoot ("{0}.txt" -f $routeName)
        $results += Invoke-EndpointProbe -BaseUrl $BaseUrl -Route $routePath -OutputPath $outputPath -TimeoutSeconds $TimeoutSeconds -Round $Round -Group $routeGroup
    }
    return $results
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$packRoot = Join-Path $repoRoot $OutputDirectory
New-Item -ItemType Directory -Force -Path $packRoot | Out-Null
$preexistingFeedbackRecords = Get-FeedbackRecordsFromPackFiles -Path $packRoot

$contextDirectory = Join-Path $OutputDirectory 'desktop-context'
$absoluteContextDirectory = Join-Path $repoRoot $contextDirectory
New-Item -ItemType Directory -Force -Path $absoluteContextDirectory | Out-Null
$snapshotResult = Invoke-DesktopContextSnapshot -OutputDirectoryRelative $contextDirectory
$snapshotSummary = Parse-DesktopSnapshotResult -ResultText $snapshotResult
$snapshotLatest = Join-Path $repoRoot $snapshotSummary.reportRelativePath
$snapshotShaPath = Join-Path (Split-Path -Parent $snapshotLatest) 'latest.sha256.txt'

$runtimeFactsRoot = Join-Path $packRoot 'runtime-facts'
New-Item -ItemType Directory -Force -Path $runtimeFactsRoot | Out-Null

$environmentOutput = Join-Path $packRoot 'environment-variables.txt'
$environmentLines = @(
    '# Environment variables (redacted for sensitive keys)'
    ("# generated: {0}" -f (Get-Date -Format o))
    ("# total: {0}" -f (Get-ChildItem Env: | Measure-Object).Count)
    ''
)
foreach ($entry in (Get-ChildItem Env: | Sort-Object Name)) {
    $environmentLines += ('{0}={1}' -f $entry.Name, (ConvertTo-ValueForHandoff -Name $entry.Name -Value $entry.Value))
}
Write-TextOrFailure -Path $environmentOutput -Label '[OK] environment' -Content ($environmentLines -join "`r`n")

$dbEnvironmentOutput = Join-Path $packRoot 'environment-variables-db.txt'
$dbEnvironmentLines = @(
    '# DB-scoped environment variables (redacted for sensitive keys)'
    ("# generated: {0}" -f (Get-Date -Format o))
    ''
)
foreach ($entry in (Get-ChildItem Env: |
    Where-Object { $_.Name -match '(?i)db|mysql|mariadb|jdbc|agentdb|supabase|postgres|redis|mongo|database' } |
    Sort-Object Name)) {
    $dbEnvironmentLines += ('{0}={1}' -f $entry.Name, (ConvertTo-ValueForHandoff -Name $entry.Name -Value $entry.Value))
}
if ($dbEnvironmentLines.Count -eq 3) {
    $dbEnvironmentLines += 'none'
}
Write-TextOrFailure -Path $dbEnvironmentOutput -Label '[OK] environment-db' -Content ($dbEnvironmentLines -join "`r`n")

$runtimeFactsPack = @(
    '# runtime facts packet'
    ("# generated: {0}" -f (Get-Date -Format o))
    ''
    ("snapshot_decision={0}" -f $snapshotSummary.decision)
    ("mariadb_decision={0}" -f $snapshotSummary.mariadb)
    ("evidence_needed_count={0}" -f $snapshotSummary.evidenceNeededCount)
)
Write-TextOrFailure -Path (Join-Path $runtimeFactsRoot 'pack-meta.txt') -Label '[OK] runtime-facts-meta' -Content ($runtimeFactsPack -join "`r`n")

$gitFacts = @(
    '# git/context'
    ("# generated: {0}" -f (Get-Date -Format o))
)
$gitBranch = 'unknown'
$gitChangedFiles = @()
try {
    $gitBranch = (git branch --show-current).Trim()
    $rawGitStatus = git status --short
    $gitFacts += "branch=$gitBranch"
    $gitFacts += ("changed_file_count={0}" -f $rawGitStatus.Count)
    $gitFacts += 'status='
    if ($rawGitStatus) {
        $gitChangedFiles = @($rawGitStatus)
        $gitFacts += $gitChangedFiles
    } else {
        $gitFacts += 'clean'
    }
}
catch {
    $gitFacts += 'error=git status command failed'
}
Write-TextOrFailure -Path (Join-Path $runtimeFactsRoot 'git-status.txt') -Label '[OK] runtime-facts-git' -Content ($gitFacts -join "`r`n")

$processFacts = @(
    '# java-processes'
    ("# generated: {0}" -f (Get-Date -Format o))
)
$ollamaProcessCount = 0
$ollamaProcessIds = @()
try {
    $javaProcesses = Get-Process -Name java -ErrorAction SilentlyContinue |
        Select-Object -Property Id, ProcessName, HandleCount, PM, NPM, WS, VirtualMemorySize, WorkingSet, CPU |
        ForEach-Object {
            "id={0} name={1} handle={2} pm={3} npm={4} ws={5} vms={6} ws2={7} cpu={8}" -f `
                $_.Id, $_.ProcessName, $_.HandleCount, $_.PM, $_.NPM, $_.WS, $_.VirtualMemorySize, $_.WorkingSet, $_.CPU
        }
    if ($javaProcesses -and $javaProcesses.Count -gt 0) {
        $processFacts += $javaProcesses
    } else {
        $processFacts += 'none'
    }
}
catch {
    $processFacts += 'error=failed to enumerate java processes'
}
$ollamaProcesses = Get-Process -Name ollama -ErrorAction SilentlyContinue
if ($ollamaProcesses) {
    $ollamaProcessCount = $ollamaProcesses.Count
    $ollamaProcessIds = @($ollamaProcesses | Select-Object -ExpandProperty Id)
    foreach ($p in $ollamaProcesses) {
        $processFacts += "ollama id={0} name={1} ws={2}" -f $p.Id, $p.ProcessName, $p.WorkingSet
    }
} else {
    $processFacts += 'ollama none'
}
Write-TextOrFailure -Path (Join-Path $runtimeFactsRoot 'java-processes.txt') -Label '[OK] runtime-facts-process' -Content ($processFacts -join "`r`n")

$portFacts = @(
    '# listening-ports'
    ("# generated: {0}" -f (Get-Date -Format o))
)
try {
    $listening = Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { $_.LocalPort -in 8080, 8081, 3000, 4173, 9000 } |
        Select-Object -Property LocalAddress, LocalPort, OwningProcess, State |
        Sort-Object LocalPort, OwningProcess |
        ForEach-Object {
            "listen={0}:{1} pid={2}" -f $_.LocalAddress, $_.LocalPort, $_.OwningProcess
        } |
        Select-Object -Unique
    if ($listening -and $listening.Count -gt 0) {
        $portFacts += $listening
    } else {
        $portFacts += 'none'
    }
}
catch {
    $portFacts += 'error=listening-port-check-skipped'
}
Write-TextOrFailure -Path (Join-Path $runtimeFactsRoot 'listening-ports.txt') -Label '[OK] runtime-facts-ports' -Content ($portFacts -join "`r`n")

$base = if ([string]::IsNullOrWhiteSpace($BaseUrl)) { "http://127.0.0.1:$HttpPort" } else { $BaseUrl.TrimEnd('/') }
$probeRoot = Join-Path $packRoot 'trace-mla'
New-Item -ItemType Directory -Force -Path $probeRoot | Out-Null

$routes = @(
    @{ Group = 'trace'; Name = 'trace_snapshots'; Route = '/api/diagnostics/trace/snapshots?limit=20' }
    @{ Group = 'trace'; Name = 'latest_trace_memory_html'; Route = '/api/diagnostics/trace/snapshots/latest-trace-memory/html' }
    @{ Group = 'trace'; Name = 'latest_trace_memory_checkpoints'; Route = '/api/diagnostics/trace/snapshots/latest-trace-memory/checkpoints' }
    @{ Group = 'trace'; Name = 'latest_harmony_html'; Route = '/api/diagnostics/trace/snapshots/latest-harmony/html' }
    @{ Group = 'trace'; Name = 'trace_memory_self_probe'; Route = '/api/diagnostics/trace/memory/self-probe?scenario=notebook_pingpong' }
    @{ Group = 'trace'; Name = 'trace_memory_self_probe_contamination'; Route = '/api/diagnostics/trace/memory/self-probe?scenario=synthetic_context_contamination' }
    @{ Group = 'trace'; Name = 'trace_memory_self_probe_loader_starvation'; Route = '/api/diagnostics/trace/memory/self-probe?scenario=synthetic_loader_starvation' }
    @{ Group = 'trace'; Name = 'trace_memory_self_probe_filter_starvation'; Route = '/api/diagnostics/trace/memory/self-probe?scenario=synthetic_after_filter_starvation' }
    @{ Group = 'trace'; Name = 'trace_memory_self_probe_silent_failure'; Route = '/api/diagnostics/trace/memory/self-probe?scenario=synthetic_silent_failure' }
    @{ Group = 'trace'; Name = 'trace_memory_self_probe_dropped_breadcrumb'; Route = '/api/diagnostics/trace/memory/self-probe?scenario=synthetic_dropped_breadcrumb' }
    @{ Group = 'runtime'; Name = 'runtime'; Route = '/api/diagnostics/runtime' }
    @{ Group = 'runtime'; Name = 'debug'; Route = '/api/diagnostics/debug' }
    @{ Group = 'runtime'; Name = 'debug_classpath'; Route = '/api/diagnostics/debug/classpath' }
    @{ Group = 'runtime'; Name = 'debug_events'; Route = '/api/diagnostics/debug/events?limit=40' }
    @{ Group = 'runtime'; Name = 'debug_fingerprints'; Route = '/api/diagnostics/debug/fingerprints?limit=40' }
    @{ Group = 'runtime'; Name = 'ablation'; Route = '/api/debug/ablation' }
    @{ Group = 'runtime'; Name = 'debug_ai_snapshot'; Route = '/api/diagnostics/debug/ai/snapshot?limit=120&windowMs=120000' }
    @{ Group = 'runtime'; Name = 'debug_ai_compact'; Route = '/api/diagnostics/debug/ai/compact?limit=60&windowMs=120000' }
    @{ Group = 'runtime'; Name = 'debug_ai_history'; Route = '/api/diagnostics/debug/ai/history?maxEntries=24' }
    @{ Group = 'runtime'; Name = 'debug_triadic_adjudication'; Route = '/api/diagnostics/debug/triadic-adjudication' }
    @{ Group = 'runtime'; Name = 'langgraph_reports'; Route = '/api/diagnostics/langgraph-contamination/reports?limit=20' }
    @{ Group = 'runtime'; Name = 'web_failsoft_domain_stage_report'; Route = '/api/diagnostics/web-failsoft/domain-stage-report?topN=20&minCount=1' }
    @{ Group = 'models'; Name = 'llm_cloud_models'; Route = '/api/diagnostics/llm/cloud-models?stage=chat' }
    @{ Group = 'models'; Name = 'embedding'; Route = '/api/diagnostics/embedding' }
    @{ Group = 'models'; Name = 'local_llm_smoke'; Route = '/api/diagnostics/local-llm/smoke-history?limit=20' }
    @{ Group = 'models'; Name = 'ocr'; Route = '/api/diagnostics/ocr' }
    @{ Group = 'uaw'; Name = 'uaw_autolearn_quality'; Route = '/api/diagnostics/uaw/autolearn/quality' }
    @{ Group = 'uaw'; Name = 'uaw_autolearn_loop'; Route = '/api/diagnostics/uaw/autolearn/loop' }
    @{ Group = 'uaw'; Name = 'uaw_autolearn_gpu_preflight'; Route = '/api/diagnostics/uaw/autolearn/gpu-gateway/preflight' }
    @{ Group = 'uaw'; Name = 'uaw_autolearn_agent_handoff'; Route = '/api/diagnostics/uaw/autolearn/agent-handoff' }
    @{ Group = 'vector'; Name = 'vector_diagnostics'; Route = '/api/vector/diagnostics' }
    @{ Group = 'vector'; Name = 'vector_upstash_info'; Route = '/api/vector/upstash/info' }
    @{ Group = 'vector'; Name = 'vector_upstash_namespaces'; Route = '/api/vector/upstash/namespaces' }
    @{ Group = 'vector_admin'; Name = 'vector_admin_status'; Route = '/api/admin/vector/status' }
    @{ Group = 'vector_admin'; Name = 'vector_admin_ingest_protection'; Route = '/api/admin/vector/ingest-protection' }
    @{ Group = 'vector_admin'; Name = 'vector_admin_dlq'; Route = '/api/admin/vector/dlq' }
    @{ Group = 'vector_admin'; Name = 'vector_admin_dlq_records'; Route = '/api/admin/vector/dlq/records' }
    @{ Group = 'vector_admin'; Name = 'vector_admin_dlq_reasons'; Route = '/api/admin/vector/dlq/reasons' }
    @{ Group = 'vector_admin'; Name = 'vector_admin_ingest_audit'; Route = '/api/admin/vector/ingest-audit' }
    @{ Group = 'vector_admin'; Name = 'vector_admin_quarantine'; Route = '/api/admin/vector/quarantine' }
    @{ Group = 'outbox'; Name = 'nova_outbox_stats'; Route = '/api/nova/outbox/stats' }
    @{ Group = 'outbox'; Name = 'nova_outbox_peek'; Route = '/api/nova/outbox/peek?state=pending&limit=20' }
    @{ Group = 'rag'; Name = 'rag_brain_domains'; Route = '/api/diagnostics/rag/brain-state/domains' }
    @{ Group = 'rag'; Name = 'rag_learning_ops_overview'; Route = '/api/diagnostics/rag/learning-ops/overview?limit=80' }
    @{ Group = 'rag'; Name = 'rag_learning_ops_metrics'; Route = '/api/diagnostics/rag/learning-ops/metrics' }
    @{ Group = 'rag'; Name = 'rag_learning_ops_samples'; Route = '/api/diagnostics/rag/learning-ops/samples?limit=40' }
    @{ Group = 'rag'; Name = 'rag_learning_ops_failures'; Route = '/api/diagnostics/rag/learning-ops/failures?limit=40' }
    @{ Group = 'rag'; Name = 'rag_learning_ops_prometheus'; Route = '/api/diagnostics/rag/learning-ops/metrics/prometheus' }
    @{ Group = 'rag'; Name = 'rag_ops_ledger_summary'; Route = '/api/diagnostics/rag/ops-ledger/summary?hours=24' }
    @{ Group = 'rag'; Name = 'rag_ops_ledger_recent'; Route = '/api/diagnostics/rag/ops-ledger/recent?limit=80' }
    @{ Group = 'image'; Name = 'image_diag'; Route = '/api/diagnostics/image/config' }
    @{ Group = 'db'; Name = 'agent_db_snapshot'; Route = '/agent/db-context/snapshot' }
    @{ Group = 'db'; Name = 'agent_db_memory'; Route = '/agent/db-context/memory' }
    @{ Group = 'db'; Name = 'agent_db_ledger'; Route = '/agent/db-context/ledger' }
    @{ Group = 'db'; Name = 'agent_db_strategy'; Route = '/agent/db-context/strategy' }
    @{ Group = 'db'; Name = 'agent_db_pipeline_health'; Route = '/agent/db-context/pipeline-health' }
)

$explicitHours = $PSBoundParameters.ContainsKey('PingPongHours')
$explicitMinutes = $PSBoundParameters.ContainsKey('PingPongMinutes')
$explicitRounds = $PSBoundParameters.ContainsKey('PingPongRounds')

$effectivePingPongMinutes = if ($explicitHours -and $PingPongHours -gt 0) {
    $PingPongHours * 60
} else {
    $PingPongMinutes
}

if ($explicitHours -and $explicitMinutes -and $PingPongHours -ne 0 -and $PingPongMinutes -ne 0) {
    Write-Host ('[PACK] PingPongHours={0} overrides PingPongMinutes={1}' -f $PingPongHours, $PingPongMinutes)
}

$probeRoundCount = if ($effectivePingPongMinutes -gt 0) {
    if ($PingPongHours -gt 0) {
        [Math]::Max(1, [int][Math]::Ceiling(($effectivePingPongMinutes * 60) / $PingIntervalSeconds))
    } else {
        [Math]::Max(1, [Math]::Min(400, [int][Math]::Ceiling(($effectivePingPongMinutes * 60) / $PingIntervalSeconds)))
    }
} elseif ($explicitRounds -and $explicitMinutes -and $PingPongMinutes -eq 0 -and -not $explicitHours) {
    [Math]::Max(1, $PingPongRounds)
} else {
    [Math]::Max(1, $PingPongRounds)
}
$probeMode = if ($PingPongHours -gt 0) {
    ('{0}시간치 pingpong x {1}회 (간격 {2}s)' -f $PingPongHours, $probeRoundCount, $PingIntervalSeconds)
} elseif ($effectivePingPongMinutes -gt 0) {
    ('{0}분치 pingpong x {1}회 (간격 {2}s)' -f $effectivePingPongMinutes, $probeRoundCount, $PingIntervalSeconds)
} else {
    ('수동 x {0}회' -f $probeRoundCount)
}
Write-Host ("[PACK] probe mode: {0}" -f $probeMode)

$allProbeResults = @()
$roundTimings = @()

if (Test-Path -LiteralPath $probeRoot) {
    Remove-Item -LiteralPath $probeRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $probeRoot | Out-Null

$routeManifestPath = Join-Path $probeRoot 'route-manifest.txt'
$routeManifest = @(
    '# notebook pingpong route manifest'
    ("# generated: {0}" -f (Get-Date -Format o))
    ("route_count={0}" -f $routes.Count)
    ''
)
for ($i = 0; $i -lt $routes.Count; $i++) {
    $entry = $routes[$i]
    $routeManifest += ('[{0:D3}] method=GET group={1} name={2} route={3}' -f ($i + 1), [string]$entry.Group, [string]$entry.Name, [string]$entry.Route)
}
Write-TextOrFailure -Path $routeManifestPath -Label '[OK] route-manifest' -Content ($routeManifest -join "`r`n")

for ($round = 1; $round -le $probeRoundCount; $round++) {
    $roundStart = Get-Date
    Write-Host ("[ROUND] {0}/{1}" -f $round, $probeRoundCount)
    $roundResults = Invoke-RouteProbeBatch -BaseUrl $base -Routes $routes -Round $round -ProbeRoot $probeRoot -TimeoutSeconds $HttpTimeoutSeconds
    $roundEnd = Get-Date
    $allProbeResults += $roundResults
    $roundTimings += [pscustomobject][ordered]@{
        Round = $round
        Start = $roundStart.ToString('o')
        End = $roundEnd.ToString('o')
        DurationMs = [int](($roundEnd - $roundStart).TotalMilliseconds)
        Total = $roundResults.Count
        Ok = ($roundResults | Where-Object { $_.Status -eq 'ok' }).Count
        Fail = ($roundResults | Where-Object { $_.Status -eq 'fail' }).Count
    }

    $roundOk = ($roundResults | Where-Object { $_.Status -eq 'ok' }).Count
    $roundFail = ($roundResults | Where-Object { $_.Status -eq 'fail' }).Count
    $roundSummaryPath = Join-Path (Join-Path $probeRoot ('round-{0:D2}' -f $round)) ('summary.txt')
    $roundSummary = @(
        '# pingpong round summary'
        ("# generated: {0}" -f (Get-Date -Format o))
        ("round={0}" -f $round)
        ("total={0}" -f $roundResults.Count)
        ("ok={0}" -f $roundOk)
        ("fail={0}" -f $roundFail)
        ''
        '### endpoints'
    )
    foreach ($probe in $roundResults) {
        $roundSummary += ('[{0}] {1} code={2} status={3}' -f $probe.Group, $probe.Route, $probe.HttpCode, $probe.Status)
        if ($probe.Error) { $roundSummary += ('  error={0}' -f $probe.Error) }
    }
    Write-TextOrFailure -Path $roundSummaryPath -Label ('[OK] round-summary') -Content ($roundSummary -join "`r`n")

    if ($round -lt $probeRoundCount) {
        Start-Sleep -Seconds $PingIntervalSeconds
    }
}

$probeResults = $allProbeResults
$okProbeCount = ($probeResults | Where-Object { $_.Status -eq 'ok' }).Count
$failProbeCount = ($probeResults | Where-Object { $_.Status -eq 'fail' }).Count
$emptyValidProbeCount = ($probeResults | Where-Object { $_.ProbeStatus -eq 'empty_valid' }).Count
$failures = $probeResults | Where-Object { $_.Status -eq 'fail' }
$probeRoundCountObserved = $probeRoundCount
$expectedProbeCount = $probeRoundCount * $routes.Count
$probeRoundSummary = ($probeResults | Group-Object -Property Round | Sort-Object Name)
$probeStatusOrder = @(
    'ok',
    'empty_valid',
    'server_unreachable',
    'connection_refused',
    'timeout',
    'route_missing_404',
    'unauthorized_401',
    'forbidden_403',
    'server_error_5xx',
    'db_unreachable',
    'db_auth_failure',
    'db_query_failure',
    'parse_error',
    'evidence_needed',
    'skipped',
    'unknown'
)
$probeStatusCounts = @{}
foreach ($status in $probeStatusOrder) {
    $probeStatusCounts[$status] = 0
}
foreach ($entry in ($probeResults | Group-Object -Property ProbeStatus)) {
    $key = [string]$entry.Name
    if (-not [string]::IsNullOrWhiteSpace($key)) {
        $probeStatusCounts[$key] = $entry.Count
    }
}

$routeStatusPath = Join-Path $probeRoot 'route-status.txt'
$routeStatusLines = @(
    '# route status breakdown (all rounds)'
    ("# generated: {0}" -f (Get-Date -Format o))
    ('# expected_probe_count: {0}' -f $expectedProbeCount)
    ('# observed_probe_count: {0}' -f $probeResults.Count)
    ''
)
foreach ($route in ($probeResults | Group-Object -Property Route | Sort-Object Name)) {
    $rounds = ($route.Group | Group-Object Round | Measure-Object).Count
    $fail = ($route.Group | Where-Object { $_.Status -eq 'fail' }).Count
    $statusCounts = @()
    foreach ($statusName in $probeStatusOrder) {
        $statusCounts += ('{0}={1}' -f $statusName, (($route.Group | Where-Object { $_.ProbeStatus -eq $statusName }).Count))
    }
    $firstFail = ($route.Group | Where-Object { $_.Status -eq 'fail' } | Select-Object -First 1)
    $lastFail = ($route.Group | Where-Object { $_.Status -eq 'fail' } | Select-Object -Last 1)
    $errorSample = if ($firstFail) { ("first={0}; last={1}" -f (Format-PreviewText -Text $firstFail.Error -MaxLength 90), (Format-PreviewText -Text $lastFail.Error -MaxLength 90)) } else { 'none' }
    $routeStatusLines += ('{0} rounds={1} fail={2} {3} {4}' -f $route.Name, $rounds, $fail, ($statusCounts -join ' '), $errorSample)
}
Write-TextOrFailure -Path $routeStatusPath -Label '[OK] route-status' -Content ($routeStatusLines -join "`r`n")

$groupOverallStatusPath = Join-Path $probeRoot 'group-overall-status.txt'
$groupOverallStatusLines = @(
    '# route group overall status'
    ("# generated: {0}" -f (Get-Date -Format o))
    ''
)
foreach ($group in ($probeResults | Group-Object -Property Group | Sort-Object Name)) {
    $statusCounts = @()
    foreach ($statusName in $probeStatusOrder) {
        $statusCounts += ('{0}={1}' -f $statusName, (($group.Group | Where-Object { $_.ProbeStatus -eq $statusName }).Count))
    }
    $groupOverallStatusLines += ('{0}: total={1} fail={2} {3}' -f $group.Name, $group.Count, (($group.Group | Where-Object { $_.Status -eq 'fail' }).Count), ($statusCounts -join ' '))
}
Write-TextOrFailure -Path $groupOverallStatusPath -Label '[OK] group-overall-status' -Content ($groupOverallStatusLines -join "`r`n")

$groupRoundSummaryPath = Join-Path $probeRoot 'group-round-summary.txt'
$allGroups = ($routes | ForEach-Object Group | Sort-Object -Unique)
$groupRoundSummaryLines = @(
    '# route group status by round'
    ("# generated: {0}" -f (Get-Date -Format o))
    ''
)
foreach ($round in $probeRoundSummary) {
    $groupRoundSummaryLines += ('# round={0}' -f $round.Name)
    foreach ($groupName in $allGroups) {
        $roundGroup = $round.Group | Where-Object { $_.Group -eq $groupName }
        $statusCounts = @()
        foreach ($statusName in $probeStatusOrder) {
            $statusCounts += ('{0}={1}' -f $statusName, (($roundGroup | Where-Object { $_.ProbeStatus -eq $statusName }).Count))
        }
        $groupRoundSummaryLines += ('- {0}: fail={1} {2}' -f $groupName, ($roundGroup | Where-Object { $_.Status -eq 'fail' }).Count, ($statusCounts -join ' '))
    }
    $groupRoundSummaryLines += ''
}
Write-TextOrFailure -Path $groupRoundSummaryPath -Label '[OK] group-round-summary' -Content ($groupRoundSummaryLines -join "`r`n")

$routeFailRankPath = Join-Path $probeRoot 'route-fail-rank.txt'
$routeFailRankLines = @(
    '# route fail rank'
    ("# generated: {0}" -f (Get-Date -Format o))
    ''
)
if ($failProbeCount -eq 0) {
    $routeFailRankLines += 'none'
} else {
    foreach ($route in ($failures | Group-Object Route | Sort-Object Count -Descending)) {
        $ok = ($route.Group | Where-Object { $_.Status -eq 'ok' }).Count
        $fail = ($route.Group | Where-Object { $_.Status -eq 'fail' }).Count
        $firstFail = $route.Group | Where-Object { $_.Status -eq 'fail' } | Select-Object -First 1
        $lastFail = $route.Group | Where-Object { $_.Status -eq 'fail' } | Select-Object -Last 1
        $routeFailRankLines += ('{0} ok={1} fail={2} first_round={3} last_round={4} first_err={5}' -f $route.Name, $ok, $fail, $firstFail.Round, $lastFail.Round, (Format-PreviewText -Text $firstFail.Error -MaxLength 90))
    }
}
Write-TextOrFailure -Path $routeFailRankPath -Label '[OK] route-fail-rank' -Content ($routeFailRankLines -join "`r`n")

$probeTimelinePath = Join-Path $probeRoot 'probe-rounds-timeline.txt'
$probeTimelineLines = @(
    '# probe rounds timeline'
    ("# generated: {0}" -f (Get-Date -Format o))
    ''
)
foreach ($item in $roundTimings) {
    $probeTimelineLines += ('round={0} start={1} end={2} dur_ms={3} total={4} ok={5} fail={6}' -f $item.Round, $item.Start, $item.End, $item.DurationMs, $item.Total, $item.Ok, $item.Fail)
}
Write-TextOrFailure -Path $probeTimelinePath -Label '[OK] probe-rounds-timeline' -Content ($probeTimelineLines -join "`r`n")

$probeSummaryPath = Join-Path $packRoot 'probe-summary.txt'
$groupSummary = ($probeResults | Group-Object -Property Group | Sort-Object Name)
$probeSummary = @(
    '# probe summary'
    ("# generated: {0}" -f (Get-Date -Format o))
    ("round_count={0}" -f $probeRoundCountObserved)
    ("round_interval_seconds={0}" -f $PingIntervalSeconds)
    ("configured_minutes={0}" -f $effectivePingPongMinutes)
    ("configured_hours={0}" -f $PingPongHours)
    ("requested_minutes={0}" -f $PingPongMinutes)
    ("configured_rounds={0}" -f $probeRoundCount)
    ("requested_rounds={0}" -f $PingPongRounds)
    ("expected_total={0}" -f $expectedProbeCount)
    ('total={0}' -f $probeResults.Count)
    ('ok={0}' -f $okProbeCount)
    ('fail={0}' -f $failProbeCount)
    ('empty_valid={0}' -f $emptyValidProbeCount)
    ("coverage={0:P1}" -f (($probeResults.Count / [Math]::Max(1,$expectedProbeCount))))
    ''
)
foreach ($statusName in $probeStatusOrder) {
    $probeSummary += ('{0}={1}' -f ('status_' + $statusName), $probeStatusCounts[$statusName])
}

foreach ($group in $groupSummary) {
    $probeSummary += ('[{0}] count={1}' -f $group.Name, $group.Count)
}
$probeSummary += ''
$probeSummary += '### fail list'
foreach ($probe in $failures) {
    $probeSummary += ('{0} code={1} status={2} error={3}' -f $probe.Route, $probe.HttpCode, $probe.ProbeStatus, $probe.Error)
}
if ($failProbeCount -eq 0) {
    $probeSummary += 'none'
}
$probeSummary += ''
$probeSummary += '### round-by-round'
foreach ($round in $probeRoundSummary) {
    $roundOkCount = ($round.Group | Where-Object Status -eq 'ok').Count
    $roundFailCount = ($round.Group | Where-Object Status -eq 'fail').Count
    $statusCounts = @()
    foreach ($statusName in $probeStatusOrder) {
        $statusCounts += ('{0}={1}' -f $statusName, (($round.Group | Where-Object { $_.ProbeStatus -eq $statusName }).Count))
    }
    $probeSummary += ('round={0} total={1} ok={2} fail={3} {4}' -f $round.Name, $round.Count, $roundOkCount, $roundFailCount, ($statusCounts -join ' '))
}
$probeSummary += ''
$probeSummary += '### recurring fails'
if ($failProbeCount -gt 0) {
    foreach ($probe in ($failures | Group-Object Route | Sort-Object Name)) {
        $probeSummary += ('{0} times={1}' -f $probe.Name, $probe.Count)
    }
} else {
    $probeSummary += 'none'
}
Write-TextOrFailure -Path $probeSummaryPath -Label '[OK] probe-summary' -Content ($probeSummary -join "`r`n")

$mlabrOutput = Join-Path $packRoot 'mla-breadcrumbs.txt'
$mlabrLines = @(
    '# MLA/trace breadcrumb packet'
    ("# generated: {0}" -f (Get-Date -Format o))
    ("round_count={0}" -f $probeRoundCountObserved)
    ("round_interval_seconds={0}" -f $PingIntervalSeconds)
    ''
)
foreach ($probe in $probeResults) {
    $mlabrLines += ('[r{0:D2}] {1} group={2} code={3} status={4} probe_status={5}' -f $probe.Round, $probe.Route, $probe.Group, $probe.HttpCode, $probe.Status, $probe.ProbeStatus)
    if ($probe.Error) { $mlabrLines += ('  error={0}' -f $probe.Error) }
}
Write-TextOrFailure -Path $mlabrOutput -Label '[OK] mla-breadcrumbs' -Content ($mlabrLines -join "`r`n")

$failOnlyOutput = Join-Path $packRoot 'mla-failures-only.txt'
$failOnlyLines = @(
    '# MLA failures only'
    ("# generated: {0}" -f (Get-Date -Format o))
    ("total_fail={0}" -f $failProbeCount)
    ''
)
foreach ($probe in $failures) {
    $failOnlyLines += ('[r{0:D2}] {1} code={2} status={3} error={4}' -f $probe.Round, $probe.Route, $probe.HttpCode, $probe.ProbeStatus, $probe.Error)
}
if ($failProbeCount -eq 0) {
    $failOnlyLines += 'none'
}
Write-TextOrFailure -Path $failOnlyOutput -Label '[OK] mla-failures-only' -Content ($failOnlyLines -join "`r`n")

$postProbeFeedbackRecords = Get-FeedbackRecordsFromPackFiles -Path $packRoot
$feedbackMerge = Merge-FeedbackRecords -ExistingRecords $preexistingFeedbackRecords -IncomingRecords $postProbeFeedbackRecords
$feedbackRecords = @($feedbackMerge.Records)
$feedbackConflicts = @($feedbackMerge.Conflicts)
$feedbackJsonlPath = Join-Path $packRoot 'feedback-queue.jsonl'
$feedbackMarkdownPath = Join-Path $packRoot 'feedback-queue.md'
$feedbackConflictPath = Join-Path $packRoot 'feedback-queue-conflicts.md'

$feedbackJsonLines = @()
foreach ($record in ($feedbackRecords | Sort-Object { if ($_.feedback_id) { [string]$_.feedback_id } else { 'fallback' } })) {
    $feedbackJsonLines += ($record | ConvertTo-Json -Depth 12 -Compress)
}
if ($feedbackJsonLines.Count -eq 0) {
    Write-TextOrFailure -Path $feedbackJsonlPath -Label '[OK] feedback-queue.jsonl' -Content ''
} else {
    Write-TextOrFailure -Path $feedbackJsonlPath -Label '[OK] feedback-queue.jsonl' -Content ($feedbackJsonLines -join "`r`n")
}

$feedbackMdLines = @(
    '# feedback queue'
    ("# generated: {0}" -f (Get-Date -Format o))
    ''
)
if ($feedbackRecords.Count -eq 0) {
    $feedbackMdLines += 'none'
} else {
    foreach ($record in ($feedbackRecords | Sort-Object { if ($_.feedback_id) { [string]$_.feedback_id } else { 'fallback' } })) {
        $feedbackMdLines += ('feedback_id={0}' -f (if ($record.feedback_id) { $record.feedback_id } else { '(fallback)' }))
        if ($record.source_row_id) { $feedbackMdLines += ('source_row_id={0}' -f $record.source_row_id) }
        if ($record.owner) { $feedbackMdLines += ('owner={0}' -f $record.owner) }
        if ($record.status) { $feedbackMdLines += ('status={0}' -f $record.status) }
        if ($record.file) { $feedbackMdLines += ('file={0}' -f $record.file) }
        if ($record.note) { $feedbackMdLines += ('note={0}' -f $record.note) }
        if ($record.action) { $feedbackMdLines += ('action={0}' -f $record.action) }
        if ($record.acknowledged_at) { $feedbackMdLines += ('acknowledged_at={0}' -f $record.acknowledged_at) }
        if ($record.updated_at) { $feedbackMdLines += ('updated_at={0}' -f $record.updated_at) }
        if ($record.SourceFile) { $feedbackMdLines += ('source_file={0}' -f $record.SourceFile) }
        $feedbackMdLines += ''
    }
}
Write-TextOrFailure -Path $feedbackMarkdownPath -Label '[OK] feedback-queue.md' -Content ($feedbackMdLines -join "`r`n")

if ($feedbackConflicts.Count -gt 0) {
    $conflictLines = @(
        '# feedback queue conflicts'
        ("# generated: {0}" -f (Get-Date -Format o))
        ''
    )
    foreach ($item in $feedbackConflicts) {
        $conflictLines += ('feedback_id={0} current_status={1} incoming_status={2} current_file={3} incoming_file={4}' -f $item.feedback_id, $item.current_status, $item.incoming_status, $item.current_file, $item.incoming_file)
    }
    Write-TextOrFailure -Path $feedbackConflictPath -Label '[OK] feedback-queue-conflicts.md' -Content ($conflictLines -join "`r`n")
} else {
    Write-TextOrFailure -Path $feedbackConflictPath -Label '[OK] feedback-queue-conflicts.md' -Content "no_conflict"
}

$runEndTime = Get-Date
$runMetadataPath = Join-Path $packRoot 'run-metadata.txt'
$runMetadata = @(
    '# notebook pingpong run metadata'
    ("started_at_utc={0}" -f $runStartTime.ToUniversalTime().ToString('o'))
    ("ended_at_utc={0}" -f $runEndTime.ToUniversalTime().ToString('o'))
    ("run_id={0}" -f $runStartTime.ToString('yyyyMMdd-HHmmss'))
    ("duration_seconds={0}" -f [int]($runEndTime - $runStartTime).TotalSeconds)
    ("duration_display={0}" -f $runEndTime.Subtract($runStartTime).ToString())
    ''
    '# capture config'
    ("pingpong_hours={0}" -f $PingPongHours)
    ("pingpong_minutes={0}" -f $PingPongMinutes)
    ("pingpong_rounds={0}" -f $PingPongRounds)
    ("ping_interval_seconds={0}" -f $PingIntervalSeconds)
    ("effective_pingpong_minutes={0}" -f $effectivePingPongMinutes)
    ("probe_round_count={0}" -f $probeRoundCount)
    ("http_port={0}" -f $HttpPort)
    ("base_url={0}" -f $base)
    ("http_timeout_seconds={0}" -f $HttpTimeoutSeconds)
    ("database_mode={0}" -f $DatabaseMode)
    ("database_timeout_seconds={0}" -f $DatabaseTimeoutSeconds)
    ("max_metadata_rows={0}" -f $MaxMetadataRows)
    ("route_count={0}" -f $routes.Count)
    ("feedback_record_count={0}" -f $feedbackRecords.Count)
    ("feedback_conflict_count={0}" -f $feedbackConflicts.Count)
    ('feedback_queue_jsonl=feedback-queue.jsonl')
    ('feedback_queue_markdown=feedback-queue.md')
    ('feedback_conflict_file=feedback-queue-conflicts.md')
    ("probe_root_dir={0}" -f 'trace-mla')
)
Write-TextOrFailure -Path $runMetadataPath -Label '[OK] run-metadata' -Content ($runMetadata -join "`r`n")

$summaryPath = Join-Path $packRoot 'pingpong-summary.txt'
$snapshotLatestStatus = if (Test-Path -LiteralPath $snapshotLatest) { 'ok' } else { 'missing' }
$snapshotShaValue = if (Test-Path -LiteralPath $snapshotShaPath) { (Get-Content $snapshotShaPath) } else { $snapshotSummary.sha256 }
$runtimeFiles = Get-PacketCollectedFiles -PackRoot $packRoot
$allCapturedFileObjects = @()
$emptyFileNames = @()
$summary = @(
    '### Notebook pingpong packet generated'
    ("time: {0}" -f (Get-Date).ToString('o'))
    ("output_root: {0}" -f $packRoot)
    ('capture_mode: {0}' -f $probeMode)
    ('capture_rounds: {0}' -f $probeRoundCountObserved)
    ('capture_interval_seconds: {0}' -f $PingIntervalSeconds)
    ''
    '### 핵심 골자'
    ("snapshot_report: {0}" -f $snapshotSummary.reportRelativePath)
    ("snapshot_decision: {0}" -f $snapshotSummary.decision)
    ("mariadb_decision: {0}" -f $snapshotSummary.mariadb)
    ("evidence_needed_count: {0}" -f $snapshotSummary.evidenceNeededCount)
    ("snapshot_latest: {0}" -f $snapshotLatestStatus)
    ("snapshot_sha256: {0}" -f $snapshotShaValue)
    ("env_total: {0}" -f (Get-Content -Path $environmentOutput | Measure-Object).Count)
    ("db_env_total: {0}" -f (Get-Content -Path $dbEnvironmentOutput | Measure-Object).Count)
    ("probe_total: {0}" -f $probeResults.Count)
    ("probe_ok: {0}" -f $okProbeCount)
    ("probe_fail: {0}" -f $failProbeCount)
    ("probe_empty_valid: {0}" -f $emptyValidProbeCount)
    ("feedback_record_count: {0}" -f $feedbackRecords.Count)
    ("feedback_conflict_count: {0}" -f $feedbackConflicts.Count)
    ("feedback_queue_jsonl: feedback-queue.jsonl")
    ("feedback_queue_markdown: feedback-queue.md")
    ("feedback_conflict_file: feedback-queue-conflicts.md")
    ("captured_file_count: {0}" -f ($runtimeFiles.Count))
    ("all_captured_file: all-captured-files.txt")
    ("run_metadata_file: run-metadata.txt")
    ("notebook_index_file: notebook-pingpong-index.txt")
    ("captured_rounds_target: {0}" -f $probeRoundCountObserved)
    ("captured_rounds_observed: {0}" -f $probeRoundSummary.Count)
    ("next_action: {0}" -f ($(if ($failProbeCount -gt 0) { 'evidence_needed' } else { 'ready_for_review' })))
    ''
    '### route groups'
)
foreach ($group in ($routes | Group-Object -Property Group | Sort-Object Name)) {
    $summary += ('- {0}: {1}' -f $group.Name, $group.Count)
}
$summary += ''
$summary += '### Probe summary (all rounds)'
foreach ($probe in $probeResults) {
    $summary += ('- {0} code={1} status={2}' -f $probe.Route, $probe.HttpCode, $probe.ProbeStatus)
}
$summary += ''
$summary += '### Round summary'
foreach ($round in $probeRoundSummary) {
    $statusLine = ('- round={0}: total={1}, ok={2}, fail={3}' -f $round.Name, $round.Count, ($round.Group | Where-Object { $_.Status -eq 'ok' }).Count, ($round.Group | Where-Object { $_.Status -eq 'fail' }).Count)
    $statusCounts = @()
    foreach ($statusName in $probeStatusOrder) {
        $statusCounts += ('{0}={1}' -f $statusName, (($round.Group | Where-Object { $_.ProbeStatus -eq $statusName }).Count))
    }
    $summary += ('{0} {1}' -f $statusLine, ($statusCounts -join ' '))
}
$summary += ''
$summary += '### Collected files'
if ($runtimeFiles.Count -gt 0) {
    $summary += $runtimeFiles
} else {
    $summary += 'none'
}
$summary += ''
$summary += '### route_failure_rank'
foreach ($failure in ($failures | Group-Object Route | Sort-Object Count -Descending | Select-Object -First 20)) {
    $summary += ('- {0}: fail={1}' -f $failure.Name, $failure.Count)
}
$summary += ''
$summary += '### key=value index'
$summary += ('summary_time_utc={0}' -f (Get-Date).ToUniversalTime().ToString('o'))
$summary += ('branch={0}' -f $gitBranch)
$summary += ('changed_file_count={0}' -f $gitChangedFiles.Count)
$summary += ('changed_files_sample={0}' -f (($gitChangedFiles | Select-Object -First 12) -join '|'))
$summary += ('java_process_count={0}' -f ($javaProcesses.Count))
$summary += ('ollama_process_count={0}' -f $ollamaProcessCount)
$summary += ('ollama_process_ids={0}' -f (($ollamaProcessIds | ForEach-Object { $_ }) -join ','))
$summary += ('snapshot_decision={0}' -f $snapshotSummary.decision)
$summary += ('mariadb_decision={0}' -f $snapshotSummary.mariadb)
$summary += ('probe_mode={0}' -f $probeMode)
$summary += ('probe_rounds={0}' -f $probeRoundCountObserved)
$summary += ('probe_total={0}' -f $probeResults.Count)
$summary += ('probe_ok={0}' -f $okProbeCount)
$summary += ('probe_fail={0}' -f $failProbeCount)
$summary += ('probe_empty_valid={0}' -f $emptyValidProbeCount)
foreach ($statusName in $probeStatusOrder) {
    $summary += ('probe_status_{0}={1}' -f $statusName, $probeStatusCounts[$statusName])
}
$summary += ('feedback_record_count={0}' -f $feedbackRecords.Count)
$summary += ('feedback_conflict_count={0}' -f $feedbackConflicts.Count)
$summary += ('probe_timeout_seconds={0}' -f $HttpTimeoutSeconds)
$summary += ('next_action_file=NEXT_ACTIONS.txt')
$summary += ('environment_variable_count={0}' -f (Get-Content -Path $environmentOutput | Measure-Object).Count)
$summary += ('db_environment_variable_count={0}' -f (Get-Content -Path $dbEnvironmentOutput | Measure-Object).Count)
$summary += ('all_captured_file_count={0}' -f $runtimeFiles.Count)
$summary += ('all_captured_file=all-captured-files.txt')
$summary += ('run_metadata_file=run-metadata.txt')
$summary += ('empty_file_count={0}' -f $emptyFileNames.Count)
$summary += ('empty_files={0}' -f (($emptyFileNames | Select-Object -First 20) -join ','))
$summary += ('run_status={0}' -f ($(if ($failProbeCount -gt 0) { 'FAIL' } else { 'PASS' })))
Write-TextOrFailure -Path $summaryPath -Label '[OK] summary' -Content ($summary -join "`r`n")

if (Test-Path -LiteralPath $snapshotShaPath) {
    Write-Host ("snapshot_sha={0}" -f (Get-Content $snapshotShaPath))
}

$notebookIndexPath = Join-Path $packRoot 'notebook-pingpong-index.txt'
$notebookIndexLines = @(
    'Notebook pingpong postprocess index',
    ("generated: {0}" -f (Get-Date -Format o)),
    '',
    '[core]',
    'run-metadata.txt',
    'all-captured-files.txt',
    "pingpong-summary.txt",
    "probe-summary.txt",
    "route-manifest.txt",
    "route-status.txt",
    "route-fail-rank.txt",
    "probe-rounds-timeline.txt",
    "group-overall-status.txt",
    "group-round-summary.txt",
    "mla-breadcrumbs.txt",
    "mla-failures-only.txt",
    '',
    '[feedback]',
    'feedback-queue.jsonl',
    'feedback-queue.md',
    'feedback-queue-conflicts.md',
    '',
    '[environment]',
    "environment-variables.txt",
    "environment-variables-db.txt",
    '',
    '[runtime facts]',
    "runtime-facts/pack-meta.txt",
    "runtime-facts/git-status.txt",
    "runtime-facts/java-processes.txt",
    "runtime-facts/listening-ports.txt",
    '',
    '[agent db context]',
    "desktop-context/latest.md",
    "desktop-context/latest.sha256.txt",
    '',
    '[mla traces]',
    "trace-mla/route-manifest.txt",
    "trace-mla/route-status.txt",
    "trace-mla/group-overall-status.txt",
    "trace-mla/group-round-summary.txt",
    "trace-mla/route-fail-rank.txt",
    "trace-mla/probe-rounds-timeline.txt",
    "trace-mla/summary-by-round: for each run: trace-mla/round-XX/summary.txt",
    "trace-mla/*.txt (route samples per route per round)"
)
Write-TextOrFailure -Path $notebookIndexPath -Label '[OK] notebook index' -Content ($notebookIndexLines -join "`r`n")

$allCapturedFileObjects = Get-ChildItem -Path $packRoot -Recurse -File | Sort-Object FullName
$allCapturedFiles = @()
$allCapturedFilesLines = @(
    '# all captured files'
    ("# generated: {0}" -f (Get-Date -Format o))
)
foreach ($entry in $allCapturedFileObjects) {
    $record = Get-FileRecord -BasePath $packRoot -Path $entry.FullName
    if ($record) {
        $allCapturedFiles += (Get-RelPath -BasePath $packRoot -FullPath $entry.FullName)
        $allCapturedFilesLines += $record
    }
}
$allCapturedFilesLines = @(
    $allCapturedFilesLines[0],
    ("total={0}" -f $allCapturedFileObjects.Count),
    ''
    ) + $allCapturedFilesLines[1..($allCapturedFilesLines.Count - 1)]
$allCapturedFilesPath = Join-Path $packRoot 'all-captured-files.txt'
Write-TextOrFailure -Path $allCapturedFilesPath -Label '[OK] all-captured-files' -Content ($allCapturedFilesLines -join "`r`n")

$emptyFiles = $allCapturedFileObjects | Where-Object { $_.Length -eq 0 }
$emptyFileNames = @()
foreach ($entry in $emptyFiles) {
    $emptyFileNames += Get-RelPath -BasePath $packRoot -FullPath $entry.FullName
}

$runIndexPath = Join-Path $packRoot 'notebook-pingpong-index.txt'
$runIndexLines = @(
    'Notebook pingpong postprocess index',
    ("generated: {0}" -f (Get-Date -Format o)),
    '',
    '[human]',
    '# Recommended read order',
    '1) run-metadata.txt',
    '2) all-captured-files.txt',
    '3) pingpong-summary.txt',
    '4) probe-summary.txt',
    '5) route-manifest.txt',
    '6) route-status.txt',
    '7) mla-failures-only.txt',
    '8) group-round-summary.txt',
    '9) trace-mla/route-status.txt',
    '10) trace-mla/route-fail-rank.txt',
    '11) feedback-queue.jsonl',
    '12) feedback-queue.md',
    '13) feedback-queue-conflicts.md',
    '',
    '[key_value_index]',
    ('run_metadata=run-metadata.txt'),
    ('all_captured_files=all-captured-files.txt'),
    ('pingpong_summary=pingpong-summary.txt'),
    ('probe_summary=probe-summary.txt'),
    ('route_manifest=trace-mla/route-manifest.txt'),
    ('route_status=trace-mla/route-status.txt'),
    ('route_fail_rank=trace-mla/route-fail-rank.txt'),
    ('group_round_summary=trace-mla/group-round-summary.txt'),
    ('mla_breadcrumbs=mla-breadcrumbs.txt'),
    ('feedback_queue_jsonl=feedback-queue.jsonl'),
    ('feedback_queue_markdown=feedback-queue.md'),
    ('feedback_queue_conflict=feedback-queue-conflicts.md'),
    ('run_total_probe={0}' -f $probeResults.Count),
    ('run_ok={0}' -f $okProbeCount),
    ('run_fail={0}' -f $failProbeCount),
    ('feedback_record_count={0}' -f $feedbackRecords.Count),
    ('feedback_conflict_count={0}' -f $feedbackConflicts.Count)
)
Write-TextOrFailure -Path $runIndexPath -Label '[OK] notebook index' -Content ($runIndexLines -join "`r`n")

$nextActionsPath = Join-Path $packRoot 'NEXT_ACTIONS.txt'
$nextActions = @(
    '### NEXT_ACTIONS',
    ("generated: {0}" -f (Get-Date -Format o)),
    '',
    ('1) route_failure_count={0}' -f $failProbeCount),
    ('2) server_offline_like={0}' -f ($(if ($failProbeCount -eq $probeResults.Count) { 'yes' } else { 'no' })),
    ('3) required_run = rerun_after-start' )
)
if ($emptyFileNames.Count -gt 0) {
    $nextActions += '4) empty_file_cleanup: ' + (($emptyFileNames | Select-Object -First 20) -join ',')
}
Write-TextOrFailure -Path $nextActionsPath -Label '[OK] NEXT_ACTIONS' -Content ($nextActions -join "`r`n")
