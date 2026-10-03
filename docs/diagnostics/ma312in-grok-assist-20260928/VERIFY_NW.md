# VERIFY_NW — commands for Codex

Exit cells stay blank until Codex runs them. This assist did not run Gradle, Node, or Verify-RAG.

Project Root: `C:\AbandonWare\demo-1\demo-1\src`. Java 17. Do not run the full `gradlew test` suite.

| ID | Command | What a pass means | Exit |
|---|---|---|---|
| V0 | `.\gradlew.bat :compileJava -x test` | NW Java edits compile. Skip if no Java edit yet | |
| V1 | `node scripts/chat_ui_stream_contract_tests.js` | NW1: ordinary deadline finite; evidence/RAG clock unchanged; existing file is the harness. Confirm the script has no extra runner before trusting a bare `node` exit | |
| V2 | `.\gradlew.bat test --tests com.example.lms.llm.gateway.LlmGatewayFailureClassifierTest` | existing structured-429 tests still pass after NW4 | |
| V3 | `.\gradlew.bat test --tests com.example.lms.llm.NoWaitFakeProviderContractTest` | T01–T03 and T05 after Codex adds that class. Until the class exists this row is NOT_RUN, not a failure of the suite | |
| V4 | `.\gradlew.bat test --tests com.example.lms.llm.HttpTransportCancellationTest` | existing `interruptibleCall("jdk_http")` coverage still passes after NW5 | |
| V5 | `Verify-RAG.bat` | only after a Java/resources edit that must be live. Exit 0 is the dev stack proof. Status-RAG is not a substitute | |

Browser video repro is Codex-owned and is not a row here. Admin/HOLD Browser is NOT_RUN.

If V0 fails, the log path goes to AWX `build_error_mine` (`log_path` only). Do not mine a green log.
