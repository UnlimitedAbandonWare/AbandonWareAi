# Deadline and scope judgment (guard-deadline-scope)

이 문서는 Devin, Antigravity, Grok CLI가 함께 읽는 판단 본문이다. 사용자가 제공한 `guard-deadline-scope` 핵심 지침을 프로젝트에 연결하며, 개인 스킬 원문을 자동으로 읽었다는 뜻은 아니다. 작업 유형과 무관하게 적용하고 기존 AGENTS, UAW, support 개발 지침의 허용 범위와 검증 절차를 따른다.

## 작업 시작과 시간 여유

- 선택 기능이나 추가 설계 전에 최신 명시 마감과 시간대, 실제 작업 가능 시간, 최소 완료 조건, 현재 baseline, 구현·검증·복구 비용을 확인한다. 마감에 시각이 없으면 임의의 시각이나 남은 시간을 만들지 않는다.
- 시간 추정에는 검색뿐 아니라 생각 정리, 프롬프트 준비, 계획, 자료 확인, 구현, 검증, 복구를 포함한다.
- 추정 작업시간의 대략 2배는 초기 계획 여유일 뿐이다(예: 50초 추정에 약 120초의 계획 여유 검토). 추정과 실제 경과를 구분하고, 명시된 사용자 hard cap 안에서 P0·검증·복구 시간을 먼저 확보한다. API timeout, 제품 TTL, 권한, 재시도 제한을 늘리는 근거로 쓰지 않는다.
- 목표가 검증되면 여유가 남아도 종료한다. 예산 확인 시 실제 진척, 경과시간, 허용된 남은 범위를 짧게 보고하고 계속하거나 범위를 줄인다. 자동 강제 종료나 실제 타이머·서비스를 새로 만들지 않는다.
- 기존 허용된 작업 기록에만 추정/예산/경과/결과/이유를 간단히 남겨 유사한 다음 작업의 추정을 보정한다. 실패마다 예산을 늘리거나 전역 자동 학습·telemetry를 추가하지 않는다.

## 범위와 판단

- 효용, 구현 복잡도, 실패·복구 위험을 각각 평가한다. 긍정 근거, 부정 근거, 반례, 더 단순한 기존 대안을 확인한다. 짧은 요청도 인증이나 공통 코드를 건드리면 위험이 클 수 있다.
- 현재 파일과 기존 동작을 먼저 확인한다. 사실, 추정, 근거 부족, NOT_RUN을 구분한다. 모델 이름이나 요청 모델은 실제 실행 모델·능력의 증거가 아니다.
- 중요한 P0·보안·정확성 결함은 마감이 가깝다는 이유만으로 뒤로 미루지 않는다. 선택 기능의 통합·검증·복구 비용이 불확실하면 기존 대안을 우선한다.
- 판정은 `adopt-minimal`, `reduce-scope`, `defer`, `reject-current-design`, `insufficient-evidence` 중 하나로 표현한다. 권고는 삭제·취소·기능 제거·규칙 우회의 권한이 아니며 최신 사용자 결정을 따른다.
- 허용된 수정은 실제 source RED → 한 원인과 최소 diff → 관련 GREEN 순서를 유지한다. 기존 assert를 약화하지 않고 제품/runtime 검증과 문서 지원 검증을 구분한다. 기존 절차: [work ledger](DEMO1-WORK-LEDGER.md), [common guards](DEMO1-AGENT-GUARD-COMMON.md), [positive/negative/neutral judge](../../.agents/skills/positive-negative-neutral-judge/SKILL.md). UAW/support 지시서는 실제 파일 확인을 대신하지 않는다.

## 불확실성과 재평가

- 같은 태스크·판단 범위의 불확실성 재평가는 누적 최대 1회다. 거절된 설계는 허용된 작은 격리 시험이나 기존 도구 재사용에서 새 증거가 생겼을 때 남은 1회 안에서 다시 판단할 수 있다. 새 증거, 모델 변경, 이름 변경으로 시도 횟수를 초기화하지 않는다.
- 재귀 fallback, 외부 작업 재실행, 취소된 작업 재개는 이 규칙으로 허용되지 않는다. 충분한 증거가 없으면 검토 대기, 다음 결정 조건, 현재 사용할 대안을 응답에 남긴다.
- 사용자가 검토 기록을 요청한 경우에만 기존 허용 저장소에 `key`, `attempt`, `status`, `createdAt`, `expiresAt`, `TTL`, `cap`을 기록한다. 요청이 없으면 응답의 대기 표시로 충분하며 큐·timer·서비스를 구현하지 않는다.
- TTL 24시간(명시 마감 이내)과 cap 10은 조정 가능한 제안이다. 강제 설정이 아니며 시각 없는 마감에 임의의 시각을 더하지 않는다. 만료는 `review_expired`, 가득 차면 새 기록 보류와 알림으로 처리한다. 자동 실행·조용한 삭제는 하지 않는다.

## 현재 프로젝트 컨텍스트

- 사용자 명시 마감: **2026-10-16, Asia/Seoul**. 명시 시각은 없다. 이는 현재 프로젝트 정보이며 위의 재사용 판단 규칙에 고정된 날짜가 아니다. 새 명시 지시가 오면 그 지시를 우선한다.
- 취소된 Jev 설계는 재개하지 않는다. 현재 지침 연결 작업은 제품 source, 설정, 권한, 모델/API 실행을 변경하는 허가가 아니다.

## 대표 판단 예시 (offline 문서 계약)

| 상황 | 판정 / 다음 행동 |
|---|---|
| 기존 baseline이 목표를 만족하고 검증됨 | `adopt-minimal`: 추가 기능 없이 종료 |
| 선택 기능은 유용하지만 마감 내 통합·복구 비용이 불명 | `defer`: 기존 대안과 재검토 조건 제시 |
| 사용자 hard cap보다 약 2배 계획 여유가 큼 | 초기 여유를 cap 안으로 조정; 실제 P0·검증·복구 비용도 초과하면 `reduce-scope` |
| 짧은 UI 요청이 인증·공통 코드까지 번짐 | `reduce-scope`: 위험을 따로 평가하고 허용된 최소 경로 선택 |
| P0·보안·정확성 결함이 현재 증거로 확인됨 | `adopt-minimal`: 필요한 수정과 검증을 우선 |
| 현재 설계가 최소 완료 조건을 충족할 수 없음 | `reject-current-design`: 더 단순한 기존 대안 제시 |
| 같은 증거로 모델만 바꾸어 재시도 제안 | `insufficient-evidence`: 횟수 유지, 재귀 fallback 금지 |
| 작은 허용 격리 시험에서 새 증거가 생김 | 누적 1회 재평가의 남은 횟수 안에서 증거를 비교하고 판정 |
| 사용자가 검토 기록을 요청하지 않음 | 응답에 대기와 조건만 남김; 큐 구현 없음 |
| 요청된 기존 검토 기록이 만료되거나 cap에 도달함 | `review_expired` 또는 새 기록 보류·알림; 삭제·자동 실행 없음 |
| 사용자가 취소한 작업을 다른 모델로 재개하자는 제안 | `reject-current-design`: 최신 취소 유지 |
| 파일 설치만 확인되고 provider 실행을 하지 않음 | 문서 연결 검증만 보고, 실제 자동 로드 `NOT_RUN` |

응답은 한국어로 결론을 먼저 쓰고 근거·대안과 한 줄 요약을 붙인다. 지침 파일이 존재하거나 정적 검사가 통과했다는 사실만으로 실제 provider가 읽었다거나 제품 검증이 통과했다고 말하지 않는다.

## CLI 판정 도구 (deadline_scope_guard)

이 문서의 판단 규칙을 정량으로 실행하는 읽기 전용 CLI다. 판정은 권고이며
삭제·취소·규칙 우회의 권한이 아니고, 최신 사용자 명시 결정이 우선한다.

```powershell
python -B scripts/deadline_scope_guard.py --task-type fix `
    --target main/java/com/example/lms/api/Foo.java --est-seconds 600
python -B scripts/deadline_scope_guard.py --task-type ai-integration --json
```

- 인자: `--deadline`(기본 2026-10-16, 문서 컨텍스트) `--now` `--target`(반복)
  `--task-type`(P0/fix/feature/refactor/ai-integration) `--est-seconds`
  `--has-browser-verify` `--has-e2e-evidence` `--json`
- 계획 예산 = 추정 × 2.0 + 브라우저/E2E 검증 필요 시 120s 버퍼.
- 판정 플로우(첫 매칭 규칙이 결정): P0→adopt-minimal → 증거 전무→
  insufficient-evidence → ai-integration·잔여≤14일→defer → 예산 초과→
  refactor는 reject-current-design, 아니면 reduce-scope → 인증·공통 코드
  침범→reduce-scope → 기본 adopt-minimal.
- 스킬 진입은 `.agents/skills/demo1-deadline-scope-guard/SKILL.md`
  (intent `deadline-scope-guard`), 단위 테스트는
  `scripts/test_deadline_scope_guard.py`.

## 최근 세션 교훈 (2026-10-03~06 관측)

- Fold6 오디오 정지·Dynamic Auto 브라우저 검증·Jev 보류 세션에서 마감
  압박과 스코프 크립 방지가 핵심이었다 — CLI로 매번 같은 기준을 적용한다.
- 브라우저/E2E 검증이 들어가는 작업은 계획에 **120초 검증 버퍼**를 별도로
  확보한다(`--has-browser-verify`가 plannedBudgetSeconds에 자동 반영).
- Jev 등 신규 외부 AI/오케스트레이터 도입은 3대 거절 기준을 먼저 확인한다:
  ① 공급자 공식 API 문서 부재, ② 순환 의존 위험, ③ 10ms 로컬 폴백 불가.
  취소된 Jev 설계는 재개하지 않으며(위 §현재 프로젝트 컨텍스트), 잔여
  ≤14일이면 도입 자체를 `defer`로 본다.
