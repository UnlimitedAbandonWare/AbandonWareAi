[CmdletBinding()]
param([string]$Case='')
$ErrorActionPreference='Stop'
$helper=Join-Path $PSScriptRoot 'cleanup_completed_directives.ps1'
$base=Join-Path ([IO.Path]::GetTempPath()) ('awx-task-cleanup-tests-'+[guid]::NewGuid().ToString('N'))
$results=@();$utf8=New-Object Text.UTF8Encoding($false)
function Put($Root,$Path,$Text){$p=Join-Path $Root $Path;[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($p))|Out-Null;[IO.File]::WriteAllText($p,$Text,$utf8)}
function Pair($Root,$Path){@{path=$Path;sha256=(Get-FileHash -LiteralPath (Join-Path $Root $Path) -Algorithm SHA256).Hash.ToLowerInvariant()}}
function Json($Root,$Path,$Value){Put $Root $Path ($Value|ConvertTo-Json -Depth 20)}
function Fixture {
    $root=Join-Path $base ([guid]::NewGuid().ToString('N'))
    Put $root 'main/source.java' 'original source postimage'
    Put $root 'data/agent-handoff/task/final/report.md' 'final report'
    Put $root 'data/agent-handoff/task/final/patch.patch' 'final applied patch'
    Put $root 'data/agent-handoff/task/final/verify.log' 'actual fixture verification: passed'
    Put $root 'data/agent-handoff/task/recovery/original.bin' 'original preimage'
    Put $root 'data/agent-handoff/task/work/old.patch' 'obsolete patch'
    Put $root 'data/agent-handoff/task/work/draft.tmp' 'intermediate draft'
    Put $root 'data/agent-handoff/task/work/copy.report.md' 'final report'
    $post=Pair $root 'main/source.java';$log=Pair $root 'data/agent-handoff/task/final/verify.log'
    $keep=@((Pair $root 'data/agent-handoff/task/final/report.md'),(Pair $root 'data/agent-handoff/task/final/patch.patch'),(Pair $root 'data/agent-handoff/task/recovery/original.bin'))
    $items=@();foreach($name in @('old.patch','draft.tmp','copy.report.md')){$p=Pair $root ('data/agent-handoff/task/work/'+$name);$p.kind=switch($name){'old.patch'{'superseded-patch'} 'draft.tmp'{'intermediate'} default{'duplicate-report'}};$p.retainedPath=if($name -eq 'copy.report.md'){$keep[0].path}else{$keep[1].path};$items+=,$p}
    $report=@{taskId='task';taskKind='patch';allRequiredWorkComplete=$true;completionFlag=$true;sourcePatchCompletion='verified';desktopFinalProof='verified';postimages=@($post);preserveFiles=$keep;evidenceFiles=@($log);disposableArtifacts=$items;queueRoots=@('data/agent-handoff/task/work');acceptance=@(@{id='focused';status='passed';exitCode=0;evidence=$log})}
    $report.appliedPatch=$keep[1]
    $request=@{schemaVersion='awx.completed-task-cleanup.v1';taskId='task';taskKind='patch';allRequiredWorkComplete=$true;completionFlag=$true;deleteAuthorized=$true;postimages=@($post);preserveFiles=$keep;evidenceFiles=@($log);disposableArtifacts=$items;queueRoots=$report.queueRoots}
    $f=@{Root=$root;Report=$report;Request=$request};Save $f;return $f
}
function Save($f){Json $f.Root 'data/agent-handoff/task/final/completion.json' $f.Report;$f.Request.completionReport=Pair $f.Root 'data/agent-handoff/task/final/completion.json';Json $f.Root 'request.json' $f.Request}
function Run($f,[switch]$Dry,[string]$Expected=''){$args=@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$helper,'-Root',$f.Root,'-RequestPath','request.json','-LogDirectory','receipts');if($Expected){$args+=@('-ExpectedRequestSha256',$Expected)};if(-not $Dry){$args+='-Apply'};$raw=& powershell.exe @args;$script:code=$LASTEXITCODE;$r=$raw|ConvertFrom-Json;$script:reason=$r.reason;return $r}
function Assert($v){if(-not $v){throw 'assertion-failed'}}
function Held($f){Save $f;$r=Run $f;Assert ($script:code -ne 0 -and $r.taskStatus -ne 'completed');Assert (Test-Path -LiteralPath (Join-Path $f.Root 'data/agent-handoff/task/work/old.patch'))}
function Check($name,[scriptblock]$body){if($Case -and $name -ne $Case){return};$script:reason='fixture';try{& $body;$script:results+=@{test=$name;passed=$true}}catch{$script:results+=@{test=$name;passed=$false;reason=$script:reason}}}
try {
    Check 'verified-task-cleans-surplus-and-preserves-evidence-and-recovery' {
        $f=Fixture;$r=Run $f;Assert ($script:code -eq 0 -and $r.taskStatus -eq 'completed' -and $r.deletedCount -eq 3)
        foreach($p in @($f.Request.postimages)+@($f.Request.preserveFiles)+@($f.Request.evidenceFiles)+@($f.Request.completionReport)){Assert ((Pair $f.Root $p.path).sha256 -eq $p.sha256)}
        $receipt=Get-Content -LiteralPath (Join-Path $f.Root $r.completionStatusRelativePath) -Raw|ConvertFrom-Json
        Assert ($receipt.taskStatus -eq 'completed' -and $receipt.stopWork -eq $true -and $receipt.taskKind -eq 'patch')
        $journal=Get-Content -LiteralPath (Join-Path $f.Root $r.journalRelativePath)|ForEach-Object{$_|ConvertFrom-Json}
        foreach($e in @($journal|Where-Object event -eq 'delete-planned')){Assert ((Pair $f.Root ((Split-Path $r.journalRelativePath -Parent)+'/'+$e.recovery)).sha256 -eq $e.sha256)}
        $r=Run $f;Assert ($script:code -eq 0 -and $r.deletedCount -eq 0 -and $r.alreadyAbsentCount -eq 3)
    }
    Check 'dry-run-has-no-receipts' {$f=Fixture;$r=Run $f -Dry;Assert ($script:code -eq 0 -and -not (Test-Path -LiteralPath (Join-Path $f.Root 'receipts')))}
    Check 'changed-dispatch-request-is-held-before-deletion' {$f=Fixture;$r=Run $f -Expected ('0'*64);Assert ($script:code -ne 0 -and $r.reason -eq 'hash-mismatch' -and -not (Test-Path -LiteralPath (Join-Path $f.Root 'receipts')))}
    Check 'report-only-completion-does-not-claim-patch-proof' {
        $f=Fixture;$f.Report.taskKind='report';$f.Request.taskKind='report';$f.Report.Remove('sourcePatchCompletion');$f.Report.Remove('desktopFinalProof');$f.Report.reportCompletion='verified'
        $f.Request.postimages=@($f.Request.preserveFiles[0]);$f.Request.preserveFiles=@($f.Request.preserveFiles|Select-Object -Skip 1);$f.Request.disposableArtifacts=@($f.Request.disposableArtifacts|Where-Object kind -ne 'superseded-patch')
        foreach($k in @('postimages','preserveFiles','disposableArtifacts')){$f.Report[$k]=$f.Request[$k]};Save $f;$r=Run $f
        Assert ($script:code -eq 0 -and $r.taskStatus -eq 'completed');$s=Get-Content -LiteralPath (Join-Path $f.Root $r.completionStatusRelativePath) -Raw|ConvertFrom-Json;Assert ($s.sourcePatchCompletion -eq 'not_applicable')
    }
    Check 'partial-report-is-held' {$f=Fixture;$f.Report.allRequiredWorkComplete=$false;Held $f}
    Check 'failed-acceptance-is-held' {$f=Fixture;$f.Report.acceptance[0].exitCode=1;Held $f}
    Check 'empty-acceptance-is-held' {$f=Fixture;$f.Report.acceptance=@();Held $f}
    Check 'unbound-acceptance-is-held' {$f=Fixture;$f.Report.acceptance[0].evidence.sha256='0'*64;Held $f}
    Check 'source-drift-is-held' {$f=Fixture;Put $f.Root 'main/source.java' 'new writer';Held $f}
    Check 'evidence-drift-is-held' {$f=Fixture;Put $f.Root 'data/agent-handoff/task/final/verify.log' 'changed log';Held $f}
    Check 'report-does-not-own-candidate-is-held' {$f=Fixture;$f.Report.disposableArtifacts=@();Held $f}
    Check 'source-candidate-is-held' {$f=Fixture;$f.Request.disposableArtifacts[0].path='main/source.java';$f.Request.disposableArtifacts[0].sha256=$f.Request.postimages[0].sha256;Held $f}
    Check 'recovery-candidate-is-held' {$f=Fixture;$f.Request.queueRoots=@('data/agent-handoff/task/recovery');$f.Report.queueRoots=$f.Request.queueRoots;$f.Request.disposableArtifacts[0].path='data/agent-handoff/task/recovery/original.bin';Held $f}
    Check 'unique-report-is-held' {$f=Fixture;Put $f.Root 'data/agent-handoff/task/work/copy.report.md' 'unique findings';$f.Request.disposableArtifacts[2].sha256=(Pair $f.Root $f.Request.disposableArtifacts[2].path).sha256;Held $f}
    Check 'active-patchdrop-is-held' {$f=Fixture;$f.Request.queueRoots=@('__patch_drop__');$f.Report.queueRoots=$f.Request.queueRoots;Held $f}
    Check 'log-as-intermediate-is-held' {$f=Fixture;Put $f.Root 'data/agent-handoff/task/work/other.log' 'verification evidence';$f.Request.disposableArtifacts[1].path='data/agent-handoff/task/work/other.log';$f.Request.disposableArtifacts[1].sha256=(Pair $f.Root $f.Request.disposableArtifacts[1].path).sha256;Held $f}
    Check 'delete-denied-still-stops-completed-task' {$f=Fixture;$path=Join-Path $f.Root $f.Request.disposableArtifacts[0].path;$reader=[IO.File]::Open($path,'Open','Read','Read');try{$r=Run $f}finally{$reader.Dispose()};Assert ($r.taskStatus -eq 'completed' -and $r.status -eq 'hold' -and $r.cleanupState -eq 'cleanup-held')}
} finally {
    $absolute=[IO.Path]::GetFullPath($base);$temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    if($absolute.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path $absolute -Leaf) -match '^awx-task-cleanup-tests-[a-f0-9]{32}$' -and (Test-Path -LiteralPath $absolute)){Remove-Item -LiteralPath $absolute -Recurse -Force}
}
$failed=@($results|Where-Object {-not $_.passed});@{tests=$results.Count;passed=$results.Count-$failed.Count;failed=$failed.Count;failures=$failed}|ConvertTo-Json -Depth 4 -Compress
if($failed.Count){exit 1}
