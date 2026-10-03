# SETTINGS_ROUTING_CONTRACT — 설정·역할별 모델 라우팅 계약 SSOT (Plan6)

Scaffold 기준일 2026-10-02. 이 문서는 `Plan6.txt`(v1→v3 통합)의 설정·라우팅
설계를 **실행 가능한 계약**으로 고정한다. 구현 주체(Devin)가 이 문서의 계약을
만족하도록 제품 소스를 수정하고, `scripts/check_settings_routing_invariants.py`
와 `SettingsRoutingContractTest`가 계약 위반을 감지한다.

- 상위 규약: `AGENTS.md`, `.windsurf/rules/demo1-hard-constraints.md`,
  `docs/API_ROUTING_SPEC.md`, `configs/api-routing.yaml` (모델 인벤토리 SSOT).
- 이 문서는 지시서 계약이지 구현 증거가 아니다. 각 계약의 충족 여부는
  구현 후 실제 테스트·관측 결과로만 판정한다 (`NOT_RUN` 표기 규칙 준수).
- 검증 스크립트: `python -B scripts/check_settings_routing_invariants.py --root .`
- 계약 테스트: `.\gradlew.bat :test --tests com.example.lms.service.routing.SettingsRoutingContractTest`
- 회귀 명세: `src/test/resources/fixtures/settings_routing_specs/settings_routing_96_specs.json`

## 0. 용어

| 용어 | 의미 |
|---|---|
| 등록 경로(registered route) | `LlmRouterProperties.models` / 라우팅 SSOT에 등록된 모델 후보 키 |
| 역할(role) | 호출자 입장에서 모델이 배정되는 6개 소비 지점 (§1) |
| 리비전(revision) | 저장된 라우팅 설정 스냅숏의 단조 증가 버전 (§3) |
| 명시 프로필 | 사용자가 저장한 역할→모델/대체 배정 집합 (미설정 시 기존 정책 유지) |

## 1. 여섯 역할 계약 (6-Role Contract)

사용자가 편집 가능한 역할은 정확히 6개이며, 각 역할은 **기존 소비자**에만
연결한다. 새 소비자를 만들거나 존재하지 않는 분기에 설정을 붙이지 않는다.

| 역할 키 | 의미 | 실제 소비자 (근거) |
|---|---|---|
| `MAIN_DEFAULT` | 기본 답변 분기에 배정된 등록 경로 | `PolicyBasedModelRouter.defaultModel` 분기, `main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java:44-100` |
| `MAIN_LIGHT` | 경량(fast) 분기 배정 경로 | `PolicyBasedModelRouter.fastModel` 분기, 같은 파일 `:50-52,:74-75` |
| `MAIN_HIGH` | 승격(high) 분기 배정 경로 | `PolicyBasedModelRouter.highModel` 분기 + `RouterPolicy` 승격 조건, 같은 파일 `:52,:76-77` |
| `SELF_ASK_BQ` | BQ 하위 질문 생성 모델 | `SelfAskPlanner.laneConfig(BQ)` → `SelfAskProperties.ThreeWay.getBq()`, `main/java/com/example/lms/service/rag/SelfAskPlanner.java:492-502` |
| `SELF_ASK_ER` | ER 하위 질문 생성 모델 | 같은 파일 `:499-501` (`getEr()`) |
| `SELF_ASK_RC` | RC 하위 질문 생성 모델 + 허용 대체 경로 | 같은 파일 `:499-501` (`getRc()`) |

계약:

- 역할 배정은 해당 분기가 **실제로 선택됐을 때만** 적용된다. `MAIN_HIGH`를
  지정해도 모든 질문이 승격되지 않고, Self-Ask 역할을 지정해도 검색 OFF/RAG OFF
  를 무시하고 하위 질문이 생성되지 않는다.
- `strict` 모드 사용자 고정 모델은 프로필로 덮어쓰지 않는다
  (`PolicyBasedModelRouter` 기존 정확 선택 경로와 오류 계약 보존).
- `judge`/`coder`/`vision`처럼 등록은 있으나 이번 설정 범위 밖인 호출자는
  편집 대상이 아니라 **현재 등록 상태·자동 후보 여부·미배선 이유**를 보여주는
  읽기 전용 항목이다.

## 2. Preview 0-call 계약

`POST /api/settings/routing/preview`(명칭 초안)는 **실제 모델 호출 0회**다.

- `ChatModel`/`ChatClient` 생성·호출 0회, warmup 0회, OAuth 갱신 0회.
- bandit(`LlmRouterBandit`) 통계·탐색 상태 변경 0회.
- 반환값은 카탈로그·역할 매핑·검증 결과만이다. 미리보기가 provider 네트워크
  요청을 발생시키면 계약 위반이다.

## 3. 리비전 격리 계약

- 라우팅 설정은 새 테이블 없이 기존 `ConfigurationSetting` 1행(JSON 직렬화)에
  `revision`과 함께 저장한다.
- 실행 시작 시점에 그 실행의 `ChatRunRegistry.Run`에 리비전을 고정한다
  (근거: `main/java/com/example/lms/service/chat/ChatRunRegistry.java`).
  Context 재생성·재연결에도 같은 Run은 저장된 리비전을 끝까지 사용한다.
  **진행 중 실행의 모델을 저장 직후 바꾸지 않는다.**
- 동시 저장: 같은 base 리비전을 둘 이상이 수정하면 첫 저장만 성공하고 나머지는
  `409`(리비전 충돌)을 반환한다. 저장 응답 유실 시 재조회한 리비전·해시로만
  판정하고 무조건 재저장하지 않는다.
- 브라우저 초안은 기존 계약대로 `sessionStorage`의 `chat.recoveryDraft`만
  사용한다(`chat.js:545,7392-7397`). `localStorage` 전환·병행은 계약 위반.

## 4. Fallback 폐쇄 계약

현재 `LlmRouterAspect.nextEligibleSelection()`는 설정된 fallback 체인 뒤에
`props.getModels().keySet()` **전체를 정렬해 후보에 추가**한다
(`main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java:725`). 이 경로는
명시 프로필의 "허용 대체 목록"을 무력화하는 우회다.

계약 (명시 프로필이 활성화된 실행에 한해):

- 일반 장애 대체·장치 대체·로컬 슬롯 포화·로컬 후보 재선택·RC 로컬 대체를
  포함한 **모든 대체 경로는 허용 목록과 추가 호출 상한을 공유**한다.
- 서로 다른 경로 키가 같은 endpoint/model을 가리키면 같은 실패 대상을 후보마다
  반복 호출하지 않는다 (endpoint dedup).
- 허용 목록+상한 초과 시 전체 등록 모델 임의 순회는 차단한다.
- 프로필 미설정 실행은 기존 정책(현재 `:725` 동작 포함)을 그대로 유지한다 —
  새 상한을 기존 실행에 소급 적용하지 않는다.
- `CallArgs.parse()`가 5·7·8 인자만 해석하는 현재 계약
  (`LlmRouterAspect.java:2342-2389`) 위에 역할 인자를 추가할 때는 기존
  오버로드를 보존하고 새 인자 해석을 별도 추가한다. 인자 개수가 바뀐 채
  parse가 `null`을 반환하면 AOP 라우팅 자체가 무력화된다.

## 5. 관측 5단계 분리 계약

"사용 모델" 한 줄 표기를 금지하고 다섯 단계를 분리한다.

```text
사용자 선택 → 정책이 고른 등록 경로 → 실제 호출 시도(모델·엔드포인트)
→ 공급자 응답에서 관측된 모델 → 최종 답변에 채택된 결과
```

- 응답에서 모델을 관측하지 못하면 `null/미확인`으로 표시한다. 성공 정보가 없을
  때 라우터가 들고 있는 이름을 "실제 사용 모델"로 표시하지 않는다
  (현재 `modelUsed`는 항상 공급자 확인값이 아님 — `ChatWorkflow` 근거).
- 마지막 성공 BQ 모델을 최종 답변 모델로 표시하거나, 캐시 답변에 이번 실행의
  생성 모델을 붙이는 것은 위반이다.
- bandit `weight`(예: 0.45/0.55)를 호출 비율 %로 표시하지 않는다.
- 응답 성공과 기억/학습 저장 성공은 별개 상태다. 저장 실패를 복구한다는 이유로
  답변을 재생성하지 않는다.

## 6. 보안 경계 계약

- `SYSTEM_PROMPT`는 비공개다. `SettingsService.KEY_SYSTEM_PROMPT`는 존재하지만
  `SettingsController.PUBLIC_SETTING_KEYS`(`SettingsController.java:26-34`)에
  넣지 않으며, `/api/settings` GET/POST 양쪽에서 노출·저장되지 않는다.
  허용 목록 외 키는 기존처럼 400으로 거부된다 (`:68-75`).
- `OPENAI_MODEL` 등 민감 키는 `SettingsControllerSecretMaskAspect.isSensitiveKey`
  (`SettingsControllerSecretMaskAspect.java:119-144`, `openai` 포함 `:142`)의
  마스킹/저장 제거 대상이므로 HTTP 200이 저장을 보증하지 않는다.
  `nova.security.settings.allowSecretUpdate`를 우회 활성화하지 않는다.
- Ray-Ban Focus 설정 읽기는 **POST `/api/assist/display/focus/settings/read`**
  이며 `focusBinding`의 기기 소유권·epoch·RUNNING 검증을 거친다
  (`DisplayConversateController.java:112-116`). 소유권 검증 없는 일반 GET을
  만들지 않는다.
- `AppSecurityConfig`의 permitAll 범위를 임의로 넓히지 않는다. 라우팅 설정
  API는 기존 POST 관리자 보호 아래 둔다. `PROTO_OPEN`에서는 AdminTokenGuard가
  ROLE_ADMIN을 부여하므로 "비관리자 거부" 검증은 일반 모드에서만 유효하다.

## 7. 변경 경계 (이 구현이 건드리지 않는 것)

`chat.js` 0줄 변경 · DB 스키마 0 · 보안 설정 완화 0 · 기존 API 계약 변경 0 ·
`chat-ui.html`은 설정 링크+브리지 로드의 10줄 이내 diff만 허용 · Jev 분류기는
`RetrieverChainConfig`의 기존 `JevRetrievalGateHandler.wrapIfEnabled`(고정/동적
체인 각 1회)를 재사용하며 신규 래핑을 추가하지 않는다.
