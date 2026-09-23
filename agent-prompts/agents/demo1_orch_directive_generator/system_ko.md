# demo1 맥미니 패치 지시서 생성기

이 프롬프트는 **데스크탑 에이전트**가 `demo1_orch_patch_scanner` 결과를 받아서
맥미니에게 줄 **실행 가능한 패치 지시서**를 생성하는 도구다.
소스를 수정하지 않는다. 지시서 생성만 한다.

---

## 0. 현재 확인된 패치 후보 우선순위 (소스 분석 기반)

아래는 소스를 실제로 읽어서 도출한 우선순위 목록이다.
스캐너 실행 전에도 이 목록으로 시작할 수 있다.

| # | topic-slug | 대상 파일 | 핵심 문제 | 점수 | 우선순위 |
|---|-----------|-----------|-----------|------|---------|
| 1 | `orch-probe-trace-redaction` | `OrchProbeController.java` | raw q + TraceStore.getAll() 노출 | 8 | P0 |
| 2 | `failpattern-starvation-metric` | `FailurePatternOrchestrator.java` | web starvation 패턴 감지 후 정량 지표 없음 | 7 | P0 |
| 3 | `nightmare-breaker-per-provider-split` | `NightmareBreaker.java` | 전역 OPEN이 하위 provider로 전염 — 분리 미완 | 7 | P0 |
| 4 | `llm-router-expected-failure-trace` | `LlmRouterAspect.java` | EXPECTED_FAILURE 시 TraceStore에 reason/model 미기록 | 6 | P1 |
| 5 | `evidence-list-empty-body-guard` | `EvidenceListTraceInjectionAspect.java` | trace가 본문 앞에 붙어 본문이 빈 것처럼 보이는 케이스 | 6 | P1 |
| 6 | `web-soak-kpi-ndjson-fixed-fields` | `WebSoakKpiProbeService.java` | Soak KPI NDJSON에 rid/sessionId/provider 고정 필드 없음 | 5 | P2 |

---

## 1. 지시서 생성 규칙

### 1A. 매 지시서에 포함해야 하는 필수 항목

```
1. Role & Worktree 선언
2. Target Files (FQCN 포함)
3. Problem (정량 근거 — 점수표 포함)
4. Patch Rules (최소 diff, proceed() 1회, TraceStore 키, secret 금지)
5. Test Contract (RED 단언 → GREEN 단언)
6. Mac mini Verification Commands (정확한 Gradle 명령)
7. PatchDrop Bundle 요구사항
8. Failure Class 목록
```

### 1B. 지시서에 절대 포함하면 안 되는 것

- raw API key, client secret, owner token
- raw query 예시 (hash/length로 대체)
- `./gradlew bootRun` (연기 필요한 smoke는 Desktop에서)
- `pages/api/**` (Next.js App Router만 허용)
- 파일 전체 재작성 요청
- simple class name만으로 파일 지정 (FQCN 필수)

---

## 2. 우선순위 P0 지시서 전문

### 지시서 #1: orch-probe-trace-redaction

```markdown
# Mac mini PatchDrop Task: orch-probe-trace-redaction

Role: Mac mini patch producer only.
DO NOT edit C:\AbandonWare\demo-1\demo-1\src through SMB.
Worktree: agent/macmini/orch-probe-trace-redaction
Branch: agent/macmini/orch-probe-trace-redaction

## 정량 근거 (점수 8/10)
- 안전성 위험: 2 — raw q가 /api/probe/orch 응답에 노출, TraceStore.getAll() 전체 반환
- 관측성: 2 — 현재 trace에 queryPresent/queryHash 분리 없음
- fail-soft: 1 — allowlist 없이 전체 덤프는 trace 오염 위험
- 복잡도: 2 — 최소 diff (메서드 하나 추가, 2줄 교체)
- 테스트: 1 — 현재 redaction 전용 테스트 없음

## Target Files
- main/java/com/example/lms/probe/OrchProbeController.java  [MODIFY]
- src/test/java/com/example/lms/probe/OrchProbeControllerTraceRedactionTest.java  [NEW]

## Active sourceSet Confirmation
root: main/java, main/resources
:app: app/src/main/java_clean, app/src/main/resources

## Problem
OrchProbeController의 두 위치:
1. `out.put("query", q)` → raw query 노출
2. `out.put("trace", TraceStore.getAll())` → TraceStore 전체 덤프

ProbeConfig.java에 이미 trace allowlist 패턴이 있다.
TraceEventCanonicalizer 또는 SafeRedactor가 있으면 재사용한다.
없으면 private 헬퍼만 추가한다.

## Patch Rules (최소 diff)
1. `out.put("query", q)` 를 아래로 교체:
   ```java
   out.put("queryPresent", q != null && !q.isBlank());
   out.put("queryLength", q == null ? 0 : q.length());
   out.put("queryHash12", q == null ? "" : sha256Hex(q).substring(0, 12));
   ```
2. `out.put("trace", TraceStore.getAll())` 를 아래로 교체:
   ```java
   out.put("trace", selectedTraceSnapshot());
   ```
3. private `selectedTraceSnapshot()` 헬퍼 추가:
   - trace.size (전체 키 수)
   - allowlist 키만 포함:
     `web.await.`, `web.failsoft.`, `aux.queryTransformer.`, `qtx.`,
     `keywordSelection.fallback.seedSource`, `embed.`, `guard.detour.`, `needle.`
   - 각 값은 `safeDiagString(v)` 또는 동등 redact 처리
4. `proceed()` 변경 없음 (이 파일에 @Around 없음)
5. `AppSecurityConfig`, YAML, secrets, `openssl`, `opnessl` 절대 건드리지 않음

## Test Contract
```java
// RED (수정 전 실패)
assertFalse(response.contains("private raw query"));

// GREEN (수정 후 통과)
assertTrue(response.contains("queryPresent"));
assertTrue(response.contains("queryHash12"));
assertFalse(response.contains("ownerToken"));
assertFalse(response.contains("rawQuery"));
// trace 키 중 allowlist 외는 없음
// trace.size 존재
```

MockMvc 설정:
```java
new OrchProbeController(null, true, "probe-secret")
TraceStore.put("web.await.last.rawQuery", "private raw query");
TraceStore.put("ownerToken", "secret-value");
GET /api/probe/orch?q=private raw q
X-Probe-Token: probe-secret
```

## Mac mini Verification Commands
```bash
export AWX_AGENT_HOST=macmini
export GRADLE_USER_HOME="$HOME/.gradle-awx-macmini"
export PROJECT_CACHE="$HOME/Library/Caches/awx-gradle-project-cache/macmini"

./gradlew checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test \
    --no-daemon --project-cache-dir "$PROJECT_CACHE"
./gradlew test \
    --tests 'com.example.lms.probe.OrchProbeControllerTraceRedactionTest' \
    --no-daemon --project-cache-dir "$PROJECT_CACHE"
git diff --check
```

## PatchDrop Bundle
Submit to: C:\AbandonWare\demo-1\demo-1\src\__patch_drop__\
- orch-probe-trace-redaction.patch
- orch-probe-trace-redaction.report.md
- orch-probe-trace-redaction.verify.log
- orch-probe-trace-redaction.sha256.txt

Failure class if failed:
wrong-sourceset | secret-leak-risk | cannot-find-symbol | test-failure | other
```

---

### 지시서 #2: failpattern-starvation-metric

```markdown
# Mac mini PatchDrop Task: failpattern-starvation-metric

Role: Mac mini patch producer only.
Worktree: agent/macmini/failpattern-starvation-metric

## 정량 근거 (점수 7/10)
- 안전성 위험: 1 — 직접 위험 없음
- 관측성: 2 — outCount=0 starvation이 FailurePatternOrchestrator에 기록 안 됨
- fail-soft: 2 — starvation 감지 후 ladder 진행 여부가 trace에 없음
- 복잡도: 1 — 중간 (starvation event 분기 추가)
- 테스트: 1 — starvation → failpattern 연결 테스트 없음

## Target Files
- main/java/ai/abandonware/nova/orch/failpattern/FailurePatternOrchestrator.java  [MODIFY]
- main/java/ai/abandonware/nova/orch/failpattern/FailurePatternKind.java  [MODIFY]
- src/test/java/ai/abandonware/nova/orch/failpattern/FailurePatternOrchestratorStarvationTest.java  [NEW]

## Problem
web search outCount=0 starvation이 반복돼도 FailurePatternOrchestrator가
WEB_STARVATION 패턴을 감지하지 못한다.
HybridWebSearchEmptyFallbackAspect에서 starvation 시 FailurePatternOrchestrator.record()를
호출하지 않기 때문이다.

## Patch Rules
1. FailurePatternKind에 `WEB_STARVATION` 추가
2. FailurePatternOrchestrator에 `recordWebStarvation(String provider, String ladderStage)` 추가:
   - TraceStore.put("failpattern.web.starvation.provider", provider)
   - TraceStore.put("failpattern.web.starvation.ladderStage", ladderStage)
   - TraceStore.inc("failpattern.web.starvation.count")
3. HybridWebSearchEmptyFallbackAspect에서 starvation 확정 시 호출 추가
   (단, HybridWebSearchEmptyFallbackAspect는 283KB라 수정 최소화 — 호출 1줄만)
4. raw provider name/query 값 로그 금지

## Test Contract
```java
// starvation 3회 → WEB_STARVATION 패턴 감지
// TraceStore에 failpattern.web.starvation.count=3 이상
// failpattern.web.starvation.ladderStage 존재
```

## Mac mini Verification Commands
```bash
./gradlew test \
    --tests 'ai.abandonware.nova.orch.failpattern.FailurePatternOrchestratorStarvationTest' \
    --no-daemon --project-cache-dir "$PROJECT_CACHE"
./gradlew checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test --no-daemon
```

## PatchDrop Bundle
- failpattern-starvation-metric.patch
- failpattern-starvation-metric.report.md
- failpattern-starvation-metric.verify.log
- failpattern-starvation-metric.sha256.txt
```

---

### 지시서 #3: nightmare-breaker-provider-split-trace

```markdown
# Mac mini PatchDrop Task: nightmare-breaker-provider-split-trace

Role: Mac mini patch producer only.
Worktree: agent/macmini/nightmare-breaker-provider-split-trace

## 정량 근거 (점수 7/10)
- 안전성 위험: 2 — 전역 OPEN이 cascade하면 모든 provider가 동시에 차단됨
- 관측성: 2 — 어느 provider가 OPEN인지 TraceStore에 없음
- fail-soft: 2 — 하위 단계로 전염 차단 미완성
- 복잡도: 0 — 1,414줄 파일, 변경 최소화 필수
- 테스트: 1 — provider별 OPEN 격리 테스트 없음

## Target Files
- main/java/com/example/lms/infra/resilience/NightmareBreaker.java  [MODIFY]
- src/test/java/com/example/lms/infra/resilience/NightmareBreakerProviderIsolationTest.java  [NEW]

## Problem
`isAnyOpenPrefix(prefix)` 가 모든 provider prefix를 한 번에 검사한다.
Naver OPEN → Brave도 skip되는 케이스가 있을 수 있다.
또한 OPEN 시 TraceStore에 provider별 reason이 없어 어느 provider가 왜 OPEN인지 알 수 없다.

## Patch Rules
1. `recordOpen(key, kind, ...)` 시 TraceStore에 추가:
   - `nightmare.breaker.<key>.openKind` = kind.name()
   - `nightmare.breaker.<key>.openAt` = epochMs
   - `nightmare.breaker.<key>.openUntil` = untilMs
2. provider prefix가 겹치지 않도록 key 네이밍 가이드 주석 추가
3. `isAnyOpenPrefix(prefix)`에 provider별 OPEN 상태 로그 추가
4. 전역 OPEN key가 하위 prefix를 커버하지 않도록 prefix 정규화 메서드 추가
5. 기존 State/Policy 구조 변경 금지

## Test Contract
```java
// Naver key OPEN → Brave key는 CLOSED 유지
// TraceStore에 nightmare.breaker.naver.openKind 존재
// TraceStore에 nightmare.breaker.brave.openKind 없음
```

## Mac mini Verification Commands
```bash
./gradlew test \
    --tests 'com.example.lms.infra.resilience.NightmareBreakerProviderIsolationTest' \
    --no-daemon --project-cache-dir "$PROJECT_CACHE"
./gradlew checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test --no-daemon
```

## PatchDrop Bundle
- nightmare-breaker-provider-split-trace.patch
- nightmare-breaker-provider-split-trace.report.md
- nightmare-breaker-provider-split-trace.verify.log
- nightmare-breaker-provider-split-trace.sha256.txt
```

---

## 3. 지시서 발행 후 추적

지시서를 발행한 뒤 아래 파일에 기록한다.

```json
// __reports__/orch-directive-log.json
{
  "issued": [
    {
      "topic": "orch-probe-trace-redaction",
      "issuedAt": "<ISO8601>",
      "priority": "P0",
      "status": "pending_macmini"
    }
  ]
}
```

상태 전이: `pending_macmini` → `patchdrop_submitted` → `desktop_applied` → `done`
