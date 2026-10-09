# 현재 확정 질문 보존 및 Focus AUTO 기본 모델 — 2026-10-09

현재 확정된 Focus 질문이 검색용 재작성 문장에 의해 프롬프트의 `USER QUESTION`에서 바뀌던 경계를 수정했다. 후속 기본 모델 단계에서는 기존 `llmrouter.gemini-cue` 경로를 Focus AUTO의 profile 기본값으로 선택했다. 최종 관련 5개 테스트 클래스 115개 및 별도 관리자 브라우저 테스트 1개가 통과했다. 일반 `/chat` 두 질문도 새 브라우저 context에서 응답 본문과 같은 요청의 모델 trace를 확인했다.

추가로 전달된 실제 Focus 종료 보고와 재현된 질문 보존 문제가 동일 원인이라고 주장하지 않는다. 변경 전 서버에서 별도로 관측한 Focus 실패는 `provider_not_configured`이며, 영상과 동일 요청인지 확인되지 않았다. 최초 질문 보존 checkpoint 봉인은 `source-lease-drift`로 거절되어 기록 절차의 상태는 PARTIAL/HOLD다. 후속 기본 모델 단계의 checkpoint는 같은 lease를 유지해 seal/finish 모두 성공했고 verified다.

## 요청과 구현 경계

- 입력: `C:/Users/nninn/Downloads/PASTE_CODEX_current_voice_question_web_evidence_20261009.txt`, 계약 `NOVA-CURRENT-QUESTION-FRESH-EVIDENCE-20261009`.
- 첨부의 과거 TXT-only 작성 지시는 참고 자료다. 이번 사용자의 실제 요청은 소스 수정이다.
- active sourceSet: root `main/java`, `main/resources`, `src/test/java`; Java 17, Spring Boot 3.3.4, LangChain4j 1.0.1. 의존성 변경 없음.
- 작업 시작부터 존재하던 다른 작성자의 NovaFocus/Display/config/script 변경은 유지했다. 두 단계의 보존한 preimage 대비 최종 변경은 7개 파일, 165줄 추가/4줄 삭제다. production Java 자체는 3줄 추가/3줄 삭제이며 profile 설정은 1줄 교체다. `final-owned-change.diff`, `final-owned-file-evidence.json`의 7개 현재 해시는 최종 115개 실행의 sourceIdentity와 일치한다.

## Focus AUTO 기본 모델 단계

`application-meta-display.yml:155`의 fallback만 `${CONVERSATE_FOCUS_DEFAULT_MODEL:llmrouter.gemini-pro}`에서 `${CONVERSATE_FOCUS_DEFAULT_MODEL:llmrouter.gemini-cue}`로 변경했다. 기존 catalog의 cue는 `gemini-3.5-flash-lite`에 연결된다. 새로운 provider, model ID, service, fallback 정책은 추가하지 않았다. `CONVERSATE_FOCUS_DEFAULT_MODEL` 환경 override와 기존 FIXED 선택 분기는 유지했다. `web-model`, 검색 권한, reasoning, timeout, 관리자 권한은 바꾸지 않았다. legacy AUTO의 routing-null 경로까지 재설계하지 않았다.

2026-10-09 [Google 공식 모델 문서](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite)로 model ID와 text/thinking/search grounding 지원을 교차 확인했다. 문서의 기능 지원과 이 앱에서 실제 검색이 실행됐다는 증거는 구분한다. 단일 probe 간 latency 차이로 가장 빠른 모델이라고 주장하지 않는다.

실제 profile을 읽는 새 회귀는 기본 AUTO cue 선택과 환경 override pro 선택을 검사한다. production 설정 변경 전 1개 실행은 expected cue / actual pro assertion으로 RED였다(`default-red-test/run.json`). 설정 변경 후 정확히 5개 클래스의 최신 실행은 115개, 실패/오류/skip 0이다(`final115/run.json`, runId `7f4f8d1e-a6d9-4edc-99e5-dde913a312be`). 새 fixture의 FIXED pro 행에는 직전 env pro override가 남으므로 이 행만으로 다른 cue 기본값보다 FIXED pro가 우선됨을 입증했다고 주장하지 않는다. FIXED 보존 판단은 변경하지 않은 분기와 기존 별도 회귀에 따른다.

작업 `codex-focus-fast-default-19d8934a`의 2개 target은 preimage 검증, 동일 lease의 apply/seal, GREEN 로그를 결합한 finish로 verified다. 원래 작업 `codex-current-question-5b43f617`의 기록 절차 HOLD를 덮어쓰지 않았다.

## 재현한 원인과 수정

`NovaFocusAnswerService`는 확정 질문을 DTO message로, 과거 맥락을 별도의 supplied `ChatConversationContext`로 전달한다. `ChatWorkflow`는 과거 맥락을 사용한 confident clarification을 `finalQuery`로 채택하고 검색에 사용한다. 패치 전에는 같은 `finalQuery`가 PromptContext.userQuery에도 들어가고, 마지막 UserMessage에는 원래 질문이 들어갔다. 따라서 프롬프트 내부에 서로 다른 현재 질문이 생겼다.

PromptContext 입력을 다음으로 바꿨다.

```java
.userQuery(conversationContext.present() ? userQuery : finalQuery)
```

현재 production 호출 그래프에서 supplied context producer는 Focus 어댑터다. 일반 `/chat`은 empty context로 기존 finalQuery 동작을 유지한다. 검색 자체의 재작성·검색 권한·모델 선택·관리자 권한은 이 패치의 변경 대상이 아니다. 향후 supplied context producer가 추가되면 적용 범위도 넓어지므로 그 호출 계약을 함께 검토해야 한다.

## 테스트 증거

| 실행 | 실제 결과 | 의미 |
|---|---|---|
| 최초 정확히 선택한 5개 클래스 | 107개, 5실패 | 원인 재현 3건과 테스트 fixture 오류 2건; 성공으로 취급하지 않음 |
| fixture 수정 후 RED | 114개, 3실패 | 높은 confidence로 다른 제품 질문을 만든 Focus 3행이 실패; production 수정 전 |
| production 수정 후 compileJava + test | compileJava 성공; 107개 통과 | 한 suite 선택 문자열 오류로 7개 누락; verification runner는 evidence_incomplete, 전체 GREEN 아님 |
| 최종 `currentQuestionVerification` | 114개, 실패/오류/skip 0 | 정확히 5개 suite 재실행, GREEN |
| `currentQuestionAuthVerification` | 1개, 실패/오류/skip 0 | 실제 Spring 앱 + DAO + 새 브라우저 로그인/로그아웃 |

첫 단계의 114개는 기존 compiled classes를 읽는 단일 Test task로 실행했다. `.files`로 task 의존성을 분리한 dry-run은 해당 Test task 하나만 표시했고, 실제 로그에도 해당 task 하나만 있다. 당시 6개 소스 해시는 해당 run의 sourceIdentity와 일치했다. 후속 default-model 회귀로 변경된 test 파일은 최신 115개 실행에 다시 결합했다. 첫 단계의 새로운 회귀 실행 16개는 제품 질문 3종의 Focus/일반 채팅 대조, low/null confidence, 수정된 최종 음성 문장의 3초 commit 및 duplicate, dated/undated evidence 렌더링, DTO 질문/맥락 전달, confident wrong-product 문장의 UnknownAnswerPolicy 분류 경계를 포함한다. 기존 memory/search/session 관련 assertions도 유지했다.

증거:

- `red/run.json`, `red/command.log`: intended RED 3건.
- `final114/run/run.json`, `final114/run/command.log`, `final114/xml/`: 114 GREEN, runId `9a6114d6-0517-4aea-9887-64a44d0714bb`.
- `auth/run/run.json`, `auth/xml/`: 관리자 격리 검증 GREEN, runId `c9beabf5-b4a0-46d0-abdf-d3f1d402c452`.
- `owned-change.diff`, `owned-file-evidence.json`: 이번 변경만의 diff와 최종 소스 결합 확인.

전체 repository blanket test는 실행하지 않았다. 이 결과는 선택한 영향 범위와 아래 브라우저 표면의 결과다.

## 브라우저와 런타임

| 표면/장면 | 관측 결과 | 상관관계 |
|---|---|---|
| 새 context, `안녕?` | stream 200, 실제 본문 표시, HOLD 없음 | requestHash `f2915aa9cc32`, trace correlationHash `822139e13177` |
| 새 context, 광합성 일반 질문 | stream 200, 실제 본문 표시, backend_unavailable 없음 | requestHash `1de3b590ccc6`, trace correlationHash `f9e19a9796b4` |
| 격리 관리자 익명 접근 | `/admin/debug-events` → 302 `/login` | requestHash `4b4a5d2d2faf` |
| 잘못된 합성 계정 | POST `/login` → 302 `/login?error`; 보호 URL 다시 302 | requestHash `d30b2bc8d8bd` / `d3c78f341c28` |
| 새 정상 로그인 | POST `/login` → 302 `/index`; 보호 URL 200 | requestHash `d6900ed4cf4f` / `95f411c272f1` |
| 로그아웃 후 | POST `/logout` → 302 `/login?logout`; 보호 URL 다시 302 | requestHash `f541222cffad` / `87d1d1f369a1` |

일반 채팅 두 요청의 observed model/provider는 `gpt-5.6-terra` / `chatgpt_oauth`, fallbackCount 0이다. 정책 선택 `chatgpt-oauth:gpt-5.6-terra`와 일치한다. 스트림을 끝까지 파싱했고 해당 session/turn trace의 model/provider/route/fallback을 대조해 matched=true였다. reasonCode는 빈 값이었다. trace에서 `finalAnswer.releaseAllowed=true`, `releaseReason=verification_not_required`, `evidenceReleaseState=not_applicable`, `retrievalExecution=not_requested`를 관측했다. 이것을 fact verification PASS로 해석하지 않았다.

최초 2회는 UI 본문과 HTTP를 확인했으나 CDP response-body 캡처가 실패해 검사 FAIL이었다. 기존 golden browser 도구의 page fetch-clone 방식을 재사용한 1회 재검증 2건에서 증거를 완성했다. 총 실제 generation POST는 4건이다. selection helper의 상태 문구 판정 applied=false는 남겨두고, 실제 송신 선택과 서버 observed-model 증거로 확인했다. 오류 console/network 건수는 재검증 두 context 모두 0이다.

관리자 검증은 임시 포트 18200, in-memory H2, 실제 production DAO/security chain, 합성 계정으로 실행했다. 새 context 2개, restoredAuthState=false, generationPosts=0이다. secret/state/쿠키를 저장하지 않았다. 인증 응답은 reason header가 없어서 serverReasonCode=null이며, 이를 임의의 reasonCode로 채우지 않았다. 현재 공유 PROTO_OPEN 모드는 유지했고 이 격리 검증을 공유 서버의 인증 차단 증거로 주장하지 않는다. 포트 lease `apl-11fee546d8d6`는 반납했고 18200 listener가 남지 않았다.

첫 단계의 읽기 전용 status에서는 PID 2928, health UP, webReady=true를 관측했다. 후속 default-model 설정 변경 후 `runtime-verify.json`은 runtime/HTTP/health 통과, freshness 실패(`sourcesNewer=true`, newest `application-meta-display.yml`)였다. 이를 새 설정 반영 성공으로 계산하지 않았다. 승인된 Close-RAG는 기존 dev runtime만 종료했고 shared Ollama 두 개는 유지했다. Start-RAG의 미지원 `-NoBrowser` 옵션은 parameter binding 단계에서 1회 거절됐고, 확인된 기존 PowerShell 런처의 `-MetaDisplay -ForceRestart -DevWatch -Preload`로 바로 수정했다. 원래 Java 수정 뒤의 자동 DevWatch 재기동은 미리 안내하지 못해 사용자에게 보고했고, 이번 명시적 재기동은 사전 알림/진행 신호 뒤에 수행했다. verification-only task의 첫 시도에는 `.files` 분리 전 암묵적 compile/resource task가 발견되어 해당 task-owned 실행을 중단했다. 첫 단계의 마지막 114/auth 검증은 dry-run 확인 후 shared compile/resource task 없이 실행했다.

최종 18:41:08 KST에 이미 진행 중인 런처가 ready로 완료됐다. `var/rag-launcher/20261009-183657-468a997c/result.json`은 status=ready, springReused=false, PID=69604, role=dev, profile=local,meta-display다. 이 런처의 Gradle 검증은 LangChain4j 순수 버전, sourceSet hygiene, compileJava를 실제 실행해 BUILD SUCCESSFUL이었다. `runtime-verify-after.json`의 읽기 전용 검증은 exitCode=0, failedChecks=[], runtime/ports/http/health/freshness 통과, sourcesNewer=false다. 별도로 `/chat`은 HTTP 200이었다. `runtime-final-identity.json`의 실행용 `build/desktop-meta-display/resources/main/application-meta-display.yml` 해시는 수정한 소스와 일치하고, sourceAssetHash와 servedAssetHash도 같다. 따라서 오래된 PID의 HTTP 200을 새 반영 증거로 사용하지 않았다.

최신 전달에서 사용자가 직접 서버를 켜고 테스트를 시작했으며 추가 실행 중단을 요청했다. 수신 뒤 추가 source 적용/build/restart/합성 질문을 수행하지 않았고, 이미 진행 중인 dev start의 완료와 서비스 상태만 관측했다. 이 시작 명령에 포함돼 있던 DevWatch arm도 기존 in-flight 명령의 결과다. 별도로 watcher 설정을 변경하거나 사용자 서버를 kill하지 않았다. 사용자의 중복 실행 창에서 보고된 `launcher-already-running`은 현재 서비스의 준비 실패나 wear 역할의 증거가 아니다. 현재 관측한 18180/18181 runtime은 dev이며 wear로 확대하지 않는다. 최종 읽기 검증의 DevWatch detail은 armed/assessment=not_running이므로 지속 watcher 실행 성공도 주장하지 않는다.

새 profile 적용 후 검색 허용 AUTO Focus generation은 **NOT_RUN**이다. 따라서 실제 선택 모델/effective @Value binding, 검색 켠 동일 Focus 요청의 답변 성공, 영상 문제 해결, 실기기 렌즈 출력은 **NOT_OBSERVED**다. 변경 전 독립 합성 probe의 native cue/FIXED cue/AUTO pro 성공은 각각 `native-cue-probe.json`, `focus-cue-probe.json`, `focus-auto-before.json`에 hash/count만 보존했다. 이들은 검색 OFF 또는 별도 surface였으며 새 default의 검색 ON 성공을 대신하지 않는다. `focus-cue-probe.json`의 request correlation은 NOT_OBSERVED로 정정돼 있다. 운영 서버는 켜두었으며 추가 모델 비교를 실행하지 않는다.

최종 verify의 compile 항목은 의도적으로 skipped, tests는 not-wired다. compile/test 성공은 위 별도 실제 로그 및 최종115 XML로 판단한다. health가 통과한 기존 H2 DDL already-exists 경고는 exceptions warn으로 남겼고 이번 질문/기본 모델 변경의 원인으로 확정하거나 별도 DB 수리로 확장하지 않았다.

## 외부 교차검증과 독립 검토

확인 날짜는 2026-10-09다. Boot 3.3.4 프로젝트의 Security 6.3 계열 계약과 관련되는 [공식 form login 문서](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html), [6.3 persistence 문서](https://docs.enterprise.spring.io/spring-security/reference/6.3/servlet/authentication/persistence.html), [6.3 logout 문서](https://docs.enterprise.spring.io/spring-security/reference/6.3/servlet/authentication/logout.html)를 참고했다. 기존 인증 구조를 바꾸지 않고 실제 chain의 새 로그인과 로그아웃을 검증했다. [Playwright authentication 문서](https://playwright.dev/docs/auth)를 확인하고 저장된 상태를 fresh login의 증거로 사용하지 않았다.

[LangChain4j 공식 message-role 문서](https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/chat-and-language-models.md)는 roles를 이해하는 보조 자료로 사용했다. 검색 결과가 1.0.1 tag 호환성 자체를 입증하지 않으므로, API 적용 여부는 repository에 고정된 1.0.1과 실제 compile/test를 기준으로 했다. 최신 main 문서를 코드로 이식하거나 버전을 바꾸지 않았다.

로컬 branch `codex/owned-runtime-browser-restart`의 HEAD와 연결된 GitHub branch SHA는 모두 `d2d62e7ded7cf70a5d9c77b9482f537bf51e4fa5`였다. 로컬 dirty 변경은 별도이며 remote snapshot과 같다고 주장하지 않았다. 관련 변경을 보조 증거로 확인했다.

- [ChatWorkflow/chat.js 변경 6a8d0cd](https://github.com/UnlimitedAbandonWare/AbandonWareAi/commit/6a8d0cd2202609df887db499632674045c4524c0): 요청 trace envelope/모델 및 provider 상태 연결.
- [RagControl 변경 a3754a3](https://github.com/UnlimitedAbandonWare/AbandonWareAi/commit/a3754a3f8faad760e90c2c23409a4064f3ae09da): 검증 terminal stage/reason과 insufficient/unavailable 구분.
- [AdminTokenGuard 변경 b43c4cf](https://github.com/UnlimitedAbandonWare/AbandonWareAi/commit/b43c4cff46955b9ccdda28d46e6e12d9bc03c980): 기존 PROTO_OPEN 및 form-login admin-session 동작.
- [AppSecurityConfig 변경 433bd6f](https://github.com/UnlimitedAbandonWare/AbandonWareAi/commit/433bd6f56bc68b216f4cba201b91926564ddf789): 로컬 해당 commit의 선택 diff로 preferences PATCH matcher 1줄 추가 확인. 큰 remote commit 결과의 300-file 범위에는 이 파일이 없었다.

GitHub workflow 조회는 결과가 빈 목록이었다. CI 성공 증거가 아니다. commit/push/merge/branch 삭제를 수행하지 않았다.

GLM은 첫 패치 이후 public/synthetic 검토 packet 1회만 전송했다. task-specific plaintext marker가 돌아오지 않은 TIMEOUT_SOFT 결과로 SESSION_UNAVAILABLE이다. fallback 독립 explorer는 patch의 질문 경계 수정과 관리자/검증 정책 무변경을 확인하고 actionable defect를 찾지 못했다. 동의는 실행 검증으로 계산하지 않았다.

## 한계와 보존

- fake clarifier가 다른 제품 질문을 높은 confidence로 만들어 주는 경쟁 입력을 재현했다. 실제 provider가 동일한 잘못된 clarification을 만들었다는 증거는 아니다.
- 검색 query는 기존 finalQuery를 유지한다. 잘못된 제품의 retrieval까지 보장해서 수정한 것은 아니다.
- 날짜/출처/미확인 렌더링 테스트는 synthetic evidence다. 실제 웹 검색의 freshness, model fact compliance, 실기기 렌즈 출력은 별도 증거가 필요하다.
- UnknownAnswerPolicy는 confident한 잘못된 제품 설명을 unknown으로 검출하지 않는다. 기존 정책을 사실 검증기로 확장하지 않았다.
- 원래 cycle-01 preimage는 모두 무결성이 확인되고 보존됐다. 여러 정상 ended lease 이후 checkpoint의 원래 lease ID와 달라 `seal`이 거절됐다. 기록을 바꾸거나 guard를 우회하지 않았고, 그 checkpoint가 sealed/verified라고 주장하지 않는다. 지원된 복구 경로가 없다는 읽기 검토를 기록했다. holdScope=cycle-01-bookkeeping, firstBlockingRule=source-lease-drift, repositoryWideHold=false.
- 완료 정리에서 소스, 최종 diff/보고/검증, 최초 회복 bytes를 보존한다. 사용자 첨부나 다른 task의 산출물을 삭제하지 않는다.
- 후속 default 단계의 자기 source lease만 released=true로 종료했고 `work_journal close --check-evidence`는 verified 및 PROJECT_STATUS 기록에 성공했다. 원래 질문 보존 journal은 partial로 유지했다. 서버, 사용자 실행, shared Ollama, DevWatch에는 종료 정리를 적용하지 않았다. 관리자 임시 port lease는 앞서 반납됐다.
- 후속 journal에는 `state.md` delivery contract가 생성돼 있지 않았다. task-bound reject-complete는 continuity-schema-invalid로 거절됐고, 존재하지 않는 binding/revision을 만들어 채우지 않았다. 스킬이 유지하는 legacy prose-only check는 exit 0이었다. 이는 완료 문장이 지시서 서문이 아님을 검사한 결과이며 task delivery contract 성공의 증거가 아니다. commit은 사용자 명시 지시에 따라 deferred, push/merge/branch 삭제 없음.

외부 API: Exa/GitHub 공식 근거 조회; 실제 OAuth 일반 채팅 총 4건; native cue 1건 및 격리 Focus 합성 질문 2건(기본 모델 변경 전); AWX 실제 실패 로그 분류; GLM 독립 검토 1회 timeout. 기본값 변경 후 합성 생성은 최신 중단 요청에 따라 NOT_RUN.
PLUGIN_USAGE:
- Superpowers: USED(systematic-debugging, regression RED→GREEN, verification-before-completion)
- Browser: USED(Chrome에서 UI 확인; Playwright 새 context 일반 채팅 및 격리 DAO 로그인 검증)
- GitHub: USED(local/remote SHA 비교 후 관련 commit/diff 및 CI 보조 조회)
- Exa: USED(Spring Security/Playwright/LangChain4j 및 Google Gemini 3.5 Flash-Lite 공식 자료 교차검증)
- AWX Control Tower: USED(연결 app은 USER_NOT_LOGGED_IN; 기존 local 기본 MCP로 실제 실패 로그 분류, other 반환; 원인 판정은 XML/소스)
- AWX Control Tower Recovery: NOT_USED
- GLM=SESSION_UNAVAILABLE(TIMEOUT_SOFT; task marker 미수신; 동의나 PASS로 계산하지 않음)
- Computer: NOT_USED
- Vercel: NOT_USED
- Sites, Plugin Management, Data, Visualize, Meta Wearables Webapp, Supabase: NOT_USED
