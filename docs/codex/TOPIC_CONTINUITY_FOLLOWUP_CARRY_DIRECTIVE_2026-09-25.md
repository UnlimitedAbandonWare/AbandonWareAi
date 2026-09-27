# Codex 실행 지시서 — 세션 토픽 연속성(follow-up topic carry) 수정

- Date: 2026-09-25
- Session: Devin Desktop 소스 탐침 → Codex 소스수정 지시서 (live demo-1)
- Mode: 진단 확정(사실/미확정 분리) + 최소 패치 + 회귀 테스트
- 전제: 영상 `F:\CAM\2026-09-25 11-47-07.mkv`(~156MB, 브리프 제공 3턴 타임라인; 미리보기 `C:\Users\nninn\Videos\grok-bot-uploads\2026-09-25_11-47-07_preview.mp4`) — 본 지시서 작성자는 영상을 직접 재생하지 않았고, 브리프 타임라인과 서버 trace 3건의 일치로 행동 근거를 확정함
- 대상 세션: `/chat` UI `session:57` 계열 ↔ 서버 trace `sessionId hash:c837649cce43`
- 근거: `var/debug/chat-session-traces/20260925/s-c837649cce43.json` (JSONL 3건)
  - T0 `8c3c24b3d286` 02:50:09Z `harmonyDecision=fallback_evidence`, `effectiveModel=smtek/Qwen3.8-27B:Q3_K_XL:fallback:evidence`
  - T1 `51cf50c48c7c` 02:51:47Z `harmonyDecision=smooth_chat`, `effectiveModel=smtek/Qwen3.8-27B:Q3_K_XL` (fallback 접미사 없음)
  - T2 `b609e5ad1ead` 02:54:27Z `harmonyDecision=fallback_evidence`, `effectiveModel=...:fallback:evidence`
  - 3건 공통: `surface=chat`, `ragEnabled=true`, `agentDbContextEnabled=true`, `outcome=completed`
- 인접(혼동 금지): `docs/codex/SESSION56_MEMORY_CONTEXT_FIX_DIRECTIVE_2026-09-25.md` — no-evidence compose의 history drop 문제. 이번 지시는 retrieve **seed/앵커**의 토픽 carry로 별개 심.

## Goal / Spec / Execution

- Goal: follow-up/meta 발화("오, 기억 세션 저장기능 잘 작동하네, 그럼 이제 어떡해 해야하냐?")에서도 직전 세션 토픽 엔티티(아이리 칸나)가 retrieve seed에 실려, 무관 웹 Evidence(`korean.go.kr`/`dongseo.ac.kr`/`humanrights.go.kr` 류 공식 PDF)가 본문을 점유하지 않는다.
- Spec: 기존 심 재사용 — `recentHistory`, `SubjectResolver`, `DisambiguationResult.targetObject`, `RoutingPlanService.planAnchored`, `SmartQueryPlanner.sanitizeAnchored`. 새 GraphRAG 엔진/Neo4j 스키마/신규 검색 서브시스템/대량 문서 금지.
- Execution: H1–H3 확정 → P1 한 줄기 → 회귀 테스트. 발견 못 한 원인을 추정으로 확정 표기 금지.

## 0. 확정 사실 vs 미확정 (조사 전 바운더리)

### 사실 (이 체크아웃 소스·trace로 검증됨)

| # | 사실 | 근거 |
|---|---|---|
| F1 | T0/T2는 `fallback_evidence`+`:fallback:evidence`, T1은 `smooth_chat` — 브리프 타임라인과 일치 | `s-c837649cce43.json` 3건 |
| F2 | T1("내가 방금 뭐라고 했냐") 성공 경로는 결정론적 history 회수: `earlyRecentHistoryFallback` short-circuit이 disambiguation **이전**에 발화 | `ChatWorkflow.java:1595–1602`, `composeRecentHistoryFallback`/`isRecentHistoryQuestion` `ChatWorkflow.java:10834–10900` |
| F3 | `recentHistory`는 retrieve 단계 진입 전 이미 존재 — `conversationContext.interpretationHistory()`("User:/Assistant:" 포맷, recent≤2+relevant≤2턴) 또는 `chatHistoryService.getFormattedRecentHistory(sessionIdLong,5)` | `ChatWorkflow.java:1591–1593`, `ChatConversationContext.java:24–30` |
| F4 | `disambiguationService.clarify(userQuery, recentHistory)`는 history를 LLM 프롬프트에 포함(`buildUniversal` "[Conversation history, oldest→latest]"). 단 promotion은 `dr.isConfident() && rewritten non-blank` 게이트 — 미충족 시 `finalQuery=userQuery` | `ChatWorkflow.java:1727–1736`, `QueryDisambiguationService.java:114,306`, `DisambiguationPromptBuilder.java:31–80` |
| F5 | `subjectResolver.analyze(finalQuery, recentHistory, dr)` — `history` 인자는 JavaDoc상 "현재 버전에서는 주로 로깅/확장용", **본문에서 미사용**. `normalized`=`dr.rewrittenQuery`\|`originalQuery`, `targetObject`=`dr.targetObject`만 | `ChatWorkflow.java:1739`, `SubjectResolver.java:122–151` |
| F6 | 웹 플랜 경로: `routingPlanService.plan(finalQuery, null, maxBranches)` → `SmartQueryPlanner.plan(input, draft, requested)` — 어느 단계에도 history 미전달 | `ChatWorkflow.java:1957`, `RoutingPlanService.java:50–83` |
| F7 | `SmartQueryPlanner.plan`은 `subjectResolver.resolve(cleaned, domain)`로 **현재 발화에서만** subject 추출 후 `sanitizeAnchored(cand, cap, jaccard, subject, null)`로 쿼리 앵커 삽입 — 앵커 주입 기구는 존재하나 follow-up에서 subject=null 또는 메타어 오탐 | `SmartQueryPlanner.java:186–187,343–351` |
| F8 | `planAnchored` 기구 존재: `RoutingPlanService.planAnchored(userPrompt, subjectPrimary, subjectAlias, draft, max)` → `SmartQueryPlanner.planAnchored` — 현재 유일 호출자는 `PairingGuardHandler:42`, 채팅 경로 미배선 | `RoutingPlanService.java:88–127`, `SmartQueryPlanner.java:439,473`, `PairingGuardHandler.java:42` |
| F9 | `GraphRagChunkingService.ingestConversationTurn(sessionId, userText, assistantText)` 존재 — 호출자는 `BrainStateAdminController`(`/api/admin/vector/brain/ingest`)뿐, 채팅 턴 자동 ingest 없음 | `GraphRagChunkingService.java:131–140`, `BrainStateAdminController.java:46–63` |
| F10 | `QueryTimeAnchorMap`은 `kg.anchor-map.enabled`/`KG_ANCHOR_MAP_ENABLED` 기본 **false**, 유일 소비자 `BrainStateService.queryAnchorSlice` — 채팅 retrieve 미연결. `KnowledgeGraphHandler`는 `generalGraphScope`+`memoryEnabled`+언급 엔티티 필요 → "그럼 이제 어떡해"는 엔티티 0이라 KG 레인 비어있음 | `QueryTimeAnchorMap.java:28,45–108`, `application.yml:111–113,580–582`, `BrainStateService.java:317,464–483`, `KnowledgeGraphHandler.java:79–108` |
| F11 | Harmony `fallback_evidence`는 하류 판정: `answerMode`/`answer.mode`에 "fallback" 포함 시 결정(`ChatHarmonyTracePostprocessor:237–238`); `exactHistoryFallback`이면 `smooth_chat`(:234–236). 배너는 원인이 아니라 증상 — UI/라벨 패치는 무의미 | `ChatHarmonyTracePostprocessor.java:213–248` |
| F12 | `FocusMemoryService`는 opt-in owner-scoped 레인("never indexes a Focus conversation automatically") — 일반 채팅 토픽 carry 기구 아님 | `FocusMemoryService.java:22–48` |
| F13 | trace의 `anchor.pick.*`는 `AnchorNarrower`가 **현재 쿼리**에서 뽑는 앵커 — 세션 carry와 무관 | `AnchorNarrower.java:368–375` |

### 미확정 (Codex가 trace 값을 열어 확정할 것)

- H1: T2에서 `clarify`가 LLM까지 도달했는지, 아니면 스킵 게이트(`shouldSkipLlmForDefinitionalQuery` `QueryDisambiguationService.java:143`, diagnostic-smoke `:146`, aux-down/breaker-open `:228–268`, failure-pattern cooldown `:275–298`) 중 하나에서 `fallback(query)`로 빠졌는지 — `aux.disambiguation`/`disambig.cooldown`/`disambiguation.noiseEscape` trace 값 미열람.
- H2: LLM이 rewrite를 반환했어도 `dr.isConfident()` 미충족으로 `finalQuery=userQuery`였는지 — `dr.confidence`/`dr.rewrittenQuery` 값 미열람.
- H3: T2 계획 웹 쿼리 문자열에 엔티티가 실제로 없었는지 — `chatApi.web.prefetch.*`/`web.selectedTerms.*`/`selectedTerms.summary` 값 미열람(trace key는 존재 확인).
- H4: `subjectResolver.analyze`에 들어간 `recentHistory`가 T2 시점에 T0/T1 턴을 실제로 담고 있었는지(`conversationContext.present()` 여부와 `recent` 크기) — 영상상 회수는 됐으나 변수 스냅샷 미열람.

## 1. 수정 항목 (THE ONE — 한 줄기)

### P1. Follow-up 세션 토픽 carry — retrieve seed에 직전 토픽 엔티티 강제 주입

- 증거: F3–F8. history는 같은 메서드 안에 있는데 rewrite(F4 게이트)/subject(F5)/plan(F6,F7) 세 단계가 모두 현재 발화만 봄. 앵커 주입 기구(`sanitizeAnchored`, `planAnchored`)는 이미 존재.
- 구현 타점 (택1로 고수, 둘 다 건드리지 말 것):

  1. **`SubjectResolver.analyze`가 `history`를 실제 사용** — `dr.targetObject`가 비어있고 현재 발화에서 subject 추출이 안 될 때, `history`의 최신 "User:" 라인들을 최신→과거 순으로 `resolve()`/`resolveMultipleEntities()`에 통과시켜 첫 히트를 `targetObject`/`focusKeywords`에 채움(결정론적, 신규 LLM 호출 금지). 단, `SmartQueryPlanner.plan`이 `analysis`가 아니라 자체 `resolve(cleaned)`로 subject를 재추출(F7)하므로, **이 선택지는 plan 경계까지 전달되는 추가 한 다리**(예: `analysis`→plan 인자, 또는 plan 내부 동일 carry 규칙)가 필요 — 없으면 retrieve 쿼리에 안 실림.
  2. **`ChatWorkflow`에서 carriedSubject를 구해 `planAnchored`로 분기** — `analysis.getTargetObject()`/`dr.getTargetObject()`가 비어있으면 `recentHistory`에서 동일 결정론적 추출로 `carriedSubject`를 구하고, `ChatWorkflow.java:1957` 분기를 `routingPlanService.planAnchored(finalQuery, carriedSubject, alias, null, maxBranches)`로 전환. `planAnchored`는 이미 subject를 쿼리에 강제 앵커(F8) — 캡 ≤4도 기존 계약.

- 권장: (2)가 실질 완결 — `planAnchored`가 이미 "subject 강제 포함" 계약을 소유. (1)은 analyze만으로는 plan에 도달하지 못하므로 단독 채택 시 F7에서 끊김. 어느 쪽이든 `finalQuery`/`dr` 재작성 경로와 `casualGreeting`/direct-retrieval-off early-exit(`ChatWorkflow.java:1710–1766`)을 침범하지 말 것.
- carry 규칙: meta-발화("기억 잘 되네", "그럼 이제 어떡해", 감탄/상태 질문)는 토픽 리셋이 아님. carry는 history 상위 N턴(제안: `getFormattedRecentHistory` 윈도 내) 이내의 **고유명사/엔티티**에 한정하고, 현재 발화에 자체 엔티티가 있으면 현재 발화 우선(carry 미적용). 히스토리 없음/EPHEMERAL/`memoryReadEnabled=false`/`conversationContext.present()==false && history empty`면 carry 미적용.
- 관측성: `topicCarry=applied|missed` + `topicCarry.termHash` + `topicCarry.source=history|analysis` TraceStore 키 1~3개 — 본문·엔티티 평문 로깅 금지(hash/길이/사유만).
- 회귀 테스트(신규 포커스드 단위 테스트, 합성 픽스처 — 실계정·유료 생성 금지):
  - (a) history=["User: 아이리 칸나가 뭐냐?","Assistant: …"] + follow-up "그럼 이제 어떡해 해야하냐?" → 계획 쿼리(또는 `planAnchored` 출력)에 "아이리 칸나" 포함
  - (b) history 없음 → 기존 계획 동일(회귀 없음)
  - (c) 현재 발화에 자체 엔티티 → carry 미적용, 현재 엔티티 우선
  - (d) meta-발화만 있는 history → `topicCarry=missed`, 기존 경로
  - (e) EPHEMERAL/`memoryReadEnabled=false` → carry 미적용
- 완료 조건: 영상 3턴 재현에서 T3 계획 쿼리/앵커에 아이리 칸나 실림(`topicCarry=applied` 또는 plan/anchor seed의 termHash 일치) + 답변이 아이리 칸나 후속(추가 정보/공식 출처/다음 질문 제안). `korean.go.kr`·인권위 PDF 등 무관 Evidence로 본문 점유 → FAIL.

### P2 (조건부·선택 — P1로 충분하면 건드리지 말 것)

- **Evidence 게이트**: 활성 세션 토픽과 의미적으로 무관한 웹 Evidence만 있을 때 `fallback_evidence` 강제 대신 hist+토픽 연속 경로 우선 — P1이 토픽 Evidence를 가져오면 불필요. 구현한다면 `ChatHarmonyTracePostprocessor`(판정은 하류·F11)가 아니라 answer-mode 결정 상류에서.
- **GraphRAG 세션 ingest**: 답변 완료 후 `ingestConversationTurn(sessionId, userText, assistantText)`를 채팅 종료 경로에 fail-soft·비동기 배선(기존 서비스 재사용, 동기 블로킹·신규 스키마 금지). `kg.anchor-map` 기본 OFF(F10)는 이번 지시에서 플립 금지 — flag-on 설계가 필요하면 별도 지시.

## 2. 메모리/검색 계약 유지 (검증 대상, 변경 아님)

- `MemoryMode` FULL/HYBRID/EPHEMERAL 의미, `ChatRequestSettingsMerger`의 `memoryMode`/`sessionId`/`history` 보존, `conversationContext` 경계(`conversation_context_limit`)는 SESSION56 지시와 동일하게 유지 — carry 구현이 이 경로를 우회하는 신규 전달 경로를 만들지 말 것.
- `fallback_evidence`/`smooth_chat` 판정 로직 자체(F11)와 `exactHistoryFallback` 계약 유지.
- `planAnchored`의 기존 유일 호출자 `PairingGuardHandler` 동작 보존(시그니처 변경 금지, 필요 시 오버로드).

## 3. 금지 경계

- Git: push/fetch/merge/rebase/reset/clean/`add -A`/`add .`/`commit -a`/`--no-verify`, 타인 staged 경로 취득·해제, 시크릿·대화·DB·대형 로그 커밋 금지. 선택 로컬 커밋은 `python -B scripts/agent_git_vibe_commit.py` 단일 진입점(스테이징 시크릿 스캔 통과 후 1회).
- Jev·GPU·RuleBreak·evidence-0 HOLD·Trace Memory unavailable/Agent DB PROBE_UNAVAILABLE 표기 재오픈 금지 — 본 목표는 토픽 carry뿐.
- "기억 기능 설명" 국립국어원/인권위 PDF 오탐을 정상 동작으로 문서화 금지.
- Harmony 라벨·UI 배너만 고치고 retrieve seed를 안 고치는 패치 금지(F11).
- `kg.anchor-map`/`KG_ANCHOR_MAP_ENABLED` 기본값 플립, 신규 GraphRAG/Neo4j 스키마, 병렬 검색 스택, 대량 문서 ingest 금지.
- `.secrets/` 열람·키 값 출력·프롬프트/응답 본문 로그 금지 — 진단 필드는 count/hash/reason만. `openssl` 키명·값·형식 변경 금지.
- 라이브 반영은 compile 후 ForceRestart/DevWatch(`$demo1-dev-reload`) — stale bootRun 200으로 성공 주장 금지. 검증 재플레이는 합성 픽스처 위주, 유료 API 재호출 최소(spend-guard).
- foreign lease/미기록 외부 변경 파일과 겹치면 soft preserve — 덮어쓰기·복원 금지, 저널에 `미기록 외부 변경`으로 기록.

## 4. 검증 (강제)

같은 세션에서 3턴 재현(수동 또는 스크립트, 합성 세션 허용):

1. `아이리 칸나가 뭐냐?` → 토픽 관련 답 (웹이어도 엔티티는 칸나)
2. `내가 방금 뭐라고 했냐` → 아이리 칸나 질문 회수 (기존 short-circuit 그대로여도 됨)
3. `오, 기억 세션 저장기능 잘 작동하네, 그럼 이제 어떡해 해야하냐?` → **아이리 칸나 연속** 답변. 무관 공식 PDF Evidence 본문 점유 시 FAIL.

- Debug: T3에서 `topicCarry=applied` 또는 plan/anchor seed에 칸나 흔적(hash 대조); Harmony가 무관 evidence에 먹히지 않음.
- 신규 단위 테스트 전부 + `gradlew :compileJava -x test` 통과.
- H1–H4 확정값을 보고에 명시(모르면 `not_observed` 유지, 추정 확정 금지).

## 5. 보고 형식

1. **확정된 원인**: H1–H4 각각 trace 값 근거로 사실/기각 표시.
2. **변경 파일**: 경로 + diff 요약 + 변경 전후 sha256.
3. **재현 결과**: T3 시나리오 수정 전/후 답변 요약 + `topicCarry`/계획 쿼리 seed 증거.
4. **테스트**: 실행 명령 + 통과/실패 카운트 + 신규 테스트명.
5. **미해결**: 남은 원인/리스크 — 없으면 "없음" 명시.

## Done 정의

- 영상 3턴 재현에서 T3가 아이리 칸나 연속 + 무관 Evidence 비점유.
- 변경이 QueryRewrite/Subject/Retrieve seed 중 **연속성 한 줄기**에 국한.
- 선택 로컬 커밋 + 검증 메모(어느 파일·왜). 푸시 없음.
- 막히면 추측 확장하지 말고 재현 debug 한 줄 + 막힌 심만 보고.
