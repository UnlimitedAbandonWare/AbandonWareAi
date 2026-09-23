---
name: demo1-meta-display-sync-client
description: Use when implementing or fixing the Meta Ray-Ban Display sync client in demo-1
---

# Display sync 클라이언트 구현

질문/preset → 기존 sync 요청 → 처리 상태 → 답변·출처 카드 → 방향키 이동을 현재 client 경계에서 구현한다. E1/E2 구현과 그 집중 검증을 이 역할 하나가 맡는다. 이미 승인된 구현 요청이면 같은 승인을 다시 요구하지 않는다.

## 시작과 범위

- [공유 소스 계약](../demo1-meta-display-webapp/references/source-contract.md)의 `Current source ownership`, `Sync request`, `Actual response`, `Sessions`, `UI` 항목을 필요한 만큼 읽는다. 파일 경로와 필드는 조사 기준선이므로 변경된 부분은 현재 소스로 재확인한다.
- [기존 작업서](../../../agent-prompts/meta_rayban_display_sync_v1_20260912.md)의 선언된 대상과 현재 source-owner gate를 사용한다. 첫 후보는 `main/resources/static/assets/display/`의 정적 client와 기존에 계획한 전용 fixture이며 URL은 `/assets/display/index.html`이다.
- 이 스킬은 새 endpoint, DTO, BFF, prompt builder 또는 소스 승인 절차를 만들지 않는다. 다른 범위의 결함이 확인되면 그 증거와 대상만 분리하고 현재 구현 권한으로 가능한지 판단한다. 일반 문서/상태 조회 요청에서 소스를 수정하지 않는다.
- 직접 진입해도 현재 입력에 맞는 selector 결과가 없으면 `python .\.agents\skills\demo1-meta-display-webapp\scripts\next_step.py --root .`를 한 번 실행한다. 이번 세션의 최신 결과는 재사용하고 관련 검증 근거가 바뀐 뒤 한 번 갱신한다. 결과에 미충족 접수/선행 조건이 있으면 먼저 충족하며, resume 역할을 추가로 왕복할 필요는 없다.

## 실제 구현에서 지킬 결정

1. 서버의 `content/evidence`를 client 카드로 투영한다. 질문이나 서버 prompt를 바꿔 짧은 답변을 유도하지 말고 원문 답변을 페이지로 나눈다. 빈 출처는 그대로 표시한다. source 계약의 text node·URL 정규화 정책을 적용하고 내부 filePath·pipeline/learning 상태를 표시하지 않는다.
2. relative `/api/chat/sync`와 same-origin cookie를 사용하고 서버가 반환한 numeric sessionId를 재사용한다. HttpOnly ownerKey를 JS/URL/storage에 복제하지 않는다. 질문의 의미를 바꾸거나 별도 system prompt를 끼우지 않는다. composer text만으로 voice 입력이라고 추정하지 않는다.
3. 한 번의 논리 전송에 `Idempotency-Key`와 body identity를 유지한다. 단일 진행 요청, 늦은 응답 무시, timeout 뒤 Enter 재전송 0회를 실제 client module로 검증한다. 409/422를 새 key로 우회하지 않는다. abort는 서버 추론 종료 증명이 아니다.
4. IDLE/LOADING/RESULT/ERROR, 보이는 focus, 방향키·Enter·Escape와 IME 입력 중 동작을 구분한다. system composer는 실제 사용자 입력 동작을 따르고 preset/일반 입력 대안을 둔다. raw mic/camera를 Web App API로 추정하지 않는다.
5. 400/403/409/413/422/429/503 및 비JSON 오류를 현재 계약으로 분류한다. 503은 실제 admission 의존성 문제일 수 있다. client를 통과시키려고 보안·Redis admission·기존 SSE를 제거하지 않는다.
6. receiver 입력은 버전·카드 text·만료 시각을 검증한 뒤에만 화면과 latestVersion을 갱신한다. 잘못된 snapshot은 ACK하지 않고 정상 후속 snapshot을 막지 않아야 한다. 빈 카드·만료·일시정지의 기존 정리 동작을 유지한다.

## 에이전트 바이브 코딩

자율 구현에는 [기존 경계를 재사용하는 작업 프롬프트](../../../agent-prompts/meta_display_vibe_coding.md)를 적용한다. 한 번에 하나의 재현 가능한 결함과 최소 대상만 맡기고, 탐색 결과를 현재 파일과 대조한다. 기존 코드가 이미 맞으면 테스트만 보강한다. 일반 sync, 휘발성 Conversate, 운영자 카드 전달의 서로 다른 의미를 유지하며 소스 존재·합성 검사·실제 모델·실기기 증거를 구분한다. 이 문서는 새로운 승인·lease·심사·provider 호출 절차를 추가하지 않는다.

## 검증과 다음 역할

미구현 기능/실제 결함을 반증하는 fixture를 먼저 실행하고, 필요한 최소 client 변경 후 같은 fixture로 확인한다. 기존 stream 계약 검사는 해당 회귀 범위에 맞게 한 번만 수행한다. fixture 성공은 provider나 공식 Simulator 성공이 아니다.

E1/E2가 입증되면 [공유 재개 계약](../demo1-meta-display-webapp/references/continuation-contract.md)에 실제 실행 근거를 기록한다. Desktop에서 실제 수행한 경우에만 E1/E2 PASS 기록을 작성하고 Notebook 결과는 supporting-only로 남긴다. 이 세션의 최신 selector 결과를 재사용하고, 관련 변경·새 증거가 생겼을 때만 다시 호출한다. E3/E4가 다음이면 [동작 검증 스킬](../demo1-meta-display-verification/SKILL.md)로 이어간다. 추가 리뷰를 위해 같은 fixture를 반복하지 않는다.

## 운영 계약

- 입력: `userScope, selectedStage, declaredTargets, sourceEvidence, verificationBudget`.
- 출력: `changedFiles, causalBoundary, redGreenEvidence, unresolvedChecks, nextSingleProof, rollback, desktopFinalProof`.
- owner/mutation: 현재 소스 gate가 허용한 client 대상만. public API/DB/credential/기존 SSE는 이 스킬의 변경 표면이 아니다.
- 한도: 근거 조사 15분/20개, 진단 30초, 구현은 현재 GoalContract 한도, 보고 30행. 필요한 검증이 끝나면 다음 작업으로 간다.
- 실패: contract-drift/duplicate-submit/stale-response와 source ownership·preimage·secret 문제를 구분한다. 후자는 해당 변경을 fail-closed로 중단하며 독립 작업은 계속한다.
- redaction: 실제 산출물의 기존 secret scanner 결과는 건수만 기록한다. 원문 질문/답변/cookie/token/internal path는 보고서·debug에 저장하지 않는다.
- rollback: guard가 기록한 preimage와 이번 신규 파일만 복구한다. 이 스킬 제거는 해당 폴더와 진입 역할 참조·selector의 task binding 항목만 복구하며 실제 소스/증거를 지우지 않는다.
- 중복 방지 근거: 기존 webapp 스킬의 E1/E2 구현 결정을 분리했다. 소스 계약·fixture·selector는 원래 소유 위치를 재사용한다.
- 반증 사례: timeout 후 Enter가 두 번째 inference 요청을 만들거나, fixture만 고쳐 실제 client 결함을 숨기거나, `answer/sources`라는 없는 DTO 필드를 사용하면 이 스킬 적용은 실패다.
