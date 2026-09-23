---
name: macmini-safe-patch-assistant
description: Use when Mac mini agents produce PatchDrop bundles for the demo-1 Desktop canonical
---

# MacMini Safe Patch Assistant

## Purpose

Use this skill to produce one safe PatchDrop v3 bundle from a Mac mini patch-producer and hand it to the Windows Desktop patch-consumer. The invariant is simple: Mac mini investigates and emits evidence; Desktop owns final source apply and final Gradle proof.

## Non-Negotiables

- Do not edit the shared Desktop canonical root or shared `WinSrc` path from Mac mini.
- Do not leave multiple active top-level patches for the same topic in `__patch_drop__`.
- Do not ask Desktop to apply an incremental chain such as `v36`, then `v37`, then `v38`.
- Do not include unrelated dirty hunks. Generate the patch with an explicit target-file pathspec.
- Do not log or report raw secrets, authorization headers, cookies, full env dumps, raw sensitive queries, or large base64 payloads.
- Do not claim Desktop success from Mac mini logs. Desktop proof is required.

## Roles

**Mac mini patch-producer**

- Works only in a Mac-owned worktree or temporary verification copy.
- Creates a single cumulative PatchDrop v3 bundle.
- Runs local dry-run, secret scan, temp actual-apply verification, focused Gradle checks, and reverse-apply check.
- Marks older attempts as superseded instead of leaving them as active candidates.

**Desktop patch-consumer**

- Uses `C:\AbandonWare\demo-1\demo-1\src` as the canonical root.
- Treats Mac mini artifacts as evidence, not final proof.
- Applies only the pinned cumulative v3 bundle after checksum, secret scan, dry-run, apply-check, and focused verification.
- Moves accepted bundles to `__patch_drop__\applied\` only after Desktop verification succeeds.

## PatchDrop v3 Bundle Contract

For each topic, exactly one active top-level bundle is allowed:

```text
__patch_drop__/
  <slug>-v3.patch
  <slug>-v3.report.md
  <slug>-v3.verify.log
  <slug>-v3.sha256.txt
  <slug>-v3.manifest.json
```

Optional:

```text
__patch_drop__/
  <slug>-v3.diffstat.txt
  <slug>.superseded-by-v3.reason.txt
```

Rules:

- `v3` means the bundle protocol, not the third incremental attempt.
- `<slug>-v3.patch` must be cumulative against the declared Desktop base.
- If a later revision is needed, replace the active v3 bundle and move the older sidecars to `superseded/` or `rejected/` with a reason. Do not leave both as active top-level patches.
- The manifest must name the active patch, target files, base evidence, verification commands, and SHA256 values.
- If Desktop sees multiple active candidates for the same slug, it stops with `patch-drop-pending` and requests one cumulative v3 resubmission.

## Producer Workflow

Git CLI here is only for a Mac-owned worktree that **emits** a PatchDrop
`.patch`. It is not Desktop ordinary source-edit intake (`AGENTS.md`
`DEMO1-GIT-LOCAL-FIRST`).

### 1. Establish a clean producer surface

Run from the Mac-owned worktree, not from shared `WinSrc`:

```bash
pwd
git rev-parse --show-toplevel
git branch --show-current
git status --short
git worktree list
```

Stop when:

- the path is the Desktop canonical root or a shared SMB source path;
- unrelated dirty files overlap the task;
- the current branch is not Mac-owned, for example not `agent/macmini/<topic>`;
- Git metadata is unavailable.

### 2. Define the single active bundle

```bash
export PATCH_SLUG="macmini-<topic>"
export PATCH_VER="v3"
export PATCH_DIR="__patch_drop__"
export PATCH_FILE="$PATCH_DIR/${PATCH_SLUG}-${PATCH_VER}.patch"
export REPORT_FILE="$PATCH_DIR/${PATCH_SLUG}-${PATCH_VER}.report.md"
export VERIFY_LOG="$PATCH_DIR/${PATCH_SLUG}-${PATCH_VER}.verify.log"
export SHA_FILE="$PATCH_DIR/${PATCH_SLUG}-${PATCH_VER}.sha256.txt"
export MANIFEST_FILE="$PATCH_DIR/${PATCH_SLUG}-${PATCH_VER}.manifest.json"
mkdir -p "$PATCH_DIR" "$PATCH_DIR/superseded"
```

Before writing the new bundle, move older active attempts for the same slug:

```bash
for f in "$PATCH_DIR/${PATCH_SLUG}"*.patch \
         "$PATCH_DIR/${PATCH_SLUG}"*.report.md \
         "$PATCH_DIR/${PATCH_SLUG}"*.verify.log \
         "$PATCH_DIR/${PATCH_SLUG}"*.sha256.txt \
         "$PATCH_DIR/${PATCH_SLUG}"*.manifest.json \
         "$PATCH_DIR/${PATCH_SLUG}"*.diffstat.txt; do
  [ -e "$f" ] || continue
  case "$f" in
    "$PATCH_FILE"|"$REPORT_FILE"|"$VERIFY_LOG"|"$SHA_FILE"|"$MANIFEST_FILE") ;;
    *) mv "$f" "$PATCH_DIR/superseded/" ;;
  esac
done
printf 'superseded by %s\n' "$(basename "$PATCH_FILE")" > "$PATCH_DIR/${PATCH_SLUG}.superseded-by-v3.reason.txt"
```

### 3. Generate only task-scoped hunks

Use explicit target files. Do not pipe the whole dirty worktree into PatchDrop.

```bash
git diff -- \
  main/java/path/to/ChangedFile.java \
  src/test/java/path/to/ChangedFileTest.java \
  > "$PATCH_FILE"
```

The patch must be non-empty and must not contain unrelated files:

```bash
test -s "$PATCH_FILE"
git diff --name-only -- \
  main/java/path/to/ChangedFile.java \
  src/test/java/path/to/ChangedFileTest.java
```

### 4. Run producer safety gates

Append command output to `.verify.log`; do not summarize success without the raw command and exit status.

```bash
{
  echo "## producer-gates"
  git apply --check --whitespace=error-all "$PATCH_FILE"
  patch -p1 --dry-run < "$PATCH_FILE"
  git diff --check -- "$PATCH_FILE" || true
  secret_hits=$(grep -EIn '(sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Authorization:|Cookie:)' "$PATCH_FILE" | wc -l | tr -d ' ')
  echo "secretPatternHits=$secret_hits"
  test "$secret_hits" = "0"
} 2>&1 | tee "$VERIFY_LOG"
```

### 5. Verify in a temporary actual-apply copy

Use a disposable worktree or copy. Never use shared `WinSrc` as the verification sandbox.

```bash
TMP_ROOT="${TMPDIR:-/tmp}/awx-${PATCH_SLUG}-${PATCH_VER}-verify"
rm -rf "$TMP_ROOT"
git worktree add --detach "$TMP_ROOT" HEAD
cp "$PATCH_FILE" "$TMP_ROOT/"
(
  cd "$TMP_ROOT"
  git apply --check --whitespace=error-all "$(basename "$PATCH_FILE")"
  git apply "$(basename "$PATCH_FILE")"
  git diff --check

  export AWX_AGENT_HOST="macmini"
  export AWX_SPLIT_BUILD_OUTPUTS="1"
  export AWX_BUILD_HOST_ID="macmini"
  export GRADLE_USER_HOME="${HOME}/.gradle-awx-macmini"
  export AWX_GRADLE_PROJECT_CACHE="${TMPDIR:-/tmp}/awx-gradle-project-cache/macmini"
  mkdir -p "$GRADLE_USER_HOME" "$AWX_GRADLE_PROJECT_CACHE"

  ./gradlew test --tests '*FocusedPattern*' --no-daemon --project-cache-dir "$AWX_GRADLE_PROJECT_CACHE"
  ./gradlew checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test --no-daemon --project-cache-dir "$AWX_GRADLE_PROJECT_CACHE"
  git apply --reverse --check "$(basename "$PATCH_FILE")"
) 2>&1 | tee -a "$VERIFY_LOG"
git worktree remove --force "$TMP_ROOT"
```

If `git worktree add` is unavailable, stop with `evidence_needed: temporary apply copy unavailable`; do not verify by modifying shared Desktop source.

### 6. Write report, manifest, and checksums

The report must be short and evidence-first:

```md
## Observation
- producer root:
- branch:
- target Desktop root: C:\AbandonWare\demo-1\demo-1\src
- active bundle: <slug>-v3.patch
- superseded bundles:
- dirty worktree handling:

## Patch Blocks
- production:
- tests:
- excluded dirty files:
- secret masking:

## Verification
- git apply --check:
- patch dry-run:
- temp actual apply:
- focused Gradle:
- reverse apply check:
- secretPatternHits:
- Desktop final proof: evidence_needed until Desktop runs it

## Desktop Instructions
- apply only <slug>-v3.patch
- reject any older v1/v2/incremental bundle for this slug
```

Manifest template:

```json
{
  "protocolVersion": "patchdrop-v3",
  "cumulative": true,
  "activePatch": "<slug>-v3.patch",
  "targetDesktopRoot": "C:\\AbandonWare\\demo-1\\demo-1\\src",
  "producerRole": "macmini-patch-producer",
  "consumerRole": "desktop-patch-consumer",
  "targetFiles": [],
  "supersedes": [],
  "verification": {
    "gitApplyCheck": "recorded-in-verify-log",
    "patchDryRun": "recorded-in-verify-log",
    "secretScan": "recorded-in-verify-log",
    "tempActualApply": "recorded-in-verify-log",
    "focusedGradle": "recorded-in-verify-log",
    "reverseApplyCheck": "recorded-in-verify-log"
  }
}
```

Checksum file:

```bash
(
  cd "$PATCH_DIR"
  sha256sum "$(basename "$PATCH_FILE")" \
            "$(basename "$REPORT_FILE")" \
            "$(basename "$VERIFY_LOG")" \
            "$(basename "$MANIFEST_FILE")" \
            > "$(basename "$SHA_FILE")"
)
```

## Desktop Consumer Workflow

Run from `C:\AbandonWare\demo-1\demo-1\src` in PowerShell.

### 1. Intake

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
Get-Location
. .\__patch_drop__\source_edit_lease_contract.ps1
$GitEvidence = Get-AwxGitOperationEvidence -ProjectRoot $Root
if ($GitEvidence.activeOperation) {
  Write-Error "[AWX][desktop] git-operation-active"
  exit 1
}
powershell -NoProfile -ExecutionPolicy Bypass -File "__patch_drop__\janitor_inventory.ps1"
```

Desktop intake does not run `git status`. Git-absent is not a source-edit hold.
Report `evidence_needed: git metadata unavailable` only when this pass must
`git apply` a PatchDrop `.patch` and Git CLI is missing. Do not claim apply
safety from memory.

### 2. Pin one active cumulative bundle

```powershell
$Slug = "macmini-<topic>"
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

### 3. Verify manifest, checksum, and secrets

```powershell
$expectedLines = Get-Content $Sha
foreach ($line in $expectedLines) {
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

$hits = Select-String -Path $Patch -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Authorization:|Cookie:" -ErrorAction SilentlyContinue
Write-Host "[AWX][desktop][security] secretPatternHits=$($hits.Count)"
if ($hits.Count -ne 0) { exit 1 }
```

### 4. Dry-run, apply, verify, reverse-check

```powershell
$Env:AWX_AGENT_HOST = "desktop"
$Env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$Env:AWX_BUILD_HOST_ID = "desktop"
$Env:GRADLE_USER_HOME = "$Env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache = "$Env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $Env:GRADLE_USER_HOME,$ProjectCache | Out-Null

git apply --check --whitespace=error-all $Patch
if (Get-Command patch -ErrorAction SilentlyContinue) {
  patch -p1 --dry-run -i $Patch
}
git apply $Patch
git diff --check

.\gradlew.bat test --tests "*FocusedPattern*" --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $ProjectCache

git apply --reverse --check $Patch
```

Move the bundle only after successful Desktop verification:

```powershell
$Applied = "__patch_drop__\applied"
New-Item -ItemType Directory -Force -Path $Applied | Out-Null
Move-Item "__patch_drop__\$Slug-v3.*" $Applied -Force
```

## Conflict Checklist

Stop and classify before editing or applying when any item is true:

| Class | Trigger | Action |
| --- | --- | --- |
| `patch-drop-pending` | more than one top-level patch for a slug | request one cumulative v3 bundle |
| `smb-conflict-risk` | Mac mini is about to edit shared Desktop root | switch to Mac-owned worktree/temp copy |
| `dirty-worktree-risk` | unrelated dirty hunks overlap task files | isolate or revert only with explicit owner consent |
| `sha-mismatch` | checksum differs from manifest | reject bundle |
| `secret-leak-risk` | secret scan count is nonzero | reject bundle and request redaction |
| `wrong-sourceset` | patch touches inactive mirror/archive | reject or request active-root resubmission |
| `gradle-cache-collision` | build uses shared cache across hosts | set host-local `GRADLE_USER_HOME` and project cache |
| `desktop-proof-missing` | only Mac mini logs exist | keep status `ACTIVE`, not `PASS` |

## Acceptance States

- `PASS`: Desktop canonical root applied the single v3 cumulative bundle and passed checksum, secret scan, apply-check, focused Gradle, and reverse-apply check.
- `ACTIVE`: Mac mini producer verification passed, but Desktop final proof is still missing.
- `BLOCKED`: bundle is missing metadata, has multiple active variants, leaks secrets, fails checksum, fails dry-run/apply-check, or fails focused verification.

## Self-Test Scenarios

Use these scenarios to check whether an assistant follows the skill:

1. PatchDrop contains `topic-v1.patch`, `topic-v2.patch`, and `topic-v3.patch`: assistant must reject the chain and request one cumulative `topic-v3.patch`.
2. Mac mini can access `WinSrc` over SMB: assistant must not edit it and must create a temp/worktree apply verification copy.
3. `.sha256.txt` does not match the patch: assistant must stop with `sha-mismatch`.
4. Patch applies locally on Mac mini but Desktop has not verified it: assistant must report `ACTIVE`, not `PASS`.
5. Patch contains a raw `Authorization:` header or API key pattern: assistant must reject with `secret-leak-risk`.
