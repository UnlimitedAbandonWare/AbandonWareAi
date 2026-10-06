# FOR_CODEX — Dynamic Router (Auto 난이도 분기) 스펙 핸드오프

- 작성: 2026-10-06 KST, Devin (frontend-fast-ui-d0d442f6)
- 근거 지시서: PASTE_DEVIN_FRONTEND_FAST_UI_AND_CODEX_HANDOFF_20261006
- 성격: **스펙 정리 문서**. Devin은 `main/java` 백엔드 소스를 일절 수정하지 않았다 (제품 Java diff = 0). 구현은 Codex dot 전담.

## 1. 목표 (지시서 원문 요약)

`Auto` 난이도 분기 엔진:

| 입력 유형 | 분기 | 기대 동작 |
|---|---|---|
| 인사말·잡담 (예: `안녕`) | LIGHT | 경량 모델로 ~1초 컷 응답. 검색/RAG 생략(이미 부분 구현됨) + **경량 모델 lane 고정**이 이번 요구사항의 핵심. |
| 미학습·고난이도 질의 (예: `원신 산드로네` 같은 신규/니치 엔티티) | FULL | 웹서치 **강제 ON** + 풀스윙 고성능 모델. 로컬 parametric 추측으로 땜빵 금지. |
| 그 외 일반 질의 | 기존 AUTO 유지 | 회귀 없음. |

## 2. 라이브 트리 사실 (2026-10-06 확인 — `사실` 표기)

- `main/java/com/example/lms/service/ChatWorkflow.java`
  - `:3663` 순수 인사 정규식 → `features.add("greeting")` 이미 존재.
  - `:1481`, `:1900` 인사·일상 대화는 검색/RAG·공개 게이트 생략 로직 이미 존재.
- `main/java/com/example/lms/prompt/pose/ModelLoadoutResolver.java` `:110-126` — `greeting`/`nonfactual` feature가 모델 로드아웃 티어 선택에 이미 반영됨(직접 경로 존재).
- `main/java/com/example/lms/service/NoEvidenceChatFallback.java` `:674` — "순수 인사" 판정 헬퍼 존재(인사+실질 질의 혼합은 인사로 보지 않음).
- `main/java/com/example/lms/service/routing/RouterPolicy.java`
  - `:41 complexMainRequest(query, intent)` — codeAnalysis 또는 COMPLEX → score `.70`, 아니면 `.40`, 임계 `router.moe.complexity-threshold=0.55`.
  - 임계 프로퍼티: `router.moe.tokens-threshold:280`, `router.moe.uncertainty-threshold:0.35`, `router.moe.web-evidence-threshold:0.55`, `router.moe.escalate-on-rigid-temp:true`.
- `main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java` `:279` — `!apiFirstEnabled && !toolsRequired` 일 때 `policy.complexMainRequest(query,intent)` 결과로 oauth 승격 lane 결정.
- `main/java/com/example/lms/llm/ChatGptOAuthRegistration.java` `:27` — `PROVIDER = "chatgpt_oauth"`. OAuth 주력 카탈로그 provider 문자열. FULL lane의 "고성능 모델" 선택은 이 provider 라인의 selectable id 안에서 해결하는 것이 크레딧 정책과 일치한다(로컬 Ollama 풀스윙 금지 방향).
- `main/java/com/example/lms/service/rag/QueryComplexityGate.java` — Level `SIMPLE/AMBIGUOUS/COMPLEX`; `QueryComplexityClassifier`(선택 주입) + 규칙 기반 폴백. `ModelBasedQueryComplexityClassifier` 존재.
- `main/java/com/example/lms/service/rag/handler/JevRetrievalGateHandler.java` `:166-167` — COMPLEX→selfAsk, SIMPLE→analyze off 힌트 연결 지점.

**이름 충돌 주의**: `com.example.rag.qc.QueryComplexityGate`(`main/java/com/example/rag/qc/`)는 rerank 스코어러(`shouldRerank(Features)`)로 채팅 라우팅 게이트가 **아님**. 분기 스펙은 `com.example.lms.service.rag.QueryComplexityGate` 기준.

## 3. 제안 구현 형태 (Codex 재량, 최소 침습)

1. **LIGHT lane**: `ChatWorkflow`에서 `greeting` feature (또는 `NoEvidenceChatFallback` 순수인사 판정) 발생 시 — 모델 선택을 경량 tier로 고정하고 웹서치·RAG 파이프라인 진입 전 조기 반환. 이미 있는 greeting feature를 재사용; `ModelLoadoutResolver`의 greeting 분기와 정합성 맞출 것.
   - 시간 예산: 첫 유효 delta까지 ~1s 목표(측정 기준은 SSE 첫 텍스트 이벤트). 하드 타임아웃이 아니라 경량 라우팅으로 달성.
2. **FULL lane**: `QueryComplexityGate.assess()==COMPLEX` 또는 미학습 엔티티 추정(사전/카탈로그·최근 검색어 히트 0 등 — 기존 신호 재사용) 시 `webSearch` 힌트 강제 ON + 고티어 모델 라우트. `PolicyBasedModelRouter.complexMainRequest` 분기 지점이 자연스러운 훅.
3. **AUTO 유지 구간**: AMBIGUOUS/일반 → 현행 동작 그대로.
4. 설정 토글: `chat.dynamicRouter.*`(예: `enabled`, `lightModel`, `fullModel`, `forceWebSearchOnComplex`) — 프로퍼티 네이밍은 `configs/api-routing.yaml`/`application.yml` SSOT에 맞춰 Codex가 확정. 기본값은 **현행 동작 보존 방향**.

## 4. 금지·주의

- `remote_selection_disabled` 등 가용성/권한 모델을 우회하지 않는다 — lane 선택은 selectable 카탈로그 안에서만.
- `modelSelectionMode` strict/auto 의미 유지 (사용자 명시 선택 우선).
- PROTO_OPEN·인증 게이트 변경 금지.
- 검증에 실제 유료 API 호출 금지 — mock/픽스처·loopback만.
- 인사 뒤 실질 질의 혼합(`안녕, 오늘 주가 어때?`)은 LIGHT가 아니라 일반/FULL 경로 — `NoEvidenceChatFallback` 판정 재사용.
- `com.example.rag.qc.QueryComplexityGate`와 혼동 금지 (§2).

## 5. Codex 대상 파일 (지시서 명시 + 실측 보강)

- `main/java/com/example/lms/service/ChatWorkflow.java` — greeting feature 재사용 + LIGHT 조기 lane.
- `main/java/com/example/lms/service/routing/RouterPolicy.java` — `complexMainRequest`/신규 분기 신호.
- `main/java/com/example/lms/service/rag/QueryComplexityGate.java` — Level 확장 또는 신호 노출.
- `main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java` — oauth/full lane 결정점 (지시서 외 추가 후보, `추정`).
- `main/java/com/example/lms/prompt/pose/ModelLoadoutResolver.java` — greeting/난이도 ↔ tier 매핑 참조.
- 테스트: `src/test/java/...` 대응 스위트 + `src/test/js` 영향 없음.

## 6. Acceptance 제안 (Codex 판정용)

- `안녕` → retrieval/검색 trace 없이 LIGHT 경로, 첫 delta 목표 ≤1s(측정치 보고).
- `원신 산드로네` 유형 질의 → web.search 실행 + 고티어 라우트 관측(trace 필드 증거).
- 인사+질의 혼합 입력은 LIGHT로 빠지지 않음(회귀 케이스).
- focused 테스트 GREEN, `:test` 전체는 기존 정책대로.