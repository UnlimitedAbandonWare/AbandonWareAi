---
name: demo1-meta-display-browser-repair
description: Use when demo-1 Meta Display needs real-browser functional QA
---

# Meta Display 브라우저 재현과 수정

완료 기준은 **현재 실행 중인 앱에서 사용자의 핵심 동선이 실제로 끝까지 동작함**이다. 음성·기능 확장보다 입력 → UI 이벤트 → 백엔드 요청 → RAG/LLM 처리 → 응답 → Display 화면 표시를 우선한다.

이 스킬은 [실제 동작 검증 역할](../demo1-meta-display-verification/SKILL.md)의 브라우저 실행 절차다. E0–E4 단계 소유자, selector, source gate를 추가하지 않는다. 직접 호출도 같은 역할에 연결하며 스킬 제작/검토 요청에서는 런타임 변경까지 자동 확대하지 않는다.

## 시작과 도구 선택

1. 현재 요청의 실행/수정/검토 범위, 실제 서버 호스트·포트·프로세스 소유자, branch/HEAD, 대상 해시를 확인한다. [기존 진입점](../demo1-meta-display-webapp/SKILL.md)의 selector를 현재 작업에서 한 번만 사용한다. stale receipt는 현재 관측으로 다시 판단하고 독립 브라우저 검사를 막는 전체 HOLD로 만들지 않는다.
2. [tool-routing.md](references/tool-routing.md)로 **Codex 브라우저 실행 1개 + 필요한 독립 읽기 역할 최대 1개**를 선택한다. 모델 이름이나 태그 수를 호출 수로 바꾸지 않는다. 부모 Codex가 브라우저·패치·최종 판정을 소유한다.
3. 기본 실행 예산은 30분, 논리적 전송 최대 12회, 소유 서버 재시작 최대 1회다. 사용자 한도가 있으면 우선한다. 재현 전에 요청별 대기 상한과 필요한 시나리오를 기록하고, 성공·새 가설 없는 반복 실패·외부 blocker에서 멈춘다. 자동 watcher나 유료 무한 반복은 만들지 않는다.
4. 실행 중인 Spring의 `/assets/display/index.html`을 연다. 포트는 현재 listener/state/실행기 출력으로 확인하고 8080이나 과거 예시를 가정하지 않는다. 서버가 없고 실행 검증이 승인된 작업이면 [기존 시작 계약](../demo1-meta-display-verification/references/startup-contract.md)으로 준비한다. 실행 불가이면 `evidence_needed`와 정확한 다음 검사를 남기고 가능한 독립 작업을 계속한다.

## 브라우저 → 최소 패치 → 같은 브라우저

1. [scenarios.md](references/scenarios.md)의 현재 기능 목록과 필수 사례를 고정한다. 실제 페이지를 탐색하고 새 AX/DOM 관측에서 얻은 요소로 클릭·입력·전송한다. JS 함수 직접 호출이나 HTTP probe를 사용자 조작 증거로 대체하지 않는다. 화면 깨짐은 스크린샷으로도 확인한다.
2. 정상 사례부터 수행한다. 동일 합성 입력의 의미 조건, 버튼/키 동작, 실제 요청 수, 상태 변화, 답변 카드·출처·focus를 확인한다. 단순 HTTP 200, modelUsed 또는 화면 문자열만으로 정상 RAG/LLM 성공을 선언하지 않는다.
3. 실패 즉시 같은 시나리오의 UI 상태, 요청/응답 메타데이터, 시간, 기존 서버 로그/trace를 [evidence.md](references/evidence.md) 형식으로 묶는다. 관측된 마지막 성공 구간과 첫 실패/미관측 구간을 구별하고 가설 하나를 선택한다. 오류 원문·cookie·질문/답변 전문을 복사하지 않는다.
4. 현재 source-backed 원인이 입증되고 수정 범위에 있으면 기존 [세 역할 preflight](../demo1-source-edit-three-way-preflight/SKILL.md)의 stable APPLY와 target-scoped owner/lease/preimage gate를 통과한다. client 결함은 [sync-client](../demo1-meta-display-sync-client/SKILL.md)의 기존 소유자에서 최소 패치한다. 승인된 수정은 반복 허가 없이 이어간다. 재현이 환경에 막히면 로컬 수정을 입증해도 브라우저 복구 완료로 기록하지 않는다.
5. 기존 checkpoint에 위험 점수·정확한 preimage·검증·복구 경로를 기록하고 수정 직후 postimage를 seal한다. 집중 테스트 후 변경된 자산/서버가 실제 실행본인지 확인한다. 같은 실패 시나리오, 정상 사례, 영향받는 인접 사례를 **브라우저에서 다시 수행**한다. 테스트 실패는 기존 guarded recovery로 처리하며 다른 작성자의 변경은 보존한다.
6. 결과가 나오면 다음 미완료 사례로 진행한다. 동일 blocker를 반복 조회하거나 이미 동작하는 RAG·Display를 재설계하지 않는다. 오류 주입을 제거하고 정상 왕복을 확인한 뒤 관측 자료와 실제 명령 결과를 함께 기록한다.

## 판정과 종료

- 결과는 `pass | fail | degraded | evidence_needed | not_run`으로 구분한다. 정상 질문에 fallback만 표시되면 `degraded`이며 정상 성공률에 넣지 않는다. 실패 처리 사례의 합격은 그 오류가 안전하게 표시·복구됐다는 뜻이다.
- `browserFunctionalStatus=verified`는 합의한 필수 시나리오가 현재 실행본에서 모두 통과하고, 남은 핵심 실패·미관측 구간·회귀가 없을 때만 사용한다. 소스/fixture green은 `clientTests`만 입증한다.
- `browserFunctionalStatus`, `normalAnswerSuccess`, `providerAttemptEvidence`, `officialSimulator`, `publicHttps`, `hardware`를 따로 보고한다. 600×600 일반 브라우저는 공식 Simulator/실기기 증거가 아니다.
- 최종 보고는 변경 경로, 실제 통과/실패/미실행 수와 분모, 수정 전후 같은 사례의 증거, 첫 끊긴 구간, 잔여 blocker와 다음 확인 1개를 포함한다. partial 결과를 E3/E4 PASS로 저장하거나 기존 receipt의 날짜/해시만 갱신하지 않는다.

오판 신호: fixture를 live로 표기, timeout 후 새 키로 재전송, 503을 숨기려고 admission 해제, 다른 작업의 서버 종료, 여러 모델의 동의를 실행 증거로 사용. 이런 경우 해당 판정/행동을 중단하고 현재 관측으로 돌아간다.
