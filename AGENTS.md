# demo-1 Codex Operating Rules
<!-- BEGIN DEMO1-PROJECT-ROOT -->
## Project Root (default working directory)
- **Project Root** for this checkout is exactly `C:\AbandonWare\demo-1\demo-1\src`. Investigate, search, edit, build, Start-RAG, DevWatch, and verification default to this root; relative paths (`Start-RAG.bat`, `scripts\`, `main\java`, `AGENTS.md`, `.agents\skills`) resolve from here.
- Use a different path only when the user explicitly names one (or a skill names a sibling path for a bounded side artifact). `C:\AbandonWare\demo-1` or `demo-1\demo-1` alone are not the code root.
<!-- END DEMO1-PROJECT-ROOT -->
<!-- BEGIN DEMO1-ANONYMOUS-VIBE-DEFAULT -->
## Anonymous-first vibe coding
- Default scope: immediate use/test of core features without login, signup, account provisioning, or role setup — no auth screens/middleware/user tables/role management/auth deps/forced sign-in redirects unless the user explicitly requests an identity-dependent feature; a starter, skill, checklist, or boilerplate is not that request.
- `demo.interview.enabled` is `false` everywhere (`@Value` defaults + `application-meta-display.yml`): `/` redirects to `/chat`; `/assets/interview/index.html` and `/api/chat/sync` stay anonymously reachable. When `true`, `InterviewDemoFilter` forwards home/chat routes to the interview page and 404-strips login/signup/admin — never broaden the catch-all to `permitAll`; flipping the flag is a product decision, not a hygiene edit.
- Before changing authentication, trace served page -> client request -> filter/controller -> actual data owner; a legacy template or `auth` filename proves neither dependency nor defect. Reuse the existing anonymous path (still preserves per-client ownership, cross-origin checks, credentials, rate/cost admission, private/admin boundaries); verify with synthetic fixtures only — never real accounts, paid generation, or production data.
<!-- END DEMO1-ANONYMOUS-VIBE-DEFAULT -->
<!-- BEGIN DEMO1-OLLAMA-MODEL-LOCK -->
## DEMO1 Ollama Model Lock (DESKTOP-M5NOV6K)
- Model SSOT: `docs/API_ROUTING_SPEC.md` + `configs/api-routing.yaml` (installed allowlist, role defaults, banned→alias map). Live truth: `ollama ls`. Do not invent models; prefer free/local → cheap → paid.
- Before wiring a model into Display conversate, RAG light, or Spring `llm.fast`/`llmrouter.models.light`, run `ollama show <tag>` or `ollama ls`; if missing use the spec's alias map — never silently pull or Spring-default a banned tag. Verify/enforce: `powershell -NoProfile -File scripts/check-model-lock.ps1`.
<!-- END DEMO1-OLLAMA-MODEL-LOCK -->
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
- Recording/handoff: `status_doc.py --expect-sha256`, `run_verified_command.py` (unconfirmed run is never a pass), `work_journal.py handoff`, `awx_session_evidence.py`, `meta_display_db_export.py` (`$demo1-meta-display-db-export`; never live-JDBC the H2 file while the JVM holds it), `codex_home_quarantine.py` hash-bound apply. Watch/cleanup: `agent_session_watch.py` / `Watch-Agents.bat` (`agent-session-watchdog`); `Safe-Cleanup.bat` WhatIf-first (`$demo1-safe-cleanup`).
- Conditional local Git (this root only, user authorization 2026-09-23): `$demo1-conditional-local-git` / `$demo1-git-secret-guard` / `$demo1-git-vibe-workflow`; one local commit only via `python -B scripts/conditional_local_git.py commit --repo . --message-file <file> --path <owned-path>` after a clean staged scan — allow/forbid lines in `DEMO1-GIT-LOCAL-FIRST`.
- Hooks are advisory detection only; the checkpoint apply/restore path is the enforcement. A hook or MCP failure must stay visible and the guarded path still refuses when safety is unproven.
<!-- END DEMO1-AGENT-GUARD-COMMON -->
<!-- BEGIN DEMO1-STALE-HANDOFF-REFERENCE -->
## Stale Handoffs And Finished Goals
- Latest user text wins over older Markdown handoffs, TLS essays, HELLO/DISPLAY TEST baselines, Autolearn cycles, and unselected notebook directives — **reference-only**; on conflict follow the live ask.
- On goal completion use `$demo1-goal-complete-stop` (`.agents/skills/demo1-goal-complete-stop/SKILL.md`). Prefer improving the living SSOT (`AGENTS.md` + active skills) over pasting historical reports into new sessions.
<!-- END DEMO1-STALE-HANDOFF-REFERENCE -->
<!-- BEGIN DEMO1-ASK-STEP-SEARCH -->
## Ask, Search, Stepwise Delivery
- Work in small verified steps; avoid oversized unsupervised sweeps. Factual gaps: web-search first (Exa / official docs); if still ambiguous, irreversible, costly, or missing a plugin/login/secret, write a short report naming options and needed plugins, then **ask** before that branch continues.
- Approved mechanical work inside the current step may proceed; do not re-ask for routine compiles. Goal completion still ends the turn via `$demo1-goal-complete-stop`.
<!-- END DEMO1-ASK-STEP-SEARCH -->
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
<!-- BEGIN DEMO1-DEVIN-SOURCE-ORCHESTRATOR -->
## Devin / multi-seam source orchestration
- Pasted multi-seam source briefs (Devin, Grok, Codex): run `python -B scripts/devin_task_orchestrate.py plan --brief-file <path>` first and follow the returned phases. Hint past-context / "지금부터 새 맥락" / late fallback overwrite -> `$demo1-conversate-hint-context` (input window, not display TTL); Fold other-tab / background listen -> `frontend-display-debug`.
- Fold wear-test: `devin_task_orchestrate.py capture --role wear --invoke` copies a **redacted** Debug-Meta-Display status into `data/agent-handoff/display-debug/`; read `latest.json` `patchHints` before choosing a write seam. Does not replace work-ledger, lease, preimage, or Debug BAT. Devin paste: `@objective-executor @demo1-devin-source-orchestrator` plus the brief (`.devin/PROMPTS/`).
<!-- END DEMO1-DEVIN-SOURCE-ORCHESTRATOR -->
<!-- BEGIN DEMO1-META-RAYBAN-DISPLAY-RUNTIME -->
## Meta Ray-Ban Display runtime (short)
- Display/output contract + runtime policy (settings-driven hold/paging/cue-cycle/budgets): SSOT `.agents/skills/demo1-meta-display-simple-caption/SKILL.md` (`$demo1-meta-display-simple-caption`) + always-on layer `.windsurf/rules/meta-rayban-display-runtime.md`. **Live Fold `#lens-display` prefs win over `application-meta-display.yml` factory defaults** (`display-ttl-ms`/`hintHoldUntil` 20 s, `trigger-quiet-ms` 2.5 s, `cooldown-ms` 10 s, `force-after-ms` 180 s — settings-driven knobs persisted via `lensSettings`; never silently clamp; last-page interval shrink forbidden).
- Live Java/YAML change -> compile then ForceRestart/DevWatch (`$demo1-dev-reload`, DEMO1-SPRING-VIBE-RELOAD); never claim live success from a stale bootRun. Ambiguous Display/RAG/LLM tradeoffs: `$demo1-triad-deliberation` / `$positive-negative-neutral-judge`.
<!-- END DEMO1-META-RAYBAN-DISPLAY-RUNTIME -->
<!-- BEGIN DEMO1-BRAVE-DUAL-KEY -->
## Brave dual-key (Free then Base)
- `BRAVE_API_KEY_FREE` first up to `gpt-search.brave.monthly-quota` (default 2000/month); then same-host Brave **base** via `BRAVE_API_KEY` only — free-quota exhaustion promotes to base, never disables Brave nor jumps to Naver. `BRAVE_SUBSCRIPTION_TOKEN` is a retired env name (never read/alias/fail over); keep header `X-Subscription-Token` with the selected key; `NAVER_*` is not Brave. Contract: `docs/codex/BRAVE_FREE_TO_BASE_ROUTING_DIRECTIVE.md`.
<!-- END DEMO1-BRAVE-DUAL-KEY -->
<!-- BEGIN DEMO1-CONVERSATE-HINT-EVIDENCE -->
## Conversate hint evidence (Fold6 / Meta Display)
- Hints stuck on fixed refusal text, wiped after empty search, or stable questions pushed into retrieval -> `$demo1-conversate-hint-evidence` (FAST for concepts; evidence routing for current/private/high-stakes; never invent citations). Hints **drag old topics** / past-context input window or late-response discard -> `$demo1-conversate-hint-context` (not hint-target-chars / display TTL).
- After green focused tests, if the user self-verifies live, stop (`$demo1-agent-api-spend-guard`). Do not restart an unowned 18180/Fold6 server without explicit approval.
<!-- END DEMO1-CONVERSATE-HINT-EVIDENCE -->
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
- Meta Display webapp / Display-to-RAG implementation -> `$demo1-meta-display-webapp` (`.agents/skills/demo1-meta-display-webapp/SKILL.md`); continuation via `scripts/next_step.py --root .`. Display roles: `$demo1-meta-display-resume` (intake/status), `$demo1-meta-display-sync-client` (E1/E2 + tests), `$demo1-meta-display-verification` (E3/E4 live proof) — keep local client, real sync, Simulator, public HTTPS, hardware proof separate.
- Lens content -> `$demo1-meta-display-simple-caption` (proven display-test-01 surface); mic/controls/settings on Fold6/web; ACK/CONNECTED is not lens proof; never mix DAT, official Web App, and custom relay fixes in one change. Java/Spring edits reaching live -> `$demo1-dev-reload` + DEMO1-SPRING-VIBE-RELOAD.
<!-- END DEMO1-META-DISPLAY -->
<!-- BEGIN DEMO1-SPRING-VIBE-RELOAD -->
## Spring / Start-RAG vibe reload (Java changes must rebuild)
- Runner: `Start-RAG.bat` -> `scripts/start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch -OpenBrowser`. Ports `18180`/`18181`/`18182`; profile `local,meta-display`. **Spring fact:** a running JVM keeps the old classpath — editing `.java` does not update what executes. This repo uses **DevWatch**, not `spring-boot-devtools`.
- After changes under `main/java` or active `main/resources`: wait for `[DEV-RELOAD] socket ready` in `var/dev-reload/dev-reload.log`, or run `start_rag_stack.ps1 -MetaDisplay -ForceRestart`. **Forbidden as proof:** restarting only the old PID; previous `build/` outputs without `:compileJava`/`:processResources`; `-CheckOnly` or HTTP 200 on the old process; a second Meta Display Spring on the same ports. SSOT: `$demo1-dev-reload`.
<!-- END DEMO1-SPRING-VIBE-RELOAD -->
<!-- BEGIN DEMO1-SERVER-LIFECYCLE-VERIFY -->
## Server start/stop + post-edit live verification
| BAT | invokes | controls |
|---|---|---|
| `Start-RAG.bat` | `scripts/start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch -OpenBrowser` | dev runtime 18180/18181/18182 (`local,meta-display`) |
| `Close-RAG.bat` | `scripts/stop_rag_stack.ps1 -MetaDisplay` | dev runtime + restart watchers only |
| `Start-Meta-Display.bat` | `scripts/start_rag_stack.ps1 -MetaDisplay -Wear -OpenBrowser` | wear runtime (own role; no ForceRestart/DevWatch) |
| `Close-Meta-Display.bat` | `scripts/stop_rag_stack.ps1 -MetaDisplay -Wear` | wear runtime only |
- **Autonomous recycle:** without asking, run the Close/Start pair of the task's target server to prove edits live; recycle only affected roles (never restart wear for a RAG-only edit). BAT-managed servers are task-managed even when user-started; explicit "keep running"/"no restart" wins. Close BATs keep the other role, siblings, shared Ollama, unproven processes — never kill java/node by name or port. `Start-Meta-Display.bat` reuses a running wear runtime (`springReused=true`) — `Close-Meta-Display.bat` first for wear-side proof.
- **Freshness proof:** `var/rag-launcher/<ts>-*/result.json` (`status=ready`, `springReused=false`), `spring-owned.json`, armed `[DEV-RELOAD] socket ready`, real endpoint responses — compile success, BAT exit, changed PID, `-CheckOnly`, or old-process HTTP 200 prove nothing. Protected-runtime refusals (`meta-display-wear-runtime-protected`, `launcher-already-running`) are not failures — check port ownership first. Detail: `$demo1-dev-reload` / `$start-rag-reload`.
<!-- END DEMO1-SERVER-LIFECYCLE-VERIFY -->
<!-- BEGIN DEMO1-DEBUG-ENTRYPOINTS -->
## Debug entry points (Debug-RAG / Debug-Meta-Display)
Thin wrappers over `scripts/debug_rag_stack.ps1` (`-Role dev|wear`); same targets as the matching Start/Close pair, never a second runtime path. Default `status` is read-only; no verbose without a symptom. Detail: `.agents/skills/demo1-toolchain-auto-select/SKILL.md` + `$demo1-evidence-debugging`.
- `-Action verify` = one-call post-edit judgement (exit 0/3/6; schema `awx.debug.verify.v2` separates tool-ran vs target-verified — unrun checks stay `skipped|blocked|not_observed`, never a pass). `tail`/`threads`/`jfr`/`restart -Loggers` per `-Action help`; restore normal settings after (matching Close BAT; `status` shows `verboseLogging=absent`).
- **Wear-test:** `capture --role wear --invoke` (DEMO1-DEVIN-SOURCE-ORCHESTRATOR) — timestamps/counts/hashes only, never transcript text or API keys. Debug-started servers close with the matching Close BAT; shared Ollama is never stopped.
<!-- END DEMO1-DEBUG-ENTRYPOINTS -->
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

<!-- BEGIN DEMO1-DEVIN-MULTI-SESSION -->
## Multiple Concurrent Devin Sessions
The user routinely runs **several Devin sessions in parallel against this checkout**. Session isolation rules govern only isolation, collision handling, and close-out hygiene; they never widen default scopes or relax ownership, preimage, lease, or verification gates.
- **Unique scope + one journal per session:** own taskId, declared targets, checkpoint root, `--agent devin-<session-name>`; keep the full returned taskId for its lifetime; write only your own `journal.json` — never note/close a sibling's, never read a foreign `in_progress` as done. Cycle dirs, `decision.json`, `goalId` are per-session. Before any write, inventory active lanes (`work_journal.py list --active`, `source_edit_session.ps1 -Action status`, `data/agent-handoff/codex-autonomy/` cycle dirs).
- **Leases are per session and per target:** `-Action begin` with your own `-TargetManifest`; never impersonate another owner. A foreign live lease on an overlapping target = **skip that file, continue independent work** — never delete/override/steal, never a repo-wide stop; stale-looking locks go only through the dead-owner quarantine procedure. **Target overlap is the collision, not the sessions.**
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
- Keep a blocker lane-local and continue independent provable work; never expand a lane-local blocker into a repository-wide `HOLD`. Use Windows/PowerShell-first commands; prefer `gradlew.bat`; verify the narrowest changed surface first, then broaden only across module boundaries.
- With `AWX_SPLIT_BUILD_OUTPUTS=1`/`AWX_BUILD_HOST_ID=desktop`, use `build\desktop\...` for boot proof; broad-test `NoClassDefFoundError` storms with classes present -> `scripts\verify_full_test_refresh.ps1`. Topology: `scripts\verify_control_plane_topology.ps1`. Do not parallelize `bootRun` smokes on the same host/cache dir.

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
- **Still forbidden:** push, remote changes, pull, fetch, merge, rebase, reset, clean, history rewrite, deleting `.git` or `index.lock`, killing `git.exe`, `--no-verify`, `add -A`, `add .`, `commit -a`, taking or unstaging another session's staged paths, and committing secrets, raw conversation, databases, models, indexes, or large logs. This rule does not authorize GraphRAG / Nova Focus / Meta Display application-source edits.
<!-- END DEMO1-GIT-LOCAL-FIRST -->
<!-- BEGIN DEMO1-VIBE-MAX-AGENCY -->
## Vibe-Max-Agency: 4-agent shared root / evidence / restart contract
- **"최대" = 하드 금지를 제외한 읽기·재기동·lease 범위 쓰기** (max reading, restart, lease-scoped writes) — never: secret reads, forced lease release, unpaid-approved provider calls, global sandbox off, auto-approve-all, or public-profile `agent.db-context` enablement.
- Setup/audit: `Vibe-Max-Agency.bat -Check` / `-Apply` -> `var/debug/vibe-max-agency-status.json`; `-Apply` only appends documented ignore exceptions and enables `main/resources/application-local.yml` `agent.db-context.enabled: true` (gitignored local; base/public untouched).
- Task entry (all four agents): `agent_preflight.py --root .` -> `awx_device_bus.py start` -> `$demo1-vibe-skill-router` (else `$demo1-core-request-router`). Maximum reads: `logs/debug-events*.ndjson`, `var/debug/**`, `data/device-resources/events/`+`registry/`, `var/meta-display-db/export/` via `meta_display_db_export.py`, `/agent/db-context/*` (ADMIN-gated; 401/403 = auth-blocked — `var/dev-admin-token.txt` never printed).
- Writes: only files covered by a valid journal + checkpoint (and lease where lease-bound); scope overlap -> report blocked/wait — **never force-release or delete another agent's lease or an expired lease whose owner is not proven dead**. Start-RAG/ForceRestart only per DEMO1-SPRING-VIBE-RELOAD / DEMO1-SERVER-LIFECYCLE-VERIFY — never for rules/config-only changes, never killing a worn live session. Cline: no "Restore checkpoint"/message-edit undo — recovery only via `codex_work_checkpoint.py`. Hard prohibitions unchanged: `.secrets/`/`apikey.txt`/`.env*`/key material blocked (values never printed/committed/attached); openssl key name/value/format/structure immutable; raw `data/device-resources` outside `events/`·`registry/` and `var/meta-display-db/lmsdb*.db` blocked; no paid-provider calls or production-secret deploys without explicit user authorization.
<!-- END DEMO1-VIBE-MAX-AGENCY -->
<!-- BEGIN DEMO1-RTX3090-WATCH -->
## RTX 3090 Anomaly Watch (DESKTOP-M5NOV6K)
- The RTX 3090 can show intermittent anomalies (fan noise spike, driver events, rare unclean shutdowns); cause is UNCONFIRMED — user runs an MSI Afterburner ~90% power-limit experiment (`power_limit_first_then_consider_psu`). Agents must NOT change power limit/clocks/PSU settings and must NOT block or reduce normal GPU/LLM/bench load.
- `Watch-Rtx3090.bat` -> `scripts/rtx3090_health_watch.ps1` (read-only) writes `var/debug/rtx3090-watch/latest.json`; on anomaly also `alert-<ts>.json` + `data/agent-handoff/rtx3090-watch/LATEST.md`. On anomaly or GPU-health mention, read those files and report hypotheses only (`power_peak_or_limit`/`psu_or_wiring`/`driver`/`unknown`; confidence=low) — `$demo1-rtx3090-health-watch`.
<!-- END DEMO1-RTX3090-WATCH -->
