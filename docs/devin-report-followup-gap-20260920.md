# 후속 작업 리포트: 최근 Devin 보고서 대비 현재 소스 미반영 항목 (2026-09-20)

- 작성: Devin · taskId `report-gap-followup-20260920` (journal 등록, checkpoint cycle-01)
- 방법: 최근 작성 보고서(2026-09-19~20)에서 "분석됨/수정 필요"로 판정된 항목을 추출 → **현재 로컬 소스·실행 경로**(sha256·실제 라인)와 대조. Git 상태는 판단 근거로 사용하지 않음.
- 판정 시각 기준: 2026-09-20 ~16:1x KST. `hint-context-fold-listen` lease는 아직 active(만료 08:30 UTC)이므로 해당 경로 후속 수정 전 `source_edit_session.ps1 -Action status -TargetManifest` 재확인 필요.
- 분류: **미반영**(보고서에 필요하다고 적혔으나 소스에 없음) / **부분 반영**(일부 경로만) / **검증 필요**(소스는 있으나 실행·실기 증거 부족 또는 의도 확인 필요)

---

## 0. 2026-09-23 교차검증 결과 (taskId `report-crosscheck-diag-touchpoints-0b3d83b1`)

본 리포트 작성 후 소스가 다수 갱신됐다. 09-23 실제 호출 경로·빈 등록·설정값·테스트 기준 재판정:

| 항목 | 09-23 판정 | 근거(현재 소스) |
|---|---|---|
| A-1 | **반영 완료 (LIVE)** | `LensDisplayPrefs`에 `triggerQuietMs`/`cueCooldownMs`/`forceAfterMs`/`forceMinDeltaChars` 존재, `ConversateSessionService`가 `lensPrefs(owner)`로 per-owner 소비, `index.html` `ld-quiet/ld-cooldown/ld-force` 입력 + `app.js` `readLensPatch`/`fillLensInputs` 저장·복원 경로 확인 |
| A-2 | **미해결·lease 차단** | 라이브 `.windsurf/rules/meta-rayban-display-runtime.md`에 "Do not arbitrarily change force-hint 180 s/Δ50 chars" 잔존(사실). 단 원 lease는 만료+`ownerState=unknown`, `-Action recover` 회수 0건 → owner 사망 증거 부재로 본 task에서 편집 불가. 사용자 명시 지시 시 정리 가능 |
| A-3 | **반영 완료** | `LlmGatewayFailureClassifier`에 `insufficient_quota`/`credit_balance_exhausted`/`spend_limit_exceeded`/`blocked_api_access` 4종 코드 세트 확인 |
| A-4 | **반영 완료** | `BRAVE_API_KEY_FREE`: `configs/api-routing.yaml`+`main/resources/configs` 미러, `NovaPropertyAliasEnvironmentPostProcessor` 별칭, `BraveSearchService` lane 선택, `docs/API_ROUTING_SPEC.md`, `.env.example` 전부 존재 |
| A-5 | **해결됨-by-design (접두어 추가 금지)** | 라이브 증거 정정: `application-meta-display.yml`이 Display cue를 `gpt-5.6-luna/terra/sol` → **chat/completions**로 라우팅하고 `ConversateApiRouteTest`가 그 계약을 고정한다. `gpt-5.6` 접두어를 responses-only에 넣으면 이 호출이 로컬 모델로 대체되므로 **YAML 미변경이 정답**(09-19 PROJECT_STATUS 기각 판정과 일치). 회귀 방지로 `ModelGuardYamlCompatibilityTest`에 `gpt-5.6-luna` 비가드 단언 추가(cycle-02, sha `8b38dfe4`, 1/1 통과) |
| A-6 | **사용자 결정 대기 (변경 없음)** | 개발용 무제한 유지 vs shared-read 분리 — 결정 전 편집 금지 유지 |
| A-7 | **적용 완료 (LIVE)** | `.grok/rules/demo1-bridge.md` 신규 생성(cycle-02, sha `f7582382`) — §7-1 내용(루트 고정, PROJECT_STATUS/journal 우선, ledger·lease, 비밀 env 이름만, 무료·로컬 우선, INDEX에서 스킬 1개 선택, `grok inspect` 확인) |
| A-8 | **반영 완료** | `positive-negative-neutral-judge/SKILL.md` L34가 `AGENTS.md`+`.windsurf/rules/demo1-hard-constraints.md`로 정정됨 |
| A-9 | **반영 완료** | `GeminiGateway.java` `modelName(` 단일 호출(L250) — 중복 제거됨 |
| B-1 | **반영 완료 + V-1 검증 완료** | contextEpoch 게이트 구조 확인 유지; V-1 재실행 exit=0 (`ConversateHintContextReplayCasesTest` 6/6 + `ConversateHintContextTest` 11/11, 실패 0) |
| B-2 | **결정 대기 (변경 없음)** | 탭 밖 연속 수음은 네이티브 필요 여부 결정 선행 |
| B-3 | **장부 정리 잔여** | 기능 반영 확인 유지 |
| V-1 | **실행 완료 (PASS)** | `gradlew.bat test --tests ConversateHintContextReplayCasesTest --tests ConversateHintContextTest` exit=0 — 17 tests, failures=0, errors=0, skipped=0 (cycle-03) |
| V-2 | **wear 재기동 후** | unowned runtime — 사용자 승인 필요 |
| V-3/V-4 | **승인 게이트 유지** | 유료 호출/정책 시험 — spend-guard 하 1회씩 |
| V-5 | **의도 확인 필요** | decision-only 유지 |
| V-6 | **evidence_needed 유지** | |
| V-7 | **다음 재기동 후** | |

**교차검증 중 발견된 신규/갱신 사항 (기존 리포트 외)**

- `UnifiedRagOrchestrator` Self-Ask: ~~`requested;planner=present;exec=not_wired`~~ → **배선 완료(LIVE, cycle-02)**. `enableSelfAsk`(플래그) + `TimeBudgetContext` 예산 게이트 하 `selfAskPlanner.generateThreeLanes` 실행 → 산출 서브쿼리가 `req.selfAskSubQueries`로 `retrieveCandidates`의 bounded 로컬 레그(SELFASK-VECTOR/SELFASK-BM25, `SELFASK_LEG_TOPK=6`, `SELFASK_MAX_SUBQUERIES=3`, 웹 제외로 유료 호출 증식 없음)에 병합. 신규 `UnifiedRagOrchestratorSelfAskExecTest` 6/6 통과(플래그 off·예산 소진·seedOnly·플래너 부재·플래너 실패 fail-soft 경로 포함).
- `.devin/PROMPTS/chat-ux-3layer-3device-20260923.md` 결함 주장: ①`ui-heartbeat` 매핑 부재·③진단 GET permitAll — **stale**(`ChatUiHeartbeatController`+테스트 존재, `GET /api/diagnostics/**` 현재 ADMIN). ②cytoscape dead-weight — **사실**이나 `chat-ui.html`+`BrainStateFrontendContractTest`가 활성 외부 lease(`chat-ux-layered-surfaces`) 하 → 해당 owner 작업에 위임.
- 진단 타점 보강 — **적용 완료(cycle-02, 모두 raw 비밀값 미기록)**:
  - `RuleBreakEvaluator`: 제시된 토큰 시도/활성화 시에만 `rulebreak.decision(+reason/policy/requestId/sessionIdHash12/tokenHash12)` + `guard.rulebreak` trace emit — 평문 요청은 무음(핫 패스 노이즈 없음). 기존 18/18 테스트 통과.
  - `UawAutolearnService`: `learning.loop.cycle.status|elapsedMs|sessionIdHash12` + `uaw.autolearn` cycle emit — 사이클당 1건 경계 기록. 기존 31/31 통과.
  - `NovaPropertyAliasEnvironmentPostProcessor`: 완료 로그에 `elapsedMs` 추가(키 이름만, 값 미기록 유지).
- `docs/PROJECT_STATUS.md`: 만료+owner 불명 lease가 커버 → `status_doc.py`(expect-sha256 지정 경로)로만 갱신 시도.

**cycle-02 검증**: `gradlew.bat compileJava compileTestJava` BUILD SUCCESSFUL (exit=0); `gradlew.bat test --tests UnifiedRagOrchestratorSelfAskExecTest --tests ModelGuardYamlCompatibilityTest --tests RuleBreakEvaluatorTest --tests RuleBreakInterceptorTest --tests UawAutolearnServiceTest --tests UnifiedRagOrchestratorRrfContractTest --tests UnifiedRagOrchestratorVectorAxisPolicyTest` BUILD SUCCESSFUL (exit=0) — 합계 82 tests, failures=0, errors=0. checkpoint cycle-02 `status=verified`.

---

## A. 미반영 (소스 수정이 실제로 안 됨) — §0 표의 최신 판정이 우선

### A-1. 힌트 생성 주기(quiet/cooldown/force-after)가 Fold 설정에 연결되지 않음 — 우선순위 P1
- **관련 리포트/규약**: `docs/META_DISPLAY_CUE_LLM_SPEC.md` L67-68, L78("Fold prefs override and persist — do not keep 180s frozen"), `.agents/skills/demo1-meta-display-simple-caption/SKILL.md` L60·L67·L71·L80·L92·L96, `.windsurf/rules/demo1-hard-constraints.md`, AGENTS.md `DEMO1-META-RAYBAN-DISPLAY-RUNTIME`. Grok이 준비한 룰 문구도 같은 방향(`data/agent-handoff/codex-autonomy/display-cycle-ssot-leftovers-3952187d/meta-rayban-display-runtime.md`).
- **현재 소스**:
  - `main/java/com/example/lms/assist/LensDisplayPrefs.java` — 레코드에 `triggerQuietMs`/`cueCooldownMs`/`forceAfterMs`/`forceMinDeltaChars` 필드 없음(13필드: 폰트/줄/TTL/페이지/chars + history 4종 + topicReset).
  - `main/java/com/example/lms/assist/ConversateSessionService.java` L59-63 — `cueCooldownMs`(10s)·`forceAfterMs`(180s)·`forceMinDeltaChars`(50)·`triggerMinDeltaChars`(120)·`triggerQuietMs`(2.5s)가 전부 `@Value` 고정. L412-413 `triggerMinDelta()`/`triggerQuiet()`는 owner 인자조차 없는 고정 클램프. 사용처: L221(cooldown/delta 게이트), L483, L489, L493, L504/514, L508/518.
  - `main/resources/static/assets/display/index.html` — `#lens-display`(ld-*)·`#hint-context`(hc-*)에 조용시간/쿨다운/강제주기 입력 없음. `app.js` `readLensPatch`/`readContextPatch`/`fillContextInputs`에 해당 필드 없음.
- **왜 미완료인가**: 규약·스펙·스킬·하드제약이 "저장 후·재접속 후 실제 생성 주기가 바뀌어야 한다"고 선언됐고 스펙 문서는 이미 "override"라고 기술하지만, 서버 per-owner 경로와 UI가 구현되지 않아 저장해도 2.5s/10s/180s 고정 그대로 동작한다. 문서만 선행된 상태.
- **수정 대상**: `LensDisplayPrefs.java`(필드+범위 상수+`defaults`+`patch`+`describe`+`Patch`; force-after 범위는 공장 180s를 반드시 수용), `ConversateSessionService.java`(`triggerQuiet()`/`triggerMinDelta()`를 owner 스코프 `prefs` 조회로 변경, `cueCooldownMs`/`forceAfterMs`/`forceMinDeltaChars` 사용처를 `lensPrefs(owner)` 경유로), `DisplayConversateController.java`(`lensSettings` Patch 전달 — Patch 레코드 확장으로 자동 전파되는지 확인), `display/index.html`(ld-* 형제 정수-초 입력), `app.js`(`readLensPatch`/`fillLensInputs`/`saveSetting('lensDisplay')` 복원 경로), `DisplayConversateHttpTest`+JS 테스트. `receiver.js` 무관(서버 주기 항목) — `?v=` bump 불필요.
- **주의**: `hint-context-fold-listen` lease가 이 파일들을 커버(만료 2026-09-20T08:30Z). 편집 전 lease status 확인.

### A-2. `.windsurf/rules/meta-rayban-display-runtime.md` 구형 "180s 동결" 문장 잔존 — P1
- **관련**: Grok task `display-cycle-ssot-leftovers-3952187d`(journal `in_progress` 잔류, lease는 2026-09-19T16:30Z에 **만료**). 준비된 교체본이 task dir에 존재.
- **현재 소스**: 라이브 파일에 여전히 "**Do not** arbitrarily change force-hint **180 s/Δ50 chars**, the ~3-minute refresh, or the deterministic rolling trigger." — `.windsurf/rules/demo1-hard-constraints.md`가 명시적으로 "supersede"한다고 선언한 바로 그 문장이 그대로다. 같은 task의 다른 2개 대상(`demo1-meta-display-simple-caption/SKILL.md`, `docs/META_DISPLAY_CUE_LLM_SPEC.md`)은 이미 갱신됨.
- **왜 미완료인가**: Grok이 준비본까지 만들었으나 apply 없이 세션 종료(journal에 event 0건). lease 만료로 현재는 재취득 후 적용 가능한 상태.
- **수정 대상**: `.windsurf/rules/meta-rayban-display-runtime.md` — task dir 준비본을 기준으로 "time knobs follow persisted Fold prefs" 문구로 교체(char 게이트 Δ50/120은 유지).

### A-3. 공유 quota 분류기 `LlmGatewayFailureClassifier`에 `blocked_api_access` 미확장 — P1
- **관련 리포트**: `docs/llm-e1e6-patch-review-20260919.md` P1 (독립 리뷰, taskId `llm-e1e6-patch-review-46b8d6d1`).
- **현재 소스**: `main/java/com/example/lms/llm/gateway/LlmGatewayFailureClassifier.java` L64-77 `hasQuotaErrorCode`는 `status==429 && error.code=="insufficient_quota"`만 인정. `blocked_api_access`(HTTP 400, Groq 지출한도)는 `ConversateApiCueService.java` L510 `QUOTA_ERROR_CODES`에만 존재.
- **영향 경로**: `FallbackAwareChatModel` L301/L410(페일오버 재시도 판정), `ChatWorkflow` L6561/L6683, `LlmErrorClassifier` L29, `OpenAiResponsesChatModel` L251 — 이 경로들에서는 HTTP 400+`blocked_api_access`가 "일시 오류"로 분류되어 같은 계정 재시도 가능. 스터프4 지적 시나리오의 절반만 막힌 상태.
- **왜 미완료인가**: E1 패치는 큐 루프(`ConversateApiCueService`→`ConversateCueRoutingPolicy`)만 커버했고 공유 분류기는 manifest 밖이라 별도 change-set으로 남겨둠 — 후속 change-set이 아직 없음.
- **수정 대상**: `LlmGatewayFailureClassifier.java` `hasQuotaErrorCode`(status 무관 body `error.code` 검사 + 코드 집합 확장: `blocked_api_access`, `credit_balance_exhausted`, `spend_limit_exceeded` — `ConversateApiCueService.QUOTA_ERROR_CODES`와 동일 집합으로 통일 권고). `WebClientResponseException` 분기(L53-54)는 이미 body를 보고 있어 status 조건만 완화하면 됨. 단위 테스트 추가 필요.

### A-4. Brave dual-key(`BRAVE_API_KEY_FREE`) 지시서 전체 미구현 — P1
- **관련 리포트**: `docs/codex/RAG_SEARCH_ORCHESTRATION_DIRECTIVE.md`(Cline 작성, 09-19) — Devin 작업 지시서.
- **현재 소스**: 저장소 전체에서 `BRAVE_API_KEY_FREE` 0건(grep: java/yaml/yml/example). `configs/api-routing.yaml` L63 `env: [BRAVE_API_KEY, BRAVE_SUBSCRIPTION_TOKEN]`뿐. `BraveSearchProperties`에 free 바인딩 없음, `NovaPropertyAliasEnvironmentPostProcessor`에 별칭 없음, `BraveSearchService`에 lane 선택 없음(월 쿼터 카운터는 존재), `frontend/.env.example`에도 없음. 미러 `main/resources/configs/api-routing.yaml` 동일.
- **왜 미완료인가**: 지시서 작성 후 이를 집행한 journal/checkpoint가 없음 — 실행 세션이 시작되지 않은 것으로 보임.
- **수정 대상**: 지시서 §3.1 표 그대로 — `configs/api-routing.yaml`+미러의 brave `env` 첫 항목으로 `BRAVE_API_KEY_FREE`, `BraveSearchProperties.java` free 전용 필드, `NovaPropertyAliasEnvironmentPostProcessor.java` 별칭, `BraveSearchService.java` lane 선택(free quota reserve → base, base는 reserve 스킵), `docs/API_ROUTING_SPEC.md`, `.env.example`, 신규 `BraveDualKeyLaneSelectionTest`.

### A-5. `responses-only-prefixes` YAML이 Java 기본 5종을 대체(누락) — P1
- **관련 리포트**: `docs/llm-api-source-audit-directive-20260919-verification.md` §7 D1(GO + "Java 기본 5종을 YAML에도 반드시 포함" 경고), §2 B.2.
- **현재 소스**: `main/resources/application-llm.yaml` L367-374 = `[gpt-4.1, gpt-4o, gpt-4.5, o1, o3, o4, gpt-5.5-pro]`. `NovaModelGuardProperties.java` L51-57 Java 기본 = `[gpt-5-pro, gpt-5.1-codex, gpt-5-codex, o3-deep-research, o4-mini-deep-research]`. Spring `List` 바인딩은 대체이므로 **유효 리스트 = YAML 7종뿐**.
- **실제 커버리지 갭**(`ModelGuardSupport.isResponsesOnlyModel` L42, `equals(p) || startsWith(p+"-")` 기준): `o3`→`o3-deep-research`, `o4`→`o4-mini-deep-research`는 커버됨. **미커버: `gpt-5-pro`, `gpt-5.1-codex`, `gpt-5-codex`** — 이 모델들이 `/v1/chat/completions`로 호출되면 model-guard가 responses-only로 인식하지 못함.
- **왜 미완료인가**: D1은 GO 판정이었으나 적용된 patch가 없음(E1-E6 사이클은 다른 항목만 커버).
- **수정 대상**: `application-llm.yaml` `responses-only-prefixes`에 `gpt-5-pro`, `gpt-5.1-codex`, `gpt-5-codex` 추가(YAML이 전체를 대체하므로 Java 기본 의도분까지 병합 필수).
- **09-23 정정(cycle-02 적용 후)**: `gpt-5.6`은 **의도적으로 미포함 유지** — `application-meta-display.yml`이 `gpt-5.6-luna/terra/sol`을 chat/completions로 라우팅(`ConversateApiRouteTest` 계약)하므로 `gpt-5.6` 접두어 추가 시 이 호출이 로컬 모델로 대체된다. `gpt-5.6-luna`가 chat/completions를 지원하는지 자체는 미검증(유료 호출 필요, V-3 계열)이지만 **현재 라우팅 계약을 깨는 방향으로는 패치 금지**. 회귀 단언 `ModelGuardYamlCompatibilityTest`에 추가됨.

### A-6. Grok `awx-control-tower` MCP 연결에 shared-read 제한·절대경로 미적용 — P2 (사용자 결정 포함)
- **관련 리포트**: `docs/codex-review-mcp-state-report-20260920.md` §5 P3/P4.
- **현재 설정**: `.grok/config.toml` = `command="python"`(시스템 PATH 의존), `args=["-B","scripts/awx_mcp_stdio_server.py"]`(상대경로 — spawn cwd가 루트일 때만 기동), `env` 없음 → `AWX_MCP_SOURCE_ACCESS=shared-read` 미적용, 비-readOnly 7종(`device_work`, `archive_restore`, `desktop_control_loop` 등) 포함 29개 도구 전부 호출 가능. Devin 연결만 22개로 제한된 상태.
- **왜 미완료인가**: 보고서가 "개발용 쓰기 경로 유지 vs 별도 shared-read 엔트리 분리"를 결정 사항으로 남겼고, 실제 편집이 없었음.
- **수정 대상**: `.grok/config.toml` — env 추가 또는 `awx-shared-read` 분리 엔트리, `command`를 hermes venv python 절대경로로, `args`를 스크립트 절대경로로. 수정 후 `mcp doctor`로 도구 수 확인.

### A-7. `.grok/rules/demo1-bridge.md` 부재 — P2 — **해결됨(09-23 cycle-02)**
- **관련 리포트**: `docs/multi-agent-coordination-analysis-20260919.md` §6 P0, §7-1.
- **현재**: `.grok/rules/demo1-bridge.md` 생성됨(§7-1 내용 그대로: 루트 고정, PROJECT_STATUS/journal 우선 읽기, ledger·lease 절차, 비밀값은 env 이름만, 무료·로컬 우선, INDEX에서 스킬 1개 선택, `grok inspect` 확인). Grok이 규칙을 로드하는지는 다음 `grok inspect`/세션에서 확인(승인·기기 경계 밖이라 runtime 검증은 별도).

### A-8. `positive-negative-neutral-judge` SKILL.md의 stale `.devin/rules` 참조 — P2
- **관련 리포트**: `docs/multi-agent-coordination-analysis-20260919.md` §6 P0(D-2), §7-3.
- **현재 소스**: `.agents/skills/positive-negative-neutral-judge/SKILL.md` L34 — "Always-on contract also lives in `.devin/rules/demo1-operating-style.md`". 해당 파일은 부재(실제 always-on 계약은 `.windsurf/rules/demo1-hard-constraints.md` + `AGENTS.md`).
- **수정 대상**: L34 1줄을 실제 경로로 정정 → `validate_demo1_skill_family.ps1 -Skills positive-negative-neutral-judge`.

### A-9. `GeminiGateway.java` 중복 `builder.modelName(...)` — P3
- **관련 리포트**: `docs/llm-e1e6-patch-review-20260919.md` P6.
- **현재 소스**: `main/java/com/example/lms/learning/gemini/GeminiGateway.java` L249와 L284에 `builder.modelName(effective.model())` 이중 호출. 무해하지만 정리 후보.
- **수정 대상**: L284 1줄 제거.

---

## B. 부분 반영 (일부 경로만 적용됨)

### B-1. 힌트 과거-맥락 제한 + 새 맥락 리셋 — 코어는 반영, 잔여 확인 1건
- **반영 확인(현재 소스)**: `LensDisplayPrefs` history 4필드+`topicResetEnabled`(L8-11, L20-23); `index.html` `#hint-context`(hc-history/window/chars/tokens/topic + `hc-reset`="지금부터 새 주제로 시작"); `app.js` `readContextPatch`→`client.lensSettings`+`saveSetting('lensDisplay')`+READY 시 `fillContextInputs` 복원; `ConversateSessionService` `context_reset`+`preResetAudio`/`contextResetAudioMark` 지연 전사 펜싱(cycle-01 sealed→**verified**, JUnit 11/11); `transcriptDiagnostics`(history/lastContextSelection/events/contextEpoch)가 `testStatus.transcript`로 노출.
- **잔여**: Grok handoff(`hint-context-verify-handoff-888c01f0`) §3의 "retry/fallback/API-cue/local-support가 모두 같은 Work snapshot(ctxEpoch)을 소비하는지 확인" 요청 — 코드상 `new Work(`는 3곳(L229/L502/L530)뿐이고 전부 `s.contextEpoch` 스탬프, 단일 `dispatch()` 워커가 L238/244/246/272에서 `work.ctxEpoch()==s.contextEpoch`를 게이트하므로 **구조적으로 커버됨을 확인**. 그러나 독립 리플레이 JUnit 실행 증거는 없음 → V-1로 이동.

### B-2. Fold 백그라운드 수음 — "복귀 복구"만 반영, "떠 있는 동안 연속 수음"은 미구현
- **반영 확인**: `app.js` L196-204 — `visibilitychange`(hidden 시 PAUSE 메시지/visible 시 `client.start()`+`voice.resume()`), `pagehide`에서 `frozenCapture=voice.isActive()`+`voice.stop()`+`dispose()`, `pageshow` persisted 시 재시작. `display-voice.js` `resume()`은 suspended `AudioContext`만 재개(closed/사용자 stop은 false). 진단 필드 `frames`/`lastFrameAt`/`lastSendAt`/`reconnects`/`segments`/`events` + 서버 `testStatus.lastAudioReceivedAt`/`lastTranscriptReceivedAt`(DisplayConversateController L478) + `app.js` debug() 출력.
- **미반영/불가 영역**: 다른 탭/앱/화면잠금 중 **연속 수음**은 Chrome freeze/discard 특성상 현재 웹 경로로 불가능할 수 있음(handoff도 동일 판단). 현재 코드는 return-recovery뿐. 수용 기준 30s/2min/5min 그리드 실측 후 네이티브 헬퍼 필요 여부를 결정해야 함.
- **수정 대상(결정 후)**: `app.js` visibility/pageshow 경로 + `display-voice.js` resume/renew 경로, 또는 범위 밖(네이티브) — 결정 전 소스 수정 금지 영역.

### B-3. Grok handoff cycle-01 3파일 — 트리에는 적용, 장부 상태는 `hold`
- **현재**: `scripts/agent_preflight.py`(sha `246e0a96`==postimage), `scripts/test_agent_guard.py`(`b83d6c72`==postimage), `__patch_drop__/source_edit_session.ps1`(`745ad3e1`==postimage) — 변경이 실제 트리에 있음. `handoff.json`의 `cycleStatus`는 cycle-02가 JUnit을 재작성하면서 `postimage-drift` `hold`로 남음.
- **남은 것**: 기능상 반영됨 — ledger 정리(해당 cycle hold 해소 또는 사유 주석)만 남은 사무 항목.

---

## C. 검증 필요 (소스는 있으나 증거 부족 / 의도 확인 필요)

| # | 항목 | 관련 리포트 | 현재 소스 위치 | 필요한 검증 | 우선순위 |
|---|---|---|---|---|---|
| V-1 | `ConversateHintContextReplayCasesTest` 실행 | `hint-context-verify-handoff-888c01f0` | `src/test/java/com/example/lms/assist/ConversateHintContextReplayCasesTest.java`(sha `fe95b91c`, 트리에 존재) | 당시 `ConversateHintContextTest.java` UTF-8 깨짐(0x95)으로 `compileTestJava` 전체 차단 → 현재 파일은 utf8-ok(sha `9d0d16b3`==postimage)로 해결됨. `gradlew test --tests ConversateHintContextReplayCasesTest --tests ConversateHintContextTest` 재실행 필요 | P1 |
| V-2 | 힌트-맥락 패치군의 wear 런타임/실기 반영 | `hint-context-fold-listen-verify-9b78634a` journal(closed, `partial`: "runtime not started; unowned") | `ConversateSessionService.java`(sha `85f709c0`==sealed postimage) | wear 런타임 미재시작 상태에서 소스만 검증됨. 재기동 후 `Debug-Meta-Display.bat -Json` + lens-settings 라운드트립 확인. unowned 런타임이라 사용자 승인 필요 | P1 |
| V-3 | codex-review `mode:review` 실검증 3경로 | `codex-review-mcp-state-report-20260920.md` §5 P2 | `scripts/awx_codex_review_adapter.py` L363-364(owned worker 필요) | Devin/Codex/Grok 각 경로로 `mode:"review"`+`reviewProfile:"economy"` 1회씩 + `audit_log` 지정. 유료/승인 → spend-guard 적용, 승인 후 1회만 | P2 |
| V-4 | `ask` 승인 규칙 실효성 시험 | 같은 보고서 §5 P7 | `~/.codex/config.toml`, `.devin/` 부재 확인됨 | 미승인 review 요청이 실제로 프롬프트/차단되는지 1회 시험(선택) | P3 |
| V-5 | 회귀 감사 P1/P2 의도 확인 | `CODEX_SESSION_SOURCE_REGRESSION_AUDIT_20260919.md` | `ChatGenerationAdmissionFilter.java` L44-48/L87-96/L120-144(local/demo bypass+503 fail-closed 그대로), `PageContentScraper.java` L44-87(여전히 null 반환; 단 TraceStore 진단 L195-238 존재) | P1: non-local 503은 "의도된 fail-closed vs 개선 대상" 결정이 선행(감사도 decision-only로 명시). P2: caller向け null 원인 구분은 미구현 — TraceStore가 있어 사후 진단은 가능하나 반환 계약은 동일 | P2 |
| V-6 | Gemini 3.8 `reasoning_effort`/`max_tokens` 수용 | `llm-e1e6-patch-review-20260919.md` P4 | `GeminiGateway.java` L268-283(`gemini-3.8-` prefix에서 샘플링 필드 생략, `reasoningEffort("low")` 유지) | 유료 호출 없이 문서 근거나 `evidence_needed`로 유지. prefix 정확일치/`models/` 형태 미커버도 동일 문서에 기록됨 | P3 |
| V-7 | E1-E6 패치 + 디버그 G1-G7 등 라이브 미반영분 | `llm-e1e6-patch-review` P5, PROJECT_STATUS §4 | `ConversateApiCueService`/`GeminiGateway`/`debug_rag_stack.ps1` 등 | 적용 당시 wear 런타임 unowned로 미재시작 — 다음 재기동 후 확인 항목으로 묶어 관리 | P2 |

---

## D. 확인 결과 "이미 반영됨"으로 제외한 항목 (오독 방지)

- `ConversateHintContextTest.java` UTF-8 깨짐(0x95@1264) — 현재 utf8-ok, sha `9d0d16b3`==cycle-01 postimage. `contextResetDropsDelayedPreResetTranscript` 포함 11/11 통과.
- `preResetAudio`/`contextResetAudioMark` 지연 전사 펜싱 — `ConversateSessionService.java` L157/184/251/305/382/397-401/573 모두 존재, sealed→verified.
- `hint-input-window-patch-3424c7ed`의 서버측 입력창(4턴/1600자/use-past) + 본 task의 Fold UI — 적용됨.
- `llm-api-e1-e6-patch-575ff9fd` 본체(E1 큐경로 quota, E2 Gemini 3.8 샘플링 생략, E3 주석, E4 삭제, E5/E6 문서) — 소스·테스트 확인됨.
- `debug-gap-fix-report-20260919.md` → `debug-gap-g1-d6f310da` G1-G7 적용·검증됨.
- `.devin/hooks.v1.json`, `scripts/agent_preflight.py`, session-watch/work-guard 계열 — 존재·검증됨.
- 루트 구형 문서 격리 — `amp-playbook.md` 등은 `docs/legacy/`로 이동됨(`EXTERNAL_SKILLS.md`는 보고서의 KEEP 판정대로 루트 유지).
- `SearchProbeController` 404/401 — 감사가 "의도된 safeguard"로 판정, 변경 대상 아님.

## E. 진행 중·잔여 레코드 (후속 작업 시 주의)

- `repo-audit-top3-b0b2e05b` (devin, `in_progress`) — brief만 존재, 산출물 없음. 본 리포트의 미반영 목록과 독립.
- `display-cycle-ssot-leftovers-3952187d` (grok, `in_progress` 잔류 + lease 만료) — journal `note --kind hold` 또는 close로 정리 필요. A-2 적용 시 lease 재취득.
- `hint-context-verify-handoff-888c01f0` cycle-01 `hold`(postimage-drift) — B-3 참조, 장부 정리 필요.
- 미기록 외부 변경은 관찰되지 않음(대조한 파일의 sha256이 journal/checkpoint의 postimage와 일치).

## F. 권장 처리 순서 — 2026-09-23 기준 갱신

**완료됨(재작업 금지)**: A-1, A-3, A-4, A-5(cycle-02로 완결), A-7(cycle-02), A-8, A-9, B-1.

**남은 작업 우선순위**:

1. **Self-Ask 실행 배선(신규 발견)** — planner 빈 등록됐으나 `exec=not_wired`. flag-gated 배선 + 전용 테스트(cycle-03).
2. **A-2**(stale windsurf 룰) — lease 회수 불가로 차단 중. 사용자 명시 지시 또는 owner 증거 확보 시 적용.
3. **B-3 장부 정리** — cycle-01 `hold` 사유 주석.
4. **A-6** — 사용자 결정(개발용 무제한 유지 vs shared-read 분리) 후 적용.
5. **V-1**(이번 task에서 실행) → **V-2/V-7**(wear 재기동 승인 후) → **V-3/V-4**(spend-guard 하 승인 후 1회씩).
6. **chat-ux 계열** — 활성 lease `chat-ux-layered-surfaces` owner의 범위. cytoscape 제거+계약 테스트 동반 수정은 그쪽에서 처리.
7. **V-5 의도 확인 / V-6 문서 근거** — 결정 사항으로 유지.

> 본 리포트는 읽기-대조 전용으로 작성됐으며 소스 변경 0건. 각 항목의 "현재 소스" 표기는 위 시각의 로컬 파일 기준이다. §0 표가 최신 판정.
