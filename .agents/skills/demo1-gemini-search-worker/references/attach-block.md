# 장착 블록 — 지시서에 그대로 붙인다
[GEMINI-SEARCH-WORKER] 장착: gemini_search_worker
- 역할: 공식 문서·최신 사양 교차검증만. 우선 도메인 <지시서가 지정>.
- 호출 상한 이 지시서 <N>회, 401/403/429 재시도 금지, 카드 ≤3KB.
- 결과는 참고 증거. 로컬 HEAD 소스·테스트가 우선, worker 동의 ≠ 검증 성공.
- 확인 날짜·적용 버전·출처 URL을 EVIDENCE에 남긴다.
- 검색 결과를 제품 프롬프트·다른 회사 메인 모델 입력으로 넘기지 않는다.
실행 SSOT: python -B scripts/gemini_search_worker.py search "<질문>" --domains <도메인> --depth <L1|L2|L3> --brief-id <taskId>
