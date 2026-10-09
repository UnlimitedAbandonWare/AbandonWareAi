---
name: demo1-parallel-auto-resume
description: "Use for overlapping source-edit contention: bounded WAITING rounds, immutable baseline, fresh own lease, reread/replan and the existing executable resume gate. Free never grants write authority."
---

# demo1 Parallel Auto-Resume

겹치는 source-edit lease를 만나도 세션을 끝내거나 사용자에게 묻지 않는다.
"기다림 → 상대 변경 다시 읽기 → 재계획 → 자동 재개"를 도구와 규칙으로 고정한다.

## 언제 쓰나

- `source_edit_session.ps1 -Action begin`이 exit 7(lease 충돌)을 돌려줬을 때
- `agent_scope_lease.py check --path`가 exit 7을 돌려줬을 때
- `lease_conflict_autoflow.py scan`에서 내 target이 `overlappingTargets`에 있을 때
- 대기 때문에 "사용자에게 확인", "세션 종료", "중단합니다"로 끝내려 할 때

## 병렬 기준표

| 상황 | 판정 | 행동 |
|---|---|---|
| 겹치는 파일 없음 | 병렬 | 바로 진행 |
| 같은 파일, 상대 lease live | 기다림 | lease-wait로 기다리며 겹치지 않는 일 먼저 |
| 같은 파일, 상대 lease stale | 판독/owner 확인 | TTL만으로 unknown owner 회수 금지; lifecycle SSOT 적용 |
| identity/경로/판독 불명 | 해당 lane BLOCKED | 쓰기 없이 근거 확인; 독립 범위 계속 |

## 순서

전체 계약과 CLI/decision 필드는 [lease lifecycle SSOT](../../../docs/agents-rules/DEMO1-LEASE-LIFECYCLE.md#parallel-waitresume-contract-d1-parallel-resume-guardrails-20261009-r1)에 둔다.
`lease-wait`에 goal/plan revision과 관련 input을 선언하고 최초 baseline/hash를 보존한다.
free 이후 자기 fresh lease를 취득하며 변경은 재독·새 계획·현재 검사에 바인딩한다.
대기한 쓰기는 기존 `guarded_source_edit.js`의 `waited:true`/`resume` gate를 소비한다.
APPLY는 현재 RED, 이미 완료됨은 현재 GREEN과 쓰기 0을 요구한다. plain drift 보고서는 advisory다.
정상 live round는 WAITING을 유지하고 독립 범위를 계속한다. 해제는 자기 acquisition에 묶인 finally로 수행한다.
마지막 gate 뒤 비협력 writer의 변경은 원자적으로 차단하지 못한다.

## 금지

- 기억 속 옛 내용으로 파일 덮어쓰기 (재읽기 없는 패치)
- live lease 강제 해제·lock 디렉터리 삭제
- lease 대기를 이유로 세션 종료/사용자 확인 요청/중단 선언
- 대기 중 다른 세션의 파일·hunk 선점 수정

## 관련

- `agent-scope-lease` — check/claim/heartbeat/done의 임대 작업 단위
- `demo1-lease-conflict-autoflow` — live/stale/orphan 분류와 stale 회수 계약
- `demo1-codex-auto-decide` — 묻지 않고 AUTO로 진행하는 선택 기본표
- `demo1-work-ledger` — journal/checkpoint 기록 절차
