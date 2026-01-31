# PATCH_NOTES_src111_mesarge15

    목적: 중화된 블록 주석 종료 토큰(`* /`) 때문에 발생한 `unclosed comment` 및 연쇄 구문 오류를 일괄 수정.

    핵심 변경
    - Java 블록 주석 내부에서 의도적으로 띄어쓴 종료 토큰 `* /`를 표준 `*/`로 정상화.
    - 파일 끝까지 닫히지 않은 블록 주석이 존재할 경우 안전하게 `*/`를 보강하여 닫음.
    - 라인/문자열/문자 리터럴 컨텍스트를 구분하는 상태 머신으로 **주석 내부에서만** 정상화 수행(코드/문자열은 보존).

    스캔/수정 통계
    - 스캔 대상: 2326 Java 파일
    - 변경된 파일 수: 104
    - 변경 파일 샘플(상위 30개 표시):
      - app/src/main/java/com/abandonware/ai/agent/orchestrator/Orchestrator.java
- app/src/main/java/com/abandonware/ai/agent/tool/response/ToolResponse.java
- app/src/main/java/com/example/lms/mcp/server/McpToolServer.java
- app/src/main/java/com/example/lms/service/rag/runtime/TimeBudget.java
- extras/gap15-stubs_v1/src/main/java/com/abandonwareai/fusion/MpAwareFuser.java
- extras/gap15-stubs_v1/src/main/java/com/abandonwareai/fusion/ScoreCalibrator.java
- extras/gap15-stubs_v1/src/main/java/com/abandonwareai/mcp/McpToolRegistry.java
- extras/gap15-stubs_v1/src/main/java/com/abandonwareai/mcp/McpTransport.java
- extras/gap15-stubs_v1/src/main/java/com/abandonwareai/planner/PlannerNexus.java
- extras/gap15-stubs_v1/src/main/java/com/abandonwareai/resilience/cfvm/RetrievalOrderServiceAdapter.java
- lms-core/src/main/java/com/abandonware/ai/agent/service/rag/bm25/Bm25IndexHolder.java
- lms-core/src/main/java/com/abandonware/ai/agent/service/rag/bm25/Bm25IndexService.java
- lms-core/src/main/java/com/abandonware/ai/agent/tool/response/ToolResponse.java
- lms-core/src/main/java/com/abandonware/ai/service/TranslationTrainingService.java
- src/main/java/com/abandonwareai/fusion/MpAwareFuser.java
- src/main/java/com/abandonwareai/mcp/McpToolRegistry.java
- src/main/java/com/abandonwareai/mcp/McpTransport.java
- src/main/java/com/abandonwareai/planner/PlannerNexus.java
- src/main/java/com/abandonwareai/resilience/cfvm/RetrievalOrderServiceAdapter.java
- src/main/java/com/example/lms/agent/CuriosityTriggerService.java
- src/main/java/com/example/lms/client/GTranslateClient.java
- src/main/java/com/example/lms/config/LangChainConfig.java
- src/main/java/com/example/lms/config/PineconeProps.java
- src/main/java/com/example/lms/config/QueryTransformerConfig.java
- src/main/java/com/example/lms/config/TelemetryConfig.java
- src/main/java/com/example/lms/config/RuleBreakConfig.java
- src/main/java/com/example/lms/controller/TrainingController.java
- src/main/java/com/example/lms/domain/ChatMessage.java
- src/main/java/com/example/lms/domain/Comment.java
- src/main/java/com/example/lms/domain/Professor.java
      - ...

    예상 효과
    - 다음과 같은 오류군 제거:
      * `error: unclosed comment`
      * `reached end of file while parsing` (블록 주석 누락으로 인한 중괄호/구문 소실의 2차 증상)
      * `illegal start of expression` / `'var' is not allowed here` (주석 범람으로 문맥 교란된 경우)
      * `; expected`, `<identifier> expected` 등의 다발성 파생 오류

    검증 가이드
    ```bash
    ./gradlew clean compileJava

    # 남아있는 비표준 패턴 탐지(선택)
    rg -n "/\*[^*]*\*\s+/" --glob "**/*.java" || true
    rg -n "^[ \t]*\*" --glob "**/*.java" | head -n 50 || true
    ```

    롤백/비고
    - 로직/시그니처/어노테이션은 변경하지 않음. 오직 주석 종료 토큰 정합성만 교정.
    - 주석 내부에 예시로 존재하던 `/* ... *&#47;`(HTML 엔티티)는 그대로 유지.