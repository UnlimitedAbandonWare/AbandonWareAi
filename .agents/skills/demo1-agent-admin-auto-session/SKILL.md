---
name: demo1-agent-admin-auto-session
description: >-
  Use when an agent needs admin access to verify something and is about to ask
  the user to log in — never ask; run the access gate and proceed with the auto
  admin session, else PROTO_OPEN observe, else HOLD.
---

# demo1-agent-admin-auto-session

SSOT: `docs/agents-rules/DEMO1-AGENT-ADMIN-AUTO.md` (규칙 본문 — 여기서는 실행 절차만).

## 언제

"관리자 로그인 계정이 없습니다 / 어느 방식으로 검증할까요?" 같은 카드를 띄우기
직전. 그 카드 자체가 금지 대상이다 — 사용자는 아이디·비밀번호를 주지 않는다.

## 실행 (순서 고정)

```powershell
$PY -B scripts/agent_admin_access_gate.py
# mode=auto_session         -> python -B scripts/agent_admin_session.py --base-url http://127.0.0.1:18180
# mode=proto_open_observe   -> http://127.0.0.1:18180 읽기 관찰로 검증, "관찰(observe)"로 기록
# mode=hold_codex_patch_pending -> HOLD 기록(재개 조건 포함), 다음 작업 계속
```

## 금지

- 사용자에게 로그인·계정·비밀번호·"어느 방식" 질문 카드.
- 공개 주소·터널·Forwarded 헤더로의 자동 세션 시도(로컬 127.0.0.1 전용).
- nonce·쿠키·비밀번호의 출력·커밋(`var\agent-admin\` 아래에만 존재).
