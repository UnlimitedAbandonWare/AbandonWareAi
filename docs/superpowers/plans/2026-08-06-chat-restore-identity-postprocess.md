# Chat Restore Identity Postprocess Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Prevent startup chat restoration from adopting or rendering a session-detail response whose canonical detail.id does not exactly match the requested stored session ID.

**Architecture:** Reuse the existing validateSessionDetail(candidateId, detail) boundary already used by interactive session selection. The startup hydrator must validate before every ownership or UI mutation, keep the requested sid authoritative, and fail with one fixed redacted status. The first execution branch is evidence-based reconciliation: if the live files already contain this contract and the behavioral fixture is green, make no source edit and report verified_no_patch_needed.

**Tech Stack:** Browser JavaScript, Node.js vm-based contract fixture, JUnit 5 source contracts, Gradle Kotlin DSL, Spring Boot static resources, PowerShell on Windows 10.

## Global Constraints

- Approved design: docs/superpowers/specs/2026-08-06-chat-restore-identity-postprocess-directive-design.md.
- Canonical Desktop root: C:\AbandonWare\demo-1\demo-1\src.
- Active mutation targets are limited to main/resources/static/js/chat.js and scripts/chat_ui_stream_contract_tests.js.
- Do not modify HTML, CSS, Java, Gradle, DB, Supabase, providers, credentials, prompt manifests, or unrelated session-list behavior.
- Do not create a branch, commit, stage, push, pull request, stash, reset, checkout, or overwrite user-owned changes.
- Both target files are currently untracked. Their hashes are evidence anchors, not permission to replace their contents.
- Preserve found=false and 403/404 cleanup, both hydration generation checks, transcript-count protection, active-run semantics, and the normal same-ID restore path.
- detail.id is the canonical response identity because validateSessionDetail validates that field. A conflicting detail.sessionId alias must never become the restored owner.
- Invalid identity remains a safe one-shot restore failure in this work unit. Do not add retry semantics or reset restoredSessionHydrated without a separate design.
- Keep raw response bodies, prompts, credentials, and private payloads out of evidence. Use paths, hashes, booleans, counts, fixed reason codes, and redacted sentinels only.
- User instructions override the Superpowers default commit cadence: every task ends with stagedCount=0 and no commit.

## File Structure

- main/resources/static/js/chat.js: owns validateSessionDetail and hydrateRestoredSessionTranscript; modify only if the live guard is absent or incorrect and the RED fixture reproduces the defect.
- scripts/chat_ui_stream_contract_tests.js: owns the executable vm/fake-DOM restore contract; preserve the current broader atomic assertions and add no duplicate block.
- No new runtime, test, helper, configuration, or manifest file is permitted.

## Reconciled Live Snapshot

Observed on 2026-08-06 after written-design approval:

- branch: main
- HEAD: b6ec55d147f7ea4b5296e095b6f052811e1da42a
- main/resources/static/js/chat.js SHA-256: 1F6B275430A12832D848DFAB6C070C20D53092FDAB3AEDD8FA978F7C4DBD4C9E
- scripts/chat_ui_stream_contract_tests.js SHA-256: FB7F2DDB8D8ADE58BB2ED5AC231E97F829D78FD0301C787A82F262C49D510D01
- current source already calls validateSessionDetail(sid, detail) before mutation and uses sid plus validated data afterward.
- current fixture already checks mismatched ID, missing ID, storage/control/composer atomicity, no transcript or mode leakage, no wrong-ID resume, and a matching canonical ID with a conflicting sessionId alias.
- node scripts/chat_ui_stream_contract_tests.js returned [AWX][chat-ui] stream heartbeat contract OK.
- staged count: 0.
- .git/index.lock: absent.

The hashes in the approved design were earlier preimages. Do not restore those earlier bytes. Recompute the live hashes at execution time.

---

### Task 1: Freeze Current Evidence and Choose No-Op or Patch

**Files:**
- Inspect: main/resources/static/js/chat.js
- Inspect: scripts/chat_ui_stream_contract_tests.js
- Inspect: build.gradle.kts
- Inspect: AGENTS.md
- Do not modify files in this task.

**Interfaces:**
- Consumes: approved design, current working tree, current target-file bytes.
- Produces: exactly one disposition: verified_no_patch_needed, fixture_only_required, patch_required, or HOLD; plus preimage hashes, the full baseline Git status rows, baseline HEAD, and a redacted evidenceSnapshotHash.

- [ ] **Step 1: Confirm the Desktop root and mutation gates**

Run from PowerShell:

~~~powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
Set-Location -LiteralPath $root

$targets = @(
  'main/resources/static/js/chat.js',
  'scripts/chat_ui_stream_contract_tests.js'
)
$auditReceiptName = 'awx-chat-restore-identity-20260806.audit.json'
$auditReceiptOwner = 'codex-chat-restore-identity-20260806'
$auditReceiptSchema = 'awx.chat_restore.audit_receipt.v1'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$auditReceiptPath = [IO.Path]::GetFullPath(
  (Join-Path $tempRoot $auditReceiptName)
)
$auditReceiptParent = [IO.Path]::GetDirectoryName($auditReceiptPath).TrimEnd('\')
if (-not [StringComparer]::OrdinalIgnoreCase.Equals(
      $tempRoot,$auditReceiptParent
    ) -or [IO.Path]::GetFileName($auditReceiptPath) -cne $auditReceiptName) {
  throw 'audit-receipt-path-invalid'
}

function Get-PorcelainZEntries {
  param([string]$RepositoryRoot)
  $startInfo = [Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = 'git'
  $startInfo.Arguments =
    '-c core.quotepath=false status --porcelain=v1 -z --untracked-files=all'
  $startInfo.WorkingDirectory = $RepositoryRoot
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $startInfo
  try {
    if (-not $process.Start()) { throw 'git-status-start-failed' }
    $rawStatus = $process.StandardOutput.ReadToEnd()
    $null = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'git-status-failed' }
  } finally {
    $process.Dispose()
  }

  $records = @(
    $rawStatus.Split(
      [char[]]@([char]0),[StringSplitOptions]::RemoveEmptyEntries
    )
  )
  $entries = [System.Collections.Generic.List[object]]::new()
  for ($index = 0; $index -lt $records.Count; $index++) {
    $record = [string]$records[$index]
    if ($record.Length -lt 4) { throw 'git-status-record-invalid' }
    $status = $record.Substring(0,2)
    $paths = [System.Collections.Generic.List[string]]::new()
    $paths.Add($record.Substring(3).Replace('\','/'))
    if ($status.IndexOf('R') -ge 0 -or $status.IndexOf('C') -ge 0) {
      $index++
      if ($index -ge $records.Count) { throw 'git-status-rename-pair-missing' }
      $paths.Add(([string]$records[$index]).Replace('\','/'))
    }
    $entries.Add([pscustomobject]@{ status = $status; paths = $paths.ToArray() })
  }
  return $entries.ToArray()
}

if (Test-Path -LiteralPath $auditReceiptPath) {
  throw 'audit-receipt-collision'
}
$baselineHead = git rev-parse HEAD
$baselineGitEntries = @(Get-PorcelainZEntries -RepositoryRoot $root)
$baselineTargetHashes = @(
  $targets | ForEach-Object {
    [pscustomobject]@{
      path = $_
      hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash
    }
  }
)
$auditReceiptJson = [ordered]@{
  schemaVersion = $auditReceiptSchema
  owner = $auditReceiptOwner
  canonicalRoot = $root
  baselineHead = $baselineHead
  baselineGitEntries = $baselineGitEntries
  targetHashes = $baselineTargetHashes
} | ConvertTo-Json -Depth 8 -Compress
$auditReceiptBytes = [Text.UTF8Encoding]::new($false).GetBytes(
  $auditReceiptJson
)
$auditReceiptStream = $null
try {
  $auditReceiptStream = [IO.File]::Open(
    $auditReceiptPath,
    [IO.FileMode]::CreateNew,
    [IO.FileAccess]::Write,
    [IO.FileShare]::None
  )
} catch [IO.IOException] {
  throw 'audit-receipt-collision'
}
$auditReceiptWriteFailed = $false
try {
  $auditReceiptStream.Write($auditReceiptBytes,0,$auditReceiptBytes.Length)
  $auditReceiptStream.Flush($true)
} catch {
  $auditReceiptWriteFailed = $true
} finally {
  $auditReceiptStream.Dispose()
}
if ($auditReceiptWriteFailed) {
  [IO.File]::Delete($auditReceiptPath)
  throw 'audit-receipt-write-failed'
}

$preflight = [ordered]@{
  root = (Get-Location).Path
  branch = (git branch --show-current)
  head = $baselineHead
  worktrees = @(git worktree list --porcelain)
  fullStatusEntryCount = $baselineGitEntries.Count
  stagedCount = @((git diff --cached --name-only)).Count
  indexLock = (Test-Path -LiteralPath '.git\index.lock')
  auditReceiptCreated = (Test-Path -LiteralPath $auditReceiptPath)
  pendingTopLevelPatches = @(
    Get-ChildItem -LiteralPath '__patch_drop__' -File -Filter '*-v3.patch' -ErrorAction SilentlyContinue |
      Select-Object -ExpandProperty Name
  )
}
$preflight | ConvertTo-Json -Depth 4
& '.\__patch_drop__\source_edit_session.ps1' -Action status -Role desktop -Root $root
~~~

The validated, exclusive audit receipt is the only cross-shell carrier for
baselineHead, structured NUL-delimited Git entries, and preimage hashes. Never
overwrite it, move it into the repository, or print its raw status paths in the
public report. On every early HOLD path, validate its exact temp parent, name,
schema, owner, and canonical root before deleting only that receipt.

Expected safe state before any future mutation:

- root equals the canonical Desktop path.
- indexLock is false.
- stagedCount is 0.
- no active top-level PatchDrop patch conflicts with a Desktop source-owner lease.
- the lease status command reports source-edit-locks=0.

If the root, ownership, branch, queue, lease, or lock state is ambiguous, return HOLD with one reason code and do not edit.

- [ ] **Step 2: Reconfirm the active resource and test seams**

Run:

~~~powershell
rg -n 'main/resources|chatUiTest|src/chatUiTest' -- AGENTS.md build.gradle.kts
rg -n 'function validateSessionDetail|async function hydrateRestoredSessionTranscript|__sessionHydrationDetail|restoreIdentityCases' -- main/resources/static/js/chat.js scripts/chat_ui_stream_contract_tests.js
~~~

Expected: root main/resources remains the active backend resource owner; chatUiTest is defined in build.gradle.kts; both JavaScript owners exist. If live Gradle evidence points to another owner, return HOLD with reason active-owner-changed.

- [ ] **Step 3: Capture hashes and a bounded static contract probe**

Run:

~~~powershell
$chatPath = 'main/resources/static/js/chat.js'
$fixturePath = 'scripts/chat_ui_stream_contract_tests.js'
$chat = Get-Content -LiteralPath $chatPath -Raw -Encoding UTF8
$fixture = Get-Content -LiteralPath $fixturePath -Raw -Encoding UTF8
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$auditReceiptName = 'awx-chat-restore-identity-20260806.audit.json'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$auditReceiptPath = [IO.Path]::GetFullPath(
  (Join-Path $tempRoot $auditReceiptName)
)
if (-not [StringComparer]::OrdinalIgnoreCase.Equals(
      [IO.Path]::GetDirectoryName($auditReceiptPath).TrimEnd('\'),$tempRoot
    ) -or [IO.Path]::GetFileName($auditReceiptPath) -cne $auditReceiptName) {
  throw 'audit-receipt-path-invalid'
}
try {
  $auditReceipt = Get-Content -LiteralPath $auditReceiptPath -Raw -Encoding UTF8 |
    ConvertFrom-Json -ErrorAction Stop
} catch {
  throw 'audit-receipt-invalid'
}
if ($auditReceipt.schemaVersion -cne 'awx.chat_restore.audit_receipt.v1' -or
    $auditReceipt.owner -cne 'codex-chat-restore-identity-20260806' -or
    -not [StringComparer]::OrdinalIgnoreCase.Equals(
      [string]$auditReceipt.canonicalRoot,$root
    )) {
  throw 'audit-receipt-owner-mismatch'
}
$preimageChatHash = [string]@(
  $auditReceipt.targetHashes | Where-Object path -CEQ $chatPath
)[0].hash
$preimageFixtureHash = [string]@(
  $auditReceipt.targetHashes | Where-Object path -CEQ $fixturePath
)[0].hash

$probe = [ordered]@{
  chatHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $chatPath).Hash
  fixtureHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $fixturePath).Hash
  usesSharedValidator = $chat.Contains('const validated = validateSessionDetail(sid, detail);')
  rejectsInvalidBeforeMutation = $chat.Contains('if (!validated) {') -and
    $chat.Contains('setStatusRailValue(dom.traceStatus, "session restore unavailable");')
  remembersRequestedId = $chat.Contains('rememberCurrentSessionId(sid);')
  rendersValidatedMessages = $chat.Contains('const messages = validated.messages;')
  appliesValidatedSettings = $chat.Contains('applyRestoredSessionSettings(validated);')
  restoresRequestedBadge = $chat.Contains('restoreSessionModeBadge(sid, validated);')
  resumesRequestedRun = $chat.Contains('resumeStoredRunIfNeeded(sid)')
  permissiveOwnershipFallbackAbsent =
    -not $chat.Contains('normalizeSessionIdValue(detail?.sessionId ?? detail?.id) || sid')
  fixtureHasWrongId = $fixture.Contains("label: 'detail-id-mismatch'") -and
    $fixture.Contains('mismatchedRestoreResult === false')
  fixtureHasNoLeak = $fixture.Contains('PRIVATE_CROSS_SESSION_TRANSCRIPT_SENTINEL_') -and
    $fixture.Contains('session restore unavailable')
}
$probe | ConvertTo-Json
if ([string]$probe.chatHash -cne $preimageChatHash -or
    [string]$probe.fixtureHash -cne $preimageFixtureHash) {
  throw 'changed-preimage'
}
~~~

Apply this branch-specific matrix. A textual match is only a routing signal; it
does not replace the behavioral command in the next step.

- `verified_no_patch_needed`: every source-guard boolean and both fixture
  booleans are true, and Node exits 0.
- `fixture_only_required`: every source-guard boolean is true, one or both
  fixture booleans are false, and the unchanged baseline Node suite exits 0.
  This explicitly authorizes an `apply_patch` edit to the fixture file only
  after its exact preimage and ownership are rechecked; it does not require or
  claim a vulnerable-source RED.
- `patch_required`: at least one source-guard boolean is false and either an
  existing fixture fails specifically on wrong-ID adoption, or the fixture is
  absent and will first be added to demonstrate that specific RED.
- `HOLD`: Node fails for another reason, the matrix is mixed or ambiguous, or
  hashes/ownership drift.

- [ ] **Step 4: Run the executable Node contract**

Run:

~~~powershell
node scripts/chat_ui_stream_contract_tests.js
~~~

Expected current result:

~~~text
[AWX][chat-ui] stream heartbeat contract OK
~~~

Classify:

- all probe booleans true and Node exits 0: disposition=verified_no_patch_needed; skip Task 2 and continue to Task 3.
- all source-guard booleans are true, at least one fixture boolean is false, and baseline Node exits 0: disposition=fixture_only_required; continue to Task 2 under the explicit fixture-only authorization above, without source preflight or source lease.
- source guard is absent or wrong and the wrong-ID fixture is absent or fails because returned ID changes state: disposition=patch_required; continue to Task 2 only after the three-way preflight is stable APPLY.
- fixture exists and Node fails specifically because wrong-ID data changes ownership or UI: disposition=patch_required; continue to Task 2.
- Node fails for an unrelated contract, target hash changes during this task, or ownership is unclear: disposition=HOLD; name the exact failing assertion or changed hash.

- [ ] **Step 5: Reject concurrent drift**

Recompute both hashes immediately:

~~~powershell
$targets = @(
  'main/resources/static/js/chat.js',
  'scripts/chat_ui_stream_contract_tests.js'
)
Get-FileHash -Algorithm SHA256 -LiteralPath $targets |
  Select-Object Path, Hash
~~~

If either differs from Step 3, return HOLD with reason concurrent-target-drift. Do not infer that the newest bytes belong to this worker.

---

### Task 2: Conditionally Repair the Startup Restore Identity Boundary

**Files:**
- Modify only if required: scripts/chat_ui_stream_contract_tests.js, near the successful startup hydration block around current lines 4791-4957.
- Modify only if RED reproduces: main/resources/static/js/chat.js, inside hydrateRestoredSessionTranscript around current lines 1040-1063.

**Interfaces:**
- Consumes: Task 1 disposition=fixture_only_required or patch_required, frozen current evidence, exact current preimage hashes.
- Produces for fixture_only_required: one retained characterization fixture, no source diff, and disposition=test_contract_added_and_verified after the full ladder.
- Produces for patch_required: one failing wrong-ID behavioral contract, the minimal validator reuse patch, then a green Node contract.

- [ ] **Step 1: Route test-only work separately from source mutation**

If disposition=fixture_only_required, verify that chat.js still has every
source-guard boolean from Task 1, confirm the fixture preimage and ownership,
and skip Steps 2 and 3. The repo skill explicitly excludes test-only work from
the source-edit three-way preflight. Continue at Step 4 and retain the new
fixture after it passes.

If disposition=patch_required, continue to Step 2.

- [ ] **Step 2: Run the mandatory three-way source-edit preflight**

Use demo1-source-edit-three-way-preflight in single-agent-logical-roles mode. Freeze at most 20 redacted evidence rows and one evidenceSnapshotHash. Create exactly:

1. POSITIVE_QUERY with two to four falsifiable scenario IDs.
2. NEGATIVE_QUERY over the same snapshot and the exact same scenario-ID set.
3. NEUTRAL_QUERY over Positive-Negative and Negative-Positive order.

Use the fixed score:

~~~text
100 * (0.25*evidenceStrength + 0.20*causalStrength
+ 0.15*verificationFeasibility + 0.15*userValue + 0.10*reversibility
+ 0.10*costEfficiency + 0.05*timeFit - 0.20*blastRadius
- 0.15*ambiguity - 0.20*authorityOrSafetyExpansion)
~~~

Proceed only when both orderings return APPLY, decisive evidence IDs match, score is at least 50, verification is available, and nextWorkflow=existing-source-owner-guard. Otherwise return HOLD or REJECT as specified by the skill; do not create a fourth reviewer.

- [ ] **Step 3: Acquire the Desktop source-owner lease**

Run only after stable APPLY:

~~~powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$leaseArgs = @{
  Action = 'begin'
  Role = 'desktop'
  Root = $root
  Topic = 'chat-restore-identity-postprocess'
  OwnerId = 'codex-chat-restore-identity-20260806'
  TtlMinutes = 180
}
& '.\__patch_drop__\source_edit_session.ps1' @leaseArgs
if ($LASTEXITCODE -ne 0) { throw 'source-owner-lease-not-acquired' }
~~~

Keep the exact topic and owner values for the matching end command. Do not edit if lease acquisition fails.

- [ ] **Step 4: Verify both preimages immediately before apply_patch**

Run:

~~~powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$targets = @(
  'main/resources/static/js/chat.js',
  'scripts/chat_ui_stream_contract_tests.js'
)
$auditReceiptName = 'awx-chat-restore-identity-20260806.audit.json'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$auditReceiptPath = [IO.Path]::GetFullPath(
  (Join-Path $tempRoot $auditReceiptName)
)
if (-not [StringComparer]::OrdinalIgnoreCase.Equals(
      [IO.Path]::GetDirectoryName($auditReceiptPath).TrimEnd('\'),$tempRoot
    ) -or [IO.Path]::GetFileName($auditReceiptPath) -cne $auditReceiptName) {
  throw 'audit-receipt-path-invalid'
}
$auditReceipt = Get-Content -LiteralPath $auditReceiptPath -Raw -Encoding UTF8 |
  ConvertFrom-Json -ErrorAction Stop
if ($auditReceipt.schemaVersion -cne 'awx.chat_restore.audit_receipt.v1' -or
    $auditReceipt.owner -cne 'codex-chat-restore-identity-20260806' -or
    -not [StringComparer]::OrdinalIgnoreCase.Equals(
      [string]$auditReceipt.canonicalRoot,$root
    )) {
  throw 'audit-receipt-owner-mismatch'
}
$currentHashes = @(
  $targets | ForEach-Object {
    [pscustomobject]@{
      path = $_
      hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash
    }
  }
)
foreach ($currentHash in $currentHashes) {
  $expected = @(
    $auditReceipt.targetHashes | Where-Object path -CEQ $currentHash.path
  )
  if ($expected.Count -ne 1 -or
      [string]$expected[0].hash -cne [string]$currentHash.hash) {
    throw 'changed-preimage'
  }
}
$currentHashes
~~~

The self-contained block compares against the validated Task 1 receipt. A
mismatch is HOLD changed-preimage. Re-read the changed file and release the
lease; never restore an older body.

- [ ] **Step 5: Add the contract only if an equivalent wrong-ID fixture is absent**

The existing harness restore route is session 321, so use requested ID 321 and returned ID 42. Insert immediately after the normal startup-hydration assertions. Do not add this block if restoreIdentityCases or an equivalent stronger block already proves the same invariants.

~~~javascript
  fetchCalls.length = 0;
  chatWindow.children = [];
  chatWindow.textContent = '';
  sessionModeList.children = [];
  sessionModeList.textContent = '';
  elements.get('modelSelect').value = 'gemma4:26b';
  elements.get('searchModeSelect').value = 'AUTO';
  elements.get('useRagToggle').checked = true;
  elements.get('traceStatus').textContent = 'restore identity baseline';

  vm.runInContext(
    "rememberCurrentSessionId(321); restoredSessionHydrated = false; " +
      "localControlOverrideActive = false; " +
      "rememberActiveRunIdentity(321, 'run-321-identity');",
    context
  );

  const restoreStorageBefore =
    JSON.stringify(Array.from(sessionStorageBacking.entries()).sort());
  const restoreControlsBefore = JSON.stringify({
    model: elements.get('modelSelect').value,
    searchMode: elements.get('searchModeSelect').value,
    useRag: elements.get('useRagToggle').checked,
    inputDisabled: elements.get('messageInput').disabled,
    sendDisabled: elements.get('sendBtn').disabled,
    stopDisabled: elements.get('stopBtn').disabled
  });
  const restoreSentinel = 'PRIVATE_RESTORE_ID_MISMATCH_SENTINEL';
  context.__sessionHydrationDetail = {
    found: true,
    id: 42,
    title: 'wrong restored chat',
    messages: [{ id: 42, role: 'assistant', content: restoreSentinel }],
    modelUsed: 'gemma3:4b',
    answerMode: 'mismatched',
    traceTurnId: 'trace-mismatched',
    settings: { model: 'gemma3:4b', searchMode: 'OFF', useRag: false }
  };

  const restoreResult =
    await vm.runInContext('hydrateRestoredSessionTranscript()', context);
  await new Promise((resolve) => setImmediate(resolve));
  const restoreCalls = fetchCalls.slice();
  delete context.__sessionHydrationDetail;

  const restoreStorageAfter =
    JSON.stringify(Array.from(sessionStorageBacking.entries()).sort());
  const restoreControlsAfter = JSON.stringify({
    model: elements.get('modelSelect').value,
    searchMode: elements.get('searchModeSelect').value,
    useRag: elements.get('useRagToggle').checked,
    inputDisabled: elements.get('messageInput').disabled,
    sendDisabled: elements.get('sendBtn').disabled,
    stopDisabled: elements.get('stopBtn').disabled
  });

  assert(
    restoreResult === false &&
      vm.runInContext('state.currentSessionId', context) === 321 &&
      restoreStorageAfter === restoreStorageBefore,
    'wrong restore identity must preserve session, storage, and active run'
  );
  assert(
    chatWindow.children.length === 0 &&
      !nodeText(chatWindow).includes(restoreSentinel) &&
      sessionModeList.children.length === 0 &&
      !nodeText(sessionModeList).includes('42'),
    'wrong restore identity must not render transcript or mode state'
  );
  assert(
    restoreControlsAfter === restoreControlsBefore &&
      elements.get('traceStatus').textContent === 'session restore unavailable',
    'wrong restore identity must preserve controls and use fixed safe status'
  );
  assert(
    !restoreCalls.some((call) =>
      String(call.url).includes('sessionId=42') ||
      String(call.url).includes('/stream?attach=true')),
    'wrong restore identity must never resume returned session 42'
  );
~~~

Use apply_patch. Do not rewrite the fixture wholesale.

- [ ] **Step 6: Run the fixture and classify RED versus characterization**

Run:

~~~powershell
node scripts/chat_ui_stream_contract_tests.js
~~~

For disposition=patch_required, expected result is nonzero with the
wrong-identity assertion showing that session 42 was adopted or its content
rendered. Continue to Step 7.

For disposition=fixture_only_required, expected result is green because the
source guard predates the missing test. Retain the new fixture, set
disposition=test_contract_added_and_verified, make no chat.js edit, and
continue to Task 3. This is characterization backfill, not a claimed
vulnerable-source RED.

- [ ] **Step 7: Apply the minimal source patch**

Only after the RED failure, use apply_patch to make exactly this semantic change inside hydrateRestoredSessionTranscript:

~~~diff
-    const restoredSessionId = normalizeSessionIdValue(detail?.sessionId ?? detail?.id) || sid;
-    rememberCurrentSessionId(restoredSessionId);
-    const messages = Array.isArray(detail?.messages) ? detail.messages : [];
+    const validated = validateSessionDetail(sid, detail);
+    if (!validated) {
+      setStatusRailValue(dom.traceStatus, "session restore unavailable");
+      return false;
+    }
+    rememberCurrentSessionId(sid);
+    const messages = validated.messages;
     if (!hasExistingMessages) {
       for (const message of messages) {
-        const role = String(message?.role || "").toLowerCase();
-        if (role !== "user" && role !== "assistant") continue;
-        appendMessage(role, message?.content || "");
+        appendMessage(message.role, message.content);
       }
     }
-    applyRestoredSessionSettings(detail);
-    restoreSessionModeBadge(restoredSessionId, detail);
-    void resumeStoredRunIfNeeded(restoredSessionId);
+    applyRestoredSessionSettings(validated);
+    restoreSessionModeBadge(sid, validated);
+    void resumeStoredRunIfNeeded(sid);
~~~

Keep the preceding found=false branch and both generation/transcript guards byte-for-byte unless current context requires only whitespace alignment. Do not create a second validator.

- [ ] **Step 8: Run GREEN**

Run:

~~~powershell
node scripts/chat_ui_stream_contract_tests.js
~~~

Expected:

~~~text
[AWX][chat-ui] stream heartbeat contract OK
~~~

If it fails, inspect only the named assertion and the two active files. Do not widen into controller, HTML, list-refresh, retry, or accessibility work.

---

### Task 3: Verify the Contract, Collect Browser Proof, and Close Without Git Mutation

**Files:**
- Verify: main/resources/static/js/chat.js
- Verify: scripts/chat_ui_stream_contract_tests.js
- Verify without modification: src/test/java/com/example/lms/web/ChatUiSendMessageContractTest.java
- No source file is created in this task.

**Interfaces:**
- Consumes: Task 1 verified_no_patch_needed, Task 2 test_contract_added_and_verified, or Task 2 GREEN, plus stable target hashes and baseline Git state.
- Produces: final disposition patched_and_verified, test_contract_added_and_verified, verified_no_patch_needed, HOLD, or evidence_needed; command results, postimage hashes, full Git/HEAD delta, count-only secret results, and a released lease.

- [ ] **Step 1: Run focused and broad non-browser verification**

Use isolated Desktop build outputs and caches:

~~~powershell
$env:AWX_AGENT_HOST = 'desktop-chat-restore'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-chat-restore'
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle-awx-desktop-chat-restore'
$projectCache = Join-Path $env:USERPROFILE '.awx-gradle-project-cache\desktop-chat-restore'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$projectCache | Out-Null

node scripts/chat_ui_stream_contract_tests.js
if ($LASTEXITCODE -ne 0) { throw 'node-chat-ui-contract-failed' }

.\gradlew.bat test --tests 'com.example.lms.web.ChatUiSendMessageContractTest' --no-daemon --project-cache-dir $projectCache
if ($LASTEXITCODE -ne 0) { throw 'focused-chat-ui-contract-failed' }

.\gradlew.bat chatUiTest --no-daemon --project-cache-dir $projectCache
if ($LASTEXITCODE -ne 0) { throw 'chat-ui-test-suite-failed' }

.\gradlew.bat classes --no-daemon --project-cache-dir $projectCache
if ($LASTEXITCODE -ne 0) { throw 'classes-failed' }

.\gradlew.bat :app:classes --no-daemon --project-cache-dir $projectCache
if ($LASTEXITCODE -ne 0) { throw 'app-classes-failed' }

.\gradlew.bat bootJar --no-daemon --project-cache-dir $projectCache
if ($LASTEXITCODE -ne 0) { throw 'boot-jar-failed' }
~~~

Expected: Node prints its OK line and each Gradle invocation ends BUILD SUCCESSFUL. A stale broad test classpath failure is not permission to claim green; follow AGENTS.md refresh guidance or report evidence_needed with the exact failing task.

- [ ] **Step 2: Gate and obtain fresh browser happy-path proof**

The lifecycle block below rechecks both ports without killing any process. An
existing listener is trusted only when every owning process has an exact
path-boundary command-line ownership match for this checkout, started no
earlier than the newest target postimage, port 8080 is among the trusted
listeners, a fresh HTTP readiness probe returns 200, and the bytes served from
`/js/chat.js` hash exactly to the current local `chat.js` SHA-256. The command
line itself must never be printed or persisted. Any failed ownership predicate returns
`evidence_needed: port-owner-unproven`; a trusted listener that fails readiness
returns `evidence_needed: trusted-runtime-not-ready`.
An otherwise ready runtime with different served bytes returns
`evidence_needed: trusted-runtime-stale-bytes`.

When no listener exists, the block exclusively creates one fixed receipt under
the current user's OS temp directory before starting `bootRun`. This receipt is
the cross-shell ownership handoff for the later Browser-tool call and cleanup;
it must never be redirected into the repository or overwritten. A collision is
`HOLD: boot-receipt-collision`. The readiness stopwatch is a hard 60-second
ceiling, including HTTP timeouts and sleeps.

Immediately after `Start-Process` returns and before the first receipt byte is
written, the block emits one bounded recovery packet containing only schema,
owner, canonical root, PID, exact process start time, launch-attempt time, and
redaction booleans. Retain that local tool output until cleanup succeeds. If
receipt writing and emergency cleanup both fail, this packet—not partial JSON—
is the recovery authority; validate its PID/start-time and exact root boundary
before stopping anything. If the packet is missing, HOLD and do not guess.

~~~powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$targets = @(
  'main/resources/static/js/chat.js',
  'scripts/chat_ui_stream_contract_tests.js'
)
$env:AWX_AGENT_HOST = 'desktop-chat-restore'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-chat-restore'
$env:GRADLE_USER_HOME =
  Join-Path $env:USERPROFILE '.gradle-awx-desktop-chat-restore'
$projectCache = Join-Path $env:USERPROFILE `
  '.awx-gradle-project-cache\desktop-chat-restore'
New-Item -ItemType Directory -Force `
  -Path $env:GRADLE_USER_HOME,$projectCache | Out-Null
$currentChatHash = (
  Get-FileHash -Algorithm SHA256 `
    -LiteralPath (Join-Path $root 'main/resources/static/js/chat.js')
).Hash
$rootBoundaryPattern =
  '(?i)(?:^|[="''\s])' + [regex]::Escape($root) + '(?:[\\/"''\s]|$)'

Add-Type -AssemblyName System.Net.Http
function Get-UrlByteSha256 {
  param([string]$Uri,[int]$TimeoutSeconds)
  $client = [System.Net.Http.HttpClient]::new()
  $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
  try {
    $bytes = $client.GetByteArrayAsync($Uri).GetAwaiter().GetResult()
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
      return [BitConverter]::ToString($sha256.ComputeHash($bytes)).Replace('-','')
    } finally {
      $sha256.Dispose()
    }
  } finally {
    $client.Dispose()
  }
}

function Stop-OwnedProcessTreeVerified {
  param(
    [int]$RootProcessId,
    [string]$RootStartTimeUtc,
    [string]$CanonicalRoot,
    [string]$CanonicalRootBoundaryPattern
  )
  $rootStart = [DateTime]::Parse(
    $RootStartTimeUtc,
    [Globalization.CultureInfo]::InvariantCulture,
    [Globalization.DateTimeStyles]::RoundtripKind
  ).ToUniversalTime()
  $ownedProcessIds = [System.Collections.Generic.List[int]]::new()
  $seenProcessIds = [System.Collections.Generic.HashSet[int]]::new()
  $identityByProcessId = @{}
  $null = $ownedProcessIds.Add($RootProcessId)
  $null = $seenProcessIds.Add($RootProcessId)
  $identityByProcessId[$RootProcessId] = $RootStartTimeUtc
  $stopFailureCount = 0
  $stableEmptyRounds = 0

  for ($round = 0; $round -lt 6; $round++) {
    $addedThisRound = 0
    do {
      $addedInPass = 0
      $processRows = @(Get-CimInstance Win32_Process -ErrorAction Stop)
      foreach ($processRow in $processRows) {
        $parentProcessId = [int]$processRow.ParentProcessId
        $childProcessId = [int]$processRow.ProcessId
        if ($seenProcessIds.Contains($parentProcessId) -and
            $seenProcessIds.Add($childProcessId)) {
          $null = $ownedProcessIds.Add($childProcessId)
          $childProcess = Get-Process -Id $childProcessId -ErrorAction SilentlyContinue
          if ($childProcess) {
            $childStart = $childProcess.StartTime.ToUniversalTime()
            if ($childStart -lt $rootStart) { return $false }
            $identityByProcessId[$childProcessId] = $childStart.ToString('o')
          }
          $addedInPass++
          $addedThisRound++
        }
      }
    } while ($addedInPass -gt 0)

    foreach ($ownedProcessId in @($identityByProcessId.Keys)) {
      $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
      if ($ownedProcess -and
          $ownedProcess.StartTime.ToUniversalTime().ToString('o') -cne
            $identityByProcessId[$ownedProcessId]) {
        return $false
      }
    }

    for ($index = $ownedProcessIds.Count - 1; $index -ge 0; $index--) {
      $ownedProcessId = $ownedProcessIds[$index]
      if (-not $identityByProcessId.ContainsKey($ownedProcessId)) { continue }
      $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
      if (-not $ownedProcess) { continue }
      if ($ownedProcess.StartTime.ToUniversalTime().ToString('o') -cne
          $identityByProcessId[$ownedProcessId]) {
        return $false
      }
      try {
        Stop-Process -InputObject $ownedProcess -Force -ErrorAction Stop
      } catch {
        $stillOwned = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
        if ($stillOwned -and
            $stillOwned.StartTime.ToUniversalTime().ToString('o') -ceq
              $identityByProcessId[$ownedProcessId]) {
          $stopFailureCount++
        }
      }
    }

    Start-Sleep -Milliseconds 200
    $remainingOwnedCount = 0
    foreach ($ownedProcessId in @($identityByProcessId.Keys)) {
      $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
      if ($ownedProcess -and
          $ownedProcess.StartTime.ToUniversalTime().ToString('o') -ceq
            $identityByProcessId[$ownedProcessId]) {
        $remainingOwnedCount++
      }
    }
    if ($remainingOwnedCount -eq 0 -and $addedThisRound -eq 0) {
      $stableEmptyRounds++
      if ($stableEmptyRounds -ge 2) { break }
    } else {
      $stableEmptyRounds = 0
    }
  }

  $remainingOwnedCount = 0
  foreach ($ownedProcessId in @($identityByProcessId.Keys)) {
    $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
    if ($ownedProcess -and
        $ownedProcess.StartTime.ToUniversalTime().ToString('o') -ceq
          $identityByProcessId[$ownedProcessId]) {
      $remainingOwnedCount++
    }
  }
  $freshOwnedListenerCount = @(
    Get-NetTCPConnection -State Listen -LocalPort 8080,8081 `
      -ErrorAction SilentlyContinue | ForEach-Object {
        $listenerProcessId = [int]$_.OwningProcess
        $listenerProcess = Get-Process -Id $listenerProcessId `
          -ErrorAction SilentlyContinue
        if ($listenerProcess -and
            $listenerProcess.StartTime.ToUniversalTime() -ge $rootStart) {
          $listenerRecord = Get-CimInstance Win32_Process `
            -Filter "ProcessId = $listenerProcessId" -ErrorAction SilentlyContinue
          if ($listenerRecord -and [regex]::IsMatch(
                [string]$listenerRecord.CommandLine,
                $CanonicalRootBoundaryPattern
              )) {
            $_
          }
        }
      }
  ).Count
  return (
    $stopFailureCount -eq 0 -and
    $remainingOwnedCount -eq 0 -and
    $freshOwnedListenerCount -eq 0 -and
    $stableEmptyRounds -ge 2
  )
}

$receiptName = 'awx-chat-restore-identity-20260806.boot.json'
$receiptOwner = 'codex-chat-restore-identity-20260806'
$receiptSchema = 'awx.chat_restore.boot_receipt.v1'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$bootReceiptPath = [IO.Path]::GetFullPath((Join-Path $tempRoot $receiptName))
$receiptParent = [IO.Path]::GetDirectoryName($bootReceiptPath).TrimEnd('\')
if (-not [StringComparer]::OrdinalIgnoreCase.Equals($tempRoot,$receiptParent) -or
    [IO.Path]::GetFileName($bootReceiptPath) -cne $receiptName) {
  throw 'boot-receipt-path-invalid'
}

$listeners = @(
  Get-NetTCPConnection -State Listen -LocalPort 8080,8081 `
    -ErrorAction SilentlyContinue |
    Select-Object LocalAddress,LocalPort,OwningProcess
)
$bootStartedByThisRun = $false
$browserRuntimeReady = $false
$trustedExistingRuntime = $false
$targetLatestWriteUtc = @(
  $targets | ForEach-Object {
    (Get-Item -LiteralPath (Join-Path $root $_)).LastWriteTimeUtc
  }
) | Sort-Object -Descending | Select-Object -First 1

if ($listeners.Count -gt 0) {
  $ownerProof = @(
    $listeners.OwningProcess | Sort-Object -Unique | ForEach-Object {
      $ownerProcessId = [int]$_
      $ownedByCanonicalRoot = $false
      $startedAfterTargets = $false
      try {
        $processRecord = Get-CimInstance Win32_Process `
          -Filter "ProcessId = $ownerProcessId" -ErrorAction Stop
        $processView = Get-Process -Id $ownerProcessId -ErrorAction Stop
        $commandLine = [string]$processRecord.CommandLine
        $ownedByCanonicalRoot = [regex]::IsMatch(
          $commandLine,$rootBoundaryPattern
        )
        $startedAfterTargets =
          $processView.StartTime.ToUniversalTime() -ge $targetLatestWriteUtc
      } catch {
        $ownedByCanonicalRoot = $false
        $startedAfterTargets = $false
      }
      [pscustomobject]@{
        ownedByCanonicalRoot = $ownedByCanonicalRoot
        startedAfterTargets = $startedAfterTargets
      }
    }
  )
  $trustedExistingRuntime =
    $ownerProof.Count -gt 0 -and
    @($ownerProof | Where-Object {
      -not $_.ownedByCanonicalRoot -or -not $_.startedAfterTargets
    }).Count -eq 0 -and
    @($listeners | Where-Object LocalPort -EQ 8080).Count -gt 0
  if (-not $trustedExistingRuntime) { throw 'port-owner-unproven' }

  try {
    $response = Invoke-WebRequest `
      -Uri 'http://127.0.0.1:8080/chat-ui' `
      -UseBasicParsing `
      -TimeoutSec 3
    if ([int]$response.StatusCode -ne 200) {
      throw 'trusted-runtime-not-ready'
    }
  } catch {
    throw 'trusted-runtime-not-ready'
  }
  try {
    $servedChatHash = Get-UrlByteSha256 `
      -Uri 'http://127.0.0.1:8080/js/chat.js' -TimeoutSeconds 3
  } catch {
    throw 'trusted-runtime-not-ready'
  }
  if ($servedChatHash -cne $currentChatHash) {
    throw 'trusted-runtime-stale-bytes'
  }
  $browserRuntimeReady = $true
} else {
  $receiptStream = $null
  try {
    $receiptStream = [IO.File]::Open(
      $bootReceiptPath,
      [IO.FileMode]::CreateNew,
      [IO.FileAccess]::Write,
      [IO.FileShare]::None
    )
  } catch [IO.IOException] {
    throw 'boot-receipt-collision'
  }

  $bootProcess = $null
  $bootProcessStartTimeUtc = $null
  $bootRecoveryPacket = $null
  $bootRecoveryPacketEmitted = $false
  $bootLaunchAttemptUtc = [DateTime]::UtcNow.ToString('o')
  $bootArgs = @(
    'bootRun',
    '--no-daemon',
    '--project-cache-dir',
    $projectCache
  )
  try {
    $bootProcess = Start-Process `
      -FilePath (Join-Path $root 'gradlew.bat') `
      -ArgumentList $bootArgs `
      -WorkingDirectory $root `
      -WindowStyle Hidden `
      -PassThru
    $bootStartedByThisRun = $true
    $bootProcessId = [int]$bootProcess.Id
    $bootProcessStartTimeUtc =
      $bootProcess.StartTime.ToUniversalTime().ToString('o')
    $bootRecoveryPacket = [ordered]@{
      schemaVersion = 'awx.chat_restore.boot_recovery.v1'
      owner = $receiptOwner
      canonicalRoot = $root
      processId = $bootProcessId
      processStartTimeUtc = $bootProcessStartTimeUtc
      launchAttemptTimeUtc = $bootLaunchAttemptUtc
      rawCommandLineStored = $false
    }
    $bootRecoveryPacket | ConvertTo-Json -Compress
    $bootRecoveryPacketEmitted = $true
    $receiptJson = [ordered]@{
      schemaVersion = $receiptSchema
      owner = $receiptOwner
      canonicalRoot = $root
      processId = $bootProcessId
      processStartTimeUtc = $bootProcessStartTimeUtc
    } | ConvertTo-Json -Compress
    $receiptBytes = [Text.UTF8Encoding]::new($false).GetBytes($receiptJson)
    $receiptStream.Write($receiptBytes,0,$receiptBytes.Length)
    $receiptStream.Flush($true)
  } catch {
    if ($null -ne $receiptStream) {
      $receiptStream.Dispose()
      $receiptStream = $null
    }
    $emergencyCleanupSucceeded = $true
    if ($null -ne $bootProcess) {
      if ([string]::IsNullOrWhiteSpace($bootProcessStartTimeUtc)) {
        try {
          $bootProcessStartTimeUtc = (
            Get-Process -Id $bootProcess.Id -ErrorAction Stop
          ).StartTime.ToUniversalTime().ToString('o')
        } catch {
          try {
            $processRecord = Get-CimInstance Win32_Process `
              -Filter "ProcessId = $($bootProcess.Id)" -ErrorAction Stop
            $bootProcessStartTimeUtc = (
              [DateTime]$processRecord.CreationDate
            ).ToUniversalTime().ToString('o')
          } catch {
            $emergencyCleanupSucceeded = $false
          }
        }
      }
      if ($null -eq $bootRecoveryPacket -and
          -not [string]::IsNullOrWhiteSpace($bootProcessStartTimeUtc)) {
        $bootRecoveryPacket = [ordered]@{
          schemaVersion = 'awx.chat_restore.boot_recovery.v1'
          owner = $receiptOwner
          canonicalRoot = $root
          processId = [int]$bootProcess.Id
          processStartTimeUtc = $bootProcessStartTimeUtc
          launchAttemptTimeUtc = $bootLaunchAttemptUtc
          rawCommandLineStored = $false
        }
      }
      if ($null -ne $bootRecoveryPacket -and
          -not $bootRecoveryPacketEmitted) {
        $bootRecoveryPacket | ConvertTo-Json -Compress
        $bootRecoveryPacketEmitted = $true
      }
      if ($emergencyCleanupSucceeded) {
        try {
          $emergencyCleanupSucceeded = Stop-OwnedProcessTreeVerified `
            -RootProcessId ([int]$bootProcess.Id) `
            -RootStartTimeUtc $bootProcessStartTimeUtc `
            -CanonicalRoot $root `
            -CanonicalRootBoundaryPattern $rootBoundaryPattern
        } catch {
          $emergencyCleanupSucceeded = $false
        }
      }
    }
    if ($emergencyCleanupSucceeded -and
        [StringComparer]::OrdinalIgnoreCase.Equals(
          [IO.Path]::GetDirectoryName($bootReceiptPath).TrimEnd('\'),$tempRoot
        ) -and [IO.Path]::GetFileName($bootReceiptPath) -ceq $receiptName) {
      [IO.File]::Delete($bootReceiptPath)
    }
    if (-not $emergencyCleanupSucceeded -or
        (Test-Path -LiteralPath $bootReceiptPath)) {
      if ($null -ne $bootRecoveryPacket) {
        [ordered]@{
          recovery = $bootRecoveryPacket
          reason = 'boot-start-or-receipt-write-failed-cleanup-needed'
          emergencyCleanupSucceeded = $emergencyCleanupSucceeded
          fixedReceiptMayBePartial = $true
        } | ConvertTo-Json -Depth 4 -Compress
      }
      throw 'boot-start-or-receipt-write-failed-cleanup-needed'
    }
    throw 'boot-start-or-receipt-write-failed'
  } finally {
    if ($null -ne $receiptStream) { $receiptStream.Dispose() }
  }

  $readinessClock = [Diagnostics.Stopwatch]::StartNew()
  $runtimeServedHashMismatch = $false
  while ($readinessClock.ElapsedMilliseconds -lt 60000) {
    $bootProcess.Refresh()
    if ($bootProcess.HasExited) { break }
    $remainingMs = 60000 - $readinessClock.ElapsedMilliseconds
    if ($remainingMs -lt 1000) { break }
    $requestTimeoutSec = [int][Math]::Min(
      3,
      [Math]::Floor($remainingMs / 1000)
    )
    try {
      $response = Invoke-WebRequest `
        -Uri 'http://127.0.0.1:8080/chat-ui' `
        -UseBasicParsing `
        -TimeoutSec $requestTimeoutSec
      if ([int]$response.StatusCode -eq 200) {
        $remainingMs = 60000 - $readinessClock.ElapsedMilliseconds
        if ($remainingMs -lt 1000) { break }
        $hashTimeoutSec = [int][Math]::Min(
          3,
          [Math]::Floor($remainingMs / 1000)
        )
        $servedChatHash = Get-UrlByteSha256 `
          -Uri 'http://127.0.0.1:8080/js/chat.js' `
          -TimeoutSeconds $hashTimeoutSec
        if ($servedChatHash -cne $currentChatHash) {
          $runtimeServedHashMismatch = $true
          break
        }
        $browserRuntimeReady = $true
        break
      }
    } catch {
    }
    $remainingMs = 60000 - $readinessClock.ElapsedMilliseconds
    if ($remainingMs -gt 0) {
      Start-Sleep -Milliseconds ([int][Math]::Min(500,$remainingMs))
    }
  }
  $readinessClock.Stop()
  [ordered]@{
    bootStartedByThisRun = $bootStartedByThisRun
    bootReceiptCreated = (Test-Path -LiteralPath $bootReceiptPath)
    browserRuntimeReady = $browserRuntimeReady
    servedChatHashMatchesPostimage =
      ($browserRuntimeReady -and -not $runtimeServedHashMismatch)
    processExited = $bootProcess.HasExited
    readinessElapsedMs = $readinessClock.ElapsedMilliseconds
  } | ConvertTo-Json
  if ($runtimeServedHashMismatch) {
    throw 'runtime-served-chat-hash-mismatch'
  }
}
~~~

If browserRuntimeReady is false, do not claim a Browser result. Set
disposition=evidence_needed and holdReason=localhost-not-ready, then run the
cleanup block below.

Use the browser skill on http://127.0.0.1:8080/chat-ui:

1. Create or open a normal session and record only its numeric session ID.
2. Send a benign message and wait for a terminal UI state.
3. Reload the page.
4. Confirm the same session ID and its own transcript restore; no different session ID appears in mode diagnostics.
5. Start one new stream and exercise Cancel once; confirm the UI remains interactive.
6. Record visible booleans and session IDs only. Do not store raw prompts, answers, cookies, headers, or provider payloads.

Browser happy-path proof supplements the Node wrong-ID contract; it does not replace it. If localhost cannot be started safely, keep final disposition=evidence_needed rather than fabricating browser success.

If the page is reachable but any same-ID restore, transcript, wrong-mode-ID,
stream, or Cancel assertion fails, set browserHappyPath=FAIL,
disposition=HOLD, and holdReason=browser-regression-observed.

If startup reported `bootStartedByThisRun=true`, run the following cleanup in a
fresh PowerShell call after Browser proof (and also after readiness or Browser
failure). It reconstructs ownership only from the fixed temp receipt, validates
every captured PID/start-time pair before stopping anything, stops descendants
before the root, and verifies that every captured owned identity is gone and no
captured PID owns ports 8080/8081. It deletes only the exact validated receipt
and only after successful cleanup. Existing trusted runtimes create no receipt
and must never be stopped by this block.

~~~powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$receiptName = 'awx-chat-restore-identity-20260806.boot.json'
$receiptOwner = 'codex-chat-restore-identity-20260806'
$receiptSchema = 'awx.chat_restore.boot_receipt.v1'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$bootReceiptPath = [IO.Path]::GetFullPath((Join-Path $tempRoot $receiptName))
$receiptParent = [IO.Path]::GetDirectoryName($bootReceiptPath).TrimEnd('\')
if (-not [StringComparer]::OrdinalIgnoreCase.Equals($tempRoot,$receiptParent) -or
    [IO.Path]::GetFileName($bootReceiptPath) -cne $receiptName) {
  throw 'boot-receipt-path-invalid'
}
if (-not (Test-Path -LiteralPath $bootReceiptPath -PathType Leaf)) {
  throw 'boot-receipt-missing'
}

try {
  $receipt = Get-Content -LiteralPath $bootReceiptPath -Raw -Encoding UTF8 |
    ConvertFrom-Json -ErrorAction Stop
} catch {
  throw 'boot-receipt-invalid'
}
if ($receipt.schemaVersion -cne $receiptSchema -or
    $receipt.owner -cne $receiptOwner -or
    -not [StringComparer]::OrdinalIgnoreCase.Equals(
      [string]$receipt.canonicalRoot,$root
    )) {
  throw 'boot-receipt-owner-mismatch'
}

$rootBoundaryPattern =
  '(?i)(?:^|[="''\s])' + [regex]::Escape($root) + '(?:[\\/"''\s]|$)'
function Stop-OwnedProcessTreeVerified {
  param(
    [int]$RootProcessId,
    [string]$RootStartTimeUtc,
    [string]$CanonicalRootBoundaryPattern
  )
  $rootStart = [DateTime]::Parse(
    $RootStartTimeUtc,
    [Globalization.CultureInfo]::InvariantCulture,
    [Globalization.DateTimeStyles]::RoundtripKind
  ).ToUniversalTime()
  $ownedProcessIds = [System.Collections.Generic.List[int]]::new()
  $seenProcessIds = [System.Collections.Generic.HashSet[int]]::new()
  $identityByProcessId = @{}
  $null = $ownedProcessIds.Add($RootProcessId)
  $null = $seenProcessIds.Add($RootProcessId)
  $identityByProcessId[$RootProcessId] = $RootStartTimeUtc
  $stopFailureCount = 0
  $stableEmptyRounds = 0

  for ($round = 0; $round -lt 6; $round++) {
    $addedThisRound = 0
    do {
      $addedInPass = 0
      $processRows = @(Get-CimInstance Win32_Process -ErrorAction Stop)
      foreach ($processRow in $processRows) {
        $parentProcessId = [int]$processRow.ParentProcessId
        $childProcessId = [int]$processRow.ProcessId
        if ($seenProcessIds.Contains($parentProcessId) -and
            $seenProcessIds.Add($childProcessId)) {
          $null = $ownedProcessIds.Add($childProcessId)
          $childProcess = Get-Process -Id $childProcessId -ErrorAction SilentlyContinue
          if ($childProcess) {
            $childStart = $childProcess.StartTime.ToUniversalTime()
            if ($childStart -lt $rootStart) { return $false }
            $identityByProcessId[$childProcessId] = $childStart.ToString('o')
          }
          $addedInPass++
          $addedThisRound++
        }
      }
    } while ($addedInPass -gt 0)

    foreach ($ownedProcessId in @($identityByProcessId.Keys)) {
      $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
      if ($ownedProcess -and
          $ownedProcess.StartTime.ToUniversalTime().ToString('o') -cne
            $identityByProcessId[$ownedProcessId]) {
        return $false
      }
    }
    for ($index = $ownedProcessIds.Count - 1; $index -ge 0; $index--) {
      $ownedProcessId = $ownedProcessIds[$index]
      if (-not $identityByProcessId.ContainsKey($ownedProcessId)) { continue }
      $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
      if (-not $ownedProcess) { continue }
      if ($ownedProcess.StartTime.ToUniversalTime().ToString('o') -cne
          $identityByProcessId[$ownedProcessId]) {
        return $false
      }
      try {
        Stop-Process -InputObject $ownedProcess -Force -ErrorAction Stop
      } catch {
        $stillOwned = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
        if ($stillOwned -and
            $stillOwned.StartTime.ToUniversalTime().ToString('o') -ceq
              $identityByProcessId[$ownedProcessId]) {
          $stopFailureCount++
        }
      }
    }
    Start-Sleep -Milliseconds 200
    $remainingOwnedCount = 0
    foreach ($ownedProcessId in @($identityByProcessId.Keys)) {
      $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
      if ($ownedProcess -and
          $ownedProcess.StartTime.ToUniversalTime().ToString('o') -ceq
            $identityByProcessId[$ownedProcessId]) {
        $remainingOwnedCount++
      }
    }
    if ($remainingOwnedCount -eq 0 -and $addedThisRound -eq 0) {
      $stableEmptyRounds++
      if ($stableEmptyRounds -ge 2) { break }
    } else {
      $stableEmptyRounds = 0
    }
  }

  $remainingOwnedCount = 0
  foreach ($ownedProcessId in @($identityByProcessId.Keys)) {
    $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
    if ($ownedProcess -and
        $ownedProcess.StartTime.ToUniversalTime().ToString('o') -ceq
          $identityByProcessId[$ownedProcessId]) {
      $remainingOwnedCount++
    }
  }
  $freshOwnedListenerCount = @(
    Get-NetTCPConnection -State Listen -LocalPort 8080,8081 `
      -ErrorAction SilentlyContinue | ForEach-Object {
        $listenerProcessId = [int]$_.OwningProcess
        $listenerProcess = Get-Process -Id $listenerProcessId `
          -ErrorAction SilentlyContinue
        if ($listenerProcess -and
            $listenerProcess.StartTime.ToUniversalTime() -ge $rootStart) {
          $listenerRecord = Get-CimInstance Win32_Process `
            -Filter "ProcessId = $listenerProcessId" -ErrorAction SilentlyContinue
          if ($listenerRecord -and [regex]::IsMatch(
                [string]$listenerRecord.CommandLine,
                $CanonicalRootBoundaryPattern
              )) {
            $_
          }
        }
      }
  ).Count
  return (
    $stopFailureCount -eq 0 -and
    $remainingOwnedCount -eq 0 -and
    $freshOwnedListenerCount -eq 0 -and
    $stableEmptyRounds -ge 2
  )
}

$cleanupSucceeded = Stop-OwnedProcessTreeVerified `
  -RootProcessId ([int]$receipt.processId) `
  -RootStartTimeUtc ([string]$receipt.processStartTimeUtc) `
  -CanonicalRootBoundaryPattern $rootBoundaryPattern
if (-not $cleanupSucceeded) { throw 'boot-process-cleanup-failed' }

[IO.File]::Delete($bootReceiptPath)
if (Test-Path -LiteralPath $bootReceiptPath) {
  throw 'boot-receipt-cleanup-failed'
}
[ordered]@{
  processTreeCleanupVerified = $true
  bootReceiptDeleted = $true
} | ConvertTo-Json
~~~

- [ ] **Step 3: Run a count-only secret scan and final target audit**

Run:

~~~powershell
$targets = @(
  'main/resources/static/js/chat.js',
  'scripts/chat_ui_stream_contract_tests.js'
)
$secretPattern = @'
(?im)(api[_-]?key|client[_-]?secret|password)\s*[:=]\s*["'][A-Za-z0-9+/=_-]{12,}["']|authorization\s*:\s*["']Bearer\s+[A-Za-z0-9._-]{12,}["']|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]+PRIVATE KEY-----|sk-[A-Za-z0-9_-]{16,}|sb_(?:secret|publishable)_[A-Za-z0-9._-]{10,}|sbp_[A-Za-z0-9_-]{10,}
'@
$secretCount = 0
foreach ($target in $targets) {
  $text = Get-Content -LiteralPath $target -Raw -Encoding UTF8
  $secretCount += [regex]::Matches($text, $secretPattern).Count
}

$allowedTargetPaths = @(
  'main/resources/static/js/chat.js',
  'scripts/chat_ui_stream_contract_tests.js'
)
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$auditReceiptName = 'awx-chat-restore-identity-20260806.audit.json'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$auditReceiptPath = [IO.Path]::GetFullPath(
  (Join-Path $tempRoot $auditReceiptName)
)
if (-not [StringComparer]::OrdinalIgnoreCase.Equals(
      [IO.Path]::GetDirectoryName($auditReceiptPath).TrimEnd('\'),$tempRoot
    ) -or [IO.Path]::GetFileName($auditReceiptPath) -cne $auditReceiptName) {
  throw 'audit-receipt-path-invalid'
}
try {
  $auditReceipt = Get-Content -LiteralPath $auditReceiptPath -Raw -Encoding UTF8 |
    ConvertFrom-Json -ErrorAction Stop
} catch {
  throw 'audit-receipt-invalid'
}
if ($auditReceipt.schemaVersion -cne 'awx.chat_restore.audit_receipt.v1' -or
    $auditReceipt.owner -cne 'codex-chat-restore-identity-20260806' -or
    -not [StringComparer]::OrdinalIgnoreCase.Equals(
      [string]$auditReceipt.canonicalRoot,$root
    )) {
  throw 'audit-receipt-owner-mismatch'
}
$baselineHead = [string]$auditReceipt.baselineHead
$preimageChatHash = [string]@(
  $auditReceipt.targetHashes |
    Where-Object path -CEQ 'main/resources/static/js/chat.js'
)[0].hash
$preimageFixtureHash = [string]@(
  $auditReceipt.targetHashes |
    Where-Object path -CEQ 'scripts/chat_ui_stream_contract_tests.js'
)[0].hash

function Get-PorcelainZEntries {
  param([string]$RepositoryRoot)
  $startInfo = [Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = 'git'
  $startInfo.Arguments =
    '-c core.quotepath=false status --porcelain=v1 -z --untracked-files=all'
  $startInfo.WorkingDirectory = $RepositoryRoot
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $startInfo
  try {
    if (-not $process.Start()) { throw 'git-status-start-failed' }
    $rawStatus = $process.StandardOutput.ReadToEnd()
    $null = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'git-status-failed' }
  } finally {
    $process.Dispose()
  }
  $records = @(
    $rawStatus.Split(
      [char[]]@([char]0),[StringSplitOptions]::RemoveEmptyEntries
    )
  )
  $entries = [System.Collections.Generic.List[object]]::new()
  for ($index = 0; $index -lt $records.Count; $index++) {
    $record = [string]$records[$index]
    if ($record.Length -lt 4) { throw 'git-status-record-invalid' }
    $status = $record.Substring(0,2)
    $paths = [System.Collections.Generic.List[string]]::new()
    $paths.Add($record.Substring(3).Replace('\','/'))
    if ($status.IndexOf('R') -ge 0 -or $status.IndexOf('C') -ge 0) {
      $index++
      if ($index -ge $records.Count) { throw 'git-status-rename-pair-missing' }
      $paths.Add(([string]$records[$index]).Replace('\','/'))
    }
    $entries.Add([pscustomobject]@{ status = $status; paths = $paths.ToArray() })
  }
  return $entries.ToArray()
}
function Get-GitEntrySignature {
  param([object]$Entry)
  return [ordered]@{
    status = [string]$Entry.status
    paths = @($Entry.paths | ForEach-Object { [string]$_ })
  } | ConvertTo-Json -Compress
}

$finalHead = git rev-parse HEAD
$finalGitEntries = @(Get-PorcelainZEntries -RepositoryRoot $root)
$baselineSignatures = @(
  $auditReceipt.baselineGitEntries |
    ForEach-Object { Get-GitEntrySignature -Entry $_ }
)
$finalSignatures = @(
  $finalGitEntries | ForEach-Object { Get-GitEntrySignature -Entry $_ }
)
$statusDelta = @(
  Compare-Object `
    -ReferenceObject $baselineSignatures `
    -DifferenceObject $finalSignatures
)
$outOfScopeStatusDelta = @(
  $statusDelta | Where-Object {
    try {
      $deltaEntry = [string]$_.InputObject | ConvertFrom-Json -ErrorAction Stop
      $deltaPaths = @($deltaEntry.paths | ForEach-Object { [string]$_ })
      $deltaPaths.Count -eq 0 -or @(
        $deltaPaths | Where-Object { $allowedTargetPaths -notcontains $_ }
      ).Count -gt 0
    } catch {
      $true
    }
  }
)
$postHashes = @(
  $targets | ForEach-Object {
    [pscustomobject]@{
      path = $_
      hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash
    }
  }
)
$postimageChatHash = [string]$postHashes[0].hash
$postimageFixtureHash = [string]$postHashes[1].hash
$sourceDiffCreated = $postimageChatHash -cne $preimageChatHash
$testDiffCreated = $postimageFixtureHash -cne $preimageFixtureHash
$targetDeletedCount = @(
  $targets | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) }
).Count
$commitCreated = $finalHead -cne $baselineHead

$final = [ordered]@{
  secretPatternCount = $secretCount
  targetHashes = $postHashes
  targetStatus = @(git status --short -- $targets)
  fullStatusEntryCount = $finalGitEntries.Count
  outOfScopeStatusDeltaCount = $outOfScopeStatusDelta.Count
  sourceDiffCreated = $sourceDiffCreated
  testDiffCreated = $testDiffCreated
  targetDeletedCount = $targetDeletedCount
  stagedCount = @((git diff --cached --name-only)).Count
  indexLock = (Test-Path -LiteralPath '.git\index.lock')
  branch = (git branch --show-current)
  baselineHead = $baselineHead
  finalHead = $finalHead
  commitCreated = $commitCreated
  auditReceiptDeleted = $false
}
$auditReceiptJsonForDeletion = $auditReceipt | ConvertTo-Json -Depth 8 -Compress
if ([string]::IsNullOrWhiteSpace($auditReceiptJsonForDeletion)) {
  throw 'audit-receipt-invalid'
}
[IO.File]::Delete($auditReceiptPath)
if (Test-Path -LiteralPath $auditReceiptPath) {
  throw 'audit-receipt-cleanup-failed'
}
$final.auditReceiptDeleted = $true
$final | ConvertTo-Json -Depth 5
~~~

Required: secretPatternCount=0, stagedCount=0, indexLock=false,
targetDeletedCount=0, outOfScopeStatusDeltaCount=0, and
commitCreated=false, auditReceiptDeleted=true. Review the exact target diff or untracked content without
printing secrets. For verified_no_patch_needed, both diff booleans are false.
For test_contract_added_and_verified, sourceDiffCreated=false and
testDiffCreated=true. For patched_and_verified, sourceDiffCreated=true.

- [ ] **Step 4: Release the lease if Task 2 acquired it**

Run in a finally path:

~~~powershell
$leaseArgs = @{
  Action = 'end'
  Role = 'desktop'
  Root = 'C:\AbandonWare\demo-1\demo-1\src'
  Topic = 'chat-restore-identity-postprocess'
  OwnerId = 'codex-chat-restore-identity-20260806'
}
& '.\__patch_drop__\source_edit_session.ps1' @leaseArgs
~~~

Expected: source-edit-locks=0 or already-absent for a no-op path.

- [ ] **Step 5: Emit the fixed completion report**

Use the actual JSON emitted by the self-contained verification, Browser, and
final-audit blocks plus the recorded command exits. Emit every key listed in
Section 14 of the standalone directive. Do not execute a report snippet that
assumes PowerShell variables survived another shell or Browser-tool call, and
do not leave variable names or choice lists in the final values.

Allowed disposition values are patched_and_verified,
test_contract_added_and_verified, verified_no_patch_needed, HOLD, and
evidence_needed. Test values are PASS, FAIL, or not_run.
browserHappyPath is PASS, FAIL, or evidence_needed. Set holdReason and
nextSingleProof to none only when no blocker remains; otherwise use one fixed
reason code and one exact command.

Do not claim patched_and_verified unless a source diff was actually applied
and every required verification passed. Do not claim
test_contract_added_and_verified unless only the missing fixture changed and
the existing source guard plus the full ladder passed. Do not claim
verified_no_patch_needed unless neither target changed and the live guard,
behavioral fixture, stable hashes, and full ladder prove the contract. If
Browser proof alone is unavailable, report evidence_needed with the exact
localhost/port action. If Browser executes and observes a regression, report
browserHappyPath=FAIL and HOLD browser-regression-observed.

- [ ] **Step 6: Remove the audit receipt on every early exit**

The successful final-audit block already deletes it. If any earlier HOLD,
evidence_needed, or verification failure exits before that block, run this
self-contained cleanup only after copying the receipt's bounded hashes, counts,
and reason code into the report:

~~~powershell
$root = 'C:\AbandonWare\demo-1\demo-1\src'
$receiptName = 'awx-chat-restore-identity-20260806.audit.json'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$receiptPath = [IO.Path]::GetFullPath((Join-Path $tempRoot $receiptName))
if (-not [StringComparer]::OrdinalIgnoreCase.Equals(
      [IO.Path]::GetDirectoryName($receiptPath).TrimEnd('\'),$tempRoot
    ) -or [IO.Path]::GetFileName($receiptPath) -cne $receiptName) {
  throw 'audit-receipt-path-invalid'
}
if (Test-Path -LiteralPath $receiptPath -PathType Leaf) {
  try {
    $receipt = Get-Content -LiteralPath $receiptPath -Raw -Encoding UTF8 |
      ConvertFrom-Json -ErrorAction Stop
  } catch {
    throw 'audit-receipt-invalid'
  }
  if ($receipt.schemaVersion -cne 'awx.chat_restore.audit_receipt.v1' -or
      $receipt.owner -cne 'codex-chat-restore-identity-20260806' -or
      -not [StringComparer]::OrdinalIgnoreCase.Equals(
        [string]$receipt.canonicalRoot,$root
      )) {
    throw 'audit-receipt-owner-mismatch'
  }
  [IO.File]::Delete($receiptPath)
  if (Test-Path -LiteralPath $receiptPath) {
    throw 'audit-receipt-cleanup-failed'
  }
}
~~~

## HOLD Follow-Ups Outside This Plan

The following remain separate and unauthorized:

- duplicated corrupted missing-session response text in ChatApiController;
- terminal session-list refresh coalescing that may swallow a final follow-up;
- inaccessible silent failure feedback in interactive session selection.

Do not use these candidates to widen either active target or the current lease.
