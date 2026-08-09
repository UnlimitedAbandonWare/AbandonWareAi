# Owned Runtime Browser Restart Design

Date: 2026-08-10

## Objective

Provide one Desktop-local workflow that validates the current checkout, preserves Ollama and other declared infrastructure ports, cleans up only a previously started and provably owned demo-1 runtime, starts a fresh runtime, and hands the exact fresh URL to the in-app Browser for visible `/chat-ui` verification.

The workflow must make a failed verification or ambiguous process owner safe: the existing runtime remains untouched and no unrelated listener is terminated.

## Existing Seam

Extend the existing `scripts/chat_ui_vibe_listener.ps1` and its contract tests. Keep `scripts/chat_ui_vibe_soak.ps1` as the downstream consumer. Do not add a second lifecycle framework and do not move lifecycle management into Spring application code.

The existing listener already detects listeners, starts a hidden `bootRun`, writes a PID file, probes `/chat-ui`, and emits JSON. The implementation will strengthen its ownership, protected-port, verification, cleanup, port-selection, and provenance contracts while preserving existing callers.

## Scope

In scope:

- Desktop PowerShell lifecycle orchestration for the canonical checkout.
- Pre-restart Gradle verification.
- Protected-port discovery and explicit additions.
- Owned-runtime manifest and process-lineage validation.
- Safe cleanup of a prior owned runtime.
- Automatic loopback port selection when an unowned listener blocks a requested port.
- Fresh-runtime HTTP and static-asset provenance.
- Exact in-app Browser target handoff and visible Browser verification.
- Contract and synthetic process tests.

Out of scope:

- Java or resource behavior changes.
- Stopping Ollama, databases, reverse proxies, unrelated Java/Gradle jobs, Node tools, or foreign processes.
- Supabase mutation or database verification.
- Provider/model semantic-success claims.
- Replacing `chat_ui_vibe_soak.ps1` or changing the main chatbot contract.

## Safety Invariants

1. Verification occurs before any existing runtime is stopped.
2. A process may be stopped only when the current canonical-root hash, manifest run token, PID creation identity, and process lineage all match.
3. Raw command lines are never written to result artifacts. Store hashes, counts, PIDs, ports, timestamps, and reason codes only.
4. Ports `11434`, `11435`, and `11438` are protected by default. Every active `ollama.exe` listener is dynamically added to the protected set. `-ProtectedPorts` may add but never remove protection.
5. A protected port is never selected for the application and its owner is never stopped.
6. An unowned or ambiguously owned listener is never stopped. The workflow selects another free loopback port or fails closed when a fixed port is required.
7. Cleanup is complete only after the owned PID tree has exited and every owned application port is no longer listening.
8. Readiness is not freshness. Freshness additionally requires a new run token, listener-to-launched-process lineage, process start time after the launch boundary, and served `chat.js` hash matching the live source file.
9. Browser success proves only the fresh local UI surface that was inspected. It does not prove provider generation, Supabase, or whole-chatbot correctness.

## Interface

Keep existing parameters compatible and add these behaviors:

- `-Port 0`, `-ManagementPort 0`, and `-NettyPort 0` request distinct automatically selected loopback ports.
- `-ProtectedPorts 11434,11435,...` adds caller-required protected ports.
- `-FixedPorts` makes an occupied unowned requested port a failure instead of selecting a replacement.
- `-SkipVerification` is an explicit escape hatch; normal restart verification is on by default.
- `-VerificationTasks <string[]>` defaults to `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, and `compileJava`.
- `-StatePath` defaults under `var/codex-runtime/` and identifies the single current owned runtime manifest.
- `-PlanOnly` performs discovery and emits the proposed action without stopping or starting anything.

A prior runtime referenced by a fully valid current-root manifest is an automatic cleanup candidate after verification. Keep `-CloseConflictingListener` as a compatibility switch for existing callers; it never broadens ownership and never authorizes killing a foreign or protected listener. An explicitly requested protected port always fails with `protected-port-conflict`, even when automatic port selection is otherwise enabled.

## Runtime Manifest

Write the current manifest atomically only after the fresh runtime passes all readiness and provenance gates. The manifest contains:

- schema version;
- canonical-root hash, never a raw alternate/UNC mapping;
- run-token hash and a non-secret run identifier;
- launcher PID, listener PID, process creation timestamps, and parent-lineage hash;
- server, management, and Netty ports;
- source `chat.js` hash and served `chat.js` hash;
- start and ready timestamps;
- output/error log paths relative to the canonical root when possible.

On restart, validate the entire identity tuple before cleanup. A stale PID reused by another process is not owned. A malformed, foreign-root, or partially matching manifest yields `owned-runtime-attribution-failed` and no process mutation.

## Lifecycle

1. Resolve the script root to the canonical checkout and reject execution from another root.
2. Discover requested ports, active listeners, active Ollama listener ports, the prior manifest, and candidate owned processes.
3. Build the protected-port set and select three distinct loopback application ports.
4. Emit the plan. In `-PlanOnly`, stop here.
5. Run the configured Gradle verification with Desktop split outputs and isolated Gradle/project caches.
6. If verification fails, emit `verification-failed`; preserve the current runtime.
7. Re-read listeners and the prior manifest to close the time-of-check/time-of-use gap.
8. Stop only the validated prior owned process tree, using PowerShell process APIs. Wait for process exit and port release; fail closed on timeout.
9. Launch `bootRun` hidden with the selected ports, stable host ID, isolated caches, and a unique non-secret run token passed to the application child so later listener ownership can be revalidated without relying on a surviving Gradle parent.
10. Require the server-port listener to descend from the launched process tree and to have started after the launch boundary.
11. Require HTTP 200 from `/chat-ui` and the static JavaScript asset. Hash the live source asset and served bytes and require equality.
12. Atomically publish the new manifest and result JSON with `browserTargetUrl` containing the selected port and run identifier.
13. The Codex Browser lane opens exactly `browserTargetUrl`, verifies the expected chat DOM, confirms no page-level error cue or console error, and records count/hash-only evidence.

## Failure Contract

| Status | Meaning | Mutation |
| --- | --- | --- |
| `ready-to-restart` | Plan and ownership checks passed | None in plan mode |
| `protected-port-conflict` | An application port intersects the protected set | None |
| `foreign-port-owner` | A fixed requested port has an unowned listener | None |
| `owned-runtime-attribution-failed` | Prior manifest/PID/lineage cannot prove ownership | None |
| `verification-failed` | Gradle verification failed | Existing runtime preserved |
| `owned-runtime-stop-timeout` | Verified process or port did not close | No new runtime start |
| `fresh-runtime-provenance-failed` | New listener, HTTP, lineage, or asset hash failed | Stop only the newly launched owned runtime |
| `listener-ready` | New runtime and browser target are fresh and proven | New runtime remains running |

## Verification Design

PowerShell contract tests will cover:

- default and dynamically discovered Ollama ports are protected;
- caller-protected ports are additive;
- requested application ports are distinct and never protected;
- a foreign synthetic TCP listener is not stopped;
- automatic selection moves away from a foreign listener;
- fixed-port mode fails without mutation;
- a valid synthetic owned manifest/process can be cleaned up;
- stale PID reuse, token mismatch, root mismatch, and creation-time mismatch cannot be cleaned up;
- cleanup waits for PID exit and port release;
- failed verification leaves an existing owned runtime running;
- manifest publication is atomic and contains no raw command line or secrets;
- readiness alone cannot pass without lineage and asset-hash provenance;
- existing `chat_ui_vibe_soak.ps1` can still consume the listener result.

Runtime verification will then run the focused PowerShell tests, the default Gradle verification tasks, start one fresh server, and use the in-app Browser on the emitted URL. Browser evidence will check `/chat-ui` visibility, fresh target port, page identity cues, and console errors. Computer Use remains a supporting lane only if browser control cannot inspect required Windows UI state.

## Completion Criteria

The work is complete only when current evidence proves all of the following:

1. Ollama and declared protected listeners remain alive and unchanged.
2. A foreign listener survives a restart attempt.
3. A prior provably owned runtime is closed and its ports are released.
4. Source verification passes before cleanup in the successful path and prevents cleanup in the failing path.
5. A new runtime starts on non-protected ports with fresh process lineage and matching served/source asset hashes.
6. The in-app Browser opens the emitted fresh URL and visibly verifies `/chat-ui` without console errors.
7. Running the lifecycle workflow does not modify Java/resources, Supabase state, unrelated dirty files, Git state, or the PatchDrop queue.
