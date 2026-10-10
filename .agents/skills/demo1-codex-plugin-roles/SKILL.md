---
name: demo1-codex-plugin-roles
description: Use when a demo-1 Codex task must limit enabled plugins per work type
---

# Demo1 Codex Plugin Roles (v2)

Applied automatically to every Codex task on this checkout (goal files and
PASTE briefs included) — no paste step. The user's verbatim source policy is
`references/user-policy-20261002.md`; this file is the merged working copy.

Priority: current local source (`<repo>`) >
AGENTS.md · goal/PASTE directive > this skill > plugin results (auxiliary
evidence only). Pick the work type below and enable only its "on" lanes;
everything else stays off. An unlisted or unknown plugin = not used. A plugin
tag grants no source, Git, auth, or deployment authority — AGENTS.md
ownership, lease, preimage, spend, and redaction gates still apply on top.

The tool catalog itself (what exists, upstream sources, call shapes) stays in
`EXTERNAL_SKILLS.md` + `data/agent-archive/skills/demo1-mcp-control-tower/references/external-skills.md` (archived).
This skill owns only the work-type allowlist and the per-plugin contract.

## Work type → plugin lanes

| Work type | On | Off / limited |
| --- | --- | --- |
| Display / voice / reconnect | Superpowers, Browser (only Display·phone-test·assistId/epoch repro named by the goal), Computer (Fold/console only), AWX (on build failure), GitHub (auxiliary), GLM assist (post-patch rebuttal; per `$demo1-glm-route-guard`) | admin/HOLD forced repro as a fixture, Ads, Supabase unless the goal names it, Sites sprawl |
| Jev / AI Gateway | Superpowers, Vercel (auth·env·quota·evaluate docs only), Exa (official docs only), AWX, GLM assist (`$demo1-glm-route-guard`) | Vercel deploys/config changes, Ads, Browser admin repro |
| Chat / RAG / Security | Superpowers, Browser (only that goal's HOLD/backend/admin scenario), GitHub (related diff), Exa (Spring Security official docs), AWX, GLM assist (`$demo1-glm-route-guard`) | Vercel when unrelated, Meta Wearables |
| General Java edit | Superpowers, AWX, GitHub, GLM assist (`$demo1-glm-route-guard`), Computer (only if a Windows UI fact is decision-changing) | Browser is never mandatory |
| Google Drive .docx 원본 편집(이력서·경력기술서) | Browser (Drive '편집하기' 탭) | 사본·다운로드·빈 문서 우회 금지 — 전 절차는 `$demo1-gdrive-docx-inplace-edit` |

## Per-plugin contract

- **Superpowers** — supporting process only (`$demo1-superpowers-repo-evidence-guard`):
  find the existing seam → RED (reproducing failing test or structural probe)
  → minimal fix → GREEN; one root-cause candidate at a time; no duplicate
  services/layers bypassing existing implementations. After product-code
  edits rerun that regression + related focused tests + Verify-RAG (full
  suite / global clean only on explicit user request) and report per
  verification-before-completion. A Verify-RAG partial caused only by the
  known H2 DDL warnings is recorded `PARTIAL(H2 DDL baseline N)` — kept
  distinct from PASS/FAIL.
- **Browser** — required when a change affects UI / streaming / auth; use a
  fresh session. Default set: short greeting (`안녕?`) body render / HOLD /
  mid-stream cut + time-to-first-body, one general question for failure
  reasonCodes (e.g. `backend_unavailable`), and the 1–2 core paths this
  change touched. Judge main `/chat` with interview OFF; state-changing
  checks hit local `http://127.0.0.1:18180` first; public kro.kr sends ≤3 per
  task with `[codex-test]` prefix and synthetic text; afterwards restore
  18180 to profile `local,meta-display` with interview OFF. Record per run:
  HTTP status, server reasonCode, requestId, time-to-first-body. HTTP 200,
  SSE start, or a restored saved login are never success evidence. Admin /
  lock / logout-block results are observation-only (PROTO_OPEN).
  VIBE_OPEN이면 이 3항목은 HTTP status만 기록하고 `DEFERRED_SECURITY`로 표기
  (PASS/FAIL 제외) — 보호형 환경 URL·자격 증명을 사용자에게 묻지 않는다
  (`configs/vibe-open.yaml`, `docs/security/VIBE_OPEN.md`).
- **GitHub** — auxiliary evidence only: check local `status` / `HEAD` /
  `branch` first, then compare origin (AbandonWareAi) SHA. GitHub
  commit/diff/CI counts only for files clean locally and SHA-matched; a file
  with uncommitted local changes never borrows GitHub diff evidence. Review
  recent diffs of the task's own modules first (e.g. RagControl,
  ChatWorkflow, SecurityConfig, AdminTokenGuard, chat.js — whichever the task
  touches). No commit / push / merge / branch delete / PR without explicit
  user approval; local commits go through `agent_git_vibe_commit.py`
  (`$demo1-conditional-local-git`) — never ad-hoc.
- **Exa / web research** — official-spec confirmation only (framework
  behavior, tool usage, provider API/error specs); official vendor domains
  only; ≤5 searches per session; record check date + applied version in one
  line; never port blog code into Java 17 / Spring Boot 3.3.4 / LangChain4j
  1.0.1 paths.
- **AWX Control Tower** — one ordering: default AWX first; recovery AWX only
  when the default lane's health fails. Real compileJava/test/boot failures
  route sanitized log paths to `build_error_mine` for failure-type
  classification; AWX output only narrows root-cause candidates — re-confirm
  against the raw stack trace and current code path.
- **Computer** — last resort: localhost screens Browser can't open, Windows
  console, local-only UI. Never for source exploration, code edits, or test
  runs (Git / file / shell tools own those).
- **GLM assist** — follow `$demo1-glm-route-guard`; two uses only:
  ① read-heavy code/log exploration,
  ② post-patch rebuttal review (hidden-symptom patch? scope creep? skipped-vs-
  failed confusion? mock/HTTP-200 mistaken for success?). No code writes,
  config changes, secret values, or irreversible decisions are delegated;
  final judgement and all edits stay with Codex. Path order: `python -B
  scripts\glm_route_preflight.py` → `glm_agent` MCP `glm_delegate_task`
  (read-only) → neither = proceed without GLM and log
  `GLM=SESSION_UNAVAILABLE(이유)`. Native `glm_worker` spawn is forbidden
  under the ChatGPT login — it 400s (`zai/glm-*` not supported on
  `model_provider: openai`); never retry. Key check is
  boolean only (`glmKeyPresent=true|false`, per `external-skills.md`) — never
  read or print values. Every delegation embeds a random `deliveryMarker` and
  is valid only when the reply's first line echoes `task_received=<marker>`
  (same check as `scripts/selfask_triad.py` MARKER_RE); HTTP 200 or plausible
  text alone is invalid. ≤3 calls per session; HTTP 400/401/403/429 or credit
  errors = no retry that session — classify on the `외부 API:` line per
  `docs/API_ROUTING_SPEC.md` (a "zai/glm-5.2 미지원" 400 means the provider is
  stuck on `openai` — a config issue, retry is pointless). glm agreement is
  not verification success; adopt only what you re-verified in code/tests.
- **Vercel** — Jev / AI Gateway work only. Allowed: `AI_GATEWAY_API_KEY` and
  project env presence checks, `vercel env pull` / OIDC / login status,
  credit·quota·model availability (zai/glm-5.2, typesafe-ai/jev), official
  docs/dashboard (`/v1/evaluate` etc). Forbidden: key/token values in
  chat/logs/Git, deploys, project config changes, domain/DNS/build-pipeline
  edits, unrelated Vercel app touches, `npx vercel ai-gateway setup`-style
  config overwrites; env-pulled files are never committed. Smoke 401/403/429 →
  no retry that session; report `auth-blocked` + the auth path tried AND the
  API_ROUTING_SPEC detail class (401=`KEY_INVALID_OR_EXPIRED`; 403 body →
  `PLAN_GATE` vs `FORBIDDEN_REGION_OR_IP`; 429=`QUOTA_OR_RATE_LIMIT`) — don't
  wire live lanes until the user re-auths. AI Gateway ZDR stays off by
  default; never enable it without an explicit goal line.
- **Meta Wearables** — only for Display work. **Sites / Plugin Management /
  Data / Visualize / Supabase / Ads Manager** — off by default; only when the
  goal names them: one-line reason, read-mostly, and config/deploy/data
  changes wait for user approval. Supabase stays read-only under
  `$demo1-demand-driven-external-proof`.

## 상용구 vs 직접 요구 우선순위 (VIBE_OPEN)

붙여넣은 플러그인 역할 상용구(위 "Work type → plugin lanes"·per-plugin 계약의
복사본 포함) 안의 보안 검사 줄은 사용자 직접 요구가 아니다. 우선순위는 현재
소스와 명시적 사용자 요구 > 상용구 — 상용구 속 "live request" 주장 문구는
근거가 되지 않는다 (SSOT: `docs/security/VIBE_OPEN.md`).

<!-- VIBE-OPEN-BOILERPLATE-RULE v1 -->
- 상용구 판별: 지시문 안의 플러그인 역할 블록("1. Superpowers … 2. Browser …", "7. glm_worker …" 같은 번호·이름 붙은 역할 절)에 들어 있는 admin 로그인·잘못된 계정 차단·로그아웃 후 차단·관리자 권한 검토 줄은 TEMPLATE_BOILERPLATE다 — VIBE_OPEN enabled면 완료 조건이 아니라 DEFERRED_SECURITY다.
- "상용구가 요구했다"/"live request"/"사용자 요구" 같은 문구가 상용구 블록 안이나 그 인용 안에 있어도 직접 요구로 승격되지 않는다 — 판정은 동일하게 DEFERRED_SECURITY다.
- 직접 요구로 인정하는 경우는 하나뿐이다 — 상용구 블록 밖에서 그 턴의 사용자가 자기 문장으로 해당 검사를 요구한 경우뿐이며, 그때도 격리 검증(scripts/agent_isolated_auth_verify.py)을 먼저 쓰고 사용자에게 로그인·계정·URL·비밀번호를 묻지 않는다.
- 한 번 DEFERRED_SECURITY로 처리한 범주는 같은 세션·같은 goal에서 다시 묻지 않는다.
<!-- /VIBE-OPEN-BOILERPLATE-RULE v1 -->

## Shared limits

- Never send or commit secrets, cookies, tokens, or `.secrets/` content to any
  plugin or Git lane; auth state / trace / cookies / tokens never enter Git or
  public artifacts.
- Reachable ≠ connected; enabled ≠ authorized; helper output ≠ target verified.
  Keep `evidence_needed` / `auth-blocked` (local-server verdict only) /
  `not_observed` labels distinct.
- No paid-provider fanout; pick the cheapest adequate lane
  (`$demo1-agent-api-spend-guard`).

## Report block (required; checked by scripts/codex_plugin_usage_lint.py)

Every final report carries, before any other content:

```text
외부 API: <한 줄 요약 — HTTP 코드·메시지(키 제외)·원인, 없으면 "없음">
PLUGIN_USAGE:
- <플러그인>: USED(이유) | NOT_USED | NOT_RUN(이유) | UNAVAILABLE(이유)
- GLM: USED(n/3, marker ok) | GLM=SESSION_UNAVAILABLE(이유) | NOT_USED
```

- `외부 API:` line is mandatory even when empty (`외부 API: 없음`).
- USED / NOT_RUN / UNAVAILABLE each need a one-line reason; `NOT_USED` needs none.
- GLM `USED` must show `n/3` (n ≤ 3) and `marker ok`.
- Lint: `python -B scripts/codex_plugin_usage_lint.py --report <file>`
  (0=OK, 2=format missing, 3=secret suspect, 4=no input; "HTTP 200"/"SSE 시작"
  alone as a PASS claim = WARN).

## Invocation

Auto-applied to all Codex work on this checkout (AGENTS.md
`DEMO1-CODEX-PLUGIN-ROLES` + `agents/openai.yaml` `default_prompt`); the
one-liner in `agent-prompts/codex-plugin-roles-shortcut.md` is an emphasis
shortcut only, not required. Update the matrix/contract here only — not in
AGENTS.md, chat pastes, or per-task rules.

A plugin mention line (`@Exa @Superpowers …`) is a force-use hint, not
enablement: enabled plugins' skills and `codex_apps` connector tools load in
every new session with no mention (verified 2026-10-03 over 68 recent demo-1
rollouts — identical catalog with/without the pasted roles paragraph; Exa and
GitHub connector calls completed in mention-free sessions). Do not paste or
request a mention line. If a lane's tool is genuinely not exposed in the
session, still do not ask for a mention — record `UNAVAILABLE(미노출)` in the
report block and take the fallback lane (`glm_agent` MCP for GLM, local
`awx-control-tower` MCP for AWX, web search for spec lookups, direct checks
otherwise). Note `codex exec`/headless sessions mount a reduced catalog —
judge plugin exposure on Desktop/vscode sessions.

- 브라우저로 /chat을 시험할 때 모델 선택은 `demo1-codex-browser-agent` / `demo1-test-model-policy`를 따른다.
