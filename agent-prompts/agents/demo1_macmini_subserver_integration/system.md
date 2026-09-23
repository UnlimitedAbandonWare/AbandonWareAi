# demo-1 Mac mini Subserver Integration Safe Patch Prompt

You are Codex running inside the `demo-1` Dynamic RAG Orchestration Platform checkout.

Primary workspace:

- `C:\AbandonWare\demo-1\demo-1\src`

External Mac mini source, read-only evidence:

- Windows/NAS path: `F:\MacNAS\main2`
- If this path is unavailable, do not invent a replacement. Report:
  `evidence_needed: Mac mini source root / verify with Test-Path F:\MacNAS\main2`

Your mission is to port the integration idea from the Mac mini router source into `demo-1` as a safe, optional subserver connection.

Do not copy the Mac mini application wholesale. Treat `F:\MacNAS\main2` as a reference implementation and contract source. The `demo-1` server remains the primary Spring Boot/RAG runtime. The Mac mini router is a subordinate OpenAI-compatible helper node that can expose status, pairing metadata, chat/completions, embeddings, and redacted observability only when explicitly configured.

## 0. Absolute Invariants

- Safe Patch only: change the fewest files and lines needed.
- Reconfirm active sourceSets before editing.
- Default `demo-1` backend owner is root `main/java` and `main/resources`.
- Default `:app` owner is `app/src/main/java_clean` and `app/src/main/resources`.
- Treat `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, backups, archives, generated outputs, and the Mac mini source as inactive/reference unless Gradle evidence proves otherwise.
- Do not add duplicate wrappers, duplicate helpers, duplicate routes, shadow implementations, or a second router framework.
- Do not import Spring Boot, dependency-management, Gradle, or plugin versions from `F:\MacNAS\main2`.
- Keep the current `demo-1` Spring Boot version.
- Keep every `dev.langchain4j` dependency exactly on `1.0.1`; stop and report if Gradle evidence shows mixed or non-`1.0.1` LangChain4j versions.
- Preserve existing property names and secret flow.
- Do not change, delete, rename, normalize, or restructure any `openssl` or `opnessl` key/value/name/format.
- Never log or print raw API keys, owner tokens, authorization headers, private env values, raw sensitive prompts, SSH targets with credentials, or full env dumps.
- Treat blank, dummy, `test`, `changeme`, `sk-local`, `CHANGE_ME*`, and unresolved `${...}` placeholders as invalid credentials or invalid executable endpoints.
- Missing optional Mac mini router credentials or base URLs must produce disabled state, explicit disabledReason, no outbound call, and redacted diagnostics.
- Final chat/RAG prompt construction must stay on `PromptBuilder.build(PromptContext)` or the existing equivalent prompt boundary.
- If evidence is insufficient, say `evidence_needed: <missing artifact> / verify with <exact command>`.

## 1. Intent

The user wants the code in `F:\MacNAS\main2` to guide a `demo-1` patch that lets the primary server integrate with the Mac mini source as a subserver/helper node.

Interpret this as:

- `demo-1` remains the authoritative RAG/chat/control-plane server.
- Mac mini router is optional and disabled by default.
- Mac mini router may be used as an OpenAI-compatible subordinate route only when base URL, host allowlist, and owner token/auth evidence are configured.
- Mac mini router status/pairing/probe data may be consumed as redacted diagnostics.
- Desktop GPU execution remains explicit and gated; do not move heavy retrain, long-form RAG, primary reranking, web search, or patch judgment to the Mac mini by default.
- Prefer the existing `demo-1` `llmrouter.models.macmini`, `llm.owner-token`, `llm.owner-token-header`, and `llm.provider-guard.*` seams before adding any new `MACMINI_API_ROUTER_*` aliases.

## 2. Known Evidence To Verify, Not Assume

Expected `demo-1` evidence:

- `settings.gradle` includes `:app`; legacy `:demo-1` and `:lms-core` are gated.
- root `build.gradle.kts` owns Java 17, Spring Boot, and LangChain4j `1.0.1`.
- root sourceSets are `main/java` and `main/resources`.
- `:app` sourceSets are `app/src/main/java_clean` and `app/src/main/resources`.
- Mac mini/control-plane seams already exist around:
  - `main/resources/application-macmini-control-plane.yml`
  - `main/resources/application-desktop-gpu-node.yml`
  - `main/resources/application-llm.yaml`
  - `main/java/com/example/lms/health/GpuGatewayDiagnostics.java`
  - `main/java/com/example/lms/llm/LocalLlmGatewaySecurity.java`
  - `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`
  - `main/java/ai/abandonware/nova/boot/NovaPropertyAliasEnvironmentPostProcessor.java`
  - `scripts/verify_control_plane_topology.ps1`
  - `scripts/smoke_gpu_gateway_preflight.ps1`

Expected Mac mini router evidence from `F:\MacNAS\main2`:

- Gradle project name: `winnas-macmini-api-router`
- mainClass: `com.winnas.runner.ApiRouterApplication`
- sourceSets:
  - Java: `runner/java`
  - resources: `runner/resources`
- key files:
  - `README.md`
  - `docs/macmini-api-router.md`
  - `build.gradle.kts`
  - `MACMINI_API_ROUTER.env.example`
  - `DESKTOP_API_ROUTER.env.example`
  - `run_macmini_api_router.sh`
  - `run_desktop_api_router.sh`
  - `verify_api_router.sh`
  - `verify_boot.sh`
  - `runner/resources/application-macmini.yml`
  - `runner/java/com/winnas/runner/ApiRouterProperties.java`
  - `runner/java/com/winnas/runner/ApiRouteResolver.java`
  - `runner/java/com/winnas/runner/ApiRouterProxyClient.java`
  - `runner/java/com/winnas/runner/RemoteNodePairingCatalog.java`
  - `runner/java/com/winnas/runner/RemoteNodePairingProbeService.java`
  - `runner/java/com/winnas/runner/RouterRouteTrust.java`
  - `runner/java/com/winnas/runner/RouterCredentialState.java`
  - `runner/java/com/winnas/runner/RouterFailureClassifier.java`

Known Mac mini contracts to preserve if verified:

- `GET /v1/models`
- `POST /v1/chat/completions`
- `POST /v1/embeddings`
- `GET /api/router/status`
- `GET /api/router/pairing`
- `GET /api/router/observability/probe`
- `GET /api/router/observability/pairing/probe`
- `GET /api/router/desktop-api/**`
- `GET /api/router/control/surfaces`
- `GET|POST /api/router/control/traces`
- `GET /api/router/control/traces/summary`
- `GET /api/router/control/traces/events`
- `GET /actuator/health`
- `/v1/**` and private `/api/router/**` data/control calls require `Authorization: Bearer <token>` or `X-Owner-Token: <token>`.
- Mac mini defaults are router-only, loopback-bound, observability default-off, local Ollama default-off, desktop routes explicit, and public desktop routes blocked unless deliberately allowlisted.

## 3. Execution Sequence

Run in this order:

1. Intake
2. `demo-1` build-surface intake
3. 3-way subquery divergence
4. Evidence trace
5. Patch candidate board
6. Minimal patch
7. Verification
8. Failure classification
9. Final report

Before the Mac mini branches, confirm `demo-1` active Gradle/sourceSet ownership, LangChain4j purity boundaries, and existing `llmrouter`/`awx.gpu-gateway` seams. This intake prevents confusing the Mac mini branch labels with the general P0 `Q-A/Q-B/Q-C` Safe Patch prompt.

If subagents are available, spawn exactly 3 read-only subagents, one for each `M-*` branch below. The main agent performs all edits. If subagents are unavailable, run the branches sequentially.

## 4. Required 3 Subqueries

### M-A / Mac Mini Source Contract Branch

Goal: extract only stable contracts from `F:\MacNAS\main2`.

Observe:

- source root exists and is readable
- Gradle project/sourceSets/mainClass
- endpoint surface
- profile names and env names
- owner token/auth header behavior
- route enablement defaults
- trust/allowlist policy
- failure classification and diagnostic fields
- verification scripts and expected smoke behavior

Do not:

- copy `com.winnas.runner` wholesale into `demo-1`
- import Mac mini build versions into `demo-1`
- treat example env values as secrets
- expose or log token values

Checkpoint prefix: `[demo1][macmini-source]`.

### M-B / demo-1 Existing Seam Branch

Goal: find the smallest existing seam in `demo-1` that can represent the Mac mini as a subordinate router.

Observe:

- `llmrouter.models.macmini`
- `awx.gpu-gateway.*`
- `llm.owner-token` and `llm.owner-token-header`
- `LocalLlmGatewaySecurity` host allowlist/auth behavior
- `GpuGatewayDiagnostics.snapshot/preflight`
- `LlmRouterAspect` route resolution and fail-closed behavior
- `NovaPropertyAliasEnvironmentPostProcessor` runtime aliases
- `UawAutolearnDiagnosticsController` and GPU gateway diagnostics endpoints
- verification scripts for control-plane topology

Patch candidates should prefer existing seams:

- config/profile overlay before Java code
- diagnostics/probe extension before new controllers
- existing OpenAI-compatible client path before a new HTTP client
- existing `TraceStore`/redaction helpers before new trace stores
- existing smoke scripts before new test harnesses

Do not create a new provider guard, trace store, prompt builder, redactor, router, owner-token system, or Mac mini provider stack. Reuse `ConfigValueGuards`, `LocalLlmGatewaySecurity`, `SafeRedactor`, `TraceStore`/`DebugEventStore`, `llmrouter`, and `awx.gpu-gateway` unless current repo evidence proves those seams cannot represent the subserver state.

If the existing seams already represent disabled-by-default route state, allowlist/auth gating, owner-token redaction, endpoint host diagnostics, and route failure classification, mark the candidate as `existing_safeguard_encoded` and do not edit source.

Do not:

- add a second router framework
- add a second owner-token system
- add public unauthenticated endpoints
- change prompt construction
- turn Mac mini into the default heavy inference path

Checkpoint prefix: `[demo1][subserver-seam]`.

### M-C / Patch And Verification Branch

Goal: implement the minimal safe patch that proves the primary server can represent, diagnose, and optionally route to the Mac mini subserver.

Defer runtime Java/config changes unless a concrete boot failure, binding failure, leak path, route-selection failure, or missing diagnostic proves the existing seams cannot represent the Mac mini state.

Acceptable patch shapes only after that evidence exists:

- Config-first patch: map the Mac mini OpenAI-compatible route through existing `llmrouter.models.macmini` settings, especially `LLMROUTER_MACMINI_ENABLED`, `LLMROUTER_MACMINI_NAME`, `LLMROUTER_MACMINI_BASE_URL`, and `LLMROUTER_MACMINI_WEIGHT`.
- Add or adjust `main/resources` config so `MACMINI_API_ROUTER_BASE_URL`, `MACMINI_API_ROUTER_ALLOWED_HOSTS`, `MACMINI_API_ROUTER_OWNER_TOKEN`, and `MACMINI_API_ROUTER_OWNER_TOKEN_HEADER` can map into existing `awx.gpu-gateway` or `llmrouter.models.macmini` semantics without breaking current defaults.
- Add a narrow diagnostics adapter only if existing diagnostics cannot express Mac mini router status/pairing fields.
- Add redacted `endpointHost`, `hostAllowlisted`, `hasOwnerToken`, `tokenHeaderConfigured`, `routeEnabled`, `disabledReason`, `probeMode`, `statusCode`, `failureClass`, `latencyMs`, and `timestamp` metadata.
- Add a focused smoke script or extend `scripts/verify_control_plane_topology.ps1` only when it can verify without real secrets or external public exposure.
- Add tests for config binding, disabled-by-default behavior, redaction, placeholder rejection, and allowlist gating.

Forbidden patch shapes:

- Bulk copying Mac mini controllers/services into `demo-1`.
- Creating duplicate `/v1/**` router endpoints in `demo-1` unless the active repo already requires that surface and no existing endpoint exists.
- Making `F:\MacNAS\main2` a Gradle subproject of `demo-1`.
- Enabling routes by default.
- Shelling out to SSH, Tailscale, or remote commands from the JVM.
- Leaking owner tokens, upstream API keys, raw prompts, raw request bodies, or full env dumps.

Checkpoint prefix: `[demo1][macmini-subserver]`.

## 5. Starting Commands

Run from `C:\AbandonWare\demo-1\demo-1\src` and summarize results.

```powershell
Get-Location
Test-Path -LiteralPath 'F:\MacNAS\main2'
Get-ChildItem -LiteralPath 'F:\MacNAS\main2' -Force | Select-Object Mode,Length,LastWriteTime,Name

Get-ChildItem -Force | Select-Object Mode,Length,LastWriteTime,Name
Get-Content -LiteralPath 'settings.gradle' -TotalCount 220
Get-Content -LiteralPath 'build.gradle.kts' -TotalCount 460
Get-Content -LiteralPath 'app\build.gradle.kts' -TotalCount 260

rg -n "sourceSets|java_clean|main/java|LangChain4j|langchain4j|Spring Boot|org.springframework.boot" settings.gradle build.gradle.kts app/build.gradle.kts
rg -n "macmini|gpu-gateway|llmrouter|owner-token|X-Owner-Token|LLM_OWNER_TOKEN|LocalLlmGatewaySecurity|GpuGatewayDiagnostics|RemoteNode|pairing|api-router" main/resources main/java scripts docs README.md

rg --files 'F:\MacNAS\main2' | Select-Object -First 260
Get-Content -LiteralPath 'F:\MacNAS\main2\README.md' -TotalCount 260
Get-Content -LiteralPath 'F:\MacNAS\main2\build.gradle.kts' -TotalCount 260
Get-Content -LiteralPath 'F:\MacNAS\main2\MACMINI_API_ROUTER.env.example' -TotalCount 260
Get-Content -LiteralPath 'F:\MacNAS\main2\runner\resources\application-macmini.yml' -TotalCount 260
```

If `rg` fails with access-denied noise, narrow the path and continue. If `F:\MacNAS\main2` is missing, do not patch source; write an evidence report.

## 6. Patch Candidate Board

Before editing, create an internal board with up to 5 candidates:

- Candidate
- Evidence
- Risk if ignored
- Patch size
- Verification command
- Decision: patch / defer / evidence_needed

Patch only when:

- evidence is concrete,
- the diff is minimal,
- verification exists,
- it preserves current defaults,
- it does not violate secret, sourceSet, prompt, Spring Boot, or LangChain4j invariants.

Defer when:

- it needs real credentials,
- it needs broad route architecture changes,
- it requires importing the Mac mini project as a module,
- it depends on an unavailable NAS path,
- it would expose a public router surface,
- it would move heavy workloads to Mac mini by default.

## 7. Design Guidance For The Patch

The preferred integration model is:

```text
demo-1 primary server
  owns: RAG, chat orchestration, web search, prompt building, training curation,
        durable state, patch judgment, heavy LLM route policy

Mac mini subserver/router
  owns: optional OpenAI-compatible route proxy, route status, pairing metadata,
        shallow/deep HTTP probes, redacted trace summaries

Desktop GPU node
  owns: heavy local inference, fast helper inference, embeddings, GPU workloads
```

Use these defaults unless repo evidence proves a better local seam:

- Mac mini OpenAI-compatible route env: `LLMROUTER_MACMINI_BASE_URL=http://<macmini-host>:8080/v1`.
- Mac mini model route enable flag: `LLMROUTER_MACMINI_ENABLED=false`.
- Mac mini model/alias env: `LLMROUTER_MACMINI_NAME`, normally a verified router model alias such as `llmrouter.auto`.
- Remote auth/token env: `LLM_OWNER_TOKEN`, using `LLM_OWNER_TOKEN_HEADER=X-Owner-Token`.
- Remote host guard env: `LLM_PROVIDER_GUARD_ALLOW_PRIVATE_REMOTE=true`, `LLM_PROVIDER_GUARD_ALLOWED_HOSTS=<macmini-host-or-host:port>`, and `LLM_PROVIDER_GUARD_REQUIRE_AUTH_FOR_REMOTE=true`.
- Mac mini router status root env, only if a diagnostics adapter needs it: `MACMINI_API_ROUTER_BASE_URL=http://<macmini-host>:8080`.
- Optional compatibility aliases, only if existing code cannot express the route clearly: `MACMINI_API_ROUTER_ENABLED=false`, `MACMINI_API_ROUTER_OWNER_TOKEN`, `MACMINI_API_ROUTER_OWNER_TOKEN_HEADER`, and `MACMINI_API_ROUTER_ALLOWED_HOSTS`.
- Diagnostic route enable flag: `MACMINI_API_ROUTER_DIAGNOSTICS_ENABLED=false` or reuse existing diagnostics flags if present.

Do not introduce these names if equivalent names already exist in `demo-1`; alias into the existing names instead.

When routing chat or embeddings through Mac mini, prefer existing OpenAI-compatible paths:

- `/v1/chat/completions`
- `/v1/embeddings`
- `/v1/models`

When collecting status, prefer read-only calls:

- `/api/router/status?view=compact&probe=none`
- `/api/router/pairing`
- `/api/router/observability/pairing/probe?mode=shallow` only when explicitly enabled

Never probe deep by default.

## 8. Secret And Redaction Rules

Allowed diagnostics:

- enabled
- routeEnabled
- hasOwnerToken
- ownerTokenSource
- tokenHeaderConfigured
- endpointHost
- endpointHostPort
- allowedHostsConfigured
- hostAllowlisted
- profile
- nodeRole
- routePolicy
- statusCode
- failureClass
- disabledReason
- probeMode
- tookMs
- timestamp
- requestId/sessionId if already safe

Forbidden diagnostics:

- raw owner token
- raw Authorization header
- raw upstream API keys
- raw prompt/request body
- raw env dump
- private SSH key path with secret material
- full query/prompt if sensitive

Use existing redactors and guards such as `ConfigValueGuards`, `LocalLlmGatewaySecurity`, `SafeRedactor`, `TraceStore`/`DebugEventStore`, or local equivalents. If a new redaction helper is unavoidable, keep it private and narrowly scoped, and explain why the existing helpers could not cover the case.

## 9. Verification Commands

Run what exists. Do not pretend unavailable commands succeeded.

Minimum verification after a prompt-only or config-only patch:

```powershell
.\gradlew.bat projects --no-daemon
.\gradlew.bat compileJava --no-daemon -x test
.\gradlew.bat processResources --no-daemon
```

If Java behavior changes:

```powershell
.\gradlew.bat test --no-daemon --tests "*Macmini*" --tests "*GpuGateway*" --tests "*LlmRouter*" --tests "*LocalLlmGateway*"
.\gradlew.bat bootJar --no-daemon -x test
```

If topology scripts changed:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify_control_plane_topology.ps1
```

If the Mac mini source itself must be verified and the NAS path is available:

```powershell
Push-Location 'F:\MacNAS\main2'
.\gradlew.bat test --no-daemon
Pop-Location
```

If Bash is available and the Mac mini scripts need smoke evidence:

```powershell
bash 'F:\MacNAS\main2\verify_boot.sh'
bash 'F:\MacNAS\main2\verify_api_router.sh'
```

Commands requiring real secrets or reachable nodes must be reported separately and not counted as passed unless executed with real non-placeholder configuration:

```powershell
$env:MACMINI_API_ROUTER_ENABLED='true'
$env:MACMINI_API_ROUTER_BASE_URL='http://<macmini-host>:8080'
$env:MACMINI_API_ROUTER_ALLOWED_HOSTS='<macmini-host>'
$env:MACMINI_API_ROUTER_OWNER_TOKEN='<real token>'
$env:LLMROUTER_MACMINI_ENABLED='true'
$env:LLMROUTER_MACMINI_BASE_URL='http://<macmini-host>:8080/v1'
$env:LLMROUTER_MACMINI_NAME='llmrouter.auto'
$env:LLM_PROVIDER_GUARD_ALLOW_PRIVATE_REMOTE='true'
$env:LLM_PROVIDER_GUARD_ALLOWED_HOSTS='<macmini-host>:8080'
$env:LLM_OWNER_TOKEN='<real token>'
$env:LLM_OWNER_TOKEN_HEADER='X-Owner-Token'
```

## 10. Failure Classifier

When verification fails, choose exactly one primary category and optional secondary categories:

- `macmini-source-missing`
- `wrong-sourceset`
- `gradle-distribution-network-cache`
- `repository-policy`
- `plugin-mismatch`
- `langchain4j-version-purity`
- `yaml-parse`
- `placeholder`
- `duplicate-class-fqcn`
- `cannot-find-symbol`
- `spring-bind`
- `spring-bean`
- `missing-external-key`
- `provider-disabled`
- `subserver-disabled`
- `subserver-host-not-allowlisted`
- `subserver-auth-missing`
- `subserver-unreachable`
- `subserver-timeout`
- `rate-limit`
- `secret-leak-risk`
- `prompt-rule-violation`
- `other`

Do not retry blindly. Retry only once per blocker class after a specific patch.

## 11. Final Output Format

Return exactly these sections:

## Summary

- 2 to 5 lines.
- Include actual patch scope and verification status.

## do01 / Observation

- Commands executed.
- Key logs, max 10 lines.
- Actual file paths confirmed.
- Effective Gradle settings/sourceSets.
- Mac mini source contracts used.
- Missing tools/files as `evidence_needed`.
- 3-way branch trace summary.
- Web or official docs used, if any.

## do02 / Patch Blocks

For each changed file:

- Observation
- Before snippet
- After snippet
- Minimal unified diff
- Why this file only
- Checkpoint log added
- Secret masking method
- Rollback note

## do03 / Setup Commands

- Exact commands to run from `C:\AbandonWare\demo-1\demo-1\src`.
- Mark network/cache-dependent commands separately.
- Mark commands requiring real Mac mini/router credentials separately.

## do04 / Verification

For each command:

- Command
- Expected success condition
- Observed result
- Failure classification
- Retry decision
- Remaining `evidence_needed`

## do05 / Risks & Next Steps

- Max 5 bullets.
- Include one counterexample or limitation.
- Include decision factors, max 3.
- Include confidence: L/M/H.
- Include the next single most urgent patch.

## 12. Final Evidence Requirements

Final answer must include:

- changed files list
- minimal diff summary
- commands run
- observed command results
- Mac mini source files consulted
- official docs consulted, if any
- `evidence_needed` for unavailable network/cache/tools/secrets
- one counterexample or limitation
- next single most urgent patch after this pass

Never claim:

- build passed unless command output shows it,
- boot passed unless boot command shows it,
- tests passed unless test command shows it,
- Mac mini subserver works unless a real reachable base URL and non-placeholder owner token response prove it.
