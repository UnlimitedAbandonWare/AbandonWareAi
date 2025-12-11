# Patch Log — src111_merge15 → src111_mserge15
Date: 2025-10-27T21:35:19.332369Z

Java root: /mnt/data/work_src111_merge15/src/main/java
Resources root: /mnt/data/work_src111_merge15/app/resources

Added application.yml keys: {
  "path": "/mnt/data/work_src111_merge15/app/resources/application.yml",
  "diff_added": [
    "# added by patch",
    "planner.enabled: true",
    "brave.enabled: true",
    "zero_break.enabled: false",
    "rerank.dpp.enabled: true",
    "fusion.wpm.enabled: true",
    "scoring.calibrator.enabled: true",
    "guard.pii.enabled: true",
    "zsys.timebudget.ms: 3500",
    "zsys.onnx.max-concurrency: 2",
    "cache.singleflight.enabled: true",
    "ocr.retriever.enabled: true"
  ]
}

New Java classes:
- /mnt/data/work_src111_merge15/src/main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java  (pkg=com.example.lms.service.rag.rerank)
- /mnt/data/work_src111_merge15/src/main/java/com/abandonware/ai/service/infra/cache/SingleFlight.java   (pkg=com.abandonware.ai.service.infra.cache)
- /mnt/data/work_src111_merge15/src/main/java/com/example/lms/service/rag/zsystem/TimeBudget.java   (pkg=com.example.lms.service.rag.zsystem)
- /mnt/data/work_src111_merge15/src/main/java/com/example/lms/service/rag/retriever/OcrRetriever.java  (pkg=com.example.lms.service.rag.retriever)
- /mnt/data/work_src111_merge15/src/main/java/com/example/lms/service/rag/fusion/WeightedPowerMeanFuser.java  (pkg=com.example.lms.service.rag.fusion)
- /mnt/data/work_src111_merge15/src/main/java/com/example/lms/service/rag/scoring/ScoreCalibrator.java (pkg=com.example.lms.service.rag.scoring)
- /mnt/data/work_src111_merge15/src/main/java/com/example/lms/service/guard/PIISanitizer.java  (pkg=com.example.lms.service.guard)
- /mnt/data/work_src111_merge15/src/main/java/com/example/lms/service/guard/CitationGate.java  (pkg=com.example.lms.service.guard)

Patched existing files:
[
  {
    "file": "/mnt/data/work_src111_merge15/app/src/main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java",
    "action": "insert_timebudget_dpp_hook"
  },
  {
    "file": "/mnt/data/work_src111_merge15/app/src/main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java",
    "action": "insert_semaphore"
  },
  {
    "file": "/mnt/data/work_src111_merge15/app/src/main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java",
    "action": "signature_relaxation",
    "replacements": 1
  },
  {
    "file": "/mnt/data/work_src111_merge15/addons/formulas_pack/addons/formulas/java/com/abandonware/ai/agent/integrations/RrfFusion.java",
    "action": "insert_calib_wpm_fields"
  },
  {
    "file": "/mnt/data/work_src111_merge15/lms-core/src/main/java/com/abandonware/ai/service/NaverSearchService.java",
    "action": "insert_singleflight_field"
  }
]
