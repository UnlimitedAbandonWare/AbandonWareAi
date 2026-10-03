# A3 — CTX 관측 뼈대

CTX 제품 패치는 Codex가 ATT-6 다음에 한다. 이 표는 측정 칸이다. 이번 세션은 카운트를 재지 않았고, 심볼이 있는지만 읽었다. 0을 측정값으로 적지 않는다.

## before / after 카운트

한 요청에서 다섯 수를 같은 run에 남긴다. 모델 호출됨, HTTP 200, ctx 필드가 채워짐은 이 표의 통과가 아니다.

| 단계 | 셀 것 | 이번 읽기 | before | after |
|---|---|---|---|---|
| 원문 근거 수 | 권한·인용 검사를 통과한 span | `ChatWorkflow` 3003–3010행이 attachmentIds일 때 localDocs를 넣는다. span 수는 안 읽음 | not_observed | Codex |
| refiner 입장 사유 | `prompt.context.refiner.reason` | 기존 테스트가 `disabled`, `provider_disabled`, `selected`와 `candidateCount=3`을 기대한다. 요청 1건의 라이브 값은 없음 | not_observed | Codex |
| 보조 호출 수 | 역할 `context_prepare`의 실제 호출 | `context_prepare`, `evidence_pack`, `PreparedContextPacket`는 java/yml 검색에서 나오지 않았다 | not_observed | Codex |
| builder 자료 수 | 가설이 아닌 근거로 `PromptBuilder`에 들어간 수 | `StandardPromptBuilder`는 `ensembleCandidates`를 렌더한다. 단일 근거 packet 필드는 `PromptContext`에서 보이지 않았다. 있는 필드는 `contextRefinementSummary`와 `contextRefinementSignals` | not_observed | Codex |
| 최종 messages 자료 수 | UserMessage 자료 구역의 근거 수. SystemMessage 안의 본문은 이 칸이 아님 | `ChatWorkflow` 3456행이 `unifiedCtx`를 `SystemMessage`로 넣는다 | not_observed | Codex |

상태 이름은 지시서의 `SKIPPED → STARTED → RECEIVED → VALIDATED → RENDERED → DISPATCHED`를 그대로 쓴다. DISPATCHED는 spy가 본 입력이고, provider가 이해했다는 증거가 아니다.

## 단일 정리와 2/3 가설

`EnsembleFinalAnswerService`를 읽었다.

- `isReusableAttachedDualPair` 324행: 후보 수가 2가 아니면 false. 방향은 SUPPORT와 FALSIFY.
- `isReusableAttachedThreeRoleSet` 343행: 후보 수가 3이 아니면 false. nodeId는 `support`, `support_alternative`, `falsify`.

단일 근거 정리를 이 두 검사에 넣지 않는다. 개수 조건을 `>= 1`로 완화하지 않는다. `ensemble.sampling.enabled`를 켜서 단일 정리를 대신하지 않는다.
한 요청에서 legacy 가설 세트와 `evidence_pack`이 둘 다 실행되면 실패다. 그 전략 키는 아직 제안이다.

## SystemMessage 승격 (C08 / C09)

| 위치 | 읽은 동작 | 회귀 |
|---|---|---|
| `ChatWorkflow` 3454–3461행 | 문맥 `unifiedCtx`를 SystemMessage로 넣은 뒤 role 메시지와 마지막 사용자 질문을 붙인다 | CTX가 켜진 경계에서는 첨부 본문·정리문을 이 SystemMessage에 넣지 않는다. 신뢰된 지시는 기존 `buildInstructions`에 남긴다 |
| `ChatConversationContext.fit` 66–79행 | cap을 넘으면 builder 결과를 다시 `SystemMessage.from`으로 만들고, 마지막 메시지는 유지한다 | `fit`이 packet을 빼거나 미신뢰 자료를 SystemMessage로 다시 올리면 CT12 실패 |
| `PromptContext.toBuilder` | `ensembleCandidates`, `contextRefinementSummary`, `contextRefinementSignals`를 복사한다 | 새 packet 필드를 넣으면 `toBuilder` 복사 누락이 실패다 |

이 변경은 CTX가 활성인 경계만이다. 모든 프롬프트 경로를 한 번에 바꾸지 않는다.

## 서브 실패 시 원문 유지

CT07 스케치. 보조 결과가 timeout, 429, 또는 invalid JSON이면 추가 보조 호출은 0이다. packet은 버리고, 이미 검사한 원문 묶음으로 주 답변을 계속한다. 정상 본문을 HOLD로 바꾸지 않는다. parent deadline이 이미 끝났으면 새 주 호출도 시작하지 않는다.
이 분기는 심볼 검색상 아직 없다. 테스트를 먼저 실패하게 두고 구현한다.

## ensemble 전역 완화 탐침

위 324행과 343행의 `size() != 2`, `size() != 3`이 그대로인지 패치 후에 다시 읽는다. 그 조건을 느슨하게 만든 diff는 CTX 완료가 아니다.
일반 대화의 5초 / 30초와 기존 호출 상한을 이 기능 때문에 올리는 diff도 완료가 아니다. 승인된 분석에서 보조 1회가 들어가는 경로는 별도 케이스다.

## 제안 테스트와 명령 칸

CT01–CT16은 `00_START_CONTEXT_PREPARATION_2026-09-28.txt` 6절의 제안이다. 통과한 제품 테스트가 아니다. 첫 Codex CTX 패치가 고르는 한 개의 FQCN과 명령을 여기에 나중에 적는다. 이번 칸은 비어 있다.
