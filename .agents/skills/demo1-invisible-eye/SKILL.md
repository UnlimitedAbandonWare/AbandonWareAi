---
name: demo1-invisible-eye
description: Use when demo-1 Desktop coding or debugging involves hidden conditions
---

# Demo1 Invisible Eye — Desktop 자동 조사

겉으로 드러난 동작에서 내부 구조와 조건을 역으로 추론하고, 그 추론이 틀릴 관찰을 찾는다. 목표는 **새로운 설명과 검증 가능한 예측**이다. 직관은 후보를 만드는 데 쓰고, 확정은 증거에 맡긴다.

## 범위와 소유권

이 저장소 스킬은 분석 절차와 요청된 보고서를 소유한다. 기본은 읽기와 허용된 진단이다. 호출 자체는 소스 수정, debugger attach, 드라이버/Lua 실행, feature flag 변경 권한이 아니다. 이미 허용된 안전한 작업은 반복 승인 없이 진행한다. 필요한 변경은 현재 작업의 소유권·검증·rollback 절차를 따른다.

단순 설명·문구 수정·원인이 이미 입증된 작은 수정에는 전체 절차를 적용하지 않는다. 사람의 심리를 판정하는 용도로 전환하지 않는다. 기술적 단서가 없으면 정확한 제품·빌드·관찰 하나를 요청하고 사실을 만들지 않는다.

## Desktop 요청의 자동 적용

저장소의 모든 새 Desktop 지시에서 요청 범위와 이 스킬의 관련성을 짧게 판단한다. 구현·수정 요청의 내부 동작이 불확실하거나 위 trigger가 있으면 이 스킬을 자동 선택한다. 이미 원인이 입증된 작은 수정은 기존 구현 절차로 바로 이어간다. 단순 설명·문구 수정에는 전체 조사를 강제하지 않는다.

명시적으로 수정이 요청된 작업에서는 아래 분석 결과를 현재 source owner의 기존 수정 경계에 넘기고 최소 패치·집중 검증·복원 증거까지 완료한다. 분석을 끝냈다는 이유만으로 지시서 제출에서 멈추지 않는다. 같은 EvidenceSnapshot과 기존 단일 심사 결과를 재사용하며 별도의 심사·lease 프로토콜을 만들지 않는다. 읽기·조사만 요청된 작업은 발견 사항과 다음 증명으로 끝낸다. 자동 선택 자체는 새로운 소스 수정 권한이 아니다.

현재 저장소의 활성 sourceSet과 실제 call path를 먼저 확인한다. 최종 RAG 프롬프트는 기존 PromptBuilder/PromptContext 경계를 유지한다. 애플리케이션 수정은 AGENTS.md의 기존 소스 소유권·선행 게이트·검증 규칙에 따르며 index lock, 겹치는 작성자, 변경된 preimage를 우회하지 않는다. 금지된 공개 API·DB·credential 변경, 커밋·푸시·배포·외부 메시지를 자동 실행하지 않는다.

이 패키지는 기존 개인 invisible-eye의 저장소 배포본이다. 개인 스킬 경로에 의존하지 않으며 이 폴더의 참조를 사용한다. Notebook 개인 스킬과의 이름 충돌을 피하려고 demo1-invisible-eye라는 이름을 사용한다. 저장소 지침과 패키지가 준비됐다는 사실과 실제 Desktop 세션이 읽고 사용했다는 증거는 구분한다.

## 조사 절차

1. **주장을 분해한다.** 들은 표현과 확인된 식별자를 분리한다. 제품, 모듈, 버전, 실행 환경, 관찰 시점을 고정한다. 불명확한 이름을 익숙한 라이브러리명으로 자동 교정하지 않는다. 전문성이나 여러 에이전트의 동의는 독립 증거가 아니다.
2. **같은 증거를 한 번만 수집한다.** 파일·명령·공식 자료에 E1… ID와 build/revision, 관찰 위치를 붙인다. 현재 파일과 실행 출력을 우선한다. 외부 제품의 불확실한 동작은 해당 버전의 공식 자료로 확인한다. 검색 결과가 없다는 것은 부재 증명이 아니다.
3. **관찰에서 내부로 들어간다.** 입출력·횟수·순서·시간의 기대와 차이를 찾고, 최초 분기 경계를 따라 호출자 → 등록/설정 → 실행기 → 결과를 연결한다. 각 화살표는 근거 또는 `unknown`이다. 아래 단계를 따로 판정한다.

   | 단계 | 필요한 구별 |
   |---|---|
   | present / included | 문자열·소스 존재와 현재 배포 빌드 포함 여부 |
   | reachable / enabled | 호출·등록 경로 존재와 현재 조건·권한·상태 충족 여부 |
   | executed / causal | 실제 실행 관찰과 현상의 원인이라는 대조 증거 |

4. **가능한 구조를 예측한다.** “이 현상이 맞다면 내부에 어떤 상태·분기·전파 경로가 있어야 하는가?”를 묻는다. 대응되는 정상 사례·이전 버전·켜짐/꺼짐 조건과 비교해 잠재 기능이나 누락된 경계를 후보로 만든다. 후보마다 예상되는 관찰을 먼저 적는다. 의미 없는 가능성 목록으로 끝내지 않는다. 조건부 기능은 조건식과 **평가 시점·행동 순서·재평가 여부**를 함께 기록한다. 현재 플래그 값과 초기화 때 값을 구분한다.
5. **2–4개 경쟁 가설을 만든다.** `설명 / 지지·충돌 E-ID / 예측 / 반증 조건`을 각 행에 기록한다. 가장 강한 대안과 관측 오류를 검토한다. 같은 판정 축에서는 구별되는 가설로 만들고, 재시도+이중 등록처럼 공존 가능한 원인은 별도 축 또는 조합 가설로 보존한다. 단일 원인을 강제하지 않는다.
6. **가장 작은 구별 검사 하나를 고른다.** 먼저 `무엇을 셌는가 / 어느 계층에서 봤는가 / 실행·시도 식별자 / 관찰 기간 / 빠질 수 있는 기록`을 고정한다. 서로 다른 가설이 그 관찰에서 같은 결과를 예측하면 아직 구별되지 않은 집합으로 남기고, 예측이 갈리는 경계의 관찰 하나를 고른다. 현재 확인된 도구·대상으로 `명령 또는 정확한 관찰 요청 / 고정 조건 / 변경 변수 / 가설별 예상 / timeout / 실제 결과`를 기록한다. 읽기만 허용됐으면 기존 증거를 조사한다. 새 검사는 `proposed`, 제공된 검증 기록의 판독은 `reviewed-evidence`, 이번 작업에서 도구로 실제 수행한 검사만 `executed`다. `executionOwner`와 `performedNow`를 함께 남긴다. 같은 blocker를 상태 변화 없이 반복하지 않는다.
7. **결론과 의도를 분리한다.** 각 주장을 `supported | ruled-out | unknown | confounded`로 판정한다. 구현 제약으로 추론한 설계 목적은 `inferred`, 버전과 출처가 확인된 제작자 설명은 `documented`, 숨은 심리·상업 동기는 근거 없으면 `unknown`이다. 충분한 근거가 있으면 확인한 범위는 분명히 결론내린다.

## 입출력과 예산

입력: `question, exactClaim, authorizedScope, artifactRefs, buildOrRevision, environmentBoundary, observations, budget`.
출력은 **확인된 통찰 → 증거 단계·경계 → 가설 표 → 검사 결과 → 남은 한 가지 증거** 순서다. 필수 필드: `insight, EvidenceLedger, BoundaryMap, MechanismStages, HypothesisMatrix, ProbeResult, mechanismVerdict, intentVerdict, confidence(L/M/H), NextSingleProof`. 관찰이 모호하면 `ObservationContract`, 활성 조건이 쟁점이면 `ConditionModel`을 덧붙인다. 다른 사용자·에이전트에게 전달할 때는 아래 참조의 `InsightCard`로 관찰과 예측을 재현 가능하게 요약한다. 제공되지 않은 다른 대화나 비공개 내부 상태를 본 것처럼 쓰지 않는다.

상세 판정·실험 설계가 필요하면 [mechanism-contract.md](references/mechanism-contract.md)를 읽는다. 원리 설명이나 연구 근거가 요청되면 [research-basis.md](references/research-basis.md)를 읽는다. 기본 상한은 15분, 검사 5회, 검사당 30초, evidence 20행, 최종 900단어다. 사용자 예산이 우선한다. 충분하면 즉시 끝낸다. 근거 없는 확률은 만들지 않는다.

## 실패·정보 보호·복구

`artifact-missing`, `identity-unresolved`, `version-mismatch`, `observation-confounded`, `probe-blocked`, `budget-exhausted`, `mechanism-underdetermined`를 구분한다. 증거 부족은 해당 주장만 보류하고 독립적인 확인은 계속한다. 권한 확대·비밀 노출은 그 작업을 fail-closed로 중단한다.

원문 payload·질의·credential을 복사하지 않는다. allowlist한 count/hash/timing/boolean/reason과 위치를 쓴다. 저장 전 기존 secret scanner 또는 알려진 토큰·개인키·credential 할당 패턴 검사를 재사용하고 탐지 건수만 보고한다. 검사 불가는 `secretScan=evidence_needed`; 안전 확인 전 민감 산출물을 게시하지 않는다.

분석만 수행했다면 런타임 rollback은 없다. 허용된 실험 변경은 해당 실험의 복구 절차를 쓴다. 스킬 제거는 실제 경로와 소유권을 확인한 `demo1-invisible-eye` 폴더만 대상으로 한다.

## 재사용과 검증

Superpowers의 원인 추적·최소 검사와 기존 반증 원칙을 재사용한다. 이 스킬은 **증거 단계, 잠재 실행 조건, 조건별 예측, 의도 분리**를 보완한다. 기존 프로젝트의 EvidenceSnapshot·세 역할 평가·source owner guard를 복제하지 않는다. 수정이 필요할 때 기존 작업 절차에 결과를 전달한다.

스킬 변경을 검증할 때만 [evaluation-cases.md](references/evaluation-cases.md)를 사용한다. 소스 문자열만으로 실제 실행을 확정하거나, 충분한 대조 증거에도 결론을 계속 보류하거나, 단순 문구 수정에 조사 절차를 강요하면 이 스킬의 실패다.
