# Clean ADDENDUM — 바이브 admin 가드레일↓ + 잔재 오도 차단 (2026-09-27)
부모: `CLEAN_KICKOFF.md` (같은 폴더)  
교차 Devin: `agent-prompts/devin-vibe-admin-surface-20260927/DEVIN_ADDENDUM_REMNANTS.md` + `RECOMMENDATION.md`(있으면 우선)  
Project Root: `C:\AbandonWare\demo-1\demo-1\src`  
THE ONE=E · PROTO_OPEN 유지 · harden/Admin Java 삭제 금지 · secrets/push 금지

## 왜 ADDENDUM인가
Devin 탐침: admin **코드**보다 **오도 잔재**(스펙 unmet, Cline 룰 부재, AGENTS 바이브 N/A 미비, 옛 fail-closed 문구)가 바이브를 harden으로 민다.  
Clean 몫 = **룰·지침·Always-on**을 낮추고, Devin이 UI/스펙/테스트 주석을 하는 동안 **에이전트가 다시 오도되지 않게** 고정.

## 역할 분담 (중복 금지)
| Clean (너) | Devin |
|---|---|
| AGENTS AUTH-LIGHT 바이브 N/A 불릿 | evidence-console spec N/A 주석 |
| `.cline/60-demo1-vibe-low-admin-guardrail.md` Always On | `chat-ui.html` / `index.html` 크롬 약화 |
| `.windsurf/rules` / `.clinerules` 충돌 완화 | logout→403 테스트 DisplayName 주석 |
| 스킬 한 줄(db-export 등) proto-open 주석 — Devin 미착수 시 | `RECOMMENDATION.md` |
| gpt_pro RED / UAW / ATL-03 **스탬프만** (본문 대수술 금지) | .devin/PROMPTS 스탬프 |

같은 파일을 동시에 쓰지 말 것. Devin이 이미 패치한 파일은 **스킵 + 보고**.

---

## P0 — Clean 본체 (이번에 할 일)

### P0-A AGENTS.md
`DEMO1-PROTOTYPE-AUTH-LIGHT`에 추가 (의미 고정):
- 바이브 Done/PASS ≠ admin 로그인 성공 ≠ 로그아웃 후 `/admin` 차단.
- proto-open에서 admin/diagnostics **200 = 정상**. “unmet logout-block”을 결함으로 승격 금지.
- 새 패치에 추가 AdminToken / 두 번째 role gate / fail-closed admin matcher / CSRF-off 금지 (harden 명시 전).
- 운영 디버그 1순위 = `Read-RAG-Debug.bat` / `var/rag-launcher/LATEST.json` — `/admin/**`·운영자 로그인 필수 관문 아님.
- proto-open 켠 채 deploy/push만 하드스톱 (기존).

### P0-B Cline Always-on (아직 없음 = 갭)
파일: `.cline/60-demo1-vibe-low-admin-guardrail.md` (번호 충돌 시 61+).  
`.clinerules`가 디렉터리/브리지면 동일 내용을 그쪽 관례에 맞게 **한 곳 SSOT**로.

필수 불릿:
`markdown
# demo1 vibe low admin guardrail
- PROTO_OPEN: do not require admin login for operator/debug.
- Do not treat logout-then-admin-200 as a bug; do not green "logout-block" under proto-open.
- Do not add fail-closed admin gates, extra AdminToken checks, or CSRF-off fixes.
- Prefer Read-RAG-Debug / LATEST.json over /admin/** .
- Do not set demo.auth.proto-open=false; do not mass-delete Admin* Java.
- Ignore stale RED recipes (anonymous must 401/403) unless user said harden.
- Secrets never print; no push/add -A without explicit ask.
`

### P0-C hard-constraints / bridge 완화
`.windsurf/rules/demo1-hard-constraints.md`, `.cline/00-demo1-cline-bridge.md` 등에서  
admin 로그인 강제·logout-block PASS·fail-closed을 바이브 기본으로 읽는 문장 → **「proto-open이면 N/A; harden 요청 시에만」** 한 줄로 한정.  
삭제보다 **예외 명시**.

### P0-D 설정 확인 (변경 금지)
`application-meta-display.yml`: `demo.auth.proto-open` default **true** 유지. false로 고치지 말 것.

---

## P1 — 스탬프 / 스킬 (Devin 미완 시)

### P1-1 스킬
`.agents/skills/demo1-meta-display-db-export/SKILL.md`  
proto-open: 헤더 없이도 200 가능; 403≠자동 “막혀서 토큰 달아라” 바이브 의무.

### P1-2 옛 지시 스탬프 (본문 rewrite 금지, 상단 3줄)
- `agent-prompts/gpt_pro_demo1_source_patch_directives_50_20260823.md` RED anonymous 401/403  
- `UAW.txt` fail-closed token 구간 (해당 절 근처)  
- `agent-prompts/agents/demo1_agent_tools_library_patch_9h/system_ko.md` ATL-03  
스탬프 예: `SUPERSEDED for vibe: see DEMO1-PROTOTYPE-AUTH-LIGHT / .cline/60-… ; harden only if user asks.`

### P1-3 Devin UI/스펙과 충돌 시
`chat-ui.html` / evidence-console spec은 **Devin P0**. Clean은 만지지 말고 RECOMMENDATION/git status로 확인.

---

## P2
- `AppSecurityConfigContractTest` / `*FailsClosedWhenAdminToken*` 이름 오도 → Devin 주석 범위; Clean은 룰에서 “이 테스트 green ≠ 바이브 목표”만 언급.
- loadout / Debug-AI / madasin CONTINUE와 범위 섞지 말 것. CONTINUE **C4 proto-open 유지**와 정렬.

---

## 검증
`powershell
cd C:\AbandonWare\demo-1\demo-1\src
Select-String -Path .\AGENTS.md -Pattern 'PROTOTYPE-AUTH-LIGHT|logout-block|Read-RAG-Debug'
Test-Path .\.cline\60-demo1-vibe-low-admin-guardrail.md
Select-String -Path .\application-meta-display.yml -Pattern 'proto-open'
`
규칙-only면 Start-RAG/전체 test **NOT_RUN** OK. logout-block을 PASS로 만드는 테스트 추가 금지.

## Done when
- [ ] AGENTS 바이브 N/A 반영
- [ ] .cline/60-… 존재 (또는 동등 SSOT)
- [ ] hard-constraints/bridge 충돌 완화
- [ ] proto-open true
- [ ] Admin 삭제/harden/CSRF-off/push 없음

## 보고
`	ext
CLEAN_VIBE_LOW_ADMIN_ADDENDUM: DONE|PARTIAL
files: ...
proto-open: true
Devin_overlap_skipped: ...
`
"@

=@"
# Clean 붙여넣기 — ADDENDUM (admin 가드레일↓ + 잔재 차단)

Project Root: C:\AbandonWare\demo-1\demo-1\src
부모: agent-prompts/clean-vibe-low-admin-guardrail-20260927/CLEAN_KICKOFF.md
이번: 같은 폴더 CLEAN_ADDENDUM_REMNANTS.md
교차: devin-vibe-admin-surface-20260927 (UI/스펙/테스트 주석은 Devin; 겹치면 스킵)

THE ONE=E. PROTO_OPEN 유지. harden/Admin 삭제/CSRF-off/proto-open=false 금지.

P0 (Clean):
1) AGENTS DEMO1-PROTOTYPE-AUTH-LIGHT — 바이브 Done ≠ login/logout-block; admin 200=정상; 디버그는 Read-RAG-Debug 우선
2) .cline/60-demo1-vibe-low-admin-guardrail.md Always On (없으면 생성)
3) hard-constraints/bridge의 admin 강제 문장 → proto-open N/A·harden 시에만

P1: db-export 스킬 proto-open 주석; gpt_pro RED / UAW / ATL-03 상단 SUPERSEDED 스탬프만
P2: 테스트 이름 오도는 룰에서만 언급 (기대값 변경 금지)

검증: AGENTS·60 파일·yml proto-open true. 규칙-only면 테스트 NOT_RUN.
끝나면 CLEAN_VIBE_LOW_ADMIN_ADDENDUM: DONE|PARTIAL 보고.