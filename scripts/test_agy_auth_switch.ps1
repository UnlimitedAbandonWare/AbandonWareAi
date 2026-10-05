# test_agy_auth_switch.ps1 - self-test for scripts\agy_auth_switch.ps1.
# Uses a FAKE credential target (awx-test:agy-auth-<8hex>) and a throwaway
# StoreDir under var\agy-auth-test\ - the real 'gemini:antigravity' credential
# is never read, written, or deleted by this file. No Pester; plain asserts.
# Last line: RESULT PASS n/n (exit 0) or RESULT FAIL n/n (exit 1).

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$AuthPs1 = Join-Path $PSScriptRoot 'agy_auth_switch.ps1'
$suffix = -join ((0..7) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
$Target = "awx-test:agy-auth-$suffix"
$StoreDir = Join-Path $root "var\agy-auth-test\$suffix"

if (-not ('AgyAuthTestNative' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

[StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
public struct AgyAuthTestCredentialW {
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

public static class AgyAuthTestNative {
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool CredReadW(string target, uint type, uint flags, out IntPtr cred);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool CredWriteW(ref AgyAuthTestCredentialW cred, uint flags);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool CredDeleteW(string target, uint type, uint flags);
    [DllImport("advapi32.dll")]
    public static extern void CredFree(IntPtr buffer);
}
'@
}

$script:Pass = 0
$script:Fail = 0
$script:AllOut = New-Object System.Text.StringBuilder
$script:leakFile = $false

function Assert {
    param([bool]$Cond, [string]$Label)
    if ($Cond) { $script:Pass++; Write-Host "PASS $Label" }
    else       { $script:Fail++; Write-Host "FAIL $Label" }
}

function Invoke-Auth {
    param([string[]]$AuthArgs)
    $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $AuthPs1 @AuthArgs 2>&1 | Out-String
    [void]$script:AllOut.AppendLine($out)
    return @{ Code = $LASTEXITCODE; Text = $out }
}

function Write-TestCred {
    param([string]$TargetName, [byte[]]$Blob, [string]$UserName)
    $blobPtr = [IntPtr]::Zero
    if ($Blob.Length -gt 0) {
        $blobPtr = [System.Runtime.InteropServices.Marshal]::AllocHGlobal($Blob.Length)
        [System.Runtime.InteropServices.Marshal]::Copy($Blob, 0, $blobPtr, $Blob.Length)
    }
    try {
        $c = New-Object AgyAuthTestCredentialW
        $c.Flags = 0; $c.Type = 1; $c.TargetName = $TargetName
        $c.Comment = 'awx agy-auth self-test'
        $c.CredentialBlobSize = [uint32]$Blob.Length; $c.CredentialBlob = $blobPtr
        $c.Persist = 2; $c.AttributeCount = 0; $c.Attributes = [IntPtr]::Zero
        $c.TargetAlias = $null; $c.UserName = $UserName
        $ok = [AgyAuthTestNative]::CredWriteW([ref]$c, 0)
        if (-not $ok) { return [System.Runtime.InteropServices.Marshal]::GetLastWin32Error() }
        return 0
    } finally {
        if ($blobPtr -ne [IntPtr]::Zero) { [System.Runtime.InteropServices.Marshal]::FreeHGlobal($blobPtr) }
    }
}

function Test-TargetCred {
    param([string]$TargetName)
    $ptr = [IntPtr]::Zero
    $ok = [AgyAuthTestNative]::CredReadW($TargetName, 1, 0, [ref]$ptr)
    if ($ok) { [AgyAuthTestNative]::CredFree($ptr) }
    return $ok
}

function Remove-TestCred {
    param([string]$TargetName)
    $ok = [AgyAuthTestNative]::CredDeleteW($TargetName, 1, 0)
    if (-not $ok) { return [System.Runtime.InteropServices.Marshal]::GetLastWin32Error() }
    return 0
}

$dummyA = [System.Text.Encoding]::UTF8.GetBytes("DUMMY-A-$([guid]::NewGuid().ToString('N'))")
$dummyB = [System.Text.Encoding]::UTF8.GetBytes("DUMMY-B-$([guid]::NewGuid().ToString('N'))")

try {

    # pre-clean in case a previous run crashed mid-way
    [void](Remove-TestCred $Target)

    # (1) EMPTY status on a never-written target
    $r = Invoke-Auth @('-Action','status','-Target',$Target,'-StoreDir',$StoreDir)
    Assert (($r.Code -eq 0) -and ($r.Text -match 'account=EMPTY')) 'case1-status-empty'

    # (2) write dummy A -> save A -> status reports A
    Assert ((Write-TestCred $Target $dummyA 'tester') -eq 0) 'case2-write-dummyA'
    $r = Invoke-Auth @('-Action','save','-Name','A','-Target',$Target,'-StoreDir',$StoreDir)
    Assert ($r.Code -eq 0) 'case2-saveA-exit0'
    $r = Invoke-Auth @('-Action','status','-Target',$Target,'-StoreDir',$StoreDir)
    Assert (($r.Code -eq 0) -and ($r.Text -match 'account=A\b')) 'case2-status-A'

    # (3) overwrite target with dummy B -> save B -> list shows 2
    Assert ((Write-TestCred $Target $dummyB 'tester') -eq 0) 'case3-write-dummyB'
    $r = Invoke-Auth @('-Action','save','-Name','B','-Target',$Target,'-StoreDir',$StoreDir)
    Assert ($r.Code -eq 0) 'case3-saveB-exit0'
    $r = Invoke-Auth @('-Action','list','-Target',$Target,'-StoreDir',$StoreDir)
    $n = ([regex]::Matches($r.Text, 'saved name=')).Count
    Assert (($r.Code -eq 0) -and ($n -eq 2)) 'case3-list-2'

    # (4) use A -> MATCH, status=A, exactly one _autobackup-* created
    #     (a guaranteed-not-running ProcessName keeps the case deterministic
    #     even when a real agy.exe session is live on the machine)
    $r = Invoke-Auth @('-Action','use','-Name','A','-Target',$Target,'-StoreDir',$StoreDir,'-ProcessName',"awx-not-running-$suffix")
    Assert (($r.Code -eq 0) -and ($r.Text -match 'result=MATCH')) 'case4-useA-match'
    $r = Invoke-Auth @('-Action','status','-Target',$Target,'-StoreDir',$StoreDir)
    Assert (($r.Code -eq 0) -and ($r.Text -match 'account=A\b')) 'case4-status-A'
    $bk = @(Get-ChildItem -LiteralPath $StoreDir -Filter '_autobackup-*.agyauth' -ErrorAction SilentlyContinue)
    Assert ($bk.Count -eq 1) 'case4-autobackup-1'

    # (5) use refused while the named process is running (powershell = this test host)
    $r = Invoke-Auth @('-Action','use','-Name','A','-Target',$Target,'-StoreDir',$StoreDir,'-ProcessName','powershell')
    Assert ($r.Code -eq 3) 'case5-use-refused-exit3'

    # (6) invalid name rejected
    $r = Invoke-Auth @('-Action','save','-Name','../x','-Target',$Target,'-StoreDir',$StoreDir)
    Assert ($r.Code -eq 2) 'case6-badname-exit2'

    # (7) forget B -> list shows 1 (autobackup excluded from list)
    $r = Invoke-Auth @('-Action','forget','-Name','B','-Target',$Target,'-StoreDir',$StoreDir)
    Assert ($r.Code -eq 0) 'case7-forgetB-exit0'
    $r = Invoke-Auth @('-Action','list','-Target',$Target,'-StoreDir',$StoreDir)
    $n = ([regex]::Matches($r.Text, 'saved name=')).Count
    Assert (($r.Code -eq 0) -and ($n -eq 1)) 'case7-list-1'

    # (8) leak check: no dummy plaintext in any captured output or store bytes
    $allText = $script:AllOut.ToString()
    $leakOut = ($allText.Contains('DUMMY-A') -or $allText.Contains('DUMMY-B'))
    Get-ChildItem -LiteralPath $StoreDir -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
        $txt = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($_.FullName))
        if ($txt.Contains('DUMMY-A') -or $txt.Contains('DUMMY-B')) { $script:leakFile = $true }
    }
    Assert ((-not $leakOut) -and (-not $script:leakFile)) 'case8-no-plaintext-leak'

    # (9) cleanup: delete test credential + store dir
    $delErr = Remove-TestCred $Target
    Remove-Item -LiteralPath $StoreDir -Recurse -Force -ErrorAction SilentlyContinue
    $stillThere = Test-TargetCred $Target
    Assert (($delErr -eq 0) -and (-not $stillThere) -and (-not (Test-Path -LiteralPath $StoreDir))) 'case9-cleanup'

} finally {
    # belt-and-suspenders cleanup even on a mid-test crash
    try { [void](Remove-TestCred $Target) } catch { }
    if (Test-Path -LiteralPath $StoreDir) { Remove-Item -LiteralPath $StoreDir -Recurse -Force -ErrorAction SilentlyContinue }
}

$total = $script:Pass + $script:Fail
if ($script:Fail -eq 0) {
    Write-Host "RESULT PASS $script:Pass/$total"
    exit 0
}
Write-Host "RESULT FAIL $script:Pass/$total"
exit 1
