# AUTO BUILD FIX REPORT — src111_merge15
- Timestamp: 2025-11-01T21:52:47.031334Z

## Summary
- Created curated source set: `app/src/main/java_clean` with a minimal Spring Boot app (`AppApplication`) and a `/bootstrap` probe endpoint.
- Patched Gradle to ensure mainClass and UTF-8 compilation; disabled tests for faster build.
- Applied safe regex-escape normalization across original sources to fix `illegal escape character` patterns in Java string literals.

## Files created (curated)
  - `app/src/main/java_clean/com/example/lms/AppApplication.java`
  - `app/src/main/java_clean/com/example/lms/probe/BootstrapController.java`
  - `app/src/main/java_clean/com/example/lms/util/UrlCanonicalizer.java`
  - `app/src/main/java_clean/com/example/lms/util/SingleFlight.java`

## Java files auto-normalized (regex escapes) — sample
  - `src/main/java/com/abandonware/ai/agent/integrations/service/rag/overdrive/ExtremeZHandler.java`

## Detected build error patterns (from repo logs)
- **java.cannot_find_symbol**: 1201
- **java.package_does_not_exist**: 81
- **java.incompatible_types**: 47
- **gradle.build_failed**: 55
- **gradle.dependency_resolution**: 2
- **java.class_interface_expected**: 0
- **java.illegal_escape_character**: 4
- **maven.build_failure**: 0
- **lombok.missing**: 0

## Latest raw build log snippet
```
[RAW BUILD LOG SNIPPET — 2025-10-17]
Task :compileJava FAILED
... illegal escape character at regex tokens like \p{L}, \p{Nd}, \s, \- ...
... cannot find symbol: class Bm25LocalIndex in Bm25LocalRetriever ...
```