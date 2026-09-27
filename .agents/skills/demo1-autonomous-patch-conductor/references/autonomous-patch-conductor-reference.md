# Autonomous Patch Conductor Reference

This reference keeps the long operating details out of `SKILL.md`. Load the
needed section only after the entrypoint selects this skill.

## Session Start Checklist

Run from the Desktop canonical root.

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-Location
$indexOp = (Test-Path ".git\MERGE_HEAD") -or (Test-Path ".git\CHERRY_PICK_HEAD") -or (Test-Path ".git\rebase-merge") -or (Test-Path ".git\rebase-apply")
if ($indexOp) { Write-Error "[AWX] index-operation-active"; exit 1 }

powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1

$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null

.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
```

Stop before edits on a real index/ref operation in progress
(`index-lock-conflict`), ambiguous PatchDrop top-level
patches, an overlapping writer/lease/preimage conflict, unreviewable
overlapping file changes, or unproven active sourceSet.

## Patch Cycle Loop

Use this loop for each patch cycle.

1. Choose the first OPEN item from `agent-prompts/data/patch_backlog.yaml` or
   the current directive's patch backlog.
2. Confirm `id`, `priority`, `status`, `target_files`, required trace keys,
   focused test pattern, and risk.
3. Re-read target files and refresh their sha256 preimages for those paths.
4. Add a characterization or RED test when behavior is nontrivial and the repo
   already has a focused test surface.
5. Patch the smallest active source or script branch.
6. Keep trace labels redacted, preferably through existing `SafeRedactor` or
   local redaction helpers.
7. Verify narrowly. Mark `DONE` only after proof. Mark `SKIP` with `failReason`
   when the seam is invalid, already handled, or blocked by unrelated failure.
8. Every few successful cycles, refresh source-score or harmony reports if they
   are relevant to the objective.

Do not keep looping after the failure class changes. Report the new class and
the exact command that exposed it.

## Verification Commands

Use only commands that exist in the checkout. Set Desktop cache isolation before
Gradle:

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
```

Narrow source gates:

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
```

Focused test gate, replacing the pattern with the actual changed surface:

```powershell
.\gradlew.bat test --tests "*AgentPipelineHealth*" --no-daemon --project-cache-dir $pcd
```

PatchDrop guard gates:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_tests.ps1 -Suite CoreGuards
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
```

Control Tower and completion audit gates:

```powershell
'{"nodeRole":"desktop","root":".","requestId":"autonomous-source-scan","sessionId":"autonomous-patch"}' | python scripts\awx_mcp_toolbox.py --input-json - source_scan
python scripts\awx_mcp_completion_audit.py --root .
```

Count-only secret scan:

```powershell
$hits = Select-String -Path ".\main\java\**\*.java",".\main\resources\**\*.yml",".\main\resources\**\*.yaml",".\scripts\*.ps1",".\scripts\*.py" -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}" -Recurse -ErrorAction SilentlyContinue
Write-Host "[AWX][desktop][security] secretHits=$($hits.Count)"
```

Run `bootJar -x test` only after narrow P0/P1 checks pass or the changed
surface requires a package-level gate.

## Session Report Contract

Report in Korean with the user's requested Safe Patch sections when this skill
is used for repo work:

- `요약`
- `do01 / Observation`
- `do02 / Patch Blocks`
- `do03 / Setup Commands`
- `do04 / Verification`
- `do05 / Risks & Next Steps`

Include command, expected success condition, observed result, failure class,
retry decision, and remaining `evidence_needed`. Do not claim build, boot,
Browser, Computer, Supabase, Mac mini, or Notebook proof without current command
or sidecar evidence.

## Patch Backlog Management

Default backlog path:

```text
agent-prompts/data/patch_backlog.yaml
```

If no backlog file exists, use the pasted directive's patch board as an inline
backlog and record the decision in the final report. Preserve existing status
fields where possible. Do not rewrite a broad backlog just to mark one item.

Recommended item fields:

```yaml
id: short-stable-id
priority: P0
status: OPEN
target_files:
  - main/java/example/Owner.java
trace_keys_required:
  - example.trace.key
test_pattern: "*OwnerTest*"
risk: medium
```

## Prohibitions

- Do not edit inactive mirrors, archives, generated output, backup trees, real
  secret files, `.env` files, shell profiles, `openssl`, or `opnessl`.
- Do not add a new orchestration framework, SMB service, background daemon, or
  agent broker.
- Do not fabricate external evidence or turn missing optional proof into OK.
- Do not ignore failed tests and continue as though the patch succeeded.
- Do not use stale default build artifacts when Desktop split outputs are
  enabled.
- Do not run parallel `bootRun` smoke checks against the same host/cache.
- Do not mark `DONE` without a patch or a proven `no_patch_needed` finding.
