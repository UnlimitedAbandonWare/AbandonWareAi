# 백엔드 실행 수명주기 우선 소스수정 지시서 R2 [초안]

## ① 입력 게이트

| 자료 | 열림 상태 | 기준 시점·해석 |
|---|---|---|
| mfwasainx.zip | 열림 — 목록 전체 확인, 실행 관련 소스 선택 정독 | 사용자 제공 2026-09-30 KST 스냅숏. SHA-256 db2221f3aa06f99c5a101dd5ed9b8504c7e6f431fd23393917072bc051ad7f38 |
| UAW.txt | 열림 | 설계 의도·과거 소스 지도. 현재 구현의 증거로 사용하지 않음 |
| Abandon_X.txt | 열림 | 2026-09-23 교정 문서. 현재 소스로 재확인 |
| Abandon_Project_Instructions(1).md, Abandon_AGENTS_Insert(1).md | 열림 | 작업 범위·증거·최소 변경 규칙 |
| Abandon_Evidence_Verification(1).md | 열림 | 과거 증거 도구 검증. Java 앱 검증과 무관 |
| 이전 Jev 지시서·MFWASAINX_AUDIT_EXECUTION_PACK_20260930.zip | 열림 | 제안 코드와 독립 탐침. 원본 적용 완료 증거가 아님 |

제공된 HEAD=4150b2822f2c, branch=codex/owned-runtime-browser-restart는 사용자 기준선이다. ZIP에 Git 메타데이터가 없어 독립 확인하지 못했다. 최신 작업 트리는 null이며 이 문서의 줄 번호는 이 ZIP에만 해당한다.

ZIP 일반 파일 2,390개, Java 2,176개. 루트는 main이다. 빌드 파일·Wrapper·테스트·app/src/main/java_clean·scripts/chatgpt_oauth_flow.py·zdr_guard.py·별도 models.json·API_ROUTING_SPEC는 이 ZIP에 없다. 저장소 전체에도 없다는 뜻이 아니다. 과거 ZIP으로 대신하지 않는다.

## ② 결론

첫 수정 대상은 Jev가 아니라 **ChatRunRegistry의 실행 상태 잠금 안에서 수행하는 영속 저장**이다. 느린 저장 하나가 상태 조회·삭제 및 단일 정리 스케줄러를 함께 기다리게 할 수 있다. 다음은 Jev의 본문 마감시간 누락과 시작 전 취소 시 permit 미반환, Brave의 Free/Base 상태 혼합이다.

500ms try/catch, Gemini Flash 전환, Python VRAM 80% 제한을 일괄 적용하지 않는다. 각각의 실행 주체와 자원 소유권에 맞는 최소 수정을 한다. 이번 산출은 지시서와 독립 재현 증거이며 원본 수정·커밋·배포·실제 공급자 호출을 하지 않았다.

## ③ 한계와 증거 등급

- STATIC_CONFIRMED: 해당 ZIP의 코드·등록·호출 경로를 직접 확인.
- MECHANISM_REPRODUCED: 같은 JDK 동시성/HTTP 구조의 독립 재현. 원본 클래스 테스트가 아님.
- NOT_RUN: 프로젝트 Gradle 테스트, 전체 빌드, 실제 Java 17 실행, Windows/H2/GPU/공급자 통합 검증.
- evidence_needed: 실제 빌드 루트, sourceSet, 설치 버전, 런처, SQL 예외, GPU 계측, OAuth writer 계약.

P0는 실제 주 실행·제어 경로의 정지, 복구 불능 또는 권한/삭제 계약 파괴가 재현될 때 확정한다. 현재 WP1은 **P0 우선 재현 후보**다. 무한 대기와 모든 장애를 P0로 뭉뚱그리지 않는다. Jev는 선택 기능이므로 기본 P1이며 전체 채팅 장애 전파가 증명될 때만 승격한다.

## ④ 변경된 우선순위

| 순서 | 작업 | 분류 | 긴급도 | 현재 증거 |
|---|---|---|---|---|
| 1 | 실행 상태 잠금과 영속 저장 분리, 삭제 fence 보존 | NEW | P0 후보 | 실제 호출 경로 + 잠금 결합 독립 재현 |
| 2 | Jev 전송 종료·시작 전 취소·permit 소유권 | PARTIAL, 시작 전 취소 결함은 NEW | P1 | 본문 정지/permit 누수 독립 재현 |
| 3 | Brave 전송 경로별 quota와 공급자 전체 차단 분리 | PARTIAL | P1 | 서비스·인터셉터·AOP의 상태 의미 충돌 |
| 4 | 기존 GPU admission을 실제 Ollama 실행 경계에 연결 | PARTIAL | P1 | 기존 가드 존재, 주 추론 소비·상한 전달 공백 |
| 5 | H2 오류 분류 후 실행 소유권/경로 최소 보정 | PARTIAL / 수정 HOLD | 미확정; 기동 실패 재현 시 P0 | 상대 파일 URL만 확인. exit 3의 SQL 원인 미제공 |

NEW는 이번에 직접 확인한 새로운 근본 원인이라는 뜻이다. 자료가 없다는 이유로 NEW를 붙이지 않는다.

## ⑤ 원래 제안의 채택·보정

| 제안 | 판정 | 이 프로젝트에서의 실행 지시 |
|---|---|---|
| Jev 500ms try/catch | PARTIAL | 응답 대기 예산과 실제 전송 종료 예산을 분리한다. timeout 반환만으로 작업 종료·permit 회수를 선언하지 않는다. |
| 이전 캐시 또는 Gemini Flash 즉시 전환 | PARTIAL | 캐시는 같은 권한·현재 입력·revision·정책·TTL이 맞는 결과만. Flash는 캐시가 아니라 별도 외부 호출이다. Jev 실패는 우선 기존 결정론적 baseline으로 귀환한다. |
| Brave 충돌도 다른 모델로 처리 | CONFLICT | LLM 전환은 Free/Base quota 상태를 수리하지 않는다. 실제 전송 경로를 상태 키에 연결한다. |
| H2 권한 미들웨어 추가 | CONFLICT | HTTP owner 검사, OS 파일 권한, H2 단독 파일 점유, SQL 트랜잭션 락을 분리한다. 미들웨어로 파일 락을 해제하지 않는다. |
| Python 엔트리에 CUDA 80% 제한 | CONFLICT | PyTorch 할당자 설정은 별도 Ollama 프로세스를 제한하지 않는다. 현행 실행 주체는 LocalLlmProcessManager가 시작하는 Ollama다. |
| 좀비 위험 전부 P0, 일괄 리팩터링 커밋 | CONFLICT | 도달성과 파급을 재현한 근본 원인 하나씩 처리한다. 구조 정리·다른 하위 시스템 변경을 같은 패치에 넣지 않는다. |

공식 규격 근거는 말미 S1~S8. 수치 예시는 제안이며 실측값으로 표기하지 않는다.

---

## 착수 계약 — 실제 저장소에서 먼저 할 일

사용자 작업 트리와 기존 작업 리스·저널을 읽는다. ZIP을 main/에 다시 덮어쓰거나, 다른 에이전트 수정분을 되돌리지 않는다.

```powershell
git rev-parse --show-toplevel
git rev-parse --short=12 HEAD
git branch --show-current
git status --short
git diff --name-only
```

빌드 파일과 런처에서 mainClass, sourceSets, 테스트 task를 확인한다. 이 ZIP의 후보 진입점은 main/java/com/example/lms/LmsApplication.java:20~30이다. 스캔 범위는 com.example.lms와 com.nova.protocol이며 SpringApplication.run을 호출한다. 실제 배포 진입점인지 빌드·실행 명령으로 확인한다.

본문 main/java·main/resources는 ZIP 내부 경로다. 실제 sourceSet이 같으면 그대로 사용한다. src/main으로 임의 변환하지 않는다. 테스트 FQCN은 아래에 명시한 **신규 제안**이며, 기존 동등 테스트가 있으면 그 클래스를 확장한다. 테스트 파일은 확인한 test source root 아래 해당 package 경로에 둔다. testSourceRoot=null 상태에서 임의 빌드 파일을 만들지 않는다.

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat compileJava --no-daemon
.\gradlew.bat dependencyInsight --dependency langchain4j --configuration runtimeClasspath
.\gradlew.bat dependencyInsight --dependency h2 --configuration runtimeClasspath
```

위 명령은 해당 소스가 root task에 연결된 경우의 명령이다. 모듈이 다르면 확인된 소유 모듈의 task로 바꿔 기록한다. 의존성 갱신·일괄 업그레이드는 하지 않는다. 기존 캐시가 없고 네트워크/설치 승인이 없으면 필요한 의존성 확보를 미확인으로 남긴다.

기준선 컴파일 실패는 BASELINE_BLOCKED다. RED 테스트의 성공적 재현으로 계산하지 않는다. 출력은 명령·exit code·정제된 원인만 남기며 전체 환경변수·credential·대화 원문을 덤프하지 않는다.

공통 불변 조건:
- Java 17, LangChain4j 1.0.1과 기존 프로퍼티·환경변수 이름 유지.
- 기존 선택 모델, 검색 ON/OFF, owner/session/channel/동의 범위, 원본 대화, 수정·삭제 revision, 취소·재연결·멱등성 유지.
- 새 기능 off, 유료 false, ZDR false, 새 지출 상한 0. 기존 사용자가 승인한 값은 임의 초기화하지 않음.
- 실제 공급자 호출 금지. fixture는 loopback 임시 포트·임시 파일·가상 계정만 사용. 18180/18181/18182, 실제 GPU/DB/Display 채널을 테스트로 점유하지 않음.
- 원본 소스/설정의 직접 적용·커밋·push는 이 감사 범위 밖. 구현 담당도 승인된 파일만 수정하고 git add -A/reset/clean/강제 push를 사용하지 않음.

---

# WP1 — 실행 제어 잠금을 영속 저장에서 분리 [NEW / P0 후보]

## 관측 근거와 seam

| FQCN | ZIP 경로·메서드·행 | 확인한 역할 |
|---|---|---|
| com.example.lms.service.chat.ChatRunRegistry | main/java/com/example/lms/service/chat/ChatRunRegistry.java, runTerminalSideEffect(), 806~818 | run.gate를 잡은 채 action.run() 실행 |
| 같은 클래스 | 생성자/startStaleSweep(), 222~251; runStaleSweepNow()/timeoutIfStale(), 292~310 | 단일 evictor가 여러 실행을 순회하며 같은 gate 획득 |
| 같은 클래스 | cancelSessionForDeletion(), 512~559 | runs.compute 내부의 gate 획득527이 bounded await548보다 앞섬 |
| 같은 클래스 | cancelRun(), 1088~1145; describeExact(), 1044~1054 | 중지·상태 조회도 같은 gate 사용 |
| com.example.lms.api.ChatApiController | main/java/com/example/lms/api/ChatApiController.java, durablePersistence lambda, 2689~2740 | 원본 응답·그래프·요약·trace·세션 메타 저장을 action에 묶음 |
| 같은 클래스 | 세션 삭제 핸들러, 4982~5008 | owner 검사 후 fence, fence 실패503, 성공 후에만 deleteSession |
| com.example.lms.service.chat.FinalizedMemoryPersistence | main/java/com/example/lms/service/chat/FinalizedMemoryPersistence.java, persist(), 16~43 | 각 terminal Stage를 동일 registry gate로 실행 |
| com.example.lms.service.ChatWorkflow | main/java/com/example/lms/service/ChatWorkflow.java, continueChat() terminal 구간, 4460~4498 | prepare는 바깥, 영속화는 FinalizedMemoryPersistence 호출 |

현재 핵심 구조:

```java
synchronized (run.gate) {
    if (run.status != Status.COMMITTING || !leaseValid(run)) return false;
    action.run();
    touchProgress(run);
    return true;
}
```

성공 가설: 이 잠금은 삭제와 최종 저장을 직렬화하여 삭제한 기억의 재생성을 막는다. 정상적으로 빨리 끝나는 저장에는 유효하다.
반례: 저장이 지연되면 해당 실행의 상태 조회·삭제가 gate에서 먼저 기다린다. timeoutIfStale도 이 gate 앞에서 멈추므로 다른 실행의 정리까지 지연된다. 단일 evictor를 공유하는 선택적 cluster lease 갱신도 영향을 받을 수 있으나 cluster 활성은 미확인이다.
중립 판정: 삭제 보호 목적은 보존하고, 제어 잠금 안에 blocking I/O를 넣는 범위만 바꾼다. DB에 유한 timeout이 있으면 영구 교착이 아니라 그 시간 동안의 장애 증폭일 수 있다. 실서비스 전체 정지를 확정하지 않는다.

## RED-first

신규 제안 FQCN: com.example.lms.service.chat.ChatRunRegistryTerminalIsolationTest

- R1을 COMMITTING에 두고 terminal action을 latch로 정지시킨다. action 시작이 확인된 뒤 describeExact·일반 cancelExact·별도 R2의 stale sweep을 실행한다. 기존 코드의 gate 대기로 의도한 assertion이 실패해야 한다.
- 일반 cancelExact가 COMMITTING을 취소하지 않는 기존 의미를 유지하면서 짧은 제어 응답으로 거절하는지 확인한다. 커밋 롤백을 가장하지 않는다.
- 삭제 요청의 전체 대기는 기존 deletion-cancel-wait-millis 예산으로 제한돼야 한다. gate 획득 전부터 같은 monotonic deadline을 사용한다. 짧은 테스트 설정으로 fence 실패가 반환되고 history delete 호출은 0회여야 한다.
- R1 저장을 해제하면 쓰기 완료 확인 후 재시도 삭제가 성공한다. 이후 늦은 R1 후속 Stage가 쓰지 않고, 원본 메시지·그래프·벡터에 옛 revision이 재생성되지 않는다.
- R1의 늦은 완료가 R2의 상태·슬롯·최종 답변·기억을 바꾸지 않는다. 미정 결과를 이유로 새 run을 생성하지 않는다.
- 별도 세션 R2의 정리와 terminal eviction이 R1 저장에 묶이지 않는다. 검증은 sleep 추정 대신 latch와 테스트용 clock/scheduler를 사용한다.

신규 제안 FQCN: com.example.lms.service.chat.FinalizedMemoryPersistenceCancellationFenceTest
- 기존 cancellation propagation, stage 순서, 시작 전 취소, stage 간 삭제 fence, 동일 실행의 중복 영속화 방지를 고정한다.

## 최소 변경 사양

첫 변경은 ChatRunRegistry와 위 회귀 테스트로 한정한다. 콜백 저장 내용을 다시 설계하지 않는다.

- run.gate는 상태·identity·fence·lease 같은 짧은 메타데이터 전이에만 쓴다. DB·모델·네트워크·외부 취소 콜백을 gate 안에서 실행하지 않는다.
- 기존 Run 내부에 영속 side effect 전용 상호배제/진행 상태를 둔다. 대기 가능한 ReentrantLock 또는 동등한 기존 제어기 중 하나를 선택하되, 새 전역 실행 레지스트리는 만들지 않는다.
- 쓰기 진입은 전용 제어를 획득한 뒤 짧은 gate에서 exact run·COMMITTING·lease·deletionFence를 다시 검사한다. gate를 해제하고 action을 실행한다. 완료 처리는 exact identity를 다시 확인하고 짧게 기록한다.
- 삭제는 먼저 짧은 gate에서 기존 deletionFence를 세워 새 쓰기 진입을 막는다. gate 및 ConcurrentHashMap.compute 콜백 밖에서 이미 승인된 쓰기의 종료를 남은 삭제 예산만큼 기다린다.
- 대기 만료는 기존 SessionDeletionFenceException 및 503 SESSION_DELETION_FENCE_UNAVAILABLE로 연결한다. 물리 삭제를 실행하거나 삭제 성공으로 반환하지 않는다.
- 실제 쓰기가 남아 있으면 실행을 교체 가능하게 만들거나 owner/slot/cluster lease를 조기 반환하지 않는다. CANCELLING·draining을 기존 상태 의미 안에서 표현하고, 종료를 확인한 시점에만 finalization한다.
- 대기 순서를 고정한다. gate를 잡은 상태에서 쓰기 잠금을 기다리지 않는다. sweep·describe는 쓰기 잠금을 기다리지 않는다. 쓰기 완료 알림은 finally에서 정확히 한 번 처리한다.
- timeoutIfStale와 terminal eviction도 같은 진행 중 쓰기 계약을 소비해야 한다. 잠금만 밖으로 옮겨 조기 CANCELLED/eviction을 허용하는 패치는 불합격이다.
- cluster 활성 경로에서는 DB 기반 fence/owner 조회의 I/O도 gate 밖인지 별도로 확인한다. 분산 lease 위반을 로컬 잠금 제거로 우회하지 않는다. cluster 설정을 임의 활성화하지 않는다.

삭제 deadline 안에 JDBC 작업 자체를 강제로 끝낼 수 있다는 보장은 하지 않는다. 이 패치의 목표는 **제어 응답과 다른 실행의 복구 경로를 살리고, 끝나지 않은 저장은 정확히 미완료로 유지**하는 것이다. 실제 DB timeout 조정은 원인 확인 후 WP5에서 다룬다.

## 완료 명령·조건

```powershell
.\gradlew.bat test --tests 'com.example.lms.service.chat.ChatRunRegistryTerminalIsolationTest' --tests 'com.example.lms.service.chat.FinalizedMemoryPersistenceCancellationFenceTest' --no-daemon --rerun-tasks
git diff --check
```

RED: 정상 컴파일 후 원하는 제어 대기 assertion 실패, exit 1. GREEN: 실제 테스트 실행 수 > 0, 실패·오류0, exit 0. 취소·삭제·late callback 경쟁을 반복 실행한다. 원래 COMMITTING 보호·owner 격리·삭제 후 늦은 저장 차단이 하나라도 약화되면 통합하지 않는다.

금지 파일: OAuth/Python 인증 파일, Brave 클라이언트, GPU 런처·설정, H2 데이터 파일, ChatWorkflow 전체 구조, 일반 UI. 필요 없는 service 계층 추가 금지.

---

# WP2 — Jev 실제 종료와 permit 소유권 보정 [PARTIAL / P1]

## 관측 근거

- main/java/com/example/lms/assist/JevGatewayClient.java, exchange(), 59~128: request timeout + ofInputStream 이후 readNBytes. 크기 상한은 있으나 본문 전체 수신을 직접 소유하는 deadline이 없다.
- main/java/com/example/lms/assist/JevEvaluationRuntime.java, advise(), 81~140: inFlight.tryAcquire()90은 supplier 밖, release126은 supplier finally에만 있다. timeout135의 future.cancel(true)가 시작 전 supplier를 생략시키면 permit이 반환되지 않는다.
- 같은 파일 prefetch()/await(), 244~333도 동일한 부모 예산·전송 소유권 계약으로 검증한다. 관측용 사전 실행을 무조건 금지하지 않는다.
- main/java/com/example/lms/assist/JevSurfacePolicy.java, resolve(), 12~19: 소스 기본 decision-wait=150ms, request-timeout=800ms다. 500ms가 현재 실효 설정이라는 근거는 없다.
- main/java/com/example/lms/assist/JevChoiceAdvisor.java와 JevDecisionScope.java의 기존 요청 범위 재사용을 먼저 확인한다. 새 전역 캐시를 만들지 않는다.

성공 가설: 호출자는 짧게 기다린 뒤 baseline으로 돌아가 응답 가용성을 확보한다.
반례: 호출자가 돌아가도 blocking transport가 남을 수 있다. 반대로 시작 전 취소라면 supplier finally 자체가 실행되지 않는다. timeout catch에 무조건 release를 더하면 실행 중 작업의 자원을 조기 반환해 동시 한도를 깨뜨린다.
중립 판정: 대체 응답이 아니라 전송·작업 생명주기 소유권을 고친다. 기본 off/지출 gate 때문에 실호출하지 않는 환경은 장애 영향이 제한적이다.

## RED-first와 순서

신규 제안 FQCN:
- com.example.lms.assist.JevGatewayClientBodyDeadlineTest
- com.example.lms.assist.JevEvaluationRuntimePermitLifecycleTest
- com.example.lms.assist.JevEvaluationRuntimeCapacityRecoveryTest

먼저 본문 deadline 결함만 재현·수정·검증하고, 다음으로 시작 전 취소 결함을 재현·수정·검증한다. 한 번의 실패를 두 원인의 해결 증거로 사용하지 않는다.

본문 fixture: 200 헤더만 보내기, 첫 바이트 후 정지, 403 본문 정지, 작은 청크를 계속 보내기, 정상 JSON, 초과 본문, 깨진 JSON, 401/402/403/429/5xx. 전체 deadline 후 transport가 끝나고 정상 다음 요청이 도달해야 한다.

작업 fixture: executor를 latch로 점유 → permit 예약 → 평가 작업 제출 → 시작 전 timeout/cancel → executor 해제. 평가 본문 호출0회, permit 원상복구가 기대값이다. 기존 코드는 permit이 남지 않아 RED가 된다. 정상 실행·실행 중 취소·interrupt 무시 transport·executor rejection·shutdown·중복 취소도 검사한다.

## 최소 변경

전송은 이전 문서의 BoundedHttpBody 제안과 동등한 **수신 중 바이트 제한 BodySubscriber + 단일 전체 deadline + 소유한 HTTP future/subscription 취소**로 고친다. 이전 제안 파일은 적용 여부를 현재 코드로 확인한 후 재사용한다. 무제한 byte[]로 다 받은 뒤 검사하지 않는다.

작업은 queued/running/finished/cancelled-before-start를 분리한다. 논리적 결과 future와 실제 실행 상태를 같은 값으로 취급하지 않는다. permit 반환 권한을 CAS 또는 단일 소유 상태로 정확히 한 번 관리한다.

| 상황 | permit 반환 | 지출 예약 |
|---|---|---|
| 제출 거절·시작 전 취소, 실행 불가가 확정됨 | 즉시 한 번 | 미전송이 확인된 예약만 환원 |
| 실행 중 caller timeout | 아직 반환하지 않음 | 전송 여부·과금 결과 불명이면 유지 |
| transport 실제 종료/실패 | worker 종료에서 한 번 | 실제 상태에 맞게 정산 |
| 중복 cancel/늦은 callback | 추가 반환 없음 | 추가 정산 없음 |

FutureTask.done()만으로 반환하지 않는다. 실행 중 cancel에도 done이 먼저 불릴 수 있기 때문이다. HTTP 연결 취소 요청이 공급자 처리·과금 취소를 보장한다고 기록하지 않는다.

500ms를 실험하려면 기존 demo.jev.decision-wait-ms 또는 해당 surface override를 사용한다. 기존 기본값을 전역적으로 올리지 않는다. caller 대기 예산과 전송의 hard deadline은 의미가 다르며, 부모 deadline을 넘지 않게 단조 시계로 계산한다. HTTP connect·본문·재시도마다 새500ms를 부여하지 않는다. 사전 평가·관측 실행을 유지하는 경우에도 종료 예산과 permit 귀속은 유한해야 한다.

## 대체 처리 계약

Jev의 native HTTP는 /v1/evaluate에서 typed decision을 반환한다(S2). 일반 답변 생성 모델의 대체물이 아니다.

1차 대체는 기존 Advice.defer 및 현재 요청의 baseline이다. 권한·결제·검색 허용·선택 모델은 baseline 밖에서 기존 검증을 거친다. 캐시는 현재 owner/session/입력 fingerprint/정책 revision/TTL이 일치하는 허용된 값만 사용한다. 이전 질문의 답변을 새 질문의 답변으로 반환하지 않는다.

Gemini Flash는 기존 라우터에 승인된 모델이 있고, 외부 전송·지출·남은 예산이 허용될 때만 최대1회 선택하는 별도 정책 변경이다. 이번 핫픽스에서 새 fallback 클라이언트·키·강제 모델 교체를 추가하지 않는다. 사용자가 exact model을 지정한 경우 자동 교체하지 않는다. 실패 원인이 auth/plan이면 timeout처럼 재전송하거나 ZDR을 자동 해제하지 않는다.

```powershell
.\gradlew.bat test --tests 'com.example.lms.assist.JevGatewayClientBodyDeadlineTest' --tests 'com.example.lms.assist.JevEvaluationRuntimePermitLifecycleTest' --tests 'com.example.lms.assist.JevEvaluationRuntimeCapacityRecoveryTest' --no-daemon --rerun-tasks
git diff --check
```

GREEN exit 0 외에 timeout 후 active 작업0, permit 복구, 후속 정상 요청 도달, 실제 전송수 일치가 필요하다. off/budget_skip이면 HTTP0건이어야 한다. 실제 공급자 검증은 NOT_RUN이다.

금지 파일: ChatWorkflow 광역 리팩터링, Brave 상태, 인증 credential, H2·GPU 설정, 새 AI SDK/Node sidecar·LangChain4j 업그레이드.

---

# WP3 — Brave Free/Base 상태를 실제 전송과 결합 [PARTIAL / P1]

## 근거와 seam

| ZIP 경로 | 심볼·행 |
|---|---|
| main/java/com/example/lms/service/web/BraveSearchService.java | reserveForRequest(), 1994~1999; markQuotaExhaustedAndDisable(), 2006~2026 |
| main/java/ai/abandonware/nova/orch/web/brave/BraveAdaptiveQpsRestTemplateInterceptor.java | intercept()/onResponse(), 95~146; 월간 소진 처리278~320 |
| main/java/ai/abandonware/nova/orch/web/brave/BraveRateLimitState.java | 기존 상태 저장소, lane 소비 계약 확장 대상 |
| main/java/ai/abandonware/nova/orch/aop/BraveOperationalGateAspect.java | apply(), 61~98; setOperationalDisabled(), 100~113 |
| main/java/ai/abandonware/nova/orch/aop/ProviderRateLimitBackoffAspect.java | resolveBraveDisabledReason(), 478~509 |

서비스는 Free 소진 후 Base가 있으면 전체 disable을 피한다. 그러나 인터셉터는 onResponse(resp)로 요청 경로를 잃고, 월간 소진 상태를 전체 공급자 차단으로 올린다. AOP도 같은 전역 상태를 소비한다. 최소 변경은 기존 서비스의 lane 예약을 버리는 것이 아니라, 모든 상태 소비자가 같은 의미를 쓰게 하는 것이다.

## RED-first

신규 제안 FQCN: com.example.lms.service.web.BraveLaneQuotaIsolationTest

- Free 월간0, 승인된 독립 Base 유효 → Base 전송 가능, providerDisabled=false.
- Free 응답이 늦게 도착한 사이 Base 요청이 실행되어도 각 헤더가 원래 전송 경로 상태만 변경.
- Base 일시429가 Free 월간 소진으로 변하지 않음. 반대 방향도 동일.
- 단일 키, 동일한 두 키, 독립 할당량을 확인하지 못한 키, 둘 다 소진, 월 rollover, 설정 off, missing key를 각각 검증.
- 월간 limit=0은 무제한으로 취급하는 현재 처리를 유지. remaining=0만으로 소진 판정 금지(S3).
- 기존 Naver/Tavily fallback의 허용 조건·예산·전송 횟수와 HTTP 401/403 정책을 유지.

## 최소 변경

예약이 선택한 FREE/BASE/LEGACY/UNKNOWN 식별자를 요청→응답 처리까지 전달한다. 기존 LaneReservation과 BraveRateLimitState를 확장하거나 동등한 기존 타입을 재사용한다. 평문 키를 로그·cache key·trace에 넣지 않는다. 전송 이후의 freeLaneActive()로 과거 응답의 경로를 추정하지 않는다.

경로별 quota/429와 공급자 전체 disable의 상태를 분리한다. 전체 disable은 승인되고 실제 사용 가능한 경로가 모두 불가한 경우만 계산한다. 서비스·인터셉터·두 AOP 소비를 함께 회귀 검증한다. trace의 providerDisabled도 실제 계산값을 기록한다.

Base 사용이 유료인 실제 플랜이면 기존 승인을 확인한다. Free 소진 자체가 유료 승인이 아니다. 401/403일 때 키를 자동 순환시켜 제한을 우회하지 않는다. UNKNOWN은 사실이 확인되지 않은 경로이며 무제한 허용으로 해석하지 않는다.

```powershell
.\gradlew.bat test --tests 'com.example.lms.service.web.BraveLaneQuotaIsolationTest' --no-daemon --rerun-tasks
git diff --check
```

의도한 격리 assertion으로 RED exit1, 수정 후 GREEN exit0. 로그에 키 값0건, 실제 fixture 호출 경로·횟수 검증. 실제 API 플랜/할당량이 독립인지 외부 확인 전에는 live 승격 성능을 주장하지 않는다.

금지 파일: Gemini/Jev 모델 라우팅, 공통 owner 검증, 전체 검색 순서, 프로퍼티명·키 값. 새 검색 공급자 계층 추가 금지.

---

# WP4 — 기존 GPU 가드와 Ollama 실행 경계를 연결 [PARTIAL / P1]

## 근거와 적용 경계

| ZIP 경로 | 심볼·행 | 현재 확인 |
|---|---|---|
| main/java/com/example/lms/config/LocalLlmProcessManager.java | buildLaunchRequest(), 632~658 | Ollama executable + serve, OLLAMA_HOST·CUDA_VISIBLE_DEVICES 전달 |
| main/java/com/example/lms/llm/OllamaNativeChatModel.java | chatStructured(), 286~353 | num_predict·num_gpu 등 전달, num_ctx 누락 |
| main/java/com/example/lms/health/GpuHardwareDiagnostics.java | admission(), 170~258 | 메모리 warn0.82/block0.90 기본의 기존 가드 |
| main/java/com/example/lms/service/rag/rerank/RerankGate.java | 188행 호출 | 기존 admission 소비 |
| main/java/com/example/lms/uaw/autolearn/UawAutolearnOrchestrator.java | 261행 호출 | 기존 admission 소비 |

Python torch.cuda.memory.set_per_process_memory_fraction(0.8)은 PyTorch caching allocator의 제한이다(S4). 별도 Ollama, ONNX, 화면 출력 프로세스의 합산 VRAM 제한이 아니다. 모델 가중치가 필요한 크기만큼 적재되는 것까지 캐시 비우기로 없앨 수 없다. 따라서 Python 엔트리나 CUDA allocator 교체를 이 프로젝트의 즉시 해결책으로 추가하지 않는다.

추가 함정: GpuHardwareDiagnostics는 blockThreshold를 warnThreshold 이상으로 clamp한다(174~177). warn 기본0.82를 둔 채 block만0.80으로 설정하면 실효 block은0.82가 된다. 또한 maxMemoryUsedRatio는 전체 GPU의 최대 비율이다. 이를 주 추론에 그대로 붙여 대상이 아닌 GPU의 압력으로 모든 요청을 막지 않는다.

## 단계별 최소 수정

첫 단계는 새 가드 생성이 아니라 기존 GPU 계측과 실제 target endpoint/UUID의 결합이다. 데이터가 없거나 오래됐으면 unknown으로 기록하고 임의 용량을 만들지 않는다. 승인된 GPU-only 실행에 한해서 기존 정책대로 거절/승인된 대체 경로를 선택하며 API-only 요청까지 막지 않는다.

프로세스별로 기존 런처의 환경 전달 지점에 OLLAMA_NUM_PARALLEL·OLLAMA_MAX_LOADED_MODELS·OLLAMA_CONTEXT_LENGTH를 명시하는 계약을 검증한다(S5). 부모 환경 또는 기존 twin launcher가 이미 전달하면 중복 설정을 만들지 않는다. 외부에서 시작한 Ollama의 환경을 이 Java 객체 변경만으로 바꿀 수 있다고 가정하지 않는다. 사용자 승인 없는 전역 재시작은 하지 않는다.

chatStructured의 options.num_ctx는 모델별 검증된 입력+출력 예산으로 전달한다(S6). 임베딩·주 추론·보조 추론을 모두 같은 컨텍스트로 맞추지 않는다. 입력이 초과하면 기존 문맥 축약을 사용하고 현재 질문/owner evidence를 조용히 잘라내지 않는다. allowCpuFallback이 false인 경로는 유지한다.

80%는 우선 계획 예산이지 드라이버 hard cap이 아니다. 이전 기준선 3090 24GiB의80%=19.2GiB, 3060 12GiB의80%=9.6GiB다. 3060이 실제16GiB로 변경된 경우 계측값을 근거로12.8GiB로 바꾸되 이름만 보고 용량을 고정하지 않는다. 화면·ONNX·Ollama 동시 사용량과 여유를 같은 물리 GPU 예산에서 고려한다.

수용 조건: weights + KV(context, parallel) + workspace + 다른 활성 소비량 + 안전 여유 <= 대상 GPU의 실제 사용 가능 VRAM.

parallel=1, max_loaded_models=1은 안전한 초기 계측 후보이며 최고 처리량 보장이 아니다. 모델 교체 지연과 임베딩 경쟁을 측정한다. Ollama 공식 FAQ와 Context length 문서의 기본 컨텍스트 설명이 다르므로 설치 버전의 실효값을 직접 확인하고 암묵 기본값에 의존하지 않는다(S5/S6).

NVIDIA의 프로그램별 Prefer No Sysmem Fallback은 지원 드라이버에서 실험 가능한 보조 정책이지만, 메모리가 모자라면 OOM으로 바뀔 수 있다(S7). 실제 CUDA runner 실행 파일에 적용되는지 확인한다. 80% hard cap 또는 메모리 부족 해결로 보고하지 않는다. 전역 드라이버 설정 변경·Windows 페이지 파일 비활성화·실행 중 강제 GPU reset 금지.

## RED-first / 검증

신규 제안 FQCN:
- com.example.lms.config.LocalLlmProcessManagerMemoryEnvironmentTest
- com.example.lms.llm.OllamaNativeChatModelContextBudgetTest
- com.example.lms.health.GpuAdmissionTargetIsolationTest

mock으로 대상 포트/UUID·환경 전달·명시 num_ctx·다른 GPU 압력 격리·80% 설정의 warn/block 순서·동시 예약·unknown telemetry·부모 예산 만료·GPU-only에서 CPU 재호출0건을 검증한다. 현재 기능 off일 때 기존 경로 변화가 없어야 한다.

```powershell
.\gradlew.bat test --tests 'com.example.lms.config.LocalLlmProcessManagerMemoryEnvironmentTest' --tests 'com.example.lms.llm.OllamaNativeChatModelContextBudgetTest' --tests 'com.example.lms.health.GpuAdmissionTargetIsolationTest' --no-daemon --rerun-tasks
ollama --version
nvidia-smi --query-gpu=uuid,name,memory.total,memory.used,driver_version --format=csv
```

테스트 GREEN exit0은 환경·요청 계약만 증명한다. 실제 Windows 시험은 별도 승인·비어 있는 작업 시간에 수행한다. 설치 버전, 모델 digest·양자화, 실제 컨텍스트, Dedicated/Shared memory, 동시 요청, 지연을 기록한다. shared memory 상승·OOM·응답 정지·기존 대비 지연 악화 시 확대 실험 중단, 자신이 바꾼 설정만 되돌린다. 계측 없는 정확한 VRAM 보장값은 null이다.

금지 파일: Python 인증 flow, 새 Torch 의존성·allocator 모듈, 사용자 선택 모델 강제 교체, 포트·환경변수명 변경, 공용 GPU 작업 강제 종료.

---

# WP5 — H2 실행 소유권을 오류 근거에 맞춰 보정 [PARTIAL / HOLD]

## 확인한 것과 모르는 것

main/resources/application-meta-display.yml:7의 spring.datasource.url은 LMS_DB_URL 미설정 시 상대 파일 DB를 쓴다. H2 버전·실제 JDBC URL·작업 디렉터리·점유 PID·SQLState/error code·런처 경로는 null이다. exit 3만으로 파일 락·권한·메모리 오류를 확정하지 않는다.

H2 파일은 OS ACL, embedded 다중 JVM 점유, SQL 락, 다른 CWD로 인한 다른 DB 생성이 별개다(S8). HTTP 인증 미들웨어는 이 문제의 해결책이 아니다. 현재 ChatApiController의 owner 검사를 유지한다.

## 착수·최소 수정

실제 로그에서 정제한 예외 클래스·SQLState/error code·중복 JVM 존재·DB 경로 정규화 결과를 확보한다. URL 안의 credential과 전체 command line을 출력하지 않는다. actualLauncherPath=null을 기존 저장소 런처 조사로 해소한다. 새 런처·포트 관리기를 추가하지 않는다.

단일 서버 계약이면 같은 DB와 포트를 소유하는 기존 정상 인스턴스를 재사용한다. 재시작은 기존 소유 PID의 실제 종료 확인 뒤 수행한다. ACL 거부가 확인되면 해당 파일/디렉터리·서비스 계정에 필요한 범위만 수정한다. Everyone 전체 권한·관리자 강제 실행으로 덮지 않는다.

여러 JVM에서 같은 DB를 의도적으로 사용해야 한다면 별도 승인 항목으로 AUTO_SERVER=TRUE를 검토한다. 동일한 절대 URL, H2 버전, 내부 TCP 서버, 파일 접근 권한, 소유 프로세스 종료 때의 트랜잭션 영향을 테스트한다. AUTO_SERVER는 웹 포트 중복을 해결하지 않는다. 운영 DB를 jdbc:h2:mem:으로 바꿔 세션을 휘발시키지 않는다. FILE_LOCK=NO나 실행 중 .lock.db/.mv.db 삭제 금지.

## RED-first / 완료

새 제안 FQCN: com.example.lms.config.H2RuntimeOwnershipContractTest. 실제 H2 의존성 버전으로 임시 파일 DB·임시 JVM을 사용한다. 단일 소유 정책에서 두 번째 진입의 명확한 결과, 소유자 정상 종료 후 재개, CWD 변경에도 같은 의도 경로, 기존 데이터 유지, 오류 분류를 확인한다. AUTO_SERVER 시험은 승인된 경우에만 분리한다.

```powershell
.\gradlew.bat test --tests 'com.example.lms.config.H2RuntimeOwnershipContractTest' --no-daemon --rerun-tasks
git diff --check
```

실제 원인·런처 seam 확보 전 설정 패치는 HOLD다. 확실한 기동 실패가 재현되면 이 WP를 P0로 승격해 다른 기능 튜닝보다 먼저 처리한다. timeout을 줄여 실패를 빨리 보이는 것과 락 원인을 해결한 것을 구분한다.

금지 파일: 운영 DB/락 파일 삭제, 전체 application 프로필 덮어쓰기, 새 인증 미들웨어, 전체 파일 ACL/실행 정책 변경, 공용 서버 무단 종료.

---

## 이번에 다시 수정하지 않을 항목

| 항목 | 판정 | 현재 근거 |
|---|---|---|
| canonical RuleBreak MVC 등록 | DONE — 정적 등록 경로 있음 | WebMvcConfig.java:59~68. 과거 Abandon_X의 미등록 판정 반복 금지. 실제 bean 활성은 컨텍스트 테스트로 확인 |
| 제한된 LLM·그래프 실행 풀 | DONE — 해당 구현 보존 | SearchExecutorConfig.java의 bounded queue·분리 executor. 전부 새 executor로 교체하지 않음 |
| 페이지 scraper의 while(true) | 결함 미채택 | PageContentScraper.java의 redirect 횟수·deadline·break를 함께 확인. 문법 검색만으로 무한 루프로 분류하지 않음 |
| Naver의 private bare acquire 후보 | HOLD | 해당 함수의 실제 호출 연결이 확인되지 않아 주 실행 장애로 승격하지 않음 |
| 동적 검색 recoveryExecutor 후보 | HOLD | 선언만으로 실제 런타임 사용을 단정하지 않음 |
| ChatWorkflow의 대규모 분할 | HOLD | 기능 실패 경계가 안정화되기 전에 의미 변경과 이동을 섞지 않음 |
| OAuth 사용 권한 전체 감사 | HOLD — Java 소비자만 확인 | ChatGptOAuthRegistration.java:97~122의 파일·만료 검사만 확인. Python writer의 승인 scope/서명 검증 계약은 미제공 |

OAuth에 대한 이전 P1 판단은 Java 소비자 쪽 추가 검증 필요성으로 한정한다. 상위 writer에서 검증된 권한이 저장 계약으로 보장되는지는 확인하지 못했으므로 전체 인증을 결함으로 확정하지 않는다. 승인 scope와 계정-카탈로그 결합을 실제 writer에서 확인한 후 기존 경계에만 수정한다. 전역 로그인 강제·일반 API key로 무단 fallback 금지.

## 최종 판정·측정 계약

작업별로 caller_returned, cancel_requested, transport_terminated, permit_released, durable_write_in_progress, persisted, skipped, failed를 구분한다. HTTP200·future.isDone·예외 없음만으로 전체 성공을 계산하지 않는다.

진단은 기존 trace/run 식별자와 이유 enum·소요시간·건수만 사용한다. 새로운 상위 점수 하나로 권한 위반·중복 저장을 상쇄하지 않는다. critical 회귀 한 건이면 통합 중단이다. 실제 사용자 session/Display/channel이나 외부 API를 테스트에 쓰지 않는다.

완료 보고에 기록할 항목: 실제 수정 파일, 최소 diff, 기준 HEAD, RED/GREEN 명령과 exit code·실제 테스트 수, 실제 transport 수/permit 수/늦은 저장 차단 결과, NOT_RUN 범위, 남은 위험. 자료 부재는 완료가 아니다. 변경 없는 WP는 검사 결과만 기록하고 중단한다.

## 이번 감사에서 실행한 검증

환경: OpenJDK 21.0.11. javac --release 17로 소스/API 타깃을 제한했다. Java 17 JVM에서 실행했다는 뜻이 아니다.

RuntimeHazardProbe는 제어 잠금 결합과 시작 전 Jev 취소 패턴의 독립 재현이다. HttpBodyDeadlineProbe는 loopback HTTP 본문 정지 재현이다. 원본 Spring 클래스·Gradle·H2·GPU·실제 공급자를 실행하지 않았다. exit0은 **설계한 결함 메커니즘이 관측되어 assertion을 통과**했다는 뜻이지 원본이 GREEN이라는 뜻이 아니다. 실제 결과는 VERIFICATION_R2_20260930.md 및 로그 파일을 따른다.

## 공식 자료 — 확인일 2026-09-30 KST

| ID | 공식 자료 URL | 참고한 이유·적용 범위 |
|---|---|---|
| S1 | https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html | Java17 future 취소와 실제 작업 제어의 구분. source/API17 타깃 유지 |
| S2 | https://vercel.com/changelog/ai-gateway-now-supports-typesafe-clients-and-http-api-for-jev | 2026-09-21 native /v1/evaluate 공개. typed decision이며 일반 생성 답변과 다름. Java에서 AI SDK 도입 불필요 |
| S3 | https://api-dashboard.search.brave.com/documentation/guides/rate-limiting | 초당·월간 제한, reset 단위, 무제한 한도 구분 |
| S4 | https://docs.pytorch.org/docs/main/generated/torch.cuda.memory.set_per_process_memory_fraction.html | PyTorch allocator 제한 범위. 실제 프로젝트에 PyTorch가 설치됐다는 증거가 아님 |
| S5 | https://docs.ollama.com/faq | num_ctx·parallel·loaded models·keep_alive 계약. 설치 버전의 실효값 별도 확인 |
| S6 | https://docs.ollama.com/context-length | 컨텍스트와 VRAM 관계·별도 기본값 설명. 암묵 기본값 채택 금지 |
| S7 | https://nvidia.custhelp.com/app/answers/detail/a_id/5490 | CUDA 시스템 메모리 fallback 정책과 OOM 대가. Ollama 실효는 실제 runner 계측 필요 |
| S8 | https://h2database.github.io/html/features.html | 상대 경로, embedded·automatic mixed 접속, 다중 프로세스 조건 |

최신 공식 설명을 현재 설치 라이브러리에 그대로 적용하지 않는다. 설치 버전·실제 응답·계정 플랜이 없는 항목은 null/NOT_RUN으로 남긴다. 이 문서는 위 자료를 사실 확인에만 사용하며 사용자의 비공개 소스나 로그를 웹 자료로 대체하지 않는다.
