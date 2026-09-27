# Meta Ray-Ban Display — Nova Focus 소스수정 지시서

작성일: 2026-09-23 (Asia/Seoul)  
분석 기준: `mai2111n.zip` + 첨부 `붙여넣은 마크다운(1).md`  
대상: 기존 Fold6 Conversate 전사 → 기존 Meta 렌즈 웹앱.  
목적: 이미 켜진 전사에 호출어 **노바**가 들어오면 집중 대화 화면을 열고, 같은 대화방에서 질문·답변을 이어가다가 설정한 시간이 지나면 화면만 닫는다.

> 아래는 실행 에이전트에게 넘길 구현 지시서다. 현재 ZIP에 기능을 실제 패치하거나 Java 빌드·외부 서비스·Fold6·렌즈 실기기 검증을 완료한 문서는 아니다. 신규 클래스·API·기본값은 **제안 계약**이며 기존 구현으로 오인하지 않는다. 코드 근거는 함께 제공한 `Nova_Focus_Source_Evidence.md`의 E01~E28을 참조한다.

## 0. 작업 원칙과 범위

기존 수음·전사·RAG·검색·소유권·렌즈 전달 경로를 재사용한다. 독립 음성 호출 엔진, 삼성 다른 앱의 전사 탈취, 서비스 워커의 상시 마이크, 네이티브 Android 앱 재작성, 다른 Meta 앱 위로 강제 전환, 꺼진 안경 자체의 시스템 깨우기는 이번 범위가 아니다. **현재 웹 전사가 살아 있고 렌즈 웹앱이 실행 중인 상태의 인앱 모드 전환**이다.

원본 경로와 실행 source-set을 먼저 확인한다. ZIP은 `java/`, `resources/` 중심이며 `gradlew`, Java 빌드 설정, 테스트 source-set은 포함되지 않았다. 이 문서의 파일 경로는 ZIP 상대 경로다. 실행 환경에서 실제 canonical root에 대응시킨다. `src/main/java`라고 추측하여 동명의 다른 복제본을 수정하지 않는다.

Git 초기화·checkout/reset/clean/stash/rebase/worktree·브랜치 이동을 전제하지 않는다. 기존 프로젝트의 비Git 작업 규칙, 파일 리스와 체크포인트, preimage SHA-256 및 최소 패치 방식을 우선한다. 작업자 간 파일 소유권을 나누고, 타 작업자의 편집을 덮어쓰지 않는다. 관계없는 오케스트레이션 `nova` 설정, 모델 이름 Nova, Deepgram Nova 모델은 이번 음성 호출어와 별개다. 코드 이름은 `NovaFocus`로 구분한다.

서비스 ON/OFF만 만든 뒤 완료로 보고하지 않는다. **ASR 입력 → 상태 전환 → 모델 입력 → 영구 기록 → lens/text 계약 → 실제 렌더링 → 자동 복귀**까지 하나의 기능이다.

## 1. 실제 소스에서 확인한 연결 지점

| 근거 | 현재 구현 | 이번 변경에서 중요한 이유 |
|---|---|---|
| E01~E02 | `ConversateAsrBridge`가 provider 이벤트를 `Utterance`로 만들어 `sessions.submit(...,"phone_voice",...)`에 전달한다. Deepgram 어댑터는 확정 구간을 합쳐 발화 final을 만든다. | 새 마이크나 `SpeechRecognition.onresult`를 별도로 붙이는 작업이 아니다. 이미 존재하는 정규화 전사 이벤트를 사용한다. |
| E03 | `ConversateSessionService.submit`에 staleAudio/epoch/dedup/자막 갱신 및 `!s.hintsEnabled` 조기 반환이 있다. | 단순히 기존 힌트 발생 분기에 호출어 검사를 추가하면 자동 힌트 OFF에서 Nova도 막힌다. |
| E03, E06 | 기존 final 처리 및 누적·강제 힌트 경로가 `cancelWork(s)`와 공용 inflight를 사용한다. | 주변 발화가 집중 답변을 취소하거나 다음 힌트가 집중 화면을 덮지 않게 별도 작업 수명주기가 필요하다. |
| E07 | 질문 정책에는 source revision 검증 외에도 `lastQuestionHash` 기반 동일 문장 억제가 있다. | 동일 질문을 나중에 다시 하는 것은 유효하다. Focus에 주변 대화용 문장 억제를 그대로 쓰지 않는다. |
| E08 | `ConversateAnswerPipeline`은 cues가 있으면 즉시 cue 경로로 반환한다. 다른 경로도 `ephemeral`, `sessionId(null)`이며 문자열 history를 모두 user 역할로 매핑한다. | 영구적인 집중 대화·역할 있는 문맥·명시 질문 응답을 기존 cue 호출만으로 구현했다고 주장할 수 없다. |
| E09~E10 | `ChatWorkflow`는 `sessionId` 기반 historyStr과 최종 `msgs`를 조립한다. 정적 검색에서 `getHistory(` 호출이 없고, 확인한 최종 조립은 system 메시지들 뒤 현재 user를 추가한다. | DTO.history만 추가하고 끝내지 않는다. Focus에서 구성한 문맥이 실제 provider messages에 들어가는 것을 검증해야 한다. |
| E11~E15 | `LensText`는 conversation/hint 중심이고 `/lens/text`는 내용만 읽는 경로다. relay도 caption/hint/display 위주다. | 별도 `focus` 계약과 변경 버전, 렌즈 renderer 추가가 필요하다. 기존 hint 필드에 억지로 섞으면 hints OFF/TTL에 가려진다. |
| E16~E18 | Fold의 설정 UI가 있고 localStorage 값을 재적용한다. 전역 Escape는 stop을 누르고 pagehide는 voice.stop을 호출한다. | 같은 문서 안 overlay로 만들며, Focus 닫기가 기존 stop까지 전파되지 않도록 수정한다. |
| E19~E21 | 클라이언트 `canSubmit`은 voice 활성 중 입력 제출을 막는다. Meta의 주 reader는 `/lens/text`다. | 음성 중 기존 input 버튼을 강제로 누르는 구현을 피한다. 서버 이벤트 라우터와 별도 Focus 제어 API를 연결한다. |
| E22~E27 | ChatSession/ChatMessage와 bounded history query는 존재한다. 일반 chat admission filter는 `/api/chat*`의 일부 경로만 다룬다. | 대화 저장 모델은 재사용하되, 내부 Focus 호출의 권한·멱등·비용 제한이 자동으로 적용된다고 가정하지 않는다. |
| E28 | meta-display는 rolling을 켜며 cue quiet/cooldown/force 및 display/context TTL을 가진다. | 이 값들은 Focus 질문 제출·답변 후 대기·영구 기록 보존 설정과 별개다. |

위 내용은 정적 소스 확인이다. 활성 Bean, 실제 실행 프로파일의 override, DB schema 적용 여부, 현재 배포본과 ZIP의 동일성은 실행 환경에서 추가 확인한다.

## 2. 사용자 동작 계약

```text
기존 Fold6 전사 시작 (사용자 동작)
  └─ Nova 호출 OFF: 기존 Conversate 동작 그대로
  └─ Nova 호출 ON: 이미 들어오는 새 전사 이벤트만 검사
       └─ 독립 호출어 ‘노바’ 확인
            → Fold 집중 패널 + Meta 집중 화면
            → 호출어 뒤 질문을 실시간 수정 표시
            → 질문이 확정되고 발화 종료 대기를 충족하면 한 번 제출
            → 기존 RAG/검색을 필요한 경우 사용
            → 질문·완료된 답변을 같은 방에 기록
            → 답변 표시 후 설정 시간 동안 호출어 없이 후속 질문
            → 무발화 타임아웃이면 패널만 닫고 Conversate로 복귀
다시 ‘노바’: 이전 방 재사용. 새 방 생성하지 않음.
```

일반 자막과 자동 힌트는 그대로 존재한다. 집중 모드 동안 자동 힌트 생성·앞면 표시만 일시 억제하고 `hintsEnabled` 사용자 값은 변경하지 않는다. 닫힌 후 원래 값을 그대로 따른다. Focus 중 쌓인 발화 전체를 닫는 순간 새 힌트 트리거로 몰아서 보내지 않도록, cue 전용 누적 기준점만 현재 위치로 재설정한다. 영구 대화 기록은 초기화하지 않는다.

호출 전 주변 전사는 기존 자막 정책대로 처리하되 Nova 영구 채팅 메시지로 모두 저장하지 않는다. 기본 모델 문맥은 **명시적으로 시작한 Nova 대화의 기록**이다. 사용자가 선택한 TXT나 현재 대화 배경을 참고하게 할 때는 승인된 범위만 별도 비신뢰 자료로 전달한다.

## 3. 설정창

`resources/static/assets/display/index.html`의 `advanced-settings` 안에 독립된 **노바 집중 대화** 구역을 넣는다. 기존 `ld-quiet`, `ld-cooldown`, `ld-force`, 힌트 TTL을 바꾸거나 이름만 재활용하지 않는다.

아래 수치는 실기기 측정 전의 **초기 제안값**이며 확인된 최적값이 아니다.

| 항목 | 내부 필드 | 기본값 / 검증 |
|---|---|---|
| 노바 음성 호출 | `enabled` | OFF. 켜도 마이크를 새로 시작하지 않는다. |
| 호출어 | `wakeWord` | `노바`. 정규식이 아닌 리터럴, 1~16 Unicode code point. 비어 있음/제어문자 거부. |
| 질문 제출 대기 | `utteranceQuietMs` | 1,200ms. 500~5,000ms. 확정된 발화 조립과 함께 적용. |
| 답변 후 후속 대화 대기 | `followupIdleMs` | 20,000ms. 5,000~120,000ms. |
| 호출만 하고 질문하지 않을 때 | `wakeListenTimeoutMs` | 8,000ms. 3,000~30,000ms. 새 실제 입력이 있으면 갱신. |
| 수동 조작 | 버튼 | 집중 대화 열기 / 닫기 / 이전 기록 보기. 수음 시작·중지 버튼과 분리. |

`enabled`는 **음성 자동 진입** 스위치다. OFF에서도 사용자가 명시적으로 수동 창을 열어 텍스트로 질문할 수 있다. 다만 ON→OFF 변경 순간 활성 Focus는 종료하고 Focus 대기·생성 작업을 취소한다. 계속 대화하려면 이후 수동 열기를 명시적으로 누른다. 기능의 서버 전체 kill switch(`available`)는 별도로 두고 false면 수동/자동 모두 비활성이다.

설정 변경은 소유권과 producer 검증 후 DB에 저장하고 설정 버전을 반환한다. Fold localStorage는 표시용 캐시만 맡긴다. 최초 폴링 때 오래된 localStorage 값을 서버에 무조건 덮어쓰지 않는다. 초기 GET은 서버가 기준이며, UI의 명시적인 저장에 `expectedSettingsVersion`을 보낸다. 409 충돌은 최신 설정을 읽고 차이를 표시한다. 자동 재시도 덮어쓰기는 금지한다.

실행 가능 표시를 함께 넣는다: `꺼짐`, `켜짐·전사 중지`, `호출 대기`, `집중 대화 중`, `연결 중단`, `저장소 미준비`. ON인데 수음 중지가 되어 있으면 ‘호출 대기’라고 표시하지 않는다. `응답 생성 중`, `기록 저장 실패`, `렌즈 표시 미확인`도 혼동 없이 표시한다.

## 4. 상태와 ID를 분리한다

권장 상태는 `OFF`, `ARMED`, `WAKE_PREVIEW`, `LISTENING`, `THINKING`, `ANSWER_READY`, `WAITING`, `SUSPENDED`다. 오류는 상태와 별도의 제한된 reason code로 표시한다. API 작업 취소와 UI 비표시는 명시적으로 구분한다.

- `OFF`: 자동 호출 OFF. 수동 열기는 별도 명시 동작으로 허용.
- `ARMED`: 사용자 허용 + 현재 수음 활성. 과거 transcript를 재검사하지 않음.
- `WAKE_PREVIEW`: interim에서 호출어 후보 발견. 패널을 먼저 미리 보여줄 수 있지만 아직 방 생성·LLM 호출·확정 기록 저장 금지.
- `LISTENING`: 호출 확정 또는 명시 수동 열기. 현재 질문 draft 수정 표시.
- `THINKING`: 요청 접수·문맥 선택·검색·생성·완료 기록 저장 중. 후속 무발화 자동 닫기 적용 금지.
- `ANSWER_READY`: 답변 저장 및 출력 준비 완료. 새 답변 render receipt를 짧게 기다림.
- `WAITING`: 답변 후 후속 질문 대기. 새 발화는 호출어 없이 다음 질문으로 수집.
- `SUSPENDED`: capture/producer 또는 전사 연결을 잃음. 대화방은 유지하고 자동 제출은 중지. 복구 후 오래된 interim을 자동 제출하지 않음.

`assistId/epoch`는 전사 transport 세션, `activationId`는 집중 화면을 한 번 연 구간, `turnId`는 한 질문, `chatSessionId`는 영구 대화방이다. `serverInstanceId`, `stateVersion`, `settingsVersion`도 별개다. 호출마다 `activationId`는 바뀔 수 있지만 **영구 방 ID는 유지**한다. provider stream 교체 ID는 capture/발화 식별에만 쓰며 방을 만들지 않는다.

상태를 바꾸는 주체는 서버의 단일 Focus 상태기다. Fold와 렌즈는 검증된 상태 projection을 렌더링한다. 클라이언트가 자체 키워드 매칭으로 별도 LLM 요청을 만들지 않는다. GET/poll/lens read는 상태 변경·발화 제출·추론·방 생성 없이 snapshot만 읽는다. 타이머 전이는 scheduler가 담당한다.

각 처리 결과는 `(ownerScope, producerGeneration, activationId, turnId, stateVersion)`로 fence한다. 이미 닫힌 activation, 다른 producer, 취소된 turn의 늦은 응답은 화면을 다시 열거나 새 답변으로 반영할 수 없다.

## 5. ASR와 호출어 라우팅 — 가장 먼저 구현할 부분

### 5.1 삽입 순서

`ConversateSessionService.submit`의 phone_voice 경로를 다음 책임으로 분리한다.

```text
owner/assist/epoch/수음 source 검증
→ event 구조·source revision·중복/역전 검증
→ 새로 받아들인 전사만 일반 caption에 반영
→ NovaFocus에 동일 이벤트 전달
   ├─ Focus가 소비: 기존 cue question 경로로 중복 전송 금지
   └─ Focus 비활성: 기존 cue 경로 유지
→ 기존 hintsEnabled/quiet/cooldown/자동 힌트 정책
```

실제 source의 `policy.accept...`는 입력 검증과 의미 억제가 섞여 있으므로 그대로 모든 결정을 재사용하지 않는다. `ConversateQuestionPolicy`에서 **source event 수용 검사**를 분리하거나 같은 검사 규칙을 공유 컴포넌트로 추출하고, Focus의 명시 질문에는 `lastQuestionHash`나 자동 힌트 `NO_CUE` 억제를 적용하지 않는다. OFF일 때 기존 결과가 바뀌지 않는 회귀 테스트를 먼저 둔다.

일반 caption 업데이트 앞뒤에 있던 `cancelWork(s)`를 무조건 유지하면 Focus 진입 이전에 작업이 취소될 수 있다. cue 작업 취소와 Focus 작업 취소를 다른 실행 핸들/세대에 묶는다. `maybeAccumulatedHint`, `maybeForceRollingHint`, 일반 dispatch, `control(hints_*)` 모두 Focus 활성 시 cue만 제어하도록 점검한다.

### 5.2 호출어 판정

입력은 그 순간 받아들인 `Utterance.text`다. 렌즈에 보낸 280자 rolling caption, 전체 TXT, 과거 채팅 로그, polling response, assistant 답변 문자열을 입력으로 삼지 않는다.

매칭용 사본에서 Unicode 정규화 및 공백·영문 대소문자를 정리하되, 저장할 원문 질문의 숫자·부정·이름을 바꾸지 않는다. 원문 범위를 정확히 추적하여 **매치한 호출어 한 번과 경계 구두점만 제거하고 뒤 질문을 보존**한다. 전역 replace는 금지한다. 정규화 전후 index가 달라질 수 있으므로 normalized 문자열 index를 원문 substring에 그대로 쓰지 않는다.

기본은 독립된 리터럴 토큰이다. Java의 Unicode 문자/숫자/결합문자와 `_`를 포함하는 경계 규칙을 작성한다. ASCII `\b`의 한글 동작을 가정하지 말고 테스트한다. `노바`, `노바, 지금 내용을 정리해줘`는 수용하고 `슈퍼노바`, `노바크`, `노바카인`은 제외한다. 공백으로 분리된 `노 바`나 유사 발음은 기본 매치하지 않는다. 별칭/퍼지 인식/화자인증은 후속 범위다.

발화 중간의 독립 호출어도 수용하되 호출어 앞 문장은 새 질문으로 자동 저장하지 않는다. 단순 텍스트 매칭은 인용된 호출어나 다른 사람이 말한 호출어의 의도를 구별할 수 없음을 UI 설명과 실기기 오탐 측정에 반영한다. 이를 화자인증처럼 홍보하지 않는다.

### 5.3 부분 결과, 최종 결과, 질문 조립

`(captureEpoch, providerStreamId, utteranceId)` 단위로 revision을 관리한다. 동일 key의 interim은 추가 메시지가 아니라 draft 교체다. final 뒤 낮은 revision/interim은 거부한다. 마지막 revision이 같은데 다른 확정 본문이면 충돌 사유를 기록한다.

interim에 `노바`가 있다가 `노바카인`으로 바뀌면 preview를 되돌리고 LLM 호출 0회를 유지한다. 이미 확정한 호출이 있으면 뒤 interim 수정은 질문 draft만 교체한다. 과거에 확정된 호출어의 재전송으로 다시 열지 않는다.

Deepgram의 `is_final`과 `speech_final`/UtteranceEnd 차이는 기존 `DeepgramAsrTransport`가 일부 처리한다. 어댑터가 이미 합친 final을 또 덧붙여 중복 질문을 만들지 않는다. Soniox/local provider의 종료 의미도 계약 테스트로 맞춘다. 이번 quiet timer는 **정규화된 전사 이벤트 위의 질문 조립기**이며 provider를 모르는 상태에서 final semantics를 바꾸는 패치가 아니다.

질문 조립기는 새 의미 있는 발화 변화가 있을 때만 quiet deadline을 갱신한다. 같은 이벤트 재전송·동일 poll·DOM paint는 갱신 사유가 아니다. 안정된 final 구간과 종료 신호를 기준으로 질문을 확정한다. 최신 interim이 남아 있으면 타이머가 지났다는 이유만으로 그것을 final로 승격하지 않는다. 긴 질문의 확정 구간들을 보존하고, 아직 끝나지 않은 발화는 입력 중으로 표시한다.

`노바`만 확정되면 빈 질문을 모델에 보내지 않고 LISTENING으로 기다린다. 다음 새 utterance를 동일 activation 질문에 연결한다. `노바 오늘 일정 정리해줘`는 호출과 질문을 같은 이벤트에서 처리한다. 화면을 연 뒤 질문을 별도 발화로 한 경우도 동일하게 동작한다.

최대 입력은 초기 8,000 UTF-16 code unit / 60초를 안전 상한으로 두고 기존 event의 8,192 UTF-16 code unit 제한보다 느슨하게 풀지 않는다. 출력과 substring 처리는 code point 경계를 보존한다. 상한을 넘으면 초과 부분을 몰래 자르거나 미확정 텍스트를 자동 전송하지 않는다. ‘질문을 나눠 말씀해 주세요’와 수동 확인 UI를 제공한다. 마지막 확정 데이터가 없는 상태에서 전사가 끊기면 실패 상태로 남기고 재연결 시 과거 버퍼를 전송하지 않는다.

### 5.4 답변 중 다음 발화

주변의 새 소리가 현재 Focus 요청을 취소하면 안 된다. 기본 barge-in 자동 취소는 넣지 않는다. 하나의 활성 요청과 하나의 제한된 `nextDraft`만 허용한다. 답변 생성 중 새 확정 발화는 nextDraft에 모으고 ‘다음 질문 대기’를 표시하되 즉시 병렬 호출·영구 user 메시지 추가는 하지 않는다.

이전 답변의 저장·표시가 끝나고 최소 1.5초의 표시 기회를 준 뒤, nextDraft가 final/quiet 조건을 충족하면 다음 turn을 접수한다. 이를 통해 user1/user2/assistant1 같은 순서 꼬임 없이 user1/assistant1/user2/assistant2로 저장한다. 대기 공간을 초과하면 그 사실을 보여주고 사용자의 입력을 조용히 버리지 않는다. nextDraft가 있으면 무발화 자동 닫기는 시작하지 않는다.

## 6. 집중 대화 실행과 기존 ChatWorkflow 문맥 연결

새 `NovaFocusAnswerService`는 기존 `ChatService`/`ChatWorkflow`와 검색/RAG 정책을 재사용한다. `ConversateApiCueService`의 ‘도움말을 띄울지’ 판정 또는 `NO_CUE`로 명시 질문을 무시하는 경로는 거치지 않는다. `maxTokens=192`의 공개 힌트 단답 규격이나 cue 15초 수명을 Focus의 답변 규격으로 고정하지 않는다. 렌즈용 페이지 표시와 답변의 실제 내용 길이는 분리한다.

### 6.1 소유권·예산이 포함된 내부 호출 계약

ASR background thread에서 `ClientOwnerKeyResolver.ownerKey()`나 HTTP request 객체를 다시 읽지 않는다. 인증된 연결/명시 제어 시점에 확정한 immutable owner와 producer 범위를 실행 문맥으로 전달하고, executor 안에서는 필요한 권한 컨텍스트를 scope 형태로 설치한 뒤 finally에서 정리한다. `system:no-request` 등 공용 fallback owner를 새 방의 주인으로 쓰지 않는다.

이 ZIP의 `ChatSessionAccessGuard`는 package-private이고 일반 HTTP 입구용이다. 접근 불가능한 클래스를 다른 package에서 바로 호출하는 코드를 만들지 않는다. 필요한 소유권 검사 책임을 public 공유 서비스로 추출하거나 Focus 서비스 내부에서 기존 owner 규칙에 맞춰 검사한다. admin-owned와 guest owner-key-owned 방을 구별한다.

기존 `/phone-test`/public display 경로는 사설 vector corpus를 허용하지 않는 별도 경계가 있다. Focus 추가를 이유로 익명 경로의 private RAG 권한을 넓히지 않는다. 같은 guest owner의 명시적 Focus 기록은 사용할 수 있지만 별도 보호 자료는 기존 승인 범위가 확인된 경우만 쓴다. 확인 불가 시 private RAG는 거부하고 공개 검색/승인된 사용자 배경만 사용한다.

일반 `ChatGenerationAdmissionFilter`는 지정된 `/api/chat*` HTTP 경로만 처리하므로 서비스 직접 호출이 그 멱등·rate limit을 통과한다고 가정하지 않는다. Focus admission은 기존 Assist의 승인된 비용 검사와 bounded worker 규칙을 이어받고, provider의 무료/유료·쿼터·동시성 정책을 우회하지 않는다. 라우팅 모델명·API 키·기존 과금 정책을 이 작업에서 임의 교체하지 않는다.

### 6.2 모델 입력은 영구 history와 별도로 명시 구성

기본 입력은 현재 질문 + 최근 완료된 2개 Q/A 쌍 + 누적 핵심 요약 + 현재 질문 관련 과거 기록이다. 최근 2턴은 메시지 두 개가 아니라 **user/assistant 두 쌍**이다. `user`와 `assistant` 역할을 보존한다. 아직 답변 중/실패/취소된 턴은 완료 history로 취급하지 않는다.

컨텍스트 예산은 실제 라우팅 모델의 tokenizer/context cap을 기준으로 정한다. 초기 Focus history 상한은 3,000 tokens, 그 안에서 요약 600 / 과거 관련 기록 800 tokens 상한을 제안한다. 이는 full prompt 상한이 아니다. 시스템 지시·현재 질문·RAG·출력 예약까지 합산하여 모델 context cap 이내인지 마지막 조립 단계에서 확인한다. tokenizer가 없으면 보수적 추정임을 기록하고 한국어에 영문 4chars/token 규칙을 사실처럼 쓰지 않는다. 초과 시 오래된 검색 기억부터 제거하고 현재 질문은 몰래 자르지 않는다.

원본 ChatMessage는 메모리 창에서 빠져도 삭제하지 않는다. 누적 요약은 일정량의 새 완료 turn이 생겼을 때만 갱신하고 summary version/last summarized turn watermark를 저장한다. 한 번 폴링할 때마다 요약 모델을 호출하지 않는다. 기존 `ChatHistoryServiceImpl`의 rolling summary와 bounded 조회를 재사용할 수 있지만, Focus 저장 경로에서 자동 갱신되는지 별도로 검증한다. 요약 실패는 다음 질문 자체를 막지 않으며 최근 완료 turn만으로 계속할 수 있다.

### 6.3 중요: DTO.history만 채우지 말 것

E09~E10 기준으로 `ChatRequestDto.history`만 채우고 `ChatService.continueChat()`를 부르면 문맥 전달이 입증되지 않는다. 또한 `externalCtxProvider`는 `ChatWorkflow`의 웹 검색 허용/예산 조건 안에서 읽히는 경로이므로, 웹 OFF인 기억 회상에 의존할 수 없다.

권장 최소 변경은 **서버 전용 `ChatExecutionContext`와 `BoundedConversationContext`**를 만들고 `ChatService`/`ChatWorkflow`에 추가 overload를 제공하는 것이다. 기존 호출은 empty context로 위임하여 결과를 바꾸지 않는다. 예시 타입/메서드 계약:

```java
// 신규 내부 타입. HTTP JSON에서 역직렬화하여 권한을 부여하지 않는다.
// 승인된 owner 범위, 취소/예산, 역할을 보존한 bounded 과거 turn을 포함한다.
ChatResult continueChat(ChatRequestDto request, ChatExecutionContext execution);
```

`ChatExecutionContext`는 설정/권한이 검증된 Focus 서비스만 구성한다. public 요청의 문자열 `mode="nova"`나 `roleScope`만으로 이를 획득할 수 없게 한다. 캐시가 사용된다면 이 overload는 먼저 캐시 제외하고, 나중에 owner와 bounded context를 안전하게 키에 포함한 뒤에만 허용한다.

`ChatWorkflow`에서 이 context를 **대명사/후속 질문 해석과 검색 query planning 이전**에 사용할 수 있게 연결한다. 최종 `msgs` 조립에서도 현재 user 직전에 검증된 과거 user/assistant를 한 번만 넣는다. 요약·검색된 과거 발언은 인용된 비신뢰 데이터로 넣으며 새 system 명령/도구 실행 지시로 승격하지 않는다. HTTP 입력 history에 임의 system/tool 역할을 허용하는 광역 패치를 만들지 않는다.

Focus의 영구 기록은 별도 history 서비스가 책임지고, 모델 실행은 필요에 따라 `memoryMode="ephemeral"`, `sessionId=null`로 수행할 수 있다. 이 경우 실행 문맥으로 bounded history를 주므로 ‘ephemeral이라 기억하지 못함’과 ‘저장하지 않음’을 혼동하지 않는다. 단, 전체 코드에서 이 호출에 부수적인 일반 chat 저장이 생기지 않는지 검증한다. 영구 sessionId를 무턱대고 넘겨 default history/메모리를 중복 로드하는 방식은 피한다.

웹 검색은 실제 `SearchDecisionService`/`SearchMode.AUTO`와 현재 RAG 정책을 연결한다. `useWebSearch=true`를 모든 질문에 강제하거나 단순 키워드 목록으로 모든 필요성을 단정하지 않는다. ‘아까 정한 이름은?’처럼 Focus 기록만으로 충분한 질문은 웹을 쓰지 않아도 답할 수 있어야 하고, ‘공식 자료를 찾아 확인해줘’는 허용된 검색 경로로 이어져야 한다. 필요한 private corpus의 권한이 없으면 그 사실을 응답한다.

실제 provider 호출 인자를 캡처하는 fake 모델 테스트로 최근2쌍, 요약, 질문, 역할, token 상한, current question 한 번, web OFF 후속 회상을 확인한다. DTO builder mock 검사만으로 통과시키지 않는다. 검색 중/생성 중 UI는 실제 stage event가 있을 때만 상세 표시하며 단계 신호가 없으면 정직하게 ‘응답 준비 중’으로 표시한다.

## 7. 영구 방·기록·멱등

기존 `ChatSession`, `ChatMessage`를 대화 저장의 기준으로 재사용한다. 새로운 사용자용 여러 채팅방 UI나 별도 LLM 채팅 엔진은 만들지 않는다. 신규 저장 모델은 **Focus owner 설정/방 연결**과 **Focus turn 처리 상태**에 한정한다.

권장 신규 타입: `NovaFocusProfile`, `NovaFocusTurn` + repositories. 구현 package는 기존 entity/repository 관례에 맞추되 source-set을 확인한다.

- `NovaFocusProfile`: 검증된 owner scope + environment/test channel + purpose=`NOVA_FOCUS`에 unique. 설정, 설정 version, nullable 기존 ChatSession FK, 요약 watermark를 보관한다. 설정만 ON으로 바꿔도 채팅방은 만들지 않는다. 첫 확정 호출 또는 수동 열기에서 하나를 생성/연결한다.
- `NovaFocusTurn`: UUID turnId, room FK, turn sequence, source/요청 멱등 키, input hash, 상태, user/assistant message FK, 승인·완료 시간. `(room, idempotencyKey)` unique, turn sequence도 방별 unique. 원문을 디버그 로그나 중복 테이블에 불필요하게 반복 저장하지 않는다.

방 생성 경합은 DB unique + 짧은 transaction으로 해결한다. 같은 소유자가 동시에 열어도 연결되는 ChatSession은 하나다. guest owner cookie가 같거나 동일 인증 계정으로 검증되어야 재접속 복구한다. cookie/로컬 데이터까지 지운 익명 사용자를 근거 없이 이전 owner와 동일하다고 추정하지 않는다. 서로 다른 테스트 채널을 live 영구 방에 합치지 않는다.

턴 접수 트랜잭션에서 멱등 claim과 user 메시지를 기록한다. 그 뒤 DB lock을 놓고 모델을 실행한다. 완료 트랜잭션에서 **여전히 유효한 상태인 turn에만** assistant 메시지·결과·COMPLETED를 함께 저장하고, 성공한 뒤 화면에 완료 답변을 publish한다. DB commit 전에 ‘영구 저장 완료’로 표시하지 않는다. 저장 실패 시 상세 이유 코드를 남기고 성공 표시를 하지 않는다.

동일 요청ID·같은 본문 재전송은 기존 상태/결과를 반환한다. 같은 요청ID·다른 본문은 409다. 동일 문장을 나중에 새 turnId로 요청하는 것은 별도 정상 질문이다. revision/polling/네트워크 retry 때문에 user/assistant 행이나 과금 호출이 중복되면 안 된다.

외부 모델 호출의 결과를 알 수 없는 상태에서 프로세스가 죽으면 `OUTCOME_UNKNOWN`으로 복구한다. 자동으로 다시 과금 호출하지 말고 사용자에게 재시도 여부를 표시한다. 정상 HTTP 재시도에서의 중복 억제와 **장애 전 구간의 외부 API exactly-once 보장**을 혼동하지 않는다. Focus close와 completion이 경합하면 DB의 terminal CAS 승자를 기준으로 기록하며, 어떤 결과도 닫힌 activation을 재개하지 않는다.

기존 `awx_chat_requests` SQL은 있지만 일반 HTTP filter에 묶여 있고 local/demo 예외도 있다(E25). 공유 가능한 일반 멱등 helper가 있으면 재사용하되, Scope가 다른 filter를 억지로 통과시키거나 요청 기반 owner를 조작하지 않는다. Focus turn ledger는 영구 대화 순서/메시지 FK 때문에 필요하므로 일반 짧은 TTL request cache와 동일시하지 않는다.

전체 history UI는 cursor 기반으로 기본50개, 최대100개를 조회한다. 프롬프트는 bounded history 조회만 쓴다. JPA lazy `session.getMessages()` 전체 로딩이나 거대 문자열 이어붙이기로 수만 turn을 매 요청 읽지 않는다. 기존 `findNewestWindowBySessionId(..., Pageable)` 등을 활용한다.

Fold IndexedDB는 owner scope로 분리된 메시지 페이지 캐시와 아직 서버 접수를 확인하지 못한 명시 수동 입력의 outbox를 맡긴다. 브라우저 저장소만을 영구 보존의 유일한 원본으로 삼지 않는다. 재연결 시 자동 재생성보다 turn 상태 조회/결과 동기화를 먼저 한다. 기본 이력 보존과 실제 사용자가 명시적으로 기록 삭제하는 기능은 별개다. 이번 자동 닫기·OFF·context reset은 기록 삭제를 수행하지 않는다.

DB 파일이 `resources/db/migration`에 있다는 이유만으로 Flyway 자동 적용을 가정하지 않는다. 기존 README와 실제 SQL이 서로 달라 실제 DDL 실행 방식은 실행자 확인 사항이다. 신규 DDL에는 unique/FK/index와 rollback 계획을 명시하고, 승인되지 않은 운영 DB DDL을 실행하지 않는다. 테이블 미준비이면 Nova만 `storage_unavailable`로 fail closed하고 기존 전사는 계속 살아 있어야 한다.

## 8. Fold와 Meta 계약: hint와 별도 focus projection

`ConversateSessionService.Snapshot`, `DisplayConversateController.View`, `LensText`, `DisplayRelay.Event`에 additive `focus`를 넣는다. 기존 필드와 semantics는 유지한다. 구버전 응답에 `focus`가 없으면 구버전 동작으로 처리하며 `contract_error`로 전체 자막을 멈추지 않는다.

다음은 **새 계약 예시**다. 전체 history, owner key, 모델 prompt, 인증 쿠키는 렌즈 snapshot에 넣지 않는다.

```json
{
  "focus": {
    "schemaVersion": 1,
    "serverInstanceId": "opaque-boot-id",
    "activationId": "opaque-activation-id",
    "stateVersion": 12,
    "phase": "WAITING",
    "active": true,
    "turnId": "opaque-turn-id",
    "draftText": "",
    "draftFinal": true,
    "questionText": "방금 설명한 내용을 다시 정리해줘",
    "answerVersion": 1,
    "answerText": "완료된 답변의 표시용 본문",
    "answerTruncated": false,
    "hasMoreOnFold": false,
    "idleRemainingMs": 18000,
    "reason": "answer_ready",
    "renderReceiptTicket": null
  }
}
```

이 표시는 history 저장 본문과 별개다. wire 기본 상한은 draft/question tail 각2,000 code point, answer8,000 code point, 전체 Focus JSON UTF-8 48KiB로 제안한다. 표시 한도 초과 시 word/code-point 경계에서 자르고 `answerTruncated=true`, `hasMoreOnFold=true`와 ‘전문은 폴드 기록에서’를 표시한다. 전체 답변은 DB에 보존한다. 렌즈의 제한된 snapshot을 원문으로 덮어 저장하지 않는다.

현재 `LensText`의 hint는 1,180자, conversation은 기본280자로 제한된다(E12/E20). Focus 답변을 hint 필드에 넣어서 이 제한에 잘리는 구현을 하지 않는다. 검증기·응답 DTO·renderer를 함께 업데이트한다.

`DisplayRelay.publish`의 변경 감지에 focus snapshot/version도 포함한다. caption/hint가 그대로인데 LISTENING→THINKING으로 변한 이벤트가 누락되면 안 된다. `/lens/text`가 실제 주 렌즈 경로이므로 relay만 수정해서 끝내지 않는다. 동일 내용의 poll을 받았다고 stateVersion을 증가시키거나 만료시간을 갱신하지 않는다.

서버 전체 broadcast 비활성, revoked/expired lens grant, producer 교체 시 Focus 데이터도 같은 권한/출력 정책을 따른다. 기존 `/lens/text`와 relay의 broadcast gate가 일치하는지 확인하고, 적어도 신규 Focus는 OFF된 출력으로 유출되지 않게 한다. producer 교체 시 새 generation으로 이전 projection을 clear한다. 고장난 포커스 field는 제한된 오류와 Focus-only fallback으로 처리하고 기존 caption은 가능한 한 유지한다.

### 8.1 제안 API

권한·캐시·body 크기 규칙은 기존 Display controller와 일관되게 구현한다. 아래 경로는 신규 제안이다.

| 경로 | 기능 / 권한 |
|---|---|
| `GET /api/assist/display/focus/settings` | 검증된 owner의 설정 읽기. 방 생성/LLM 호출 없음. |
| `POST /api/assist/display/focus/settings` | 기존 binding + producer + epoch + 설정버전 검증 후 수정. read token으로 불가. |
| `POST /api/assist/display/focus/control` | `open`, `close`, `cancel_turn`만 허용. 요청ID 및 active generation 검증. unknown action은400. |
| `POST /api/assist/display/focus/input` | 수동 텍스트 질문/수정된 draft 명시 전송. 동일 turn 멱등. 기존 voice 금지 canSubmit를 해제하지 않고 독립된 명시 경로로 처리. |
| `GET /api/assist/display/focus/history` | owner/room 권한 검증 후 cursor 페이지. `mode=older&cursor=messageId` 또는 `mode=newer&cursor=messageId`로 방향 하나만 허용. |
| `POST /api/assist/display/focus/rendered` | 아래의 **receipt 전용 ticket**으로 현재 답변의 최초 DOM render만 기록. 읽기 토큰을 일반 쓰기 권한으로 바꾸지 않음. |

실시간 상태/turn 처리 결과는 기존 status/poll 응답의 Focus 필드로 받는다. 실패 후 재전송 판단에 필요한 특정 turn status는 owner-protected Focus history 응답 또는 한정된 status query로 제공한다. 초당 별도 history 전체 GET을 추가하지 않는다.

서버 internal ASR는 HTTP focus/input을 자기 자신에게 다시 호출하지 않고 인증된 내부 서비스 계약으로 직접 넘긴다. write는 기존 `bound`와 `requireProducer`를 통과하고 `binding.owner`를 기준으로 저장한다. 단, `requireProducer`는 relayChannel이 있는 경우의 검사이므로 신규 write의 적용 모드에 실제로 producer 검증이 작동하는지 테스트한다. caller가 body에 보낸 owner/chatSessionId를 신뢰하지 않는다.

## 9. 타이머와 ‘답변이 보인 뒤 닫기’

서버 monotonic 시간으로 deadline을 관리하고 wire에는 상대 remainingMs를 보낸다. DB에는 별도 wall-clock timestamp를 기록한다. 기기 시계 차이, 백그라운드 timer throttling, 중복 응답 때문에 시간이 늘거나 역전되지 않게 한다. Fake clock으로 테스트한다.

질문 제출 quiet timer, 호출만 하고 기다리는 timer, 모델 request timeout, render 확인 grace, 답변 후 followup timer는 서로 다르다. Focus의 `followupIdleMs`를 기존 힌트 TTL이나 cue cooldown으로 계산하지 않는다. poll receipt, debug events, 같은 글자 재렌더는 실제 발화/사용자 조작이 아니다.

**답변 후 timer 시작 제안:** DB에 완료 답변을 저장하고 projection을 publish한 뒤 `ANSWER_READY`로 전환한다. 실제 renderer는 새 `(activationId,turnId,answerVersion)`를 DOM에 반영하고 visible 문서에서 `requestAnimationFrame` 후 최초 receipt를 보낸다. 이는 ‘브라우저 DOM에 반영됨’ 증거이지 실제 눈에 보였다는 증명이 아니다.

주 대상은 렌즈이며 활성 렌즈 연결이 없으면 Fold renderer를 대상으로 한다. 서버는 답변 publish 시점에 한 번만 암호학적으로 안전한 **receipt-only ticket**을 발급한다. 현재 owner/activation/turn/answerVersion/대상/짧은 만료에 묶고 서버에는 hash를 저장한다. GET마다 ticket을 재발급하지 않는다. ticket은 해당 답변의 첫 render 확인 외에 방 열기·음성 입력·설정 수정·history 조회를 할 권한이 없다. 기존 lens read token 자체를 producer token으로 바꾸지 않는다. ticket은 log/referrer에 남기지 않는다.

첫 유효 receipt에서 WAITING과 followup deadline을 시작한다. 반복 receipt는 시간 연장 불가다. 연결 소실로 receipt가 안 오면 기본3초의 render grace 후 `display_unconfirmed`를 명시하고 제한된 WAITING으로 넘어간다. 무한 대기나 “렌즈 표시 성공” 허위 표시는 금지한다. DOM 업데이트를 받기 전에 renderer가 중단되었으면 성공으로 기록하지 않는다. 실기기 최종 검증은 별도다.

새 질문 draft 또는 nextDraft가 있으면 answer idle timer를 중단한다. followup timeout/수동 close 시 focus만 숨기고 ARMED(호출 ON+수음 중) 또는 OFF로 복귀한다. 현재 PCM transport, voice stream, assist session, 영구 chat room은 그대로 둔다. 수동 close 후 같은 ASR final의 늦은 재전송으로 다시 열리지 않게 source watermark를 유지한다.

클라이언트가 오래 끊긴 경우 stale projection은 bounded transport timeout으로 숨기고 ‘연결 확인 필요’를 표시한다. 오래된 WAITING을 무한히 보여주거나 복귀 시 남은 시간을 다시20초로 만드는 방식은 금지한다. 서버 restart는 serverInstanceId가 달라진 것으로 식별하고 transient activation은 폐기하되 DB 방/완료 기록은 복구한다.

## 10. 프런트엔드 변경

### 10.1 Fold

`resources/static/assets/display/index.html`, `app.js`, `display-conversate.js`, `styles.css`를 수정하고 새 `display-focus.js`로 Focus UI/상태 projection 로직을 분리한다. 기존 JS의 UMD/module test 스타일을 따른다. Next.js나 React로 다시 만들지 않는다.

집중 패널은 같은 document 안 overlay다. 상단에 `노바 · 듣는 중/응답 준비 중/후속 질문 대기`, 현재 질문 draft, 최신 답변, ‘같은 대화의 이전 기록’, 닫기 버튼을 제공한다. 새 창·window.open·전체 location 변경·기존 /chat로 이동을 자동 진입 동작으로 사용하지 않는다. 그렇지 않으면 현재 pagehide에서 수음이 끝날 수 있다(E18).

부분 전사는 같은 노드를 갱신하고 확정된 메시지만 history에 추가한다. 답변은 완결된 단락/페이지 단위로 보여주며 token마다 전체 history DOM을 재작성하지 않는다. 원문을 `innerHTML`로 넣지 않는다. 텍스트는 textContent, markdown을 쓸 경우 기존 검증된 sanitizer를 사용한다.

Escape 처리 우선순위는 `Focus 열림 → Focus만 close → return`, 그 외는 기존 동작이다. global handler로 이벤트가 흘러 stop까지 호출되지 않게 한다. Focus overlay의 focus trap은 textarea/select/composition 이벤트를 방해하지 않는다. 설정 적용이나 history 버튼 사용이 caption/mic 흐름을 리셋하지 않아야 한다.

`display-conversate.js`에 optional focus decoder와 설정/control/input/history/receipt helper를 추가한다. voice 활성 중 기존 `canSubmit` guard를 전체 제거하지 않는다. background ASR focus routing과 명시 Focus API를 별도로 연결한다. 요청4초 timeout 등의 기존 값은 접수/status에만 적용하고 모델 전체 생성 시간과 혼동하지 않는다. write 접수 후 결과는 status로 추적한다.

### 10.2 Meta 렌즈

실제 기본 대상은 `resources/static/assets/display/meta/index.html`과 `meta/receiver.js`다. 현재 index는 receiver.js를 직접 로드한다. 인접한 `meta/boot.js`만 수정하고 적용된 것으로 착각하지 않는다.

같은600×600 stage에서 focused overlay를 표시한다. 상태/짧은 질문/답변 페이지/페이지 번호를 나누고 기존 typography 설정과 measured pagination을 재사용한다. 집중 중 일반 힌트와 rolling 자막이 답변의 앞면을 덮지 않도록 하되 데이터 수신은 계속한다. 종료 시 최신 자막으로 복귀한다. 기본 입력은 Fold 마이크다. 안경 input field를 자동 focus하는 것과 음성 입력 composer/시스템 마이크를 자동 실행하는 것은 같지 않다.

현재 reader의 `valid`/`apply`/`paint` 및 페이지 넘김을 focus-aware로 바꾼다. 키보드/Neural Band 방향 입력은 Focus가 보이면 Focus 페이지를, 그렇지 않으면 기존 힌트 페이지를 넘긴다. 현재는 Arrow/Enter가 hint 페이지로 직접 연결되어 있으므로 반드시 분기한다. 기본 MVP에서 렌즈의 읽기 연결에 전체 Focus close 권한을 부여하지 않는다. 실제 종료는 Fold close와 서버 타이머로 한다. 안경에서 종료 제어까지 추가하려면 별도 최소권한 control 설계를 승인된 범위로 다뤄야 한다.

큰 폰 설정 UI를600px로 줄이는 방식이 아니라 기존 렌즈 UI의 좁은 표시 구조를 따른다. 페이지 text wrapping, 긴 한글·영문URL, emoji·surrogate pair, 확대 글꼴에서 overflow와 잘림을 테스트한다. 모든 JS/CSS 참조의 cache-busting version을 같이 갱신한다. CSP에서 새 스크립트 로드를 허용하는지 확인하되 보안을 전역 완화하지 않는다.

## 11. 수정·신규 파일 묶음

### 기존 파일 — 직접 변경 대상

| 파일 (ZIP 상대 경로) | 책임 |
|---|---|
| `java/com/example/lms/assist/ConversateSessionService.java` | 이벤트 intake/Focus route, cue 격리, snapshot, timer tick, cleanup. |
| `java/com/example/lms/assist/ConversateQuestionPolicy.java` | source revision/dedup 검증을 의미 기반 힌트 억제와 분리. |
| `java/com/example/lms/assist/DisplayConversateController.java` | owner/producer별 Focus API, View/LensText projection, receipt-only 권한. |
| `java/com/example/lms/assist/DisplayRelay.java` | Focus payload/version 변경 감지, disable/switch/clear. |
| `java/com/example/lms/service/ChatService.java` | 서버 내부 execution context overload와 기존 호출 호환. |
| `java/com/example/lms/service/ChatWorkflow.java` | 필요한 bounded history를 planning과 final provider messages에 실제 반영. 일반 호출은 변화 없음. |
| `resources/static/assets/display/index.html` | 설정 구역 및 Focus overlay DOM. |
| `resources/static/assets/display/app.js` | 설정 동기화, render 통합, Escape 우선순위, mic 유지. |
| `resources/static/assets/display/display-conversate.js` | optional focus 계약 및 API helper. |
| `resources/static/assets/display/styles.css` | Fold overlay와 접근성/레이아웃. |
| `resources/static/assets/display/meta/index.html` |600×600 집중 stage와 asset version. |
| `resources/static/assets/display/meta/receiver.js` | 주 lens/text 경로 계약·Focus 페이지·render receipt. |
| `resources/application-meta-display.yml` | Focus availability/defaults만 추가. 기존 cue/RAG/provider 키는 변경하지 않음. |

`ConversateAsrBridge`, `DeepgramAsrTransport`, Soniox/local adapters, `LensDisplayPrefs`, `ChatHistoryServiceImpl`, repositories, 보안 설정은 우선 참고/검증 대상이다. event metadata나 bounded API가 부족하다는 테스트 근거가 있는 경우만 최소 변경한다. 동명의 legacy/patch 패키지는 canonical 실행 경로 확인 없이 수정하지 않는다.

### 신규 파일 — 기존에 없는 제안

- `java/com/example/lms/assist/NovaFocusService.java`: 상태기·worker 수명주기·기존 Conversate 연결. lock 안에서 DB/모델 호출 금지.
- `java/com/example/lms/assist/NovaFocusSettings.java`: 입력 검증·기본값·명시 patch/version 계약.
- `java/com/example/lms/assist/NovaWakeMatcher.java`: 순수 리터럴/Unicode 매처.
- `java/com/example/lms/assist/NovaFocusTurnAssembler.java`: source identity/revision별 질문 조립과 final/quiet 경계.
- `java/com/example/lms/assist/NovaFocusAnswerService.java`: 기존 ChatService에 승인된 execution context로 연결.
- `java/com/example/lms/assist/NovaFocusHistoryService.java`: 방 get-or-create, turn 멱등·CAS·메시지 transaction·bounded history/cache sync.
- `java/com/example/lms/assist/NovaFocusSnapshot.java`: wire projection·길이 제한·버전.
- `java/com/example/lms/service/ChatExecutionContext.java`, `BoundedConversationContext.java`: 외부 JSON이 직접 생성할 수 없는 내부 승인·문맥 타입.
- `java/com/example/lms/domain/NovaFocusProfile.java`, `NovaFocusTurn.java` 및 대응 repository: 기존 ChatSession/ChatMessage 연결과 unique 제약.
- `resources/static/assets/display/display-focus.js`: Fold의 Focus UI/pagination/history cache 결합.
- 실제 DB 적용 관례에 맞는 명시 DDL, 기존 테스트 source-set의 Focus 테스트, 개인정보 없는 replay fixture와 smoke script.

이 이름은 책임 분리 제안이다. 기존에 동등한 안전한 구현이 실제로 있으면 재사용하고 증거를 보고한다. 클래스 개수를 늘리는 것 자체는 목표가 아니다. assist package-private 기능은 같은 package에서 합법적으로 접근하거나 public bridge를 명시적으로 만들며 reflection으로 우회하지 않는다.

## 12. 진단 지점

기존 bounded diagnostics 구조를 확장한다. 로그 본문에 원음·전체 질문/답변·owner cookie·lens token·receipt ticket·API key를 남기지 않는다. 이벤트와 이유코드, 길이·해시·카운트·상대 시간만 남긴다.

필수 이벤트: `NOVA_ARMED`, `NOVA_WAKE_PREVIEW`, `NOVA_WAKE_CONFIRMED`, `NOVA_WAKE_RETRACTED`, `NOVA_DRAFT_UPDATED`, `NOVA_TURN_ACCEPTED`, `NOVA_DUPLICATE_SUPPRESSED`, `NOVA_CONTEXT_SELECTED`, `NOVA_SEARCH_STARTED`, `NOVA_ANSWER_COMPLETED`, `NOVA_HISTORY_COMMITTED`, `NOVA_LENS_RENDER_ACK`, `NOVA_DISPLAY_UNCONFIRMED`, `NOVA_IDLE_CLOSED`, `NOVA_CANCELLED`, `NOVA_RESUMED_CONVERSATE`.

최소 측정: wake-confirmed→Fold/렌즈 DOM 표시, final→accepted, accepted→answer-ready, ready→최초 렌더 receipt, 실제 followup 대기, history turn/token 수, 사용한 검색 횟수, 중복 억제, 오탐 회수, expired/stale 응답 폐기 수. P50/P95는 실제 수집 표본으로만 산출하며 목표시간을 측정값처럼 쓰지 않는다.

`server_completed`, `projection_published`, `client_dom_rendered`, `hardware_visibility`를 서로 다른 항목으로 둔다. 마지막은 수동 기기 확인 전 `not_observed`다. `CONNECTED`, HTTP200, receipt 하나만으로 실제 렌즈 사용 성공이라고 보고하지 않는다.

## 13. 필수 테스트 매트릭스

기존 Java/JUnit 및 JS 테스트 체계를 먼저 확인하고 그 안에 추가한다. ZIP에는 전체 runner가 없으므로 임의의 Gradle task 이름을 사실처럼 단정하지 않는다. 단위 테스트는 fake clock·fake ASR·fake model·transaction DB fixture로, 프런트는 reducer/DOM/네트워크 fixture로 재현한다.

| 번호 | 시나리오 | 통과 기준 |
|---|---|---|
| T01 | Nova OFF에서 ‘노바’ 여러 번 | Focus UI/추가 DB 방/추가 LLM 호출0. 기존 Conversate 결과 동일. |
| T02 | Nova ON, 수음 중지 | mic 새 시작0, ‘전사 중지’ 표시, 가짜 ARMED 금지. |
| T03 | hints OFF + Nova ON | 자막 유지, Nova 명시 질문 응답/렌즈 표시 가능. |
| T04 | 슈퍼노바/노바크/노바카인/노 바 | 기본 호출0. |
| T05 | 노바 → 노바카인으로 interim 수정 | preview 철회, LLM·방 생성0. |
| T06 | 노바만 확정 후 다음 utterance 질문 | 빈 제출0, 같은 activation에 한 질문. |
| T07 | 노바, 질문을 같은 utterance로 발화 | 호출어 한 번 제거, 질문 suffix 완전 보존. |
| T08 | revised interim 반복/배열 재전송 | draft 교체, 중복 메시지0. |
| T09 | final 뒤 old revision/interim | 거부, 화면·질문 후퇴0. |
| T10 | 여러 provider 확정 구간 + 긴 질문 | 누락/중복 없이 질문 한 번; quiet 전에 조기 전송0. |
| T11 | 미확정 interim만 남고 quiet 경과 | 강제 final 승격0. |
| T12 | 동일 문장을 다른 turn으로 다시 질문 | 새로운 정상 요청. |
| T13 | 같은 requestId HTTP retry/다른 본문 | 같은 결과/상태 재사용; 다른 본문409. |
| T14 | Focus THINKING 중 새 전사 | 현재 요청 취소0, nextDraft 제한·표시, 병렬 모델 중복0. |
| T15 | Focus 활성 중 cue force deadline | 자동 힌트가 Focus 답변 덮기/취소0. |
| T16 | hints ON/OFF 조작 중 Focus | Focus 작업 수명주기와 저장 영향0. |
| T17 | 기존 cue context_reset | Nova 영구 기록 삭제/방 교체0. |
| T18 | 기존 QUIET/TTL 설정 변경 | Nova submit/idle 설정은 변경되지 않음. |
| T19 | 검색/생성 중 followup 시간 경과 | 자동 닫기0; 별도 request timeout만 작동. |
| T20 | 최초 render receipt/중복 receipt | 첫 확인에서만 idle 시작, 중복 연장0. |
| T21 | 렌즈 receipt 유실/숨겨진 document | grace 후 표시 미확인, 성공 허위표시/무한 대기0. |
| T22 | followup timer 종료 | Focus만 닫힘, 기존 mic/PCM/전사 유지, 새 자막 도착. |
| T23 | Focus 열린 상태 Escape | mic stop 호출0. |
| T24 | 자동 닫기 후 stale wake/final 재전송 | 다시 열림0; 진짜 새 호출은 정상 열림. |
| T25 | 수동 close/OFF와 LLM 완료 경합 | terminal CAS/activation fence, 늦은 응답 재개0. |
| T26 | producer 교체/owner 불일치 | 이전 projection·작업 무효화, 다른 owner 기록 유출0. |
| T27 | 렌즈 read token으로 settings/input/history 요청 |403 또는 기존 불가 응답. 읽기 권한의 쓰기 승격0. |
| T28 | receipt ticket replay/다른 turn/임의 action | 최초 현재답변 receipt 외의 권한0. |
| T29 | broadcast OFF | Focus도 렌즈로 노출0; Fold 기능 정책 유지. |
| T30 | /lens/text polling만 반복 | LLM/room 생성/타이머 슬라이드0. |
| T31 | relay만/주 lens reader 각각 | focus-only 상태 변경이 둘 다 전달됨. |
| T32 | focus field 없는 구버전 응답 | 기존 caption/hint 정상. |
| T33 | 구버전 렌즈 asset와 새 서버 | additive compatibility; 명시 asset version으로 최신 경로 확인. |
| T34 | 같은 owner 동시 첫 open | 영구 ChatSession 하나. |
| T35 | 같은 owner 재접속/서버 재시작 | 방과 완료 기록 복구; 미확정 draft 자동 제출0. |
| T36 | anonymous cookie 삭제/다른 사용자 | 자동 소유권 합치기0. |
| T37 | 10,000+ turn 기록 | bounded SQL/query/page, 모델 history token cap 유지. |
| T38 | provider 최종 messages 캡처 | 최근 완료2쌍 역할 정확, current question 한 번, summary/검색기억 상한. |
| T39 | web OFF, ‘아까 정한 이름은?’ | 실제 context로 회상, externalCtxProvider 미호출만으로 기억 소실하지 않음. |
| T40 | 공개 display에서 private RAG 시도 | 승인 없는 corpus 접근0. |
| T41 | 저장 실패/DB table 미준비 | Nova만 fail closed; 전사 유지, 영구 저장 성공 허위표시0. |
| T42 | 모델 호출 후 crash/outcome unknown | 자동 재과금0, 상태 조회/명시 재시도 안내. |
| T43 | settings stale localStorage와 두 producer | 서버 version 기준, 조용한 덮어쓰기0. |
| T44 | 길고 악의적인 텍스트/emoji/한글 | DOM XSS0, code point 잘림 방지, wire 한도와 전문 보존. |
| T45 | 렌즈 Focus 페이지 넘김 | Focus에 방향키 적용, 종료 후 힌트 페이지 제어 복귀. |
| T46 | capture renew/local→cloud provider 변경 | chatSessionId 유지, 새 source identity, 중복 wake/턴0. |
| T47 | nextDraft 대기 중 첫 답변 완료 | 최소 표시 기회, turn 순서 정상, idle 조기 닫기0. |
| T48 | 전체 기능 OFF 회귀 | 기존 전사·자동 힌트·렌즈·일반 chat API 테스트 그대로 통과. |

고장 지점별 테스트 예시 이름: `NovaWakeMatcherTest`, `NovaFocusTurnAssemblerTest`, `NovaFocusLifecycleTest`, `NovaFocusHistoryConcurrencyTest`, `NovaFocusPromptIntegrationTest`, `NovaFocusDisplayContractTest`, `display-focus.test.js`, `meta-focus-reader.test.js`. 이름은 신규 제안이며 기존 파일이 있다는 뜻이 아니다.

## 14. 구현 순서와 작업자 분담

**단계 A — 경계·설정·순수 상태기.** source-set과 기존 root 규칙, 실제 DB 적용 방식, profile/route를 확인한다. OFF baseline을 확보하고 매처/조립기/fake clock 테스트부터 작성한다. 불통과 증거를 본 뒤 상태기를 구현한다. 아직 실제 모델/API 비용은 사용하지 않는다.

**단계 B — ASR intake와 cue 격리.** `submit`, 누적/강제 힌트, hints toggle, epoch/source 갱신을 연결한다. 텍스트 fixture만으로 ON/OFF/hints OFF/중복/종료 뒤 전사 유지가 통과해야 다음으로 간다.

**단계 C — 영구 방과 모델 문맥.** DB unique/CAS/rollback·재접속·owner scope를 구현한다. 서버 내부 execution context를 ChatWorkflow의 planning 및 provider messages에 연결한다. fake 모델로 실제 문맥 수신을 먼저 입증하고 실제 모델은 다음 단계에서 쓴다.

**단계 D — Fold/Meta wire와 UI.** Focus snapshot/API 계약을 먼저 고정하고 Fold/Meta renderer를 각각 연결한다. lens/text 주 경로, relay version, Escape, hints OFF, 새 설정, receipt timer, cache version을 함께 테스트한다. 설정 버튼만 달고 완료 처리하지 않는다.

**단계 E — 진단·실제 요청·실기기.** 권한 있는 테스트 계정/기존 설정으로 실제 질문 하나부터 수행한다. 수음 PCM이 계속 전송되는지, 후속 질문이 이어지는지, 실제 렌즈의 글자가 읽히는지 확인한다. 장치 미접속/권한 미승인은 해당 검증을 미실행으로 보고한다. 임의로 보안 게이트를 끄지 않는다.

**단계 F — 결과와 인계.** 변경 파일/해시, DB 적용 상태, 실행 명령·종료코드, 테스트별 결과, 실제 입력→렌즈 확인 기록, 미검증 항목, 되돌리기 방법을 제출한다. 기존 source를 통째로 덮어쓰거나 git rollback으로 다른 작업까지 되돌리지 않는다.

병렬 작업은 단순히 모델 수를 늘리기보다 소유 파일을 분리한다. ASR/FSM 담당, history/ChatWorkflow 담당, Fold UI 담당, Meta reader 담당, 테스트·계약 검토 담당으로 나눈다. 공유 DTO/controller를 여러 작업자가 동시에 수정하지 않도록 한 명을 integration owner로 지정한다. 각 후보는 preimage hash/적용 대상과 검증 로그를 포함하며 조합 전 contract mismatch를 확인한다.

## 15. 실제 기기 인수 절차

1. Fold의 기존 전사를 켜고 Nova OFF에서 같은 발화를 시험한다. 기존 자막/힌트만 나와야 한다.
2. 설정에서 Nova ON, 자동 힌트 OFF로 바꾼다. `노바, 지금 내가 말한 내용을 정리해줘`라고 말한다.
3. Fold와 실제 Meta 렌즈 양쪽에서 집중 표시와 수정되는 질문이 보이는지 확인한다. 서버 연결 성공만 체크하지 않는다.
4. 질문이 끝나면 한 번만 제출되는지 확인한다. 답변 준비 동안 창이 닫히지 않아야 한다.
5. 답변을 본 뒤 호출어 없이 `좀 더 쉽게 설명해줘`라고 말한다. 이전 질문·답변 맥락으로 이어지는지 확인한다.
6. 대기 시간 동안 말하지 않는다. Focus만 닫히고 일반 자막이 이후 발화를 계속 표시하는지 확인한다.
7. 다시 `노바`라고 말해 동일 방의 기록을 연다. 새 방이 생기지 않았는지 DB/응답으로 확인한다.
8. 수동 close, 설정 OFF, Escape, 네트워크 단절/복구, producer 교체, provider renew를 차례로 시험한다.
9. 렌즈 페이지 넘김, 긴 답변/큰 글꼴, 중간 callback 지연, old asset을 검증한다. 민감정보가 없는 화면 기록과 타임라인만 남긴다.

이 사용 흐름은 녹음/전사에 참여하는 사람의 동의와 사용 장소의 규칙을 확인한 상태에서 검증한다. 서비스의 ON 표시와 실제 마이크 상태를 숨기지 않는다.

## 16. 완료 판정

완료라고 보고하려면 아래 조건을 모두 충족하거나 미실행 사유를 분리해 써라.

- ON/OFF와 설정이 서버 기준으로 보존되고 기존 자동 힌트와 독립적이다.
- 호출어가 새 실제 전사 이벤트에서 판정되며 중복/철회/부분 확정 오류가 없다.
- 기존 전사 스트림을 끊지 않고 Focus 진입·후속 대화·자동 복귀가 이어진다.
- 같은 owner의 방 하나에 완료 history가 축적되며 토큰 상한 안에서 실제 모델 입력으로 이어진다.
- Focus가 `/lens/text`와 실제 렌즈 renderer까지 연결되고 hints OFF에서도 보인다.
- 소유권·reader 권한·비용·멱등·보안 경계를 유지하며 구버전/기능 OFF 회귀가 없다.
- Java/JS 테스트와 배포본 asset 확인, 실제 Fold/Meta 검증 결과가 각각 기록된다.

‘UI 추가 완료’, ‘HTTP200’, ‘CONNECTED’, ‘DTO에 history가 있음’, ‘모델이 기억하는 것처럼 대답함’만으로는 전체 기능 완료가 아니다.

## 17. 외부 공식 문서 근거 (소스 사실과 구분)

아래는 2026-09-23 확인한 공개 문서다. 실제 프로젝트 구현 사실은 위 E번호의 ZIP 소스 근거를 우선한다. 이 문서들은 제안의 플랫폼 제약과 설계 근거를 보완하며, 사용자의 기기 펌웨어/배포본에서 동작을 보장하지 않는다.

**Meta 공식 FAQ — Web Apps:** HTML/CSS/JS 기반 렌즈 앱 및 Neural Band 입력, 브라우저 개발과 실기기 검증의 구분.
`https://developers.meta.com/wearables/faq/`

**Meta 공식 Web App Toolkit:**600×600 viewport, D-pad navigation 및 실제 웹앱 개발 도구. 새 프레임워크로의 전면 재작성을 요구하는 근거가 아니다.
`https://github.com/facebook/meta-wearables-webapp`

**Deepgram Endpointing / Interim Results:** 확정 구간과 발화 종료를 구분하고 구간을 합쳐야 하는 이유. 기존 프로젝트 어댑터의 구현도 함께 확인해야 한다.
`https://developers.deepgram.com/docs/understand-endpointing-interim-results`

**LangChain4j Chat Memory:** UI history와 모델 memory는 다르며, memory window/ChatMemoryStore eviction을 영구 대화 원본의 보존으로 착각하면 안 된다.
`https://docs.langchain4j.dev/tutorials/chat-memory/`

**Chrome Page Lifecycle API:** 숨겨진 모바일 페이지의 freeze/discard 가능성. 현재 열어 둔 전사가 살아 있는 동안의 기능으로 범위를 정한다.
`https://developer.chrome.com/docs/web-platform/page-lifecycle-api`

**MDN Storage quotas and eviction:** 브라우저 저장소 소실 가능성 때문에 IndexedDB를 유일한 영구 보관소로 삼지 않는다.
`https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria`

---
핵심 목표: **이미 켜둔 전사에서 ‘노바’ → 같은 문서의 집중 UI → 명시 질문 한 번 처리 → 같은 방에 축적 → 렌즈에 표시 → 설정 시간 뒤 화면만 닫기.** 새 음성 인식기를 만들거나 기존 Conversate를 끄는 기능이 아니다.
