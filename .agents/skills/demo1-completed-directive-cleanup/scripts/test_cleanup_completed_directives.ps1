[CmdletBinding()]
param([string]$Case='')
$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot 'cleanup_completed_directives.ps1'
$base = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('awx-directive-cleanup-tests-' + [guid]::NewGuid().ToString('N'))))
$utf8 = New-Object Text.UTF8Encoding($false)
$results = New-Object 'Collections.Generic.List[object]'
$junctions = New-Object 'Collections.Generic.List[string]'
function Write-Text($Path, $Text) { [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null; [IO.File]::WriteAllText($Path, $Text, $utf8) }
function Hash($Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Write-Json($Path, $Value) { Write-Text $Path ($Value | ConvertTo-Json -Depth 20 -Compress) }
function New-Fixture {
    $dir = Join-Path $base ([guid]::NewGuid().ToString('N'))
    Write-Text (Join-Path $dir 'queue/done.md') '# Synthetic completed directive'
    Write-Text (Join-Path $dir 'source/code.txt') 'verified postimage'
    Write-Text (Join-Path $dir 'proof/test.log') 'tests=1 exit=0'
    $post = [ordered]@{ path='source/code.txt'; sha256=(Hash (Join-Path $dir 'source/code.txt')) }
    $report = [ordered]@{ sourcePatchCompletion='verified'; desktopFinalProof='verified'; directive='queue/done.md'; postimages=@([ordered]@{path=$post.path; preimage=('0'*64); postimage=$post.sha256}) }
    Write-Json (Join-Path $dir 'proof/completion.json') $report
    $request = [ordered]@{
        schemaVersion='awx.completed-directive-cleanup.v1'; deleteAuthorized=$true; allRequiredWorkComplete=$true; completionFlag=$true
        directive=[ordered]@{path='queue/done.md';sha256=(Hash (Join-Path $dir 'queue/done.md'))}
        completionReport=[ordered]@{path='proof/completion.json';sha256=(Hash (Join-Path $dir 'proof/completion.json'))}
        postimages=@($post); evidenceFiles=@([ordered]@{path='proof/test.log';sha256=(Hash (Join-Path $dir 'proof/test.log'))});queueRoots=@('queue')
    }
    Write-Json (Join-Path $dir 'request.json') $request
    [pscustomobject]@{Root=$dir;Request=$request;Report=$report}
}
function Save-Fixture($Fixture, [switch]$ReportChanged) {
    if($ReportChanged){Write-Json (Join-Path $Fixture.Root 'proof/completion.json') $Fixture.Report; $Fixture.Request.completionReport.sha256=Hash (Join-Path $Fixture.Root 'proof/completion.json')}
    Write-Json (Join-Path $Fixture.Root 'request.json') $Fixture.Request
}
function Invoke-Fixture($Fixture, [switch]$Apply, [switch]$MarkOnly, [string]$RootOverride='') {
    $invokeRoot=if($RootOverride){$RootOverride}else{$Fixture.Root}
    $args=@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$helper,'-Root',$invokeRoot,'-RequestPath',(Join-Path $Fixture.Root 'request.json'),'-LogDirectory','.cleanup')
    if($Apply){$args+='-Apply'}
    if($MarkOnly){$args+='-MarkOnly'}
    $old=$ErrorActionPreference;$ErrorActionPreference='Continue'
    try{$raw=@(& powershell.exe @args 2>&1);$code=$LASTEXITCODE}finally{$ErrorActionPreference=$old}
    $parsed=$null;try{$parsed=($raw -join "`n")|ConvertFrom-Json}catch{}
    $script:lastHelperReason=if($null -ne $parsed -and $parsed.reason -match '^[a-z0-9-]{3,80}$'){$parsed.reason}else{'helper-output-unavailable'}
    if($null -ne $parsed -and $parsed.journalRelativePath){
        $events=@(Get-Content -LiteralPath (Join-Path $Fixture.Root $parsed.journalRelativePath)|ForEach-Object{$_|ConvertFrom-Json})
        $held=@($events|Where-Object{$_.event -eq 'held'})
        if($held.Count -gt 0 -and $held[0].reason -match '^[a-z0-9-]{3,80}$'){$script:lastHelperReason=$held[0].reason}
    }
    [pscustomobject]@{Code=$code;Value=$parsed}
}
function Assert-True($Value) { if(-not $Value){throw 'assertion-failed'} }
function Check($Name,[scriptblock]$Body) {
    if($Case -and $Name -ne $Case){return};$script:lastHelperReason='fixture-failed'
    try{& $Body;$results.Add([pscustomobject]@{test=$Name;passed=$true})}catch{$results.Add([pscustomobject]@{test=$Name;passed=$false;reason=$script:lastHelperReason})}
}
function Assert-Held($Fixture) {
    $r=Invoke-Fixture $Fixture -Apply
    Assert-True ($r.Code -ne 0 -and $r.Value.status -eq 'hold' -and (Test-Path -LiteralPath (Join-Path $Fixture.Root 'queue/done.md')))
    Assert-True (@(Get-ChildItem -LiteralPath $Fixture.Root -Filter 'completion-status.json' -Recurse).Count -eq 0)
}
function Assert-Marked($Fixture,$Result,[string]$CleanupState) {
    Assert-True ($Result.Value.directiveStatus -eq 'completed' -and $Result.Value.markedCount -eq 1)
    $receipt=Get-Content -LiteralPath (Join-Path $Fixture.Root $Result.Value.completionStatusRelativePath) -Raw|ConvertFrom-Json
    Assert-True ($receipt.directiveStatus -eq 'completed' -and $receipt.doNotReapply -eq $true -and $Result.Value.cleanupState -eq $CleanupState)
    Assert-True ($receipt.directive.path -eq 'queue/done.md' -and $receipt.directive.sha256 -eq $Fixture.Request.directive.sha256)
    Assert-True ($receipt.completionReport.sha256 -eq $Fixture.Request.completionReport.sha256 -and $receipt.postimages[0].sha256 -eq $Fixture.Request.postimages[0].sha256)
    $notice=Get-Content -LiteralPath (Join-Path $Fixture.Root $Result.Value.completionMarkdownRelativePath) -Raw
    Assert-True ($notice -match 'COMPLETED' -and $notice -match 'doNotReapply=true' -and $notice -match 'journal.jsonl')
    $events=@(Get-Content -LiteralPath (Join-Path $Fixture.Root $Result.Value.journalRelativePath)|ForEach-Object{$_|ConvertFrom-Json})
    $published=@($events|Where-Object{$_.event -eq 'completion-recorded'})
    Assert-True ($published.Count -eq 1 -and $events[-1].cleanupState -eq $CleanupState)
    # Cleanup must not overwrite the durable completion binding it published before DELETE.
    Assert-True ((Hash (Join-Path $Fixture.Root $Result.Value.completionStatusRelativePath)) -eq $published[0].statusSha256)
    Assert-True ((Hash (Join-Path $Fixture.Root $Result.Value.completionMarkdownRelativePath)) -eq $published[0].markdownSha256)
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $Fixture.Root 'queue') -File).Count -le 1)
}
[IO.Directory]::CreateDirectory($base)|Out-Null
try {
    Check 'verified-directive-removes-only-directive-with-recovery' {
        $f=New-Fixture;$r=Invoke-Fixture $f -Apply
        Assert-True ($r.Code -eq 0 -and $r.Value.deletedCount -eq 1 -and -not (Test-Path -LiteralPath (Join-Path $f.Root 'queue/done.md')))
        Assert-True ((Test-Path -LiteralPath (Join-Path $f.Root 'source/code.txt')) -and (Test-Path -LiteralPath (Join-Path $f.Root 'proof/completion.json')))
        Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $f.Root '.cleanup') -Filter '*.bin' -Recurse).Count -eq 1)
    }
    if(-not (Test-Path -LiteralPath $helper)){throw 'helper-missing-red'}
    Check 'dry-run-preserves-directive' {$f=New-Fixture;$r=Invoke-Fixture $f;Assert-True ($r.Code -eq 0 -and $r.Value.mode -eq 'dry-run' -and (Test-Path -LiteralPath (Join-Path $f.Root 'queue/done.md')))}
    Check 'delete-sharing-denied-still-records-completion' {
        $f=New-Fixture;$path=Join-Path $f.Root 'queue/done.md'
        $reader=New-Object IO.FileStream($path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
        try{$r=Invoke-Fixture $f -Apply}finally{$reader.Dispose()}
        Assert-True ($r.Code -ne 0 -and $r.Value.status -eq 'hold' -and $r.Value.deletedCount -eq 0 -and (Hash $path) -eq $f.Request.directive.sha256)
        Assert-Marked $f $r 'cleanup-held'
        $retry=Invoke-Fixture $f -Apply
        Assert-True ($retry.Code -eq 0 -and $retry.Value.deletedCount -eq 1)
        Assert-Marked $f $retry 'deleted'
    }
    Check 'read-only-directive-retains-visible-completion' {
        $f=New-Fixture;$path=Join-Path $f.Root 'queue/done.md';[IO.File]::SetAttributes($path,[IO.FileAttributes]::ReadOnly)
        try{
            $r=Invoke-Fixture $f -Apply
            Assert-True ($r.Code -ne 0 -and $r.Value.status -eq 'hold' -and (Hash $path) -eq $f.Request.directive.sha256)
            Assert-Marked $f $r 'cleanup-held'
        }finally{[IO.File]::SetAttributes($path,[IO.FileAttributes]::Normal)}
    }
    Check 'mark-only-needs-no-delete-authorization-and-preserves-bytes' {
        $f=New-Fixture;$f.Request.deleteAuthorized=$false;Save-Fixture $f
        $r=Invoke-Fixture $f -Apply -MarkOnly
        Assert-True ($r.Code -eq 0 -and $r.Value.mode -eq 'mark-only' -and $r.Value.deletedCount -eq 0 -and $r.Value.retainedCount -eq 1)
        Assert-True ((Hash (Join-Path $f.Root 'queue/done.md')) -eq $f.Request.directive.sha256)
        Assert-Marked $f $r 'retained'
        Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $f.Root '.cleanup') -Filter '*.bin' -Recurse).Count -eq 0)
    }
    Check 'mark-only-receipt-allows-later-authorized-cleanup' {
        $f=New-Fixture;$f.Request.deleteAuthorized=$false;Save-Fixture $f
        $marked=Invoke-Fixture $f -Apply -MarkOnly
        Assert-True ($marked.Code -eq 0 -and $marked.Value.retainedCount -eq 1)
        $receiptPath=Join-Path $f.Root $marked.Value.completionStatusRelativePath
        $receiptHash=Hash $receiptPath
        $denied=Invoke-Fixture $f -Apply
        Assert-True ($denied.Code -ne 0 -and (Test-Path -LiteralPath (Join-Path $f.Root 'queue/done.md')))
        $f.Request.deleteAuthorized=$true;Save-Fixture $f
        $deleted=Invoke-Fixture $f -Apply
        Assert-True ($deleted.Code -eq 0 -and $deleted.Value.deletedCount -eq 1)
        Assert-True ((Hash $receiptPath) -eq $receiptHash)
        Assert-True ($marked.Value.journalRelativePath -ne $deleted.Value.journalRelativePath)
        Assert-Marked $f $deleted 'deleted'
    }
    Check 'mark-only-dry-run-does-not-publish' {
        $f=New-Fixture;$f.Request.deleteAuthorized=$false;Save-Fixture $f
        $r=Invoke-Fixture $f -MarkOnly
        Assert-True ($r.Code -eq 0 -and $r.Value.mode -eq 'dry-run' -and -not (Test-Path -LiteralPath (Join-Path $f.Root '.cleanup')))
    }
    Check 'mark-only-cannot-certify-partial-proof' {
        $f=New-Fixture;$f.Report.desktopFinalProof='evidence_needed';Save-Fixture $f -ReportChanged
        $r=Invoke-Fixture $f -Apply -MarkOnly
        Assert-True ($r.Code -ne 0 -and $r.Value.directiveStatus -ne 'completed' -and -not (Test-Path -LiteralPath (Join-Path $f.Root '.cleanup')))
    }
    Check 'pending-failed-and-partial-reports-held' {
        foreach($state in @('pending','failed','verified-partial','COMPLETE')){$f=New-Fixture;$f.Report.sourcePatchCompletion=$state;Save-Fixture $f -ReportChanged;Assert-Held $f}
        $f=New-Fixture;$f.Report.desktopFinalProof='evidence_needed';Save-Fixture $f -ReportChanged;Assert-Held $f
    }
    Check 'flags-must-be-real-complete-booleans' {
        foreach($flag in @('deleteAuthorized','allRequiredWorkComplete','completionFlag')){foreach($value in @($false,'true')){$f=New-Fixture;$f.Request[$flag]=$value;Save-Fixture $f;Assert-Held $f}}
    }
    Check 'contradictory-report-flags-held' {
        foreach($flag in @('allRequiredWorkComplete','completionFlag')){foreach($value in @($false,'true')){$f=New-Fixture;$f.Report[$flag]=$value;Save-Fixture $f -ReportChanged;Assert-Held $f}}
    }
    Check 'changed-target-and-source-and-evidence-held' {
        foreach($path in @('queue/done.md','source/code.txt','proof/test.log','proof/completion.json')){$f=New-Fixture;Write-Text (Join-Path $f.Root $path) 'changed';Assert-Held $f}
    }
    Check 'traversal-absolute-and-ads-held' {
        foreach($path in @('../done.md','queue/../done.md','C:/done.md','queue/done.md:stream','queue/TEMPLA~1/done.md')){$f=New-Fixture;$f.Request.directive.path=$path;Save-Fixture $f;Assert-Held $f}
    }
    Check 'report-target-set-and-directive-binding-held' {
        $f=New-Fixture;$f.Report.postimages=@();Save-Fixture $f -ReportChanged;Assert-Held $f
        $f=New-Fixture;$f.Report.directive='queue/other.md';Save-Fixture $f -ReportChanged;Assert-Held $f
        $f=New-Fixture;$f.Report.postimages[0].postimage='0'*64;Save-Fixture $f -ReportChanged;Assert-Held $f
    }
    Check 'duplicate-and-evidence-overlap-held' {
        $f=New-Fixture;$f.Request.relatedMarkdown=@($f.Request.directive);Save-Fixture $f;Assert-Held $f
        $f=New-Fixture;$f.Request.postimages=@($f.Request.postimages[0],$f.Request.postimages[0]);Save-Fixture $f;Assert-Held $f
        $f=New-Fixture;$f.Request.evidenceFiles=@($f.Request.directive);Save-Fixture $f;Assert-Held $f
    }
    Check 'related-markdown-mismatch-prevents-whole-delete' {
        $f=New-Fixture;Write-Text (Join-Path $f.Root 'queue/related.md') 'related';$f.Request.relatedMarkdown=@([ordered]@{path='queue/related.md';sha256=('0'*64)});Save-Fixture $f;Assert-Held $f
    }
    Check 'related-markdown-exact-and-repeat-idempotent' {
        $f=New-Fixture;Write-Text (Join-Path $f.Root 'queue/related.md') 'related';$f.Request.relatedMarkdown=@([ordered]@{path='queue/related.md';sha256=(Hash (Join-Path $f.Root 'queue/related.md'))});Save-Fixture $f
        $first=Invoke-Fixture $f -Apply;$second=Invoke-Fixture $f -Apply
        Assert-True ($first.Code -eq 0 -and $first.Value.deletedCount -eq 2 -and $second.Code -eq 0 -and $second.Value.alreadyAbsentCount -eq 2 -and $second.Value.deletedCount -eq 0)
    }
    Check 'log-failure-prevents-delete' {$f=New-Fixture;Write-Text (Join-Path $f.Root '.cleanup') 'not-directory';Assert-Held $f}
    Check 'protected-markdown-is-held' {
        foreach($path in @('queue/AGENTS.md','queue/README.md','queue/x.report.md','agent-prompts/agents/x.md','queue/backup/x.md','queue/template/x.md','BackupsXS/x.md','.agents/x.md')){
            $f=New-Fixture;Write-Text (Join-Path $f.Root $path) 'protected';$f.Request.directive=[ordered]@{path=$path;sha256=(Hash (Join-Path $f.Root $path))};$f.Request.queueRoots=@('.');$f.Report.directive=$path;Save-Fixture $f -ReportChanged;Assert-Held $f
        }
    }
    Check 'hardlink-target-held' {$f=New-Fixture;New-Item -ItemType HardLink -Path (Join-Path $f.Root 'queue/link.md') -Target (Join-Path $f.Root 'queue/done.md')|Out-Null;Assert-Held $f}
    Check 'inaccessible-target-is-held-not-absent' {
        $f=New-Fixture;$blocked=New-Object IO.FileStream((Join-Path $f.Root 'queue/done.md'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
        try{Assert-Held $f}finally{$blocked.Dispose()}
    }
    Check 'native-path-error-is-held-not-absent' {
        $f=New-Fixture;$f.Request.directive.path='queue/'+('a'*256)+'.md';$f.Report.directive=$f.Request.directive.path;Save-Fixture $f -ReportChanged;Assert-Held $f
    }
    Check 'reparse-parent-held' {
        $f=New-Fixture;$junction=Join-Path $f.Root 'linked';New-Item -ItemType Junction -Path $junction -Target (Join-Path $f.Root 'queue')|Out-Null;$junctions.Add($junction)
        $f.Request.directive.path='linked/done.md';$f.Report.directive='linked/done.md';$f.Request.queueRoots=@('linked');Save-Fixture $f -ReportChanged;Assert-Held $f
    }
    Check 'whole-directive-proof-required-even-when-target-absent' {$f=New-Fixture;$first=Invoke-Fixture $f -Apply;Assert-True ($first.Code -eq 0);$f.Report.desktopFinalProof='evidence_needed';Save-Fixture $f -ReportChanged;$r=Invoke-Fixture $f -Apply;Assert-True ($r.Code -ne 0 -and $r.Value.status -eq 'hold')}
    Check 'explicit-local-root-queue-produces-only-status-markdown' {$f=New-Fixture;$f.Request.queueRoots=@('.');Save-Fixture $f;$r=Invoke-Fixture $f -Apply;Assert-True ($r.Code -eq 0);Assert-Marked $f $r 'deleted';$markdown=@(Get-ChildItem -LiteralPath (Join-Path $f.Root '.cleanup') -Filter '*.md' -Recurse);Assert-True ($markdown.Count -eq 1 -and $markdown[0].Name -eq 'COMPLETED.md')}
    Check 'drive-root-is-not-project-queue' {$f=New-Fixture;$f.Request.queueRoots=@('.');Save-Fixture $f;$r=Invoke-Fixture $f -RootOverride ([IO.Path]::GetPathRoot($f.Root));Assert-True ($r.Code -ne 0 -and $r.Value.reason -eq 'root-queue-requires-project-directory')}
} catch {if($_.Exception.Message -ne 'helper-missing-red'){$results.Add([pscustomobject]@{test='fixture-harness';passed=$false;reason='fixture-failure'})}}
finally {
    foreach($junction in $junctions){if(Test-Path -LiteralPath $junction){[IO.Directory]::Delete($junction,$false)}}
    $expected=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    if($base.StartsWith($expected,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $base) -match '^awx-directive-cleanup-tests-[a-f0-9]{32}$'){
        if(@(Get-ChildItem -LiteralPath $base -Recurse -Force | Where-Object {($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0}).Count -eq 0){Remove-Item -LiteralPath $base -Recurse -Force}
    }
}
$failed=@($results|Where-Object{-not $_.passed})
[ordered]@{tests=$results.Count;passed=$results.Count-$failed.Count;failed=$failed.Count;failedCases=@($failed|ForEach-Object{$_.test});failureReasons=@($failed|ForEach-Object{$_.reason});reason=$(if($failed.Count){'regression-failed'}else{'all-passed'})}|ConvertTo-Json -Compress
if($failed.Count){exit 1}
exit 0
