
# Metrics & Dashboard Spec (merge15)

## Labels
- `app`, `env`, `plan`, `route` (web|vector|kg), `mode` (normal|brave|zerobreak|extremez|overdrive)

## Counters
- `budget_exhausted_total{stage}`
- `rerank_semaphore_blocked_total`
- `rerank_guard_fallback_total{reason="timeout|queue_full|budget"}`
- `plan_applied_total{plan}`
- `mode_trigger_total{mode}`
- `canonicalizer_duplicates_removed_total`
- `citation_gate_fail_total`
- `final_sigmoid_gate_pass_total`
- `rulebreak_bypass_total`
- `mcp_tool_invocation_total{tool,status}`

## Gauges / Histograms
- `stage_latency_ms{stage}` (Histogram)
- `end_to_end_latency_ms` (Histogram)
- `retrieval_topk_count{route}` (Gauge)
- `fusion_rrf_sources{route}` (Gauge)
- `nDCG_10` (Gauge; imported from Soak)
- `evidence_rate` (Gauge)
- `embedding_cache_hit_ratio` (Gauge)
- `webcache_hit_ratio` (Gauge)
- `dpp_selected_k` (Gauge)

## Example PromQL
```promql
histogram_quantile(0.95, sum(rate(end_to_end_latency_ms_bucket[5m])) by (le))
rate(rerank_semaphore_blocked_total[5m]) / (rate(rerank_guard_fallback_total[5m]) + 1)
sum(rate(mode_trigger_total[5m])) / sum(rate(plan_applied_total[5m]))
avg(fusion_rrf_sources{route="web"})
```
