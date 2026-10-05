# agy_auth_switch.ps1 - save/list/switch the Windows Credential Manager
# credential that agy (Antigravity CLI) uses (default target: gemini:antigravity).
#
# Saved copies are DPAPI(CurrentUser)-encrypted under StoreDir, so only this
# Windows user on this machine can open them. Raw credential material stays in
# memory only: console/log output never carries the blob, its length, or a hash.
# The account name (e-mail) is printed only with -ShowAccount, masked.
#
# Exit codes: 0 ok | 1 unhandled | 2 bad name | 3 refused/empty/failed |
#             4 post-write verify MISMATCH
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('status','list','save','use','login','forget')]
    [string]$Action,
    [string]$Name,
    [string]$Target = 'gemini:antigravity',
    [string]$StoreDir = (Join-Path $env:LOCALAPPDATA 'awx-agy-auth'),
    [string]$ProcessName = 'agy',
    [switch]$Force,
    [switch]$ShowAccount
)

$ErrorActionPreference = 'Stop'

function Out([string]$m) { Write-Output "[agy-auth] $m" }

function Set-AgyPresence {
    # Presence SSOT (data/agent-handoff/agy-presence.json) — never fatal.
    param([string]$Status, [string]$Account)
    try {
        $py = Join-Path $PSScriptRoot 'agy_presence.py'
        if (Test-Path -LiteralPath $py) {
            $argz = @('-B', $py, 'set', '--status', $Status)
            if ($Account) { $argz += @('--account', $Account) }
            & python @argz *>$null
        }
    } catch { }
}

if (-not ('AgyAuthNative' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

[StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
public struct AgyAuthCredentialW {
    public uint Flags;
    public uint Type;
    public string TargetName;
    public string Comment;
    public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
    public uint CredentialBlobSize;
    public IntPtr CredentialBlob;
    public uint Persist;
    public uint AttributeCount;
    public IntPtr Attributes;
    public string TargetAlias;
    public string UserName;
}

public static class AgyAuthNative {
    public const uint CRED_TYPE_GENERIC = 1;

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool CredReadW(string target, uint type, uint flags, out IntPtr cred);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool CredWriteW(ref AgyAuthCredentialW cred, uint flags);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool CredDeleteW(string target, uint type, uint flags);

    [DllImport("advapi32.dll")]
    public static extern void CredFree(IntPtr buffer);
}
'@
}
Add-Type -AssemblyName System.Security

function Read-TargetCred {
    param([string]$TargetName)
    $ptr = [IntPtr]::Zero
    $ok = [AgyAuthNative]::CredReadW($TargetName, [AgyAuthNative]::CRED_TYPE_GENERIC, 0, [ref]$ptr)
    if (-not $ok) {
        $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
        return @{ Found = $false; Error = $err }
    }
    try {
        $c = [System.Runtime.InteropServices.Marshal]::PtrToStructure($ptr, [type][AgyAuthCredentialW])
        $len = [int]$c.CredentialBlobSize
        $blob = New-Object byte[] $len
        if ($len -gt 0) {
            [System.Runtime.InteropServices.Marshal]::Copy($c.CredentialBlob, $blob, 0, $len)
        }
        return @{
            Found = $true; Error = 0; Blob = $blob
            UserName = [string]$c.UserName; Persist = [uint32]$c.Persist
            Type = [uint32]$c.Type; Comment = [string]$c.Comment
        }
    } finally {
        [AgyAuthNative]::CredFree($ptr)
    }
}

function Write-TargetCred {
    param([string]$TargetName, [byte[]]$Blob, [string]$UserName,
          [uint32]$Persist, [uint32]$Type, [string]$Comment)
    $blobPtr = [IntPtr]::Zero
    if ($Blob -and $Blob.Length -gt 0) {
        $blobPtr = [System.Runtime.InteropServices.Marshal]::AllocHGlobal($Blob.Length)
        [System.Runtime.InteropServices.Marshal]::Copy($Blob, 0, $blobPtr, $Blob.Length)
    }
    try {
        $c = New-Object AgyAuthCredentialW
        $c.Flags = 0
        $c.Type = $Type
        $c.TargetName = $TargetName
        $c.Comment = $Comment
        $c.CredentialBlobSize = [uint32]($(if ($Blob) { $Blob.Length } else { 0 }))
        $c.CredentialBlob = $blobPtr
        $c.Persist = $Persist
        $c.AttributeCount = 0
        $c.Attributes = [IntPtr]::Zero
        $c.TargetAlias = $null
        $c.UserName = $UserName
        $ok = [AgyAuthNative]::CredWriteW([ref]$c, 0)
        if (-not $ok) { return [System.Runtime.InteropServices.Marshal]::GetLastWin32Error() }
        return 0
    } finally {
        if ($blobPtr -ne [IntPtr]::Zero) {
            [System.Runtime.InteropServices.Marshal]::FreeHGlobal($blobPtr)
        }
    }
}

function Remove-TargetCred {
    param([string]$TargetName)
    $ok = [AgyAuthNative]::CredDeleteW($TargetName, [AgyAuthNative]::CRED_TYPE_GENERIC, 0)
    if (-not $ok) { return [System.Runtime.InteropServices.Marshal]::GetLastWin32Error() }
    return 0
}

function Bytes-Equal {
    param([byte[]]$a, [byte[]]$b)
    if ($null -eq $a -or $null -eq $b) { return ($null -eq $a -and $null -eq $b) }
    if ($a.Length -ne $b.Length) { return $false }
    for ($i = 0; $i -lt $a.Length; $i++) { if ($a[$i] -ne $b[$i]) { return $false } }
    return $true
}

function Mask-Account([string]$s) {
    if ([string]::IsNullOrEmpty($s)) { return '' }
    if ($s.Contains('@')) {
        $p = $s.Split('@', 2)
        return ($p[0].Substring(0, 1) + '***@' + $p[1])
    }
    return ($s.Substring(0, 1) + '***')
}

function Protect-Record {
    param([hashtable]$Rec)
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([byte[]][System.Text.Encoding]::ASCII.GetBytes('AGYAUTH1'))
    $u  = [System.Text.Encoding]::UTF8.GetBytes([string]$Rec.UserName)
    $cm = [System.Text.Encoding]::UTF8.GetBytes([string]$Rec.Comment)
    $bw.Write([uint32]$u.Length);  $bw.Write([byte[]]$u)
    $bw.Write([uint32]$cm.Length); $bw.Write([byte[]]$cm)
    $bw.Write([uint32]$Rec.Type)
    $bw.Write([uint32]$Rec.Persist)
    $bw.Write([uint32]$Rec.Blob.Length); $bw.Write([byte[]]$Rec.Blob)
    $bw.Flush()
    $plain = $ms.ToArray()
    $bw.Dispose(); $ms.Dispose()
    return ,[System.Security.Cryptography.ProtectedData]::Protect(
        $plain, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
}

function Unprotect-Record {
    param([string]$Path)
    try {
        $cipher = [System.IO.File]::ReadAllBytes($Path)
        $plain = [System.Security.Cryptography.ProtectedData]::Unprotect(
            $cipher, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
        $ms = New-Object System.IO.MemoryStream(,$plain)
        $br = New-Object System.IO.BinaryReader($ms)
        $magic = [System.Text.Encoding]::ASCII.GetString($br.ReadBytes(8))
        if ($magic -ne 'AGYAUTH1') { $br.Dispose(); $ms.Dispose(); return $null }
        $ul = [int]$br.ReadUInt32(); $u  = [System.Text.Encoding]::UTF8.GetString($br.ReadBytes($ul))
        $cl = [int]$br.ReadUInt32(); $cm = [System.Text.Encoding]::UTF8.GetString($br.ReadBytes($cl))
        $ty = $br.ReadUInt32(); $pe = $br.ReadUInt32()
        $bl = [int]$br.ReadUInt32(); $blob = $br.ReadBytes($bl)
        $br.Dispose(); $ms.Dispose()
        return @{ Found = $true; Blob = $blob; UserName = $u; Persist = $pe; Type = $ty; Comment = $cm }
    } catch {
        return $null
    }
}

function Ensure-Store {
    if (-not (Test-Path -LiteralPath $StoreDir)) {
        New-Item -ItemType Directory -Force -Path $StoreDir | Out-Null
        try {
            $me = (whoami)
            & icacls.exe $StoreDir /inheritance:r /grant:r "${me}:(OI)(CI)F" 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0) {
                Out "warn: icacls hardening failed (err=$LASTEXITCODE); DPAPI still protects at rest"
            }
        } catch {
            Out "warn: icacls hardening skipped; DPAPI still protects at rest"
        }
    }
}

function Save-Record {
    param([string]$StoreName, [hashtable]$Rec)
    Ensure-Store
    $cipher = Protect-Record $Rec
    $dataPath = Join-Path $StoreDir ($StoreName + '.agyauth')
    [System.IO.File]::WriteAllBytes($dataPath, $cipher)
    $meta = @{
        name       = $StoreName
        savedAtKst = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
        persist    = [int]$Rec.Persist
    }
    $metaPath = Join-Path $StoreDir ($StoreName + '.meta.json')
    [System.IO.File]::WriteAllText($metaPath, ($meta | ConvertTo-Json -Compress), (New-Object System.Text.UTF8Encoding($false)))
}

function Assert-NameOk {
    param([string]$n)
    if ([string]::IsNullOrEmpty($n) -or ($n -notmatch '^[A-Za-z0-9_-]{1,32}$')) {
        Out "invalid name (allowed: ^[A-Za-z0-9_-]{1,32}$)"
        exit 2
    }
}

try {
    switch ($Action) {

        'status' {
            $cur = Read-TargetCred $Target
            if (-not $cur.Found) {
                Out "account=EMPTY"
            } else {
                $match = $null
                if (Test-Path -LiteralPath $StoreDir) {
                    foreach ($f in Get-ChildItem -LiteralPath $StoreDir -Filter '*.agyauth') {
                        $n = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
                        $rec = Unprotect-Record $f.FullName
                        if ($rec -and (Bytes-Equal $rec.Blob $cur.Blob) -and ($rec.UserName -eq $cur.UserName)) {
                            $match = $n; break
                        }
                    }
                }
                if ($match) { Out "account=$match" } else { Out "account=UNSAVED" }
                if ($ShowAccount) { Out ("accountName=" + (Mask-Account $cur.UserName)) }
            }
            exit 0
        }

        'list' {
            $count = 0
            if (Test-Path -LiteralPath $StoreDir) {
                foreach ($f in (Get-ChildItem -LiteralPath $StoreDir -Filter '*.agyauth' | Sort-Object Name)) {
                    $n = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
                    if ($n -like '_autobackup-*') { continue }
                    $when = $f.LastWriteTime.ToString("yyyy-MM-ddTHH:mm:sszzz")
                    $metaPath = Join-Path $StoreDir ($n + '.meta.json')
                    if (Test-Path -LiteralPath $metaPath) {
                        try {
                            $m = Get-Content -LiteralPath $metaPath -Raw | ConvertFrom-Json
                            if ($m.savedAtKst) { $when = [string]$m.savedAtKst }
                        } catch { }
                    }
                    Out "saved name=$n at=$when"
                    $count++
                }
            }
            if ($count -eq 0) { Out 'saved=(none)' }
            exit 0
        }

        'save' {
            Assert-NameOk $Name
            $cur = Read-TargetCred $Target
            if (-not $cur.Found) { Out "save failed: target EMPTY (err=$($cur.Error))"; exit 3 }
            Save-Record $Name $cur
            Out "saved name=$Name"
            if ($ShowAccount) { Out ("accountName=" + (Mask-Account $cur.UserName)) }
            exit 0
        }

        'use' {
            Assert-NameOk $Name
            $proc = Get-Process -Name $ProcessName -ErrorAction SilentlyContinue
            if ($proc -and -not $Force) {
                $procId = ($proc | Select-Object -First 1).Id
                Out "use refused: process '$ProcessName' running (pid=$procId); close it or pass -Force"
                exit 3
            }
            $dataPath = Join-Path $StoreDir ($Name + '.agyauth')
            if (-not (Test-Path -LiteralPath $dataPath)) {
                Out "use failed: no saved name=$Name"
                exit 3
            }
            $rec = Unprotect-Record $dataPath
            if (-not $rec) { Out "use failed: saved record unreadable"; exit 3 }
            Set-AgyPresence -Status SWITCHING -Account $Name
            $cur = Read-TargetCred $Target
            if ($cur.Found) {
                $bk = '_autobackup-' + (Get-Date).ToString('yyyyMMdd-HHmmss')
                if (Test-Path -LiteralPath (Join-Path $StoreDir ($bk + '.agyauth'))) {
                    $bk = $bk + '-' + [guid]::NewGuid().ToString('N').Substring(0, 4)
                }
                Save-Record $bk $cur
                Out "backup name=$bk"
            } else {
                Out 'backup skipped: target EMPTY'
            }
            $werr = Write-TargetCred -TargetName $Target -Blob $rec.Blob -UserName $rec.UserName `
                                      -Persist $rec.Persist -Type $rec.Type -Comment $rec.Comment
            if ($werr -ne 0) { Set-AgyPresence -Status OFFLINE; Out "use failed: CredWrite err=$werr"; exit 3 }
            $after = Read-TargetCred $Target
            if ($after.Found -and (Bytes-Equal $after.Blob $rec.Blob) -and ($after.UserName -eq $rec.UserName)) {
                Out "use name=$Name result=MATCH"
                if ($ShowAccount) { Out ("accountName=" + (Mask-Account $after.UserName)) }
                Set-AgyPresence -Status OFFLINE
                exit 0
            }
            Out "use name=$Name result=MISMATCH"
            Set-AgyPresence -Status OFFLINE
            exit 4
        }

        'login' {
            Out 'switching accounts needs one manual login per account:'
            Out '  1) Start-Agy-CLI.bat'
            Out '  2) /logout   (inside the agy session)'
            Out '  3) /login    (pick the account in the browser)'
            Out '  4) exit agy, then:  Agy-Auth.bat save <name>'
            Out 'after that, "Agy-Auth.bat use <name>" switches without a browser login.'
            exit 0
        }

        'forget' {
            Assert-NameOk $Name
            $dataPath = Join-Path $StoreDir ($Name + '.agyauth')
            $metaPath = Join-Path $StoreDir ($Name + '.meta.json')
            $existed = (Test-Path -LiteralPath $dataPath)
            Remove-Item -LiteralPath $dataPath -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $metaPath -Force -ErrorAction SilentlyContinue
            $bak = @(Get-ChildItem -LiteralPath $StoreDir -Filter '_autobackup-*.agyauth' -ErrorAction SilentlyContinue |
                     Sort-Object Name -Descending)
            if ($bak.Count -gt 5) {
                $bak | Select-Object -Skip 5 | ForEach-Object {
                    $stem = [System.IO.Path]::GetFileNameWithoutExtension($_.Name)
                    Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
                    Remove-Item -LiteralPath (Join-Path $StoreDir ($stem + '.meta.json')) -Force -ErrorAction SilentlyContinue
                }
            }
            if ($existed) { Out "forgot name=$Name" } else { Out "nothing stored for name=$Name" }
            exit 0
        }
    }
} catch {
    Out ("error: " + $_.Exception.GetType().Name)
    exit 1
}
