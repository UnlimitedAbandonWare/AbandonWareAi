# Desktop–Notebook Context Markdown Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Desktop의 Java·Windows·하드웨어·repository 정보와 live MariaDB 메타데이터를 비밀정보 없이 UTF-8 Markdown으로 발행해 Notebook이 canonical Y:\ 공유 루트에서 읽게 한다.

**Architecture:** PowerShell wrapper와 focused module이 bounded Desktop probe, redaction, Markdown rendering, atomic publication을 소유한다. 별도 Java 17 source-file helper는 기존 Gradle runtimeClasspath의 MySQL Connector/J를 사용해 current catalog metadata만 read-only transaction으로 조회하고 bounded JSON을 PowerShell에 돌려준다.

**Tech Stack:** Windows PowerShell 5.1/PowerShell 7 compatible script, Java 17 source-file mode, JDBC DatabaseMetaData, existing com.mysql:mysql-connector-j runtime dependency, Gradle wrapper dependency resolution, UTF-8 Markdown, SHA-256.

**Spec:** docs/superpowers/specs/2026-08-19-desktop-notebook-context-snapshot-design.md

## Global Constraints

- Desktop canonical root is C:\AbandonWare\demo-1\demo-1\src; Notebook read path is Y:\data\agent-handoff\desktop-context\latest.md.
- Do not add an SMB server, watcher, HTTP endpoint, background broker, Scheduled Task, production dependency, Spring component, or application-source change.
- Do not modify build.gradle.kts, settings.gradle*, scripts/awx_mcp_toolbox.py, main/resources/mcp/awx-control-tower-tools.json, AGENTS.md, DB/DDL, credentials, or existing user changes.
- DatabaseMode accepts Metadata or Skip only. No arbitrary SQL, raw table-row export, DDL, DML, procedure, outfile, backup, or restore path.
- Never publish password, token, JDBC URL, DB username, environment dump, process command line, UNC mapping, application row, full error message, SQL text, raw stdout, or stack trace.
- Exact JAVA_HOME/java.home, Java/Gradle/OS/GPU versions, canonical repository path, canonicalWorkspace=Y:\, DB metadata names and counts are allowed.
- DB failure is lane-local and produces partial; secret, integrity, or publication failure preserves the previous latest.md.
- Every external process has a bounded timeout and finally cleanup.
- Do not commit, push, deploy, install packages, or persist environment variables; the user explicitly requested implementation without commits.

## File Map

Create scripts/desktop_notebook_context_snapshot.ps1 as the single user command.

Create scripts/modules/DesktopNotebookContextSnapshot.psm1 for path validation,
host probes, DB configuration resolution, connector resolution, Java invocation,
redaction, rendering, and atomic publication.

Create scripts/DesktopMariaDbMetadataSnapshot.java for one read-only MariaDB
metadata session and a dependency-free bounded JSON serializer.

Create scripts/desktop_notebook_context_snapshot_contract_tests.ps1 as the
offline RED/GREEN harness for the module, wrapper, and Java source contract.

Create data/agent-handoff/desktop-context/README.md for the Desktop refresh and
Notebook read/hash commands.

Create data/agent-handoff/desktop-context/.gitignore to retain README.md and
.gitignore while ignoring latest.md, latest.sha256.txt, and temporary files.

Runtime-only files are data/agent-handoff/desktop-context/latest.md and
data/agent-handoff/desktop-context/latest.sha256.txt.

---

### Task 1: Safe Markdown renderer and atomic publisher

**Files:**
- Create: scripts/desktop_notebook_context_snapshot_contract_tests.ps1
- Create: scripts/modules/DesktopNotebookContextSnapshot.psm1

**Interfaces:**
- Produces: Resolve-AwxSnapshotOutputDirectory([string] Root, [string] RelativePath) -> DirectoryInfo.
- Produces: Test-AwxSnapshotText([string] Text) -> object with Safe, HitCount, Reasons.
- Produces: ConvertTo-AwxDesktopContextMarkdown([hashtable] Snapshot) -> string.
- Produces: Publish-AwxDesktopContextSnapshot([string] Markdown, [DirectoryInfo] OutputDirectory) -> object with ReportPath, HashPath, Sha256, Decision.
- Consumes later: Invoke-AwxDesktopNotebookContextSnapshot calls all four functions.

- [ ] **Step 1: Write the failing path, redaction, rendering, and publication contract**

Add a temporary-root harness to scripts/desktop_notebook_context_snapshot_contract_tests.ps1. The first fixture must import the not-yet-created module and assert these exact behaviors:

~~~powershell
$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Module = Join-Path $PSScriptRoot 'modules\DesktopNotebookContextSnapshot.psm1'
Import-Module $Module -Force

$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('awx-context-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $TempRoot | Out-Null
try {
    $resolved = Resolve-AwxSnapshotOutputDirectory -Root $TempRoot -RelativePath 'data/agent-handoff/desktop-context'
    Assert-Equal $resolved.FullName (Join-Path $TempRoot 'data\agent-handoff\desktop-context')
    Assert-Throws { Resolve-AwxSnapshotOutputDirectory -Root $TempRoot -RelativePath '..\escape' } 'snapshot-output-outside-handoff'
    Assert-Throws { Resolve-AwxSnapshotOutputDirectory -Root $TempRoot -RelativePath '\\server\share' } 'snapshot-output-unc-forbidden'
    Assert-Throws { Resolve-AwxSnapshotOutputDirectory -Root $TempRoot -RelativePath 'data/agent-handoff/desktop-context:ads' } 'snapshot-output-ads-forbidden'

    $outside = Join-Path ([System.IO.Path]::GetTempPath()) ('awx-outside-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $outside | Out-Null
    $junction = Join-Path $TempRoot 'data\agent-handoff\junction'
    New-Item -ItemType Directory -Path (Split-Path -Parent $junction) -Force | Out-Null
    New-Item -ItemType Junction -Path $junction -Target $outside | Out-Null
    Assert-Throws { Resolve-AwxSnapshotOutputDirectory -Root $TempRoot -RelativePath 'data/agent-handoff/junction/desktop-context' } 'snapshot-output-reparse-forbidden'

    $unsafe = Test-AwxSnapshotText -Text 'password=visible-value'
    Assert-False $unsafe.Safe 'secret text must fail'

    $snapshot = [ordered]@{
        generatedAt = '2026-08-19T00:00:00+09:00'
        snapshotDecision = 'partial'
        java = [ordered]@{ status='ok'; javaHome='C:\jdk\jdk-17.0.13'; version='17.0.13' }
        build = [ordered]@{ status='ok'; gradleVersion='8.x' }
        windows = [ordered]@{ status='ok'; os='Windows'; build='test' }
        hardware = [ordered]@{ status='ok'; cpu='fixture'; logicalProcessors=1 }
        services = @()
        repository = [ordered]@{ status='ok'; branch='fixture'; head='0000000'; dirtyCount=0 }
        smb = [ordered]@{ canonicalWorkspace='Y:\'; backingShareIdentityVerified=$false; backingShareIdentityReason='evidence-needed' }
        mariadb = [ordered]@{ decision='evidence_needed'; rawDbRowStored=$false; jdbcUrlStored=$false }
        agentDb = [ordered]@{ decision='not_observed' }
        evidenceNeeded = @('fixture')
    }
    $markdown = ConvertTo-AwxDesktopContextMarkdown -Snapshot $snapshot
    Assert-Contains $markdown '# Desktop Context Snapshot'
    Assert-Contains $markdown '## MariaDB Metadata'
    Assert-Contains $markdown 'JAVA_HOME'

    $published = Publish-AwxDesktopContextSnapshot -Markdown $markdown -OutputDirectory $resolved
    Assert-True (Test-Path -LiteralPath $published.ReportPath) 'latest.md exists'
    Assert-Equal (Get-FileHash -Algorithm SHA256 -LiteralPath $published.ReportPath).Hash $published.Sha256
} finally {
    Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
    if ($outside) {
        Remove-Item -LiteralPath $outside -Recurse -Force -ErrorAction SilentlyContinue
    }
}
~~~

The harness defines Assert-True, Assert-False, Assert-Equal, Assert-Contains,
and Assert-Throws before the fixture. Assert-Throws compares the thrown
exception message with the required reason code.

- [ ] **Step 2: Run the contract and confirm RED**

Run:

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_notebook_context_snapshot_contract_tests.ps1
~~~

Expected: FAIL because DesktopNotebookContextSnapshot.psm1 or exported functions do not exist.

- [ ] **Step 3: Implement the minimal safe module core**

Create scripts/modules/DesktopNotebookContextSnapshot.psm1 with strict mode,
explicit exports, and these signatures:

~~~powershell
Set-StrictMode -Version Latest

function Resolve-AwxSnapshotOutputDirectory {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$RelativePath
    )
    if ([System.IO.Path]::IsPathRooted($RelativePath) -or $RelativePath -match '^[\\/]{2}') {
        throw 'snapshot-output-unc-forbidden'
    }
    if ($RelativePath.Contains(':')) {
        throw 'snapshot-output-ads-forbidden'
    }
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\','/')
    $candidate = [System.IO.Path]::GetFullPath((Join-Path $rootFull $RelativePath))
    $handoff = [System.IO.Path]::GetFullPath((Join-Path $rootFull 'data\agent-handoff')).TrimEnd('\','/')
    if (-not $candidate.StartsWith($handoff + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'snapshot-output-outside-handoff'
    }
    $rootItem = Get-Item -LiteralPath $rootFull
    if (($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'snapshot-output-reparse-forbidden'
    }
    $cursorPath = $rootFull
    $relativeCanonical = $candidate.Substring($rootFull.Length).TrimStart([char[]]@('\','/'))
    foreach ($segment in @($relativeCanonical -split '[\\/]')) {
        if ([string]::IsNullOrWhiteSpace($segment)) { continue }
        $cursorPath = Join-Path $cursorPath $segment
        if (Test-Path -LiteralPath $cursorPath) {
            $item = Get-Item -LiteralPath $cursorPath
        } else {
            $item = New-Item -ItemType Directory -Path $cursorPath
        }
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'snapshot-output-reparse-forbidden'
        }
    }
    Get-Item -LiteralPath $candidate
}

function Test-AwxSnapshotText {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Text)
    $patterns = @(
        '(?i)(password|passwd|pwd)\s*[:=]\s*[^\s|]+',
        '(?i)(api[-_]?key|token|authorization|cookie|client[-_]?secret)\s*[:=]\s*[^\s|]+',
        '(?i)jdbc:(mysql|mariadb):',
        '(?i)bearer\s+[a-z0-9._~+/-]+'
    )
    $reasons = @($patterns | Where-Object { [regex]::IsMatch($Text, $_) })
    [pscustomobject]@{ Safe = $reasons.Count -eq 0; HitCount = $reasons.Count; Reasons = $reasons }
}
~~~

ConvertTo-AwxDesktopContextMarkdown emits the nine headings in the spec in
fixed order and uses a local ConvertTo-AwxMarkdownScalar that removes CR/LF,
pipe, control characters, and values longer than 500 characters.

Publish-AwxDesktopContextSnapshot writes UTF-8 without BOM to a GUID temporary
file in OutputDirectory, validates 1 MiB maximum size and every required heading,
runs Test-AwxSnapshotText, calculates SHA-256, then uses File.Move for first
publication or File.Replace with a GUID backup path for replacement. It writes
latest.sha256.txt only after latest.md is replaced and deletes temporary/backup
files in finally.

- [ ] **Step 4: Run the contract and confirm GREEN**

Run the Task 1 command again.

Expected: PASS for path containment, UNC rejection, secret rejection, headings,
UTF-8 report publication, and matching SHA-256.

- [ ] **Step 5: Inspect the Task 1 diff without committing**

Run:

~~~powershell
git status --short -- scripts\desktop_notebook_context_snapshot_contract_tests.ps1 scripts\modules\DesktopNotebookContextSnapshot.psm1
git diff --check -- scripts\desktop_notebook_context_snapshot_contract_tests.ps1 scripts\modules\DesktopNotebookContextSnapshot.psm1
~~~

Expected: only the two declared new files; no commit.

---

### Task 2: Read-only MariaDB metadata helper

**Files:**
- Create: scripts/DesktopMariaDbMetadataSnapshot.java
- Modify: scripts/desktop_notebook_context_snapshot_contract_tests.ps1

**Interfaces:**
- Consumes: child-only AWX_SNAPSHOT_DB_URL, AWX_SNAPSHOT_DB_USERNAME, AWX_SNAPSHOT_DB_PASSWORD, AWX_SNAPSHOT_DB_DRIVER.
- Produces: one compact JSON object on stdout with schemaVersion=awx.desktop.mariadb-metadata.v1.
- Produces fields: decision, capturedAt, readOnlySession, currentCatalog, server, summary, schemas, tables, columns, indexes, views, queryIds, metadataHash, rawDbRowStored=false, jdbcUrlStored=false, evidenceNeeded.
- Exit codes: 0 connected/self-test pass; 2 input/config failure; 3 connect/auth failure; 4 query/limit failure; 5 internal contract failure.

- [ ] **Step 1: Add failing Java helper contract assertions**

Append assertions that:

~~~powershell
$JavaSource = Join-Path $PSScriptRoot 'DesktopMariaDbMetadataSnapshot.java'
Assert-True (Test-Path -LiteralPath $JavaSource) 'Java helper exists'
$sourceText = Get-Content -LiteralPath $JavaSource -Raw
foreach ($forbidden in @('executeUpdate(', 'addBatch(', 'prepareCall(', 'INTO OUTFILE', 'LOAD DATA')) {
    Assert-False $sourceText.Contains($forbidden) "forbidden helper token: $forbidden"
}
$selfTestRaw = & java $JavaSource --self-test 2>&1
Assert-Equal $LASTEXITCODE 0 'Java self-test exit'
$selfTest = $selfTestRaw | ConvertFrom-Json
Assert-Equal $selfTest.schemaVersion 'awx.desktop.mariadb-metadata.v1'
Assert-Equal $selfTest.decision 'self_test_passed'
Assert-True $selfTest.readOnlySession 'self-test read-only flag'
Assert-False $selfTest.rawDbRowStored 'self-test raw row flag'
Assert-False $selfTest.jdbcUrlStored 'self-test JDBC URL flag'
~~~

- [ ] **Step 2: Run and confirm RED**

Run the contract test.

Expected: FAIL because scripts/DesktopMariaDbMetadataSnapshot.java does not exist.

- [ ] **Step 3: Implement source-file helper and JSON serializer**

Create public final class DesktopMariaDbMetadataSnapshot using only JDK APIs at
compile time: java.sql, java.time, java.security, java.nio.charset, and
java.util. Do not import Spring, Jackson, or MySQL implementation classes.

The entry point follows this shape:

~~~java
public static void main(String[] args) {
    if (args.length == 1 && "--self-test".equals(args[0])) {
        Map<String, Object> out = syntheticSelfTest();
        System.out.print(Json.write(out));
        return;
    }
    Config config = Config.fromEnvironment();
    Result result = collect(config);
    System.out.print(Json.write(result.toMap()));
    if (!"connected".equals(result.decision())) {
        System.exit(result.exitCode());
    }
}
~~~

Config.fromEnvironment requires all four child variables except driver, whose
safe default is com.mysql.cj.jdbc.Driver. It never includes a value in an
exception. Missing input throws ConfigFailure with db-credentials-unresolved.

collect loads the driver by class name, connects with java.util.Properties,
calls connection.setReadOnly(true), connection.setAutoCommit(false), executes
the exact fixed statement SET SESSION TRANSACTION READ ONLY, sets query timeout
and max rows, and filters all metadata to connection.getCatalog().

MySQL/MariaDB treats databases as JDBC catalogs: synthesize the exported schema
list from DatabaseMetaData.getCatalogs filtered to connection.getCatalog(), and
pass that catalog explicitly to getTables/getColumns/getIndexInfo. Use fixed
prepared information_schema queries only where JDBC metadata lacks the required
view, size, estimate, or fingerprint field. Record only these query IDs:

~~~text
session_read_only
server_variables
server_status
schemas
tables
columns
indexes
views
metadata_fingerprint_start
metadata_fingerprint_end
~~~

Cap each collection and the total at MaxMetadataRows from environment
AWX_SNAPSHOT_DB_MAX_ROWS, clamped to 1..10000. Use
AWX_SNAPSHOT_DB_TIMEOUT_SECONDS, clamped to 1..30. Roll back and close in
finally. Return only reason codes and exception simple class names.

Implement a small Json.write(Object) supporting null, String, Number, Boolean,
Map and Iterable. Json.escape encodes quote, backslash, CR/LF/tab and remaining
control characters. It rejects unsupported types.

Self-test renders synthetic metadata, reparses only through structural string
checks inside Java, asserts forbidden values are absent, and emits
self_test_passed.

- [ ] **Step 4: Run helper self-test and PowerShell contract**

Run:

~~~powershell
java .\scripts\DesktopMariaDbMetadataSnapshot.java --self-test
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_notebook_context_snapshot_contract_tests.ps1
~~~

Expected: JSON decision self_test_passed and all contract assertions PASS.

- [ ] **Step 5: Inspect the Task 2 diff without committing**

Run git status and git diff --check for the Java helper and contract test.

Expected: declared files only; no commit.

---

### Task 3: Desktop probes, DB configuration, connector resolution, and wrapper

**Files:**
- Create: scripts/desktop_notebook_context_snapshot.ps1
- Modify: scripts/modules/DesktopNotebookContextSnapshot.psm1
- Modify: scripts/desktop_notebook_context_snapshot_contract_tests.ps1

**Interfaces:**
- Produces: Get-AwxJavaFacts, Get-AwxWindowsFacts, Get-AwxHardwareFacts, Get-AwxServiceFacts, Get-AwxRepositoryFacts, Get-AwxSmbFacts.
- Produces: Resolve-AwxDesktopDbSettings([string] RepoRoot, [string] ProfileConfig) -> private AwxDesktopDbSettings with hidden Url, Username, Password, Driver and public Resolved/Reason only.
- Produces: Resolve-AwxMysqlConnectorJar([string] RepoRoot, [int] TimeoutSeconds) -> absolute JAR path.
- Produces: Invoke-AwxMariaDbMetadataHelper(settings, jar, timeout, maxRows) -> safe parsed object.
- Produces: Get-AwxOllamaFacts and Get-AwxAgentDbFacts -> bounded optional runtime summaries.
- Produces: Invoke-AwxDesktopNotebookContextSnapshot(parameters) -> publication summary with no secret-bearing fields.

- [ ] **Step 1: Add failing fixture tests for configuration and orchestrator**

Within the existing temporary root, create a profile fixture:

~~~powershell
$profile = Join-Path $TempRoot 'main\resources\application-desktop-gpu-node.yml'
New-Item -ItemType Directory -Path (Split-Path -Parent $profile) -Force | Out-Null
@'
spring:
  datasource:
    url: ${DESKTOP_DB_URL:jdbc:mysql://127.0.0.1:3306/fixture}
    username: ${DESKTOP_DB_USERNAME:fixture_user}
    password: ${DESKTOP_DB_PASSWORD:fixture_password}
    driver-class-name: ${DESKTOP_DB_DRIVER:com.mysql.cj.jdbc.Driver}
'@ | Set-Content -LiteralPath $profile -Encoding UTF8

$settings = Resolve-AwxDesktopDbSettings -RepoRoot $TempRoot -ProfileConfig $profile
Assert-True $settings.Resolved 'fallback settings resolve'
Assert-Equal $settings.ToString() '[desktop-db-settings:redacted]' 'settings string redaction'
Assert-False (($settings | Select-Object * | ConvertTo-Json -Compress) -match 'fixture_password|jdbc:mysql') 'hidden settings do not serialize secrets'
~~~

Add a Skip-mode orchestrator fixture that passes injected probe results, avoids
real external processes, publishes latest.md, and asserts snapshotDecision is
partial only because DatabaseMode=Skip.

- [ ] **Step 2: Run and confirm RED**

Run the contract test.

Expected: FAIL on missing Resolve-AwxDesktopDbSettings and orchestrator functions.

- [ ] **Step 3: Implement bounded Desktop probes**

Each probe uses try/catch, Stopwatch, a hard timeout where a child process is
used, and returns ordered public fields only.

Get-AwxJavaFacts reads process JAVA_HOME and filtered java properties.
Get-AwxWindowsFacts uses Get-CimInstance for Win32_OperatingSystem and returns
caption/build/PowerShell version only.
Get-AwxHardwareFacts returns CPU model/logical count, total memory, fixed-drive
total/free bytes, and nvidia-smi name/driver/memory fields.
Get-AwxServiceFacts checks only ports 3306, 8080, 8081, 11434, 11435, 11436 and
returns bindingClass plus process name, never command line.
Get-AwxRepositoryFacts runs fixed git branch, rev-parse, status count,
index-lock, janitor inventory summary, and source-set count probes.
Get-AwxSmbFacts returns canonicalWorkspace=Y:\ and only the safe identity
boolean/reason if the existing verifier can produce them.
Get-AwxOllamaFacts uses the existing ValidateOnly seam or bounded localhost tags
metadata and never sends a generation request.
Get-AwxAgentDbFacts calls existing agent_db_snapshot only when 8080 or 8081 is
already listening; otherwise it returns local fallback plus
agent-db-runtime-unavailable without starting Spring.

- [ ] **Step 4: Implement private DB setting resolution**

Resolve-AwxDesktopDbSettings validates ProfileConfig is under RepoRoot
main/resources and parses only the four direct children of spring.datasource.
It accepts the exact ENV or ENV:fallback placeholder grammar, gives current
process environment precedence, rejects nested/unresolved expressions, and
returns an AwxDesktopDbSettings PowerShell class whose secret-bearing properties
are hidden and whose ToString method is [desktop-db-settings:redacted].

Do not return Password, URL, or Username from any public invocation summary.

- [ ] **Step 5: Implement temporary Gradle connector resolver**

Write a GUID .gradle init script under the OS temp directory. It registers
awxPrintMysqlConnectorPath after projects are evaluated, resolves root
runtimeClasspath, filters resolved artifacts by group=com.mysql and
name=mysql-connector-j, requires exactly one match, and prints only its absolute
JAR path.

Run gradlew.bat with:

~~~powershell
& $GradleWrapper '-I' $InitScript '-q' 'awxPrintMysqlConnectorPath' '--no-daemon' '--project-cache-dir' $ProjectCache
~~~

Capture output, accept only one existing .jar path ending in
mysql-connector-j-*.jar, and delete the init script in finally. Do not print the
full Gradle output in the report.

- [ ] **Step 6: Implement child-only Java invocation**

Use System.Diagnostics.ProcessStartInfo with UseShellExecute=false,
RedirectStandardOutput=true and RedirectStandardError=true. Set only the child
environment AWX_SNAPSHOT_DB_* variables through
ProcessStartInfo.EnvironmentVariables so Windows PowerShell 5.1 is supported,
then run:

~~~powershell
$Arguments = @('-cp', $ConnectorJar, $JavaSource)
~~~

Enforce the timeout, kill only this task-started process on timeout, parse
stdout as JSON, discard stderr, and return reason code plus exception type only.
Clear the ProcessStartInfo environment entries and private settings references
in finally.

- [ ] **Step 7: Create the thin wrapper**

scripts/desktop_notebook_context_snapshot.ps1 declares the five public
parameters from the spec, locates RepoRoot from PSScriptRoot, imports the module,
calls Invoke-AwxDesktopNotebookContextSnapshot, and prints one compact line:

~~~powershell
"decision=$($result.Decision) report=$($result.ReportRelativePath) sha256=$($result.Sha256) mariadb=$($result.MariaDbDecision) evidenceNeededCount=$($result.EvidenceNeededCount)"
~~~

It never prints private settings, raw probe output, or environment data.

- [ ] **Step 8: Run offline contracts**

Run the full PowerShell contract test.

Expected: all fixture, path, redaction, wrapper, Java self-test, Gradle init
shape, and atomic publication assertions PASS without connecting to MariaDB.

- [ ] **Step 9: Inspect the Task 3 diff without committing**

Run git status and diff checks on the wrapper, module, test, and helper.

Expected: no undeclared files or existing-file modifications; no commit.

---

### Task 4: Notebook handoff docs, live MariaDB snapshot, and completion proof

**Files:**
- Create: data/agent-handoff/desktop-context/README.md
- Create: data/agent-handoff/desktop-context/.gitignore
- Runtime create: data/agent-handoff/desktop-context/latest.md
- Runtime create: data/agent-handoff/desktop-context/latest.sha256.txt
- Modify if a focused test exposes a defect: only the four scripts from Tasks 1–3.

**Interfaces:**
- Consumes: scripts/desktop_notebook_context_snapshot.ps1.
- Produces: Desktop latest.md and matching SHA-256.
- Produces: exact Notebook Y:\ read/hash commands.
- Completion evidence: live mariadbDecision=connected, nonzero metadata counts, zero secret hits, stable read-only metadata hash, Desktop/Notebook SHA match.

- [ ] **Step 1: Add handoff README and local ignore contract**

README.md explains:

~~~powershell
# Desktop refresh
Set-Location -LiteralPath 'C:\AbandonWare\demo-1\demo-1\src'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_notebook_context_snapshot.ps1

# Notebook read
$Snapshot = 'Y:\data\agent-handoff\desktop-context\latest.md'
Get-Content -LiteralPath $Snapshot -Raw
Get-FileHash -Algorithm SHA256 -LiteralPath $Snapshot
~~~

It states that files older than 30 minutes must be refreshed on Desktop and that
Notebook must not write a refresh request or source file.

.gitignore contains:

~~~text
*
!.gitignore
!README.md
~~~

Append contract assertions that README includes both canonical paths and that
latest.md/latest.sha256.txt are ignored while README.md remains visible to Git.

- [ ] **Step 2: Run all offline tests**

Run:

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_notebook_context_snapshot_contract_tests.ps1
java .\scripts\DesktopMariaDbMetadataSnapshot.java --self-test
~~~

Expected: PASS and self_test_passed.

- [ ] **Step 3: Re-run live preflight**

Check branch, HEAD, target status, index lock, source lease, top-level PatchDrop,
Java 17, port 3306, and output directory boundary. Hold only a conflicting
target or unsafe publication lane.

- [ ] **Step 4: Run one live Desktop snapshot**

Run:

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_notebook_context_snapshot.ps1 -DatabaseMode Metadata
~~~

Expected: compact output with report path, SHA-256,
mariadb=connected, and decision complete or partial only for optional evidence.

- [ ] **Step 5: Verify report semantics and secret safety**

Parse latest.md and assert:

~~~powershell
$Report = '.\data\agent-handoff\desktop-context\latest.md'
$Text = Get-Content -LiteralPath $Report -Raw
if ($Text -notmatch 'mariadbDecision\s*[:|]\s*connected') { throw 'live-mariadb-proof-missing' }
if ($Text -notmatch 'columnCount\s*[:|]\s*[1-9][0-9]*') { throw 'live-column-proof-missing' }
if ($Text -notmatch 'rawSecretPatternHits\s*[:|]\s*0') { throw 'secret-scan-not-green' }
if ($Text -notmatch 'rawDbRowStored\s*[:|]\s*false') { throw 'raw-row-contract-missing' }
if ($Text -notmatch 'jdbcUrlStored\s*[:|]\s*false') { throw 'jdbc-redaction-contract-missing' }
~~~

Compare latest.sha256.txt with Get-FileHash. Confirm no helper Java process or
open task-started process remains.

- [ ] **Step 6: Verify DB non-mutation evidence**

Require report fields readOnlySession=true, queryIds exactly within the fixed
allowlist, and matching structuralMetadataFingerprintStart/End; the structural
fingerprint excludes row estimates, byte sizes, uptime, and other volatile
status. Re-run the snapshot once
and require the second run to preserve schema/table/column counts unless an
independent current DB writer is observed.

- [ ] **Step 7: Verify Notebook visibility when Y:\ is observable**

On Notebook run the README read/hash commands. Require bytes and SHA-256 to
match Desktop latest.md. If the current session cannot observe Notebook Y:\,
record exactly:

~~~text
evidence_needed: Notebook Y:\ snapshot read and SHA-256 match / verify with Get-Content and Get-FileHash from README.md
~~~

Do not claim Notebook visibility from Desktop file existence alone.

- [ ] **Step 8: Final scope and preservation audit**

Run:

~~~powershell
git status --short -- docs\superpowers\specs\2026-08-19-desktop-notebook-context-snapshot-design.md docs\superpowers\plans\2026-08-19-desktop-notebook-context-snapshot.md scripts\desktop_notebook_context_snapshot.ps1 scripts\modules\DesktopNotebookContextSnapshot.psm1 scripts\DesktopMariaDbMetadataSnapshot.java scripts\desktop_notebook_context_snapshot_contract_tests.ps1 data\agent-handoff\desktop-context
git diff --check
~~~

Confirm only declared artifacts changed, unrelated worktree entries remain
untouched, no commit exists, and no DB/application mutation occurred.
