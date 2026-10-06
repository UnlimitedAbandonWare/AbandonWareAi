<!-- BEGIN DEMO1-GRAPH-RAG-HYBRID-SEAM -->
## Graph RAG Hybrid Failure Isolation

- Vector + BM25 + Graph(neo4j) + Web lane은 **독립 후보 lane**이다 — 한 lane의
  장애가 다른 lane 후보를 지우지 않는다. 상세 계약:
  `docs/design/UNHOOKED_SEAMS_CONTRACT_SPEC.md` §S3. 프로브:
  `python -B scripts/probe_unhooked_seams_guard.py --dry-run`.
- G1 lane 분리 기록: `UnifiedRagOrchestrator` trace의 `vector`/`bm25`/`fused`
  리스트를 지운 채로 병합하지 않는다.
- G2 lane 실패 수렴: 예외는 그 lane의 빈 후보 + 실패 trace로 변환하고 융합을
  계속한다. `toDocsOrEmpty`·"VECTOR-EMERGENCY" 재시도가 선례 — 동일 패턴을
  graph/bm25 lane에도 적용한다.
- G3 `WeightedReciprocalRankFuser`의 `fail-soft stage=` 래핑을 유지 — 융합기가
  예외를 위로 던지는 경로를 만들지 않는다.
- G4 `retrieval.vector.required`는 기본 `false`를 유지 — vector lane 부재가
  BM25/웹 후보까지 기아시키는 fail-closed는 명시 설정일 때만.
- G5 인용 게이트/리랭커 전량 탈락은 기아가 아니라 **미검증 강등 주입**이다
  (spec §R2): 수집 성공 후 `web:0, vector:0` 프롬프트는 계약 위반.
  topDocs 복원 선례(`rerankInput`/`fused` limit)와 같은 방향으로 처리.
<!-- END DEMO1-GRAPH-RAG-HYBRID-SEAM -->
