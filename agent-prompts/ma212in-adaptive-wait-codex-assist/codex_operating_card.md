# ma212in 적응형 대기 — Codex 작업 카드

기준: 라이브 체크아웃 `C:\AbandonWare\demo-1\demo-1\src`. 이 카드는 애플리케이션 소스를 고치지 않는다.
Grok이 읽기 전용 프로브와 판단 순서를 남긴다. Codex가 `main/java`와 `main/resources`를 고친다.

코덱스에 붙일 한 줄:

> `agent-prompts/ma212in-adaptive-wait-codex-assist/codex_operating_card.md`를 읽고, 패치 전에 `python -B agent-prompts/ma212in-adaptive-wait-codex-assist/probe_adaptive_wait.py`를 실행하라. JSON의 `leaseBlocks` 경로는 수정하지 마라. `status=open`만 아래 순서로 최소 수정하라. `preserve`와 `later`는 다시 열지 마라. `gradleProof=not-run`은 테스트 통과가 아니다. ZIP·지시서의 줄 번호보다 프로브의 `line`이 우선이다.

## 프로브

프로젝트 루트에서 실행한다.

```powershell
python -B agent-prompts/ma212in-adaptive-wait-codex-assist/probe_adaptive_wait.py
python -B agent-prompts/ma212in-adaptive-wait-codex-assist/probe_adaptive_wait.py --fail-on-open
```

stdout JSON 하나다. 스키마는 `awx.ma212in-adaptive-wait-probe.v1`.
각 open 항목을 고친 뒤 같은 프로브를 다시 실행한다. 프로브가 닫혀도 Gradle은 따로 실행한다.
`--fail-on-open`이 1로 끝나는 것은 open 항목이 남아 있다는 뜻이다. 프로브 스크립트 자체의 실패가 아니다.

## 카드를 만든 직후 한 번 (historical)

2026-09-24 03:15Z 실행이다. 패치 전에는 프로브를 다시 실행한다.

- open은 다섯 개였다. `budget-shorter-than-model-timeout`, `primary-timeout-div3-factory`, `primary-timeout-div3-aspect`, `linkage-error-collapsed`, `compile-failure-marker-missing`.
- 그 순간 `main/java/com/example/lms/service/ChatWorkflow.java`만 active lease `ma212in-catalog-recovery`에 있었다. 예산 항목의 나머지 파일은 그 리스에 없었다.
- 테스트 8개는 `chat-video-focused-tests-devin`이, `scripts/start_rag_stack.ps1`와 `scripts/chat_ui_vibe_listener.ps1`은 `rag-launcher-last-summary`가 잡고 있었다.
- `ChatSessionDetailResponseBuilder.class`는 `build/classes/java/main`에 있었다. 영상 2 당시의 부재와, `var/dev-reload/last-compile-failed.json`이 아직 없는 것은 별개다.
- dev-reload 로그 끝 200KB에는 `compile-failed`가 있었다.
- 저널 `chat-stream-resilience-c77e5dea`가 `ChatApiController.java`, `chat.js`, `scripts/dev_reload_watch.ps1`, `scripts/debug_rag_stack.ps1`를 범위로 적고 있었다. 저널은 리스가 아니다. 쓰기 직전에 그 바이트를 다시 읽는다.

## 참고 문서는 세 갈래다

| 문서 | 역할 | 라이브에서 이렇게 쓴다 |
|---|---|---|
| `agent-prompts/codex_chat_stream_failures_source_directive_20260924.md` | Devin. 영상 1은 요청 예산, 영상 2는 `NoClassDefFoundError`와 compile-failed 로그 | 원인 구분은 유지한다. 줄 번호와 “git 호출 금지”는 이 카드와 프로브가 이긴다. 조건부 로컬 git은 이 카드의 일이 아니다 |
| Downloads `Abandon_Dynamic_Wait_Instructions.md` | 진행 중인 정상 요청은 기다린다 | exact로 이미 시작한 요청에 적용한다 |
| Downloads `ma212in_adaptive_wait_routing.md` | 새 요청만 혼잡을 보고 다른 자원으로 보낸다 | 새 필드가 있을 때만 적용한다. `strictModelSelection:true`를 끄지 않는다 |

첨부 ZIP의 `main/`은 이 저장소의 `main/`이다. `src/main/java`로 복사하지 않는다.
서버 로그가 없다는 문장은 Devin 지시서가 이미 로그 경로를 적은 뒤의 문장이다. 그 로그 파일이 아직 있으면 다시 읽고, 없으면 `not_observed`로 둔다. 영상 2를 모델 혼잡으로 다시 단정하지 않는다.

스터프1의 `scripts/agent_git_vibe_commit.py` 오케스트레이터는 이 카드의 일이 아니다. 프로브 `journalOverlaps` 또는 `leaseBlocks`에 그 경로가 있으면 만들지 않는다.

## 팩이 충돌할 때의 판단

1. 이미 선택된 모델로 돌아가고 있는 요청은 유지한다. 같은 질문을 다른 모델에 다시 보내지 않는다. “계속 기다리기”는 새 POST가 아니다.
2. 새 요청이 선택한 모델의 슬롯을 기다릴 뿐이고, 다른 물리 자원에 이미 올라간 허용 모델이 있을 때만 그 모델로 배정한다. 화면에는 선호 모델과 이번 답변 모델을 따로 보여 준다.
3. 같은 GPU에 아직 안 올라간 다른 대형 모델은 여유 자원이 아니다.
4. 사용자가 고른 모델이 느리다는 이유만으로 바꾸지 않는다. 없거나, 팩토리가 그 모델을 만들 수 없거나, 클래스 로딩이 깨진 경우에만 기존 로컬 fast 경로를 한 번 본다.
5. 공개·익명 요청의 상한은 120초다. `OwnerKeyBootstrapFilter`가 익명 방문자에게 주는 ownerKey는 600초 권한이 아니다. 프론트 `X-Budget-Ms`만 600000으로 올리는 수정은 서버가 거절한다.
6. Exa 플러그인은 에이전트 조사 도구다. 프로브가 `exa-is-not-an-llm-route`를 `preserve`로 주면 채팅 모델로 넣지 않는다. 가격이 낮은 클라우드 후보는 `configs/api-routing.yaml`의 기존 `low_cost`이고, 기존 클라우드 허용이 켜져 있고 로컬 후보가 모두 불가일 때만 쓴다. `paid_quality`는 기존 지출 가드를 넘지 않는다.
7. 공유 failover를 켜면 `DynamicChatModelFactory.localPrimaryTimeout`이 남은 시간을 3으로 나눈다. 이 분기는 exact가 아니고 클라우드 fallback이 켜진 경우에만 탄다. 영상 1을 고친다고 exact를 풀면 첫 모델이 더 빨리 죽는다.

## 수정 순서

프로브를 먼저 실행하고, `leaseBlockedPaths`가 있는 파일은 그 항목에서 뺀다.

1. `compile-failure-marker-missing`이 open이면 `scripts/dev_reload_watch.ps1`가 컴파일 실패 때 `var/dev-reload/last-compile-failed.json`을 남기고, 성공하면 지운다. `scripts/debug_rag_stack.ps1 -Action verify`는 그 파일이 있는 동안 하드 실패다. 18180에서 `.class`를 지워 재현하지 않는다. 재현은 `scripts/agent_port_lease.py`로 빌린 포트에서만 한다. `scripts/start_rag_stack.ps1`와 `scripts/chat_ui_vibe_listener.ps1`가 `leaseBlocks`에 있으면 런처는 수정하지 않는다.
2. `linkage-error-collapsed`가 open이면 스트림 생명주기 캐치에서 `LinkageError`와 `NoClassDefFoundError`를 `stream_failed`와 다른 코드로 보낸다. 프론트 분류와 테스트를 같이 넣는다. 테스트는 새 파일이다. 프로브가 막는 `src/test/js/chat-failure-classification.test.cjs`, `chat-failure-recovery.test.cjs`, `PublicRequestBudgetGuardTest.java`, `PublicRequestBudgetProjectionFocusedTest.java`는 수정하지 않는다.
3. `budget-shorter-than-model-timeout`이 open이면 180초 모델 정책이 120초 요청 예산에 조용히 잘리는 상태를 조기 예산 코드 또는 남은 시간 표시로 드러낸다. `llm.call.timeout.cappedByRequestBudget` 트레이스 키는 유지한다. 공개 상한 키 이름은 유지한다.
4. `primary-timeout-div3-factory`와 `primary-timeout-div3-aspect`가 open이어도, 대체 후보가 없고 같은 자원을 쓰면 3등분을 하지 않는다. 파일이 `leaseBlocks`에 있으면 건너뛰고 프로브에 open으로 남긴다.
5. 혼잡 우회는 새 요청과 새 정책 필드에만 연결한다. 기존 `ChatRunRegistry`, `ModelRuntimeHealthTracker`, `FallbackAwareChatModel`을 재사용한다. 두 번째 라우터를 만들지 않는다. `/api/ps`의 loaded는 바쁨이 아니다.
6. `native-stream-false`는 `later`다. 완성 답을 60자로 나누는 일을 스트리밍 완료로 보고하지 않는다. open 항목이 닫히기 전에는 이 파일을 열지 않는다.
7. `strict-selection-still-sent`와 `cancel-shield-preserved`는 `preserve`다.

## 검증

- `.\gradlew.bat :compileJava :processResources -x test`
- 새로 만든 테스트만. 150초에서 600초 sleep으로 증명하지 않는다. 가짜 시계나 가짜 모델을 쓴다.
- 프로브 `--fail-on-open`은 아직 open이 남아 있으면 실패한다. 그것이 Gradle 실패를 대신하지 않는다.
- 18180 ForceRestart는 다른 세션이 `main/java`를 고치는 동안 하지 않는다. 그 재빌드가 영상 2의 클래스 소실로 이미 이어졌다.
- 라이브 증명은 빌린 포트의 준비 응답과 에러 코드까지다. 오래된 JVM의 HTTP 200은 통과가 아니다.

## 보고

변경 파일, 수정 전후 SHA-256, 실행한 명령과 exit code, 프로브 `openFindingIds`, 아직 `not_observed`인 항목을 나눈다.
다음 네 문장만 증거로 답한다.

- 120초보다 긴 정상 로컬 요청이 공개 상한 안에서 어떻게 끝나는가?
- exact 사용자는 다른 모델로 바뀌지 않는가?
- 영상 2의 `stream_failed`를 클래스패스 손상으로 다시 확인했는가, 로그가 없어 `not_observed`인가?
- 취소 화면 뒤에 워커가 끝났다는 증거는 무엇인가?

`docs/PROJECT_STATUS.md`, `AGENTS.md`, `.agents/skills-intent-index.yaml`은 프로브가 막으면 쓰지 않는다. 막지 않아도 이 슬라이스의 완료 조건이 아니다.
