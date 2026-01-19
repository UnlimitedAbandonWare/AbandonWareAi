# src111_merge15dw6 — pass9x Patch Bundle

This bundle implements items 0~16: Scorecard, duplicate cleanup, Weighted‑RRF, dynamic retrieval, OCR ops, prompt scaffold, sanitizer/citation gate, indexing stability, bootstrap security, Naver defaults, final sigmoid gate, SSE trace hooks.

## How to apply
1) From repo root: `bash scripts/commit_1_remove_app_duplicates.sh`
2) Copy files from this bundle onto the repo root (or unzip at repo root).
3) Edit `settings.gradle` and add: `include("tools-scorecard")`
4) Append `app/src/main/resources/application.yml.append` into `app/src/main/resources/application.yml`.
5) Run: `./gradlew :tools-scorecard:run` (or simply `./gradlew scorecard`) to generate `build/scorecard.json`.
6) Build prompts: `bash scripts/build_prompts.sh` → `out/resume_agent.prompt`

## CI
- Add a step to run `scripts/ci-scorecard.sh` and archive `build/scorecard.pre.json`, `build/scorecard.json`.

## Notes
- Duplicate classpaths (src vs app) removed: 7 → 0 (see `DUPLICATE_CLASS_REPORT.md`).
- Weighted‑RRF exposes: `retrieval.fusion.rrf.k`, `dedupe-by-canonical-key`, `retrieval.fusion.weights`.
- Sanitizer enforces PII masking and minimum authoritative citations (profile: gov+scholar+news).
- OCR is production‑safe with health endpoint `/internal/health/ocr` and fallback `OcrNullService`.