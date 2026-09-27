# Codex 새 세션 — API-01 CONTINUE (소스 수정 허용)

너는 Codex다. Project Root는 **반드시** `C:\AbandonWare\demo-1\demo-1\src`.
이전 세션은 API-01을 **HOLD/paused** 했다. 이 세션은 그 보류를 **풀어 최소 패치**한다.

## 먼저 읽어라 (실행 계약)
1. `docs/diagnostics/API01-main-selection-20260927.md` — 보류 근거·해시·미실행 목록
2. `agent-prompts/devin-groq-evidence-ttl-extend-20260927/PASTE_TO_DEVIN.md` — 증빙 TTL 정책
3. (참고만) Downloads의 DIRECTIVE_V2 — 범위는 API-01 한 단위. API-02/03·trace·ChangeIntent 신규 구현 금지

## 확정된 범위 (퀴즈 없이 진행)
- 주목표: **DIRECTIVE_V2 API-01** — 등록 API(`llmrouter.api3` / Groq free 경로) **메인 직접 선택**이 동작하게.
- **PROTO_OPEN 유지.** 관리자 로그인 필수 / fail-close / SecurityConfig harden **금지**.
- Browser “잘못된 계정·로그아웃 후 차단” 매트릭스는 **완료 조건에서 제외** → NOT_RUN.
- ChangeIntent control plane **새로 만들지 마** (이미 있음). lease 충돌 시 외래 강제 end 금지.
- 완료 ≠ 플러그인 많이 씀. 완료 = 증빙 TTL 정합 + api3 직접선택 검증(가능하면 live) + 회귀.

## 이전 HOLD가 말한 것 (재조사만 하고 같은 결론이면 바로 패치)
- Groq 증빙 24h 만료로 admission 실패 (키 존재 ≠ 준비완료) — **증빙 TTL ≠ API 키 만료**
- `app.ai.allow-remote-model-selection=false`, Groq manifest `enabled:false`
- 공통 remote ON 시 OpenAI economy 등 타 경로 노출 위험 → **한 경로만** 열지 검증 필수
- `:18180/chat` CONNECTION_REFUSED 였음 → Start-RAG/Status로 서버 상태 확인 후 Browser
- `ExactRequestedModelTest...` expected `model_unavailable` vs actual `backend_unavailable` — **기대값 완화로 통과 금지**. 별도 기록; API-01과 무관하면 이번 단위에서 고치지 말 것

## 작업 단위 (이 순서, 한 단위씩 체크포인트)

### U0 — Evidence TTL (필수, 먼저)
`GroqFreeTierGuard.java`의 `now-at>DAY` 하드코딩을
`groq.free-tier.evidence-max-age-ms` (**기본 90일 = 7776000000**)로 교체.
`application-meta-display.yml` (+ `.env.example` 주석) 추가.
`AGENTS.md` / `docs/provider-limits/groq-limits.md`의 “24-hour account evidence” 문구를 90일 정책에 맞게 수정.
`GroqFreeTierGuardTest` 갱신.
필요 시 `data/usage/groq-free-plan.json`의 `verifiedAtMs`/`expiresAtMs`만 max-age에 맞게 갱신 (**키·해시 전문 채팅 출력 금지**).
plan=free / keyHash / limits / ledger 검사 **유지**. 가드 삭제·항상 eligible 금지.

검증: `GroqFreeTierGuardTest` + status/eligible reasonCode만 보고.

### U1 — API-01 최소 활성화 (U0 통과 후)
목표: 승인한 api3/Groq free **직접 선택**만 가능하게.
- 설정으로 되면 Java/JS 최소화.
- 공통 `allow-remote-model-selection`을 켜기 **전후** 카탈로그 비교: 미승인 API가 같이 열리면 **롤백/더 좁은 스위치**.
- 실패 시 사용자 몰래 로컬·다른 API로 바꾸지 말 것 (이유 보존).
- 실제 provider 호출은 기존 키·예산 범위에서만; mock과 구분.

검증: 관련 카탈로그/strict/가드 테스트 + (서버 up이면) Browser에서 모델 선택·짧은 생성. admin 로그인 매트릭스 스킵.

### U2 — 한 단위 종료
통과·막힘·이미해결 중 하나로 보고 후 **사용자확인대기**. API-02/trace 자동 이어가기 금지. commit/push는 별도 승인 없으면 하지 마.

## 플러그인 역할 (Stuff2 — 제한 사용)
Superpowers: systematic-debugging, 원인 하나, 실패 테스트 먼저, verification-before-completion. 중복 계층 금지.
Browser: /chat·모델 선택·API-01 회귀만. HOLD 본문·backend_unavailable은 API-01 관련일 때만. **admin 잘못된 계정/로그아웃 차단 시나리오 스킵(NOT_RUN).** 쿠키/토큰 Git 금지.
GitHub: 로컬 status/HEAD 우선. RagControl/ChatWorkflow/SecurityConfig/AdminTokenGuard/chat.js는 **건드릴 일 없으면 diff 참고만**. 무단 commit/push/merge 금지.
Exa: Spring/Playwright/provider 공식 문서만. 블로그→Java 이식 금지.
AWX: compile/test/boot 실패 시 정제 로그만 build_error_mine. 복구 AWX 기본 금지.
Computer: localhost/콘솔 필요할 때만. 소스 탐색용 Computer 금지.
glm_worker: 패치 후 반박 검토만. 동의≠검증 성공.
사용 금지: Supabase, Data, Wolfram, SciSpace, Sites, Meta Wearables Webapp (범위 변경 시에만 재판단).

## Hard stops
Secrets 출력, `add -A`, force-push, AbandonWare3, PROTO_OPEN 끄기, 테스트 기대값 약화로 초록 만들기, 외래 lease force-end, 공유 build 통삭제.

## Done 형식
```
API01_CONTINUE: DONE|PARTIAL|HOLD
U0_TTL: ...
U1_selection: ...
tests: ...
live_api_called: yes|no
browser: ...
NOT_RUN: [admin auth matrix, ...]
files/diff: ...
rollback: ...
```