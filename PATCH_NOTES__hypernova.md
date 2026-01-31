# Hypernova Patch Notes

- Applied at: 2025-10-16T07:44:30.722444 UTC
* [2025-10-16T07:44:30.722444] Added Hypernova math & governance stubs under ../../../../home/sandbox/app/src/main/java/service/rag/* (12 files).
* [2025-10-16T07:44:30.722444] Annotated WeightedRRF.java with Hypernova fuse wrapper usage hint.
* [2025-10-16T07:44:30.722444] Annotated WeightedRRF.java with Hypernova fuse wrapper usage hint.
* [2025-10-16T07:44:30.722444] Annotated WeightedRRF.java with Hypernova fuse wrapper usage hint.
* [2025-10-16T07:44:30.722444] Annotated RetrievalOrderService.java with RiskKAllocator hint.
* [2025-10-16T07:44:30.722444] Annotated RetrievalOrderService.java with RiskKAllocator hint.
* [2025-10-16T07:44:30.722444] Annotated RetrievalOrderService.java with RiskKAllocator hint.
* [2025-10-16T07:44:30.722444] Annotated RetrievalOrderService.java with RiskKAllocator hint.
* [2025-10-16T07:44:30.722444] Annotated Pipeline.java with DPP stage hint.
* [2025-10-16T07:44:30.722444] Annotated Pipeline.java with DPP stage hint.
* [2025-10-16T07:44:30.722444] Failed to update application.yml: [Errno 2] No such file or directory: 'app/src/main/resources/application.yml'

## Build error pattern scan (best-effort)
- Build system files: gradlew, settings.gradle
- Scanned log files: 81

### error: cannot find symbol — 149 hits
- BUILD_FIX_NOTES__79_fusion.md:
```
ionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`
- `FusionService.java:40: error: cannot find symbol: class SearchResult`

## Root causes (mapped to internal build-error patterns)
- **OverrideMismatch**: `@Override` pres
```
- analysis/build_patterns_aggregated.json:
```
itelist.isOfficial"
      ],
      "sample_error": [
        "error: package service.rag.auth does not exist",
        "error: cannot find symbol class DomainWhitelist"
      ]
    }
  ]
}
```
### symbol:\s+class\s+\w+ — 104 hits
- BUILD_FIX_NOTES__77_bm25.md:
```
, or record expected` around lines with `} catch (Throwable ignore) { /* analyzer not on classpath */ }`
- `cannot find symbol: class NoriAnalyzer` at:
  - `com.abandonware.ai.agent.service.rag.bm25.Bm25IndexHolder`
  - `com.abandonware.ai.agent.service.rag.bm25.Bm2
```
- BUILD_FIX_NOTES__79_fusion.md:
```
 error: method fuse in class WeightedRRF cannot be applied to given types`
- `FusionService.java:40: error: cannot find symbol: class SearchResult`

## Root causes (mapped to internal build-error patterns)
- **OverrideMismatch**: `@Override` present while the class 
```
### BUILD FAILED — 17 hits
- _assistant/diagnostics/build_error_patterns.json:
```
s not exist",
    "line": "package lombok does not exist"
  },
  {
    "file": "src/fix-and-build.ps1",
    "pattern": "BUILD FAILED",
    "line": "BUILD FAILED"
  },
  {
    "file": "tools/Matrixtxt.snapshot.txt",
    "pattern": "cannot find symbol",

```
- _assistant/diagnostics/build_error_patterns.json:
```
ackage lombok does not exist"
  },
  {
    "file": "src/fix-and-build.ps1",
    "pattern": "BUILD FAILED",
    "line": "BUILD FAILED"
  },
  {
    "file": "tools/Matrixtxt.snapshot.txt",
    "pattern": "cannot find symbol",
    "line": "cannot find sym
```
### found:\s+[\w<>\[\],\s]+ — 11 hits
- BUILD_FIX_NOTES.md:
```
vice,MemoryHandler,MemoryWriteInterceptor,LearningWriteInterceptor,UnderstandAndMemorizeInterceptor,AttachmentService
  found:    no arguments
```

**Root cause**  
`ChatService` is annotated with `@RequiredArgsConstructor` (Lombok), thus it has **no default cons
```
- BUILD_PATTERN_SUMMARY.md:
```
ing `RerankerStatus`.


- `constructor ChatService in class ChatService cannot be applied to given types; required: ... found: no arguments` → **SuperConstructorMissing**
  - Context: Subclass declares no constructor while the parent has only Lombok-generated
```
### required:\s+[\w<>\[\],\s]+ — 9 hits
- BUILD_FIX_NOTES.md:
```
ervice in class ChatService cannot be applied to given types;
class AbandonWareAi_ChatService extends ChatService {
^
  required: QueryTransformer,CircuitBreaker,TimeLimiter,ChatHistoryService,QueryDisambiguationService,ChatModel,PromptService,CurrentModelRepository,RuleEngine,MemoryReinforcementService,FactVerifierService,DynamicChatModelFactory,LangChainRAGService,NaverSearchService,ChatMemoryProvider,QueryContextPreprocessor,StrategySelectorService,StrategyDecisionTracker,ContextualScorer,QueryAugmentationService,SmartQueryPlanner,Environment,QueryCorrectionService,PromptEngine,SmartFallbackService,ContextOrchestrator,HybridRetriever,NineArtPlateGate,PromptBuilder,ModelRouter,RerankerSelector,PromptOrchestrator,StreamingCoordinator,GuardPipeline,VerbosityDetector,SectionSpecGenerator,LengthVerifierService,AnswerExpanderService,MemoryHandler,MemoryWriteInterceptor,LearningWriteInterceptor,UnderstandAndMemorizeInterceptor,AttachmentService
  found:    no arguments
```

**Root cause**  
`ChatService` is annotated with `@RequiredArgsConstructor` (Lombok), thus it has
```
- tools/Matrixtxt.snapshot.txt:
```
turn new AnalyzeWebSearchRetriever(koreanAnalyzer, svc, maxTokens, preprocessor, smartQueryPlanner);
               ^
  required: no arguments
  found:    Analyzer,NaverSearchService,int,QueryContextPreprocessor,SmartQueryPlanner
  reason: actual and formal argument lis
```
### error: method does not override or implement a method from a supertype — 9 hits
- BUILD_FIX_NOTES__79_fusion.md:
```
# Build Fix Notes — src111_merge15 (79)

## Symptoms
- `Bm25LocalRetriever.java:26: error: method does not override or implement a method from a supertype`
- `FusionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`
- `FusionService.j
```
- tools/Matrixtxt.snapshot.txt:
```
lass ChatApiController
C:\AbandonWare\demo-1\demo-1\src\main\java\com\example\lms\service\impl\ChatServiceImpl.java:23: error: method does not override or implement a method from a supertype
    @Override
    ^
C:\AbandonWare\demo-1\demo-1\src\main\java\com\example\lms\service\impl\ChatServiceImpl.java:25: er
```
### error: package [\w\.]+ does not exist — 4 hits
- analysis/build_patterns_aggregated.json:
```
mainWhitelist",
        "replace whitelist.isAllowed -> whitelist.isOfficial"
      ],
      "sample_error": [
        "error: package service.rag.auth does not exist",
        "error: cannot find symbol class DomainWhitelist"
      ]
    }
  ]
}
```
- build_error_patterns_summary.json:
```
ms.service\nC:\\AbandonWare\\demo-1\\demo-1\\src\\main\\java\\com\\example\\lms\\service\\impl\\ChatServiceImpl.java:8: error: package com.example.lms.service.ChatService does not exist\nimport com.example.lms.service.ChatService.ChatResult;"
  ],
  "unreachable_statement_finally": [
    "Unreachable sta
```
### error: incompatible types: — 3 hits
- tools/Matrixtxt.snapshot.txt:
```
pe
    @Override
    ^
C:\AbandonWare\demo-1\demo-1\src\main\java\com\example\lms\service\impl\ChatServiceImpl.java:25: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResu
```
- tools/Matrixtxt.snapshot.txt:
```
pe
    @Override
    ^
C:\AbandonWare\demo-1\demo-1\src\main\java\com\example\lms\service\impl\ChatServiceImpl.java:31: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResu
```
