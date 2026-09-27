# THE ONE — 「Trail Classifier + Assist Bridge」동결
날짜: 2026-09-26 KST · 작성: Devin · 상태: **FROZEN** · 구현: Clean (`agent-prompts/clean-primitive-debug-ai-impl-20260926/CLEAN_KICKOFF.md`)

## 1. 선택과 한 문장

**THE ONE = Opt1 (Trail nextCommand matrix) + Opt2 (`-AiAssist` 옵트인 브리지) — `read_rag_debug_trail.ps1`이 writer의 `failurePoint`와 excerpt 추론으로 `failureClass`를 붙이고 클래스별 랭크된 `nextCommand`를 인쇄하며(실행 절대 금지), 컴파일 클래스에서만 플래그로 `tools/ai_debug_assist.py`를 fail-soft 호출한다.**

## 2. 왜 다른 옵션을 이겼는가

- **vs Opt3 (copilot twin):** Java 휴리스틱의 Python 쌍은 규칙 드리프트가 확실하고, 입력 증거(TraceStore 요청 브레드크럼)가 런처 요약과 다른 평면이라 「이식」이 아니라 신규 엔진. M~L 크기로 5점 자산(LATEST 필드) 재사용이 4점에 그침.
- **vs Opt4 (thin bat):** 분류 능력 0의 이름표. `Read-RAG-Debug.bat`의 `%*` 패스스루가 이미 플래그 전달을 해결 → 단독 불가, 부속 이름표로만.
- **vs Opt5 (Spring deepen):** JVM 순환 의존(Spring이 죽을 때 필요한 도구가 Spring 필요), 증거 평면 불일치, `DEBUG_COPILOT_TRIADIC_ENABLED` 기본 false 정책 변경, skill-free 위반 — 기각 유지.
- **Opt1+2 승리 축:** ① writer가 이미 계산한 `failurePoint`/`nextAction`(`start_rag_stack.ps1` L639–665)을 reader가 **표면화** = 가장 작은 진짜 「원시 AI」; ② 157개 구형 run dir에 writer 필드가 없어도 excerpt 추론으로 후방호환; ③ assist는 완제 계약(`awx.ai-debug-observation.v1`, 미실행 내장)을 **호출만** 하므로 새 AI 섬 0.

## 3. Clean 화이트리스트 (만져도 되는 것 — 이 목록 밖은 전부 금지)

| 경로 | 허용 내용 |
|---|---|
| `scripts/read_rag_debug_trail.ps1` | `failureClass` 해석 함수 + 클래스 매트릭스 + `nextCommand`/`nextCommandAlts`/`rationale`/`aiAssist` 리포트 필드 + `-AiAssist` 파라미터. exit 코드 의미(0/1/3/4) **불변**. |
| `scripts/test_rag_debug_trail_matrix.py` (신규) | 픽스처 기반 unittest 1파일. |
| `scripts/fixtures/rag-debug-trail/*.json` (신규 디렉터리) | §6 픽스처 LATEST/evidence. |
| `Read-RAG-Debug.bat` | **선택·주석급만:** `-AiAssist` 안내 echo/타이틀 한 줄. `%*` 패스스루는 이미 동작하므로 로직 변경 불필요. |
| `AGENTS.md` | `DEMO1-DEBUG-ENTRYPOINTS`/`DEMO1-RAG-DEBUG-TRAIL` 안에 **한 단락**: failureClass + 매트릭스 + `-AiAssist` 언급. |
| `docs/agent-loadout/LOADOUT.md` | **조건부:** 그 파일이 병행 Clean 작업으로 존재할 때만 `Debug-AI = Read-RAG-Debug(+AiAssist)` 한 줄. 없으면 만들지 말 것(소유권은 loadout Clean). |

## 4. Clean 블랙리스트 (만지면 안 되는 것)

- `scripts/start_rag_stack.ps1` 및 LATEST/result.json 스키마 일체 — **writer와 포인터 형식은 불변 SSOT**. Clean은 읽기 쪽만.
- `tools/ai_debug_assist.py`, `tools/build_error_miner.py` — 호출만, 수정 없음.
- 모든 Java (`DebugCopilotService`, `EvidenceGroundedTriadicDebugAdjudicator`, `TriadicDebugAdjudicationController` 포함), `debug.copilot.triadic.enabled`/`DEBUG_COPILOT_TRIADIC_ENABLED` 플래그 값, 신규 Java 패키지.
- `application*.yml`, 모델 라우팅, PromptBuilder/LC4j 버전.
- `scripts/debug_rag_stack.ps1` — `-Action trail` 통과 경로는 이미 새 필드를 그대로 흘림(검증만).
- 제2 SSOT/incident lifecycle/새 debug 제품 엔트리, `docs/debug-ui` notepad 승격.
- OSTP(`demo1_ops_surface_thin`), codex home quarantine, 9only ingest — 별도 작업 범위.
- secrets 출력, `git push`/`add -A`/`add .`, 제안 명령의 자동 실행(어느 경로로든).
- `var/rag-launcher/` 실데이터 편집 — 픽스처는 `scripts/fixtures/` 아래 복제.

## 5. 공개 CLI/필드 계약 (이름 고정)

- 신규 파라미터: `-AiAssist` (switch, 기본 off). 지정 시에만 assist spawn. **delegate/라이브 모델 경로는 이번 범위 밖** — `--mcp off --ai off` 고정(로컬 miner 분류만).
- 리포트 신규 필드(콘솔+`-JsonStdout` 양쪽):
  - `failureClass`: §6 표의 클래스 문자열
  - `nextCommand`: 클래스 1순위 bat/명령 문자열 (현행 필드 유지·값만 매트릭스)
  - `nextCommandAlts`: 0~2개 대안 문자열
  - `rationale`: 한 줄, 비밀 없음 (예: `writer failurePoint=compile`; `writerHint=fix_compile_errors_then_rerun` echo 허용)
  - `aiAssist`: `skipped|disabled|not-eligible|ran|failed-soft` + 요약 필드(`assistPath`, `classes` 카운트)
- assist 호출 형태(고정):
  `python -B tools/ai_debug_assist.py --log <evidence err.log> --out var/debug/assist-<runId>-<utcstamp>.json --mcp off --ai off`
  — `--out`은 매번 신규 경로(assist 배타 생성), 타임아웃 ≤60s, 실패 시 `aiAssist=failed-soft`로 인쇄하고 trail exit 불변.
- 콘솔 `[TRAIL NEXT]` 라인은 유지하고 `failureClass`/`alts`/`aiAssist`를 인접 라인으로 인쇄. `[TRAIL EXIT]` 의미 불변.

## 6. failureClass 결정 순서 + 최소 클래스 세트 (상수 고정)

순서: ① LATEST 없음/파싱불가 → `not_running` ② `ok=true`/`status=ready` → `ready` (excerpt 노이즈 무시 — 라이브 runId 20260926-230555-7e83e1d4가 ready+H2 DDL excerpt 공존 증거) ③ writer `failurePoint` 비어있지 않음 → 직접 매핑(compile→`compile_or_build`, verification→`degraded_verify`, port-conflict→`port_conflict`, ollama→`ollama_dependency`, provenance→`provenance_fail`, spring-start/launcher→`launcher_stage_fail`) ④ `role=wear` → `meta_wear` 오버레이 ⑤ excerpt/stage/reason 추론(구형 run) ⑥ `unknown`.

클래스 세트(최소, 이름 고정): `ready`, `not_running`, `stale_pointer`, `compile_or_build`, `degraded_verify`, `port_conflict`, `ollama_dependency`, `provenance_fail`, `launcher_stage_fail`, `meta_wear`, `unknown`. (`stale_pointer`·`port_conflict`·`ollama_dependency`·`provenance_fail`은 시드 세트 확장 — writer 값과 1:1 대응 위함)

## 7. 픽스처 시나리오 (≥4 요구 → 7종 제안, `scripts/fixtures/rag-debug-trail/`)

| 파일 | 내용 | 기대 |
|---|---|---|
| `ready/LATEST.json` + benign excerpt | ok=true, failurePoint=null, firstErrorExcerpt=H2 DDL (라이브 형태) | `ready` + `Debug-RAG.bat -Action status`, excerpt가 클래스를 안 뒤집음 |
| `not_running/` | LATEST 부재, run dir 없음 | `not_running`, exit 3, `Status-RAG.bat` |
| `stale_pointer/` | LATEST 부재 + run-dir result.json 있음 | `stale_pointer` 또는 exit≥3 계열 + fallback 경고 유지 |
| `compile/LATEST.json` + err.log(`X.java:42: error: cannot find symbol`) | status=failed, failurePoint=compile | `compile_or_build`, primary `Verify-RAG.bat`; `-AiAssist` 시 assist JSON 생성 |
| `verification/LATEST.json` | failurePoint=verification (writer 필드만, excerpt 없음) | `degraded_verify`, writer 필드가 excerpt 부재보다 우선 |
| `port_conflict/LATEST.json` | reason=port-conflict 계열 | `port_conflict` |
| `wear/LATEST.json` | role=wear + 임의 실패 | `meta_wear` 오버레이 → Meta-Display 계열 제안 |

픽스처에는 비밀 유사 문자열도 없음(리뷰 스캔 대상).

## 8. Done when (Clean 체크리스트)

- [ ] `failureClass` + `nextCommand`/`nextCommandAlts`/`rationale`/`aiAssist` 리포트 필드 동작(인쇄+JSON)
- [ ] §6 결정 순서 구현 — writer `failurePoint`가 excerpt보다 우선, ready가 노이즈에 안 짐
- [ ] exit 코드 의미(0/1/3/4) 불변, `-Action trail` 통과 시 새 필드 그대로 관찰
- [ ] `-AiAssist` 미지정 시 assist 파일 0개; 지정+compile 시 생성·요약 인쇄·실패 fail-soft
- [ ] 계약 테스트(§7) PASS 또는 NOT_RUN+사유
- [ ] AGENTS 한 단락 갱신; LOADOUT 조건부 한 줄
- [ ] 블랙리스트 준수(소스 diff가 화이트리스트 안에서 끝남), secrets 미출력
- [ ] **「Devin 소스 diff = 0」확인 문장**을 보고에 포함 (Devin은 본 두 문서만 작성)

## 9. 검증 커맨드 초안

```powershell
cd C:\AbandonWare\demo-1\demo-1\src
# 단위(픽스처)
python -B -m unittest scripts.test_rag_debug_trail_matrix -v
# 라이브 읽기 (서버 미접촉)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\read_rag_debug_trail.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\read_rag_debug_trail.ps1 -JsonStdout |
  powershell -NoProfile -Command "$r=$input|ConvertFrom-Json; $r.failureClass; $r.nextCommand; $r.aiAssist"
# 통과 경로
Debug-RAG.bat -Action trail   # 새 필드 관찰, exit 의미 동일
```

Status/Verify/Start-RAG/ForceRestart를 「구현 증명」으로 돌리지 말 것 — LATEST 읽기 + 픽스처면 충분.

## 10. 포인터

- Clean SSOT: `agent-prompts/clean-primitive-debug-ai-impl-20260926/CLEAN_KICKOFF.md` (본 THE_ONE이 시드 D1–D6를 **대체·상속**; 충돌 시 본 문서 우선 — Clean D6 규칙과 일치)
- 설계 근거 전부: `IDEAS.md` (같은 폴더)
- **Devin 소스 diff: 0** — Devin은 `IDEAS.md`/`THE_ONE.md` 두 문서만 생성했고 application/script/bat/테스트는 일절 미수정.
