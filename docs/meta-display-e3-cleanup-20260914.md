# Meta Display E3 및 Conversate 입력 계약 점검 — 2026-09-14

> **MODEL LOCK (2026-09-17, noted 2026-09-19):** This dated document may reference retired model tags (`qwen3:8b`, `qwen3:30b`, `qwen3-coder:*`, `gemma3:*`, `qwen2.5:7b-instruct`). Do not execute or wire those tags — live SoT: repo `AGENTS.md` model lock + `configs/api-routing.yaml` + `ollama ls`.

이번 후속 작업은 마이크 시작 경합 1건을 수정했고, 지정된 수용 명령 5개를 순서대로 실행해 모두 종료 코드 0을 확인했다. 추가 세션 회귀 검사도 12개 통과했다. 실제 브라우저 응답 헤더와 세션 저장소를 결합한 E3 증거는 아직 `evidence_needed`다. E0–E2의 현재 기록은 수리·검증했으며 선택기는 다음 단계로 E3를 반환한다.

## 변경한 파일과 동작

- `main/resources/static/conversate/app.js`: 세션 시작 응답을 기다리는 동안 화면이 숨겨지면 기존 마이크 시작 의도를 무효화한다. 다시 화면이 보여도 이전 의도로 권한 요청을 재개하지 않는다. 숨겨진 페이지에서 시작한 요청도 마이크를 획득하지 않는다. 마이크 시작에만 적용하는 세대 번호를 사용해 기존 페이지 초기화 lifecycle을 보존했다.
- `src/test/js/conversate-ui.test.cjs`: 시작 중 숨김, 숨겼다가 복귀, 숨겨진 화면에서 시작, 숨김 후 늦게 도착한 권한 결과의 모든 트랙 해제, `asrAvailable=false`에서 텍스트 입력 유지 등 5개 회귀 검사를 추가했다. 기존 검사 48개 통과 → 새 검사 포함 53개 중 3개 실패 → 패치 후 53개 통과를 확인했다.
- `scripts/conversate_pcm_contract_tests.cjs`: 사용자가 지정한 수용 경로가 없어서 기존 UI 및 PCM 테스트를 함께 실행하는 진입점만 추가했다. 테스트 본문이나 PCM 구현을 복제하지 않았다.
- `src/test/java/com/example/lms/api/ChatApiControllerSyncLifecycleTest.java`: 연속 질의의 응답 `X-Session-Id`, 본문 sessionId, 사용자·답변 저장 호출 대상의 일치를 검사한다. 다른 소유자의 세션은 403이며 모델 호출과 저장을 수행하지 않는다는 반례도 추가했다. 실제 컨트롤러를 실행하고 history/model 경계는 mock을 사용한다. 첫 부정 테스트는 bounded 조회 오버로드의 mock 누락으로 실패했으며, 테스트 fixture를 수정한 뒤 12개 모두 통과했다.

## Display 정리 및 화면 검사

`display-core.js`와 `receiver.js`에는 제거할 임시 console 로깅이 없었다. UUID 보안 맥락, 출처 URL, URL fragment, 렌더링 이후 ACK를 설명하는 주석은 동작 계약으로 유지했다. fallback 식별, 안전·증거 표, 출처 페이지, 멱등성 키, 단일 진행 요청, 80초 서버 예산과 90초 클라이언트 제한을 변경하지 않았다.

실제 600×600 IAB 화면에서 기존 답변·안전 정보·출처 9장을 넘겨 확인했다. 가로·세로 페이지 오버플로우와 주요 조작 요소의 경계 이탈은 모두 0이었다. 답변 카드 경계는 x=24, y=142, right=576, bottom=432.90625였다. 수신기 미연결 화면도 실제 600×600에서 오버플로우가 없었다. 연결된 수신기 카드, ACK, 공식 Simulator, 렌즈 표시 증거로 확장하지 않는다.

현재 제공되는 core/app/receiver/styles 4개 자산은 소스 SHA-256과 일치했다. 답변 카드 관측 때 로드된 app과 현재 app의 차이는 companion redirect 주석·분기 2줄이며 이를 제외한 바이트 해시는 정확히 일치했다. 현재 자산으로 새로고침한 뒤 입력, Escape, 방향키, 새 대화의 focus와 초기 상태를 확인했다. 새로운 추론 요청은 보내지 않았고 임시 viewport를 복원했다.

## 세션 및 Provider 증거

오늘 앞선 실제 HTTP 클라이언트의 2회 실행 기록에서 응답 헤더·본문의 세션 해시 일치, 두 요청 사이의 동일 세션, 서로 다른 요청 키, 자동 재시도 0을 확인했다. HTTP 200과 의미 검사는 각각 2회 통과했고 왕복 시간은 63,125ms와 28,656ms였다. 이 기록은 동일 브라우저의 응답 헤더를 관측한 증거가 아니다.

현재 브라우저 도구에는 응답 헤더/네트워크 캡처 기능이 없으며 기존 페이지도 그 정보를 DOM에 표시하지 않는다. 데모 런타임의 `InterviewDemoFilter`는 `/api/chat/sessions/{id}` 조회를 허용하지 않고 실제 조회는 404였다. 보안 필터, cookie, 자격 증명, DB 설정을 변경하지 않았다. 추가한 컨트롤러 테스트는 저장 호출 경계의 계약을 확인하며 실제 DB 저장 상태를 증명하지 않는다.

11435의 기존 Ollama 로그에서 브라우저 2회와 별도 HTTP 2회 질의 시간 구간을 추출했다. 모델 blob 식별자를 로컬 manifest와 대조해 `qwen3:8b`, `qwen3-embedding:4b`, `gemma4:26b`의 로딩을 식별했다. 해당 구간의 메모리 예측에 따른 모델 퇴거는 4건, 클라이언트 연결 종료에 따른 로딩 중단은 4건이었다. 후자는 같은 시각의 `Load failed` 기록과 짝을 이루므로 별도의 4회 실패로 더하지 않는다. 예측 필요 메모리 16.9 GiB / 가용 VRAM 6.9 GiB 및 5.4 GiB / 2.2 GiB 사례가 있었다. 명시적 OOM과 OS swap 증거는 관측되지 않았다.

이 결과는 모델 교체가 지연에 기여한다는 해석을 뒷받침한다. Ollama는 메모리가 부족하면 새 요청을 대기시키고 기존 모델을 내린 뒤 로드할 수 있다. 다만 이 로그에 브라우저 요청 ID가 없어 개별 요청·보조 모델 역할·최종 생성까지 완전한 계보를 확정할 수 없다. `wireAttemptCoverage=not_observed`를 유지한다. [Ollama 공식 동시 요청 FAQ](https://docs.ollama.com/faq#how-does-ollama-handle-concurrent-requests)

## 수용 명령 실행 결과

아래 5개 명령을 이 순서로 실행했다. Java 명령은 Windows의 `gradlew.bat`, Java 17.0.13, 기존 task 전용 build/cache 경로로 실행했다.

| 순서 | 검사 | 결과 |
|---|---|---|
| 1 | `node scripts/meta_display_webapp_contract_tests.cjs` | 55 통과, 0 실패 |
| 2 | `node scripts/display_receiver_rag_contract_tests.cjs` | 5 통과, 0 실패 |
| 3 | `node scripts/conversate_pcm_contract_tests.cjs` | 53 통과, 0 실패 |
| 4 | `gradlew.bat test --tests com.example.lms.llm.DynamicChatModelFactoryRoutingTest` | BUILD SUCCESSFUL, exit 0 |
| 5 | `gradlew.bat test --tests com.example.lms.service.ChatWorkflowAgentVisibleDebugEvidenceTest` | BUILD SUCCESSFUL, exit 0 |
| 추가 | `ChatApiControllerSyncLifecycleTest` | 12 통과, 실패·오류·skip 0 |

실제 bootstrap은 `asrAvailable=false`, `storage=volatile`였다. 권한 거절, 트랙 종료, 백그라운드 전환, ASR READY 대기, PCM16LE 16k mono 계약은 합성 검사로 확인했으며 실제 마이크나 Deepgram 연결을 실행하지 않았다. 11435 health는 HTTP 200이고 기존 자동 시작 스크립트 SHA-256은 `9ab3aab0f132c179996ed4f0201c5280d20ebcbe881605a3a64a56f543b6e3c4`로 유지됐다. GPU 매핑, 다른 프로세스, 인덱스 잠금은 보존했다.

## 증거와 다음 수용 조건

작업 산출물은 `data/agent-handoff/codex/report/display-ai-audit-01a09daa/phase-e3-cleanup/`에 있다. `acceptance-current.json`, `browser-viewport.json`, `ollama-memory-review.json`, `source.diff`, `postimages.json`, `preflight.json`을 함께 보관했다. 추가 라인에 대한 기존 토큰·Bearer·private-key 패턴 검사는 0건이다. 기존 실패 기록도 보존했다.

남은 E3 수용 조건은 **동일 브라우저의 두 응답 헤더와 같은 소유자 맥락의 세션 저장 결과를 요청 ID에 묶어 관측하는 것**이다. 다음 검증은 네트워크 헤더를 읽을 수 있는 승인된 브라우저 관측 경로에서 두 응답의 세션 해시를 대조하고, 허용된 세션 조회 경로의 저장 결과와 연결하는 것이다. 현재 결과의 `repositoryWideHold=false`이며 로컬 패치·계약 검증 완료와 E3의 미확인 증거를 구분한다. 공식 Simulator 설치·약관 동의는 이번 스프린트의 선행 조건으로 사용하지 않았다.
