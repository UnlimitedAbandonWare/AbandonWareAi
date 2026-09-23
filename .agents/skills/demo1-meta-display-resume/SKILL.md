---
name: demo1-meta-display-resume
description: Use when resuming the existing Meta Ray-Ban Display task
---

# Display 작업 접수와 재개

현재 완료 근거로 다음 작업을 정한다. E0 접수, 상태 조회, 오래된 기록 판정이 이 역할의 범위다. client 구현과 실제 sync/Simulator 관측은 해당 역할로 이어가며 같은 증거를 세 역할이 반복 심사하지 않는다.

## 한 번 확인하고 다음으로

1. 이미 이번 세션에서 실행한 최신 selector 결과가 있고 입력이 그대로면 재사용한다. 없거나 관련 파일/증거가 바뀌었을 때만 현재 repo root에서 아래 명령을 실행한다.

   ```powershell
   python .\.agents\skills\demo1-meta-display-webapp\scripts\next_step.py --root .
   ```

2. [공유 재개 계약](../demo1-meta-display-webapp/references/continuation-contract.md)의 정확한 기록 schema·freshness·실패 분류를 사용한다. schema를 다시 정의하거나 별도 상태 파일/선택기를 만들지 않는다. 필요할 때 동일 helper의 `--requirements`를 읽는다.
3. E0이면 해당 Desktop 세션에서 기존 [canonical intake](../demo1-desktop-canonical-goal-intake/SKILL.md)의 artifact_only 절차로 실제 C root·AGENTS·작업서를 확인한다. Notebook 파일 접근과 hash 일치는 Desktop 실행 증명이 아니다. Notebook에서는 supporting-only로 남기고 가능한 문서 작업을 계속한다.
4. E1/E2는 [sync client](../demo1-meta-display-sync-client/SKILL.md), E3/E4는 [동작 검증](../demo1-meta-display-verification/SKILL.md)으로 직접 이어간다. 구현이 이미 승인된 현재 작업이면 같은 지시서나 승인을 다시 요구하지 않는다. 상태 조회만 요청됐다면 결과와 다음 단계까지 제공한다.

## 선택기 결과 처리

| 결과 | 다음 행동 |
|---|---|
| READY + nextStage | 해당 단계의 실제 작업을 현재 사용자 범위에서 수행 |
| task/target/evidence hash stale | 해당 변경과 원래 검증을 확인해 영향을 받은 단계부터 재검증 |
| runtime-evidence-stale | 원래 관측 시각을 보존하고 실제 E3/E4 관측을 다시 수행 |
| corrupt/missing proof, unsafe-path | 기록을 보존하고 정확한 원본 근거 하나부터 확인; PASS로 초기화 금지 |
| RECEIPTS_COMPLETE | 원본 Desktop 최종 근거를 검토; provider/공개 HTTPS/실기기 완료로 치환 금지 |

같은 blocker에는 새 증거가 생기기 전까지 반복 조회하지 않는다. E3의 연결 문제나 E4 확장 부재는 해당 단계에만 적용하며 독립 client/UI 검사를 막지 않는다. selector의 `sourceMutationAllowed=false`를 그대로 유지한다. 기존 E0 기록은 새 patch의 lease/preimage/source gate를 대체하지 않는다.

**Exception — Fold display + generation timing is not this role.** If the live user ask is to wire 전사/힌트 유지시간, 페이지 전환 간격, **or cue/generation cycle** (20 s hold, ~2 s page floor, 2.5 s quiet, 10 s cooldown, 180 s force-hint) on the existing Fold 안경 표시 설정, stop resume/status here and implement under `$demo1-meta-display-simple-caption`. Persist last saved prefs so the running cycle changes after save and after refresh/reconnect. That ask already authorizes source mutation; do not write a report-only packet, do not treat `sourceMutationAllowed=false` as a veto, and do not keep YAML defaults as immutable.

## 운영 계약

- 입력: `userScope, currentRoot, selectorResult, stageRecords, changedInputs`.
- 출력: `nextStage, nextAction, reason, acceptedStageCount, evidenceOwner, nextSingleProof, desktopFinalProof`.
- owner/mutation: 기본 read-only. 실제 Desktop 접수 기록은 해당 Desktop 실행자가 기존 codex/report 경계에만 작성한다. Notebook ACK·소스·credentials·watcher를 생성하지 않는다.
- 한도: 기본 조사 15분/20개 근거, selector의 협력적 25초 예산/Git 5초 timeout 및 호출자 30초 한도, 보고 30행.
- 실패: 잘못된 기록은 fail-closed, 해당 단계만 보류. 원문 예외·경로·질문·cookie를 출력하지 않고 기존 scanner의 count/reason/hash를 사용한다.
- rollback: 읽기에는 복구 작업 없음. 잘못된 요약은 원래 실행 증거에 따라 해당 요약만 복구한다. 스킬 제거는 이 폴더·역할 참조·task binding 항목만 대상으로 하고 실행 근거는 보존한다.
- 중복 방지 근거: 기존 webapp의 접수/재개 판단을 분리했다. Desktop resolver, selector, source-owner gate와 기록 schema는 새로 만들지 않는다.
- 반증 사례: 오래된 E3 timestamp만 현재 시각으로 바꾸거나, E1 core가 바뀌었는데 E3부터 진행하거나, Notebook에서 E0 PASS를 대신 만들면 실패다.
