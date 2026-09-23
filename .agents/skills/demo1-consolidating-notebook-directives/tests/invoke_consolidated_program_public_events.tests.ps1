[CmdletBinding()]
param(
    [switch]$PrimaryOnly,
    [switch]$NoPatchOnly,
    [switch]$SerializerOnly,
    [switch]$LockOnly,
    [switch]$ReadOnlyOnly,
    [switch]$CommittedBeginOnly,
    [switch]$CommittedSnapshotOnly,
    [switch]$ReachabilityOnly,
    [switch]$PhaseDeltaOnly,
    [switch]$AppendRollbackOnly,
    [switch]$RollbackSuffixOnly,
    [switch]$CreatedReplacementOnly,
    [switch]$PrefixTamperOnly,
    [switch]$HardLinkOnly,
    [switch]$RollbackUnprovenOnly,
    [switch]$PartialTailOnly,
    [switch]$MalformedLedgerOnly,
    [switch]$RelationMatrixOnly,
    [switch]$BoundsOnly,
    [switch]$LockShapeOnly,
    [switch]$LockReparseOnly,
    [switch]$LockAppearanceOnly,
    [switch]$ProspectiveBoundsOnly,
    [switch]$CheckpointTamperOnly,
    [switch]$HashOracleOnly,
    [switch]$CorruptionMatrixOnly,
    [switch]$PublisherRaceOnly,
    [switch]$DelayedPublisherOnly,
    [switch]$DelayedRecordNextBeginOnly,
    [switch]$SnapshotRaceOnly,
    [switch]$NoWriteOnly,
    [switch]$BeginLoserOnly,
    [switch]$BeginInternalOnly,
    [switch]$RuntimeLocksOnly,
    [switch]$HistoricalProofOnly,
    [switch]$EnvelopeMatrixOnly,
    [switch]$LedgerEnvelopeOnly,
    [switch]$AdoptOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PSBoundParameters.Count -eq 0) {
    $matrixModes = @(
        'PrimaryOnly','NoPatchOnly','SerializerOnly','LockOnly','ReadOnlyOnly','CommittedBeginOnly',
        'CommittedSnapshotOnly','ReachabilityOnly','PhaseDeltaOnly','AppendRollbackOnly',
        'RollbackSuffixOnly','CreatedReplacementOnly','PrefixTamperOnly','HardLinkOnly',
        'RollbackUnprovenOnly','PartialTailOnly','MalformedLedgerOnly','RelationMatrixOnly','BoundsOnly',
        'LockShapeOnly','LockReparseOnly','LockAppearanceOnly','ProspectiveBoundsOnly','CheckpointTamperOnly',
        'HashOracleOnly','CorruptionMatrixOnly','PublisherRaceOnly','DelayedPublisherOnly',
        'DelayedRecordNextBeginOnly','SnapshotRaceOnly','NoWriteOnly','BeginLoserOnly','BeginInternalOnly',
        'RuntimeLocksOnly','HistoricalProofOnly','EnvelopeMatrixOnly','LedgerEnvelopeOnly','AdoptOnly'
    )
    $matrixPowerShell = (Get-Command powershell.exe -ErrorAction Stop).Source
    $matrixTotal = 0
    foreach ($matrixMode in $matrixModes) {
        $matrixOutput = @(& $matrixPowerShell -NoProfile -ExecutionPolicy Bypass -File $MyInvocation.MyCommand.Path ("-$matrixMode") 2>&1)
        $matrixExitCode = $LASTEXITCODE
        if ($matrixExitCode -ne 0) {
            $matrixOutput | ForEach-Object { Write-Output $_.ToString() }
            throw "public-events-matrix-failed:${matrixMode}:$matrixExitCode"
        }
        $matrixPass = @($matrixOutput | ForEach-Object { $_.ToString() } | Where-Object { $_ -match '^PASS: ([0-9]+) assertions$' })
        if ($matrixPass.Count -ne 1) { throw "public-events-matrix-result-invalid:$matrixMode" }
        $matrixAssertions = [int]([regex]::Match($matrixPass[0], '^PASS: ([0-9]+) assertions$').Groups[1].Value)
        $matrixTotal += $matrixAssertions
        $matrixOutput | ForEach-Object { $_.ToString() } | Where-Object {
            $_ -match '^(HARDLINK SENTINEL:|DELAYED RECORD -> NEXT BEGIN:)'
        } | ForEach-Object { Write-Output $_ }
        Write-Output "MATRIX: mode=$matrixMode assertions=$matrixAssertions"
    }
    Write-Output "PASS: $matrixTotal assertions"
    exit 0
}

$script:utf8 = New-Object Text.UTF8Encoding($false, $true)
$script:assertions = 0
$script:repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..'))
$script:sourceController = Join-Path $PSScriptRoot '..\scripts\invoke_consolidated_program.ps1'
$script:sourceCore = Join-Path $PSScriptRoot '..\scripts\consolidated_program_core.psm1'
$script:sourceSession = Join-Path $script:repoRoot '__patch_drop__\source_edit_session.ps1'
$script:sourceLeaseContract = Join-Path $script:repoRoot '__patch_drop__\source_edit_lease_contract.ps1'
$script:sourceControllerHash = (Get-FileHash -LiteralPath $script:sourceController -Algorithm SHA256).Hash
$script:sourceCoreHash = (Get-FileHash -LiteralPath $script:sourceCore -Algorithm SHA256).Hash
$script:sourceSessionHash = (Get-FileHash -LiteralPath $script:sourceSession -Algorithm SHA256).Hash
$script:sourceLeaseContractHash = (Get-FileHash -LiteralPath $script:sourceLeaseContract -Algorithm SHA256).Hash
$script:fixtureRoot = $null
$script:fixtureController = $null
$script:outsideSentinel = $null
$script:pwsh = (Get-Command powershell.exe -ErrorAction Stop).Source

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
    $script:assertions++
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -cne $Expected) { throw "ASSERTION FAILED: $Message (actual=$Actual expected=$Expected)" }
    $script:assertions++
}

function Write-Utf8 {
    param([string]$Path, [string]$Text)
    $parent = Split-Path $Path -Parent
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force -ErrorAction Stop | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Text, $script:utf8)
}

function Get-Hash {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Get-TestTreeFingerprint {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return 'absent' }
    $rootItem = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'test-fingerprint-reparse' }
    if (-not $rootItem.PSIsContainer) {
        return ([ordered]@{ kind='leaf'; bytes=[int64]$rootItem.Length; sha256=Get-Hash $rootItem.FullName } | ConvertTo-Json -Compress)
    }
    $rootFull = [IO.Path]::GetFullPath($rootItem.FullName).TrimEnd('\')
    $rows = @()
    foreach ($item in @(Get-ChildItem -LiteralPath $rootFull -Force -Recurse -ErrorAction Stop | Sort-Object FullName)) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'test-fingerprint-reparse' }
        $relative = $item.FullName.Substring($rootFull.Length + 1).Replace('\','/')
        if ($item.PSIsContainer) {
            $rows += ,[ordered]@{ path=$relative; kind='directory' }
        } else {
            $rows += ,[ordered]@{ path=$relative; kind='leaf'; bytes=[int64]$item.Length; sha256=Get-Hash $item.FullName }
        }
    }
    return (@($rows) | ConvertTo-Json -Depth 5 -Compress)
}

function Get-TestProtocolFingerprint {
    param($Fixture)
    $stateDirectory = Split-Path $Fixture.State -Parent
    $sourceLeaseRoot = Join-Path $Fixture.Root '__patch_drop__\source-edit-locks'
    return ([ordered]@{
        stateDirectory = Get-TestTreeFingerprint $stateDirectory
        sourceLeases = Get-TestTreeFingerprint $sourceLeaseRoot
    } | ConvertTo-Json -Depth 8 -Compress)
}

function Get-ByteHash {
    param([byte[]]$Bytes)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '') }
    finally { $sha.Dispose() }
}

function Copy-TestValue {
    param($Value)
    return (($Value | ConvertTo-Json -Depth 40 -Compress) | ConvertFrom-Json -ErrorAction Stop)
}

function Get-IndependentStateProjectionHash {
    param($StateObject)
    $projectionUnits = @()
    foreach ($unit in @($StateObject.workUnits)) {
        $unitCopy = Copy-TestValue $unit
        if ($null -ne $unitCopy.PSObject.Properties['transition']) { $unitCopy.PSObject.Properties.Remove('transition') }
        $projectionUnits += ,$unitCopy
    }
    $projection = [ordered]@{
        schemaVersion = [string]$StateObject.schemaVersion
        canonicalMarkdownPath = [string]$StateObject.canonicalMarkdownPath
        canonicalMarkdownSha256 = [string]$StateObject.canonicalMarkdownSha256
        sourceOwner = [string]$StateObject.sourceOwner
        activeSourceSets = @($StateObject.activeSourceSets)
        controllerManifest = Copy-TestValue $StateObject.controllerManifest
        directiveInventory = Copy-TestValue $StateObject.directiveInventory
        workUnits = @($projectionUnits)
        retirement = Copy-TestValue $StateObject.retirement
        desktopFinalProof = [string]$StateObject.desktopFinalProof
        runtimeLineageVerdict = [string]$StateObject.runtimeLineageVerdict
        deleteAuthorized = [bool]$StateObject.deleteAuthorized
    }
    $text = $projection | ConvertTo-Json -Depth 40 -Compress
    return Get-ByteHash $script:utf8.GetBytes($text)
}

function Get-IndependentRecordHash {
    param($Record)
    $ordered = [ordered]@{
        runId = [string]$Record.runId
        outcome = [string]$Record.outcome
        evidenceSha256 = [string]$Record.evidenceSha256
        targetPreimages = @($Record.targetPreimages)
        targetPostimages = @($Record.targetPostimages)
        redCommandId = $Record.PSObject.Properties['redCommandId'].Value
        greenCommandIds = @($Record.greenCommandIds)
        rollbackStatus = [string]$Record.rollbackStatus
        desktopFinalProof = [string]$Record.desktopFinalProof
    }
    return Get-ByteHash $script:utf8.GetBytes(($ordered | ConvertTo-Json -Depth 20 -Compress))
}

function Get-IndependentEventTexts {
    param($Row)
    $nullable = {
        param($Property)
        $value = $Property.Value
        if ($null -eq $value) { return 'null' }
        $text = [string]$value
        if ($text -notmatch '^[A-Za-z0-9._:-]+$') { throw 'oracle-nonascii-value' }
        return '"' + $text + '"'
    }
    foreach ($name in @('programId','canonicalMarkdownSha256','action','result','stateProjectionSha256','eventId','eventSha256','previousEventSha256','schemaVersion')) {
        $value = [string]$Row.$name
        if ($value -notmatch '^[A-Za-z0-9._:-]+$') { throw 'oracle-nonascii-value' }
    }
    $workUnit = & $nullable $Row.PSObject.Properties['workUnitId']
    $runHash = & $nullable $Row.PSObject.Properties['runIdSha256']
    $evidenceHash = & $nullable $Row.PSObject.Properties['evidenceSha256']
    $recordHash = & $nullable $Row.PSObject.Properties['recordSha256']
    $idInput = '{"programId":"' + [string]$Row.programId + '","canonicalMarkdownSha256":"' +
        [string]$Row.canonicalMarkdownSha256 + '","action":"' + [string]$Row.action + '","workUnitId":' +
        $workUnit + ',"runIdSha256":' + $runHash + ',"result":"' + [string]$Row.result +
        '","stateProjectionSha256":"' + [string]$Row.stateProjectionSha256 + '","evidenceSha256":' +
        $evidenceHash + ',"recordSha256":' + $recordHash + '}'
    $hashInput = '{"schemaVersion":"' + [string]$Row.schemaVersion + '","sequence":' +
        [string][int64]$Row.sequence + ',"previousEventSha256":"' + [string]$Row.previousEventSha256 +
        '","eventId":"' + [string]$Row.eventId + '","programId":"' + [string]$Row.programId +
        '","canonicalMarkdownSha256":"' + [string]$Row.canonicalMarkdownSha256 + '","action":"' +
        [string]$Row.action + '","workUnitId":' + $workUnit + ',"runIdSha256":' + $runHash +
        ',"result":"' + [string]$Row.result + '","stateProjectionSha256":"' +
        [string]$Row.stateProjectionSha256 + '","evidenceSha256":' + $evidenceHash +
        ',"recordSha256":' + $recordHash + '}'
    $rowText = '{"schemaVersion":"' + [string]$Row.schemaVersion + '","sequence":' +
        [string][int64]$Row.sequence + ',"previousEventSha256":"' + [string]$Row.previousEventSha256 +
        '","eventId":"' + [string]$Row.eventId + '","eventSha256":"' + [string]$Row.eventSha256 +
        '","programId":"' + [string]$Row.programId + '","canonicalMarkdownSha256":"' +
        [string]$Row.canonicalMarkdownSha256 + '","action":"' + [string]$Row.action +
        '","workUnitId":' + $workUnit + ',"runIdSha256":' + $runHash + ',"result":"' +
        [string]$Row.result + '","stateProjectionSha256":"' + [string]$Row.stateProjectionSha256 +
        '","evidenceSha256":' + $evidenceHash + ',"recordSha256":' + $recordHash + '}'
    return [pscustomobject]@{ idInput=$idInput; hashInput=$hashInput; rowText=$rowText }
}

function Copy-ControllerForFixture {
    param([string]$Destination)

    $text = [IO.File]::ReadAllText($script:sourceController, $script:utf8)
    $rootLine = '$CanonicalRoot = ''C:\AbandonWare\demo-1\demo-1\src'''
    $rootMatches = [regex]::Matches($text, '(?m)^' + [regex]::Escape($rootLine) + '$')
    if ($rootMatches.Count -ne 1) { throw 'unexpected-canonical-root-assignment' }
    $escapedRoot = $script:fixtureRoot.Replace("'", "''")
    $copyText = $text.Replace($rootLine, "`$CanonicalRoot = '$escapedRoot'")

    $dotSourceGuardMatches = [regex]::Matches($copyText, '(?ms)^if \(\$MyInvocation\.InvocationName -eq ''\.''\) \{\r?\n    throw ''dot-source-not-supported''\r?\n\}')
    if ($dotSourceGuardMatches.Count -ne 1) {
        throw 'unexpected-dot-source-guard-shape'
    }
    $probeGuard = "if (`$MyInvocation.InvocationName -eq '.' -and `$env:AWX_EVENT_PURE_PROBE -cne '1') {`r`n    throw 'dot-source-not-supported'`r`n}"
    $copyText = $copyText.Remove($dotSourceGuardMatches[0].Index, $dotSourceGuardMatches[0].Length).Insert($dotSourceGuardMatches[0].Index, $probeGuard)
    if ([regex]::Matches($copyText, '(?m)^if \(\$MyInvocation\.InvocationName -eq ''\.'' -and \$env:AWX_EVENT_PURE_PROBE -cne ''1''\) \{\r?$').Count -ne 1) {
        throw 'unexpected-dot-source-guard-shape'
    }

    $gateLine = '    Assert-CanonicalManifest $manifest $programFile.text.Substring(0, $beginMatches[0].Index)'
    $gateMatches = [regex]::Matches($copyText, '(?m)^' + [regex]::Escape($gateLine) + '\r?$')
    if ($gateMatches.Count -ne 1) { throw 'unexpected-canonical-gate-shape' }
    $conditionalGate = "    if (Test-HasProperty `$manifest 'programId') {`r`n        Assert-CanonicalManifest `$manifest `$programFile.text.Substring(0, `$beginMatches[0].Index)`r`n    }"
    $copyText = $copyText.Replace($gateLine, $conditionalGate)
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($gateLine) + '\r?$').Count -ne 0 -or
        [regex]::Matches($copyText, '(?m)^    if \(Test-HasProperty \$manifest ''programId''\) \{\r?$').Count -ne 1) {
        throw 'unexpected-canonical-gate-shape'
    }
    $terminalCasLine = '    return Publish-StateAtomically -RootPath $root -DestinationPath $state -ContentBytes $bytes -FinalGate {'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($terminalCasLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-terminal-cas-shape'
    }
    $terminalHook = "    if (`$Phase -ceq 'terminal' -and (Test-Path -LiteralPath (Join-Path `$root '.event-test-fail-terminal'))) { throw 'state-hash-changed' }`r`n$terminalCasLine"
    $copyText = $copyText.Replace($terminalCasLine, $terminalHook)
    if ([regex]::Matches($copyText, '(?m)^    if \(\$Phase -ceq ''terminal'' -and \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-fail-terminal''\)\)\) \{ throw ''state-hash-changed'' \}\r?$').Count -ne 1) {
        throw 'unexpected-terminal-cas-shape'
    }
    $syncRowsLine = '        $expectedRows = @(Get-ExpectedEventRows $validated.state $validated.manifest)'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($syncRowsLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-event-sync-shape'
    }
    $syncFailureHook = $syncRowsLine + "`r`n        if (Test-Path -LiteralPath (Join-Path `$root '.event-test-fail-sync-native')) { throw 'syn-native-event-exception-83' }`r`n        if (`$expectedRows.Count -eq 1 -and (Test-Path -LiteralPath (Join-Path `$root '.event-test-fail-snapshot-sync'))) { throw 'event-lock-failed' }`r`n        if (`$expectedRows.Count -ge 2 -and (Test-Path -LiteralPath (Join-Path `$root '.event-test-fail-sync'))) { throw 'event-lock-failed' }"
    $copyText = $copyText.Replace($syncRowsLine, $syncFailureHook)
    if ([regex]::Matches($copyText, '(?m)^        if \(\$expectedRows\.Count -ge 2 -and \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-fail-sync''\)\)\) \{ throw ''event-lock-failed'' \}\r?$').Count -ne 1) {
        throw 'unexpected-event-sync-shape'
    }
    if ([regex]::Matches($copyText, '(?m)^        if \(\$expectedRows\.Count -eq 1 -and \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-fail-snapshot-sync''\)\)\) \{ throw ''event-lock-failed'' \}\r?$').Count -ne 1) {
        throw 'unexpected-event-sync-shape'
    }
    if ([regex]::Matches($copyText, '(?m)^        if \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-fail-sync-native''\)\) \{ throw ''syn-native-event-exception-83'' \}\r?$').Count -ne 1) {
        throw 'unexpected-event-sync-shape'
    }
    $appendLine = '                $stream.Write($rowBytes, 0, $rowBytes.Length)'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($appendLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-event-append-shape'
    }
    $appendHook = @'
                if (Test-Path -LiteralPath (Join-Path $root '.event-test-fail-append-wrong-suffix')) {
                    $partialLength = [Math]::Min(17, $rowBytes.Length)
                    $wrongSuffix = [Array]::CreateInstance([byte], $partialLength)
                    for ($wrongIndex = 0; $wrongIndex -lt $wrongSuffix.Length; $wrongIndex++) { $wrongSuffix[$wrongIndex] = 0x5A }
                    $stream.Write($wrongSuffix, 0, $wrongSuffix.Length)
                    $stream.Flush($true)
                    throw [IO.IOException]::new('event-test-append-wrong-suffix')
                }
                if (Test-Path -LiteralPath (Join-Path $root '.event-test-fail-append-unproven')) {
                    $partialLength = [Math]::Min(17, $rowBytes.Length)
                    $stream.Write($rowBytes, 0, $partialLength)
                    $null = $stream.Seek(0, [IO.SeekOrigin]::Begin)
                    $stream.Write([byte[]]@(0x58), 0, 1)
                    $stream.Flush($true)
                    throw [IO.IOException]::new('event-test-append-unproven')
                }
                if (Test-Path -LiteralPath (Join-Path $root '.event-test-fail-append')) {
                    $partialLength = [Math]::Min(17, $rowBytes.Length)
                    $stream.Write($rowBytes, 0, $partialLength)
                    $stream.Flush($true)
                    throw [IO.IOException]::new('event-test-append-failed')
                }
                $stream.Write($rowBytes, 0, $rowBytes.Length)
'@.TrimEnd("`r","`n")
    $copyText = $copyText.Replace($appendLine, $appendHook)
    if ([regex]::Matches($copyText, '(?m)^                if \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-fail-append''\)\) \{\r?$').Count -ne 1) {
        throw 'unexpected-event-append-shape'
    }
    if ([regex]::Matches($copyText, '(?m)^                if \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-fail-append-unproven''\)\) \{\r?$').Count -ne 1) {
        throw 'unexpected-event-append-shape'
    }
    if ([regex]::Matches($copyText, '(?m)^                if \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-fail-append-wrong-suffix''\)\) \{\r?$').Count -ne 1) {
        throw 'unexpected-event-append-shape'
    }
    $removeCreatedLine = '            if ($removeCreatedFile) {'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($removeCreatedLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-created-ledger-cleanup-shape'
    }
    $replacementHook = $removeCreatedLine + "`r`n                if (Test-Path -LiteralPath (Join-Path `$root '.event-test-replace-created-before-delete')) {`r`n                    [IO.File]::Delete(`$path)`r`n                    [IO.File]::WriteAllBytes(`$path, `$Utf8.GetBytes('replacement-ledger-sentinel'))`r`n                }"
    $copyText = $copyText.Replace($removeCreatedLine, $replacementHook)
    if ([regex]::Matches($copyText, '(?m)^                if \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-replace-created-before-delete''\)\) \{\r?$').Count -ne 1) {
        throw 'unexpected-created-ledger-cleanup-shape'
    }
    $streamOpenLine = '        $stream = [IO.FileStream]::new($path, $mode, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None, 4096, [IO.FileOptions]::WriteThrough)'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($streamOpenLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-event-stream-open-shape'
    }
    $prefixTamperHook = "        if (Test-Path -LiteralPath (Join-Path `$root '.event-test-tamper-prefix-before-open')) {`r`n            `$tamperedPrefix = [IO.File]::ReadAllBytes(`$path)`r`n            if (`$tamperedPrefix.Length -lt 1) { throw 'event-test-prefix-empty' }`r`n            `$tamperedPrefix[0] = [byte](`$tamperedPrefix[0] -bxor 1)`r`n            [IO.File]::WriteAllBytes(`$path, `$tamperedPrefix)`r`n        }`r`n" + $streamOpenLine
    $copyText = $copyText.Replace($streamOpenLine, $prefixTamperHook)
    if ([regex]::Matches($copyText, '(?m)^        if \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-tamper-prefix-before-open''\)\) \{\r?$').Count -ne 1) {
        throw 'unexpected-event-stream-open-shape'
    }
    $firstReadLine = '    $first = Get-ReadOnlyEventSnapshot'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($firstReadLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-event-read-shape'
    }
    $readLockAppearanceHook = $firstReadLine + "`r`n    if (Test-Path -LiteralPath (Join-Path `$root '.event-test-create-read-lock')) { [IO.File]::WriteAllBytes(`$lockPath, [byte[]]@()) }"
    $copyText = $copyText.Replace($firstReadLine, $readLockAppearanceHook)
    if ([regex]::Matches($copyText, '(?m)^    if \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-create-read-lock''\)\) \{ \[IO\.File\]::WriteAllBytes\(\$lockPath, \[byte\[\]\]@\(\)\) \}\r?$').Count -ne 1) {
        throw 'unexpected-event-read-shape'
    }
    $snapshotRowBoundLine = '    $null = Get-CanonicalEventRowBytes $rows[0]'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($snapshotRowBoundLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-snapshot-event-bound-shape'
    }
    $snapshotRowBoundHook = "    if (Test-Path -LiteralPath (Join-Path `$root '.event-test-snapshot-row-too-large')) { `$rows[0].programId = ('X' * 4097) }`r`n" + $snapshotRowBoundLine
    $copyText = $copyText.Replace($snapshotRowBoundLine, $snapshotRowBoundHook)
    if ([regex]::Matches($copyText, '(?m)^    if \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-snapshot-row-too-large''\)\) \{ \$rows\[0\]\.programId = \(''X'' \* 4097\) \}\r?$').Count -ne 1) {
        throw 'unexpected-snapshot-event-bound-shape'
    }
    $prospectiveRowLine = '    $nextRow = $proposedRows[$proposedRows.Count - 1]'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($prospectiveRowLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-prospective-event-bound-shape'
    }
    $prospectiveRowHook = $prospectiveRowLine + "`r`n    `$rowBoundMarker = Join-Path `$root ('.event-test-' + `$Phase + '-row-too-large')`r`n    if (Test-Path -LiteralPath `$rowBoundMarker) { `$nextRow.programId = ('X' * 4097) }`r`n    `$rowCountMarker = Join-Path `$root ('.event-test-' + `$Phase + '-row-count-exceeded')`r`n    if (Test-Path -LiteralPath `$rowCountMarker) {`r`n        while (`$proposedRows.Count -le 1 + 2 * @(`$ProposedState.workUnits).Count) { `$proposedRows += ,`$nextRow }`r`n    }"
    $copyText = $copyText.Replace($prospectiveRowLine, $prospectiveRowHook)
    if ([regex]::Matches($copyText, '(?m)^    \$rowBoundMarker = Join-Path \$root \(''\.event-test-'' \+ \$Phase \+ ''-row-too-large''\)\r?$').Count -ne 1 -or
        [regex]::Matches($copyText, '(?m)^    \$rowCountMarker = Join-Path \$root \(''\.event-test-'' \+ \$Phase \+ ''-row-count-exceeded''\)\r?$').Count -ne 1) {
        throw 'unexpected-prospective-event-bound-shape'
    }
    $prospectiveLedgerLine = '        $ledger = Read-ValidatedEventLedger $currentRows @($CurrentState.workUnits).Count'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($prospectiveLedgerLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-prospective-event-ledger-bound-shape'
    }
    $prospectiveLedgerHook = $prospectiveLedgerLine + "`r`n        `$ledgerBoundMarker = Join-Path `$root ('.event-test-' + `$Phase + '-ledger-too-large')`r`n        if (Test-Path -LiteralPath `$ledgerBoundMarker) { `$ledger.bytes = [Array]::CreateInstance([byte], 256KB) }"
    $copyText = $copyText.Replace($prospectiveLedgerLine, $prospectiveLedgerHook)
    if ([regex]::Matches($copyText, '(?m)^        \$ledgerBoundMarker = Join-Path \$root \(''\.event-test-'' \+ \$Phase \+ ''-ledger-too-large''\)\r?$').Count -ne 1) {
        throw 'unexpected-prospective-event-ledger-bound-shape'
    }
    $casAdapterLine = '        CompareAndSwapState = { param($ExpectedHash, $StateObject, $Phase) Write-UpdatedState $StateObject $ExpectedHash $Phase }'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($casAdapterLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-public-cas-adapter-shape'
    }
    $casAdapterHook = @'
        CompareAndSwapState = {
            param($ExpectedHash, $StateObject, $Phase)
            $candidate = $StateObject
            $checkpointTamperMarker = Join-Path $root ('.event-test-tamper-' + $Phase + '-checkpoint')
            if (Test-Path -LiteralPath $checkpointTamperMarker) {
                $candidate = Copy-ControllerValue $StateObject
                $candidate.eventCheckpoint.eventSha256 = ('F' * 64)
            }
            Write-UpdatedState $candidate $ExpectedHash $Phase
        }
'@
    $copyText = $copyText.Replace($casAdapterLine, $casAdapterHook.TrimEnd("`r","`n"))
    if ([regex]::Matches($copyText, '(?m)^            \$checkpointTamperMarker = Join-Path \$root \(''\.event-test-tamper-'' \+ \$Phase \+ ''-checkpoint''\)\r?$').Count -ne 1) {
        throw 'unexpected-public-cas-adapter-shape'
    }
    $eventLockFunctionLine = 'function Enter-EventLock {'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($eventLockFunctionLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-event-lock-function-shape'
    }
    $eventLockReadyHook = $eventLockFunctionLine + "`r`n    if (Test-Path -LiteralPath (Join-Path `$root '.event-test-enable-lock-trace')) {`r`n        `$otherHeld = if (`$null -ne `$script:stateCasLockStream) { 1 } else { 0 }`r`n        [IO.File]::AppendAllText((Join-Path `$root '.event-test-lock-trace'), ('event|' + `$otherHeld + [char]0x0A), `$Utf8)`r`n    }`r`n    if (-not [string]::IsNullOrWhiteSpace(`$env:AWX_EVENT_LOCK_READY)) {`r`n        `$readyPath = [IO.Path]::GetFullPath(`$env:AWX_EVENT_LOCK_READY)`r`n        if (-not (Test-InRoot `$root `$readyPath)) { throw 'event-lock-invalid' }`r`n        [IO.File]::WriteAllText(`$readyPath, [string]`$PID, `$Utf8)`r`n    }"
    $copyText = $copyText.Replace($eventLockFunctionLine, $eventLockReadyHook)
    if ([regex]::Matches($copyText, '(?m)^        \[IO\.File\]::WriteAllText\(\$readyPath, \[string\]\$PID, \$Utf8\)\r?$').Count -ne 1) {
        throw 'unexpected-event-lock-function-shape'
    }
    if ([regex]::Matches($copyText, '(?m)^        \[IO\.File\]::AppendAllText\(\(Join-Path \$root ''\.event-test-lock-trace''\), \(''event\|'' \+ \$otherHeld \+ \[char\]0x0A\), \$Utf8\)\r?$').Count -ne 1) {
        throw 'unexpected-event-lock-function-shape'
    }
    $stateLockFunctionLine = 'function Enter-StateCasLock {'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($stateLockFunctionLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-state-lock-function-shape'
    }
    $stateLockParamLine = '    param([string]$RootPath, [string]$DestinationPath)'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($stateLockParamLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-state-lock-function-shape'
    }
    $stateLockReadyHook = $stateLockParamLine + "`r`n    if (Test-Path -LiteralPath (Join-Path `$root '.event-test-enable-lock-trace')) {`r`n        `$otherHeld = if (`$null -ne `$script:eventLockStream) { 1 } else { 0 }`r`n        [IO.File]::AppendAllText((Join-Path `$root '.event-test-lock-trace'), ('state|' + `$otherHeld + [char]0x0A), `$Utf8)`r`n    }`r`n    if (-not [string]::IsNullOrWhiteSpace(`$env:AWX_STATE_LOCK_READY)) {`r`n        `$readyPath = [IO.Path]::GetFullPath(`$env:AWX_STATE_LOCK_READY)`r`n        if (-not (Test-InRoot `$root `$readyPath)) { throw 'state-cas-lock-invalid' }`r`n        [IO.File]::WriteAllText(`$readyPath, [string]`$PID, `$Utf8)`r`n    }"
    $copyText = $copyText.Replace($stateLockParamLine, $stateLockReadyHook)
    if ([regex]::Matches($copyText, '(?m)^        \[IO\.File\]::WriteAllText\(\$readyPath, \[string\]\$PID, \$Utf8\)\r?$').Count -ne 2) {
        throw 'unexpected-state-lock-function-shape'
    }
    if ([regex]::Matches($copyText, '(?m)^        \[IO\.File\]::AppendAllText\(\(Join-Path \$root ''\.event-test-lock-trace''\), \(''state\|'' \+ \$otherHeld \+ \[char\]0x0A\), \$Utf8\)\r?$').Count -ne 1) {
        throw 'unexpected-state-lock-function-shape'
    }
    $adoptStatePublishLine = '    return Publish-StateAtomically -RootPath $root -DestinationPath $state -ContentBytes $bytes -FinalGate $finalGate -ExpectedHash $ExpectedHash -PostwriteGate $postwriteGate -RollbackGate $rollbackGate'
    $adoptStatePublishMatches = [regex]::Matches($copyText, '(?m)^' + [regex]::Escape($adoptStatePublishLine) + '\r?$')
    if ($adoptStatePublishMatches.Count -eq 1) {
        $adoptStateHook = "    if (Test-Path -LiteralPath (Join-Path `$root '.event-test-fail-adopt-state')) { throw 'state-hash-changed' }`r`n" + $adoptStatePublishLine
        $copyText = $copyText.Replace($adoptStatePublishLine, $adoptStateHook)
    } elseif ($copyText -match "'Adopt'") {
        throw 'unexpected-adopt-state-publish-shape'
    }
    $adoptAppendLine = '                $stream.Write($bundleBytes, 0, $bundleBytes.Length)'
    $adoptAppendMatches = [regex]::Matches($copyText, '(?m)^' + [regex]::Escape($adoptAppendLine) + '\r?$')
    if ($adoptAppendMatches.Count -eq 1) {
        $adoptAppendHook = @'
                if (Test-Path -LiteralPath (Join-Path $root '.event-test-fail-adopt-append')) {
                    $partialLength = [Math]::Min(17, $bundleBytes.Length)
                    $stream.Write($bundleBytes, 0, $partialLength)
                    throw [IO.IOException]::new('simulated-adopt-append-failure')
                }
'@
        $copyText = $copyText.Replace($adoptAppendLine, $adoptAppendHook.TrimEnd("`r","`n") + "`r`n" + $adoptAppendLine)
    } elseif ($copyText -match "'Adopt'") {
        throw 'unexpected-adopt-event-append-shape'
    }
    $syncFunctionLine = 'function Sync-EventLedger {'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($syncFunctionLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-event-sync-function-shape'
    }
    $delayedSyncHook = $syncFunctionLine + @'

    $syncCounter = Get-Variable -Scope Script -Name eventTestSyncInvocationCount -ErrorAction SilentlyContinue
    if ($null -eq $syncCounter) { $script:eventTestSyncInvocationCount = 0 }
    $script:eventTestSyncInvocationCount++
    $delayMarker = Join-Path $root '.event-test-delay-begin-publisher'
    if ($Action -ceq 'Begin' -and $script:eventTestSyncInvocationCount -eq 2 -and (Test-Path -LiteralPath $delayMarker)) {
        [IO.File]::WriteAllText((Join-Path $root '.event-test-delay-begin-ready'), [string]$PID, $Utf8)
        $delayWatch = [Diagnostics.Stopwatch]::StartNew()
        try {
            while (-not (Test-Path -LiteralPath (Join-Path $root '.event-test-delay-begin-release'))) {
                if ($delayWatch.ElapsedMilliseconds -ge 120000) { throw 'event-lock-timeout' }
                Start-Sleep -Milliseconds 25
            }
        } finally { $delayWatch.Stop() }
    }
    $recordDelayMarker = Join-Path $root '.event-test-delay-record-publisher'
    if ($Action -ceq 'Record' -and $script:eventTestSyncInvocationCount -eq 2 -and (Test-Path -LiteralPath $recordDelayMarker)) {
        [IO.File]::WriteAllText((Join-Path $root '.event-test-delay-record-ready'), [string]$PID, $Utf8)
        $recordDelayWatch = [Diagnostics.Stopwatch]::StartNew()
        try {
            while (-not (Test-Path -LiteralPath (Join-Path $root '.event-test-delay-record-release'))) {
                if ($recordDelayWatch.ElapsedMilliseconds -ge 120000) { throw 'event-lock-timeout' }
                Start-Sleep -Milliseconds 25
            }
        } finally { $recordDelayWatch.Stop() }
    }
'@
    $copyText = $copyText.Replace($syncFunctionLine, $delayedSyncHook.TrimEnd("`r","`n"))
    if ([regex]::Matches($copyText, '(?m)^    if \(\$Action -ceq ''Begin'' -and \$script:eventTestSyncInvocationCount -eq 2 -and \(Test-Path -LiteralPath \$delayMarker\)\) \{\r?$').Count -ne 1) {
        throw 'unexpected-event-sync-function-shape'
    }
    if ([regex]::Matches($copyText, '(?m)^    if \(\$Action -ceq ''Record'' -and \$script:eventTestSyncInvocationCount -eq 2 -and \(Test-Path -LiteralPath \$recordDelayMarker\)\) \{\r?$').Count -ne 1) {
        throw 'unexpected-event-sync-function-shape'
    }
    $snapshotShouldProcessLine = "    if (-not `$PSCmdlet.ShouldProcess((Get-RelativePath `$root `$state), 'create controller state')) {"
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($snapshotShouldProcessLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-snapshot-should-process-shape'
    }
    $snapshotDeclineHook = "    if ((Test-Path -LiteralPath (Join-Path `$root '.event-test-decline-snapshot')) -or -not `$PSCmdlet.ShouldProcess((Get-RelativePath `$root `$state), 'create controller state')) {"
    $copyText = $copyText.Replace($snapshotShouldProcessLine, $snapshotDeclineHook)
    if ([regex]::Matches($copyText, '(?m)^    if \(\(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-decline-snapshot''\)\) -or -not \$PSCmdlet\.ShouldProcess').Count -ne 1) {
        throw 'unexpected-snapshot-should-process-shape'
    }
    $legacyWriterParamLine = "    param([string]`$ActionName, [string]`$UnitId, [string]`$EventRunId, [string]`$Result, [string]`$EvidenceHash = '', [string]`$DecisionKind = '', [string]`$ReferenceHash = '')"
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($legacyWriterParamLine) + '\r?$').Count -ne 1) {
        throw 'unexpected-legacy-writer-shape'
    }
    $legacyWriterHook = $legacyWriterParamLine + "`r`n    if (Test-Path -LiteralPath (Join-Path `$root '.event-test-fail-legacy-writer')) { throw 'legacy-event-writer-reached' }"
    $copyText = $copyText.Replace($legacyWriterParamLine, $legacyWriterHook)
    if ([regex]::Matches($copyText, '(?m)^    if \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-fail-legacy-writer''\)\) \{ throw ''legacy-event-writer-reached'' \}\r?$').Count -ne 1) {
        throw 'unexpected-legacy-writer-shape'
    }
    $targetSnapshotMatches = [regex]::Matches($copyText, '(?m)^function Get-PublicCoreTargetSnapshot \{\r?\n    param\(\$Request\)\r?$')
    if ($targetSnapshotMatches.Count -ne 1) {
        throw 'unexpected-public-target-snapshot-shape'
    }
    $targetSnapshotAnchor = $targetSnapshotMatches[0].Value.TrimEnd("`r","`n")
    $targetSnapshotHook = $targetSnapshotAnchor + "`r`n    if (`$Request.mode -ceq 'preimage' -and (Test-Path -LiteralPath (Join-Path `$root '.event-test-fail-target-capture'))) { throw 'event-test-capture-failed' }"
    $copyText = $copyText.Replace($targetSnapshotAnchor, $targetSnapshotHook)
    if ([regex]::Matches($copyText, '(?m)^    if \(\$Request\.mode -ceq ''preimage'' -and \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-fail-target-capture''\)\)\) \{ throw ''event-test-capture-failed'' \}\r?$').Count -ne 1) {
        throw 'unexpected-public-target-snapshot-shape'
    }
    $leaseAbsenceMatches = [regex]::Matches($copyText, '(?m)^function Get-PublicCoreLeaseAbsenceEnvelope \{\r?\n    param\(\$Request\)\r?$')
    if ($leaseAbsenceMatches.Count -ne 1) {
        throw 'unexpected-public-lease-absence-shape'
    }
    $leaseAbsenceAnchor = $leaseAbsenceMatches[0].Value.TrimEnd("`r","`n")
    $leaseAbsenceHook = $leaseAbsenceAnchor + "`r`n    if (Test-Path -LiteralPath (Join-Path `$root '.event-test-fail-cleanup-absence')) {`r`n        return [pscustomobject][ordered]@{ isAbsent=`$false; topic=[string]`$Request.topic; ownerId=[string]`$Request.ownerId; root=`$CanonicalRoot }`r`n    }"
    $copyText = $copyText.Replace($leaseAbsenceAnchor, $leaseAbsenceHook)
    if ([regex]::Matches($copyText, '(?m)^    if \(Test-Path -LiteralPath \(Join-Path \$root ''\.event-test-fail-cleanup-absence''\)\) \{\r?$').Count -ne 1) {
        throw 'unexpected-public-lease-absence-shape'
    }
    $mainEntryMatches = [regex]::Matches($copyText, '(?m)^function Invoke-ControllerMain \{\r?\n    try \{\r?$')
    if ($mainEntryMatches.Count -ne 1) {
        throw 'unexpected-controller-main-shape'
    }
    $envelopeProbeHook = @'
function Invoke-ControllerMain {
    try {
        if (-not [string]::IsNullOrWhiteSpace($env:AWX_EVENT_ENVELOPE_PROBE)) {
            $probeParts = @($env:AWX_EVENT_ENVELOPE_PROBE.Split([char]0x7C))
            if ($probeParts.Count -ne 2 -or [string]$probeParts[0] -cnotin @('pre','post')) { throw 'event-schema-invalid' }
            if ([string]$probeParts[0] -ceq 'post') {
                Set-EventCommittedContext 'WU-EVENT-A' ('A' * 64) ('B' * 64)
            }
            if ([string]$probeParts[1] -ceq 'native') {
                $script:controllerStage = 'event-lock'
                throw 'SK_SECRET_NATIVE_ENVELOPE_93'
            }
            throw [string]$probeParts[1]
        }
'@
    $copyText = $copyText.Remove($mainEntryMatches[0].Index, $mainEntryMatches[0].Length).Insert(
        $mainEntryMatches[0].Index,
        $envelopeProbeHook.TrimEnd("`r","`n")
    )
    if ([regex]::Matches($copyText, '(?m)^        if \(-not \[string\]::IsNullOrWhiteSpace\(\$env:AWX_EVENT_ENVELOPE_PROBE\)\) \{\r?$').Count -ne 1 -or
        [regex]::Matches($copyText, '(?m)^                throw ''SK_SECRET_NATIVE_ENVELOPE_93''\r?$').Count -ne 1) {
        throw 'unexpected-controller-main-shape'
    }
    Write-Utf8 $Destination $copyText
}

function Copy-CoreForFixture {
    param([string]$Destination)

    $text = [IO.File]::ReadAllText($script:sourceCore, $script:utf8)
    $rootLine = '$script:CanonicalRoot = ''C:\AbandonWare\demo-1\demo-1\src'''
    $rootMatches = [regex]::Matches($text, '(?m)^' + [regex]::Escape($rootLine) + '$')
    if ($rootMatches.Count -ne 1) { throw 'unexpected-core-root-assignment' }
    $escapedRoot = $script:fixtureRoot.Replace("'", "''")
    Write-Utf8 $Destination ($text.Replace($rootLine, "`$script:CanonicalRoot = '$escapedRoot'"))
}

function New-PublicEventsFixture {
    $script:fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-public-events-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $script:fixtureRoot -ErrorAction Stop | Out-Null

    $scripts = Join-Path $script:fixtureRoot '.agents\skills\demo1-consolidating-notebook-directives\scripts'
    $patchDrop = Join-Path $script:fixtureRoot '__patch_drop__'
    New-Item -ItemType Directory -Path $scripts, $patchDrop -Force -ErrorAction Stop | Out-Null
    $script:fixtureController = Join-Path $scripts 'invoke_consolidated_program.ps1'
    Copy-ControllerForFixture $script:fixtureController
    Copy-CoreForFixture (Join-Path $scripts 'consolidated_program_core.psm1')
    Copy-Item -LiteralPath $script:sourceSession -Destination (Join-Path $patchDrop 'source_edit_session.ps1')
    Copy-Item -LiteralPath $script:sourceLeaseContract -Destination (Join-Path $patchDrop 'source_edit_lease_contract.ps1')
    Assert-Equal (Get-Hash (Join-Path $patchDrop 'source_edit_session.ps1')) $script:sourceSessionHash 'temp source-session copy changed'
    Assert-Equal (Get-Hash (Join-Path $patchDrop 'source_edit_lease_contract.ps1')) $script:sourceLeaseContractHash 'temp lease-contract copy changed'

    $candidateRelative = 'data/agent-handoff/notebook/input.md'
    $candidatePath = Join-Path $script:fixtureRoot ($candidateRelative.Replace('/', '\'))
    Write-Utf8 $candidatePath 'event fixture directive'
    $candidateHash = Get-Hash $candidatePath
    $candidateBytes = [IO.File]::ReadAllBytes($candidatePath).Length
    $greenTargetRelative = 'data/event-green-target.txt'
    $greenTargetPath = Join-Path $script:fixtureRoot ($greenTargetRelative.Replace('/', '\'))
    Write-Utf8 $greenTargetPath 'green target before'

    $inventory = [ordered]@{
        schemaVersion = 'demo1.notebook-directive-inventory.v1'
        canonicalExecutionRoot = $script:fixtureRoot
        candidateRoots = @('data/agent-handoff/notebook','__patch_drop__/notebook','agent-prompts')
        candidates = @([ordered]@{
            path = $candidateRelative; sha256 = $candidateHash; bytes = [int64]$candidateBytes; gitTracking = 'untracked'
            provenance = 'notebook'; format = 'markdown'; directiveIds = @(); targetFiles = @(); inclusionReason = 'standalone-notebook-directive'
        })
        excluded = @([ordered]@{
            path = 'agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md'; reason = 'reusable-prompt'
        })
    }
    $eventCommand = 'powershell -NoProfile -Command event-fixture-green'
    $units = @(
        [ordered]@{
            workUnitId = 'WU-EVENT-A'; status = 'pending'; dependencies = @(); required = $true; kind = 'verification'
            targetFiles = @(); redCommands = @(); greenCommands = @($eventCommand); retirementCoverage = @($candidateRelative)
        },
        [ordered]@{
            workUnitId = 'WU-EVENT-B'; status = 'pending'; dependencies = @('WU-EVENT-A'); required = $false; kind = 'verification'
            targetFiles = @(); redCommands = @(); greenCommands = @($eventCommand); retirementCoverage = @()
        },
        [ordered]@{
            workUnitId = 'WU-EVENT-C'; status = 'pending'; dependencies = @('WU-EVENT-A'); required = $false; kind = 'verification'
            targetFiles = @(); redCommands = @(); greenCommands = @($eventCommand); retirementCoverage = @()
        }
    )
    $redCommand = 'powershell -NoProfile -Command event-fixture-red'
    $units += [ordered]@{
        workUnitId = 'WU-EVENT-D'; status = 'pending'; dependencies = @('WU-EVENT-A'); required = $false; kind = 'verification'
        targetFiles = @($greenTargetRelative); redCommands = @($redCommand); greenCommands = @($eventCommand); retirementCoverage = @()
    }
    if ($AdoptOnly) {
        $units = @([ordered]@{
            workUnitId = 'WU-EVENT-ADOPT'; status = 'pending'; dependencies = @(); required = $true; kind = 'verification'
            targetFiles = @($greenTargetRelative); redCommands = @(); greenCommands = @($eventCommand); retirementCoverage = @($candidateRelative)
        })
    }
    $retirement = [ordered]@{
        schemaVersion = 'demo1.notebook-directive-retirement.v1'; deleteAuthorized = $false
        canonicalDirectivePath = 'agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md'
        canonicalDirectiveSha256Evidence = 'external-final-output'; allRequiredWorkUnitsGreen = $false
        desktopFinalProof = 'evidence_needed'; status = 'hold'
        items = @([ordered]@{
            path = $candidateRelative; sha256 = $candidateHash; bytes = [int64]$candidateBytes; gitTracking = 'untracked'
            coverage = @($(if ($AdoptOnly) { 'WU-EVENT-ADOPT' } else { 'WU-EVENT-A' })); eligibility = 'hold'; holdReason = 'desktop-work-unit-proof-pending'
            deletionResult = 'not_run'; status = 'hold'
        })
    }
    $manifest = [ordered]@{
        schemaVersion = 'awx.notebook.directive.controller-manifest.v1'
        directiveInventory = $inventory
        workUnits = $units
        retirement = $retirement
    }
    $program = Join-Path $script:fixtureRoot 'agent-prompts\awx_desktop_notebook_consolidated_source_directive_20260806.md'
    $programText = 'fixture' + "`n" +
        '<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-BEGIN -->' + "`n" +
        '```json' + "`n" + ($manifest | ConvertTo-Json -Depth 30) + "`n" + '```' + "`n" +
        '<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-END -->'
    Write-Utf8 $program $programText

    & git -C $script:fixtureRoot init --quiet 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'fixture-git-init-failed' }
    & git -C $script:fixtureRoot add -- $greenTargetRelative 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'fixture-git-add-failed' }
    & git -C $script:fixtureRoot -c user.name=event-fixture -c user.email=event-fixture.invalid commit --quiet -m baseline 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'fixture-git-commit-failed' }

    $base = Join-Path $script:fixtureRoot 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806'
    return [pscustomobject]@{
        Root = $script:fixtureRoot
        Program = $program
        State = Join-Path $base 'program-state.json'
        Events = Join-Path $base 'events.jsonl'
        EventLock = Join-Path $base 'events.jsonl.lock'
        GreenTarget = $greenTargetPath
        EventCommand = $eventCommand
    }
}

function Invoke-Controller {
    param([string[]]$Arguments)
    $prior = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $script:pwsh -NoProfile -ExecutionPolicy Bypass -File $script:fixtureController @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prior
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Text = ($lines -join "`n") }
}

function Read-Result {
    param($Invocation)
    $lines = @($Invocation.Text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($lines.Count -ne 1) { throw 'unexpected-controller-output' }
    return $lines[0] | ConvertFrom-Json -ErrorAction Stop
}

function Write-ControllerWorkerScript {
    param([string]$Path)
    Write-Utf8 $Path @'
param([string]$RequestPath, [string]$ResultPath, [string]$StartedPath)
$ErrorActionPreference = 'Stop'
$utf8 = New-Object Text.UTF8Encoding($false, $true)
try {
    $request = [IO.File]::ReadAllText($RequestPath, $utf8) | ConvertFrom-Json -ErrorAction Stop
    if ([string]$request.readyKind -ceq 'event') { $env:AWX_EVENT_LOCK_READY = [string]$request.lockReadyPath }
    elseif ([string]$request.readyKind -ceq 'state') { $env:AWX_STATE_LOCK_READY = [string]$request.lockReadyPath }
    [IO.File]::WriteAllText($StartedPath, [string]$PID, $utf8)
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& ([string]$request.powershell) -NoProfile -ExecutionPolicy Bypass -File ([string]$request.controller) @($request.arguments) 2>&1)
        $controllerExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    $result = [ordered]@{ exitCode=[int]$controllerExit; text=($lines -join "`n") }
    [IO.File]::WriteAllText($ResultPath, ($result | ConvertTo-Json -Compress), $utf8)
    exit 0
} catch {
    [IO.File]::WriteAllText($ResultPath, ([ordered]@{ exitCode=-999; text='worker-failed' } | ConvertTo-Json -Compress), $utf8)
    exit 2
}
'@
}

function Start-ControllerWorker {
    param(
        $Fixture,
        [string]$WorkerScript,
        [string]$Name,
        [string[]]$Arguments,
        [ValidateSet('none','event','state')][string]$ReadyKind = 'none'
    )
    $workerRoot = Join-Path $Fixture.Root 'event-race-workers'
    if (-not (Test-Path -LiteralPath $workerRoot)) { New-Item -ItemType Directory -Path $workerRoot -ErrorAction Stop | Out-Null }
    $requestPath = Join-Path $workerRoot ($Name + '.request.json')
    $resultPath = Join-Path $workerRoot ($Name + '.result.json')
    $startedPath = Join-Path $workerRoot ($Name + '.started')
    $lockReadyPath = Join-Path $workerRoot ($Name + '.lock-ready')
    $stdoutPath = Join-Path $workerRoot ($Name + '.stdout.log')
    $stderrPath = Join-Path $workerRoot ($Name + '.stderr.log')
    $request = [ordered]@{
        powershell = $script:pwsh
        controller = $script:fixtureController
        arguments = @($Arguments)
        readyKind = $ReadyKind
        lockReadyPath = $lockReadyPath
    }
    Write-Utf8 $requestPath ($request | ConvertTo-Json -Depth 8 -Compress)
    $process = Start-Process -FilePath $script:pwsh -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath `
        -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',$WorkerScript,'-RequestPath',$requestPath,'-ResultPath',$resultPath,'-StartedPath',$startedPath)
    return [pscustomobject]@{
        Name=$Name; Process=$process; Request=$requestPath; Result=$resultPath; Started=$startedPath
        LockReady=$lockReadyPath; Stdout=$stdoutPath; Stderr=$stderrPath
    }
}

function Wait-TestPaths {
    param([string[]]$Paths, [int]$TimeoutMilliseconds, [string]$Reason)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        while (@($Paths | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) }).Count -gt 0) {
            if ($watch.ElapsedMilliseconds -ge $TimeoutMilliseconds) { throw $Reason }
            Start-Sleep -Milliseconds 25
        }
    } finally { $watch.Stop() }
}

function Wait-TestWorkers {
    param($Workers, [int]$TimeoutMilliseconds, [string]$Reason)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        while (@($Workers | Where-Object { -not $_.Process.HasExited }).Count -gt 0) {
            if ($watch.ElapsedMilliseconds -ge $TimeoutMilliseconds) { throw $Reason }
            Start-Sleep -Milliseconds 25
        }
        foreach ($worker in @($Workers)) { $worker.Process.WaitForExit(); $worker.Process.Refresh() }
    } finally { $watch.Stop() }
}

function Stop-TestWorkers {
    param($Workers)
    foreach ($worker in @($Workers)) {
        if ($null -eq $worker -or $null -eq $worker.Process) { continue }
        $worker.Process.Refresh()
        if (-not $worker.Process.HasExited) {
            Stop-Process -Id $worker.Process.Id -Force -ErrorAction Stop
            $worker.Process.WaitForExit()
            $worker.Process.Refresh()
        }
        $worker.Process.Dispose()
    }
}

function Read-WorkerResult {
    param($Worker)
    if (-not (Test-Path -LiteralPath $Worker.Result -PathType Leaf)) { throw 'worker-result-missing' }
    return ([IO.File]::ReadAllText($Worker.Result, $script:utf8) | ConvertFrom-Json -ErrorAction Stop)
}

function Write-RecordEvidence {
    param($Fixture, $RunningState, [string]$UnitId, [string]$RunId, [ValidateSet('Green','NoPatchNeeded','Hold','Failed')][string]$Outcome)

    $unit = @($RunningState.workUnits | Where-Object workUnitId -CEQ $UnitId)[0]
    $greenCommands = @()
    $redCommand = $null
    if ($Outcome -cin @('Green','NoPatchNeeded')) {
        $logRelative = "verification/$($UnitId.ToLowerInvariant())-green.log"
        $logPath = Join-Path $Fixture.Root ($logRelative.Replace('/', '\'))
        Write-Utf8 $logPath 'bounded green proof'
        $logBytes = [IO.File]::ReadAllBytes($logPath)
        $commandHash = (Get-ByteHash $script:utf8.GetBytes('powershell -NoProfile -Command event-fixture-green')).ToLowerInvariant()
        $greenCommands = @([ordered]@{
            commandId = "green:sha256:$commandHash"; exitCode = 0; logPath = $logRelative
            logSha256 = Get-ByteHash $logBytes; logBytes = [int64]$logBytes.Length; characterization = ($Outcome -ceq 'NoPatchNeeded')
        })
    }
    if ($Outcome -ceq 'Green') {
        $redLogRelative = "verification/$($UnitId.ToLowerInvariant())-red.log"
        $redLogPath = Join-Path $Fixture.Root ($redLogRelative.Replace('/', '\'))
        Write-Utf8 $redLogPath 'bounded red proof'
        $redLogBytes = [IO.File]::ReadAllBytes($redLogPath)
        $redCommandHash = (Get-ByteHash $script:utf8.GetBytes('powershell -NoProfile -Command event-fixture-red')).ToLowerInvariant()
        $redCommand = [ordered]@{
            commandId = "red:sha256:$redCommandHash"; exitCode = 3; logPath = $redLogRelative
            logSha256 = Get-ByteHash $redLogBytes; logBytes = [int64]$redLogBytes.Length
        }
    }
    $targetPostimages = @()
    if ($Outcome -ceq 'Green') {
        $targetBytes = [IO.File]::ReadAllBytes($Fixture.GreenTarget)
        $targetPostimages = @([ordered]@{
            path = 'data/event-green-target.txt'; exists = $true; bytes = [int64]$targetBytes.Length
            sha256 = Get-ByteHash $targetBytes; resolvedPathContained = $true; ancestorNonReparse = $true
            reparseTraversal = $false; leafKind = 'leaf'
        })
    }
    $evidence = [ordered]@{
        schemaVersion = 'awx.notebook.directive.work-unit-evidence.v1'; owner = 'desktop'; runId = $RunId
        workUnitId = $UnitId; canonicalMarkdownSha256 = [string]$RunningState.canonicalMarkdownSha256; outcome = $Outcome
        targetPreimages = @($unit.execution.targetPreimages); targetPostimages = @($targetPostimages); redCommand = $redCommand
        greenCommands = @($greenCommands); secretScan = [ordered]@{ mode = 'count-only'; hitCount = 0 }
        rollback = if ($Outcome -cin @('Green','NoPatchNeeded')) { [ordered]@{ status = 'not_required'; actualTargets = @() } } else { [ordered]@{ status = 'verified'; actualTargets = @() } }
        desktopFinalProof = if ($Outcome -cin @('Green','NoPatchNeeded')) { 'verified' } else { 'evidence_needed' }
    }
    $relative = "verification/$($UnitId.ToLowerInvariant())-$($Outcome.ToLowerInvariant()).json"
    $path = Join-Path $Fixture.Root ($relative.Replace('/', '\'))
    Write-Utf8 $path ($evidence | ConvertTo-Json -Depth 20 -Compress)
    return [pscustomobject]@{ Relative = $relative; Path = $path; Sha256 = Get-Hash $path }
}

function Write-AdoptEvidence {
    param($Fixture, [string]$RunId, [switch]$Stale, [switch]$MissingAnchor)

    $targetBytes = [IO.File]::ReadAllBytes($Fixture.GreenTarget)
    $targetPostimages = @([ordered]@{
        path = 'data/event-green-target.txt'; exists = $true; bytes = [int64]$targetBytes.Length
        sha256 = Get-ByteHash $targetBytes; resolvedPathContained = $true; ancestorNonReparse = $true
        reparseTraversal = $false; leafKind = 'leaf'
    })
    $targetJson = ConvertTo-Json -InputObject @($targetPostimages) -Depth 10 -Compress
    $targetAggregate = Get-ByteHash $script:utf8.GetBytes($targetJson)
    $anchorRelative = if ($MissingAnchor) { 'verification/missing-adopt-freeze-anchor.txt' } else { 'verification/adopt-freeze-anchor.txt' }
    $anchorPath = Join-Path $Fixture.Root ($anchorRelative.Replace('/', '\'))
    if (-not $MissingAnchor) { Write-Utf8 $anchorPath 'frozen adopt anchor' }
    $anchorHash = 'A' * 64
    $anchorByteCount = [int64]19
    if (-not $MissingAnchor) {
        [byte[]]$anchorBytes = [IO.File]::ReadAllBytes($anchorPath)
        $anchorHash = Get-ByteHash $anchorBytes
        $anchorByteCount = [int64](Get-Item -LiteralPath $anchorPath -Force -ErrorAction Stop).Length
    }
    $logRelative = 'verification/wu-event-adopt-green.log'
    $logPath = Join-Path $Fixture.Root ($logRelative.Replace('/', '\'))
    Write-Utf8 $logPath 'PASS: 3 assertions'
    $logBytes = [IO.File]::ReadAllBytes($logPath)
    $commandHash = (Get-ByteHash $script:utf8.GetBytes('powershell -NoProfile -Command event-fixture-green')).ToLowerInvariant()
    $observedAt = if ($Stale) { [DateTimeOffset]::UtcNow.AddHours(-3) } else { [DateTimeOffset]::UtcNow }
    $state = [IO.File]::ReadAllText($Fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
    $evidence = [ordered]@{
        schemaVersion = 'awx.notebook.directive.adopt-evidence.v1'; owner = 'desktop'; runId = $RunId
        workUnitId = 'WU-EVENT-ADOPT'; canonicalMarkdownSha256 = [string]$state.canonicalMarkdownSha256
        outcome = 'NoPatchNeeded'; observedAtUtc = $observedAt.ToString('o')
        targetPostimageSha256 = $targetAggregate; targetPostimages = @($targetPostimages)
        freezeAnchor = [ordered]@{
            path = $anchorRelative
            sha256 = $anchorHash
            bytes = $anchorByteCount
        }
        greenCommands = @([ordered]@{
            commandId = "green:sha256:$commandHash"; exitCode = 0; assertionCount = 3; durationMs = 17
            logPath = $logRelative; logSha256 = Get-ByteHash $logBytes; logBytes = [int64]$logBytes.Length; characterization = $true
        })
        secretScan = [ordered]@{ mode = 'count-only'; hitCount = 0 }
        desktopFinalProof = 'verified'
    }
    $relative = 'verification/wu-event-adopt.json'
    $path = Join-Path $Fixture.Root ($relative.Replace('/', '\'))
    Write-Utf8 $path ($evidence | ConvertTo-Json -Depth 20 -Compress)
    return [pscustomobject]@{ Relative = $relative; Path = $path; Sha256 = Get-Hash $path; TargetPostimageSha256 = $targetAggregate }
}

function Remove-SafeFixture {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path)) { return }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if (-not $full.StartsWith($temp + '\awx-public-events-', [StringComparison]::OrdinalIgnoreCase) -or
        -not ([IO.Directory]::GetParent($full).FullName.TrimEnd('\')).Equals($temp, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'unsafe-fixture-cleanup-target'
    }
    $reparse = @(Get-ChildItem -LiteralPath $full -Force -Recurse -ErrorAction Stop | Where-Object {
        ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
    })
    if ($reparse.Count -ne 0) { throw 'unsafe-fixture-cleanup-reparse' }
    Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction Stop
    if (Test-Path -LiteralPath $full) { throw 'fixture-cleanup-failed' }
}

function Remove-SafeOutsideSentinel {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path)) { return }
    $full = [IO.Path]::GetFullPath($Path)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    $parent = [IO.Directory]::GetParent($full).FullName.TrimEnd('\')
    if (-not $parent.Equals($temp, [StringComparison]::OrdinalIgnoreCase) -or
        -not ([IO.Path]::GetFileName($full)).StartsWith('awx-public-events-outside-', [StringComparison]::Ordinal)) {
        throw 'unsafe-outside-sentinel-cleanup-target'
    }
    $item = Get-Item -LiteralPath $full -Force -ErrorAction Stop
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'unsafe-outside-sentinel-cleanup-shape'
    }
    Remove-Item -LiteralPath $full -Force -ErrorAction Stop
    if (Test-Path -LiteralPath $full) { throw 'outside-sentinel-cleanup-failed' }
}

try {
    $fixture = New-PublicEventsFixture
    if ($AdoptOnly) {
        $controllerText = [IO.File]::ReadAllText($script:fixtureController, $script:utf8)
        Assert-True ($controllerText -match "ValidateSet\('Status', 'Snapshot', 'Next', 'Begin', 'Record', 'Adopt', 'Retire', 'Release'\)") 'public Adopt action is absent'

        $snapshot = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root,'-ProgramPath',$fixture.Program,'-StatePath',$fixture.State)
        $snapshotValue = Read-Result $snapshot
        Assert-Equal $snapshot.ExitCode 0 "Adopt fixture Snapshot failed: $($snapshotValue.reason)"
        Write-Utf8 $fixture.GreenTarget 'frozen adopted postimage'
        $targetHash = Get-Hash $fixture.GreenTarget
        $runId = 'event-adopt-run'
        $evidence = Write-AdoptEvidence $fixture $runId

        $stateBefore = Get-Hash $fixture.State
        $eventsBefore = Get-Hash $fixture.Events
        $begin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-ADOPT','-RunId',$runId)
        $beginValue = Read-Result $begin
        Assert-Equal $begin.ExitCode 1 'dirty-target Begin unexpectedly succeeded before Adopt'
        Assert-Equal $beginValue.reason 'dirty-target-overlap' 'Begin dirty-target gate changed while adding Adopt'
        Assert-Equal (Get-Hash $fixture.State) $stateBefore 'rejected Begin changed state before Adopt'
        Assert-Equal (Get-Hash $fixture.Events) $eventsBefore 'rejected Begin changed events before Adopt'

        $arguments = @(
            '-Action','Adopt','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-ADOPT','-RunId',$runId,
            '-EvidencePath',$evidence.Relative,'-ExpectedEvidenceSha256',$evidence.Sha256,
            '-ExpectedTargetPostimageSha256',$evidence.TargetPostimageSha256
        )

        $wrongEvidence = Invoke-Controller @(
            '-Action','Adopt','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-ADOPT','-RunId',$runId,
            '-EvidencePath',$evidence.Relative,'-ExpectedEvidenceSha256',('A' * 64),
            '-ExpectedTargetPostimageSha256',$evidence.TargetPostimageSha256
        )
        $wrongEvidenceValue = Read-Result $wrongEvidence
        Assert-Equal $wrongEvidence.ExitCode 1 'Adopt accepted a mismatched evidence SHA'
        Assert-Equal $wrongEvidenceValue.reason 'adopt-evidence-sha-mismatch' 'evidence SHA mismatch reason changed'
        Assert-Equal (Get-Hash $fixture.State) $stateBefore 'evidence SHA mismatch changed state'
        Assert-Equal (Get-Hash $fixture.Events) $eventsBefore 'evidence SHA mismatch changed events'

        $wrongTarget = Invoke-Controller @(
            '-Action','Adopt','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-ADOPT','-RunId',$runId,
            '-EvidencePath',$evidence.Relative,'-ExpectedEvidenceSha256',$evidence.Sha256,
            '-ExpectedTargetPostimageSha256',('B' * 64)
        )
        $wrongTargetValue = Read-Result $wrongTarget
        Assert-Equal $wrongTarget.ExitCode 1 'Adopt accepted a mismatched target postimage SHA'
        Assert-Equal $wrongTargetValue.reason 'adopt-target-postimage-sha-mismatch' 'target postimage mismatch reason changed'
        Assert-Equal (Get-Hash $fixture.State) $stateBefore 'target SHA mismatch changed state'
        Assert-Equal (Get-Hash $fixture.Events) $eventsBefore 'target SHA mismatch changed events'

        $staleEvidence = Write-AdoptEvidence $fixture $runId -Stale
        $stale = Invoke-Controller @(
            '-Action','Adopt','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-ADOPT','-RunId',$runId,
            '-EvidencePath',$staleEvidence.Relative,'-ExpectedEvidenceSha256',$staleEvidence.Sha256,
            '-ExpectedTargetPostimageSha256',$staleEvidence.TargetPostimageSha256
        )
        $staleValue = Read-Result $stale
        Assert-Equal $stale.ExitCode 1 'Adopt accepted stale characterization evidence'
        Assert-Equal $staleValue.reason 'stale-adopt-evidence' 'stale evidence reason changed'
        Assert-Equal (Get-Hash $fixture.State) $stateBefore 'stale evidence changed state'
        Assert-Equal (Get-Hash $fixture.Events) $eventsBefore 'stale evidence changed events'

        $missingAnchorEvidence = Write-AdoptEvidence $fixture $runId -MissingAnchor
        $missingAnchor = Invoke-Controller @(
            '-Action','Adopt','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-ADOPT','-RunId',$runId,
            '-EvidencePath',$missingAnchorEvidence.Relative,'-ExpectedEvidenceSha256',$missingAnchorEvidence.Sha256,
            '-ExpectedTargetPostimageSha256',$missingAnchorEvidence.TargetPostimageSha256
        )
        $missingAnchorValue = Read-Result $missingAnchor
        Assert-Equal $missingAnchor.ExitCode 1 'Adopt accepted a missing freeze anchor'
        Assert-Equal $missingAnchorValue.reason 'freeze-anchor-missing' 'missing freeze anchor reason changed'
        Assert-Equal (Get-Hash $fixture.State) $stateBefore 'missing freeze anchor changed state'
        Assert-Equal (Get-Hash $fixture.Events) $eventsBefore 'missing freeze anchor changed events'

        $evidence = Write-AdoptEvidence $fixture $runId
        $arguments = @(
            '-Action','Adopt','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-ADOPT','-RunId',$runId,
            '-EvidencePath',$evidence.Relative,'-ExpectedEvidenceSha256',$evidence.Sha256,
            '-ExpectedTargetPostimageSha256',$evidence.TargetPostimageSha256
        )
        $canonicalHash = ([IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop).canonicalMarkdownSha256
        $leaseTopic = 'consolidated-' + $canonicalHash.Substring(0, 12).ToLowerInvariant() + '-WU-EVENT-ADOPT'
        $sessionScript = Join-Path $fixture.Root '__patch_drop__\source_edit_session.ps1'
        $leaseBegin = @(& $script:pwsh -NoProfile -ExecutionPolicy Bypass -File $sessionScript -Action begin -Role desktop -Root $fixture.Root -Topic $leaseTopic -OwnerId $runId 2>&1)
        Assert-Equal $LASTEXITCODE 0 "active-lease fixture setup failed: $($leaseBegin -join ';')"
        try {
            $activeLease = Invoke-Controller $arguments
            $activeLeaseValue = Read-Result $activeLease
            Assert-Equal $activeLease.ExitCode 1 'Adopt accepted an active source lease'
            Assert-Equal $activeLeaseValue.reason 'adopt-lease-active' 'active lease reason changed'
            Assert-Equal (Get-Hash $fixture.State) $stateBefore 'active lease changed state'
            Assert-Equal (Get-Hash $fixture.Events) $eventsBefore 'active lease changed events'
        } finally {
            $leaseEnd = @(& $script:pwsh -NoProfile -ExecutionPolicy Bypass -File $sessionScript -Action end -Role desktop -Root $fixture.Root -Topic $leaseTopic -OwnerId $runId 2>&1)
            Assert-Equal $LASTEXITCODE 0 "active-lease fixture cleanup failed: $($leaseEnd -join ';')"
        }

        $stateFailMarker = Join-Path $fixture.Root '.event-test-fail-adopt-state'
        Write-Utf8 $stateFailMarker 'fail before adopt state publish'
        try {
            $stateFailure = Invoke-Controller $arguments
            $stateFailureValue = Read-Result $stateFailure
            Assert-Equal $stateFailure.ExitCode 1 'Adopt state-commit failure hook did not fail'
            Assert-Equal $stateFailureValue.reason 'state-hash-changed' 'Adopt state failure reason changed'
            Assert-Equal (Get-Hash $fixture.State) $stateBefore 'state commit failure changed state'
            Assert-Equal (Get-Hash $fixture.Events) $eventsBefore 'state commit failure published a completion event'
        } finally { Remove-Item -LiteralPath $stateFailMarker -Force -ErrorAction Stop }

        $appendFailMarker = Join-Path $fixture.Root '.event-test-fail-adopt-append'
        Write-Utf8 $appendFailMarker 'fail during adopt event append'
        try {
            $appendFailure = Invoke-Controller $arguments
            $appendFailureValue = Read-Result $appendFailure
            Assert-Equal $appendFailure.ExitCode 1 'Adopt append failure hook did not fail'
            Assert-Equal $appendFailureValue.reason 'event-publication-pending' 'Adopt append failure reason changed'
            Assert-Equal (Get-Hash $fixture.State) $stateBefore 'event append failure did not restore exact state preimage'
            Assert-Equal (Get-Hash $fixture.Events) $eventsBefore 'event append failure did not restore exact ledger preimage'
            Assert-Equal (Get-Hash $fixture.GreenTarget) $targetHash 'event append rollback wrote the target'
        } finally { Remove-Item -LiteralPath $appendFailMarker -Force -ErrorAction Stop }

        $adopt = Invoke-Controller $arguments
        $adoptValue = Read-Result $adopt
        Assert-Equal $adopt.ExitCode 0 "public Adopt failed: $($adoptValue.reason)"
        Assert-Equal $adoptValue.action 'Adopt' 'Adopt result action changed'
        Assert-Equal $adoptValue.reason 'work-unit-adopted' 'Adopt success reason changed'
        Assert-Equal $adoptValue.status 'no_patch_needed' 'Adopt did not use canonical NoPatchNeeded status'
        Assert-Equal (Get-Hash $fixture.GreenTarget) $targetHash 'Adopt wrote the frozen target'

        $adoptedState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
        $adoptedUnit = @($adoptedState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-ADOPT')[0]
        Assert-Equal $adoptedUnit.status 'no_patch_needed' 'Adopt state status changed'
        Assert-Equal $adoptedUnit.record.runId $runId 'Adopt record RunId changed'
        Assert-Equal $adoptedUnit.record.evidenceSha256 $evidence.Sha256 'Adopt record evidence hash changed'
        Assert-True (-not ($null -ne $adoptedUnit.PSObject.Properties['execution'])) 'Adopt created a Begin execution'
        Assert-True (-not ($null -ne $adoptedUnit.PSObject.Properties['transition'])) 'Adopt retained a transition'

        $eventLines = @([IO.File]::ReadAllLines($fixture.Events, $script:utf8))
        Assert-Equal $eventLines.Count 3 'Adopt did not publish the existing Snapshot/Begin/Record bundle exactly once'
        $eventRows = @($eventLines | ForEach-Object { $_ | ConvertFrom-Json -ErrorAction Stop })
        Assert-Equal (($eventRows | ForEach-Object action) -join ',') 'Snapshot,Begin,Record' 'Adopt changed Task4E event vocabulary'
        Assert-Equal $eventRows[2].result 'no_patch_needed' 'Adopt completion event result changed'

        $adoptedStateHash = Get-Hash $fixture.State
        $adoptedEventsHash = Get-Hash $fixture.Events
        $replay = Invoke-Controller $arguments
        $replayValue = Read-Result $replay
        Assert-Equal $replay.ExitCode 0 "identical Adopt replay failed: $($replayValue.reason)"
        Assert-Equal $replayValue.reason 'already-adopted' 'identical Adopt replay reason changed'
        Assert-Equal (Get-Hash $fixture.State) $adoptedStateHash 'identical Adopt replay duplicated state'
        Assert-Equal (Get-Hash $fixture.Events) $adoptedEventsHash 'identical Adopt replay duplicated events'

        $mismatch = Invoke-Controller @(
            '-Action','Adopt','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-ADOPT','-RunId',$runId,
            '-EvidencePath',$evidence.Relative,'-ExpectedEvidenceSha256',$evidence.Sha256,
            '-ExpectedTargetPostimageSha256',('B' * 64)
        )
        $mismatchValue = Read-Result $mismatch
        Assert-Equal $mismatch.ExitCode 1 'same RunId with a different payload succeeded'
        Assert-Equal $mismatchValue.reason 'adopt-replay-mismatch' 'same RunId payload mismatch did not fail closed'
        Assert-Equal (Get-Hash $fixture.State) $adoptedStateHash 'replay mismatch changed state'
        Assert-Equal (Get-Hash $fixture.Events) $adoptedEventsHash 'replay mismatch changed events'
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    $serializerProbe = Join-Path $fixture.Root 'canonical-string-probe.ps1'
    $serializerOutput = Join-Path $fixture.Root 'canonical-string-output.bin'
    $serializerTokens = $null
    $serializerErrors = $null
    $controllerAst = [Management.Automation.Language.Parser]::ParseFile($script:fixtureController, [ref]$serializerTokens, [ref]$serializerErrors)
    Assert-Equal @($serializerErrors).Count 0 'temp controller parser rejected serializer probe source'
    $serializerFunctions = @($controllerAst.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'ConvertTo-CanonicalJsonString'
    }, $true))
    Assert-Equal $serializerFunctions.Count 1 'canonical string serializer function count changed'
    Write-Utf8 $serializerProbe ($serializerFunctions[0].Extent.Text + "`r`n" + @'
$ErrorActionPreference = 'Stop'
$OutputPath = [string]$args[0]
$value = -join @(
    [char]0x22, [char]0x5C, [char]0x08, [char]0x09, [char]0x0A, [char]0x0C,
    [char]0x0D, [char]0x01, [char]0x2028, [char]0x2029, [char]0xD83D, [char]0xDE00, 'Z'
)
$utf8 = New-Object Text.UTF8Encoding($false, $true)
[IO.File]::WriteAllText($OutputPath, (ConvertTo-CanonicalJsonString $value), $utf8)
$rejected = 0
foreach ($invalid in @([string][char]0xD800, [string][char]0xDC00)) {
    try { $null = ConvertTo-CanonicalJsonString $invalid }
    catch {
        if ([string]$_.Exception.Message -cne 'event-schema-invalid') { throw }
        $rejected++
    }
}
if ($rejected -ne 2) { throw 'lone-surrogate-accepted' }
'@)
    $serializerInvocation = @(& $script:pwsh -NoProfile -ExecutionPolicy Bypass -File $serializerProbe $serializerOutput 2>&1)
    Assert-Equal $LASTEXITCODE 0 "canonical string probe failed: $($serializerInvocation -join ';')"
    $emoji = [string]::Concat([char]0xD83D, [char]0xDE00)
    $expectedCanonicalString = '"' + '\"' + '\\' + '\b' + '\t' + '\n' + '\f' + '\r' + '\u0001' + '\u2028' + '\u2029' + $emoji + 'Z' + '"'
    $actualCanonicalString = [IO.File]::ReadAllText($serializerOutput, $script:utf8)
    Assert-Equal $actualCanonicalString $expectedCanonicalString 'canonical JSON string escaping changed'
    Assert-Equal (Get-Hash $serializerOutput) '1AC3CE570B1E7D4387EFE8C30B361218E18850DBD5F6BEFA3531547835C86ACC' 'canonical JSON string hash changed'
    Assert-Equal @($serializerInvocation).Count 0 'canonical string probe emitted output while testing lone-surrogate rejection'
    if ($SerializerOnly) {
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    if ($HashOracleOnly) {
        $fixedOracleProbe = Join-Path $fixture.Root 'fixed-event-oracle-probe.ps1'
        Write-Utf8 $fixedOracleProbe @'
param([string]$Controller)
$ErrorActionPreference = 'Stop'
$env:AWX_EVENT_PURE_PROBE = '1'
$probeController = $Controller
. $probeController
$canonicalHash = 'A' * 64
$evidenceHash = 'F' * 64
$inventory = [ordered]@{
    schemaVersion='oracle.inventory.v1'; canonicalExecutionRoot='ORACLE'; candidateRoots=@(); candidates=@(); excluded=@()
}
$retirement = [ordered]@{
    schemaVersion='oracle.retirement.v1'; deleteAuthorized=$false; canonicalDirectivePath='agent-prompts/oracle.md'
    canonicalDirectiveSha256Evidence='external'; allRequiredWorkUnitsGreen=$false
    desktopFinalProof='evidence_needed'; status='hold'; items=@()
}
$pending = [ordered]@{
    workUnitId='WU-ORACLE'; status='pending'; dependencies=@(); required=$false; kind='verification'; targetFiles=@()
}
$manifest = [ordered]@{
    schemaVersion='oracle.manifest.v1'; directiveInventory=$inventory; workUnits=@($pending); retirement=$retirement
}
function New-OracleProjection($Unit) {
    return [ordered]@{
        schemaVersion='awx.notebook.directive.program.v2'; canonicalMarkdownPath='agent-prompts/oracle.md'
        canonicalMarkdownSha256=$canonicalHash; sourceOwner='desktop'; activeSourceSets=@('main/java')
        controllerManifest=$manifest; directiveInventory=$inventory; workUnits=@($Unit); retirement=$retirement
        desktopFinalProof='evidence_needed'; runtimeLineageVerdict='HOLD'; deleteAuthorized=$false
    }
}
$running = [ordered]@{
    workUnitId='WU-ORACLE'; status='running'; dependencies=@(); required=$false; kind='verification'; targetFiles=@()
    execution=[ordered]@{
        runId='oracle-run'; owner='desktop'; leaseTopic='oracle-topic'
        canonicalMarkdownSha256=$canonicalHash; targetPreimages=@()
    }
}
$record = [ordered]@{
    runId='oracle-run'; outcome='NoPatchNeeded'; evidenceSha256=$evidenceHash
    targetPreimages=@(); targetPostimages=@(); redCommandId=$null; greenCommandIds=@()
    rollbackStatus='not_required'; desktopFinalProof='verified'
}
$terminal = [ordered]@{
    workUnitId='WU-ORACLE'; status='no_patch_needed'; dependencies=@(); required=$false
    kind='verification'; targetFiles=@(); record=$record
}
$snapshotProjectionHash = Get-CanonicalJsonHash (New-OracleProjection $pending) 'state-projection'
$beginProjectionHash = Get-CanonicalJsonHash (New-OracleProjection $running) 'state-projection'
$recordProjectionHash = Get-CanonicalJsonHash (New-OracleProjection $terminal) 'state-projection'
$recordHash = Get-CanonicalJsonHash $record 'record'
$runHash = Get-EventRunIdHash 'oracle-run'
$snapshotRow = New-EventCheckpointRow -Sequence 1 -PreviousEventSha256 ('0' * 64) -ProgramId 'oracle-program' `
    -CanonicalMarkdownSha256 $canonicalHash -ActionName 'Snapshot' -UnitId $null -RunHash $null -Result 'created' `
    -StateProjectionSha256 $snapshotProjectionHash -EvidenceHash $null -RecordHash $null
$beginRow = New-EventCheckpointRow -Sequence 2 -PreviousEventSha256 ([string]$snapshotRow.eventSha256) -ProgramId 'oracle-program' `
    -CanonicalMarkdownSha256 $canonicalHash -ActionName 'Begin' -UnitId 'WU-ORACLE' -RunHash $runHash -Result 'running' `
    -StateProjectionSha256 $beginProjectionHash -EvidenceHash $null -RecordHash $null
$recordRow = New-EventCheckpointRow -Sequence 3 -PreviousEventSha256 ([string]$beginRow.eventSha256) -ProgramId 'oracle-program' `
    -CanonicalMarkdownSha256 $canonicalHash -ActionName 'Record' -UnitId 'WU-ORACLE' -RunHash $runHash -Result 'no_patch_needed' `
    -StateProjectionSha256 $recordProjectionHash -EvidenceHash $evidenceHash -RecordHash $recordHash
[ordered]@{
    snapshotProjectionSha256=$snapshotProjectionHash; beginProjectionSha256=$beginProjectionHash
    recordProjectionSha256=$recordProjectionHash; recordSha256=$recordHash; runIdSha256=$runHash
    snapshotEventId=[string]$snapshotRow.eventId; snapshotEventSha256=[string]$snapshotRow.eventSha256
    snapshotRowBytesSha256=(Get-Sha256 (Get-CanonicalEventRowBytes $snapshotRow))
    beginEventId=[string]$beginRow.eventId; beginEventSha256=[string]$beginRow.eventSha256
    beginRowBytesSha256=(Get-Sha256 (Get-CanonicalEventRowBytes $beginRow))
    recordEventId=[string]$recordRow.eventId; recordEventSha256=[string]$recordRow.eventSha256
    recordRowBytesSha256=(Get-Sha256 (Get-CanonicalEventRowBytes $recordRow))
} | ConvertTo-Json -Compress
'@
        $fixedOracleInvocation = @(& $script:pwsh -NoProfile -ExecutionPolicy Bypass -File $fixedOracleProbe -Controller $script:fixtureController 2>&1)
        Assert-Equal $LASTEXITCODE 0 "fixed event oracle probe failed: $($fixedOracleInvocation -join ';')"
        Assert-Equal $fixedOracleInvocation.Count 1 'fixed event oracle probe output shape changed'
        $fixedOracle = $fixedOracleInvocation[0] | ConvertFrom-Json -ErrorAction Stop
        $fixedExpected = [ordered]@{
            snapshotProjectionSha256='C59D5D1755255A7BB8D8FE2A06DB19B586549EF9E5004C07EDD9D7AED9CDA8C9'
            beginProjectionSha256='73BC8394C3BFDEDCF1706D81E3FE8107AC38B547A3529F8CC5E5A4B329B82021'
            recordProjectionSha256='5602C53935751764ED230BD48B2EE01816077D3F725815B958407FB4C3C8D0F6'
            recordSha256='2FB264FCEE95774465CEC7621C1DA8031A4CF7A62534CE5205B79F37B631A3E2'
            runIdSha256='FE2246710FE28D5BFD5E0E35EE71562E9268976B006D384A1E73953EA2D4CBD5'
            snapshotEventId='88C9886604E79676A252F7A4631547E07BB5281DC76EE2DB5736B12348EAFBA9'
            snapshotEventSha256='9D173464AE61ADDBBAB6677920F1A72291B16F086EFA4D79C00665368BE2E8BB'
            snapshotRowBytesSha256='9E7585229EBC2CB2E4A5E0859E141261D7A278B91EF49A159299B9F906A45829'
            beginEventId='98987190001882E3F582BE9AD8E7F00C4955F254AB59C105272FA38B3907992B'
            beginEventSha256='5A2C61B4DBAABF7F53B7283FC40B0A29A9DE138B42A2F296ABD6187ABF6E4C68'
            beginRowBytesSha256='4A373F3DD0D7344D71A32A75375C95C385CAB83E18FAE634BDB18F86CB7F38AE'
            recordEventId='D74659F8CAEF72DE0EECA84DEA3D6B6F19B51E761705875A60E5800EE4F90235'
            recordEventSha256='702A10DE0C7056E1048FA64A7102516CFBCB8BC1CA658A3E07E82853FF6C13D3'
            recordRowBytesSha256='DAB28DC97DE0BCA4187D19C627C661042519B17FAE804B25E84F417C4672EAD8'
        }
        foreach ($property in $fixedExpected.Keys) {
            Assert-Equal ([string]$fixedOracle.$property) ([string]$fixedExpected[$property]) "fixed oracle mismatch: $property"
        }
    }
    $stateLockFunctions = @($controllerAst.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Enter-StateCasLock'
    }, $true))
    $eventLockFunctions = @($controllerAst.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Enter-EventLock'
    }, $true))
    Assert-Equal $stateLockFunctions.Count 1 'state CAS lock function count changed'
    Assert-Equal $eventLockFunctions.Count 1 'event lock function count changed'
    Assert-True $stateLockFunctions[0].Extent.Text.Contains('if ($null -ne $script:eventLockStream) { throw ''state-cas-lock-invalid'' }') 'state CAS lock does not reject an already-held event lock'
    Assert-True $eventLockFunctions[0].Extent.Text.Contains('if ($null -ne $script:stateCasLockStream) { throw ''event-lock-invalid'' }') 'event lock does not reject an already-held state CAS lock'
    $productionControllerText = [IO.File]::ReadAllText($script:sourceController, $script:utf8)
    foreach ($diagnosticToken in @(
        'event-schema-array-', 'event-checkpoint-run-invalid', 'event-checkpoint-index-invalid',
        'event-checkpoint-outcome-invalid', 'event-checkpoint-unit-', 'event-checkpoint-empty-invalid',
        "'-sync-invalid'", "'-candidate-invalid'", "'-preflight-invalid'"
    )) {
        Assert-True (-not $productionControllerText.Contains($diagnosticToken)) "temporary event diagnostic token remains: $diagnosticToken"
    }
    foreach ($instrumentationToken in @(
        'event-test-', 'AWX_EVENT_PURE_PROBE', 'AWX_EVENT_LOCK_READY', 'AWX_STATE_LOCK_READY',
        'AWX_EVENT_ENVELOPE_PROBE', 'eventTestSyncInvocationCount', 'SK_SECRET_NATIVE_ENVELOPE'
    )) {
        Assert-True (-not $productionControllerText.Contains($instrumentationToken)) "temporary event instrumentation remains: $instrumentationToken"
    }
    if ($LockOnly) {
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    if ($EnvelopeMatrixOnly) {
        $envelopeProtocol = Get-TestProtocolFingerprint $fixture
        $safeEventReasons = @(
            'event-publication-pending','event-ledger-diverged','event-lock-timeout','event-lock-invalid',
            'event-lock-failed','event-ledger-too-large','event-row-too-large','event-row-count-exceeded',
            'event-rollback-unproven','event-schema-invalid','events-without-state','event-checkpoint-invalid'
        )
        Assert-Equal $safeEventReasons.Count 12 'closed event reason matrix cardinality changed'
        Assert-Equal @($safeEventReasons | Sort-Object -Unique).Count 12 'closed event reason matrix contains a duplicate'
        $expectedEnvelopeProperties = 'action,status,reason,workUnitId,committed,stateSha256,canonicalMarkdownSha256,eventReason'
        $nativeSecret = 'SK_SECRET_NATIVE_ENVELOPE_93'
        $nativeLeakCount = 0
        foreach ($mode in @('pre','post')) {
            foreach ($safeReason in @($safeEventReasons + 'native')) {
                $env:AWX_EVENT_ENVELOPE_PROBE = $mode + '|' + $safeReason
                try {
                    $envelopeInvocation = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
                } finally {
                    Remove-Item Env:AWX_EVENT_ENVELOPE_PROBE -ErrorAction SilentlyContinue
                }
                $envelopeResult = Read-Result $envelopeInvocation
                $expectedEventReason = if ($safeReason -ceq 'native') { 'event-lock-failed' } else { $safeReason }
                Assert-Equal $envelopeInvocation.ExitCode 1 "$mode envelope probe did not fail for $safeReason"
                Assert-Equal (@($envelopeResult.PSObject.Properties.Name) -join ',') $expectedEnvelopeProperties "$mode envelope property order changed for $safeReason"
                Assert-True ($envelopeResult.action -is [string] -and $envelopeResult.action -ceq 'Status') "$mode envelope action type/value changed for $safeReason"
                Assert-True ($envelopeResult.status -is [string] -and $envelopeResult.status -ceq 'error') "$mode envelope status type/value changed for $safeReason"
                Assert-True ($envelopeResult.reason -is [string]) "$mode envelope reason type changed for $safeReason"
                Assert-True ($envelopeResult.eventReason -is [string] -and $envelopeResult.eventReason -ceq $expectedEventReason) "$mode envelope eventReason changed for $safeReason"
                Assert-True ($envelopeResult.committed -is [bool]) "$mode envelope committed type changed for $safeReason"
                if ($mode -ceq 'pre') {
                    Assert-Equal $envelopeResult.reason $expectedEventReason "precommit reason changed for $safeReason"
                    Assert-True (-not [bool]$envelopeResult.committed) "precommit envelope set committed=true for $safeReason"
                    Assert-True ($null -eq $envelopeResult.workUnitId) "precommit envelope invented workUnitId for $safeReason"
                    Assert-True ($null -eq $envelopeResult.stateSha256) "precommit envelope invented state hash for $safeReason"
                    Assert-True ($null -eq $envelopeResult.canonicalMarkdownSha256) "precommit envelope invented canonical hash for $safeReason"
                } else {
                    Assert-Equal $envelopeResult.reason 'event-publication-pending' "postcommit reason changed for $safeReason"
                    Assert-True ([bool]$envelopeResult.committed) "postcommit envelope lost committed=true for $safeReason"
                    Assert-True ($envelopeResult.workUnitId -is [string] -and $envelopeResult.workUnitId -ceq 'WU-EVENT-A') "postcommit workUnitId changed for $safeReason"
                    Assert-True ($envelopeResult.stateSha256 -is [string] -and $envelopeResult.stateSha256 -ceq ('A' * 64)) "postcommit state hash changed for $safeReason"
                    Assert-True ($envelopeResult.canonicalMarkdownSha256 -is [string] -and $envelopeResult.canonicalMarkdownSha256 -ceq ('B' * 64)) "postcommit canonical hash changed for $safeReason"
                }
                if ($envelopeInvocation.Text.Contains($nativeSecret)) { $nativeLeakCount++ }
            }
        }
        Assert-Equal $nativeLeakCount 0 'closed envelope mapper leaked native exception text'
        Assert-Equal (Get-TestProtocolFingerprint $fixture) $envelopeProtocol 'closed envelope mapper changed protocol artifacts'
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    if ($CommittedSnapshotOnly) {
        $snapshotSyncFailureMarker = Join-Path $fixture.Root '.event-test-fail-snapshot-sync'
        Write-Utf8 $snapshotSyncFailureMarker 'fail post-state snapshot event sync'
        $failedSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root)
        $failedSnapshotResult = Read-Result $failedSnapshot
        $committedSnapshotState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
        $committedSnapshotHash = Get-Hash $fixture.State
        Assert-Equal $failedSnapshot.ExitCode 1 'forced post-state Snapshot event failure did not fail'
        Assert-Equal (@($failedSnapshotResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId,committed,stateSha256,canonicalMarkdownSha256,eventReason' 'post-Snapshot committed envelope shape changed'
        Assert-Equal $failedSnapshotResult.reason 'event-publication-pending' 'post-Snapshot failure did not report pending publication'
        Assert-True ($null -eq $failedSnapshotResult.workUnitId) 'post-Snapshot committed envelope invented a work unit'
        Assert-True ($failedSnapshotResult.committed -is [bool] -and [bool]$failedSnapshotResult.committed) 'post-Snapshot failure did not set committed=true'
        Assert-Equal $failedSnapshotResult.stateSha256 $committedSnapshotHash 'post-Snapshot committed state hash changed'
        Assert-Equal $failedSnapshotResult.canonicalMarkdownSha256 $committedSnapshotState.canonicalMarkdownSha256 'post-Snapshot committed canonical hash changed'
        Assert-Equal $failedSnapshotResult.eventReason 'event-lock-failed' 'post-Snapshot failure did not retain a closed event reason'
        Assert-True (-not (Test-Path -LiteralPath $fixture.Events)) 'post-Snapshot failure created a partial ledger'

        Remove-Item -LiteralPath $snapshotSyncFailureMarker -Force -ErrorAction Stop
        $snapshotRetry = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root)
        $snapshotRetryResult = Read-Result $snapshotRetry
        Assert-Equal $snapshotRetry.ExitCode 0 "exact initial Snapshot retry failed: $($snapshotRetryResult.reason)"
        Assert-Equal $snapshotRetryResult.reason 'snapshot-already-created' 'exact initial Snapshot retry did not return idempotent result'
        Assert-Equal (Get-Hash $fixture.State) $committedSnapshotHash 'Snapshot retry rewrote exact initial state'
        $snapshotLedgerText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
        Assert-Equal @($snapshotLedgerText.Substring(0, $snapshotLedgerText.Length - 1).Split([char]0x0A)).Count 1 'Snapshot retry did not converge exactly sequence 1'
        $snapshotLedgerHash = Get-Hash $fixture.Events
        $snapshotReplay = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root)
        $snapshotReplayResult = Read-Result $snapshotReplay
        Assert-Equal $snapshotReplay.ExitCode 0 "converged Snapshot replay failed: $($snapshotReplayResult.reason)"
        Assert-Equal $snapshotReplayResult.reason 'snapshot-already-created' 'converged Snapshot replay changed result'
        Assert-Equal (Get-Hash $fixture.Events) $snapshotLedgerHash 'converged Snapshot replay duplicated sequence 1'
        Assert-Equal (Get-Hash $fixture.State) $committedSnapshotHash 'converged Snapshot replay changed initial state'
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    if ($SnapshotRaceOnly) {
        $snapshotWorkerScript = Join-Path $fixture.Root 'event-snapshot-worker.ps1'
        Write-ControllerWorkerScript $snapshotWorkerScript
        $stateDirectory = Split-Path $fixture.State -Parent
        New-Item -ItemType Directory -Path $stateDirectory -Force -ErrorAction Stop | Out-Null
        $stateCasLock = $fixture.State + '.cas.lock'
        [IO.File]::WriteAllBytes($stateCasLock, [byte[]]@())
        $parentStateLock = [IO.FileStream]::new($stateCasLock, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $snapshotWorkers = @()
        try {
            $snapshotWorkers += ,(Start-ControllerWorker $fixture $snapshotWorkerScript 'snapshot-a' @('-Action','Snapshot','-Root',$fixture.Root) 'state')
            $snapshotWorkers += ,(Start-ControllerWorker $fixture $snapshotWorkerScript 'snapshot-b' @('-Action','Snapshot','-Root',$fixture.Root) 'state')
            Wait-TestPaths @($snapshotWorkers | ForEach-Object { $_.Started }) 10000 'snapshot-workers-not-started'
            Wait-TestPaths @($snapshotWorkers | ForEach-Object { $_.LockReady }) 20000 'snapshot-workers-not-at-state-lock'
            Assert-True (-not (Test-Path -LiteralPath $fixture.State)) 'held Snapshot race created state before state-lock release'
            Assert-True (-not (Test-Path -LiteralPath $fixture.Events)) 'held Snapshot race created events before state-lock release'
            foreach ($worker in $snapshotWorkers) {
                $worker.Process.Refresh()
                Assert-True (-not $worker.Process.HasExited) "$($worker.Name) exited before state-lock release"
            }
            $parentStateLock.Dispose()
            $parentStateLock = $null
            Wait-TestWorkers $snapshotWorkers 60000 'snapshot-workers-timeout'
            $snapshotWorkerResults = @($snapshotWorkers | ForEach-Object { Read-WorkerResult $_ })
            $snapshotControllerResults = @($snapshotWorkerResults | ForEach-Object { [string]$_.text | ConvertFrom-Json -ErrorAction Stop })
            $snapshotReasons = @($snapshotControllerResults | ForEach-Object { [string]$_.reason })
            Assert-Equal @($snapshotWorkerResults | Where-Object { [int]$_.exitCode -eq 0 }).Count 2 "concurrent Snapshot did not return two idempotent successes: $($snapshotReasons -join ',')"
            Assert-Equal @($snapshotReasons | Where-Object { $_ -ceq 'snapshot-created' }).Count 1 'concurrent Snapshot did not have exactly one creator'
            Assert-Equal @($snapshotReasons | Where-Object { $_ -ceq 'snapshot-already-created' }).Count 1 'concurrent Snapshot loser was not an exact initial-state replay'
            $snapshotRaceState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
            Assert-Equal ([int64]$snapshotRaceState.eventCheckpoint.sequence) ([int64]1) 'concurrent Snapshot checkpoint is not sequence 1'
            $snapshotRaceBytes = [IO.File]::ReadAllBytes($fixture.Events)
            $snapshotRaceText = $script:utf8.GetString($snapshotRaceBytes)
            Assert-Equal @($snapshotRaceText.Substring(0,$snapshotRaceText.Length-1).Split([char]0x0A)).Count 1 'concurrent Snapshot published duplicate seq1 rows'
            Assert-True (Test-Path -LiteralPath $fixture.EventLock -PathType Leaf) 'concurrent Snapshot omitted persistent event lock'
            Assert-Equal ([IO.File]::ReadAllBytes($fixture.EventLock).Length) ([int64]0) 'concurrent Snapshot changed event lock shape'
            Assert-Equal ([IO.File]::ReadAllBytes($stateCasLock).Length) ([int64]0) 'concurrent Snapshot changed state CAS lock shape'
            $snapshotDebris = @(Get-ChildItem -LiteralPath $stateDirectory -Force -File -ErrorAction Stop | Where-Object {
                $_.Name -like '.state-*.tmp' -or $_.Name -like '*.cas-backup-*'
            })
            Assert-Equal $snapshotDebris.Count 0 'concurrent Snapshot left temp or backup debris'
        } finally {
            if ($null -ne $parentStateLock) { $parentStateLock.Dispose() }
            Stop-TestWorkers $snapshotWorkers
        }
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    if ($ProspectiveBoundsOnly) {
        $snapshotBoundMarker = Join-Path $fixture.Root '.event-test-snapshot-row-too-large'
        Write-Utf8 $snapshotBoundMarker 'force complete seq1 row above the prospective limit'
        try {
            $snapshotBound = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root)
        } finally {
            Remove-Item -LiteralPath $snapshotBoundMarker -Force -ErrorAction SilentlyContinue
        }
        $snapshotBoundResult = Read-Result $snapshotBound
        Assert-Equal $snapshotBound.ExitCode 1 'Snapshot committed a prospective row larger than 4096 bytes'
        Assert-Equal $snapshotBoundResult.reason 'event-row-too-large' 'Snapshot prospective row bound reason changed'
        Assert-True ($snapshotBoundResult.committed -is [bool] -and -not [bool]$snapshotBoundResult.committed) 'Snapshot prospective bound invented a commit'
        Assert-True (-not (Test-Path -LiteralPath $fixture.State)) 'Snapshot prospective row failure created state'
        Assert-True (-not (Test-Path -LiteralPath $fixture.Events)) 'Snapshot prospective row failure created ledger'
        Assert-True (-not (Test-Path -LiteralPath $fixture.EventLock)) 'Snapshot prospective row failure created event lock'
    }
    if ($NoWriteOnly) {
        $initialProtocol = Get-TestProtocolFingerprint $fixture
        $snapshotWhatIf = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root,'-WhatIf')
        $snapshotWhatIfResult = Read-Result $snapshotWhatIf
        Assert-Equal $snapshotWhatIf.ExitCode 0 "Snapshot WhatIf failed: $($snapshotWhatIfResult.reason)"
        Assert-Equal $snapshotWhatIfResult.reason 'whatif' 'Snapshot WhatIf reason changed'
        Assert-Equal (Get-TestProtocolFingerprint $fixture) $initialProtocol 'Snapshot WhatIf created a state, ledger, lock, or lease artifact'

        $snapshotDeclineMarker = Join-Path $fixture.Root '.event-test-decline-snapshot'
        Write-Utf8 $snapshotDeclineMarker 'decline Snapshot before any durable write'
        try {
            $snapshotDeclined = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root)
        } finally {
            Remove-Item -LiteralPath $snapshotDeclineMarker -Force -ErrorAction SilentlyContinue
        }
        $snapshotDeclinedResult = Read-Result $snapshotDeclined
        Assert-Equal $snapshotDeclined.ExitCode 0 "declined Snapshot failed: $($snapshotDeclinedResult.reason)"
        Assert-Equal $snapshotDeclinedResult.reason 'declined' 'Snapshot decline reason changed'
        Assert-Equal (Get-TestProtocolFingerprint $fixture) $initialProtocol 'declined Snapshot created a state, ledger, lock, or lease artifact'

        $createdSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root)
        $createdSnapshotResult = Read-Result $createdSnapshot
        Assert-Equal $createdSnapshot.ExitCode 0 "NoWrite setup Snapshot failed: $($createdSnapshotResult.reason)"
        $validStateBytes = [IO.File]::ReadAllBytes($fixture.State)
        Remove-Item -LiteralPath $fixture.EventLock -Force -ErrorAction Stop
        Write-Utf8 $fixture.State 'x'
        $invalidStateHash = Get-Hash $fixture.State
        try {
            $invalidStateBegin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId','event-prevalidate-84')
            $invalidStateResult = Read-Result $invalidStateBegin
            Assert-Equal $invalidStateBegin.ExitCode 1 'Begin accepted malformed state before event synchronization'
            Assert-Equal (@($invalidStateResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId' 'prevalidation state error envelope changed'
            Assert-Equal $invalidStateResult.reason 'malformed-state' 'Begin did not validate state before event synchronization'
            Assert-Equal (Get-Hash $fixture.State) $invalidStateHash 'prevalidation rejection changed malformed state bytes'
            Assert-True (-not (Test-Path -LiteralPath $fixture.EventLock)) 'prevalidation rejection created the persistent event lock'
        } finally {
            [IO.File]::WriteAllBytes($fixture.State, $validStateBytes)
            [IO.File]::WriteAllBytes($fixture.EventLock, [byte[]]@())
        }
        $snapshotProtocol = Get-TestProtocolFingerprint $fixture
        foreach ($disabledAction in @('Release','Retire')) {
            $disabled = Invoke-Controller @('-Action',$disabledAction,'-Root',$fixture.Root)
            $disabledResult = Read-Result $disabled
            Assert-Equal $disabled.ExitCode 1 "$disabledAction unexpectedly succeeded"
            Assert-Equal (@($disabledResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId' "$disabledAction error envelope shape changed"
            Assert-Equal $disabledResult.reason 'action-not-implemented' "$disabledAction reason changed"
            Assert-Equal (Get-TestProtocolFingerprint $fixture) $snapshotProtocol "$disabledAction changed state, ledger, lock, or lease artifacts"
        }

        $nativeEventMarker = Join-Path $fixture.Root '.event-test-fail-sync-native'
        Write-Utf8 $nativeEventMarker 'force one unknown native-shaped precommit event failure'
        try {
            $nativeEventFailure = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId','event-native-envelope-83')
        } finally {
            Remove-Item -LiteralPath $nativeEventMarker -Force -ErrorAction SilentlyContinue
        }
        $nativeEventResult = Read-Result $nativeEventFailure
        Assert-Equal $nativeEventFailure.ExitCode 1 'unknown precommit event failure unexpectedly succeeded'
        Assert-Equal (@($nativeEventResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId,committed,stateSha256,canonicalMarkdownSha256,eventReason' 'unknown precommit event failure envelope shape changed'
        Assert-Equal $nativeEventResult.reason 'event-lock-failed' 'unknown precommit event failure reason was not redacted'
        Assert-Equal $nativeEventResult.eventReason 'event-lock-failed' 'unknown precommit event failure eventReason was not redacted'
        Assert-True ($nativeEventResult.committed -is [bool] -and -not [bool]$nativeEventResult.committed) 'unknown precommit event failure invented a commit'
        Assert-True (-not $nativeEventFailure.Text.Contains('syn-native-event-exception-83')) 'unknown precommit event failure leaked native exception text'
        Assert-Equal (Get-TestProtocolFingerprint $fixture) $snapshotProtocol 'unknown precommit event failure changed state, ledger, lock, or lease artifacts'

        $rawRunId = 'sk-secret-runLeak77'
        $beginWhatIf = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$rawRunId,'-WhatIf')
        $beginWhatIfResult = Read-Result $beginWhatIf
        Assert-Equal $beginWhatIf.ExitCode 0 "Begin WhatIf failed: $($beginWhatIfResult.reason)"
        Assert-Equal $beginWhatIfResult.reason 'whatif' 'Begin WhatIf reason changed'
        Assert-Equal (Get-TestProtocolFingerprint $fixture) $snapshotProtocol 'Begin WhatIf created state, event, lock, or lease changes'

        $legacyWriterMarker = Join-Path $fixture.Root '.event-test-fail-legacy-writer'
        Write-Utf8 $legacyWriterMarker 'public paths must not call the legacy writer'
        try {
            $actualBegin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$rawRunId)
            $actualBeginResult = Read-Result $actualBegin
            Assert-Equal $actualBegin.ExitCode 0 "legacy-writer reachability Begin failed: $($actualBeginResult.reason)"

            $fullLedgerBytes = [IO.File]::ReadAllBytes($fixture.Events)
            $fullLedgerText = $script:utf8.GetString($fullLedgerBytes)
            $fullLedgerLines = @($fullLedgerText.Substring(0,$fullLedgerText.Length-1).Split([char]0x0A))
            Assert-Equal $fullLedgerLines.Count 2 'NoWrite setup Begin did not create exactly Snapshot-Begin'
            Write-Utf8 $fixture.Events ($fullLedgerLines[0] + "`n")
            $oneAheadProtocol = Get-TestProtocolFingerprint $fixture

            foreach ($disabledAction in @('Release','Retire')) {
                $oneAheadDisabled = Invoke-Controller @('-Action',$disabledAction,'-Root',$fixture.Root)
                $oneAheadDisabledResult = Read-Result $oneAheadDisabled
                Assert-Equal $oneAheadDisabled.ExitCode 1 "$disabledAction unexpectedly succeeded while the event ledger was one-ahead"
                Assert-Equal $oneAheadDisabledResult.reason 'action-not-implemented' "$disabledAction touched event logic before disabled rejection"
                Assert-Equal (Get-TestProtocolFingerprint $fixture) $oneAheadProtocol "$disabledAction repaired or changed a one-ahead ledger"
            }

            $replayWhatIf = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$rawRunId,'-WhatIf')
            $replayWhatIfResult = Read-Result $replayWhatIf
            Assert-Equal $replayWhatIf.ExitCode 0 "one-ahead Begin WhatIf failed: $($replayWhatIfResult.reason)"
            Assert-Equal $replayWhatIfResult.reason 'whatif' 'one-ahead Begin WhatIf reason changed'
            Assert-Equal (Get-TestProtocolFingerprint $fixture) $oneAheadProtocol 'Begin WhatIf repaired or changed a one-ahead ledger'

            $rawEvidenceRelative = 'verification/syn-secret-77-promptLeak78-commandLeak79-decisionLeak80-referenceLeak81-evidenceLeak82.json'
            $recordWhatIf = Invoke-Controller @(
                '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$rawRunId,
                '-Outcome','NoPatchNeeded','-EvidencePath',$rawEvidenceRelative,'-WhatIf'
            )
            $recordWhatIfResult = Read-Result $recordWhatIf
            Assert-Equal $recordWhatIf.ExitCode 0 "Record WhatIf failed: $($recordWhatIfResult.reason)"
            Assert-Equal $recordWhatIfResult.reason 'whatif' 'Record WhatIf reason changed'
            Assert-Equal (Get-TestProtocolFingerprint $fixture) $oneAheadProtocol 'Record WhatIf repaired or changed a one-ahead ledger'

            [IO.File]::WriteAllBytes($fixture.Events, $fullLedgerBytes)
            $runningState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
            $generatedEvidence = Write-RecordEvidence $fixture $runningState 'WU-EVENT-A' $rawRunId 'NoPatchNeeded'
            $rawEvidencePath = Join-Path $fixture.Root ($rawEvidenceRelative.Replace('/','\'))
            $evidenceContentMarker = 'evidenceContentLeak83'
            $generatedEvidenceValue = [IO.File]::ReadAllText($generatedEvidence.Path, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
            $greenLogRelative = [string]$generatedEvidenceValue.greenCommands[0].logPath
            $greenLogPath = Join-Path $fixture.Root ($greenLogRelative.Replace('/','\'))
            Write-Utf8 $greenLogPath $evidenceContentMarker
            $greenLogBytes = [IO.File]::ReadAllBytes($greenLogPath)
            $generatedEvidenceValue.greenCommands[0].logSha256 = Get-ByteHash $greenLogBytes
            $generatedEvidenceValue.greenCommands[0].logBytes = [int64]$greenLogBytes.Length
            Write-Utf8 $rawEvidencePath ($generatedEvidenceValue | ConvertTo-Json -Depth 20 -Compress)
            Remove-Item -LiteralPath $generatedEvidence.Path -Force -ErrorAction Stop
            $actualRecord = Invoke-Controller @(
                '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$rawRunId,
                '-Outcome','NoPatchNeeded','-EvidencePath',$rawEvidenceRelative
            )
            $actualRecordResult = Read-Result $actualRecord
            Assert-Equal $actualRecord.ExitCode 0 "legacy-writer reachability Record failed: $($actualRecordResult.reason)"
            Assert-Equal $actualRecordResult.reason 'work-unit-recorded' 'legacy-writer reachability Record result changed'
        } finally {
            Remove-Item -LiteralPath $legacyWriterMarker -Force -ErrorAction SilentlyContinue
        }

        $leakCorpus = @(
            $snapshotWhatIf.Text, $snapshotDeclined.Text, $createdSnapshot.Text, $beginWhatIf.Text,
            $nativeEventFailure.Text, $actualBegin.Text, $replayWhatIf.Text, $recordWhatIf.Text, $actualRecord.Text,
            [IO.File]::ReadAllText($fixture.Events, $script:utf8)
        ) -join "`n"
        $leakMarkers = [ordered]@{
            rawRunId=$rawRunId; secret='sk-secret-77'; prompt='promptLeak78'; command='commandLeak79'
            decision='decisionLeak80'; reference='referenceLeak81'; evidence='evidenceLeak82'
            evidenceContent=$evidenceContentMarker; declaredCommand=$fixture.EventCommand
        }
        $leakHitCount = 0
        foreach ($category in $leakMarkers.Keys) {
            $categoryCount = [regex]::Matches($leakCorpus, [regex]::Escape([string]$leakMarkers[$category]), [Text.RegularExpressions.RegexOptions]::CultureInvariant).Count
            Assert-Equal $categoryCount 0 "count-only leak scan found a raw $category marker"
            $leakHitCount += $categoryCount
        }
        Assert-Equal $leakHitCount 0 'count-only leak scan found raw sensitive markers'
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    if ($RuntimeLocksOnly) {
        $lockTraceMarker = Join-Path $fixture.Root '.event-test-enable-lock-trace'
        $lockTracePath = Join-Path $fixture.Root '.event-test-lock-trace'
        Write-Utf8 $lockTraceMarker 'trace only the opposite lock-held boolean at each real lock entry'
        try {
            $lockSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root)
            $lockSnapshotResult = Read-Result $lockSnapshot
            Assert-Equal $lockSnapshot.ExitCode 0 "lock-trace Snapshot failed: $($lockSnapshotResult.reason)"

            $lockRunId = 'event-runtime-locks-85'
            $lockBegin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$lockRunId)
            $lockBeginResult = Read-Result $lockBegin
            Assert-Equal $lockBegin.ExitCode 0 "lock-trace Begin failed: $($lockBeginResult.reason)"
            $lockRunningState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
            $lockEvidence = Write-RecordEvidence $fixture $lockRunningState 'WU-EVENT-A' $lockRunId 'NoPatchNeeded'
            $lockRecord = Invoke-Controller @(
                '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$lockRunId,
                '-Outcome','NoPatchNeeded','-EvidencePath',$lockEvidence.Relative
            )
            $lockRecordResult = Read-Result $lockRecord
            Assert-Equal $lockRecord.ExitCode 0 "lock-trace Record failed: $($lockRecordResult.reason)"
        } finally {
            Remove-Item -LiteralPath $lockTraceMarker -Force -ErrorAction SilentlyContinue
        }

        Assert-True (Test-Path -LiteralPath $lockTracePath -PathType Leaf) 'real public lifecycle did not produce a lock-entry trace'
        $lockTraceBytes = [IO.File]::ReadAllBytes($lockTracePath)
        $lockTraceText = $script:utf8.GetString($lockTraceBytes)
        Assert-True ($lockTraceBytes.Length -gt 0 -and $lockTraceBytes[0] -ne 0xEF) 'lock trace is absent or BOM-prefixed'
        Assert-True (-not $lockTraceText.Contains("`r")) 'lock trace is not LF-only'
        Assert-True ($lockTraceText.EndsWith("`n", [StringComparison]::Ordinal)) 'lock trace omitted final LF'
        $lockTraceLines = @($lockTraceText.Substring(0,$lockTraceText.Length-1).Split([char]0x0A))
        Assert-True (@($lockTraceLines | Where-Object { $_ -ceq 'event|0' }).Count -gt 0) 'real path did not enter the event lock'
        Assert-True (@($lockTraceLines | Where-Object { $_ -ceq 'state|0' }).Count -gt 0) 'real path did not enter the state lock'
        foreach ($lockTraceLine in $lockTraceLines) {
            Assert-True ($lockTraceLine -cmatch '^(event|state)\|0$') "real path overlapped state and event locks: $lockTraceLine"
        }

        $lockLedgerText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
        $lockLedgerLines = @($lockLedgerText.Substring(0,$lockLedgerText.Length-1).Split([char]0x0A))
        Assert-Equal $lockLedgerLines.Count 3 'lock-trace lifecycle did not end at Snapshot-Begin-Record'
        $lockTerminalState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
        Assert-Equal ([int64]$lockTerminalState.eventCheckpoint.sequence) ([int64]3) 'lock-trace terminal checkpoint is not sequence 3'
        Assert-Equal ($lockTerminalState.eventCheckpoint | ConvertTo-Json -Compress) (($lockLedgerLines[2] | ConvertFrom-Json -ErrorAction Stop) | ConvertTo-Json -Compress) 'lock-trace terminal checkpoint differs from ledger tail'
        Assert-Equal ([IO.File]::ReadAllBytes($fixture.EventLock).Length) ([int64]0) 'lock-trace flow changed persistent event lock bytes'
        Assert-Equal ([IO.File]::ReadAllBytes($fixture.State + '.cas.lock').Length) ([int64]0) 'lock-trace flow changed persistent state lock bytes'
        $lockSourceRoot = Join-Path $fixture.Root '__patch_drop__\source-edit-locks'
        Assert-Equal @(Get-ChildItem -LiteralPath $lockSourceRoot -Directory -Force -ErrorAction SilentlyContinue).Count 0 'lock-trace Record retained a source lease'
        $lockDebris = @(Get-ChildItem -LiteralPath (Split-Path $fixture.State -Parent) -Force -File -ErrorAction Stop | Where-Object {
            $_.Name -like '.state-*.tmp' -or $_.Name -like '*.cas-backup-*' -or $_.Name -like '*.cas-failed-*' -or
            $_.Name -like '.event-*.tmp' -or $_.Name -like '*.event-backup-*' -or $_.Name -like '*.event-failed-*'
        })
        Assert-Equal $lockDebris.Count 0 'lock-trace lifecycle left state or event publication debris'
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    if ($CreatedReplacementOnly) {
        $appendFailureMarker = Join-Path $fixture.Root '.event-test-fail-append'
        $replacementMarker = Join-Path $fixture.Root '.event-test-replace-created-before-delete'
        $replacementBytes = $script:utf8.GetBytes('replacement-ledger-sentinel')
        Write-Utf8 $appendFailureMarker 'fail the first append after writing the intended prefix'
        Write-Utf8 $replacementMarker 'replace the created ledger after its exclusive stream closes'
        try {
            $replacementFailure = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root)
        } finally {
            Remove-Item -LiteralPath $appendFailureMarker,$replacementMarker -Force -ErrorAction SilentlyContinue
        }
        $replacementResult = Read-Result $replacementFailure
        Assert-Equal $replacementFailure.ExitCode 1 'created-ledger replacement probe unexpectedly succeeded'
        Assert-Equal (@($replacementResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId,committed,stateSha256,canonicalMarkdownSha256,eventReason' 'created-ledger replacement envelope shape changed'
        Assert-Equal $replacementResult.reason 'event-publication-pending' 'created-ledger replacement lost committed pending status'
        Assert-Equal $replacementResult.eventReason 'event-rollback-unproven' 'created-ledger replacement was not rejected as rollback-unproven'
        Assert-True ($replacementResult.committed -is [bool] -and [bool]$replacementResult.committed) 'created-ledger replacement lost committed Snapshot state'
        Assert-True (Test-Path -LiteralPath $fixture.Events -PathType Leaf) 'created-ledger replacement was deleted through a stale path'
        Assert-Equal (Get-ByteHash ([IO.File]::ReadAllBytes($fixture.Events))) (Get-ByteHash $replacementBytes) 'created-ledger replacement bytes changed'
        Assert-Equal ([IO.File]::ReadAllBytes($fixture.Events).Length) $replacementBytes.Length 'created-ledger replacement length changed'
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    if ($LedgerEnvelopeOnly) {
        $sentinelRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-public-events-ledger-sentinel-' + [guid]::NewGuid().ToString('N'))
        $sentinelPath = Join-Path $sentinelRoot 'sentinel.txt'
        New-Item -ItemType Directory -Path $sentinelRoot,(Split-Path $fixture.Events -Parent) -Force -ErrorAction Stop | Out-Null
        Write-Utf8 $sentinelPath 'outside ledger reparse sentinel remains unchanged'
        $sentinelHash = Get-Hash $sentinelPath
        try {
            $null = New-Item -ItemType Junction -Path $fixture.Events -Target $sentinelRoot -ErrorAction Stop
            $snapshotReparse = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root)
            $snapshotReparseResult = Read-Result $snapshotReparse
            Assert-Equal $snapshotReparse.ExitCode 1 'Snapshot accepted a reparse event ledger'
            Assert-Equal (@($snapshotReparseResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId,committed,stateSha256,canonicalMarkdownSha256,eventReason' 'real Snapshot ledger error envelope shape changed'
            Assert-Equal $snapshotReparseResult.reason 'event-schema-invalid' 'real Snapshot ledger reparse reason changed'
            Assert-Equal $snapshotReparseResult.eventReason 'event-schema-invalid' 'real Snapshot ledger reparse eventReason changed'
            Assert-True ($snapshotReparseResult.committed -is [bool] -and -not [bool]$snapshotReparseResult.committed) 'real Snapshot ledger reparse invented a commit'
            Assert-True (-not (Test-Path -LiteralPath $fixture.State)) 'real Snapshot ledger reparse created state'
            Assert-Equal (Get-Hash $sentinelPath) $sentinelHash 'real Snapshot ledger reparse changed outside sentinel'

            $snapshotJunction = Get-Item -LiteralPath $fixture.Events -Force -ErrorAction Stop
            Assert-True (($snapshotJunction.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) 'Snapshot ledger fixture lost its junction'
            [IO.Directory]::Delete($fixture.Events)
            $normalSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root)
            $normalSnapshotResult = Read-Result $normalSnapshot
            Assert-Equal $normalSnapshot.ExitCode 0 "ledger-envelope setup Snapshot failed: $($normalSnapshotResult.reason)"
            $ledgerStateHash = Get-Hash $fixture.State
            Remove-Item -LiteralPath $fixture.Events -Force -ErrorAction Stop
            $null = New-Item -ItemType Junction -Path $fixture.Events -Target $sentinelRoot -ErrorAction Stop

            foreach ($readAction in @('Status','Next')) {
                $ledgerRead = Invoke-Controller @('-Action',$readAction,'-Root',$fixture.Root)
                $ledgerReadResult = Read-Result $ledgerRead
                Assert-Equal $ledgerRead.ExitCode 1 "$readAction accepted a reparse event ledger"
                Assert-Equal (@($ledgerReadResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId,committed,stateSha256,canonicalMarkdownSha256,eventReason' "$readAction real ledger error envelope shape changed"
                Assert-Equal $ledgerReadResult.reason 'event-schema-invalid' "$readAction real ledger reparse reason changed"
                Assert-Equal $ledgerReadResult.eventReason 'event-schema-invalid' "$readAction real ledger reparse eventReason changed"
                Assert-True ($ledgerReadResult.committed -is [bool] -and -not [bool]$ledgerReadResult.committed) "$readAction real ledger reparse invented a commit"
                Assert-Equal (Get-Hash $fixture.State) $ledgerStateHash "$readAction real ledger reparse changed state"
                Assert-Equal (Get-Hash $sentinelPath) $sentinelHash "$readAction real ledger reparse changed outside sentinel"
            }
        } finally {
            if (Test-Path -LiteralPath $fixture.Events) {
                $cleanupLedgerJunction = Get-Item -LiteralPath $fixture.Events -Force -ErrorAction Stop
                if (($cleanupLedgerJunction.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) { throw 'unsafe-ledger-junction-cleanup-target' }
                [IO.Directory]::Delete($fixture.Events)
            }
            $sentinelFull = [IO.Path]::GetFullPath($sentinelRoot).TrimEnd('\')
            $tempFull = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
            if (-not ([IO.Directory]::GetParent($sentinelFull).FullName.TrimEnd('\')).Equals($tempFull, [StringComparison]::OrdinalIgnoreCase) -or
                -not ([IO.Path]::GetFileName($sentinelFull)).StartsWith('awx-public-events-ledger-sentinel-', [StringComparison]::Ordinal)) {
                throw 'unsafe-ledger-sentinel-cleanup-target'
            }
            $sentinelReparse = @(Get-ChildItem -LiteralPath $sentinelFull -Force -Recurse -ErrorAction Stop | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 })
            if ($sentinelReparse.Count -ne 0) { throw 'unsafe-ledger-sentinel-cleanup-reparse' }
            Remove-Item -LiteralPath $sentinelFull -Recurse -Force -ErrorAction Stop
        }
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    $snapshot = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root)
    $snapshotResult = Read-Result $snapshot
    Assert-Equal $snapshot.ExitCode 0 "temp Snapshot failed: $($snapshotResult.reason)"
    Assert-Equal $snapshotResult.reason 'snapshot-created' 'temp Snapshot did not commit initial state'

    # First Task 4E RED against the frozen Task 4D controller.
    Assert-True (Test-Path -LiteralPath $fixture.Events -PathType Leaf) 'Snapshot did not publish events.jsonl'

    $state = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
    Assert-Equal $state.schemaVersion 'awx.notebook.directive.program.v2' 'Snapshot did not create v2 state'
    Assert-True ($null -ne $state.PSObject.Properties['eventCheckpoint']) 'Snapshot state omitted eventCheckpoint'

    if ($HardLinkOnly) {
        $script:outsideSentinel = Join-Path ([IO.Path]::GetTempPath()) ('awx-public-events-outside-' + [guid]::NewGuid().ToString('N') + '.sentinel')
        $snapshotLedger = [IO.File]::ReadAllBytes($fixture.Events)
        [IO.File]::WriteAllBytes($script:outsideSentinel, $snapshotLedger)
        Remove-Item -LiteralPath $fixture.Events -Force -ErrorAction Stop
        $null = New-Item -ItemType HardLink -Path $fixture.Events -Target $script:outsideSentinel -ErrorAction Stop
        Assert-Equal (Get-Hash $fixture.Events) (Get-Hash $script:outsideSentinel) 'hard-link fixture aliases do not start with identical bytes'

        $outsideBytes = [IO.File]::ReadAllBytes($script:outsideSentinel)
        $outsideHash = Get-Hash $script:outsideSentinel
        $stateHash = Get-Hash $fixture.State
        $eventLockHash = Get-Hash $fixture.EventLock
        $hardLinkRunId = 'event-run-hardlink-96'
        $hardLinkBegin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$hardLinkRunId)
        $hardLinkResult = Read-Result $hardLinkBegin

        $outsideBytesAfter = [IO.File]::ReadAllBytes($script:outsideSentinel)
        $changedByteCount = [Math]::Abs($outsideBytesAfter.Length - $outsideBytes.Length)
        for ($outsideIndex = 0; $outsideIndex -lt [Math]::Min($outsideBytesAfter.Length, $outsideBytes.Length); $outsideIndex++) {
            if ($outsideBytesAfter[$outsideIndex] -ne $outsideBytes[$outsideIndex]) { $changedByteCount++ }
        }
        $outsideHashAfter = Get-Hash $script:outsideSentinel
        Assert-Equal $changedByteCount 0 'hard-linked outside sentinel bytes changed during public Begin'
        Assert-Equal $outsideHashAfter $outsideHash 'hard-linked outside sentinel SHA changed during public Begin'
        Assert-Equal (Get-Hash $fixture.Events) $outsideHash 'hard-linked ledger entry changed during public Begin'
        Assert-Equal (Get-Hash $fixture.State) $stateHash 'hard-link rejection changed controller state'
        Assert-Equal (Get-Hash $fixture.EventLock) $eventLockHash 'hard-link rejection changed persistent event lock'
        Assert-Equal $hardLinkBegin.ExitCode 1 'hard-linked event ledger was accepted'
        Assert-Equal $hardLinkResult.reason 'event-schema-invalid' 'hard-link rejection reason changed'
        Assert-Equal $hardLinkResult.eventReason 'event-schema-invalid' 'hard-link rejection did not use a closed event reason'
        Assert-True ($hardLinkResult.committed -is [bool] -and -not [bool]$hardLinkResult.committed) 'hard-link rejection invented committed=true'
        Assert-True ($null -eq $hardLinkResult.workUnitId) 'hard-link rejection invented a work unit'
        Assert-Equal @(Get-ChildItem -LiteralPath (Join-Path $fixture.Root '__patch_drop__\source-edit-locks') -Directory -Force -ErrorAction SilentlyContinue).Count 0 'hard-link rejection created a source lease'
        Assert-True (-not $hardLinkBegin.Text.Contains($hardLinkRunId)) 'hard-link rejection leaked raw run ID'
        Write-Output "HARDLINK SENTINEL: bytesChanged=$changedByteCount shaChanged=$(if ($outsideHashAfter -ceq $outsideHash) { 0 } else { 1 })"
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($BeginInternalOnly) {
        $internalBaselineStateHash = Get-Hash $fixture.State
        $internalBaselineEventHash = Get-Hash $fixture.Events
        $internalBaselineLockHash = Get-Hash $fixture.EventLock
        $sourceLockRoot = Join-Path $fixture.Root '__patch_drop__\source-edit-locks'
        $captureFailureMarker = Join-Path $fixture.Root '.event-test-fail-target-capture'
        Write-Utf8 $captureFailureMarker 'fail only after the mutation lease is acquired'
        try {
            $cleanupRunId = 'event-internal-cleanup-88'
            $cleanFailure = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$cleanupRunId)
            $cleanFailureResult = Read-Result $cleanFailure
            Assert-Equal $cleanFailure.ExitCode 1 'capture-failure Begin unexpectedly succeeded'
            Assert-Equal $cleanFailureResult.reason 'event-test-capture-failed' 'capture-failure Begin changed its categorical reason'
            $cleanState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
            $cleanUnit = @($cleanState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
            Assert-Equal $cleanUnit.status 'pending' 'intent/recovery/cleanup failure did not restore pending state'
            Assert-True ($null -eq $cleanUnit.PSObject.Properties['transition']) 'intent/recovery/cleanup failure retained a transition'
            Assert-Equal ([int64]$cleanState.eventCheckpoint.sequence) ([int64]1) 'intent/recovery/cleanup failure advanced checkpoint'
            Assert-Equal (Get-Hash $fixture.State) $internalBaselineStateHash 'clean Begin failure did not restore the exact initial state postimage'
            Assert-Equal (Get-Hash $fixture.Events) $internalBaselineEventHash 'intent/recovery/cleanup failure emitted an event row'
            Assert-Equal (Get-Hash $fixture.EventLock) $internalBaselineLockHash 'intent/recovery/cleanup failure changed event lock'
            Assert-Equal @(Get-ChildItem -LiteralPath $sourceLockRoot -Directory -Force -ErrorAction SilentlyContinue).Count 0 'intent/recovery/cleanup failure retained a source lease'

            $cleanupAbsenceMarker = Join-Path $fixture.Root '.event-test-fail-cleanup-absence'
            Write-Utf8 $cleanupAbsenceMarker 'make cleanup absence proof fail closed once'
            try {
                $recoveryRunId = 'event-internal-recovery-89'
                $recoveryFailure = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$recoveryRunId)
            } finally {
                Remove-Item -LiteralPath $cleanupAbsenceMarker -Force -ErrorAction SilentlyContinue
            }
            $recoveryFailureResult = Read-Result $recoveryFailure
            Assert-Equal $recoveryFailure.ExitCode 1 'cleanup-unproven Begin unexpectedly succeeded'
            Assert-Equal $recoveryFailureResult.reason 'begin-cleanup-unproven' 'cleanup-unproven Begin reason changed'
            $recoveryState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
            $recoveryUnit = @($recoveryState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
            Assert-Equal $recoveryUnit.status 'pending' 'cleanup-unproven Begin changed unit status'
            Assert-Equal $recoveryUnit.transition.phase 'begin-recovery-required' 'cleanup-unproven Begin did not retain recovery state'
            Assert-Equal $recoveryUnit.transition.runId $recoveryRunId 'cleanup-unproven recovery state changed run identity'
            Assert-Equal ([int64]$recoveryState.eventCheckpoint.sequence) ([int64]1) 'durable recovery state advanced checkpoint'
            Assert-Equal (Get-Hash $fixture.Events) $internalBaselineEventHash 'durable recovery state emitted an event row'
            Assert-Equal (Get-Hash $fixture.EventLock) $internalBaselineLockHash 'durable recovery state changed event lock'
            Assert-Equal @(Get-ChildItem -LiteralPath $sourceLockRoot -Directory -Force -ErrorAction SilentlyContinue).Count 0 'cleanup-unproven recovery retained a source lease after release'
        } finally {
            Remove-Item -LiteralPath $captureFailureMarker -Force -ErrorAction SilentlyContinue
        }

        $recoveryRetry = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$recoveryRunId)
        $recoveryRetryResult = Read-Result $recoveryRetry
        Assert-Equal $recoveryRetry.ExitCode 0 "durable recovery retry failed: $($recoveryRetryResult.reason)"
        Assert-Equal $recoveryRetryResult.reason 'work-unit-begun' 'durable recovery retry result changed'
        $recoveredState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
        $recoveredUnit = @($recoveredState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
        Assert-Equal $recoveredUnit.status 'running' 'durable recovery retry did not reach running'
        Assert-Equal $recoveredUnit.execution.runId $recoveryRunId 'durable recovery retry changed run identity'
        $recoveredEventText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
        $recoveredEventLines = @($recoveredEventText.Substring(0,$recoveredEventText.Length-1).Split([char]0x0A))
        Assert-Equal $recoveredEventLines.Count 2 'durable recovery retry did not publish exactly one Begin row'
        Assert-Equal ($recoveredState.eventCheckpoint | ConvertTo-Json -Compress) (($recoveredEventLines[1] | ConvertFrom-Json -ErrorAction Stop) | ConvertTo-Json -Compress) 'durable recovery retry checkpoint differs from Begin tail'
        Assert-True (-not $recoveredEventText.Contains($cleanupRunId) -and -not $recoveredEventText.Contains($recoveryRunId)) 'internal Begin lifecycle leaked raw run IDs'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($HistoricalProofOnly) {
        $historicalProbe = Join-Path $fixture.Root 'event-historical-proof-probe.ps1'
        $historicalStateHash = Get-Hash $fixture.State
        $historicalEventHash = Get-Hash $fixture.Events
        $historicalLockHash = Get-Hash $fixture.EventLock
        Write-Utf8 $historicalProbe @'
param([string]$Controller, [string]$StatePath, [string]$ProgramPath)
$ErrorActionPreference = 'Stop'
$env:AWX_EVENT_PURE_PROBE = '1'
$probeController = $Controller
$probeStatePath = $StatePath
$probeProgramPath = $ProgramPath
. $probeController
$script:root = $CanonicalRoot
$script:program = $probeProgramPath
$script:state = $probeStatePath

function New-ProbeManifestResult($Manifest, [string]$Hash) {
    return [pscustomobject]@{ manifest=$Manifest; hash=$Hash }
}

function New-ProbeRecord($Unit, [string]$RunId, [string]$EvidenceHash) {
    $greenIds = @(Get-ControllerCommandIds $Unit 'greenCommands')
    return [pscustomobject][ordered]@{
        runId=$RunId; outcome='NoPatchNeeded'; evidenceSha256=$EvidenceHash
        targetPreimages=@(); targetPostimages=@(); redCommandId=$null; greenCommandIds=@($greenIds)
        rollbackStatus='not_required'; desktopFinalProof='verified'
    }
}

$snapshotState = ConvertFrom-StrictJson ([IO.File]::ReadAllText($probeStatePath, $Utf8)) 'malformed-state'
$sourceManifest = Get-ControllerManifest $probeProgramPath
$canonicalHash = [string]$snapshotState.canonicalMarkdownSha256

$approvalManifest = Copy-ControllerValue $sourceManifest.manifest
$approvalUnit = $approvalManifest.workUnits[0]
$approvalUnit.status = 'hold'
$approvalUnit | Add-Member -NotePropertyName approvalGate -NotePropertyValue ([pscustomobject][ordered]@{
    schemaVersion='awx.notebook.directive.approval-gate.v1'; requiredForBegin=$true
    acceptAny=@('noNewPublicApi','explicitApproval')
})
$approvalResult = New-ProbeManifestResult $approvalManifest $canonicalHash
$approvalState = Get-EventBaselineState $snapshotState $approvalResult
$approvalState = Get-ValidatedStateObject $approvalState $approvalResult -SynchronizeDerived
$approvalRows = @(Get-ExpectedEventRows $approvalState $approvalResult)

$recordlessManifest = Copy-ControllerValue $sourceManifest.manifest
$recordlessManifest.workUnits[0].status = 'no_patch_needed'
$recordlessManifest.retirement.allRequiredWorkUnitsGreen = $true
$recordlessResult = New-ProbeManifestResult $recordlessManifest $canonicalHash
$recordlessState = Get-EventBaselineState $snapshotState $recordlessResult
$recordlessState = Get-ValidatedStateObject $recordlessState $recordlessResult -SynchronizeDerived
$recordlessRows = @(Get-ExpectedEventRows $recordlessState $recordlessResult)

$twoRequiredManifest = Copy-ControllerValue $sourceManifest.manifest
$twoRequiredManifest.workUnits[1].required = $true
$twoRequiredResult = New-ProbeManifestResult $twoRequiredManifest $canonicalHash
$afterA = Get-EventBaselineState $snapshotState $twoRequiredResult
$afterAUnit = $afterA.workUnits[0]
$afterAUnit.status = 'no_patch_needed'
$afterAUnit | Add-Member -NotePropertyName record -NotePropertyValue (New-ProbeRecord $afterAUnit 'historical-run-a-86' ('A' * 64))
Set-EventDerivedFields $afterA
$afterA = Get-ValidatedStateObject $afterA $twoRequiredResult -SynchronizeDerived
$afterARows = @(Get-ExpectedEventRows $afterA $twoRequiredResult)

$afterB = Copy-ControllerValue $afterA
$afterBUnit = $afterB.workUnits[1]
$afterBUnit.status = 'no_patch_needed'
$afterBUnit | Add-Member -NotePropertyName record -NotePropertyValue (New-ProbeRecord $afterBUnit 'historical-run-b-87' ('B' * 64))
Set-EventDerivedFields $afterB
$afterB = Get-ValidatedStateObject $afterB $twoRequiredResult -SynchronizeDerived
$afterBRows = @(Get-ExpectedEventRows $afterB $twoRequiredResult)

$beforeBRecord = Copy-ControllerValue $afterA
$runningB = Get-EventRunningUnitFromRecord $afterB.workUnits[1] $canonicalHash
Set-EventProjectionUnit $beforeBRecord 1 $runningB
Set-EventDerivedFields $beforeBRecord

[ordered]@{
    approvalRowCount=[int]$approvalRows.Count; approvalActions=@($approvalRows | ForEach-Object { [string]$_.action })
    recordlessRowCount=[int]$recordlessRows.Count; recordlessActions=@($recordlessRows | ForEach-Object { [string]$_.action })
    afterA=$afterA; afterARows=@($afterARows)
    beforeBRecord=$beforeBRecord
    afterB=$afterB; afterBRows=@($afterBRows)
} | ConvertTo-Json -Depth 50 -Compress
'@
        $historicalInvocation = @(& $script:pwsh -NoProfile -ExecutionPolicy Bypass -File $historicalProbe `
            -Controller $script:fixtureController -StatePath $fixture.State -ProgramPath $fixture.Program 2>&1)
        Assert-Equal $LASTEXITCODE 0 "historical proof probe failed: $($historicalInvocation -join ';')"
        Assert-Equal $historicalInvocation.Count 1 'historical proof probe output shape changed'
        $historical = $historicalInvocation[0] | ConvertFrom-Json -ErrorAction Stop

        Assert-Equal ([int]$historical.approvalRowCount) 1 'approval-gated frozen HOLD synthesized lifecycle rows'
        Assert-Equal (@($historical.approvalActions) -join ',') 'Snapshot' 'approval-gated frozen HOLD action prefix changed'
        Assert-Equal ([int]$historical.recordlessRowCount) 1 'recordless frozen terminal synthesized lifecycle rows'
        Assert-Equal (@($historical.recordlessActions) -join ',') 'Snapshot' 'recordless frozen terminal action prefix changed'

        Assert-Equal @($historical.afterARows).Count 3 'first required terminal did not derive Snapshot-Begin-Record'
        Assert-Equal (@($historical.afterARows | ForEach-Object { [string]$_.action }) -join ',') 'Snapshot,Begin,Record' 'first required terminal action history changed'
        Assert-Equal $historical.afterA.desktopFinalProof 'evidence_needed' 'first required terminal verified program proof early'
        Assert-True ($historical.afterA.retirement.allRequiredWorkUnitsGreen -is [bool] -and -not [bool]$historical.afterA.retirement.allRequiredWorkUnitsGreen) 'first required terminal marked all required work green early'
        Assert-Equal $historical.afterARows[2].stateProjectionSha256 (Get-IndependentStateProjectionHash $historical.afterA) 'first required Record projection hash changed'

        Assert-Equal $historical.beforeBRecord.desktopFinalProof 'evidence_needed' 'second required Begin verified program proof early'
        Assert-True ($historical.beforeBRecord.retirement.allRequiredWorkUnitsGreen -is [bool] -and -not [bool]$historical.beforeBRecord.retirement.allRequiredWorkUnitsGreen) 'second required Begin marked all required work green early'
        Assert-Equal $historical.afterBRows[3].stateProjectionSha256 (Get-IndependentStateProjectionHash $historical.beforeBRecord) 'second required Begin projection hash changed'

        Assert-Equal @($historical.afterBRows).Count 5 'second required terminal did not derive five-row history'
        Assert-Equal (@($historical.afterBRows | ForEach-Object { [string]$_.action }) -join ',') 'Snapshot,Begin,Record,Begin,Record' 'two-required action history changed'
        Assert-Equal $historical.afterB.desktopFinalProof 'verified' 'final required Record did not verify program proof'
        Assert-True ($historical.afterB.retirement.allRequiredWorkUnitsGreen -is [bool] -and [bool]$historical.afterB.retirement.allRequiredWorkUnitsGreen) 'final required Record did not mark all required work green'
        Assert-Equal $historical.afterB.retirement.desktopFinalProof 'verified' 'final required Record did not synchronize retirement proof'
        Assert-Equal $historical.afterBRows[4].stateProjectionSha256 (Get-IndependentStateProjectionHash $historical.afterB) 'final required Record projection hash changed'
        Assert-Equal (Get-Hash $fixture.State) $historicalStateHash 'pure historical proof probe changed state'
        Assert-Equal (Get-Hash $fixture.Events) $historicalEventHash 'pure historical proof probe changed ledger'
        Assert-Equal (Get-Hash $fixture.EventLock) $historicalLockHash 'pure historical proof probe changed event lock'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($BeginLoserOnly) {
        $beginLoserWorkerScript = Join-Path $fixture.Root 'event-begin-loser-worker.ps1'
        Write-ControllerWorkerScript $beginLoserWorkerScript
        $stateCasLock = $fixture.State + '.cas.lock'
        $parentStateLock = [IO.FileStream]::new($stateCasLock, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $beginLoserWorkers = @()
        $beginLoserRunIds = @('event-begin-race-a-71','event-begin-race-b-72')
        $beforeRaceStateHash = Get-Hash $fixture.State
        $beforeRaceEventHash = Get-Hash $fixture.Events
        try {
            for ($workerIndex = 0; $workerIndex -lt 2; $workerIndex++) {
                $workerName = 'begin-loser-' + [char]([int][char]'a' + $workerIndex)
                $beginLoserWorkers += ,(Start-ControllerWorker $fixture $beginLoserWorkerScript $workerName @(
                    '-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$beginLoserRunIds[$workerIndex]
                ) 'state')
            }
            Wait-TestPaths @($beginLoserWorkers | ForEach-Object { $_.Started }) 10000 'begin-loser-workers-not-started'
            Wait-TestPaths @($beginLoserWorkers | ForEach-Object { $_.LockReady }) 20000 'begin-loser-workers-not-at-state-lock'
            Assert-Equal (Get-Hash $fixture.State) $beforeRaceStateHash 'held Begin CAS race changed state before state-lock release'
            Assert-Equal (Get-Hash $fixture.Events) $beforeRaceEventHash 'held Begin CAS race published an internal phase row'
            foreach ($worker in $beginLoserWorkers) {
                $worker.Process.Refresh()
                Assert-True (-not $worker.Process.HasExited) "$($worker.Name) exited before state-lock release"
            }
            $parentStateLock.Dispose()
            $parentStateLock = $null
            Wait-TestWorkers $beginLoserWorkers 60000 'begin-loser-workers-timeout'

            $beginLoserResults = @()
            for ($workerIndex = 0; $workerIndex -lt 2; $workerIndex++) {
                $workerResult = Read-WorkerResult $beginLoserWorkers[$workerIndex]
                $controllerResult = [string]$workerResult.text | ConvertFrom-Json -ErrorAction Stop
                $beginLoserResults += ,[pscustomobject]@{
                    runId=$beginLoserRunIds[$workerIndex]; exitCode=[int]$workerResult.exitCode
                    text=[string]$workerResult.text; result=$controllerResult
                }
            }
            $winner = @($beginLoserResults | Where-Object { $_.exitCode -eq 0 })
            $loser = @($beginLoserResults | Where-Object { $_.exitCode -ne 0 })
            Assert-Equal $winner.Count 1 'Begin CAS race did not have exactly one winner'
            Assert-Equal $loser.Count 1 'Begin CAS race did not have exactly one loser'
            Assert-Equal $winner[0].result.reason 'work-unit-begun' 'Begin CAS race winner result changed'
            Assert-Equal $winner[0].result.workUnitId 'WU-EVENT-A' 'Begin CAS race winner resolved the wrong unit'
            Assert-Equal (@($loser[0].result.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId' 'Begin CAS loser error envelope changed'
            Assert-Equal $loser[0].result.reason 'state-hash-changed' 'Begin CAS loser did not fail at the durable compare-and-swap gate'

            $afterRaceState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
            $afterRaceUnit = @($afterRaceState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
            Assert-Equal $afterRaceUnit.status 'running' 'Begin CAS race did not retain one running winner'
            Assert-Equal $afterRaceUnit.execution.runId $winner[0].runId 'Begin CAS race state does not belong to the successful request'
            Assert-True (-not $winner[0].text.Contains($winner[0].runId)) 'Begin CAS winner output leaked raw run ID'
            Assert-True (-not $loser[0].text.Contains($loser[0].runId)) 'Begin CAS loser output leaked raw run ID'

            $raceEventText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
            $raceEventLines = @($raceEventText.Substring(0,$raceEventText.Length-1).Split([char]0x0A))
            Assert-Equal $raceEventLines.Count 2 'Begin CAS loser published or removed an event row'
            $raceBeginRow = $raceEventLines[1] | ConvertFrom-Json -ErrorAction Stop
            $winnerRunHash = Get-ByteHash $script:utf8.GetBytes("awx.notebook.directive.run-id.v1`0$($winner[0].runId)")
            Assert-Equal ([int64]$afterRaceState.eventCheckpoint.sequence) ([int64]2) 'Begin CAS race state checkpoint is not sequence 2'
            Assert-Equal ($afterRaceState.eventCheckpoint | ConvertTo-Json -Compress) ($raceBeginRow | ConvertTo-Json -Compress) 'Begin CAS race state checkpoint differs from the only Begin row'
            Assert-Equal $raceBeginRow.action 'Begin' 'Begin CAS race ledger tail action changed'
            Assert-Equal $raceBeginRow.runIdSha256 $winnerRunHash 'Begin CAS race ledger belongs to the losing request'
            Assert-True (-not $raceEventText.Contains($beginLoserRunIds[0]) -and -not $raceEventText.Contains($beginLoserRunIds[1])) 'Begin CAS race ledger leaked raw run IDs'

            $sourceLockRoot = Join-Path $fixture.Root '__patch_drop__\source-edit-locks'
            $sourceLocks = @(Get-ChildItem -LiteralPath $sourceLockRoot -Directory -Force -ErrorAction Stop)
            Assert-Equal $sourceLocks.Count 1 'Begin CAS loser released or duplicated the winner lease directory'
            Assert-True (Test-Path -LiteralPath (Join-Path $sourceLocks[0].FullName 'lease.json') -PathType Leaf) 'Begin CAS loser removed the winner lease leaf'
            Assert-Equal ([IO.File]::ReadAllBytes($fixture.EventLock).Length) ([int64]0) 'Begin CAS race changed persistent event lock bytes'
            Assert-Equal ([IO.File]::ReadAllBytes($stateCasLock).Length) ([int64]0) 'Begin CAS race changed persistent state lock bytes'
            $raceDebris = @(Get-ChildItem -LiteralPath (Split-Path $fixture.State -Parent) -Force -File -ErrorAction Stop | Where-Object {
                $_.Name -like '.state-*.tmp' -or $_.Name -like '*.cas-backup-*' -or $_.Name -like '*.cas-failed-*'
            })
            Assert-Equal $raceDebris.Count 0 'Begin CAS race left state candidate or backup debris'
        } finally {
            if ($null -ne $parentStateLock) { $parentStateLock.Dispose() }
            Stop-TestWorkers $beginLoserWorkers
        }
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($PublisherRaceOnly) {
        $publisherWorkerScript = Join-Path $fixture.Root 'event-publisher-worker.ps1'
        Write-ControllerWorkerScript $publisherWorkerScript
        $publisherRunId = 'event-publisher-race-61'
        $publisherFailureMarker = Join-Path $fixture.Root '.event-test-fail-sync'
        Write-Utf8 $publisherFailureMarker 'leave the exact running checkpoint one row ahead'
        try {
            $publisherSetup = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$publisherRunId)
        } finally {
            Remove-Item -LiteralPath $publisherFailureMarker -Force -ErrorAction SilentlyContinue
        }
        $publisherSetupResult = Read-Result $publisherSetup
        Assert-Equal $publisherSetup.ExitCode 1 'publisher race setup did not stop after running state CAS'
        Assert-Equal $publisherSetupResult.reason 'event-publication-pending' 'publisher race setup reason changed'
        Assert-True ($publisherSetupResult.committed -is [bool] -and [bool]$publisherSetupResult.committed) 'publisher race setup did not retain committed running state'
        $publisherStateHash = Get-Hash $fixture.State
        $publisherState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
        Assert-Equal ([int64]$publisherState.eventCheckpoint.sequence) ([int64]2) 'publisher race setup checkpoint is not sequence 2'
        $publisherOldLedgerHash = Get-Hash $fixture.Events
        $publisherOldText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
        Assert-Equal @($publisherOldText.Substring(0,$publisherOldText.Length-1).Split([char]0x0A)).Count 1 'publisher race setup did not retain exact seq1 prefix'

        $parentEventLock = [IO.FileStream]::new($fixture.EventLock, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $publisherWorkers = @()
        try {
            $publisherArguments = @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$publisherRunId)
            $publisherWorkers += ,(Start-ControllerWorker $fixture $publisherWorkerScript 'publisher-a' $publisherArguments 'event')
            $publisherWorkers += ,(Start-ControllerWorker $fixture $publisherWorkerScript 'publisher-b' $publisherArguments 'event')
            Wait-TestPaths @($publisherWorkers | ForEach-Object { $_.Started }) 10000 'publisher-workers-not-started'
            Wait-TestPaths @($publisherWorkers | ForEach-Object { $_.LockReady }) 20000 'publisher-workers-not-at-event-lock'
            Assert-Equal (Get-Hash $fixture.State) $publisherStateHash 'held publisher race changed state'
            Assert-Equal (Get-Hash $fixture.Events) $publisherOldLedgerHash 'held publisher race changed ledger'
            foreach ($worker in $publisherWorkers) {
                $worker.Process.Refresh()
                Assert-True (-not $worker.Process.HasExited) "$($worker.Name) exited before event-lock release"
            }
            $parentEventLock.Dispose()
            $parentEventLock = $null
            Wait-TestWorkers $publisherWorkers 60000 'publisher-workers-timeout'
            $publisherResults = @($publisherWorkers | ForEach-Object { Read-WorkerResult $_ })
            Assert-Equal @($publisherResults | Where-Object { [int]$_.exitCode -eq 0 }).Count 2 'two publisher/drainers did not both converge successfully'
            foreach ($publisherResult in $publisherResults) {
                $controllerResult = [string]$publisherResult.text | ConvertFrom-Json -ErrorAction Stop
                Assert-Equal $controllerResult.reason 'already-running' 'publisher/drainer replay result changed'
                Assert-Equal $controllerResult.workUnitId 'WU-EVENT-A' 'publisher/drainer replay resolved the wrong unit'
                Assert-True (-not ([string]$publisherResult.text).Contains($publisherRunId)) 'publisher/drainer output leaked raw run ID'
            }
            Assert-Equal (Get-Hash $fixture.State) $publisherStateHash 'publisher/drainer race changed committed running state'
            $publisherFinalBytes = [IO.File]::ReadAllBytes($fixture.Events)
            $publisherFinalText = $script:utf8.GetString($publisherFinalBytes)
            $publisherFinalLines = @($publisherFinalText.Substring(0,$publisherFinalText.Length-1).Split([char]0x0A))
            Assert-Equal $publisherFinalLines.Count 2 'publisher/drainer race lost or duplicated seq2'
            $publisherTail = $publisherFinalLines[1] | ConvertFrom-Json -ErrorAction Stop
            Assert-Equal $publisherTail.eventSha256 $publisherState.eventCheckpoint.eventSha256 'publisher/drainer ledger tail differs from state checkpoint'
            Assert-True ((Get-Hash $fixture.Events) -cne $publisherOldLedgerHash) 'publisher/drainer race did not append the pending row'
            Assert-Equal ([IO.File]::ReadAllBytes($fixture.EventLock).Length) ([int64]0) 'publisher/drainer race changed event lock shape'
            $sourceLockRoot = Join-Path $fixture.Root '__patch_drop__\source-edit-locks'
            $publisherSourceLocks = @(Get-ChildItem -LiteralPath $sourceLockRoot -Directory -Force -ErrorAction Stop)
            Assert-Equal $publisherSourceLocks.Count 1 'publisher/drainer race lost or duplicated source lease directories'
            Assert-True (Test-Path -LiteralPath (Join-Path $publisherSourceLocks[0].FullName 'lease.json') -PathType Leaf) 'publisher/drainer race lost the active lease leaf'
        } finally {
            if ($null -ne $parentEventLock) { $parentEventLock.Dispose() }
            Stop-TestWorkers $publisherWorkers
        }
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($DelayedPublisherOnly) {
        $delayedWorkerScript = Join-Path $fixture.Root 'event-delayed-publisher-worker.ps1'
        Write-ControllerWorkerScript $delayedWorkerScript
        $delayedRunId = 'event-delayed-publisher-62'
        $delayMarker = Join-Path $fixture.Root '.event-test-delay-begin-publisher'
        $delayReady = Join-Path $fixture.Root '.event-test-delay-begin-ready'
        $delayRelease = Join-Path $fixture.Root '.event-test-delay-begin-release'
        $delayedWorkers = @()
        Write-Utf8 $delayMarker 'pause Begin after running CAS and before postcommit event lock'
        try {
            $delayedWorkers += ,(Start-ControllerWorker $fixture $delayedWorkerScript 'delayed-begin' @(
                '-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$delayedRunId
            ))
            Wait-TestPaths @($delayedWorkers[0].Started) 10000 'delayed-begin-worker-not-started'
            Wait-TestPaths @($delayReady) 30000 'delayed-begin-not-at-postcommit-sync'
            $delayedWorkers[0].Process.Refresh()
            Assert-True (-not $delayedWorkers[0].Process.HasExited) 'delayed Begin exited before release marker'
            $delayedRunningState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
            $delayedRunningUnit = @($delayedRunningState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
            Assert-Equal $delayedRunningUnit.status 'running' 'delayed Begin did not commit running state before pause'
            Assert-Equal $delayedRunningUnit.execution.runId $delayedRunId 'delayed Begin committed the wrong run identity'
            Assert-Equal ([int64]$delayedRunningState.eventCheckpoint.sequence) ([int64]2) 'delayed Begin running checkpoint is not sequence 2'
            $delayedPrefixText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
            Assert-Equal @($delayedPrefixText.Substring(0,$delayedPrefixText.Length-1).Split([char]0x0A)).Count 1 'delayed Begin published seq2 before pause'

            $delayedEvidence = Write-RecordEvidence $fixture $delayedRunningState 'WU-EVENT-A' $delayedRunId 'NoPatchNeeded'
            $racingRecord = Invoke-Controller @(
                '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$delayedRunId,
                '-Outcome','NoPatchNeeded','-EvidencePath',$delayedEvidence.Relative
            )
            $racingRecordResult = Read-Result $racingRecord
            Assert-Equal $racingRecord.ExitCode 0 "Record could not overtake the paused publisher safely: $($racingRecordResult.reason)"
            Assert-Equal $racingRecordResult.reason 'work-unit-recorded' 'racing Record result changed'
            $delayedTerminalState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
            $delayedTerminalUnit = @($delayedTerminalState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
            Assert-Equal $delayedTerminalUnit.status 'no_patch_needed' 'racing Record did not terminalize the running unit'
            Assert-Equal ([int64]$delayedTerminalState.eventCheckpoint.sequence) ([int64]3) 'racing Record checkpoint is not sequence 3'
            $terminalStateHash = Get-Hash $fixture.State
            $terminalLedgerHash = Get-Hash $fixture.Events
            $terminalLedgerText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
            $terminalLines = @($terminalLedgerText.Substring(0,$terminalLedgerText.Length-1).Split([char]0x0A))
            Assert-Equal $terminalLines.Count 3 'racing Record did not publish Snapshot-Begin-Record exactly once'
            Assert-Equal (($terminalLines[2] | ConvertFrom-Json -ErrorAction Stop).eventSha256) $delayedTerminalState.eventCheckpoint.eventSha256 'racing Record ledger tail differs from terminal checkpoint'

            Write-Utf8 $delayRelease 'resume delayed Begin publisher'
            Wait-TestWorkers $delayedWorkers 60000 'delayed-begin-worker-timeout'
            $delayedResult = Read-WorkerResult $delayedWorkers[0]
            Assert-Equal ([int]$delayedResult.exitCode) 0 'delayed Begin did not accept exact latest-state convergence'
            $delayedControllerResult = [string]$delayedResult.text | ConvertFrom-Json -ErrorAction Stop
            Assert-Equal $delayedControllerResult.reason 'work-unit-begun' 'delayed Begin result changed after latest-state convergence'
            Assert-Equal $delayedControllerResult.workUnitId 'WU-EVENT-A' 'delayed Begin result lost actual work unit'
            Assert-True (-not ([string]$delayedResult.text).Contains($delayedRunId)) 'delayed Begin output leaked raw run ID'
            Assert-Equal (Get-Hash $fixture.Events) $terminalLedgerHash 'delayed Begin appended a ghost row after Record'
            $afterDelayedText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
            Assert-Equal @($afterDelayedText.Substring(0,$afterDelayedText.Length-1).Split([char]0x0A)).Count 3 'delayed Begin changed the exact Snapshot-Begin-Record prefix'
            Assert-Equal (Get-Hash $fixture.State) $terminalStateHash 'delayed Begin changed terminal state after convergence'
            $sourceLockRoot = Join-Path $fixture.Root '__patch_drop__\source-edit-locks'
            Assert-Equal @(Get-ChildItem -LiteralPath $sourceLockRoot -Directory -Force -ErrorAction SilentlyContinue).Count 0 'delayed Begin resurrected a released source lease'
        } finally {
            if (-not (Test-Path -LiteralPath $delayRelease)) { Write-Utf8 $delayRelease 'cleanup-release' }
            Stop-TestWorkers $delayedWorkers
            Remove-Item -LiteralPath $delayMarker,$delayReady,$delayRelease -Force -ErrorAction SilentlyContinue
        }
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($DelayedRecordNextBeginOnly) {
        $recordDelayWorkerScript = Join-Path $fixture.Root 'event-delayed-record-worker.ps1'
        Write-ControllerWorkerScript $recordDelayWorkerScript
        $recordDelayRunId = 'event-delayed-record-63'
        $nextBeginRunId = 'event-next-begin-64'
        $recordDelayMarker = Join-Path $fixture.Root '.event-test-delay-record-publisher'
        $recordDelayReady = Join-Path $fixture.Root '.event-test-delay-record-ready'
        $recordDelayRelease = Join-Path $fixture.Root '.event-test-delay-record-release'
        $recordDelayWorkers = @()

        $recordDelayBegin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$recordDelayRunId)
        $recordDelayBeginResult = Read-Result $recordDelayBegin
        Assert-Equal $recordDelayBegin.ExitCode 0 "delayed Record setup Begin failed: $($recordDelayBeginResult.reason)"
        $recordDelayRunningState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
        $recordDelayEvidence = Write-RecordEvidence $fixture $recordDelayRunningState 'WU-EVENT-A' $recordDelayRunId 'NoPatchNeeded'
        Write-Utf8 $recordDelayMarker 'pause Record after terminal CAS and before postcommit event sync'
        try {
            $recordDelayWorkers += ,(Start-ControllerWorker $fixture $recordDelayWorkerScript 'delayed-record' @(
                '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$recordDelayRunId,
                '-Outcome','NoPatchNeeded','-EvidencePath',$recordDelayEvidence.Relative
            ))
            Wait-TestPaths @($recordDelayWorkers[0].Started) 10000 'delayed-record-worker-not-started'
            Wait-TestPaths @($recordDelayReady) 30000 'delayed-record-not-at-postcommit-sync'
            $recordDelayWorkers[0].Process.Refresh()
            Assert-True (-not $recordDelayWorkers[0].Process.HasExited) 'delayed Record exited before release marker'

            $recordDelayTerminalState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
            $recordDelayTerminalUnit = @($recordDelayTerminalState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
            Assert-Equal $recordDelayTerminalUnit.status 'no_patch_needed' 'delayed Record did not terminalize before pause'
            Assert-Equal ([int64]$recordDelayTerminalState.eventCheckpoint.sequence) ([int64]3) 'delayed Record terminal checkpoint is not sequence 3'
            $recordDelayPrefixText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
            Assert-Equal @($recordDelayPrefixText.Substring(0,$recordDelayPrefixText.Length-1).Split([char]0x0A)).Count 2 'delayed Record published seq3 before pause'

            $nextBegin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-B','-RunId',$nextBeginRunId)
            $nextBeginResult = Read-Result $nextBegin
            Assert-Equal $nextBegin.ExitCode 0 "next Begin could not converge delayed Record: $($nextBeginResult.reason)"
            Assert-Equal $nextBeginResult.reason 'work-unit-begun' 'next Begin result changed during delayed Record race'
            Assert-Equal $nextBeginResult.workUnitId 'WU-EVENT-B' 'next Begin selected the wrong work unit'

            $nextRunningState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
            $nextRunningA = @($nextRunningState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
            $nextRunningB = @($nextRunningState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-B')[0]
            Assert-Equal $nextRunningA.status 'no_patch_needed' 'next Begin changed the completed Record unit'
            Assert-Equal $nextRunningB.status 'running' 'next Begin did not retain its running unit'
            Assert-Equal $nextRunningB.execution.runId $nextBeginRunId 'next Begin stored the wrong run identity'
            Assert-Equal ([int64]$nextRunningState.eventCheckpoint.sequence) ([int64]4) 'next Begin checkpoint is not sequence 4'
            $nextLedgerHash = Get-Hash $fixture.Events
            $nextLedgerText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
            $nextLedgerLines = @($nextLedgerText.Substring(0,$nextLedgerText.Length-1).Split([char]0x0A))
            Assert-Equal $nextLedgerLines.Count 4 'next Begin did not converge exact Snapshot-Begin-Record-Begin rows'
            $nextLedgerRows = @($nextLedgerLines | ForEach-Object { $_ | ConvertFrom-Json -ErrorAction Stop })
            Assert-Equal $nextLedgerRows[2].action 'Record' 'next Begin lost the delayed Record row'
            Assert-Equal $nextLedgerRows[2].workUnitId 'WU-EVENT-A' 'next Begin published the wrong Record identity'
            Assert-Equal $nextLedgerRows[3].action 'Begin' 'next Begin row action changed'
            Assert-Equal $nextLedgerRows[3].workUnitId 'WU-EVENT-B' 'next Begin row work-unit identity changed'

            Write-Utf8 $recordDelayRelease 'resume delayed Record publisher'
            Wait-TestWorkers $recordDelayWorkers 60000 'delayed-record-worker-timeout'
            $recordDelayResult = Read-WorkerResult $recordDelayWorkers[0]
            Assert-Equal ([int]$recordDelayResult.exitCode) 0 'delayed Record did not accept the later matching Record row'
            $recordDelayControllerResult = [string]$recordDelayResult.text | ConvertFrom-Json -ErrorAction Stop
            Assert-Equal $recordDelayControllerResult.reason 'work-unit-recorded' 'delayed Record result changed after next Begin'
            Assert-Equal $recordDelayControllerResult.workUnitId 'WU-EVENT-A' 'delayed Record result lost its work-unit identity'
            Assert-Equal $recordDelayControllerResult.stateSha256 (Get-Hash $fixture.State) 'delayed Record did not return the latest converged state hash'
            Assert-Equal (Get-Hash $fixture.Events) $nextLedgerHash 'delayed Record duplicated or rewrote the converged ledger'
            Assert-True (-not (($nextLedgerText + [string]$recordDelayResult.text).Contains($recordDelayRunId))) 'delayed Record race leaked the first raw run ID'
            Assert-True (-not (($nextLedgerText + [string]$recordDelayResult.text).Contains($nextBeginRunId))) 'delayed Record race leaked the next raw run ID'

            $nextEvidence = Write-RecordEvidence $fixture $nextRunningState 'WU-EVENT-B' $nextBeginRunId 'NoPatchNeeded'
            $nextRecord = Invoke-Controller @(
                '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-B','-RunId',$nextBeginRunId,
                '-Outcome','NoPatchNeeded','-EvidencePath',$nextEvidence.Relative
            )
            $nextRecordResult = Read-Result $nextRecord
            Assert-Equal $nextRecord.ExitCode 0 "next Begin cleanup Record failed: $($nextRecordResult.reason)"
            Assert-Equal @(Get-ChildItem -LiteralPath (Join-Path $fixture.Root '__patch_drop__\source-edit-locks') -Directory -Force -ErrorAction SilentlyContinue).Count 0 'delayed Record race retained a source lease'
        } finally {
            if (-not (Test-Path -LiteralPath $recordDelayRelease)) { Write-Utf8 $recordDelayRelease 'cleanup-release' }
            Stop-TestWorkers $recordDelayWorkers
            Remove-Item -LiteralPath $recordDelayMarker,$recordDelayReady,$recordDelayRelease -Force -ErrorAction SilentlyContinue
        }
        Write-Output 'DELAYED RECORD -> NEXT BEGIN: accepted=true duplicateRows=0'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($ProspectiveBoundsOnly) {
        $boundsBaselineStateHash = Get-Hash $fixture.State
        $boundsBaselineEventHash = Get-Hash $fixture.Events
        $boundsBaselineLockHash = Get-Hash $fixture.EventLock
        $sourceLockRoot = Join-Path $fixture.Root '__patch_drop__\source-edit-locks'
        $beginBoundCases = @(
            [pscustomobject]@{ Marker = '.event-test-running-row-too-large'; Reason = 'event-row-too-large'; RunId = 'event-bounds-begin-row-51' },
            [pscustomobject]@{ Marker = '.event-test-running-ledger-too-large'; Reason = 'event-ledger-too-large'; RunId = 'event-bounds-begin-ledger-52' },
            [pscustomobject]@{ Marker = '.event-test-running-row-count-exceeded'; Reason = 'event-row-count-exceeded'; RunId = 'event-bounds-begin-count-53' }
        )
        foreach ($boundCase in $beginBoundCases) {
            $boundMarker = Join-Path $fixture.Root $boundCase.Marker
            Write-Utf8 $boundMarker 'force prospective Begin capacity rejection'
            try {
                $boundBegin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$boundCase.RunId)
            } finally {
                Remove-Item -LiteralPath $boundMarker -Force -ErrorAction SilentlyContinue
            }
            $boundBeginResult = Read-Result $boundBegin
            Assert-Equal $boundBegin.ExitCode 1 "Begin accepted prospective bound marker $($boundCase.Marker)"
            Assert-Equal $boundBeginResult.reason $boundCase.Reason "Begin prospective bound reason changed for $($boundCase.Marker)"
            Assert-True ($boundBeginResult.committed -is [bool] -and -not [bool]$boundBeginResult.committed) "Begin prospective bound invented a commit for $($boundCase.Marker)"
            Assert-Equal (Get-Hash $fixture.State) $boundsBaselineStateHash "Begin prospective bound changed baseline state for $($boundCase.Marker)"
            Assert-Equal (Get-Hash $fixture.Events) $boundsBaselineEventHash "Begin prospective bound changed ledger for $($boundCase.Marker)"
            Assert-Equal (Get-Hash $fixture.EventLock) $boundsBaselineLockHash "Begin prospective bound changed event lock for $($boundCase.Marker)"
            Assert-Equal @(Get-ChildItem -LiteralPath $sourceLockRoot -Directory -Force -ErrorAction SilentlyContinue).Count 0 "Begin prospective bound retained a source lease for $($boundCase.Marker)"
        }

        $boundsRunId = 'event-bounds-record-54'
        $boundsBegin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$boundsRunId)
        $boundsBeginResult = Read-Result $boundsBegin
        Assert-Equal $boundsBegin.ExitCode 0 "bounds setup Begin failed: $($boundsBeginResult.reason)"
        $boundsRunningState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
        $boundsRunningStateHash = Get-Hash $fixture.State
        $boundsRunningEventHash = Get-Hash $fixture.Events
        $boundsEvidence = Write-RecordEvidence $fixture $boundsRunningState 'WU-EVENT-A' $boundsRunId 'NoPatchNeeded'

        $awaitingBoundCases = @(
            [pscustomobject]@{ Marker='.event-test-record-awaiting-release-row-too-large'; Reason='event-row-too-large' },
            [pscustomobject]@{ Marker='.event-test-record-awaiting-release-ledger-too-large'; Reason='event-ledger-too-large' },
            [pscustomobject]@{ Marker='.event-test-record-awaiting-release-row-count-exceeded'; Reason='event-row-count-exceeded' }
        )
        foreach ($awaitingCase in $awaitingBoundCases) {
            $awaitingBoundMarker = Join-Path $fixture.Root $awaitingCase.Marker
            Write-Utf8 $awaitingBoundMarker 'force future Record capacity rejection before historical commit'
            try {
                $awaitingBound = Invoke-Controller @(
                    '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$boundsRunId,
                    '-Outcome','NoPatchNeeded','-EvidencePath',$boundsEvidence.Relative
                )
            } finally {
                Remove-Item -LiteralPath $awaitingBoundMarker -Force -ErrorAction SilentlyContinue
            }
            $awaitingBoundResult = Read-Result $awaitingBound
            Assert-Equal $awaitingBound.ExitCode 1 "Record committed awaiting-release before prospective capacity proof: $($awaitingCase.Marker)"
            Assert-Equal $awaitingBoundResult.reason $awaitingCase.Reason "awaiting-release prospective capacity reason changed: $($awaitingCase.Marker)"
            Assert-True ($awaitingBoundResult.committed -is [bool] -and -not [bool]$awaitingBoundResult.committed) "awaiting-release preflight invented a business commit: $($awaitingCase.Marker)"
            Assert-Equal (Get-Hash $fixture.State) $boundsRunningStateHash "awaiting-release prospective rejection changed running state: $($awaitingCase.Marker)"
            Assert-Equal (Get-Hash $fixture.Events) $boundsRunningEventHash "awaiting-release prospective rejection changed ledger: $($awaitingCase.Marker)"
            Assert-Equal @(Get-ChildItem -LiteralPath $sourceLockRoot -Directory -Force -ErrorAction SilentlyContinue).Count 1 "awaiting-release prospective rejection released the running lease: $($awaitingCase.Marker)"
        }

        $terminalBoundCases = @(
            [pscustomobject]@{ Marker='.event-test-terminal-row-too-large'; Reason='event-row-too-large' },
            [pscustomobject]@{ Marker='.event-test-terminal-ledger-too-large'; Reason='event-ledger-too-large' },
            [pscustomobject]@{ Marker='.event-test-terminal-row-count-exceeded'; Reason='event-row-count-exceeded' }
        )
        foreach ($terminalCase in $terminalBoundCases) {
            $terminalBoundMarker = Join-Path $fixture.Root $terminalCase.Marker
            Write-Utf8 $terminalBoundMarker 'force Record capacity rejection at terminal CAS recheck'
            try {
                $terminalBound = Invoke-Controller @(
                    '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$boundsRunId,
                    '-Outcome','NoPatchNeeded','-EvidencePath',$boundsEvidence.Relative
                )
            } finally {
                Remove-Item -LiteralPath $terminalBoundMarker -Force -ErrorAction SilentlyContinue
            }
            $terminalBoundResult = Read-Result $terminalBound
            $awaitingState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
            $awaitingUnit = @($awaitingState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
            Assert-Equal $terminalBound.ExitCode 1 "terminal CAS accepted prospective capacity overflow: $($terminalCase.Marker)"
            Assert-Equal $terminalBoundResult.reason 'event-publication-pending' "terminal prospective failure lost committed pending classification: $($terminalCase.Marker)"
            Assert-Equal $terminalBoundResult.eventReason $terminalCase.Reason "terminal prospective capacity eventReason changed: $($terminalCase.Marker)"
            Assert-True ($terminalBoundResult.committed -is [bool] -and [bool]$terminalBoundResult.committed) "terminal prospective failure lost historical business commit: $($terminalCase.Marker)"
            Assert-Equal $awaitingUnit.transition.phase 'record-awaiting-release' "terminal prospective failure did not retain awaiting state: $($terminalCase.Marker)"
            Assert-Equal (Get-Hash $fixture.Events) $boundsRunningEventHash "terminal prospective rejection published Record early: $($terminalCase.Marker)"
            Assert-Equal @(Get-ChildItem -LiteralPath $sourceLockRoot -Directory -Force -ErrorAction SilentlyContinue).Count 0 "terminal prospective rejection retained a source lease: $($terminalCase.Marker)"
        }

        Remove-Item -LiteralPath $boundsEvidence.Path -Force -ErrorAction Stop
        $terminalRetry = Invoke-Controller @(
            '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$boundsRunId,
            '-Outcome','NoPatchNeeded','-EvidencePath',$boundsEvidence.Relative
        )
        $terminalRetryResult = Read-Result $terminalRetry
        Assert-Equal $terminalRetry.ExitCode 0 "terminal prospective retry failed: $($terminalRetryResult.reason)"
        $terminalState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
        $terminalUnit = @($terminalState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
        Assert-Equal $terminalUnit.status 'no_patch_needed' 'terminal prospective retry did not finalize exact committed record'
        $terminalLedgerText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
        Assert-Equal @($terminalLedgerText.Substring(0, $terminalLedgerText.Length - 1).Split([char]0x0A)).Count 3 'terminal prospective retry did not publish exactly one Record row'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($CheckpointTamperOnly) {
        $tamperBaselineStateHash = Get-Hash $fixture.State
        $tamperBaselineEventHash = Get-Hash $fixture.Events
        $tamperBaselineLockHash = Get-Hash $fixture.EventLock
        $sourceLockRoot = Join-Path $fixture.Root '__patch_drop__\source-edit-locks'
        foreach ($tamperPhase in @('begin-intent','running')) {
            $tamperMarker = Join-Path $fixture.Root ('.event-test-tamper-' + $tamperPhase + '-checkpoint')
            Write-Utf8 $tamperMarker 'tamper only the deep-copied public CAS candidate checkpoint'
            try {
                $tamperedBegin = Invoke-Controller @(
                    '-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A',
                    '-RunId',('event-checkpoint-tamper-' + $tamperPhase)
                )
            } finally {
                Remove-Item -LiteralPath $tamperMarker -Force -ErrorAction SilentlyContinue
            }
            $tamperedBeginResult = Read-Result $tamperedBegin
            Assert-Equal $tamperedBegin.ExitCode 1 "Begin accepted a $tamperPhase checkpoint-tampered CAS candidate"
            Assert-Equal $tamperedBeginResult.reason 'event-checkpoint-invalid' "$tamperPhase checkpoint tamper reason changed"
            Assert-True ($tamperedBeginResult.committed -is [bool] -and -not [bool]$tamperedBeginResult.committed) "$tamperPhase checkpoint tamper invented a commit"
            Assert-Equal (Get-Hash $fixture.State) $tamperBaselineStateHash "$tamperPhase checkpoint tamper changed baseline state"
            Assert-Equal (Get-Hash $fixture.Events) $tamperBaselineEventHash "$tamperPhase checkpoint tamper changed ledger"
            Assert-Equal (Get-Hash $fixture.EventLock) $tamperBaselineLockHash "$tamperPhase checkpoint tamper changed event lock"
            Assert-Equal @(Get-ChildItem -LiteralPath $sourceLockRoot -Directory -Force -ErrorAction SilentlyContinue).Count 0 "$tamperPhase checkpoint tamper retained a source lease"
        }

        $tamperRunId = 'event-checkpoint-tamper-record-55'
        $tamperBegin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$tamperRunId)
        $tamperBeginResult = Read-Result $tamperBegin
        Assert-Equal $tamperBegin.ExitCode 0 "checkpoint-tamper setup Begin failed: $($tamperBeginResult.reason)"
        $tamperRunningState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
        $tamperRunningStateHash = Get-Hash $fixture.State
        $tamperRunningEventHash = Get-Hash $fixture.Events
        $tamperEvidence = Write-RecordEvidence $fixture $tamperRunningState 'WU-EVENT-A' $tamperRunId 'NoPatchNeeded'

        $awaitingTamperMarker = Join-Path $fixture.Root '.event-test-tamper-record-awaiting-release-checkpoint'
        Write-Utf8 $awaitingTamperMarker 'tamper awaiting-release CAS candidate checkpoint'
        try {
            $awaitingTamper = Invoke-Controller @(
                '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$tamperRunId,
                '-Outcome','NoPatchNeeded','-EvidencePath',$tamperEvidence.Relative
            )
        } finally {
            Remove-Item -LiteralPath $awaitingTamperMarker -Force -ErrorAction SilentlyContinue
        }
        $awaitingTamperResult = Read-Result $awaitingTamper
        Assert-Equal $awaitingTamper.ExitCode 1 'Record accepted an awaiting-release checkpoint-tampered candidate'
        Assert-Equal $awaitingTamperResult.reason 'event-checkpoint-invalid' 'awaiting-release checkpoint tamper reason changed'
        Assert-True ($awaitingTamperResult.committed -is [bool] -and -not [bool]$awaitingTamperResult.committed) 'awaiting-release checkpoint tamper invented a business commit'
        Assert-Equal (Get-Hash $fixture.State) $tamperRunningStateHash 'awaiting-release checkpoint tamper changed running state'
        Assert-Equal (Get-Hash $fixture.Events) $tamperRunningEventHash 'awaiting-release checkpoint tamper changed ledger'
        Assert-Equal @(Get-ChildItem -LiteralPath $sourceLockRoot -Directory -Force -ErrorAction SilentlyContinue).Count 1 'awaiting-release checkpoint tamper released running lease'

        $terminalTamperMarker = Join-Path $fixture.Root '.event-test-tamper-terminal-checkpoint'
        Write-Utf8 $terminalTamperMarker 'tamper terminal CAS candidate checkpoint'
        try {
            $terminalTamper = Invoke-Controller @(
                '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$tamperRunId,
                '-Outcome','NoPatchNeeded','-EvidencePath',$tamperEvidence.Relative
            )
        } finally {
            Remove-Item -LiteralPath $terminalTamperMarker -Force -ErrorAction SilentlyContinue
        }
        $terminalTamperResult = Read-Result $terminalTamper
        $tamperAwaitingState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
        $tamperAwaitingUnit = @($tamperAwaitingState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
        Assert-Equal $terminalTamper.ExitCode 1 'Record accepted a terminal checkpoint-tampered candidate'
        Assert-Equal $terminalTamperResult.reason 'event-publication-pending' 'terminal checkpoint tamper lost committed pending classification'
        Assert-Equal $terminalTamperResult.eventReason 'event-checkpoint-invalid' 'terminal checkpoint tamper eventReason changed'
        Assert-True ($terminalTamperResult.committed -is [bool] -and [bool]$terminalTamperResult.committed) 'terminal checkpoint tamper lost historical business commit'
        Assert-Equal $tamperAwaitingUnit.transition.phase 'record-awaiting-release' 'terminal checkpoint tamper did not retain awaiting state'
        Assert-Equal (Get-Hash $fixture.Events) $tamperRunningEventHash 'terminal checkpoint tamper published Record early'
        Assert-Equal @(Get-ChildItem -LiteralPath $sourceLockRoot -Directory -Force -ErrorAction SilentlyContinue).Count 0 'terminal checkpoint tamper retained a source lease'

        Remove-Item -LiteralPath $tamperEvidence.Path -Force -ErrorAction Stop
        $tamperRetry = Invoke-Controller @(
            '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$tamperRunId,
            '-Outcome','NoPatchNeeded','-EvidencePath',$tamperEvidence.Relative
        )
        $tamperRetryResult = Read-Result $tamperRetry
        Assert-Equal $tamperRetry.ExitCode 0 "checkpoint-tampered terminal retry failed: $($tamperRetryResult.reason)"
        $tamperTerminalState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
        $tamperTerminalUnit = @($tamperTerminalState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
        Assert-Equal $tamperTerminalUnit.status 'no_patch_needed' 'checkpoint-tampered terminal retry did not finalize committed record'
        $tamperTerminalLedgerText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
        Assert-Equal @($tamperTerminalLedgerText.Substring(0, $tamperTerminalLedgerText.Length - 1).Split([char]0x0A)).Count 3 'checkpoint-tampered terminal retry did not publish exactly one Record row'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($ReachabilityOnly) {
        $reachabilityProbe = Join-Path $fixture.Root 'event-reachability-probe.ps1'
        $reachabilityStateHash = Get-Hash $fixture.State
        $reachabilityEventHash = Get-Hash $fixture.Events
        Write-Utf8 $reachabilityProbe @'
param([string]$Controller, [string]$StatePath, [string]$ProgramPath)
$ErrorActionPreference = 'Stop'
$env:AWX_EVENT_PURE_PROBE = '1'
$probeController = $Controller
$probeStatePath = $StatePath
$probeProgramPath = $ProgramPath
. $probeController
$script:root = $CanonicalRoot
$script:program = $probeProgramPath
$script:state = $probeStatePath
$stage = 'read'
try {
    $stateText = [IO.File]::ReadAllText($probeStatePath, $Utf8)
    $value = ConvertFrom-StrictJson $stateText 'malformed-state'
    $stage = 'manifest'
    $manifest = Get-ControllerManifest $probeProgramPath
    $stage = 'mutate'
    $unit = @($value.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-B')[0]
    $unit.status = 'hold'
    $unit | Add-Member -NotePropertyName record -NotePropertyValue ([pscustomobject][ordered]@{
        runId = 'unreachable-run-b'; outcome = 'Hold'; evidenceSha256 = ('A' * 64)
        targetPreimages = @(); targetPostimages = @(); redCommandId = $null; greenCommandIds = @()
        rollbackStatus = 'verified'; desktopFinalProof = 'evidence_needed'
    })
    $stage = 'validate'
    $null = Get-ValidatedStateObject $value $manifest -SynchronizeDerived
    [Console]::Out.Write('accepted')
    exit 0
} catch {
    $reason = [string]$_.Exception.Message
    if ($reason -notmatch '^[a-z0-9-]+$') { $reason = ('probe-' + $stage + '-failed') }
    [Console]::Out.Write($reason)
    exit 1
}
'@
        $reachabilityOutput = @(& $script:pwsh -NoProfile -ExecutionPolicy Bypass -File $reachabilityProbe -Controller $script:fixtureController -StatePath $fixture.State -ProgramPath $fixture.Program 2>&1)
        Assert-Equal $LASTEXITCODE 1 'pure checkpoint validator accepted terminal work behind a pending dependency'
        Assert-Equal ($reachabilityOutput -join '') 'event-checkpoint-invalid' 'unreachable terminal state did not fail at checkpoint reachability'
        Assert-Equal (Get-Hash $fixture.State) $reachabilityStateHash 'pure reachability probe changed state'
        Assert-Equal (Get-Hash $fixture.Events) $reachabilityEventHash 'pure reachability probe changed ledger'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($PhaseDeltaOnly) {
        $phaseProbe = Join-Path $fixture.Root 'event-phase-delta-probe.ps1'
        $phaseStateHash = Get-Hash $fixture.State
        $phaseEventHash = Get-Hash $fixture.Events
        Write-Utf8 $phaseProbe @'
param([string]$Controller, [string]$StatePath, [string]$ProgramPath)
$ErrorActionPreference = 'Stop'
$env:AWX_EVENT_PURE_PROBE = '1'
$probeController = $Controller
$probeStatePath = $StatePath
$probeProgramPath = $ProgramPath
. $probeController
$script:root = $CanonicalRoot
$script:program = $probeProgramPath
$script:state = $probeStatePath
$stage = 'read'
try {
    $current = ConvertFrom-StrictJson ([IO.File]::ReadAllText($probeStatePath, $Utf8)) 'malformed-state'
    $manifest = Get-ControllerManifest $probeProgramPath
    $topic = 'consolidated-' + ([string]$current.canonicalMarkdownSha256).Substring(0, 12).ToLowerInvariant() + '-WU-EVENT-A'

    $stage = 'intent'
    $intent = Copy-ControllerValue $current
    $intentUnit = @($intent.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
    $intentUnit | Add-Member -NotePropertyName transition -NotePropertyValue ([pscustomobject][ordered]@{
        phase = 'begin-intent'; runId = 'phase-run-a'; owner = 'desktop'; leaseTopic = $topic
        canonicalMarkdownSha256 = [string]$current.canonicalMarkdownSha256
    })
    Assert-ProspectiveEventPreflight $current $intent $manifest 'begin-intent'

    $stage = 'recovery'
    $recovery = Copy-ControllerValue $current
    $recoveryUnit = @($recovery.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
    $recoveryUnit | Add-Member -NotePropertyName transition -NotePropertyValue ([pscustomobject][ordered]@{
        phase = 'begin-recovery-required'; runId = 'phase-run-a'; owner = 'desktop'; leaseTopic = $topic
    })
    Assert-ProspectiveEventPreflight $current $recovery $manifest 'begin-recovery-required'

    $stage = 'cleanup'
    Assert-ProspectiveEventPreflight $current (Copy-ControllerValue $current) $manifest 'begin-cleanup-complete'

    $stage = 'forged'
    $forged = Copy-ControllerValue $intent
    $forgedUnit = @($forged.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-B')[0]
    $forgedUnit.status = 'hold'
    $forgedUnit | Add-Member -NotePropertyName record -NotePropertyValue ([pscustomobject][ordered]@{
        runId = 'unreachable-run-b'; outcome = 'Hold'; evidenceSha256 = ('A' * 64)
        targetPreimages = @(); targetPostimages = @(); redCommandId = $null; greenCommandIds = @()
        rollbackStatus = 'verified'; desktopFinalProof = 'evidence_needed'
    })
    Assert-ProspectiveEventPreflight $current $forged $manifest 'begin-intent'
    [Console]::Out.Write('accepted')
    exit 0
} catch {
    $reason = [string]$_.Exception.Message
    if ($reason -notmatch '^[a-z0-9-]+$') { $reason = 'probe-failed' }
    [Console]::Out.Write('phase-' + $stage + '-' + $reason)
    exit 1
}
'@
        $phaseOutput = @(& $script:pwsh -NoProfile -ExecutionPolicy Bypass -File $phaseProbe -Controller $script:fixtureController -StatePath $fixture.State -ProgramPath $fixture.Program 2>&1)
        Assert-Equal $LASTEXITCODE 1 'zero-delta preflight accepted unrelated terminal progress'
        Assert-Equal ($phaseOutput -join '') 'phase-forged-event-checkpoint-invalid' 'phase delta failed at the wrong boundary'
        Assert-Equal (Get-Hash $fixture.State) $phaseStateHash 'phase delta probe changed state'
        Assert-Equal (Get-Hash $fixture.Events) $phaseEventHash 'phase delta probe changed ledger'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($CommittedBeginOnly) {
        $committedRunId = 'event-run-committed-auto-46'
        $syncFailureMarker = Join-Path $fixture.Root '.event-test-fail-sync'
        Write-Utf8 $syncFailureMarker 'fail post-running event sync'
        $committedBegin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-RunId',$committedRunId)
        $committedBeginResult = Read-Result $committedBegin
        $committedState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
        $committedUnit = @($committedState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
        Assert-Equal $committedBegin.ExitCode 1 'forced post-running event failure did not fail the public Begin'
        Assert-Equal (@($committedBeginResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId,committed,stateSha256,canonicalMarkdownSha256,eventReason' 'post-running committed envelope shape changed'
        Assert-Equal $committedBeginResult.reason 'event-publication-pending' 'post-running event failure did not report pending publication'
        Assert-Equal $committedBeginResult.workUnitId 'WU-EVENT-A' 'auto-selected committed Begin omitted the actual work unit'
        Assert-True ($committedBeginResult.committed -is [bool] -and [bool]$committedBeginResult.committed) 'post-running event failure did not set committed=true'
        Assert-Equal $committedBeginResult.stateSha256 (Get-Hash $fixture.State) 'post-running committed state hash changed'
        Assert-Equal $committedBeginResult.canonicalMarkdownSha256 $committedState.canonicalMarkdownSha256 'post-running committed canonical hash changed'
        Assert-Equal $committedBeginResult.eventReason 'event-lock-failed' 'post-running event failure did not retain a closed event reason'
        Assert-Equal $committedUnit.status 'running' 'forced event failure did not retain running state'
        Assert-Equal $committedUnit.execution.runId $committedRunId 'forced event failure changed the committed run identity'
        $oneAheadText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
        Assert-Equal @($oneAheadText.Substring(0, $oneAheadText.Length - 1).Split([char]0x0A)).Count 1 'post-running failure appended a partial Begin row'
        Assert-True (-not $committedBegin.Text.Contains($committedRunId)) 'post-running committed error leaked raw run ID'

        Remove-Item -LiteralPath $syncFailureMarker -Force -ErrorAction Stop
        $omittedRetry = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-RunId',$committedRunId)
        $omittedRetryResult = Read-Result $omittedRetry
        Assert-Equal $omittedRetry.ExitCode 0 "omitted-unit committed Begin retry failed: $($omittedRetryResult.reason)"
        Assert-Equal $omittedRetryResult.reason 'already-running' 'omitted-unit retry did not replay the committed running unit'
        Assert-Equal $omittedRetryResult.workUnitId 'WU-EVENT-A' 'omitted-unit retry resolved the wrong running unit'
        $repairedLedgerText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
        Assert-Equal @($repairedLedgerText.Substring(0, $repairedLedgerText.Length - 1).Split([char]0x0A)).Count 2 'omitted-unit retry did not converge exactly one Begin row'
        Assert-True (-not (($committedBegin.Text + $omittedRetry.Text + $repairedLedgerText).Contains($committedRunId))) 'omitted-unit retry leaked raw run ID'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($AppendRollbackOnly) {
        $appendRunId = 'event-run-append-rollback-47'
        $appendFailureMarker = Join-Path $fixture.Root '.event-test-fail-append'
        $oldLedgerHash = Get-Hash $fixture.Events
        $oldLedgerLength = [IO.File]::ReadAllBytes($fixture.Events).Length
        Write-Utf8 $appendFailureMarker 'fail after partial append'
        $appendFailure = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$appendRunId)
        $appendFailureResult = Read-Result $appendFailure
        $appendCommittedState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
        Assert-Equal $appendFailure.ExitCode 1 'partial append hook did not fail public Begin'
        Assert-Equal $appendFailureResult.reason 'event-publication-pending' 'partial append failure did not close as publication pending'
        Assert-True ($appendFailureResult.committed -is [bool] -and [bool]$appendFailureResult.committed) 'partial append failure lost committed=true'
        Assert-Equal $appendFailureResult.workUnitId 'WU-EVENT-A' 'partial append failure lost actual work unit'
        Assert-Equal ([IO.File]::ReadAllBytes($fixture.Events).Length) $oldLedgerLength 'partial append rollback did not restore exact old length'
        Assert-Equal (Get-Hash $fixture.Events) $oldLedgerHash 'partial append rollback did not restore exact old hash'
        Assert-Equal ([int64]$appendCommittedState.eventCheckpoint.sequence) ([int64]2) 'partial append failure lost committed Begin checkpoint'
        Assert-True (-not $appendFailure.Text.Contains($appendRunId)) 'partial append failure leaked raw run ID'

        Remove-Item -LiteralPath $appendFailureMarker -Force -ErrorAction Stop
        $appendRetry = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$appendRunId)
        $appendRetryResult = Read-Result $appendRetry
        Assert-Equal $appendRetry.ExitCode 0 "partial append retry failed: $($appendRetryResult.reason)"
        Assert-Equal $appendRetryResult.reason 'already-running' 'partial append retry did not replay committed Begin'
        $appendLedgerText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
        Assert-Equal @($appendLedgerText.Substring(0, $appendLedgerText.Length - 1).Split([char]0x0A)).Count 2 'partial append retry did not publish exactly seq2'
        Assert-True (-not $appendLedgerText.Contains($appendRunId)) 'partial append retry leaked raw run ID'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($RollbackSuffixOnly) {
        $suffixRunId = 'event-run-rollback-wrong-suffix-97'
        $suffixMarker = Join-Path $fixture.Root '.event-test-fail-append-wrong-suffix'
        $suffixOldStateHash = Get-Hash $fixture.State
        $suffixOldLedgerHash = Get-Hash $fixture.Events
        $suffixOldLength = [IO.File]::ReadAllBytes($fixture.Events).Length
        Write-Utf8 $suffixMarker 'write an unrelated suffix while preserving the old prefix'
        $suffixFailure = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$suffixRunId)
        $suffixResult = Read-Result $suffixFailure
        Assert-Equal $suffixFailure.ExitCode 1 'wrong-suffix rollback probe did not fail public Begin'
        Assert-Equal $suffixResult.reason 'event-publication-pending' 'wrong-suffix rollback lost committed pending reason'
        Assert-Equal $suffixResult.eventReason 'event-rollback-unproven' 'wrong-suffix rollback was not classified unproven'
        Assert-True ($suffixResult.committed -is [bool] -and [bool]$suffixResult.committed) 'wrong-suffix rollback lost committed=true'
        Assert-Equal $suffixResult.workUnitId 'WU-EVENT-A' 'wrong-suffix rollback lost actual work unit'
        Assert-True ((Get-Hash $fixture.State) -cne $suffixOldStateHash) 'wrong-suffix rollback did not retain committed running state'
        $suffixBytes = [IO.File]::ReadAllBytes($fixture.Events)
        Assert-Equal $suffixBytes.Length ($suffixOldLength + 17) 'wrong-suffix rollback truncated unowned bytes'
        Assert-True ((Get-Hash $fixture.Events) -cne $suffixOldLedgerHash) 'wrong-suffix rollback silently restored the old ledger'
        Assert-Equal @($suffixBytes[$suffixOldLength..($suffixBytes.Length - 1)] | Where-Object { $_ -ne 0x5A }).Count 0 'wrong-suffix rollback changed the unowned suffix bytes'
        $suffixCorruptHash = Get-Hash $fixture.Events
        $suffixCommittedStateHash = Get-Hash $fixture.State

        Remove-Item -LiteralPath $suffixMarker -Force -ErrorAction Stop
        $suffixRetry = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$suffixRunId)
        $suffixRetryResult = Read-Result $suffixRetry
        Assert-Equal $suffixRetry.ExitCode 1 'later invocation repaired an unowned suffix'
        Assert-Equal $suffixRetryResult.reason 'event-ledger-diverged' 'later wrong-suffix retry did not fail as divergence'
        Assert-True ($suffixRetryResult.committed -is [bool] -and -not [bool]$suffixRetryResult.committed) 'later wrong-suffix divergence invented a commit'
        Assert-Equal (Get-Hash $fixture.Events) $suffixCorruptHash 'later invocation rewrote the unowned suffix'
        Assert-Equal (Get-Hash $fixture.State) $suffixCommittedStateHash 'later invocation changed committed state after wrong suffix'
        Assert-True (-not (($suffixFailure.Text + $suffixRetry.Text).Contains($suffixRunId))) 'wrong-suffix rollback path leaked raw run ID'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($PrefixTamperOnly) {
        $prefixRunId = 'event-run-prefix-tamper-95'
        $prefixMarker = Join-Path $fixture.Root '.event-test-tamper-prefix-before-open'
        $oldPrefix = [IO.File]::ReadAllBytes($fixture.Events)
        $expectedTamperedPrefix = [Array]::CreateInstance([byte], $oldPrefix.Length)
        [Array]::Copy($oldPrefix, $expectedTamperedPrefix, $oldPrefix.Length)
        $expectedTamperedPrefix[0] = [byte]($expectedTamperedPrefix[0] -bxor 1)
        $expectedTamperedHash = Get-ByteHash ([byte[]]$expectedTamperedPrefix)
        Write-Utf8 $prefixMarker 'tamper exact old prefix after validation and before exclusive open'

        $prefixFailure = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$prefixRunId)
        $prefixResult = Read-Result $prefixFailure
        $prefixCommittedState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
        Assert-Equal $prefixFailure.ExitCode 1 'same-length prefix tamper did not fail public Begin'
        Assert-Equal $prefixResult.reason 'event-publication-pending' 'same-length prefix tamper lost committed publication status'
        Assert-Equal $prefixResult.eventReason 'event-ledger-diverged' 'same-length prefix tamper did not fail as divergence'
        Assert-True ($prefixResult.committed -is [bool] -and [bool]$prefixResult.committed) 'same-length prefix tamper lost committed=true'
        Assert-Equal $prefixResult.workUnitId 'WU-EVENT-A' 'same-length prefix tamper lost actual work unit'
        Assert-Equal ([int64]$prefixCommittedState.eventCheckpoint.sequence) ([int64]2) 'same-length prefix tamper lost committed Begin checkpoint'
        Assert-Equal ([IO.File]::ReadAllBytes($fixture.Events).Length) $oldPrefix.Length 'same-length prefix tamper was followed by an appended row'
        Assert-Equal (Get-Hash $fixture.Events) $expectedTamperedHash 'same-length prefix tamper input was not preserved byte-for-byte'
        Assert-True (-not $prefixFailure.Text.Contains($prefixRunId)) 'same-length prefix tamper path leaked raw run ID'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($RollbackUnprovenOnly) {
        $unprovenRunId = 'event-run-rollback-unproven-48'
        $unprovenMarker = Join-Path $fixture.Root '.event-test-fail-append-unproven'
        $unprovenOldStateHash = Get-Hash $fixture.State
        $unprovenOldLedgerHash = Get-Hash $fixture.Events
        $unprovenOldLength = [IO.File]::ReadAllBytes($fixture.Events).Length
        Write-Utf8 $unprovenMarker 'corrupt old prefix before append failure'
        $unprovenFailure = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$unprovenRunId)
        $unprovenResult = Read-Result $unprovenFailure
        Assert-Equal $unprovenFailure.ExitCode 1 'unproven rollback hook did not fail public Begin'
        Assert-Equal $unprovenResult.reason 'event-publication-pending' 'unproven rollback lost committed pending reason'
        Assert-Equal $unprovenResult.eventReason 'event-rollback-unproven' 'unproven rollback did not expose closed proof failure'
        Assert-True ($unprovenResult.committed -is [bool] -and [bool]$unprovenResult.committed) 'unproven rollback lost committed=true'
        Assert-Equal $unprovenResult.workUnitId 'WU-EVENT-A' 'unproven rollback lost actual work unit'
        Assert-True ((Get-Hash $fixture.State) -cne $unprovenOldStateHash) 'unproven rollback did not retain committed running state'
        Assert-True ((Get-Hash $fixture.Events) -cne $unprovenOldLedgerHash) 'unproven rollback silently restored a corrupted prefix'
        Assert-Equal ([IO.File]::ReadAllBytes($fixture.Events).Length) ($unprovenOldLength + 17) 'unproven rollback changed the corrupt append length'
        $unprovenCorruptHash = Get-Hash $fixture.Events
        $unprovenCommittedStateHash = Get-Hash $fixture.State

        Remove-Item -LiteralPath $unprovenMarker -Force -ErrorAction Stop
        $unprovenRetry = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$unprovenRunId)
        $unprovenRetryResult = Read-Result $unprovenRetry
        Assert-Equal $unprovenRetry.ExitCode 1 'later invocation repaired an unproven partial ledger'
        Assert-Equal $unprovenRetryResult.reason 'event-ledger-diverged' 'later unproven retry did not fail as divergence'
        Assert-True ($unprovenRetryResult.committed -is [bool] -and -not [bool]$unprovenRetryResult.committed) 'later divergence invented a new commit'
        Assert-Equal (Get-Hash $fixture.Events) $unprovenCorruptHash 'later invocation rewrote unproven ledger bytes'
        Assert-Equal (Get-Hash $fixture.State) $unprovenCommittedStateHash 'later invocation changed committed state'
        Assert-True (-not (($unprovenFailure.Text + $unprovenRetry.Text).Contains($unprovenRunId))) 'unproven rollback path leaked raw run ID'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    $runId = 'event-run-raw-42'
    $begin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$runId)
    $beginResult = Read-Result $begin
    Assert-Equal $begin.ExitCode 0 "temp Begin failed: $($beginResult.reason)"
    Assert-Equal $beginResult.reason 'work-unit-begun' 'temp Begin did not persist running state'

    $eventBytes = [IO.File]::ReadAllBytes($fixture.Events)
    Assert-True ($eventBytes.Length -gt 0 -and $eventBytes[$eventBytes.Length - 1] -eq 0x0A) 'event ledger is not LF terminated'
    Assert-True (-not ($eventBytes.Length -ge 3 -and $eventBytes[0] -eq 0xEF -and $eventBytes[1] -eq 0xBB -and $eventBytes[2] -eq 0xBF)) 'event ledger has a BOM'
    Assert-Equal @($eventBytes | Where-Object { $_ -eq 0x0D }).Count 0 'event ledger contains CR bytes'
    $eventText = $script:utf8.GetString($eventBytes)
    $eventLines = @($eventText.Substring(0, $eventText.Length - 1).Split([char]0x0A))
    Assert-Equal $eventLines.Count 2 'Begin did not publish exactly one seq2 row'
    $snapshotRow = $eventLines[0] | ConvertFrom-Json -ErrorAction Stop
    $beginRow = $eventLines[1] | ConvertFrom-Json -ErrorAction Stop
    $eventKeys = 'schemaVersion,sequence,previousEventSha256,eventId,eventSha256,programId,canonicalMarkdownSha256,action,workUnitId,runIdSha256,result,stateProjectionSha256,evidenceSha256,recordSha256'
    Assert-Equal (@($beginRow.PSObject.Properties.Name) -join ',') $eventKeys 'Begin row does not have the exact ordered 14-field shape'
    Assert-Equal ([int64]$beginRow.sequence) ([int64]2) 'Begin row sequence is not 2'
    Assert-Equal $beginRow.previousEventSha256 $snapshotRow.eventSha256 'Begin row is not chained to Snapshot'
    Assert-Equal $beginRow.action 'Begin' 'seq2 action is not Begin'
    Assert-Equal $beginRow.workUnitId 'WU-EVENT-A' 'seq2 work-unit identity changed'
    Assert-Equal $beginRow.result 'running' 'seq2 result is not running'
    $expectedRunHash = Get-ByteHash $script:utf8.GetBytes("awx.notebook.directive.run-id.v1`0$runId")
    Assert-Equal $beginRow.runIdSha256 $expectedRunHash 'Begin row runIdSha256 is not domain separated'
    Assert-True (-not $eventText.Contains($runId)) 'event ledger leaked the raw run ID'

    if ($HashOracleOnly) {
        $oracleRunningState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
        Assert-Equal $snapshotRow.stateProjectionSha256 (Get-IndependentStateProjectionHash $state) 'independent Snapshot projection hash mismatch'
        Assert-Equal $beginRow.stateProjectionSha256 (Get-IndependentStateProjectionHash $oracleRunningState) 'independent Begin projection hash mismatch'
        foreach ($oracleRow in @($snapshotRow, $beginRow)) {
            Assert-True ([string]$oracleRow.programId -match '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') 'hash-oracle programId is not bounded ASCII'
            foreach ($hashName in @('canonicalMarkdownSha256','stateProjectionSha256','eventId','eventSha256')) {
                Assert-True ([string]$oracleRow.$hashName -cmatch '^[0-9A-F]{64}$') "hash-oracle $hashName is not uppercase SHA-256"
            }
        }
        foreach ($nullName in @('workUnitId','runIdSha256','evidenceSha256','recordSha256')) {
            Assert-True ($null -eq $snapshotRow.PSObject.Properties[$nullName].Value) "Snapshot hash-oracle $nullName nullability changed"
        }
        foreach ($nullName in @('evidenceSha256','recordSha256')) {
            Assert-True ($null -eq $beginRow.PSObject.Properties[$nullName].Value) "Begin hash-oracle $nullName nullability changed"
        }
        $snapshotTexts = Get-IndependentEventTexts $snapshotRow
        $beginTexts = Get-IndependentEventTexts $beginRow
        Assert-Equal $snapshotRow.eventId (Get-ByteHash $script:utf8.GetBytes($snapshotTexts.idInput)) 'independent Snapshot eventId oracle mismatch'
        Assert-Equal $snapshotRow.eventSha256 (Get-ByteHash $script:utf8.GetBytes($snapshotTexts.hashInput)) 'independent Snapshot eventSha256 oracle mismatch'
        Assert-Equal $eventLines[0] $snapshotTexts.rowText 'persisted Snapshot raw row bytes differ from independent ordered bytes'
        Assert-Equal $beginRow.eventId (Get-ByteHash $script:utf8.GetBytes($beginTexts.idInput)) 'independent Begin eventId oracle mismatch'
        Assert-Equal $beginRow.eventSha256 (Get-ByteHash $script:utf8.GetBytes($beginTexts.hashInput)) 'independent Begin eventSha256 oracle mismatch'
        Assert-Equal $eventLines[1] $beginTexts.rowText 'persisted Begin raw row bytes differ from independent ordered bytes'

        $oracleEvidence = Write-RecordEvidence $fixture $oracleRunningState 'WU-EVENT-A' $runId 'NoPatchNeeded'
        $oracleRecord = Invoke-Controller @(
            '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$runId,
            '-Outcome','NoPatchNeeded','-EvidencePath',$oracleEvidence.Relative
        )
        $oracleRecordResult = Read-Result $oracleRecord
        Assert-Equal $oracleRecord.ExitCode 0 "hash-oracle Record failed: $($oracleRecordResult.reason)"
        $oracleTerminalState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
        $oracleTerminalUnit = @($oracleTerminalState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
        $oracleLedgerBytes = [IO.File]::ReadAllBytes($fixture.Events)
        $oracleLedgerText = $script:utf8.GetString($oracleLedgerBytes)
        $oracleLines = @($oracleLedgerText.Substring(0, $oracleLedgerText.Length - 1).Split([char]0x0A))
        Assert-Equal $oracleLines.Count 3 'hash-oracle Record did not persist exactly sequence 3'
        $oracleRecordRow = $oracleLines[2] | ConvertFrom-Json -ErrorAction Stop
        Assert-Equal $oracleRecordRow.stateProjectionSha256 (Get-IndependentStateProjectionHash $oracleTerminalState) 'independent Record projection hash mismatch'
        Assert-Equal $oracleRecordRow.recordSha256 (Get-IndependentRecordHash $oracleTerminalUnit.record) 'independent durable record hash mismatch'
        Assert-Equal $oracleRecordRow.evidenceSha256 $oracleEvidence.Sha256 'hash-oracle Record evidence hash changed'
        $recordTexts = Get-IndependentEventTexts $oracleRecordRow
        Assert-Equal $oracleRecordRow.eventId (Get-ByteHash $script:utf8.GetBytes($recordTexts.idInput)) 'independent Record eventId oracle mismatch'
        Assert-Equal $oracleRecordRow.eventSha256 (Get-ByteHash $script:utf8.GetBytes($recordTexts.hashInput)) 'independent Record eventSha256 oracle mismatch'
        Assert-Equal $oracleLines[2] $recordTexts.rowText 'persisted Record raw row bytes differ from independent ordered bytes'
        Assert-True (-not $oracleLedgerText.Contains($runId)) 'hash-oracle ledger leaked raw run ID'
        Assert-True (-not $oracleLedgerText.Contains($oracleEvidence.Relative)) 'hash-oracle ledger leaked evidence path'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    $runningState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
    Assert-Equal ($runningState.eventCheckpoint | ConvertTo-Json -Compress) ($beginRow | ConvertTo-Json -Compress) 'running checkpoint does not equal seq2 row'
    $beforeReplayHash = Get-Hash $fixture.Events
    $replay = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$runId)
    $replayResult = Read-Result $replay
    Assert-Equal $replay.ExitCode 0 "same-request Begin replay failed: $($replayResult.reason)"
    Assert-Equal $replayResult.reason 'already-running' 'same-request Begin replay was not idempotent'
    Assert-Equal (Get-Hash $fixture.Events) $beforeReplayHash 'same-request Begin replay duplicated or changed an event row'
    Assert-True (-not (($begin.Text + $replay.Text).Contains($runId))) 'public Begin result leaked the raw run ID'

    if ($PartialTailOnly -or $MalformedLedgerOnly) {
        $corruptStateHash = Get-Hash $fixture.State
        $corruptLockHash = Get-Hash $fixture.EventLock
        if ($PartialTailOnly) {
            $corruptText = $eventLines[0] + "`n" + $eventLines[1].Substring(0, 17)
            $caseName = 'proper partial tail'
        } else {
            $corruptText = $eventLines[0] + "`n{}`n"
            $caseName = 'malformed complete row'
        }
        Write-Utf8 $fixture.Events $corruptText
        $corruptEventHash = Get-Hash $fixture.Events
        $corruptRead = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
        $corruptResult = Read-Result $corruptRead
        Assert-Equal $corruptRead.ExitCode 1 "Status accepted $caseName"
        Assert-Equal (@($corruptResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId,committed,stateSha256,canonicalMarkdownSha256,eventReason' "$caseName error envelope shape changed"
        Assert-Equal $corruptResult.reason 'event-ledger-diverged' "$caseName did not fail as ledger divergence"
        Assert-Equal $corruptResult.eventReason 'event-ledger-diverged' "$caseName eventReason changed"
        Assert-True ($corruptResult.committed -is [bool] -and -not [bool]$corruptResult.committed) "$caseName invented a new commit"
        Assert-Equal (Get-Hash $fixture.State) $corruptStateHash "$caseName Status changed state"
        Assert-Equal (Get-Hash $fixture.Events) $corruptEventHash "$caseName Status rewrote ledger bytes"
        Assert-Equal (Get-Hash $fixture.EventLock) $corruptLockHash "$caseName Status changed event lock"
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($RelationMatrixOnly) {
        $relationStateHash = Get-Hash $fixture.State
        $relationLockHash = Get-Hash $fixture.EventLock
        $relationCases = @(
            [pscustomobject]@{ Name = 'absent ledger at sequence 2'; Mode = 'absent'; Text = $null },
            [pscustomobject]@{ Name = 'zero-byte ledger at sequence 2'; Mode = 'text'; Text = '' },
            [pscustomobject]@{ Name = 'extra ghost row'; Mode = 'text'; Text = ($eventText + $eventLines[1] + "`n") },
            [pscustomobject]@{ Name = 'reordered Snapshot and Begin rows'; Mode = 'text'; Text = ($eventLines[1] + "`n" + $eventLines[0] + "`n") },
            [pscustomobject]@{ Name = 'changed canonical prefix'; Mode = 'text'; Text = $eventText.Replace('"result":"created"','"result":"running"') },
            [pscustomobject]@{ Name = 'v1 row contamination'; Mode = 'text'; Text = $eventText.Replace('awx.notebook.directive.event.v2','awx.notebook.directive.event.v1') }
        )
        foreach ($relationCase in $relationCases) {
            if ($relationCase.Mode -ceq 'absent') {
                Remove-Item -LiteralPath $fixture.Events -Force -ErrorAction Stop
                $beforeExists = $false
                $beforeHash = $null
            } else {
                Write-Utf8 $fixture.Events ([string]$relationCase.Text)
                $beforeExists = $true
                $beforeHash = Get-Hash $fixture.Events
            }
            $relationRead = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
            $relationResult = Read-Result $relationRead
            Assert-Equal $relationRead.ExitCode 1 "Status accepted $($relationCase.Name)"
            Assert-Equal $relationResult.reason 'event-ledger-diverged' "$($relationCase.Name) reason changed"
            Assert-Equal $relationResult.eventReason 'event-ledger-diverged' "$($relationCase.Name) eventReason changed"
            Assert-True ($relationResult.committed -is [bool] -and -not [bool]$relationResult.committed) "$($relationCase.Name) invented a commit"
            Assert-Equal (Get-Hash $fixture.State) $relationStateHash "$($relationCase.Name) changed state"
            Assert-Equal (Get-Hash $fixture.EventLock) $relationLockHash "$($relationCase.Name) changed lock"
            Assert-Equal (Test-Path -LiteralPath $fixture.Events) $beforeExists "$($relationCase.Name) changed ledger presence"
            if ($beforeExists) { Assert-Equal (Get-Hash $fixture.Events) $beforeHash "$($relationCase.Name) changed ledger bytes" }
            Write-Utf8 $fixture.Events $eventText
        }
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($CorruptionMatrixOnly) {
        $corruptionStateHash = Get-Hash $fixture.State
        $corruptionLockHash = Get-Hash $fixture.EventLock
        $baseEventBytes = [IO.File]::ReadAllBytes($fixture.Events)
        $baseEventHash = Get-Hash $fixture.Events
        $withBegin = {
            param([string]$MutatedBegin)
            return $eventLines[0] + "`n" + $MutatedBegin + "`n"
        }
        $eventIdLiteral = '"eventId":"' + [string]$beginRow.eventId + '"'
        $eventShaLiteral = '"eventSha256":"' + [string]$beginRow.eventSha256 + '"'
        $projectionLiteral = '"stateProjectionSha256":"' + [string]$beginRow.stateProjectionSha256 + '"'
        $corruptionCases = @(
            [pscustomobject]@{ Name='UTF-8 BOM'; Bytes=[byte[]](@(0xEF,0xBB,0xBF) + @($baseEventBytes)) },
            [pscustomobject]@{ Name='CRLF'; Bytes=$script:utf8.GetBytes($eventText.Replace("`n","`r`n")) },
            [pscustomobject]@{ Name='blank line'; Bytes=$script:utf8.GetBytes($eventLines[0] + "`n`n" + $eventLines[1] + "`n") },
            [pscustomobject]@{ Name='missing final LF'; Bytes=$script:utf8.GetBytes($eventText.Substring(0,$eventText.Length-1)) },
            [pscustomobject]@{ Name='duplicate JSON key'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace('"result":"running"','"result":"running","result":"running"'))) },
            [pscustomobject]@{ Name='alternate property order'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace(($eventIdLiteral + ',' + $eventShaLiteral),($eventShaLiteral + ',' + $eventIdLiteral)))) },
            [pscustomobject]@{ Name='unknown field'; Bytes=$script:utf8.GetBytes((& $withBegin ($eventLines[1].Substring(0,$eventLines[1].Length-1) + ',"unknown":"x"}'))) },
            [pscustomobject]@{ Name='v1 schema'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace('awx.notebook.directive.event.v2','awx.notebook.directive.event.v1'))) },
            [pscustomobject]@{ Name='wrong scalar type'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace('"sequence":2','"sequence":"2"'))) },
            [pscustomobject]@{ Name='lowercase hash'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace($eventIdLiteral,('"eventId":"' + ([string]$beginRow.eventId).ToLowerInvariant() + '"')))) },
            [pscustomobject]@{ Name='wrong nullable evidence'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace('"evidenceSha256":null','"evidenceSha256":"' + ('A'*64) + '"'))) },
            [pscustomobject]@{ Name='omitted nullable record'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace(',"recordSha256":null',''))) },
            [pscustomobject]@{ Name='unknown work unit'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace('"workUnitId":"WU-EVENT-A"','"workUnitId":"WU-UNKNOWN"'))) },
            [pscustomobject]@{ Name='wrong action'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace('"action":"Begin"','"action":"Record"'))) },
            [pscustomobject]@{ Name='wrong result'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace('"result":"running"','"result":"green"'))) },
            [pscustomobject]@{ Name='wrong projection'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace($projectionLiteral,('"stateProjectionSha256":"' + ('A'*64) + '"')))) },
            [pscustomobject]@{ Name='wrong record hash'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace('"recordSha256":null','"recordSha256":"' + ('B'*64) + '"'))) },
            [pscustomobject]@{ Name='wrong event hash'; Bytes=$script:utf8.GetBytes((& $withBegin $eventLines[1].Replace($eventShaLiteral,('"eventSha256":"' + ('C'*64) + '"')))) }
        )
        foreach ($corruptionCase in $corruptionCases) {
            Assert-True ((Get-ByteHash $corruptionCase.Bytes) -cne $baseEventHash) "$($corruptionCase.Name) fixture did not change ledger bytes"
            [IO.File]::WriteAllBytes($fixture.Events, [byte[]]$corruptionCase.Bytes)
            $corruptHash = Get-Hash $fixture.Events
            $corruptStatus = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
            $corruptResult = Read-Result $corruptStatus
            Assert-Equal $corruptStatus.ExitCode 1 "Status accepted $($corruptionCase.Name) corruption"
            Assert-Equal (@($corruptResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId,committed,stateSha256,canonicalMarkdownSha256,eventReason' "$($corruptionCase.Name) envelope shape changed"
            Assert-Equal $corruptResult.reason 'event-ledger-diverged' "$($corruptionCase.Name) reason changed"
            Assert-Equal $corruptResult.eventReason 'event-ledger-diverged' "$($corruptionCase.Name) eventReason changed"
            Assert-True ($corruptResult.committed -is [bool] -and -not [bool]$corruptResult.committed) "$($corruptionCase.Name) invented a commit"
            Assert-Equal (Get-Hash $fixture.Events) $corruptHash "$($corruptionCase.Name) rejection changed ledger bytes"
            Assert-Equal (Get-Hash $fixture.State) $corruptionStateHash "$($corruptionCase.Name) rejection changed state"
            Assert-Equal (Get-Hash $fixture.EventLock) $corruptionLockHash "$($corruptionCase.Name) rejection changed event lock"
            [IO.File]::WriteAllBytes($fixture.Events, $baseEventBytes)
        }
        Assert-Equal (Get-Hash $fixture.Events) $baseEventHash 'corruption matrix did not restore the exact baseline ledger'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($BoundsOnly) {
        $boundsStateHash = Get-Hash $fixture.State
        $boundsLockHash = Get-Hash $fixture.EventLock
        $sixRows = ((1..6 | ForEach-Object { $eventLines[0] }) -join "`n") + "`n"
        Write-Utf8 $fixture.Events $sixRows
        $sixRowsHash = Get-Hash $fixture.Events
        $sixRowsRead = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
        $sixRowsResult = Read-Result $sixRowsRead
        Assert-Equal $sixRowsRead.ExitCode 1 'Status accepted six non-prefix rows'
        Assert-Equal $sixRowsResult.reason 'event-ledger-diverged' 'six rows were compared to current checkpoint count instead of program row bound'
        Assert-Equal (Get-Hash $fixture.Events) $sixRowsHash 'six-row bound probe changed ledger'

        $tenRows = ((1..10 | ForEach-Object { $eventLines[0] }) -join "`n") + "`n"
        Write-Utf8 $fixture.Events $tenRows
        $tenRowsHash = Get-Hash $fixture.Events
        $tenRowsRead = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
        $tenRowsResult = Read-Result $tenRowsRead
        Assert-Equal $tenRowsRead.ExitCode 1 'Status accepted ten rows for a four-unit program'
        Assert-Equal $tenRowsResult.reason 'event-row-count-exceeded' 'program row-count bound did not reject row 10'
        Assert-Equal (Get-Hash $fixture.Events) $tenRowsHash 'row-count rejection changed ledger'

        $oversizedBytes = [Array]::CreateInstance([byte], 256KB + 1)
        for ($index = 0; $index -lt $oversizedBytes.Length; $index++) { $oversizedBytes[$index] = 0x20 }
        [IO.File]::WriteAllBytes($fixture.Events, [byte[]]$oversizedBytes)
        $oversizedHash = Get-Hash $fixture.Events
        $oversizedRead = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
        $oversizedResult = Read-Result $oversizedRead
        Assert-Equal $oversizedRead.ExitCode 1 'Status accepted ledger larger than 256 KiB'
        Assert-Equal $oversizedResult.reason 'event-ledger-too-large' 'oversized ledger reason changed'
        Assert-Equal (Get-Hash $fixture.Events) $oversizedHash 'oversized ledger rejection changed bytes'
        Assert-Equal (Get-Hash $fixture.State) $boundsStateHash 'bounds probes changed state'
        Assert-Equal (Get-Hash $fixture.EventLock) $boundsLockHash 'bounds probes changed lock'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($LockShapeOnly) {
        $lockShapeStateHash = Get-Hash $fixture.State
        $lockShapeEventHash = Get-Hash $fixture.Events
        Write-Utf8 $fixture.EventLock 'x'
        $nonzeroLockHash = Get-Hash $fixture.EventLock
        $nonzeroRead = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
        $nonzeroResult = Read-Result $nonzeroRead
        Assert-Equal $nonzeroRead.ExitCode 1 'Status accepted a nonzero event lock leaf'
        Assert-Equal $nonzeroResult.reason 'event-lock-invalid' 'nonzero event lock reason changed'
        Assert-Equal $nonzeroResult.eventReason 'event-lock-invalid' 'nonzero event lock eventReason changed'
        Assert-Equal (Get-Hash $fixture.EventLock) $nonzeroLockHash 'nonzero lock rejection rewrote lock'

        Remove-Item -LiteralPath $fixture.EventLock -Force -ErrorAction Stop
        New-Item -ItemType Directory -Path $fixture.EventLock -ErrorAction Stop | Out-Null
        $directoryRead = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
        $directoryResult = Read-Result $directoryRead
        Assert-Equal $directoryRead.ExitCode 1 'Status accepted a directory event lock'
        Assert-Equal $directoryResult.reason 'event-lock-invalid' 'directory event lock reason changed'
        Assert-Equal $directoryResult.eventReason 'event-lock-invalid' 'directory event lock eventReason changed'
        Assert-True (Test-Path -LiteralPath $fixture.EventLock -PathType Container) 'directory lock rejection changed lock shape'
        Assert-Equal (Get-Hash $fixture.State) $lockShapeStateHash 'lock-shape probes changed state'
        Assert-Equal (Get-Hash $fixture.Events) $lockShapeEventHash 'lock-shape probes changed ledger'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($LockReparseOnly) {
        $reparseStateHash = Get-Hash $fixture.State
        $reparseEventHash = Get-Hash $fixture.Events
        $sentinelRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-public-events-sentinel-' + [guid]::NewGuid().ToString('N'))
        $sentinelPath = Join-Path $sentinelRoot 'sentinel.txt'
        Remove-Item -LiteralPath $fixture.EventLock -Force -ErrorAction Stop
        New-Item -ItemType Directory -Path $sentinelRoot -ErrorAction Stop | Out-Null
        Write-Utf8 $sentinelPath 'outside sentinel remains unchanged'
        $sentinelHash = Get-Hash $sentinelPath
        try {
            New-Item -ItemType Junction -Path $fixture.EventLock -Target $sentinelRoot -ErrorAction Stop | Out-Null
            $junction = Get-Item -LiteralPath $fixture.EventLock -Force -ErrorAction Stop
            Assert-True (($junction.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) 'lock junction fixture is not a reparse point'
            $reparseRead = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
            $reparseResult = Read-Result $reparseRead
            Assert-Equal $reparseRead.ExitCode 1 'Status accepted a reparse event lock'
            Assert-Equal (@($reparseResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId,committed,stateSha256,canonicalMarkdownSha256,eventReason' 'reparse lock error envelope shape changed'
            Assert-Equal $reparseResult.reason 'event-lock-invalid' 'reparse event lock reason changed'
            Assert-Equal $reparseResult.eventReason 'event-lock-invalid' 'reparse event lock eventReason changed'
            Assert-True (Test-Path -LiteralPath $fixture.EventLock) 'reparse lock rejection removed junction'
            Assert-Equal (Get-Hash $sentinelPath) $sentinelHash 'reparse lock probe changed outside sentinel'
            Assert-Equal (Get-Hash $fixture.State) $reparseStateHash 'reparse lock probe changed state'
            Assert-Equal (Get-Hash $fixture.Events) $reparseEventHash 'reparse lock probe changed ledger'
        } finally {
            if (Test-Path -LiteralPath $fixture.EventLock) {
                $cleanupJunction = Get-Item -LiteralPath $fixture.EventLock -Force -ErrorAction Stop
                if (($cleanupJunction.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) { throw 'unsafe-junction-cleanup-target' }
                [IO.Directory]::Delete($fixture.EventLock)
            }
            $sentinelFull = [IO.Path]::GetFullPath($sentinelRoot).TrimEnd('\')
            $tempFull = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
            if (-not ([IO.Directory]::GetParent($sentinelFull).FullName.TrimEnd('\')).Equals($tempFull, [StringComparison]::OrdinalIgnoreCase) -or
                -not ([IO.Path]::GetFileName($sentinelFull)).StartsWith('awx-public-events-sentinel-', [StringComparison]::Ordinal)) {
                throw 'unsafe-sentinel-cleanup-target'
            }
            $sentinelReparse = @(Get-ChildItem -LiteralPath $sentinelFull -Force -Recurse -ErrorAction Stop | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 })
            if ($sentinelReparse.Count -ne 0) { throw 'unsafe-sentinel-cleanup-reparse' }
            Remove-Item -LiteralPath $sentinelFull -Recurse -Force -ErrorAction Stop
        }
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($LockAppearanceOnly) {
        $appearanceStateHash = Get-Hash $fixture.State
        $appearanceEventHash = Get-Hash $fixture.Events
        Remove-Item -LiteralPath $fixture.EventLock -Force -ErrorAction Stop
        $appearanceMarker = Join-Path $fixture.Root '.event-test-create-read-lock'
        Write-Utf8 $appearanceMarker 'create lock after first stable-read snapshot'
        try {
            foreach ($readAction in @('Status','Next')) {
                Assert-True (-not (Test-Path -LiteralPath $fixture.EventLock)) "$readAction lock-appearance fixture began with a lock"
                $appearanceRead = Invoke-Controller @('-Action',$readAction,'-Root',$fixture.Root)
                $appearanceResult = Read-Result $appearanceRead
                Assert-Equal $appearanceRead.ExitCode 1 "$readAction accepted a lock that appeared between stable reads"
                Assert-Equal (@($appearanceResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId,committed,stateSha256,canonicalMarkdownSha256,eventReason' "$readAction lock-appearance envelope shape changed"
                Assert-Equal $appearanceResult.reason 'event-publication-pending' "$readAction lock-appearance reason changed"
                Assert-Equal $appearanceResult.eventReason 'event-publication-pending' "$readAction lock-appearance eventReason changed"
                Assert-True ($appearanceResult.committed -is [bool] -and -not [bool]$appearanceResult.committed) "$readAction lock appearance invented a commit"
                Assert-True (Test-Path -LiteralPath $fixture.EventLock -PathType Leaf) "$readAction hook did not create the event lock leaf"
                Assert-Equal ([IO.File]::ReadAllBytes($fixture.EventLock).Length) ([int64]0) "$readAction changed the event lock shape"
                Assert-Equal (Get-Hash $fixture.State) $appearanceStateHash "$readAction lock appearance changed state"
                Assert-Equal (Get-Hash $fixture.Events) $appearanceEventHash "$readAction lock appearance changed ledger"
                Remove-Item -LiteralPath $fixture.EventLock -Force -ErrorAction Stop
            }
        } finally {
            Remove-Item -LiteralPath $appearanceMarker -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $fixture.EventLock -Force -ErrorAction SilentlyContinue
        }
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    if ($ReadOnlyOnly) {
        Assert-True (Test-Path -LiteralPath $fixture.EventLock -PathType Leaf) 'mutation flow omitted persistent event lock'
        $convergedStateHash = Get-Hash $fixture.State
        $convergedEventHash = Get-Hash $fixture.Events
        $convergedLockHash = Get-Hash $fixture.EventLock
        foreach ($readAction in @('Status','Next')) {
            $convergedRead = Invoke-Controller @('-Action',$readAction,'-Root',$fixture.Root)
            $convergedResult = Read-Result $convergedRead
            Assert-Equal $convergedRead.ExitCode 0 "$readAction rejected an exactly converged ledger: $($convergedResult.reason)"
            Assert-Equal (Get-Hash $fixture.State) $convergedStateHash "$readAction changed converged state"
            Assert-Equal (Get-Hash $fixture.Events) $convergedEventHash "$readAction changed converged ledger"
            Assert-Equal (Get-Hash $fixture.EventLock) $convergedLockHash "$readAction changed the persistent event lock"
        }

        $fullLedgerText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
        Write-Utf8 $fixture.Events ($eventLines[0] + "`n")
        $oneAheadHash = Get-Hash $fixture.Events
        foreach ($readAction in @('Status','Next')) {
            $oneAheadRead = Invoke-Controller @('-Action',$readAction,'-Root',$fixture.Root)
            $oneAheadResult = Read-Result $oneAheadRead
            Assert-Equal $oneAheadRead.ExitCode 1 "$readAction accepted an exact one-ahead checkpoint"
            Assert-Equal $oneAheadResult.reason 'event-publication-pending' "$readAction did not classify exact one-ahead relation"
            Assert-Equal (Get-Hash $fixture.State) $convergedStateHash "$readAction changed state while one-ahead"
            Assert-Equal (Get-Hash $fixture.Events) $oneAheadHash "$readAction repaired one-ahead ledger"
            Assert-Equal (Get-Hash $fixture.EventLock) $convergedLockHash "$readAction changed lock while one-ahead"
        }

        $divergedLedgerText = $fullLedgerText.Replace('"result":"running"', '"result":"hold"')
        Assert-True ($divergedLedgerText -cne $fullLedgerText) 'divergence fixture did not change the Begin row'
        Write-Utf8 $fixture.Events $divergedLedgerText
        $divergedHash = Get-Hash $fixture.Events
        $divergedRead = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
        $divergedResult = Read-Result $divergedRead
        Assert-Equal $divergedRead.ExitCode 1 'Status accepted a divergent ledger'
        Assert-Equal $divergedResult.reason 'event-ledger-diverged' 'Status did not classify a divergent ledger'
        Assert-Equal (Get-Hash $fixture.Events) $divergedHash 'Status rewrote a divergent ledger'
        Assert-Equal (Get-Hash $fixture.State) $convergedStateHash 'Status changed state for a divergent ledger'

        Write-Utf8 $fixture.Events $fullLedgerText
        Remove-Item -LiteralPath $fixture.EventLock -Force -ErrorAction Stop
        foreach ($readAction in @('Status','Next')) {
            $absentLockRead = Invoke-Controller @('-Action',$readAction,'-Root',$fixture.Root)
            $absentLockResult = Read-Result $absentLockRead
            Assert-Equal $absentLockRead.ExitCode 0 "$readAction rejected a stable converged read with absent lock: $($absentLockResult.reason)"
            Assert-True (-not (Test-Path -LiteralPath $fixture.EventLock)) "read-only $readAction created an absent event lock"
            Assert-Equal (Get-Hash $fixture.State) $convergedStateHash "absent-lock $readAction changed state"
            Assert-Equal (Get-Hash $fixture.Events) $convergedEventHash "absent-lock $readAction changed ledger"
        }

        [IO.File]::WriteAllBytes($fixture.EventLock, [byte[]]@())
        $heldLock = [IO.FileStream]::new($fixture.EventLock, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        try {
            foreach ($readAction in @('Status','Next')) {
                $heldLockRead = Invoke-Controller @('-Action',$readAction,'-Root',$fixture.Root)
                $heldLockResult = Read-Result $heldLockRead
                Assert-Equal $heldLockRead.ExitCode 1 "$readAction ignored an exclusively held event lock"
                Assert-Equal $heldLockResult.reason 'event-lock-timeout' "$readAction did not bound an exclusively held event lock"
                Assert-Equal (Get-Hash $fixture.State) $convergedStateHash "held-lock $readAction changed state"
                Assert-Equal (Get-Hash $fixture.Events) $convergedEventHash "held-lock $readAction changed ledger"
            }
        } finally {
            $heldLock.Dispose()
        }
        Assert-Equal (Get-Hash $fixture.EventLock) $convergedLockHash 'held-lock reads changed the lock leaf'
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    $noPatchEvidence = Write-RecordEvidence $fixture $runningState 'WU-EVENT-A' $runId 'NoPatchNeeded'
    $terminalFailMarker = Join-Path $fixture.Root '.event-test-fail-terminal'
    Write-Utf8 $terminalFailMarker 'fail once before terminal state CAS'
    $beforeAwaitingLedgerHash = Get-Hash $fixture.Events
    $firstRecord = Invoke-Controller @(
        '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$runId,
        '-Outcome','NoPatchNeeded','-EvidencePath',$noPatchEvidence.Relative
    )
    $firstRecordResult = Read-Result $firstRecord
    Assert-Equal $firstRecord.ExitCode 1 'terminal-CAS test hook did not stop the first Record'
    $awaitingState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
    $awaitingUnit = @($awaitingState.workUnits | Where-Object workUnitId -CEQ 'WU-EVENT-A')[0]
    Assert-Equal (@($firstRecordResult.PSObject.Properties.Name) -join ',') 'action,status,reason,workUnitId,committed,stateSha256,canonicalMarkdownSha256,eventReason' 'committed event error envelope shape changed'
    Assert-Equal $firstRecordResult.reason 'event-publication-pending' 'terminal-CAS failure did not close as event publication pending'
    Assert-Equal $firstRecordResult.workUnitId 'WU-EVENT-A' 'committed event error omitted the actual work unit'
    Assert-True ($firstRecordResult.committed -is [bool] -and [bool]$firstRecordResult.committed) 'committed event error did not set boolean committed=true'
    Assert-Equal $firstRecordResult.stateSha256 (Get-Hash $fixture.State) 'committed event error state hash changed'
    Assert-Equal $firstRecordResult.canonicalMarkdownSha256 $awaitingState.canonicalMarkdownSha256 'committed event error canonical hash changed'
    Assert-Equal $firstRecordResult.eventReason 'event-publication-pending' 'committed event error leaked an unsafe internal reason'
    Assert-True (-not $firstRecord.Text.Contains($runId)) 'committed event error leaked raw run ID'
    Assert-True (-not $firstRecord.Text.Contains($noPatchEvidence.Relative)) 'committed event error leaked evidence path'
    Assert-Equal $awaitingUnit.transition.phase 'record-awaiting-release' 'first Record did not durably commit awaiting-release'
    Assert-Equal ([int64]$awaitingState.eventCheckpoint.sequence) ([int64]2) 'awaiting-release advanced the Begin checkpoint'
    Assert-Equal (Get-Hash $fixture.Events) $beforeAwaitingLedgerHash 'awaiting-release published a premature Record row'
    $committedRecord = $awaitingUnit.transition.record
    $expectedRecordHash = Get-ByteHash $script:utf8.GetBytes(($committedRecord | ConvertTo-Json -Depth 20 -Compress))

    Remove-Item -LiteralPath $terminalFailMarker -Force -ErrorAction Stop
    Remove-Item -LiteralPath $noPatchEvidence.Path -Force -ErrorAction Stop
    $recordRetry = Invoke-Controller @(
        '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$runId,
        '-Outcome','NoPatchNeeded','-EvidencePath',$noPatchEvidence.Relative
    )
    $recordRetryResult = Read-Result $recordRetry
    Assert-Equal $recordRetry.ExitCode 0 "deleted-evidence Record retry failed: $($recordRetryResult.reason)"
    Assert-Equal $recordRetryResult.reason 'work-unit-recorded' 'deleted-evidence retry did not terminalize the committed record'
    $afterNoPatchBytes = [IO.File]::ReadAllBytes($fixture.Events)
    $afterNoPatchText = $script:utf8.GetString($afterNoPatchBytes)
    $afterNoPatchLines = @($afterNoPatchText.Substring(0, $afterNoPatchText.Length - 1).Split([char]0x0A))
    Assert-Equal $afterNoPatchLines.Count 3 'terminal NoPatchNeeded did not publish exactly seq3'
    $recordRow = $afterNoPatchLines[2] | ConvertFrom-Json -ErrorAction Stop
    Assert-Equal ([int64]$recordRow.sequence) ([int64]3) 'NoPatchNeeded Record sequence is not 3'
    Assert-Equal $recordRow.previousEventSha256 $beginRow.eventSha256 'Record row is not chained to Begin'
    Assert-Equal $recordRow.action 'Record' 'seq3 action is not Record'
    Assert-Equal $recordRow.workUnitId 'WU-EVENT-A' 'seq3 work-unit identity changed'
    Assert-Equal $recordRow.result 'no_patch_needed' 'NoPatchNeeded result mapping changed'
    Assert-Equal $recordRow.runIdSha256 $expectedRunHash 'Record run hash differs from Begin'
    Assert-Equal $recordRow.evidenceSha256 $noPatchEvidence.Sha256 'Record evidence hash changed after evidence deletion'
    Assert-Equal $recordRow.recordSha256 $expectedRecordHash 'Record hash is not bound to the durable exact record'
    $terminalState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
    Assert-Equal ($terminalState.eventCheckpoint | ConvertTo-Json -Compress) ($recordRow | ConvertTo-Json -Compress) 'terminal checkpoint does not equal seq3 row'
    $terminalReplayHash = Get-Hash $fixture.Events
    $terminalReplay = Invoke-Controller @(
        '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-EVENT-A','-RunId',$runId,
        '-Outcome','NoPatchNeeded','-EvidencePath',$noPatchEvidence.Relative
    )
    $terminalReplayResult = Read-Result $terminalReplay
    Assert-Equal $terminalReplay.ExitCode 0 "terminal Record replay failed: $($terminalReplayResult.reason)"
    Assert-Equal $terminalReplayResult.reason 'already-recorded' 'terminal Record replay was not idempotent'
    Assert-Equal (Get-Hash $fixture.Events) $terminalReplayHash 'terminal Record replay duplicated an event row'

    if ($NoPatchOnly) {
        Write-Output "PASS: $script:assertions assertions"
        return
    }

    foreach ($mapping in @(
        [pscustomobject]@{ UnitId = 'WU-EVENT-B'; RunId = 'event-run-hold-43'; Outcome = 'Hold'; Result = 'hold'; Sequence = 5 },
        [pscustomobject]@{ UnitId = 'WU-EVENT-C'; RunId = 'event-run-failed-44'; Outcome = 'Failed'; Result = 'failed'; Sequence = 7 },
        [pscustomobject]@{ UnitId = 'WU-EVENT-D'; RunId = 'event-run-green-45'; Outcome = 'Green'; Result = 'green'; Sequence = 9 }
    )) {
        $mappedBegin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId',$mapping.UnitId,'-RunId',$mapping.RunId)
        $mappedBeginResult = Read-Result $mappedBegin
        Assert-Equal $mappedBegin.ExitCode 0 "$($mapping.Outcome) Begin failed: $($mappedBeginResult.reason)"
        $mappedRunningState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
        if ($mapping.Outcome -ceq 'Green') {
            Write-Utf8 $fixture.GreenTarget 'green target after'
        }
        $mappedEvidence = Write-RecordEvidence $fixture $mappedRunningState $mapping.UnitId $mapping.RunId $mapping.Outcome
        $mappedRecord = Invoke-Controller @(
            '-Action','Record','-Root',$fixture.Root,'-WorkUnitId',$mapping.UnitId,'-RunId',$mapping.RunId,
            '-Outcome',$mapping.Outcome,'-EvidencePath',$mappedEvidence.Relative
        )
        $mappedRecordResult = Read-Result $mappedRecord
        Assert-Equal $mappedRecord.ExitCode 0 "$($mapping.Outcome) Record failed: $($mappedRecordResult.reason)"
        $mappedBytes = [IO.File]::ReadAllBytes($fixture.Events)
        $mappedText = $script:utf8.GetString($mappedBytes)
        $mappedLines = @($mappedText.Substring(0, $mappedText.Length - 1).Split([char]0x0A))
        $mappedRow = $mappedLines[$mappedLines.Count - 1] | ConvertFrom-Json -ErrorAction Stop
        Assert-Equal ([int64]$mappedRow.sequence) ([int64]$mapping.Sequence) "$($mapping.Outcome) Record sequence changed"
        Assert-Equal $mappedRow.result $mapping.Result "$($mapping.Outcome) event result mapping changed"
        Assert-True (-not $mappedText.Contains($mapping.RunId)) "$($mapping.Outcome) event leaked the raw run ID"
    }
    Write-Output "PASS: $script:assertions assertions"
}
finally {
    try {
        Assert-Equal (Get-Hash $script:sourceController) $script:sourceControllerHash 'production controller changed during public-events RED'
        Assert-Equal (Get-Hash $script:sourceCore) $script:sourceCoreHash 'frozen core changed during public-events RED'
        Assert-Equal (Get-Hash $script:sourceSession) $script:sourceSessionHash 'source-session guard changed during public-events RED'
        Assert-Equal (Get-Hash $script:sourceLeaseContract) $script:sourceLeaseContractHash 'lease contract changed during public-events RED'
    } finally {
        try { Remove-SafeFixture $script:fixtureRoot }
        finally { Remove-SafeOutsideSentinel $script:outsideSentinel }
    }
}
