# CHAT-WAIT-PROGRESS-UI — 적용 및 보류 기록 (2026-10-06)

상태: **부분 적용, 목표 미완료 / blocked 감사 충족**. 작업 ID `codex-wait-progress-aee89b80`.
원본 첨부 `PASTE_CODEX_CHAT_WAIT_PROGRESS_UI_20261006.md`는 참고 자료로 읽었고, 실제 소스·현재 사용자 지시·lease를 적용 기준으로 사용했다.

## 실제 적용

| 파일 | 변경 | 현재 sha12 |
|---|---|---|
| `main/resources/static/css/chat-style.css` | 기존 pending bubble에 “답변 준비 중”과 작은 장식 spinner, 줄바꿈·경과 시간 스타일, `prefers-reduced-motion` 대응 | `2538171e20f5` |
| `main/resources/templates/chat-ui.html` | 기존 `googleSearchRescueToggle`의 표시와 aria-label을 “검색 보강”으로 변경. unchecked와 RAG checked 유지 | `cfbef47f8446` |
| `scripts/chat_wait_progress_contract_tests.cjs` | CSS/template와 기존 대기 함수의 재현 가능한 회귀 검사 6개. 미적용 JS 관련 3개는 현재 RED | 작업 생성 테스트 |
| `scripts/chat_rag_golden_browser.js` | 기존 SSE metadata parser에서 terminal error의 문자열 data/code 및 plain JSON reasonCode 수집. 기존 allowlist 유지 | `0394cbbe144e` |
| `scripts/chat_stream_reason_capture_tests.cjs` | 서버의 현재 error DTO, 호환 code, JSON 거절 및 본문 비수집 회귀 5개 | `11d47bd44407` |

원본 바이트와 변경 증거는 `data/agent-handoff/codex-autonomy/codex-wait-progress-aee89b80/ui-01/` 및 `test-01/`에 있다. `test-03`의 백업은 테스트 확장 뒤 생성되어 pre-edit 보존 증거가 아니다. 이 제한은 journal에 기록했다. 다른 작업자의 소스는 덮어쓰지 않았다.

## 현재 소스와 원인

기존 `markAssistantClientWait`는 장시간 대기 뒤 평문 placeholder를 표시한다. 기존 token renderer는 reasoning filter의 결과가 실제 본문인지 확인하기 전에 placeholder를 지운다. 이에 따라 즉시 상태·경과 시간과 첫 유효 본문 전환을 같은 bubble에서 관리하는 변경이 필요하다.

`model_wait`는 controller의 `continueChat` 호출 전 상태이므로 최종 모델 dispatch의 증거로 사용하지 않았다. workflow의 web/vector 조회 뒤에도 첨부·attribution·context refine 단계가 남는다. 전체 검색 완료 및 최종 모델 호출 시작의 producer 연결은 아직 적용하지 않았다. 공개 trace, 타이머, `prompt_build`만으로 해당 단계를 꾸며 표시하지 않는다.

## 준비됐지만 적용하지 않은 JS

`data/agent-handoff/codex-autonomy/codex-wait-progress-aee89b80/`:

- `prepared-chat.patch`, `prepared-chat.js`, `prepared-source.json`: **NOT_APPLIED**. preimage `ad7c7eca9645`, `git apply --check` exit 0.
- `prepare-progress.py`: 현재 파일에서 좁은 일치 문맥으로 후보를 재생성한다. 적용 전 새로운 preimage/lease 확인이 필요하다.
- `candidate-unit.cjs`: 후보에 대한 6개 계약 검사.
- `browser-progress.cjs`: 실제 `/chat`을 로드하고 후보 JS만 route override한 synthetic 검증. 브라우저 인증 상태 저장 없음, 실제 모델 호출 0.

후보는 즉시 status row·클라이언트 대기 초·기존 status code만 허용하는 단계 표시·첫 유효 delta에서 row 제거를 구현한다. 진행률이나 ETA는 없다. 기존 rescue attribution 검사·sanitizer·answer payload·toggle 기본값을 유지한다. 독립 검토에서 발견한 공백 토큰 손실, transport finally의 과도한 terminal latch, attach elapsed 초기화, 빈 final의 CSS spinner 및 resume terminal SSE 오류 cleanup을 수정했다.

## 새 검증 결과

| 검증 | 실제 결과 | 범위/한계 |
|---|---|---|
| CSS/template 계약 | PASS 3/3 | RED 2 실패 → 적용 후 GREEN. 마지막 focused 재실행도 3/3 |
| production 전체 새 계약 | **3 PASS / 3 FAIL** | JS가 미적용이므로 동적 3개 RED. 전체 성공으로 보고하지 않음 |
| 후보 unit | PASS 6/6 | product 파일이 아닌 후보를 읽은 결과 |
| 후보 브라우저 | PASS 41 synthetic checks | 새 context 3개, 320/390/1280px. reasoning·공백·late status·cancel·빈 final·HTTP/SSE resume 오류·EOF 후 exact-run attach·elapsed 포함. 모션 감소 모두 none. 모델 호출 0 |
| 실제 제공 CSS 브라우저 | 관측됨, HTTP 200, 3 widths | fallback spinner 및 모션 감소. 후보 JS가 서버에 적용됐다는 증거가 아님 |
| `processResources` | SUCCESS, exit 0 | UI 적용 후 실행. Java 통합 검증 아님 |
| 기존 `chat_wait_routing_tests.cjs` | FAIL: 600000 vs 120000 | 이 작업에서 chat.js 수정 없음. 전체 suite의 pre-existing 건강으로 확대하지 않음 |
| 기존 `chat_ui_stream_contract_tests.js` | FAIL: DOM order assertion | 보존한 실제 CSS/template preimage로도 동일 실패 재현. assertion 완화하지 않음 |
| `Phase2ApplicationBrowserAuthTest` | 빌드 실패, 실행 tests 0 | compileJava output 정리 실패. 관리자 로그인 성공 증거 없음 |
| 기존 live matrix | live 2 cells FAIL, fixture 1 PASS, generation 0 | 실제 모델 선택 요소가 닫힌 settings details 안에 있어 visible 대기 timeout. 앱의 backend 실패 증거가 아님 |
| 직접 live probe | 새 context 2개, HTTP 200 두 건, UI ready 본문 관측 | greeting 18자, general 240자; 두 화면에서 HOLD/backend 오류 문구 없음. response.text() 실패로 서버 releaseReason 및 실제 모델은 not_observed. 요청 hash `17f06ed91760`, `150277e1600f` |
| decoded live probe (15:33–15:34 KST) | 새 context 2개, HTTP 200 + final 두 건, 실제 모델 확인 | requested/DOM 선택/observed가 `gpt-5.6-luna`로 일치. fallbackCount=0, error event=0, failure=0. greeting 18자/general 240자 ready, HOLD/backend 문구 없음. HTTP/SSE/bubble 요청 hash 일치: `0a7d151366a0`, `dd358e7a47ef` |
| SSE reason 신규 회귀 | PASS 5/5 | RED 4 실패/1 통과 → helper 최소 수정 후 GREEN 5/5. checkpoint capture-01 verified |
| 기존 golden + 신규 회귀 | 22 PASS / 6 FAIL | settings catalog 6개 실패. 보존한 helper 수정 전 바이트로 기존 23개를 실행해 17 PASS / 같은 6 FAIL 확인. 전체 PASS 아님 |
| decoded-event 수집 검증 | PASS 3 cases + 3 privacy checks | 실제 template/static 파일을 브라우저에 합성 route로 제공. SSE error, JSON HTTP 503, final 구분. outbound 모델 호출 0; 실제 서버 결과 아님 |

`candidate-browser-progress.json`은 현재 후보의 41개 결과를 담는다. `served-browser-progress.json`은 적용된 CSS 관측을 담는다. matrix 파일은 `data/agent-handoff/test-model-policy/matrix-2026-10-06T06-02-46-417Z.json`; auth 실패 로그는 작업 폴더 `auth-test/command.log`에 있다. `Verify-RAG`와 Java focused 회귀는 현재 통합 패치에 대해 PASS가 아니다. 공유 coop verify의 ticket-cap으로 새 ticket을 보존하지 못했다. 재시도 성공이나 unattended 재개를 주장하지 않는다.

## 적용을 막는 현재 경계

- `holdScope`: `main/resources/static/js/chat.js` 및 최종 dispatch 연결에 필요한 `main/java/com/example/lms/llm/TimedChatModelCaller.java`.
- `firstBlockingRule`: foreign live lease, owner `codex-answer-truncation-e8df4304`, topic `chat-answer-truncation-20261006`. 15:29 및 15:34 KST source status는 effective 만료 17:21 KST를 표시했다. lease.json의 원래 expiresAtUtc는 16:31 KST다. ChatWorkflow의 `chat-browser10-concept` lease는 15:34 목록에서 사라졌지만 JS와 Timed caller의 겹치는 live lease는 유지됐다.
- `blockingEvidence`: 해당 live lease와 target overlap. 강제 해제·우회 구현 없음.
- `independentWorkCompleted`: CSS/template 적용·회귀 RED/GREEN·후보 unit/browser·SSE reason 수집기 수정과 preimage 비교·decoded collector 합성 검증·공식 문서 교차검증·remote 관계 확인.
- `repositoryWideHold`: false.
- release request: `data/agent-handoff/codex-autonomy/chat-answer-truncation-20261006-e8df4304/LEASE_RELEASE_REQUEST.md`.
- 다음 검증: owner가 lease를 끝낸 뒤 현재 preimage로 재생성 → 자신의 target lease/checkpoint → JS 적용 → focused 계약/Java status producer 회귀 → fresh Verify-RAG와 `/chat` 실제 응답.

현재 `configs/vibe-open.yaml` enabled=true 및 `docs/security/VIBE_OPEN.md`에 따라 관리자 로그인·잘못된 계정·로그아웃 재차단은 **DEFERRED_SECURITY**다. 이를 성공이나 차단 실패로 채점하지 않았고, 인증 범위나 설정을 변경하지 않았다. 유효 관리자 로그인은 not_observed다.

## 외부 근거와 Git

확인일 2026-10-06. 소스 stack: Java 17, Spring Boot 3.3.4, LangChain4j 1.0.1 유지. 기존 active sourceSets가 `main/java`, `main/resources`, `src/test/java`임을 확인했다.

- Spring Framework 6.1.13 [SseEmitter Javadoc](https://docs.spring.io/spring-framework/docs/6.1.13/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html): 기존 named SSE 경로를 사용할 수 있는 계약 확인. 새로운 endpoint나 polling을 추가하지 않았다.
- [Playwright isolated contexts](https://playwright.dev/docs/browser-contexts) 및 [authentication](https://playwright.dev/docs/auth): 새 context를 사용하고 storageState·cookies·tokens·trace를 저장하지 않았다. 설치된 Playwright API와 실행 결과로 추가 확인했다.
- [Spring Security logout](https://docs.spring.io/spring-security/reference/servlet/authentication/logout.html): 세션/CSRF 관련 공식 계약의 참고 근거. 최신 reference 내용을 현재 버전의 인증 변경으로 이식하지 않았다.

로컬 branch `codex/owned-runtime-browser-restart`, HEAD `6c1a250e40c518bffe8bce43cb82ef7792968d4f`. sole remote `UnlimitedAbandonWare/AbandonWareAi`; 해당 branch 원격 SHA `42c8c860a241776e21ff47a0b5d1301a4b99bfc0`는 로컬 HEAD의 4-commit ancestor임을 로컬 graph로 확인했다. targeted ChatWorkflow/chat.js 최근 diff를 보조 증거로 읽었다. 원격 snapshot을 로컬보다 우선하지 않았다. commit/push/fetch/merge/branch 삭제 없음.

## PLUGIN_USAGE

| 역할 | 실제 사용/결과 |
|---|---|
| Superpowers | systematic-debugging, verification-before-completion, TDD 절차: RED→최소 CSS/template 변경→focused GREEN, 미검증 범위 명시 |
| Browser | Playwright/Edge, 새 contexts, 실제 `/chat` + 명시적 후보 route override를 구분. 저장된 관리자 상태 사용 없음 |
| Exa + 웹 | 공식 Spring/Playwright 규격 한정 교차검증 |
| GitHub | branch/commit SHA 읽기 및 로컬 ancestry/diff 비교. mutation 없음 |
| AWX | 실제 실패한 정제 로그 경로만 전달. 연결 app은 USER_NOT_LOGGED_IN; 기본 local AWX는 class=other, matches=0. 이를 원인 확정으로 사용하지 않음. 복구 AWX 미사용 |
| glm_worker | 1차 수정 후 bounded 반박 검토를 시도했으나 task context 전달을 입증하지 못해 SESSION_UNAVAILABLE. 동의를 검증 성공으로 사용하지 않음. native read-only explorer의 지적을 후보 회귀로 검증 |
| Computer / Sites / Data / Visualize / Supabase / Meta / Plugin Management / Vercel | 작업 역할에 필요하지 않아 사용하지 않음 |

자체 source lease는 모두 종료했고, 미기동 auth port lease `apl-07786a84665b`를 release했다. 다른 세션의 프로세스나 lease를 종료하지 않았다. 목표 완료로 표시하지 않았다.

## 15:30 KST 후속 증거

`live-probe-evidence.json`의 두 실제 요청은 HTTP와 UI까지 관측했으나, stream body 읽기 실패로 reasonCode/release/model을 판정하지 않았다. `decoded-probe.cjs`는 브라우저의 기존 `decodeSseEvent` 및 `classifyChatFailure`를 임시 계측하며 원래 return을 보존한다. 본문을 기록하지 않고 allowlisted metadata, machine reason, stage code, 요청 hash만 수집한다. `decoded-fixture-evidence.json`에서 세 합성 응답과 본문 비수집을 확인했다. 로컬 서버가 연결 거절/CATALOG_UNAVAILABLE 상태여서 추가 실제 모델 호출은 실행하지 않았다. 현재까지 이 작업의 실제 POST 예산 사용은 2건이다.

수집기 helper의 독립 검토는 ACCEPT였으나 소스 검사만 수행했고, 이를 테스트 성공으로 취급하지 않았다. 전체 golden 실패 6개는 수정 전 helper로 같은 실패를 재현한 해당 항목에만 baseline 분류를 적용한다. `capture-new-green/command.log`의 Node 출력이 5/5 실행 증거다. 명령 기록 도구의 JUnit tests=0 요약과 혼동하지 않는다.

최종 호출 시작 신호의 안전한 후보 위치는 `TimedChatModelCaller`의 기존 context-wrapped worker에서 cancellation 검사를 지난 첫 모델 호출 직전이다. `chat_draft` 문자열만으로 판별하면 retry와 projection도 포함되어 잘못된 신호가 된다. canonical main draft에서만 기존 timed-call overload에 선택적 callback을 전달하는 변경을 검토했지만 아직 적용하지 않았다. queue timeout 뒤 provider 호출과 callback이 모두 0인 기존 `TimedChatModelCallerTest.timedOutQueuedCallIsPurgedBeforeTheNextAdmission` 기반 회귀가 필요하다. 이 후보도 외부 HTTP 전송 자체를 증명하지는 않는다.

기존 lease가 끝난 뒤 현재 소스에서 후보를 다시 생성하고 실제 적용·Java 회귀·fresh `/chat` 검증을 해야 한다. 이 파일의 부분 적용 기록은 원래 목표의 완료 기준을 축소하지 않는다.

## 15:34–15:38 KST 갱신

새 JVM PID 46932(StartTime 15:31:54 KST), 모델 catalog RESOLVED를 확인한 후 최대 2건으로 제한한 추가 실제 검증을 수행했다. `decoded-live-evidence.json`은 각 요청의 HTTP header hash, SSE requestIdHash, bubble hash가 일치하고, final 이벤트와 실제 `chatgpt_oauth`/`gpt-5.6-luna` 응답을 관측한 기록이다. 두 요청 모두 timeout=false, errorSeen=false, failures=[], fallbackCount=0이다. UI의 HOLD/backend 오류 문구는 없었다. metadata.reasonCode는 빈 문자열이며 별도의 releaseReason/releaseStatus 값은 전송되지 않아 이를 추정하지 않았다. selection status pill의 applied=false와 달리 DOM 선택값 및 서버 observedModel은 요청 모델과 일치했다. 이번 작업의 실제 전송 예산은 앞선 2건을 포함해 총 4건 사용했다.

실제 제공 CSS는 HTTP 200이며 source/served sha12가 모두 `2538171e20f5`다. JS 후보는 여전히 NOT_APPLIED이고, 위 실제 응답은 동적 progress 패치가 적용됐다는 증거가 아니다.

추가 읽기 전용 검토에서 `ctxBuilder.build()`의 문서 수와 `PromotionResult.CONFIRMED_EMPTY`만으로 전체 retrieval 성공/실패를 네 가지로 확정할 수 없음을 확인했다. CONFIRMED_EMPTY에는 인용 locator 부재와 evidence/citation gate 차단이 포함된다. budget skip, rescue/attachment fail-soft와 성공한 0-result 조회를 같은 결과로 표시하지 않아야 한다. 이 불확실성 때문에 Java producer를 아직 추가하지 않았다. 현재 이벤트로 확인할 수 없는 단계는 첨부의 계약대로 일반 대기 표시를 유지하는 대상이다. 다음 producer 검사에는 swallowed attachment/rescue failure와 성공한 0-result를 구분하는 typed outcome의 재현 증거가 필요하다.

## 15:42 KST blocked 감사

동일한 target lease가 세 번의 연속 목표 턴에서 남아 있다. 최신 source status는 topic `chat-answer-truncation-20261006`, active, valid heartbeat, `chat.js`/`TimedChatModelCaller`/해당 Java 테스트 target overlap을 다시 확인했다. 현재 chat.js와 Timed caller sha12는 각각 `ad7c7eca9645`, `9ba8ad5bc160`이다. owner process는 unknown/0이므로 이를 실제 프로세스에 대한 verified wait로 부르지 않는다. lease의 유효성은 편집을 막는 근거로만 사용한다.

독립적인 수정·검증은 위 범위까지 완료했으며, 남은 제품 UI 적용과 최종 호출 신호 연결은 소유권 해제라는 외부 상태 변경이 필요하다. 원래 목표를 작은 통과 범위로 축소하지 않는다. 목표 도구의 세 번째 연속 blocker 조건을 충족해 blocked로 전환할 근거를 `data/agent-handoff/codex-autonomy/codex-wait-progress-aee89b80/blocked-audit-20261006-1542.json`에 남겼다. repositoryWideHold=false이며 자체 lease/프로세스는 남기지 않는다. 재개 시 해당 target lease와 현재 preimage를 먼저 확인한 뒤 기존 후보를 통합해야 한다.
