# Interview Demo Implementation Plan

**Goal:** One local homepage for RAG chat and explicit question, hint or short-answer delivery to a Display output client, with no login or administrative demo navigation.

**Architecture:** Reuse `/api/chat/sync` and DisplayCore without altering RAG prompts or orchestration. Reuse ConversateSessionService's volatile card, SSE, expiry and ACK lifecycle. The existing chat security owner limits the default local demo to these routes. The homepage previews cards without claiming device delivery; a separate output-client ACK is required and is still not hardware proof.

**Tech Stack:** Existing Java 17, Spring Boot, HTML/CSS/JavaScript, Node built-in tests, isolated Gradle chatUiTest. No new production dependency or public deployment.

**Authority:** The current user explicitly delegates design and requests source implementation, auth/UI removal and local HTTP simplification. Preserve anonymous RAG owner cookies, admission, all RAG algorithms, protected properties, unrelated work and locks. This supersedes the older Display-only file limit for the newly requested UI/auth/transport scope.

- [x] Characterize existing behavior and run focused RED for HTTP LAN crypto, short-card limits and honest ACK states.
- [x] Freeze the existing three-query preflight; acquire a scoped source lease, back up current target bytes and verify the preimage.
- [x] Implement default interview route allowlist and homepage; use existing session publish/output/ACK for manual display cards, with redacted counts/reasons.
- [x] Verify Node contracts, Java security/controller contracts, compile and isolated runtime/browser behavior. Preserve runtime/provider/hardware gaps explicitly.
- [x] Record only this task's diff, pre/post hashes, exact commands, verification results and rollback guidance. Release the owned lease.

**Protected assets:** RAG core, anonymous conversation isolation, provider/admission contracts, existing diagnostic source and recovery settings, private preparation material and concurrent writers. Former account/admin routes are absent from the enabled demo; diagnostic capabilities are retained behind an explicitly documented recovery setting.

**Acceptance:** Homepage shows input, request state, full answer/evidence and explicit display card state together. Original questions remain unchanged. Display text over 120 Unicode code points is rejected rather than silently shortened. Empty/expired/stale cards and missing ACK stay visibly unconfirmed. Browser logs store stage, counts, safe IDs, timings and reason codes only. HTTP LAN works without secure-context randomUUID. Cross-origin writes and unrelated legacy routes are rejected. No synthetic test is reported as real RAG or hardware success.

**Platform evidence:** Meta's current official toolkit describes public HTTPS for hardware webapp testing: https://raw.githubusercontent.com/facebook/meta-wearables-webapp/main/plugins/meta-wearables-webapp/skills/test-on-device/SKILL.md . Local HTTP browser/receiver proof and actual glasses proof remain distinct. No tunnel or Sites publication is part of this local task.

**Execution result:** Source, focused verification, full runtime and browser output ACK were observed. The one real RAG request stopped with HTTP 503 `chat_admission_unavailable` before generation. RAG semantics, provider attempts, official Simulator and hardware remain evidence_needed. See the task report; no complete-goal claim.
