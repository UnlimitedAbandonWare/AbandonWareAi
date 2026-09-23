# Source and platform contract

The original platform investigation was collected 2026-09-12 on canonical `Y:\`. The client implementation and active Gradle owners were rechecked on Desktop on 2026-09-14. Source presence, focused test results, actual service execution and hardware proof remain separate; recheck changed files before implementation.

## Current source ownership

| Evidence location | Contract |
|---|---|
| `build.gradle.kts:780-795` | Root main Java=`main/java`, resources=`main/resources`; tests=`src/test/java` |
| `app/build.gradle.kts:82-89` | App Java=`src/main/java_clean`, resources=`src/main/resources`; app tests empty |
| `build.gradle.kts:800-804,862-870` | Existing isolated `chatUiTest` source set/task |
| `demo.interview.enabled` — `@Value` default `false` (`PageController`, `ConversateController`, `ChatOpenSecurityConfig`); `application-meta-display.yml` sets `enabled: false`; no active config sets `true` | Anonymous core flow preserved: `/api/chat/sync` stays `permitAll` via `chatOpenChain` (`/api/chat/**`); interview page reachable directly at `/assets/interview/index.html`; `/`,`/index`,`/chat-ui` currently serve the legacy `chat-ui` template |
| `main/java/com/example/lms/security/ChatOpenSecurityConfig.java` (`InterviewDemoFilter`, `chatOpenChain`) | `InterviewDemoFilter` exists only when `demo.interview.enabled=true`; when active it allows core sync/assist routes before the legacy chain and 404-strips login/signup/admin. `chatOpenChain` retains cross-origin and local-address checks either way |
| `main/java/com/example/lms/config/AppSecurityConfig.java` (`defaultSecurityFilterChain`) | Protected legacy/admin mode remains available when explicitly selected; not a requirement to add login to the default core UI |
| `main/java/com/example/lms/web/PageController.java` (`home`, `dashboard`, `chatUi`) | Enabled demo forwards home/chat aliases to `/assets/interview/index.html`, whose client uses `/api/chat/sync` |

The implemented client is `main/resources/static/assets/display/{index.html,styles.css,app.js,display-core.js,manifest.webmanifest,favicon.png}`, with `scripts/meta_display_webapp_contract_tests.cjs`. Its exact entry URL is `/assets/display/index.html`; do not rely on directory index or `/display/` aliases. The default local home is `/assets/interview/index.html` and reuses `display-core.js`; do not infer its requests from the inactive legacy `templates/chat-ui.html`. The separate `receiver.html`/`receiver.js` consume the existing volatile assist output and use `scripts/display_receiver_rag_contract_tests.cjs`. Neither client file presence nor a transport ACK proves AI generation or physical display. Keep the existing anonymous core flow; do not scaffold login/signup or change the demo default merely because an older protected route exists.

`settings.gradle` includes `:app`, with historical modules behind `includeLegacyModules`. Source inspection shows Spring Boot 3.3.4 and LangChain4j 1.0.1. Preserve these versions; evaluated Gradle/sourceSet/Java17 proof still belongs to the implementation run.

## Sync request and call path

`main/java/com/example/lms/api/ChatApiController.java:1355`:

`chatSync → public request budget → ChatSessionAccessGuard.authorize → admission → handleChat → ChatService.continueChat → ChatWorkflow.continueChat → PromptContext → PromptBuilder → visible answer/evidence → ChatResponseDto`.

Boundary evidence: controller lines 1361-1371,4228,4410; `main/java/com/example/lms/service/ChatWorkflow.java:2633,2925-2926`; `main/java/com/example/lms/prompt/PromptBuilder.java:13,22`. The first client task has no Java targets and does not need to edit this chain.

`main/java/com/example/lms/dto/ChatRequestDto.java` defines the source fields at lines 27,78,185-186:

```json
{"message":"사용자가 입력한 질문","sessionId":null,"inputType":"text"}
```

This example describes the body contract; do not store actual user questions as proof. `message` is required/nonblank. `PublicRequestBudgetGuard.java:399-406` enforces the budget; the configured default max length is 32768 at 89-90. Client may impose a smaller usability bound without changing the API.

Use the server-returned numeric `sessionId` in subsequent request bodies. Do not send aliases or invent a UUID session ID. Model/useRag/useWebSearch are optional; omission preserves server policy. `inputType=voice` is only justified when the input provenance is actually known to be voice; composer text alone does not prove that. Do not send `systemPrompt`, traits, artificial history or nonexistent `displayMode/maxCharacters/sourcesRequested` fields.

## Actual response and projection

`main/java/com/example/lms/dto/ChatResponseDto.java:12-22,99`:

```text
content, sessionId, modelUsed, ragUsed, answerMode, traceTurnId,
learningContext, evidence, pipelineSnapshot, selectionEntropy
```

There is no success `answer`, `sources`, `status`, or `runToken` field. UI states and a compact `answer/sources` view model are client projections only. Preserve full answer text in client paging; do not silently ask a different prompt or fabricate summaries. The plain-text view omits only the exact internal `<!-- rag-control-projection:v1 -->` HTML comment; retain the seven-stage safety disclosure and the original DTO. When the existing core projects `fallback=true`, label every answer page and its live announcement as a fallback response without exposing the raw model identifier. An ordinary response or unknown model metadata must not be relabelled as fallback.

`main/java/com/example/lms/dto/RagEvidenceMetadata.java:13-24`:

```text
marker, kind, title, source, filePath, lineStart, lineEnd,
rank, confidence, confidenceSource
```

Show only useful citation title/marker and eligible HTTP(S) URL. Do not display internal `filePath`, raw excerpts, or pipeline/learning metadata. Parse with the browser URL parser, require an absolute HTTP(S) URL without username/password, and reject malformed/nonweb schemes, loopback, unspecified, private/link-local IP literals, single-label hosts and `.localhost/.local/.internal` names. Check the normalized hostname, including normalized numeric IPv4 and bracketed/mapped IPv6, not raw string prefixes. Unparseable or ambiguous hosts stay non-clickable; retain the safe citation title. This is a client link filter, not proof that a DNS name is public or that a URL contains no sensitive data. Reuse any stricter existing evidence-promotion policy; never claim public reachability from scheme alone. Use `textContent`/text nodes, not `innerHTML`. Missing/empty evidence means “출처 없음”; `ragUsed=true` does not create a citation or establish correctness.

## Sessions, request identity and errors

Use a relative `/api/chat/sync` fetch with `credentials:'same-origin'`. `OwnerKeyBootstrapFilter.java:63-70` issues HttpOnly ownerKey; `ClientOwnerKeyResolver.java:47-59` distrusts spoofable public owner headers. The browser/server own this cookie. Do not read it or copy it into request bodies, headers, localStorage or URLs.

`ChatSessionAccessGuard.java:45-54` rejects a foreign session with 403 before chat work. Reset client session only as a deliberate “new conversation” action; do not hide ownership errors with automatic retry. A static page has no Thymeleaf CSRF meta. Existing chat CSRF exemption permits sync without a newly invented CSRF endpoint. If future policy changes, use the updated policy rather than weakening it.

`RequestIdHeaderFilter.java:33-52` accepts/generates `X-Request-Id`. Use one client request ID per deliberate submit, then retain the response header when available. `traceTurnId` and optional `X-Trace-Snapshot-Id` are distinct identifiers. Existing header/parser patterns are in `static/js/chat.js:1496-1537`.

| HTTP | Possible existing body |
|---|---|
| 403 | `content=session_forbidden`, `modelUsed=forbidden` |
| 409 | `content=run_active/request_cancelled`, or `error=idempotency_duplicate` |
| 400/413 | `status,reasonCode` budget/body/session validation shape |
| 422 | `error=idempotency_payload_mismatch` |
| 429 | `error=chat_rate_limited`; honor Retry-After when present |
| 503 | `error=chat_admission_unavailable` |
| Network/nonJSON | bounded generic error; no raw response dump |

Evidence: `ChatApiController.java:130-167,1386-1389`, `main/java/com/example/lms/api/ChatGenerationAdmissionFilter.java:63-143,155-158`. Read bounded known reason fields and fall back safely. Send `Idempotency-Key` (a UUID is within the accepted header syntax) for each logical submit; omission disables that duplicate fence. Preserve the key and original serialized body identity for an unresolved operation. The key fences duplicates and does not replay a cached answer: same key/body receives 409, changed body receives 422. Do not mint a new key to bypass either response.

Do not automatically resend after timeout or stream failure. The first version has no same-operation retransmit control; timeout followed by repeated Enter must produce zero additional calls. Editing/selecting a different question is a new deliberate operation, not a retry. Client abort does not prove server cancellation, so report the outcome as unknown. Use one in-flight request and a generation/sequence check so a late reply cannot replace a newer state. Test timeout → Enter as well as ordinary double Enter. A future retry/recovery design must account for server completion explicitly rather than treating fetch settlement as inference settlement.

The current admission filter also requires enabled Redis and its existing DataSource; unavailable admission dependencies return 503 before inference. `OUTCOME_UNKNOWN` claims preserve the fence instead of authorizing a retry. A running local LLM alone therefore does not prove that sync can run. Verify current dependency availability through existing redacted diagnostics when testing local sync; do not disable admission or create a new account/credential to hide this prerequisite.

## UI and official toolkit

Official sources inspected 2026-09-12:

- [Facebook toolkit README](https://github.com/facebook/meta-wearables-webapp)
- [create-webapp contract](https://raw.githubusercontent.com/facebook/meta-wearables-webapp/main/plugins/meta-wearables-webapp/skills/create-webapp/SKILL.md)
- [HTML template](https://raw.githubusercontent.com/facebook/meta-wearables-webapp/main/plugins/meta-wearables-webapp/skills/create-webapp/templates/index.html)
- [navigation template](https://raw.githubusercontent.com/facebook/meta-wearables-webapp/main/plugins/meta-wearables-webapp/skills/create-webapp/templates/app.js)
- [display guidelines](https://raw.githubusercontent.com/facebook/meta-wearables-webapp/main/plugins/meta-wearables-webapp/references/display-guidelines.md)
- [system text input](https://raw.githubusercontent.com/facebook/meta-wearables-webapp/main/plugins/meta-wearables-webapp/skills/add-text-input/SKILL.md)
- [device testing](https://raw.githubusercontent.com/facebook/meta-wearables-webapp/main/plugins/meta-wearables-webapp/skills/test-on-device/SKILL.md)

Use a 600×600 viewport, app description and `<meta name="mrbd-web-app-capable" content="yes">`. Include manifest.webmanifest and a linked PNG icon larger than 52×52 (toolkit default128×128). Manifest includes name/short_name/icons/background_color/theme_color/display=standalone. No mandatory external Meta JS SDK is present in this template; no DAT SDK is needed for the first static UI.

Black acts transparent on the additive display; use bright text and distinguishable dark-gray UI surfaces with safe margins. Keyboard arrows move among visible enabled focusable controls, Enter activates, Escape goes back. Keep focus visible and inside current screen; input/composition handling must not accidentally submit or navigate cards.

System composer opens through user focus+tap on supported input/textarea, and committed text arrives via input/change. Programmatic focus alone does not open it. Do not promise dictation-only or raw microphone access. Provide presets and normal desktop typing when composer is unavailable.

## Separate proof stages

1. **Client contract tests:** synthetic success/error/security/timing fixtures. They prove client behavior, not a provider call.
2. **Local browser:** actual Spring URL, directional flow and one actual sync exchange with cookie/session continuity. Request count must stay one under repeated Enter.
3. **Official Simulator:** [Meta Chrome extension](https://chromewebstore.google.com/detail/meta-ray-ban-display-simu/jpjlmmodokemlepklkdbimceggpbjcll), opened on the app page. Public store version observed 0.5.0, updated 2026-08-26. There is no verified simulator CLI. Record extension presence/use separately from a custom viewport. If unavailable, retain `simulator-unavailable` and continue independent client tests.
4. **Public HTTPS:** only after actual access controls and external-publication authority are established. Current sync is anonymous. Public app-shell accessibility per toolkit does not authorize public inference. Do not expose the whole Spring application with a tunnel as a default implementation step.
5. **Hardware:** actual supported account/device, Developer Mode, public app load, focus, input and real request. Region, firmware, private setup menus and lens usability remain evidence_needed. No purchase, account creation or region workaround is part of stage1.

[Cloudflare Quick Tunnel docs](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/) explicitly exclude SSE and describe temporary test addresses. Keep `/api/chat/stream` unchanged and disconnected from the first Display client. Later streaming needs its own verified transport and access-control task.

## Metrics without invented timing

Client may measure its own round-trip `totalMs` with a monotonic clock, count projected evidence and retain HTTP status/request ID. Label this as client round-trip, not RAG or LLM timing. `ragSearchMs`, `llmFirstResponseMs`, `llmTotalMs` require real existing server stage records; keep null/evidence_needed when absent. Optional developer overlay allowlist: roundTripMs, sourceCount, requestId, httpStatus. UI success, `ragUsed`, hashes or modelUsed do not prove provider/wire lineage. Require correlated prompt/options hash plus attempt/response evidence for that claim.

## Existing verification commands and new fixture contract

Existing commands (not executed by this source audit):

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
node .\scripts\chat_ui_browser_fault_fixture_tests.js
.\gradlew.bat --no-daemon --project-cache-dir $displayProjectCache chatUiTest --tests "com.example.lms.config.ChatUiViewConfigFocusedTest" --tests "com.example.lms.web.ChatFrontendStreamCancellationFocusedTest"
```

Set the host's local Gradle user/project caches and split outputs before Gradle. Existing regression tests are not proof of changed Display files. `scripts/meta_display_webapp_contract_tests.cjs` exercises the actual client module with Node built-ins and deterministic fetch fixtures: real DTO projection, empty citations, unsafe markup/URL (including local hosts, embedded credentials and normalized IP bypasses), heterogeneous errors, cookie options, per-operation idempotency header, one in-flight submit, timeout → Enter with zero retransmits, stale reply suppression and input/navigation separation. Do not add a test framework merely for this task.

Broader API classes exist under `src/test/java`: ChatApiControllerSyncLifecycleTest, ChatApiControllerInputGuardTest, ChatGenerationAdmissionFilterTest, PublicRequestBudgetGuardTest, ChatEvidenceMetadataDtoTest, ChatResponseDtoLearningContextTest and ChatOpenSecurityConfigTest. Use exact package names from current source only when backend changes or a real regression warrants broader tests.
