# Patch Summary — YAML Duplicate Key Fix
Date (UTC): 2025-10-21T03:55:21.108224Z

**Symptom**
```
org.yaml.snakeyaml.constructor.DuplicateKeyException: found duplicate key fusion
```
- Location: `src/main/resources/application.yml`
- Offending lines: 614 and 650 (two top-level `fusion:` keys within the same document)
- Context hint from logs: `selfask:` at line 548

**Root Cause**
Multiple top-level `fusion:` mappings existed in **the same YAML document**, introduced during prior merges (annotated as `# ==== PATCH: probe + fusion + rag.quality ====` and `### === inserted: rag/fusion/rerank toggles`). SnakeYAML forbids duplicate keys within a mapping.

**Fix**
- Split file by YAML doc separators (`---`).
- In the document starting near `selfask:`, merged the second `fusion:` block (TWPM/CVAR parameters) into the first `fusion:` block (RRR + locale-boost).
- Removed the duplicate header to keep a single `fusion:` mapping per document.

**Net Changes**
- `src/main/resources/application.yml`: 1 duplicate removed; content preserved by merge.

**Verification**
- Static check: no remaining duplicate top-level keys per document.
- Target lines now contain a single consolidated `fusion:` block with `rrf`, `locale-boost`, `fuser`, `twpm`, and `cvar` keys.

**Guardrail**
When adding new sections, prefer:
- One `fusion:` per document; extend existing block.
- If profiling is needed, use `---` and `spring.config.activate.on-profile` instead of duplicating the key.
