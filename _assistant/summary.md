# Patch Summary (2025-10-14T23:52:00.395589Z)

## What was fixed

- Replaced `new ScoreCalibrator()` (interface) in `FusionPipelineConfig` with `new MinMaxCalibrator()` concrete implementation within the same package.

## Touched files

- src/main/java/com/abandonware/ai/service/rag/fusion/FusionPipelineConfig.java

## Detected historical build error patterns

[
  {
    "file": "BUILD_ERROR_PATTERN_SCAN.md",
    "pattern": "cannot find symbol",
    "line": "cannot find symbol"
  },
  {
    "file": "BUILD_FIX_NOTES.md",
    "pattern": "cannot find symbol",
    "line": "cannot find symbol"
  },
  {
    "file": "BUILD_FIX_NOTES__77_bm25.md",
    "pattern": "cannot find symbol",
    "line": "cannot find symbol"
  },
  {
    "file": "BUILD_FIX_NOTES__79_fusion.md",
    "pattern": "cannot find symbol",
    "line": "cannot find symbol"
  },
  {
    "file": "BUILD_PATTERN_SUMMARY.md",
    "pattern": "cannot find symbol",
    "line": "cannot find symbol"
  },
  {
    "file": "_assistant/diagnostics/build_error_patterns.json",
    "pattern": "cannot find symbol",
    "line": "cannot find symbol"
  },
  {
    "file": "_assistant/summary.md",
    "pattern": "cannot find symbol",
    "line": "cannot find symbol"
  },
  {
    "file": "analysis/build_patterns_aggregated.json",
    "pattern": "cannot find symbol",
    "line": "cannot find symbol"
  },
  {
    "file": "build_error_patterns_summary.json",
    "pattern": "cannot find symbol",
    "line": "cannot find symbol"
  },
  {
    "file": "scripts/analyze_build_output.py",
    "pattern": "package .* does not exist",
    "line": "package lombok does not exist"
  },
  {
    "file": "src/fix-and-build.ps1",
    "pattern": "BUILD FAILED",
    "line": "BUILD FAILED"
  },
  {
    "file": "tools/Matrixtxt.snapshot.txt",
    "pattern": "cannot find symbol",
    "line": "cannot find symbol"
  },
  {
    "file": "tools/build_matrix.yaml",
    "pattern": "cannot find symbol",
    "line": "cannot find symbol"
  }
]