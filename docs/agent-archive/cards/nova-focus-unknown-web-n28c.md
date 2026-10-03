# Nova Focus 모름 신호 → 선제 웹 확장: UnknownAnswerPolicy + unknown-web-enabled(기본 on)/scoped(기본 off)
- card-id: nova-focus-unknown-web-n28c
- kind: checklist
- status: still-true (flags verified 2026-10-03)
- date: 2026-09-28 KST
- evidence: docs/diagnostics/nova-focus-web-unknown-20260928/01_CHANGED_FILES.md ; main/java/com/example/lms/assist/NovaFocusAnswerService.java:39-40
- reverify: `Select-String -Path main\java\com\example\lms\assist\NovaFocusAnswerService.java -Pattern 'unknown-web'`

## 근거
- 신규 `UnknownAnswerPolicy`: 모름 신호 분류(classify), Jev verdict→모드 환산(mode),
  선제 웹 확장 게이트(defaultWebAllowed), 요청당 한 번 재시도 판정(decide).
- NovaFocusAnswerService: `conversate.focus.unknown-web-enabled`(기본 on),
  `unknown-web-scoped-enabled`(기본 off) — 모름 신호 시 같은 60s 한도 안에서 웹 ON으로
  최대 한 번 재시도 (진단키 focus.unknown.*).
- ConversateApiCueService: RAG_CUE 기본 웹 retrieve()를 defaultWebAllowed로 게이트;
  SCOPED_RAG 무플래그 시 retrievalSkipped.
- 변경 안 한 것: ConversateQuestionPolicy(NO_CUE 유지), SearchDecisionService,
  UnifiedRagOrchestrator, ConversateSessionService, DisplayRelay, receiver.js.
