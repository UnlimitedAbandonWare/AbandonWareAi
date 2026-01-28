# BUILD FIX REPORT — src111_merge15s

- Date: 2025-10-17
- Fixes:
  - Normalize Java regex escapes in string literals: `\p{..}`, `\s`, `\d`, `\b`, etc.
  - Normalize hyphen escapes: `\-` → `\\-` in character classes.
  - Fix `.split("\s+")` → `.split("\\s+")`.
  - Add import for `Bm25LocalIndex` in `Bm25LocalRetriever`.

## Patched Files (sample)
```

```

## Pattern Summary
See `BUILD_ERROR_PATTERNS.json`.
