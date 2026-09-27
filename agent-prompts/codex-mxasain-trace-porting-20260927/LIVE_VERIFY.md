# LIVE 대조 (2026-09-27, Project Root)

대상: `C:\AbandonWare\demo-1\demo-1\src`

| 항목 | 결과 | 근거 |
|---|---|---|
| F02 nested `tr` | **still_present** | `main/resources/static/js/chat-trace-ui.js:101-102` — `table.querySelectorAll("tr")` then `.slice(100)` remove |
| F01 token/prompt key | **still_present** (정적) | `SafeRedactor.java` ~357 `k.contains("token")`, ~392 `k.contains("prompt")`; `TraceHtmlBuilder.sanitizeMeta` @1122 |
| SafeRedactor / TraceHtmlBuilder | 존재 | `main/java/com/example/lms/trace/SafeRedactor.java`, `.../service/trace/TraceHtmlBuilder.java` |
| 격리 재실행 E06/E07 | **NOT_RUN** (이 브리프 작성 시) | 패키지 probes PASS는 재현 성공이지 수정 완료 아님 |

권장: Codex는 WP0에서 위 항목을 baseline에 적고 WP1→WP2만 구현.
