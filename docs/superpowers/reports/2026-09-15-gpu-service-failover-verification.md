# GPU·서비스 장애 전환: 현재 구현과 검증

2026-09-15 Desktop 후속 검증. 승인된 설계는 `docs/superpowers/specs/2026-09-14-gpu-service-failover-design.md`이다. **주요 소스 변경과 모의 장애 전환을 구현·검증했으나 전체 목표는 미완료**다. 첫 cloud 설정이 disabled인 AUTO 경로와 상세 Debug FX 표시 후보는 아직 적용하지 못했다. 실제 GPU 복귀·실물 장치·실제 두 외부 API 사이 전환도 미검증이다.

이 보고서는 이전 `data/agent-handoff/codex/report/gpu-service-failover-20260914/implementation-verification.md`의 후속 결과를 우선한다. 이전 보고서의 “추론별 4회”, 최종 108개, JAR 해시, 소스 15개 모두 동일, 모든 lease 해제라는 문구는 현재 상태를 나타내지 않는다. 이전 보고서의 prepared checkpoint는 변경 없이 보존했다. 새 보고서 작성이 소스 구현의 완료 증명은 아니다.

## 적용한 파일과 핵심 변경

활성 root `main/java`·`main/resources`의 기존 소유자를 보강했다. Java 17.0.13, Spring Boot 3.3.4, LangChain4j 1.0.1과 기존 프로퍼티명을 유지했다. 새 production 의존성이나 별도 라우터는 추가하지 않았다.

| 파일 | 적용 내용 |
|---|---|
| `main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java` | endpoint/model 장애 구간·OPEN·단일 HALF_OPEN·복귀 안정 시간. 추가로 request timeline 전체의 물리적 추론 호출 4회 상한을 동기화하여 관리 |
| `main/java/com/example/lms/llm/gateway/LlmGatewayProperties.java` | 기존 정책에 장애 횟수·안정 시간·probe 만료·sampling 설정 추가 |
| `main/java/com/example/lms/llm/gateway/HybridLlmGatewayProbeService.java` | native/compatible 공통 차단, 신선한 GPU와 정확한 `/api/ps` 모델·VRAM 검증 |
| `main/java/com/example/lms/llm/OllamaNativeChatModel.java` | CUDA·timeout·연결 실패와 취소·성공 분류를 공통 상태에 연결 |
| `main/java/com/example/lms/llm/DynamicChatModelFactory.java` | 직접 로컬 모델 이름도 기존 failover 경로에 연결; sampling·token·deadline 유지 |
| `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java` | 적격 등록 API의 후속 순서, endpoint/model 중복 방지, 기능·컨텍스트 용량 검사, 남은 시간 배분. disabled-first-cloud 반례는 미해결 |
| `main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java` | 동일 messages로 유한한 provider 전환. 전체 request 상한 공유, 취소·side effect·부분 출력 replay 금지. HTTP 전 local OPEN 차단만 정확히 한 번 호출 예약 반환 |
| `main/java/com/example/lms/llm/gateway/LlmGatewayFailureClassifier.java` | 상한 소진을 상위 workflow에서도 non-replayable로 유지 |
| `main/java/com/example/lms/assist/ConversateLocalCardGenerator.java` | 기존 4초 예산·구조화 card·CardVerifier·출처 ID를 유지하며 같은 API failover 연결 |
| `main/java/com/example/lms/assist/ConversateSessionService.java` | ASR/transport 유실에서 확정 문맥·카드 TTL 보존, capture epoch fencing. 후속 다른 작성자의 변경도 보존 |
| `main/resources/static/conversate/app.js` | STT/마이크/전송 장애의 텍스트 입력·웹 카드 대체. 기존 3회 SSE 제한과 다른 작성자의 후속 변경 보존 |
| `main/java/com/example/lms/infra/upstash/UpstashBackedWebCache.java` | remote cache 기본 250ms 제한 및 원래 deadline 적용; local cache/miss로 진행, 취소 유지 |
| `main/java/com/example/lms/api/ChatStreamSignalBuilder.java` | allowlist 기반 경로·원인·상태·횟수·응답시간·잔여시간 label. 상세 값을 실제 화면에 표시하는 JS 후보는 미적용 |
| `main/java/com/example/lms/api/CloudModelDiagnosticsController.java` | 실행 중 Spring에 바인딩된 failover 설정을 기존 관리자 진단에 제공 |
| `main/resources/application-llm.yaml` | 기존 설정 키로 ENFORCE/local/cloud 기본 활성화. 명시적인 disabled 설정은 우선 |

`src/test/java/com/example/lms/llm/gateway/RequestTotalInferenceBudgetTest.java`와 `src/test/java/com/example/lms/ChatFailoverAdmissionHttpTest.java`에 새 요청 상한·HTTP 승인 경계 검사를 추가했다. 나머지 신규 suite와 기존 suite에 추가한 반례는 아래 증거 파일에 기록되어 있다.

## 차단·전환·복귀와 요청 보존

- 같은 endpoint/model에서 일반 CUDA·timeout·연결 실패가 기본 60초 구간에 3회 쌓이면 OPEN이다. GPU Lost는 즉시 OPEN이고 기존 hard cooldown 600초를 유지한다. 일반 오류 cooldown은 60초다.
- cooldown 이후 HALF_OPEN의 단일 요청만 검증한다. 신선한 GPU 상태, 해당 장치 role, 같은 Ollama 서버의 정확한 대상 모델과 양수 VRAM, 생성 성공, 연속 2회 성공과 최소 30초 안정 시간이 모두 필요하다. CPU 응답·다른 GPU/모델·UNKNOWN은 복귀 증거가 아니다. permit 취소·만료·늦은 완료는 fencing한다.
- **4회 상한은 답변 확장을 포함한 같은 request 전체에 적용한다.** SDK의 실제 HTTP 재시도도 같은 카운터를 사용한다. adapter 예약과 첫 HTTP를 이중 계산하지 않으며, 이후 HTTP는 별도 예약한다. 기존 더 작은 한도와 원래 deadline도 유지한다.
- local이 이미 OPEN이라 실제 HTTP가 시작되지 않은 경우만 예약을 반환한다. HTTP 시작 후 같은 오류 이름을 던져도 반환하지 않는다. 첫 API에서 남은 시간을 모두 사용하지 않도록 후속 API 예산을 남긴다.
- 추론 전환에는 동일 system 지시·대화 순서·검색 결과를 반영한 messages와 출처 ID를 전달한다. provider가 바뀌었다는 이유로 검색을 다시 실행하지 않는다. 기존 의미·출처 검증 경계를 유지한다. 모델 사이 문구나 품질의 완전한 동일성까지 보장하지 않는다.
- 현재 `/api/chat/stream`은 완성된 non-streaming 모델 결과를 받은 뒤 화면 chunk를 보낸다. 모델 실패는 출력 전에 전환한다. 사용자 취소·이미 실행된 외부 작업·모델 부분 출력 이후의 low-level replay는 금지한다. 이미 전달된 답변에 새 모델 텍스트를 이어 붙이지 않는다. 실제 provider의 부분 토큰을 받는 경로에서 자동 교체까지 수행했다는 주장은 하지 않는다.
- 벡터 검색 실패 시 기존 누적 근거를 보존하고 다음 기존 검색 경로로 진행한다. BM25는 실제 corpus가 있는 경로에서만 사용하고 일반 corpus가 없는 곳에 결과를 만들지 않는다. 임베딩 fallback의 벡터 공간 호환성·순서·개수 검사를 유지한다. 단순 차원 일치만으로 서로 다른 모델의 벡터를 섞지 않는다.
- STT 장애는 확정 발화를 유지한 텍스트 입력으로 대체한다. 미확정 오디오를 자동 재전송하지 않는다. Display 전송 장애에서는 동일 검증 카드를 기존 웹 세션에 유지하고 TTL·stop·clear·owner 검사를 지킨다. 다른 작성자의 cloud STT/Whisper 변경은 이 작업의 실물 인증으로 포함하지 않는다.
- Redis 우회는 선택적 cache에 한정한다. 요청 승인·멱등성 저장소가 unavailable이면 신규 승인만 fail-closed한다. allow-all이나 중복 실행 가능 우회는 추가하지 않았다.

## 신규 검사와 현재 결과

변경 전 **223개는 기준선**이다. 아래 수치는 서로 겹치는 suite가 있어 합산하지 않는다. 전체 저장소의 모든 테스트 통과를 의미하지 않는다.

| 검사 | 결과 | 증거와 범위 |
|---|---:|---|
| 처음 추가한 장애 전용 7 suite | 34 통과 | recovery fence 8, provider chain 8, health routing 7, direct factory 2, configuration binding 2, Conversate gateway 1, web cache 6; 이전 구현 보고서와 각 GREEN summary |
| `RequestTotalInferenceBudgetTest` | 신규 6 통과 | 답변 확장·동시 8요청·SDK retry·정상 비failover 보존·OPEN 예약 반환·HTTP 후 잘못된 반환 금지 |
| 마지막 Java 영향 경계 검사 | 14 suite, 151 통과, 실패/skip 0 | `reservation-refund-summary.json`, `reservation-refund.log`; 위 신규 검사 일부 포함 |
| 확장 요청의 전체 HTTP 검사 | 1 통과 | `http-13-summary.json`, `http-expansion-13.json`; 실제 loopback 호출 local 1/API1 2/API2 1, 합계 4, 추가 생성 차단 |
| 최신 일반 요청의 전체 HTTP 재검증 | 1 통과, 실패/skip 0 | 2026-09-15 KST, `http-14-resume.log`; BUILD SUCCESSFUL 1m40s, 실제 테스트 2.67초 |
| 현재 Conversate JavaScript | 61 통과, 실패/skip 0 | `conversate-resume-15.log`; 다른 작성자의 후속 변경 포함 |
| 버전·소스셋·빌드 | 통과 | `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, `compileJava`, `:app:classes`, `bootJar`; `reservation-refund.log` |

새 요청 상한 구현은 기존 4개 반례에서 먼저 RED를 확인했고, OPEN 예약 문제를 실제 HTTP에서 추가로 발견한 뒤 6개 반례와 영향 검사를 GREEN으로 확인했다. 실패와 복구 기록을 보존했다. JS 상세 gateway 표시 후보는 실제 파일을 수정하지 않은 메모리 내 harness에서 11개 필드·ARIA·unknown label 제외를 확인했지만, 현재 파일의 RED를 해소한 것으로 계산하지 않는다.

최신 일반 HTTP 검사에서는 **local 1 → API1 1 → API2 1**, HTTP 200, 1,873ms, 주어진 전체 deadline 15,000ms, 전환 시 잔여 13,687ms를 확인했다. 세 경로의 messages hash가 같았다. 같은 멱등 키 재전송은 저장 JSON을 재사용하여 추가 모델 호출이 없었다. Redis 승인 오류는 503, 제한은 429/Retry-After 2였고 추가 SQL claim·추론이 없었다. 확장 검사에서는 원래 생성이 3회, 기존 AnswerExpander의 편집 생성이 1회이며 합계 4를 넘지 않았다. 다른 편집 프롬프트를 원래 생성의 동일 프롬프트라고 보고하지 않는다.

이 통합 검사는 실제 LmsApplication·로그인·controller·SQL 승인/저장 코드를 실행하지만, **H2·mock Redis client·loopback 모델** 의존성을 사용한다. `verification-http` profile과 `demo.mode=false`로 보호된 승인 경계를 검사했다. 실제 운영 Redis/SQL/API 증명은 별도다. 최종 테스트 파일 해시는 `9765ce8396c2afd02dabebcfba66b0089322b9047535fc69da377143c75a0049`다.

## 실행 중 설정과 브라우저·외부 API

최신 Spring 통합 실행에서 실제 바인딩된 ENFORCE, local/cloud 활성화와 테스트용 15,000ms 예산을 assert했다. 15초는 이 테스트의 설정이며 운영 기본값을 바꾼 것이 아니다. 이전 패키지 앱의 관리자 진단에서도 ENFORCE/local/cloud 활성화, threshold 3/window 60000/recovery 2를 읽었다 (`runtime-effective-failover.json`, 2026-09-14T10:29:11Z).

요청 상한 패치 이후 만든 JAR은 `build/desktop-gpu-failover-baseline-20260914/libs/src111_merge15.jar`, SHA-256 `0d1cfcdc86286cd166ae02267c1596e9edf79162286a330fbd5c452e1ef941bd`다. 두 상한 변경 class는 현재 compiled class와 일치한다. **이 최신 JAR 자체의 별도 프로세스 실행은 미관측**이며, 최신 소스의 실제 Spring 테스트 런타임은 실행했다. 이전 JAR 진단을 새 JAR 실행 증거로 바꾸지 않는다.

2026-09-14T12:17Z의 최신 소스 Spring 테스트 앱에서 실제 브라우저 로그인·SSE를 실행했다. 이미 OPEN인 local은 추가 0회, API1/API2는 각각 추가 1회였다. 두 API의 messages hash가 같고 화면에는 `[source-7]`을 포함한 완료 답변 하나, `Answer: OK`, `fallback: local_device`가 보였다. 모델/GPU 상태는 UNKNOWN이다. 첫 진단용 질문은 기존 debug heartbeat 분기로 처리되어 provider 증거에서 제외했고, 다음 일반 질문에서 답변을 확인했다. `browser-http-14.json`, `http-browser-counts-14.json`에 기록했다.

이 브라우저는 검색/RAG OFF인 합성 질문이다. 실제 검색 출처의 end-to-end 품질·실제 GPU·실제 외부 provider 증거로 확장하지 않는다. 당시 Gradle 종료 기록이 끊겼으므로 완료로 추정하지 않았고, 이후 같은 테스트 파일의 일반 HTTP 실행을 다시 수행해 종료 코드 0을 확보했다. 이전 테스트 앱 PID 49396과 포트 54009는 현재 부재이며 새 테스트 프로세스도 종료했다.

이전 승인 구간에서 등록된 실제 API adapter 응답은 2회 관측됐다(3,097ms/3,350ms, 합성 입력). 한 검사는 주입한 GPU Lost 이후 API 응답이다. 그러나 `clientHttpExchangeObserved=false`, `providerReceiptObserved=false`이므로 상관관계가 연결된 **실제 wire 전환 증명은 not_observed**다. 당시 적격 실제 provider는 1개여서 API1→API2 실증은 없다. 두 번째 호출은 컴파일 대기 후 원래 120초 window를 약 53초 초과했다. 이를 숨기지 않고 기록했으며 이후 live harness를 비활성화했다. 이번 재개에서는 외부 생성 0회이고 새 유료 가입·권한 변경은 없었다.

최신 task-entry bus는 `read-only-no-generation`으로 실행했다. 등록 정보/연결 조회는 추론 성공 증거가 아니다. registry `data/device-resources/registry/desktop/08e4ba08-f916-4d79-857d-a3bcc4b0c67c.json`, inbox 0개, shared secrets `evidence_needed`다. 비밀값을 읽어 출력하거나 credential/ACL을 바꾸지 않았다.

## 남은 실제 소스 문제와 소유권 제한

| 미완료 항목 | 현재 증거 | 다음 조건 |
|---|---|---|
| 모든 AUTO local이 OPEN이고 첫 cloud route 자체가 disabled | 신규 RED. `disabled-cloud-candidate.diff` 2줄 후보 미적용 | Java 키 조회식에 대한 checkpoint 오탐을 기존 보호 수준 안에서 해결하고 새 preflight/lease로 적용 |
| 상세 route/state/count/latency를 Debug FX에 표시 | 서버 label은 존재하나 현재 `chat.js`가 소비하지 않음. `gateway-ui-red.log` RED, `gateway-ui-candidate.log`는 메모리 내 후보 검사만 PASS | JS 표현식 분류와 보호 검사 해결 후 후보 적용 및 실제 화면 재검증 |
| 오래 열린 checkpoint의 완료 기록 | `http-14`는 검증 exit 0을 기록했지만 `source-lease-expired` HOLD | 기존 helper가 검증된 heartbeat의 effective expiry를 수용하도록 소유자가 좁게 수정·검증 |

공통 helper `scripts/codex_work_checkpoint.py`와 `scripts/test_codex_work_checkpoint_source_expressions.py`는 다른 작업 `conversate-zero-loop-01a09f60`의 예약 대상이다. 현재 lease는 expired이나 owner PID가 0, ownerState unknown이므로 종료·유기 증거가 없다. 다른 작업자의 lease를 삭제·갱신하거나 덮어쓰지 않았다. 나이만으로 회수하지 않는다.

우리 browser/report lease는 동일 owner와 fingerprint로 heartbeat 갱신을 완료했다. heartbeat는 원본 lease bytes를 바꾸지 않는다. checkpoint helper는 그 sidecar를 읽지 않고 원본 `expiresAtUtc`만 검사하여 완료를 거부한다. 검증 성공을 조작하거나 검사기를 우회하지 않았다. `http-14`의 상태는 HOLD, `report-current`는 변경 없는 prepared다. 두 예약은 이 복구 제한과 함께 보존했다. 이 보고서는 별도의 Markdown checkpoint로 검증한다.

`holdScope=disabled-first-cloud-source-patch,gateway-debug-ui,interrupted-checkpoint-finalization`; `firstBlockingRule=checkpoint-secret-pattern/source-lease-expired`; `blockingEvidence=helper-targets-reserved-by-unknown-owner`; `independentWorkCompleted=request-total-source-patch,151-boundary-tests,expansion-http,latest-normal-http,browser,61-js-tests`; `repositoryWideHold=false`.

다음 실행은 해당 helper 소유 작업의 현재 소유권 정리·수정 완료 후 정확한 target 상태를 재검증하는 것이다. 설계 승인을 다시 받는 문제가 아니다. 현재 checkout의 다른 독립 파일 작업까지 금지하는 HOLD도 아니다.

## 남은 환경 검증과 한계

- 실제 두 적격 외부 provider의 전환, 실제 Redis/SQL과 연결한 최종 브라우저 요청: 새로 확인된 권한·기존 예산 구간·환경 증거가 필요하다. 지금은 모의 검증과 분리한다.
- 실제 GPU 복귀: GPU를 고장 내지 않고 자연 회복 때 fresh GPU·정확한 `/api/ps`·성공 생성·안정 구간을 관측해야 한다. 현재 복귀 증거는 fake clock/transport/GPU 주입 검사다.
- 실제 마이크·STT·Display·Simulator·공개 HTTPS: 장치 전달 미관측. 소스/Java/JS 통과가 실물 동작 증명은 아니다. 기존 Meta selector의 stale task binding도 별도다.
- GPU만 응답을 잃고 OS/JVM/network가 살아 있으면 이 프로세스의 자동 전환이 가능하다. **PC 전원이 꺼지면 같은 PC의 라우터도 멈춘다.** 그 경우 다른 호스트의 ingress·세션·라우팅 배치가 필요하며 이번 변경 범위에 포함하지 않았다. 실제 GPU reset·과부하·강제 재부팅은 수행하지 않았다.

## 보존·감사

`resume-verification-15.json`에서 원래 15개 대상의 현재 해시와 preimage를 다시 대조했다. `opnessl` 포함 바이트 줄 불일치 0이다. 최초 audit 이후 바뀐 4개는 우리 request 상한 2개와 다른 작성자의 Conversate 2개이며, 최초 postimage 15개가 모두 같다는 이전 표현을 사용하지 않는다. 전체 저장소 secret 안전 인증으로 일반화하지 않는다. 공개 진단에 raw API key·대화/오디오 원문·full 오류 body를 추가하지 않았다.

초기 작업의 작은 hunk 1건은 `git-operation-active` 거부 후 적용된 절차 이탈이 있었고, 이후 정확한 preimage/guard/테스트로 재검증했다 (`guard-reconciliation.json`). 최초 거부를 PASS로 바꾸지 않는다. 앞서 적은 API wall-window 이탈도 별도 보존한다. 이번 재개는 소스 예외 적용·외부 생성·다른 작성자 변경 덮어쓰기를 하지 않았다.

모든 상세 증거의 공통 위치는 `data/agent-handoff/codex/report/gpu-service-failover-20260914/`이다. 원복 시 `preimages/`와 각 `data/agent-handoff/codex-autonomy/gpu-service-failover-20260914/` cycle의 정확한 전후 해시를 사용하고 현재 writer를 확인해야 한다. 저장소 전체 reset/clean이나 Git HEAD로 dirty 파일을 덮어쓰지 않는다. commit/push/deploy·운영 DB 변경·데이터 삭제는 수행하지 않았다.
