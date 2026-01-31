# Patch Notes — src111_merge15 (Job/Soak compile fix)

## Summary
Fixed `:compileJava` failure by aligning the Task API with a richer JobService contract and by exposing public default implementations for soak testing.

## Changes
1. **jobs**
   - `com.example.lms.jobs.JobService` — expanded API:
     - Added default overload `enqueue(String jobType, Object payload, Map<String,Object> metadata, String correlationId)`.
     - Added generic async hook `<T> void executeAsync(String jobId, Supplier<T> work, Consumer<T> onSuccess)`.
   - New public class `com.example.lms.jobs.InMemoryJobService` — in‑memory executor and status store.
   - `JobConfig` already wires `InMemoryJobService` as a bean.

2. **soak**
   - Split package‑private classes into public top‑level classes so they can be wired from `SoakConfig`:
     - New `com.example.lms.service.soak.DefaultSoakQueryProvider`
     - New `com.example.lms.service.soak.DefaultSoakTestService`
   - Trimmed old helper declarations from:
     - `SoakQueryProvider.java` → **interface only**
     - `SoakTestService.java` → **interface only**

## Rationale
- Controller `TasksApiController` expects `JobService.enqueue(type, payload, meta, correlId)` and `executeAsync(...)`.
- `SoakConfig` referenced `DefaultSoakQueryProvider` and `DefaultSoakTestService` which were previously package‑private and not visible across packages.

## Affected error signatures (before)
- `method enqueue in interface JobService cannot be applied to given types`
- `cannot find symbol: method executeAsync(...)`
- `cannot find symbol: class InMemoryJobService`
- `cannot find symbol: class DefaultSoakQueryProvider`
- `cannot find symbol: class DefaultSoakTestService`

## After
- All symbols are provided. The Job API matches controller usage; default soak classes are public and bean‑creatable.

## Notes
- The in‑repo build‑error pattern scanner flagged prior incidents such as regex escape issues and missing symbols; none of those touch the modified files. See `BUILD_ERROR_PATTERNS.json` and `BUILD_PATTERN_SUMMARY.md` for historical context.
