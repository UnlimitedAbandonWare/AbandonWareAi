# Request-Scoped Provider Proof Prompt-Only Correction Plan

## Goal

Correct the registered Codex postprocess prompt pack so a future evidence run can correlate application prompt/options hashes, provider attempts, and observed responses per request without adding a public API or changing active application source.

This plan repairs prompt governance only. It does not implement runtime telemetry.

```text
executionMode=prompt_only
publicApiChanges=forbidden
sourceMutationAllowed=false
runtimeProofClaimAllowed=false
lineageSchema=awx.request-provider-proof.v1
```

## Live Evidence Boundary

- Registered owner: `agent-prompts/agents/demo1_three_perspective_chat_postprocess/**`.
- Manifest owner: `agent-prompts/prompts.manifest.yaml`.
- Generated output: `agent-prompts/out/demo1_three_perspective_chat_postprocess.prompt`.
- Existing prompt contracts pass their six focused tests, but that is static prompt evidence only.
- A Browser-visible synthetic response can prove the local chat DOM completed; it cannot prove provider/wire lineage.
- If no request-scoped runtime rows exist, record `evidence_needed` and keep the runtime verdict at `HOLD`.

## Non-Goals

- No edits to `main/java`, `main/resources`, `src/test`, `scripts`, Gradle files, public REST/SSE DTOs, DB/DDL, or Supabase.
- No new controller, endpoint, background service, provider call, runtime judge, always-on review fan-out, or agent broker.
- No raw prompt, options, response, request ID, credentials, queries, provider error body, or environment dump in artifacts.
- No claim that Browser, Computer, or Supabase evidence substitutes for provider/wire evidence.

## Prompt Contract

Every proof row is grouped and ordered by:

```text
requestIdHash -> logicalCallOrdinal -> attemptOrdinal -> evidenceBoundary
```

Persistent digests use full run-scoped HMAC-SHA-256 and include:

```text
hashAlgorithm=hmac-sha256
hashKeyScope=run_ephemeral
hashKeyScopeId=<non-secret run scope identifier>
canonicalizationVersion=awx-canon-json-v1
canonicalizationProfileHash=<full SHA-256 of the resolvable profile>
hashSubject=request_id|prompt_messages|options|application_response|http_request_body|http_response_body
evidenceBoundary=application|client_http|provider_ack|wire
```

`requestIdHash` is the boundary-neutral `hashSubject=request_id` join key created once per request; `evidenceBoundary` scopes payload digests, not this join key. The run key is CSPRNG-generated with at least 256 bits of entropy, shared in memory by all emitters and the joiner for exactly one run, and never persisted. Only non-secret `hashKeyScopeId` identifies the scope; missing or mismatched IDs force `ambiguous`.

`awx-canon-json-v1` is the exact 567-byte UTF-8 value after `canonicalizationProfileSpec=` below. The prefix and line ending are excluded and there is no final LF. String escaping is fixed to quote/backslash, short b/t/n/f/r, lowercase u00xx for remaining controls, unescaped NFC UTF-8 for other scalars, and lone-surrogate rejection. Separators are comma/colon without whitespace. Numeric domains are signed int64 and finite IEEE-754 binary64 with the stated shortest-roundtrip/exponent/fixed rules. A run envelope with a different profile hash cannot be exact.

```text
canonicalizationProfileSpec=awx-canon-json-v1|encoding=UTF-8|unicode=NFC|objectKeys=unicode-codepoint-ascending|separators=comma-colon-no-whitespace|arrayOrder=preserve|messageToolMultipartOrder=preserve|null=explicit-marker|absent=omitted|boolean=true-or-false-lowercase|stringEscape=quote-backslash-short-b-t-n-f-r-control-as-lowercase-u00xx-other-scalars-unescaped-reject-lone-surrogate|integerDomain=signed-int64|integer=minimal-base10|decimalDomain=finite-ieee754-binary64|decimal=shortest-roundtrip-lowercase-e-no-plus-no-exp-leading-zero-fixed-if-1e-6-le-abs-lt-1e21|-0=0|nonFinite=reject
canonicalizationProfileHash=sha256:3376ee6d54ba358ebc425903428cbe2eb9b688673d673bd5e0d6f7d7c5314a4c
```

Required row fields are count/hash/boolean/allowlist only:

```text
requestIdHash
logicalCallOrdinal
attemptOrdinal
logicalAttemptIdHash
evidenceBoundary
promptHash
promptHashSubject
promptByteCount
messageCount
optionsHash
optionsHashSubject
optionKeyCount
applicationAttemptObserved
providerAttemptObserved
wireAttemptObserved
responseObserved
responseKind
responseHash
responseHashSubject
responseByteCount
providerResponseObserved
wireResponseObserved
outcome
failureClass
```

Required request aggregates:

```text
proofRowCount
logicalCallCount
physicalAttemptCount
providerAttemptCount
providerResponseCount
attemptTotal
retryCount
attemptDropped
duplicateRowCount
malformedRowCount
distinctPromptHashCount
distinctOptionsHashCount
distinctResponseHashCount
correlationStatus=exact|partial|missing|ambiguous
rawPromptStored=false
rawOptionsStored=false
rawResponseStored=false
```

`correlationStatus=exact` requires unique four-field row keys, monotonic ordinals, one shared run HMAC scope, a matching profile hash, truthful observation flags, and zero dropped/duplicate/malformed rows. Boundary truth is fixed: application owns only application observation; client_http may own directly observed provider/client attempts and HTTP responses; provider_ack owns only verified provider acknowledgements; wire owns only wire capture. A true flag never propagates across boundaries. Synthetic disabled text and self-asserted SSE flags are not provider/wire proof.

Run envelope metadata is `hashAlgorithm`, `hashKeyScope`, `hashKeyScopeId`, `canonicalizationVersion`, and `canonicalizationProfileHash`. Row digest metadata is `promptHashSubject`, `optionsHashSubject`, and `responseHashSubject`; missing envelope or subject metadata forces `partial`. `client_http.providerAttemptObserved=true` means outbound-to-provider was observed at the client, not provider receipt. Receipt requires `provider_ack`. Provider attempt/response aggregates count each distinct `(requestIdHash, logicalCallOrdinal, attemptOrdinal)` tuple once, never boundary rows.

The literal artifact matrix is identified and hashed as follows. Hash input is the 23 listed tokens in display order, LF-joined with no final LF, encoded as UTF-8.

```text
requiredTokenMatrixId=awx-lineage-required-tokens-v1
requiredTokenMatrixHash=sha256:ba4c8a1d33b98b62e734357f6991a15520e2a45c897cf7f63d7e31eab5968dfb
requiredTokenMatrix=awx.request-provider-proof.v1|requestIdHash|logicalCallOrdinal|attemptOrdinal|evidenceBoundary|promptHash|optionsHash|responseHash|hashKeyScopeId|canonicalizationVersion=awx-canon-json-v1|canonicalizationProfileHash=sha256:3376ee6d54ba358ebc425903428cbe2eb9b688673d673bd5e0d6f7d7c5314a4c|providerAttemptCount|providerResponseCount|artifactVerdict|runtimeLineageVerdict|packetType=FALSIFY|packetType=NEUTRAL|proofRowCount=2|physicalAttemptCount=1|correlationStatus=ambiguous|rawPromptStored=false|rawOptionsStored=false|rawResponseStored=false
```

## Deterministic Scenario Bag

Use synthetic seed `LINEAGE-20260729-V1`.

1. One request with attempts 0/1/2, two retryable failures, three distinct response hashes, then success.
2. Interleaved A0, B0, A1 rows; A aggregation must exclude B.
3. Provider-disabled and pre-exchange failure; provider attempts/responses remain zero and hashes absent.
4. Application-boundary and direct HTTP owner side by side; each may claim only its owned fields.
5. One valid row, one exact duplicate, and one malformed row; rejected rows cannot inflate success counts.
6. One logical call has one application row plus one worker-thread client_http attempt-0 row with the same `logicalAttemptIdHash`; expect `proofRowCount=2`, `logicalCallCount=1`, `physicalAttemptCount=1`, and duplicate 0.
7. Known capacity drop maps to `attemptDropped>0`, `partial`, `HOLD`; ordinal/owner-unknown opaque retry maps to `ambiguous`, `HOLD`.

## Independent Review

Run exactly:

1. `SUPPORT_CONTRACT`
2. `SUPPORT_SCENARIO`
3. `FALSIFY`
4. `NEUTRAL`

The first three are independent read-only packets. NEUTRAL receives only the three packets and current evidence, checks A-B and B-A ordering, and returns `APPLY | HOLD | REJECT` without majority voting. Prompt artifact `APPLY` does not imply runtime `APPLY`.

## Verification

```powershell
python -X utf8 scripts\test_three_perspective_chat_postprocess.py
python -X utf8 agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_three_perspective_chat_postprocess
python -X utf8 scripts\test_agent_prompt_secret_patterns.py
```

Also verify manifest IDs are unique, generated output equals the manifest merge, an explicit lineage-schema required-token matrix is complete, the standalone `/goal` prompt starts with `/goal` and ends with `[DONE]`, and changed-file secret-pattern hits are zero without printing matches. Baseline prompt tests alone do not prove the lineage artifact or runtime.

## Acceptance

- Prompt pack defines the complete lineage schema, truthful boundary matrix, bounded aggregation, privacy contract, deterministic scenarios, independent review, and fail-closed neutral gate.
- The prompt and generated output contain no public-API or source-mutation authorization.
- Browser proof, when run, is reported separately from provider proof.
- Report `artifactVerdict` and `runtimeLineageVerdict` separately; optional `neutralVerdict` is a non-authoritative summary only.
- `artifactVerdict=APPLY` requires fresh manifest-merge equality plus the explicit lineage-schema required-token matrix.
- Runtime lineage remains `evidence_needed` until current application evidence rows prove it; prompt text, baseline tests, browser labels, and synthetic output do not prove provider exchange.
