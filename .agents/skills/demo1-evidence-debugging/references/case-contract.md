# 사건 계약

복잡한 원인 비교, 예외·복구 수정, 에이전트 인계에 사용한다. 작은 원인 확정 수정에는 필요한 항목과 기존 증거 참조만 사용한다. 기록 양식을 새 저장소·DB·실행 프레임워크로 구현할 필요는 없다.

## DebugCasePacket

| 항목 | 보존할 내용 |
|---|---|
| intent | 원래 요청의 의미, 증상, 같은 입력의 expected/observed, 완료 조건. 권한·부정 조건·예외를 요약 과정에서 빼지 않는다. |
| scope | 읽기/진단/수정 범위, 활성 owner, 선언한 대상, 변경 불가 계약, 기존 심사·세션 참조. |
| identity | 저장소·branch/HEAD, 대상 파일과 현재 해시, build/runtime 식별, 관찰 시점. 모르는 값은 `unknown`. |
| reproduction | 입력의 안전한 참조·식별값, 재현 단계·환경·빈도, 정상 비교 사례, 관련 로그의 경로와 범위. |
| evidence | E-ID, 출처, 관찰 계층, 요청/실행/시도 단위, 시간 범위, coverage·누락 가능성, 동일 출처 관계. |
| mechanismStages | present/included/reachable/enabled/executed/causal의 확인 범위·근거·미확인 상태. 각 단계는 다음 단계의 증명이 아니다. |
| conditionModel | 조건식, 평가 이벤트와 시점의 상태, 실행 순서, 재평가 여부. 현재 설정과 시작 당시 설정을 분리한다. |
| hypotheses | 후보 ID, causal axis/공존 관계, 설명, 지지·충돌 E-ID, 예측, 반증 조건, `supported/ruled-out/unknown/confounded`. |
| activeProbe | 선택한 불확실성, 고정 조건, 변경 변수 하나, 가설별 예상, 실제 도구·대상, 제한 시간·횟수, 관찰 결과. |
| patchIntent | 증거에 연결된 최소 변경, 의미적 RED/재현 참조, 기존 심사 결과, 대상 preimage·복원 참조. |
| acceptance | 각 완료 조건의 필요한 증거·실행 owner·현재 상태·실제 결과. 진단 슬롯과 독립된 의무다. |
| budgetAndNext | 원래 기한, 사용한 시도, 남은 불확실성, 다음 하나의 결정 변경 행동. 재개가 예산을 초기화하지 않는다. |

인계 시 `intent/scope/identity`, E-ID와 관찰 경계, mechanismStages, 비활성 후보를 포함한 hypotheses, activeProbe 조건·coverage, 남은 acceptance를 보존한다. 기존 문서에 있으면 내용을 복제하지 않고 식별 가능한 참조로 넘긴다. 수신자가 결정에 필요한 제약을 표현할 수 없으면 해당 인계를 보류하고 누락을 명시한다. 해시는 바이트 동일성을 보여주며 원인·권한·검증 통과를 대신하지 않는다.

## 첫 두 턴 컨텍스트·근거 조립

이 증상이 있는 사건의 `reproduction/evidence/acceptance`에 아래 슬롯을 붙인다. 기존 사건·검사 결과를 재사용하며 모든 슬롯에 새 도구 실행을 할당하지 않는다.

| 슬롯 | A와 즉시 후속 B에서 기록할 증거 |
|---|---|
| turnPair | 같은 owner 참조·sessionId에서 A→B 순서, 각 assistantMessageId/runId/requestId/snapshotId와 실제 join 근거. sourceId는 각 본문·단계에 연결한다. 없는 ID는 `NOT_OBSERVED`, UI로만 얻은 session 연결은 `UI_JOIN_ONLY`; timestamp·모델명·checksum은 join을 대신하지 않는다. |
| intentAndSupport | A의 기대 의미·유용한 답변과 근거, B의 대명사/별칭→대상 해석, B가 새로 묻는 사실→직접 지지 본문/span을 별도로 기록한다. A 본문 재사용은 B의 사실도 지지하는 span이 확인될 때만 근거가 된다. 대상 해석 성공은 신규 질문 근거 확보 성공이 아니다. |
| evidenceStages | A/B별 `candidate → sourceBody → afterFilter → packing → actualProviderDispatch → citation → stored/reload`의 관측 결과·E-ID/sourceId·count/hash/locator 참조. dispatch는 해당 run/request의 실제 전송 메시지 안 본문 포함을 입증하는 기존 receipt/참조가 필요하다. packing count는 실제 전송의 증명이 아니다. citation은 주장/span 연결, 저장·reload는 같은 owner/session/message의 연결·의미 보존을 확인한다. |
| acceptance | A의 유용한 답변·실제 근거 전달과 B의 대상 해석·질문별 본문 전달을 같은 두 턴에서 재현할 인수 조건, 현재 증명 범위, 남은 검사 하나. 첫 턴·fixture/test PASS와 실제 A→B 성공은 별도다. 저장·reload 증상이 포함되면 해당 슬롯을 검증한다. 새 세션 시작은 별도 사례이며 타세션 기억을 자동 공유하지 않는다. |

단계의 count/URL/Sources 표시/HTTP200/ctx·memory 길이는 그 관측만 증명한다. 단계 이름이 붙은 count만 있으면 `countReported`로 기록하고 본문 포함은 미관측으로 남긴다. 본문 ID/receipt 없이 이를 packing 본문 주입 PASS로 쓰지 않는다. Sources append와 모델 본문 주입은 다른 경로일 수 있다. `actualProviderDispatch=NOT_OBSERVED`를 미실행·검색 미지원·모델이 못 읽음으로 바꾸지 않는다. `disabled`는 해당 단계의 명시적 실행 상태·reason이 있을 때만 쓰고, `no_citable_locator`를 전체 provider 비활성으로 확대하지 않는다. `executed_empty`와 filter starvation, metadata 미완성·scope 미결속도 구별한다.

검증 서비스 장애로 관측된 unknown, 일반 관측 부족, known rejected/contradicted를 따로 기록한다. `releaseAllowed/evidenceGatePassed`는 공개 정책 판정일 수 있으며 근거 존재·인용·검증 성공을 뜻하지 않는다. 공개/검증/knowledge write 판단은 [기존 정책](../../../../docs/agents-rules/DEMO1-EVIDENCE-ZERO-RELEASE.md)을 따른다. 유용한 일반 설명을 보존하고 미지원 신규 구체 수치·사실은 제외하여 미확인 범위를 밝힌다. 단순히 unverified라고 붙여 추측성 구체 주장을 재공개하거나 rejected 초안을 fail-soft로 재공개하지 않는다. 검증 assert를 약화하거나 허위로 안전한 답변을 강제하지 않는다.

실행 접점은 기존 snapshot/answer bundle·요청별 trace·focused tests다. `scripts/session413_evidence_answer_recovery_assist.py proof-gap --root . --file <기존 proof-gaps.json>`의 dispatch-messages/response-body/run-request-join/judge-executed/live-replay 슬롯은 위 미확인 경계에 연결할 수 있다. 도구의 `NOT_PROVEN/NOT_RUN` 의미와 receipt 요구를 유지한다. shape 검사 exit0은 제품 PASS가 아니며 합성 GREEN은 역사적 세션 복구가 아니다. 허용된 기록이 부족하면 그 슬롯과 다음 관찰을 남기고 접근거부 저장소를 우회하지 않는다.

## 증거 상태와 단계 전환

- `proposed`: 실행할 검사만 정했다. 실행 증거로 세지 않는다.
- `reviewed-evidence`: 제공되거나 이전에 수집된 기록을 판독했다. 원래 build/time/owner를 유지한다.
- `executed`: 이번 작업에서 실제 도구 실행과 결과를 관찰했다. `performedNow=true`, executionOwner, target/build와 coverage를 기록한다.
- `acceptance=pending|passed|failed|evidence_needed`: 각 완료 조건의 상태다. pending은 새 실패 분류가 아니다.

의미적 RED는 원래 동작 계약이 깨지는 재현이다. 단순 컴파일 오류나 잘못된 테스트 설정은 그 증상의 RED가 아니다. RED가 특정 가설의 인과를 자동 확정하지도 않는다. 원인 결론은 구별 관찰·통제된 변화·충분한 호출 근거와 함께 제시한다. 간헐적 증상은 관찰 기간과 재현 한계를 남기고, 합성 재현을 실제 환경 재현으로 표시하지 않는다.

진단은 가장 이른 **결정 관련** 미확인 경계 하나에서 시작한다. 무관한 저장소 실패가 다른 대상의 수리를 전부 막지는 않는다. 기존 필수 sourceSet·owner·lease·preimage 조건은 생략하지 않는다. 소스 수정이 이미 완료됐다면 현재 postimage를 확인하고 남은 검증부터 이어간다.

## 예외복구

아래에서 영향받은 계약에 해당하는 행을 선택하고 현재 구현·테스트를 먼저 확인한다.

| 경계 | 판단과 집중 반례 |
|---|---|
| 재시도 | transient와 permanent/disabled/잘못된 입력을 구별한다. 총 attempts와 원래 deadline이 보존되는지, 성공·최종 실패가 정확히 전달되는지 검사한다. |
| 쓰기·중복 | 기존 요청 식별·idempotency·중복 방지 계약과 실제 부작용을 확인한다. 응답 유실 후 재시도로 이중 쓰기가 가능한 반례를 검사한다. 안전한 재시도가 입증되지 않으면 그 재시도를 추가하지 않는다. |
| 취소 | 기존 취소·인터럽트 전파와 정리 경계를 보존한다. catch/fallback이 취소를 정상 결과로 바꾸거나 제한 이후 작업을 계속하지 않는지 검사한다. |
| timeout | 내부 호출 timeout과 전체 요청 deadline을 구분한다. backoff·후속 시도가 잔여 시간을 넘기거나 재개 때 예산을 초기화하는지 확인한다. |
| empty/fallback | 정상 빈 결과, 필터 고갈, timeout, rate limit, provider disabled를 기존 분류에 맞춰 구별한다. fake result나 예외를 삼킨 성공으로 대체하지 않는다. |
| 자원 복구 | 자원의 실제 소유자와 close/finally 범위를 확인한다. 성공·실패·취소 경로 모두에서 task-owned 자원이 정리되는지 검사한다. |
| 복합 원인 | retry와 이중 등록처럼 별개 causal axis를 보존한다. 한 실험에서 바꾸는 변수는 하나이며, 다른 원인이 남았는지 관련 acceptance에서 확인한다. |

실제 외부 쓰기나 파괴적 복구는 기존 operation-level authority를 따른다. 로컬 합성 입력·mock 경계 검사는 실제 공급자 시도 또는 외부 쓰기의 증거가 아니다. randomized 검사를 실제 수행했다면 seed·case ordinal/count·실패 입력 해시를 남기고 최소 반례로 줄인다. 단순 후보 목록 섞기는 fuzz 검증이 아니다.

## 기존 저장소와의 연결

현재 `AGENTS.md`, Gradle 설정과 활성 call path가 소유권의 근거다. root `main/java`, `main/resources`와 app `app/src/main/java_clean`, `app/src/main/resources`는 시작점이며 매 작업의 현재 설정으로 확인한다. 애플리케이션 수정 전 Java·의존성 버전과 보호 설정은 기존 소스 게이트가 요구하는 대로 검사한다.

RAG 최종 프롬프트는 기존 PromptBuilder/PromptContext 경계를 따른다. 같은 이름의 alias나 비활성 폴더를 새 구현 owner로 삼지 않는다. Notebook·PatchDrop·subsystem 작업이면 해당 기존 경로에 사건 기록을 전달하고 그 경로의 증거를 재사용한다. 이 스킬이 추가적인 심사나 쓰기 권한을 만들지 않는다.

외부 증거가 막히면 `holdScope, firstBlockingRule, blockingEvidence, independentWorkCompleted, repositoryWideHold`를 기록한다. 필수 UI/hardware/provider 조건이 남으면 전체 완료는 미증명이다. 로컬에서 확인한 변경은 그 범위로 보고하고 다음 검증 행동 하나를 제시한다.

## 짧은 사례

동일 요청 로그가 네 줄이어도 실제 실행 네 번을 뜻하지 않을 수 있다. E1에서 로그 복제 관계와 observation unit을 고정하고 H1=retry, H2=중복 등록, H3=로그 복제, 필요하면 H1+H2를 보존한다. 등록·시도 식별자가 갈리는 경계에서 검사 하나를 선택한다. 확인된 owner의 수정과 집중 테스트가 끝나도 명시된 UI 취소 검증은 `pending`이다. UI 검사를 실행해 결과를 얻기 전에는 새 UI 실패를 선언하지 않는다.
