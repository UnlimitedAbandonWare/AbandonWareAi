---
name: demo1-toolchain-auto-select
description: Use at the start of vibe-coding or verification on demo-1 to detect existing
---

# demo1-toolchain-auto-select

## Project Root

- Default: `C:\AbandonWare\demo-1\demo-1\src`.

## Loop (always)

1. **Detect** markers (do not invent absences into installs).
2. **Select** the smallest matching row in the table below.
3. **Run** that one command/skill path.
4. **Verify** with its proof column.
5. **Reuse** the same path for the rest of the goal.

## Markers (live SoT)

| Stack | Present when |
|-------|----------------|
| Gradle | `gradlew.bat`, `settings.gradle*`, `build.gradle.kts` |
| Maven | never assume; `pom.xml`/`mvnw` absent in this tree |
| Spring runtime | `Start-RAG.bat`, `scripts/start_rag_stack.ps1`, DevWatch |
| Node BFF | `frontend/package.json` (`lint`, `test`) |
| Soniox | `main/resources/soniox-sidecar/package.json` (deps; no scripts gate) |
| Static UI | `main/resources/static/assets/display|interview` (no package.json) |
| Python helpers | `scripts/requirements-awx-mcp-http.txt`, `tools/conversate-asr/requirements.txt` |
| Verify scripts | `scripts/smoke_*.ps1`, `verify_*.ps1`, `*_tests.ps1`, `*_tests.cjs` |
| Model lock | `scripts/check-model-lock.ps1` |

## Change ??tool map

| Change | Tool | Proof |
|--------|------|-------|
| Java / active config | DevWatch or Start-RAG ForceRestart (`$demo1-dev-reload`) | `[DEV-RELOAD] socket ready` / new process on 18180 |
| Compile check | `.\gradlew.bat :compileJava -x test` | BUILD SUCCESSFUL |
| Post-edit runtime judgement | `Debug-RAG.bat` / `Debug-Meta-Display.bat` `-Action verify [-WithCompile] [-Json]` | exit 0 verified / 3 / 6 + `failedChecks`/`firstFailure`/`hint` in `var\debug\*-verify.json` (checks: compile?, runtime, ports, HTTP probes, out-log exception classes, config, freshness, devwatch, ollama) |
| Narrow unit | `.\gradlew.bat test --tests <Fqcn>` | green |
| Frontend BFF | `cd frontend` ??`npm run lint` / `npm test` | exit 0 |
| Named smoke/verify | matching `scripts\smoke_*` or `verify_*` | script exit/log |
| Stale classpath suspicion | `scripts\verify_full_test_refresh.ps1` | refresh proof |
| Topology | `scripts\verify_control_plane_topology.ps1` | topology green |
| Display/evidence | `$demo1-meta-display-verification`, `$demo1-evidence-debugging`, `$verify-boot` | skill Verification |
| Start/close 실패, 미반영, 힌트 지연 | `Debug-RAG.bat` / `Debug-Meta-Display.bat` (기본 `status`) | exit 0/3/4 + `var\debug\*-status.json` pipeline |

## Do not

- Install Playwright, ESLint, Prettier, Ruff, uv, or `spring-boot-devtools` for convenience.
- Invent `npm run typecheck` / root eslint for static assets.
- Treat `-CheckOnly` or stale HTTP 200 as post-edit proof.
- Hop to a second toolchain after the first path already verified.

## Related

- AGENTS: `DEMO1-TOOLCHAIN-AUTO-SELECT`, `DEMO1-SPRING-VIBE-RELOAD`, Evidence And Verification
- Skills: `demo1-dev-reload`, `demo1-project-root`, `demo1-agent-api-spend-guard`