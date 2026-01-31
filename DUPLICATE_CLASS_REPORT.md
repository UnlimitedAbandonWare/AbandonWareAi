# Duplicate Class Report: HybridRetriever

During the patch process a duplicate definition of `HybridRetriever` was
detected.  The primary file targeted for repair is:

```
src/main/java/com/abandonware/ai/agent/integrations/HybridRetriever.java
```

A second file with the same class name exists at:

```
app/src/main/java/com/abandonware/ai/agent/integrations/HybridRetriever.java
```

This secondary file appears to be a stub (only 26 lines) and was not
modified as part of this patch.  If both source sets are compiled
simultaneously, the duplicate definitions could result in build errors or
class‑shadowing.  No action was taken in this bundle, but this duplication
should be addressed in a future change by either deleting the stub or
ensuring only one copy is on the classpath.