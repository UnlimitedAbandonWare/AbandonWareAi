# GPU/service failover implementation plan

Goal: Implement the approved bounded service failover and verify behavior with injected failures before any live provider check.
Architecture: Extend existing endpoint health tracker, inference router, request budgets and feature owners. No new gateway, dependency, model index or orchestration framework.
Stack: Java 17, Spring Boot 3.3.4, LangChain4j 1.0.1, existing browser JavaScript.
Spec: ../specs/2026-09-14-gpu-service-failover-design.md (user approved).

Global constraints: preserve dirty preimages, Conversate concurrent changes, SSE retry limit, opnessl bytes, existing properties, source IDs and frozen messages. No credential disclosure, real GPU fault, reboot, deployment, commit, or permission expansion. Parent owns writes; existing source gate is the sole review.

- [x] Endpoint health: failed regressions reproduced, fake-clock recovery/expiry/cancel fences implemented and verified in native/compatible paths.
- [x] Inference chain: registered eligible API successors, direct local factory model names, OPEN preselection, no endpoint/model cycle, original deadline and physical call cap, identical conversation/evidence, cancel/side-effect protection verified.
- [x] Recovery health and effective enablement: bounded fresh GPU/target VRAM checks, explicit disabled configuration preserved; final JAR Spring binding read from the running loopback app. Actual GPU recovery remains separate below.
- [x] Retrieval/embedding: existing fail-soft owner paths and only real corpus reused; accumulated evidence/shape/embedding-space contracts verified. No absent general BM25 corpus fabricated.
- [x] STT/Display: transport loss retains confirmed context/card; bounded text/web fallback; pause/stop, owner, TTL and existing SSE3 fences verified in Java/JS.
- [x] Partial inference output: active controller generates a full model response before UI chunks. Model failure fallback completes before publication; existing non-replayable partial/cancel/tool and final-save contracts retained. No duplicate token-replacement machinery added.
- [x] Redis and optional services: stalled cache read/write bounded within original deadline; existing admission/idempotency fail-closed contract retained.
- [x] Observability: existing diagnostics expose allowlisted route/reason/state/count/time; preselection counter separated from physical attempts; redaction tests pass.
- [x] Available verification executed: dedicated new fault suites 34 passes; final model/debug suite run 108 passes; JavaScript 59 passes; source/dependency/compile/classes/bootJar gates pass; final running binding and browser diagnostics observed.
- [ ] External acceptance evidence: full HTTP/browser inference blocked at admission503; actual API wire receipt, second real eligible provider, actual GPU recovery, microphone/Display/Simulator proof not observed. No bypass of security or device fault injection.
- [x] Exact task postimages, JAR class/resource comparison, scoped secret counts and opnessl preservation checked; owned source leases released. Full evidence report written.

Result: `data/agent-handoff/codex/report/gpu-service-failover-20260914/implementation-verification.md`. Live API adapter responses were observed twice; the second exceeded the original 120-second wall-clock window after compilation. This deviation and an earlier single-hunk guard-rejection reconciliation are recorded, not treated as compliant green evidence. No further live calls were made. PC power loss remains outside in-process failover capability.

Pre-change baseline: 223 unique tests passed, recorded under data/agent-handoff/codex/report/gpu-service-failover-20260914. This is not implementation proof.
