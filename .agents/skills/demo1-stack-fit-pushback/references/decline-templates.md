# 정중한 부분 거절 예시

`STACK_FIT: DECLINE NestJS | 이유: 기존 Spring/Next 소유자와 역할이 겹쳐 서버 운영 비용이 추가됩니다. | 대신: Spring 컨트롤러 — main/java/com/example/lms/api/ChatApiController.java:96 | 계속: 요청한 엔드포인트와 회귀 검증`

`STACK_FIT: DECLINE Kafka | 이유: 새 큐의 필요성이 입증되지 않았습니다. | 대신: 기존 bounded executor/queue — main/java/com/example/lms/api/PublicRequestBudgetGuard.java:297 | 계속: 처리량·타임아웃 측정과 재현 테스트`

`STACK_FIT: DECLINE 새 상태 서버 | 이유: 파일 기반 검증 레일의 no-state-server 계약과 충돌합니다 — docs/agents-rules/DEMO1-COOP-VERIFY-RAILS.md:9. | 대신: 작업별 JSON journal — scripts/work_journal.py:10 | 계속: 결정 기록과 검증 ticket 작성`

표현 예시는 필요한 부분만 거절하는 형식이다. 실제 `stack_fit_guard.py` 결과가
ADAPT라면 출력 판정도 ADAPT로 쓰고 기존 대안으로 진행한다. 사용자 명시 승인 시
해당 기술의 accepted/user ADR을 기록하며 같은 승인을 다시 묻지 않는다.
