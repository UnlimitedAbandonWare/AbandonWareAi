## src_54
- feat(rag): HybridRetriever 실구현(BM25 + Title + Recency + MMR, LRU 캐시)
- feat(web): TavilyWebSearchRetriever 실구현(언어 자동 선택)
- feat(fusion): RRF 융합 유틸(댐핑/가중치/중복 제거, JSON 로드)
- feat(rerank): CE(heuristic/ONNX) · SBERT · ColBERT-lite · SBERT-pre · ColBERT‑T 지원
- feat(tune): RRF Weight Tuner(JSONL → rrf_weights.json)
- chore: 환경변수 플래그로 기능 온·오프 및 폴백 설계
