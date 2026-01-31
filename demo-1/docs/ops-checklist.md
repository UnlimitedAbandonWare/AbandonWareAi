# Rollout / Rollback Checklist (demo-1)

- Preflight: `onnx.enabled=false`, `retrieval.vector.enabled=false` -> deploy -> turn on sequentially.
- Health: `/actuator/health` ok, logs include `[sessionId/requestId]`.
- Probe: `POST /api/probe/search` with `admin-token` returns trace.
- Soak: run `/internal/soak/run` if available.
- Rollback: set `onnx.enabled=false`, `gate.*.enabled=false` or revert build.

Metrics to capture:
- Recall@10, citation count, generation confidence (FinalSigmoidGate), latency p50/p90, external calls ratio.