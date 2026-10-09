# 선택형 답변 유지시간 구현 및 Gemini 재연결 검증

검증일: 2026-10-09. 작업: `focus-answer-hold-implementation-0d0effce`. 최신 사람 지시의 우선순위는 Gemini Flash / Flash-Lite의 선택·저장·재연결·실응답 확인이다. 3초·5초 착용 확인은 사용자가 진행하며 이를 기다리거나 추가 시간 튜닝을 하지 않는다. 첨부·외부 제안은 참고 자료로만 사용했다.

## 적용한 변경

기존 `NovaFocusSettings.Presentation.tailHoldMs`를 그대로 재사용했다. 기본 5000ms, 기존 허용 범위 2000–15000ms, 저장/API 형식은 그대로다. Fold 화면의 **연결·입력 설정 → 노바 대화 설정 → 답변 유지시간(초)**에서 3 또는 5를 입력할 수 있다. 화면 읽기는 ms/1000, 저장은 초×1000이며 새 공개 설정·DB 필드·서비스·레이어·타이머 프레임워크는 추가하지 않았다.

`NovaFocusState`는 선택된 renderer의 첫 유효 `presentation_done` ACK 때 질문 접수 시 고정한 `runPresentation.tailHoldMs`로 내부 유지 마감 시각을 잡는다. 자동 음성 질문의 소비는 그 마감 전까지 지연한다. 그동안 `PRESENTING`, 기존 답변, answerVersion, 한 개 대기 질문을 보존하고 ASR partial/final/revision 수신은 계속한다. 음성을 버리거나 3초를 상수로 박아 넣지 않았다. 유지 시간 후에도 final+quiet와 실행 용량 조건을 충족해야 정확히 한 번 실행한다.

허용된 수동 질문은 기존 단일 `typed-` 요청 식별, final+quiet, 실행 용량을 모두 만족할 때 유지 시간을 건너뛸 수 있다. quiet 또는 용량이 부족한 typed 질문은 먼저 phase를 풀지 않는다. 음성 초안을 수동 질문으로 덮어쓰는 기존 금지와 queuefull 동작은 유지한다. 중복 ACK는 시간을 연장하지 않으며 Stop은 즉시 닫는다. 이전 activation/receipt는 새 턴을 열지 못한다. 짧은 idle은 유지 시간을 먼저 끊지 않고, idle 계산 시작점은 첫 완료 ACK 그대로다.

변경 대상은 State, settings controls, HTML 및 관련 State/Service/JS 테스트 6개다. 기존 모델 라우팅, Luna preset, fallback outcome/banner, OAuth/API 정책, 인증 설정은 이 단계에서 수정하지 않았다. HTML은 원래 CRLF, 나머지 대상은 원래 LF를 보존했다. `hold-final-source-bindings.json`의 6개 postimage와 `hold-final-owned.patch`는 최초 cycle-01 preimage 대비 최종 변화에만 바인딩된다.

## 재현과 회귀 검증

| 증거 | 관측 결과 |
|---|---|
| 수정 전 hold 회귀 Java | 3개 실행, 2개 실패: 유지 전 음성 실행 및 짧은 idle의 선점 |
| 수정 전 settings JS | 1개 실행, 1개 실패: 5000ms가 5초로 표시되지 않음 |
| 독립 검토의 manual 경계 RED | 1개 실행, 1개 실패: quiet/용량 전 phase 해제 |
| 최종 관련 Java 9개 클래스 | **194 tests, failures 0, errors 0, skipped 0** |
| 관련 Display JavaScript | **188 tests, pass 188, fail 0** |
| 3/5초 실제 settings API | CAS save→read→페이지 reload→동일 소유 격리 binding 재연결→read→hydrate 모두 PASS |

Java 최종 run은 `0f401416-9b77-4442-982d-7cd56d137c93`, 실제 JUnit XML 합계는 `hold-final-java-v2/junit-counts.json`이다. 공통 command runner의 totals=0은 suite 인수를 주지 않은 parser 결과이며 실제 실행 개수로 사용하지 않았다. JS 188개는 `hold-final-js/command.log`의 Node 결과다.

State 회귀는 3초/5초 각각 deadline−1에서 답변·초안·answerVersion 보존, 정각 단일 실행, 중복 ACK, 설정 snapshot, final+quiet, short idle, Stop, stale receipt, audio epoch, queuefull, typed identity 및 capacity true/false 경계를 검사한다. Service의 수동 후속 질문 helper는 유지 시간을 인위적으로 추가하지 않았다.

`hold-settings-browser-v5/evidence.json`은 UI 값을 3/5로 채우고 실제 settings API에 정상 CAS 저장했다. 3초는 version 0→1, 3000ms, 재연결 read version 1 및 UI 3을 확인했다. 5초는 version 1→2, 5000ms, 재연결 read version 2 및 UI 5를 확인했다. 이 harness는 실제 HTML/controls와 격리 API를 사용하지만 form onsubmit을 직접 호출했다. **실제 저장 버튼 클릭 성공이나 착용 결과로 확대하지 않는다.** 앞선 v1–v4의 접힌 fixture UI 탐침 실패도 보존했다.

## 현재 빌드의 Gemini 실응답

두 시나리오는 각각 새 브라우저 context, 저장된 인증 상태 없이 기존 격리 test channel과 자체 binding을 사용했다. FIXED / API_ONLY / fallback OFF / search OFF / STANDARD / 200chars로 실제 settings save→read→페이지 reload→정상 재연결→read 후 각 모델에 생성 요청을 한 번만 보냈다.

| 선택 alias / requestedModel / selectedAlias | 실제 modelUsed | 저장→재연결 | 결과 | isFallback / 횟수 | 단일 관측 지연 | terminal 시각 KST |
|---|---|---|---|---|---|---|
| `llmrouter.gemini-pro` | `gemini-3.8-flash` | version 1→1, 설정 동일 | ANSWER_READY / terminal success | false / 0 | 5095ms | 21:04:29.626 |
| `llmrouter.gemini-cue` | `gemini-3.5-flash-lite` | version 1→1, 설정 동일 | ANSWER_READY / terminal success | false / 0 | 3077ms | 21:04:34.058 |

두 모델 모두 live catalog의 selectable=true와 modelId를 확인했다. Flash 요청 hash는 `fed24af393e1`, Lite는 `39cb71e99625`; 두 요청의 동일 서버 instance hash는 `6233a1de975a`다. request/session/epoch/turn 및 terminal diagnostic을 결합했고, requested/selected alias hash와 실제 모델의 full SHA-256을 비교했다. Flash를 요청하고 조용히 Lite로 바꾸는 동작은 관측되지 않았다.

현재 UI 경로는 Fold/Display 수음 화면의 **연결·입력 설정** 접힘 섹션 → **노바 대화 설정** 접힘 섹션 → **답변 모델** 드롭다운이다. Flash 표시명은 `gemini · llmrouter.gemini-pro`, Flash-Lite는 `gemini · llmrouter.gemini-cue`다. `display-focus-controls.js:113`의 modelOptions는 현재 catalog provider와 alias로 표시명을 만든다. `index.html:70`, `:92`, `:106`의 현재 실제 label을 기준으로 경로를 적었다. 일반 질문 시험에서는 `API_ONLY`, 모델 자동 전환 OFF, 웹검색 OFF를 사용했다. **제미나이 (Gemini · 웹검색)** preset은 검색 경로를 함께 바꾸므로 두 일반 모델의 직접 선택과 구분한다.

최신 생성 검사는 2026-10-09 **21:04 KST**, 상관 결합 재확인은 **21:05:57 KST**, UI catalog 재확인은 **21:09:32 KST**다. `NovaFocusAnswerService.java:224`는 `Objects.toString(result.modelUsed(),"not_observed")`를 `NovaFocusHistoryService.digest`에 전달한다. `NovaFocusHistoryService.java:63`의 규칙은 문자열 변경 없이 UTF-8 bytes→SHA-256→소문자 64hex다. 같은 규칙으로 `gemini-3.8-flash`는 `319034f3e25d0d9bd6b3a46b1def7a01b7689f0d51384c63da1f6e0b464af810`, `gemini-3.5-flash-lite`는 `37a58c167b3b8f674049d3c259bacf074a8d47c406de12f2ca2ece97e0cf658a`이며 각 실제 resultModelHash와 일치했다. 별도 strip/lowercase/alias 대체를 추가하지 않았다.

`gemini-reconnect-final/evidence.json`의 최초 verifier FAIL은 64자리 `resultModelHash`를 `hash:`가 붙은 짧은 12자리 이벤트 hash와 비교한 **검증 스크립트 오류**다. 실제 두 terminal은 성공했다. 원본을 보존하고 같은 실행 자료만 다시 계산한 `correlated-runtime.json`에서 PASS를 확인했다. 추가 generation/restart로 실패를 덮지 않았다. 최신 검증 생성은 **총 2회**이며, 이 한 번씩의 지연으로 모델 간 일반적인 성능 순위를 주장하지 않는다.

2026-10-09 확인한 [Google Flash 공식 문서](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash) 및 [Flash-Lite 공식 문서](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite)는 model ID/지원 규격의 보조 근거다. 현재 alias 매핑·실행은 repository와 live catalog 및 위 provider diagnostic으로 판정했다. 기존 Gemini 검색 preset의 native search 경로와 이번 API_ONLY/search OFF 시험을 구분한다. provider/API 규격을 새로 추정하여 소스를 교체하지 않았다.

## 런타임·프로필·범위

현재 runtime은 dev PID **38456**, launcher `20261009-205853-7168561d`, springReused=false, ready다. 기동 전 최신 관측 window에서 시작됐으나 terminal이 없는 Focus 요청은 관측되지 않았다. 서버는 끝에 ON으로 유지한다. 새 Java 보완을 반영하기 위해 승인된 기존 기동 경로를 사용했으며, 정리를 위한 재기동은 하지 않았다.

`hold-final-runtime.json`: primary `/chat` HTTP 200, health UP, 최종 controls.js와 index.html의 source/served bytes 및 SHA 일치. `hold-bytecode-binding.json`: JUnit이 사용한 root State.class와 runtime split build State.class SHA 일치. `hold-runtime-verify.txt`: compile/runtime/ports/http/freshness PASS, sourceNewer=false. 그 command의 tests는 별도 실행 대상이 아니므로 Java194/JS188 로그와 구분한다. 기존 H2 constraint/index DDL warning 132개는 남으며 별도 DB 수리로 확장하지 않았다.

사용자 live 프로필은 Gemini 시험 전후 **version 7 / sha256 309b41cad43d5be2e7d7aa6d7d3468acfec2cba4f4d7df4c3801fa97e1bfd0ce**로 동일하다. FIXED `chatgpt-oauth:gpt-5.6-luna`, API_ONLY, fallback OFF, search OFF, STANDARD, 200chars 및 tailHoldMs=5000을 보존했다. agent live-profile write=0, direct DB UPDATE=0. 시험은 test-channel 자기 설정에만 썼다. 토큰·쿠키·인증 상태·private 질문/답변을 저장하거나 보고하지 않았다.

Java 17 / Spring Boot 3.3.4 / LangChain4j 1.0.1과 active sourceSet을 유지했다. HEAD `d2d62e7ded7cf70a5d9c77b9482f537bf51e4fa5`, branch `codex/owned-runtime-browser-restart`는 그대로이며 초기 GitHub SHA 정렬 확인은 이전 원본 보고서의 보조 증거다. dirty tree를 remote와 동일하다고 주장하지 않는다. commit/push/merge/branch 삭제는 수행하지 않았다.

독립 built-in read-only 검토는 manual 경계 REVISE→최소 조건 수정→ACCEPT였다. 실제 검증 성공은 reviewer 동의가 아니라 fresh 테스트와 API/provider/runtime 결과로 판정했다. GLM은 이전 task plaintext marker를 받지 못한 SESSION_UNAVAILABLE이며 PASS로 취급하지 않았다. 3/5초의 실제 Fold/안경 착용 표시 시간 및 새 유지 동작의 하드웨어 end-to-end 결과는 **NOT_RUN**, 사용자의 성공 언급은 **USER_REPORTED_SUCCESS**다.

원래 `/chat` greeting/general 및 fresh-login 보호 경로 검증은 `REPORT.md`의 이전 단계 증거이며 이번 Gemini 검증으로 소급하여 새 인증 판정을 하지 않았다. source/최종 patch/보고서/실행 증거/실패 기록/preimage 복구 bytes는 보존하고 자기 lease만 닫는다. 다른 writer 파일·사용자 채널·첨부·실행 서버를 삭제하지 않는다.

외부 API: 최신 Gemini provider 생성 2회(모델당 1회); 그 외 설정/카탈로그/상관 결합 조회 및 기본 AWX 정제 로그 분류만 실행. 사용자 live profile 쓰기 0, 추가 모델 재생성 0.
PLUGIN_USAGE:
- Superpowers: USED(systematic-debugging, reproducible RED→minimum patch→fresh GREEN, verification-before-completion)
- Browser: USED(fresh Playwright contexts; isolated real settings/reconnect/provider verification; no restored or persisted auth state)
- GitHub: NOT_USED(initial aligned SHA/diff read remains prior-phase supplemental evidence)
- Exa: NOT_USED(2026-10-09 official specifications already checked; internal hold implementation uses source/test evidence)
- AWX Control Tower: USED(actual sanitized failing test log, primaryClass=other/outputCount=0; raw synthetic assertion/code reviewed)
- AWX Control Tower Recovery: NOT_USED
- GLM=SESSION_UNAVAILABLE(no task plaintext marker; built-in read-only fallback reviewer, never provider PASS)
- Computer: NOT_USED
- Vercel: NOT_USED
- Sites, Plugin Management, Data, Visualize, Meta Wearables Webapp, Supabase: NOT_USED
