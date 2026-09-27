# 영상 분석 보고서: /chat 3턴 세션 — 후속 발화에서 메모리/컨텍스트 부재 → 로컬 안전 응답

- 영상: `F:\CAM\2026-09-25 09-31-49.mkv` (10분 15초, 1280×720, 30fps, h264+aac)
- 분석 일시: 2026-09-25
- 작업 ID: `video-analysis-20260925-093149-2ceaf71c`
- 범위: `/chat` UI, ChatWorkflow 메모리/컨텍스트 조립, agent.db-context·Trace Memory 레인, 로컬 LLM 실패→폴백 경로

## 0. 세션·기록 대응표 (사실)

| 영상 표기 | 서버 실체 |
|---|---|
| 배지 `session:56` | trace `sessionId = hash:7688b6ef5255` (`var/debug/chat-session-traces/20260925/s-7688b6ef5255.json`, 3건) |
| Debug FX `session: hash:81b1e0485f14` | `logs/debug-events.ndjson`의 `sid=hash:81b1e0485f14` (브라우저 세션 해시) |
| 배지 `fallback: evidence:224` | record 1 `effectiveModel=smtek/Qwen3.8-27B:Q3_K_XL:fallback:evidence` |
| 배지 `recent history:229` | record 2 `effectiveModel=history:fallback:recent` |
| 배지 `fallback: local:234` | record 3 `effectiveModel=...:fallback:local-lite`, `harmonyDecision=blank_guard` |
| export 번들 | `var/debug/chat-session-traces/export/7688b6ef5255/` (`records.json` + `manifest.json`) |

영상 시각(09:31–09:42 KST)과 서버 타임스탬프(00:32–00:42 UTC)가 정확히 일치한다.

## 1. 영상에서 관측된 주요 증상

### 1.1 턴 1 — "하이젠베르크 불확실성 원리가 뭐냐?" (09:32:2x–09:34:33)

- 전송 후 약 60초 `Response still pending (client-wait:60010ms)` 표시, 이후 총 ~127초 만에 답변 도착.
- 서버 로그(사실): Naver Search API 4회 모두 200(00:32:26–29), `RagEvidenceAttributionService.promoteForPrompt` promoted=2·게이트 통과, `PromptBuilder.build` webCount=2·ctxLen=947·**memoryPresent=true**.
- 그러나 LLM 호출이 ~120초 정지 후 `stage_boundary llm failureClass=catch` + `local_llm_operator_action failureClass=model_unavailable triggerReason=llm_unavailable_after_retries` → `fallback:evidence`(추출형 근거 답변)로 종료.
- 답변 본문 "### 근거(검색 결과)" 안에 리터럴 `**Loading...**` 불릿이 그대로 노출됨(아티팩트).
- Debug FX 상시 라인: `Trace Memory unavailable cfvm:unavailable pattern:unavailable | Agent DB DISABLED agent_db_context_disabled`.

### 1.2 턴 2 — 동일 질문 재전송 (09:39:24)

- 배지 `recent history:229`. 서버 레코드: `effectiveModel=history:fallback:recent`, `chat.historyFallback.shortCircuit=true`(`shortCircuitBefore=disambiguation`).
- **모델 미호출** — 이전 답변을 recent-history 폴백으로 재사용. 파이프라인 칩은 Retrieve warn·Context done·Model warn·GPU/API Failover warn로 표시됐으나 실제 LLM 실행 없음.
- 답변 상단에 `⚠ 위 내용은 비공식 자료(위키/커뮤니티) 기반일 수 있습니다…` 경고.

### 1.3 턴 3(세 번째 세션) — "와,그걸 어떡해.." (09:39:53–09:41:54) ← 핵심

사용자 발화는 직전 답변을 가리키는 맥락 의존 후속 발화(10자). 결과는 **대화 맥락이 전혀 반영되지 않은 로컬 안전 응답**:

> "기본 모델 응답이 지금 안정적으로 생성되지 않아 로컬 안전 응답으로 먼저 보여드립니다. 현재 사용할 근거가 없이 확정 답변은 제한됩니다. 원하는 출력 형식이나 추가 설명이 있으면 이어서 적어 주세요."

서버·UI 증거(모두 사실):

- `promptContextComposer`: `activated=false, reason="empty_input", pressureScore=0.0` — 컨텍스트 조립 입력이 비어 조립 미발동.
- `promoteForPrompt`: `candidateCount=0, promoted=0` — 검색/근거 0건. UI 파이프라인 칩: `Anchor skipped Retrieve skipped DPP/Rerank skipped Context skipped … Supabase skipped`.
- `PromptBuilder.build`: `webCount=0, citableEvidenceCount=0, memoryPresent=true, ctxLen=1453, queryLen=10`.
- ~121초 대기 후 `NightmareBreaker OPEN`(chat:draft 키, 15초 윈도우) → `stage_boundary llm failureClass=catch reasonCode=reactiveexception` → `llm_unavailable_after_retries` → `blank_guard` → `fallback:local-lite`.
- Debug FX 3턴 라인(영상 말미 확대 판독): `harmony degraded:true blank_guard blank_answer_guarded | MLA FINAL llm_catch reactiveexception | Trace Memory unavailable stage:load cfvm:false pattern:unavailable | Agent DB PROBE UNAVAILABLE agent_db_context_controller_unavailable | native route:native promptChars:2222 maxTokens:256 | Local LLM model unavailable next:inspect_model_route or start_local_llm`.
- 같은 시간 작업 관리자: GPU 0 = RTX 3090, 전용 메모리 21.5/24.0 GB 점유, 사용률 급등 패턴 — 모델 로드/서빙 실패와 정합.

### 1.4 부가 관측

- 사이드바 "최근 대화"에 동일 제목 `하이젠베르크 불확실성 원리가 뭐냐?` 2건 + `내 아이디어의 장점과 단점을 명쾌하게 검/* ... */` 1건. 제목의 `/* ... */`는 코드 주석식 말줄임이 UI로 새어 나온 아티팩트(§2.5).
- 녹화 중 카카오톡 창·`logs=...\var\ra-lau...` 로그 tail cmd 창·작업 관리자(GPU 패널)가 번갈아 전면에 표시됨. 애플리케이션 동작과 무관.

## 2. 코드 근거 및 원인 분석

### 2.1 [핵심] 후속 발화의 폴백 응답은 설계상 대화 맥락을 받지 못한다 (사실)

- `NoEvidenceChatFallback.orEvidenceFallback(...)` (`main/java/com/example/lms/service/NoEvidenceChatFallback.java:120–160`) 시그니처에는 `query, modelUsed, ragUsed, topDocs, vectorDocs, localDocs, evidenceFallback, evidenceMetadata`만 있다 — **history/memory 인자가 없다**.
- 근거 0건이면 `:145–148`이 `compose(query)`로 직행. `compose()`(`:41–69`)는 `LOCAL_FALLBACK_NOTICE` + 고정 문구만 반환한다.
- 호출부(`ChatWorkflow.java:3448, 3482, 3500, 3655`)는 `composeEvidenceFallback(..., historyStr, ...)`으로 **히스토리를 포함한 폴백 문자열을 만들어 넘기지만**, `orEvidenceFallback`의 무근거 분기는 그 인자(`evidenceFallback`)를 버리고 `compose(query)`만 쓴다. 즉 "직전 대화를 반영한 폴백" 경로가 만들어져 있어도 무근거 턴에서는 도달하지 않는다.
- 결과: 모델 실패 + 근거 0 + 후속 발화 조합에서 사용자는 "기억을 못 읽는" 것과 동일한 출력을 받는다.

### 2.2 [핵심] 컨텍스트 조립 입력이 비면 `Context skipped`로 닫힌다 (사실)

- `ChatStreamSignalBuilder.java:347–351`: `compose`("Context") 블록은 `finalContextCount>0`이면 done, 완료 시 0이면 `skipped`. 3턴은 `promptWebDocs`/`promptVectorDocs` 모두 0 → `empty_input` → Context skipped.
- 메모리 텍스트(`memoryCtx`, `memoryHandler.loadForSession`, `ChatWorkflow.java:2790–2804`)와 `historyStr`/`lastAnswer`(`:2809–2819`)는 별도 경로로 프롬프트에 들어가지만 `finalContextCount` 집계에는 잡히지 않는다. 즉 UI의 "Context skipped"는 "근거 문서 0"을 뜻하고, 메모리/히스토리 주입 여부는 이 칩으로 판별할 수 없다(관측성 갭 — §2.3의 실제 메모리 레인과 별개).
- 재현 지점: `ChatWorkflow.java:2860–2892`의 `promptContextCompressor.composeForPrompt` — 입력이 비면 `activated=false, reason=empty_input`.

### 2.3 [핵심] 심층 메모리 레인 2개가 실제로 죽어 있다 (사실 + 원인은 추정)

- `agent.db-context`: `application-meta-display.yml:205–207`에서 `enabled: true`(로컬/디스플레이 프로파일 opt-in). 서버 trace도 3건 모두 `agentDbContextEnabled=true`.
- 그런데 런타임 미러(`ChatWorkflow.mirrorAgentDbContextAvailability` → `mirrorAgentDbContextAvailabilityForAgentDebug`, `ChatWorkflow.java:12414–12457`)는 `AgentPipelineHealthController` 빈이 없으면 `PROBE_UNAVAILABLE / agent_db_context_controller_unavailable`, 플래그 off면 `DISABLED / agent_db_context_disabled`를 찍는다. **같은 배포에서 턴1–2는 DISABLED, 턴3은 PROBE_UNAVAILABLE로 표기가 엇갈렸다** — 두 경로(heartbeat 표기 vs per-run 미러)가 서로 다른 신호를 보는 불일치가 있다. 어느 쪽이든 `AgentDbContextPromptInjector`(`agent.dbContext.prompt.injected`)는 동작하지 않았다.
- `Trace Memory`: `AgentVisibleDebugEvidenceBuilder.java:466–505` — `traceMemory.*` 키가 현재 요청 TraceStore에 없으면 `virtualCheckpoint.latestStage`가 기본값 `"load"`로 떨어져 `Trace Memory unavailable stage:load`로 표시된다. 직전 2턴이 정상 종료했는데도 3턴에서 로드할 체크포인트가 없었다는 뜻 — 쓰기 경로(`traceMemory.checkpoint.*` 생산자)가 실행되지 않았거나, 읽기 경로(heartbeat/probe의 `TraceSnapshotStore`)가 요청 범위 밖을 못 보는지 추적이 필요하다(추정).
- 참고: `ChatSessionTraceRecorder`는 3턴 모두 `outcome=completed, errorClass=none`으로 기록한다 — **폴백도 "정상 완료"로 잡히므로** 실패 탐지가 outcome만으로는 불가능하다.

### 2.4 로컬 LLM 불능 (사실 + 정확한 원인은 미확정)

- 요청 모델 `smtek/Qwen3.8-27B:Q3_K_XL`은 allowlist(`configs/api-routing.yaml:27,40,44,45`)와 `ollama ls`에 존재(14 GB). 분석 시점 `ollama ps`는 비어 있음.
- 3턴 모두 `llm_unavailable_after_retries`/`reactiveexception`/`model_unavailable`로 종료. 영상 당시 3090 전용 메모리 21.5/24 GB — VRAM 부족으로 14 GB 모델 적재 실패, 또는 Ollama 엔드포인트 응답 불능이 유력(추정). `llm.error.*`·`llm.gateway.failure.*`·`llm.ollamaNative.*` 값은 per-request TraceStore에 있으며 이번 분석은 값을 열람하지 않았다 — Codex가 `debug-events`/trace export로 확정해야 한다.
- 2026-09-24 영상 리포트(`docs/video-analysis-report-20260924-143134.md`)의 "cold-load vs 상위 TimeBudget" 이슈와 같은 계열이다.

### 2.5 아티팩트 2건 (사실)

- `**Loading...**`: `EvidenceAnswerComposer.java:135–141`이 `- **{title}**: {snippet}`로 렌더. 검색 결과 문서의 title이 "Loading..." 같은 플레이스홀더면 그대로 노출된다(추정: Naver 결과 title 필드).
- `/* ... */`: `ChatHistoryServiceImpl.java:232, 1192` — `safe.substring(0,20) + "/* ... */"`. 세션 제목 20자 초과 시 코드 주석 말줄임이 그대로 제목이 된다. UI 말줄임 문자로 교체 대상.

## 3. 우선순위 제안

| 우선순위 | 항목 | 기대 효과 |
|---|---|---|
| P1 | 모델 불능 시 폴백이 최소 대화 맥락(직전 Q/A·세션 메모리)을 반영 | "기억 못 읽음" 체감 제거 |
| P1 | 무근거 후속 발화에도 historyStr/lastAnswer를 프롬프트·폴백에 유지 | 맥락 의존 발화 정상화 |
| P2 | agent.db-context 표기 일원화 + 주입 경로 실제 동작 확인 | 메모리 레인 사망 여부 확정 |
| P2 | Trace Memory `stage:load` unavailable 원인 규명(쓰기/읽기 경로) | 체크포인트 메모리 복구 |
| P3 | Context 칩에 memory/history 주입 카운트 분리 표시 | "컨텍스트 없음" 오판 방지 |
| P3 | `Loading...` 플레이스홀더 title 필터 / `/* ... */` 제목 말줄임 교체 | 아티팩트 제거 |
| P3 | 로컬 모델 불능 시 60초 client-wait → 빠른 실패/사유 표시 | UX 대기 절감 (0924 리포트 P1 연속) |

## 4. 검증 제안

- `chat_session_debug_export.py show 7688b6ef5255`로 3턴 레코드 재확인, `llm.error.*`/`llm.gateway.*` 값 확정.
- `ChatWorkflowAgentVisibleDebugEvidenceTest` 계열에 "모델 실패 + 근거0 + 세션 히스토리 존재" 시나리오 회귀 테스트 추가.
- 무근거 분기가 historyStr을 버리는지 여부는 `NoEvidenceChatFallback` 단위 테스트로 재현 가능.

## 5. 다음 단계

- Codex 소스 수정 지시서: `docs/codex/SESSION56_MEMORY_CONTEXT_FIX_DIRECTIVE_2026-09-25.md`
- 본 문서는 분석 산출물이며 운영 소스는 변경하지 않았다.
