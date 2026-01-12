# 30_CHECKLIST.md — 규칙 준수 체크

- [x] 버전 순도(LC4J=1.0.1 단독) 확인 (build.gradle 정합)
- [x] 핸들러 Spring Bean 주입 (신규 Tomcat 커스터마이저 @Configuration)
- [x] 프롬프트 규약 (ChatService 내 문자열 결합 금지) — 위반 흔적 없음
- [x] 체인 순서 (Hybrid→SelfAsk→Analyze→Web→VectorDb) 보존 — 체인 관련 파일 미변경
- [x] 설정 파일 미변경 (application*.yml|yaml|properties 그대로 유지)
- [x] Content 가드 (직접 구현/0.2.x 스타일 호출 없음) 유지
