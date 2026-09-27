# Clean(Cline) 지시서 — 원시 Debug-AI THE ONE 최소 구현
날짜: 2026-09-26 KST  
수신: **Clean (Cline)**  
역할: **구현·테스트·AGENTS 한 블록** (아이디어 탐색은 Devin)  
Project Root: `C:\AbandonWare\demo-1\demo-1\src`  
Prototype Light · secrets 미출력 · push/dd -A 금지

## 역할 분담
| 주체 | 역할 |
|---|---|
| **Devin** | `agent-prompts/devin-primitive-debug-ai-ideas-20260926/THE_ONE.md` 동결 |
| **Clean (너)** | 동결된 THE ONE을 **최소 diff**로 구현·검증 |
| 병행 | `clean-agent-loadout-harmony-20260926` — LOADOUT/placement-scan. Debug-AI는 **Read-RAG-Debug 척추 확장**. 로드아웃이 먼저면 그 문서에 Debug-AI 한 줄만 추가 |

### THE ONE이 아직 없을 때 (기본 시드 — Devin이 바꾸면 THE_ONE.md 우선)
**Trail nextCommand matrix (+ 선택적 ai_debug_assist bridge)**  
- `scripts/read_rag_debug_trail.ps1`에 failure-class → 랭크 `nextCommands[]` (또는 기존 `nextCommand` + `nextCommandAlts`)  
- 빌드/컴파일 excerpt가 miner 클래스일 때만 선택적으로 `tools/ai_debug_assist.py` 호출 (플래그 off가 기본이거나 `-AiAssist`)  
- **절대 자동실행 없음** · LATEST.json SSOT 유지 · Spring triadic 켜지 않음 · 새 Java 패키지 금지

---

## Self-Ask
1. **요청:** 디버그 루프에 원시 AI(분류+제안).
2. **증거:** 현재 nextCommand는 exit 0/3→status, else→verify. assist는 빌드로그용으로 이미 존재.
3. **모호:** Devin THE_ONE vs 기본 시드 — **THE_ONE.md 있으면 그것**, 없으면 시드.
4. **금지:** 서버 start/stop을 「증명」으로 쓰기, ForceRestart, secrets, 로드아웃과 평행 엔트리, OSTP/quarantine.
5. **seam:** `read_rag_debug_trail.ps1` + 계약 테스트 + (필요 시) bat 도움말/AGENTS 한 절.

---

## 구현 순서

### 0) 게이트
1. `Get-Content ...\devin-primitive-debug-ai-ideas-20260926\THE_ONE.md -ErrorAction SilentlyContinue`  
   - 있으면 화이트리스트만 수정.  
   - 없으면 본 킥오프 시드로 진행하고 EVIDENCE에 `THE_ONE_FALLBACK=seed` 기록.
2. 로드아웃 폴더 존재 시 `LOADOUT.md` / placement-scan과 **충돌 없는지** 확인 (Read-RAG-Debug 유지).

### 1) Failure-class taxonomy (코드 상수/주석으로 고정)
최소 세트 (이름 조정 OK, 의미 유지):
- `ready` / `not_running` / `degraded_verify` / `compile_or_build` / `launcher_stage_fail` / `meta_wear` / `unknown`

매핑 입력: LATEST `status`, `failurePoint`, `stage`, exit code, evidence excerpt 키워드(기존 필드 우선, 신규 필드는 최소).

### 2) `read_rag_debug_trail.ps1` 보강
- 리포트에 `failureClass` 추가
- `nextCommand`를 클래스별 매트릭스로 (인쇄만)
- 권장 매트릭스 시드:
  - `not_running` → `Status-RAG.bat` 또는 `Debug-RAG.bat -Action status`
  - `degraded_verify` → `Debug-RAG.bat -Action verify` 그다음 `-Action tail`
  - `compile_or_build` → `Verify-RAG.bat` / assist 가설 섹션 (실행 아님)
  - `launcher_stage_fail` → `Read-RAG-Debug` 재실행 + evidencePaths 수동 확인 안내
  - `meta_wear` → `Debug-Meta-Display.bat` / `Verify-Meta-Display.bat`
  - `ready` → `Debug-RAG.bat -Action status` (현행 유지)
- 선택: `-AiAssist` 스위치 → `compile_or_build`일 때만 `python -B tools/ai_debug_assist.py ...` (stdin/파일은 기존 계약 따름). 실패해도 trail 본체 exit는 유지(fail-soft).
- **서버를 절대 시작/중지하지 않음** (현행 계약).

### 3) 테스트
- 픽스처 LATEST JSON (ready / not_running / degraded / compile excerpt) → `failureClass` + `nextCommand` assert
- 기존 `scripts/test_*rag*debug*` / `debug_ai_*_contract_tests.ps1` / `test_ai_debug_assist.py`가 있으면 **확장**; 없으면 최소 Pester 또는 unittest 1파일
- assist는 여전히 **명령 미실행** 계약 유지

### 4) AGENTS / bat / loadout
- `AGENTS.md` `DEMO1-RAG-DEBUG-TRAIL`에 한 단락: failureClass + nextCommand 매트릭스 + (옵션) AiAssist
- `Read-RAG-Debug.bat` 주석에 새 플래그 안내(있을 때)
- 로드아웃 작업과 겹치면 `LOADOUT.md` Quickslots에 「Debug-AI = Read-RAG-Debug(+AiAssist)」한 줄

### 5) 검증 커맨드
`powershell
cd C:\AbandonWare\demo-1\demo-1\src
powershell -NoProfile -File .\scripts\read_rag_debug_trail.ps1
# 픽스처/유닛 테스트 (실제 파일명으로 교체)
# python -B -m unittest ...  또는  existing contract ps1
`
Status/Verify/Start-RAG를 「구현 증명」으로 돌리지 말 것. LATEST 읽기 + 픽스처면 충분.

---

## Done when
- [ ] failureClass + nextCommand 매트릭스 동작 (인쇄만)
- [ ] 계약 테스트 PASS (또는 NOT_RUN 사유)
- [ ] AiAssist는 옵션·fail-soft·미실행
- [ ] AGENTS 한 절 갱신
- [ ] Spring triadic/새 Java/LATEST 대체 없음
- [ ] 로드아웃/OSTP/quarantine 비충돌
- [ ] secrets 미출력

## 보고 형식
`	ext
CLEAN_DEBUG_AI: DONE|PARTIAL
THE_ONE_SOURCE: Devin|seed
failureClasses: ...
files: ...
tests: PASS|NOT_RUN
AiAssist: on-flag|off
`

## 참조
- Devin: `agent-prompts/devin-primitive-debug-ai-ideas-20260926/`
- `tools/ai_debug_assist.py`, `demo1-observed-debugging-meta`
- `agent-prompts/clean-agent-loadout-harmony-20260926/`
---

## 부록 D — 구현 디테일 (시드 THE ONE)

### D1. 리포트 JSON/콘솔에 넣을 필드 (예시)
`	ext
failureClass: compile_or_build
nextCommand: Verify-RAG.bat
nextCommandAlts:
  - Debug-RAG.bat -Action verify
  - (optional) python -B tools/ai_debug_assist.py ...
aiAssist: skipped|ran|failed-soft
rationale: one line, no secrets
`

### D2. 매트릭스 의사코드
`
class = Classify(LATEST, exitCode, excerpts)
primary, alts = Matrix[class]
if -AiAssist and class == compile_or_build:
  try assist; attach hypotheses as text; never exec
print primary (+ alts); exit = trail exit (unchanged semantics)
`

### D3. 회귀 금지
- exit code 의미 (0 ready, 3 not-running/no-LATEST, 4 degraded, 1 tool error) **유지**
- LATEST 포인터 형식 깨지 않기 (writer는 start_rag_stack — Clean이 writer를 건드릴 거면 THE_ONE에 명시된 경우만, 기본은 **읽기 쪽만**)
- `debug_rag_stack.ps1 -Action trail`이 read trail을 부르면 새 필드가 그대로 흘러가게

### D4. 테스트 픽스처 위치 제안
`scripts/fixtures/rag-debug-trail/` 또는 기존 테스트 디렉터리 관례를 따를 것.
시나리오: ready.json, not_running(missing), degraded.json, compile_excerpt.json

### D5. 로드아웃 한 줄 (병행 작업 시)
Quickslots: `Read-RAG-Debug.bat` = Debug-AI 엔트리 (failureClass + nextCommand).  
Anti-combo: RAG 장애인데 Invoke-Devin/goal_next부터 금지 (9only 교훈).

### D6. 충돌 시 우선순위
1. Devin THE_ONE.md  
2. 본 CLEAN_KICKOFF 시드  
3. loadout harmony (척추 유지)  
4. 임의 새 엔트리 금지