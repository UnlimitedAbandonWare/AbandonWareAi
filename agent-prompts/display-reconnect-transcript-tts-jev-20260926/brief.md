# 데빈 지시서 — Meta Ray-Ban Display 재연결 완화 + 최근 전사 연속 + TTS API + Jev/RAG·ASR 라우팅

## 배경 (유저 피드백)
자전거 타며 확인한 제일 큰 단점: 수음 끊긴 뒤 재커넥트 인식이 어렵다. 연결 지연·SYN이 길어져도 재수립은 쉽게. 그다음: 최근 음성 전사 위주로 대화 이어가기, 챗봇 말(TTS)을 API로 선택, Jev를 RAG에 본격 응용, 음성 전사 시에도 모델 선택/라우팅이 같은 경로로.

## Project Root
`C:\AbandonWare\demo-1\demo-1\src`
최소 diff. 시크릿 출력·push·히스토리 재작성 금지. soft-auto git만. Display는 HTTPS poll (WS/SSE 아님).

## THE ONE (1순위 — 이 패치부터)
끊김 후 같은 `assistId`로 용서형 audio 재수립 (새 세션 시스템 금지).

심 3곳만 동시 조율

### Server `DisplayConversateController.audioStart`
- 현재: `continuation`인데 `firstRecovery`가 아니고 `now - lastStartAt < renewAfterMs - 500`이면 `segment_not_ready` → 계획 renew(~65s, `stream-seconds:75`)에 묶여 드롭 직후 재시작이 막힘.
- 목표: audio가 `WAITING` / `API_PAUSED` / finished 이면 전체 renewAfterMs 대기 없이 continuation 허용. `capture_active`·중복 start 방지는 유지.

### Client `static/assets/display/display-voice.js`
- `recoverable`: 지금은 `stale_epoch`, `assist_not_found`, `asr_disabled`, `paired_phone_required` 등을 fatal → 수동 재인식.
- 목표: drop / WAITING / `segment_not_ready` 는 bounded retry로 renew. 3회 소진→30s cool-down·peak≥128만 재시도하는 경로도 드롭 복구에 맞게 완화(완전 무한 루프 금지).

### Client `static/assets/display/display-conversate.js`
- 404/409·`reconnect()`가 `session=null` → 새 `assistId`로 컨텍스트 증발.
- 목표: voice renew 중 일시적 409에 session null 금지; `audio/start` 응답의 epoch로 갱신해 `assistId`+rolling context 유지.

### 성공 기준 (THE ONE)
- 수음 mid-stream drop 후 수십 초 `segment_not_ready` 없이 재 `audio/start` 성공.
- 같은 assist 세션에서 caption/context가 비지 않음.
- 계획된 segment renew(정상 65s 창)는 깨지지 않음.

## 2순위 — 최근 전사 위주 대화 연속
이미: ASR final → `ConversateSessionService.submit` → `remember` → `ConversateAnswerPipeline.answerLive`가 context 최대 4턴을 `sharedRag.continueChat` history로 전달. `cue.use-past` 등 존재. `nextSegment`는 context를 비우지 않음.

할 일:
- renew/재연결 시 rolling `context`/caption 유지; 비우는 건 명시적 `context_reset` / 토픽 변경만.
- cue past를 최근 N개 final로 시드(기존 노브 재사용).
- 금지: Display 음성에 `/chat`의 `SubjectResolver`/GraphRAG 토픽캐리를 통째로 이식하거나 이중 연속성 라우터 신설. FocusMemory는 별 스토어 — 기본 path로 강제 합치지 말 것.

## 3순위 — Jev를 RAG·ASR 라우팅에 "본격" (두 번째 라우터 금지)
현재:
- Jev Java 클라이언트 없음. `scripts/jev_gateway_smoke.mjs` + brief만, smoke 401. AGENTS: Jev=Gateway 평가/플랜 선택만, chat LLM 대체 금지.
- Cue LLM은 이미 `ConversateCueRoutingPolicy` + `LlmRouterAspect`/`LlmRouterBandit`.
- ASR는 `conversate.asr.provider` + `ConversateSttRouting`/`FailoverAsrTransport` — bandit/Jev 밖.

할 일:
- RAG 쪽: Jev를 plan/admission shadow로 `ConversateAnswerPipeline` / cue 게이트에 얇게 (shadow/off 기본, `demo.jev.*`). 본문 생성 대체 금지.
- ASR 쪽: 제공자 선택을 같은 admission 스타일(CueRoutingPolicy / spend-guard 메타 + `api-routing.yaml` `routes.asr` 인벤토리)로 통일. Jev auth 되면 그 seam에 optional shadow chooser.
- 금지: 두 번째 bandit, ASR용 별도 Jev 스택, 시크릿 로그.

## 4순위 — 챗봇 TTS 음성 API 선택 (별도 thin)
현재 TTS speak/voiceId API 없음. `gemini.gateway.speech`는 STT(WAV→text). Display는 캡션/힌트 텍스트만.

할 일 (THE ONE과 분리 PR 권장):
- thin `POST` speak/audio-reply + voiceId 선택 API.
- Meta 렌즈 TTS는 접근성/Screen Reader와 혼동하지 말 것(기존 메모: 렌즈는 짧은 텍스트).
- 새 STT 벤더와 섞지 말 것.

## 주요 경로 (복붙용)

| 역할 | 경로 |
|---|---|
| Display API | `.../assist/DisplayConversateController.java` |
| Session | `ConversateSessionService.java` |
| ASR | `ConversateAsrBridge`, `ConversateCloudStt`, `ConversateSttRouting` |
| Answer/RAG cue | `ConversateAnswerPipeline`, `ConversateApiCueService`, `ConversateCueRoutingPolicy` |
| Client | `static/assets/display/display-voice.js`, `display-conversate.js` |
| LLM router | `LlmRouterBandit`, `LlmRouterAspect` (ASR 호출 없음) |
| Jev | `scripts/jev_gateway_smoke.mjs`, `AGENTS.md` (코드 미배선) |

## 설정 노브 (이름만)
`conversate.display.audio.enabled`, `conversate.asr.*`, `conversate.asr.cloud.stream-seconds`, `conversate.transcript.rolling-enabled`, `conversate.context-ttl-ms`, `conversate.cue.*`, `demo.jev.*`, Soniox/Deepgram env 이름만.

## 검증
- phone-test/Display: 녹음 중 강제 stop 후 즉시 재시작 → 재수립·같은 assistId·최근 전사 유지.
- cue/hint가 재연결 직후에도 최근 전사 맥락 유지.
- (후속) Jev shadow 로그 admission만 / TTS voiceId 호출.

## 작업 순서
THE ONE 재연결 3심 → 전사 연속 → Jev/ASR admission → TTS API.
