[DOT-BRIEF]
외부 API: 없음
PLUGIN_USAGE:
- Computer Use: USED(사용자 PC Chrome 인벤토리와 기존 /chat 읽기; 쓰기·질문 전송 없음)
- Superpowers: USED(위임 예외 확인; 기존 seam 구조 RED와 focused baseline 검증)
- Meta Wearables: NOT_USED
- GLM: NOT_USED

Project Root: C:\AbandonWare\demo-1\demo-1\src
Goal key: CHAT_TRACE_DEFAULT_VISIBILITY_20261007
작성 작업: chat-trace-default-visibility-20261007-3fba4fd7
상태: 제품 수정 HOLD(source-target-overlap). 승인된 대체 산출물인 이 지시서만 작성했다.
기준일: 2026-10-07 UTC. 이 문서는 새 목표이며 다른 완료 작업을 재개하지 않는다.

## Goal

메인 /chat의 신규 방문·미설정 사용자에게 '각 답변의 디버깅 트레이스 표시'를 기본 체크한다.
답변마다 '이 답변 추적'의 짧은 요약을 클릭 없이 보여 면접관이 실제 실행 모델,
실제로 관측된 Brave/Naver 검색 상태, 인용 가능한 근거 수, NORMAL/STRIKE를 확인하게 한다.
명시적으로 저장한 OFF는 reload·새 대화·계정 경계에서도 유지한다.
표시 토글은 메모리 동의·수집·입력과 독립이며 기존 대화와 개인 설정을 보존한다.

## 현재 근거 / Facts

1. 활성 sourceSet은 build.gradle.kts:775의 main/java, main/resources와 src/test/java다.
   Spring Boot 3.3.4, dev.langchain4j 1.0.1 두 선언을 확인했다. 비활성 트리로 패치하지 않는다.
2. main/resources/templates/chat-ui.html:74의 '답변별 디버깅'은 기본 접힌 details다.
   :77의 chatDiagnosticsEnabled 조건 아래 :79의 trace checkbox에 checked 기본값이 없다.
3. main/resources/static/js/chat-trace-ui.js:19의 enabled()는 diagnostics DOM 존재와
   checkbox.checked만 확인한다. 현재 파일에 trace ON/OFF의 저장·복원 처리가 없다.
4. main/resources/static/js/chat.js:1372 currentControlSettings와 :1603의 복원 함수,
   main/resources/static/js/chat-settings-bridge.js:18 KEYS 및 :89 PREFERENCE_KEYS,
   main/java/com/example/lms/service/ChatPreferenceService.java:17 KEYS에 trace 표시 설정이 없다.
   '저장된 OFF를 유지했다'는 현재 소스 기준으로 아직 증명할 수 없다.
5. 기존 개인 설정은 서버 /api/settings/preferences와 ownerScopeId 검증을 사용한다.
   chat-settings-bridge.js:236의 begin은 기존 대화 설정을 보존하고 새 대화에 기본값을 적용한다.
   :260의 owner 변경 확인, :267의 unscoped cache 비권위 규칙과 :327의 generation 폐기를 보존한다.
6. chat-trace-ui.js:48의 outer details는 기본 닫혀 있고 metadata/signals/body가 내부에 있다.
   :75의 toggle과 :505의 restore에서 열려 있으면 loadSnapshot이 시작된다.
   따라서 outer panel.open=true만 추가하면 과거 답변 상세 fetch가 자동으로 발생한다.
7. chat-trace-ui.js:6의 MAX_SNAPSHOT_FETCHES=2, :8 timeout=12000ms,
   :511의 enabled/loaded/loading/isConnected 검사와 :596 dispose/abort를 재사용한다.
   chat.js:1267 refreshVisibleTurnTraces의 session/hydration/generation 검사를 유지한다.
8. 복원된 turnTraces.fields의 allowlist는 요청된 네 가지 사실을 모두 포함하지 않는다.
   metadata pointer/projection의 현재 allowlist와 실제 producer 값을 먼저 확인해야 한다.
   live chat.js:6165의 observedModel guard를 참고하되 요청 selector를 실제 모델로 대체하지 않는다.
   :3568의 agentWebSearch OK/FAIL_SOFT/SKIPPED는 집계 상태이며 Brave/Naver 개별 증거가 아니다.
   메시지 executionMode의 AUTO/STRIKE/SELF_ASK와 orchestration NORMAL/STRIKE를 혼동하지 않는다.
9. 사용자 PC Chrome extension 연결과 기존 공개 /chat 탭 읽기를 확인했다.
   기존 탭의 trace toggle은 checked=true였고 답변 추적은 이미 펼쳐져 있었다.
   기존 사용자 상태이므로 신규 방문 기본값·설정 저장 성공의 증거가 아니다.
   fresh 로컬 /chat은 ERR_CONNECTION_REFUSED, fresh 공개 /chat은 ERR_BLOCKED_BY_CLIENT다.
   fresh UI/설정/계정 매트릭스는 NOT_RUN. 다른 브라우저나 합성 DOM을 사용자 PC 증거로 바꾸지 않는다.
10. target-scoped lease status exit7: 충돌 경로는 chat.js와 chat-ui.html 두 개,
    conflictingLeaseCount=1, unrelatedLeaseCount=2, repositoryWideHold=false다.
    owner topic=context-export, task=context-export-8c75705b,
    leaseId=996001168f1a47ada37cb09d68682706,
    expiresAtUtc=2026-10-07T03:47:38.0665106Z(ETA 아님).
    강제 해제하지 않았다. 기존 change-plane 도구로 release-request를 1회 기록했다.
    fingerprint=cf-c532caf69b2753a1, eventSeq=65.

## Work 파일:줄 / 단계

### W0 현재 소유권과 설정 사실 재확인

- AGENTS.md 및 관련 .agents/skills를 읽고 demo1_vibe_skill_router.py로 primary를 resolve한다.
  이번 resolve는 intent=null이었으며 억지로 다른 제품 route를 붙이지 않았다.
- 실제 target manifest와 preimage hash로 lease status를 다시 확인한다.
  live context-export lease 또는 hash drift가 남아 있으면 제품 변경을 시작하지 않는다.
- 아래 근거의 현재 hash와 줄 번호를 다시 확인한다. 다른 작업의 이미 수정된 hunk를 보존한다.
- 질문·모델 생성·서버 재시작 없이 가능한 focused 검증부터 진행한다.

### W1 미설정 기본 ON과 명시적 OFF

- main/resources/templates/chat-ui.html:74, :79 — 기존 label/checkbox와 /chat 배치 재사용.
- main/resources/static/js/chat-trace-ui.js:19 — 표시 판단과 초기화 seam 재사용.
- main/resources/static/js/chat-settings-bridge.js:89, :236 — 개인 설정 및 owner 검증 seam 재사용.
- main/java/com/example/lms/service/ChatPreferenceService.java:17, :95 — 필요할 때만 기존
  allowlist/boolean 검증에 최소 변경. 먼저 lease·preimage·설정 소유권을 새 scope로 확인한다.
- 표시용 true/false/unset을 구분한다. 명시적 false를 truthy fallback으로 덮어쓰지 않는다.
  미설정 기본값을 page load에서 사용자 override로 저장하지 않는다.
  익명 owner와 로그인 owner 사이에 전역·unscoped 설정이 누출되지 않게 한다.
  기존 모델/검색/RAG/개인 기본값/세션 복원 우선순위를 바꾸지 않는다.
  새로운 표시 값이 생성 payload나 memoryMode 의미를 변경하지 않게 한다.

### W2 자동 요약과 기존 상세 lazy-load

- main/resources/static/js/chat-trace-ui.js:48, :434, :465, :511, :596 — 기존 답변별 slot,
  WeakMap, upsert/restore, dispose/abort를 재사용한다.
- main/resources/static/js/chat.js:1219, :1267, :1291 — 복원 포인터와 토글 갱신 seam 유지.
- main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java:73, :160, :321 —
  summary allowlist와 diag 분리·projection 검증 경계. observedModel(:199), orch.mode(:63),
  prompt.citableEvidenceCount(:50)는 diagnostics로 분리되어 현재 fields에 전달되지 않는다.
- main/java/com/example/lms/api/ChatSessionDetailResponseBuilder.java:98, :133, :189 —
  pointer.projection, 실행 영수증 복원, modelUsed/sessionId 추가 경계.
- main/java/com/example/lms/api/ChatStreamSignalBuilder.java:179, :210 —
  실행 영수증과 집계 agentWebSearch producer. 제공자별 실제 관측 증거와 별도로 판단한다.
- 기본 ON에서는 안전한 짧은 요약이 클릭 없이 보이게 한다.
  긴 원시 로그·기존 상세는 사용자 상세 열기에서만 기존 lazy-load 경로로 가져온다.
  자동 요약 표시가 outer details의 기존 fetch gate를 열지 않게 한다.
- 실제 답변에 결속된 live/restored allowlisted projection을 사용한다.
  현재 metadata가 부족하면 기존 producer/projection allowlist를 최소 확장하거나
  그 항목은 NOT_OBSERVED로 표시한다. 별도 전 답변 fetch 루프를 추가하지 않는다.
- 관측 모델, Brave와 Naver 개별 관측, citableEvidenceCount, orchestration mode의
  출처와 의미를 각각 확인한다. requestedModel·requested executionMode·candidate count·
  returned search count를 실제 모델·NORMAL/STRIKE·인용 수로 승격하지 않는다.
- NOT_OBSERVED는 OFF/0/NORMAL이 아니다. 관측된 false/0와 없는 필드를 구분한다.
- 기존 DOMPurify, 크기 제한, field allowlist, provenance, redaction 경계를 보존한다.
  비밀 값·내부 사고·원시 시스템 prompt를 summary나 HTML/SSE에 추가하지 않는다.

### W3 RED → 최소 patch → focused GREEN → 독립 review → currenthash

- src/test/js/chat-trace-ui.test.cjs — 기본값, ON/OFF/unset, no-click summary, lazy detail 테스트.
- src/test/js/chat-trace-restore.test.cjs — 답변 소유 포인터, invalid/missing metadata, 복원 경계.
- src/test/js/settings-core.test.cjs 및 src/test/js/settings-ux.test.cjs — 기존 우선순위와 owner 경계.
- 필요 producer를 변경하면 그 producer의 기존 focused 테스트에 관측·미관측/0 경계를 추가한다.
  blanket suite는 실행하지 않는다. Java 변경의 실제 build/live 증거가 없으면 NOT_RUN을 유지한다.
- focused command: node --test src/test/js/chat-trace-ui.test.cjs src/test/js/chat-trace-restore.test.cjs
  이후 바뀐 설정·projection의 기존 focused suites만 추가한다.
- read-only 독립 reviewer에게 OFF 복원, raw-log fetch, unknown 오표시, listener/abort 누수,
  owner 변경, memory 독립성과 기존 hunk 보존을 반증 검토시킨다.
- 최종 source/test currenthash를 다시 읽고 검증 당시 hash와 바뀌면 이전 PASS를 승격하지 않는다.
  source/built/served/running은 각각 증거로 분리한다.

## Acceptance

1. 새 방문/unset은 checkbox ON이고 답변의 짧은 요약이 클릭 없이 보인다.
2. 명시적 OFF를 저장한 뒤 reload·새 대화·세션 전환에서도 OFF를 보존한다.
   명시적 ON도 roundtrip하며 unset 복원은 기본 ON이다.
3. owner A와 B, 익명과 로그인 owner 전환에서 이전 owner의 OFF/ON이 누출되지 않는다.
4. 기존 대화 선택·개인 기본값·현재 대화 설정·브라우저 reload 동작과 우선순위를 보존한다.
5. memory 동의·수집·입력은 trace 표시 toggle ON/OFF와 독립이다.
6. 실제 model/provider/search/citable-count/mode를 관측한 경우만 값으로 표시한다.
   unknown, candidate count, requested selector가 실제 값으로 둔갑하지 않는다.
7. 과거 답변을 다수 복원할 때 클릭 없는 요약은 추가 raw snapshot fetch=0이다.
   상세 클릭은 기존 fetch 경로만 사용하며 중복 fetch/listener와 폴링 증가가 없다.
   OFF·페이지 이탈·session/account 전환에서 in-flight fetch와 late response를 차단한다.
8. fresh 사용자 PC 브라우저에서 unset/OFF/ON/reload/session/owner matrix를 확인한다.
   UI 지원 또는 접속이 안 되면 NOT_RUN으로 보고한다. 모델 호출이나 재시작으로 우회하지 않는다.
9. focused GREEN과 독립 review, 현재 hash를 기록한다. 기존 baseline PASS만으로 신규 기능 완료라 하지 않는다.
10. /chat 외 Meta Display·검색 실행·모델 라우팅 동작을 변경하지 않는다.

## 금지 / COMMON_RULES

다른 작업 소유 파일 덮어쓰기, live lease 강제 해제, 광범위 refactor, 중복 wrapper/route/framework,
모델 호출, 서버 restart/kill, commit/push, 비밀 파일 읽기·노출, 가짜 UI 성공은 금지다.
Codex 앱 렉/예비군/다른 완료 목표를 재개하지 않는다. PROTO_OPEN과 기존 인증 경계를 보존한다.
검증 중 기존 사용자의 실제 탭은 읽기만 한다. 설정 roundtrip 검증은 격리한 테스트 상태에서 수행한다.

## HOLD

현재 HOLD는 /chat 소스 소유권 충돌에 한정한다. 제품 source 수정 0, build/restart/kill/model 호출 0.
lease 해제 후에도 새 target manifest와 현재 preimage/hash를 확인하기 전에는 제품 적용하지 않는다.
충돌이 계속되거나 필요한 관측 projection/설정 경계를 확정할 수 없으면 이 지시서와 근거를 인계한다.
다른 파일에 우회 구현을 넣지 않는다. 사용자에게 동일 승인을 반복 요청하지 않는다.

## 이번 실행 결과와 evidence

- structural RED: 0 pass / 2 fail / exit1. unchecked default와 closed summary의 소스 구조만 재현했다.
  outer panel 자동 open을 권장하는 테스트가 아니며 아직 제품 patch/GREEN은 없다.
- 독립 focused baseline: 28 tests / 28 pass / 0 fail / 0 skip / exit0. 기존 동작만 검증했다.
- UI: 기존 공개 탭 읽기 OBSERVED; fresh 브라우저 및 신규 기능 matrix NOT_RUN.
- lease proof: data/agent-handoff/codex-autonomy/chat-trace-default-visibility-20261007-3fba4fd7/lease-status.json
- RED proof: data/agent-handoff/codex-autonomy/chat-trace-default-visibility-20261007-3fba4fd7/structural-red.json
- source baseline: data/agent-handoff/codex-autonomy/chat-trace-default-visibility-20261007-3fba4fd7/source-hashes.json
- 독립 review와 최종 hash는 같은 task 디렉터리에 보존한다.

독립 review 시 source/test SHA-256 (재개 시 현재 파일과 다시 비교):

| 파일 | SHA-256 |
|---|---|
| main/resources/static/js/chat-trace-ui.js | f09a374e9c8b94e91fb318987bb1918d3847b89efd54e03104a9fec8841d2076 |
| main/resources/static/js/chat.js | 6aaf88a048a59474e40fd7ca2fcf6e2ccba6e997766cc8a4b50b5f5ee6529b43 |
| main/resources/static/js/chat-trace-dock.js | f99e86cf1c2b85158cbe65420197ee84b33d64503b498373b922ae3f6ddae0c7 |
| main/resources/templates/chat-ui.html | 019dca83e321f99780c29044b3cd95a07ff2db08a8e27944bb44bd84f5981e2e |
| src/test/js/chat-trace-ui.test.cjs | 890f4c4cc4456651d4fe45371dc5a648e03829f882d76db5551173e63b15edab |
| src/test/js/chat-trace-restore.test.cjs | 77bf7e294ebcf7750c5af51540962f28451664a5b52833d8c8c6369f6ccd71ef |

제품 기능 상태는 미구현/HOLD이며, 이 파일은 실행 가능한 후속 지시서다.
