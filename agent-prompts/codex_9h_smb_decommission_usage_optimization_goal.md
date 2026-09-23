/goal

# demo-1 9시간 Safe Patch Goal - SMB 서비스 제거 및 Codex 사용량 최적화 지시서

> Canonical prompt source: `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md`
> 대상: Codex / Antigravity / Claude 계열 코딩 에이전트
> 루트: `C:\AbandonWare\demo-1\demo-1\src`
> 모드: Autonomous Safe Patch. 기존 모양새를 유지하면서, active sourceSet 증거와 실제 명령 출력만 기준으로 최소 diff를 적용한다.
> 핵심 목표: SMB/Mac mini/Notebook 상시 조율 의존을 줄이고, Desktop 단독 로컬 증거 루프로 최적화하여 Codex 사용량과 외부 에이전트 왕복 비용을 낮춘다.
> 기본 실행 profile: `desktop_source_safe_patch`

---

## 0. Filled User Intent

나는 이 프롬프트를 코딩 에이전트(Codex/Claude/Antigravity)에 붙여넣어서 demo-1 소스를 장시간 탐침하고 패치하게 한다.

이번 pass의 목적은 기능 추가가 아니다. 기존 Dynamic RAG / Control Tower / Agent Tool / PatchDrop 모양새를 그대로 이어받되, 이제 Codex 사용량을 줄여야 하므로 SMB 기반 다중 에이전트 서비스와 상시 PatchDrop/producer evidence 루프를 제거하거나 기본 비활성화하고, 필요한 경우에만 수동으로 켜지는 구조로 최적화한다.

에이전트는 처음 2시간을 source-only 탐침과 패치 방향 판단에 쓴다. 그 뒤 최대 7시간 동안 증거가 있는 작은 패치만 반복한다. 단, 2시간을 실제로 꽉 채우라는 뜻이 아니라, "충분한 소스 증거 없이 바로 삭제하지 말라"는 시간 예산이다.

사용자가 소스 변경을 금지하면 즉시 `prompt_artifact_only` profile로 전환한다. 이 profile에서는 `agent-prompts/**`, `.agents/skills/**`, `docs/**`, 그리고 해당 prompt/skill을 검증하는 focused `scripts/**` harness/test만 수정할 수 있다. application source, public API, DB/DDL, provider 설정은 수정하지 않으며 prompt artifact 때문에 Gradle을 의례적으로 실행하지 않는다.

원칙:

- 기존 모양새를 갈아엎지 않는다.
- SMB/PatchDrop 안전 게이트는 삭제 대상이 아니라, 기본 실행 경로에서 빠질 후보로 본다.
- Desktop final proof는 유지한다.
- Mac mini/Notebook/Browser/Computer/Supabase proof는 supporting evidence이며, 기본 루프에서 자동 요구하지 않는다.
- 실제 live source가 이미 최적화되어 있으면 `no_patch_needed`로 끝낸다.
- 추측하지 않고 `evidence_needed`를 남긴다.

---

## 1. Role

You are a senior full-stack / AI-systems coding agent working in the demo-1 Desktop canonical root.

Your job is to reduce orchestration overhead and Codex usage while preserving operational quality:

- remove or disable direct SMB edit/service assumptions,
- collapse always-on Mac mini / Notebook producer loops into optional manual evidence lanes,
- keep PatchDrop janitor/apply safety available for explicit handoff cases,
- reduce Browser / Computer / Supabase proof collection to demand-driven read-only probes,
- avoid repeated large context scans and repeated external-agent dispatch,
- preserve active sourceSet, PromptBuilder, LangChain4j, secret-safety, and Gradle verification contracts.

Never produce broad architecture essays when a patch, command, or evidence report is needed.

---

## 2. Current Source Evidence Snapshot

Prompt-authoring-time counts and provider states are deliberately not embedded as current truth. Start with this freshness contract:

```text
canonical_root: C:\AbandonWare\demo-1\demo-1\src
snapshotFresh=false
snapshotAuthority=supporting_only
refreshRequired=true
reuseUntil=HEAD_or_target_hash_or_lock_state_changes
```

Run one current preflight and one `source_scan` per stable `sourceTruthFingerprint`. Reuse the count-only summary until Git HEAD, selected target hashes, `.git/index.lock`, or the PatchDrop top-level queue changes. Do not refresh merely because another reasoning cycle starts.

Relevant live seams seen in source:

```text
main/java/com/example/lms/agent/context/AgentPipelineHealthController.java
main/java/com/example/lms/agent/context/ExternalAgentEvidenceReader.java
main/java/com/abandonware/ai/agent/tool/AgentToolInvoker.java
main/java/com/example/lms/api/internal/InternalAgentToolController.java
main/java/com/example/lms/config/AgentToolOpsConfig.java
main/java/com/example/lms/debug/ai/DebugAiMetricsService.java
main/resources/mcp/awx-control-tower-tools.json
scripts/awx_mcp_toolbox.py
scripts/goal_next_auto.ps1
__patch_drop__/janitor_inventory.ps1
__patch_drop__/janitor_tests.ps1
```

Supabase proof is also live-only. Initialize it as:

```text
supabase: not_probed
mutationAllowed: false
desktopOnlyBlocked: false
decision: supporting_evidence_needed_until_current_read_only_probe
```

Important interpretation:

- This repo already has Control Tower and PatchDrop infrastructure.
- The next patch should not add another SMB coordinator.
- The next patch should reduce default dependence on external producers and UI proof, not remove safety tooling blindly.

---

## 3. Authority Order

Use this priority order when instructions conflict:

1. Current repository files and actual command output.
2. Root `AGENTS.md`, `.agents/skills`, and active `agent-prompts`.
3. Uploaded logs, screenshots, ZIPs, pasted instructions, and this `/goal`.
4. Official vendor documentation for Spring, Gradle, Supabase, Browser/Computer tooling, and external APIs.
5. Older prompts, memory, stale monitor reports, or human wording.

If the user's request conflicts with live evidence, follow live evidence and report the conflict.

Use:

```text
evidence_needed: <missing artifact> / verify with <exact command>
```

Do not guess.

---

## 4. Non-Negotiables

- Safe Patch only: modify the fewest files and lines needed.
- Do not revert unrelated dirty worktree changes.
- When application source is patched, patch only active sourceSets:
  - `main/java`
  - `main/resources`
  - `src/test/java`
  - `src/test/resources`
  - `app/src/main/java_clean`
  - `app/src/main/resources`
- An explicitly selected prompt/tooling lane may instead patch only its owning `agent-prompts/**`, `.agents/skills/**`, `docs/**`, or focused `scripts/**` file. These paths are tooling artifacts, not Java sourceSets.
- Do not patch inactive mirrors unless Gradle proves they are active:
  - `project/src/main/java`
  - `app/src/main/java`
  - `demo-1`
  - `lms-core`
  - archives, backups, generated output
- Do not edit `apikey.txt`, `apikey.ps1`, `.env*`, shell profiles, real secret setup, `openssl`, or `opnessl`.
- Do not print raw API keys, Supabase tokens, service role keys, owner tokens, Authorization headers, cookies, raw DB URLs, raw prompts, raw queries, raw env dumps, or raw full error bodies.
- Keep every `dev.langchain4j:*` dependency exactly `1.0.1`.
- Keep final prompt construction on `PromptBuilder.build(PromptContext)` or the existing equivalent boundary.
- Missing optional credentials must fail soft with redacted `disabledReason` and no outbound call.
- Do not fabricate build, boot, provider, Browser, Computer, Supabase, Mac mini, Notebook, or PatchDrop proof.
- Do not add a new orchestration framework, new SMB service, new agent broker, or new background daemon unless a RED test proves existing repo seams cannot satisfy the goal.

---

## 5. Decomposition Decision

Default decomposition for this pass:

```md
Decomposition decision:
- mode: 3-way
- reason: SMB decommission and usage optimization touches source ownership, external evidence, and UI/diagnostic reporting
- axes:
  1. Runtime/source axis: which active code paths make SMB/PatchDrop/producer evidence part of normal health or goal flow?
  2. External evidence axis: which Mac mini/Notebook/Browser/Computer/Supabase proof lanes are always-on or treated as hard blockers?
  3. Cost/usage axis: which loops, dispatch packets, UI warnings, scans, or agent tools cause repeated Codex/tool use without changing source truth?
```

Use direct mode for one exact failing test, one property default, one UI label, one manifest schema flag, or one script branch.

Do not use 3-way decomposition when the failure is a one-file syntax issue, exact compile error, or one test assertion.

---

## 6. What "Remove SMB Service" Means

Do not interpret this as "delete every file named PatchDrop or SMB".

```text
defaultDirectSmbEdit=false
legacyCompatibilityInput=MACSRC_SMB_DIRECT
guardedDirectMode=YDRIVE_SMB_GUARDED_DIRECT
canonicalWorkspace=Y:\
backingShareIdentityVerified=true|false
backingShareIdentityReason=match|mismatch|evidence-needed
sourceWriteRoot=Y:\|null
authorizedMutation=true|false
externalReadAccess=unrestricted
applicationSourceWriteRootOnly=true
fallbackWorkspace=null
directGateBackingIdentity=required
directGateSourceLease=required
directGatePreimageCas=required
requiredGuardSkill=demo1-macsrc-smb-direct-patch
guardRollbackRequired=true
desktopFinalProof=evidence_needed
canonicalQueryCount=3
```

`MACSRC_SMB_DIRECT` is accepted only as a compatibility input and must never be emitted.

| Mode | Select when | Result |
|---|---|---|
| `SMB_ACCESS` | Read, search, audit, build, or evidence work on the canonical workspace | Access is allowed with `sourceWriteRoot=null` and `authorizedMutation=false`. |
| `YDRIVE_SMB_GUARDED_DIRECT` | A Notebook user explicitly requests application-source implementation on proven `Y:\`, with declared targets and every repository direct gate passing | Run the existing `demo1-macsrc-smb-direct-patch` identity/lease/CAS guard, including rollback, focused verification, and postimage hashes; set `sourceWriteRoot=Y:\`, `authorizedMutation=true`, and retain `desktopFinalProof=evidence_needed`. |
| `LOCAL_PRODUCER` | The default producer path, direct mode is unavailable, or isolation is selected | Use a local clone/worktree and the existing PatchDrop producer contract. |
| `HOLD` | Root, authorization, targets, guard, or verification evidence is absent or fails | Do not mutate source; set `sourceWriteRoot=null`, `authorizedMutation=false`, and `fallbackWorkspace=null`, then request the smallest decision-changing proof. |

Only when all direct gates pass may the result be `YDRIVE_SMB_GUARDED_DIRECT` with `sourceWriteRoot=Y:\` and `authorizedMutation=true`.

If backing identity, index lock, source lease, preimage CAS, reparse traversal, secret safety, or verification fails, return `HOLD` with `sourceWriteRoot=null`, `authorizedMutation=false`, and `fallbackWorkspace=null`; never choose a fallback source root.

Reads, searches, web access, tools, evidence collection, and explicit non-source output remain unrestricted; `YDRIVE_SMB_GUARDED_DIRECT` restricts only the application-source write root.

This decommission removes always-on SMB coordination and unguarded direct editing from the default path. It does not invalidate an explicit repository-owned guarded direct session. Desktop retains canonical ownership and final proof; Mac mini remains a producer and Notebook evidence remains supporting evidence unless every guarded direct gate is proven.

For this repo, "remove SMB service" means:

1. Direct SMB source editing is not the default workflow (`defaultDirectSmbEdit=false`); only an explicit, fully gated `YDRIVE_SMB_GUARDED_DIRECT` session may mutate application source.
2. Mac mini and Notebook producer workflows are optional, manual, and supporting evidence only.
3. Desktop should be able to run a local source-analysis and patch loop without waiting for external producer sidecars.
4. PatchDrop remains as a manual, explicit handoff and janitor safety mechanism.
5. The UI and goal/reporting surfaces should not keep demanding Mac mini/Notebook/SMB proof when the current task is Desktop-only.
6. Continuous or automatic dispatch/export loops should default to disabled or dry-run unless the user explicitly requests multi-node producer work.

Keep:

```text
__patch_drop__/janitor_inventory.ps1
__patch_drop__/janitor_tests.ps1
PatchDrop apply gates for explicit external bundles
Desktop final source apply and Gradle proof
source_scan and local completion audit
Supabase read-only evidence_needed reporting
Browser/Computer smoke evidence as optional supporting proof
```

Remove, disable, or demote when live evidence proves the seam:

```text
default Mac mini/Notebook dispatch requirements
automatic producer kit export
automatic desktop_dispatch_packet write mode
external evidence as mandatory completion blocker for Desktop-only work
UI warnings that imply missing SMB proof is a runtime failure
goal_next loops that keep asking for producer sidecars after local Desktop proof is enough
source leases that block Desktop-only patching when no top-level PatchDrop candidate exists
```

---

## 7. Bounded Reconnaissance - Up To The First 2 Hours

Run from Desktop canonical root. `desktop_source_safe_patch` performs source reconnaissance. `prompt_artifact_only` limits reconnaissance to the named prompt/skill/harness owners and skips application-source candidate expansion.

### 7.1 Preflight

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-Location
git rev-parse --show-toplevel 2>$null
git branch --show-current 2>$null
git worktree list 2>$null
$status = @(git status --short 2>$null)
Write-Host "[AWX][desktop] statusCount=$($status.Count)"
if (Test-Path ".git\index.lock") { Write-Error "[AWX][desktop] index-lock-conflict"; exit 1 }

powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__\janitor_inventory.ps1
Pop-Location
```

Expected:

- repo root is the Desktop canonical root,
- no `.git/index.lock`,
- no active top-level PatchDrop patch unless the current task is explicitly to consume it.

If blocked:

```text
failureClass: index-lock-conflict | patch-drop-pending | branch-ownership-mismatch | worktree-overlap | smb-conflict-risk
action: stop source edits and report evidence_needed
```

### 7.2 Active SourceSet Proof

Use stdin JSON to avoid PowerShell quoting issues:

```powershell
'{"nodeRole":"desktop","root":".","requestId":"smb-decommission-source-scan","sessionId":"smb-decommission"}' |
  python scripts\awx_mcp_toolbox.py --input-json - source_scan
```

Record and reuse:

```text
sourceTruthFingerprint=<HEAD + selected target hashes + index lock + PatchDrop top-level count>
summaryReuseCondition=unchanged_fingerprint
repeatProbeRecommendation=do_not_repeat_until_input_changes
```

Then list target files:

```powershell
$sourceHits = @(rg -n "PatchDrop|patchdrop|SMB|smb|producer_command_plan|desktop_dispatch_packet|external_evidence|sourceLease|source-lease|computer-use|browser|supabase|usage" `
  main\java main\resources scripts src\test agent-prompts AGENTS.md -S)
Write-Host "[AWX][desktop] sourceHitCount=$($sourceHits.Count)"
$sourceHits | Select-Object -First 80
```

Do not read all results into the final answer. Keep counts, at most 80 decision-changing rows, and the selected target paths. After selection, run `git status --short -- <target paths>` instead of reopening the whole dirty worktree.

### 7.3 Cost/Usage Candidate Scan

Look for these patterns:

```powershell
$costHits = @(rg -n "write_dispatch|producer_kit|require_producer_bundles|submit_.*patchdrop|run_.*node_smoke|collect_.*producer|externalEvidenceComplete|browser_ui_smoke_missing|computer_use_smoke_missing|project_ref_missing|goal_next_auto|desktop_control_loop" `
  scripts main\java main\resources src\test -S)
Write-Host "[AWX][desktop] costHitCount=$($costHits.Count)"
$costHits | Select-Object -First 80
```

Candidate files to inspect first:

```text
scripts/goal_next_auto.ps1
scripts/awx_mcp_toolbox.py
scripts/awx_mcp_completion_audit.py
main/resources/mcp/awx-control-tower-tools.json
main/java/com/example/lms/agent/context/AgentPipelineHealthController.java
main/java/com/example/lms/agent/context/ExternalAgentEvidenceReader.java
main/resources/templates/chat-ui.html
main/resources/static/js/chat.js
src/test/java/com/example/lms/agent/context/AgentPipelineHealthControllerTest.java
scripts/goal_next_auto_tests.ps1
```

### 7.4 Supabase Boundary

Supabase is read-only unless project-scoped auth is proven.

```powershell
'{"nodeRole":"desktop","root":".","requestId":"smb-decommission-supabase","sessionId":"smb-decommission"}' |
  python scripts\awx_mcp_toolbox.py --input-json - supabase_context_probe
```

If `project_ref_missing`, `SUPABASE_ACCESS_TOKEN` absent, CLI missing, or MCP auth incomplete:

```text
supabase: supporting_evidence_needed
mutationAllowed: false
patchScope: local source only
desktopOnlyBlocked: false
primaryNextAction: do_not_set_SUPABASE_PROJECT_REF_unless_RequireSupabaseProof
```

Do not create migrations, DB schema changes, or Supabase client code for this pass. Do not stop a Desktop-only Safe Patch pass solely because `SUPABASE_PROJECT_REF` or Supabase auth is missing; preserve the read-only evidence row and continue local source/PatchDrop/Gradle proof. Treat Supabase live proof as a primary blocker only when the run explicitly requests Supabase proof, DB schema proof, or a Supabase-touching source change.

### 7.5 Browser / Computer Boundary

Use Browser or Computer only when a local UI proof is necessary.

Default:

```text
browser: optional supporting evidence
computer-use: optional supporting evidence
no source patch should depend on Browser/Computer proof unless the changed surface is UI-only
```

If smoke files are missing, do not loop. Report:

```text
evidence_needed: browser/computer smoke missing / run only if UI proof is required
```

### 7.6 Superpowers Boundary

Use Superpowers only as a process helper, not as authority over repo evidence.

Default:

```text
superpowers: optional planning/process support
repo evidence: authoritative
AGENTS.md and active sourceSet proof: higher priority
```

Rules:

- If a Superpowers skill is available and clearly relevant, use it to structure investigation or verification.
- Do not let Superpowers require broad design-doc or implementation-plan ceremony when the current blocker is an exact Safe Patch seam.
- Do not let Superpowers override secret-safety, active sourceSet proof, Desktop final verification, LangChain4j purity, or `PromptBuilder.build(PromptContext)` boundaries.
- If Superpowers is unavailable, continue with repo-local Safe Patch policy and report:

```text
superpowers: evidence_needed / skill unavailable in this agent session
```

---

## 8. Patch Priority Board

| Priority | Target | Patch Rule | Verification |
|---|---|---|---|
| P0 | Root/sourceSet safety | Confirm active roots before touching source | `source_scan`, `checkSourceSetHygiene` |
| P0 | Secret safety | Do not print or persist secrets | changed-file secret scan count only |
| P0 | SMB direct-edit assumptions | Remove direct SMB edit as normal path; classify as forbidden | focused text/test check |
| P0 | External producer as hard blocker | Desktop-only work must not require Mac mini/Notebook sidecars | `goal_next_auto_tests.ps1` or focused Java test |
| P0 | Dispatch write defaults | `desktop_dispatch_packet`/producer kit write modes must be opt-in | toolbox tests |
| P1 | UI/health rollup | Missing optional Browser/Computer/Supabase proof should read as supporting/evidence_needed, not runtime failure | controller/UI tests |
| P1 | Codex usage reduction | Prefer local source scan and cached summaries over repeated external dispatch | focused script tests |
| P1 | Agent tool side effects | Side-effect tools remain disabled/admin-gated | `*AgentTool*`, `*InternalAgentTool*` tests |
| P2 | Prompt/docs cleanup | Update prompt packs or AGENTS pointers only after source behavior is proven | prompt build or no-op report |

---

## 9. Candidate Patch Lanes

Patch only the first lane with confirmed active evidence. Stop when the failure class changes.

### Lane A - Desktop-Only Completion Mode

Goal:

Desktop local proof should be enough for Desktop-only source patch tasks.

Look for:

```text
goal_next_auto.ps1 demanding:
- macmini_node_smoke_json
- notebook_node_smoke_json
- producer_handoff_json
- patchdrop_v3_sidecars

awx_mcp_toolbox.py / awx-control-tower-tools.json demanding:
- require_producer_bundles=true by default
- externalEvidenceComplete=false as a hard blocker
- desktop_dispatch_packet write_dispatch suggestions for ordinary source work
```

Allowed patch:

- Add or use an existing `desktop-only`, `local-only`, `require_producer_bundles=false`, or `externalEvidenceMode=optional` branch.
- Make Desktop-only completion keep Mac/Notebook proof as `supporting_evidence_missing`, not a hard stop.
- Preserve hard stop only when the task explicitly says Mac mini/Notebook/PatchDrop handoff is required.

Do not:

- remove PatchDrop janitor scripts,
- remove external evidence audit schemas,
- skip Desktop Gradle proof.

Focused tests:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto_tests.ps1
python scripts\awx_mcp_completion_audit.py --root .
```

If too broad, add one narrow test around the changed branch only.

### Lane B - PatchDrop Manual-Only Default

Goal:

PatchDrop remains safe but not always part of normal Codex work.

Look for:

```text
PatchDrop is shown as WARN when no active top-level patch exists
nested/applied/history files are counted as active queue
source lease blocks Desktop-only source edits when no top-level patch is pending
```

Allowed patch:

- Separate `topLevelPatchCount`, `pendingProducerCount`, `nestedReferenceCount`, and `appliedHistoryCount`.
- Treat top-level empty queue as OK for Desktop-only mode.
- Keep report-only/nested producer artifacts as supporting evidence.

Verification:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__\janitor_tests.ps1 -Suite CoreGuards
powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__\janitor_inventory.ps1
```

### Lane C - Browser / Computer / Supabase Demand-Driven Evidence

Goal:

Missing optional external proof should not trigger repeated Codex work.

Look for:

```text
browser_ui_smoke_missing
computer_use_smoke_missing
project_ref_missing
run_computer_use_lightweight_smoke
run_browser_local_ui_smoke
run_supabase_schema_snapshot
```

Allowed patch:

- Keep these as `evidence_needed` rows.
- In Desktop-only source patch mode, move them below runtime/build proof.
- Do not ask for Browser/Computer/Supabase proof unless the patch touches those surfaces.
- If UI labels are noisy, change display wording only, not source truth.

Supabase rules:

- Read-only only.
- No live SQL without project ref and auth.
- No service role key.
- No schema mutation.

Verification:

```powershell
'{"nodeRole":"desktop","root":".","requestId":"supabase-readonly","sessionId":"smb-decommission"}' |
  python scripts\awx_mcp_toolbox.py --input-json - supabase_context_probe
```

### Lane D - Agent Tool Usage and Side-Effect Guard

Goal:

Reduce Codex/tool usage by keeping high-cost or side-effect agent tools disabled unless explicitly requested.

Look for:

```text
AgentToolInvoker
InternalAgentToolController
ToolRegistry
AgentToolOpsConfig
DebugAiMetricsService usage(...)
main/resources/tool_manifest__kchat_gpt_pro.json
```

Allowed patch:

- Preserve artifact-by-reference for large outputs.
- Keep side-effect tools owner/admin gated.
- Add usage count summaries, not raw payload logs.
- Reject tool responses that inline large source/log data.
- Prefer `source_scan` summaries over full file dumps.

Verification:

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
.\gradlew.bat test --tests "*AgentTool*" --tests "*InternalAgentTool*" --no-daemon --project-cache-dir $pcd
```

### Lane E - Prompt Pack Cleanup

Use this only after runtime/script behavior is proven or if no runtime patch is needed.

Goal:

Replace older SMB-heavy prompt wording with Desktop-local, demand-driven wording.

Allowed patch:

- Add a standalone prompt artifact under `agent-prompts/`.
- Update `AGENTS.md` only as a short pointer, not with long workflows.
- Rebuild manifest only if you edit a registered prompt pack.

Verification:

```powershell
python -X utf8 agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent <agent_id>
python -X utf8 scripts\test_agent_prompt_secret_patterns.py
```

If the prompt is standalone and manifest is not touched, report that manifest rebuild was intentionally skipped.

### Lane F - Request-Scoped Count-Only Proof

Use this lane only when the task asks for exact app prompt/options/response hashes or provider-attempt correlation without a public API change. In `prompt_artifact_only`, this lane may edit only the existing harness/prompt/test owners.

Consume the existing listener's `[LLM_REQUEST_PROOF]` rows from a bounded post-request byte range. Match only the exact redacted request hash; never scan every repository log or persist the original proof line.

Required aggregate:

```text
schemaVersion=awx.chat-request-proof.v1
requestHashCount
sequenceCount
appPromptHashSha256Count
appOptionsHashSha256Count
appResponseHashSha256Count
adapterAttemptObservedCount
clientHttpExchangeObservedCount
clientHttpResponseObservedCount
providerAttemptObservedCount
wireAttemptObservedCount
providerEvidenceStatus=observed_positive|observed_zero|not_emitted
correlationStatus=verified|partial|ambiguous|evidence_needed
modelSuccessClaimAllowed=false
rawPromptStored=false
rawOptionsStored=false
rawResponseStored=false
rawProofLinesStored=false
```

Correlation can be verified while provider evidence is `observed_zero`. That is valid negative evidence, not model success. Never infer provider/wire success from `clientHttpResponse`, a response hash, a Browser-rendered answer, or a process-lifetime usage delta.

Hard gates:

- capture relevant source hashes and listener byte offset before the request;
- accept only unique sequences for one request hash;
- require `clientHttpResponseObservedCount <= clientHttpExchangeObservedCount <= adapterAttemptObservedCount`;
- classify unknown response hashes as `partial`;
- classify mixed request hashes, duplicate sequences, or changed source hashes as `ambiguous` or `worktree-overlap`;
- do not add a controller, endpoint, response field, or source instrumentation to make the harness green.

### Lane G - Independent Positive, Negative, And Neutral Review

Use this lane for decision-sensitive prompt, harness, provider-proof, or fallback changes. When available, follow `.agents/skills/demo1-agentic-chat-postprocess/SKILL.md`. Produce four isolated, count/hash-only packets; do not turn them into votes or let one review inherit another review's conclusion.

`SUPPORT_CONTRACT` prompt:

```text
Role: positive contract reviewer.
Input: exact claim, changed-file hashes, focused command outcomes, and the
awx.chat-request-proof.v1 aggregate for one request hash.
Check: schema fields, unique sequence counts, prompt/options/response hash
presence, and clientHttpResponse <= clientHttpExchange <= adapterAttempt.
Output: SUPPORT | HOLD, supportedInvariants, missingEvidence, counterexample.
Do not inspect raw prompts, raw responses, credentials, or full proof lines.
```

`SUPPORT_SCENARIO` prompt:

```text
Role: positive scenario reviewer, independent from SUPPORT_CONTRACT.
Input: redacted Korean/English scenario IDs, expected reason codes, count-only
request aggregates, and current focused test outcomes.
Check: adverse negation, ambiguous intent, missing optional provider evidence,
and zero-attempt behavior without assuming the contract review passed.
Output: SUPPORT | HOLD, passedScenarios, failedScenarios, missingEvidence.
Do not read or reproduce raw user prompts or provider responses.
```

`FALSIFY` prompt:

```text
Role: negative reviewer.
Input: the exact claim and the same bounded hashes, counts, and command evidence.
Search for: mixed request hashes, duplicate sequences, unknown response hashes,
stale source hashes, provider/wire inference from a UI answer, and false-green
zero-attempt cases.
Output: FALSIFIED | NOT_FALSIFIED | EVIDENCE_NEEDED, disproofs,
minimalReproduction, decisionChangingGap.
A single reproducible false green can force HOLD even if both SUPPORT packets pass.
```

`NEUTRAL` prompt:

```text
Role: neutral adjudicator; do not gather evidence, browse, call providers, or
implement a patch.
Input: SUPPORT_CONTRACT, SUPPORT_SCENARIO, FALSIFY, and the current verification
summary with exact source/request hashes.
Output: APPLY | HOLD | REJECT, decisiveFactors (maximum three), unresolvedGap,
and one next action.
Rules: no majority voting; FALSIFY controls when it proves a false green;
providerEvidenceStatus=observed_zero|not_emitted forbids a model-success claim.
```

Store only role, verdict, counts, hashes, reason codes, and bounded command outcomes. Keep `rawPromptStored=false`, `rawOptionsStored=false`, `rawResponseStored=false`, and `rawProofLinesStored=false` in every packet.

---

## 10. Patch Rules

Before editing a candidate file:

1. Confirm the file is active or owns the active script/tool behavior.
2. Check whether the file is already modified.
3. If it is modified by someone else, read carefully and patch around existing changes.
4. Add a focused RED or characterization test when behavior is nontrivial.
5. Patch the smallest branch or default.
6. Run the narrowest test that can disprove the patch.

Do not:

- rename existing public fields without compatibility aliases,
- delete schemas used by completion audit,
- remove tests just because the goal is to reduce usage,
- turn optional evidence into fake OK,
- hide a real missing proof as success.

Prefer:

```text
status=SUPPORTING_EVIDENCE_MISSING
decision=desktop_only_ready
externalEvidenceMode=optional
requireProducerBundles=false
nextAction=none_for_desktop_only
```

over:

```text
status=OK
externalEvidenceComplete=true
fake producer proof
```

---

## 11. Verification Ladder

Run only available commands. Do not claim unavailable commands succeeded.

Select verification by the changed owner:

```text
prompt_artifact_only -> focused prompt/harness tests + UTF-8/placeholder/secret checks; no Gradle
script_only          -> focused PowerShell/Python tests; Gradle only if a build contract changed
source_safe_patch    -> focused RED/GREEN, sourceSet/LangChain4j gates, compile, affected tests
ui_only              -> focused UI contract plus Browser only when decision-changing
supabase_explicit    -> read-only project-scoped proof only when ref/auth are proven
```

Do not run a broader gate simply because it is listed below. The active profile and changed files decide the verification surface.

### 11.1 Narrow Script/Tool Gates

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__\janitor_tests.ps1 -Suite CoreGuards
powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__\janitor_inventory.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto_tests.ps1
python scripts\awx_mcp_completion_audit.py --root .
```

### 11.2 Source/Gradle Gates

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null

.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat test --tests "*AgentPipelineHealth*" --tests "*ExternalAgentEvidence*" --tests "*AgentTool*" --no-daemon --project-cache-dir $pcd
```

### 11.3 Secret Scan

Count only. Do not print values.

```powershell
$secretPattern = "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}"
$files = @(
  Get-ChildItem -LiteralPath ".\main\java" -Recurse -File -Filter "*.java" -EA SilentlyContinue
  Get-ChildItem -LiteralPath ".\main\resources" -Recurse -File -Include "*.yml","*.yaml" -EA SilentlyContinue
  Get-ChildItem -LiteralPath ".\scripts" -File -EA SilentlyContinue |
    Where-Object { $_.Extension -in ".ps1",".py" }
)
$hits = if ($files.Count -gt 0) {
  @(Select-String -Path $files.FullName -Pattern $secretPattern -EA SilentlyContinue)
} else {
  @()
}
Write-Host "[AWX][desktop][security] secretHits=$($hits.Count)"
```

### 11.4 Broad Gate Only If Needed

Run after P0/P1 changes pass:

```powershell
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $pcd
```

Do not run boot/browser smoke unless the changed surface requires runtime UI proof.

---

## 12. Failure Classifier

Pick exactly one primary class and optional secondary classes:

```text
index-lock-conflict
branch-ownership-mismatch
worktree-overlap
patch-drop-pending
smb-conflict-risk
gradle-cache-collision
wrong-sourceset
langchain4j-version-purity
secret-leak-risk
prompt-rule-violation
external-evidence-overrequired
producer-bundle-overrequired
desktop-only-mode-missing
patchdrop-queue-misclassified
optional-ui-proof-overrequired
supabase-project-ref-missing
supabase-auth-missing
browser-smoke-missing
computer-use-smoke-missing
cannot-find-symbol
yaml-parse
spring-bean
spring-bind
missing-task
gradle-distribution-network-cache
other
```

Retry only once per blocker class, and only after a specific patch.

---

## 13. Stop Conditions

Stop and report honestly when any is true:

- `.git/index.lock` exists.
- Current branch is Mac mini / Notebook owned.
- Top-level PatchDrop has a pending `.patch` and this task is not a PatchDrop consumption pass.
- Target file has unrelated user changes you cannot safely patch around.
- Source scan cannot prove active sourceSet.
- No active SMB/producer overrequirement exists.
- Supabase live proof is explicitly required and is blocked by missing project ref/auth. In Desktop-only mode, missing Supabase project ref/auth is `supporting_evidence_needed`, not a stop condition.
- The next failure class changes after a patch.
- Verification fails in a way unrelated to your change.
- A patch would require broad rewrite or deleting safety gates.

If no patch is needed:

```text
no_patch_needed: live source already treats SMB/PatchDrop/external evidence as optional or supporting for Desktop-only work
```

---

## 14. Final Report Format

Return exactly this structure:

```md
## 요약
- 2~5줄. 실제 수정 범위, SMB 제거/비활성화 판단, Codex 사용량 최적화 방향, 검증 상태만.

## do01 / Observation
- 실행한 명령.
- 핵심 로그 최대 10줄.
- repo root / branch / active sourceSets.
- Decomposition decision.
- PatchDrop / source lease / index.lock 상태.
- Supabase / Browser / Computer / Superpowers 사용 여부.
- evidence_needed.

## do02 / Patch Blocks
파일별:
- Observation
- Before snippet, 최대 20줄.
- After snippet, 최대 20줄.
- Minimal unified diff.
- Why this file only.
- SMB/Codex usage reduction effect.
- Secret masking method.
- Rollback note.

## do03 / Setup Commands
- repo root에서 실행할 정확한 명령.
- Desktop cache isolation 명령.
- network/cache-dependent 명령 분리.
- Browser/Computer/Supabase proof가 필요한 경우와 아닌 경우 분리.

## do04 / Verification
각 명령별:
- Command.
- Expected success condition.
- Observed result.
- Failure classification.
- Retry decision.
- Remaining evidence_needed.

## do05 / Risks & Next Steps
- 최대 5개.
- SMB 충돌 위험: H/M/L.
- Codex 사용량 감소 기대효과: L/M/H.
- counterexample/limitation 1개.
- decision factors 최대 3개.
- confidence: L/M/H.
- next single most urgent patch.
```

Never say build passed unless command output proves it.
Never say boot passed unless boot output proves it.
Never say Supabase works unless project-scoped read-only proof exists.
Never say Mac mini/Notebook proof exists unless current Desktop-visible sidecars prove it.

---

## 15. One-Shot Operating Instruction

For the next 9-hour pass:

1. Record the active profile. Use `desktop_source_safe_patch` unless the user requires `prompt_artifact_only`.
2. Confirm Desktop root, bounded dirty-worktree count, PatchDrop, source leases, and only the source/tooling owners allowed by that profile.
3. Spend up to the first 2 hours on one bounded reconnaissance pass; reuse the result while `sourceTruthFingerprint` is unchanged.
4. Decide direct / 2-way / 3-way based on the confirmed seam.
5. Patch only the smallest owner allowed by the active profile.
6. Preserve PatchDrop janitor safety for explicit external bundles.
7. Keep Browser, Computer, and Supabase as demand-driven evidence lanes.
8. Rerun the narrowest verification selected by changed owner.
9. Stop when the next failure class changes and report honestly with bounded command output.

Your goal is not to delete useful safety tools. Your goal is to remove always-on SMB/multi-agent overhead from the default Codex workflow while keeping Desktop final proof reliable.

[DONE]
