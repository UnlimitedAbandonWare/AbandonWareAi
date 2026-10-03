# A3 — Track API contract map (no code change)

Start this track after NW core is green, or only on files that do not overlap the NW edit and the Devin lease. Directive priority wins over the long SSOT when they disagree: `DIRECTIVE_CODEX_ma312in_api_orch.md`.

Live `agent.tools.web-search.enabled` default is the `@Value` in `ChatApiController` line 956: `${agent.tools.web-search.enabled:${AGENT_WEB_SEARCH_ENABLED:false}}`. No `agent.tools.web-search.enabled` key was found in `*.yml` / `*.yaml` / `*.properties`. Leave the live default OFF. Spy tests may set the property. Do not turn live search on.

## P0-A — CUE label vs search scope

File: `main/java/com/example/lms/assist/ConversateApiCueService.java`.

| Step | Lines | Observed | Contract |
|---|---|---|---|
| Jev call | 97–99 | null advisor → `Advice.off()`. `NO_CUE` → `defer`, not a revive | Jev off/fail keeps the local cue |
| Verdict → cue label | 102–107 | `WEB`, `HYBRID`, and `SCOPED_RAG` all become `RAG_CUE`. `RECENT_ONLY` becomes `CUE` | label `RAG_CUE` is not a license to widen scope |
| Retrieval | 128–142 | only when `cueDecision.equals("RAG_CUE")`. Empty prepared material then calls `retrieve(...)` if cost allows | `SCOPED_RAG`: empty prepared material does **not** become web. `RECENT_ONLY`: this method does not enter the block, so it does not start a new search here |

`RECENT_ONLY` in this method is not a whole-product proof that no vector search runs later. Other call sites are `evidence_needed`.

Existing test: `src/test/java/com/example/lms/assist/ConversateApiCueServiceTest.java`.

Spy sketch `cueScopeDoesNotWiden` (Codex writes it):

- `SCOPED_RAG` + empty prepared material → external search spy count 0; cue may still render.
- `RECENT_ONLY` → search spy count 0 and no new vector/memory query from this service.
- `WEB` → spy may be called once inside the existing budget.
- advisor null or failed → same branch as today's local rules, no extra provider.

## P0-B — Focus call order

File: `main/java/com/example/lms/assist/NovaFocusAnswerService.java`, image overload `answer` starting line 51.

Observed order:

1. `TraceStore.clear()` (52).
2. Web bit from `decisions.decide` / `focusEvidenceRequested` (53–55), before ownership.
3. `jevAdvisor.advise(...)` (58), including when an image is present. The image guard is only on applying the verdict (60: `usable && !imagePresent`).
4. `SCOPED_RAG` is grouped with `RECENT_ONLY` and forces `web=false` (63). `useRag` is hardcoded `false` (68). That is not scoped retrieval of prepared material.
5. `budgets.validateChatProjected` (78).
6. `runs.beginOrJoin` ownership (79–80). Not owner → `focus_busy`.
7. Default `TimeBudget(60000)` if none is set (83).
8. Cancel check and `memories.retrieve` (94–96).
9. Second projected budget check (103).
10. `capRequestWait(Long.MAX_VALUE)` (105) then model execution.

Wanted order: ownership, cancel, applicability, external-send allowed → base budget → Jev only if the request will use it → if search volume grows, cost recheck → then search.

Image branch: do not call evaluate for a verdict that will be ignored (`ON` and `SHADOW`).

Existing test: `src/test/java/com/example/lms/assist/NovaFocusAnswerServiceTest.java` (already spies `RECENT_ONLY`).

Spy sketch `focusJevRunsAfterOwnership`:

- Count `advise` calls. Image request → 0.
- `advise` is not invoked before a failed ownership/cancel fixture.
- `SCOPED_RAG` does not set web search and does not call a new global vector search.
- `RECENT_ONLY` does not call a new vector search.
- One logical request id → one evaluate. A late ON result still `remember`s (directive P1 note; not re-read here).

## P1 — early-stop

File: `main/java/com/abandonware/ai/agent/integrations/AcmeAICoreGateway.java`.

Line 107: `if (returnedCount > 0) break;` stops the provider loop on the first non-empty bundle, **before** `fuseAndRank` (133) and before the id join that can drop rows.

After the filter, line 173 sets reason `after-filter-starvation` when ranked ids miss bundle docs. That reason does not start another provider.

Contract: first non-empty bundle is not "enough evidence". If the post-filter set is short, call **one** next allowed provider, not all of them. Prefer a `missingEvidenceKind` query over repeating the whole question. Tavily `search_depth=basic`, `auto_parameters=false`, `include_answer=false` was not re-read in this file; Codex confirms the call site before setting those fields.

Spy sketch `earlyStopWaitsForFilteredEvidence`: provider A returns docs that all drop in the filter; provider B is invoked once; provider C is not.

## P1 — mask then cut

`ApiSpendAttribution.safe` (111–123): length > 96 takes `substring(0, 24)` **before** the `sk-` / `gsk_` / `bearer ` check (120–122). A long secret prefix can be cut into the clear.

`safeModel` (102–108) cuts model labels at 64 with no secret-prefix check.

Sketch: a synthetic string longer than 96 that starts with `sk-` is stored as `redacted_len=...` with no raw prefix. Do not put a real key in the test.

## Budgets that stay separate

| Budget | Observed home | Do not |
|---|---|---|
| Public request wall | `public.request-budget.max-time-budget-ms` in `application-llm.yaml` | fold into agent spend or Focus 60s |
| Agent spend guard | `configs/agent-api-spend-guard.yaml` `production_hard_caps: false` | turn the flag into a chat timeout, or set every cap off |
| Focus default | `TimeBudget(60000)` at NovaFocus line 83 | replace with `Long.MAX_VALUE` (`capRequestWait(Long.MAX_VALUE)` at 105 and 109 is a wait-cap input, not a product fix for this assist) |
| Device voice | not read | do not merge |

`vercel-glm` uses `AI_GATEWAY_API_KEY` only. Do not place `ZAI_API_KEY` on the gateway. OpenCode Zen Jev is a later note, not this Done.
