# 요청별 최소 증거와 완료 판단

## 하나의 사례에 구간을 묶기

기존 request ID와 서버 trace를 재사용한다. 아래 이름은 **작업 보고서의 구간 이름**이며 새로운 앱 telemetry/API를 추가하라는 지시가 아니다. 현재 필드가 없으면 `not_observed`로 남긴다. 디버깅에 꼭 필요한 누락만 source-backed 가설과 기존 source gate를 거쳐 최소 계측한다.

| 구간 | 관측할 것 | 흔한 첫 실패 |
|---|---|---|
| input | 실제 타이핑/IME 완료, 입력 반영, 합성 case ID | input-not-reflected, composition-submit |
| ui_event | 버튼/Enter 조작, focus, 로딩 전환, 호출 수 | button-inactive, duplicate-submit |
| backend_request | POST 경로, HTTP status, request ID 상관관계, admission 결과 | request-not-sent, network-error, validation, admission-unavailable |
| rag_llm | 기존 request-scoped workflow/provider attempt와 terminal 결과, 필요한 검색 근거 | pipeline-not-entered, provider-disabled, timeout, lineage-not-observed |
| response | 응답 수신, JSON/DTO shape, content 길이, error 분류 | response-missing, non-json, empty-content |
| display_render | 로딩 종료, 답변/출처 카드, 의미 조건, focus, 스크린샷 | render-missing, stale-answer, layout-clipped |

증거 연결은 같은 `runId + caseId + attempt + requestId`를 기준으로 한다. submit 전 UI 이벤트에는 case/attempt를 먼저 부여하고 네트워크 관측 뒤 request ID에 연결한다. `traceTurnId`·`X-Trace-Snapshot-Id`·client request ID는 서로 다른 값이다. 실제 연결 근거가 없으면 시간 근접만으로 같은 요청이라고 확정하지 않는다. 세션은 원본 대신 필요한 동일성 boolean/hash만 사용한다.

첫 실패 앞뒤의 짧은 시간창에서 관련 로그를 좁힌다. 출처 없는 원인을 추측하기보다 `lastObservedSuccess`, `firstFailure`, `firstUnobservedStage`를 각각 기록한다. 서버 미시작은 `environment/runtime`이지 클릭 handler 결함이 아니다. HTTP503의 원인이 admission인지 provider인지는 현재 로그/trace로 구별한다.

## 저장 계약

현재 task의 기존 evidence/report 디렉터리에 시나리오별 redacted JSON/Markdown과 안전한 화면 증거를 둔다. 기존 경로가 없으면 `data/agent-handoff/codex/report/meta-display-browser/<task-id>/`를 사용한다. 한 파일은 사례별 증거, 한 요약은 사례 목록·판정·다음 행동을 담는다. E0–E4 receipt를 복제하는 새 승인 프로토콜은 만들지 않는다.

실제 값만 채울 최소 구조 예시(모든 null은 미관측이며 PASS 예제가 아님):

```json
{
  "runId": "task-scoped-id", "caseId": "M01", "attempt": 1,
  "observedAt": null, "evidenceKind": "live-browser", "result": "evidence_needed",
  "runtime": {"assetHash": null, "buildIdentity": null, "servedMatchesPostimage": null},
  "requestId": null, "requestCount": null, "httpStatus": null,
  "responseShapeValid": null, "responseChars": null, "sourceCount": null,
  "clientRoundTripMs": null, "ragMs": null, "llmMs": null,
  "stages": {"input": "not_observed", "ui_event": "not_observed", "backend_request": "not_observed", "rag_llm": "not_observed", "response": "not_observed", "display_render": "not_observed"},
  "normalAnswerSuccess": null, "fallbackObserved": null,
  "providerAttemptEvidence": "not_observed", "answerMatchesExpected": null,
  "lastObservedSuccess": null, "firstFailure": null, "firstUnobservedStage": "input",
  "reasonCode": "not-executed", "evidenceRefs": [],
  "cleanupConfirmed": null, "retestOf": null, "nextAction": "open-verified-runtime-url"
}
```

구간 값은 `pass|fail|not_observed|not_applicable`。`not_applicable`에는 현재 계약상 해당하지 않는 근거가 필요하며 정상 RAG 질문의 미관측 처리를 우회하는 값으로 쓰지 않는다. 시간은 실제 monotonic/서버 관측에서 가져오며 client 왕복 시간을 RAG/LLM 시간으로 바꾸지 않는다. 상태를 바꿀 때 실제 관측 시각·근거를 연결한다.

네트워크 증거는 method, pathname, status, 허용된 reasonCode, timing, shape/length, count, request ID만 남긴다. 현재 브라우저 도구에서 요청/응답 관측이 제공되지 않으면 승인된 기존 로컬 trace/log로 보완하고, 그래도 관측 불가하면 `network-evidence-unavailable`이다. 개발자 도구 화면/요청 목록이 허용된 API로 실제 접근 가능한 경우에만 사용한다. 가상의 HAR/응답/상태를 만들지 않는다.

raw 질문·답변·provider 응답·전체 오류 본문·headers·cookie·ownerKey·token·환경 덤프·민감 URL query를 저장하거나 외부 모델에 보내지 않는다. DOM/스크린샷에도 대화·계정·비밀 정보가 포함될 수 있으므로 task 합성 데이터 화면만 보관한다. 전체 HAR 대신 allowlist 요약을 사용한다. 로컬 복구용 preimage/diff도 외부 전송하지 않는다.

## 성공률과 미실행 분모

필수 사례/하위 사례 목록은 실행 전에 고정한다. 각 사례의 최신 결과를 집계하되 이전 실패와 수정 후 retest는 보존한다. 재시도 횟수를 통과 사례 수로 세지 않는다.

- `requiredTotal = pass + fail + degraded + evidence_needed + not_run`.
- `verifiedCoverage = pass / requiredTotal`. 분모가 0이면 null이다.
- `observedCasePassRate = pass / (pass + fail + degraded)`. 미관측 수를 바로 옆에 표시하며 이것만으로 완료를 주장하지 않는다.
- `normalAnswerRate = 의미·실제 생성·표시까지 확인한 정상 왕복 수 / 계획한 정상 왕복 수`. fallback, injected 응답, provider/필수 stage 미관측은 정상 성공 분자에서 제외한다.

예: 필수 8개 중 pass=2, fail=1, not_run=5이면 coverage=25%, 관측분 pass rate=66.7%, 전체는 incomplete다. 모델 3개의 의견은 이 수치를 바꾸지 않는다. 정상 요청의 fallback 화면 표시만 관측됐으면 `result=degraded`, `normalAnswerSuccess=false`, UI 전달 사실은 별도 보존한다.

## 종료와 보류

`browserFunctionalStatus=verified`에는 현재 runtime 계보, 필수 사례 통과, 입력 의미 조건, 필요한 단계/요청 상관관계, 주입 해제, 같은 실패 사례의 retest가 필요하다. 핵심 UI가 동작해도 provider attempt가 미관측이면 `providerAttemptEvidence=not_observed`이며 전체 RAG/LLM 정상 검증은 `evidence_needed`다. 원격 wire 세부 계보와 공식 Simulator/HTTPS/실기기의 추가 증명은 각각 별도로 보고한다.

HOLD는 `holdScope`, `firstBlockingRule`, `blockingEvidence`, `independentWorkCompleted`, `repositoryWideHold=false`와 다음 검사 한 개를 포함한다. 다른 작업 서버 재시작 불가, 없는 Simulator, API 지연 주입 불가는 각각 해당 사례만 막는다. 지속 goal은 기존 목표와 미완료 기준을 유지하고 반복 임계 조건 없이 blocked로 바꾸지 않는다.
