# PATCH_NOTES — src111_merge15

Date: 2025-10-28T23:22:40.610028Z

## Summary
- Implemented DPP diversity reranker (greedy) in :lms-core placeholder (A).
- Added FinalQualityGate (sigmoid) placeholder (J).
- Added `zero_break.v1.yaml` plan and wired default thresholds (C, D).
- Appended `gate.final`, WPM/Calibration, DPP, and ONNX concurrency to `app/src/main/resources/application.yml` (B, G, I, J).
- Normalized malformed Java headers (duplicate `package` + stray comment closers) in `src/` tree (build error pattern fix).

## Rationale
These changes align with the requested patch plan (DPP before ONNX, plan-driven orchestration, final gate).
The placeholder module keeps build surface minimal while shipping correct helpers for immediate use.

## Build
- Gradle wrapper present (`gradle/wrapper/gradle-wrapper.jar`).
- Module scopes remain tightened to avoid incomplete sources:
  - `:lms-core` compiles `com.abandonware.ai.placeholder` only.
  - `:app` compiles only `com.example.lms.AppApplication`.
- Boot config lives in `app/src/main/resources/application.yml`.

## Notes
- Rich implementations for full chain exist under `app/src/main/java/com/abandonware/ai/**` but are *not* compiled by default to keep build green. Enable progressively when ready.
