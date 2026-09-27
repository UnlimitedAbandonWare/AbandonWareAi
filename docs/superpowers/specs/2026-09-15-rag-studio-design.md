# RAG Studio: shared engine, separate web experience

## Goal and choice
Add a portfolio-quality, anonymous web workspace at `/assets/interview/studio.html`, preserving the current interview delivery workspace and the lightweight Display app. Both use the existing `/api/chat/sync` transport and `PromptBuilder` backend. Extend existing HTML instead of adding a second backend or moving the Java runtime into a static hosting service.

## Boundaries
- `display-core.js`: retain transport/session/idempotency/error behavior; allow an optional response projector for the richer web surface. Default Display projection stays unchanged.
- `rag-inspector.js`: pure, bounded projection of existing public DTO metadata and count-only telemetry. Never expose filePath, learningContext, snippets, raw diagnostics or credentials.
- `studio.html`, `studio.css`, `studio.js`: web conversation, evidence explorer, five-stage explanation, routing/quality metrics and session diagnostics. Use text nodes and validated citation links.
- `studio-voice.js`: optional browser speech recognition; transcript is editable and only an explicit submit calls the existing RAG client. Existing Display ASR remains untouched.
- Current interview homepage: link to the new web workspace and retain all existing card/ACK behavior.

## Truthful observability
The sync transport supplies a terminal snapshot, not streaming stage events. During a request, mark only input as sent and backend processing as pending. At completion distinguish observed evidence, server-reported context counts and unobserved stages. Confidence and finalSigmoid are labeled as scores, never calibrated probabilities. Browser round-trip is distinct from unavailable retrieval/model latency. Count observed fallback responses once per request, not server retry attempts. Do not infer local/API routing from arbitrary model-name substrings.

## Preservation
Preserve properties, PromptBuilder, rate/cost admission, same-origin checks, session ownership, Display voice/transcription/polling/card/ACK flows and all unrelated dirty files. Active source lease overlaps Display voice/security; do not edit those paths. Index-independent changes only while the pre-existing Git index lock exists.

## Verification and delivery
Add focused Node contracts for DTO sanitization, missing metrics, fallback counts, transcript lifecycle and actual web DOM behavior. Run existing Display and interview regressions. Start the existing owned Spring launcher with isolated outputs/cache, verify anonymous page and actual sync plus negative security routes, then browser layout/interaction checks. Reuse the registered HTTPS tunnel only after actual public allowlist/ownership checks. Public URL and live provider response are separate acceptance criteria; report missing runtime or deployment evidence explicitly. No fixture represents live generation or hardware proof.

## Execution ledger
GLM configured but withheld: CLI 0.144.1 and gpt-5.6-sol multi_agent_version=v2 retain transport HOLD. One native explorer identifies backend seams. Control Tower plan probe timed out (45 seconds); local source and focused commands remain available. Current Meta selector has stale task binding; no prior receipt is promoted to fresh proof.
