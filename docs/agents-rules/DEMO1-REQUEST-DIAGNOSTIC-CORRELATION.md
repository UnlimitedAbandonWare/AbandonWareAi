<!-- moved-from: AGENTS.md L331-L336 sha256=e4fa755b679dc255f5c934b68426cc50ad15cd2f471327178abe242b38d58fce movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-REQUEST-DIAGNOSTIC-CORRELATION -->
### Same-request diagnostic evidence
- Keep the debugged request distinct from the current diagnostic request. Record `providerSurface` and `toolId` with evidence; an empty correlated result must stay empty instead of falling back to global recent errors. Reuse normalized correlation hashes without hashing them again.
- Correlation hashes, a snapshot ID, and `ToolContext` strings identify evidence but do not establish ownership. Use the existing owner check before stored trace or answer-bundle access; describe bounded ring events and durable chat projections separately, with unavailable history explicit.
- Reuse `DebugEventStore`, `TraceSnapshotStore`, the answer trace bundle, `DebugCopilot`, and the existing MCP toolbox. Diagnostic suggestions use registered tool IDs and validated arguments; never execute generated shell strings. ZIP checksums establish file integrity only, not server origin or complete history.
<!-- END DEMO1-REQUEST-DIAGNOSTIC-CORRELATION -->
