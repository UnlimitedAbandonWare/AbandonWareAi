# src111_merge15 — Patch summary
- Split DTOs into separate public classes: SearchProbeRequest, SearchProbeResponse, CandidateDTO, StageSnapshot.
- Fixed `CombinedSoakQueryProvider` to implement `SoakQueryProvider#queries(String)` and corrected logging.
- Updated `TasksApiController#askAsync` to match `JobService` API and run asynchronously using `CompletableFuture`.
- Repaired `ProbeConfig` sample pipeline to remove `...` artifacts.
- Added build error patterns registry at app/resources/dev/build/ERROR_PATTERNS.json.
