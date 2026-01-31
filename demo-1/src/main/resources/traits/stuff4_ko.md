# Jammini Memory 강화 — {스터프4} 스타일 지시서 (v1.1)

목표: `src111_merge15`의 **추가기능 전용 원자 메모리**를 Jammini에 이식하고, 중복/미완성 흔적을 정리해 **실행 가능한 구성**으로 고정.

## 0) 산출물
- `JamminiMemory.atomic.yaml` — 운영지향 키-값 메모리(토글/엔드포인트/경로)
- `JamminiMemory.merge.patch.md` — 소스 수정 타점/패치 스니펫
- `prompts.manifest.yaml` — 에이전트/트레이트 병합 매니페스트(예시)

## 1) 구성/토글 주입 (application.yml)
```yaml
retrieval:
  vector:
    enabled: true
naver:
  hedge:
    enabled: true
    delay-ms: 120
  search:
    timeout-ms: 2500
    web-top-k: 10
    cache:
      enabled: true
      ttl-seconds: 3600
onnx:
  enabled: true
probe:
  search:
    enabled: true
upstash:
  cache:
    ttl-seconds: 3600
gate:
  citation:
    enabled: true
    min: 3
  finalSigmoid:
    enabled: true
    k: 12.0
    x0: 0.0
```

## 2) 모듈 경로 정리(중복 제거)
- **컴파일 대상**은 `app/` 모듈 + `src/main/java` 만 남기고, 아래는 **빌드 제외**:
  - `src/backup/_abandonware_backup/**`
  - `src/app/src/main/java_clean/**`
  - `src/extras/gap15-stubs_v1/**`
- Gradle 예시:
```kotlin
sourceSets {
  val main by getting {
    java.exclude("**/_abandonware_backup/**",
                 "**/java_clean/**",
                 "extras/**",
                 "backup/**")
  }
}
```

## 3) 리트리버/재랭커 배선
- 순서: `SelfAsk/Analyze` → `DynamicRetrievalHandlerChain` → `[Web, Vector, KG]` → `RrfFusion(WeightedRRF)` → `BiEncoder` → `ONNX CrossEncoder`.
- 누락시 파일 배치:
  - `service/rag/handler/DynamicRetrievalHandlerChain.java`
  - `strategy/RetrievalOrderService.java`
  - `service/rag/fusion/RrfFusion.java`, `service/rag/fusion/WeightedRRF.java`
  - `service/onnx/OnnxRuntimeService.java`, `service/onnx/OnnxCrossEncoderReranker.java`
- ONNX 동시성 게이트:
```java
@Bean
public Semaphore onnxLimiter(@Value("${zsys.onnx.max-concurrency:2}") int max) {
  return new Semaphore(Math.max(1, max));
}
```

## 4) 캐시/레이트리미팅/싱글플라이트
- Upstash 캐시: `infra/upstash/UpstashBackedWebCache`, `UpstashRateLimiter` 와이어링.
- 단일 비행(SingleFlight) 실행기: `infra/cache/SingleFlightExecutor` 로 통일하고 **패키지 중복본 제거**.
- 정책: 동일 키의 동시 웹요청은 선두 1건만 외부 호출, 후행은 결과 공유.

## 5) 관측/테스트
- 헤더 트레이싱: `RequestIdHeaderFilter`, `TraceFilter`, `TraceContext`.
- SSE 실시간: `SseEventPublisher`, `LoggingSseEventPublisher`.
- Soak/Probe 켜기:
  - `/internal/soak/run`, `/api/probe/search` (admin-token 헤더 필요)

## 6) 안전장치
- `DomainWhitelist` + `CitationGate(min=3)` + `AnswerSanitizer` 체인 후단 고정.
- 추천/게임 도메인은 `GenshinRecommendationSanitizer` 활성.

## 7) DBVM‑X/OCR 통합
- OCR 스팬/청크를 표준 컨텍스트 포맷 `{id,title,snippet,source,score,rank}` 로 변환 후 `RRF` 에 합류.

---
세부 패치는 `JamminiMemory.merge.patch.md` 참고.
