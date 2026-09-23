# Compile + existing verify smoke (manual)

## Steps
1. Stay in Project Root (this workspace).
2. Prefer in-repo markers: `gradlew.bat compileJava` / matching `smoke_*` / `verify_*` scripts already present — do not install Playwright/Ruff/new runners unless already used.
3. For frontend under `frontend/`: use existing `npm` lint/test scripts only if the change touches that tree.
4. Paste the exact command exit codes; on failure, stop and show the first actionable error.
