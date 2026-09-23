$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'start_rag_stack.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('awx-display-gpu-' + [guid]::NewGuid().ToString('N'))
$originalRoot = $script:RagRoot
$names = @('OLLAMA_HOST','LLM_BASE_URL','LLM_3090_BASE_URL','EMBED_BASE_URL','LOCAL_LLM_ENABLED','LOCAL_LLM_AUTOSTART','LOCAL_LLM_HEALTH_CHECK_URL','LOCAL_LLM_FAIL_FAST')
$original = @{}
foreach ($name in $names) { $original[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$script:passed = 0
function Write-RagStage { param($Stage,$Status,$Message) }
function Read-RagMetaAsrDefaults { return @{} }
function Get-RagDisplayRegistrationUrl { return 'https://example.com/assets/display/index.html' }
function Invoke-RagServices {
    param($RequestedPort,$OllamaPort,$RunDirectory,$TimeoutSeconds,[switch]$CheckOnly,[switch]$MetaDisplay)
    $script:captured = @{port=$OllamaPort;host=$env:OLLAMA_HOST;primary=$env:LLM_BASE_URL;embed=$env:EMBED_BASE_URL;health=$env:LOCAL_LLM_HEALTH_CHECK_URL}
    throw 'synthetic-service-boundary'
}
function Assert-Route($Condition, $Name) {
    if (-not $Condition) { throw ('FAIL: ' + $Name) }
    $script:passed++
}
try {
    New-Item -ItemType Directory -Path $fixture | Out-Null
    $script:RagRoot = $fixture
    foreach ($case in @(
        @{meta=$true;argument=0;hostValue=$null;primary=$null;expected=11434},
        @{meta=$false;argument=0;hostValue=$null;primary=$null;expected=11435},
        @{meta=$true;argument=11438;hostValue=$null;primary=$null;expected=11438},
        @{meta=$true;argument=0;hostValue='127.0.0.1:11435';primary=$null;expected=11435},
        @{meta=$true;argument=0;hostValue=$null;primary='http://127.0.0.1:11438/v1';expected=11434})) {
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$null,'Process') }
        $env:OLLAMA_HOST = $case.hostValue
        $env:LLM_BASE_URL = $case.primary
        $MetaDisplay = $case.meta
        $OllamaPort = $case.argument
        $Port = 0
        $CheckOnly = $true
        $code = Invoke-RagLauncher
        Assert-Route ($code -eq 1) 'capture stopped at service boundary'
        Assert-Route ($script:captured.port -eq $case.expected) 'selected port follows Meta default and explicit precedence'
        Assert-Route ($script:captured.host -eq ('127.0.0.1:' + $case.expected)) 'local manager receives selected endpoint'
        Assert-Route ($script:captured.health -eq ('http://127.0.0.1:' + $case.expected + '/api/version')) 'health checks the selected service'
        $expectedPrimary = if ($case.primary) { $case.primary } elseif ($case.expected -ne 11435) { 'http://127.0.0.1:' + $case.expected + '/v1' } else { $null }
        $expectedEmbed = if ($case.expected -ne 11435) { 'http://127.0.0.1:' + $case.expected + '/api/embed' } else { $null }
        Assert-Route ([string]$script:captured.primary -eq [string]$expectedPrimary) 'primary URL preserves explicit override'
        Assert-Route ([string]$script:captured.embed -eq [string]$expectedEmbed) 'embedding route follows existing selected-port contract'
        Assert-Route ([string]$env:OLLAMA_HOST -eq [string]$case.hostValue -and [string]$env:LLM_BASE_URL -eq [string]$case.primary -and -not $env:EMBED_BASE_URL -and -not $env:LOCAL_LLM_HEALTH_CHECK_URL) 'caller environment restored on failure'
    }
    Write-Output "PASS: $script:passed Meta GPU route checks"
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$original[$name],'Process') }
    $script:RagRoot = $originalRoot
    $resolved = [IO.Path]::GetFullPath($fixture)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolved.StartsWith($tempPrefix,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolved -Leaf).StartsWith('awx-display-gpu-')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
