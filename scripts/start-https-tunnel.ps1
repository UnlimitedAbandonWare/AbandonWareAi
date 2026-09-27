[CmdletBinding()]
param(
    [ValidateSet('start','status','stop')][string]$Action='status',
    [ValidateRange(1024,65535)][int]$Port=18080,
    [switch]$InstallClient
)
$ErrorActionPreference='Stop'
$demoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$demoStateRoot=Join-Path $demoRoot 'data\agent-handoff\interview-tunnel'
$demoStateFile=Join-Path $demoStateRoot 'state.json'
function Write-DemoState($Value) {
    [IO.Directory]::CreateDirectory($demoStateRoot) | Out-Null
    if((Get-Item -LiteralPath $demoStateRoot).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'tunnel_state_reparse'}
    $demoTemp=Join-Path $demoStateRoot ('state-'+[Guid]::NewGuid().ToString('N')+'.tmp')
    try {
        [IO.File]::WriteAllText($demoTemp,($Value|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
        if(Test-Path -LiteralPath $demoStateFile){[IO.File]::Replace($demoTemp,$demoStateFile,$null)}else{[IO.File]::Move($demoTemp,$demoStateFile)}
    } finally {if(Test-Path -LiteralPath $demoTemp){Remove-Item -LiteralPath $demoTemp}}
}
function Get-OwnedTunnel($State) {
    if($null -eq $State -or -not $State.pid){return $null}
    $demoProcess=Get-Process -Id ([int]$State.pid) -ErrorAction SilentlyContinue
    if($null -eq $demoProcess){return $null}
    # ConvertFrom-Json may already materialize ISO timestamps as DateTime (PowerShell 7).
    # A second Parse would stringify in the local culture and lose its UTC kind.
    $demoRecordedStart=([DateTime]$State.startedAt).ToUniversalTime()
    if($demoProcess.ProcessName -ne 'cloudflared' -or [Math]::Abs(($demoProcess.StartTime.ToUniversalTime()-$demoRecordedStart).TotalSeconds) -gt 1){throw 'tunnel_process_ownership_changed'}
    if([IO.Path]::GetFullPath($demoProcess.Path) -ne [IO.Path]::GetFullPath($State.executable)){throw 'tunnel_executable_changed'}
    return $demoProcess
}
$demoState=$null
if(Test-Path -LiteralPath $demoStateFile){$demoState=Get-Content -LiteralPath $demoStateFile -Raw -Encoding UTF8 | ConvertFrom-Json}
$demoOwned=Get-OwnedTunnel $demoState
if($Action -eq 'status'){
    [pscustomobject]@{status=$(if($demoOwned){'running'}else{'stopped'});pid=$(if($demoOwned){$demoOwned.Id}else{$null});publicUrl=$(if($demoOwned){$demoState.publicUrl}else{''});port=$(if($demoState){$demoState.port}else{$Port})} | ConvertTo-Json -Compress
    exit 0
}
if($Action -eq 'stop'){
    if($demoOwned){Stop-Process -Id $demoOwned.Id -Force}
    Write-DemoState @{status='stopped';publicUrl='';port=$Port;expiresAt=0}
    Write-Output '{"status":"stopped","ownedProcessOnly":true}'
    exit 0
}
if($demoOwned){
    if([int]$demoState.port -ne $Port){throw 'tunnel_port_conflict'}
    if([long]$demoState.expiresAt -le [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()){throw 'tunnel_receipt_expired_stop_before_restart'}
    [pscustomobject]@{status='running';pid=$demoOwned.Id;publicUrl=$demoState.publicUrl;port=$Port;reused=$true}|ConvertTo-Json -Compress
    exit 0
}
if(-not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)){throw 'local_listener_missing'}
$demoClient=Get-Command cloudflared -ErrorAction SilentlyContinue
$demoBinary=if($demoClient){$demoClient.Source}else{Join-Path $env:LOCALAPPDATA 'AbandonWareX\tools\cloudflared\cloudflared.exe'}
if(-not (Test-Path -LiteralPath $demoBinary)){
    if(-not $InstallClient){throw 'cloudflared_missing_use_InstallClient'}
    # Official release asset plus the digest supplied by its immutable GitHub release record.
    $demoRelease=Invoke-RestMethod -Uri 'https://api.github.com/repos/cloudflare/cloudflared/releases/latest' -Headers @{'User-Agent'='demo1-interview-tunnel'} -TimeoutSec 30
    $demoAsset=@($demoRelease.assets | Where-Object name -eq 'cloudflared-windows-amd64.exe')
    if($demoAsset.Count -ne 1 -or $demoAsset[0].digest -notmatch '^sha256:[a-fA-F0-9]{64}$' -or $demoAsset[0].browser_download_url -notlike 'https://github.com/cloudflare/cloudflared/releases/download/*'){throw 'official_client_digest_unavailable'}
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($demoBinary))|Out-Null
    $demoDownload=$demoBinary+'.download'
    try{
        Invoke-WebRequest -Uri $demoAsset[0].browser_download_url -OutFile $demoDownload -TimeoutSec 60
        if((Get-FileHash -LiteralPath $demoDownload -Algorithm SHA256).Hash.ToLowerInvariant() -ne $demoAsset[0].digest.Substring(7).ToLowerInvariant()){throw 'cloudflared_digest_mismatch'}
        [IO.File]::Move($demoDownload,$demoBinary)
    }finally{if(Test-Path -LiteralPath $demoDownload){Remove-Item -LiteralPath $demoDownload}}
}
[IO.Directory]::CreateDirectory($demoStateRoot)|Out-Null
$demoRun=[Guid]::NewGuid().ToString('N')
$demoOut=Join-Path $demoStateRoot ($demoRun+'.out.log')
$demoErr=Join-Path $demoStateRoot ($demoRun+'.err.log')
$demoChild=$null;$demoRegistered=$false
try{
    $demoChild=Start-Process -FilePath $demoBinary -ArgumentList @('--no-autoupdate','tunnel','--url',"http://127.0.0.1:$Port",'--protocol','http2') -WorkingDirectory $demoStateRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $demoOut -RedirectStandardError $demoErr
    $demoDeadline=[DateTime]::UtcNow.AddSeconds(45);$demoPublic=''
    do{
        $demoChild.Refresh();if($demoChild.HasExited){throw 'tunnel_process_exited'}
        foreach($demoLog in @($demoErr,$demoOut)){
            if(Test-Path -LiteralPath $demoLog){$demoText=(Get-Content -LiteralPath $demoLog -Tail 60 -ErrorAction SilentlyContinue)-join "`n";if($demoText -match 'https://[a-z0-9-]+\.trycloudflare\.com'){$demoPublic=$Matches[0];break}}
        }
        if(-not $demoPublic){Start-Sleep -Milliseconds 250}
    }while(-not $demoPublic -and [DateTime]::UtcNow -lt $demoDeadline)
    if(-not $demoPublic){throw 'tunnel_url_timeout'}
    $demoRecord=@{status='active';publicUrl=$demoPublic;pid=$demoChild.Id;port=$Port;startedAt=$demoChild.StartTime.ToUniversalTime().ToString('o');expiresAt=[DateTimeOffset]::UtcNow.AddHours(4).ToUnixTimeMilliseconds();executable=$demoBinary;clientSha256=(Get-FileHash -LiteralPath $demoBinary -Algorithm SHA256).Hash.ToLowerInvariant();transport='cloudflare-quick-tunnel';proof='url-allocated'}
    Write-DemoState $demoRecord;$demoRegistered=$true
    [pscustomobject]@{status='running';pid=$demoChild.Id;publicUrl=$demoPublic;port=$Port;proof='url-allocated';sseSupported=$false}|ConvertTo-Json -Compress
}finally{
    if(-not $demoRegistered -and $null -ne $demoChild){$demoChild.Refresh();if(-not $demoChild.HasExited){Stop-Process -Id $demoChild.Id -Force}}
}
