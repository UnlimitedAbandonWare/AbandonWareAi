# m21222ain 소스 근거 발췌
확인일: 2026-09-24 KST

이 문서는 업로드 ZIP의 실제 파일을 줄 번호와 함께 발췌한 정적 분석 근거다. 운영 소스를 수정하거나 앱을 실행한 결과가 아니다.
줄 번호는 이 ZIP 전용이다. Codex는 현재 C-root에서 메서드/필드 앵커와 SHA를 재확인하고 적용해야 한다.

ZIP SHA-256: `dbd2a4fa7b65ce6e38d7cb4ec25751167d2c4c06c570b310e613f29d2a129c3d`
ZIP 엔트리 3014개 / 파일 2371개 / 최상위 `main/`.
테스트·Gradle wrapper·build 설정은 이 ZIP에 없다. 이전 첨부의 테스트 통과 수치는 독립 검증하지 않았다.

## 근거 목록

| ID | 확인 대상 | 파일 | 범위 |
|---|---|---|---|
| S01 | 이미 반영된 chat/embedding warmup 설정 | `main/resources/application-local-llm.yml` | 19–30 |
| S02 | 이미 반영된 embedding endpoint warmup | `main/java/com/example/lms/config/LocalLlmProcessManager.java` | 882–940 |
| S03 | 복구된 configuredModelId | `main/java/com/example/lms/llm/DynamicChatModelFactory.java` | 452–490 |
| S04 | evidenceReleaseRequired는 일반 출처 정책 전체가 아님 | `main/java/com/example/lms/service/ChatWorkflow.java` | 1346–1371 |
| S05 | 명시적 evidence_needed 응답 지시 파서 | `main/java/com/example/lms/service/EvidenceNeededDirectivePolicy.java` | 1–12, 74–116 |
| S06 | retrieval contract와 evidence 상태 도출 | `main/java/com/example/lms/service/ChatWorkflow.java` | 7360–7463 |
| S07 | 최종 공개 정책: metadata incomplete 무조건 HOLD | `main/java/com/example/lms/service/ChatWorkflow.java` | 7516–7549, 7556–7584, 7628–7637 |
| S08 | PromotionResult의 CONFIRMED_EMPTY는 검증 탈락도 포함 | `main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java` | 55–91, 115–122, 223–241, 256–286 |
| S09 | 짧은 답변의 선택적 확장 호출부 | `main/java/com/example/lms/service/ChatWorkflow.java` | 928–938, 4005–4023 |
| S10 | 확장기의 입력·거부·숫자 검사 | `main/java/com/example/lms/service/answer/AnswerExpanderService.java` | 51–57, 119–132, 186–230, 272–294 |
| S11 | 캐시된 GPU 측정과 전체 GPU 최대 메모리 기반 admission | `main/java/com/example/lms/health/GpuHardwareDiagnostics.java` | 25–43, 74–105, 183–213, 234–262 |
| S12 | GPU admission을 호출하는 기존 rerank gate | `main/java/com/example/lms/service/rag/rerank/RerankGate.java` | 78–99, 183–204 |
| S13 | ONNX에 한정된 semaphore aspect | `main/java/com/example/lms/resilience/SemaphoreGateAspect.java` | 18–73 |
| S14 | 기존 동일 embedding-space 보호 | `main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java` | 636–662 |
| S15 | 포트 추측 후보가 명시 fallback보다 앞서는 경로 | `main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java` | 1583–1615 |
| S16 | 주/보조 endpoint 기본값과 이미 켜진 gateway 설정 | `main/resources/application-llm.yaml` | 10–17, 22–56 |
| S17 | desktop profile의 GPU admission 및 역할 설정 | `main/resources/application-desktop-gpu-node.yml` | 37–64, 66–98, 120–135 |
| S18 | 기존 preferred/strict/auto와 검색 UI | `main/resources/templates/chat-ui.html` | 269–277, 296–305 |
| S19 | 실제 모델 선택/검색 요청 직렬화 | `main/resources/static/js/chat.js` | 6420–6460 |
| S20 | 요청 DTO의 기존 strict/polish/useAdaptive 필드 | `main/java/com/example/lms/dto/ChatRequestDto.java` | 58–68, 132–170, 491–503 |
| S21 | raw intent를 보존하는 설정 병합 | `main/java/com/example/lms/api/ChatRequestSettingsMerger.java` | 84–124 |
| S22 | 답변 cache key와 제한된 캐시 적용 범위 | `main/java/com/example/lms/service/ChatService.java` | 57–140 |
| S23 | SettingsController의 공개 allowlist 및 범용 map 저장 | `main/java/com/example/lms/api/SettingsController.java` | 15–42, 53–74 |
| S24 | KG handler의 성공/빈 결과/실패 관측과 fail-soft | `main/java/com/example/lms/service/rag/handler/KnowledgeGraphRetrievalHandler.java` | 29–60, 74–82 |
| S25 | 기존 generation fallback 시도·예산 경계 | `main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java` | 208–281, 284–309 |
| S26 | 기존 요청 cancellation/interruptibleCall | `main/java/com/example/lms/service/chat/ChatRunExecutionContext.java` | 43–102, 126–156 |
| S27 | 실제 구현이 아닌 이름만 있는 파일 | `main/java/com/example/lms/service/RagRetrievalService.java` | 1–1 |
| S28 | 기존 로컬 BM25 점수기 | `main/java/com/example/lms/service/rag/retriever/LocalBm25Retriever.java` | 1–82 |

## S01 — 이미 반영된 chat/embedding warmup 설정

파일: `main/resources/application-local-llm.yml`
SHA-256: `05e47bf5977559a14070c1d2c5a6d062d413d4ce261b041a9a85d571d269be38`

```text
   19 |   health-check-interval: ${LOCAL_LLM_HEALTH_CHECK_INTERVAL:1s}
   20 |   health-check-attempt-timeout: ${LOCAL_LLM_HEALTH_CHECK_ATTEMPT_TIMEOUT:2s}
   21 |   fail-fast: ${LOCAL_LLM_FAIL_FAST:true}
   22 |   warmup:
   23 |     enabled: ${LOCAL_LLM_WARMUP_ENABLED:true}
   24 |     pull: ${LOCAL_LLM_WARMUP_PULL:false}
   25 |     show: ${LOCAL_LLM_WARMUP_SHOW:true}
   26 |     embed: ${LOCAL_LLM_WARMUP_EMBED:true}
   27 |     model: ${LOCAL_LLM_WARMUP_MODEL:${llm.chat-model:gemma4:26b}}
   28 |     embed-model: ${LOCAL_LLM_WARMUP_EMBED_MODEL:${embedding.model:qwen3-embedding:4b}}
   29 |     dimensions: ${LOCAL_LLM_WARMUP_DIMENSIONS:${embedding.dimensions:1536}}
   30 |     keep-alive: ${LOCAL_LLM_WARMUP_KEEP_ALIVE:5m}
```


## S02 — 이미 반영된 embedding endpoint warmup

파일: `main/java/com/example/lms/config/LocalLlmProcessManager.java`
SHA-256: `2ff70f5700f0b184685a9ae0818b5208631bf9f331fd4951c66cef8212f66057`

```text
  882 |             // Chat readiness is the managed-route gate and always uses the chat model.
  883 |             {
  884 |                 Map<String, Object> body = new LinkedHashMap<>();
  885 |                 body.put("model", model);
  886 |                 body.put("messages", List.of(Map.of("role", "user", "content", "ping")));
  887 |                 body.put("stream", false);
  888 |                 body.put("options", Map.of("num_predict", 1));
  889 |                 if (hasText(warmupKeepAlive)) body.put("keep_alive", warmupKeepAlive);
  890 |                 JsonNode response = postJson("/api/chat", body, warmupTimeoutMs);
  891 |                 if (!response.path("done").asBoolean() || !response.path("message").isObject()) {
  892 |                     throw new IOException("MODEL_LOAD_FAILED");
  893 |                 }
  894 |                 traceWarmup("ok", 0, "chat");
  895 |             }
  896 |             embedVerified = false;
  897 |             if (embedWarmupApplies()) {
  898 |                 String embedModel = trimToNull(warmupEmbedModel);
  899 |                 if (embedModel == null) {
  900 |                     warmupReason = "embed_model_missing";
  901 |                     log.warn("[AWX][ollama][warmup] step=embed status=skipped reason=embed_model_missing");
  902 |                 } else {
  903 |                     try {
  904 |                         Map<String, Object> body = new LinkedHashMap<>();
  905 |                         body.put("model", embedModel);
  906 |                         body.put("input", "ping");
  907 |                         if (warmupDimensions > 0) {
  908 |                             body.put("dimensions", warmupDimensions);
  909 |                         }
  910 |                         String keepAlive = trimToNull(warmupKeepAlive);
  911 |                         if (keepAlive != null) {
  912 |                             body.put("keep_alive", keepAlive);
  913 |                         }
  914 |                         // Use the embedding route; explicit local warmup under a foreign
  915 |                         // provider still targets this managed server.
  916 |                         String embedUrl = "ollama".equalsIgnoreCase(trimToNull(embeddingProvider))
  917 |                                 ? envString("embedding.base-url", ollamaBaseUrl() + "/api/embed")
  918 |                                 : ollamaBaseUrl() + "/api/embed";
  919 |                         JsonNode root = startupRuntime.postJson(
  920 |                                 remapManagedServiceUrl(embedUrl), body, warmupTimeoutMs);
  921 |                         int dim = embeddingDimension(root);
  922 |                         warmupReturnedDim = Math.max(0, dim);
  923 |                         if (dim <= 0) {
  924 |                             warmupReason = "embedding_empty";
  925 |                             log.warn("[AWX][ollama][warmup] step=embed status=failed reason=embedding_empty");
  926 |                         } else if (warmupDimensions > 0 && dim != warmupDimensions) {
  927 |                             warmupReason = "embedding_dimension_mismatch";
  928 |                             log.warn("[AWX][ollama][warmup] step=embed status=failed reason=embedding_dimension_mismatch targetDim={} returnedDim={}",
  929 |                                     warmupDimensions, dim);
  930 |                         } else {
  931 |                             embedVerified = true;
  932 |                             traceWarmup("ok", dim, "embed");
  933 |                             log.info("[AWX][ollama][warmup] step=embed status=ok modelHash={} modelLength={} targetDim={} returnedDim={}",
  934 |                                     SafeRedactor.hashValue(embedModel), embedModel.length(), warmupDimensions, dim);
  935 |                         }
  936 |                     } catch (Exception embedFailure) {
  937 |                         warmupReason = "embed_request_failed";
  938 |                         traceSuppressed("ollama.warmup.embed", embedFailure);
  939 |                     }
  940 |                 }
```


## S03 — 복구된 configuredModelId

파일: `main/java/com/example/lms/llm/DynamicChatModelFactory.java`
SHA-256: `63b856dfffcf0705a8ed241d05d3e638cefabcb16e891861e49ec116f637578b`

```text
  452 |             if (local && !sharedLocalFailover) {
  453 |                 selectedModel = guardLocalOpenAiCompatibleEndpoint(selectedModel, baseUrl, effectiveModel);
  454 |             }
  455 |             recordCreativeEffectiveSampling(creativeSamplingClaimed, safeTemp, safeTopP);
  456 |             ChatUsageLedger.ParameterKind parameterKind = tokenParam == null
  457 |                     ? ChatUsageLedger.ParameterKind.OMITTED
  458 |                     : "max_tokens".equals(tokenParam)
  459 |                             ? ChatUsageLedger.ParameterKind.MAX_TOKENS
  460 |                             : ChatUsageLedger.ParameterKind.MAX_COMPLETION_TOKENS;
  461 |             return rememberConfiguredTokenBudget(selectedModel, effectiveModel, maxTokens, parameterKind);
  462 |         } catch (Exception e) {
  463 |             throw wrapConnect(e, baseUrl);
  464 |         }
  465 |         } catch (RuntimeException | Error failure) {
  466 |             recordCreativeSamplingFailure(creativeSamplingClaimed);
  467 |             throw failure;
  468 |         }
  469 |     }
  470 | 
  471 |     public static String configuredModelId(ChatModel model) {
  472 |         return model == null ? null : CONFIGURED_MODEL_IDS.get(model);
  473 |     }
  474 | 
  475 |     public static ChatUsageLedger.ConfiguredCap configuredTokenBudget(ChatModel model) {
  476 |         if (model == null) {
  477 |             return ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null);
  478 |         }
  479 |         ChatUsageLedger.ConfiguredCap configured = CONFIGURED_TOKEN_BUDGETS.get(model);
  480 |         return configured == null
  481 |                 ? ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null)
  482 |                 : configured;
  483 |     }
  484 | 
  485 |     private static void recordCreativeEffectiveSampling(Double temperature, Double topP) {
  486 |         recordCreativeEffectiveSampling(claimCreativeProviderSampling(), temperature, topP);
  487 |     }
  488 | 
  489 |     private static boolean claimCreativeProviderSampling() {
  490 |         com.example.lms.service.guard.GuardContext context =
```


## S04 — evidenceReleaseRequired는 일반 출처 정책 전체가 아님

파일: `main/java/com/example/lms/service/ChatWorkflow.java`
SHA-256: `01d9a25b715d13c01782dd1bb275848b6809996d2cc2107e5ecdf5ceceb47bf1`

```text
 1346 |                                 ? "model_unavailable" : chatModelCatalogService.failureCode(choice));
 1347 |             }
 1348 |         }
 1349 | 
 1350 |         // ?? 0) ?ъ슜???낅젰 ?뺣낫 ?????????????????????????????????????
 1351 |         final String userQuery = Optional.ofNullable(req.getMessage()).orElse("");
 1352 |         final boolean evidenceReleaseRequired =
 1353 |                 EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(userQuery);
 1354 |         final String requestedModel = Optional.ofNullable(req.getModel()).orElse("");
 1355 |         final boolean forceLightSearchMode = req != null
 1356 |                 && req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT;
 1357 |         final boolean directRetrievalOffMode = req != null
 1358 |                 && (req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.OFF
 1359 |                 || Boolean.FALSE.equals(req.getUseWebSearch()))
 1360 |                 && Boolean.FALSE.equals(req.getUseRag());
 1361 |         // 인사·일상 대화는 근거 검증이 필요 없다 — AUTO 검색/RAG 실행과 공개 게이트를 함께 생략.
 1362 |         // 명시적 evidence_needed 지시·강제 검색 모드·직접 OFF 계약은 기존 규칙을 유지한다.
 1363 |         final boolean casualGreetingNoEvidenceIntent = !evidenceReleaseRequired
 1364 |                 && !directRetrievalOffMode
 1365 |                 && req != null
 1366 |                 && req.getSearchMode() != com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT
 1367 |                 && req.getSearchMode() != com.example.lms.gptsearch.dto.SearchMode.FORCE_DEEP
 1368 |                 && NoEvidenceChatFallback.isCasualGreetingOnly(userQuery);
 1369 | 
 1370 |         if (userQuery.isBlank()) {
 1371 |             return finishEarlyResult(ChatResult.of("?뺣낫 ?놁쓬", String.format("lc:%s", chatModel.getClass().getSimpleName()), true));
```


## S05 — 명시적 evidence_needed 응답 지시 파서

파일: `main/java/com/example/lms/service/EvidenceNeededDirectivePolicy.java`
SHA-256: `9de10db4916242fb1f3456f59c6fb67ecf63bc9015a32b60cc309b2fe4a7b86c`

```text
    1 | package com.example.lms.service;
    2 | 
    3 | import java.util.regex.Matcher;
    4 | import java.util.regex.Pattern;
    5 | 
    6 | /** Pure, request-local parser for an explicit missing-evidence output contract. */
    7 | public final class EvidenceNeededDirectivePolicy {
    8 |     private static final String TOKEN_REGEX =
    9 |             "(?<![A-Za-z0-9_])evidence_needed(?![A-Za-z0-9_])";
   10 |     private static final Pattern TOKEN = Pattern.compile(TOKEN_REGEX, Pattern.CASE_INSENSITIVE);
   11 |     private static final Pattern MISSING_EVIDENCE_CONDITION = Pattern.compile(
   12 |             "(?:\\b(?:if|when)\\b[^\\r\\n.!?;。！？；]{0,180}(?:"
```

```text
   74 |     }
   75 | 
   76 |     public static boolean requiresEvidenceNeeded(String userQuery) {
   77 |         if (userQuery == null || userQuery.isBlank()) {
   78 |             return false;
   79 |         }
   80 |         Matcher conditionMatcher = MISSING_EVIDENCE_CONDITION.matcher(userQuery);
   81 |         while (conditionMatcher.find()) {
   82 |             if (insidePairedQuote(userQuery, conditionMatcher.start())) {
   83 |                 continue;
   84 |             }
   85 |             Matcher directiveMatcher = OUTPUT_DIRECTIVE.matcher(userQuery);
   86 |             while (directiveMatcher.find()) {
   87 |                 Matcher tokenMatcher = TOKEN.matcher(userQuery);
   88 |                 while (tokenMatcher.find()) {
   89 |                     int tokenOffset = tokenMatcher.start();
   90 |                     if (tokenOffset < directiveMatcher.start() || tokenOffset >= directiveMatcher.end()
   91 |                             || insidePairedQuote(userQuery, tokenOffset)) {
   92 |                         continue;
   93 |                     }
   94 |                     if (sameConditionalClause(
   95 |                             userQuery,
   96 |                             conditionMatcher.start(),
   97 |                             conditionMatcher.end(),
   98 |                             tokenMatcher.start(),
   99 |                             tokenMatcher.end())
  100 |                             && !clauseRejected(
  101 |                                     userQuery,
  102 |                                     Math.min(conditionMatcher.start(), directiveMatcher.start()),
  103 |                                     Math.max(conditionMatcher.end(), directiveMatcher.end()))) {
  104 |                         return true;
  105 |                     }
  106 |                 }
  107 |             }
  108 |         }
  109 |         return false;
  110 |     }
  111 | 
  112 |     private static boolean sameConditionalClause(
  113 |             String value,
  114 |             int conditionStart,
  115 |             int conditionEnd,
  116 |             int tokenStart,
```


## S06 — retrieval contract와 evidence 상태 도출

파일: `main/java/com/example/lms/service/ChatWorkflow.java`
SHA-256: `01d9a25b715d13c01782dd1bb275848b6809996d2cc2107e5ecdf5ceceb47bf1`

```text
 7360 |         EVIDENCE_PRESENT,
 7361 |         CONFIRMED_EMPTY,
 7362 |         METADATA_INCOMPLETE
 7363 |     }
 7364 | 
 7365 |     record RetrievalReleaseContract(
 7366 |             boolean retrievalContractRequested,
 7367 |             boolean webRequested,
 7368 |             boolean ragRequested,
 7369 |             boolean effectiveWeb,
 7370 |             boolean effectiveRag,
 7371 |             boolean explicitDirectOff) {
 7372 |     }
 7373 | 
 7374 |     static RetrievalReleaseContract buildRetrievalReleaseContract(
 7375 |             ChatRequestDto req,
 7376 |             ChatRequestDto.RetrievalRequestIntent intent,
 7377 |             boolean effectiveWeb,
 7378 |             boolean effectiveRag,
 7379 |             boolean evidenceReleaseRequired) {
 7380 |         ChatRequestDto.RetrievalRequestIntent rawIntent = intent != null
 7381 |                 ? intent
 7382 |                 : new ChatRequestDto.RetrievalRequestIntent(
 7383 |                         req == null ? null : req.getUseWebSearch(),
 7384 |                         req == null ? null : req.getUseRag());
 7385 |         com.example.lms.gptsearch.dto.SearchMode mode = req == null
 7386 |                 ? null
 7387 |                 : req.getSearchMode();
 7388 |         boolean forcedWeb = mode == com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT
 7389 |                 || mode == com.example.lms.gptsearch.dto.SearchMode.FORCE_DEEP;
 7390 |         boolean explicitOff = !forcedWeb
 7391 |                 && ((mode == com.example.lms.gptsearch.dto.SearchMode.OFF
 7392 |                 && req != null
 7393 |                 && Boolean.FALSE.equals(req.getUseRag()))
 7394 |                 || (Boolean.FALSE.equals(rawIntent.webSearch())
 7395 |                 && Boolean.FALSE.equals(rawIntent.rag())));
 7396 |         boolean webRequested = Boolean.TRUE.equals(rawIntent.webSearch()) || forcedWeb;
 7397 |         boolean ragRequested = Boolean.TRUE.equals(rawIntent.rag());
 7398 |         if (evidenceReleaseRequired && !explicitOff) {
 7399 |             webRequested = webRequested || effectiveWeb;
 7400 |             ragRequested = ragRequested || effectiveRag;
 7401 |         }
 7402 |         boolean retrievalRequested = webRequested || ragRequested || evidenceReleaseRequired;
 7403 |         return new RetrievalReleaseContract(
 7404 |                 retrievalRequested,
 7405 |                 webRequested,
 7406 |                 ragRequested,
 7407 |                 effectiveWeb,
 7408 |                 effectiveRag,
 7409 |                 explicitOff);
 7410 |     }
 7411 | 
 7412 |     static EvidenceReleaseState deriveEvidenceReleaseState(
 7413 |             com.example.lms.service.rag.RagEvidenceAttributionService.PromotionResult promotionResult,
 7414 |             java.util.List<RagEvidenceMetadata> finalEvidence,
 7415 |             boolean lateUnattributedEvidenceAdded,
 7416 |             RetrievalReleaseContract contract) {
 7417 |         RetrievalReleaseContract safeContract = Objects.requireNonNull(contract, "contract");
 7418 |         if (safeContract.explicitDirectOff()
 7419 |                 || (!safeContract.retrievalContractRequested()
 7420 |                 && !safeContract.effectiveWeb()
 7421 |                 && !safeContract.effectiveRag())) {
 7422 |             return EvidenceReleaseState.NOT_APPLICABLE;
 7423 |         }
 7424 |         if (promotionResult == null
 7425 |                 || promotionResult.status()
 7426 |                 == com.example.lms.service.rag.RagEvidenceAttributionService.PromotionStatus.FAILED
 7427 |                 || promotionResult.status()
 7428 |                 == com.example.lms.service.rag.RagEvidenceAttributionService.PromotionStatus.UNAVAILABLE
 7429 |                 || lateUnattributedEvidenceAdded
 7430 |                 || (safeContract.retrievalContractRequested()
 7431 |                 && !safeContract.effectiveWeb()
 7432 |                 && !safeContract.effectiveRag())
 7433 |                 || (safeContract.webRequested() && !safeContract.effectiveWeb())
 7434 |                 || (safeContract.ragRequested() && !safeContract.effectiveRag())
 7435 |                 || (safeContract.webRequested() && promotionResult.webCitableLocatorCount() <= 0)
 7436 |                 || (safeContract.ragRequested() && promotionResult.vectorCitableLocatorCount() <= 0)) {
 7437 |             return EvidenceReleaseState.METADATA_INCOMPLETE;
 7438 |         }
 7439 | 
 7440 |         java.util.List<RagEvidenceMetadata> evidence = finalEvidence == null
 7441 |                 ? java.util.List.of()
 7442 |                 : finalEvidence;
 7443 |         if (!evidence.isEmpty()) {
 7444 |             boolean webPresent = evidence.stream()
 7445 |                     .filter(Objects::nonNull)
 7446 |                     .anyMatch(item -> "WEB".equals(item.kind()));
 7447 |             boolean vectorPresent = evidence.stream()
 7448 |                     .filter(Objects::nonNull)
 7449 |                     .anyMatch(item -> "VECTOR".equals(item.kind()));
 7450 |             if ((safeContract.webRequested() && !webPresent)
 7451 |                     || (safeContract.ragRequested() && !vectorPresent)) {
 7452 |                 return EvidenceReleaseState.METADATA_INCOMPLETE;
 7453 |             }
 7454 |             return EvidenceReleaseState.EVIDENCE_PRESENT;
 7455 |         }
 7456 | 
 7457 |         if (promotionResult.status()
 7458 |                 == com.example.lms.service.rag.RagEvidenceAttributionService.PromotionStatus.PROMOTED
 7459 |                 || promotionResult.status()
 7460 |                 == com.example.lms.service.rag.RagEvidenceAttributionService.PromotionStatus.CONFIRMED_EMPTY) {
 7461 |             return EvidenceReleaseState.CONFIRMED_EMPTY;
 7462 |         }
 7463 |         return EvidenceReleaseState.METADATA_INCOMPLETE;
```


## S07 — 최종 공개 정책: metadata incomplete 무조건 HOLD

파일: `main/java/com/example/lms/service/ChatWorkflow.java`
SHA-256: `01d9a25b715d13c01782dd1bb275848b6809996d2cc2107e5ecdf5ceceb47bf1`

```text
 7516 |     static FinalVerificationReleaseDecision applyEvidenceReleasePolicy(
 7517 |             FinalVerificationReleaseDecision base,
 7518 |             EvidenceReleaseState state,
 7519 |             boolean evidenceReleaseRequired,
 7520 |             boolean explicitDirectOff) {
 7521 |         Objects.requireNonNull(base, "base");
 7522 |         Objects.requireNonNull(state, "state");
 7523 |         if (!base.releaseAllowed()) {
 7524 |             return base;
 7525 |         }
 7526 |         if (explicitDirectOff
 7527 |                 || state == EvidenceReleaseState.NOT_APPLICABLE
 7528 |                 || state == EvidenceReleaseState.EVIDENCE_PRESENT) {
 7529 |             return base;
 7530 |         }
 7531 |         if (state == EvidenceReleaseState.METADATA_INCOMPLETE) {
 7532 |             return new FinalVerificationReleaseDecision(
 7533 |                     "evidence_needed: attribution unavailable / verify retrieval evidence",
 7534 |                     "HOLD",
 7535 |                     "evidence_release_metadata_incomplete",
 7536 |                     false,
 7537 |                     true,
 7538 |                     false);
 7539 |         }
 7540 |         if (state == EvidenceReleaseState.CONFIRMED_EMPTY && evidenceReleaseRequired) {
 7541 |             return new FinalVerificationReleaseDecision(
 7542 |                     "evidence_needed",
 7543 |                     "HOLD",
 7544 |                     "evidence_required_empty",
 7545 |                     false,
 7546 |                     true,
 7547 |                     false);
 7548 |         }
 7549 |         return base;
```

```text
 7556 |     static FinalVerificationReleaseDecision applyFinalVerificationReleaseGate(
 7557 |             String candidate,
 7558 |             boolean verificationRequired,
 7559 |             String verificationStatus,
 7560 |             boolean outcomeKnown,
 7561 |             boolean acceptedForMemory) {
 7562 |         String safeCandidate = candidate == null ? "" : candidate;
 7563 |         if (!verificationRequired) {
 7564 |             return new FinalVerificationReleaseDecision(
 7565 |                     safeCandidate,
 7566 |                     "NOT_REQUIRED",
 7567 |                     "verification_not_required",
 7568 |                     true,
 7569 |                     false,
 7570 |                     true);
 7571 |         }
 7572 | 
 7573 |         String normalizedStatus = verificationStatus == null
 7574 |                 ? "unknown"
 7575 |                 : verificationStatus.trim().toLowerCase(Locale.ROOT);
 7576 |         if (!outcomeKnown) {
 7577 |             return new FinalVerificationReleaseDecision(
 7578 |                     "evidence_needed: final verification outcome unknown / retry with verifiable evidence",
 7579 |                     "HOLD",
 7580 |                     "verification_outcome_unknown",
 7581 |                     false,
 7582 |                     false,
 7583 |                     false);
 7584 |         }
```

```text
 7628 |     record FinalVerificationReleaseDecision(
 7629 |             String content,
 7630 |             String releaseStatus,
 7631 |             String reasonCode,
 7632 |             boolean releaseAllowed,
 7633 |             boolean evidencePolicyApplied,
 7634 |             boolean knowledgeWriteAllowed) {
 7635 |     }
 7636 | 
 7637 |     static ChatResult sanitizeFallbackResult(
```


## S08 — PromotionResult의 CONFIRMED_EMPTY는 검증 탈락도 포함

파일: `main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java`
SHA-256: `f6e89919d4dc82402d13b5d3227d014bb2c7ab32a5a8d8066d1fc9e5ad26f9d2`

```text
   55 |     public enum PromotionStatus {
   56 |         PROMOTED,
   57 |         CONFIRMED_EMPTY,
   58 |         FAILED,
   59 |         UNAVAILABLE
   60 |     }
   61 | 
   62 |     public enum PromotionReason {
   63 |         PROMOTED("promoted"),
   64 |         NO_CITABLE_LOCATOR("no_citable_locator"),
   65 |         EVIDENCE_GATE_BLOCKED("evidence_gate_blocked"),
   66 |         CITATION_GATE_BLOCKED("citation_gate_blocked"),
   67 |         GATE_EXCEPTION("gate_exception"),
   68 |         SERVICE_UNAVAILABLE("service_unavailable"),
   69 |         CALLER_FAILURE("caller_failure");
   70 | 
   71 |         private final String traceValue;
   72 | 
   73 |         PromotionReason(String traceValue) {
   74 |             this.traceValue = traceValue;
   75 |         }
   76 | 
   77 |         public String traceValue() {
   78 |             return traceValue;
   79 |         }
   80 |     }
   81 | 
   82 |     public record PromotionResult(
   83 |             PromotionStatus status,
   84 |             PromotionReason reason,
   85 |             List<RagEvidenceMetadata> evidence,
   86 |             int webCandidateCount,
   87 |             int webCitableLocatorCount,
   88 |             int vectorCandidateCount,
   89 |             int vectorCitableLocatorCount,
   90 |             int localCandidateCount,
   91 |             int localCitableLocatorCount) {
```

```text
  115 |                 }
  116 |                 case CONFIRMED_EMPTY -> {
  117 |                     if (!evidence.isEmpty()
  118 |                             || (reason != PromotionReason.NO_CITABLE_LOCATOR
  119 |                             && reason != PromotionReason.EVIDENCE_GATE_BLOCKED
  120 |                             && reason != PromotionReason.CITATION_GATE_BLOCKED)) {
  121 |                         throw new IllegalArgumentException("confirmed-empty result has contradictory authority");
  122 |                     }
```

```text
  223 |             String question,
  224 |             List<Content> webDocs,
  225 |             List<Content> vectorDocs,
  226 |             List<Document> localDocs,
  227 |             QueryDomain domain,
  228 |             boolean followUp) {
  229 |         long started = System.nanoTime();
  230 |         List<Candidate> candidates = new ArrayList<>();
  231 |         candidates.addAll(fromContents("WEB", "W", webDocs));
  232 |         candidates.addAll(fromContents("VECTOR", "V", vectorDocs));
  233 |         candidates.addAll(fromDocuments("LOCAL_DOC", "D", localDocs));
  234 | 
  235 |         List<Candidate> rawCitableCandidates = citableCandidates(candidates);
  236 |         int webCandidateCount = candidateCount(candidates, "WEB");
  237 |         int webCitableLocatorCount = candidateCount(rawCitableCandidates, "WEB");
  238 |         int vectorCandidateCount = candidateCount(candidates, "VECTOR");
  239 |         int vectorCitableLocatorCount = candidateCount(rawCitableCandidates, "VECTOR");
  240 |         int localCandidateCount = candidateCount(candidates, "LOCAL_DOC");
  241 |         int localCitableLocatorCount = candidateCount(rawCitableCandidates, "LOCAL_DOC");
```

```text
  256 |         boolean evidencePassed = false;
  257 |         boolean citationSoftPassed = false;
  258 |         boolean citationMinPassed = false;
  259 |         int citationMin = effectiveMinCitations();
  260 |         PromotionReason reason = null;
  261 |         List<Candidate> filteredCandidates = List.of();
  262 |         try {
  263 |             evidencePassed = evidenceGate == null || evidenceGate.hasSufficientCoverage(
  264 |                     question,
  265 |                     vectorLines,
  266 |                     List.of(),
  267 |                     kbLines,
  268 |                     followUp,
  269 |                     domain == null ? QueryDomain.GENERAL : domain);
  270 |             filteredCandidates = filteredCitableCandidates(question, candidates);
  271 |             List<String> sources = filteredCandidates.stream()
  272 |                     .map(c -> locatorKey(c.metadata))
  273 |                     .filter(s -> s != null && !s.isBlank())
  274 |                     .distinct()
  275 |                     .toList();
  276 |             citationSoftPassed = citationGate == null || citationGate.ok(sources, citationMin, 0.0d);
  277 |             citationMinPassed = citationGate == null || sources.size() >= citationMin;
  278 |             if (candidates.isEmpty()) {
  279 |                 reason = PromotionReason.NO_CITABLE_LOCATOR;
  280 |             } else if (!evidencePassed) {
  281 |                 reason = PromotionReason.EVIDENCE_GATE_BLOCKED;
  282 |             } else if (filteredCandidates.isEmpty()) {
  283 |                 reason = PromotionReason.NO_CITABLE_LOCATOR;
  284 |             } else if (!citationSoftPassed || !citationMinPassed) {
  285 |                 reason = PromotionReason.CITATION_GATE_BLOCKED;
  286 |             }
```


## S09 — 짧은 답변의 선택적 확장 호출부

파일: `main/java/com/example/lms/service/ChatWorkflow.java`
SHA-256: `01d9a25b715d13c01782dd1bb275848b6809996d2cc2107e5ecdf5ceceb47bf1`

```text
  928 |             boolean refinerFeatureEnabled,
  929 |             ConversationFrameV1 frame) {
  930 |         return refinerFeatureEnabled && safeConversationFrame(frame).allowsOptionalRefinement();
  931 |     }
  932 | 
  933 |     static boolean allowsConversationExpansion(ConversationFrameV1 frame) {
  934 |         return safeConversationFrame(frame).allowsOptionalExpansion();
  935 |     }
  936 | 
  937 |     static boolean deniesConversationMemoryWrite(
  938 |             boolean interactionPolicyDenied,
```

```text
 4005 |                 }
 4006 |             } else if (com.example.lms.service.guard.EvidenceAwareGuard.looksWeak(out)) {
 4007 |                 TraceStore.put("answer.guardRecovery.skipped", "retrieval_off_direct");
 4008 |             }
 4009 |         } catch (Throwable ignore) {
 4010 |             ChatWorkflowTraceSuppressions.traceSuppressed("answer.guardRecovery", ignore);
 4011 |         }
 4012 | 
 4013 |         traceS8Integrity("preExpansion", finalQuery, out);
 4014 |         if (allowsConversationExpansion(conversationFrame)
 4015 |                 && lengthVerifier.isShort(out, vp.minWordCount())) {
 4016 |             out = Optional.ofNullable(answerExpander.expandWithLc(out, vp, model)).orElse(out);
 4017 |         }
 4018 |         traceS8Integrity("postExpansion", finalQuery, out);
 4019 | 
 4020 |         // Evidence-aware regeneration guard (legacy) removed: the pipeline either
 4021 |         // rewrites using evidence-only answers
 4022 |         // or expands with the configured answerExpander.
 4023 |         // [Dual-Vision] View2 2李??⑥뒪
```


## S10 — 확장기의 입력·거부·숫자 검사

파일: `main/java/com/example/lms/service/answer/AnswerExpanderService.java`
SHA-256: `266cb79cccc453a9c8337c3178695a4e996880a422ade6a2bc18c9235d05fa2d`

```text
   51 |     /**
   52 |      * Backward-compatible overload used by legacy call sites that do not provide
   53 |      * explicit evidence snippets. Delegates to the full variant with an empty list.
   54 |      */
   55 |     public String expandWithLc(String draft, VerbosityProfile vp, ChatModel model) {
   56 |         return expandWithLc(draft, vp, model, java.util.Collections.emptyList());
   57 |     }
```

```text
  119 |     public String expandWithLc(String draft, VerbosityProfile vp, ChatModel model, List<String> evidenceSnippets) {
  120 |         ChatUsageLedger.ExpansionAttempt expansionAttempt = chatUsageLedger == null
  121 |                 ? null
  122 |                 : chatUsageLedger.beginExpansion();
  123 |         ChatUsageLedger.ModelAttempt modelAttempt = null;
  124 |         try {
  125 |             TimeBudget requestBudget = TimeBudgetContext.get();
  126 |             if (requestBudget != null && requestBudget.expired()) {
  127 |                 TraceStore.put("answer.expansion.skipped", "request_budget_exhausted");
  128 |                 if (expansionAttempt != null) {
  129 |                     expansionAttempt.skippedBeforeModel();
  130 |                 }
  131 |                 return null;
  132 |             }
```

```text
  186 |             if (result == null || result.isBlank()) {
  187 |                 if (expansionAttempt != null) {
  188 |                     expansionAttempt.rejectedEmpty();
  189 |                 }
  190 |                 return null;
  191 |             }
  192 | 
  193 |             // The editor prompt forbids inventing numbers. Enforce that invariant so a
  194 |             // second model call cannot turn a correct draft value into a different one.
  195 |             if (introducesUnsupportedNumber(draft, result, evidenceSnippets)) {
  196 |                 TraceStore.put("answer.expansion.rejected", "numeric_invariant_mismatch");
  197 |                 if (expansionAttempt != null) {
  198 |                     expansionAttempt.rejectedNumeric();
  199 |                 }
  200 |                 return null;
  201 |             }
  202 | 
  203 |             // [NO_EVIDENCE] / soft-empty markers: treat as null expansion so callers KEEP the original draft.
  204 |             if (result != null) {
  205 |                 String trimmed = result.trim();
  206 |                 String compact = trimmed.replaceAll("\\s+", "");
  207 |                 if ("[NO_EVIDENCE]".equals(trimmed)
  208 |                         || "NO_EVIDENCE".equalsIgnoreCase(trimmed)
  209 |                         || compact.contains("근거없다")
  210 |                         || compact.contains("근거없음")) {
  211 |                     if (expansionAttempt != null) {
  212 |                         expansionAttempt.rejectedNoEvidence();
  213 |                     }
  214 |                     TraceStore.put("answer.expansion.rejected", "no_evidence_marker_keep_draft");
  215 |                     return null; // 확장하지 않고 원문 그대로 사용
  216 |                 }
  217 |             }
  218 | 
  219 |             // 너무 짧게 요약한 경우도 원문 사용
  220 |             if (result != null && result.length() < draft.length() * 0.8) {
  221 |                 if (expansionAttempt != null) {
  222 |                     expansionAttempt.rejectedTooShort();
  223 |                 }
  224 |                 return null;
  225 |             }
  226 | 
  227 |             if (expansionAttempt != null) {
  228 |                 expansionAttempt.accepted();
  229 |             }
  230 |             return result;
```

```text
  272 |     private static boolean introducesUnsupportedNumber(
  273 |             String draft,
  274 |             String result,
  275 |             List<String> evidenceSnippets) {
  276 |         if (result == null || result.isBlank()) {
  277 |             return false;
  278 |         }
  279 |         Set<String> allowed = new HashSet<>();
  280 |         collectNumberTokens(allowed, draft);
  281 |         for (String evidence : safeEvidenceSnippets(evidenceSnippets)) {
  282 |             collectNumberTokens(allowed, evidence);
  283 |         }
  284 |         Matcher resultNumbers = NUMBER_TOKEN.matcher(result);
  285 |         while (resultNumbers.find()) {
  286 |             if (structuralListOrdinal(result, resultNumbers)) {
  287 |                 continue;
  288 |             }
  289 |             if (!allowed.contains(canonicalNumber(resultNumbers.group()))) {
  290 |                 return true;
  291 |             }
  292 |         }
  293 |         return false;
  294 |     }
```


## S11 — 캐시된 GPU 측정과 전체 GPU 최대 메모리 기반 admission

파일: `main/java/com/example/lms/health/GpuHardwareDiagnostics.java`
SHA-256: `bad62614c93e2f03f4282ae904296804a30b7f3ec3022704c72ed817bb5a945f`

```text
   25 |     private static final CommandRunner LOCAL_QUERY = GpuHardwareDiagnostics::runNvidiaSmi;
   26 |     private static QuerySample lastQuery;
   27 | 
   28 |     private record QuerySample(CommandRunner runner, int timeoutMs, long completedNanos,
   29 |                                long observedAtMillis, CommandResult result, IOException failure) { }
   30 | 
   31 |     private static synchronized QuerySample query(CommandRunner runner, int timeoutMs) throws InterruptedException {
   32 |         // Both successful and failed observations have a short bound; concurrent requests share one probe.
   33 |         if (lastQuery != null && lastQuery.runner() == runner && lastQuery.timeoutMs() == timeoutMs
   34 |                 && System.nanoTime() - lastQuery.completedNanos() < TimeUnit.SECONDS.toNanos(2)) return lastQuery;
   35 |         CommandResult result = null;
   36 |         IOException failure = null;
   37 |         try {
   38 |             result = runner.run(timeoutMs);
   39 |         } catch (IOException unavailable) {
   40 |             failure = unavailable;
   41 |         }
   42 |         lastQuery = new QuerySample(runner, timeoutMs, System.nanoTime(), System.currentTimeMillis(), result, failure);
   43 |         return lastQuery;
```

```text
   74 |                 out.put("status", "timeout");
   75 |                 out.put("disabledReason", "timeout");
   76 |                 return finish(env, out);
   77 |             }
   78 |             List<Map<String, Object>> devices = parseNvidiaSmiCsv(result.stdout());
   79 |             out.put("devices", devices);
   80 |             out.put("detectedCount", devices.size());
   81 |             out.put("hasRtx3090", hasDevice(devices, "3090"));
   82 |             out.put("hasRtx3060", hasDevice(devices, "3060"));
   83 |             out.put("heavyLaneReady", result.exitCode() == 0 && Boolean.TRUE.equals(out.get("hasRtx3090"))
   84 |                     && Boolean.TRUE.equals(out.get("hasRtx3060")));
   85 |             out.put("memoryEvidenceComplete", !devices.isEmpty()
   86 |                     && devices.stream().allMatch(device -> device.containsKey("memoryUsedRatio")));
   87 |             out.put("maxMemoryUsedRatio", maxDouble(devices, "memoryUsedRatio"));
   88 |             out.put("maxUtilizationGpuPct", maxInt(devices, "utilizationGpuPct"));
   89 |             out.put("maxTemperatureC", maxInt(devices, "temperatureC"));
   90 |             out.put("available", !devices.isEmpty());
   91 |             out.put("status", devices.isEmpty() ? "parse_error" : STATUS_OK);
   92 |             out.put("disabledReason", devices.isEmpty() ? "no_parseable_devices" : null);
   93 |             if (result.exitCode() != 0) {
   94 |                 out.put("status", devices.isEmpty() ? "error" : "partial");
   95 |                 out.put("disabledReason", "nvidia_smi_exit_" + result.exitCode());
   96 |             }
   97 |             return finish(env, out);
   98 |         } catch (InterruptedException ex) {
   99 |             Thread.currentThread().interrupt();
  100 |             traceSuppressed("gpuHardware.interrupted", ex);
  101 |             out.put("status", "interrupted");
  102 |             out.put("disabledReason", "interrupted");
  103 |         } catch (IOException ex) {
  104 |             traceSuppressed("gpuHardware.io", ex);
  105 |             String status = commandFailureStatus(ex);
```

```text
  183 |         out.put("enabled", enabled);
  184 |         out.put("warnThreshold", warnThreshold);
  185 |         out.put("blockThreshold", blockThreshold);
  186 |         out.put("blockWhenUnavailable", blockWhenUnavailable);
  187 |         out.put("pressureLevel", "disabled");
  188 |         out.put("status", "disabled_by_config");
  189 |         out.put("reason", "disabled_by_config");
  190 |         out.put("heavyWorkloadsAllowed", true);
  191 |         out.put("retrainAllowed", true);
  192 |         out.put("rerankAllowed", true);
  193 |         out.put("embeddingFallbackAllowed", true);
  194 | 
  195 |         if (!enabled) {
  196 |             return out;
  197 |         }
  198 |         if (!telemetryEnabled) {
  199 |             out.put("pressureLevel", "observe_only");
  200 |             out.put("status", "observe_only");
  201 |             out.put("reason", "telemetry_disabled");
  202 |             return out;
  203 |         }
  204 | 
  205 |         String status = String.valueOf(safeSnapshot.getOrDefault("status", ""));
  206 |         boolean available = boolValue(safeSnapshot.get("available"), false);
  207 |         if (!STATUS_OK.equals(status) || !available) {
  208 |             out.put("pressureLevel", blockWhenUnavailable ? "block" : "observe_only");
  209 |             out.put("status", blockWhenUnavailable ? "blocked" : "observe_only");
  210 |             out.put("reason", "gpu_telemetry_unavailable");
  211 |             if (blockWhenUnavailable) {
  212 |                 blockHeavy(out);
  213 |             }
```

```text
  234 |         if (Boolean.FALSE.equals(safeSnapshot.get("memoryEvidenceComplete"))) {
  235 |             out.put("pressureLevel", blockWhenUnavailable ? "block" : "observe_only");
  236 |             out.put("status", blockWhenUnavailable ? "blocked" : "observe_only");
  237 |             out.put("reason", "gpu_memory_evidence_needed");
  238 |             if (blockWhenUnavailable) blockHeavy(out);
  239 |             return out;
  240 |         }
  241 |         double maxMemory = doubleValue(safeSnapshot.get("maxMemoryUsedRatio"), 0.0d);
  242 |         out.put("maxMemoryUsedRatio", maxMemory);
  243 |         if (maxMemory >= blockThreshold) {
  244 |             out.put("pressureLevel", "block");
  245 |             out.put("status", "blocked");
  246 |             out.put("reason", "gpu_memory_pressure");
  247 |             blockHeavy(out);
  248 |             return out;
  249 |         }
  250 |         if (maxMemory >= warnThreshold) {
  251 |             out.put("pressureLevel", "warn");
  252 |             out.put("status", "degraded");
  253 |             out.put("reason", "gpu_memory_pressure_warn");
  254 |             out.put("retrainAllowed", false);
  255 |             out.put("embeddingFallbackAllowed", false);
  256 |             return out;
  257 |         }
  258 | 
  259 |         out.put("pressureLevel", "nominal");
  260 |         out.put("status", STATUS_OK);
  261 |         out.put("reason", "ok");
  262 |         return out;
```


## S12 — GPU admission을 호출하는 기존 rerank gate

파일: `main/java/com/example/lms/service/rag/rerank/RerankGate.java`
SHA-256: `10add5471c2e41f3d1def60116f1b6f7d7198ec13aa521a9008a067ddbc24c09`

```text
   78 |      * @param candidates the first pass candidate list
   79 |      * @return {@code true} when reranking should be performed; otherwise
   80 |      * {@code false}
   81 |      */
   82 |     public boolean shouldRerank(List<Content> candidates) {
   83 |         if (candidates == null || candidates.isEmpty()) {
   84 |             return false;
   85 |         }
   86 |         if (gpuHardwareAdmissionBlocksRerank()) {
   87 |             return false;
   88 |         }
   89 | 
   90 |         // [Err333 Fix] ensure that even small candidate sets are reranked
   91 |         int size = candidates.size();
   92 |         // [Err333 Fix] 소량 후보군(1~ceTopK-1개)은 비용이 낮으므로 항상 재랭크를 수행한다.
   93 |         if (size < ceTopK) {
   94 |             TraceStore.put("rerank.ce.skipped", true);
   95 |             TraceStore.put("rerank.ce.skipReason", "insufficient_candidates");
   96 |             return false;
   97 |         }
   98 |         try {
   99 |             // ----------------------- NEW HEURISTICS -----------------------
```

```text
  183 |     private boolean gpuHardwareAdmissionBlocksRerank() {
  184 |         if (!boolProp("awx.gpu-hardware.admission.rerank-gate-enabled", false)) {
  185 |             return false;
  186 |         }
  187 |         Map<String, Object> snapshot = GpuHardwareDiagnostics.snapshot(env);
  188 |         Map<String, Object> admission = GpuHardwareDiagnostics.admissionFromSnapshot(snapshot);
  189 |         boolean allowed = boolValue(admission.get("rerankAllowed"), true);
  190 |         try {
  191 |             TraceStore.put("rerank.ce.gpuHardwareAdmission.status", admission.getOrDefault("status", ""));
  192 |             TraceStore.put("rerank.ce.gpuHardwareAdmission.reason", SafeRedactor.traceLabelOrFallback(String.valueOf(admission.getOrDefault("reason", "")), "unknown"));
  193 |             TraceStore.put("rerank.ce.gpuHardwareAdmission.allowed", allowed);
  194 |             if (!allowed) {
  195 |                 TraceStore.put("rerank.ce.skipped", true);
  196 |                 TraceStore.put("rerank.ce.skipReason", "gpu_hardware_admission");
  197 |             }
  198 |         } catch (RuntimeException ex) {
  199 |             TraceStore.put("rerank.ce.gpuHardwareAdmission.traceErrorType", safeExceptionName(ex));
  200 |         }
  201 |         return !allowed;
  202 |     }
  203 | 
  204 |     private boolean boolProp(String key, boolean defaultValue) {
```


## S13 — ONNX에 한정된 semaphore aspect

파일: `main/java/com/example/lms/resilience/SemaphoreGateAspect.java`
SHA-256: `8bc074e519dd0d74d20044c3aa6209145f347bbd911a215b8e0dd8f4c6a5511f`

```text
   18 | /**
   19 |  * Simple concurrency gate for ONNX rerank to prevent request pile-ups when the
   20 |  * runtime is slow or under contention.
   21 |  *
   22 |  * <p>
   23 |  * Fail-soft policy: if we cannot acquire a permit immediately, we skip the
   24 |  * expensive rerank call and return the pre-filtered list trimmed to topN.
   25 |  * </p>
   26 |  */
   27 | @Aspect
   28 | @Component
   29 | @ConditionalOnProperty(name = "reranker.ce.maxConcurrency")
   30 | @Order(Ordered.HIGHEST_PRECEDENCE + 18)
   31 | public class SemaphoreGateAspect {
   32 | 
   33 |     private static final Logger log = LoggerFactory.getLogger(SemaphoreGateAspect.class);
   34 | 
   35 |     private final Semaphore semaphore;
   36 | 
   37 |     @Value("${reranker.ce.maxConcurrency:1}")
   38 |     private int maxConcurrency;
   39 | 
   40 |     public SemaphoreGateAspect(@Value("${reranker.ce.maxConcurrency:1}") int maxConcurrency) {
   41 |         this.maxConcurrency = maxConcurrency;
   42 |         this.semaphore = new Semaphore(Math.max(1, maxConcurrency));
   43 |     }
   44 | 
   45 |     // Gate only the rerank call; do not wrap all methods on the class.
   46 |     @Around("execution(java.util.List *..OnnxCrossEncoderReranker.rerank(..))")
   47 |     public Object around(ProceedingJoinPoint pjp) throws Throwable {
   48 |         if (maxConcurrency <= 0) {
   49 |             return pjp.proceed();
   50 |         }
   51 | 
   52 |         // Fast-fail: do not enqueue.
   53 |         if (!semaphore.tryAcquire()) {
   54 |             log.debug("[SemaphoreGate] ONNX rerank concurrency limit reached; skipping rerank (fail-soft)");
   55 | 
   56 |             Object[] args = pjp.getArgs();
   57 |             if (args != null && args.length >= 3 && args[1] instanceof List<?> list && args[2] instanceof Integer topN) {
   58 |                 int k = topN;
   59 |                 if (k <= 0) k = list.size();
   60 |                 k = Math.min(k, list.size());
   61 |                 return new ArrayList<>(list.subList(0, k));
   62 |             }
   63 |             // Fallback: if signature unexpected, proceed.
   64 |             return pjp.proceed();
   65 |         }
   66 | 
   67 |         try {
   68 |             return pjp.proceed();
   69 |         } finally {
   70 |             semaphore.release();
   71 |         }
   72 |     }
   73 | }
```


## S14 — 기존 동일 embedding-space 보호

파일: `main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java`
SHA-256: `35f638bee64f2adfcf94a05d15b14668f5ab0b3ca0404c7a3a3d38ead1064503`

```text
  636 |     private boolean backupEmbeddingSpaceCompatible() {
  637 |         // Only a known local configuration can attest model and preprocessing identity.
  638 |         // A generic/OpenAI backup with the same vector length is not the same embedding space.
  639 |         if (!(backupModel instanceof OllamaEmbeddingModel localBackup)
  640 |                 || localBackup == this || localBackup.backupModel != null) return false;
  641 |         return isOllamaProvider() && localBackup.isOllamaProvider()
  642 |                 && model != null && !model.isBlank()
  643 |                 && Objects.equals(model.trim(), localBackup.model == null ? null : localBackup.model.trim())
  644 |                 && configuredIndexDimensions() == localBackup.configuredIndexDimensions()
  645 |                 && Objects.equals(effectiveNormalizationMode(), localBackup.effectiveNormalizationMode())
  646 |                 && allowZeroPad == localBackup.allowZeroPad;
  647 |     }
  648 | 
  649 |     private void requireCompatibleBackupEmbeddingSpace() {
  650 |         if (!backupEmbeddingSpaceCompatible()) {
  651 |             com.example.lms.search.TraceStore.put("embed.failover.blockedReason", "embedding_space_unverified");
  652 |             throw new IllegalStateException("Embedding fallback blocked: embedding_space_unverified");
  653 |         }
  654 |     }
  655 | 
  656 |     private float[] callBackupVector(String text, String stage) {
  657 |         if (backupModel != null) requireCompatibleBackupEmbeddingSpace();
  658 |         if (backupModel == null) {
  659 | 	        log.warn("[OllamaEmbeddingModel] backupEmbeddingModel not available; failing embedding (stage={})", stage);
  660 | 	        if (debugEventStore != null) {
  661 | 	            debugEventStore.emit(
  662 | 	                    DebugProbeType.EMBEDDING,
```


## S15 — 포트 추측 후보가 명시 fallback보다 앞서는 경로

파일: `main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java`
SHA-256: `35f638bee64f2adfcf94a05d15b14668f5ab0b3ca0404c7a3a3d38ead1064503`

```text
 1583 |     List<String> buildCandidateUrls(String primary, String secondary) {
 1584 |         if (localLlmProcessManager != null) {
 1585 |             primary = localLlmProcessManager.resolveServiceUrl(primary);
 1586 |             secondary = localLlmProcessManager.resolveServiceUrl(secondary);
 1587 |         }
 1588 |         List<String> out = new ArrayList<>();
 1589 |         addUrl(out, primary);
 1590 | 
 1591 |         boolean fallbackAllowed = portFallbackEnabled || crossGpuFallbackEnabled;
 1592 |         logFallbackConfigOnce(primary, secondary, fallbackAllowed);
 1593 | 
 1594 |         if (fallbackAllowed) {
 1595 |             String alt = alternativePortUrl(primary);
 1596 |             if (alt != null && !alt.equals(primary)) {
 1597 |                 addUrl(out, alt);
 1598 |             }
 1599 |         }
 1600 | 
 1601 |         if (fallbackAllowed && secondary != null && !secondary.isBlank()) {
 1602 |             addUrl(out, secondary);
 1603 |             if (fallbackAllowed) {
 1604 |                 String alt2 = alternativePortUrl(secondary);
 1605 |                 if (alt2 != null && !alt2.equals(secondary)) {
 1606 |                     addUrl(out, alt2);
 1607 |                 }
 1608 |             }
 1609 |         }
 1610 | 
 1611 |         // Also consider health-url override as a primary for GET health, if applicable.
 1612 |         return out;
 1613 |     }
 1614 | 
 1615 |     private void logFallbackConfigOnce(String primary, String secondary, boolean fallbackAllowed) {
```


## S16 — 주/보조 endpoint 기본값과 이미 켜진 gateway 설정

파일: `main/resources/application-llm.yaml`
SHA-256: `dacec4da8144dc373a1a57d400b4305d8f3cbc8f5b308e6fa4272dec36a078f0`

```text
   10 | llm:
   11 |   provider: local
   12 |   # Mac mini-safe default; heavier local models should be enabled only by env override.
   13 |   base-url: ${LLM_BASE_URL:${LLM_3090_BASE_URL:http://127.0.0.1:11435/v1}}
   14 |   api-key: ${LLM_API_KEY:ollama}
   15 |   owner-token: ${LLM_OWNER_TOKEN:}
   16 |   owner-token-header: ${LLM_OWNER_TOKEN_HEADER:X-Owner-Token}
   17 |   chat-model: ${LLM_CHAT_MODEL:gemma4:26b}
```

```text
   22 |   gateway:
   23 |     enabled: ${LLM_GATEWAY_ENABLED:true}
   24 |     enforcement: ${LLM_GATEWAY_ENFORCEMENT:enforce}
   25 |     min-route-score: ${LLM_GATEWAY_MIN_ROUTE_SCORE:55}
   26 |     throw-on-empty-failure: ${LLM_GATEWAY_THROW_ON_EMPTY_FAILURE:false}
   27 |     local-device-failover:
   28 |       enabled: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_ENABLED:true}
   29 |       enforcement: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_ENFORCEMENT:ENFORCE}
   30 |       hard-cooldown-ms: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_HARD_COOLDOWN_MS:600000}
   31 |       runner-failure-window-ms: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_RUNNER_WINDOW_MS:60000}
   32 |       runner-failure-threshold: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_RUNNER_THRESHOLD:2}
   33 |       recovery-successes: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_RECOVERY_SUCCESSES:2}
   34 |       max-endpoints: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_MAX_ENDPOINTS:16}
   35 |       transient-failure-threshold: ${LLM_GATEWAY_LOCAL_TRANSIENT_FAILURE_THRESHOLD:3}
   36 |       transient-cooldown-ms: ${LLM_GATEWAY_LOCAL_TRANSIENT_COOLDOWN_MS:60000}
   37 |       recovery-stable-ms: ${LLM_GATEWAY_LOCAL_RECOVERY_STABLE_MS:30000}
   38 |       probe-timeout-ms: ${LLM_GATEWAY_LOCAL_PROBE_TIMEOUT_MS:60000}
   39 |       health-sample-interval-ms: ${LLM_GATEWAY_LOCAL_HEALTH_SAMPLE_INTERVAL_MS:10000}
   40 |     cloud:
   41 |       enabled: ${LLM_GATEWAY_CLOUD_ENABLED:true}
   42 |       route-key: ${LLM_GATEWAY_CLOUD_ROUTE_KEY:api3}
   43 |     probe:
   44 |       enabled: ${LLM_GATEWAY_PROBE_ENABLED:true}
   45 |       timeout-ms: ${LLM_GATEWAY_PROBE_TIMEOUT_MS:1500}
   46 |       ttl-ms: ${LLM_GATEWAY_PROBE_TTL_MS:30000}
   47 |     spec-registry:
   48 |       enabled: ${LLM_GATEWAY_SPEC_REGISTRY_ENABLED:true}
   49 |       path: ${LLM_GATEWAY_SPEC_REGISTRY_PATH:./data/model-spec-registry.json}
   50 |   fast:
   51 |     base-url: ${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${llm.base-url}}}
   52 |     model: ${LLM_FAST_MODEL:qwen3.5:9b}
   53 |     temperature: 0.0
   54 |     timeout-seconds: 5
   55 |     max-retries: 0
   56 |     max-tokens: 256
```


## S17 — desktop profile의 GPU admission 및 역할 설정

파일: `main/resources/application-desktop-gpu-node.yml`
SHA-256: `9b4621c082b114ee39a2a76486a74e2120b645862ef8a63ac830e667a6df0710`

```text
   37 |     primary-chat-base-url: ${llm.base-url:${LLM_BASE_URL:${LLM_3090_BASE_URL:http://127.0.0.1:11435/v1}}}
   38 |     fast-base-url: ${llm.fast.base-url:${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:http://127.0.0.1:11435/v1}}}
   39 |     embedding-base-url: ${embedding.base-url:${EMBED_BASE_URL:${EMBED_3060_BASE_URL:http://127.0.0.1:11435/api/embed}}}
   40 |     allowed-hosts: ${LLM_PROVIDER_GUARD_ALLOWED_HOSTS:}
   41 |     require-auth-for-remote: ${LLM_PROVIDER_GUARD_REQUIRE_AUTH_FOR_REMOTE:true}
   42 |     owner-token: ${LLM_OWNER_TOKEN:}
   43 |     owner-token-header: ${LLM_OWNER_TOKEN_HEADER:X-Owner-Token}
   44 |   gpu-hardware:
   45 |     telemetry:
   46 |       enabled: ${DESKTOP_GPU_TELEMETRY_ENABLED:true}
   47 |       timeout-ms: ${DESKTOP_GPU_TELEMETRY_TIMEOUT_MS:1000}
   48 |     admission:
   49 |       enabled: ${DESKTOP_GPU_ADMISSION_ENABLED:true}
   50 |       rerank-gate-enabled: ${DESKTOP_GPU_RERANK_ADMISSION_ENABLED:true}
   51 |       memory-warn-threshold: ${DESKTOP_GPU_MEMORY_WARN_THRESHOLD:0.82}
   52 |       memory-block-threshold: ${DESKTOP_GPU_MEMORY_BLOCK_THRESHOLD:0.90}
   53 |       block-when-unavailable: ${DESKTOP_GPU_BLOCK_WHEN_UNAVAILABLE:true}
   54 |   learning-ops:
   55 |     collector:
   56 |       enabled: ${DESKTOP_LEARNING_OPS_COLLECTOR_ENABLED:false}
   57 | 
   58 | local-llm:
   59 |   enabled: true
   60 |   autostart: ${LOCAL_LLM_AUTOSTART:true}
   61 |   cuda-visible-device: ${LOCAL_LLM_CUDA_VISIBLE_DEVICE:}
   62 |   cuda-auto-discover: ${LOCAL_LLM_CUDA_AUTO_DISCOVER:true}
   63 |   warmup:
   64 |     enabled: ${LOCAL_LLM_WARMUP_ENABLED:true}
```

```text
   66 | llm:
   67 |   provider: local
   68 |   base-url: ${LLM_BASE_URL:${LLM_3090_BASE_URL:http://127.0.0.1:11435/v1}}
   69 |   fast:
   70 |     base-url: ${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:http://127.0.0.1:11435/v1}}
   71 |   high:
   72 |     base-url: ${LLM_HIGH_BASE_URL:${LLM_3090_BASE_URL:${llm.base-url}}}
   73 |   judge:
   74 |     base-url: ${LLM_JUDGE_BASE_URL:${LLM_3090_BASE_URL:${llm.high.base-url}}}
   75 |   coder:
   76 |     base-url: ${LLM_CODER_BASE_URL:${LLM_3090_BASE_URL:${llm.high.base-url}}}
   77 |   vision:
   78 |     base-url: ${LLM_VISION_BASE_URL:${LLM_3060_BASE_URL:${llm.fast.base-url}}}
   79 | 
   80 | llmrouter:
   81 |   models:
   82 |     light:
   83 |       enabled: ${LLMROUTER_LIGHT_ENABLED:true}
   84 |       base-url: ${LLMROUTER_LIGHT_BASE_URL:${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:http://127.0.0.1:11435/v1}}}
   85 |       weight: ${LLMROUTER_LIGHT_WEIGHT:0.45}
   86 |       node-role: desktop-gpu-executor
   87 |       device: rtx3060
   88 |       workload: fast-helper
   89 |     gemma:
   90 |       enabled: ${LLMROUTER_GEMMA_ENABLED:true}
   91 |       base-url: ${LLMROUTER_GEMMA_BASE_URL:${LLM_3090_BASE_URL:${llm.base-url}}}
   92 |       weight: ${LLMROUTER_GEMMA_WEIGHT:0.55}
   93 |       node-role: desktop-gpu-executor
   94 |       device: rtx3090
   95 |       workload: primary-chat
   96 |     judge:
   97 |       enabled: ${LLMROUTER_JUDGE_ENABLED:true}
   98 |       base-url: ${LLMROUTER_JUDGE_BASE_URL:${LLM_3090_BASE_URL:${llm.high.base-url}}}
```

```text
  120 |       node-role: macmini-control-plane
  121 |       device: m4-16gb
  122 |       workload: observe-curate-schedule
  123 | 
  124 | embedding:
  125 |   base-url: ${EMBED_BASE_URL:${EMBED_3060_BASE_URL:http://127.0.0.1:11435/api/embed}}
  126 |   cross-gpu-fallback:
  127 |     enabled: ${EMBED_CROSS_GPU_FALLBACK_ENABLED:false}
  128 |   fallback:
  129 |     enabled: ${EMBED_FALLBACK_ENABLED:false}
  130 | 
  131 | onnx:
  132 |   enabled: ${ONNX_ENABLED:false}
  133 |   gpu-id: ${ONNX_DEVICE_ID:${ZSYS_ONNX_GPU_ID:0}}
  134 | 
  135 | zsys:
```


## S18 — 기존 preferred/strict/auto와 검색 UI

파일: `main/resources/templates/chat-ui.html`
SHA-256: `5bcd2ce1d0badc3d3c598a519b80a87218bd9cde1fcd104cbab8e052243bb90f`

```text
  269 |                 
  270 |                 </label>
  271 |                 <button id="modelBrowserTrigger" class="model-browser-trigger" type="button" aria-haspopup="dialog" aria-controls="modelBrowser">모델 탐색 ⌄</button>
  272 |                 <label><span>모델 사용 방식</span>
  273 |                     <select id="modelSelectionMode" aria-label="모델 사용 방식">
  274 |                         <option value="preferred" selected>선택 모델 우선 · 장애 시 자동 우회</option>
  275 |                         <option value="strict">선택 모델만 사용</option>
  276 |                         <option value="auto">자동 선택</option>
  277 |                     </select>
```

```text
  296 |                 <label><span>검색</span>
  297 |                     <select id="searchModeSelect" name="searchMode" aria-label="Search" data-testid="chat-search-mode-select">
  298 |                         <option value="AUTO">자동 검색</option>
  299 |                         <option value="OFF" selected>검색 끔</option>
  300 |                         <option value="FORCE_LIGHT">빠른 검색</option>
  301 |                         <option value="FORCE_DEEP">깊이 검색</option>
  302 |                     </select>
  303 |                 </label>
  304 |                 <label class="toggle-row"><input id="useRagToggle" type="checkbox" aria-label="Use RAG context" data-testid="chat-rag-toggle"><span>RAG</span></label>
  305 |             </div>
```


## S19 — 실제 모델 선택/검색 요청 직렬화

파일: `main/resources/static/js/chat.js`
SHA-256: `c9d59b32710cb6d8be2c1aaf21a9ad20457118b2c67917cb2e6ade571a02d768`

```text
 6420 |     return await sendMessageUnlocked(text);
 6421 |   } finally {
 6422 |     sendMessageInFlight = false;
 6423 |   }
 6424 | }
 6425 | 
 6426 | function modelSelectionPayload() {
 6427 |   const mode = dom.modelSelectionMode?.value || "strict";
 6428 |   return {
 6429 |     model: mode === "auto" ? "llmrouter.auto" : dom.modelSelect?.value || undefined,
 6430 |     strictModelSelection: mode !== "preferred" && mode !== "auto"
 6431 |   };
 6432 | }
 6433 | 
 6434 | async function sendMessageUnlocked(text) {
 6435 |   await waitForPendingStreamCancel();
 6436 |   clearSelectionEntropyTrace(dom.chatMessages);
 6437 |   clearDirectLiteralDiagnosticsSuppression();
 6438 |   state.latestVisibleTurnEvidence = null;
 6439 |   const draftText = text;
 6440 |   let clearDraft = false;
 6441 |   clearActiveRunIdentity();
 6442 |   streamCancelRequested = false;
 6443 |   streamRenderSuppressed = false;
 6444 |   const payload = {
 6445 |     message: text,
 6446 |     question: text,
 6447 |     ...modelSelectionPayload(),
 6448 |     useRag: dom.useRag?.checked ?? true,
 6449 |     useWebSearch: dom.searchModeSelect?.value !== "OFF",
 6450 |     searchMode: dom.searchModeSelect?.value || "AUTO"
 6451 |   };
 6452 |   const currentSessionId = sessionIdFromPayload({ sessionId: state.currentSessionId });
 6453 |   if (currentSessionId) payload.sessionId = currentSessionId;
 6454 |   appendMessage("user", text);
 6455 |   const loaderId = `assistant-${Date.now()}-${++assistantMessageSequence}`;
 6456 |   beginChatTransitionDebugTurn(`turn:${assistantMessageSequence}`, "new-turn");
 6457 |   const assistant = appendMessage("assistant", "");
 6458 |   assistant.id = loaderId;
 6459 |   suppressAnswerOnlyDiagnostics(assistant, draftText);
 6460 |   activeStreamAssistant = assistant;
```


## S20 — 요청 DTO의 기존 strict/polish/useAdaptive 필드

파일: `main/java/com/example/lms/dto/ChatRequestDto.java`
SHA-256: `50dcd8b2789b2e694ba4769b17719e28b804193b2f5ab11b24619ec9b4d9b28f`

```text
   58 | 
   59 |         /**
   60 |          * Optional requested model id.
   61 |          * When null/blank, the server chooses via settings/routing policy.
   62 |          * Prefer logical ids like "llmrouter.auto" if enabled.
   63 |          */
   64 |         private String model;
   65 |         /** Exact manual selection: fail visibly instead of changing provider/model. */
   66 |         private boolean strictModelSelection;
   67 | 
   68 |         private Double temperature;
```

```text
  132 |          * type allows us to distinguish between an explicit {@code false} and an
  133 |          * unspecified value. See ChatApiController.mergeWithSettings() for
  134 |          * normalisation logic.
  135 |          */
  136 |         @JsonProperty("useRag")
  137 |         @JsonAlias("use_rag")
  138 |         @Builder.Default
  139 |         private java.lang.Boolean useRag = null;
  140 | 
  141 |         /**
  142 |          * Flag indicating whether live or hybrid web search should be executed for
  143 |          * this request. As with {@link #useRag}, a {@code null} value means the
  144 |          * client has not set a preference and the server should defer to its
  145 |          * defaults. When the user explicitly turns on web search this field is
  146 |          * set to {@code true} and the {@link #webSearchExplicit} flag is also
  147 |          * flipped to {@code true} via the setter.
  148 |          */
  149 |         @JsonProperty("useWebSearch")
  150 |         @JsonAlias("use_web_search")
  151 |         @Builder.Default
  152 |         private java.lang.Boolean useWebSearch = null;
  153 | 
  154 |         /** true → RAG 단독 실행 / false → 컨텍스트 주입 */
  155 |         private Boolean ragStandalone;
  156 | 
  157 |         @Builder.Default
  158 |         private boolean useAdaptive = false;
  159 |         @Builder.Default
  160 |         private boolean autoTranslate = false;
  161 |         /**
  162 |          * When {@code true} the assistant should refine its answer by performing
  163 |          * additional grammar and style polishing. A {@code null} value indicates
  164 |          * that the client has not specified a preference. This wrapper type
  165 |          * mirrors the flags for retrieval features.
  166 |          */
  167 |         @Builder.Default
  168 |         private java.lang.Boolean polish = null;
  169 | 
  170 |         /**
```

```text
  491 |         }
  492 | 
  493 |         /**
  494 |          * Indicate whether answer polishing has been requested. When the
  495 |          * {@link #polish} flag is {@code null} or {@code false} this returns
  496 |          * {@code false}.
  497 |          *
  498 |          * @return true if polish is explicitly true
  499 |          */
  500 |         public boolean isPolish() {
  501 |                 return java.lang.Boolean.TRUE.equals(this.polish);
  502 |         }
  503 | 
```


## S21 — raw intent를 보존하는 설정 병합

파일: `main/java/com/example/lms/api/ChatRequestSettingsMerger.java`
SHA-256: `8f7a3c6b068945c715f066b506d9cc188ca576b8ef89114423fe5ae65520f3cb`

```text
   84 |         ChatRequestDto.RetrievalRequestIntent retrievalIntent = ui.getRetrievalRequestIntent() != null
   85 |                 ? ui.getRetrievalRequestIntent()
   86 |                 : new ChatRequestDto.RetrievalRequestIntent(ui.getUseWebSearch(), ui.getUseRag());
   87 |         Boolean normUseRag = ui.getUseRag() != null ? ui.getUseRag() : defaultUseRag;
   88 |         Boolean normUseWeb;
   89 |         if (ui.getUseWebSearch() != null) {
   90 |             normUseWeb = ui.getUseWebSearch();
   91 |         } else {
   92 |             String cfgVal = cfg.getOrDefault("chat.defaults.useWebSearch", "false");
   93 |             normUseWeb = Boolean.valueOf(cfgVal);
   94 |         }
   95 |         return ui.toBuilder()
   96 |                 .sessionId(ui.getSessionId())
   97 |                 .message(ui.getMessage())
   98 |                 .history(ui.getHistory())
   99 |                 .mode(ui.getMode())
  100 |                 .memoryMode(ui.getMemoryMode())
  101 |                 .model(model)
  102 |                 .temperature(temperature)
  103 |                 .topP(topP)
  104 |                 .frequencyPenalty(frequencyPenalty)
  105 |                 .presencePenalty(presencePenalty)
  106 |                 .maxTokens(ui.getMaxTokens())
  107 |                 .useVerification(ui.getUseVerification())
  108 |                 .useRag(normUseRag)
  109 |                 .useWebSearch(normUseWeb)
  110 |                 .understandingEnabled(ui.isUnderstandingEnabled())
  111 |                 .searchMode(ui.getSearchMode())
  112 |                 .webProviders(ui.getWebProviders())
  113 |                 .officialSourcesOnly(ui.getOfficialSourcesOnly())
  114 |                 .webTopK(ui.getWebTopK())
  115 |                 .precisionSearch(ui.getPrecisionSearch())
  116 |                 .precisionTopK(ui.getPrecisionTopK())
  117 |                 .accumulation(ui.getAccumulation())
  118 |                 .roleScope(ui.getRoleScope())
  119 |                 .domainProfile(ui.getDomainProfile())
  120 |                 .attachmentIds(ui.getAttachmentIds())
  121 |                 .polish(ui.getPolish())
  122 |                 .webSearchExplicit(ui.getWebSearchExplicit())
  123 |                 .retrievalRequestIntent(retrievalIntent)
  124 |                 .build();
```


## S22 — 답변 cache key와 제한된 캐시 적용 범위

파일: `main/java/com/example/lms/service/ChatService.java`
SHA-256: `0df6c69a7c5f2bb50d548d97f3e2489d3b7f290bec402725ff101ef96d98e5c1`

```text
   57 |     public static String cacheKey(ChatRequestDto req) {
   58 |         if (req == null)
   59 |             return "";
   60 | 
   61 |         MessageDigest digest = sha256();
   62 |         appendFingerprint(digest, "message", req.getMessage());
   63 |         appendFingerprint(digest, "systemPrompt", req.getSystemPrompt());
   64 |         appendFingerprint(digest, "traits", req.getTraits());
   65 |         appendFingerprint(digest, "history", req.getHistory());
   66 |         appendFingerprint(digest, "mode", req.getMode());
   67 |         appendFingerprint(digest, "memoryMode", req.getMemoryMode());
   68 |         appendFingerprint(digest, "model", req.getModel());
   69 |         appendFingerprint(digest, "temperature", req.getTemperature());
   70 |         appendFingerprint(digest, "topP", req.getTopP());
   71 |         appendFingerprint(digest, "frequencyPenalty", req.getFrequencyPenalty());
   72 |         appendFingerprint(digest, "presencePenalty", req.getPresencePenalty());
   73 |         appendFingerprint(digest, "maxTokens", req.getMaxTokens());
   74 |         appendFingerprint(digest, "sessionId", req.getSessionId());
   75 |         appendFingerprint(digest, "useRag", req.getUseRag());
   76 |         appendFingerprint(digest, "useWebSearch", req.getUseWebSearch());
   77 |         appendFingerprint(digest, "useVerification", req.getUseVerification());
   78 |         appendFingerprint(digest, "ragStandalone", req.getRagStandalone());
   79 |         appendFingerprint(digest, "useAdaptive", req.isUseAdaptive());
   80 |         appendFingerprint(digest, "autoTranslate", req.isAutoTranslate());
   81 |         appendFingerprint(digest, "polish", req.getPolish());
   82 |         appendFingerprint(digest, "understandingEnabled", req.isUnderstandingEnabled());
   83 |         appendFingerprint(digest, "inputType", req.getInputType());
   84 |         appendFingerprint(digest, "maxMemoryTokens", req.getMaxMemoryTokens());
   85 |         appendFingerprint(digest, "maxRagTokens", req.getMaxRagTokens());
   86 |         appendFingerprint(digest, "searchMode", req.getSearchMode());
   87 |         appendFingerprint(digest, "webProviders", req.getWebProviders());
   88 |         appendFingerprint(digest, "webTopK", req.getWebTopK());
   89 |         appendFingerprint(digest, "searchQueries", req.getSearchQueries());
   90 |         appendFingerprint(digest, "accumulation", req.getAccumulation());
   91 |         appendFingerprint(digest, "roleScope", req.getRoleScope());
   92 |         appendFingerprint(digest, "domainProfile", req.getDomainProfile());
   93 |         appendFingerprint(digest, "officialSourcesOnly", req.getOfficialSourcesOnly());
   94 |         appendFingerprint(digest, "searchScopes", req.getSearchScopes());
   95 |         appendFingerprint(digest, "precisionSearch", req.getPrecisionSearch());
   96 |         appendFingerprint(digest, "precisionTopK", req.getPrecisionTopK());
   97 |         appendFingerprint(digest, "imageBase64", req.getImageBase64());
   98 |         appendFingerprint(digest, "attachmentIds", req.getAttachmentIds());
   99 |         appendFingerprint(digest, "webSearchExplicit", req.getWebSearchExplicit());
  100 |         ChatRequestDto.RetrievalRequestIntent intent = req.getRetrievalRequestIntent();
  101 |         appendFingerprint(digest, "retrievalIntent.webSearch", intent == null ? null : intent.webSearch());
  102 |         appendFingerprint(digest, "retrievalIntent.rag", intent == null ? null : intent.rag());
  103 |         appendFingerprint(digest, "profile", req.getProfile());
  104 |         appendFingerprint(digest, "guardLevel", req.getGuardLevel());
  105 |         appendFingerprint(digest, "memoryProfile", req.getMemoryProfile());
  106 | 
  107 |         String messageHash = SafeRedactor.hash12(req.getMessage());
  108 |         return "chat:v2:" + (messageHash == null ? "none" : messageHash)
  109 |                 + ":" + HexFormat.of().formatHex(digest.digest());
  110 |     }
  111 | 
  112 |     /**
  113 |      * Only stateless requests with explicit execution choices may reuse a response.
  114 |      */
  115 |     public static boolean isCacheSafe(ChatRequestDto req) {
  116 |         if (req == null
  117 |                 || req.getSessionId() != null
  118 |                 || req.getModel() == null
  119 |                 || req.getModel().isBlank()
  120 |                 || req.getMode() == null
  121 |                 || req.getMode().isBlank()
  122 |                 || !"ephemeral".equalsIgnoreCase(String.valueOf(req.getMemoryMode()).trim())
  123 |                 || req.getTemperature() == null
  124 |                 || req.getTopP() == null
  125 |                 || req.getFrequencyPenalty() == null
  126 |                 || req.getPresencePenalty() == null
  127 |                 || req.getMaxTokens() == null
  128 |                 || !Boolean.FALSE.equals(req.getUseRag())
  129 |                 || !Boolean.FALSE.equals(req.getUseWebSearch())
  130 |                 || !Boolean.FALSE.equals(req.getUseVerification())
  131 |                 || req.isUnderstandingEnabled()
  132 |                 || (req.getHistory() != null && !req.getHistory().isEmpty())
  133 |                 || (req.getAttachmentIds() != null && !req.getAttachmentIds().isEmpty())
  134 |                 || (req.getImageBase64() != null && !req.getImageBase64().isBlank())) {
  135 |             return false;
  136 |         }
  137 | 
  138 |         ChatRequestDto.RetrievalRequestIntent intent = req.getRetrievalRequestIntent();
  139 |         return intent == null
  140 |                 || (Boolean.FALSE.equals(intent.webSearch()) && Boolean.FALSE.equals(intent.rag()));
```


## S23 — SettingsController의 공개 allowlist 및 범용 map 저장

파일: `main/java/com/example/lms/api/SettingsController.java`
SHA-256: `52a915402106e3ca9f4c510392979d6a5eac71b7a768f2352159ba181bbaba5a`

```text
   15 | 
   16 | 
   17 | @RestController
   18 | @RequestMapping("/api/settings")
   19 | @RequiredArgsConstructor
   20 | // ✅ 클래스 이름을 파일명과 동일하게 SettingsController로 수정했습니다.
   21 | public class SettingsController {
   22 | 
   23 |     private static final Set<String> PUBLIC_SETTING_KEYS = Set.of();
   24 | 
   25 |     private final ConfigurationSettingRepository configurationSettingRepository;
   26 | 
   27 |     /**
   28 |      * 모든 설정을 'configuration_settings' 테이블에서 조회합니다.
   29 |      * 이제 UI를 열 때 항상 최신 설정값을 불러옵니다.
   30 |      *
   31 |      * @return DB에 저장된 모든 설정을 Key-Value 형태의 Map으로 반환
   32 |      */
   33 |     @GetMapping
   34 |     public ResponseEntity<Map<String, String>> getAllSettings() {
   35 |         Map<String, String> settingMap = PUBLIC_SETTING_KEYS.stream()
   36 |                 .map(configurationSettingRepository::findById)
   37 |                 .flatMap(Optional::stream)
   38 |                 .collect(Collectors.toMap(
   39 |                         ConfigurationSetting::getSettingKey,
   40 |                         ConfigurationSetting::getSettingValue
   41 |                 ));
   42 | 
```

```text
   53 |     @PostMapping
   54 |     @Transactional // 여러 건의 저장을 하나의 트랜잭션으로 처리
   55 |     public ResponseEntity<Map<String, String>> saveAllSettings(@RequestBody Map<String, String> settings) {
   56 | 
   57 |         if (settings == null) {
   58 |             return ResponseEntity.badRequest().body(Map.of("message", "settings body is required"));
   59 |         }
   60 | 
   61 |         settings.forEach((k, v) -> {
   62 |             ConfigurationSetting entity =
   63 |                     configurationSettingRepository
   64 |                             .findById(k)                       // ① 존재 여부 확인
   65 |                             .orElseGet(() ->                  // ② 없으면 새로 생성
   66 |                                     new ConfigurationSetting(k, null));
   67 | 
   68 |             entity.setSettingValue(v);                    // ③ 값만 갱신
   69 |             configurationSettingRepository.save(entity);  //   INSERT 또는 UPDATE
   70 |         });
   71 | 
   72 |         return ResponseEntity.ok(
   73 |                 Map.of("message", "설정이 저장되었습니다.")
   74 |         );
```


## S24 — KG handler의 성공/빈 결과/실패 관측과 fail-soft

파일: `main/java/com/example/lms/service/rag/handler/KnowledgeGraphRetrievalHandler.java`
SHA-256: `c34af4e1c714ec65aa6d36fd8ab88ff71036b64d3a94c0732ac6fada20409d8d`

```text
   29 |     protected boolean doHandle(Query query, List<Content> accumulator) {
   30 |         long started = System.nanoTime();
   31 |         int before = accumulator == null ? -1 : accumulator.size();
   32 |         String queryHash12 = query == null ? "" : blankToDefault(SafeRedactor.hash12(query.text()), "");
   33 |         try {
   34 |             if (delegate == null) {
   35 |                 recordKgFixedChain("skipped", false, 0, 0, "delegate_unavailable", "",
   36 |                         elapsedMs(started), queryHash12);
   37 |                 return true;
   38 |             }
   39 |             if (accumulator == null) {
   40 |                 recordKgFixedChain("skipped", true, 0, 0, "accumulator_unavailable", "",
   41 |                         elapsedMs(started), queryHash12);
   42 |                 return true;
   43 |             }
   44 |             List<Content> results = delegate.retrieve(query);
   45 |             int returnedCount = results == null ? 0 : results.size();
   46 |             int addedCount = 0;
   47 |             if (results != null && !results.isEmpty()) {
   48 |                 accumulator.addAll(results);
   49 |                 addedCount = Math.max(0, accumulator.size() - before);
   50 |             }
   51 |             recordKgFixedChain(returnedCount > 0 ? "success" : "empty", true, returnedCount, addedCount,
   52 |                     returnedCount > 0 ? "" : "delegate_empty", "", elapsedMs(started), queryHash12);
   53 |         } catch (Exception ex) {
   54 |             String failureClass = failureClass(ex);
   55 |             recordKgFixedChain("failed", delegate != null, 0, 0, "delegate_exception",
   56 |                     failureClass, elapsedMs(started), queryHash12);
   57 |             log.warn("[KG][fixed-chain] failed; continuing retrieval chain failureClass={}",
   58 |                     failureClass);
   59 |         }
   60 |         return true;
```

```text
   74 |         try {
   75 |             TraceStore.put("retrieval.kg.fixedChain.status", safeStatus);
   76 |             TraceStore.put("retrieval.kg.fixedChain.enabled", enabled);
   77 |             TraceStore.put("retrieval.kg.fixedChain.returnedCount", Math.max(0, returnedCount));
   78 |             TraceStore.put("retrieval.kg.fixedChain.addedCount", Math.max(0, addedCount));
   79 |             TraceStore.put("retrieval.kg.fixedChain.disabledReason", safeDisabledReason);
   80 |             TraceStore.put("retrieval.kg.fixedChain.failureClass", safeFailureClass);
   81 |             TraceStore.put("retrieval.kg.fixedChain.failSoft", "failed".equals(safeStatus));
   82 |             TraceStore.put("retrieval.kg.fixedChain.tookMs", Math.max(0L, tookMs));
```


## S25 — 기존 generation fallback 시도·예산 경계

파일: `main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java`
SHA-256: `58007f7298d9ad5cd9701a01080bc7f895fa9d78039a561c5c1ae79b3a4fc8a3`

```text
  208 |             ResolvedFallback resolvedFallback = nextFallbackResolver == null ? resolveFallback(failureClass)
  209 |                     : nextFallbackResolver.resolve(failureClass, Set.copyOf(usedRoutes));
  210 |             com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
  211 |             if (resolvedFallback == null || resolvedFallback.model() == null) {
  212 |                 TraceStore.put("llm.gateway.fallbackAware.sameRequestRetry", false);
  213 |                 TraceStore.put("llm.gateway.fallback.started", false);
  214 |                 TraceStore.put("llm.gateway.fallback.skippedReason", "no_eligible_fallback");
  215 |                 throw terminalFailure(ex);
  216 |             }
  217 |             if (nextFallbackResolver != null && (resolvedFallback.routeKey() == null
  218 |                     || !usedRoutes.add(resolvedFallback.routeKey()))) {
  219 |                 TraceStore.put("llm.gateway.fallback.skippedReason", "route_cycle");
  220 |                 throw terminalFailure(ex);
  221 |             }
  222 |             if (breadcrumbs != null) {
  223 |                 breadcrumbs.publishFallback(
  224 |                         primaryKey,
  225 |                         resolvedFallback.routeKey(),
  226 |                         failureClass,
  227 |                         nextFallbackResolver == null ? "same_request_retry_once" : "bounded_provider_failover");
  228 |             }
  229 |             ChatModel fallback = resolvedFallback.model();
  230 |             if (requestBudget != null) {
  231 |                 long remainingMs = Math.max(0L, requestBudget.remainingMillis());
  232 |                 TraceStore.put("llm.gateway.fallback.remainingMs", remainingMs);
  233 |                 if (remainingMs <= 0L) {
  234 |                     TraceStore.put("llm.gateway.fallback.started", false);
  235 |                     TraceStore.put("llm.gateway.fallback.skippedReason", "request_deadline_exhausted");
  236 |                     throw terminalFailure(ex);
  237 |                 }
  238 |             }
  239 |             TraceStore.put("llm.gateway.fallback.started", true);
  240 |             TraceStore.put("llm.gateway.fallback.skippedReason", "none");
  241 |             TraceStore.put("llm.gateway.fallback.count", fallbackNumber + 1);
  242 |             TraceStore.put("llm.gateway.fallback.selectedRoute",
  243 |                     com.example.lms.trace.SafeRedactor.traceLabelOrFallback(resolvedFallback.routeKey(), "unknown"));
  244 |             LOG.info("[llm-failover] route={} fallbackCount={} cause={}",
  245 |                     com.example.lms.trace.SafeRedactor.traceLabelOrFallback(resolvedFallback.routeKey(), "unknown"),
  246 |                     fallbackNumber + 1, failureClass.name());
  247 |             ModelRuntimeHealthTracker.RequestAttemptRoute fallbackRoute = resolvedFallback.route();
  248 |             int fallbackAttemptTotalBefore = requestAttemptTotal();
  249 |             long fallbackStartedNanos = System.nanoTime();
  250 |             try {
  251 |                 com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
  252 |                 ChatResponse response = invokeModel(fallback, frozen, request);
  253 |                 requireAnswer(response);
  254 |                 recordAttempt(
  255 |                         "fallback",
  256 |                         fallbackRoute,
  257 |                         LlmFailureClass.NONE,
  258 |                         elapsedMs(fallbackStartedNanos),
  259 |                         fallbackAttemptTotalBefore);
  260 |                 TraceStore.put("llm.gateway.fallback.latencyMs", elapsedMs(fallbackStartedNanos));
  261 |                 recordCompletion(resolvedFallback.routeKey(), fallbackNumber + 1, elapsedMs(primaryStartedNanos));
  262 |                 try { maskedFallbackReporter.accept(primaryKey, resolvedFallback.routeKey()); }
  263 |                 catch (RuntimeException ignored) { TraceStore.put("llm.gateway.fallback.maskedReportFailed", true); }
  264 |                 return response;
  265 |             } catch (RuntimeException fallbackFailure) {
  266 |                 LlmResponseTerminalException.rethrowIfPresent(fallbackFailure);
  267 |                 if (hasGatewayReason(fallbackFailure, "failover_exhausted")) throw fallbackFailure;
  268 |                 recordAttempt(
  269 |                         "fallback",
  270 |                         fallbackRoute,
  271 |                         classifyFailure(fallbackFailure),
  272 |                         elapsedMs(fallbackStartedNanos),
  273 |                         fallbackAttemptTotalBefore);
  274 |                 if (nextFallbackResolver == null || !fallbackAllowed(fallbackFailure, classifyFailure(fallbackFailure)))
  275 |                     throw fallbackFailure;
  276 |                 ex = fallbackFailure;
  277 |                 failureClass = classifyFailure(fallbackFailure);
  278 |             }
  279 |             }
  280 |             throw terminalFailure(ex);
  281 |         }
```

```text
  284 |     /** Forward the full request; a legacy list-only delegate keeps its original entry point. */
  285 |     private static ChatResponse invokeModel(ChatModel model, List<ChatMessage> messages, ChatRequest request) {
  286 |         if (request == null || messages == null || messages.isEmpty()) {
  287 |             return model.chat(messages);
  288 |         }
  289 |         try {
  290 |             return model.chat(request);
  291 |         } catch (RuntimeException failure) {
  292 |             if (isMissingDoChatContract(failure)) {
  293 |                 return model.chat(messages);
  294 |             }
  295 |             throw failure;
  296 |         }
  297 |     }
  298 | 
  299 |     static boolean isMissingDoChatContract(RuntimeException failure) {
  300 |         if (failure == null
  301 |                 || failure.getClass() != RuntimeException.class
  302 |                 || !"Not implemented".equals(failure.getMessage())) {
  303 |             return false;
  304 |         }
  305 |         // Only the interface-default doChat produces this exact throw before any
  306 |         // transport; a provider error raised inside a real doChat must propagate.
  307 |         StackTraceElement[] frames = failure.getStackTrace();
  308 |         return frames.length > 0
  309 |                 && "dev.langchain4j.model.chat.ChatModel".equals(frames[0].getClassName())
```


## S26 — 기존 요청 cancellation/interruptibleCall

파일: `main/java/com/example/lms/service/chat/ChatRunExecutionContext.java`
SHA-256: `5b9a45a78eed93022a705fa369c2e85a587dd62edff1b84b3dedf18848606052`

```text
   43 |     public String redactedRunIdentity() {
   44 |         return com.example.lms.trace.SafeRedactor.hashValue(runId);
   45 |     }
   46 | 
   47 |     public static void throwIfCancelled() {
   48 |         ChatRunExecutionContext run = current();
   49 |         if (Thread.currentThread().isInterrupted() || (run != null && !run.admitCall(() -> {}))) {
   50 |             throw new java.util.concurrent.CancellationException("exact chat run cancelled");
   51 |         }
   52 |     }
   53 | 
   54 |     /** Cap this wait against the original request deadline; zero never means unlimited I/O. */
   55 |     public static long capRequestWait(long configuredMillis) {
   56 |         throwIfCancelled();
   57 |         var budget = com.abandonware.ai.addons.budget.TimeBudgetContext.get();
   58 |         long remaining = budget == null ? configuredMillis : budget.capWaitMillis(configuredMillis);
   59 |         if (remaining <= 0) {
   60 |             throw new com.example.lms.llm.gateway.LlmGatewayException("request budget exhausted",
   61 |                     com.example.lms.llm.gateway.LlmFailureClass.TIMEOUT_SOFT, "request_budget_exhausted");
   62 |         }
   63 |         return remaining;
   64 |     }
   65 | 
   66 |     /** Scope must be opened on the blocking caller, never on a shared I/O event loop. */
   67 |     public static BlockingCall interruptibleCall(String transport) {
   68 |         capRequestWait(Long.MAX_VALUE);
   69 |         return new BlockingCall(current(), Thread.currentThread(), transport);
   70 |     }
   71 | 
   72 |     public static final class BlockingCall implements AutoCloseable {
   73 |         private final ChatRunExecutionContext run;
   74 |         private final String id = java.util.UUID.randomUUID().toString();
   75 |         private final java.util.function.LongConsumer observer;
   76 | 
   77 |         private BlockingCall(ChatRunExecutionContext run, Thread caller, String transport) {
   78 |             this.run = run;
   79 |             String kind = java.util.Set.of("jdk_http", "ollama_native", "ollama_embedding").contains(transport)
   80 |                     ? transport : "http";
   81 |             observer = at -> {
   82 |                 caller.interrupt();
   83 |                 org.slf4j.LoggerFactory.getLogger("com.example.lms.llm.ModelRuntimeHealthTracker.requestProof")
   84 |                         .info("[LLM_TRANSPORT_CANCEL] runHash={} transport={} forwarding=caller_interrupt providerCompletion=not_observed",
   85 |                                 run.redactedRunIdentity(), kind);
   86 |             };
   87 |             if (run != null && !run.registry.registerCancellationObserver(run, id, observer)) {
   88 |                 throw new java.util.concurrent.CancellationException("exact run transport admission unavailable");
   89 |             }
   90 |             try { throwIfCancelled(); }
   91 |             catch (RuntimeException cancelled) { close(); throw cancelled; }
   92 |         }
   93 | 
   94 |         @Override public void close() {
   95 |             // Removal shares the cancellation gate; no callback can interrupt this thread after close returns.
   96 |             if (run != null) run.registry.removeCancellationObserver(run, id, observer);
   97 |         }
   98 |     }
   99 | 
  100 |     public boolean tryBeginCommit() {
  101 |         return registry.tryBeginCommit(this);
  102 |     }
```

```text
  126 |     }
  127 | 
  128 |     public boolean runTerminalSideEffect(Runnable action) {
  129 |         return registry.runTerminalSideEffect(this, action);
  130 |     }
  131 | 
  132 |     public boolean permitsEmission() {
  133 |         return registry.permitsEmission(this);
  134 |     }
  135 | 
  136 |     public boolean sameRun(ChatRunExecutionContext other) {
  137 |         return other != null
  138 |                 && registry == other.registry
  139 |                 && sessionId.equals(other.sessionId)
  140 |                 && runId.equals(other.runId);
  141 |     }
  142 | 
  143 |     /** Opaque capability sent only to an authorized client for exact cancel/attach. */
  144 |     public String clientToken() {
  145 |         return runId;
  146 |     }
  147 | 
  148 |     public boolean belongsToSession(Long expectedSessionId) {
  149 |         return expectedSessionId != null && sessionId.equals(expectedSessionId);
  150 |     }
  151 | 
  152 |     public boolean registerCancellationHandle(Disposable handle) {
  153 |         return registry.registerCancellationHandle(this, handle);
  154 |     }
  155 | 
  156 |     public boolean awaitClientAcknowledgement(long timeoutMillis) {
```


## S27 — 실제 구현이 아닌 이름만 있는 파일

파일: `main/java/com/example/lms/service/RagRetrievalService.java`
SHA-256: `a3bfd01886c1956d65abc1503de2ef85875dc3445e9a8cd7e76f5373cfa6fa04`

```text
    1 | package com.example.lms.service;
```


## S28 — 기존 로컬 BM25 점수기

파일: `main/java/com/example/lms/service/rag/retriever/LocalBm25Retriever.java`
SHA-256: `41b3ccac629a711fc5ffd08df54e66ae4f4de17e3e837b82852a70e016738fa4`

```text
    1 | package com.example.lms.service.rag.retriever;
    2 | 
    3 | import java.util.*;
    4 | import java.util.concurrent.ConcurrentHashMap;
    5 | 
    6 | /** Minimal dependency-free BM25-like scorer.
    7 |  *  Toggle: retrieval.localbm25.enabled
    8 |  *  This is a light in-memory index for small corpora or tests.
    9 |  */
   10 | public class LocalBm25Retriever {
   11 | 
   12 |     public static class Doc {
   13 |         public final String id;
   14 |         public final String text;
   15 |         public Doc(String id, String text){ this.id=id; this.text=text==null?"":text; }
   16 |     }
   17 | 
   18 |     private final Map<String, Integer> df = new ConcurrentHashMap<>();
   19 |     private final List<Doc> docs = Collections.synchronizedList(new ArrayList<>());
   20 |     private double avgLen = 0d;
   21 | 
   22 |     public void add(Doc d){
   23 |         docs.add(d);
   24 |         String[] terms = tokenize(d.text);
   25 |         Set<String> seen = new HashSet<>();
   26 |         for (String t: terms){
   27 |             if(seen.add(t)){
   28 |                 df.merge(t, 1, Integer::sum);
   29 |             }
   30 |         }
   31 |         avgLen = docs.stream().mapToInt(x -> tokenize(x.text).length).average().orElse(1d);
   32 |     }
   33 | 
   34 |     public List<Doc> topK(String query, int k){
   35 |         if (k <= 0) {
   36 |             return List.of();
   37 |         }
   38 |         String[] q = tokenize(query);
   39 |         if (q.length == 0) {
   40 |             return List.of();
   41 |         }
   42 |         double N = Math.max(1, docs.size());
   43 |         List<Scored> scored = new ArrayList<>();
   44 |         for (Doc d: docs){
   45 |             double score = 0d;
   46 |             String[] terms = tokenize(d.text);
   47 |             Map<String,Integer> tf = new HashMap<>();
   48 |             for(String t: terms) tf.merge(t,1,Integer::sum);
   49 |             double dl = Math.max(1, terms.length);
   50 |             for(String term: q){
   51 |                 int ni = df.getOrDefault(term, 0);
   52 |                 if(ni==0) continue;
   53 |                 double idf = Math.log1p((N - ni + 0.5) / (ni + 0.5));
   54 |                 double tfv = tf.getOrDefault(term, 0);
   55 |                 double k1 = 1.2, b = 0.75;
   56 |                 double denom = tfv + k1 * (1 - b + b * (dl / Math.max(1, avgLen)));
   57 |                 score += idf * ((tfv * (k1+1)) / Math.max(1e-6, denom));
   58 |             }
   59 |             if(score>0) scored.add(new Scored(d, score));
   60 |         }
   61 |         scored.sort((a,b)->Double.compare(b.s, a.s));
   62 |         List<Doc> out = new ArrayList<>();
   63 |         for(int i=0;i<Math.min(k, scored.size());i++) out.add(scored.get(i).d);
   64 |         return out;
   65 |     }
   66 | 
   67 |     private static class Scored{ Doc d; double s; Scored(Doc d,double s){this.d=d;this.s=s;} }
   68 |     private String[] tokenize(String t){
   69 |         if (t == null || t.isBlank()) {
   70 |             return new String[0];
   71 |         }
   72 |         String normalized = t.toLowerCase(Locale.ROOT)
   73 |                 .replaceAll("[^\\p{L}\\p{N}]+", " ")
   74 |                 .trim();
   75 |         if (normalized.isEmpty()) {
   76 |             return new String[0];
   77 |         }
   78 |         return Arrays.stream(normalized.split("\\s+"))
   79 |                 .filter(term -> !term.isBlank())
   80 |                 .toArray(String[]::new);
   81 |     }
   82 | }
```


## S29 — LocalBm25Retriever 이름 참조 스캔

대상은 `main/java/com/example/lms/**/*.java`이다. 이 이름의 참조 확인이며 런타임 DI 그래프 전체를 증명하는 검사는 아니다.
```text
main/java/com/example/lms/assist/ConversateAnswerPipeline.java:3 | import com.example.lms.service.rag.retriever.LocalBm25Retriever;
main/java/com/example/lms/assist/ConversateAnswerPipeline.java:133 | var index=new LocalBm25Retriever();var sentences=new LinkedHashMap<String,Sentence>();
main/java/com/example/lms/assist/ConversateAnswerPipeline.java:136 | String key="s"+sentences.size(),normalized=normalize(text);sentences.put(key,new Sentence(key,material.sourceId(),text,normalized));index.add(new LocalBm25Retriever.Doc(key,normalized));
main/java/com/example/lms/assist/ConversateApiCueService.java:9 | import com.example.lms.service.rag.retriever.LocalBm25Retriever;
main/java/com/example/lms/assist/ConversateApiCueService.java:116 | var index=new LocalBm25Retriever();var prepared=new LinkedHashMap<String,PreparedMaterialReader.Material>();
main/java/com/example/lms/assist/ConversateApiCueService.java:117 | if(!publicDisplay)for(var material:materials){String id="p"+prepared.size();prepared.put(id,material);index.add(new LocalBm25Retriever.Doc(id,material.text()));}
```

현재 브라우저 RAG에 BM25 fallback이 완성돼 있다고 단정하지 말고, 실제 문서 저장소·ACL·locator 연결을 확인한다.
