[CmdletBinding()]
param(
    [string]$Workspace = "",
    [string]$OutputPath = "",
    [string]$ActiveSourceManifest = "",
    [switch]$FullWorkspaceScan,
    [int]$MaxItems = 5000
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Workspace)) {
    $scriptRoot = $PSScriptRoot
    if ([string]::IsNullOrWhiteSpace($scriptRoot) -and $MyInvocation.MyCommand.Path) {
        $scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
    }
    if ([string]::IsNullOrWhiteSpace($scriptRoot)) {
        $scriptRoot = (Get-Location).Path
    }
    $Workspace = Join-Path $scriptRoot ".."
}
$Workspace = (Resolve-Path -LiteralPath $Workspace).Path

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $Workspace "__reports__/context-purity-decisions.tsv"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath = Join-Path $Workspace $OutputPath
}
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)

if ($MaxItems -lt 1) {
    throw "MaxItems must be greater than zero"
}

$BoundedScanRoots = @(
    "main/java",
    "main/resources",
    "src/test/java",
    "src/test/resources",
    "app/src/main/java_clean",
    "app/src/main/resources",
    ".agents/skills",
    "docs/ai-memory",
    "agent-prompts"
)

# Evidence-only roots are outside the active build graph. Only Java files that
# duplicate an active FQCN are emitted into the bounded decision report.
$InactiveJavaEvidenceRoots = @(
    "app/src/main/java",
    "project/src/main/java",
    "demo-1/src/main/java",
    "lms-core/src/main/java"
)

$SkipPathPattern = "\\(\.git|\.gradle|build|build-[^\\]+|node_modules|\.next|__patch_drop__|BackupsXS|var)\\"
$DecisionColumns = @(
    "source_path",
    "source_set_role",
    "active_weight",
    "generated_score",
    "legacy_score",
    "duplicate_score",
    "delete_score",
    "memory_value",
    "decision",
    "reason"
)

function Resolve-ContextPurityRoot {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root
    )

    $candidate = Join-Path $Workspace $Root
    if (Test-Path -LiteralPath $candidate) {
        return (Resolve-Path -LiteralPath $candidate).Path
    }
    return $null
}

function New-ContextPurityDecision {
    param(
        [Parameter(Mandatory = $true)] [string]$SourcePath,
        [Parameter(Mandatory = $true)] [string]$SourceSetRole,
        [Parameter(Mandatory = $true)] [string]$ActiveWeight,
        [Parameter(Mandatory = $true)] [string]$GeneratedScore,
        [Parameter(Mandatory = $true)] [string]$LegacyScore,
        [Parameter(Mandatory = $true)] [string]$DuplicateScore,
        [Parameter(Mandatory = $true)] [string]$DeleteScore,
        [Parameter(Mandatory = $true)] [string]$MemoryValue,
        [Parameter(Mandatory = $true)] [ValidateSet("KEEP", "DELETE", "QUARANTINE", "EVIDENCE_NEEDED")] [string]$Decision,
        [Parameter(Mandatory = $true)] [string]$Reason
    )

    return [pscustomobject][ordered]@{
        source_path = $SourcePath
        source_set_role = $SourceSetRole
        active_weight = $ActiveWeight
        generated_score = $GeneratedScore
        legacy_score = $LegacyScore
        duplicate_score = $DuplicateScore
        delete_score = $DeleteScore
        memory_value = $MemoryValue
        decision = $Decision
        reason = $Reason
    }
}

function Get-ContextPurityDecision {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RelativePath
    )

    $normalized = $RelativePath.Replace("\", "/")
    $lower = $normalized.ToLowerInvariant()
    $extension = [System.IO.Path]::GetExtension($normalized).ToLowerInvariant()

    if ($lower.StartsWith("agent-prompts/out/")) {
        return New-ContextPurityDecision $RelativePath "generated-build" "0.00" "1.00" "0.00" "0.00" "1.00" "0.00" "DELETE" "generated_artifact"
    }
    if ($lower -eq "gradle/wrapper/gradle-wrapper.jar") {
        return New-ContextPurityDecision $RelativePath "build-wrapper" "1.00" "0.00" "0.00" "0.00" "0.00" "0.00" "KEEP" "build_wrapper_required"
    }
    if (@(".class", ".jar", ".war", ".ear", ".pyc") -contains $extension) {
        return New-ContextPurityDecision $RelativePath "generated-build" "0.00" "1.00" "0.00" "0.00" "1.00" "0.00" "DELETE" "generated_artifact"
    }
    if ($lower.StartsWith("main/java/") -or $lower.StartsWith("main/resources/")) {
        return New-ContextPurityDecision $RelativePath "active-root" "1.00" "0.00" "0.00" "0.00" "0.00" "0.00" "KEEP" "active_sourceset"
    }
    if ($lower.StartsWith("app/src/main/java_clean/") -or $lower.StartsWith("app/src/main/resources/")) {
        return New-ContextPurityDecision $RelativePath "active-app" "1.00" "0.00" "0.00" "0.00" "0.00" "0.00" "KEEP" "active_sourceset"
    }
    if ($lower.StartsWith("src/test/java/") -or $lower.StartsWith("src/test/resources/")) {
        return New-ContextPurityDecision $RelativePath "support-test" "0.80" "0.00" "0.00" "0.00" "0.00" "0.00" "KEEP" "test_source"
    }
    if ($lower -eq "docs/ai-memory/dynamic_rag_orchestration_platform.memory.md") {
        return New-ContextPurityDecision $RelativePath "support-memory" "0.50" "0.00" "0.00" "0.00" "0.00" "1.00" "KEEP" "canonical_memory_doc"
    }
    if ($lower.StartsWith(".agents/skills/") -and $lower.EndsWith("/skill.md")) {
        return New-ContextPurityDecision $RelativePath "support-memory" "0.50" "0.00" "0.00" "0.00" "0.00" "0.90" "KEEP" "canonical_skill"
    }
    if (@(".bak", ".backup", ".orig", ".rej", ".patch") -contains $extension) {
        return New-ContextPurityDecision $RelativePath "legacy-artifact" "0.00" "0.00" "0.80" "0.00" "0.20" "0.00" "QUARANTINE" "uncertain_legacy_artifact"
    }
    return New-ContextPurityDecision $RelativePath "inactive-or-unknown" "0.10" "0.00" "0.70" "0.00" "0.31" "0.00" "EVIDENCE_NEEDED" "needs_owner_review"
}

function Get-ContextPurityJavaEvidence {
    param([object[]]$Records)

    $javaEvidence = New-Object System.Collections.Generic.List[object]
    foreach ($record in $Records) {
        if ([System.IO.Path]::GetExtension($record.source_path).ToLowerInvariant() -ne ".java") {
            continue
        }
        try {
            $sourceBytes = [System.IO.File]::ReadAllBytes($record.full_path)
            $utf8Offset = 0
            if ($sourceBytes.Length -ge 3 `
                    -and $sourceBytes[0] -eq 0xEF `
                    -and $sourceBytes[1] -eq 0xBB `
                    -and $sourceBytes[2] -eq 0xBF) {
                $utf8Offset = 3
            }
            $source = [System.Text.Encoding]::UTF8.GetString(
                $sourceBytes,
                $utf8Offset,
                $sourceBytes.Length - $utf8Offset)
            $packageMatch = [regex]::Match(
                $source,
                "(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;")
            $className = [System.IO.Path]::GetFileNameWithoutExtension($record.source_path)
            if ($className -eq "package-info" -or $className -eq "module-info") {
                continue
            }
            $fqcn = if ($packageMatch.Success) {
                $packageMatch.Groups[1].Value + "." + $className
            } else {
                $className
            }
            $authorityRank = 0
            if ($record.normalized_path.StartsWith("main/java/")) {
                $authorityRank = 300
            } elseif ($record.normalized_path.StartsWith("app/src/main/java_clean/")) {
                $authorityRank = 200
            }
            $sha256 = [System.Security.Cryptography.SHA256]::Create()
            try {
                $sourceHash = [System.BitConverter]::ToString($sha256.ComputeHash($sourceBytes)).Replace("-", "")
            } finally {
                $sha256.Dispose()
            }
            $javaEvidence.Add([pscustomobject]@{
                fqcn = $fqcn
                sha256 = $sourceHash
                authority_rank = $authorityRank
                record = $record
            }) | Out-Null
        } catch {
            throw "evidence_needed: context purity Java duplicate evidence incomplete / path=$($record.source_path)"
        }
    }

    return $javaEvidence
}

function Get-ContextPurityReportItems {
    [CmdletBinding()]
    param(
        [string]$Root = $Workspace,
        [switch]$FullWorkspaceScan,
        [int]$Limit = $MaxItems
    )

    $scanRoots = @()
    if ($FullWorkspaceScan) {
        $scanRoots = @([pscustomobject]@{
            path = (Resolve-Path -LiteralPath $Root).Path
            evidence_only = $false
        })
    } else {
        foreach ($bounded in $BoundedScanRoots) {
            $resolved = Resolve-ContextPurityRoot -Root $bounded
            if ($resolved) {
                $scanRoots += [pscustomobject]@{
                    path = $resolved
                    evidence_only = $false
                }
            }
        }
        foreach ($inactive in $InactiveJavaEvidenceRoots) {
            $resolved = Resolve-ContextPurityRoot -Root $inactive
            if ($resolved) {
                $scanRoots += [pscustomobject]@{
                    path = $resolved
                    evidence_only = $true
                }
            }
        }
    }

    $seen = @{}
    $decisions = [ordered]@{}
    $fileRecords = New-Object System.Collections.Generic.List[object]
    foreach ($scanSpec in $scanRoots) {
        $scanRoot = $scanSpec.path
        $scanErrors = @()
        $files = Get-ChildItem -LiteralPath $scanRoot -Force -Recurse -File -ErrorAction SilentlyContinue -ErrorVariable scanErrors |
            Where-Object { $_.FullName -notmatch $SkipPathPattern }
        if (@($scanErrors).Count -gt 0) {
            $rootLabel = $scanRoot.Substring($Workspace.Length).TrimStart("\", "/")
            if ([string]::IsNullOrWhiteSpace($rootLabel)) {
                $rootLabel = "."
            }
            throw "evidence_needed: context purity scan incomplete / root=$rootLabel errorCount=$(@($scanErrors).Count)"
        }
        foreach ($file in $files) {
            $relative = $file.FullName.Substring($Workspace.Length).TrimStart("\", "/")
            $relative = $relative.Replace("/", "\")
            $key = $relative.ToLowerInvariant()
            if (-not $seen.ContainsKey($key)) {
                $seen[$key] = $true
                $normalized = $relative.Replace("\", "/").ToLowerInvariant()
                $isInactiveJava = $normalized.StartsWith("app/src/main/java/") `
                    -or $normalized.StartsWith("project/src/main/java/") `
                    -or $normalized.StartsWith("demo-1/src/main/java/") `
                    -or $normalized.StartsWith("lms-core/src/main/java/")
                $fileRecords.Add([pscustomobject]@{
                    full_path = $file.FullName
                    source_path = $relative
                    key = $key
                    normalized_path = $normalized
                    evidence_only = [bool]$scanSpec.evidence_only
                    inactive_java = $isInactiveJava
                }) | Out-Null
                if (-not [bool]$scanSpec.evidence_only) {
                    $decisions[$key] = Get-ContextPurityDecision -RelativePath $relative
                }
            }
        }
    }

    $javaEvidence = @(Get-ContextPurityJavaEvidence -Records $fileRecords)

    foreach ($group in @($javaEvidence | Group-Object fqcn -CaseSensitive | Where-Object { $_.Count -gt 1 })) {
        $authorities = @($group.Group |
            Where-Object { $_.authority_rank -ge 200 } |
            Sort-Object @{ Expression = "authority_rank"; Descending = $true },
                @{ Expression = { $_.record.source_path }; Descending = $false })
        if ($authorities.Count -eq 0) {
            continue
        }
        $canonical = $authorities[0]
        foreach ($candidate in $group.Group) {
            if ($candidate.record.key -eq $canonical.record.key) {
                continue
            }

            $matchesActiveAuthority = @($authorities |
                Where-Object { $_.sha256 -eq $candidate.sha256 }).Count -gt 0
            if ($candidate.record.inactive_java -and $matchesActiveAuthority) {
                $decisions[$candidate.record.key] = New-ContextPurityDecision `
                    $candidate.record.source_path "inactive-duplicate" "0.00" "0.00" "0.80" "1.00" "0.20" "0.00" `
                    "QUARANTINE" "exact_duplicate_non_active_excluded"
                continue
            }

            if ($candidate.sha256 -eq $canonical.sha256) {
                continue
            }

            if ($candidate.record.normalized_path.StartsWith("app/src/main/java_clean/") `
                    -and $canonical.record.normalized_path.StartsWith("main/java/")) {
                $decisions[$candidate.record.key] = New-ContextPurityDecision `
                    $candidate.record.source_path "active-app-shadow" "0.50" "0.00" "0.70" "1.00" "0.20" "0.00" `
                    "EVIDENCE_NEEDED" "fqcn_content_divergence_evidence_needed_verify_with_app_duplicate_excludes"
            } elseif ($candidate.record.inactive_java) {
                $decisions[$candidate.record.key] = New-ContextPurityDecision `
                    $candidate.record.source_path "inactive-divergent" "0.00" "0.00" "0.90" "1.00" "0.20" "0.00" `
                    "EVIDENCE_NEEDED" "fqcn_content_divergence_evidence_needed_verify_with_checkSourceSetHygiene"
            } elseif ($candidate.authority_rank -ge 200) {
                $decisions[$candidate.record.key] = New-ContextPurityDecision `
                    $candidate.record.source_path "active-divergent" "0.50" "0.00" "0.70" "1.00" "0.20" "0.00" `
                    "EVIDENCE_NEEDED" "fqcn_content_divergence_evidence_needed_verify_with_checkSourceSetHygiene"
            }
        }
    }

    $ordered = @($decisions.Values | Sort-Object source_path)
    if ($ordered.Count -gt $Limit) {
        throw "Context purity item limit exceeded: found=$($ordered.Count) limit=$Limit"
    }
    return $ordered
}

function ConvertTo-ContextPurityTsvCell {
    param([AllowNull()] [object]$Value)

    if ($null -eq $Value) {
        return ""
    }
    return ([string]$Value) -replace "[`t`r`n]", " "
}

function Write-ContextPurityDecisionsAtomically {
    param(
        [Parameter(Mandatory = $true)] [object[]]$Rows,
        [Parameter(Mandatory = $true)] [string]$Path
    )

    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add(($DecisionColumns -join "`t")) | Out-Null
    foreach ($row in $Rows) {
        $cells = foreach ($column in $DecisionColumns) {
            ConvertTo-ContextPurityTsvCell -Value $row.$column
        }
        $lines.Add(($cells -join "`t")) | Out-Null
    }

    $nonce = [Guid]::NewGuid().ToString("N")
    $tempPath = Join-Path $parent ("." + [System.IO.Path]::GetFileName($Path) + ".tmp-" + $nonce)
    $backupPath = Join-Path $parent ("." + [System.IO.Path]::GetFileName($Path) + ".backup-" + $nonce)
    try {
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllLines($tempPath, $lines, $utf8NoBom)
        if (Test-Path -LiteralPath $Path) {
            [System.IO.File]::Replace($tempPath, $Path, $backupPath)
        } else {
            [System.IO.File]::Move($tempPath, $Path)
        }
    } finally {
        if (Test-Path -LiteralPath $tempPath) {
            Remove-Item -LiteralPath $tempPath -Force
        }
        if (Test-Path -LiteralPath $backupPath) {
            Remove-Item -LiteralPath $backupPath -Force
        }
    }
}

function Assert-ContextPurityRetainedSources {
    param([string]$ManifestPath)

    try {
        $manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw "SOURCE_HYGIENE_MANIFEST: unreadable retained-source manifest"
    }
    $owners = @($manifest.PSObject.Properties)
    if ($owners.Count -lt 1 -or $owners.Count -gt 2) {
        throw "SOURCE_HYGIENE_MANIFEST: expected root/app compiler owners"
    }
    $retainedCount = 0
    foreach ($owner in $owners) {
        $prefix = switch ($owner.Name) {
            "root" { "main/java/" }
            "app" { "app/src/main/java_clean/" }
            default { throw "SOURCE_HYGIENE_MANIFEST: unknown compiler owner" }
        }
        $expectedRoot = [System.IO.Path]::GetFullPath((Join-Path $Workspace $prefix)).TrimEnd('\', '/')
        $records = New-Object System.Collections.Generic.List[object]
        $seen = @{}
        foreach ($relative in @($owner.Value)) {
            $retainedCount++
            if ($retainedCount -gt $MaxItems) { throw "SOURCE_HYGIENE_LIMIT: retained source limit exceeded" }
            if ($relative -isnot [string] -or [System.IO.Path]::IsPathRooted($relative)) {
                throw "SOURCE_HYGIENE_PATH: expected workspace-relative Java source"
            }
            $full = [System.IO.Path]::GetFullPath((Join-Path $Workspace $relative))
            if (-not $full.StartsWith($expectedRoot + [System.IO.Path]::DirectorySeparatorChar,
                    [System.StringComparison]::OrdinalIgnoreCase) -or
                    [System.IO.Path]::GetExtension($full) -ne '.java') {
                throw "SOURCE_HYGIENE_PATH: retained source outside declared owner"
            }
            if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
                throw "SOURCE_HYGIENE_PATH: retained source missing"
            }
            $entry = Get-Item -LiteralPath $full -Force
            while ($null -ne $entry) {
                if (($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                    throw "SOURCE_HYGIENE_PATH: reparse traversal unsupported"
                }
                if ($entry.FullName.TrimEnd('\', '/') -eq $Workspace.TrimEnd('\', '/')) { break }
                $entry = if ($entry -is [System.IO.DirectoryInfo]) { $entry.Parent } else { $entry.Directory }
            }
            if ($seen.ContainsKey($full)) { throw "SOURCE_HYGIENE_MANIFEST: repeated source path" }
            $seen[$full] = $true
            $records.Add([pscustomobject]@{
                full_path = $full
                source_path = $relative
                normalized_path = $relative.Replace('\', '/').ToLowerInvariant()
            }) | Out-Null
        }
        $evidence = @(Get-ContextPurityJavaEvidence -Records $records)
        $duplicates = @($evidence | Group-Object fqcn -CaseSensitive | Where-Object { $_.Count -gt 1 })
        if ($duplicates.Count -gt 0) {
            throw "SOURCE_HYGIENE_DUPLICATE: owner=$($owner.Name) groups=$($duplicates.Count)"
        }
    }
    Write-Output "[AWX][sourceset] owners=$($owners.Count) retainedFiles=$retainedCount duplicateGroups=0"
}

if (-not [string]::IsNullOrWhiteSpace($ActiveSourceManifest)) {
    Assert-ContextPurityRetainedSources -ManifestPath $ActiveSourceManifest
    return
}

$items = @(Get-ContextPurityReportItems -Root $Workspace -FullWorkspaceScan:$FullWorkspaceScan -Limit $MaxItems)
Write-ContextPurityDecisionsAtomically -Rows $items -Path $OutputPath
Write-Output "[AWX][context-purity] mode=$(if ($FullWorkspaceScan) { 'full' } else { 'bounded' }) rows=$($items.Count) output=$OutputPath"
