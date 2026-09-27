/goal

# demo-1 Request-Scoped Provider Lineage Prompt-Only Goal

## Objective

공개 API와 active application source를 수정하지 않고, Codex prompt/Markdown 후처리 계약만 교정한다. 최종 prompt는 앱의 각 요청에서 prompt/options digest와 provider-attempt/response 증거를 count/hash-only로 연결하도록 요구하되, 실제 runtime row가 없으면 구현됐다고 주장하지 않는다.

```text
executionMode=prompt_only
runBudgetMinutes=540
maxScenarioAttempts=56
seed=LINEAGE-20260729-V1
lineageSchema=awx.request-provider-proof.v1
publicApiChanges=forbidden
sourceMutationAllowed=false
runtimeProofClaimAllowed=false
```

## Authority And Scope

1. Current checkout and actual command/browser output.
2. Root `AGENTS.md`, registered `demo1_three_perspective_chat_postprocess` pack, and active sourceSet proof.
3. This goal and linked prior reports.
4. Official primary documentation only when an unknown fact changes the decision.

Allowed edits:

- `agent-prompts/**`
- explicitly named Markdown under `docs/**`

Read-only:

- `main/java`, `main/resources`, `src/test`, `scripts`, Gradle files
- Browser, Computer, Supabase, provider/runtime logs

Forbidden:

- public route/DTO/SSE changes
- DB/DDL or Supabase mutation
- new runtime agent judge, broker, background service, or always-on fan-out
- raw prompt/options/response/request ID/query/credential/full error storage
- source patch inferred only from Browser fallback or missing provider evidence

## Preflight

Confirm root, branch, `.git/index.lock`, dirty ownership, PatchDrop top-level count, source leases, active sourceSets, and count-only secret hits. A live Git process owns its lock; a zero-byte stale lock may be moved only with explicit user authority after process count is zero.

## Exact Count/Hash-Only Contract

Group rows by:

```text
requestIdHash -> logicalCallOrdinal -> attemptOrdinal -> evidenceBoundary
```

`requestIdHash` is a boundary-neutral `hashSubject=request_id` join key created once per request; `evidenceBoundary` scopes only payload digests. Persistent digests are full `hmac-sha256:<64hex>` with `hashKeyScope=run_ephemeral`, non-secret `hashKeyScopeId`, `canonicalizationVersion=awx-canon-json-v1`, `canonicalizationProfileHash`, `hashSubject`, and when applicable `evidenceBoundary`. Generate the run key with a CSPRNG and at least 256 bits of entropy, share it in memory across all emitters and the joiner for exactly one run, and never persist it. Missing or mismatched scope IDs force `ambiguous`. Never compare different payload subjects, boundaries, versions, profiles, or run scopes.

Require a run envelope with `hashAlgorithm`, `hashKeyScope`, `hashKeyScopeId`, `canonicalizationVersion`, and `canonicalizationProfileHash`. Require per-row `promptHashSubject`, `optionsHashSubject`, and `responseHashSubject`; missing envelope or subject metadata forces `partial`.

`awx-canon-json-v1` is the exact 567-byte UTF-8 value after `canonicalizationProfileSpec=` below. Exclude the prefix and line ending; append no final LF. String escaping is quote/backslash, short b/t/n/f/r, lowercase u00xx for remaining controls, unescaped NFC UTF-8 for other scalars, with lone surrogates rejected. Separators are comma/colon without whitespace. Numeric domains are signed int64 and finite IEEE-754 binary64 using the stated shortest-roundtrip/exponent/fixed rule. A different run-envelope profile hash forbids exact.

```text
canonicalizationProfileSpec=awx-canon-json-v1|encoding=UTF-8|unicode=NFC|objectKeys=unicode-codepoint-ascending|separators=comma-colon-no-whitespace|arrayOrder=preserve|messageToolMultipartOrder=preserve|null=explicit-marker|absent=omitted|boolean=true-or-false-lowercase|stringEscape=quote-backslash-short-b-t-n-f-r-control-as-lowercase-u00xx-other-scalars-unescaped-reject-lone-surrogate|integerDomain=signed-int64|integer=minimal-base10|decimalDomain=finite-ieee754-binary64|decimal=shortest-roundtrip-lowercase-e-no-plus-no-exp-leading-zero-fixed-if-1e-6-le-abs-lt-1e21|-0=0|nonFinite=reject
canonicalizationProfileHash=sha256:3376ee6d54ba358ebc425903428cbe2eb9b688673d673bd5e0d6f7d7c5314a4c
```

Require row fields:

```text
requestIdHash logicalCallOrdinal attemptOrdinal logicalAttemptIdHash
evidenceBoundary promptHash promptHashSubject promptByteCount messageCount
optionsHash optionsHashSubject optionKeyCount
applicationAttemptObserved providerAttemptObserved wireAttemptObserved
responseObserved responseKind responseHash responseHashSubject responseByteCount
providerResponseObserved wireResponseObserved outcome failureClass
```

Require aggregates:

```text
proofRowCount logicalCallCount physicalAttemptCount providerAttemptCount providerResponseCount attemptTotal retryCount
attemptDropped duplicateRowCount malformedRowCount
distinctPromptHashCount distinctOptionsHashCount distinctResponseHashCount
correlationStatus=exact|partial|missing|ambiguous
rawPromptStored=false rawOptionsStored=false rawResponseStored=false
```

Truth rules:

- boundary matrix: `application` may directly set only application attempt/response truth; `client_http` may set provider/client attempt and HTTP response truth only when directly owned; `provider_ack` may set provider response truth only for a verified acknowledgement; `wire` may set wire truth only for owned capture. Never propagate true across boundaries.
- `client_http.providerAttemptObserved=true` means outbound-to-provider was observed at the client, not provider receipt. Receipt requires `provider_ack`. `providerAttemptCount` and `providerResponseCount` count distinct `(requestIdHash, logicalCallOrdinal, attemptOrdinal)` tuples once, never boundary rows.
- `responseHash` exists only when the named boundary owns observed response bytes/text.
- provider-disabled or pre-exchange failure has provider attempt/response count 0, provider/wire flags false, and provider response hash absent.
- synthetic disabled output is `responseKind=synthetic_disabled`, never provider response proof.
- Browser/SSE self-asserted flags are not provider/wire proof.
- interleaved requests are filtered by requestIdHash before aggregation.
- duplicate/malformed rows do not increment success/attempt counts.
- async/nested fixture: one logical call emits one application row plus one worker-thread client_http attempt-0 row with the same logicalAttemptIdHash; expect proofRowCount=2, logicalCallCount=1, physicalAttemptCount=1, duplicateRowCount=0.
- known `attemptDropped>0` or row/count mismatch forces `partial` and `HOLD`; ordinal/owner-unknown opaque retry or log rotation/truncation/late-flush ownership failure forces `ambiguous` and `HOLD`.
- 540 minutes or 56 attempts is a hard maximum, not a target to fill.

Artifact required-token matrix: hash the following 23 tokens in display order as UTF-8, LF-joined with no final LF.

```text
requiredTokenMatrixId=awx-lineage-required-tokens-v1
requiredTokenMatrixHash=sha256:ba4c8a1d33b98b62e734357f6991a15520e2a45c897cf7f63d7e31eab5968dfb
requiredTokenMatrix=awx.request-provider-proof.v1|requestIdHash|logicalCallOrdinal|attemptOrdinal|evidenceBoundary|promptHash|optionsHash|responseHash|hashKeyScopeId|canonicalizationVersion=awx-canon-json-v1|canonicalizationProfileHash=sha256:3376ee6d54ba358ebc425903428cbe2eb9b688673d673bd5e0d6f7d7c5314a4c|providerAttemptCount|providerResponseCount|artifactVerdict|runtimeLineageVerdict|packetType=FALSIFY|packetType=NEUTRAL|proofRowCount=2|physicalAttemptCount=1|correlationStatus=ambiguous|rawPromptStored=false|rawOptionsStored=false|rawResponseStored=false
```

## Scenario Bag

Run deterministic synthetic scenarios, never random unrestricted chat:

- S1-S8 from the registered pack.
- S9: one request, attempts 0/1/2, two retries, three distinct response hashes.
- S10: A0, B0, A1 interleaving with B excluded from A.
- S11: provider-disabled and pre-exchange zero-attempt/zero-response truth.
- S12: application-only versus direct HTTP ownership.
- S13: valid, exact duplicate, and malformed row separation.
- S14: fixed async fixture has two boundary rows, one logical call, one physical attempt, and zero duplicates.
- S15: known capacity drop is `partial`; ordinal/owner-unknown opaque retry is `ambiguous`; both are `HOLD`.

Browser may prove DOM completion, terminal state, cancel/reload, and visible warnings. It cannot prove provider/wire receipt. Computer is used only for Windows UI evidence that Browser cannot provide. Supabase remains read-only `evidence_needed` unless project-scoped auth is proven.

## Independent Agents

Create three independent read-only packets:

1. `SUPPORT_CONTRACT` — schema, canonicalization, cardinality, boundaries, privacy.
2. `SUPPORT_SCENARIO` — S9-S15 and existing chat scenarios.
3. `FALSIFY` — misjoin, collision/dictionary risk, duplicate/drop, disabled/synthetic response, self-asserted provider flags, stale/static evidence.

Then give only those packets and current evidence to `NEUTRAL`. It checks A-B and B-A input order and returns `APPLY | HOLD | REJECT`. This is manual Codex review, not a production runtime judge and not majority voting.

## Query Rewriting And Web Facts

When an ambiguous probe blocks a decision, use Self-Ask then exactly A/B/C query rewrites. Search the web only for decision-changing unknowns and use official documentation, original research, or official repositories. Record URL and date, never raw search/query/provider bodies.

## Verification

Run the registered prompt test, prompt build, manifest uniqueness check, manifest-merge/output equality check, standalone first/last-line check, and changed-file count-only secret scan. Do not run Gradle merely for prompt-only edits.

Browser completion is reported separately:

```text
browserDomResult=<verified|evidence_needed>
providerLineageResult=<verified|evidence_needed>
wireAttemptCoverage=<observed|not_observed>
```

## Final Report

```text
runId
seed
scenarioId
observation
supportReports
falsifyReport
artifactVerdict=APPLY|HOLD|REJECT
runtimeLineageVerdict=APPLY|HOLD|REJECT
neutralVerdict=<optional non-authoritative summary only>
evidenceIds
failureClass
toolDecision
nextAction
```

Prompt artifact completion and runtime provider-lineage completion are separate claims. If runtime request rows are absent, finish the prompt correction but leave runtime proof as `evidence_needed` and do not close the larger chatbot goal.

[DONE]
