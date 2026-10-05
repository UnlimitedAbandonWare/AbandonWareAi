# TRI-SYSTEM ISOLATION SPEC — Meta Display / RAG / Main Chat

- 작성: 2026-10-05 Devin(SWE-2). 상태: 목표 아키텍처 명세 (as-built가 아님).
- 소스 지시서: Devin tri-system isolation directive (DV01). 구현 소유: Codex (Java Source Owner).
- 표기: `사실` = 이 체크아웃에서 직접 확인, `추정` = 지시서/문서 주장 미검증, `policy-conflict` = 지시서와 라이브 소스 충돌.
- 검증 도구: `python -B scripts/probe_tri_system_isolation.py --dry-run` (15+ assertion),
  `node --test scripts/test_tri_system_frontend_isolation.cjs`.

## 0. 목적

Meta Ray-Ban Display(웨어러블 HUD / Nova Focus), Dynamic RAG 플랫폼, 메인 `/chat`
챗봇이 하나의 거대 공유 파이프라인(`ChatWorkflow`, `StandardPromptBuilder`)을 지나면서
프롬프트 제약·검색 제어·모델 라우팅·프론트엔드 표면이 서로 새는 결함을 원천 격리한다.

## 1. 시스템 경계 정의

### 시스템 1 — Meta Ray-Ban Display (웨어러블 HUD / Nova Focus)

- 물리 환경: 600×600 단안 HUD(우측 렌즈), 신경 밴드(sEMG)·Fold6 컴패니언 제어. `사실`(메타런타임 룰)
- 표면: `/meta`, `assets/display/*` (`lens.html/lens.js`, `receiver.html/receiver.js`,
  `display-focus-controls.js`, `display-focus-flow.js`, `display-conversate.js`). `사실`
- 프롬프트 제약: 글자수 상한 80–800(기본 400, `NovaFocusSettings.effectiveAnswerLengthChars` `사실`),
  줄 단위 간결 출력, 마크다운 표/복잡한 섹션 템플릿 금지, 출처 덤프 금지.
  현재 구현: `DISPLAY FOCUS OUTPUT` 블록(`StandardPromptBuilder.java:623-625` `사실`).
- 검색/도구: Quick 모드(`quickAnswerEnabled`) 시 웹/RAG/Jev 재시도 억제 —
  `NovaFocusAnswerService.java:62-71`에서 `useRag(false)`, quick이면 `webTopK=0`·`searchMode=OFF`. `사실`
- 모델 라우팅: `chatgpt-oauth:gpt-5.6-luna` (API/OAuth 우선). 모델 ID는
  `configs/cloud-models.manifest.yaml:177` + `application-meta-display.yml:74,207`에 존재 `사실`;
  "기본값으로 고정"은 `추정` — `conversate.focus.default-model` 빈 기본값(`NovaFocusAnswerService.java:39`).
- 실행 대상: `NovaFocusSettings.ExecutionTarget = {AUTO, API_ONLY, LOCAL_ONLY}` `사실`
  (`index.html` `nf-answer-target` 셀렉트가 LOCAL_ONLY를 노출) — §4 정책 충돌 참조.

### 시스템 2 — Dynamic RAG Platform (심층 검색 / 지식 베이스 / 인용 검증)

- 표면: 백엔드 검색 엔진·임베딩 파이프라인(제품 UI 아님).
- 기능: 하이브리드 검색(BM25+벡터), GraphRAG, 다중 홉 근거 수집, `SECTION TEMPLATE`
  (OVERVIEW/DETAILS/REFERENCES), `EVIDENCE_ONLY` 게이트, `SafeExcerptDto` 출처 앵커.
- 하드웨어: RTX 3090 로컬 레인(임베딩·로컬 RAG). LLM 챗 서빙과 분리 정책 `사실`
  (`docs/agents-rules/DEMO1-RTX3090-WATCH.md`, `DEMO1-GPU-LANE-EVIDENCE.md`).

### 시스템 3 — LLM Main Chatbot (메인 `/chat`)

- 표면: `/chat` → `templates/chat-ui.html` + `js/chat.js` 등 `js/chat-*.js`. `사실`
- 상태/세션: 영구 `ChatSession`, 세션 목록(`sessionListRefresh*`, `chatAccessState`,
  `strictBackendSessionId` — `chat.js` 실재 식별자 `사실`), SSE 스트리밍.
- 모델 라우팅: API/OAuth 우선 사다리 → 무료 API → 로컬 Ollama 슬롯 관리 Fast-Spillover
  (`configs/api-routing.yaml`, `docs/API_ROUTING_SPEC.md`가 SSOT).
- 인증: PROTO_OPEN (익명 우선, role-gate 없음) `사실` (`demo.auth.proto-open`).

## 2. 교차 전파 경로 맵 (as-built, 2026-10-05 실측)

```
Fold lensSettings (profile_db LENS_SETTINGS)
  → NovaFocusHistoryService.Context { answerLengthChars, quickAnswerEnabled, transcript[] }
  → NovaFocusAnswerService.answer()
      ChatRequestDto: useRag=false, useWebSearch=web, webTopK=0|3, searchMode=OFF|AUTO
      ChatConversationContext: focusAnswerLengthChars (+80..800 검증, ChatConversationContext.java:24)
  → ChatService → ChatWorkflow
      PromptContext.builder().focusAnswerLengthChars(conversationContext.focusAnswerLengthChars())
      (ChatWorkflow.java:3048 — 유일한 매핑점 `사실`)
  → StandardPromptBuilder.buildInstructions()
      L529 focusOutput = ctx.focusAnswerLengthChars() != null   ← 길이 필드만으로 표면 판정
      L538 !focusOutput → SECTION TEMPLATE 주입
      L623 focusOutput  → DISPLAY FOCUS OUTPUT 주입
      L732 !focusOutput → minimum words 주입
```

핵심 결함: 표면 구분이 "길이 필드 null 여부"라는 암묵 신호에 의존한다. `/chat` 경로가
실수로 `focusAnswerLengthChars`를 채우면 즉시 Display 프롬프트로 전환되고, 반대로
Display 요청이 필드를 잃으면 RAG 섹션 템플릿이 렌즈 답변에 섞인다. `사실`

## 3. The 5 Anti-Pollution Invariants (목표 불변 규칙)

| # | 불변 | 규칙 | 현재 상태(사실) |
|---|---|---|---|
| I1 | Prompt | `focusAnswerLengthChars`는 Display 채널에서만 주입; 일반 `/chat`·RAG의 `minWordCount`/`sectionSpec`을 마스킹·제거하지 않는다 | 부분 충족: 길이 필드가 있으면 SECTION TEMPLATE·minWords 상호배타로 차단되나, 표면 판정이 `surfaceOrigin`이 아니라 필드 존재 여부에 의존 → 격리 보강 필요 |
| I2 | Search | Display Quick(`quickAnswerEnabled`)의 `useWebSearch=false`/`useRag=false`는 `NovaFocusAnswerService` 어댑터 경계 안에 국한; 글로벌 `ChatWorkflow`·RAG 기본값을 오염시키지 않는다 | 충족 방향: 플래그는 요청 DTO에만 세팅됨. 잔여 위험: 공유 `ChatRequestDto`/`ChatConversationContext`가 같은 인스턴스를 재사용하면 전이됨 → DTO 격리 복제 필요 |
| I3 | Hardware & Model | Display 요청은 자동 경로·폴백·스필오버에서 로컬 Ollama 큐/RTX 3090 임베딩 레인에 진입하지 않는다. 슬롯 경합 시 로컬 서빙 전이가 아니라 에러 | **policy-conflict**: 라이브 `ExecutionTarget.LOCAL_ONLY` 사용자 옵션 존재. §5 미결 참조 |
| I4 | Surface | `chat.js`는 Display 컨트롤러·렌즈 DOM 셀렉터를 포함하지 않고, `display-focus-controls.js`는 세션 목록/메인 챗봇 DOM을 조작하지 않는다. `/interview` 실패는 메인 서피스 판정에 영향 없음 (`docs/PRIMARY_SURFACE.md`) | 충족 (2026-10-05 실측 0건). `chat-display-bridge.js`는 chat-ui.html 로드·기본 OFF의 **선언된 opt-in 브리지** — 오염 아님 |
| I5 | Verification | 실물 안경(`onGlasses`)·실물 OAuth 로그인은 데스크톱 CI에서 `NOT_RUN_DEVICE`/`NOT_RUN_AUTH`로 분리 표기; 이를 이유로 BLOCKED 루프에 빠지지 않는다 | 규칙 신설 필요 (면접화면 오인 BLOCKED 사고 재발 방지) |

## 4. 알려진 결합 지점과 목표 조치

| 지점 | 결함 | 목표 조치 (Codex 소유) |
|---|---|---|
| `StandardPromptBuilder.java:529` | 표면 판정이 `focusAnswerLengthChars() != null`만 | `SurfaceOrigin` enum 도입 후 `ctx.surfaceOrigin()==META_DISPLAY`와 결합 |
| `ChatWorkflow.java:3048` | 컨텍스트 → PromptContext 직접 매핑 | 요청 수신 시 `SurfaceOrigin` 확정 + Display DTO는 격리 인스턴스로 복제 |
| `NovaFocusAnswerService.java:69-71` | `useRag(false)`·quick 시 `webTopK=0`이 어댑터 경계 안에서만 유효해야 함 | DTO deep-copy + 경계 밖 역류 금지 테스트 |
| `chat-ui.html:24` | `chat-display-bridge.js` 로드 | 유지 — 단, opt-in 게이트(`displayBridge`/`data-enabled`) 제거 금지 (DV04 가드) |
| `assets/interview/*` | 로컬 디버그 화면 | PRIMARY_SURFACE 원칙 유지; 실패는 보조 증거로만 |

## 5. 미결 정책 (policy-conflict — 사용자 결정 필요)

1. `ExecutionTarget.LOCAL_ONLY`: 지시서 I3는 "어떤 상황에서도 로컬 진입 금지"이나
   라이브는 사용자 명시 옵션. 권장안: 자동/폴백/스필오버 경로는 로컬 금지로 고정하고
   `LOCAL_ONLY`는 "명시적 소유자 선택 + TTFT 경고"로 유지할지, enum에서 제거할지 결정.
2. Display Quick 모드의 벡터 스토어 캐시 질의: (권장) 기 연산된 메모리 캐시 읽기만 허용,
   신규 실시간 웹 검색·원격 벡터 인덱싱 차단.
3. Display 프롬프트 빌더 물리 분리: (권장) 현 단계는 `StandardPromptBuilder` 내
   `SurfaceOrigin` 가드 분기, 차기 리팩토링에서 `DisplayPromptBuilder`로 분리.

## 6. 검증 수단

- 오프라인 계약 프로브: `python -B scripts/probe_tri_system_isolation.py --dry-run --scan-sources`
  (불변 위반 시 exit 3, 불명확 시 2).
- 프론트엔드 격리: `node --test scripts/test_tri_system_frontend_isolation.cjs` (exit 0).
- 제안 Java 계약 테스트( Codex ): `src/test/java/com/example/lms/prompt/TriSystemPromptIsolationTest.java`
  — 명세는 `data/agent-handoff/for-codex/FOR_CODEX_TRI_SYSTEM_ISOLATION.md`.
- 물리 안경·실물 OAuth: `NOT_RUN_DEVICE`/`NOT_RUN_AUTH`.
