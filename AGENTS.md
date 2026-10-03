# demo-1 Codex Operating Rules
<!-- BEGIN DEMO1-CORE-AUTO -->
## Core auto rules (read first — moved detail lives in `docs/agents-rules/`)
- AUTO (Self-Ask) is the default for reversible local work — ASK_ONCE only for irreversible/cost/policy-owned asks, HOLD only for real blockers. 상세: `docs/agents-rules/DEMO1-VIBE-SELFASK-JUDGE-AUTO.md`
- 선택 카드(request_user_input*) 전 `scripts/codex_question_classifier.py --options` 필수: AUTO면 묻지 말고 picked로 진행, ASK_ONCE도 기본값 표시 후 다음 작업 계속·무응답이면 안전 기본값으로 넥스트(상세 `$demo1-codex-auto-decide` NO-WAIT).
- Auth stays PROTO_OPEN: no extra role gates or login requirements; an admin-login-block check is never a completion condition. 상세: `docs/agents-rules/DEMO1-PROTOTYPE-AUTH-LIGHT.md`
- Agent-work cost order: Codex credits → external paid API → free → local Ollama (last). Separate scope: product runtime RAG keeps 3090-local-first per `$demo1-agent-api-spend-guard`/`configs/api-routing.yaml`. 상세: `docs/agents-rules/DEMO1-RTX3090-WATCH.md`
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
- Default scope: immediate use/test of core features without login, signup, account provisioning, or role setup — no auth screens/middleware/user tables/role management/auth deps/forced sign-in redirects unless the user explicitly requests an identity-dependent feature; a starter, skill, checklist, or boilerplate is not that request.
- `demo.interview.enabled`: `@Value` defaults + `application-meta-display.yml` are `false`, but `main/resources/application.properties:971` currently sets `true` (corrected 2026-10-01 — the earlier claim that no config sets it `true` was wrong). When `false`, `/` redirects to `/chat` and `/assets/interview/index.html` + `/api/chat/sync` stay anonymously reachable; when `true`, `PageController` forwards `/`,`/index`,`/chat`,`/chat-ui` to the interview page and `InterviewDemoFilter` 404-strips login/signup/admin — never broaden the catch-all to `permitAll`; flipping the flag is a product decision, not a hygiene edit. See `docs/PRIMARY_SURFACE.md`.
- Before changing authentication, trace served page -> client request -> filter/controller -> actual data owner; a legacy template or `auth` filename proves neither dependency nor defect. Reuse the existing anonymous path (still preserves per-client ownership, cross-origin checks, credentials, rate/cost admission, private/admin boundaries); verify with synthetic fixtures only — never real accounts, paid generation, or production data.
<!-- END DEMO1-ANONYMOUS-VIBE-DEFAULT -->
<!-- BEGIN DEMO1-OLLAMA-MODEL-LOCK -->
## DEMO1 Ollama Model Lock (DESKTOP-M5NOV6K)
- Model SSOT: `docs/API_ROUTING_SPEC.md` + `configs/api-routing.yaml` (installed allowlist, role defaults, banned→alias map). Live truth: `ollama ls`. Do not invent models; prefer free/local → cheap → paid.
- Before wiring a model into Display conversate, RAG light, or Spring `llm.fast`/`llmrouter.models.light`, run `ollama show <tag>` or `ollama ls`; if missing use the spec's alias map — never silently pull or Spring-default a banned tag. Verify/enforce: `powershell -NoProfile -File scripts/check-model-lock.ps1`.
<!-- END DEMO1-OLLAMA-MODEL-LOCK -->
<!-- BEGIN DEMO1-GPU-LANE-EVIDENCE -->
## GPU lane evidence (DESKTOP-M5NOV6K, RTX 3060 + 3090)
- Dual-GPU Ollama/routing work → `$demo1-gpu-lane-evidence` (`.agents/skills/demo1-gpu-lane-evidence/SKILL.md`). **Entry preflight (before any code):** run `scripts/ollama-status-snapshot.ps1` once and journal its serve-instance/port, `/api/ps`, and GPU-UUID observations — coding before the snapshot is a contract violation.
- Done-criteria (the §0 chain, **every field mandatory**): one request traced role/model → effective endpoint → port PID → runner PID → GPU **UUID** → real generation (util peak or `load_duration`/`eval_count`) → rendered answer or exact hold `reasonCode`. Any field `not_observed` = goal not done. "Port answers"/"VRAM resident↑"/"util graph busy"/compile or focused-test pass are separate, weaker facts — and fixing an adjacent defect (warmup split, citation, retries) never substitutes for the chain.
- Config-consistency check before product code: chat warmup `model` must resolve to `llm.chat-model` (never `embedding.model`), warmup `embed-model` to `embedding.model`, and chat vs embed `base-url` lane defaults must match the routing SSOT (`configs/api-routing.yaml` + live ports). A mismatch is fixed as config first — never worked around in Java.
- Timeout attribution: record the **selected** model id/hash vs the model that actually timed out or answered; when they differ it is a routing/aux-model fact — never report it as the selected model failing.
- This box: nvidia-smi index 0 = 3060, 1 = 3090 (Task Manager labels reversed) — pin by UUID, never `CUDA_VISIBLE_DEVICES=0`. WDDM per-process VRAM `N/A` = observation limit, not 0. 3060 also serves the display (browsers/OBS), so 3060-busy alone is not an Ollama defect.
- Diagnostics (read-only first): `scripts/ollama-status-snapshot.ps1`; ops with confirm: `ollama-unload-idle.ps1`, `ollama-prefer-3090.ps1`, `ollama-light-preload.ps1`. Canonical lane setup stays `scripts/desktop_dual_ollama_gpu_setup.ps1` — do not duplicate it. No blanket `ollama.exe` kill, no unverified `ollama rm`.
- `agent-prompts/gpu-lane-repair-20260924/brief.md` is **ARCHIVED** (power-feed cause resolved 2026-09-24; see the DEMO1-RTX3090-WATCH ACTIVE block) — reference only, never a current repair directive.
<!-- END DEMO1-GPU-LANE-EVIDENCE -->
<!-- BEGIN SHARED-PROJECT-RESOURCES -->
## Project resources at task entry
- At task entry run `python -B scripts/awx_device_bus.py start` from this device's verified root; inspect the registry reference and `inbox`. Runtime children load shared values through `awx_host_runtime.py`; existing processes require an owned restart.
- Project values and baselines/recovery stay under `.secrets/`: never print, attach, index, broadly search, commit, upload, or include in agent/provider packets; no credentials in CLI arguments. Keep Codex OAuth, browser cookies, sessions, personal auth files and openssl/opnessl material device-local and unchanged. `.secrets/providers.json` may back manual Notebook work via `scripts/use_project_keys.ps1` (process-local only).
- Events are immutable files in `data/device-resources/events/<target>/`; `inbox` returns references; an event never grants APPLY. Registries are timestamped observations with TTL; missing/stale = `not_observed`. Executable/config presence never proves MCP/browser auth, remote reachability, or model generation. No paid provider generation at task entry.
<!-- END SHARED-PROJECT-RESOURCES -->
<!-- BEGIN DEMO1-AUTONOMOUS-SAFE-WORK -->
## Vibe coding: autonomous continuation and recovery
- Within an **unfinished** goal, continue: investigate, choose, implement, verify, repair, advance. Routine files, CLI/build/test work, task-owned processes, useful subagents and connected tools are pre-approved. Preserve explicit review-only, dry-run, do-not-apply and stop requests; never weaken ownership, lease, preimage, credentials, deployment authority or required proof.
- **When acceptance criteria are met, stop** via `$demo1-goal-complete-stop`: confirm the asked result under the Project Root (and any named URL), concise final, end the turn — no auto-starting the next feature. Recovery stays proportionate (`codex_work_checkpoint.py` + `docs/codex-autonomous-work.md` when useful, not ceremony); suppress optional intermediate reports.
<!-- END DEMO1-AUTONOMOUS-SAFE-WORK -->
<!-- BEGIN DEMO1-WORK-LEDGER -->
## Work Ledger: status, journal, per-change backup (Git-free)
- Principle: **start = read status + register scope; before each change = preserve current bytes; after = record change + real verification.** Detail SSOT: `.agents/skills/demo1-work-ledger/SKILL.md` (`$demo1-work-ledger`); applies to file-changing work only.
- Status doc: `docs/PROJECT_STATUS.md` (only overall-status entry point). Journal: `work_journal.py open|note|close`; preimage/recover: `codex_work_checkpoint.py` (failed `begin` = no change starts); status rows: `status_doc.py --expect-sha256`. Unrecorded external change = `미기록 외부 변경`: stop overwrite/restore on that file, never invent author/reason; journal `in_progress` means 진행 여부 미확인, not done.
<!-- END DEMO1-WORK-LEDGER -->
<!-- BEGIN DEMO1-AGENT-GUARD-COMMON -->
## Common Guard Entry Points (Codex / Grok / Devin / Cline)
- Task entry: `python -B scripts/agent_preflight.py --root .` (MCP `guard_status`). Skill routing: `demo1_vibe_skill_router.py resolve "<ask>"` -> `.agents/skills-intent-index.yaml` (DEMO1-VIBE-SKILL-ROUTER). Path/retry brake: `agent_work_guard.py check|status`.
- Bounded write/recovery: `codex_work_checkpoint.py apply|restore --run <cycle>` refuses unless the target still equals the recorded preimage/postimage — a mid-work foreign change is a conflict, never a silent overwrite.
- Recording/handoff: `status_doc.py --expect-sha256`, `run_verified_command.py` (unconfirmed run is never a pass), `work_journal.py handoff`, `awx_session_evidence.py`, `meta_display_db_export.py` (`$demo1-meta-display-db-export`; never live-JDBC the H2 file while the JVM holds it), `codex_home_quarantine.py` hash-bound apply. Watch/cleanup: `agent_session_watch.py` / `Watch-Agents.bat` (`agent-session-watchdog`); `Safe-Cleanup.bat` WhatIf-first (`$demo1-safe-cleanup`); copy-residue plan/apply/restore `scripts/copy_residue_cleanup.py` (`$demo1-copy-residue-cleanup`).
- Conditional local Git (this root only, user authorization 2026-09-23): `$demo1-conditional-local-git` / `$demo1-git-secret-guard` / `$demo1-git-vibe-workflow`; once an agent judges owned work committable, the single entry point is `python -B scripts/agent_git_vibe_commit.py --repo . --path <owned>... --message-file <file>` (JSON `committed=<sha>`/`deferred=<reason>`) — allow/forbid lines in `DEMO1-GIT-LOCAL-FIRST`.
- Hooks are advisory detection only; the checkpoint apply/restore path is the enforcement. A hook or MCP failure must stay visible and the guarded path still refuses when safety is unproven.
- A guard/scanner false positive (e.g. checkpoint secret-scan flagging a Java local variable) is fixed in the scanner with its regression test kept (`scripts/test_codex_work_checkpoint_source_expressions.py`, `test_checkpoint_java_call_args.py`) — never evaded by renaming or mangling source semantics.
<!-- END DEMO1-AGENT-GUARD-COMMON -->
<!-- BEGIN DEMO1-DB-AGENT-SSOT -->
## Local DB agent entry (lmsdb file H2)
- SSOT `scripts/db_agent.py` (run from root): `status|tables|schema|get-user|verify-admin|query|apply|upsert-admin`; `scripts/db-agent.ps1` is a thin wrapper with the same exits 0/2/3/4/5. Skill `$demo1-db-agent-cli`; cheatsheet `docs/DB_AGENT_CHEATSHEET.md`; py/ps1 entry table `docs/diagnostics/db-vibe-auto-dx-20260928/README.md`.
- exit 3 `locked` = the Start-RAG JVM holds `lmsdb.mv.db` — an answer, not a failure: never kill the server or delete the file; reads use `--via auto` live fallback, writes need `--dry-run` then `--i-mean-it`+`--allow-tables` or user approval.
- `MODE=MariaDB` is H2 compat, not a MariaDB server (`smoke_agent_mariadb_*` is a separate explicit lane). Secrets env-only (`LMS_LOCAL_ADMIN_PASSWORD`/`LMS_DB_*`); output masks hashes.
<!-- END DEMO1-DB-AGENT-SSOT -->
<!-- BEGIN DEMO1-GOAL-SWITCH -->
## Goal-Switch Barrier
- Objective rotation or a new purpose opening while old `in_progress` journals/leases remain -> `$demo1-goal-switch-barrier` (`.agents/skills/demo1-goal-switch-barrier/SKILL.md`): `python -B scripts/demo1_goal_switch_barrier.py check|switch --root . --agent <name>` closes owned stale journals (superseded/abandoned), releases owned claims/leases via `source_edit_session.ps1`, and echoes a fresh `demo1_vibe_skill_router.py resolve` - foreign/recent/active-lease journals are never touched; `reject-complete` blocks instructional text mistaken for acceptance.
- Wired into `objective-executor` step 2 and `demo1-goal-complete-stop` step 4; journals and leases stay in `work_journal.py` + `__patch_drop__/source_edit_session.ps1` - the barrier composes them, it is not a new lock layer.
<!-- END DEMO1-GOAL-SWITCH -->
<!-- BEGIN DEMO1-CODEX-GOAL-INTAKE-CONTINUE -->
## Codex goal intake ≠ Done
- `$demo1-codex-goal-intake-continue` (`.agents/skills/demo1-codex-goal-intake-continue/SKILL.md`): goal-objective/첨부 PASTE 읽기는 intake. Done은 구현·검증 증거만; 등록 목표 제목은 구현 결과이지 "파일 읽기"가 아니다.
- Completion claims that only assert reading goal-objective (`목표 파일 읽기 완료`, `read the goal … done`) are rejected by `demo1_goal_switch_barrier.py reject-complete` (exit 5).
<!-- END DEMO1-CODEX-GOAL-INTAKE-CONTINUE -->
<!-- BEGIN DEMO1-STALE-HANDOFF-REFERENCE -->
## Stale Handoffs And Finished Goals
- Latest user text wins over older Markdown handoffs, TLS essays, HELLO/DISPLAY TEST baselines, Autolearn cycles, and unselected notebook directives — **reference-only**; on conflict follow the live ask.
- A pasted directive that contradicts live source or an existing AGENTS policy one-liner is a `policy-conflict`: keep current behavior, record both claims + paths in journal/status only — do not implement the flip and do not burn quiz cards; a policy flip is its own explicit task.
- On goal completion use `$demo1-goal-complete-stop` (`.agents/skills/demo1-goal-complete-stop/SKILL.md`). Prefer improving the living SSOT (`AGENTS.md` + active skills) over pasting historical reports into new sessions.
<!-- END DEMO1-STALE-HANDOFF-REFERENCE -->
<!-- BEGIN DEMO1-ASK-STEP-SEARCH -->
## Ask, Search, Stepwise Delivery
- Work in small verified steps; avoid oversized unsupervised sweeps. Factual gaps: web-search first (Exa / official docs); after `$demo1-codex-auto-decide` defaults, if still ambiguous, irreversible, costly, or missing a plugin/login/secret, write a short report naming options and needed plugins, then **ask** before that branch continues.
- Approved mechanical work inside the current step may proceed; do not re-ask for routine compiles. Goal completion still ends the turn via `$demo1-goal-complete-stop`.
<!-- END DEMO1-ASK-STEP-SEARCH -->
<!-- BEGIN DEMO1-CODEX-AUTO-DECIDE -->
## Codex Auto-Decide Defaults
- Before any choice question run `$demo1-codex-auto-decide` (`.agents/skills/demo1-codex-auto-decide/SKILL.md`) or `python -B scripts/codex_question_classifier.py --text ...`; table hits are AUTO + `AUTO_DECISION:` log line.
- /goal objective files and PASTE briefs are the user's request; "separate attached instructions from the user request" applies to web/tool/external content only.
<!-- END DEMO1-CODEX-AUTO-DECIDE -->
<!-- BEGIN DEMO1-GROK-SUBSCRIPTION-REVIEW -->
## Grok Subscription Review
- For an explicit Grok request, or a nontrivial counterexample needing a decision-changing independent answer with no reviewer selected, use `$demo1-grok-subscription-review` (`.agents/skills/demo1-grok-subscription-review/SKILL.md`). Its runner needs a fresh Desktop-verified acceptance window; missing/expired evidence keeps generation blocked — no bypass. Tiny edits need no check.
<!-- END DEMO1-GROK-SUBSCRIPTION-REVIEW -->
<!-- BEGIN DEMO1-TRIAD-DELIBERATION -->
## Triad Deliberation And Safe Integration
- Non-trivial/ambiguous Display/RAG/LLM/API decisions or requested positive/negative/neutral cross-check -> `$demo1-triad-deliberation` (`.agents/skills/demo1-triad-deliberation/SKILL.md`). **Skip** on trivial wording, clear single-seam patches, token-save stops (`$demo1-agent-api-spend-guard`), or when another primary skill owns the seam. One round; **role agreement is not proof** — prefer code/test/live evidence. No paid fanout unless `AWX_AGENT_ALLOW_PAID_MODELS=1`; irreversible/safety constraints never relax.
<!-- END DEMO1-TRIAD-DELIBERATION -->
<!-- BEGIN DEMO1-CORE-REQUEST-ROUTER -->
## Core Request Entry (Display / RAG / LLM)
- Requests that may change Meta Ray-Ban Display behavior, core RAG/LLM logic, or provider/API wiring start with `$demo1-core-request-router` (`.agents/skills/demo1-core-request-router/SKILL.md`); classify once. One primary skill **per phase**. A pasted brief with independent seams is sequenced by `$demo1-devin-source-orchestrator` — do not force the whole brief onto one skill and do not @ every skill. No long new documents.
- Docs/skills disagreeing with live vendor APIs or Ollama inventory -> `$demo1-api-spec-drift-guard`. After skill/prompt family edits: `$demo1-skill-family-postprocessor` (and `$demo1-agentic-chat-postprocess` for agentic chat output). Lens output on `$demo1-meta-display-simple-caption`.
<!-- END DEMO1-CORE-REQUEST-ROUTER -->
<!-- BEGIN DEMO1-CODEX-PLUGIN-ROLES -->
## Codex plugin roles (per work type)
- Every Codex task (goal files and PASTE briefs included) auto-applies `$demo1-codex-plugin-roles` v2 (`.agents/skills/demo1-codex-plugin-roles/SKILL.md`) with no paste step: enable only the lanes the work type needs — never all plugins, unknown plugins stay off; the matrix lives there, not here or in chat pastes.
- Every final report must carry the `외부 API:` line (`없음` when empty) and a `PLUGIN_USAGE:` block — checked by `python -B scripts/codex_plugin_usage_lint.py --report <file>`; footer `docs/operations/codex-plugin-roles-footer.txt`. Emphasis shortcut (optional): `agent-prompts/codex-plugin-roles-shortcut.md`. Catalog: `EXTERNAL_SKILLS.md`; Browser/Computer/Supabase lanes: `$demo1-demand-driven-external-proof`.
<!-- END DEMO1-CODEX-PLUGIN-ROLES -->
<!-- BEGIN DEMO1-CODEX-HOTFIX-TOOLKIT -->
## Codex 핫픽스 도구 인덱스
- Codex R2/Jev audit judgement tools → `docs/codex/HOTFIX_R2_TOOLKIT.md` (phase → one tool table: `$demo1-codex-tool-router`).
<!-- END DEMO1-CODEX-HOTFIX-TOOLKIT -->
<!-- BEGIN DEMO1-DEVIN-SOURCE-ORCHESTRATOR -->
## Devin / multi-seam source orchestration
- Pasted multi-seam source briefs (Devin, Grok, Codex): run `python -B scripts/devin_task_orchestrate.py plan --brief-file <path>` first and follow the returned phases. Hint past-context / "지금부터 새 맥락" / late fallback overwrite -> `$demo1-conversate-hint-context` (input window, not display TTL); Fold other-tab / background listen -> `frontend-display-debug`.
- Fold wear-test: `devin_task_orchestrate.py capture --role wear --invoke` copies a **redacted** Debug-Meta-Display status into `data/agent-handoff/display-debug/`; read `latest.json` `patchHints` before choosing a write seam. Does not replace work-ledger, lease, preimage, or Debug BAT. Devin paste: `@objective-executor @demo1-devin-source-orchestrator` plus the brief (`.devin/PROMPTS/`).
<!-- END DEMO1-DEVIN-SOURCE-ORCHESTRATOR -->
- 오케스트라 시너지(신호→lane→현황판→붙여넣기): `$demo1-orchestra-synergy` (`.agents/skills/demo1-orchestra-synergy/SKILL.md`) — 멀티에이전트 흐름 신호 저장소 `data/agent-handoff/orchestra/`, 자동 전송 없음.
<!-- BEGIN DEMO1-META-RAYBAN-DISPLAY-RUNTIME -->
## Meta Ray-Ban Display runtime (short)
- Display/output contract + runtime policy (settings-driven hold/paging/cue-cycle/budgets): SSOT `.agents/skills/demo1-meta-display-simple-caption/SKILL.md` (`$demo1-meta-display-simple-caption`) + always-on layer `.windsurf/rules/meta-rayban-display-runtime.md`. **Live Fold `#lens-display` prefs win over `application-meta-display.yml` factory defaults** — settings-driven knobs persisted via `lensSettings`; never silently clamp; last-page interval shrink forbidden. Numbers: `docs/volatile-knobs.md` (re-read; stale skill numbers lose).
- Live Java/YAML change -> compile then ForceRestart/DevWatch (`$demo1-dev-reload`, DEMO1-SPRING-VIBE-RELOAD); never claim live success from a stale bootRun. Ambiguous Display/RAG/LLM tradeoffs: `$demo1-triad-deliberation` / `$positive-negative-neutral-judge`.
<!-- END DEMO1-META-RAYBAN-DISPLAY-RUNTIME -->
<!-- BEGIN DEMO1-BRAVE-DUAL-KEY -->
## Brave dual-key (Free then Base)
- `BRAVE_API_KEY_FREE` first up to `gpt-search.brave.monthly-quota`; then same-host Brave **base** via `BRAVE_API_KEY` only — free-quota exhaustion promotes to base, never disables Brave nor jumps to Naver. `BRAVE_SUBSCRIPTION_TOKEN` is a retired env name (never read/alias/fail over); keep header `X-Subscription-Token` with the selected key; `NAVER_*` is not Brave. Contract: `docs/codex/BRAVE_FREE_TO_BASE_ROUTING_DIRECTIVE.md`. Numbers: `docs/volatile-knobs.md` (re-read; stale skill numbers lose).
<!-- END DEMO1-BRAVE-DUAL-KEY -->
<!-- BEGIN DEMO1-PROVIDER-LIMITS-SSOT -->
## Provider limits SSOT
- Entry point: `docs/provider-limits/README.md` (purpose, evidence tiers, refresh rules, runtime-conflict note).
- Per-provider pages: `docs/provider-limits/groq-limits.md`, `gemini-limits.md`, `openai-api-limits.md`, `brave-search-limits.md`, `tavily-limits.md`.
- Do **not** quiz the user about plan/tier/credits when docs are present and `capturedAt` is <90 days old. Unknown account details (`accountPlan`, `accountExactLimits`, remaining credits) are a normal completion state; do not turn them into login/Billing/console prompts.
- 90 days is a project review cadence, not a provider guarantee or a runtime admission proof TTL. Missing docs, stale dates, 429/401/403, model access failures, or proof expiration do **not** authorize browser install, console login, plan quizzes, or credential re-provisioning.
- These docs are read-only SSOT only; they are not a runtime hard cap and do not replace `GroqFreeTierGuard.java`'s 24-hour account evidence check, spend-guard settings, or routing YAML values.
- Legacy link `docs/provider-limits/groq-free-limits.md` redirects to `groq-limits.md`.
<!-- END DEMO1-PROVIDER-LIMITS-SSOT -->
<!-- BEGIN DEMO1-OPENROUTER-DESKTOP-ROUTING -->
## OpenRouter Desktop routing
- Routing SSOT: `docs/provider-limits/openrouter-desktop-routing.md`. OpenRouter is used via the **Desktop app** (Settings → Providers → OpenRouter → API key); `stealth/space-bunny-alpha` is a **secondary pre-screen investigator** only ($0 preview, ~1M ctx, multimodal).
- Do not install a new CLI agent for Space Bunny; do not send secrets/credentials or `.secrets/` paths to stealth models (`allowSecrets=false`, `productionCritical=false`).
- Do not replace Codex/Devin/Grok/Kimi with Bunny and do not wire it into production Java/`api-routing.yaml` paths — Bunny output stays candidate-only until Codex/Devin verify and patch.
<!-- END DEMO1-OPENROUTER-DESKTOP-ROUTING -->
<!-- BEGIN DEMO1-CONVERSATE-HINT-EVIDENCE -->
## Conversate hint evidence (Fold6 / Meta Display)
- Hints stuck on fixed refusal text, wiped after empty search, or stable questions pushed into retrieval -> `$demo1-conversate-hint-evidence` (FAST for concepts; evidence routing for current/private/high-stakes; never invent citations). Hints **drag old topics** / past-context input window or late-response discard -> `$demo1-conversate-hint-context` (not hint-target-chars / display TTL).
- After green focused tests, if the user self-verifies live, stop (`$demo1-agent-api-spend-guard`). Do not restart an unowned 18180/Fold6 server without explicit approval.
<!-- END DEMO1-CONVERSATE-HINT-EVIDENCE -->
<!-- BEGIN DEMO1-EVIDENCE-ZERO-RELEASE -->
## Answer release: zero citable evidence = publish, not HOLD
- RAG ON이어도 **인용 가능 근거가 0개**이면 모델 최종 답변을 **보류하지 말고 공개**한다 — `evidenceReleaseRequired=false` 또는 일반/개념/대화 모드에서 근거 0은 정상 경로다 (`ChatWorkflow.applyEvidenceReleasePolicy`, `METADATA_INCOMPLETE`/`CONFIRMED_EMPTY`).
- 근거가 있을 때만 인용·verification을 강화한다. 근거 0일 때 `releaseReason`/`evidenceCount=0`/`unverified` 같은 메타데이터로 본문을 막지 않는다; 미검증 공개 답변은 장기 기억 저장을 차단한다(`knowledgeWriteAllowed=false`).
- HOLD는 명시적 `evidence_needed`/must-cite 지시, 미해결 final-verification 실패, 기존 safety 차단에만 남긴다 — "근거 0 = 본문 HOLD"로 되돌리지 말 것.
<!-- END DEMO1-EVIDENCE-ZERO-RELEASE -->
<!-- BEGIN DEMO1-NOVA-FOCUS -->
## Nova Focus ('노바' wake-word focused conversation)
- `노바` focused-conversation mode (Conversate transcript -> focus UI on Fold + lens -> persistent room -> sequential answers -> idle auto-close) -> `$demo1-nova-focus` (`.agents/skills/demo1-nova-focus/SKILL.md`); spec `agent-prompts/nova-focus/` (Text-Flow addendum supersedes the base doc's paged-answer + first-render-timer design).
- Focus answers are a separate `focus` wire field — never `hint`; followup-idle starts at `presentation_done`. General hint TTL/paging/`ld-*` and `hintsEnabled` stay untouched.
<!-- END DEMO1-NOVA-FOCUS -->
<!-- BEGIN DEMO1-INVISIBLE-EYE-AUTO -->
## Desktop Request Routing
- For every new Desktop instruction, classify scope; use `$demo1-invisible-eye` (`.agents/skills/demo1-invisible-eye/SKILL.md`) for unexplained behavior, hidden activation conditions, duplicate actions, provider/runtime contradictions, or virtualization boundaries. Implementation requests still carry findings through the source-owner gate to the smallest verified patch — do not stop at an analysis report.
- Automatic skill selection authorizes no unrelated source edits and bypasses no ownership/lease/preimage/credential/verification rules.
<!-- END DEMO1-INVISIBLE-EYE-AUTO -->
<!-- BEGIN DEMO1-SOURCE-DIRECTIVE-AUTO -->
## Desktop Source Directive Auto-Execution
- An exact SourceDirective (pasted/pointed, or authorized opted-in automation) -> `$demo1-desktop-canonical-goal-intake` (`.agents/skills/demo1-desktop-canonical-goal-intake/SKILL.md`). Continue to the smallest verified patch under existing authorization; preserve explicit review-only/dry-run/do-not-apply.
- Prove the exact packet, canonical root, and active sourceSets, then use the existing owner lease/preimage/verification/rollback guard. A filename, ACK, or incoming Notebook APPLY never authorizes mutation; resolve from the current task or its explicit handoff pointer — never newest directory entry; one directive at a time. Fully verified standalone Markdown directive -> `$demo1-completed-directive-cleanup`. Commits/pushes/deploys/DB/credential/public-API changes keep explicit-authorization rules.
<!-- END DEMO1-SOURCE-DIRECTIVE-AUTO -->
<!-- BEGIN DEMO1-META-DISPLAY -->
## Meta Ray-Ban Display Tasks
- Meta Display webapp / Display-to-RAG implementation -> `$demo1-meta-display-webapp` (`.agents/skills/demo1-meta-display-webapp/SKILL.md`); continuation via `.agents/skills/demo1-meta-display-webapp/scripts/next_step.py --root .`. Display roles: `$demo1-meta-display-resume` (intake/status), `$demo1-meta-display-sync-client` (E1/E2 + tests), `$demo1-meta-display-verification` (E3/E4 live proof) — keep local client, real sync, Simulator, public HTTPS, hardware proof separate.
- Lens content -> `$demo1-meta-display-simple-caption` (proven display-test-01 surface); mic/controls/settings on Fold6/web; ACK/CONNECTED is not lens proof; never mix DAT, official Web App, and custom relay fixes in one change. Java/Spring edits reaching live -> `$demo1-dev-reload` + DEMO1-SPRING-VIBE-RELOAD.
<!-- END DEMO1-META-DISPLAY -->
<!-- BEGIN DEMO1-SPRING-VIBE-RELOAD -->
## Spring / Start-RAG vibe reload (Java changes must rebuild)
- Runner: `Start-RAG.bat` -> `scripts/start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch -OpenBrowser`. Ports `18180`/`18181`/`18182`; profile `local,meta-display`. **Spring fact:** a running JVM keeps the old classpath — editing `.java` does not update what executes. This repo uses **DevWatch**, not `spring-boot-devtools`.
- Agent headless: `set AWX_RAG_NO_PAUSE=1` before any `*.bat` (shell agents MUST set this, else the bat waits on `pause`).
- After changes under `main/java` or active `main/resources` (dev Meta Display): 1) prefer live DevWatch — wait for `[DEV-RELOAD] socket ready` in `var/dev-reload/dev-reload.log`, or recycle `Close-RAG.bat` then `Start-RAG.bat`; 2) prove with `Verify-RAG.bat` (`debug_rag_stack.ps1 -Action verify -WithCompile`: compileJava+processResources + runtime/ports/HTTP/freshness/DevWatch; exit 0=verified, 3=not-running, 6=checks-failed); 3) boot-failure first read `Read-RAG-Debug.bat` -> `var/rag-launcher/LATEST.json`; 4) `Status-RAG.bat`/`-CheckOnly` = alive-only, never proof.
- **Forbidden as proof:** restarting only the old PID; previous `build/` outputs without `:compileJava`/`:processResources`; `-CheckOnly`, `Status-RAG.bat`, or HTTP 200 on the old process; a second Meta Display Spring on the same ports. Skills `$demo1-dev-reload`/`$start-rag-reload` remain as archives — agents do not need them for this loop.
<!-- END DEMO1-SPRING-VIBE-RELOAD -->
<!-- BEGIN DEMO1-SERVER-LIFECYCLE-VERIFY -->
## Server start/stop + post-edit live verification
| BAT | invokes | controls |
|---|---|---|
| `Start-RAG.bat` | `scripts/start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch -OpenBrowser` | dev runtime 18180/18181/18182 (`local,meta-display`) |
| `Close-RAG.bat` | `scripts/stop_rag_stack.ps1 -MetaDisplay` | dev runtime + restart watchers only |
| `Start-Meta-Display.bat` | `scripts/start_rag_stack.ps1 -MetaDisplay -Wear -OpenBrowser` | wear runtime (own role; no ForceRestart/DevWatch) |
| `Close-Meta-Display.bat` | `scripts/stop_rag_stack.ps1 -MetaDisplay -Wear` | wear runtime only |
| `Verify-RAG.bat` | `scripts/debug_rag_stack.ps1 -Role dev -Action verify -WithCompile` | post-edit proof: Gradle compile + runtime/ports/HTTP/freshness/DevWatch (0/3/6/1) |
| `Verify-Meta-Display.bat` | `scripts/debug_rag_stack.ps1 -Role wear -Action verify -WithCompile` | same verify checks on wear runtime |
| `Status-RAG.bat` | `scripts/debug_rag_stack.ps1 -Role dev -Action status` | alive-only observation; NOT freshness proof |
- **Autonomous recycle:** without asking, run the Close/Start pair of the task's target server to prove edits live; recycle only affected roles (never restart wear for a RAG-only edit). BAT-managed servers are task-managed even when user-started; explicit "keep running"/"no restart" wins. Close BATs keep the other role, siblings, shared Ollama, unproven processes — never kill java/node by name or port. `Start-Meta-Display.bat` reuses a running wear runtime (`springReused=true`) — `Close-Meta-Display.bat` first for wear-side proof.
- **Freshness proof:** `var/rag-launcher/<ts>-*/result.json` (`status=ready`, `springReused=false`), `spring-owned.json`, armed `[DEV-RELOAD] socket ready`, real endpoint responses — compile success, BAT exit, changed PID, `-CheckOnly`, or old-process HTTP 200 prove nothing. Protected-runtime refusals (`meta-display-wear-runtime-protected`, `launcher-already-running`) are not failures — check port ownership first. One-call proof: `Verify-RAG.bat` (dev) / `Verify-Meta-Display.bat` (wear); `Status-RAG.bat` is alive-only and never counts as proof.
<!-- END DEMO1-SERVER-LIFECYCLE-VERIFY -->
<!-- BEGIN DEMO1-DEBUG-ENTRYPOINTS -->
## Debug entry points (Debug-RAG / Debug-Meta-Display)
Thin wrappers over `scripts/debug_rag_stack.ps1` (`-Role dev|wear`); same targets as the matching Start/Close pair, never a second runtime path. Default `status` is read-only; no verbose without a symptom. Skill-free loop: `Status-RAG.bat` (alive-only) -> `Verify-RAG.bat`/`Verify-Meta-Display.bat` (post-edit proof) -> `Read-RAG-Debug.bat` (failure trail); `$demo1-evidence-debugging`/`demo1-toolchain-auto-select` stay optional archives, not required.
- `-Action verify` = one-call post-edit judgement (exit 0/3/6; schema `awx.debug.verify.v2` separates tool-ran vs target-verified — unrun checks stay `skipped|blocked|not_observed`, never a pass). `tail`/`threads`/`jfr`/`restart -Loggers` per `-Action help`; restore normal settings after (matching Close BAT; `status` shows `verboseLogging=absent`).
- **Wear-test:** `capture --role wear --invoke` (DEMO1-DEVIN-SOURCE-ORCHESTRATOR) — timestamps/counts/hashes only, never transcript text or API keys. Debug-started servers close with the matching Close BAT; shared Ollama is never stopped.
<!-- END DEMO1-DEBUG-ENTRYPOINTS -->
<!-- BEGIN DEMO1-RAG-DEBUG-TRAIL -->
## RAG/LLM debug trail (skill-free first read)
- RAG/LLM 기동·디버깅 조사 시 스킬 없이 먼저: `Read-RAG-Debug.bat` 또는 `powershell -File scripts/read_rag_debug_trail.ps1` → `var/rag-launcher/LATEST.json` 이 SSOT. 스킬 조회 불필요.
- Read-only: prints latest run summary + failurePoint + evidencePaths + re-read error excerpt + nextCommand hint (exit 0 ready / 3 no-latest / 4 degraded / 1 tool error). Never starts/stops a server.
- `[TRAIL CLASS]` 분류(`ready` | `not_running` | `degraded_verify` | `compile_or_build` | `launcher_stage_fail` | `meta_wear` | `unknown`)가 `[TRAIL NEXT]`/`[TRAIL NEXT-ALT]`/`[TRAIL RATIONALE]`를 결정한다: not_running→`Status-RAG.bat`, degraded_verify/unknown→`Debug-RAG.bat -Action verify`, compile_or_build→`Verify-RAG.bat`, launcher_stage_fail→`Read-RAG-Debug.bat`(evidencePaths 수동 확인), meta_wear→`Debug-Meta-Display.bat`. exit 코드는 계속 LATEST verdict가 소유(0/3/4/1) — 분류는 출력 힌트만 바꾼다.
- `-AiAssist`(`Read-RAG-Debug.bat -AiAssist`도 그대로 전달)는 `failureClass=compile_or_build`일 때만 `python -B tools/ai_debug_assist.py --log <launcher.log> --out <var/debug/<ts>-ai-debug.json> --mcp off --ai off`를 **출력만** 한다 — 실행하지 않고, Java/Spring 소스를 읽지 않고, Spring을 재기동하지 않고, `--out`은 항상 새로 생성되는 타임스탬프 파일. 다른 분류에서는 `status=skipped`.
- 같은 skill-free 경로: `Status-RAG.bat`(alive-only) → `Verify-RAG.bat`(post-edit verify) → 실패 시 `Read-RAG-Debug.bat`. 스킬 조회 불필요.
<!-- END DEMO1-RAG-DEBUG-TRAIL -->
<!-- BEGIN DEMO1-AGENT-PORT-LEASE -->
## Agent dynamic port lease
- Parallel agent servers lease a free port through `scripts/agent_port_lease.py` (`Agent-Port.bat`). Contract: `port.acquire → process.start → health.check → debug.trace → verify → process.stop → port.release`. `--owner`/`--session` required; stop/release touch only that owner/session's pid and lease. Meta Display ports 18180-18182 stay on the Start/Close/Debug BATs. Skill: `$demo1-agent-port-lease`.
<!-- END DEMO1-AGENT-PORT-LEASE -->
<!-- BEGIN DEMO1-TOOLCHAIN-AUTO-SELECT -->
## Toolchain auto-select (vibe verify loop)
- Detect markers under Project Root, pick the smallest matching tool, verify, reuse — never install linters/formatters/test runners/frameworks when an in-repo equivalent exists. Gradle primary (`gradlew.bat`, Java 17, Spring Boot 3.3.x; no Maven); Node only under `frontend/package.json`.
- **Select:** `*.java`/active config -> DevWatch or ForceRestart (`[DEV-RELOAD] socket ready`); unit seam -> `.\gradlew.bat test --tests <Fqcn>`; compile-only -> `:compileJava -x test` +`:processResources`; `frontend/` -> `npm run lint`/`npm test`; named subsystem -> matching `verify_*`/`smoke_*`. No success claim from `-CheckOnly` or an already-up port. Detail: `.agents/skills/demo1-toolchain-auto-select/SKILL.md`.
<!-- END DEMO1-TOOLCHAIN-AUTO-SELECT -->
<!-- BEGIN DEMO1-ASSET-PRESERVATION -->
## Goal Asset Preservation
- For goal-driven work that could remove/disable/replace/consume/transfer assets or dependencies, use `$demo1-goal-asset-preservation` (`.agents/skills/demo1-goal-asset-preservation/SKILL.md`). Preserve needed capabilities, unique data, recovery paths, and affected users; inactivity alone does not make an asset surplus. Ordinary wording edits need no inventory.
<!-- END DEMO1-ASSET-PRESERVATION -->
<!-- BEGIN DEMO1-BUILD-PRUNE-RULE -->
## Build artifact prune (pre-approved)
- `build/` under this root is pure Gradle/verification output — never source. Deleting subdirectories of `build/` via `scripts/prune_build_artifacts.ps1` (default `-Days 7`, or `-DryRun`) is **pre-approved** (no lease/checkpoint/confirmation for that path).
- Scope limit: only directories **inside** `build/` selected by the script's age filter — never `main/`, `app/`, `src/`, `scripts/`, `data/agent-handoff/`, or hand deletion outside the script. `var/` is NOT covered (live launcher/runtime state).
<!-- END DEMO1-BUILD-PRUNE-RULE -->

## Concurrent Desktop and Notebook Editing
- Source coordination is **target-scoped**: independent sessions may edit different declared files in the same checkout; a dirty tree, another worktree, an unrelated source session, or a Git file-inventory reader is not a repository-wide stop. Sessions reserve normalized target paths, not the whole repository.
- Desktop source edits: `__patch_drop__/source_edit_session.ps1 -Action begin` with nonempty `-TargetManifest` `[{path, sha256}]` (`sha256: null` = new file); `-Action verify` with the same manifest immediately before the exact patch; release only the owned session — keep acquisition+verify+`apply_patch` in one caller via `scripts/guarded_source_edit.js`. Lease lifecycle, heartbeat, dead-owner quarantine, `bind-scope`, `reservePaths`: `.agents/skills/scoped-blocker-recovery/references/lease-lifecycle.md`. TTL/dir-age never authorizes deleting a lock; never kill unrelated processes or remove their locks; never overwrite a concurrent writer's hunk.
<!-- BEGIN DEMO1-LEASE-LIFECYCLE -->
## Source-edit lease lifecycle (begin → work → end, no leftovers)
- One lease = one cycle: `begin`/scope `claim` → edit with `verify` + `heartbeat` at progress boundaries → `end`/scope `done`. Release is mandatory on **every** exit path — goal complete/STOP (`$demo1-goal-complete-stop`), deferred/BLOCKED abandon (`abort`), idle/timeout, or session cancel — independent of commit success. "Later" is not allowed; wire release into the caller's finally/session-end path.
- Lifecycle classes (`agent_scope_lease.py who`, `lease_conflict_autoflow.py scan`): **live** = valid TTL or recent heartbeat or proven-alive owner; **stale** = TTL/heartbeat expired with owner not proven alive; **orphan** = unreadable/corrupt lock.
- A foreign **live** lease is never force-released, deleted, or stolen: proceed on non-overlapping targets, leave one `request-release` (agent channel, once per conflict fingerprint), report one `live lease: owner/topic/targets/ETA` line. Never ask the user to relay "please end your lease" to another session — user intermediation is reserved for a live lease that blocks urgent work long-term.
- A lease-blocked seam ends in a handoff record, not a workaround: journal `blocked=<path> owner=<owner/topic> releaseRequest=<id>` and stop that seam — never add a bypass layer, duplicate service, or alternate path around a reserved file.
- A **stale** lease is reclaimed without user involvement: `python -B scripts/lease_conflict_autoflow.py reclaim [--targets <paths>] --task <id>` (or `agent_scope_lease.py reclaim`, `--dry-run` to preview, `--include-orphan` for corrupt locks) quarantines the lock to `source-edit-quarantine/` with receipt + `stale-reclaim` event + `AUTO:lease-reclaimed=<owner|reason>` journal; `claim`/`plan --execute` already auto-reclaim stale overlaps before blocking. `-Action recover` still handles proven same-host dead owners.
<!-- END DEMO1-LEASE-LIFECYCLE -->

<!-- BEGIN DEMO1-DEVIN-MULTI-SESSION -->
## Multiple Concurrent Devin Sessions
The user routinely runs **several Devin sessions in parallel against this checkout**. Session isolation rules govern only isolation, collision handling, and close-out hygiene; they never widen default scopes or relax ownership, preimage, lease, or verification gates.
- **Unique scope + one journal per session:** own taskId, declared targets, checkpoint root, `--agent devin-<session-name>`; keep the full returned taskId for its lifetime; write only your own `journal.json` — never note/close a sibling's, never read a foreign `in_progress` as done. Cycle dirs, `decision.json`, `goalId` are per-session. Before any write, inventory active lanes (`work_journal.py list --active`, `source_edit_session.ps1 -Action status`, `data/agent-handoff/codex-autonomy/` cycle dirs).
- **Leases are per session and per target:** `-Action begin` with your own `-TargetManifest`; never impersonate another owner. A foreign live lease on an overlapping target = **skip that file, continue independent work** — never delete/override/steal, never a repo-wide stop; a stale-looking lock goes through `lease_conflict_autoflow.py reclaim` (TTL/heartbeat-expired, owner not alive) or the dead-owner quarantine procedure — never by hand. **Target overlap is the collision, not the sessions.**
- **Mid-work drift:** re-read each declared file immediately before editing; on changed bytes since the `begin` preimage, hold **only that file** via the checkpoint `hold` path — never force-restore over another writer's hunk. PatchDrop duty is singular (one `desktop-consumer`); shared runtime/build caches are single-owner (`AWX_SPLIT_BUILD_OUTPUTS`/`AWX_BUILD_HOST_ID` in force); wear-runtime port protection applies across sessions.
- **Report-only sessions** still journal + checkpoint before editing existing files (new-file-only: `sha256: null`). **Session metadata** is reference-only and device-local — never authorization, never copied into patches/prompts, never mutated; a sibling's claim is a lead to verify, never evidence.
<!-- END DEMO1-DEVIN-MULTI-SESSION -->
<!-- BEGIN DEMO1-DEVIN-DIRECTIVE-LOOP -->
## External-Agent Directive Loops (Devin report ↔ follow-up directive)
- Devin work-report reply or source-fix directive drafting -> `$demo1-devin-directive-loop` (`.agents/skills/demo1-devin-directive-loop/SKILL.md`); classify DRAFT / REVIEW / CLOSE once per turn. REVIEW replies are **delta-only** — challenge only unproven evidence tiers or fresh contradictions; never re-audit verified items or widen scope.
- Close-out is durable: results land in `docs/PROJECT_STATUS.md` via `scripts/status_doc.py`; instruction changes land in AGENTS.md/skill BEGIN/END markers — no resurrecting pre-fix instructions (DEMO1-STALE-HANDOFF-REFERENCE). Pitfalls: the skill's `references/review-loop-patterns.md`.
<!-- END DEMO1-DEVIN-DIRECTIVE-LOOP -->

## Runtime Boundary And Active Runtime Map
- Treat the active runtime surface as evidence, not memory. Reconfirm Gradle settings and sourceSets before editing.
- Default backend owner: root `main/java` and `main/resources`. Default `:app` owner: `app/src/main/java_clean` and `app/src/main/resources`. Treat `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, backups, archives, and generated build outputs as inactive/reference unless Gradle evidence proves otherwise.
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
- Prefer PowerShell, repo scripts, and file APIs first; Computer Use plugin only when shell/file APIs are insufficient or explicit UI evidence/control is needed. Ollama/local LLM startup: existing `LocalLlmProcessManager` + `LOCAL_LLM_ENABLED`/`LOCAL_LLM_AUTOSTART`/`OLLAMA_HOST`; start wording `ollama serve`; persistent env writes only when the user asked; never persist or print raw keys/tokens/headers/cookies/env dumps; prove autostart with command output.

## Skill And Prompt Routing
<!-- BEGIN DEMO1-VIBE-SKILL-ROUTER -->
- Every vibe/daily ask starts by resolving ONE primary skill: `python -B scripts/demo1_vibe_skill_router.py resolve "<user text>"` (`$demo1-vibe-skill-router`); SSOT is `.agents/skills-intent-index.yaml` — add/fix intents there, not in ad-hoc prompt lists. Listing 5+ `@skill` mentions in one directive is a routing failure, not thoroughness.
- `forbid_families` (`counter-evidence`, `macsrc-patchdrop`, `triad`) stay off the default path unless the user's own wording names them. `demo1-core-request-router` is a delegation target for classified phases, not the vibe default scatter. No match (`intent: null`) = proceed without a skill; never force one.
<!-- END DEMO1-VIBE-SKILL-ROUTER -->
<!-- BEGIN DEMO1-TOOL-PLACEMENT-SCAN -->
## Tool placement scan (advisory, reuse-only)
- Per-turn second opinion on **which existing tool to call first**: `python -B scripts/demo1_tool_placement_scan.py scan "<ask>"` reads the ask + journal/lease/dirty state and prints a ranked JSON list of already-existing script/bat/skill calls (`--list-triggers` audits the table; `--skip-state`/`--no-router`/`--no-git` for offline). It calls `demo1_vibe_skill_router.py resolve` as a subprocess — it never replaces the router and never executes what it ranks.
- Known misroutes it corrects (measured): ForceRestart/restart Meta Display → `$demo1-dev-reload`/`Start-RAG.bat` (not the caption skill); RAG debug trail → `Read-RAG-Debug.bat`→`var/rag-launcher/LATEST.json` (not evidence-debugging); SelfAsk ownership → `SelfAskPlannerOwnershipContractTest`+canonical `SelfAskPlanner.java`; commit-dirty/goal-complete/verify-all-models → `conditional_local_git`/`agent_git_vibe_commit`/`$demo1-goal-complete-stop`/`$demo1-agent-api-spend-guard` (router-null gaps); zombie journal → `work_journal list --active`+`agent_recovery_status` (not safe-cleanup).
- `misroutes[]` rows mean the resolved router primary is a known-wrong target for that ask — prefer the listed `useInstead` calls. Advisory only: routing grants no write authority; file changes still follow work-ledger + lease gates.
<!-- END DEMO1-TOOL-PLACEMENT-SCAN -->
<!-- BEGIN DEMO1-ADAPTIVE-RULE-LAB -->
- For semantic organization of skills/rules/directives or a bounded measured experiment, use `$demo1-adaptive-rule-lab` (sidecar `.agents/skills/semantic-catalog.yaml`; the typed routing index stays authoritative). Similar names or low usage never authorize merging/deletion — promote shared rule changes only from frozen, comparable, independently verified improvement through the existing source-owner workflow.
<!-- END DEMO1-ADAPTIVE-RULE-LAB -->
- Repeated HOLD / index lock / partial GPU failure -> `$scoped-blocker-recovery`; HOLD stays dependency-scoped. Keep this root file to durable repository rules: read `.agents/skills/INDEX.md` only when a specialized workflow is needed; long execution prompts stay under `agent-prompts`; do not load the whole index for ordinary narrow work.
- Select exactly one primary route **per phase**; add a second guard only when independently required. A pasted brief with two independent seams uses `$demo1-devin-source-orchestrator` to sequence phases — not a duplicate route. Route identity = `(kind, canonicalId)`; same-namespace duplicates/collisions fail; verify every route's `source`/`pairedArtifact` against actual repo-relative paths.
- Before application-source mutation, the target-scoped source-owner/lease/preimage/protected-setting/factual checks are mandatory. `$demo1-source-edit-three-way-preflight` is optional review — not a routine prerequisite. Named gates: `$demo1-macsrc-smb-direct-patch` (authorized `Y:\` work), `$patchdrop-safe-patch-orchestrator` (PatchDrop routing), `$demo1-subsystem-patch-directive` (S01-S08 bodies), `$demo1-cross-subsystem-guard` (2+ S01-S08 seams), `demo1_notebook_desktop_goal_handoff` (Notebook -> Desktop GoalContract).

## PatchDrop Bundle Rules
- Full producer/consumer contract — complete-bundle parts, exactly one active cumulative `<slug>-v3` per slug, `sourceIsolation` fail-closed promotion, nested-bundle promotion, MISSING_PATCH/MISSING_REPORT handling, applied/rejected moves after Gradle verification: `$patchdrop-safe-patch-orchestrator` (`.agents/skills/patchdrop-safe-patch-orchestrator/SKILL.md`).
- Never choose a PatchDrop patch by newest timestamp; an ambiguous queue = `patch-drop-pending`. Acquire the source-edit lease as `desktop-consumer` before applying a top-level bundle. Run `__patch_drop__/janitor_inventory.ps1` first.

## AutoLearn Handoff Review
- Before patching AutoLearn failures, read `data/agent-handoff/codex/{manifest.json,cycles.jsonl,rejected.jsonl}` (`accepted.jsonl` = supporting only); as of 2026-09-19 all absent — if still absent report `evidence_needed`, no substitutes.
- `train_rag.jsonl` is the file-backed training SoT (`data/train_rag.jsonl` currently absent; the bundled resource copy is not live). Vector DB rows are staged shadow until promoted. Never overwrite raw JSONL while diagnosing; reports go under `data/agent-handoff/codex/report/`; source fixes stay on existing UAW seams.

## Redaction
- Never log or print raw API keys, client secrets, owner tokens, authorization headers, private environment values, raw sensitive queries, or full environment dumps. Prefer `hasKey`, `keySource`, host/path summaries, counts, timing, reason codes, masked tails, and hash-only values (`queryHash`, `bodyHash`, `backupHash`).
- Public evidence, trace, SSE, and HTML surfaces must remain allowlisted and redacted; raw evidence snippets stay out of TraceStore and UI payloads unless an existing gate explicitly promotes them.

## Evidence And Verification
<!-- BEGIN DEMO1-COMPLETION-CLEANUP -->
### Automatic completion and cleanup
- At the final verified boundary of a patch or report-only task, automatically use `.agents/skills/demo1-completed-directive-cleanup/SKILL.md`. The 2026-09-15 authorization covers stopping completed work and deleting its proven surplus artifacts; do not ask for another routine cleanup confirmation.
- Bind the exact whole-task evidence using `awx.completed-task-cleanup.v1`; neither file age nor an isolated green test permits cleanup — never select another task or patch by newest timestamp. For checkpoint work, prepare `task-cleanup-request.json` in the exact cycle before the final successful `codex_work_checkpoint.py finish`; preserve source, final patches, reports, verification evidence, receipts, recovery files — no recursive or queue-wide sweep. A cleanup failure never requeues a completed patch.
<!-- END DEMO1-COMPLETION-CLEANUP -->
- Existing repo files and real command output beat prompt assumptions; official vendor docs beat memory for external API/CLI/library behavior. If evidence is insufficient, record `evidence_needed: <artifact> / verify with <command>` instead of inventing files, routes, keys, or results.
- Report PASS only for the subset actually run (name suites + counts). A full `:test` run with failures is reported as counts plus per-failure classification — `pre-existing` requires a same-failure preimage/baseline run as evidence; never blanket-declare suite failures "all pre-existing", and never widen a scoped pass into whole-suite health.
- Keep a blocker lane-local and continue independent provable work; never expand a lane-local blocker into a repository-wide `HOLD`. Use Windows/PowerShell-first commands; prefer `gradlew.bat`; verify the narrowest changed surface first, then broaden only across module boundaries.
- With `AWX_SPLIT_BUILD_OUTPUTS=1`/`AWX_BUILD_HOST_ID=desktop`, use `build\desktop\...` for boot proof; broad-test `NoClassDefFoundError` storms with classes present -> `scripts\verify_full_test_refresh.ps1`. Topology: `scripts\verify_control_plane_topology.ps1`. Do not parallelize `bootRun` smokes on the same host/cache dir.

<!-- BEGIN DEMO1-REQUEST-DIAGNOSTIC-CORRELATION -->
### Same-request diagnostic evidence
- Keep the debugged request distinct from the current diagnostic request. Record `providerSurface` and `toolId` with evidence; an empty correlated result must stay empty instead of falling back to global recent errors. Reuse normalized correlation hashes without hashing them again.
- Correlation hashes, a snapshot ID, and `ToolContext` strings identify evidence but do not establish ownership. Use the existing owner check before stored trace or answer-bundle access; describe bounded ring events and durable chat projections separately, with unavailable history explicit.
- Reuse `DebugEventStore`, `TraceSnapshotStore`, the answer trace bundle, `DebugCopilot`, and the existing MCP toolbox. Diagnostic suggestions use registered tool IDs and validated arguments; never execute generated shell strings. ZIP checksums establish file integrity only, not server origin or complete history.
<!-- END DEMO1-REQUEST-DIAGNOSTIC-CORRELATION -->

<!-- BEGIN DEMO1-LOCAL-FIRST-RAG -->
## Local-First RAG Repair Overlay
- Before source work, verify Java 17. Missing optional historical ZIPs, reports, or handoffs do not block live-checkout facts; named attachments, PatchDrop artifacts, AutoLearn intake files, and required acceptance evidence remain mandatory.
- Source mutation requires the existing owner, lease, immediate preimage and applicable PatchDrop checks defined above; a triad verdict is required only when that review is selected; `sessionId` and `ctx.memory` stay read-only unless user and active memory policy authorize mutation.
- Trace RAG defects UI -> controller -> workflow -> `PromptBuilder` -> retrieval -> provider -> stream/response -> persistence/restore; stop at the first source-backed broken seam. Never silently substitute providers or invent results; delivery (HTTP 200, UI/terminal output, hashes) proves neither semantics nor provider/wire attempts. Manage or stop only task-started processes and the task's BAT-managed target servers (DEMO1-SERVER-LIFECYCLE-VERIFY); report unrelated owners.
<!-- END DEMO1-LOCAL-FIRST-RAG -->
<!-- BEGIN DEMO1-GIT-LOCAL-FIRST -->
## Local Source First; Conditional Local Git (this canonical root only)
- Baseline unchanged: the current working tree, active sourceSets, and passing compile/test/runtime results win. Old commits/branches/HEAD are evidence, never a restore source; a clean tree is never a completion condition; `git status` alone never classifies a file as contamination, backup, duplicate, or safe-to-delete (hygiene classes `CURRENT_CANONICAL`/`CURRENT_UNUSED`/`LEGACY_DUPLICATE`/`GENERATED`/`UNKNOWN` — never delete UNKNOWN). Recovery copies come from current bytes snapshotted before the change under `data/agent-handoff/codex-autonomy/<task>/<cycle>/` — never past commits; no `.bak` next to sources; no secrets in recovery copies; keep `.git` and its config in place. Do not open ordinary work with `git status`/history/branch/cleanup checks; agent/editor built-in Git status is never a source-edit gate.
- Git missing, `.git` absent, old history, uncommitted files, or `.git\index.lock` alone do **not** blanket-hold ordinary worktree-edit. `git-operation-active` is a **scoped** hold only for a proven this-root Git writer or proven worktree mutation markers (`MERGE_HEAD` / cherry-pick / revert / rebase / sequencer); a live `git.exe` writer remains a scoped hold, not a repository-wide stop. Keep file-level lease, target reservation, preimage hash checks. Enforcement: `__patch_drop__/source_edit_lease_contract.ps1` + `source_edit_session.ps1`; `agent_preflight.py` stays Git-free.
- Conditional local Git — user authorization on 2026-09-23 replaces the blanket Git-mutation ban for `C:\AbandonWare\demo-1\demo-1\src` only. Policy body `.grok/rules/demo1-conditional-local-git.md`; enforcer `scripts/conditional_local_git.py` (`policy|check|scan|commit`). A session not restarted after this change stays `PATCHED_NOT_RELOADED`. `AGENTS.override.md` is not a substitute: in this directory an override replaces the whole `AGENTS.md`.
- **Allowed without asking again for the same scope:** `git status`, `git diff`, selective `git add` of paths this session owns, a staged-blob secret scan, and one local commit after that scan passes — `python -B scripts/conditional_local_git.py commit --repo . --message-file <file> --path <owned-path>`.
- **Agent commit entry point:** once a session judges its owned changes committable, it calls only `python -B scripts/agent_git_vibe_commit.py --repo . --path <owned>... --message-file <file>` (`--dry-run` prints the plan; `--task-id` journals `AUTO:`). The orchestrator runs check → stale-lock soft-clear → owned add → scan → commit and prints `committed=<sha>`/`deferred=<reason>` JSON; `--preserve-foreign-staged` is its default staging mode and `--strict-staging` selects exact-match. Any agent committing here uses this one path — no ad-hoc `git` commit chains.
- **Still forbidden:** push, remote changes, pull, fetch, merge, rebase, reset, clean, history rewrite, `git tag`/annotated tags, VERSION/CHANGELOG/RELEASE root files, semver bumps, `gh release`, versioned artifact uploads, deleting `.git` or `index.lock`, killing `git.exe`, `--no-verify`, `add -A`, `add .`, `commit -a`, taking or unstaging another session's staged paths, and committing secrets, raw conversation, databases, models, indexes, or large logs. Agents make local selective commits only — no version/release structure (tags, changelogs, release pipelines); a real external consumer needing a milestone tag is a human decision, never an agent branch. This rule does not authorize GraphRAG / Nova Focus / Meta Display application-source edits.
- Selected local commit: `conditional_local_git.py commit --preserve-foreign-staged --path <owned-path>` preserves all other staging and keeps hooks enabled; `--strict-staging` names the exact-staged-set mode explicitly (policy body above).
<!-- END DEMO1-GIT-LOCAL-FIRST -->
<!-- BEGIN DEMO1-GIT-REMOTE-SOLE -->
## Git remote (sole valid)
- Sole valid main remote: https://github.com/UnlimitedAbandonWare/AbandonWareAi
- AbandonWare3 is fully discarded. Never treat as valid remote, temporary origin, migration keep, or backup upstream.
- Do not add a second remote "for convenience." Dual remotes confuse vibe coding — prefer single-repo branch/tag.
- Do not mutate remotes (`remote add/remove/set-url`) or push/merge until the user explicitly asks.
- If local git still lists AbandonWare3: report it, never fetch/push to it, never prefer its SHA over C-root / AbandonWareAi.
- Old docs/ZIP mentioning AbandonWare3 are historical only; C-root + AbandonWareAi win.
<!-- END DEMO1-GIT-REMOTE-SOLE -->
<!-- BEGIN DEMO1-VIBE-GIT-AUTO-CONTINUE -->
## Vibe Git auto-continue (agreed soft branches never ask)
- Soft branches proceed without a question card — journal one `AUTO:<reason>` line instead: a stale 0-byte `index.lock` (past the age threshold, no confirmed `git.exe` writer, unchanged index hash) moves aside via `python -B scripts/conditional_local_git.py lock --repo . --backup-dir data/agent-handoff/<taskId>` (`--days` default 1.0) or inside `agent_git_vibe_commit.py` (`--stale-lock-days` default 0.25 = 6h) → `index.lock.bak-<date>`; commit only this session's paths via `agent_git_vibe_commit.py` (preserve-foreign-staged is its default; foreign staged entries stay staged and byte-identical, never unstaged; unowned files are excluded and journaled, never asked "whose?"); additive gate-tool hardening that keeps the hard constraints proceeds; `git` resolves from PATH or `F:\git\cmd\git.exe` (only a real absence → `git-not-found` BLOCKED).
- Hard list unchanged and never auto-approved — secrets/`apikey.txt`/openssl in logs or commits, `.git`/history rewrite/`reset --hard`/`clean -fdx`/old-HEAD overwrite, push/pull/fetch/merge/rebase/`git tag`/version bump or release files without an explicit ask, foreign lease/journal/staging mutation, `add -A`/`add .`/`commit -a`/`--no-verify`, out-of-scope bulk delete, sandbox bypass (full list: `DEMO1-GIT-LOCAL-FIRST` + `.windsurf/rules/demo1-hard-constraints.md`). Foreign-leased target → stale is reclaimed via `lease_conflict_autoflow.py reclaim`; live gets one `request-release`, work unblocked files, defer the rest — never a user-relay request.
- Detail: `.agents/skills/demo1-vibe-git-auto-continue/SKILL.md` (`$demo1-vibe-git-auto-continue`); gate `$demo1-conditional-local-git` / `$demo1-git-vibe-workflow` / `$demo1-git-secret-guard`.
<!-- END DEMO1-VIBE-GIT-AUTO-CONTINUE -->
<!-- BEGIN DEMO1-VIBE-MAX-AGENCY -->
## Vibe-Max-Agency: 4-agent shared root / evidence / restart contract
- **"최대" = 하드 금지를 제외한 읽기·재기동·lease 범위 쓰기** (max reading, restart, lease-scoped writes) — never: secret reads, forced lease release, unpaid-approved provider calls, global sandbox off, auto-approve-all, or public-profile `agent.db-context` enablement.
- Setup/audit: `Vibe-Max-Agency.bat -Check` / `-Apply` -> `var/debug/vibe-max-agency-status.json`; `-Apply` only appends documented ignore exceptions and enables `main/resources/application-local.yml` `agent.db-context.enabled: true` (gitignored local; base/public untouched).
- Task entry (all four agents): `agent_preflight.py --root .` -> `awx_device_bus.py start` -> `$demo1-vibe-skill-router` (else `$demo1-core-request-router`). Maximum reads: `logs/debug-events*.ndjson`, `var/debug/**`, `data/device-resources/events/`+`registry/`, `var/meta-display-db/export/` via `meta_display_db_export.py`, `/agent/db-context/*` (ADMIN-gated; 401/403 = auth-blocked — `var/dev-admin-token.txt` never printed).
- Writes: only files covered by a valid journal + checkpoint (and lease where lease-bound); scope overlap -> report blocked/wait — **never force-release or delete another agent's live lease** (valid TTL / recent heartbeat / proven-alive owner); a stale expired lease whose owner is not proven dead goes only through `lease_conflict_autoflow.py reclaim` quarantine — never manual lock deletion. Start-RAG/ForceRestart only per DEMO1-SPRING-VIBE-RELOAD / DEMO1-SERVER-LIFECYCLE-VERIFY — never for rules/config-only changes, never killing a worn live session. Cline: no "Restore checkpoint"/message-edit undo — recovery only via `codex_work_checkpoint.py`. Hard prohibitions unchanged: `.secrets/`/`apikey.txt`/`.env*`/key material blocked (values never printed/committed/attached); openssl key name/value/format/structure immutable; raw `data/device-resources` outside `events/`·`registry/` and `var/meta-display-db/lmsdb*.db` blocked; no paid-provider calls or production-secret deploys without explicit user authorization.
<!-- END DEMO1-VIBE-MAX-AGENCY -->
<!-- BEGIN DEMO1-RTX3090-WATCH -->
## RTX 3090 ACTIVE — power issue RESOLVED (DESKTOP-M5NOV6K)
- The RTX 3090 is the **primary local GPU**: run local Ollama embedding/inference and other GPU work on it actively (the 3060 stays the auxiliary lane). The earlier power-peak/driver instability is **RESOLVED 2026-09 — root cause was insufficient power feed, fixed by a dedicated auxiliary PSU feed to the GPU**. Never skip the 3090 or prefer API lanes "because the 3090 is unstable" — that reason is obsolete. PL 80/90 power-limit tuning is not a standing mitigation; keep it as optional past-experiment context only.
- `Watch-Rtx3090.bat` -> `scripts/rtx3090_health_watch.ps1` (read-only) still writes `var/debug/rtx3090-watch/latest.json`; on anomaly also `alert-<ts>.json` + `data/agent-handoff/rtx3090-watch/LATEST.md`. Monitoring stays read-only: agents must NOT change power limit/clocks/PSU settings and must NOT block or reduce normal GPU/LLM/bench load. On anomaly or GPU-health mention, read those files and report hypotheses only (`power_peak_or_limit`/`psu_or_wiring`/`driver`/`unknown`; confidence=low) — `$demo1-rtx3090-health-watch`.
- Local first; API fallback only on a classified explicit failure: an Ollama/embed/LLM timeout·no-response is handled per incident — local retry ≤1 (0 for power/oom/driver signs), then AUTO API fallback in `configs/api-routing.yaml` order (free_local → low_cost → paid_quality; paid needs `AWX_AGENT_ALLOW_PAID_MODELS`) — decide via `python -B scripts/gpu_power_fallback.py decide`; procedure + hard stops in `$demo1-gpu-power-fallback`, `.windsurf/rules/demo1-gpu-power-fallback.md`. First classified failure → API fallback proceeds without a retry question card.
<!-- END DEMO1-RTX3090-WATCH -->
<!-- BEGIN DEMO1-PROTOTYPE-LIGHT -->
## Prototype light mode (no admin, minimal default surface)
- Canonical root is `C:\AbandonWare\demo-1\demo-1\src` only. Do not default to UNC `MacSrc`, `Y:\`, or Notebook SMB paths; they apply only when the user explicitly names them.
- Default tools: `Start-RAG.bat` / `Debug-RAG.bat` / `Read-RAG-Debug.bat` (when present), `scripts/conditional_local_git.py` + `agent_git_vibe_commit.py` for user-requested commits, and the existing chat / Meta Display BAT path — nothing heavier without an explicit ask.
- Default OFF (skip unless the user names it): `demo1-docker-autograder`, `macsrc-*`, `*-smb-*`, `notebook-smb-handoff`, `desktop-smb-ack`, `patchdrop-safe-patch-orchestrator`, `demo1-patchdrop-manual-default`, `macmini-safe-patch-assistant`, and three-node SMB `agent-prompts`. Gated skills carry `PROTO-LIGHT` in their description first line.
- No admin required: never install services, pull Docker images, change SMB share ACLs, register scheduled tasks, or touch firewall settings; run only what works under the current user.
- Prefer local Ollama (`$demo1-gpu-power-fallback` order) and already-configured env APIs; no new SaaS accounts, daemons, or background watchers.
- Search cheaply: no wholesale grep/read of `__patch_drop__/`, `data/agent-handoff/`, or `agent-prompts/` unless the user explicitly asks — they are reference residue, not the working surface. One page: `docs/PROTOTYPE_LIGHT.md`.
<!-- END DEMO1-PROTOTYPE-LIGHT -->
<!-- BEGIN DEMO1-PROTOTYPE-AUTH-LIGHT -->
## Prototype auth-light mode (PROTO_OPEN)
- **Auth mode = PROTO_OPEN** (flag `demo.auth.proto-open`, env `DEMO_AUTH_PROTO_OPEN`, set in `application-meta-display.yml`): this product is a prototype — login/security expectations are intentionally LOW; do not enforce production auth unless the user says "harden".
- Do not require admin login for operator/debug/admin UI features in the prototype. In `proto-open` mode the existing `AdminTokenGuardFilter` grants `ROLE_ADMIN` to every request, so all `.hasRole("ADMIN")` matchers and the dual AdminTokenGuard (filter + interceptor) pass without tokens — do not add extra role gates or a second token check that would re-block the demo.
- Do not quiz the user about "local vs LAN vs public" scope — treat access as globally prototype-open (local, Fold, private net, and external origins share one open scope for the demo).
- Do **not** disable CSRF as a fix — CSRF/cookie handling stays as-is; prefer the existing session paths, but never let an `AdminTokenGuard`/role gate block prototype access when it breaks the demo.
- Never commit plaintext passwords or secrets — credentials stay env/local-only (`.secrets/`, env vars, gitignored `application-local.yml`); rotate and harden later. Risks + hardening TODOs: `docs/PROTOTYPE_AUTH.md`.
- **Never deploy or push with `proto-open` enabled** — it is a development-only posture; a public deployment with this flag on is a hard stop, not a warning.
<!-- END DEMO1-PROTOTYPE-AUTH-LIGHT -->
<!-- BEGIN DEMO1-GOAL-FOOTER-THE-ONE -->
## Codex goal footer (THE ONE protocol)
- When handing Codex a bounded goal, append `docs/operations/codex-goal-footer-the-one.txt`: THE ONE only; decided policy = no re-quiz; no blanket `:test` (focused tests + Verify-RAG only); Browser = 1-2 named repros, Exa/Computer/glm off by default; pasted attachments are hypotheses (live source wins); lease-blocked = stop + report (no bypass); exit leaves a one-line handoff.
<!-- END DEMO1-GOAL-FOOTER-THE-ONE -->
<!-- BEGIN DEMO1-M21222AIN-ADAPTIVE-FALLBACK -->
## m21222ain adaptive fallback + release policy separation (directive 2026-09-24)
- SSOT: `docs/codex/M21222AIN_SOURCE_FIX_DIRECTIVE_2026-09-24.md` + evidence snapshot `docs/codex/M21222AIN_SOURCE_EVIDENCE_2026-09-24.md`. The evidence line anchors are a 2026-09-24 snapshot — verify identity before anchoring: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify_source_evidence_hashes.ps1 -EvidenceMd docs/codex/M21222AIN_SOURCE_EVIDENCE_2026-09-24.md`; a `DIFF` file is re-anchored by method/field, never patched by stale line numbers.
- GPU policy (fixed, no re-ask): RTX 3060 = display + auxiliary AI (embed/rerank/light gen) **kept**; RTX 3090 = primary generation. No 3060 disabling/removal; adaptive bypass reuses the existing `llmroute.gpu.preferred/strict/auto` + `$demo1-gpu-power-fallback` lane order (free_local -> low_cost -> paid_quality; paid needs `AWX_AGENT_ALLOW_PAID_MODELS`).
- Release-policy boundary: new HOLD logic from the directive applies **only** when `evidenceReleaseRequired=true` or the query explicitly must cite; zero citable evidence on a non-required query stays **release** per `DEMO1-EVIDENCE-ZERO-RELEASE` — do not reintroduce "근거 0 = 본문 HOLD". Short answers (1-3 lines) are never expanded merely because RAG/evidence/verification is active; expansion only on explicit user request.
- Implementation scope (`ChatWorkflow`/`RagEvidenceAttributionService`/`AnswerExpander` + tests) belongs to the owning Devin lane (`m21222ain-adaptive-release-gpu-0924-cecd9f9e`); other agents touch rules/docs/tools only and never those Java files while that lane is active. The directive packet's `acceptance_cases` JSON / source-manifest JSON are **missing** — record `evidence_needed`, never invent acceptance rows.
<!-- END DEMO1-M21222AIN-ADAPTIVE-FALLBACK -->
<!-- BEGIN DEMO1-VERCEL-AI-GATEWAY-CREDIT -->
## Vercel AI Gateway / Jev 비용 메모 (agent-visible)

- Team AbandonWare AI Gateway용 AI Credit 관측값(2026-09-24): 잔액 약 USD 22 ($22.32). 잔액은 Vercel 팀 Billing의 AI Credit이며, API 키 문자열 안에 잔액이 들어 있지 않다. 인증은 AI_GATEWAY_API_KEY(또는 OIDC)다.
- Jev(typesafe-ai/jev)는 Gateway evaluation만. 로컬 GML/GLM·Ollama는 생성/임베딩. 채팅 LLM을 Jev로 교체 금지. Jev는 계획 선택 보조(decision)만.
- 프로모 Free는 2026-09-25까지(시각/TZ 미확정). 이후 종량. demo.jev.free-only=true / allow-paid=false면 무료 확인 만료·가격 불명확 시 호출 스킵+기존 경로 유지. 자동 유료 전환 금지.
- Pro 플랜은 해지/미유지 전제. Hobby+카드+AI Credit만 가정. 에이전트가 Pro 업그레이드·Auto-reload·Buy Credit를 유도하지 마라.
- 401=auth_invalid, 403+plan/Pro/ZDR 문구=plan_gate, 기타 403=permission_denied, 429=rate_limited, 5xx=upstream_error. 잔액≠키 유효. 키값 출력 금지. smoke PASS 전 제품 배선 금지. ZDR 규칙 SSOT: `docs/API_ROUTING_SPEC.md` "Vercel AI Gateway — Jev" (기본 OFF; Hobby라 ON이면 403 plan_gate).
<!-- END DEMO1-VERCEL-AI-GATEWAY-CREDIT -->
<!-- BEGIN DEMO1-COOP-VERIFY-RAILS -->
## Cooperative verification rails (multi-agent deferred verify)

- `scripts/coop_verify.py` is the shared deferred-verification rail layered on the existing journal/lease/checkpoint layer (`data/agent-handoff/coop-verify/` store). Skill: `.agents/skills/awx-cooperative-verification/SKILL.md`.
- Another writer editing a ticket scope -> `DEFERRED` + ticket - never `PASS`. Own end-of-turn state is `APPLIED_PENDING_VERIFICATION`.
- Wrappers call `coop_verify writer-begin|heartbeat|end` around real edits and `request`/`run-once`/`watch` for verification. `run-once` exits 10 DEFERRED / 0 PASS / 20 FAILED / 11 INVALIDATED / 30 ERROR.
- Before `begin`/verification: `status`/`recover` first. Orphan writer -> `BLOCKED_UNKNOWN_OWNER` - never silent-reclaim or verify-as-PASS.
- Receipts separate source/built/running/on-glasses evidence. No new busy.lock sole-truth, no state server, no product Java/RAG changes. Contract `DEMO1-DEVIN-COOP-VERIFY-RAILS-FOR-CODEX-20260928`; Codex-side hook/wrapper wiring per `docs/diagnostics/coop-verify-0928/FOR_CODEX.md`.
<!-- END DEMO1-COOP-VERIFY-RAILS -->
<!-- BEGIN DEMO1-F01B-NARROW-JDBC-ASSIST -->
## F01-B narrow JDBC assist rails (Devin track)
- Codex contract DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929 assist only: GATE-0 read-only probe python -B scripts/f01b_schema_gate_probe.py (exit 0 GATE0_PASS / 4 evidence_needed / 5 probe-incomplete), evidence packet docs/diagnostics/f01b-narrow-jdbc-0929.md, skill $demo1-f01b-narrow-jdbc-assist.
- Devin measures gates; product enablement (bandonware.understanding.deferred.enabled, jobs.enabled-types), DDL apply, and in-memory fallbacks are never Devin actions - Codex owns product Java. Never: InMemoryJobQueue, task_ask, n8n, Autograde B, F02.
<!-- END DEMO1-F01B-NARROW-JDBC-ASSIST -->
<!-- BEGIN DEMO1-TRACE-DOCK-ASSIST -->
## Trace-dock always-on assist rails (Devin track)
- Codex contract DEMO1-CODEX-TRACE-DOCK-ALWAYS-ON-20260929 assist only: cost/a11y guardrails docs/diagnostics/trace-dock-always-on-0929/, skill $demo1-trace-dock-assist. visible ON != debug=true/eager HTML; stays unmerged from mgain per-answer trace and from F01-B; product UI is Codex-owned.
- R2 contract DEMO1-CODEX-TRACE-DOCK-ALWAYS-ON-20260929-R2 runs in parallel with JE-1 DEMO1-CODEX-JEV-LATE-ERROR-20260929; product UI stays Codex-owned.
<!-- END DEMO1-TRACE-DOCK-ASSIST -->
<!-- BEGIN DEMO1-VIBE-SELFASK-JUDGE-AUTO -->
## Vibe Self-Ask judge (approval-quiz reduction)
- 사용자에게 승인 퀴즈(1/2/3)를 던지기 **전에** `$demo1-vibe-selfask-judge-auto`(`.agents/skills/demo1-vibe-selfask-judge-auto/SKILL.md`): POSITIVE → NEGATIVE → COUNTEREXAMPLE → NEUTRAL JUDGE가 정확히 하나를 출력 — `AUTO`(가역·로컬·근거 있음, 묻지 않고 진행) / `ASK_ONCE`(불가역·비용·정책 소유, 질문 1개만) / `HOLD`(blocker+재개 조건).
- 판정 후 journal에 `SELFASK_JUDGE AUTO|ASK_ONCE|HOLD | reason | paths` 한 줄 기록. AUTO도 기록해 재질문을 막는다.
- 이 루프는 hard constraint(lease·secret·git remote 금지·flag)를 약화하지 않는다 — commit/push·운영DB·secret 출력·foreign lease는 여전히 ASK/STOP.
<!-- END DEMO1-VIBE-SELFASK-JUDGE-AUTO -->
<!-- BEGIN DEMO1-CODEX-SELFASK-TRIAD -->
## Codex Self-Ask triad (UAW 3-axis subagent fan-out)
- 판정 갈림(예상 밖 RED/GREEN·가설 2회 실패·근거 충돌·P0/P1 완료 주장 직전·ASK_ONCE/HOLD 선택 직전)이면 `.codex/agents-staged/`의 `selfask_definer`·`selfask_aliaser`·`selfask_challenger`(read-only·중첩 금지·모델 상속)를 부착: `python -B scripts/selfask_triad.py packet ...` → `spawn_agent` 축별 1회 → `... judge`가 `agent_vibe_auto_decision.py`와 같은 AUTO/ASK_ONCE/HOLD(exit 0/3/4)를 낸다. 절차·금지 조건 SSOT: `.agents/skills/demo1-codex-selfask-triad/SKILL.md` — `$demo1-vibe-selfask-judge-auto`·`$demo1-triad-deliberation`을 대체하지 않는다.
- 판정 후 journal에 `SELFASK_TRIAD AUTO|ASK_ONCE|HOLD | <reason> | <slug>` 한 줄. 오타·명확한 단일 수정·토큰 절약/중단 지시 시 부착 금지; 작업당 최대 3회. 설치: `scripts/selfask_triad_install.py`(dry-run 기본, 기존 파일 덮지 않음).
<!-- END DEMO1-CODEX-SELFASK-TRIAD -->
<!-- BEGIN DEMO1-CLEAN-QUARANTINE-RAILS -->
## Clean-quarantine mining & vibe AUTO rails (2026-09-29)
- Done/ASK 주장 전 필수 레일(위 Self-Ask 스킬의 CLI 미러): `scripts/agent_done_evidence_guard.py`(exit 2 = 근거 없음·인용 증거 경로 부재), `scripts/agent_vibe_auto_decision.py --action "..." --paths "a,b"`(exit 0=AUTO / 3=ASK_ONCE / 4=HOLD, foreign live lease 자동 감지).
- 격리 Codex rollout 재마이닝: `scripts/quarantine_codex_rollout_mine.py`(줄 단위 스트리밍, 136MB+ hugeline 안전, raw 텍스트를 리포트에 복사하지 않음) →アン티패턴 카탈로그 `docs/diagnostics/codex-quarantine-vibe-antipatterns-20260929.md`; 일괄 프로브 `scripts/agent_access_bundle.py --tracks vibe`.
- session-watch P14 `child-stale-no-evidence`로 부모 미청취 좀비 자식 세션을 --stale-hours 전에 info로 조기 감지한다. Handoff: `data/agent-handoff/clean-quarantine-opt-20260929/`(FOR_CODEX/FOR_DEVIN/STATUS).
<!-- END DEMO1-CLEAN-QUARANTINE-RAILS -->
<!-- BEGIN DEMO1-APIKIT -->
외부 API 되나 확인: .\scripts\apikit.ps1 check (0원). Java 없이.
- 실패 분류 SSOT: `docs/API_ROUTING_SPEC.md` §External API failure classification — 401 `KEY_INVALID_OR_EXPIRED`, 403은 본문으로 `PLAN_GATE`/`FORBIDDEN_REGION_OR_IP` 구분(외부 API에 `auth-blocked` 미사용). 키 만료 원장 `configs/api-key-expiry.json`(env/sha8/last4/expiresAt만, 값 금지).
- 외부 API 관련 보고 첫 행: `provider | http | classification | key sha8 | cost | evidence`. mock/fixture 통과는 `NOT_RUN(실제 확인 안 함)` 표기.
<!-- END DEMO1-APIKIT -->
<!-- BEGIN DEMO1-P6-RESILIENCE-RULES -->
## P6 복원력·검증·데이터 정합성 5대 지침 (2026-10-01, SSOT)
상세 규격: `.agents/rules/subagent-resilience-and-common-verifier.md`. 아래 5원칙은 요약 SSOT.
- **원칙 1 — 에이전트 독립 공통 검증기**: 에이전트 자체 "PASS"는 claim일 뿐. `scripts/common_verifier.py`가 직접 실행해 exit≠0·testCount=0·100% skip·assertion/@Disabled 약화·post-test digest 변경이면 FAIL/INCOMPLETE/REJECTED/INVALIDATED. all-clear는 `VERIFIED_PENDING_APPROVAL`(승인 별도 단계).
- **원칙 2 — 서브에이전트 무한 대기 차단·삼중 경계**: 부모 deadline 상속(단계별 새 시계 금지), 대기열 상한·`CallerRunsPolicy` 금지(메인 스레드 지연 전파 차단), `CompletableFuture.cancel(true)` ≠ 실제 worker 종료(슬롯 반환은 실제 종료 시), 오류 결과 생성에 LLM 체이닝 금지, 늦은 결과는 폐기(중복 final 차단). 모의 하네스: `scripts/fault_matrix_harness.py` + `data/fixtures/fault_matrix_28.json`.
- **원칙 3 — 영수증 없는 checkpoint 전진 금지**: `VectorFlushOutcome.durable()` 없이 batch success/checkpoint 전진 금지(backoff·store_failure·source_rejected는 미커밋), `ATOMIC_MOVE` 실패 은폐 금지, `readOffset`≠`committedOffset`, 미종결 JSONL tail은 재처리(마지막 완성 경계까지만 커밋). 진단: `scripts/check_vector_checkpoint_receipt.py`.
- **원칙 4 — 1차 분류·역할 분리**: 5축(성격·복잡도·문맥·근거·실행가능성) 분류; `max_output_tokens` 단독 승격 금지; 게이트와 `RouterPolicy`는 동일 룰셋(정책 내 `new QueryComplexityGate()`는 알려진 불일치 결함); 모델 경로 파일 존재 여부로 verdict가 갈리는 분기는 결함; 명시적 사용자 모델/검색 설정 불변. 탐침: `scripts/probe_front_router_consistency.py`.
- **원칙 5 — Jev 후보 선별 한정·OAuth 크레딧**: Jev는 검색 후 후보 rerank/filter 보조 신호로 한정(필수 단계·본문 생성 아님, 보호 후보 유지 필수); 70ms는 TypeSafe US-west 벤치이므로 강제 하드 타임아웃 금지; `confidenceAccepted`(확률≥임계)와 scorer confidence는 별개량; ChatGPT 플랜 OAuth는 `chatgpt.tokens.use.direct` + `store:false` + `stream:true` 필수·미지원 필드 격리, 일반 API 키 결제와 별개 레인.
<!-- END DEMO1-P6-RESILIENCE-RULES -->
<!-- BEGIN DEMO1-CODEX-PARALLEL-LANES -->
## Codex parallel lanes (multi-chat same-tree operation)
- 여러 Codex/Devin 채팅을 일부러 동시에 돌릴 때: `$demo1-codex-parallel-lanes` — `codex_lane_plan.py`(레인 분할, 쓰기 범위 비겹침 보장) → 채팅마다 `lane-<id>.txt` 머리말 → 각 채팅 첫 수정 전 `codex_parallel_preflight.py --goal-key <key> --lane <plan/id> --scope <paths>`(OWNER|VERIFIER|TAKEOVER|WAIT, UNCLAIMED_EDIT·STALE_CLAIM dry-run) → claim + writer-begin + `codex_lane_quota.py` 쿼터 → `codex_lane_integrate.py check` 통합 게이트.
- 새 잠금/데몬 없음: 기존 journal·lease·checkpoint 위의 읽기 기반 판정 + 레인 계획 + 쿼터 토큰뿐. 상세: `docs/agent-tooling/codex-parallel-quickstart-ko.md`.
<!-- END DEMO1-CODEX-PARALLEL-LANES -->
<!-- BEGIN DEMO1-GROKBOT-ROLE -->
- Codex 비서 "점"(dot)/Codex 채팅에 Grok Bot 대타 역할 -> `$demo1-grokbot-role` (`.agents/skills/demo1-grokbot-role/SKILL.md`); 팩 SSOT `agent-prompts/devin-agy-grokbot-upgrade-20261002/handover/`; 카드 `docs/agent-tooling/codex-jeom-as-grokbot-ko.md`, primer `agent-prompts/codex-jeom-grokbot-primer.md`.
- 로컬 PC 접근은 사용자만 점 프로필 `Computers > Your computer > Allow access`로 켠다(연결됨 != 작업 권한); 이 역할로도 제품 소스 직접 수정 금지.
<!-- END DEMO1-GROKBOT-ROLE -->
<!-- AWX-TEST-MODEL-POLICY:BEGIN -->
- RAG·챗봇 테스트는 `.agents/skills/demo1-test-model-policy`를 따른다. 2026-12-30까지는 `chatgpt-oauth:*` API 모델로 테스트하고, 화면 기본 모델(로컬 `qwen3.5:9b`)을 그대로 쓰지 않는다. resolve → select → send → check → record: `scripts/test_model_policy.py`.
- 브라우저로 /chat 챗봇을 연습하거나 테스트할 때도 화면 기본 모델을 쓰지 말고 `scripts/chat_practice_browser.js` 또는 `test_model_policy.py resolve`로 모델을 고른다(자유 대화 연습 포함). 2026-12-30까지는 chatgpt-oauth API 모델.
<!-- AWX-TEST-MODEL-POLICY:END -->

<!-- BEGIN DEMO1-AUTH-MODEL-MATRIX -->
## /chat 수정 후 브라우저 매트릭스 (Codex)
- 채팅·스트림·에러표시·chat UI 수정 후 브라우저 검증 = `$demo1-codex-auth-model-browser-matrix` — 상세 SSOT: `.agents/skills/demo1-codex-auth-model-browser-matrix/SKILL.md` (도구 `scripts/chat_auth_model_matrix_browser.js`, `--dry-run` 먼저).
<!-- END DEMO1-AUTH-MODEL-MATRIX -->
<!-- BEGIN DEMO1-OUTPUT-BUDGET -->
## Tool output budget + session state
- 도구 출력 예산·wait·JSON-parse·patch 재시도 SSOT: `.agents/skills/demo1-output-budget/SKILL.md`; 긴 세션 state.md 이어가기: `.agents/skills/demo1-session-state-checkpoint/SKILL.md`
<!-- END DEMO1-OUTPUT-BUDGET -->
