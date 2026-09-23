---
name: abandonware-desktop-zombie-purge-safe-patch
description: Use when working on AbandonWareX/demo-1 Desktop canonical-root zombie-code cleanup
---

# AbandonWare Desktop Zombie Purge Safe Patch

Use this skill only from the Desktop canonical-owner perspective. Treat Mac mini, Notebook, archive, report, and PatchDrop claims as evidence, not final proof.

## Required Context

- Canonical Desktop root: `C:\AbandonWare\demo-1\demo-1\src`.
- Default active backend roots: `main/java` and `main/resources`.
- Default active `:app` roots: `app/src/main/java_clean` and `app/src/main/resources`.
- Inactive unless Gradle proves otherwise: `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, backups, archives, generated output, and old overlay folders.
- For detailed candidate families and deletion gates, read `references/zombie-purge-candidates.md`.
- Before any deletion, run the read-only helper: `scripts/zombie_candidate_audit.py`.

## Hard Stops

Stop and report `evidence_needed` instead of deleting when any of these are true:

- The active sourceSet is not proven from live Gradle/source evidence.
- An actual Git index/ref operation or live git writer is in progress (a bare `.git\index.lock` file alone is not a stop), PatchDrop has an applyable pending bundle for the same files, or a source-edit lease blocks Desktop ownership.
- The candidate is identified only by simple class name rather than package plus FQCN.
- The file is a Spring component, auto-configuration entry, prompt boundary, provider seam, guard, or canonical runtime class listed in `AGENTS.md`.
- Usage evidence is ambiguous, generated from stale archives, or only present in Mac mini/Notebook logs.
- The patch would touch secrets, `.env*`, `apikey*`, `openssl`, `opnessl`, real keystores, or raw credential values.
- Verification cannot run and no narrower static proof can disprove the deletion risk.

## Workflow

1. Confirm Desktop ownership and active roots:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
Get-Location
$indexOp = (Test-Path ".git\MERGE_HEAD") -or (Test-Path ".git\CHERRY_PICK_HEAD") -or (Test-Path ".git\rebase-merge") -or (Test-Path ".git\rebase-apply")
if ($indexOp) { Write-Error "[AWX][desktop] index-operation-active"; exit 1 }
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
rg -n "sourceSets|srcDirs|java_clean|main/java|main/resources|app/src/main" build.gradle.kts app/build.gradle.kts AGENTS.md
```

2. Build a candidate list from current source evidence only. Prefer exact paths and FQCNs. Never merge or delete by simple class name.

3. Run the read-only audit:

```powershell
python .agents\skills\abandonware-desktop-zombie-purge-safe-patch\scripts\zombie_candidate_audit.py --root . --candidates candidates.md --format markdown
python .agents\skills\abandonware-desktop-zombie-purge-safe-patch\scripts\zombie_candidate_audit.py --root . --candidates candidates.md --format json
```

If the skill lives outside the repo, use its absolute path under `%USERPROFILE%\.codex\skills\...`; the canonical repo location is `.agents\skills\abandonware-desktop-zombie-purge-safe-patch\`.

4. Patch only candidates classified as `REVIEW_DELETE_CANDIDATE` after manual confirmation that they are not active runtime seams. Keep each deletion small and reversible.

5. Verify from the Desktop root, narrowing first:

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null

if (Test-Path "__patch_drop__\janitor_inventory.ps1") {
  powershell -NoProfile -ExecutionPolicy Bypass -File "__patch_drop__\janitor_inventory.ps1"
}
if (Test-Path "__patch_drop__\janitor_tests.ps1") {
  powershell -NoProfile -ExecutionPolicy Bypass -File "__patch_drop__\janitor_tests.ps1" -Suite CoreGuards
}
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $pcd
```

Run broader tests only when the deletion crosses shared runtime seams.

## Deletion Evidence Contract

For each deleted file, record:

- path, package, type, and FQCN;
- active source root label;
- canonical replacement or reason it is dead;
- exact import/FQCN/simple-name usage counts outside the candidate file;
- Spring/component/auto-configuration status;
- PatchDrop or Mac mini evidence used, if any;
- rollback note;
- verification commands and observed results.

## PatchDrop Handling

- Use PatchDrop patches as input evidence only until Desktop rechecks them.
- Run secret scan and `git apply --check` before applying any `.patch`.
- Apply only one manifest-pinned cumulative v3 bundle when PatchDrop is involved.
- Move applied bundles to `__patch_drop__\applied\` only after Desktop verification passes.
- If metadata or the `.patch` body is missing, classify as `MISSING_META`, `MISSING_PATCH`, or `patch-drop-pending` and stop the apply path.

## Reporting

Keep the final answer evidence-first:

- `Summary`: changed files and verification status.
- `Observation`: root, branch/Git availability, active sourceSets, PatchDrop state, decomposition mode.
- `Patch`: exact files changed or deleted, why those files only, rollback note.
- `Verification`: command, expected result, observed result, failure class, retry decision.
- `Risks & Next`: remaining blockers, one next urgent patch, confidence.
