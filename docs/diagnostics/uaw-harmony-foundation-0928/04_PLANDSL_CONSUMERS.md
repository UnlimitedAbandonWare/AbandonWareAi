# 04_PLANDSL_CONSUMERS — planDsl `not_used` 계약 + 실소비자 정적 스캔 (WP1-H2)

- Contract: `DEMO1-DEVIN-UAW-HARMONY-FOUNDATION-20260928` · taskId `uaw-harmony-foundation-0928-c50981a8`
- Date: 2026-09-28 · 정적 스캔만. broad DSL 실행기 활성화는 비목표.
- 결론: Abandon_X §2-D 판정 유효 — broad 키는 metadata-only(`not_used`),
  hint/projection 계열은 실소비자 존재. "전면 미사용"(UAW)과 "완전 실행"(포트폴리오) 둘 다 과장.

## 1. 계약 앵커 (사실)

| 증거 | 위치 |
| --- | --- |
| `planDsl.status="not_used"`, `planDsl.loaded=false`, `planDsl.unwiredKeys` emit | `service/rag/orchestrator/UnifiedRagOrchestrator.java` L390-393 |
| unwiredKeys 수집기 | `com.example.lms.plan.PlanHintApplier.dslUnwiredKeys(plan)` (호출: UnifiedRagOrchestrator L390) |
| 계약 고정 테스트 | `src/test/.../UnifiedRagOrchestratorPlanHintsTest.java` (L61-64,69), `ServicePlanDslLoaderTraceContractTest.java` (L29-49), `nova/PlanDslLoaderTest.java` (L51-77) |

## 2. 실소비자 목록 (정적 호출 확인)

| 소비자 | 무엇을 읽는가 | 호출 위치 |
| --- | --- | --- |
| `PlanHintApplier` (canonical) | plan YAML → `PlanHints`/`PlanExecutionSpec` → GuardContext/hints/meta 적용 | `ChatApiController` L1840-41, L4146-47, L4874-75; `ChatWorkflow` L1478-84, L2143; `PublicRequestBudgetGuard` L631, L865-76; `UnifiedRagOrchestrator` L301-320, L365; `WorkflowOrchestrator` L73 (plan 자동선택) |
| `PlanDslExecutor` (minimal, opaque) | `classpath:/plans/{id}` 존재 확인 수준의 메타 로드 — **실행기 아님**(payload opaque, YAML 파싱 없음) | `UnifiedRagOrchestrator` L253, L293 (`plan.source` dbg) |
| `service/rag/plan/PlanDslLoader` + `PlanPolicyMapper` + `PlanModelResolver` | `projection_agent.v1.yaml` 프로젝션 플랜 (profile/model/token 경계) | `ChatWorkflow` L399-407 주입; 테스트 6종(ChatWorkflowProjection*Test)이 분기/기본값 고정 |
| `UawThumbnailPlanLoader` | `UAW_thumbnail.v1.yaml` 썸네일 플랜 | `uaw/thumbnail/UawThumbnailPlanLoader.java`, 소비 `UawThumbnailService` L39-49 |
| `nova/PlanDslLoader` (`novaPlanDslLoader`) | nova 계열 플랜 로더 — 명시 빈명으로 충돌 회피 | `com/example/lms/nova/PlanDslLoader.java`, `PlanDslLoaderTest` |
| `strategy/plan/PlanDslLoader` | 루트 패키지 — **dormant**(스캔 밖) | `main/java/strategy/plan/PlanDslLoader.java` |

## 3. 비소비자 (이름 오인 금지)

| 이름 | 실체 |
| --- | --- |
| `RetrievalOrderService.planDslOrder()` | DSL 미독 — `RuleBreakContextHolder` SPEED_FIRST 분기일 뿐 (L191-201). plan-DSL 증거로 인용 금지 |
| `plans/*.yaml`의 `plan.pipeline`/`fusion`/`llm` broad 키 | `dslUnwiredKeys`로 수집만, 실행기 없음 → metadata-only |
| `plans/projection_agent.v1.yaml` | DSL 아님 — 프로젝션 정책 파일(소비자 2번 표 참조) |

## 4. 조화 계약 (밑밥 준수 사항)

- `planDsl.status=not_used`를 깨는 broad 실행기/새 DSL 파이프는 Codex+승인 영역.
- hint/projection 키는 실소비자가 있으므로 "DSL 전면 dead"로 오보하지 말 것.
- 계약 테스트 재실행은 focused만: `gradlew.bat test --tests UnifiedRagOrchestratorPlanHintsTest --tests ServicePlanDslLoaderTraceContractTest` (본 세션 미실행 — Codex lease 비침범 원칙).
