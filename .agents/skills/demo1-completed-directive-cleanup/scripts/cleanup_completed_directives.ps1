[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Root,
      [Parameter(Mandatory=$true)][string]$RequestPath,
      [Parameter(Mandatory=$true)][string]$LogDirectory,
      [string]$ExpectedRequestSha256='',
      [switch]$Apply,
      [switch]$MarkOnly)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
$clock=[Diagnostics.Stopwatch]::StartNew()
$utf8=New-Object Text.UTF8Encoding($false,$true)
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
$locks=New-Object 'Collections.Generic.List[object]'
$directories=@{};$files=@{};$journal=$null;$exitCode=2
$taskMode=$false;$preserve=@()
$result=[ordered]@{schemaVersion='awx.completed-directive-cleanup.result.v1';mode=$(if(-not $Apply){'dry-run'}elseif($MarkOnly){'mark-only'}else{'apply'});status='hold';reason='precondition-unverified';plannedCount=0;deletedCount=0;alreadyAbsentCount=0;heldCount=0;markedCount=0;retainedCount=0;directiveStatus='evidence_needed';cleanupState='not-started';requestSha256=$null;journalRelativePath=$null;completionStatusRelativePath=$null;completionMarkdownRelativePath=$null}
function Fail([string]$Code){throw $Code}
function Budget {if($clock.Elapsed.TotalSeconds -ge 120){Fail 'cleanup-deadline'}}
function Has($Object,[string]$Name){return $null -ne $Object.PSObject.Properties[$Name]}
function Require-Object($Object){if($null -eq $Object -or $Object -isnot [Management.Automation.PSCustomObject]){Fail 'invalid-object'}}
function Fields($Object,[string[]]$Required,[string[]]$Optional=@()){
    Require-Object $Object
    foreach($name in $Required){if(-not (Has $Object $name)){Fail 'required-field-missing'}}
    foreach($property in $Object.PSObject.Properties){if($property.Name -cnotin ($Required+$Optional)){Fail 'unknown-field'}}
}
function Sha-Text($Value){if($Value -isnot [string] -or $Value -cnotmatch '^[A-Fa-f0-9]{64}$'){Fail 'invalid-sha256'};return $Value.ToLowerInvariant()}
function Relative($Value,[switch]$Queue){
    if($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 1024){Fail 'invalid-relative-path'}
    if($Queue -and $Value -ceq '.'){return '.'}
    if([IO.Path]::IsPathRooted($Value) -or $Value -match '[:<>"|?*~\x00-\x1f]'){Fail 'unsafe-relative-path'}
    $parts=$Value.Replace('\','/').Split('/')
    foreach($part in $parts){if($part -in @('','.', '..') -or $part -match '[. ]$' -or $part -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?$'){Fail 'unsafe-relative-path'}}
    return ($parts -join '/')
}
function Full([string]$Relative){return [IO.Path]::GetFullPath((Join-Path $script:rootPath $Relative.Replace('/','\')))}
function Under([string]$Path,[string]$Parent){return $Path.Equals($Parent,[StringComparison]::OrdinalIgnoreCase) -or $Path.StartsWith($Parent.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)}
function Argument-Relative([string]$Value){
    $full=if([IO.Path]::IsPathRooted($Value)){[IO.Path]::GetFullPath($Value)}else{Full $Value}
    if(-not (Under $full $script:rootPath) -or $full -eq $script:rootPath){Fail 'argument-outside-root'}
    return Relative $full.Substring($script:rootPath.TrimEnd('\').Length+1)
}
function Hold-Directory([string]$Path){
    Budget
    $key=$Path.ToLowerInvariant();if($directories.ContainsKey($key)){return}
    $handle=[AwxCompletedDirectiveCleanupNativeV1]::Open($Path,$true,$false)
    try{$info=[AwxCompletedDirectiveCleanupNativeV1]::Info($handle);if(($info.Attributes -band 0x400) -ne 0 -or ($info.Attributes -band 0x10) -eq 0){Fail 'reparse-or-directory-risk'};$locks.Add($handle);$directories[$key]=$true}catch{$handle.Dispose();throw}
}
function Hold-Chain([string]$Directory){
    $drive=[IO.Path]::GetPathRoot($Directory);Hold-Directory $drive
    $current=$drive
    foreach($part in $Directory.Substring($drive.Length).Split('\')){if($part){$current=Join-Path $current $part;Hold-Directory $current}}
}
function Stream-Hash($Stream){
    Budget;if($Stream.Length -gt 1048576){Fail 'file-size-limit'};$Stream.Position=0;$sha=[Security.Cryptography.SHA256]::Create()
    try{$value=([BitConverter]::ToString($sha.ComputeHash($Stream))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose();$Stream.Position=0}
    Budget;return $value
}
function Locked-File([string]$Path,[switch]$Delete,[switch]$AllowAbsent){
    Budget;$full=Full $Path;Hold-Chain ([IO.Path]::GetDirectoryName($full))
    if([AwxCompletedDirectiveCleanupNativeV1]::Missing($full)){if($AllowAbsent){return $null};Fail 'evidence-file-missing'}
    $handle=[AwxCompletedDirectiveCleanupNativeV1]::Open($full,$false,[bool]$Delete)
    try{
        $info=[AwxCompletedDirectiveCleanupNativeV1]::Info($handle)
        if(($info.Attributes -band 0x410) -ne 0){Fail 'reparse-or-directory-risk'}
        if($info.Links -ne 1){Fail 'hardlink-risk'}
        $stream=New-Object IO.FileStream($handle,[IO.FileAccess]::Read)
        try{$hash=Stream-Hash $stream}catch{$stream.Dispose();throw}
        $row=[pscustomobject]@{path=$Path;fullPath=$full;stream=$stream;hash=$hash;identity=[AwxCompletedDirectiveCleanupNativeV1]::Identity($handle)}
        $locks.Add($stream);$files[$Path.ToLowerInvariant()]=$row;return $row
    }catch{$handle.Dispose();throw}
}
function Read-Json($Row){
    $bytes=New-Object byte[] ([int]$Row.stream.Length);$Row.stream.Position=0;$offset=0
    while($offset -lt $bytes.Length){$read=$Row.stream.Read($bytes,$offset,$bytes.Length-$offset);if($read -le 0){Fail 'short-evidence-read'};$offset+=$read}
    $Row.stream.Position=0
    try{$value=$utf8.GetString($bytes).TrimStart([char]0xfeff)|ConvertFrom-Json -ErrorAction Stop}catch{Fail 'invalid-json'}
    Require-Object $value;return $value
}
function Pair($Value){Fields $Value @('path','sha256');return [pscustomobject]@{path=(Relative $Value.path);sha256=(Sha-Text $Value.sha256)}}
function Pairs($Value){if($Value -isnot [Array]){Fail 'array-required'};foreach($item in $Value){Pair $item}}
function Check-Hash($Row,[string]$Expected){if($null -eq $Row -or $Row.hash -cne $Expected){Fail 'hash-mismatch'}}
function Task-Artifacts($Value){
    if($Value -isnot [Array]){Fail 'array-required'}
    foreach($item in $Value){
        Fields $item @('path','sha256','kind','retainedPath')
        if($item.kind -cnotin @('temporary-patch','superseded-patch','intermediate','duplicate-report')){Fail 'artifact-kind-invalid'}
        [pscustomobject]@{path=(Relative $item.path);sha256=(Sha-Text $item.sha256);kind=$item.kind;retainedPath=(Relative $item.retainedPath)}
    }
}
function Match-Rows($Actual,$Expected,[switch]$Artifacts){
    $rows=@(if($Artifacts){Task-Artifacts $Actual}else{Pairs $Actual})
    if($rows.Count -ne $Expected.Count){Fail 'report-artifact-set-mismatch'}
    $map=@{};foreach($row in $rows){$key=$row.path.ToLowerInvariant();if($map.ContainsKey($key)){Fail 'duplicate-report-reference'};$map[$key]=$row}
    foreach($row in $Expected){
        $key=$row.path.ToLowerInvariant();if(-not $map.ContainsKey($key) -or $map[$key].sha256 -cne $row.sha256){Fail 'report-artifact-set-mismatch'}
        if($Artifacts -and ($map[$key].kind -cne $row.kind -or $map[$key].retainedPath -ine $row.retainedPath)){Fail 'report-artifact-set-mismatch'}
    }
}
function Task-Protected([string]$Path){
    if($Path -notmatch '^(data/agent-handoff/[^/]+/|__patch_drop__/(superseded|applied)/[^/]+/)'){return $true}
    foreach($part in $Path.Split('/')){
        if($part -match '(^|[-_.])(source|original|recovery|before|backup|archive|receipt|evidence|final|secrets?|locks?)(s)?([-_.]|$)' -or $part -in @('.git','.agents','skills','agents')){return $true}
    }
    $leaf=($Path.Split('/'))[-1]
    return $leaf -match '^(AGENTS|SKILL|README|COMPLETED|completion-status|manifest|checkpoint|journal)(\..*)?$' -or $leaf -match '(\.verify\.log|\.sha256(\.txt)?|\.manifest\.json|\.bin|\.lock|\.log)$'
}
function Protected([string]$Path){
    $parts=$Path.Split('/');$leaf=$parts[-1]
    foreach($part in $parts){if($part.ToLowerInvariant() -in @('.git','.agents','skills','agents','__patch_drop__') -or $part -match '^(template|backup|archive)'){return $true}}
    return $leaf -match '^(AGENTS|SKILL|README)(\..*)?$' -or $leaf -match '\.report\.md$' -or $leaf -match '(^|[-_.])(backup|archive|template)s?([-_.]|$)'
}
function Journal($Value){
    Budget
    try{$bytes=$utf8.GetBytes(($Value|ConvertTo-Json -Depth 12 -Compress)+"`n");$journal.Write($bytes,0,$bytes.Length);$journal.Flush($true)}catch{Fail 'journal-write-failed'}
}
function Write-StatusFile($Stream,[string]$Text){
    Budget
    try{$bytes=$utf8.GetBytes($Text);if($bytes.Length -gt 1048576){Fail 'status-size-limit'};$Stream.Position=0;$Stream.Write($bytes,0,$bytes.Length);$Stream.SetLength($bytes.Length);$Stream.Flush($true)}catch{Fail 'completion-status-write-failed'}
}
function Write-Completion([string]$CleanupState){
    $notice=[ordered]@{
        schemaVersion='awx.completed-directive-status.v1';directiveStatus='completed';doNotReapply=$true
        sourcePatchCompletion='verified';desktopFinalProof='verified';cleanupStateAtPublication=$CleanupState
        directive=$main;targets=@($targets);completionReport=$reportPair;postimages=@($posts);evidenceFiles=@($evidence)
        request=[ordered]@{path=$requestRel;sha256=$requestFile.hash};journalRelativePath=$result.journalRelativePath
        nextAction=$(if($CleanupState -in @('pending','cleanup-held')){'cleanup-only'}else{'none'})
        updatedAtUtc=[DateTime]::UtcNow.ToString('o')
    }
    if($taskMode){
        $notice.schemaVersion='awx.completed-task-status.v1';$notice.directiveStatus='not_applicable';$notice.directive=$null
        $notice.taskId=$request.taskId;$notice.taskKind=$request.taskKind;$notice.taskStatus='completed';$notice.stopWork=$true
        $notice.preserveFiles=@($preserve);$notice.disposableArtifacts=@($artifacts)
        if($request.taskKind -ceq 'report'){$notice.sourcePatchCompletion='not_applicable';$notice.desktopFinalProof='not_applicable';$notice.reportCompletion='verified'}
    }
    Write-StatusFile $statusStream ($notice|ConvertTo-Json -Depth 12)
    $label=[Security.SecurityElement]::Escape($main.path)
    if($taskMode){$label=[Security.SecurityElement]::Escape($request.taskId)}
    $markdown="# COMPLETED - Desktop verification recorded`n`nDirective: <code>$label</code>`n`n"+
        "directiveStatus=completed`n`ndoNotReapply=true`n`ncleanupStateAtPublication=$CleanupState`n`n"+
        "The exact directive, report, source postimages and acceptance hashes are in [completion-status.json](completion-status.json).`n`n"+
        "This is a completion receipt, not an executable directive. Preserve the original directive bytes. " +
        "On the next intake, validate those bindings before skipping source work. A changed directive or failed proof needs reassessment. " +
        "This receipt is immutable. Read [journal.jsonl](journal.jsonl) for the current cleanup outcome. " +
        "If cleanup is pending or held, retry only cleanup after its condition changes.`n"
    if($taskMode){$markdown="# COMPLETED - task verification recorded`n`nTask: <code>$label</code>`n`ntaskStatus=completed`n`nstopWork=true`n`n"+
        "Task kind: $($request.taskKind). Preserve the final report, evidence and recovery files bound in [completion-status.json](completion-status.json). " +
        "Read [journal.jsonl](journal.jsonl) for cleanup outcome. Revalidate current proof before skipping work; retry cleanup only after its blocking condition changes.`n"}
    Write-StatusFile $markdownStream $markdown
}
function Ensure-Directory([string]$Path){
    if([IO.Directory]::Exists($Path)){Hold-Chain $Path;return}
    if([IO.File]::Exists($Path)){Fail 'log-path-not-directory'}
    $parent=[IO.Path]::GetDirectoryName($Path);if(-not (Under $parent $script:rootPath)){Fail 'log-outside-root'}
    Ensure-Directory $parent;[IO.Directory]::CreateDirectory($Path)|Out-Null;Hold-Directory $Path
}
try{
    if($env:OS -ne 'Windows_NT'){Fail 'windows-required'}
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class AwxCompletedDirectiveCleanupNativeV1 {
 [StructLayout(LayoutKind.Sequential)] public struct FileInfo { public uint Attributes,CL,CH,AL,AH,WL,WH,Volume,SizeHigh,SizeLow,Links,IndexHigh,IndexLow; }
 [StructLayout(LayoutKind.Sequential)] struct Disposition { [MarshalAs(UnmanagedType.U1)] public bool Delete; }
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern SafeFileHandle CreateFileW(string p,uint a,uint s,IntPtr security,uint c,uint f,IntPtr template);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool GetFileInformationByHandle(SafeFileHandle h,out FileInfo info);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetFileInformationByHandle(SafeFileHandle h,int cls,ref Disposition info,uint size);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern uint GetFileAttributesW(string path);
 public static bool Missing(string path) {
  if(GetFileAttributesW(path)!=0xffffffffu)return false;
  int error=Marshal.GetLastWin32Error();if(error==2||error==3)return true;
  throw new IOException("native-absence-unproven");
 }
 public static SafeFileHandle Open(string path,bool directory,bool delete) {
  var h=CreateFileW(path,directory?0x80u:(0x80000000u|(delete?0x10000u:0u)),directory?3u:1u,IntPtr.Zero,3,0x200000u|(directory?0x2000000u:0u),IntPtr.Zero);
  if(h.IsInvalid){h.Dispose();throw new IOException("native-open-failed");} return h;
 }
 public static FileInfo Info(SafeFileHandle h) { FileInfo i;if(!GetFileInformationByHandle(h,out i))throw new IOException("native-info-failed");return i; }
 public static string Identity(SafeFileHandle h){var i=Info(h);return i.Volume.ToString("X8")+i.IndexHigh.ToString("X8")+i.IndexLow.ToString("X8");}
 public static void Delete(SafeFileHandle h){var d=new Disposition{Delete=true};if(!SetFileInformationByHandle(h,4,ref d,1))throw new IOException("native-delete-failed");}
}
'@ -ErrorAction Stop
    $script:rootPath=[IO.Path]::GetFullPath($Root).TrimEnd('\');if($script:rootPath -match '^[A-Za-z]:$'){$script:rootPath+='\'}
    if(-not [IO.Directory]::Exists($script:rootPath)){Fail 'root-missing'};Hold-Chain $script:rootPath
    $requestRel=Argument-Relative $RequestPath;$logRel=Argument-Relative $LogDirectory;$logFull=Full $logRel
    $requestFile=Locked-File $requestRel;$result.requestSha256=$requestFile.hash
    if($ExpectedRequestSha256){Check-Hash $requestFile (Sha-Text $ExpectedRequestSha256)}
    $request=Read-Json $requestFile
    $taskMode=(Has $request 'schemaVersion') -and $request.schemaVersion -ceq 'awx.completed-task-cleanup.v1'
    if($taskMode){
        Fields $request @('schemaVersion','taskId','taskKind','deleteAuthorized','allRequiredWorkComplete','completionFlag','completionReport','postimages','queueRoots','disposableArtifacts','preserveFiles','evidenceFiles')
        $result.schemaVersion='awx.completed-task-cleanup.result.v1';$result.taskStatus='evidence_needed';$result.stopWork=$false;$result.directiveStatus='not_applicable'
        if($request.taskId -isnot [string] -or $request.taskId -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$' -or $request.taskKind -cnotin @('patch','report')){Fail 'task-identity-invalid'}
    }else{
        Fields $request @('schemaVersion','deleteAuthorized','allRequiredWorkComplete','completionFlag','directive','completionReport','postimages','queueRoots') @('relatedMarkdown','evidenceFiles')
        if($request.schemaVersion -isnot [string] -or $request.schemaVersion -cne 'awx.completed-directive-cleanup.v1'){Fail 'request-schema-invalid'}
    }
    foreach($name in @('allRequiredWorkComplete','completionFlag')){if($request.$name -isnot [bool] -or $request.$name -ne $true){Fail 'completion-authorization-missing'}}
    if($request.deleteAuthorized -isnot [bool] -or (-not $MarkOnly -and -not $request.deleteAuthorized)){Fail 'completion-authorization-missing'}
    if($taskMode){
        $main=Pair $request.completionReport;$artifacts=@(Task-Artifacts $request.disposableArtifacts)
        $targets=@($artifacts|ForEach-Object{[pscustomobject]@{path=$_.path;sha256=$_.sha256}});$preserve=@(Pairs $request.preserveFiles)
    }else{$main=Pair $request.directive;$targets=@($main);if(Has $request 'relatedMarkdown'){$targets+=@(Pairs $request.relatedMarkdown)}}
    $reportPair=Pair $request.completionReport;$posts=@(Pairs $request.postimages);$evidence=@();if(Has $request 'evidenceFiles'){$evidence=@(Pairs $request.evidenceFiles)}
    if($posts.Count -eq 0 -or 2+$targets.Count+$posts.Count+$evidence.Count+$preserve.Count -gt 512){Fail 'file-count-limit'}
    if($taskMode -and $evidence.Count -eq 0){Fail 'acceptance-evidence-required'}
    if($request.queueRoots -isnot [Array] -or $request.queueRoots.Count -eq 0 -or $request.queueRoots.Count -gt 512){Fail 'queue-roots-invalid'}
    $queues=@();$queueKeys=@{}
    foreach($value in $request.queueRoots){
        $q=Relative $value -Queue;if($queueKeys.ContainsKey($q.ToLowerInvariant())){Fail 'duplicate-queue-root'};$queueKeys[$q.ToLowerInvariant()]=$true
        if($taskMode -and (Task-Protected ($q+'/candidate.tmp'))){Fail 'task-artifact-root-protected'}
        if($q -eq '.'){
            if($script:rootPath.TrimEnd('\') -eq [IO.Path]::GetPathRoot($script:rootPath).TrimEnd('\')){Fail 'root-queue-requires-project-directory'}
            $driveName=[IO.Path]::GetPathRoot($script:rootPath).TrimEnd('\').TrimEnd(':');$drive=Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue
            if($script:rootPath.StartsWith('\\') -or $null -eq $drive -or -not [string]::IsNullOrEmpty([string]$drive.DisplayRoot)){Fail 'root-queue-requires-local-root'}
        }else{if(Under $logFull (Full $q)){Fail 'log-inside-queue'}}
        $queues+=,$q
    }
    $seen=@{};$all=@([pscustomobject]@{path=$requestRel})+@($targets)+@($reportPair)+@($posts)+@($evidence)+@($preserve)
    foreach($pair in $all){$key=$pair.path.ToLowerInvariant();if($seen.ContainsKey($key)){Fail 'duplicate-or-overlapping-reference'};$seen[$key]=$true;if(Under (Full $pair.path) $logFull){Fail 'log-reference-overlap'}}
    if([IO.Path]::GetExtension($requestRel) -ine '.json' -or [IO.Path]::GetExtension($reportPair.path) -ine '.json'){Fail 'json-path-required'}
    foreach($target in $targets){
        if($taskMode){if(Task-Protected $target.path){Fail 'protected-task-artifact'}}
        elseif([IO.Path]::GetExtension($target.path) -ine '.md' -or (Protected $target.path)){Fail 'protected-or-non-markdown-target'}
        $matched=$false;foreach($q in $queues){if($q -eq '.' -or (Under (Full $target.path) (Full $q))){$matched=$true}}
        if(-not $matched){Fail 'target-outside-declared-queue'}
    }
    $result.plannedCount=$targets.Count
    $reportFile=Locked-File $reportPair.path;Check-Hash $reportFile $reportPair.sha256;$report=Read-Json $reportFile
    $requiredReport=if($taskMode){@('taskId','taskKind','postimages','allRequiredWorkComplete','completionFlag','preserveFiles','disposableArtifacts','evidenceFiles','queueRoots','acceptance')}else{@('sourcePatchCompletion','desktopFinalProof','directive','postimages')}
    foreach($field in $requiredReport){if(-not (Has $report $field)){Fail 'completion-report-incomplete'}}
    foreach($field in @('allRequiredWorkComplete','completionFlag')){if((Has $report $field) -and ($report.$field -isnot [bool] -or $report.$field -ne $true)){Fail 'completion-report-contradiction'}}
    if($taskMode){
        if($report.taskId -cne $request.taskId -or $report.taskKind -cne $request.taskKind){Fail 'task-report-identity-mismatch'}
        if($request.taskKind -ceq 'patch'){
            if(-not (Has $report 'sourcePatchCompletion') -or -not (Has $report 'desktopFinalProof') -or $report.sourcePatchCompletion -cne 'verified' -or $report.desktopFinalProof -cne 'verified'){Fail 'whole-task-proof-missing'}
        }elseif(-not (Has $report 'reportCompletion') -or $report.reportCompletion -cne 'verified'){Fail 'whole-task-proof-missing'}
        Match-Rows $report.preserveFiles $preserve;Match-Rows $report.evidenceFiles $evidence;Match-Rows $report.disposableArtifacts $artifacts -Artifacts
        if($report.queueRoots -isnot [Array] -or $report.queueRoots.Count -ne $queues.Count){Fail 'report-queue-mismatch'}
        $reportQueues=@{};foreach($q in $report.queueRoots){$key=(Relative $q).ToLowerInvariant();if($reportQueues.ContainsKey($key) -or -not $queueKeys.ContainsKey($key)){Fail 'report-queue-mismatch'};$reportQueues[$key]=$true}
        $retained=@{};foreach($p in @($preserve)+@($posts)+@($reportPair)){$retained[$p.path.ToLowerInvariant()]=$p.sha256}
        foreach($item in $artifacts){
            $ext=[IO.Path]::GetExtension($item.path).ToLowerInvariant();$key=$item.retainedPath.ToLowerInvariant()
            if(-not $retained.ContainsKey($key)){Fail 'retained-replacement-required'}
            switch($item.kind){
                'duplicate-report' {if($ext -notin @('.md','.txt','.json') -or $retained[$key] -cne $item.sha256){Fail 'report-not-identical-duplicate'}}
                'intermediate' {if($ext -notin @('.tmp','.temp','.scratch','.draft')){Fail 'intermediate-type-protected'}}
                default {
                    if($request.taskKind -cne 'patch' -or $ext -ne '.patch' -or -not (Has $report 'appliedPatch')){Fail 'applied-patch-proof-required'}
                    $applied=Pair $report.appliedPatch
                    if($applied.path -ine $item.retainedPath -or $applied.sha256 -cne $retained[$key] -or [IO.Path]::GetExtension($applied.path) -ine '.patch'){Fail 'applied-patch-proof-required'}
                }
            }
        }
        if($report.acceptance -isnot [Array] -or $report.acceptance.Count -eq 0 -or $report.acceptance.Count -gt 512){Fail 'required-acceptance-missing'}
        $accepted=@{};$boundEvidence=@{};foreach($p in $evidence){$boundEvidence[$p.path.ToLowerInvariant()]=$p.sha256}
        foreach($check in $report.acceptance){
            Fields $check @('id','status','exitCode','evidence')
            if($check.id -isnot [string] -or $check.id -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$' -or $accepted.ContainsKey($check.id) -or $check.status -cne 'passed' -or $check.exitCode -isnot [int] -or $check.exitCode -ne 0){Fail 'required-acceptance-failed'}
            $accepted[$check.id]=$true;$p=Pair $check.evidence;$key=$p.path.ToLowerInvariant()
            if(-not $boundEvidence.ContainsKey($key) -or $boundEvidence[$key] -cne $p.sha256){Fail 'acceptance-evidence-unbound'}
        }
    }elseif($report.sourcePatchCompletion -isnot [string] -or $report.sourcePatchCompletion -cne 'verified' -or $report.desktopFinalProof -isnot [string] -or $report.desktopFinalProof -cne 'verified' -or (Relative $report.directive) -ine $main.path){Fail 'whole-task-proof-missing'}
    if($report.postimages -isnot [Array] -or $report.postimages.Count -ne $posts.Count){Fail 'report-postimage-set-mismatch'}
    $reportPosts=@{}
    foreach($row in $report.postimages){
        Require-Object $row;if(-not (Has $row 'path')){Fail 'report-postimage-set-mismatch'};$path=Relative $row.path;$key=$path.ToLowerInvariant()
        if($reportPosts.ContainsKey($key)){Fail 'report-postimage-set-mismatch'}
        $hash=if(Has $row 'postimage'){Sha-Text $row.postimage}elseif(Has $row 'sha256'){Sha-Text $row.sha256}else{Fail 'report-postimage-set-mismatch'}
        if((Has $row 'postimage') -and (Has $row 'sha256') -and (Sha-Text $row.sha256) -cne $hash){Fail 'report-postimage-set-mismatch'};$reportPosts[$key]=$hash
    }
    foreach($post in $posts){if(-not $reportPosts.ContainsKey($post.path.ToLowerInvariant()) -or $reportPosts[$post.path.ToLowerInvariant()] -cne $post.sha256){Fail 'report-postimage-set-mismatch'}}
    foreach($pair in @($posts)+@($evidence)+@($preserve)){$row=Locked-File $pair.path;Check-Hash $row $pair.sha256;if($taskMode -and $row.stream.Length -eq 0){Fail 'empty-completion-evidence'}}
    # Completion proof needs read access only. DELETE access is acquired per item after durable marking.
    $openTargets=@();$absentTargets=@();foreach($target in $targets){$row=Locked-File $target.path -AllowAbsent;if($null -eq $row){$result.alreadyAbsentCount++;$absentTargets+=,$target}else{Check-Hash $row $target.sha256;$openTargets+=,$row}}
    if(-not $Apply){$result.status='validated';$result.reason='dry-run-verified';$exitCode=0}
    else{
        Ensure-Directory $logFull;$runDir=Join-Path $logFull ('cleanup-'+$requestFile.hash.Substring(0,16)+'-'+[guid]::NewGuid().ToString('N'));Ensure-Directory $runDir
        # Inherit the existing proof directory ACL, including its actual SMB principal.
        $journalPath=Join-Path $runDir 'journal.jsonl';$journal=New-Object IO.FileStream($journalPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read,4096,[IO.FileOptions]::WriteThrough)
        $result.journalRelativePath=$journalPath.Substring($script:rootPath.TrimEnd('\').Length+1).Replace('\','/')
        Journal ([ordered]@{event='planned';requestSha256=$requestFile.hash;reportSha256=$reportFile.hash;targets=@($targets);alreadyAbsentCount=$result.alreadyAbsentCount})
        foreach($target in $absentTargets){Journal ([ordered]@{event='already_absent';path=$target.path;sha256=$target.sha256})}
        foreach($row in $files.Values){if((Stream-Hash $row.stream) -cne $row.hash){Fail 'changed-proof-before-delete'}}
        $statusPath=Join-Path $runDir 'completion-status.json';$markdownPath=Join-Path $runDir 'COMPLETED.md'
        $statusStream=New-Object IO.FileStream($statusPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read,4096,[IO.FileOptions]::WriteThrough);$locks.Add($statusStream)
        $markdownStream=New-Object IO.FileStream($markdownPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read,4096,[IO.FileOptions]::WriteThrough);$locks.Add($markdownStream)
        Write-Completion $(if($MarkOnly){'retained'}else{'pending'})
        $result.completionStatusRelativePath=$statusPath.Substring($script:rootPath.TrimEnd('\').Length+1).Replace('\','/')
        $result.completionMarkdownRelativePath=$markdownPath.Substring($script:rootPath.TrimEnd('\').Length+1).Replace('\','/')
        $result.cleanupState=$(if($MarkOnly){'retained'}else{'pending'})
        Journal ([ordered]@{event='completion-recorded';directiveStatus=$(if($taskMode){'not_applicable'}else{'completed'});taskStatus=$(if($taskMode){'completed'}else{'not_applicable'});statusRelativePath=$result.completionStatusRelativePath;statusSha256=(Stream-Hash $statusStream);markdownRelativePath=$result.completionMarkdownRelativePath;markdownSha256=(Stream-Hash $markdownStream)})
        $result.directiveStatus='completed';$result.markedCount=$targets.Count
        if($taskMode){$result.directiveStatus='not_applicable';$result.taskStatus='completed';$result.stopWork=$true;$result.taskId=$request.taskId;$result.taskKind=$request.taskKind}
        if($MarkOnly){
            $result.retainedCount=$openTargets.Count;$result.status='complete';$result.reason='verified-directives-marked';$exitCode=0
            Journal ([ordered]@{event='finished';status='complete';cleanupState='retained';mode='mark-only';retainedCount=$result.retainedCount;deletedCount=0})
        }else{
        $index=0
        foreach($row in $openTargets){
            Budget;$index++;$backupName=('{0:D4}-{1}.bin' -f $index,$row.hash.Substring(0,16));$backup=Join-Path $runDir $backupName;$stage='inspect'
            try{
                # Close our read handle, then rebind DELETE to the same bytes and file identity.
                # Another reader may deny DELETE; the completion receipt must survive that failure.
                $stage='delete-open';$row.stream.Dispose();$deleteRow=Locked-File $row.path -Delete
                if($deleteRow.hash -cne $row.hash -or $deleteRow.identity -cne $row.identity){Fail 'target-changed-before-delete'}
                $row=$deleteRow;$stage='inspect'
                $info=[AwxCompletedDirectiveCleanupNativeV1]::Info($row.stream.SafeFileHandle)
                if($info.Links -ne 1 -or ($info.Attributes -band 0x410) -ne 0 -or [AwxCompletedDirectiveCleanupNativeV1]::Identity($row.stream.SafeFileHandle) -cne $row.identity -or (Stream-Hash $row.stream) -cne $row.hash){Fail 'target-changed-before-delete'}
                $stage='recovery';$output=New-Object IO.FileStream($backup,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read,4096,[IO.FileOptions]::WriteThrough)
                try{
                    $stage='recovery-copy';$row.stream.Position=0;$row.stream.CopyTo($output);$stage='recovery-flush';$output.Flush($true)
                    $stage='recovery-hash';if((Stream-Hash $output) -cne $row.hash){Fail 'recovery-hash-mismatch'}
                    $locks.Add($output);$output=$null
                }finally{if($null -ne $output){$output.Dispose()};$row.stream.Position=0}
                Journal ([ordered]@{event='delete-planned';path=$row.path;sha256=$row.hash;recovery=$backupName})
                $stage='native-delete';[AwxCompletedDirectiveCleanupNativeV1]::Delete($row.stream.SafeFileHandle);$row.stream.Dispose()
                if(-not [AwxCompletedDirectiveCleanupNativeV1]::Missing($row.fullPath)){Fail 'delete-absence-unproven'}
                $result.deletedCount++;Journal ([ordered]@{event='deleted';path=$row.path;sha256=$row.hash})
            }catch{
                $exception=$_.Exception;while($null -ne $exception.InnerException){$exception=$exception.InnerException}
                $reason=$exception.Message;if($reason -notmatch '^[a-z0-9-]{3,80}$'){$reason='item-'+$stage+'-failed'}
                if($reason -eq 'journal-write-failed' -or $reason -eq 'cleanup-deadline'){throw}
                $result.heldCount++;Journal ([ordered]@{event='held';path=$row.path;reason=$reason})
            }
        }
        $result.retainedCount=$result.heldCount
        $result.cleanupState=$(if($result.heldCount -eq 0){'deleted'}else{'cleanup-held'})
        if($result.heldCount -eq 0){$result.status='complete';$result.reason='verified-directives-cleaned';$exitCode=0}else{$result.reason='item-cleanup-held';$exitCode=3}
        Journal ([ordered]@{event='finished';deletedCount=$result.deletedCount;alreadyAbsentCount=$result.alreadyAbsentCount;heldCount=$result.heldCount;status=$result.status;cleanupState=$result.cleanupState})
        }
    }
}catch{
    $reason=$_.Exception.Message;if($reason -notmatch '^[a-z0-9-]{3,80}$'){$reason='cleanup-io-failure'}
    $result.status='hold';$result.reason=$reason;$result.heldCount=[Math]::Max(1,$result.plannedCount-$result.deletedCount-$result.alreadyAbsentCount);$exitCode=2
}finally{
    if($null -ne $journal){try{$journal.Dispose()}catch{}}
    for($i=$locks.Count-1;$i -ge 0;$i--){try{$locks[$i].Dispose()}catch{}}
}
$result|ConvertTo-Json -Depth 5 -Compress
exit $exitCode
