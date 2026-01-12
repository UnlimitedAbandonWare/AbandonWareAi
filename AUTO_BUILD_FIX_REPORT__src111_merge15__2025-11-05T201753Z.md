# AUTO BUILD FIX REPORT — src111_merge15

UTC: 2025-11-05T20:17:55.403173Z

## What I fixed
- **application.yml (app module):** merged duplicate top-level `retrieval:` blocks into one.  
  → Prevents `DuplicateKeyException: found duplicate key retrieval` at boot.  
- **Gradle preflight guard:** added `yamlTopLevelGuard` task and wired it to run before compilation.  
  → Fails fast if any top-level key (e.g., `retrieval`) is duplicated in `application.yml`.
- **Build error pattern catalog:** added `yaml.duplicate_key` detector to **tools/build_error_guard.py** and updated **BUILD_ERROR_PATTERNS.json** with counts and example.

## Files touched
- `src/app/src/main/resources/application.yml`  (duplicate key merged)
- `src/app/build.gradle.kts`  (YAML guard injected)
- `src/tools/build_error_guard.py`  (catalog entry added)
- `src/BUILD_ERROR_PATTERNS.json`  (category + example added)

## How the new guard works
Run:
```
./gradlew :app:preflight
```
You should see **`YAML Guard: OK`**.
If any duplicate top-level key is found, the build fails with a clear message like:
```
YAML duplicate top-level keys detected: retrieval in application.yml
```

