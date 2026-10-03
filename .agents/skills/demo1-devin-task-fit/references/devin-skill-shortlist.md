# 데빈이 고를 스킬 15개

163개 전부에서 고르지 않는다. 데빈 역할(도구, 스크립트, 규칙, 검증)에 맞는 것만 둔다. 제품 설계 스킬은 Codex 쪽이다.

후보를 거른 방법: `.agents/skills/*/SKILL.md` 163개의 이름과 description을 읽고, "수정 범위가 정해진 일"과 "검증·lease·보고"에 해당하는 것을 남겼다.

| 스킬 | 한 줄 | 언제 |
|---|---|---|
| demo1-devin-task-fit | 지시서를 잘 맞음/조건부/안 맞음으로 나눈다 | PASTE를 받은 직후, 파일을 고치기 전 |
| demo1-devin-directive-loop | 보고서의 완료 주장을 보고 답장을 쓴다 | 데빈 보고서가 도착했을 때. 절차는 그 스킬에만 있다 |
| demo1-devin-source-orchestrator | 여러 seam 지시서를 단계로 나눈다 | 브리프에 서로 다른 수정 대상이 둘 이상일 때 |
| demo1-work-ledger | journal과 checkpoint로 변경을 남긴다 | 파일을 만들기 전 |
| demo1-lease-conflict-autoflow | 겹친 lease를 live/stale로 가른다 | 수정이 lease에 막혔을 때. live는 강제 해제하지 않는다 |
| demo1-vibe-skill-router | 단계마다 primary 스킬 하나를 고른다 | 어떤 스킬을 열지 헷갈릴 때 |
| demo1-toolchain-auto-select | 이미 있는 빌드·테스트 명령을 고른다 | 검증 명령을 새로 만들기 전 |
| demo1-goal-complete-stop | Acceptance가 끝나면 멈춘다 | 통과 표가 채워진 뒤 |
| demo1-main-chat-surface | 판정 화면은 `/chat`이다 | "채팅이 된다"를 적을 때 |
| demo1-evidence-debugging | 로그에서 재현 가능한 증상으로 시작한다 | 실패를 재현·분류할 때 |
| demo1-output-budget | 긴 파일은 필요한 줄만 읽는다 | 로그·리포트를 열 때 |
| agent-session-watchdog | 세션 저장소의 실패 패턴을 훑는다 | 데빈 세션이 쌓였는지 볼 때 |
| demo1-conditional-local-git | 이 루트에서만 status/diff/선택 add/로컬 커밋 | 사용자가 커밋을 시킨 범위 안에서만 |
| demo1-vibe-selfask-judge-auto | 되돌릴 수 있는 선택은 AUTO로 정한다 | 승인 퀴즈를 쓰기 전 |
| demo1-desktop-only-proof-loop | 로컬 파일·명령 증거로만 증명한다 | 데스크톱에서 확인 가능한 일을 끝낼 때 |
