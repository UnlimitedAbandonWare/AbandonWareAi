# Nova Focus / Conversate — "모름 → 웹" 우선순위 표 (DEMO1-NOVA-FOCUS-WEB-UNKNOWN-20260928-R1)

상태: source 적용 + focused tests GREEN · 실기기(wear) NOT_RUN — capture 전후 모두 `status=not-running`.

단일 판정 지점: `main/java/com/example/lms/assist/UnknownAnswerPolicy.java`
- `classify(answer)` — 빈 답변 → `EMPTY_ANSWER`; `EvidenceAwareGuard.looksNoEvidenceTemplate` 일치 → `EXPLICIT_UNKNOWN`.
- `mode(jev)` — 사용 가능한 Jev verdict를 표의 모드로 환산. off/shadow/defer/CLARIFY → `GENERAL`(로컬 게이트 그대로).
- `decide(mode, requestWebOff, scopedWebEnabled, featureEnabled, webAttempted, trigger)` — 아래 표 그대로.
- `defaultWebAllowed(mode, scopedWebEnabled)` — RAG_CUE의 선제 웹 확장(모름 신호와 무관)도 같은 표를 따른다.

## 우선순위 표 (코드 = 표)

| 모드 / 조건 | unknown-웹 | reason 코드 |
| --- | --- | --- |
| 신호 없음 (`classify` → null) | 안 함 | `no_signal` |
| 요청이 웹 OFF (Focus 이미지 포함 요청) | 안 함 | `request_web_off` |
| `RECENT_ONLY` | 절대 안 함 (웹·벡터·그래프 확장 전면 금지) | `recent_only_precedence` |
| `SCOPED_RAG` 기본 | 안 함 (범위 내 BM25만) | `scoped_rag_precedence` |
| `SCOPED_RAG` + 명시 플래그 | 허용 | `scoped_flag` |
| `GENERAL`/`WEB`/`HYBRID` | 허용 | `allowed` |
| 기능 OFF (`*.unknown-web-enabled=false`) | 안 함 | `disabled` |
| 같은 요청에서 이미 웹 호출함 | 안 함 (요청당 최대 1회) | `web_already_attempted` |

평가 순서(왼쪽이 먼저): `no_signal` → `request_web_off` → 모드(`RECENT_ONLY`/`SCOPED_RAG`) → `disabled` → `web_already_attempted` → `allowed`.

## 바인딩

- **트리거**: `EMPTY_ANSWER`(빈 답), `EXPLICIT_UNKNOWN`(no-evidence 템플릿), `INSUFFICIENT_EVIDENCE`(rag 경로의 구조적 insufficient), `NO_CUE`, `LOW_ASR_CONFIDENCE`.
  - `NO_CUE`는 게이트 단계의 조용한 소음 차단으로 **유지** — ambient NO_CUE는 여전히 즉시 반환이며 웹을 태우지 않는다. 표의 `NO_CUE`는 "답변 단계에서 아무 카드도 생산되지 않은" 상태를 가리키는 enum 값이며, 현재 호출자가 그 지점에서 생성 실패를 웹으로 복구하지는 않는다(생성 자체가 깨진 경우 웹 증거로도 회복 불가 — 의도적 미연결).
  - `LOW_ASR_CONFIDENCE`는 정책 hook으로 존재한다. `Utterance.confidence`는 caption까지 도달하지만 현재 `answer()` 시그니처로 내려오지 않는다 → 미연결, 관련 라이브 검증 NOT_RUN.
- **Focus** (`NovaFocusAnswerService.answer`): 첫 `executeModels` 답변이 모름 신호이고 표가 허용하면 `useWebSearch=true`/`searchMode=AUTO`/`webTopK=3`으로 같은 요청 안에서 한 번만 재시도한다. 재시도도 공백이면 기존 `focus_empty_answer`. 재시도가 공백이고 1차 답이 비어있지 않으면 1차 답을 유지(fail-soft). 웹이 이미 켜진 요청은 `web_already_attempted`로 종료 — 연쇄 검색 없음.
- **Cue** (`ConversateApiCueService.answer`):
  - `SCOPED_RAG` verdict가 `RAG_CUE`를 유도해도 `defaultWebAllowed`가 거짓이면 기본 `retrieve()`를 건너뛰고 `retrievalSkipped=SCOPED_RAG_PRECEDENCE`를 기록한다 — 이것이 P0-B의 누수 수정이다.
  - 힌트가 모름 신호이고 이번 요청에 웹 호출이 없었으며 표가 허용하면, `conversate.web.unknownSupplement`로 표시된 한 번의 `retrieve()` + 근거 병합 + `rag=true` 재생성을 돌린다. 기존 `canSupplement`(NAVER 히트 후속) 경로는 그대로며 두 경로는 상호 배타다.
- **상한**: 검색은 `SINGLE_SEARCH_RESERVATION_USD` 예약, `max-attempts-per-stage`, `generationDeadline` 1.5s 잔량, 12s 총 한도(큐)/60s 총 한도(Focus) 안에서만 돌고, 한도 부족이면 그대로 유지한다.

## 설정 플래그 (기본값)

| 플래그 | 기본 | 의미 |
| --- | --- | --- |
| `conversate.cue.unknown-web-enabled` | `true` | 일반 cue의 모름→웹 보충 on/off |
| `conversate.cue.scoped-web-enabled` | `false` | SCOPED_RAG 하 웹 확장 명시 허용 (선제 retrieve + 보충 모두) |
| `conversate.focus.unknown-web-enabled` | `true` | Focus 모름→웹 재시도 on/off |
| `conversate.focus.unknown-web-scoped-enabled` | `false` | SCOPED_RAG verdict 하 Focus 재시도 명시 허용 |

## 채널 소유권 (P0-C)

- `hint` = 일반 cue/웹 보강 채널. 페이징(`hintPages`/`moveHint`)과 `expiresAt` TTL — 변경 없음.
- `focus` = Nova Focus 답변 채널. `DisplayRelay.Event.focus`(별도 필드) → `receiver.js` `state.focus` → `display-focus-flow.js` 순차/타자기 표시. 생산자 연결 끊김 시 focus만 즉시 내려가고 hint는 자체 TTL까지 유지된다(`DisplayRelayTest.focusAndHintStaySeparateChannelsAndFocusDropsOnDisconnect`).
- Focus 답변을 `hint`에 넣지 않는다. Focus에 페이지 번호 UI를 붙이지 않는다. `cancelWork(s)`는 세션 힌트 inflight/queue만 취소하고 Focus 생성은 `NovaFocusService`의 자체 executor(`nova-focus-answer`)에서 돌아 서로 건드리지 않는다 — 검증 결과 이미 분리돼 있어 변경 없음.
