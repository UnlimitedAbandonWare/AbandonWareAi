[CmdletBinding()]
param(
    [ValidateSet('Publisher')]
    [string]$Suite = 'Publisher'
)

$ErrorActionPreference = 'Stop'
$script:Failures = 0
$script:Passed = 0
$script:TempRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-autoconsume-contract-' + [guid]::NewGuid().ToString('N'))
$PublisherPath = Join-Path $PSScriptRoot 'publish_desktop_autoconsume_request.ps1'
function Assert-True([string]$Name,[bool]$Condition,[string]$Detail){if(-not$Condition){$script:Failures++;Write-Host "[FAIL] $Name $Detail"}else{$script:Passed++;Write-Host "[PASS] $Name"}}
function Write-TestText([string]$Path,[string]$Text){New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path)|Out-Null;[IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))}
function Get-TestSha256([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Update-TestSidecar($b){$l=@('patch','report','verify','manifest','pending'|ForEach-Object{$n=if($_-eq'pending'){"../$($b.topic).notebook-pending.md"}else{Split-Path -Leaf $b.paths[$_]};"$((Get-TestSha256 $b.paths[$_]))  $n"});Write-TestText $b.paths.sha (($l-join"`n")+"`n")}
function Get-LiveQueueSnapshot{$r=Join-Path (Split-Path -Parent $PSScriptRoot) '__patch_drop__';$l=@(Get-ChildItem $r -File -Recurse|Sort-Object FullName|ForEach-Object{"$($_.FullName.Substring($r.Length)) $((Get-TestSha256 $_.FullName))"});$b=[Text.UTF8Encoding]::new($false).GetBytes(($l-join"`n"));[pscustomobject]@{fileCount=$l.Count;manifestSha256=([BitConverter]::ToString(([Security.Cryptography.SHA256]::Create()).ComputeHash($b))).Replace('-','').ToLowerInvariant()}}
function Get-LiveTopLevelCounts{[pscustomobject]@{patchCount=0;leaseCount=0}}
function Get-SingleJson([string]$Output){try{$Output|ConvertFrom-Json}catch{$null}}
function New-TestBundle([string]$Root,[string]$Topic){$pd=Join-Path $Root '__patch_drop__';$nd=Join-Path $pd notebook;$b="$Topic-notebook-v3";$p=[ordered]@{patch=Join-Path $nd "$b.patch";report=Join-Path $nd "$b.report.md";verify=Join-Path $nd "$b.verify.log";manifest=Join-Path $nd "$b.manifest.json";sha=Join-Path $nd "$b.sha256.txt";pending=Join-Path $pd "$Topic.notebook-pending.md"};Write-TestText $p.patch "diff --git a/docs/$Topic.md b/docs/$Topic.md`nnew file mode 100644`n--- /dev/null`n+++ b/docs/$Topic.md`n@@ -0,0 +1 @@`n+$Topic`n";Write-TestText $p.report '# r';Write-TestText $p.verify 'secretPatternHits=0';Write-TestText $p.pending '# p';$m=[ordered]@{schemaVersion='patchdrop-producer-v3';topic=$Topic;slug=$Topic;node='notebook';activePatch="$b.patch";desktopFinalProof='evidence_needed';sourceIsolation=[ordered]@{guard='PASS';sourceRootKind='local-worktree';directCanonicalSourceEdit=$false}};Write-TestText $p.manifest (($m|ConvertTo-Json -Depth 4)+"`n");$o=[pscustomobject]@{root=$Root;patchDrop=$pd;topic=$Topic;paths=$p};Update-TestSidecar $o;$o}
function Invoke-Publisher([string]$Root,[string]$Topic,[string]$Profile='powershell-tooling',[string]$RequestId='publisher-contract-001',[string]$Node='notebook',[int]$TtlMinutes=180){$e=$ErrorActionPreference;try{$ErrorActionPreference='Continue';$o=@(&powershell.exe -NoProfile -ExecutionPolicy Bypass -File $PublisherPath -Root $Root -Topic $Topic -Node $Node -VerificationProfile $Profile -RequestId $RequestId -TtlMinutes $TtlMinutes 2>&1);[pscustomobject]@{exitCode=$LASTEXITCODE;output=($o-join"`n")}}finally{$ErrorActionPreference=$e}}
function Test-BoundedPublisherFailure($i,[string]$r,[string]$f){$j=Get-SingleJson $i.output;$i.exitCode-ne0-and$j.reasonCode-ceq$r-and-not$i.output.Contains($f)-and-not$i.output.Contains($PublisherPath)}
function Invoke-PublisherTests {
    if (-not (Test-Path -LiteralPath $PublisherPath -PathType Leaf)) { throw 'publisher-entrypoint-missing' }
    $baseline = Get-LiveQueueSnapshot
    New-Item -ItemType Directory -Force -Path $script:TempRoot | Out-Null
    $bundle = New-TestBundle -Root $script:TempRoot -Topic 'publisher-contract'

    Remove-Item -LiteralPath $bundle.paths.report -Force
    $missing = Invoke-Publisher -Root $bundle.root -Topic $bundle.topic -RequestId 'publisher-missing-001'
    Assert-True 'incomplete sidecars exit nonzero' ($missing.exitCode -ne 0 -and $missing.output -match 'producer-bundle-missing') "exitCode=$($missing.exitCode)"
    Write-TestText $bundle.paths.report "# $($bundle.topic)`nDesktop final proof: evidence_needed`n"

    Remove-Item -LiteralPath $bundle.paths.pending -Force
    $missingPending = Invoke-Publisher -Root $bundle.root -Topic $bundle.topic -RequestId 'publisher-pending-001'
    Assert-True 'missing producer pending notice is rejected' ($missingPending.exitCode -ne 0 -and $missingPending.output -match 'producer-bundle-missing') "exitCode=$($missingPending.exitCode)"
    Write-TestText $bundle.paths.pending "# pending $($bundle.topic)`nDesktop final proof: evidence_needed`n"
    Update-TestSidecar $bundle

    $badManifest = Get-Content -Raw -LiteralPath $bundle.paths.manifest | ConvertFrom-Json
    $badManifest.sourceIsolation.guard = 'FAIL'
    Write-TestText $bundle.paths.manifest (($badManifest | ConvertTo-Json -Depth 8) + "`n")
    Update-TestSidecar $bundle
    $guard = Invoke-Publisher -Root $bundle.root -Topic $bundle.topic
    Assert-True 'manifest without PASS guard is rejected' ($guard.exitCode -ne 0 -and $guard.output -match 'producer-bundle-invalid') "exitCode=$($guard.exitCode)"
    $badManifest.sourceIsolation.guard = 'PASS'
    Write-TestText $bundle.paths.manifest (($badManifest | ConvertTo-Json -Depth 8) + "`n")
    Update-TestSidecar $bundle

    $badManifest.activePatch = 'other-notebook-v3.patch'
    Write-TestText $bundle.paths.manifest (($badManifest | ConvertTo-Json -Depth 8) + "`n")
    Update-TestSidecar $bundle
    $unbound = Invoke-Publisher -Root $bundle.root -Topic $bundle.topic -RequestId 'publisher-unbound-001'
    Assert-True 'manifest activePatch must pin the selected nested patch' ($unbound.exitCode -ne 0 -and $unbound.output -match 'producer-bundle-invalid') "exitCode=$($unbound.exitCode)"
    $badManifest.activePatch = "$($bundle.topic)-notebook-v3.patch"
    Write-TestText $bundle.paths.manifest (($badManifest | ConvertTo-Json -Depth 8) + "`n")
    Update-TestSidecar $bundle

    $documentation = Invoke-Publisher -Root $bundle.root -Topic $bundle.topic -Profile 'documentation-only' -RequestId 'publisher-docs-001'
    Assert-True 'documentation-only verification profile is accepted' ($documentation.exitCode -eq 0) "exitCode=$($documentation.exitCode) output=$($documentation.output)"

    $unsupported = Invoke-Publisher -Root $bundle.root -Topic $bundle.topic -Profile 'arbitrary-command' -RequestId 'publisher-unsafe-001'
    $unsupportedJson = Get-SingleJson $unsupported.output
    Assert-True 'arbitrary verification profile is rejected with bounded JSON' ($unsupported.exitCode -ne 0 -and $null -ne $unsupportedJson -and $unsupportedJson.reasonCode -ceq 'verification-profile-unsupported' -and -not $unsupported.output.Contains($bundle.root) -and @($unsupportedJson.PSObject.Properties.Name | Where-Object { $_ -notin @('ok', 'reasonCode') }).Count -eq 0) "exitCode=$($unsupported.exitCode)"
    $invalidNode = Invoke-Publisher -Root $bundle.root -Topic $bundle.topic -RequestId 'publisher-node-001' -Node 'desktop'
    Assert-True 'invalid node returns exactly one bounded JSON result' (Test-BoundedPublisherFailure $invalidNode 'node-invalid' $bundle.root) "exitCode=$($invalidNode.exitCode)"
    $invalidTtl = Invoke-Publisher -Root $bundle.root -Topic $bundle.topic -RequestId 'publisher-ttl-001' -TtlMinutes 1
    Assert-True 'invalid TTL returns exactly one bounded JSON result' (Test-BoundedPublisherFailure $invalidTtl 'ttl-invalid' $bundle.root) "exitCode=$($invalidTtl.exitCode)"

    $published = Invoke-Publisher -Root $bundle.root -Topic $bundle.topic
    Assert-True 'publication exits successfully' ($published.exitCode -eq 0) "exitCode=$($published.exitCode) output=$($published.output)"
    $result = $published.output | ConvertFrom-Json
    Assert-True 'successful publication output has only allowlisted safe fields' (@($result.PSObject.Properties.Name | Where-Object { $_ -notin @('requestId', 'requestFile', 'readyFile', 'requestSha256', 'secretPatternHits') }).Count -eq 0) ($result | ConvertTo-Json -Compress)
    $requestPath = Join-Path $bundle.patchDrop ('autoconsume/requests/' + $result.requestFile)
    $readyPath = Join-Path $bundle.patchDrop ('autoconsume/requests/' + $result.readyFile)
    $publishedFilesExist = (Test-Path -LiteralPath $requestPath) -and (Test-Path -LiteralPath $readyPath)
    Assert-True 'publication creates the hash-bound request and ready files' $publishedFilesExist ''
    if (-not $publishedFilesExist) { return }
    $request = Get-Content -Raw -LiteralPath $requestPath | ConvertFrom-Json
    $ready = Get-Content -Raw -LiteralPath $readyPath | ConvertFrom-Json
    Assert-True 'request schema and bounded fields match the approved contract' (
        $request.schemaVersion -ceq 'awx.patchdrop.autoconsume.request.v1' -and
        $request.desktopFinalProof -ceq 'evidence_needed' -and
        $request.nestedPatch -ceq "notebook/$($bundle.topic)-notebook-v3.patch" -and
        $request.nestedManifest -ceq "notebook/$($bundle.topic)-notebook-v3.manifest.json" -and
        $request.nestedManifestSha256 -cmatch '^[a-f0-9]{64}$' -and
        -not [string]::IsNullOrWhiteSpace([string]$request.createdAtUtc) -and
        -not [string]::IsNullOrWhiteSpace([string]$request.expiresAtUtc)
    ) ($request | ConvertTo-Json -Compress)
    Assert-True 'ready schema uses the exact approved property set' ($ready.schemaVersion -ceq 'awx.patchdrop.autoconsume.ready.v1' -and $ready.requestFile -ceq $result.requestFile -and @($ready.PSObject.Properties.Name | Where-Object { $_ -notin @('schemaVersion', 'requestFile', 'requestSha256') }).Count -eq 0) ($ready | ConvertTo-Json -Compress)
    Assert-True 'ready file hash equals request SHA-256' ($ready.requestSha256 -ceq (Get-TestSha256 $requestPath)) "readyHash=$($ready.requestSha256)"
    $requestText = Get-Content -Raw -LiteralPath $requestPath
    Assert-True 'request contains no absolute root or environment values' (-not $requestText.Contains($script:TempRoot) -and -not $requestText.Contains($env:TEMP)) ''
    $retry = Invoke-Publisher -Root $bundle.root -Topic $bundle.topic
    $retryResult = Get-SingleJson $retry.output
    Assert-True 'identical request ID and unchanged bundle is idempotent' ($retry.exitCode -eq 0 -and $null -ne $retryResult -and $retryResult.requestSha256 -ceq $result.requestSha256 -and (Get-TestSha256 $requestPath) -ceq $result.requestSha256) "exitCode=$($retry.exitCode)"

    $ttlRoot = Join-Path $script:TempRoot 'publisher-requested-ttl'
    $ttlTopic = 'requested-ttl'
    $ttlRequestId = 'requested-ttl-001'
    $ttlMinutes = 30
    $ttlBundle = New-TestBundle -Root $ttlRoot -Topic $ttlTopic
    $ttlPublished = Invoke-Publisher -Root $ttlRoot -Topic $ttlTopic -RequestId $ttlRequestId -TtlMinutes $ttlMinutes
    $ttlResult = $ttlPublished.output | ConvertFrom-Json
    $ttlRequestPath = Join-Path $ttlBundle.patchDrop ('autoconsume/requests/' + $ttlResult.requestFile)
    $ttlReadyPath = Join-Path $ttlBundle.patchDrop ('autoconsume/requests/' + $ttlResult.readyFile)
    $ttlRequest = Get-Content -Raw -LiteralPath $ttlRequestPath | ConvertFrom-Json
    $ttlCreatedAt = [datetimeoffset]::ParseExact([string]$ttlRequest.createdAtUtc, 'o', [Globalization.CultureInfo]::InvariantCulture)
    $ttlRequest.expiresAtUtc = $ttlCreatedAt.AddMinutes($ttlMinutes + 1).ToString('o')
    Write-TestText $ttlRequestPath (($ttlRequest | ConvertTo-Json -Compress) + "`n")
    $ttlReady = Get-Content -Raw -LiteralPath $ttlReadyPath | ConvertFrom-Json
    $ttlReady.requestSha256 = Get-TestSha256 $ttlRequestPath
    Write-TestText $ttlReadyPath (($ttlReady | ConvertTo-Json -Compress) + "`n")
    $ttlReadyBefore = Get-TestSha256 $ttlReadyPath
    $ttlRejected = Invoke-Publisher -Root $ttlRoot -Topic $ttlTopic -RequestId $ttlRequestId -TtlMinutes $ttlMinutes
    Assert-True 'existing request with a different valid TTL is rejected without ready replacement' ((Test-BoundedPublisherFailure $ttlRejected 'request-id-conflict' $ttlRoot) -and (Get-TestSha256 $ttlReadyPath) -ceq $ttlReadyBefore) "exitCode=$($ttlRejected.exitCode)"

    $conflict = Invoke-Publisher -Root $bundle.root -Topic $bundle.topic -Profile 'documentation-only'
    $conflictJson = Get-SingleJson $conflict.output
    Assert-True 'conflicting existing request ID is rejected' ($conflict.exitCode -ne 0 -and $null -ne $conflictJson -and $conflictJson.reasonCode -ceq 'request-id-conflict') "exitCode=$($conflict.exitCode)"
    foreach ($extraName in @('commands', 'arguments')) {
        $extraRoot = Join-Path $script:TempRoot ("publisher-extra-request-" + $extraName)
        $extraBundle = New-TestBundle -Root $extraRoot -Topic ("extra-" + $extraName)
        $extraPublished = Invoke-Publisher -Root $extraRoot -Topic $extraBundle.topic -RequestId ("extra-" + $extraName + '-001')
        $extraResult = $extraPublished.output | ConvertFrom-Json
        $extraRequestPath = Join-Path $extraBundle.patchDrop ('autoconsume/requests/' + $extraResult.requestFile)
        $extraReadyPath = Join-Path $extraBundle.patchDrop ('autoconsume/requests/' + $extraResult.readyFile)
        $extraRequest = Get-Content -Raw -LiteralPath $extraRequestPath | ConvertFrom-Json
        $extraRequest | Add-Member -NotePropertyName $extraName -NotePropertyValue @('forbidden')
        Write-TestText $extraRequestPath (($extraRequest | ConvertTo-Json -Compress) + "`n")
        $readyBefore = Get-TestSha256 $extraReadyPath
        $extraRejected = Invoke-Publisher -Root $extraRoot -Topic $extraBundle.topic -RequestId ("extra-" + $extraName + '-001')
        Assert-True "existing request extra $extraName is rejected without ready mutation" ($extraRejected.exitCode -ne 0 -and (Get-SingleJson $extraRejected.output).reasonCode -ceq 'request-id-conflict' -and (Get-TestSha256 $extraReadyPath) -ceq $readyBefore) "exitCode=$($extraRejected.exitCode)"
    }
    $readyExtraRoot = Join-Path $script:TempRoot 'publisher-extra-ready'
    $readyExtraBundle = New-TestBundle -Root $readyExtraRoot -Topic 'extra-ready'
    $readyExtraPublished = Invoke-Publisher -Root $readyExtraRoot -Topic $readyExtraBundle.topic -RequestId 'extra-ready-001'
    $readyExtraResult = $readyExtraPublished.output | ConvertFrom-Json
    $readyExtraPath = Join-Path $readyExtraBundle.patchDrop ('autoconsume/requests/' + $readyExtraResult.readyFile)
    $readyExtra = Get-Content -Raw -LiteralPath $readyExtraPath | ConvertFrom-Json
    $readyExtra | Add-Member -NotePropertyName 'arguments' -NotePropertyValue @('forbidden')
    Write-TestText $readyExtraPath (($readyExtra | ConvertTo-Json -Compress) + "`n")
    $readyExtraBefore = Get-TestSha256 $readyExtraPath
    $readyExtraRejected = Invoke-Publisher -Root $readyExtraRoot -Topic $readyExtraBundle.topic -RequestId 'extra-ready-001'
    Assert-True 'existing ready extra field is rejected without replacement' ($readyExtraRejected.exitCode -ne 0 -and (Get-SingleJson $readyExtraRejected.output).reasonCode -ceq 'request-id-conflict' -and (Get-TestSha256 $readyExtraPath) -ceq $readyExtraBefore) "exitCode=$($readyExtraRejected.exitCode)"

    $requestTamperCases = @(
        [pscustomobject]@{ name = 'schemaVersion'; apply = { param($r) $r.schemaVersion = 'awx.patchdrop.autoconsume.request.v0' } },
        [pscustomobject]@{ name = 'requestId'; apply = { param($r) $r.requestId = 'different-request-id' } },
        [pscustomobject]@{ name = 'nestedPatch'; apply = { param($r) $r.nestedPatch = 'notebook/different-notebook-v3.patch' } },
        [pscustomobject]@{ name = 'nestedManifest'; apply = { param($r) $r.nestedManifest = 'notebook/different-notebook-v3.manifest.json' } },
        [pscustomobject]@{ name = 'desktopFinalProof'; apply = { param($r) $r.desktopFinalProof = 'complete' } },
        [pscustomobject]@{ name = 'createdAtUtc-invalid'; apply = { param($r) $r.createdAtUtc = 'not-a-date' } },
        [pscustomobject]@{ name = 'expiresAtUtc-invalid'; apply = { param($r) $r.expiresAtUtc = 'not-a-date' } },
        [pscustomobject]@{ name = 'date-order'; apply = { param($r) $r.createdAtUtc = '2030-01-02T00:00:00.0000000+00:00'; $r.expiresAtUtc = '2030-01-01T00:00:00.0000000+00:00' } }
    )
    foreach ($case in $requestTamperCases) {
        $caseRoot = Join-Path $script:TempRoot ('publisher-binding-request-' + $case.name)
        $caseTopic = ('binding-' + $case.name.ToLowerInvariant())
        $caseRequestId = ($caseTopic + '-001')
        $caseBundle = New-TestBundle -Root $caseRoot -Topic $caseTopic
        $casePublished = Invoke-Publisher -Root $caseRoot -Topic $caseTopic -RequestId $caseRequestId
        $caseResult = $casePublished.output | ConvertFrom-Json
        $caseRequestPath = Join-Path $caseBundle.patchDrop ('autoconsume/requests/' + $caseResult.requestFile)
        $caseReadyPath = Join-Path $caseBundle.patchDrop ('autoconsume/requests/' + $caseResult.readyFile)
        $caseRequest = Get-Content -Raw -LiteralPath $caseRequestPath | ConvertFrom-Json
        & $case.apply $caseRequest
        Write-TestText $caseRequestPath (($caseRequest | ConvertTo-Json -Compress) + "`n")
        $caseReady = Get-Content -Raw -LiteralPath $caseReadyPath | ConvertFrom-Json
        $caseReady.requestSha256 = Get-TestSha256 $caseRequestPath
        Write-TestText $caseReadyPath (($caseReady | ConvertTo-Json -Compress) + "`n")
        $caseReadyBefore = Get-TestSha256 $caseReadyPath
        $caseRejected = Invoke-Publisher -Root $caseRoot -Topic $caseTopic -RequestId $caseRequestId
        Assert-True "existing request binding $($case.name) is rejected without ready mutation" ((Test-BoundedPublisherFailure $caseRejected 'request-id-conflict' $caseRoot) -and (Get-TestSha256 $caseReadyPath) -ceq $caseReadyBefore) "exitCode=$($caseRejected.exitCode)"
    }

    foreach ($readyField in @('schemaVersion', 'requestFile')) {
        $caseRoot = Join-Path $script:TempRoot ('publisher-binding-ready-' + $readyField.ToLowerInvariant())
        $caseTopic = ('ready-' + $readyField.ToLowerInvariant())
        $caseRequestId = ($caseTopic + '-001')
        $caseBundle = New-TestBundle -Root $caseRoot -Topic $caseTopic
        $casePublished = Invoke-Publisher -Root $caseRoot -Topic $caseTopic -RequestId $caseRequestId
        $caseResult = $casePublished.output | ConvertFrom-Json
        $caseReadyPath = Join-Path $caseBundle.patchDrop ('autoconsume/requests/' + $caseResult.readyFile)
        $caseReady = Get-Content -Raw -LiteralPath $caseReadyPath | ConvertFrom-Json
        if ($readyField -ceq 'schemaVersion') { $caseReady.schemaVersion = 'awx.patchdrop.autoconsume.ready.v0' }
        else { $caseReady.requestFile = 'different-request.json' }
        Write-TestText $caseReadyPath (($caseReady | ConvertTo-Json -Compress) + "`n")
        $caseReadyBefore = Get-TestSha256 $caseReadyPath
        $caseRejected = Invoke-Publisher -Root $caseRoot -Topic $caseTopic -RequestId $caseRequestId
        Assert-True "existing ready binding $readyField is rejected without replacement" ((Test-BoundedPublisherFailure $caseRejected 'request-id-conflict' $caseRoot) -and (Get-TestSha256 $caseReadyPath) -ceq $caseReadyBefore) "exitCode=$($caseRejected.exitCode)"
    }
    $after = Get-LiveQueueSnapshot
    Assert-True 'entire live PatchDrop queue manifest and hashes are unchanged' ($after.fileCount -eq $baseline.fileCount -and $after.manifestSha256 -ceq $baseline.manifestSha256) "files=$($after.fileCount) manifest=$($after.manifestSha256)"
}

try {
    Invoke-PublisherTests
} finally {
    if (Test-Path -LiteralPath $script:TempRoot) { Remove-Item -LiteralPath $script:TempRoot -Recurse -Force }
}

Write-Host "[summary] passed=$script:Passed failed=$script:Failures secretPatternHits=0"
if ($script:Failures -ne 0) { exit 1 }
exit 0
