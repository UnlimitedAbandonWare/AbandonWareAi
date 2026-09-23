[CmdletBinding()]
param([switch]$LibraryMode)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:MaxInputBytes = 65536
$script:MaxOutputBytes = 1024
$script:SourceEditClassifier = Join-Path $PSScriptRoot 'source_edit_triage.py'
$script:AdditionalContext = 'triggerReason=source-edit-intent; Before any application-source mutation, use $demo1-source-edit-three-way-preflight. Do not mutate source unless NEUTRAL_QUERY returns APPLY and the existing source-owner guard passes. Record the change via $demo1-work-ledger (journal + checkpoint preimage).'

function Read-BoundedUtf8Stream {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][IO.Stream]$Stream,
        [Parameter(Mandatory = $true)][ValidateRange(0, 2147483646)][int]$MaxBytes
    )

    $buffer = [Array]::CreateInstance([byte], $MaxBytes + 1)
    $total = 0
    while ($total -lt $buffer.Length) {
        $read = $Stream.Read($buffer, $total, $buffer.Length - $total)
        if ($read -le 0) { break }
        $total += $read
    }
    if ($total -gt $MaxBytes) {
        return [pscustomobject]@{ accepted=$false; text=''; reason='input-too-large' }
    }

    try {
        $utf8 = New-Object Text.UTF8Encoding($false, $true)
        $text = $utf8.GetString($buffer, 0, $total)
        return [pscustomobject]@{ accepted=$true; text=$text; reason='accepted' }
    } catch [Text.DecoderFallbackException] {
        return [pscustomobject]@{ accepted=$false; text=''; reason='invalid-utf8' }
    }
}

function Test-SourceEditIntent {
    [CmdletBinding()]
    param([AllowEmptyString()][string]$Prompt)

    if ([string]::IsNullOrWhiteSpace($Prompt)) { return $false }
    # Python is the sole semantic classifier on Windows and POSIX.
    $process = New-Object Diagnostics.Process
    try {
        $process.StartInfo.FileName = (Get-Command python -ErrorAction Stop).Source
        $classifier = $script:SourceEditClassifier
        $process.StartInfo.Arguments = '-B "' + $classifier + '" --classify'
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.CreateNoWindow = $true
        $process.StartInfo.RedirectStandardInput = $true
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        [void]$process.Start()
        $answerTask = $process.StandardOutput.ReadToEndAsync()
        $null = $process.StandardError.ReadToEndAsync()
        $bytes = [Text.Encoding]::UTF8.GetBytes((@{ prompt=$Prompt } | ConvertTo-Json -Compress))
        $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(15000)) {
            try { $process.Kill() } catch { }
            return $false
        }
        $answer = $answerTask.GetAwaiter().GetResult()
        return ($process.ExitCode -eq 0 -and $answer -ceq 'true')
    } catch {
        return $false
    } finally {
        $process.Dispose()
    }
}

function New-SourceEditHookOutput {
    [CmdletBinding()]
    param([AllowEmptyString()][string]$Prompt)

    if (-not (Test-SourceEditIntent -Prompt $Prompt)) { return '' }
    $payload = [ordered]@{
        hookSpecificOutput = [ordered]@{
            hookEventName = 'UserPromptSubmit'
            additionalContext = $script:AdditionalContext
        }
    }
    $json = $payload | ConvertTo-Json -Depth 4 -Compress
    if ([Text.Encoding]::UTF8.GetByteCount($json) -gt $script:MaxOutputBytes) { return '' }
    return $json
}

function Invoke-SourceEditHook {
    [CmdletBinding()]
    param()

    try {
        $inputResult = Read-BoundedUtf8Stream -Stream ([Console]::OpenStandardInput()) -MaxBytes $script:MaxInputBytes
        if (-not $inputResult.accepted) { return 0 }
        $raw = $inputResult.text
        $event = $raw | ConvertFrom-Json
        $prompt = [string]$event.prompt
        $output = New-SourceEditHookOutput -Prompt $prompt
        if (-not [string]::IsNullOrEmpty($output)) { [Console]::Out.Write($output) }
        return 0
    } catch {
        return 0
    }
}

if (-not $LibraryMode) { exit (Invoke-SourceEditHook) }
