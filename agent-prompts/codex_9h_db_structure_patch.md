# 코덱스 9시간 자율 패치 — DB 구조 갭 소스수정 지시서

> **대상**: Codex / Antigravity / OpenCode 자율 에이전트
> **목적**: 설계 문서(Dynamic RAG Orchestration Platform)와 실제 소스 간 **DB 영속화 갭**을 정량 측정하고, 최소 diff로 패치
> **모드**: Safe Patch. 대규모 리팩터링 금지. 필요한 파일/라인만 최소 diff로 수정
> **시간**: 9시간 (4 Phase)
> **필수 준수**: `Abandon.md`, `AGENTS.md`의 모든 규칙

---

## 0. 이 지시서의 사용법

1. **먼저 도구를 실행**한다. 도구 없이 패치하지 않는다.
2. 각 Phase의 패치는 **독립적으로 컴파일 가능**해야 한다.
3. 패치 후 반드시 해당 Phase의 검증 명령을 실행한다.
4. **실제 secret 값을 코드/로그/문서에 출력하지 않는다.**
5. `PromptBuilder.build(ctx)` 우회 금지. 문자열 결합으로 최종 프롬프트를 직접 만들지 않는다.
6. `dev.langchain4j:*`는 `1.0.1` 계열만 허용한다.
7. Spring Bean Wiring을 깨지 않는다.
8. 파일 전체 재작성 금지. 최소 diff + 체크포인트 로그만 추가한다.

---

## 1. 사전 준비 도구

### 1.1 정량 측정 도구 (이미 생성됨)

```text
scripts/db_gap_scanner.py     — Python 정량 분석기
scripts/db_gap_scanner.ps1    — PowerShell 래퍼 (Python 없이도 독립 실행 가능)
scripts/db_structure_api.py   — REST API 서버 (Codex가 런타임에 조회)
```

### 1.2 도구 실행 명령

```powershell
# 베이스라인 수집
python scripts/db_gap_scanner.py --root main/java --output data/db-gap-report/before

# 또는 PowerShell만
powershell -File scripts/db_gap_scanner.ps1 -OutputDir data\db-gap-report\before

# REST API 서버 (별도 터미널)
python scripts/db_structure_api.py --root main/java --port 9090

# API 조회
curl http://localhost:9090/api/gap-matrix
curl http://localhost:9090/api/volatile-audit
curl http://localhost:9090/api/trace-keys
curl http://localhost:9090/api/subsystem/S02_CFVM
```

---

## 2. 현재 갭 매트릭스 (소스 검증 완료)

| # | 서브시스템 | 영속화 | 갭 | 패치 대상 |
|---|-----------|--------|----|---------| 
| S01 | Overdrive | ❌ 인메모리 | 🟡 MEDIUM | Phase 3 |
| S02 | CFVM | ❌ ArrayDeque(9) | 🔴 CRITICAL | Phase 2 |
| S03 | MoE | 🟡 부분 (StrategyPerformance만 DB) | 🟡 MEDIUM | Phase 3 |
| S04 | Matryoshka | ✅ 설정 기반 | 🟢 LOW | — |
| S05 | ExtremeZ | ❌ 3중 중복 + 비영속 | 🔴 CRITICAL | Phase 2 |
| S06 | HYPERNOVA | ❌ 인메모리 | 🟡 MEDIUM | Phase 3 |
| S07 | TraceStore | ❌ ThreadLocal | 🔴 CRITICAL | Phase 2 |
| S08 | OpenAI Adapter | ✅ 설정 기반 | 🟢 LOW | — |

---

## Phase 1: 정량 측정 + 베이스라인 (0h ~ 1.5h)

### 목표
패치 전 정확한 수치 베이스라인을 확보한다.

### 실행

```powershell
# 1. 도구 동작 확인
python -m py_compile scripts/db_gap_scanner.py
python -m py_compile scripts/db_structure_api.py

# 2. 베이스라인 수집
python scripts/db_gap_scanner.py --root main/java --output data/db-gap-report/before

# 3. 결과 확인
type data\db-gap-report\before\gap_matrix.json

# 4. Gradle 베이스라인
gradlew.bat compileJava
gradlew.bat checkLangchain4jVersionPurity
gradlew.bat checkSourceSetHygiene
```

### 검증 기준
- `gap_matrix.json`이 생성되고 8개 서브시스템 모두 포함
- `CRITICAL` 갭이 3개 (S02, S05, S07)
- `compileJava` 성공
- version purity 통과

### 기록할 베이스라인 수치
```json
{
  "phase": "before",
  "critical_gaps": 3,
  "medium_gaps": 3,
  "entities_total": "?",
  "repositories_total": "?",
  "trace_keys_total": "?",
  "extremez_duplicates": 3,
  "compile_status": "PASS/FAIL"
}
```

---

## Phase 2: CRITICAL 갭 패치 (1.5h ~ 5h)

### 2A. S02 CFVM — 볼츠만 매트릭스 영속화 (1.5h ~ 2.5h)

#### 문제
`RawMatrixBuffer`는 `ArrayDeque<Entry>(capacity=9)` 인메모리.
설계: "9개 가상 매트릭스 + 볼츠만 학습 결과가 세션 간 유지"
현실: 프로세스 재시작 시 학습 결과 전부 소멸.

#### 패치 대상 파일

**[NEW] `main/java/com/example/lms/cfvm/CfvmSnapshot.java`**

```java
package com.example.lms.cfvm;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * Durable snapshot of CFVM Boltzmann weights.
 * Persists the 9-tile matrix state so it survives process restarts.
 */
@Entity
@Table(name = "cfvm_snapshot", indexes = {
    @Index(name = "idx_cfvm_snap_created", columnList = "created_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CfvmSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "weights_json", nullable = false, length = 512)
    private String weightsJson;

    @Column(name = "boltzmann_temp", nullable = false)
    private double boltzmannTemp;

    @Column(name = "buffer_size", nullable = false)
    private int bufferSize;

    @Column(name = "dominant_slot", nullable = false)
    private int dominantSlot;

    @Column(name = "session_hash", length = 64)
    private String sessionHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

**[NEW] `main/java/com/example/lms/cfvm/CfvmSnapshotRepository.java`**

```java
package com.example.lms.cfvm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface CfvmSnapshotRepository extends JpaRepository<CfvmSnapshot, Long> {
    Optional<CfvmSnapshot> findTopByOrderByCreatedAtDesc();
}
```

**[MODIFY] `main/java/com/example/lms/cfvm/RawMatrixBuffer.java`**

최소 추가 (기존 코드 유지, 아래 3개 메서드만 추가):

```java
/** Restore weights from a persisted snapshot. */
public synchronized void restoreFromSnapshot(double[] savedWeights, double savedTemp) {
    if (savedWeights == null || savedWeights.length != capacity) return;
    System.arraycopy(savedWeights, 0, this.weights, 0, capacity);
    this.boltzmannTemp = normalizeBoltzmannTemp(savedTemp);
    TraceStore.put("cfvm.rawBuffer.restoredFromSnapshot", true);
    TraceStore.put("cfvm.rawBuffer.boltzmannTemp", this.boltzmannTemp);
}

public synchronized double[] exportWeights() {
    return Arrays.copyOf(weights, capacity);
}

public synchronized double getBoltzmannTemp() {
    return boltzmannTemp;
}
```

**[NEW] `main/java/com/example/lms/cfvm/CfvmSnapshotService.java`**

```java
package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CfvmSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(CfvmSnapshotService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectProvider<RawMatrixBuffer> bufferProvider;
    private final ObjectProvider<CfvmSnapshotRepository> repoProvider;

    public CfvmSnapshotService(ObjectProvider<RawMatrixBuffer> bufferProvider,
                                ObjectProvider<CfvmSnapshotRepository> repoProvider) {
        this.bufferProvider = bufferProvider;
        this.repoProvider = repoProvider;
    }

    @jakarta.annotation.PostConstruct
    public void restoreOnStartup() {
        CfvmSnapshotRepository repo = repoProvider.getIfAvailable();
        RawMatrixBuffer buffer = bufferProvider.getIfAvailable();
        if (repo == null || buffer == null) {
            TraceStore.put("cfvm.snapshot.restore.skipped", "bean_unavailable");
            return;
        }
        repo.findTopByOrderByCreatedAtDesc().ifPresentOrElse(
            snapshot -> {
                try {
                    double[] weights = mapper.readValue(snapshot.getWeightsJson(), double[].class);
                    buffer.restoreFromSnapshot(weights, snapshot.getBoltzmannTemp());
                    TraceStore.put("cfvm.snapshot.restored", true);
                    TraceStore.put("cfvm.snapshot.restored.id", snapshot.getId());
                    log.info("[CFVM] Restored Boltzmann weights from snapshot id={}", snapshot.getId());
                } catch (JsonProcessingException e) {
                    TraceStore.put("cfvm.snapshot.restore.error", e.getClass().getSimpleName());
                    log.warn("[CFVM] Failed to parse snapshot: {}", e.getClass().getSimpleName());
                }
            },
            () -> {
                TraceStore.put("cfvm.snapshot.restore.skipped", "no_previous_snapshot");
                log.info("[CFVM] No previous snapshot, starting fresh");
            }
        );
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void periodicSnapshot() {
        RawMatrixBuffer buffer = bufferProvider.getIfAvailable();
        CfvmSnapshotRepository repo = repoProvider.getIfAvailable();
        if (buffer == null || repo == null || buffer.size() == 0) return;
        try {
            double[] weights = buffer.exportWeights();
            CfvmSnapshot snapshot = CfvmSnapshot.builder()
                .weightsJson(mapper.writeValueAsString(weights))
                .boltzmannTemp(buffer.getBoltzmannTemp())
                .bufferSize(buffer.size())
                .dominantSlot(dominantSlot(weights))
                .build();
            repo.save(snapshot);
            TraceStore.put("cfvm.snapshot.saved", true);
        } catch (Exception e) {
            TraceStore.put("cfvm.snapshot.save.error", e.getClass().getSimpleName());
            log.debug("[CFVM] Snapshot save failed: {}", e.getClass().getSimpleName());
        }
    }

    private static int dominantSlot(double[] w) {
        int slot = 0;
        for (int i = 1; i < w.length; i++) { if (w[i] > w[slot]) slot = i; }
        return slot;
    }
}
```

#### 검증

```powershell
gradlew.bat compileJava
gradlew.bat test --tests "*Cfvm*"
gradlew.bat bootRun --args="--spring.main.web-application-type=none --spring.profiles.active=local"
# 15초 대기 후 종료. 로그에 [CFVM] 확인
```

#### RED/GREEN 테스트

```java
// [NEW] src/test/java/com/example/lms/cfvm/CfvmSnapshotRoundTripTest.java
@Test void boltzmannWeightsSurviveRestart() {
    RawMatrixBuffer buf1 = new RawMatrixBuffer(9, 0.35);
    buf1.updateWeight(3, 0.9);
    double[] exported = buf1.exportWeights();
    
    RawMatrixBuffer buf2 = new RawMatrixBuffer(9, 0.35);
    buf2.restoreFromSnapshot(exported, buf1.getBoltzmannTemp());
    assertThat(buf2.getWeights()[3]).isGreaterThan(0.0);
}
```

---

### 2B. S05 ExtremeZ — 3중 중복 해소 (2.5h ~ 3.5h)

#### 문제
`ExtremeZSystemHandler`가 3개 패키지에 존재:
- `com.example.lms.extreme.ExtremeZSystemHandler` — 레거시
- `com.example.lms.nova.extremez.ExtremeZSystemHandler` — 레거시 alias
- `com.example.lms.service.rag.burst.ExtremeZSystemHandler` — **canonical**

#### 패치 규칙
- 파일을 삭제하지 않는다 (Safe Patch)
- 레거시 2개에서 `@Component`/`@Service` 어노테이션만 제거
- `@Deprecated(since = "2026-06", forRemoval = true)` 추가
- canonical에 `@Component`가 없으면 추가

#### 패치 대상

**[MODIFY] `main/java/com/example/lms/extreme/ExtremeZSystemHandler.java`**
- `@Component` 또는 `@Service` 제거
- `@Deprecated(since = "2026-06", forRemoval = true)` 추가
- Javadoc에 canonical 클래스 참조 추가

**[MODIFY] `main/java/com/example/lms/nova/extremez/ExtremeZSystemHandler.java`**
- 동일 처리

**[VERIFY] `main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java`**
- `@Component` 확인. 없으면 추가.

#### 검증

```powershell
gradlew.bat compileJava

# @Component가 canonical에만 있는지 확인
Get-ChildItem -Recurse -Filter "ExtremeZSystemHandler.java" main\java | ForEach-Object {
    $content = Get-Content $_.FullName -Raw
    $hasComponent = $content -match '@Component|@Service'
    Write-Host "$($_.FullName): @Component=$hasComponent"
}
# 기대: burst/ = True, extreme/ = False, nova/extremez/ = False

gradlew.bat bootRun --args="--spring.main.web-application-type=none --spring.profiles.active=local"
# 중복 Bean 에러 없어야 함
```

---

### 2C. S07 TraceStore — 요청 완료 시 NDJSON 영속화 (3.5h ~ 5h)

#### 문제
`TraceStore` = `ThreadLocal<ConcurrentHashMap>` — 요청 끝나면 소멸.
설계: "MLA 브레드크럼이 기록되어 참조 가능"

#### 패치 전략
TraceStore 자체를 변경하지 않는다. 대신 **요청 완료 시점에 핵심 trace를 NDJSON으로 내보내는 Filter**를 추가한다.

#### 패치 대상

**[NEW] `main/java/com/example/lms/trace/TraceSnapshotExporter.java`**

```java
package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Component
public class TraceSnapshotExporter {
    private static final Logger log = LoggerFactory.getLogger(TraceSnapshotExporter.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${trace.snapshot.dir:data/trace-snapshots}")
    private String snapshotDir;

    @Value("${trace.snapshot.enabled:true}")
    private boolean enabled;

    private static final Set<String> EXPORT_KEY_PREFIXES = Set.of(
        "cfvm.", "artplate.", "retrieval.order", "extremeZ.",
        "hypernova.", "twpm.", "cvar.", "overdrive.",
        "moe.", "boosterMode.", "queryTransformer.",
        "llm.modelGuard.", "web.failsoft."
    );

    public void exportCurrentTrace(String requestId, String sessionHash) {
        if (!enabled) return;
        Map<String, Object> allTrace = TraceStore.getAll();
        if (allTrace == null || allTrace.isEmpty()) return;

        Map<String, Object> exported = new LinkedHashMap<>();
        exported.put("_ts", Instant.now().toString());
        exported.put("_rid", requestId == null ? "unknown" : requestId);
        exported.put("_sessionHash", sessionHash == null ? "unknown" : sessionHash);

        for (Map.Entry<String, Object> entry : allTrace.entrySet()) {
            if (EXPORT_KEY_PREFIXES.stream().anyMatch(entry.getKey()::startsWith)) {
                exported.put(entry.getKey(), entry.getValue());
            }
        }
        if (exported.size() <= 3) return;

        try {
            Path dir = Paths.get(snapshotDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("trace_" + LocalDate.now() + ".ndjson");
            String line = mapper.writeValueAsString(exported);
            try (BufferedWriter w = Files.newBufferedWriter(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(line);
                w.newLine();
            }
            TraceStore.put("trace.snapshot.exported", true);
        } catch (IOException e) {
            log.debug("[TRACE] Snapshot export failed: {}", e.getClass().getSimpleName());
        }
    }
}
```

**[NEW] `main/java/com/example/lms/trace/TraceSnapshotFilter.java`**

```java
package com.example.lms.trace;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TraceSnapshotFilter implements Filter {
    private final ObjectProvider<TraceSnapshotExporter> exporterProvider;

    public TraceSnapshotFilter(ObjectProvider<TraceSnapshotExporter> exporterProvider) {
        this.exporterProvider = exporterProvider;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            TraceSnapshotExporter exporter = exporterProvider.getIfAvailable();
            if (exporter != null && request instanceof HttpServletRequest httpReq) {
                String rid = httpReq.getHeader("X-Request-Id");
                String sid = httpReq.getHeader("X-Session-Id");
                try { exporter.exportCurrentTrace(rid, sid); }
                catch (Exception ignore) { /* fail-soft */ }
            }
        }
    }
}
```

#### 검증

```powershell
gradlew.bat compileJava
gradlew.bat test --tests "*TraceSnapshot*"
gradlew.bat bootRun --args="--spring.profiles.active=local"
# 다른 터미널:
curl -H "X-Request-Id: test-001" -H "X-Session-Id: sess-001" http://localhost:8080/
# 확인:
type data\trace-snapshots\trace_2026-06-14.ndjson
```

---

## Phase 3: MEDIUM 갭 패치 (5h ~ 7.5h)

### 3A. S03 MoE — ArtPlateEvolver 진화 이력 영속화 (5h ~ 6h)

**[NEW] `main/java/com/example/lms/artplate/ArtPlateEvolutionLog.java`** — @Entity

**[NEW] `main/java/com/example/lms/artplate/ArtPlateEvolutionLogRepository.java`** — JpaRepository

**[MODIFY] `main/java/com/example/lms/artplate/ArtPlateEvolver.java`**
- `@Autowired(required = false) ArtPlateEvolutionLogRepository` 필드 추가
- `abTest()` 결과를 영속화하는 `persistEvolution()` 메서드 추가
- fail-soft: repo가 null이면 skip

### 3B. S06 HYPERNOVA — 스코어 TraceStore 키 추가 (6h ~ 6.5h)

**[MODIFY] `main/java/com/example/lms/service/rag/fusion/TailWeightedPowerMeanFuser.java`**
- `fuse()` 메서드에 `hypernova.twpm.*` TraceStore 키 추가
- TraceSnapshotExporter가 자동 수집

### 3C. S01 Overdrive — 압축 메트릭 TraceStore 키 추가 (6.5h ~ 7.5h)

**[MODIFY] `main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java`**
- `overdrive.*` TraceStore 키 추가

**[MODIFY] `main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java`**
- `overdrive.compress.*` TraceStore 키 추가

---

## Phase 4: 검증 + 리포트 (7.5h ~ 9h)

```powershell
# After 정량 측정
python scripts/db_gap_scanner.py --root main/java --output data/db-gap-report/after

# Before/After 비교
python -c "
import json
b = json.load(open('data/db-gap-report/before/gap_matrix.json'))
a = json.load(open('data/db-gap-report/after/gap_matrix.json'))
print('Before:', json.dumps(b['gap_severity_counts']))
print('After:', json.dumps(a['gap_severity_counts']))
"

# 전체 빌드 + 검증
gradlew.bat clean compileJava
gradlew.bat test
gradlew.bat check
gradlew.bat checkLangchain4jVersionPurity
gradlew.bat checkSourceSetHygiene

# Secret scan
Get-ChildItem -Recurse -Include "*.java","*.yml","*.yaml" main\, scripts\ | `
  Select-String -Pattern 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}' | `
  Measure-Object | Select-Object -Property Count
```

### 기대 결과

| 항목 | Before | After |
|------|--------|-------|
| CRITICAL 갭 | 3 | **0** |
| MEDIUM 갭 | 3 | **0** |
| LOW 갭 | 2 | 2 |
| 신규 엔티티 | 0 | **2** (CfvmSnapshot, ArtPlateEvolutionLog) |
| 신규 리포지토리 | 0 | **2** |
| 신규 서비스 | 0 | **3** (CfvmSnapshotService, TraceSnapshotExporter, TraceSnapshotFilter) |
| ExtremeZ @Component | 3 | **1** (canonical만) |

---

## 비협상 체크리스트

- [ ] `gradlew.bat compileJava` 성공
- [ ] `gradlew.bat test` 성공
- [ ] `gradlew.bat checkLangchain4jVersionPurity` 성공
- [ ] `gradlew.bat checkSourceSetHygiene` 성공
- [ ] Secret scan 0건
- [ ] CRITICAL 갭 = 0
- [ ] `cfvm_snapshot` 테이블 자동 생성
- [ ] ExtremeZ canonical @Component 1개만
- [ ] TraceStore ThreadLocal 구조 미변경
- [ ] PromptBuilder 우회 없음
- [ ] 기존 테스트 미파괴
