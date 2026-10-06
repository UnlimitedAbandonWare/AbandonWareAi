# 패치 명세서 — demo-1 디스플레이/전사/LLM 스택 (외부 리뷰용)

- 대상 체크아웃: `C:\AbandonWare\demo-1\demo-1\src` (Java 17 / Spring Boot / Gradle)
- 적용 기간: 2026-09-19 ~ 2026-09-20 · 작성 기준: 현재 로컬 소스(sha256 표기)
- 용도: GPT Pro 외부 리뷰용 — **적용된 패치의 계약 명세**. 미적용·검증 필요 항목은 §F에 별도 정리(리뷰어가 "없다"고 보고하지 않도록).
- 검증 기록 원본: `docs/PROJECT_STATUS.md` §4, `data/agent-handoff/codex-autonomy/<taskId>/` (checkpoint cycle, preimage/postimage sha)

---

## A. 안경 디스플레이 설정·힌트 수명 (서버+안경)

### P-A1. 렌즈 표시 수명·페이징 설정화 — taskId `meta-display-hint-display-patch-d015def1`, `meta-display-hint-paging-restore-d42af9de`, `lens-display-timing-controls-c7f2a1d0`, live 검증 `lens-display-timing-live-verify-6cd380b8`

**파일**: `main/java/com/example/lms/assist/LensDisplayPrefs.java`(sha `92380aa8`), `DisplayConversateController.java`(sha `3d9f7158`), `main/resources/static/assets/display/meta/receiver.js`(served `?v=lens-band-14`), `index.html`, `app.js`

**계약**:
- `LensDisplayPrefs` 레코드 13필드: 폰트/줄 수, `transcriptTtlMs`·`hintTtlMs`(각 1,000~100,000ms), `autoPageMs`(0=off 또는 1,000~100,000ms), `hintPageLines`(기본 11), `hintTargetChars`, history 4종, `topicResetEnabled`
- `PATCH /display/api/conversate/lens-settings` — 부분 갱신, 범위 밖 값은 400. `lens/text` 응답의 `display` 객체에 저장값 echo, `conversationExpiresAt`·`hintExpiresAt` 실측 반환
- receiver.js `mountLens`: 전사/힌트 독립 TTL 만료, 만료 재부활(`hint_suppressed`) 억제, 설정된 페이지 간격으로 자동 넘김. 마지막 페이지는 TTL 잔여분으로 간격을 줄이지 않음
- `index.html` `#lens-display` 입력 → `app.js` `readLensPatch` → `client.lensSettings` + `saveSetting('lensDisplay')` → READY 시 `fillLensInputs` 복원

**검증**: JS 114/114, JUnit 104/104, wear 라이브 라운드트립(1s/5s/23s/46s/100s/0·400 거부, expiresAt 실측 989/4986/45988ms 일치). 실기 안경 광학 확인만 사용자 단계.

### P-A2. 힌트 과거-맥락 제한 + "지금부터 새 맥락" — taskId `hint-input-window-patch-3424c7ed`(Grok) + `hint-context-fold-listen-b4d2b15d`(Devin) + 검증 `hint-context-fold-listen-verify-9b78634a`

**파일**: `ConversateSessionService.java`(sha `85f709c0`, sealed postimage 일치), `LensDisplayPrefs.java`, `DisplayConversateController.java`, `ConversateHintInputWindow.java`, `ConversateQuestionPolicy.java`, `ConversateCardPrompt.java`, `ConversateApiCueService.java`, `application-meta-display.yml`, `index.html`/`app.js`, 테스트 `ConversateHintContextTest.java`(sha `9d0d16b3`, utf8-ok)

**계약**:
- 입력창: 힌트 프롬프트에 과거 맥락을 최대 4턴/1,600자, `use-past` 토글로 제한(`ConversateHintInputWindow`); `topicChanged`가 '다른 주제' 문구만 보던 문제 정정(`ConversateQuestionPolicy`)
- Fold 설정 history: `historyEnabled`·`historyWindowMs`(5~300s)·`historyMaxChars`(200~8,192)·`historyMaxTokens`(50~4,096)·`topicResetEnabled` — lensSettings 지속
- **context epoch**: 세션에 `contextEpoch`; `new Work` 3곳(enqueue L229/502/530)이 epoch 스탬프, 단일 dispatch 워커가 L238/244/246/272에서 `work.ctxEpoch()==s.contextEpoch` 게이트 → 리셋 후 생성 중이던 late 응답 전부 폐기
- `POST /display/api/conversate/context-reset` — 캡처·세션 유지한 채 맥락만 리셋; `topicChanged` 자동 리셋(설정 on 시)
- **지연 전사 펜싱**: 리셋 시점에 `contextResetAudioMark`로 ASR 스트림 상한 스냅샷 → 그 이전 오디오의 늦은 전사는 `preResetAudio`에서 드롭, `duplicates++` + `CONTEXT_STALE_AUDIO` 진단; `nextSegment`/`closeCapture`에서 해제
- `transcriptDiagnostics` → `testStatus.transcript`로 노출(history/lastContextSelection/events/contextEpoch)
- UI: `#hint-context`(hc-history/window/chars/tokens/topic) + `hc-reset` "지금부터 새 주제로 시작" 버튼

**검증**: Java 4 suites 62/62 + 신규 시험 11/11(음성대조 포함: preimage에서 실패 확인), JS 101/101, checkpoint cycle-01·02 finish exit=0. **미검증**: wear 런타임 재기동 후 라이브, 실기 안경.

## B. 수음(voice capture) 복원력 — Fold 클라이언트

### P-B1. 연속 수음 모드 복원력 — `display-voice.js`(sha `50a7d9d9`, 사용자 추가 편집 포함)

**계약**:
- `continuous` 모드: ASR API 한도류(`asr_budget_*`/`asr_quota_exceeded`/`asr_rate_limited`/`display_rate_limited`) → `pauseApi`: **수음 유지**, 대기 구간 PCM은 미저장, 상태 메시지만 표기
- 전송 지연 흡수: 전사 대기 큐 32프레임(7.68s) 상한 + 초과 시 `droppedAudioMs` 누적/`audio_waiting` 이벤트; 세그먼트 갱신 대기 `renewalQueue` 128프레임 상한
- `state`: `frames`/`bytes`/`level`/`reconnects`/`segments`/`events`(12개 링버퍼)/`sttPausedReason`/`droppedAudioMs`/`permission`/`inputRate`/`audioContext`/`errorCode` — **사용자 추가(미검증 편집)**: `lastFrameAt`(마지막 PCM 프레임 수신 시각), `lastSendAt`(마지막 `voiceChunk` 전송 시각), `event()`에 `at` 타임스탬프
- `resume()`: 현재 세그먼트가 살아있을 때만 suspended `AudioContext` 재개(closed/사용자 stop이면 false → 자동 재시작 없음)

### P-B2. 페이지 수명주기 복구 — `app.js`(sha `901712eb`, 사용자 편집 포함)

**계약**:
- `visibilitychange`: hidden → 비standalone에서 클라이언트 PAUSE + "화면을 켜 두세요" 상태 표기(수음 명시 stop 없음); visible → `client.start()` + `voice.resume()`
- `pagehide`: `frozenCapture=voice.isActive()` 기록 + `voice.stop()` + `dispose()`; `pageshow` persisted → `client.start()` + 수음이었다면 재시작
- `debug()` 출력 필드: connection/microphone/capture/audioContext/inputLevel/frames/bytes/sttPausedReason/droppedAudioMs/captureEvents/**lastFrameAt·lastSendAt(신규)**/transcript diag/transcription/path/hintsEnabled/processing/relay/reconnects/segments/**lastTranscriptReceivedAt·lastAudioReceivedAt**/displayRuntime/audio/asrUsage/pipeline/lensDisplay/roundTripMs/processingMs/backgroundChars/error

**검증**: `fold6_display_capture_tests.cjs` 27/27, `meta_display_conversate_client_tests.cjs` 18/18 (합성 픽스처). **주의**: return-recovery만 증명 — 다른 탭/앱/화면잠금 중 **연속 수음**은 미검증(Chrome freeze 특성상 불가능할 수 있음, §F-V 참조). `lastFrameAt`/`lastSendAt`는 방금 추가되어 픽스처 미반영.

## C. 힌트 표시 계약·서버 상태 노출

### P-C1. testStatus/진단 필드 — `DisplayConversateController.java` + `display-conversate.js`

- `testStatus` 응답에 `lastAudioReceivedAt`, `lastTranscriptReceivedAt`, `lensDisplay`(저장된 설정 echo), `transcript`(맥락 진단), `asr`, `relay`, `processing`, `displayRuntime`, `audio`, `asrUsage`, `pipeline`, `processingMs` — `app.js debug()`가 전부 노출
- `lens/text` 5키 계약 + `display` echo + `hintId` 계약 유지

## D. LLM 게이트웨이 — `llm-api-e1-e6-patch-575ff9fd`

**파일**: `ConversateApiCueService.java`, `ConversateCueRoutingPolicy.java`, `GeminiGateway.java`, 관련 테스트

- **E1**: 큐(cue) 경로 quota 즉시중단 — `QUOTA_ERROR_CODES = {insufficient_quota, credit_balance_exhausted, spend_limit_exceeded, blocked_api_access}`(L510), `quotaExhausted` 판정이 라우팅 정책에서 큐 중단으로 연결
- **E2**: Gemini 3.8 호환 — `gemini-3.8-` prefix 모델에서 샘플링 필드 생략, `reasoningEffort("low")` 유지, `max_tokens`/`max_completion_tokens` 선택 로직
- **E3**: 주석 정정 / **E4**: 사용 안 하는 코드 삭제 / **E5-E6**: 문서·테스트
- **검증**: 관련 JUnit 통과. **미해결**: 공유 분류기(`LlmGatewayFailureClassifier`)는 여전히 `429+insufficient_quota`만 인식 → §F-A3

## E. 운영·런타임·에이전트 툴링

### P-E1. 디버그 스택 계약 — `debug-gap-g1-d6f310da`
`scripts/debug_rag_stack.ps1` G1-G7: `-Json`에 pipeline/freshness/readiness 키, `-Action start|tail|threads|jfr|restart`, verboseLogging 적용 표기, wear 런타임 보호(타 role 프로세스 종료 금지), tail 패턴 UTF-8 인자, bounded jcmd 수집. 검증: tests 21/21 + 라이브 8경로 e2e.

### P-E2. 시작/종료 수명주기 — `close-bat-runtime-stop-d377f5c8`
`stop_rag_stack.ps1` + `Close-RAG.bat`/`Close-Meta-Display.bat`: `spring-owned.json` 소유권 매니페스트 기반 role-scoped 종료(watcher graceful → CloseMainWindow → bounded force; other-role/shared/unproven 유지; `-DryRun`). **주의**: 창 닫기 자동 종료(`-StopWithParent` job-tie)는 **revert됨** — 현재 없음이 정상 상태. e2e verified.

### P-E3. Devin 오케스트레이션 — `devin-source-orchestrator-1d42a377` + `display-debug-snapshot-f03f26a0`
`scripts/devin_task_orchestrate.py`: `plan --brief-file`(단계별 스킬·도구·skip 결정) + `capture --role wear --invoke`(Debug-Meta-Display 상태를 redacted로 `data/agent-handoff/display-debug/` 보관, `latest.json` `patchHints`) + compare. unittest+self-test 통과.

### P-E4. 작업 장부·리스·가드 (사설 infrastructure)
- `work_journal.py`/`codex_work_checkpoint.py`/`status_doc.py`/`run_verified_command.py` — taskId 장부, preimage/postimage cycle, drift 안전 status 행
- `__patch_drop__/source_edit_session.ps1` target-scope lease + `git-local-first-source-edit-c174990b`(git 없어도 일반 편집 진행, Git writer/MERGE만 hold)
- `agent_preflight.py`/`agent_work_guard.py`(+ps1)/`agent_session_watch.py` — 태스크 진입 프리플라이트, explorer 루프 브레이크(3회 동일 실패 차단), 4-에이전트 세션 워치독. 시리즈 `session-watch-*`·`work-guard-*` 전부 unittest 통과
- `provider-mcp-wiring-0836f75d`: Grok 리뷰 어댑터 1.0.30→1.0.34 repin(바이너리 sha 실측), Kimi 어댑터 스켈레톤 fail-closed 등록, MCP tools 카탈로그 29(+Devin shared-read 22) — kimi 8/8·stdio 47/47·grok 21/21

### P-E5. 정리(cleanup)
`repo-residue-cleanup-8ff47167`(잔재 168항목 삭제 + DevWatch 경로 정정), `cleanup-old-artifacts-apply-9eec93c2`(393.8MiB → `C:\AbandonWare\_rescue\` 보존 이동), 루트 구형 문서 `docs/legacy/` 격리.

## F. 리뷰어 참고 — 의도적 미적용/미검증 (상세: `docs/devin-report-followup-gap-20260920.md`)

| # | 항목 | 상태 |
|---|---|---|
| F-A1 | 생성 주기 `trigger-quiet-ms`/`cooldown-ms`/`force-after-ms`/`forceMinDeltaChars`는 `@Value` 고정 — 규약상 Fold prefs override 의무이나 서버/UI 미구현 | **해결됨(09-23 검증)** — `LensDisplayPrefs` 필드+`ConversateSessionService` per-owner 소비+Fold UI ld-quiet/cooldown/force 저장·복원 전부 라이브 |
| F-A3 | `LlmGatewayFailureClassifier`가 `blocked_api_access` 등을 HTTP 400에서 미인식 — E1은 큐 경로만 커버 | **해결됨(09-23 검증)** — 공유 분류기에 4종 코드 세트 존재 |
| F-A4 | `BRAVE_API_KEY_FREE` dual-key 지시서 미구현(repo에 심볼 0건) | **해결됨(09-23 검증)** — yaml(본체+미러)/별칭/resolver/lane/문서/.env.example 전부 존재 |
| F-A5 | `application-llm.yaml` `responses-only-prefixes`가 Java 기본 5종 대체 → `gpt-5-pro`/`gpt-5.1-codex`/`gpt-5-codex`/`gpt-5.6*` 미커버 | **해결됨(09-23)** — 앞의 3종은 이미 반영. `gpt-5.6`은 **의도적 미포함**: Display cue 라우팅(`gpt-5.6-luna/terra/sol`→chat/completions, `ConversateApiRouteTest` 계약)이 깨지므로 추가 금지. `ModelGuardYamlCompatibilityTest`에 비가드 회귀 단언 추가됨 |
| F-V2 | A2·B·D 패치군의 wear 런타임 재기동 후 라이브 검증 | 검증 필요 |
| F-V5 | 회귀 감사 P1(non-local 503 fail-closed 의도 확인)·P2(`PageContentScraper` null 반환) | 결정 선행 |
| F-음성 | `display-voice.js`/`app.js`의 `lastFrameAt`/`lastSendAt`/`event.at` — 사용자 직접 추가, 픽스처 테스트 미반영 | 검증 필요 |

## G. 리뷰용 실행 경로 참조

- Wear 런타임: `Start-Meta-Display.bat` → `scripts/start_rag_stack.ps1 -MetaDisplay -Wear` (포트 18180/18181/18182, profile `local,meta-display`)
- 진단: `Debug-Meta-Display.bat -Json` / `-Action tail|threads|jfr|restart`
- 테스트: `gradlew test --tests com.example.lms.assist.*` · `node --test scripts/fold6_display_capture_tests.cjs scripts/meta_display_conversate_client_tests.cjs scripts/meta_display_webapp_contract_tests.cjs` · `node --test src/test/js/*.test.cjs`
- 소스 해시 검증: 위 표기 sha256 (문서 작성 시점 로컬 바이트 기준)
