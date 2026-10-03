# 05_FAILURE_ORDER_CALLS — Failure Pattern ↔ RetrievalOrder 연결 탐침 (WP1-H4)

- Contract: `DEMO1-DEVIN-UAW-HARMONY-FOUNDATION-20260928` · taskId `uaw-harmony-foundation-0928-c50981a8`
- Date: 2026-09-28 · 정적 호출 그래프 요약. 제품 패치 0.

## 1. 생산 측: NovaFailurePatternAutoConfiguration (imports 등록, LIVE)

`ai.abandonware.nova.autoconfig.NovaFailurePatternAutoConfiguration` — 조건
`${nova.orch.enabled:true} && ${nova.orch.failure.enabled:true}` (둘 다 기본 true):

| @Bean | 조건 | 역할 |
| --- | --- | --- |
| `failurePatternDetector` | — | 로그 패턴 감지 |
| `failurePatternCooldownRegistry` | — | 소스별 cooldown 상태 |
| `failurePatternMetrics` | — | Micrometer 카운터 |
| `failurePatternJsonlWriter` | — | JSONL 지속화 |
| `failurePatternOrchestrator` | — | 중앙 조정자(DebugEventStore 연동) |
| `failurePatternLogAppenderInstaller` | `nova.orch.failure.log-appender-enabled` (기본 true) | 기존 로그를 관측 입력으로 연결 |
| `policyAdjuster` | `nova.orch.failure.feedback.enabled` (기본 true) | cooldown → 순서 조정 정책 |
| `retrievalOrderFeedbackAspect` | `nova.orch.failure.feedback.enabled` (기본 true) | `decideOrder` 결과를 soft reorder |
| `novaCircuitBreakerRegistryEventConsumer` | `resilience4j-events.enabled=true` (기본 **false**) | R4j OPEN 이벤트 카운터 — 옵션 |
| `failurePatternCooldownDiagnosticsAspect` | `cooldown-diagnostics.enabled` (기본 true) | cooldown 진단 |

## 2. 소비 측: RetrievalOrderService.decideOrder 체인 (canonical `com.example.lms.strategy`)

진입 `decideOrder(queryText)` (L56-113) 순서:

```
probeDbContext()                        # AgentDbContextProvider vector 격리 비율 관측
mode = retrieval.order.mode (기본 "fixed")
  → blank/fixed 이면 즉시 publishOrder("DEFAULT", DEFAULT_ORDER) 반환  ★게이트
planDslOrder()      # RuleBreakContextHolder.get() → SPEED_FIRST → VECTOR_KG_WEB ("PLAN_DSL")
guardOrder()        # GuardContextHolder rate-limit/strike/compression/bypass → VECTOR_KG_WEB
                    #   officialOnly → DEFAULT_ORDER
searchRecoveryOrder() # TraceStore failpattern.searchRecovery.source∈{web,tavily} → VECTOR_KG_WEB ("CFVM_FAILURE_PATTERN")
failurePatternOrchestrator()            # ObjectProvider<FailurePatternOrchestrator>
  → isCoolingDown("web") → VECTOR_KG_WEB ("CFVM_FAILURE_PATTERN")
strategyOrder()     # StrategySelectorService(MoE) → VECTOR_FIRST/KG,VECTOR,WEB/DEFAULT ("MoE")
heuristicOrder()    # 길이≥120 → VECTOR_FIRST; factoid(what/who/…/짧은 ?) → KG_FIRST; else DEFAULT
```

`publishOrder`는 `retrievalOrder.lastSetBy/lastOrder/setCount/conflictDetected` 트레이스를 남긴다.

## 3. 두 개의 피드백 고리 (사실)

1. **직접 분기**: `isCoolingDown("web")` → `VECTOR_KG_WEB` (L92-99).
   `FailurePatternOrchestrator`는 `ObjectProvider` 경유 선택 주입 (L48-49, L297-311) —
   autoconfig가 꺼져도 서비스는 부팅됨.
2. **AOP soft reorder**: `RetrievalOrderFeedbackAspect` (`@Around("execution(List *..RetrievalOrderService.decideOrder(..))")`, Order=HIGHEST_PRECEDENCE+115) →
   `PolicyAdjuster.adjustOrder(list)`가 반환 리스트를 재배열. 코어 서비스 무수정 설계.

## 4. 게이팅 뉘앙스 (이번 세션 발견 — Abandon_X 미기재)

- `retrieval.order.mode`는 **어떤 yml/properties에도 선언이 없다** → 기본 `fixed` 적용,
  decideOrder가 L59-61에서 조기 반환. 즉 위 분기 체인(2·3·4·5·6 단계)은
  `adjustFromCfvm()`이 `mode`를 `"vector-first"|"kg-first"`로 플립한 **이후 요청**에서만 도달 가능 (L171-179).
- 결론: Failure→Order 연결은 코드상 LIVE이나, 런타임 발화는 CFVM 조정 이벤트 선행이 조건.
  "고리가 항상 동작" / "고리가 없다" 둘 다 부정확 → `PARTIAL(조건부 LIVE)`로 기록.
- 검증 후보(focused, 미실행): `RetrievalOrderServiceTest` (존재, L145 planDsl 관련),
  `WebMvcRuleBreakRegistrationTest`. 런타임 발화 증거는 `retrievalOrder.*` TraceStore 키 관측 필요 — `evidence_needed`.
