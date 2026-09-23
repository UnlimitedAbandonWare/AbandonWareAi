# Demo1 Runtime Map

## Verified In This Checkout

- CWD: `C:\AbandonWare\demo-1\demo-1\src`
- Existing repo-local skills: `.agents/skills/context-purity-vector-memory`, `.agents/skills/judge-recovery`
- Root build files: `settings.gradle`, `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`
- Root `settings.gradle` includes `:app`; `:demo-1` and `:lms-core` are gated by `includeLegacyModules`.
- Root `build.gradle.kts` sets Java 17, Spring Boot 3.3.4, and `dev.langchain4j:langchain4j` plus `langchain4j-open-ai` at `1.0.1`.
- Root `build.gradle.kts` sourceSets:
  - main Java: `main/java`
  - main resources: `main/resources`
  - tests: `src/test/java`, `src/test/resources`
- `app/build.gradle.kts` compiles `app/src/main/java_clean` and resources from `app/src/main/resources`.
- `gradlew`, `gradlew-real`, `verify_boot.sh`, `verify_boot_plus.sh`, `tools/run_build_error_miner.sh`, and `tools/build_error_miner.py` exist.
- No `*.zip` was present at the repo root during the skill creation pass.
- No `package.json` or `next.config.*` was found inside this `src` checkout during the skill creation pass.

## Missing Artifact Handling

Record missing referenced tools in this form instead of creating placeholders:

```text
evidence_needed: Tool_B / verify with Get-ChildItem -Recurse -Directory -Filter Tool_B
evidence_needed: AbandonWareTool_v1 / verify with Get-ChildItem -Recurse -Directory -Filter AbandonWareTool_v1
evidence_needed: BackupsXS/index.jsonl / verify with Test-Path BackupsXS\index.jsonl
evidence_needed: orchestration/run_pipeline.sh / verify with Test-Path orchestration\run_pipeline.sh
```

## Useful Verification Commands

Run from `C:\AbandonWare\demo-1\demo-1\src` unless a command proves another root:

```powershell
.\gradlew.bat projects --no-daemon
.\gradlew.bat compileJava --no-daemon -x test
.\gradlew.bat :app:classes --no-daemon -x test
.\gradlew.bat bootJar --no-daemon -x test
bash verify_boot.sh
bash verify_boot_plus.sh
bash tools/run_build_error_miner.sh build-logs analysis/build_error_report
```

If `bash` is unavailable, report it as environment evidence and use Gradle commands directly.

## Runtime Endpoints And UI Surface

- Backend chat UI: `main/resources/templates/chat-ui.html`
- Main chat API: `main/java/com/example/lms/api/ChatApiController.java`
- RAG API: `main/java/com/example/lms/api/RagOrchestratorController.java`
- Security path allowlist: `main/java/com/example/lms/config/AppSecurityConfig.java` and `main/java/com/example/lms/security/ChatOpenSecurityConfig.java`
- SSE DTO and streaming support: `main/java/com/example/lms/dto/ChatStreamEvent.java`, `main/java/com/example/lms/service/chat/ChatStreamEmitter.java`

## High-Risk Search Provider Seams

- Naver: `main/java/com/example/lms/service/NaverSearchService.java`
- Brave: `main/java/com/example/lms/service/web/BraveSearchService.java`
- Web fusion/orchestration: `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`
- Debug/trace: `main/java/com/example/lms/debug/DebugEventStore.java`, `main/java/com/example/lms/search/TraceStore.java`

Patch missing-key behavior as provider disabled with reason. Never turn blank, dummy, `sk-local`, `test`, `changeme`, or unresolved `${...}` into a successful external credential.

