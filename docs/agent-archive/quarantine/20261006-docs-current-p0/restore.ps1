param([switch]$Apply,[switch]$WhatIf,[string]$Root='C:\AbandonWare\demo-1\demo-1\src')
$ErrorActionPreference='Stop'
$base=[IO.Path]::GetFullPath($Root).TrimEnd('\')
$manifest=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json

$lease=$null
$restoreTopic='docs-restore-'+[guid]::NewGuid().ToString('N')
$restoreOwner='docs-quarantine-restorer'
if($Apply -and -not $WhatIf){
  $targets=@()
  foreach($item in $manifest.items){
    if(Test-Path -LiteralPath (Join-Path $base $item.originalPath)){throw 'original-exists; HOLD'}
    $targets+=@{path=$item.originalPath;sha256=$null}
  }
  $targetFile=Join-Path ([IO.Path]::GetTempPath()) ($restoreTopic+'.json')
  @{targets=$targets} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $targetFile -Encoding utf8
  $targetFile=$targetFile.Replace('\','/')
  $engine=Join-Path $base '__patch_drop__/source_edit_session.ps1'
  $lease=& $engine -Action begin -Root $base -Topic $restoreTopic -OwnerId $restoreOwner -TaskId $restoreTopic -TargetManifest $targetFile -TtlMinutes 10 -Json | ConvertFrom-Json
  if(-not $lease.acquired){throw 'restore-lease-unavailable; HOLD'}
}
try{
if($lease){
  & $engine -Action verify -Root $base -Topic $restoreTopic -OwnerId $restoreOwner -LeaseFingerprint $lease.fingerprint -TargetManifest $targetFile | Out-Null
  if($LASTEXITCODE -ne 0){throw 'restore-lease-verify-failed'}
}
foreach($item in $manifest.items){
  $src=[IO.Path]::GetFullPath((Join-Path $base $item.archivePath))
  $dst=[IO.Path]::GetFullPath((Join-Path $base $item.originalPath))
  foreach($p in @($src,$dst)){ if(-not $p.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'outside-root'} }
  if(Test-Path -LiteralPath $dst){ Write-Output ('HOLD original exists: '+$item.originalPath); continue }
  if(-not (Test-Path -LiteralPath $src)){throw ('missing archive: '+$item.archivePath)}
  if((Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.sha256){throw 'archive-hash-drift'}
  if($Apply -and -not $WhatIf){
    # Acquire/verify target-scoped ownership before -Apply; never overwrite a foreign writer.
    $parent=Split-Path -Parent $dst
    if(-not (Test-Path -LiteralPath $parent)){New-Item -ItemType Directory -Path $parent | Out-Null}
    [IO.File]::Copy($src,$dst,$false)
    if((Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.sha256){throw 'restore-hash-mismatch'}
    Write-Output ('RESTORED '+$item.originalPath+' MATCH; archive retained')
  }else{Write-Output ('WOULD RESTORE '+$item.originalPath+' sha256='+$item.sha256)}
}

}finally{
  if($lease -and $lease.acquired){
    & $engine -Action end -Root $base -Topic $restoreTopic -OwnerId $restoreOwner -LeaseFingerprint $lease.fingerprint | Out-Null
  }
}
