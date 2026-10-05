# agy CLI Capabilities Cheatsheet (demo-1 amplifier lane)

> Purpose: the underused `agy.exe` 1.2.14 features that make agy a fast
> idea-amplifier (read + judge + deliberate + design directives). Flag surface
> verified live via `agy --help` 2026-09-30. `$0` = no model call; live = spends
> the user's own Google quota (never buy credits, ASK_ONCE on billing flags).
> Launch context: `Start-Agy-CLI.bat` pins cwd to project root, defaults
> `AWX_AGY_YOLO=1` (auto-approve) and `AWX_AGY_EFFORT=high`.

## 1. Reasoning effort — `--effort low|medium|high|max`

Deep inference dial for hypothesis bursts and directive design. Default `high`
(user decision 2026-09-30) via `Start-Agy-CLI.bat`/`run-agent.mjs`; `max` for
high-stakes architecture, `medium` for quick checks. Interactive:
`Start-Agy-CLI.bat --effort max`. Headless:
`tools\agents\agent.cmd agy review p.md --effort max`. Opt out:
`set "AWX_AGY_EFFORT=off"` or pass your own `--effort`.

## 2. Session continuity — `-c` / `--continue`, `--conversation <id>`

Idea context is never lost: `-c` resumes the most recent conversation,
`--conversation <id>` resumes a named one — both pass straight through
`Start-Agy-CLI.bat %*` and `run-agent.mjs` (recorded in run `meta.json`).
Use after a dropped session or to fork a follow-up directive off prior context.

## 3. $0 local probes — slash commands + metadata subcommands

Free, local, no generation: `agy -p "/skills"` (skill list — print mode runs the
slash locally), `agy models`, `agy mcp list`, `agy agents` (available agents),
`agy plugins list`, `agy --version`. These are the doctor.mjs probes — safe in
unattended loops. `--disable-slash-commands` exists for pure print runs.

## 4. `--json-schema` — strict directive output shape

Force a schema on the final result (`--json-schema '<inline>'` or a file path)
so a generated directive/digest validates structurally before it is pasted.
`run-agent.mjs --schema <json|file>` materializes inline schemas to a file for
CLIs that only accept paths (codex). Pairs with `--output-format json` or
`stream-json` for pipelines.

## 5. Headless directive drafting — `tools\agents\agent.cmd agy <role> <prompt.md>`

Non-interactive drafting into `data/agent-handoff/agent-runs/<ts>-agy-<role>/`
(`prompt.md`, `stdout.json`, `stderr.log`, `meta.json` — env **names** only).
`--mode plan` is the default (read-only); `accept-edits` only when explicit.
Amply fits "draft a directive skeleton offline, paste the result".

## 6. Subagents — parallel investigation lanes

`--agent <name>` selects an agent for the session; `agy agents` lists available
ones (self / research lanes). Delegate background evidence sweeps (e.g. a
research subagent to trawl handoffs) while the main lane drafts — the fleet
digest (`scripts/agent_signal_digest.py`) gives them a 1-second shared picture.

## 7. Artifacts & `brain/` — durable run evidence

Every headless run leaves `meta.json` (cli/version/args/exit/duration/env
names). agy's own brain/settings live under `~/.gemini/antigravity-cli/`
(skills, settings, session state — device-local, never committed). The event
bus (`data/device-resources/events/<host>/`) and handoff tree are the shared
memory other agents write and agy reads via the digest.

## Related tooling

- `python -B scripts/agent_signal_digest.py [--json]` — fleet signal in <1s.
- `.agents/skills/demo1-agy-directive-writer/references/idea-burst-rubric.md` —
  burst → adversarial → triad → THE ONE pipeline.
- `tools\agents\cross-check.cmd <path>` — 3-way plan-mode verdict
  (**3 live calls**, high-stakes decisions only).
- Guardrail: STRICT_ZERO — agy never edits `main/**`, `src/test/**`
  (`agy-korean-grokbot-role.md`); directives delegate source edits.

## 8. Grok Bot mode — agy as the Grok Bot stand-in

`Start-Agy-GrokBot.bat` launches agy with the Grok Bot primer (`agent-prompts/agy-grokbot-primer.md`) — `--mode accept-edits`, `--effort high`, and **no** `--dangerously-skip-permissions` unless `AWX_AGY_YOLO=1`. The existing `Start-Agy-CLI.bat` is unchanged.

- Role skill: `.agents/skills/demo1-agy-grokbot-mode` routes "지시서 써줘" / "끝난거야?" / "멈췄는데 뭐라고 해?" / "Top10" to the current handover pack (`references/grokbot-current/`); `demo1-agy-report-review` judges agent reports and drafts replies.
- Brief persistence: `python -B scripts/brief_save.py save --draft <f> --agent <X> --topic <kebab>` — Downloads `PASTE_*` + `agent-prompts\` double-write, sha12 check, registry row; lint FAIL refuses to save. `brief_save.py list|latest|search <q>` recalls who wrote what.
- Reply contract: Korean, verdict first, `말로: 「…」` on directive/review answers, `한 줄:` ending.
- Known limit: git watcher off while `core.repositoryformatversion=0` + `extensions.worktreeConfig=true` (diagnosis only, config untouched).

## 9. agy 4대 특화 영역 (2026-10-05 공식화)

멀티에이전트 협업에서 agy의 1순위 주특기 레인 — SSOT:
`docs/agents-rules/DEMO1-AGY-SPECIALIZATION.md`. 특화는 우선 배정이지
역량 배제가 아니다(범용 보조 계속, STRICT_ZERO 그대로).

1. **문서 수집** — 공식 문서(T1)·GitHub 릴리스(T2)·최신 스펙/에러 원인 신속 리서치.
2. **코덱스 최적화 전달 (Context Curation for Codex)** — 3-Pack: 결론 3줄 + `file:line` 앵커 + diff 10줄 이내 정제 컨텍스트.
3. **지시서 작성** — WP≤5·RED check·`[ANTI-STOP]`·lease 분담 `PASTE_*` 스캐폴딩.
4. **서브 리포터** — `agent_signal_digest.py` 플릿 점검·활성 저널/리스 확인·보고서 교차 검증.

orchestra `lanes.AGY_RESEARCH.for`와 시너지 역할표가 이 4축을 반영한다.
