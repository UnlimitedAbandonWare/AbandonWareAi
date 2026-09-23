# E1–E6 패치 세션 독립 리뷰 — 문제점 보고서

- 작성일: 2026-09-19 · 리뷰어: Devin(별도 세션) · taskId: `llm-e1e6-patch-review-46b8d6d1`
- 대상 세션: `llm-api-e1-e6-patch-575ff9fd` (journal closed/verified, preflight APPLY 87.75)
- 방법: journal·checkpoint·diff·preimage 대조 + 소스 재판독 + **Gradle 테스트 재실행**(실제 검증)
- 상태 표기: [확인] 소스/실행 근거 · [미검증] 증거 부족 · [잔여] 패치가 커버하지 못한 경계

## 0. 독립 재검증 결과 (리뷰어가 직접 실행)

| 검증 | 결과 |
|---|---|
| `gradlew test --tests ConversateApiCueServiceTest --tests GeminiGatewayContractTest` | **exit 0** — 64/0, 16/0 (test-results XML timestamp 2026-09-19T11:34Z). 저널 주장과 정확히 일치 |
| 변경 파일 무결성 | UTF-8 정상, U+FFFD 0건, 한글/em-dash 손상 없음 (파일 바이트 기준) |
| 리스 상태 | `default.lock` 해제됨, 활성 리스 0 (gpu-failover 만료 lock 2개는 무관) |
| E4 placeholder | `main/java`에서 삭제됨, 참조 0건, lms-core 미러는 `build.gradle.kts` sourceSets(`main/java`)에 없음 → build-excluded 확인 |

**결론 요약:** 패치는 실제로 적용·테스트 통과했고 저널 주장은 정확하다. 다만 아래 문제점이 있다 — 1건은 잔여 기능 공백(P1), 1건은 diff 규약 위반(P2), 나머지는 위생·범위 문제(P3).

## 1. 문제점 목록

### P1 [잔여] E1 quota 분류가 공유 분류기 계층에는 미적용

- `ConversateApiCueService.quotaExhausted`(L503-514)만 확장됨: `blocked_api_access` 추가 + HTTP 상태 무관 body code 검사. 큐 루프 경로에서는 `policy.complete(...,"API_QUOTA_EXHAUSTED",...)` → `ConversateCueRoutingPolicy` L149/152가 quota 상태로 마킹해 sibling 재시도 차단(신규 테스트 `calls==["a"]`로 입증). **이 경로는 정상.**
- 그러나 공유 게이트 `LlmGatewayFailureClassifier.hasNonReplayableReason`(L31-77)은 여전히 **`status==429` + `code=="insufficient_quota"`만** non-replayable로 본다. 이 게이트를 쓰는 경로: `FallbackAwareChatModel` L301/L410(페일오버 재시도 판정), `ChatWorkflow` L6561/L6683, `LlmErrorClassifier` L29, `OpenAiResponsesChatModel` L251.
- 결과: Groq 지출한도 차단(HTTP 400 + `blocked_api_access`)이 **responses 경로 또는 FallbackAwareChatModel의 인-레이어 재시도**에 도달하면 여전히 "일시 오류"로 분류되어 같은 계정 재시도가 가능하다. 스터프4가 지적한 시나리오의 절반만 막힘.
- 추가 공백: `quotaExhausted`는 langchain4j `HttpException`과 `LlmGatewayException.reasonCode`만 검사한다. Spring `WebClientResponseException`(responses 경로가 던지는 타입)의 본문 code는 어느 분기도 못 본다 — `hasNonReplayableReason`는 WCRE를 보지만 429+insufficient_quota 한정이라 역시 미스.
- 조치 제안: `QUOTA_ERROR_CODES` 집합과 status-agnostic body 검사를 `LlmGatewayFailureClassifier.hasQuotaErrorCode`로 내려 공유화하거나, 최소한 `hasNonReplayableReason`에 `blocked_api_access`(HTTP 400)를 추가. 단 이 파일은 manifest 밖이라 **별도 change-set/리스 필요**.

### P2 [확인] CRLF→LF 전체 파일 플립 (최소 diff 위반)

- `GeminiGateway.java`: preimage `cycle-02-e2-gemini38/before/0.bin` = 824라인 전부 CRLF → 현재 828라인 전부 LF.
- `GeminiGatewayContractTest.java`: `before/1.bin` = 457라인 CRLF → 현재 547라인 LF.
- 원인: `apply_e2.py`/`apply_e2_test.py`(task dir에 잔존)가 EOL을 보존하지 않고 LF로 기록. `change.diff`가 -824/+828 전체 교체로 나오는 이유가 이것이다.
- 영향: 기능 손상 없음(테스트 green, 인코딩 무손상). 그러나 ① "minimal diff" 하드 제약 위반 ② 향후 해당 파일의 모든 diff/리뷰에 노이즈 ③ checkpoint 복원 시 EOL이 원본과 달라짐.
- 조치 제안: 파손은 아니므로 되돌릴 필요는 없지만, 향후 패치 스크립트는 `newline=''` 보존 또는 바이트 단위 치환을 사용할 것. `.gitattributes`가 없으면 이 저장소는 EOL 혼재 상태가 계속된다는 점도 인지.

### P3 [확인] 지시 범위 밖/미선언 변경

- **AGENTS.md에 신규 거버넌스 섹션 추가**: E5 지시는 `demo.interview.enabled` 정정뿐인데 `DEMO1-DEVIN-MULTI-SESSION` 블록(다중 Devin 세션 규칙, 약 30라인)이 같은 사이클에서 추가됨. 내용은 합리적이지만 ① 지시서에 없는 룰 신설 ② always-on 룰 파일이라 다른 모든 세션이 즉시 읽는다는 점에서 승인 없는 범위 확장이다. 유지/제거는 사용자 판단 사항.
- **manifest 밖 파일**: `.agents/skills/semantic-catalog.yaml` 리해시가 어느 cycle manifest의 target에도 없음(`sidecar-preimages/`에만 preimage 존재). 저널 verify 이벤트에 언급은 있으므로 은폐는 아니지만 선언 scope는 `docs/…3건`이었다.
- **잔여 산출물**: `cycle-01-e1-quota/`가 status=`prepared`로 방치(저널도 "stale…left unused (dead lease pinned)"로 자백). 정리 권고. `apply_e*.py`/`fix_e2_test.py`/`inspect_cat.py`/`refresh_catalog.py`는 task dir 안이라 허용 범위이나 정리 후보도 동일.

### P4 [미검증] Gemini 3.8 경로의 잔여 필드

- `cueJson` 분기의 `reasoningEffort("low")`(GeminiGateway L277-283)는 gemini-3.8에도 계속 전송된다 — 신규 테스트가 `reasoning_effort=="low"`를 assert하므로 의도된 유지다. Google OpenAI-compat이 3.8에서 `reasoning_effort`→`thinking_level` 매핑을 수용하는지는 로컬 mock 테스트로 입증 불가 → **evidence_needed**(실 엔드포인트 확인 시 유료 호출이므로 spend-guard 적용).
- `maxTokens`(L268-276)도 그대로 전송. `tokenParamKey`가 `max_tokens`/`max_completion_tokens`를 결정하는데 Gemini OpenAI-compat의 수용 여부 미검증. 구글 문서상 `max_tokens` 지원이라 위험은 낮음.
- prefix 판정 `startsWith("gemini-3.8-")`은 `"gemini-3.8"` 정확일치나 `"models/gemini-3.8-flash"` 프리픽스를 놓친다. 현재 설정(`priced-model: gemini-3.8-flash`)은 해당 없음 — 향후 이름 변형 도입 시 주의.

### P5 [운영] 라이브 미반영

- 18180 wear 런타임(unowned, 미재시작)은 패치 이전 클래스로 실행 중. E1/E2 효과는 ForceRestart/DevWatch까지 라이브에 없다. 저널이 "liveRuntime unowned - no restart"로 정직하게 표기 — 결함이 아니라 **미적용 상태**. 라이브 반영이 목표였다면 별도 승인 후 `start_rag_stack.ps1 -MetaDisplay -ForceRestart` 필요.

### P6 [경미] 기록 품질

- `change.diff` 산출물에서 비ASCII(한글/em-dash)가 `?`로 손실. 소스 파일 자체는 무손상(§0)이고 복구는 `before/*.bin` 바이너리로 하므로 실해성 없음 — diff 생성기의 인코딩 지정 누락으로 보임.
- `GeminiGateway.java` L249와 L284에 `builder.modelName(effective.model())` 중복 호출 — 무해하지만 정리 후보.

## 2. 잘된 점 (유지할 것)

- 신규 테스트가 실제 HTTP payload를 캡처해 필드 부재를 검증(mock 서버 패턴 재사용)하고, 비quota 400 오분류 방지 테스트(`nonQuotaHttp400DoesNotClassifyAsExhausted`)까지 포함.
- 저널·checkpoint·lease·PROJECT_STATUS 반영·check-model-lock 등 절차는 규칙대로 수행, 검증 주장과 재실행 결과 일치.
- E4 삭제 전 참조 0건 + lms-core build-excluded 확인, E5 정정 내용(`demo.interview.enabled` 실제 `false`)은 소스와 일치.

## 3. 해당 세션 Devin에게 — 우선순위 조치

1. **P1**: `LlmGatewayFailureClassifier.hasNonReplayableReason`에 `blocked_api_access`(및 status-agnostic quota code) 추가 여부 결정 — 별도 change-set으로. 큐 경로만으로 충분하다고 판단되면 "responses/failover 경로는 의도적 미적용"을 저널에 명시.
2. **P3**: `DEMO1-DEVIN-MULTI-SESSION` 블록이 사용자 승인된 추가인지 확인 요청. `cycle-01-e1-quota` prepared dir 정리.
3. **P2**: 이후 패치에서 EOL 보존 방식으로 수정(이번 건은 롤백 불필요).
4. **P4**: `reasoning_effort`/`max_tokens`의 Gemini 3.8 수용 여부는 유료 호출 없이 문서 근거나 `evidence_needed`로 남길 것.
5. **P5**: 라이브 적용이 필요하면 사용자 승인 후 ForceRestart.
