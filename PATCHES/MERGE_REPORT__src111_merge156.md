# MERGE REPORT — src111_merge156

This bundle integrates legacy formulas into src111 with opt-in wiring.

## Added
- strategy/SoftmaxUtil.java — numerically-stable softmax with temperature.
- service/rag/calibration/MLCalibrationUtil.java — score normalization utilities (ported).
- service/rag/fusion/PowerMeanCombiner.java — weighted power mean combiner.
- guard/SigmoidQualityGate.java — sigmoid final gate (optional).
- service/retrieval/util/TimeBudget.java — time budget helper (ported).

## Patched
- strategy/RetrievalOrderService.java — added `recommendK(int baseK, String query, double T)` for softmax-based K allocation.
- service/onnx/OnnxCrossEncoderReranker.java — added env/system property aware `resolveBudgetMs()` helper (non-invasive).

## How to enable (opt-in)

application.yml (example):

```yaml
retrieval:
  kalloc:
    temperature: 0.7   # Softmax temperature (lower => more deterministic)

onnx:
  enabled: true
  maxConcurrency: 4
  timeBudgetMs: 120    # or set env ONNX_TIME_BUDGET_MS
```

Usage (code):

```java
// K allocation
strategy.RetrievalOrderService order = new strategy.RetrievalOrderService();
java.util.Map<String,Integer> km = order.recommendK(24, query, 0.7);

// Final sigmoid gate
guard.SigmoidQualityGate gate = new guard.SigmoidQualityGate(6.0, 0.6, 0.90);
if (!gate.accept(combinedScore)) { /* safe fallback */ }
```
