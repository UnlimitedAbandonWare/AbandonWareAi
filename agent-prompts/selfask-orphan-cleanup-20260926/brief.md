# THE ONE — SelfAskPlanner orphan cleanup

Project Root: C:\AbandonWare\demo-1\demo-1\src
Canonical keep: main/java/com/example/lms/service/rag/SelfAskPlanner.java
Consumers: HybridRetriever, UnifiedRagOrchestrator, QueryBurstExpander

Delete or quarantine (_orphan_quarantine) the other 6 SelfAskPlanner stubs under service/, strategy/, com/abandonware/ai/...
Do not change canonical logic. Do not touch Display/Jev/Luna/admin. No push/add -A/secrets.

Verify:
  rg -l "class SelfAskPlanner" main/java   # expect 1
  .\gradlew.bat :compileJava -x test
  .\gradlew.bat test --tests "*SelfAsk*" --tests "com.example.lms.service.rag.HybridRetriever*"
