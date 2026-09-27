# ASSIST_NOTES — mxasain 트레이스 A단계 Codex assist 레일 (2026-09-27)

Devin assist-only 메모. 제품 소스 diff 0. Codex A(WP0–WP2)가 LIVE에서 바로 쓸 수 있는
확인값·명령·충돌 지도만 모은다. 새 가설 스캔·새 도구 empire 없음.

- Devin 지시서: `agent-prompts/devin-mxasain-trace-porting-assist-20260927/PASTE_TO_DEVIN.md`
- Codex SSOT(구현자용): `agent-prompts/codex-mxasain-trace-porting-20260927/`
- Codex journal: `mxasain-trace-a-2311272b` (in_progress, scope 8 — 아래 §6 참고)
- assist 부록(요약본): `docs/diagnostics/trace-porting-assist-rails.md`

## 1. P0 레일 — 확인 완료 (evidenceCheckedAt 2026-09-27 ~00:1x UTC)

### 1.1 SSOT 가시성 — OK
`agent-prompts/codex-mxasain-trace-porting-20260927/` 존재:
`CODEX_START_HERE.txt` / `PASTE_TO_CODEX.md` / `LIVE_VERIFY.md` / `README.md` /
`evidence/`(directive·audit·v2_draft·source_evidence_index.json). Downloads 사본 불필요.

### 1.2 Build root / wrapper / JDK / 테스트 태스크 — LIVE 확인
- Build root = Project Root(`settings.gradle.kts`, `gradlew.bat`, `build.gradle.kts`).
- Gradle wrapper **8.7** (`gradle/wrapper/gradle-wrapper.properties`), **Java 17.0.13** (`java -version`).
- 활성 sourceSets(`build.gradle.kts` 780–795): `main/java` + `main/resources`,
  테스트 `src/test/java` + `src/test/resources`. 별도 sourceSet: `chatUiTest`,
  `gatewaySecurityTest`, `crossSubsystemContractTest`, `glmAgentMcp(Test)` 등.
- 명령: 컴파일 `.\gradlew.bat compileJava -x test`(+`processResources`),
  단위 `.\gradlew.bat test --tests <FQCN>` (예: `--tests com.example.lms.trace.SafeRedactorTest`).
- JS: `node v24.13.0`. `node --test src/test/js/*.test.cjs` —
  `chat-trace-ui.test.cjs`, `chat-trace-restore.test.cjs` 존재.
  `node --check .\main\resources\static\js\chat-trace-ui.js` = **syntax만** (실측 exit 0),
  DOM/중첩표 재현은 이 명령으로 증명 불가 → `.test.cjs` fixture 또는 브라우저.

### 1.3 Spend guard — 현 상태
`configs/agent-api-spend-guard.yaml`: 활성화 = env `AWX_AGENT_SPEND_GUARD` any-true
/ `AWX_AGENT_HOST` present / spring profile `agent-spend-guard`.
본 셸 기준 `AWX_AGENT_SPEND_GUARD`·`AWX_AGENT_ALLOW_PAID_MODELS`·`AWX_HOST_MODE` **모두 unset**.
정책상 유료 fanout 금지·local Ollama 우선이 기본. WP0–WP2 검증은 단위테스트+node라
유료 호출 유도 자체가 불필요 — `AWX_AGENT_ALLOW_PAID_MODELS=1` 없으면 유료 경로 제안 금지.

### 1.4 proto-open / admin 오도 룰 — 이미 완화됨 (편집 불필요, 포인터만)
- `.clinerules/61-demo1-vibe-agent-auth-relax.md`: proto-open 하 login/admin/token gate는
  agent-N/A, proto-open=false 뒤집기·Admin 대량삭제 금지 명시.
- `.devin/PROMPTS/chat-ux-3layer-3device-20260923.md`: SUPERSEDED 스탬프 — admin/harden
  권고는 OUT OF SCOPE.
- `.windsurf/rules/demo1-hard-constraints.md` + `AGENTS.md` `DEMO1-PROTOTYPE-AUTH-LIGHT`
  /`DEMO1-VIBE-AGENT-AUTH-RELAX`/`DEMO1-ANONYMOUS-VIBE-DEFAULT` 동일 취지.
- **충돌 지도:** 위 룰 파일들은 foreign lane `clean-vibe-agent-auth-relax-0927`·
  `clean-vibe-low-admin-guardrail-0927`이 **live lease 보유 중**(§5). Devin·Codex 모두
  해당 파일 편집 금지 — 완료 조건에 admin login/logout-block/`proto-open=false`를
  넣는 룰 문장은 이미 제거됨. 추가 완화 필요 시 그쪽 SSOT에만 포인터.

### 1.5 Git lease / staging 지도 — selective만
- `index.lock` 없음 (00:1x UTC 확인).
- **foreign staged 1건 보존:** `AM src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java`
  — unstage·덮기 금지, Codex 커밋 대상에서 반드시 제외.
- 작업트리 대량 foreign dirty(수정·삭제 다수) — `git add -A`/`add .`/`commit -a` 금지.
- 소유 경로만: `python -B scripts/agent_git_vibe_commit.py --repo . --path <owned>... --message-file <file>`
  (단일 진입점, staged-blob secret scan 통과 후 로컬 커밋 1회). push/force 금지.
  선호 git: `F:\git\cmd\git.exe`.

## 2. 막힘별 핸드오프 (P1)

| 막힘 | 확인값 / 명령 한 줄 |
|---|---|
| 테스트 클래스 경로 | WP1: `src/test/java/com/example/lms/trace/SafeRedactorTest.java`, `SafeRedactorFallbackContractTest.java`, `service/trace/TraceHtmlBuilderRedactionTest.java`(+TraceHtml* 11종). 신규 제안명 `TraceDiagnosticProjectionTest`/`TraceHtmlDiagnosticContractTest`는 Codex declared scope. WP2: `src/test/js/chat-trace-ui.test.cjs` + Codex declared `scripts/test_chat_trace_ui_porting.cjs` |
| F01 격리 재현(JDK만) | probe는 `agent-prompts/` 또는 `tools/` 아래에만. `javac`(JDK17)로 `main/java` 파일 단독 컴파일 시 외부 dep 없는 클래스는 `-d` scratch dir로 격리 가능 — **제품 `main`에 probe 심지 말 것**. evidence 패키지가 이미 JDK/Chromium 격리 재현을 했으므로 재실행은 선택 |
| DOM 재현 | `node --check`=syntax만. 중첩표는 `.test.cjs` fixture(node:test+간이 DOM) 또는 브라우저. Chromium fixture는 evidence 패키지 결과 인용으로 대체 가능(로그 보려 재실행 금지) |
| Verify/Debug BAT | `Verify-RAG.bat`, `Debug-RAG.bat -Action status|verify`, `Debug-Meta-Display.bat`, `Read-RAG-Debug.bat` — **read_rag_debug_trail*.ps1은 foreign lease**(§5)라 읽기만 |
| 줄번호 drift | ZIP 줄번호 폐기. LIVE 앵커(00:1x UTC 확인): `SafeRedactor.java:357` `k.contains("token")`, `:392` `k.contains("prompt")`; `TraceHtmlBuilder.java:1122` `sanitizeMeta`, `:1145` `safeMetaKey`; `chat-trace-ui.js:101–103` `querySelectorAll("tr")`+`.slice(100)` `row.remove()` — 메서드명·해시로 재대조 |

## 3. 보존 / hard stop (Codex와 동일)
구형 통복사 ❌ · 최신 전역 snapshot 대체 ❌ · secret/raw prompt 허용 ❌ · 로그 보려
LLM/검색 재실행 ❌ · 무제한 tail ❌ · DOMPurify/secret mask off ❌ · 테스트 기대값
완화·skip으로 PASS 만들기 ❌ · madasin admin PASS 기준 혼입 ❌ · WP3+ 구현 ❌

## 4. Codex declared scope (journal mxasain-trace-a-2311272b)
`docs/diagnostics/trace-porting-baseline.md`, `SafeRedactor.java`,
`TraceHtmlBuilder.java`, `TraceSnapshotStore.java`, `chat-trace-ui.js`,
`TraceDiagnosticProjectionTest`, `TraceHtmlDiagnosticContractTest`,
`scripts/test_chat_trace_ui_porting.cjs` — Codex 소유. Devin은 이 경로에 쓰지 않는다.
assist 부록은 `trace-porting-assist-rails.md`로 분리(파일명 충돌 없음).

## 5. Live foreign leases (00:1x UTC)
| lease topic | 만료 UTC | 경로 |
|---|---|---|
| clean-vibe-agent-auth-relax-0927 | ~02:23 | `.clinerules/00·61`, `.windsurf/rules/demo1-hard-constraints.md`, `agents.md` |
| clean-primitive-debug-ai-impl-0926 | ~01:19 | `read-rag-debug.bat`, `scripts/read_rag_debug_trail*.ps1` |

→ `AGENTS.md` 포인터 블록(지시서상 선택)은 lease 충돌로 **skip** — 완료 조건 오도는
이미 해소(§1.4)라 실익 없음. 필요하면 lease 해제 후 사용자 승인 시에만.

## 6. 환경 실행정책 메모
`__patch_drop__/source_edit_session.ps1` 직접 호출은 ExecutionPolicy 차단됨 →
`powershell -NoProfile -ExecutionPolicy Bypass -File ...` 로 실행. 동일 현상 막히면 이 플래그.
