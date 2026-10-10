# Fold6 → Meta Ray-Ban Display 착용 관찰
관측창: 2026-10-10 09:54~10:44 KST / 00:54~01:44 UTC.
실제 종료 확인: 2026-10-10T01:44:06.128Z UTC. Task: fold6-meta-wear-observation-20261010-a4615178.

관찰과 지시서 작성은 완료했다. 실제 Fold6 수음 복구·안경 표시·현재 본인 세션의 요청 체인은 **미확인/PARTIAL**이다. 제품 소스 변경·서버 재시작·설정/인증 변경·실 마이크/카메라 활성화는 수행하지 않았다. 확인된 한 근본원인 RED가 없어 추측 패치를 적용하지 않았다. 10:21 KST 사용자 우선순위 변경에 따라 Codex 지시서를 먼저 저장·업로드했다.

## 관측 범위와 공백
현재 인증된 본인 owner + 서버 검증 device/session binding 및 해당 진단 권한을 확보하지 못했다. 따라서 개인 /api/diagnostics/display·metrics/export·DB 전역 조회는 NOT_RUN이다. 전역 최신행, 시간 근접, “1인 사용”, 과거 ownerhash를 Fold6에 대입하지 않았다. 아래 서버 로그는 모두 **unbound-runtime**이다.

09:59:14 KST의 최초 health 읽기와 현재 프로세스/기존 로그를 확인했다. 09:54~최초 접근 사이에는 실시간 수집 공백이 있으며, 그 이후에도 착용자에 귀속된 계측 공백은 끝까지 남았다. 피드백 처리·영상 획득 실패·지시서 작성 사이에는 연속 이벤트 수집이 없었다. 표의 시각은 실제 보존한 파일 메타데이터 스냅샷이다. 관측 누락을 0이나 장애 확정으로 쓰지 않았다.

기존 Desktop Display 탭은 읽기만 수행했고 mic idle였다. 이것은 Fold6 상태나 binding 증거가 아니다. poll/relay poll은 subscriber/output 상태를 바꿀 수 있어 진단 대용으로 호출하지 않았다. 새 사용자 테스트 호출을 발생시키지 않았다.

## 실행 버전과 로그
- PID39080, JVM 시작 2026-10-10 08:19:01 KST / 2026-10-09 23:19:01 UTC; 18180/18181 listener. 관측 종료 상태: 마감 스냅샷 10:44:05 KST에도 PID39080 및 같은 JVM 시작 시각 확인.
- manifest: var/rag-launcher/20261010-081700-e7ed8c83/spring-owned.json. profile local,meta-display. 실행 source commit/build version 필드는 없어 정확한 실행 commit은 미확인.
- 작업 중 checkout HEAD 940dc9921ddc50b1c1d575ae85dc0ea03c571c67. 이 HEAD를 실행 JVM 버전으로 단정하지 않는다.
- app.js manifest sourceAssetHash=servedAssetHash=0b46552688cfe8ea0058cbb2079d80739b19df612768aaae6ca6aefcee4b8181.
- 10:11경 기존 로컬 index.html/app.js/display-conversate.js/display-voice.js 제공 바이트와 source 해시가 일치했다. Fold6 loaded asset freshness는 미확인. 종료 source 재확인: index.html/app.js/display-conversate.js는 초기 해시와 동일했다. display-voice.js는 10:41:22 KST에 다른 현재 바이트로 바뀌었고 작성자는 미확인이다. 이 작업은 수정하지 않았다. 종료 source hash cf9da330d91e73edc99dfafe88e8c70aedee8bb0555fbf8e9f3649dab5e2c804, 기존/제공 hash 727558451dcc45319103ab9413c9b3c74ade6eee776f513476dd4b7addeec7ce. 마감 검증용 10:44:57 기존 정적 GET은 24907바이트의 기존 hash를 반환했다. source 변경이 실행 자산에 반영되었다고 볼 수 없으며 Fold6 loaded version은 미확인이다. 변경 전 display-voice.js 기반 검사·줄앵커는 현재 파일 검증으로 승격하지 않는다.
- 09:59:14 기존 actuator health는 UP였다. health와 listener만으로 수음·STT·렌즈 정상 판정을 내리지 않는다.

| 실제 스냅샷 KST | PID | stdout 바이트 | stderr 바이트 | debug-events 바이트 |
|---|---:|---:|---:|---:|
| 10:02:16 | 39080 | 3546477 | 445 | 190759 |
| 10:09:44 | 39080 | 3598350 | 445 | 190759 |
| 10:13:03 | 39080 | 3603338 | 445 | 190759 |
| 10:15:25 | 39080 | 3605226 | 445 | 190759 |
| 10:19:58 | 39080 | 3622315 | 445 | 190759 |
| 10:22:23 | 39080 | 3624675 | 445 | 190759 |
| 10:25:19 | 39080 | 3626563 | 445 | 190759 |
| 10:27:53 | 39080 | 3627979 | 445 | 190759 |
| 10:31:06 | 39080 | 3629631 | 445 | 190759 |
| 10:34:08 | 39080 | 3631283 | 445 | 190759 |
| 10:37:09 | 39080 | 3633407 | 445 | 190759 |
| 10:40:10 | 39080 | 3635767 | 445 | 190759 |
| 10:43:11 | 39080 | 3654504 | 445 | 190759 |
| 10:44:05 | 39080 | 3655682 | 445 | 190759 |

마지막 파일 상태: var/rag-launcher/20261010-081700-e7ed8c83/chat-ui-vibe-listener-18180.out.log=3655682바이트 (갱신 10:44:00 KST); var/rag-launcher/20261010-081700-e7ed8c83/chat-ui-vibe-listener-18180.err.log=445바이트 (갱신 10:01:18 KST); logs/debug-events.ndjson=190759바이트 (갱신 10:01:46 KST); logs/trace.ndjson=5006바이트 (갱신 23:58:30 KST); var/abnadon/debug/2026-10-10.ndjson=185325바이트 (갱신 10:01:46 KST).
debug-events와 mirror의 마지막 갱신은 10:01:46 KST, trace는 전일 23:58:30 KST로 남았다. 이후 정체는 해당 파일의 관측값이며 음성/LLM/표시가 없었다는 증거가 아니다. 소유권 없는 전역 ring/계측의 bounded window로 전체 사건 체인을 복원할 수 없다.

## KST 타임라인과 피드백 대조
| 시각 | 입력/확인 | 판단 |
|---|---|---|
| 09:54 | 요청한 착용 관측창 시작 | 실시간 개인 수집 확보 전 |
| 09:59:14 | 기존 health/프로세스/로그 확인 | 서버 접근 가능, 본인 binding 미확인 |
| 10:01:00.093~10:01:56.514 | unbound status409 200행, 아래 근거 참조 | 특정 Fold6/ACK 경로·고유 요청 수 미확인 |
| 10:01:45.052 / 46.108 | transport capture_error, httpStatus408, epoch2 | 사용자 사건과 동일 요청인지 미확인 |
| 10:06 전달 | 인터넷 불안정 중 refresh 후 수음 불가; 최소 수정 요청 | 실제 영향은 사용자 보고. 원인 조사는 mock/코드 대조로 확대 |
| 10:08 전달 | 갈색 화면·UI 비활성 | 영상의 검은 배경과 구분. 실제 disabled/권한 실패 미확인 |
| 10:14 부모 전달 | 부모 PC 연결 단절 | 이 작업의 이후 읽기 성공과 별도로 기록 |
| 10:18 부모 전달 | 부모 PC 연결 복귀 | 10:19 이후 본 작업 읽기 및 Node mock도 성공 |
| 10:19~10:21 | 초기 연결 복구 mock 3 PASS; 그록 가설 대조 | 영구 연결 정지 RED 미재현; epoch 미반환 가설은 코드와 불일치 |
| 10:21 전달 | Codex에 전달할 지시서 우선 요청 | 제품 쓰기 추가하지 않고 지시서로 전환 |
| 10:25~10:28 | Downloads 지시서 저장·재검토·Library 버전1 갱신 | 지시서 완료와 관찰/실장비 복구를 분리 |
| 10:44 | 관측창 종료 | 관찰·지시서·보고서 작성 범위 완료; 실제 Fold6 복구와 본인 trace는 NOT_RUN/PARTIAL |

영상 근거는 **부모의 실제 픽셀 분석 전달**이다. 입력 Library libfile_28753e852c788191994d028663a81b02, 24525.mp4, 7.03초. 0~3초 “재연결 중/display-timeout” → 3~3.7초 refresh/레이아웃 변화 → “서버 연결 중” → 종료까지 수음 대기·자동 힌트 꺼짐·전사 비어 있음. 분명한 Start 클릭, 실제 disabled 속성, 권한 거절은 관측되지 않았다. 이 PC는 공식 materialize helper의 Windows os.setxattr 미지원으로 로컬 영상 획득에 실패했다. 이 PC의 형식/첫 프레임/끝 프레임 직접 검사는 NOT_RUN이다.

## 문제별 근거·재현 조건·영향·다음 한 검사
### 1. refresh 후 수음 불가
확인: 사용자 영향 보고와 영상의 display-timeout/재연결→서버 연결 대기 상태.
추정: 네트워크 지연으로 초기 handshake가 끝나지 않은 상태는 영상과 맞는다. 영구 버튼 잠김·권한 실패·producer 오류의 원인은 아직 미확인.
조건: 수음 중 불안정한 인터넷 → 새 문서 refresh. 실제 이전 capture intent/producer/epoch/permission이 연결된 증거 없음.
영향: 사용자가 수음 재개를 못했다고 보고; 중단 길이·STT 누락량은 산정 불가.
근거: main/resources/static/assets/display/display-conversate.js:99 초기 bootstrap/transcription/phone-test deadline10000ms; :103 connecting flight 공유; :188~201 RECONNECTING/backoff/start/pause. app.js:109 canCapture, :124 disabled, :131 수음 상태. 7.03초 영상만으로 10초 handshake의 영구 실패를 확정할 수 없다.
다음 한 검사: 현재 본인 binding과 Fold6 loaded version을 확인한 뒤, 같은 refresh의 실제 handshake route/reason와 요청·응답 epoch/version을 한 체인으로 대조한다.

### 2. 409 반복
확인: 로그 행 빈도이며 사용자 귀속은 미확인.
기존 out.log 전체 status409 226행: L10977 08:23:29.484 ~ L20396 10:01:56.514. [10:01,10:06)에는 200행이며 전부 10:01에 집중, 첫 L19299 10:01:00.093부터 56.421초.
[09:54,09:59) 비교창은 409 기록 없음/404 1행; 건강 확정창은 아니다.
[10:01,10:06) status 필드 빈도: 200=312행,409=200행,408=1행,204=2행. 서로 다른 로그 필드의 빈도이며 고유 HTTP 요청 수로 합산하지 않는다.
이 창의 409는 reason=http_request,pathLength23. 명시 route가 없어 ACK로 특정 불가. L20375 10:01:53.836,L20386 10:01:55.154,L20396 10:01:56.514는 반복 예시다.
최종 이후 구간 집계: [10:06,10:44) KST의 이 로그에서는 status409 기록이 없었다. 전체226행과 마지막 L20396 10:01:56.514는 유지되었다. 최종 읽기는10:44:02 KST이며 파일 마지막 timestamp L20902 10:44:00.198이었다. 이는 소유 세션 복구 판정이 아니다.
근거 경로: var/rag-launcher/20261010-081700-e7ed8c83/chat-ui-vibe-listener-18180.out.log, 위 줄/시각.
서버 파일 기준은 main/java/com/example/lms/assist/DisplayConversateController.java 및 main/java/com/example/lms/assist/ConversateSessionService.java이다. 현재 코드: DisplayConversateController.java:359~365,:679와 ConversateSessionService.java:355,:454는 producer_reclaimed 뒤 최신 epoch를 이미 반환한다. display-conversate.js:240,:246,:248은 handshake/audio-start epoch를 적용한다.
403 event_owner_required(:429,:566)와409 stale_epoch(:575)를 구분한다. ACK(:506~509)의 stale_epoch/invalid_*_ack는 서비스 :668,:142,:153,:161로 구별된다. Controller:109는 focus 검증, :401은 lens-settings여서 ACK의 근거가 아니다.
그록 제안은 미검증 참고로 취급했다. 같은 owner라도 다른 device/tab을 임의 강탈하거나 자동 녹음하는 근거가 아니다.
다음 한 검사: 소유 binding이 확인된 동일 409의 실제 route/reason/request epoch/version을 확보한다. 이 증거 없이 blanket retry/새 epoch API를 추가하지 않는다.

### 3. transport 408과 카메라/마이크 충돌
확인: out.log L20289 10:01:45.052,L20296 10:01:46.108의 stage=transport,httpStatus408,errorCode=display_http,epoch2,producerMismatch=false,lastFrameAgeMs226.
추정: 전송 시간 초과일 수 있지만 영상 display-timeout과 동일 요청인지 미확인. 카메라와 마이크 충돌·STT final 누락·답변 사라짐을 확정하지 않았다.
다음 한 검사: 본인 captureRun/connection generation/trace로 이 408과 동일 handshake·audio request를 연결한다.

### 4. 갈색 화면/UI 비활성
확인: 사용자 보고. 받은 영상 배경은 검정이며 클릭/DOM disabled 증거 없음.
영향/조건: refresh 이후 사용자 체감 UI 비활성. 브라우저 console/DOM/JS 초기화 오류는 미확인.
다음 한 검사: 같은 본인 Fold6 문서의 연결 phase와 Start disabled 실제 속성/초기화 오류를 읽어 상태 대기와 렌더 장애를 구별한다.

## 단계별 계측 가능성
| 단계 | 현재 결과 | 계약상 관측 가능 지점 |
|---|---|---|
| 수음 시작/수신/재접속/중복/late | 본인 체인 NOT_RUN | DisplayConversateController.java:213~226; audioStarts-1은 정상 segment도 포함하므로 장애 reconnect 수가 아님 |
| STT partial/final | 본인 체인 NOT_RUN | ConversateAsrBridge.java:67~80,:211~215; 길이/상태/provider/model evidence |
| 호출어/포커스 | 본인 체인 NOT_RUN | NovaFocusState.java:342~347; 서버 capture accept는 provider image attached와 다름 |
| LLM requested/actual/fallback | 본인 체인 NOT_RUN | NovaFocusAnswerService.java:226~236,:267~311; safe model hash/settingsVersion/preset |
| 사진 attached/presetversion | NOT_RUN | imageCount allowlist 부재, 별도 presetVersion 필드 미확인. 현재 버그 주장 없음 |
| relay/ACK/render | 본인 체인 NOT_RUN | DisplayRelay.java:71~86; NovaFocusService.java:81~82,:340~350의 DOM callback ≠ 물리 렌즈 표시 |
| 실제 안경 답변 유지/표시 | 사용자 확인 없음/NOT_RUN | software ACK로 대체 불가 |

diagnostics binding 제한 근거: main/java/com/example/lms/assist/NovaFocusService.java:77의 deviceHash/captureRun/connectionGeneration null; DisplayRuntimeDiagnostics.java:24,:48~60은 무소유 전역128 ring. ConversateSessionService.java:628~650의 최근24행, NovaFocusService.java:32의64 queue, main/java/com/example/lms/debug/DebugEventStore.java:89~111,:639~649의 bounded ring/queue는 전체 세션 기록이 아니다. diagnostics의 역할 확인은 Controller.java:195~211, owner cookie와 auth principal 구분은 main/java/com/example/lms/web/ClientOwnerKeyResolver.java:47~63.

## 실행한 검증과 NOT_RUN
- 네트워크/마이크 없는 fresh standalone fetch/timer mock 3 PASS: pending 첫 연결 중 pause/start → 10000ms timeout → 1000ms retry → epoch2 READY → 옛 응답 무시; pause 후 retry 없음; disposed 옛 문서 Stop 요청0. 제품 결함 RED는 재현되지 않았다.
- 기존 client focused 검사 7 PASS: resume ignores|remote stop|pause preserves|fresh page construction|input POST failure.
- 기존 webapp focused 검사 5 PASS: page exit|explicit user stop|returning to a visible|cancel fences|late result.
- 기존 client 확장 focused 검사 8 PASS/1 FAIL:
  node --test --test-name-pattern='hung bootstrap|server preparing|earlier poll|fresh page construction|pause preserves|remote stop|input POST failure' scripts/meta_display_conversate_client_tests.cjs
  FAIL은 L94~95의 4000ms timer 기대와 현행 초기 deadline10000ms의 계약 차이다. 제품 영구 정지의 RED로 사용하지 않았다.
- 기존 fold6_display_capture_tests.cjs focused 결과3 PASS/4 FAIL. 실행 뒤 display-voice.js 소스 변경을 감지했으므로 이 음성 검사는 현재 소스 기준 stale/재검증 필요다. 고정2000ms timer vs jitter, stale_epoch 정책 등 현행 계약 차이가 섞여 있으며 이 장애의 확정 근거가 아니다. 테스트를 약화하거나 unrelated 수리하지 않았다.
- Java 빌드/테스트·실장비 capture·provider API·권한/역할 변경·서버 restart/deploy·현재 metrics/export는 NOT_RUN.
- 개인 diagnose-display-sessions 최신 main은 읽었으나 관련 field-feedback/recovery-checks/camera-settings-acceptance resource read는 실패해 현재 활성 코드를 대신 대조했다.
- 과거 카메라 검증 OFF 최종 image0, INTERVIEW 음성사진 결합, 클라이언트 JPEG 대체 미검증은 역사적 PARTIAL이다. 현재 버그로 확대하지 않았다.

## 전달·보존
지시서: C:/Users/nninn/Downloads/PASTE_CODEX_fold6_reload_audio_recovery_20261010.txt.
Contract ID FOLD6_RELOAD_AUDIO_RECOVERY_20261010_V1. 최종 16983바이트, SHA256 dc15f3d21a99a7640378b2452e1865fc6352d6e45219603ac652e5fad770187d. 재읽기/준비 바이트 일치/형식 및 secret-pattern lint 통과.
Library 첨부 ID libfile_9065695907a88191b8eec6c9216eefa2, current version1, 업로드 성공. Windows 로컬 xattr helper는 os.setxattr 미지원으로 실패했고 서버 업로드 성공과 구분했다.
이 작업의 소스 patch/lease 취득0건. 종료에 감지한 다른 작성자의 display-voice.js 변경과 실행 자산 불일치는 별도 사실이다. 후보 fix journal은10:25 abandoned 종료, 관찰 journal은 이 보고서 검증 뒤 partial 종료. checkpoint는 이 새 보고서만 선언해 생성 전 absent preimage를 기록했다. 다른 writer의 소스·lease·journal은 변경하지 않았다.
보고서는 원음·사진·전사 원문·owner token·signed URL을 포함하지 않는다. 보고서 Library 첨부 ID는 최종 전달 메시지에 제공한다.

외부 API: Library 파일 전달만 사용; LLM·검색·실장비 테스트 API 미호출.
PLUGIN_USAGE:
- openai-library: USED(영상 materialize 시도, 지시서 버전1 및 보고서 업로드)
- unified-computer-use: USED(기존 Desktop 탭 읽기만)
- Codex collaboration: USED(로컬 읽기 전용 로그/계약 대조 및 지시서 검토)
- Devin/Grok/Gemini: NOT_RUN(외부 메시지 금지; 사용자 제공 그록 가설만 검증)
- GLM: NOT_USED

한 줄: 착용 관찰과 지시서는 전달했으며, 본인 세션 binding을 확보한 동일 요청 검사 전까지 실제 수음 복구와 근본원인은 미확인이다.

