# 변경 파일과 검증 기록 (nova-focus-web-unknown)

Journal: `nova-focus-web-unknown-b21e5202` · Lease: `nova-focus-web-unknown.lock` · Checkpoint run: `data/agent-handoff/codex-autonomy/nova-focus-web-unknown-b21e5202/checkpoints/001` (pre/post 이미지 보존). 커밋/푸시 없음.

## 소스 변경

| 파일 | 변경 |
| --- | --- |
| `main/java/com/example/lms/assist/UnknownAnswerPolicy.java` | 신규. 모름 신호 분류(`classify`), Jev verdict→모드 환산(`mode`), 선제 웹 확장 게이트(`defaultWebAllowed`), 요청당 한 번의 재시도 판정(`decide`). `00_PRECEDENCE.md`의 표를 그대로 인코딩. |
| `main/java/com/example/lms/assist/NovaFocusAnswerService.java` | `conversate.focus.unknown-web-enabled`(기본 on), `conversate.focus.unknown-web-scoped-enabled`(기본 off) `@Value` 추가. 1차 답변 후 모름 신호 시 같은 60s 한도 안에서 웹 ON으로 최대 한 번 재시도(`focus.unknown.*` 추적키). 진단 allowlist에 `focus.unknown.trigger/mode/webRetry/reason/outcome` 추가. |
| `main/java/com/example/lms/assist/ConversateApiCueService.java` | `var mode=UnknownAnswerPolicy.mode(jev)` 추가. `RAG_CUE` 기본 웹 `retrieve()`를 `defaultWebAllowed(mode,...)`로 게이트(SCOPED_RAG 무플래그 시 `retrievalSkipped`). 기존 보강 블록 뒤에 모름 신호 → 한 번의 `conversate.web.unknownSupplement` retrieve+병합+재생성 블록 추가(`unknownTrigger`/`unknownWebMode`/`unknownWeb`/`supplementStatus=GROUNDED_UNKNOWN_WEB`/`unknownWebOutcome` 진단키). `conversate.cue.unknown-web-enabled`/`conversate.cue.scoped-web-enabled` env 플래그. |

변경하지 않은 것: `ConversateQuestionPolicy`(게이트 NO_CUE 유지), `SearchDecisionService`/`UnifiedRagOrchestrator`/`ConversateSessionService`/`DisplayRelay`/`receiver.js` — 재사용만 하고 수정 없음. `cancelWork(s)`는 세션 힌트 작업만 취소하며 Focus 생성 executor와 분리돼 있음을 확인(변경 불요).

## 테스트 변경

| 파일 | 추가 |
| --- | --- |
| `src/test/java/.../UnknownAnswerPolicyTest.java` | 신규. 우선순위 표 전수(모드×트리거), 분류기 바인딩, Jev→모드 환산, LOW_ASR_CONFIDENCE hook. |
| `.../NovaFocusAnswerServiceTest.java` | 모름→웹 1회 재시도·DTO 플래그 캡처, 공백→1회 재시도 후 `focus_empty_answer`, `unknown-web-enabled=false`, 웹 이미 켜진 요청 재시도 금지, 이미지 요청/`RECENT_ONLY` 절대 금지, `SCOPED_RAG` 플래그 on/off. |
| `.../ConversateApiCueServiceTest.java` | CUE 경로 모름 텍스트→1회 웹 보충+재생성, 플래그 off 시 검색 없음, `RECENT_ONLY` 억제, `SCOPED_RAG` 기본 웹 확장 차단 + 명시 플래그 시 1회 복원, 이미 웹 호출한 요청의 연쇄 검색 금지. |
| `.../DisplayRelayTest.java` | `hint`/`focus` 채널 분리 + 생산자 단절 시 focus만 드롭하는 pin 테스트. |

## 실행한 검증 (synthetic fixtures)

```
gradlew.bat test --tests UnknownAnswerPolicyTest --tests NovaFocusAnswerServiceTest
    --tests ConversateApiCueServiceTest --tests DisplayRelayTest
→ BUILD SUCCESSFUL · 97 tests, 0 failed  (실패 2건은 내 테스트 fixture 오류로 수정 후 통과)

gradlew.bat test --tests NovaFocusServiceTest --tests NovaFocusStateTest
    --tests NovaFocusIntegrationTest --tests ConversateSessionServiceTest
    --tests ConversateQuestionPolicyTest --tests NovaFocusInputTest
→ BUILD SUCCESSFUL (OFF 회귀: hints-OFF/Nova-ON 라우팅, 중복·발화 억제, 취소 경로 유지)
```

## 미검증 항목

- 실기기/렌즈(wear): capture 전후 모두 `status=not-running` → 라이브 검증 불가. `patchHint`는 transcript-input=0으로 마이크/ASR 세션 선결 필요 — 이번 변경과 무관한 환경 상태.
- `LOW_ASR_CONFIDENCE` 트리거: `Utterance.confidence`가 `answer()` 시그니처까지 내려오지 않아 정책 hook만 존재 → 연결은 향후 작업, 라이브 NOT_RUN.
- Brave/네이버 실검색: 목으로 대체. 네트워크 호출 없음.
