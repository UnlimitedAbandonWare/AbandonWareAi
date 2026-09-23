# Desktop Only Proof Loop Reference

## Preflight Commands

Run from the repo root before deciding Desktop-only proof is enough:

```powershell
Get-Location
$indexOp = (Test-Path ".git\MERGE_HEAD") -or (Test-Path ".git\CHERRY_PICK_HEAD") -or (Test-Path ".git\rebase-merge") -or (Test-Path ".git\rebase-apply")
if ($indexOp) { Write-Error "[AWX][desktop] index-operation-active"; exit 1 }
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
'{"nodeRole":"desktop","root":".","requestId":"desktop-only-source-scan","sessionId":"desktop-only"}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - source_scan
```

Stop before edits for a real index/ref operation in progress
(`index-lock-conflict`), an overlapping writer/lease/preimage conflict,
active top-level PatchDrop queue, or unproven sourceSet.

## Narrow Verification Ladder

Prefer the smallest command that can falsify the decision:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\goal_next_auto_tests.ps1
python .\scripts\awx_mcp_completion_audit.py --root .
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
```

## Output Fields

- `root`, `branch`, `indexLock`, `patchDropTopLevelCount`, `pendingProducerCount`
- `activeSourceSets`
- `desktopDecision`: `desktop_only_ready`, `evidence_needed`, or `blocked`
- `supportingEvidenceMissing`: Mac mini, Notebook, Browser, Computer, Supabase as applicable
- `nextAction`: `none_for_desktop_only` or one exact command
