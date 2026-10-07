# demo-1 Codex Operating Rules
<!-- BEGIN DEMO1-CORE-AUTO -->
## Core auto rules (read first — moved detail lives in `docs/agents-rules/`)
- AUTO (Self-Ask) is the default for reversible local work — ASK_ONCE only for irreversible/cost/policy-owned asks, HOLD only for real blockers. 상세: `docs/agents-rules/DEMO1-VIBE-SELFASK-JUDGE-AUTO.md`
- 선택 카드(request_user_input*)·자유 문장 질문·BLOCKED 기록 전 `scripts/codex_question_classifier.py --options`(목표 본문은 `--objective`) 필수: AUTO면 묻지 말고 picked로 진행, ASK_ONCE도 기본값 표시 후 다음 작업 계속·무응답이면 안전 기본값으로 넥스트(상세 `$demo1-codex-auto-decide` NO-WAIT).
- Auth stays PROTO_OPEN: no extra role gates or login requirements; an admin-login-block check is never a completion condition. 상세: `docs/agents-rules/DEMO1-PROTOTYPE-AUTH-LIGHT.md`
- Agent-work cost order: Codex credits → external paid API → free → local Ollama (last). Separate scope: product main chat is API/OAuth-first, Ollama last (user 2026-10-02); RAG·embed keep 3090-local. 상세: `docs/agents-rules/DEMO1-RTX3090-WATCH.md`
- Several chats may share this tree: take the file/target lease before edits; intentional parallel chats need a lane plan + quota first. 상세: `docs/agents-rules/DEMO1-CODEX-PARALLEL-LANES.md`
- Forbidden: push/pull/fetch/merge/rebase, `add -A`/`add .`/`commit -a`, reset/checkout/restore/stash/clean, remote mutation, history rewrite, secret reads, blanket `:test` suites, skip-permissions advocacy. Sole commit path: `agent_git_vibe_commit.py`. 상세: `docs/agents-rules/DEMO1-GIT-LOCAL-FIRST.md`
- New rule blocks: write the detail in `docs/agents-rules/<BLOCK-ID>.md` and keep only a 2-line pointer stub here, then run `python -B scripts/agents_md_budget.py check`. 상세: `docs/agent-tooling/agents-md-budget-ko.md`
<!-- END DEMO1-CORE-AUTO -->
<!-- BEGIN DEMO1-PROJECT-ROOT -->
## Project Root (default working directory)
- **Project Root** for this checkout is exactly `C:\AbandonWare\demo-1\demo-1\src`. Investigate, search, edit, build, Start-RAG, DevWatch, and verification default to this root; relative paths (`Start-RAG.bat`, `scripts\`, `main\java`, `AGENTS.md`, `.agents\skills`) resolve from here.
- Use a different path only when the user explicitly names one (or a skill names a sibling path for a bounded side artifact). `C:\AbandonWare\demo-1` or `demo-1\demo-1` alone are not the code root.
<!-- END DEMO1-PROJECT-ROOT -->
<!-- BEGIN DEMO1-PRIMARY-SURFACE -->
## Primary surface (judge all "chat works"/boot verdicts here)
- Primary surface = main `/chat` chat-ui — public `https://abandonwareai.kro.kr/chat`, local `http://127.0.0.1:18180/chat`; SSOT `docs/PRIMARY_SURFACE.md`. The interview page (`static/assets/interview/*`) is a local debug-only screen — keep it, but it is not the product surface; interview-page results are auxiliary evidence, never a main-surface verdict.
<!-- END DEMO1-PRIMARY-SURFACE -->
<!-- BEGIN DEMO1-ANONYMOUS-VIBE-DEFAULT -->
## Anonymous-first vibe coding
- 익명 우선 기본값 + demo.interview.enabled 의미 — 인증 작업 전 필독. — 상세: `docs/agents-rules/DEMO1-ANONYMOUS-VIBE-DEFAULT.md`
<!-- END DEMO1-ANONYMOUS-VIBE-DEFAULT -->
<!-- BEGIN DEMO1-OLLAMA-MODEL-LOCK -->
## DEMO1 Ollama Model Lock (DESKTOP-M5NOV6K)
- Ollama 모델 잠금/허용 목록 — 모델 배선 전 필독. — 상세: `docs/agents-rules/DEMO1-OLLAMA-MODEL-LOCK.md`
<!-- END DEMO1-OLLAMA-MODEL-LOCK -->
<!-- BEGIN DEMO1-GPU-LANE-EVIDENCE -->
## GPU lane evidence (DESKTOP-M5NOV6K, RTX 3060 + 3090)
- 듀얼 GPU 증거 체인+사전 스냅샷 — GPU/Ollama 작업 전 필독. — 상세: `docs/agents-rules/DEMO1-GPU-LANE-EVIDENCE.md`
<!-- END DEMO1-GPU-LANE-EVIDENCE -->
<!-- BEGIN SHARED-PROJECT-RESOURCES -->
## Project resources at task entry
- 태스크 진입 시 device bus/공유 리소스와 .secrets 경계 — 세션 시작 절차를 확인할 때. — 상세: `docs/agents-rules/SHARED-PROJECT-RESOURCES.md`
<!-- END SHARED-PROJECT-RESOURCES -->
<!-- BEGIN DEMO1-AUTONOMOUS-SAFE-WORK -->
## Vibe coding: autonomous continuation and recovery
- 자율 계속·완료 정지($demo1-goal-complete-stop) — 완료 선언 전 필독. — 상세: `docs/agents-rules/DEMO1-AUTONOMOUS-SAFE-WORK.md`
<!-- END DEMO1-AUTONOMOUS-SAFE-WORK -->
<!-- BEGIN DEMO1-WORK-LEDGER -->
## Work Ledger: status, journal, per-change backup (Git-free)
- 작업 원장: PROJECT_STATUS 읽기 → journal open → checkpoint → 검증 기록. 파일 변경 작업 필수. — 상세: `docs/agents-rules/DEMO1-WORK-LEDGER.md`
<!-- END DEMO1-WORK-LEDGER -->
<!-- BEGIN DEMO1-AGENT-GUARD-COMMON -->
## Common Guard Entry Points (Codex / Grok / Devin / Cline)
- 공통 가드 진입점: preflight/스킬 라우터/checkpoint/조건부 Git/워치독 명령 모음·보호 범위 해석. — 상세: `docs/agents-rules/DEMO1-AGENT-GUARD-COMMON.md`
<!-- END DEMO1-AGENT-GUARD-COMMON -->
<!-- BEGIN DEMO1-DB-AGENT-SSOT -->
## Local DB agent entry (lmsdb file H2)
- 로컬 H2(lmsdb) db_agent.py CLI — DB 읽기/쓰기와 lock(exit 3) 의미. — 상세: `docs/agents-rules/DEMO1-DB-AGENT-SSOT.md`
<!-- END DEMO1-DB-AGENT-SSOT -->
<!-- BEGIN DEMO1-GOAL-SWITCH -->
## Goal-Switch Barrier
- 목표 전환 배리어: 새 목표 전 stale journal/lease 정리와 reject-complete. — 상세: `docs/agents-rules/DEMO1-GOAL-SWITCH.md`
<!-- END DEMO1-GOAL-SWITCH -->
<!-- BEGIN DEMO1-STAGED-METHOD -->
## Staged Method (default work order)
- 기본 작업 순서 = 지시서 1개 → 스킬 resolve → 사실 → 작은 단계 → 검증 → 닫기 → 다음. — 상세: `docs/agents-rules/DEMO1-STAGED-METHOD.md`
<!-- END DEMO1-STAGED-METHOD -->
<!-- BEGIN DEMO1-CODEX-GOAL-INTAKE-CONTINUE -->
## Codex goal intake ≠ Done
- goal-objective 읽기는 intake이지 Done이 아니다 — 완료 주장 전 확인. — 상세: `docs/agents-rules/DEMO1-CODEX-GOAL-INTAKE-CONTINUE.md`
<!-- END DEMO1-CODEX-GOAL-INTAKE-CONTINUE -->
<!-- BEGIN DEMO1-STALE-HANDOFF-REFERENCE -->
## Stale Handoffs And Finished Goals
- 낡은 인계 문서는 참조만 — 라이브 지시와 충돌 시 policy-conflict 기록. — 상세: `docs/agents-rules/DEMO1-STALE-HANDOFF-REFERENCE.md`
<!-- END DEMO1-STALE-HANDOFF-REFERENCE -->
<!-- BEGIN DEMO1-ASK-STEP-SEARCH -->
## Ask, Search, Stepwise Delivery
- 질문 전 웹서치/단계적 전달 — 모호·불가역·비용 분기에서 묻는 절차. — 상세: `docs/agents-rules/DEMO1-ASK-STEP-SEARCH.md`
<!-- END DEMO1-ASK-STEP-SEARCH -->
<!-- BEGIN DEMO1-CODEX-AUTO-DECIDE -->
## Codex Auto-Decide Defaults
- 선택 질문 전 자동 결정 기본표(codex_question_classifier.py) — 승인 퀴즈 전에. — 상세: `docs/agents-rules/DEMO1-CODEX-AUTO-DECIDE.md`
<!-- END DEMO1-CODEX-AUTO-DECIDE -->
<!-- BEGIN DEMO1-STACK-FIT-GATE -->
## Stack-Fit Gate
- 새 기술·서버·데몬·SaaS·재작성: stack_fit_guard 판정 후 부분 DECLINE+대안. — 상세: `docs/agents-rules/DEMO1-STACK-FIT-GATE.md`.
<!-- END DEMO1-STACK-FIT-GATE -->
<!-- BEGIN DEMO1-GROK-SUBSCRIPTION-REVIEW -->
## Grok Subscription Review
- 명시적 Grok 요청/독립 리뷰 필요 시 — 실행 전 수용 윈도우 증거 필요. — 상세: `docs/agents-rules/DEMO1-GROK-SUBSCRIPTION-REVIEW.md`
<!-- END DEMO1-GROK-SUBSCRIPTION-REVIEW -->
<!-- BEGIN DEMO1-TRIAD-DELIBERATION -->
## Triad Deliberation And Safe Integration
- 비자명 판정의 긍정/부정/중립 삼자 심의 — 언제 건너뛰는지 포함. — 상세: `docs/agents-rules/DEMO1-TRIAD-DELIBERATION.md`
<!-- END DEMO1-TRIAD-DELIBERATION -->
<!-- BEGIN DEMO1-CORE-REQUEST-ROUTER -->
## Core Request Entry (Display / RAG / LLM)
- Display/RAG/LLM/API 코어 요청의 단일 primary 스킬 분류 — 코어 작업 진입. — 상세: `docs/agents-rules/DEMO1-CORE-REQUEST-ROUTER.md`
<!-- END DEMO1-CORE-REQUEST-ROUTER -->
<!-- BEGIN DEMO1-TRI-SYSTEM-SEAM-ISOLATION -->
## Tri-System Seam Isolation (Display / RAG / Main Chat)
- 삼중 시스템 표면·프롬프트·검색·모델 라우팅 혼동 원천 격리 5대 불변 — 상세: `docs/agents-rules/DEMO1-TRI-SYSTEM-SEAM-ISOLATION.md`
<!-- END DEMO1-TRI-SYSTEM-SEAM-ISOLATION -->
<!-- BEGIN DEMO1-CODEX-PLUGIN-ROLES -->
## Codex plugin roles (per work type)
- 작업 유형별 Codex 플러그인 활성화 표와 보고서 PLUGIN_USAGE 의무. — 상세: `docs/agents-rules/DEMO1-CODEX-PLUGIN-ROLES.md`
<!-- END DEMO1-CODEX-PLUGIN-ROLES -->
<!-- BEGIN DEMO1-CODEX-HOTFIX-TOOLKIT -->
## Codex 핫픽스 도구 인덱스
- Codex R2/Jev 핫픽스 판정 도구 인덱스 — 단계당 도구 하나. — 상세: `docs/agents-rules/DEMO1-CODEX-HOTFIX-TOOLKIT.md`
<!-- END DEMO1-CODEX-HOTFIX-TOOLKIT -->
<!-- BEGIN DEMO1-DEVIN-SOURCE-ORCHESTRATOR -->
## Devin / multi-seam source orchestration
- 붙여넣은 멀티-심 지시서의 단계 분해(devin_task_orchestrate.py plan). — 상세: `docs/agents-rules/DEMO1-DEVIN-SOURCE-ORCHESTRATOR.md`
<!-- END DEMO1-DEVIN-SOURCE-ORCHESTRATOR -->
- 오케스트라 시너지(신호→lane→현황판→붙여넣기): `$demo1-orchestra-synergy` (`.agents/skills/demo1-orchestra-synergy/SKILL.md`) — 멀티에이전트 흐름 신호 저장소 `data/agent-handoff/orchestra/`, 자동 전송 없음.
<!-- BEGIN DEMO1-META-RAYBAN-DISPLAY-RUNTIME -->
## Meta Ray-Ban Display runtime (short)
- 렌즈 표시 계약·설정 우선·DevWatch/ForceRestart — Display 출력/주기 작업. — 상세: `docs/agents-rules/DEMO1-META-RAYBAN-DISPLAY-RUNTIME.md`
<!-- END DEMO1-META-RAYBAN-DISPLAY-RUNTIME -->
<!-- BEGIN DEMO1-BRAVE-DUAL-KEY -->
## Brave dual-key (Free then Base)
- Brave Free→Base 듀얼 키 라우팅 — 검색 API 키/쿼터 작업. — 상세: `docs/agents-rules/DEMO1-BRAVE-DUAL-KEY.md`
<!-- END DEMO1-BRAVE-DUAL-KEY -->
<!-- BEGIN DEMO1-PROVIDER-LIMITS-SSOT -->
## Provider limits SSOT
- 제공자별 요금 한도 문서 SSOT — 한도·플랜 재질문 금지 규칙. — 상세: `docs/agents-rules/DEMO1-PROVIDER-LIMITS-SSOT.md`
<!-- END DEMO1-PROVIDER-LIMITS-SSOT -->
<!-- BEGIN DEMO1-OPENROUTER-DESKTOP-ROUTING -->
## OpenRouter Desktop routing
- OpenRouter/Space Bunny 보조 조사 역할 — 프로덕션 배선 금지. — 상세: `docs/agents-rules/DEMO1-OPENROUTER-DESKTOP-ROUTING.md`
<!-- END DEMO1-OPENROUTER-DESKTOP-ROUTING -->
<!-- BEGIN DEMO1-CONVERSATE-HINT-EVIDENCE -->
## Conversate hint evidence (Fold6 / Meta Display)
- Conversate 힌트의 근거 라우팅·과거 맥락 윈도우 — 힌트 표시 문제. — 상세: `docs/agents-rules/DEMO1-CONVERSATE-HINT-EVIDENCE.md`
<!-- END DEMO1-CONVERSATE-HINT-EVIDENCE -->
<!-- BEGIN DEMO1-EVIDENCE-ZERO-RELEASE -->
## Answer release: zero citable evidence = publish, not HOLD
- 근거 0 = 본문 공개(HOLD 아님) — 답변 보류/공개 판정. — 상세: `docs/agents-rules/DEMO1-EVIDENCE-ZERO-RELEASE.md`
- 검증기 fail-soft(판정불능) ≠ HOLD — unknown/fail-soft는 본문 유지+메모리 금지. — 상세: 동 문서 fail-soft 절
<!-- END DEMO1-EVIDENCE-ZERO-RELEASE -->
<!-- BEGIN DEMO1-NOVA-FOCUS -->
## Nova Focus ('노바' wake-word focused conversation)
- 노바 wake-word 포커스 대화 — focus 필드/idle 규칙, hint와 분리. — 상세: `docs/agents-rules/DEMO1-NOVA-FOCUS.md`
<!-- END DEMO1-NOVA-FOCUS -->
<!-- BEGIN DEMO1-INVISIBLE-EYE-AUTO -->
## Desktop Request Routing
- 설명되지 않는 동작·숨은 조건 분류 — Desktop 요청 라우팅. — 상세: `docs/agents-rules/DEMO1-INVISIBLE-EYE-AUTO.md`
<!-- END DEMO1-INVISIBLE-EYE-AUTO -->
<!-- BEGIN DEMO1-SOURCE-DIRECTIVE-AUTO -->
## Desktop Source Directive Auto-Execution
- 정확한 SourceDirective 자동 실행 경로와 권한 경계. — 상세: `docs/agents-rules/DEMO1-SOURCE-DIRECTIVE-AUTO.md`
<!-- END DEMO1-SOURCE-DIRECTIVE-AUTO -->
<!-- BEGIN DEMO1-META-DISPLAY -->
## Meta Ray-Ban Display Tasks
- Meta Display webapp/동기 클라이언트/검증 스킬 입구 — Display 태스크. — 상세: `docs/agents-rules/DEMO1-META-DISPLAY.md`
<!-- END DEMO1-META-DISPLAY -->
<!-- BEGIN DEMO1-SPRING-VIBE-RELOAD -->
## Spring / Start-RAG vibe reload (Java changes must rebuild)
- Java 변경 후 DevWatch/ForceRestart + Verify-RAG — 재빌드·라이브 반영. — 상세: `docs/agents-rules/DEMO1-SPRING-VIBE-RELOAD.md`
<!-- END DEMO1-SPRING-VIBE-RELOAD -->
<!-- BEGIN DEMO1-SERVER-LIFECYCLE-VERIFY -->
## Server start/stop + post-edit live verification
- Start/Close/Verify BAT 표와 자율 재기동·freshness 증거 규칙. — 상세: `docs/agents-rules/DEMO1-SERVER-LIFECYCLE-VERIFY.md`
<!-- END DEMO1-SERVER-LIFECYCLE-VERIFY -->
<!-- BEGIN DEMO1-DEBUG-ENTRYPOINTS -->
## Debug entry points (Debug-RAG / Debug-Meta-Display)
- Debug-RAG/Debug-Meta-Display 진입점과 verify 액션 의미. — 상세: `docs/agents-rules/DEMO1-DEBUG-ENTRYPOINTS.md`
<!-- END DEMO1-DEBUG-ENTRYPOINTS -->
<!-- BEGIN DEMO1-RAG-DEBUG-TRAIL -->
## RAG/LLM debug trail (skill-free first read)
- Read-RAG-Debug 스킬 없는 첫 진단 경로와 TRAIL 분류. — 상세: `docs/agents-rules/DEMO1-RAG-DEBUG-TRAIL.md`
<!-- END DEMO1-RAG-DEBUG-TRAIL -->
<!-- BEGIN DEMO1-AGENT-PORT-LEASE -->
## Agent dynamic port lease
- 에이전트 동적 포트 lease 계약(agent_port_lease.py). — 상세: `docs/agents-rules/DEMO1-AGENT-PORT-LEASE.md`
<!-- END DEMO1-AGENT-PORT-LEASE -->
<!-- BEGIN DEMO1-TOOLCHAIN-AUTO-SELECT -->
## Toolchain auto-select (vibe verify loop)
- 빌드/테스트 도구 자동 선택 — Gradle 우선, 검증 루프. — 상세: `docs/agents-rules/DEMO1-TOOLCHAIN-AUTO-SELECT.md`
<!-- END DEMO1-TOOLCHAIN-AUTO-SELECT -->
<!-- BEGIN DEMO1-ASSET-PRESERVATION -->
## Goal Asset Preservation
- 자산 삭제/비활성 가능 작업의 보존 인벤토리 규칙. — 상세: `docs/agents-rules/DEMO1-ASSET-PRESERVATION.md`
<!-- END DEMO1-ASSET-PRESERVATION -->
<!-- BEGIN DEMO1-BUILD-PRUNE-RULE -->
## Build artifact prune (pre-approved)
- build/ 하위 사전 승인 정리 범위(prune_build_artifacts.ps1). — 상세: `docs/agents-rules/DEMO1-BUILD-PRUNE-RULE.md`
<!-- END DEMO1-BUILD-PRUNE-RULE -->

## Concurrent Desktop and Notebook Editing
- Desktop/Notebook 동시 편집의 target-scoped 조정과 lease begin/verify 규칙. — 상세: `docs/agents-rules/SECTION-concurrent-desktop-and-notebook-editing.md`
<!-- BEGIN DEMO1-LEASE-LIFECYCLE -->
## Source-edit lease lifecycle (begin → work → end, no leftovers)
- 소스 편집 lease 수명주기: begin→heartbeat→end, stale reclaim, live 금지. — 상세: `docs/agents-rules/DEMO1-LEASE-LIFECYCLE.md`
<!-- END DEMO1-LEASE-LIFECYCLE -->

<!-- BEGIN DEMO1-DEVIN-MULTI-SESSION -->
## Multiple Concurrent Devin Sessions
- 동일 체크아웃 다중 Devin 세션 격리·충돌·종료 규칙. — 상세: `docs/agents-rules/DEMO1-DEVIN-MULTI-SESSION.md`
<!-- END DEMO1-DEVIN-MULTI-SESSION -->
<!-- BEGIN DEMO1-DEVIN-DIRECTIVE-LOOP -->
## External-Agent Directive Loops (Devin report ↔ follow-up directive)
- Devin 보고서 회신/지시서 작성 루프 — DRAFT/REVIEW/CLOSE. — 상세: `docs/agents-rules/DEMO1-DEVIN-DIRECTIVE-LOOP.md`
<!-- END DEMO1-DEVIN-DIRECTIVE-LOOP -->
<!-- BEGIN DEMO1-GEMINI-SEARCH-WORKER -->
## Gemini Search Worker (external-spec subagent)
- gemini_search_worker 한 줄 장착: 공식 문서·최신 사양 교차검증 전용, 실행 SSOT=scripts/gemini_search_worker.py. — 상세: `docs/agents/DEMO1-GEMINI-SEARCH-WORKER.md`
<!-- END DEMO1-GEMINI-SEARCH-WORKER -->

## Runtime Boundary And Active Runtime Map
- Treat the active runtime surface as evidence, not memory. Reconfirm Gradle settings and sourceSets before editing.
- Active runtime/test: root `main/java`, `main/resources`, `src/test/java` (build.gradle.kts:775-794). `:app` is empty since 2026-10-01. Treat `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, backups, archives and build outputs as reference unless Gradle proves otherwise.
- Canonical seams, alias warnings, entry/boot guards (ExtremeZ, Overdrive, DPP, fusion/CVaR, CFVM, time-budget, PII case-sensitivity, fail-soft providers, PromptBuilder boundary): `.agents/skills/demo1-rag-strategy-orchestration/references/strategy-map.md` — read before patching any of them.

## Desktop / Mac Mini / Notebook Workspaces
- Desktop original/final verification area: `C:\AbandonWare\demo-1\demo-1\src`. PatchDrop/local-worktree is the default for Mac mini producers and Notebook evidence-only work; non-guarded edits belong in separate worktrees/clones on dedicated `agent/<node>/<topic>` branches.
- Notebook reads use canonical `Y:\`; `SMB_ACCESS`/audit-only outputs keep `canonicalWorkspace=Y:\` with `sourceWriteRoot=null` + `authorizedMutation=false` — a canonical workspace is not write authorization. Backing identity: `scripts\verify_ydrive_backing_identity.ps1` (`backingShareIdentityVerified`); mismatch/missing = `smb-root-identity-changed` -> `HOLD`. An authorized Notebook direct edit emits `YDRIVE_SMB_GUARDED_DIRECT`: run `$demo1-macsrc-smb-direct-patch` from `Y:\` with declared relative targets; never redirect source or patch traces to OneDrive.
- PatchDrop exchange goes through `__patch_drop__\`; apply in Desktop only after diff, secret scan, checksum, `git apply --check`, command-evidence review; contract: `$patchdrop-safe-patch-orchestrator`. Check concurrency evidence first (`source_edit_session.ps1 -Action status -TargetManifest`, pending `.patch`, preimage hashes); a bare `.git\index.lock` is not a global stop — classify conflicts lane-locally. Multi-machine builds: keep `AWX_SPLIT_BUILD_OUTPUTS`/`AWX_BUILD_HOST_ID`; isolate `GRADLE_USER_HOME` and host-local `--project-cache-dir`.

## Safe Patch Rules
- Patch only the confirmed blocker with the fewest files and lines needed. Do not add duplicate wrappers, helpers, routes, shadow implementations, or new orchestration frameworks.
- Preserve existing property names and secret flow. Do not rename, delete, normalize, or restructure any `openssl` or `opnessl` key/value/name.
- Keep Spring Boot on the existing repo version. Keep every `dev.langchain4j` dependency exactly on `1.0.1`; stop and report if Gradle evidence shows mixed, beta, or non-`1.0.1` LangChain4j versions.
- Do not graft code from archives, SQL backups, UAW files, or memory unless the file is part of the active sourceSet and the current task proves it is required.

## Prompt, Search, And Provider Hygiene
- Final RAG prompt construction must stay on `PromptBuilder.build(PromptContext)` or the existing equivalent prompt boundary — no ad hoc string concatenation in ChatService paths.
- Optional external providers must fail soft when credentials are missing, blank, dummy, `test`, `changeme`, `sk-local`, or unresolved `${...}` placeholders: provider disabled state, explicit `disabledReason`, no outbound call, redacted diagnostics.
- Never emit fake search results. Classify empty provider output separately from after-filter starvation, timeout, rate limit, provider-disabled, and missing-key states.

## Codex Computer And Environment Autostart
- Computer Use 플러그인 우선순위와 Ollama/로컬 LLM 자동 시작 규칙. — 상세: `docs/agents-rules/SECTION-codex-computer-and-environment-autostart.md`
## Skill And Prompt Routing
<!-- BEGIN DEMO1-VIBE-SKILL-ROUTER -->
- 모든 vibe 요청의 단일 primary 스킬 resolve 절차. — 상세: `docs/agents-rules/DEMO1-VIBE-SKILL-ROUTER.md`
- 난이도 3티어 승격·토큰 예산 — 상세: `docs/agents-rules/DEMO1-SKILL-PERFORMANCE-TIERING.md`
<!-- END DEMO1-VIBE-SKILL-ROUTER -->
<!-- BEGIN DEMO1-TOOL-PLACEMENT-SCAN -->
## Tool placement scan (advisory, reuse-only)
- 기존 도구 우선순위 2차 의견 스캔(자문 전용). — 상세: `docs/agents-rules/DEMO1-TOOL-PLACEMENT-SCAN.md`
<!-- END DEMO1-TOOL-PLACEMENT-SCAN -->
<!-- BEGIN DEMO1-ADAPTIVE-RULE-LAB -->
- 스킬/룰 의미론적 정리·실험 — 병합/삭제 권한 없음. — 상세: `docs/agents-rules/DEMO1-ADAPTIVE-RULE-LAB.md`
<!-- END DEMO1-ADAPTIVE-RULE-LAB -->
- Repeated HOLD / index lock / partial GPU failure -> `$scoped-blocker-recovery`; HOLD stays dependency-scoped. Keep this root file to durable repository rules: read `.agents/skills/INDEX.md` only when a specialized workflow is needed; long execution prompts stay under `agent-prompts`; do not load the whole index for ordinary narrow work.
- Select exactly one primary route **per phase**; add a second guard only when independently required. A pasted brief with two independent seams uses `$demo1-devin-source-orchestrator` to sequence phases — not a duplicate route. Route identity = `(kind, canonicalId)`; same-namespace duplicates/collisions fail; verify every route's `source`/`pairedArtifact` against actual repo-relative paths.
- Before application-source mutation, the target-scoped source-owner/lease/preimage/protected-setting/factual checks are mandatory. `$demo1-source-edit-three-way-preflight` is optional review — not a routine prerequisite. Named gates: `$demo1-macsrc-smb-direct-patch` (authorized `Y:\` work), `$patchdrop-safe-patch-orchestrator` (PatchDrop routing), `$demo1-subsystem-patch-directive` (S01-S08 bodies), `$demo1-cross-subsystem-guard` (2+ S01-S08 seams), `demo1_notebook_desktop_goal_handoff` (Notebook -> Desktop GoalContract).

## PatchDrop Bundle Rules
- Full producer/consumer contract — complete-bundle parts, exactly one active cumulative `<slug>-v3` per slug, `sourceIsolation` fail-closed promotion, nested-bundle promotion, MISSING_PATCH/MISSING_REPORT handling, applied/rejected moves after Gradle verification: `$patchdrop-safe-patch-orchestrator` (`.agents/skills/patchdrop-safe-patch-orchestrator/SKILL.md`).
- Never choose a PatchDrop patch by newest timestamp; an ambiguous queue = `patch-drop-pending`. Acquire the source-edit lease as `desktop-consumer` before applying a top-level bundle. Run `__patch_drop__/janitor_inventory.ps1` first.

## AutoLearn Handoff Review
- AutoLearn 실패 패치 전 handoff 파일 확인 순서와 train_rag.jsonl SoT. — 상세: `docs/agents-rules/SECTION-autolearn-handoff-review.md`
## Redaction
- Never log or print raw API keys, client secrets, owner tokens, authorization headers, private environment values, raw sensitive queries, or full environment dumps. Prefer `hasKey`, `keySource`, host/path summaries, counts, timing, reason codes, masked tails, and hash-only values (`queryHash`, `bodyHash`, `backupHash`).
- Public evidence, trace, SSE, and HTML surfaces must remain allowlisted and redacted; raw evidence snippets stay out of TraceStore and UI payloads unless an existing gate explicitly promotes them.

## Evidence And Verification
<!-- BEGIN DEMO1-COMPLETION-CLEANUP -->
### Automatic completion and cleanup
- 검증 완료 경계에서의 자동 정리 규칙과 증거 바인딩. — 상세: `docs/agents-rules/DEMO1-COMPLETION-CLEANUP.md`
<!-- END DEMO1-COMPLETION-CLEANUP -->
- 증거 우선순위·PASS 부분 보고·빌드 산출물/동시성 검증 세부 규칙. — 상세: `docs/agents-rules/SECTION-evidence-and-verification.md`
- Keep a blocker lane-local and continue independent provable work; never expand a lane-local blocker into a repository-wide `HOLD`. Use Windows/PowerShell-first commands; prefer `gradlew.bat`; verify the narrowest changed surface first, then broaden only across module boundaries.

<!-- BEGIN DEMO1-REQUEST-DIAGNOSTIC-CORRELATION -->
### Same-request diagnostic evidence
- 디버그 요청 상관관계 근거 분리 규칙. — 상세: `docs/agents-rules/DEMO1-REQUEST-DIAGNOSTIC-CORRELATION.md`
<!-- END DEMO1-REQUEST-DIAGNOSTIC-CORRELATION -->

<!-- BEGIN DEMO1-LOCAL-FIRST-RAG -->
## Local-First RAG Repair Overlay
- RAG 수리·검증된 채택 보존·진행 중 패치의 승격 조건·정상 rollback 범위. — 상세: `docs/agents-rules/DEMO1-LOCAL-FIRST-RAG.md`
<!-- END DEMO1-LOCAL-FIRST-RAG -->
<!-- BEGIN DEMO1-GIT-LOCAL-FIRST -->
## Local Source First; Conditional Local Git (this canonical root only)
- 조건부 로컬 Git: 허용 명령·커밋 진입점·여전히 금지 목록. — 상세: `docs/agents-rules/DEMO1-GIT-LOCAL-FIRST.md`
<!-- END DEMO1-GIT-LOCAL-FIRST -->
<!-- BEGIN DEMO1-GIT-REMOTE-SOLE -->
## Git remote (sole valid)
- 단일 유효 원격·AbandonWare3 폐기·브랜치 위상 — 원격 변경 금지. — 상세: `docs/agents-rules/DEMO1-GIT-BRANCH-TOPOLOGY.md`
<!-- END DEMO1-GIT-REMOTE-SOLE -->
<!-- BEGIN DEMO1-VIBE-GIT-AUTO-CONTINUE -->
## Vibe Git auto-continue (agreed soft branches never ask)
- Git 소프트 브랜치 자동 계속(퀴즈 없이 AUTO 저널). — 상세: `docs/agents-rules/DEMO1-VIBE-GIT-AUTO-CONTINUE.md`
<!-- END DEMO1-VIBE-GIT-AUTO-CONTINUE -->
<!-- BEGIN DEMO1-VIBE-MAX-AGENCY -->
## Vibe-Max-Agency: 4-agent shared root / evidence / restart contract
- 4-에이전트 공유 루트/증거/재기동 계약 — 최대 범위와 금지. — 상세: `docs/agents-rules/DEMO1-VIBE-MAX-AGENCY.md`
<!-- END DEMO1-VIBE-MAX-AGENCY -->
<!-- BEGIN DEMO1-RTX3090-WATCH -->
## RTX 3090 ACTIVE — power issue RESOLVED (DESKTOP-M5NOV6K)
- RTX 3090 활성·로컬 우선 정책과 헬스 워치 — GPU/API 폴백. — 상세: `docs/agents-rules/DEMO1-RTX3090-WATCH.md`
<!-- END DEMO1-RTX3090-WATCH -->
<!-- BEGIN DEMO1-PROTOTYPE-LIGHT -->
## Prototype light mode (no admin, minimal default surface)
- 프로토타입 라이트 모드 — 기본 도구·기본 OFF 스킬·관리자 불필요. — 상세: `docs/agents-rules/DEMO1-PROTOTYPE-LIGHT.md`
<!-- END DEMO1-PROTOTYPE-LIGHT -->
<!-- BEGIN DEMO1-PROTOTYPE-AUTH-LIGHT -->
## Prototype auth-light mode (PROTO_OPEN)
- 바이브 코딩은 로컬 PROTO_OPEN에서 Codex 로그인 없이 접근; 계정 재요청·추가 인증 강제 금지. — 상세: `docs/agents-rules/DEMO1-PROTOTYPE-AUTH-LIGHT.md`
<!-- END DEMO1-PROTOTYPE-AUTH-LIGHT -->
<!-- BEGIN DEMO1-GOAL-FOOTER-THE-ONE -->
## Codex goal footer (THE ONE protocol)
- Codex 골 푸터(THE ONE 프로토콜) — 골 지시서에 붙이는 문구. — 상세: `docs/agents-rules/DEMO1-GOAL-FOOTER-THE-ONE.md`
<!-- END DEMO1-GOAL-FOOTER-THE-ONE -->
<!-- BEGIN DEMO1-M21222AIN-ADAPTIVE-FALLBACK -->
## m21222ain adaptive fallback + release policy separation (directive 2026-09-24)
- m21222ain 어댑티브 폴백 지시서 SSOT와 소유 레인. — 상세: `docs/agents-rules/DEMO1-M21222AIN-ADAPTIVE-FALLBACK.md`
<!-- END DEMO1-M21222AIN-ADAPTIVE-FALLBACK -->
<!-- BEGIN DEMO1-VERCEL-AI-GATEWAY-CREDIT -->
## Vercel AI Gateway / Jev 비용 메모 (agent-visible)
- Vercel AI Gateway/Jev 크레딧 메모와 실패 분류. — 상세: `docs/agents-rules/DEMO1-VERCEL-AI-GATEWAY-CREDIT.md`
<!-- END DEMO1-VERCEL-AI-GATEWAY-CREDIT -->
<!-- BEGIN DEMO1-COOP-VERIFY-RAILS -->
## Cooperative verification rails (multi-agent deferred verify)
- 협동 검증 레일(coop_verify.py) — DEFERRED는 PASS 아님. — 상세: `docs/agents-rules/DEMO1-COOP-VERIFY-RAILS.md`
<!-- END DEMO1-COOP-VERIFY-RAILS -->
<!-- BEGIN DEMO1-F01B-NARROW-JDBC-ASSIST -->
## F01-B narrow JDBC assist rails (Devin track)
- F01-B narrow JDBC 어시스트 레일(Devin 측 게이트만). — 상세: `docs/agents-rules/DEMO1-F01B-NARROW-JDBC-ASSIST.md`
<!-- END DEMO1-F01B-NARROW-JDBC-ASSIST -->
<!-- BEGIN DEMO1-TRACE-DOCK-ASSIST -->
## Trace-dock always-on assist rails (Devin track)
- Trace-dock always-on 어시스트 레일 — 제품 UI는 Codex 소유. — 상세: `docs/agents-rules/DEMO1-TRACE-DOCK-ASSIST.md`
<!-- END DEMO1-TRACE-DOCK-ASSIST -->
<!-- BEGIN DEMO1-VIBE-SELFASK-JUDGE-AUTO -->
## Vibe Self-Ask judge (approval-quiz reduction)
- 승인 퀴즈 전 POSITIVE/NEGATIVE/반례→중립 판정(AUTO/ASK_ONCE/HOLD). — 상세: `docs/agents-rules/DEMO1-VIBE-SELFASK-JUDGE-AUTO.md`
<!-- END DEMO1-VIBE-SELFASK-JUDGE-AUTO -->
<!-- BEGIN DEMO1-CODEX-SELFASK-TRIAD -->
## Codex Self-Ask triad (UAW 3-axis subagent fan-out)
- 판정 갈림 시 SelfAsk 3축 서브에이전트 패킷 절차. — 상세: `docs/agents-rules/DEMO1-CODEX-SELFASK-TRIAD.md`
<!-- END DEMO1-CODEX-SELFASK-TRIAD -->
<!-- BEGIN DEMO1-CLEAN-QUARANTINE-RAILS -->
## Clean-quarantine mining & vibe AUTO rails (2026-09-29)
- Done/ASK 주장 전 필수 레일 + 격리 재마이닝 도구. — 상세: `docs/agents-rules/DEMO1-CLEAN-QUARANTINE-RAILS.md`
<!-- END DEMO1-CLEAN-QUARANTINE-RAILS -->
<!-- BEGIN DEMO1-APIKIT -->
- 외부 API 작동 확인(apikit.ps1 check)과 실패 분류 SSOT. — 상세: `docs/agents-rules/DEMO1-APIKIT.md`
<!-- END DEMO1-APIKIT -->
<!-- BEGIN DEMO1-P6-RESILIENCE-RULES -->
## P6 복원력·검증·데이터 정합성 5대 지침 (2026-10-01, SSOT)
- P6 복원력·검증·데이터 정합성 5대 지침(요약 SSOT). — 상세: `docs/agents-rules/DEMO1-P6-RESILIENCE-RULES.md`
<!-- END DEMO1-P6-RESILIENCE-RULES -->
<!-- BEGIN DEMO1-CODEX-PARALLEL-LANES -->
## Codex parallel lanes (multi-chat same-tree operation)
- 같은 트리 다중 Codex 채팅의 레인 분할·쿼터·통합 게이트. — 상세: `docs/agents-rules/DEMO1-CODEX-PARALLEL-LANES.md`
<!-- END DEMO1-CODEX-PARALLEL-LANES -->
<!-- BEGIN DEMO1-GROKBOT-ROLE -->
- 점(dot)/Codex 채팅의 Grok Bot 대타 역할 — 지시서·보고 판정. — 상세: `docs/agents-rules/DEMO1-GROKBOT-ROLE.md`
<!-- END DEMO1-GROKBOT-ROLE -->
<!-- AWX-TEST-MODEL-POLICY:BEGIN -->
- RAG/챗 테스트 모델 정책 — 화면 기본 모델 대신 정책 모델 선택. — 상세: `docs/agents-rules/AWX-TEST-MODEL-POLICY.md`
<!-- AWX-TEST-MODEL-POLICY:END -->

<!-- BEGIN DEMO1-AUTH-MODEL-MATRIX -->
## /chat 수정 후 브라우저 매트릭스 (Codex)
- 채팅·스트림·chat UI 수정 후 브라우저 매트릭스 검증. — 상세: `docs/agents-rules/DEMO1-AUTH-MODEL-MATRIX.md`
<!-- END DEMO1-AUTH-MODEL-MATRIX -->
<!-- BEGIN DEMO1-OUTPUT-BUDGET -->
## Tool output budget + session state
- 도구 출력 예산·wait·JSON-parse·patch 재시도·세션 state.md 체크포인트 SSOT. — 상세: `docs/agents-rules/DEMO1-OUTPUT-BUDGET.md`
<!-- END DEMO1-OUTPUT-BUDGET -->
<!-- BEGIN DEMO1-VIBE-OPEN -->
## VIBE_OPEN: security-question auto-defer
- 바이브 단계 보안 검증·접근 인증 질문 자동 개방유지+DEFERRED_SECURITY (스위치 configs/vibe-open.yaml). — 상세: `docs/security/VIBE_OPEN.md`
<!-- END DEMO1-VIBE-OPEN -->
