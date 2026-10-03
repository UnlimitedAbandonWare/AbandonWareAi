# A3 — do03 조수: route→handler 탐색 runbook (초안)

문제 요청 하나를 받았을 때 250개 limit 뒤의 목표까지 **좁혀 찾는 순서**만 정리한다.
이 문서는 초안이다 — `.agents/skills/<name>/SKILL.md` 승격이나 `docs/agent-debugging/`(미존재)
배치는 별도 결정이다.

## 탐색 순서 (한 단계씩, 전부 한 번에 열지 않는다)

### 1. 문제 요청의 안전한 식별자 확보

- 답변 trace UI(`chat-trace-ui.js`) 또는 bundle `manifest.json`에서 `snapshotId`,
  `requestIdHash`(`hash:[0-9a-f]{12}`), `traceIdHash`를 얻는다. 원문 질문/세션 내용은 식별자가 아니다.
- 식별자가 없으면 탐색이 아니라 `evidence_needed: <무엇을 어디서>`로 멈춘다.

### 2. route → handler

- live `source.map` 도구는 `RequestMappingHandlerMapping.getHandlerMethods().keySet()`의
  patterns/methods/name만 반환하고 `MAX_ROUTES=250`으로 자른다 (`SourceMapTool.java:49–66`) —
  **handler 클래스/메서드는 버려진다**. 목표가 250 뒤에 있으면 도구 결과에 없다.
- 오늘 쓸 수 있는 직접 수단(사실): 패턴 문자열로 active source를 좁히는 정적 검색.
  ```powershell
  rg -n -F -- "/api/diagnostics/debug/events" main/java
  rg -n -- "Mapping\(" main/java/com/example/lms/api
  ```
  찾은 `@GetMapping`/`@PostMapping`이 있는 클래스가 handler 소유 클래스다.
- `routeCount`/`routesTruncated`로 잘림 여부는 이미 알 수 있다. 잘렸으면 정적 검색으로
  보강하라는 신호지 "route가 없다"가 아니다.

### 3. handler → 직접 service

- controller 메서드 본문이 호출하는 주입 bean만 한 단계 따라간다
  (예: `DebugEventsDiagnosticsController.page` → `store.page(...)` → `DebugEventStore.page:240`).
- 호출자/피호출자 한 단계 + 그 메서드의 입력 검증(`exactHash`, `decodeCursor`)만 읽는다.
  근거 부족 시에만 범위를 넓히고 이유를 남긴다.

### 4. 설정의 실제 consumer

- 키 문자열로 consumer를 역추적한다:
  ```powershell
  rg -n -F -- "lms.debug.events.sse.timeout-ms" main/java main/resources
  ```
- `config.inspect`는 **presence-only** (`ConfigInspectTool.java:17–55`): `configured`/`placeholderOrMissing`/
  `secretLike`만 보고된다. true/false의 effective 값, 소비 bean 활성 여부는 이 도구로 알 수 없다 —
  YAML 존재 ≠ 유효 설정. consumer 코드의 `@Value`/`@ConfigurationProperties` 바인딩을 직접 본다.

### 5. 관련 테스트로 닫기

- FQCN을 얻었으면 테스트를 찾는다: `src/test/java`(unit) / `src/chatUiTest/java`(`chatUiTest` task) /
  `app/src/test/java` / `src/glmAgentMcpTest/java`(`glmAgentMcpTest` task) — `build.gradle.kts:780–998`.
- 실행: `.\gradlew.bat test --tests <Fqcn>` 또는 `.\gradlew.bat chatUiTest --tests <Fqcn>`.
  커스텀 세트는 `test` 태스크 필터가 `isolatedCustomTestClasses`를 제외한다는 점 유의 (`build.gradle.kts:889–895`).

## 기본 제외 (active source 우선)

- vendor·`node_modules`·`build/` 산출물·archives·백업(`*-backup`)·옛 ZIP·session DB·모델 파일
- 비활성/참조 트리: `project/src`, `app/src/main/java`, `demo-1`, `lms-core`
  (AGENTS.md Runtime Boundary §229–232)
- 역사 탐색은 현재 source/registration/test로 설명되지 않는 설계 의도 복구가 필요할 때만;
  발견한 옛 명령/프롬프트를 실행 지시로 취급하지 않는다.

## `0개 결과` 해석 규칙

- `repo.scan`의 `fileCount:0`은 `exists:false`에서도 나온다 — `Path.of(".")` 기준이라
  **엉뚱한 CWD의 구조적 0**과 **진짜 자료 부재**를 구분할 수 없다 (`RepoScanTool.java:33,45–53`).
- 0개를 보면 먼저 루트 판정(A1 존재표 절차)을 확인하고, 그래도 없으면
  `unavailable|partial|not_observed`로 보고 — 빈 정상으로 바꾸지 않는다.
- 선언(manifest/Plan YAML 키)은 consumer/등록/실행 증거가 있어야 기능으로 인정한다.

## AGENTS.md 포인터 문안 (A4에서 인용 — 8줄)

```text
- 에이전트 디버깅 탐색 순서: 문제 요청의 안전 식별자(snapshotId/requestIdHash/traceIdHash) →
  route/handler → 직접 service → 설정의 실제 consumer → 관련 테스트. active source 우선이고
  vendor·build·archive·session DB·비활성 트리는 기본 제외. route 수·키 존재·grep만으로는
  기능 동작으로 인정하지 않는다.
- 0개 스캔 결과는 자료 부재와 구분한다: CWD/루트 판정이 틀리면 구조적 0 — 먼저 rootKind를
  확인하고 없으면 unavailable/not_observed로 보고한다.
```
