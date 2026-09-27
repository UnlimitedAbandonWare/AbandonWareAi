# Devin 붙여넣기 — mxasain 트레이스 A단계 Codex assist-only (소스 수정 금지)

작성: 2026-09-27 Asia/Seoul · Grok Bot

## 0. 역할 분리 (절대)

| 역할 | 담당 | 금지 |
|---|---|---|
| **Codex** | 제품 소스 수정 (WP1 SafeRedactor/TraceHtmlBuilder, WP2 chat-trace-ui.js 등) | — |
| **Devin (너)** | 도구·셋업·룰/지침·스크립트·검증 레일·막힘 해제·핸드오프 메모 | **`main/java` / `main/resources/static` / 제품 YAML·SecurityConfig·Admin UI 제품 코드 수정 금지** |

Codex가 잘 끝나게 **옆에서 레일을 깐다.** Codex 패치를 대신 쓰거나, “내가 고칠게”로 소스를 가져가지 않는다.

## 1. Project / SSOT

- **Project Root:** `C:\AbandonWare\demo-1\demo-1\src`
- **Codex 작업 SSOT (읽기 + 공유):** `agent-prompts/codex-mxasain-trace-porting-20260927/`
  - `CODEX_START_HERE.txt` / `PASTE_TO_CODEX.md` / `LIVE_VERIFY.md` / `evidence/`
- **이 지시서 SSOT:** `agent-prompts/devin-mxasain-trace-porting-assist-20260927/`
- **Remote:** `https://github.com/UnlimitedAbandonWare/AbandonWareAi` only. AbandonWare3 discarded.
- **Git:** `scripts/conditional_local_git.py` soft-auto / selective. Preferred `F:\git\cmd\git.exe`. push / `add -A` / history rewrite 금지 until user asks. foreign staging 보존.

## 2. Codex가 하는 일 (참고만 — 네가 구현하지 않음)

목표: 구형 ZIP 통이식 ❌. 현재 트레이스가 **정제→저장→조회→화면**에서 소실되지 않게.

이번 Codex 세션 **A만:** WP0 → WP1 → WP2. WP3+ 금지 until user.

| WP | Codex 목표 | LIVE 힌트 |
|---|---|---|
| WP0 | `docs/diagnostics/trace-porting-baseline.md` (소스 변경 없음) | E01–E18 → still_present / already_fixed / moved / not_reproduced |
| WP1 | typed 진단값 보존 (exact key allowlist) | F01: `SafeRedactor` `contains("prompt"|"token")`, `TraceHtmlBuilder.sanitizeMeta` |
| WP2 | 중첩 표 뒤 그룹 삭제 수정 | F02: `chat-trace-ui.js:101-102` `querySelectorAll("tr").slice(100)` |

완료 보고 형식 (Codex): `CODEX_TRACE_PORTING_A: DONE|PARTIAL` + baseline + NOT_RUN.

## 3. 네가 할 일 (assist 체크리스트)

### P0 — Codex 시작 전 레일 (소스 수정 없이)

1. **SSOT 가시성:** Codex 세션이 `agent-prompts/codex-mxasain-trace-porting-20260927/PASTE_TO_CODEX.md`를 찾을 수 있는지 확인. 없으면 Downloads 사본/경로를 메모로 알려주고, 제품 트리에 **지시서만** 복사하는 건 OK (제품 코드 X).
2. **Build root / wrapper / JDK:** LIVE에서 Gradle wrapper·테스트 태스크 이름 확인 → `docs/diagnostics/` 또는 이 폴더 `ASSIST_NOTES.md`에 한 줄로 적어 Codex가 WP0에 쓰게 한다. ZIP에 없다고 LIVE에 없다고 단정 금지.
3. **Spend guard:** `AWX_AGENT_SPEND_GUARD` / host mode — 유료 fanout 끄기. 검증은 local/min-token. `AWX_AGENT_ALLOW_PAID_MODELS=1` 없으면 유료 모델 호출 유도 금지.
4. **proto-open / admin:** Codex 완료 조건에 admin login·logout-block·`proto-open=false`를 **넣지 않게** AGENTS/스킬/룰에서 오도 문장이 있으면 **규칙·지침·에이전트 프롬프트만** 완화 (제품 SecurityConfig 손대지 말 것). 기존 `clean-vibe-agent-auth-relax` / `devin-vibe-admin-surface`와 충돌 시 그쪽 SSOT를 포인터로만 연결.
5. **Git lease:** Codex가 WP1/WP2만 selective commit 후보로 잡게, stale `index.lock` soft-auto, foreign staging(SelfAsk 등) exclude. `conditional_local_git` / vibe-git 룰이 있으면 그 경로로 안내.

### P1 — Codex가 막힐 때 (여전히 소스 수정 금지)

| 막힘 | Devin 대응 |
|---|---|
| 테스트 클래스 경로/이름 모름 | LIVE test tree에서 기존 `*Trace*` / `*SafeRedactor*` / JS test runner 유무 조사 → 명령 한 줄 제안. **테스트 기대값 완화·skip 금지 유도.** |
| JDK 컴파일만으로 F01 재현하고 싶음 | evidence/probes 또는 최소 격리 스크립트를 **tools/ 또는 agent-prompts 아래**에 두고 Codex가 돌리게. 제품 `main`에 probe 심지 말 것. |
| DOMPurify / node --check | syntax check vs DOM 재현 구분 문서화. Chromium fixture는 assist 노트에 경로만. |
| Verify-RAG / Start-RAG / Read-RAG-Debug | 기존 bat·스킬 경로만 안내. 새 엔진·새 empire 금지. |
| 줄번호 drift | ZIP 줄번호 버리고 메서드명·해시 대조하라고 리마인드. `LIVE_VERIFY.md` 갱신은 **노트 파일만** (Codex baseline과 중복이면 포인터). |

### P2 — 규칙/지침 (제품 코드 아님)

허용 터치 (에이전트 레일만):
- `AGENTS.md`에 **짧은 포인터 블록** (선택): “trace porting A = Codex; Devin assist-only; WP3+ until user”
- `.agents/skills/` 또는 `.windsurf/rules` / Devin rules에 **assist 스킬 1개** (선택, 최소)
- `agent-prompts/devin-mxasain-trace-porting-assist-20260927/ASSIST_NOTES.md` (권장 SSOT 메모)
- `docs/diagnostics/`에 **Codex가 쓴 baseline을 덮지 않는** assist 부록만 (이름: `trace-porting-assist-rails.md`)

금지 터치:
- `SafeRedactor.java`, `TraceHtmlBuilder.java`, `chat-trace-ui.js`, Snapshot/SSE/Controller 제품 구현
- SecurityConfig harden, CSRF-off, Admin 삭제, secrets 출력, push

## 4. 보존 / 교차 (Codex와 동일 hard stop)

- 구형 통복사 ❌ · 최신 전역 snapshot 대체 ❌ · secret/raw prompt 허용 ❌
- 로그 보려 LLM/검색 재실행 ❌ · 무제한 log tail ❌ · 새 RAG 엔진 ❌
- DOMPurify off / secret mask off 로 “고친 척” ❌
- madasin CONTINUE admin PASS 기준 혼입 ❌

## 5. 산출·보고

작업 끝나면:

```text
DEVIN_TRACE_PORTING_ASSIST: DONE|PARTIAL
helped: (tools/rules/notes 경로)
codex_unblocked: (무엇을 풀어줬는지)
product_source_edits: NONE
NOT_RUN: ...
next: Codex WP0–WP2 진행 중이면 대기 / 막힘 있으면 한 줄 재핸드오프
```

제품 diff가 하나라도 있으면 **실수**다. revert하거나 Codex에게 넘기고 보고에 명시.

## 6. Stop rule

- Codex A가 DONE이면 축하 메모 + WP3 제안만 (구현 시작 금지 until user).
- Codex PARTIAL이면 막힌 WP만 assist 재시도. 범위 확장으로 “대신 구현” 금지.
- 동일 가설 무한 스캔·새 대시보드·agent framework 추가 금지.
