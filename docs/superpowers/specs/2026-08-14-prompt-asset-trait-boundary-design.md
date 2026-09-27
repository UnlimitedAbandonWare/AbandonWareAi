# Prompt Asset Trait Boundary Design

Date: 2026-08-14  
Candidate: A03  
Cluster: `prompt-asset-id-containment`  
Decision: proceed with the existing identifier validator at the public trait resolver boundary

## Current evidence

- `ChatRequestDto.traits` is a public JSON field.
- `ChatWorkflow` passes the field to `PromptAssetService.renderTraits`.
- `renderTraits` calls `resolveTraitText` for every supplied identifier.
- `resolveSystemPromptText` already rejects unsafe identifiers with `isSafePromptId`.
- `resolveTraitText` constructs classpath locations from the trimmed identifier without the same check.
- The selected root `main/java` sourceSet owns `PromptAssetService`; the target and existing contract test were clean at selection time.

```text
ChatRequestDto.traits
  -> ChatWorkflow
  -> PromptAssetService.renderTraits
  -> PromptAssetService.resolveTraitText
  -> ResourceLoader
```

## Root-cause hypothesis

The public trait resolver omitted the identifier-boundary check already used by the adjacent public system-prompt resolver. Therefore an identifier containing traversal syntax can be normalized by the resource layer and resolve outside the intended trait directories.

## Options considered

1. Reuse `isSafePromptId` immediately after trimming in `resolveTraitText` (selected). This is one production guard, preserves valid identifiers and cache semantics, and aligns both public asset boundaries.
2. Canonicalize every resolved resource and compare it with an allowed classpath root. This is broader, resource-protocol-specific, and would require a larger test matrix for exploded directories and JAR resources.
3. Introduce a configuration-backed trait allowlist. This changes deployment/configuration contracts and creates migration work for existing assets.

Option 1 is the smallest reversible change that addresses the observed omission without adding a dependency, property, wrapper, or alternate prompt path.

## Contract

- Blank, traversal-bearing, absolute, overlong, or otherwise unsafe trait identifiers return `null` before cache lookup or resource resolution.
- Existing safe trait identifiers retain their current lookup order and rendering behavior.
- Trusted literal system-prompt behavior remains confined to `resolveTrustedSystemPromptText`.
- No prompt text, query, session state, credential, or provider response is logged or persisted.

## Success criteria

- A focused characterization test is RED because `../system/projection.final` currently resolves a non-trait asset.
- Adding the existing guard makes that test GREEN.
- Existing public/trusted prompt asset contract tests remain GREEN.
- Related prompt-boundary tests, compilation, sourceSet/LangChain4j checks, `:app:classes`, and the full root suite remain GREEN.
- The final diff changes only the design/plan, one focused test hunk, one production guard, and report artifacts created for this goal.

## Non-goals

- No prompt architecture refactor, resource-loader replacement, trait manifest, cache redesign, provider call, or property change.
- No changes to `sessionId`, `ctx.memory`, `opnessl`, `openssl`, dependencies, Spring Boot, Gradle, or LangChain4j.

## Rollback boundary

If the RED test does not fail by returning the cross-directory asset, if a safe trait regresses, if the target preimage changes, or if focused verification exposes an intentional public traversal contract, hold A03 and remove only this Work Unit's test/guard hunks. Do not touch unrelated dirty files.
