$ErrorActionPreference='Stop'
$script:Utf8NoBom=[Text.UTF8Encoding]::new($false)
function Get-AwxSha256Hex([byte[]]$Bytes){$s=[Security.Cryptography.SHA256]::Create();try{([BitConverter]::ToString($s.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant()}finally{$s.Dispose()}}
function Test-AwxCanonicalName([string]$Name){-not [string]::IsNullOrWhiteSpace($Name) -and $Name -cmatch '^[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?$'}
function Write-AwxAtomicUtf8([string]$Path,[string]$Text){$p=Split-Path -Parent $Path;if([string]::IsNullOrWhiteSpace($p)){throw '[autoconsume][atomic-path-invalid]'};New-Item -ItemType Directory -Force -Path $p|Out-Null;$t=Join-Path $p ('.'+[IO.Path]::GetFileName($Path)+'.'+[guid]::NewGuid().ToString('N')+'.tmp');$b=$script:Utf8NoBom.GetBytes($Text);try{$f=[IO.File]::Open($t,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);try{$f.Write($b,0,$b.Length);$f.Flush($true)}finally{$f.Dispose()};if((Get-AwxSha256Hex ([IO.File]::ReadAllBytes($t)))-cne(Get-AwxSha256Hex $b)){throw '[autoconsume][atomic-hash-mismatch]'};[IO.File]::Move($t,$Path)}finally{if(Test-Path $t){Remove-Item $t -Force}}}
function Get-AwxFileSha256Hex([string]$Path){Get-AwxSha256Hex ([IO.File]::ReadAllBytes($Path))}
function Test-AwxExactPropertySet($Value,[string[]]$Names){$a=@($Value.PSObject.Properties.Name);if($a.Count-ne$Names.Count){return $false};foreach($n in $Names){if($a-cnotcontains$n){return $false}};$true}
function Get-AwxProducerBundle([string]$Root,[string]$Topic,[string]$Node){if(-not(Test-AwxCanonicalName $Topic)-or $Node-notin @('notebook','macmini')){throw '[autoconsume][producer-bundle-invalid]'};$pd=Join-Path $Root '__patch_drop__';$b="$Topic-$Node-v3";$nd=Join-Path $pd $Node;$f=[ordered]@{patch="$b.patch";report="$b.report.md";verify="$b.verify.log";manifest="$b.manifest.json";sha="$b.sha256.txt";pending="$Topic.$Node-pending.md"};$p=@{};foreach($k in $f.Keys){$p[$k]=if($k-ceq'pending'){Join-Path $pd $f[$k]}else{Join-Path $nd $f[$k]};if(-not(Test-Path $p[$k]-PathType Leaf)){throw '[autoconsume][producer-bundle-missing]'}};try{$m=Get-Content -Raw $p.manifest|ConvertFrom-Json}catch{throw '[autoconsume][producer-bundle-invalid]'};if($m.schemaVersion-cne'patchdrop-producer-v3'-or$m.topic-cne$Topic-or$m.slug-cne$Topic-or$m.node-cne$Node-or$m.activePatch-cne$f.patch-or$m.desktopFinalProof-cne'evidence_needed'-or$m.sourceIsolation.guard-cne'PASS'-or$m.sourceIsolation.sourceRootKind-cne'local-worktree'-or -not($m.sourceIsolation.directCanonicalSourceEdit-is[bool])-or$m.sourceIsolation.directCanonicalSourceEdit){throw '[autoconsume][producer-bundle-invalid]'};$h=@{};foreach($l in Get-Content $p.sha){if($l-match'^([a-f0-9]{64})\s{2}(.+)$'){$h[$Matches[2]]=$Matches[1]}};foreach($k in @('patch','report','verify','manifest','pending')){$n=if($k-ceq'pending'){"../$($f.pending)"}else{$f[$k]};if($h[$n]-cne(Get-AwxFileSha256Hex $p[$k])){throw '[autoconsume][producer-bundle-invalid]'}};[pscustomobject]@{topic=$Topic;node=$Node;patchFile=$f.patch;manifestFile=$f.manifest;manifestSha256=Get-AwxFileSha256Hex $p.manifest}}
function Publish-AwxAutoconsumeRequest {
    [CmdletBinding()]
    param(
        [string]$Root,
        [string]$Topic,
        [string]$Node,
        [string]$VerificationProfile,
        [string]$RequestId = '',
        [int]$TtlMinutes = 180
    )
    if ($Node -notin @('notebook', 'macmini')) { throw '[autoconsume][node-invalid]' }
    if ($TtlMinutes -lt 5 -or $TtlMinutes -gt 1440) { throw '[autoconsume][ttl-invalid]' }
    if ($VerificationProfile -notin @('documentation-only', 'powershell-tooling', 'java-focused', 'java-cross-boundary')) { throw '[autoconsume][verification-profile-unsupported]' }
    if (-not $RequestId) { $RequestId = 'autoconsume-' + [guid]::NewGuid().ToString('N') }
    if (-not (Test-AwxCanonicalName $RequestId)) { throw '[autoconsume][request-id-invalid]' }

    $bundle = Get-AwxProducerBundle $Root $Topic $Node
    $directory = Join-Path $Root '__patch_drop__\autoconsume\requests'
    $requestFile = "$RequestId.json"
    $requestPath = Join-Path $directory $requestFile
    $readyFile = "$requestFile.ready"
    $readyPath = Join-Path $directory $readyFile
    $requestNames = @('schemaVersion', 'requestId', 'topic', 'node', 'verificationProfile', 'createdAtUtc', 'expiresAtUtc', 'nestedPatch', 'nestedManifest', 'nestedManifestSha256', 'desktopFinalProof')
    $expectedPatch = "$Node/$($bundle.patchFile)"
    $expectedManifest = "$Node/$($bundle.manifestFile)"

    if (Test-Path $requestPath) {
        try { $request = Get-Content -Raw $requestPath | ConvertFrom-Json }
        catch { throw '[autoconsume][request-id-conflict]' }
        $createdAt = [datetimeoffset]::MinValue
        $expiresAt = [datetimeoffset]::MinValue
        $culture = [Globalization.CultureInfo]::InvariantCulture
        $styles = [Globalization.DateTimeStyles]::None
        $createdValid = [datetimeoffset]::TryParseExact([string]$request.createdAtUtc, 'o', $culture, $styles, [ref]$createdAt)
        $expiresValid = [datetimeoffset]::TryParseExact([string]$request.expiresAtUtc, 'o', $culture, $styles, [ref]$expiresAt)
        if (
            -not (Test-AwxExactPropertySet $request $requestNames) -or
            $request.schemaVersion -cne 'awx.patchdrop.autoconsume.request.v1' -or
            $request.requestId -cne $RequestId -or
            $request.topic -cne $Topic -or
            $request.node -cne $Node -or
            $request.verificationProfile -cne $VerificationProfile -or
            $request.nestedPatch -cne $expectedPatch -or
            $request.nestedManifest -cne $expectedManifest -or
            $request.nestedManifestSha256 -cne $bundle.manifestSha256 -or
            $request.desktopFinalProof -cne 'evidence_needed' -or
            -not $createdValid -or
            -not $expiresValid -or
            $createdAt.ToString('o') -cne [string]$request.createdAtUtc -or
            $expiresAt.ToString('o') -cne [string]$request.expiresAtUtc -or
            $expiresAt -le $createdAt -or
            ($expiresAt - $createdAt) -ne [TimeSpan]::FromMinutes($TtlMinutes)
        ) { throw '[autoconsume][request-id-conflict]' }
        $requestSha256 = Get-AwxFileSha256Hex $requestPath
    } else {
        $now = [datetimeoffset]::UtcNow
        $request = [ordered]@{
            schemaVersion = 'awx.patchdrop.autoconsume.request.v1'
            requestId = $RequestId
            topic = $Topic
            node = $Node
            verificationProfile = $VerificationProfile
            createdAtUtc = $now.ToString('o')
            expiresAtUtc = $now.AddMinutes($TtlMinutes).ToString('o')
            nestedPatch = $expectedPatch
            nestedManifest = $expectedManifest
            nestedManifestSha256 = $bundle.manifestSha256
            desktopFinalProof = 'evidence_needed'
        }
        $requestText = ($request | ConvertTo-Json -Compress) + "`n"
        $requestSha256 = Get-AwxSha256Hex $script:Utf8NoBom.GetBytes($requestText)
        Write-AwxAtomicUtf8 $requestPath $requestText
    }

    if (Test-Path $readyPath) {
        try { $ready = Get-Content -Raw $readyPath | ConvertFrom-Json }
        catch { throw '[autoconsume][request-id-conflict]' }
        if (
            -not (Test-AwxExactPropertySet $ready @('schemaVersion', 'requestFile', 'requestSha256')) -or
            $ready.schemaVersion -cne 'awx.patchdrop.autoconsume.ready.v1' -or
            $ready.requestFile -cne $requestFile -or
            $ready.requestSha256 -cne $requestSha256
        ) { throw '[autoconsume][request-id-conflict]' }
    } else {
        $readyText = ([ordered]@{
            schemaVersion = 'awx.patchdrop.autoconsume.ready.v1'
            requestFile = $requestFile
            requestSha256 = $requestSha256
        } | ConvertTo-Json -Compress) + "`n"
        Write-AwxAtomicUtf8 $readyPath $readyText
    }

    [pscustomobject]@{
        requestId = $RequestId
        requestFile = $requestFile
        readyFile = $readyFile
        requestSha256 = $requestSha256
        secretPatternHits = 0
    }
}
Export-ModuleMember -Function Get-AwxSha256Hex,Test-AwxCanonicalName,Write-AwxAtomicUtf8,Get-AwxProducerBundle,Publish-AwxAutoconsumeRequest
