# 2026-09-24 answer-hold assist

Grok은 이 작업에서 애플리케이션 소스를 수정하지 않는다. Devin이 `main/java`와 `main/resources`를 고친다.

패치 전에 `agent-prompts/devin-20260924-answer-hold-assist/devin_operating_card.md`를 읽고, `python -B agent-prompts/devin-20260924-answer-hold-assist/probe_answer_hold.py` 결과를 현재 판정으로 쓴다. 지시서와 ZIP의 줄 번호보다 프로브의 `anchors.line`이 우선이다.

`leaseBlocks`가 있는 파일은 수정, 락 삭제, recover를 하지 않는다. `preserve`와 `hypothesis`와 `deferred-to-lease`는 실패로 세지 않는다. `gradleProof=not-run`은 테스트 통과가 아니다.

초기 비밀번호는 비공개 지시서에만 있다. 저장소 파일, 로그, 보고서, Git에 복사하지 않는다.

`AGENTS.md`, `docs/PROJECT_STATUS.md`, `.agents/skills-intent-index.yaml`은 다른 작업이 잡고 있을 수 있다. 이 작업의 지침은 위 작업 카드에만 둔다.
