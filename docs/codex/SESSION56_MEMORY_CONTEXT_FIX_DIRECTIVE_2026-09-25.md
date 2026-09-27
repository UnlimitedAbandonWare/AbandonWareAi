# Codex 실행 지시서 — Session56 3턴 메모리/컨텍스트 조립 실패 수정

- Date: 2026-09-25
- Session: Codex 소스수정 (live demo-1)
- Mode: 진단 확정 + 최소 패치 + 회귀 테스트
- 전제: 영상 `F:\CAM\2026-09-25 09-31-49.mkv`(10분15초) 분석 결과 — 상세: `docs/video-analysis-report-20260925-093149.md`
- 대상 세션: `/chat` UI `session:56` ↔ 서버 trace `sessionId hash:7688b6ef5255`
- 근거 번들(전달용): `var/debug/chat-session-traces/export/7688b6ef5255/` (`records.json` 3건 + `manifest.json`) + `logs/debug-events.ndjson`

## Goal / Spec / Execution

- Goal: 후속 발화(맥락 의존, 근거 0, 모델 불능)에서도 (1) 세션 기억이 실제로 읽히고 (2) 그 사실이 컨텍스트 조립·프롬프트·폴백 어느 단계에서든 끊기면 끊긴 지점이 진단 가능하게 한다.
- Spec: 기존 심 재사용(`memoryCtx`/`historyStr`/`lastAnswer`, `PromptContextCompressor`, `AgentDbContextPromptInjector`, `NoEvidenceChatFallback`, `ChatStreamSignalBuilder`). 새 메모리/검색 서브시스템·프로바이더 라우팅 재설계 금지.
- Execution: P1 → P2 → P3. 각 항목 "증거 → 재현 → 최소 수정 → 회귀 테스트 → 완료 조건" 순서로. 발견 못 한 원인을 추정으로 확정 표기 금지.

## 0. 확정 사실 vs 미확정 (조사 전 바운더리)

### 사실 (trace/log/video로 검증됨)

| # | 사실 | 근거 |
|---|---|---|
| F1 | 턴3 발화 "와,그걸 어떡해.."(10자) → `promptContextComposer activated=false reason=empty_input` → Context skipped | debug-events runId `384b31b42718` / record 3 |
| F2 | 턴3 `PromptBuilder.build webCount=0 memoryPresent=true ctxLen=1453` — 메모리 객체는 비어있지 않았으나 근거 문서 0 | record 3 |
| F3 | LLM ~121초 지연 → NightmareBreaker OPEN → `reactiveexception` → `blank_guard` → `effectiveModel=...:fallback:local-lite` | record 3 (`llm.call.*`, `llm.gateway.*`) |
| F4 | 무근거 분기는 history를 버린다: `orEvidenceFallback(...)`은 history 인자 없이 `compose(query)` 직행; 호출부가 만든 `composeEvidenceFallback(...,historyStr,...)` 문자열은 무근거 분기에서 미사용 | `NoEvidenceChatFallback.java:41–69,120–160`, `ChatWorkflow.java:3448–3504,3655` |
| F5 | 턴2는 모델 미호출 `history:fallback:recent` short-circuit(before=disambiguation)으로 재생 | record 2 |
| F6 | 같은 세션에서 Agent DB 표기 불일치: 턴1–2 `DISABLED agent_db_context_disabled`, 턴3 `PROBE UNAVAILABLE agent_db_context_controller_unavailable`, trace 3건 모두 `agentDbContextEnabled=true` | record 1–3 + 영상 Debug FX |
| F7 | 턴3 `Trace Memory unavailable stage:load` — 현 요청 TraceStore에 `traceMemory.*` 키 없음 | `AgentVisibleDebugEvidenceBuilder.java:466–505` |
| F8 | `agent.db-context.enabled=true`는 `application-meta-display.yml:205–207` opt-in; `AgentDbContextAutoConfiguration`/`AgentDbContextController`는 `@ConditionalOnProperty("agent.db-context.enabled")` | 해당 파일 |
| F9 | `ChatSessionTraceRecorder`는 폴백도 `outcome=completed, errorClass=none`으로 기록 | record 1–3 |
| F10 | 아티팩트: 답변 본문 `**Loading...**` 불릿(`EvidenceAnswerComposer.java:135–141`), 사이드바 제목 `/* ... */`(`ChatHistoryServiceImpl.java:232,1192`) | 영상 + 소스 |

### 미확정 (Codex가 값을 열어 확정할 것)

- H1: `reactiveexception`의 정확한 원인 — `llm.error.*`/`llm.gateway.failure.*`/`llm.ollamaNative.*`/`llm.localEndpoint.*` 값 미열람. VRAM(영상 당시 3090 전용 21.5/24 GB) vs Ollama 엔드포인트 불능 구분 필요.
- H2: `memoryCtx`가 실제로 무엇을 담고 있었는지(`memory.session.*`/`memory.summary.*` 값 미열람) — "메모리 읽기 시도→로드→프롬프트 주입" 각 단계 성공 여부 불명.
- H3: `AgentDbContextPromptInjector`가 3턴에서 호출됐는지 — `agent.dbContext.agentVisible.*` 값 미열람.
- H4: Trace Memory `stage:load`가 쓰기 경로 미실행인지 읽기 경로(요청 범위 외 TraceSnapshotStore) 문제인지.

## 1. 수정 항목

### P1-A. 무근거 후속 발화의 폴백에 최소 대화 맥락 연결 (F1,F4)

- 증거: `NoEvidenceChatFallback.java:120–160` — 무근거 분기 `compose(query)`가 `evidenceFallback`(historyStr 포함 문자열)을 폐기.
- 재현: 히스토리 존재 세션 + 근거 0 + 모델 실패 조합에서 답변이 고정문구만 출력.
- 최소 수정(택1, 더 작은 쪽):
  1. `compose(query)` 경로에서 히스토리가 존재하면 고정문구 뒤에 "직전 대화 맥락(요약 1–2문)" 1줄 첨부 — 이미 조립된 `historyStr`/`lastAnswer` 재사용, 신규 조립 금지.
  2. 맥락 의존 발화 감지(대명사/지시어: "그걸","방금","아까" 등) 시 무근거 분기 전에 recent-history 계열 폴백으로 라우팅 — `composeRecentHistoryFallback` 기존 경로 확장.
- 주의: 폴백이 "기억을 읽었다"고 거짓 표기하면 안 됨. 실제 로드된 historyStr이 없으면 맥락 언급 금지(F4 분기 조건과 동일 신호 사용).
- 회귀 테스트: `NoEvidenceChatFallback` 단위 테스트 — (a) history 존재+근거0 → 답변에 맥락 문장 포함, (b) history 없음 → 기존 고정문구 유지, (c) EPHEMERAL 모드 → 맥락 미포함.
- 완료 조건: 턴3 재현 시나리오에서 폴백 답변이 직전 답변을 가리키는 문장을 1개 이상 포함하거나, 포함 불가 사유가 trace에 기록.

### P1-B. 컨텍스트 조립 파이프라인에 메모리/히스토리 주입 관측성 (F1,F2,F9)

- 증거: `finalContextCount`는 web/vector 문서만 집계 → `ChatStreamSignalBuilder.java:347–351`의 "Context" 칩이 메모리 주입과 무관하게 skipped. `memoryPresent=true`는 PromptBuilder 단에서만 보임.
- 최소 수정: 조립 단계에서 `memoryCtx`/`historyStr` 주입 여부·길이를 별도 카운터로 기록(기존 `prompt.context.composer` debug 이벤트에 필드 추가), Context 칩 판정에 "memory/history 주입됨"을 반영하거나 칩 라벨을 `Evidence`로 한정.
- 회귀 테스트: 근거0+메모리있음 턴에서 칩이 "skipped"로 오표기되지 않음.
- 완료 조건: 영상 턴3 재현 시 UI 칩이 "근거 0"과 "메모리 주입 여부"를 구분해 표시.

### P2-A. agent.db-context 표기 불일치 해소 (F6,F8)

- 증거: `mirrorAgentDbContextAvailability`(`ChatWorkflow.java:12414–12457`) 두 경로가 서로 다른 신호를 봄. 플래그 on + `AgentPipelineHealthController` 빈 없음 → PROBE_UNAVAILABLE.
- 조사: 실제 프로파일에서 해당 빈이 생성되는지 확인 — `AgentDbContextAutoConfiguration`의 조건과 활성 프로파일 대조.
- 최소 수정: enabled=true인데 health 빈이 없으면 "설정됨·프로브 없음"을 단일 라벨로 일원화하고, `AgentDbContextPromptInjector` 주입 시도 결과(`injected/skipped:reason`)를 per-request trace에 기록. 주입기가 실제로 dead면 원인(빈 미등록 vs provider 결과 없음)을 trace로 구분 가능하게.
- 완료 조건: 같은 세션 내 턴별 표기가 일관되고, "enabled"와 "실제 주입됨"이 별도 필드로 기록.

### P2-B. Trace Memory `stage:load` unavailable 원인 확정 (F7)

- 조사: `traceMemory.checkpoint.*` 쓰기 생산자 경로 vs `AgentVisibleDebugEvidenceBuilder` 읽기 경로(요청 TraceStore 범위) 대조. 정상 종료한 직전 턴의 체크포인트가 다음 턴에서 안 보이는 이유를 `TraceStore` 스코프 기준으로 확정.
- 최소 수정: 읽기 경로가 요청 범위에 한정된 설계가 의도라면 라벨을 그 의도대로 정정(예: "이번 요청 체크포인트 없음"), 비의도라면 `TraceSnapshotStore` 기반 조회로 연결.
- 완료 조건: 2연속 정상 턴에서 2번째 턴 Debug FX가 `stage:load` unavailable이 아닌 실제 최신 stage를 표시.

### P3. 아티팩트 (F10)

- `EvidenceAnswerComposer`: title이 `Loading...`류 플레이스홀더/공백/URL-only면 title 대신 도메인 또는 `[근거 n]`로 대체.
- `ChatHistoryServiceImpl.java:232,1192`: `/* ... */` → `…`(또는 UI 기존 말줄임 관례).
- 회귀 테스트: 플레이스홀더 title 문서 → 렌더에 `Loading...` 미포함; 20자 초과 제목 → `/*` 미포함.

## 2. 메모리 모드 계약 유지 (검증 대상, 변경 아님)

- `MemoryMode.java`: `FULL` 읽기+쓰기, `HYBRID` 읽기 전용, `EPHEMERAL` 메모리 비활성 — `isReadEnabled()`/`isWriteEnabled()` 기존 의미 유지.
- `ChatRequestSettingsMerger`가 `memoryMode`/`sessionId`/`history`를 보존하는 것은 확인됨 — P1 수정 시 이 경로를 우회하는 신규 전달 경로를 만들지 말 것.
- 필수 회귀 매트릭스(기존 `MemoryMode` 테스트와 합쳐): EPHEMERAL에서 맥락 미주입 / HYBRID에서 읽기됨+쓰기안됨 / FULL에서 읽기+쓰기 / 세션ID 무효·결측 / provider 빈 부재 / compressor 실패(fail-soft 원본 반환 확인, `ChatWorkflow.java` `compressMemoryForPrompt` 경로) / 모델 실패→폴백에서 동일 컨텍스트 스냅샷 소비.

## 3. 금지 경계

- 신규 메모리·검색 서브시스템, 병렬 컨텍스트 스택 생성 금지. 기존 심(`PromptContextCompressor`, `AgentDbContext*`, `NoEvidenceChatFallback`) 안에서 최소 diff.
- GPU/프로바이더 라우팅 재설계, allowlist(`configs/api-routing.yaml`) 변경 금지 — H1 원인이 VRAM이어도 이번 지시 범위는 메모리/컨텍스트다.
- `.secrets/` 열람 금지, 키·프롬프트·응답 본문 로그 기록 금지 — 진단 필드는 카운트/해시/사유 코드만.
- `openssl` 관련 키명·값·형식 변경 금지. Git push/커밋 금지. 무관 코드 삭제·포맷 정리 금지.
- "agentDbContextEnabled=true"·HTTP 200·`outcome=completed`만으로 "메모리 정상" 판정 금지 — enabled/attempted/loaded/assembled/injected/delivered를 분리해서 보고할 것.

## 4. 보고 형식

결과 보고 시 다음을 분리:

1. **확정된 원인**: H1–H4 각각 trace 값 근거로 사실/기각 표시.
2. **변경 파일**: 경로 + diff 요약 + 변경 전후 sha256.
3. **재현 결과**: 턴3 시나리오(히스토리 존재+근거0+모델 불능) 수정 전/후 답변과 Debug FX 라인.
4. **테스트**: 실행 명령 + 통과/실패 카운트 + 신규 테스트명.
5. **미해결**: 남은 원인/리스크 — 없으면 "없음" 명시.
