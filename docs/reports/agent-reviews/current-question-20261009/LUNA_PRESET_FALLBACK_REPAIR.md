# 현재 질문 보존 · OAuth Luna · Focus 표시 수정

확인일: 2026-10-09. Java 17 / Spring Boot 3.3.4 / LangChain4j 1.0.1. 첨부와 외부 제안은 참고 자료로 사용하고 실제 sourceSet, 호출 흐름, 테스트 및 최신 사용자 지시를 기준으로 최소 수정했다.

## 결과와 현재 프로필

Java 190/190, 최종 JavaScript 188/188, 합성 브라우저 UI PASS. 격리한 새 브라우저의 일반 Focus 질문은 OAuth Luna로 실제 답변했다. 서버는 ON이며 PID 59544, `/chat` 200 / health UP이다. 최종 정적 파일 반영은 processResources로 수행했고 추가 재기동은 하지 않았다.

20:14:38 KST 읽기 전용 조회에서 본인 profilePrefix `dfa4c4c2aa24`, ownerHash `33d5f21b86f3`는 **settingsVersion=7**이다. **FIXED / chatgpt-oauth:gpt-5.6-luna / API_ONLY / fallback OFF / search OFF / STANDARD / 200자 / quick OFF**를 확인했다. 이전 v6의 선택 불가 alias 상태 보고는 현재 상태로 취급하지 않는다. 이 작업은 본인 프로필 쓰기, 직접 DB UPDATE, 인증·owner-key 읽기나 대리 사용을 수행하지 않았다. 저장 주체/경로는 NOT_OBSERVED이다. 현재 값은 맞으므로 재저장할 필요가 없다. 기존 Fold 창은 최신 JS 새로고침 후 정상 재연결이 필요하다. 실제 그 창의 module 로드와 안경 렌더는 관측하지 않았다.

후속 사용자 발언 **“잘되더라”**는 USER_REPORTED_SUCCESS로 기록한다. 정확한 모델/설정/안경 표시를 직접 명시한 발언이 아니므로 OAuth Luna 실기기 성공으로 확대하지 않는다. Flash/Flash-Lite 추가 요청은 이 단계의 기록과 lease 종료 후 별도 단계로 처리한다.

## 변경

- `ChatWorkflow.java:3183`: supplied Focus 대화 맥락이 있을 때 PromptContext.userQuery에 현재 확정 질문을 보존한다. 높은 confidence로 과거 다른 제품 질문이 현재 질문을 덮는 경쟁 입력의 RED를 재현했다. 검색용 finalQuery와 일반 `/chat` empty-context 동작은 기존 경계를 유지한다.
- `display-focus-controls.js:136`: Luna 프리셋은 현재 카탈로그의 selectable OAuth Luna만 선택한다. 경로가 없으면 기존 입력값을 유지한다. 저장된 선택 불가/미확인 항목은 정보용 disabled option이다. 유효한 다른 모델 선택도 유지한다.
- `NovaFocusAnswer`, `NovaFocusAnswerService`, `NovaFocusService`, `NovaFocusState`: 기존 accepted-answer 결과에 requestedModel, effectiveRoute/model, 실제 isFallback, 최초 안전한 오류 사유/예외 class를 연결했다. 실제 fallback 횟수로 판단하므로 정상 AUTO는 false다. 다음 질문/정상 응답/닫기에서 metadata를 교체하거나 지우며 lens projection에서는 제외한다.
- `NovaFocusAnswerService`: validation, budget/admission rejection을 retry/fallback으로 넘기지 않는다. fallback OFF, 허용 목록, strict Gemini 검색, 취소, 예산, 이미 공개된 prefix 정책은 보존한다. 일반 OpenAI API 활성화 중간 실험은 원래 YAML/catalog-test bytes로 철회했다. 현재 일반 API alias는 selectable=false / remote_selection_disabled다.
- `display-focus.js:38`: 기존 Fold panel에만 폴백 경고를 표시한다. 본문이나 lens/relay 텍스트에 경고를 붙이지 않는다. 정상 응답 뒤 초기화하며 낮은 stateVersion으로 되살릴 수 없다.
- `display-conversate.js:344`: Focus 응답 적용 전에 캡처한 assist/epoch/poll generation을 확인한다. outbound 차단 기능은 아니다. 필수성이 재현되지 않은 일반 action 전체 검사와 그 전용 테스트는 이번에 추가한 부분만 제거했다. 다른 작성자의 변경과 기존 action/apply는 보존했다.

중복 서비스, 새 계층, provider 우회, 관리자 권한 확대, 새 인증 gate, dependency 변경은 추가하지 않았다. 최종 diff는 원래 줄바꿈을 보존한다. cycle-02의 아홉 LF 파일과 cycle-01의 두 LF 파일을 각각 원래 before bytes와 대조해 복원했다.

## 검증

| 표면 | 실제 결과 | 증거 |
|---|---|---|
| 정확히 선택한 Java 9개 클래스 | 190 tests, failure/error/skip 0 | final-focus-java/run.json; runId 4a58d4ce-6abc-4f0e-9100-54224b85f761 |
| Focus 및 공유 Display 영향 JS | 188 tests, fail/cancel/skip 0 | final-display-js-eol/command.log; runId 8105ad1b-b231-4d10-95cf-be62571f9451 |
| 최종 static resource 반영 | processResources BUILD SUCCESSFUL | final-static-resources-eol/command.log |
| OAuth Focus 일반 질문 | ANSWER_READY, 103자, 5083ms, actual gpt-5.6-luna, fallback false | focus-final-browser/correlated-runtime.json |
| 저장 항목/프리셋 UI | selectable OAuth 선택, unavailable 입력 보존, disabled 저장 항목 | focus-browser-fixture/evidence.json |
| 폴백 배너 | Fold 표시, 정상 초기화, 이전 상태 거부, lens 비표시, 본문 경고 제외 | 같은 evidence; fallback-banner.png |
| 본인 프로필 | 읽기 전용 v7 및 요청 정책 일치 | final-user-profile-readonly.json |
| 현재 소스/served/health | 수정 자산 SHA 일치, /chat 200, UP | final-served-catalog.json |

Java runner의 기록된 Java sourceIdentity 6개는 최종 bytes와 모두 일치한다. JS runner의 totals=0은 JUnit XML만 파싱하는 wrapper의 값이다. 실제 Node log의 188 tests / 188 pass 및 exit 0을 근거로 했다. 과거 189개 결과는 제거된 일반 action 테스트를 포함하므로 최종 수치로 쓰지 않는다. blanket repository test는 실행하지 않았다.

정상 수음 관련 기존 회귀도 마지막 188 실행에서 GREEN이다: `PCM capture starts before the handshake and releases buffered audio once in order`, `a failed audio-start rolls back its own voice latch so the next start can succeed`, `an older failed start cannot clear a newer successful voice attempt`. 동의 경계인 `automatic renewal cannot transfer consent to a new assist`도 통과했다. 실제 마이크/기기 녹음 성공으로 확대하지 않는다.

OAuth Focus 상관 키는 **requestHash=hash:77758c5c0dc3 / sessionHash=hash:97e0b75b7b0b / epoch=1 / serverInstanceHash=hash:bb4384b3d041**이다. helper가 requestHash로 표시했던 `97a7878055ff`는 turnId hash였다. 고립 세션/epoch의 accepted request가 하나임을 확인하고 question_confirmed→generation_started→model_attempt→terminal success/none을 같은 실제 requestHash로 연결했다. requested/selected hash는 OAuth route, result hash는 actual gpt-5.6-luna와 일치했다. fallbackCount=0, effectiveFallbackAllowed=false, searchRequested/allowed=false다. provider wire/HTTP와 hardware render는 NOT_OBSERVED다.

원래 inline DOM 검사는 rAF 이전 같은 콜스택에서 빈 본문을 읽어 FAIL였다. 원본 실패 evidence를 유지했다. 추가 실모델 호출 없이 existing served module의 합성 fixture에 정상 프레임/line layout을 적용해 배너·본문·초기화를 검증했다. 이를 실제 OAuth 응답의 기기 DOM 전체 렌더 성공으로 바꾸지 않는다. malformed settings 400은 helper가 partial settings를 전체 DTO로 전송한 오류였다. server settings 전체에 test-owned 필드만 overlay했고 cleanup DTO도 FocusCommand/relay-settings로 분리했다.

원래 main `/chat` 새 context 결과는 browser-v2/evidence.json에 있다. 안녕?와 일반 질문 모두 stream 200, 실제 본문 표시, HOLD/backend_unavailable 없음, observed OAuth Terra/fallbackCount=0이었다. 관리자 matrix는 auth/run/run.json의 production DAO/security chain + in-memory H2 + 새 브라우저 합성 계정 검증이다. 익명/잘못된 계정/로그아웃 후 protected URL 302 login, 정상 새 로그인 후 200이었다. 공유 PROTO_OPEN 서버의 접근 차단으로 확대하지 않는다. auth 소스는 변경하지 않았다.

## 공식 근거와 검토

2026-10-09 Exa를 공식 규격 확인에 한정했다. Boot 3.3.4와 맞는 Security 6.3 [form login](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html), [session persistence](https://docs.enterprise.spring.io/spring-security/reference/6.3/servlet/authentication/persistence.html), [logout](https://docs.enterprise.spring.io/spring-security/reference/6.3/servlet/authentication/logout.html), [Playwright auth](https://playwright.dev/docs/auth)를 소스/실행과 대조했다. [LangChain4j message roles](https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/chat-and-language-models.md)는 보조 근거이며 main 문서를 1.0.1에 그대로 이식하지 않았다. GPT Pro 모델 자체를 호출했다는 주장은 하지 않는다.

GitHub 보조 조회 당시 local/remote SHA는 d2d62e7ded7cf70a5d9c77b9482f537bf51e4fa5로 일치했다. 관련 ChatWorkflow/chat.js, RagControl, AdminTokenGuard/security diff 기록은 REPORT.md에 있다. 최종 HEAD/branch를 읽기 확인했으며 dirty source는 remote와 별개다. CI 목록이 비어 있어 CI PASS가 아니다. commit/push/merge/branch 삭제는 수행하지 않았다.

GLM 검토는 task-specific plaintext marker를 받지 못해 SESSION_UNAVAILABLE이다. 독립 read-only explorer가 오류 은폐/권한/fallback/state를 반박 검토하고 EOL 변경을 지적해 수리했다. explorer tests는 NOT_RUN이며 동의를 실행 성공으로 계산하지 않는다. AWX는 실제 compile 실패의 정제 로그만 기본 MCP에 전달했다. other / count 0 반환을 보조 증거로 사용하고 compiler/source에서 missing import를 확인해 수리했다. 복구 AWX는 사용하지 않았다. 마지막 resource 첫 명령의 dotted Gradle property 분리 오류는 정확한 argv 배열로 수정했다.

## 기록 및 한계

현재 source leases는 모두 종료한다. source/tests/runtime 증거와 before bytes를 보존한다. 초기 cycle-01 postimage-drift 및 원래 질문 task source-lease-drift HOLD는 기록 경계로 남겨두고 guard/state를 바꾸지 않았다. holdScope=historical-checkpoint-bookkeeping, firstBlockingRule=recorded-postimage-or-lease-drift, repositoryWideHold=false. 실제 현재 소스/검증과 구분한다. 오래된 journal의 AUTO 목적은 최신 FIXED OAuth 요청을 대체하지 않는다. v7 확인으로 프로필 저장 대기는 해소됐다.

공유 H2의 existing-constraint DDL warnings는 live readonly verify에 남는다. health UP, fatal classpath/config/port failure 0이며 별도 DB 수리로 확장하지 않았다. readonly verify의 compile skipped/tests not-wired는 별도 Java/JS/Gradle 로그와 구분했다. 기존 Fold 창 module 적용, 실제 발화 수음, 안경 렌즈 출력과 영상의 전체 end-to-end 해결은 직접 관측하지 않았다. 사용자 보고 성공은 별도 USER_REPORTED_SUCCESS다.

완료 정리는 자기 lease와 조사 child 종료, 최종 증거/회복 파일 보존으로 제한한다. 사용자/타writer source, 채널, 실행 서버, 첨부 파일은 삭제하지 않는다. 한도 증가나 cleanup endpoint를 추가하지 않았다. 이 단계의 추가 provider 호출/재기동/기능 변경은 종료한다.

외부 API: 마지막 실제 OAuth Focus generation 1회; 최종 합성 UI generation 0회; 일반 OpenAI API 활성화/생성 0회. 앞선 단계의 승인된 호출/auth/main browser는 원래 REPORT.md의 기록과 구분한다.
PLUGIN_USAGE:
- Superpowers: USED(systematic-debugging, focused RED→GREEN, verification-before-completion)
- Browser: USED(fresh Playwright contexts, actual OAuth Focus, synthetic UI; no restored/persisted auth state)
- GitHub: USED(local/remote SHA alignment; supplemental commit/diff/CI reads)
- Exa: USED(official version-relevant specifications only)
- AWX Control Tower: USED(actual sanitized compile log; advisory classification)
- AWX Control Tower Recovery: NOT_USED
- GLM=SESSION_UNAVAILABLE(no task plaintext marker; NOT PASS)
- Computer: NOT_USED
- Vercel: NOT_USED
- Sites, Plugin Management, Data, Visualize, Meta Wearables Webapp, Supabase: NOT_USED
