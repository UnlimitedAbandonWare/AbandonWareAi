# Codex 소스 수정 지시서 — Dynamic RAG Orchestration Platform
# 설계 Self-Ask 교정 결과 기반 패치 지시서 v1.0

Role: Desktop patch executor (Safe Patch, minimum diff)
CWD: C:\AbandonWare\demo-1\demo-1\src
Active sourceSets:
  root runtime: main/java, main/resources
  :app shim:    app/src/main/java_clean, app/src/main/resources
Inactive (reference only): project/src/main/java, app/src/main/java, demo-1/, lms-core/

---

## 설계 Self-Ask 교정 결과 요약

입력된 설계 문서(Dual-Gear Routing / GraphRAG Brain State / Overdrive / CFVM / MoE / Matryoshka / ExtremeZ)를
아래 4가지 축으로 교정한 결과다.

| PHASE | 검증 항목 | 판정 | 교정 내용 |
|-------|-----------|------|-----------|
| Q1 소스 존재 | HYPERNOVA·Overdrive·ExtremeZ가 동일 조건에서 공존 가능 | FAIL | 우선순위 상수 + 상호 배타 가드 추가 |
| Q1 소스 존재 | 설계가 "이미 작동"이라 주장하는 CancelShieldExecutorService — imports 미등록 증거 | FAIL | AutoConfiguration imports 검증 후 누락 시 추가 |
| Q2 Bean wiring | FailurePatternOrchestrator가 WEB_STARVATION 기록 안 함 | FAIL | recordWebStarvation() 추가 |
| Q3 트리거 충돌 | 429/timeout/cancel 동일 failure 카운터 집계 | FAIL | 에러 분류 enum 분리 |
| Q3 트리거 충돌 | Overdrive + ExtremeZ 동시 활성 경로 존재 | FAIL | SpecialMode 상호 배타 가드 |
| Q4 fail-soft | QueryTransformer 실패 시 예외 상위 전파 | FAIL | try-catch + rawQuery fallback |
| Q4 fail-soft | Silent catch (log.debug + null return) 다수 | FAIL | TraceStore.put reason 1줄 추가 |
| Q1 소스 존재 | PromptBuilder 우회 (string concat) 경로 | FAIL | PromptBuilder.build(ctx) 경로로 리다이렉트 |

교정 기준으로 확정된 패치만 아래에 기술한다.

---

## P0 패치 블록

### P0-1: 실패 분류 enum 분리 — NightmareBreaker

**대상 파일**: `main/java/com/example/lms/infra/resilience/NightmareBreaker.java` [MODIFY]

**문제**
429/timeout/CancellationException이 모두 동일 실패 카운터를 증가시켜
breaker가 조기 OPEN 상태가 된다.

**교정된 설계**
- 429 + Retry-After 헤더 있음 → `RATE_LIMIT`: cooldown hint만. 카운터 증가 안 함.
- timeout → `SOFT_FAILURE`: 카운터 0.5 가중치.
- CancellationException → `CANCELLED`: 카운터 증가 안 함. breadcrumb만.

**최소 diff**

```java
// [추가] NightmareBreaker 내부 — 실패 분류 enum
public enum FailureKind {
    HARD,         // 실제 서버 에러 (5xx, parse fail)
    RATE_LIMIT,   // 429 — 카운터 증가 없음, Retry-After cooldown만
    SOFT_TIMEOUT, // timeout — 0.5 가중치
    CANCELLED     // Future.cancel — 카운터 증가 없음, breadcrumb만
}

// [수정] recordFailure(key, ...) 분기 추가
public void recordFailure(String key, FailureKind kind, @Nullable Duration retryAfter) {
    switch (kind) {
        case HARD        -> incrementHard(key);
        case SOFT_TIMEOUT -> incrementWeighted(key, 0.5);
        case RATE_LIMIT  -> applyCooldown(key, retryAfter);
        case CANCELLED   -> TraceStore.put("cancel.breadcrumb." + key, Instant.now().toString());
    }
}
```

**TraceStore 필수 키**
- `nightmare.breaker.<key>.openKind`
- `nightmare.breaker.<key>.openAt`
- `nightmare.breaker.<key>.lastFailureKind`

**검증 명령**
```bash
./gradlew.bat test --tests 'com.example.lms.infra.resilience.NightmareBreakerFailureKindTest' --no-daemon
./gradlew.bat compileJava --no-daemon -x test
```

**Rollback**: enum 추가이므로 기존 코드 영향 없음. `recordFailure()` 호출부는 기존 signature 유지.

---

### P0-2: Cancellation toxicity 차단 — invokeAll cancel(true) 제거

**대상 파일**: `main/java/ai/abandonware/nova/boot/exec/CancelShieldExecutorService.java` [MODIFY]

**문제**
`ExecutorService.invokeAll(tasks, timeout, TimeUnit)` 이후 JDK 구현이 미완료 Future에
`cancel(true)`를 호출해 thread interrupt를 전파한다.

**교정된 설계**
- `cancel(true)` 대신 `cancel(false)` 사용하는 CancelShieldFuture 래퍼 적용.
- interrupt 발생 시 무시하지 말고 `TraceStore.put("cancel.suppressed.<stage>", reason)` breadcrumb 남김.

**최소 diff**

```java
// [수정] invokeAll 래퍼 내부
@Override
public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                      long timeout, TimeUnit unit)
        throws InterruptedException {
    List<Future<T>> raw = delegate.invokeAll(tasks, timeout, unit);
    // cancel(true) 대신 cancel(false)로 교체
    raw.forEach(f -> {
        if (!f.isDone()) {
            boolean cancelled = f.cancel(false); // ← true → false
            if (cancelled) {
                TraceStore.put("cancel.suppressed." + Thread.currentThread().getName(),
                               "invokeAll timeout shield");
            }
        }
    });
    return raw;
}
```

**주의사항**
- `proceed()` 호출 없음. 이 파일에 @Around 없음.
- `openssl`, `opnessl` 키 건드리지 않음.

**검증 명령**
```bash
./gradlew.bat test --tests 'ai.abandonware.nova.boot.exec.CancelShieldExecutorServiceTest' --no-daemon
```

---

### P0-3: Starvation Ladder 상태기계 단일화

**대상 파일**: `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java` [MODIFY]

**문제**
starvation fallback ladder (officialOnly → NOFILTER_SAFE → remergeOnce → cacheOnly → vectorFallback)가
여러 서비스에 산재되어 있어 순서가 보장되지 않는다.

**교정된 설계**
- `StarvationStage` enum을 HybridWebSearchProvider 내부에 선언.
- 각 단계 진입·이탈 시 TraceStore에 현재 stage 기록.
- allSkipped 상태에서도 vectorFallback을 최후 시도.

**최소 diff**

```java
// [추가] 내부 enum
private enum StarvationStage {
    OFFICIAL_ONLY, NOFILTER_SAFE, REMERGE_ONCE, CACHE_ONLY, VECTOR_FALLBACK, EXHAUSTED
}

// [추가] starvation 진입 시 TraceStore 기록
private void traceStage(StarvationStage stage, String reason) {
    TraceStore.put("starvation.currentStage", stage.name());
    TraceStore.put("starvation.reason." + stage.name(), reason);
    TraceStore.inc("starvation.stageEntry." + stage.name());
}

// [수정] 기존 fallback 로직 앞에 traceStage() 삽입 — 로직 자체는 변경 안 함
```

**필수 TraceStore 키**
```
outCount
starvation.currentStage
starvation.reason.<STAGE>
cacheOnly.merged.count
vectorFallback.used
rescueMerge.used
starvationFallback.trigger
poolSafeEmpty
```

**검증 명령**
```bash
./gradlew.bat test --tests 'com.example.lms.search.provider.HybridWebSearchProviderStarvationTest' --no-daemon
```

---

### P0-4: SpecialMode 상호 배타 가드 — Overdrive / ExtremeZ / HYPERNOVA 충돌 차단

**대상 파일**: `main/java/ai/abandonware/nova/config/Zero100EngineProperties.java` [MODIFY]

**문제 (Self-Ask Q3 교정 결과)**
설계 문서는 Overdrive, ExtremeZ, HYPERNOVA를 각각 독립 트리거로 설명하지만
동일 조건(저결과 + 모순 + 긴급어)에서 세 모드가 동시 활성화 가능하다.

**교정된 설계**
- 우선순위: `HYPERNOVA > ExtremeZ > OVERDRIVE`
- 한 번에 하나의 SpecialMode만 활성.
- 우선순위는 상수로 선언하여 코드에 명시.

**최소 diff**

```java
// [추가] Zero100EngineProperties 또는 별도 SpecialModeGuard 내부
public enum SpecialMode {
    NONE, OVERDRIVE, EXTREME_Z, HYPERNOVA;

    /** 우선순위: 높을수록 먼저. HYPERNOVA=3, ExtremeZ=2, Overdrive=1, None=0 */
    public int priority() {
        return switch (this) {
            case HYPERNOVA -> 3;
            case EXTREME_Z -> 2;
            case OVERDRIVE -> 1;
            case NONE      -> 0;
        };
    }
}

// [추가] 단일 선택 메서드
public static SpecialMode selectHighest(SpecialMode... candidates) {
    SpecialMode selected = SpecialMode.NONE;
    for (SpecialMode c : candidates) {
        if (c.priority() > selected.priority()) selected = c;
    }
    TraceStore.put("specialMode.selected", selected.name());
    return selected;
}
```

**검증 명령**
```bash
./gradlew.bat test --tests '*SpecialMode*' --no-daemon
./gradlew.bat compileJava --no-daemon -x test
```

---

### P0-5: QueryTransformer fail-soft — raw query 자동 우회

**대상 파일**: `main/java/com/example/lms/transform/QueryTransformer.java` [MODIFY]

**문제**
transformer 실패 시 예외가 상위로 전파되어 web/vector 검색 전체가 중단된다.

**교정된 설계**
- 변환 실패(timeout/breaker-open/빈 결과) → raw query로 즉시 우회.
- 우회 시 TraceStore에 `queryTransformer.bypassed=true`, `queryTransformer.reason=...` 기록.
- PromptBuilder.build(ctx) 경로는 유지.

**최소 diff**

```java
public String transform(String rawQuery, PromptContext ctx) {
    try {
        String result = doTransform(rawQuery, ctx);
        if (result == null || result.isBlank()) {
            TraceStore.put("queryTransformer.bypassed", "true");
            TraceStore.put("queryTransformer.reason", "empty_result");
            return rawQuery;
        }
        return result;
    } catch (Exception e) {
        // [수정] 기존 throw → fail-soft fallback
        TraceStore.put("queryTransformer.bypassed", "true");
        TraceStore.put("queryTransformer.reason", e.getClass().getSimpleName());
        log.warn("[demo1][qtx] fail-soft: bypassing transformer, reason={}", e.getClass().getSimpleName());
        return rawQuery; // raw query 우회
    }
}
```

**검증 명령**
```bash
./gradlew.bat test --tests 'com.example.lms.transform.QueryTransformerFailSoftTest' --no-daemon
```

---

## P1 패치 블록

### P1-1: FailurePatternOrchestrator — WEB_STARVATION 기록 추가

**대상 파일**: `main/java/ai/abandonware/nova/orch/failpattern/FailurePatternOrchestrator.java` [MODIFY]
**대상 파일**: `main/java/ai/abandonware/nova/orch/failpattern/FailurePatternKind.java` [MODIFY]

**최소 diff**

```java
// FailurePatternKind.java — enum 값 추가
WEB_STARVATION,   // web outCount=0 반복

// FailurePatternOrchestrator.java — 메서드 추가
public void recordWebStarvation(String provider, String ladderStage) {
    TraceStore.put("failpattern.web.starvation.provider", provider);
    TraceStore.put("failpattern.web.starvation.ladderStage", ladderStage);
    TraceStore.inc("failpattern.web.starvation.count");
    record(FailurePatternKind.WEB_STARVATION);
}
```

HybridWebSearchEmptyFallbackAspect에서 starvation 확정 시 1줄만 추가:
```java
failurePatternOrchestrator.recordWebStarvation(provider, currentStage.name());
```

---

### P1-2: PromptBuilder 우회 경로 차단

**대상 파일**: 아래 파일에서 `String.format` / `+` 연산으로 최종 prompt를 만드는 경로 [MODIFY]

검색 명령 (Codex가 먼저 실행):
```bash
rg -n "String\.format.*prompt\|promptStr\s*=\s*\"\|prompt\s*\+=\s*\"" main/java --type java
```

발견된 경로마다:
```java
// BEFORE
String prompt = "사용자 질문: " + query + "\n컨텍스트: " + context;

// AFTER
ctx.setQuery(query);
ctx.setContext(context);
String prompt = promptBuilder.build(ctx);
```

`PromptContext`에 필요한 필드가 없으면 필드 추가 (PromptContext만 수정, 다른 로직 변경 안 함).

---

### P1-3: Silent catch → TraceStore reason 추가

**대상 파일**: 아래 패턴이 있는 모든 파일 [MODIFY]

검색 명령:
```bash
rg -n "catch.*Exception.*\n.*log\.debug" main/java --type java -A 2
```

발견된 패턴마다 `log.debug` 다음 줄에 1줄만 추가:
```java
} catch (Exception e) {
    log.debug("Error: {}", e.getMessage());
    TraceStore.put("failsoft.<ClassName>.<methodName>.reason", e.getClass().getSimpleName()); // [추가]
    return /* 기존 null/empty 반환 유지 */;
}
```

raw 예외 메시지에 secret이 포함될 수 있으므로 `e.getMessage()` 대신 `e.getClass().getSimpleName()`만 TraceStore에 기록.

---

### P1-4: AutoConfiguration imports 검증

**대상 파일**: `main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` [VERIFY/MODIFY]

검사 명령:
```bash
Get-Content main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

아래 클래스가 누락되어 있으면 1줄씩 추가:
```
ai.abandonware.nova.autoconfig.NovaOrchestrationAutoConfiguration
ai.abandonware.nova.autoconfig.NovaFailurePatternAutoConfiguration
ai.abandonware.nova.autoconfig.NovaOpsStabilizationAutoConfiguration
ai.abandonware.nova.autoconfig.NovaZero100AutoConfiguration
```

중복 추가 금지. 이미 있으면 수정하지 않음.

---

## P2 패치 블록 (P0/P1 검증 통과 후에만)

### P2-1: Naver credential bridge 단일화

**대상 파일**: `main/java/com/example/lms/service/search/NaverCredentialBridge.java` [MODIFY]

```java
// 우선순위: naver.keys → NAVER_KEYS env → naver.client-id:naver.client-secret 조합
// 로그에 남기는 것: sourceName, keysPresent=true/false, parsedCount=N, disabledReason
// 절대 금지: id/secret/token 실제 값 로그
```

---

### P2-2: Matryoshka 슬라이싱 disable reason 표준화

**대상 파일**: `main/java/com/example/lms/embed/OllamaEmbeddingModel.java` [MODIFY]

```java
// 슬라이싱 dim이 0이거나 원본 dim보다 크면:
TraceStore.put("embedding.matryoshka.disabled", "true");
TraceStore.put("embedding.matryoshka.disabledReason", "invalid_slice_dim:" + sliceDim);
// 슬라이싱 없이 원본 벡터 반환 (fail-soft)
```

---

## 전체 검증 명령 세트

```powershell
# 1. 의존성 순도 확인
.\gradlew.bat checkLangchain4jVersionPurity --no-daemon

# 2. 컴파일
.\gradlew.bat compileJava --no-daemon -x test

# 3. 핵심 테스트
.\gradlew.bat test --tests '*NightmareBreaker*' `
              --tests '*CancelShield*' `
              --tests '*HybridWebSearch*' `
              --tests '*SpecialMode*' `
              --tests '*QueryTransformer*' `
              --no-daemon

# 4. 로그 secret scan (count만 확인, 값 출력 금지)
rg -c "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" logs/ build/ 2>$null
```

---

## 보류 항목 (Deferred — 소스 증거 필요)

| 항목 | 이유 | evidence_needed |
|------|------|----------------|
| CFVM Boltzmann 온도 적응화 | 기존 hardcode 증거 필요 | `rg -n "temperature\|boltzmann" main/java/com/abandonwareai/cfvm` |
| GraphRAG BrainSnapshot 신규 추가 | 대규모 신규. P0 완료 후 | `rg -n "BrainSnapshot\|RagGraphState" main/java` |
| MoE ArtPlate 10번째 플레이트 라우팅 | ArtPlateEvolver 소스 확인 필요 | `rg -n "ArtPlateEvolver\|dynamicPlate" main/java` |
| application.properties vs .yml 충돌 정리 | 42KB 설정 파일 구조 파악 먼저 | 설정 파일 전체 diff 검토 후 별도 지시서 작성 |

---

## 완료 산출물 형식

패치 완료 후 아래 형식으로 보고한다:

```markdown
## 요약
- 변경 목적 2~5줄

## 핵심 답변
- P0 수정 파일과 이유
- 실제 적용한 최소 diff 요약

## 근거/로그/파일경로
- 파일경로 + 변경 라인 범위
- 검증 명령 결과 (pass/fail)
- 보류 항목 evidence_needed

## 다음 단계
- 다음 가장 시급한 패치 1개
```
