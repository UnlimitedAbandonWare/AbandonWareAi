param(
    [string]$Root = "",
    [string]$ReportPath = "",
    [switch]$SelfTest,
    [switch]$AllowPendingPatchDrop
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

$LeaseContractPath = Join-Path $PSScriptRoot '..\__patch_drop__\source_edit_lease_contract.ps1'
if (-not (Test-Path -LiteralPath $LeaseContractPath -PathType Leaf)) {
    throw "source edit lease contract missing: $LeaseContractPath"
}
. $LeaseContractPath

$SecretValuePattern = "(?i)sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|\b(?:authorization|cookie)\s*[:=]|-----BEGIN (?:RSA |EC |OPENSSH |PRIVATE )?PRIVATE KEY-----"
$PatchInstructionPattern = "(?i)(Before snippet|After snippet|Minimal unified diff|Patch Blocks:|Setup Commands:|Source Modifier Agent|read Abandon\.md|BEGIN PATCH|END PATCH|Autonomous Safe Patch Prompt|Return exactly this structure)"
$FileModePattern = "(?m)^(old mode|new mode|deleted file mode|new file mode)\s+"
$ForbiddenPatchPathPatterns = @(
    @{ Pattern = '(^|/)(apikey\.txt|apikey\.ps1)$'; Reason = 'secret-setup' },
    @{ Pattern = '(^|/)\.env[^/]*(/|$)'; Reason = 'secret-env' },
    @{ Pattern = '(^|/)pages/api/'; Reason = 'nextjs-pages-api' },
    @{ Pattern = '(^|/)(\.gradle|build|node_modules|\.next|\.turbo|\.swc)(/|$)'; Reason = 'shared-cache-build-output' },
    @{ Pattern = '\.(p12|jks)$'; Reason = 'keystore' }
)

function Test-JsonBooleanProperty {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Expected
    )
    if ($null -eq $Object) { return $false }
    $property = $Object.PSObject.Properties[$Name]
    return $null -ne $property -and $property.Value -is [bool] -and $property.Value -eq $Expected
}

function Test-JsonIntegerProperty {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][long]$Expected
    )
    if ($null -eq $Object) { return $false }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -is [bool]) { return $false }
    if (-not ($property.Value -is [int] -or $property.Value -is [long])) { return $false }
    return [long]$property.Value -eq $Expected
}

function Test-CanonicalPatchRelativePath {
    param([Parameter(Mandatory = $true)][string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Contains('\')) { return $false }
    if ($Value.StartsWith('/') -or $Value.StartsWith('//') -or $Value -match '^[A-Za-z]:') { return $false }
    if ($Value -match '[\x00-\x20\x7f"<>:|?*]') { return $false }
    $parts = @($Value.Split('/'))
    if (@($parts | Where-Object { $_ -in @('', '.', '..') }).Count -ne 0) { return $false }
    foreach ($part in $parts) {
        if ($part.EndsWith('.') -or $part.EndsWith(' ')) { return $false }
        if ($part -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)') { return $false }
    }
    return $true
}

function Test-CanonicalPatchNewFileTarget {
    param([Parameter(Mandatory = $true)][string]$Target)
    if (-not (Test-CanonicalPatchRelativePath $Target)) { return $false }
    foreach ($entry in $script:ForbiddenPatchPathPatterns) {
        if ($Target -match $entry.Pattern) { return $false }
    }
    return $true
}

function Get-FirstPatchHunkIndex {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string[]]$Lines)
    for ($position = 0; $position -lt $Lines.Count; $position++) {
        if ($Lines[$position].StartsWith('@@ ')) { return $position }
    }
    return -1
}

function Test-ExactPatchHunkSequence {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string[]]$Lines,
        [switch]$RequireNewFile
    )
    $firstHunk = Get-FirstPatchHunkIndex -Lines $Lines
    if ($firstHunk -lt 0) { return $false }
    $oldIndexes = @()
    $newIndexes = @()
    for ($position = 0; $position -lt $firstHunk; $position++) {
        if ($Lines[$position].StartsWith('--- ')) { $oldIndexes += $position }
        if ($Lines[$position].StartsWith('+++ ')) { $newIndexes += $position }
    }
    if ($oldIndexes.Count -ne 1 -or $newIndexes.Count -ne 1 -or $newIndexes[0] -ne ($oldIndexes[0] + 1)) { return $false }
    if ($firstHunk -ne ($newIndexes[0] + 1)) { return $false }
    $index = $firstHunk
    $hunkCount = 0
    while ($index -lt $Lines.Count) {
        $header = [regex]::Match($Lines[$index], '^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(?: .*)?$')
        if (-not $header.Success) { return $false }
        $oldStart = [int]$header.Groups[1].Value
        $oldExpected = if ($header.Groups[2].Success) { [int]$header.Groups[2].Value } else { 1 }
        $newStart = [int]$header.Groups[3].Value
        $newExpected = if ($header.Groups[4].Success) { [int]$header.Groups[4].Value } else { 1 }
        if ($RequireNewFile -and ($hunkCount -ne 0 -or $oldStart -ne 0 -or $oldExpected -ne 0 -or $newStart -ne 1 -or $newExpected -le 0)) { return $false }
        $hunkCount++
        $index++
        $oldActual = 0
        $newActual = 0
        $oldEofMarked = $false
        $newEofMarked = $false
        while ($index -lt $Lines.Count -and -not $Lines[$index].StartsWith('@@ ')) {
            $line = $Lines[$index]
            if ($line -ceq '\ No newline at end of file') {
                if ($index -eq 0 -or -not $Lines[$index - 1].StartsWith(' ') -and -not $Lines[$index - 1].StartsWith('+') -and -not $Lines[$index - 1].StartsWith('-')) { return $false }
                for ($future = $index + 1; $future -lt $Lines.Count; $future++) {
                    if ($Lines[$future].StartsWith('@@ ')) { return $false }
                }
                $previousKind = $Lines[$index - 1][0]
                if ($previousKind -eq '-') {
                    if ($oldEofMarked -or $newEofMarked) { return $false }
                    $oldEofMarked = $true
                } elseif ($previousKind -eq '+') {
                    if ($newEofMarked) { return $false }
                    $newEofMarked = $true
                } else {
                    if ($oldEofMarked -or $newEofMarked) { return $false }
                    $oldEofMarked = $true
                    $newEofMarked = $true
                }
                $index++
                continue
            }
            if ([string]::IsNullOrEmpty($line) -or $line[0] -notin @(' ', '+', '-')) { return $false }
            if ($newEofMarked -or ($oldEofMarked -and $line[0] -ne '+')) { return $false }
            if ($RequireNewFile -and $line[0] -ne '+') { return $false }
            if ($line[0] -in @(' ', '-')) { $oldActual++ }
            if ($line[0] -in @(' ', '+')) { $newActual++ }
            if ($oldActual -gt $oldExpected -or $newActual -gt $newExpected) { return $false }
            $index++
        }
        if ($oldActual -ne $oldExpected -or $newActual -ne $newExpected) { return $false }
    }
    if ($RequireNewFile) { return $hunkCount -eq 1 }
    return $hunkCount -gt 0
}

function Test-CanonicalPatchNewFileHunk {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string[]]$Lines)
    return (Test-ExactPatchHunkSequence -Lines $Lines -RequireNewFile)
}

function Get-CanonicalPatchNewFileTarget {
    param(
        [Parameter(Mandatory = $true)][string]$Block,
        [Parameter(Mandatory = $true)][string[]]$ModeLines
    )
    if ($ModeLines.Count -ne 1 -or $ModeLines[0] -cne 'new file mode 100644') { return '' }
    $normalizedBlock = $Block.Replace("`r`n", "`n")
    if ($normalizedBlock.EndsWith("`n", [StringComparison]::Ordinal)) { $normalizedBlock = $normalizedBlock.Substring(0, $normalizedBlock.Length - 1) }
    $lines = @($normalizedBlock -split "`n")
    if ($lines.Count -eq 0) { return '' }
    $header = [regex]::Match($lines[0], '^diff --git a/([^\s"\\]+) b/([^\s"\\]+)$')
    if (-not $header.Success -or $header.Groups[1].Value -cne $header.Groups[2].Value) { return '' }
    $target = $header.Groups[1].Value
    if (-not (Test-CanonicalPatchNewFileTarget -Target $target)) { return '' }
    $firstHunk = Get-FirstPatchHunkIndex -Lines $lines
    $envelope = if ($firstHunk -gt 0) { @($lines[0..($firstHunk - 1)]) } else { @($lines) }
    $oldHeaders = @($envelope | Where-Object { $_.StartsWith('--- ') })
    $newHeaders = @($envelope | Where-Object { $_.StartsWith('+++ ') })
    if ($oldHeaders.Count -ne 1 -or $oldHeaders[0] -cne '--- /dev/null') { return '' }
    if ($newHeaders.Count -ne 1 -or $newHeaders[0] -cne "+++ b/$target") { return '' }
    if (-not (Test-CanonicalPatchNewFileHunk $lines)) { return '' }
    foreach ($line in $lines) {
        if (
            $line.StartsWith('rename from ') -or
            $line.StartsWith('rename to ') -or
            $line.StartsWith('copy from ') -or
            $line.StartsWith('copy to ') -or
            $line -eq 'GIT binary patch' -or
            ($line.StartsWith('Binary files ') -and $line.EndsWith(' differ'))
        ) { return '' }
    }
    return $target
}

function Get-PatchModeSummary {
    param([Parameter(Mandatory = $true)][string]$PatchText)
    $text = $PatchText.TrimStart([char]0xfeff).Replace("`r`n", "`n")
    $modePattern = '(?m)^(old mode|new mode|deleted file mode|new file mode)(?:\s+.*)?$'
    $total = ([regex]::Matches($text, $modePattern)).Count
    $covered = 0
    $allowed = 0
    $violations = 0
    $targets = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($blockMatch in [regex]::Matches($text, '(?ms)^diff --git .*?(?=^diff --git |\z)')) {
        $modeLines = @([regex]::Matches($blockMatch.Value, $modePattern) | ForEach-Object { $_.Value })
        $covered += $modeLines.Count
        $blockLines = @($blockMatch.Value.Replace("`r`n", "`n") -split "`n")
        $firstHunk = Get-FirstPatchHunkIndex -Lines $blockLines
        $envelope = if ($firstHunk -gt 0) { @($blockLines[0..($firstHunk - 1)]) } else { @($blockLines) }
        $hasOldNull = @($envelope | Where-Object { $_ -ceq '--- /dev/null' }).Count -gt 0
        $hasNewNull = @($envelope | Where-Object { $_ -ceq '+++ /dev/null' }).Count -gt 0
        if ($hasNewNull -or ($hasOldNull -and $modeLines.Count -eq 0)) {
            $violations += [Math]::Max(1, $modeLines.Count)
            continue
        }
        if ($modeLines.Count -eq 0) { continue }
        $target = Get-CanonicalPatchNewFileTarget -Block $blockMatch.Value -ModeLines $modeLines
        if (-not [string]::IsNullOrWhiteSpace($target) -and $targets.Add($target)) {
            $allowed++
        } else {
            $violations += $modeLines.Count
        }
    }
    $violations += ($total - $covered)
    return [pscustomobject]@{
        FilemodeLineCount = $total
        AllowedNewFileCount = $allowed
        FilemodeViolationCount = $violations
    }
}

function Get-PatchTargetPaths {
    param([Parameter(Mandatory = $true)][string]$PatchText)
    $targets = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $hunkStarted = $false
    foreach ($line in ($PatchText.TrimStart([char]0xfeff) -split "`r?`n")) {
        $candidates = New-Object System.Collections.Generic.List[string]
        if ($line.StartsWith('diff --git ')) {
            $hunkStarted = $false
            $match = [regex]::Match($line, '^diff --git a/([^\s"\\]+) b/([^\s"\\]+)$')
            if ($match.Success) {
                $candidates.Add($match.Groups[1].Value)
                $candidates.Add($match.Groups[2].Value)
            } else {
                $candidates.Add($line.Substring('diff --git '.Length))
            }
        } elseif ($line.StartsWith('@@ ')) {
            $hunkStarted = $true
        } elseif (-not $hunkStarted -and ($line.StartsWith('--- ') -or $line.StartsWith('+++ '))) {
            $raw = $line.Substring(4).Split("`t", 2)[0]
            if ($raw -eq '/dev/null') { continue }
            if ($raw.StartsWith('a/') -or $raw.StartsWith('b/')) { $raw = $raw.Substring(2) }
            $candidates.Add($raw)
        } elseif ($line.StartsWith('rename from ') -or $line.StartsWith('rename to ') -or $line.StartsWith('copy from ') -or $line.StartsWith('copy to ')) {
            $candidates.Add($line.Substring($line.IndexOf(' ') + 1).Substring($line.Substring($line.IndexOf(' ') + 1).IndexOf(' ') + 1))
        }
        foreach ($candidate in $candidates) {
            if (-not [string]::IsNullOrWhiteSpace($candidate)) { [void]$targets.Add($candidate) }
        }
    }
    return @($targets)
}

function Get-PatchPathViolationCount {
    param([Parameter(Mandatory = $true)][string]$PatchText)
    $violations = 0
    foreach ($target in (Get-PatchTargetPaths -PatchText $PatchText)) {
        if (-not (Test-CanonicalPatchRelativePath $target)) {
            $violations++
            continue
        }
        foreach ($entry in $script:ForbiddenPatchPathPatterns) {
            if ($target -match $entry.Pattern) {
                $violations++
                break
            }
        }
    }
    return $violations
}

function Get-PatchStructureViolationCount {
    param(
        [Parameter(Mandatory = $true)][string]$PatchPath,
        [Parameter(Mandatory = $true)][string]$PatchText
    )
    $text = $PatchText.TrimStart([char]0xfeff).Replace("`r`n", "`n")
    if ($text.EndsWith("`n", [StringComparison]::Ordinal)) { $text = $text.Substring(0, $text.Length - 1) }
    $blocks = [regex]::Matches($text, '(?ms)^diff --git .*?(?=^diff --git |\z)')
    $violations = 0
    if ($blocks.Count -eq 0) { $violations++ }
    if ($blocks.Count -gt 0 -and $blocks[0].Index -gt 0 -and -not [string]::IsNullOrWhiteSpace($text.Substring(0, $blocks[0].Index))) { $violations++ }
    $seenTargets = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($blockMatch in $blocks) {
        $block = $blockMatch.Value
        if ($block.EndsWith("`n", [StringComparison]::Ordinal)) { $block = $block.Substring(0, $block.Length - 1) }
        $lines = @($block -split "`n")
        $header = [regex]::Match($lines[0], '^diff --git a/([^\s"\\]+) b/([^\s"\\]+)$')
        if (-not $header.Success -or $header.Groups[1].Value -cne $header.Groups[2].Value) { $violations++; continue }
        $target = $header.Groups[1].Value
        if (-not (Test-CanonicalPatchRelativePath $target) -or -not $seenTargets.Add($target)) { $violations++; continue }
        $firstHunk = Get-FirstPatchHunkIndex -Lines $lines
        $envelope = if ($firstHunk -gt 0) { @($lines[0..($firstHunk - 1)]) } else { @($lines) }
        $oldHeaders = @($envelope | Where-Object { $_.StartsWith('--- ') })
        $newHeaders = @($envelope | Where-Object { $_.StartsWith('+++ ') })
        $modeLines = @($lines | Where-Object { $_ -match '^(old mode|new mode|deleted file mode|new file mode)(?:\s+.*)?$' })
        if ($oldHeaders.Count -eq 1 -and $oldHeaders[0] -ceq '--- /dev/null') {
            if ([string]::IsNullOrWhiteSpace((Get-CanonicalPatchNewFileTarget -Block $block -ModeLines $modeLines))) { $violations++ }
        } elseif (
            $oldHeaders.Count -ne 1 -or
            $newHeaders.Count -ne 1 -or
            $oldHeaders[0] -cne "--- a/$target" -or
            $newHeaders[0] -cne "+++ b/$target" -or
            -not (Test-ExactPatchHunkSequence -Lines $lines)
        ) {
            $violations++
        }
    }
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & git apply --numstat --whitespace=nowarn -- $PatchPath 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { $violations++ }
    } catch {
        $violations++
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    return $violations
}

function Resolve-ProofRoot {
    param([string]$CandidateRoot)
    if ([string]::IsNullOrWhiteSpace($CandidateRoot)) {
        $CandidateRoot = Join-Path $PSScriptRoot ".."
    }
    return [IO.Path]::GetFullPath($CandidateRoot)
}

function Join-RootPath {
    param(
        [Parameter(Mandatory = $true)][string]$Base,
        [Parameter(Mandatory = $true)][string]$Child
    )
    return [IO.Path]::GetFullPath((Join-Path $Base $Child))
}

function Convert-ToProofRelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$Base,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $baseFull = [IO.Path]::GetFullPath($Base).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $pathFull = [IO.Path]::GetFullPath($Path)
    if ($pathFull.StartsWith($baseFull, [StringComparison]::OrdinalIgnoreCase)) {
        return $pathFull.Substring($baseFull.Length).Replace('\', '/')
    }
    return $pathFull.Replace('\', '/')
}

function Read-TextOrEmpty {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return ""
    }
    try {
        return [IO.File]::ReadAllText($Path, [Text.UTF8Encoding]::new($false))
    } catch {
        return ""
    }
}

function Test-NotebookDesktopProofRecorded {
    param(
        [Parameter(Mandatory = $true)][string]$PatchDrop,
        [Parameter(Mandatory = $true)][string]$Topic
    )

    foreach ($name in @(
            "$Topic.desktop-reconcile-proof.md",
            "$Topic.desktop-gates.md",
            "applied\$Topic.desktop-reconcile-proof.md",
            "applied\$Topic.desktop-gates.md"
        )) {
        if (Test-Path -LiteralPath (Join-RootPath $PatchDrop $name) -PathType Leaf) {
            return $true
        }
    }
    return $false
}

function New-Finding {
    param(
        [Parameter(Mandatory = $true)][string]$Class,
        [Parameter(Mandatory = $true)][string]$Severity,
        [string]$Path = "",
        [string]$Detail = ""
    )
    return [pscustomobject]@{
        Class = $Class
        Severity = $Severity
        Path = $Path
        Detail = $Detail
    }
}

function Add-Finding {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.Generic.List[object]]$Findings,
        [Parameter(Mandatory = $true)][string]$Class,
        [Parameter(Mandatory = $true)][string]$Severity,
        [string]$Path = "",
        [string]$Detail = ""
    )
    $Findings.Add((New-Finding -Class $Class -Severity $Severity -Path $Path -Detail $Detail)) | Out-Null
}

function Get-ProofFiles {
    param(
        [Parameter(Mandatory = $true)][string]$RootPath,
        [Parameter(Mandatory = $true)][string[]]$RelativeRoots,
        [string[]]$Extensions = @()
    )
    $files = @()
    foreach ($relativeRoot in $RelativeRoots) {
        $absoluteRoot = Join-RootPath $RootPath $relativeRoot
        if (-not (Test-Path -LiteralPath $absoluteRoot -PathType Container)) {
            continue
        }
        $items = Get-ChildItem -LiteralPath $absoluteRoot -Recurse -File -ErrorAction SilentlyContinue
        foreach ($item in $items) {
            if ($Extensions.Count -gt 0 -and -not ($Extensions -contains $item.Extension.ToLowerInvariant())) {
                continue
            }
            $files += $item
        }
    }
    return $files
}

function Get-LargeSourceLineBaselines {
    param([AllowEmptyString()][string]$BuildText)
    $baselines = @{}
    if ([string]::IsNullOrWhiteSpace($BuildText)) {
        return $baselines
    }
    $mapMatch = [regex]::Match($BuildText, 'knownLargeSourceLineBaselines\s*=\s*mapOf\s*\((.*?)\)', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $mapMatch.Success) {
        return $baselines
    }
    foreach ($match in [regex]::Matches($mapMatch.Groups[1].Value, '"([^"]+\.java)"\s+to\s+(\d+)')) {
        $baselines[$match.Groups[1].Value.Replace('\', '/')] = [int]$match.Groups[2].Value
    }
    return $baselines
}

function Get-ProofLineCount {
    param([AllowEmptyString()][string]$Text)
    if ([string]::IsNullOrEmpty($Text)) {
        return 0
    }
    return ([regex]::Matches($Text, "`n").Count + 1)
}

function Get-SourceFingerprintSnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$RootPath,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Files
    )
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($file in $Files) {
        $fullName = [string]$file.FullName
        $relativePath = Convert-ToProofRelativePath $RootPath $fullName
        if (-not (Test-Path -LiteralPath $fullName -PathType Leaf)) {
            $rows.Add([pscustomobject]@{
                Path = $relativePath
                Exists = $false
                Length = -1L
                LastWriteTimeUtcTicks = -1L
            }) | Out-Null
            continue
        }
        try {
            $item = Get-Item -LiteralPath $fullName
            $rows.Add([pscustomobject]@{
                Path = $relativePath
                Exists = $true
                Length = [long]$item.Length
                LastWriteTimeUtcTicks = [long]$item.LastWriteTimeUtc.Ticks
            }) | Out-Null
        } catch {
            $rows.Add([pscustomobject]@{
                Path = $relativePath
                Exists = $false
                Length = -1L
                LastWriteTimeUtcTicks = -1L
            }) | Out-Null
        }
    }
    return @($rows.ToArray() | Sort-Object Path)
}

function Compare-SourceFingerprintSnapshot {
    param(
        [Parameter(Mandatory = $true)]$Before,
        [Parameter(Mandatory = $true)]$After
    )
    $changes = New-Object System.Collections.Generic.List[object]
    $beforeRows = @($Before)
    $afterRows = @($After)
    $beforeByPath = @{}
    $afterByPath = @{}
    foreach ($row in $beforeRows) {
        $beforeByPath[[string]$row.Path] = $row
    }
    foreach ($row in $afterRows) {
        $afterByPath[[string]$row.Path] = $row
    }
    foreach ($path in @($beforeByPath.Keys | Sort-Object)) {
        if (-not $afterByPath.ContainsKey($path)) {
            $changes.Add([pscustomobject]@{ Path = $path; Kind = "deleted" }) | Out-Null
            continue
        }
        $beforeRow = $beforeByPath[$path]
        $afterRow = $afterByPath[$path]
        if ($beforeRow.Exists -ne $afterRow.Exists -or $beforeRow.Length -ne $afterRow.Length -or $beforeRow.LastWriteTimeUtcTicks -ne $afterRow.LastWriteTimeUtcTicks) {
            $changes.Add([pscustomobject]@{ Path = $path; Kind = "modified" }) | Out-Null
        }
    }
    foreach ($path in @($afterByPath.Keys | Sort-Object)) {
        if (-not $beforeByPath.ContainsKey($path)) {
            $changes.Add([pscustomobject]@{ Path = $path; Kind = "added" }) | Out-Null
        }
    }
    return @($changes.ToArray())
}

function Test-ProcessResourceExclude {
    param(
        [Parameter(Mandatory = $true)][string]$BuildText,
        [Parameter(Mandatory = $true)][string]$FileName
    )
    if ([string]::IsNullOrWhiteSpace($BuildText)) {
        return $false
    }
    $escaped = [regex]::Escape($FileName)
    return [regex]::IsMatch($BuildText, '"' + $escaped + '"')
}

function New-HashSidecarCheck {
    param(
        [Parameter(Mandatory = $true)][string]$PatchDrop,
        [Parameter(Mandatory = $true)][string]$ShaPath,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.Generic.List[object]]$Findings,
        [Parameter(Mandatory = $true)][string]$RootPath
    )
    $lines = Get-Content -LiteralPath $ShaPath -ErrorAction SilentlyContinue
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $match = [regex]::Match($line, "^\s*([a-fA-F0-9]{64})\s+(.+?)\s*$")
        if (-not $match.Success) {
            Add-Finding $Findings "sha-sidecar-parse" "BLOCK" (Convert-ToProofRelativePath $RootPath $ShaPath) "unparseable sha256 sidecar line"
            continue
        }
        $expected = $match.Groups[1].Value.ToLowerInvariant()
        $relativeFile = $match.Groups[2].Value.Trim().Trim('"')
        $filePath = Join-RootPath $PatchDrop $relativeFile
        if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
            Add-Finding $Findings "missing-bundle-meta" "BLOCK" (Convert-ToProofRelativePath $RootPath $filePath) "sha256 sidecar references missing file"
            continue
        }
        $actual = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $expected) {
            Add-Finding $Findings "sha-mismatch" "BLOCK" (Convert-ToProofRelativePath $RootPath $filePath) "sha256 sidecar mismatch"
        }
    }
}

function Get-JavaMetrics {
    param(
        [Parameter(Mandatory = $true)][string]$RootPath,
        [Parameter(Mandatory = $true)][IO.FileInfo[]]$JavaFiles
    )
    $methodRegex = [regex]'(?m)^\s*(?:(?:public|protected|private|static|final|synchronized|abstract|native|strictfp|default)\s+)+(?:<[^{};]+>\s+)?[A-Za-z_][A-Za-z0-9_<>, ?\[\].&]*\s+[A-Za-z_][A-Za-z0-9_]*\s*\([^;{}]*\)\s*(?:throws\s+[^;{}]+)?\{'
    $broadCatchRegex = [regex]'catch\s*\(([^)]*)\)'
    $broadCatchTypes = @("Exception", "Throwable", "RuntimeException", "Error")
    $rows = New-Object System.Collections.Generic.List[object]

    foreach ($file in $JavaFiles) {
        $text = Read-TextOrEmpty $file.FullName
        $lineCount = if ($text.Length -eq 0) { 0 } else { ([regex]::Matches($text, "`n").Count + 1) }
        $methodCount = $methodRegex.Matches($text).Count
        $broadCatchCount = 0
        foreach ($match in $broadCatchRegex.Matches($text)) {
            $catchSpec = $match.Groups[1].Value
            $types = $catchSpec.Substring(0, [Math]::Max(0, $catchSpec.LastIndexOf(" "))).Trim()
            if ([string]::IsNullOrWhiteSpace($types)) {
                $types = $catchSpec.Trim()
            }
            foreach ($type in $types.Split('|')) {
                $simple = $type.Trim().Split('.')[-1]
                if ($broadCatchTypes -contains $simple) {
                    $broadCatchCount++
                    break
                }
            }
        }
        $score = $lineCount + ($methodCount * 25) + ($broadCatchCount * 10)
        if ($lineCount -ge 1500 -or $methodCount -ge 40 -or $score -ge 2500) {
            $rows.Add([pscustomobject]@{
                Path = Convert-ToProofRelativePath $RootPath $file.FullName
                Lines = $lineCount
                Methods = $methodCount
                BroadCatches = $broadCatchCount
                Score = $score
            }) | Out-Null
        }
    }

    return @($rows | Sort-Object -Property @{ Expression = "Score"; Descending = $true }, Path)
}

function Invoke-SourceGovernanceProofChainV4 {
    param(
        [Parameter(Mandatory = $true)][string]$RootPath,
        [string]$OutputReportPath = "",
        [switch]$AllowPendingBundles
    )

    $findings = New-Object System.Collections.Generic.List[object]
    $observations = [ordered]@{}
    $rootPath = Resolve-ProofRoot $RootPath
    $observations.Root = $rootPath
    $observations.GeneratedAtUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")

    $rootBuild = Join-RootPath $rootPath "build.gradle.kts"
    $appBuild = Join-RootPath $rootPath "app/build.gradle.kts"
    $mainReadme = Join-RootPath $rootPath "main/README.md"
    $rootBuildText = Read-TextOrEmpty $rootBuild
    $appBuildText = Read-TextOrEmpty $appBuild
    $readmeText = Read-TextOrEmpty $mainReadme

    if (-not (Test-Path -LiteralPath $rootBuild -PathType Leaf)) {
        Add-Finding $findings "build-criteria-mismatch" "BLOCK" "build.gradle.kts" "missing root build file"
    }
    if (-not (Test-Path -LiteralPath (Join-RootPath $rootPath "main/java") -PathType Container)) {
        Add-Finding $findings "wrong-sourceset" "BLOCK" "main/java" "missing Desktop root Java sourceSet"
    }
    if (-not (Test-Path -LiteralPath (Join-RootPath $rootPath "main/resources") -PathType Container)) {
        Add-Finding $findings "wrong-sourceset" "BLOCK" "main/resources" "missing Desktop root resources sourceSet"
    }
    if (-not [regex]::IsMatch($rootBuildText, 'srcDirs\("main/java"\)')) {
        Add-Finding $findings "build-criteria-mismatch" "BLOCK" "build.gradle.kts" "root main Java sourceSet is not main/java"
    }
    if (-not [regex]::IsMatch($rootBuildText, 'srcDirs\("main/resources"\)')) {
        Add-Finding $findings "build-criteria-mismatch" "BLOCK" "build.gradle.kts" "root main resources sourceSet is not main/resources"
    }
    if ((Test-Path -LiteralPath $appBuild -PathType Leaf) -and -not [regex]::IsMatch($appBuildText, 'java\.setSrcDirs\(listOf\("src/main/java_clean"\)\)')) {
        Add-Finding $findings "build-criteria-mismatch" "BLOCK" "app/build.gradle.kts" "app Java sourceSet is not java_clean"
    }

    $buildCriteriaText = $rootBuildText + "`n" + $appBuildText
    $gradleStringVars = @{}
    foreach ($varMatch in [regex]::Matches($buildCriteriaText, '(?m)^\s*val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]+)"')) {
        $gradleStringVars[$varMatch.Groups[1].Value] = $varMatch.Groups[2].Value
    }
    $langchainMatches = [regex]::Matches($buildCriteriaText, 'dev\.langchain4j:[^:"\s]+:([^)"\s]+)')
    foreach ($match in $langchainMatches) {
        $versionExpression = $match.Groups[1].Value
        $version = $versionExpression
        if ($versionExpression -match '^\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?$') {
            $varName = $Matches[1]
            if ($gradleStringVars.ContainsKey($varName)) {
                $version = [string]$gradleStringVars[$varName]
            }
        }
        if ($version -ne "1.0.1") {
            Add-Finding $findings "langchain4j-version-purity" "BLOCK" "build.gradle.kts" "declared dev.langchain4j version is not 1.0.1"
        }
    }
    $observations.LangchainDeclaredCount = $langchainMatches.Count

    $springBuildVersion = ""
    $springBuildMatch = [regex]::Match($rootBuildText, 'id\("org\.springframework\.boot"\)\s+version\s+"([^"]+)"')
    if ($springBuildMatch.Success) {
        $springBuildVersion = $springBuildMatch.Groups[1].Value
    }
    $observations.SpringBootBuildVersion = if ($springBuildVersion) { $springBuildVersion } else { "<missing>" }
    $springDocMismatches = 0
    foreach ($match in [regex]::Matches($readmeText, 'Spring Boot\s+([0-9]+(?:\.[0-9]+){1,2})')) {
        if ([string]::IsNullOrWhiteSpace($springBuildVersion) -or $match.Groups[1].Value -ne $springBuildVersion) {
            $springDocMismatches++
            Add-Finding $findings "build-criteria-mismatch" "BLOCK" "main/README.md" "Spring Boot documentation claim does not match root build"
        }
    }
    $observations.SpringBootDocumentationMismatchCount = $springDocMismatches

    foreach ($requiredExclude in @("application-secrets.yml", "keystore.p12", "*.p12", "*.jks", "**/*.p12", "**/*.jks")) {
        if (-not (Test-ProcessResourceExclude -BuildText $rootBuildText -FileName $requiredExclude)) {
            Add-Finding $findings "build-criteria-mismatch" "BLOCK" "build.gradle.kts" "processResources does not exclude $requiredExclude"
        }
    }

    $patchDrop = Join-RootPath $rootPath "__patch_drop__"
    $activePatchCount = 0
    $orphanMetaCount = 0
    $patchSecretHits = 0
    $patchFilemodeHits = 0
    $patchAllowedNewFileCount = 0
    $patchFilemodeViolationCount = 0
    $patchBinaryMarkerHits = 0
    $desktopApplyHelperCount = 0
    $sourceLeaseActiveCount = 0
    $sourceLeaseCorruptCount = 0
    $sourceLeaseExpiredCount = 0
    $notebookPendingCount = 0
    $notebookReconciliationCount = 0
    $notebookReconciliationPendingCount = 0
    $notebookReconciliationRecordedCount = 0
    if (Test-Path -LiteralPath $patchDrop -PathType Container) {
        $activePatches = @(Get-ChildItem -LiteralPath $patchDrop -File -Filter "*.patch" -ErrorAction SilentlyContinue)
        $activePatchCount = $activePatches.Count
        if ($activePatches.Count -gt 0 -and -not $AllowPendingBundles) {
            Add-Finding $findings "patch-drop-pending" "BLOCK" "__patch_drop__" "active top-level patch bundle exists; Desktop final proof requires an applied or empty queue"
        }
        foreach ($patch in $activePatches) {
            $slug = [IO.Path]::GetFileNameWithoutExtension($patch.Name)
            $requiredSidecars = @(
                "$slug.report.md",
                "$slug.verify.log",
                "$slug.sha256.txt",
                "$slug.manifest.json"
            )
            foreach ($sidecar in $requiredSidecars) {
                $sidecarPath = Join-RootPath $patchDrop $sidecar
                if (-not (Test-Path -LiteralPath $sidecarPath -PathType Leaf)) {
                    Add-Finding $findings "missing-bundle-meta" "BLOCK" (Convert-ToProofRelativePath $rootPath $sidecarPath) "active patch is missing required v3 sidecar"
                }
            }
            $patchText = Read-TextOrEmpty $patch.FullName
            $secretCorpus = $patchText
            foreach ($sidecar in $requiredSidecars) {
                $sidecarPath = Join-RootPath $patchDrop $sidecar
                if (Test-Path -LiteralPath $sidecarPath -PathType Leaf) {
                    $secretCorpus += Read-TextOrEmpty $sidecarPath
                }
            }
            $secretHits = [regex]::Matches($secretCorpus, $SecretValuePattern).Count
            $modeSummary = Get-PatchModeSummary -PatchText $patchText
            $filemodeHits = [int]$modeSummary.FilemodeLineCount
            $filemodeViolations = [int]$modeSummary.FilemodeViolationCount
            $allowedNewFiles = [int]$modeSummary.AllowedNewFileCount
            $binaryMarkerHits = [regex]::Matches($patchText, '(?m)^(GIT binary patch|Binary files .+ differ)$').Count
            $pathViolationCount = Get-PatchPathViolationCount -PatchText $patchText
            $structureViolationCount = Get-PatchStructureViolationCount -PatchPath $patch.FullName -PatchText $patchText
            $patchSecretHits += $secretHits
            $patchFilemodeHits += $filemodeHits
            $patchAllowedNewFileCount += $allowedNewFiles
            $patchFilemodeViolationCount += $filemodeViolations
            $patchBinaryMarkerHits += $binaryMarkerHits
            if ($secretHits -gt 0) {
                Add-Finding $findings "secret-leak-risk" "BLOCK" (Convert-ToProofRelativePath $rootPath $patch.FullName) "bundle contains high-confidence secret pattern count=$secretHits"
            }
            if ($filemodeViolations -gt 0) {
                Add-Finding $findings "filemode-blocked" "BLOCK" (Convert-ToProofRelativePath $rootPath $patch.FullName) "patch contains unsafe filemode metadata count=$filemodeViolations"
            }
            if ($binaryMarkerHits -gt 0) {
                Add-Finding $findings "binary-patch-blocked" "BLOCK" (Convert-ToProofRelativePath $rootPath $patch.FullName) "patch contains binary marker count=$binaryMarkerHits"
            }
            if ($pathViolationCount -gt 0) {
                Add-Finding $findings "unsafe-patch-path" "BLOCK" (Convert-ToProofRelativePath $rootPath $patch.FullName) "patch contains unsafe or forbidden target count=$pathViolationCount"
            }
            if ($structureViolationCount -gt 0) {
                Add-Finding $findings "patch-structure-invalid" "BLOCK" (Convert-ToProofRelativePath $rootPath $patch.FullName) "patch is not a canonical parseable unified diff count=$structureViolationCount"
            }
            $manifestPath = Join-RootPath $patchDrop "$slug.manifest.json"
            if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
                try {
                    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
                    $activePatch = ""
                    if ($manifest.PSObject.Properties.Name -contains "activePatch") {
                        $activePatch = [string]$manifest.activePatch
                    }
                    $manifestSchemaVersion = ''
                    if ($manifest.PSObject.Properties.Name -contains 'schemaVersion') {
                        $manifestSchemaVersion = [string]$manifest.schemaVersion
                    }
                    if ($manifestSchemaVersion -cne 'patchdrop-producer-v3') {
                        Add-Finding $findings "patch-manifest-schema" "BLOCK" (Convert-ToProofRelativePath $rootPath $manifestPath) "manifest schemaVersion must be patchdrop-producer-v3"
                    }
                    if ($activePatch -ne $patch.Name) {
                        Add-Finding $findings "patch-drop-pending" "BLOCK" (Convert-ToProofRelativePath $rootPath $manifestPath) "manifest activePatch does not match patch file"
                    }
                    $identity = [regex]::Match($slug, '^(?<topic>[a-z0-9]|[a-z0-9][a-z0-9._-]{0,94}[a-z0-9])-v3$')
                    $manifestContractValid = $identity.Success
                    $expectedTopic = if ($identity.Success) { $identity.Groups['topic'].Value } else { '' }
                    $manifestNode = [string]$manifest.node
                    $manifestSourceRootInputHash = [string]$manifest.sourceRootInputHash
                    $manifestSourceRootHash = [string]$manifest.sourceRootHash
                    $isolation = $manifest.sourceIsolation
                    $verification = $manifest.verification
                    $manifestContractValid = $manifestContractValid -and
                        [string]$manifest.topic -ceq $expectedTopic -and
                        [string]$manifest.slug -ceq $expectedTopic -and
                        $manifestNode -cin @('macmini', 'notebook', 'desktop') -and
                        $activePatch -ceq $patch.Name -and
                        [string]$manifest.desktopFinalProof -ceq 'evidence_needed' -and
                        $manifestSourceRootInputHash -cmatch '^[a-f0-9]{64}$' -and
                        $manifestSourceRootHash -cmatch '^[a-f0-9]{64}$' -and
                        $null -ne $isolation -and
                        [string]$isolation.guard -ceq 'PASS' -and
                        [string]$isolation.sourceRootKind -ceq 'local-worktree' -and
                        (Test-JsonBooleanProperty -Object $isolation -Name 'sharedSourceRoot' -Expected $false) -and
                        (Test-JsonBooleanProperty -Object $isolation -Name 'desktopCanonicalSourceRoot' -Expected $false) -and
                        (Test-JsonBooleanProperty -Object $isolation -Name 'directCanonicalSourceEdit' -Expected $false) -and
                        (Test-JsonBooleanProperty -Object $isolation -Name 'gitRootPresent' -Expected $true) -and
                        (Test-JsonBooleanProperty -Object $isolation -Name 'gitRootMatchesSourceRoot' -Expected $true) -and
                        [string]$isolation.gitRootHash -ceq $manifestSourceRootHash -and
                        (Test-JsonIntegerProperty -Object $verification -Name 'diffHeaderCount' -Expected ([regex]::Matches($patchText.TrimStart([char]0xfeff), '(?m)^diff --git ').Count)) -and
                        (Test-JsonIntegerProperty -Object $verification -Name 'filemodeLineCount' -Expected $filemodeHits) -and
                        (Test-JsonIntegerProperty -Object $verification -Name 'allowedNewFileCount' -Expected $allowedNewFiles) -and
                        (Test-JsonIntegerProperty -Object $verification -Name 'filemodeViolationCount' -Expected $filemodeViolations) -and
                        (Test-JsonIntegerProperty -Object $verification -Name 'forbiddenPathCount' -Expected $pathViolationCount) -and
                        (Test-JsonIntegerProperty -Object $verification -Name 'secretPatternHits' -Expected $secretHits) -and
                        (Test-JsonIntegerProperty -Object $verification -Name 'rawSecretPatternHits' -Expected $secretHits)
                    if (-not $manifestContractValid) {
                        Add-Finding $findings "patch-manifest-contract" "BLOCK" (Convert-ToProofRelativePath $rootPath $manifestPath) "manifest identity, root, isolation, or verification metrics do not match bundle bytes"
                    }
                } catch {
                    Add-Finding $findings "patch-drop-pending" "BLOCK" (Convert-ToProofRelativePath $rootPath $manifestPath) "manifest is not valid JSON"
                }
            }
            $shaPath = Join-RootPath $patchDrop "$slug.sha256.txt"
            if (Test-Path -LiteralPath $shaPath -PathType Leaf) {
                New-HashSidecarCheck -PatchDrop $patchDrop -ShaPath $shaPath -Findings $findings -RootPath $rootPath
            }
        }

        $sidecarFiles = @(Get-ChildItem -LiteralPath $patchDrop -File -ErrorAction SilentlyContinue | Where-Object {
            $_.Name -match '\.(report\.md|verify\.log|sha256\.txt|manifest\.json)$'
        })
        foreach ($sidecar in $sidecarFiles) {
            $slug = $sidecar.Name -replace '\.(report\.md|verify\.log|sha256\.txt|manifest\.json)$', ''
            $patchPath = Join-RootPath $patchDrop "$slug.patch"
            if (-not (Test-Path -LiteralPath $patchPath -PathType Leaf)) {
                $orphanMetaCount++
                Add-Finding $findings "missing-patch" "BLOCK" (Convert-ToProofRelativePath $rootPath $sidecar.FullName) "PatchDrop metadata has no patch body"
            }
        }

        $desktopApplyHelpers = @(Get-ChildItem -LiteralPath $patchDrop -File -Filter "*-v3.desktop-apply.ps1" -ErrorAction SilentlyContinue)
        $desktopApplyHelperCount = $desktopApplyHelpers.Count
        foreach ($helper in $desktopApplyHelpers) {
            Add-Finding $findings "desktop-apply-helper" "BLOCK" (Convert-ToProofRelativePath $rootPath $helper.FullName) "top-level Desktop apply helper requires review/quarantine; do not bypass source lease gates"
        }

        $sourceLeaseSummary = Get-AwxSourceEditLeaseSummary -PatchDropDir $patchDrop
        $sourceLeaseActiveCount = [int]$sourceLeaseSummary.sourceLeaseActiveCount
        $sourceLeaseCorruptCount = [int]$sourceLeaseSummary.sourceLeaseCorruptCount
        $sourceLeaseExpiredCount = [int]$sourceLeaseSummary.sourceLeaseExpiredCount
        foreach ($lease in @($sourceLeaseSummary.sourceLeases)) {
            $relativeLockPath = Convert-ToProofRelativePath $rootPath ([string]$lease.lockPath)
            switch ([string]$lease.status) {
                'active' {
                    Add-Finding $findings "source-lease-active" "BLOCK" $relativeLockPath "source-edit lease is active topic=$($lease.topic) role=$($lease.role) expiresAtUtc=$($lease.expiresAtUtc)"
                }
                'expired' {
                    Add-Finding $findings "source-lease-expired" "WARN" $relativeLockPath "source-edit lease expired and is nonblocking topic=$($lease.topic) expiresAtUtc=$($lease.expiresAtUtc)"
                }
                default {
                    Add-Finding $findings "source-lease-corrupt" "BLOCK" $relativeLockPath "source-edit lease is corrupt topic=$($lease.topic) reason=$($lease.reason)"
                }
            }
        }

        $notebookPending = @(Get-ChildItem -LiteralPath $patchDrop -File -Filter "*.notebook-pending.md" -ErrorAction SilentlyContinue)
        $notebookPendingCount = $notebookPending.Count
        foreach ($notice in $notebookPending) {
            Add-Finding $findings "notebook-pending" "BLOCK" (Convert-ToProofRelativePath $rootPath $notice.FullName) "top-level notebook pending notice requires promotion, rejection, or applied/rejected queue cleanup before final proof"
        }

        $notebookReconciliations = @(Get-ChildItem -LiteralPath $patchDrop -File -Filter "*.notebook-reconciliation.md" -ErrorAction SilentlyContinue)
        $notebookReconciliationCount = $notebookReconciliations.Count
        foreach ($reconciliation in $notebookReconciliations) {
            $reconciliationTopic = $reconciliation.Name.Substring(0, $reconciliation.Name.Length - ".notebook-reconciliation.md".Length)
            $reconciliationText = Read-TextOrEmpty $reconciliation.FullName
            if (Test-NotebookDesktopProofRecorded -PatchDrop $patchDrop -Topic $reconciliationTopic) {
                $notebookReconciliationRecordedCount++
            } elseif ($reconciliationText -match "(?i)(proof-skipped|evidence_needed|Desktop final proof)") {
                $notebookReconciliationPendingCount++
            } else {
                $notebookReconciliationRecordedCount++
            }
        }
    }
    $observations.ActivePatchCount = $activePatchCount
    $observations.OrphanPatchDropMetaCount = $orphanMetaCount
    $observations.PatchSecretPatternHits = $patchSecretHits
    $observations.PatchFilemodeHits = $patchFilemodeHits
    $observations.PatchAllowedNewFileCount = $patchAllowedNewFileCount
    $observations.PatchFilemodeViolationCount = $patchFilemodeViolationCount
    $observations.PatchBinaryMarkerHits = $patchBinaryMarkerHits
    $observations.DesktopApplyHelperCount = $desktopApplyHelperCount
    $observations.SourceLeaseActiveCount = $sourceLeaseActiveCount
    $observations.SourceLeaseCorruptCount = $sourceLeaseCorruptCount
    $observations.SourceLeaseExpiredCount = $sourceLeaseExpiredCount
    $observations.NotebookPendingCount = $notebookPendingCount
    $observations.NotebookReconciliationCount = $notebookReconciliationCount
    $observations.NotebookReconciliationPendingCount = $notebookReconciliationPendingCount
    $observations.NotebookReconciliationRecordedCount = $notebookReconciliationRecordedCount

    $javaFiles = Get-ProofFiles -RootPath $rootPath -RelativeRoots @("main/java", "app/src/main/java_clean") -Extensions @(".java")
    $resourceFiles = Get-ProofFiles -RootPath $rootPath -RelativeRoots @("main/resources", "app/src/main/resources")
    $sourceSnapshotBefore = Get-SourceFingerprintSnapshot -RootPath $rootPath -Files @($javaFiles + $resourceFiles)
    $largeLineBaselines = Get-LargeSourceLineBaselines -BuildText $rootBuildText
    $largeActiveSourceGrowthRows = New-Object System.Collections.Generic.List[object]
    foreach ($file in $javaFiles) {
        $relativePath = Convert-ToProofRelativePath $rootPath $file.FullName
        if (-not $largeLineBaselines.ContainsKey($relativePath)) {
            continue
        }
        $lineCount = Get-ProofLineCount (Read-TextOrEmpty $file.FullName)
        $baseline = [int]$largeLineBaselines[$relativePath]
        if ($lineCount -gt $baseline) {
            $largeActiveSourceGrowthRows.Add([pscustomobject]@{
                Path = $relativePath
                Lines = $lineCount
                Baseline = $baseline
            }) | Out-Null
            Add-Finding $findings "large-active-source-growth" "BLOCK" $relativePath "active Java source exceeds known large-source line baseline lines=$lineCount baseline=$baseline"
        }
    }
    $observations.LargeActiveSourceBaselineCount = $largeLineBaselines.Count
    $observations.LargeActiveSourceGrowthCount = $largeActiveSourceGrowthRows.Count

    $instructionHits = 0
    foreach ($file in @($javaFiles + $resourceFiles)) {
        $text = Read-TextOrEmpty $file.FullName
        if ([string]::IsNullOrEmpty($text)) {
            continue
        }
        foreach ($match in [regex]::Matches($text, $PatchInstructionPattern)) {
            $line = $text.Substring(0, $match.Index).Split("`n").Count
            $instructionHits++
            Add-Finding $findings "patch-instruction-residue" "BLOCK" (Convert-ToProofRelativePath $rootPath $file.FullName) "instruction marker '$($match.Value)' at line $line"
        }
    }
    $observations.PatchInstructionResidueCount = $instructionHits

    $secretResourceStores = 0
    $secretNamedConfigCount = 0
    $secretNamedConfigExcludedCount = 0
    $activeResourceSecretHits = 0
    foreach ($file in $resourceFiles) {
        $relativePath = Convert-ToProofRelativePath $rootPath $file.FullName
        $extension = $file.Extension.TrimStart('.').ToLowerInvariant()
        if (@("p12", "jks", "pfx", "pem", "key", "keystore") -contains $extension) {
            $secretResourceStores++
            Add-Finding $findings "secret-resource" "BLOCK" $relativePath "active resource contains secret/key-store file type"
        }
        $text = Read-TextOrEmpty $file.FullName
        if (-not [string]::IsNullOrEmpty($text)) {
            $hits = [regex]::Matches($text, $SecretValuePattern).Count
            $activeResourceSecretHits += $hits
            if ($hits -gt 0) {
                Add-Finding $findings "secret-leak-risk" "BLOCK" $relativePath "active resource contains high-confidence secret pattern count=$hits"
            }
        }
        if ($file.Name -match '(?i)(secret|credential|token|api[-_]?key)') {
            $secretNamedConfigCount++
            if (Test-ProcessResourceExclude -BuildText $rootBuildText -FileName $file.Name) {
                $secretNamedConfigExcludedCount++
            } else {
                Add-Finding $findings "build-criteria-mismatch" "BLOCK" $relativePath "secret-named resource is not excluded by processResources"
            }
        }
    }
    $observations.SecretResourceStoreCount = $secretResourceStores
    $observations.SecretNamedConfigCount = $secretNamedConfigCount
    $observations.SecretNamedConfigExcludedCount = $secretNamedConfigExcludedCount
    $observations.ActiveResourceSecretPatternHits = $activeResourceSecretHits

    $godObjects = @(Get-JavaMetrics -RootPath $rootPath -JavaFiles $javaFiles)
    $observations.GodObjectCandidateCount = $godObjects.Count

    $sourceFilesAfter = @(
        (Get-ProofFiles -RootPath $rootPath -RelativeRoots @("main/java", "app/src/main/java_clean") -Extensions @(".java")) +
        (Get-ProofFiles -RootPath $rootPath -RelativeRoots @("main/resources", "app/src/main/resources"))
    )
    $sourceSnapshotAfter = Get-SourceFingerprintSnapshot -RootPath $rootPath -Files $sourceFilesAfter
    $sourceSnapshotChanges = @(Compare-SourceFingerprintSnapshot -Before @($sourceSnapshotBefore) -After @($sourceSnapshotAfter))
    foreach ($change in $sourceSnapshotChanges) {
        Add-Finding $findings "source-changed-during-proof" "BLOCK" $change.Path "active source changed during source governance proof kind=$($change.Kind)"
    }
    $observations.SourceChangedDuringProofCount = $sourceSnapshotChanges.Count

    $blockCount = @($findings | Where-Object { $_.Severity -eq "BLOCK" }).Count
    $warnCount = @($findings | Where-Object { $_.Severity -eq "WARN" }).Count
    $status = if ($blockCount -eq 0) { "PASS" } else { "FAIL" }
    $observations.Status = $status
    $observations.BlockingFindingCount = $blockCount
    $observations.WarningFindingCount = $warnCount

    if ([string]::IsNullOrWhiteSpace($OutputReportPath)) {
        $OutputReportPath = Join-RootPath $rootPath "build/reports/source-governance-proof-chain-v4/source-governance-proof-chain-v4.md"
    }
    $reportFullPath = [IO.Path]::GetFullPath($OutputReportPath)
    $reportParent = Split-Path -Parent $reportFullPath
    if (-not [string]::IsNullOrWhiteSpace($reportParent)) {
        New-Item -ItemType Directory -Force -Path $reportParent | Out-Null
    }

    $report = New-Object System.Text.StringBuilder
    [void]$report.AppendLine("# Source Governance Proof Chain v4")
    [void]$report.AppendLine()
    [void]$report.AppendLine("## Summary")
    [void]$report.AppendLine("- status: $status")
    [void]$report.AppendLine("- root: $rootPath")
    [void]$report.AppendLine("- generatedAtUtc: $($observations.GeneratedAtUtc)")
    [void]$report.AppendLine("- blockingFindingCount: $blockCount")
    [void]$report.AppendLine("- warningFindingCount: $warnCount")
    [void]$report.AppendLine()
    [void]$report.AppendLine("## PatchDrop v3 Queue Proof")
    [void]$report.AppendLine("- activePatchCount: $activePatchCount")
    [void]$report.AppendLine("- orphanPatchDropMetaCount: $orphanMetaCount")
    [void]$report.AppendLine("- patchSecretPatternHits: $patchSecretHits")
    [void]$report.AppendLine("- patchFilemodeHits: $patchFilemodeHits")
    [void]$report.AppendLine("- patchAllowedNewFileCount: $patchAllowedNewFileCount")
    [void]$report.AppendLine("- patchFilemodeViolationCount: $patchFilemodeViolationCount")
    [void]$report.AppendLine("- patchBinaryMarkerHits: $patchBinaryMarkerHits")
    [void]$report.AppendLine("- desktopApplyHelperCount: $desktopApplyHelperCount")
    [void]$report.AppendLine("- sourceLeaseActiveCount: $sourceLeaseActiveCount")
    [void]$report.AppendLine("- sourceLeaseCorruptCount: $sourceLeaseCorruptCount")
    [void]$report.AppendLine("- sourceLeaseExpiredCount: $sourceLeaseExpiredCount")
    [void]$report.AppendLine("- notebookPendingCount: $notebookPendingCount")
    [void]$report.AppendLine("- notebookReconciliationCount: $notebookReconciliationCount")
    [void]$report.AppendLine("- notebookReconciliationPendingCount: $notebookReconciliationPendingCount")
    [void]$report.AppendLine("- notebookReconciliationRecordedCount: $notebookReconciliationRecordedCount")
    [void]$report.AppendLine("- allowPendingPatchDrop: $($AllowPendingBundles.IsPresent)")
    [void]$report.AppendLine()
    [void]$report.AppendLine("## Patch Instruction Residue")
    [void]$report.AppendLine("- patchInstructionResidueCount: $instructionHits")
    [void]$report.AppendLine()
    [void]$report.AppendLine("## Source Stability Proof")
    [void]$report.AppendLine("- sourceChangedDuringProofCount: $($observations.SourceChangedDuringProofCount)")
    if ($sourceSnapshotChanges.Count -eq 0) {
        [void]$report.AppendLine("- none")
    } else {
        foreach ($change in $sourceSnapshotChanges) {
            [void]$report.AppendLine("- path: $($change.Path); kind: $($change.Kind)")
        }
    }
    [void]$report.AppendLine()
    [void]$report.AppendLine("## Secret Resource Proof")
    [void]$report.AppendLine("- secretResourceStoreCount: $secretResourceStores")
    [void]$report.AppendLine("- secretNamedConfigCount: $secretNamedConfigCount")
    [void]$report.AppendLine("- secretNamedConfigExcludedCount: $secretNamedConfigExcludedCount")
    [void]$report.AppendLine("- activeResourceSecretPatternHits: $activeResourceSecretHits")
    [void]$report.AppendLine()
    [void]$report.AppendLine("## Build Criteria Proof")
    [void]$report.AppendLine("- rootMainJavaExpected: main/java")
    [void]$report.AppendLine("- rootMainResourcesExpected: main/resources")
    [void]$report.AppendLine("- appJavaExpected: app/src/main/java_clean")
    [void]$report.AppendLine("- springBootBuildVersion: $($observations.SpringBootBuildVersion)")
    [void]$report.AppendLine("- springBootDocumentationMismatchCount: $springDocMismatches")
    [void]$report.AppendLine("- langchainDeclaredCount: $($observations.LangchainDeclaredCount)")
    [void]$report.AppendLine()
    [void]$report.AppendLine("## Large Active Source Baseline Proof")
    [void]$report.AppendLine("- largeActiveSourceBaselineCount: $($observations.LargeActiveSourceBaselineCount)")
    [void]$report.AppendLine("- largeActiveSourceGrowthCount: $($observations.LargeActiveSourceGrowthCount)")
    if ($largeActiveSourceGrowthRows.Count -eq 0) {
        [void]$report.AppendLine("- none")
    } else {
        foreach ($row in $largeActiveSourceGrowthRows) {
            [void]$report.AppendLine("- path: $($row.Path); lines: $($row.Lines); baseline: $($row.Baseline)")
        }
    }
    [void]$report.AppendLine()
    [void]$report.AppendLine("## God Object Quantification")
    [void]$report.AppendLine("- formula: godObjectScore = lineCount + methodCount*25 + broadCatchCount*10")
    [void]$report.AppendLine("- candidateCount: $($godObjects.Count)")
    if ($godObjects.Count -eq 0) {
        [void]$report.AppendLine("- none")
    } else {
        foreach ($row in @($godObjects | Select-Object -First 30)) {
            [void]$report.AppendLine("- path: $($row.Path); lines: $($row.Lines); methods: $($row.Methods); broadCatches: $($row.BroadCatches); godObjectScore: $($row.Score)")
        }
    }
    [void]$report.AppendLine()
    [void]$report.AppendLine("## Findings")
    if ($findings.Count -eq 0) {
        [void]$report.AppendLine("- none")
    } else {
        foreach ($finding in $findings) {
            $findingDetail = ([string]$finding.Detail).Replace([char]96, [char]39)
            [void]$report.AppendLine("- class: $($finding.Class); severity: $($finding.Severity); path: $($finding.Path); detail: $findingDetail")
        }
    }

    [IO.File]::WriteAllText($reportFullPath, $report.ToString(), [Text.UTF8Encoding]::new($false))
    Write-Host "[AWX][source-governance][v4] status=$status blocking=$blockCount warnings=$warnCount activePatchCount=$activePatchCount desktopApplyHelperCount=$desktopApplyHelperCount sourceLeaseActiveCount=$sourceLeaseActiveCount sourceLeaseCorruptCount=$sourceLeaseCorruptCount sourceLeaseExpiredCount=$sourceLeaseExpiredCount notebookPendingCount=$notebookPendingCount notebookReconciliationCount=$notebookReconciliationCount notebookReconciliationPendingCount=$notebookReconciliationPendingCount notebookReconciliationRecordedCount=$notebookReconciliationRecordedCount largeActiveSourceGrowth=$($largeActiveSourceGrowthRows.Count) sourceChangedDuringProof=$($sourceSnapshotChanges.Count) patchInstructionResidue=$instructionHits secretResourceStores=$secretResourceStores activeResourceSecretHits=$activeResourceSecretHits godObjectCandidates=$($godObjects.Count) report=$reportFullPath"

    return [pscustomobject]@{
        Status = $status
        Findings = @($findings.ToArray())
        Observations = $observations
        GodObjects = @($godObjects)
        ReportPath = $reportFullPath
    }
}

function Set-TestFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowEmptyString()][string]$Value
    )
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Initialize-TestRoot {
    param([Parameter(Mandatory = $true)][string]$TestRoot)
    if (Test-Path -LiteralPath $TestRoot) {
        Remove-Item -LiteralPath $TestRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $TestRoot | Out-Null
    $rootBuild = @'
plugins {
    java
    id("org.springframework.boot") version "3.3.4"
}
dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}
sourceSets {
    main {
        java {
            srcDirs("main/java")
        }
        resources {
            srcDirs("main/resources")
        }
    }
}
tasks.processResources {
    exclude(
        "application-secrets.yml",
        "keystore.p12",
        "*.p12",
        "*.jks",
        "**/*.p12",
        "**/*.jks"
    )
}
'@
    $appBuild = @'
plugins {
    java
}
sourceSets {
  main {
    java.setSrcDirs(listOf("src/main/java_clean"))
    resources.setSrcDirs(listOf("src/main/resources"))
  }
}
'@
    Set-TestFile (Join-Path $TestRoot "build.gradle.kts") $rootBuild
    Set-TestFile (Join-Path $TestRoot "app/build.gradle.kts") $appBuild
    Set-TestFile (Join-Path $TestRoot "main/README.md") "Demo uses Spring Boot 3.3.4.`n"
    Set-TestFile (Join-Path $TestRoot "main/java/com/example/Foo.java") "package com.example;`npublic class Foo { public void ok() {} }`n"
    Set-TestFile (Join-Path $TestRoot "app/src/main/java_clean/com/example/AppFoo.java") "package com.example;`npublic class AppFoo { public void ok() {} }`n"
    Set-TestFile (Join-Path $TestRoot "main/resources/application-secrets.yml") "placeholder: true`n"
    New-Item -ItemType Directory -Force -Path (Join-Path $TestRoot "__patch_drop__") | Out-Null
}

function New-TestBundle {
    param(
        [Parameter(Mandatory = $true)][string]$TestRoot,
        [Parameter(Mandatory = $true)][string]$Slug,
        [Parameter(Mandatory = $true)][string]$PatchBody,
        [switch]$WrongHash
    )
    $patchDrop = Join-Path $TestRoot "__patch_drop__"
    $patchName = "$Slug.patch"
    $patchPath = Join-Path $patchDrop $patchName
    $topic = if ($Slug.EndsWith('-v3', [StringComparison]::Ordinal)) { $Slug.Substring(0, $Slug.Length - 3) } else { $Slug }
    $reportPath = Join-Path $patchDrop "$Slug.report.md"
    $verifyPath = Join-Path $patchDrop "$Slug.verify.log"
    $manifestPath = Join-Path $patchDrop "$Slug.manifest.json"
    $reportText = "# report`nDesktop final proof: evidence_needed`n"
    $verifyText = "secretPatternHits=0`nrawSecretPatternHits=0`nDesktop final proof: evidence_needed`n"
    Set-TestFile $patchPath $PatchBody
    Set-TestFile $reportPath $reportText
    Set-TestFile $verifyPath $verifyText
    $modeSummary = Get-PatchModeSummary -PatchText $PatchBody
    $rootHash = 'a' * 64
    $secretHits = [regex]::Matches(($PatchBody + $reportText + $verifyText), $SecretValuePattern).Count
    $manifest = [ordered]@{
        schemaVersion = 'patchdrop-producer-v3'
        topic = $topic
        slug = $topic
        node = 'macmini'
        activePatch = $patchName
        desktopFinalProof = 'evidence_needed'
        sourceRootInputHash = $rootHash
        sourceRootHash = $rootHash
        sourceIsolation = [ordered]@{
            guard = 'PASS'
            sourceRootKind = 'local-worktree'
            sharedSourceRoot = $false
            desktopCanonicalSourceRoot = $false
            directCanonicalSourceEdit = $false
            gitRootPresent = $true
            gitRootMatchesSourceRoot = $true
            gitRootHash = $rootHash
        }
        verification = [ordered]@{
            diffHeaderCount = [regex]::Matches($PatchBody.TrimStart([char]0xfeff), '(?m)^diff --git ').Count
            filemodeLineCount = [int]$modeSummary.FilemodeLineCount
            allowedNewFileCount = [int]$modeSummary.AllowedNewFileCount
            filemodeViolationCount = [int]$modeSummary.FilemodeViolationCount
            forbiddenPathCount = [int](Get-PatchPathViolationCount -PatchText $PatchBody)
            secretPatternHits = $secretHits
            rawSecretPatternHits = $secretHits
        }
    }
    Set-TestFile $manifestPath (($manifest | ConvertTo-Json -Depth 10) + "`n")
    $shaLines = @()
    foreach ($entry in @(
        [pscustomobject]@{ Name = $patchName; Path = $patchPath },
        [pscustomobject]@{ Name = "$Slug.report.md"; Path = $reportPath },
        [pscustomobject]@{ Name = "$Slug.verify.log"; Path = $verifyPath },
        [pscustomobject]@{ Name = "$Slug.manifest.json"; Path = $manifestPath }
    )) {
        $hash = if ($WrongHash -and $entry.Name -ceq $patchName) {
            '0000000000000000000000000000000000000000000000000000000000000000'
        } else {
            (Get-FileHash -LiteralPath $entry.Path -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        $shaLines += "$hash  $($entry.Name)"
    }
    Set-TestFile (Join-Path $patchDrop "$Slug.sha256.txt") (($shaLines -join "`n") + "`n")
}

function Set-TestBundleHashes {
    param(
        [Parameter(Mandatory = $true)][string]$TestRoot,
        [Parameter(Mandatory = $true)][string]$Slug
    )
    $patchDrop = Join-Path $TestRoot '__patch_drop__'
    $lines = @()
    foreach ($suffix in @('.patch', '.report.md', '.verify.log', '.manifest.json')) {
        $name = $Slug + $suffix
        $path = Join-Path $patchDrop $name
        $lines += "$((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant())  $name"
    }
    Set-TestFile (Join-Path $patchDrop "$Slug.sha256.txt") (($lines -join "`n") + "`n")
}

function Assert-Condition {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Message = "assertion failed"
    )
    if ($Condition) {
        Write-Host "[source-governance-v4-test][PASS] $Name"
        return 0
    }
    Write-Host "[source-governance-v4-test][FAIL] $Name :: $Message"
    return 1
}

function Test-HasFinding {
    param(
        [Parameter(Mandatory = $true)]$Result,
        [Parameter(Mandatory = $true)][string]$Class
    )
    return (@($Result.Findings | Where-Object { $_.Class -eq $Class }).Count -gt 0)
}

function Invoke-SelfTest {
    $failures = 0
    $testRoot = Join-Path ([IO.Path]::GetTempPath()) ("awx-source-governance-v4-" + [guid]::NewGuid().ToString("N"))
    try {
        Initialize-TestRoot $testRoot
        $clean = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "clean fixture passes" ($clean.Status -eq "PASS") "expected PASS"

        Initialize-TestRoot $testRoot
        $variableBuild = (Read-TextOrEmpty (Join-Path $testRoot "build.gradle.kts")).Replace(
            'dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}',
            'val langchain4jVersion = "1.0.1"

dependencies {
    implementation("dev.langchain4j:langchain4j:$langchain4jVersion")
}')
        Set-TestFile (Join-Path $testRoot "build.gradle.kts") $variableBuild
        $variableLangchain = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "langchain4j variable version purity passes" (($variableLangchain.Status -eq "PASS") -and -not (Test-HasFinding $variableLangchain "langchain4j-version-purity")) "expected langchain4jVersion=1.0.1 variable to pass"

        Initialize-TestRoot $testRoot
        Set-TestFile (Join-Path $testRoot "__patch_drop__/missing-v3.patch") "not a real patch`n"
        $missing = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "missing bundle metadata detected" (Test-HasFinding $missing "missing-bundle-meta") "expected missing-bundle-meta"

        Initialize-TestRoot $testRoot
        New-TestBundle -TestRoot $testRoot -Slug 'manifest-schema-v3' -PatchBody "diff --git a/README.md b/README.md`n--- a/README.md`n+++ b/README.md`n@@ -1 +1 @@`n-before`n+after`n"
        Set-TestFile (Join-Path $testRoot '__patch_drop__/manifest-schema-v3.manifest.json') '{"activePatch":"manifest-schema-v3.patch"}'
        $manifestSchema = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'manifest schema is required' (Test-HasFinding $manifestSchema 'patch-manifest-schema') 'expected patch-manifest-schema'

        Initialize-TestRoot $testRoot
        New-TestBundle -TestRoot $testRoot -Slug "filemode-v3" -PatchBody "diff --git a/README.md b/README.md`nold mode 100644`nnew mode 100755`n"
        $filemode = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "filemode blocked detected" (Test-HasFinding $filemode "filemode-blocked") "expected filemode-blocked"

        Initialize-TestRoot $testRoot
        $newFilePatch = @(
            'diff --git a/main/resources/new-fixture.txt b/main/resources/new-fixture.txt',
            'new file mode 100644',
            'index 0000000..1111111',
            '--- /dev/null',
            '+++ b/main/resources/new-fixture.txt',
            '@@ -0,0 +1 @@',
            '+created'
        ) -join "`n"
        New-TestBundle -TestRoot $testRoot -Slug "new-file-v3" -PatchBody ($newFilePatch + "`n")
        $newFile = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "canonical new-file patch is not filemode drift" (
            $newFile.Status -eq 'PASS' -and
            -not (Test-HasFinding $newFile 'filemode-blocked') -and
            [int]$newFile.Observations.PatchFilemodeHits -eq 1
        ) "expected canonical new-file patch to pass with observable mode metadata"

        Set-TestFile (Join-Path $testRoot 'main/resources/new-fixture.txt') "created`n"
        $newFileAfterApply = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "canonical new-file structure stays valid during post-apply proof" (
            -not (Test-HasFinding $newFileAfterApply 'filemode-blocked')
        ) "source governance must not reuse the consumer-only target absence gate"

        foreach ($unsafeMode in @('100755', '100600', '120000')) {
            Initialize-TestRoot $testRoot
            $unsafeModePatch = $newFilePatch.Replace('new file mode 100644', "new file mode $unsafeMode")
            New-TestBundle -TestRoot $testRoot -Slug "unsafe-mode-$unsafeMode-v3" -PatchBody ($unsafeModePatch + "`n")
            $unsafeModeResult = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
            $failures += Assert-Condition "unsafe new-file mode $unsafeMode remains blocked" (Test-HasFinding $unsafeModeResult 'filemode-blocked') "expected mode $unsafeMode to remain blocked"
        }

        Initialize-TestRoot $testRoot
        $deletedPatch = $newFilePatch.Replace('new file mode 100644', 'deleted file mode 100644')
        New-TestBundle -TestRoot $testRoot -Slug 'deleted-v3' -PatchBody ($deletedPatch + "`n")
        $deleted = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'deleted file mode remains blocked' (Test-HasFinding $deleted 'filemode-blocked') 'expected deletion to remain blocked'

        Initialize-TestRoot $testRoot
        $modeFreeDeletePatch = "diff --git a/README.md b/README.md`n--- a/README.md`n+++ /dev/null`n@@ -1 +0,0 @@`n-before`n"
        New-TestBundle -TestRoot $testRoot -Slug 'mode-free-delete-v3' -PatchBody $modeFreeDeletePatch
        $modeFreeDelete = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'mode-free deletion remains blocked' (Test-HasFinding $modeFreeDelete 'filemode-blocked') 'expected semantic deletion to remain blocked'

        Initialize-TestRoot $testRoot
        $unsafePathPatch = $newFilePatch.Replace('main/resources/new-fixture.txt', '../outside.txt')
        New-TestBundle -TestRoot $testRoot -Slug 'unsafe-path-v3' -PatchBody ($unsafePathPatch + "`n")
        $unsafePath = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'unsafe new-file target remains blocked' (Test-HasFinding $unsafePath 'filemode-blocked') 'expected unsafe target to remain blocked'

        Initialize-TestRoot $testRoot
        $adsPatch = "diff --git a/apikey.ps1::`$DATA b/apikey.ps1::`$DATA`n--- a/apikey.ps1::`$DATA`n+++ b/apikey.ps1::`$DATA`n@@ -1 +1 @@`n-before`n+after`n"
        New-TestBundle -TestRoot $testRoot -Slug 'ads-path-v3' -PatchBody $adsPatch
        $adsPath = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'ADS target remains blocked' (Test-HasFinding $adsPath 'unsafe-patch-path') 'expected ADS target to remain blocked'

        Initialize-TestRoot $testRoot
        $envrcPatch = "diff --git a/.envrc b/.envrc`n--- a/.envrc`n+++ b/.envrc`n@@ -1 +1 @@`n-before`n+after`n"
        New-TestBundle -TestRoot $testRoot -Slug 'envrc-path-v3' -PatchBody $envrcPatch
        $envrcPath = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition '.env prefix target remains blocked' (Test-HasFinding $envrcPath 'unsafe-patch-path') 'expected .envrc target to remain blocked'

        Initialize-TestRoot $testRoot
        $forbiddenPathPatch = $newFilePatch.Replace('main/resources/new-fixture.txt', 'pages/api/unsafe.ts')
        New-TestBundle -TestRoot $testRoot -Slug 'forbidden-path-v3' -PatchBody ($forbiddenPathPatch + "`n")
        $forbiddenPath = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'forbidden new-file target remains blocked' (Test-HasFinding $forbiddenPath 'filemode-blocked') 'expected forbidden target to remain blocked'

        Initialize-TestRoot $testRoot
        $malformedHunkPatch = $newFilePatch.Replace('@@ -0,0 +1 @@', '@@ -0,0 +1,999 @@')
        New-TestBundle -TestRoot $testRoot -Slug 'malformed-hunk-v3' -PatchBody ($malformedHunkPatch + "`n")
        $malformedHunk = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'malformed new-file hunk count remains blocked' (Test-HasFinding $malformedHunk 'filemode-blocked') 'expected malformed new-file hunk to remain blocked'

        $trackedEnvelope = @(
            'diff --git a/f.txt b/f.txt',
            '--- a/f.txt',
            '+++ b/f.txt'
        )
        $validTracked = ($trackedEnvelope + @(
            '@@ -1 +1 @@', '-a', '+A',
            '@@ -3 +3 @@', '-c', '+C', ''
        )) -join "`n"
        Initialize-TestRoot $testRoot
        New-TestBundle -TestRoot $testRoot -Slug 'exact-hunk-valid-v3' -PatchBody $validTracked
        $validTrackedResult = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'exact tracked multi-hunk remains valid' (
            $validTrackedResult.Status -eq 'PASS' -and -not (Test-HasFinding $validTrackedResult 'patch-structure-invalid')
        ) 'expected exact multi-hunk patch to pass'

        $headerLikePayload = ($trackedEnvelope + @(
            '@@ -1 +1 @@', '--- /dev/null', '+++ /dev/null', ''
        )) -join "`n"
        Initialize-TestRoot $testRoot
        New-TestBundle -TestRoot $testRoot -Slug 'header-like-payload-v3' -PatchBody $headerLikePayload
        $headerLikePayloadResult = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'header-like tracked payload is not parsed as envelope or mode metadata' (
            $headerLikePayloadResult.Status -eq 'PASS' -and
            -not (Test-HasFinding $headerLikePayloadResult 'patch-structure-invalid') -and
            -not (Test-HasFinding $headerLikePayloadResult 'filemode-blocked')
        ) 'expected ---/+++ payload content to remain hunk data'

        $unsafeLookingPayload = ($trackedEnvelope + @(
            '@@ -1 +1 @@', '--- ../../outside.txt', '+++ pages/api/unsafe.ts', ''
        )) -join "`n"
        Initialize-TestRoot $testRoot
        New-TestBundle -TestRoot $testRoot -Slug 'unsafe-looking-payload-v3' -PatchBody $unsafeLookingPayload
        $unsafeLookingPayloadResult = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'path scanner ignores header-like hunk payload' (
            $unsafeLookingPayloadResult.Status -eq 'PASS' -and
            -not (Test-HasFinding $unsafeLookingPayloadResult 'unsafe-patch-path')
        ) 'expected payload text to stay out of target path classification'

        foreach ($case in @(
            [pscustomobject]@{ Label = 'deficit'; Body = @('@@ -1 +1 @@', '-a', '+A', '@@ -3,2 +3,2 @@', '-c', '+C', '') },
            [pscustomobject]@{ Label = 'overflow'; Body = @('@@ -1 +1 @@', '-a', '+A', '@@ -3 +3 @@', '-c', '+C', '+extra', '') },
            [pscustomobject]@{ Label = 'marker-before'; Body = @('@@ -1 +1 @@', '\ No newline at end of file', '-a', '+A', '') },
            [pscustomobject]@{ Label = 'trailing-junk'; Body = @('@@ -1 +1 @@', '-a', '+A', 'TRAILER', '') },
            [pscustomobject]@{ Label = 'old-marker-before-old-payload'; Body = @('@@ -1,2 +1,2 @@', '-a', '\ No newline at end of file', '-b', '+A', '+B', '') },
            [pscustomobject]@{ Label = 'new-marker-before-new-payload'; Body = @('@@ -1 +1,2 @@', '-a', '+A', '\ No newline at end of file', '+B', '') },
            [pscustomobject]@{ Label = 'marker-before-later-hunk'; Body = @('@@ -1 +1 @@', '-a', '+A', '\ No newline at end of file', '@@ -3 +3 @@', '-c', '+C', '') },
            [pscustomobject]@{ Label = 'context-marker-before-context'; Body = @('@@ -1,2 +1,2 @@', ' one', '\ No newline at end of file', ' two', '') }
        )) {
            Initialize-TestRoot $testRoot
            $invalidTracked = ($trackedEnvelope + $case.Body) -join "`n"
            New-TestBundle -TestRoot $testRoot -Slug "exact-hunk-$($case.Label)-v3" -PatchBody $invalidTracked
            $invalidTrackedResult = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
            $failures += Assert-Condition "exact tracked $($case.Label) remains blocked" (
                Test-HasFinding $invalidTrackedResult 'patch-structure-invalid'
            ) 'expected patch-structure-invalid'
        }

        Initialize-TestRoot $testRoot
        New-TestBundle -TestRoot $testRoot -Slug 'sidecar-secret-v3' -PatchBody "diff --git a/README.md b/README.md`n--- a/README.md`n+++ b/README.md`n@@ -1 +1 @@`n-before`n+after`n"
        $sidecarSecret = 'author' + 'ization: REDACTED'
        Set-TestFile (Join-Path $testRoot '__patch_drop__/sidecar-secret-v3.report.md') ("# report`n$sidecarSecret`n")
        Set-TestBundleHashes -TestRoot $testRoot -Slug 'sidecar-secret-v3'
        $sidecarSecretResult = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'SHA-covered sidecar secret remains blocked' (
            Test-HasFinding $sidecarSecretResult 'secret-leak-risk'
        ) 'expected secret-leak-risk from report sidecar'

        Initialize-TestRoot $testRoot
        New-TestBundle -TestRoot $testRoot -Slug 'manifest-contract-v3' -PatchBody "diff --git a/README.md b/README.md`n--- a/README.md`n+++ b/README.md`n@@ -1 +1 @@`n-before`n+after`n"
        $manifestContractPath = Join-Path $testRoot '__patch_drop__/manifest-contract-v3.manifest.json'
        $manifestContractData = Get-Content -LiteralPath $manifestContractPath -Raw | ConvertFrom-Json
        $manifestContractData.topic = 'wrong-topic'
        Set-TestFile $manifestContractPath (($manifestContractData | ConvertTo-Json -Depth 10) + "`n")
        Set-TestBundleHashes -TestRoot $testRoot -Slug 'manifest-contract-v3'
        $manifestContractResult = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'manifest identity mutation remains blocked' (
            Test-HasFinding $manifestContractResult 'patch-manifest-contract'
        ) 'expected patch-manifest-contract'

        Initialize-TestRoot $testRoot
        $binaryPatch = "diff --git a/main/resources/blob.bin b/main/resources/blob.bin`nBinary files a/main/resources/blob.bin and b/main/resources/blob.bin differ`n"
        New-TestBundle -TestRoot $testRoot -Slug 'binary-v3' -PatchBody $binaryPatch
        $binary = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'binary patch marker remains blocked' (Test-HasFinding $binary 'binary-patch-blocked') 'expected binary marker to remain blocked'

        Initialize-TestRoot $testRoot
        New-TestBundle -TestRoot $testRoot -Slug 'nonsense-v3' -PatchBody "diff --git a/README.md b/README.md`nnonsense`n"
        $nonsense = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'malformed unified envelope remains blocked' (Test-HasFinding $nonsense 'patch-structure-invalid') 'expected malformed unified patch to remain blocked'

        Initialize-TestRoot $testRoot
        $composedDelete = "--- a/README.md`n+++ /dev/null`n@@ -1 +0,0 @@`n-before`ndiff --git a/dummy.txt b/dummy.txt`n"
        New-TestBundle -TestRoot $testRoot -Slug 'composed-delete-v3' -PatchBody $composedDelete
        $composed = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition 'traditional composed deletion remains blocked' (Test-HasFinding $composed 'patch-structure-invalid') 'expected composed traditional patch to remain blocked'

        Initialize-TestRoot $testRoot
        New-TestBundle -TestRoot $testRoot -Slug "sha-v3" -PatchBody "diff --git a/README.md b/README.md`n" -WrongHash
        $sha = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "sha mismatch detected" (Test-HasFinding $sha "sha-mismatch") "expected sha-mismatch"

        Initialize-TestRoot $testRoot
        Set-TestFile (Join-Path $testRoot "__patch_drop__/unsafe-v3.desktop-apply.ps1") "param([switch]`$ApproveNoSourceLease)`ngit apply .\unsafe-v3.patch`n"
        $desktopHelper = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "desktop apply helper detected" (Test-HasFinding $desktopHelper "desktop-apply-helper") "expected desktop-apply-helper"

        Initialize-TestRoot $testRoot
        Set-TestFile (Join-Path $testRoot "__patch_drop__/queued.notebook-pending.md") "notebook_bundle: `notebook/queued-notebook-v3.patch``n"
        $notebookPending = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "notebook pending notice detected" (Test-HasFinding $notebookPending "notebook-pending") "expected notebook-pending"

        Initialize-TestRoot $testRoot
        $leaseDir = Join-Path $testRoot "__patch_drop__/source-edit-locks/active-topic.lock"
        New-Item -ItemType Directory -Force -Path $leaseDir | Out-Null
        Set-TestFile (Join-Path $leaseDir "lease.json") "{`"topic`":`"active-topic`",`"ownerId`":`"desktop-codex`",`"role`":`"desktop`",`"expiresAtUtc`":`"2099-01-01T00:00:00Z`"}"
        $sourceLease = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $sourceLeaseReport = Read-TextOrEmpty $sourceLease.ReportPath
        $failures += Assert-Condition "active source lease blocks final proof" ((Test-HasFinding $sourceLease "source-lease-active") -and ($sourceLease.Observations.SourceLeaseActiveCount -eq 1) -and $sourceLeaseReport.Contains("sourceLeaseActiveCount: 1")) "expected source-lease-active finding and sourceLeaseActiveCount=1"

        Initialize-TestRoot $testRoot
        New-Item -ItemType Directory -Force -Path (Join-Path $testRoot "__patch_drop__/source-edit-locks/corrupt-topic.lock") | Out-Null
        $sourceLeaseCorrupt = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $sourceLeaseCorruptReport = Read-TextOrEmpty $sourceLeaseCorrupt.ReportPath
        $failures += Assert-Condition "corrupt source lease blocks final proof separately" ((Test-HasFinding $sourceLeaseCorrupt "source-lease-corrupt") -and ($sourceLeaseCorrupt.Observations.SourceLeaseActiveCount -eq 0) -and ($sourceLeaseCorrupt.Observations.SourceLeaseCorruptCount -eq 1) -and $sourceLeaseCorruptReport.Contains("sourceLeaseCorruptCount: 1")) "expected source-lease-corrupt finding, sourceLeaseActiveCount=0, and sourceLeaseCorruptCount=1"

        Initialize-TestRoot $testRoot
        $expiredLeaseDir = Join-Path $testRoot "__patch_drop__/source-edit-locks/expired-topic.lock"
        New-Item -ItemType Directory -Force -Path $expiredLeaseDir | Out-Null
        Set-TestFile (Join-Path $expiredLeaseDir "lease.json") "{`"topic`":`"expired-topic`",`"ownerId`":`"desktop-codex`",`"role`":`"desktop`",`"expiresAt`":`"2000-01-01T00:00:00Z`"}"
        $sourceLeaseExpired = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $sourceLeaseExpiredReport = Read-TextOrEmpty $sourceLeaseExpired.ReportPath
        $expiredLeaseFindingCount = @($sourceLeaseExpired.Findings | Where-Object { $_.Class -in @('source-lease-active', 'source-lease-corrupt') }).Count
        $failures += Assert-Condition "legacy expired source lease is observable but nonblocking" (($expiredLeaseFindingCount -eq 0) -and ($sourceLeaseExpired.Observations.SourceLeaseActiveCount -eq 0) -and ($sourceLeaseExpired.Observations.SourceLeaseCorruptCount -eq 0) -and ($sourceLeaseExpired.Observations.SourceLeaseExpiredCount -eq 1) -and $sourceLeaseExpiredReport.Contains("sourceLeaseExpiredCount: 1")) "expected expired lease count=1 with no active/corrupt finding"

        Initialize-TestRoot $testRoot
        Set-TestFile (Join-Path $testRoot "__patch_drop__/source-edit-locks") "malformed lock root"
        $sourceLeaseMalformedRoot = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "non-directory source lease root fails closed" ((Test-HasFinding $sourceLeaseMalformedRoot "source-lease-corrupt") -and ($sourceLeaseMalformedRoot.Observations.SourceLeaseActiveCount -eq 0) -and ($sourceLeaseMalformedRoot.Observations.SourceLeaseCorruptCount -eq 1)) "expected malformed source-edit-locks root to block as corrupt"

        Initialize-TestRoot $testRoot
        Set-TestFile (Join-Path $testRoot "__patch_drop__/proof-v3.notebook-reconciliation.md") "proof-skipped because RunGradle not set`n"
        Set-TestFile (Join-Path $testRoot "__patch_drop__/recorded-v3.notebook-reconciliation.md") "Desktop proof recorded after focused verification`n"
        Set-TestFile (Join-Path $testRoot "__patch_drop__/applied-proof-v3.notebook-reconciliation.md") "proof-skipped in first pass`ndesktop_final_proof: present in applied/applied-proof-v3.desktop-gates.md`n"
        Set-TestFile (Join-Path $testRoot "__patch_drop__/applied/applied-proof-v3.desktop-gates.md") "status: APPLIED_DESKTOP_VERIFIED`n"
        $notebookReconciliation = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $notebookReconciliationReport = Read-TextOrEmpty $notebookReconciliation.ReportPath
        $failures += Assert-Condition "notebook reconciliation counted as supporting evidence" (($notebookReconciliation.Observations.NotebookReconciliationCount -eq 3) -and $notebookReconciliationReport.Contains("notebookReconciliationCount: 3")) "expected notebookReconciliationCount=3 in observations and report"
        $failures += Assert-Condition "notebook reconciliation pending proof counted separately" (($notebookReconciliation.Observations.NotebookReconciliationPendingCount -eq 1) -and $notebookReconciliationReport.Contains("notebookReconciliationPendingCount: 1")) "expected notebookReconciliationPendingCount=1 in observations and report"
        $failures += Assert-Condition "notebook reconciliation recorded proof counted separately" (($notebookReconciliation.Observations.NotebookReconciliationRecordedCount -eq 2) -and $notebookReconciliationReport.Contains("notebookReconciliationRecordedCount: 2")) "expected notebookReconciliationRecordedCount=2 in observations and report"

        Initialize-TestRoot $testRoot
        Set-TestFile (Join-Path $testRoot "main/java/com/example/Foo.java") "package com.example;`n// Before snippet`npublic class Foo {}`n"
        $residue = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "patch instruction residue detected" (Test-HasFinding $residue "patch-instruction-residue") "expected patch-instruction-residue"

        Initialize-TestRoot $testRoot
        Set-TestFile (Join-Path $testRoot "main/resources/keystore.p12") "not-real"
        $secret = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "secret resource detected" (Test-HasFinding $secret "secret-resource") "expected secret-resource"

        Initialize-TestRoot $testRoot
        Set-TestFile (Join-Path $testRoot "main/resources/application.yml") "supabase: sb_secret_$('A' * 24)`n"
        $supabaseSecret = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "supabase sb key pattern detected in active resources" (Test-HasFinding $supabaseSecret "secret-leak-risk") "expected secret-leak-risk for Supabase sb key prefix"

        Initialize-TestRoot $testRoot
        Set-TestFile (Join-Path $testRoot "main/resources/application.yml") "supabaseAccessToken: sbp_$('B' * 24)`n"
        $supabasePat = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "supabase access token pattern detected in active resources" (Test-HasFinding $supabasePat "secret-leak-risk") "expected secret-leak-risk for Supabase access token prefix"

        Initialize-TestRoot $testRoot
        Set-TestFile (Join-Path $testRoot "main/README.md") "Demo uses Spring Boot 3.4.0.`n"
        $buildMismatch = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $failures += Assert-Condition "build criteria mismatch detected" (Test-HasFinding $buildMismatch "build-criteria-mismatch") "expected build-criteria-mismatch"

        Initialize-TestRoot $testRoot
        $snapshotFile = Get-Item -LiteralPath (Join-Path $testRoot "main/java/com/example/Foo.java")
        $snapshotBefore = Get-SourceFingerprintSnapshot -RootPath $testRoot -Files @($snapshotFile)
        Start-Sleep -Milliseconds 25
        Set-TestFile $snapshotFile.FullName "package com.example;`npublic class Foo { public void changed() {} }`n"
        $snapshotAfter = Get-SourceFingerprintSnapshot -RootPath $testRoot -Files @(Get-Item -LiteralPath $snapshotFile.FullName)
        $snapshotChanges = @(Compare-SourceFingerprintSnapshot -Before @($snapshotBefore) -After @($snapshotAfter))
        $failures += Assert-Condition "source fingerprint detects changed active source" ($snapshotChanges.Count -eq 1 -and $snapshotChanges[0].Path -eq "main/java/com/example/Foo.java") "expected one changed source fingerprint"

        Initialize-TestRoot $testRoot
        $baselineBuild = (Read-TextOrEmpty (Join-Path $testRoot "build.gradle.kts")) + @'

val knownLargeSourceLineBaselines = mapOf(
    "main/java/com/example/Giant.java" to 3
)
'@
        Set-TestFile (Join-Path $testRoot "build.gradle.kts") $baselineBuild
        Set-TestFile (Join-Path $testRoot "main/java/com/example/Giant.java") "package com.example;`npublic class Giant {`n    public void ok() {}`n}`n"
        $largeGrowth = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $largeGrowthReport = Read-TextOrEmpty $largeGrowth.ReportPath
        $failures += Assert-Condition "large active source growth detected before Gradle" ((Test-HasFinding $largeGrowth "large-active-source-growth") -and $largeGrowthReport.Contains("largeActiveSourceGrowthCount: 1")) "expected large-active-source-growth finding and report count"

        Initialize-TestRoot $testRoot
        $bigBody = "package com.example;`npublic class Giant {`n" + (("    public void m() { try { } catch (Exception ex) { } }`n" * 50)) + ("    // filler`n" * 1600) + "}`n"
        Set-TestFile (Join-Path $testRoot "main/java/com/example/Giant.java") $bigBody
        $god = Invoke-SourceGovernanceProofChainV4 -RootPath $testRoot -AllowPendingBundles
        $godReport = Read-TextOrEmpty $god.ReportPath
        $failures += Assert-Condition "god object score quantified" (($god.GodObjects.Count -gt 0) -and $godReport.Contains("godObjectScore")) "expected god object score in report"
    } finally {
        if (Test-Path -LiteralPath $testRoot) {
            Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    if ($failures -gt 0) {
        Write-Host "[source-governance-v4-test][SUMMARY] failed=$failures"
        exit 1
    }
    Write-Host "[source-governance-v4-test][SUMMARY] failed=0"
    exit 0
}

if ($SelfTest) {
    Invoke-SelfTest
}

$resolvedRoot = Resolve-ProofRoot $Root
$result = Invoke-SourceGovernanceProofChainV4 -RootPath $resolvedRoot -OutputReportPath $ReportPath -AllowPendingBundles:$AllowPendingPatchDrop
if ($result.Status -ne "PASS") {
    exit 1
}
exit 0
