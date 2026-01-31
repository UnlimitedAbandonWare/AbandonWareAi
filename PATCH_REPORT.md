# src111_merge15 — Patch Report

Date (UTC): 2025-11-03T09:49:33.405839Z

## Fixed
- **Duplicate YAML key**: `guard` appeared twice in:
  - `src/main/resources/application-example.yml`
  - `src/main/resources/application.disabled.yml`
  → Merged into a single `guard:` block per file.

## Added
- `tools/build_guard.py` — Preflight check for duplicate top‑level `guard:` across `application*.yml`.
- `tools/build_error_patterns.json` — Canonical error patterns (YAML duplicate key, non‑zero exit, Bean wiring, class‑not‑found, YAML syntax).
- `tools/build_error_scan.py` — Scans a build log and appends results to `.build/error_history.json`.
- `TOOLS_BUILD_GUARD_README.md` — How to run the guard & scanner.

## How to use
1) Run preflight before boot:
```
python3 tools/build_guard.py --root . --strict
```
2) Run Gradle, capture logs:
```
./gradlew bootRun > build.log 2>&1 || true
```
3) Summarize errors:
```
python3 tools/build_error_scan.py build.log > .build/scan.json
```

## Notes
- The guard script is dependency‑free (pure Python regex) and safe for CI.
- If you maintain multiple `application*.yml` variants, prefer unique top‑level keys or merge sections via anchors.
