# mxasain API + trace full-force 결과 — 2026-09-27

작업: `mxasain-full-force-0927-010677cd` · root: `C:/AbandonWare/demo-1/demo-1/src` · 검증 시각: 2026-09-27 14:39 KST 이후 마감.

**승인된 단일 API의 실제 생성, 선택 복원, WP0–WP8의 범위 내 수정과 LIVE 검증을 완료했다. API-03은 명시적으로 허용된 부작용 조건에 따라 HOLD, QUERY-01/VECTOR-01 추가 이식은 근거가 없어 SKIP이다. 전체 저장소가 완전히 정상이라는 판정은 아니다.** 최종 영향 범위 Java 통합 검증은 165/167 통과(수정 전부터 재현된 동일 2건 실패), Verify-RAG는 exit 0 / target=partial(DDL 예외 128건)이다.

현재 사용자 full-force 목표 및 `agent-prompts/codex-mxasain-full-force-20260927/PASTE_CODEX.txt`를 실행 범위로 사용했다. 첨부 audit/directive/probe는 기술 근거다. 최신 목표가 WP6/7 기존 저장소 확장을 승인하므로 이전 DIRECTIVE_V2의 해당 OUT 경계와 초반 종료 방침은 적용하지 않았다. 커밋·push·배포·의존성 업그레이드·PROTO_OPEN 변경은 하지 않았다.

## 단위별 결과

| 단위 | 판정 | 변경 / 근거 | 실제 원격 생성 |
|---|---|---|---|
| BASE / WP0 | DONE | Java 17, Boot 3.3.4, LangChain4j 1.0.1 및 active main sourceSet 확인. 작업별 리스/체크포인트 사용. F01 typed scalar와 F02 nested table을 현재 제품·브라우저로 대조. | NO |
| API-01 | DONE | 공통 remote switch는 false 유지, api3만 scoped allowlist + manifest로 직접 선택. exact model 실패를 몰래 교체하지 않는다. Groq 입장 검사 전에 wire 시도를 기록하던 경계 및 strict rebuild의 출력 상한 유실을 수정. 최종 카탈로그 api3=true, openai-economy=false, mistral-medium=false. | YES |
| API-02 | DONE | 카탈로그 비동기 로딩 중 복원 모델 ID를 로컬 기본값으로 덮지 않음. 사용자 변경 우선·비활성 placeholder 보존. 실제 reload에서 api3/strict/search OFF/RAG OFF 유지. | NO; API-01 세션 복원 |
| API-03 | HOLD | 자동 main 후보와 보조 호출의 목적 격리가 없고 SelfAsk가 api3를 직접 소비. fallback-only/weight를 확대하면 보조 호출 유입 가능. 자동 승격하지 않음. | NO |
| WP1 / TRACE-01 | DONE | SafeRedactor의 실제 생산 키에 한정해 boolean/count/finite number 보존. prompt 원문·비밀·중첩 임의 값 보호 유지. LIVE true / 0 / 0.618 확인. | NO |
| WP2 / TRACE-02 | DONE (현재 제품 재검증) | 현재 direct-row 제한은 중첩 표의 자손 행을 부모 row budget으로 제거하지 않음. 불필요한 제품 재수정 없이 Chromium 12/12 및 LIVE 뒤 진단 그룹·중첩 표 유지 확인. | NO |
| WP3 / TRACE-04 | DONE | 성공한 poll/heartbeat 관측 요청의 자동 capture만 억제하여 답변 링 축출을 줄임. 오류·명시 debug·memory capture 유지. exact assistant/pointer/snapshot 결합; 전역 latest 대체 없음. 재시작 보관은 WP6에서 해결. | NO |
| WP4 / TRACE-05 | DONE | 기존 DebugEventStore에 requestIdHash AND traceIdHash exact 필터를 limit 전에 적용하고 bounded cursor page 추가. 관리자 화면도 서버 페이지 사용. LIVE limit=1의 연속 두 페이지가 서로 다른 동일 요청 행을 반환. | NO |
| WP5 / TRACE-06 | DONE | SSE 재연결 시 cursor gap/eviction을 명시하고 UI 경고 유지. timestamp 순서에 의존하지 않는 cursor 계약, empty ring·dedup·worker 회수 테스트. LIVE hello→gap→debug-event, historyComplete=false. | NO |
| WP8 / TRACE-03 | DONE | 실제 생산되는 Query Transformation, Keyword Selection, Vector, Prompt, Memory 등의 안전 필드를 기존 TraceHtmlBuilder에 연결. LIVE Query Transformation 포함 여러 그룹 확인. 기여도/위험 수치는 관측 기반 휴리스틱이며 실제 재실행 품질 증분이나 인과 확률이 아님을 명시. | NO |
| WP6 | DONE (안전한 상세 투영 보관) | 기존 chat system pointer v3에 제한된 typed 진단을 저장. 같은 ChatApiController에 소유 세션의 exact snapshot endpoint 하나를 추가(html/bundle format). 재시작 뒤 같은 답변의 typed 20개 필드/요약 복원 및 내려받기 확인. 별도 snapshot 저장 플랫폼 없음. | NO |
| WP7 | DONE (기존 출력 통합) | 기존 failure-pattern 감지 결과를 redacted bounded DebugEventStore 이벤트로 연결. raw logger/message/throwable/디스크 tail 없음. 기존 OTel 연결 회귀 검증; exporter 구조 변경 없음. | NO |
| QUERY-01 | SKIP (추가 이식 없음) | 기존 query transformer의 fail-open/constraint 동작을 관련 focused suites로 확인. 구형 동작을 추가해야 할 fixture 이점이 확인되지 않음. | NO |
| VECTOR-00 | DONE (읽기 전용, 기록 정합성 미판정) | 설정 진단 HTTP200과 fingerprint/legacy/shadow/quarantine 계약 테스트. dimensions=1536, allowLegacy=false, missing-metadata bypass=false, Upstash 비활성. 저장 레코드 전체 청결은 not_observed. | NO |
| VECTOR-01 | SKIP | 오염 유입/검색 포함 근본 원인 재현 없음. wipe/removeAll/namespace 초기화/재임베딩 미실시. | NO |

## 실제 API와 브라우저 증거

- 승인 경로: UI `llmrouter.api3` → 실제 `openai/gpt-oss-120b`. fresh dev run `20260927-131400-1d2a6242`, session 96, 요청 해시 `4dfdc37d9263`. adapter 1회, provider HTTP200, 응답 2652 bytes, 응답 해시 prefix `d7e113535018`, 브라우저 본문 표시. 엄격 선택의 출력 예산에 따른 끝부분 제한은 남아 있다. RequestProof client flag는 not_observed이며, 실제 전송은 provider ledger/lifecycle와 렌더된 본문으로 확인했다.
- API 시도는 모두 같은 성공으로 합치지 않았다. compact 160 예산의 이전 요청 `e2fa21c01032`는 HTTP200 뒤 `LLM blank response`로 실패했다. reasoning 예산 소진 가능성은 추론이며 확정 원인이 아니다. 다른 요청의 TPM 초과는 prewire 차단이었다. 가드·증빙·quota를 우회하지 않았다.
- API01 저널의 `assistant454` 표기는 trace metadata ID였다. session96의 실제 assistant ID는 별도 검증하지 않았으므로 본 보고의 성공 결합은 session/request hash를 사용한다.
- 로컬 greeting session97/assistant456 및 일반 질문 session98/assistant461에 답변 본문이 나타났으며 HOLD/backend_unavailable가 없었다.
- session98의 exact snapshot `f4a0d9d4-0eab-411e-b272-78f94191a25e`: 재시작 전 JSON/HTML HTTP200, Query Transformation 포함 그룹 관측. fresh PID50688 재시작 후 전역 in-memory snapshot은 HTTP404이나 소유 세션의 답변 상세와 bundle은 정상 복원되었다.
- 재시작 전 bundle은 typed 20개 필드와 exact correlated events 8개, 재시작 후 bundle은 같은 요약/typed 값이다(JSON 의미 동등). 모든 entry SHA-256 검증 통과. 이후 events는 `ring_expired_or_restarted`, logs는 `raw_application_logs_not_collected`로 명시되며 빈 파일로 성공을 꾸미지 않는다.
- durable 복원 UI는 보관한 안전 필드를 Raw TraceStore 영역에 표시한다. 과거의 전체 HTML/그룹 배치·임의 문자열·이벤트 전 이력을 영속 보관하는 기능은 아니다. v1/v2 legacy pointer는 호환하지만 이미 만료된 원본 상세를 만들어내지 않는다.
- diagnostics 펼치기/다운로드는 모델·검색·기억 저장을 실행하지 않는다. Chromium fixture 관측 networkCalls=0/generationCalls=0; LIVE 진단 동작에서 새 답변 생성은 없었다.
- 관리자 debug-events는 PROTO_OPEN 현재 계약으로 접근·필터·페이지를 검증했다. production 로그인/잘못된 계정/로그아웃 차단은 목표의 PROTO_OPEN 보존 조건과 상충하므로 수행하지 않았고, 이를 인증 보안 검증 성공이라고 주장하지 않는다.

상세 정제 증거: [final-live-evidence.json](../../data/agent-handoff/codex-autonomy/mxasain-full-force-0927-010677cd/final-live-evidence.json), [bundle 비교](../../data/agent-handoff/codex-autonomy/mxasain-full-force-0927-010677cd/live-bundle-comparison.json).

## 검증 결과와 한계

| 검증 | 실제 결과 | 증거 |
|---|---|---|
| 최종 영향 범위 Java 통합 17 suites | 167 실행, 165 PASS / 2 FAIL / 0 skipped; exit 1 | `verify-final-integration/run.json` |
| 동일 실패 수정 전 baseline | 같은 두 테스트 2/2 FAIL | `verify-api01-bound-baseline/run.json` |
| Vector / query / explain focused 13 suites | 118 PASS, exit 0 | `verify-vector-explain-query/run.json` |
| 최종 Chromium | 12/12 PASS, network 0 / generation 0, exit 0 | `verify-final-chromium/command.log` |
| 최종 trace restore / bundle / admin UI Node | 43 PASS, exit 0 | `verify-wp6-bundle-ui/command.log` |
| 마지막 휴리스틱 문구/기여도 Java | 11 PASS, exit 0 | `verify-wp8-label/run.json` |
| 최종 Verify-RAG | exit 0; verified / target=partial; 9 checks, 1 warning, 0 not-run | `var/debug/dev-20260927-143808-verify.log` |

실패 2개는 `ChatWorkflowStrictSingleAttemptHttpIntegrationTest.providerOwnedTimeoutBeforeGenerousRequestDeadlineStillRecordsFailure`, `providerThrownTimeoutWhileRequestCapStillHasTimeRecordsProviderFailure`이다. 기대한 `recordCurrentRequestRouteFailure`와 실제 request-scoped 기록 호출의 Mockito 불일치가 수정 전에도 동일하게 재현되었다. 이 두 건에만 baseline 판정을 적용한다. 테스트 skip/보호 기대값 약화로 녹색을 만들지 않았다. 전체 무필터 `:test`는 실행하지 않았다.

최종 Verify-RAG는 새 JVM PID50688, springReused=false, DevWatch PID58452 armed, compile/processResources·HTTP·ports·freshness current·공유 Ollama health를 확인했다. H2 DDL의 이미 존재하는 constraint 등 예외/ERROR hit 128건이 남아 target=partial이다. 이 128은 서로 다른 결함 수를 뜻하지 않는다. DB 정리/DDL 변경으로 범위를 넓히지 않았다. AWX build_error_mine은 실제 실패 로그만 분류했고 `other`는 원인 증명이 아니다.

각 실행의 **정확한 commandArgv, exit, suite totals, 실패, 로그 경로/해시**는 [verification-index.json](../../data/agent-handoff/codex-autonomy/mxasain-full-force-0927-010677cd/verification-index.json)에 보관했다. Node/Chromium runner의 XML totals=0은 0개 실행 뜻이 아니며 실제 command.log의 TAP/브라우저 결과를 사용한다. 단위 검증과 통합 검증은 겹치므로 수치를 합산하지 않는다.

주요 명령은 `.\gradlew.bat test --tests <해당 FQCN>`, `node --test src/test/js/chat-trace-ui.test.cjs src/test/js/chat-trace-restore.test.cjs scripts/debug_events_ui_contract_tests.js`, 기존 Chromium harness, `Verify-RAG.bat`이다. 각 Java/JS 단위의 원격 API는 NO; API-01 LIVE만 YES다. 컴파일/재기동이 provider 생성 증명은 아니다.

## 보호·동시 변경·미관측 범위

- source lease 및 preimage가 일치하는 단위만 변경했다. 실패 cycle은 guarded rollback 후 새 cycle로 수정했다. 게이트가 정상 Java cookie method-reference, 공개 dummy comment/empty property, HTML 내 실제 inline JS의 런타임 CSRF 표현식을 비밀로 오탐한 부분은 해당 scanner와 회귀 테스트로 고쳤으며 제품 의미를 이름 변경으로 회피하지 않았다.
- `ChatModelCatalogService.java`는 본 작업 api01-r2 이후 외부 변경(`runtimeApprovedRoutes/registerApprovedRoute`)이 발견되었다. 작성자/사유를 추정하지 않았고 덮어쓰거나 되돌리지 않았다. 현재 소스의 카탈로그 테스트 및 최종 HTTP allowlist 결과는 재확인했다. 이 추가 코드를 본 작업 변경으로 귀속하지 않는다.
- `LocalLlmProcessManager.java`의 동시 변경과 PROJECT_STATUS의 타 작업 행도 보존했다. status 갱신은 fresh hash + row-only 도구로 수행한다.
- API-03 HOLD를 풀려면 main auto 후보와 SelfAsk/판정/배치/Display의 호출 목적 격리를 별도 검증해야 한다. 이 작업에서 안전성이 없는 자동 확대를 하지 않았다.
- persistent vector record 청결, 전체 로그 이력, 외부 OTel exporter 전달은 not_observed. AutoLearn 필수 intake `data/agent-handoff/codex/{manifest.json,cycles.jsonl,rejected.jsonl}` 및 live `data/train_rag.jsonl`이 없으므로 AutoLearn 수정/학습 성공은 주장하지 않는다.
- GLM 독립 검토는 계정의 unsupported model로 불가하여 native read-only explorer를 사용했다. 검토 동의 대신 실제 소스/시험/브라우저 출력으로 판정했다.
- Supabase/Sites/Meta 기기/배포/인증 hardening·외부 저장소 write는 사용하지 않았다. 이번 완료는 로컬 승인된 패치 범위의 완료이며 전 저장소 무결함 또는 production 준비 판정이 아니다.

## 변경 파일과 복구

아래는 24개 검증된 source/test/scanner cycle의 43개 경로다. [source-change-index.json](../../data/agent-handoff/codex-autonomy/mxasain-full-force-0927-010677cd/source-change-index.json)에 cycle, 본 작업 postimage, 현재 hash, 전체 ordered diff 경로를 보관했다. 최종 보고와 PROJECT_STATUS는 별도 문서 cycle이다.

| ��� | ������ ���� cycle |
|---|---|
| `main/java/ai/abandonware/nova/autoconfig/NovaFailurePatternAutoConfiguration.java` | `wp7-log` |
| `main/java/ai/abandonware/nova/orch/failpattern/FailurePatternOrchestrator.java` | `wp7-log` |
| `main/java/com/example/lms/api/ChatApiController.java` | `wp6-hash` |
| `main/java/com/example/lms/api/ChatSessionDetailResponseBuilder.java` | `wp6-endpoint` |
| `main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java` | `wp6-codec` |
| `main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java` | `wp6-codec` |
| `main/java/com/example/lms/api/DebugEventsDiagnosticsController.java` | `wp5-empty-gap` |
| `main/java/com/example/lms/debug/DebugEventStore.java` | `wp4-page` |
| `main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java` | `api01-admission` |
| `main/java/com/example/lms/llm/RequestedModelSelection.java` | `api01-ceiling-r2` |
| `main/java/com/example/lms/service/ChatModelCatalogService.java` | `api01-r2` |
| `main/java/com/example/lms/service/ChatWorkflow.java` | `api01-bound-r4` |
| `main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java` | `api01-bound-r4` |
| `main/java/com/example/lms/service/trace/TraceHtmlAblationAttributionCalloutRenderer.java` | `wp8-label` |
| `main/java/com/example/lms/service/trace/TraceHtmlBuilder.java` | `wp8-groups-r2` |
| `main/java/com/example/lms/trace/SafeRedactor.java` | `wp8-groups-r2` |
| `main/java/com/example/lms/web/TraceFilter.java` | `wp3-observers-r2` |
| `main/resources/application.properties` | `api01-r2` |
| `main/resources/configs/cloud-models.manifest.yaml` | `api01-r2` |
| `main/resources/static/js/chat-trace-ui.js` | `wp6-bundle` |
| `main/resources/static/js/chat.js` | `api02-restore-r2` |
| `main/resources/templates/debug-events.html` | `wp6-bundle` |
| `scripts/codex_work_checkpoint.py` | `gate-html-parser` |
| `scripts/debug_events_ui_contract_tests.js` | `wp5-gap` |
| `scripts/test_checkpoint_html_runtime_expressions.py` | `gate-html-parser` |
| `scripts/test_checkpoint_java_cookie_reference.py` | `gate-cookie-r2` |
| `scripts/test_checkpoint_property_comments.py` | `gate-empty` |
| `src/test/java/ai/abandonware/nova/orch/failpattern/FailurePatternDebugEventTest.java` | `wp7-log` |
| `src/test/java/com/example/lms/api/ChatSessionTraceDetailTest.java` | `wp6-hash` |
| `src/test/java/com/example/lms/api/ChatTraceDurableDetailTest.java` | `wp6-codec` |
| `src/test/java/com/example/lms/api/DebugEventsDiagnosticsControllerSseLifecycleTest.java` | `wp5-empty-gap` |
| `src/test/java/com/example/lms/api/DebugEventsPageTest.java` | `wp4-page` |
| `src/test/java/com/example/lms/llm/ModelRuntimeGroqAdmissionTest.java` | `api01-admission` |
| `src/test/java/com/example/lms/llm/gateway/CloudModelRouteClassifierTest.java` | `api01-r2` |
| `src/test/java/com/example/lms/service/ChatModelCatalogServiceTest.java` | `api01-r2` |
| `src/test/java/com/example/lms/service/ChatWorkflowStrictSingleAttemptHttpIntegrationTest.java` | `api01-ceiling-r2` |
| `src/test/java/com/example/lms/service/routing/ExactRequestedModelTest.java` | `api01-bound-r4` |
| `src/test/java/com/example/lms/service/trace/TraceHtmlAblationAttributionCalloutRendererTest.java` | `wp8-label` |
| `src/test/java/com/example/lms/service/trace/TraceHtmlQueryTransformationTest.java` | `wp8-groups-r2` |
| `src/test/java/com/example/lms/trace/TraceDiagnosticProjectionTest.java` | `wp1-typed` |
| `src/test/java/com/example/lms/web/TraceFilterBreadcrumbTest.java` | `wp3-observers-r2` |
| `src/test/js/chat-model-picker.test.cjs` | `api02-restore-r2` |
| `src/test/js/chat-trace-ui.test.cjs` | `wp6-bundle` |

작업 루트: `data/agent-handoff/codex-autonomy/mxasain-full-force-0927-010677cd/`. 각 cycle의 `manifest.json`, `before/*.bin`, `change.diff`, `checkpoint.json`을 모두 보존한다. [applied-patch-series.patch](../../data/agent-handoff/codex-autonomy/mxasain-full-force-0927-010677cd/applied-patch-series.patch)는 검증된 순차 diff의 증거 묶음이며 단일 apply-ready 패치가 아니다.

복구는 의존 cycle의 역순으로, 현재 대상과 해당 sealed postimage 일치를 확인하고 필요한 source lease를 획득한 뒤 `python -B scripts/codex_work_checkpoint.py restore --root . --run <exact-cycle>`를 사용한다. 불일치한 catalog/타 세션 파일은 복구 HOLD이며 강제 덮어쓰기하지 않는다. 옛 HEAD로 복원하거나 shared build를 wipe하지 않는다. 준비 상태로 남은 초기 scanner 시도의 preimage도 회복 증거로 보존한다.

이번 작업의 최종 artifact/증거/복구 bytes는 보존한다. surplus 삭제 후보는 0개이며 hash-bound 완료 receipt만 기록한다. 커밋은 사용자의 금지에 따라 시도하지 않는다.

