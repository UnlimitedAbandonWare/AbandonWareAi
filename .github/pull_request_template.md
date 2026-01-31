# PR Title: [merge16] Unified Orchestration + Observability & Guardrails

## 🔎 Summary
- Introduces `UnifiedRagOrchestrator` (Web/Vector/KG/BM25 → Weighted‑RRF → Bi‑Encoder → ONNX).
- Adds probe-friendly controller, Plan‑DSL hook, and JSON schema guardrail placeholder.
- Expands configs for caching, hedging, rate limiting, and domain allowlists.

## ✅ Checklist (S/C/E/O/T — score presence 1/0)
- [ ] **Source**: New/updated classes placed under `src/main/java/.../orchestrator/`
- [ ] **Config**: `application.yml` keys added with sane defaults (toggle‑off by default)
- [ ] **Endpoint**: `/api/rag/query`, `/api/rag/probe` documented in OpenAPI draft
- [ ] **Obs/Resilience**: correlation‑id propagation, timeouts/hedging, cache, allowlist
- [ ] **Test/Probe**: Smoke test + manual probe recipe in PR notes

> **Implementation Score** = (checked count / 5) × 20%

## 🔧 Risk & Rollout
- Feature gated behind `rag.pipeline.*` toggles.
- Backward compatible: no removal, only additive endpoints/classes.
- Rollout plan: canary (10%), then progressive ramp.

## 🧪 Manual Probe (curl)
```bash
curl -s -X POST http://localhost:8080/api/rag/probe \
  -H "Content-Type: application/json" \
  -d '{"q":"what is weighted rrf"}'
```

## 📝 Notes
- If ONNX not available, pipeline gracefully degrades to Bi‑Encoder / fusion only.
- Planner & Self‑Ask are optional and no‑op without underlying implementations.