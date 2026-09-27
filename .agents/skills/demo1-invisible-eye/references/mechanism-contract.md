# Mechanism evidence and discriminating probes

Read when an investigation needs detailed evidence stages, competing explanations or a bounded experiment. These are analytical contracts, not instructions to activate features.

## Evidence ledger and insight

Each evidence row contains `id, sourceRef, buildOrRevision, observedAt, collectionBoundary, fact, limitation`. Use a file/line, actual command with exit status, or a precise official URL. Unknown version/time remains unknown. Mark user recollection as `reported`, deductions as `inferred`, synthetic examples as `synthetic`. Repeated citations to one source do not become independent evidence.

Write an insight as: **The observation suggests mechanism M under conditions C; therefore observation P should follow. Evidence E supports it; alternative A remains; observation F would falsify it.**

Search for an internal state that would make the surprising result ordinary. Useful questions:

- Which conservation expectation broke: one request/one attempt, one registration/one callback, one state transition/one effect? First verify that this expectation is actually promised.
- Which prerequisite must exist for the observed effect: dispatcher registration, loader, host binding, capability negotiation, queue or persisted state?
- Is a visible toggle merely the presentation layer for a capability with independent build, registration, permission and runtime gates?
- What differs between a failing and working case at the first shared boundary?
- Does a less visible mechanism explain several independent observations and predict one new observation better than the current story?

A promising capability may be predicted as conditional. Preserve `predicted` until the needed build and execution evidence exists. Do not convert code archaeology into an activation claim.

## MechanismStages schema

For the exact implementation and build, output each of:

`present, included, reachable, enabled, executed, causal`

Each value is `{status: supported|ruled-out|unknown|confounded, evidenceIds, scope, missingProof}`. “Supported” always names the claim supported (for example, `enabled=false`), so a disabled feature is not mislabeled as active.

| Stage | Positive evidence | What it does not prove |
|---|---|---|
| present | Exact source definition, symbol or registration record | Current artifact inclusion or availability |
| included | Matching build manifest and artifact/class/symbol inventory | Live registration, linkage or execution |
| reachable | Matching entry/caller/registration path and executable boundary | Current effective guard values or whether a request took the path |
| enabled | Observed effective flags, privileges, state and initialization for that instance | An actual invocation |
| executed | Correlated entry/attempt/response or trace on the target instance | That it caused the reported symptom |
| causal | Reproducible differential observation, controlled intervention or a complete causal trace, with confounders addressed | Motive or generalization to other builds |

Do not require a new experiment when an existing complete trace already resolves the claim. Conversely, if the source-set and exhaustive artifact inventory exclude a particular implementation, rule out that implementation for that build. Other implementations remain separate claims. A zero search hit from an incomplete corpus stays unknown.

BoundaryMap rows: `from -> condition/transform -> to | evidenceIds | known/unknown`. Never fill missing edges with remembered architecture. Match the inspected build to the running build before drawing a runtime conclusion.

## Hypotheses and probe schema

### ObservationContract — make the invisible measurable

Use when the measurement itself is ambiguous. Record `observable, unit, layer, runOrAttemptId, timeWindow, coverage, observerEffects, evidenceIds`. Examples of different units are business actions, application writes, bytes, transport segments, receiver effects and log rows. A zero count is evidence of absence only within proven coverage and a working collection path. Record buffering, sampling, capture duplication and clock alignment when they affect the proposed distinction.

If H1 and H2 predict the same currently recorded outputs, preserve `indistinguishableUnderCurrentObservations=[H1,H2]`. This is bounded observational ambiguity, not proof of program equivalence on every input. Select a boundary where predictions differ. Repeating the same non-discriminating observation or adding another opinion does not identify the mechanism. If that boundary is inaccessible, name the missing sensor or artifact and leave this claim unknown.

### ConditionModel — state plus time

Use when a capability depends on flags, initialization or action order. Record `predicate, evaluationEvent, stateAtEvaluation, actionOrder, registrationResult, reevaluationPath, invalidationOrReset, evidenceIds`. Retain unknown fields; derive actual predicate semantics from the implementation, not a property's name.

For a synthetic startup-only gate `licensed && vmReady && sessionArmed`, all three values being true after startup does not establish that registration happened. Compare the state **at the gate evaluation**, its result and the registration record. An already-registered entry can also survive later flag changes if there is no invalidation path. Do not invent such a path from the final flag values. A successful registration still does not prove invocation or debugger effect.

Before a new state-changing experiment, inspect existing startup traces or the registration caller. If an authorized experiment is needed, compare action order at equivalent process lifecycle points and define reset/rollback. Distinguish the failure to register from failure to invoke an entry that is registered.

Hypothesis row: `id, explanation, causalAxis, supports, conflicts, prediction, falsifier, currentStatus`.
Differentiate alternatives at one causal axis. Where causes can coexist, include the joint case or separate axes; “retry” and “double registration” need not exclude each other.

Probe record:

```text
status: proposed | reviewed-evidence | executed | blocked
executionOwner: self | provided-artifact | none
performedNow: true only for an actual probe tool execution in this task
question: the one uncertainty this observation resolves
targetAndBuild: proven identifier or unknown
commandOrObservationRequest: grounded command, or precise evidence request
authorizationBasis: existing request/scope
fixedConditions: what must remain comparable
changedVariable: one variable or none for read-only observation
predictions: H1 -> expected observation; H2 -> different observation
falsifies: the hypothesis an outcome would contradict
timeoutSeconds: 30 by default
outputLimit: bounded fields/rows
observed: actual result from the named owner; null for proposed/blocked
confounders: residual ambiguity
rollback: required for a permitted state change; otherwise not applicable
```

Choose the existing read-only observation that distinguishes the leading explanations most directly. Do not fabricate CLI flags or use a placeholder command as if runnable. If product identity or CLI syntax is unknown, `commandOrObservationRequest` is the precise missing artifact request. A proposed probe earns no validation credit.

When the user supplies a previously verified trace or trial, use `status=reviewed-evidence`, `executionOwner=provided-artifact`, `performedNow=false`. Its results may support the mechanism, but do not claim a new test ran. Reading that description is not execution of the described test. Reserve `executed` for a probe actually run through a tool in this task, with its real command/operation and result. If provenance is unspecified, preserve that limitation.

## Example: duplicate actions

Synthetic evidence: one business submit, two recorded writes and three captured packets, with retry permitted. There are no correlated attempt IDs yet.

| Hypothesis | Prediction | Falsifier |
|---|---|---|
| Timeout retry generates the second write | Same logical request, distinct attempts and a timeout before the second write | Proven complete trace has one attempt and no retry while two writes originate elsewhere |
| Duplicate registration invokes the handler twice | Two callback/subscription origins for one logical event | Proven single registration and callback; second write belongs to a later retry |
| Measurement inflates the count | Repeated observation IDs or multiple capture points, with one underlying write | Independent complete write records prove two distinct invocations |
| Registration plus retry both contribute | Separate callback branches and a retry edge are present | Complete trace excludes either required branch |

First inspect an existing correlated attempt/caller trace. If it does not exist and instrumentation is outside scope, request that one trace rather than editing source. Treat missing telemetry as a measurement gap. Distinguish logical requests, application writes, transport retransmission, packet segmentation and capture duplication. Packet count alone cannot locate a library defect.

## Dormant features and virtualization

For Lua/debug clues, trace host binding -> registration -> script loader -> effective guard -> invocation -> effect. A Lua string does not establish a kernel driver, host process or available API. A branch in unreleased source says nothing about the user's installed version without a matching artifact.

For virtualization, map physical CPU -> host hypervisor -> guest -> guest application/backend, plus observation location. A capability flag, virtual NIC or a successful launch does not establish backend selection, acceleration or nested virtualization use. A disabled acceleration initialization followed by a fallback must stay visible. Compare equivalent boundaries; a similar UI effect is not architectural equivalence.

## Why a feature is not public

Separate four claims: intended code behavior, technical design rationale, actual release decision, and a person's private motive. Code can establish the first. Dependencies, test failures or compatibility constraints may support a labeled inference about the second. A dated release decision or attributable owner explanation can document the third. The fourth is unknown without relevant direct evidence, and even a stated reason is evidence of the statement rather than access to private thought.

Candidate explanations may include incomplete testing, compatibility, support cost or a licensing condition **only as alternatives grounded in observed artifacts**. A licensing check proves a condition, not the commercial reason for withholding. A useful next observation is the relevant decision record, not more speculation about a person.

## Explaining and transferring an insight

An `InsightCard` contains:

```text
observedContrast: comparable observations and their E-IDs
hiddenMechanism: the proposed internal state or boundary, with epistemic status
activationCondition: predicate plus evaluation event, or not applicable
newPrediction: an observation not already used to invent the explanation
falsifier: a result that would contradict the explanation
reproduction: one grounded command or precise artifact request
provenanceAndScope: build, observer, performedNow, and unavailable evidence
```

Explain the decisive mismatch in ordinary language before displaying fields. Share allowlisted evidence references and deductions, not hidden chain-of-thought, private conversations or raw sensitive traces. Preparing this card is not permission to send an external message.

## Research and capability routing

For backward dependence tracing, missing behavior, differential reduction and the limitations of causal claims, read [research-basis.md](research-basis.md). Manual source inspection must be called manual inspection; do not claim a slicing engine, model checker, debugger or delta-debugging algorithm ran without its actual execution record.

Reuse the current environment's file/search/CLI tools first. Use primary-source research for external/version-specific claims; a scholarly connector can find papers but its search abstract is not verified full text. Use computation for an explicit quantitative model and data analytics for actual trace data with a valid denominator. Use a browser or computer tool when UI evidence is necessary, and Sites only for a requested site deliverable. Check callable capabilities at runtime. A named but unavailable worker or plugin remains unavailable; do not invent its result or make optional integrations prerequisites for the skill.

## External terminology provenance

Consulted 2026-09-10: [Netty project](https://netty.io/), [Apache MINA project](https://mina.apache.org/mina-project/index.html), [Lua reference manuals](https://www.lua.org/manual/). These support network-framework and embedded-language terminology only. They do not identify the user's ambiguous historical names or establish a past bug, unreleased feature or developer's intent. Recheck version-specific behavior when investigating an actual product.
