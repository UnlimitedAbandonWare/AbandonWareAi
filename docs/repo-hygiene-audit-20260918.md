# Repository Hygiene Audit — demo-1/src (2026-09-18, v3)

Scope: `C:\AbandonWare\demo-1\demo-1\src`.

**전제(v2, 사용자 지시)**: 이 저장소의 Git history는 정상 기준이 아니다. 2026-08-10경 HEAD는 이미 깨진 과거 상태이고, 이후 working tree 직접 수정으로 시스템이 복구·개선되어 왔다. 따라서 Git은 조회·비교·계보 추적용으로만 쓰고, SSOT는 현재 working tree + 활성 sourceSet + 통과하는 compile/test/runtime이다. 본 보고서는 그 전제로 재분류한다.

분류: `CURRENT_CANONICAL` / `CURRENT_UNUSED` / `LEGACY_DUPLICATE` / `GENERATED` / `UNKNOWN`.

---

## 0. 요약

- `git status` 61,989 → **5,195** (.gitignore 생성물 존 추가 후), dead-FQCN 제거 후 현재 ~4,100.
- **LEGACY_DUPLICATE 1,099개 파일 삭제 완료** — 전량 `C:\AbandonWare\_rescue\repo-hygiene-20260918\`(저장소 외부, 7.4MB)에 path-scoped rescue 후 제거. 매니페스트·SHA-256: `C:\AbandonWare\_rescue\repo-hygiene-20260918\{dead-tree-manifest,rescue-sha256-manifest}.json` (검증 1,730/1,730 일치).
- 삭제 후 dead↔active FQCN 중복 **0건** — 에이전트가 dead copy를 canonical로 오인할 최대 함정 해소. 삭제 안전성 semantic 검증(§1-5): **ORPHANED_LOGIC_CANDIDATE 23파일** 후보로 명시(자동 이식 없음), compile/test/hygiene 전부 통과.
- `UNKNOWN` 631개 파일은 규칙대로 유지 (근거 없으면 삭제 금지).
- Git 명령은 조회만 사용. commit/stage/reset/clean 일절 없음. `index.lock`만 제거(stale, 0바이트, git 프로세스 없음).

---

## 1. 완료 조치

### 1-1. 공통 룰에 Git 원칙 추가 (Codex·Devin·Grok 공통)
- `AGENTS.md` — `DEMO1-GIT-WORKTREE-FIRST` 섹션 신설: Git은 read-only 증거, working tree+sourceSet+compile/test/runtime이 SSOT, 금지 명령 목록, 5-버킷 분류, dead-tree A/B/C 판정 절차, 외부 rescue 규칙.
- `.windsurf/rules/demo1-hard-constraints.md` — always-on 요약 1줄 추가 (`.devin/RULES_SSOT.md`는 미러 금지 명시라 미수정 — AGENTS.md를 가리키므로 자동 적용).

### 1-2. `.gitignore` 수정 (v1에서 수행, v2 전제에서도 유효)
- 광역 catch-all 4개(`/**/*secret*.*`, `/**/*token*.*`, `/**/*credential*.*`, `/**/*api[-_]*key*.*`)가 활성 소스(`CancellationToken.java` + 테스트 5개)를 git에서 숨기던 버그 → 확장자 한정으로 축소. 시크릿 계열 ignore 전부 유지 검증.
- 생성물 존 추가: `/var/`, `/data/agent-handoff/`, `/verification/`, `/output/`, `/.gradle-*/`, `/.playwright-cli/`, `/.superpowers/`, `/.codex-remote-attachments/`, `/.env.shared/`, `/BackupsXS/`, `/.next/`, `/*.bak-*`, `/awx-bootrun-*`, `/hs_err_pid*.log`, `/javac.*.args`, `__pycache__/`, `*.pyc`.
- 효과: untracked 60,133 → 3,332. 숨겨진 소스는 다시 보이고, 생성물은 status를 오염시키지 않음. ※ 이 조치는 "untracked = 불필요" 판정이 아니라 검색 노이즈 제거다.

### 1-3. stale `.git/index.lock` 제거
- 0바이트, 2026-09-08 생성, 실행 중 git 없음 → 삭제. `git rev-parse --verify HEAD` 정상.

### 1-4. LEGACY_DUPLICATE 1,099개 삭제 (외부 rescue 후)
판정 근거: ① 비활성 트리(어떤 sourceSet에도 srcDir되지 않음, 빌드·스크립트 참조 0건) ② 동일 FQCN이 활성 루트에 존재 → canonical은 컴파일·실행되는 활성 쪽(Git 연령 무관).

| 트리 | 삭제(dup) | 잔류 UNKNOWN | 잔류 non-java |
|---|---|---|---|
| `java/` | 919 (12 identical + 907 diff) | 290 | 5 |
| `test/` | 5 | 97 | 7 |
| `com/` | 4 | 6 | 0 |
| `service/` | 1 | 6 | 0 |
| `guard/` | 0 | 1 | 0 |
| `app/src/main/java` | 170 (7 identical + 163 diff) | 218 | 1 |
| **합계** | **1,099** | **618** | **13** |

- Rescue: `C:\AbandonWare\_rescue\repo-hygiene-20260918\` — 전 트리 통째로(1,730파일) 보존 후 dup만 삭제. 저장소 내부에 archive/clone 트리는 만들지 않음(검색 오염 방지).
- UNKNOWN 잔류는 규칙 C("근거 없으면 삭제 금지")에 따라 유지. 이들은 FQCN 충돌이 없어 canonical 오인 위험이 낮음. 단순명 참조 여부는 약한 증거이므로 UNKNOWN으로 보류.

### 1-5. 삭제 안전성 검증 (v3, 2026-09-18)

sourceSet hygiene 통과만으로는 안전성이 증명되지 않으므로(비활성 파일은 원래 컴파일 대상이 아님), rescue↔canonical semantic diff를 수행했다. Git HEAD는 비교 기준으로 사용하지 않았다.

**Rescue 무결성**
- `C:\AbandonWare\_rescue\repo-hygiene-20260918\`: 1,730파일 전량 존재, `dead-tree-manifest.json` + `rescue-sha256-manifest.json`을 외부 rescue에도 저장(ignored `var/` 외에 영속 사본 확보). SHA-256 검증 **1,730/1,730 일치, missing 0, mismatch 0**.

**Semantic diff (1,099쌍, 1,070 diff 파일 대상)**
- 자동 추출 항목: rescue-only 메서드/생성자·필드/상수·애노테이션·config/env 키·provider/fallback/routing 조건·catch·timeout/retry/budget·public 시그니처. 포맷팅/import/주석 차이 제외.
- 1단계 휴리스틱: 522파일에 rescue-only 요소. → 2단계(public/behavioral 메서드 + 상수): 123파일. → 3단계(메서드명이 canonical 원문에 없는 것): 44파일. → 4단계(메서드명이 **활성 루트 전체**에 없는 것): **23파일 = ORPHANED_LOGIC_CANDIDATE**.
- 나머지는 canonical에 이름 존재(리네임/시그니처 드리프트) 또는 활성 다른 클래스로 이등가 이식 확인 → 소실 아님. 예: `KnowledgeGraphHandler.parseWeights`→`parseScoreWeights`(kg.score.weights 설정화), `service.NaverSearchService` hedge/gamePatch 로직은 `com.abandonware.ai.service`/`com.example.lms.service` 정식 패키지 구현에 존재(canonical `service.` 버전은 의도된 minimal placeholder), `FinalSigmoidGate` 계열 score/allow는 활성 canonical에 존재.
- **ORPHANED_LOGIC_CANDIDATE는 자동 이식하지 않음.** 증거 파일(전수 목록): `C:\AbandonWare\_rescue\repo-hygiene-20260918\orphaned-logic-{scan,tierA,verified,confirmed,globalcheck}.json`.

| FQCN | 활성 루트에 없는 rescue-only 멤버 |
|---|---|
| `com.example.lms.config.Bm25Config` | `bm25Retriever` @Bean, `getIndexPath/setIndexPath` (+상수 `enabled=false`,`provider="lucene"`,`indexPath`,`topK=20`) |
| `service.NaverSearchService` | `ApiKey` 내부클래스, `resolveKeys`, `setNaverKeysCsv/ClientId/ClientSecret` (canonical `service.` 버전은 placeholder; 실기능은 타 패키지 구현에 존재) |
| `com.example.lms.matrix.MatrixTransformer` | `moeGates` (+`ALPHA/BETA/GAMMA/MU` 상수) |
| `com.example.lms.service.chat.ChatRunRegistry` | `startOrGet`, `markCancelled` |
| `com.example.lms.service.rag.handler.KnowledgeGraphHandler` | `parseWeights` (이등가 `parseScoreWeights` 존재 — 잔여 이름만) |
| `com.example.lms.service.rag.BiEncoderRerankerTest` | `testRerankTopN` (dead 테스트) |
| `com.example.lms.config.AppSecurityConfig` | `appSecurity` @Bean SecurityFilterChain |
| `com.example.lms.service.guard.CitationGate` | `hasEnoughTrusted` |
| `com.abandonware.ai.agent.orchestrator.Step` | `setRef` (Jackson setter) |
| `ai.abandonware.nova.orch.aop.NightmareBreakerWebRateLimitPropagatorAspect` | `aroundRecordRateLimit`, `aroundIsOpenOrHalfOpen` (@Around advice) |
| `com.example.lms.config.AutoWiringConfig` | `tileAliasCorrector`, `mpcPreprocessor` @Bean |
| `com.example.lms.service.UserService` | `authenticateStudent` |
| `com.example.lms.service.rag.ModelBasedQueryComplexityClassifier` | `processInput`, `processOutput` |
| `com.example.lms.service.scoring.AdaptiveScoringService` | `applyPositiveFeedback`, `applyNegativeFeedback` |
| `com.nova.protocol.config.NovaProtocolConfig` | `piiSanitizer` @Bean (2개 diff copy) |
| `com.abandonware.ai.agent.orchestrator.Orchestrator` | `runFlow` |
| `com.example.lms.service.embedding.EmbeddingCache` | `getOrPut` |
| `com.example.lms.strategy.RetrievalOrderService` | `decideKWithRisk` |
| `com.nova.protocol.alloc.RiskKAllocator` | `allocCvarAware` |
| `com.abandonware.ai.predict.tree.PredictTreeController` | `buildPost` |
| `com.example.lms.service.TrainingService` | `trainAsync` |
| `com.abandonware.ai.agent.consent.ConsentRequiredException` | `missingScopes` (static factory) |
| 상수 변경(diff-only) | `UawAutolearnProperties`: `minEvidenceCount 1→3`, `maxZeroEvidenceAcceptedPerCycle 1→0`, `allowZeroEvidenceForStaticSeeds true→false`; `service.NaverSearchService`: `timeoutMs 40000→1800` |

- 판독 주의: "메서드명 부재"는 곧 "기능 부재"가 아니다. canonical 쪽이 의도적으로 축소·개명·설정화한 결과일 수 있으며(익명 우선 구조상 `appSecurity`/`authenticateStudent` 축소는 정상적일 수 있음), 위 항목은 **사람 검토 대상 후보**다. 어떤 것도 자동 복원하지 않았다.

**숫자 재검산**
- `java/`: 삭제 919파일 = **919 unique FQCN**, intra-tree 중복 0. 이전 감사의 "918"은 `main/java`와만 비교한 수치였고, 919는 **전체 활성 루트**(main/java + java_clean + test 루트)와 비교한 수치 — 비교 범위 차이로 +1. 카운팅 오류 아님.
- `app/src/main/java`: 삭제 170파일 = **165 unique FQCN** — `DppDiversityReranker`가 6개 경로에 중복 존재(164×1 + 1×6 = 170). 설명됨.

---

## 2. 현재 상태 판정 (새 분류)

### CURRENT_CANONICAL (보호)
- `main/java` (2,129 클래스), `main/resources`, `src/test/java`, `src/chatUiTest`, `src/glmAgentMcp*`, `app/src/main/java_clean`+`app/src/main/resources`, `build.gradle.kts`, `settings.gradle`(Groovy — Gradle이 실제 사용), `gradlew.bat`, `Start-RAG.bat`→`scripts/start_rag_stack.ps1`, `.agents/skills`(96), `agent-prompts`, `docs`, `configs`, `config`, `.codex/hooks`, `.windsurf`, `.devin`, `tools/build_error_miner.py`(CI 참조), `.github/workflows`, `frontend`, `demo-1`/`lms-core`/`cfvm-raw`(includeLegacyModules 게이트 레거시 모듈), `__patch_drop__`, `.secrets`(디바이스 로컬).
- `main/java`·`src/test/java` 등의 untracked 다수 파일 — v2 전제상 untracked 자체는 결함 아님. 현재 컴파일 대상이면 CANONICAL.

### LEGACY_DUPLICATE — 1,099개 삭제 완료 (§1-4)

### GENERATED (검색만 차단, 디스크 정리는 사용자 판단)
- `var/` 10.8GB, `data/agent-handoff/` 4.8GB(24,872파일), 루트 `.gradle-*` 77개, `app/build/` 142,800파일/211MB, `frontend/node_modules` 9,701, `verification/` 1,746, `output/`, `.playwright-cli`, `.superpowers`, 루트 `awx-bootrun-*` 로그 ~40 + dead `.pid` 3 + `hs_err_pid*` 11, `*.bak-*` 10개, `javac.*.args`, `__pycache__`(일부 tracked → `git rm --cached` 후보), `ONNX`(0바이트).
- 전부 gitignore 처리됨. 실제 디스크 삭제는 사용자 승인 필요 — 전부 재생성 가능하지만 handoff 증거 성격이 섞여 있음.

### CURRENT_UNUSED / UNKNOWN (유지 — §1-4 잔류 631 + 아래)
- `settings.gradle.kts` — Gradle엔 dead(Groovy 우선)지만 `.codex/hooks.json`의 루트 sentinel → 현재 기능상 사용 중. `rootProject.name="lms-core"` 등 내용이 settings.gradle과 드리프트 → 에이전트가 빌드 설정으로 오인할 수 있으므로 sentinel임을 명시할 것.
- `test/` 잔류 104, `java/` 잔류 295, `app/src/main/java` 잔류 219 등 UNKNOWN — 사용 증거 없음·삭제 금지.
- tracked 레거시 스캐폴드: `analysis/`(15), `AGENT/`(6), `_assistant/`(6), `ops/`(10), `plans/`(6), `addons/`(32), `extras/`(65), `PATCHES/`(14), `tools-scorecard/`(13), `backup/`(93), `contract/`(1), `infra/`(1), `ci/`(1), `.internal/`(1) — 활성 빌드와 무관하나 tracked·사용자 원본 가능성 → UNKNOWN, 유지.
- `gradlew`↔`gradlew-real`(내용 상이), `verify_boot.ps1`(untracked)↔`.sh`/`_plus.sh`(tracked), `tools/build_error_guard.*` 5종 — canonical 판정 근거 부족 → UNKNOWN, 후속 CONSOLIDATE 후보.
- `main/java`↔`app/java_clean` 활성-활성 FQCN 중복 9건(`AnswerSanitizer`, `DomainWhitelist`, `TraceContext` 등) — 둘 다 살아있는 모듈이라 코드 병합 판단 필요 → 별도 작업.

### 수정 필요 (FIX)
- `AGENTS.md` "AutoLearn Handoff Review" → `data/agent-handoff/codex/manifest.json`/`cycles.jsonl`/`rejected.jsonl` 미존재 — 현재 구조에 맞게 수정 필요.
- `semantic-catalog.yaml` — 26개 신규 스킬 누락(core-request-router, goal-complete-stop, triad-deliberation, meta-rayban-display, safe-source-edit 등). 사용자가 현재 갱신 작업 중 → 본인 작업과 충돌하지 않게 후속.
- `.env.example` 10키 vs 실제 `${ENV}` 참조 686종 — 이름 전용(env-name only) 재생성 필요.
- 22/96 스킬 `agents/openai.yaml` 없음 — paired-metadata 규약 불일치(동작엔 지장 없음).

### Git/worktree 상태 (참고용 — 복구 기준 아님)
- HEAD `0796a3c` @ `codex/owned-runtime-browser-restart` (2026-08-10) — 과거 기준으로 취급하지 않음.
- fsck 손상 0. dangling 3,303개 — 과거 증거로만 취급, 복원 대상 아님.
- worktree 36개 등록, **5개 prunable**(awx-notebook-* ×4, `D:\...\codex-source-edit-probe-v2`) — 메타데이터 정리(`git worktree prune`)는 사용자 승인 시 가능.
- staged 1개(`SelfAskPlannerOwnershipContractTest.java`) — 절반만 staged된 상태로 방치됨(참고).
- ~1,700개 수정 파일에 LF→CRLF 경고 — 워크트리 LF 드리프트(SMB/Mac 기여). 저장소 내용은 `text=auto`로 정규화되므로 미관상 문제.

## 3. SMB/다중 에이전트 흔적 (실측)

- `.bak-*` 타임스탬프 백업 — 오늘만 6개+ 에이전트 패스(model-lock, project-root, spring-vibe, devwatch, force-restart, dupgate).
- `.gradle-*` 77개 — 세션마다 저장소 안에 새 Gradle home 생성. 호스트 분리 의도였으나 위치가 소스 루트인 설계 결함 → 이제 ignored.
- `AbandonWaregradle-project-cachedesktop-triadic-20260731` — 경로 결합 버그 빈 디렉터리.
- prunable worktree 4개가 `awx-notebook-*` + D:\ — 노트북 체크아웃 사라진 메타데이터.
- `java/`의 tracked-삭제/untracked-신규 교체 패턴 — 다중 호스트가 같은 트리를 다른 버전으로 덮어쓴 흔적.
- 이중 래퍼(gradlew-real), verify_boot 트리오 추적 불일치 — 우회 구현 흔적.

## 4. 테스트/컴파일 (v3 검증)

- `compileJava` + `compileTestJava` — **BUILD SUCCESSFUL** (desktop split outputs, offline).
- Focused test — ORPHANED 후보 도메인을 커버하는 기존 테스트 17개 클래스 선별 실행: **124 tests, 0 failures, 0 skipped**. 대상: CitationGate(3), Orchestrator, RetrievalOrderService, SimpleRiskKAllocator, EmbeddingCache, ChatRunRegistry(2), NaverSearchService(2), KnowledgeGraphHandler, NovaProtocolGuardNamespace, Bm25Index, NightmareBreaker, DppDiversityReranker, RerankCanonicalizerIdentity, BiEncoderReranker.
- `checkSourceSetHygiene` 재검증 — `owners=2 retainedFiles=2131 duplicateGroups=0`, `inactive-present: app/src/main/java`(잔류 UNKNOWN — 의도된 유지).
- **활성 canonical source 파일 직접 수정 0건. 비활성 source tree의 중복 파일 1,099개 제거.** (삭제 자체는 repository 변경 — 검증 증거는 §1-5.)

## 5. 남은 권장 순서 (사용자 판단 필요)

1. **ORPHANED_LOGIC_CANDIDATE 23파일 사람 검토** (§1-5 표) — rescue에만 있던 멤버가 실제 기능 소실인지 의도된 축소인지 판정. 자동 이식 금지. 이어서 **UNKNOWN 631개 재판정**: 잔류 unique 구현을 import/reflection/config/runtime 근거로 재조사 — 필요 시 동일 방식(외부 rescue→삭제)으로 2차 분리.
2. **`settings.gradle.kts` sentinel 명시** 또는 hook 조건 변경 후 정리.
3. **`git worktree prune`** — 5개 prunable 메타데이터.
4. **AGENTS.md AutoLearn 섹션**을 현재 파일 구조로 수정.
5. GENERATED 디스크 정리(`var/`, `.gradle-*`, `app/build`, `data/agent-handoff` 구간) — 용량 확보용, 사용자 승인 시.
6. 활성-활성 FQCN 중복 9건(main↔app)은 코드 병합 판단이 필요한 별도 작업.
7. 새 baseline은 별도 작업: "과거 commit 복구"가 아니라 "현재 정상 working tree에서 canonical만 선별한 새 기준점" — 사용자 요청 시에만.

## 6. 스터프5(GPT-Pro) 검증 — v2 전제에서 재평가

- **맞은 것**: stale lock, prunable worktree, dead duplicate(`java/` 대량 FQCN 중복 — 실제 삭제까지 수행), 추적된 생성물, 임시파일 잔재, 보고서 스프롤, env 스프롤.
- **환각/과장**: 참조 깨짐 광범위 주장(실측: 스크립트 17개 전부 존재, 스킬 ref 0건 깨짐), 인코딩/BOM 손상(실손상 0건), "timestamp 최신·내용 구버전"(증거 없음).
- **v2 전제에서 무효가 된 지적**: "untracked 60k·오래된 commit 미해결"은 결함이 아니라 알려진 정상 상황. "git gc·dangling 정리"도 우선순위에서 제외.
