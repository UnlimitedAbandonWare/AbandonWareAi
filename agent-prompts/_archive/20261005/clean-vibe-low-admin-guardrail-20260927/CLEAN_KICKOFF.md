# Clean(Cline) 지시서 — 바이브용 admin/지침/룰 가드레일 낮추기
날짜: 2026-09-27 KST  
수신: **Clean (Cline)**  
역할: **규칙·지침·스킬 포인터를 Prototype Light에 맞게 낮추기** (SecurityConfig harden / Admin Java 대량 삭제 금지)  
Project Root: `C:\AbandonWare\demo-1\demo-1\src`  
교차: Devin `agent-prompts/devin-vibe-admin-surface-20260927/` (권고 THE ONE이 있으면 따름; 없으면 시드 E)

## 사용자 의도
admin 같은 가드레일이 바이브 코딩을 막는다 → **지침/룰을 낮춰라**.  
「없는 게 낫다」= 바이브에서 admin 인증·logout-block·추가 role gate를 **성공조건/강제 규칙으로 두지 말 것**.  
「자유롭게」= 기존 **PROTO_OPEN** 유지·강화.

## Self-Ask
1. **요청:** Clean이 Cline/룰/AGENTS 쪽 가드레일을 낮춘다.
2. **증거:** AGENTS `DEMO1-PROTOTYPE-AUTH-LIGHT` — `demo.auth.proto-open`; proto-open 시 AdminTokenGuard가 `ROLE_ADMIN` 부여; 추가 gate로 데모 재차단 금지; CSRF 끄기 금지; proto-open 배포/push 하드스톱. Cline 규칙 트리: `.cline\00-demo1-cline-bridge.md` … `50-*.md`, `.clinerules`, `.windsurf\rules\` (hard-constraints 등).
3. **모호:** Devin RECOMMENDATION 유무 — 있으면 THE ONE, 없으면 **시드 E**.
4. **금지:** `proto-open=false`, fail-closed admin, CSRF off, Admin* 대량 삭제, secrets, push, madasin logout-block PASS 복원.
5. **seam:** AGENTS 보강 블록 + Cline Always-on 짧은 룰 1장 + (선택) windsurf/hard-constraints에서 admin-auth 강제 문장 완화. UI 숨김은 Devin이 B/E를 택하고 화이트리스트 줄 때만.

---

## 시드 THE ONE (Devin 없을 때) = E
**PROTO_OPEN 유지 + 바이브 규칙에서 admin 인증 가드레일 제거/완화 + (선택) admin 내비 숨김은 후순위.**  
코드 제국 삭제 아님. harden 아님.

---

## 작업 순서

### 0) 게이트
1. `devin-vibe-admin-surface-20260927/RECOMMENDATION.md` 있으면 THE ONE 준수.
2. `git status` — foreign/dirty staging 보존. commit/push 금지.
3. `application-meta-display.yml`(또는 실제 설정)에서 `demo.auth.proto-open` / `DEMO_AUTH_PROTO_OPEN` **true 유지** 확인. false로 바꾸지 말 것.

### 1) AGENTS.md — 가드레일↓ 문장 (최소 diff)
`DEMO1-PROTOTYPE-AUTH-LIGHT` 블록 안에 바이브 조항 추가 (없으면 BEGIN/END 유지하며 보강):

필수 취지 (표현은 Clean이 다듬되 의미 고정):
- 바이브/에이전트 검증에서 **admin 로그인 성공·로그아웃 후 차단**을 Done/PASS 조건으로 쓰지 말 것 (proto-open에서는 N/A).
- `.hasRole("ADMIN")` 실패를 “고쳐야 할 보안 버그”로 승격하지 말 것 — proto-open이면 가드가 열어둔 것이 정상.
- 새 패치에 **추가 AdminToken / 두 번째 role gate / fail-closed admin matcher**를 넣지 말 것 (사용자가 harden 명시 전).
- admin UI가 “없어도 되는” 바이브 DX: 진단은 `Read-RAG-Debug` / `var/rag-launcher/LATEST.json` / skill-free 경로 우선; `/admin/**`를 필수 관문으로 문서화하지 말 것.
- 공개 배포·push에 proto-open 켠 채 가는 것만 하드스톱 (기존 유지).

### 2) Cline 룰 — 새 Always-on 짧게 1장
경로 관례: `.cline\` 숫자 prefix 다음 빈 번호 또는 `.clinerules\`에 프로젝트 룰로 추가.  
제안 파일명: `.cline/60-demo1-vibe-low-admin-guardrail.md` (번호 충돌 시 조정).

내용 골격:
`markdown
# demo1 vibe low admin guardrail (Always On when editing demo-1)
- Auth posture: PROTO_OPEN. Do not require admin login for operator/debug work.
- Do not add fail-closed admin gates, extra AdminToken checks, or CSRF-off "fixes".
- Vibe Done criteria: never "logout then admin blocked". Report 200 after logout as expected under proto-open.
- Prefer Read-RAG-Debug / LATEST.json over /admin/** as the debug entry.
- Do not delete Admin* Java wholesale; do not set demo.auth.proto-open=false.
- Secrets never print; no push/add -A without explicit user ask.
`

기존 `00-demo1-cline-bridge.md` / `demo1-hard-constraints.md`에 **admin 로그인 강제·logout-block PASS** 문구가 있으면 **삭제 대신** “proto-open일 때 예외/N/A” 한 줄로 완화 (바이브 친화). 프로덕션 harden 문장은 “사용자 harden 요청 시에만”으로 한정.

### 3) .windsurf/rules (있으면)
`demo1-hard-constraints.md` 등에서 admin/auth를 바이브에 과도하게 조이는 문장만 동일 취지로 완화.  
Meta Display runtime 규칙은 **건드리지 않거나** auth와 무관하면 유지.

### 4) 스킬 포인터 (선택, 최소)
- `.agents/skills`에 짧은 `demo1-vibe-proto-open-auth/SKILL.md` **또는** 기존 observed-debugging / tool-placement 스킬 Related에 한 줄: “vibe: admin auth N/A under proto-open”.  
- 새 라우터 제국 금지.

### 5) (후순위) UI 크롬 숨김
Devin THE ONE이 B/E이고 화이트리스트를 준 경우에만.  
Clean 단독으로 admin 페이지/컨트롤러 삭제하지 말 것.

### 6) 검증
`powershell
cd C:\AbandonWare\demo-1\demo-1\src
# 설정 플래그 확인 (값은 true여야 함; 시크릿 아님)
Select-String -Path .\AGENTS.md -Pattern 'PROTOTYPE-AUTH-LIGHT|proto-open|logout'
# 새 Cline 룰 파일 존재
Test-Path .\.cline\60-demo1-vibe-low-admin-guardrail.md
`
- SecurityConfig/테스트로 “logout block green” 만들지 말 것.
- Start-RAG/ForceRestart는 이 작업에 **불필요** (문서/룰만이면).

---

## Done when
- [ ] AGENTS 바이브 admin N/A 조항 반영
- [ ] Cline Always-on 룰 1장 (또는 동등 완화)
- [ ] hard-constraints 충돌 문장 완화 (해당 시)
- [ ] proto-open **여전히 true**
- [ ] Admin Java 대량 삭제 없음 / harden 없음 / CSRF off 없음
- [ ] secrets 미출력 · push 안 함 · foreign staging 보존

## 보고 형식
`	ext
CLEAN_VIBE_LOW_ADMIN: DONE|PARTIAL
THE_ONE_SOURCE: Devin|seed-E
files: ...
proto-open: true (verified path)
tests: NOT_RUN (rules-only) | ...
`

## 하지 말 것
- madasin CONTINUE의 C4와 모순되게 proto-open 끄기
- “가드레일 낮춤”을 핑계로 secrets·push·원격 변경
- loadout / Debug-AI / quarantine 범위와 섞기