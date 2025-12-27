# merge24 Runtime Notes
- Java 17 / Spring Boot 3 baseline.
- ONNX CrossEncoder gated by `onnx.enabled` (default true).
- Semaphore concurrency: `zsys.onnx.max-concurrency`.
- Feature flags under `features.*`.
- Probe endpoint: POST /api/probe/search (guarded by property).
- Soak endpoint: GET /internal/soak/run?k=10&topic=default.
- Guards chain: AnswerLengthGovernor, CitationGate, FinalSigmoidGate, EmbeddingDriftGuard (placeholders).
- Plans: resources/plans/default_qa_flow.yaml
