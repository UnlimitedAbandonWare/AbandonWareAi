[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Prepare", "Verify", "Complete", "Abort", "Heartbeat")]
    [string]$Mode,
    [Parameter(Mandatory = $true)]
    [string]$Root,
    [Parameter(Mandatory = $true)]
    [string]$RunId,
    [Parameter(Mandatory = $true)]
    [string]$OwnerId,
    [int]$OwnerProcessId = 0,
    [string[]]$TargetFiles = @(),
    [string[]]$BoundaryEvidenceFiles = @(),
    [string[]]$WatchRoots = @(),
    [ValidateRange(1, 540)]
    [int]$TtlMinutes = 180,
    [switch]$AllowDeletion,
    [switch]$AllowPendingPatchQueue,
    [string]$VerificationEvidenceFile = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$script:ProductionMacSrcRoot = "\\desktop-m5nov6k\MacSrc"
$script:AccessPolicy = [ordered]@{
    externalReadAccess = "unrestricted"
    externalToolAccess = "unrestricted"
    applicationSourceWriteRootOnly = $true
    oneDriveSourceWriteDefault = $false
    externalNonSourceWrite = "explicit-user-selection"
}
$script:SecretPattern = '(?i)(sk-[a-z0-9_-]{20,}|gsk_[a-z0-9_-]{20,}|AIza[a-z0-9_-]{20,}|pcsk_[a-z0-9_-]{20,}|sb_(?:secret|publishable)_[a-z0-9_-]{10,}|sbp_[a-z0-9_-]{10,}|-----BEGIN (?:RSA |EC |OPENSSH |PRIVATE )?PRIVATE KEY-----|authorization\s*[:=]\s*bearer\s+[a-z0-9._-]{16,}|(?:client[_-]?secret|api[_-]?key|owner[_-]?token)\s*[:=]\s*([''"])[a-z0-9_./+=-]{16,}\1)'

function Get-Sha256Bytes {
    param([byte[]]$Bytes)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace("-", "").ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-Sha256Text {
    param([string]$Value)
    return Get-Sha256Bytes -Bytes ([Text.Encoding]::UTF8.GetBytes($Value))
}

function Get-FileSha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Resolve-CanonicalPath {
    param([string]$Path)
    $resolved = Resolve-Path -LiteralPath $Path
    $providerPath = [string]$resolved.ProviderPath
    if ([string]::IsNullOrWhiteSpace($providerPath)) { $providerPath = [string]$resolved.Path }
    if ($providerPath -match "^([A-Za-z]):\\") {
        $drive = Get-PSDrive -Name $Matches[1] -ErrorAction SilentlyContinue
        if ($drive -and -not [string]::IsNullOrWhiteSpace([string]$drive.DisplayRoot)) {
            $driveRoot = [IO.Path]::GetPathRoot($providerPath)
            $suffix = $providerPath.Substring($driveRoot.Length)
            $providerPath = if ([string]::IsNullOrWhiteSpace($suffix)) { [string]$drive.DisplayRoot } else { Join-Path ([string]$drive.DisplayRoot) $suffix }
        }
    }
    return $providerPath.TrimEnd([char[]]@("\", "/"))
}

function Test-PathInsideRoot {
    param([string]$Candidate, [string]$CanonicalRoot)
    $prefix = $CanonicalRoot.TrimEnd([char[]]@("\", "/")) + "\"
    return $Candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
}

function Test-ReparseChain {
    param([string]$Candidate, [string]$CanonicalRoot)
    $cursor = if (Test-Path -LiteralPath $Candidate) { $Candidate } else { Split-Path -Parent $Candidate }
    while (-not [string]::IsNullOrWhiteSpace($cursor)) {
        if ($cursor.Equals($CanonicalRoot, [StringComparison]::OrdinalIgnoreCase)) { break }
        if (-not (Test-PathInsideRoot -Candidate $cursor -CanonicalRoot $CanonicalRoot)) { throw "target-outside-macsrc" }
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "reparse-path-risk" }
        }
        $next = Split-Path -Parent $cursor
        if ($next -eq $cursor) { break }
        $cursor = $next
    }
}

function Resolve-RelativeMember {
    param([string]$CanonicalRoot, [string]$RelativePath, [ValidateSet("Any", "File", "Directory")][string]$Kind = "Any")
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath) -or $RelativePath.Contains(":")) { throw "target-outside-macsrc" }
    $candidate = [IO.Path]::GetFullPath((Join-Path $CanonicalRoot $RelativePath))
    if (-not (Test-PathInsideRoot -Candidate $candidate -CanonicalRoot $CanonicalRoot)) { throw "target-outside-macsrc" }
    Test-ReparseChain -Candidate $candidate -CanonicalRoot $CanonicalRoot
    if ($Kind -eq "File" -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "boundary-evidence-invalid" }
    if ($Kind -eq "Directory" -and -not (Test-Path -LiteralPath $candidate -PathType Container)) { throw "watch-root-invalid" }
    return [pscustomobject]@{
        relativePath = $candidate.Substring($CanonicalRoot.Length).TrimStart([char[]]@("\", "/")).Replace("/", "\")
        fullPath = $candidate
    }
}

function Test-SecretLikeText {
    param([string]$Text)
    return [bool]($Text -match $script:SecretPattern)
}

function Get-LockedFileScan {
    param([string]$Path, [switch]$CaptureBytes)
    $stream = $null
    $sha = [Security.Cryptography.SHA256]::Create()
    $memory = if ($CaptureBytes) { [IO.MemoryStream]::new() } else { $null }
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        $buffer = New-Object byte[] 65536
        $tail = ""
        $secretHit = $false
        $total = 0L
        while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            [void]$sha.TransformBlock($buffer, 0, $read, $buffer, 0)
            if ($CaptureBytes) {
                if (($total + $read) -gt 2097152) { throw "verification-evidence-too-large" }
                $memory.Write($buffer, 0, $read)
            }
            $ascii = [Text.Encoding]::ASCII.GetString($buffer, 0, $read).Replace("`0", "")
            $scanText = $tail + $ascii
            if ($scanText -match $script:SecretPattern) { $secretHit = $true }
            $tail = if ($scanText.Length -gt 256) { $scanText.Substring($scanText.Length - 256) } else { $scanText }
            $total += $read
        }
        [void]$sha.TransformFinalBlock((New-Object byte[] 0), 0, 0)
        return [pscustomobject]@{
            sha256 = ([BitConverter]::ToString($sha.Hash)).Replace("-", "").ToLowerInvariant()
            secretHit = $secretHit
            byteCount = $total
            bytes = if ($CaptureBytes) { $memory.ToArray() } else { $null }
        }
    } catch {
        if ($_.Exception.Message -eq "verification-evidence-too-large") { throw }
        throw "secret-scan-unverified"
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
        if ($null -ne $memory) { $memory.Dispose() }
        $sha.Dispose()
    }
}

function Write-JsonAtomic {
    param([string]$Path, [object]$Value)
    if ($script:CanonicalRoot) { Test-ReparseChain -Candidate $Path -CanonicalRoot $script:CanonicalRoot }
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $tempPath = Join-Path $parent ((Split-Path -Leaf $Path) + ".tmp-" + [guid]::NewGuid().ToString("N"))
    try {
        [IO.File]::WriteAllText($tempPath, (($Value | ConvertTo-Json -Depth 20) + "`n"), [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $tempPath -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $tempPath) { Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue }
    }
}

function Write-GuardResult {
    param([object]$Value, [int]$ExitCode = 0)
    Write-Output ($Value | ConvertTo-Json -Depth 14 -Compress)
    exit $ExitCode
}

function Write-GuardFailure {
    param([string]$Classification, [int]$ExitCode = 2, [object]$Extra = $null)
    $row = [ordered]@{
        schemaVersion = "awx.macsrc_smb_patch_guard.result.v2"
        mode = $Mode
        runId = $RunId
        verdict = "HOLD"
        authorized = $false
        failureClassification = $Classification
        accessPolicy = $script:AccessPolicy
        globalSafeDirectoryMutation = $false
    }
    if ($null -ne $Extra) { foreach ($property in $Extra.PSObject.Properties) { $row[$property.Name] = $property.Value } }
    Write-GuardResult -Value $row -ExitCode $ExitCode
}

function Get-GitEvidence {
    param([string]$CanonicalRoot, [string[]]$RelativeTargets)
    $result = [ordered]@{ mode = "filesystem-cas"; branch = "unavailable"; targetDirtyCount = $null; globalSafeDirectoryMutation = $false }
    try {
        $rootText = @(& git --no-optional-locks -C $CanonicalRoot rev-parse --show-toplevel 2>$null)
        if ($LASTEXITCODE -ne 0 -or $rootText.Count -eq 0) { return [pscustomobject]$result }
        $reportedRoot = Resolve-CanonicalPath -Path ([string]$rootText[0])
        if (-not $reportedRoot.Equals($CanonicalRoot, [StringComparison]::OrdinalIgnoreCase)) { return [pscustomobject]$result }
        $branchText = @(& git --no-optional-locks -C $CanonicalRoot branch --show-current 2>$null)
        $statusArgs = @('--no-optional-locks', "-C", $CanonicalRoot, "status", "--porcelain=v1", "--") + $RelativeTargets
        $statusText = @(& git @statusArgs 2>$null)
        $result.mode = "native"
        if ($branchText.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace([string]$branchText[0])) { $result.branch = [string]$branchText[0] }
        $result.targetDirtyCount = $statusText.Count
    } catch { }
    return [pscustomobject]$result
}

function Get-SessionPaths {
    param([string]$CanonicalRoot)
    $sessionDir = Join-Path $CanonicalRoot ("data\agent-handoff\macsrc-smb-direct\" + $RunId)
    $lockRoot = Join-Path $CanonicalRoot "__patch_drop__\source-edit-locks"
    $lockDir = Join-Path $lockRoot ("macsrc-" + $RunId + ".lock")
    return [pscustomobject]@{
        sessionDir = $sessionDir
        sessionPath = Join-Path $sessionDir "session.json"
        readyPath = Join-Path $sessionDir "ready.json"
        commitIntentPath = Join-Path $sessionDir "commit-intent.json"
        completionPath = Join-Path $sessionDir "completion.json"
        abortPath = Join-Path $sessionDir "abort.json"
        failurePath = Join-Path $sessionDir "last-failure.json"
        transitionLockPath = Join-Path $sessionDir ".transition.lock"
        topic = "macsrc-" + $RunId
        lockRoot = $lockRoot
        lockDir = $lockDir
        leasePath = Join-Path $lockDir "lease.json"
        patchDropRoot = Join-Path $CanonicalRoot "__patch_drop__"
        promotionLockPath = Join-Path $CanonicalRoot "__patch_drop__\.promotion.lock"
    }
}

function Open-ExclusiveFile {
    param([string]$Path, [string]$FailureClass)
    if ($script:CanonicalRoot) { Test-ReparseChain -Candidate $Path -CanonicalRoot $script:CanonicalRoot }
    if ($FailureClass -eq 'promotion-lock-busy') { return Open-AwxCoordinationHandle -Path $Path }
    try { return [IO.File]::Open($Path, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch { throw $FailureClass }
}

function Get-PendingPatchInventory {
    param([object]$Paths)
    $rows = @()
    foreach ($file in @(Get-ChildItem -LiteralPath $Paths.patchDropRoot -File -Force -Filter "*.patch" -ErrorAction Stop | Sort-Object Name)) {
        $touched = @{}
        $sectionCount = 0
        $parsedSectionCount = 0
        foreach ($line in [IO.File]::ReadLines($file.FullName)) {
            if ($line.StartsWith("diff --git ", [StringComparison]::Ordinal)) {
                $sectionCount++
                if ($line -notmatch '^diff --git a/(\S+) b/(\S+)$') { throw "patch-drop-path-inventory-unverified" }
                foreach ($candidate in @([string]$Matches[1], [string]$Matches[2])) {
                    $relative = $candidate.Replace("/", "\")
                    if ([IO.Path]::IsPathRooted($relative) -or $relative.Contains(":") -or $relative.Split("\") -contains "..") { throw "patch-drop-path-inventory-unverified" }
                    $touched[$relative.ToLowerInvariant()] = $relative
                }
                $parsedSectionCount++
                continue
            }
            if ($line -notmatch '^(?:\+\+\+|---)\s+(?:a|b)/([^\t]+)') { continue }
            $relative = ([string]$Matches[1]).Replace("/", "\")
            if ([IO.Path]::IsPathRooted($relative) -or $relative.Contains(":") -or $relative.Split("\") -contains "..") { throw "patch-drop-path-inventory-unverified" }
            $touched[$relative.ToLowerInvariant()] = $relative
        }
        if ($sectionCount -eq 0 -or $parsedSectionCount -ne $sectionCount -or $touched.Count -eq 0) { throw "patch-drop-path-inventory-unverified" }
        $rows += [ordered]@{ nameHash = Get-Sha256Text -Value $file.Name.ToLowerInvariant(); contentSha256 = Get-FileSha256 -Path $file.FullName; touchedPaths = @($touched.Values | Sort-Object) }
    }
    $lines = @($rows | ForEach-Object { [string]$_['nameHash'] + "|" + [string]$_['contentSha256'] })
    return [pscustomobject]@{ count = $rows.Count; sha256 = Get-Sha256Text -Value ($lines -join "`n"); rows = @($rows) }
}

function Test-ExcludedWatchPath {
    param([string]$RelativePath)
    $normalized = $RelativePath.Replace("/", "\").ToLowerInvariant()
    return [bool]($normalized -match "(^|\\)(\.git|\.gradle|node_modules|build|build-[^\\]*)(\\|$)" -or
        $normalized.StartsWith("data\agent-handoff\macsrc-smb-direct\") -or
        $normalized.StartsWith("__patch_drop__\source-edit-locks\"))
}

function Get-WatchManifest {
    param([string]$CanonicalRoot, [object[]]$WatchRows)
    $map = @{}
    foreach ($watch in $WatchRows) {
        foreach ($item in @(Get-ChildItem -LiteralPath ([string]$watch.fullPath) -Recurse -Force -ErrorAction Stop)) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "reparse-path-risk" }
            if ($item.PSIsContainer) { continue }
            $relative = $item.FullName.Substring($CanonicalRoot.Length).TrimStart([char[]]@("\", "/")).Replace("/", "\")
            if (Test-ExcludedWatchPath -RelativePath $relative) { continue }
            Test-ReparseChain -Candidate $item.FullName -CanonicalRoot $CanonicalRoot
            $map[$relative.ToLowerInvariant()] = [ordered]@{ relativePath = $relative; sha256 = Get-FileSha256 -Path $item.FullName }
        }
    }
    return @($map.Values | Sort-Object { [string]$_['relativePath'] })
}

function Get-WatchDelta {
    param([object[]]$Before, [object[]]$After, [string[]]$DeclaredTargets)
    $beforeMap = @{}; foreach ($row in $Before) { $beforeMap[([string]$row.relativePath).ToLowerInvariant()] = [string]$row.sha256 }
    $afterMap = @{}; foreach ($row in $After) { $afterMap[([string]$row.relativePath).ToLowerInvariant()] = [string]$row.sha256 }
    $declared = @{}; foreach ($relative in $DeclaredTargets) { $declared[([string]$relative).ToLowerInvariant()] = $true }
    $keys = @($beforeMap.Keys + $afterMap.Keys | Sort-Object -Unique)
    $changed = @()
    foreach ($key in $keys) {
        $beforeHash = if ($beforeMap.ContainsKey($key)) { $beforeMap[$key] } else { $null }
        $afterHash = if ($afterMap.ContainsKey($key)) { $afterMap[$key] } else { $null }
        if ([string]$beforeHash -cne [string]$afterHash) { $changed += $key }
    }
    $undeclared = @($changed | Where-Object { -not $declared.ContainsKey($_) })
    return [pscustomobject]@{ changedCount = $changed.Count; undeclaredCount = $undeclared.Count; changedPathHashes = @($changed | ForEach-Object { Get-Sha256Text -Value $_ }); undeclaredPathHashes = @($undeclared | ForEach-Object { Get-Sha256Text -Value $_ }) }
}

function Read-SessionEnvelope {
    param([object]$Paths)
    Test-ReparseChain -Candidate $Paths.sessionPath -CanonicalRoot $script:CanonicalRoot
    if (-not (Test-Path -LiteralPath $Paths.sessionPath -PathType Leaf)) { throw "session-not-found" }
    try {
        $bytes = [IO.File]::ReadAllBytes($Paths.sessionPath)
        $text = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
        return [pscustomobject]@{ session = ($text | ConvertFrom-Json); sha256 = Get-Sha256Bytes -Bytes $bytes }
    } catch { throw "session-invalid" }
}

function Read-AndValidateLease {
    param([object]$Paths, [string]$ExpectedOwnerHash, [string]$ExpectedRoot, [string]$ExpectedNonce, [string]$ExpectedSessionSha, [switch]$AllowExpired)
    Test-ReparseChain -Candidate $Paths.leasePath -CanonicalRoot $script:CanonicalRoot
    if (-not (Test-Path -LiteralPath $Paths.leasePath -PathType Leaf)) { throw "source-lease-missing" }
    try { $lease = Get-Content -LiteralPath $Paths.leasePath -Raw -Encoding UTF8 | ConvertFrom-Json }
    catch { throw "source-lease-corrupt" }
    if ([string]$lease.schemaVersion -cne "awx.source_edit_session.lease.v1" -or [string]$lease.ownerId -cne $ExpectedOwnerHash -or [string]$lease.role -cne "notebook" -or -not [bool]$lease.mutationAllowed) { throw "source-lease-owner-mismatch" }
    if ([string]$lease.sessionNonce -cne $ExpectedNonce -or [string]$lease.sessionSha256 -cne $ExpectedSessionSha) { throw "session-integrity-mismatch" }
    $leaseRoot = Resolve-CanonicalPath -Path ([string]$lease.root)
    if (-not $leaseRoot.Equals($ExpectedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw "source-lease-root-mismatch" }
    $expires = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse((ConvertTo-AwxLeaseDateText $lease.expiresAtUtc), [ref]$expires)) { throw "source-lease-corrupt" }
    $lifetime = Get-AwxLeaseLifetime -Lease $lease -LeasePath $Paths.leasePath -PatchDropDir $Paths.patchDropRoot
    if (-not $AllowExpired -and $lifetime.ownerProcessId -gt 0 -and ($lifetime.ownerState -ne 'alive' -or -not (Test-AwxLeaseOwnerAncestor $lifetime.ownerProcessId))) { throw 'owner-process-unproven' }
    if ($lifetime.heartbeatState -eq 'valid') { $expires = [DateTimeOffset]::Parse($lifetime.effectiveExpiresAtUtc) }
    if (-not $AllowExpired -and ($expires.ToUniversalTime() -le [DateTimeOffset]::UtcNow -or $lifetime.ownerState -eq 'dead')) { throw "source-lease-expired" }
    return $lease
}

function Get-ResolvedSessionRows {
    param([object]$Session, [string]$CanonicalRoot)
    $targets = @(); foreach ($row in @($Session.targets)) { $r = Resolve-RelativeMember -CanonicalRoot $CanonicalRoot -RelativePath ([string]$row.relativePath) -Kind "Any"; $targets += [pscustomobject]@{ relativePath = $r.relativePath; fullPath = $r.fullPath; existed = [bool]$row.existed; preimageSha256 = $row.preimageSha256 } }
    $boundaries = @(); foreach ($row in @($Session.boundaryEvidence)) { $r = Resolve-RelativeMember -CanonicalRoot $CanonicalRoot -RelativePath ([string]$row.relativePath) -Kind "File"; $boundaries += [pscustomobject]@{ relativePath = $r.relativePath; fullPath = $r.fullPath; sha256 = [string]$row.sha256 } }
    $watches = @(); foreach ($row in @($Session.watchRoots)) { $r = Resolve-RelativeMember -CanonicalRoot $CanonicalRoot -RelativePath ([string]$row.relativePath) -Kind "Directory"; $watches += $r }
    return [pscustomobject]@{ targets = @($targets); boundaries = @($boundaries); watches = @($watches) }
}

function Test-Boundaries {
    param([object[]]$Rows)
    foreach ($row in $Rows) { if ((Get-FileSha256 -Path ([string]$row.fullPath)) -cne [string]$row.sha256) { throw "boundary-evidence-changed" } }
}

function Test-Preimages {
    param([object[]]$Rows)
    foreach ($row in $Rows) {
        $exists = Test-Path -LiteralPath ([string]$row.fullPath) -PathType Leaf
        if ([bool]$row.existed) { if (-not $exists -or (Get-FileSha256 -Path ([string]$row.fullPath)) -cne [string]$row.preimageSha256) { throw "preimage-changed" } }
        elseif ($exists) { throw "preimage-changed" }
    }
}

function Test-PendingInventory {
    param([object]$Paths, [object]$Session)
    $promotionHandle = Open-ExclusiveFile -Path $Paths.promotionLockPath -FailureClass "promotion-lock-busy"
    try {
        if ($Session.PSObject.Properties['coordinationMode'] -and $Session.coordinationMode -eq 'target-scoped') {
            $conflict = Get-AwxPendingPatchConflictDecision -PatchDropDir $Paths.patchDropRoot -TargetPaths @($Session.targets.relativePath)
            if (-not $conflict.allowed) { throw 'patch-drop-overlap' }
            return
        }
        $current = Get-PendingPatchInventory -Paths $Paths
        if ([string]$current.sha256 -cne [string]$Session.pendingPatchInventorySha256 -or [int]$current.count -ne [int]$Session.pendingPatchCount) { throw "patch-drop-queue-changed" }
    } finally { $promotionHandle.Dispose() }
}

function Release-LeaseSafely {
    param(
        [object]$Paths,
        [string]$OwnerHash,
        [string]$CanonicalRoot,
        [string]$Nonce,
        [string]$SessionSha,
        [switch]$AllowExpired,
        [Nullable[int]]$ExpectedPendingCount = $null,
        [string]$ExpectedPendingSha = ""
    )
    $promotionHandle = Open-ExclusiveFile -Path $Paths.promotionLockPath -FailureClass "promotion-lock-busy"
    $releasedPath = $null
    try {
        Test-ReparseChain -Candidate $Paths.lockDir -CanonicalRoot $CanonicalRoot
        [void](Read-AndValidateLease -Paths $Paths -ExpectedOwnerHash $OwnerHash -ExpectedRoot $CanonicalRoot -ExpectedNonce $Nonce -ExpectedSessionSha $SessionSha -AllowExpired:$AllowExpired)
        $leaseFingerprint = Get-FileSha256 -Path $Paths.leasePath
        $children = @(Get-ChildItem -LiteralPath $Paths.lockDir -Force)
        if ($children.Count -ne 1 -or $children[0].Name -cne 'lease.json') { throw 'lease-inventory-changed' }
        if ($null -ne $ExpectedPendingCount) {
            $pending = Get-PendingPatchInventory -Paths $Paths
            if ([int]$pending.count -ne [int]$ExpectedPendingCount -or [string]$pending.sha256 -cne $ExpectedPendingSha) { throw "patch-drop-queue-changed" }
        }
        $releasedPath = Join-Path $Paths.lockRoot (".released-" + $Nonce + "-" + [guid]::NewGuid().ToString("N"))
        Test-ReparseChain -Candidate $releasedPath -CanonicalRoot $CanonicalRoot
        if ((Get-FileSha256 -Path $Paths.leasePath) -cne $leaseFingerprint) { throw 'lease-identity-changed' }
        $releasedLease = Get-Content -LiteralPath $Paths.leasePath -Raw -Encoding UTF8 | ConvertFrom-Json
        Write-AwxLeaseEvent -PatchDropDir $Paths.patchDropRoot -Event release -Lease $releasedLease -TargetPaths $(if ($releasedLease.PSObject.Properties['targetPaths']) { @($releasedLease.targetPaths) } else { @() })
        Move-Item -LiteralPath $Paths.lockDir -Destination $releasedPath
    } finally { $promotionHandle.Dispose() }
    if ($null -ne $releasedPath -and (Test-Path -LiteralPath $releasedPath)) {
        Test-ReparseChain -Candidate $releasedPath -CanonicalRoot $CanonicalRoot
        $releasedLeasePath = Join-Path $releasedPath 'lease.json'
        if (@(Get-ChildItem -LiteralPath $releasedPath -Force).Count -ne 1 -or (Get-FileSha256 -Path $releasedLeasePath) -cne $leaseFingerprint) { throw 'lease-identity-changed' }
        [IO.File]::Delete($releasedLeasePath)
        [IO.Directory]::Delete($releasedPath, $false)
    }
}

function Invoke-StaleLeaseCleanup {
    param([object]$Paths, [string]$CanonicalRoot)
    # Prepare already holds the common publication handle. Directory age and
    # expiry alone never prove that a Desktop or remote Notebook writer exited.
    $recovery = Invoke-AwxAbandonedLeaseRecovery -PatchDropDir $Paths.patchDropRoot
    return [pscustomobject]@{ inspectedCount = $recovery.inspectedCount; deletedCount = 0; quarantinedCount = $recovery.recoveredCount; skippedCount = $recovery.skippedCount }
}

function Write-FailureRecord {
    param([object]$Paths, [string]$SessionSha, [string]$Classification, [object]$Extra = $null)
    $row = [ordered]@{ schemaVersion = "awx.macsrc_smb_patch_failure.v2"; runId = $RunId; sessionSha256 = $SessionSha; generatedAtUtc = [DateTimeOffset]::UtcNow.ToString("o"); failureClassification = $Classification }
    if ($null -ne $Extra) { foreach ($property in $Extra.PSObject.Properties) { $row[$property.Name] = $property.Value } }
    Write-JsonAtomic -Path $Paths.failurePath -Value $row
}

if ($RunId -notmatch "^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$") { Write-GuardFailure -Classification "run-id-invalid" }
if ([string]::IsNullOrWhiteSpace($OwnerId) -or (Test-SecretLikeText -Text $OwnerId)) { Write-GuardFailure -Classification "owner-id-invalid" }

try {
    $canonicalRoot = Resolve-CanonicalPath -Path $Root
    $expectedRoot = Resolve-CanonicalPath -Path $script:ProductionMacSrcRoot
} catch { Write-GuardFailure -Classification "macsrc-root-unavailable" }
if (-not $canonicalRoot.Equals($expectedRoot, [StringComparison]::OrdinalIgnoreCase)) { Write-GuardFailure -Classification "macsrc-root-mismatch" }
$script:CanonicalRoot = $canonicalRoot

$paths = Get-SessionPaths -CanonicalRoot $canonicalRoot
$ownerHash = Get-Sha256Text -Value $OwnerId
$repoRoot = Resolve-CanonicalPath -Path (Join-Path $PSScriptRoot "..\..\..\..")
$leaseContract = Join-Path $repoRoot "__patch_drop__\source_edit_lease_contract.ps1"
if (-not (Test-Path -LiteralPath $leaseContract -PathType Leaf)) { Write-GuardFailure -Classification "source-lease-contract-missing" }
. $leaseContract

$transitionHandle = $null
try {
    if ($Mode -eq "Prepare") {
        if ($TargetFiles.Count -eq 0) { throw "target-list-empty" }
        if ($BoundaryEvidenceFiles.Count -eq 0) { throw "boundary-evidence-invalid" }
        if ($WatchRoots.Count -eq 0) { throw "watch-root-invalid" }
        if (Test-Path -LiteralPath $paths.sessionDir) { throw "run-id-already-exists" }
        if (-not (Test-Path -LiteralPath $paths.patchDropRoot -PathType Container)) { throw "patchdrop-root-missing" }

        $targetRows = @(); foreach ($relative in $TargetFiles) { $r = Resolve-RelativeMember -CanonicalRoot $canonicalRoot -RelativePath $relative -Kind "Any"; $exists = Test-Path -LiteralPath $r.fullPath -PathType Leaf; $targetRows += [ordered]@{ relativePath = $r.relativePath; existed = $exists; preimageSha256 = if ($exists) { Get-FileSha256 -Path $r.fullPath } else { $null } } }
        $targetRows = @($targetRows | Sort-Object { [string]$_['relativePath'] } -Unique)
        $boundaryRows = @(); foreach ($relative in $BoundaryEvidenceFiles) { try { $r = Resolve-RelativeMember -CanonicalRoot $canonicalRoot -RelativePath $relative -Kind "File" } catch { throw "boundary-evidence-invalid" }; $boundaryRows += [ordered]@{ relativePath = $r.relativePath; sha256 = Get-FileSha256 -Path $r.fullPath } }
        $watchRows = @(); foreach ($relative in $WatchRoots) { $watchRows += Resolve-RelativeMember -CanonicalRoot $canonicalRoot -RelativePath $relative -Kind "Directory" }
        foreach ($target in $targetRows) {
            $targetFull = [IO.Path]::GetFullPath((Join-Path $canonicalRoot ([string]$target.relativePath)))
            $covered = @($watchRows | Where-Object { $targetFull.StartsWith(([string]$_.fullPath).TrimEnd("\") + "\", [StringComparison]::OrdinalIgnoreCase) }).Count -gt 0
            if (-not $covered) { throw "target-outside-watch-root" }
        }
        $gitEvidence = Get-GitEvidence -CanonicalRoot $canonicalRoot -RelativeTargets @($targetRows.relativePath)
        $operation = Get-AwxScopedOperationDecision -Operation worktree-edit -GitEvidence (Get-AwxGitOperationEvidence -ProjectRoot $canonicalRoot -AllowFilesystemFallback) -TargetsVerified $true -SourceLeaseChecked $true
        if (-not $operation.allowed) { throw $operation.firstBlockingRule }
        $nonce = [guid]::NewGuid().ToString("N")
        $promotionHandle = Open-ExclusiveFile -Path $paths.promotionLockPath -FailureClass "promotion-lock-busy"
        $leaseCreated = $false
        try {
            $expiredCleanup = Invoke-StaleLeaseCleanup -Paths $paths -CanonicalRoot $canonicalRoot
            $pending = Get-PendingPatchInventory -Paths $paths
            $pendingConflict = Get-AwxPendingPatchConflictDecision -PatchDropDir $paths.patchDropRoot -TargetPaths @($targetRows.relativePath)
            if (-not $pendingConflict.allowed) { throw 'patch-drop-overlap' }
            if ($pending.count -gt 0) {
                $targetSet = @{}; foreach ($target in $targetRows) { $targetSet[([string]$target.relativePath).ToLowerInvariant()] = $true }
                foreach ($pendingRow in @($pending.rows)) {
                    foreach ($touchedPath in @($pendingRow.touchedPaths)) {
                        $normalizedTouched = ([string]$touchedPath).ToLowerInvariant()
                        $overlap = $targetSet.ContainsKey($normalizedTouched)
                        if ($overlap) { throw "patch-drop-overlap" }
                    }
                }
            }
            $conflict = Get-AwxSourceEditConflictDecision -PatchDropDir $paths.patchDropRoot -TargetPaths @($targetRows.relativePath)
            if (-not $conflict.allowed -or (Test-Path -LiteralPath $paths.lockDir)) { throw "source-lease-blocked" }
            if (-not (Test-Path -LiteralPath $paths.lockRoot)) { New-Item -ItemType Directory -Force -Path $paths.lockRoot | Out-Null }
            New-Item -ItemType Directory -Path $paths.lockDir | Out-Null
            $leaseCreated = $true
            $baseline = @()
            $session = [ordered]@{
                schemaVersion = "awx.macsrc_smb_patch_session.v2"
                coordinationMode = 'target-scoped'
                verificationScope = 'declared-targets'
                runId = $RunId
                sourceWriteMode = "MACSRC_SMB_DIRECT"
                createdAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
                canonicalRoot = $canonicalRoot
                accessPolicy = $script:AccessPolicy
                ownerHash = $ownerHash
                sessionNonce = $nonce
                gitEvidenceMode = $gitEvidence.mode
                branch = $gitEvidence.branch
                targetDirtyCount = $gitEvidence.targetDirtyCount
                globalSafeDirectoryMutation = $false
                allowDeletion = [bool]$AllowDeletion
                pendingPatchCount = $pending.count
                pendingPatchInventorySha256 = $pending.sha256
                pendingPatchQueueAcknowledged = [bool]($pending.count -gt 0 -and $AllowPendingPatchQueue)
                targets = @($targetRows)
                boundaryEvidence = @($boundaryRows)
                watchRoots = @($watchRows | ForEach-Object { [ordered]@{ relativePath = $_.relativePath } })
                sourceBaseline = @($baseline)
                rawSecretPatternHits = 0
            }
            Write-JsonAtomic -Path $paths.sessionPath -Value $session
            $sessionSha = Get-FileSha256 -Path $paths.sessionPath
            $lease = New-AwxSourceEditLeaseRecord -Topic $paths.topic -Role "notebook" -OwnerId $ownerHash -Root $canonicalRoot -TtlMinutes $TtlMinutes -MutationAllowed $true -OwnerProcessId $OwnerProcessId -TaskId $RunId
            $lease['sessionNonce'] = $nonce
            $lease['sessionSha256'] = $sessionSha
            $lease['coordinationMode'] = 'target-scoped'
            $lease['targetPaths'] = @($targetRows.relativePath | ForEach-Object { ConvertTo-AwxCanonicalTargetPath $_ })
            Write-JsonAtomic -Path $paths.leasePath -Value $lease
            Write-AwxLeaseEvent -PatchDropDir $paths.patchDropRoot -Event acquire -Lease ([pscustomobject]$lease) -TargetPaths @($lease.targetPaths)
        } catch {
            if ($leaseCreated -and (Test-Path -LiteralPath $paths.lockDir)) { Remove-Item -LiteralPath $paths.lockDir -Recurse -Force -ErrorAction SilentlyContinue }
            throw
        } finally { $promotionHandle.Dispose() }
        Write-GuardResult -Value ([ordered]@{
            schemaVersion = "awx.macsrc_smb_patch_guard.result.v2"; mode = $Mode; runId = $RunId; verdict = "APPLY"; authorized = $false; nextMode = "Verify"; sessionPath = $paths.sessionPath; sessionSha256 = $sessionSha; gitEvidenceMode = $gitEvidence.mode; accessPolicy = $script:AccessPolicy; globalSafeDirectoryMutation = $false; pendingPatchCount = $pending.count; pendingPatchQueueAcknowledged = [bool]($pending.count -gt 0 -and $AllowPendingPatchQueue); expiredLeaseCleanupInspectedCount = $expiredCleanup.inspectedCount; expiredLeaseCleanupDeletedCount = $expiredCleanup.deletedCount; expiredLeaseCleanupQuarantinedCount = $expiredCleanup.quarantinedCount; expiredLeaseCleanupSkippedCount = $expiredCleanup.skippedCount; failureClassification = "none"
        })
    }

    if (-not (Test-Path -LiteralPath $paths.sessionDir -PathType Container)) { throw "session-not-found" }
    $transitionHandle = Open-ExclusiveFile -Path $paths.transitionLockPath -FailureClass "transition-lock-busy"
    $envelope = Read-SessionEnvelope -Paths $paths
    $session = $envelope.session
    $sessionSha = $envelope.sha256
    if ([string]$session.ownerHash -cne $ownerHash -or -not ([string]$session.canonicalRoot).Equals($canonicalRoot, [StringComparison]::OrdinalIgnoreCase)) { throw "session-integrity-mismatch" }
    if ($Mode -eq "Complete" -and -not (Test-Path -LiteralPath $paths.lockDir)) {
        $terminalPath = if (Test-Path -LiteralPath $paths.completionPath -PathType Leaf) { $paths.completionPath } elseif (Test-Path -LiteralPath $paths.commitIntentPath -PathType Leaf) { $paths.commitIntentPath } else { "" }
        if ($terminalPath) {
            try { $terminal = Get-Content -LiteralPath $terminalPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { throw "completion-record-invalid" }
            if ([string]$terminal.runId -cne $RunId -or [string]$terminal.sessionSha256 -cne $sessionSha -or [string]$terminal.ownerHash -cne $ownerHash -or [string]$terminal.state -notin @("COMMITTING", "COMPLETE")) { throw "completion-record-invalid" }
            if ([string]$terminal.state -eq "COMMITTING") {
                $terminal.state = "COMPLETE"
                $terminal.completedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
                Write-JsonAtomic -Path $paths.completionPath -Value $terminal
            }
            Write-GuardResult -Value ([ordered]@{ schemaVersion = "awx.macsrc_smb_patch_guard.result.v2"; mode = $Mode; runId = $RunId; verdict = "COMPLETE"; authorized = $false; changedFileCount = [int]$terminal.changedFileCount; undeclaredSourceChangeCount = 0; rawSecretPatternHits = 0; leaseReleased = $true; sessionPath = $paths.sessionPath; completionPath = $paths.completionPath; idempotentReplay = $true; failureClassification = "none" })
        }
    }
    if ($Mode -eq 'Heartbeat') {
        $heartbeatHandle = Open-ExclusiveFile -Path $paths.promotionLockPath -FailureClass 'promotion-lock-busy'
        try {
            [void](Read-AndValidateLease -Paths $paths -ExpectedOwnerHash $ownerHash -ExpectedRoot $canonicalRoot -ExpectedNonce ([string]$session.sessionNonce) -ExpectedSessionSha $sessionSha -AllowExpired)
            $heartbeat = Update-AwxLeaseHeartbeat -PatchDropDir $paths.patchDropRoot -LeasePath $paths.leasePath -ExpectedFingerprint (Get-FileSha256 -Path $paths.leasePath) -TtlMinutes $TtlMinutes
        } finally { $heartbeatHandle.Dispose() }
        Write-GuardResult -Value ([ordered]@{ mode = $Mode; runId = $RunId; verdict = 'RENEWED'; authorized = $false; expiresAtUtc = $heartbeat.expiresAtUtc; sessionSha256 = $sessionSha; failureClassification = 'none' })
    }
    $allowExpiredLease = $Mode -eq "Abort"
    [void](Read-AndValidateLease -Paths $paths -ExpectedOwnerHash $ownerHash -ExpectedRoot $canonicalRoot -ExpectedNonce ([string]$session.sessionNonce) -ExpectedSessionSha $sessionSha -AllowExpired:$allowExpiredLease)
    $rows = Get-ResolvedSessionRows -Session $session -CanonicalRoot $canonicalRoot
    $targetScoped = $session.PSObject.Properties['coordinationMode'] -and $session.coordinationMode -eq 'target-scoped'
    $operation = Get-AwxScopedOperationDecision -Operation worktree-edit -GitEvidence (Get-AwxGitOperationEvidence -ProjectRoot $canonicalRoot -AllowFilesystemFallback) -TargetsVerified $true -SourceLeaseChecked $true
    if (-not $operation.allowed) { throw $operation.firstBlockingRule }
    $conflict = Get-AwxSourceEditConflictDecision -PatchDropDir $paths.patchDropRoot -TargetPaths @($rows.targets.relativePath) -ExcludeLockPath $paths.lockDir
    if (-not $conflict.allowed) { throw 'source-lease-blocked' }

    if ($Mode -eq "Verify") {
        Test-Boundaries -Rows $rows.boundaries
        Test-Preimages -Rows $rows.targets
        Test-PendingInventory -Paths $paths -Session $session
        Write-JsonAtomic -Path $paths.readyPath -Value ([ordered]@{ schemaVersion = "awx.macsrc_smb_patch_ready.v2"; runId = $RunId; sessionSha256 = $sessionSha; sessionNonce = $session.sessionNonce; verifiedAtUtc = [DateTimeOffset]::UtcNow.ToString("o") })
        Write-GuardResult -Value ([ordered]@{ schemaVersion = "awx.macsrc_smb_patch_guard.result.v2"; mode = $Mode; runId = $RunId; verdict = "APPLY"; authorized = $true; targetCount = @($rows.targets).Count; sessionPath = $paths.sessionPath; sessionSha256 = $sessionSha; failureClassification = "none" })
    }

    if ($Mode -eq "Complete") {
        if (-not (Test-Path -LiteralPath $paths.readyPath -PathType Leaf)) { throw "session-not-verified" }
        try { $ready = Get-Content -LiteralPath $paths.readyPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { throw "ready-record-invalid" }
        if ([string]$ready.sessionSha256 -cne $sessionSha -or [string]$ready.sessionNonce -cne [string]$session.sessionNonce -or [string]$ready.runId -cne $RunId) { throw "ready-record-invalid" }
        Test-Boundaries -Rows $rows.boundaries
        Test-PendingInventory -Paths $paths -Session $session
        if (-not $targetScoped) {
            $currentManifest = Get-WatchManifest -CanonicalRoot $canonicalRoot -WatchRows $rows.watches
            $delta = Get-WatchDelta -Before @($session.sourceBaseline) -After $currentManifest -DeclaredTargets @($rows.targets.relativePath)
            if ($delta.undeclaredCount -gt 0) { throw "undeclared-source-change" }
        }

        $postRows = @(); $changed = 0; $secretHits = 0
        foreach ($target in $rows.targets) {
            $exists = Test-Path -LiteralPath ([string]$target.fullPath) -PathType Leaf
            if ([bool]$target.existed -and -not $exists -and -not [bool]$session.allowDeletion) { throw "unapproved-target-deletion" }
            $scan = if ($exists) { Get-LockedFileScan -Path ([string]$target.fullPath) } else { $null }
            if ($null -ne $scan -and $scan.secretHit) { $secretHits++ }
            $postHash = if ($null -ne $scan) { $scan.sha256 } else { $null }
            $isChanged = ([bool]$target.existed -ne $exists) -or ([string]$target.preimageSha256 -cne [string]$postHash)
            if ($isChanged) { $changed++ }
            $postRows += [ordered]@{ relativePath = [string]$target.relativePath; exists = $exists; postimageSha256 = $postHash; changed = $isChanged }
        }
        if ($changed -eq 0) { throw "no-source-change" }
        if ($secretHits -gt 0) {
            Write-FailureRecord -Paths $paths -SessionSha $sessionSha -Classification "secret-leak-risk" -Extra ([pscustomobject]@{ rawSecretPatternHits = $secretHits })
            Write-GuardFailure -Classification "secret-leak-risk" -Extra ([pscustomobject]@{ rawSecretPatternHits = $secretHits; leaseReleased = $false; sessionPath = $paths.sessionPath })
        }
        if ([string]::IsNullOrWhiteSpace($VerificationEvidenceFile)) { throw "verification-evidence-missing" }
        $evidenceResolved = Resolve-RelativeMember -CanonicalRoot $canonicalRoot -RelativePath $VerificationEvidenceFile -Kind "File"
        if (-not (Test-PathInsideRoot -Candidate $evidenceResolved.fullPath -CanonicalRoot $paths.sessionDir)) { throw "verification-evidence-outside-session" }
        $evidenceScan = Get-LockedFileScan -Path $evidenceResolved.fullPath -CaptureBytes
        if ($evidenceScan.secretHit) { throw "secret-leak-risk" }
        try {
            $evidenceText = [Text.UTF8Encoding]::new($false, $true).GetString($evidenceScan.bytes)
            $evidence = $evidenceText | ConvertFrom-Json
        } catch { throw "verification-evidence-invalid" }
        if ([string]$evidence.schemaVersion -cne "awx.macsrc_smb_patch_verification.v1" -or [string]$evidence.runId -cne $RunId -or [string]$evidence.sessionSha256 -cne $sessionSha -or [int]$evidence.exitCode -ne 0 -or [string]::IsNullOrWhiteSpace([string]$evidence.command)) { throw "verification-evidence-invalid" }
        $evidenceMap = @{}; foreach ($row in @($evidence.targetPostimages)) { $evidenceMap[([string]$row.relativePath).ToLowerInvariant()] = [string]$row.sha256 }
        if ($evidenceMap.Count -ne $postRows.Count) { throw "verification-target-hash-mismatch" }
        foreach ($post in $postRows) { if (-not $evidenceMap.ContainsKey(([string]$post.relativePath).ToLowerInvariant()) -or [string]$evidenceMap[([string]$post.relativePath).ToLowerInvariant()] -cne [string]$post.postimageSha256) { throw "verification-target-hash-mismatch" } }
        $completion = [ordered]@{ schemaVersion = "awx.macsrc_smb_patch_completion.v2"; state = "COMMITTING"; runId = $RunId; sessionSha256 = $sessionSha; ownerHash = $ownerHash; preparedAtUtc = [DateTimeOffset]::UtcNow.ToString("o"); changedFileCount = $changed; undeclaredSourceChangeCount = 0; rawSecretPatternHits = 0; verificationEvidenceSha256 = $evidenceScan.sha256; postimages = @($postRows) }
        Write-JsonAtomic -Path $paths.commitIntentPath -Value $completion
        if ($targetScoped) {
            Test-PendingInventory -Paths $paths -Session $session
            Release-LeaseSafely -Paths $paths -OwnerHash $ownerHash -CanonicalRoot $canonicalRoot -Nonce ([string]$session.sessionNonce) -SessionSha $sessionSha
        } else {
            Release-LeaseSafely -Paths $paths -OwnerHash $ownerHash -CanonicalRoot $canonicalRoot -Nonce ([string]$session.sessionNonce) -SessionSha $sessionSha -ExpectedPendingCount ([int]$session.pendingPatchCount) -ExpectedPendingSha ([string]$session.pendingPatchInventorySha256)
        }
        $completion.state = "COMPLETE"
        $completion.completedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
        Write-JsonAtomic -Path $paths.completionPath -Value $completion
        Write-GuardResult -Value ([ordered]@{ schemaVersion = "awx.macsrc_smb_patch_guard.result.v2"; mode = $Mode; runId = $RunId; verdict = "COMPLETE"; authorized = $false; changedFileCount = $changed; undeclaredSourceChangeCount = 0; rawSecretPatternHits = 0; leaseReleased = $true; sessionPath = $paths.sessionPath; completionPath = $paths.completionPath; failureClassification = "none" })
    }

    if ($Mode -eq "Abort") {
        $rollbackRequired = $false
        foreach ($target in $rows.targets) {
            $exists = Test-Path -LiteralPath ([string]$target.fullPath) -PathType Leaf
            $currentHash = if ($exists) { Get-FileSha256 -Path ([string]$target.fullPath) } else { $null }
            if (([bool]$target.existed -ne $exists) -or ([string]$target.preimageSha256 -cne [string]$currentHash)) { $rollbackRequired = $true }
        }
        if (-not $targetScoped) {
            $abortManifest = Get-WatchManifest -CanonicalRoot $canonicalRoot -WatchRows $rows.watches
            $abortDelta = Get-WatchDelta -Before @($session.sourceBaseline) -After $abortManifest -DeclaredTargets @($rows.targets.relativePath)
            if ($abortDelta.changedCount -gt 0) { $rollbackRequired = $true }
        }
        if ($rollbackRequired) {
            Write-FailureRecord -Paths $paths -SessionSha $sessionSha -Classification "rollback-required"
            Write-GuardFailure -Classification "rollback-required" -Extra ([pscustomobject]@{ rollbackRequired = $true; leaseReleased = $false; sessionPath = $paths.sessionPath })
        }
        Release-LeaseSafely -Paths $paths -OwnerHash $ownerHash -CanonicalRoot $canonicalRoot -Nonce ([string]$session.sessionNonce) -SessionSha $sessionSha -AllowExpired
        Write-JsonAtomic -Path $paths.abortPath -Value ([ordered]@{ schemaVersion = "awx.macsrc_smb_patch_abort.v2"; state = "ABORTED"; runId = $RunId; sessionSha256 = $sessionSha; abortedAtUtc = [DateTimeOffset]::UtcNow.ToString("o"); rollbackRequired = $false; leaseReleased = $true })
        Write-GuardResult -Value ([ordered]@{ schemaVersion = "awx.macsrc_smb_patch_guard.result.v2"; mode = $Mode; runId = $RunId; verdict = "ABORTED"; authorized = $false; rollbackRequired = $false; leaseReleased = $true; sessionPath = $paths.sessionPath; abortPath = $paths.abortPath; failureClassification = "none" })
    }
} catch {
    $known = @(
        "target-list-empty", "target-outside-macsrc", "target-outside-watch-root", "reparse-path-risk", "boundary-evidence-invalid", "boundary-evidence-changed", "watch-root-invalid", "index-lock-present", "patchdrop-root-missing", "patch-drop-pending", "patch-drop-queue-changed", "patch-drop-path-inventory-unverified", "patch-drop-overlap", "promotion-lock-busy", "transition-lock-busy", "source-lease-blocked", "source-lease-missing", "source-lease-corrupt", "source-lease-owner-mismatch", "source-lease-root-mismatch", "source-lease-expired", "expired-lease-cleanup-failed", "session-integrity-mismatch", "preimage-changed", "session-not-found", "session-invalid", "session-not-verified", "ready-record-invalid", "completion-record-invalid", "run-id-already-exists", "unapproved-target-deletion", "undeclared-source-change", "no-source-change", "secret-scan-unverified", "secret-leak-risk", "verification-evidence-missing", "verification-evidence-outside-session", "verification-evidence-invalid", "verification-evidence-too-large", "verification-target-hash-mismatch", "rollback-required", "macsrc-root-mismatch"
    )
    $classification = if ($_.Exception.Message -in $known) { $_.Exception.Message } else { "guard-internal-error" }
    if ($null -ne $transitionHandle) { $transitionHandle.Dispose(); $transitionHandle = $null }
    Write-GuardFailure -Classification $classification
} finally {
    if ($null -ne $transitionHandle) { $transitionHandle.Dispose() }
}
