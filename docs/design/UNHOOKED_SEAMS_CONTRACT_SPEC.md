# UNHOOKED_SEAMS_CONTRACT_SPEC — 미체결 4대 Seam 경계 계약

> 목적: `rw (2).txt` 분석에서 확인된 4대 미체결 주제(Meta Ray-Ban Display,
> 음성전사 ASR, Graph RAG 내결함성, Jev 응용 경계)의 **아키텍처 경계 계약**을
> 한 문서에 고정한다. 제품 Java/UI 수정은 Codex 소유 — 이 문서는 계약과
> 라이브 앵커만 제공한다.
>
> 작성: 2026-10-05, Devin `devin-unhooked-seams-companion-1bb31a8f`.
> 상태 표기: `사실` = 이 체크아웃 라이브 소스/설정에서 확인, `계약` = 유지되어야
> 할 불변(현재 코드가 만족하면 잠금, 미만족이면 갭필 대상), `정책` = 기존 룰/정책
> 문서에서 가져온 판정 기준, `미결` = 의도적 미확정.

관련 SSOT: `docs/design/TRI_SYSTEM_ISOLATION_SPEC.md`(삼중 시스템 격리),
`docs/agents-rules/DEMO1-TRI-SYSTEM-SEAM-ISOLATION.md`, `docs/PRIMARY_SURFACE.md`,
`docs/agents-rules/DEMO1-EVIDENCE-ZERO-RELEASE.md`, `docs/volatile-knobs.md`,
`configs/api-routing.yaml`.

정적 검증: `python -B scripts/probe_unhooked_seams_guard.py --dry-run`
계약 검증: `python -B scripts/test_rag_context_starvation_contract.py`
Codex 갭필 청사진:
`data/agent-handoff/devin-unhooked-seams-companion-20261005/FOR_CODEX_RAG_GAPFILL.md`

---

## S1. Meta Ray-Ban Display seam

| # | 계약 | 근거(사실) |
|---|---|---|
| D1 | 렌즈 표시는 **서버 선택 컨텍스트**에서만 유도된다. `ChatConversationContext`는 "Server-selected conversation data. Never deserialized from a public request"이며 `focusAnswerLengthChars`(80..800, `invalid_focus_answer_length`)를 유일한 Display 신호로 운반한다. | `ChatConversationContext.java` L6-9, L24 |
| D2 | `focusAnswerLengthChars != null`이면 `StandardPromptBuilder`가 `DISPLAY FOCUS OUTPUT` 블록을 쓰고, `!focusOutput` 게이트가 `SECTION TEMPLATE`/`minWordCount`를 보호한다. 메인 `/chat`은 이 필드를 가질 수 없다. | `StandardPromptBuilder.java` L529, L538-545, L623-625 |
| D3 | Display 설정(`LensDisplayPrefs` 15필드)은 `assets/display` + `/api/assist/display/relay/lens-settings` + `application-meta-display.yml` 안에만 산다. `ChatRequestDto`·`ChatDefaultsProperties`·`application.yml`·`application-llm.yaml`에는 display/lens/focus/hintTarget 키가 0개여야 한다. | `LensDisplayPrefs.java`; 누출 스캔 사실(2026-10-05): main DTO/defaults/yml 0건, 표시 키는 `application-meta-display.yml`에만 존재 |
| D4 | 길이·TTL·페이지·큐 주기는 **설정 구동 변수**다. 범위: hintTargetChars 240..1100, TTL/autoPage 1..100 s(autoPage 0=off), triggerQuiet 500..30000 ms, cueCooldown 1..120 s, forceAfter 1..600 s. 저장된 Fold `lensSettings`가 공장 기본(20 s TTL, 5 s 페이지, 2.5 s quiet, 10 s cooldown, 180 s forceAfter)을 이긴다. 180 s 저장값을 자동 클램프 금지. | `LensDisplayPrefs.java` L13-36, L58-60; `docs/volatile-knobs.md` |
| D5 | Quick/이미지 경로의 `useRag=false`/`useWebSearch=false`/`webTopK=0`은 `NovaFocusAnswerService` 어댑터 경계 안에 국한 — `ChatWorkflow`/RAG 기본값 오염 금지. | `NovaFocusAnswerService.java`; tri-system I2 |
| D6 | 렌즈 출력은 캡션(`DisplayContentView.Transcript`)+힌트 카드(`TextCard`)로 바인딩되며 공유 하드캡 `HINT_TEXT_MAX=1180`, `lens-conversation-chars=280`을 넘지 않는다. 마크다운/인용 덤프는 렌즈 표면 계약이 아니다. | `DisplayConversateController.java` View L71-72; `volatile-knobs.md` |
| D7 | 생성 모델은 서버가 `NovaFocusSettings`(ExecutionTarget AUTO/API_ONLY/LOCAL_ONLY) + `configs/api-routing.yaml` 정책으로 해결한다. 렌즈/클라이언트가 모델을 고르거나 박는 경로는 없다 — "특정 OAuth 모델 고정"은 SSOT 라우팅의 결과이지 렌즈 계약이 아니다. | `NovaFocusSettings.java`; `volatile-knobs.md` |
| D8 | 600×600 우측 렌즈 HUD(가산 블렌딩, 검정=투명)는 OFFICIAL 하드웨어 계약. 렌즈엔 키 입력이 없으므로 autoPage가 유일한 자동 페이지 경로(0=off는 유효한 opt-out). | `meta-rayban-display-runtime` rule; `receiver.js` DISPLAY_DEFAULTS |

## S2. 음성전사(ASR) seam

| # | 계약 | 근거(사실) |
|---|---|---|
| A1 | PCM 오디오 수신은 `/api/assist/display/audio/{start,chunk,chunk-batch,stop}` 뿐이며 `audioGuard()`로 이중 게이트: `conversate.display.audio.enabled`(기본 `false`, env `CONVERSATE_DISPLAY_AUDIO_ENABLED`) 미설정 시 `display_audio_disabled`(404), 브리지 없으면 `asr_disabled`(503). | `DisplayConversateController.java` L49-50, L556-629 |
| A2 | 오디오 바인딩은 **페어링 폰 생산자** 전용: standalone은 phoneTest+owner 일치, 전사 세션은 `paired_phone_required`(owner==phoneOwner). `requireProducer(clientId)`가 바인딩된 생산자 클라이언트만 PCM을 받는다. | `DisplayConversateController.java` L631 `audioBinding` |
| A3 | 전사기는 `ConversateAsrBridge`(`@ConditionalOnProperty conversate.enabled`) 하나로 수렴 — transport 선택은 `local`(자식 whisper 스크립트) 또는 `deepgram`/`soniox`/`auto` 클라우드 스트림. 선택지는 설정(`conversate.asr.provider`)이며 요청 선택(`requestSelectionEnabled`)은 `StreamPolicy.engine` 경유만. | `ConversateAsrBridge.java` L18-65 |
| A4 | 렌즈는 **텍스트 수신만** 한다: `/api/assist/display/lens{,/text,/ack,/link}` 폴링은 caption+hint 카드만 반환하고 PCM 디코딩 경로가 없다(`request.pcm()`은 audio 엔드포인트 내부에만 존재). 안경 마이크 하드웨어 의존 금지. | `DisplayConversateController.java` L297-341 vs L575-626 |
| A5 | 수용된 전사는 `ChatConversationContext.Transcript`(서버 스코프, RAM-only, `toString` redacted, ≤12 turns/≤2000 tokens, sourceId sha256 형식, speaker=UNKNOWN)로만 진입 — 오디오 자체는 LLM/메모리 파이프라인과 물리 분리. | `ChatConversationContext.java` L15-24 |
| A6 | ASR 입력 상실은 `ASR_INPUT_LOST`(마지막 입력 후 5 s)로 실패 분류되고 세션은 `text_fallback`으로 내려간다 — 오디오 파이프라인 장애가 텍스트/힌트 경로를 죽이지 않는다. | `ConversateAsrBridge.java` L56; `DisplayConversateController.java` L570 |
| A7 | 백엔드 선택지(로컬 whisper 자식 vs 클라우드 스트림)는 **미결 정책** — 지시서 ASK_ONCE와 동일 항목. 기본: 브라우저 Web Speech API/클라우드 우선, 로컬 컨테이너는 opt-in. | `conversate.asr.provider` 선택 로직 `사실`; 운영 기본값 `미결` |

## S3. Graph RAG 하이브리드 seam

| # | 계약 | 근거(사실) |
|---|---|---|
| G1 | 검색 lane은 `vector`/`bm25`/`graph`/`web`으로 분리 나열되고, `UnifiedRagOrchestrator` trace가 `List<Doc> vector`/`bm25`/`fused`를 분리 기록한다. | `UnifiedRagOrchestrator.java` L201-205 |
| G2 | **lane 장애 격리**: 한 lane 예외는 그 lane의 빈 후보 + 실패 trace로 수렴하고 다른 lane 후보를 지우지 않는다. `toDocsOrEmpty(...,"VECTOR-EMERGENCY")`와 emergency vector-only 재시도(L734-769)가 선례 — 동일 패턴이 모든 lane에 적용되는 것이 계약. | `UnifiedRagOrchestrator.java` L734-769 |
| G3 | 융합기 `WeightedReciprocalRankFuser`는 입력 리스트 단위로 RRF하며 각 보조 스테이지가 `fail-soft stage=...`로 감싸져 있다(`empty_input`/`empty_output` trace). 융합 자체는 예외를 던지지 않는다. | `WeightedReciprocalRankFuser.java` L122, L172, L195-447 |
| G4 | `retrieval.vector.required`(기본 `false`)가 `requireDependency("vector",...)`를 만족시키는 게이트 — `true`로 설정되지 않는 한 vector lane 부재가 전체 융합을 기아시키면 안 된다. | `UnifiedRagOrchestrator.java` L107, L273 |
| G5 | 그래프 lane(Neo4j `Neo4jKnowledgeGraphClient`, `GraphRagChunkingService`, `KnowledgeGraphHandler`, `BrainStateService`)은 선택 lane — Neo4j 다운/미설정이 vector+bm25+web 결과를 0으로 만들면 안 된다. | `service/rag/graph/*`, `service/rag/kg/*` |
| G6 | 리랭크는 수집된 후보를 **줄일 수 있으나 지울 수 없다**: `topDocs`가 비면 `rerankInput`/`fused`로 복원하는 선례가 이미 있다(`rerankInput.stream().limit(keepN)`). 인용 게이트/리랭커 전량 탈락 = 기아가 아니라 "unverified context" 강등 주입 대상 (§R2). | `ChatWorkflow.java` L2647-2655, L2763-2794 |

## S4. Jev 응용 경계 seam

| # | 계약 | 근거(사실) |
|---|---|---|
| J1 | `JevGatewayClient`는 Vercel AI Gateway `POST /v1/evaluate` 전송자 — "Evaluation only — never requests text generation". 본문 생성 호출 경로(`/chat/completions` 등)가 클라이언트에 존재하면 위반. | `JevGatewayClient.java` L17-22 |
| J2 | Jev는 **후보 선별 보조**에 한정: `JevRetrievalGateHandler`는 데코레이터이며 비활성/스코프 부재/`CURRENT` 점유 시 무조건 `delegate.handle(query,accumulator)` 패스스루 — 후보 수집 자체를 막지 않는다. | `JevRetrievalGateHandler.java` L35-50, L63 |
| J3 | 힌트는 **좁히기만** 한다: `hints.put("allowWeb",false)`/`useWebSearch=false`/`retrieval.web.enabled=false`는 허용, 어떤 permission boolean도 `true`로 승격 불가("Permission booleans never become true"). `depth` 조정은 기존 budget/provider 힌트를 보존. | `JevRetrievalGateHandler.java` L155-174 |
| J4 | 실패는 **fail-soft**: wire 실패(`timeout`,`network`,`budget_skip`,`jev_not_configured`,`auth_*`,`plan_gate`,`rate_limited`,`http_*`,`error` 등 `safeReason` 허용 목록)는 baseline 유지로 수렴. observation은 `httpStatus==200 && "ok"`일 때만 유효 — 그 외엔 baseline 결정이 그대로 실행된다. | `JevGatewayClient.java` L102-141; `JevRetrievalGateHandler.java` L253-263 |
| J5 | 복잡도 분류 실패도 baseline 게이트로 위임(`classifierFailure → state.complexityLevel=null`), 상위 취소(`CancellationException`/`backend_timeout`)만 위로 던진다 — Jev 장애가 전체 답변을 보류시키는 경로는 없다. | `JevRetrievalGateHandler.java` L193-199 |
| J6 | 부모 focus/cue 요청은 Jev에 `main` 권한을 만들지 않는다(`JevDecisionScope.capture()!=null` → 패스스루) — seam 간 권한 상속 금지. | `JevRetrievalGateHandler.java` L48-49 |
| J7 | Jev 타임아웃은 `TimeBudget`/`budget.remainingMillis` 데드라인으로만 경계되고, 임의 하드 타임아웃(예: 고정 70 ms)을 새로 박지 않는다 — 시간 한도는 SSOT `public.request-budget.max-time-budget-ms` 계열. | `JevRetrievalGateHandler.java` L91, L103-107; `docs/volatile-knobs.md` |

## R. RAG 방출·기아·취소 잔류 계약 (갭필 대상 — Codex 소유)

| # | 계약 | 라이브 상태(사실) |
|---|---|---|
| R1 | **판정불능 ≠ 보류**: `verification.outcomeKnown()==false`(fail-soft)는 `releaseAllowed=false` HOLD가 아니라 본문 유지 + `UNVERIFIED`/경고 플래그 + `knowledgeWriteAllowed=false`로 방출되어야 한다(정책 `DEMO1-EVIDENCE-ZERO-RELEASE`: fail-soft는 본문 유지+메모리 금지). | 현재 `ChatWorkflow.applyFinalVerificationReleaseGate` L8208-8216은 `!outcomeKnown → HOLD + releaseAllowed=false` — 정책과 충돌, 갭필 대상. `applyUnavailableVerificationRelease`(L8150-8179)가 올바른 UNVERIFIED 방출 패턴의 선례. |
| R2 | **컨텍스트 기아 금지**: 수집된 웹/벡터 후보가 존재하는데 인용 게이트가 전량 탈락시켜도(`CITATION_GATE_BLOCKED`/`CONFIRMED_EMPTY`/`NO_CITABLE_LOCATOR`) 프롬프트 컨텍스트는 빈 상태가 되지 않는다 — 탈락 후보는 "미검증" 마킹으로 강등 주입된다. `web: 0, vector: 0` while 수집 성공 = 계약 위반. | 현재 `RagEvidenceAttributionService.promoteForPromptDetailed` L277-331: 게이트 실패 → promoted 비움 → `prompt.citableEvidenceRenderedCount=0`. topDocs 기아 복원 선례(ChatWorkflow L2647-2655)는 존재하나 프롬프트 주입 계층엔 없음. |
| R3 | **HELD 통체 치환 금지**: `RagControlProjectionRenderer`는 상태 테이블(`TABLE_MARKER`)을 **본문에 덧붙이되**, `plan.shouldStop()`일 때 본문 전체를 `HELD_NOTICE`로 치환하지 않는다 — 보류는 플래그/부록이지 본문 삭제가 아니다. | 현재 L30 `plan.shouldStop() ? HELD_NOTICE : semanticAnswer` — 본문 통체 치환 중, 갭필 대상(Codex `rag-nonmodel-release` 리스 영역과 동일 seam). |
| R4 | **Cancel ACK = 잔류 없음**: 정확 토큰 취소(`cancelExact`) 이후 (a) late SSE 프레임은 터미널 이벤트 1회 + `tryEmitComplete`로 닫히고 비터미널 프레임은 억제, (b) `cancelledBeforePersist` 시 세션/메모리 기록 없음, (c) `isCancelled` replay가 모바일 갱신을 즉시 반영, (d) `throwIfCancelled` 체크포인트가 생성 중단. | 현재 기구 대부분 존재(`ChatCancellationCommandHandler`, `ChatRunRegistry.finishCancellation` L1197-1234, `ChatApiController` L1133/L2535+/L2711+, `ChatRunExecutionContext` L71-96). 잔존 갭: ACK와 `finishCancellation` 사이 비터미널 프레임 윈도우, 모바일 `sessionListRefresh` 전파 — Codex `codex-timeout-split` 저널과 교차 확인 필요. |

## 교차 오염 금지 매트릭스

| 항목 | Display seam | 메인 /chat·RAG seam |
|---|---|---|
| `focusAnswerLengthChars` | `ChatConversationContext`→`PromptContext` 서버 내부만 | `ChatRequestDto`·`ChatDefaultsProperties`·`application*.yml`(meta-display 제외)·`chat.js`에 0건 |
| `useRag=false`/`webTopK=0`(Quick) | `NovaFocusAnswerService` 어댑터 안 | `ChatWorkflow` 기본값/Jev 힌트 외 경로에 0건 |
| PCM 오디오 | `/api/assist/display/audio/*` + producer-bound만 | `/api/assist/display/lens*`, `/api/chat*`에 0건 |
| Jev evaluate | `JevRetrievalGateHandler` 힌트 병합만 | 본문 생성/보류 결정에 0건 |
| 전사 텍스트 | `Transcript` record(≤12t/≤2000tok) | 메모리/프롬프트 외 직접 주입 0건 |

## 미결·정책 노트

1. `SurfaceOrigin` enum: tri-system spec의 목표 상태 — 미착륙 시 length-only 게이트가 유지되며 `probe_tri_system_isolation.py`가 `enum-not-landed` 경고로 추적.
2. `ExecutionTarget.LOCAL_ONLY`(NovaFocusSettings)와 I3 자동 폴백의 로컬 lane 진입 — tri-system spec §5 미결.
3. R1의 `insufficient` 판정 경계: outcomeKnown=true+insufficient는 "판정된 부족"으로 별도 표시 유지 가능 — zero-evidence 정책과의 우선순위는 FOR_CODEX 청사진에서 Codex가 확정.
4. ASR 백엔드 운영 기본값(로컬 whisper vs 클라우드 우선)은 지시서 ASK_ONCE 미결 항목 — 기본 브라우저/클라우드 우선.
