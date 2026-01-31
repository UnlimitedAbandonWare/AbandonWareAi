# Patch Notes — src111_mersage15

### Overview
This patch applies surgical fixes to resolve the head-of-line compilation failures shown in your Gradle log excerpt and harmonises a few config/data classes to match their call sites.

### What changed

1. **Cognitive state model (core)**
   - Replaced `src/main/java/com/example/lms/service/rag/pre/CognitiveState.java`
   - New design: immutable value + four nested enums
     - `AbstractionLevel { SUMMARY, PROCEDURAL, FACTUAL, COMPARATIVE }`
     - `TemporalSensitivity { RECENT_REQUIRED, HISTORICAL, IRRELEVANT }`
     - `ComplexityBudget { LOW, MEDIUM, HIGH }`
     - `ExecutionMode { KEYWORD_SEARCH, VECTOR_SEARCH }`
   - Rationale: fixes the `one-public-type-per-file` errors and stabilises imports for `PromptContext`, `QueryTransformer`, `QueryKeywordPromptBuilder`, `GuardrailQueryPreprocessor`.

2. **Security role**
   - Added `src/main/java/com/example/lms/domain/Role.java` (enum: `ADMIN`, `USER`) to satisfy `AdminInitializer`.

3. **MOE routing properties**
   - Ensured Lombok + Spring binding: annotated `MoeRoutingProperties` with `@Data` and `@ConfigurationProperties(prefix="moe.routing")` so calls like `isEnabled()`, `getAllow()`, `getTierOrder()` exist.

4. **Reward scoring constants**
   - Added `SIMILARITY_FLOOR` and `DEFAULT` to `RewardScoringEngine` to fix missing‑constant references from `MemoryReinforcementService` and the inner `SimilarityPolicy`.

5. **Build-log slot extraction (backup namespace)**
   - Rewrote `_abandonware_backup/.../RawSlot.java` with `@Value @Builder` and nested `Stage { ANALYZE, SELF_ASK, WEB, VECTOR, KG, ANSWER, BUILD, RUNTIME }`, matching usage in `BuildLogSlotExtractor`.

6. **Spring/Java imports in backup ChatService**
   - Inserted imports for `@Autowired`, `Environment`, `Function`, and `ChatMemoryProvider` into `ChatService__backup_pre_interface.java` to un-block symbol resolution.

7. **Hyperparameter entity constructor**
   - Confirmed Lombok with `@NoArgsConstructor @AllArgsConstructor` so `new Hyperparameter(key, value, "...")` compiles; Lombok covers `getParamKey/Value`, `setParamValue(...)`.

### Notes about filters & whitelists
`NaverSearchService`’s getters (`isEnableDomainFilter`, `getDomainAllowlist`, etc.) align with the documented “도메인 프로파일 + 화이트리스트” feature and the related toggles (e.g., `naver.filters.domain-allowlist.*`). No code change was necessary beyond ensuring property accessors compile. fileciteturn0file1

### Suggested follow-ups (optional)
- If any further “cannot find symbol” remain, run with `-Xdiags:verbose -Xlint:deprecation,unchecked -Xmaxerrs 2000` and feed the next 100–200 lines back into the pattern scanner.
- If your build still trips on `Duplicate class`, check `DUPLICATE_CLASS_REPORT.md` for the `HybridRetriever` duplicates and keep only one on the compile classpath.

— end —
