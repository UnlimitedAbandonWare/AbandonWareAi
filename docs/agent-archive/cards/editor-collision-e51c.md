# editor "already exists" 오류 = 다른 세션 소유 파일 충돌 신호 — 덮어쓰지 말고 고유 이름으로 회수
- card-id: editor-collision-e51c
- kind: failure-signature
- status: still-true (행동 규칙)
- date: 2026-09-30 KST
- evidence: data/agent-handoff/clean-jev-bet-assist-20260930/FINDINGS-CLINE.md (§1)
- reverify: `Get-Content data\agent-handoff\clean-jev-bet-assist-20260930\FINDINGS-CLINE.md -TotalCount 20`

## 근거
- 사건: Cline 보조 세션이 `PRECHECK.md`를 생성하려다 "already exists" → 같은 경로에 새 파일로
  써서 **상대 보조 세션의 T0 판독을 덮어씀**. FOR_GROK.md G-I-5와 같은 근본 원인.
- 교정 패턴: 충돌을 파일명 충돌로 간주 → 고유 이름 회수(`PRECHECK-CLINE.md`), 이후 신규 파일은
  상대가 쓰지 않은 이름(`WP2_*`, `TOSS-JEVBET-0*`)만 사용.
- 규칙: 병렬 세션에서 "already exists"는 내 파일이 아닐 가능성이 높다 → lease/owner 확인 후
  다른 이름으로.
