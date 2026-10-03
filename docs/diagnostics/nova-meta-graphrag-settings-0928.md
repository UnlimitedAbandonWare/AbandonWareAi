# Nova Focus × GraphRAG·메모리·설정 진단 (WP0)

- 계약: `DEMO1-DEVIN-NOVA-META-GRAPHRAG-SETTINGS-20260928`
- taskId: `nova-meta-graphrag-settings-0928-d70a0915` (agent devin)
- evidenceCheckedAt: 2026-09-28 12:1x UTC (라이브 소스/스냅샷 기준)
- 범례: **[사실]** = 이 체크아웃 소스/실행 관찰로 확인 · **[추정]** = 간접 근거 · **[미관찰]** = 증거 없음

## 0) 한 줄 판정 (지시서 §0 재확인)

**지금은 Even G2 / Rokid급 “그래프RAG 기반 메모리세션 서비스”가 아니다. [사실]**
뼈대(Nova Focus 유틸 + 옵트인 FocusMemory + Fold 렌즈 설정)는 있고,
Focus 안의 “그래프”는 Neo4j가 아니라 **owner-scoped fact 엔티티 공출현 확장**
(`BrainStateService.expandPrivate`, 정적, ≤2 hop)이다. 전역 GraphRAG/Neo4j는
Focus 경로에 **붙어 있지 않다(NOT_WIRED)** — 설정 게이트로만 옵트인해야 한다.

| 축 | 현재 | 판정 |
|---|---|---|
| 유틸 이름 Nova | `NovaFocus*`, wake `노바`, `focus` 필드 분리 | 유지 |
| 메모리 세션 | 기본 EPHEMERAL; `recallEnabled`/`rememberFactsEnabled` 기본 false; `FocusMemoryService` owner-scoped, 최대 256 fact, 자동 인덱싱 없음 | 설정으로만 확장 |
| GraphRAG | Focus 경로 = 사실 엔티티 co-mention 확장만; `KnowledgeGraph*`/Neo4j/`privateSources`는 Focus에 NOT_WIRED; `retrieval.kg.neo4j.enabled=false`, anchor-map 기본 false | `graphMode` 옵트인만 |
| 디스플레이 | `focus` 별도 필드, 순차 렌더 계약; `hint`≠`focus` | 유지 |
| 입맛 조절 | `LensDisplayPrefs`(16필드)+`NovaFocusSettings`(11필드) 서버권위 | 크리커=프리셋/모드 버튼으로 노출 |

## 1) NovaFocusSettings 필드 표 (record, `NovaFocusSettings.java`)

| 필드 | 기본 | 범위/검증 | Fold 키 | 노출 |
|---|---|---|---|---|
| enabled | false | — | `nf-enabled` | O |
| wakeWord | `노바` | ≤16 codepoints, 제어문자 금지 | `nf-wake` | O |
| utteranceQuietMs | 1200 | 500–5000 | `nf-quiet` | O |
| followupIdleMs | 20000 | 5000–120000 (`presentation_done` 기점) | `nf-idle` | O |
| wakeListenTimeoutMs | 8000 | 3000–30000 | `nf-listen` | O |
| presentation.* | sequential 80ms/6줄/fade 5000+400 | charInterval 50–160, lines 4–8, tail 2000–15000, fade 200–1000 | `nf-sequential/speed/lines/fade-on/hold/fade` | O |
| recallEnabled | false | — | `nf-recall` | O |
| rememberFactsEnabled | false | — | `nf-remember` | O |
| snapshot(enabled,source) | false, FOLD_REAR | source∈{FOLD_REAR,META_GLASSES} | `nf-snapshot-*` | O |
| answerSelection(mode,modelId,routing) | AUTO | routing.target∈{AUTO,API_ONLY,LOCAL_ONLY}, fallback≤3 | `nf-answer-*` | O |
| recentContext(enabled,age,count,budget) | true,180,12,2000 | 30–180s, 1–12, 256–2000 | `nf-recent-*` | O |
| **memory mode / graphMode / maxEvidence / embeddingPrefer / webOnUnknown** | — | — | — | **X (이번 승격 대상)** |
| jev mode | 서버 소유 표시 | — | `nf-jev` (읽기전용, disabled) | 표시만 |

## 2) FocusMemoryService.Result 필드 표 (`FocusMemoryService.java`)

```
Result(evidence, status, retrievalMode, vectorHits, graphHits,
       graphHops, evidenceBytes, tookMs, truncated, degradationReason)
Status = OFF | NO_AUTHORIZED_MEMORY | OK | DEGRADED | BLOCKED_SCOPE | TIMED_OUT | CANCELLED
retrievalMode = SCOPED_VECTOR_LOCAL_GRAPH | SCOPED_LEXICAL_LOCAL_GRAPH | NONE
```

- `vectorHits`: owner scope 내 compatible 임베딩 → `InMemoryEmbeddingStore` ANN, `minScore 0.65`, 최대 6.
- `graphHits`: vector+lexical 선택 후 `expandPrivate`로 추가된 fact 수. 트리거 = 관계어 정규식
  `(관계|연결|함께|원인|related|connected)` **또는** `selected.size()<2` → 사실상 기본 AUTO.
- evidence 상한: `size>4` 또는 `evidenceBytes>3072` → truncate (현재 **하드코딩 4** — maxEvidence 승격 대상).
- `save()`: 확정(`confirmed`) 사실만, ≤1200B, 엔티티 ≤8, assertionType∈{USER_REPORTED,HYPOTHESIS,ASSISTANT_GENERATED}, revision CAS, sourceId 재시도 멱등.
- `delete()`: 전 버전 소거(`text=null`,`entitiesJson="[]"`,embedding null) — GDPR식 소거 시맨틱.

## 3) Focus → 그래프 호출 경로 (callgraph)

```
NovaFocusService.generate()
  └─ memories.scope(owner,channel)                 # settingsVersion+memoryRevision 바인딩
  └─ NovaFocusAnswerService.answer(room,q,…,scope)
       └─ FocusMemoryService.retrieve(scope,q,current)
            ├─ rows(scope)  ≤257 current facts      # H2 focus_memory_fact
            ├─ ANN (InMemoryEmbeddingStore)         # vectorHits
            ├─ lexical: question ⊇ entity           # 폴백 선택
            └─ BrainStateService.expandPrivate(     # graphHits (≤2 hop, ≤20 nodes, ≤40 edges)
                   namespace, PrivateFact[] , seeds, current)
```

- `expandPrivate` = **정적 순수 함수**: 전역 `chunks`/`entities` 맵·Neo4j·`recordChunks` 미접촉. **[사실]**
- `BrainStateService.privateSources(GeneralGraphScope,…)`(글로벌 청크 맵 경로)는 **일반 채팅 RAG 전용** —
  Focus가 호출하지 않음. `KnowledgeGraphHandler`/`Neo4jKnowledgeGraphClient`도 Focus 무관. **[사실 → NOT_WIRED]**
- Neo4j: `retrieval.kg.neo4j.enabled` 기본 **false**; `disabledReason` 체인(missing_uri/user/password/unsafe) 있음.
- anchor-map: `rag.brain-state.anchor-map.enabled`(=`kg.anchor-map.enabled`) 기본 **false**.
- `rag.brain-state.enabled` 기본 true이나 `expandPrivate`는 이 플래그를 보지 않음(정적 경로). **[사실]**

## 4) 임베딩 레인 현재값

| 키 | 현재 | 위치 |
|---|---|---|
| `focus.memory.embedding.local-enabled` | **false** (env `FOCUS_MEMORY_LOCAL_ENABLED`) | `FocusMemoryService` `@Value` |
| `focus.memory.embedding.cloud-enabled` | **false** (matchIfMissing=false + fallback 키 조건) | `FocusMemoryEmbeddingConfig` 빈 조건 |
| private lane 해석 | `configs/api-routing.yaml` `ollama.default_hosts[0]`=`127.0.0.1:11434` + `native_embed_path=/api/embed` + `installed_models.embed[0]`=`qwen3-embedding:4b` → `http://127.0.0.1:11434/api/embed` (loopback 강제) | `OllamaEmbeddingModel.privateSpace()` |

- **DEGRADED 경로 존재**: local=false 이고 cloud 빈 없음 → `focus_embedding_route_unavailable` →
  `Status.DEGRADED` + `degradationReason=embedding_unavailable_IllegalStateException` → lexical+graph만 동작. **[사실]**
- 주의: 전역 `embedding.base-url` 기본은 `127.0.0.1:11435`(`application-llm.yaml`)인데 Focus private lane은
  api-routing `default_hosts[0]`=**11434**로 고정 — 두 레인이 다르다. 의도인지 문서화 필요.
- Ollama 스냅샷(2026-09-28 12:0x UTC, `scripts/ollama-status-snapshot.ps1`): serve 3개(11434/11435/11438) 기동,
  `/api/ps` 전부 0개 로드, `qwen3-embedding:4b` 설치됨. **11434 ↔ GPU UUID 귀속은 [미관찰]** — runner 프로세스 없어
  포트→PID→GPU 체인 불가. 3060(idx0, UUID …e670)은 디스플레이 점유 중, 3090(idx1, …e586) idle.
- local 실패 시 60s `localRetryAfterNanos` 쿨다운 → cloud 폴백. embeddingPrefer 설정은 이 순서를 입맛으로 노출.

## 5) Fold UI 노출 표 (`assets/display/index.html` + `display-focus-controls.js` + `app.js`)

| 구역 | 노출된 키 | 미노출(이번 대상) |
|---|---|---|
| `#lens-display` (`ld-*`) | transcriptFontPx, hintFontPx, transcriptMaxLines, hintPageLines, transcriptTtlMs, hintTtlMs, autoPageMs, triggerQuietMs, cueCooldownMs, forceAfterMs, hintTargetChars | **`lens.preset` 크리커 버튼** |
| `#hint-context` (`hc-*`) | historyEnabled, historyWindowMs, historyMaxChars, historyMaxTokens, topicResetEnabled | — |
| `#nova-settings` (`nf-*`) | enabled, wakeWord, quiet/idle/listen, presentation.*, recall/remember, snapshot.*, answerSelection.*, recentContext.*, jev(RO), memory read/save form | **memory mode 크리커, graphMode, maxEvidence, embeddingPrefer, webOnUnknown** |

- settings 전송: `focus/settings` POST `{assistId,epoch,clientId,settingsVersion,settings}` → `NovaFocusService.configure` →
  `NovaFocusHistoryService.settings(owner,channel,expected,value)` — settingsVersion CAS, 충돌 시 `focus_settings_conflict`.
  **생략된 optional 블록은 서버 저장값 보존** 패턴이 이미 있음(snapshot/answerSelection/recentContext) → 새 memory 블록도 동일 규칙.
- `lensSettings` PATCH: `LensSettings` 레코드 → `LensDisplayPrefs.patch(Patch)` 필드별 범위 검증, 400 `invalid_lens_settings:<field>`.

## 6) 모름→웹 (webOnUnknown) 현재

- `conversate.focus.unknown-web-enabled` 기본 **true**(전역), `conversate.focus.unknown-web-scoped-enabled` 기본 false.
- `UnknownAnswerPolicy.decide`: 모름 신호(`classify`)시 요청당 **최대 1회** 웹 재시도; RECENT_ONLY/SCOPED_RAG 우선순위·
  `request_web_off`·`web_already_attempted` 게이트. trace: `focus.unknown.*`.
- 지시서 `focus.webOnUnknown`은 **per-owner 좁힘 토글**로 해석: 전역 정책 안에서 owner가 OFF 선택 가능
  (ON이 전역 게이트를 열지 않음 — spend guard). 새 라우터 금지 → 기존 decide() 재사용.

## 7) 설정 vs 소스 경계 (§1 A/B/C 라이브 확인)

- A(이미 설정): §1 표 그대로 — `LensDisplayPrefs` 16키 + `NovaFocusSettings` 11키, 전부 서버권위+settingsVersion. **[사실]**
- C(소스만): `HINT_TEXT_MAX=1180`/`HINT_LINE_MAX=24`/`LENS_LINE_BUDGET`/`MIN_CAPTION_LINES` 하드캡,
  `/lens/text` RO, hint≠focus, 원시 Q/A 자동저장 금지(EPHEMERAL), Neo4j 강제설치 금지 — 이번 패치 범위 밖 유지.
- B(승격): `memory.mode` → 기존 boolean 매핑 크리커, `graphMode` OFF|AUTO|ON, `maxEvidence` 1–8(기본=코드 4),
  `embeddingPrefer` LOCAL_ONLY|LOCAL_THEN_CLOUD|CLOUD_ONLY, `webOnUnknown`(per-owner 좁힘), `lens.preset`
  READ_EASY/DENSE/DEFAULT(설정 브리프 Q1: 30px·8줄 / 22px·13줄 / 기본) — Fold 큰 버튼.

## 8) 잔여 리스크

- `embeddingPrefer=LOCAL_ONLY` + local-disabled 조합 = 사실상 벡터 검색 무효 → Fold UI에 경고 표시 필요.
- graphMode ON이어도 Neo4j 없음: 현재 expandPrivate(엔티티 co-mention)만 — 기대치 문구는 "로컬 사실 그래프".
- `FocusMemoryScope.policyRevision` 현재 1 고정 — 스코프 필드 확장 시 검증 로직 확인 필요.
- Autograde R3(`ChatSessionMetaMerger`, chat.js 계열)와 파일 비겹침 — 현재 리스 목록에서도 충돌 없음 [사실].
