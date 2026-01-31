# Orchestrator Hook + Soak/Probe A/B — merge156-A

This patch wires **Softmax‑K** allocation into the orchestrator (opt‑in via `${input.k_alloc}`),
and upgrades **Probe**/**Soak** endpoints to run A/B comparisons (fixed vs. Softmax‑K)
and report latency + allocation stats.

## (a) Orchestrator Hook
- File: `com/abandonware/ai/agent/orchestrator/Orchestrator.java`
  - On `execute(...)`, we compute `k_alloc` if absent and `input.query` exists:
    `com.abandonware.ai.agent.orchestrator.KAllocRuntime.compute(query, 24, 0.7, intent)`
  - Injects `k_alloc` into both **input** (best-effort) and **state** maps.

- Advisor: `com.abandonware/ai/agent/orchestrator/KAllocRuntime.java`
  - Uses `QueryComplexityClassifier` + `KAllocationPolicy` (Softmax Util) to obtain `{web,vector,kg}` K.

## (b) Soak/Probe A/B
- `POST /api/probe/search`
  - Body: `{ "query": "...", "intent": "news|ocr|...", "k": 24 }`
  - Returns both **A**(fixed) and **B**(Softmax‑K) allocations + latency.
- `GET /internal/soak/run?k=24&topic=all`
  - Runs over 5 built‑in sample queries and aggregates average allocations for A vs. B.

> NOTE: Doc retrieval in this sample remains stubbed (Tavily shim + BM25 demo); accuracy metrics
> like **nDCG@10**/**evidence_ratio** require gold labels or real retriever outputs.

## Config (optional)
- JVM/System prop: `-Dretrieval.kalloc.temperature=0.7` (not strictly required; code uses 0.7 default)
- To integrate with flows, reference `${input.k_alloc.web}` etc. in tool args.

## Files touched
- Added:
  - `com/abandonware/ai/agent/orchestrator/KAllocRuntime.java`
- Modified:
  - `com/abandonware/ai/agent/orchestrator/Orchestrator.java` (additive block `[merge156-A]`)
  - `com/example/lms/probe/SearchProbeController.java` (A/B response)
  - `com/example/lms/api/internal/SoakApiController.java` (A/B aggregate)
