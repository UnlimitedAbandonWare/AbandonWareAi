# AUTO_PATTERN_APPLY_REPORT

Scanned build-error artifacts and applied deterministic fixes.


## Pattern: cannot_find_symbol_NovaNextFusionService
- Fix: Added local stub com.nova.protocol.fusion.NovaNextFusionService with nested ScoredResult; wire real impl via profile later.
- Occurrences: 0

## Pattern: nested_class_ScoredResult_missing
- Fix: Used the same stub; ensured imports in RrfHypernovaBridge.
- Occurrences: 0

## Pattern: duplicate_field_kalloc_nprops
- Fix: Removed duplicated @Autowired fields (RiskKAllocator kalloc, NovaNextProperties nprops) keeping first declaration per class.
- Occurrences: 0

## Pattern: non_static_access_hypernovaBridge
- Fix: Removed 'static' from methods referencing instance field hypernovaBridge in WeightedRRF.
- Occurrences: 0

## Pattern: incompatible_types_ContextSlice_List
- Fix: Added RrfHypernovaBridge.postProcess(List<Double>) to maintain List-in/List-out contract.
- Occurrences: 0

## Pattern: syntax_missing_parenthesis
- Fix: Added missing ')' in RuleBreakInterceptor HmacSigner.verifyAndDecode call.
- Occurrences: 0

## Pattern: method_cannot_be_applied_to_given_types__WeightedRRF_fuse
- Symptom: `method fuse in class WeightedRRF cannot be applied to given types; required: double,double,double; found: List<List<ContextSlice>>,int,Map<String,Double>,ScoreCalibrator,boolean`.
- Fix: Implemented new overloads in WeightedRRF to accept rich inputs:
  - `com.abandonware.ai.service.rag.fusion.WeightedRRF#fuse(List<List<ContextSlice>>, int, Map<String,Double>, ScoreCalibrator, boolean)`
  - `com.example.rag.fusion.WeightedRRF#fuse(List<List<Map<String,Object>>>, int)` (legacy SelfAsk).
- Occurrences: 3 (FusionService.java, RrfFusion.java, SelfAskPlanner.java).

## Pattern: cannot_find_symbol__OnnxCrossEncoderReranker.rerankTopK
- Symptom: `cannot find symbol rerankTopK(List<ContextSlice>,int)`.
- Fix: Added convenience overload `OnnxCrossEncoderReranker#rerankTopK(List<ContextSlice>, int)` that sorts by `ContextSlice.getScore()` with a semaphore guard.
- Occurrences: 1 (RerankOrchestrator.java).
