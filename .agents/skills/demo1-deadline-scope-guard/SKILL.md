---
name: demo1-deadline-scope-guard
description: 'Use when a demo-1 task needs deadline/scope judgment (마감 판단, 범위 판단, 시간 예산, 스코프 크립, 데드라인, 자동 반려, 동적 예산) — runs the quantitative guard CLI scripts/deadline_scope_guard.py and maps its output to the 5 verdicts. SSOT: docs/agents-rules/DEMO1-DEADLINE-SCOPE-JUDGMENT.md'
---

# demo1-deadline-scope-guard

문서로만 있던 `guard-deadline-scope` 판단을 정량 CLI로 실행한다.
판정 SSOT는 `docs/agents-rules/DEMO1-DEADLINE-SCOPE-JUDGMENT.md` — 이 스킬은
CLI 호출 절차와 판정 해석만 담고, 규칙 본문을 복제하지 않는다.

## When

- 작업이 마감(기본 2026-10-16, Asia/Seoul)과 실제 작업 가능 시간 안에
  들어가는지 판단할 때
- "짧은 요청"이 인증·공통 코드로 번지는지, 신규 외부 AI/오케스트레이터
  (Jev 등) 도입을 마감 앞에서 받아들일지 결정할 때
- 지시서 Acceptance 범위와 예상 소요(구현+검증+복구)를 비교해
  adopt/축소/보류를 정량으로 뽑을 때
- 시간·범위 판단만 필요한 경우 — 구현·검증 실행 자체는 별도 스킬

## Do

1. 판정 입력 모으기: `--task-type`(P0/fix/feature/refactor/ai-integration),
   `--target`(변경 파일 목록), `--est-seconds`(추정 초),
   `--has-browser-verify`, `--has-e2e-evidence`, `--deadline`.
2. CLI 실행:
   `python -B scripts/deadline_scope_guard.py --task-type <t> --target <f> --est-seconds <n> [--json]`
3. 출력 `verdict`를 5대 판정으로 해석:
   - `adopt-minimal`: 최소 변경 채택 — 계획 진행
   - `reduce-scope`: 범위 축소 — P0·검증·복구 시간을 cap 안에서 먼저 확보
   - `defer`: 보류 — 기존 대안 + 재검토 조건을 응답에 남김
   - `reject-current-design`: 현재 설계 거절 — 더 단순한 기존 대안 제시
   - `insufficient-evidence`: 근거 부족 — 재현 증거/대상 목록 먼저 확보
4. 시간 예산 규칙: 계획 예산 = 추정 × 2.0 + 브라우저/E2E 검증 시 120s 버퍼.
   추정과 실제 경과를 구분하고 사용자 hard cap 안에서 P0·검증·복구를 먼저 확보.
5. 신규 외부 AI 도입(Jev 등)은 잔여 ≤14일이면 `defer`; 도입 자체는
   3대 거절 기준(API 문서 부재, 순환 의존 위험, 10ms 로컬 폴백 불가)을
   `externalAiGate` 출력으로 확인한다.
6. 판정 결과를 응답·journal에 사실/추정 구분으로 남긴다 — CLI 판정은
   권고이며 삭제·취소·규칙 우회 권한이 아니다.

## Do not

- CLI 판정을 최신 사용자 명시 결정보다 우선시하지 않는다 (권고일 뿐).
- 마감에 시각이 없는데 임의의 시각·남은 시간을 만들지 않는다.
- 재귀 fallback, 취소된 작업 재개, 같은 증거로 모델만 바꾼 재시도를
  이 판정으로 허용하지 않는다.
- P0·보안·정확성 결함을 마감이 가깝다는 이유만으로 뒤로 미루지 않는다.
- 판정 PASS/exit 0을 제품 동작 검증으로 표기하지 않는다 — 범위 판단 도구다.

## Verify

```powershell
python -B scripts/deadline_scope_guard.py --help            # exit 0
python -B -m unittest scripts/test_deadline_scope_guard.py  # ALL PASS
python -B scripts/agy_skill_pack_check.py --skill .agents/skills/demo1-deadline-scope-guard --grade M
```

## Example

- `python -B scripts/deadline_scope_guard.py --task-type fix --target scripts/x.py --est-seconds 600`
  → `verdict: adopt-minimal` (또는 위험 경로 시 reduce-scope)
- `python -B scripts/deadline_scope_guard.py --task-type ai-integration --est-seconds 7200 --json`
  → 마감 임박이면 `defer` + `externalAiGate` 3대 기준 나열.

## SSOT

`docs/agents-rules/DEMO1-DEADLINE-SCOPE-JUDGMENT.md` — 판단 규칙 본문,
대표 판단 예시 표, 재평가 상한. CLI: `scripts/deadline_scope_guard.py`,
단위 테스트 `scripts/test_deadline_scope_guard.py`.
