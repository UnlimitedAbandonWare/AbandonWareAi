# demo-1 Orchestration Feature Expansion Plan

Generated: 2026-06-03 Asia/Seoul
Workspace: `C:\AbandonWare\demo-1\demo-1\src`

이 문서는 `demo-1` Dynamic RAG Orchestration Platform에 Brave / Zero100 /
RuleBreak / safe_autorun 같은 실행 모드를 증설하기 전, 충돌을 막기 위한 기준서와
로드맵이다.

목표는 즉시 기능을 늘리는 것이 아니라, Plan DSL + feature flag + Safe Patch
방식으로 어떤 기능을 어느 레이어에 붙일지 먼저 고정하는 것이다. 기존 런타임
규칙을 우회하지 않는다.

## 0. Decomposition Decision

```text
Decomposition decision:
- mode: 3-way
- reason: Plan DSL, WorkflowOrchestrator/UnifiedRagOrchestrator, provider/gate/trace 계층이 서로 영향을 주므로 owner와 우선순위를 분리해야 한다.
- axes:
  1. Definition/domain: 실행 모드, Plan DSL, feature flag, 게이트 contract
  2. Alias/path/variant: safe_autorun, safe.v1, Brave, Zero100, RuleBreak, zero_break, hyper_nova
  3. Relation/failure: PromptBuilder/CitationGate/FinalSigmoidGate/Naver bridge/provider fail-soft와의 충돌
```

## 1. Current Evidence Baseline

현재 repo에서 확인된 기준 경계:

- canonical Desktop root: `C:\AbandonWare\demo-1\demo-1\src`
- root runtime sourceSet: `main/java`, `main/resources`
- `:app` sourceSet: `app/src/main/java_clean`, `app/src/main/resources`
- Plan hint owner:
  - `main/java/com/example/lms/plan/PlanHintApplier.java`
  - `main/java/com/example/lms/plan/PlanHints.java`
  - `main/resources/plans/*.yaml`
- RAG runtime owner:
  - `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`
  - `main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java`
  - `main/java/com/example/lms/service/rag/handler/OrchestrationGate.java`
  - `main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java`
- Provider owner:
  - `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java`
  - `main/java/com/example/lms/service/NaverSearchService.java`
  - `main/java/com/example/lms/service/web/BraveSearchService.java`
  - `main/java/com/example/lms/service/search/NaverCredentialBridge.java`
- Gate / prompt owner:
  - `main/java/com/example/lms/prompt/PromptBuilder.java`
  - `main/java/com/example/lms/prompt/PromptContext.java`
  - `main/java/com/example/lms/guard/CitationGate.java`
  - `main/java/com/example/lms/guard/FinalSigmoidGate.java`
  - `main/java/com/example/lms/service/guard/EvidenceAwareGuard.java`
- Trace owner:
  - `main/java/com/example/lms/search/TraceStore.java`
  - `main/java/com/example/lms/debug/DebugEventStore.java`

`main/java/config`, `main/java/web`, `main/java/planner`, `main/java/com/nova/protocol`
같은 legacy/protocol-looking paths는 caller/sourceSet/bean evidence가 없으면
새 증설 owner로 삼지 않는다.

## 2. Non-Negotiable Preservation Rules

증설 작업자는 아래를 바꾸지 않는다.

- 최종 RAG/chat prompt는 `PromptBuilder.build(PromptContext)` 또는 기존 동등
  boundary만 사용한다.
- Plan DSL은 prompt 문자열을 직접 조립하지 않는다.
- `CitationGate`, `FinalSigmoidGate`, Evidence gate는 RuleBreak/Brave/Zero100에서도
  최종 게이트로 남는다.
- Naver credential flow는 `naver.keys` -> `NAVER_KEYS` -> `naver.client-id` +
  `naver.client-secret` bridge를 유지한다.
- Plan YAML이나 trace에 raw API key, client secret, owner token, Authorization,
  cookie, raw full prompt, raw sensitive query를 넣지 않는다.
- `dev.langchain4j:*`는 전부 `1.0.1`이어야 한다.
- `openssl` / `opnessl` key, name, value, format은 변경하지 않는다.
- 새 모드는 기본 OFF 또는 allowlisted plan으로만 켠다.
- missing optional provider credential은 provider-disabled/fail-soft이며 outbound call을
  하지 않는다.
- Mac mini 결과는 PatchDrop evidence이고, Desktop canonical root에서 최종 검증해야 한다.

## 3. Layer Owner Map

| Layer | Owner | Allowed expansion | Forbidden expansion |
|---|---|---|---|
| Plan files | `main/resources/plans/*.yaml` | 새 plan id, budget/topK/gate/pass-through knob | secret key, raw prompt, provider credential |
| Plan parsing | `PlanHintApplier`, `PlanHints` | schema allowlist, alias normalization, feature-flag-aware fallback | second parser, duplicate PlanLoader owner |
| Mode selection | future `com.example.lms.plan.OrchestrationModeSelector` | pure decision: requested mode -> selected plan id + reason | provider call, prompt build, direct retrieval mutation |
| Request projection | `UnifiedRagOrchestrator.QueryRequest`, `OrchestrationHints` | allowWeb/allowRag/topK/selfAsk/onnx flags | bypassing `PlanHintApplier` |
| Retrieval chain | `DynamicRetrievalHandlerChain`, `OrchestrationGate` | honor hints, skip/degrade with trace | hard fail on optional stage |
| Self-Ask/Zero100 | `SelfAskWebSearchRetriever`, `ai.abandonware.nova.orch.zero100.*` | lane budgets, rollover, query burst under caps | unbounded fanout, raw query trace |
| Provider | `HybridWebSearchProvider`, `NaverSearchService`, `BraveSearchService` | disabledReason, timeout/rate-limit classification | fake empty success, credential logging |
| Gate | `CitationGate`, `FinalSigmoidGate`, `EvidenceAwareGuard` | stricter thresholds by plan | disabling final gates from plan |
| Prompt | `PromptBuilder`, `PromptContext` | context fields consumed by builder | final prompt concatenation outside builder |
| Observability | `TraceStore`, `DebugEventStore`, SSE DTOs | redacted reason/count/hash keys | raw secret/query/prompt/log dump |

## 4. Mode Selection Priority

Mode selection must be deterministic. The stricter rule wins.

1. **Safety override**
   - If plan id is malformed, schema invalid, secret-like key is present, or feature flag is
     disabled, select `safe.v1` or `safe_autorun.v1`.
   - Trace: `plan.selection.fallback.reason`.
2. **Admin-only RuleBreak**
   - Allowed only when all are true:
     - `orchestration.mode.rulebreak.enabled=true`
     - authenticated/admin request path or valid existing RuleBreak token contract
     - audit logging enabled
   - RuleBreak may raise budget/topK or relax whitelist profile only within configured caps.
   - It must not disable PromptBuilder, CitationGate, FinalSigmoidGate, redaction, or provider credential guards.
3. **Explicit request plan**
   - Request `planId` or mode header may choose an allowlisted plan.
   - Normalize aliases through `PlanHintApplier.normalizePlanId` semantics: `brave` -> `brave.v1`,
     `zero100` -> `zero100.v1`, `autorun` -> `safe_autorun.v1`, `rule` -> `rulebreak.v1`.
4. **Failure-driven downgrade**
   - timeout, rate-limit, provider-disabled, aux-down, breaker-open, or budget exhaustion can
     downgrade to `safe.v1`, `ap9_cost_saver.v1`, `kg_first.v1`, or cache/vector-only behavior.
   - No downgrade may hide the failure class.
5. **Query-analysis-driven upgrade**
   - entity-heavy: prefer `kg_first.v1`
   - recency-heavy: prefer `recency_first.v1`
   - low initial recall with safe provider state: consider `brave.v1`
   - long-running multi-lane research with explicit flag: consider `zero100.v1`
6. **Default**
   - `safe_autorun.v1` for normal runtime unless repo evidence changes the default.

## 5. Feature Flag Contract

Add new flags only under a small, namespaced surface. Defaults are conservative.

```yaml
orchestration:
  mode-selector:
    enabled: false
    default-plan-id: safe_autorun.v1
    allowed-plan-ids:
      - safe.v1
      - safe_autorun.v1
      - kg_first.v1
      - recency_first.v1
      - brave.v1
      - zero100.v1
    brave-enabled: false
    zero100-enabled: false
    rulebreak-enabled: false
    max-web-top-k: 20
    max-vector-top-k: 20
    max-kg-top-k: 30
    max-query-burst-count: 12
    max-zero100-minutes: 100
```

Rules:

- Runtime behavior must be the intersection of feature flag, plan YAML, provider state, and
  request context.
- A plan cannot enable a feature that the global feature flag disables.
- A request cannot enable a plan id outside `allowed-plan-ids`.
- RuleBreak is never enabled by alias matching alone.
- Rollback is always possible by setting `orchestration.mode-selector.enabled=false` and
  defaulting to `safe_autorun.v1`.

## 6. Plan DSL Contract

Plan YAML may control only these classes of values:

- retrieval caps: `allowWeb`, `allowRag`, `retrieval.order`, `webTopK`, `vecTopK`, `kgTopK`,
  `k_schedule`
- budget caps: `webBudgetMs`, `vecBudgetMs`, `zero100.*` timebox knobs
- rerank knobs: `onnx.enabled`, `use_cross_encoder`, `rerank.backend`, `rerank.topK`,
  `rerank.ce.topK`
- expansion knobs: `expand.selfAsk.count`, `expand.queryBurst.count`, `extremeZ.enabled`,
  `overdrive.enabled`
- gate minimums: `gates.citationMin`, `guards.whitelist_profile`
- observability knobs: redacted trace/debug enablement only

Plan YAML must not control:

- API keys, client secrets, owner/admin tokens, cookies, Authorization headers
- final prompt strings
- raw evidence snippets
- LangChain4j versions
- `openssl` / `opnessl` keys
- direct Java class names for dynamic loading
- filesystem write paths outside existing diagnostic/report roots

## 7. Trace Key Registry

Use allowlisted, redacted keys. Prefer boolean, enum, count, hash, timing, and reason code.

### Selection

```text
plan.requested
plan.normalized
plan.selected
plan.source
plan.applied
plan.disabledReason
plan.selection.reason
plan.selection.priority
plan.selection.fallback.reason
plan.featureFlag.modeSelector.enabled
plan.featureFlag.<mode>.enabled
plan.allowed
plan.schema.ok
plan.schema.invalid.last
```

### Projection

```text
plan.allowWeb
plan.allowRag
plan.webTopK
plan.vectorTopK
plan.kgTopK
plan.selfAsk.enabled
plan.onnx.enabled
plan.officialOnly
plan.minCitations
plan.retrievalOrder
plan.kSchedule
```

### Execution

```text
orchestration.mode
orchestration.mode.reason
orchestration.mode.rollbackPath
retrieval.order.override
selfask.planOverride.enabled
selfask.planOverride.reason
selfask.branch3.seedCount
zero100.enabled
zero100.activeLane
zero100.rollover.used
zero100.rollover.reason
web.<provider>.skipped.reason
web.<provider>.disabledReason
web.<provider>.returnedCount
web.<provider>.afterFilterCount
web.<provider>.timeout
web.<provider>.rateLimited
gate.citation.min
gate.citation.result
gate.finalSigmoid.mode
gate.finalSigmoid.result
prompt.builder.used
```

Forbidden trace values:

```text
raw API key
raw client secret
ownerToken
Authorization header
cookie
raw full prompt
raw full query
full environment dump
full external error body
```

## 8. Rollback Ladder

Every expansion patch must have a no-code rollback first.

1. `orchestration.mode-selector.enabled=false`
2. remove or narrow the plan id from `orchestration.mode-selector.allowed-plan-ids`
3. set default plan to `safe_autorun.v1`
4. set mode-specific flag false, e.g. `orchestration.mode-selector.zero100-enabled=false`
5. disable only the failing passthrough knob, e.g. `extremeZ.enabled=false`
6. revert the smallest source patch
7. move failed PatchDrop bundle to `__patch_drop__/rejected/` with reason

Do not roll back by deleting PromptBuilder, provider bridge, final gates, or trace redaction.

## 9. Expansion Roadmap

### Phase 0 - Inventory and Baseline

Owner: Desktop Codex.

Actions:

- Run sourceSet and Gradle proof.
- Inventory all plan ids under `main/resources/plans`.
- Produce current trace key snapshot from focused unit/probe tests.
- Confirm PatchDrop queue before editing.

Verification:

```powershell
.\gradlew.bat projects --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache
rg -n "PlanHintApplier|PlanHints|safe_autorun|brave.v1|zero100.v1|rulebreak.v1|PromptBuilder|CitationGate|FinalSigmoidGate|NaverCredentialBridge" main/java main/resources src/test/java
```

Rollback: no source change.

### Phase 1 - Plan DSL Contract Tests

Owner: `com.example.lms.plan`.

Patch target:

- `src/test/java/com/example/lms/plan/*`
- only if needed: `PlanHintApplier`, `PlanHints`

Acceptance:

- malformed plan id falls back safely
- secret-like keys in plan YAML are rejected
- alias mapping is deterministic
- `allowWeb=false` removes web from retrieval order
- `rulebreak.v1` is not selectable without feature/admin gate in later phases

Rollback: remove test-only additions or revert `PlanHintApplier` patch.

### Phase 2 - Pure Mode Selector

Owner: new narrow class under `main/java/com/example/lms/plan/` or
`main/java/com/example/lms/orchestration/`.

Design:

- Input: requested plan id/mode, query-analysis summary, provider status summary, feature flags.
- Output: selected plan id, reason code, priority, rollback path.
- No provider call.
- No prompt building.
- No mutation of `QueryRequest`.

Suggested API:

```java
record OrchestrationModeDecision(
        String requestedPlanId,
        String selectedPlanId,
        String reason,
        int priority,
        boolean fallback,
        String disabledReason
) {}
```

Acceptance:

- default returns `safe_autorun.v1`
- disabled Brave request falls back with reason `feature_flag_disabled`
- invalid plan falls back with reason `invalid_plan`
- RuleBreak without admin/token falls back with reason `admin_gate_required`

Rollback: disable selector flag; keep existing request `planId` path.

### Phase 3 - Projection Into Existing Runtime

Owner: `UnifiedRagOrchestrator` + `PlanHintApplier`.

Patch rule:

- Call selector before `applyPlanHints`, then feed only selected plan id into existing
  `PlanHintApplier`.
- Keep `applyPlanHints` as the only place that mutates `QueryRequest` from Plan DSL.
- Add trace keys from the registry.

Acceptance:

- existing `safe_autorun.v1` behavior stays unchanged when selector flag is false.
- selected plan is visible in `resp.debug` and `TraceStore`.
- plan load failure still fails soft to safe fallback.

Rollback: selector flag false.

### Phase 4 - Brave / Zero100 Controlled Enablement

Owner:

- Brave: provider layer + `brave.v1` plan
- Zero100: `SelfAskWebSearchRetriever`, `ai.abandonware.nova.orch.zero100.*`,
  `zero100.v1` plan

Patch rule:

- Brave and Zero100 are recall expansion modes, not safety bypasses.
- They may raise topK/queryBurst within feature-flag caps.
- They must preserve provider fail-soft, timeouts, cancellation hygiene, and final gates.

Acceptance:

- missing Brave key: `provider-disabled`, no outbound call, safe fallback remains useful.
- zero100 budget exhaustion: trace reason and continue/degrade, not blank answer.
- `outCount=0` includes starvation/fallback reason.

Rollback:

- `orchestration.mode-selector.brave-enabled=false`
- `orchestration.mode-selector.zero100-enabled=false`
- or remove the specific plan id from allowed list.

### Phase 5 - RuleBreak Admin-Only Mode

Owner: request/security filter + gate/trace layer.

Patch rule:

- RuleBreak is an audited override, not a free mode.
- It must require feature flag and admin/token evidence.
- It cannot turn off CitationGate, FinalSigmoidGate, PromptBuilder, redaction, or provider
  credential checks.

Acceptance:

- without token/admin: `admin_gate_required`, selected safe fallback.
- with token/admin and flag true: selected `rulebreak.v1`, trace audit key present.
- final gate trace still present.

Rollback:

- `orchestration.mode-selector.rulebreak-enabled=false`
- rotate or blank RuleBreak token without editing source.

### Phase 6 - KPI and PatchDrop Handoff

Owner: Desktop final verifier; Mac mini may produce PatchDrop evidence only.

Required before/after KPIs:

- plan selection count by mode
- fallback count by reason
- provider disabled/rate-limit/timeout counts
- outCount and afterFilterCount distribution
- citation gate pass/warn/block
- final sigmoid pass/warn/degrade/block
- zero100 lane rollover count
- prompt builder usage assertion count

Desktop verification order:

```powershell
.\gradlew.bat projects --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat test --tests '*Plan*' --tests '*Orchestration*' --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $ProjectCache
```

## 10. Patch Candidate Board

| Priority | Candidate | Files | Why | Rollback |
|---|---|---|---|---|
| P0 | Plan DSL contract tests | `src/test/java/com/example/lms/plan/*` | protects current PlanHintApplier behavior before expansion | remove tests/revert source |
| P0 | Mode selector properties | new `OrchestrationModeSelectorProperties` | all new modes default OFF | flag false |
| P1 | Pure mode selector | new selector class + tests | deterministic plan choice without runtime mutation | flag false |
| P1 | Trace projection | `UnifiedRagOrchestrator` narrow trace additions | makes selection auditable | flag false/revert |
| P1 | Brave/Zero100 allowlist rollout | `main/resources/plans/*.yaml`, selector tests | controlled recall expansion | remove allowed id |
| P2 | RuleBreak admin gate hardening | existing filter/security seam only | admin override clarity | flag false/token blank |

## 11. Mac mini Directive Template

When sending this to Mac mini, use PatchDrop producer mode only:

```text
Work in a Mac-owned worktree only. Do not edit the Desktop canonical root.

Task:
- Inspect current Plan DSL and mode selection seams.
- Propose one Safe Patch bundle for Phase 1 or Phase 2 only.
- Preserve PromptBuilder.build(PromptContext), CitationGate, FinalSigmoidGate, NaverCredentialBridge, LangChain4j 1.0.1, feature flags default OFF, and redacted TraceStore keys.
- Submit exactly one PatchDrop v3 bundle with .patch, .report.md, .verify.log, .sha256.txt, and manifest.

Do not implement Brave/Zero100/RuleBreak runtime changes until Phase 1 tests pass on Desktop.
```

## 12. Completion Criteria

The expansion plan is ready for implementation only when:

- owner map is accepted
- feature flag defaults are accepted
- Plan DSL priority order is accepted
- trace key registry is accepted
- rollback ladder is accepted
- Phase 1 test-first patch is selected as the next single change

If any item is disputed, do not patch runtime code. Revise this plan first.
