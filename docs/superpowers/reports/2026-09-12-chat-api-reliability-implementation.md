# 채팅 API 영속화·취소·중복 제어 구현 보고서

2026-09-12 · Desktop 활성 체크아웃 · 사용자 승인 후 후처리

Goal 상태: **blocked**. 비운영 공유 Redis·DB와 실제 제공자 종료 증거가 필요한 수용 환경의 부재가 세 턴 연속 확인되어 전환했다. 구현과 로컬 검증 결과는 보존되어 있으며, 차단 범위는 배포·실제 Redis·브라우저·제공자 수용 시험이다.

활성 프로덕션 경로 16개에 세 가지 개선을 구현했다. 관련 테스트 211개는 실패·오류·건너뜀 없이 통과했고, projects → compileJava → :app:classes → bootJar 검증이 성공했다. 전체 목표는 **운영 수용 증거 필요** 상태다. 실제 공유 Redis의 Lua 실행, 실제 Spring 다중 인스턴스 배포, 브라우저 단절 후 GPU/API 연산 종료를 이번 로컬 검증과 구분한다.

## 1. 작업 영속화와 조회

기본 JobService를 JdbcJobService로 연결했다. 202를 반환하기 전에 요청을 DB에 기록하며, 등록된 task_ask 처리기가 저장된 요청을 읽어 기존 ChatService를 호출한다. 상태와 본문은 별도 테이블에 저장되고 상태 응답에는 resultRef만 포함된다.

| 경로 | 동작 |
|---|---|
| POST /v1/tasks/ask/async | 입력 영속화 후 taskId와 Location 반환 |
| GET /v1/tasks/{taskId} | 제출 주체의 상태·완료 시각·결과 참조 조회 |
| GET /v1/tasks/{taskId}/result | 완료 결과 조회; 미완료 409, 없는 작업·다른 소유자 404 |
| POST /v1/tasks/{taskId}/cancel | 대기 작업 취소 또는 실행기 중단 요청 |

모든 작업 경로에 관리자 권한 및 기존 토큰 가드를 적용했다. DB 조회와 취소는 제출 주체의 전체 SHA-256 해시로 한정한다. 공용 관리자 토큰으로 인증한 요청은 기존 인증기의 동일한 admin-token 주체를 공유하므로, 토큰 하나를 여러 사람이 공유하는 운영은 개인별 분리를 제공하지 않는다.

작업 선점과 결과 기록은 상태·실행기 토큰·임대 시각으로 조건을 건다. 임대가 끊긴 실행을 자동으로 재추론하지 않고 OUTCOME_UNKNOWN으로 남긴다. 이는 외부 모델의 결과 생성과 DB 커밋 사이에 원자적 트랜잭션이 없기 때문이다. 이 상태는 “결과가 복구됐다”는 의미가 아니다.

완료·실패·취소가 확정된 작업은 완료 시점부터 24시간 보관한다. 대기·실행·결과 불명 작업에는 자동 만료를 적용하지 않는다. 요청은 1MiB, 결과는 4MiB로 제한한다. 완료 콜백은 DB 상태로 재시도하며 최대 5회 이후 실패 상태를 남긴다. 콜백 실패는 추론을 다시 실행하지 않으며, 새 작업이 계속 대기하더라도 준비된 콜백은 처리 기회를 갖는다.

## 2. 대화형 연결 단절

활성 구현은 SseEmitter 대신 Reactor Flux이므로 기존 ChatRunRegistry의 정확한 실행 단위 취소 경로에 연결했다.

- 최초 스트림과 attach=true 스트림 모두 실제 HTTP 구독을 추적한다.
- 마지막 대화형 구독자가 해제되면 ACK 여부와 관계없이 해당 실행의 취소 핸들·관찰자를 호출한다.
- 내부 재전송용 브리지 구독은 실제 클라이언트 수에 포함하지 않는다.
- 다른 클라이언트가 남아 있으면 실행을 유지한다. DONE과 COMMITTING은 단절 때문에 취소 상태로 바꾸지 않는다.
- 250ms 간격 SSE 주석을 클라이언트 방향에만 보내 조용한 소켓의 실패를 드러낸다. 주석은 저장된 재전송 이벤트에 들어가지 않는다.
- 연결이 세션 생성보다 먼저 끊어진 경우도 나중에 결합되는 실행을 취소한다.
- 202로 접수한 영속 백그라운드 작업은 연결 수와 독립적으로 계속되며 명시적 취소 API를 사용한다.

진행 중인 대화를 모든 클라이언트가 떠난 뒤에도 계속 생성하던 기존 동작은 바뀐다. 완료·취소된 실행의 보존된 재전송은 가능하다. 조용히 패킷을 버리는 네트워크에서는 250ms 주석만으로 단절 감지를 1초 이내에 보장할 수 없다.

## 3. 멱등성 키와 공유 요청 제한

공통 필터는 POST /api/chat, /api/chat/sync, /api/chat/stream의 신규 생성에 적용된다. attach, cancel, ACK는 생성 할당량을 소비하지 않는다.

| 상황 | 응답 |
|---|---|
| 같은 소유자·Idempotency-Key의 중복 | 409 idempotency_duplicate |
| 같은 키를 다른 본문/경로/쿼리에 재사용 | 422 idempotency_payload_mismatch |
| 잘못된 키 형식 | 400 invalid_idempotency_key |
| 사용자 또는 IP 버킷 소진 | 429 chat_rate_limited + Retry-After |
| Redis 또는 필요한 멱등성 DB 기록 실패 | 503 chat_admission_unavailable |

키는 선택적 헤더이며 영문·숫자·점·밑줄·콜론·하이픈 1~128자를 받는다. 호출자는 하나의 논리적 요청을 재시도할 때 같은 키와 같은 요청, 같은 인증 주체 또는 ownerKey 쿠키를 유지해야 한다. 쿠키 없이 각각 새 소유자를 발급받는 요청은 동일 소유자의 재시도로 묶이지 않는다. 이번 구현은 중복 경고 반환을 선택했으며 완료 응답 본문을 캐시해 재전송하지 않는다. 키가 없는 요청에는 공유 요청 제한만 적용된다.

Redis에서 한 번의 EVAL로 서버 시각에 따른 두 버킷의 충전·차감 및 키 등록을 처리한다. 키들은 같은 Redis Cluster hash tag를 사용한다. 기존 Upstash REST 전송 경로를 재사용했으며 새 프로덕션 의존성은 추가하지 않았다.

DB의 고유 키 기록은 Redis 만료·유실 이후에도 실행 중 요청의 중복을 막는다. 정상 응답 종료 시점부터 24시간이 지난 기록은 정리한다. 비동기 오류·타임아웃 또는 불확실한 종료는 OUTCOME_UNKNOWN으로 보존해 자동 재실행을 차단한다.

## 설정과 적용 순서

| 설정 | 기본값 | 의미 |
|---|---|---|
| jobs.storage | jdbc | 기본 영속 작업 저장소 |
| chat.admission.enabled | true | 공유 생성 제한 필터 활성화 |
| chat.admission.user-capacity | 20 | 사용자 버킷의 최대 요청 수 |
| chat.admission.user-per-minute | 20 | 사용자 버킷의 분당 충전량 |
| chat.admission.ip-capacity | 60 | IP 버킷의 최대 요청 수 |
| chat.admission.ip-per-minute | 60 | IP 버킷의 분당 충전량 |

버킷의 토큰은 LLM 출력 토큰이 아니라 요청 허용량이다. IP는 ClientOwnerKeyResolver의 기존 TrustedProxyPolicy를 거친 뒤 해시한다. 신뢰하지 않는 피어의 X-Forwarded-For로 버킷을 바꿀 수 없다.

운영자는 먼저 공유 MySQL 호환 DB에 아래 SQL을 검토·적용하고, 기존 upstash.redis.rest-url / upstash.redis.rest-token 설정을 해당 비운영 또는 운영 Redis에 연결해야 한다. 이번 작업에서는 실제 DB 스키마나 자격 증명, 실행 중 서버를 변경하지 않았다.

1. main/resources/db/migration/V20260912__durable_jobs.sql
2. main/resources/db/migration/V20260912_02__chat_requests.sql

자동 마이그레이션을 새로 설치하지 않았으므로 파일을 두는 것만으로 실제 DB에 테이블이 생기지 않는다. 준비되지 않은 Redis는 생성 요청을 503으로 거부한다. 로컬 개발에서만 기존 동작이 필요하면 jobs.storage=memory 및 chat.admission.enabled=false를 명시할 수 있다. 이 설정은 영속화·공유 제한의 수용 증거가 될 수 없다.

## 신선한 검증

| 검증 | 실제 결과 | 증명의 범위 |
|---|---|---|
| 관련 테스트 | 211 통과, 실패/오류/건너뜀 0 | 변경된 소스와 직접 영향 경계 |
| 빌드 | projects, compileJava, :app:classes, bootJar 성공 | 컴파일·패키징; 실제 서비스 기동과 별개 |
| 프로세스 장애 | JVM 3개, 강제 종료 2회, 처리 1회, 동일 결과 | 실제 공유 H2 TCP/파일 DB와 루프백 HTTP 시험 실행기 |
| 5개 요청 | 0/100/200/300/400ms, 처리 1회, 409 응답 4개 | 두 필터 인스턴스와 실제 DB 고유 키; Redis 응답·모델은 대역 |
| 업스트림 취소 | SDK/native/embedding 모두 1000ms 제한 통과 | 취소 직전 소켓·호출기가 살아 있음을 확인한 실제 루프백 HTTP |
| 전송 종료 시간 | SDK 1ms, native 0ms, embedding 0ms | 나노초 측정치를 밀리초로 내림한 값; 0은 1ms 미만 |
| 되돌림 검사 | 소스 패치 reverse --check 성공 | 원본 사본 기준 16개 프로덕션 경로만 포함 |
| 소스 일관성 | 검사 전후 해시 불일치 0 | 실제 검증된 소스 상태 |
| 비밀 패턴 | 새 프로덕션 줄의 고신뢰도 패턴 0건 | 전체 저장소 보안 인증을 의미하지 않음 |

전체 테스트 로그는 final.verify.log다. HTTP 전송 10개 테스트는 취소 전 열린 소켓 확인을 더한 후 transport-causal.verify.log에서 재검증했다. 테스트 수 211은 중복 합산하지 않은 수다.

## 남은 수용 증거와 한 가지 다음 단계

**지정된 비운영 공유 Redis·DB 및 실제 모델 관찰이 가능한 Spring 실행 환경에서 수용 시험을 수행해야 한다.** 현재 컴퓨터에는 Redis 서버/CLI/Docker가 없고, 시험용 외부 대상도 지정되지 않았다.

그 환경에서 실제 Spring 인스턴스 A/B의 교차 조회·재시작, Lua 실행과 공유 버킷 소진, 브라우저 최초/attach 연결 단절, 5회 요청의 실제 모델 호출 수를 확인한다. 모델 서버의 요청별 종료 시각 또는 토큰/GPU 중단 증거가 없으면 1초 이내 계산 중단을 완료로 표시하지 않는다.

소스 수정은 마쳤지만 이 외부 수용 조건이 남아 있으므로 전체 goal은 완료로 표시하지 않았다. 기존 무관한 변경과 index.lock을 보존했으며 커밋·푸시·배포는 수행하지 않았다.

## 증거와 복구

기준 디렉터리: data/agent-handoff/api-reliability/20260912-01a09365/

- verification.summary.json — 테스트별 수와 프로세스·전송 측정값
- acceptance-audit.json — 원래 요구사항 10개별 달성 범위와 다음 증거
- implementation.source.patch — 이번 프로덕션 변경만 포함한 검토용 패치
- source-postimages.json — 변경 전후 해시와 비밀 패턴 검사 범위
- build-artifact.json — 생성한 JAR 경로·해시·크기
- jobs/, disconnect/, admission/ — 단계별 원본 사본, 대상 목록, 정확히 세 개의 논리적 사전 검토 패킷

역적용은 검사만 수행했다. 실제 되돌림은 당시 소스·소유권·해시를 다시 확인한 뒤 저장소의 기존 소스 편집 절차로 수행해야 한다. 테스트 파일의 변경은 이 프로덕션 전용 패치에 포함하지 않았으므로 함께 검토한다.
