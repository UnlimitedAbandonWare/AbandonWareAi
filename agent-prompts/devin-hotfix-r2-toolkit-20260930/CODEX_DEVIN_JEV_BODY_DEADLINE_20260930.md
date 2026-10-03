# [CODEX/DEVIN 지시서] Jev HTTP 본문 수신 마감시간과 평가 슬롯 회복

기준일: 2026-09-30 KST. 대상 입력 ZIP SHA256: db2221f3aa06f99c5a101dd5ed9b8504c7e6f431fd23393917072bc051ad7f38.

## 0. 목표
Jev 응답이 헤더만 도착하거나 본문이 중간에 멎어도, 정해진 전체 전송 시간 내에 종료하여 평가 작업 슬롯을 회수하고 다음 요청이 실행되게 한다.

## 착수 전 기준선과 변경 금지 범위
- 사용자 제시 HEAD: 4150b2822f2c / 브랜치: codex/owned-runtime-browser-restart. ZIP에는 Git 메타데이터가 없고 원격의 해당 ref 조회는 404였다. 로컬에서 재확인한다. 자동 checkout/reset/clean은 하지 않는다.
- ZIP은 main 아래 일반 파일 2,390개(Java 2,176개)만 제공한다. Gradle build/settings/wrapper/test, app/src/main/java_clean, scripts/chatgpt_oauth_flow.py, zdr_guard.py, 별도 models.json은 제공되지 않았다.
- 경로는 ZIP 기준이다. 로컬 Gradle sourceSets를 읽어 실제 작업 경로와 test task를 매핑한다. 동일한 파일명을 찾았다는 이유만으로 다른 버전의 파일을 덮어쓰지 않는다.
- Java 17 / LangChain4j 1.0.1 유지. 의존성 버전, 포트 18180/18181/18182, DB 설정, OAuth, Brave, GPU 설정은 이번 패치에서 변경하지 않는다.
- 키는 AI_GATEWAY_API_KEY라는 환경변수명으로만 취급한다. 테스트는 프로세스 환경변수를 읽지 않고 가짜 credential supplier를 주입한다. 실제 공급자 호출은 금지한다.
- 기존 mode, free-only, allow-paid, 일일 예산, ZDR 및 redirect 차단 정책을 보존한다. 타임아웃 발생을 핑계로 유료 호출·다른 공급자 재시도·ZDR 해제를 추가하지 않는다.
- 기존 변경 및 다른 에이전트 소유 파일을 보존한다. git add -A, 일괄 커밋/푸시, 전체 인증 화면 도입은 하지 않는다.

## 1. 대상 파일 및 변경 사양

### 변경
`main/java/com/example/lms/assist/JevGatewayClient.java`
- FQCN: com.example.lms.assist.JevGatewayClient
- exchange(EvalRequest,List<ChoiceQuestion>): 59~128행. 특히 94~100행.
- 현행: request.timeout → send(ofInputStream) → readNBytes(maxResponseBytes+1).
- 변경: bounded subscriber를 쓰는 sendAsync와 monotonic deadline을 결합한다. 응답 전체 body가 도착하기 전에는 성공으로 취급하지 않는다.
- request.timeout과 connectTimeout은 유지하되 이것만으로 본문 마감시간이 보장된다고 간주하지 않는다.
- maxResponseBytes는 읽기를 다 끝낸 후 검사하는 값이 아니라 수신 도중 초과 즉시 취소하는 상한으로 유지한다.
- 401/402/403/429/5xx/redirect, 모델 검증, choice 파싱 등 기존 분기는 그대로 둔다.

### 신규 (작은 package-private 보조 클래스)
`main/java/com/example/lms/assist/BoundedHttpBody.java`
- FQCN: com.example.lms.assist.BoundedHttpBody
- 인터페이스:
  static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, int limit, long deadlineNanos)
      throws IOException, InterruptedException
- 같은 패키지의 제안 구현 BoundedHttpBody.java가 이 전달 묶음에 있다. 원본 프로젝트에는 아직 적용하지 않았다.
- HttpClient를 직접 생성하거나 credential을 소유하지 않는다. 호출자가 검증·구성한 기존 client/request만 받는다.
- TooLarge 예외는 httpStatus만 보존하고 provider body나 credential은 보존하지 않는다.

### 회귀 검증 대상 (우선 무수정)
`main/java/com/example/lms/assist/JevEvaluationRuntime.java`
- advise(): 81~140행. shadow finally 107행, on finally 126행.
- prefetch(): 244~290행. finally 279행.
- future.cancel(true)는 실행 중인 supplyAsync 작업의 즉시 중단을 보장하지 않는다. 따라서 gateway transport 자체가 정해진 시간 내에 반환해야 한다.
- 이번 패치에서는 슬롯 회수 finally를 추가하지 않는다. 중복 release로 max-in-flight 한도가 깨질 수 있다.
- decision-wait-ms보다 transport timeout이 길 수 있다. caller가 먼저 defer하더라도 transport의 request-timeout-ms 이내 종료는 필수다. 기존 shadow/late-completion 회계는 보존한다.

## 2. RED-first 테스트 명세
아래 FQCN은 기존 테스트 확인 결과가 아니라 새로 작성할 테스트 명세다. 실제 Gradle test source root 아래 해당 패키지로 만든다.

### com.example.lms.assist.JevGatewayClientBodyDeadlineTest
1. headersThenStalledBodyTimesOut: 200 headers와 '{'만 flush하고 fixture가 대기. requestTimeoutMs=300, 1,500ms 이내 timeout 응답이어야 한다. 기존 구현은 이 조건에서 실패해야 한다.
2. stalled403BodyAlsoTimesOut: 403 headers 이후 body가 멎어도 동일하다. 에러 응답이라고 무한히 읽지 않는다.
3. slowTrickleCannotExtendTotalDeadline: 일정 간격으로 작은 조각을 계속 보내는 경우에도 전체 300ms deadline이 갱신되지 않는다.
4. completeResponsePreservesChoiceAndModel: 정상 200 JSON의 모델/choice 결과가 그대로 통과한다.
5. bodyLimitBoundary: limit-1, limit, limit+1에서 앞의 둘은 body 수신을 허용하고 마지막은 oversized_response. 유효 JSON 여부와 전송 크기 판정은 분리한다.
6. oversized403StopsBeforeErrorParsing: 무한하거나 초과하는 에러 body도 즉시 중단한다.
7. interruptionCancelsOwnedTransport: 중단 후 cancelled 결과, 호출 스레드 interrupt flag 보존, 소유 subscription 취소.
8. failedRequestsNeverBecomeSuccess: 401/402/403/429/5xx, 잘못된 모델, 깨진 JSON, redirect는 기존 분류 유지. redirect target에는 요청 0건.

### com.example.lms.assist.JevEvaluationRuntimeCapacityRecoveryTest
9. onModeRecoversBothPermits: max-in-flight=2. 멎는 요청 2건 후 각각의 transport deadline이 지난 다음 정상 3번째 요청이 실제 loopback 서버에 도착한다. 단순히 status.ready 확인만으로 통과시키지 않는다.
10. shadowModeRecoversBothPermits: shadow에서도 동일한 슬롯 회복.
11. prefetchRecoversPermitAfterTransportTimeout: prefetch/await 경로에서도 이후 요청의 실제 도착을 확인한다.
12. permitsAreNotReleasedTwice: 최대 동시 transport 수가 2를 초과하지 않는다.
13. budgetSkipRemainsNonNetwork: clock을 2026-09-30으로 고정하고 free-only=true/allow-paid=false인 경우 budget_skip, HTTP 호출 0건.
14. noBillingOrPrivacyFallback: timeout/403이 allow-paid나 zero-data-retention 값을 바꾸지 않으며 추가 공급자 호출도 없다.

### com.example.lms.assist.BoundedHttpBodyTest
15. cancelBeforeOnSubscribe: timeout과 BodyHandler/onSubscribe 진입 순서가 뒤집혀도 새 subscription이 취소된다.
16. fragmentedLimitAndMultiBufferBatch: 여러 ByteBuffer의 누적 크기를 long으로 검사하여 초과·overflow를 방지한다.
17. resourcesTerminate: fixture, executor 및 subscription이 종료된다. 테스트 끝에 살아 있는 작업을 숨기기 위해 daemon만 설정해서 통과시키지 않는다.

fixture는 JDK HttpServer(127.0.0.1:임시포트)와 CountDownLatch로 구현한다. 고정 5초 sleep 대신 latch로 지연을 제어하고 finally에서 해제한다. 테스트 자체 timeout을 별도로 둔다. warm-up 후 시간 측정, 환경에 맞는 허용 오차 기록. timeout 수치만으로 interrupt 회복·슬롯 반환을 대체 검증하지 않는다.

## 3. 구현 요구사항
공식 native endpoint는 POST https://ai-gateway.vercel.sh/v1/evaluate 이다. SDK-only라는 과거 설명을 근거로 Node sidecar나 LangChain4j 업그레이드를 도입하지 않는다.

exchange()의 핵심 변경 예:
```java
long deadlineNanos = System.nanoTime()
        + TimeUnit.MILLISECONDS.toNanos(req.requestTimeoutMs());
var response = BoundedHttpBody.send(
        http, request, req.maxResponseBytes(), deadlineNanos);
int status = response.statusCode();
byte[] data = response.body();
// 아래 status/model/answers 분기는 기존 구현 유지.
```
TooLarge catch는 IOException catch보다 앞에 배치한다:
```java
} catch (BoundedHttpBody.TooLarge oversized) {
    return wireFail(oversized.httpStatus(), "oversized_response");
} catch (java.net.http.HttpTimeoutException timeout) {
    return wireFail(0, "timeout");
} catch (InterruptedException interrupted) {
    Thread.currentThread().interrupt();
    return wireFail(0, "cancelled");
} catch (java.io.IOException network) {
    return wireFail(0, "network");
}
```
- deadline은 재시도/청크 수신마다 새로 계산하지 않는다. 요청 전송을 시작하기 직전에 한 번 계산하고 남은 시간만 소비한다.
- BodySubscribers.ofByteArray로 무제한 받은 후 크기 검사하는 대체는 금지한다.
- Future만 취소하지 말고 직접 소유한 Flow.Subscription도 취소한다. onSubscribe 이전 취소 race를 테스트한다.
- ByteBuffer 청크를 누적하기 전에 (long)currentSize + incomingSize > limit를 검사한다.
- caller timeout 후 도착한 결과를 사용자에게 성공으로 다시 배포하지 않는다. 전송된 요청을 무료라고 가정하거나 자동 refund하지 않는다.
- 이 변경에는 GPU 추론이 필요하지 않다. 11434/11435, num_ctx, max-loaded-models 및 CUDA 환경변수는 건드리지 않는다.

## 4. 검증 완료 조건
완전한 로컬 저장소 root에서 실행한다. 해당 소스가 root test task에 속한 경우 아래 명령을 그대로 사용한다. 멀티모듈이면 sourceSets로 확인한 해당 모듈의 :test/:compileJava로만 매핑한다.

```powershell
git rev-parse --short=12 HEAD
git branch --show-current
git status --short
java -version
.\gradlew.bat --version
.\gradlew.bat compileJava --no-daemon

# 테스트를 먼저 추가한 RED 단계: 정상 컴파일 후 assertion 실패(exit 1)를 기록한다.
.\gradlew.bat test --tests 'com.example.lms.assist.JevGatewayClientBodyDeadlineTest' --no-daemon --rerun-tasks

# 구현 이후 GREEN 단계: 각 FQCN이 실제 실행되고 failures=0, errors=0, exit 0.
.\gradlew.bat test --tests 'com.example.lms.assist.JevGatewayClientBodyDeadlineTest' --tests 'com.example.lms.assist.JevEvaluationRuntimeCapacityRecoveryTest' --tests 'com.example.lms.assist.BoundedHttpBodyTest' --no-daemon --rerun-tasks

# 변경 패치 형식 확인. 커밋이나 푸시하지 않는다.
git diff --check
```

- 최초 compileJava 자체 실패는 BASELINE_BLOCKED로 기록하며 RED 재현으로 세지 않는다.
- NO-SOURCE, 테스트 0개, 전부 skipped는 통과가 아니다. test XML의 testcase 수, 실패 수, 소요 시간 및 JDK 버전을 첨부한다.
- 성공한 targeted suite를 20회 반복하고 각 exit 0, capacity recovery/active transport 상한을 확인한다. 실제 공급자를 호출하는 통합 테스트를 전체 test/check 실행으로 우발적으로 켜지 않는다.
- 이 ZIP만으로는 Gradle 실행이 불가능하다. 이 보고서에서 Java 17 Spring 통합 테스트나 공급자 실환경 검증이 완료되었다고 주장하지 않는다.
- LIVE 상태는 별도 승인된 예산과 데이터 정책 안에서만 승격한다. 이미 승인된 정책 범위를 임의 축소할 필요는 없지만, 이번 offline timeout 패치가 새 과금 승인으로 해석되어서는 안 된다.

## 현재 수행한 검증의 범위
- 기존 패턴의 독립 재현: request timeout 300ms여도 body 읽기가 1,400ms 후 계속 대기함. JDK 21.0.11에서 관측. Java 17 API 대상으로 컴파일했지만 Java 17 런타임 실행은 아님.
- 제안 helper 독립 검증: JDK 21.0.11, --release 17 컴파일. stalled 200/403 각각 약 304/300ms에 timeout, 초과 body 차단, 후속 정상 응답 수신. 정확한 결과는 bounded_body_probe_result.txt.
- 위 결과는 Spring/Gradle/JevEvaluationRuntime 슬롯 통합 검증이나 live Jev 응답 검증을 대신하지 않는다.

## 공식 근거
- Vercel native HTTP API: https://vercel.com/changelog/ai-gateway-now-supports-typesafe-clients-and-http-api-for-jev
- Java 17 BodySubscribers: https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscribers.html
- Vercel ZDR 플랜: https://vercel.com/changelog/zero-data-retention-no-prompt-training-on-ai-gateway
- Jev 무료 기간 종료: https://vercel.com/changelog/typesafe-ai-jev-now-available-on-ai-gateway
