---
name: demo1-retrieval-sparse-facts
description: "Use when sparse retrieval, BM25, or Pinecone hybrid is discussed. Read the SSOT, then run the facts guard before any change."
---

# Retrieval sparse facts

## When
Use this when the ask mentions sparse retrieval, BM25, Pinecone hybrid, or a dense-index metric change.

## Facts
Read `docs/RAG_SPARSE_STATUS.md` only. Do not copy that document into this skill.

## Do not
1. retrieval.bm25.enabled는 메인 스위치 아님. 그 이름을 메인 스위치로 부르지 않는다.
2. cosine을 dotproduct로 바꾸자는 제안을 하지 않는다.
3. sparse 플래그 켜기 제안 금지(LATER).
4. SSOT 본문을 룰, 스킬, 메모에 복붙하지 않는다.

## Check
`python -B scripts/retrieval_facts_guard.py --root . --json`

`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Retrieval-Facts-Guard.ps1 --json`

## When the judgment may change
Only after an evaluation bundle exists and a dense versus independent BM25 plus RRF comparison reproduces an improvement.
