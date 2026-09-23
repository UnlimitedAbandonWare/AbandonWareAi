$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'start_rag_stack.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('awx-display-asr-' + [guid]::NewGuid().ToString('N'))
$script:passed = 0
$script:stageMessages = [Collections.Generic.List[string]]::new()
function Write-RagStage { param($Stage,$Status,$Message) $script:stageMessages.Add($Message) }
function Assert-Asr($Condition, $Name) {
    if (-not $Condition) { throw ('FAIL: ' + $Name) }
    $script:passed++
}
try {
    New-Item -ItemType Directory -Path $fixture | Out-Null
    $file = Join-Path $fixture 'settings.properties'
    Assert-Asr ((Read-RagMetaAsrDefaults -Path $file).Count -eq 0) 'missing config is optional'
    $program = Join-Path $fixture 'synthetic.py'
    [IO.File]::WriteAllText($program, '# synthetic path only; never executed')
    $valid = "conversate.asr.enabled=true`nconversate.asr.python=$program`nconversate.asr.script=$program`nconversate.asr.model=$fixture`nconversate.asr.cpu-threads=2`nconversate.asr.provider=local"
    [IO.File]::WriteAllText($file, $valid)
    $before = $env:CONVERSATE_ASR_MODEL
    $loaded = Read-RagMetaAsrDefaults -Path $file
    Assert-Asr ($loaded.Count -eq 6 -and $loaded.CONVERSATE_ASR_ENABLED -eq 'true') 'complete local configuration accepted'
    Assert-Asr ($loaded.CONVERSATE_ASR_MODEL -eq $fixture) 'literal absolute path preserved'
    Assert-Asr ($env:CONVERSATE_ASR_MODEL -eq $before) 'reader does not mutate environment'
    foreach ($invalid in @(
        ($valid + "`nunrelated.setting=synthetic"),
        ($valid + "`nCONVERSATE_ASR_ENABLED=false"),
        ($valid -replace 'cpu-threads=2','cpu-threads=0'),
        ($valid -replace 'provider=local','provider=remote'),
        'conversate.asr.enabled=true',
        ('x' * 32769))) {
        [IO.File]::WriteAllText($file, $invalid)
        Assert-Asr ((Read-RagMetaAsrDefaults -Path $file).Count -eq 0) 'invalid file rejected without partial defaults'
    }
    Assert-Asr (-not (($script:stageMessages -join ' ') -like "*$fixture*")) 'diagnostics omit config values'
    Write-Output "PASS: $script:passed Meta ASR config checks"
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $resolved -Leaf).StartsWith('awx-display-asr-')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}
