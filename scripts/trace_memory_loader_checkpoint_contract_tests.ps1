param()

$ErrorActionPreference = "Stop"

$path = "main\java\com\example\lms\service\rag\handler\MemoryHandler.java"
if (-not (Test-Path $path)) {
    throw "[AWX][trace-memory-loader] missing MemoryHandler.java"
}
$probePath = "main\java\com\example\lms\trace\TraceMemoryFingerprintProbe.java"
if (-not (Test-Path $probePath)) {
    throw "[AWX][trace-memory-loader] missing TraceMemoryFingerprintProbe.java"
}

$source = Get-Content $path -Raw
$probe = Get-Content $probePath -Raw
$failures = New-Object System.Collections.Generic.List[string]

function Require-Contains([string]$Text, [string]$Needle, [string]$Label) {
    if (-not $Text.Contains($Needle)) {
        $script:failures.Add("missing:$Label")
    }
}

function Require-NotContains([string]$Text, [string]$Needle, [string]$Label) {
    if ($Text.Contains($Needle)) {
        $script:failures.Add("forbidden:$Label")
    }
}

function Require-Order([string]$Text, [string]$First, [string]$Second, [string]$Label) {
    $firstIndex = $Text.IndexOf($First)
    $secondIndex = $Text.IndexOf($Second)
    if ($firstIndex -lt 0 -or $secondIndex -lt 0 -or $firstIndex -ge $secondIndex) {
        $script:failures.Add("order:$Label")
    }
}

function Require-After([string]$Text, [string]$First, [string]$Second, [string]$Label) {
    $firstIndex = $Text.IndexOf($First)
    $secondIndex = if ($firstIndex -lt 0) { -1 } else { $Text.IndexOf($Second, $firstIndex + $First.Length) }
    if ($firstIndex -lt 0 -or $secondIndex -lt 0) {
        $script:failures.Add("order:$Label")
    }
}

Require-Contains $source "TraceMemoryFingerprintProbe" "probe-import-or-field"
Require-Contains $source "traceMemoryFingerprintProbe" "optional-probe-field"
Require-Contains $source "traceMemoryCheckpoint(" "loader-checkpoint-helper"
Require-Contains $source '"raw_snapshot"' "raw-snapshot-stage"
Require-Contains $source '"memory_loader_raw"' "raw-loader-phase"
Require-Contains $source '"first_refinement"' "first-refinement-stage"
Require-Contains $source '"memory_loader_after_recent_filter"' "after-recent-filter-phase"
Require-Contains $source '"second_refinement"' "second-refinement-stage"
Require-Contains $source '"memory_loader_after_section_assembly"' "after-section-assembly-phase"
Require-Contains $source '"load"' "load-stage"
Require-Contains $source '"memory_loader_assembled"' "assembled-phase"
Require-Contains $source '"memory.loader.checkpoint.suppressed"' "checkpoint-suppression-breadcrumb"
Require-Contains $source "SafeRedactor.hashValue" "query-session-hash-only"
Require-Contains $probe 'case "memory_loader_raw" -> "memory.loader.raw";' "raw-loader-phase-normalized"
Require-Contains $probe 'case "memory_loader_after_recent_filter" -> "memory.loader.after_recent_filter";' "after-filter-phase-normalized"
Require-Contains $probe 'case "memory_loader_after_section_assembly" -> "memory.loader.after_section_assembly";' "after-section-assembly-phase-normalized"
Require-Contains $probe 'case "memory_loader_assembled" -> "memory.loader.assembled";' "assembled-phase-normalized"
Require-Contains $probe 'TraceStore.append(PREFIX + "checkpoint.history"' "checkpoint-history-append"
Require-Contains $probe 'TraceStore.put(PREFIX + "checkpoint.historySize"' "checkpoint-history-size"
Require-Contains $probe '"deltaChangedCount"' "checkpoint-history-delta-count"
Require-Contains $probe '"supabaseShadowCount"' "checkpoint-history-supabase-shadow-count"
Require-Order $source 'traceMemoryCheckpoint("raw_snapshot", "memory_loader_raw"' 'traceMemoryCheckpoint("first_refinement", "memory_loader_after_recent_filter"' "raw-before-first"
Require-Order $source 'traceMemoryCheckpoint("first_refinement", "memory_loader_after_recent_filter"' 'traceMemoryCheckpoint("second_refinement", "memory_loader_after_section_assembly"' "first-before-second"
Require-After $source 'traceMemoryCheckpoint("second_refinement", "memory_loader_after_section_assembly"' 'traceMemoryCheckpoint("load", "memory_loader_assembled"' "second-before-load"
Require-NotContains $source 'TraceStore.put("traceMemory.rawSnapshot.raw"' "raw-snapshot-dump"
Require-NotContains $source 'TraceStore.put("traceMemory.memoryCtx"' "raw-memory-context-dump"
Require-NotContains $source 'log.warn("[AWX][trace-memory-loader]' "warn-raw-loader-log"

if ($failures.Count -gt 0) {
    Write-Host "[AWX][trace-memory-loader] FAIL"
    foreach ($failure in $failures) {
        Write-Host "[AWX][trace-memory-loader] $failure"
    }
    exit 1
}

Write-Host "[AWX][trace-memory-loader] PASS"
