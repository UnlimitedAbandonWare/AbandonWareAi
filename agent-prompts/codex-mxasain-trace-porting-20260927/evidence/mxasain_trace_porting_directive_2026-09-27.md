# mxasain 웹 트레이스·디버깅 복원 및 선별 이식 Implementation Plan

> 실행 담당: Codex / Devin. 현재 소스를 기준으로 작업 단위별 실패 테스트 → 최소 수정 → 재검증 → 체크포인트 순서로 실행한다. 설치된 경우 executing-plans 계열 절차를 사용하되, 도구 설치나 대규모 규칙 정비를 선행 과제로 만들지 않는다.

**Goal:** 구형 설계의 장점인 ‘한 질문의 검색·문맥 조립·모델 호출·가드·실패 원인을 웹에서 연결해서 보는 경험’을 현재 mxasain 구조에 복원한다.

**Architecture:** 기존 TraceStore / OrchTrace / TraceSnapshotStore / DebugEventStore / TraceLogger / 답변별 trace UI를 유지한다. 이미 생성된 증거가 정제·화면 제한·저장·조회·재연결 과정에서 소실되는 경계를 먼저 고친다. 새 RAG 엔진이나 별도 관측 플랫폼을 만드는 작업이 아니다.

**Tech Stack:** 업로드에 확인된 Java, Spring MVC·Reactor, Thymeleaf/브라우저 JavaScript, DOMPurify, Logback, OpenTelemetry API. 현재 ZIP에 빌드 루트가 없으므로 실제 Spring·JDK·의존성 버전은 실행 루트에서 확인하고 유지한다.

**Spec:** 이 문서의 ‘설계 계약’과 동봉 `mxasain_trace_audit_evidence_2026-09-27.md`. 작성·외부 문서 확인 기준일: **2026-09-27, Asia/Seoul**.

---

## 0. 먼저 읽을 결론

**이번 현재본은 ‘디버깅 기능이 통째로 사라진 소스’가 아니다. 복원된 기능이 많고, 구형보다 안전하고 풍부한 부분도 많다.** 구형 파일을 통째로 덮어쓰면 답변별 소유권, 스냅샷 v2, 메타데이터 전용 표시, 비동기 취소·용량 제한, DOM 안전성이 다시 퇴행할 수 있다.

우선순위는 다음과 같다.

| 단계 | 실제 목적 | 먼저 할 작업 |
|---|---|---|
| A | 이미 찍힌 정보를 제대로 보여주기 | 안전한 숫자·불리언·상태의 타입 보존, 중첩 표 손실 수정, 누락 사유 표시 |
| B | 한 답변과 관련 로그를 정확하게 연결 | 기존 ID의 의미 정리, 서버 측 필터·페이지 조회, SSE 커서·gap 처리 |
| C | 외부 웹에서 과거 문제를 다시 분석 | 안전한 상세 스냅샷 영속화, 제한된 로그 어댑터, 선택 답변 증거 묶음 다운로드 |
| D | 옛 설계의 설명력을 추가 복원 | 원인·기여도·단계별 손실 요약, 보존 회귀 테스트, 기존 OTel 연결 검증 |

A만 완료해도 독립적으로 배포·검증 가능해야 한다. B/C/D를 한 번에 붙이는 거대한 패치로 만들지 않는다.

### 분석 범위와 한계

두 ZIP의 전체 항목을 열거하고, 디버깅·트레이스·오케스트레이션 관련 파일과 호출부를 추적했다. 현재 `main/` 2,364개 파일과 구형 ZIP 4,252개 파일을 식별했다. 구형의 비교 기준은 `src/main/`이며, 별도 문서·테스트도 참고했다. **모든 파일의 모든 실행 경로를 검증했다는 뜻은 아니다.**

현재 ZIP에는 `main/`만 있고 빌드 루트·테스트 트리가 없다. 배포 서버 접속, 실제 모델 호출, 전체 Gradle 빌드, 전체 서버 E2E는 하지 않았다. 대신 실제 현재본 `SafeRedactor`와 `PromptMasker`를 JDK로 컴파일해 실행했고, 실제 `chat-trace-ui.js`와 동봉 DOMPurify를 Chromium에서 실행했다. SSE 커서는 현재 클래스 본문을 그대로 추출한 격리 실험이다. 구체적인 결과는 근거 문서와 `probes/`에 있다.

### 소스 기준

- 수정 대상: `mxasain.zip` / SHA-256 `2f9590c7806f63b1ff960089359efef338c6322ede433d3b0f2009a02e934a58`.
- 참고 대상: `src111_mergex15.zip` / SHA-256 `4381359c3f04f57ad309f1da950192c2f3484e63950507e170bbf79c7d0e33f1`.
- 예상 canonical source root: `C:\AbandonWare\demo-1\demo-1\src`. 실제 작업 시 기존 프로젝트 지침과 현재 경로를 확인한다.
- 아래 `main/...` 경로는 source root 기준이다. 구형 `src/main/...`를 현재의 `src/src/main/...`에 복사하지 않는다.
- 줄 번호는 **첨부 ZIP 스냅샷 기준**이다. 실행 시 파일 해시와 메서드 이름을 함께 대조한다. 오래된 지시서보다 현재 소스와 재현 결과가 우선이다.

## 1. 잃어버린 설계에서 실제로 찾은 것

### 1.1 가장 중요한 복구 단서

구형 ZIP의 `docs/TRACESTORE_FLOW.md`에는 요청의 흐름과 생성 키가 명시돼 있다. 단순 로그 출력이 아니라 다음 구조였다. [E01]

```text
요청 / MDC / TraceFilter
  → 오케스트레이션 모드·게이트 판단
  → 질의 변환 / 키워드 선택 / 모호성 해소
  → 웹·벡터 검색 / 품질 제거 / 임베딩 캐시·폴백
  → 기여도·가드 판단 / DebugCopilot 원인 요약
  → Trace HTML·JSON

비동기 병합·배치 작업은 별도 TraceLogger NDJSON
```

복원할 장점은 ‘어느 컴포넌트가 있는지’보다 **앞 단계의 결과가 다음 단계에 얼마나 전달됐고, 어디서 줄거나 우회했는지**다. `orch.*`, `qtx.*`, `keywordSelection.*`, `embed.*`, `vector.*`, `ablation.*`, `dbg.copilot.*`를 관계 없이 나열하는 것만으로는 이 장점이 살아나지 않는다.

구형 `src/AGENT/50_DEBUG_PRESERVE.md`에는 바이브 코딩 에이전트가 디버그 기능을 소음으로 보고 삭제하지 못하게 하는 보존 규칙이 있다. 이 원칙은 복원하되 **클래스 수·문자열 존재만 검사하는 규칙이 아니라 실제 생성→정제→전송→표시 계약 테스트**로 바꾼다. 오래된 코드 자체를 영구 불가침으로 만들지는 않는다. [E02]

### 1.2 무엇을 이식하고 무엇을 보존할지

| 구형 자산·아이디어 | 현재 상태 | 이번 판단 |
|---|---|---|
| TraceStore 키 흐름도 | 구형 문서에서 재발견 | 현재 producer/consumer 기준 문서로 재작성 |
| Raw search / Final context / Orchestration 분리 | 현재 TraceHtmlBuilder에 존재 | 유지. 실제 LLM 전달 증거와 검색 후보를 더 명확히 분리 |
| 검색 단계 지연·쿼리별 표 | 현재 builder + 안전한 JS 정렬 존재 | 재구현하지 말고 표 손실·타입 정제 수정 |
| 오케스트레이션 모드·우회 사유 | 현재 생성·표시 경로 존재 | 값의 타입과 상태 라벨을 끝까지 보존 |
| 프롬프트 이벤트 / 모델 라우터 이벤트 | 현재 표 생성기 존재 | producer 값이 정제 과정에서 지워지는지 계약 검사 |
| DebugCopilot 원인·명령 제안 | 현재 서비스 존재 | 저장된 원인 ID·근거 키·안전한 템플릿을 표시하는 projection 연결 |
| 구형 renderAblationPanel / renderDebugCopilotPanel | 구형 파일에서 선언은 있으나 같은 파일의 호출을 찾지 못함 | ‘과거 정상 작동 기능’이라고 단정하지 말 것. 의도 참고 후 현행 서비스로 새 연결 |
| TraceLogger NDJSON의 배치·병합 이벤트 | 현재 logger와 파일 appender 존재 | 임의 파일 tail 대신 허용된 structured 이벤트 어댑터로 연결 |
| DebugEventStore + 관리자 SSE | 현재 ring·NDJSON·bounded worker 존재 | 유지. 서버 필터·재연결·gap·일관된 조회 보강 |
| 답변별 스냅샷 | 현재 v2 assistantMessageId 바인딩 존재 | 절대 v1 순서 추측 방식으로 되돌리지 않음 |
| 메타데이터-only 스냅샷 | 현재 producer/consumer 모두 지원 | 유지. 전체 상세와 구별하고 영속화 상태 표기 |
| 현재 `orch.events.v1` 표준 이벤트 | 구형보다 상당히 발전 | 관측 데이터의 중심으로 재사용. 새 병렬 이벤트 규격 남발 금지 |
| Grafana/soak 자료 | 구형에 별도 파일 존재 | 패널·검사 항목 참고 자료. 현행 metric 이름 확인 전 복사·설치 금지 |
| 구형 임의 HTML/inline JS/약한 token gate | 일부 구형·보조 코드에 존재 | 이식하지 않음 |

## 2. 이번 소스에서 확인한 문제와 가설의 구분

### F01 — 안전한 디버깅 값까지 사라진다. [격리 실행 재현, E05–E06]

`SafeRedactor`는 키에 `prompt`가 들어가면 숫자·불리언도 내용물로 취급해 요약 객체로 바꾼다. 키에 `token`이 들어가면 일부 예외 외에는 비밀로 취급한다. `TraceHtmlBuilder.sanitizeMeta()`는 이 규칙을 일괄 적용하며, `safeMetaKey()`는 토큰 관련 **키 이름까지 해시**로 바꾼다.

실제 현재 클래스 실행 결과:

| 입력 | 현재 결과 | 디버깅 손실 |
|---|---|---|
| `prompt.historyRendered = true` | `{present, len, hash12}` 객체 | 문맥 포함 여부를 즉시 읽기 어려움 |
| `prompt.events.webCount = 4` | `{present, len, hash12}` 객체 | 단계별 건수 표·비교가 무의미해짐 |
| `llm.call.approxInputTokens = 123` | `(redacted)` | 실제 입력 크기 추정 비교 불가 |
| `llm.ollamaNative.maxTokens = 1024` | `(redacted)` | 로컬 모델 길이 설정 분석 방해 |
| `memory.session.tokenEstimate = 300` | `(redacted)` | 세션 기억 잘림 분석 방해 |
| `orch.events.v1.phase = "FINAL"` | 요약 객체 | JSON 표준 이벤트와 HTML 표시가 달라질 수 있음 |

상기 토큰 키 중 실제 producer는 `ChatWorkflow`, `OllamaNativeChatModel`, `ChatHistoryServiceImpl` 등에 존재한다. 시험용 `budget.tokens.remaining` 등은 규칙 시연용 합성 키이며 현행 producer의 존재를 주장하지 않는다.

또한 `prompt.*` 요약 객체를 재정제하면 내부 `present/len/hash12`까지 다시 요약돼, 일부 결과가 **멱등하지 않다**. 원문 문자열은 복원하지 않으면서도 정보 손실이 누적된다.

**수정:** 비밀 마스킹을 약화하지 말고 정확한 키·경로·타입의 allowlist를 만든다. `prompt.*` 전체 허용, 모든 `*Tokens` 숫자 허용 같은 광역 예외는 금지한다. 입력 타입이 기대와 다르면 안전한 invalid-type 표시로 낮춘다. 이미 정제된 DTO를 다시 raw-content sanitizer에 넣지 않는다.

### F02 — 중첩 표 100행 제한이 뒤의 진단 그룹까지 삭제한다. [브라우저 재현, E07]

`chat-trace-ui.js:101–103`의 `table.querySelectorAll("tr")`는 해당 표의 직접 행뿐 아니라 하위 표 행까지 센다. 현재 builder는 바깥 `trace-kv` 표 안에 여러 이벤트 표를 넣는다.

실제 현재 JS + DOMPurify를 실행한 3,467자 합성 패널에서, 내부 이벤트 120개는 99개만 남고 **그 다음 바깥 진단 그룹이 사라졌다**. 60,000자 제한보다 훨씬 작은 입력인데 경고도 없었다. 스크립트·이미지 제거와 동일 진단 1개 유지 동작은 정상 확인했다.

**수정:** `row.closest('table') === table`인 직접 행만 해당 표의 예산으로 센다. 데이터 표와 바깥 레이아웃 표를 구분하고, 헤더는 데이터 행 수에 포함하지 않는다. 이후에는 페이지 단위 렌더링 또는 구조화 JSON 렌더링을 사용한다. `total/displayed/omitted`를 표시하고, terminal·failure 요약은 이벤트 표 밖에서 보존한다. DOMPurify와 textContent/replaceChildren 경로는 유지한다.

### F03 — 상세 스냅샷은 메모리, 영속 포인터는 짧은 요약이다. [정적 확인, E08]

`TraceSnapshotStore`는 메모리 ring이고 기본 최대 200개다. `?TRACESNAP?v2`에는 답변 ID·snapshot ID와 최대 2,048바이트의 요약 projection을 저장한다. `/snapshots/{id}/html`은 현재 store를 조회한다. 따라서 **포인터가 DB에 남는 것과 상세 HTML/JSON이 재시작 후 남는 것은 다르다.**

`TraceSnapshotExporter`는 허용된 일부 지표의 파일 exporter이고, `ChatSessionTraceRecorder`는 세션 종료의 안전한 요약·키 목록 기록이다. 이들이 존재한다고 전체 상세 스냅샷이 영속 복원된다고 보아서는 안 된다.

**수정:** 구조화된 안전한 상세 payload만 선택적으로 영속화하고, 같은 snapshot ID로 조회한다. archived / memory_only / pending / expired / capture_skipped / io_failed / unknown을 구분한다. 화면이 과거 자료를 찾지 못했다고 새 질의나 새 LLM 호출로 ‘복원’하지 않는다.

### F04 — ‘관련 로그 보기’는 현재 최신 목록의 클라이언트 필터에 가깝다. [정적 확인, E09]

관리자 두 화면은 traceId/sid 딥링크를 만들지만, 기본 조회는 `?limit=...`로 최신 N개를 받는다. 그 안에서 브라우저가 필터링한다. 요청의 기록이 최신 N개 밖에 있으면 **기록이 있는데도 빈 화면**이 될 수 있다.

**수정:** server-side exact 필터·커서·보존 범위 조회를 추가한다. `matchCountInScannedRange`, `hasMore`, `coverage`, `retentionState`를 내보낸다. ‘최근 50건 중 없음’을 ‘이 요청의 로그 없음’으로 표시하지 않는다. admin 글로벌 목록과 답변 소유자 바인딩을 분리한다.

### F05 — SSE는 재접속해도 중복·누락 범위를 정확히 설명하지 못한다. [정적 확인 + 커서 격리 실행, E10]

`Last-Event-ID`를 읽고 cursor를 복원하지만, 초기 backlog는 cursor로 거르지 않고 다시 보낸다. tail은 최근 `max(initialLimit,120)`개만 읽는다. 그 사이 발생량이 조회 창을 넘거나 ring에서 지워지면 gap을 표현하지 못한다.

커서는 `tsMs + 같은 시각의 id 집합`이다. 현재 클래스 본문을 실행하면 마지막 시각보다 **늦게 도착한 더 이른 timestamp의 이벤트를 새 이벤트로 인식하지 못한다.** 같은 timestamp의 다른 ID는 인식한다. 이 실험은 실제 네트워크 서버 재현과 구분한다.

**수정:** 저장 순서의 sequence와 process/stream epoch를 쓰고, event timestamp는 표시용으로 분리한다. 재접속은 cursor 이후만 읽으며, 잃은 범위·재시작·보존 만료는 명시적인 gap 이벤트로 보낸다. 기존 bounded worker·heartbeat·취소 처리는 유지한다. chat POST 스트림과 관리자 EventSource 스트림을 혼동하지 않는다.

### F06 — 웹 진단 이벤트가 전체 서버 로그를 뜻하지는 않는다. [정적 확인, E11]

`DebugEventStore`는 명시적으로 발행된 debug event를 보관한다. `TRACE_JSON`, `DEBUG_EVENT_JSON`, `SEARCH_TRACE`, 일반 application logger는 서로 다른 출력 경로다. `logs/debug-events.ndjson`와 DebugEventStore의 별도 일자별 mirror는 같은 이벤트의 복수 출력일 수 있다. 한 사건을 파일 개수만큼 세면 안 된다.

**수정:** 기존 debug event, TraceLogger의 structured event, 최종 session record를 우선 연결한다. 일반 로그는 명시한 logger·WARN/ERROR·요청 연관성에 한해 제한된 adapter로 연결한다. 임의 파일 경로를 받아 tail하는 API, root DEBUG 상시 활성화, stack/prompt 원문 무제한 전송은 만들지 않는다.

### F07 — ‘Final Context’ 표와 실제 모델 호출의 입력은 증거 수준이 다르다. [정적 확인, E12]

현재에는 `prompt.historyRendered`, `prompt.events`, `prompt.contextInjected.*`, `llm.call.inputChars`, `llm.call.approxInputTokens` 등 좋은 계측이 있다. 그러나 `prompt.contextInjected.delivered`는 현재 코드에서 특정 문맥 마커 포함 여부를 검사하는 값이다. 모델 전송의 네트워크 성공을 뜻하지 않는다.

**수정:** retrieved → filtered → selected → assembled → request-built → HTTP-started → completed/cancelled를 구분한다. 실제 provider payload에서 제거·축약될 수 있는 부분은 handoff fingerprint·role count·section count로 확인한다. 원문 프롬프트 저장이 아니라 **어느 경계에서 실제로 관측했는지**를 남긴다. 0은 측정된 0, null/unknown은 미관측, disabled는 비활성으로 구분한다.

### F08 — 관측 토글과 진단용 계산의 결합을 주의해야 한다. [정적 확인, E13]

현재 `debug=true`는 TraceFilter에서 dbgSearch와 `uaw.ablation.bridge`를 켠다. 이 사실만으로 답변이 바뀐다고 단정할 수는 없지만, ‘HTML 노출만 조절하는 토글’이라고 가정할 수도 없다.

**수정:** 새로운 로그 조회·상세 펼치기·export는 완전히 read-only로 만든다. 기존 debug-on/off 상태에서 모델·검색 호출 수, route, budget, prompt fingerprint를 먼저 비교한다. 관측과 진단 계산이 결합돼 있으면 실제 분기만 제한적으로 분리한다. 자동으로 boost, probe, triadic adjudication, 학습, retrieval retry를 켜지 않는다. 현재 DebugCopilot의 triadic 명시 실행 분리는 보존한다.

### F09 — 종료 캡처도 budget과 비동기 경계를 확인해야 한다. [정적 위험, E14]

`captureCustom()`도 `consumeBudget()`를 거친다. 상세 캡처가 제한되면 포인터 생성이 null로 끝날 수 있다. 필터 종료 후 ThreadLocal을 읽는 `TraceSnapshotFilter`는 비동기 생성의 실제 종료와 같은 시점이라고 보장할 수 없다. 현재 ChatApiController는 별도 final extraMeta와 terminal recorder 경로를 이미 갖는다.

**수정:** 기존 terminal 경로에서 캡처한 immutable 최종 projection을 저장의 입력으로 쓰고, 필터 종료 시점만 믿지 않는다. 상세 캡처가 budget으로 생략되면 이유와 terminal 요약을 남긴다. 관측 제한을 제거하거나 답변 성공을 저장 성공으로 둔갑시키지 않는다.

### F10 — 생성되는 세부 진단과 답변 화면의 매핑이 일치하지 않는다. [정적 확인, E18]

`QueryTransformer`는 `qtx.stagePolicy.enabled/clamped`, `KeywordSelectionService`는 여러 `keywordSelection.mode`를 기록하고, 현재 `DebugCopilotService`는 `dbg.copilot.causes/summary/actions`를 만든다. 하지만 답변 HTML의 orchestration 그룹은 선택된 prefix와 일부 개별 키만 보여주며, 이 세부 항목의 전용 표시가 모두 연결돼 있지는 않다. 별도 snapshot JSON 등에 값이 남을 수 있으므로 ‘데이터 자체가 전부 없어졌다’와 구분한다.

**수정:** WP8에서 producer→projection→section 연결표를 만든다. 질의 변환은 stage-policy/bypass, 키워드 선택은 mode/reason, 임베딩은 cache/failover, 벡터는 quality/drop, 원인 요약은 cause/evidence 참조를 같은 요청의 단계에 붙인다. 전부를 raw KV로 늘어놓지 말고, 먼저 미연결 핵심 필드부터 정제된 typed 상세로 연결한다. 새 엔진을 실행할 필요가 없다.

### 이번 지시서에서 결함으로 취급하지 않을 것

- 답변 ID 바인딩 미구현: 현재 v2와 assistantMessageId 검증이 있다. [E03–E04]
- 메타데이터-only HTML 미지원: 현재 정확한 두 producer shape를 지원한다. [E03]
- 정렬·필터 버튼 전부 소실: 현재 안전한 JS 재구성이 있다. [E03]
- 동일 trace/score 무제한 중복: 현재 dedup과 score 상한이 있다. [E03]
- SSE worker 무제한: 현재 bounded runtime이 있다. [E10]
- 웹 OFF면 동기 traceHtml 무조건 없음: 현재 OFF 분기에서도 빈 SearchResult를 생성하므로 그 가설은 성립하지 않는다. [E15]
- OpenTelemetry 연결 자체 부재: 현재 `MlaOtelBridge`가 있다. [E16]

---

## 3. 설계 계약

### 3.1 단일 자료 흐름

```text
기존 producer: TraceStore + orch.events.v1 + debug events + trace JSON
                    │
                    ▼
          안전한 typed projection / identity normalization
                    │
       ┌────────────┼─────────────┐
       ▼            ▼             ▼
  live 답변 요약   snapshot JSON   허용된 구조화 로그
       │        메모리 + 선택 보관     │
       └────────────┼─────────────┘
                    ▼
    답변별 상세 / 관리자 동일 요청 조회 / bounded SSE / 증거 묶음
```

기존 업무용 TraceStore를 다른 저장소로 교체하지 않는다. 화면용 projection은 데이터 복사·정제만 수행하며 결정 로직을 호출하지 않는다. HTML과 JSON이 서로 다른 redaction 정책을 유지하지 않게 한다. 기존 `rag-orch-events.v1` 의미를 재사용한다.

### 3.2 식별자 계약

| 항목 | 의미 | 금지 사항 |
|---|---|---|
| assistantMessageId | DB assistant 답변 ID | SYSTEM 포인터 메시지 ID로 대체 금지 |
| traceTurnId | 기존 응답 메타의 의미 유지; 호출부에서 포인터 메시지 ID인 경우가 있음 | 이름만 보고 assistant ID로 사용 금지 |
| snapshotId | 특정 captured snapshot 주소 | 최신 전역 snapshot으로 대체 금지 |
| traceIdHash / requestIdHash / sessionIdHash | 정제된 상관 키 | 해시를 인증 수단으로 사용 금지; 반복 해시 금지 |
| retrievalExecutionId / searchExecutionId / providerAttemptId | 하위 실행·시도 식별 | 서로 다른 재시도·검색을 한 행으로 합치지 않음 |
| eventId | 같은 사건의 불변 ID | 재전송·file mirror마다 새 사건으로 집계 금지 |
| streamEpoch + cursorSeq | 해당 저장·전송 스트림의 위치 | 업무 발생 시각이나 per-trace seq와 혼용 금지 |
| raw runId / clientToken | 현재 기록기에 따르면 attach/cancel 권한을 가진 값 | URL·export·로그·SSE ID로 절대 노출 금지 |

W3C traceparent의 표준 trace ID와 앱의 `hash:...` 식별자는 구분한다. 외부 표준 연계가 필요하면 기존 OTel 문맥과 별도 속성으로 연결한다. UI의 hashed correlation 값을 표준 span ID인 것처럼 바꾸지 않는다. [W5]

### 3.3 안전한 typed projection

권장 추가 파일: `main/java/com/example/lms/trace/TraceDiagnosticProjection.java`. 동등한 기존 클래스가 발견되면 통합하고 중복 생성하지 않는다.

**제안 인터페이스** — 아래는 현재 API가 아니라 이번 작업의 신규 내부 계약이다.

```java
public final class TraceDiagnosticProjection {
    public static Map<String, Object> project(Map<String, Object> raw);
}
```

출력은 `schemaVersion=awx.trace-view.v1`인 깊은 immutable copy다. 기본 섹션은 `identity`, `summary`, `orchestration`, `prompt`, `modelCalls`, `sources`, `capture`로 제한한다. null 입력은 빈 projection과 명시된 capture 상태로 처리한다. raw unknown 키는 기존 보수적 정책을 적용한다.

- `orchestration`: 기존 SnapshotStore의 standard event whitelist·label 처리를 공통화해 재사용한다. phase/step/status는 서버가 정의한 enum·label grammar로 검증한다.
- `prompt`: 실제 producer의 `historyRendered`, `contextInjected.history`, `historyChars`, `lastAssistant`, `memory`, `delivered`와 prompt.events의 허용된 카운터·라벨만 받는다.
- `modelCalls`: 현재 실제 producer의 `llm.call.approxInputTokens`, `llm.ollamaNative.maxTokens`, `llm.gateway.spec.contextTokens`, `memory.session.tokenEstimate` 등 정확한 키+Number 조합을 registry로 관리한다. 추정 토큰은 `estimated=true`로 표시한다.
- secret/password/authorization/cookie/ownerToken/clientToken의 원문 차단은 절대 해제하지 않는다. `hasOwnerToken` 같은 존재 여부를 허용할 때도 정확한 boolean 필드 한 개만 별도 승인한다.
- Map/String으로 잘못 들어온 숫자 필드, NaN/Infinity, 음수 count, 과도한 깊이, 이름을 위장한 키를 테스트한다. 잘못된 타입을 문자열로 ‘살려서’ 내보내지 않는다.
- 마스킹 요약 DTO는 명시적 타입 또는 버전 표식을 사용해 반복 정제로 변형되지 않게 한다. 불특정 사용자가 만든 `{present,len,hash12}` 모양을 trusted로 간주하지 않는다.
- UI에서는 이 DTO를 textContent로 그린다. `JSON.stringify(raw)`로 우회 표시하지 않는다.

### 3.4 정적 요약과 사건 타임라인의 조화

처음 펼치면 ‘요청한 모델 / 실제 모델 / 검색 사용 여부 / 최종 evidence 수 / 메모리 조립 상태 / 마지막 실패·우회 / 기록 보존 상태’를 보여준다. 상세에는 기존 항목을 다음 순서로 묶는다.

1. 입력·의도·설정 적용 상태.
2. 질의 변환·검색 branch 및 실제 시도별 결과.
3. 필터링·융합·재순위화·선택.
4. 기억/이전 답변/첨부/웹/벡터의 prompt 조립과 provider handoff.
5. 모델 호출·폴백·대기·취소·최종 반환.
6. 가드·기여도·DebugCopilot 원인 후보와 근거 event 링크.

모든 단계가 매 요청 존재할 필요는 없다. 실행되지 않은 단계를 초록색 성공으로 채우지 않는다. `disabled`, `skipped`, `empty`, `success`, `degraded`, `failed`, `cancelled`, `not_observed`를 구별한다. 순서는 causal parent/seq로 설명하고, 병렬 이벤트의 wall-clock 순서가 원인 순서라고 단정하지 않는다.

### 3.5 웹 접근과 노출 정책

‘외부 웹에서 본다’는 인터넷 무인증 공개가 아니라 **기존 관리자 인증으로 원격 조회한다**는 뜻으로 구현한다. 사용자-facing 답변 상세는 현행 owner+admin 조건을 유지한다. 관리자 글로벌 진단 페이지는 역할로 보호하되 owner 범위와 global 범위가 UI에 분명해야 한다.

기존 `/api/diagnostics/**` 인증 경계를 재사용한다. `/trace/events`, `/internal/stream/ops` 같은 별도 보조 route는 신규 UI에 바로 연결하지 않는다. 특히 OpsSseController의 X-Token은 빈 값 검사만 있으므로 실제 빈 등록·보안 체인·사용 여부를 먼저 확인한다. `/api/debug/ablation`은 별도 property+정확한 토큰 검증이 있으므로 ‘모든 debug API가 무보호’라고 단정하지 않는다. [E17]

새 응답에는 `Cache-Control: no-store`를 명시하고, export는 attachment disposition을 사용한다. 파일 기반 조회는 허용 root 내 서버가 정한 파일만 읽으며 path 입력, traversal, symlink 탈출, 과대 압축 해제 등을 차단한다. 로그인 세션·토큰을 query string으로 넣지 않는다. 로그 조회 자체와 권한 실패는 별도 최소 감사 항목으로 기록한다. [W1]

---

## 4. 실행 작업 묶음

### WP0 — 현재 작업과 충돌하지 않는 기준점 확보

**대상:** 현재 프로젝트 지침·작업 중 변경·실제 build root. 소스 변경 없음.

- [ ] 현재 root, wrapper, JDK, 테스트 태스크를 읽어 확인한다. 다른 에이전트의 변경·예약은 되돌리지 않는다.
- [ ] E01–E18의 현재 파일을 메서드 단위로 대조해 `still_present / already_fixed / moved / not_reproduced`를 기록한다.
- [ ] 현재 증거 파일과 핵심 UI screenshot을 로컬에 저장하고 외부 API 사용량 0인 baseline을 만든다.
- [ ] 기존 테스트와 신규 테스트를 분리해 baseline failure를 기록한다. ZIP에 없는 빌드 파일을 새로 지어내지 않는다.
- [ ] 각 WP의 touched files만 예약한다. 이 문서 전체 범위를 한꺼번에 독점하지 않는다.

**산출:** `docs/diagnostics/trace-porting-baseline.md` 한 개. 기존 동등 문서가 있으면 갱신한다. 불필요한 규칙·저널 파일을 여러 개 늘리지 않는다.

### WP1 — 타입 보존과 HTML/JSON 정제 정책 통일 [A, 최우선]

**수정:** `trace/SafeRedactor.java`, `service/trace/TraceHtmlBuilder.java`, `trace/TraceSnapshotStore.java`.
**필요 시 추가:** 위 `TraceDiagnosticProjection.java`.
**테스트 제안:** `test/java/com/example/lms/trace/TraceDiagnosticProjectionTest.java`, `test/java/com/example/lms/service/trace/TraceHtmlDiagnosticContractTest.java`.

- [ ] 먼저 실패 테스트: 실제 prompt boolean/count, 실제 token count가 정확한 타입으로 남고, orch phase/step을 snapshot JSON·HTML이 같은 라벨로 표시한다.
- [ ] 먼저 실패 테스트: 동일 안전 projection을 재처리해도 값·타입이 변하지 않는다. 단, untrusted map을 정제 완료 DTO로 가장하는 공격은 막는다.
- [ ] secret 문자열·raw prompt·raw query·URI credential·owner token이 어떤 형식으로도 새지 않는 음성 테스트를 넣는다.
- [ ] exact key/type registry와 기존 standardEvent 공통 projection으로 최소 수정한다. 기존 SafeRedactor 전체를 새 정책으로 갈아엎지 않는다.
- [ ] model/RAG 호출 mock count가 변경 전후 같음을 확인하고 typed 출력 golden fixture를 저장한다.
- [ ] 이 묶음만 체크포인트/commit한다.

**완료:** E06의 현상 재현을 ‘문제가 재현됨’에서 ‘타입 보존 검증 통과’로 대체할 실제 프로젝트 테스트가 있고, raw content는 여전히 차단된다.
**롤백:** 새 projection 호출부만 이전 경로로 돌린다. DOMPurify·secret mask를 끄는 rollback은 금지한다.

### WP2 — 중첩 표 손실·문자열 잘림 수정 [A]

**수정:** `resources/static/js/chat-trace-ui.js`, `resources/static/css/chat-trace.css`, 필요한 builder 렌더러.
**테스트 제안:** 기존 프런트 테스트 체계에 `chat-trace-ui.spec.*` 추가.

- [ ] 120행 내부 표 뒤에 다른 외부 진단 그룹이 있는 fixture를 넣는다. 그룹은 살아 있어야 하고 누락 수가 정확해야 한다.
- [ ] 헤더 보존, 여러 내부 표 각각의 cap, 60,000자 경계, 0/1/200행, 한글·emoji, 메타데이터-only를 테스트한다.
- [ ] 직접 데이터 행만 제한하고, 표별 `total/displayed/omitted`를 출력한다. 예산이 부족하면 덜 중요한 세부 항목을 생략하되 terminal/failure 요약은 보존한다.
- [ ] server HTML을 임의 substring으로 자르는 경로는 구조화 projection 예산 또는 완결된 section 단위 생성으로 옮긴다. 상한을 무작정 키우지 않는다.
- [ ] script/event-handler/URL/DOM clobbering/중첩 태그 공격과 정렬·필터 복원·dedup·aria-live 제외를 함께 검증한다.
- [ ] 3개 이상 패널을 열 때 fetch 동시성 2는 유지한다. 사용자가 닫았다 다시 열어야만 되는 실패 대신 취소 가능한 작은 대기열 또는 재시도 버튼을 사용한다. 세션 전환 후 이전 응답 덮어쓰기 금지.
- [ ] 체크포인트한다.

**완료:** 기존 E07 fixture에서 뒤 그룹이 보이며 누락 수가 표시된다. 초기 렌더 비용·동시 fetch 상한과 보안 동작이 유지된다.

### WP3 — 기존 답변 바인딩 유지 + 캡처 상태·종료 경계 정리 [A→B]

**수정:** `api/ChatApiController.java`, `api/ChatTraceSnapshotPointerPersister.java`, `api/ChatTraceMetaMessageRestorer.java`, `api/ChatSessionDetailResponseBuilder.java`, `trace/TraceSnapshotStore.java`의 해당 메서드만.

- [ ] 기존 v1·v2, 잘못된 ID, 충돌 포인터, 동일 timestamp 답변, 삭제된 assistant, owner 불일치를 먼저 회귀 검사한다.
- [ ] 최종 `extraMeta` 복사본을 snapshot/projection/terminal recorder가 공유하도록 연결한다. 최종화는 현재 run terminal owner에서만 한 번 수행한다.
- [ ] 상세 snapshot budget 소진·필터 제외·disabled·생성 실패 각각에 진단 상태를 남긴다. pointer가 없다고 ‘기록할 사건 없음’으로 표현하지 않는다.
- [ ] 응답 DTO에 additive한 `diagnosticAvailability`를 추가한다. 기존 포인터 v2 의미를 바꾸지 말고, 필요한 migration은 버전으로 명시한다.
- [ ] sync·stream·재연결·cancel·오류를 각 1회 이상 검증한다. cancel 전 assistant 미생성은 assistant ID를 만들어 붙이지 말고 run 단위 진단으로 둔다.
- [ ] debug 표시 on/off의 모델·검색 호출 수, route, budget, prompt fingerprint를 비교한다. 실제 차이가 확인될 때만 관측 옵션을 진단 계산에서 분리한다.

**완료:** live 상태와 이후 session reload 상태가 같은 답변에 연결되고, 상세가 없는 이유가 구체적으로 보인다. 전역 latest fallback은 0건이다.

### WP4 — 서버 측 동일 요청 조회와 안전한 딥링크 [B]

**수정:** `api/DebugEventsDiagnosticsController.java`, `debug/DebugEventStore.java`, `api/TraceSnapshotsDiagnosticsController.java`, 두 관리자 template.

**제안 신규 API:** `GET /api/diagnostics/debug/events/query`. 기존 `/events`의 List 응답을 바꾸지 않는다.

입력은 `traceIdHash`, `requestIdHash`, `sessionIdHash`, `probe`, `level`, `cursor`, `limit`의 검증된 optional 값이다. 복수 상관 키는 AND로 적용하고 exact match를 기본으로 한다. 기본 limit=50, 최대 200을 제안한다. 아래는 신규 응답 예시다.

```json
{
  "schemaVersion": "awx.debug-page.v1",
  "events": [],
  "nextCursor": null,
  "hasMore": false,
  "coverage": "memory_window",
  "retentionState": "unknown",
  "omittedCount": null
}
```

- [ ] 대상 사건 이후 다른 사건 100개가 생긴 fixture에서도 서버 필터로 과거 대상이 조회되는 테스트를 먼저 작성한다.
- [ ] store가 갖고 있는 범위에서 먼저 필터한 뒤 페이지 제한한다. 최신 N개를 먼저 잘라서 필터하는 구현 금지.
- [ ] snapshot 목록에도 같은 exact 필터를 적용하되 기존 JSON envelope를 보존한다. 상세 포인터 snapshotId가 있으면 직접 조회를 우선한다.
- [ ] 잘못된 cursor/필터는 400, 권한 부족은 기존 정책, 존재하지 않음·보존 만료는 정보 공개 정책에 맞춰 구별한다. ID만 알면 접근 가능한 구조를 만들지 않는다.
- [ ] 두 화면의 딥링크를 동일 필터 계약에 연결한다. 원본/해시를 반복 변환하지 않고 현재 저장된 표기와 일치시킨다.
- [ ] page 상태를 읽는 동안 provider·LLM·reranker·memory write mock은 호출되지 않아야 한다.

**완료:** ‘최신 N개 중 없음’과 ‘이 범위에서 기록 없음’의 UI가 다르고, 같은 답변 → snapshot → debug event → snapshot 왕복이 유지된다.

### WP5 — SSE 재연결·gap·일관된 순서 [B]

**수정:** `debug/DebugEvent.java`, `debug/DebugEventStore.java`, `api/DebugEventsDiagnosticsController.java`, `api/DebugEventsSseRuntime.java`의 필요한 seam, `templates/debug-events.html`.

**계약:** 기존 `event.id`는 사건 식별자로 유지한다. 저장 append 순서의 monotonic sequence와 process epoch를 additive하게 제공한다. 새 SSE ID는 검증 가능한 `epoch:seq` 커서이고, 보존된 구형 UUID Last-Event-ID는 가능한 경우 대응 위치로 변환한다. 변환할 수 없으면 gap/legacy 상태를 표시한다.

- [ ] 재접속 시 이전 event가 backlog로 다시 나오는 재현 테스트를 먼저 작성한다.
- [ ] timestamp 역전·동일 timestamp·한 poll에 120개 초과·ring eviction·process restart·모르는 cursor를 테스트한다.
- [ ] 초기 backlog와 tail 모두 동일한 `readAfter(cursor, filters, pageSize)` 규칙을 쓰게 한다. page가 남으면 drain하되 poll당 처리 예산을 둔다.
- [ ] cursor 배정·ring 삽입·읽기 snapshot이 같은 순서를 보장하도록 한다. AtomicLong 숫자만 먼저 받게 하고 삽입 순서를 방치하지 않는다.
- [ ] `gap`은 잃은 범위 또는 `unknown_count`와 원인을 명시한다. keepalive/hello는 data cursor를 전진시키지 않는다.
- [ ] 필터 스트림의 전역 seq 건너뜀은 비대상 사건일 수 있으므로 자동으로 loss로 계산하지 않는다. scanning watermark와 emitted cursor 의미를 명시한다.
- [ ] 같은 EventSource 자동 재접속과, 사용자가 pause 후 새 EventSource를 만드는 경우를 각각 검사한다. 후자는 저장한 안전한 cursor를 명시 전달한다.
- [ ] 기존 worker 상한·heartbeat·종료 callback을 보존한다. IOException 이후 emitter의 이중 완료와 자원 정리를 프로젝트 Spring 버전 기준으로 검증한다. [W2–W3]

**완료:** 보존된 범위는 누락 없이 replay하거나, 복구 불가능한 범위를 명시한다. exactly-once를 과장하지 말고 at-least-once + eventId dedup 계약으로 설계한다.

### WP6 — 상세 보관과 ‘이 답변 증거 묶음’ [C]

**수정:** 기존 SnapshotStore와 diagnostics controller에 repository seam을 연결한다.
**필요 시 추가:** `trace/TraceSnapshotRepository.java`, `trace/FileTraceSnapshotRepository.java`, `api/TraceEvidenceBundleService.java`. 기존 동등 구현이 있으면 재사용한다.

**제안 API:** `GET /api/diagnostics/trace/snapshots/{id}/bundle`. 새 API이며 현재 존재한다고 가정하지 않는다.

- [ ] snapshot을 만든 뒤 store를 새로 생성해도 같은 ID의 안전한 상세 JSON을 찾는 실패 테스트를 먼저 작성한다.
- [ ] default-off 옵션으로 구조화 payload를 원자적으로 보관한다. HTML은 동일 projection에서 재생성하며 HTML을 원본 증거 저장소로 삼지 않는다.
- [ ] 제안 초기 상한: snapshot payload 256 KiB, 총 128 MiB 또는 7일 중 먼저 도달한 한도, export 2 MiB. **현재 설정값이 아니라 시작 제안값**이다. 단위·상한 초과 동작을 코드와 테스트에 명시한다.
- [ ] 정제→제한된 큐→temp 파일→atomic rename 경로를 사용한다. queue full/disk full/permission failure는 답변 실패로 전파하지 않고 archive 상태에 남긴다. 보관 성공 전에 archived라고 표시하지 않는다.
- [ ] 필터 종료가 아니라 실제 terminal projection으로 저장한다. 상세 캡처가 생략되어도 최소 terminal 상태·사유는 독립적으로 유지한다.
- [ ] bundle에는 `manifest.json`, `summary.json`, `trace.json`, `events.ndjson`, `logs.ndjson`, `README.txt`를 넣는다. 없는 source는 가짜 빈 성공 파일 대신 manifest에 unavailable 사유를 표시한다.
- [ ] manifest에 schema/build 식별, captureAt, snapshot ID, 안전한 correlation, source별 범위·drop·truncation, redactionVersion, checksum, 종료 상태를 넣는다.
- [ ] delete/expiry가 되어도 DB의 기존 pointer를 다른 snapshot으로 재활용하지 않는다. 다중 인스턴스에서는 node/epoch를 명시하고 공유 repository 없는 경우 local-only 한계를 표시한다.
- [ ] bundle 생성 중 외부 API 호출·학습·재검색이 0회임을 검사한다.

**완료:** 브라우저를 닫았다 열거나 앱을 재시작해도 설정한 보존 범위의 같은 답변 증거를 내려받을 수 있다. 만료·실패는 정확히 표시된다.

### WP7 — 웹 로그 가시성 확대: 기존 출력 통합부터 [C]

**수정 후보:** `trace/TraceLogger.java`, `debug/DebugEventStore.java`, `debug/ChatSessionTraceRecorder.java`, `resources/logback-spring.xml`, 진단 조회 서비스.

- [ ] 출력 source별 목록과 event ID·correlation·redaction 경계를 먼저 작성한다. 파일 mirror를 별개 사건으로 집계하지 않는 테스트를 넣는다.
- [ ] 1차는 기존 DebugEventStore, TraceLogger의 safe structured event, session terminal record만 묶는다. emitter publish 또는 고정 포맷 파일 reader 중 기존 프로젝트에 맞는 가장 작은 seam 하나를 선택한다.
- [ ] 화면에 `pipeline events / application diagnostics / terminal record` source 구분을 유지한다. 배치 이벤트가 요청 ID가 없으면 background로 분리하고 최근 사용자 세션에 억지 연결하지 않는다.
- [ ] 2차 일반 application 로그는 허용 logger·WARN/ERROR·bounded queue·rate limit·stack frame 개수 제한을 갖는 adapter로만 추가한다. 먼저 trace-correlated 로그로 범위를 좁힌다.
- [ ] adapter는 자신의 logger와 TRACE_JSON/DEBUG_EVENT_JSON의 순환 재수집을 제외한다. JSON 처리 실패·queue drop 자체가 다시 무한 로그를 발생시키지 않게 한다.
- [ ] 독자적인 임의 경로 파일 다운로드, stdout 전체 노출, 환경변수·설정 dump, Actuator 전체 공개는 금지한다.
- [ ] 로그 pane은 기본 닫힘, tail 중지/재개, level·source·동일 요청 필터, 누락 범위·보존 상태 표시를 제공한다.

**완료:** 선택한 답변에 관련된 구조화 로그를 웹에서 볼 수 있고, ‘현재 어떤 로그는 수집 대상이 아닌지’까지 표시된다. “모든 서버 로그 수집 완료”라고 허위 표기하지 않는다.

### WP8 — 옛 설계의 설명력과 보존 규칙 복원 [D]

**수정 후보:** 기존 `service/trace/DebugCopilotService.java`, `TraceHtmlAblationAttributionCalloutRenderer.java`, `trace/attribution/*`, `trace/StageBoundaryBreadcrumbs.java`, `ai/abandonware/nova/orch/trace/OrchTrace.java`, `MlaOtelBridge.java`.

- [ ] E18의 producer→projection→section 연결표와 누락된 핵심 필드의 golden fixture를 만든다. 전체 raw KV 공개로 해결하지 않는다.
- [ ] 원인 요약이 저장된 event·근거 키만 읽고 새로운 model/probe를 실행하지 않는 테스트를 작성한다.
- [ ] `dbg.copilot.*`의 cause ID, confidence의 출처, evidence references, 안전한 action template을 typed projection에 연결한다. command 문자열을 shell로 자동 실행하지 않는다.
- [ ] 원인 ID 클릭 시 같은 요청의 근거 event를 열게 한다. 근거가 없으면 원인 가설이라고 명시하고 확정 진단으로 표시하지 않는다.
- [ ] ablation/기여도는 실제 counterfactual 측정, 휴리스틱 점수, 관측 기반 설명을 구분한다. 화면의 퍼센트를 실제 원인 확률·품질 향상율로 바꾸어 설명하지 않는다.
- [ ] 현재 OTel bridge를 검증한다. 이미 종료 후 생성한 짧은 span을 실제 전체 stage duration으로 해석하지 않는다. 우선 event 속성 `stageMs`로 표현하고 실제 interval span은 시작·끝·부모 context가 증명될 때만 생성한다. [W4–W5]
- [ ] 기존 지침에 아래 보존 규칙의 짧은 포인터를 추가하고, active source 삭제/이동 시 계약 테스트도 함께 갱신하게 한다.

**프로젝트 지침에 넣을 문구:**

> 디버깅 복원은 현재 동작하는 답변·세션·라우팅을 보존하는 관측 작업이다. TraceStore 키나 logger를 소음으로 간주해 삭제하지 말고 producer→typed projection→API→UI→reload/export 계약을 먼저 확인한다. 단순 문자열/클래스 존재 테스트만으로 완료하지 않는다. 비밀·원문 노출, 최신 전역 snapshot 대체, 관측 UI에서의 재검색·LLM·학습 호출을 금지한다. 같은 결함을 고칠 때는 실패 테스트, 최소 변경, 검증 산출물을 남긴다. 합의한 acceptance를 통과하면 후속 기능을 임의 추가하지 않고 멈춘다.

---

## 5. 검증 행렬과 종료 조건

| 케이스 | 반드시 검증할 결과 | 담당 |
|---|---|---|
| Web ON/OFF, RAG ON/OFF 4조합 | disabled / empty / missing 구분, sync와 stream의 설명 일치 | WP1/3 |
| 세 번째 세션에서 기억 조회 | 과거 assistant 바인딩, retrieved/assembled/handoff 단계 분리 | WP3/8 |
| 한 답변의 여러 provider attempt | request 공통·attempt 구분, 폴백 사유 보존 | WP1/4 |
| trace 반복 수신·score 반복 수신 | 답변당 패널 1개, 중복 없는 안정된 상세 | WP2/3 |
| 내부 표120행 뒤 terminal 그룹 | terminal 보존, omitted 정확, 경고 표시 | WP2 |
| 60,000자 HTML·한글 경계 | 깨진 section·조용한 삭제 없음 | WP2 |
| snapshot capture budget 소진 | 생략 사유, terminal 요약, 가짜 성공 없음 | WP3 |
| 예전 v1·현재 v2·충돌 포인터 | 잘못된 답변에 붙이지 않음 | WP3 |
| 50건 밖 과거 이벤트 딥링크 | server exact 조회 또는 보존 범위 한계 표시 | WP4 |
| 재접속·시각 역전·순간 대량 이벤트 | cursor replay/dedup/gap 계약 | WP5 |
| stream 종료·pause·timeout·브라우저 닫기 | worker·fetch·timer 회수, 상한 유지 | WP2/5 |
| 앱 재시작·ring eviction·보관 만료 | archive 또는 명시적 expiry, latest 대체 금지 | WP6 |
| disk full·queue full·reader 손상 | 답변 경로 비간섭, 보관/로그 상태 설명 | WP6/7 |
| 관리자/다른 owner/비로그인 | 현행 정책에 맞는 거부, payload 미노출 | WP3–7 |
| raw query/credential/경로/HTML 공격 | 정제·DOM·file root 제약 유지 | WP1/2/6/7 |
| passive 조회·export·상세 펼치기 | 모델/검색/probe/training 추가 호출 0 | 전체 |
| debug 표시 on/off | route/budget/calls/prompt fingerprint 동등 또는 기존 차이를 명확히 보고 | WP3 |

### 성능·비용

관측을 켰다고 추가 유료 모델 호출이나 반복 self-probe가 생기면 실패다. 현재 bounded runtime, fetch 상한, NDJSON 큐 제한을 보존한다. 성능 기준은 운영 root에서 동일 mock·동일 fixture로 정한다. 제안 회귀 기준은 first-token·terminal 응답 p95 증가가 `max(50ms, baseline의 5%)`를 넘지 않는 것이다. 이 숫자는 **제안 기준이지 이번 분석에서 측정된 성능이 아니다.** 로그 조회·export 부하는 생성 경로와 분리한다.

### 테스트 명령의 사용법

현재 ZIP에는 wrapper와 테스트가 없으므로 아래는 실행 담당이 실제 build root를 확인한 뒤 사용하는 패턴이다. 태스크·프런트 runner 이름을 임의 생성하지 않는다.

```powershell
# 실제 Gradle wrapper가 있는 루트에서 실행한다.
.\gradlew.bat test --tests "*TraceDiagnosticProjectionTest" --tests "*TraceHtmlDiagnosticContractTest"
# 이후 해당 WP의 실제 추가 테스트명과 기존 회귀 suite를 실행한다.
# node --check는 syntax check일 뿐 DOM·서버 E2E의 대체가 아니다.
node --check .\src\main\resources\static\js\chat-trace-ui.js
```

WP별 최종 보고는 **바뀐 파일 / 실패 재현 / 실행 명령·결과 / 미실행 범위 / rollback 방법** 다섯 항목만 명확히 남긴다. 외부 서비스 장애로 검증하지 못한 항목은 성공으로 적지 말고 mock 검증과 분리한다. 관련 없는 기존 실패를 해결하느라 작업 범위를 늘리지 않는다.

### Stop rule

A의 합의된 테스트를 통과하면 우선 체크포인트한다. 이미 고쳐진 항목은 다시 작성하지 않는다. 확실한 다음 결함이나 승인된 다음 WP가 없으면 멈춘다. 동일 가설을 반복 스캔하거나 새로운 엔진·대시보드·복잡한 agent framework를 추가하지 않는다. 이번 사용자의 요구는 **구형 장점을 현재본에 조화롭게 이식하는 지시서**이지, 모든 기능을 새로 만드는 작업이 아니다.

---

## 6. 외부 공식 근거와 적용 범위

아래는 구현 원칙 검증용 공식 자료다. 프로젝트의 실제 의존성 버전·현재 코드 정상 동작을 입증하는 자료는 아니다. 본문 [E..]는 첨부 소스/실험 근거, [W..]는 외부 자료다.

- **[W1] OWASP Logging Cheat Sheet** — 요청 상관 식별, 민감정보 제외, 접근 제어, 로그 실패 시 영향 최소화. 이 보고서는 최대 가시성을 ‘안전한 필드와 연결 정보 확대’로 해석한다.
  https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
- **[W2] WHATWG HTML — Server-sent events** — id/Last-Event-ID와 reconnect 규칙. 애플리케이션 저장소의 과거 이벤트 replay와 gap 복구는 별도 구현해야 한다.
  https://html.spec.whatwg.org/multipage/server-sent-events.html
- **[W3] Spring Framework — Asynchronous Requests** — ThreadLocal 비동기 전파, heartbeat, emitter IOException 처리. 현재 프로젝트 버전을 유지한 채 해당 버전 문서와 대조한다.
  https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html
- **[W4] OpenTelemetry Java API** — Context/Scope 및 span 부모 관계. 기존 MlaOtelBridge의 문맥·시간 의미를 검증하는 데 사용하며 새 유료 관측 서비스 도입을 요구하지 않는다.
  https://opentelemetry.io/docs/languages/java/api/
- **[W5] W3C Trace Context** — 표준 traceparent/tracestate와 식별자 규격. 앱 내부 hashed correlation과 표준 trace ID를 혼동하지 않는다.
  https://www.w3.org/TR/trace-context/
- **[W6] DOMPurify 공식 저장소** — sanitizer 구성·DOM 반환 사용. 기존 안전 렌더링을 유지하고 정제 이후 untrusted HTML 문자열을 재삽입하지 않는다.
  https://github.com/cure53/DOMPurify

이 작업에는 Spring major upgrade, LangChain4j upgrade, tracing vendor 이전, Prometheus/Grafana 설치가 필수 사항이 아니다.
