# Notebook–Desktop Goal Handoff Design

## Status and Decision

- Status: approved design A; implementation awaits review of this written specification.
- Request class: `prompt_skill_tooling_only`.
- Notebook owns read-only evidence collection, three-role goal evaluation, and source directives.
- Desktop owns canonical prompt/tooling mutation and final verification.
- Application source, database state, credentials, deployment, commit, and push are outside this design.

The approved approach adds one small repository handoff prompt and one compatible
Y-drive identity probe, aligns the existing three-node SMB prompt with current
policy, and reuses the existing goal-directive and triadic-review assets. It does
not add a second orchestration framework or copy the global goal validator into
the repository.

## Current Evidence

- `canonicalWorkspace=Y:\`.
- A compatibility-safe probe using `SHA256.Create().ComputeHash()` reports
  `backingShareIdentityVerified=true` and
  `backingShareIdentityReason=match` against the repository-owned baseline.
- The earlier mismatch was a false negative caused by unavailable
  `SHA256.HashData` combined with suppressed PowerShell errors. The current
  repository baseline is already correct, so changing its value is a no-op.
- Git metadata, branch, and dirty-status evidence remain unavailable from this
  Notebook session. The new design file has no pre-existing path overlap.
- The registered `demo1_three_node_smb_codex` prompt contains legacy direct-mode
  and raw UNC examples and does not emit the current Y-drive direct mode.
- The registered `demo1_three_perspective_chat_postprocess` prompt supplies
  reusable Positive/Negative/Neutral concepts but remains scoped to chat
  postprocessing; it is not broadened into the general handoff owner.
- `scripts/ydrive_smb_workspace_policy_autograder.py` already validates routing
  decisions but does not acquire or hash the live mapping.
- `agent-prompts/build.py` already owns manifest-based prompt generation.
- The validated triad used three actual agents, shared EvidenceSnapshot SHA-256
  `570945263512ccc0c4f882c01745b5cdd468b72735cba89236a2d5ecd668ae8d`,
  and produced artifact SHA-256
  `4a45108c6d5e717415b64985b6de4e24915f172913ed22154de3312e478fde97`.

## Goals

1. Make the recurring Notebook-to-Desktop workflow explicit and mechanically
   verifiable without granting Notebook canonical source ownership.
2. Prevent false `HOLD` decisions caused by PowerShell/.NET hash API differences.
3. Preserve exactly three decision roles when literal subagents are requested:
   `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`.
4. Make Negative counter-evidence and authority gates decisive; do not use
   majority voting.
5. Keep Supabase demand-driven, project-scoped, and read-only unless a later
   request separately authorizes mutation.
6. Keep long workflow instructions under `agent-prompts` and retain only a short
   repository pointer in `AGENTS.md`.

## Non-Goals

- Editing `main/java`, `main/resources`, `app/src/main/java_clean`, or any other
  application source/resource surface.
- Changing public APIs, Gradle dependencies, DB/DDL, Supabase schema/data, API
  keys, environment variable names, or secret flow.
- Treating Notebook, Browser, Computer, Supabase, prompt-build, or generated
  output evidence as Desktop runtime proof.
- Replacing `notebook-goal-directive-generator`,
  `notebook-smb-network-workspace`, `tri-query-cloud-router`, or the existing
  Y-drive decision autograder.
- Supporting a fourth judge, fallback vote, implicit majority, or provider rank
  as evidence authority.

## Architecture

### 1. Compatible live identity probe

Add `scripts/verify_ydrive_backing_identity.ps1` as a mechanical evidence
collector. It accepts the canonical workspace and the repository-owned expected
SHA-256, resolves the live mapping only into a temporary variable, normalizes it
with `Trim().TrimEnd('\').ToLowerInvariant()`, and hashes UTF-8 bytes with
`SHA256.Create().ComputeHash()`.

The public JSON output contains exactly:

```text
canonicalWorkspace
backingShareIdentityVerified
backingShareIdentityReason
```

It never emits or persists the raw mapping, normalized mapping, actual hash,
credentials, Git top-level path, or environment dump. Missing mapping or crypto
failure returns `evidence-needed`; a hash mismatch returns `mismatch`; both are
fail-closed for mutation. The probe does not itself authorize writes.

The existing `ydrive_smb_workspace_policy_autograder.py` remains the decision
grader. The probe supplies the `mappingIdentityMatched` fact; the autograder
continues to decide read access versus guarded direct execution.

### 2. Notebook–Desktop handoff prompt

Add the prompt package:

```text
agent-prompts/agents/demo1_notebook_desktop_goal_handoff/meta.yaml
agent-prompts/agents/demo1_notebook_desktop_goal_handoff/system_ko.md
```

Register `demo1_notebook_desktop_goal_handoff` in
`agent-prompts/prompts.manifest.yaml`, producing
`agent-prompts/out/demo1_notebook_desktop_goal_handoff.prompt` through the
existing builder. The generated output is never hand-edited.

The prompt is a controller and handoff contract, not another triad
implementation. It routes Notebook work through existing skills and requires a
validated artifact before producing `GoalContract` and `SourceDirective`.

### 3. Existing three-node policy alignment

Update only the policy-owned sections of
`agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md`:

- retain `Y:\` as the Notebook canonical workspace;
- emit `YDRIVE_SMB_GUARDED_DIRECT` only after every direct-write gate passes;
- accept the historical direct-mode identifier only as an internal input;
- remove user-facing raw UNC examples and UNC/local/OneDrive fallback advice;
- separate `SMB_ACCESS` from mutation authority;
- preserve Desktop final ownership and PatchDrop producer modes where explicitly
  selected.

The specialized three-perspective chat-postprocess prompt remains unchanged and
is referenced only as a reusable role-pattern asset.

### 4. Short AGENTS pointer

Add one concise pointer under reusable prompt packs in `AGENTS.md`. It names the
new prompt for recurring Notebook goal/directive handoffs and states that the
compatible probe must precede any Y-drive authority decision. It does not copy
the long workflow or alter the existing backing-identity baseline.

## Data Flow

1. The Notebook controller classifies the request before any mutation.
2. The compatible probe returns only the canonical workspace, verification
   boolean, and reason.
3. For read/audit work, `sourceWriteRoot=null` and
   `authorizedMutation=false` remain mandatory even when identity matches.
4. The Notebook freezes one redacted EvidenceSnapshot and seals its canonical
   UTF-8 SHA-256.
5. If literal subagents are explicitly requested, three actual roles run
   sequentially: Positive creates falsifiable scenario IDs, Negative attacks
   each sealed ID exactly once, and Neutral compares A–B and B–A without new
   evidence.
6. The existing goal-directive validator checks schema, packet bounds, evidence
   closure, scenario coverage, score calculation, and order stability.
7. Notebook emits a GoalContract and a Desktop-owned SourceDirective. Unknown
   Desktop root, branch, status, targets, or verification remains
   `evidence_needed` rather than being inferred.
8. Desktop re-runs identity, Git/worktree/index-lock, target-boundary, and
   prompt-build gates before editing.
9. Desktop performs the minimal prompt/tooling patch, rebuilds the manifest
   output, verifies merge equality and secret counts, and supplies final proof.

## Role and Decision Contract

- Positive identifies reusable assets, user value, minimal verification, and
  two to four falsifiable worlds.
- Negative attacks every Positive scenario ID exactly once with counterexample,
  alternative cause, authority risk, blast radius, and the smallest
  disconfirming probe.
- Neutral evaluates both packet orders independently. Any verdict or decisive
  evidence-set disagreement forces `orderStable=false` and `HOLD`.
- A score below 50 cannot `APPLY`; ownership, safety, and verification gates
  override any score.
- Incomplete literal agents produce only `TriadExecutionStatus` and do not
  synthesize packets or contracts.
- Negative evidence is decisive when it demonstrates authority expansion,
  unproven root/branch/target, secret risk, or unverifiable execution.

## Supabase Boundary

Supabase tools are an optional evidence lane. Before any project call, the
controller requires a selected project scope and proven read-only authority.
Without both, it records `supabase-project-scope-unproven`, performs no call,
and continues when the decision does not depend on Supabase. Database, Auth,
Storage, Edge Function, migration, and credential mutation remain forbidden.

Current Supabase documentation must be refreshed before any later feature or
schema implementation. Tool availability alone never proves project scope.

## Failure Contract

| Condition | Classification | Result |
| --- | --- | --- |
| Mapping unavailable | `smb-root-unproven` | `HOLD`; no write root or fallback |
| Identity mismatch | `smb-root-identity-changed` | `HOLD`; reads may continue if safe |
| Hash API/crypto failure | `identity-probe-unavailable` | `HOLD`; reason `evidence-needed` |
| Raw mapping appears in output | `redaction-failed` | Reject artifact |
| Literal agent missing/invalid | `literal-subagents-unavailable` or `triad-role-failed` | Terminal triad `HOLD` |
| Scenario or evidence closure fails | validator failure class | Reject artifact |
| A–B/B–A differs | `order-unstable` | `HOLD` |
| Git root/branch/status unavailable | `desktop-proof-missing` | Design allowed; execution `HOLD` |
| Legacy mode emitted | `legacy-emitted-mode` | Fail prompt policy test |
| Supabase scope missing | `supabase-project-scope-unproven` | Skip calls; no mutation |
| Prompt output differs from manifest merge | `prompt-output-stale` | Fail verification |

## Capability Contract

- Trigger: recurring Notebook SMB read/audit followed by a Desktop source or
  prompt/tooling directive; explicit three-subagent goal evaluation; Y-drive
  identity uncertainty.
- Non-trigger: ordinary single-host questions, application runtime debugging,
  direct DB work, or a request that does not need a Desktop handoff.
- Owner: repository prompt/tooling; Desktop is final mutation and verification
  owner.
- Mutation surface: the declared prompt package, prompt manifest, compatible
  probe/tests, existing three-node prompt policy section, and short AGENTS
  pointer only.
- Input schema: UserRequest, redacted EvidenceSnapshot, snapshot hash, request
  classification, and observed role provenance.
- Output schema: probe decision; three canonical packets or terminal triad
  status; GoalContract; SourceDirective.
- Timeout and budget: probe 10 seconds; triad envelope 120 seconds; design and
  implementation hard cap 120 minutes with early exit on proof.
- Bounded output: Positive 2400, Negative 2400, Neutral 1800 canonical JSON
  characters; probe output contains exactly three public fields.
- Redaction: no raw mapping, prompt/response body, credentials, auth headers,
  cookies, DB URL, environment dump, or UNC Git root.
- Fail mode: fail-closed for identity, ownership, mutation, secrets, and triad
  validity; fail-soft only for optional unscoped Supabase evidence.
- Rollback: remove the new prompt/probe/tests and manifest/pointer entries;
  restore the three-node prompt from its verified preimage; generated output is
  rebuilt or removed, never manually reversed.
- Non-duplication rationale: the new prompt composes existing skills and the new
  probe fills only live identity acquisition, which the current decision
  autograder does not perform.
- Falsifying test: if the existing prompt plus current tools can produce the
  exact bounded probe output and validated handoff without any new source, the
  new package/probe must be dropped as unnecessary.

## Planned File Surface

Add:

```text
agent-prompts/agents/demo1_notebook_desktop_goal_handoff/meta.yaml
agent-prompts/agents/demo1_notebook_desktop_goal_handoff/system_ko.md
scripts/verify_ydrive_backing_identity.ps1
scripts/verify_ydrive_backing_identity_contract_tests.ps1
scripts/test_notebook_desktop_goal_handoff_prompt.py
```

Modify:

```text
AGENTS.md
agent-prompts/prompts.manifest.yaml
agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md
```

Generated, not hand-edited:

```text
agent-prompts/out/demo1_notebook_desktop_goal_handoff.prompt
agent-prompts/out/demo1_three_node_smb_codex.prompt
```

Excluded:

```text
main/java/**
main/resources/**
app/src/main/java_clean/**
app/src/main/resources/**
project/**
demo-1/**
lms-core/**
archives, backups, generated build outputs, DB/DDL, secret/config files
```

## Verification Contract

Run narrow prompt/tooling checks first:

```powershell
$Expected = '30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify_ydrive_backing_identity.ps1 -CanonicalWorkspace 'Y:\' -ExpectedSha256 $Expected
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify_ydrive_backing_identity_contract_tests.ps1
python -X utf8 scripts\ydrive_smb_workspace_policy_autograder.py --self-test
python -X utf8 scripts\test_notebook_desktop_goal_handoff_prompt.py
python -X utf8 agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_notebook_desktop_goal_handoff
python -X utf8 agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_three_node_smb_codex
python -X utf8 scripts\test_agent_prompt_secret_patterns.py
```

Then verify manifest uniqueness and exact merge equality for both generated
prompts. Expected results are zero duplicate IDs and
`manifest_merge_eq_out=True` for each agent.

Because implementation changes repository governance, Desktop final proof also
runs with isolated caches:

```powershell
$env:AWX_AGENT_HOST = 'desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop'
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$ProjectCache | Out-Null
.\gradlew.bat sourceScoreReport checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache
```

Notebook prompt/probe results remain supporting evidence;
`desktopFinalProof=evidence_needed` until these Desktop commands run.

## Implementation Sequence

1. Add failing contract tests for compatible hashing, bounded output, mismatch,
   missing mapping, crypto failure, and raw-path non-disclosure.
2. Implement the smallest compatible probe and make its tests green.
3. Add failing static prompt tests for handoff schema, role order, failure-only
   terminal triad, Supabase scope gate, and Desktop ownership.
4. Add the handoff prompt package and manifest entry; build and compare output.
5. Add failing policy checks for legacy emitted mode, raw UNC examples, unsafe
   fallback, and missing Y-drive mode in the three-node prompt.
6. Apply the narrow three-node prompt alignment and short AGENTS pointer.
7. Run prompt build, manifest uniqueness, merge equality, secret scan, policy
   autograder, and Desktop governance gates.
8. Record pre/post hashes, zero secret counts, rollback paths, and Desktop final
   evidence without committing, pushing, or deploying unless separately asked.

## Rollback

- Delete only the new prompt package and probe/test files.
- Remove their manifest and AGENTS pointer entries.
- Restore the three-node prompt from its captured preimage hash.
- Rebuild generated prompt outputs from the restored manifest.
- Re-run identity, prompt, manifest, secret, and policy checks.
- Do not change the repository backing-identity baseline during rollback because
  this design did not change it.

