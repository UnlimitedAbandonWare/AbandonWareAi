---
name: demo1-stack-fit-pushback
description: "언제 쓰는지: 요청·계획서·GPT Pro 답 또는 Codex 자신의 제안에 새 프레임워크·언어 서버·상시 데몬·SaaS·DB/큐·런타임 전환·재작성이 있을 때 사용한다."
---

# Stack-Fit Pushback

## 사용 범위

새 기술을 도입하기 **전** 요청과 자기 제안에 같은 게이트를 적용한다.
버그 수정, 같은 라이브러리 버전업, 같은 계열 모듈, 테스트 전용 의존성,
문서 작성·읽기 전용 비교, 일회성 stdlib 스크립트에는 도입 게이트를 강제하지 않는다.
SSOT는 `configs/stack-fit.yaml`이다. JSON-compatible YAML 1.2를 stdlib로 읽으므로
YAML 파서나 다른 패키지를 설치하지 않는다. 선언/소스 배선/운영 증거를 구별한다.

## Self-Ask 5문

1. 실제로 풀려는 문제와 완료 조건은 무엇인가?
2. 지금 스택이 이미 푸는가? 라이브 소유자의 `file:line`을 확인한다.
3. 새 런타임·데몬·계정·의존성·운영 비용과 규칙 충돌이 무엇인가?
   [prototype-light](../../../docs/agents-rules/DEMO1-PROTOTYPE-LIGHT.md):8과
   [coop-verify](../../../docs/agents-rules/DEMO1-COOP-VERIFY-RAILS.md):9를 확인한다.
4. SSOT의 `story`와 맞는가? 이력서 키워드만을 위한 추가인가?
5. 같은 문제를 푸는 가장 작은 기존 대안은 무엇인가?

## 절차

1. 루트/라이브 소유자/현재 요구를 확인한다. 로컬 사실에 외부 호출을 덧붙이지 않는다.
2. 다음 중 요청 입력과 맞는 **하나**를 검사한다.

   ```powershell
   python -B scripts/stack_fit_guard.py --root . --text-file <요청.md> --json
   python -B scripts/stack_fit_guard.py --root . --text "NestJS 도입해줘" --json
   python -B scripts/stack_fit_guard.py --root . --diff --json
   python -B scripts/stack_fit_guard.py --root . --paths frontend/package.json --json
   ```

3. `FIT`(0): 진행한다. `ADAPT`(3): 기존 대안으로 진행하고 보고에
   `원래 요청 X → 대안 Y | 이유·file:line` 한 줄을 남긴다.
   `DECLINE`(4): 그 부분만 HOLD하고 대안 1개를 제안하며 나머지 WP를 계속한다.
   `OVERRIDE`(0): 해당 기술의 사용자 승인 ADR을 확인하고 승인 범위로 진행한다.
4. 애매하면 [Self-Ask judge](../demo1-vibe-selfask-judge-auto/SKILL.md)를 쓴다.
   라이브 근거가 충돌할 때만 [challenger 축](../demo1-codex-selfask-triad/SKILL.md)을
   한 번 사용한다. 이 스킬에 기존 심의 내용을 복사하지 않는다.
5. 바뀐 범위를 집중 검증하고 결과·대안·미검증 사실을 작업 원장에 남긴다.

## 사용자 OVERRIDE

사용자가 명시적으로 "그래도 X로 해"라고 하면 승인된 기술과 요구 범위를
`docs/architecture/decisions/TEMPLATE.md` 형식으로 기록한다.
ADR frontmatter의 `status: ACCEPTED`, `approvedBy: user`, `tech: <SSOT tech>`가
모두 있어야 검사기가 OVERRIDE를 인정한다. 기술 여러 개는 쉼표로 나열한다.
본문에 다른 기술을 언급해도 그 기술까지 승인하지 않는다.
같은 요청은 다시 묻지 않으며, 승인되지 않은 독립 기술은 계속 판정한다.
ADR 승인은 별도의 비밀키·배포·삭제 권한을 만들지 않는다.
`SUPERSEDED`로 바꾼 ADR은 override 효력을 잃는다.

## 출력 형식과 예시

`STACK_FIT: DECLINE <기술> | 이유: <규칙/비용> | 대신: <대안 + file:line> | 계속: <나머지>`

정중하고 짧게 설명한다. [한국어 예시 3개](references/decline-templates.md)와
[평가 14케이스](references/evaluation-cases.md)를 재사용한다.
기존 Nest 결정은 `docs/architecture/decisions/ADR-0001-nestjs-declined.md`다.

## 금지

보고 없는 생략, 빈 Nest 폴더·껍데기 서비스, 같은 질문 반복, 명시적 override 무시,
게이트를 핑계로 전체 작업 중단, 새 SaaS/데몬 설치, 비밀값·원문 요청 출력은 금지한다.
`.env*`, `.secrets`, 키 파일은 열지 않는다. 검사기는 읽기 전용이며 Git을 변경하지 않는다.
새 기술을 "예시"로 설치하지 않는다. 거절 대신 기존 동작을 삭제하지 않는다.
[자산 보존](../demo1-goal-asset-preservation/SKILL.md)과
[동등 도구 우선](../demo1-toolchain-auto-select/SKILL.md)은 기존 계약을 따른다.

## 검증 / Acceptance

`python -B -m unittest scripts.test_stack_fit_guard -v`와 두 라우터 테스트를 실행한다.
새 스킬은 quality audit에서 오류 0, AGENTS 예산 검사는 exit 0이어야 한다.
`FIT`는 스택 도입 탐지 결과이며 빌드·런타임·브라우저 성공을 증명하지 않는다.
검사 오류는 `DECLINE` + reason code로 보고하고 필요한 입력만 복구한다.
