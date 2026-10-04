# RAG sparse 상태

확인일: 2026-10-04 KST. 작업 진입 시 HEAD: 906579b2ff4a227962c7ec237ad83c97359c3f65.
판정: **LATER** — 검색품질 비교는 **NOT_RUN**이며, 개선 효과를 주장할 근거가 없다.
평가 보고서: [demo1_sparse_need_assessment_20261004.md](C:/Users/nninn/Downloads/demo1_sparse_need_assessment_20261004.md).

## 현재 메인 /chat 경로

- LMS 체인은 dense(Pinecone) 검색 경로가 정적으로 연결되며, 조사한 메인 체인에는 독립 BM25 leg가 없다. 웹/KG 등 다른 leg도 존재한다.
- 근거: `main/java/com/example/lms/config/RetrieverChainConfig.java:42-95`, `main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java:528-540,668-669`.
- 정적 연결과 기본값은 실제 호출이나 effective 설정의 관측을 뜻하지 않는다. 이번 작업의 라이브 호출은 0/0이다.

## sparse 후보 3종

1. **Pinecone sparse / 단일 인덱스 hybrid**: 기존 adapter는 cosine·dense를 검증한다. cosine 인덱스에 sparse 옵션만 붙이는 안은 무효이며, 단일 인덱스 dense+sparse에는 dense + dotproduct가 필요하다. 기존 검증은 유지한다.
   - 근거: `main/java/com/example/lms/service/vector/PineconeVectorStoreAdapter.java:43-60`; [Pinecone 공식 단일 인덱스 hybrid 계약](https://docs.pinecone.io/guides/search/hybrid-search/single-index) (2026-10-04 확인).
   - `application-local.yml`의 1536d/cosine 표기는 기대값을 설명하는 주석이다. 원격 index metadata는 **미관측**이다.
2. **Lucene BM25**: `Bm25Props`의 `bm25.enabled` 선언 기본값은 true다. `bm25.autoIndex=false`는 자동 재색인만 생략하며 검색 비활성을 뜻하지 않는다. 메인 LMS 체인에 연결되지 않고 별도 `/api/probe/search` 체인에서 호출된다.
   - 근거: `main/java/com/abandonware/ai/agent/config/Bm25Props.java:9`, `main/java/com/abandonware/ai/agent/service/rag/bm25/Bm25IndexService.java:35,47-52`, `main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java:103-106`.
   - probe endpoint 자체의 선언 기본값은 `probe.search.enabled=false`다 (`main/java/com/abandonware/ai/probe/SearchProbeController.java:20-21`). 현재 bean·색인 내용·effective 플래그는 미관측이다.
3. **Unified / integrations BM25**: 별도의 구현이 존재한다. Unified는 요청의 `useBm25`와 optional `Bm25Index` bean이 필요하며, integrations는 저장소 문서용 로컬 색인을 lazy build한다.
   - 근거: `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java:232-233,2888-2911`, `main/java/com/abandonware/ai/agent/integrations/HybridRetriever.java:44-65,106-123`.
   - 존재만으로 메인 /chat 연결이나 Pinecone와 동일한 corpus/chunk ID를 입증하지 않는다.

## 사용되지 않는 설정과 오해 방지

- `Bm25Config`는 Spring bean이 아닌 JVM system-property 컨테이너다. active `main/java`에서 사용처가 없고 기존 설정 테스트만 사용한다.
- `retrieval.bm25.enabled`의 기본값 false는 실제 Lucene `bm25.enabled`의 기본값 true와 다른 설정이다. 전자를 켜도 메인 검색은 바뀌지 않는다.
- `main/resources/application.properties:484-485`의 `rag.hybrid.weight.vector` / `rag.hybrid.weight.keyword`는 active Java 소비 코드가 확인되지 않았다. 실제 검색 비율 또는 Pinecone alpha로 해석할 수 없다.
- 해당 properties 파일에는 비UTF-8 바이트가 섞여 있으므로 이번 작업에서 편집하지 않았다. merge16·플래그 값·검색 배선·Pinecone payload도 변경하지 않았다.

## 다음 단계 1개

같은 corpus/chunk ID·소유 필터의 비민감 평가 bundle(정답 chunk ID, corpus 버전, cached dense 순위, 기존 BM25 순위/평가용 corpus)을 확보한 뒤, **10-20 질의로 dense-only 대 독립 BM25+RRF를 오프라인 비교**한다.
동일 query/corpus/k/필터로 top-5 적중률·정답 recall·질의군별 승패를 기록한다. dense top-k의 키워드 재정렬은 독립 BM25 후보 추가와 구별한다.
bundle이 없으면 NOT_RUN을 유지한다. 이번 판정은 플래그 활성화, 새 인덱스, 재색인 또는 메인 체인 연결을 승인하지 않는다.
