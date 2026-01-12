PATCH NOTES — src111_mersge15 (2025-10-15)

Fix: Build failure in KnowledgeGraphHandler.java
- Root causes:
  1) InterruptedException not handled from Semaphore#tryAcquire(timeout, unit)
  2) Unreachable statement: return placed after finally

Changes:
- src/main/java/com/abandonware/ai/service/rag/handler/KnowledgeGraphHandler.java
  * Wrap tryAcquire in try/catch (InterruptedException), restore interrupt flag, return emptyList on interrupt.
  * Guard semaphore release with 'acquired' flag to avoid IllegalRelease.
  * Consolidate early returns; remove post-finally return that was unreachable.

Operational:
- Appended patterns to build_error_patterns_summary.json:
  * interrupted_try_acquire
  * unreachable_statement_finally

How to run:
  ./gradlew :app:bootRun --args='--spring.profiles.active=prod'

