{
  "java_src_root": "src/main/java",
  "resources_root": "src/main/resources",
  "added_or_ensured": [
    "src/main/java/com/nova/protocol/fusion/RrfHypernovaBridge.java",
    "src/main/java/com/nova/protocol/fusion/BodeClamp.java",
    "src/main/java/com/nova/protocol/properties/NovaNextProperties.java",
    "src/main/java/com/nova/protocol/alloc/RiskKAllocator.java",
    "src/main/java/com/nova/protocol/alloc/SimpleRiskKAllocator.java",
    "src/main/java/com/nova/protocol/rerank/Dpp.java",
    "src/main/resources/application-nova-next.yml",
    "src/main/resources/plans/hyper_nova.v1.yaml"
  ],
  "rulebreak_fixed_files": [
    "app/src/main/java/com/nova/protocol/filter/RuleBreakInterceptor.java",
    "src/main/java/com/nova/protocol/filter/RuleBreakInterceptor.java"
  ],
  "note": "Placed com.nova.protocol.* under the module's active source root so imports resolve."
}