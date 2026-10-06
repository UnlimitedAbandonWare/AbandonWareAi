---
doc_id: TRI-CTX-07
title: Spring Boot WebClient SSE and Resilience4j 2.2.0 Invariant Cheatsheet
created_at: "2026-10-05T21:40:00+09:00"
expires_at: "PERPETUAL"
ttl_days: null
lifecycle: INVARIANT
validity_basis: "build.gradle.kts 의존성 및 ChatApiController, SSE 스트리밍 표준"
---

# 07. WebClient SSE & Resilience4j 2.2.0 불변 치트시트 (Tri-Agent 공유)

> demo-1 체크아웃에서 SSE(Server-Sent Events)와 Resilience4j를 건드릴 때
> 웹서치 없이 바로 쓰는 규격 카드. 모든 행은 `main/java`·`build.gradle.kts`
> 라이브 확인분이며, 일반 웹 문서의 안티패턴(전체 스트림 Mono.timeout, 프록시
> 버퍼링 방치)을 명시적으로 차단한다.

## 1. 이 리포지토리의 SSE 패턴 (서버 → 클라이언트)

### 서버측 (Spring MVC + Reactor)

```java
// main/java/com/example/lms/api/ChatApiController.java:1610
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<ChatStreamEvent>> chatStream(...)
```

- 이벤트 생성: `ServerSentEvent.<T>builder(e).event(e.type()).build()`
  (ChatApiController.java:3298 `sse()` 헬퍼).
- 다중 구독·재접속(attach) 허브는 `Sinks.Many<ServerSentEvent<T>>` —
  `Sinks.many()` + `sink.asFlux()` (ChatApiController.java:1723, 3119;
  ChatRunRegistry.java:133, 406).
- keep-alive는 데이터가 아닌 **SSE comment**:
  `ServerSentEvent.builder().comment("keepalive").build()`
  (ChatRunRegistry.java:438, ConversateSessionService.java:328).
- `produces = MediaType.TEXT_EVENT_STREAM_VALUE`면 Spring이
  `Content-Type: text/event-stream;charset=UTF-8`을 설정한다. 수동 헤더
  조립 금지.

### 클라이언트측 (WebClient → Flux)

```java
// ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java:412,565
.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
```

- SSE 본문은 반드시 `ParameterizedTypeReference<ServerSentEvent<String>>`로
  받는다 — `bodyToFlux(String.class)`는 이벤트 프레임(event:/data: 경계)을
  잃는다. 클러스터 재접속도 동일 패턴 (ChatRunCluster.java:143).
- 요청 측은 `.accept(MediaType.TEXT_EVENT_STREAM)`를 명시
  (OpenAiResponsesChatModel.java:396; 프런트는 chat.js:7083
  `Accept: text/event-stream`).

## 2. 필수 응답 헤더 계약

| 헤더 | 값 | 근거 |
|---|---|---|
| `Content-Type` | `text/event-stream;charset=UTF-8` | `produces=TEXT_EVENT_STREAM_VALUE` (ChatApiController:1610) |
| `Cache-Control` | `no-cache` | 브라우저/프록시 캐시로 이벤트가 묶이면 스트리밍 의미 소멸 |
| `X-Accel-Buffering` | `no` | nginx/cloudflared 역방향 프록시 버퍼링 끄기 — 없으면 이벤트가 청크로 묶여 클라이언트 지연 |

## 3. 타임아웃 격리 (가장 흔한 실수)

- **연결 수준**: Netty `HttpClient`의 `responseTimeout`/connect 계열은
  **첫 바이트·연결 수립**에만 적용한다.
- **스트림 수준**: 개별 이벤트 간 공백 감시는 `Flux.timeout(Duration)` 또는
  읽기-스톨(read-stall) 감시로 분리한다. 이 리포지토리는 OAuth 스트림에
  "network read-stall protection, renewed by bytes including SSE
  heartbeats"를 적용한다 (OpenAiResponsesChatModel.java:376-378 주석 계약).
- **금지**: 전체 SSE 스트림을 `Mono.timeout(...)`으로 감싸는 것 —
  스트림은 설계상 장기 열림이므로 전체 타임아웃은 정상 동작을 끊는다.
  벽시계 상한은 `public.request-budget.max-time-budget-ms`
  (configs/api-routing.yaml:10-17) 같은 **예산 필드**로 표현한다.

## 4. Resilience4j 2.2.0 (build.gradle.kts 고정)

- 의존성: `resilience4j-spring-boot3:2.2.0` (:109), `resilience4j-reactor:2.2.0`
  (:120) — 버전은 BOM이 아니라 명시 고정이다. 다른 버전 제안 금지.
- 리액티브 적용 패턴 (연산자, 어노테이션 아님):

```java
// GeminiGateway.java:388 — Flux/Mono에 CB를 transform으로 입힌다
attempt = attempt.transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
```

- 수동 빈 패턴 (ResilienceConfig.java):

```java
@Bean CircuitBreaker llmCircuitBreaker() {          // :20
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .slidingWindowType(SlidingWindowType.COUNT_BASED) /* ... */ .build();
    return CircuitBreaker.of("llm", config);
}
@Bean TimeLimiter llmTimeLimiter() {                 // :36
    return TimeLimiter.of(Duration.ofSeconds(20));
}
```

- 프로퍼티 인스턴스 방식도 지원 (application-ultra.properties:254-258 예시):

```properties
resilience4j.timelimiter.instances.<name>.timeoutDuration=5s
resilience4j.timelimiter.instances.<name>.cancelRunningFuture=true
resilience4j.retry.instances.<name>.maxAttempts=2
resilience4j.retry.instances.<name>.waitDuration=200ms
resilience4j.circuitbreaker.instances.<name>.slidingWindowSize=20
resilience4j.circuitbreaker.instances.<name>.failureRateThreshold=50
```

- `cancelRunningFuture=true`: TimeLimiter 타임아웃 시 진행 중 future를
  취소해 스레드·연결 누수를 막는다 — SSE/장기 호출에 특히 중요.
- 레지스트리 이벤트 소비 패턴: `RegistryEventConsumer<CircuitBreaker>`
  (FailurePatternOrchestrator.java:257 — OPEN 전이 시 학습 기록).
- 주의: ChatWorkflow.java:424 주석상 CB/TimeLimiter 빈이 주입됐지만
  실제 호출부에 안 쓰인 이력이 있다 — 빈 존재 ≠ 적용. 패치 전에
  `transformDeferred`/`decorate*` 호출 지점을 grep으로 확인한다.

## 5. 안티패턴 방어표

| 안티패턴 | 증상 | 정답 |
|---|---|---|
| 전체 SSE 스트림에 `Mono.timeout` | 정상 장기 스트림이 중간에 끊김 | 연결 타임아웃 + 이벤트 간 `Flux.timeout`/read-stall 분리 |
| `bodyToFlux(String.class)`로 SSE 수신 | event:/data: 프레임 소실 | `ParameterizedTypeReference<ServerSentEvent<String>>` |
| 프록시 버퍼링 방치 | 이벤트가 묶여서 늦게 도착 | `X-Accel-Buffering: no` + `Cache-Control: no-cache` |
| Resilience4j 버전 임의 상향/하향 | build.gradle.kts 고정값과 불일치 | 2.2.0 유지 (`:109`, `:120`) |
| `@CircuitBreaker` 어노테이션만 추가 | AOP 미적용·테스트 공회전 | reactor 연산자 `CircuitBreakerOperator.of`로 Flux에 적용 |
| Sinks.Many에 다중 스레드 `tryEmitNext` 직행 | EmitResult 실패·이벤트 유실 | emit 핸들러 직렬화 — 기존 ChatStreamEmitter 패턴 재사용 |

## 관련 문서

- [01_ARCHITECTURAL_INVARIANTS.md](01_ARCHITECTURAL_INVARIANTS.md) — 루트 SSOT·검증-판정 분리
- [06_SPRING_BOOT_3_3_4_LANGCHAIN4J_1_0_1_SSOT.md](06_SPRING_BOOT_3_3_4_LANGCHAIN4J_1_0_1_SSOT.md) — 플랫폼 버전 순수성 게이트·LlmConfig 빈 배선
- [README.md](README.md) — 카탈로그 인덱스와 TTL 정책
