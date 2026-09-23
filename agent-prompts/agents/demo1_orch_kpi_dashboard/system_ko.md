# demo1 오케스트레이션 정량 KPI 대시보드

이 프롬프트는 **패치 전후 정량 지표를 수집·비교**하는 도구다.
"이 패치가 실제로 개선됐나?"를 숫자로 답한다.
소스를 수정하지 않는다.

---

## 0. KPI 수집 트리거

아래 세 시점에 실행한다.

```
Baseline: 패치 적용 직전
After:    Desktop git apply + bootJar 완료 직후
Delta:    After - Baseline (양수 = 개선, 음수 = 퇴보)
```

---

## 1. 정적 KPI (빌드 시간 수집)

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
$env:AWX_AGENT_HOST = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"

# KPI 1: 전체 Java 파일 수 (소스 규모)
$javaCount = (Get-ChildItem (Join-Path $Root "main\java") -Recurse -Filter "*.java").Count
Write-Host "[kpi] javaFileCount=$javaCount"

# KPI 2: AOP Aspect 수
$aspectCount = (Get-ChildItem (Join-Path $Root "main\java\ai\abandonware\nova\orch\aop") -Filter "*.java").Count
Write-Host "[kpi] aopAspectCount=$aspectCount"

# KPI 3: TraceStore.put 호출 수 (관측성 밀도)
$tracePutCount = (Select-String -Path (Join-Path $Root "main\java\**\*.java") -Recurse -Pattern "TraceStore\.put\(" -ErrorAction SilentlyContinue).Count
Write-Host "[kpi] traceStorePutCount=$tracePutCount"

# KPI 4: catch 블록에서 TraceStore 없는 것 (silent failure 위험)
$catchWithoutTrace = (Select-String -Path (Join-Path $Root "main\java\**\*.java") -Recurse -Pattern "catch.*Exception" -ErrorAction SilentlyContinue |
    Where-Object { $_.Line -notmatch "TraceStore|disabledReason|failureClass" }).Count
Write-Host "[kpi] catchWithoutTraceStore=$catchWithoutTrace"

# KPI 5: FQCN 중복 수 (simple name 충돌)
$duplicates = Get-ChildItem (Join-Path $Root "main\java") -Recurse -Filter "*.java" |
    Group-Object Name |
    Where-Object { $_.Count -gt 1 }
Write-Host "[kpi] simpleNameDuplicates=$($duplicates.Count)"

# KPI 6: 컴파일 시간 (ms)
$compileStart = Get-Date
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $ProjectCache 2>&1 | Out-Null
$compileMs = (Get-Date) - $compileStart
Write-Host "[kpi] compileTimeMs=$([int]$compileMs.TotalMilliseconds)"

# KPI 7: 테스트 통과율
$testStart = Get-Date
$testOut = .\gradlew.bat test --no-daemon --project-cache-dir $ProjectCache 2>&1
$testMs = (Get-Date) - $testStart
$passMatch = $testOut | Select-String "(\d+) tests completed, (\d+) failed"
Write-Host "[kpi] testTimeMs=$([int]$testMs.TotalMilliseconds)"
Write-Host "[kpi] testResult=$($passMatch -join ',')"

# KPI 8: secret 패턴 (항상 0이어야)
$secretHits = (Select-String -Path (Join-Path $Root "main\java\**\*.java") -Recurse `
    -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" `
    -ErrorAction SilentlyContinue).Count
Write-Host "[kpi][security] secretPatternInSource=$secretHits"
```

---

## 2. 동적 KPI (bootRun 후 probe 수집)

```powershell
# probe endpoint가 활성이고 AWX_PROBE_TOKEN 있을 때만
$token = $env:AWX_PROBE_TOKEN
if (-not $token) {
    Write-Warning "[kpi] AWX_PROBE_TOKEN not set. Dynamic KPI skipped."
} else {
    # KPI 9: starvation 연속 횟수
    $soakResp = Invoke-RestMethod `
        -Uri "http://localhost:8080/api/probe/soak/kpi" `
        -Headers @{ "X-Probe-Token" = $token } `
        -ErrorAction SilentlyContinue
    if ($soakResp) {
        Write-Host "[kpi] outCount=$($soakResp.outCount)"
        Write-Host "[kpi] starvationFallback.trigger=$($soakResp.'starvationFallback.trigger')"
        Write-Host "[kpi] rescueMerge.used=$($soakResp.'rescueMerge.used')"
        Write-Host "[kpi] cacheOnly.merged.count=$($soakResp.'cacheOnly.merged.count')"
    }

    # KPI 10: KG axis 상태
    $orchResp = Invoke-RestMethod `
        -Uri "http://localhost:8080/api/probe/orch?q=_kpi_" `
        -Headers @{ "X-Probe-Token" = $token } `
        -ErrorAction SilentlyContinue
    if ($orchResp -and $orchResp.kgAxis) {
        Write-Host "[kpi] kgAxis.status=$($orchResp.kgAxis.status)"
        Write-Host "[kpi] kgAxis.neo4jStatus=$($orchResp.kgAxis.neo4jStatus)"
        Write-Host "[kpi] kgAxis.signals=$($orchResp.kgAxis.signals -join ',')"
    }
}
```

---

## 3. 패치 전후 Delta 보고

```powershell
# 이전 KPI를 JSON으로 저장
function Save-KpiSnapshot {
    param([string]$Phase, [hashtable]$Data)
    $outPath = Join-Path "C:\AbandonWare\demo-1\demo-1\src\__reports__" "kpi-$Phase-$(Get-Date -Format yyyyMMdd-HHmmss).json"
    $Data | ConvertTo-Json | Set-Content $outPath -Encoding UTF8
    Write-Host "[kpi] saved=$outPath"
}

# 사용 예:
# $before = @{ javaFileCount=2198; traceStorePutCount=431; catchWithoutTraceStore=22; ... }
# Save-KpiSnapshot -Phase "before" -Data $before
# (패치 적용)
# $after  = @{ javaFileCount=2199; traceStorePutCount=435; catchWithoutTraceStore=20; ... }
# Save-KpiSnapshot -Phase "after" -Data $after
```

Delta 표:

| KPI | Before | After | Delta | 판정 |
|-----|--------|-------|-------|------|
| `traceStorePutCount` | - | - | +N | ✅ 관측성 개선 |
| `catchWithoutTraceStore` | - | - | -N | ✅ silent failure 감소 |
| `simpleNameDuplicates` | 155 | - | - | 변경 없어야 정상 |
| `secretPatternInSource` | 0 | 0 | 0 | 반드시 0 |
| `compileTimeMs` | - | - | - | ±10% 이내 정상 |

---

## 4. 임계 기준

| KPI | 통과 기준 | 실패 시 |
|-----|----------|---------|
| `secretPatternInSource` | 반드시 0 | 즉시 중단, secret-leak-risk 분류 |
| `simpleNameDuplicates` | 패치 전과 동일 | wrong-sourceset 확인 |
| `testResult` | failed=0 | test-failure 분류 |
| `compileTimeMs` | 기존 대비 +50% 이하 | 비용 검토 |
| `traceStorePutCount` | 패치 후 ≥ 패치 전 | 관측성 퇴보 경고 |

---

## 5. KPI 보고 형식

```markdown
## KPI Delta Report

**패치**: <topic-slug>
**측정 시각**: <ISO8601>

### 정적 KPI
| 지표 | Before | After | Delta |
|------|--------|-------|-------|
| javaFileCount | | | |
| aopAspectCount | | | |
| traceStorePutCount | | | |
| catchWithoutTraceStore | | | |
| simpleNameDuplicates | | | |
| secretPatternInSource | 0 | 0 | 0 ✅ |

### 동적 KPI (probe endpoint)
- outCount: <N>
- starvationFallback.trigger: <value>
- kgAxis.status: <value>
- kgAxis.signals: <list>

### 판정
- 전체: PASS / FAIL
- 실패 기준: <없으면 빈칸>
- 다음 측정 트리거: <다음 패치명>
```
