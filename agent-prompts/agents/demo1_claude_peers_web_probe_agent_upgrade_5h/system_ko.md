# demo1 Claude Peers Web Probe Agent Upgrade 5h Directive

Generated: 2026-06-28 Asia/Seoul
Workspace: `C:\AbandonWare\demo-1\demo-1\src`
Mode: Desktop Safe Patch, 5-hour bounded pass, web-probe-first

이 지시서는 첨부 `claude-peers-suite-manual.html` 및 `claude-peers-suite.zip`을 demo-1 소스에 접목하기 위한 실행 프롬프트다.
목표는 `claude-peers-suite`를 그대로 설치하거나 벤더링하는 것이 아니라, 그 안의 좋은 패턴을 demo-1의 기존
MCP Control Tower, PatchDrop, AgentTool, TraceStore, PromptBuilder 경계에 맞춰 "자동 에이전트 기능"으로 개량하는 것이다.

## 0. Executive Intent

5시간 동안 다음을 한다.

1. 첨부 파일과 웹 문서에서 `peer discovery`, `message queue`, `mutual close`, `summary`, `manual/notify delivery`, `stdio MCP`, `read-only Supabase evidence` 패턴을 수집한다.
2. demo-1 live source가 이미 가진 `awx_mcp_toolbox.py`, `agent tool manifest`, `PatchDrop`, `TraceStore`, `DebugEventStore`, `PromptBuilder.build(PromptContext)`에 매핑한다.
3. 바로 소스 패치가 가능한 작은 RED/GREEN 후보만 패치한다.
4. 외부 키, Supabase project_ref, Claude/Codex/Antigravity 로그인, Browser/Computer UI 증거가 없으면 `evidence_needed`로 남긴다.
5. 결과는 목적 조화, 오케스트레이션, 잠재력에 과감하게 베팅하되, 적용은 Desktop Safe Patch와 실제 Gradle 출력으로만 확정한다.

## 1. Attachment Findings

첨부 `claude-peers-suite`의 durable pattern:

```text
suite: claude-peers-suite
shape: one localhost broker + one MCP server per agent + launchers
broker: localhost:7899 + SQLite, peer registry, message routing, heartbeat, stale peer cleanup
tools: list_peers, resolve_peer, send_message, close_conversation, set_summary, check_messages
delivery: push for Claude channel, notify/manual for non-channel runtimes
identity: ephemeral peer id plus stable logical name
summary: visible 1-2 sentence peer status
closure: mutual close protocol before suppressing accidental further messages
secrets: zero bundled secrets, per-user env file, chmod 600, launcher env only
platform: macOS/Linux native, Windows through WSL2 rather than PowerShell/cmd
```

Do not import this suite directly into `main/java`, `scripts`, `.codex`, `.claude`, or user shell profiles.
Treat it as an evidence bundle and protocol pattern.

## 2. Web Evidence Contracts

Use web probing as a first-class lane, but only with primary sources or attached local code.

Checked source contracts:

- MCP tools are model-controlled and need clear human-visible exposure and denial paths: `https://modelcontextprotocol.io/docs/concepts/tools.md`
- MCP stdio uses newline-delimited UTF-8 JSON-RPC with the client launching the server as a subprocess: `https://modelcontextprotocol.io/docs/concepts/transports.md`
- Claude Code connects to external tools through MCP servers: `https://docs.anthropic.com/en/docs/claude-code/mcp.md`
- Claude Code settings have global/project scope, so prompt/launcher assumptions must not overwrite local operator settings: `https://docs.anthropic.com/en/docs/claude-code/settings.md`
- Supabase MCP should be scoped with `project_ref`, can use `read_only=true`, and feature groups limit tool exposure: `https://supabase.com/docs/guides/ai-tools/mcp`
- Supabase Data API safety needs explicit grants plus RLS: `https://supabase.com/docs/guides/api/securing-your-api.md`
- Supabase changes frequently; check `https://supabase.com/changelog.md` at the start of any Supabase patch lane.
- `claude-peers-mcp` reference pattern is advisory only: `https://github.com/louislva/claude-peers-mcp`

### Web Probe Ledger

The Control Tower `peer_evidence_bus` must also expose a `webProbeRefreshPacket` so Browser/Supabase/official-doc refresh work is executable without storing raw web content, raw query text, screenshot paths, tokens, or cookies.

Record web 탐침 as a first-class artifact. A patch pass must refresh this ledger before changing code that depends on external contracts.

```text
checkedAt: 2026-06-28 Asia/Seoul
method: Invoke-WebRequest / curl.exe -L, primary sources only
MCP tools: https://modelcontextprotocol.io/docs/concepts/tools.md, status=200, sha16=b500aee8a25af2bc
MCP transports: https://modelcontextprotocol.io/docs/concepts/transports.md, status=200, sha16=e4df3c146b524d58
Claude Code MCP: https://docs.anthropic.com/en/docs/claude-code/mcp.md, status=200, sha16=7b87d554327c0b89
Claude Code settings: https://docs.anthropic.com/en/docs/claude-code/settings.md, status=200, sha16=14604f0b3631288c
Supabase MCP: https://supabase.com/docs/guides/getting-started/mcp.md and https://supabase.com/docs/guides/ai-tools/mcp.md, status=200
Supabase changelog: https://supabase.com/changelog.md, status=200, sha16=fb6e0b48189f9475
claude-peers reference: https://raw.githubusercontent.com/louislva/claude-peers-mcp/main/README.md, status=200, sha16=f9800cde62908d02
```

For every external source used in a patch decision, write:

```text
sourceUrl
sourceKind: official_doc | attached_code | upstream_reference | repo_local
checkedAt
sha16 or evidence_needed
contractExtract: max 2 lines, paraphrased
impactOnPatch: allow | block | require_gate | evidence_only
```

`webProbeRefreshPacket` minimum contract:

```text
schemaVersion: awx.web_probe.refresh_packet.v1
mode: read-only-official-sources
fetchTargets: sourceUrl + markdownUrl + requiredSignals, rawContentStored=false
supabaseMcpGate: SUPABASE_PROJECT_REF required, read_only=true, features=database,debugging,docs, mutationAllowed=false
browserProbeGate: storeRawUrl=false, storeScreenshotPath=false, storeDomSnapshot=false
importContract: store extracts only, maxExtractChars<=240, sourceHash required
```

Forbidden web-probe behavior:

- no blind search-result scraping as proof,
- no full article dumps,
- no raw browser cookies, auth headers, local history, personal files, or screenshots uploaded to external services,
- no Supabase live-project claims from public docs alone.

If any web source is unavailable, record:

```text
evidence_needed: <url> / retry with Invoke-WebRequest -UseBasicParsing -Uri <url>
```

## 3. Authority Order

1. Live Desktop source and command output under `C:\AbandonWare\demo-1\demo-1\src`.
2. Root `AGENTS.md`, `.agents/skills`, `agent-prompts`, `scripts/awx_mcp_*`, `main/resources/mcp/*`.
3. Attached `claude-peers-suite` files.
4. Official MCP, Claude Code, Supabase, Spring, Gradle documentation.
5. Older memory, screenshots, stale reports, Mac mini or Notebook claims.

If evidence conflicts, live Desktop proof wins.

## 4. Non-Negotiables

- Safe Patch only: fewest files and lines needed.
- Do not install `claude-peers-suite` into the repo, user home config, `.zshrc`, `.claude.json`, `.codex/config.toml`, Antigravity settings, WSL, or shell profile.
- Do not run `install.sh` from the attachment during this pass.
- Do not modify `apikey.txt`, `apikey.ps1`, `.env*`, shell profiles, real secret setup, `openssl`, or `opnessl`.
- Do not expose `message.send`, `web.search`, Supabase write tools, or browser/computer side-effect tools by default.
- Do not create `pages/api/**`.
- Do not return large payloads as base64. Use artifact ids, manifests, reports, or paths.
- Keep every `dev.langchain4j:*` dependency at `1.0.1`.
- Keep final prompt construction on `PromptBuilder.build(PromptContext)`.
- Missing optional credentials must become provider-disabled or `evidence_needed`, not an outbound call.
- Browser and Computer Use are evidence surfaces, not permission to transmit secrets or automate risky UI actions.
- Supabase stays read-only unless the user explicitly supplies project-scoped proof and authorizes mutation.

## 5. Decomposition Decision

```md
Decomposition decision:
- mode: 4-way
- reason: the attached suite has a peer-broker layer, a tool/MCP layer, a launcher/secrets layer, and a web/Supabase evidence lane. Keeping them separate prevents unsafe direct installation while preserving the useful orchestration pattern.
- axes:
  1. peer-protocol axis: list, resolve, summary, queue, close, heartbeat, stale cleanup
  2. demo1-control axis: MCP Control Tower, PatchDrop, source leases, external evidence audit
  3. agent-tool axis: manifest, registry, invoker, controller, artifact-by-reference, owner/admin gates
  4. web-evidence axis: official docs, Supabase read-only/project_ref, Browser/Computer proof when needed
```

Use `direct` mode only for one exact failing test or manifest mismatch.

## 6. Target Architecture Bet

Bet boldly on this shape:

```text
claude-peers pattern
  list_peers            -> demo1 peer evidence registry over source_scan + producer smoke + PatchDrop inventory
  resolve_peer          -> stable role/topic/node identity from manifest and evidence sidecars
  send_message          -> safe dispatch packet or PatchDrop producer directive, never direct SMB edit
  set_summary           -> redacted current-task summary stored in TraceStore/DebugEventStore or handoff JSON
  check_messages        -> pending PatchDrop, source lease, external evidence audit, unread dispatch queue
  close_conversation    -> mutual close / applied-or-rejected decision record
  heartbeat             -> node smoke, sourceIsolation.guard, lease status, port/cache isolation
```

The demo-1 implementation target is not "chat between random terminals".
It is a `Peer Evidence Bus`: agents become evidence-producing workers, and Desktop is the final verifier.

## 6.1 Purpose Harmony / Orchestration / Potential Bet Matrix

This pass should be ambitious, but every bet must still land on a repo-owned seam.

| Dimension | Meaning In This Repo | Bet | Acceptance Evidence |
| --- | --- | --- | --- |
| purpose_harmony | The agent network improves demo-1's RAG reliability instead of creating another chat layer | peer messages become source-backed evidence, not social chatter | `agent.peer.*` or Control Tower report contains sourceSet, patch topic, proof state, and next action |
| orchestration | Desktop/Mac mini/Notebook, web probes, Supabase, Browser, and Computer lanes are routed through one decision surface | Control Tower becomes the broker-like coordinator; PatchDrop remains the apply gate | `desktop_control_loop`, `external_evidence_audit`, or new focused tests show pending/closed/reopened states |
| potential | High-upside research modes can run without contaminating final prompts or leaking secrets | web-probe-first scout generates ranked patch candidates; high-power modes feed PromptContext only | trace keys include query hash/length, source count, citation contract, and `rawContentStored=false` |
| restraint | Bold exploration never bypasses Safe Patch, owner/admin gates, or LangChain4j purity | side-effect tools stay disabled by default; no launcher install or shell profile edits | manifest/controller/invoker tests prove deny paths and redaction |

Decision rule:

```text
If a change improves orchestration but weakens purpose_harmony, reject it.
If a change increases potential but bypasses PromptBuilder, owner/admin gates, PatchDrop, or redaction, reject it.
If a change can be expressed as web-probe evidence + PatchDrop directive instead of runtime code, prefer the directive first.
```

## 7. Existing Seams To Probe First

Run before any patch:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-Location
git branch --show-current 2>$null
git worktree list 2>$null
git status --short 2>$null
if (Test-Path ".git\index.lock") { Write-Error "[AWX][desktop] index-lock-conflict"; exit 1 }

$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null

.\gradlew.bat projects --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd

rg -n "AgentToolInvoker|ToolRegistry|InternalAgentToolController|tool_manifest__kchat_gpt_pro|awx_mcp_toolbox|external_evidence_audit|desktop_dispatch_packet|TraceStore|DebugEventStore|PromptBuilder" main scripts src/test agent-prompts
```

Known active owners:

```text
main/java
main/resources
src/test/java
src/test/resources
app/src/main/java_clean
app/src/main/resources
scripts
agent-prompts
```

Do not patch inactive mirrors unless Gradle proves them active.

## 8. Five-Hour Execution Board

| Time | Lane | Goal | Patch condition |
| --- | --- | --- | --- |
| 00-30 min | Intake | root, worktree, sourceSet, PatchDrop, Gradle, attachment hash/list | no source patch if intake blocked |
| 30-75 min | Attachment + web probe | map claude-peers tools to demo1 seams, check MCP/Claude/Supabase docs | write only notes/report unless RED test identified |
| 75-150 min | Peer Evidence Bus P0 | prove manifest/controller/invoker can represent peer evidence safely | patch tests and smallest source if missing |
| 150-210 min | Web Probe Scout P0 | add/verify web-probe directive/tool contract with read-only, citation-first, redacted output | patch only if `web.search` or probe is exposed unsafely or cannot report disabled reason |
| 210-270 min | Supabase + external evidence | read-only project_ref/auth gap, PatchDrop producer evidence, source lease closure | no live Supabase mutation |
| 270-300 min | Verification + report | focused tests, prompt build, Gradle gates, secret scan, final directive report | stop on changed failure class |

## 9. Patch Priority Board

| ID | Priority | Patch Target | Patch Only If | Expected Proof |
| --- | --- | --- | --- | --- |
| CP-00 | P0 | no-op evidence | Current source already satisfies contract | proof-backed no-op |
| CP-01 | P0 | Agent tool peer matrix test | Manifest lacks a safe read-only peer/evidence status contract or disabled side-effect policy is ambiguous | focused JUnit |
| CP-02 | P0 | `scripts/awx_mcp_toolbox.py` / tests | Control Tower lacks a safe peer message/close/evidence queue concept already present in sidecars | toolbox test |
| CP-03 | P0 | `main/resources/tool_manifest__kchat_gpt_pro.json` | A new read-only peer evidence tool is proven necessary and implementation exists | manifest validation |
| CP-04 | P0 | `AgentToolInvoker` / `InternalAgentToolController` | peer/evidence responses can leak raw paths, raw messages, raw queries, tokens, or inline large payloads | redaction/security tests |
| CP-05 | P1 | TraceStore/DebugEventStore bridge | peer assignment and close decisions are invisible to orchestration/harmony metrics | trace contract test |
| CP-06 | P1 | Supabase evidence lane | project_ref is available but MCP URL/sample remains unscoped or not read-only | read-only probe |
| CP-07 | P2 | Browser/Computer evidence lane | local UI proof is needed and source lacks artifact-by-reference capture of proof | browser/computer proof report only |

Recommended first source patch if needed:

```text
CP-01: Add or extend a manifest/runtime matrix test that proves peer-style evidence tools are read-only, side-effect message tools stay disabled, and all outputs are redacted/artifact-by-reference.
```

## 10. Candidate Contracts

### CP-01 - Peer Evidence Runtime Matrix

Do not create a live localhost broker in Java first. Start with the contract.

Candidate test:

```text
src/test/java/com/abandonware/ai/agent/tool/PeerEvidenceToolContractTest.java
```

Assertions:

```text
agent.peer.list or equivalent is read_only, admin/consent gated, and returns no raw cwd if public
agent.peer.dispatch or equivalent is disabled/write_controlled unless owner/admin token is present
agent.peer.close or equivalent records a decision without transmitting a message
message.send remains disabled unless explicit owner/admin gate and PatchDrop path are proven
large peer inventories return artifact reference, not inline body
raw API keys, Authorization, cookies, ownerToken, .env, full prompt, full query are absent
TraceStore keys are low-cardinality: peer.count, peer.pending.count, peer.dispatch.topicHash, peer.close.status
```

If no implementation exists and no direct need is proven, do not invent runtime classes. Patch the prompt pack/report only.

### CP-02 - Control Tower Peer Queue

Patch condition:

```text
desktop_dispatch_packet, producer_command_plan, external_evidence_intake, external_evidence_audit, and source_scan cannot express:
- peer summary
- pending message/evidence count
- mutual close decision
- reopen=true for a new task after close
```

Preferred implementation:

```text
scripts/awx_mcp_toolbox.py
scripts/test_awx_mcp_toolbox.py
```

Rules:

- Add a small JSON field or new helper only if existing schemas cannot carry it.
- No raw message body in audit logs; store messageHash, messageLength, topic, role, status.
- Close protocol states: proposed, accepted, closed, reopened, rejected.
- Desktop final apply remains separate from any peer message.

### CP-03 - Manifest Additions

Only after CP-01/CP-02 GREEN, consider manifest entries:

```json
{
  "id": "agent.peer.status",
  "enabled": true,
  "description": "Summarize peer/source-lease/PatchDrop evidence without raw message or path content.",
  "risk": "read_only",
  "scopes": ["internal.read"],
  "readOnly": true,
  "ownerTokenRequired": false,
  "returnsLargePayloadByReference": true
}
```

Do not enable:

```text
message.send
web.search
supabase.write
browser.control
computer.control
```

unless the user explicitly approves that side-effect lane and tests prove admin/owner gating.

### CP-04 - Web Probe Scout

Purpose: make web 탐침 an evidence lane that feeds safe source patch decisions.

Contract:

```text
input: q, domains, recency, purpose, riskClass
output: sourceUrl, sourceTitle, publishedOrCheckedAt, claimSummary, contractImpact, rawContentStored=false
trace: webProbe.qHash, webProbe.qLength, webProbe.sourceCount, webProbe.allowedDomains, webProbe.evidenceNeeded
forbidden: raw API key, raw cookie, raw owner token, raw full prompt, raw personal file upload, full article dump
```

Patch only if an existing probe/debug endpoint leaks raw query/source text or cannot produce a disabled reason.
Otherwise write a Mac mini/Notebook directive and leave runtime untouched.

### CP-04B - Browser / Computer Evidence Lane

Browser and Computer tags mean "visible proof is allowed when useful", not "automate arbitrary UI".

Browser lane:

```text
allowed: inspect local HTML/manual, localhost UI, public docs, rendered prompt artifacts
preferred proof: title, URL, DOM-visible contract, screenshot only when visual layout matters
forbidden: uploading local attachments to third-party pages, entering secrets, accepting risky permission prompts without explicit user approval
```

Computer lane:

```text
allowed: passive Windows-app inspection only if the task cannot be proven from files/browser/shell
preferred proof: app/window name, visible state summary, no raw sensitive screen content
forbidden: terminal automation through UI, password managers, Windows security settings, account/login dialogs, installing new software
```

Patch rule:

```text
Do not add Browser/Computer runtime code for this pass. Use them as evidence collectors and then reduce the result to repo-local files, tests, or PatchDrop directives.
```

### CP-05 - Supabase Read-Only Evidence Lane

Supabase is a read-only proof lane by default.

Required before any live DB claim:

```text
SUPABASE_PROJECT_REF present or MCP URL contains project_ref
Supabase OAuth/PAT context proven by tool response, not by config text
read_only=true unless user explicitly authorizes writes
features limited to database,debugging,docs unless a narrower feature list is enough
RLS/grants checked before claiming Data API exposure
```

If missing:

```text
evidence_needed: Supabase project_ref/auth unavailable / verify with scripts\smoke_supabase_readonly_snapshot.ps1 or supabase_context_probe
```

Do not add a Supabase Java client just to satisfy this lane.

## 11. RED/GREEN Verification Commands

Use only commands available in the current root.

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null

.\gradlew.bat test --tests "*AgentTool*" --tests "*InternalAgentTool*" --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
python .\scripts\awx_mcp_completion_audit.py --root .
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\awx_mcp_toolbox_tests.ps1
```

Prompt-pack verification after editing this directive:

```powershell
python -X utf8 agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_claude_peers_web_probe_agent_upgrade_5h
python -X utf8 -c "import yaml,pathlib,collections; m=yaml.safe_load(pathlib.Path('agent-prompts/prompts.manifest.yaml').read_text(encoding='utf-8')); ids=[a['id'] for a in m['agents']]; dup=[k for k,v in collections.Counter(ids).items() if v>1]; assert not dup, dup; assert 'demo1_claude_peers_web_probe_agent_upgrade_5h' in ids; print('YAML_OK agents=%d duplicate_ids=NONE' % len(ids))"
```

Secret scan, count only:

```powershell
$files = Get-ChildItem -Path main\java,main\resources,src\test\java,scripts,agent-prompts -Include *.java,*.yml,*.yaml,*.properties,*.ps1,*.py,*.md,*.json -Recurse -File -EA SilentlyContinue
$secretPattern = "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Authorization:\s*Bearer|" + "service" + "_role"
$hits = $files | Select-String -Pattern $secretPattern -EA SilentlyContinue
Write-Host "[AWX][claude-peers-upgrade][security] secretHits=$(@($hits).Count)"
```

## 12. Failure Classes

Pick exactly one primary class:

```text
repository-policy
index-lock-conflict
patch-drop-pending
wrong-sourceset
prompt-rule-violation
tool-manifest-mismatch
tool-disabled-exposed
tool-owner-token-bypass
tool-admin-token-bypass
tool-large-payload-inline
tool-redaction-leak
mcp-toolbox-contract
peer-message-unsafe
peer-close-protocol-missing
web-probe-leak-risk
supabase-project-ref-missing
supabase-auth-missing
supabase-readonly-required
langchain4j-version-purity
secret-leak-risk
gradle-distribution-network-cache
cannot-find-symbol
test-failure
other
```

Retry only once per unchanged failure class.

## 13. Source Patch Output Format

Return exactly:

```md
## 요약
- 2~5줄. 첨부 claude-peers-suite에서 무엇을 흡수했고, 실제 소스/프롬프트 수정 범위와 검증 상태만.

## Observation
- 실행한 명령.
- 핵심 로그 최대 10줄.
- repo root / branch / active sourceSets.
- attachment findings.
- web probe sources.
- Supabase / Browser / Computer evidence state.
- Decomposition decision.
- evidence_needed.

## Patch
파일별:
- Observation
- Before / After 요약
- Minimal diff 요약
- Why this file only
- Checkpoint or trace keys
- Secret masking method
- Rollback note

## Verification
각 명령별:
- Command
- Expected
- Observed
- Failure classification
- Retry decision

## Risks & Next
- 최대 5개.
- 목적 조화 / 오케스트레이션 / 잠재력 decision factors 최대 3개.
- confidence: L/M/H.
- next single most urgent patch.
```

Never say build, boot, provider, browser, Supabase, or peer messaging passed without real output.

## 14. One-Shot Instruction

Use this for the next 5-hour pass:

```md
You are Codex on the Windows Desktop canonical root.

Read the attached claude-peers-suite as an evidence bundle, not an installer. Do not run install.sh and do not edit user home config or shell profiles. Your mission is to adapt the useful peer orchestration pattern into demo-1 as a Safe Patch: peer evidence bus, web-probe-first source scouting, PatchDrop/Control-Tower dispatch, redacted summaries, mutual close decisions, and Supabase read-only evidence closure.

Start with root/sourceSet/PatchDrop/Gradle intake. Then run attachment and web probes against official MCP, Claude Code MCP, Supabase MCP, and Supabase API security docs. Patch source only when a focused RED test proves a concrete gap in AgentTool manifest/runtime matrix, MCP Control Tower schema, redaction, artifact-by-reference, peer close protocol, or web-probe disabled-reason handling. Keep side-effect tools disabled unless owner/admin gates and explicit user authorization exist.

If no concrete source gap is found, produce a proof-backed no-op and a ranked PatchDrop directive queue. Do not fabricate Claude peer, Browser, Computer, or Supabase proof.
```
