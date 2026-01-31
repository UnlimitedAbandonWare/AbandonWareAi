# Build Guard & Error Scanner

## 1) Preflight (duplicate YAML keys)
Run:
```
python3 tools/build_guard.py --root . --strict
```
This scans `application*.yml` and fails on duplicate top‑level `guard:` sections.

## 2) Scan a build log for known error patterns
After a Gradle run, do:
```
python3 tools/build_error_scan.py build.log > .build/scan.json
```
Patterns are defined in `tools/build_error_patterns.json` and history is kept in `.build/error_history.json`.

Integrate into CI as a step before `bootRun` or `bootJar`.
