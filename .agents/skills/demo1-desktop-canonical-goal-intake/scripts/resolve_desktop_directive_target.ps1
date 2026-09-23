[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyString()]
    [AllowNull()]
    [string]$DirectivePath,
    [switch]$LexicalOnly
)

$ErrorActionPreference = 'Stop'
$canonicalRoot = 'C:\AbandonWare\demo-1\demo-1\src'

function New-ResolverResult {
    [pscustomobject][ordered]@{
        status = 'HOLD'
        reason = 'verification-unproven'
        canonicalExecutionRoot = $canonicalRoot
        targetRel = $null
        desktopTarget = $null
        withinRoot = $false
        exists = $false
        reparseRisk = $false
    }
}

function Write-ResolverResult {
    param([pscustomobject]$Result)
    $Result | ConvertTo-Json -Compress
}

function Throw-Reason {
    param([string]$Reason)
    throw [System.InvalidOperationException]::new($Reason)
}

function Assert-LexicalSegments {
    param([string]$RelativePath)

    if ([string]::IsNullOrWhiteSpace($RelativePath)) { Throw-Reason 'directive-target-empty' }
    $segments = @($RelativePath.Split('\'))
    $invalidFileNameChars = [IO.Path]::GetInvalidFileNameChars()
    foreach ($segment in $segments) {
        if ([string]::IsNullOrWhiteSpace($segment)) { Throw-Reason 'directive-path-unsupported' }
        if ($segment -eq '..') { Throw-Reason 'directive-path-escape' }
        if ($segment -eq '.') { continue }
        if ($segment.IndexOfAny($invalidFileNameChars) -ge 0) {
            Throw-Reason 'directive-path-unsupported'
        }
        if (-not $segment.Equals($segment.TrimEnd([char[]]' .'), [StringComparison]::Ordinal)) {
            Throw-Reason 'directive-path-unsupported'
        }
        $deviceBase = ($segment -split '\.', 2)[0]
        if ($deviceBase -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$') {
            Throw-Reason 'directive-path-unsupported'
        }
    }
}

function Get-RootRelativePath {
    param(
        [string]$Root,
        [string]$AbsolutePath,
        [string]$OutsideReason
    )

    $rootFull = [IO.Path]::GetFullPath($Root)
    $rootBase = $rootFull.TrimEnd('\')
    $rootPrefixLocal = $rootBase + '\'
    $pathFull = [IO.Path]::GetFullPath($AbsolutePath)
    if ($pathFull.Equals($rootFull, [StringComparison]::OrdinalIgnoreCase) -or
        $pathFull.Equals($rootBase, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Reason 'directive-target-empty'
    }
    if (-not $pathFull.StartsWith($rootPrefixLocal, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Reason $OutsideReason
    }

    $rootUri = [Uri]::new($rootPrefixLocal)
    $pathUri = [Uri]::new($pathFull)
    $relativeUri = $rootUri.MakeRelativeUri($pathUri)
    if ($relativeUri.IsAbsoluteUri) { Throw-Reason $OutsideReason }
    $relative = [Uri]::UnescapeDataString($relativeUri.ToString()).Replace('/', '\').TrimEnd('\')
    if ([string]::IsNullOrWhiteSpace($relative)) { Throw-Reason 'directive-target-empty' }

    $roundTrip = [IO.Path]::GetFullPath([IO.Path]::Combine($rootFull, $relative))
    if (-not $roundTrip.Equals($pathFull, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Reason 'directive-path-unsupported'
    }
    $relative
}

function Resolve-LexicalTarget {
    param([string]$InputPath)

    $rawValue = if ($null -eq $InputPath) { '' } else { $InputPath }
    if ([string]::IsNullOrWhiteSpace($rawValue)) { Throw-Reason 'directive-target-empty' }
    if (-not $rawValue.Equals($rawValue.Trim(), [StringComparison]::Ordinal)) {
        Throw-Reason 'directive-path-unsupported'
    }
    $value = $rawValue.Replace('/', '\')
    if ($value.TrimEnd('\').Equals($canonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Reason 'directive-target-empty'
    }
    if ($value.StartsWith('\\')) { Throw-Reason 'directive-path-unsupported' }

    $relativeInput = $null
    $relativeRoot = $canonicalRoot
    $outsideReason = 'directive-path-escape'
    if ($value -match '^[A-Za-z]:') {
        if ($value -notmatch '^[A-Za-z]:\\') { Throw-Reason 'directive-path-unsupported' }
        $drive = $value.Substring(0, 2)
        if ($drive.Equals('Y:', [StringComparison]::OrdinalIgnoreCase)) {
            $relativeInput = $value.Substring(3)
            $relativeRoot = 'Y:\'
        } elseif ($drive.Equals('C:', [StringComparison]::OrdinalIgnoreCase)) {
            $relativeInput = $value.Substring(3)
            $outsideReason = 'directive-path-not-y-rooted'
        } else {
            Throw-Reason 'directive-path-not-y-rooted'
        }
    } elseif ($value.StartsWith('\')) {
        Throw-Reason 'directive-path-unsupported'
    } else {
        $relativeInput = $value
    }

    if (((($value -notmatch '^[A-Za-z]:') -or
          $value.StartsWith('Y:\', [StringComparison]::OrdinalIgnoreCase)) -and
         $relativeInput -match '^(?:\.(?:\\|$))+$') -or
        ($value.StartsWith($canonicalRoot + '\', [StringComparison]::OrdinalIgnoreCase) -and
         $value.Substring($canonicalRoot.Length + 1) -match '^(?:\.(?:\\|$))+$')) {
        Throw-Reason 'directive-target-empty'
    }
    if ($value.StartsWith('C:\', [StringComparison]::OrdinalIgnoreCase) -and
        $relativeInput -match '^(?:\.(?:\\|$))+$') {
        Throw-Reason 'directive-path-not-y-rooted'
    }

    Assert-LexicalSegments -RelativePath $relativeInput
    if ($value -match '^[A-Za-z]:') {
        $relative = Get-RootRelativePath -Root $relativeRoot -AbsolutePath $value -OutsideReason $outsideReason
    } else {
        $candidateInput = [IO.Path]::GetFullPath([IO.Path]::Combine($canonicalRoot, $relativeInput))
        $relative = Get-RootRelativePath -Root $canonicalRoot -AbsolutePath $candidateInput -OutsideReason 'directive-path-escape'
    }

    $candidate = [IO.Path]::GetFullPath([IO.Path]::Combine($canonicalRoot, $relative))
    $canonicalRelative = Get-RootRelativePath -Root $canonicalRoot -AbsolutePath $candidate -OutsideReason 'directive-path-escape'
    $result = New-ResolverResult
    $result.targetRel = $canonicalRelative
    $result.desktopTarget = $candidate
    $result.withinRoot = $true
    $result
}

function Assert-NoReparseTraversal {
    param([pscustomobject]$Result)

    $Result.exists = [bool](Test-Path -LiteralPath $Result.desktopTarget -PathType Leaf)
    $volumeRoot = [IO.Path]::GetPathRoot($Result.desktopTarget)
    if ([string]::IsNullOrWhiteSpace($volumeRoot)) { Throw-Reason 'reparse-traversal-risk' }
    $components = @($volumeRoot)
    $current = $volumeRoot
    $suffix = $Result.desktopTarget.Substring($volumeRoot.Length)
    foreach ($segment in $suffix.Split('\')) {
        if ([string]::IsNullOrWhiteSpace($segment)) { continue }
        $current = [IO.Path]::Combine($current, $segment)
        $components += $current
    }

    foreach ($component in $components) {
        try {
            $item = Get-Item -LiteralPath $component -Force
        } catch [System.Management.Automation.ItemNotFoundException] {
            $Result.exists = $false
            break
        }
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            $Result.reparseRisk = $true
            Throw-Reason 'reparse-traversal-risk'
        }
    }
}

try {
    $result = Resolve-LexicalTarget -InputPath $DirectivePath

    if ($LexicalOnly) {
        $result.status = 'HOLD'
        $result.reason = 'lexical-only'
        Write-ResolverResult -Result $result
        exit 0
    }

    if (-not (Test-Path -LiteralPath $canonicalRoot -PathType Container)) {
        $result.status = 'HOLD'
        $result.reason = 'c-canonical-unavailable'
        Write-ResolverResult -Result $result
        exit 0
    }

    $gitRootRows = @(& git -C $canonicalRoot rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0 -or $gitRootRows.Count -ne 1) { Throw-Reason 'c-canonical-unavailable' }
    $gitRoot = [IO.Path]::GetFullPath(([string]$gitRootRows[0]).Trim())
    if ($gitRoot.StartsWith('\\') -or -not $gitRoot.Equals($canonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Reason 'c-canonical-unavailable'
    }

    Assert-NoReparseTraversal -Result $result
    if (-not $result.exists) { Throw-Reason 'directive-target-missing' }

    $result.status = 'PASS'
    $result.reason = 'match'
    Write-ResolverResult -Result $result
} catch {
    $reason = $_.Exception.Message
    if ($reason -notin @('directive-target-empty', 'directive-path-not-y-rooted', 'directive-path-escape', 'directive-path-unsupported', 'c-canonical-unavailable', 'directive-target-missing', 'reparse-traversal-risk')) {
        $reason = 'verification-unproven'
    }
    if ($null -eq $result) { $result = New-ResolverResult }
    $result.status = 'HOLD'
    $result.reason = $reason
    Write-ResolverResult -Result $result
}
