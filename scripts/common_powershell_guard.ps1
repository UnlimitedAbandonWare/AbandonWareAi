# common_powershell_guard.ps1 -- shared defensive helpers for AWX PowerShell tooling.
# Dot-source:  . (Join-Path $PSScriptRoot 'common_powershell_guard.ps1')
# NOTE: keep comments ASCII-only -- ps1 files without BOM are read as ANSI by
# Windows PowerShell 5.1; multibyte comments can emit a backtick that swallows
# the next code line (observed on this checkout).
# Contract (awx.common-ps-guard.v1):
#   Normalize-SafePath            : strip CR/LF + outer whitespace, '/'->'\', \\?\ prefix on long rooted paths
#   Invoke-VerifiedNativeCommand  : run a native exe, verify $LASTEXITCODE precisely, return explicit error
#   Set-Utf8ConsoleEncoding       : $OutputEncoding + [Console] encodings -> UTF-8 (fail-soft)

function Set-Utf8ConsoleEncoding {
    [CmdletBinding()] param()
    # Fail-soft: hosts without a console must not die here.
    try {
        $enc = New-Object System.Text.UTF8Encoding($false)
        [Console]::InputEncoding = $enc
        [Console]::OutputEncoding = $enc
        $global:OutputEncoding = $enc
    } catch { }
}

function Normalize-SafePath {
    [CmdletBinding()]
    param([AllowNull()][AllowEmptyString()][string]$Path)
    if ($null -eq $Path) { return '' }
    $p = $Path -replace "[`r`n]", ''          # P11: embedded newlines hide path failures
    $p = $p.Trim()
    $p = $p -replace '/', '\'                 # unify mixed separators
    while ($p.Length -gt 3 -and $p.EndsWith('\')) { $p = $p.Substring(0, $p.Length - 1) }
    if ($p.Length -eq 0) { return '' }
    # MAX_PATH(260) overflow: rooted paths only get the \\?\ prefix (UNC -> \\?\UNC\)
    $rooted = ($p -match '^[A-Za-z]:\\') -or $p.StartsWith('\\')
    if ($rooted -and $p.Length -ge 248 -and -not $p.StartsWith('\\?\')) {
        $p = if ($p.StartsWith('\\')) { '\\?\UNC\' + $p.Substring(2) } else { '\\?\' + $p }
    }
    return $p
}

function Invoke-VerifiedNativeCommand {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [int[]]$AllowedExitCodes = @(0),
        [int]$TimeoutMs = 30000
    )
    # Native commands never throw on failure under $ErrorActionPreference='Stop';
    # they only set $LASTEXITCODE. Return an explicit result object so quiet
    # failures cannot pass as success.
    $exe = Normalize-SafePath $FilePath
    if (-not $exe) { throw 'native-command-empty-path' }
    $argText = ''
    if ($ArgumentList -and $ArgumentList.Count -gt 0) {
        $argText = (@($ArgumentList) | ForEach-Object {
            $a = [string]$_
            if (($a -eq '') -or ($a -match '[\s"]')) {
                $q = $a -replace '(\\*)"', '$1$1\"'   # N backslashes + quote -> 2N + escaped quote
                $q = $q -replace '(?<!\\)"', '\"'      # remaining bare quotes -> \"
                $q = $q -replace '(\\+)$', '$1$1'      # backslashes before closing quote -> doubled
                '"' + $q + '"'
            } else { $a }
        }) -join ' '
    }
    $pinfo = [Diagnostics.ProcessStartInfo]::new()
    $pinfo.FileName = $exe
    if ($argText) { $pinfo.Arguments = $argText }
    $pinfo.UseShellExecute = $false
    $pinfo.RedirectStandardOutput = $true
    $pinfo.RedirectStandardError = $true
    $pinfo.CreateNoWindow = $true
    $p = [Diagnostics.Process]::new()
    $p.StartInfo = $pinfo
    try {
        try { [void]$p.Start() } catch {
            return [pscustomobject]@{ ok = $false; exitCode = -1; stdout = ''; stderr = '';
                error = ('native-command-start-failed: ' + $_.Exception.Message); timedOut = $false }
        }
        $outTask = $p.StandardOutput.ReadToEndAsync()
        $errTask = $p.StandardError.ReadToEndAsync()
        if (-not $p.WaitForExit($TimeoutMs)) {
            try { $p.Kill() } catch { }
            return [pscustomobject]@{ ok = $false; exitCode = -1; stdout = ''; stderr = '';
                error = ('native-command-timeout-' + $TimeoutMs + 'ms'); timedOut = $true }
        }
        $code = $p.ExitCode
        $ok = ($AllowedExitCodes -contains $code)
        [pscustomobject]@{ ok = $ok; exitCode = $code; stdout = $outTask.Result; stderr = $errTask.Result;
            error = ($(if ($ok) { '' } else { 'native-command-exit-' + $code })); timedOut = $false }
    } finally { $p.Dispose() }
}
