# BUILD_FIX_NOTES (src111_merge15)

This repository was patched with:
- `scripts/build/ci_build.sh`: robust CI build runner with log capture and auto-retry.
- `scripts/build/parse_build_log.py`: parser that stores matched error patterns to `.build/error_patterns.jsonl`.
- `scripts/build/error_patterns.yaml`: canonical error patterns and hints (append-only).
- `src/main/resources/application-patch.yml`: operational knobs for ONNX, OCR, cache, vector routing, probe, SSE, KG, rate limiter, soak, translate, naver.

## Usage

```bash
scripts/build/ci_build.sh
```

Artifacts:
- Logs under `.build/logs/`
- Error summary under `.build/out/errors-*.md`
- Pattern memory under `.build/error_patterns.jsonl`
