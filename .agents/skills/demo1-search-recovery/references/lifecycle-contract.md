# Search correction lifecycle contract

Read this table when choosing the next observation. Use existing request-bound receipts and diagnostic tools; do not create a shadow pipeline. Preserve raw sensitive queries/bodies outside public reports; use IDs, hashes and allowlisted span references.

## Stage classification

| Observation class | Minimum distinguishing observation | Do not infer |
|---|---|---|
| provider execution | Current provider enabled/disabled reason, attempted/executed receipt and output before filtering | A selected provider label does not prove it ran; disabled does not mean trace absent |
| admission | Current plan/policy/budget allow/deny with request join | Denied execution is not executed-empty |
| alias | Effective alias-to-provider/model/plan resolution and active call path | A familiar name is not the active owner |
| timeout | Attempt deadline, overall deadline and terminal outcome | Timeout is not empty, disabled or cancellation |
| empty | Executed provider's raw returned result count is zero | Missing stage metadata or no citable locator is not proof of zero output |
| filter | Nonzero upstream output, kept/rejected counts and reasons | Filter starvation is not an upstream search miss; do not relax domains by default |
| fetch | Request-bound URL identity, fetch status and extraction outcome | HTTP200, redirects or a URL alone do not prove usable content |
| mainbody | Extracted text contains the required subject, fact and relationship span | Title/snippet/irrelevant body is not support; model failure alone is not absent trace |
| packing | Required supporting span survives truncation/compression and retains provenance | Candidate count or metadata-only packing is not body inclusion |
| dispatch | Authoritative sent model-input receipt contains that body/span and current question, with request/turn join | Builder state, Sources append, completed metadata and context counts are not model receipt |
| citation | Output claim is supported by the dispatched span and locator; intended meaning is useful | releaseAllowed/evidenceGatePassed proves publication policy only |
| cancel recovery | Cancel event/propagation, owned-resource cleanup, terminal state and subsequent new request | Source RED, missing Stop video or an unrelated timeout does not prove the reported UI incident |

Attach OBSERVED, NOT_OBSERVED, NOT_PROVEN, NOT_RUN or NOT_READ to the object being assessed. A missing ZIP is ZIP NOT_READ, not a conclusion about its contents. A model-input receipt can be NOT_OBSERVED while search execution is OBSERVED. A proved receipt with incorrect output directs the next probe downstream, rather than another upstream search.

Find the earliest break using spans/IDs, not mismatched count labels. Missing intermediate stages remain unknown even when a downstream body is observed; do not invent a loss at every gap. If extraction has the requested relation and packing lacks it, test that boundary; if packing is unproved, inspect it before naming dispatch as the confirmed defect.

## Correction and replay

Retain the original question and follow-up wording, settings and allowed domain/provider choices. Write a falsifiable hypothesis and disconfirming observation, then one narrow RED reproduction. Keep source-extracted RED, focused tests, browser observation and actual provider-wire evidence distinct. A test setup failure is not that contract's RED. Repair the confirmed boundary only and rerun the same distinguishing test.

A/B acceptance uses the existing five-slot contract, not two unrelated fresh sessions. Required spans must support A's useful answer and B's newly requested fact/referent. Do not weaken assertions to match a wrong answer. A correct answer string alone cannot prove retrieval or question-specific grounding.

Where the shared cancel path changes, cover Stop before first token and the ordinary after-token path, repeated submit/Stop if that state is affected, and a new search request after cancellation in the same session. Check stale timer/lock cleanup, cancellation propagation and late completion that could overwrite the new turn. The new request must pass the applicable provider-to-citation path; do not mark its evidence stages N/A merely because the preceding request was cancelled. The cancelled request need not produce an answer. Never infer a video Stop action that was not observed.

When a second correction fails, replace the next observation with one that distinguishes the remaining causes (for example mainbody span, packing loss, effective admission rule or actual dispatch receipt). Preserve contradictory evidence. Switching providers, restricting everything to official sources, lowering citation gates or paying for a stronger model requires demonstrated causality; none substitutes for missing bodies.

## Current-artifact verification

For code work authorized by its own task, use the active sourceSet/owner and established build/reload path. Record source hash, built/installed artifact hash and provenance, serving process/version and the live request that exercised it. File date, a journal's historical PASS, unit exit0 or HTTP200 cannot replace runtime evidence.

For skill-only work, verify current SKILL/reference/metadata hashes, typed source/pairedArtifact registration, UTF-8 and actual offline use in fresh contexts. Skill static validation is not behavior testing; behavior testing is not product runtime testing. Automatic skill selection stays NOT_RUN without a real selection/invocation receipt.

## Small example

Current request R extracts a fact/relationship span, but the packed body omits it; dispatch receipt is unavailable. The demonstrated break is mainbody-to-packing, and reception remains unproved. Reproduce span preservation RED, fix the existing packer if authorized, then replay unchanged same-session A/B and cancel/new-search cases on the installed runtime. Do not insert the expected answer or treat six URLs as acceptance.
