@superpowers

# Meta Ray-Ban Display sync webapp — Desktop 실행 작업

작성일 2026-09-12. 현재 사용자 요청은 제공한 조사로 소스 수정에 사용할 스킬·작업을 준비하는 것이다. 이 문서는 그 실행 작업이며 애플리케이션 구현 완료 보고가 아니다. Desktop이 이 구현 작업을 맡으면 현재 대화와 인계에 기록된 사용자 구현 의도를 재사용한다. 지시서 재작성이나 수동 스킬 호출을 요구하지 말고 다음 미완료 단계부터 기존 소유권 경계 안에서 수행한다. 파일 발견만으로 별도 백그라운드 실행이 시작된다고 간주하지 않는다.

Primary route: `$demo1-meta-display-webapp`, `.agents/skills/demo1-meta-display-webapp/SKILL.md`; pairedArtifact=`.agents/skills/demo1-meta-display-webapp/agents/openai.yaml`. 상세 기준선은 해당 `references/source-contract.md` 하나를 재사용한다. 다른 Next.js/BFF나 새 provider orchestration 경로를 만들지 않는다.

실행 역할은 현재 단계 하나만 선택한다: E0/재개 기록은 `$demo1-meta-display-resume`, E1/E2 구현과 집중 검사는 `$demo1-meta-display-sync-client`, E3/E4 실제 실행 근거는 `$demo1-meta-display-verification`. 각 역할은 `.agents/skills/<skill-name>/SKILL.md`와 `agents/openai.yaml`을 가진다. 공통 source/continuation 계약과 selector 결과를 공유하며 중복 심사를 추가하지 않는다.

## Desktop 접수와 이후 재개

현재 후속 인계는 `data/agent-handoff/notebook/meta-display-startup-desktop-postprocess-20260912-zz5.json`이다. 사용자가 승인한 서버 자동 준비·필요한 소스 수정·패치 후처리를 기존 Desktop 실행 경계에서 이어간다. 이전 `meta-display-resume-20260912.json`과 `meta-display-sync-v1-20260912.json`은 당시 기록으로 보존한다. 인계 후보를 열거 순서나 최신 시각으로 선택하지 않는다. 경량 인계 파일 존재는 Desktop 수신·실행·패치 완료 증명이 아니다.

이 작업을 받은 Desktop은 기존 `demo1-desktop-canonical-goal-intake`로 현재 C 루트와 `AGENTS.md`를 확인한다. 처음에는 `artifact_only` 접수로 작업·스킬을 읽고, 실제 구현에는 현재 사용자 의도와 기존 소스 게이트를 적용한다. Notebook이 작성한 인계 파일의 존재를 Desktop 접수 완료로 바꾸지 않는다.

관련 패치 시작/검증 직후 `python .\.agents\skills\demo1-meta-display-webapp\scripts\next_step.py --root .`를 한 번 실행한다. E0 기록이 없으면 현재 Desktop 확인부터 하고, 이후에는 반환된 미완료 단계의 구현·검증을 이어간다. 이미 끝난 작업을 다시 계획하거나 같은 지시를 사용자에게 재요청하지 않는다. 기록 방법과 CLI의 `--requirements`는 [재개 계약](../.agents/skills/demo1-meta-display-webapp/references/continuation-contract.md)을 따른다.

기록용 E0는 작업 접수 확인만 뜻한다. 아래 E0의 현재 소스 게이트·RED 조건은 소스를 바꾸는 각 세션에서 별도로 충족한다. E1–E4는 단계별 증거의 현재 파일 일치 여부로 선택하며, E5는 자동 승격하지 않는다. 이 선택기는 기존 source guard, `goal_next_auto`, terminal patch postprocessor를 교체하지 않는다.

## GoalContract

- goalId: meta-display-sync-v1-20260912
- rewrittenUserIntent: Meta Ray-Ban Display를 위한 기존 RAG sync 연결 작업을 Desktop이 추측 없이 구현하도록 스킬과 단계별 작업을 준비한다.
- desiredOutcome: 같은 Spring origin의 Display UI에서 질문/preset, loading, 실제 답변/출처, 방향키 카드 이동을 구현하고 각 검증 단계의 증거를 남긴다.
- measurableSuccess: 준비 단계는 실제 소스 기반 스킬·작업·인계 파일과 검증 PASS. 구현 단계는 단위검사, local sync/session/UI, 공식 Simulator 각각의 실제 결과. 공개 HTTPS와 실기기는 별도 acceptance다.
- nonGoals: 기기·도메인·API 구매, 신규 계정/PAT, DAT/native camera/raw mic, SSE 삭제, 새 Java API/DTO, 배포·터널 자동 공개.
- authorizedMutationSurface: 이 준비 실행은 스킬/Markdown/작업 인계만. Desktop 구현 실행의 후보는 아래 7개 파일이며 현재 사용자 구현 범위·소유권 게이트에서 확정한다.
- prohibitedSurface: 기존 `/stream`, Java API/security/DTO/PromptBuilder, DB/credential/env 이름, 글로벌 Git trust, 타인 lock·lease·변경분, commit/push/deploy.
- evidenceBaseline: canonicalWorkspace=Y:\; backingShareIdentityVerified=true; backingShareIdentityReason=match. 소스 계약은 source-contract.md. `.git/index.lock` 존재, trusted Git은 dubious-ownership, Notebook java 명령 부재, 앱 도구에서 연결된 Desktop 프로젝트는 반환되지 않음.
- assumptions: 신규 static client가 기존 sync 계약을 사용하면 backend 수정 없이 1차 목표를 달성할 수 있다. 이는 파일 근거에 따른 구현 가설이며 runtime 성공을 뜻하지 않는다.
- constraints: `content/evidence` client projection; cookie 소유권; logical-submit Idempotency-Key와 single-flight; HTML 삽입 금지; 단계별 proof; 현재 sourceSet·Java17·lock/lease/preimage를 실행자가 재확인. 기존 admission Redis/DataSource의 실제 가용성도 sync 증명에 필요하다.
- verificationOwner: Notebook은 스킬/지시서 증명. Desktop은 실제 애플리케이션·Gradle·runtime proof. 공식 Simulator와 실기기 실행은 해당 환경의 실행 증거가 필요하다.
- verificationCommands: 아래 단계별 명령 및 source-contract.md의 기존 검사. 실제 결과가 없는 명령은 proposed로 유지한다.
- rollback: 준비 변경은 해당 신설 파일과 AGENTS marker만; 구현 변경은 guard가 기록한 preimage/postimage와 신규 파일 목록만 복구. 기존 SSE·설정 유지.
- stopConditions: root mismatch, unresolved/overlapping writer, lock/lease/preimage conflict, secret risk, wrong sourceSet, undefined verification은 의존하는 작업만 보류한다. source lock은 애플리케이션 mutation을 막으며 독립적인 문서 준비까지 막지 않는다. 외부 API·공개·기기 blocker도 해당 단계만 보류.
- timeBudgetMinutes: 180 (구현 실행 hard cap; 입증 시 즉시 종료)
- goalScore: 88 (스킬·작업 준비 범위 평가; 앱 실행 점수 아님)
- verdict: APPLY for preparation; application mutation/readiness requires current Desktop gate.
- evidence_needed: Desktop execution/Java17/Git ownership+lock resolution; evaluated sourceSets; real sync/provider lineage; official Simulator; access-controlled HTTPS; hardware/account/region.

## SourceDirective

- directiveId: meta-display-client-only-v1
- sourceOwner: desktop
- provenRoot: Notebook이 확인한 canonical Y:\. Desktop의 원본 경로는 해당 세션에서 다시 증명한다.
- provenBranch: filesystem HEAD points to `codex/owned-runtime-browser-restart`; supporting only, trusted Desktop branch/worktree evidence_needed. 이 이름으로 checkout하거나 branch를 만들라는 지시가 아니다.
- activeSourceSets: source-declared root main/java + main/resources, tests src/test/java, isolated src/chatUiTest/java; app/src/main/java_clean + app/src/main/resources. Evaluated Desktop proof=evidence_needed.
- targetFiles: 아래 예정 파일 7개. 현재 존재 여부·작성자·preimage를 guard 시작 직전에 확인한다.
- callPathOrBoundary: static asset → relative POST /api/chat/sync → 기존 session/admission → ChatService/ChatWorkflow → PromptBuilder → ChatResponseDto. 변경은 client 경계에 한정한다.
- beforeBehavior: 기존 채팅/sync는 소스에 존재하지만 Meta Display 전용 static asset·검증은 이번 조사에서 발견되지 않았다. 기존 RAG/LLM runtime 정상은 미확인이다.
- afterBehavior: 600×600 중심 UI에서 한 번의 명시적 전송, 처리 상태, content/evidence 카드, 안전한 오류와 방향/Enter/Escape 제어를 제공한다.
- excludedFilesAndMirrors: 모든 Java와 기존 chat.js/chat-ui.html, app/src/main/java, project/src, 백업·archive·build output, 기존 SSE. 실제 blocker가 이 경계를 넘으면 별도 선언·권한·게이트가 필요하다.
- publicApiChange: forbidden
- secretMutation: forbidden
- redTest: 새 client 계약 검사를 먼저 작성해 잘못된 응답 projection 또는 미구현 client로 실패함을 기록한다. 오류 메시지·exit code를 구분하고 실기기 실패를 합성하지 않는다.
- greenTest: 같은 검사에서 실제 client module이 통과하고 기존 stream 계약이 회귀하지 않으며 local sync/session flow를 실제로 증명한다.
- exactVerificationCommands: 아래 E0–E4 단계. 명령의 상태를 proposed/executed/reviewed-evidence로 구분한다.
- expectedEvidence: 대상 pre/post hash, RED/GREEN exit, fixture assertions, local request count/status/session continuity, Simulator 확장 실행 기록. 원문 질문/답변·cookie는 보관하지 않는다.
- failureClassifications: contract-drift, wrong-sourceset, index-lock-present, source-lease-conflict, session-forbidden, rate-limited, stale-response, simulator-unavailable, public-access-control-unproven, provider-lineage-missing.
- rollback: 현재 guard의 변경 전 파일만 복구하고 신설 파일은 소유권 확인 후 제거. 응답 DTO/보안/SSE를 변경해 통과시키지 않는다.
- patchdropContract: 이 인계는 애플리케이션 patch bundle이 아니다. Desktop 직접 구현은 기존 Desktop guard; 별도 producer 모드를 명시적으로 선택하면 기존 cumulative v3 계약을 사용한다.
- desktopFinalProof: evidence_needed

## 예정 파일과 작업 순서

| 단계 | 대상 | 완료 조건 |
|---|---|---|
| E0: 실행 소유권/RED | 기존 source guard; `scripts/meta_display_webapp_contract_tests.cjs`(신규 예정) | 현재 Java17·sourceSet·writer 증명, 의미 있는 RED |
| E1: 순수 client 계약 | `main/resources/static/assets/display/display-core.js`(신규 예정) | 실제 DTO projection, 오류 분류, 안전 URL, single-flight·stale 방어 GREEN |
| E2: 입력·카드 UI | 같은 폴더 `index.html`, `styles.css`, `app.js`(신규 예정) | 600×600, focus, Enter/Escape, preset, composer fallback, 네 상태 |
| E3: Meta shell·로컬 증명 | 같은 폴더 `manifest.webmanifest`, `favicon.png`(신규 예정) | 공식 meta/manifest/icon, local Spring entry 및 실제 sync/session 흐름 |
| E4: Simulator | 공식 Meta Chrome 확장 | 확장 실제 사용 기록, 네비게이션·가독성·오류 상태; custom viewport와 구분 |
| E5: HTTPS/하드웨어 | 현재 patch 범위 밖 | 접근제어·공개범위와 실제 계정/기기 조건이 충족될 때 별도 실행 |

`/assets/display/index.html`을 사용한다. 임의 `/display/` route 추가나 보안 permitAll 확장은 하지 않는다. 첫 앱은 질문을 바꾸지 않고 원래 답변을 client에서 페이지로 나눈다. `learningContext/pipelineSnapshot/filePath`는 Display에 노출하지 않는다.

## 실행 명령과 검증

아래는 **Desktop에서 수행할 proposed 명령**이다. source owner gate가 허용하기 전에는 소스를 수정하지 않는다. 현재 Notebook에서 Java 부재/lock을 이유로 진단값을 조작하거나 잠금을 삭제하지 않는다.

```powershell
# 현재 Desktop 프로젝트 루트에서; 먼저 Java17와 기존 gate를 확인
java -version
git --no-optional-locks rev-parse --show-toplevel
git --no-optional-locks branch --show-current
git --no-optional-locks worktree list
git --no-optional-locks status --short
$displayIndex = git --no-optional-locks rev-parse --git-path index
Test-Path -LiteralPath ($displayIndex + '.lock')

# source gate가 허용한 RED/GREEN. 첫 명령은 새 fixture를 만든 뒤 실행
node .\scripts\meta_display_webapp_contract_tests.cjs
node .\scripts\chat_ui_stream_contract_tests.js
node .\scripts\chat_ui_browser_fault_fixture_tests.js

# Desktop 전용 기존 host-local cache/output lane을 사용
$env:GRADLE_USER_HOME = Join-Path $env:LOCALAPPDATA 'AbandonWareX/gradle-user-home/desktop-display'
$displayProjectCache = Join-Path $env:LOCALAPPDATA 'AbandonWareX/gradle-project-cache/desktop-display'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-display'
.\gradlew.bat --no-daemon --project-cache-dir $displayProjectCache checkLangchain4jVersionPurity checkSourceSetHygiene
.\gradlew.bat --no-daemon --project-cache-dir $displayProjectCache chatUiTest --tests "com.example.lms.config.ChatUiViewConfigFocusedTest" --tests "com.example.lms.web.ChatFrontendStreamCancellationFocusedTest"
```

Notebook에서 진단 명령을 재사용할 때는 `Set-Location Y:\`와 Notebook cache/host ID를 쓴다. Git top-level이 backing path로 출력되지 않도록 capture/redact한다. 위 Desktop 명령 블록은 Desktop-local 실행용이다.

각 진단 timeout60초, Node 검사120초, Gradle 단계15분 상한을 두고 exit/count/reason을 기록한다. boot/smoke는 기존 검증된 시작 명령을 확인한 뒤 해당 세션이 시작한 프로세스만 관리한다. 기존 서버를 재시작하거나 다른 포트를 점유한 프로세스를 종료하지 않는다.

새 client fixture 필수 사례: actual content/evidence DTO, 빈 출처, HTML·위험 URL(내부 host·userinfo·정규화 IP 우회 포함), 403/409/413/422/429/503 및 비JSON 오류, same-origin cookie, logical-submit Idempotency-Key, 연속 Enter에서 요청1회, timeout 뒤 Enter에서도 재요청0회, 뒤늦은 응답 무시, 입력/IME 중 카드 이동 억제. 첫 버전은 같은 작업의 재전송 UI를 제공하지 않고 outcome unknown을 유지한다. 기존 key를 바꿔 409/422 fence를 우회하지 않는다. 실제 API test는 최소 body와 응답 ID만 사용하며 사용자 원문/secret은 출력하지 않는다.

로컬 UI 결과에서 `clientRoundTripMs/sourceCount/httpStatus/requestId`만 측정한다. 서버 stage timing이 없으면 `ragSearchMs/llmTotalMs`는 evidence_needed다. HTTP200·modelUsed·ragUsed·UI만으로 provider lineage PASS를 주지 않는다.

## 완료 보고

`artifactReady`, `clientTests`, `localSync`, `sessionContinuity`, `officialSimulator`, `publicHttps`, `hardware`, `runtimeLineageVerdict`, `desktopFinalProof`를 분리한다. 제공된 과거 검증은 `reviewed-evidence/ performedNow=false`, 직접 실행한 검사만 `executed`다. E0–E4 완료가 E5 또는 provider lineage 완료를 뜻하지 않는다.

현재 인계는 작업을 전달할 수 있도록 준비한 상태다. 파일 발견은 Desktop 세션 시작·스킬 사용·소스 변경·검증 증명이 아니다. 실제 해당 세션의 결과가 오기 전까지 각각 evidence_needed로 유지한다.
