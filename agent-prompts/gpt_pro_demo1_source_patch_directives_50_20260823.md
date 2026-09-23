# demo-1 50개 후보 소스수정 지시서 묶음 — 2026-08-23

> 이 문서는 구현 결과가 아니라 독립 실행 가능한 Safe Patch 지시서다. 한 번의
> 실제 소스수정 세션에서는 아래 `DIRECTIVE_ID` 하나만 선택한다. 실제 Java 또는
> resource 쓰기 전에는 반드시 `$demo1-source-edit-three-way-preflight`의 동일
> EvidenceSnapshot 기반 `POSITIVE_QUERY`, `NEGATIVE_QUERY`, `NEUTRAL_QUERY`를
> 정확히 한 번씩 실행하고, 양 순서에서 안정적인 `APPLY`를 얻은 뒤 기존
> owner/lease/preimage/PatchDrop 가드에 진입한다.

## 0. 산출물 계약

```yaml
contractVersion: demo1.source-patch-directives-50.v1
canonicalExecutionRoot: C:\AbandonWare\demo-1\demo-1\src
canonicalDirectivePath: agent-prompts/gpt_pro_demo1_source_patch_directives_50_20260823.md
generatedForBranch: codex/owned-runtime-browser-restart
generatedForHead: 0796a3c5b29bbb08c3314bd40649d856d4a7bce6
sourceMutationPerformed: false
directiveGenerationVerdict: APPLY
applicationSourceMutationVerdict: HOLD_UNTIL_PER_DIRECTIVE_PREFLIGHT
desktopFinalProof: evidence_needed
sourceOwner: desktop
finalVerificationOwner: desktop
browserEvidenceAuthority: supporting_only
computerEvidenceAuthority: supporting_only
superpowersAuthority: supporting_process
repoEvidenceAuthority: authoritative
repositoryWideHold: false
```

SUPER_TITLE: Owner-Scoped 50-Finding Safe Patch Directives

SUPER_TOKEN: `SUPER::audit50-owner-scoped::twenty-one-patch-units::eleven-verify-or-hold::desktop-final-proof`

decomposition_decision: `N-way`; 21개 소스 패치 단위와 5개 비수정 증거
게이트. 항목 수가 아니라 동일 원인, 동일 활성 소유자, 독립 RED/GREEN 가능성을
기준으로 분리했다.

goal_table:

| Field | Value |
| --- | --- |
| `target_metric` | 50개 후보 100% 판정, 선택된 각 패치의 독립 RED/GREEN, 원문·비밀 비노출, Desktop 최종 검증 |
| `observed_current` | 활성 root `main/java` 2,075개, root test 1,003개, `app/src/main/java_clean` 17개; top-level PatchDrop patch 0; active lease 0; index lock 없음 |
| `gap` | 36개 source-backed 수정 후보, 11개 선행 증거 필요, 2개 별도 권한/결함 세마 필요, 1개 runtime 수정 기각 |
| `suspected_causal_chain` | 활성 경로의 권한·수명·UTF-8·bounded-I/O·cache 수치 계약 누락 → 사용자/운영 실패 → 소유자별 최소 패치 필요 |
| `active_surface` | 각 `DIRECTIVE_ID`의 `TARGET_FILES_AND_METHODS` |
| `active_sourceSet` | root `main/java`, `main/resources`, `src/test/java`; `:app`는 `app/src/main/java_clean`, `app/src/main/resources` |
| `proof_command` | 각 지시서의 focused RED/GREEN 후 공통 broad ladder |

candidate_scoreboard:

| Cluster | Evidence | causal_strength | patch_size | blast_radius | verification_cost | success_probability |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 인증·owner·첨부 | 현재 controller/filter/service 직접 확인 | 5 | 2 | 3 | 2 | H |
| n8n·job 수명 | 현재 enqueue/status/executor 직접 확인 | 5 | 3 | 3 | 3 | H |
| UTF-8 런타임 문자열 | 활성 mojibake와 비활성 정상 참고본 동시 확인 | 5 | 2 | 2 | 2 | H |
| provider/vector/graph bounded-I/O | `cancel(false)`, common pool, 순차 timeout 직접 확인 | 5 | 4 | 4 | 4 | M |
| cache·수치 계약 | 활성 map/cache/env parse 직접 확인 | 4 | 2 | 2 | 2 | H |
| shadow/구조/broad catch | package/report 증거는 있으나 독립 결함 세마 부족 | 2 | 5 | 5 | 4 | L |

selected_branch: `demo1-long-think-goal-composer` +
`demo1-superpowers-repo-evidence-guard`; Superpowers는 계획 형식만 지원하고
현재 저장소 증거와 가드를 덮어쓰지 않는다.

patch_intent: 아래 `ACCEPT` 항목만 대응 `DIRECTIVE_ID`에서 수정한다.
`VERIFY_FIRST`, `DEFER`, `REJECT` 항목은 같은 세션에 끼워 넣지 않는다.

proof_command: 각 지시서의 `FOCUSED_VERIFY_COMMANDS`를 먼저 실행하고, GREEN 뒤
`BROAD_VERIFY_COMMANDS`를 기재 순서대로 실행한다.

evidence_needed: runtime/browser/provider 결과는 이 문서 생성만으로 관찰되지 않았다.
각 지시서의 별도 증거 항목을 충족해야 한다.

## 1. 생성 시점의 신선한 경계 증거

- canonical root: `C:\AbandonWare\demo-1\demo-1\src`
- branch/HEAD: `codex/owned-runtime-browser-restart` /
  `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- Java: 17.0.13
- dirty entry count: 26,951; 이 문서 대상 경로는 생성 전 존재하지 않았다.
- `.git/index.lock`: 없음
- PatchDrop top-level `.patch`: 0
- source-edit lease: active 0, corrupt 0, expired 0
- `source_scan`: root main 2,075 Java, root resources 113, tests 1,003,
  app java_clean 17, app resources 69, secretPatternHits 0
- 첨부된 harmony/broad-catch/test-tree JSON은 전달된 SHA-256과 현재 파일
  SHA-256이 일치했다. 이 사실은 byte identity만 증명하며 runtime 성공을
  증명하지 않는다.
- 첨부에서 제시한 Gradle 성공 로그는 공급된 증거다. 이 문서 작성 세션이 같은
  Gradle 명령을 다시 실행한 것으로 보고하지 않는다.

## 2. 모든 실제 패치가 상속하는 불변 경계

1. Java 17, 현재 Spring Boot 버전, 모든 `dev.langchain4j` 1.0.1을 유지한다.
2. 최종 RAG prompt는 `PromptBuilder.build(PromptContext)`에서만 조립한다.
3. 신규 production dependency, property/secret 이름 변경, 광범위 포맷 정리,
   비가역 삭제, duplicate owner/route/helper/framework를 금지한다.
4. `project/src/main/java`, `app/src/main/java`, root `java`, archive, build output은
   활성 소유자로 승격하지 않는다. root `java`의 정상 UTF-8 문자열은 복원
   후보를 확인하는 supporting evidence일 뿐 구현 복사 원본이 아니다.
5. raw prompt, response, query, body, key, cookie, session ID, callback URL,
   환경값을 로그·TraceStore·보고서에 남기지 않는다. reason/count/hash/length만
   허용한다.
6. commit, push, deploy, DB/credential/ACL 변경은 이 지시서가 허가하지 않는다.
7. `@브라우저`: UI/SSE 표시문 또는 사용자 흐름이 변경된 지시서에서만 localhost
   visible proof를 수집한다. 모델 답변 또는 provider wire 성공으로 과장하지 않는다.
8. `@컴퓨터`: shell/file API로 불가능한 Windows UI 증거가 있을 때만 사용한다.
   현재 지시서 생성과 일반 Java patch에는 필요하지 않다.
9. 소스 수정 직전 exact target별 SHA-256과 기존 dirty hunk 소유권을 다시 확인한다.
   target preimage가 바뀌거나 overlapping writer가 있으면 해당 지시서만 HOLD한다.

### 2.1 공통 Desktop preflight

```powershell
Set-Location 'C:\AbandonWare\demo-1\demo-1\src'
Get-Location
git rev-parse --show-toplevel
git branch --show-current
git worktree list
git status --short
if (Test-Path -LiteralPath '.git\index.lock') { throw 'index-lock-conflict' }
powershell -NoProfile -ExecutionPolicy Bypass -File '.\__patch_drop__\janitor_inventory.ps1'
'{"nodeRole":"desktop","root":".","requestId":"source-directive-execution","sessionId":"source-directive"}' |
  python '.\scripts\awx_mcp_toolbox.py' --input-json - source_scan
java -version
```

그 다음 선택 지시서의 RED를 재현하고, 동일 target preimage에 대해
`$demo1-source-edit-three-way-preflight`를 실행한다. `canonicalQueryCount=3`, 같은
scenario ID 집합, 양 packet order `APPLY`, goalScore 50 이상이 아니면 HOLD한다.

안정적인 APPLY 뒤에만 lease를 연다. 아래에서 `a1-settings-exposure`는 선택한
지시서의 고정 slug로 바꿔 실행한다.

```powershell
$OwnerId = 'desktop-source-directive-session'
powershell -NoProfile -ExecutionPolicy Bypass -File '.\__patch_drop__\source_edit_session.ps1' `
  -Action begin -Role desktop -Root . -Topic 'a1-settings-exposure' -OwnerId $OwnerId
```

`apply_patch` 직전에 target hash, target-scoped `git diff`, index lock, PatchDrop,
lease를 한 번 더 확인한다. 성공 또는 검증된 rollback 후 `finally`에서 같은
`Topic`과 `OwnerId`로 `-Action end`를 실행한다.

### 2.2 공통 Gradle 초기화와 broad ladder

각 지시서의 `FOCUSED_VERIFY_COMMANDS` 전에 그 지시서 ID를 소문자 slug로 넣어
초기화한다. 예시는 A1의 실제 값이다.

```powershell
$DirectiveId = 'a1-settings-exposure'
$env:AWX_AGENT_HOST = 'desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = "desktop-$DirectiveId"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop-$DirectiveId"
$ProjectCache = "$env:USERPROFILE\.awx-gradle-project-cache\desktop-$DirectiveId"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$ProjectCache | Out-Null
```

각 지시서에서 focused GREEN 및 기재한 영향 경계 테스트가 끝난 뒤 다음을 정확히
순서대로 실행한다.

```powershell
.\gradlew.bat checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat checkLangchain4jVersionPurity --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $ProjectCache
git diff --check
```

모든 `BROAD_VERIFY_COMMANDS`는 이 exact ladder를 뜻한다. focused/영향 경계가
RED이면 broad ladder로 실패를 숨기지 않는다.

---

## 3. 실행 지시서

### DIRECTIVE A1 — 설정 테이블 접근 경계

DIRECTIVE_ID: `AWX-AUDIT50-A1-SETTINGS-ACCESS`

SELECTED_FINDINGS: `#01`

VERDICT_PER_FINDING: `#01 ACCEPT` — 현재 `GET /api/settings/**`가 permitAll이고
`SettingsController.getAllSettings()`가 repository `findAll()` 전체 key/value를
반환한다.

ACTIVE_OWNER_PROOF: `main/java/com/example/lms/config/AppSecurityConfig.java`의
`filterChain`; `main/java/com/example/lms/api/SettingsController.java`의
`getAllSettings`; root `main/java` 활성.

ROOT_CAUSE: read와 write에 서로 다른 보안 정책을 적용하면서 read DTO/allowlist도
두지 않았다.

TARGET_FILES_AND_METHODS: 수정 `AppSecurityConfig.filterChain`; 테스트 생성
`src/test/java/com/example/lms/api/SettingsControllerAccessBoundaryTest.java`.

DO_NOT_TOUCH: `ConfigurationSetting` schema, `PromptService`, 설정 key/value,
admin credential/property, 공개 settings DTO 신규 설계.

RED_OR_CHARACTERIZATION_TEST: anonymous GET은 401/403, ADMIN GET은 200,
응답 또는 anonymous body에 `SYSTEM_PROMPT`가 없음을 MockMvc로 먼저 RED로 만든다.

MINIMAL_PATCH_STEPS: GET matcher를 POST와 같은 `hasRole("ADMIN")` 경계로 이동한다.
공개 endpoint가 실제 소비된다는 별도 증거가 나오면 이 패치를 멈추고 allowlist
DTO를 새 지시서로 분리한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: 거부는 표준 401/403이며 설정 key/value를
TraceStore나 error body에 넣지 않는다. `settings.access.denied.count`와
`reason=authentication_required`만 허용한다.

NEGATIVE_AND_CONCURRENCY_TESTS: anonymous, non-admin authenticated, admin,
empty table, `SYSTEM_PROMPT` 포함 table, 동시 GET/POST에서 non-admin read 거부.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.api.SettingsControllerAccessBoundaryTest `
  --tests ai.abandonware.nova.orch.aop.SettingsControllerSecretMaskAspectTest `
  --tests com.example.lms.api.SettingsControllerNullPayloadTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: admin UI가 GET을 수행할 때 인증된 요청까지 401/403이 되거나
기존 admin POST가 깨지면 session-owned hunk만 되돌린다.

EVIDENCE_NEEDED: 공개 GET의 승인된 소비자가 존재하는지 여부. 확인 명령:
`rg -n "/api/settings" main/resources src/test main/java -S`.

COMPLETION_CRITERIA: focused 및 영향 테스트 GREEN, anonymous secret non-exposure,
공통 ladder GREEN, target diff만 존재, secretPatternHits=0.

### DIRECTIVE A2 — 최초 익명 요청 ownerKey 단일 계약

DIRECTIVE_ID: `AWX-AUDIT50-A2-OWNERKEY-FIRST-REQUEST`

SELECTED_FINDINGS: `#02, #03`

VERDICT_PER_FINDING: `#02 ACCEPT`; `#03 ACCEPT`. 필터는 새 UUID를 response cookie에만
기록하고 현재 request에는 전달하지 않는다. resolver의 `ipua:`와 history의
salted-IP candidate, detail/delete의 exact cookie 비교가 서로 다르다.

ACTIVE_OWNER_PROOF: `OwnerKeyBootstrapFilter.doFilter`,
`ClientOwnerKeyResolver.ownerKey`, `ChatHistoryServiceImpl.resolveGuestOwnerKey`와
`getSessionsForUser`, `ChatApiController.sessions/deleteSession/getSession`.

ROOT_CAUSE: 한 요청에서 owner identity를 한 번 생성·전달하는 canonical request
contract가 없고, legacy IP compatibility가 authorization candidate로 섞였다.

TARGET_FILES_AND_METHODS: 수정 `OwnerKeyBootstrapFilter`, `ClientOwnerKeyResolver`,
`ChatHistoryServiceImpl`; 필요한 exact authorization assertion만
`ChatApiController` 테스트로 고정. 테스트 생성
`src/test/java/com/example/lms/web/AnonymousOwnerKeyFirstRequestContractTest.java`.

DO_NOT_TOUCH: 공개 `X-Owner-Key` 신뢰 복원, raw IP/UA 저장, owner cookie 이름,
admin ownership, DB 자동 migration, `OwnerKeyResolver`를 새 active owner로 승격.

RED_OR_CHARACTERIZATION_TEST: cookie 없는 최초 chat request의 filter chain 안에서
resolver가 Set-Cookie와 동일 UUID를 반환해야 하며, 저장된 session을 다음 cookie
요청으로 list/detail/delete할 수 있어야 한다. 현재는 first-request assertion이 RED다.

MINIMAL_PATCH_STEPS: 필터가 선택한 UUID를 고정 request attribute에 먼저 쓰고
resolver가 attribute → validated cookie → compatibility 순으로 읽게 한다. 신규 guest
session은 항상 그 UUID를 저장한다. salted-IP/IPUA legacy row는 목록·인가 자동 후보에서
제거하고 `legacy_owner_reset_required`로만 분류한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: request attribute/cookie가 모두 없을 때 새 raw PII
identity를 만들지 않는다. reason은 `owner_key_bootstrapped`,
`legacy_owner_reset_required`, `owner_mismatch`; 값은 `ownerKeyHash12`/길이만 기록한다.

NEGATIVE_AND_CONCURRENCY_TESTS: malformed cookie rotation, spoofed header 무시,
동일 NAT의 서로 다른 UA/두 동시 최초 요청 격리, legacy IP-only row 비노출,
admin session 불변.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.web.AnonymousOwnerKeyFirstRequestContractTest `
  --tests com.example.lms.web.OwnerKeyBootstrapFilterTest `
  --tests com.example.lms.web.ClientOwnerKeyResolverTest `
  --tests com.example.lms.api.ChatApiControllerStateSecurityTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: 첫 요청과 다음 요청의 owner hash가 다르거나 다른 guest의
session ID/title가 노출되거나 기존 admin access가 변하면 rollback.

EVIDENCE_NEEDED: legacy IP-derived row의 실제 수와 승인된 migration 정책. raw 값을
조회하지 말고 count-only repository query/test fixture로 검증한다.

COMPLETION_CRITERIA: first request → persisted owner → next request의 단일 UUID 계약,
positive/negative/concurrent tests GREEN, 공통 ladder GREEN.

### DIRECTIVE A3 — 첨부 API의 ChatSession 소유권 재사용

DIRECTIVE_ID: `AWX-AUDIT50-A3-ATTACHMENT-SESSION-AUTH`

SELECTED_FINDINGS: `#04`

VERDICT_PER_FINDING: `#04 ACCEPT` — 현재 upload/inspect/delete는 caller 문자열
`sessionId`와 in-memory index만 보고 ChatSession owner를 확인하지 않는다.

ACTIVE_OWNER_PROOF: `AttachmentController.upload/inspect/delete`,
`AttachmentService.saveAll(..., sessionId)/deleteForSession`, 기존
`ChatSessionAccessGuard`.

ROOT_CAUSE: chat session authorization과 attachment session index가 별도 신뢰
경계를 가진다.

TARGET_FILES_AND_METHODS: 수정 `AttachmentController` 및 최소 재사용을 위한
`ChatSessionAccessGuard` package-private decision seam; 테스트 생성
`src/test/java/com/example/lms/api/AttachmentControllerSessionAccessTest.java`.

DO_NOT_TOUCH: attachment bytes, parser, storage path, sessionless 정책, 새로운 auth
framework, raw session ID 로그.

RED_OR_CHARACTERIZATION_TEST: user A session으로 user B가 upload/inspect/delete하면
403, A는 성공, guest ownerKey mismatch는 403, 존재하지 않는 session은 fail-closed.

MINIMAL_PATCH_STEPS: controller 진입 시 numeric session ID를 파싱하고 기존
ChatSessionAccessGuard의 단일 access decision을 재사용한다. authorization 성공 후에만
save/index/delete를 호출한다. invalid/non-numeric ID는 400 reason code로 종료한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: authorization/parse 실패 시 저장·추출·삭제를 전혀
시작하지 않는다. `attachment.auth.denied.count`, `reason=session_mismatch|invalid_session`,
`sessionHash12`만 남긴다.

NEGATIVE_AND_CONCURRENCY_TESTS: 다른 admin/guest, unknown session, malformed ID,
동시 upload와 delete에서 foreign operation 0회, 자기 session 정상.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.api.AttachmentControllerSessionAccessTest `
  --tests com.example.lms.service.AttachmentServiceSessionOwnershipTest `
  --tests com.example.lms.api.AttachmentControllerConversationArchiveTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: 자기 session upload/delete가 거부되거나 unauthorized request가
storage/parser를 한 번이라도 호출하면 rollback.

EVIDENCE_NEEDED: conversation-archive ingest가 같은 session guard를 요구하는지 현재
product contract. 증거가 없으면 해당 endpoint는 이번 target에서 제외한다.

COMPLETION_CRITERIA: foreign side effect 0, own-session behavior GREEN, redacted denial,
공통 ladder GREEN.

### DIRECTIVE A4 — 첨부 삭제와 bounded retention

DIRECTIVE_ID: `AWX-AUDIT50-A4-ATTACHMENT-RETENTION`

SELECTED_FINDINGS: `#06, #07`

VERDICT_PER_FINDING: 둘 다 `ACCEPT`. delete는 map만 지우고 disk file을 남기며,
repo/extracted/digest/session maps에 TTL/상한이 없다.

ACTIVE_OWNER_PROOF: `AttachmentService.delete/cacheExtractedText/sessionIndex`,
`LocalFileStorageService.save`; `AttachmentDto.url`이 저장 상대 URL을 보존한다.

ROOT_CAUSE: metadata와 disk object의 lifecycle owner가 분리됐고 in-memory state에
expiration/size budget이 없다.

TARGET_FILES_AND_METHODS: 수정 `LocalFileStorageService`에 root-contained
`deleteStoredUrl`, `AttachmentService`에 eviction/cleanup; 테스트 생성
`src/test/java/com/example/lms/service/AttachmentLifecycleRetentionTest.java`.

DO_NOT_TOUCH: root directory property 이름, public URL schema, `FileStorageService`
인터페이스, 외부 파일, 비가역 bulk cleanup, 신규 cache dependency.

RED_OR_CHARACTERIZATION_TEST: 임시 root에 저장→delete 후 파일 부재; traversal URL은
거부; deterministic clock으로 TTL 이후 four maps와 disk가 정리; max count+1에서
oldest eligible entry만 eviction.

MINIMAL_PATCH_STEPS: URL을 configured root 내부 상대 경로로만 역해석해 best-effort
delete한다. metadata 제거와 file 결과를 `deleted|missing|io_failed|outside_root`로
분류한다. 기존 의존성으로 access timestamp/created timestamp와 fixed max/TTL을
두고 write/read 시 bounded sweep한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: outside-root는 절대 삭제하지 않는다. disk 실패 시
metadata 상태를 `file_cleanup_pending`으로 남기고 재시도 가능하게 한다.
`attachment.retention.{evicted,fileDeleted,fileMissing,fileDeleteFailed}.count`,
`reason`, `idHash12`만 기록한다.

NEGATIVE_AND_CONCURRENCY_TESTS: `..`, encoded separator, symlink/reparse-like containment,
동시 read/delete/evict, duplicate delete, cache text 50,000 경계, disk failure.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.service.AttachmentLifecycleRetentionTest `
  --tests com.example.lms.service.AttachmentServiceDocumentLimitTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: root 밖 path가 삭제되거나 metadata/file 상태가 모순되거나
동시성 테스트에서 live attachment가 사라지면 rollback.

EVIDENCE_NEEDED: 승인된 TTL/max 기본값. 현재 설정이 없으므로 테스트 가능한 보수적
기본값을 문서화하거나 product owner 결정을 받아야 한다.

COMPLETION_CRITERIA: root containment, bounded maps, disk/metadata 결과 분류,
count-only telemetry, focused/broad GREEN.

### DIRECTIVE A5 — upload public prefix 단일 정규화

DIRECTIVE_ID: `AWX-AUDIT50-A5-UPLOAD-PREFIX`

SELECTED_FINDINGS: `#08`

VERDICT_PER_FINDING: `#08 ACCEPT` — storage 반환 URL은 `/uploads/` 고정이고 handler는
`lms.upload-public-prefix`를 정규화한다.

ACTIVE_OWNER_PROOF: `LocalFileStorageService.save`, `WebMvcConfig.addResourceHandlers`와
`normalizePublicPrefix`.

ROOT_CAUSE: 같은 설정을 handler만 소비하고 URL producer는 별도 literal을 쓴다.

TARGET_FILES_AND_METHODS: 수정 `LocalFileStorageService.save`와 package-private
prefix normalizer의 단일 소유 위치; 테스트 생성
`src/test/java/com/example/lms/storage/UploadPublicPrefixContractTest.java`.

DO_NOT_TOUCH: `lms.upload-dir`, 실제 disk layout, resource handler security,
content validation, 기존 property 이름.

RED_OR_CHARACTERIZATION_TEST: default, `files`, `/files`, `/files/`, blank prefix에서
storage URL과 handler pattern이 정확히 동일해야 한다.

MINIMAL_PATCH_STEPS: 현재 normalizer를 중복 복사하지 말고 작은 package-private
value/helper 한 곳으로 이동해 두 owner가 공유한다. URL은 forward slash만 사용한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: invalid prefix는 `/uploads/`로 fail-soft하고
`upload.prefix.normalized.reason=defaulted|trimmed`, count만 기록한다. disk path는
로그에 남기지 않는다.

NEGATIVE_AND_CONCURRENCY_TESTS: blank, missing slash, duplicate slash, traversal-like
prefix, 여러 동시 save에서 동일 prefix.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.storage.UploadPublicPrefixContractTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: 기본 `/uploads/` URL이 변하거나 handler와 반환 URL이 다시
불일치하거나 disk root가 변하면 rollback.

EVIDENCE_NEEDED: 없음. 실제 UI URL 접근은 Browser supporting proof로 추가 가능하다.

COMPLETION_CRITERIA: prefix matrix GREEN, active resources 불변, 공통 ladder GREEN.

### DIRECTIVE B1 — n8n bounded intake와 idempotency

DIRECTIVE_ID: `AWX-AUDIT50-B1-N8N-INTAKE`

SELECTED_FINDINGS: `#10, #11`

VERDICT_PER_FINDING: 둘 다 `ACCEPT`. body는 `readAllBytes()`로 cap 없이 읽고,
`Idempotency-Key`는 선언만 되어 있다.

ACTIVE_OWNER_PROOF: `N8nWebhookController.accept`, `SignatureVerifier.verify`.

ROOT_CAUSE: public webhook intake가 인증 전 resource budget과 재전송 identity를
소유하지 않는다.

TARGET_FILES_AND_METHODS: 수정 `N8nWebhookController.accept`; 필요하면 controller
인접 package-private bounded idempotency window 하나; 테스트 생성
`src/test/java/com/example/lms/api/N8nWebhookIntakeContractTest.java`.

DO_NOT_TOUCH: signature algorithm/header 이름, raw body/key logging, callback 상태,
job executor, 신규 dependency.

RED_OR_CHARACTERIZATION_TEST: cap-1/cap/cap+1, invalid signature, same key+same body,
same key+different body 409, TTL 만료 후 재요청, max entries 초과 eviction.

MINIMAL_PATCH_STEPS: `n8n.webhook.max-body-bytes`의 bounded default로 `readNBytes(cap+1)`
후 초과를 413으로 거부한다. signature 성공 뒤에만 hash-only key/body pair를 bounded
TTL/LRU window에 등록하고 동일 pair는 기존 jobId를 반환한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: cap 초과·signature 실패·key 충돌은 enqueue 0회.
`api.n8nWebhook.accept.reason=body_too_large|invalid_signature|idempotent_replay|idempotency_conflict`,
`bodyLength`, `keyHash12`, count만 기록한다.

NEGATIVE_AND_CONCURRENCY_TESTS: two-thread same key/body에서 enqueue 1회, same key/different
body, blank key, malformed JSON은 기존 payload contract대로 처리, cap 경계.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.api.N8nWebhookIntakeContractTest `
  --tests com.example.lms.api.N8nWebhookControllerRedactionTest `
  --tests com.example.lms.integrations.n8n.SignatureVerifierTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: raw key/body가 trace에 나타나거나 cap+1이 할당/검증을 계속하거나
동시 replay가 두 job을 만들면 rollback.

EVIDENCE_NEEDED: effective servlet/proxy body limit. 확인은 배포 설정의 count/value만
읽고 `effectiveLimitBytes`로 기록한다.

COMPLETION_CRITERIA: bounded allocation, enqueue cardinality 1, redaction, focused/broad
GREEN.

### DIRECTIVE B2 — n8n PENDING 유령 job 제거

DIRECTIVE_ID: `AWX-AUDIT50-B2-N8N-EXECUTION`

SELECTED_FINDINGS: `#12`

VERDICT_PER_FINDING: `#12 ACCEPT` — webhook은 enqueue만 하고 `executeAsync` 또는 다른
dispatcher를 호출하지 않아 terminal state가 없다.

ACTIVE_OWNER_PROOF: `N8nWebhookController.accept`, `JobService.enqueue/executeAsync`,
`TasksApiController`의 별도 실행 경로.

ROOT_CAUSE: webhook payload에 연결된 실행 owner 없이 202/jobId를 먼저 발급한다.

TARGET_FILES_AND_METHODS: 우선 `N8nWebhookController.accept`; 실제 dispatcher owner가
현재 source에서 증명될 때만 그 기존 adapter. 테스트 생성
`src/test/java/com/example/lms/api/N8nWebhookExecutionContractTest.java`.

DO_NOT_TOUCH: 의미 없는 echo Supplier, fake success, 신규 workflow engine, n8n callback,
payload raw trace.

RED_OR_CHARACTERIZATION_TEST: 202를 반환했다면 bounded 시간 안에 RUNNING 후 terminal
상태가 되어야 한다. 실행 owner가 없으면 job을 만들지 않고 명시적 503
`webhook_execution_disabled`를 반환해야 한다.

MINIMAL_PATCH_STEPS: 현재 repo에서 payload task dispatcher가 증명되지 않으면 fail-closed
503 경로를 구현하고 enqueue를 호출하지 않는다. dispatcher가 증명되면 그 owner에
정확히 한 번 연결하고 terminal transition을 검증한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: disabled 상태는 `reason=no_execution_owner`,
`enqueueCount=0`; 실행 실패는 `FAILED`와 errorType/count만 남긴다.

NEGATIVE_AND_CONCURRENCY_TESTS: missing owner, dispatcher exception, duplicate delivery와
B1 idempotency 결합, shutdown 중 request, terminal polling.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.api.N8nWebhookExecutionContractTest `
  --tests com.example.lms.jobs.InMemoryJobServiceTraceContractTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: 202가 다시 무기한 PENDING이거나 의미 없는 work가 SUCCEEDED로
기록되거나 disabled request가 job을 만들면 rollback.

EVIDENCE_NEEDED: webhook payload를 실제로 실행할 canonical dispatcher와 payload schema.
없으면 503 branch가 최종 결과다.

COMPLETION_CRITERIA: `202 implies terminal` 또는 `no owner implies no job + 503`의 배타적
계약, focused/broad GREEN.

### DIRECTIVE B3 — in-memory job bounded lifecycle

DIRECTIVE_ID: `AWX-AUDIT50-B3-JOB-LIFECYCLE`

SELECTED_FINDINGS: `#13, #14`

VERDICT_PER_FINDING: 둘 다 `ACCEPT`. status map은 영구 보존되고 executor는 unbounded
cached pool이며 destroy/queue/timeout 계약이 없다.

ACTIVE_OWNER_PROOF: `InMemoryJobService.status/exec/executeAsync`, `JobConfig.jobService`.

ROOT_CAUSE: local/dev 설명의 process-local 구현이 조건 없이 production bean이 됐고
resource budget이 없다.

TARGET_FILES_AND_METHODS: 수정 `InMemoryJobService`, `JobConfig`; 테스트 생성
`src/test/java/com/example/lms/jobs/InMemoryJobServiceBoundedLifecycleTest.java`.

DO_NOT_TOUCH: persistent DB job store 신규 구현, callback semantics, job ID raw logging,
신규 dependency.

RED_OR_CHARACTERIZATION_TEST: fixed pool+bounded queue saturation, rejection reason,
work timeout, terminal polling grace 후 eviction, max entries, restart, shutdown 중 submit.

MINIMAL_PATCH_STEPS: `ThreadPoolExecutor` fixed threads/queue/AbortPolicy, terminal timestamp를
가진 bounded status record, deterministic Clock, `@PreDestroy` orderly shutdown을 기존
class에 추가한다. properties는 JobConfig에서 bounded 값으로 주입한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: saturation/timeout은 `FAILED` 또는 명시적 rejected
상태로 terminal 처리한다. `jobs.inMemory.{submitted,rejected,timedOut,evicted}.count`,
queueDepth/activeCount/errorType만 기록한다.

NEGATIVE_AND_CONCURRENCY_TESTS: queue+1, 동일 jobId 동시 status, timeout race,
shutdown-submit, terminal eviction 중 polling grace, worker exception.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.jobs.InMemoryJobServiceBoundedLifecycleTest `
  --tests com.example.lms.jobs.InMemoryJobServiceTraceContractTest `
  --tests com.abandonware.ai.agent.job.InMemoryJobQueueTraceTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: accepted job이 상태 없이 유실되거나 shutdown 후 thread가 남거나
terminal polling grace가 사라지면 rollback.

EVIDENCE_NEEDED: 운영 환경에서 in-memory bean을 허용하는 profile 정책. 별도 persistent
store 선택은 이 지시서 밖이다.

COMPLETION_CRITERIA: pool/queue/status cardinality와 timeout/shutdown이 모두 bounded,
focused/broad GREEN.

### DIRECTIVE C1 — ChatWorkflow rescue/no-evidence UTF-8 계약

DIRECTIVE_ID: `AWX-AUDIT50-C1-CHAT-NO-EVIDENCE-UTF8`

SELECTED_FINDINGS: `#18, #19`

VERDICT_PER_FINDING: 둘 다 `ACCEPT`. 활성 `ChatWorkflow`의 final rescue 문구와
no-evidence sentinel이 mojibake다. root `java` 참고본과 활성 guard 계약은 각각
`검색 결과가 존재하나 답변 생성에 실패했습니다. 다시 시도해 주세요.`와
`정보 없음`을 supporting evidence로 제공한다.

ACTIVE_OWNER_PROOF: `main/java/com/example/lms/service/ChatWorkflow.java`의 rescue
branch와 weak suppression; `InfoFailurePatterns`/`EvidenceAwareGuard.looksWeak`.

ROOT_CAUSE: 사용자 표시문과 내부 no-evidence 상태를 손상된 문자열 동등성으로
겸용한다.

TARGET_FILES_AND_METHODS: 수정 `ChatWorkflow`의 final rescue/no-evidence branch와
기존 guard constant/reason seam만; 테스트 생성
`src/test/java/com/example/lms/service/ChatWorkflowNoEvidenceUtf8ContractTest.java`.

DO_NOT_TOUCH: 13,009줄 구조 리팩터링, retrieval/provider/persistence, root `java`
implementation 복사, PromptBuilder 경계, 다른 mojibake 일괄 치환.

RED_OR_CHARACTERIZATION_TEST: EvidenceComposer와 evidence-list가 모두 실패하면 정확한
UTF-8 rescue 문구와 reason을 반환; no evidence FREE branch는 내부 reason
`NO_EVIDENCE`와 표시문 `정보 없음`을 분리; legacy mojibake 입력도 weak로 인식하고
출력하지 않는다.

MINIMAL_PATCH_STEPS: 활성 guard의 기존 failure-pattern seam을 재사용해 내부 상태를
판정하고, 위 두 exact fixture를 상수화한다. 참고본은 문자열 근거로만 사용하며
logging/구조 코드는 복사하지 않는다.

FAIL_SOFT_AND_REDACTION_CONTRACT: 이중 실패 시 빈 응답/exception 대신 exact rescue
문구를 낸다. `chat.finalFallback.reason=no_evidence|composer_failed`,
`composerFailureCount`, `answerLength`만 기록한다.

NEGATIVE_AND_CONCURRENCY_TESTS: evidence 존재, blank answer, legacy sentinel,
whitespace, 동시 두 request의 reason 격리, raw query 미기록.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.service.ChatWorkflowNoEvidenceUtf8ContractTest `
  --tests com.example.lms.prompt.PromptBuilderBoundaryTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: evidence가 있는데 `정보 없음`으로 축약되거나 mojibake가 사용자
output에 남거나 PromptBuilder boundary가 깨지면 rollback.

EVIDENCE_NEEDED: exact 문구의 UI product approval. supporting reference는
`java/com/example/lms/service/ChatWorkflow.java:2223-2229`; 승인 불일치 시 HOLD.

COMPLETION_CRITERIA: exact UTF-8 fixtures, internal reason/display separation,
focused/broad GREEN; 필요 시 localhost Browser에서 표시문을 확인하되 모델 성공으로
주장하지 않는다.

### DIRECTIVE C2 — SmartFallback UTF-8와 PromptBuilder 보존

DIRECTIVE_ID: `AWX-AUDIT50-C2-SMART-FALLBACK-UTF8`

SELECTED_FINDINGS: `#20, #21, #22`

VERDICT_PER_FINDING: 모두 `ACCEPT`. 활성 service는 mojibake sentinel/system/user/template을
사용하지만 이미 `PromptBuilder.build(PromptContext)`를 호출한다.

ACTIVE_OWNER_PROOF: `SmartFallbackService.maybeSuggest`, `maybeSuggestDetailed`,
`templateFallback`; `SmartFallbackPromptBoundaryTest`.

ROOT_CAUSE: 과거 UTF-8 텍스트 손상 뒤 의미 계약 테스트가 PromptBuilder wiring만
검사하고 실제 문구를 고정하지 않았다.

TARGET_FILES_AND_METHODS: 수정 `SmartFallbackService`의 상수·세 메서드; 테스트 생성
`src/test/java/com/example/lms/service/fallback/SmartFallbackUtf8ContractTest.java`.

DO_NOT_TOUCH: `FallbackHeuristics`, model routing, manual system+user concatenation,
raw query 표시/로그, root `java`의 낡은 prompt assembly.

RED_OR_CHARACTERIZATION_TEST: 정상 `정보 없음`, legacy mojibake, whitespace가 동일
fallback eligibility; system/user fixture가 6줄·비단정·가능한 후보 계약을 포함;
no-model/exception/candidate-empty가 정상 template을 반환.

MINIMAL_PATCH_STEPS: supporting reference
`java/com/example/lms/service/fallback/SmartFallbackService.java:55-148`의 정상 문자열을
현재 PromptContext builder 구조에만 이식한다. query 본문 대신 기존 `queryHash12`를
template에 유지한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: LLM 없음/실패 시 정상 한국어 template;
`fallback.reason=context_missing|no_evidence|model_unavailable|model_failed`,
candidateCount/queryHash12/queryLength만 기록한다.

NEGATIVE_AND_CONCURRENCY_TESTS: 충분한 context에서는 null, unknown heuristic는 null,
candidate 0/3, 두 동시 query hash 격리, legacy sentinel은 출력되지 않음.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.service.fallback.SmartFallbackUtf8ContractTest `
  --tests com.example.lms.service.fallback.SmartFallbackPromptBoundaryTest `
  --tests com.example.lms.prompt.PromptBuilderBoundaryTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: final prompt가 PromptBuilder를 우회하거나 raw query가 template/log에
노출되거나 정상 context가 fallback으로 바뀌면 rollback.

EVIDENCE_NEEDED: supporting reference 문구의 현재 UX 승인. active test fixture로 먼저
고정한 뒤 source를 바꾼다.

COMPLETION_CRITERIA: exact UTF-8 prompt/template snapshot, boundary test GREEN,
no-model fail-soft GREEN, broad ladder GREEN.

### DIRECTIVE C3 — SelfAsk seed/follow-up UTF-8 fixture

DIRECTIVE_ID: `AWX-AUDIT50-C3-SELFASK-UTF8`

SELECTED_FINDINGS: `#23`

VERDICT_PER_FINDING: `#23 ACCEPT` — 활성 builder의 두 prompt가 mojibake다.

ACTIVE_OWNER_PROOF: `QueryKeywordPromptBuilder.buildSelfAskSeedPrompt`와
`buildSelfAskFollowupPrompt`; 호출 owner는 current SelfAsk retrieval path.

ROOT_CAUSE: prompt method 이동 후 깨진 문자열에 1–3/1–2/한 줄/설명 금지 snapshot
test가 없다.

TARGET_FILES_AND_METHODS: 수정 위 두 method; 확장
`src/test/java/com/example/lms/prompt/QueryKeywordPromptBuilderTest.java`.

DO_NOT_TOUCH: QueryTransformer에 prompt 추가, parser 개수 변경, 다른 keyword prompt,
manual final RAG prompt assembly.

RED_OR_CHARACTERIZATION_TEST: seed fixture는 정확히 짧은 키워드형 질의 1–3개와 한 줄
출력/설명 금지; follow-up은 1–2개와 한 줄에 하나/설명 금지; Unicode replacement와
대표 mojibake token이 없어야 한다.

MINIMAL_PATCH_STEPS: supporting reference
`java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java:488-497`의 정상 계약을
현재 builder method에만 옮기고 `%s` 값 위치를 유지한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: blank question도 prompt 구조는 유지하되 raw 질문을
로그/TraceStore에 기록하지 않는다. `selfAsk.prompt.kind`, `queryLength`, `queryHash12`만.

NEGATIVE_AND_CONCURRENCY_TESTS: blank, emoji/surrogate boundary, multiline input,
동시 seed/follow-up fixture 분리.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.prompt.QueryKeywordPromptBuilderTest `
  --tests com.example.lms.prompt.PromptBuilderBoundaryTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: query count/parser contract가 바뀌거나 prompt가 builder 밖으로
이동하거나 Unicode 검사 실패 시 rollback.

EVIDENCE_NEEDED: 없음; reference는 supporting-only이며 active tests가 최종 authority다.

COMPLETION_CRITERIA: 두 exact snapshot과 parser boundary GREEN, broad ladder GREEN.

### DIRECTIVE C4 — Chat SSE/session/attachment 표시문 UTF-8

DIRECTIVE_ID: `AWX-AUDIT50-C4-CHAT-UI-UTF8`

SELECTED_FINDINGS: `#24, #25`

VERDICT_PER_FINDING: 둘 다 `ACCEPT`. 활성 SSE thought/status와 attachment/session error
표시문이 mojibake다.

ACTIVE_OWNER_PROOF: `ChatApiController`의 stream debug events, sync attachment meta,
`deleteSession`, `getSession`.

ROOT_CAUSE: machine code와 display text가 controller literal로 혼합되고 UTF-8 순서
fixture가 없다.

TARGET_FILES_AND_METHODS: 수정 `ChatApiController`의 해당 literal과 기존
`ChatStreamEvent.StatusSignal` 사용 seam; 테스트 생성
`src/test/java/com/example/lms/api/ChatApiControllerUtf8StreamContractTest.java`.

DO_NOT_TOUCH: stream protocol/event names, `error`/`action` values, provider search,
session auth, all-controller string cleanup.

RED_OR_CHARACTERIZATION_TEST: exact 순서는 `처리를 시작합니다`, `쿼리 분석 중`,
`웹/하이브리드 검색 준비`, `검색 계획 수립`; attachment는
`첨부 %d개 중 %d개 로드 실패`; missing session은 `세션이 만료되었습니다.`이며
machine fields는 그대로다.

MINIMAL_PATCH_STEPS: 표시문을 controller 인접 constants 또는 기존 status signal
factory 한 곳에 모으고 위 exact fixture로 교체한다. root `java` 참고본의 실행 로직은
복사하지 않는다.

FAIL_SOFT_AND_REDACTION_CONTRACT: machine-readable `error/action/code` 유지;
`chat.stream.status.code`, `attachmentFailedCount`, `sessionReason`만 남기며 raw session
ID/text를 남기지 않는다.

NEGATIVE_AND_CONCURRENCY_TESTS: debug off, web off, attachment 0/all fail, restoreProbe,
delete/detail 404, 두 stream의 event order 격리.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.api.ChatApiControllerUtf8StreamContractTest `
  --tests com.example.lms.api.ChatApiControllerStateSecurityTest `
  --tests com.example.lms.api.ChatApiControllerRestoreProbeContractTest `
  --tests com.example.lms.api.ChatApiControllerRagControlPresentationContractTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder. 이후 task-started localhost runtime에서
Browser로 debug stream 순서와 session reset 표시만 확인한다. Computer lane은 불필요하다.

ROLLBACK_CONDITION: event code/order 또는 restore action이 바뀌거나 browser에서
cancel/reload가 깨지거나 mojibake가 남으면 rollback.

EVIDENCE_NEEDED: localhost port owner와 browser-visible run. port 8080/8081 owner가
불명확하면 browser lane만 HOLD한다.

COMPLETION_CRITERIA: exact payload tests, security/restore regression, broad ladder,
필요한 Browser visible proof; provider/model 성공 주장은 금지.

### DIRECTIVE C5 — 모델 설정 예외 flash UTF-8

DIRECTIVE_ID: `AWX-AUDIT50-C5-MODEL-FLASH-UTF8`

SELECTED_FINDINGS: `#26`

VERDICT_PER_FINDING: `#26 ACCEPT` — unexpected exception branch만 mojibake다.

ACTIVE_OWNER_PROOF: `PageController.saveModelSettings`.

ROOT_CAUSE: 정상/validation branch에는 redacted 문구가 있으나 generic exception 문구
fixture가 없다.

TARGET_FILES_AND_METHODS: 수정 `PageController.saveModelSettings`; 테스트 확장 또는 생성
`src/test/java/com/example/lms/web/PageControllerModelSettingsUtf8Test.java`.

DO_NOT_TOUCH: model ID/hash redaction, model change service, route/security, 다른 page text.

RED_OR_CHARACTERIZATION_TEST: service generic exception 때 exact
`모델 저장 중 예상치 못한 오류가 발생했습니다.` flash, redirect 유지, raw model/error
message 미노출.

MINIMAL_PATCH_STEPS: supporting reference `java/com/example/lms/web/PageController.java:188-195`
문구만 현재 redaction 구조에 넣는다.

FAIL_SOFT_AND_REDACTION_CONTRACT: log는 errorType/errorHash/errorLength, flash는 고정 문구.

NEGATIVE_AND_CONCURRENCY_TESTS: success, IllegalArgumentException, generic exception,
null/긴 model ID, 병렬 두 request flash 격리.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.web.PageControllerModelSettingsUtf8Test `
  --tests com.example.lms.web.PageControllerModelPolicyTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: success/validation flash가 변하거나 raw model ID/error가 노출되면
rollback.

EVIDENCE_NEEDED: 없음.

COMPLETION_CRITERIA: exception MVC test와 redaction GREEN, broad ladder GREEN.

### DIRECTIVE D1 — provider 검색 bounded-I/O 수명 정책

DIRECTIVE_ID: `AWX-AUDIT50-D1-SEARCH-BOUNDED-IO`

SELECTED_FINDINGS: `#27, #28, #29, #30, #38`

VERDICT_PER_FINDING: 모두 `ACCEPT`. 현재 세 owner가 timeout 뒤 `cancel(false)`를 쓰고,
Nova/empty fallback은 caller interrupt를 clear하며, `searchIoExecutor`의
`CallerRunsPolicy`가 provider I/O를 request thread에서 실행할 수 있다.

ACTIVE_OWNER_PROOF: `NovaAnalyzeWebSearchRetriever.retrieve`,
`HybridWebSearchEmptyFallbackAspect` provider fan-out,
`HybridRetriever` branch/fusion execution, `SearchExecutorConfig.searchIoExecutor`.

ROOT_CAUSE: caller deadline, client timeout, queue admission, worker task lifetime,
interrupt propagation을 하나의 정책으로 소유하지 않는다.

TARGET_FILES_AND_METHODS: 위 네 owner와 기존 provider client timeout configuration;
테스트 생성/확장 `NovaAnalyzeWebSearchRetrieverBoundedLifetimeTest`,
`HybridWebSearchEmptyFallbackBoundedLifetimeTest`,
`HybridRetrieverBoundedLifetimeTest`, `SearchExecutorSaturationContractTest`.

DO_NOT_TOUCH: 세 파일에 독립 `cancel(true)` 땜질, executor 추가 증식, provider 대체,
fake result, raw query, PromptBuilder, unrelated retrieval algorithm.

RED_OR_CHARACTERIZATION_TEST: non-interruptible fake provider를 timeout storm으로 막아도
accepted/running/queued task 수가 configured 상한을 넘지 않고 caller latency가 deadline+
작은 tolerance 이내; caller interrupt flag 보존; saturation은 provider call 0의 명시적
fail-soft reason.

MINIMAL_PATCH_STEPS: 먼저 하나의 policy contract를 tests로 고정한다. provider/client에
실제 connect/read/request timeout을 적용하고, bounded queue+fail-fast rejection으로
격리한다. interruptible client만 협력 취소를 사용하고 non-interruptible client는
bulkhead 상한으로 격리한다. caller interrupt는 re-interrupt/typed cancellation로 보존한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: `search.io.reason=deadline|caller_cancelled|queue_saturated|client_timeout`,
`activeCount`, `queueDepth`, `cancelAttemptedCount`, `completedAfterDeadlineCount`,
queryHash12만 기록한다. timeout/disabled는 empty evidence이며 fake 결과를 만들지 않는다.

NEGATIVE_AND_CONCURRENCY_TESTS: interruptible/non-interruptible clients, queue+1,
100회 timeout storm, caller cancel, near-deadline completion, shutdown, no stale interrupt
on reused worker.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test `
  --tests ai.abandonware.nova.orch.adapters.NovaAnalyzeWebSearchRetrieverBoundedLifetimeTest `
  --tests ai.abandonware.nova.orch.aop.HybridWebSearchEmptyFallbackBoundedLifetimeTest `
  --tests com.example.lms.service.rag.HybridRetrieverBoundedLifetimeTest `
  --tests com.example.lms.config.SearchExecutorSaturationContractTest `
  --tests ai.abandonware.nova.orch.adapters.NovaAnalyzeWebSearchRetrieverTimeoutTraceTest `
  --tests ai.abandonware.nova.orch.aop.HybridWebSearchInterruptHygieneAspectTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: `$demo1-subsystem-patch-directive`가 core S01–S08 body 변경으로
판정하면 그 gate를 먼저 적용하고, 두 subsystem 이상이면 완료 전
`$demo1-cross-subsystem-guard`를 적용한 뒤 2.2 exact ladder.

ROLLBACK_CONDITION: provider 호출이 caller thread에서 실행되거나 active/queued 수가
상한을 넘거나 caller interrupt가 사라지거나 success result가 누락되면 rollback.

EVIDENCE_NEEDED: 각 실제 provider/client의 interruptibility와 effective wire timeout.
provider attempt가 관찰되지 않으면 `wireAttemptCoverage=not_observed`를 유지한다.

COMPLETION_CRITERIA: bounded-lifetime RED→GREEN, interrupt preservation, saturation
fail-soft, existing success ordering, subsystem guard와 broad ladder GREEN.

### DIRECTIVE D2 — FederatedEmbeddingStore 전체 deadline/bulkhead

DIRECTIVE_ID: `AWX-AUDIT50-D2-FEDERATED-DEADLINE`

SELECTED_FINDINGS: `#32, #33`

VERDICT_PER_FINDING: 둘 다 `ACCEPT`. 현재 각 Future에 전체 timeout을 순차 적용하고
timeout 후 backing store는 `cancel(false)`로 남는다.

ACTIVE_OWNER_PROOF: `FederatedEmbeddingStore.search`, fixed pool,
`shutdownPool`; root `main/java`의 `@Primary` component.

ROOT_CAUSE: per-store timeout을 request absolute deadline으로 오인하고 store client
lifetime/bulkhead를 별도 추적하지 않는다.

TARGET_FILES_AND_METHODS: 수정 `FederatedEmbeddingStore.search`와 인접 test seam;
확장 `src/test/java/com/example/lms/vector/FederatedEmbeddingStoreTest.java`.

DO_NOT_TOUCH: vector store implementation 대체, shutdownNow를 request cancellation로
사용, embedding contents/raw filter trace, pool 증식.

RED_OR_CHARACTERIZATION_TEST: stores > maxParallelism, 첫 store hang/뒤 store success,
일부 hang에서 total elapsed가 single request timeout+tolerance 이하; 반복 timeout에도
active workers가 maxParallelism 이하.

MINIMAL_PATCH_STEPS: search 시작 시 absolute deadline을 한 번 계산하고 completion-order로
남은 시간만 기다린다. store별 client timeout seam이 있으면 적용하고, 없으면 fixed
bulkhead가 새 request를 fail-fast하도록 한다. timeout task의 late completion은 result에
합치지 않는다.

FAIL_SOFT_AND_REDACTION_CONTRACT: healthy store partial result는 유지한다.
`vector.federated.reason=store_timeout|request_deadline|saturated`, storeId는 allowlisted
label/hash, timedOutCount/lateCompletionCount/activeCount만 기록한다.

NEGATIVE_AND_CONCURRENCY_TESTS: 1/8/9 stores, all hang, one success, caller interrupt,
shutdown race, late side effect 배제.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.vector.FederatedEmbeddingStoreTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: total elapsed가 bound를 넘거나 healthy partial result를 잃거나
pool 상한을 넘으면 rollback.

EVIDENCE_NEEDED: 각 backing store의 native timeout/cancel 지원 여부; 없으면 bulkhead
격리만 완료로 주장한다.

COMPLETION_CRITERIA: absolute deadline와 partial fail-soft GREEN, count-only metrics,
broad ladder GREEN.

### DIRECTIVE D3 — graph ingest 공용 bounded executor

DIRECTIVE_ID: `AWX-AUDIT50-D3-GRAPH-INGEST-EXECUTOR`

SELECTED_FINDINGS: `#34, #35`

VERDICT_PER_FINDING: 둘 다 `ACCEPT`; #35는 BrainState flags가 켜질 때만 실행된다.

ACTIVE_OWNER_PROOF: `GraphRagThumbnailBridge.captureThumbnail`과
`BrainStateChatWorkflowAspect.captureConversationTurn`이 executor 없는
`CompletableFuture.runAsync`를 사용한다.

ROOT_CAUSE: graph ingest 이벤트에 queue, rejection, shutdown, completion owner가 없다.

TARGET_FILES_AND_METHODS: 위 두 class, 기존 config 인접 위치의 단일 named bounded
executor bean; 테스트 생성 `src/test/java/com/example/lms/service/rag/graph/GraphIngestExecutorContractTest.java`.

DO_NOT_TOUCH: 두 executor 생성, common pool tuning, KG data model, transactional 저장소,
raw chat/thumbnail text trace.

RED_OR_CHARACTERIZATION_TEST: queue capacity+1에서 accepted/rejected가 deterministic,
thumbnail/chat이 같은 executor를 사용, shutdown 이후 submit 거부, accepted task 완료
count와 failure count 일치.

MINIMAL_PATCH_STEPS: repository의 기존 context-aware executor pattern으로 named fixed
pool+bounded queue+fail-fast rejection 하나를 만들고 두 owner에 주입한다. event ordering이
필요한 동일 session에는 기존 순서를 보존한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: saturation은 primary chat/thumbnail persistence를
실패시키지 않고 graph ingest만 skip한다. `graph.ingest.{accepted,rejected,completed,failed}.count`,
queueDepth, reason, sessionHash12만.

NEGATIVE_AND_CONCURRENCY_TESTS: null/blank event, burst, same-session order,
different-session concurrency, exception, shutdown/restart.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.service.rag.graph.GraphIngestExecutorContractTest `
  --tests com.example.lms.service.rag.graph.GraphRagThumbnailBridgeTest `
  --tests com.example.lms.service.rag.graph.BrainStateChatWorkflowAspectTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: primary response가 graph saturation 때문에 실패하거나 accepted
task가 추적 없이 유실되거나 두 executor가 생기면 rollback.

EVIDENCE_NEEDED: same-session strict ordering 요구. 요구가 없으면 bounded parallel
processing을 유지한다.

COMPLETION_CRITERIA: one executor identity, bounded queue, lifecycle counts,
focused/broad GREEN.

### DIRECTIVE D4 — transient graph session 격리

DIRECTIVE_ID: `AWX-AUDIT50-D4-TRANSIENT-GRAPH-ISOLATION`

SELECTED_FINDINGS: `#36`

VERDICT_PER_FINDING: `#36 ACCEPT` — 기능이 켜지면 null session 모두
`__TRANSIENT__`를 공유한다.

ACTIVE_OWNER_PROOF: `BrainStateChatWorkflowAspect.captureConversationTurn`.

ROOT_CAUSE: persistent session identity가 없는 request를 하나의 persistent graph key로
대체한다.

TARGET_FILES_AND_METHODS: 수정 위 aspect의 null-session branch; 확장
`BrainStateChatWorkflowAspectTest`.

DO_NOT_TOUCH: 추적 가능한 raw ephemeral UUID 저장, retrieval session contract,
BrainState feature default, D3 executor 외 새 executor.

RED_OR_CHARACTERIZATION_TEST: 두 동시 null-session request가 서로의 text를 같은 graph
session으로 ingest하지 않는다; 선택 정책이 skip이면 ingest 0회.

MINIMAL_PATCH_STEPS: durable session ID가 없으면 capture를 skip하는 방식을 우선한다.
product가 ephemeral capture를 요구한다는 증거가 있을 때만 request-local non-persisted
token을 사용하고 retrieval에 노출하지 않는다.

FAIL_SOFT_AND_REDACTION_CONTRACT: `graph.capture.skipped.reason=missing_durable_session`,
count만 기록. raw ephemeral key/text 금지.

NEGATIVE_AND_CONCURRENCY_TESTS: null, blank, valid ID, two concurrent transient,
memorySaveAllowed false, D3 saturation.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.service.rag.graph.BrainStateChatWorkflowAspectTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: valid session capture가 사라지거나 transient data가 persistent
retrieval에 나타나거나 raw key가 trace에 남으면 rollback.

EVIDENCE_NEEDED: ephemeral capture product requirement; 없으면 skip이 canonical이다.

COMPLETION_CRITERIA: concurrent transient isolation, valid-session regression,
focused/broad GREEN.

### DIRECTIVE D5 — RemoteEmbedder client/interrupt 수명

DIRECTIVE_ID: `AWX-AUDIT50-D5-REMOTE-EMBEDDER-LIFETIME`

SELECTED_FINDINGS: `#39, #40`

VERDICT_PER_FINDING: 둘 다 `ACCEPT`. field HttpClient를 두고도 매 call 새 client를
생성하며 `InterruptedException`을 broad catch로 삼켜 heuristic fallback을 실행한다.

ACTIVE_OWNER_PROOF: `RemoteEmbedder.embed`; conditional selector
`AnnIndexer.selectEmbedder`.

ROOT_CAUSE: reusable client field와 blocking send exception contract가 구현에서 분리됐다.

TARGET_FILES_AND_METHODS: 수정 `RemoteEmbedder` constructor/test seam과 `embed`;
테스트 확장 `RemoteEmbedderSecretSafetyTest`, 생성 `RemoteEmbedderLifecycleTest`.

DO_NOT_TOUCH: endpoint/env property 이름, provider substitution, new HTTP dependency,
raw text/key/header logging. 누락 credential outbound 문제는 새 scope로 확장하지 말고
별도 RED가 확인되면 같은 owner의 후속 지시서로 분리한다.

RED_OR_CHARACTERIZATION_TEST: injected/field client factory count 1, 연속 두 호출 재사용,
send interrupted 시 interrupt flag true이고 heuristic fallback 호출 0 또는 명시된 typed
cancellation; 2xx/non-2xx/parse failure 기존 fallback 유지.

MINIMAL_PATCH_STEPS: `client.send`를 사용하고 package-private constructor로 fake client
주입 seam을 만든다. `InterruptedException`을 먼저 catch해 re-interrupt하고 typed
cancellation/fail-soft 결과를 기존 caller 계약에 맞게 반환한다. IOException은 기존
heuristic fallback을 유지한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: `remoteEmbed.reason=interrupted|http_status|parse_failed`,
status class/errorType/inputHash12/length만; raw response/body/header 금지.

NEGATIVE_AND_CONCURRENCY_TESTS: two sequential calls, concurrent calls, interrupted send,
timeout, 4xx/5xx, malformed JSON, placeholder key header suppression.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test `
  --tests com.abandonware.ai.agent.integrations.RemoteEmbedderLifecycleTest `
  --tests com.abandonware.ai.agent.integrations.RemoteEmbedderSecretSafetyTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: interrupt가 사라지거나 fallback behavior가 2xx success를 덮거나
Authorization/raw text가 노출되면 rollback.

EVIDENCE_NEEDED: caller가 typed cancellation을 받을 수 있는 exact interface contract.
interface가 표현하지 못하면 re-interrupt 후 safe empty/failure reason을 테스트로 고정한다.

COMPLETION_CRITERIA: one client, interrupt preservation, existing redaction/fallback GREEN,
broad ladder GREEN.

### DIRECTIVE E1 — QueryExpander hash-only bounded cache

DIRECTIVE_ID: `AWX-AUDIT50-E1-QUERY-EXPANDER-CACHE`

SELECTED_FINDINGS: `#42`

VERDICT_PER_FINDING: `#42 ACCEPT` — raw original query를 cache key에 영구 포함하고
hit 때만 TTL을 검사하며 size cap이 없다.

ACTIVE_OWNER_PROOF: `QueryExpander.cache/expand`; active caller
`AutonomousExplorationService`.

ROOT_CAUSE: privacy-sensitive logical key와 eviction budget이 분리되지 않았다.

TARGET_FILES_AND_METHODS: 수정 `QueryExpander` cache key/entry/eviction; 테스트 생성
`src/test/java/com/example/lms/search/QueryExpanderCacheBoundTest.java`.

DO_NOT_TOUCH: LLM prompt, sanitizer, expansion ranking, new cache dependency,
raw query telemetry.

RED_OR_CHARACTERIZATION_TEST: source/reflection으로 raw query가 key에 없음, deterministic
clock expiry, unique max+1 eviction, same logical input hit, deliberately injected digest
collision이 원문/스니펫 fingerprint mismatch로 잘못 hit하지 않음.

MINIMAL_PATCH_STEPS: SHA-256 digest+non-sensitive structural fingerprint key, bounded
access-order map 또는 existing concurrent structure+deterministic sweep, Clock seam을 쓴다.

FAIL_SOFT_AND_REDACTION_CONTRACT: digest 실패 시 cache bypass하고 expansion은 계속한다.
`queryExpander.cache.reason=hit|miss|expired|evicted|hash_failed`, size/count만.

NEGATIVE_AND_CONCURRENCY_TESTS: blank/long query, many unique, expiry race, same-key
concurrent calls, collision fixture, LLM failure.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.search.QueryExpanderCacheBoundTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: raw query가 key/trace에 남거나 cache hit가 다른 logical input 결과를
반환하거나 expansion behavior가 변하면 rollback.

EVIDENCE_NEEDED: max entries 기본값의 product 승인; 없으면 보수적 fixed default를
test/document한다.

COMPLETION_CRITERIA: raw-key non-retention, TTL/max/collision/concurrency GREEN,
broad ladder GREEN.

### DIRECTIVE E2 — EmbeddingCache bounded eviction

DIRECTIVE_ID: `AWX-AUDIT50-E2-EMBEDDING-CACHE`

SELECTED_FINDINGS: `#43`

VERDICT_PER_FINDING: `#43 ACCEPT` — expired entry를 remove하지 않고 max size가 없으며
null/zero/negative TTL은 영구다.

ACTIVE_OWNER_PROOF: `EmbeddingCache.InMemory.getOrCompute/invalidate`; 기본 consumer
`DecoratingEmbeddingModel`.

ROOT_CAUSE: TTL freshness, storage retention, single-flight lifecycle이 하나의 bounded
cache contract로 결합되지 않았다.

TARGET_FILES_AND_METHODS: 수정 `EmbeddingCache.InMemory`; 테스트 생성
`EmbeddingCacheBoundedEvictionTest`, 기존 `EmbeddingCacheInterruptContractTest` 유지.

DO_NOT_TOUCH: embedding values 로그, stale-on-failure, single-flight semantics,
new dependency, blanket interrupt.

RED_OR_CHARACTERIZATION_TEST: expired lookup 즉시 remove, unique max+1 bounded,
deterministic Clock, null/zero/negative TTL 정책, stale-on-compute-failure, concurrent
same-key compute 1회.

MINIMAL_PATCH_STEPS: Clock와 maxEntries를 package-private constructor에 추가하고
existing map에 bounded sweep/eviction을 넣는다. non-positive TTL은 문서화한 short
default 또는 no-cache 중 하나로 고정하되 영구 retention은 금지한다.

FAIL_SOFT_AND_REDACTION_CONTRACT: eviction은 caller 결과를 실패시키지 않는다.
`embeddingCache.{hit,miss,expired,evicted,computeFailed}.count`, size만.

NEGATIVE_AND_CONCURRENCY_TESTS: empty vector non-cache, invalid TTL, expiry during
single-flight, invalidate race, interrupted waiter flag 보존.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.example.lms.service.embedding.EmbeddingCacheBoundedEvictionTest `
  --tests com.example.lms.service.embedding.EmbeddingCacheInterruptContractTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: stale-on-failure/single-flight가 깨지거나 empty embedding이 cache되거나
size가 상한을 넘으면 rollback.

EVIDENCE_NEEDED: non-positive TTL의 승인된 의미. 결정을 못 받으면 `no-cache`가 더
보수적인 fail-soft다.

COMPLETION_CRITERIA: bounded size/expiry, 기존 resilience와 interrupt tests GREEN,
broad ladder GREEN.

### DIRECTIVE E3 — agent RRF 유한 수치 계약

DIRECTIVE_ID: `AWX-AUDIT50-E3-RRF-NUMERIC-GUARD`

SELECTED_FINDINGS: `#46`

VERDICT_PER_FINDING: `#46 ACCEPT` — parse 성공한 음수/NaN/Infinity를 그대로 점수에 쓴다.

ACTIVE_OWNER_PROOF: `com.abandonware.ai.agent.integrations.RrfFusion.fuse/parseEnvDouble`;
conditional consumer `HybridRetriever`.

ROOT_CAUSE: syntactic parsing만 있고 domain validation이 없다.

TARGET_FILES_AND_METHODS: 수정 `RrfFusion` parameter normalization; 확장
`RrfFusionTraceTest`.

DO_NOT_TOUCH: canonical `com.example.lms.service.rag.fusion.RrfFusion`, ranking/dedupe
algorithm, env 이름, dependency.

RED_OR_CHARACTERIZATION_TEST: `K=-1,0,NaN,+Infinity`; weights negative/NaN/Infinity;
finite default; score는 항상 finite이고 comparator ordering deterministic.

MINIMAL_PATCH_STEPS: `Double.isFinite`, `K>0`, weight non-negative와 최소 한 positive
weight를 검증한다. invalid input만 기존 safe defaults로 되돌린다.

FAIL_SOFT_AND_REDACTION_CONTRACT: `rrf.config.reason=non_finite|invalid_k|invalid_weight|zero_weight_sum`,
envName allowlist와 invalidCount만 기록; raw env value 금지.

NEGATIVE_AND_CONCURRENCY_TESTS: mixed valid/invalid, both zero, duplicate items,
parallel fuse calls, deterministic tie.

FOCUSED_VERIFY_COMMANDS:

```powershell
.\gradlew.bat test --tests com.abandonware.ai.agent.integrations.RrfFusionTraceTest `
  --tests com.abandonware.ai.agent.integrations.HybridRetrieverTraceTest `
  --no-daemon --project-cache-dir $ProjectCache
```

BROAD_VERIFY_COMMANDS: 2.2의 exact ladder.

ROLLBACK_CONDITION: valid 기존 설정의 ranking이 바뀌거나 score가 non-finite이거나 raw
env value가 trace에 나타나면 rollback.

EVIDENCE_NEEDED: weight upper-bound/normalization product contract. 증거가 없으면
non-negative finite만 강제하고 합 정규화는 하지 않는다.

COMPLETION_CRITERIA: invalid matrix와 valid regression GREEN, broad ladder GREEN.

## 4. `VERIFY_FIRST` 전용 비변경 증거 게이트

이 절의 packet은 소스수정 지시가 아니다. 먼저 아래 read-only 증거와 정책 결정을
고정하고, 결론이 `ACCEPT`일 때만 2절의 공통 preflight부터 새 실행 세션을 시작한다.
증거가 없으면 해당 finding만 `evidence_needed`로 남긴다. 다른 `ACCEPT` directive를
막거나 이 절의 여러 항목을 한 patch에 합치지 않는다.

### GATE VF-A — sessionless upload와 content policy (`#05`, `#09`)

CURRENT_VERDICT: `#05 VERIFY_FIRST`, `#09 VERIFY_FIRST`.

READ_ONLY_EVIDENCE_ACTION:

```powershell
Set-Location 'C:\AbandonWare\demo-1\demo-1\src'
rg -n --hidden --glob '!build/**' --glob '!app/build/**' `
  'attachments|sessionId|upload|deleteAttachment|FormData' `
  main/resources app/src/main/resources frontend 2>$null
rg -n 'allowedExtensions|contentType|originalFilename|MultipartFile|extractText' `
  main/java/com/example/lms/storage/LocalFileStorageService.java `
  main/java/com/example/lms/service/AttachmentService.java `
  main/java/com/example/lms/api/AttachmentController.java
```

DECISION_RULE:

- `#05`: 공식 UI가 session 생성 전에 업로드한다면 owner-bound 임시 handle + TTL +
  attach/abandon/delete 계약을 채택한다. 공식 caller가 모두 먼저 session을 만든다는
  fresh trace와 외부 호환성 부재가 증명되면 `sessionId` 필수화가 더 작은 변경이다.
  둘 중 어느 계약인지 결정되지 않으면 `VERIFY_FIRST`를 유지한다.
- `#09`: 허용 extension별 실제 parser와 허용 signature, 최대 sniff bytes, empty/oversize,
  polyglot 처리표를 먼저 승인한다. 기존 JDK/현재 parser로 bounded 판정이 가능한 형식만
  `ACCEPT`; 새 production dependency가 필요한 형식은 별도 권한 전까지 `DEFER`한다.

REQUIRED_CHARACTERIZATION: upload-before-session → attach, abandon, delete; 자기/타인 owner;
extension/content mismatch; empty; header가 chunk 경계를 넘는 경우; 허용 signature 뒤
active content가 붙은 polyglot. raw file body는 test failure나 trace에 출력하지 않는다.

EVIDENCE_NEEDED: 공식 UI request 순서, 외부 API 호환성, extension별 parser/signature 표,
최대 파일 및 sniff 크기. Browser는 이 결정에 필요한 실제 request 순서를 관찰할 때만
사용하고, Computer tag는 임의 UI 클릭 권한으로 해석하지 않는다.

### GATE VF-B — callback와 위치정보 product contract (`#15`, `#16`, `#17`)

CURRENT_VERDICT: `#15 VERIFY_FIRST`, `#16 VERIFY_FIRST`, `#17 VERIFY_FIRST`.

READ_ONLY_EVIDENCE_ACTION:

```powershell
Set-Location 'C:\AbandonWare\demo-1\demo-1\src'
rg -n 'N8nNotifier|onSuccess|SUCCEEDED|callback|webhookUrl' `
  main/java main/resources --glob '*.java' --glob '*.properties'
rg -n 'LocationController|setConsent|lastResolved|LastLocation|X-User-Id|location' `
  main/java main/resources --glob '*.java' --glob '*.properties' --glob '*.html'
```

DECISION_RULE:

- `#15`: `computeStatus`와 `deliveryStatus`를 분리하고, success의 공개 의미, 최대 시도,
  backoff/deadline, 재시작 후 처리, callback egress allowlist/private-address 정책을
  승인한 뒤 `ACCEPT`한다. 단순 `.block()` 또는 무한 retry는 허용하지 않는다.
- `#16`: 제품 결정은 `DISABLE_END_TO_END` 또는
  `RESTORE_AUTHENTICATED_CONSENTED_INTAKE` 중 하나여야 한다. 주석만 해제하거나 임의
  `X-User-Id`를 principal로 신뢰하는 안은 `REJECT`한다.
- `#17`: consent-off가 `DELETE_NOW`, `RETENTION_WINDOW`, 또는 법적 보존 중 무엇인지,
  DB 좌표·address cache·파생 prompt data·backup에 동일하게 적용되는지 결정된 뒤
  `ACCEPT`한다. HTTP intake 비활성은 기존 데이터 정리를 생략할 근거가 아니다.

REQUIRED_CHARACTERIZATION: callback 2xx/4xx/timeout/private address; 동일 job 중복 callback;
consent 없는 write/read; 철회 직후와 재시작 뒤 read; cache miss/hit; 인증 사용자와
spoofed header. 위치·URL·callback body는 hash/reason/count만 보존한다.

EVIDENCE_NEEDED: callback 상태/API 호환성, egress 정책, 위치 기능 on/off 결정, 동의 철회
보존 정책. 이 네 결정 중 필요한 항목이 비어 있으면 해당 finding만 보류한다.

### GATE VF-D — 조건부 graph 실행과 관리자 SSE (`#31`, `#37`)

CURRENT_VERDICT: `#31 VERIFY_FIRST`, `#37 VERIFY_FIRST`.

READ_ONLY_EVIDENCE_ACTION:

```powershell
Set-Location 'C:\AbandonWare\demo-1\demo-1\src'
rg -n 'RagGraphExecutor|supplyAsync|orTimeout|cancel\(' `
  main/java main/resources --glob '*.java' --glob '*.properties'
rg -n 'DebugEventsDiagnosticsController|SseEmitter|newCachedThreadPool|SecurityFilterChain' `
  main/java main/resources --glob '*.java' --glob '*.properties'
```

DECISION_RULE:

- `#31`: 활성 property/profile과 실제 call path가 확인되면 D1의 동일 bounded-I/O 수명
  정책에 consumer로 편입하되 common pool 제거와 absolute deadline을 별도 focused test로
  증명한다. 호출되지 않는 compatibility owner면 source를 추측 수정하지 않는다.
- `#37`: 관리자 인가 경계, 허용 동시 연결 수, idle/max lifetime, client reconnect 계약을
  먼저 고정한다. existing cleanup callback만으로 thread 상한이 증명되면 no-op;
  `connections > limit`에서 thread가 증가하면 bounded scheduler/shared publisher 최소안으로
  `ACCEPT`한다.

REQUIRED_CHARACTERIZATION: graph timeout 뒤 active task count; caller interrupt; SSE
connect/disconnect/half-open/limit+1; timeout 후 executor/scheduler count. provider 결과와 SSE
payload 원문은 저장하지 않는다.

EVIDENCE_NEEDED: 실제 활성 조건과 call owner, admin auth proof, 연결/idle 한도. load test는
localhost와 synthetic event만 사용한다.

### GATE VF-E — bounded registry/router와 packaged orphan (`#44`, `#45`, `#47`)

CURRENT_VERDICT: `#44 VERIFY_FIRST`, `#45 VERIFY_FIRST`, `#47 VERIFY_FIRST`.

READ_ONLY_EVIDENCE_ACTION:

```powershell
Set-Location 'C:\AbandonWare\demo-1\demo-1\src'
rg -n 'SoakMetricRegistry|resetForSid|snapshot|SoakQuickRunner' main/java --glob '*.java'
rg -n 'requestedCache|computeIfAbsent|PolicyBasedModelRouter|requestedModel' `
  main/java main/resources --glob '*.java' --glob '*.properties'
rg -n 'WeightedPowerMean' app main src/test --glob '*.java' --glob '*.kt' --glob '*.kts'
$env:AWX_SPLIT_BUILD_OUTPUTS='1'
$env:AWX_BUILD_HOST_ID='vf-e'
$env:GRADLE_USER_HOME='C:\Users\nninn\.codex\tmp\demo1-vf-e-gradle-home'
$VfProjectCache='C:\Users\nninn\.codex\tmp\demo1-vf-e-project-cache'
$JarStartedUtc=(Get-Date).ToUniversalTime().AddSeconds(-2)
.\gradlew.bat :app:jar --no-daemon --project-cache-dir `
  $VfProjectCache --rerun-tasks
$FreshJars=@(Get-ChildItem -LiteralPath 'app\build\vf-e\libs' -Filter '*.jar' -File |
  Where-Object { $_.LastWriteTimeUtc -ge $JarStartedUtc })
if ($FreshJars.Count -ne 1) { throw "evidence_needed: expected one fresh :app JAR; observed=$($FreshJars.Count)" }
$JarEntries=& jar tf $FreshJars[0].FullName
if ($LASTEXITCODE -ne 0 -or
    $JarEntries -notcontains 'service/rag/fusion/WeightedPowerMean.class') {
  throw 'evidence_needed: packaged WeightedPowerMean not observed in the fresh :app JAR'
}
```

DECISION_RULE:

- `#44`: SID lifecycle owner가 run 종료를 알 수 있으면 explicit remove를 우선한다. 그렇지
  않으면 max-runs + TTL을 승인하고 concurrent reset/snapshot contract를 고정한 뒤
  `ACCEPT`한다.
- `#45`: 허용 모델 ID/옵션 cardinality와 `ChatModel` close ownership을 먼저 증명한다.
  요청 모델이 이미 finite allowlist에 normalize되고 cache 상한 이내면 no-op; 임의 ID/옵션이
  key를 늘리면 allowlist + bounded eviction + close-once를 하나의 directive로 `ACCEPT`한다.
- `#47`: fresh JAR 포함과 direct/reflective/service-loader/external ABI owner를 모두 확인한다.
  owner가 있으면 p=0 geometric mean, weight/input finite 계약을 테스트 후 `ACCEPT`한다.
  owner가 없으면 runtime patch는 `REJECT`; 삭제는 비가역 cleanup 권한을 받은 별도 작업에서만
  수행한다.

REQUIRED_CHARACTERIZATION: 10,000 distinct SID/model key의 size 상한; concurrent same-key
single construction; eviction close-once; `p=0`, null, negative base/fractional p, NaN/Infinity;
JAR ABI. 모델 ID, SID, input vector 원문은 trace하지 않는다.

EVIDENCE_NEEDED: registry run lifecycle, router allowlist와 close contract, WeightedPowerMean의
실제 owner/ABI. glob로 여러 JAR를 한꺼번에 읽지 말고 fresh task가 만든 단일 JAR의 절대
경로와 timestamp를 먼저 고정한다.

### GATE VF-F — broad catch 110개 중 첫 실제 owner (`#50`)

CURRENT_VERDICT: `#50 VERIFY_FIRST`.

READ_ONLY_EVIDENCE_ACTION:

```powershell
$Report='C:\Users\nninn\.codex\tmp\demo1-audit50-reports\broad-catch\broad_catch_report.json'
(Get-FileHash -LiteralPath $Report -Algorithm SHA256).Hash
rg -n 'catch\s*\(\s*(Exception|Throwable|RuntimeException)' `
  main/java/com/example/lms/service/ChatOrchestrator.java `
  main/java/com/example/lms/service/ChatServiceLegacy.java `
  main/java/com/example/lms --glob '*.java'
```

DECISION_RULE: 먼저 report hash가
`AD962739F732081C82BA8217400FA37064E695A69FB4799B236770D5309462E1`인지 확인한다. 그 뒤
`GENUINE_SILENT` 중 active owner + reachable call + 사용자 의미 변화 + 재현 가능한 failure
injection이 모두 있는 정확히 한 catch만 선택한다. 해당 catch에만 기존 TraceStore/
DebugEventStore seam으로 `stage`, categorical `reason`, `errorType`, count/hash breadcrumb를
추가하는 새 단일-finding directive를 만든다. 110개 일괄 로깅, patch/legacy/shadow 파일 동시
수정, raw exception message/prompt/payload 기록은 `REJECT`한다.

REQUIRED_CHARACTERIZATION: 같은 입력의 success/failure, fallback 결과, breadcrumb count,
raw query/body/credential 부재. report hash가 다르면 classifier를 현재 HEAD에서 다시 만든 뒤
새 snapshot으로만 결정한다.

EVIDENCE_NEEDED: 선택 catch의 FQCN/line/method, active owner와 caller, injected exception,
기대 fallback, allowlisted breadcrumb. 이 다섯 항목이 없으면 `VERIFY_FIRST`를 유지한다.

## 5. 50개 전체 판정 및 라우팅 표

판정 합계는 `ACCEPT 36`, `VERIFY_FIRST 11`, `DEFER 2`, `REJECT 1`이다. `ACCEPT`는
아래 directive를 한 번에 하나씩 실행한다는 뜻이며, 이미 수정되었다는 뜻이 아니다.

| # | 우선순위·활성성 | 판정 | 짧은 근거 | 실행 단위/게이트 |
|---:|---|---|---|---|
| 01 | P1 · ACTIVE_CONFIRMED | ACCEPT | 인증 없이 전체 설정 map 반환 | A1 |
| 02 | P1 · ACTIVE_CONFIRMED | ACCEPT | bootstrap key가 현재 request에 전달되지 않음 | A2 |
| 03 | P1 · ACTIVE_CONFIRMED | ACCEPT | 첫 요청 fallback과 후속 cookie owner 형식 불일치 | A2 |
| 04 | P1 · ACTIVE_CONFIRMED | ACCEPT | attachment API에 ChatSession owner 검증 없음 | A3 |
| 05 | P2 · ACTIVE_CONFIRMED | VERIFY_FIRST | sessionless lifecycle의 공개 API 계약 선택 필요 | VF-A |
| 06 | P1 · ACTIVE_CONFIRMED | ACCEPT | metadata만 지우고 storage file을 남김 | A4 |
| 07 | P2 · ACTIVE_CONFIRMED | ACCEPT | 네 memory collection에 TTL/size/종료 정리 없음 | A4 |
| 08 | P2 · ACTIVE_CONFIRMED | ACCEPT | 반환 URL과 configurable handler prefix 불일치 | A5 |
| 09 | P2 · ACTIVE_CONFIRMED | VERIFY_FIRST | 형식별 signature/parser 정책과 dependency 경계 필요 | VF-A |
| 10 | P1 · ACTIVE_CONFIRMED_WITH_LIMIT_EVIDENCE_NEEDED | ACCEPT | 서명 전에 body 전체 materialize; 정확한 limit은 설정 증거 필요 | B1 |
| 11 | P2 · ACTIVE_CONFIRMED | ACCEPT | 수신한 idempotency key를 상태 전이에 사용하지 않음 | B1 |
| 12 | P1 · ACTIVE_CONFIRMED | ACCEPT | enqueue 뒤 실행 소비자가 없어 PENDING 고착 | B2 |
| 13 | P2 · ACTIVE_CONFIRMED | ACCEPT | 완료 job map에 TTL/상한/eviction 없음 | B3 |
| 14 | P2 · ACTIVE_CONFIRMED | ACCEPT | cached pool 무제한, shutdown/deadline 계약 없음 | B3 |
| 15 | P2 · ACTIVE_CONFIRMED | VERIFY_FIRST | compute와 callback delivery 상태/egress 계약 결정 필요 | VF-B |
| 16 | P2 · ACTIVE_CONFIRMED_MIXED | VERIFY_FIRST | intake 복구 또는 end-to-end 비활성 중 product 결정 필요 | VF-B |
| 17 | P1 · ACTIVE_CONDITIONAL | VERIFY_FIRST | consent-off의 보존·삭제 계약 결정 필요 | VF-B |
| 18 | P1 · ACTIVE_CONFIRMED | ACCEPT | active rescue 사용자 문구가 mojibake | C1 |
| 19 | P1 · ACTIVE_CONFIRMED | ACCEPT | active no-evidence sentinel이 정상 계약과 다름 | C1 |
| 20 | P1 · ACTIVE_CONFIRMED | ACCEPT | SmartFallback sentinel 비교가 깨진 값에 고정 | C2 |
| 21 | P1 · ACTIVE_CONFIRMED | ACCEPT | SmartFallback system/user prompt가 mojibake | C2 |
| 22 | P1 · ACTIVE_CONFIRMED | ACCEPT | SmartFallback non-LLM template도 mojibake | C2 |
| 23 | P1 · ACTIVE_CONFIRMED | ACCEPT | SelfAsk seed/follow-up prompt가 mojibake | C3 |
| 24 | P1 · ACTIVE_CONFIRMED | ACCEPT | SSE thought/status runtime text가 mojibake | C4 |
| 25 | P1 · ACTIVE_CONFIRMED | ACCEPT | session/attachment runtime error text가 mojibake | C4 |
| 26 | P2 · ACTIVE_CONFIRMED | ACCEPT | model settings exception flash가 mojibake | C5 |
| 27 | P1 · ACTIVE_CONFIRMED | ACCEPT | timeout 뒤 Nova provider worker가 계속 실행 | D1 |
| 28 | P1 · ACTIVE_CONFIRMED | ACCEPT | Hybrid empty fallback도 같은 잔존 수명 문제 | D1 |
| 29 | P2 · ACTIVE_CONFIRMED | ACCEPT | canonical branch/fusion timeout 뒤 task가 살아남음 | D1 |
| 30 | P2 · ACTIVE_CONFIRMED | ACCEPT | polling이 caller interrupt를 clear | D1 |
| 31 | P2 · ACTIVE_CONDITIONAL | VERIFY_FIRST | 활성 call/조건과 D1 lifetime owner 증명 필요 | VF-D |
| 32 | P2 · ACTIVE_CONFIRMED | ACCEPT | store별 full timeout을 순차 적용해 총 deadline 초과 | D2 |
| 33 | P2 · ACTIVE_CONFIRMED | ACCEPT | future 취소가 backing store I/O 종료를 보장하지 않음 | D2 |
| 34 | P2 · ACTIVE_CONFIRMED | ACCEPT | thumbnail ingest가 common-pool fire-and-forget | D3 |
| 35 | P2 · ACTIVE_CONDITIONAL | ACCEPT | BrainState capture도 같은 unmanaged async seam 사용 | D3 |
| 36 | P1 · ACTIVE_CONDITIONAL | ACCEPT | sessionless chats가 `__TRANSIENT__` key를 공유 | D4 |
| 37 | P2 · ACTIVE_CONFIRMED_ADMIN | VERIFY_FIRST | auth, connection limit, idle/lifetime 계약 필요 | VF-D |
| 38 | P2 · ACTIVE_CONFIRMED | ACCEPT | CallerRunsPolicy가 provider I/O를 caller에 역류 | D1 |
| 39 | P2 · ACTIVE_CONDITIONAL | ACCEPT | 선언한 client 대신 호출마다 HttpClient 생성 | D5 |
| 40 | P2 · ACTIVE_CONDITIONAL | ACCEPT | InterruptedException을 일반 fallback 실패로 삼킴 | D5 |
| 41 | P1 · ACTIVE_CONDITIONAL | DEFER | 실제 ONNX는 새 production dependency/지원 결정 필요 | DEFER-ONNX |
| 42 | P2 · ACTIVE_CONFIRMED | ACCEPT | raw question cache key와 무제한 lazy TTL map | E1 |
| 43 | P2 · ACTIVE_CONFIRMED | ACCEPT | expired entry 미제거, size cap 없음 | E2 |
| 44 | P2 · ACTIVE_CONDITIONAL | VERIFY_FIRST | SID lifecycle owner와 remove/TTL 정책 필요 | VF-E |
| 45 | P2 · ACTIVE_CONFIRMED | VERIFY_FIRST | 모델 allowlist cardinality와 close ownership 필요 | VF-E |
| 46 | P2 · ACTIVE_CONDITIONAL | ACCEPT | finite/domain validation 없이 RRF 계산 | E3 |
| 47 | P3 · PACKAGED_ORPHAN · OWNER_PROOF_NEEDED | VERIFY_FIRST | JAR 포함은 확인됐지만 caller/ABI owner 미확인 | VF-E |
| 48 | P3 · SHADOW_SOURCE | REJECT | 중복 FQCN은 기본 JAR 제외; runtime patch 대상 아님 | REJECT-RUNTIME |
| 49 | P2 · ACTIVE_CONFIRMED_STRUCTURAL | DEFER | 결함과 무관한 선제 대분해는 최소 patch 원칙 위반 | DEFER-STRUCTURAL |
| 50 | P2 · ACTIVE_CONFIRMED_STRUCTURAL | VERIFY_FIRST | 110개 중 첫 active/reachable/user-impact catch 선택 필요 | VF-F |

### `DEFER` / `REJECT`의 해제 조건

- `#41 DEFER-ONNX`: ONNX를 공식 지원할지 제거할지 제품 결정을 받고, 필요한 production
  dependency 추가 권한과 모델/shape/close 계약이 생겼을 때만 별도 directive를 작성한다.
  그 전에는 현재 explicit `dependency_unavailable` lexical fallback을 성공으로 위장하지 않는다.
- `#48 REJECT-RUNTIME`: `checkSourceSetHygiene`와 fresh `:app:jar` exclusion 회귀만 유지한다.
  alias 정리/삭제는 canonical ABI와 test 의존을 증명하고 비가역 cleanup 승인을 받은 별도
  작업이다.
- `#49 DEFER-STRUCTURAL`: 실제 결함의 RED가 한 seam 추출 없이는 고칠 수 없고 current call
  graph/contract tests가 고정됐을 때만 그 collaborator 하나를 함께 추출한다. 파일 줄 수,
  subsystem signal 수, 클래스 수 감소만을 성공 기준으로 삼지 않는다.

## 6. 실행 순서, 외부 증거 lane, 최종 handoff

### 6.1 권장 순서

1. P1 security/ownership: `A1 → A2 → A3 → A4`.
2. P1 webhook execution: `B1 → B2`; 이후 `B3`.
3. UTF-8 계약은 `C1 → C2 → C3 → C4 → C5`, 매 directive별 exact string test를 먼저
   고정한다.
4. async 수명은 D1 하나로 Nova/Hybrid/search executor policy를 먼저 고정한 뒤
   `D2 → D3 → D4 → D5`를 독립 실행한다.
5. bounded cache/numeric guard는 `E1 → E2 → E3`.
6. 각 `VERIFY_FIRST` packet은 독립 결정이다. 여러 packet이 승인되어도 하나의 mega-patch로
   합치지 않는다.

이 순서는 우선순위이며 일괄 실행 명령이 아니다. 한 directive가 완료될 때마다 lease 해제,
postimage hash, focused/broad 증거, final diff를 확정한 후 다음 directive를 새 preflight로
시작한다.

### 6.2 Browser / Computer / Superpowers 경계

- `@브라우저`: localhost UI/API 의미가 바뀌는 A3/A5/C4 및 VF-A/VF-D에만 fresh browser
  proof를 추가한다. 렌더링 성공은 provider/wire 성공이나 소유권 의미 성공을 대신하지 않는다.
- `@컴퓨터`: shell/file API로 불가능한 Windows UI 관찰에만 사용한다. 프로세스 시작/종료,
  env 변경, 파일 mutation 권한을 자동으로 만들지 않는다. task가 시작한 프로세스만 관리한다.
- `@Superpowers`: brainstorming/plan/verification discipline을 제공하지만 live source owner,
  preflight, lease, PatchDrop, dependency, destructive-operation 권한을 대체하지 않는다.
- Browser와 Computer evidence는 서로 대체할 수 없고, 이번 문서 작성에서는 어느 UI도
  조작하지 않았다. 구현자가 UI 변경을 실행할 때만 해당 directive의 요구에 따라 사용한다.

### 6.3 이 지시서 자체의 완료 계약

- 이 파일은 실행 계획 산출물이며 Java, resource, Gradle, property, database, runtime을
  변경하지 않는다.
- 50개 finding은 5절에 정확히 한 번씩 판정되고, 36개 `ACCEPT`는 21개 작은 directive에,
  11개 `VERIFY_FIRST`는 5개 read-only gate에, 나머지는 명시적 해제 조건에 연결된다.
- 구현 완료 주장은 각 directive의 fresh focused output, 영향 경계, sourceSet hygiene,
  LangChain4j purity, compile/classes, 필요한 runtime/browser proof가 모두 있을 때만 가능하다.
- report/hash/과거 build output은 snapshot identity를 증명할 뿐 새 patch의 성공 증거가 아니다.
- source 변경 도중 baseline failure가 나면 해당 verification lane만 `HOLD`하고
  `holdScope`, `firstBlockingRule`, `blockingEvidence`, `independentWorkCompleted`,
  `repositoryWideHold=false`를 기록한다. 모든 lane이 실제로 unsafe일 때만 true다.

END_OF_DIRECTIVE_ARTIFACT
