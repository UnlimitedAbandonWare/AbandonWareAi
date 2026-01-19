# Patch Report — 2025-11-05

## What changed
- Added **DppDiversityReranker** (MMR-style) at `src/main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java`.
- Patched **UnifiedRagOrchestrator** DPP block to use the correct FQCN and API.
- Strengthened build-error mining:
  - Added patterns for missing DppDiversityReranker and TreeSerializer escape errors.
  - Added sanitizer to strip `` markers from logs.
  - Dropped resource patterns under `resources/build/errors/patterns.d`.

## Expected effect
- Fixes `error: cannot find symbol class DppDiversityReranker`.
- Prevents noisy `` markers breaking build reporting.
- Guides future contributors via actionable hints in error miner.

## Hashes
- DppDiversityReranker.java: b4f731632263813f