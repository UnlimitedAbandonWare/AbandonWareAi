| 입력 | 상태 | 기준·용도 |
|---|---|---|
| `FIND_X.zip` | 일부: 전체 ZIP 목록 확인, 설정·라우팅·RAG 관련 소스 선별 열람 | SHA-256 `d94c087f8d89eb2a8f14206c273b49ca82dbe3178cae464e483df8ecb39143ea`; HEAD=null |
| `UAW.txt` | 열림: 이번 설정 설계와 관계된 절을 선별 대조 | SHA-256 `a4ab90929e7c7541c3b4a9b28a60ed4234578d708f641d3479380ff15cffbe16`; 의도·소스 지도 |
| `Abandon_X.txt` | 열림: 이전 교정 내용과 현재 소스의 차이 확인 | 2026-09-23 작성된 참고 보고. 현재 구현 증거로 대체하지 않음 |
| `FIND_X_settings_routing_v2_ko.md`·v2 ZIP | 열림: 기존 불변·보류·저장 계약·작업 패키지 확인 | 이전 설계 초안. 구현 또는 테스트 완료 보고가 아님 |
| `FIND_X_settings_instructions_ko.md` | 일부: 앞선 페이지 초안의 존재·해시 확인 | 최초 제약은 대화의 명시 요구와 v2에 따라 보존. 이번에 전체 코드를 재검증하지 않음 |

# AbandonWare AI — UAW 의도와 현재 설정·라우팅의 연결을 정교화하는 지시서 v3 [초안]

기준: 2026-10-01 제공 소스, Asia/Seoul. 작성 성격: 설계·소스 수정 지시서. 실행 담당: 실제 checkout을 가진 로컬 코딩 에이전트. 이 문서 작성 과정에서는 원본 소스·설정·DB·운영 서버를 수정하지 않았다.

## 결론

**설정 페이지를 새 오케스트레이터로 만들지 말고, 기존 오케스트레이터가 무엇을 선택하고 실제로 무엇을 실행했는지 사용자가 조절·확인하는 화면으로 만든다.**

UAW에서 보존할 설계 의도는 세 가지다. 첫째, 가벼운 판단·질의 분해·근거 수집과 고성능 최종 생성을 역할로 나눈다. 둘째, 검색·정제·문맥 조립은 기존 경로를 재사용한다. 셋째, 기능이 켜졌다는 선언보다 현재 소비자와 실제 실행 증거를 보여준다. 이를 v2의 여섯 역할 배정·허용 대체 목록·Run 단위 정책 고정에 결합한다.

이번 개량의 한 줄 흐름은 다음과 같다.

**브라우저 선호·서버 역할 배정 → 기존 권한·요청 의도·Plan 조건 → 기존 Jev·검색·정제 경로 → PromptContext/PromptBuilder → 고정한 메인 모델 → 실행 결과·최종 저장 상태.**

이 화살표는 책임 관계를 설명한다. 모든 요청이 모든 단계를 실행하거나, 표시된 순서대로 새 실행 엔진을 돌린다는 뜻이 아니다. 실제 순서는 현재 호출 경로를 따른다. 특히 메인 모델은 문맥·출력 예산을 투영하기 전에 고정하는 현재 순서를 유지한다(S18).

### v2 대비 추가로 완성할 것

- 모델 역할 카드에 **언제 호출되는가, 무엇이 상속되는가, 왜 적용되지 않았는가**를 표시한다.
- 설정값·Plan 제안·유효값·관측값을 분리한다. ‘저장됨’과 ‘실행에서 확인됨’을 구분한다.
- 이미 연결된 Jev와 제한적 Plan 조건 평가·단계 기록을 재사용한다. 중복 분류기·새 DSL 실행기를 추가하지 않는다.
- 근거 정제·문맥 준비·생성·저장의 인계 결과를 기존 권한 안에서 조회한다. ‘고성능 모델 선택=전체 파이프라인 강화’라고 표시하지 않는다.

## 한계

FIND_X는 일반 파일 2,394개를 담은 `main/` 오버레이다. 이번에는 관련 심볼·호출부를 선별 조사했다. 전체 파일의 모든 메서드를 다시 전수 의미 분석하지 않았다. ZIP에는 실제 빌드 루트·Wrapper·sourceSet 정의·HEAD·현재 테스트 트리가 확인되지 않는다. 이 부재를 실제 로컬 저장소 전체의 부재로 확대하지 않는다.

Java 17·LangChain4j 1.0.1·기존 프로퍼티명 유지가 요구 조건이다. Spring Boot 3.3.4·Gradle 8.7은 사용자가 제공한 환경이며 이 ZIP만으로 버전을 독립 확인하지 못했다. 운영 활성 플래그, 실제 모델 제공 여부·가격·크레딧·동의 상태·GPU 배치는 근거 부족이다. 웹으로 이 빈칸을 대신 채우지 않았다.

`API_ROUTING_SPEC §6`과 로컬 INV/DONE/HOLD는 이 첨부에 없다. 아래 **신규 DTO 이름·설정 표시 식별자·테스트 이름은 명칭 초안**이다. 구현 전에 실제 문서와 대조하되, 불명확한 값은 null과 필요한 증거로 남긴다. 이 문서는 기존 결정 변경을 자동 승인하지 않는다.

이번 산출은 v2를 대체하는 전체 코드 묶음이 아니라 **v2의 구현 순서를 하나로 통합·보강한 수정 기준**이다. v2 문서와 계약 예시를 `reference/`에 원문 그대로 넣었다. 기존 v2 코드를 이미 적용했다면 hash·diff·테스트로 확인한 기능을 재작성하지 않는다.

---

## 1. 보존할 결정과 상태 판정

`DONE`은 해당 좁은 기존 코드·계약이 정적으로 확인됐음을 뜻한다. 신규 설정 페이지의 구현 완료나 운영 성공을 뜻하지 않는다. `PARTIAL`은 일부 연결만 확인되거나 새 화면 소비가 남은 상태다. `NEW`는 현 요청에 따라 의도적으로 추가할 기능이다. `CONFLICT`는 자동 채택할 수 없는 충돌이다. 별도 `HOLD` 표시는 그 항목의 작업을 보류한다는 뜻이며, 자료가 없다는 이유만으로 NEW로 분류하지 않는다.

| 항목 | 상태 | 지시 |
|---|---|---|
| 별도 `/settings`, 메인 `/chat` 중심 | PARTIAL | v1/v2 페이지 초안 재사용. 스튜디오를 참고·복사·수정하지 않는다. |
| chat.js·DB 스키마·보안·기존 API 계약 변경 금지 | DONE·불변 | 예외를 임의 생성하지 않는다. |
| 네 DOM 컨트롤 브리지 | PARTIAL | 브라우저 기본값만 반영. 새 역할·Plan·동의 값을 숨은 필드나 네트워크 가로채기로 전송하지 않는다. |
| MAIN_DEFAULT/FAST/HIGH와 SELFASK_BQ/ER/RC | NEW 또는 로컬 적용 시 PARTIAL | v2의 여섯 역할을 그대로 사용. 역할 enum을 다시 만든다면 기존 것이 없는지 먼저 확인. |
| strict 및 기존 복잡 요청 OAuth 우선순위 | DONE·정적 | S41의 순서를 보존. 신규 프로필·프리셋이 덮어쓰지 않는다. |
| 후보 허용 목록·대체 상한·Run 스냅숏 | NEW 또는 로컬 적용 시 PARTIAL | v2 계약을 그대로 구현·회귀 검증. |
| 설정의 출처·효과·실제 실행 보기 | NEW | 이번 v3의 핵심 UI/조회 보강. 별도 정책 저장소는 만들지 않는다. |
| Jev를 메인 검색 앞단에 새로 추가 | CONFLICT | 이미 S01~S06 경로가 있으므로 중복 추가 금지. 기존 지원 상태를 보여준다. |
| 모든 Plan 키를 설명 전용으로 판정 | CONFLICT | 제한적 when 소비·stageLedger가 있으므로 S09~S16으로 구분한다. |
| Plan YAML을 범용 실행 엔진으로 노출 | CONFLICT | 현재 지원 범위 밖. YAML 편집기·드래그 실행 순서 편집기 추가 금지. |
| 고성능 선택 시 context_prepare·자동 학습·전체 검색 자동 ON | CONFLICT·HOLD | 기존 호출 조건·권한·동의 계약을 바꿀 수 없다. |
| 원본 UAW/Abandon_X 자동 덮어쓰기 | CONFLICT | `Abandon_X_settings_routing_v3_addendum.txt`를 별도 제안으로만 제공. |

새 기능 기본 off, 추가 유료 허용 false, 신규 ZDR 기본 false, 새 추가 호출·비용 상한 0을 유지한다. **이 값들을 기존 서비스 전역 설정에 덮어쓰지 않는다.** 기존 기능의 유효 설정은 그대로이며, 신규 off 경로는 기존 실행에 새 DB·네트워크 의존을 추가하지 않는다. ZDR=false는 ZDR을 요구하지 않는 기본 정책일 뿐, 학습·보존·외부전송 동의가 자동으로 확보된다는 뜻이 아니다.

## 2. UAW 대조표 — 살릴 의도, 반복하면 안 되는 패치

아래 UAW 줄 번호는 이번에 읽은 원본 바이트의 줄 기준이다. Sxx는 §17과 `evidence/source-anchors-v3.json`의 현재 FIND_X 경로·범위·해시를 가리킨다. UAW 문장에 있는 ‘완벽·100%·3배·90%’는 성능 측정 증거로 쓰지 않는다.

| ID | UAW 위치 | 보존할 의도·검토할 주장 | 판정 | 현재 근거 | 이번 조화 방식 |
|---|---|---|---|---|---|
| U01 | `UAW.txt:1720-1742` | 경량 준비와 고성능 최종 생성의 역할 분담 | PARTIAL | S01,S41,S42 | 기존 여섯 역할 배정을 구현하며 Jev는 별도 신호 카드로 표시. 신규 심판 모델 호출을 자동 추가하지 않음. |
| U02 | `UAW.txt:1834-1839;2586-2589` | 정제 근거를 PromptContext에서 PromptBuilder로 인계 | DONE | S17,S18,S19,S21 | 기존 문맥·프롬프트 경로 재사용. 설정 화면은 역할과 실제 인계 결과를 투영. |
| U03 | `UAW.txt:838-927;3686-3703` | Plan 힌트·선언형 실행에 관한 상반된 설명 | PARTIAL | S09,S10,S11,S12,S13,S14,S15,S27 | when 제한 소비와 ledger를 보존. broad 실행기 신설도, 기존 조건 평가 삭제도 하지 않음. |
| U04 | `UAW.txt:646-729` | FailurePattern/Zero100 imports 추가 지시 | DONE | S34 | 이미 import 행 존재. 재추가 금지. 운영 빈 활성은 NOT_RUN. |
| U05 | `UAW.txt:731-836` | RuleBreak 생산자 등록 지시 | DONE | S33 | 조건부 등록 코드 존재. 재등록 금지. 실제 빈 생성·요청 전파는 별도 검증. |
| U06 | `UAW.txt:1810-1839;2187-2208` | 조건부 확장과 앵커 응축 | PARTIAL | S02,S03,S10,S15,S26 | 확장 허용과 모델 배정을 분리. role target 저장만으로 확장을 켜지 않음. |
| U07 | `UAW.txt:3373-3382;3551-3556` | 동적 K 분배 | PARTIAL | S24,S25,S26 | 현재 확인한 적용은 burst 제한. 나머지 suggested 값은 추천으로 표시하고 서버 전체 K 제어로 포장하지 않음. |
| U08 | `UAW.txt:2407-2416;3635-3642` | Bi-Encoder·DPP·ONNX 정제 | PARTIAL | S28,S29 | canonical 호출을 재사용. 파일·빈·요청 조건·실행을 각각 표시. |
| U09 | `UAW.txt:3267-3285` | Matrix 기반 문맥 분량·기여도 | PARTIAL | S30,S31,S17 | 동명이인 구현 구분. 호출 증거 없는 UI 가중치 편집이나 Display 줄 수와의 혼합 금지. |
| U10 | `UAW.txt:3115-3209` | ArtPlate 진화와 상태 개선 | PARTIAL | S32 | proposeWithSse·AtomicReference 존재. 과거 패치 반복 금지, 설정 미리보기에서 decide/propose 호출 금지. |
| U11 | `UAW.txt:2913-2928;2806-2832` | 요청 전체 예산과 취소 신호 | PARTIAL | S02,S03,S08,S26,S36 | 기존 Run·기한·취소 계약 유지. 미리보기 실호출 0, 늦은 결과 저장 금지. |
| U12 | `UAW.txt:1940-1945;3454-3502` | 결정 신호의 제어·관측 이중 활용 | PARTIAL | S16,S22,S23,S44 | 기존 실제 관측을 typed view로 연결. 가상 성공 확률이나 새 로그 서버를 만들지 않음. |
| U13 | `UAW.txt:2151-2167;1108-1184` | 대화 응답과 학습 수용 기준 구분 | PARTIAL | S38,S36 | 학습 필터·동의·저장 결과와 응답 품질을 분리. 전역 0.90 승격 금지. |
| U14 | `UAW.txt:3024-3042` | 기억·벡터·관계 근거 조합 | PARTIAL | S35,S36,S37,S19 | 기억 삭제·동의·owner 계약 유지. 새 전역 그래프/기억 서비스 추가하지 않음. |
| U15 | `UAW.txt:2067-2101;2378-2393` | 비대칭 GPU·임베딩 역할 분리 | PARTIAL | S37,S41 | 논리 endpoint 배정과 실제 GPU를 구분. GPU·차원 드롭다운은 추가하지 않음. |
| U16 | `UAW.txt:2019-2052` | 버전 순수성과 프로토콜 어댑터 재사용 | PARTIAL | S39,S40,S41 | 기존 factory와 선택기를 유지. 실제 dependency graph는 로컬 확인 필요. |
| U17 | `UAW.txt:2445-2489` | 무음 실패 드러내기와 장애 복구 | PARTIAL | S07,S08,S22,S29,S36 | 권한·예산·의존성·시간초과를 별도 이유로 표시. 프로세스 강제 종료나 새 생성 요청을 기본 복구로 추가하지 않음. |
| U18 | `UAW.txt:3041-3042;3584-3587` | 기존 코드 수정 제로라는 설명 | CONFLICT | S01,S18,S41,S42 | 현 요청의 최소 consumer 수정 원칙을 유지. 수정 제로를 위해 중복 라우터를 만들지 않음. |

### 2.1 이전 교정문까지 현재 소스와 다시 대조한다

`Abandon_X.txt`도 2026-09-23의 정적 보고다. 다음 항목은 FIND_X와 차이가 있으므로 과거 지시를 그대로 실행하면 안 된다.

- RuleBreak: 이전 문서는 미등록으로 적지만 FIND_X의 `WebMvcConfig.addInterceptors()`에는 빈이 존재할 때 등록하는 코드가 있다(S33). 다시 등록하지 않는다. 빈이 실제 없으면 등록하지 않는 분기도 있으므로 운영 실행 완료라고 단정하지 않는다.
- 자동설정: FailurePattern·Zero100 import는 현재 존재한다(S34). import 수가 과거 보고와 다르다는 이유로 과거 여섯 행을 덮어쓰지 않는다.
- DPP: `UnifiedRagOrchestrator`가 canonical `service.rag.rerank.DppDiversityReranker`를 사용하는 코드가 있다(S28). 전부 dormant라고 묶거나 새 재랭커를 추가하지 않는다. 해당 요청에서 실행했는지는 별도다.
- ArtPlate: UAW가 미래 개선으로 설명한 `proposeWithSse`와 AtomicReference 기반 상태가 이미 있다(S32). 다만 현재 read→compute→set 전체가 CAS 전이로 구현됐다는 뜻은 아니다. ‘동시성 완전 해결’로 승격하거나 이번 설정 작업에 별도 동시성 리팩터링을 끼워 넣지 않는다.

## 3. 현재 연결에서 가장 중요한 여섯 가지 구분

### 3.1 Jev 연결은 이미 있으며, 사용 가능 상태는 여러 조건의 교집합이다

`RetrieverChainConfig.retrievalHandler()`는 fixed와 dynamic 양쪽에서 `JevRetrievalGateHandler.wrapIfEnabled()`를 호출한다(S01). 래퍼 생성은 surface·choice·prefetch·seam 플래그에 의존한다(S02). advisor와 runtime 자체도 조건부 빈이다(S04).

따라서 Environment나 DB에 값을 저장한다고 기존 빈 그래프에 래퍼가 저절로 추가되는 것은 아니다. **동적 재설정 계약을 확인하지 못한 Jev 활성화 값은 이번 페이지에서 읽기 전용**으로 둔다. ‘설정은 ON, 현재 연결 없음, 서버 재기동 여부 확인 필요’를 표시할 수는 있지만, UI 저장만으로 활성화됐다고 표시하지 않는다.

Jev의 search-need·complexity 판단은 기존 QuestionKey·기한·privacy·Run 확인과 함께 소비한다(S03). 새 설정 조회나 모델 카드 렌더링 때문에 다시 분류를 호출하지 않는다. 실제 런타임 admission을 생략해 비용을 우회하지도 않는다.

### 3.2 Plan의 선언·제한 조건·실행 기록은 다른 것이다

`PlanHintApplier`는 일부 수치 힌트를 적용한다(S10). `PlanExecutionSpec`는 제한된 조건을 평가하고 stageLedger를 만든다(S12~S15). 반면 broad DSL 미사용 진단은 계속 남는다(S11·S27). 둘은 반드시 오류 관계가 아니다.

설정 화면에 표시할 상태는 다음과 같다.

| 구분 | 의미 | 화면 행동 |
|---|---|---|
| 선언됨 | YAML에 단계가 있다 | 사용됐다고 표시하지 않음 |
| 조건 판정 TRUE/FALSE/UNKNOWN | 기존 평가기가 관측 가능한 조건을 평가 | UNKNOWN을 false 또는 true로 조작하지 않음 |
| ENABLED | 실행 가능 표식 | 실제 호출 성공과 구분 |
| EXECUTED | 기존 ledger에 실행 근거가 있음 | 어떤 근거에서 왔는지 유지 |
| DELEGATED | 호출자가 담당하는 단계 | prompt/build·answer/generate 성공으로 자동 변환하지 않음 |
| SKIPPED/FAILED/UNAVAILABLE | 기능 OFF·조건 불충족·의존성·오류 등 | 원인별 안내와 실제 허용 행동 제공 |

표의 대문자는 기존 Java enum을 설명한다. 실제 ledger의 소문자 상태와 `skipped_flag_off`, `skipped_when_inactive`, `skipped_dependency`, `skipped_duplicate` 세부 코드를 보존하며, UI 번역만 적용한다. 기존 직렬화 값을 새 enum으로 덮어쓰지 않는다.

후반에 조건이 UNKNOWN→TRUE가 됐다는 `lateActivation` 기록만으로 이미 끝난 검색을 재실행하지 않는다. 기록은 관측이지 명령이 아니다.

### 3.3 추천한 검색 예산과 실제 적용 예산은 다르다

`RetrievalBudgetGovernor`는 여러 K와 확장 관련 값을 반환한다(S24~S25). 이번에 확인한 `SelfAskWebSearchRetriever` 호출부는 `queryBurstCount`를 제한에 사용한다(S26). 따라서 그 반환 객체의 web/vector/kg 값까지 전부 적용된다고 표시할 근거는 없다.

화면에는 `추천 검색량`과 `실제 사용 검색량`을 별도 표시한다. 최종 후보 수, 외부 호출 횟수, 질의 개수는 단위가 다르므로 한 숫자로 합치지 않는다. 이번 v3는 새 전역 K 슬라이더를 추가하지 않는다. 실제 모든 소비자에 적용하는 범위가 별도 승인·검증되기 전에는 기존 관측과 v2 역할별 호출 상한만 제공한다.

### 3.4 문맥 준비는 모델 선택만으로 켜지지 않는다

현재 `evidence_pack` 경로는 명시적인 요청, 비strict, 이미지 없음, 로컬 모델, Plan 허용, when=TRUE, 소스 현재성 등의 조건을 검사한다(S20). UAW의 ‘경량 준비+강한 최종 생성’ 설명을 근거로 이 조건을 풀거나 원격 상위 모델 경로까지 자동 확대하지 않는다.

chat.js 수정 0 및 네 컨트롤 제한 아래서는 새로운 `contextPreparationRequested` 전송을 몰래 추가할 수 없다. 따라서 이번 UI는 **문맥 준비의 지원 조건·이번 실행의 생략 이유를 표시**한다. 해당 기능을 활성화하는 새 브라우저 토글은 HOLD다. 일반 PromptContext/PromptBuilder 경로는 그대로 사용한다(S18·S21).

### 3.5 채팅·학습·기억·Display는 같은 저장 범위가 아니다

대화 생성 성공이 학습 샘플 채택이나 장기기억 저장 완료를 의미하지 않는다. 기존 학습용 필터는 별도 namespace와 scope를 가진다(S38). 최종 기억 저장은 기존 Run의 commit·취소 fencing을 따른다(S36). FocusMemoryScope의 consent/index revision을 서버 역할 프로필이나 브라우저 설정으로 재생성하지 않는다(S35).

MAIN_HIGH를 바꿨다고 Display의 STT·힌트 모델도 바뀌게 만들지 않는다. 새 프로필은 v2의 메인 채팅 여섯 역할에만 적용한다. Focus/cue 상태는 같은 의미의 public 문자열로 합치지 않고 현재 surface를 명확히 표시한다.

### 3.6 ‘고성능’과 ‘많은 호출’은 같은 설정이 아니다

고성능 역할은 기존 승격 분기가 선택됐을 때 사용할 모델 배정이다. 고성능 역할을 지정했다고 검색 단계 수·모델 수·temperature·학습 기준을 함께 올리지 않는다. bandit weight를 호출 비율로, 신뢰 점수를 사실 정확도 확률로, endpoint 별칭을 실제 GPU 번호로 표시하지 않는다.

## 4. 화면 설계 — 모델 선택과 실행 구성을 한 화면에서 이해하게 한다

기존 좌측 카테고리와 메인 `/chat`의 시각 언어는 유지한다. 새 관측 기능을 위해 스튜디오 코드를 가져오지 않는다. 바닐라 JS·CSS·Thymeleaf만 사용한다.

```text
설정                              [설정 검색] [채팅으로 돌아가기]

일반              모델·라우팅
모델·라우팅        [기본 모델] [역할별 배정] [실행 구성] [적용 결과]
검색·RAG
답변·진단          이 브라우저: 저장됨
디스플레이         서버 역할 정책: r12 저장됨 / 다음 실행 대기
데이터·기록        선택한 기존 실행: r11 / 완료
서버 설정
                  역할             배정          적용 조건        이번 결과
                  메인 기본        상속          기본 분기        사용됨
                  메인 경량        등록 경로      경량 분기        미선택
                  메인 고성능      등록 경로      승격 분기        미선택
                  Self-Ask BQ      등록 경로      분해 허용        생략
                  Self-Ask ER      상속          분해 허용        생략
                  Self-Ask RC      등록 경로      반례 분기        생략

                  판단 신호        상태          역할
                  Jev              미관측        검색 필요·복잡도 보조
                  Plan             일부 지원     조건·수치 힌트·단계 기록
                  문맥 준비        조건 불충족   기존 근거 묶음 준비

                  [변경 미리보기] [다음 실행부터 적용] [편집 취소]
```

### 4.1 역할 카드의 필수 정보

각 역할 카드는 다음 여섯 영역을 갖는다.

| 영역 | 구체 정보 | 정보 출처 |
|---|---|---|
| 역할 | 실제 소비자와 호출 조건 | S41·S42 및 v2 consumer mapping |
| 배정 | 상속/등록 경로/정확 모델·endpoint 식별자 | 기존 카탈로그·v2 프로필 |
| 대체 | 순서 있는 허용 후보·추가 호출 상한 | v2 정책. 전체 후보로 조용히 확장 금지 |
| 매개변수 | 요청값·정규화된 값·적용 출처 | 기존 ModelCapabilities/ParamMatrix·factory 경로 |
| 제약 | 검색·privacy·strict·예산·scope 때문에 제외된 이유 | 서버가 검증한 기존 정책 |
| 결과 | 선택·시도·응답 관측·최종 채택·저장 | 기존 Run에 귀속된 실제 결과 |

상속 버튼은 하드코딩된 예전 모델 이름을 저장하지 않고 **해당 binding override를 제거**한다. 상속 후 어느 값이 적용될지 미리보기를 제공한다. 자기 역할만 재설정하며 다른 역할·브라우저 기본값·서버 보안 설정을 건드리지 않는다.

모델 목록이 갱신되더라도 저장된 선택을 자동으로 첫 항목으로 바꾸지 않는다. 삭제·비활성·권한 변경 등으로 선택이 유효하지 않으면 원래 식별자와 이유를 표시하고 사용자가 해결하도록 한다. API 오류를 이유로 모델 alias를 임의 canonicalize해 다른 모델로 대체하지 않는다.

### 4.2 ‘실행 구성’ 탭은 읽기 투영이며 실행 편집기가 아니다

다음 일곱 책임을 표시한다.

| 책임 | 현재 연결 | 쓰기 범위 |
|---|---|---|
| 요청 의도·분기 | 기존 검색 선택·복잡도 분기 | 기존 브라우저 선택만 |
| 경량 판단 | Jev의 이미 연결된 seam | 이번 v3에서는 읽기 |
| 하위 질문 | Self-Ask BQ/ER/RC | v2의 등록 모델 배정·대체만 |
| 근거 수집·정제 | 기존 검색 체인·재랭커·앵커·예산 | 현재 값·추천/관측 읽기 |
| 문맥 조립 | compressor·attribution·PromptBuilder | 읽기. 임의 프롬프트 편집 없음 |
| 최종 생성 | MAIN 역할·정확 선택 | v2의 역할 배정·대체 |
| 기록 | 기존 대화/기억/학습/Display 결과 | 상태 읽기. 실행·삭제·학습 자동 시작 없음 |

해당 기능이 서버에 없으면 ‘지원되지 않음’, 빈은 있지만 기능이 꺼져 있으면 ‘비활성’, 권한이 부족하면 ‘잠김’, 실행 근거가 없으면 ‘미관측’으로 표시한다. 화면에서 모든 항목이 성공한 것처럼 보이도록 기본값을 채우지 않는다.

### 4.3 역할 조합 초안 — 편집 편의를 높이되 두 번째 정책 엔진은 만들지 않는다

선택 편의를 위한 ‘역할 조합 초안’을 제공할 수 있다(NEW). 예를 들어 ‘현재 설정 유지’는 모든 서버 binding을 상속으로 두고, ‘준비·생성 역할 분리’는 사용자가 선택한 경량 경로를 BQ/ER에, 별도로 선택한 경로를 MAIN_HIGH에 폼으로 배치한다.

이는 즉시 실행하는 프리셋이나 별도 DB 레코드가 아니다. 로컬 카탈로그에 확인된 경로만 사용하고, 모델 이름을 문서에 고정하지 않는다. 선택 시 바뀌는 셀의 diff를 먼저 보여준다. RC, paid, ZDR, 검색 ON/OFF, 자동 학습, Display, source scope, strict 값은 초안이 임의 변경하지 않는다. **조합 선택은 폼 변경만, 서버 적용은 명시적인 저장 한 번**이다.

고성능 조합이 어떤 평가에서 우수하다는 실측이 없으면 ‘추천 최적값’이 아니라 ‘편집 출발점’으로 표시한다. 무작위 온도 섭동이나 운영 트래픽 A/B 활성화는 이 기능에 포함하지 않는다.

## 5. 설정 인벤토리의 읽기·쓰기·범위를 명확히 고정

| 설정·정보 | 저장 위치 | 범위·권한 | 적용/관측 연결 | v3 판정 |
|---|---|---|---|---|
| 기본 모델 choiceId | `awx.settings.v1.*` 기존 namespace | 브라우저 | 기존 #modelSelect + change | PARTIAL·유지 |
| preferred/strict/auto | 동일 | 브라우저 | #modelSelectionMode + change | PARTIAL·유지 |
| 기본 검색 선택 | 동일 | 브라우저 | #searchModeSelect + change | PARTIAL·유지 |
| RAG 기본값 | 동일 | 브라우저 | #useRagToggle + change | PARTIAL·유지 |
| 여섯 서버 역할 배정·대체 | 기존 ConfigurationSetting의 v2 단일 JSON 행 | 서버 전체·기존 ADMIN 경계 | v2 RoutingProfileResolver 및 호출자 | NEW 또는 기존 적용분 보강 |
| 정책 revision/hash·상속 출처 | v2 행·Run 스냅숏 | 기존 권한 | 저장 후 재조회와 Run 사용 기록 | PARTIAL |
| 등록 모델의 현재 연결 식별 | 기존 카탈로그/서버 구성 읽기 | 공개/상세 권한을 분리 | 기존 선택·factory | 읽기 확장 |
| 역할별 sampling·token-param | 기존 모델 정책·factory 읽기 | 상세 권한 | S39·S40, 소비경로 추가 확인 | 읽기 확장 |
| Jev main/focus/cue 상태·reason | 기존 Environment·runtime.status | 기존 ADMIN 상세 화면 | S04~S08 | 읽기 확장 |
| Jev confidence·wait·timeout | 기존 프로퍼티 | 서버 관리 | 정확한 소비자별 값 표시 | 쓰기 HOLD |
| Plan ID·조건·단계 | 기존 Plan 및 현재 Run | 상세 권한 | S09~S16 | 읽기 확장 |
| QueryBurst·K 제안/적용 | 기존 예산 판단·trace | 현재 Run | S25~S26 | 읽기 확장 |
| DPP·ONNX 요청/실행/실패 | 기존 stage trace | 현재 Run | S28~S29 | 읽기 확장 |
| 문맥 기여·근거 건수 | 기존 compressor/attribution/snapshot | 현재 Run | S17~S23 | 읽기 확장 |
| context_prepare 활성 요청 | 기존 ChatRequest·Plan 조건 | 요청·scope | S20 | 신규 토글 HOLD |
| 원본/최근 문맥/장기기억/학습 상태 | 기존 저장기별 상태 | owner/session/channel/동의 | S35~S38 | 읽기, 새 저장 정책 없음 |
| 임베딩 모델·차원·정규화 | 기존 fingerprint | 서버 관리 | S37 | 읽기. 모델 교체·재인덱싱 HOLD |
| GPU 실배치 | 실제 운영 증거 필요 | 서버 관리 | 이번 ZIP만으로 근거 부족 | 미관측 |
| 브라우저 설정 내보내기·가져오기 | localStorage 허용 목록만 | 브라우저 | 기존 v1/v2 검증 | PARTIAL·유지 |
| 복구 초안 지우기 | 기존 `sessionStorage`의 정확한 키 | 현재 탭 | v1 초안의 제한 유지 | PARTIAL·유지 |
| 시스템 프롬프트·서버 기본 모델 | 기존 API·마스킹 계약 | 기존 관리자 보호 | v1/v2에서 확인한 잠금 보존 | HOLD 유지 |

**편집 가능한 항목만 저장 문서에 들어간다.** 설명 카드의 현재 값, Jev 결과, Plan 조건, 원본 trace, 서버 설정 출처는 browser import/export나 역할 binding JSON에 섞지 않는다.

## 6. 데이터 계약 — 정책은 v2 한 개, 관측은 별도 읽기 뷰

### 6.1 영속 저장 스키마를 또 늘리지 않는다

v2의 `CHAT_ROUTING_PROFILE_V1` 명칭 초안과 schemaVersion=1을 그대로 사용한다. 새 테이블·컬럼·@Version·별도 UAW profile 행을 추가하지 않는다. 기존 JSON에 임의 Jev/Plan/기억 키를 끼워 넣는 것도 금지한다.

v2의 최대 16KiB, 여섯 역할, 역할당 대체 최대 3개, 식별자 최대 256자, 중복 키·미지원 필드 거부 계약을 유지한다. 이 수치는 새 설정 문서 구조의 설계 한도이며 모델 성능·컨텍스트·유료 한도가 아니다.

### 6.2 읽기 뷰에만 설명 필드를 추가한다

기존 v2 신규 컨트롤러의 read 응답에 별도 `settingsView`/`pipelineView`를 추가하는 설계를 사용한다. 현재 `/api/settings`의 기존 응답 DTO를 수정하지 않는다. 다음 정보는 브라우저에 표시할 수 있도록 서버에서 선별 투영한다.

```text
SettingDescriptor
  id                  화면용 안정 식별자
  kind                role_binding / signal / conditional_hint / observation
  editable            현재 권한·지원 계약 아래 실제 저장 가능한가
  configuredValue     허용된 비밀값 없는 구성값, 미확인은 null
  effectiveValue      특정 소비자에 전달한 유효값, 미관측은 null
  observedValue       실행 결과에서 실제 확인한 값, 미관측은 null
  sourceKind          browser / role_profile / existing_config / plan_hint / runtime
  sourceKey           허용 목록에 든 기존 key 이름만
  appliedScope        browser / server / run / surface
  runtimeAvailable    해당 런타임 연결을 확인했는가, 미확인은 null
  applyTiming         browser_load / next_run / restart_required / read_only
  reasonCode          정제된 기존 이유 코드 또는 명칭 초안
```

이것은 새 범용 기능 레지스트리·설정 저장 서비스가 아니다. 이번 페이지에 필요한 소수의 명시적 descriptor를 기존 카탈로그·v2 resolver·Plan snapshot에서 만드는 **읽기 투영**이다. Environment 전체 덤프, 임의 key 조회 API, 클래스패스 전체 자동 토글 생성은 금지한다.

`runtimeAvailable=true`는 요청이 실제로 그 단계를 실행했다는 뜻이 아니다. `configuredValue=true`를 `observedValue=true`로 복사하지 않는다. 같은 프로퍼티라도 Plan·요청·모델 정규화에서 달라졌다면 두 값을 보이고 바뀐 이유를 남긴다.

### 6.3 API 수는 v2에서 늘리지 않는다

- `POST /api/settings/routing/read`: 정책·정제된 capability·권한 있는 기존 실행의 관측.
- `POST /api/settings/routing/preview`: 합성 시나리오 또는 명시적으로 제공한 입력에 대한 무호출 검증.
- `POST /api/settings/routing/save`: 기존 v2 역할 문서만 원자적 저장.

세 경로는 v2의 명칭 초안이다. 실제 `API_ROUTING_SPEC §6`과 대조한다. v3를 위해 `/api/uaw/settings`·새 디버그 서버·전역 이벤트 버스를 추가하지 않는다.

settings GET은 공개, POST는 ADMIN인 현재 매핑 차이를 유지한다(S43). GET 성공이나 localStorage 플래그로 관리자 판별하지 않는다. 신규 POST가 401/403이면 서버 정책 블록만 잠그고 브라우저 설정은 계속 사용 가능하게 한다. PROTO_OPEN과 일반 실행의 기존 권한 차이는 v2 테스트대로 보존한다.

## 7. 정책 우선순위와 Run 고정 — 한 줄 순서표로 뭉치지 않는다

### 7.1 모델·기능·권한은 독립된 축이다

권한·동의·privacy·취소는 기존 서버 검증이 결정한다. 브라우저 설정이나 UAW 조합은 권한이 아니다. 기능 실행 여부는 기존 검색 선택·RAG 선택·Plan 조건·현재 단계 판정이 결정한다. 모델 배정은 **그 역할이 실제로 호출될 때** 적용한다.

메인 선택의 구체 순서는 S41과 v2를 보존한다. exact 선택을 먼저 처리하고, 기존 복잡 요청 OAuth 조건을 그다음 처리하며, 그 외의 일반 tier 선택 경로에서 역할 배정을 적용한다. preferred를 strict처럼 표시하거나 고성능 프로필이 OAuth보다 무조건 먼저라고 설명하지 않는다.

Plan의 `expand.selfAsk.count`처럼 부가 enable override를 만드는 값도 있다(S10). 따라서 ‘역할 프로필은 기능을 켜지 않는다’는 불변을 **모델 배정 계층만 검사해서 통과시키면 안 된다.** 기존 요청의 명시 OFF와 현재 Plan 조건을 함께 회귀 검증하되, 기존 Plan 동작 전체를 이번에 재설계하지 않는다.

### 7.2 고정할 것과 매번 다시 검사할 것

| 고정할 것 | 다시 검사할 것 |
|---|---|
| v2 역할 정책 revision/hash | 현재 권한·동의 철회·소스 삭제/수정 |
| 그 Run이 실제 채택한 모델 배정·허용 대체 범위 | 취소·기한·남은 호출 예산 |
| 관측에 사용할 기존 설정/카탈로그/Plan 지문 | 허용된 후보의 현재 사용 가능성 |
| 메인 결정과 문맥·출력 예산의 동일 기준 | 실제 transport 응답의 모델 식별·오류 |

스냅숏이 고정됐다고 취소나 동의 철회까지 고정 이전 상태로 되돌리면 안 된다. 반대로 설정이 저장됐다는 이유로 진행 중 Run의 모델을 바꾸지 않는다. 이미 사용 중인 모델의 권한이 철회되면 기존 권한 정책대로 중단·축소하며, 새로운 모델로 무단 대체하지 않는다.

불변 정책은 v2의 기존 Run에 결합한다. 관측 ledger는 실행이 진행되며 늘어나는 별도 정제된 결과다. 둘을 하나의 mutable JSON으로 덮어쓰지 않는다. 같은 Run의 재연결은 기존 snapshot과 기록을 사용하며, 새 프로필 저장을 이유로 새 sessionId나 멱등성 키를 발급하지 않는다.

### 7.3 동시 저장·응답 유실·재기동

v2의 expectedRevision/hash, DB 행 잠금·최초 생성 경쟁·409·commit 이후 read-back을 유지한다. read 요청은 행을 만들지 않는다. 정책 조회 실패를 ‘행 없음’으로 해석하지 않는다.

서버 재기동 뒤 같은 정책 revision이 있어도 배포 구성·카탈로그·Plan 지문이 바뀌었으면 이전 미리보기를 현재 결과로 재사용하지 않는다. 초기 구현에서는 설정 조회 시 한정된 지문을 비교하고 ‘구성 변경, 다시 확인’으로 표시한다. 신규 자동 배포·분산 동기화·원격 정책 갱신 체계를 만들지 않는다.

## 8. Jev·경량 판단·고성능 생성의 조화

### 8.1 역할은 명확히 나누되 호출은 늘리지 않는다

Jev는 검색 필요·복잡도 등 현재 연결된 choice 판단의 보조 신호다(S01~S06). BQ/ER/RC는 기존 Self-Ask 질의 생성 역할이다(S42). 최종 자연어 답변은 메인 선택 경로다(S41). 제브 결과를 최종 생성 모델 명칭에 넣거나, 모든 BQ/ER/RC에 고성능 모델을 자동 배정하지 않는다.

UAW가 강조하는 ‘경량 준비+강한 최종 판정’은 **현재 존재하는 역할을 다르게 배정하는 설계 의도**로 반영한다. 매 질문 세 평가자나 별도 심판 모델을 호출하는 구조로 바꾸지 않는다. judge/coder/vision은 v2의 추가 consumer 확인 전 보류 결정을 유지한다.

### 8.2 제브 상태 카드가 보여줄 내용

기존 `JevEvaluationRuntime.status()`의 정제된 결과를 우선 사용한다(S07). main/focus/cue의 현재 상태와 global off 우선 규칙을 표시한다(S05). 다음은 서로 다른 이유다.

| 이유 | 의미 | 가능한 안내 |
|---|---|---|
| disabled | 기존 기능이 비활성 | 서버에서 관리; 새 역할 선택으로 켜지지 않음 |
| jev_not_configured | 기존 연결 구성이 준비되지 않음 | 키 값 노출 없이 서버 구성 확인 |
| auth_blocked / plan_gate | 인증·정책 admission 거부 | 현재 연결·권한 상태 확인 |
| rate_limited | 기존 런타임이 속도 제한 관측 | 이미 허용된 복구 정책을 따름 |
| budget_skip | 비용·무료 기간·일일 상한 등에 따른 생략 | 기다림이 아니라 해당 예산 설정 확인 |
| ready | 기존 admission 상태가 준비됨 | 성공 호출 또는 적용 완료로 표시하지 않음 |

S08의 무료 기간 기본 문자열은 **소스의 기본값**이지 현재 공급자의 무료 서비스 정책 증거가 아니다. 크레딧 잔액·계정 사용권·무료 제공 여부를 여기서 추론하지 않는다.

`decisionWaitMs`, transport timeout, 부모 절대 기한은 별도다. UI가 500ms 하나를 ‘전체 지연 500ms 보장’으로 표시하지 않게 한다. 새 프로필이 제브 요청 슬롯을 또 확보하거나, 이미 제출한 평가를 다시 제출하지 않도록 기존 handle을 재사용한다.

## 9. 검색 결과가 최종 모델에 들어가는 경로를 보이게 한다

### 9.1 새 프롬프트 빌더를 만들지 않는다

주력 경로는 기존 compressor(S17), `RagEvidenceAttributionService`(S19), `PromptContext`(S18), `PromptBuilder`(S21)를 사용한다. 설정 화면은 문서 내용을 직접 합쳐 프롬프트로 만들지 않는다. ‘AI용 설정 설명’이라는 이유로 역할 설정 JSON·관리자 정책·원시 진단을 모델 입력에 주입하지 않는다.

상위 모델에 전달되는 근거는 기존 sourceId/sourceRevision·분리된 web/rag/memory/localDocs 계약을 유지한다. 모델명이 바뀌어도 소스의 권한·수정·삭제 상태가 유지돼야 한다. 앞 단계가 성공했다고 이후에 삭제된 원본까지 유효한 것으로 취급하지 않는다.

### 9.2 문맥 인계 카드

```text
문맥 인계
  근거 수집:       기존 검색 단계에서 관측
  후보 정제:       실행 / 생략 / 실패 및 이유
  인용 가능 근거:  실제 attribution 결과, 미확인 시 —
  PromptBuilder:   호출 근거가 있을 때만 사용됨
  문맥 준비:       허용 조건 / 실제 사용 / 폐기 이유
  최종 입력 한도:  메인 결정에 사용한 기존 예산
  최종 생성 모델:  실제 응답 관측과 확인 수준
```

화면의 ‘최종 근거 건수’가 web+vector 합산 추정이면 기존 `finalContextCountSource=web_vector_estimate`를 그대로 표시한다(S22). 그 숫자를 실제 토큰화된 최종 프롬프트의 근거 수라고 이름 바꾸지 않는다.

원문 내용과 전체 프롬프트는 표시하지 않는다. 필요한 건수·단계·검증된 짧은 식별자만 제공한다. 별도의 ‘기여도 80%’ 같은 수치는 직접 산출·검증하는 코드가 없는 한 추가하지 않는다. 병렬 단계 시간의 합을 전체 지연으로 표시하지 않고, 측정 범위를 명시한다.

### 9.3 Matrix·DPP·ONNX는 현재 호출자별로 구분한다

MatrixTransformer는 서로 다른 구현이 있다(S30·S31). 이름만 보고 한 빈의 설정이 모두에 적용된다고 표시하지 않는다. 실제 main 경로의 compressor·attribution을 먼저 기준으로 삼는다. DPP와 ONNX도 요청이 어느 체인/오케스트레이터를 통과했는지에 따라 관측이 달라지므로, UnifiedRagOrchestrator의 존재만으로 모든 `/chat` 실행에 동일 순서를 그리지 않는다.

일반 설정의 글자 수·Display 줄 제한과 서버 입력 문맥·재랭킹 후보 수·임베딩 차원은 단위가 다르다. 서로 상한을 공유시키지 않는다. 임베딩 경로는 S37의 fingerprint 계약 때문에 별도 운영 검증 대상이며 일반 생성 모델 교체 UI에 묶지 않는다.

## 10. 저장·미리보기·실행 관측의 상호작용

### 10.1 저장

브라우저의 네 기본값은 기존 자동 저장·반영 계약을 유지한다. 서버 역할 배정은 여러 셀을 하나의 원자적 변경으로 저장한다. 편집 취소는 마지막 committed snapshot으로 폼을 되돌릴 뿐 서버 rollback을 호출하지 않는다.

저장 실패 시 폼은 유지하고 오류와 미저장 표시를 남긴다. 저장됐는지 불명확한 연결 단절은 read-back으로 확인한다. 409는 새 값을 보여주고 충돌 셀을 표시하며 자동 재저장하지 않는다. 로컬 JSON import도 서버 정책으로 자동 승격시키지 않는다.

### 10.2 미리보기

v2의 무호출 미리보기를 강화한다. 결과는 `입력 시나리오 + 확인된 구성 + 기존 순수 선택 규칙`의 예측이며 실제 응답 모델의 확정이 아니다.

금지되는 부수 효과:

- 모델 생성·실호출·warmup·설치·OAuth 토큰 갱신·Jev transport 호출.
- PlanHintApplier.load가 만드는 trace/cache 변경을 미리보기 요청의 정상 동작으로 묵인하기.
- NineArtPlateGate.decide/propose, bandit reward 관측, 자동 학습·재인덱싱·영구 정책 변경.
- 기존 Run 생성·/sync·Display bootstrap·실제 대화·기억 저장.

Plan은 이미 확보한 비밀값 없는 불변 spec 또는 합성 fixture로 평가한다. 필요하면 기존 parser의 순수 부분을 작은 함수로 추출하고 기존 load 동작은 특성 테스트로 고정한다. 미리보기를 위해 두 번째 Plan parser를 만들지 않는다. 관측이 없으면 UNKNOWN을 그대로 유지한다.

S32처럼 mutable 상태를 갱신하는 선택기는 실제 preview 엔진으로 호출하지 않는다. v2의 순수 후보 계산과 scenario input만 사용하며, 현 상태를 복사해 평가할 수 없는 경우 ‘후보 결정 미확인’으로 표시한다. 실제 조회 결과를 가장한 무작위 후보 선택은 금지한다.

### 10.3 실행 관측

v2 `RoutingOutcomeProjector`에 기존 pipeline snapshot을 조합한다. `ChatStreamSignalBuilder.buildPipelineSnapshot()`과 그 단계 allowlist를 재사용한다(S22·S23). 해당 메서드는 api 패키지 접근 범위를 가지므로 같은 `com.example.lms.api`의 신규 v2 컨트롤러에서 호출하고, 순수 결과를 projector와 합치는 방식이 우선이다. 접근 제한을 피하려고 동일 로직을 별도 패키지에 복사하지 않는다. 별도 작은 adapter가 정말 필요하면 먼저 이유를 적는다.

관측은 동일 Run에서 허용된 메타데이터를 캡처한 결과만 사용한다. 설정 페이지의 현재 요청 ThreadLocal `TraceStore`를 읽어 과거 실행을 재구성하지 않는다. 다른 세션 또는 전역 ‘마지막 성공’ 기록으로 대체하지도 않는다. 종료 기록이 없거나 Run TTL이 끝났으면 `not_recorded`로 표시하며 새 생성을 하지 않는다.

기존 `ChatSessionTraceRecorder`는 정제된 terminal 기록을 위한 코드다(S44). 운영 파일을 전부 스캔하는 새로운 공개 조회 API로 바꾸지 않는다. 조회 권한·TTL·삭제 후 동작은 v2의 기존 Run 접근 계약을 따른다.

## 11. 충돌·반례 시나리오

| 상황 | 기대 결과 | 하지 말 것 |
|---|---|---|
| 검색 OFF인데 고성능/BQ 모델이 지정됨 | 메인 모델 정책은 유지, 검색·하위 질문 실행은 기존 OFF 판정 준수 | 모델 지정이 검색을 강제 ON |
| AUTO 검색에서 제브 NONE 판단 | 기존 허용된 이유·confidence 조건일 때만 영향 | 제브가 모든 명시 검색 선택을 덮음 |
| 제브 on 구성이나 advisor/래퍼 없음 | 현재 연결 없음·지원 조건 안내 | 재설정 없이 즉시 적용 완료 |
| Plan 단계가 있지만 대응 consumer 없음 | unavailable/no_binding | 단계 문자열만으로 새 실행 |
| Plan when UNKNOWN, 나중에 TRUE | 실제 관측과 lateActivation 표시 | 끝난 단계를 브라우저가 재호출 |
| strict 메인 + MAIN_HIGH override | 기존 exact 모델 | 고성능 프로필을 이유로 다른 모델 |
| 역할 배정 변경 중 실행 진행 | Run의 기존 revision 유지 | 중간 모델 교체·원본 세션 교체 |
| Run 정책 고정 후 동의 철회/소스 삭제 | 현재 권한 재검증·기존 취소 fence | 오래된 snapshot으로 접근 지속 |
| ONNX 설정 on·의존성 없음 | 생략/대체 원인 표시 | ‘정밀 재랭킹 성공’ |
| Governor에서 webTopK 추천 | 추천 상태 표시 | 모든 retrieval API에 적용했다고 표시 |
| 생성 성공·기억 저장 실패 | 결과를 분리하고 허용 복구만 제시 | 생성 재시도로 저장 중복 |
| 고성능 조합 + context_prepare 승인 없음 | 기존 PromptBuilder 경로·준비 생략 이유 | local-only/strict/명시 동의 조건 삭제 |
| 임베딩 모델 이름만 같고 fingerprint 다름 | 별도 인덱스 적합성 확인 | 차원만 같으니 호환이라고 표기 |
| GUI에서 서버 설정 GET 200 | 권한 근거로 사용하지 않음 | 관리자 카드 잠금 해제 |
| 로컬 테스트가 기존 테스트 0개를 실행 | 검증 실패·설정 수정 필요 | exit 0만 보고 통과 |

## 12. 파일과 최소 수정 위치

모든 경로는 FIND_X의 `main/` 기준이다. 실제 checkout sourceSet을 확인하기 전 `src/main`으로 이동하지 않는다.

| 파일·seam | 변경 | 상태·주의 |
|---|---|---|
| v2 `routing/settings/RoutingProfile.java` | 기존 여섯 역할 문서 유지 | v3용 새 영속 필드 불필요 |
| v2 `RoutingSettingsService`·`RoutingProfileResolver` | 원자적 저장·상속·제약·순수 preview 유지, 읽기 설명을 합성 | 현재 로컬 존재 여부부터 확인 |
| v2 `api/RoutingSettingsController` | 기존 세 POST 응답에 정제된 settings/pipeline view 결합 | 기존 `/api/settings` 변경 0, 새 endpoint 0 |
| v2 `RoutingOutcomeProjector` | 선택/시도/응답/채택과 Plan/근거 인계 상태를 조합 | 동일 Run·기존 상세 접근 권한 |
| v2 `RunRoutingSnapshot`·기존 Run 보관부 | 정책 고정과 관측값 분리; 현재 취소·동의 재검증 보존 | 새 세션 저장소 금지 |
| `service/routing/PolicyBasedModelRouter.routeMain/route` | v2 역할 소비, 기존 exact/OAuth 우선순위 유지 | S41, 활성 역할만 |
| `service/rag/SelfAskPlanner.modelIdForLane/providerForLane` 및 v2 대체 호출부 | v2 역할·provider·예산 일치, no-op 배정 방지 | S42, 새 래퍼로 이중 실행 금지 |
| `llm/DynamicChatModelFactory`·`nova/orch/aop/LlmRouterAspect` | v2 내부 invocation·AOP 인자 전달·후보 경계 | v2 상세 계획 유지 |
| `service/ChatWorkflow` snapshot/메인 결정/최종 결과 seam | v2 소비·관측 연결에 필요한 최소 줄 | S15~S21, 기존 RAG/기억 알고리즘 변경 없음 |
| `api/ChatStreamSignalBuilder.buildPipelineSnapshot/planStages` | 우선 변경 없이 호출·재사용 | S22·S23, 필요 시 순수 함수 한정 추출 |
| v2 `static/js/settings-routing.js` | 역할 카드·실행 구성·view schema·diff·관측 표시 | settings 페이지에서만 로드 |
| 기존 초안 settings HTML/CSS | 카테고리 보존, 상태 카드·모바일·접근성 보강 | 새 프레임워크·CDN 없음 |
| `chat-ui.html` | 앞선 링크 1줄·bridge script 1줄 범위 유지 | 추가 조합 스크립트는 settings에서만 로드 |
| `chat-settings-bridge.js` | 기존 네 컨트롤+change만 | chat.js 전역·fetch 가로채기 금지 |

변경 금지: chat.js, 스튜디오, DB 엔티티·스키마, 보안 설정·필터 정책, 기존 SettingsController/ModelSettingsController의 계약, 환경변수명·비밀값, 운영 사용자 데이터. 이번 범위에서 Jev·Plan·Matrix·ONNX·자동 학습 구현은 **관측·재사용 대상이지 전면 교체 대상이 아니다.**

`SettingDescriptor` 등 작은 record는 v2 controller/projector 내부에 둘 수 있다. 기존 동등 DTO가 있으면 재사용한다. v3를 명목으로 모든 모듈을 통합하는 `UawSettingsOrchestrator`, 별도 작업 큐, 범용 policy DSL, AI 기반 설정 에이전트를 만들지 않는다.

## 13. 통합 작업 패키지 — v2와 합쳐 다섯 개

아래는 v2 WP1~WP5의 **보강판**이다. v2 다섯 개를 완료한 뒤 v3 다섯 개를 처음부터 반복하는 방식이 아니다. 로컬 DONE는 실제 diff·테스트로 판정하고 완료된 항목을 생략한다. 각 WP 안에서도 한 원인씩 RED→최소 수정→GREEN으로 진행한다.

공통 금지: 원본 chat.js, 스튜디오, 보안 정책 파일, DB 스키마, 비밀값, 운영 세션·Display·GPU 테스트, commit/push/reset/clean. 실호출은 금지하고 mock만 쓴다.

`$TestTask`는 실제로 확인한 Gradle Test task, `$TestRoot`는 그 task의 sourceSet이다. 그 값이 없으면 임의 :test나 src/test를 가정하지 않는다. 아래 클래스명은 초안이다. 이미 동등 테스트가 있으면 그곳에 assertion을 추가한다. 테스트 0건은 통과가 아니다.

### WP1 — 현재 지원 범위·설정 출처·Plan 의미를 고정

**판정:** PARTIAL + NEW.

**seam:** `RetrieverChainConfig.retrievalHandler`(S01), `JevRuntimeConfiguration`(S04), `PlanExecutionSpec.evaluateWhen/stageLedger`(S12~S14), `ChatStreamSignalBuilder.buildPipelineSnapshot`(S22).

**문제:** 클래스 존재·설정 ON·단계 선언·실행 완료를 한 상태로 합치면 거짓 설정 페이지가 된다.

**RED:** v2 `RoutingProfileContractTest`·`RoutingPreviewParityTest`를 보존하고, `SettingsCapabilityProjectionTest`와 `SettingsPlanProjectionTest`에 V3-01~06을 작성한다. 특히 `planDsl=not_used`와 `when=true`가 함께 존재할 때 둘 다 보존하는 fixture를 만든다.

**최소 수정:** 별도 기능 실행기를 만들지 않고 명시적 descriptor·기존 ledger 투영만 연결한다. public 응답에 Plan raw expression·전체 Environment가 포함되지 않도록 allowlist를 따른다.

**GREEN 명령:**

```powershell
.\gradlew.bat $TestTask --tests '*RoutingProfileContractTest' --tests '*RoutingPreviewParityTest' --tests '*SettingsCapabilityProjectionTest' --tests '*SettingsPlanProjectionTest'
```

**완료 증거:** 모든 capability가 source/권한/consumer/관측 수준을 구분. 클래스만 존재하는 fixture는 ready가 아님. 새로운 실행·빈 생성·외부 호출 0.

**추가 금지 파일:** Jev 설정·조건부 빈 등록, Plan YAML 원본, WebMvcConfig, AutoConfiguration.imports. 등록이 현재 존재하므로 재추가하지 않는다.

### WP2 — v2 저장 스키마와 Run 정책 고정의 호환성을 완성

**판정:** NEW 또는 이미 적용된 부분의 PARTIAL.

**seam:** v2 `RoutingSettingsService/Controller`, `ConfigurationSettingRepository`, 기존 `ChatRunRegistry.Run`, `RunRoutingSnapshot`; 취소·scope 경계 S20·S35·S36.

**문제:** 페이지 설명 필드를 정책으로 저장하거나, 고정 snapshot이 현재 권한 검사를 무력화할 위험이 있다.

**RED:** v2 저장·권한·동시성 테스트를 유지하고 `SettingsSnapshotCompatibilityTest` V3-07~11을 추가한다. 이전 profile JSON을 읽기-쓰기해도 의미가 같아야 하며, descriptor fields는 저장 요청에서 거부한다.

**최소 수정:** 역할 정책 스키마는 유지. 다음 Run 적용·read-back·409 동작을 구현한다. 운영 상태 fingerprint는 관측 식별용이며 권한 토큰이 아니다. 정책 off에서 새 DB 조회 0을 보장한다.

**GREEN 명령:**

```powershell
.\gradlew.bat $TestTask --tests '*RoutingSettingsControllerTest' --tests '*RoutingSettingsPersistenceTest' --tests '*RoutingRunSnapshotTest' --tests '*SettingsSnapshotCompatibilityTest'
```

**완료 증거:** 기존 schemaVersion=1 호환·동시 저장 원자성·off 격리·동의 철회 테스트. 기존 Run 모델 정책은 그대로지만 취소·소스 현재성은 현재 상태로 검사.

**추가 금지 파일:** 범용 SettingsController 허용 목록, 엔티티, 학습·기억 권한 정책, 메모리 삭제 구현.

### WP3 — 역할 배정과 기존 검색·문맥 소비자의 계약을 연결

**판정:** PARTIAL + NEW. **기존 동작 변경:** 활성 v2 역할의 모델·대체 경계에만 한정.

**seam:** `PolicyBasedModelRouter.routeMain`(S41), `SelfAskPlanner.modelIdForLane/providerForLane`(S42), v2 factory/AOP invocation, `ChatWorkflow` 메인 결정·문맥 준비(S18~S21).

**문제:** 모델 배정이 잘 저장돼도 provider·AOP·대체 호출에서 유실되거나, 배정이 feature activation으로 잘못 해석될 수 있다.

**RED:** v2 여섯 역할·후보 제한·AOP 인자 테스트를 보존하고 `SettingsRoutingIntegrationTest` V3-12~20을 추가한다. 실제 factory 호출 인자와 호출 횟수를 mock으로 검증한다. 제브 existing handle은 추가 호출 없이 재사용해야 한다.

**최소 수정:** v2의 내부 typed invocation과 허용 목록을 모든 대체 seam에 전달한다. 검색 OFF·strict·권한·제브 AUTO 한정 계약을 유지한다. 기존 문맥 준비 조건을 그대로 두고 기존 PromptBuilder를 호출한다. 예산 추천값을 적용값으로 가장하지 않는다.

**GREEN 명령:**

```powershell
.\gradlew.bat $TestTask --tests '*RoleRoutingConsumptionTest' --tests '*RoutingFallbackBoundaryTest' --tests '*RoutingFactoryArgumentsTest' --tests '*SettingsRoutingIntegrationTest'
```

**완료 증거:** 여섯 역할 target/provider 일치, 후보 밖 호출 0, 제브 중복 호출 0, 기능 OFF의 무단 ON 0, 메인 결정과 출력 예산 기준 일치.

**추가 금지 파일:** 제브 client transport·가드, 전체 retrieval 체인 재설계, context_prepare 승인 조건, 원격 신규 provider, 공개 ChatRequest/ChatResult 계약.

### WP4 — 선택·근거 인계·생성·저장 결과를 정직하게 투영

**판정:** PARTIAL + NEW.

**seam:** v2 `RoutingOutcomeProjector`, `ChatStreamSignalBuilder.buildPipelineSnapshot/planStages`(S22·S23), `ChatWorkflow` 관측 seam(S16·S19·S21), `FinalizedMemoryPersistence` 결과 경계(S36), 기존 terminal trace(S44).

**문제:** 상위 모델 선택만 보이면 하위 단계가 실제 기여했는지, 빈 문맥이나 저장 실패가 있었는지 알 수 없다. 반대로 상세 raw trace 공개는 금지해야 한다.

**RED:** v2 관측·권한·redaction 테스트에 `SettingsOutcomeProjectionTest` V3-21~27을 추가한다. 추정 건수·delegated·제브 신호·lateActivation·저장 취소를 실제 성공으로 잘못 승격시키면 실패한다.

**최소 수정:** 같은 Run의 typed·정제된 관측을 합성하고 기존 권한 이후에만 반환한다. pipeline projection은 기존 api 패키지 메서드를 재사용한다. fallback chain의 마지막 보조 모델을 최종 생성 모델로 표시하지 않는다.

**GREEN 명령:**

```powershell
.\gradlew.bat $TestTask --tests '*RoutingOutcomeProjectionTest' --tests '*RoutingOutcomeAccessTest' --tests '*RoutingRedactionTest' --tests '*SettingsOutcomeProjectionTest'
```

**완료 증거:** 읽기만으로 새로운 실행·계산·저장 없음, 숫자 출처 보존, 원문·비밀값 0, 최대 32단계와 기존 권한 범위 준수. 기존 modelUsed를 재정의하지 않는다.

**추가 금지 파일:** 공개 진단 API 신설, 기존 로그 전체 노출, 메모리/TTS 본문, 기존 breadcrumb allowlist의 광역 확대.

### WP5 — 설정 페이지와 실제 사용자 확인 흐름을 연결

**판정:** PARTIAL + NEW.

**seam:** v2 `settings-routing.js`, 기존 페이지 초안 `settings.html/settings-page.css/settings-page.js`, 네 컨트롤 bridge.

**문제:** 기능은 연결돼도 상태가 혼동되거나, 저장/미리보기 버튼이 실제 부수 효과를 숨길 수 있다.

**RED:** v2 Node·MockMvc 초안을 보존한다. `SettingsHarmonyUiTest`에 대응하는 JS fixture V3-28~32 및 서버 페이지 검사를 작성한다. keyboard 조작, 403 잠금, 409 충돌, late response 방어, 모바일 카드 순서를 확인한다.

**최소 수정:** 역할 카드, 실행 구성 탭, 역할 조합 폼, source/effective/observed 값, locked 상태, 원인별 안내를 추가한다. 설정 조합은 폼 변경만이며 저장 한 번으로 v2 정책을 commit한다. 기존 /chat의 두 줄 diff를 넘기지 않는다.

**GREEN 명령:**

```powershell
node --test tests/settings-routing.test.cjs tests/settings-harmony.test.cjs
.\gradlew.bat $TestTask --tests '*SettingsRoutingPageTest'
git diff --check
git diff --numstat -- $VerifiedChatJsPath
```

**완료 증거:** 마지막 명령의 chat.js 변경 출력 없음. tests 경로는 실제 노드 테스트 위치로 대응한다. 존재하지 않는 테스트 파일을 제외해 실패를 숨기지 않는다. 새 제어 항목은 실제 저장·consumer 검증이 있거나 읽기 전용 이유가 명시돼야 한다.

**추가 금지 파일:** 스튜디오·chat.js·보안·schema·원본 UAW/Abandon_X.

## 14. 보완 테스트 명세

다음 32개는 **테스트 설계**이며 이번 대화에서 구현·실행하지 않았다. 기존 v2 64개 명세는 `reference/v2-test-matrix.json`에 보존돼 있다. 총 96개 항목을 모두 구현 완료로 계산하지 말고, 기존 테스트에 통합할 assertion으로 사용한다. 동일 원인을 두 테스트가 검증하면 이름 수를 늘리기보다 겹침을 정리한다.

| ID | 소유 작업·검사 | 입력 조건 | 기대 결과 | 실행 |
|---|---|---|---|---|
| V3-01 | WP1 · `conditionalBeanIsNotLiveSupport` | 클래스는 존재하지만 조건부 Jev 빈이 생성되지 않은 context | 조건부 빈 부재가 확인된 fixture는 runtimeAvailable=false; 상태 조회 자체가 미확인인 별도 fixture만 null; 클래스 존재만으로 ready 표기 금지; 새 빈 생성 0 | NOT_RUN |
| V3-02 | WP1 · `configuredValueIsNotObservedExecution` | onnx.enabled 구성은 true이나 실행 관측 없음 | configured=true; executed=null; 사용됨 배지 없음 | NOT_RUN |
| V3-03 | WP1 · `missingValueStaysUnknown` | 관측된 값·속성 출처가 없음 | value=null·source=unknown; 가짜 0·false·기본 모델 없음 | NOT_RUN |
| V3-04 | WP1 · `conditionalPlanPreservesLegacyDslStatus` | planDsl=not_used와 when=true 및 ledger가 함께 존재 | legacy 필드 보존; 제한 조건 적용·단계 기록 별도 표시 | NOT_RUN |
| V3-05 | WP1 · `delegatedIsNotExecuted` | prompt.build·answer.generate ledger 상태가 delegated | 호출자 위임으로 표시; 별도 생성 증거 없으면 실행됨 아님 | NOT_RUN |
| V3-06 | WP1 · `unknownDoesNotStartExpansion` | when=UNKNOWN인 Plan과 BQ 배정이 있음 | 배정 유지·확장 실행 없음·when_unknown 이유 | NOT_RUN |
| V3-07 | WP2 · `v2ProfileRoundTripsUnchanged` | 기존 v2 schemaVersion=1 역할 문서를 읽고 변경 없이 저장 | 기존 의미 동일; 설명 descriptor·Jev·Plan 데이터가 bindings로 유입되지 않음 | NOT_RUN |
| V3-08 | WP2 · `profileOffAddsNoNewDependency` | 신규 runtime gate false 상태에서 기존 채팅 실행 | 라우팅 설정용 DB 조회·정책 변경 0; 기존 선택 유지 | NOT_RUN |
| V3-09 | WP2 · `pinnedPolicyDoesNotFreezeRevocation` | 실행 스냅숏 고정 후 owner 동의 또는 소스 revision이 무효화 | 모델 정책은 고정하되 취소/권한 재검증은 현재 값; 뒤늦은 문맥 인계·저장 없음 | NOT_RUN |
| V3-10 | WP2 · `previewBasisDriftRequiresRecheck` | 미리보기 이후 역할 정책 또는 catalog/config 지문 변경 | UI 차이 알림·재검증; 이전 결과를 현재 실행 보장으로 표시하지 않음 | NOT_RUN |
| V3-11 | WP2 · `recipeIsDraftOnly` | 브라우저에서 역할 조합 초안을 선택 | 폼만 변경; DB write·API call·기존 기본값 변화 0 | NOT_RUN |
| V3-12 | WP3 · `strictMainSurvivesRoleProfile` | strict 모델 지정과 MAIN_HIGH override가 동시에 있음 | 기존 exact 선택 유지; profile이 다른 모델 호출로 대체하지 않음 | NOT_RUN |
| V3-13 | WP3 · `noJevDoubleDispatch` | 같은 교정 질의에서 search-need·complexity 소비자가 둘 다 실행 | 기존 같은 handle 사용; 설정 기능이 두 번째 transport 호출을 추가하지 않음 | NOT_RUN |
| V3-14 | WP3 · `surfacePolicyDoesNotBleed` | main 역할 정책을 변경하고 focus/cue 설정은 그대로 둠 | focus/cue 선택·기한·scope 변경 없음 | NOT_RUN |
| V3-15 | WP3 · `explicitSearchChoiceWinsOverSignal` | 명시 검색 OFF 또는 명시 검색 깊이와 상충하는 Jev 결과 | 기존 AUTO 한정 적용 경계를 유지 | NOT_RUN |
| V3-16 | WP3 · `budgetSuggestionNotAppliedTopK` | Governor가 web/vector/kg와 burst 후보를 반환 | 현재 consumer에서 반영된 burst만 applied; 다른 K는 suggested | NOT_RUN |
| V3-17 | WP3 · `evidencePreparationKeepsRequestGate` | 설정 조합은 고성능이지만 contextPreparationRequested=false | prepareContext 호출 0; 기존 일반 PromptBuilder 경로 유지 | NOT_RUN |
| V3-18 | WP3 · `evidencePreparationCannotBypassStrictOrLocalCheck` | strict/원격/이미지/when_unknown 중 하나의 기존 제한에 걸림 | evidence_pack 추가 준비는 실행 안 함; 제한을 고성능 이름으로 우회하지 않음 | NOT_RUN |
| V3-19 | WP3 · `modelParamsUseExistingNormalizer` | 고정 sampling 규칙을 가진 source 모델에 다른 temperature를 요청 | 기존 ModelCapabilities 정규화 결과를 effective로 표시; requested와 구분 | NOT_RUN |
| V3-20 | WP3 · `frozenBudgetMatchesMainBinding` | 역할 배정과 출력 토큰 예산을 계산하는 중 새 정책 저장 | 같은 Run의 main binding과 예산 계산이 같은 정책 기준 | NOT_RUN |
| V3-21 | WP4 · `estimateIsNotActualCount` | web/vector만 관측되고 finalContextCount는 없음 | 기존 web_vector_estimate 출처 보존; 실제 최종 근거 건수로 표시하지 않음 | NOT_RUN |
| V3-22 | WP4 · `jevSignalIsNotAnswerOrigin` | Jev 평가 성공 후 메인 생성 실패 | Jev succeeded와 final failed를 분리; 최종 생성 모델에 Jev를 넣지 않음 | NOT_RUN |
| V3-23 | WP4 · `lateActivationDoesNotReplay` | 실행 후 when이 unknown에서 true로 변경됐다는 trace 존재 | lateActivation 관측만 표시; 브라우저/API에서 재실행 0 | NOT_RUN |
| V3-24 | WP4 · `persistenceIsNotModelSuccess` | 생성 성공 후 저장 stage 실패 또는 취소 | 생성 성공·저장 실패/취소 분리; 정상 답변/기억에 진단 문자열 추가 없음 | NOT_RUN |
| V3-25 | WP4 · `boundedAllowedProjectionOnly` | unknown stage·raw expr·secret·원문을 meta에 주입 | 기존 allowlist 외 값 제거; raw expr 미출력; 최대 32단계; 범위 권한 먼저 검사 | NOT_RUN |
| V3-26 | WP4 · `previewDoesNotMutateAdaptiveState` | 미리보기에서 상태ful bandit/ArtPlate/Plan loader가 spy로 연결됨 | decide/propose/observe·로드 cache/trace mutation·model 생성·write 호출 0 | NOT_RUN |
| V3-27 | WP4 · `sameRunOnlyNoGlobalTraceFallback` | 다른 runId·삭제된 실행 또는 저장된 관측이 없는 실행 조회 | 기존 접근 계약 유지; 조회 요청의 ThreadLocal trace나 전역 로그로 대체하지 않음 | NOT_RUN |
| V3-28 | WP5 · `registeredAndExecutedBadgesDiffer` | 등록된 경로가 역할 조건 때문에 생략됨 | 등록됨/선택 가능/조건부 생략 구분; 기본값으로 처리한 척하지 않음 | NOT_RUN |
| V3-29 | WP5 · `unwiredControlCannotAutosave` | 재시작 필요·소비 미확인 설정 카드를 클릭 | 읽기 전용 안내; 저장 호출 없음; localStorage에 동작 설정으로 저장하지 않음 | NOT_RUN |
| V3-30 | WP5 · `browserBridgeRemainsFourControls` | settings-routing.js와 bridge가 포함된 정적 페이지 | bridge는 modelSelect/modelSelectionMode/searchModeSelect/useRagToggle만 반영; chat.js 수정 0 | NOT_RUN |
| V3-31 | WP5 · `adminGetSuccessDoesNotUnlock` | 기존 settings GET은 200, 새 ADMIN POST read는 403 | 서버 역할 카드 잠금; 브라우저 기본값은 사용 가능 | NOT_RUN |
| V3-32 | WP5 · `reportActionsNeverStartWork` | 기존 실행 보기·미리보기·내보내기 버튼 실행 | 새 chat run·sync·bootstrap·training·warmup 호출 0 | NOT_RUN |

## 15. 교차검증과 선택한 설계

### 성공 가설 1 — 기존 역할 소비자에 배정만 연결하면 준비·생성의 분업을 강화할 수 있다

근거: 메인 router와 BQ/ER/RC 설정 소비자가 있다(S41·S42). 제브와 프롬프트 경로도 별도로 존재한다(S01·S21).

반례: 높은 모델을 배정해도 검색이 OFF거나 하위 역할이 호출되지 않으면 결과 품질은 바뀌지 않을 수 있다. 모델 지원 능력·prompt 품질·검색 결과가 병목일 수도 있다.

중립 판정: 역할 배정 편집은 채택한다. 품질 상승 수치나 최적 모델 추천은 측정 전 보류한다. 이번 설정 UI가 ‘어느 단계가 미실행인지’를 설명해야 한다.

### 성공 가설 2 — 설정 출처와 실제 관측을 함께 보이면 무음 no-op을 줄일 수 있다

근거: 현재 이미 plan.stageLedger, pipeline snapshot, reason code, reported/estimate 구분이 있다(S16·S22·S23).

반례: trace가 없거나 다른 실행의 기록이면 상세한 화면도 거짓이다. EXECUTED가 해당 wrapper 호출을 뜻할 뿐 내부 물리 GPU 작업까지 보증하지 않을 수 있다.

중립 판정: 현재 Run의 정제된 관측만 사용한다. 관측 범위를 표시하며, 미관측을 공백/null로 남긴다. 추가 관측 수집은 bound와 권한 검증 안에서만 구현한다.

### 약한 가설 3 — UAW의 자동 진화 전략을 설정 페이지에 노출하면 품질이 크게 오른다

근거: ArtPlate 진화 코드와 관측은 있다(S32).

반례: 미리보기에서 상태를 갱신하거나 운영 트래픽을 무작위 분배하면 기존 선택·예산·비용 계약을 어길 수 있다. 모델 합의가 근거 검증을 대신하지도 않는다.

중립 판정: 이번에는 채택하지 않는다. 읽기 정보와 순수 미리보기만 제공하고, 자동 튜닝·운영 A/B·무작위 sampling 조절은 별도 실험 과제로 둔다.

## 16. 적용·되돌림·ASK_ONCE

### 16.1 적용 전에 할 일

실제 checkout의 HEAD·status·기존 리스/저널, build root, sourceSet, 실행 진입점, 테스트 task를 확인한다. read-only 진단 범위를 넘지 않는다. 이 ZIP 안에 임의 build.gradle을 만들어 통과시키지 않는다. 기존 v1/v2 파일 적용 여부를 hash·diff로 확인한다.

경로 확인 후 보호 파일의 기준 hash와 작업별 예상 diff를 기록한다. 같은 공유 원본을 여러 에이전트가 동시에 쓰지 않는다. Git worktree가 운영 DB·포트·GPU·Display 격리까지 해결하는 것은 아니다.

### 16.2 되돌리는 방법

신규 기능이 비활성일 때 기존 경로가 동일하다는 테스트를 먼저 유지한다. 활성 역할 정책을 되돌릴 때는 v2의 리비전 저장 절차로 검토한 이전 역할 값을 **새 revision**으로 저장하며 번호를 역행시키지 않는다. 진행 중 Run은 기존 정책과 현재 취소/권한 fence를 따른다.

이번 작업의 source diff만 적용 전 백업과 대조해 되돌린다. 다른 에이전트의 파일·미추적 파일·사용자 설정을 삭제하지 않는다. 원본 UAW·Abandon_X·채팅 DB·인덱스를 되돌림 대상으로 포함하지 않는다. 임의 reset/clean/force push는 금지한다.

### 16.3 ASK_ONCE 목록

이번 v3가 기존 결정을 뒤집어 자동 채택하는 항목은 없다. 아래는 필요한 경우에만 묻는 별도 승인 후보이며 기본 구현에 포함하지 않는다.

| 후보 | 충돌 | 현재 처리 |
|---|---|---|
| 일반 인증 구성에서도 `/settings`를 익명 공개 | 기존 보안 매핑 변경 필요 | v2 HOLD 유지. 보안 diff 없음 |
| Jev 활성/예산/timeout을 페이지에서 즉시 변경 | 조건부 빈·동적 갱신 계약 미확인 | 읽기 전용 |
| context_prepare를 새 UI에서 요청·원격 모델로 확대 | chat.js 불변·기존 승인 조건과 충돌 | HOLD |
| Plan 순서·검색 K·품질 임계값을 전면 편집 | 실제 consumer 범위·기존 정책 변경 필요 | 읽기 전용 |
| 개인 OAuth를 서버 역할에 전역 배정 | owner·자격·scope 충돌 | 금지 유지 |

해당 정책 변경을 실제로 제안하는 후속 요청에서만 정확한 요구·현재 코드·필요한 공식 규격을 확인해 한 번 질문한다. 이번에는 공식 근거 없는 변경안을 승인된 것으로 포함하지 않는다.

### 16.4 남은 정보

실제 Gradle 설정과 테스트 sourceSet, API_ROUTING_SPEC §6, 현재 INV/DONE/HOLD, 적용된 v1/v2 diff, 정제된 한 건의 `/chat` 실행 관측, 운영 플래그의 허용 목록별 유효값·출처가 필요하다. 실제 공급자 사용권·크레딧·가격·ZDR·GPU 정보는 이번 소스 판독에서 확정하지 않는다.

## 17. 현재 소스 근거 색인

아래는 모두 `FIND_X.zip!` 기준이다. 행 범위는 해당 파일의 현재 hash에 결합되어 있다. 다른 스냅숏의 줄 번호로 재사용하지 않는다. 원본 소스 내용은 배포 ZIP에 넣지 않았다.

| 근거 | 파일:행 | 직접 확인한 범위 |
|---|---|---|
| S01 | `main/java/com/example/lms/config/RetrieverChainConfig.java:42–97` | 기존 retrievalHandler 빈이 dynamic·fixed 경로 모두 JevRetrievalGateHandler.wrapIfEnabled를 호출한다. |
| S02 | `main/java/com/example/lms/service/rag/handler/JevRetrievalGateHandler.java:35–66` | Jev 연결은 기능·surface·seam 플래그, 실행 컨텍스트·시간 예산·privacy 조건과 finally 정리를 따른다. |
| S03 | `main/java/com/example/lms/service/rag/handler/JevRetrievalGateHandler.java:112–140` | 질의 교정 이후 prefetch하며 같은 QuestionKey·기한으로 관측하고 질의 변경 시 폐기한다. |
| S04 | `main/java/com/example/lms/assist/JevRuntimeConfiguration.java:8–21` | Jev 런타임과 advisor는 조건부 빈이다. 클래스 존재와 런타임 빈 생성은 다르다. |
| S05 | `main/java/com/example/lms/assist/JevSurfacePolicy.java:7–29` | global off가 surface 설정보다 우선한다. decisionWait와 requestTimeout은 별개다. |
| S06 | `main/java/com/example/lms/gptsearch/decision/JevSearchNeedAdvisor.java:13–42` | AUTO·일부 기존 이유·유효 confidence·동일 검색 허용 범위 안에서만 제브 신호를 반영한다. |
| S07 | `main/java/com/example/lms/assist/JevEvaluationRuntime.java:103–124` | 기존 status는 configured·reason·surface 상태를 정제해서 제공한다. |
| S08 | `main/java/com/example/lms/assist/JevEvaluationRuntime.java:269–284` | 무료 기간·allow-paid·일일 상한 admission은 별도 조건이다. 기본 상한 0의 의미도 enabled와 함께 해석한다. |
| S09 | `main/java/com/example/lms/plan/PlanHintApplier.java:43–77` | PlanHints와 PlanExecutionSpec를 같은 로드 결과에서 캐시한다. load에는 trace·cache 부수 효과가 있다. |
| S10 | `main/java/com/example/lms/plan/PlanHintApplier.java:79–151` | 현재 지원 힌트가 GuardContext에 전달된다. selfAsk count의 양수 힌트는 enable override도 만든다. |
| S11 | `main/java/com/example/lms/plan/PlanHintApplier.java:803–816` | 기존 broad DSL unwired 목록은 유지된다. 제한된 조건 소비와 따로 해석해야 한다. |
| S12 | `main/java/com/example/lms/plan/PlanExecutionSpec.java:33–89` | 기존 tri-state와 단계 상태·debugView를 재사용할 수 있다. |
| S13 | `main/java/com/example/lms/plan/PlanExecutionSpec.java:174–229` | 제한된 when·pipeline 구조와 context_prepare 허용 조건을 파싱한다. |
| S14 | `main/java/com/example/lms/plan/PlanExecutionSpec.java:339–376` | stageLedger는 기록 투영이며 prompt.build와 answer.generate는 DELEGATED이다. |
| S15 | `main/java/com/example/lms/service/ChatWorkflow.java:1470–1505` | 주력 ChatWorkflow에서 Plan 선택·조건 평가·미관측 확장 제거 코드가 있다. |
| S16 | `main/java/com/example/lms/service/ChatWorkflow.java:2824–2854` | 기존 관측 결과로 plan.stageLedger를 생성해 TraceStore·metaHints에 넣는다. |
| S17 | `main/java/com/example/lms/service/ChatWorkflow.java:2927–2958` | 기존 DynamicContextCompressor가 프롬프트용 문맥을 정제한다. |
| S18 | `main/java/com/example/lms/service/ChatWorkflow.java:2979–3024` | 메인 경로 결정 이후 PromptContext를 웹·RAG·기억·이력 등의 구분을 유지해 구성한다. |
| S19 | `main/java/com/example/lms/service/ChatWorkflow.java:3073–3101` | RagEvidenceAttributionService가 인용 가능한 근거를 프롬프트용으로 승격하며 실패 상태를 구분한다. |
| S20 | `main/java/com/example/lms/service/ChatWorkflow.java:3103–3148` | evidence_pack 문맥 준비는 명시 요청·로컬 모델·비strict·Plan 승인·scope 현재성 등의 조건을 요구한다. |
| S21 | `main/java/com/example/lms/service/ChatWorkflow.java:3327–3330` | 주력 답변에서 기존 PromptBuilder.build와 buildInstructions를 호출한다. |
| S22 | `main/java/com/example/lms/api/ChatStreamSignalBuilder.java:95–174` | 기존 PipelineSnapshot은 Plan·카운트·품질 점수·미관측 값과 reported/estimate 출처를 구분한다. |
| S23 | `main/java/com/example/lms/api/ChatStreamSignalBuilder.java:192–219` | 단계 투영은 allowlist·최대 32행·숫자 범위를 제한한다. |
| S24 | `main/java/com/example/lms/service/rag/budget/RetrievalBudgetDecision.java:3–17` | 기존 예산 판단에는 web/vector/kg/burst 등 제안 값이 있다. |
| S25 | `main/java/com/example/lms/service/rag/budget/RetrievalBudgetGovernor.java:79–102` | 예산 trace의 suggested 값들과 applied.queryBurstCount 표기가 구분돼 있다. |
| S26 | `main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java:1145–1181` | 확인한 Zero100 burst 호출부는 decision.queryBurstCount를 소비하고 앵커로 후보를 거른다. |
| S27 | `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java:363–408` | 제한된 when 조건 소비와 broad planDsl.status=not_used 진단이 공존한다. |
| S28 | `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java:871–887` | canonical DppDiversityReranker 호출 경로가 존재하며 조건부로 적용한다. |
| S29 | `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java:939–995` | ONNX 요청 여부·의존성 부재·실행·실패·카운트를 구분한다. |
| S30 | `main/java/com/example/lms/matrix/MatrixTransformer.java:30–120` | matrixTransformer는 고정 한도 내에서 sections-only 문맥과 후보를 반환한다. |
| S31 | `main/java/com/example/lms/fusion/MatrixTransformer.java:10–46` | 이름이 비슷한 fusionMatrixTransformer는 별도의 줄 배분 유틸리티다. |
| S32 | `main/java/com/example/lms/artplate/NineArtPlateGate.java:204–247` | AtomicReference 상태와 proposeWithSse 호출이 있다. get/set 전체가 CAS 원자 전이인 것은 아니다. |
| S33 | `main/java/com/example/lms/config/WebMvcConfig.java:59–71` | canonical RuleBreakInterceptor 조건부 등록 코드가 존재한다. |
| S34 | `main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports:1–8` | FailurePattern·Zero100를 포함해 여덟 import 행이 존재한다. |
| S35 | `main/java/com/example/lms/assist/FocusMemoryScope.java:3–10` | FocusMemoryScope는 owner 결합 이후의 서버 내부 scope이며 consent/index revision을 가진다. |
| S36 | `main/java/com/example/lms/service/chat/FinalizedMemoryPersistence.java:16–46` | 기존 최종 저장은 Run commit·terminal side effect·취소 경계를 보존한다. |
| S37 | `main/java/com/example/lms/vector/EmbeddingFingerprint.java:69–110` | 임베딩 동일성은 provider/model/dimension/정규화 조건으로 구성된다. |
| S38 | `main/java/com/example/lms/uaw/autolearn/UawDatasetFilterProperties.java:18–58` | 학습용 필터는 별도 canonical 설정·action·scope를 사용한다. |
| S39 | `main/java/com/example/lms/llm/ModelCapabilities.java:188–234` | 기존 모델별 샘플링 매개변수 정규화 함수가 있다. 공급자 최신 규격 증거는 아니다. |
| S40 | `main/java/com/example/lms/llm/OpenAiModelParamMatrix.java:158–192` | 토큰 매개변수 이름을 모델과 baseURL 규칙에 따라 해석하는 기존 코드다. |
| S41 | `main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java:243–270` | exact 선택·복잡 요청 OAuth·일반 선택 순서와 main decision 기록을 보존한다. |
| S42 | `main/java/com/example/lms/service/rag/SelfAskPlanner.java:457–510` | BQ/ER/RC model/provider/temperature/timeout 설정 소비자가 있다. |
| S43 | `main/java/com/example/lms/config/AppSecurityConfig.java:149–155` | settings GET과 POST 권한이 다르다. 기존 POST ADMIN 경계 재사용. |
| S44 | `main/java/com/example/lms/debug/ChatSessionTraceRecorder.java:81–150` | 기존 terminal trace는 정제된 식별자·모델·상태를 기록하고 recordId로 중복 append를 막는다. |

## 18. 수용 기준·검증 보고

| 기준 | 완료 증거 |
|---|---|
| 여섯 역할을 실제 모델 호출에 배정 | target/provider/AOP/대체 경로까지 mock assertion |
| UAW 의도와 실제 기능 구분 | §2 대응표의 판정과 current seam 일치 |
| 설정 상태가 실제 동작과 일치 | configured/effective/observed·조건·지원 상태 구분 |
| 새 정책 off에서 기존 동작 유지 | 신규 DB·네트워크 의존 0, 기존 경로 특성 테스트 |
| 문맥·근거·권한 유지 | 기존 PromptBuilder·scope·revision·취소 fence 검증 |
| 화면 조회·미리보기 부수 효과 0 | 모델 호출·상태 mutation·저장·새 Run 0 |
| 보안·기존 계약 불변 | 보호 파일 hash·기존 테스트·비관리자/PROTO_OPEN 분리 |
| 무음 성공·정확도 과장 없음 | read-back, 실제 관측 출처, null 유지, 점수와 확률 구분 |
| 코드·배포 범위 준수 | chat.js 0, schema 0, 보안 0, 스튜디오 0, 원본 문서 0 |

로컬 에이전트의 최종 보고는 **변경 파일·최소 diff·실제 명령·종료 코드·테스트 건수·남은 위험** 순으로 작성한다. 정적 확인, 테스트 통과, 전체 빌드, 서버 기동, 실기능 확인은 각각 별도 상태다. targeted test를 수행한 결과를 전체 시스템 정상으로 확대하지 않는다.

이번 문서 생성에서 실행한 검증은 입력 해시 보존, 근거 행 범위·파일 해시, JSON 구조·참조, 테스트 ID 중복, v2 원문 보존, ZIP 무결성 등 **산출물 정합성 검사**뿐이다. Java/Node 애플리케이션 테스트·전체 빌드·서버·모델 호출은 NOT_RUN이며 검증 기록에 분리한다.

## 19. 코딩 에이전트에 붙여 넣을 시작 지시

> FIND_X 기준 v3 지시서와 reference의 v2를 읽고, 현재 로컬 INV/DONE/HOLD·HEAD·리스·실제 빌드/sourceSet부터 확인하라. UAW는 의도·지도, Abandon_X는 과거 교정 자료이며 현재 코드보다 우선하지 않는다. 목표는 `/settings`에서 메인·경량·고성능과 BQ/ER/RC의 실제 모델 배정·대체 범위를 편집하고, 기존 Jev·Plan·검색·정제·PromptBuilder·최종 저장의 관측을 같은 Run 기준으로 확인하게 하는 것이다. 기존 제브·Plan 조건·RuleBreak·DPP 구현을 다시 추가하지 말라. 이 v3의 WP1~WP5는 v2와 합친 작업이며 DONE를 반복 구현하지 않는다. read/preview/save는 v2의 기존 신규 컨트롤러만 사용하고 저장 스키마를 늘리지 않는다. chat.js·스튜디오·schema·보안·기존 API 계약·원본 UAW/Abandon_X 변경 0. 새 기능 off·추가 유료 false·신규 상한 0, 외부 실호출 0, mock만 사용한다. 설정값·유효값·관측값을 구분하고 소비자 없는 토글은 저장하지 않는다. 한 원인씩 RED→최소 수정→GREEN으로 진행하며 커밋·push·reset·clean하지 않는다. 실제 명령·종료 코드·건수와 NOT_RUN을 분리 보고하라.

결정요인: 기존 소비자 재사용, 정책·관측 분리, 권한·문맥·실행 수명 보존. 확신: 소스 구간 판독 높음 / 신규 통합 설계 중간 / 로컬 실행 결과 근거 부족.
