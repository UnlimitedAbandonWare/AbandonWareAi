# Adaptive main routing and reported OAuth benefit

Verified against the local root source on 2026-10-01: Java 17.0.13, Spring Boot 3.3.4, LangChain4j 1.0.1. Active sourceSets are root main/java and main/resources; :app has empty sourceSets. Historical ZIP line numbers are reference only.

## Routing contract

The served path is ChatApiController → ChatService → ChatWorkflow → PolicyBasedModelRouter → DynamicChatModelFactory → TimedChatModelCaller. Prompt construction stays on PromptBuilder.build(PromptContext); search OFF, retrieval, reranking and evidence release policies remain on their existing owners.

routeMain selects before prompt/context assembly. An existing immutable LlmRouteDecision carries the selected key and admitted output budget. The DTO and RequestedModelSelection retain that decision; the main retry helper receives it explicitly, including when its DTO was cloned earlier. Auxiliary calls do not inherit the main route.

| Request | First main generation |
|---|---|
| Greeting/status/simple fact | Existing direct/light route; existing RAG when selected |
| Complex comparison/reasoning/code analysis without required tools | Exact chatgpt-oauth route when registered and admitted; zero factory retries |
| Explicit model | Exact requested model, including existing strict-selection failure semantics |
| Required tools | Tool-capable existing route; explicit OAuth returns responses_tools_unsupported before resolving a bearer |
| Disabled/invalid OAuth session or missing catalogue model | Existing automatic route |

RouterPolicy consumes router.moe.complexity-threshold (default 0.55) using the existing QueryComplexityGate and a code-analysis signal. The existing legacy MoeRoutingProps escalation remains independent; no score engine or new tier was added. Automatic classifier, judge, Display and rewrite roles remain on existing lightweight paths. The historical explicit selectOauth helper retains its caller-controlled semantics.

main-model is optional and must name a model already in the registered catalogue. With no override, the first valid catalogue entry is used. No installed model or provider capability is inferred from a filename.

The output budget is an admitted planning value, not a proven OAuth provider ceiling. OpenAiEndpointCompatibility preserves the ChatGPT transport body contract: model/input/store=false/stream=true. max_output_tokens and sampling options are OMITTED_BY_CONTRACT. Provider output-cap enforcement and an OAuth context-window specification remain not_observed; no real generation was run.

## Terminal and transition contract

LlmResponseTerminalException.metadata is never null. Unknown usage, model, id and finish reason remain null. Failed/incomplete/cancelled OAuth SSE carries the usage/id/model actually present in that terminal response. Original status, provider code and reason remain failures; partial output is not promoted to a completed answer.

A same-request fallback requires an existing pre-transport local admission reason: local_endpoint_open or local_backend_busy. HTTP errors, timeout, blank output and zero received tokens do not establish non-execution. Uncertain execution is non-replayable. Completed tools, partial output and cancellation keep their existing fences. FinalizedMemoryPersistence and attachment revision/deletion ownership were verified through their existing regression suites.

## Benefit and billing

Jev uses Vercel AI Gateway credits; it is independent of the ChatGPT OAuth plan. The actually loaded root main/resources/application-meta-display.yml keeps Jev mode=off, free-only=false, allow-paid=true defaults.

| Field | Meaning |
|---|---|
| reportedGrantCredits=62500, unit=credits | User-reported allocation input, not observed balance |
| observedRemaining / observedTokens | null until actually observed |
| estimatedCredits / actualDebitedCredits | null; neither tokens nor USD are converted to credits |
| reportedExpiresOn=2026-12-31 | Reported calendar date, timezone unknown |
| expiresAt | null |
| operationalCutoff | Optional separately configured Instant; invalid values suspend this benefit |

An account-ref scopes only the existing ConfigurationSettingRepository record. A provider-confirmed subscription_sharing_usage_limit_exceeded persists exhausted in existing H2 settings. This is a usage-limit observation, not proof of the exact remaining balance or permanent credit exhaustion. Restart does not recreate the reported allocation as a live balance. Missing storage/account evidence means inactive benefit; it does not delete provider registration, session or catalogue. There is no distributed ledger, cross-JVM reservation, automatic charge or refill.

The benefit key uses explicit `chatgpt.oauth.account-ref` first, then the existing synced catalogue's 12-hex `catalog_account_fingerprint`; no new YAML default or store is required.
With neither value, BenefitSnapshot exposes `bindingState=unbound`, `storageObserved=false` and inactive benefit; one value-free WARN is emitted per registration and ordinary OAuth routing remains available.
With `own-account-only=false`, an explicit account-ref wins even if the catalogue differs; with `true`, the existing credential/catalogue/account-ref matching rule still rejects the mismatch.
The product `modelFor` callback captures that key and records provider-confirmed usage-limit terminals from OpenAiResponsesChatModel, so an in-flight response cannot debit a newly selected account.

## Optional owner association

chatgpt.oauth.own-account-only defaults to false, preserving the local single-user behavior and PROTO_OPEN. Outside localhost, this default can permit another requester to consume the globally registered subscription; changing the default is a separate authorized product decision.

When enabled, configure chatgpt.oauth.owner-hash to the server-resolved AttachmentOwnerIdentity hash and chatgpt.oauth.account-ref to the existing 12-hex harness account fingerprint. The request owner is server-bound, ignored on JSON input, and independent of attachment presence. Catalogue projection is request-specific; OAuth entries do not enter the shared server cache.

The current credential-derived fingerprint, catalogue catalog_account_fingerprint and configured account-ref must match. The association is captured at construction and rechecked before dispatch. Replacing only credentials or both files with another account is rejected. The existing harness precedence is decoded id_token subject → refresh token → access token → client id, SHA-256 first 12 hex characters. This is local attribution, not verified JWT signature or provider account ownership. Without a subject, token rotation can safely reject stale attribution until the existing catalogue/configuration is re-associated. No login, refresh or actual credential file was inspected in this task.

These settings are existing Spring properties declared in Java; optional YAML aliases were deferred while another target-scoped writer held the profile. No parallel configuration layer was created.

## Configuration change and rollback

Use existing LlmRouterProperties.ModelConfig, the cloud-model manifest and routing YAML for same-protocol model replacement. The inactive illustration below uses the actual ModelConfig fields and makes no availability/pricing claim; do not activate it without checking the existing provider/model contract.

```yaml
# Documentation only; not imported by the application.
llmrouter:
  models:
    sample-disabled:
      enabled: false
      provider: openai
      name: fixture-model-replacement
      base-url: https://api.openai.com/v1
```

Set chatgpt.oauth.main-routing-enabled=false to roll back automatic main promotion. This preserves explicit OAuth selection, the subscription catalogue, embeddings and H2 rows. The default is true for this feature. Do not reset a DB, clear memories, alter embedding dimensions or disable OAuth registration to perform this rollback.

## Offline acceptance coverage

Existing equivalent tests were extended rather than copied into the attachment's suggested class names.

| Cases | Actual suites |
|---|---|
| WP1 four terminal invariants | ResponsesTerminalStatusContractTest; TimedChatModelCaller tests; DTO/trace consumers |
| T01–T04 configurable model, disabled credentials, tools and billing | ExactModelGatewayTest; OpenAiResponsesChatModelTest; ChatGptResponsesTransportContractTest; ChatGptCatalogIsolationContractTest; ChatGptWorkflowBillingContractTest |
| T05–T11 report/expiry/storage/account separation | RoutingBenefitLifecycleTest (including a real temporary H2 close/reopen) |
| T12–T17 first route/explicit choice/budget/role isolation | AdaptiveRouteDecisionTest; ConversateCueRoutingPolicyTest; ChatGptCueRoutingContractTest |
| Owner isolation and account-file replacement | ChatGptCatalogIsolationContractTest |
| T18–T23 transitions/cancel/once/deleted evidence | FallbackAwareReplaySafetyTest; FallbackAwareChatModelTest; BoundedProviderChainTest; LlmInFlightCancellationTest; LlmRequestLifecycleTest; FinalizedMemoryPersistence tests; AttachmentServiceDurabilityTest |
| T24–T25 embedding/config rollback/retained H2 rows | RoutingConfigurationContinuityTest |

Mock HTTP/SSE, synthetic credentials, fixed Clock and temporary stores provide these proofs. Real OAuth/API generation, balance lookup, UI answer generation, full test suite and hardware proof are NOT_RUN. PlannerNode still creates fixed analysis and verification contexts; the requested sequential plan/tool/verify agent remains HOLD.

The task report contains exact commands, RED failures, final suite counts, runtime verification and scoped diffs under data/agent-handoff/codex-autonomy/oauth-adaptive-r5-1001-9f522366.

Official protocol cross-check, accessed 2026-10-01: [OpenAI streaming event reference](https://developers.openai.com/api/reference/resources/responses/streaming-events/) and [streaming Responses guide](https://developers.openai.com/api/docs/guides/streaming-responses). These confirm typed terminal events; the local verified ChatGPT transport compatibility contract governs omitted options.
