# 2026-09-24 답변 보류 · 운영 화면 — Devin 작업 카드

기준 체크아웃은 `C:\AbandonWare\demo-1\demo-1\src`다. 이 카드는 애플리케이션 소스를 고치지 않는다.
Grok이 프로브, 플러그인 순서, 리스에 걸린 파일의 건너뛰기를 남긴다. Devin이 `main/java`와 `main/resources`를 고친다.

데빈에게 붙일 한 줄:

> `agent-prompts/devin-20260924-answer-hold-assist/devin_operating_card.md`를 읽고, 패치 전에 `python -B agent-prompts/devin-20260924-answer-hold-assist/probe_answer_hold.py`를 실행하라. JSON의 `leaseBlocks` 경로는 수정하지 마라. `status=open`이고 `editAllowed=true`인 항목만 최소 수정하라. ZIP과 지시서의 줄 번호보다 프로브 `anchors.line`이 우선이다. `gradleProof=not-run`은 테스트 통과가 아니다. 비밀번호는 비공개 지시서에서만 읽고 저장소 파일에 복사하지 마라.

## 프로브

프로젝트 루트에서 실행한다.

```powershell
python -B agent-prompts/devin-20260924-answer-hold-assist/probe_answer_hold.py
python -B agent-prompts/devin-20260924-answer-hold-assist/probe_answer_hold.py --self-test
python -B agent-prompts/devin-20260924-answer-hold-assist/probe_answer_hold.py --fail-on-open
```

stdout JSON 하나다. 스키마는 `awx.devin-20260924-answer-hold-probe.v1`.
각 open 항목을 고친 뒤 같은 프로브를 다시 실행한다. 프로브가 닫혀도 Gradle은 따로 실행한다.

| 종료 코드 | 의미 |
|---|---|
| 0 | 보고서를 썼다. `--fail-on-open`이면 편집 가능한 open이 없다. |
| 1 | 편집 가능한 open 또는 regressed가 남았다. |
| 2 | 필수 소스가 없거나 `--self-test`가 실패했다. |
| 3 | 남은 open이 전부 리스에 걸린 파일이다. 그 파일은 건너뛴다. |

`--self-test`가 0인 것은 프로브 스크립트가 보고서를 만든다는 뜻이다. 버그가 닫혔다는 뜻이 아니다.

플랜은 이 브리프만 쓴다. Downloads의 지시서 원문으로 `devin_task_orchestrate.py plan`을 돌리지 않는다. 그 파일에는 초기 비밀번호가 있고, 다른 플레이북 신호와 겹칠 수 있다.

```powershell
python -B scripts/devin_task_orchestrate.py plan --brief-file agent-prompts/devin-20260924-answer-hold-assist/brief.txt
```

`matchedPlaybooks`가 `answer-hold-20260924`가 아니면 그 플랜으로 패치하지 말고 이 카드의 순서를 따른다.

## 누가 이미 같은 일을 잡고 있는지

저널은 리스가 아니다. 쓰기 직전에 프로브 `leaseBlocks`를 다시 본다.

카드 작성 시점에 보인 것들이다. 다음 실행의 프로브가 이긴다.

- 소스 패치 저널 `chat-release-admin-fix-devin-0924-7355c8ae`가 이미 이 지시서의 Java 범위를 적고 있었다. 같은 파일로 저널을 하나 더 열지 말고 그 taskId를 재사용한다.
- Codex 저널 `chat-release-admin-access-cc876c7f`도 같은 파일을 적고 있었다. 저널이므로 파일을 잠그지는 않는다. 바이트가 바뀌었으면 그 파일만 멈춘다.
- 리스 `proto-auth-light`가 `AdminTokenGuardFilter`, `AdminTokenGuardInterceptor`, `ChatOpenSecurityConfig`, `application-local.yml`, `application-meta-display.yml`을 잡고 있었다. 그 다섯 경로는 수정, 락 삭제, recover를 하지 않는다.
- 리스 `proto-light-mode`가 `AGENTS.md`를 잡고 있었다. 이 패치의 지침은 이 카드에만 둔다. `AGENTS.md`, `docs/PROJECT_STATUS.md`, `.agents/skills-intent-index.yaml`은 완료 조건이 아니다.

운영 화면을 로그인 없이 열라는 사용자 요청의 쓰기 소유자는 `proto-auth-light`다. 답변 보류 패치는 그 파일을 기다리지 않고, 리스에 없는 `ChatWorkflow`와 `chat.js`부터 고친다.

## 활성 트리

Gradle `sourceSets.main.java`는 `main/java`다. `src/main/java`로 복사하지 않는다.
테스트 소스셋은 `src/test/java`다. `src/src/test`는 그 소스셋이 아니다. 비활성 트리만 고치고 통과했다고 보고하지 않는다.
`chatUiTest` 소스셋은 `src/chatUiTest/java`다. 같은 테스트가 `src/src/chatUiTest`에만 있으면 그 경로는 실행 대상이 아니다. 프로브 `activeSource`를 본다.

## 첨부 문서의 역할

| 문서 | 역할 |
|---|---|
| Downloads `Devin_Fix_Instructions_20260924.md` | 원인과 회귀시험의 내용. 줄 번호는 첨부 ZIP 기준이라 프로브 줄이 이긴다. |
| Downloads `02_Source_Evidence.md` | 발췌 근거. 현재 파일과 다르면 현재 파일이 이긴다. |
| `docs/video-analysis-report-20260924-143134.md` | `qwen3.5:9b` 콜드 로드와 12초 타임아웃 가설. 확정 원인이 아니다. |
| 이 카드 | 플러그인 순서, 리스, 활성 트리, 사용자 요청과 지시서가 부딪힐 때의 판단. |

영상은 다시 받지 않는다. 증상의 재현은 소스와 테스트와 브라우저로 한다.

## 사용자 요청과 지시서가 다를 때

1. `안녕?`의 보류 문구는 모델 프롬프트가 아니라 서버 공개 경계다. `RagControlProjectionRenderer`의 hold 문구는 유지한다. `verification_outcome_missing`을 지워서 모든 답을 통과시키지 않는다.
2. 검증 불필요와 검증 미실행은 다르다. 불필요하면 본문을 보류하지 않는다. 필요한데 실행하지 못했으면 통과로 바꾸지 않는다.
3. 사용자에게 보여주는 것과 장기 지식에 쓰는 것을 나눈다. 현재 레코드의 다섯 번째 필드는 `evidencePolicyApplied`다. 그 필드를 `knowledgeWriteAllowed`로 이름만 바꾸지 말고, 지식 저장 허용을 따로 둔다.
4. 인사 fallback은 `NoEvidenceChatFallback.isGreeting`에 이미 있다. 인사 함수를 새로 만들지 않는다. 조기 반환이 최종 공개에서 다시 지워지는 경로를 고친다.
5. 사용자가 끈 웹 검색을 future-tech 분기가 다시 켜지 못하게 한다.
6. `backend_unavailable` 분류기를 새로 만들지 않는다. 기존 `ModelSelectionException.failure`가 어디서 한 코드로 뭉개지는지 본다. 12초 `total-timeout-ms`는 가설이다. 프로브가 `verification-not-required-collapsed`를 닫기 전에 타임아웃 숫자부터 올리지 않는다.
7. 일반 채팅의 Selection replay 표는 기본으로 숨긴다. 진단을 켠 사용자에게만 보인다.
8. 운영자 계정은 기존 `administrators`와 `AdminService.createIfAbsent`와 `BCryptPasswordEncoder`다. 새 `users` 테이블, 공개 `/create-admin`, 소스에 박힌 초기 비밀번호, `{bcrypt}` 접두사 추정은 하지 않는다. `createIfAbsent`의 ready 로그는 비밀번호가 맞다는 증거가 아니다.
9. 초기 비밀번호는 비공개 지시서에만 있다. 프로세스 환경변수 `LMS_ADMIN_BOOTSTRAP_PASSWORD`로 일회 주입한다. 명령 인자, 셸 기록, 보고서, Git, 이 카드에 값을 쓰지 않는다.
10. 로그인 없이 운영 화면을 쓸 수 있어야 한다. 이것은 지시서 H의 “비관리자 세션은 관리 경로 거부”보다 우선이다. 익명 세션이 데스크톱의 채팅, 모델 선택, 진단 표시, model-settings, pipeline-status, 채팅이 쓰는 진단 읽기를 사용할 수 있으면 된다.
11. 틀린 비밀번호와 클라이언트가 보낸 role 쿠키는 admin principal이 되지 못한다. CSRF는 세션으로 허용하는 쓰기에 유지한다. “로그인 403이니 CSRF를 끄자”는 수정은 하지 않는다. `.secrets`, openssl, 기계 토큰 발급, 다른 테넌트 owner scope는 열지 않는다. `SecurityFilterChain` 전체를 `permitAll`로 갈아엎지 않는다. `ChatOpenSecurityConfig`는 이미 채팅 익명 경로를 갖고 있으므로 두 번째 보안 체인을 만들지 않는다. 가드 파일은 리스가 끝나면 그 세션의 결과를 이어서 본다.

## 플러그인

프로브 `pluginLane` 순서를 지킨다. 한 단계에 플러그인을 몰아 쓰지 않는다.

쓰지 않는 것: Supabase, Data, Wolfram, SciSpace, Sites, Meta Wearables, 복구용 AWX. 관리자 테이블이 없다는 이유만으로 Supabase에 SQL을 보내지 않는다. datasource가 그 제품임이 소스와 설정으로 확인되기 전에는 DB 도구를 고르지 않는다.

스터프3에 붙은 `@objective-executor`부터 `@demo1-agent-api-spend-guard`까지의 나열은 라우팅이 아니다. 페이즈마다 플랜이 준 스킬 하나만 쓴다.

GitHub는 로컬 HEAD와 원격 SHA가 같을 때만 보조 증거다. push, merge, 브랜치 삭제는 하지 않는다. 이 카드가 커밋을 허가하지 않는다.

Browser 로그인은 새 컨텍스트에서 직접 한다. 저장해 둔 인증 상태 파일을 성공 증거로 쓰지 않고 Git에 넣지 않는다.

## 수정 순서

프로브를 먼저 실행하고 `editAllowed=false`인 파일은 뺀다.

1. `verification-not-required-collapsed`가 open이면 `RagControlRuntimeAdapter.verificationFinding`이 검증 불필요를 `verification_not_required`로 계속 진행하고, 필요한데 결과가 없으면 `verification_outcome_missing` HOLD를 유지한다.
2. `release-and-memory-coupled`와 `prior-fallback-locks-release`가 open이면 `ChatWorkflow`의 공개 허용과 지식 저장을 분리한다. fallback이 적용됐다는 사실만으로 본문을 보류하지 않는다.
3. `explicit-search-off-reenabled`가 open이면 명시적 검색 끔 뒤에서 `useWeb = true`로 되돌리지 않는다.
4. `selection-replay-ungated`가 open이면 `renderSelectionEntropyTrace`가 진단 스위치 없이 표를 붙이지 않는다. 닫힘 토큰은 프로브 detail에 있다.
5. `bootstrap-password-always-required`가 open이면 `security.bootstrap-admin.enabled`일 때만 그 비밀을 필수로 본다.
6. `always-remember-ignores-checkbox`가 open이면 `AppSecurityConfig`와 `CustomSecurityConfig` 둘 다 고친다. 한쪽만 고치면 닫히지 않는다.
7. `admin-form-login-ignored`는 리스가 없을 때만 고친다. 폼 로그인으로 만들어진 관리자 Authentication을 허용 조건에 더한다. 헤더 토큰 경로는 남긴다. 리스가 있으면 exit 3으로 남기고 다른 항목을 계속한다.
8. `operator-ui-without-admin-principal`은 프로브 실패 조건이 아니다. 가드 리스가 살아있는 동안 이 카드의 패치가 그 파일을 열지 않는다.
9. `hold-notice-kept`와 `greeting-fallback-exists`는 preserve다. 없어져 있으면 regressed다. 문구를 지우거나 인사 클래스를 복제하지 않는다.
10. `conversate-12s-timeout-hypothesis`는 가설이다. 요청 기록으로 그 기한이 실제 실패 지점임이 확인되기 전에는 `DynamicChatModelFactory`, `LlmRouterAspect`, `ConversateApiCueService`, `chat-model-picker.js`를 첫 패치로 열지 않는다. `application-meta-display.yml`이 `leaseBlocks`에 있으면 그 파일도 열지 않는다.

## 검증

- `.\gradlew.bat :compileJava :processResources -x test`
- 새로 만든 시험만. 외부 모델과 운영 DB 없이 결정적으로 실행한다. 시험 위치는 `src/test/java`다.
- 제안 이름이 이미 있으면 그 시험을 확장한다. `AnswerReleasePolicyTest`, `ChatFallbackTerminalStateTest`, `AdminLoginSecurityIntegrationTest`.
- 프로브 `--fail-on-open`이 1이면 편집 가능한 구멍이 남았다. 3이면 리스 때문에 남은 것이다. 둘 다 Gradle 통과를 대신하지 않는다.
- 브라우저에서 새 세션으로 `안녕?` 본문, 일반 질문의 실패 코드, 틀린 계정의 거부를 본다. 보호 URL의 HTTP 200만으로 끝내지 않는다.
- 18180 ForceRestart는 다른 세션이 `main/java`를 고치는 동안 하지 않는다. 라이브 증명이 필요하면 `scripts/agent_port_lease.py`로 빌린 포트에서 한다.

## 보고

변경 파일, 수정 전후 SHA-256, 실행한 명령과 exit code, 프로브 `openFindingIds`, `leaseBlocks`, 아직 `not_observed`인 항목을 나눈다.
상태 이름은 `FIXED_AND_VERIFIED`, `REPRODUCED_NOT_FIXED`, `BLOCKED_EXTERNAL`, `BLOCKED_LIVE_LEASE` 중 하나를 쓴다.

다음 문장만 증거로 답한다.

- `안녕?`에서 hold 문구가 사라졌는가, 그리고 검증이 필요한 질문의 보류는 남았는가?
- 공개 허용과 지식 저장이 다른 값인가?
- Selection replay 표가 기본 채팅에서 숨는가?
- 틀린 비밀번호가 admin principal이 되지 않는가?
- 운영 화면의 익명 사용은 누가 고쳤는가, 아니면 리스 때문에 `BLOCKED_LIVE_LEASE`인가?
- 초기 비밀번호 값이 소스, 로그, 보고서, Git에 없는가?
