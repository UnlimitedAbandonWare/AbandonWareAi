# Devin paste — answer-hold 2026-09-24

`@objective-executor @demo1-devin-source-orchestrator`

작업 루트는 `<repo>`다. 애플리케이션 소스만 고친다. 이 프롬프트와 작업 카드는 이미 있다.

1. `agent-prompts/devin-20260924-answer-hold-assist/devin_operating_card.md`를 읽는다.
2. `python -B scripts/devin_task_orchestrate.py plan --brief-file agent-prompts/devin-20260924-answer-hold-assist/brief.txt`를 실행한다. Downloads 지시서 원문으로 plan 하지 않는다.
3. `python -B agent-prompts/devin-20260924-answer-hold-assist/probe_answer_hold.py`를 패치 전과 후에 실행한다.
4. `leaseBlocks` 파일은 건너뛴다. 지금 그 목록에 관리자 토큰 가드가 있으면 운영 화면 개방은 그 리스 소유자에게 남기고, 답변 공개 판정과 `chat.js`를 먼저 고친다.
5. 기존 저널 `chat-release-admin-fix-devin-0924-7355c8ae`가 아직 `in_progress`이면 그 taskId를 재사용한다.
6. 초기 비밀번호는 비공개 지시서에서만 읽고 환경변수로만 주입한다. 소스와 Git에 넣지 않는다.
7. 플러그인은 카드의 순서만 쓴다. Supabase, Data, Wolfram, SciSpace, Sites, Meta Wearables, 복구용 AWX는 쓰지 않는다.
8. 완료는 컴파일이 아니다. 프로브 open, Gradle exit code, 브라우저에서 `안녕?` 본문과 틀린 로그인의 거부를 나눠 보고한다.
