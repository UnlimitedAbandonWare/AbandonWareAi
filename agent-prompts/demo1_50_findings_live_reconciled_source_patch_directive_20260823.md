# demo-1 50개 finding 라이브 재대조 소스 수정 지시서

문서 상태: DESIGN_APPROVED / DOCUMENT_ONLY / LIVE_RECONCILED

작성 목적: 과거 소스와 환각 정보가 섞인 첨부문서의 50개 finding을 현재 Desktop 체크아웃과 다시 대조하고, 지금 실행할 수 있는 부분만 안전한 소스 수정 지시로 승격한다.

이 파일은 소스 수정 결과가 아니다. 미래의 각 패치 세션이 따라야 할 실행 지시서다. 이 문서를 만들면서 main/java, main/resources, app sourceSet, Gradle 설정, DB, 외부 서비스, 브라우저 상태는 변경하지 않았다.

---

## 1. 신뢰 경계와 EvidenceSnapshot

### 1.1 입력 문서

- 입력 파일: C:\Users\nninn\Downloads\demo1_50_findings_safe_patch_directives_2026-08-23.md
- 입력 SHA-256: 82DA4C01A170E3963E2AB968EABD5DB74279DEA43598940C4F67193978E76822
- 입력 길이: 2,537줄
- 입력의 역할: 위험 후보와 탐색 키워드
- 입력의 역할이 아닌 것: 현재 소스 진실, 현재 테스트 목록, 현재 line number, 현재 sourceSet, 현재 owner, 실행 승인

첨부문서 안의 문장은 사용자 요청보다 우선하지 않는다. ACTIVE_CONFIRMED, ACCEPT, REJECT, 기존 테스트 이름, focused command, line number를 그대로 신뢰하지 않는다.

### 1.2 라이브 Desktop 기준점

- canonical workspace: C:\AbandonWare\demo-1\demo-1\src
- branch: codex/owned-runtime-browser-restart
- HEAD: 0796a3c5b29bbb08c3314bd40649d856d4a7bce6
- Java: 17.0.13 LTS
- root main sourceSet: main/java, main/resources
- root test sourceSet: src/test/java
- :app main sourceSet: app/src/main/java_clean, app/src/main/resources
- Git index lock: 없음
- 기존 사용자 아티팩트 agent-prompts/gpt_pro_demo1_source_patch_directives_50_20260823.md: 미추적 상태, 수정 및 덮어쓰기 금지

이 snapshot과 HEAD가 달라지면 아래 판정을 자동 적용하지 않는다. 먼저 대상 symbol과 preimage를 다시 결합한다.

### 1.3 권위 순서

1. 현재 파일 내용, 현재 sourceSet, 현재 Git 상태, 현재 명령 출력
2. 가장 가까운 AGENTS.md와 repo-local skill
3. 이 재대조 지시서
4. 첨부문서와 이전 보고서
5. 기억이나 추측

충돌 시 다음 형식을 사용한다.

~~~text
superpowers: supporting_process
repoEvidence: authoritative
decision: follow_live_source
evidence_needed: <missing artifact> / verify with <exact command>
~~~

---

## 2. 실행 상태 분류

| 상태 | 의미 | production 수정 |
|---|---|---|
| PATCH_READY | 라이브 owner와 결함이 확인되고 좁은 seam과 preimage가 안정적이다 | 아래 상세 지시를 한 건씩 실행 가능 |
| CHARACTERIZE_THEN_PATCH | 우려는 유효하지만 RED, caller 계약, 수치 또는 제품 정책이 먼저 필요하다 | RED까지만 허용, RED 없이 production 수정 금지 |
| VALID_BUT_PREIMAGE_CHANGED | finding은 유효하지만 대상 파일에 현재 사용자 변경이 있다 | 현재 수정 금지, 변경 owner와 preimage 안정화 후 재대조 |
| OWNER_OR_PRODUCT_DECISION_NEEDED | 기능 활성, 보존, 삭제, 외부 ABI 또는 사용자 문구의 정답을 소스만으로 확정할 수 없다 | 결정 증거 전 production 수정 금지 |
| ALREADY_SATISFIED | 첨부의 문제 설명이 현재 소스에는 해당하지 않는다 | no-op과 회귀 확인만 |
| REJECT_STALE_PREMISE | sourceSet, packaging, owner 또는 테스트 전제가 라이브 증거와 모순된다 | 원 지시 폐기 |
| DEFER_STRUCTURAL | 구조 부채는 있으나 독립 대규모 refactor가 실제 결함보다 위험하다 | 실제 결함 seam 안에서 필요한 최소 추출만 |

---

## 3. 50개 finding 전체 추적표

| # | 라이브 판정 | 현재 근거와 처리 |
|---:|---|---|
| 01 | PATCH_READY | SettingsController.getAllSettings가 repository.findAll 결과를 공개한다. 공개 GET 자체는 라이브 보안 계약이므로 유지하고 응답을 명시적 공개 키 projection으로 제한한다. |
| 02 | VALID_BUT_PREIMAGE_CHANGED | 최초 익명 요청의 response cookie와 같은 요청의 resolver 결과가 다를 수 있다. OwnerKeyBootstrapFilter, ClientOwnerKeyResolver, ChatHistoryServiceImpl이 현재 수정 중이다. |
| 03 | VALID_BUT_PREIMAGE_CHANGED | UUID cookie, ipua digest, history salt digest가 서로 다르다. #02와 같은 단일 owner-key 계약으로만 후속 수정한다. |
| 04 | VALID_BUT_PREIMAGE_CHANGED | AttachmentController가 문자열 sessionId만 받아 실제 ChatSession owner를 확인하지 않는다. canonical ChatSessionAccessGuard가 현재 수정 중이므로 새 중복 guard를 만들지 않는다. |
| 05 | OWNER_OR_PRODUCT_DECISION_NEEDED | sessionless upload는 upload-before-session 의도일 수 있다. UI 생성, attachToSession, 삭제 순서를 Computer 또는 브라우저로 증명하기 전 계약을 바꾸지 않는다. |
| 06 | VALID_BUT_PREIMAGE_CHANGED | AttachmentService.delete가 실제 저장 파일을 삭제하지 않는다. AttachmentService에 큰 사용자 변경이 있어 현재 patch 금지다. |
| 07 | VALID_BUT_PREIMAGE_CHANGED | attachment repo, sessionIndex, extracted text, digest map에 수명 상한이 없다. #06과 같은 lifecycle owner에서만 재설계한다. |
| 08 | PATCH_READY | WebMvcConfig는 lms.upload-public-prefix를 사용하지만 LocalFileStorageService는 /uploads/를 고정 반환한다. 기존 property를 공유하도록 수정한다. |
| 09 | CHARACTERIZE_THEN_PATCH | 확장자 allowlist만 확인한다. 파일별 magic, parser, text 형식 정책과 기존 의존성을 먼저 고정한다. |
| 10 | VALID_BUT_PREIMAGE_CHANGED | N8nWebhookController가 readAllBytes로 body를 전부 읽는다. 파일이 현재 수정 중이다. |
| 11 | VALID_BUT_PREIMAGE_CHANGED | Idempotency-Key를 받지만 dedup에 사용하지 않는다. #10과 같은 ingress patch로만 처리한다. |
| 12 | OWNER_OR_PRODUCT_DECISION_NEEDED | enqueue 뒤 실제 dispatcher 연결이 보이지 않아 PENDING이 남지만 payload schema와 실행 owner가 없다. N8nWebhookController와 JobService 변경도 진행 중이다. |
| 13 | VALID_BUT_PREIMAGE_CHANGED | InMemoryJobService status map이 unbounded다. 대상과 JobConfig가 각각 수정 또는 미추적 상태다. |
| 14 | VALID_BUT_PREIMAGE_CHANGED | cached thread pool과 명시 shutdown, timeout 계약이 부족하다. #13과 같은 lifecycle patch로만 처리한다. |
| 15 | OWNER_OR_PRODUCT_DECISION_NEEDED | compute 성공과 callback 전달 성공을 나누는 상태 모델과 N8nNotifier caller 연결을 먼저 증명해야 한다. |
| 16 | OWNER_OR_PRODUCT_DECISION_NEEDED | LocationController는 비활성 주석 상태이고 ChatApiController 소비 경로는 존재한다. location package에 삭제, 수정, 미추적 변경이 동시에 있다. |
| 17 | OWNER_OR_PRODUCT_DECISION_NEEDED | consent-off가 좌표, 주소 cache, 파생 데이터 중 무엇을 삭제해야 하는지 제품 정책이 없다. #16 결정 전 수정 금지다. |
| 18 | VALID_BUT_PREIMAGE_CHANGED | ChatWorkflow의 사용자 rescue 문구에 mojibake가 남아 있으나 파일이 현재 수정 중이다. |
| 19 | VALID_BUT_PREIMAGE_CHANGED | no-evidence sentinel 문자열이 mojibake이며 여러 지점에 반복된다. ChatWorkflow preimage 안정화 후 typed reason seam으로 처리한다. |
| 20 | CHARACTERIZE_THEN_PATCH | SmartFallbackService sentinel과 ChatWorkflow sentinel이 다르다. 공유 failure reason의 소비자를 먼저 특성화한다. |
| 21 | OWNER_OR_PRODUCT_DECISION_NEEDED | SmartFallback LLM prompt의 원문 의미를 추측 복원하지 않는다. 승인된 prompt fixture가 필요하다. |
| 22 | OWNER_OR_PRODUCT_DECISION_NEEDED | SmartFallback 사용자 template의 정확 문구 근거가 필요하다. 현재 PromptBuilder boundary는 유지한다. |
| 23 | ALREADY_SATISFIED | QueryKeywordPromptBuilder의 seed와 follow-up 검색 prompt는 현재 정상 한국어다. 첨부의 mojibake 주장은 stale이다. |
| 24 | VALID_BUT_PREIMAGE_CHANGED | ChatApiController SSE 상태문 일부가 mojibake다. 파일이 현재 수정 중이며 StatusSignal code와 표시문을 분리한 뒤 Computer UI 증거가 필요하다. |
| 25 | VALID_BUT_PREIMAGE_CHANGED | Chat 오류 응답 문구도 같은 dirty controller에 있다. HTTP/SSE machine code와 사용자 문구를 별도 계약으로 고정한다. |
| 26 | VALID_BUT_PREIMAGE_CHANGED | PageController generic model-setting error branch의 mojibake는 확인되지만 파일이 현재 수정 중이다. |
| 27 | CHARACTERIZE_THEN_PATCH | Nova retriever는 absolute deadline을 사용하지만 cancel(false)와 provider I/O 수명 분리가 남는다. 실제 provider timeout API를 먼저 증명한다. |
| 28 | VALID_BUT_PREIMAGE_CHANGED | HybridWebSearchEmptyFallbackAspect가 현재 수정 중이다. timeout, interrupt, cancellation 지시를 덮지 않는다. |
| 29 | VALID_BUT_PREIMAGE_CHANGED | canonical HybridRetriever에 큰 in-progress bounded-executor/deadline patch가 있다. 별도 D01 patch를 금지한다. |
| 30 | CHARACTERIZE_THEN_PATCH | Nova와 aspect의 일부 polling path가 Thread.interrupted로 flag를 지운다. clean Nova seam에서 RED를 먼저 만들고 dirty aspect는 건드리지 않는다. |
| 31 | VALID_BUT_PREIMAGE_CHANGED | RagGraphExecutor가 executor 없는 supplyAsync와 cancel(false)를 사용한다. 파일 상태가 modified이므로 현재 patch 금지다. |
| 32 | VALID_BUT_PREIMAGE_CHANGED | FederatedEmbeddingStore가 future마다 전체 timeout을 순차 적용한다. 파일에 현재 interrupt 관련 사용자 변경이 있다. |
| 33 | VALID_BUT_PREIMAGE_CHANGED | backing store 호출의 실제 취소는 cancel(false)만으로 보장되지 않는다. #32와 분리하지 않는다. |
| 34 | PATCH_READY | GraphRagThumbnailBridge가 common pool runAsync를 사용한다. 기존 bounded applicationTaskExecutor를 재사용하는 한 파일 seam이 있다. |
| 35 | VALID_BUT_PREIMAGE_CHANGED | BrainStateChatWorkflowAspect도 common pool을 사용하지만 현재 memory decision 관련 사용자 변경이 있다. |
| 36 | VALID_BUT_PREIMAGE_CHANGED | null session을 고정 __TRANSIENT__ key로 capture한다. #35 preimage와 transient 제품 요구가 안정되기 전 수정 금지다. |
| 37 | VALID_BUT_PREIMAGE_CHANGED | DebugEventsDiagnosticsController는 cached pool, SseEmitter(0L), 연결별 loop를 사용한다. 파일이 modified 상태다. |
| 38 | CHARACTERIZE_THEN_PATCH | SearchExecutorConfig.searchIoExecutor의 CallerRunsPolicy는 외부 I/O를 caller thread에서 실행할 수 있다. rejection caller RED를 먼저 만든다. |
| 39 | PATCH_READY | RemoteEmbedder가 field HttpClient를 만들고도 embed마다 새 client를 사용한다. field client 재사용으로 좁게 수정한다. |
| 40 | PATCH_READY | RemoteEmbedder가 InterruptedException을 generic Exception으로 삼킨다. 별도 catch와 re-interrupt를 추가하되 기존 fallback 출력 계약은 caller 증거 없이 바꾸지 않는다. |
| 41 | OWNER_OR_PRODUCT_DECISION_NEEDED | OnnxRuntimeService는 현재 대규모 수정 중이며 실제 model I/O contract와 지원 선언이 없다. 신규 dependency 추가 금지다. |
| 42 | PATCH_READY | QueryExpander cache key가 raw 질문을 보존하고 cache가 unbounded이며 expired entry를 제거하지 않는다. 한 클래스 내부 bounded TTL cache로 수정한다. |
| 43 | VALID_BUT_PREIMAGE_CHANGED | EmbeddingCache는 이미 TTL lookup, expired recompute, single-flight를 수행한다. 남은 gap은 크기 상한과 sweep뿐이며 파일에 현재 interrupt patch가 있다. |
| 44 | PATCH_READY | SoakMetricRegistry.resetForSid는 entry 교체만 하고 SoakQuickRunner는 snapshot 후 제거하지 않는다. 종료 finally에서 제거한다. |
| 45 | CHARACTERIZE_THEN_PATCH | PolicyBasedModelRouter requestedCache는 unbounded다. 공식 model 허용 계약, max entries, loser 또는 eviction client close 가능성을 먼저 증명한다. |
| 46 | PATCH_READY | com.abandonware.ai.agent.integrations.RrfFusion은 parse 성공 뒤 finite, K, weight domain을 검증하지 않는다. 기본 공식을 유지하며 입력만 방어한다. |
| 47 | OWNER_OR_PRODUCT_DECISION_NEEDED | app/src/main/java_clean/service/rag/fusion/WeightedPowerMean은 active sourceSet에 있으나 직접, 반사, 외부 ABI owner가 불명이다. owner proof 전 production 수정 금지다. |
| 48 | REJECT_STALE_PREMISE | app/src/main/java_clean은 :app의 실제 main sourceSet이다. build는 선택된 duplicate FQCN만 제외하며 일부 duplicate가 JAR에 남을 수 있다고 보고한다. 전체를 shadow source로 간주한 원 지시를 폐기한다. |
| 49 | DEFER_STRUCTURAL | 거대 class 분해 자체를 목표로 하지 않는다. 실제 결함 patch에서 테스트 불가능한 seam 하나만 package-private collaborator로 추출할 수 있다. |
| 50 | CHARACTERIZE_THEN_PATCH | broad catch 110개 일괄 변경은 금지한다. 활성 owner, 사용자 영향, failure injection이 있는 catch 한 개만 후속 후보로 선택한다. |

---

## 4. 모든 미래 소스 패치의 공통 게이트

한 세션에서 아래 상세 directive 하나만 선택한다. 서로 다른 directive를 한 patch로 묶지 않는다.

### 4.1 시작 전 read-only preflight

~~~powershell
$root = "C:\AbandonWare\demo-1\demo-1\src"
Set-Location -LiteralPath $root

java -version
git branch --show-current
git rev-parse HEAD
git worktree list
git status --short -- <DECLARED_TARGETS>
Test-Path -LiteralPath ".git\index.lock"
Get-FileHash -LiteralPath <EACH_TARGET> -Algorithm SHA256
.\__patch_drop__\janitor_inventory.ps1
~~~

필수 판정:

- Java major가 17이 아니면 해당 patch HOLD.
- target FQCN이 root 또는 :app의 실제 sourceSet에 없으면 HOLD.
- target hash가 이 문서의 clean preimage와 다르면 자동 patch 금지. 새 diff를 읽고 directive를 재결합한다.
- 같은 hunk의 사용자 변경, source-edit lease, index lock, top-level PatchDrop patch가 있으면 해당 lane만 HOLD.
- dirty tree 전체를 정리하거나 reset하지 않는다.

### 4.2 application source edit gate

실제 main/java 또는 app source를 바꾸기 직전에 repo-local demo1-source-edit-three-way-preflight를 사용한다.

- 하나의 redacted EvidenceSnapshot을 고정한다.
- 정확히 POSITIVE_QUERY, NEGATIVE_QUERY, NEUTRAL_QUERY를 실행한다.
- NEUTRAL의 APPLY가 A-B와 B-A 순서에서 안정적일 때만 source owner guard로 진입한다.
- HOLD 또는 REJECT이면 production edit 금지.
- 이 문서를 만든 것 자체는 위 source-edit gate를 통과했다는 뜻이 아니다.

RRF처럼 subsystem 알고리즘 body를 수정할 때는 demo1-subsystem-patch-directive도 적용한다. 두 개 이상의 S01-S08 subsystem을 건드리면 별도 세션으로 재설계하고 demo1-cross-subsystem-guard를 사용한다.

### 4.3 RED-first 테스트 명칭 규칙

- 기존 test가 있으면 실제 파일과 package 선언을 확인한 FQCN만 사용한다.
- 없는 test는 명령에 먼저 넣지 않는다. directive에 ADD_RED_TEST라고 표시하고 파일 생성 뒤 Test-Path와 package/class 선언을 확인한다.
- source를 먼저 바꾸고 나중에 test를 맞추지 않는다.
- broad wildcard GREEN은 focused RED/GREEN을 대체하지 않는다.

### 4.4 공통 빌드 격리

~~~powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop-live-directive"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop-live-directive"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop-live-directive"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
~~~

각 directive의 focused test가 GREEN인 뒤 영향 범위에 맞게 다음을 실행한다.

~~~powershell
.\gradlew.bat checkSourceSetHygiene --no-daemon --project-cache-dir $pcd --stacktrace
.\gradlew.bat checkLangchain4jVersionPurity --no-daemon --project-cache-dir $pcd --stacktrace
.\gradlew.bat compileJava --no-daemon --project-cache-dir $pcd --stacktrace
~~~

:app 파일 또는 root와 :app 경계를 건드린 경우에만 추가한다.

~~~powershell
.\gradlew.bat :app:classes --no-daemon --project-cache-dir $pcd --stacktrace
~~~

LangChain4j dependency는 모두 1.0.1이어야 한다. 기존 property 이름과 openssl 또는 opnessl 키를 바꾸지 않는다.

---

## 5. PATCH_READY 상세 지시

## LIVE-01 공개 settings projection 제한

### 적용 finding

#01

### 현재 preimage

- main/java/com/example/lms/api/SettingsController.java
- SHA-256: 9BAE2A0C4882BAD4F062ABCFD8AAE24F5915A7A7A843A08916BF383C7E542BCE
- Git snapshot: clean

### 라이브 owner proof

- AppSecurityConfig는 GET /api/settings와 하위 GET을 public으로 유지하고 POST는 ADMIN으로 제한한다.
- ChatRecoverySafePatchContractTest는 이 순서를 명시적으로 고정한다.
- SettingsController.getAllSettings는 ConfigurationSettingRepository.findAll 결과를 모두 map으로 반환한다.
- PromptService는 같은 repository의 SYSTEM_PROMPT를 내부 prompt로 사용한다.
- SettingsControllerSecretMaskAspect는 key 또는 value 휴리스틱 masking이며 공개 항목 allowlist가 아니다.
- 현재 chat.js는 /api/settings POST를 호출하지만 GET 소비자는 source scan에서 확인되지 않았다.

### RED

ADD_RED_TEST:

- src/test/java/com/example/lms/api/SettingsControllerPublicProjectionTest.java
- 예정 FQCN: com.example.lms.api.SettingsControllerPublicProjectionTest

필수 RED:

1. repository에 SYSTEM_PROMPT, API key 형태가 아닌 내부 설정, 임의의 새 key를 넣어도 공개 응답에 없어야 한다.
2. allowlist에 없는 key는 이름이나 값이 무해해 보여도 반환하지 않아야 한다.
3. 공개 key가 현재 source-backed consumer로 증명되지 않으면 응답은 HTTP 200과 빈 map을 유지해야 한다.
4. POST null payload와 POST ADMIN 보안 계약은 변하지 않아야 한다.

### 최소 patch

1. SettingsController 안에 명시적 immutable PUBLIC_SETTING_KEYS를 둔다.
2. 공개 key가 source-backed consumer로 증명되지 않은 현재 snapshot에서는 빈 allowlist가 기본이다.
3. findAll을 호출하지 말고 PUBLIC_SETTING_KEYS에 해당하는 row만 조회한다.
4. unknown DB row는 default deny한다.
5. 응답 형식 Map<String,String>과 HTTP 200은 유지한다.
6. 공개 key를 추가하려면 같은 patch에 실제 consumer와 positive contract test가 있어야 한다.

### 금지

- 현재 수정 중인 AppSecurityConfig를 건드리지 않는다.
- public GET을 ADMIN으로 바꾸지 않는다.
- SettingsControllerSecretMaskAspect를 제거하지 않는다. projection 뒤 defense-in-depth로 유지한다.
- SYSTEM_PROMPT, provider key, token, secret, password, model credential을 공개 key로 넣지 않는다.
- key blacklist로 대체하지 않는다.

### focused verify

새 RED test 파일 생성 뒤에만:

~~~powershell
.\gradlew.bat test --tests "com.example.lms.api.SettingsControllerPublicProjectionTest" --tests "com.example.lms.api.SettingsControllerNullPayloadTest" --tests "ai.abandonware.nova.orch.aop.SettingsControllerSecretMaskAspectTest" --tests "com.example.lms.safepatch.ChatRecoverySafePatchContractTest" --no-daemon --project-cache-dir $pcd --stacktrace
~~~

### rollback

- public GET 보안 순서가 바뀜
- POST ADMIN 계약이 약화됨
- unknown key가 응답에 등장함
- response body에 raw internal setting이 노출됨

---

## LIVE-02 업로드 public prefix 통일

### 적용 finding

#08

### 현재 preimage

- main/java/com/example/lms/storage/LocalFileStorageService.java
- SHA-256: 01D20B5F20DC93020E39E6CAA788A7AA85557E58FAC21FF745E1468296BA414A
- Git snapshot: clean

### 라이브 owner proof

- WebMvcConfig는 lms.upload-public-prefix를 normalize해 resource handler path로 사용한다.
- LocalFileStorageService.save는 같은 파일을 저장한 뒤 /uploads/와 relative path를 고정 결합한다.
- application.properties는 lms.upload-public-prefix=/uploads/ 기본을 이미 선언한다.

### RED

ADD_RED_TEST:

- src/test/java/com/example/lms/storage/UploadPublicPrefixContractTest.java
- 예정 FQCN: com.example.lms.storage.UploadPublicPrefixContractTest

필수 RED:

1. prefix가 /files/이면 반환 URL은 /files/chat/... 이어야 한다.
2. files, /files, /files/ 입력은 동일한 /files/ 결과로 normalize되어야 한다.
3. 기본값은 기존 /uploads/를 보존해야 한다.
4. 저장 대상 physical root와 path traversal 방어는 변하지 않아야 한다.

### 최소 patch

1. LocalFileStorageService가 기존 lms.upload-public-prefix property를 읽는다.
2. 반환 직전에 leading slash 하나와 trailing slash 하나를 보장한다.
3. normalized prefix와 root-relative forward-slash path를 결합한다.
4. WebMvcConfig의 현재 normalization과 같은 입력 fixture를 테스트로 공유한다.
5. 현재 dirty WebMvcConfig의 사용자 hunk는 수정하지 않는다.

### 금지

- 새 property 이름 추가
- WebMvcConfig 사용자 변경 덮어쓰기
- upload directory, extension allowlist, max byte, 저장 filename 변경
- #06 파일 삭제 또는 #09 content sniffing을 같은 patch에 포함
- 별도 storage wrapper 또는 두 번째 route 추가

### focused verify

~~~powershell
.\gradlew.bat test --tests "com.example.lms.storage.UploadPublicPrefixContractTest" --no-daemon --project-cache-dir $pcd --stacktrace
~~~

### rollback

- default /uploads/가 바뀜
- URL에 double slash 또는 missing slash가 생김
- target file이 upload root 밖에 저장됨
- WebMvc handler와 반환 URL fixture가 다름

---

## LIVE-03 thumbnail graph ingest를 기존 bounded executor로 이동

### 적용 finding

#34

### 현재 preimage

- main/java/com/example/lms/service/rag/graph/GraphRagThumbnailBridge.java
- SHA-256: AF643B6564BBB02E8276096D7692119BC759B9210B244B93838033D2B81E275A
- Git snapshot: clean

### 라이브 owner proof

- captureThumbnail은 CompletableFuture.runAsync만 호출해 common pool을 사용한다.
- AsyncExecutorConfig는 applicationTaskExecutor alias를 가진 bounded ThreadPoolTaskExecutor를 이미 제공한다.
- ingestNow는 예외를 fail-soft 처리하고 content 대신 failure class와 caption hash만 남긴다.
- 기존 test: com.example.lms.service.rag.graph.GraphRagThumbnailBridgeTest

### RED

기존 GraphRagThumbnailBridgeTest에 다음을 추가한다.

1. direct test executor를 주입하면 ingest task가 그 executor를 통해 실행된다.
2. rejecting executor에서는 captureThumbnail이 호출자에게 예외를 전파하지 않는다.
3. rejection 시 chunkingService가 호출되지 않고 redacted reason/count만 남는다.
4. null 또는 blank graphText no-op은 유지된다.

### 최소 patch

1. GraphRagThumbnailBridge constructor에 applicationTaskExecutor를 명시 qualifier로 주입한다.
2. CompletableFuture.runAsync를 제거하고 주입된 Executor.execute로 submit한다.
3. submission rejection만 좁게 잡아 thumbnail ingest를 skip하고 primary event publisher를 계속 진행한다.
4. accepted, rejected, completed count를 TraceStore에 count-only로 남긴다.
5. ingestNow 내부의 기존 fail-soft와 relation generation은 바꾸지 않는다.

### 금지

- 새 executor bean 또는 thread pool 생성
- BrainStateChatWorkflowAspect까지 함께 수정
- event payload, graphText, caption 원문, session 원문 로그
- rejection에서 caller thread 동기 ingest
- primary thumbnail persistence를 실패시키기

### focused verify

~~~powershell
.\gradlew.bat test --tests "com.example.lms.service.rag.graph.GraphRagThumbnailBridgeTest" --no-daemon --project-cache-dir $pcd --stacktrace
~~~

### rollback

- rejection이 primary event flow로 전파됨
- ingest가 caller thread에서 동기 실행됨
- common pool 사용이 남음
- payload가 trace 또는 log에 노출됨

---

## LIVE-04 RemoteEmbedder client 재사용과 interrupt 보존

### 적용 finding

#39, #40

### 현재 preimage

- main/java/com/abandonware/ai/agent/integrations/RemoteEmbedder.java
- SHA-256: 7FD70A30D1220FF6F64D13EEA743DDA0A04933387B6BC09C82FB4B37B425E95C
- Git snapshot: clean

### 라이브 owner proof

- field client는 HttpClient.newHttpClient로 한 번 생성된다.
- embed는 field를 쓰지 않고 매 호출 HttpClient.newHttpClient().send를 실행한다.
- InterruptedException은 generic Exception catch에 들어가 flag가 복구되지 않는다.
- sibling TokenEmbedder는 interrupt를 복구한 뒤 fallback을 유지한다.
- TavilyWebSearchRetriever는 interrupt를 복구하고 empty fail-soft를 반환한다.
- 기존 test: com.abandonware.ai.agent.integrations.RemoteEmbedderSecretSafetyTest

### RED

ADD_RED_TEST:

- src/test/java/com/abandonware/ai/agent/integrations/RemoteEmbedderInterruptTest.java
- 예정 FQCN: com.abandonware.ai.agent.integrations.RemoteEmbedderInterruptTest

필수 RED:

1. 주입된 test HttpClient의 send가 반복 호출 모두 처리하고 새 client factory가 embed 안에서 호출되지 않아야 한다.
2. send가 InterruptedException을 던진 뒤 embed 반환 시 current thread interrupt flag가 true여야 한다.
3. interrupt trace는 reason 또는 stage와 errorType만 포함하고 text, endpoint, key, body를 포함하지 않아야 한다.
4. HTTP non-2xx, invalid vector, IOException의 기존 heuristic fallback은 유지되어야 한다.

### 최소 patch

1. embed 안의 HttpClient.newHttpClient를 field client.send로 교체한다.
2. test를 위해 package-private constructor 또는 factory seam으로 HttpClient를 주입할 수 있게 하되 public default constructor와 env/property 계약을 유지한다.
3. InterruptedException catch를 generic catch보다 앞에 둔다.
4. catch에서 Thread.currentThread().interrupt를 호출하고 reason=interrupted를 redacted trace로 남긴다.
5. caller 결과 계약은 별도 증거 없이 바꾸지 않는다. 현재 patch에서는 sibling 계약과 맞춰 기존 heuristic fallback을 유지한다.
6. IOException, JSON parse, invalid response 경로는 기존 behavior를 보존한다.

### 금지

- endpoint, header, env 이름, model, timeout 변경
- 신규 HTTP dependency
- key 또는 request/response body 로그
- interrupt flag clear
- caller proof 없이 CancellationException 또는 zero-length vector로 출력 계약 변경

### focused verify

~~~powershell
.\gradlew.bat test --tests "com.abandonware.ai.agent.integrations.RemoteEmbedderInterruptTest" --tests "com.abandonware.ai.agent.integrations.RemoteEmbedderSecretSafetyTest" --no-daemon --project-cache-dir $pcd --stacktrace
~~~

### rollback

- 매 embed마다 새 client 생성
- interrupt flag가 false
- non-interrupt fallback 회귀
- raw text, URL, key, body 노출

---

## LIVE-05 QueryExpander raw-key 제거와 bounded TTL

### 적용 finding

#42

### 현재 preimage

- main/java/com/example/lms/search/QueryExpander.java
- SHA-256: 67768345ABB8808DF90DD5927619C3E1C86446AD55EC0EF087574A49838DA056
- Git snapshot: clean

### 라이브 owner proof

- cache key는 original + delimiter + snippets.hashCode이며 raw 질문을 heap key로 보존한다.
- ConcurrentHashMap에 max entries가 없다.
- expired entry는 hit로 사용하지 않지만 map에서 제거하지 않는다.
- LLM, sanitizer, BM25 expansion 결과 계약은 이 finding과 무관하다.

### RED

ADD_RED_TEST:

- src/test/java/com/example/lms/search/QueryExpanderCacheTest.java
- 예정 FQCN: com.example.lms.search.QueryExpanderCacheTest

필수 RED:

1. 같은 normalized 질문과 snippet은 model call 한 번으로 cache hit한다.
2. 다른 질문은 다른 key가 된다.
3. cache 내부 key 어디에도 raw 질문이나 snippet이 남지 않는다.
4. deterministic clock으로 TTL boundary 전에는 hit, 이후에는 miss와 expired removal이 된다.
5. max entries보다 많은 unique input 뒤 size가 max 이하여야 한다.
6. concurrent same key에서 중복 LLM call이 폭증하지 않아야 한다.
7. cache 실패는 uncached expansion 또는 기존 fail-soft 결과로 진행한다.

### 최소 patch

1. normalized question과 안정적으로 순서화한 snippet 입력을 delimiter와 length를 포함해 SHA-256 full digest로 만든다.
2. digest는 cache 내부 key로만 사용하고 원문을 log 또는 trace로 옮기지 않는다.
3. class 내부에 작은 bounded access-order TTL cache를 둔다. 신규 cache dependency는 추가하지 않는다.
4. max entries는 positive bounded 값이며 default를 코드 또는 기존 property injection seam으로 제공한다.
5. now source는 test에서 제어 가능한 Clock 또는 LongSupplier로 만든다.
6. get에서 expired entry를 제거하고 put 전에 expired sweep와 eldest eviction을 수행한다.
7. LLM call, sanitizer, BM25 계산은 cache lock 밖에서 수행한다.
8. 반환 List는 기존 순서와 중복 제거 semantics를 유지한다.

### 금지

- raw 질문 또는 snippet을 다른 key, metric, trace로 이동
- SafeRedactor의 12자리 표시용 hash만 cache identity로 사용
- 신규 Caffeine, Redis 또는 distributed cache dependency
- LLM prompt, Self-Check, version expansion 변경
- unbounded cleanup thread

### focused verify

~~~powershell
.\gradlew.bat test --tests "com.example.lms.search.QueryExpanderCacheTest" --no-daemon --project-cache-dir $pcd --stacktrace
~~~

### rollback

- raw query가 cache key에 남음
- same input hit가 깨짐
- size가 max를 넘어서 계속 증가
- model call이 cache lock 안에서 실행
- output ordering 또는 sanitizer 계약 변화

---

## LIVE-06 Soak SID registry lifecycle 종료

### 적용 finding

#44

### 현재 preimage

- main/java/com/example/lms/service/soak/metrics/SoakMetricRegistry.java
- SHA-256: 6464B5268F00D0479C72597ED66F1169405EBB6D7F57BC5E50EBFA4FFEE339FB
- main/java/com/example/lms/service/soak/runner/SoakQuickRunner.java
- SHA-256: FCE8394D68DCFA7D9A85185ADB19EC4DE5E6F8A99F34BD765FB1B73808A0A5E4
- Git snapshot: 둘 다 clean

### 라이브 owner proof

- resetForSid는 bySid.put으로 entry를 교체한다.
- snapshot은 counters를 읽지만 entry를 제거하지 않는다.
- SoakQuickRunner는 providerSid마다 reset, run, snapshot, report copy를 수행한 뒤 제거하지 않는다.
- 기존 tests:
  - com.example.lms.service.soak.metrics.SoakMetricRegistryTraceTest
  - com.example.lms.service.soak.runner.SoakQuickRunnerTest

### RED

ADD_RED_TEST:

- src/test/java/com/example/lms/service/soak/metrics/SoakMetricRegistryRetentionTest.java
- 예정 FQCN: com.example.lms.service.soak.metrics.SoakMetricRegistryRetentionTest

필수 RED:

1. N provider run을 완료한 뒤 registry entry count가 0이어야 한다.
2. provider run이 예외를 던져도 해당 SID가 finally에서 제거되어야 한다.
3. snapshot을 report에 복사한 뒤 제거해도 report 값은 보존되어야 한다.
4. concurrent increment, snapshot, remove에서 예외가 없어야 한다.
5. shared _nosid bucket 정책은 별도이며 provider SID cleanup이 이를 지우지 않아야 한다.

### 최소 patch

1. SoakMetricRegistry에 removeForSid를 추가하고 null 또는 blank input을 안전 no-op 처리한다.
2. 필요하면 count-only package-private 관찰 seam을 test에서만 사용하되 raw SID 출력은 금지한다.
3. SoakQuickRunner는 provider metrics snapshot을 report 객체에 복사한 뒤 finally에서 removeForSid를 호출한다.
4. normal, provider exception, report mapping exception 경로 모두 cleanup을 통과시킨다.
5. process-local registry이므로 lifecycle owner가 확정된 provider SID에 별도 scheduler 또는 TTL infrastructure를 추가하지 않는다.

### 금지

- SID 원문 log 또는 trace
- 실행 중 entry 조기 제거
- reset 의미 또는 counter 계산 변경
- background cleanup thread
- FaithfulnessMetricSnapshotStore의 독립 정책 변경

### focused verify

~~~powershell
.\gradlew.bat test --tests "com.example.lms.service.soak.metrics.SoakMetricRegistryRetentionTest" --tests "com.example.lms.service.soak.metrics.SoakMetricRegistryTraceTest" --tests "com.example.lms.service.soak.runner.SoakQuickRunnerTest" --no-daemon --project-cache-dir $pcd --stacktrace
~~~

### rollback

- report metric이 0 또는 누락
- provider 실행 중 entry가 사라짐
- exception 경로에서 entry가 남음
- raw SID 노출

---

## LIVE-07 optional agent RRF 수치 입력 검증

### 적용 finding

#46

### 현재 preimage

- main/java/com/abandonware/ai/agent/integrations/RrfFusion.java
- SHA-256: D53EBA113F1DA7CE420450733666F9BC883280A0E0E44E6094F8046DD3CAD109
- Git snapshot: clean

### 라이브 owner proof

- com.abandonware.ai.agent.integrations.HybridRetriever가 optional RRF path에서 이 class를 호출한다.
- 별도 canonical com.example.lms.service.rag.fusion.RrfFusion도 존재하므로 서로 혼동하면 안 된다.
- parseEnvDouble은 NumberFormatException만 fallback하고 NaN, Infinity, 음수, K=0을 허용한다.
- 기존 test: com.abandonware.ai.agent.integrations.RrfFusionTraceTest

### RED

ADD_RED_TEST:

- src/test/java/com/abandonware/ai/agent/integrations/RrfFusionValidationTest.java
- 예정 FQCN: com.abandonware.ai.agent.integrations.RrfFusionValidationTest

필수 RED:

1. K=0, negative, NaN, positive 또는 negative Infinity를 거부한다.
2. local 또는 web weight의 NaN, Infinity, negative를 거부한다.
3. 두 weight 합이 0이면 default tuple로 회귀한다.
4. invalid tuple 뒤 모든 rrfScore가 finite여야 한다.
5. 정상 default와 정상 custom fixture의 순위는 기존 공식과 같아야 한다.
6. tie ordering은 반복 실행에서 deterministic이어야 한다.

### 최소 patch

1. RRF_K, RRF_W_LOCAL, RRF_W_WEB 세 값을 읽은 뒤 하나의 config tuple로 검증한다.
2. K는 finite이며 0보다 커야 한다.
3. weight는 finite이며 0 이상이고 합은 0보다 커야 한다.
4. 어느 하나라도 invalid이면 tuple 전체를 기존 default 60, 1, 1로 원자 회귀한다.
5. comparator 전에 score가 finite인지 방어하고 invalid result는 안전 default 계산으로 대체한다.
6. reason은 invalid_k, non_finite_weight, negative_weight, zero_weight_sum 중 하나와 count만 남긴다.
7. raw env 값은 log 또는 trace에 남기지 않는다.

### 금지

- com.example.lms.service.rag.fusion.RrfFusion 수정
- 정상 RRF 공식, default, dedupe key, output map schema 변경
- property 또는 env 이름 변경
- 근거 없는 K 또는 weight 상한 추가
- HybridRetriever의 현재 다른 구현과 병합

### focused verify

~~~powershell
.\gradlew.bat test --tests "com.abandonware.ai.agent.integrations.RrfFusionValidationTest" --tests "com.abandonware.ai.agent.integrations.RrfFusionTraceTest" --no-daemon --project-cache-dir $pcd --stacktrace
~~~

### 추가 gate

이 patch는 fusion 알고리즘 body를 건드리므로 source-edit three-way preflight 뒤 demo1-subsystem-patch-directive를 적용한다.

### rollback

- 정상 ranking fixture 변화
- non-finite score 잔존
- raw env 값 노출
- canonical RRF까지 변경

---

## 6. CHARACTERIZE_THEN_PATCH 패킷

이 절에서는 test와 증거까지만 만든다. 아래 exit 조건을 만족하기 전 production source 수정은 금지한다.

### CHAR-01 업로드 content type 검증

- finding: #09
- current owner: LocalFileStorageService
- ADD_RED_TEST 예정: com.example.lms.storage.UploadTypeValidationTest
- RED: jpg 이름의 script, pdf 이름의 invalid header, oversized bounded sniff, valid txt/json/png/pdf fixture
- 먼저 확인할 것: 기존 parser dependency, 각 format의 canonical signature, text format의 허용 encoding과 max sniff bytes
- production exit gate: 신규 dependency 없이 지원 format별 검증 정책을 명시할 수 있음
- 금지: content-type header만 신뢰, 전체 파일 readAllBytes, 원문 log

### CHAR-02 Nova timeout과 caller interrupt

- findings: #27, #30의 clean Nova seam만
- owner: ai.abandonware.nova.orch.adapters.NovaAnalyzeWebSearchRetriever
- 기존 test: ai.abandonware.nova.orch.adapters.NovaAnalyzeWebSearchRetrieverTimeoutTraceTest
- 추가 RED: interrupt 후 flag true, total elapsed bound, timeout 뒤 merge 차단, unfinished task count
- exit gate: provider client의 connect, read, request timeout API와 cancel semantics 확인
- dirty HybridWebSearchEmptyFallbackAspect와 HybridRetriever는 수정 금지

### CHAR-03 searchIoExecutor saturation

- finding: #38
- owner: SearchExecutorConfig.searchIoExecutor
- ADD_RED_TEST 예정: ai.abandonware.nova.orch.SearchExecutorConfigSaturationTest
- RED: pool과 queue 포화 때 provider body가 request thread 이름으로 실행되는지 확인
- 다음 RED: CallerRunsPolicy를 AbortPolicy로 바꾸었을 때 모든 실제 caller가 RejectedExecutionException을 typed fail-soft 처리하는지 확인
- exit gate: 모든 caller가 rejection을 empty와 구분한 reason=rejected로 처리
- 금지: queue 무한 확대, caller thread I/O, provider별 새 pool

### CHAR-04 requested model cache

- finding: #45
- owner: PolicyBasedModelRouter.requestedCache
- 기존 test: com.example.lms.service.routing.PolicyBasedModelRouterRedactionContractTest
- ADD_RED_TEST 예정: com.example.lms.service.routing.PolicyBasedModelRouterCacheTest
- RED: unique requested model 또는 options가 max를 넘어 증가, same key concurrent duplicate build, losing putIfAbsent client
- evidence_needed: 공식 허용 model 집합, max entries, ChatModel close 가능성, in-flight eviction 안전성
- exit gate: bounded cardinality와 close 또는 GC semantics를 source-backed test로 설명 가능
- 금지: 임의 model allow 확대, in-use client close, raw model request log

### CHAR-05 active broad catch 한 개

- finding: #50
- 먼저 한 파일, 한 method, 한 catch를 고른다.
- RED: failure injection 뒤 현재 breadcrumb가 0이고 사용자 fail-soft 결과는 유지됨
- patch 후보는 같은 실제 finding을 숨기는 catch만 허용
- trace: stage, enum reason, errorType, count, short hash
- 금지: 110개 일괄 변경, raw exception message, stacktrace, query, session, key

---

## 7. VALID_BUT_PREIMAGE_CHANGED 재개 큐

아래 finding은 폐기하지 않는다. 다만 현재 사용자 변경을 보존하기 위해 production patch를 시작하지 않는다.

| 큐 | finding | 현재 충돌 증거 | preimage 안정 후 첫 행동 |
|---|---|---|---|
| Q01 | #02-#03 | OwnerKeyBootstrapFilter, ClientOwnerKeyResolver, ChatHistoryServiceImpl modified | filter request attribute와 resolver precedence RED를 새로 만들고 기존 row 자동 이관은 별도 결정 |
| Q02 | #04 | ChatSessionAccessGuard modified, 관련 test untracked | guard를 재사용할 수 있는 visibility와 attachment controller 403/404 계약을 먼저 고정 |
| Q03 | #06-#07 | AttachmentService modified, digest와 interaction evidence hunk 진행 중 | storage handle 기반 disk delete와 map lifecycle을 같은 owner에서 재설계 |
| Q04 | #10-#14 | N8nWebhookController와 InMemoryJobService modified, JobConfig untracked | bounded ingress, idempotency, dispatcher, job lifecycle을 분리해 첫 owner를 확정 |
| Q05 | #16-#17 | location 파일 D/M/?? 혼재, ChatApiController modified | 기능 RESTORE 또는 REMOVE 제품 결정과 consent deletion semantics 확보 |
| Q06 | #18-#19 | ChatWorkflow modified | typed no-evidence reason과 기존 정상 한국어 fixture를 먼저 고정 |
| Q07 | #24-#25 | ChatApiController modified | machine code와 표시문 분리 후 Computer로 stream, error, cancel, reload 확인 |
| Q08 | #26 | PageController modified | PageControllerModelPolicyTest를 기반으로 exact generic message fixture 확보 |
| Q09 | #28-#29 | fallback aspect와 canonical HybridRetriever modified | 현재 in-progress deadline patch의 focused GREEN과 final diff를 먼저 확인 |
| Q10 | #31 | RagGraphExecutor modified | common pool과 cancel(false) 특성화 후 기존 managed executor 재사용 여부 결정 |
| Q11 | #32-#33 | FederatedEmbeddingStore modified | 현재 interrupt hunk를 보존하고 absolute deadline RED를 새 preimage에 결합 |
| Q12 | #35-#36 | BrainStateChatWorkflowAspect modified | memorySaveAllowed hunk와 transient skip 정책을 함께 재대조 |
| Q13 | #37 | DebugEventsDiagnosticsController modified | 유한 idle timeout, connection cap, shared scheduler를 현재 diff 위에서 다시 설계 |
| Q14 | #41 | OnnxRuntimeService 대규모 modified | 실제 artifact, model I/O shape, supported 선언, existing dependency 증명 |
| Q15 | #43 | EmbeddingCache modified | existing TTL, single-flight, stale-on-failure를 보존한 bounded size만 RED |

재개 시 변경된 전체 파일을 과거 HEAD로 되돌리지 않는다. current worktree와 HEAD 양쪽 diff를 읽고 사용자 hunk 위에 최소 patch를 재설계한다.

---

## 8. no-op, reject, defer

### #23 ALREADY_SATISFIED

- QueryKeywordPromptBuilder의 현재 seed와 follow-up prompt는 정상 한국어다.
- production change 없음.
- 후속 regression은 기존 QueryKeywordPromptBuilderTest 또는 실제 test inventory를 사용한다.
- 첨부가 제안한 QueryKeywordPromptBuilderUtf8Test는 현재 존재하지 않는다.

### #47 OWNER_OR_PRODUCT_DECISION_NEEDED

- app/src/main/java_clean/service/rag/fusion/WeightedPowerMean.java는 active sourceSet 안에 있다.
- owner가 없다는 이유로 바로 삭제하거나 수치 공식을 고치지 않는다.
- direct call, import, reflection, ServiceLoader, external ABI, :app JAR listing을 먼저 증명한다.
- owner가 없으면 별도 cleanup 승인을 요청한다.
- owner가 있으면 p=0, negative base, non-finite, empty input의 제품 수학 계약을 먼저 만든다.

### #48 REJECT_STALE_PREMISE

- app/src/main/java_clean 전체는 shadow가 아니다.
- app/build.gradle.kts는 :app main sourceSet을 java_clean으로 선언한다.
- duplicate exclusion은 선택적이고 build logic은 일부 duplicate가 JAR에 계속 포함될 수 있음을 보고한다.
- 원문 F01의 generated excludes=10, hardExcluded=2, kept=0 고정 기대를 사용하지 않는다.
- production Java cleanup 전 실제 :app:jar listing과 specific FQCN owner를 다시 증명한다.

### #49 DEFER_STRUCTURAL

- ChatWorkflow 줄 수 감소를 성공 지표로 삼지 않는다.
- public API, bean wiring, prompt boundary, persistence contract를 보존한다.
- 실제 patch에서 test 불가능한 책임 하나가 확인될 때만 collaborator 하나를 추출한다.

---

## 9. 첨부문서의 존재하지 않는 focused test 33개

2026-08-23 snapshot에서 아래 exact FQCN에 대응하는 src/test/java 파일은 모두 없었다. 이 목록의 이름을 기존 GREEN 증거처럼 실행하지 않는다.

1. ai.abandonware.nova.orch.SearchProviderLifetimePolicyTest
2. com.abandonware.ai.agent.integrations.RemoteEmbedderInterruptTest
3. com.abandonware.ai.agent.integrations.RrfFusionValidationTest
4. com.example.lms.api.AttachmentOwnershipTest
5. com.example.lms.api.ChatApiUtf8ContractTest
6. com.example.lms.api.DebugEventsDiagnosticsControllerSseTest
7. com.example.lms.api.N8nWebhookExecutionTest
8. com.example.lms.api.N8nWebhookIngressTest
9. com.example.lms.api.SettingsControllerSecurityTest
10. com.example.lms.integrations.n8n.N8nDeliveryStateTest
11. com.example.lms.jobs.InMemoryJobServiceLifecycleTest
12. com.example.lms.location.LocationConsentLifecycleTest
13. com.example.lms.observability.ActiveBroadCatchBreadcrumbTest
14. com.example.lms.prompt.QueryKeywordPromptBuilderUtf8Test
15. com.example.lms.search.QueryExpanderCacheTest
16. com.example.lms.service.AttachmentLifecycleTest
17. com.example.lms.service.ChatWorkflowContractCharacterizationTest
18. com.example.lms.service.ChatWorkflowNoEvidenceContractTest
19. com.example.lms.service.embedding.EmbeddingCacheBoundedTest
20. com.example.lms.service.fallback.SmartFallbackServiceContractTest
21. com.example.lms.service.onnx.OnnxRuntimeServiceContractTest
22. com.example.lms.service.rag.graph.GraphIngestExecutorTest
23. com.example.lms.service.rag.graph.TransientGraphSessionIsolationTest
24. com.example.lms.service.rag.langgraph.RagGraphExecutorDeadlineTest
25. com.example.lms.service.routing.PolicyBasedModelRouterCacheTest
26. com.example.lms.service.soak.metrics.SoakMetricRegistryRetentionTest
27. com.example.lms.storage.UploadPublicPrefixContractTest
28. com.example.lms.storage.UploadTypeValidationTest
29. com.example.lms.vector.FederatedEmbeddingStoreDeadlineTest
30. com.example.lms.web.AnonymousOwnerKeyContinuityTest
31. com.example.lms.web.PageControllerModelSettingsErrorTest
32. service.rag.fusion.ShadowSourcePackagingTest
33. service.rag.fusion.WeightedPowerMeanOwnerProofTest

이 지시서가 일부 같은 이름을 ADD_RED_TEST로 다시 제안하는 경우 의미는 명확하다.

- 현재 존재한다는 뜻이 아니다.
- source patch보다 먼저 새 RED test로 작성하라는 뜻이다.
- 작성 후 Test-Path, package 선언, class 선언을 확인한 뒤에만 Gradle --tests에 넣는다.

---

## 10. Computer, Browser, Supabase, 외부 provider 레인

### Computer

- 이 문서 생성에는 GUI가 없어 NOT_REQUIRED_FOR_ARTIFACT_GENERATION이다.
- #05, #24-#25처럼 실제 UI 순서 또는 렌더링 계약이 필요한 후속 patch에서만 사용한다.
- 증명할 항목: upload-before-session 순서, visible SSE code와 text, stream/cancel/reload, responsive geometry, error presentation.
- 화면 렌더링만으로 server semantic success 또는 provider wire success를 주장하지 않는다.

### Browser

- localhost UI 회귀가 필요한 directive에서만 사용한다.
- carried-forward screenshot 또는 이전 JAR의 browser proof를 새 version proof로 사용하지 않는다.

### Supabase 또는 DB

- 이 50개 재대조 문서에는 DB mutation 권한이 없다.
- reachable endpoint, 403, 연결 가능성만으로 project scope나 schema owner를 주장하지 않는다.
- location 또는 persistence patch에 DB 증거가 필요하면 project-scoped read-only evidence를 먼저 확보한다.

### 외부 provider

- optional credential이 missing, blank, dummy, test, changeme, sk-local 또는 unresolved placeholder이면 provider disabled와 disabledReason을 반환하고 outbound call을 하지 않는다.
- HTTP 200, response capture, delivery ACK는 provider semantic success를 증명하지 않는다.
- wire attempt가 관찰되지 않으면 not_observed로 보고한다.

---

## 11. patch 완료 보고 형식

~~~text
DIRECTIVE_ID:
FINDING_IDS:
BRANCH:
HEAD_BEFORE:
TARGET_PREIMAGE_SHA256:
SOURCESET_PROOF:
PREFLIGHT_POSITIVE:
PREFLIGHT_NEGATIVE:
PREFLIGHT_NEUTRAL:
NEUTRAL_ORDER_STABLE:
RED_TEST_PATH:
RED_COMMAND:
RED_RESULT:
PATCH_FILES:
USER_HUNKS_PRESERVED:
FAIL_SOFT_REASON_CODES:
REDACTION_COUNTS:
FOCUSED_TEST_COMMAND:
FOCUSED_TEST_RESULT:
IMPACT_TEST_RESULT:
SOURCESET_HYGIENE_RESULT:
LANGCHAIN4J_PURITY_RESULT:
COMPILE_RESULT:
APP_CLASSES_RESULT:
COMPUTER_OR_BROWSER_RESULT:
PROVIDER_WIRE_ATTEMPT:
ROLLBACK_CHECK:
EVIDENCE_NEEDED_REMAINING:
FINAL: APPLY | HOLD | ROLLBACK
~~~

필수 해석:

- test command가 실행됐다는 사실과 test가 존재한다는 사실을 구분한다.
- delivery와 semantics를 구분한다.
- artifact 또는 prompt GREEN과 runtime GREEN을 구분한다.
- source preimage가 바뀌면 이전 APPLY를 재사용하지 않는다.
- 이 문서의 생성 완료를 50개 runtime patch 완료로 보고하지 않는다.

---

## 12. 이 문서 자체의 비목표

- 50개 finding의 production Java 일괄 수정
- 사용자 dirty tree 정리
- 기존 untracked directive 덮어쓰기
- sourceSet 이동, duplicate class 삭제 또는 병합
- dependency 추가
- DB, Supabase, ACL, credential, environment 영구 변경
- bootRun, localhost UI, 외부 provider 호출
- commit, push, deploy

이 지시서의 완료 조건은 50개 finding 모두가 라이브 판정에 연결되고, 실행 가능한 directive는 실제 owner, preimage, RED, 금지사항, verify, rollback을 가지며, stale 또는 충돌 항목은 명시적으로 격리되는 것이다.
