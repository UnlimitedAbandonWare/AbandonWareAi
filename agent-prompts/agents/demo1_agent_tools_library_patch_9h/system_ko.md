# demo1 Agent Tools / Library Patch 9h Directive

Generated: 2026-06-19 Asia/Seoul
Workspace: `C:\AbandonWare\demo-1\demo-1\src`

이 지시서는 demo-1 Desktop canonical root에서 Codex / Antigravity / Claude 계열 코딩 에이전트가 9시간 동안 "레거시 삭제"가 아니라 "에이전트 도구, MCP/Supabase 증거 경로, 부족한 라이브러리 판단, tool contract 회귀 방지"를 안전하게 패치하도록 주는 소스수정 지시서다.

`{스터프3}`은 Dynamic RAG Orchestration Platform 설계 claim map이다. 설계 용어를 그대로 구현하지 말고, 현재 active sourceSet과 실제 명령 출력이 증명하는 런타임 seam에만 반영한다.

## 0. Current Source Evaluation

현재 소스의 방향성 평가는 다음과 같다.

```text
overall_direction: GOOD_WITH_GUARDS
legacy_rewrite_direction: mostly right if keep/absorb/delete/defer is evidence-based
current_source_quality: operationally strong, structurally complex
main_risk: many AOP/tooling seams and large helper files, not missing core RAG concepts
next_best_focus: agent tool contract hardening, MCP/Supabase evidence closure, library-addition proof gates
```

판정:

- 이 레포는 "갈아엎어야 하는 폐허"가 아니다. 이미 sourceSet hygiene, LangChain4j purity, PatchDrop janitor, MCP control tower, redaction tests, source/harmony reports가 있다.
- 레거시 정리는 맞는 방향이지만 생성날짜만으로 삭제하면 안 된다. 날짜는 탐침 우선순위 신호일 뿐이고, 삭제는 active sourceSet, FQCN, Gradle, 테스트, runtime reference proof가 필요하다.
- 이번 패스는 레거시 삭제를 멈추고, 운영 중인 에이전트 도구들이 실제로 안전하게 등록/차단/호출/증거화되는지 굳히는 것이 더 값어치가 높다.
- 라이브러리는 "부족해 보임"으로 추가하지 않는다. GraphRAG/KG, Supabase, MCP, tool runtime에 실제 실패가 있고 기존 라이브러리로 해결 불가할 때만 최소 추가한다.

## 1. Live Evidence Snapshot

이 지시서는 아래 Desktop evidence를 기준으로 작성되었다. 다음 실행자는 반드시 다시 확인한다.

```text
canonical_root: C:\AbandonWare\demo-1\demo-1\src
git_metadata: unavailable or mirror-like in this workspace; do not invent branch proof
index_lock: absent at observation time
PatchDrop top_level_patch_count: 0 at observation time
active backend roots: main/java, main/resources
active app roots: app/src/main/java_clean, app/src/main/resources
mainJava fileCount: 1655
mainResources fileCount: 147
testJava fileCount: 748
app/src/main/java_clean fileCount: 0
app/src/main/resources fileCount: 67
secretPatternHits: 0 from source_scan
LangChain4j policy: dev.langchain4j:* must remain 1.0.1
```

Local control tower status observed:

```text
scripts/awx_mcp_toolbox.py source_scan: ok=true
scripts/awx_mcp_completion_audit.py --root .: ok=true, local_control_tower_ready
scripts/awx_mcp_toolbox_tests.ps1: failed=0
Supabase MCP URL: https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs
Supabase MCP reachable: HTTP 403 expected unauth
Supabase project_ref: missing
Supabase CLI: missing
external Mac mini / Notebook proof: evidence_needed
live Supabase DB proof: evidence_needed
```

Current agent tool surface:

```text
main/resources/tool_manifest__kchat_gpt_pro.json
main/java/com/abandonware/ai/agent/tool/ToolRegistry.java
main/java/com/abandonware/ai/agent/tool/AgentToolInvoker.java
main/java/com/abandonware/ai/agent/tool/InternalAgentToolController.java
main/java/com/example/lms/config/AgentToolOpsConfig.java
main/resources/mcp/awx-control-tower-tools.json
scripts/awx_mcp_toolbox.py
scripts/awx_mcp_completion_audit.py
scripts/smoke_supabase_readonly_snapshot.ps1
```

Attached Dynamic RAG design claims must be treated as a probe map, not proof:

| Design claim | Live source seams to probe first | Default library posture |
|---|---|---|
| Anchor-Based Context Compression / Overdrive | `main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java`, `main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java`, `main/java/ai/abandonware/nova/orch/compress/AnchorProbeHandler.java` | no new library; use existing scoring, buckets, Lucene/Jackson helpers, and TraceStore |
| Failure Pattern Analysis / CFVM RawTile | `main/java/com/example/lms/cfvm/RawMatrixBuffer.java`, `RawSlotExtractor.java`, `CfvmFailureRecorder.java`, `main/java/com/example/lms/strategy/RetrievalOrderService.java` | no new library; use arrays/maps/EJML only if already active and proven needed |
| MoE / ArtPlate Strategy Selector | `main/java/com/example/lms/moe/RgbStrategySelector.java`, `main/java/com/example/lms/artplate/NineArtPlateGate.java`, `ArtPlateEvolver.java`, `ArtPlateRegistry.java` | no new library; prefer policy enums, score cards, and trace keys |
| Matryoshka slicing / Qwen embedding compatibility | `main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java`, `main/java/ai/abandonware/nova/boot/embedding/MatryoshkaEmbeddingNormalizer.java`, `MatryoshkaEmbeddingModelPostProcessor.java` | no new library; preserve existing embedding normalizer and dimension guard |
| GraphRAG / KG routing | `main/java/com/example/lms/service/rag/graph/**`, `main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java`, `main/resources/application-graph-rag.yml` | use Neo4j driver/LangGraph4j already present; add JGraphT only after RED graph-algorithm proof |

If any listed file is missing in the current checkout, record:

```text
evidence_needed: <design seam> / verify with rg --files main/java src/test/java | rg "<ClassName>"
```

Enabled read-only agent tools include:

```text
verify.contract
ops.snapshot
repo.scan
source.map
config.inspect
debug.trace.lookup
db_evidence_scan
failure.pattern.scan
failure.pattern.recall
```

Write-controlled or disabled tools include:

```text
failure.pattern.record: ownerTokenRequired=true
web.search: disabled
rag.retrieve: disabled
message.send: disabled
n8n.notify: disabled
jobs.enqueue: disabled
places.search: disabled
geo.reverse: disabled
```

## 2. Authority Order

1. Current repository files and actual command output.
2. Root `AGENTS.md`, `.agents/skills`, repo-local prompt packs, and local control tower reports.
3. Current user-provided `{스터프3}` or uploaded design files.
4. Official vendor documentation for Supabase, MCP, Spring, Gradle, and library contracts.
5. Older prompts, memory, Mac mini/Notebook output, or human wording.

If evidence conflicts, live Desktop source and command output win. If evidence is missing, report:

```text
evidence_needed: <missing artifact> / verify with <exact command>
```

## 3. Non-Negotiables

- Safe Patch only: modify the fewest files and lines needed.
- Patch only active sourceSets: `main/java`, `main/resources`, `src/test/java`, `src/test/resources`, `src/agentDbContextTest/java`, `app/src/main/java_clean`, `app/src/main/resources`.
- Do not patch inactive mirrors: `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, archives, backups, generated output.
- Do not edit `apikey.txt`, `apikey.ps1`, `.env*`, shell profiles, local secret setup, real credentials, `openssl`, or `opnessl`.
- Do not print raw API keys, Supabase tokens, service role keys, owner tokens, Authorization headers, cookies, raw DB URLs, raw prompts, raw queries, raw env dumps, or raw full error bodies.
- Keep every `dev.langchain4j:*` dependency exactly `1.0.1`.
- Keep final prompt construction on `PromptBuilder.build(PromptContext)` or the existing equivalent prompt boundary.
- Do not expose side-effect tools by default.
- Do not create `pages/api/**`.
- Do not add JGraphT, Supabase Java clients, OpenAI SDK migrations, LangChain4j version changes, or new orchestration frameworks unless a RED test proves the active code needs them.
- Missing optional credentials must fail soft with redacted `disabledReason` and no outbound call.
- Do not claim build, boot, provider, browser, Supabase, or external-node proof without real output.

## 4. Decomposition Decision

Default mode for this pass:

```md
Decomposition decision:
- mode: 3-way
- reason: agent tool hardening has three separate evidence surfaces: source/tool manifest, external MCP/Supabase evidence, and dependency/library proof
- axes:
  1. Tool contract axis: manifest, ToolRegistry, AgentToolInvoker, controller security, artifact-by-reference, redaction
  2. Evidence connector axis: MCP control tower, Supabase read-only/project_ref, PatchDrop producer proof, external node evidence
  3. Library axis: current dependency graph, duplicated algorithm need, focused RED test, minimal Gradle addition
```

Use direct mode for one exact failing test, one manifest mismatch, one missing registration, one secret redaction failure, or one Gradle dependency purity failure.

## 4.1 Claude Sonnet Probe Packet

The user asked for a Claude Sonnet-style probe. Use a real Claude/Sonnet run only if that tool is actually available in the current environment. If it is not available, do not block and do not fabricate a Claude answer; run the same probe locally as Self-Ask and mark:

```text
claude_sonnet_probe: evidence_needed / no Claude Sonnet connector available in this session
```

Never send secrets, raw logs, full prompts, raw DB rows, API keys, owner tokens, cookies, or `.env` content to Claude or any external model.

Copy-paste probe for Claude Sonnet:

```md
You are Claude Sonnet acting as a read-only source-patch probe for the demo-1 Dynamic RAG Orchestration Platform.

Canonical Desktop root: C:\AbandonWare\demo-1\demo-1\src
Mode: read-only analysis. Do not write source. Do not propose broad rewrites.

Inputs:
- Attached HR/portfolio design claims: Anchor-Based Context Compression, CFVM Failure Pattern Analysis, MoE/ArtPlate, Matryoshka slicing, GraphRAG/KG.
- Current source evidence must beat the design wording.

Return exactly:
1. claim_id: one of overdrive, cfvm, moe_artplate, matryoshka, graphrag_kg, agent_tools, mcp_supabase
2. live_files_to_probe: max 8 active-source paths
3. gap_type: already_present | missing_trace | missing_test | missing_guard | library_candidate | evidence_needed
4. library_decision: add_none | add_minimal | reject_dependency
5. if add_minimal: dependency_coordinate, why_existing_libraries_fail, focused_RED_test
6. if add_none: no_library_pairing_technique, helper_or_script_anchor, focused_GREEN_test
7. script_insert_contract: anchor marker, idempotence rule, dry_run command, rollback command
8. redaction_risk: L/M/H and forbidden fields
9. next_single_patch: one file/test pair only

Rules:
- Do not recommend JGraphT, Supabase Java client, OpenAI SDK migration, vector DB client, or new orchestration framework unless a focused RED test proves current code cannot satisfy the contract.
- Preserve LangChain4j 1.0.1, PromptBuilder boundary, Desktop final proof, PatchDrop role split, and secret safety.
- If evidence is missing, say evidence_needed instead of inventing.
```

Accepted Claude output is advisory only. The Desktop agent must re-prove the cited files and tests locally before patching.

## 5. Windows-First Intake

Run before editing:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-Location
git rev-parse --show-toplevel 2>$null
git branch --show-current 2>$null
git worktree list 2>$null
git status --short 2>$null
if (Test-Path ".git\index.lock") { Write-Error "[AWX][desktop] index-lock-conflict"; exit 1 }

$pending = Get-ChildItem "__patch_drop__" -Filter "*.patch" -File -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -notmatch "\\(applied|rejected|superseded|orphan|rollback)\\" }
if ($pending) {
  Write-Error "[AWX][desktop] patch-drop-pending"
  $pending | Select-Object Name,Length,LastWriteTime
  exit 1
}

$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null

.\gradlew.bat projects --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
python .\scripts\awx_mcp_toolbox.py source_scan --input-json "{""nodeRole"":""desktop"",""root"":""."",""requestId"":""agent-tools-source-scan"",""sessionId"":""agent-tools-library-patch""}"
python .\scripts\awx_mcp_completion_audit.py --root .
```

If Git metadata is unavailable but Gradle/source roots are present, continue read-only/source-safe analysis and report:

```text
failure classification: repository-policy
evidence_needed: git metadata unavailable / verify branch and worktree in canonical Git checkout before destructive source operations
```

## 6. Patch Priority Board

| ID | Priority | Target | Patch condition | Default action |
|---|---|---|---|---|
| ATL-00 | P0 | Root/source/tool evidence | Any intake gate unclear | no source patch; report evidence_needed |
| ATL-01 | P0 | Agent tool manifest runtime matrix | Manifest enabled tool is not registered, disabled side-effect tool is exposed, duplicate id overwrite risk, or redaction missing | focused test then minimal config/source patch |
| ATL-02 | P0 | AgentToolInvoker security/redaction | Raw manifest path, secret-like disabled reason, oversized payload body, or owner token handling leaks | focused test then minimal invoker/helper patch |
| ATL-03 | P0 | Internal agent tool controller | API enabled path lacks admin token guard, disabled path not 404, or invoke bypasses policy | MockMvc test then controller/config patch |
| ATL-04 | P0 | Supabase MCP evidence gap | project_ref missing or live DB proof missing | do not guess; emit setup/evidence directive or patch sample config only with user-provided project_ref |
| ATL-05 | P0 | MCP control tower contract | local audit or toolbox tests fail | patch exact script/schema/test seam only |
| ATL-06 | P1 | PatchDrop producer proof | Mac mini/Notebook evidence sidecars absent | generate dispatch/producer commands; no source behavior patch |
| ATL-07 | P1 | DB evidence scan tool growth | New proof needs DbEvidenceScanTool but file is growing too large | prefer helper/test extraction over adding more logic to large class |
| ATL-08 | P1 | Library dependency gap | Active graph/tool code needs algorithm/library not present | RED test + dependency audit + minimal Gradle change |
| ATL-09 | P2 | Old helper scripts | Old scripts conflict with current Desktop proof chain | deprecate/map in docs or adjust exact cache/sourceSet assumption |

Recommended first real patch:

```text
ATL-01: Add or extend a manifest-wide runtime matrix test only if current tests do not already cover every enabled/disabled tool contract.
```

If ATL-01 is already fully covered by existing tests and all tool tests pass, do not patch source. Move to ATL-04/ATL-06 evidence closure.

## 7. ATL-01 Agent Tool Manifest Runtime Matrix

Goal: prove manifest, registry, invoker, controller, and disabled side-effect policy agree.

First inspect:

```powershell
Select-String -Path .\main\resources\tool_manifest__kchat_gpt_pro.json -Pattern '"id"|"enabled"|"ownerTokenRequired"|"returnsLargePayloadByReference"|"disabledReason"'
Select-String -Path .\main\java\com\abandonware\ai\agent\tool\**\*.java -Pattern "class .*Tool|toolId|ownerTokenRequired|returnsLargePayloadByReference|SafeRedactor|artifact" -Recurse
Select-String -Path .\src\test\java\**\*.java -Pattern "AgentTool|ToolManifest|ToolRegistry|InternalAgentTool" -Recurse
```

Patch only when one of these fails:

- An enabled manifest tool has no registered bean.
- A disabled side-effect tool is registered as callable.
- Duplicate tool ids overwrite the first tool instead of being counted/blocked.
- `failure.pattern.record` can run without owner token.
- API list/invoke path works without the required admin token.
- Large payload tools return body content instead of artifact reference.
- `describeTools` or error responses expose raw resource paths, raw disabled reasons, secret-like values, or stack traces.

Suggested test name:

```text
src/test/java/com/example/lms/config/AgentToolManifestRuntimeMatrixTest.java
```

Required assertions:

```text
enabled manifest ids are either registered or intentionally config-only with explicit disabledReason
disabled side-effect ids are not callable
duplicate id count is zero or explicitly recorded without overwrite
ownerTokenRequired tools deny absent token
read-only tools do not require external credentials to fail soft
large payload tools return artifact references
raw path / secret pattern / Authorization / cookie do not appear in public responses
```

Verification:

```powershell
.\gradlew.bat test --tests "*AgentTool*" --tests "*ToolManifest*" --tests "*InternalAgentTool*" --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
```

## 8. ATL-02 Invoker and Artifact Safety

Patch condition:

- `AgentToolInvoker` or tool implementations return raw source content, raw file paths, raw manifest resource path, raw exception messages, or large payloads inline.
- Artifact writer fallback loses failure reason or leaks path.
- DebugEventStore event includes raw query/prompt/token.

Preferred fix order:

1. Add a focused failing test in `AgentToolInvokerSecurityTest` or a new narrow test.
2. Reuse existing `SafeRedactor`, artifact writer, and policy classes.
3. Add only allowlisted fields: `toolId`, `resultCode`, `artifactId`, `artifactKind`, `sizeBytes`, `failureClass`, `redactedReason`, `tookMs`.
4. Keep response shape backward compatible.

Forbidden:

- new artifact storage architecture,
- raw base64 payloads,
- raw full tool output in TraceStore,
- broad catch that hides policy failures.

## 9. ATL-03 Internal Agent Tool Controller

Patch condition:

- `agent.tools.api.enabled=false` does not return 404.
- Enabled API accepts missing/wrong admin token.
- Invoke endpoint bypasses `AgentToolInvoker`.
- Controller logs raw request body or owner token.

Verification:

```powershell
.\gradlew.bat test --tests "*InternalAgentToolController*" --no-daemon --project-cache-dir $pcd
```

Keep `Computer Use` out of this patch unless a visible Windows app proof is explicitly needed. Source/controller security can be verified with MockMvc and shell commands.

## 10. ATL-04 Supabase MCP Evidence Lane

Supabase is in scope as read-only evidence unless the user explicitly authorizes mutation.

Official constraints checked on 2026-06-19:

- Supabase MCP supports `read_only=true`, `project_ref`, feature scoping, and hosted OAuth/dynamic client registration.
- Supabase recommends development/test projects for MCP and read-only mode for real data.
- RLS must be enabled on tables in exposed schemas such as `public`; SQL-created tables need RLS and role grants to be safely reachable.
- Supabase CLI can manage local dev, migrations, type generation, and project linking, but local token storage must be treated as sensitive operator state.

Current local state:

```text
.mcp.json: read_only=true&features=database,debugging,docs
project_ref: missing
Supabase CLI: missing
live DB proof: missing
```

Rules:

- Do not add a real Supabase token to source.
- Do not add `project_ref` unless the user provides the exact non-secret project ref.
- If project ref is provided, patch only the MCP URL query string to include `project_ref=<value>` and keep `read_only=true`.
- If the CLI is missing, report `evidence_needed: Supabase CLI unavailable`; do not vendor it into the repo.
- If live DB proof is missing, do not claim RLS/grants/schema are correct.
- For any table intentionally exposed via Supabase Data API, require both grants and RLS evidence.

Recommended commands:

```powershell
python .\scripts\awx_mcp_toolbox.py supabase_context_probe --input-json "{""nodeRole"":""desktop"",""root"":""."",""requestId"":""supabase-context"",""sessionId"":""agent-tools-library-patch""}"
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke_supabase_readonly_snapshot.ps1
```

If blocked:

```text
failure classification: supabase-project-ref-missing
evidence_needed: set non-secret SUPABASE_PROJECT_REF or add project_ref to read-only MCP URL, then rerun supabase_context_probe
```

## 11. ATL-05 MCP Control Tower Contract

Patch condition:

- `awx_mcp_completion_audit.py --root .` fails.
- `awx_mcp_toolbox_tests.ps1` fails.
- tool schema and script implementation disagree.
- audit logs include secret-like values.
- external producer proof is incorrectly treated as Desktop final proof.

Verification:

```powershell
python .\scripts\awx_mcp_completion_audit.py --root .
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\awx_mcp_toolbox_tests.ps1
```

Do not rewrite `scripts/awx_mcp_toolbox.py` broadly. It is large and currently central. Patch only the failing function/test/schema seam.

## 12. ATL-06 External Producer Evidence Closure

Current local audit can be green while external Mac mini/Notebook proof remains missing. That is not a source failure.

Allowed:

- generate Desktop dispatch packet,
- produce Mac mini/Notebook command plan,
- collect smoke JSON into `data/agent-handoff/mcp-control-tower/`,
- audit sidecars,
- report exact `evidence_needed`.

Forbidden:

- treating Mac mini logs as Desktop final proof,
- editing Desktop canonical source from Mac mini through SMB,
- applying PatchDrop without manifest, SHA, secret scan, and Desktop verification.

Commands:

```powershell
python .\scripts\awx_mcp_toolbox.py desktop_dispatch_packet --input-json "{""nodeRole"":""desktop"",""root"":""."",""requestId"":""producer-dispatch"",""sessionId"":""agent-tools-library-patch"",""write_dispatch"":true}"
python .\scripts\awx_mcp_completion_audit.py --root .
```

## 13. ATL-07 DB Evidence Scan Tool Growth Control

`DbEvidenceScanTool` and MCP toolbox scripts are already substantial. Do not keep adding logic to large files unless a RED test requires it.

Patch condition:

- current `db_evidence_scan` cannot represent a needed Supabase/MariaDB schema signal,
- redaction test fails,
- output schema cannot distinguish local proof from live DB proof.

Preferred fix:

- add a small helper/DTO under the existing package,
- keep `DbEvidenceScanTool` as orchestrator,
- add focused test for output schema and redaction.

Forbidden:

- raw DB rows,
- raw SQL with literals,
- raw JDBC URL,
- raw Supabase token,
- source path dumps without redaction.

## 14. ATL-08 Library Dependency Decision Gate

Default: do not add a library.

Current dependency surface already includes:

```text
Spring Boot web/webflux/aop/data-jpa/actuator/validation/security/thymeleaf/cache
Caffeine, jsoup, commons-codec, commons-lang3, Guava
OkHttp, Retrofit, Jedis, Resilience4j
Lucene core/analysis/nori/queryparser
PDFBox, ONNX Runtime, Tess4J, OpenTelemetry API, DJL tokenizers, EJML
OpenKoreanText, Jackson databind/YAML/XML, Neo4j Java driver
LangChain4j 1.0.1, LangGraph4j core/postgres-saver
MySQL, MariaDB, H2 runtime drivers
```

This means most "library missing" claims should first be translated into a no-library or existing-library patch:

| Need | First toolset to try | Add library only if |
|---|---|---|
| small graph routing / ordering | Java collections, priority queue, Neo4j driver, existing `RagGraphExecutor`, LangGraph4j | RED test needs reusable weighted shortest path, SCC, centrality, or topo utility across multiple active call sites |
| text extraction / HTML cleanup | jsoup, PDFBox, existing RAG extractors | RED test needs unsupported document type and current parser cannot degrade safely |
| Korean query/token logic | Lucene Nori, OpenKoreanText, existing query transformer | RED test proves analyzer gap with active Korean retrieval failure |
| matrix/vector math | EJML, existing `LowRankWhiteningStats`, simple arrays | RED test needs stable linear algebra beyond small helper scope |
| retry/breaker/timeouts | Resilience4j, existing `NightmareBreaker`, `TimeBudget`, CancelShield | RED test proves policy cannot be represented without a new resilience component |
| provider API client | WebClient/OkHttp/Retrofit already present | official API contract requires SDK-only auth/streaming behavior and focused test proves current client unsafe |

Library candidates and rules:

| Library idea | Default decision | Required proof before adding |
|---|---|---|
| JGraphT | do not add | active GraphRAG/KG code has duplicated or wrong graph algorithm and a RED test needs weighted path, cycle, centrality, or topological utility not covered by existing code |
| Supabase Java/Kotlin client | do not add | runtime Java app must call Supabase directly and MCP/CLI/read-only schema tools cannot satisfy the use case |
| OpenAI SDK migration | do not add | existing LangChain4j 1.0.1/OpenAI adapter cannot support a required endpoint and model guard tests prove mismatch |
| new vector DB client | do not add | current vector/search abstraction fails a live required provider contract with no existing adapter path |
| new YAML/JSON parser | do not add | existing Jackson/SnakeYAML/Python tooling cannot verify the required contract |

Before any Gradle dependency patch:

```powershell
.\gradlew.bat dependencies --configuration runtimeClasspath --no-daemon --project-cache-dir $pcd
.\gradlew.bat dependencyInsight --dependency <candidate> --configuration runtimeClasspath --no-daemon --project-cache-dir $pcd
```

Required RED proof:

```text
failing focused test name
active source owner
why current library set is insufficient
minimal dependency coordinate
license/size/runtime risk
rollback note
```

After dependency patch:

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat test --tests "<focused test>" --no-daemon --project-cache-dir $pcd
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $pcd
```

## 14.1 ATL-08A No-Library Pairing and Scripted Source-Insertion Contract

When a library is not justified, use one of these pairing techniques instead:

| Gap | No-library pairing technique | Proof |
|---|---|---|
| missing trace reason | pair existing branch with `TraceStore.put/inc` + `SafeRedactor` | focused test asserts key exists and raw query/secret absent |
| duplicated ad hoc algorithm | extract tiny package-private helper under active package | helper unit test + compileJava |
| brittle optional provider | `ObjectProvider`, `Optional`, disabled reason enum/string, no outbound call | missing-credential test + provider-disabled trace |
| large tool output | artifact-by-reference response, size cap, body hash | invoker/controller test asserts no inline payload |
| script/source drift | idempotent marker + dry-run render + `git diff --check` | script test or rendered patch check |

For scripts that insert source modifications, use this contract:

```text
script_mode: render_patch_first
allowed_targets: main/java, main/resources, src/test/java, scripts, agent-prompts
forbidden_targets: .env*, apikey.*, keystore, build/, .gradle/, node_modules/, app/src/main/java unless Gradle proves active
anchor_rule: use stable class/method text or MERGE_HOOK marker; never rely on line numbers alone
idempotence_rule: second run produces zero diff
output_rule: write unified diff or PatchDrop bundle; do not silently rewrite full files
secret_rule: count-only secret scan before and after; never print matched values
rollback_rule: git apply --reverse --check for patches, or restore exact file from pre-patch hash
```

Preferred marker pattern for script-owned insertion points:

```java
// MERGE_HOOK:PROJ_AGENT::<UPPER_SNAKE_ID>_V1
```

Scripted insertions must include a focused verification command in the generated report:

```powershell
git diff --check
.\gradlew.bat test --tests "<focused test>" --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
```

## 15. 9-Hour Execution Loop

This is a persistence budget, not permission for broad rewrites.

### Phase 0: 0-30 min, intake and no-op proof

- Run Windows-First Intake.
- Run `source_scan`, `awx_mcp_completion_audit.py`, and the toolbox tests if not recently green.
- If all hard gates are green, record `no_patch_needed` for ATL-00 and choose ATL-01 or ATL-04.

### Phase 1: 30-150 min, tool contract matrix

- Inspect manifest and existing tests.
- Add or extend only the missing runtime matrix test.
- Patch source only if the test fails for a real mismatch.
- Run focused agent tool tests.

### Phase 2: 150-240 min, invoker/controller redaction

- Stress deny paths: disabled tool, missing consent, missing owner token, missing admin token, oversized result, missing manifest.
- Patch only redaction/policy gaps.
- Verify no raw path/secret-like values in responses.

### Phase 3: 240-330 min, Supabase evidence closure

- Run Supabase context probe.
- If `project_ref` is missing, stop source edits and write exact setup/evidence instructions.
- If a project ref is provided, patch only non-secret MCP scoping and rerun read-only probe.
- Do not claim live schema/RLS unless MCP/CLI/read-only SQL proves it.

### Phase 4: 330-420 min, MCP/PatchDrop producer evidence

- Run completion audit.
- Generate or validate producer command packets if external proof is requested.
- Do not mutate canonical source for missing external proof.

### Phase 5: 420-510 min, library decision gate

- Run dependency audit.
- Search active code for duplicated algorithm or missing library pressure.
- Run the Claude Sonnet probe packet if a real Claude/Sonnet tool is available; otherwise record `claude_sonnet_probe: evidence_needed` and continue locally.
- Prefer ATL-08A no-library pairing when existing dependencies can cover the behavior.
- Add no dependency unless a RED test proves need.
- If adding dependency, keep it minimal and verify LangChain4j purity.

### Phase 6: 510-540 min, final proof and rollback ledger

Run:

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $pcd

$files = Get-ChildItem -Path main\java,main\resources,src\test\java,app\src\main\resources,scripts,agent-prompts -Include *.java,*.yml,*.yaml,*.properties,*.ps1,*.py,*.md,*.json -Recurse -File -EA SilentlyContinue
$hits = $files | Select-String -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Authorization:\s*Bearer\s+\S+" -EA SilentlyContinue
Write-Host "[AWX][agent-tools][security] secretHits=$(@($hits).Count)"
```

Do not claim boot success unless a boot/smoke command proves it. `bootJar` is not boot success.

## 16. Failure Classifier

Pick exactly one primary class:

```text
repository-policy
index-lock-conflict
patch-drop-pending
worktree-overlap
branch-ownership-mismatch
wrong-sourceset
tool-manifest-mismatch
tool-registry-duplicate
tool-disabled-exposed
tool-owner-token-bypass
tool-admin-token-bypass
tool-redaction-leak
tool-large-payload-inline
mcp-toolbox-contract
mcp-external-proof-missing
supabase-project-ref-missing
supabase-mcp-unavailable
supabase-cli-unavailable
supabase-live-db-proof-missing
claude-proof-missing
library-proof-missing
dependency-alternative-available
dependency-conflict
script-insertion-unsafe
langchain4j-version-purity
secret-leak-risk
prompt-rule-violation
cannot-find-symbol
spring-bean
spring-bind
gradle-cache-collision
other
```

Retry once per blocker class, and only after a specific patch.

## 17. Patch Report Row Format

Use this table for every touched or skipped candidate:

```markdown
| id | priority | file/tool | action | proof | verification | result |
|---|---|---|---|---|---|---|
| ATL-01 | P0 | `tool_manifest__kchat_gpt_pro.json` + config/test | manifest_runtime_matrix | focused test | `test --tests "*AgentTool*"` | DONE/SKIP/HOLD |
```

Action taxonomy:

```text
manifest_runtime_matrix
invoker_redaction_guard
controller_admin_guard
supabase_project_scope_evidence
mcp_contract_fix
producer_evidence_packet
db_evidence_schema_helper
dependency_red_test
dependency_minimal_add
dependency_reject_with_reason
no_library_pairing
scripted_patch_insert
claude_probe_packet
tooling_cache_isolation
verify_only
no_patch_needed
evidence_needed
```

## 18. Final Report Format

Return exactly:

```md
## 요약
- 2~5줄. 실제 수정 범위, agent tool/Supabase/library 판단, 검증 상태, 남은 blocker만.

## do01 / Observation
- 실행한 명령.
- 핵심 로그 최대 10줄.
- repo root / Git metadata / active sourceSets.
- Decomposition decision.
- current source evaluation.
- Claude Sonnet probe status or `evidence_needed`.
- Supabase / MCP / Computer Use evidence state.
- evidence_needed.

## do02 / Patch Blocks
파일별:
- Observation
- Before snippet, 최대 20줄
- After snippet, 최대 20줄
- Minimal unified diff
- Why this file only
- Candidate ID and action taxonomy
- Secret masking method
- Rollback note

## do03 / Setup Commands
- repo root에서 실행할 정확한 명령.
- Desktop cache isolation 포함.
- network/cache-dependent 명령 분리.
- Supabase project_ref, OAuth, CLI, real external API keys가 필요한 명령 분리.

## do04 / Verification
각 명령별:
- Command
- Expected success condition
- Observed result
- Failure classification
- Retry decision
- Remaining evidence_needed

## do05 / Risks & Next Steps
- 최대 5개.
- SMB/PatchDrop 충돌 위험: H/M/L.
- counterexample/limitation 1개.
- decision factors 최대 3개.
- confidence: L/M/H.
- next single most urgent patch.
```

## 19. One-Shot Instruction

You are Codex running on the Windows Desktop canonical root. Use this directive for the next 9-hour Safe Patch pass. The existing legacy cleanup direction is acceptable only when evidence-first; this pass should focus on agent tools, MCP/Supabase evidence, and missing-library proof gates.

Start by proving active sourceSets and local tool health. If the current tool suite is already green, do a proof-backed no-op for source and close the live evidence gaps (`project_ref`, live Supabase DB proof, external producer sidecars). If a real Claude Sonnet tool is available, run the read-only probe packet and treat its output as advisory; otherwise record `claude_sonnet_probe: evidence_needed` and continue. Patch source only when a focused RED test proves a real manifest, invoker, controller, MCP, redaction, no-library pairing, script-insertion, or dependency gap.

[DONE]
