<!-- BEGIN DEMO1-AGENT-ADMIN-AUTO -->
## Agent admin-auto access (사용자 로그인 요청 금지)
- 사용자는 관리자 로그인·아이디·비밀번호를 제공하지 않는다. 관리자 권한이 필요한 검증에서 사용자에게 로그인·계정·비밀번호·"어느 방식으로 진행할까요" 류의 카드를 띄우지 않는다. (2026-10-03 사용자 결정 — 에이전트의 자동 관리자 접속은 이미 승인된 기본 동작)
- 관리자 권한이 필요하면 순서대로 진행한다:
  1. `scripts/agent_admin_session.py`가 있고 서버가 자동 세션을 발급하면(`POST /api/agent-auth/admin-session`, 기본값 꺼짐 `demo.agent.admin-auto.enabled` / env `AWX_AGENT_ADMIN_AUTO`) 그 세션으로 접속·검증한다.
  2. 아직 없거나 꺼져 있으면 PROTO_OPEN 관찰로 진행하고 "관리자 로그인 검증 = 관찰(observe)"로 기록한다 — proto-open(`demo.auth.proto-open`, `application-meta-display.yml` 기본 true)에서는 `AdminTokenGuardFilter`가 요청에 `ROLE_ADMIN`을 부여한다.
  3. 둘 다 안 되면 HOLD — 재개 조건: agent-admin-autologin 패치 반영 또는 `AWX_AGENT_ADMIN_AUTO=true`. 어떤 경우에도 사용자 카드 금지.
- 판정 도구: `python -B scripts/agent_admin_access_gate.py` → JSON `mode`(auto_session | proto_open_observe | hold_codex_patch_pending) + `next` 한 줄. `ask_user`는 항상 false, 종료코드 항상 0 — 질문으로 새지 않는다.
- 자동 세션은 로컬(127.0.0.1, 터널·Forwarded 헤더 없음) 전용이다. 공개 주소·prod에서 쓰려는 시도 금지. nonce·쿠키·비밀번호는 출력·커밋 금지(`var\agent-admin\` 아래에만).
- 관리자 "로그인 성공 / 잘못된 계정 차단 / 로그아웃 후 차단" 검사는 완료 조건이 아니다(분류기 D18, AGENTS.md DEMO1-CORE-AUTO와 같은 뜻).
- 관리자 범위를 넓히거나 PROTO_OPEN을 바꾸는 것은 여전히 ASK_ONCE다 — 이 룰은 "검증을 위한 접속"만 AUTO로 만든다. 스킬: `$demo1-agent-admin-auto-session`.
<!-- END DEMO1-AGENT-ADMIN-AUTO -->
