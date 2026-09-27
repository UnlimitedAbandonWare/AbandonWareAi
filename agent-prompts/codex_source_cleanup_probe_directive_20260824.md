# demo-1 소스 정리 탐침 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Repository rules keep production edits, integration, verification, and final judgment in the parent Codex lane.

**Goal:** 현재 GREEN과 사용자 dirty-tree 변경을 보존하면서 `ChatWorkflow`의 요청 추적 봉투 하나를 직접 테스트 가능한 package-private 소유 경계로 분리한다.

**Architecture:** `TraceStore.clear()` 이후의 최소 trace seeding block만 stateless non-Spring helper로 이동한다. Timeline, creative/presentation restore, chat/RAG orchestration, provider, prompt, memory 경계는 기존 owner에 남긴다. Web fail-soft KPI와 중복 retrieval chain은 별도 판정으로 격리한다.

**Tech Stack:** Java 17, Spring Boot의 기존 저장소 버전, Gradle Wrapper 8.7, JUnit 5, Mockito/Spring test utilities의 기존 의존성, PowerShell.

**Spec:** 사용자 승인 탐침 설계를 이 파일 `C:\AbandonWare\demo-1\demo-1\src\agent-prompts\codex_source_cleanup_probe_directive_20260824.md`의 Sections 1-3에 고정했다. Governing repository contract: `C:\AbandonWare\demo-1\demo-1\src\AGENTS.md`.

## Global Constraints

- root active owner는 `main/java`, `main/resources`; root test owner는 `src/test/java`다.
- `:app` active owner는 `app/src/main/java_clean`, `app/src/main/resources`다.
- Java 17과 모든 `dev.langchain4j` dependency `1.0.1`을 유지한다.
- current working-tree preimage와 사용자 hunks를 보존한다. HEAD 복원, broad formatting, encoding normalization은 금지한다.
- source mutation 직전 `demo1-source-edit-three-way-preflight`의 정확한 세 query와 안정된 `APPLY`가 필요하다.
- 한 implementation session은 `CLEANUP-A` 하나만 실행한다. `CLEANUP-B`와 삭제 후보는 별도 세션이다.
- 신규 production dependency, DB/provider/credential mutation, staging, commit, push, deploy는 이 plan의 권한 밖이다.

문서 상태: `DESIGN_APPROVED / DOCUMENT_ONLY / LIVE_RECONCILED`

- Directive ID: `AWX-SOURCE-CLEANUP-PROBE-20260824`
- 기준 날짜: 2026-08-24 (Asia/Seoul)
- canonical workspace: `C:\AbandonWare\demo-1\demo-1\src`
- 목적: 현재 활성 sourceSet에서 책임 집중, 테스트 결합, 중복 소유권 의심이 큰 지점을 찾고, 동작을 바꾸지 않는 가장 작은 정리 패치만 실행 가능 상태로 만든다.
- 권한 경계: 이 문서는 미래 소스 패치의 실행 지시서다. 문서 작성 세션에서는 `main/java`, `main/resources`, `app` sourceSet, 테스트, Gradle 설정, DB, 외부 provider, localhost runtime을 수정하지 않았다.

이 지시서의 성공은 “거대한 파일을 많이 쪼개는 것”이 아니다. 현재 동작과 사용자 변경분을 보존하면서 테스트 가능한 소유 경계 하나를 만드는 것이 성공이다.

---

## 1. 최종 판정

| 우선순위 | 후보 | 판정 | 이번 실행 지시 |
|---:|---|---|---|
| 1 | `ChatWorkflow.continueChat`의 요청 추적 봉투 | `PATCH_READY_ON_FROZEN_CURRENT_PREIMAGE` | 요청 추적 봉투만 package-private 협력자로 추출한다. 다른 chat/RAG 경로는 건드리지 않는다. |
| 2 | `WebFailSoftSearchAspect.emitSoakKpiJson` | `CHARACTERIZE_THEN_PATCH_SEPARATELY` | 부작용 없는 입력 스냅샷과 projection 경계를 RED로 증명할 수 있을 때만 별도 세션에서 추출한다. |
| 3 | `com.example.lms...DynamicRetrievalHandlerChain` | `HOLD_SPRING_RUNTIME / HOLD_REFERENCED` | 활성 root sourceSet의 canonical 구현이며 wiring과 테스트 참조가 있으므로 삭제하지 않는다. |
| 4 | `com.abandonware.ai...DynamicRetrievalHandlerChain` | `HOLD_REFERENCED` | `SearchProbeController` 직접 소비자가 있으므로 삭제하지 않는다. |
| 5 | `service.rag.handler.DynamicRetrievalHandlerChain` | `DELETE_CANDIDATE_BUT_HOLD_DIRTY` | 현재 scan 범위 밖으로 보이지만 파일 자체가 수정 중이다. 별도 승인, 참조·classpath·bean proof 전 삭제하지 않는다. |
| 6 | `HybridWebSearchProvider`, `NaverSearchService` | `DEFER_NO_NARROW_BROKEN_SEAM` | 크기와 broad catch 수만으로 수정하지 않는다. provider 의미 결함 RED가 생길 때만 해당 owner에서 처리한다. |
| 7 | AOP ordering 전반 | `ALREADY_SATISFIED` | fresh scanner에서 명시적 order coverage가 1.0이었다. `@Order`를 재배치하지 않는다. |

기본 실행 단위는 우선순위 1 하나뿐이다. 우선순위 2는 같은 patch, 같은 source-edit lease, 같은 커밋 후보에 묶지 않는다.

---

## 2. 신뢰 경계와 EvidenceSnapshot

### 2.1 라이브 체크아웃 기준점

- branch: `codex/owned-runtime-browser-restart`
- HEAD: `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- Java: `17.0.13`
- root main sourceSet: `main/java`, `main/resources`
- root test sourceSet: `src/test/java`
- `:app` main sourceSet: `app/src/main/java_clean`, `app/src/main/resources`
- Git index lock: 없음
- top-level active PatchDrop `.patch`: 없음
- port 8080 listener: 없음
- port 8081 listener: 없음

현재 tree는 매우 dirty하다. dirty tree 전체를 정리하거나 HEAD 기준으로 복원하지 않는다. 아래 SHA-256은 HEAD의 해시가 아니라, 이 문서를 만들 때 실제로 읽은 현재 working-tree preimage다.

### 2.2 대상 preimage

| 파일 | Git 상태 | 줄 수 | 현재 SHA-256 |
|---|---:|---:|---|
| `main/java/com/example/lms/service/ChatWorkflow.java` | modified | 13,183 | `8FA3163273A2213EDB48A7214DD712B30DE897F581D286FD47051FFDDA15F573` |
| `main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java` | modified | 5,888 | `CE83D3FDC6A27F97323A25DD5731A5B380E3289FAB69DCAF620AF6D52403B8F6` |
| `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java` | modified | 4,078 | `2EC493811C0C4BEE6B16CE48468956D37ED7B1445A802E92B16F3356E69C3138` |
| `main/java/com/example/lms/service/NaverSearchService.java` | modified | 4,705 | `79F208C0CD473730648822ABBD27243EA99CB9B6170BE6D53AA044F7400EDCCB` |
| `main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java` | untracked | 2,083 | `37D4F5D8BB58DE7EC8262615558130558CBB70E85B085EEC0BA5449A2DB1C5F5` |
| `main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java` | tracked clean | 206 | `1BF601107684107C65366562D8DA824CE26FBE6492B3414C207EC0652D0E3F6E` |
| `main/java/service/rag/handler/DynamicRetrievalHandlerChain.java` | modified | 76 | `90252D32DBD4AB7A752C7D7194E49BCF15D33D80705DBAC91C33BEF14D2B7835` |
| `app/src/main/java_clean/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java` | tracked clean | 65 | `BD2B8B61839DD719BB3EAB74CFD77A4CB8DF8D9BBACB15639EDA5B4AAE124894` |

HEAD 대비 현재 numstat도 preimage 경고로 유지한다.

| 파일 | HEAD 대비 추가 | HEAD 대비 삭제 |
|---|---:|---:|
| `ChatWorkflow.java` | 7,420 | 511 |
| `WebFailSoftSearchAspect.java` | 266 | 4 |
| `HybridWebSearchProvider.java` | 224 | 115 |
| `NaverSearchService.java` | 428 | 139 |

위 값 중 하나라도 바뀌면 기존 `APPLY`를 재사용하지 않는다. 새 현재 파일과 실제 hunk를 다시 읽고 해당 lane만 `evidence_needed`로 되돌린다.

### 2.3 fresh 구조 탐침

fresh read-only scan은 저장소 report를 덮어쓰지 않고 사용자 temp 아래에서 실행했다.

- active Java files: 2,090
- cross-subsystem files: 212
- cross-subsystem files over 1,000 lines: 47
- runtime cross-subsystem files over 1,000 lines: 41
- catch blocks: 4,052
- broad catch blocks: 3,276
- broad catch without local breadcrumb, heuristic approximation: 13
- aspect files: 66
- explicit aspect order approximation: 66
- aspect order coverage approximation: 1.0
- secret-pattern hits reported by that scan: 0
- test Java files: 1,009
- test missing-import count: 0
- test-tree risk score: 0.0

핫스폿 수치는 우선순위 신호일 뿐 결함 수가 아니다.

| 파일 | 줄 수 | method 근사 | broad catch 근사 | 해석 |
|---|---:|---:|---:|---|
| `ChatWorkflow.java` | 13,183 | 310 | 176 | 요청 trace, chat 흐름, fallback, 모델, retrieval 책임이 한 파일에 집중됨 |
| `WebFailSoftSearchAspect.java` | 5,888 | 60 | 151 | AOP 경계, stage, backoff, KPI projection과 emission이 집중됨 |
| `NaverSearchService.java` | 4,705 | 146 | 75 | 크지만 현재 provider 의미 결함 RED 없음 |
| `ChatApiController.java` | 4,300 | 93 | 98 | UI/controller lane이므로 이번 정리 범위에서 제외 |
| `HybridWebSearchProvider.java` | 4,078 | 68 | 118 | 크지만 provider 호출 의미를 건드릴 좁은 RED 없음 |
| `UnifiedRagOrchestrator.java` | 3,878 | 134 | 21 | cross-subsystem owner라 단순 분해 금지 |
| canonical `DynamicRetrievalHandlerChain.java` | 2,083 | 70 | 45 | 활성 wiring과 직접 참조가 있는 owner |

`catch (Exception|Throwable)` 개수만으로 catch를 좁히거나 로그를 추가하지 않는다. 예를 들어 runtime-health proof logging의 fail-soft catch는 provider 결과를 바꾸지 않는 것이 계약이므로 정리 대상으로 승격하지 않았다.

### 2.4 fresh 빌드와 테스트 기준선

격리된 Gradle user home, project cache, split build output을 사용했다.

~~~text
checkLangchain4jVersionPurity: PASS
checkSourceSetHygiene: PASS
compileJava: PASS
BUILD SUCCESSFUL in 1m 37s
~~~

`checkSourceSetHygiene`의 `inactive-present: app/src/main/java`는 저장소가 이미 선언한 inactive 경고이며 실패가 아니다. `:app:compileJava`와 root `compileJava`가 모두 통과했다.

집중 기준선:

~~~text
com.example.lms.service.ChatWorkflowTraceRedactionContractTest: PASS
ai.abandonware.nova.orch.aop.WebFailSoftSearchAspectTest: PASS
ai.abandonware.nova.orch.aop.WebAwaitEventsSummaryTest: PASS
BUILD SUCCESSFUL in 57s
~~~

따라서 아래 작업은 실패 수리가 아니라 동작 보존형 구조 정리다. GREEN 기준선을 바꾸거나 기존 assertion을 삭제해 리팩터링을 통과시키지 않는다.

---

## 3. 모든 미래 소스 패치의 공통 게이트

### 3.1 한 세션, 한 primary lane

- 기본 세션은 `CLEANUP-A-CHAT-TRACE-ENVELOPE`만 실행한다.
- `CLEANUP-B-WEB-SOAK-PROJECTION`은 A와 별도 세션, 별도 preimage, 별도 RED/GREEN, 별도 최종 판정으로 처리한다.
- 중복 chain 삭제는 어느 추출 패치에도 묶지 않는다.
- Browser, Computer, provider, DB 증거가 없다는 이유로 독립적인 unit/compile lane을 중단하지 않는다.

### 3.2 read-only preflight

~~~powershell
$repoRoot = 'C:\AbandonWare\demo-1\demo-1\src'
Set-Location -LiteralPath $repoRoot

java -version
git branch --show-current
git rev-parse HEAD
git worktree list
git status --short -- `
  main/java/com/example/lms/service/ChatWorkflow.java `
  main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java `
  src/test/java/com/example/lms/service/ChatWorkflowTraceRedactionContractTest.java `
  src/test/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspectTest.java
Test-Path -LiteralPath '.git\index.lock'
.\__patch_drop__\janitor_inventory.ps1
~~~

선택한 lane의 대상만 다시 hash한다.

~~~powershell
Get-FileHash -Algorithm SHA256 -LiteralPath `
  'main/java/com/example/lms/service/ChatWorkflow.java', `
  'src/test/java/com/example/lms/service/ChatWorkflowTraceRedactionContractTest.java'
~~~

필수 판정:

- Java major가 17이 아니면 해당 lane `HOLD`.
- `.git\index.lock`이 있으면 해당 source-edit lane `HOLD`.
- 같은 target/hunk를 소유한 lease나 writer가 있으면 해당 lane `HOLD`.
- top-level PatchDrop patch가 있으면 Desktop source-owner 규칙에 따라 해당 lane `HOLD`.
- 문서의 target SHA-256과 현재 hash가 다르면 자동 patch 금지. 새 preimage와 diff를 읽고 지시를 재결합한다.
- dirty tree 전체는 repository-wide HOLD 사유가 아니다. 선언한 파일과 hunk만 판단한다.
- `git reset`, `git checkout --`, broad formatter, line-ending normalization으로 현재 사용자 변경을 지우지 않는다.

### 3.3 application source edit gate

실제 `main/java`를 바꾸기 직전에 repo-local `demo1-source-edit-three-way-preflight`를 사용한다.

1. 하나의 redacted `EvidenceSnapshot`을 고정한다.
2. 정확히 `POSITIVE_QUERY`, `NEGATIVE_QUERY`, `NEUTRAL_QUERY`를 독립 실행한다.
3. `POSITIVE_QUERY`는 현재 trace key, ordering, MDC/TraceStore lifecycle, fail-soft 의미가 추출 뒤에도 보존되는지 입증한다.
4. `NEGATIVE_QUERY`는 새 helper가 새 Spring owner, raw identifier leak, ThreadLocal lifecycle 이동, prompt/provider 의미 변경, user-hunk 손실을 만들 수 있는 반례를 찾는다.
5. `NEUTRAL_QUERY`는 두 packet과 현재 명령 증거만 보고 `APPLY | HOLD | REJECT`를 반환한다. 새 증거를 수집하거나 구현하지 않는다.
6. packet 순서를 A-B와 B-A로 바꿔도 `APPLY`가 안정적일 때만 source owner guard로 진입한다.

이 문서의 `DESIGN_APPROVED`는 위 source-edit `APPLY`를 대신하지 않는다.

### 3.4 Superpowers task checklist

아래 checklist는 `CLEANUP-A`의 유일한 실행 순서다. 각 task는 독립적인 review gate를 가지며, 체크되지 않은 step을 건너뛰지 않는다. 이 plan은 commit 권한을 부여하지 않으므로 commit step 대신 verified diff에서 멈춘다.

### Task 1: 현재 preimage와 source-edit 권한 고정

**Files:**

- Read: `C:\AbandonWare\demo-1\demo-1\src\AGENTS.md`
- Read: `main/java/com/example/lms/service/ChatWorkflow.java`
- Read: `src/test/java/com/example/lms/service/ChatWorkflowTraceRedactionContractTest.java`
- Modify: none

**Interfaces:**

- Consumes: 이 문서 Section 2의 branch, HEAD, sourceSet, SHA-256, current target status.
- Produces: redacted `EvidenceSnapshot`, stable three-query `APPLY`, exact target preimage immediately before source mutation.

- [ ] **Step 1: read-only repository preflight를 실행한다.**

~~~powershell
$repoRoot = 'C:\AbandonWare\demo-1\demo-1\src'
Set-Location -LiteralPath $repoRoot
java -version
git branch --show-current
git rev-parse HEAD
git worktree list
git status --short -- `
  main/java/com/example/lms/service/ChatWorkflow.java `
  src/test/java/com/example/lms/service/ChatWorkflowTraceRedactionContractTest.java
Test-Path -LiteralPath '.git\index.lock'
.\__patch_drop__\janitor_inventory.ps1
~~~

Expected: Java major 17, exact target가 root active sourceSet에 존재, index lock 없음, 겹치는 owner/lease/top-level PatchDrop patch 없음. 다른 unrelated dirty 파일은 그대로 둔다.

- [ ] **Step 2: target current preimage를 문서 snapshot과 비교한다.**

~~~powershell
$chatWorkflowHash = (Get-FileHash -LiteralPath `
  'main/java/com/example/lms/service/ChatWorkflow.java' -Algorithm SHA256).Hash
$chatWorkflowHash
$chatWorkflowHash -eq '8FA3163273A2213EDB48A7214DD712B30DE897F581D286FD47051FFDDA15F573'
~~~

Expected: 마지막 boolean이 `True`. `False`면 자동 수정하지 않고 current hunk를 다시 읽어 `HOLD_PREIMAGE_CHANGED` 또는 새 directive를 만든다.

- [ ] **Step 3: 정확한 three-query gate를 실행한다.**

`demo1-source-edit-three-way-preflight`로 하나의 redacted snapshot을 고정하고 `POSITIVE_QUERY`, `NEGATIVE_QUERY`, `NEUTRAL_QUERY`를 정확히 한 번씩 실행한다. NEUTRAL 결과가 A-B와 B-A에서 모두 `APPLY`인지 기록한다. `HOLD` 또는 `REJECT`면 Task 2 이후 production source로 진입하지 않는다.

### Task 2: helper 계약을 RED로 고정

**Files:**

- Create: `src/test/java/com/example/lms/service/ChatWorkflowRequestTraceEnvelopeTest.java`
- Modify: `src/test/java/com/example/lms/service/ChatWorkflowTraceRedactionContractTest.java`
- Production modify: none

**Interfaces:**

- Consumes: `TraceStore`, `SafeRedactor`, `ChatRequestDto`, SLF4J `MDC`의 현재 동작.
- Produces: exact package-private signatures `ChatWorkflowRequestTraceEnvelope.seed(ChatRequestDto, String)`과 `rehydrateRequestCorrelation(String)`에 대한 RED contract.

- [ ] **Step 1: 신규 helper direct test의 첫 RED를 작성한다.**

~~~java
package com.example.lms.service;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatWorkflowRequestTraceEnvelopeTest {

    @AfterEach
    void clearTraceState() {
        org.slf4j.MDC.clear();
        TraceStore.clear();
    }

    @Test
    void rehydratesCorrelationAliasesAsHashOnly() {
        String raw = "raw-workflow-request-correlation";
        ChatWorkflowRequestTraceEnvelope.rehydrateRequestCorrelation(raw);

        String expected = SafeRedactor.hashValue(raw);
        for (String key : List.of("trace.id", "x-request-id", "requestId", "traceId")) {
            assertEquals(expected, TraceStore.get(key));
        }
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));

        TraceStore.clear();
        ChatWorkflowRequestTraceEnvelope.rehydrateRequestCorrelation("   ");
        for (String key : List.of("trace.id", "x-request-id", "requestId", "traceId")) {
            assertNull(TraceStore.get(key));
        }
    }
}
~~~

- [ ] **Step 2: test를 실행해 정확한 RED 이유를 확인한다.**

~~~powershell
.\gradlew.bat test `
  --tests com.example.lms.service.ChatWorkflowRequestTraceEnvelopeTest `
  --no-daemon --project-cache-dir $cleanupProjectCache
~~~

Expected: `ChatWorkflowRequestTraceEnvelope` symbol이 아직 없어서 compile/test RED. unrelated classpath 또는 기존 test 실패면 production 구현 전에 분리한다.

- [ ] **Step 3: 나머지 seed 계약 test를 같은 class에 추가한다.**

정확한 test 이름과 입력은 다음으로 고정한다.

~~~java
@Test
void seedsRequestAndSessionBreadcrumbsWithoutRawTraceStoreIdentifiers() {
    com.example.lms.dto.ChatRequestDto req = new com.example.lms.dto.ChatRequestDto();
    req.setSessionId(42L);
    org.slf4j.MDC.put("sid", "raw-browser-request-sid");
    org.slf4j.MDC.put("traceId", "raw-request-trace-id");

    ChatWorkflowRequestTraceEnvelope.seed(req, "chat-42");

    assertEquals(SafeRedactor.hashValue("raw-browser-request-sid"), TraceStore.get("req.sid"));
    assertEquals(SafeRedactor.hashValue("42"), TraceStore.get("chatSessionHash"));
    assertEquals(SafeRedactor.hashValue("chat-42"), TraceStore.get("sid"));
    String trace = String.valueOf(TraceStore.getAll());
    assertFalse(trace.contains("raw-browser-request-sid"));
    assertFalse(trace.contains("raw-request-trace-id"));
}

@Test
void preservesExistingMdcContract() {
    com.example.lms.dto.ChatRequestDto req = new com.example.lms.dto.ChatRequestDto();
    req.setSessionId(42L);
    org.slf4j.MDC.put("sid", "raw-browser-request-sid");

    ChatWorkflowRequestTraceEnvelope.seed(req, "chat-42");

    assertEquals("raw-browser-request-sid", org.slf4j.MDC.get("requestSid"));
    assertEquals("42", org.slf4j.MDC.get("chatSessionId"));
    assertEquals("chat-42", org.slf4j.MDC.get("sid"));
    assertEquals("chat-42", org.slf4j.MDC.get("sessionId"));
}

@Test
void nullRequestLeavesSuppressionBreadcrumbsAndReturns() {
    ChatWorkflowRequestTraceEnvelope.seed(null, "chat-42");
    assertEquals(Boolean.TRUE, TraceStore.get("chat.workflow.suppressed.traceSeed.chatSessionId"));
    assertEquals(Boolean.TRUE, TraceStore.get("chat.workflow.suppressed.traceSeed.breadcrumb"));
}
~~~

### Task 3: 최소 helper를 구현하고 owner 호출을 한 줄로 전환

**Files:**

- Create: `main/java/com/example/lms/service/ChatWorkflowRequestTraceEnvelope.java`
- Modify: `main/java/com/example/lms/service/ChatWorkflow.java`
- Test: Task 2의 두 test 파일

**Interfaces:**

- Consumes: `ChatRequestDto req`, normalized `String sessionKey`, current MDC values.
- Produces: hash-only TraceStore correlation/session keys와 현재 MDC/breadcrumb side effects. 반환값과 Spring bean은 만들지 않는다.

- [ ] **Step 1: package-private helper shell과 correlation method를 작성한다.**

~~~java
package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

final class ChatWorkflowRequestTraceEnvelope {

    private ChatWorkflowRequestTraceEnvelope() {
    }

    static void rehydrateRequestCorrelation(String rawCorrelationId) {
        if (rawCorrelationId == null || rawCorrelationId.isBlank()) {
            return;
        }
        String correlationHash = SafeRedactor.hashValue(rawCorrelationId);
        if (correlationHash == null || correlationHash.isBlank()) {
            return;
        }
        TraceStore.put("trace.id", correlationHash);
        TraceStore.put("x-request-id", correlationHash);
        TraceStore.put("requestId", correlationHash);
        TraceStore.put("traceId", correlationHash);
    }

    static void seed(ChatRequestDto req, String sessionKey) {
        try {
            TraceStore.put("trace.runId", String.format(
                    "chat:%s:%d", SafeRedactor.hashValue(sessionKey), System.nanoTime()));

            String requestSid = null;
            try {
                requestSid = org.slf4j.MDC.get("sid");
                if (requestSid != null && !requestSid.isBlank()
                        && sessionKey != null && !requestSid.equals(sessionKey)) {
                    TraceStore.putIfAbsent("req.sid", SafeRedactor.hashValue(requestSid));
                    if (org.slf4j.MDC.get("requestSid") == null) {
                        org.slf4j.MDC.put("requestSid", requestSid);
                    }
                }
            } catch (Throwable failure) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.requestSid", failure);
            }

            try {
                if (req.getSessionId() != null) {
                    TraceStore.put("chatSessionHash",
                            SafeRedactor.hashValue(String.valueOf(req.getSessionId())));
                    if (org.slf4j.MDC.get("chatSessionId") == null) {
                        org.slf4j.MDC.put("chatSessionId", String.valueOf(req.getSessionId()));
                    }
                }
            } catch (Throwable failure) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.chatSessionId", failure);
            }

            String traceId = null;
            try {
                traceId = org.slf4j.MDC.get("traceId");
                if (traceId == null || traceId.isBlank()) {
                    traceId = org.slf4j.MDC.get("trace");
                }
                if (traceId == null || traceId.isBlank()) {
                    traceId = org.slf4j.MDC.get("x-request-id");
                }
            } catch (Throwable failure) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.mdcTraceId", failure);
            }
            if (traceId == null || traceId.isBlank()) {
                traceId = java.util.UUID.randomUUID().toString();
            }
            rehydrateRequestCorrelation(traceId);

            if (sessionKey != null && !sessionKey.isBlank()) {
                TraceStore.put("sid", SafeRedactor.hashValue(sessionKey));
                try {
                    org.slf4j.MDC.put("sid", sessionKey);
                    org.slf4j.MDC.put("sessionId", sessionKey);
                } catch (Throwable failure) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.mdcSession", failure);
                }
            }

            try {
                java.util.Map<String, Object> breadcrumb = new java.util.LinkedHashMap<>();
                breadcrumb.put("conversationSidHash", SafeRedactor.hashValue(sessionKey));
                breadcrumb.put("requestSidHash", SafeRedactor.hashValue(requestSid));
                breadcrumb.put("chatSessionHash",
                        SafeRedactor.hashValue(String.valueOf(req.getSessionId())));
                breadcrumb.put("traceIdHash", SafeRedactor.hashValue(traceId));
                ai.abandonware.nova.orch.trace.OrchEventEmitter.breadcrumb(
                        "conversation.breadcrumb.seed",
                        "Seeded conversation breadcrumb in MDC/TraceStore",
                        "ChatWorkflow.continueChat",
                        breadcrumb);
            } catch (Throwable failure) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.breadcrumb", failure);
            }
        } catch (Exception failure) {
            ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.envelope", failure);
        }
    }
}
~~~

위 코드는 현재 `ChatWorkflow.java`의 최소 trace-envelope statements를 동일한 순서와 key로 옮긴 구현 형태다. import 정리만 해당 신규 파일 범위에서 수행하고 다른 block을 끌어오지 않는다.

- [ ] **Step 2: `ChatWorkflow.continueChat`의 기존 envelope block을 호출 한 줄로 교체한다.**

~~~java
ChatWorkflowRequestTraceEnvelope.seed(req, sessionKey);
~~~

호출 위치는 `TraceStore.clear()`와 timeline/creative/presentation 복원 뒤, user query와 downstream chat 흐름 전에 유지한다. 기존 private `rehydrateRequestCorrelation`은 helper로 이동한 뒤 중복 소유하지 않는다.

- [ ] **Step 3: focused GREEN을 실행한다.**

~~~powershell
.\gradlew.bat test `
  --tests com.example.lms.service.ChatWorkflowRequestTraceEnvelopeTest `
  --tests com.example.lms.service.ChatWorkflowTraceRedactionContractTest `
  --no-daemon --project-cache-dir $cleanupProjectCache
~~~

Expected: 두 test class 모두 PASS, raw identifier leak assertion 0, 기존 redaction/source contract 유지.

- [ ] **Step 4: final production diff에서 이동 외 의미 변경이 없는지 확인한다.**

~~~powershell
git diff -- `
  main/java/com/example/lms/service/ChatWorkflow.java `
  main/java/com/example/lms/service/ChatWorkflowRequestTraceEnvelope.java `
  src/test/java/com/example/lms/service/ChatWorkflowTraceRedactionContractTest.java `
  src/test/java/com/example/lms/service/ChatWorkflowRequestTraceEnvelopeTest.java
~~~

Expected: `ChatWorkflow`에서는 helper 이동과 호출 전환만 보이고, prompt/provider/retrieval/memory/user copy 변경은 0.

### Task 4: 영향 검증과 handoff

**Files:**

- Verify: Task 3의 네 declared files
- Modify: none after verification unless an exact failure requires the same owned hunk

**Interfaces:**

- Consumes: focused GREEN patch와 current postimage hashes.
- Produces: Section 11 completion record, `APPLY | HOLD | ROLLBACK` final judgment.

- [ ] **Step 1: sourceSet, dependency purity, compile 경계를 fresh 실행한다.**

~~~powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes `
  --no-daemon --project-cache-dir $cleanupProjectCache
~~~

Expected: exit code 0, LangChain4j `1.0.1` purity PASS, sourceSet hygiene PASS, root와 `:app` classes PASS.

- [ ] **Step 2: whitespace, hash, secret count-only 검증을 실행한다.**

Section 4.6의 `rg`와 `git diff --check`, Section 9의 count-only secret scan을 그대로 실행한다. raw match, raw MDC, raw TraceStore payload를 보고서에 붙이지 않는다.

- [ ] **Step 3: Section 11 보고서를 채우고 verified diff에서 멈춘다.**

`FINAL=APPLY`는 모든 fresh command가 exit 0이고 완료 조건이 전부 참일 때만 쓴다. commit, staging, push는 하지 않는다. 불일치는 first error와 exact path/hash를 포함해 lane-local `HOLD` 또는 owned-hunk `ROLLBACK`으로 보고한다.

---

## 4. CLEANUP-A — ChatWorkflow 요청 추적 봉투 분리

### 4.1 목표와 현재 owner proof

현재 `ChatWorkflow.continueChat(ChatRequestDto, Function<...>)`는 다음을 연속 수행한다.

1. `ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY`를 clear 전에 보관한다.
2. `sessionKey`를 정규화한다.
3. `TraceStore.clear()`를 fail-soft로 실행한다.
4. request timeline, creative-emergence, presentation input을 복원한다.
5. `trace.runId`, request sid, chat session hash, correlation aliases, normalized session hash와 breadcrumb event를 다시 심는다.
6. 그 뒤 실제 chat/retrieval/answer 흐름으로 진입한다.

분리 seam은 현재 `ChatWorkflow.java`의 `rehydrateRequestCorrelation`과 `continueChat` 내부 최소 trace-envelope block이다.

- 현재 helper: 대략 1,122-1,134행
- 현재 envelope: 대략 1,179-1,260행
- `TraceStore.clear()`와 timeline/creative/presentation 복원: 대략 1,159-1,177행

행 번호는 탐색 보조일 뿐 patch anchor가 아니다. symbol과 현재 hash로 결합한다.

### 4.2 선언 대상

수정 허용 파일:

- `main/java/com/example/lms/service/ChatWorkflow.java`
- 신규 `main/java/com/example/lms/service/ChatWorkflowRequestTraceEnvelope.java`
- `src/test/java/com/example/lms/service/ChatWorkflowTraceRedactionContractTest.java`
- 신규 `src/test/java/com/example/lms/service/ChatWorkflowRequestTraceEnvelopeTest.java`

그 밖의 production 파일을 건드려야 한다면 이 directive는 `HOLD`다. 새 utility framework, 새 Spring bean, 새 dependency를 만들지 않는다.

### 4.3 RED

먼저 신규 테스트만 작성한다. production class가 아직 없으므로 새 helper 계약 테스트가 compile RED가 되는 것을 확인한다.

필수 테스트 계약:

1. `rehydratesCorrelationAliasesAsHashOnly`
   - raw correlation을 넣으면 `trace.id`, `x-request-id`, `requestId`, `traceId`가 모두 동일한 `SafeRedactor.hashValue`가 된다.
   - `TraceStore.getAll()`에는 raw correlation 문자열이 없다.
   - null, empty, blank 입력은 key를 만들지 않는다.
2. `seedsRequestAndSessionBreadcrumbsWithoutRawTraceStoreIdentifiers`
   - 기존 MDC의 request sid와 normalized `sessionKey`를 구분한다.
   - `req.sid`, `chatSessionHash`, `sid`, breadcrumb map 값은 hash-only다.
   - raw request sid, raw session id, raw correlation은 `TraceStore`와 event payload에 들어가지 않는다.
3. `preservesExistingMdcContract`
   - 기존 downstream 계약 때문에 MDC에 저장하던 `requestSid`, `chatSessionId`, `sid`, `sessionId`의 현재 의미와 조건을 바꾸지 않는다.
   - 이 리팩터링을 개인정보 정책 변경으로 사용하지 않는다.
4. `nullRequestLeavesSuppressionBreadcrumbsAndReturns`
   - null request를 넣어도 helper가 exception을 caller에 노출하지 않는다.
   - 현재 내부 suppression key `traceSeed.chatSessionId`, `traceSeed.breadcrumb`을 기록한다.
   - outer best-effort 경계와 suppression key `traceSeed.envelope`도 production 구현에 그대로 남긴다.
5. 기존 `ChatWorkflowTraceRedactionContractTest`의 ordering 계약은 내부 문자열 전체를 복제하지 않고 다음 세 지점만 확인한다.
   - `TraceStore.clear()`
   - `ChatWorkflowRequestTraceEnvelope.seed(req, sessionKey)`
   - 이후 refinement/chat 흐름의 첫 안정된 symbol
   - 순서는 `clear < seed < downstream`이어야 한다.

기존 테스트의 다음 두 책임은 신규 helper 테스트로 이동한다.

- `workflowRehydratesRequestCorrelationAsHashOnlyAliases`
- `workflowTraceStoreSeedsCorrelationAsHashOnly`

이동은 assertion 삭제가 아니다. 같은 의미를 helper의 public하지 않은 계약에 직접 결합하고, giant source-text fixture 의존만 줄인다. 나머지 `ChatWorkflowTraceRedactionContractTest`는 일괄 정리하지 않는다.

예상 RED 명령:

~~~powershell
.\gradlew.bat test `
  --tests com.example.lms.service.ChatWorkflowRequestTraceEnvelopeTest `
  --no-daemon --project-cache-dir $cleanupProjectCache
~~~

RED 이유는 `ChatWorkflowRequestTraceEnvelope` 부재여야 한다. 기존 baseline test 실패, unrelated compile 실패, stale classpath 실패면 production 수정 전에 원인을 분리한다.

### 4.4 최소 production patch

신규 class 계약:

~~~text
package: com.example.lms.service
name: ChatWorkflowRequestTraceEnvelope
visibility: package-private final
Spring annotation: none
mutable instance state: none
primary entry: static void seed(ChatRequestDto req, String sessionKey)
testable correlation seam: package-private static method 또는 동등한 좁은 seam
~~~

구현 규칙:

- `ChatWorkflow.java`에서 현재 `rehydrateRequestCorrelation`과 1,179-1,260행 상당의 최소 trace-envelope만 helper로 이동한다.
- `TraceStore.clear()`, request timeline 복원, `recordModelRequestTimelinePhase`, `rehydrateCreativeEmergenceTrace`, `RagControlRuntimeAdapter.capturePresentationInput`은 `ChatWorkflow.continueChat`에 그대로 둔다.
- `sessionKey` 생성 규칙을 이동하거나 바꾸지 않는다.
- `continueChat`에는 복원 단계 뒤 `ChatWorkflowRequestTraceEnvelope.seed(req, sessionKey)` 호출 하나만 둔다.
- 기존 trace key 이름과 suppression key 이름을 한 글자도 바꾸지 않는다.
- `trace.runId`의 session 부분은 계속 hash여야 한다.
- `req.sid`, `chatSessionHash`, `sid`, breadcrumb의 four identifiers는 계속 hash-only여야 한다.
- `System.nanoTime()`을 사용하는 현재 per-run uniqueness 의미를 바꾸지 않는다.
- `OrchEventEmitter.breadcrumb`의 event name, message, owner string을 유지한다.
- 기존 MDC write 조건과 raw MDC lifecycle을 이 구조 정리에서 바꾸지 않는다.
- helper는 `TraceStore.clear()`를 호출하지 않는다.
- helper는 request timeline, model health, prompt, memory, retrieval, provider, cancellation을 알지 못해야 한다.
- helper는 `@Component`, `@Service`, `@Configuration`, `@Aspect`, constructor injection을 사용하지 않는다.
- helper가 checked exception을 새로 caller에 노출하지 않는다.

현재 edited hunk 안의 mojibake comment는 짧은 유효 UTF-8 주석으로 교체할 수 있다. 단, edited hunk 밖의 comment/string/line ending을 일괄 정규화하지 않는다. 사용자 표시문과 prompt 문자열은 변경하지 않는다.

### 4.5 절대 금지

- `continueChat` 전체 분해
- direct-answer, memory, attachment, retrieval, fallback, model selection, prompt assembly 수정
- `PromptBuilder.build(PromptContext)` 경계 우회 또는 ad-hoc prompt 연결
- `TraceContext`에 억지로 책임을 합쳐 기존 MDC/ThreadLocal lifecycle 변경
- raw session, request, trace identifier를 새 로그나 TraceStore key에 기록
- suppression catch 제거 또는 generic logging으로 대체
- 기존 사용자 hunks를 HEAD 버전으로 되돌리기
- broad formatter, import optimizer, 파일 전체 encoding 변환
- 새 production dependency 추가

### 4.6 GREEN과 영향 검증

~~~powershell
.\gradlew.bat test `
  --tests com.example.lms.service.ChatWorkflowRequestTraceEnvelopeTest `
  --tests com.example.lms.service.ChatWorkflowTraceRedactionContractTest `
  --no-daemon --project-cache-dir $cleanupProjectCache

.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes `
  --no-daemon --project-cache-dir $cleanupProjectCache
~~~

추가 정적 확인:

~~~powershell
rg -n 'TraceStore\.clear\(|ChatWorkflowRequestTraceEnvelope\.seed|traceSeed\.|trace\.runId|req\.sid|chatSessionHash|conversation\.breadcrumb\.seed' `
  main/java/com/example/lms/service/ChatWorkflow.java `
  main/java/com/example/lms/service/ChatWorkflowRequestTraceEnvelope.java

git diff --check -- `
  main/java/com/example/lms/service/ChatWorkflow.java `
  main/java/com/example/lms/service/ChatWorkflowRequestTraceEnvelope.java `
  src/test/java/com/example/lms/service/ChatWorkflowTraceRedactionContractTest.java `
  src/test/java/com/example/lms/service/ChatWorkflowRequestTraceEnvelopeTest.java
~~~

LangChain4j는 모든 선언이 기존 `1.0.1`을 유지해야 한다. 이번 patch에는 dependency 파일 변경이 없어야 한다.

### 4.7 CLEANUP-A 완료 조건

모두 참이어야 `APPLY`다.

- 현재 target preimage를 즉시 재검증했다.
- three-way preflight가 순서 독립적으로 안정된 `APPLY`였다.
- helper는 package-private, stateless, non-Spring이다.
- `continueChat`의 clear/restore/seed/downstream 순서가 보존됐다.
- 모든 기존 trace key와 suppression key가 보존됐다.
- raw identifier가 TraceStore, breadcrumb payload, log로 새로 유출되지 않는다.
- focused tests, sourceSet hygiene, LangChain4j purity, compileJava, `:app:classes`가 fresh PASS다.
- 최종 diff에는 선언한 네 파일만 있고, 그중 production 의미 변경은 trace-envelope 이동뿐이다.

하나라도 실패하면 `HOLD` 또는 소유 hunk만 `ROLLBACK`한다. assertion을 느슨하게 만들어 GREEN으로 바꾸지 않는다.

---

## 5. CLEANUP-B — Web fail-soft soak KPI projection 조건부 분리

### 5.1 왜 바로 patch하지 않는가

`WebFailSoftSearchAspect`는 크지만 AOP ordering 결함은 확인되지 않았다.

- class에 현재 `@Order(Ordered.HIGHEST_PRECEDENCE + 50)`가 있다.
- fresh scanner의 aspect-order coverage approximation은 1.0이다.
- `aroundSearch`, `aroundSearchWithTrace`, `applyStages`, provider backoff, KPI projection과 emission이 한 class에 집중돼 있다.
- `emitSoakKpiJson` 하나가 대략 4,843-5,797행, 약 950행이다.
- 이 method는 projection만 하지 않는다. `TraceStore` read/write, backoff KPI refresh, dedupe, `ObjectMapper`, last-store update, log emission, WARN JSON, suppression breadcrumb를 섞는다.

따라서 method 전체 이동은 god-helper를 하나 더 만드는 잘못된 정리다.

### 5.2 별도 세션 진입 조건

다음 RED가 먼저 가능할 때만 `CLEANUP-B`를 시작한다.

1. `WebFailSoftSoakKpiProjectionTest`가 현재 output의 base key set, provider state, stage-count fallback, hash-only rid/sessionId, redacted reason을 고정한다.
2. helper 입력이 clock, MDC, `TraceStore`, logger, `ObjectMapper`, backoff coordinator를 직접 읽지 않는 typed immutable snapshot으로 표현된다.
3. helper가 반환하는 것은 새 `LinkedHashMap<String,Object>` 또는 동등한 deterministic projection이다.
4. 입력 snapshot을 만들기 위해 raw query, raw snippet, provider response, credential, authorization header를 복사할 필요가 없다.
5. snapshot 또는 helper가 지나치게 많은 collaborator를 요구하면 `HOLD_HELPER_SHAPE_NOT_COHESIVE`다.

### 5.3 조건부 선언 대상

별도 세션에서만 허용:

- `main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java`
- 신규 `main/java/ai/abandonware/nova/orch/aop/WebFailSoftSoakKpiProjection.java`
- `src/test/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspectTest.java`
- `src/test/java/ai/abandonware/nova/orch/aop/WebAwaitEventsSummaryTest.java`
- 신규 `src/test/java/ai/abandonware/nova/orch/aop/WebFailSoftSoakKpiProjectionTest.java`

### 5.4 조건부 최소 patch

- 첫 patch에서는 base/provider projection처럼 하나의 응집된 contiguous block만 추출한다.
- `@Aspect`, `@Order`, 두 `@Around` pointcut, `ProceedingJoinPoint`, `pjp.proceed(...)`, stage selection, provider call, retry/backoff mutation은 원 class에 둔다.
- dedupe, `refreshRateLimitBackoffKpis`, `TraceStore.put`, JSON serialization, last-store update, KPI logger와 WARN logger도 원 aspect에 둔다.
- helper는 `TraceStore.getAll()` 같은 raw global snapshot을 받지 않는다.
- helper는 `SafeRedactor.hashValue`와 stable reason canonicalization을 유지한다.
- helper는 `rid`와 `sessionId`를 raw output map에 넣지 않는다.
- existing `EcosystemKpiFields` 호출의 현재 순서와 의미를 보존한다.
- 기존 `WebFailSoftFailureTrace`, `WebFailSoftRescueQuerySorter`, `WebFailSoftTraceSuppressions`를 중복 구현하지 않는다.
- projection을 옮기면서 provider taxonomy를 합치거나 `disabled`, `empty`, `after-filter starvation`, `timeout`, `rate-limit`을 같은 상태로 축약하지 않는다.

### 5.5 필수 테스트

- 신규 helper direct test: 동일 snapshot은 key order와 값이 동일하다.
- rid/sessionId raw 값은 output/JSON에 없고 hash만 있다.
- raw query, snippet, provider response, dummy credential fixture가 output에 나타나지 않는다.
- null/empty stage counts와 empty output은 기존 fail-soft 결과와 같다.
- existing soak KPI tests 14개 이상이 그대로 PASS한다. 기존 fixture를 삭제해 통과시키지 않는다.
- `WebAwaitEventsSummaryTest`가 await timeout과 provider timeout을 계속 구분한다.
- reflection으로 `@Order(Ordered.HIGHEST_PRECEDENCE + 50)`를 고정한다.
- projection helper는 Spring/AOP annotation과 `ProceedingJoinPoint` 참조가 없어야 한다.
- pointcut의 `proceed` 호출 횟수와 rescue/retry cardinality는 현재 각 scenario와 같아야 한다. 모든 성공 경로가 무조건 1회라고 새로 가정하지 않는다.

focused command:

~~~powershell
.\gradlew.bat test `
  --tests ai.abandonware.nova.orch.aop.WebFailSoftSoakKpiProjectionTest `
  --tests ai.abandonware.nova.orch.aop.WebFailSoftSearchAspectTest `
  --tests ai.abandonware.nova.orch.aop.WebAwaitEventsSummaryTest `
  --no-daemon --project-cache-dir $cleanupProjectCache
~~~

### 5.6 CLEANUP-B HOLD 조건

다음 중 하나면 source를 옮기지 않고 characterization 결과만 보고한다.

- helper가 `ProceedingJoinPoint`, provider client, backoff coordinator, logger, `ObjectMapper`, last-store를 필요로 한다.
- helper 입력이 raw `TraceStore` 전체 map 또는 raw provider payload다.
- 기존 output key 삭제, rename, enum 의미 변경이 필요하다.
- pointcut order 또는 proceed cardinality가 변한다.
- current SHA-256이 문서 snapshot과 다르고 새 preimage를 재검토하지 않았다.
- `WebFailSoftSearchAspect.java`의 사용자 hunk와 겹친다.
- focused baseline이 patch 전부터 실패한다.

이 경우 완료 상태는 `HOLD_HELPER_SHAPE_NOT_COHESIVE`, `repositoryWideHold=false`다.

---

## 6. DynamicRetrievalHandlerChain 중복 소유권 판정

### 6.1 canonical root owner — 삭제 금지

`main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java`는 untracked이지만 root active sourceSet 아래에 있고 `@Component`다.

현재 확인된 소비/연결:

- `main/java/com/example/lms/config/RetrieverChainConfig.java`가 정확한 FQCN을 import하고 `ObjectProvider<DynamicRetrievalHandlerChain>`으로 선택한다.
- `retrieval.chain.mode=dynamic`일 때 dynamic chain을 가져오는 wiring이 있다. 기본이 `fixed`라는 사실은 dynamic owner 삭제 권한이 아니다.
- `KgStepRegistrar`, `WiringPrecheckRunner`, `ClasspathOriginReporter`, `CfvmKallocLearningAspect`와 여러 테스트가 직접 참조한다.

판정: `HOLD_SPRING_RUNTIME / HOLD_REFERENCED`. untracked 상태를 zombie 증거로 오해하지 않는다.

### 6.2 com.abandonware adapter — 삭제 금지

`main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java`는 Spring component로 보이지 않더라도 `com.abandonware.ai.probe.SearchProbeController`가 정확한 FQCN을 직접 소비한다.

판정: `HOLD_REFERENCED`. probe consumer의 계약과 대체 owner가 증명되기 전 삭제하거나 canonical class로 단순 rename하지 않는다.

### 6.3 root service.* stub — 별도 삭제 후보지만 현재 HOLD

`main/java/service/rag/handler/DynamicRetrievalHandlerChain.java`에서 확인된 냄새:

- root `service.rag.handler` package
- 76줄의 minimal stub
- duplicate `Autowired` import
- raw `Map`
- `@Component`
- 현재 수정 중인 working-tree 파일
- `LmsApplication` scan: `com.example.lms`, `com.nova.protocol`
- `AgentApplication` scan: `com.abandonware.ai.agent`, `com.example.lms`
- 위 두 scan 범위에는 root `service.*`가 포함되지 않음
- exact FQCN/import/string 탐침에서는 직접 소비자를 찾지 못함

이것은 강한 삭제 후보 신호지만 삭제 proof는 아니다. 현재 파일이 modified이므로 사용자 변경을 폐기할 수도 있다.

별도 삭제 승인 뒤 다음을 모두 통과할 때만 새 directive를 만든다.

1. 모든 application entry와 `@ComponentScan` 재확인
2. exact FQCN, import, reflection string, resource/config reference 검색
3. runtime/classpath origin 확인
4. bean context에서 root `service.*` bean 부재 확인
5. `checkSourceSetHygiene`, `compileJava`, 관련 context test PASS
6. current user hunk의 owner/의도 확인
7. destructive deletion에 대한 operation-level authority 확인

이번 directive에서는 파일을 삭제하지 않고 import 한 줄도 고치지 않는다.

### 6.4 :app shim

`app/src/main/java_clean/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java`는 `:app`의 별도 active sourceSet에 있는 65줄 shim/adapter다. root canonical owner와 단순 파일명만 비교해 중복 삭제하지 않는다. Gradle duplicate-FQCN exclusion과 실제 JAR contents를 별도 증명해야 한다.

---

## 7. 의도적으로 수정하지 않는 핫스폿

### 7.1 `HybridWebSearchProvider`와 `NaverSearchService`

둘 다 크고 현재 modified 상태지만, 이번 탐침에는 provider 의미를 바꿔야 하는 focused RED가 없다.

- broad catch를 개수 기준으로 일괄 좁히지 않는다.
- optional provider의 missing/blank/dummy/test/changeme/sk-local/unresolved credential fail-soft 계약을 바꾸지 않는다.
- provider disabled, provider empty, after-filter starvation, timeout, rate-limit을 섞지 않는다.
- outbound call, retry, timeout, fallback provider substitution을 정리 명목으로 변경하지 않는다.
- fake search result를 만들지 않는다.

### 7.2 AOP order

현재 `WebFailSoftSearchAspect`의 explicit order와 fresh aspect coverage가 확인됐다. ordering을 “깨끗하게 정리”한다며 새로운 global order registry, 공통 base aspect, annotation wrapper를 만들지 않는다.

### 7.3 broad catches와 mojibake

- broad catch count는 탐침 신호이지 자동 수정 목록이 아니다.
- 실제 실패 injection, 사용자 영향, stable owner, 기존 fallback 계약이 있는 한 catch만 후속 patch 후보가 될 수 있다.
- edited hunk 밖의 mojibake comment/string은 대량 치환하지 않는다.
- 사용자 표시문이나 prompt의 원문 의미를 추측 복원하지 않는다.

---

## 8. Browser, Computer, 외부 evidence lane

### 8.1 Browser

문서 작성 시 in-app Browser로 `http://127.0.0.1:8080/chat-ui`를 한 번 확인했다.

~~~text
browserEvidence=not_observed
port8080ListenerCount=0
port8081ListenerCount=0
navigationResult=net::ERR_CONNECTION_REFUSED
providerWireAttempt=not_observed
~~~

동일 상태에서 재시도하거나 runtime을 증거 장식용으로 시작하지 않았다.

~~~text
holdScope=localhost-runtime-proof
firstBlockingRule=owned-runtime-unavailable
blockingEvidence=8080/8081 listener 없음과 ERR_CONNECTION_REFUSED
independentWorkCompleted=source scan, isolated Gradle baseline, directive authoring
repositoryWideHold=false
evidence_needed=owned localhost runtime on 8080 or 8081 / verify after implementation with a port-owner gate
~~~

`CLEANUP-A`는 UI나 HTTP 의미를 바꾸지 않으므로 Browser proof가 완료 필수조건은 아니다. future runtime smoke를 실행한다면 task가 소유한 process와 새 build artifact로만 증명한다. 이전 screenshot이나 이전 JAR 증거를 재사용하지 않는다.

### 8.2 Computer

Computer lane에서 설치된 editor 후보를 read-only로 확인했다.

~~~text
computerEvidence=not_observed
editorCandidateCount=1
editorCandidate=Notepad++
targetableWindowCount=0
~~~

열린 대상 창이 없었고, unrelated/private tab 복원을 피하기 위해 editor를 새로 실행하지 않았다.

~~~text
holdScope=editor-visible-proof
firstBlockingRule=no-targetable-editor-window
blockingEvidence=Notepad++ windowCount 0
independentWorkCompleted=shell/file evidence and directive artifact
repositoryWideHold=false
~~~

### 8.3 provider와 DB

- 이 정리에는 provider 호출이 필요하지 않다.
- HTTP delivery, browser render, response hash는 provider generation proof가 아니다.
- observed wire attempt가 없으므로 `providerWireAttempt=not_observed`를 유지한다.
- Supabase, 다른 DB, credential, ACL, production state를 조회하거나 수정하지 않는다.

---

## 9. 격리 검증 환경

future implementation은 repository 안의 공용 Gradle cache를 공유하지 않는다.

~~~powershell
$cleanupVerifyRoot = Join-Path $env:LOCALAPPDATA 'Temp\awx-cleanup-chat-trace-envelope'
$cleanupGradleHome = Join-Path $cleanupVerifyRoot 'gradle-home'
$cleanupProjectCache = Join-Path $cleanupVerifyRoot 'project-cache'
New-Item -ItemType Directory -Force -Path $cleanupGradleHome,$cleanupProjectCache | Out-Null

$env:GRADLE_USER_HOME = $cleanupGradleHome
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'cleanup-chat-trace-envelope'
~~~

다른 host/agent와 병렬 `bootRun`하지 않는다. runtime smoke가 실제로 필요할 때만 port owner를 확인하고 task-owned process 하나를 시작한다.

고신뢰 secret scan은 값을 출력하지 않고 count만 보고한다.

~~~powershell
$declaredFiles = @(
  'main/java/com/example/lms/service/ChatWorkflow.java',
  'main/java/com/example/lms/service/ChatWorkflowRequestTraceEnvelope.java',
  'src/test/java/com/example/lms/service/ChatWorkflowTraceRedactionContractTest.java',
  'src/test/java/com/example/lms/service/ChatWorkflowRequestTraceEnvelopeTest.java'
)
$secretMatches = @(rg --no-heading --pcre2 '(?i)(sk-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{20,}|Authorization\s*:\s*Bearer\s+\S+)' -- $declaredFiles 2>$null)
'secretPatternHitCount={0}' -f $secretMatches.Count
~~~

raw match 내용을 최종 보고에 붙이지 않는다.

---

## 10. 실패, rollback, 보존 규칙

- rollback은 이 세션이 소유한 exact hunk와 신규 파일에만 적용한다.
- `git reset --hard`, `git checkout -- <file>`, broad delete, repository cleanup을 사용하지 않는다.
- 현재 사용자 변경분과 겹치면 자동 merge하지 않고 `HOLD_PREIMAGE_OVERLAP`으로 보고한다.
- 신규 helper만 만든 뒤 호출 전환이 실패했다면 helper/test의 소유 hunk만 되돌린다.
- source patch 뒤 focused test가 실패하면 기존 assertion을 삭제하거나 기대값을 완화하지 않는다.
- baseline failure가 재현되면 patch 전/후를 같은 isolated cache로 비교하고 첫 오류를 기록한다.
- generated build output은 source change로 보고하지 않는다.
- staging, commit, push, deploy는 별도 권한 없이는 하지 않는다.

`HOLD` 형식:

~~~text
holdScope=<affected lane only>
firstBlockingRule=<first applicable rule>
blockingEvidence=<path/hash/first error>
independentWorkCompleted=<what was still proven>
repositoryWideHold=false
evidence_needed=<one missing artifact> / verify with <one exact action>
~~~

`repositoryWideHold=true`는 모든 허가된 lane을 안전하게 진행할 수 없는 경우에만 사용한다.

---

## 11. 완료 보고 형식

~~~text
DIRECTIVE_ID=AWX-SOURCE-CLEANUP-PROBE-20260824
WORK_UNIT=CLEANUP-A-CHAT-TRACE-ENVELOPE | CLEANUP-B-WEB-SOAK-PROJECTION
BRANCH=
HEAD_BEFORE=
SOURCESET_PROOF=
TARGET_PREIMAGE_SHA256=
TARGET_POSTIMAGE_SHA256=
TARGET_STATUS_BEFORE=
PREFLIGHT_POSITIVE=
PREFLIGHT_NEGATIVE=
PREFLIGHT_NEUTRAL=
NEUTRAL_ORDER_STABLE=true|false
RED_TEST_PATHS=
RED_COMMAND=
RED_RESULT=
PATCH_FILES=
USER_HUNKS_PRESERVED=true|false
TRACE_KEYS_ADDED_COUNT=
TRACE_KEYS_REMOVED_COUNT=
SUPPRESSION_KEYS_CHANGED_COUNT=
RAW_IDENTIFIER_LEAK_COUNT=
SECRET_PATTERN_HIT_COUNT=
FOCUSED_TEST_COMMAND=
FOCUSED_TEST_RESULT=
SOURCESET_HYGIENE_RESULT=
LANGCHAIN4J_PURITY_RESULT=
COMPILE_RESULT=
APP_CLASSES_RESULT=
BROWSER_EVIDENCE=not_required|not_observed|observed
COMPUTER_EVIDENCE=not_required|not_observed|observed
PROVIDER_WIRE_ATTEMPT=not_observed
ROLLBACK_CHECK=
EVIDENCE_NEEDED_REMAINING=
FINAL=APPLY|HOLD|ROLLBACK
~~~

필수 해석:

- test command 실행과 실제 test PASS를 구분한다.
- compile/test GREEN과 runtime/browser GREEN을 구분한다.
- browser render와 semantic/provider 성공을 구분한다.
- 현재 preimage가 바뀌면 이 문서의 이전 `APPLY`를 재사용하지 않는다.
- helper 파일 수가 늘었다는 사실을 정리 성공으로 보고하지 않는다. 원 owner의 책임이 좁아지고 contract test가 직접화됐는지 보고한다.

---

## 12. 이 문서의 비목표

- `ChatWorkflow` 13,183줄의 전면 재작성
- 모든 1,000줄 이상 class 분해
- broad catch 3,276개 일괄 변경
- AOP ordering 재설계
- `HybridWebSearchProvider` 또는 `NaverSearchService` provider 의미 변경
- 세 종류 `DynamicRetrievalHandlerChain`의 일괄 병합/삭제
- root `service.*` stub의 즉시 삭제
- PromptBuilder boundary, memory policy, session lifecycle, provider routing, retrieval arbitration 변경
- 사용자 표시문/prompt/mojibake의 대량 추측 복원
- dependency upgrade 또는 신규 production dependency
- DB, Supabase, credential, ACL, production mutation
- localhost runtime 강제 시작
- dirty tree cleanup, staging, commit, push, deploy

최종 원칙은 하나다. 현재 GREEN과 사용자 변경을 보존한 채, `ChatWorkflow`의 요청 추적 봉투 하나만 직접 테스트 가능한 소유 경계로 이동한다. 그보다 넓어지는 순간 해당 patch는 정리가 아니라 새 위험이므로 `HOLD`한다.
