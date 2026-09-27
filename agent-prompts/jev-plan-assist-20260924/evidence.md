# maasxin.zip — Jev 통합 정적 분석 근거

작성일: 2026-09-24 (Asia/Seoul)

## 0. 분석 범위와 한계

사용자가 제공한 ZIP을 경로 이탈 검사 후 추출하여, Jev 통합에 직접 관련된 Java/설정/계획 호출 경로를 정적으로 추적했다. 모든 Java 파일을 줄 단위로 전수 검증한 보고서가 아니다. 실제 Windows 서버, API 자격 증명, 라이브 Jev 평가, 빌드, 테스트, 브라우저 실행은 이 분석에서 수행하지 않았다. 원본 소스는 수정하지 않았다.

- 입력 파일: `maasxin.zip`
- SHA-256: `e8ab2751094a06ffe63d594dbbdee5c962693f41170daee911793039f7619eff`
- 파일 수: 2,371 (디렉터리 항목 제외)
- Java 파일 수: 2,161
- 압축 해제 크기: 21,529,804 bytes
- 아카이브 루트: `main/`
- `AGENTS.md`, Gradle build/wrapper, `test/`, `scripts/jev_gateway_smoke.mjs`는 이 ZIP에 없다.
- 이 ZIP의 텍스트 파일에서 Jev 식별자(`jev`, `typesafe`, `/v1/evaluate`)에 해당하는 제품 통합은 확인되지 않았다.
- 따라서 Devin이 남겼다고 보고한 스모크 스크립트, 실제 401 응답, 저널/리스/검증 기록은 붙여넣은 보고서의 주장이지 이 ZIP으로 독립 확인한 사실이 아니다.
- 모든 아래 경로와 줄 번호는 업로드된 ZIP 스냅샷 기준이다. 실행 에이전트는 현재 C-root에서 같은 심볼을 다시 확인해야 한다.

## 1. 핵심 판정

1. 기본 `GuardContext`는 `planId="safe"`, `headerMode="S1"`를 채운다. `ensurePlanSelected`는 이미 있는 planId면 조기 반환한다. 기본 safe와 사용자가 지정한 safe를 구분하지 않으면 새 advice가 실행되지 않거나 명시적 설정을 침범한다. OFF 상태의 기존 동작은 바꾸지 않아야 한다.
2. 계획 선택은 실제 스트리밍, 일반 채팅, 서비스 계층과 예산 projection에서 호출된다. 하나의 메서드에 외부 요청을 무조건 넣으면 예산 점검에도 네트워크가 생길 수 있다.
3. `ChatWorkflow` 진입은 TraceStore를 비운다. TraceStore에만 Future/중복 방지 상태를 보관하면 controller → workflow 동안 유실될 수 있다.
4. 기존 AOP가 반환값을 보정하므로 proposed plan과 최종 적용 plan을 구분해야 한다. typed 인자를 사용하고 추가 String 인자에 기대지 않는다.
5. `RouterDecisionCache` leader는 `supplier.get()`을 동기 실행한다. future timeout 설정만으로 동기 네트워크 blocking이 사라지는 것은 아니다.
6. 근거 0의 일반 답변 허용과 명시적 evidence_needed 예외가 이미 존재한다. Jev 제안을 새로운 검증 근거나 안전 우회 권한으로 사용하지 않는다.

## 2. 코드 발췌

아래 발췌는 소스 텍스트를 그대로 인용한다. 원본에 있는 주석 인코딩 문제도 남아 있을 수 있다. 줄 번호는 편집 후 달라질 수 있다.

### E01. 기본 safe와 조기 반환: 기본값을 사용자 지정으로 오인하지 말 것

`main/java/com/example/lms/service/guard/GuardContext.java:724–751`

```text
724:      * Create a default, S1-style safe context.
725:      */
726:     public static GuardContext defaultContext() {
727:         GuardContext ctx = new GuardContext();
728:         ctx.planId = "safe";
729:         ctx.mode = "safe";
730:         ctx.engine = "default";
731:         ctx.fusionScore = 0.0;
732:         ctx.onnxScore = 0.0;
733:         ctx.officialOnly = false;
734:         ctx.highRiskQuery = false;
735:         ctx.entityQuery = false;
736:         ctx.memoryProfile = "MEMORY";
737:         ctx.headerMode = "S1";
738:         ctx.guardLevel = "BALANCED";
739:         ctx.webPrimary = null;
740:         ctx.irregularityScore = 0.0;
741:         ctx.compressionMode = false;
742:         ctx.strikeMode = false;
743:         ctx.bypassMode = false;
744:         ctx.webRateLimited = false;
745:         ctx.cheapSearchMode = false;
746:         ctx.bypassReason = null;
747:         ctx.interactionPolicyDecision = InteractionEvidencePolicy.offDecision();
748:         ctx.interactionPolicyFacts = List.of();
749:         ctx.interactionSuspectEvidenceIds = Set.of();
750:         return ctx;
751:     }
```

`main/java/com/example/lms/orchestration/WorkflowOrchestrator.java:50–83`

```text
50:     public String ensurePlanSelected(GuardContext ctx, AnswerMode answerMode,
51:                                     QueryDomain domain, String userQuery) {
52:         return ensurePlanSelected(ctx, answerMode, domain, userQuery, false);
53:     }
54: 
55:     public String ensurePlanSelected(GuardContext ctx, AnswerMode answerMode,
56:                                     QueryDomain domain, String userQuery,
57:                                     boolean hasAttachments) {
58:         if (ctx == null) return null;
59:         if (ctx.getPlanId() != null && !ctx.getPlanId().isBlank()) {
60:             return ctx.getPlanId();
61:         }
62: 
63:         String selected = enabled
64:                 ? selectPlan(ctx, answerMode, domain, userQuery, hasAttachments)
65:                 : defaultPlanId;
66: 
67:         if (selected == null || selected.isBlank()) {
68:             selected = defaultPlanId;
69:         }
70: 
71:         // PlanHintApplier로 정규화
72:         try {
73:             selected = planHintApplier.load(selected).planId();
74:         } catch (Exception ex) {
75:             LOG.log(System.Logger.Level.DEBUG,
76:                     "Workflow plan normalization skipped stage=plan_hint_load errorType="
77:                             + ex.getClass().getSimpleName());
78:         }
79: 
80:         ctx.setPlanId(selected);
81:         TraceStore.put("plan.auto", selected);
82:         return selected;
83:     }
```

### E02. 기존 규칙 기반 계획 선택: baseline과 추천 후보를 구별

`main/java/com/example/lms/orchestration/WorkflowOrchestrator.java:85–154`

```text
85:     private String selectPlan(GuardContext ctx, AnswerMode answerMode,
86:                               QueryDomain domain, String userQuery,
87:                               boolean hasAttachments) {
88:         // Priority 1) SENSITIVE → safe
89:         if (domain == QueryDomain.SENSITIVE) {
90:             return safePlanId;
91:         }
92: 
93:         if (looksFreeCostHint(ctx)) {
94:             return "ap9_cost_saver.v1";
95:         }
96: 
97:         // ✅ MoE YELLOW: GAME/SUBCULTURE → creative/community plan (avoid official-only lock)
98:         // Priority 2) explicit brave/creative hint (header/mode)
99:         if (looksBraveHint(ctx)) {
100:             return creativePlanId;
101:         }
102: 
103:         // Priority 3) uploaded-document questions use a document evidence plan unless
104:         // an explicit header/plan already selected a different route.
105:         if (hasAttachments && ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion(userQuery)) {
106:             TraceStore.put("plan.documentEvidence", true);
107:             return documentPlanId;
108:         }
109: 
110:         if (domain == QueryDomain.GAME || domain == QueryDomain.SUBCULTURE) {
111:             TraceStore.put("moe.color", "YELLOW");
112:             return creativePlanId;
113:         }
114: 
115:         // Priority 3) CREATIVE mode → brave
116:         if (answerMode == AnswerMode.CREATIVE) {
117:             return creativePlanId;
118:         }
119: 
120:         // Priority 4) recency queries → recency_first
121:         if (looksRecency(userQuery)) {
122:             return recencyPlanId;
123:         }
124: 
125:         // Below: lightweight AP routing (MoE-ish)
126: 
127:         // ap9: cheap/fast
128:         if (looksCostOrFast(userQuery)) {
129:             return "ap9_cost_saver.v1";
130:         }
131: 
132:         // ap11: finance specialization
133:         if (looksFinance(userQuery)) {
134:             return "ap11_finance_special.v1";
135:         }
136: 
137:         // ap1: legal/government/official
138:         if (looksLegalOrOfficial(userQuery)) {
139:             return "ap1_auth_web.v1";
140:         }
141: 
142:         // ap3: code / stack traces / debugging
143:         if (looksCodeOrStackTrace(userQuery)) {
144:             return "ap3_vec_dense.v1";
145:         }
146: 
147:         // kg_first: entity-ish / graph-ish
148:         if (ctx.isEntityQuery()) {
149:             return entityPlanId;
150:         }
151: 
152:         // default
153:         return safePlanId;
154:     }
```

### E03. 실제 스트리밍 실행: pre-search 전에 계획 선택

`main/java/com/example/lms/api/ChatApiController.java:1788–1818`

```text
1788:             GuardContext gctx = GuardContext.defaultContext();
1789:             attachSelectionState(gctx, selectionEntropy, selectionDecisionLedger);
1790:             if (_jamminiMode != null && !_jamminiMode.isBlank()) {
1791:                 gctx.setHeaderMode(_jamminiMode);
1792:                 gctx.setMode(_jamminiMode);
1793:                 gctx.setPlanId(_jamminiMode);
1794:                 if ("S1".equalsIgnoreCase(_jamminiMode) || "safe".equalsIgnoreCase(_jamminiMode)) {
1795:                     gctx.setMemoryProfile("MEMORY");
1796:                 } else if ("S2".equalsIgnoreCase(_jamminiMode)
1797:                         || "brave".equalsIgnoreCase(_jamminiMode)
1798:                         || "free".equalsIgnoreCase(_jamminiMode)
1799:                         || "zero_break".equalsIgnoreCase(_jamminiMode)) {
1800:                     gctx.setMemoryProfile("NONE");
1801:                 }
1802:             }
1803:             if (_guardLevel != null && !_guardLevel.isBlank()) {
1804:                 gctx.setGuardLevel(_guardLevel);
1805:             }
1806:             if (req != null && req.getMessage() != null) {
1807:                 gctx.setEntityQueryFromQuestion(req.getMessage());
1808: 				// UAW: propagate raw user query for downstream orchestration/unmasking/autolearn hooks
1809: 				gctx.setUserQuery(req.getMessage());
1810:             }
1811:             GuardContextHolder.set(gctx);
1812:             String requestTimelineId = null;
1813:             try {
1814:                 // 1) ???깆젧 ?곌랜理묌뜮?
1815:                 ChatRequestDto dto = mergeWithSettings(req);
1816:                 publicRequestBudgetGuard.validateChatEffective(dto);
1817:                 final boolean __hasAttachments = hasAttachments(dto);
1818:                 final boolean __looksLikeAttachmentQ =
```

`main/java/com/example/lms/api/ChatApiController.java:1829–1849`

```text
1829: 
1830:                 // DROP: apply plan selection + guard hints BEFORE web prefetch/search.
1831:                 PlanHints __planHints = null;
1832:                 boolean __allowWebCap = true;
1833:                 boolean __allowRagCap = true;
1834:                 try {
1835:                     AnswerMode __am = AnswerMode.fromString(dto.getMode());
1836:                     QueryDomain __qd = (gctx != null && gctx.isSensitiveTopic()) ? QueryDomain.SENSITIVE : QueryDomain.GENERAL;
1837:                     if (workflowOrchestrator != null) {
1838:                         workflowOrchestrator.ensurePlanSelected(gctx, __am, __qd, dto.getMessage(), __hasAttachments);
1839:                     }
1840:                     if (planHintApplier != null && gctx != null && gctx.getPlanId() != null) {
1841:                         __planHints = planHintApplier.load(gctx.getPlanId());
1842:                         planHintApplier.applyToGuardContext(__planHints, gctx);
1843:                     }
1844:                     __allowWebCap = (__planHints == null || __planHints.allowWeb() != Boolean.FALSE);
1845:                     __allowRagCap = (__planHints == null || __planHints.allowRag() != Boolean.FALSE);
1846:                     TraceStore.put("plan.id.preSearch", (gctx == null ? null : gctx.getPlanId()));
1847:                     TraceStore.put("plan.allowWeb.cap", __allowWebCap);
1848:                     TraceStore.put("plan.allowRag.cap", __allowRagCap);
1849:                 } catch (Exception ignorePlan) {
```

### E04. 일반 채팅 실행: 기본 컨텍스트와 별도 계획 선택

`main/java/com/example/lms/api/ChatApiController.java:3960–3980`

```text
3960:         SelectionDecisionLedger selectionDecisionLedger = selectionState.ledger();
3961:         GuardContext ctx = GuardContext.defaultContext();
3962:         attachSelectionState(
3963:                 ctx,
3964:                 selectionEntropy,
3965:                 selectionDecisionLedger);
3966:         if (jamminiMode != null && !jamminiMode.isBlank()) {
3967:             ctx.setHeaderMode(jamminiMode);
3968:             ctx.setMode(jamminiMode);
3969:             // Simple plan mapping; can be refined to safe_autorun.v1 / brave.v1 etc.
3970:             ctx.setPlanId(jamminiMode);
3971:             if ("S1".equalsIgnoreCase(jamminiMode) || "safe".equalsIgnoreCase(jamminiMode)) {
3972:                 ctx.setMemoryProfile("MEMORY");
3973:             } else if ("S2".equalsIgnoreCase(jamminiMode)
3974:                     || "brave".equalsIgnoreCase(jamminiMode)
3975:                     || "free".equalsIgnoreCase(jamminiMode)
3976:                     || "zero_break".equalsIgnoreCase(jamminiMode)) {
3977:                 ctx.setMemoryProfile("NONE");
3978:             }
3979:         }
3980:         if (guardLevel != null && !guardLevel.isBlank()) {
```

`main/java/com/example/lms/api/ChatApiController.java:4041–4079`

```text
4041: 
4042:         // 1) ???깆젧 ?곌랜理묌뜮?
4043:         ChatRequestDto dto = mergeWithSettings(uiReq);
4044:         publicRequestBudgetGuard.validateChatEffective(dto);
4045:         final boolean __hasAttachments = hasAttachments(dto);
4046:         final boolean __looksLikeAttachmentQ =
4047:                 ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion(dto.getMessage());
4048: 
4049:         // DROP: apply plan selection + guard hints BEFORE web prefetch/search.
4050:         PlanHints __planHints = null;
4051:         boolean __allowWebCap = true;
4052:         boolean __allowRagCap = true;
4053:         try {
4054:             GuardContext __gctx = GuardContextHolder.get();
4055:             if (__gctx != null) {
4056:                 AnswerMode __am = AnswerMode.fromString(dto.getMode());
4057:                 QueryDomain __qd = (__gctx.isSensitiveTopic()) ? QueryDomain.SENSITIVE : QueryDomain.GENERAL;
4058:                 if (workflowOrchestrator != null) {
4059:                     workflowOrchestrator.ensurePlanSelected(__gctx, __am, __qd, dto.getMessage(), __hasAttachments);
4060:                 }
4061:                 if (planHintApplier != null && __gctx.getPlanId() != null) {
4062:                     __planHints = planHintApplier.load(__gctx.getPlanId());
4063:                     planHintApplier.applyToGuardContext(__planHints, __gctx);
4064:                 }
4065:             }
4066:             __allowWebCap = (__planHints == null || __planHints.allowWeb() != Boolean.FALSE);
4067:             __allowRagCap = (__planHints == null || __planHints.allowRag() != Boolean.FALSE);
4068:             TraceStore.put("plan.id.preSearch", (__gctx == null ? null : __gctx.getPlanId()));
4069:             TraceStore.put("plan.allowWeb.cap", __allowWebCap);
4070:             TraceStore.put("plan.allowRag.cap", __allowRagCap);
4071:         } catch (Exception ignorePlan) {
4072:             logSuppressed("plan.preSearch");
4073:         }
4074: 
4075:         // === 嶺뚳퐘維? ???쳜????덈콦 ?낅슣??????獄??????吏?OFF ===
4076:         // Build a composed message by injecting attachment contents before the user
4077:         // question. When
4078:         // the user specifically references the uploaded file(s) the web search flag is
4079:         // forced
```

### E05. 예산 projection도 같은 메서드를 호출: 외부 I/O 금지

`main/java/com/example/lms/api/ChatApiController.java:4741–4762`

```text
4741:     private void validateProjectedBudgetBeforeStream(ChatRequestDto request,
4742:                                                      String jamminiMode,
4743:                                                      String guardLevel) {
4744:         ChatRequestDto dto = mergeWithSettings(request);
4745:         GuardContext context = GuardContext.defaultContext();
4746:         if (jamminiMode != null && !jamminiMode.isBlank()) {
4747:             context.setHeaderMode(jamminiMode);
4748:             context.setMode(jamminiMode);
4749:             context.setPlanId(jamminiMode);
4750:             if ("S1".equalsIgnoreCase(jamminiMode) || "safe".equalsIgnoreCase(jamminiMode)) {
4751:                 context.setMemoryProfile("MEMORY");
4752:             } else if ("S2".equalsIgnoreCase(jamminiMode)
4753:                     || "brave".equalsIgnoreCase(jamminiMode)
4754:                     || "free".equalsIgnoreCase(jamminiMode)
4755:                     || "zero_break".equalsIgnoreCase(jamminiMode)) {
4756:                 context.setMemoryProfile("NONE");
4757:             }
4758:         }
4759:         if (guardLevel != null && !guardLevel.isBlank()) {
4760:             context.setGuardLevel(guardLevel);
4761:         }
4762:         if (dto.getMessage() != null) {
```

`main/java/com/example/lms/api/ChatApiController.java:4777–4797`

```text
4777:         PlanHints planHints = null;
4778:         boolean allowWeb = true;
4779:         boolean allowRag = true;
4780:         try {
4781:             AnswerMode answerMode = AnswerMode.fromString(dto.getMode());
4782:             QueryDomain queryDomain = context.isSensitiveTopic() ? QueryDomain.SENSITIVE : QueryDomain.GENERAL;
4783:             if (workflowOrchestrator != null) {
4784:                 workflowOrchestrator.ensurePlanSelected(
4785:                         context, answerMode, queryDomain, dto.getMessage(), hasAttachments);
4786:             }
4787:             if (planHintApplier != null && context.getPlanId() != null) {
4788:                 planHints = planHintApplier.load(context.getPlanId());
4789:                 planHintApplier.applyToGuardContext(planHints, context);
4790:             }
4791:             allowWeb = planHints == null || planHints.allowWeb() != Boolean.FALSE;
4792:             allowRag = planHints == null || planHints.allowRag() != Boolean.FALSE;
4793:         } catch (PublicRequestBudgetGuard.Rejection rejection) {
4794:             throw rejection;
4795:         } catch (Exception suppressed) {
4796:             logSuppressed("budget.preStream.plan");
4797:         }
```

### E06. ChatWorkflow 진입에서 TraceStore.clear; 재선택 지점 존재

`main/java/com/example/lms/service/ChatWorkflow.java:1320–1348`

```text
1320:         // Web/RAG-only flows may bypass it.
1321:         try {
1322:             TraceStore.clear();
1323:         } catch (Exception ignore) {
1324:             ChatWorkflowTraceSuppressions.traceSuppressed("traceStore.clear", ignore);
1325:         }
1326:         if (requestTimelineIdBeforeTraceClear != null) {
1327:             TraceStore.putInternal(
1328:                     ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY,
1329:                     requestTimelineIdBeforeTraceClear);
1330:             recordModelRequestTimelinePhase("pending", req == null ? null : req.getModel(), "none");
1331:         }
1332:         rehydrateCreativeEmergenceTrace(GuardContextHolder.get());
1333:         com.example.lms.orchestration.control.RagControlRuntimeAdapter.capturePresentationInput(
1334:                 com.example.lms.orchestration.control.RagControlRuntimeAdapter.RuntimeInput.evidenceNeeded(
1335:                         req != null && (Boolean.TRUE.equals(req.getUseWebSearch())
1336:                                 || Boolean.TRUE.equals(req.getUseRag()))));
1337: 
1338:         ChatWorkflowRequestTraceEnvelope.seed(req, sessionKey);
1339:         com.example.lms.llm.RequestedModelSelection.begin(req.isStrictModelSelection() ? req.getModel() : null);
1340:         if (req.isStrictModelSelection()) {
1341:             var choice = chatModelCatalogService == null ? null
1342:                     : chatModelCatalogService.resolve(req.getModel()).orElse(null);
1343:             if (choice == null || !choice.selectable()) {
1344:                 throw new com.example.lms.llm.ModelSelectionException(
1345:                         chatModelCatalogService == null
1346:                                 ? "model_unavailable" : chatModelCatalogService.failureCode(choice));
1347:             }
1348:         }
```

`main/java/com/example/lms/service/ChatWorkflow.java:1436–1470`

```text
1436:             if (workflowOrchestrator != null) {
1437:                 try {
1438:                     boolean hasDocumentEvidence = req != null
1439:                             && req.getAttachmentIds() != null
1440:                             && !req.getAttachmentIds().isEmpty();
1441:                     workflowOrchestrator.ensurePlanSelected(gctx, answerMode, queryDomain, userQuery, hasDocumentEvidence);
1442:                 } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("workflow.planSelected", ignore); }
1443:             }
1444:             if (gctx.getPlanId() == null || gctx.getPlanId().isBlank())
1445:                 gctx.setPlanId("safe_autorun.v1");
1446: 
1447:             try {
1448:                 if (planHintApplier != null) {
1449:                     planHints = planHintApplier.load(gctx.getPlanId());
1450:                     planHintApplier.applyToGuardContext(planHints, gctx);
1451:                     // plan.when execution gate (chat path): the plan's own activation
1452:                     // condition is evaluated against observable request scope. A
1453:                     // conclusively FALSE verdict suppresses plan-driven expansion
1454:                     // overrides; UNKNOWN never fabricates metric evidence.
1455:                     planExecSpec = planHintApplier.loadExecutionSpec(gctx.getPlanId());
1456:                     if (planExecSpec != null && !planExecSpec.isEmpty()) {
1457:                         planWhen = planExecSpec.evaluateWhen(chatPlanScope(gctx));
1458:                         TraceStore.put("plan.when", planWhen.state().name().toLowerCase(Locale.ROOT));
1459:                         TraceStore.put("plan.when.conditions", planWhen.debugView());
1460:                         if (!planExecSpec.pipeline().isEmpty()) {
1461:                             TraceStore.put("plan.pipeline.declared", planExecSpec.pipeline());
1462:                         }
1463:                         if (planWhen.state() == com.example.lms.plan.PlanExecutionSpec.TriState.FALSE
1464:                                 && planExecSpec.declaresExpansion()) {
1465:                             stripPlanExpansionKeys(gctx.getPlanOverrides());
1466:                             TraceStore.put("plan.expansion.gated", "when_false");
1467:                         }
1468:                     }
1469:                 }
1470:             } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("planHints.applyGuardContext", ignore); }
```

### E07. AOP가 반환 뒤 계획을 보정하며 String 인자를 질의로 해석

`main/java/ai/abandonware/nova/orch/aop/WorkflowPlanMisrouteHatchAspect.java:38–96`

```text
38:     @Around("execution(String com.example.lms.orchestration.WorkflowOrchestrator.ensurePlanSelected(..))")
39:     public Object aroundEnsurePlanSelected(ProceedingJoinPoint pjp) throws Throwable {
40:         Object ret = pjp.proceed();
41:         if (!(ret instanceof String planId)) {
42:             return ret;
43:         }
44: 
45:         if (planId == null) {
46:             return ret;
47:         }
48: 
49:         // Only care about ap1_auth_web.* plans
50:         if (!planId.startsWith("ap1_auth_web")) {
51:             return ret;
52:         }
53: 
54:         Object[] args = pjp.getArgs();
55:         GuardContext ctx = null;
56:         String userQuery = null;
57:         if (args != null) {
58:             for (Object a : args) {
59:                 if (a instanceof GuardContext gc) {
60:                     ctx = gc;
61:                 } else if (a instanceof String s) {
62:                     // ensurePlanSelected signature has only one String arg = userQuery
63:                     userQuery = s;
64:                 }
65:             }
66:         }
67: 
68:         String q = (userQuery == null) ? "" : userQuery;
69:         String lower = q.toLowerCase(Locale.ROOT);
70: 
71:         boolean hasOfficialToken = lower.contains("공식") || lower.contains("official");
72:         boolean hasOtherLegalCue = OTHER_LEGAL_CUES.matcher(q).find();
73: 
74:         if (hasOfficialToken && !hasOtherLegalCue) {
75:             String safePlanId = env.getProperty("plans.auto-select.safe", "safe_autorun.v1");
76: 
77:             if (ctx != null) {
78:                 try {
79:                     ctx.setPlanId(safePlanId);
80:                 } catch (Throwable ignore) {
81:                     TraceStore.put("plan.hatch.ap1Misroute.contextUpdateSkipped", true);
82:                     TraceStore.put("plan.hatch.ap1Misroute.contextUpdateReason", "guard_context_plan_update_failed");
83:                 }
84:             }
85: 
86:             TraceStore.put("plan.hatch.ap1Misroute", true);
87:             TraceStore.put("plan.hatch.ap1Misroute.original", planId);
88:             TraceStore.put("plan.hatch.ap1Misroute.safe", safePlanId);
89:             TraceStore.put("plan.hatch.ap1Misroute.queryHash", SafeRedactor.hashValue(q));
90:             TraceStore.put("plan.hatch.ap1Misroute.queryLength", q.length());
91: 
92:             return safePlanId;
93:         }
94: 
95:         return ret;
96:     }
```

### E08. 근거 없음 처리: 일반 공개와 명시적 evidence_needed 예외 구분

`main/java/com/example/lms/service/ChatWorkflow.java:1351–1368`

```text
1351:         final String userQuery = Optional.ofNullable(req.getMessage()).orElse("");
1352:         final boolean evidenceReleaseRequired =
1353:                 EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(userQuery);
1354:         final String requestedModel = Optional.ofNullable(req.getModel()).orElse("");
1355:         final boolean forceLightSearchMode = req != null
1356:                 && req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT;
1357:         final boolean directRetrievalOffMode = req != null
1358:                 && (req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.OFF
1359:                 || Boolean.FALSE.equals(req.getUseWebSearch()))
1360:                 && Boolean.FALSE.equals(req.getUseRag());
1361:         // 인사·일상 대화는 근거 검증이 필요 없다 — AUTO 검색/RAG 실행과 공개 게이트를 함께 생략.
1362:         // 명시적 evidence_needed 지시·강제 검색 모드·직접 OFF 계약은 기존 규칙을 유지한다.
1363:         final boolean casualGreetingNoEvidenceIntent = !evidenceReleaseRequired
1364:                 && !directRetrievalOffMode
1365:                 && req != null
1366:                 && req.getSearchMode() != com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT
1367:                 && req.getSearchMode() != com.example.lms.gptsearch.dto.SearchMode.FORCE_DEEP
1368:                 && NoEvidenceChatFallback.isCasualGreetingOnly(userQuery);
```

`main/java/com/example/lms/service/ChatWorkflow.java:7516–7562`

```text
7516:     static FinalVerificationReleaseDecision applyEvidenceReleasePolicy(
7517:             FinalVerificationReleaseDecision base,
7518:             EvidenceReleaseState state,
7519:             boolean evidenceReleaseRequired,
7520:             boolean explicitDirectOff) {
7521:         Objects.requireNonNull(base, "base");
7522:         Objects.requireNonNull(state, "state");
7523:         if (!base.releaseAllowed()) {
7524:             return base;
7525:         }
7526:         if (explicitDirectOff
7527:                 || state == EvidenceReleaseState.NOT_APPLICABLE
7528:                 || state == EvidenceReleaseState.EVIDENCE_PRESENT) {
7529:             return base;
7530:         }
7531:         if (state == EvidenceReleaseState.METADATA_INCOMPLETE) {
7532:             if (evidenceReleaseRequired) {
7533:                 return new FinalVerificationReleaseDecision(
7534:                         "evidence_needed: attribution unavailable / verify retrieval evidence",
7535:                         "HOLD",
7536:                         "evidence_release_metadata_incomplete",
7537:                         false,
7538:                         true,
7539:                         false);
7540:             }
7541:             // 근거 0·인용 메타데이터 불완전만으로 본문을 보류하지 않는다 — 명시적
7542:             // evidence_needed 지시가 있을 때만 HOLD를 유지하고, 미검증 공개 답변은
7543:             // 장기 기억 저장을 차단한다(knowledgeWriteAllowed=false).
7544:             return new FinalVerificationReleaseDecision(
7545:                     base.content(),
7546:                     base.releaseStatus(),
7547:                     "evidence_unverified_release",
7548:                     true,
7549:                     false,
7550:                     false);
7551:         }
7552:         if (state == EvidenceReleaseState.CONFIRMED_EMPTY && evidenceReleaseRequired) {
7553:             return new FinalVerificationReleaseDecision(
7554:                     "evidence_needed",
7555:                     "HOLD",
7556:                     "evidence_required_empty",
7557:                     false,
7558:                     true,
7559:                     false);
7560:         }
7561:         return base;
7562:     }
```

### E09. 캐시 timeout과 동기 supplier: timeout만으로 호출 스레드가 풀리지 않음

`main/java/com/example/lms/service/routing/plan/RouterDecisionCache.java:100–146`

```text
100: 
101:         // 3) Compute (with in-flight de-dup)
102:         final String inflightKey = traceKey + "|" + fp;
103:         CompletableFuture<CacheEntry> fresh = new CompletableFuture<>();
104:         CompletableFuture<CacheEntry> existing = inflight.putIfAbsent(inflightKey, fresh);
105:         CompletableFuture<CacheEntry> future = existing != null ? existing : fresh;
106:         String flightRole = existing == null ? "leader" : "follower";
107: 
108:         if (existing == null) {
109:             long leaderLeaseMillis = remainingWaitMillis();
110:             fresh.orTimeout(leaderLeaseMillis, TimeUnit.MILLISECONDS)
111:                     .whenComplete((ignored, failure) -> {
112:                         if (isTimeoutFailure(failure)) {
113:                             inflight.remove(inflightKey, fresh);
114:                         }
115:                     });
116:             try {
117:                 T computed = supplier.get();
118:                 CacheEntry stored = new CacheEntry(fp, computed);
119:                 if (fresh.complete(stored)) {
120:                     TraceStore.put(traceKey, stored);
121:                     if (l2Enabled) {
122:                         l2Cache.put(traceKey, stored);
123:                     }
124:                 }
125:             } catch (Throwable t) {
126:                 traceSuppressed("compute", traceKey, t);
127:                 fresh.completeExceptionally(t);
128:                 throw t;
129:             } finally {
130:                 inflight.remove(inflightKey, fresh);
131:             }
132:         }
133: 
134:         CacheEntry resolved = awaitFlight(future, inflightKey, traceKey, flightRole);
135:         if (resolved != null && expectedType.isInstance(resolved.value)) {
136:             return expectedType.cast(resolved.value);
137:         }
138: 
139:         // Defensive fallback: compute synchronously
140:         T computed = supplier.get();
141:         CacheEntry stored = new CacheEntry(fp, computed);
142:         TraceStore.put(traceKey, stored);
143:         if (l2Enabled) {
144:             l2Cache.put(traceKey, stored);
145:         }
146:         return computed;
```

### E10. 기존 검색 정책은 deterministic; 별도 쿼리 계획 캐시 존재

`main/java/com/example/lms/search/policy/SearchPolicyEngine.java:16–26`

```text
16: /**
17:  * SearchPolicy engine.
18:  *
19:  * <p>This component is intentionally "low ceremony": it does not call LLMs.
20:  * It only adjusts breadth and generates deterministic variants (slices/expansions)
21:  * based on lightweight heuristics and optional overrides.
22:  */
23: @Component
24: public class SearchPolicyEngine {
25: 
26:     private static final String CREATIVE_OPTIONS_HASH_TRACE = "search.policy.creative.requestedOptionsHash";
```

`main/java/com/example/lms/search/policy/SearchPolicyEngine.java:40–89`

```text
40:     public SearchPolicyDecision decide(String query, Map<String, Object> metaHints) {
41:         String q = Objects.toString(query, "").trim();
42:         Map<String, Object> meta = (metaHints == null) ? Map.of() : metaHints;
43: 
44:         // Explicit override
45:         SearchPolicyMode override = parseMode(meta.get("searchPolicyMode"));
46:         if (override == null) override = parseMode(meta.get("search.policy.mode"));
47:         if (override == null) override = parseMode(meta.get("searchPolicy.mode"));
48: 
49:         boolean strike = boolish(meta.get("strikeMode"));
50:         boolean bypass = boolish(meta.get("bypassMode"));
51:         boolean compression = boolish(meta.get("compressionMode"));
52:         boolean nightmare = boolish(meta.get("nightmareMode"));
53: 
54:         if (override != null) {
55:             return withCreativeProfile(forMode(override, "override"), meta);
56:         }
57: 
58:         if (strike || bypass || compression || nightmare) {
59:             return withCreativeProfile(forMode(SearchPolicyMode.PRECISION, "guard/cheap-mode"), meta);
60:         }
61: 
62:         SearchPolicyMode uiMode = policyModeFromSearchMode(meta.get("searchMode"));
63:         if (uiMode == null) uiMode = policyModeFromSearchMode(meta.get("search_mode"));
64:         if (uiMode != null) {
65:             return withCreativeProfile(forMode(uiMode, "ui-search-mode"), meta);
66:         }
67: 
68:         // Lightweight intent heuristics
69:         String lower = q.toLowerCase(Locale.ROOT);
70:         int tokCount = TextUtils.tokenize(q).size();
71: 
72:         if (containsAny(lower, "최신", "최근", "업데이트", "release", "changelog", "patch", "변경사항", "버전", "릴리즈")) {
73:             return withCreativeProfile(forMode(SearchPolicyMode.RECALL, "recency"), meta);
74:         }
75: 
76:         if (containsAny(lower, "뜻", "의미", "정의", "difference", "vs", "비교", "차이")) {
77:             return withCreativeProfile(forMode(SearchPolicyMode.DISAMBIGUATE, "disambiguate"), meta);
78:         }
79: 
80:         if (containsAny(lower, "공식", "근거", "출처", "citation", "source", "정확")) {
81:             return withCreativeProfile(forMode(SearchPolicyMode.PRECISION, "precision-keyword"), meta);
82:         }
83: 
84:         if (tokCount <= 2 && q.length() <= 16) {
85:             return withCreativeProfile(forMode(SearchPolicyMode.DISAMBIGUATE, "short-query"), meta);
86:         }
87: 
88:         return withCreativeProfile(forMode(SearchPolicyMode.BALANCED, "default"), meta);
89:     }
```

`main/java/com/example/lms/service/routing/plan/RoutingPlanService.java:49–72`

```text
49:      */
50:     public List<String> plan(String userPrompt, @Nullable String assistantDraft, int maxQueries) {
51:         int requested = clamp(maxQueries, 1, 32);
52: 
53:         String input = Objects.toString(userPrompt, "").trim();
54:         String normKey = TextUtils.normalizeQueryKey(input);
55:         String draftHash = TextUtils.sha1(Objects.toString(assistantDraft, ""));
56: 
57:         String decisionKey = TextUtils.sha1("plan|" + normKey + "|draft=" + draftHash);
58:         String sliceFp = slicePolicy.fingerprint(
59:                 NS,
60:                 normKey,
61:                 Map.of(
62:                         "draft", draftHash,
63:                         "kind", "plan"));
64: 
65:         PlanEnvelope env = decisionCache.getOrCompute(
66:                 NS,
67:                 decisionKey,
68:                 sliceFp,
69:                 PlanEnvelope.class,
70:                 () -> new PlanEnvelope(safeList(smartQueryPlanner.plan(input, assistantDraft, requested)), requested));
71: 
72:         // If the caller asked for more than we previously computed for, attempt to extend the plan.
```

### E11. 검색 확장 우선순위와 충돌 해결은 기존 resolver 소유

`main/java/com/example/lms/orchestration/StrategyConflictResolver.java:27–89`

```text
27:     public ExecutionPlan resolve(Signals signals) {
28:         if (signals == null) {
29:             ExecutionPlan normal = ExecutionPlan.normal();
30:             trace(normal);
31:             return normal;
32:         }
33: 
34:         List<String> triggers = new ArrayList<>(4);
35:         if (signals.lowRecall()) {
36:             triggers.add("lowRecall");
37:         }
38:         if (signals.lowAuthority()) {
39:             triggers.add("lowAuthority");
40:         }
41:         if (signals.contradiction()) {
42:             triggers.add("contradiction");
43:         }
44:         if (signals.highRiskTail()) {
45:             triggers.add("highRiskTail");
46:         }
47: 
48:         boolean requestedExtremeZ = signals.lowRecall();
49:         boolean requestedOverdrive = signals.lowAuthority() || signals.contradiction();
50:         boolean requestedHypernova = signals.highRiskTail();
51: 
52:         ExecutionPlan.PrimaryMode mode;
53:         if (requestedExtremeZ) {
54:             mode = ExecutionPlan.PrimaryMode.EXTREMEZ;
55:         } else if (requestedHypernova) {
56:             mode = ExecutionPlan.PrimaryMode.HYPERNOVA;
57:         } else if (requestedOverdrive) {
58:             mode = ExecutionPlan.PrimaryMode.OVERDRIVE;
59:         } else {
60:             mode = ExecutionPlan.PrimaryMode.NORMAL;
61:         }
62: 
63:         boolean extremeZ = mode == ExecutionPlan.PrimaryMode.EXTREMEZ;
64:         boolean overdrive = mode == ExecutionPlan.PrimaryMode.OVERDRIVE;
65:         boolean hypernova = mode == ExecutionPlan.PrimaryMode.HYPERNOVA;
66:         String suppressed = suppressedModes(mode, requestedExtremeZ, requestedOverdrive, requestedHypernova);
67: 
68:         Map<String, Object> knobs = new LinkedHashMap<>();
69:         knobs.put("extremeZ.aggressive", extremeZ);
70:         knobs.put("overdrive.aggressive", overdrive);
71:         knobs.put("gateChain.sharp", !triggers.isEmpty());
72:         knobs.put("breaker.failSoft", true);
73:         knobs.put("starvationLadder.deterministic", true);
74:         knobs.put("cancelShield.breadcrumb", !triggers.isEmpty());
75:         knobs.put("promptBuilder.required", true);
76:         knobs.put("specialMode.priority", "EXTREMEZ>HYPERNOVA>OVERDRIVE");
77:         knobs.put("specialMode.conflict.suppressed", suppressed);
78: 
79:         ExecutionPlan plan = new ExecutionPlan(
80:                 mode,
81:                 extremeZ,
82:                 overdrive,
83:                 hypernova,
84:                 triggers,
85:                 ExecutionPlan.DEFAULT_STAGES,
86:                 knobs);
87:         trace(plan);
88:         return plan;
89:     }
```

### E12. 실제 적용 증명: plan loader와 retrieval order는 별도

`main/java/com/example/lms/plan/PlanHintApplier.java:350–371`

```text
350:         Resource res = findPlanResource(planId);
351:         if (res == null || !res.exists()) {
352:             TraceStore.append("plan.load.miss", safeTraceToken(planId));
353:             return new LoadedPlan(PlanHints.empty(planId), PlanExecutionSpec.empty("missing_resource"));
354:         }
355: 
356:         try (InputStream in = res.getInputStream()) {
357:             @SuppressWarnings("unchecked")
358:             Map<String, Object> root = yamlMapper.readValue(in, Map.class);
359:             if (root == null) {
360:                 return new LoadedPlan(PlanHints.empty(planId), PlanExecutionSpec.empty("empty_document"));
361:             }
362:             PlanValidationResult validation = validatePlanRoot(planId, root);
363:             if (!validation.valid()) {
364:                 TraceStore.append("plan.schema.invalid", safeTraceToken(planId + ":" + validation.reason()));
365:                 TraceStore.put("plan.schema.invalid.last", safeTraceToken(planId + ":" + validation.reason()));
366:                 if (!"safe.v1".equals(planId)) {
367:                     TraceStore.put("plan.schema.fallback", safeTraceToken(planId + "->safe.v1"));
368:                     return loadInternal("safe.v1");
369:                 }
370:                 return new LoadedPlan(PlanHints.empty("safe.v1"), PlanExecutionSpec.empty("invalid_plan"));
371:             }
```

`main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java:494–534`

```text
494:         // 6. Dynamic order execution: Web / Vector / KG as decided by orderService
495:         java.util.List<Source> plan;
496:         try {
497:             plan = orderService.decideOrder(qText);
498:         } catch (Exception e) {
499:             // fallback to default order
500:             log.warn("[OrderService] decide failed; using default. errorHash={} errorLength={}",
501:                     SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
502:             traceSuppressed("order.decide", e);
503:             mask("[OrderService]", e, qText);
504:             plan = List.of(Source.WEB, Source.VECTOR, Source.KG);
505:         }
506:         // plan override: metaHints["retrieval.order"] = ["kg","vector","web"] 등
507:         try {
508:             java.util.Map<String, Object> md = toMap(QueryUtils.metadata(effectiveQuery));
509:             java.util.List<Source> override = parseOrderOverride(md);
510:             if (override != null && !override.isEmpty()) {
511:                 plan = override;
512:                 TraceStore.put("retrieval.order.override", override.toString());
513:             }
514:         } catch (Exception e) {
515:             traceSuppressed("retrieval.orderOverride", e);
516:         }
517: 
518:         // OrchestrationHints: allowWeb/allowRag per-request switches
519:         try {
520:             java.util.Map<String, Object> md = toMap(QueryUtils.metadata(effectiveQuery));
521:             boolean allowWeb = metaBool(md, "allowWeb", true) && metaBool(md, "retrieval.web.enabled", true);
522:             boolean allowRag = metaBool(md, "allowRag", true);
523:             if (!allowWeb) {
524:                 plan = plan.stream().filter(s -> s != Source.WEB).toList();
525:             }
526:             if (!allowRag) {
527:                 plan = plan.stream().filter(s -> s != Source.VECTOR && s != Source.KG).toList();
528:             }
529:             if (!metaBool(md, "retrieval.vector.enabled", true)) {
530:                 plan = plan.stream().filter(s -> s != Source.VECTOR).toList();
531:             }
532:         } catch (Exception e) {
533:             traceSuppressed("retrieval.allowSwitches", e);
534:         }
```

### E13. 개인 GraphRAG 권한/동의/세션 경계

`main/java/com/example/lms/service/rag/graph/GeneralGraphScope.java:29–64`

```text
29:     public static Optional<GeneralGraphScope> authorize(ChatSession session, String username, String ownerKey) {
30:         if (session == null || session.getId() == null || session.getId() <= 0) return Optional.empty();
31:         try {
32:             String persistedOwner = session.getAdministrator() != null
33:                     ? AttachmentOwnerIdentity.forAdministrator(session.getAdministrator().getUsername()).hash()
34:                     : AttachmentOwnerIdentity.forAnonymous(session.getOwnerKey()).hash();
35:             String requester = AttachmentOwnerIdentity.forActor(username, ownerKey).hash();
36:             return persistedOwner.equals(requester)
37:                     ? Optional.of(new GeneralGraphScope(persistedOwner, session.getId())) : Optional.empty();
38:         } catch (IllegalArgumentException missingOwner) {
39:             return Optional.empty();
40:         }
41:     }
42: 
43:     public String ownerNamespace() { return ownerNamespace; }
44:     public long sessionId() { return sessionId; }
45:     public String channel() { return CHANNEL; }
46:     public long consentEpoch() { return consentEpoch; }
47:     public boolean memoryEnabled() { return consentEpoch > 0 && memoryEnabled; }
48: 
49:     public String indexNamespace() {
50:         return org.apache.commons.codec.digest.DigestUtils.sha256Hex(
51:                 ownerNamespace + ":" + sessionId + ":" + CHANNEL + ":" + consentEpoch);
52:     }
53: 
54:     GeneralGraphScope withPolicy(long epoch, boolean enabled) {
55:         if (epoch <= 0) throw new IllegalArgumentException("invalid_graph_epoch");
56:         return new GeneralGraphScope(ownerNamespace, sessionId, epoch, enabled);
57:     }
58: 
59:     /** Stored index claims still require relational owner, policy and source validation. */
60:     static GeneralGraphScope indexClaim(String owner, long session, long epoch) {
61:         if (owner == null || !owner.matches("[a-f0-9]{64}") || session <= 0 || epoch <= 0)
62:             throw new IllegalArgumentException("invalid_graph_claim");
63:         return new GeneralGraphScope(owner, session, epoch, true);
64:     }
```

`main/java/com/example/lms/service/rag/handler/KnowledgeGraphHandler.java:74–110`

```text
74:     @Override
75:     public List<Content> retrieve(Query query) {
76:         return retrieveAuthorizedSources(query);
77:     }
78: 
79:     private List<Content> retrieveAuthorizedSources(Query query) {
80:         String text = query == null || query.text() == null ? "" : query.text();
81:         var scope = com.example.lms.service.rag.QueryUtils.generalGraphScope(query).orElse(null);
82:         List<Content> out = new ArrayList<>();
83:         if (scope != null && scope.memoryEnabled() && sourceAuthority != null) {
84:             Set<com.example.lms.service.rag.graph.KgChunk.SourceRef> candidates = new LinkedHashSet<>();
85:             if (neo4jClient != null) candidates.addAll(neo4jClient.lookupSources(scope, text, 16));
86:             BrainStateService brain = brainStateProvider == null ? null : brainStateProvider.getIfAvailable();
87:             if (brain != null) candidates.addAll(brain.privateSources(scope, text, 16));
88:             Set<String> seen = new LinkedHashSet<>();
89:             for (var reference : candidates.stream().sorted(java.util.Comparator.comparingLong(
90:                     (com.example.lms.service.rag.graph.KgChunk.SourceRef r) ->
91:                             com.example.lms.service.rag.graph.GeneralGraphSourceAuthority.sourceMessageId(r.sourceId()))
92:                     .reversed()).toList()) {
93:                 if (out.size() >= 8) break;
94:                 try {
95:                     var evidence = sourceAuthority.source(scope, reference);
96:                     if (evidence.isPresent() && seen.add(evidence.get().sourceId())) {
97:                         out.add(sourceAuthority.evidenceContent(evidence.get()));
98:                     }
99:                 } catch (RuntimeException unavailable) {
100:                     TraceStore.put("retrieval.kg.private.disabledReason", "source_authority_unavailable");
101:                 }
102:             }
103:         }
104:         // Only the explicitly published manual lane is public. Legacy JPA/BrainState
105:         // relations have no owner or source contract and cannot be used as fallback.
106:         TraceStore.put("retrieval.kg.legacy.status", "unscoped_excluded");
107:         int privateCount = out.size();
108:         TraceStore.put("retrieval.kg.private.returnedCount", privateCount);
109:         boolean publicFailed = false;
110:         if (knowledgeBase != null) {
```

### E14. 기존 cost guard는 비에이전트 요청의 지출 상한이 아님

`main/java/com/example/lms/routing/AgentApiSpendGuard.java:16–63`

```text
16:             String purpose,
17:             String provider,
18:             String model,
19:             String caller,
20:             String probeId,
21:             boolean explicitPaidOverride) {
22:         String fp = ApiSpendAttribution.fingerprint(purpose, provider, model, caller, probeId);
23:         if (!ApiSpendAttribution.agentModeActive()) {
24:             ApiSpendAttribution.record(purpose, provider, model, "n/a", "user_request", caller, "miss",
25:                     null, null, null, null, estimateTier(provider, model));
26:             return new Decision(true, "user_request", "miss");
27:         }
28:         if (ApiSpendAttribution.shouldSkipSuccessfulReplay(fp)) {
29:             ApiSpendAttribution.record(purpose, provider, model, "n/a", "verification_replay_blocked", caller,
30:                     "hit_skip", null, "skipped", null, null, "local0");
31:             return new Decision(false, "verification_replay_blocked", "hit_skip");
32:         }
33:         if (!explicitPaidOverride && ApiSpendAttribution.isStalePaidAutoModel(model)) {
34:             ApiSpendAttribution.record(purpose, provider, model, "paid_quality", "model_auto_blocked_stale", caller,
35:                     "forced", null, "blocked", null, null, "llm_paid");
36:             return new Decision(false, "model_auto_blocked_stale", "forced");
37:         }
38:         String why = "verification_required";
39:         if ("connectivity_min".equalsIgnoreCase(purpose) || "probe".equalsIgnoreCase(purpose)) {
40:             why = "connectivity_min";
41:         }
42:         ApiSpendAttribution.record(purpose, provider, model, estimateTier(provider, model), why, caller, "miss",
43:                 null, null, null, null, estimateTier(provider, model));
44:         return new Decision(true, why, "miss");
45:     }
46: 
47:     public static void afterSuccess(String purpose, String provider, String model, String caller, String probeId,
48:                                     Integer promptTokens, Integer completionTokens, String estCostClass) {
49:         String fp = ApiSpendAttribution.fingerprint(purpose, provider, model, caller, probeId);
50:         ApiSpendAttribution.markSuccess(fp);
51:         ApiSpendAttribution.record(purpose, provider, model, estimateTier(provider, model), "verification_required",
52:                 caller, "miss", 200, "ok", promptTokens, completionTokens, estCostClass);
53:     }
54: 
55:     public static void afterFailure(String purpose, String provider, String model, String caller, String probeId,
56:                                     Integer httpStatus, String errorClass) {
57:         ApiSpendAttribution.record(purpose, provider, model, estimateTier(provider, model), "verification_required",
58:                 caller, "miss", httpStatus, errorClass, null, null, estimateTier(provider, model));
59:     }
60: 
61:     private static String estimateTier(String provider, String model) {
62:         String p = provider == null ? "" : provider.toLowerCase();
63:         if (p.contains("ollama") || p.contains("local")) {
```

### E15. TraceStore는 요청용이고 sanitizer가 아니다

`main/java/com/example/lms/search/TraceStore.java:16–34`

```text
16:  * Request-scoped trace bag.
17:  *
18:  * <p>
19:  * This store is frequently accessed across async boundaries. The underlying
20:  * map is a {@link ConcurrentHashMap} to reduce cross-thread corruption when the
21:  * same context is installed in worker threads.
22:  *
23:  * <p>
24:  * TraceStore is not a sanitizer. Callers must store only redacted, hashed,
25:  * count-only, or explicitly allowlisted values because snapshots can be
26:  * serialized into diagnostics and UI surfaces.
27:  * </p>
28:  */
29: public final class TraceStore {
30:     private static final Logger log = LoggerFactory.getLogger(TraceStore.class);
31: 
32:     private static final ThreadLocal<Map<String, Object>> TRACE = ThreadLocal.withInitial(ConcurrentHashMap::new);
33: 
34:     private static final ThreadLocal<Map<String, AtomicLong>> SEQ = ThreadLocal.withInitial(ConcurrentHashMap::new);
```

`main/java/com/example/lms/search/TraceStore.java:128–144`

```text
128:     /**
129:      * Store behavior-only state that must be available to internal pipeline
130:      * consumers but must not be exposed through public diagnostic snapshots.
131:      */
132:     public static void putInternal(String key, Object value) {
133:         if (key == null || key.isBlank()) {
134:             return;
135:         }
136:         Map<String, Object> trace = TRACE.get();
137:         if (value == null) {
138:             trace.remove(key);
139:             internalKeys(trace).remove(key);
140:             return;
141:         }
142:         trace.put(key, value);
143:         internalKeys(trace).add(key);
144:     }
```

`main/java/com/example/lms/search/TraceStore.java:197–205`

```text
197:     /** Remove all entries from the current thread's trace map. */
198:     public static void clear() {
199:         // Prefer remove() over clear() so the ThreadLocal doesn't retain a large map
200:         // across reused threads.
201:         TRACE.remove();
202:         SEQ.remove();
203:     }
204: 
205:     /**
```

### E16. GuardContext copy와 기존 선택 재현 원장: 새 외부 결정과 혼동 금지

`main/java/com/example/lms/service/guard/GuardContext.java:138–168`

```text
138:     public synchronized void attachSelectionEntropy(
139:             SelectionEntropy entropy,
140:             SelectionDecisionLedger ledger) {
141:         Objects.requireNonNull(entropy, "entropy");
142:         Objects.requireNonNull(ledger, "ledger");
143:         if (selectionEntropy == null && selectionDecisionLedger == null) {
144:             selectionEntropy = entropy;
145:             selectionDecisionLedger = ledger;
146:             return;
147:         }
148:         if (selectionEntropy == entropy && selectionDecisionLedger == ledger) {
149:             return;
150:         }
151:         throw new IllegalStateException("selection_entropy_context_already_attached");
152:     }
153: 
154:     public synchronized boolean hasAttachedSelectionEntropy() {
155:         return selectionEntropy != null && selectionDecisionLedger != null;
156:     }
157: 
158:     public synchronized SelectionEntropy selectionEntropy() {
159:         return selectionEntropy == null
160:                 ? SelectionEntropyFactory.standard()
161:                 : selectionEntropy;
162:     }
163: 
164:     public synchronized SelectionDecisionLedger selectionDecisionLedger() {
165:         if (selectionDecisionLedger == null) {
166:             selectionDecisionLedger = SelectionDecisionLedger.forStandard();
167:         }
168:         return selectionDecisionLedger;
```

`main/java/com/example/lms/service/guard/GuardContext.java:674–680`

```text
674:      * components/threads.
675:      */
676:     public synchronized GuardContext copy() {
677:         GuardContext c = new GuardContext();
678:         c.planId = this.planId;
679:         c.mode = this.mode;
680:         c.engine = this.engine;
```

`main/java/com/example/lms/service/guard/GuardContext.java:711–719`

```text
711:         c.bypassReason = this.bypassReason;
712:         c.userQuery = this.userQuery;
713:         c.interactionPolicyDecision = this.getInteractionPolicyDecision();
714:         c.interactionPolicyFacts = this.getInteractionPolicyFacts();
715:         c.interactionSuspectEvidenceIds = this.getInteractionSuspectEvidenceIds();
716:         c.selectionEntropy = this.selectionEntropy;
717:         c.selectionDecisionLedger = this.selectionDecisionLedger;
718:         c.auxDegraded = this.auxDegraded;
719:         c.auxHardDown = this.auxHardDown;
```

`main/java/com/example/lms/infra/selection/SelectionEntropyMode.java:1–16`

```text
1: package com.example.lms.infra.selection;
2: 
3: public enum SelectionEntropyMode {
4:     STANDARD("standard"),
5:     REPLAY("replay");
6: 
7:     private final String wireValue;
8: 
9:     SelectionEntropyMode(String wireValue) {
10:         this.wireValue = wireValue;
11:     }
12: 
13:     public String wireValue() {
14:         return wireValue;
15:     }
16: }
```

`main/java/com/example/lms/infra/selection/SelectionDecisionLedger.java:68–98`

```text
68:             List<String> stableCandidateKeys,
69:             int selectedIndex,
70:             String fallbackReason,
71:             boolean drawConsumed,
72:             boolean stableTieBreak) {
73:         recordValidatedDecision(
74:                 lane,
75:                 coordinate,
76:                 stableCandidateKeys,
77:                 selectedIndex,
78:                 fallbackReason == null ? "" : fallbackReason,
79:                 drawConsumed,
80:                 stableTieBreak);
81:     }
82: 
83:     public synchronized void markFailure(SelectionEntropyReason failureReason) {
84:         if (failureReason == null || failureReason == SelectionEntropyReason.NONE) {
85:             throw new IllegalArgumentException("selection_entropy_failure_reason_invalid");
86:         }
87:         if (coherence.get() == SelectionEntropyCoherence.FAILED) {
88:             return;
89:         }
90:         reason.set(failureReason);
91:         coherence.set(SelectionEntropyCoherence.FAILED);
92:     }
93: 
94:     public synchronized Snapshot snapshot(boolean terminal) {
95:         SelectionEntropyCoherence visible = coherence.get();
96:         if (terminal && visible == SelectionEntropyCoherence.ACCEPTED) {
97:             visible = SelectionEntropyCoherence.MATCHED;
98:         }
```

## 3. 기존 plan 파일 (전체 내용)

`main/resources/plans/ap9_cost_saver.v1.yaml`

```yaml
# MERGE_HOOK:PROJ_AGENT::FIM15
llm:
  provider: local
  model: ${llm.high.model:${llm.chat-model}}

id: ap9_cost_saver.v1
name: AP9_COST_SAVER
version: '1'
params:
  use_cross_encoder: false
  allowWeb: true
  allowRag: true
  rerank_backend: auto
  rerank_top_k: 6
  budget_ms: 1200
chain:
- alias_correct
- retrieve_web
- retrieve_vector
- fuse_rrf
- rerank_bi
- final_sigmoid

# NineTile alias corrector toggle
alias:
  corrector:
    enabled: true

probe:
  search:
    enabled: false


retrieval:
  order: [web, vector]
  web:
    enabled: true
  vector:
    enabled: true
```

`main/resources/plans/ap3_vec_dense.v1.yaml`

```yaml
# MERGE_HOOK:PROJ_AGENT::FIM15
llm:
  provider: local
  model: ${llm.high.model:${llm.chat-model}}

id: ap3_vec_dense.v1
name: AP3_VEC_DENSE
version: '1'
params:
  vectorOnly: true
  allowWeb: false
  allowRag: true
  vecTopK: 15
  use_cross_encoder: false
  rerank_backend: auto
  rerank_top_k: 8
chain:
- alias_correct
- retrieve_vector
- calibrate_scores
- rerank_bi
- final_sigmoid

# NineTile alias corrector toggle
alias:
  corrector:
    enabled: true

probe:
  search:
    enabled: false


retrieval:
  order: [vector]
  web:
    enabled: false
  vector:
    enabled: true
```

`main/resources/plans/kg_first.v1.yaml`

```yaml
# MERGE_HOOK:PROJ_AGENT::FIM15
llm:
  provider: local
  model: ${llm.high.model:${llm.chat-model}}

id: kg_first.v1
retrieval:
  order: [kg, vector, web]
  topk: { web: 6, vector: 8, kg: 8 }
budgets:
  total_ms: 3500
  web_ms: 1200
  reranker_ms: 1000
fusion:
  score_calibrator: isotonic@v1
guards:
  min_citations: 2
  whitelist_profile: "trusted+kg"

# NineTile alias corrector toggle
alias:
  corrector:
    enabled: true

probe:
  search:
    enabled: false
```

`main/resources/plans/document_evidence.v1.yaml`

```yaml
# MERGE_HOOK:PROJ_AGENT::document_evidence_rag_fusion
# Upload-document focused RAG route. This plan never changes provider/model policy.

plan:
  id: document_evidence.v1
  description: "Use uploaded document evidence as first-class local context, then apply existing vector/rerank gates."
  overrides:
    properties:
      gate.citation.min: 3
      onnx.enabled: true
      overdrive.enabled: true
      retrieval.vector.enabled: true
    knobs:
      expand.selfAsk.count: 3
      rerank.ce.topK: 24
      rerank.topK: 8

retrieval:
  order: [VECTOR, WEB, KG]
  topk:
    web: 8
    vector: 12
    kg: 3

guards:
  min_citations: 3
  whitelist_profile: "trusted+docs"

params:
  allowRag: true
  retrieval:
    vector:
      enabled: true
```

