<#
.SYNOPSIS
    DB Structure Gap Scanner — PowerShell wrapper
.DESCRIPTION
    Scans Java source to quantify design-vs-implementation DB persistence gaps.
    Wraps db_gap_scanner.py or runs standalone analysis via Select-String.
.EXAMPLE
    .\scripts\db_gap_scanner.ps1
    .\scripts\db_gap_scanner.ps1 -OutputDir data\db-gap-report\after
    .\scripts\db_gap_scanner.ps1 -SkipPython
#>
[CmdletBinding()]
param(
    [string]$Root = "main\java",
    [string]$OutputDir = "data\db-gap-report",
    [switch]$SkipPython
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path (Join-Path $repoRoot $Root))) {
    $repoRoot = (Get-Location).Path
}
$javaRoot = Join-Path $repoRoot $Root
$outDir = Join-Path $repoRoot $OutputDir

if (-not (Test-Path $javaRoot)) {
    Write-Error "Java root not found: $javaRoot"
    exit 1
}

New-Item -ItemType Directory -Path $outDir -Force | Out-Null
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  DB Structure Gap Scanner (PowerShell)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Root: $javaRoot"
Write-Host "  Output: $outDir"

# ─── Try Python first ──────────────────────────────────────
if (-not $SkipPython) {
    $pyScript = Join-Path $repoRoot "scripts\db_gap_scanner.py"
    if (Test-Path $pyScript) {
        $pyCmd = $null
        foreach ($candidate in @("python3", "python", "py")) {
            try {
                $ver = & $candidate --version 2>&1
                if ($ver -match "Python 3") { $pyCmd = $candidate; break }
            } catch {}
        }
        if ($pyCmd) {
            Write-Host "`n  Running Python scanner..." -ForegroundColor Green
            & $pyCmd $pyScript --root $javaRoot --output $outDir --format both
            if ($LASTEXITCODE -eq 0) {
                Write-Host "`n  Python scanner completed successfully." -ForegroundColor Green
                exit 0
            }
            Write-Warning "Python scanner failed (exit=$LASTEXITCODE), falling back to PowerShell"
        }
    }
}

# ─── PowerShell standalone fallback ────────────────────────
Write-Host "`n  Running PowerShell standalone analysis..." -ForegroundColor Yellow

$javaFiles = Get-ChildItem -Recurse -Filter "*.java" -Path $javaRoot
Write-Host "  Scanning $($javaFiles.Count) Java files..."

# Entity scan
$entityResults = $javaFiles | Select-String -Pattern "@Entity" -SimpleMatch |
    ForEach-Object {
        $content = Get-Content $_.Path -Raw -Encoding UTF8
        $tableName = ""
        if ($content -match '@Table\s*\(\s*name\s*=\s*"(\w+)"') {
            $tableName = $Matches[1]
        }
        $className = ""
        if ($content -match 'class\s+(\w+)') {
            $className = $Matches[1]
        }
        $pkg = ""
        if ($content -match '^\s*package\s+([\w.]+)\s*;') {
            $pkg = $Matches[1]
        }
        [PSCustomObject]@{
            FQCN = "$pkg.$className"
            SimpleName = $className
            TableName = if ($tableName) { $tableName } else { $className.ToLower() }
            FilePath = $_.Path.Replace($javaRoot, "").TrimStart("\")
            LineNumber = $_.LineNumber
        }
    }

# Repository scan
$repoResults = $javaFiles | Select-String -Pattern "JpaRepository" |
    Where-Object { $_.Line -match "extends" } |
    ForEach-Object {
        $content = Get-Content $_.Path -Raw -Encoding UTF8
        $entityType = ""
        $idType = ""
        if ($content -match 'extends\s+(?:Jpa|Crud)Repository\s*<\s*(\w+)\s*,\s*(\w+)\s*>') {
            $entityType = $Matches[1]
            $idType = $Matches[2]
        }
        $className = ""
        if ($content -match 'interface\s+(\w+)') {
            $className = $Matches[1]
        }
        $pkg = ""
        if ($content -match '^\s*package\s+([\w.]+)\s*;') {
            $pkg = $Matches[1]
        }
        [PSCustomObject]@{
            FQCN = "$pkg.$className"
            SimpleName = $className
            EntityType = $entityType
            IdType = $idType
            FilePath = $_.Path.Replace($javaRoot, "").TrimStart("\")
        }
    }

# Subsystem volatile check
$subsystemChecks = @(
    @{ Name="S02_CFVM_RawMatrixBuffer"; Pattern="class RawMatrixBuffer"; Volatile=@("ArrayDeque","synchronized") },
    @{ Name="S05_ExtremeZ"; Pattern="class ExtremeZSystemHandler"; Volatile=@("ThreadLocal") },
    @{ Name="S07_TraceStore"; Pattern="class TraceStore"; Volatile=@("ThreadLocal","ConcurrentHashMap") },
    @{ Name="S07_DebugEventStore"; Pattern="class DebugEventStore"; Volatile=@("ConcurrentLinkedDeque") }
)

$volatileFindings = @()
foreach ($check in $subsystemChecks) {
    $hits = $javaFiles | Select-String -Pattern $check.Pattern
    foreach ($hit in $hits) {
        $content = Get-Content $hit.Path -Raw -Encoding UTF8
        $foundVolatile = @()
        foreach ($vp in $check.Volatile) {
            if ($content -match [regex]::Escape($vp)) {
                $foundVolatile += $vp
            }
        }
        if ($foundVolatile.Count -gt 0) {
            $volatileFindings += [PSCustomObject]@{
                Subsystem = $check.Name
                File = $hit.Path.Replace($javaRoot, "").TrimStart("\")
                VolatilePatterns = ($foundVolatile -join ", ")
            }
        }
    }
}

# ExtremeZ duplicate count
$extremeZCount = ($javaFiles | Select-String -Pattern "class ExtremeZSystemHandler").Count

# Summary
$summary = [PSCustomObject]@{
    ScanDate = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
    TotalJavaFiles = $javaFiles.Count
    TotalEntities = $entityResults.Count
    TotalRepositories = $repoResults.Count
    ExtremeZDuplicateCount = $extremeZCount
    VolatileSubsystems = $volatileFindings.Count
    CriticalGaps = @(
        "S02_CFVM: RawMatrixBuffer uses ArrayDeque (volatile, process-local)",
        "S05_ExtremeZ: $extremeZCount duplicate classes",
        "S07: TraceStore uses ThreadLocal (request-local, non-durable)"
    )
}

Write-Host "`n  Results:" -ForegroundColor Cyan
Write-Host "    Entities: $($entityResults.Count)"
Write-Host "    Repositories: $($repoResults.Count)"
Write-Host "    ExtremeZ duplicates: $extremeZCount"
Write-Host "    Volatile subsystem findings: $($volatileFindings.Count)"

# Write outputs
$summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outDir "gap_summary_ps.json") -Encoding UTF8
$entityResults | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $outDir "entity_catalog_ps.json") -Encoding UTF8
$repoResults | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $outDir "repository_catalog_ps.json") -Encoding UTF8
if ($volatileFindings.Count -gt 0) {
    $volatileFindings | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $outDir "volatile_findings_ps.json") -Encoding UTF8
}

Write-Host "`n  Output written to: $outDir" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
