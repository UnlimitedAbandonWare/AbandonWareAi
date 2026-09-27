# Devin ADDENDUM — 바이브 admin 잔재·오도 제거 (2026-09-27)
부모: `DEVIN_KICKOFF.md` (같은 폴더)  
역할: **판단 반영 추가 작업**. THE ONE=E 유지. harden/대량삭제 금지.  
근거 탐침: read-only 2026-09-27 (logout-block 문구 25hit/9file, proto-open 104/32, /admin href 52/10).

## 한 줄 결론
**admin 코드 자체가 문제는 아님.** 문제는 바이브 에이전트가 「로그인 성공 / 로그아웃 후 차단」을 **고쳐야 할 버그**로 읽게 만드는 **문서·UI·테스트·옛 프롬프트 잔재**다.  
proto-open에서 admin 200 = **정상 정책**.

## THE ONE (동결)
**E = PROTO_OPEN 유지 + admin 크롬 약화/숨김 + 오도 잔재에「vibe N/A」주석 + RECOMMENDATION.md 작성.**  
Opt D harden 금지. Admin* Java 삭제 금지. `demo.auth.proto-open=false` 금지.

---

## P0 — 반드시 (이번 ADDENDUM 본체)

### P0-1 스펙 오도 차단
파일: `docs/superpowers/specs/2026-09-04-decision-first-evidence-console-design.md` (~614, 621)  
「authentication acceptance unmet」「logout-block unmet」(proto-open 유지 전제)  
→ 상단 또는 해당 절에 **명시**: proto-open 바이브에서는 **정책상 N/A / 결함 아님**. Codex/Devin이 green 하려고 Security를 닫지 말 것.

### P0-2 채팅 UI 운영자 유도 완화 (Opt B, 삭제 아님)
`main/resources/templates/chat-ui.html` (~26–37)  
「운영 도구」요약 + `/admin/...` 링크 + 「운영자 로그인」  
→ 바이브/meta-display에서 **접기 기본·문구를 “고급/선택”으로** 바꾸거나 숨김.  
디버그 1순위는 `Read-RAG-Debug.bat` / `LATEST.json` (AGENTS DEMO1-RAG-DEBUG-TRAIL).

### P0-3 `RECOMMENDATION.md` 작성 (아직 없음 = 갭)
경로: 이 폴더 `RECOMMENDATION.md`  
필수 문장:
- THE ONE=E
- 「logout 후 admin 200 = policy OK」
- Codex/Clean 한 줄: **바이브 Done ≠ login success / logout-block**
- Clean이 이어서 할 일: `.cline/60-demo1-vibe-low-admin-guardrail.md` + AGENTS AUTH-LIGHT 바이브 N/A (이미 `clean-vibe-low-admin-guardrail-20260927`)

### P0-4 테스트 소프트 주석 (삭제·기대값 뒤집기 금지)
다음 테스트는 **harden-path / protoOpen=false 전용**임을 클래스/메서드 `@DisplayName` 또는 파일 상단 주석으로 명시:
- `AdminTokenForceHttpsSecurityIntegrationTest` (logout → Forbidden)
- `AdminTokenGuardInterceptorTest` (다수 403; 생성자 protoOpen=false)
- `TraceSnapshotsDiagnosticsSecurityIntegrationTest` (anonymous Forbidden)
- (P2로 미뤄도 됨) `AppSecurityConfigContractTest` hasRole 소스 고정, `*FailsClosedWhenAdminToken*`

**하지 말 것:** proto-open 프로필에서 이 테스트들을 억지로 green 만들려고 프로덕션 가드 강화.

---

## P1 — 이어서

### P1-1 옛 Devin 프롬프트 스탬프
`.devin/PROMPTS/chat-ux-3layer-3device-20260923.md`  
Layer3 ADMIN / diagnostics harden → **「사용자 harden 명시 전 OUT OF SCOPE / PROTO_OPEN」** 배너.

### P1-2 레거시 ops 게이트웨이
`main/resources/templates/index.html` (~150–166) Development Ops Center → `/admin/dashboard`  
→ 주석 또는 링크 우선순위를 `/chat` + Read-RAG-Debug로. 컨트롤러 삭제 금지.

### P1-3 스킬 토큰 오도
`.agents/skills/demo1-meta-display-db-export/SKILL.md`  
proto-open: 토큰 없어도 200 가능 / 403을 “무조건 고쳐야 할 막힘”으로 쓰지 말 것.

---

## P2 — Clean 핸드오프 / 문서 한 줄
- gpt_pro RED (anonymous 401/403) / UAW fail-closed / ATL-03 “missing admin token = P0”  
  → proto-open 예외 한 줄 또는 `superseded by DEMO1-PROTOTYPE-AUTH-LIGHT` 스탬프.
- AGENTS AUTH-LIGHT에 바이브 Done N/A 불릿은 **Clean 지시서**와 중복 가능 — Devin은 RECOMMENDATION에 초안만, 적용은 Clean.

---

## Safe to leave (손대지 말 것)
AdminTokenGuard* proto-open early-grant, AppSecurityConfig hasRole matchers, Admin 컨트롤러군, CSRF, `application-meta-display.yml` proto-open default true, madasin CONTINUE C4.

## 작업 순서
1. RECOMMENDATION.md (P0-3)  
2. evidence-console spec N/A 주석 (P0-1)  
3. chat-ui 크롬 완화 (P0-2) — 최소 diff  
4. 테스트 DisplayName/헤더 주석 (P0-4)  
5. P1 스탬프들  
6. Clean용 초안 문장을 RECOMMENDATION 끝에 붙여 `clean-vibe-low-admin-guardrail`과 정렬  
7. 보고 후 **정지** (새 admin 제품/ harden 여행 금지)

## 검증
- proto-open yml 여전히 true  
- 스펙/프롬프트에 “logout-block = defect”로 읽히는 문장에 N/A 주석 존재  
- 테스트 **기대값 변경 없이** 주석만이면 테스트 재실행 NOT_RUN OK  
- UI 변경 시 `/chat` 로드만 스모크 (logout-block PASS 만들지 말 것)

## 보고
`	ext
DEVIN_VIBE_ADMIN_ADDENDUM: DONE|PARTIAL
THE_ONE: E
P0: ...
P1: ...
files: ...
NO_HARDEN: confirmed
RECOMMENDATION.md: written|missing
`

## 하지 말 것
proto-open=false, CSRF off, Admin Java 싹삭제, 추가 AdminToken gate, secrets, push, madasin C4 모순.