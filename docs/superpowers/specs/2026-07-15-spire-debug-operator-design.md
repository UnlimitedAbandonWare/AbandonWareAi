# Spire Debug Operator Design

## Status

- Date: 2026-07-15
- Node: Notebook secondary investigator / PatchDrop producer
- Approved direction: repo-local prompt plus one sharp repo-local skill
- Target branch: `agent/notebook/spire-debug-operator`
- Desktop final owner: `C:\AbandonWare\demo-1\demo-1\src`

## Problem

The intended operator is strong at debugging unfamiliar systems but loses
reliability when retrieval, skills, tools, and agent loops expand without a
clear decision boundary. Existing demo-1 assets cover autonomous Safe Patch,
debug scanning, metrics, and tool usage separately. They do not provide one
compact operator contract that enforces a narrow failure target, a two-tool
evidence budget, and success-only pattern memory across a nine-hour source
patch session.

## Goals

1. Add a reusable skill that turns a broad failure into one falsifiable
   hypothesis and one patch intent at a time.
2. Add a nine-hour prompt pack that applies the skill to demo-1 Safe Patch work
   in priority order: P0, then P1, then gated P2.
3. Limit each patch cycle to one observation tool family and one verification
   tool family.
4. Preserve current sourceSet, PromptBuilder, LangChain4j, secret, PatchDrop,
   and Desktop ownership rules.
5. Compress only verified successful patterns into a bounded session ledger.

## Non-goals

- Do not add a runtime agent framework, broker, daemon, scheduler, memory
  service, database schema, UI, or provider integration.
- Do not modify Java, resources, Gradle, secrets, `.env*`, or secret-tool
  configuration names in this prompt-and-skill patch.
- Do not treat nine hours as required runtime. It is the maximum agent effort
  budget and may end early.
- Do not make Browser, Computer, Supabase, Mac mini, or Notebook evidence a
  default source-edit blocker.
- Do not duplicate the full contents of existing debugging or autonomous patch
  skills.

## Evidence Baseline

- Local Git root: `C:\AbandonWare\worktrees\awx-notebook`
- Local branch: `agent/notebook/spire-debug-operator`
- Local HEAD: `bb7710e`
- Local worktree at intake: clean, no `.git\index.lock`
- Active root sourceSet: `main/java`, `main/resources`
- Active `:app` sourceSet: `app/src/main/java_clean`,
  `app/src/main/resources`
- LangChain4j evidence: `1.0.1` in the local Gradle build
- Shared evidence path: `\\desktop-m5nov6k\MacSrc`
- Shared branch: `main`
- Shared worktree: dirty with existing user changes, including
  `agent-prompts/prompts.manifest.yaml` and `AGENTS.md`
- Shared path policy: read-only evidence; no direct edits

## Selected Architecture

Use a thin prompt plus one sharp skill.

The prompt owns session scope, time budget, priority transitions, repository
invariants, and final reporting. The skill owns the decision loop: select the
first failing layer, state one hypothesis, spend at most two evidence-tool
slots, apply one minimal patch, verify, and record the result. This split keeps
the skill reusable while preventing the prompt from becoming another broad
orchestration framework.

## Components

### 1. `demo1-debugging-with-two-tools` skill

The skill is a discipline and technique guide for cases where debugging starts
to sprawl across logs, RAG, providers, source mirrors, and broad test suites.
It must trigger on symptoms such as tool proliferation, repeated scans,
multiple simultaneous hypotheses, downstream symptom patching, and nine-hour
Safe Patch loops with unclear stop conditions.

The skill contains:

- the first-failure-layer rule;
- the one-hypothesis and one-patch-intent contract;
- the two evidence-tool-slot policy;
- the P0/P1/P2 advancement gate;
- the verifier and rollback contract;
- the bounded success-pattern format;
- red flags and common mistakes;
- one complete demo-1 example.

The skill stays compact. Heavy demo-1 command inventories remain in existing
skills and repository scripts instead of being copied into it.

### 2. `demo1_spire_debug_operator_9h` prompt

The prompt gives Codex, Claude, or Antigravity a bounded source-modification
directive. It explicitly requires the new skill and keeps repository evidence
above operator personality.

The prompt contains:

- role and authority order;
- initial Git, sourceSet, PatchDrop, cache, and version gates;
- the nine-hour maximum effort policy;
- a P0/P1/P2 backlog contract;
- the two-tool cycle schema;
- stop, rollback, and external-evidence rules;
- the success-pattern ledger;
- the Korean Notebook/Desktop-safe report contract.

### 3. Prompt manifest and generated output

Register the prompt in `agent-prompts/prompts.manifest.yaml` and generate
`agent-prompts/out/demo1_spire_debug_operator_9h.prompt` for local comparison.
The repository ignores `**/out/`, so the generated output is verification
evidence and is not included in the source patch. Desktop regenerates it after
applying the bundle. The manifest edit is inserted after a stable existing
agent block so the patch remains narrow when Desktop has unrelated manifest
changes.

### 4. Skill metadata

Generate `.agents/skills/demo1-debugging-with-two-tools/agents/openai.yaml`
from the completed skill. Metadata contains only display name, short
description, and default prompt derived from `SKILL.md`.

### 5. Root instructions

Do not modify `AGENTS.md` in this patch. The shared file is already dirty and
the prompt manifest plus skill metadata provide sufficient discovery.

## Operator Contract

The operator is a spire: narrow base of evidence, one elevated target, and no
horizontal expansion unless the current target is falsified.

For every cycle it must report:

```text
failure_layer:
fact:
inference:
suspicion:
hypothesis:
observation_tool:
patch_intent:
verification_tool:
observed_result:
rollback:
next_priority:
```

Facts come from current files or command output. Inferences connect facts.
Suspicions are unproven and cannot authorize a source edit.

## Two-Tool Policy

Each cycle has two evidence-tool slots:

1. Observation slot: normally `rg` plus targeted file reads, or the smallest
   existing diagnostic command that exposes the first failure layer.
2. Verification slot: the narrowest command that can falsify the patch claim,
   normally a focused test or contract script.

The source edit itself and the cycle ledger update are mandatory workflow
steps, not additional investigation slots. A third evidence tool is allowed
only after the current verification changes the failure classification. That
event closes the current cycle; the next tool belongs to a new cycle and must
be justified in the ledger.

Broad recursive scans, full log dumps, full test suites, boot smoke, Browser,
Computer, or external providers are not default tools. They require evidence
that the narrow verifier cannot decide the next action.

## Patch Cycle

1. Prove root, branch, index-lock state, dirty paths, PatchDrop state, active
   sourceSets, cache isolation, and LangChain4j purity.
2. Select the earliest failing layer supported by current evidence.
3. Write one falsifiable hypothesis and its disproof condition.
4. Spend the observation slot only on evidence that changes that hypothesis.
5. Add a characterization or RED test when behavior changes and a focused test
   surface exists.
6. Patch one intent, normally no more than three files.
7. Spend the verification slot on the narrowest disproof command.
8. If verification succeeds, update the priority ledger and optionally promote
   a compact success pattern.
9. If verification fails, roll back only the current cycle diff, record the
   exact failure class, and do not advance priority.
10. If the failure class changes, close the cycle and start a new intake from
    the changed layer.

## Priority Model

### P0

- sourceSet or FQCN ownership violations;
- build or boot blockers;
- secret or redaction failures;
- PromptBuilder boundary bypass;
- LangChain4j version impurity;
- data-loss, authorization, or unsafe outbound-call behavior.

### P1

- fail-soft correctness;
- cancellation and timeout handling;
- silent failure observability;
- bounded resource use;
- verification or rollback gaps that make P0 behavior unstable.

### P2

- narrow cleanup, deduplication, metrics, or documentation that measurably
  reduces future debugging cost.

P2 is allowed only when all known P0 and P1 items are verified or explicitly
classified `SKIP`/`evidence_needed`, the change stays within one owner and at
most three files, and a focused verifier plus rollback path exist. P2 never
authorizes speculative architecture work.

## Nine-Hour Budget

- Nine hours is a hard maximum, not a completion requirement.
- Reserve the final 45 minutes for cumulative verification, secret scan,
  ledger closure, and PatchDrop handoff.
- P0 may consume all available patch time.
- P1 begins only when the current P0 gate is green or explicitly blocked.
- P2 may continue while each candidate has source ownership, measurable value,
  a focused verifier, and a rollback path.
- Stop early when no candidate clears those gates, the same external-only
  blocker repeats, or the next change would add an orchestration framework.

## Success-Pattern Memory

Do not add runtime memory infrastructure. Keep a session-local ledger with at
most five entries:

```text
symptom -> proof -> minimal_patch -> verifier -> rollback
```

An entry is stored only after fresh verification succeeds. Reuse is preferred
after the same pattern succeeds twice. Evict the least reusable or oldest
entry when the ledger exceeds five. Store no raw prompts, queries, logs,
credentials, headers, cookies, or private environment values.

## Error Handling and Stop Rules

Stop before editing on:

- Git unavailable, wrong branch, index lock, or unproven sourceSet;
- ambiguous active PatchDrop bundles or overlapping target-file changes;
- mixed or non-`1.0.1` LangChain4j versions;
- credential-like content in the current patch;
- no focused verifier or no rollback path;
- a changed failure class after verification;
- Desktop-only authority required for final proof.

Missing Browser, Computer, Supabase, Mac mini, or Notebook evidence remains
supporting `evidence_needed` unless the changed surface explicitly requires it.

## File Plan

Create:

- `.agents/skills/demo1-debugging-with-two-tools/SKILL.md`
- `.agents/skills/demo1-debugging-with-two-tools/agents/openai.yaml`
- `agent-prompts/agents/demo1_spire_debug_operator_9h/system_ko.md`
- `agent-prompts/agents/demo1_spire_debug_operator_9h/meta.yaml`

Modify:

- `agent-prompts/prompts.manifest.yaml`

Include this design document in the final bundle so Desktop can review the
rationale with the implementation.

## Validation Design

### Skill validation

- Create a failing baseline pressure scenario before the skill exists.
- Validate name, frontmatter, description triggers, compactness, two-tool rule,
  one-hypothesis rule, priority gate, rollback, and success-memory bound.
- Run `quick_validate.py` on the new skill.
- Run the repo skill-family validator when available.
- Because current multi-agent policy does not authorize subagent delegation,
  record forward-agent pressure testing as `evidence_needed` unless the user
  explicitly authorizes it.

### Prompt validation

- Build only `demo1_spire_debug_operator_9h` through `agent-prompts/build.py`.
- Parse the manifest and reject duplicate IDs.
- Compare generated output with the manifest merge result.
- Confirm the prompt explicitly requires the new skill.
- Count secret-pattern hits without printing matched values.

### Patch validation

- Inspect `git diff --check` and the exact changed path list.
- Ensure the patch contains no unrelated shared dirty changes.
- Apply-check the cumulative patch against a temporary copy seeded from the
  current shared prompt/skill files, not against the shared path itself.
- Produce one Notebook PatchDrop v3 bundle with patch, report, verify log,
  manifest, and SHA256 sidecar.
- Mark Desktop final proof as `evidence_needed` until Desktop applies and
  verifies the bundle.

No Gradle build is required for prompt/skill-only changes unless the prompt
governance contract or build configuration is modified.

## Success Criteria

- Every documented patch cycle has one hypothesis and one patch intent.
- Every cycle uses no more than two evidence-tool slots, except a documented
  failure-class transition that starts a new cycle.
- P2 never starts before P0/P1 disposition is recorded.
- Every successful patch has a fresh verifier and rollback note.
- Success-pattern memory contains at most five redacted entries.
- Prompt build succeeds, manifest IDs are unique, and generated output equals
  the manifest merge.
- Skill validation succeeds and secret-pattern hit count is zero.
- The PatchDrop bundle contains only the approved prompt, skill, metadata,
  manifest hunk, and this design document. Generated output remains local
  verification evidence because `**/out/` is ignored.

## Risks

1. The Desktop working tree is heavily dirty; even a narrow manifest hunk may
   conflict. Mitigation: stable context plus temp-copy apply proof.
2. A two-tool policy can become performative if tool families are defined too
   broadly. Mitigation: require exact command and decision-changing result for
   each slot.
3. P2 can expand scope after P0/P1. Mitigation: same-owner, three-file,
   verifier, rollback, and measurable-value gates.
4. Success memory can preserve a locally successful but non-general pattern.
   Mitigation: prefer reuse only after two verified occurrences and keep five
   entries maximum.
5. Notebook verification cannot prove Desktop final behavior. Mitigation:
   classify final proof as `desktop-proof-missing` until Desktop verification.
