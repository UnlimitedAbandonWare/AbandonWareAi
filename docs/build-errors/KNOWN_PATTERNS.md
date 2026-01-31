# Known Build Error Patterns (auto-harvested)

- **NON_ZERO_EXIT_BOOTRUN** in `build-logs/2025-10-18-bootRun.log` → _Application failed to start during bootRun; see preceding Spring error_
- **NON_ZERO_EXIT_BOOTRUN** in `build-logs/error_patterns_detail.json` → _Application failed to start during bootRun; see preceding Spring error_

## Newly added pattern handlers (2025-10-21)
- **MISSING_OPENAI_MODEL** — Add default for openai.api.model or set OPENAI_API_MODEL env var
- **BEAN_CREATION_GPTSERVICE** — Property injection failed; ensure model/key properties present
- **NON_ZERO_EXIT_BOOTRUN** — Application failed to start during bootRun; see preceding Spring error
