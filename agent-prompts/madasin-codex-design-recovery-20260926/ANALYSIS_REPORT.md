# madasin 설계·UI 복원 분석 리포트

**기준일:** 2026-09-26, Asia/Seoul  
**수정 대상:** `madasin.zip`의 현재 소스  
**참고 계보:** `src111_mergex15.zip` 및 Library에서 확인한 `UAW.txt` 관련 설계·교정 절  
**산출물 성격:** Codex용 수정 설계와 근거. 애플리케이션 소스를 직접 수정하거나 운영 서버를 배포하지 않았다.

## 1. 결론

**옛 소스를 복구하는 작업이 아니라, 현재 소스에 남아 있는 좋은 구현들의 연결 계약을 복구하는 작업이 맞다.**

현재 버전은 과거 버전보다 기능이 단순히 줄어든 상태가 아니다. 답변별 트레이스, 관리자 전용 상세, HTML 정제, 세션 저장 포인터, exact-run 스트림 처리, 첨부 소유권, GraphRAG 출처 검증, 모델 사용 방식 등이 추가되어 있다. 과거 UI를 통째로 덮으면 이 개선사항이 사라질 수 있다. 반면 옛 설계의 핵심 장점이었던 **“왜 이 경로를 선택했고, 어디서 검색이 줄었으며, 어떤 문맥이 최종 답변에 들어갔는지 한 답변 단위로 관찰하는 경험”**은 여전히 여러 표시 규격과 저장 규격에 나뉘어 있다.

따라서 권장안은 다음과 같다.

> 현재 실행 경로를 유지한다. 답변·실행·스냅샷의 식별자를 명확히 연결한다. 트레이스의 생산·저장·전송·표시 계약을 맞춘다. 고급 검색·압축·전략 기능은 실측과 예산 범위 안에서만 적용한다. UAW의 강점은 보존하고, 근거 없는 성능·학습·자동실행 주장은 제거한다.

이 보고서는 “예전의 버튼과 파일을 모두 되살리기”, “RAG 엔진을 하나 더 만들기”, “모든 기능을 기본 ON으로 바꾸기”를 권장하지 않는다.

## 2. 조사 범위와 확인 수준

### 2.1 전체 아카이브 탐침

| 구분 | 확인값 |
|---|---:|
| 현재 ZIP 전체 파일 | 2,364 |
| 현재 Java 파일 | 2,152 |
| 과거 ZIP 전체 파일 | 4,252 |
| 과거 `src/main` 비교 대상 | 2,075 |
| 공통 경로 | 1,664 |
| 바이트까지 동일 | 17 |
| CRLF/LF만 다른 파일 | 653 |
| 실제 내용이 달라진 공통 파일 | 994 |
| 현재에만 존재 | 700 |
| 과거 main에만 존재 | 411 |
| 현재 ZIP의 빌드 정의·wrapper | 없음 |
| 현재 ZIP의 `test/`·`tests/` 경로 | 없음 |

`current/main/...`와 `legacy/src/main/...`를 동일 상대 경로로 비교했다. 과거 ZIP에는 `backup`, 패치, 별도 모듈 및 도구도 있으므로 **과거 4,252개 전부를 현재 2,364개와 일대일 복구 대상으로 해석하면 안 된다.** 같은 이유로 개행만 달라진 653개 파일을 기능 변경으로 계산하지 않았다.

모든 ZIP 멤버의 경로·크기·SHA-256 및 비교 결과는 `evidence/source_inventory.json`에 있다. 동일 ZIP 멤버 중복은 발견되지 않았다. package+파일 stem 기준 중복 후보도 없었지만, 이것은 전체 Bean 이름 충돌이나 실제 sourceSet의 중복 부재를 증명하지 않는다.

Java UTF-8 구조 검사에서 `UnifiedRagOrchestrator.java` 안의 NUL 바이트 1개를 발견했다. 검색 도구가 이 파일을 binary로 보고 뒤쪽 탐색을 생략할 수 있으므로 전체 검색에는 binary-safe 방법을 써야 한다. **이 사실만으로 컴파일 실패라고 단정하지 않으며, 해당 문자를 일괄 제거하지 않는다.** 실제 빌드에서 그 위치의 문자열·주석 의미를 확인한다.

### 2.2 실제 수행한 실행 검증

| 검증 | 결과 | 의미와 한계 |
|---|---|---|
| 제공된 현재 UI JS를 Chromium 격리 페이지에 직접 로드 | 10개 시나리오 | 7개 보존할 동작 통과, 3개 표시/호환 간극 재현 |
| 현재 first-party 정적 JS `node --check` | 31/31 통과 | 구문 검사만 수행. DOM 초기화·서버 API·회귀 전부를 보장하지 않음 |
| 현재 `PlanExecutionSpec.java`를 `javac --release 17`로 독립 컴파일 | 9개 assertion 통과 | 순수 Java 계약만 검증. `ChatWorkflow` 연결부 또는 Spring context 검증 아님 |
| 전체 Spring 빌드·DB·로그인·실제 SSE 재연결 | 미실행 | 현재 ZIP에 실제 빌드 루트·테스트·운영 DB가 없음 |
| 실제 provider 호출·GPU 실행·검색 품질 비교 | 미실행 | 유료 호출·운영 변경을 하지 않음 |

전 파일의 구조·해시 탐침과 주요 연결부의 심층 분석을 수행했다. 모든 클래스의 모든 실행 분기를 동적으로 검증한 것은 아니다. 이 구분을 Codex의 완료 보고에서도 유지해야 한다.

`UAW.txt`는 Library 검색/본문 조회로 확인했다. 원본 바이트 materialization은 허용되지 않아 이 패키지에 UAW 원본을 복사하거나 원본 해시를 만들어 넣지 않았다. 사용자가 가진 UAW 원본은 별도로 Codex에 제공해도 되지만, 실제 최신 코드보다 우선하지 않는다.

## 3. 소스 우선순위와 설계 접근법

**우선순위:** 실제 Desktop 실행 소스·fresh 테스트 → 현재 madasin 코드 → 이 보고서의 재현·소스 근거 → 과거 코드 → UAW의 설계 설명.

`UAW.txt`에는 최소 수정·실행 계약을 강조하는 교정 지시와, 모든 기능이 완성된 것처럼 설명하는 이전 포트폴리오 서술이 함께 있다. 예를 들어 “YAML 한 줄로 임의 실행 순서가 바뀐다”, “현재는 누락된 AutoConfiguration을 등록해야 한다”, “1536차원으로 줄이면 검색이 3배 빨라진다”를 모두 현재 사실로 받아들이면 안 된다.

| 접근 | 장점 | 결정적 단점 | 판단 |
|---|---|---|---|
| 과거 UI/서비스 파일 통째 복원 | 외형을 빠르게 옛 모습으로 만들 수 있음 | 보안·세션·첨부·라우팅 회귀, 현재 DOM 및 DTO 불일치 | 배제 |
| 새 UI 프레임워크와 새 RAG/trace 엔진 도입 | 새 구조를 명확하게 설계할 수 있음 | 기존 관측·저장·실행 경로가 더 늘고 작업 범위가 폭증 | 이번 범위에서 배제 |
| 현재 경로의 최소 호환 복구 | 최신 장점을 유지하며 손상 지점만 봉합 | 생산자부터 소비자까지 계약을 정확히 맞춰야 함 | **채택** |

## 4. 확인된 문제와 위험 — 우선순위 표

표의 `재현`은 격리 fixture에서의 재현이며 운영 서버 재현을 뜻하지 않는다. 근거 E 번호의 파일·행·소스 해시는 `evidence/EVIDENCE.md`에 있다.

| ID | 수준 | 발견 | 영향 | 권장 조치 |
|---|---|---|---|---|
| F01 | 재현 / P1 | 서버는 metadata-only `<section>`도 저장하지만 클라이언트 snapshot 경로는 `details.search-trace`만 수용 | 저장된 요약이 있는데 “트레이스 표시 불가” | 기존 sanitizer 안에서 producer/consumer shape 정합화 |
| F02 | 재현 / P1 | HTML trace는 upsert지만 typed trace/score 행은 매번 transcript parent에 append | 같은 이벤트가 반복되면 진단이 중복·분산 | assistant 기준 소유권, summary upsert, event-id 중복 제거 |
| F03 | 재현 / P1 | 현재 builder의 옛 filter/sort 입력·data 속성·script가 현재 sanitizer에서 제거됨 | UI 기능이 사라지거나 설명만 남음 | 정적 JS가 만드는 안전한 필터/정렬 컨트롤로 재구현 |
| F04 | 정적 / P1 | 저장 trace를 `createdAt` 순서와 직전 non-system 메시지에 연결 | 동률·끼어든 user·지연 저장에서 오결합 여지 | 명시적 assistantMessageId와 v1 호환 reader |
| F05 | 정적 / P1 | Plan의 UNKNOWN 계약과 ChatWorkflow의 FALSE-only gate가 불일치 | 미측정 상태에서 plan 기반 확장 허용 여지 | 명시적 사용자 정책과 미측정 plan 확장을 분리 |
| F06 | 정적 / P1 | stage ledger는 TraceStore에 있지만 chat DTO는 요약 중심 | 선언·실행·skip·실패를 한 화면에서 정확히 보지 못함 | 기존 trace snapshot 경로에 제한된 typed projection |
| F07 | 정적 / P1 | `samples=evidenceCount*5+sessionRecur`를 promotion threshold에 사용 | 실험 표본이 없는 heuristic이 검증된 학습처럼 보임 | heuristic weight와 실제 관측 횟수를 구분 |
| F08 | 정적 / P2 | 실제 view renderer는 Jsoup인데 template에 th 속성이 남아 있음 | 옛 th 기반 컨트롤을 붙여도 서버 값/조건이 적용되지 않음 | `ChatUiViewConfig` projection과 함께 변경 |
| F09 | 정적 / P2 | 상세 snapshot fetch의 자체 timeout 없음 | 서버가 응답을 끝내지 않으면 2개 동시 조회 슬롯이 지속 점유될 수 있음 | 기존 abort/version 처리 위에 유한 timeout 추가 |
| F10 | 정적 / P2 | 큰 chat.js/controller와 다수 관측 표면 | 수정 시 다른 경로를 놓치기 쉽고 재작업 증가 | 새로운 복제본 대신 소유권 명확화; 대규모 분리는 별도 |

### 4.1 F01: 메타데이터형 스냅샷은 실제로 정상 표시되지 않는다

현재 `ChatTraceSnapshotPointerPersister`는 HTML이 없고 traceMemory 또는 harmony 메타데이터가 있으면 `<section data-trace="..." data-kind="metadata-only">` 형태의 HTML을 생성한다. `/api/diagnostics/trace/snapshots/{id}/html`은 저장된 HTML을 반환한다. 그러나 `chat-trace-ui.js`의 snapshot sanitize 경로는 최종적으로 `fragment.querySelector("details.search-trace")`만 반환한다. 이 때문에 해당 `<section>`은 정제 가능 태그이면서도 결과 루트로 선택되지 않는다. [E05–E08]

실제 제공된 JS로 구성한 테스트에서 정상 `details.search-trace` 스냅샷은 표시됐고, metadata-only section은 `트레이스 표시 불가`가 됐다. **“snapshot이 안 만들어졌으니 저장 모듈을 새로 만들자”는 진단은 이 경우 틀리다.**

권장 수선은 새 데이터에는 공통 wrapper를 사용하고, 기존 저장 데이터에는 제한된 호환 adapter를 적용하는 것이다. metadata-only는 “검색 상세가 아니라 저장된 진단 요약”이라고 명확히 표시한다. 전체 HTML 문서·임의 section·임의 script를 허용하는 방식은 금지한다.

### 4.2 F02: 하나의 답변에 여러 진단 소유자가 있다

`AwxChatTraceUi`는 WeakMap을 사용해 정확한 assistant bubble에 panel 하나를 붙이고 갱신한다. 이 구현은 보존할 장점이다. 반면 `renderTraceSignalDetail`, `renderScoreDeltaDetail`은 div를 새로 만들고 전달받은 `target`에 append한다. trace 이벤트 처리부는 `bubble.parentElement`를 전달한다. [E05, E13]

격리 fixture에서 같은 signal/event를 두 번 전달하면 signal 행 2개와 score 행 2개가 만들어졌다. 이는 스트림 재연결 전체가 잘못됐다는 증명이 아니라 **소비자 함수에 동일 이벤트의 중복 방어와 답변별 관리가 부족하다는 재현**이다.

요약은 덮어쓰고, 이력은 고유한 사건만 제한 개수로 보존한다. 점수 이력을 전부 하나로 덮으면 변화 관측이라는 장점을 잃으므로 `summary`와 `timeline`을 구분한다. 실제 provider SSE id/cursor를 이용할 수 있으면 그것을 쓰고, 임의로 전역 request의 최신 값을 가져오지 않는다.

### 4.3 F03: 옛 상호작용의 장점은 살리되 구현은 되돌리지 않는다

과거 chat.js는 `innerHTML`을 주입한 후 `script[data-trace-script="1"]`를 새 script로 만들어 실행했다. 현재 trace sanitizer는 이 경로를 차단한다. 현재 `TraceHtmlBuilder`에도 이전 방식의 필터 입력·정렬 속성·inline script가 남아 있으므로 안전한 클라이언트에서 그 기능이 사라지는 것은 자연스러운 결과다. [E14–E15]

복원할 것은 “실패/느린 단계 필터”, “순서·반환수·소요시간 정렬”, “안전한 진단 요약 복사”이지 inline script 실행 권한이 아니다. 컨트롤은 신뢰된 정적 JS가 DOM API로 만든다. 행 데이터는 typed 값 또는 명확히 제한된 정제 결과로 공급한다. 표시된 `hash/length`를 원문 쿼리처럼 속여서 보여주지 않는다.

OWASP의 HTML 정제 및 위험한 sink 회피 원칙에 따라, sanitize 이후 임의 script 재삽입이나 위험한 HTML 수정은 허용하지 않는다. [W01]

### 4.4 F04: 식별자 세 가지를 구분해야 한다

현재 assistantMessageId는 assistant 메시지를 저장할 때 얻는다. 이후 snapshot pointer를 system 메시지로 저장하고 반환하는 ID는 **그 system 메타 메시지 ID**다. 코드 일부에서 이를 `traceTurnId`라고 부른다. 세션 상세 builder는 pointer 직전의 non-system ID를 추정해서 TurnTraceDto에 넣는다. `createdAt`만 정렬하므로 같은 시각의 순서도 명시적이지 않다. [E07, E09–E11]

따라서 다음을 혼동하면 안 된다.

- `assistantMessageId`: 답변 메시지의 소유자.
- `traceTurnId`: 현재 호환 계약에서는 trace 메타 메시지 식별자일 수 있음.
- `snapshotId`: 상세 저장소에서 조회하는 불투명한 식별자.

새 포인터에는 검증된 assistant ID를 명시적으로 넣고, 읽을 때 같은 session의 assistant인지 확인한다. v1은 계속 읽되 `(createdAt,id)` 정렬과 이전 assistant 기준의 보수적인 fallback을 적용한다. 명확하지 않으면 `unbound`로 처리하고 다른 답변에 붙이지 않는다. 기존 DB 메타 메시지를 일괄 재작성할 필요는 없다. 실제 경쟁 조건 발생 여부는 실제 저장 트랜잭션과 exact-run 검증에서 확인한다.

### 4.5 F05–F06: Plan은 “전부 죽은 YAML”도 “완전한 워크플로 엔진”도 아니다

현재 `PlanExecutionSpec`은 when 조건을 tri-state로 평가하고 stage ledger를 만든다. 독립 테스트에서 미측정 metric은 UNKNOWN, 관측된 0은 실제 값, 미관측 stage는 DECLARED, 중복 stage는 SKIPPED_DUPLICATE, 미지원 stage는 UNAVAILABLE로 처리됐다. 이 기능을 다시 만들면 중복이다. [E16]

그러나 `ChatWorkflow`는 plan hints를 먼저 적용하고, FALSE일 때만 확장 키를 제거한다. `chatStageFlags`도 `state != FALSE`를 expansionEligible로 사용한다. 즉 **“UNKNOWN만으로 plan을 활성화하면 안 된다”는 명세와 호출부 사이에 간극**이 있다. 미측정 조건과 명시적 deep 검색·기존의 baseline Self-Ask를 구분하는 regression test가 필요하다. [E17]

검색 후 UNKNOWN→TRUE가 되면 현재는 `lateActivation=true`를 기록한다. 이 기록 자체가 뒤늦은 추가 검색을 실행했다는 뜻은 아니다. 이번 복원에서 이를 핑계로 두 번째 숨은 검색 루프를 만들지 않는다. 미지원 pipeline label은 unavailable로 보여주고, declared 순서와 observed 순서를 나눠 표시한다. [E18]

현재 chat의 `PipelineSnapshot`에는 planId·route·count·coverage 등의 요약은 있지만 전체 stage ledger 계약은 없다. 기존 `ChatStreamSignalBuilder`를 경유하는 작은 typed 확장으로 연결하고, 호환 생성자·구독자 projection·replay·새로고침을 함께 검증한다. 상세 조건 원문, header 값, 사용자 prompt 전체를 그대로 복사하지 않는다. [E19–E20]

### 4.6 F07: 전략 점수와 검증된 학습 결과는 다르다

`NineArtPlateGate`의 scoreCard 생성은 `samples`를 `evidenceCount * 5 + sessionRecur`로 계산한다. `ArtPlateEvolver`는 score와 이 samples를 기준으로 5%, 15%, 50% rollout을 정한다. 한 요청의 evidence 개수가 실제 A/B 결과 수처럼 쓰일 수 있는 구조다. [E23–E24]

전략을 조건별로 선택하는 장점은 남긴다. 다만 UI/로그에서 heuristic score를 정확도·성공확률·실험 표본 수로 표시하면 안 된다. 현재 측정 체계에 실제 성공·실패·지연·비용 관측이 없다면 “실험 완료에 따른 승격”은 보류한다. 단순한 `getLastSelected()` 전역 값은 그 답변의 결정 증거가 아니므로 답변 패널의 근거로 사용하지 않는다.

## 5. UAW의 장점 추출표

| 설계 요소 | 살릴 핵심 | 제거·상쇄할 단점 | 현재 연결부 / 수정 태도 |
|---|---|---|---|
| 동적 RAG 오케스트레이션 | 질문·사용자 설정·실행 상태에 따른 전략 선택 | 모든 요청에 풀스택 검색, 별도 엔진 증식 | ChatWorkflow / PlanHintApplier / UnifiedRagOrchestrator; 기존 경로 유지 |
| Self-Ask / 검색 확장 | 질문 분해·다른 관점·부족한 근거 보강 | 무조건 12~24개 fan-out, 재귀 재시도, OFF 무시 | 기존 budget과 flags; 호출 수·skip reason 관찰 |
| Anchor 압축 | 중요 키워드·출처를 살린 조건부 압축 | 무조건 강한 절단, 반대 근거 소거, 메모리 혼합 | ContextOrchestrator / DynamicContextCompressor; provenance·원본 fallback 보존 [E21–E22] |
| Weighted-RRF / 재랭킹 | 소스 간 융합·정밀 순위·다양성 | 점수 단위 혼합, 여러 번 중복 rerank | 기존 경로에서 input/output count와 비용 확인; 새 fuser 금지 |
| Plan DSL | 설정·전략과 실행 관측의 분리 | 선언을 실행으로 오인, UNKNOWN 허용, 숨은 재실행 | 현재 when/ledger 개선 [E16–E20] |
| MLA breadcrumb | 동일 실행을 제어와 관측에서 연결 | telemetry 원문이 제어 명령·외부 근거로 역류 | 정제한 read-only projection; trace는 증거 문서 아님 |
| Failure Pattern / CFVM | 실패 유형·복구 원인·빈도 재사용 | 휴리스틱을 학습된 진실로 표현, 무제한 JSONL | 실제 imports 보존; retention·rotation·scope는 런타임 검증 |
| MoE 전략 selector | 빠른 규칙 기반 경로 선택과 제한된 실험 | 가상 samples로 자동 승격, 전역 latest 혼입 | empirical observation과 heuristic 분리 [E23–E24] |
| Hypernova / ExtremeZ | 필요할 때 제한적으로 탐색 강화 | 모든 모드 기본 ON, 예산 확대를 회복으로 오인 | 기본 흐름 유지; 효과는 동일 질의집 실측 후 판단 |
| PromptContext → PromptBuilder | 문맥과 최종 prompt 조립 책임의 일관성 | 핸들러가 system prompt 우회, 출처 scope 소실 | 현재 memory-only/empty fallback 보존 [E21] |
| 첨부 IQR | 준비된 파일을 해당 사용자·세션 안에서 검색·재랭킹 | 미준비 파일 사용, private 파일의 전역화, 반복 횟수 과장 | 현재 handler는 iterations=1인 경로가 있음; 상태·scope 검증 [E25] |
| 세션 기억 | 실제 메시지·기억·설정을 이어서 사용 | 이전 답변/trace를 외부 사실 증거로 승격 | session hydration·settings merger·context assembly 회귀 [E30–E31] |
| GraphRAG | 출처·revision·동의·세션을 가진 지식 연결 | stale/deleted/private 노드가 답변에 남음 | GeneralGraphVectorGate의 fence 유지 [E26] |
| 임베딩 절단 | 인덱스 차원과 provider dimension의 명시적 계약 | 차원이 같으면 모델도 호환된다는 오해, 고정 속도·정확도 주장 | 모델·차원·정규화 fingerprint 확인; 차원 맞춘다고 재색인 생략 금지 [E28] |
| OpenAI 호환 provider | 로컬/클라우드의 인터페이스 일관성 | 모델명만 바꾸면 모든 capability가 같다는 가정 | 현재 선택모드·취소·종료사유 유지; API 전면 교체 금지 |
| Jev 결정 보조 | 한 질문의 제한된 결정 신호, deterministic fallback | 전사 중 반복 호출, 근거 없는 유료 fallback, 미응답을 거절로 오해 | 기존 off/shadow/on·budgetSkip·timeout 유지 [E27] |
| AutoLearn | 검증된 샘플 선별·유휴 ingest·중단 가능성 | 일반 채팅을 학습용 gate로 차단, 재색인을 LLM 훈련이라 표현 | 현재 retrain은 vector ingest; 둘의 성공을 구분 [E29] |
| 옛 UI | 단계표·질문별 상세·필터·정렬·기억할 설정 | bootstrap DOM 통째 이식, 스크립트 재실행, 진단 과밀 | 현재 plain JS/Jsoup renderer에서 필요한 기능만 복원 |

**예전의 기술 이름을 전부 UI 메뉴로 다시 만드는 것은 복원이 아니다.** 사용자가 실제 확인하려는 질문 — 어떤 모델·검색·문맥·차단·복구였는가 — 에 답하는 기능부터 되살린다.

## 6. 목표 UI와 데이터 계약

### 6.1 세 가지 정보층

**기본 대화:** 답변 본문, 안전한 출처, 선택 모델과 실제 사용 모델, 실제 검색 사용 여부, 중단/재시도 상태만 보여준다. 내부 trace가 없어도 정상적인 답변은 표시한다.

**운영자 답변 상세:** 해당 답변에 하나의 접힌 `이 답변 추적`을 둔다. 모델/route → 검색 단계 → 문맥 조립 → gate/fallback → 저장 상태를 순서대로 본다. raw/final 전환은 같은 패널에서 갱신한다. 안에는 검색 source별 단계표·count·duration·reason과 memory provenance 요약이 들어간다.

**전체 운영 진단:** 이미 존재하는 Pipeline Status / RAG Ops / Vector / Models / Brain 등의 운영 도구를 유지한다. 전체 서버 heartbeat와 특정 답변의 성공·실패를 혼동하지 않는다. 운영 진단이 미확인이라고 모든 답변을 보류하지 않는다.

새 framework·route·대형 dashboard는 필요하지 않다. 기본 접힘, 키보드 접근, 화면 폭 360/768/1440px 및 200% 확대, horizontal overflow와 focus 검증을 요구한다. 이 크기는 acceptance fixture 제안이지 실제 장치의 렌더링 검증 결과가 아니다.

### 6.2 식별자와 호환

새 응답/포인터 필드는 additive로 넣는다. Java record component 추가 시 기존 생성자, 모든 copy/projection, final/replay 경로가 동시에 컴파일되어야 한다. `traceTurnId`의 기존 의미를 조용히 바꾸지 않는다. v1/기존 단순 pointer/TRACE64를 읽는 경로는 유지하되 기존 raw HTML을 사용자 기본 본문으로 다시 섞지 않는다.

권장 개념 필드:

```text
schemaVersion          기존값 또는 additive version
assistantMessageId     해당 답변 DB id, session+role 검증
snapshotId             불투명한 상세 스냅샷 id
requestIdHash          표시용 correlation; 인증 수단 아님
planId / whenState     선언된 계획과 조건 판정
stageRows[]            source/stage/status/count/durationMs/reasonCode
contextSummary         history/memory/vector/web/attachment/graph의 기여 구분
storageState           live, summary_only, not_found, denied, error, timeout
```

이 목록은 **전면 DTO 교체안이 아니라 복원할 의미 계약**이다. 현재 `PipelineSnapshot`, traceSignal, durable projection 중 기존 구조를 최대한 재사용한다. 최종 생성 prompt 전체·API key·owner token·임의 SQL 결과·음성 원문을 저장하지 않는다. 표시용 해시도 access token처럼 취급하지 않는다.

### 6.3 이벤트와 상태의 의미

검색 OFF, 기능 사용했지만 결과 0, 아직 관측하지 못함, dependency 없음, rate limit, timeout, 취소는 서로 다르다. 현재 `finalWebTopK=null`과 빈 list의 구분을 유지한다. `EXECUTED`는 실행 관측을 의미하며 “정답 확인/검증 통과”라는 초록색 배지로 확대 해석하지 않는다.

SSE를 fetch POST로 읽는 현재 방식을 native EventSource로 덮지 않는다. WHATWG의 id/retry/Last-Event-ID 개념은 참고하되 fetch에서 재연결·cursor 복원이 자동 제공된다고 가정하지 않는다. 현재 exact-run replay와 terminal commit을 유지한다. Spring의 비동기 ThreadLocal 전파·disconnect 특성도 실제 실행 스택에 맞춰 검증한다. [W02–W03]

### 6.4 설정 복원의 범위

현재 기본 UI는 model, preferred/strict/auto, 검색 AUTO/OFF/LIGHT/DEEP, RAG를 이미 제공한다. 과거의 slider ID를 그대로 복사하면 동작하지 않는다. 기존 `/model-settings`와 `ChatRequestSettingsMerger` 및 session 저장 규격부터 확인한다. [E04, E30–E31]

이번 범위에서 복원 가치가 큰 것은 **현재 답변에 실제 적용된 설정과 그 출처**다. 예를 들어 사용자 요청 OFF와 저장된 기본 ON 중 무엇이 우선했는지, UI 선택 모델과 actual model이 왜 달랐는지 보인다. 고급 topK/provider/official-only 옵션을 다시 노출하려면 consumer·저장·복원·effective response가 모두 있을 때만 한다. consumer 없는 입력은 숨기거나 명확하게 비활성으로 둔다.

## 7. Codex 작업 분할과 순서

실행용 세부 지시문은 `Abandon.txt`에 있다. 아래 순서가 권장된다.

| 단계 | 목적 | 최소 변경축 | 완료 조건 |
|---|---|---|---|
| do00 | 실제 실행 루트·baseline 확정 | 읽기 전용 조사·기존 테스트 | zip와 실제 파일 drift, build/sourceSet, 기존 실패 분리 |
| do01 | metadata-only snapshot 호환 | persister/UI adapter·테스트 | 기존 details와 두 metadata shape 표시, XSS 여전히 차단 |
| do02 | 동일 답변에 진단 귀속·중복 방어 | chat.js/trace-ui·테스트 | summary 하나, 고유 event 이력만 보존, replay 중복 없음 |
| do03 | durable pointer의 명시적 답변 연결 | persister/restorer/session builder/callsite | v1/v2, 동률 timestamp, 끼어든 user, 새로고침 검증 |
| do04 | 옛 filter/sort의 안전한 복원 | static JS + CSS + 필요한 typed rows | inline script 없이 필터·정렬·요약 복사 |
| do05 | 운영자 발견성·설정 출처 개선 | template + Jsoup projection + JS | 기본 대화 uncluttered, 관리자만 상세, inert th 없음 |
| do06 | Plan UNKNOWN 계약과 ledger 연결 | Plan/ChatWorkflow/SignalBuilder/DTO | 미측정 확장 금지·기존 baseline 유지·정확한 stage 상태 |
| do07 | heuristic과 empirical 전략 성과 분리 | ArtPlateGate/Evolver·기존 관측 저장 | 가상 samples만으로 실험 승격 불가 |
| do08 | memory/attachment/graph/embedding 회귀 fence | 기존 테스트 우선, 증명된 결함만 수정 | scope·revision·설정·차원·PromptBuilder 경로 보존 |
| do09 | 종료 게이트와 설계 계약 고정 | docs+테스트 결과 | 전체 관련 테스트/빌드 및 화면 증거, 미확인 분리, 추가 작업 중지 |

한 단계 안에 실제 원인이 둘이면 작은 patch 둘로 나눈다. 각 단계는 적은 수의 실제 소유 파일에만 적용한다. 기능 그룹 이름으로 여러 패키지 전체를 예약하지 않는다. 기존 작업 조정 규칙이 있으면 재사용하고, 이번 리포트를 위해 work-ledger나 감독 agent를 새로 만들지 않는다.

## 8. 테스트 요구사항

### 핵심 UI/저장 회귀

1. admin/non-admin/anonymous × debug on/off의 initial/replay/session/snapshot 접근. HTML이 숨는 것과 서버 권한 검증을 각각 확인한다.
2. RAW→FINAL 한 답변에 한 패널. 답변 A→B 각자 격리. 동일 trace event 재전송 중복 없음.
3. 스냅샷 full-details/traceMemory metadata-only/harmony metadata-only/no stored HTML. 잘못된 shape는 fail closed, summary_only와 missing을 구분.
4. script/onerror/iframe/svg/style/id/name clobbering 차단. DOMPurify 없을 때 실행 가능한 우회 없이 안전한 안내.
5. 401/403/404/500/network/timeout/취소. 반복 retry가 새 생성 요청을 보내지 않음.
6. 세션 전환 중 늦게 도착한 snapshot은 무시. 토글 OFF 후 내용 제거. 삭제/새 대화 후 AbortController 정리.
7. v1, v2, malformed version, invalid ID, oversized summary, wrong session/role, timestamp 동률, assistant와 pointer 사이 user 입력.
8. native controls로 non-ok/slow/stage 필터와 정렬. 정렬 후 실제 evidence/source 연관 보존. copy에는 시크릿/원문 prompt 없음.

### 핵심 설계 회귀

9. Plan absent/TRUE/FALSE/UNKNOWN. 미측정값과 0 구별. 명시적 사용자 deep/기존 baseline은 plan UNKNOWN 때문에 무조건 OFF되지 않음.
10. declared stage, disabled, no binding, dependency missing, 실패, duplicate, caller-owned generation의 상태를 정확히 표시. lateActivation은 실행 사실과 구분.
11. web OFF/vector ON, web ON/vector OFF, 둘 다 OFF+memory ON, 모두 없음, attachment-only, graph unavailable 시 fallback.
12. 세션 A의 사실이 허용된 기억 경로로 이어지되 사용자 B/다른 owner에게 노출되지 않음. 세 번째 세션에서 저장·load·compression·prompt inclusion을 각각 증명.
13. 첨부 INDEXING/실패는 검색 제외, READY/ACTIVE는 실제 상태 enum에 맞게 포함. 삭제된 파일·owner 변경·다른 세션 차단.
14. Graph source revision/consent epoch 변경·삭제 뒤 delayed redrive와 prompt entry 양쪽에서 stale 자료 차단.
15. 압축 전후 출처 정보 유지, 과도한 중복압축 금지, 반례 소실 검사. 예외면 원본 context 유지하되 reason 기록.
16. heuristic score 높고 empirical sample 0인 후보가 자동 승격되지 않음. 실험군 할당과 성공 평가는 별도.
17. Jev off/shadow/on, 미설정/timeout/401/429/budget_skip. 실제 STT hot path 호출 수 0, 최종 질문당 유한 호출.
18. provider model 선택 preferred/strict/auto, 취소/timeout/stream final, UI model과 actual model 일치. strict가 임의 fallback되지 않음.
19. 임베딩 같은 차원·다른 모델, dimension underflow, 원시 차원 변경, normalize 변경은 올바르게 구분. 재색인은 별도 승인·작업으로 제한.
20. 일반 답변 release와 training dataset acceptance 분리. 학습용 근거 기준 미달이 모든 인터랙티브 답변 차단으로 번지지 않음.

위 테스트는 만들거나 실제 저장소의 동등 테스트를 재사용할 요구사항이다. 이 보고서가 그 모든 테스트를 실행했다는 뜻은 아니다.

## 9. 성능·비용·프라이버시 수락 기준

검증 baseline과 patch를 동일 질의집, 동일 설정, 동일 provider 조건에서 비교한다. 최소한 답변 생성 성공률, first event/first token/완료 시간, 재연결 시 추가 생성 횟수, 검색·Jev·LLM 호출 횟수, DOM 진단 node 수, snapshot bytes, UI 렌더 시간, 메모리/첨부 scope 오류를 기록한다.

“정확도 향상”, “3배 빠름”, “자가진화 완료” 등의 주장은 실제 비교 근거가 없으면 결과에 쓰지 않는다. snapshot lazy loading과 필터링을 위해 검색/모델을 다시 호출하지 않는다. 서버 전체 로그와 사용자 prompt를 묶어 자동 외부 전송하지 않는다. OpenTelemetry 문서도 모델 입출력이 민감하고 클 수 있음을 경고하므로 여기서는 기본적으로 count·enum·hash·출처 권한이 있는 요약만 쓴다. [W04]

**Jev 비용 주의:** Vercel 공지는 무료 기간을 2026-09-25까지로 안내한다. 현재 조사일은 2026-09-26이다. 코드의 `free-only`/기간 종료 시 `budget_skip`은 오류로 보고 제거할 기능이 아니다. 공지가 정확한 종료 시각·timezone까지 보장하는 것은 아니므로, 코드의 UTC timestamp를 공식 과금 시각처럼 단정하지 않는다. 현재 유료 허용·계정 잔액·실제 요금은 이 조사에서 확인하지 않았다. [W05, E27]

## 10. 금지사항과 명확한 종료 조건

금지: 과거 main 덮어쓰기, application 전체 설정 교체, bean override로 충돌 숨기기, 클래스 이름이 비슷하다는 이유로 package 통합, 새로운 RAG/DSL/trace 엔진, DOM script 재실행, 공짜 기간 가정, GPU 배치 재작성, provider 키 변경, 운영 DB arbitrary SQL endpoint, 원문 prompt/음성/토큰 수집, 메타데이터를 외부 사실 증거로 사용, 최신 snapshot을 아무 답변에나 연결, 소스만 보고 실제 배포 성공 주장.

종료: 실제 build root에서 관련 테스트와 전체 빌드의 fresh output을 확보하고, 관리자/일반 사용자·답변 2개 이상·새로고침·취소/재연결·기억/첨부 대표 흐름의 증거가 있으면 멈춘다. 미지원 실험 기능이 unavailable이라고 정직하게 보이는 것은 실패가 아니다. 그 기능을 전부 구현해야 복원이 끝나는 것은 아니다.

**최종 목표는 기능 수를 회복하는 것이 아니라, 현재 답변이 어떤 설계로 만들어졌는지 다시 추적하고 제어할 수 있게 만드는 것이다.**

## 참고 자료

내부 소스: `evidence/EVIDENCE.md`의 E01–E31, 각 소스의 SHA-256 및 행 범위.  
UAW: Library의 동일 이름 자료 관련 절을 설계 의도 자료로 활용. 구현/성능 증명은 아님.  
웹: `SOURCES.md`의 W01–W06. 공개 문서의 원칙을 적용했으며 사용자 비공개 서버의 정상 작동 근거로 사용하지 않았다.
