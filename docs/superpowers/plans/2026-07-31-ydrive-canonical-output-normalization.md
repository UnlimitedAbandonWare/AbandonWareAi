# Y-drive Canonical Output Normalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every new Notebook-facing workspace result use `Y:\` while keeping the real SMB backing identity private and preserving identity-mismatch safety gates.

**Architecture:** The repository policy owns a normalized SHA-256 baseline for the backing share. The global Notebook policy and SMB workspace skill compare the live mapping internally, discard the raw path, and emit only `Y:\`, `backingShareIdentityVerified`, and a redacted reason code. Existing historical mode, skill, script, and trace identifiers remain internal compatibility details.

**Tech Stack:** Markdown policy files, PowerShell 5+, Python 3 UTF-8 assertions, Codex skill validator, existing Y-drive SMB policy autograder.

## Global Constraints

- Canonical Notebook workspace and all user-facing workspace path fields are exactly `Y:\`.
- Normalize the live mapping with `Trim().TrimEnd('\\').ToLowerInvariant()` and SHA-256 over UTF-8 bytes.
- Expected normalized backing-identity SHA-256 is `30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9`.
- Never print or persist the raw `DisplayRoot`/UNC value in user-facing summaries, GoalContracts, SourceDirectives, commands, logs, or handoffs.
- Output backing identity only as `backingShareIdentityVerified=true|false` and `backingShareIdentityReason=match|mismatch|evidence-needed`.
- A missing baseline or mismatch is fail-closed with verdict `HOLD`; it never authorizes another source root.
- Preserve historical mode and skill identifiers as internal compatibility inputs, but do not echo them in new user-facing mode/status output.
- Preserve every unrelated existing modification in `Y:\AGENTS.md`; stop with `changed-preimage` if the file changes after its task preimage is captured.
- Do not edit application source, runtime resources, manifests, scripts, tests, prompt packs, Git configuration, Windows share configuration, or Supabase.
- Do not commit: the user has not separately authorized a commit.

---

## File Structure

- Modify `C:\Users\nninn\.codex\AGENTS.md`: global canonical-output and final-report policy.
- Modify `C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md`: reusable SMB decision/output contract.
- Modify `Y:\AGENTS.md`: demo-1 repository baseline hash, redaction rule, and canonical output rule.
- Do not create or modify tests; use focused inline contract assertions plus existing validators.

### Task 1: Global Notebook policy

**Files:**
- Modify: `C:\Users\nninn\.codex\AGENTS.md:15-18`
- Modify: `C:\Users\nninn\.codex\AGENTS.md:234-243`

**Interfaces:**
- Consumes: the approved normalized backing-identity SHA-256 from Global Constraints.
- Produces: the global `Y:\` path-output contract consumed by all Notebook GoalContracts and SourceDirectives.

- [ ] **Step 1: Capture the current preimage hash**

Run:

```powershell
$GlobalAgents = 'C:\Users\nninn\.codex\AGENTS.md'
$GlobalAgentsPreimage = (Get-FileHash -Algorithm SHA256 -LiteralPath $GlobalAgents).Hash
[pscustomobject]@{ path=$GlobalAgents; sha256=$GlobalAgentsPreimage } | ConvertTo-Json -Compress
```

Expected: one path and one 64-character SHA-256; no file content is printed.

- [ ] **Step 2: Run the RED global-output contract**

Run:

```powershell
$env:AWX_GLOBAL_AGENTS = 'C:\Users\nninn\.codex\AGENTS.md'
@'
from pathlib import Path
import os
p = Path(os.environ['AWX_GLOBAL_AGENTS'])
t = p.read_text(encoding='utf-8')
required = [
    'backingShareIdentityCheck=sha256-compare-only',
    'backingShareIdentityOutput=boolean-reason-only',
    'expectedBackingShareIdentitySha256=30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9',
    'backingShareIdentityVerified=true|false',
    'backingShareIdentityReason=match|mismatch|evidence-needed',
]
missing = [x for x in required if x not in t]
assert not missing, 'global-output-contract-missing:' + ','.join(missing)
print('GLOBAL_OUTPUT_CONTRACT_PASS')
'@ | python -X utf8 -
```

Expected before modification: FAIL containing `global-output-contract-missing`.

- [ ] **Step 3: Verify the preimage again immediately before editing**

Run the Step 1 hash command again and compare it with `$GlobalAgentsPreimage` retained by the executing agent. If it differs, stop with `changed-preimage`.

- [ ] **Step 4: Apply the minimal global-policy patch**

Use `apply_patch`. In the top contract block, replace the single backing-identity role line with:

```text
backingShareIdentityCheck=sha256-compare-only
backingShareIdentityOutput=boolean-reason-only
expectedBackingShareIdentitySha256=30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9
```

In `Canonical Y-drive routing for demo-1`, replace the raw `DisplayRoot` storage instruction with these rules:

```markdown
- Resolve the live `DisplayRoot` or mapped remote path into a temporary variable, normalize it with `Trim().TrimEnd('\\').ToLowerInvariant()`, and compare its UTF-8 SHA-256 with `expectedBackingShareIdentitySha256`; never print or persist the raw value.
- User-facing output reports only `canonicalWorkspace=Y:\`, `backingShareIdentityVerified=true|false`, and `backingShareIdentityReason=match|mismatch|evidence-needed`. A missing baseline or mismatch returns `HOLD` without exposing the backing path.
- Do not print Git top-level output when it resolves to UNC; compare it internally and retain `Y:\` in paths, commands, GoalContracts, SourceDirectives, and evidence summaries.
- Historical guard or mode identifiers may be accepted internally for compatibility but must not be echoed as new user-facing mode/status values.
```

Keep the existing authorization, lease, preimage, source-set, PatchDrop, secret, and Desktop-final-proof rules unchanged.

- [ ] **Step 5: Run the GREEN global-output contract**

Run the exact Step 2 command.

Expected: `GLOBAL_OUTPUT_CONTRACT_PASS`.

- [ ] **Step 6: Record a no-commit checkpoint**

Run:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'C:\Users\nninn\.codex\AGENTS.md' | Format-List
```

Expected: a postimage hash different from the Task 1 preimage. Do not commit.

### Task 2: Notebook SMB workspace skill

**Files:**
- Modify: `C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md:12-17`
- Modify: `C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md:29-35`
- Modify: `C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md:37-51`
- Modify: `C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md:57-62`
- Modify: `C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md:70-77`

**Interfaces:**
- Consumes: repository-owned expected SHA-256 and global output field names from Task 1.
- Produces: reusable decision rules that never expose the live backing path.

- [ ] **Step 1: Invoke the required skill-editing process**

Read and follow `superpowers:writing-skills` before editing this skill. Keep its changes limited to this existing skill; do not create a replacement skill.

- [ ] **Step 2: Capture the skill preimage and run the RED assertion**

Run:

```powershell
$SkillPath = 'C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md'
$SkillPreimage = (Get-FileHash -Algorithm SHA256 -LiteralPath $SkillPath).Hash
$env:AWX_SMB_SKILL = $SkillPath
@'
from pathlib import Path
import os
t = Path(os.environ['AWX_SMB_SKILL']).read_text(encoding='utf-8')
required = [
    'backingShareIdentityVerified',
    'backingShareIdentityReason',
    'never print or persist the raw',
    'expected backing-identity SHA-256',
]
missing = [x for x in required if x not in t]
assert not missing, 'smb-skill-output-contract-missing:' + ','.join(missing)
assert 'expected MacSrc `DisplayRoot`' not in t, 'raw-share-example-present'
print('SMB_SKILL_OUTPUT_CONTRACT_PASS')
'@ | python -X utf8 -
```

Expected before modification: FAIL containing `smb-skill-output-contract-missing` or `raw-share-example-present`.

- [ ] **Step 3: Verify the skill preimage again immediately before editing**

Recalculate the hash and compare it with `$SkillPreimage`. Stop with `changed-preimage` on any mismatch.

- [ ] **Step 4: Apply the minimal skill patch**

Use `apply_patch` to make these exact policy changes:

```markdown
## Mapped-Drive Canonical Workspace

When repository policy declares a mapped drive canonical, resolve the live
mapping only into a temporary value. Normalize that value with
`Trim().TrimEnd('\\').ToLowerInvariant()`, hash its UTF-8 bytes with SHA-256,
and compare it with the repository-owned expected backing-identity SHA-256.
Keep the mapped root as `canonicalWorkspace` and, when authorized,
`sourceWriteRoot`. Never print or persist the raw mapping value.

Return backing identity only as `backingShareIdentityVerified=true|false` and
`backingShareIdentityReason=match|mismatch|evidence-needed`. Missing or
mismatched identity evidence is `HOLD`.
```

Update Workflow step 2 to perform the hash comparison without emitting the raw value. Update Workflow step 5 to return `canonicalWorkspace`, the verification boolean, the redacted reason, mode, allowed surface, failure class, verification owner, and `evidence_needed`.

Add to Repository Guard Routing:

```markdown
Historical input and guard identifiers remain internal compatibility details.
New user-facing output emits only the canonical Y-drive mode/status and never
echoes a historical identifier or the raw backing path.
```

Add to Bounded Contract:

```markdown
- For mapped-drive identity evidence, paths means the canonical mapped path only; never emit the raw `DisplayRoot`, remote path, or a Git top-level path resolved to UNC.
```

Replace the example's share-name wording with `expected backing identity` and keep `Y:\` as the only displayed workspace path.

- [ ] **Step 5: Run the GREEN skill assertion**

Run the exact Task 2 Step 2 Python assertion.

Expected: `SMB_SKILL_OUTPUT_CONTRACT_PASS`.

- [ ] **Step 6: Validate the skill package**

Run:

```powershell
$env:PYTHONUTF8 = '1'
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py C:\Users\nninn\.codex\skills\notebook-smb-network-workspace
```

Expected: validator exit code `0` and a valid-skill result.

- [ ] **Step 7: Record a no-commit checkpoint**

Record the postimage SHA-256. Do not commit and do not modify any other personal skill.

### Task 3: Demo-1 repository policy

**Files:**
- Modify: `Y:\AGENTS.md:28-35`

**Interfaces:**
- Consumes: field names and hashing algorithm from Tasks 1-2.
- Produces: the repository-owned identity baseline and demo-1-specific fail-closed output rules.

- [ ] **Step 1: Capture the current dirty-file preimage**

Run:

```powershell
$ProjectAgents = 'Y:\AGENTS.md'
$ProjectAgentsPreimage = (Get-FileHash -Algorithm SHA256 -LiteralPath $ProjectAgents).Hash
git -c safe.directory=Y:/ status --short -- AGENTS.md
[pscustomobject]@{ path=$ProjectAgents; sha256=$ProjectAgentsPreimage; indexLock=(Test-Path 'Y:\.git\index.lock') } | ConvertTo-Json -Compress
```

Expected: `AGENTS.md` may already be modified, `indexLock` is `false`, and a preimage hash is captured without printing a UNC root.

- [ ] **Step 2: Run the RED repository-policy assertion**

Run:

```powershell
$env:AWX_PROJECT_AGENTS = 'Y:\AGENTS.md'
@'
from pathlib import Path
import os
t = Path(os.environ['AWX_PROJECT_AGENTS']).read_text(encoding='utf-8')
required = [
    '30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9',
    'backingShareIdentityVerified=true|false',
    'backingShareIdentityReason=match|mismatch|evidence-needed',
    'never print or persist the raw',
]
missing = [x for x in required if x not in t]
assert not missing, 'project-output-contract-missing:' + ','.join(missing)
print('PROJECT_OUTPUT_CONTRACT_PASS')
'@ | python -X utf8 -
```

Expected before modification: FAIL containing `project-output-contract-missing`.

- [ ] **Step 3: Verify the dirty-file preimage immediately before editing**

Recalculate the SHA-256 and compare it with `$ProjectAgentsPreimage`. If it differs, stop with `changed-preimage`; do not merge or overwrite concurrent changes.

- [ ] **Step 4: Apply a context-anchored minimal patch**

Use `apply_patch` only in `Desktop / Mac Mini / Notebook Workspaces`. Replace the single `DisplayRoot` bullet with:

```markdown
- The repository-owned expected backing-identity SHA-256 is `30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9`, computed from the live remote path after `Trim().TrimEnd('\\').ToLowerInvariant()` using UTF-8 bytes.
- Resolve the live `DisplayRoot` only into a temporary value and compare its SHA-256 with that baseline; never print or persist the raw mapping or a Git top-level path resolved to UNC.
- User-facing evidence reports only `canonicalWorkspace=Y:\`, `backingShareIdentityVerified=true|false`, and `backingShareIdentityReason=match|mismatch|evidence-needed`. A missing baseline or mismatch is `smb-root-identity-changed` and requires `HOLD`.
```

Extend the legacy compatibility bullet with: `Accept it internally only; new user-facing output must not echo it.` Keep every other existing dirty hunk byte-for-byte unchanged.

- [ ] **Step 5: Run the GREEN repository-policy assertion**

Run the exact Task 3 Step 2 command.

Expected: `PROJECT_OUTPUT_CONTRACT_PASS`.

- [ ] **Step 6: Review only the intended project diff**

Run:

```powershell
git -c safe.directory=Y:/ diff -- AGENTS.md
```

Expected: the previously existing dirty diff remains present; the newly added changes are limited to the Y-drive workspace bullets. Do not stage or commit.

### Task 4: Integrated verification and handoff

**Files:**
- Verify: `C:\Users\nninn\.codex\AGENTS.md`
- Verify: `C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md`
- Verify: `Y:\AGENTS.md`
- Verify without modifying: `Y:\scripts\ydrive_smb_workspace_policy_autograder.py`

**Interfaces:**
- Consumes: all three updated policy surfaces.
- Produces: count/hash/reason-only evidence for final reporting and rollback.

- [ ] **Step 1: Run the combined static contract**

Run:

```powershell
$env:AWX_GLOBAL_AGENTS = 'C:\Users\nninn\.codex\AGENTS.md'
$env:AWX_SMB_SKILL = 'C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md'
$env:AWX_PROJECT_AGENTS = 'Y:\AGENTS.md'
@'
from pathlib import Path
import os
import re
paths = [Path(os.environ[k]) for k in ('AWX_GLOBAL_AGENTS','AWX_SMB_SKILL','AWX_PROJECT_AGENTS')]
texts = [p.read_text(encoding='utf-8') for p in paths]
for p, t in zip(paths, texts):
    assert 'backingShareIdentityVerified=true|false' in t, f'missing-verified-field:{p.name}'
    assert 'backingShareIdentityReason=match|mismatch|evidence-needed' in t, f'missing-reason-field:{p.name}'
assert 'expected MacSrc `DisplayRoot`' not in texts[1], 'raw-share-example-present'
assert all(not re.search(r'\\\\[^\\\r\n]+\\[^\\\r\n]+', t) for t in texts), 'raw-unc-literal-present'
print('COMBINED_POLICY_CONTRACT_PASS files=3 raw_unc_literals=0')
'@ | python -X utf8 -
```

Expected: `COMBINED_POLICY_CONTRACT_PASS files=3 raw_unc_literals=0`.

- [ ] **Step 2: Run the existing policy autograder self-test**

Run:

```powershell
python -X utf8 Y:\scripts\ydrive_smb_workspace_policy_autograder.py --self-test
```

Expected: `self-test fixtures=13 failures=0`. This is a regression check only; its internal fixture schema is not the new user-facing output schema.

- [ ] **Step 3: Run the redacted live mapping probe**

Run:

```powershell
$ExpectedBackingHash = '30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9'
$mapping = Get-SmbMapping -LocalPath 'Y:' -ErrorAction Stop
$normalized = ([string]$mapping.RemotePath).Trim().TrimEnd('\').ToLowerInvariant()
$sha = [System.Security.Cryptography.SHA256]::Create()
try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($normalized)
    $actual = ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','')
} finally {
    $sha.Dispose()
}
$verified = $actual -eq $ExpectedBackingHash
$reason = if($verified){'match'}else{'mismatch'}
[pscustomobject]@{
    canonicalWorkspace='Y:\'
    backingShareIdentityVerified=$verified
    backingShareIdentityReason=$reason
    rawIdentityPrinted=$false
} | ConvertTo-Json -Compress
if(-not $verified){ throw 'smb-root-identity-changed' }
```

Expected: JSON containing `canonicalWorkspace="Y:\\"`, `backingShareIdentityVerified=true`, `backingShareIdentityReason="match"`, and `rawIdentityPrinted=false`; no raw UNC appears.

- [ ] **Step 4: Re-run the skill validator**

Run the exact Task 2 Step 6 command.

Expected: exit code `0`.

- [ ] **Step 5: Capture final hashes and mutation boundaries**

Run:

```powershell
$paths = @(
  'C:\Users\nninn\.codex\AGENTS.md',
  'C:\Users\nninn\.codex\skills\notebook-smb-network-workspace\SKILL.md',
  'Y:\AGENTS.md'
)
$rows = foreach($p in $paths){
  $f = Get-FileHash -Algorithm SHA256 -LiteralPath $p
  [pscustomobject]@{ path=$p; sha256=$f.Hash }
}
$rows | ConvertTo-Json -Compress
git -c safe.directory=Y:/ status --short -- AGENTS.md docs/superpowers/specs/2026-07-31-ydrive-canonical-output-normalization-design.md docs/superpowers/plans/2026-07-31-ydrive-canonical-output-normalization.md
```

Expected: three final hashes; only the approved policy/document paths appear from this task. Application source, Supabase, Git config, Windows share configuration, scripts, tests, and prompt packs remain untouched.

- [ ] **Step 6: Prepare rollback evidence**

Retain the exact three `apply_patch` hunks and preimage hashes in the completion report. Rollback is the inverse of only those hunks; never reset `Y:\AGENTS.md` or discard its unrelated pre-existing changes.
