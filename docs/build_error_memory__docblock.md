# Build Error Memory — DocBlockCorruption

This repo keeps an internal memory of common build error patterns. This patch adds the **DocBlockCorruption** family:

## Symptoms (examples from Gradle compile)
- `'{'' expected` right after a sentence like `enum to satisfy ... */`
- `class, interface, enum, or record expected` around lines that begin with prose such as `class replaces ...`
- `illegal character: '#'` or `illegal character: '\u2011'` where Javadoc `{@link Optional#empty()}` or non‑ASCII punctuation leaked into code
- `illegal start of type` where a `* @param` line appears without an opening comment

## Heuristic detectors
- Line starts with `*` but we are not inside a `/* ... */` block
- Line starts with `class ` followed by a lower‑case word (`class is`, `class for`, `class does ...`)
- A line contains `enum to satisfy` and ends with `*/` while not inside a comment
- A single line contains two `/**` sequences

## Remediations (automated)
1. **Deduplicate inline `/**`** — keep the first, drop the rest.
2. **Stray doc lines → line comments** — prefix with `// ` when outside a block (covers `* @param`, `class is ...`, etc.).
3. **Stray `*/` outside a block** — neutralise as `* /` (and restore to `*/` if the same line also opened `/**`).
4. **No code changes** — only comment/text placement adjusted.

## Mapping to memory codes
- `IllegalStartOfType`, `ClassOrInterfaceExpected` ⇢ **DocBlockCorruption**
- `IllegalCharacter` (non‑ASCII or `#`) ⇢ **DocBlockCorruption**
