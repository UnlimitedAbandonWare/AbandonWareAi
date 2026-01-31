# Build Error Pattern Report — 2025-10-21 (src111_mergsae15)

## Input
- Developer log snippet shows SnakeYAML error during boot:
  - `DuplicateKeyException ... found duplicate key rag`

## Pattern matched
- **DUP_YAML_DUP_KEY** (new)
  - Regex: `DuplicateKeyException.*duplicate key\s+(?<key>[A-Za-z0-9_.-]+)`
  - Captured `key=rag`

## Fix applied
- Normalized `src/main/resources/application.yml`:
  - Removed duplicate top-level sections and merged their mappings.
  - Dropped stray document separators `---` at column 0.
  - Ensured boolean types are legit (`probe.search.enabled: true`).

## Verification (static)
- After normalization, top-level duplicate count: **0**.
- File preserved: all unique subkeys kept; last-wins for conflicts.

> Runtime verification should be done with `./gradlew bootRun` and the Probe/Soak endpoints per Jammini inventory.
