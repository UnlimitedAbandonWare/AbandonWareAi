# trace-porting assist rails (Devin 부록 — 2026-09-27)

Codex WP0 baseline(`trace-porting-baseline.md`, Codex 소유)을 덮지 않는 **보조 메모**다.
전체 레일·충돌 지도: `agent-prompts/devin-mxasain-trace-porting-assist-20260927/ASSIST_NOTES.md`.

## 확인값 (LIVE, evidenceCheckedAt 2026-09-27 ~00:1x UTC)

| 항목 | 값 | 근거 |
|---|---|---|
| Build root | Project Root(`settings.gradle.kts`+`gradlew.bat`) | 파일 존재 |
| Gradle wrapper | 8.7 | `gradle/wrapper/gradle-wrapper.properties` |
| JDK | Java 17.0.13 | `java -version` |
| sourceSets | `main/java`+`main/resources`, test `src/test/java`+`src/test/resources` | `build.gradle.kts` 780–795 |
| 컴파일 | `.\gradlew.bat compileJava -x test` | AGENTS/상태표와 일치 |
| 단위 테스트 | `.\gradlew.bat test --tests <FQCN>` | 예: `com.example.lms.trace.SafeRedactorTest` |
| JS 테스트 | `node --test src/test/js/*.test.cjs` (node v24.13.0) | `chat-trace-ui.test.cjs` 존재 |
| `node --check` | syntax check만 — DOM 재현 증거 아님 | chat-trace-ui.js exit 0 |
| F01 앵커 | `SafeRedactor.java:357` token, `:392` prompt; `TraceHtmlBuilder.java:1122` sanitizeMeta | LIVE read |
| F02 앵커 | `chat-trace-ui.js:101–103` `querySelectorAll("tr")`→`.slice(100)` `row.remove()` | LIVE read |

## Git / lease
- `index.lock` 없음. foreign staged 1건 `AM src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java` — exclude·보존.
- 소유 경로만 `python -B scripts/agent_git_vibe_commit.py --repo . --path <owned>... --message-file <file>`; push·`add -A` 금지.
- live lease 2건(`agents.md`+clinerules+windsurf hard-constraints / read_rag_debug_trail*.ps1) — 편집 금지, ASSIST_NOTES §5.

## Spend / auth
- `AWX_AGENT_SPEND_GUARD`·`AWX_AGENT_ALLOW_PAID_MODELS` unset → local/무유료 검증만.
- 완료 조건에 admin login / logout-block / `proto-open=false` 포함 금지 — 관련 룰은
  이미 proto-open N/A로 완화됨(`.clinerules/61`, `.devin` SUPERSEDED, AGENTS AUTH-LIGHT).
