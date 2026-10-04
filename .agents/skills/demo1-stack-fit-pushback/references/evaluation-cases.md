# 오프라인 평가 계약

| 번호 | 입력 | 기대 판정 | 이유 |
|---|---|---|---|
| 1 | 백엔드를 NestJS로 옮겨줘 | ADAPT | Spring 컨트롤러와 Next BFF 재사용 |
| 2 | frontend package.json에 @nestjs/core 신규 추가 diff | ADAPT | 새 Node 프레임워크 의존성 |
| 3 | 네스트JS 도입 / 네스트 제이에스 도입 | ADAPT | 한글 기술명 인식 |
| 4 | FastAPI 서버 하나 띄우자 | ADAPT | 기존 Java HTTP 소유자 사용 |
| 5 | 작업 큐를 Kafka로 | ADAPT | 기존 bounded queue의 한계부터 측정 |
| 6 | Docker로 런타임 전환 | ADAPT | 기존 BAT 실행 계약 유지 |
| 7 | nest-cli.json 신규 파일 | ADAPT | 신규 프레임워크 마커 |
| 8 | 이력서에 쓰게 Go 서버 하나 추가 | ADAPT | 별도 서버 운영과 이야기 분산 |
| 9 | next 16.2.10→16.2.11 버전업 | FIT | 같은 라이브러리 버전 변경 |
| 10 | lucene-analysis-nori 설정 조정 | FIT | 기존 라이브러리 설정 |
| 11 | nested JSON 파싱 버그 수정 | FIT | 기술명 단어 경계; nestedList/NestedConfig/nest_level도 FIT |
| 12 | NestJS 공식 문서랑 Spring 비교만 해줘(읽기 전용) | FIT | 도입 요청이 없는 비교 |
| 13 | 기존 soniox-sidecar package.json 그대로 | FIT | 이미 선언된 의존성; 신규 도입 없음 |
| 14 | tech: nestjs + ACCEPTED + approvedBy: user ADR이 있는 NestJS 도입 | OVERRIDE | 명시적 사용자 승인 결정 기록 |

직접 규칙과 충돌하는 새 SaaS 계정/상시 데몬/백그라운드 워처/상태 서버는
대안이 있어도 DECLINE(4)다. 나머지 독립 WP는 계속한다.
합성 임시 폴더 테스트는 실제 프레임워크 설치나 서버 시작을 하지 않는다.
