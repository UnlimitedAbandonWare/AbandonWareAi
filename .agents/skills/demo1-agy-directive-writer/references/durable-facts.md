# Durable facts (pointers only)

이 파일은 포인터다. 출처와 다르면 출처가 이긴다. 출처가 바뀌면 이 줄을 고친다.
확인일 2026-09-30 (Grok Bot 인계 팩 반영 2026-10-02). 형식: `- <사실 요약> → <path>#<heading>`
(AGENTS.md 헤딩 포인터는 백틱 없이 쓰고 `,` `)` 또는 줄 끝에서 끝낸다 — 포인터 검사가
그 문자까지를 헤딩으로 잡는다.)

## Project / Git

- Project Root = <repo> (sole remote: UnlimitedAbandonWare/AbandonWareAi) → AGENTS.md#Project Root, `.agents/skills/demo1-project-root/SKILL.md`
- Sole valid remote = https://github.com/UnlimitedAbandonWare/AbandonWareAi (origin only — no other remote); no remote mutation without explicit ask → AGENTS.md#Git remote
- Conditional local Git only (status/diff/selective add/one scanned commit via `scripts/agent_git_vibe_commit.py`); commit/push/add -A/commit -a stay forbidden in directive-writer mode → AGENTS.md#Local Source First

## Cost / providers

- Jev = Vercel AI Gateway evaluation (decision assist) only, never chat-LLM swap; ZDR default OFF (Hobby plan: enabling = 403 plan_gate); `demo.jev.free-only=true`/`allow-paid=false` skip unpriced calls; agent never buys credit or pushes Pro → `docs/API_ROUTING_SPEC.md` §6 "Vercel AI Gateway — Jev evaluation (ZDR rule SSOT)", AGENTS.md#Vercel AI Gateway
- agy generation calls spend the user's own Google quota; `$0` probes = `-p "/skills|/usage|/credits|/model|/config"`, `agy models`, `agy mcp list`, `node tools/agents/skill-lint.mjs`, `Doctor-Agents.bat` → `.agents/skills/demo1-agy-cli-entry/SKILL.md` (Cost rules)
- Provider/search failures classify per `docs/API_ROUTING_SPEC.md` §External API failure classification (401=auth_invalid, 403+plan/ZDR=plan_gate, 402=billing-blocked, 429=rate_limited); a mock pass is `NOT_RUN` for real-provider verification → `docs/API_ROUTING_SPEC.md`

## Display / Nova

- Meta lens = short caption/hint only; mic/controls/settings stay on Fold6/web; live `lensSettings` beat `application-meta-display.yml` factory defaults; last-page interval shrink forbidden → AGENTS.md#Meta Ray-Ban Display runtime, `.agents/skills/demo1-meta-display-simple-caption/SKILL.md`
- Nova Focus (노바 wake-word) answers go in the `focus` field, never `hint`; followup-idle starts at `presentation_done` → AGENTS.md#Nova Focus, `.agents/skills/demo1-nova-focus/SKILL.md`
- PCM/세션 hot-path writes 금지 + NO_CUE 부활 금지 → user standing rule (2026-09-30 대화; SSOT 미등재 — FOR_CLEAN 등재 제안 대상)

## Roles / orchestration

- Role split convention (user standing): Codex = product source · Grok = tools/scripts/mocks · Clean(킴미) = red-team/rule proposals · Devin = runtime evidence/Display/yml/smoke · agy = directive writing + GrokBot 한국어 플로우 계승 (agy executes PASTE_GROK_* targets by default; `~/.grok/**` read-only) · user = decisions → AGENTS.md#Devin / multi-seam source orchestration, AGENTS.md#Multiple Concurrent Devin Sessions, AGENTS.md#External-Agent Directive Loops, `.agents/skills/demo1-codex-plugin-roles/SKILL.md`, `.agents/rules/agy-korean-grokbot-role.md`
- 위 분업은 기본 관행일 뿐 — 실제 오너는 작업별 명시 권한과 대상 파일 종류가 정한다. agy/점은 어느 경우든 제품 소스를 직접 고치지 않는다 → `.agents/skills/demo1-agy-directive-writer/SKILL.md` §4
- Per-agent rule dirs (agy reads as reference only): `.grok/rules/` (Grok), `.clinerules/` (Cline) → those dirs
- Pasted multi-seam brief → `python -B scripts/devin_task_orchestrate.py plan --brief-file <path>` first; one primary skill per phase → AGENTS.md#Devin / multi-seam source orchestration, `.agents/skills/demo1-vibe-skill-router/SKILL.md`
- Skill routing SSOT = `.agents/skills-intent-index.yaml` via `scripts/demo1_vibe_skill_router.py resolve "<ask>"` → AGENTS.md#Skill And Prompt Routing

## Lease / journal / evidence

- Lease lifecycle = `agent_scope_lease.py` check → claim → verify → heartbeat → done|abort; live foreign lease = skip target, never steal; stale → `lease_conflict_autoflow.py reclaim` → `.agents/skills/agent-scope-lease/SKILL.md`, AGENTS.md#Source-edit lease lifecycle
- Work ledger = `work_journal.py` open/note/close + `codex_work_checkpoint.py` begin/seal/finish; new-file preimage = `sha256: null`; status rows only via `scripts/status_doc.py --expect-sha256` → `.agents/skills/demo1-work-ledger/SKILL.md`, AGENTS.md#Work Ledger
- Evidence tiers + test accounting (bind run → command/exit/XML path; never sum runs; `historical` marks pre-final-source runs) → `.agents/skills/demo1-devin-directive-loop/SKILL.md`, AGENTS.md#Evidence And Verification
- Deferred verify rail = `scripts/coop_verify.py` (writer-begin/heartbeat/end; `DEFERRED` is never `PASS`) → AGENTS.md#Cooperative verification rails, `.agents/skills/awx-cooperative-verification/SKILL.md`
- Redaction: env var NAMES only; no raw keys/tokens/headers/env dumps; masked tails/hashes → AGENTS.md#Redaction

## Modes / judges

- Prototype light: canonical root only, no admin/SMB/Docker/scheduled tasks, PROTO-LIGHT skills default-off → AGENTS.md#Prototype light mode, `docs/PROTOTYPE_LIGHT.md`
- Self-Ask judge before any approval quiz: POSITIVE → NEGATIVE → COUNTEREXAMPLE → NEUTRAL JUDGE emits exactly one of AUTO / ASK_ONCE / HOLD → AGENTS.md#Vibe Self-Ask judge, `.agents/skills/demo1-vibe-selfask-judge-auto/SKILL.md`
- CLI mirrors: `scripts/agent_done_evidence_guard.py` (exit 2 = no evidence), `scripts/agent_vibe_auto_decision.py` (0=AUTO/3=ASK_ONCE/4=HOLD) → AGENTS.md#Clean-quarantine mining, `.agents/skills/self-ask-query-rewrite-safe-patch/SKILL.md`

## Build / multi-session

- Multi-session builds: `AWX_SPLIT_BUILD_OUTPUTS=1` + `AWX_BUILD_HOST_ID=<host>` → outputs under `build/<hostId>/`; focused tests only, no clean → `build.gradle.kts` :23-52, AGENTS.md#Evidence And Verification
- Safe patch floor: fewest files/lines, preserve property+secret names, LangChain4j stays `1.0.1`, no archive grafting → AGENTS.md#Safe Patch Rules

## agy mechanics

- agy rule loading: walks cwd→repo root reading `GEMINI.md`/`AGENTS.md`/`.agents/rules/*.md`; 24,000B per-file cap, 20k-token rule budget (overage → path pointer) → `GEMINI.md` (project pointers block)
- agy workspace skills auto-discovered at `.agents/skills/<name>/SKILL.md`; settings at `%USERPROFILE%\.gemini\antigravity-cli\settings.json` (`toolPermission`, `permissions{allow,deny,ask}`, `useG1Credits`) → `builtin:skills/agy-customizations`
- Directive output dir = `%USERPROFILE%\Downloads\`; name `PASTE_<TARGET>_<TOPIC>_<YYYYMMDD>.txt`, never overwrite (append `_R2`, `_R3`) → `SKILL.md` §6 [superseded 2026-10-02 → grokbot-current/HANDOVER.md §3: 이제 Downloads + agent-prompts 이중 저장 + sha12 대조, 저장 도구는 `scripts/brief_save.py`]
- Directive output = 이중 저장: `%USERPROFILE%\Downloads\PASTE_<AGENT>_<topic>_<yyyymmdd>.txt` + `agent-prompts\<agent>-<topic>-<yyyymmdd>\BRIEF.txt` 사본, 두 sha12 일치 확인, 같은 이름이면 `_R2`,`_R3` (덮어쓰기 금지) — 저장·lint·기록부는 `scripts/brief_save.py`; 단 'Downloads만' 단독 산출 요청에는 repo 사본·기록부를 강제하지 않는다 → `grokbot-current/HANDOVER.md` §3, `SKILL.md` §6
- 지시서 기록부 = `data/agent-handoff/brief-registry/briefs.jsonl` (`brief_save.py list|latest|search`) → `grokbot-current/HANDOVER.md` §10

## Grok Bot 계보 (2026-10-02 인계 팩)

- "Grok Bot" 세 가지를 구분한다: (1) **Grok Bot 앱**(현재 데스크톱 비서 — 인계 팩 기준, "Grok Bot 역할"의 뜻) · (2) 옛 웹 GrokBot(`agent-prompts/grokbot-session-primer.md` 대상) · (3) grok.exe(Grok CLI, `~/.grok`) → `references/grokbot-current/HANDOVER.md`
- Grok Bot 앱의 현재 규칙·레시피 원본 5종(지시서 작성·보고서 판정·재개 문장·다중 인계·Top10) = `references/grokbot-current/*.md` (sha12 표는 HANDOVER §표) — 옛 문서와 충돌 시 이 팩이 최신
- Grok Bot 답 형식: 결론 첫 줄 → 근거 2~4줄 → (지시서면) 경로·바이트·sha12 → `말로: 「…」` → `한 줄:` → `grokbot-current/HANDOVER.md` §1
- Grok Bot 지시서 형식: `[ANTI-STOP]` 위·아래, 섹션 순서 고정(0 한 줄 목표→사실→공통 규칙→항목→HOLD→ASK_ONCE→절대 금지→Acceptance→보고 형식), Devin 첫 줄 `@skill` 줄 / Codex 첫 줄 `$skill` 줄 → `grokbot-current/HANDOVER.md` §4
- 보고서 판정·답장 초안·재개 문장(CONTINUE) 레시피 = `grokbot-current/demo1-agent-report-review.md`; 요청→레시피 라우팅은 `.agents/skills/demo1-agy-grokbot-mode/SKILL.md`
- GrokBot recall (read-only, $0): `python -B scripts/grok_to_agy_memory_bridge.py list|show|search|prompts|sync` → `docs/GROKBOT_MEMORY_INDEX.md`, `data/agent-handoff/grokbot/sessions_index.json` (`recentPrompts` = prompt_history 발췌); 워크플로우 형태 SSOT = `references/grokbot-playbook.md` → `.agents/rules/agy-korean-grokbot-role.md`
- 지시서 작성 상시 지침 7원칙 SSOT(입력 게이트·최신본만·기존 결정 보존·증거 등급 분리·기본값 불변·WP≤5·보고 순서) = `docs/GROKBOT_DIRECTIVE_PLAYBOOK.md` (bot.txt:13806-13820 정제); 웹 GrokBot 세션 프라이머 = `agent-prompts/grokbot-session-primer.md` → `docs/GROKBOT_DIRECTIVE_PLAYBOOK.md`
