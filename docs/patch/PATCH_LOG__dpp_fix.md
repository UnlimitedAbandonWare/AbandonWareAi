# Patch: Fix DppDiversityReranker API for UnifiedRagOrchestrator
Date: 2025-10-27T21:56:12.056825Z

Patched file:
- /mnt/data/work_src111_smerge15/src/main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java

Key changes:
- Added inner static class `Config(double diversityLambda, int topK)`.
- Added constructor `(Config, dev.langchain4j.model.embedding.EmbeddingModel)`.
- Added overload `rerank(List<T> docs, String query, int k)` (uses reflection to read title/source/score).
- Kept backward-compatible method `rerank(List<Candidate>, int)`.
