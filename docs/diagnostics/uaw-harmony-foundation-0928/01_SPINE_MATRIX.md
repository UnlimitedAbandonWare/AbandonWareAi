# 01_SPINE_MATRIX — UAW 큰 그림 ↔ live 스파인 매트릭스

- Contract: `DEMO1-DEVIN-UAW-HARMONY-FOUNDATION-20260928` (WP0, read-only)
- Date: 2026-09-28 (Asia/Seoul) · taskId `uaw-harmony-foundation-0928-c50981a8` · agent `devin-uaw-harmony`
- 판정 원천: Abandon_X.txt §4(2026-09-23 정적 교정본) + **이번 세션 live 재검증**.
  UAW.txt 포트폴리오 문장은 intent-only — 수치·DONE/LIVE 표기를 증거로 재사용하지 않음.
- Verdict: LIVE / PARTIAL / DEAD / DORMANT / DISABLED / ABSENT / META /
  STALE(과거 주장이 현재와 다름) / `evidence_needed`.
- `사실` = 이 checkout에서 이번 세션 명령으로 확인. `문서` = Abandon_X 등 문서 주장(미재실행).

## A. 스파인 (조화 유지 대상 — "세트 효과")

| 기능 | UAW/Abandon_X 주장 | live 판정 (사실) | canonical 경로 | 밑밥 아티팩트 | 소유 |
| --- | --- | --- | --- | --- | --- |
| Boot 런처 | canonical=LmsApplication, AgentApplication은 레거시 | **LIVE+WARN** — `build.gradle.kts` `mainClass.set("com.example.lms.LmsApplication")` (L931), `scanBasePackages={com.example.lms, com.nova.protocol}` (LmsApplication L20). `AgentApplication.java` 존재 지속 → 존재 경고만 | `main/java/com/example/lms/LmsApplication.java`, `main/java/com/abandonware/ai/agent/AgentApplication.java` | 있음: `scripts/uaw_spine_probe.py` S1/S2/S4 | probe=Devin / 런처 삭제=승인+Codex |
| Nova orch autoconfig | imports 6종, ~67 @Bean | **LIVE** — `AutoConfiguration.imports` 정확히 6행, 중복/누락 0 (probe S3) | `main/resources/META-INF/spring/…AutoConfiguration.imports` | 있음: probe S3 | 관측=Devin / 빈 변경=Codex |
| RAG spine | UnifiedRagOrchestrator + DynamicRetrievalHandlerChain | **LIVE** — 두 클래스 모두 canonical 패키지에 존재, PlanHintApplier 호출 확인 | `service/rag/orchestrator/UnifiedRagOrchestrator.java`, `service/rag/handler/DynamicRetrievalHandlerChain.java` | 있음: probe S5 | — |
| planDsl 계약 | status=`not_used`(broad), hint/projection만 소비 | **LIVE(계약 유지)** — `planDsl.status="not_used"` 마커 + `dslUnwiredKeys` 존재 (L390-393); 테스트가 고정(아래 H2) | UnifiedRagOrchestrator + `plan/PlanHintApplier.java` + `service/rag/plan/PlanDslLoader.java` | 있음: 04_PLANDSL_CONSUMERS.md, probe S5 | 계약 문서=Devin / DSL 실행기=Codex·승인 |
| Order/policy | RetrievalOrderService + RuleBreak 소비자 | **LIVE(조건부)** — `planDslOrder()`가 `RuleBreakContextHolder.get()` SPEED_FIRST→VECTOR_KG_WEB (L191-201). **단 `retrieval.order.mode` 기본 `fixed`라 L59-61에서 조기 DEFAULT 반환** → 분기는 `adjustFromCfvm`의 mode 플립 후에만 도달 | `strategy/RetrievalOrderService.java` | 있음: 05_FAILURE_ORDER_CALLS.md | mode/정책 값 변경=Codex |
| Gates | Citation/FinalSigmoid/DomainWhitelist/PII canonical | **LIVE** — canonical FQCN 5종 전부 존재 (probe S10). dormant 계보 다수(CitationGate ×7 계보, FinalSigmoid ×5, PII ×7)는 스캔 밖 | `com.example.lms.guard.{CitationGate,FinalSigmoidGate,PiiSanitizer}`, `service.guard.PIISanitizer`, `service.rag.auth.DomainWhitelist` | 있음: 03_GATE_TABLE.md, probe S10 | 게이트 우회 레이어 신설 금지 |
| Failure Pattern | autoconfig + 소비자 | **LIVE** — `NovaFailurePatternAutoConfiguration` @Bean 10종(조건부 포함), `RetrievalOrderFeedbackAspect`가 `decideOrder`를 around + `isCoolingDown("web")` 직접 분기 | `ai/abandonware/nova/autoconfig/NovaFailurePatternAutoConfiguration.java` | 있음: 05_FAILURE_ORDER_CALLS.md | — |
| Zero100 | autoconfig + plan yaml | **LIVE** — `NovaZero100AutoConfiguration` + `plans/zero100.v1.yaml` 존재 | imports L5, plans/zero100.v1.yaml | 있음: probe S3 | — |
| LLM 게이트웨이 | `com.example.lms.llm.*` + LlmRouterAspect | **LIVE** (Abandon_X PARTIAL→문서 클래스명만 stale) | `main/java/com/example/lms/llm/**` | 갭: 라우팅 실확인은 `ollama ls`+`check-model-lock.ps1` (기존 도구) | — |
| Trace | TraceFilter + GuardContextInitFilter | **LIVE** — 두 파일 canonical 존재 | `web/TraceFilter.java`, `web/filter/GuardContextInitFilter.java` | 있음 | — |
| UAW product | autolearn/thumbnail/presence/selfclean | **LIVE·기본 OFF** — `uaw/autolearn/` ~20 클래스, `uaw.thumbnail.enabled`/`uaw.autolearn.enabled` yml 기본 false (L781-805) | `com/example/lms/uaw/**` | 있음: 02_SETUP_ORDER §F "켜는 체크리스트" | 활성화=승인 후, Devin은 플래그 OFF 유지 |

## B. 지금 얹지 말 것 (세트 깨짐 — 관측만)

| 대상 | live 판정 | 근거 | 밑밥 |
| --- | --- | --- | --- |
| RuleBreak 생산자 | **STALE→해소됨 (drift 발견)** | Abandon_X P0-A "미등록"은 09-23 기준. 현재 `WebMvcConfig.addInterceptors`가 `ruleBreakInterceptorProvider.getIfAvailable()` → `/**` 등록 + `[AWX][rulebreak]` 로그 (L62-71). canonical `RuleBreakInterceptor`는 `@Component`+evaluator=admin-token `MessageDigest.isEqual` 비교. 계약 테스트 `WebMvcRuleBreakRegistrationTest` 존재 | probe S6; 재패치 금지 — Codex 소유 완료로 처리 |
| RuleBreak 잔여 계보 | DORMANT×7 + write-only | nova interceptor(NovaRequestContext write-only), nova.protocol WebFilter(서블릿 부팅에서 무력), abandonware/patch/ai/zerobreak/루트 계보 | 활성화 금지 유지; zerobreak nonblank 계보 절대 금지 |
| 설정 이중 선언 | **PARTIAL 지속** | properties `ocr.enabled=true`/`min-confidence=0.78` vs yml `${OCR_ENABLED:false}`/`0.65` — properties 우선. `local-llm.base-url`, `retrieval.vector.enabled`도 양쪽 | probe S8 관측만; 값 수정은 Codex+승인 |
| plans/ 비-v1 별칭 | **지속** | `brave.yaml`, `safe_autorun.yaml`, `zero_break.yaml`이 각 v1과 공존 | probe S7; 로더 선택 규칙 = evidence_needed |
| dormant 루트 | DORMANT | `com/abandonware/ai`, `com/abandonwareai`, `com/abandonware/patch` + 루트 패키지(strategy/service/web/config/guard/trace) 9종 존재 | probe S9; 깨우기 금지 |
| GRANDAS / JamminiMemory.atomic.yaml | ABSENT | 이름으로 클래스/파일 없음(Abandon_X §4) | "만들어야 함"으로 해석 금지 |
| 포트폴리오 수치 | evidence_needed | "속도 3배/손실 1~2%/100%" 미검증 | Done 근거 금지 |

## C. Codex/제품 소유 경계 (Devin은 탐침·문서만)

- `main/java/**`, `main/resources/static/**`, `plans/*.yaml`, `application*.*` 값 — **Codex MAX-PUSH/승인 소유**. 본 세션 diff 0 확인 필수.
- `WebMvcConfig` RuleBreak 등록은 이미 제품에 있음 — 재패치 금지(`사실` 관측).
- maiaswsn F01–F12 / Autograde B04+ / MAX-PUSH Track A-B — 본 작업과 무관, 파일 겹치면 HOLD.

## D. 검증 로그 (WP0)

- `python -B scripts/uaw_spine_probe.py --root . --md docs/diagnostics/uaw-harmony-foundation-0928/probe_summary.md` → exit 0, OK=6 WARN=4 (`probe_summary.md`).
- 수동 확인: LmsApplication L20, build.gradle.kts L931, imports 6행, WebMvcConfig L59-71, RuleBreakEvaluator L81-126, UnifiedRagOrchestrator L390-393, RetrievalOrderService L56-113·L191-201·L297-311, application.properties L714-715, application.yml L193-197·L216-224, application-dev.yml L85-97(ignore 가드: grep 행만), application-prod.yml L77-94(동일).
- NOT_RUN: Gradle 빌드/테스트, 부팅, property-origin 실확인, plans 로더 선택 규칙 실행 확인 — 범위 밖(읽기 전용 WP).
