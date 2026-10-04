<!-- moved-from: AGENTS.md L234-L238 sha256=d18d67ba0339b1a9f9f171ffdd143431e2cd531208038a709e5cfcedf5083bde movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-BUILD-PRUNE-RULE -->
## Build artifact prune (pre-approved)
- `build/` under this root is pure Gradle/verification output — never source. Deleting subdirectories of `build/` via `scripts/prune_build_artifacts.ps1` (default `-Days 7`, or `-DryRun`) is **pre-approved** (no lease/checkpoint/confirmation for that path).
- Scope limit: only directories **inside** `build/` selected by the script's age filter — never `main/`, `app/`, `src/`, `scripts/`, `data/agent-handoff/`, or hand deletion outside the script. `var/` is NOT covered (live launcher/runtime state).
<!-- END DEMO1-BUILD-PRUNE-RULE -->
