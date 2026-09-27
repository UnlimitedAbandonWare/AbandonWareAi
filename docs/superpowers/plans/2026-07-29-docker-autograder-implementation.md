# Docker Auto-Grader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fail-closed Docker RED/GREEN grader to MacSrc, integrate it between Finalize and PromoteIntent, and apply a bounded verified-test delta in terminal postprocessing.

**Architecture:** A new repo-local `demo1-docker-autograder` skill owns immutable job/result contracts and a Python Docker controller. The existing defect-intake tool adds `GradeSandbox`, while the terminal postprocessor consumes optional GREEN evidence and computes `base + [-10,+10]` without changing tri-query packet visibility or order.

**Tech Stack:** Python 3 standard library, Windows PowerShell 5.1, Docker CLI contract, JUnit XML, existing ready-last SHA-256 artifacts.

## Global Constraints

- Write only repo-local tooling, skills, tests, plans, and handoff artifacts under `\\desktop-m5nov6k\MacSrc`.
- Do not modify Java/Spring application source, Gradle dependencies, DB, Supabase, credentials, OneDrive, Docker daemon configuration, or global Git configuration.
- Never mount the SMB/UNC root into Docker; copy explicit bounded inputs to a host-local temporary directory first.
- Test containers use digest-pinned preloaded images, `--pull never`, `--network none`, read-only input/root filesystems, no added privileges, and bounded CPU/memory/PID/time.
- Raw stdout/stderr remains temporary; persistent output contains hashes, byte counts, booleans, test counts, timing, and reason codes only.
- Docker infrastructure failure yields HOLD and no dynamic score.
- Preserve Positive/Negative isolation and Neutral A-B/B-A order. Compute the Docker delta only after the deterministic base score.
- No commit, push, deployment, Docker installation, or image pull is authorized in this session.

---

### Task 1: Docker Controller and Observable Sandbox Contract

**Files:**
- Create: `.agents/skills/demo1-docker-autograder/scripts/docker_autograder.py`
- Create: `scripts/test_demo1_docker_autograder.py`

**Interfaces:**
- Consumes: `run(root: Path, job_path: Path, output_path: Path, docker_bin: str) -> dict`
- Produces: ready-last `awx.docker-autograder.result.v1` and a process exit code of zero for a published COMPLETE/HOLD record; invalid caller input exits nonzero without publishing PASS.

- [ ] **Step 1: Write the failing controller tests**

Create a `unittest` fixture with a real fake-Docker subprocess. The fake writes
its argv to a file and emits literal JUnit XML to the `/results` bind source.
Assert these independently derived outcomes:

```python
self.assertEqual(result["tests"], {
    "total": 4, "passed": 2, "failed": 1, "errored": 0, "skipped": 1,
    "passRatio": 0.5,
})
self.assertEqual(result["executionStatus"], "COMPLETE")
self.assertNotIn("\\\\desktop-m5nov6k\\MacSrc", " ".join(captured_argv))
for flag in ("--network", "none", "--read-only", "--cap-drop", "ALL",
             "--pids-limit", "--cpus", "--memory", "--pull", "never"):
    self.assertIn(flag, captured_argv)
```

Add separate cases for path traversal, reparse input, mutable image tag,
selector beginning with `-`, oversized input, timeout/kill, missing Docker, DTD
XML, zero tests, and stdout/stderr hash-only persistence.

- [ ] **Step 2: Run RED**

Run:

```powershell
python scripts\test_demo1_docker_autograder.py
```

Expected: failure because `docker_autograder.py` does not exist.

- [ ] **Step 3: Implement input validation and local staging**

Implement strict job fields and bounds:

```python
ALLOWED_PROFILES = {"pytest", "gradle-junit"}
PROHIBITED_PARTS = {".git", ".gradle", "build", "node_modules"}

def resolve_member(root: Path, relative: str) -> Path:
    if not relative or Path(relative).is_absolute() or ":" in relative:
        raise GraderError("path-outside-root")
    candidate = (root / relative).resolve(strict=True)
    if root != candidate and root not in candidate.parents:
        raise GraderError("path-outside-root")
    reject_reparse_chain(root, candidate)
    return candidate
```

Copy explicit files/directories with file-count and total-byte limits. Hash
source before and after each copy and publish a sorted input manifest hash.
Reject a staging root beginning with `\\`.

- [ ] **Step 4: Implement fixed Docker argv and timeout**

Construct argv as a list, never a caller-provided shell command:

```python
argv = [docker_bin, "run", "--rm", "--name", container_name,
        "--pull", "never", "--network", "none", "--read-only",
        "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--user", "65532:65532", "--cpus", str(job.cpus),
        "--memory", f"{job.memory_mb}m", "--memory-swap", f"{job.memory_mb}m",
        "--pids-limit", str(job.pids_limit), "--stop-timeout", "1"]
```

Add local read-only `/input`, writable `/results`, and tmpfs `/workspace` and
`/tmp`. Capture streams to temporary files. On timeout call `docker kill` for
the generated container name, publish HOLD, and delete staging.

- [ ] **Step 5: Implement JUnit parsing and ready-last result**

Reject XML over the bound, `DOCTYPE`/`ENTITY`, inconsistent counts, and zero
tests. Aggregate top-level suites and calculate:

```python
passed = total - failed - errored - skipped
pass_ratio = round(passed / total, 10)
```

Publish JSON, `.sha256`, then `.ready` last using same-directory temporary
files and atomic replacement. Do not persist raw logs.

- [ ] **Step 6: Run GREEN**

Run:

```powershell
python scripts\test_demo1_docker_autograder.py
```

Expected: all controller tests pass with no real Docker dependency.

---

### Task 2: PowerShell Boundary and Repo-Local Skill

**Files:**
- Create: `.agents/skills/demo1-docker-autograder/scripts/invoke_docker_autograder.ps1`
- Create: `.agents/skills/demo1-docker-autograder/SKILL.md`
- Create: `.agents/skills/demo1-docker-autograder/references/docker-autograder-contract.md`
- Create: `.agents/skills/demo1-docker-autograder/agents/openai.yaml`
- Create: `scripts/demo1_docker_autograder_contract_tests.ps1`

**Interfaces:**
- Consumes: canonical root, relative ready-last job path, relative result path, optional fake Docker binary for contract testing.
- Produces: the Python controller's machine JSON while prohibiting output outside `data/agent-handoff/docker-autograder/<runId>`.

- [ ] **Step 1: Write the failing PowerShell contract**

Create a temporary fixture, ready-last job, included code/test files, and fake
Docker executable. Assert root normalization, job checksum verification,
handoff-only output, traversal/reparse rejection, bounded JSON output, and
result ready marker. Assert `mutationAllowed=false`.

- [ ] **Step 2: Run RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_docker_autograder_contract_tests.ps1
```

Expected: failure because the PowerShell wrapper and skill are missing.

- [ ] **Step 3: Implement the wrapper**

Normalize mapped drives through `PSDrive.DisplayRoot`, require exact MacSrc for
production use while allowing explicit temporary fixtures in `-ContractTest`
mode, validate JSON/sidecar/ready from one byte snapshot, resolve output under
the Docker handoff root, then invoke Python with an argument array.

- [ ] **Step 4: Write the minimal skill**

Document trigger/non-trigger, owner, mutation surface, job/result schemas,
timeouts and bounds, redaction, failure classes, fail-closed behavior,
rollback/removal, non-duplication, and the falsifying tests. Keep detailed
schema examples in the reference and validate `agents/openai.yaml` metadata.

- [ ] **Step 5: Run GREEN and skill validation**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_docker_autograder_contract_tests.ps1
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py .agents\skills\demo1-docker-autograder
```

Expected: contract summary has zero failures and skill validation says valid.

---

### Task 3: GradeSandbox Pipeline Transition

**Files:**
- Modify: `.agents/skills/demo1-macsrc-defect-intake/scripts/prepare_autograder_probe.ps1`
- Modify: `.agents/skills/demo1-macsrc-defect-intake/scripts/advance_autograder_probe.ps1`
- Modify: `.agents/skills/demo1-macsrc-defect-intake/SKILL.md`
- Modify: `.agents/skills/demo1-macsrc-defect-intake/references/defect-intent-contract.md`
- Modify: `scripts/demo1_macsrc_defect_intake_contract_tests.ps1`

**Interfaces:**
- Consumes: ready decision, intent spec, Docker grade job/result.
- Produces: `awx.autograder.grade-transition.v1`, intent promotion bound to the grade, and intent-bound `awx.red-evidence.v1`.

- [ ] **Step 1: Write failing transition tests**

Extend the existing real-script fixture so the dispatch stages are exactly:

```text
Finalize>GradeSandbox>PromoteIntent>PlanSession
```

Assert PromoteIntent refuses missing grade transition, a timeout result, zero
tests, exit zero, no JUnit failures, expected-signal false, stale decision SHA,
and stale intent-spec SHA. Assert a valid RED result derives a ready-last RED
record whose `intentSha256`, command, exit code, and output hash match immutable
inputs.

- [ ] **Step 2: Run RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_macsrc_defect_intake_contract_tests.ps1
```

Expected: GradeSandbox mode/stage is missing and PromoteIntent accepts the old
path without Docker evidence.

- [ ] **Step 3: Add GradeSandbox**

Add the mode and optional `-DockerBin`. Validate all ready records, call the
new wrapper, then publish a transition containing decision, intent-spec, job,
and result hashes. Keep `mutationAllowed=false`.

- [ ] **Step 4: Harden PromoteIntent and derive RED**

Require a valid `RED_PROBE` transition before calling the existing intent
builder. Recheck target/boundary preimages, build the intent, then publish RED
evidence bound to the new intent SHA. Promotion points to that RED file and
sets `nextAction=PLAN_GUARD_SESSION`.

- [ ] **Step 5: Run GREEN**

Run the defect-intake and guarded-session contracts. Expected: all assertions
pass and the temporary-root session remains HOLD for `macsrc-root-mismatch`.

---

### Task 4: Postprocessor Dynamic Grade

**Files:**
- Modify: `.agents/skills/demo1-macsrc-patch-postprocessor/scripts/new_postprocess_packet.ps1`
- Modify: `.agents/skills/demo1-macsrc-patch-postprocessor/scripts/prepare_patch_tri_query.ps1`
- Modify: `.agents/skills/demo1-macsrc-patch-postprocessor/SKILL.md`
- Modify: `.agents/skills/demo1-macsrc-patch-postprocessor/references/postprocess-contract.md`
- Modify: `scripts/demo1_macsrc_patch_postprocessor_contract_tests.ps1`

**Interfaces:**
- Consumes: optional ready-last GREEN Docker result bound into the frozen postprocess input map.
- Produces: base score, recomputed pass ratio, bounded delta, adjusted score, and Docker-specific HOLD classes/actions.

- [ ] **Step 1: Write failing score tests**

Add a literal fixture with `total=4`, `failed=1`, `errored=0`, `skipped=1`.
For base `73.5`, assert `passed=2`, `passRatio=0.5`, delta `0`, adjusted `73.5`.
Add all-pass delta `+10`, all-fail delta `-10`, clamp cases, forged ratio,
wrong run/purpose/hash, timeout, zero tests, and malformed count cases. Assert
tri-query packet hashes and `orderStable` are unchanged by the grade.

- [ ] **Step 2: Run RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_macsrc_patch_postprocessor_contract_tests.ps1
```

Expected: parameter/fields are absent and the old record publishes only the
base score.

- [ ] **Step 3: Freeze and validate Docker evidence**

Add the optional file to the input hash map before preparing tri-query
requests. Validate ready-last publication, schema, run, purpose, immutable
input manifest, execution status, counts, exit code, secret count, and policy
booleans. Recompute every count-derived field.

- [ ] **Step 4: Compute the bounded score**

Implement:

```powershell
$autograderDelta = [Math]::Round(20.0 * ($passRatio - 0.5), 4)
$adjustedGoalScore = [Math]::Round(
    [Math]::Min(100.0, [Math]::Max(0.0, $triScore + $autograderDelta)), 4)
```

Invalid/infrastructure evidence yields HOLD, no delta, and
`nextAction=RERUN_DOCKER_AUTOGRADER`. Failed GREEN yields
`autograder-green-failed` and cannot COMPLETE.

- [ ] **Step 5: Run GREEN**

Run the postprocessor contract and confirm submitted ratio/score values cannot
override recomputed values.

---

### Task 5: Full Verification and Handoff

**Files:**
- Modify only if validation finds a defect: the exact owning tool, test, skill, or reference file.

**Interfaces:**
- Consumes: all artifacts from Tasks 1-4.
- Produces: evidence-backed completion report with real-Docker smoke explicitly separated.

- [ ] **Step 1: Parse all changed PowerShell files**

Use `System.Management.Automation.Language.Parser.ParseFile` and require zero
parse errors.

- [ ] **Step 2: Run focused and regression suites**

Run controller, Docker wrapper, GoalScore, memory tri-query, memory autopatch,
defect intake, guarded session, patch postprocessor, and Python skill-family
tests. Require exit zero and record exact pass/fail counts.

- [ ] **Step 3: Validate skills and family metadata**

Run `quick_validate.py` for the new and three modified skills, then run the
repo skill-family validator with line/word budgets. Require no invalid skills,
prompt/metadata errors, scaffold markers, secret hits, or coverage failures.

- [ ] **Step 4: Recheck safety state**

Report canonical MacSrc root, index lock, top-level PatchDrop count, active
lease count, Git ownership classification, executable global-safe-directory
mutation count, secret count, changed application-source target count, and
Docker availability.

- [ ] **Step 5: Run the live-smoke decision**

If Docker remains unavailable, invoke the wrapper with a valid job and require
`docker-cli-unavailable`, HOLD, no score, and ready-last evidence. Do not install
Docker or pull an image. Record real container smoke as
`desktopFinalProof=evidence_needed`.
