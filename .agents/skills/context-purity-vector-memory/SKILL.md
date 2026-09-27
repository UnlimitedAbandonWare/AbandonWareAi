---
name: context-purity-vector-memory
description: Normalize cleanup metrics, remove generated legacy duplicate contamination
license: project-internal
metadata:
  version: "1.0.0"
  author: "abandonware-context-purity"
  last-updated: "2026-05-20"
allowed-tools: read_file edit_file run_tests shell
---

# Context Purity Vector Memory

## When To Use

- The user asks to remove unnecessary files, clean legacy context, normalize cleanup metrics, or store vector DB memory.
- The tree contains build outputs, `.gradle`, class files, jars, backups, root build reports, or patch reports.
- The same FQCN, resource key, or file hash appears in multiple source roots.

## Non-Negotiable Directives

1. Generated artifacts are deleted first. Do not archive generated artifacts.
2. Preserve `gradle/wrapper/gradle-wrapper.jar`.
3. Exact duplicate non-active files are deleted or excluded, not wrapped.
4. Same FQCN with different content is quarantined only with `evidence_needed` and a verification path.
5. Do not add wrappers, duplicate helpers, duplicate routes, or legacy adapters to make old context compile.
6. Active build graph wins over docs.
7. The vector DB should ingest canonical memory docs, not patch logs, build logs, class files, jars, or raw dumps.
8. Never log secrets, owner tokens, API keys, raw stack dumps, or large payload text.

## Active Source Roots

- `main/java`
- `main/resources`
- `app/src/main/java_clean`
- `app/src/main/resources`

## Required Workflow

1. Run Gradle sourceSet verification before changing code.
2. Generate `__reports__/context-purity-decisions.tsv`.
3. Delete only generated artifacts unless the user explicitly approves legacy/report cleanup.
4. Keep uncertain legacy material out of vector ingest with `QUARANTINE` or `EVIDENCE_NEEDED`.
5. Reuse existing VectorPoisonGuard, VectorStoreService, TrainRagIngestService, and FederatedEmbeddingStore flows.
