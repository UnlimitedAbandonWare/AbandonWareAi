---
name: patchdrop-safe-patch-orchestrator
description: Use when demo-1 work involves PatchDrop, SMB/shared source
---

# PatchDrop Safe Patch Orchestrator

## Core Invariant

`C:\AbandonWare\demo-1\demo-1\src` is the Desktop canonical root. Mac mini may read it as evidence, but must not directly edit it through SMB/shared mounts. Mac mini produces a patch bundle; Desktop consumes, applies, and verifies it.

Use this skill to decide the safe path before touching source or applying PatchDrop artifacts.

## Role Decision

Pick exactly one role for the current pass:

| Role | Allowed action | Forbidden action |
| --- | --- | --- |
| Desktop source owner | Directly patch confirmed active source, or consume PatchDrop after gates pass | Trust Mac mini proof as final |
| Desktop patch consumer | Inspect one PatchDrop bundle, apply it on Desktop, run Desktop verification | Apply multiple active/incremental patches |
| Mac mini patch producer | Investigate in a Mac-owned worktree/temp copy and emit a bundle | Edit the Desktop canonical root or shared `WinSrc` |
| Read-only investigator | Produce evidence, score candidates, or write a handoff | Modify source |

If the current path is shared Desktop source on Mac mini, stop with `smb-conflict-risk` and switch to a Mac-owned worktree or temp copy.

## Desktop Preflight

Ordinary source-edit follows `AGENTS.md` `DEMO1-GIT-LOCAL-FIRST`: lease +
preimage, no `git status`/`rev-parse` intake gate. The Git CLI below is only
for PatchDrop `.patch` apply (`git apply`), not for starting a source session.

Run from `C:\AbandonWare\demo-1\demo-1\src` before PatchDrop apply:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
Get-Location
. .\__patch_drop__\source_edit_lease_contract.ps1
$GitEvidence = Get-AwxGitOperationEvidence -ProjectRoot $Root
Get-AwxScopedOperationDecision -Operation read-only -GitEvidence $GitEvidence

$PatchDrop = Join-Path $Root "__patch_drop__"
if (Test-Path "__patch_drop__\janitor_inventory.ps1") {
  powershell -NoProfile -ExecutionPolicy Bypass -File "__patch_drop__\janitor_inventory.ps1"
} else {
  Get-ChildItem $PatchDrop -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime |
    Select-Object Name,Length,LastWriteTime
}
```

If Git CLI or `.git` is unavailable, ordinary source-edit still proceeds via
lease/preimage. Record `evidence_needed: git metadata unavailable` only for
PatchDrop `git apply` of a `.patch`. Use sidecars, direct source reads, janitor
scripts, and Gradle evidence. Do not fabricate Git proof.

## One Active Bundle Rule

PatchDrop v3 allows exactly one active cumulative top-level patch per slug:

```text
__patch_drop__/
  <slug>-v3.patch
  <slug>-v3.report.md
  <slug>-v3.verify.log
  <slug>-v3.sha256.txt
  <slug>-v3.manifest.json
```

The manifest `activePatch` must name the patch. Do not select "latest" by timestamp when more than one candidate exists. Move older v1/v2/incremental attempts to `superseded/` or `rejected/` with a reason before Desktop applies anything.

Stop with `patch-drop-pending` when:

- more than one active top-level patch exists for the same slug;
- the v3 bundle is missing `.report.md`, `.verify.log`, `.sha256.txt`, or `.manifest.json`;
- the manifest `activePatch` does not match `<slug>-v3.patch`;
- sidecars describe an incremental chain instead of one cumulative patch.

## Mac Mini Producer Gates

These Git commands belong only to a Mac-owned worktree that emits a PatchDrop
`.patch`. They are not Desktop ordinary source-edit intake.

Mac mini must generate the bundle from a Mac-owned worktree or disposable apply copy:

```bash
pwd
git rev-parse --show-toplevel
git branch --show-current
git status --short
git worktree list
```

Producer requirements:

- use a branch such as `agent/macmini/<topic>`;
- use explicit pathspecs when generating `git diff`;
- include only task-scoped hunks;
- run local `git apply --check`, secret scan, temp actual-apply verification, focused Gradle where available, and reverse-apply check;
- write `Desktop final proof: evidence_needed until Desktop runs it` in the report.

Producer secret scan must count hits, not print values:

```bash
secret_hits=$(grep -EIn '(sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Authorization[:]|Cookie[:])' "$PATCH_FILE" | wc -l | tr -d ' ')
echo "secretPatternHits=$secret_hits"
test "$secret_hits" = "0"
```

## Desktop Consumer Gates

Desktop applies only the manifest-pinned v3 patch:

```powershell
$Slug = "<slug>"
$Patch = "__patch_drop__\$Slug-v3.patch"
$Report = "__patch_drop__\$Slug-v3.report.md"
$VerifyLog = "__patch_drop__\$Slug-v3.verify.log"
$Sha = "__patch_drop__\$Slug-v3.sha256.txt"
$Manifest = "__patch_drop__\$Slug-v3.manifest.json"

$active = Get-ChildItem "__patch_drop__" -File -Filter "$Slug*.patch"
if ($active.Count -ne 1 -or $active[0].Name -ne "$Slug-v3.patch") {
  Write-Error "[AWX][desktop] patch-drop-pending: exactly one active cumulative v3 patch is required"
  $active | Select-Object Name,Length,LastWriteTime
  exit 1
}
foreach ($f in @($Patch,$Report,$VerifyLog,$Sha,$Manifest)) {
  if (-not (Test-Path $f)) {
    Write-Error "[AWX][desktop] evidence_needed: missing bundle file $f"
    exit 1
  }
}
```

Verify checksums and secrets before applying:

```powershell
foreach ($line in Get-Content $Sha) {
  $parts = $line -split "\s+", 2
  if ($parts.Count -lt 2) { continue }
  $expected = $parts[0].ToLowerInvariant()
  $file = Join-Path "__patch_drop__" ($parts[1].Trim())
  $actual = (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($actual -ne $expected) {
    Write-Error "[AWX][desktop] sha-mismatch: $file"
    exit 1
  }
}

$hits = Select-String -Path $Patch -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Authorization[:]|Cookie[:]" -ErrorAction SilentlyContinue
Write-Host "[AWX][desktop][security] secretPatternHits=$($hits.Count)"
if ($hits.Count -ne 0) { exit 1 }
```

Apply and verify on Desktop:

```powershell
$Env:AWX_AGENT_HOST = "desktop"
$Env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$Env:AWX_BUILD_HOST_ID = "desktop"
$Env:GRADLE_USER_HOME = "$Env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache = "$Env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $Env:GRADLE_USER_HOME,$ProjectCache | Out-Null

git apply --check --whitespace=error-all $Patch
git apply $Patch
git diff --check
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $ProjectCache
git apply --reverse --check $Patch
```

Move the bundle to `__patch_drop__\applied\` only after Desktop verification succeeds. Move it to `rejected/` with a `.reason.txt` when any gate fails.

## Conflict Classifier

| Class | Trigger | Required response |
| --- | --- | --- |
| `smb-conflict-risk` | Mac mini/shared mount would edit canonical root | stop, switch to Mac-owned worktree/temp copy |
| `index-lock-conflict` | resolved index lock exists | preserve the lock; hold index/ref changes and unscoped applies; use `scoped-blocker-recovery` for independently authorized target-manifest/lease/preimage-verified file edits |
| `branch-ownership-mismatch` | Desktop is on `agent/macmini/*` | stop or get explicit user approval |
| `patch-drop-pending` | multiple/missing/non-cumulative active bundles | reject queue state, request one v3 cumulative bundle |
| `missing-bundle-meta` | `.report.md`, `.verify.log`, `.sha256.txt`, or manifest is absent | reject or isolate orphan metadata |
| `sha-mismatch` | sidecar checksum differs | reject bundle |
| `secret-leak-risk` | secret pattern count is nonzero | reject and request redacted regeneration |
| `filemode-blocked` | patch contains `old mode`, `new mode`, `deleted file mode`, or `new file mode` drift | reject unless user explicitly allows mode-only changes |
| `wrong-sourceset` | patch targets inactive mirror/archive | reject or request active sourceSet diff |
| `gradle-cache-collision` | host-local Gradle caches are not isolated | set host env/cache and rerun |
| `port-conflict` | Desktop boot/smoke ports `8080`/`8081` are already in use | stop smoke, identify owner, rerun sequentially |
| `desktop-proof-missing` | only Mac mini logs exist | report `ACTIVE`, not `PASS` |

## Completion Standard

Use these states:

- `PASS`: Desktop canonical root applied exactly one cumulative v3 patch and passed checksum, secret scan, dry-run/apply, focused Gradle, and reverse-apply check.
- `ACTIVE`: producer bundle exists or Mac mini proof passed, but Desktop final proof is missing.
- `BLOCKED`: gates fail or evidence is missing.

Final reports must name the role, bundle slug, active patch, accepted/rejected state, commands run, observed result, failure classifier, secret hit count, and next single action.

## Common Mistakes

- Treating Mac mini build logs as Desktop success. Keep them as supporting evidence only.
- Applying the newest `.patch` by timestamp. Use the manifest-pinned active v3 bundle.
- Leaving multiple active patch attempts for one slug. Supersede or reject older attempts first.
- Moving a patch to `applied/` before Desktop verification succeeds.
- Editing inactive mirrors such as `project/src/main/java`, `app/src/main/java`, archives, or build outputs without sourceSet proof.
