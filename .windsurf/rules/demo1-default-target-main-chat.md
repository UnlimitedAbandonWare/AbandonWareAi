---
trigger: always_on
---

# demo1-default-target-main-chat

Default verification target for Devin on this checkout. Applies only when the
directive does not name its own target surface.

- D1. Default target: no explicit target in the directive →
  `https://abandonwareai.kro.kr/chat` (public main AbandonWare AI chat). It is
  the same backend as local `http://127.0.0.1:18180/chat` via cloudflared —
  writes there persist real data/sessions locally.
- D2. Exceptions (only when explicitly named): Meta Ray-Ban Display /
  Conversate / receiver / lens / display relay, `/debug/studio`·interview
  screen (`static/assets/interview`), `/model-settings`, GPT Pro output review,
  a specific local port.
- D3. Public-site limits: page open/screenshot/GET are free; chat sends ≤3 per
  task with `[devin-test]` prefix and synthetic sentences only. Forbidden:
  load/repeat tests, crawling, session delete/settings save/uploads or any
  write, admin login/token entry, personal info/keys, cookie/token storage or
  output.
- D4. State-changing verification (post-patch regression, write paths,
  repeated runs) → local `http://127.0.0.1:18180/chat` first; the public site
  is a final single smoke.
- D5. Verdict evidence: target URL, request time (KST), HTTP status,
  `<title>`, chat-ui unique elements present, `INTERVIEW DEMO` absent,
  screenshot path.
- D6. If kro.kr serves the interview screen or 502/connection failure: do not
  fix — STOP_REPORT(`PRIMARY_SURFACE_OVERRIDDEN` or `PUBLIC_UNREACHABLE`) and
  continue on local 18180 only. Never touch cloudflared/tunnel/deploy config;
  no 18180 restart unless directed.

Probe: `python -B scripts/main_chat_target_probe.py [--local|--both]`.
Flow card: `data/agent-handoff/devin-default-target/TARGET_DECISION.md`.
