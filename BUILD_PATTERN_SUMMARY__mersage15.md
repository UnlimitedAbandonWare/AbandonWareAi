# BUILD ERROR PATTERN SUMMARY — src111_mersage15


## Matched patterns (by frequency)

1) ONE_PUBLIC_TOP_LEVEL_TYPE
   - Symptom: `enum AbstractionLevel is public, should be declared in a file named AbstractionLevel.java`
   - Fix: consolidate enums as *nested* types under `CognitiveState`, or split into their own files.
   - Action: replaced `CognitiveState.java` with a compact class that declares the four enums as nested types.

2) MISSING_SYMBOL / IMPORT_REQUIRED
   - Symptom: `cannot find symbol Autowired | Function | Environment | CognitiveState | QueryCorrectionService`
   - Fix: add imports for Spring/Java util packages and ensure referenced types exist.
   - Action: inserted imports in `ChatService__backup_pre_interface.java`, created stubs where necessary.

3) MISSING_CONSTANT
   - Symptom: `RewardScoringEngine.SimilarityPolicy → SIMILARITY_FLOOR`, `RewardScoringEngine.DEFAULT`
   - Fix: define constants.
   - Action: added `public static final double SIMILARITY_FLOOR = 0.60` and `public static final RewardScoringEngine DEFAULT`.

4) MISSING_BEAN_PROPERTIES (Lombok / @ConfigurationProperties)
   - Symptom: `NaverFilterProperties.isEnableDomainFilter()` etc.
   - Fix: confirm fields and Lombok getters.
   - Action: class already had `@Getter @Setter`; no code change needed. (Call sites now compile once other errors are cleared.)

5) PACKAGE_DRIFT (backup namespace)
   - Symptom: `_abandonware_backup/.../BuildLogSlotExtractor` expects `RawSlot.Stage` + builder.
   - Fix: bring backup `RawSlot` up to parity.
   - Action: rewrote `_abandonware_backup/.../RawSlot.java` with `@Value @Builder` + nested `Stage`.

6) DTO/ENTITY GETTERS (SelectedTerms, SynergyStat, DomainKnowledge, Hyperparameter)
   - Symptom: `getExact/getMust/...`, `getPositive/getNegative`, `getDomain/getEntityName`, `getParamKey/getParamValue`
   - Fix: ensure Lombok annotations or explicit accessors.
   - Action: verified Lombok usage; added `@AllArgsConstructor` to `Hyperparameter` to fix constructor mismatch.

> Related runtime feature references: the web-source whitelist & filters used by `NaverSearchService` align with the Domain Profile Loader and filter toggles described in the “추가기능 전용” memo (see `naver.filters.domain-allowlist.*` and related flags). fileciteturn0file1
