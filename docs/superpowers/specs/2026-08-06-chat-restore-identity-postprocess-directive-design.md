# Chat Restore Identity 후처리 소스수정 지시서 설계

상태: approved for implementation planning — 사용자 승인 2026-08-06, live reconciliation amendment 반영  
작성일: 2026-08-06  
선택안: A — 시작 시 세션 복원 응답의 ID 불일치 차단만 수행

## 1. 목적

다른 Codex 세션이 실행할 수 있는 독립형 후처리 소스수정 지시서를 만든다. 지시서는 저장된 세션 ID와 백엔드 상세 응답의 ID가 다를 때, 시작 복원 경로가 다른 세션의 transcript, 설정, mode badge, active run identity를 현재 세션으로 받아들이지 못하게 하는 한 가지 P0 결함만 다룬다.

이 설계 세션에서는 애플리케이션 소스와 테스트를 수정하지 않는다. 커밋, 스테이징, 브랜치 생성, 푸시도 하지 않는다.

## 2. 현재 증거 스냅샷

- canonical root: C:\AbandonWare\demo-1\demo-1\src
- branch: main
- HEAD: b6ec55d147f7ea4b5296e095b6f052811e1da42a
- .git/index.lock: 없음
- staged file count: 0
- main/resources/static/js/chat.js
  - Git 상태: untracked
  - SHA-256: 99772C961339098BEA68B8FCD1AA415729EA859F69444C45D8C66E0D4F290685
- scripts/chat_ui_stream_contract_tests.js
  - Git 상태: untracked
  - SHA-256: 7C1C0C4B3E2200E787C536E8DE6102CB6D8246560CEE69A79F7F86B3CA473BD9

위 해시는 설계 시점의 증거 앵커다. 실행 세션은 편집 직전에 다시 계산해야 하며, 불일치하면 현재 파일을 재탐침하고 패치를 재조정하기 전까지 HOLD한다. 사용자 소유 변경을 덮어쓰거나 과거 해시의 본문으로 되돌리지 않는다.

## 3. 확인된 결함과 원인

main/resources/static/js/chat.js의 대화형 세션 선택 경로는 validateSessionDetail(candidateId, detail)을 사용한다. 이 함수는 요청 ID와 detail.id를 strictBackendSessionId로 정규화해 정확히 일치하는지, found가 false가 아닌지, messages와 settings 형태가 유효한지를 확인한다.

반면 hydrateRestoredSessionTranscript()는 저장된 sid로 /api/chat/sessions/{sid}?restoreProbe=true를 호출한 뒤 같은 검증을 사용하지 않는다. 현재 구현은 detail.sessionId 또는 detail.id를 받아들이고, 값이 없으면 요청 sid로 대체한다. 그 뒤 다음 변이를 순서대로 수행한다.

1. 반환 ID를 current session으로 기억한다.
2. 반환 messages를 transcript에 렌더링한다.
3. 반환 settings를 적용한다.
4. 반환 ID와 상세 정보로 mode badge를 복원한다.
5. 반환 ID로 저장된 run을 재개한다.

따라서 저장·요청 ID가 41인데 백엔드가 ID 42의 상세를 반환하면, 시작 복원 경로가 42를 현재 세션으로 채택하고 42의 상태를 화면에 반영할 수 있다. 대화형 선택 경로에 추가된 strict identity contract가 더 오래된 시작 복원 경로에는 전파되지 않아 두 복원 경로의 계약이 갈라진 것이 원인이다.

## 4. 선택한 구조

### 활성 작업 단위

다음 두 파일만 미래 구현 세션의 활성 대상이다.

1. main/resources/static/js/chat.js
2. scripts/chat_ui_stream_contract_tests.js

구현은 기존 validateSessionDetail을 재사용하여 hydrateRestoredSessionTranscript()가 어떠한 상태 변이보다 먼저 요청 sid와 응답 detail의 identity 및 형태를 검증하도록 한다. 별도 검증기, 새 저장소 추상화, 새 복원 경로는 만들지 않는다.

### 명시적으로 제외하는 대상

- main/resources/static/chat-ui.html 및 모든 HTML/CSS
- Java controller, service, repository, test
- Gradle 설정과 의존성
- DB, Supabase, 외부 provider, credentials
- agent prompt manifest 등록
- 세션 목록 refresh coalescing 개선
- 대화형 세션 선택 실패의 사용자 알림 개선
- 백엔드 missing-session 응답 문구 정리

제외 항목은 이 설계의 완료 조건이 아니며, 활성 대상 파일을 늘리는 근거로 사용하지 않는다.

## 5. 미래 구현 계약

### RED 계약

Node fixture에 시작 복원 전용 실패 시나리오를 먼저 추가한다.

- 저장·요청 sid는 41이다.
- 상세 응답은 found=true, id=42, sentinel message, settings 및 mode 정보를 포함한다.
- hydrateRestoredSessionTranscript()의 결과는 false여야 한다.
- currentSessionId, sessionStorage, active run identity, transcript, controls, mode badge가 응답 42 때문에 변하지 않아야 한다.
- sentinel message는 렌더링되지 않아야 한다.
- 숫자 42가 저장·선택·렌더링·run resume 대상으로 사용되지 않아야 한다.
- 사용자 가시 상태는 원문 응답이나 민감 정보를 포함하지 않는 고정된 redacted restore-unavailable 계열 문구만 허용한다.

기존 구현에서 이 시나리오가 실패하는 것을 확인한 뒤에만 소스 패치로 이동한다.

### 최소 GREEN 패치

hydrateRestoredSessionTranscript() 안에서 다음 순서를 지킨다.

1. 기존 generation, current session, transcript count 동시성 가드를 유지한다.
2. 기존 found=false 처리와 403/404 reset 의미를 유지한다.
3. 그 다음 validateSessionDetail(sid, detail)을 호출한다.
4. 검증이 null이면 고정된 안전 상태만 표시하고 false를 반환한다.
5. 검증 실패 경로에서는 session ID 저장, active run 변경, message append, settings 적용, badge 복원, run resume를 전혀 실행하지 않는다.
6. 검증된 객체와 요청 sid를 사용해 기존 정상 복원 동작을 계속한다.
7. detail.sessionId 또는 detail.id의 임의 선택 및 ID 누락 시 sid로 대체하는 permissive ownership fallback을 제거한다.

정상 응답 ID가 요청 sid와 같은 happy path, found=false, generation 변경, 이미 존재하는 transcript 보호 동작은 회귀시키지 않는다.

## 6. 실행 절차와 권한 게이트

미래 실행 세션은 다음 순서를 따른다.

1. 가장 가까운 AGENTS.md와 활성 sourceSet을 다시 확인한다.
2. git worktree list, 현재 branch, git status --short, staged 상태, .git/index.lock, PatchDrop pending, source-edit lease를 점검한다.
3. 두 활성 대상 파일을 읽기 전용으로 탐침하고 preimage SHA-256, 기존 guard, 동등 behavioral fixture, Node 결과를 기준으로 verified_no_patch_needed, fixture_only_required, patch_required, HOLD 중 정확히 하나를 고른다.
4. verified_no_patch_needed이면 preflight, lease, 편집 없이 검증 사다리로 이동한다.
5. fixture_only_required이면 테스트 파일의 현재 소유권과 preimage를 즉시 다시 확인하고 동등한 characterization fixture만 apply_patch로 추가한다. 이 경로는 source RED, source preflight, source lease, application-source patch를 주장하지 않는다.
6. patch_required일 때만 demo1-source-edit-three-way-preflight에 따라 하나의 redacted EvidenceSnapshot을 고정하고 정확히 POSITIVE_QUERY, NEGATIVE_QUERY, NEUTRAL_QUERY를 독립 실행한다. A-B와 B-A 순서의 NEUTRAL 판정이 모두 APPLY일 때만 기존 source-owner guard와 lease를 획득하며, 판정이 달라지면 HOLD한다.
7. patch_required 경로는 두 대상의 preimage SHA-256을 apply_patch 직전에 다시 확인하고, Node fixture RED를 증명한 뒤 main/resources/static/js/chat.js에 최소 패치를 적용한다.
8. 선택된 경로에 맞는 GREEN과 회귀 검증을 수행한다.
9. 모든 경로에서 postimage 해시, count-only secret scan, 전체 baseline/final Git 상태와 HEAD, 잠금과 staged 상태를 기록한다.

모든 편집은 apply_patch로 수행한다. git reset, git checkout, 강제 덮어쓰기, 사용자 변경 삭제를 사용하지 않는다. 커밋과 스테이징도 만들지 않는다.

## 7. 검증 사다리

최소 순서는 다음과 같다.

1. node scripts/chat_ui_stream_contract_tests.js
2. 변경 계약과 직접 관련된 focused chat UI test
3. gradlew.bat chatUiTest
4. gradlew.bat classes
5. gradlew.bat :app:classes
6. gradlew.bat bootJar
7. 새 브라우저 세션에서 동일 ID의 정상 reload/restore happy path와 활성 stream/cancel 동작 확인
8. 두 대상 파일과 생성 증거에 대한 count-only secret scan
9. postimage SHA-256, git diff, git status --short, staged count 0, .git/index.lock 없음 확인

Gradle 실행은 Desktop host-specific build output, GRADLE_USER_HOME, project cache isolation 규칙을 따른다. 브라우저에서 답변이 보였다는 사실만으로 identity contract 성공을 선언하지 않고, 동일 입력에 대한 상태 불변성과 정상 복원 의미를 함께 확인한다.

## 8. 오류 처리와 중단 조건

선택된 경로에서 요구되는 다음 조건 중 하나가 발생하면 소스 편집 없이
HOLD한다. no-op과 fixture-only 경로에는 source preflight, NEUTRAL APPLY,
vulnerable-source RED 조건을 적용하지 않는다.

- 대상 preimage가 달라졌고 현재 구현과 테스트를 재대조하지 못함
- .git/index.lock 충돌
- source-edit lease 충돌
- PatchDrop pending 또는 worktree/branch 소유권 충돌
- NEUTRAL 판정 불안정 또는 APPLY 아님
- RED가 기존 구현에서 재현되지 않음
- 검증 실패를 활성 작업 단위 안에서 설명할 수 없음
- secret risk 또는 redaction 위반

검증 실패 시 정확한 작업 단위의 변경만 apply_patch로 되돌린다. 저장소 전체나 사용자 소유 변경을 되돌리지 않는다.

## 9. HOLD 후속 후보

아래는 독립 탐침에서 발견했지만 A안에서 의도적으로 제외한 후속 후보다.

- ChatApiController의 중복된 손상·mojibake missing-session 응답 문구와 이를 고정하지 못하는 테스트
- terminal 시점 refresh 요청이 이미 진행 중인 이전 refresh에 합쳐져 후속 최신화가 누락될 가능성
- 대화형 세션 선택 실패가 false로만 끝나 접근 가능한 오류 알림을 제공하지 않는 문제

각 후보는 별도 현재 증거, 승인, RED 계약을 받아야 하며 이 지시서 실행 중 함께 수정하지 않는다.

## 10. 완료 조건

후처리 소스수정 지시서 자체는 다음을 만족해야 한다.

- 독립 세션이 복사해 실행할 수 있는 완결된 목표, 범위, 증거, RED/GREEN, 검증, 중단, 롤백 계약을 포함한다.
- 활성 대상은 정확히 두 파일이다.
- A안 밖 후보는 HOLD_FOLLOWUP으로만 보존한다.
- 현재 해시를 증거로 제공하되, 미래 세션의 편집 권한으로 오해하지 않게 preimage 재검증을 요구한다.
- 애플리케이션 소스, 테스트, 런타임, DB를 이 설계 세션에서 수정하지 않는다.
- 커밋, 스테이징, 브랜치, 푸시를 만들지 않는다.

실제 실행 세션은 live preimage를 다시 읽은 뒤 다음 세 완료 경로 중
하나만 선택한다.

1. verified_no_patch_needed
   - shared validator guard와 wrong-ID behavioral fixture가 실행 시작 전에
     이미 존재한다.
   - Node, focused/broad Gradle, 정상 Browser happy path, count-only secret,
     stable hash, full Git status/HEAD 검증이 모두 통과한다.
   - source와 fixture diff를 새로 만들지 않는다.
   - 이미 고쳐진 상태이므로 RED 재현이나 소스 패치를 요구하지 않는다.
2. test_contract_added_and_verified
   - source guard는 이미 정확하지만 동등한 behavioral fixture만 없다.
   - 승인된 fixture 한 파일에 characterization contract만 추가하고 전체
     검증 사다리를 통과한다.
   - source diff는 없고 fixture diff만 존재한다.
   - 이미 정상인 source를 일부러 취약하게 만들지 않으며 source RED를
     주장하지 않는다.
3. patched_and_verified
   - vulnerable source에 대해 wrong-ID RED가 재현된다.
   - 승인된 최소 source patch와 필요한 fixture가 적용된다.
   - RED→GREEN, 전체 검증 사다리, Browser happy path, count-only secret,
     postimage, full Git status/HEAD와 무커밋 상태가 모두 충족된다.

Browser에서 실제 회귀가 관찰되면 browserHappyPath=FAIL과
HOLD browser-regression-observed로 판정한다. Browser를 실행할 수 없는
경우에만 evidence_needed를 사용한다. 어떤 경로에서도 staged 변화나
HEAD 변화, 대상 삭제, 새 out-of-scope Git 상태 변화가 있으면 완료로
판정하지 않는다.
