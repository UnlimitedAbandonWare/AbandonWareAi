# Devin 지시서 — 디버깅 기능 안에 넣는 「원시적 Debug-AI」아이디어 탐색 (설계만, 소스 수정 금지)
날짜: 2026-09-26 KST  
수신: **Devin**  
역할: **아이디어 / 아키텍처 / 리스크 랭킹 / THE ONE 동결** (구현은 Clean)  
Project Root: `C:\AbandonWare\demo-1\demo-1\src`  
Prototype Light · secrets 출력·push/dd -A 금지 · AbandonWare3 폐기 · 단독 원격 AbandonWareAi

## 역할 분담 (필수)
| 주체 | 역할 | 산출 |
|---|---|---|
| **Devin (너)** | 원시 Debug-AI **아이디어 전수 탐색**, 옵션 3~5개 비교, THE ONE 추천·동결 | `IDEAS.md` + `THE_ONE.md` (이 폴더) |
| **Clean** | Devin이 동결한 THE ONE을 **최소 seam으로 구현** | 별도 SSOT `agent-prompts/clean-primitive-debug-ai-impl-20260926/` |
| Grok Bot | 증거 탐침 + 두 지시서 작성 | 본 문서 |

**소스 패치·bat 수정·테스트 추가 = Clean 전담.** Devin은 **읽기·설계·랭킹만**. Clean과 겹쳐 같은 파일을 동시에 쓰지 말 것.

---

## Self-Ask
1. **요청:** 디버깅 기능 자체에 「원시적인 AI」를 얹고 싶다. Devin은 아이디어, Clean은 지시(구현).
2. **현재 증거 (디스크 검증 2026-09-26):**
   - 스킬프리 루프: `Status-RAG` → `Verify-RAG` → 실패 시 `Read-RAG-Debug.bat` → `scripts/read_rag_debug_trail.ps1` → SSOT `var/rag-launcher/LATEST.json` (`start_rag_stack.ps1`이 씀) → `nextCommand`는 **인쇄만, 실행 안 함**.
   - 오늘 `nextCommand`는 exit-code만: 0/3 → `Debug-RAG.bat -Action status`, else → `verify`. **failurePoint/stage/excerpt 클래스 미반영.**
   - 이미 있는 「AI 섬」들: `tools/ai_debug_assist.py` (빌드로그 분류·가설만, 명령 미실행), `DebugCopilotService` / triadic (Spring, 기본 off), skills `demo1-evidence-debugging` / `demo1-debugging-with-two-tools` / `demo1-observed-debugging-meta`.
   - root debug 계열 bat ≈11, 관련 scripts ≈81, 관련 skills ≈21, rag-launcher run dirs ≈157.
3. **모호/창의:** 「원시적 AI」의 경계 — 로컬 룰 매트릭스 vs LLM vs Spring copilot.
4. **바꾸면 안 됨:** LATEST.json SSOT, nextCommand 자동실행 금지, triadic 기본 on 금지, 새 debug 제국, OSTP/Codex quarantine/loadout harmony와 충돌, secrets, PromptBuilder/LC4j 1.0.1 하드스톱.
5. **가장 작은 검증 seam (권장 THE ONE seed):** trail의 `nextCommand`를 failure-class 매트릭스로 키우고, 빌드 클래스일 때만 선택적으로 `ai_debug_assist.py` 브리지.

---

## 반드시 먼저 읽을 것 (읽기 전용)
1. `AGENTS.md` — `DEMO1-DEBUG-ENTRYPOINTS`, `DEMO1-RAG-DEBUG-TRAIL`
2. `Read-RAG-Debug.bat`, `scripts/read_rag_debug_trail.ps1` (특히 `nextCommand` 블록)
3. `scripts/start_rag_stack.ps1` — `Write-RagRunResult` / LATEST 포인터
4. `tools/ai_debug_assist.py`, `tools/build_error_miner.py` (스키마 `awx.ai-debug-observation.v1`)
5. skills: `demo1-evidence-debugging`, `demo1-debugging-with-two-tools`, `demo1-observed-debugging-meta`
6. (참고만) `DebugCopilotService.java`, triadic adjudicator — **기본 off, skill-free 루프 밖**
7. `docs/debug-ui/README.md` — LATEST 대체 아님
8. 병행 Clean: `agent-prompts/clean-agent-loadout-harmony-20260926/` — Read-RAG-Debug는 로드아웃 척추; Debug-AI는 **그 척추를 확장**

---

## 산출물 (Devin이 쓸 파일)
폴더: `agent-prompts/devin-primitive-debug-ai-ideas-20260926/`

### A) `IDEAS.md` — 옵션 3~5개 (각 옵션에 필수 섹션)
각 옵션마다:
- **한 줄 정의**
- **사용자가 느끼는 「원시 AI」감각** (무엇을 보고, 무엇을 제안하는지)
- **재사용 seam** (파일/bat/스크립트)
- **신규 파일** (있으면; 최소화)
- **데이터 흐름** (실패 → 증거 → 분류 → 제안 → (사람/에이전트가 실행))
- **장점 / 단점 / 실패모드**
- **Prototype Light 친화도** (1~5)
- **로드아웃 충돌 위험** (1~5, 5=나쁨)
- **자동실행 유혹** (어떻게 막을지)
- **검증 방법** (테스트/픽스처 LATEST)
- **예상 구현 크기** (S/M/L) — Clean 핸드오프용

### 반드시 비교할 옵션 후보 (이름 고정, 내용 보강·변형 OK)
1. **Trail nextCommand matrix (PS-only)**  
   `failurePoint`/`stage`/excerpt 클래스 → 랭크된 bat/script 힌트. LLM 없음.
2. **Trail + ai_debug_assist bridge**  
   Read-RAG-Debug에 선택 플래그(예: `-AiAssist`). 빌드/컴파일 excerpt가 miner 클래스일 때만 assist 호출. 가설은 인쇄만.
3. **Offline DebugCopilot twin**  
   Spring `DebugCopilotService` 휴리스틱을 Python으로 trail JSON에 적용. JVM 불필요.
4. **Thin `Debug-AI.bat` naming/loadout wrapper**  
   새 로직 최소; 기존 Read/Debug/assist를 하나의 퀵슬롯 이름으로. (로드아웃 문서·placement-scan과 정합)
5. **Spring triadic / metrics deepen**  
   **기본 기각 후보로 두고 왜 기각인지 근거** (live JVM, `DEBUG_COPILOT_TRIADIC_ENABLED` default false, skill-free 루프와 불일치). 살릴 거면 「언제만」조건을 명확히.

추가 옵션을 발명해도 됨. 단 **LATEST 대체·새 incident lifecycle·명령 자동실행**은 옵션으로도 올리지 말 것.

### B) `THE_ONE.md` — 동결
- 선택한 THE ONE 이름 + 한 문장
- 왜 다른 옵션을 이겼는지 (로드아웃/Prototype Light/재사용/리스크)
- Clean이 만져도 되는 파일 화이트리스트
- Clean이 만지면 안 되는 것 블랙리스트
- Done when (체크리스트)
- 검증 커맨드 초안
- Clean SSOT 경로 포인터: `agent-prompts/clean-primitive-debug-ai-impl-20260926/CLEAN_KICKOFF.md`

### C) 보고 형식 (채팅)
`	ext
DEVIN_DEBUG_AI_IDEAS: DONE|PARTIAL
THE_ONE: <name>
rejected: <short>
reuse: LATEST + read_rag_debug_trail + ...
Clean whitelist: ...
IDEAS.md / THE_ONE.md: paths
NO_SOURCE_EDITS: confirmed
`

---

## 깊이 요구 (「토큰 최대」의도)
- 표면적 bullet만 쓰지 말 것. 각 옵션에 **실제 디스크 경로·현재 nextCommand 한계·ai_debug_assist 스키마·AGENTS 문장**을 인용.
- 「원시적」을 정의: **관측 경계 안에서의 분류+순위 제안**, 자율 패치/재시작 아님.
- Failure-class taxonomy 초안을 IDEAS에 제안 (예: `launcher_not_running`, `port_conflict`, `compile_fail`, `verify_degraded`, `meta_display_wear`, `unknown`) — Clean이 매트릭스에 쓸 수 있게.
- Anti-patterns: Devin-first/exec 난사(9only), ForceRestart를 「증명」으로 쓰기, notepad를 제2 SSOT로 승격.

## 하지 말 것
- 소스/테스트/AGENTS 본문 수정 (아이디어 문서만)
- triadic 기본 켜기 제안만으로 THE ONE 만들기
- Codex home quarantine / OSTP / 9only ingest와 범위 섞기
- secrets 출력, push

## Done when (Devin)
- [ ] IDEAS.md (옵션 ≥3, 위 섹션 충족)
- [ ] THE_ONE.md 동결 + Clean 화이트리스트
- [ ] 채팅 보고 포맷
- [ ] **소스 diff 0**
---

## 부록 A — 현재 루프 (증거 인용, 설계 시 깨면 안 됨)

### A1. Skill-free 경로 (AGENTS)
`Status-RAG.bat` (alive-only) → 편집 후 `Verify-RAG.bat` → 실패 시 `Read-RAG-Debug.bat` → `var/rag-launcher/LATEST.json`.  
Read-only. nextCommand는 **힌트만**. 서버 start/stop 금지.

### A2. nextCommand 현행 (빈 seam)
`read_rag_debug_trail.ps1` roughly:
- exit 0 or 3 → `Debug-RAG.bat -Action status`
- default → `Debug-RAG.bat -Action verify`
→ **failurePoint/stage/excerpt를 안 봄.** 여기가 「원시 AI」를 꽂을 자리.

### A3. 이미 있는 AI 섬 (통합하지 말고 오케스트레이션)
| 섬 | 위치 | 하는 일 | 루프 위치 |
|---|---|---|---|
| ai_debug_assist | `tools/ai_debug_assist.py` | 빌드 에러 분류+가설, 명령 미실행 | 오프라인, 빌드 스코프 |
| build_error_miner | `tools/build_error_miner.py` | 로컬 분류기 | assist 하위 |
| DebugCopilot | Java `DebugCopilotService` | TraceStore→finding | 런타임 JVM |
| Triadic adjudicator | Java, default **disabled** | 수요형 심판 | 켜면 skill-free 깨짐 |
| DebugCasePacket | skill evidence-debugging | 증거 패킷 | 에이전트 수동 |
| two-tools | skill | observe+verify 슬롯 | 에이전트 수동 |
| debug-ui notepad | `docs/debug-ui` | 뷰어 | **제2 SSOT 아님** |

### A4. Failure-class taxonomy 초안 (Devin이 IDEAS에서 다듬을 것)
| class | 신호 예 | 1순위 nextCommand 예 |
|---|---|---|
| ready | LATEST status ready, exit 0 | Debug-RAG status (현행) |
| not_running | no LATEST / exit 3 | Status-RAG / Debug-RAG status |
| degraded_verify | exit 4, verify artifacts fail | Debug-RAG verify → tail |
| compile_or_build | excerpt gradle/javac | Verify-RAG + optional AiAssist |
| launcher_stage_fail | failurePoint/stage in LATEST | re-read trail + evidencePaths |
| meta_wear | wear/Meta Display role | Debug-Meta-Display / Verify-Meta-Display |
| unknown | else | Debug-RAG status (safe default) |

### A5. 「원시적 AI」정의 (이 지시서의 의미)
- **원시적** = 관측 경계(LATEST+evidence excerpts+선택적 빌드로그) 안에서 **규칙/가벼운 분류기**로 클래스 붙이고 **다음 도구를 순위 제안**.
- **AI** = LLM 필수 아님. 로컬 miner/매트릭스가 본체; LLM/MCP는 assist 옵션.
- **디버깅 기능 자체** = skill-free bat 경로에 붙음. 별도 「Debug Agent 제품」이 아님.
- **금지** = 제안 명령 자동 실행, ForceRestart로 green 만들기, Spring 기본 on.

---

## 부록 B — 옵션 비교 채점표 (IDEAS.md에 채워 넣기)

| 기준 (가중) | Opt1 matrix | Opt2 +assist | Opt3 copilot-twin | Opt4 thin bat | Opt5 Spring |
|---|---|---|---|---|---|
| LATEST 재사용 | | | | | |
| skill-free 유지 | | | | | |
| 구현 크기 (낮을수록↑) | | | | | |
| 로드아웃 정합 | | | | | |
| 자동실행 유혹 낮음 | | | | | |
| Prototype Light | | | | | |
| **합계** | | | | | |

권장 시드 승자: **Opt1 필수 + Opt2 옵션 플래그** (Opt4는 이름만 로드아웃에 추가 가능). Opt5는 기본 기각.

---

## 부록 C — Clean 핸드오프 계약
THE_ONE.md에 반드시:
1. whitelist paths (글롭 OK)
2. blacklist (Java triadic flip, new SSOT, quarantine, OSTP)
3. public CLI/flags 이름
4. fixture 시나리오 4개 이상 설명
5. 「Devin 소스 diff = 0」확인 문장