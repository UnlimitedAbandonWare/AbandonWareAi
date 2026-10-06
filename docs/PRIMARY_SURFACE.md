# Primary Surface and P0 Entry (2026-10-06)

Desktop 기준 루트는 `C:\AbandonWare\demo-1\demo-1\src`입니다. Codex·Devin·AGI CLI는
현재 사용자 요청 → [운영 규칙](../AGENTS.md) → [현재 현황](PROJECT_STATUS.md) 순서로 읽고,
필요한 [컨텍스트 카탈로그](tri-agent-context/README.md)만 선택합니다.
과거 보고서의 실행 명령·완료 문구·모델명은 현재 실행 권한이나 구현 증거가 아닙니다.

## 현행 기준과 구현 확인

- [주력 화면](PRIMARY_SURFACE.md): 메인 `/chat` chat-ui. 면접/Display 디버깅 화면의 결과는 별도 근거입니다.
- [답변 공개 계약](agents-rules/DEMO1-EVIDENCE-ZERO-RELEASE.md): 지원되는 내용과 저위험 일반 설명을 제공하되 검색 장애·불확실성을 밝힙니다. 미지원 구체 주장·알려진 반박·취소·개인정보·메모리 차단 보호를 유지합니다. AGENTS의 짧은 제목보다 이 본문을 읽습니다.
- [API 라우팅](API_ROUTING_SPEC.md), [메인 RAG 스킬](../.agents/skills/demo1-rag-platform/SKILL.md): 최신 정책과 실제 배선은 별도로 확인합니다. 모델명·가격·OAuth 권한을 옛 문서에서 추정하지 않습니다.
- 코드 기준: `settings.gradle`이 활성이고 `settings.gradle.kts`는 sentinel입니다. `build.gradle.kts:775-794`의 `main/java`, `main/resources`, `src/test/java`가 활성입니다. 빈 `:app`의 성공은 제품 검증이 아닙니다.
- 확인된 선언: Java 17, Spring Boot 3.3.4, LangChain4j 1.0.1, Resilience4j 2.2.0, Gradle wrapper 8.7. 의존성 선언·소스 존재(`SOURCE_PRESENT`), focused 검증, 실행/배포 확인을 구분합니다.

## 10월 13일 면접 백엔드 마감: P0 목표 계약

아래는 사용자 요구이며 전체 구현 완료 선언이 아닙니다. 기존 Spring 메인 오케스트레이터의
기본 흐름을 보존하고, 오늘은 현재 P0 한 가지의 근거 → 최소 작업 → focused 검증 순서로 진행합니다.
RRF 확장·추가 로깅·보조 기능은 핵심 P0 통과 후 별도 작업으로 둡니다.

| 완료 조건 | 필요한 검증 |
|---|---|
| 정상 요청 | 기존 메인 `/chat` 생성·최종 응답·이력 계약 유지 |
| 검색/검증 서비스 실패 | 가능한 지원 부분 답변·저위험 일반 설명과 실제 장애/미확인 범위 안내; 초안 전체 자동 공개 금지 |
| 전체 생성 실패 | 명확한 오류·복구 상태; 성공한 모델 응답/검증으로 표시 금지 |
| 취소 전파 | 하위 작업 중단, 취소 이후 추가 답변·호출·저장 없음 |
| 사용자·세션 격리 | 동시 두 세션의 답변·이력·첨부·replay 비혼입; 개인정보 캐시를 타인 답변으로 재사용 금지 |
| 한 사용자 과부하 격리 | 다른 사용자의 정상 요청 유지, 점유 자원 해제·재수용 확인 |
| 제한된 큐/재시도/시간 | 기존 한도와 실측 상한을 함께 기록; 무한 timeout 확대·보호 assert 완화 금지 |
| 검증 단계 분리 | 1단계 현재 source/test SHA에 묶인 focused 단위/통합 검증; 2단계 별도 승인 범위의 실기·배포·부하 실측 |

### 바로 다음 P0 한 가지

**검증 판정불능 시 답변 내용 분리 경계를 먼저 재현·검증합니다.** 확인 당시
`ChatWorkflow.java:4507-4513,8279-8285`는 strict/scope 제약이 없는 unknown/fail_soft에서
`safeCandidate` 전체를 `UNVERIFIED`로 공개할 수 있고, `:8209`는 이미 공개 허용된 경우
부분 발췌 복구를 건너뜁니다. 이는 공개 계약의 미지원 구체 주장 제거 요구와 대조할
소스상 간극 후보입니다. 현재 파일 SHA를 다시 확인해야 하며 결함 재현·수정·전체 P0 완료는 `NOT_RUN`입니다.
최신 검색 admission의 제한된 성공은 [현재 현황](PROJECT_STATUS.md)에 남겨 두며,
그 결과를 전체 답변 품질·다중 사용자 부하 검증으로 넓히지 않습니다.

다음 담당자는 동일 요청에서 일반 설명/지원 부분 보존, 미지원 수치·관계 제거,
known-negative 재공개 방지, 실제 검색 장애와 일반 unknown 구분, `knowledgeWriteAllowed=false`,
취소 후 출력/저장 0을 focused 검사로 묶습니다. 정상 요청 회귀 및 두 세션 비혼입도 유지하고,
실기·배포·과부하 실측은 별도 단계의 `NOT_RUN`으로 시작합니다. 활성 파일 소유권을 먼저 확인합니다.

## 버전에 맞는 공식 자료

확인 날짜: **2026-10-06 UTC**. 현재 릴리스 문서 대신 해당 버전의 공식 태그/API를 확인했습니다.
공식 자료는 라이브러리 계약 근거이며 demo-1 구현·런타임 성공 증거가 아닙니다.

| 공식 URL | 버전·적용 범위 |
|---|---|
| [Spring Boot 의존성 원본](https://raw.githubusercontent.com/spring-projects/spring-boot/v3.3.4/spring-boot-project/spring-boot-dependencies/build.gradle) | 3.3.4 BOM 관리 범위; H2 2.2.224·Reactor BOM 2023.0.10. 실제 resolved/runtime 버전 확인은 별도 |
| [LangChain4j ChatModel](https://github.com/langchain4j/langchain4j/blob/1.0.1/langchain4j-core/src/main/java/dev/langchain4j/model/chat/ChatModel.java) | 1.0.1 `ChatRequest`→`ChatResponse`, listener 및 오류 전파 경계; 기존 PromptBuilder 유지 |
| [CircuitBreakerOperator](https://github.com/resilience4j/resilience4j/blob/v2.2.0/resilience4j-reactor/src/main/java/io/github/resilience4j/reactor/circuitbreaker/operator/CircuitBreakerOperator.java) | 2.2.0 publisher 구독 허용/OPEN 거부; 사용자별 자원 격리의 대체 증거가 아님 |
| [TimeLimiterOperator](https://github.com/resilience4j/resilience4j/blob/v2.2.0/resilience4j-reactor/src/main/java/io/github/resilience4j/reactor/timelimiter/TimeLimiterOperator.java) | 2.2.0 Mono/Flux timeout 적용; 전체 요청·큐·재시도 예산은 별도 검증 |

## 실행과 역사 자료

실행·종료·진단은 기존 `Start-RAG.bat`, `Close-RAG.bat`, `Debug-RAG.bat` 및
[서버 수명주기 규칙](agents-rules/DEMO1-SERVER-LIFECYCLE-VERIFY.md)을 따릅니다.
문서 정리만으로 서버를 시작하거나 재시작하지 않습니다.
역사·회귀 근거는 [기존 아카이브](agent-archive/README.md)에 남기고,
이번 백업 격리는 [원래 경로·해시·이유·복원 방법](agent-archive/quarantine/20261006-docs-current-p0/manifest.json)으로 추적합니다.

## 오래된 루트 README의 사용 경계

루트 README의 Zero Break v0.1 pack은 역사 설계 자료입니다. 해당 통합 경로·프로필·엔드포인트와
CitationGate 최소 개수는 현재 실행 지침으로 사용하지 않습니다. README의 `<signed>` 토큰 예시가
현재 checkpoint 비밀 패턴 검사에 걸려 이번에는 원문 수정이 HOLD되었습니다.
가드를 우회하지 않았고 아래의 현행 목표·기존 운영 규칙을 우선합니다.

## 기존 화면 판정 계약 (원문 보존)

아래 화면 기준은 보존합니다. 구현 줄번호·설정 수치·미완료 작업 문구는 당시 관찰이므로
현재 SOURCE_PRESENT·실행 확인으로 확대하지 않고 현재 소스·현황표로 다시 확인합니다.

# Primary Surface SSOT (2026-10-01)

모든 에이전트 룰·지침·스킬·도구가 "채팅이 된다"/"기동 정상"을 판정할 때의 기준 화면.
도구 분류 표: `scripts/TOOL_SURFACE_MAP.md`.

## 1. 주력 화면: AbandonWare AI 메인 `/chat`

- 공개: https://abandonwareai.kro.kr/chat
- 로컬: http://127.0.0.1:18180/chat
- 구현: `PageController.chatUi` → `main/resources/templates/chat-ui.html` + `main/resources/static/js/chat*.js`

## 2. 디버깅 화면: 면접 화면 (곁 RAG & Display Studio)

- 위치: `main/resources/static/assets/interview/*` (`assets/display/*` 포함).
- 성격: 가끔 쓰는 **로컬 디버깅 화면** — 면접이나 사용자 대상 제품 화면이 아니다. 지우지 않는다.
- 진입: 별도 작업(`devin-interview-absorb`)이 만들 `/debug/studio`(loopback 전용, `debug.studio.enabled`).
  그 전까지는 `/assets/interview/index.html` 직접 접속.

## 3. 판정 규칙

- "채팅이 된다"와 "기동 정상"은 **메인 chat-ui** 기준으로만 판정한다.
- 면접 화면에서 얻은 결과는 보조 증거 칸에 따로 적는다 — 주력 판정에 섞지 않는다.
- `demo.interview.enabled=true` 상태에서는 `/chat`(및 `/`,`/index`,`/chat-ui`)이 면접 화면으로
  forward되므로(`PageController.java:292,298,328` + `InterviewDemoFilter`) 그 응답으로 메인을 판정하지 않는다.
- chat-ui 식별자: `<title>AbandonWare AI</title>` + `/js/chat.js` 로드 + 면접 표지
  (`RAG & DISPLAY STUDIO` 브랜드, `/assets/interview/` 참조, "INTERVIEW DEMO"류 문구) 부재.

## 4. 현재 상태와 할 일

- `main/resources/application.properties:971` = `demo.interview.enabled=true` — 플래그가 메인을 덮을 수 있다.
- 공통 기본값 `false` + interview opt-in profile 분리는 `devin-interview-absorb` Phase B가 맡는다.
  이 문서는 그 결과를 기다리지 않고 **판정 기준만** 고정한다.

## 5. 에이전트별 한 줄

- **Codex**: `/chat` 작업·검증은 chat-ui 기준; 면접 결과는 보조 증거로만.
- **Devin**: 동일. 런처·진단 판정은 `scripts/TOOL_SURFACE_MAP.md` 분류와 함께 본다.
- **Grok CLI**: 주력 = `/chat` chat-ui; 면접 = 디버깅 화면.
- **Clean (Cline)**: 주력 = `/chat` chat-ui; 면접 = 디버깅 화면.
- **Windsurf**: 주력 = `/chat` chat-ui; 면접 = 디버깅 화면.
