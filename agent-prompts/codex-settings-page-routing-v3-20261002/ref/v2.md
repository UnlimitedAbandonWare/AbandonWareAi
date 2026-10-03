| 입력 | 확인 상태 | 기준 시점·용도 |
|---|---|---|
| `FIND_X.zip` | 일부: 전체 목록과 관련 경로 검색, 라우팅·설정·권한 관련 48개 파일 선별 열람 | SHA-256 `d94c087f8d89eb2a8f14206c273b49ca82dbe3178cae464e483df8ecb39143ea`; HEAD=null |
| 이전 `FIND_X_settings_instructions_ko.md` | 일부: 설계·제약·보류 사항 확인 | 이전에 제공한 코드 초안이며 현재 적용 증거가 아님 |
| 이전 `FIND_X_settings_draft_package.zip` | 일부: 파일 목록과 연결 초안 확인 | 재사용할 초안. 운영 소스에 이미 들어 있다고 가정하지 않음 |
| `UAW.txt`, `Abandon_X.txt` | 열림: 제공된 참고 내용 확인 | 기능 설명·이전 감사 기록. 현재 라우팅 동작의 증거로 사용하지 않음 |
| 프로젝트 추가 지침·AGENTS 삽입문 | 열림 | 현재 소스·실제 실행·과거 보고의 증거 등급을 분리 |

# AbandonWare AI 설정·모델 라우팅 정교화 수정 지시서 v2 [초안]

작성 기준: 2026-10-01, Asia/Seoul. 대상: 제공된 `FIND_X.zip`의 `main/` 소스 오버레이. 구현 담당: 로컬 Codex 또는 같은 작업 계약을 따르는 코딩 에이전트.

## 결론

설정 페이지를 단순한 기본값 보관함이 아니라 **“어느 역할에 어떤 모델을 배정하고, 실패하면 어디까지 대체하며, 이번 답변은 실제로 누가 생성했는지 확인하는 화면”**으로 확장한다.

가장 먼저 완성할 연결은 다음과 같다.

**설정 카드 → 검증된 등록 경로 → 실행 시작 시 고정한 정책 → 기존 모델 선택기·Self-Ask 소비자 → 실제 호출·응답 검증 기록 → 설정 화면의 적용 확인.**

기존 `PolicyBasedModelRouter`, `LlmRouterAspect`, `LlmRouterBandit`, `RequestedModelSelection`, `LlmRouteDecision`, `SelfAskPlanner`를 재사용한다. 별도의 범용 라우터나 워크플로 엔진을 만들지 않는다. 기존 화면의 네 컨트롤 연결은 그대로 유지하며, 역할별 서버 설정은 그 브리지에 억지로 싣지 않는다.

이번 지시서의 핵심 산출은 실제 배선이 가능한 역할 편집, 대체 경로의 경계, 저장과 적용의 구분, 부작용 없는 미리보기, 응답 모델의 관측 계약이다. 새로운 라우팅 프로필은 기본 비활성이며, 활성화하지 않은 기존 실행은 변경하지 않는다.

## 한계와 증거 등급

이 ZIP에는 일반 파일 2,394개가 있으며, 모든 파일의 모든 메서드를 개별 의미 분석한 것은 아니다. 이번 추가 조사는 설정과 모델 라우팅의 관련 경로에 한정했다. ZIP 멤버의 가장 늦은 수정 시각은 `2026-10-01 21:43:20`이나 ZIP에는 시간대가 없으므로 커밋 시각이나 운영 반영 시각으로 사용하지 않는다.

실제 빌드 루트, Gradle Wrapper, sourceSet, 의존성 잠금, 기존 테스트 트리, 배포 HEAD는 이 ZIP에서 확인하지 못했다. Java 17·LangChain4j 1.0.1 유지가 요구 조건이며, Spring Boot 3.3.4·Gradle 8.7은 사용자 제공 환경 정보다. 정확한 빌드 버전은 로컬에서 확인한다. 임의 빌드 파일을 만들어 성공을 연출하지 않는다.

`docs/API_ROUTING_SPEC.md §6`, 현재 저장소의 INV/DONE/HOLD, 실제 운영 설정과 정제된 실행 기록은 추가 증거다. 아래 신규 경로·JSON 키·이유 코드는 **명칭 초안**이며, 해당 문서가 로컬에 존재하면 §6 명칭으로 대응시킨 후 구현한다. 기존 프로퍼티·환경변수 이름은 변경하지 않는다.

증거 표기:
- `직접·정적`: 현재 ZIP의 실제 코드·구성·호출 참조를 열람했다. 빈의 운영 활성화나 API 성공까지 증명하지 않는다.
- `이전 보고`: 이전 설정 초안의 테스트·분석 결과다. 이번 검증 결과로 재사용하지 않는다.
- `설계 제안`: 이번 요청을 위해 새로 정의한 동작이다. 구현 완료가 아니다.
- `근거 부족 / NOT_RUN`: 확인하지 못한 실행·빌드·정책 항목이다.

이 문서의 `E01`부터 `E52`까지는 `evidence/source-anchors.json`의 정확한 ZIP 경로·행·파일 해시를 가리킨다. 원본 소스는 새 배포 ZIP에 넣지 않는다. 외부 웹 자료로 소스의 빈칸을 보충하지 않았으며, 소스 속 모델 이름은 현재 공급자의 실제 제공 여부나 사용 권한을 보증하지 않는다.

---

## 1. 먼저 보존할 결정과 이번 변경의 경계

상태의 의미를 혼동하지 않는다. `DONE`은 명시된 좁은 기존 계약이 확인됐다는 뜻이며 신규 설정 페이지의 구현 완료를 뜻하지 않는다. `PARTIAL`은 기존 구성 요소는 있으나 이번 설정 연결이 더 필요한 상태다. `NEW`는 의도적으로 추가할 기능이다. `CONFLICT`는 기존 요구 또는 확인된 계약과 충돌해 자동 적용하면 안 되는 제안이다.

| 항목 | 판정 | 구현 지시 |
|---|---|---|
| `/chat` 중심, 별도 `/settings` | PARTIAL | 이전 페이지 초안을 유지·보강한다. 모달이나 스튜디오 기반으로 다시 만들지 않는다. |
| `chat.js` 변경 금지 | DONE·불변 | 읽기만 한다. fetch·EventSource·XHR·전송 함수를 교체하거나 가로채지 않는다. |
| 네 DOM 컨트롤을 통한 브리지 | PARTIAL | 브라우저 기본값에만 사용한다. 임의 role 데이터는 기존 요청에 몰래 추가하지 않는다. |
| 명시 모델 정확 선택 | DONE·정적 | 기존 정확 선택·owner 검증·오류 계약을 그대로 사용한다. E01·E03·E08·E11. |
| 역할별 배정 편집 | NEW | 실제 소비자가 확인된 기본·경량·고성능 및 BQ/ER/RC를 연결한다. |
| 대체 후보의 명시적 제한 | NEW·기존 동작 변경 | 새 프로필이 활성화된 역할에 한해서만 기존 전체 후보 확장을 제한한다. |
| 실행마다 정책 리비전 고정 | NEW | 기존 Run에 저장한다. 새 전역 세션 관리기를 만들지 않는다. |
| 상세 모델 호출 관측 | PARTIAL | 기존 메타데이터를 우선 사용하고 부족한 증거만 실제 호출 경계에서 보충한다. |
| 일반 사용자도 전체 서버 라우팅 편집 | CONFLICT | 허용하지 않는다. 브라우저 선호와 관리자 서버 정책을 구분한다. |
| 기존 settings GET 성공=관리자 | CONFLICT | 현재 코드와 다르다. 권한 판정으로 사용하지 않는다. E30~E34. |
| 일반 인증 구성에서 `/settings` 익명 공개 | CONFLICT | 현재 공개 목록에 없다. 보안 diff 없이 별도 승인 사항으로 남긴다. E33. |
| 모델 역할을 선택하면 관련 기능도 자동 활성화 | CONFLICT | RAG·Self-Ask·Jev·Display의 기존 활성화 조건을 보존한다. |
| 시스템 프롬프트·서버 모델을 범용 API에 임의 추가 | CONFLICT | 이전 초안의 잠금 처리를 유지한다. 기존 허용 목록을 넓히지 않는다. |
| DB 테이블·컬럼·인증 체계·API 키 입력 | DONE·불변 | 변경 0. 키·토큰·임의 base URL 입력란을 만들지 않는다. |

이전 초안과 달라지는 사용자 경험은 명시한다. 브라우저 기본값은 즉시 자동 저장한다. 여러 역할에 영향을 주는 서버 라우팅은 **편집 → 미리보기 → “다음 실행부터 적용”**으로 변경한다. 이는 서버 전체 정책을 불완전한 중간값으로 적용하지 않기 위한 **새 라우팅 블록의 설계 제안**이며, 기존 `/api/settings` 동작을 바꾸는 것이 아니다.

---

## 2. 현재 소스에서 확인한 라우팅 구조와 오해하기 쉬운 지점

### 2.1 세 종류의 선택을 하나로 섞지 말 것

1. **화면의 모델 선택 방식**: `preferred / strict / auto`와 `strictModelSelection`.
2. **기본·경량·고성능 선택**: `PolicyBasedModelRouter`와 `RouterPolicy`가 담당하는 tier 배정.
3. **등록된 공급 경로 선택·장애 대체**: `llmrouter.*`, `LlmRouterBandit`, `LlmRouterAspect`가 담당하는 endpoint/model 경로.

여기에 Self-Ask의 BQ/ER/RC별 배정과 OAuth 메인 선택이 별도로 존재한다. “모델 하나 고르기”만으로 이 모든 설정을 바꿨다고 표시하면 안 된다.

### 2.2 현재 흐름의 근거

| 근거 | 실제 코드 위치: `FIND_X.zip!` 뒤의 경로 | 확인한 현재 동작 | 수정에 주는 의미 |
|---|---|---|---|
| E01 | `main/resources/static/js/chat.js:6622–6627` | auto는 `llmrouter.auto`를 전송한다. | auto를 빈 문자열이나 첫 모델로 바꾸지 않는다. |
| E02 | `main/java/com/example/lms/api/ChatRequestSettingsMerger.java:27–44` | 요청 모델이 있으면 우선하고, 없으면 서버 기본값을 사용한다. | UI·서버 기본값을 별도 범위로 표시한다. |
| E03 | `main/java/com/example/lms/service/ChatWorkflow.java:1348–1372` | 정확 선택은 검증된 owner와 함께 확인한다. | 브라우저 저장값은 사용 권한이 아니다. |
| E04 | 같은 `ChatWorkflow.java:2979–2990` | 메인 결정을 예산 투영 전에 고정한다. | 설정 스냅숏은 그보다 앞서 고정되어야 한다. |
| E05·E06 | `main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java:44–100,243–270` | 실제 Primary 구현이며 정확 선택 → 복잡 요청 OAuth → 일반 라우팅 순이다. | UI 설명과 적용 우선순위를 이 분기에 맞춘다. |
| E13·E15 | `main/java/com/example/lms/config/SelfAskProperties.java:18–61`; `main/java/com/example/lms/service/rag/SelfAskPlanner.java:457–510` | BQ/ER/RC의 model/provider 설정을 실제 소비한다. | 역할별 편집의 가장 구체적인 연결 지점이다. |
| E18 | `main/java/ai/abandonware/nova/orch/router/LlmRouterBandit.java:242–281` | 비용 tier를 먼저 좁히고 UCB 보상·탐색을 사용한다. weight는 작은 prior다. | weight를 “호출 비율 45%/55%”로 표시하지 않는다. |
| E21 | `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java:714–747` | fallbackKey 체인 다음에 전체 등록 키를 더한다. | 새 후보 제한이 이 분기까지 도달해야 한다. |
| E25 | `main/java/com/example/lms/service/ChatModelCatalogService.java:18–75` | Choice의 id/provider/endpointId/modelId/status가 분리돼 있다. | 논리 경로와 실제 모델 ID를 같은 필드로 뭉치지 않는다. |
| E36 | `main/java/com/example/lms/service/chat/ChatRunRegistry.java:1215–1235` | 같은 Run에서 Context 객체가 재생성된다. | Context 인스턴스에만 리비전을 넣으면 재연결 시 사라진다. |
| E39 | `main/java/com/example/lms/service/ChatWorkflow.java:4308–4325` | modelUsed는 성공 모델 또는 라우터 이름에서 유도될 수 있다. | 이 문자열만으로 공급자가 확인한 실제 모델이라고 단정하지 않는다. |
| E49 | `main/java/ai/abandonware/nova/orch/router/LlmRouterContext.java:1–30` | 마지막 경로를 ThreadLocal에 보관한다. | 비동기 다중 역할의 최종 실행 원장으로 그대로 쓰면 안 된다. |

### 2.3 이름이 비슷해도 새 편집기의 연결 대상이 아닌 것

`ModelRouterCore`를 이름만 보고 실제 Primary 라우터라고 가정하지 않는다. 현재 `PolicyBasedModelRouter`의 등록을 먼저 따른다. `ModelRouterAdapter`의 설명 주석은 실제 클래스·빈 등록과 교차검증한다.

`service/routing/plan/RoutingPlanService`는 검색 질의 계획을 생성한다. 모델 배정 미리보기의 엔진이 아니다. 기존 서비스를 재사용한다는 이유로 여기에 모델 설정을 덧붙이지 않는다. E44.

`ApiRoutingPolicySnapshot`은 구성 문서를 읽은 스냅숏이다. DB에 문자열을 저장한다고 자동 갱신되는 실시간 정책 저장소가 아니다. `@Value` 필드와 이미 생성된 모델 빈도 별도 갱신 계약 없이 바뀌지 않는다. E05·E07·E29·E43.

---

## 3. 설정 인벤토리 확장: 무엇을 실제로 조절하게 만들 것인가

### 3.1 첫 배포에서 연결할 설정

아래 여섯 서버 역할 이름은 명칭 초안이다. 로컬 API_ROUTING_SPEC §6과 충돌하면 의미를 보존하고 명칭만 대응시킨다. 새 enum이 생겼다는 이유로 기존 enum·프로퍼티를 이름 변경하지 않는다.

| 설정 | 현재 근거·소비자 | 저장·범위 | 새 화면 | 판정 |
|---|---|---|---|---|
| 기본 모델 | 네 컨트롤, E01·E02 | 기존 브라우저 설정 / 이 브라우저 | 현재 카탈로그의 Choice.id 선택 | PARTIAL |
| 모델 선택 방식 | `preferred/strict/auto`, E01 | 동일 | “선택 모델 우선 / 선택 모델 고정 / 자동 선택” 설명 | PARTIAL |
| MAIN_DEFAULT | `PolicyBasedModelRouter.route(RouteSignal)`, `llm.chat-model`, E05 | 새 typed 프로필 / 서버 전체 | 상속 또는 등록 경로 선택 | NEW |
| MAIN_FAST | 같은 라우터의 경량 분기, `llm.fast.model`, E05 | 동일 | 상속 또는 등록 경로 선택 | NEW |
| MAIN_HIGH | 같은 라우터의 승격 분기, `llm.high.model`, E05·E09·E10 | 동일 | 상속 또는 등록 경로 선택 | NEW |
| SELFASK_BQ | `SelfAskPlanner.modelIdForLane`, E13·E15 | 동일 | BQ의 실제 배정 경로 편집 | NEW |
| SELFASK_ER | 같은 소비자의 ER 분기 | 동일 | ER의 실제 배정 경로 편집 | NEW |
| SELFASK_RC | 같은 소비자의 RC 분기 및 localCounterFallback, E14·E15 | 동일 | RC 배정과 허용 대체 후보 편집 | NEW |
| 역할별 대체 목록 | fallbackKey/deviceFallbackKey 및 대체 분기, E16·E20·E21 | 동일 | 명시적 순서 또는 상속 | NEW |
| 역할별 추가 대체 호출 상한 | 기존 실행 예산과 새 제한의 교집합 | 동일 | 새 override 역할은 기본 0 | NEW |
| 변경 적용 상태 | 기존에는 DB 저장과 실행 반영이 별개 | 서버 리비전 + Run 스냅숏 | 저장됨/다음 실행 대기/실행에서 확인 | NEW |
| 실제 사용 모델 확인 | E23·E38·E39·E40 | 기존 실행 기록 / 기존 접근 권한 | 선택·호출·응답 검증을 따로 표시 | PARTIAL |

브라우저의 기본 모델은 서버 tier 슬롯을 교체하지 않는다. 서버 MAIN_HIGH를 바꾸는 것은 허용된 승격 분기에서 사용할 경로를 바꾸는 것이며, 모든 질문을 고성능 모델로 강제하는 설정이 아니다.

### 3.2 설정 표시를 정교하게 하되 소비자 없는 스위치를 만들지 않을 항목

| 항목 | 현재 근거 | 이번 처리 |
|---|---|---|
| judge/coder/vision 역할 | `application-llm.yaml:580–605`에 등록·weight=0 존재, E48 | 등록 상태와 자동 후보 여부를 분리한 읽기 정보. 해당 호출자의 종단 배선 확인 없이 새 편집 슬롯을 활성화하지 않는다. |
| 빠름·균형·품질 선택 | RouterPolicy에 관련 조건 존재, E09·E10 | 분기 설명과 미리보기 제공. weight나 복잡도 임계치를 임의의 품질 퍼센트 슬라이더로 포장하지 않는다. |
| temperature/top_p/output/timeout | 기존 설정 및 factory/예산 계약 | 각 역할에 “현재 값·출처·상속”을 보여준다. 새 역할 편집기 첫 배포는 배정·대체 경계에 집중하며, 매개변수 쓰기는 기존 지원 블록 외에 확대하지 않는다. |
| 추론 강도·도구·이미지 입력 | 경로별 지원·검증 메타데이터 필요 | 지원/미지원/미확인을 표시한다. 모든 모델에 동일한 파라미터를 보내지 않는다. |
| OAuth 플랜 사용 경로 | ChatGptOAuthRegistration, E41·E42 | 현재 owner에게 허용된 카탈로그 항목만 기존 모델 선택에 노출. 서버 전역 역할에 개인 OAuth 항목을 저장하지 않는다. |
| Jev | 후보 선별 신호와 동의·예산 제한, E46 | 후보 평가용임을 표시. 생성 모델과 합치지 않으며 새 프로필이 Jev를 켜지 않는다. |
| 임베딩 | 차원·인덱스 일관성 영향 | 일반 채팅 모델 편집에서 제외. 선택만으로 인덱스가 호환된다고 표시하지 않는다. |
| Ray-Ban/STT/힌트 생성 | 별도의 소유·연결·활성화 조건 | 기존 v1 잠금·숨김 결정을 유지. MAIN 역할 변경이 Display까지 바꾼다고 설명하지 않는다. |
| 모델 설치·다운로드·warmup | 별도 부수 효과 | 설정 조회·미리보기·카탈로그 실패 복구에서 실행 금지. |
| 원격 무료 여부·ZDR·잔액 | 공급 조건과 실제 관측 필요 | 구성 표시는 구성 표시일 뿐이다. 미확인 비용·잔액·보존 조건은 null/근거 부족. |

### 3.3 모델 ID는 다음 네 칸으로 나눌 것

- `choiceId`: 기존 카탈로그 선택 식별자. 브라우저 기본 모델은 이 값을 보존한다.
- `routeKey`: 서버가 등록한 논리 경로. 예를 들어 `llmrouter.gemma`.
- `configuredModelId`: 그 경로의 현재 서버 구성에 적힌 공급자 모델 ID.
- `responseModelId`: 실제 응답 메타데이터에서 관측한 모델 ID. 관측하지 못하면 null.

`endpointId`는 기존 서버가 정제한 식별자를 쓴다. endpoint URL 전체·query·credentialEnv 값·토큰을 표시하지 않는다. 동일 모델이 서로 다른 endpoint에 존재할 수 있으므로 모델 이름만으로 중복 제거하지 않는다. 반대로 서로 다른 routeKey가 동일 endpoint/model을 가리키면 대체 호출 중복을 줄일 수 있다. 현재 identity 계산 계약과 대조한다. E22.

---

## 4. 화면 설계: 사용자가 설정의 효과를 바로 이해하게 만들 것

### 4.1 페이지 구조

```text
설정                          [설정 검색] [채팅으로 돌아가기]

일반              모델·라우팅
모델·라우팅        이 브라우저 기본값: 저장됨
검색·RAG           서버 정책: r12 저장됨 / 새 실행부터 적용
답변·진단          현재 선택한 실행: r11 사용 / 완료
디스플레이
데이터·기록        [기본 모델] [역할별 배정] [대체 경로] [미리보기] [실행 결과]
서버 설정
                  기본 모델          [현재 카탈로그에서 선택]
                  선택 방식          [선택 모델 고정]
                  적용 범위          이 브라우저, 기존 대화 복원값은 유지

                  역할               상속 원본        새 배정          적용 상태
                  기본 답변          서버 기본         상속            상속
                  경량 처리          경량 경로         경로 선택        편집 중
                  고성능 처리        고성능 경로       경로 선택        편집 중
                  Self-Ask BQ        BQ 설정           경로 선택        기능 조건부
                  Self-Ask ER        ER 설정           상속            기능 조건부
                  Self-Ask RC        RC 설정           경로 선택        기능 조건부

                  [변경 미리보기] [다음 실행부터 적용] [편집 취소]
```

모바일에서는 카테고리를 상단 탐색으로 바꾸고, 역할 표는 역할당 카드로 전환한다. 행을 잘라 정보를 숨기지 않는다. 현재 `/chat`의 변수·폰트·간격을 재사용하고 스튜디오 CSS를 복사하지 않는다.

### 4.2 역할 카드 상세

각 카드에는 다음 정보가 함께 있어야 한다.

**역할 의미**: 언제 호출되는지, 켜져 있는 기능인지, 어느 기존 소비자가 읽는지.

**현재 유효값**: 상속 원본, 등록 경로, 구성된 모델, 공급 방식, 기능 지원 상태. 정적 구성인지 최근 관측인지 함께 표시한다.

**편집값**: 상속/직접 배정, 선택 경로, 허용 대체 목록, 추가 대체 호출 상한. 관리자 권한이 없으면 입력 대신 잠금 안내를 보여준다.

**변경 효과**: “다음 실행의 BQ에만 적용”, “현재 진행 중인 답변에는 영향 없음”, “Self-Ask가 건너뛰어지면 이 모델은 호출되지 않음”처럼 실제 범위를 설명한다.

**진단 링크**: 해당 역할의 최근 관측은 사용자가 명시적으로 선택하고 접근 권한이 확인된 실행에서만 보여준다. 서버의 마지막 요청을 전역으로 찾아 노출하지 않는다.

### 4.3 경로 선택기

검색 가능한 모델 목록을 만들되 정렬과 배지는 다음 의미를 따른다.

| 표시 | 의미 |
|---|---|
| 등록됨 | 서버 구성 또는 현재 카탈로그에 존재한다. 호출 성공 보증이 아니다. |
| 선택 가능 | 현재 선택 계약상 허용된 항목이다. 미래 호출 성공 보증은 아니다. |
| 자동 후보 | enabled·weight·fallbackOnly·기존 정책을 만족하는 자동 후보다. |
| 대체 전용 | primary 자동 후보와 다른 용도다. |
| 상태 미확인 | 최근 관측이 없거나 카탈로그 조회가 실패했다. 삭제된 모델로 취급하지 않는다. |
| 이번 역할에 부적합 | 요구되는 기능 또는 허용 범위와 맞지 않는다. 이유를 표시한다. |
| 현재 owner 전용 | OAuth 등의 사용자 귀속 항목. 서버 전역 저장은 불가하다. |

카탈로그 재확인은 기존 `/api/chat/models/recheck`를 통해 사용자가 지정한 한 항목에만 수행한다. 이 동작과 부작용 없는 정책 미리보기를 구분한다. 모든 후보를 동시에 재확인하지 않는다. E24~E26.

### 4.4 저장 상호작용

브라우저 기본값: 값 검증 → 해당 네임스페이스 저장 → 성공 표시. 저장소 접근이 막히면 현재 UI 값과 저장 실패를 구분한다. 실패했다고 채팅 모델을 다른 값으로 바꾸지 않는다.

서버 역할 정책: 편집 값은 메모리에 두고, 선택 항목만으로 만든 미리보기를 확인한 후 원자적으로 저장한다. 서버 초안·OAuth 항목·실행 기록 전체를 localStorage에 자동 기록하지 않는다. 승인되지 않은 페이지 이탈은 편집 손실 안내만 한다.

저장 실패 시: 이전 서버 유효값은 유지하되 사용자 편집값은 보존한다. “실패 후 되돌림”은 입력을 지워버리는 것이 아니라 **활성 정책을 바꾸지 않는 것**이다. 네트워크 시간 초과는 저장 실패 확정이 아니므로 재조회로 리비전과 해시를 비교한다. 무조건 저장 재시도를 보내지 않는다.

기본값으로: 문서에 적힌 과거 모델 문자열을 하드코딩하지 않는다. 역할 override를 제거해 **현재 서버 상속값**으로 돌린다. 서버 전체 일괄 초기화와 역할 하나 초기화를 구분한다.

---

## 5. 라우팅 우선순위를 실제 코드와 맞출 것

### 5.1 현재 메인 경로를 보존하는 적용 순서

```text
기존 요청 권한·기능·실행 예산 확인
  → 실행에 고정된 라우팅 정책 읽기
  → 요청이 strict인가?
      예: 기존 exact 선택 경로. 프로필로 다른 모델을 덮지 않음.
      아니오:
        기존 복잡 요청 OAuth 분기가 성립하는가?
          예: 기존 owner·도구·자격 조건을 따름.
          아니오:
            기존 RouterPolicy가 기본/경량/고성능을 결정
            → 해당 슬롯의 상속 또는 프로필 배정 적용
            → 기존 non-strict requestedModel 처리 계약 유지
  → 기존 메인 결정 객체 고정
  → 같은 모델 예산으로 문맥 준비 및 실행
```

`preferred`는 무조건 사용자가 고른 모델이 1순위라는 완전한 보장이 아니다. 현재 `routeMain`에는 비정확 선택보다 앞선 복잡 요청 OAuth 분기가 있다. 화면은 실제 우선순위를 설명해야 한다. 이 순서를 바꿔 “preferred가 OAuth보다 먼저”가 되게 하는 것은 **별도 기존 동작 변경**이며 이번 기본 패치에 포함하지 않는다. E06.

`auto` 역시 “항상 UCB만 사용”을 뜻하지 않는다. 이 값이 들어와도 메인 경로의 기존 분기를 먼저 통과한다. 최종 선택 이유를 사용자에게 보여줘야 하는 이유다.

### 5.2 역할 정책과 기능 활성화의 분리

Self-Ask가 실행되는 경우에만 BQ/ER/RC 배정을 적용한다. 검색 OFF, RAG OFF, 경량 인사 처리, 기존 단계 생략 판단을 새 프로필이 뒤집지 않는다.

BQ 모델을 바꾸면 `modelIdForLane`뿐 아니라 `providerForLane`, lane 재생성, RC의 로컬 대체 경로까지 같은 유효 배정을 사용해야 한다. model만 변경하고 provider 문자열을 이전 값으로 남기지 않는다. provider는 서버의 선택 경로에서 함께 도출한다. E14·E15.

새 프로필을 적용받지 않는 옛 `plan(String,int)` 등의 경로가 있으면 “BQ/ER/RC 전용 적용, 단일 플래너는 기존 상속”이라고 표시한다. 배선하지 않은 소비자까지 설정이 적용된다고 보고하지 않는다.

### 5.3 생성 모델과 분류·평가 모델의 분리

Jev는 이번 코드에서 후보 평가 신호다. 이를 MAIN_HIGH나 최종 답변 모델로 선택할 수 있게 하지 않는다. 반대로 높은 생성 성능을 가진 모델을 모든 경량 분류에 자동 배정하지 않는다.

judge/coder/vision 이름이 있다고 매 요청 세 모델이 실행되는 것이 아니다. 해당 소비자를 확인한 다음 확장한다. 고품질은 무조건 많은 모델 호출이나 병렬 호출로 정의하지 않는다.

---

## 6. 대체 경로: UI 목록 밖으로 실제 호출이 새지 않도록 만들 것

### 6.1 프로필이 없는 기존 실행

기존 후보 선택·fallbackKey·deviceFallbackKey·최저 비용 tier·쿨다운·오류 분류를 그대로 유지한다. 새 기능 기본값 0을 기존 시스템 전체의 호출 상한에 덮어쓰지 않는다.

### 6.2 명시 배정이 있는 역할

새 배정은 `primary + orderedFallbacks`로 표현한다. 대체 후보는 최대 3개라는 **새 편집기 구조 제한 초안**을 둔다. 실제 추가 호출 상한은 기본 0이며, 양수로 설정하더라도 기존 서버·실행 예산의 상한을 넘지 못한다.

대체 가능 집합은 다음의 교집합이다.

**사용자가 저장한 역할별 후보 ∩ 현재 서버의 등록·기능·권한 허용 ∩ owner·데이터 범위 ∩ 남은 실행·호출 예산.**

아래 규칙을 모두 적용한다.

- 주 후보가 실패했다고 모든 등록 경로를 다시 후보로 넣지 않는다. `nextEligibleSelection`의 전체 키 추가뿐 아니라 `runtimeDeviceFallbackSelection`, `deviceFallbackSelection`, `eligibleFallbackSelection`, `repickUncontendedLocalArm` 등 분기 끝에서 동일한 제한을 확인한다. E20·E21.
- 순서가 명시된 새 목록은 그 순서대로 적합성을 확인한다. 비용 정책이 허용하지 않는 항목은 이유를 남기고 건너뛴다. 자동으로 재정렬해 명시 순서를 바꾸지 않는다. 기존 무프로필 자동 선택의 tier 정책은 그대로다.
- routeKey 순환과 endpoint/model 중복을 구분한다. 동일 실패 대상 재시도를 서로 다른 후보 성공 가능성처럼 계산하지 않는다.
- 기능 요구, 관리형 파일 검색의 연결 범위, 이미지·도구 지원, 응답 모델 검증 요구를 낮추며 대체하지 않는다. E22·E23.
- strict 메인 선택에는 이 목록을 사용하지 않는다. 정확 선택의 기존 `model_unavailable` 계약을 유지한다.
- 전역 정책 맵의 `fallbackKey`를 요청 중 임시로 변경했다가 복원하는 방식은 금지한다. 동시 요청에 영향을 준다.
- 역할 실패의 도메인 대체와 gateway 내부 대체를 각각 별도 무료 기회로 계산하지 않는다. `SelfAskPlanner.localCounterFallback`까지 포함해 하나의 역할 호출 장부에서 추가 호출을 계산한다.

### 6.3 호출 장부와 멈춤 조건

`maxExtraFallbackCalls=0`은 새 명시 배정 역할에서 primary 이후 추가 대체 호출을 허용하지 않는 뜻이다. 무제한도 아니고, 상속된 전체 서비스의 기존 기본값을 0으로 만드는 뜻도 아니다.

취소, owner 범위 실패, 정책 철회, 호출 시작 여부 불명은 단순 모델 장애와 다르다. 취소 후 새 후보를 생성하지 않는다. SSE가 끊겼다는 이유로 `/sync`나 새 runId로 다시 생성하지 않는다. 기존 실행 조회·재연결과 취소·슬롯 반환 계약을 따른다.

새 대체 호출을 허용해도 유료 호출 권한이 생기는 것은 아니다. 추가 유료 경로 허용은 기본 false이고, 역할 설정은 기존 예산·권한을 제한할 수만 있다. OAuth 플랜과 API key 과금은 다른 출처로 표시하며, 잔액 미관측을 잔액 0으로 바꿔 기존 허용 경로를 막지 않는다. E41·E42.

---

## 7. 서버 저장 API와 DB: 새 테이블 없이 실제 소비되는 정책 만들기

### 7.1 기존 API만으로 가능한 것과 불가능한 것

기존 `/api/settings` 허용 목록에는 역할별 경로·대체 목록이 없다. JSON 문자열을 임의 키로 POST하면 현재 계약상 거부된다. `SettingsService`에 저장 메서드가 있다는 사실만으로 기존 공개 API가 그 키를 허용하는 것은 아니다. E27.

대안 비교:

| 방안 | 장점 | 한계·판정 |
|---|---|---|
| 기존 네 컨트롤과 기존 API만 사용 | 변경이 가장 작음 | 기본 모델 외 역할별 배정은 구현할 수 없음. 이번 추가 요구를 충족하지 못함. |
| 기존 보호 하위에 typed API + 기존 ConfigurationSetting 한 행 | 기존 DB·보안·범용 API를 보존하면서 실제 역할 편집 가능 | 신규 DTO·검증·리비전·소비자 연결 필요. **채택 제안.** |
| 브라우저에서 YAML 편집·hot reload | 설정 전부를 노출하기 쉬움 | 민감 값 노출·다른 설정 손상·이미 생성된 빈 불일치. 이번 범위에서 제외. |

### 7.2 GET 성공을 관리자 판별로 쓰지 않는 API 초안

현재 보안 구성은 settings GET을 permitAll, POST를 ADMIN으로 정의한다. Guard도 환경에 따라 미설정 토큰을 통과시킬 수 있다. 따라서 기존 GET 성공만으로 상세 서버 토폴로지를 공개하면 안 된다. E30~E32.

보안 파일을 바꾸지 않고 기존 ADMIN 경계를 사용하는 최소안은 아래 **단일 컨트롤러의 세 POST 동작**이다.

| 메서드·경로 초안 | 목적 | 부수 효과 |
|---|---|---|
| `POST /api/settings/routing/read` | 관리자 역할 정책·리비전·안전한 등록 경로 보기 | DB·메모리 조회만. 생성·probe·설치·활성화 없음. |
| `POST /api/settings/routing/preview` | 편집 정책의 정적 검증과 선택 시나리오 설명 | DB 저장·모델 호출·카운터 갱신 없음. |
| `POST /api/settings/routing/save` | expectedRevision을 확인하고 정책 한 건 저장 | 허용된 ConfigurationSetting 행만 변경. 모델 실행 없음. |

POST 조회를 쓰는 이유는 기존 POST ADMIN 경계를 그대로 상속하기 위해서다. 이 선택이 프로젝트 API 명명 규칙과 충돌하면 해당 규칙에 맞는 **이미 보호된 경로**를 우선한다. 새로운 permitAll·우회 인증·토큰 입력·query 토큰 전달로 해결하지 않는다.

현재 `/api/settings/**`에는 CSRF 예외도 있다. 이번 패치에서 추가하거나 제거하지 않는다. “새 토큰을 넣어야 한다”고 새 계약을 만들지 말고 현재 세션·Guard 처리를 사용한다. 이 항목의 보안 정책 변경이 필요하다면 별도 검토 사항이다. E34.

새 컨트롤러는 기존 범용 SettingsController만 겨냥한 비밀값 마스킹 Aspect가 자동 보호해줄 것이라고 가정하지 않는다. 입력·출력 모두 DTO 허용 목록으로 구성하며 임의 settings map, Environment 전체, 모델 구성 객체 전체를 직렬화하지 않는다.

### 7.3 저장 문서 초안

기존 `ConfigurationSetting`의 문자열 키와 LONGTEXT 값에 하나의 제한된 JSON 문서를 저장한다. 테이블·엔티티·컬럼·`@Version` 컬럼을 추가하지 않는다. E28.

제안 키는 `CHAT_ROUTING_PROFILE_V1`이다. 기존 키가 아니라 **새 소비자와 함께 추가하는 명칭 초안**이다. 실제 저장소의 §6 이름과 충돌하면 적용 전에 대응한다. 환경변수는 추가하지 않는다.

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "enabled": false,
  "bindings": {},
  "additionalPaidAllowed": false,
  "additionalCostCapUsd": 0
}
```

`bindings`의 키는 첫 배포 여섯 역할만 허용한다. 역할 항목의 편집 형식은 다음과 같다. 아래는 형식 예시이며 서버에서 발견되지 않은 경로를 추가하는 설치 명령이 아니다.

```json
{
  "role": "SELFASK_BQ",
  "selection": "registered-route",
  "target": "llmrouter.gemma",
  "orderedFallbacks": [],
  "maxExtraFallbackCalls": 0
}
```

역할이 없으면 상속이다. null을 임의 기본 모델로 바꾸지 않는다. 상속 상태에서는 대체 목록을 새로 강제하지 않는다. 명시 역할에서 빈 대체 목록은 추가 대체 없음이다. `enabled=false`면 저장 내용과 무관하게 새 배정은 소비하지 않는다. 비활성 프로필 저장은 기존 동작을 바꾸지 않는다.

고정 제한 초안: JSON UTF-8 최대 16KiB, 여섯 역할, 역할당 대체 최대 3개, 식별자 최대 256자. 이 제한은 모델의 컨텍스트 용량이나 공급자 제한이 아니라 새 설정 문서의 구조 제한이다. 알 수 없는 필드·중복 JSON 키·유효하지 않은 숫자·중복 역할·범위 밖 식별자는 400으로 거부한다. 기존 시스템의 임의 값을 자동 교정해서 저장하지 않는다.

역할 target은 서버의 등록 경로 집합에서 확인한다. 브라우저가 provider·endpoint·credentialEnv를 함께 보내도 사용하지 말고 거부한다. 이 프로필로 disabled 경로를 enable하거나 개인 OAuth 경로를 서버 전역에 편입하지 않는다.

### 7.4 리비전·동시 저장·재조회 계약

요청은 `expectedRevision`, `expectedProfileHash`, 변경할 문서를 포함한다. hash는 정규화된 비밀값 없는 정책 JSON의 무결성 비교용이며 접근 권한이 아니다.

- 행이 없으면 조회 결과는 상속 상태, revision=0이다. 조회 때문에 기본 행을 생성하지 않는다.
- 기존 행 갱신은 트랜잭션과 해당 ConfigurationSetting 행의 DB 잠금 아래 현재 revision/hash를 다시 확인한다.
- 다른 클라이언트가 먼저 저장했으면 409를 반환한다. 클라이언트가 새 revision을 받아 자동 덮어쓰지 않는다.
- 최초 생성 충돌은 기존 setting_key의 유일성으로 판단한다. 트랜잭션이 실패한 뒤 새 트랜잭션에서 재조회해 409로 정리한다. rollback-only 트랜잭션에서 계속 진행하지 않는다.
- 성공은 commit 이후 재조회한 revision/hash와 응답이 일치할 때 `SAVED`다. HTTP 200만으로 소비자 적용을 주장하지 않는다.
- 저장 응답이 유실됐으면 read로 원하는 hash가 이미 저장됐는지 확인한다. 같은 payload를 새 revision으로 다시 제출하지 않는다.
- 기존 SettingsService 캐시와 별개로 typed 정책은 명시적인 조회 경로를 갖는다. `getAllSettings()` 캐시에 기대어 실행 정책을 결정하지 않는다. 일반 설정 캐시와의 상호작용은 해당 테스트로 고정한다.

Java `synchronized` 하나로 여러 서버 인스턴스까지 원자성이 확보됐다고 설명하지 않는다. 로컬 H2 운영 구성과 동시 연결 조건을 확인하되 DB 스키마를 바꾸지 않는다.

### 7.5 “저장됨”과 “적용됨”의 정의

| 상태 | 필요한 증거 |
|---|---|
| 편집 중 | 브라우저 폼만 바뀜 |
| 검증됨 | 서버의 typed validator가 현재 등록 계약과 구조를 확인함 |
| 저장됨 rN | DB commit 및 재조회 revision/hash 일치 |
| 다음 실행 적용 대기 | 새 Run이 아직 rN을 사용한 기록이 없음 |
| 실행에 적용됨 rN | 특정 Run이 rN을 고정했고 해당 소비자가 읽은 기록 있음 |
| 해당 역할 생략 | Run은 rN이나 기능·분기 조건 때문에 역할이 실행되지 않음 |
| 실행 실패 | 배정은 적용됐으나 호출·응답·저장 등 특정 단계가 실패함 |

“모델 응답 성공”과 “대화 최종 저장 완료”도 서로 다른 결과다. 새 설정 적용 여부를 최종 답변 성공률 하나로 대체하지 않는다.

---

## 8. 실행 스냅숏: 저장 도중 모델이 바뀌지 않게 할 것

### 8.0 새 실행 경로의 기본 비활성 경계

신규 비밀값 없는 프로퍼티 `chat.settings.routing.enabled=false`를 runtime 연결 스위치의 명칭 초안으로 둔다. 기존 키를 바꾸지 않고 새 환경변수도 만들지 않는다. 실제 §6에 같은 역할의 기존 키가 있으면 그것을 재사용한다. 이 값은 새 `RoutingProfileResolver`와 실행 snapshot 바인더가 소비한다. 소비자가 없는 토글은 추가하지 않는다.

false이면 기존 채팅이 **새 프로필 DB를 조회하지도 않고** 원래 경로로 진행한다. 따라서 새 기능을 넣었다는 이유만으로 기존 실행에 새 DB 장애 의존성을 만들지 않는다. 설정 페이지는 runtimeEnabled=false를 표시하며 비활성 초안은 저장할 수 있지만 활성 프로필을 “적용됨”으로 수락하지 않는다.

runtime 연결을 로컬에서 명시적으로 활성화한 뒤에도 DB 프로필의 enabled=false는 상속 상태다. runtime 활성과 프로필 활성은 서로 다른 값이다. 실제 역할 제한이 전달되는 bean·AOP·소비자가 확인돼야 runtimeSupported=true라고 표시한다. 적용 에이전트는 이번 기본값을 자동으로 true로 바꾸지 않는다.

### 8.1 어디에 고정하는가

현재 `ChatRunRegistry.Run`에는 실행 식별자·commitLease·준비 문맥·최종 상태가 있다. `contextFor`는 같은 Run에 대해 새 `ChatRunExecutionContext`를 만들 수 있다. 따라서 정책은 Context 객체 하나가 아니라 **기존 Run에 귀속된 불변 스냅숏**이어야 한다. E35~E37.

새 `RunRoutingSnapshot`에는 profileRevision/profileHash, 서버 기본 정책 지문, 정제된 역할 배정, 적용 범위, 스키마 버전을 보관한다. 계정 토큰·endpoint URL·프롬프트를 넣지 않는다. 개인 OAuth 정보는 기존 owner 검증 경로에서 해결하며 글로벌 정책 스냅숏에 복사하지 않는다.

실행 시작 직후, 모델 예산·문맥 준비보다 먼저 DB 정책을 읽고 해당 Run에 한 번 결합한다. 기존 Run의 snapshot이 있으면 재조회하지 않는다. 동시 최초 결합은 기존 Run의 소유 lease와 gate 아래 한 값만 채택하며, DB I/O를 gate 내부에서 장시간 수행하지 않는다.

새 프로필 경로의 초기 구현은 **새 Run마다 committed 행을 한 번 읽는 방식**을 택한다. 전역 hot reload·분산 캐시 동기화 계층을 새로 만들지 않는다. 이 DB 조회의 시간 제한과 실패 처리는 기존 요청 예산 안에 둔다. 측정 없이 지연 개선을 주장하지 않는다.

### 8.2 DB 조회 실패와 상속을 같은 것으로 취급하지 말 것

행이 없는 것은 상속이다. DB 조회 실패는 상속이 아니다. 새 프로필 적용 여부를 읽지 못한 상태에서 임의로 기존 원격 경로를 선택하면 저장한 제한을 벗어날 수 있다.

- 기존 Run: 이미 고정한 snapshot을 계속 사용한다.
- 새 Run: 정책을 읽을 수 없으면 상태를 `routing_policy_unavailable`로 분리한다. 오류를 대화 원문·기억·TTS에 넣지 않는다.
- 유효한 이전 committed snapshot을 사용할지에 대한 운영 정책이 기존에 있으면 그 범위를 재사용한다. 없다면 새 캐시를 만들어 조용히 stale 정책을 쓰지 않는다.
- 새 역할 정책의 저장 기능을 아직 배선하지 않은 배포에서는 기존 실행을 그대로 유지한다. 새 기능 실패가 무관한 과거 경로를 무조건 막는 식으로 확장하지 않는다.

전역 비활성 상태와 정책 저장소 장애를 구분해야 한다. 비활성임을 이미 확정한 기존 실행은 계속 실행할 수 있지만, 새 실행의 정책 상태를 확인하지 못한 값을 false로 날조해서는 안 된다.

### 8.3 비동기 역할과 클라이언트 캐시

main/BQ/ER/RC를 하나의 mutable `currentRole` 필드로 기록하지 않는다. 병렬 실행이 역할을 서로 덮어쓴다. 각 호출에 불변 역할 식별자와 동일 Run snapshot을 명시적으로 전달하고, 기존 실행 컨텍스트 전파 경로에서 복원한다.

`LlmRouterContext`는 마지막 선택의 ThreadLocal일 뿐이므로 이 기록만으로 역할별 완료 결과를 조립하지 않는다. 해당 Context의 baseUrl을 새 페이지로 직렬화하지 않는다. E49.

신규 명시 역할 경로의 클라이언트 생성에는 역할·리비전·endpoint/model 정체성이 고정돼야 한다. 기존 캐시가 모델·tier·토큰·시간·온도만 포함하는 점을 고려한다. 초기안은 새 override 분기에서 기존 factory 경로를 사용하되, 새 전역 모델 빈 교체를 하지 않는다. 캐시를 재사용한다면 정책 리비전과 정제된 연결 정체성·기능 요구까지 키에 반영하고 용량·만료를 유지한다. E07.

다른 사용자의 endpoint·credential·OAuth client를 같은 모델 이름이라는 이유로 재사용하지 않는다. profile 저장 중 클라이언트를 전역으로 교체하지 않는다.

## 9. 미리보기: 모델을 호출하지 않고 설정의 의미를 검증하기

### 9.1 미리보기와 실제 점검을 분리한다

미리보기는 정책·카탈로그의 안전한 메타데이터·이미 관측된 상태를 고정해 수행하는 순수 계산이다. 모델 응답 품질을 측정하거나 연결 성공을 보장하는 기능이 아니다.

허용: 역할 누락·중복·순환 검증, 정책 상속 계산, 시나리오별 분기 설명, 현재 등록 경로와 후보 제한 비교, 고정된 bandit 통계로 후보 점수 설명, 적용 전후 차이.

금지: 생성 API, OAuth 갱신, 네트워크 probe, 모델 설치·pull·warmup, 공급자별 전체 카탈로그 새로고침, bandit pulls/successes 갱신, 기존 요청의 취소·재생성, 새 세션 생성, 메모리 저장.

`LlmRouterBandit.pick`은 내부 arm 생성과 trace 기록이 있으므로 미리보기에서 호출하지 않는다. `DesktopRouterStatusBridgeController`의 `/api/router/status`도 조건부이고 health·GPU diagnostics를 수행하므로 순수 미리보기 데이터 원본으로 호출하지 않는다. E19·E45.

### 9.2 계산은 공유하되 부수 효과는 분리한다

기존 `RouterPolicy`의 분기 판단과 `LlmRouterBandit`의 후보 점수 계산에서 순수한 부분만 작은 함수로 추출한다. 실제 실행과 미리보기가 같은 계산 함수를 사용하게 하고, 실제 실행의 관측·통계 기록은 기존 호출자에 남긴다.

새 `PreviewRouter`가 비슷한 알고리즘을 복제하는 방식은 금지한다. 현재 lowest-tier 우선과 UCB 계산을 UI에서 따로 재구현하지 않는다. 정적 weight는 요청 비율이 아니다. “이번 후보 계산에서의 prior”라고 표시하거나 첫 배포에서는 읽기 정보로만 둔다.

구조적으로 공유할 최소 경계의 명칭 초안:

```text
RouterPolicy의 기존 순수 판단 함수
LlmRouterBandit.scoreCandidates(고정 후보, 고정 통계)
RoutingProfileResolver.resolveRole(고정 프로필, 역할, 기존 기본값)
RoutingProfileResolver.restrictFallbacks(역할, 기존 허용 집합)
```

기존 계산 결과를 그대로 유지하는 특성 테스트를 먼저 작성한다. runtime에서만 cooldown·관측 갱신이 발생하고 preview에서는 하나도 발생하지 않아야 한다.

### 9.3 시나리오와 출력

첫 배포의 시나리오는 짧은 인사, 일반 질문, CODE, 최신 정보 검색, RAG OFF, Self-Ask 활성, 선택 모델 비가용, 허용 후보 없음, 취소 상태를 포함한다. 실제 질문 원문을 저장하거나 전송하지 않고 구조화된 가정으로 계산한다.

질문 분석이 필요한 분기를 가정으로 입력했다면 “복잡 요청으로 판단된 경우”라고 표시한다. 모델이나 분류기를 호출하지 않았는데 실제 사용자 질문의 분류 결과라고 출력하지 않는다.

```text
시나리오: Self-Ask가 실행되는 질문 / BQ

기존 배정: 서버 BQ 설정 상속
편집 배정: llmrouter.gemma
대체 목록: 없음
추가 대체 호출: 0

분기 설명:
- Self-Ask 실행 조건이 성립한 경우에만 적용
- 선택 경로는 등록 목록에 존재
- 최신 endpoint 상태는 미관측
- 실제 실행 시 기존 gateway 적합성 확인 필요

외부 호출 수: 0
정책 저장: 안 함
예상 실제 응답 모델: 미확정
```

미리보기의 `selectedCandidate`와 실제 응답의 `responseModelId`를 같은 칸에 넣지 않는다. 가격이나 실제 토큰 수가 없으면 예상 비용은 null이다. null을 0달러로 그리지 않는다.

---

## 10. 실제 사용 모델 관측: 다섯 단계를 구분한다

### 10.1 관측 계약

| 단계 | 필드 명칭 초안 | 증거를 쓰는 시점 | 하면 안 되는 대체 |
|---|---|---|---|
| 사용자의 요구 | requestedChoiceId, strictModelSelection | 검증된 요청을 접수한 때 | 브라우저의 임의 관리자·owner 값을 신뢰 |
| 정책의 선택 | selectedRouteKey, role, profileRevision | 기존 라우터가 후보를 정한 때 | 이것을 실제 응답 모델로 표시 |
| 호출 시도 | attemptedRouteKey, configuredModelId, attemptId | 실제 adapter 호출을 시작한 때 | 단순 client 생성이나 preflight를 호출 성공으로 기록 |
| 공급자 응답 관측 | responseModelId, verificationStatus | 실제 ChatResponse 등의 메타데이터를 읽은 때 | 응답 모델이 없는데 selectedRouteKey를 복사 |
| 사용자 답변에 채택 | answerOriginAttemptId, resultOrigin | 어떤 시도 결과가 최종 답변에 채택됐는지 확정한 때 | 마지막으로 성공한 보조 모델을 최종 모델로 표시 |

`LlmRouteDecision`은 메인 선택 계약으로 그대로 유지한다. 이번 관측 추가를 이유로 기존 record를 별도 MainDecision으로 대체하거나 public ChatRequest/ChatResult 계약을 바꾸지 않는다. 상세 관측은 새 typed 조회 응답에 담고, 기존 `modelUsed`는 호환용 레이블로 유지한다.

`verificationStatus`의 기존 값이 있는 경로는 그대로 대응한다. 새 통합 표시가 필요하면 `exact`, `approved_alias`, `unverified`, `mismatch`, `not_called`를 구분하되 §6과 맞춘다. 응답 검증을 요구하는 경로의 mismatch/unverified를 성공으로 바꾸지 않는다. E23.

캐시·규칙 응답·정적 대체 안내라면 이번 실행의 responseModelId는 null일 수 있다. 과거에 캐시를 만든 모델과 이번에 생성 호출한 모델을 구분한다. 호출이 없다는 이유로 임의 모델명을 넣지 않는다.

### 10.2 역할별 관측 화면

```text
실행: 사용자가 선택한 자신의 실행
사용 정책: r12

역할            선택 경로           실제 호출       응답 모델 검증      최종 답변 기여
메인 생성       등록 경로 A          시도 1 실패      응답 없음           미채택
메인 생성       허용 대체 경로 B     시도 2 성공      exact               채택
Self-Ask BQ     등록 경로 C          시도 1 성공      unverified          검색 질문 생성
Self-Ask ER     상속 경로            건너뜀           not_called          없음
Self-Ask RC     상속 경로            건너뜀           not_called          없음
```

위 A/B/C는 화면 구조를 설명하는 예시다. 특정 모델이 현재 실행됐다는 보고가 아니다. 실제 UI에는 서버가 정제한 실제 식별자만 들어간다.

### 10.3 접근 권한과 보존 범위

새 `routing/read`는 세션·실행 선택을 생략하면 서버 정책만 반환한다. 실행을 선택한 경우 서버가 세션 소유자를 다시 조회한다. 클라이언트가 보낸 owner 값은 입력 스키마에 없다.

상세 실행 조회는 `ChatApiController.getSessionResponse`의 기존 `isAdmin`, `traceOwner`, `interviewDemo` 관련 조건을 그대로 재사용한다. 현재 일반 session 조회와 상세 trace 조회 조건은 다르다. 단순히 ADMIN이라는 이유로 다른 소유자의 상세 실행을 반환하지 않는다. E38.

구현 시 기존 접근 판정 코드를 작게 추출해 재사용해야 한다면 같은 입력에 같은 결과를 내는 특성 테스트를 먼저 작성한다. 인증·권한 정책 강화나 완화는 하지 않는다. 기존 API의 응답 코드와 복구 행동도 바꾸지 않는다.

최근 실행 데이터는 기존 Run 및 이미 허용된 trace 저장 범위에 둔다. Run TTL 이후 기존 스냅숏에 없는 상세 정보는 `not_recorded`로 반환한다. 이를 위해 장기기억·GraphRAG·대화 원본에 진단 로그를 추가 저장하지 않는다. 삭제된 세션의 늦은 callback으로 기록을 되살리지 않는다.

기존 `api.route.*` debug allowlist에 임의 필드를 섞지 않는다. `purpose/provider/model/endpointClass/attempt/httpStatus/errorClass/fallbackTo/keyPresent/keySource`의 의미와 `api.spend.decision`의 분리를 유지한다. 새 typed 관측 객체는 별도이며 입력 원문·전체 예외·키·개인 그래프를 포함하지 않는다. E40·E50.

---

## 11. 코드 연결 지점: 추가 인자가 AOP에서 사라지는 실수 방지

### 11.1 현재 확인된 인자 해석 경계

현재 `DynamicChatModelFactory.lcWithTimeout`에는 5·7·8개 인자 호출 경로와 `ModelSpecSnapshot observedContext`를 포함한 9개 인자 구현이 있다. 반면 `LlmRouterAspect.CallArgs.parse`는 5·7·8개 인자 형태를 구분한다. E51·E52.

근거: `FIND_X.zip!main/java/com/example/lms/llm/DynamicChatModelFactory.java:230–232,266–301`; `FIND_X.zip!main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java:2308–2389`.

이 차이만으로 기존 9개 인자 호출을 무조건 결함이라고 판정하지 않는다. 해당 경로가 AOP를 의도적으로 통과하지 않는지 현재 호출자·테스트로 확인해야 한다. 다만 **역할 정보를 추가한 새 호출을 기존 parse가 자동 이해한다고 가정하면 안 된다.**

### 11.2 신규 명시 배정 호출에만 내부 typed 인자를 추가한다

기존 오버로드를 없애거나 바꾸지 않는다. 필요한 경우 아래 10개 인자 오버로드를 **새로운 내부 seam**으로 추가한다.

```java
public ChatModel lcWithTimeout(
        String modelName,
        Double temperature,
        Double topP,
        Double frequencyPenalty,
        Double presencePenalty,
        Integer maxTokens,
        int timeoutSeconds,
        Integer maxRetriesOverride,
        ModelSpecSnapshot observedContext,
        RoutingInvocation routingInvocation);
```

이 코드는 구현 본문이 아니라 정확한 연결 계약 초안이다. 새 `RoutingInvocation`은 서버에서 만든 불변 객체로서 role·Run snapshot 참조·허용 후보·공유 추가 호출 장부를 가진다. 공개 요청 DTO에 직렬화하지 않는다. owner 인증값을 클라이언트에서 받지 않는다.

9번째 인자만 다른 타입인 오버로드를 새로 만들지 않는다. 기존 마지막 null 전달이 모호해질 수 있다. 10번째 인자를 추가해 기존 9개 인자 의미를 보존한다.

`CallArgs.parse`는 이 정확한 신규 형태를 인식해야 한다. `withoutLibraryRetries`, 선택 재시도, device 대체, client wrapper 생성 등 CallArgs 복사 지점은 invocation과 observedContext를 보존한다. 모델 생성 시에만 제한을 확인하고 반환된 ChatModel의 실제 chat 호출에서는 잊어버리는 구현은 허용하지 않는다.

AOP의 역할 정책 적용 bean이 없거나 해당 실행 경로가 역할 제한을 전달할 수 없으면 “설정 적용됨”으로 표시하지 않는다. 새 프로필을 활성화하기 위한 runtimeSupported 검사와 회귀 테스트로 잡는다. 기존 생성 경로의 정상 작동을 새로운 무제한 bypass로 대체하지 않는다.

### 11.3 역할별 provider와 main 결정의 충돌 방지

보조 역할을 exact로 고정하고 싶다는 이유로 `RequestedModelSelection`의 메인 선택 값을 임시 변경하지 않는다. 메인 출력 예산·owner와 보조 역할의 제약이 섞인다.

역할의 고정 후보는 `RoutingInvocation`에서 제한한다. `RequestedModelSelection`과 `LlmRouteDecision`은 기존 메인 계약을 유지한다. 동일 role key라도 실행별 리비전이 다를 수 있으므로 cached ChatModel이 이전 실행의 장부나 owner를 포획하지 않게 한다. 호출별 invocation을 품은 객체는 전역 singleton·장수 캐시에 넣지 않는다.

---

## 12. 수정 파일 계획과 금지 범위

모든 경로는 `FIND_X.zip!main/`을 기준으로 한다. 실제 checkout의 sourceSet 확인 전 `src/main`으로 바꿔 복사하지 않는다. 이전 코드 패키지의 `src/main/...` 표기는 배포 경로 증거가 아니다.

### 12.1 새로 추가할 책임 단위

아래 이름도 §6 명칭 대응 대상이며 기능은 소비자가 확보된 것만 추가한다.

| 파일 초안 | 책임 |
|---|---|
| `main/java/com/example/lms/routing/settings/RoutingProfile.java` | 여섯 역할·정제된 경로 참조·리비전·제한의 불변 데이터. 관련 작은 record/enum은 이 단위에 모은다. |
| `main/java/com/example/lms/routing/settings/RoutingSettingsService.java` | 기존 ConfigurationSetting 한 행의 typed 검증·동시 저장·재조회. 범용 SettingsService의 역할을 복제하지 않는다. |
| `main/java/com/example/lms/routing/settings/RoutingProfileResolver.java` | 상속 계산과 후보 집합 제한. 별도 모델 선택 엔진은 만들지 않는다. |
| `main/java/com/example/lms/routing/settings/RoutingInvocation.java` | 특정 호출에 귀속되는 내부 역할·스냅숏·장부 연결. 브라우저 입력으로 생성 불가. |
| `main/java/com/example/lms/routing/settings/RoutingOutcomeProjector.java` | 실제 관측의 typed·정제된 표시. 권한 판정 이전에는 데이터를 직렬화하지 않는다. |
| `main/java/com/example/lms/service/chat/RunRoutingSnapshot.java` | 기존 Run에 결합할 불변 정책 값. 새 세션 저장소가 아니다. |
| `main/java/com/example/lms/api/RoutingSettingsController.java` | read/preview/save 세 POST 경계. 기존 ADMIN 체인 사용. |
| `main/resources/static/js/settings-routing.js` | 설정 페이지의 역할 편집·서버 API·미리보기·리비전 상태 표시. 채팅 전송에는 관여하지 않는다. |

기존 새 페이지 초안의 `settings.html`, `settings-page.js`, `settings-page.css`, `chat-settings-bridge.js`, `SettingsPageController.java`는 존재 여부·hash를 먼저 확인한 뒤 재사용한다. 없는 것을 “DONE”으로 기록하지 않는다. 별도 공통 프레임워크·event bus·일반적 workflow DSL을 추가하지 않는다.

### 12.2 필요한 기존 줄의 최소 수정

| 파일·seam | 필요한 변경 | 기존 동작 변경 여부 |
|---|---|---|
| `PolicyBasedModelRouter.route(RouteSignal)`, `routeMain(...)` | 기존 tier 결정 뒤 실제 사용하는 모델 배정에 frozen profile 반영. exact/OAuth 우선순위 유지. | 프로필 활성·해당 역할만 변경 |
| `SelfAskPlanner.modelIdForLane`, `providerForLane`, `modelFor`, `regenerateLane`, `localCounterFallback` | model/provider와 호출 invocation을 함께 전달. 추가 대체 호출 공유 제한. | 프로필 활성·BQ/ER/RC만 변경 |
| `DynamicChatModelFactory.lcWithTimeout` | 내부 10개 인자 오버로드. 기존 형태 유지. | 신규 경로만 추가 |
| `LlmRouterAspect.CallArgs.parse`, `withoutLibraryRetries`, `routeWithGateway`, 대체 선택 함수 | 역할 제약을 잃지 않고 모든 후보 선택·실제 호출에 반영. | 신규 invocation 없는 기존 경로 유지 |
| `LlmRouterBandit`의 순수 점수 계산 부분 | preview/runtime이 공유할 부수 효과 없는 함수 추출. | 결과 동일성 테스트 전제 |
| `ChatRunRegistry.Run`, `contextFor/exactRun` 관련 accessor | snapshot·역할 관측을 기존 Run에 결합하고 기존 lease 검사 사용. | 세션·취소·저장 계약 유지 |
| `ChatRunExecutionContext` | 기존 registry 접근을 통한 snapshot 읽기. 별도 전역 상태 없음. | 원래 scope 수명 유지 |
| `ChatWorkflow`의 실행 초기화와 main 결정 seam | snapshot 한 번 고정, 메인 최종 채택 시 실제 origin 연결. | 원래 main binding 순서 유지 |
| `ChatApiController.getSessionResponse` 인접 접근 판정 | 필요한 경우 현재 조건의 작은 공통 함수 추출만 허용. | 권한 결과·기존 응답 동일 |
| 이전 설정 초안의 HTML/JS/CSS | 역할 카드와 새 페이지 전용 script, 저장 상태 확장 | 새 페이지 초안 변경 |

공유 서버 정책을 실제로 소비하게 하려면 기존 소비자 몇 줄은 수정해야 한다. “새 파일만”을 절대화해 동일 역할의 두 번째 라우터를 만드는 것은 금지한다.

### 12.3 반드시 변경 0인 항목

`main/resources/static/js/chat.js`, 기존 `/model-settings` 템플릿·계약, 기존 `/api/settings` GET/POST 허용 목록과 응답 계약, `ConfigurationSetting` 엔티티·DB 스키마, 보안 설정·필터의 정책, `static/assets/interview/*`, 비밀 파일·환경변수 이름·값.

`chat-ui.html`은 이전 링크 한 줄과 bridge script 한 줄 범위를 유지한다. 새 settings-routing.js는 `/settings`에서만 로드한다. 메인 bridge는 네 기존 control ID만 사용한다. 네트워크 가로채기, DOM의 숨은 입력으로 신규 서버 정책을 전송하기, chat.js 전역 함수 호출을 금지한다.

---

## 13. Codex 작업 패키지: 다섯 개 이하, 순차 진행

공통 시작 조건: 사용자 checkout의 `git status --short`, HEAD, 기존 작업 리스·저널, 실제 build root·sourceSet을 확인한다. 공유 원본의 다른 에이전트 변경은 보존한다. 커밋·push·reset·clean은 하지 않는다. 테스트는 외부 공급자를 mock으로 격리하며 운영 DB·사용자 세션·Display·GPU를 사용하지 않는다.

Gradle 명령의 `$TestTask`는 실제로 확인한 Test task 경로다. 예를 들어 사용자 프로젝트의 별도 테스트 sourceSet에 있는 클래스를 `:test`로 실행하고 “테스트 0개”를 통과로 기록하면 안 된다. 각 WP의 클래스 이름은 새 테스트 이름 초안이며 sourceSet 경로는 확인 후 정한다.

### WP1 — 현재 카탈로그·역할 의미·순수 미리보기 경계 고정

**판정:** PARTIAL + NEW. **seam:** `ChatModelCatalogService.choices/resolve`(E25·E26), `PolicyBasedModelRouter.routeMain`(E06), `SelfAskPlanner.modelIdForLane`(E15), `LlmRouterBandit` 점수 계산(E18).

**변경:** `RoutingProfile`, `RoutingProfileResolver`의 불변 데이터·허용 역할·상속·등록 경로 식별을 정의한다. 기존 bandit의 순수 계산만 추출한다. main 카탈로그와 관리자 등록 경로 보기를 분리하고 public main 모델 목록을 자동 확대하지 않는다.

**RED:** `RoutingProfileContractTest`에서 unknown role, 개인 OAuth 전역 저장, raw URL, 중복 후보를 거부하도록 먼저 작성한다. `RoutingPreviewParityTest`에서 preview가 bandit/health/OAuth/DB write를 호출하면 실패하도록 mock 검증을 건다. 기존 선택 입력 표본은 추출 전후 결과가 같아야 한다.

**GREEN:** 실제 순수 계산·검증을 연결하고, 등록됨/선택 가능/자동 후보/응답 관측을 별도 필드로 반환한다. 새 고성능 판별 모델을 호출하지 않는다.

**완료 명령:**
```powershell
.\gradlew.bat $TestTask --tests '*RoutingProfileContractTest' --tests '*RoutingPreviewParityTest'
```
**확인:** 실행된 테스트 수, mock 외부 호출 0, bandit 상태 변경 0, 기존 tier 선택 동일.

**금지 파일:** chat.js, AppSecurityConfig, AdminTokenGuardFilter/Interceptor, ConfigurationSetting 엔티티, 스튜디오, 공급자 키 설정.

### WP2 — 기존 보호 아래 원자적인 서버 역할 정책 저장

**판정:** NEW. **seam:** `ConfigurationSettingRepository` 기존 저장소, `SettingsController` allowlist는 읽기 기준(E27), `AdminTokenGuardFilter` prefix와 기존 POST 권한(E30~E34).

**변경:** `RoutingSettingsService`, `RoutingSettingsController` 추가. read/preview/save만 제공한다. runtime 지원·활성 상태와 profile.enabled를 구분한다. 기존 row 하나에 revision/hash를 저장하고 DB 잠금·최초 생성 충돌·재조회 계약을 구현한다. 서비스 진입에서 typed JSON만 허용한다.

**RED:** `RoutingSettingsControllerTest`는 일반 비관리자 POST 거부, 현재 PROTO_OPEN 동작 보존, 기존 GET 성공으로 관리자 추정하지 않기, 기존 generic POST에서 신규 키 계속 거부를 검사한다. `RoutingSettingsPersistenceTest`는 동시 저장 두 건 중 하나만 채택, 최초 행 생성 충돌, 응답 유실 후 read-back, 캐시 오염 없음, unsupported 필드 전체 거부를 검사한다.

**GREEN:** 모든 쓰기를 원자적으로 처리한다. read는 빈 상태를 조회해도 행을 만들지 않는다. 미확인 activation 상태를 “적용”으로 반환하지 않는다. 키·계정 토큰·endpoint URL을 DTO에 넣지 않는다.

**완료 명령:**
```powershell
.\gradlew.bat $TestTask --tests '*RoutingSettingsControllerTest' --tests '*RoutingSettingsPersistenceTest'
```
**확인:** DB 테스트는 격리된 테스트 저장소, 범용 API 계약 차이 0, 보안 파일 diff 0, 충돌의 무음 덮어쓰기 0.

**금지 파일:** SettingsController, ModelSettingsController, model-settings.html, 보안 정책 파일, 엔티티·마이그레이션 파일, chat.js.

### WP3 — 실행 스냅숏과 여섯 역할의 실제 소비 연결

**판정:** PARTIAL + NEW·조건부 기존 동작 변경. **seam:** E04~E08·E14·E15·E20~E22·E35~E37 및 §11의 factory/AOP 인자 경계.

**변경:** 기존 Run에 `RunRoutingSnapshot`을 결합한다. `RoutingInvocation`을 신규 factory 오버로드와 AOP의 CallArgs에 명시적으로 전달한다. 기본·경량·고성능 및 BQ/ER/RC의 실제 호출이 같은 snapshot을 사용하게 한다. 모든 대체 분기와 RC 로컬 대체에서 후보 집합·공유 추가 호출 상한을 검사한다.

**RED:** `RoutingRunSnapshotTest`는 진행 중 rN을 저장된 rN+1로 바꾸지 않기, context 재생성·재연결·병렬 lane에서도 같은 snapshot, 취소 후 늦은 결과 폐기, 기능 off에서 신규 저장소 의존 0을 검사한다. `RoleRoutingConsumptionTest`는 각 여섯 역할을 선택한 실제 factory 호출 인자로 검증한다. `RoutingFallbackBoundaryTest`는 global-tail/device/local-contended/RC 대체가 목록 밖을 호출하면 실패하도록 한다. `RoutingFactoryArgumentsTest`는 기존 5·7·8·9 인자 특성과 새 10개 인자의 관측 문맥·역할 전달을 각각 검사한다.

**GREEN:** default/off 경로는 기존 코드를 그대로 사용한다. 특정 override만 새 invocation 경로를 사용한다. 엄격한 메인 선택·OAuth 우선순위·출력 예산 고정·원래 취소와 멱등성을 보존한다.

**완료 명령:**
```powershell
.\gradlew.bat $TestTask --tests '*RoutingRunSnapshotTest' --tests '*RoleRoutingConsumptionTest' --tests '*RoutingFallbackBoundaryTest' --tests '*RoutingFactoryArgumentsTest'
```
**확인:** 여섯 역할의 consumer assertion 존재, 외부 실호출 0, 후보 밖 호출 0, strict 임의 교체 0, 실행당 리비전 혼합 0, 같은 전송의 새 run 생성 0.

**금지 파일:** chat.js, 공개 ChatRequest/ChatResult API 계약, 기존 보안 파일, 장기기억·GraphRAG 저장 로직, 글로벌 provider·model 기본값 일괄 변경.

### WP4 — 실제 호출·응답·최종 채택을 구분한 조회

**판정:** PARTIAL + NEW. **seam:** `LlmRouterAspect` 실제 호출·응답 검증(E20·E23), `ChatWorkflow` primarySuccess/최종 채택(E39), `ChatRunRegistry` 기존 상태(E35), `ChatApiController.getSessionResponse` 접근 조건(E38), `LlmGatewayBreadcrumbPublisher` allowlist(E40).

**변경:** `RoutingOutcomeProjector`와 기존 Run의 작은 관측 수집 필드를 추가한다. 선택·호출·응답·채택을 각각 기록한다. 상한이 있는 역할별 시도만 보관하고 기존 Run TTL·삭제 fence를 사용한다. `routing/read`의 선택적 실행 조회에서 기존 상세 trace 권한을 그대로 적용한다.

**RED:** `RoutingOutcomeProjectionTest`는 미관측 응답 모델=null, 보조 모델 성공을 최종 모델로 오인하지 않기, cached/static 답변에 새 LLM 호출 모델 없음, mismatch를 성공으로 표시하지 않기, 최종 저장과 모델 성공 분리를 검사한다. `RoutingOutcomeAccessTest`는 자기/타인 세션·일반/관리자·PROTO_OPEN·삭제 세션을 현재 규칙과 비교한다. `RoutingRedactionTest`는 모든 응답에 비밀값·raw URL·원문이 없음을 검사한다.

**GREEN:** 기존 modelUsed는 그대로 두고 새 typed view에 증거 수준을 표시한다. 기록 없는 과거 실행은 not_recorded이며 backfill 생성이나 전역 로그 탐색을 하지 않는다.

**완료 명령:**
```powershell
.\gradlew.bat $TestTask --tests '*RoutingOutcomeProjectionTest' --tests '*RoutingOutcomeAccessTest' --tests '*RoutingRedactionTest'
```
**확인:** 정상 응답·TTS·기억에 진단 문자열 0, 기존 상세 접근 허용 집합 변화 0, api.route allowlist 계약 변화 0.

**금지 파일:** 보안 설정, 진단 전체 공개 경로, 메모리 저장기, 스튜디오, API key·OAuth 저장소, 기존 `modelUsed` 의미 변경.

### WP5 — 설정 페이지 연결과 실패 UX 회귀

**판정:** PARTIAL + NEW. **seam:** 이전 settings 페이지 초안, 새 `settings-routing.js`, 원본 `chat-ui.html` 두 줄 추가 지점, 기존 네 control ID(E01).

**변경:** 역할별 카드·선택기·상속 출처·대체 순서·미리보기·revision 상태·실행 관측을 표시한다. 새 JS는 설정 페이지에서만 로드한다. 브리지는 네 값의 기존 저장·change 이벤트 동작만 유지한다. 권한 또는 서버 기능이 없으면 browser 기본값은 계속 사용할 수 있다.

**RED:** `tests/settings-routing.test.cjs`에 순수 schema·편집 diff·상속 reset·import 제한·409 충돌·403 잠금·응답 유실 read-back·stale catalog·늦은 응답 순서 뒤집힘을 mock으로 작성한다. `SettingsRoutingPageTest`는 페이지 매핑과 기존 `/chat`, 정적 파일 링크, 외부 CDN 없음, 비밀값 입력 없음, 일반 인증 구성의 기존 `/settings` 제한 보존을 검사한다.

**GREEN:** 버튼 이름과 실제 행동이 일치한다. “재확인”은 모델 메타데이터 재확인, “미리보기”는 호출 0, “다음 실행부터 적용”은 정책 commit, “기존 실행 보기”는 조회다. 저장이나 설정 페이지 진입이 새 대화를 만들지 않는다.

**완료 명령:**
```powershell
node --test tests/settings-routing.test.cjs
.\gradlew.bat $TestTask --tests '*SettingsRoutingPageTest'
git diff --check
git diff --numstat -- <검증된-resource-root>/static/js/chat.js
```
**확인:** 마지막 명령의 chat.js 변경 출력 없음. 모든 새 UI 항목에 저장·소비·상태 테스트가 연결됨. 실제 브라우저 모바일·키보드 검사는 별도로 기록하고 하지 않았으면 NOT_RUN.

**금지 파일:** chat.js, 스튜디오, 보안 파일, schema, 기존 model-settings 템플릿. chat-ui.html은 링크·bridge 두 줄 외 수정 금지.

## 14. 회귀 테스트 상세 목록

아래 64개는 **로컬 코딩 에이전트가 작성·실행할 테스트 명세**다. 이번 대화에서 Java/브라우저 테스트로 실행한 결과가 아니다. 모든 행의 실행 상태는 `NOT_RUN`이다. 입력·기대 결과는 `contracts/test-matrix.json`에도 같은 내용으로 수록했다.


### 작업 패키지 1 검증

| ID·테스트 | 입력·조건 | 기대 결과 |
|---|---|---|
| T01 `unknownRoleRejected` | 등록되지 않은 역할을 제출한다. | 400; 기존 정책과 revision 변화 없음. |
| T02 `catalogIdentityNotCollapsed` | choiceId/routeKey/modelId가 서로 다른 후보를 넣는다. | 각 식별자의 의미를 유지; 동일 문자열이라고 가정하지 않음. |
| T03 `globalOAuthBindingRejected` | 서버 전체 역할에 개인 OAuth Choice.id를 저장한다. | 400; 현재 owner의 개인 모델 선택은 기존 경로에서 유지. |
| T04 `arbitraryEndpointRejected` | target 외에 provider/baseUrl/credentialEnv/owner 값을 넣는다. | 400; 값을 echo하거나 URL을 호출하지 않음. |
| T05 `unavailableCatalogNotDeleted` | 이전 목록이 있고 최신 카탈로그 조회만 실패했다. | 목록 미확인 표시; 삭제나 다른 모델로 자동 전환하지 않음. |
| T06 `weightIsNotTrafficShare` | weight 0.45/0.55이고 bandit 관측 통계는 다르다. | UI에 45%/55% 호출 보장 표시 없음; 기존 계산과 동일 결과. |
| T07 `previewIsSideEffectFree` | 동일한 preview를 두 번 호출한다. | 결과 동일; generation/probe/OAuth refresh/DB write/bandit update 호출 모두 0. |
| T08 `previewUnknownHealthIsNull` | 후보는 등록돼 있으나 최신 health 관측이 없다. | 상태 미확인; 호출 성공/설치 삭제/비용 0으로 변환하지 않음. |
| T09 `previewScoreParity` | 동일한 후보·통계·정책 스냅숏을 runtime 순수 계산과 preview에 넣는다. | 선별·점수 결과 동일; 기존 비용 tier 규칙 유지. |
| T10 `nonChatRoleNotInvented` | Jev/임베딩/미배선 judge 역할을 생성 슬롯으로 넣는다. | 검증 거부 또는 명확한 읽기 전용; 생성 호출 경로 자동 추가 없음. |

### 작업 패키지 2 검증

| ID·테스트 | 입력·조건 | 기대 결과 |
|---|---|---|
| T11 `normalAnonymousPostDenied` | 일반 보안 구성, 비관리자, routing read/preview/save POST. | 기존 관리자 보호와 동일 거부; 상세 정책 노출 없음. |
| T12 `protoOpenRemainsExisting` | 현재 PROTO_OPEN 조건으로 같은 경로에 요청한다. | 기존 ROLE_ADMIN 설치와 허용 동작 유지; 새 인증 요구 없음. |
| T13 `getSuccessIsNotAdminProof` | 기존 GET /api/settings는 성공하지만 ADMIN이 없다. | 쓰기 활성화·상세 토폴로지 공개 없음. |
| T14 `genericContractUnchanged` | 기존 POST /api/settings에 새 역할 키를 보낸다. | 기존 unsupported-key 거부 유지. |
| T15 `readDoesNotCreateRow` | 새 역할 프로필 행이 없는 DB에서 read. | revision 0과 상속 상태; insert 0. |
| T16 `concurrentExpectedRevisionCAS` | 두 저장이 동일한 expectedRevision/hash를 사용한다. | 한 저장만 commit; 나머지 409; 무음 덮어쓰기 0. |
| T17 `concurrentFirstInsert` | 서로 다른 두 초안이 최초 행을 동시에 생성한다. | 기존 PK 제약 사용; 충돌 후 새 트랜잭션 재조회; schema 변경 0. |
| T18 `lostSaveResponseReadBack` | commit 뒤 응답만 유실시킨다. | read의 revision/hash로 저장 확인; 자동 재저장 0. |
| T19 `strictJsonEnvelope` | 16KiB 초과/중복 JSON 키/중복 역할/unknown 필드를 보낸다. | 400 또는 크기 초과 413; 부분 저장 0; 원문 echo 0. |
| T20 `cachedGeneralSettingsNotProfileAuthority` | 범용 SettingsService 캐시를 이전 상태로 고정한다. | typed committed 정책 조회가 정확; 기존 숫자 설정 결과 변형 없음. |
| T21 `disabledProfileHasNoExecutionEffect` | 프로필의 enabled=false로 여러 배정 초안을 저장한다. | 행 저장만 수행; 역할 배정 및 모델 호출에 반영하지 않음. |
| T22 `runtimeGateOffDoesNotPretendActive` | runtime gate가 false인데 enabled=true 활성 문서를 저장하려 한다. | 명확한 runtime_not_enabled 검증 결과; 적용 완료 표시 없음; 비활성 초안 저장은 가능. |

### 작업 패키지 3 검증

| ID·테스트 | 입력·조건 | 기대 결과 |
|---|---|---|
| T23 `disabledRuntimeNoNewDatabaseDependency` | 새 runtime gate=false 상태에서 기존 채팅을 실행한다. | 새 프로필 DB 조회 0, 신규 모델 경로 0; 기존 동작 유지. |
| T24 `allSixRolesReachConsumers` | 각 역할에 서로 구별되는 mock 등록 경로를 배정한다. | MAIN_DEFAULT/FAST/HIGH/BQ/ER/RC의 실제 factory 인자가 각각 일치. |
| T25 `strictMainNeverOverridden` | strict 모델 A, 서버 MAIN_HIGH 모델 B, A 비가용. | B로 대체하지 않고 기존 정확 선택 오류; 요청 모델 변조 0. |
| T26 `existingOAuthPrecedencePreserved` | non-strict, 복잡 요청 OAuth 기존 조건 충족. | 기존 complex_main_oauth 순서 유지; role profile이 앞질러 덮지 않음. |
| T27 `autoLiteralPreserved` | 브라우저 auto를 선택한다. | 기존 payload.model=llmrouter.auto, strictModelSelection=false. |
| T28 `runRevisionFrozen` | r12 Run을 시작한 후 r13을 저장하고 같은 Run을 재연결한다. | 같은 Run은 r12; 다음 새 Run만 r13 사용. |
| T29 `contextRecreationKeepsSnapshot` | 같은 Run에 contextFor를 다시 호출한다. | 동일 snapshot 및 공유 호출 장부; 새 모델 선택·새 세션 생성 0. |
| T30 `parallelRoleIsolation` | BQ와 ER이 같은 target이나 서로 다른 대체 목록으로 병렬 실행된다. | 역할별 후보·시도 ID가 섞이지 않음; mutable currentRole 없음. |
| T31 `disabledStageSkipped` | RAG/Self-Ask가 기존 조건에 따라 생략된다. | 설정된 BQ/ER/RC 모델 호출 0; 상태는 not_called/skipped. |
| T32 `modelProviderChangedTogether` | BQ를 다른 provider의 허용 등록 경로로 변경한다. | model/provider 모두 같은 서버 등록 원본에서 도출. |
| T33 `globalTailCannotEscape` | 새 목록에 A와 B만 있고 B도 실패하며 등록 C는 건강하다. | C 호출 0; 기존 전체 등록 후보 확장 차단이 새 invocation에만 적용. |
| T34 `deviceFallbackCannotEscape` | deviceFallbackKey가 새 역할 목록 밖의 D를 가리킨다. | D 호출 0; 기존 자원 보호·실패 이유 유지. |
| T35 `contendedArmCannotEscape` | 로컬 슬롯 포화로 repickUncontendedLocalArm 분기를 유도한다. | 명시 목록 밖 호출 0; 무제한 큐 대기 추가 없음. |
| T36 `duplicateEndpointModelNotRetried` | 다른 key A/B가 동일 endpoint/model을 가리킨다. | 같은 실패 호출 대상으로 중복 추가 실행하지 않음. |
| T37 `zeroExtraCallsMeansNoFallback` | 새 역할 maxExtraFallbackCalls=0, primary 실패. | 새 후보 generation 0; 0을 무제한으로 해석하지 않음. |
| T38 `laneAndGatewayShareBudget` | gateway 대체 1회 후 RC localCounterFallback이 추가 시도하려 한다. | 같은 역할 장부로 상한 확인; 이중 예산 우회 없음. |
| T39 `cancelDoesNotStartFallback` | primary 중 취소 후 timeout callback이 도착한다. | 추가 후보 시작 0; 기존 취소·슬롯·최종 저장 단일성 유지. |
| T40 `existingFactorySignaturesRemain` | 기존 5/7/8/9개 인자 호출 표본을 실행한다. | 기존 overload 선택과 AOP 특성 보존; 임의로 9개 경로 정책 수정 안 함. |
| T41 `newInvocationSurvivesCopies` | 10개 인자 호출과 withoutLibraryRetries/장애 대체를 실행한다. | role/snapshot/observedContext/공유 장부 유지. |
| T42 `budgetPreflightMatchesGeneration` | 서로 다른 출력 한도를 가진 모델 후보를 쓴다. | 문맥 준비와 실제 생성은 동일 고정 main 결정·한도 사용. |
| T43 `additionalPaidPermissionNotGranted` | 비용 상한 0 또는 추가 paid=false에서 새 원격 후보를 선택한다. | 기존 spend/permission 경계를 넘는 추가 paid 실행 0. |
| T44 `profileReadFailureNotAbsent` | runtime gate=true인 새 Run에서 정책 DB 조회가 실패한다. | 명확한 policy unavailable; false/행 없음으로 날조하지 않음; 기존 Run snapshot은 유지. |
| T45 `cachedClientDoesNotCaptureOtherRun` | 같은 모델을 두 owner·두 profile revision으로 사용한다. | 다른 owner/호출 장부/이전 연결 구성 재사용 없음. |

### 작업 패키지 4 검증

| ID·테스트 | 입력·조건 | 기대 결과 |
|---|---|---|
| T46 `missingResponseModelNotFabricated` | 선택·호출 성공 정보는 있으나 response.modelName이 없다. | responseModelId=null, unverified; routeKey로 채우지 않음. |
| T47 `helperSuccessNotFinalAnswerModel` | BQ가 마지막으로 성공했지만 최종 답변은 main 이전 성공 결과다. | answerOriginAttemptId는 실제 채택 main; BQ는 보조 역할. |
| T48 `verifiedMismatchNotSuccess` | 검증 필수 경로가 다른 response.modelName을 반환한다. | 기존 mismatch 처리 유지; 정상 모델 성공 표시 없음. |
| T49 `staticOrCachedAnswerHasNoNewModel` | 이번 Run은 캐시 또는 정적 안내만 반환한다. | 이번 generation model=null/not_called; 과거 캐시 생성 모델과 구분. |
| T50 `ownershipProjectionMatchesExisting` | 자기/타인 세션과 일반/관리자/PROTO_OPEN 조합을 검사한다. | 상세 trace의 기존 isAdmin AND traceOwner 및 관련 조건과 동일. |
| T51 `deletedSessionLateResultDiscarded` | 세션 삭제 후 늦게 종료된 역할 결과를 기록하려 한다. | 조회·저장 거부; 삭제된 기록 부활 없음. |
| T52 `runExpiryNotBackfilled` | Run TTL이 지났고 기존 trace에 상세 항목이 없다. | not_recorded; 과거 모델 추측·새 생성·메모리 저장 0. |
| T53 `secretsNotInTypedProjection` | mock 메타데이터에 토큰·credential·전체 URL·프롬프트를 포함한다. | typed DTO·로그·export에 해당 값 0; 허용 필드만 반환. |
| T54 `modelSuccessNotPersistenceSuccess` | 모델은 성공했지만 최종 저장이 거부된다. | 모델 성공/저장 실패를 각각 표시; 전체 성공 표시 없음. |

### 작업 패키지 5 검증

| ID·테스트 | 입력·조건 | 기대 결과 |
|---|---|---|
| T55 `bridgeUsesOnlyExistingFourControls` | 새 페이지에서 브라우저 기본값을 저장하고 chat으로 복귀한다. | 네 기존 ID와 change 이벤트만 사용; chat.js 변경 0. |
| T56 `activeConversationNotOverwritten` | 기존 세션·진행 실행이 있고 다른 탭에서 기본값을 바꾼다. | 진행 중인 선택·실행 유지; 다음 적절한 새 대화에서 기본값 반영. |
| T57 `revisionConflictPreservesEditor` | 서버 save가 409를 반환한다. | 편집값 보존·기존 서버값 재조회; 무음 재저장 없음. |
| T58 `expiredAdminLocksOnlyServerBlock` | 서버 라우팅 API가 403 또는 로그인 HTML을 반환한다. | 서버 블록 잠금·사유 표시; browser 기본값은 유지; HTML 원문 출력 없음. |
| T59 `lateReadCannotOverwriteNewerRevision` | r12 read 응답이 r13 저장 확인 뒤 늦게 도착한다. | 낮은 리비전으로 화면·활성 상태를 되돌리지 않음. |
| T60 `resetMeansCurrentInheritance` | 과거 기본 모델과 현재 서버 상속 모델이 다르다. | override 제거 후 현재 서버 상속값 사용; 과거 문자열 하드코딩 없음. |
| T61 `importIsAtomicAndNotAuthority` | 파일에 unknown role/prototype 관련 키/개인 OAuth/권한 값을 넣는다. | 전체 거부, 기존 브라우저·서버 설정 보존; import 자동 서버 적용 없음. |
| T62 `buttonsMatchActions` | 미리보기·재확인·적용·기존 실행 보기 버튼을 각각 누른다. | 미리보기 생성0, 재확인 단일metadata, 적용정책저장만, 실행보기조회만. |
| T63 `settingsAuthBoundaryPreserved` | 일반 인증 구성과 PROTO_OPEN에서 /settings에 접근한다. | 기존 실제 체인의 동작을 기준으로 판정; 무조건 익명200 요구 안 함. |
| T64 `noUnrelatedFileChanges` | 적용 후 diff와 정적 파일 목록을 검사한다. | chat.js/schema/security/studio 변경0; CDN 추가0; chat-ui 최소2줄. |

## 15. 교차검증: 성공 가설·반례·중립 판정

| 가설 | 성공하는 조건 | 반례·회귀 위험 | 중립 채택 기준 |
|---|---|---|---|
| 역할 설정을 기존 소비자에 연결하면 모델 배정을 제어할 수 있다. | 여섯 역할의 실제 factory 호출이 frozen 배정을 사용한다. | 화면은 BQ를 바꿨으나 regenerate나 RC 대체는 옛 경로를 호출할 수 있다. | T24·T30~T32·T38·T41에서 실제 mock 호출 인자·후보 집합 확인. |
| 한 행의 리비전과 Run 스냅숏으로 저장·실행 불일치를 줄일 수 있다. | commit 이후 새 Run에 한 번 결합하고 같은 Run은 재사용한다. | Context 인스턴스가 바뀌면 스냅숏이 사라지거나 일반 설정 캐시가 이전 JSON을 반환할 수 있다. | T16~T20·T28·T29·T44로 동시 저장·재연결·조회 실패를 분리. |
| 관측 정보를 추가하면 실제 사용 모델을 설명할 수 있다. | 실제 호출·응답·최종 채택 지점에 별도 기록이 있다. | 선택된 이름만 있고 응답 모델은 없거나 보조 역할이 마지막 성공일 수 있다. | T46~T54로 미관측=null, 역할 분리, 권한·삭제·저장 결과 분리. |

별도 모델 세 개를 호출해 찬성·반대·중립 의견을 받는 것으로 검증을 대신하지 않는다. 이번 가설 검증은 코드 경로와 격리 테스트의 assertions로 수행한다.

성능 개선 수치나 “고품질 몇 % 향상”은 이번 자료로 제시하지 않는다. 로컬에서 다음 수치를 실제로 수집해야 한다: 신규 프로필 조회 지연, 설정 저장 지연, 모델 선택 지연, 불필요 대체 호출 수, exact 선택 위반 수, 정책 리비전 혼합 수, 모호한 modelUsed 표시 건수. 이전 값과 비교할 표본이 없으면 개선 폭은 근거 부족이다.

---

## 16. 적용·되돌림·남은 위험

### 16.1 적용 전 확인 명령

아래는 실제 로컬 checkout에서 수행할 명령이다. 이 문서를 생성한 환경에서 사용자 checkout에 실행한 결과가 아니다.

```powershell
git rev-parse --show-toplevel
git rev-parse HEAD
git status --short
```

이후 실제 루트에서 Gradle 파일·Wrapper·sourceSet·mainClass·관련 Test task만 확인한다. `tasks --all`은 존재하는 Wrapper에서만 실행한다. 스냅숏에 빌드 파일이 없다는 사실을 사용자 저장소에도 없다고 확대하지 않는다. 다른 에이전트의 작업 리스·저널을 확인하고 파일 담당 범위를 나눈다.

추가 증거 목록:

| 필요한 자료 | 확인 이유 | 없을 때 처리 |
|---|---|---|
| 현재 HEAD와 관련 파일 diff | 이 ZIP 이후 수정·이미 완료된 패치 확인 | ZIP 행 번호를 새 소스에 그대로 적용하지 않음 |
| 실제 build.gradle(.kts), settings.gradle(.kts), Wrapper 설정 | 버전·sourceSet·테스트 task | 빌드·테스트는 NOT_RUN; 임의 빌드 생성 금지 |
| API_ROUTING_SPEC §6 및 현재 INV/DONE/HOLD | 신규 키·API·이유 코드 이름과 결정 보존 | 명칭은 초안; 해당 추가 선언 적용 전에 대조 |
| 민감 값 제외한 활성 프로필·등록 경로·runtime bean 정보 | 구성과 실제 실행의 차이 확인 | 임의 기본 모델·GPU·공급자 권한 추정 금지 |
| 기존 model-selection·fallback·session-owner 회귀 테스트 | 현재 비정확 선택·정확 선택·특수 경로의 계약 보존 | 해당 seam부터 특성 테스트 작성 |
| 사용자 본인의 정제된 1회 실행 메타데이터 | configured/selected/observed/final origin 실제 대응 | 실제 모델 사용·활성화를 사실로 보고하지 않음 |

키·토큰·쿠키·대화 원문·전체 로그·비공개 그래프 제출은 요구하지 않는다. 운영 확인을 위해 사용자의 실제 세션에 테스트 메시지를 주입하지 않는다.

### 16.2 적용 순서

WP1의 계약·미리보기 → WP2의 저장 → WP3의 실제 소비 → WP4의 실제 관측 → WP5의 UI 연결 순서다. 도중에 다음 WP의 대규모 작업을 섞지 않는다. 각 단계 RED와 GREEN의 실제 종료 코드·실행 테스트 수를 기록한다.

신규 runtime gate는 false, 신규 프로필도 false, 추가 paid 허용 false, 추가 비용 상한 0을 유지한다. mock 종단 검증 뒤 로컬에서 명시적으로 활성화한 경우만 활성 배정을 관측한다. 이 지시서는 실호출 비용을 허가하지 않는다.

### 16.3 되돌림

코드 변경을 되돌리기 전, 현재 실행이 참조하는 리비전과 처리 상태를 확인한다. 진행 중인 Run의 스냅숏을 강제 수정하거나 원본 대화를 삭제하지 않는다.

서버 정책 되돌림은 현재 expectedRevision/hash에 대해 이전 정책 내용으로 새 revision을 저장하는 방식이다. revision을 과거 숫자로 낮추거나 캐시만 수동 바꾸지 않는다. 이전 정책 사본은 허용된 typed 정책 항목만 보관하며 비밀값·owner 전용 OAuth 자료를 포함하지 않는다.

runtime gate 비활성화는 명시적인 운영 되돌림이다. 정책 저장소 장애를 숨기기 위한 자동 우회로 사용하지 않는다. 비활성화 후의 기존 경로가 현재 운영 요구에 맞는지 확인한다.

코드 되돌림은 이번 작업이 수정한 정확한 파일·hunk에 한정한다. 기존 미커밋 변경과 다른 에이전트 파일은 보존한다. `git reset --hard`, `git clean`, 무조건적인 `git restore .`, 강제 push는 금지한다. 새 파일도 이번 작업 소유 여부를 확인한 뒤 제거한다.

### 16.4 남은 위험

가장 큰 위험은 UI가 아니라 **여러 장애 대체 분기와 비동기 호출에 역할 제약이 빠지는 것**이다. 특히 factory 오버로드, CallArgs 복사, 반환된 모델 객체의 실제 호출, RC 로컬 대체, 재연결 시 context 재생성을 집중 검사한다.

현재 보안의 일반 `/settings` 공개 여부와 POST/GET 차이는 UI만으로 해결되지 않는다. 보안 변경 0이라는 이번 경계를 유지하면 일반 인증 구성에서 익명 `/settings` 접근은 그대로 제한될 수 있다. 이 사실을 숨기고 모든 환경의 200 응답을 완료 기준으로 삼지 않는다.

미확인 능력·잔액·비용·GPU 상태·응답 모델은 null로 남긴다. 이 null을 기본 거부나 임의 허용으로 일반화하지 말고 기존 실행 정책과 새 명시 override의 검증을 구분한다.

---

## 17. 수용 기준과 최종 보고 형식

| 기준 | 완료를 판단할 증거 |
|---|---|
| R1. 실제 여섯 역할 편집 | 각 역할의 저장값이 실제 mock factory 호출 인자에 반영되는 테스트 |
| R2. 명시 모델 보존 | strict 오류·OAuth 순서·auto literal 기존 계약 회귀 |
| R3. 대체 목록 준수 | gateway·device·contention·RC 대체 모두 목록 밖 호출 0 |
| R4. 저장·적용 분리 | commit/read-back 및 특정 Run의 profileRevision 연결 |
| R5. 실행 리비전 고정 | 저장 도중 재연결·병렬 lane에도 동일 snapshot |
| R6. 정직한 실제 모델 표시 | responseModelId의 관측 근거, 최종 answer origin, unknown 분리 |
| R7. 미리보기 부작용 없음 | generation/probe/refresh/write/bandit update 모두 mock 호출 0 |
| R8. 권한·비밀 보호 유지 | 기존 권한 결과 동일, 상세 조회 owner 격리, 응답 허용 필드 검사 |
| R9. 변경 범위 유지 | chat.js·schema·security·studio 변경 0, 메인 HTML 최소 변경 |
| R10. 기본 비활성 보존 | runtime off에서 신규 DB 의존·배정 변화 0 |
| R11. 실패 UX | 403·409·응답 유실·카탈로그 장애·늦은 응답 테스트 |
| R12. 증거 보고 | 실제 명령·종료 코드·테스트 수·미검증 항목이 따로 기록됨 |

Codex 최종 보고에는 변경 파일과 최소 diff, WP별 RED/GREEN 명령과 종료 코드, 실제 실행한 테스트 수, 실제 호출 수, 적용한 profileRevision, sourceSet과 HEAD, 남은 위험을 적는다. 정적 확인·단위 테스트·전체 빌드·서버 기동·사용자 기능 확인을 한 문장으로 합쳐 “정상”이라고 보고하지 않는다.

이번 문서 생성 단계에서는 **애플리케이션 소스 수정·실제 모델 호출·Java/Node 애플리케이션 테스트·운영 배포를 수행하지 않았다.** 제공한 테스트 명세 64개는 모두 NOT_RUN이다. 번들 검증은 문서·JSON·출처 anchor·파일 해시·ZIP 포장 검사에 한정되며 `evidence/package-verification.json`을 기준으로 한다.

이전 초안의 Node 테스트 18개 통과 보고를 이번 라우팅 확장의 통과 근거로 재사용하지 않는다. 이전 페이지 코드와 이번 추가 지시서를 결합한 새 테스트가 필요하다.

## 18. Codex에 붙여 넣을 시작 지시

```text
FIND_X_settings_routing_v2_ko.md를 먼저 읽고, 기존 설정 페이지 초안의 불변 조건과 보류 결정을 보존하라.
목표는 기본 모델 드롭다운 추가가 아니라, 실제 소비자가 있는 여섯 역할의 배정·대체 경계·실행 관측을 연결하는 것이다.
현재 checkout의 HEAD, build root, sourceSet, 작업 리스와 API_ROUTING_SPEC §6을 먼저 확인하라.
이 ZIP 이후 이미 해결된 부분을 다시 패치하지 마라. NEW는 이 문서의 명시적 신규 기능에만 사용하라.
WP1부터 WP5까지 순서대로 RED → 최소 수정 → GREEN을 수행하고, 미실행은 NOT_RUN으로 기록하라.
PolicyBasedModelRouter와 SelfAskPlanner 및 기존 gateway/fallback를 재사용하고 새로운 범용 라우터를 만들지 마라.
chat.js·DB 스키마·보안 정책·스튜디오·기존 범용 설정 API 계약은 변경하지 마라.
신규 runtime/프로필은 기본 비활성, 추가 paid=false, 추가 비용 상한=0을 유지하고 실제 외부 호출은 하지 마라.
설정 저장 성공과 Run에 적용된 리비전, 선택 경로와 실제 응답 모델, 모델 성공과 최종 저장 성공을 각각 구분하라.
작업 범위를 넘어선 수정·commit·push·reset·clean·운영 세션 테스트는 하지 마라.
결과는 실제 변경 파일, 최소 diff, 명령/종료 코드/실행 테스트 수, 검증된 결과와 남은 위험으로 보고하라.
```

**결정요인:** 기존 역할 소비자의 실재, 명시 선택·대체 경계의 보존, 실제 호출까지 추적되는 리비전·모델 식별. **확신:** 소스 구조 판독 높음 / 신규 설계 적합성 중간 / 로컬 적용·실행 결과 근거 부족.
