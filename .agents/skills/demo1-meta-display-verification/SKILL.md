---
name: demo1-meta-display-verification
description: Use when starting the Meta Display Spring server or verifying sync/Simulator evidence
---

# Display 실제 동작 검증

E3의 실제 Spring 연결·세션과 E4의 공식 Simulator 관측을 맡는다. E1/E2 구현 검사를 별도 리뷰로 반복하지 않는다. 어떤 단계가 실제로 입증됐는지 결과를 나눠 기록한다.

## 브라우저 재현과 수정 반복

핵심 사용자 동선의 실제 동작률, 버튼·입력·응답 누락, 로딩 정지, 새로고침·재접속·API 실패를 검증하거나 고치는 요청은 [demo1-meta-display-browser-repair](../demo1-meta-display-browser-repair/SKILL.md)의 시나리오·증거 계약을 사용한다. 이 역할이 실행 관측을 계속 소유하며 별도 단계/심사/selector를 만들지 않는다. 승인된 최소 수정은 기존 sync-client/source-owner gate로 연결하고 같은 실패 동선을 브라우저에서 다시 확인한다. 스킬 제작 요청에서는 이 실행 절차를 작성·검증하는 범위를 지킨다.

## 서버 시작과 자동 준비

사용자가 Display 서버를 켜거나 필요한 서비스를 자동으로 준비하라고 하면 [startup-contract.md](references/startup-contract.md)를 읽고 그 실행 호스트에서 진단 → 기존 서버 시작 → 준비 확인을 수행한다. 시작 권한이 이미 있으면 같은 승인을 다시 묻지 않는다. Ollama는 기존 `LocalLlmProcessManager`에 맡긴다. 읽기 전용 `scripts/startup_doctor.py`는 Java·Ollama·환경변수 존재·loopback health만 관측하며 서비스를 실행하거나 설정을 변경하지 않는다. 원격 서버를 Notebook의 localhost로 대신 검사하지 않는다. 진단 결과만으로 실제 sync나 Desktop 완료를 기록하지 않는다.

## 현재 증거부터

[공유 소스 계약](../demo1-meta-display-webapp/references/source-contract.md)의 `Separate proof stages`, `Metrics`, `Sessions`, `UI`와 [재개 계약](../demo1-meta-display-webapp/references/continuation-contract.md)의 현재 target/check 목록을 사용한다. 실제 소스·도구가 달라졌으면 해당 항목만 재확인한다. 기존 E1/E2 근거가 현재 파일과 일치하면 재사용한다.

직접 진입해도 현재 입력에 맞는 selector 결과가 없으면 `python .\.agents\skills\demo1-meta-display-webapp\scripts\next_step.py --root .`를 한 번 실행한다. 이번 세션의 최신 결과는 재사용하고 관련 검증 근거를 기록한 뒤 한 번 갱신한다. 결과에 미충족 접수/선행 조건이 있으면 먼저 충족하며, 이 역할 선택만을 이유로 resume를 왕복하거나 검사를 중복하지 않는다.

## E3: 실제 앱과 sync

1. 실제 Spring의 `/assets/display/index.html`을 열고 선언된 meta/manifest/icon과 첫 화면을 확인한다. 정적 파일 서버만 열렸다면 Spring 검증으로 기록하지 않는다. 기존 프로세스를 임의로 재시작하지 않는다.
2. 명시적 질문 전송으로 실제 `/api/chat/sync`의 status, 응답 shape, 요청 수, 오류 처리를 확인한다. 안전한 테스트 입력을 쓰며 원문 질문·답변은 관측 후 보고서에 복사하지 않는다. fixture 결과와 실제 교환을 구분한다.
3. 동일 브라우저 맥락의 다음 질문에서 서버 sessionId가 이어지는지 확인한다. cookie 값은 읽거나 출력하지 않는다. 403/409/429/503은 현재 원인으로 분류한다. admission 503을 숨기려고 API/security/Redis 설정을 바꾸지 않는다.
4. 실제 왕복 시간·sourceCount·httpStatus·requestId만 allowlist로 기록한다. server stage timing이 없으면 evidence_needed다. modelUsed/HTTP200/화면 답변만으로 provider attempt/response 계보를 주장하지 않는다.

## E4: 공식 Simulator

실제 사용 가능한 Browser와 공식 Simulator 확장으로 앱을 열고 방향키·Enter·Escape, focus, 답변 카드, 입력과 오류 상태를 관측한다. 확장 존재와 실제 사용을 구분한다. 원본 공식 toolkit/setup은 공유 source-contract의 링크를 사용하며 변경 가능한 규격은 구현 시 공식 자료로 다시 확인한다.

확장이 없거나 실행할 수 없으면 `simulator-unavailable`로 E4만 보류한다. custom 600×600 preview나 합성 테스트를 official-simulator kind로 기록하지 않는다. 실기기가 없으면 렌즈 가독성·Neural Band·계정/지역·firmware 조건은 미확인이다. 이 스킬 적용 자체는 앱/계정 설치나 외부 공개 요청이 아니다.

## 기록과 후속 작업

실행한 항목만 기존 E3/E4 기록으로 남긴다. observedAt은 실제 시각이고 evidence/target hash는 검증한 파일을 가리켜야 한다. 24시간 freshness나 알려진 runtime 변경 때문에 다시 확인할 때도 과거 근거는 보존한다. 일부만 끝났으면 전체 단계 PASS를 만들지 않는다.

source-backed client 결함을 찾으면 현재 사용자 수정 범위에서 [sync client](../demo1-meta-display-sync-client/SKILL.md)로 그 결함만 연결한다. 기록 문제는 [재개 스킬](../demo1-meta-display-resume/SKILL.md)로 연결한다. 현재 증거를 재사용하고 같은 검사를 다시 승인·심사받는 구조를 만들지 않는다.

`clientTests/localSync/sessionContinuity/officialSimulator/publicHttps/hardware/runtimeLineageVerdict/desktopFinalProof`를 따로 출력한다. 공개 HTTPS와 실기기는 기존 E5 범위이며 E4 완료로 자동 실행하지 않는다. provider lineage는 현재 correlated prompt/options와 attempt/response 근거가 없으면 HOLD다.

## 운영 계약

- 입력: `userScope, selectedStage, currentTargets, existingTestEvidence, browserAvailability, runtimeEvidence`.
- 출력: `checkResults(command/expected/observed/owner), stageEvidence, unmetChecks, nextSingleProof, desktopFinalProof`.
- owner/mutation: 기본 관측 및 현재 실행자의 redacted 검증 기록만. API·DB·보안·credential·배포 변경은 이 역할의 기본 표면이 아니다. 실제 sync는 사용자 요청한 로컬 검증 범위의 애플리케이션 동작이다.
- 한도: 초기 조사 15분/20개 근거, 진단 30초, Node 120초/Gradle 단계 15분 등 기존 작업서 timeout, 전체는 현재 GoalContract 한도. 보고 30행.
- 실패: browser-runtime-unavailable, session-forbidden, request-conflict, rate-limited, admission-unavailable, simulator-unavailable, provider-lineage-missing을 구분한다. 각 의존 단계만 fail-closed로 보류한다.
- redaction/rollback: 기존 scanner는 count만 기록한다. 질문/답변/토큰/cookie/internal path 원문을 남기지 않는다. 이번 관측 기록만 정정하고 실제 guard/원본 증거는 보존한다. 스킬 제거는 이 폴더·역할 참조·task binding 항목만 복구한다.
- 중복 방지 근거: E3/E4 실제 실행 판단만 분리했다. 기존 client fixture·Browser 도구·공식 toolkit·Desktop proof gate를 재사용한다.
- 반증 사례: fixture GREEN으로 실제 RAG를 PASS 처리하거나, ordinary viewport를 공식 Simulator로 기록하거나, 없는 latency를 0으로 채우면 실패다.
