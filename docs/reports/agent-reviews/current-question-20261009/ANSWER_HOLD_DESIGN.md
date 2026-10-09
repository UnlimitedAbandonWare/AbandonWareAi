# 답변 유지시간 선택 — 구현 전 설계

상태: DESIGN_ONLY / application source mutation NOT_RUN. 사용자 최신 지시 “설계 확인 전 구현 금지”를 따른다. Downloads의 3초 grace diff는 참고 자료이며 적용하지 않았다. 기존 tailHold 설정 및 수음/질문 상태 경계를 재사용하는 안이다.

## 현재 확인한 원인 경로

`NovaFocusSettings.Presentation.tailHoldMs`는 ms 단위, 기본 5000, 범위 2000–15000이다. Java와 JS가 같다. 기존 Fold `nf-hold` 입력은 마지막 내용 유지(ms)이며 controls의 기존 presentation 저장과 settingsVersion CAS/read 경로가 이 값을 보존한다. 별도 fadeMs 기본값은 400ms다. 설정값을 3000/5000으로 저장할 수 있으므로 새 DB 필드나 하드코딩 3000 상수는 필요하지 않다.

현재 client Flow는 마지막 글자를 그린 뒤 presentation_done 이벤트를 보내고 그 이후 tailHold를 기다려 페이드한다. tailHold를 다 소비한 뒤 ACK를 보내는 구조가 아니다. lens retainAfterPresentation은 autoFade를 끄므로 Fold와 lens의 사라지는 동작은 현재 동일하지 않다.

서버 State는 유효 presentation_done ACK에서 대기 draft가 있으면 즉시 LISTENING으로 전환한다. 다음 tick에서 질문 확정/정숙 조건이 만족되면 이전 답변을 비운다. 이 경로에는 tailHold 검사가 없다. 따라서 ASR가 이미 받아 둔 후속 발화 때문에 client의 유지 시간보다 빨리 답변이 교체될 수 있다. 기존 StateTest도 ACK 4000→다음 tick 4001에 다음 질문 실행을 기대한다.

20:05:10–55 KST 정제 stage 기록에서 같은 sessionHash `hash:211e685644a3` / epoch 4에 세 번 반복 관측했다:

| presentation_done | 다음 question_confirmed | 간격 |
|---|---|---|
| 20:05:16.582 | 20:05:16.688 | 106ms |
| 20:05:34.769 | 20:05:34.923 | 154ms |
| 20:05:49.013 | 20:05:49.206 | 193ms |

이 기록은 서버의 빠른 다음 질문 전환을 지지한다. 안경의 실제 물리적 가시 시간을 측정한 증거는 아니다. 원문 발화·답변은 export하지 않았다. 상세 allowlist evidence는 hold-design-events.json이다.

## 승인용 최소 설계

1. 기존 tailHoldMs를 **“표시 완료 후 다음 질문으로 교체되기 전 최소 유지 시간”**으로 정의한다. UI는 3초/5초 선택과 기존 2–15초 사용자 지정 값을 제공하고 저장 payload는 기존 3000/5000ms다. 기본값은 현재 5초를 유지한다. 3초를 새 기본으로 강제하지 않는다.
2. 첫 유효 presentation_done ACK에서 해당 답변에 고정된 runPresentation.tailHoldMs로 서버 holdUntil을 계산한다. ACK 전에는 시간을 시작하지 않는다. 중복 ACK·poll·설정 변경은 시간을 연장하지 않는다. 진행 중 설정 변경은 다음 답변부터 적용한다.
3. 유지 중에는 기존 PRESENTING 상태에서 ASR와 대기 질문 수집을 계속한다. 자동 음성의 다음 질문 확정/실행과 기존 답변 초기화만 holdUntil까지 미룬다. 의도한 후속 발화를 버리지 않고 기존 대기 질문 제한을 유지한다. 임의 최소 발화 길이·무조건 음성 폐기 정책을 추가하지 않는다. accepted typed 수동 질문은 기존에 허용되는 시점과 queue-full 계약 안에서 hold를 우회한다. 이미 voice draft가 있으면 focus_next_question_full을 보존하고 이를 덮어쓰거나 버리지 않는다.
4. 마감에는 기존 LISTENING/WAITING으로 전환하고 기존 final/quiet/dedup 조건을 만족한 대기 발화를 한 번 처리한다. ASR 정숙 시간은 별도로 유지한다. followupIdleMs는 기존 presentation_done ACK부터 계산하며 유지 완료 뒤 타이머를 새로 시작하지 않는다. hold가 idle보다 길 때만 암묵적 idle 종료를 hold 마감까지 보호한다. 이후에는 기존 idle 정책을 적용한다.
5. Stop/닫기/epoch·서버·activation 교체는 즉시 처리한다. hold를 기다리지 않는다. 유효 완료 ACK 뒤에는 presentation_unconfirmed timeout을 적용하지 않는다. 이전 ACK/응답으로 hold나 banner를 되살리지 못한다.
6. Fold와 lens는 동일한 **최소 교체 보호 시간**을 사용한다. Fold가 ACK 전송 지연만큼 먼저 fade하지 않도록 필요하면 기존 View에 최소의 남은 hold 시간만 전달해 로컬 fade와 맞춘다. client의 tailHold를 전부 기다린 뒤 ACK하고 서버가 다시 tailHold를 기다리는 이중 유지 구조는 만들지 않는다.

이 안은 N초 전에 자동 음성 질문이 답변을 교체하지 못하게 하는 동작이다. 유지 종료 뒤 이미 받아 둔 발화가 기존 final/quiet/dedup 조건을 만족하면 즉시 다음 질문이 될 수 있다. 에코나 잡음인지를 새로 판별하거나 폐기하는 안은 아니다. 현재 lens의 답변 retention을 **반드시 N초 후 자동 삭제**로 바꾸는 것은 별도의 TTL 정책이므로 이 안에 자동 포함하지 않는다. 둘을 한 값으로 섞어 기존 렌즈 동작을 바꾸지 않는다. 승인 때 이 의미를 확인해야 한다. 부모 채팅에 제시된 기존 설계 확인 질문의 인간 답변을 기다리며 중복 승인 질문은 추가하지 않는다.

## 구현 때 먼저 만들 실패 검사

- 3000/5000ms 각각 마감 직전에는 이전 답변과 대기 발화를 보존하고 정확한 마감에는 다음 요청을 한 번만 실행한다. 기대 시간을 선택 설정값에서 계산한다.
- ACK 전/유지 중 발화, typed 수동 질문, 여러 final/correction/duplicate와 대기 질문 제한을 검증한다. ASR 수집을 중지하거나 내용을 버리지 않는다.
- 중복 ACK, 진행 중 settings 저장, 반복 polling/재연결은 기존 answer hold를 연장하거나 되살리지 않는다.
- Stop, 닫기, 이전 epoch/server/activation ACK, presentation_unconfirmed/idle 경계는 즉시 또는 올바른 마감으로 처리한다.
- Fold/lens 최소 유지와 Fold fade, lens 기존 retention을 가짜 시계/rAF로 각각 확인한다. 실제 표시 완료 ACK를 받은 답변에만 시간을 시작한다.
- 설정 3초/5초 정상 CAS 저장→읽기→재연결→다음 질문 적용을 검증한다. 현재 성공 profile의 모델/fallback/search/길이는 변경하지 않는다.

주요 근거: NovaFocusSettings.java:50, NovaFocusState.java:210/314, display-focus-flow.js:75/107, display-focus-controls.js:40/322, NovaFocusStateTest.java:226. 독립 explorer는 읽기 설계만 수행했으며 테스트/빌드/실기기 실행은 NOT_RUN이다.

외부 API: 이 설계 단계 provider 호출 0. 소스 편집·DB 쓰기·재기동 0.
PLUGIN_USAGE:
- Superpowers: USED(read-only cause trace and falsifying test design; implementation approval pending)
- Browser: NOT_USED(no new hardware/voice/browser duration run)
- Exa: NOT_USED(existing internal state contract governs this design)
- GitHub: NOT_USED
- AWX Control Tower: NOT_USED
- AWX Control Tower Recovery: NOT_USED
- GLM=NOT_USED(no new patch)
- Computer: NOT_USED
- Vercel: NOT_USED
- Sites, Plugin Management, Data, Visualize, Meta Wearables Webapp, Supabase: NOT_USED
