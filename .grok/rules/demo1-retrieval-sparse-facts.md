# Retrieval sparse facts (pointer)
sparse/BM25/Pinecone hybrid 얘기가 나오면 docs/RAG_SPARSE_STATUS.md 를 먼저 읽는다. 사실 본문은 그 파일만 둔다.
retrieval.bm25.enabled는 메인 스위치 아님(진짜는 Bm25Props bm25.enabled). cosine→dotproduct 변경과 플래그 켜기 제안 금지(LATER).
바꾸기 전 python -B scripts/retrieval_facts_guard.py 를 실행한다.
