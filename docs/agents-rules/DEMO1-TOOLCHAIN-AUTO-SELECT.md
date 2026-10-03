<!-- moved-from: AGENTS.md L219-L223 sha256=68883f7665f8ddb46f8ad48050e3917a1e3c266b467528be888de944210ccca4 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-TOOLCHAIN-AUTO-SELECT -->
## Toolchain auto-select (vibe verify loop)
- Detect markers under Project Root, pick the smallest matching tool, verify, reuse — never install linters/formatters/test runners/frameworks when an in-repo equivalent exists. Gradle primary (`gradlew.bat`, Java 17, Spring Boot 3.3.x; no Maven); Node only under `frontend/package.json`.
- **Select:** `*.java`/active config -> DevWatch or ForceRestart (`[DEV-RELOAD] socket ready`); unit seam -> `.\gradlew.bat test --tests <Fqcn>`; compile-only -> `:compileJava -x test` +`:processResources`; `frontend/` -> `npm run lint`/`npm test`; named subsystem -> matching `verify_*`/`smoke_*`. No success claim from `-CheckOnly` or an already-up port. Detail: `.agents/skills/demo1-toolchain-auto-select/SKILL.md`.
<!-- END DEMO1-TOOLCHAIN-AUTO-SELECT -->
