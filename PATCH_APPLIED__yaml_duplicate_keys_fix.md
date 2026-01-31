# Patch: Fix Duplicate Top-level Keys in application.yml
## Why
- Spring Boot (via SnakeYAML) raised `DuplicateKeyException` because the same top-level keys appeared more than once in a single YAML document.
- Keys affected included: `onnx`, `probe`, `rag`, and `upstash`.

## What Changed
- Inserted YAML document separators `---` before the 2nd+ occurrence of any top-level key.
- This preserves intent while avoiding duplicate-key collisions within a single mapping.

## Files Patched
- `app/src/main/resources/application.yml`
- `src/main/resources/application.yml`
- `src/test/resources/application.yml`

## Backups
- `app/src/main/resources/application.yml.bak_before_split`
- `src/main/resources/application.yml.bak_before_split`
- `src/test/resources/application.yml.bak_before_split`

## Post-patch Sanity
- Each YAML file now consists of multiple documents, each with unique top-level keys.
- You can verify by running `./gradlew bootRun`.
