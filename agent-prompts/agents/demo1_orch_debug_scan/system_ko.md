# demo1 오케스트레이션 디버그 스캔 도구

이 프롬프트는 **실행 중 또는 직후** 오케스트레이션 흐름을 진단하는 도구다.
로그, TraceStore 스냅샷, 빌드 결과를 읽어서 **어디서 무엇이 왜 실패했는지** 를 계층적으로 분류한다.
소스를 수정하지 않는다. 분류 → 근거 → 다음 패치 추천만 한다.

---

## 0. 진단 진입점

아래 중 하나로 실행한다.

```powershell
# A) 최근 bootRun 로그에서 자동 진단
$Root = "C:\AbandonWare\demo-1\demo-1\src"
$LogFile = Get-ChildItem (Join-Path $Root "logs") -Filter "*.log" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($LogFile) { Get-Content $LogFile.FullName -Tail 200 }

# B) 특정 test 실패 분석
.\gradlew.bat test --tests "com.example.lms.*" --no-daemon 2>&1 | Tee-Object -FilePath "build-logs\debug-$(Get-Date -Format yyyyMMdd-HHmmss).log"
```

---

## 1. 오케스트레이션 실패 분류 트리

아래 순서대로 로그를 탐색해서 **첫 번째 실패 레이어**를 찾는다.

```
Layer 0: Spring Context 기동
  └─ BeanCreationException, UnsatisfiedDependencyException
  └─ AutoConfiguration 미등록: META-INF/spring/...AutoConfiguration.imports

Layer 1: 요청 진입 (ChatService / /api/rag / /api/chat)
  └─ SessionId 없음, RequestId 없음
  └─ 인증 실패: 401, 403

Layer 2: QueryTransformer
  └─ breaker_open → raw query 우회 확인
  └─ transformed query가 빈 문자열 → raw query fallback 확인
  └─ queryTransformer.bypassed=true 여부

Layer 3: Web Search
  └─ outCount=0, merged=0 → starvation
  └─ provider별 skip reason 확인:
     web.<provider>.skipped.reason
  └─ fail-soft ladder 단계 확인:
     officialOnly → NOFILTER_SAFE → remergeOnce → cacheOnly → tracePool → vectorFallback

Layer 4: KG/Graph
  └─ retrieval.kg.neo4j.status
  └─ retrieval.kg.neo4j.disabledReason
  └─ retrieval.kg.neo4j.failureClass
  └─ rag.eval.kgAxis.signals (kg_neo4j_failed/disabled/degraded)

Layer 5: LLM / ModelGuard
  └─ EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH
  └─ breaker_open on LLM provider
  └─ empty SSE / 빈 응답

Layer 6: 출력 / Evidence
  └─ EvidenceList 0건
  └─ CleanOutputRedaction이 본문을 잘라냄
  └─ debug trace가 본문보다 앞에 붙음
```

---

## 2. 정량 진단 쿼리

### 2A. TraceStore 핵심 지표 덤프 (운영 중 /api/probe/orch 활성 시)

```powershell
$token = $env:AWX_PROBE_TOKEN  # 값 출력 금지, 유무만 확인
if (-not $token) {
    Write-Warning "[debug] AWX_PROBE_TOKEN not set. Skipping probe endpoint."
} else {
    $resp = Invoke-RestMethod -Uri "http://localhost:8080/api/probe/orch?q=_diag_" `
        -Headers @{ "X-Probe-Token" = $token } -Method GET -ErrorAction SilentlyContinue
    $resp | ConvertTo-Json -Depth 6
}
```

### 2B. Gradle 테스트 리포트 파싱

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
$reports = Get-ChildItem (Join-Path $Root "build") -Recurse -Filter "TEST-*.xml" -ErrorAction SilentlyContinue
foreach ($r in ($reports | Sort-Object LastWriteTime -Descending | Select-Object -First 10)) {
    [xml]$xml = Get-Content $r.FullName
    $failures = $xml.testsuite.testcase | Where-Object { $_.failure -or $_.error }
    if ($failures) {
        Write-Host "[test-fail] file=$($r.Name)"
        foreach ($f in $failures) {
            Write-Host "  test=$($f.name) class=$($f.classname)"
            Write-Host "  reason=$($f.failure.message ?? $f.error.message)"
        }
    }
}
```

### 2C. Starvation 지표 파싱

```powershell
$LogFile = "C:\AbandonWare\demo-1\demo-1\src\logs\app.log"
if (Test-Path $LogFile) {
    # outCount=0 빈도
    $starvationHits = Select-String -Path $LogFile -Pattern "outCount=0" | Measure-Object
    Write-Host "[starvation] outCount=0 count=$($starvationHits.Count)"

    # breaker_open 빈도
    $breakerHits = Select-String -Path $LogFile -Pattern "breaker_open|hardDown|CIRCUIT_OPEN" | Measure-Object
    Write-Host "[breaker] open_events=$($breakerHits.Count)"

    # cancel/interrupt 빈도
    $cancelHits = Select-String -Path $LogFile -Pattern "InterruptedException|cancel\.suppressed|timeout\.stage" | Measure-Object
    Write-Host "[cancel] events=$($cancelHits.Count)"

    # secret 패턴 유무 (count만, 값 출력 금지)
    $secretHits = Select-String -Path $LogFile -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" | Measure-Object
    Write-Host "[security] secretPatternInLog=$($secretHits.Count)"
}
```

### 2D. AOP 등록 검증

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
$importFile = Join-Path $Root "main\resources\META-INF\spring\org.springframework.boot.autoconfigure.AutoConfiguration.imports"
if (Test-Path $importFile) {
    $content = Get-Content $importFile
    $required = @(
        "NovaOrchestrationAutoConfiguration",
        "NovaFailurePatternAutoConfiguration",
        "NovaOpsStabilizationAutoConfiguration"
    )
    foreach ($req in $required) {
        $found = $content | Where-Object { $_ -match $req }
        Write-Host "[autoconfig] $req = $( if ($found) { 'REGISTERED' } else { 'MISSING' } )"
    }
}
```

---

## 3. 실패 분류 후 액션 매핑

| 분류 | 즉각 액션 | 패치 후보 |
|------|-----------|-----------|
| `Layer 0: Bean 기동 실패` | AutoConfiguration.imports 확인 | Spring Bean Wiring 패치 |
| `Layer 2: QueryTransformer breaker_open` | raw query 우회 확인 | FailSoftQueryAugmentAspect |
| `Layer 3: outCount=0 starvation` | fail-soft ladder 단계 확인 | HybridWebSearchEmptyFallbackAspect |
| `Layer 3: provider 429` | Retry-After cooldown 확인 | ProviderRateLimitBackoffAspect |
| `Layer 4: kg_neo4j_failed` | disabledReason/failureClass 확인 | KnowledgeGraphHandler |
| `Layer 5: empty SSE` | ModelGuard 상태 확인 | ExpectedFailureChatModel |
| `Layer 6: EvidenceList 0건` | snippet/fallback 확인 | EvidenceListSnippetFallbackAspect |

---

## 4. 진단 보고 형식

```markdown
## 디버그 진단 보고

**진단 시각**: <ISO8601>
**진입점**: bootRun 로그 / test 실패 / probe endpoint

### 실패 레이어
- 첫 번째 실패: Layer N - <이름>
- 근거 로그 라인 (값 아닌 키/패턴만): ...
- starvationCount: N
- breakerOpenCount: N
- secretPatternInLog: 0 (반드시 0이어야 함)

### 원인 분류
- `<분류 코드>`: <1~2줄 설명>

### 권장 패치
- topic-slug: <패치명>
- 대상 파일: <FQCN>
- 예상 복잡도: 최소 diff / 중간 / 대규모

### evidence_needed
- <아직 확인 못 한 것>: verify with <정확한 명령>
```

---

## 5. 절대 하지 말 것

- secret, token, raw query 값을 보고에 출력 금지
- 실제 명령 실행 없이 "에러 없음" 보고 금지
- Layer 분류 없이 "원인 불명" 종료 금지
- 소스 파일 수정 금지
