# Embedding failover advanced

This repository includes hardening around **embedding** and **vector ingestion** to prevent silent data loss when a local embedding backend (e.g., Ollama) or a vector writer is unhealthy.

## What this is trying to prevent

Typical failure modes in RAG systems:

- Local embedder is down → embedding calls fail or return empty embeddings.
- Vector writer (Pinecone/Upstash/etc.) intermittently fails.
- Errors are swallowed (fail-soft) → ingestion appears "successful" but nothing is stored.
- Empty vectors get cached → system keeps producing empty embeddings even after recovery.

## Mechanisms implemented

- **Port failover**: if the configured Ollama embed endpoint is unreachable, we can retry on the sibling port (11435 ↔ 11434).
- **Optional explicit fallback URL**: `embedding.base-url-fallback` can be set to a second Ollama endpoint.
- **Fast-fail (breaker-lite)**: after repeated local failures, temporarily skip local Ollama and route directly to the configured fallback embedding provider.
- **Health-check preflight** (optional): before issuing local embedding calls, a lightweight check can quickly detect a down embedder.
- **No caching of empty vectors**: `EmbeddingCache` will not store empty embeddings.
- **Strict write flag**: when metadata contains `strict_write=true`, vector upsert errors are *not* swallowed.
- **VectorStoreService safety**: `vectorstore.require-non-empty-embedding=true` makes buffered flush throw if embeddings are empty/mismatched.
- **Diagnostics endpoint**: exposes runtime state for Ollama failover / fast-fail.

## Configuration

Below is a minimal example of the relevant configuration keys.

```yaml
embedding:
  provider: ollama
  base-url: http://localhost:11435/api/embed
  # Optional explicit local fallback (in addition to port failover)
  base-url-fallback: http://localhost:11434/api/embed
  model: qwen3-embedding
  dimensions: 1536

  port-fallback:
    enabled: true

  # Optional: keep the model warm in Ollama (string passed to Ollama)
  ollama:
    keep-alive: "5m"

  fast-fail:
    enabled: true
    fail-threshold: 1
    cooldown-seconds: 60

    # Optional health preflight
    health:
      enabled: true
      mode: version       # version | tags | tags_ps | embed_probe
      timeout-ms: 750
      ok-ttl-seconds: 30

  fallback:
    enabled: true
    provider: openai
    api-key: ${OPENAI_API_KEY}
    model: text-embedding-3-small
    dimensions: 1536
```

## Diagnostics endpoints

- `GET /api/diagnostics/embedding`
  - Returns a snapshot of embedding backend status (fast-fail window, streak, health cache, urls, etc.)

- `POST /api/diagnostics/embedding/reset`
  - Resets fast-fail state and clears health cache.
  - If an admin token is configured in `DomainProfileLoader`, the request must include:
    - Header: `X-Admin-Token: <token>`

## Ingestion safety knobs

- `vectorstore.require-non-empty-embedding` (default: `true`)
  - When enabled, `VectorStoreService` throws if `EmbeddingModel.embedAll()` returns:
    - a null list
    - a list size that doesn't match the number of segments
    - any empty vectors

Buffered segments are also stamped with metadata:

- `strict_write=true`

This forces vector upsert errors to propagate rather than being swallowed, so the buffer can retry safely.
