# Build Pattern Auto-Report — src111_merge15 → src111_mergse15

Date: 2025-10-25

Detected patterns from BUILD_LOG.txt:
{
  "UNINITIALIZED_FINAL": false,
  "MISSING_SYMBOL_GETTER": false,
  "MISSING_SYMBOL_SETTER": false,
  "MISSING_BOOLEAN_IS": false,
  "LombokBuilderDefaultWarnings": false
}

Applied fixes:
- BraveSearchProvider: constructor injection via @RequiredArgsConstructor; registered bean via @Component.
- SelectedTerms: added Lombok getters/setters/ctors/builder to expose get*/set*.
- ReinforcedPath: added Lombok getters/setters to satisfy setPath/setScore/setReinforcedAt calls.
- NaverFilterProperties: added Lombok getters + @ConfigurationProperties(prefix="naver.filters").
- CognitiveState: added alias methods for abstractionLevel/temporalSensitivity/evidenceTypes/complexityBudget.
- RewardScoringEngine: fixed DEFAULT to use new SimilarityPolicy(); declared SIMILARITY_FLOOR constant.
- ChatSession: added Lombok getters for id and messages.
- ChatRequestDto: added @Data + @Builder to satisfy getters and @Builder.Default warnings.
- ChatResponseDto: added @Getter (isRagUsed/getModelUsed/getSessionId).

Artifacts:
- dev/build/error-patterns.json — latest pattern flags.
