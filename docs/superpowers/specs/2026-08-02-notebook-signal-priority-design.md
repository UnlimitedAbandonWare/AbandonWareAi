# Notebook Signal Priority Prompt and Tooling Design

## 1. Status and approved scope

- Design status: approved for repository-draft design.
- Workspace: canonical `Y:\` in read/tooling mode.
- Allowed implementation surface after written-spec review: `agent-prompts/**`, one repository-local skill under `.agents/skills/**`, and bounded Python tests/tools under `scripts/**`.
- Excluded surface: `main/java`, `main/resources`, `app/src/**`, Gradle dependencies, DB/DDL, credentials, deployment, personal Codex configuration, commit, push, and Desktop installation.
- Desktop runtime and Python availability remain `evidence_needed`; Notebook evidence is supporting evidence only.

The design treats the user's explicit request as important regardless of whether it appears at the beginning or end of a message. Importance controls goal visibility and probe priority. It never grants mutation authority or bypasses safety, ownership, source-boundary, or verification gates.

## 2. Problem statement

Important deliverables can lose influence when they appear late in a long request or compete with numerous lower-value signals. A monolithic global prompt would address placement only superficially and would add permanent context cost. The repository also already contains prompt assembly, goal scoring, source-health scoring, and MCP-style source tools, so a second broad orchestration framework would duplicate owned assets.

The missing capability is a small, deterministic boundary that:

1. extracts explicit deliverables without positional weighting;
2. separates goal importance from permission to act;
3. amplifies independent, falsifiable, decision-changing signals;
4. deduplicates or attenuates weak correlated signals;
5. produces a bounded, redacted action ledger usable by Notebook and Desktop;
6. routes application-source work to Desktop proof while allowing prompt/tooling drafts on Notebook.

## 3. Chosen architecture

Use three layers with narrow ownership.

### 3.1 Thin global prompt draft

Extend `agent-prompts/codex_global_windows_personal_instructions.md`. Keep it concise and environment-agnostic enough for personal-instruction use, but do not install it into personal Codex configuration in this task.

It will define only durable rules:

- scan the complete current request for explicit deliverables before planning;
- do not reduce importance because a deliverable appears late;
- represent high-importance intent separately from action authorization;
- apply safety, authority, ownership, and current-evidence gates before mutation;
- attenuate low-confidence, duplicate, stale, or high-cost signals;
- promote an uncertain signal only when it is independent, falsifiable, and decision-changing;
- record hashes, counts, bounded scores, and reason codes instead of raw prompts or secrets;
- prefer `reuse -> extend -> create` for skills and tools.

Project sourceSets, SMB modes, fixed commands, and demo-1 file paths remain outside this global draft.

### 3.2 Code-global prompt pack

Create the manifest-owned agent `demo1_notebook_signal_priority_source_directive`:

- `agent-prompts/agents/demo1_notebook_signal_priority_source_directive/system_ko.md`
- `agent-prompts/agents/demo1_notebook_signal_priority_source_directive/meta.yaml`
- manifest entry in `agent-prompts/prompts.manifest.yaml`
- generated output `agent-prompts/out/demo1_notebook_signal_priority_source_directive.prompt`

This prompt owns project-specific code behavior:

1. freeze one redacted EvidenceSnapshot;
2. confirm active Gradle sourceSets and the current call boundary;
3. build a position-neutral CriticalIntent list;
4. score candidate signals without using score as authority;
5. select `LOG | PROBE | DIRECTIVE | HOLD | REJECT`;
6. emit a GoalContract and SourceDirective;
7. require RED/GREEN commands and rollback for implementation directives;
8. preserve `desktopFinalProof=evidence_needed` until Desktop output exists.

It must not assemble runtime RAG prompts, mutate application source, change public APIs, or claim provider/runtime lineage from prompt output.

### 3.3 Repository-local skill and deterministic CLI

Create `.agents/skills/demo1-notebook-signal-priority/` using the repository skill scaffolding and validation contract. The skill triggers when a Notebook/Desktop coding request contains many competing signals, a late explicit deliverable, or a request to amplify minority evidence. It does not trigger for a single direct fact request or when an existing subsystem-specific skill already owns the decision.

Create `scripts/notebook_signal_priority.py` as a standard-library-only CLI. Existing `source_health_scorecard.py` continues to own source-health and failure-pattern evidence; `awx_mcp_toolbox.py` continues to own source scanning and patch/verification routing. The new CLI owns only request-signal normalization. Initial implementation will not modify either existing tool.

This is a justified new asset because neither existing tool accepts a bounded structured list of request signals and proves position neutrality, correlation deduplication, and goal-priority/action-permission separation. MCP exposure is deferred unless a later failing integration test demonstrates recurring demand.

Capability contract:

| Field | Contract |
| --- | --- |
| Trigger | Competing request signals, a late explicit deliverable, or decision-changing minority evidence in a coding task |
| Non-trigger | Single factual questions, already-owned subsystem decisions, runtime source mutation, or unstructured autonomous execution |
| Owner | Repository prompt/tooling lane; Desktop remains final verifier for application-source work |
| Mutation surface | Explicit output file only; no application-source, environment, Git trust, DB, credential, or network mutation |
| Input/output | `signal-priority.input.v1` JSON to `signal-priority.ledger.v1` JSON |
| Budget | 262,144 input bytes, 100 signals, 5-second wall-clock target, 1 MiB maximum output |
| Redaction | Raw request storage forbidden; hashes, counts, allowlisted IDs, scores, and reason codes only |
| Failure policy | Schema, secret, authority, and output-bound failures are fail-closed; missing optional evidence is fail-soft to one `PROBE` or `HOLD` |
| Rollback | Remove the CLI, focused tests, skill directory, manifest entry, and generated prompt; restore the global-draft preimage |
| Non-duplication | Existing source-health and MCP tools do not own position-neutral request-signal normalization |
| Falsifying test | Moving an otherwise identical explicit deliverable changes its score or action, or a denied mutation becomes authorized because of score |

## 4. Signal contract

### 4.1 Input

The CLI accepts UTF-8 JSON from an explicit file or stdin. Maximum input is 262,144 bytes and at most 100 signals.

Exact interface:

```powershell
python -X utf8 scripts\notebook_signal_priority.py validate --input <json-path-or-->
python -X utf8 scripts\notebook_signal_priority.py evaluate --input <json-path-or-> --output <json-path-or->
```

`--input -` reads stdin and `--output -` writes stdout without any artifact. Exit code `0` means valid/success, `2` means input or policy rejection, and `3` means bounded internal failure. File output, when explicitly requested, is written beside the destination and atomically renamed only after UTF-8 encoding, output-bound, and secret checks pass.

```json
{
  "schemaVersion": "1.0",
  "requestId": "caller-supplied-nonsecret-id",
  "signals": [
    {
      "signalId": "S1",
      "sourceClass": "explicit_user",
      "kind": "deliverable",
      "position": 0.95,
      "explicitIntent": 1.0,
      "userBenefit": 0.9,
      "evidenceStrength": 0.7,
      "timeCriticality": 0.5,
      "verificationFeasibility": 0.9,
      "reversibility": 0.9,
      "blastRadius": 0.2,
      "ambiguity": 0.1,
      "authorityOrSafetyExpansion": 0.0,
      "independent": true,
      "falsifiable": true,
      "decisionChanging": true,
      "correlationGroup": "prompt-generation",
      "evidenceIds": ["E1"]
    }
  ]
}
```

`position` is retained only for the position-neutrality test and must have zero score weight.

### 4.2 Scoring and gates

For each signal:

```text
importanceScore = clamp(0, 100, 100 * (
  0.30 * explicitIntent
  0.20 * userBenefit
  0.15 * evidenceStrength
  0.10 * timeCriticality
  0.10 * verificationFeasibility
  0.10 * reversibility
  0.05 * independentDecisionValue
  - 0.20 * blastRadius
  - 0.20 * ambiguity
  - 0.30 * authorityOrSafetyExpansion
))
```

`independentDecisionValue` is `1.0` only when `independent`, `falsifiable`, and `decisionChanging` are all true; otherwise it is `0.0`.

The score controls attention, not permission:

- hard safety/authority/source-boundary failure: `HOLD` or `REJECT` regardless of score;
- score 70–100 with gates satisfied: `DIRECTIVE`;
- score 50–69: one smallest `PROBE`;
- score below 50: `LOG` with decay;
- duplicate `correlationGroup` signals cannot additively inflate the group; only the strongest independent member controls group priority;
- explicit user deliverables remain visible in CriticalIntent even when their action is held.

### 4.3 Output

Output is deterministic JSON with sorted signals and bounded narratives:

```text
schemaVersion
requestHash
criticalIntentIds
signalLedger[]: signalId, importanceScore, goalPriority, action,
                gateStatus, reasonCodes, evidenceIds, correlationGroup
groupLedger[]: correlationGroup, controllingSignalId, duplicateCount
nextSingleProof
rawInputStored=false
rawSecretPatternHits
```

The tool must not echo raw request text, credentials, paths resolved to UNC, environment dumps, or unbounded evidence snippets.

## 5. Data flow

```text
Current user request
  -> position-neutral explicit-deliverable extraction in the prompt
  -> bounded structured signal JSON
  -> notebook_signal_priority.py
  -> redacted Signal Ledger
  -> project safety/authority/sourceSet gates
  -> LOG | PROBE | DIRECTIVE | HOLD | REJECT
  -> GoalContract + SourceDirective
  -> Desktop execution and final proof when application source is involved
```

The Python tool does not read or mutate application source. Source analysis remains with the existing source-health and MCP tools.

## 6. Error handling and safety

- Invalid schema, non-finite number, duplicate `signalId`, excessive size, excessive signal count, or unknown `sourceClass`/`kind`: fail closed with a stable reason code.
- Missing optional evidence: retain the goal, lower evidence strength, and emit one `nextSingleProof`.
- Missing owner, source boundary, mutation authority, or verification command: `HOLD` even when importance is high.
- Secret-like input: count matches, omit raw content, and fail with `secret-leak-risk` when safe redaction cannot be proven.
- Desktop Python missing: `desktop-python-evidence-needed`; do not install remotely or through the SMB source workspace.
- Git dubious ownership: do not change global trust; use filesystem evidence for prompt/tooling and repository guards for any later source mutation.

## 7. Cross-host Python contract

The CLI uses only the Python standard library and repo-relative paths. Notebook proof is:

```powershell
$env:PYTHONDONTWRITEBYTECODE = "1"
python -X utf8 scripts\notebook_signal_priority.py --help
python -X utf8 scripts\test_notebook_signal_priority.py
```

Desktop must independently run the same commands. If `python` is unavailable, the Desktop operator first uses the organization-approved Python installation method, then reruns the smoke. This design does not create an automatic installer and does not infer Desktop readiness from Notebook output.

## 8. Test design

### 8.1 RED tests before implementation

1. A deliverable placed at position `0.95` and the same deliverable at `0.05` currently have no deterministic equality contract.
2. A high-benefit source edit currently lacks a machine-checkable distinction between high goal priority and denied mutation permission.
3. Correlated weak signals currently lack a bounded group deduplication contract.
4. Existing tools do not emit the defined request-level Signal Ledger schema.
5. The proposed repository-local skill does not yet exist or validate.

### 8.2 GREEN assertions

- Position changes alone do not change `importanceScore`, `goalPriority`, or `action`.
- An explicit high-value source edit remains CriticalIntent but returns `HOLD` when authority is absent.
- A hard safety blocker overrides maximum user-benefit input.
- Weak correlated signals do not inflate their group above its controlling member.
- Independent, falsifiable, decision-changing minority evidence can move `LOG` to `PROBE`, but not bypass gates.
- Output is stable across repeated runs and contains no raw input.
- Invalid, oversized, duplicate-ID, secret-bearing, and non-finite inputs fail with documented reason codes.
- Notebook Python smoke passes; Desktop remains `evidence_needed` until independently run.

### 8.3 Prompt and skill verification

```powershell
$env:PYTHONUTF8 = "1"
$env:PYTHONDONTWRITEBYTECODE = "1"
python -X utf8 agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_notebook_signal_priority_source_directive
python -X utf8 scripts\test_notebook_signal_prompt.py
python -X utf8 scripts\test_agent_prompt_secret_patterns.py
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py .\.agents\skills\demo1-notebook-signal-priority
python -X utf8 scripts\test_notebook_signal_priority.py
```

The prompt test must verify manifest ID uniqueness, manifest-merge equality, required reason codes, position-neutral wording, safety-gate precedence, and count-only secret results.

## 9. Rollback

- Restore the preimage of `agent-prompts/codex_global_windows_personal_instructions.md`.
- Remove the single new manifest entry and generated prompt output.
- Remove the new agent directory, repository-local skill directory, CLI, and their focused tests.
- Do not touch application source, Gradle dependencies, personal instructions, or external installations during rollback.

## 10. Completion boundary

Repository implementation is complete only when the focused RED/GREEN suite, prompt build/merge comparison, skill validation, and count-only secret scan pass with current output. This proves the repository prompt/tooling lane only.

Application-source mutation, persistent personal-instruction installation, Desktop Python installation, Desktop build/runtime verification, provider lineage, deployment, commit, and push remain separate explicitly authorized actions. `desktopFinalProof=evidence_needed` remains unchanged.
