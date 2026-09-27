# ma21in F01–F07 assist

Grok은 이 복구에서 애플리케이션 소스를 수정하지 않는다. Codex가 `main/java`와 `main/resources`를 고친다.

패치 전에 `agent-prompts/ma21in-f01-f07-codex-assist/codex_operating_card.md`를 읽고, `python -B agent-prompts/ma21in-f01-f07-codex-assist/probe_f01_f07.py` 결과를 현재 판정으로 쓴다. 감사 ZIP의 줄 번호보다 프로브가 우선이다.

`leaseBlocks`가 있는 파일은 수정, 락 삭제, recover를 하지 않는다. `closed` 항목은 다시 고치지 않는다. `gradleProof=not-run`은 테스트 통과가 아니다.

AGENTS.md, `docs/PROJECT_STATUS.md`, `.agents/skills-intent-index.yaml`은 다른 작업이 잡고 있다. F01–F07 지침은 위 작업 카드에만 둔다.
