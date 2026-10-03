# PLAN5 보류 장부 (hold ledger) — devin-plan5-assist-685f4d65

Live re-verification of PLAN5 audit hold items. Read-only investigation;
no product source changed. HEAD `4150b282` + working tree (Codex PLAN5
edits in flight — line numbers drift; verify before use).

Re-verified: 2026-10-02, `main/java` + `main/resources`만 검색
(`data/agent-handoff`, `agent-prompts`, `__patch_drop__` 검색 제외).

| ID | 라이브 위치 | 지금도 재현? | 정책 충돌 | 추천 담당 | 다음 브리프 묶음 제안 |
|----|-----------|-------------|-----------|----------|--------------------|
| F01 proto-open 기본값 | `main/resources/application-meta-display.yml:140` (`proto-open: ${DEMO_AUTH_PROTO_OPEN:true}`); `security/AdminTokenGuardInterceptor.java:87,91,96,401,405`; `security/AdminTokenGuardFilter.java:89`; `security/ChatOpenSecurityConfig.java:58,236` | YES | PROTO_OPEN 유지 정책 때문에 HOLD | HOLD | — (해결안 없음) |
| F02 `POST /api/chat/models/install` 공개 | `api/ChatModelInstallController.java:25` (`@PostMapping`), `:38` (status GET); `security/ChatOpenSecurityConfig.java:208` (`/api/chat/**` permitAll) | YES | PROTO_OPEN 유지 정책 때문에 HOLD | HOLD | — (해결안 없음) |
| F03 DB 조회 readOnly 미강제 | `api/MetaDisplayDbQueryController.java:114-136` (`/query` + `SqlGate.check` :125), `readOnlyConnection()` :178-182; `@Profile("meta-display")` :39로 디버그 표면 한정 | YES(구조적) | PROTO_OPEN 유지 정책 때문에 HOLD | HOLD | — (해결안 없음) |
| F04 영수증 없이 ingestedCount++ | `service/ConversationArchiveIngestService.java:67-69` (`enqueue` 후 즉시 `ingestedCount++`, 반환 receipt 미사용) — probe P7 = STILL_PRESENT | YES | 없음 | Codex | PLAN5 P1 (진행 중 브리프 WP2) |
| F05 TLS property가 YAML 가림 | `main/resources/application.properties:973-974` (`security.force-https=`, `server.ssl.enabled=` literal) vs `main/resources/application.yml:89` (`force-https: ${SECURITY_FORCE_HTTPS:${SERVER_SSL_ENABLED:false}}`) — 동일 키 두 SSOT | YES | 프로토타입 TLS 기본값 정책(명시적 false 유지 여부는 제품 결정) | Codex | config 정리 브리프 (UAW-D와 묶음) |
| F06 `setReadOnly` 실패 시 커넥션 누수 | `api/MetaDisplayDbQueryController.java:178-182` (`getConnection()` 후 `setReadOnly` — 예외 시 close 없음) — probe P8 = STILL_PRESENT | YES | 없음 | Codex | PLAN5 P1 (MetaDisplayDbQueryGateTest) |
| F07 COUNT 제한 없음 | `api/MetaDisplayDbQueryController.java:66` (`/tables`가 테이블마다 `rowCount` 호출) + `:184-191` (`SELECT COUNT(*)` — `setQueryTimeout` 없음). 참고: `/query` 본문은 이미 `st.setQueryTimeout(10)` :136 + `setMaxRows(cap+1)` :137 보유 — 감사 당시보다 부분 개선. 총 응답 바이트 예산 없음(CELL_CHAR_CAP :47은 셀당 한계) | YES(범위 축소) | 없음 | Codex | PLAN5 P1 (WP1) |
| F08 중복 컬럼명 덮어쓰기 | `api/MetaDisplayDbQueryController.java:143-146` (`md.getColumnLabel(i)` 수집) → `:154-156` (`row.put(label, …)` — 중복 라벨 시 앞 값 소실); `totalMatched` :148-151,168은 truncate 여부와 무관하게 전체 행 카운트 | YES | 없음 | Codex | PLAN5 P1 (WP1) |
| F09 BM25 bean 부재/snippet 빈값 | `service/rag/orchestrator/UnifiedRagOrchestrator.java:232-233` (`@Autowired(required=false)` `service.service.rag.bm25.Bm25Index` — bean/component 없음), `missing_bm25Index` 경로 :2891, `toDocsOrEmpty` :4688+ (Doc snippet 비움) | YES | 과거 KIT_D 지시: "bean 신규 등록 금지" 규칙과 충돌 가능 — 등록/미등록은 Codex 판단 | Codex | PLAN5 P1 (BM25-F), 단 bean 등록 금지 규칙 명시 |
| F10 LocalBm25Retriever 재토큰화 | `service/rag/retriever/LocalBm25Retriever.java:31` (`add()`가 `docs.stream().mapToInt(tokenize…)`로 전 문서 재토큰화 — O(N²)); `:46` topK도 문서마다 재토큰화 | YES | 없음 | Codex | perf 브리프 (BM25/검색 성능 묶음) |
| F11 `ApiSpendAttribution` `new StandardEnvironment()` | `routing/ApiSpendAttribution.java:20` (`agentModeActive`), `:42` (`ApiRoutingPolicySnapshot` 생성); 관련 `routing/AgentApiSpendGuard.java:22` | YES | 없음 | Codex | 라우팅 정책 브리프 (R03과 묶음) |
| F12 raw NUL 바이트 | `service/rag/orchestrator/UnifiedRagOrchestrator.java:3104` (U+0000, live nul-scan) + `:3089` (U+0001 ×2) — probe P9 = STILL_PRESENT | YES | 없음 | Codex | PLAN5 P1 (WP4) |
| F13 대형 클래스 | `UnifiedRagOrchestrator.java` 4,750줄; `service/ChatWorkflow.java` 14,039줄; `service/rag/HybridRetriever.java` 2,601줄 | YES | 없음 | HOLD | 장기 리팩터링 브리프 (분해 계획 선행 필요) |
| R01 DNS 검증 vs 실제 소켓 목적지 | `service/rag/extract/PageContentScraper.java:168-204` (`validatePublicTarget` → `InetAddress.getAllByName` :191) vs `:150` (`Jsoup.connect(targetUrl)` — 검증된 주소를 소켓에 고정하지 않음, 재조회 가능) | YES | 없음 | Codex | R-시리즈 SSRF 브리프 |
| R02 archive no-session owner 전파 | `api/AttachmentController.java:98` (`authorizeAndResolveOwner(…)` 반환값 폐기) → `service/ConversationArchiveIngestService.java:57` (null sid → `__TRANSIENT__`); 동일 fallback `service/VectorStoreService.java:376` | YES | 없음 | Codex | R-시리즈 브리프 (R02) |
| R03 unbounded/colliding success cache | `routing/ApiSpendAttribution.java:14` (`static ConcurrentHashMap SUCCESS_CACHE` — eviction 없음), `fingerprint` :81 (`purpose/provider/model/caller/probeId` 문자열 join — 구분자 충돌 가능) | YES | 없음 | Codex | 라우팅 정책 브리프 (F11과 묶음) |
| UAW-A 모델 검증 불일치 | `api/SettingsController.java:31` (`SettingsService.KEY_OPENAI_MODEL` allowlist 저장) vs `service/ModelSettingsService.java:49` (`changeCurrentModel` — 파일/placeholder/remote 정책·endpoint 호환 검증) — 두 경로 검증 깊이 상이 | YES | 없음 | Codex | UAW 통합 브리프 |
| UAW-B 기본 모델 저장소 2중 | `service/ModelSettingsService.java:28` (`CurrentModelRepository`, id=1 singleton) vs `service/SettingsService.java:33,41,166` (`ConfigurationSettingRepository` + `KEY_OPENAI_MODEL` default) | YES | 없음 | Codex | UAW 통합 브리프 |
| UAW-C RuleBreakContext 미전파 | `infra/exec/ContextPropagation.java` — RuleBreak 참조 0건 (MDC/GuardContext/TraceStore/TimeBudget 등만 전파); canonical `guard/rulebreak/RuleBreakInterceptor.java` 존재 | YES | 없음 | Codex | UAW 통합 브리프 |
| UAW-D yml/properties 중복 키 | `application.properties:724` (`retrieval.vector.enabled`), `:854/:857/:858` (`llm.fast.*`) vs `application.yml:96-98` (`retrieval.vector`), `:241-255` (`llm.fast.model`/`:251`, `max-retries`/`:254`, `max-tokens`/`:255`) — 최소 4키 재확인(지시서는 9키 주장) | YES | 없음 | Codex | config 정리 브리프 (F05와 묶음) |

## 요약
- 재현 YES: 20 / NO: 0 / UNKNOWN: 0.
- HOLD(PROTO_OPEN): F01, F02, F03 — 정책 유지, 해결안 기재 안 함.
- HOLD(유지보수성): F13 — 별도 분해 브리프 필요.
- Codex PLAN5 P1 진행 중: F04, F06, F07, F08, F09, F12 (+ probe P3~P9).
- 별도 브리브 후보: 라우팅 정책(F11+R03), SSRF/owner(R01+R02), UAW 통합(A~D), config 정리(F05+UAW-D), perf(F10), 리팩터(F13).
