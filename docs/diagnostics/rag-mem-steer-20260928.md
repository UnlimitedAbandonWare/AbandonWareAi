# rag-mem-steer M1–M9 실측 매트릭스 (2026-09-28)

Contract: `DEMO1-DEVIN-RAG-MEM-STEER-CODEX-20260928-R1`
Agent: devin · journal: `rag-mem-steer-0928-76d11b5a` · product diff: **0** (probe tests only)

## Evidence rail

| rail | status |
|---|---|
| live `/api/chat/sync` 2-turn | `admission-blocked` (HTTP 503, `X-Admission-Mode: demo-memory`; server 200·pid 27020) — concurrent B02-R2 probe window; classified NOT target-down |
| durable traces `var/debug/chat-session-traces` | keys-only schema (values not stored); `finalAnswer.*` / `memory.rehydrate.*` / `retrieval` keys confirmed firing on completed chat `s-16dc368a89b4` |
| focused probe tests (NEW, this task) | `RagMemSteerDynamicToggleProbeTest` (api) + `RagMemSteerReleaseContractProbeTest` (service) — 17 tests GREEN, assert *observed* behavior |
| pre-existing suites | `ChatRequestSettingsMergerTest`17 `SessionSettingsPrecedenceTest`5 `FinalizedMemoryPersistenceTest`4 `FinalAnswerPostProcessorTest`11 = 37 GREEN |

Probe run: `gradlew test --tests com.example.lms.api.RagMemSteerDynamicToggleProbeTest --tests com.example.lms.service.RagMemSteerReleaseContractProbeTest` → exit 0, 17/17.

## Matrix (measured)

| M | cell | verdict | measured evidence |
|---|---|---|---|
| M1 | RAG ON→OFF | **PASS** | `m1_*` probes: t2 intent `(web=T,rag=F)`; contract `ragRequested=false`, web-only `EVIDENCE_PRESENT`→release+`knowledgeWriteAllowed`. Explicit `useRag=false` clamps `req.isUseRag()\|\|strategy.isUseVectorStore()` (workflow ~1808-1816). Stale vector evidence cannot requalify a dropped lane. |
| M2 | OFF→ON | **PASS** | `m2_*`: contract is per-turn stateless — explicitDirectOff→NOT_APPLICABLE on OFF turn, fresh full contract on ON turn. No residue fields. |
| M3 | AUTO→OFF→ON | **FAIL (shared seam w/ M9)** | OFF-turn contract correct (explicitDirectOff→passthrough release). **But** a turn that *omits* `searchMode` clobbers stored `OFF`→`AUTO` — see M9 root cause. With explicit resend: PASS. |
| M4 | RAG ON+web OFF | **PASS (design)** | `m4_*`: `ragRequested` w/o vector locators → `METADATA_INCOMPLETE`; released w/ `evidence_unverified_release`+memory-block, or HOLD under `evidence_needed` directive. SCOPED_RAG semantics intact. |
| M5 | normal answer→memorySaveAllowed | **FAIL (measured gap)** | `m5_*`: default turn (no `memoryMode`) → `MemoryMode.HYBRID` → `memoryWriteEnabled=false` → postproc `write_disabled` → all 4 persist stages skipped. FULL reachable only via explicit `memoryMode` or `projection_agent.v1` `deep_memory`. `memoryMode` absent from session-meta schema → explicit FULL silently degrades on omitting turns. Lane-A transcript persists regardless (by design). |
| M6 | METADATA_INCOMPLETE | **PASS** | `m6_*`: required→HOLD `evidence_release_metadata_incomplete`; else `evidence_unverified_release` + `knowledgeWriteAllowed=false` → postproc `memory_policy_denied`. Memory blocked both branches. |
| M7 | cancel/recover | **PASS (existing coverage)** | `FinalizedMemoryPersistenceTest` 4 GREEN: cancellation-fenced `tryBeginCommit`, suppression sink. |
| M8 | EPHEMERAL vs normal | **PASS** | `m8_*`: `isReadEnabled=false`→`memoryReadEnabled=false` (workflow ~1545) → no session recall; write disabled. |
| M9 | reload→next turn | **FAIL** | `m9_*` measured: stored `searchMode="OFF"`/`"FORCE_LIGHT"` restored as `AUTO`. Root cause: `ChatRequestDto.searchMode` `@Builder.Default AUTO` ⇒ `ChatSessionMetaMerger` `getSearchMode()!=null` always true ⇒ null-restore branch unreachable, meta overwritten `"AUTO"`. `useRag/useWebSearch/model/precisionSearch/guardLevel` restore correctly (nullable). |

## Codex target cards (measured FAILs only)

### TARGET: M9 (affects M3 omitting turns)

- **symptom**: `session_meta.searchMode` 저장값(OFF/FORCE_*)이 다음턴 `searchMode` 생략 요청에 AUTO로 덮인다 — restore 분기 사문화.
- **evidence**: `RagMemSteerDynamicToggleProbeTest` — `m1_omittedFlagsAfterOff`(저장 OFF→실측 AUTO), `m9_sessionMetaRoundTrip`(저장 FORCE_LIGHT→실측 AUTO). 원인: `ChatRequestDto` `searchMode` `@Builder.Default AUTO` + `ChatSessionMetaMerger.java:52` null-비교.
- **suspect files**: `main/java/com/example/lms/dto/ChatRequestDto.java` (searchMode 필드/setter, `webSearchExplicit` 패턴 425-442 참조), `main/java/com/example/lms/api/ChatSessionMetaMerger.java:52-63`; 부수 후보 `main/resources/static/js/chat.js` (`searchMode: ... || "AUTO"` phantom-AUTO).
- **must_not_break**: B01 OFF 보존, B02 `agentPromptSearch` seam (외국 리스 활성 — 코드 소유 아님), NW 초단위, CUE/SCOPED_RAG/RECENT_ONLY, METADATA_INCOMPLETE 기억차단 분기, PROTO_OPEN.
- **patch_goal**: 최소 seam — 명시성 마커 도입. `setSearchMode`/`@JsonSetter`에서 `searchModeExplicit` transient 플래그 세팅(webSearchExplicit 패턴 미러)하고, merger가 explicit일 때만 meta 기록·비명시일 때만 복원하도록 분기. `searchMode` nullable 화 같은 넓은 변경 금지.
- **done**: flip probe asserts → 저장 `OFF`/`FORCE_LIGHT` 복원 GREEN; 기존 `SessionSettingsPrecedenceTest`+merger suites GREEN 유지.
- **forbid**: 새 메모리 버스, 전역 HOLD, RuleBreak 재등록, admin harden, A*, 성능F.

### TARGET: M5

- **symptom**: 기본/UI 턴의 장기(학습) 메모리 쓰기가 구조적으로 도달 불가 — `memorySaveAllowed=false(write_disabled)`; `memoryMode=FULL` 명시 세션도 다음 생략턴에 HYBRID로 강등.
- **evidence**: `RagMemSteerReleaseContractProbeTest.m5_*`(`write_disabled` 실측; FULL 요청 시 `none`+memoryContent 채움), `RagMemSteerDynamicToggleProbeTest.m5_*`(session-meta 스키마에 memoryMode 없음 실측), `ChatWorkflow.java:4405`(`memoryMode.isWriteEnabled()`), `MemoryMode.fromString(null)→HYBRID`, `chat.js`는 memoryMode 미전송, `PlanDslLoader`는 `projection_agent.v1`에서만 `deep_memory`.
- **suspect files**: `main/java/com/example/lms/api/ChatSessionMetaMerger.java`(스키마), `main/java/com/example/lms/service/ChatWorkflow.java`(~1497 mode resolve, ~4405 write gate), `main/java/com/example/lms/domain/enums/MemoryMode.java`.
- **must_not_break**: 위와 동일 + lane-A transcript(ChatHistoryService `updateRollingSummary`)·EPHEMERAL 읽기차단·cancel-fenced persist 계약.
- **patch_goal**: intent 확인 필수 — (a) 명시 `memoryMode`를 session-meta에 지속(최소 seam; M9와 동일 패턴) OR (b) 기본 세션의 write-enabled 기본값 상향은 **제품 결정**이므로 카드 범위 밖에서 사용자 확인. (a)만으로는 기본 경로가 여전히 HYBRID — 카드에 그 한계 명기.
- **done**: (a) 채택 시 RED flip→FULL 세션 생략턴 복원 GREEN; `FinalizedMemoryPersistenceTest`·postproc suites 유지.
- **forbid**: 기본 memoryMode 묵시적 상향(제품결정 없이), 새 메모리 버스, 전역 HOLD, RuleBreak 재등록, admin harden, A*, 성능F.

## Codex injection sentence (§4-A, as-brief)

> Devin 매트릭스 결과를 보고 소스 수정해. Project Root=C:\AbandonWare\demo-1\demo-1\src
> B00/B02/B01 기존 결과 되돌리지 마.
> 우선 Devin이 FAIL로 준 M# 하나만. RED focused → 최소 패치 → GREEN.
> RAG/검색 토글·다음턴 기억 꼬임만. admin/전체suite/A*/성능F/commit/push 금지.
> 전역 HOLD·RuleBreak 재등록·NW 대기 복원 금지.

## Deferred / not-observed

- live 2-turn HTTP repro: `admission-blocked` (503 demo-memory) — server healthy; 재시도 창은 B02-R2 완료 후.
- `RetrievalRequestIntent` null-vs-effective divergence: measured non-harmful (null intent → not-explicitOff → no contract expansion unless `evidenceReleaseRequired`); 기록만, 카드 아님.
- M1 mid-context transcript 잔여 벡터 인용: lane-A transcript에 이전턴 답변이 남는 것은 설계상 회상 — 카드 아님.
- memoryMode 기본값 상향: 제품 결정 — 카드 M5에 한계 명기로 대체.
