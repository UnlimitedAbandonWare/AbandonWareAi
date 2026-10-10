# common_powershell_guard_tests.ps1 -- offline unit tests for common_powershell_guard.ps1.
# Temp fixtures only; never touches real system paths beyond spawning cmd.exe/powershell.exe.
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\common_powershell_guard_tests.ps1
[CmdletBinding()] param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'common_powershell_guard.ps1')

$pass = 0; $fail = 0; $results = @()
function Add-Result([string]$Name, [bool]$Ok, [string]$Note) {
    $script:results += [pscustomobject]@{ test = $Name; result = ($(if ($Ok) { 'PASS' } else { 'FAIL' })); note = $Note }
    if ($Ok) { $script:pass++ } else { $script:fail++ }
}

# --- N1: CR/LF + outer whitespace stripped, '/' -> '\' ---
$n = Normalize-SafePath "  C:\work/a b/sub/file.txt`r`n"
Add-Result 'N1-newline-space-slash' ($n -eq 'C:\work\a b\sub\file.txt') "got='$n'"

# --- N2: null/empty/newline-only -> '' ---
Add-Result 'N2-null-empty' ((Normalize-SafePath $null) -eq '' -and (Normalize-SafePath '') -eq '' -and (Normalize-SafePath " `r`n ") -eq '') 'empty-input contract'

# --- N3: trailing separator trimmed (root preserved) ---
Add-Result 'N3-trailing-backslash' ((Normalize-SafePath 'C:\work\dir\') -eq 'C:\work\dir' -and (Normalize-SafePath 'C:\') -eq 'C:\') 'trim trailing sep'

# --- N4: long rooted path gets \\?\ prefix ---
$long = 'C:\' + ('a' * 260)
$n4 = Normalize-SafePath $long
Add-Result 'N4-longpath-prefix' ($n4.StartsWith('\\?\C:\')) "prefix=$($n4.Substring(0,8))"

# --- N5: long UNC path gets \\?\UNC\ prefix ---
$unc = '\\srv\share\' + ('b' * 260)
$n5 = Normalize-SafePath $unc
Add-Result 'N5-unc-prefix' ($n5.StartsWith('\\?\UNC\srv\share')) "prefix=$($n5.Substring(0,16))"

# --- N6: already-prefixed / short paths unchanged ---
$pre = '\\?\C:\already\ok'
Add-Result 'N6-prefixed-unchanged' ((Normalize-SafePath $pre) -eq $pre -and (Normalize-SafePath 'C:\short') -eq 'C:\short') 'idempotent'

# --- U1: Set-Utf8ConsoleEncoding sets UTF-8 on console + $OutputEncoding ---
Set-Utf8ConsoleEncoding
Add-Result 'U1-utf8-console' ([Console]::OutputEncoding.WebName -eq 'utf-8' -and $OutputEncoding.WebName -eq 'utf-8') "enc=$([Console]::OutputEncoding.WebName)"

$cmd = Join-Path $env:SystemRoot 'System32\cmd.exe'
$psx = Join-Path $PSHOME 'powershell.exe'

# --- V1: exit 0 -> ok, captured stdout ---
$v = Invoke-VerifiedNativeCommand $cmd @('/c', 'echo hello-awx')
Add-Result 'V1-exit0-stdout' ($v.ok -and $v.exitCode -eq 0 -and $v.error -eq '' -and ($v.stdout -match 'hello-awx')) "exit=$($v.exitCode)"

# --- V2: exit 3 -> ok=false, explicit error (no silent pass) ---
$v = Invoke-VerifiedNativeCommand $cmd @('/c', 'exit 3')
Add-Result 'V2-exit3-error' ((-not $v.ok) -and $v.exitCode -eq 3 -and ($v.error -match 'exit-3')) "err=$($v.error)"

# --- V3: missing exe -> explicit start failure, never silent ---
$missing = Join-Path $env:TEMP 'awx-no-such-exe-4f2a9c.exe'
$v = Invoke-VerifiedNativeCommand $missing @()
Add-Result 'V3-missing-exe' ((-not $v.ok) -and ($v.error -match 'start-failed')) "err=$($v.error)"

# --- V4: timeout -> killed, explicit timedOut ---
$sw = [Diagnostics.Stopwatch]::StartNew()
$v = Invoke-VerifiedNativeCommand $psx @('-NoProfile', '-Command', 'Start-Sleep 60') -TimeoutMs 1000
Add-Result 'V4-timeout-kill' ((-not $v.ok) -and $v.timedOut -and ($v.error -match 'timeout') -and ($sw.ElapsedMilliseconds -lt 15000)) "elapsed=$($sw.ElapsedMilliseconds)ms err=$($v.error)"

# --- V5: arg with space is passed as ONE argument ---
$argProbe = Join-Path $env:TEMP ('awx-argprobe-' + [guid]::NewGuid().ToString('N') + '.ps1')
try {
    Set-Content -LiteralPath $argProbe -Value 'if ($args.Count -eq 1 -and $args[0] -eq ''two words'') { exit 0 } else { exit 7 }' -Encoding ASCII
    $v = Invoke-VerifiedNativeCommand $psx @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $argProbe, 'two words')
    Add-Result 'V5-arg-space' ($v.ok -and $v.exitCode -eq 0) "exit=$($v.exitCode) err=$($v.error)"
} finally {
    Remove-Item -LiteralPath $argProbe -Force -ErrorAction SilentlyContinue
}

# --- V6: AllowedExitCodes honours non-zero success codes ---
$v = Invoke-VerifiedNativeCommand $cmd @('/c', 'exit 5') -AllowedExitCodes @(0, 5)
Add-Result 'V6-allowed-codes' ($v.ok -and $v.exitCode -eq 5 -and $v.error -eq '') "exit=$($v.exitCode)"

$results | Format-Table -AutoSize
"RESULT: $pass/$($pass + $fail) PASS"
if ($fail -gt 0) { exit 1 } else { exit 0 }
