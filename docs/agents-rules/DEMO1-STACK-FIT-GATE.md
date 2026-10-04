# Stack-Fit Gate

새 프레임워크·언어 서버·DB/큐·SaaS·상시 데몬·런타임 전환·재작성의 요청과
Codex 자기 제안은 도입 전에 검사한다. 기존 버전업·동일 계열 모듈·테스트·문서·
읽기 전용 비교·일회성 stdlib 작업은 제외한다.

- FIT(0): 진행. ADAPT(3): 기존 대안으로 진행하고 원래 요청→대안·근거를 보고.
- DECLINE(4): 그 부분만 HOLD, 정중히 이유·대안 1개·file:line을 제시하고 나머지 계속.
- OVERRIDE(0): 명시적 사용자 승인 ADR의 해당 기술/범위로 진행; 반복 질문 금지.

ADR은 frontmatter `status: ACCEPTED`, `approvedBy: user`, `tech: <정확한 기술>`가
필수다. 본문 언급은 승인이 아니다. SUPERSEDED는 효력을 잃는다. 승인에 없는
기술이나 비밀키·배포·삭제 권한은 별도로 판정한다. 몰래 생략·껍데기 구현·전체
작업 중단은 금지한다. 오류는 reason code와 필요한 검증 행동을 보고한다.

SSOT: `configs/stack-fit.yaml` (JSON-compatible YAML 1.2, stdlib).
검사: `python -B scripts/stack_fit_guard.py --text-file <요청.md> --json`.
스킬: `.agents/skills/demo1-stack-fit-pushback/SKILL.md`.
결정: `docs/architecture/decisions/TEMPLATE.md`, `ADR-0001-nestjs-declined.md`.
