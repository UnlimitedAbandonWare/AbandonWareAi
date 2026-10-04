# Gemini /chat catalog 진단 — 2026-10-03
Task: codex-gemini-chat-catalog-53b36b1f. Canonical root: C:\AbandonWare\demo-1\demo-1\src.
Authority: 지정된 goal-objective.md의 실제 수정·검증 요청. Downloads의 네 첨부는 검증할 주장/참고 자료로 취급했다.
Overall: PARTIAL. WP2/WP3/WP1 구현과 포커스 검증은 PASS; WP0/WP4의 미관측 항목 및 사전 기준선 실패 때문에 전체 완료를 선언하지 않는다.

## 프로필별 설정과 관측 범위
기본/프로필 열은 현재 소스의 import·profile 우선순위를 적용한 placeholder 기본값이다. 환경 변수·시스템 속성은 이 값보다 우선한다.
현재 JVM의 전체 Environment를 읽지 않았으며 키 값/쿠키/개인 환경 값은 열람하지 않았다. 라이브 열은 카탈로그와 동일 요청 증거로 판정 가능한 부분만 표시한다.
[Spring Boot 3.3 공식 설정 우선순위](https://docs.spring.io/spring-boot/3.3/reference/features/external-config.html), 확인일 2026-10-03, 적용 버전 3.3.4.

| 설정 | 기본 구성 | local,meta-display 구성 | 현재 JVM 관측/추론 | 소스 |
| --- | --- | --- | --- | --- |
| app.ai.allow-remote-model-selection | false | false | false 추론: eligible cue가 remote_selection_disabled이므로 global 허용 predicate는 false | application.properties:665; ChatModelCatalogService:173 |
| app.ai.remote-model-selection-routes | api3 | api3,gemini-pro | Pro는 static allowlist 또는 runtimeApprovedRoutes로 허용; 정적 목록의 정확한 값/Pro membership not_observed. Cue는 유효 허용집합에서 제외 | application.properties:667; application-meta-display.yml:196; ChatModelCatalogService:173 |
| llmrouter.api-first.enabled | false | false | false 추론: 위 cue 행과 catalog OR predicate | application-llm.yaml:484; ChatModelCatalogService:173 |
| llmrouter.models.gemini-pro.enabled | false | true | true: 새 JVM catalog configured/selectable | application-llm.yaml:548; application-meta-display.yml:218 |
| llmrouter.models.gemini-pro.name | gemini-2.5-pro | gemini-3.8-flash | gemini-3.8-flash: catalog 및 첫 SSE observedModel | application-llm.yaml:553; application-meta-display.yml:219 |
| gemini.gateway.enabled | true | true | true: 새 readiness gate를 통과한 catalog | application.yml:422; GeminiGateway:258 |
| gemini.gateway.purpose.router.enabled | false | true | true: 새 readiness gate를 통과한 catalog | application.yml:455; application-meta-display.yml:252 |
| Gemini credential | present 여부는 소스만으로 알 수 없음 | 동일 | resolver가 usable/non-conflicting으로 판정: configured gate 및 첫 SSE 응답; raw key 미열람 | GeminiGateway:253,260 |

관리 포트의 단일 named-property GET은 로컬 management HTTP403이었다. Gemini provider403 증거가 아니며 로그인/권한 확대/재시도하지 않았다.
정확한 JVM static allowlist 값은 evidence_needed. ConfigInspectTool의 고정 whitelist와 OpsSnapshotTool은 해당 속성을 노출하지 않는다.
Pro configured만으로 정적 목록 포함을 단정하지 않는다. PageController의 allowRemote 로그/뷰 값도 usable-key policy로 확장된 파생 값이므로 raw catalog property 관측이 아니다.

## F1–F7 재확인
| 사실 | 재확인 결과 |
| --- | --- |
| F1 | before/after catalog 모두 24행. gemini-pro configured/selectable=true; gemini-cue unavailable/selectable=false. after pro reasons=[]; cue reasons=[remote_selection_disabled]. |
| F2 | meta-display의 api3,gemini-pro allowlist는 외부 세션의 기존 변경. 이 작업은 해당 파일을 수정하지 않았다. |
| F3 | application.properties:667에 환경변수 브리지가 이미 있다. @Value 변경 불필요. |
| F4 | 기존 단일 reason을 유지하고 reasons를 추가. 9/11인자 호환 생성자를 유지했다. |
| F5 | Gateway의 enabled/purpose/credential 관문을 부작용 없는 RouterReadiness로 통합; builder가 동일 helper를 재사용한다. |
| F6 | 기존 probe의 Gemini 관문 공백을 RED로 재현. disabled probe 및 OBSERVE 조기 반환 앞에 readiness 반영하여 해결했다. |
| F7 | 현재 manifest에 3.8-flash/3.5-flash-lite 메타데이터 추가; 2.5-pro disabled 유지. 실 manifest 회귀 테스트로 picker 행의 identity/selectable 불변 확인. |

추가로 F9의 “ChatDefaultsPrecedenceTest 없음”은 STALE: 활성 src/chatUiTest/java에 기존 클래스가 있었다(build.gradle.kts:797,859).
첫 strict UI 기준선은 ChatPreferenceService:124에서 모든 llmrouter.*를 auto로 오인하여 HTTP500으로 끝났다.
원인 체인 SCOPE_EXPAND 2파일로 해당 한 줄과 기존 회귀 테스트를 수정했다. llmrouter.auto strict 금지는 유지한다.

## plan15 주장 판정
| 주장/제안 | 판정 | 처리 |
| --- | --- | --- |
| local,meta-display에서 Gemini가 remote_selection_disabled로 숨겨짐 | 해소됨 | before catalog에서도 pro selectable=true; 외부 allowlist 변경 보존 |
| CHAT_REMOTE_MODEL_SELECTION_ROUTES 브리지 추가 | STALE | 현재 properties에 이미 존재; 수정 없음 |
| catalog configured인데 Gateway disabled일 수 있음 | LIVE → 수정·검증됨 | 공유 readiness 관문 + 10개 parameterized 계약 사례 |
| 3.8/3.5 metadata 없음 | LIVE → 수정·검증됨 | 공식 문서 확인 후 manifest 2항목 추가 |
| Gemini3.8 sampling/Flash-Lite frequency_penalty 생략 | STALE | 기존 구현을 GatewayContractTest 18개로 검증; 라이브 payload는 not_observed |
| ModelConfig 4플래그 및 자동·fallback·default 라우팅 분리(P1-B) | HOLD | 이번 범위 밖, 명시된 forbidden routing owners 유지 |
| Gemini fallback 오류 매트릭스(P1-D) | HOLD | 별도 라운드; 이번에 fallback 후보 추가 없음 |
| UI 문구, cue 직접 선택, api-first 활성화, 2.5 활성화 | HOLD | 제품 정책/범위 밖 |
| grounding, 관리자/auth 강화, DB 변경 | HOLD | 외부 소유 hunk 및 PROTO_OPEN/DB 경계 보존 |

## 변경 및 포커스 검증
소스/설정/테스트 10파일 +265/-24줄(이 작업의 현재-byte preimage 대비; 전체 Git diff가 아님).
추가로 report-lint의 공개 artifact 파일명 오탐을 RED1failure → GREEN20tests로 고쳤다. 기존 scanner와 regression2파일 +15/-1줄; product/tooling 전체12파일 +280/-25줄.
Gateway grounding 구현은 checkpoint preimage와 동일한 영역을 유지했다.
공식 [3.8 Flash](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash), [3.5 Flash-Lite](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite), [OpenAI 호환 API](https://ai.google.dev/gemini-api/docs/openai)를 2026-10-03 확인했다.
manifest에 기존 ctx/maxOutputTokens 필드가 있으므로 공식 토큰 한도를 기록했고, 숫자 가격 필드가 없어 가격은 추가하지 않았다.

| 증거 | 실제 결과 | 경로(task 폴더 기준) |
| --- | --- | --- |
| reasons RED | 9개 중 1개 실패: reasons JSON 부재 | verify-wp2-red/junit.xml |
| readiness/manifest RED | 12개 중 11개 실패(10 readiness + 1 manifest) | verify-contract-red/run.json |
| 최종 주 검증 | 7개 suite, 61 tests, failures/errors/skipped 모두0 | verify-source-green-complete/run.json |
| strict RED → GREEN | 기존 6개 중1 실패 → 6/6 통과 | verify-strict-red/junit.xml; verify-strict-green/run.json |
| Verify-RAG | compile/resources·freshness·DevWatch·HTTP 등9체크 실행, exit0; target=PARTIAL(H2 DDL 경고132) | verify-runtime/command.log |

7개 suite: ChatModelCatalogServiceTest9, HybridLlmGatewayProbeServiceTest8, CloudModelRouteClassifierTest6,
CloudModelRouteClassifierRegisteredModelCatalogTest2, RouteCatalogRegistrationTest8, GeminiSelectionReadinessContractTest10, GeminiGatewayContractTest18.
추가 chatUiTest의 ChatDefaultsPrecedenceTest6 포함 변경 검증67개 PASS. 마지막 실패 분류를 확인한 기존 ChatFailureClassificationFocusedTest5개도 PASS(verify-failure-classification/run.json); 이번에 실제 실행한 최종 포커스 검증 합계72개. 전체 테스트 suite 실행 없음.
추가 Python report-lint20개도 PASS. Java72개 + Python20개, 최종 포커스 검증 합계92개.
새 runtime: var/rag-launcher/20261003-223219-0c0742d1/result.json, ready, springReused=false, PID76756, local,meta-display.
Verify-RAG의 실행 성공과 대상 PARTIAL을 구분한다. H2 DDL 중복 경고는 이전 runtime에도 관측됐으나 이번에 DB를 수정하지 않았다.

## 라이브 검증과 한계
생성 검증 요청4회(로컬3/공개1)를 전송했다. provider wire/billing 횟수는 not_observed.
코드 반영 전 기준선2회는 strict admission에서 실행 전에 거절돼 위4회에 포함하지 않았다.
Gemini 동일 요청401/403/429는 not_observed. GLM 후검토1회는 fallbackUsed=true라 SESSION_UNAVAILABLE; 판단에 사용하지 않았고 과금은 not_observed.

| 요청 | 결과 | 증거/한계 |
| --- | --- | --- |
| 4a 사전 기준선 | FAIL | 이전 두 런에서 strict_requires_concrete_model HTTP500, Workflow 진입 전 거절 |
| local SSE #1 | 모델/프로토콜 관측 | observedProvider=gemini, observedModel=gemini-3.8-flash, fallbackCount0, terminal/final 각1; live-local-1.json |
| local /chat #2 | 본문6 렌더링; 모델/fallback 메타 관측 | 22:43:16, request hash03e17ff1183c, trace774; 23:49경 owned session228 복원/export에서 observedModel=gemini-3.8-flash, observedProvider=gemini, fallbackCount0. 같은 runtime 로그 HTTP200/adapter success. provider wire/terminal count not_observed |
| public /chat #3 | 본문8 렌더링 | 22:46:38, devin-test synthetic, hash28f07f86f56c, trace778; 같은 runtime 상관관계 입증. provider wire/terminal count not_observed |
| local SSE #4 | FAIL | 22:54:30-31, hash83838c9a2d9a, adapter attempt1 실패; ERROR1/final0, observedModel 없음; live-local-2-sse.json |

local SSE #1 캡처기의 본문 추출은 실제 DTO의 data 필드를 읽지 않아 bodyChars0/원본 verdict FAIL을 남겼다.
이 원본을 덮어쓰지 않았다. 이는 빈 모델 답변의 증거가 아니며 모델/종료 관측과 본문 not_observed를 분리했다.
마지막 #4는 HTTP200 이후 model adapter 경계에서 실패했다. 같은 요청 API failure status=null/reason=unconfirmed, routingDebug httpStatus=n/a.
원래 provider 예외/수신 상태는 보존되지 않아 Gemini 원인을 단정할 수 없다. 공개 코드 backend_unavailable은 같은 요청 errorHash/length와 소스 allowlist의 일치에 근거한 제한된 추론이다.
원래 cause 미보존은 ModelSelectionException:15,56의 의도된 privacy 계약이며 ChatFailureClassificationFocusedTest가 검증한다. 추가 생성 없이 이 기존 공개 오류 분류 계약을 확인하고, upstream 진단 확장은 별도 좁은 관측 작업으로 남긴다.
성공 로그의 adapterAttempt/responseObserved는 model_adapter 증거이며 wireAttempt/providerAttempt/providerReceiptObserved=false를 생성 요금 증거로 바꾸지 않는다.
브라우저 스크린샷은 CDP capture timeout이 한 번 재시도 후 반복돼 not_observed. DOM 본문 관측과 동일 요청 서버 증거만 보고한다.

## 안전 경계 및 회계
모든 이 작업 lease begin/end는 release 완료. wp2-red는 guard를 먼저 끝낸 후 seal이 source-lease-drift로 HOLD됐다.
그 주기 원본 preimage, lease 이력, RED XML은 보존했고 성공 seal을 주장하지 않는다.
첫 source-green은 잘못된 suite FQCN 때문에 recorder verification 실패로 5개 source postimage를 guarded restore했고, 정확한 FQCN으로 재적용/검증했다.
전체 dirty Git diff에는 외부의 application-meta-display.yml/chat.js/GeminiGatewayContractTest 변경이 남아 있다.
이 작업 patch에는 금지 파일/grounding hunk 변경0개지만 “전체 Git diff의 금지 파일0개”라는 A8 문자 조건은 FAIL이다. 외부 변경을 정리하지 않았다.
증거 위치: data/agent-handoff/codex-autonomy/codex-gemini-chat-catalog-53b36b1f/.

## 추가 생성 없는 continuation 증거 — 2026-10-03
Task: codex-gemini-catalog-continuation-63636efa. source/tooling12 postimage 및 retained Java GREEN XML hash가 현재도 일치한다.
기존 로컬 synthetic 답변6을 /chat에서 복원하고 이 답변의 진단 링크로 answer-trace-bundle (2).zip을 다운로드했다.
ZIP SHA-256=759ae68663b7aaae4233c2049d78deaf0cd0a44aa8f4f59f2857b4258629cc43, bytes2090; AWX는 schema/checksum만 검증한다.
durable pointer의 reason=chat.trace_html.final은 과거 SSE terminal event가 정확히1개였다는 증거가 아니다.
bundle eventCount0/eventStatus=unavailable/historyComplete=false이므로 해당 terminal count는 not_observed로 유지한다.
추가 생성0회, 누적 검증 요청4/4 유지. 임시 debug 표시 설정은 false로 복원했다.
상세 provenance/Acceptance audit: data/agent-handoff/codex-autonomy/codex-gemini-chat-catalog-53b36b1f/continuation-audit.md.
전체 상태 PARTIAL: 정확한 JVM 정적 속성, 실패한 사전 baseline/마지막 SSE, 외부 forbidden dirty diff는 미충족 그대로다.
