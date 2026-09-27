# IDEAS — 디버깅 루프에 얹는 「원시적 Debug-AI」옵션 탐색
날짜: 2026-09-26 KST · 작성: Devin · 역할: 아이디어/랭킹만 (구현=Clean, 소스 diff 0)
SSOT: `DEVIN_KICKOFF.md` (같은 폴더) · Clean 계약: `agent-prompts/clean-primitive-debug-ai-impl-20260926/CLEAN_KICKOFF.md`

---

## 0. 「원시적 AI」의 정의 (이 문서 전체의 축)

- **원시적** = 관측 경계(`var/rag-launcher/LATEST.json` + 선언된 `evidencePaths`의 마스킹 excerpt + 최신 `var/debug` 다이제스트) **안에서만** 로컬 규칙/가벼운 분류기가 failure 클래스를 붙이고, 다음 도구를 **순위 제안**한다.
- **AI** = LLM 필수 아님. 로컬 매트릭스·정규식 miner가 본체, LLM/MCP는 명시적 옵트인 rung.
- **디버깅 기능 자체** = 기존 skill-free 척추(`Status-RAG → Verify-RAG → Read-RAG-Debug`)에 **붙는다**. 별도 제품·새 incident lifecycle·제2 SSOT 아님.
- **금지** = 제안 명령 자동 실행, ForceRestart를 「증명」으로 사용, Spring triadic 기본 on, JVM 의존, secrets 출력.

## 1. 현재 루프 — 디스크 검증 증거 (설계가 깨면 안 되는 것)

### 1.1 Writer는 이미 분류기다 (핵심 발견)
`scripts/start_rag_stack.ps1`은 run 종료 시 이미 failure 분류를 계산해 LATEST에 기록한다:

- `Resolve-RagFailurePoint` (L639–652): `(reason, listenerStatus, compileFound)` →
  `compile | verification | provenance | port-conflict | ollama | spring-start | launcher`
- `Get-RagFailureNextAction` (L654–665): failurePoint →
  `fix_compile_errors_then_rerun`, `inspect_ollama_runtime`, `resolve_port_or_owner_conflict`, … (사람용 동사 문자열, bat 아님)
- `Get-RagRunEvidence` (L588–637): `evidencePaths[]`(루트 상대), `firstErrorExcerpt[]`(마스킹된 3~8줄), `compileFound` bool — 컴파일 진단(`.java:N: error`) 발견 시 그 창을 우선 채택
- `Write-RagRunResult` (L683–693): run-dir `result.json` + 포인터 `var/rag-launcher/LATEST.json` (`schemaVersion awx.rag_launcher_latest.v1`) atomic write

### 1.2 Reader가 그걸 버린다 (정확한 seam)
`scripts/read_rag_debug_trail.ps1`:

- L275–279 — `nextCommand` 현행 전부:
  ```powershell
  $report.nextCommand = switch ($exitCode) {
      0 { 'Debug-RAG.bat -Action status' }
      3 { 'Debug-RAG.bat -Action status' }
      default { 'Debug-RAG.bat -Action verify' }
  }
  ```
  → LATEST의 `failurePoint`/`nextAction`/`listenerStatus`/`stage`, 재독한 excerpt 모두 **미참조**.
- L208–216 — exit 의미: 0 ready / 3 no-latest·not-running / 4 degraded / 1 tool-error (**유지 계약**)
- L218–238 — `$report.latest`에 `failurePoint`, `listenerStatus`, `nextAction` 등을 이미 옮겨 담음 (분류 입력이 이미 손에 있음)
- L68–80 `Resolve-TrailPath` — evidence 경로를 root 안으로 강제(제작된 요약으로 파일 탈취 방지)
- L82–113 `Get-TrailExcerpt` — 비밀 패턴 라인 drop + 마스킹된 3~8줄 오류 창 재독
- `debug_rag_stack.ps1` L1304–1316 — `-Action trail`이 이 스크립트를 spawn하고 stdout/exit를 **그대로 통과** → 새 필드는 Debug-RAG 래퍼 수정 없이 흘러감

### 1.3 라이브 LATEST 관찰 (2026-09-26 실측)
runId `20260926-230555-7e83e1d4` (`role=dev`):
`ok=true status=ready stage=READY failurePoint=null listenerStatus=listener-ready nextAction=open_browser_target_url` —
**그런데** `firstErrorExcerpt`는 H2 `JdbcSQLSyntaxErrorException … index already exists` DDL 노이즈를 담고 있다.

→ 설계 규칙 도출: **excerpt 추론은 `ok/status` 판정 이후에만** 적용한다. ready run의 benign 창업 로그가 클래스를 `compile_or_build`로 뒤집으면 안 된다. writer 필드(`failurePoint`) > ok/status > stage/reason > excerpt 순으로 권위를 둔다.

### 1.4 이미 있는 AI 섬 (통합 대상, 재작성 대상 아님)
| 섬 | 위치 | 하는 일 | 루프와 거리 |
|---|---|---|---|
| build_error_miner | `tools/build_error_miner.py` L13–47 | 30+ 정규식 클래스(Gradle/Javac/Kotlin/Spring/Test) + 정규화·비밀 마스킹 | 오프라인 완전 |
| ai_debug_assist | `tools/ai_debug_assist.py` L96 `diagnose()` | 로그(≤1MB)→miner 클래스+snapshot(+MCP 검증 +delegate 가설), **명령 미실행**, `--out` 배타 생성(L231) | 호출자 없음 |
| DebugCopilotService | `main/java/.../trace/DebugCopilotService.java` L29–33 | TraceStore 브레드크럼→findings+commands, best-effort | JVM 한정, 요청 스코프 |
| Triadic adjudicator | `.../ensemble/EvidenceGroundedTriadicDebugAdjudicator.java` L52 | `@Value("${debug.copilot.triadic.enabled:${DEBUG_COPILOT_TRIADIC_ENABLED:false}}")` **기본 false**, admin 명시 동작(L86–95) | 수요형만 |
| skills | evidence-debugging / two-tools / observed-debugging-meta | 사건 패킷, first-failing-layer(L31–42), P0/P1/P2, 2슬롯 | 에이전트 수동 |
| placement-scan | `scripts/demo1_tool_placement_scan.py` L73–85 | `rag-debug-trail` 인텐트→Read-RAG-Debug rank1 이미 존재 | 로드아웃 정합 |
| debug-ui notepad | `docs/debug-ui/README.md` L5–6 | 번들 뷰어 | **제2 SSOT 아님** |

규모 맥락(EVIDENCE.md): debug 계열 root bat ≈11, 관련 scripts ≈81, skills ≈21, run dirs ≈157 — **구형 run에 writer 필드가 없는 게 정상**. read-time 추론의 후방호환이 그래서 필요하다.

## 2. Failure-class taxonomy v1 (Clean이 상수로 고정할 초안)

클래스 결정 순서 = §1.3의 권위 순서. 이름은 CLEAN_KICKOFF §1 세트(`ready/not_running/degraded_verify/compile_or_build/launcher_stage_fail/meta_wear/unknown`)를 **확장**(writer failurePoint와 1:1 대응되게):

| class | 신호 (우선순위 순) | primary nextCommand | alts |
|---|---|---|---|
| `ready` | `ok=true` or `status=ready` | `Debug-RAG.bat -Action status` | — |
| `not_running` | LATEST 없음/파싱불가, `source=none` | `Status-RAG.bat` | `Debug-RAG.bat -Action status` |
| `stale_pointer` | `source=run-dir-fallback\|run-dir-no-result` | `Status-RAG.bat` (alive 확인) | LATEST 재생성을 위한 Start 힌트 문구(실행 아님) |
| `compile_or_build` | `failurePoint=compile`, else excerpt `\.java:\d+: error\|:compileJava\|BUILD FAILED\|Compilation failed` | `Verify-RAG.bat` (compile 포함 판정 경로) | `Debug-RAG.bat -Action verify`; **AiAssist 유일 자격 클래스** |
| `degraded_verify` | `failurePoint=verification` or `listenerStatus=verification-failed`, else exit4+verify artifacts | `Debug-RAG.bat -Action verify` | `Debug-RAG.bat -Action tail` |
| `port_conflict` | `failurePoint=port-conflict` or reason `port-conflict\|foreign-port-owner\|wear-runtime-protected` | `Debug-RAG.bat -Action status` (소유자 확인) | tail |
| `ollama_dependency` | `failurePoint=ollama` or `reason ^ollama-` | `Debug-RAG.bat -Action status` | `scripts/ollama-status-snapshot.ps1` (read-only) |
| `provenance_fail` | `failurePoint=provenance` | `Debug-RAG.bat -Action status` | evidencePaths 내 `spring-owned.json` 수동 확인 안내 |
| `launcher_stage_fail` | `failurePoint=spring-start\|launcher` or stage 비정상 | `Debug-RAG.bat -Action verify` | excerpt 경로 재독 안내 |
| `meta_wear` | `role=wear` 또는 MetaDisplay 런 타깃 (다른 클래스에 **오버레이**) | `Debug-Meta-Display.bat -Action status` | `Verify-Meta-Display.bat` |
| `unknown` | 위 어느 것도 해당 없음 | `Debug-RAG.bat -Action status` (안전 기본) | — |

writer `nextAction`(동사 문자열)은 매트릭스에 안 쓰고 `rationale` 한 줄로 echo (예: `writerHint=fix_compile_errors_then_rerun`).

## 3. 옵션

### Opt1 — Trail nextCommand matrix (PS-only)
- **한 줄 정의:** `read_rag_debug_trail.ps1`의 nextCommand 스위치를 §2 분류기+매트릭스로 교체. 리포트에 `failureClass`/`nextCommandAlts`/`rationale` 추가.
- **「원시 AI」감각:** 사용자는 Read-RAG-Debug 한 번으로 「이번엔 verify가 아니라 compile부터」처럼 **상황별 다른 1~3순위 명령**과 그 이유 한 줄을 본다.
- **재사용 seam:** 같은 ps1(입력 필드 전부 이미 `$report.latest`/`$report.evidence`에 존재). Debug-RAG `-Action trail` 통과 경로(L1304–1316) 자동 계승.
- **신규 파일:** 없음 (테스트 픽스처만).
- **데이터 흐름:** LATEST(+fallback) → exit/verdict(기존) → class 해석(§2 순서) → 매트릭스 → 인쇄. 실행 없음.
- **장점:** 구형 run 후방호환; JVM·파이썬·네트워크 불요; 로드아웃 척추 그대로 확장; exit 계약 불변.
- **단점:** 정규식 추론은 writer 분류보다 약함(그래서 writer 우선); 클래스 증가 시 매트릭스 유지 필요.
- **실패모드:** excerpt 노이즈 오분류 → §1.3 순서로 방어; 알 수 없는 패턴 → `unknown` 안전 기본.
- **Prototype Light 친화도:** 5 · **로드아웃 충돌 위험:** 1
- **자동실행 유혹 방어:** 출력은 `[TRAIL NEXT]` 문자열 계열 유지; 매트릭스 값은 bat 이름 상수(인자 조립 금지), `Invoke-Expression`/spawn 없음.
- **검증:** 픽스처 LATEST(§THE_ONE 7종) → `failureClass`+`nextCommand` assert; 라이브 run에서 exit 의미 불변 확인.
- **구현 크기:** S (ps1 한 함수+매트릭스 테이블+리포트 필드)

### Opt2 — Trail + ai_debug_assist bridge (Opt1 위, 옵트인 플래그)
- **한 줄 정의:** `-AiAssist` 스위치가 있고 class=`compile_or_build`이며 err.log evidence가 있을 때만 `python -B tools/ai_debug_assist.py`를 호출해 가설을 **인쇄 첨부**.
- **「원시 AI」감각:** 컴파일 계열 실패에서 「cannot-find-symbol ×4, duplicate-class ×1 → 가설 3건(파일:라인)」까지 같은 화면에. 단, 플래그를 켠 때만.
- **재사용 seam:** assist는 완제 계약(`--log --out --mcp off --ai off`) 그대로 **호출만** — 수정 불필요. 결과 JSON은 `var/debug/assist-<runId>-<ts>.json` 신규 경로(assist의 배타 생성 규약과 상성).
- **신규 파일:** 없음.
- **데이터 흐름:** Opt1 분류 → (플래그+자격) → assist spawn(≤60s, fail-soft) → `awx.ai-debug-observation.v1` 요약 인쇄 → trail exit **불변**.
- **장점:** 「AI」체감이 가장 큼; 컴파일 클래스로 스코프를 좁혀 스팸 없음; assist의 비밀 마스킹·크기 상한·미실행 계약을 그대로 상속.
- **단점:** 자식 프로세스가 생겨 「읽기 전용」의 의미가 넓어짐 → 서버 미접촉·모델 미호출(기본 `--ai off --mcp off`)로 계약 문장화 필요; delegate rung는 유료/외부 위험이라 별도 명시 옵트인.
- **실패모드:** assist 타임아웃/거절 → `aiAssist=failed-soft`로 인쇄하고 본체 정상 종료; 대형 로그 → assist 자체 1MB 상한이 거절.
- **Prototype Light 친화도:** 5 (기본 off, 로컬 분류만) · **로드아웃 충돌 위험:** 1
- **자동실행 유혹 방어:** assist 출력은 `findings/proposedTests` 텍스트 — runner 계약에 「제안 명령 실행 금지」가 원래 내장(L4 주석); trail은 경로+요약만 인쇄.
- **검증:** 픽스처 compile run + 임의 err.log → `-AiAssist` 시 assist JSON 생성·`aiAssist` 필드 assert; 미지정 시 파일 0개.
- **구현 크기:** S~M (Opt1 + 분기 spawn + 요약 인쇄)

### Opt3 — Offline DebugCopilot twin (Java 휴리스틱의 Python 이식)
- **한 줄 정의:** `DebugCopilotService`의 TraceStore→findings+commands 휴리스틱을 trail JSON 입력용 Python으로 재구현.
- **「원시 AI」감각:** 클래스보다 풍부한 「원인 후보 + 명령」 목록 — 하지만 입력 증거가 다르다(요청 브레드크럼 vs 런처 요약).
- **재사용 seam:** 휴리스틱 아이디어만. 입력 모델이 달라 상당 부분 신규 해석.
- **신규 파일:** `tools/` 계열 신규 py 1~2 + 테스트.
- **데이터 흐름:** trail JSON → twin 분석 → 별도 출력. (LATEST 옆 제2 해석기)
- **장점:** JVM 불요의 copilot 유사 체감; Opt1보다 표현력 ↑ 가능.
- **단점:** **사실상 두 번째 휴리스틱 엔진** — Java 원본과 규칙 드리프트 확실; 증거 평면이 달라(TraceStore ≠ LATEST) 「이식」은 명목상; 새 debug 제국 냄새.
- **실패모드:** 원본 수정 시 쌍이 조용히 갈라짐 — 누가 진짜인가.
- **Prototype Light 친화도:** 3 · **로드아웃 충돌 위험:** 3
- **자동실행 유혹 방어:** Opt1과 동일 원칙 가능하나 신규 엔진이라 계약 재구축 필요.
- **검증:** Java↔Python 동일 입력 기대표 필요 — 유지 부담이 검증을 먹는다.
- **구현 크기:** M~L

### Opt4 — Thin `Debug-AI.bat` naming/loadout wrapper
- **한 줄 정의:** 새 로직 없이 Read-RAG-Debug(+향후 `-AiAssist`)를 가리키는 퀵슬롯 이름 하나.
- **「원시 AI」감각:** 「Debug-AI 치면 된다」는 이름 부여일 뿐, 분류 능력은 Opt1/2 의존.
- **재사용 seam:** `Read-RAG-Debug.bat`이 이미 `%*` 패스스루 — 배울 플래그 없음.
- **신규 파일:** bat 1 (선택).
- **데이터 흐름:** bat → 동일 ps1.
- **장점:** 최소 diff; LOADOUT Quickslot 라벨과 정합(`Debug-AI = Read-RAG-Debug(+AiAssist)` 한 줄, CLEAN_KICKOFF D5).
- **단점:** **단독으로는 지능 증가 0** — THE ONE 후보가 아니라 이름표; bat 증식은 ops surface 방향과 역행(OSTP 기류).
- **실패모드:** 두 엔트리의 역할 붕괴(어느 게 진짜?) → LOADOUT에 별칭으로만 기록하면 해소.
- **Prototype Light 친화도:** 4 · **로드아웃 충돌 위험:** 2
- **자동실행 유혹 방어:** 해당 없음(래퍼).
- **검증:** bat 존재 시 호출 → trail exit 동일.
- **구현 크기:** S (≤15줄)

### Opt5 — Spring triadic / metrics deepen — **기각 후보**
- **한 줄 정의:** `DebugCopilotService`+triadic을 키워 디버그 판정을 JVM 안에서 심화.
- **기각 근거:**
  1. **순환 의존:** Spring이 죽은 상황이야말로 이 디버그 루프가 필요한 때 — JVM 의존 도우미는 정작 필요할 때 못 켠다.
  2. **증거 평면 불일치:** copilot은 `TraceStore` 요청 브레드크럼(채팅 요청)을 먹지, 런처 run 요약을 먹지 않는다.
  3. **플래그 계약:** `debug.copilot.triadic.enabled` 기본 `false`(L52 검증) — 켜는 것 자체가 정책 변경+모델 호출 비용.
  4. **skill-free 위반:** AGENTS L169–177의 「스킬 조회 불필요」루프에 Java/스킬 문맥을 끼워 넣는 것.
  5. **최소 diff 위반:** 새 Java 경로 = 빌드·테스트·라이브 재시작까지 필요 → 「아이디어→구현」이 아니라 플랫폼 작업.
- **「살릴 거면 언제만」:** 매트릭스가 반복적으로 `unknown`을 낸 **새 실패군**이 실측으로 쌓이고, 그때도 첫 선택은 PS 매트릭스 확장. triadic은 지금처럼 admin 명시 호출로만.
- **Prototype Light 친화도:** 1 · **로드아웃 충돌 위험:** 4 · **구현 크기:** L

### 옵션으로 올리지 않은 것들 (기록용)
- LATEST 스키마에 `nextCommands[]` 추가 (write-time 분류): Opt1에 read-time 추론이 이미 필요하므로(구형 run), writer 변경 없이 같은 결과 → 최소 diff에서 밀림. 다만 **writer 필드 우선 읽기**가 그 이점의 절반을 이미 회수한다.
- debug-ui notepad 창: 제2 SSOT 금지(README L5–6).
- 새 `Debug-AI.ps1` 독립 스크립트: Opt4의 스크립트판 — 엔트리 이원화만.

## 4. 채점표 (부록 B 충족, 1~5 높을수록 우세)

| 기준 | Opt1 matrix | Opt2 +assist | Opt3 copilot-twin | Opt4 thin bat | Opt5 Spring |
|---|---|---|---|---|---|
| LATEST 재사용 | 5 | 5 | 4 | 4 | 2 |
| skill-free 유지 | 5 | 5 | 4 | 5 | 1 |
| 구현 크기 (낮을수록↑) | 5 | 4 | 2 | 5 | 1 |
| 로드아웃 정합 | 5 | 5 | 3 | 4 | 1 |
| 자동실행 유혹 낮음 | 5 | 4 | 5 | 5 | 2 |
| Prototype Light | 5 | 5 | 4 | 4 | 2 |
| **합계** | **30** | **28** | **22** | **27*** | **9** |

\* Opt4는 분류 능력 0 — 점수는 「래퍼로서의 무해함」이지 「원시 AI」가 아님. 단독 THE ONE 부적격.

## 5. 결론

- **승자 = Opt1 + Opt2(옵트인 플래그 내장)** — 킥오프 권장 시드와 일치. 근거: writer가 이미 뿌린 분류(`failurePoint`)를 reader가 **표면화**하는 것이 가장 작은 「원시 AI」며, assist 브리지가 「AI」체감을 유일하게 추가하면서도 스코프를 `compile_or_build`+플래그로 닫는다.
- Opt4는 THE ONE의 **부속 이름표**로만 허용(≤1 bat 또는 LOADOUT 한 줄).
- Opt3은 중복 엔진 비용이 실익을 상회. Opt5는 §3 기각 근거대로.
- 상세 동결·화이트리스트·Done when → **`THE_ONE.md`**.

## 6. Anti-patterns (구현자·리뷰어 공통 경계)
- Devin-first/exec 난사를 디버그 진입으로 (9only 교훈, LOADOUT anti-combo).
- ForceRestart/Status 200을 「구현 증명」으로 사용.
- 제안 명령 자동 실행 — 어느 옵션에서든.
- notepad/새 JSON을 제2 SSOT로 승격.
- triadic 기본 on을 「개선」으로 포장.
