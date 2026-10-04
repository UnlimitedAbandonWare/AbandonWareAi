외부 API: groq 호출 1회 성공 확인(SDK·api3 경계; 물리 HTTP 횟수 NOT_OBSERVED) / 기타 외부 호출 수 NOT_OBSERVED. 보조 HTTP 교환 2회는 provider 미식별이며, Groq wire 전송 횟수로 합산하지 않았다. 본 세션의 401/403/429는 관측되지 않았다.

최신 api3 지시서의 소스 수정·회귀 검증·실제 /chat 확인을 완료했다. 2026-10-03 19:30 KST 새 Edge context에서 llmrouter.api3 / 검색 AUTO / RAG ON으로 합성 하이젠베르크 질문을 제출했고, HTTP 200·final 이벤트·openai/gpt-oss-120b·assistant 본문 1260자를 확인했다. 같은 요청 hash:0e9d41a10615의 api3 fallbackCount=0, stage=final-response(1260자, evidenceCount=2), SSE first token과 final trace hashes를 연결했다. 해당 요청의 stream-failed는 0이다. 관리자 인증 상태는 불러오지 않았다.

- 원인 1: RouterDecisionCache.java:206에서 TimeoutException을 CompletionException(router_decision_leader_timeout)으로 감싼다. 구조 재현의 문자열 길이72·SHA256 앞12자리209ac7c8cd59가 두 기존 실패와 일치한다. 성공 로그는 최종 draft보다 앞선 보조 planner 단계였으므로 그 결과를 사용자 답변으로 salvage하지 않았다. 당시 원본 로그의 실제 cause chain은 NOT_OBSERVED이며 소스·동일 요청·재현으로 원인을 연결했다. accepted-run 대기와 controller 진단은 다른 세션의 현재 변경을 검증했으며 덮어쓰지 않았다.
- 원인 2: ModelRuntimeHealthTracker.reserveGroq가 JSON UTF-8 바이트 수를 TPM 토큰 수로 사용했다. 기존 실패 요청의 7910 bytes + 1024 output = 8934를 8000 TPM과 비교해 실제 Groq 전송 전에 거절했다. 현재는 기존 TokenCounter.estimateTextChatInput에 출력 상한을 더한다. 계정 증거·margin·quota·cooldown은 유지하고, 지원하지 않는 payload와 overflow·빈 메시지는 명시적 입력 오류로 처리한다.
- 원인 3: 품질 저하 답변의 finalAnswer.memorySaveAllowed=false가 controller의 TraceStore.clear 이후 소실돼 요약 저장이 계속됐다. 실제 실패 테스트에서 updateRollingSummary 호출을 확인했다. Workflow 반환 직후 요청 지역 boolean으로 보존해 stream/sync의 기존 요약·shadow vector 승격 호출 조건에 적용했다. assistant 대화 기록은 정확히1회 저장되며 metadata 부재의 정상 경로는 요약을 계속 갱신한다.

본 세션 수정 파일:

| 파일 | 현재 핵심 위치 | 내용 |
|---|---|---|
| main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java | 60,66 | Groq 예약 단위 교정, 기존 토큰 추정기 재사용 |
| main/java/com/example/lms/api/ChatApiController.java | 2460,2726,4567,4606 | 명시적 memory deny를 요청 안에서 보존해 stream/sync 저장 경계로 전달 |
| src/test/java/com/example/lms/llm/ModelRuntimeGroqAdmissionTest.java | 47,73 | 예약 단위·지원 불가 입력·빈 메시지·quota 반례 |
| src/test/java/com/example/lms/service/ChatWorkflowFinalVerificationReleaseGateTest.java | 1191 | UNKNOWN/REJECTED verification 뒤 postprocessor 오류 주입; 원문 draft 공개·메시지 누출·workflow memory/understanding 호출 금지 |
| src/test/java/com/example/lms/api/ChatApiControllerSyncLifecycleTest.java | 337,344 | stream/sync degraded final 전달 및 대화1회 저장, 요약 저장 금지, 정상 요청 요약 유지 |
| src/test/java/com/example/lms/api/ChatApiControllerTraceMetaTest.java | 1260 | additive extraMeta 생성자 검사 교정; trace/pipeline/non-null entropy 보존·공개 hint·private fixture 비노출 검증 |

현재 공유 ChatWorkflow.java:4490의 salvage는 FinalAnswerPostProcessor 실패 경계를 보호한다. 이미 release gate를 통과한 내용을 품질 저하 안내와 함께 보존하고 workflow memory writes를 차단한다. cancellation·SessionQuota·ResponseTerminal은 재던진다. UNKNOWN은 HOLD, REJECTED는 REJECT를 유지한다. 모든 post-LLM 단계의 임의 예외를 공개 답변으로 바꾸는 catch-all이라고 주장하지 않는다. 단위 검증은 workflow와 실제 controller commit 경계에서 각각 실행했으며 전체 workflow-to-controller 단일 fixture는 NOT_RUN이다.

실행한 검증:

| 검사 | 실제 결과 | 증거 |
|---|---|---|
| Groq admission 수정 전 RED | 6개 중4개 실패; 빈 메시지 반례 보강 전1개 실패 | evidence/groq-admission-red, evidence/groq-edge-red |
| Groq admission / health tracker / free tier guard | PASS 37: 6+15+16, 실패·오류·skip0 | evidence/groq-final-green/run.json |
| controller 요약 저장 RED | 1개 중1개 실패, updateRollingSummary @4032 | evidence/controller-memory-red/run.json |
| 첫 controller guard | FAIL 79개 중1개: TraceStore.clear 이후 정책 값 유실 | evidence/controller-memory-green/run.json |
| controller memory capture 이후 회귀 | PASS 80: 24+53+3 | evidence/controller-memory-final-green/run.json; 당시 source identity에 한정 |
| 외부 extraMeta 추가 뒤 constructor source probe | RED 80개 중1개 실패 @TraceMetaTest:1260; DTO의 기존 필드 보존은 유지 | evidence/controller-current-final/run.json |
| 현재 controller lifecycle / TraceMeta / diagnostic | PASS 80: 24+53+3, 실패·오류·skip0 | evidence/controller-trace-final/run.json, run163c532b-f1cc-408d-9478-7a0fbbf8f85f, coop cv-eb4e3be84fed4e70 inputDrift=false |
| 현재 workflow verification release + controller lifecycle | PASS 75: 51+24, 실패·오류·skip0 | evidence/a5-current-final/run.json, coop cv-3c68abf624fb464d inputDrift=false; controller24는 최종80에서도 재확인 |
| 원래 첨부 sampling 관련 3 suite | Gradle PASS 199:34+4+161 | evidence/sampling-current-final/xml-reconciliation.json |
| brief_save / dot_brief_python_exec | PASS Python20, 관련 source bytes 유지 | 이전 완료 단계의 실제 검증 |
| 최종 Verify-RAG | exit0, status=verified, target=partial, checks9/warns1/notRun0 | evidence/runtime-stable-final/run.json, runId3a8e074d-dca3-4c8d-bbfa-7a61134126f1, coop cv-811e9683a9044fd5 inputDrift=false |

최종 Verify-RAG는 20:26:24–20:27:06 KST에 compileJava/processResources, PID8728의18180 소유권, /chat 및 관리 health HTTP, source freshness(sourcesNewer=false)와 DevWatch를 검증했다. 런타임은 기존 DevWatch의 20:04:03 launcher(run20261003-200403-0bef11c7, springReused=false)에서 재기동됐다. 본 세션의 추가 Close는 launcher-busy로 거부됐고 실제 stop/Start를 강제하지 않았다.

verify의 exceptions 경고1개는 H2 DDL 오류들과 다른 요청 chat-222의 ModelSelectionException을 포함한다. 따라서 target=partial이며 서버 전체 오류0 또는 모든 경고가 pre-existing이라는 판정을 하지 않는다. 같은 FKnmjto3omhwy7xd1s3nmwcwbo5 중복만 이전 baseline에서도 확인됐다. 19:30 성공 요청의 stream-failed0은 동일 requestHash에 한정한다. 20:00–20:02 실행은 입력이 바뀌어 coop INVALIDATED였으므로 최종 PASS 근거에서 제외했다. 마지막 코드 변경 후 합성 질문은 다시 제출하지 않았다(라이브3회 상한 소진); 현재 소스 검증과 19:30 브라우저 proof의 시점을 구분한다.

주요 명령(전체 suite는 실행하지 않음):

```text
.\gradlew.bat -Pawx.splitBuildOutputs=true -Pawx.buildHostId=party-api3 --project-cache-dir build/agent-cache/party-api3 :test --tests com.example.lms.llm.ModelRuntimeGroqAdmissionTest --tests com.example.lms.llm.ModelRuntimeHealthTrackerTest --tests com.example.lms.agent.GroqFreeTierGuardTest
.\gradlew.bat -Pawx.splitBuildOutputs=true -Pawx.buildHostId=party-api3 --project-cache-dir build/agent-cache/party-api3 :test --tests com.example.lms.api.ChatApiControllerSyncLifecycleTest --tests com.example.lms.api.ChatApiControllerTraceMetaTest --tests com.example.lms.api.ChatApiControllerPostprocessFailureTest
.\gradlew.bat -Pawx.splitBuildOutputs=true -Pawx.buildHostId=party-api3 --project-cache-dir build/agent-cache/party-api3 :test --tests com.example.lms.service.ChatWorkflowFinalVerificationReleaseGateTest --tests com.example.lms.api.ChatApiControllerSyncLifecycleTest
Verify-RAG.bat
```

Acceptance:

| 항목 | 상태 | 증거/범위 |
|---|---|---|
| A1 원인 file:line 및 예외 | PASS | TimeoutException → CompletionException @RouterDecisionCache:206, 동일 길이/hash 구조 재현; 원본 cause chain 미관측은 명시 |
| A2 클래스 체인·첫 앱 프레임·메시지 제외 | PASS | 현재 controller 진단3개 GREEN; 최대5단계와 원문 미노출 |
| A3 수정 전 RED → 후 GREEN | PASS | admission 4실패→37GREEN, controller 저장1실패→최종80GREEN |
| A4 ChatApiControllerTraceMetaTest | PASS | 최종53개 GREEN, 취소 ERROR 처리 회귀 포함 |
| A5 답 살리기·품질 저하·대화 저장 | PASS, unit boundaries | 성공 모델 응답 뒤 processor 오류·degraded release·workflow write denial, stream/sync final과 대화1회 저장·요약 금지 GREEN; 검증 거부 유지2case GREEN |
| A6 실제 /chat api3 웹 질문 | PASS, 19:30 model-adapter/UI 경계 | 19:30 HTTP200, actual model openai/gpt-oss-120b, assistant1260자, 동일 요청 final-response, stream-failed0. Provider wire/receipt는 NOT_OBSERVED; 최종 코드 이후 동일 질문 재실행 NOT_RUN(3회 상한) |
| A7 scoped ownership·lease·Git | PASS, 본 세션 범위 | 소유 lease0, 현재 tested source hashes 일치, 선언한 대상만 수정. 다른 세션 hunk 보존, Git mutation0. 공유 tree의 외부 변경을 본 세션 변경으로 주장하지 않음 |

HOLD 및 범위 밖:

- 현재 api3의 source/test/runtime 검증은 완료했고 19:30의 A6 UI 결과는 보존했다. 최종 bytes로 같은 모델 질문을 다시 제출한 결과는 NOT_RUN(라이브3회 상한). 초기 protected launcher refusal과 여러 READY 이후 JVM 종료는 기록으로 보존한다. 추가 Start/Close를 강제하지 않았고 launcher/mutex/외부 lease를 건드리지 않았다. 최초 own Start 요청1회는 protected refusal, 최종 controller 변경은 기존 DevWatch가 반영했다. 추가 재기동 요청은 하지 않았다.
- TraceMeta 회귀의 cycle-trace-contract-v2 기록은 DEFERRED 검증 전에 lease가 종료돼 finish가 source-lease-drift HOLD였다. 원본 preimage·diff·HOLD를 보존했고 source를 되돌리지 않았다. 동일 current bytes를 새 owned lease 아래 cycle-trace-current-verified로 보존·봉인하고 실제 GREEN80 run163c532b에 hash-bound 검증해 close-out했다. metadata HOLD를 실제 테스트 실패로 바꾸거나 은폐하지 않았다.
- 공유 ChatWorkflow 19:50와 controller/DTO 20:02 변경은 author/journal을 확인할 수 없는 외부 변경으로 기록했다. 원본 hunk는 유지하고 영향받은 검사만 새로 실행했다. 본 세션 final-applied.patch에는 자기 변경6개만 담았다.
- Groq HTTP provider receipt 및 complete runtime lineage는 NOT_OBSERVED. 해당 요청의 OBSERVABILITY_GAP/runtime_lineage_missing/DEGRADE를 숨기지 않는다. 추후 기존 adapter 계측 경계에서 별도 확인할 항목이며 이번 모델/UI 성공을 물리 HTTP 성공 count로 바꾸지 않는다.
- frontend Sync not_attempted 후 재조회는 프런트 후속 필요. chat.js/frontend 금지 범위이므로 수정하지 않았다.
- NamedChatModel breaker identity collapsed 경고는 이번 직접 원인으로 입증되지 않아 기록만 유지.
- 최초 요청의 관리자 실제 로그인/잘못된 계정/로그아웃 browser 인증 증거는 확보하지 않았다. 최신 api3 지시서의 PROTO_OPEN 유지·admin 강화 금지·질문 카드 금지를 적용했으며 이 단계의 성공으로 주장하지 않는다.
- GeneralGraph/deferred worker 전체 통합·모든 장기 저장 경계까지 검증한 것으로 확대하지 않는다. 이번 memory regression은 기존 workflow writers 및 controller summary/shadow-vector seam에 한정한다.

원래 첨부에서 통합한 명시적 sampling preference 보존과 brief_save의 ASCII8자리 date 검증도 유지한다. 첨부 설명과 과거 handoff는 참고로 사용했고 현재 Gradle/sourceSets와 실제 코드가 구현 기준이다. sampling recorder가 다른 buildHost XML 폴더를 바라본 evidence_incomplete는 실제 새 XML 시각·hash·sourceIdentity로 reconciliation했으며 recorder 자체 PASS로 부르지 않는다.

외부 규격 확인일은 2026-10-03, 적용 버전은 Java17 / Spring Boot3.3.4 / LangChain4j1.0.1이다. [Groq rate limits](https://console.groq.com/docs/rate-limits)의 TPM은 토큰 단위다. 재사용한 TokenCounter는 provider 확정 usage나 gpt-oss 전용 tokenizer가 아닌 기존 추정값이며 계정 한도/margin을 우회하지 않는다. [Playwright authentication](https://playwright.dev/docs/auth)에 맞춰 fresh context를 사용했고 storageState·cookies·token·trace를 저장하지 않았다.

Git은 읽기만 했다. 현재 공유 HEAD433bd6f56bc68b216f4cba201b91926564ddf789와 entryHEAD4150b2822f2c1b49773cacaadd36ddbab8ff1412의 ancestry는 확인했고 원격 main과 현재 로컬 변경 관계가 불명확해 원격 CI/snapshot을 현 source보다 우선하지 않았다. commit/add/push/fetch/merge/branch 변경은 하지 않았다.

PLUGIN_USAGE:
- Superpowers: USED(systematic-debugging, verification-before-completion)
- Browser: USED(local Playwright fresh context, 실제 /chat HTTP·final·assistant 본문·동일 요청 로그)
- GitHub: USED(read-only SHA comparison; remote CI not used as current source proof)
- Exa: USED(official Spring Security, Playwright, Groq contracts)
- AWX Control Tower: USED(actual failing tests의 sanitized build logs만 분류; other 판정은 source/stack으로 재확인)
- AWX Control Tower 복구: NOT_USED
- Computer: NOT_USED
- GLM=SESSION_UNAVAILABLE(초기 전달 증거 없음; 최신 생성 금지 이후 생성 없음, 독립 검토는 기존 built-in explorer)
- Vercel: NOT_USED
- Sites: NOT_USED
- Supabase: NOT_USED
- Meta Wearables Webapp: NOT_USED
- Data: NOT_USED
- Visualize: NOT_USED
- Plugin Management: NOT_USED
