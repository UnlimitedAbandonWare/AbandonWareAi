param(
    [string]$Root = ".",
    [string]$OutputPath = "",
    [switch]$Json,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

if ($Help) {
    @'
[AWX][safe-delete] usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\safe_delete_path_presence.ps1 -Root . -OutputPath var\codex-smoke\safe-delete-path-presence-current.json -Json

Purpose:
  Read-only presence audit for 9h safe-delete directive paths. It never deletes files,
  never emits deletion commands, and never reads secret candidate contents.
'@ | Write-Host
    exit 0
}

function Get-Sha256Text {
    param([AllowEmptyString()][string]$Text)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        return (($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $sha.Dispose()
    }
}

function Get-CandidateKind {
    param([Parameter(Mandatory = $true)][string]$Path)

    $normalized = $Path -replace '/', '\'
    $lower = $normalized.ToLowerInvariant()
    if ($lower -in @('apikey.txt', 'apikey.ps1', '.env', '.env.local', '.env.development', '.env.production')) {
        return 'secret-path'
    }
    if ($lower -eq 'backupsxs\index.jsonl') {
        return 'archive-index'
    }
    if ($lower -match '(^|\\)(backup|backup[0-9]+|bakcup[0-9]+|backupxs)(\\|$)' -or $lower -match 'backup|bakcup') {
        return 'backup'
    }
    if ($lower -in @('__archive__', 'legacy-quarantine')) {
        return 'inactive-archive'
    }
    if ($lower -in @('guard', 'service', 'infra', 'addons', 'com')) {
        return 'orphan-root-package'
    }
    if ($lower -eq '__reports__') {
        return 'report-output'
    }
    if ($lower -match '^(src2|src3|srcmain|srcmain4)$') {
        return 'source-mirror'
    }
    return 'non-source-candidate'
}

function Get-Classification {
    param(
        [Parameter(Mandatory = $true)][string]$Kind,
        [Parameter(Mandatory = $true)][bool]$Exists
    )

    if (-not $Exists) { return 'ABSENT' }
    switch ($Kind) {
        'secret-path' { return 'HOLD_SECRET_PATH' }
        'archive-index' { return 'HOLD_ARCHIVE_INDEX' }
        'backup' { return 'REVIEW_BACKUP_DIR' }
        'inactive-archive' { return 'REVIEW_INACTIVE_ARCHIVE_DIR' }
        'orphan-root-package' { return 'REVIEW_ORPHAN_ROOT_PACKAGE' }
        'report-output' { return 'REVIEW_REPORT_OUTPUT' }
        'source-mirror' { return 'REVIEW_SOURCE_MIRROR' }
        default { return 'REVIEW_NON_SOURCE_CANDIDATE' }
    }
}

function Get-ItemSummary {
    param([Parameter(Mandatory = $true)][string]$FullPath)

    if (-not (Test-Path -LiteralPath $FullPath)) {
        return [ordered]@{
            exists = $false
            itemType = 'missing'
            lengthBytes = 0
            directChildCount = 0
        }
    }

    $item = Get-Item -LiteralPath $FullPath -Force
    if ($item.PSIsContainer) {
        $childCount = 0
        try {
            $childCount = @(
                Get-ChildItem -LiteralPath $FullPath -Force -ErrorAction Stop |
                    Select-Object -First 1001
            ).Count
        } catch {
            $childCount = -1
        }
        return [ordered]@{
            exists = $true
            itemType = 'directory'
            lengthBytes = 0
            directChildCount = $childCount
        }
    }

    return [ordered]@{
        exists = $true
        itemType = 'file'
        lengthBytes = [int64]$item.Length
        directChildCount = 0
    }
}

$resolvedRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Root).Path)

$candidatePaths = @(
    'BackUp',
    '..\BackUp',
    '..\demo-1\BackUp',
    'src2',
    'src3',
    'srcmain',
    'srcmain4',
    '__archive__',
    'legacy-quarantine',
    'guard',
    'service',
    'infra',
    'addons',
    'com',
    '__reports__',
    'BackupsXS\index.jsonl',
    'apikey.txt',
    'apikey.ps1',
    '.env',
    '.env.local'
)

$candidates = [System.Collections.Generic.List[object]]::new()
$candidateFullPaths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($relativePath in $candidatePaths) {
    $fullPath = [IO.Path]::GetFullPath((Join-Path $resolvedRoot $relativePath))
    $candidateFullPaths.Add($fullPath) | Out-Null
    $summary = Get-ItemSummary -FullPath $fullPath
    $kind = Get-CandidateKind -Path $relativePath
    $classification = Get-Classification -Kind $kind -Exists ([bool]$summary.exists)
    $candidates.Add([ordered]@{
        path = $relativePath
        pathHash = Get-Sha256Text -Text $fullPath
        pathLength = $fullPath.Length
        exists = [bool]$summary.exists
        itemType = [string]$summary.itemType
        lengthBytes = [int64]$summary.lengthBytes
        directChildCount = [int]$summary.directChildCount
        kind = $kind
        classification = $classification
        deleteAllowed = $false
    }) | Out-Null
}

$present = @($candidates | Where-Object { $_.exists -eq $true })
$absent = @($candidates | Where-Object { $_.exists -ne $true })
$secretPresent = @($candidates | Where-Object { $_.exists -eq $true -and $_.kind -eq 'secret-path' })
$review = @($candidates | Where-Object { $_.classification -like 'REVIEW_*' })

$result = [ordered]@{
    schemaVersion = 'awx.safe_delete_path_presence.v1'
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    decision = 'safe_delete_path_presence'
    rootHash = Get-Sha256Text -Text $resolvedRoot
    rootLength = $resolvedRoot.Length
    mutationAllowed = $false
    deleteCommandEmitted = $false
    candidateCount = $candidates.Count
    presentCount = $present.Count
    absentCount = $absent.Count
    reviewCandidateCount = $review.Count
    secretPathPresentCount = $secretPresent.Count
    candidates = @($candidates)
    nextActions = @(
        'review_present_candidates_manually',
        'run_zombie_candidate_audit_for_java_candidates',
        'do_not_delete_secret_paths',
        'require_git_or_external_rollback_before_destructive_cleanup'
    )
    evidence_needed = if ($review.Count -gt 0) { 'manual review and rollback proof required before any deletion' } else { '' }
}

$jsonText = $result | ConvertTo-Json -Depth 10
$secretPattern = 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}'
$rawSecretPatternHits = ([regex]::Matches($jsonText, $secretPattern)).Count
$result['rawSecretPatternHits'] = $rawSecretPatternHits
$jsonText = $result | ConvertTo-Json -Depth 10

if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $outputPathIsRooted = [IO.Path]::IsPathRooted($OutputPath)
    $outputInputPath = if ($outputPathIsRooted) {
        $OutputPath
    } else {
        Join-Path $resolvedRoot $OutputPath
    }
    $resolvedOutputPath = [IO.Path]::GetFullPath($outputInputPath)
    if (-not $outputPathIsRooted) {
        $rootPrefix = $resolvedRoot.TrimEnd([char[]]'\/') + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedOutputPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw '[AWX][safe-delete] output_path_outside_root'
        }
    }
    if ($candidateFullPaths.Contains($resolvedOutputPath)) {
        throw '[AWX][safe-delete] output_path_conflicts_with_audited_candidate'
    }
    if (Test-Path -LiteralPath $resolvedOutputPath) {
        $outputItem = Get-Item -LiteralPath $resolvedOutputPath -Force
        if (-not [string]::IsNullOrWhiteSpace([string]$outputItem.LinkType)) {
            throw '[AWX][safe-delete] output_path_link_not_supported'
        }
    }
    $outputParent = Split-Path -Parent $resolvedOutputPath
    $outputAncestor = $outputParent
    while (-not [string]::IsNullOrWhiteSpace($outputAncestor)) {
        if (Test-Path -LiteralPath $outputAncestor) {
            $ancestorItem = Get-Item -LiteralPath $outputAncestor -Force
            if (($ancestorItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw '[AWX][safe-delete] output_path_reparse_not_supported'
            }
        }
        $nextAncestor = Split-Path -Parent $outputAncestor
        if ([string]::IsNullOrWhiteSpace($nextAncestor) -or $nextAncestor -eq $outputAncestor) {
            break
        }
        $outputAncestor = $nextAncestor
    }
    if ($outputParent) {
        New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
    }
    [IO.File]::WriteAllText($resolvedOutputPath, $jsonText, [Text.UTF8Encoding]::new($false))
}

if ($Json) {
    $jsonText | Write-Output
} else {
    Write-Host "[AWX][safe-delete] decision=$($result.decision) present=$($result.presentCount) absent=$($result.absentCount) secretPathPresent=$($result.secretPathPresentCount) mutationAllowed=false"
}

exit 0
