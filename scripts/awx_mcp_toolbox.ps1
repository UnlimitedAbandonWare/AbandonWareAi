[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Tool,

    [string]$Root = "",

    [string]$InputJson = "",

    [switch]$RuntimeSession
)

$ErrorActionPreference = "Stop"

function Write-ToolboxFail {
    param(
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $payload = [ordered]@{
        schemaVersion = "awx.mcp.launcher.v1"
        ok = $false
        toolName = $Tool
        decision = "launcher_error"
        failReason = $Code
        message = $Message
    } | ConvertTo-Json -Depth 8 -Compress
    Write-Output $payload
    exit 1
}

if ([string]::IsNullOrWhiteSpace($Root)) {
    if ($PSScriptRoot) {
        $Root = Split-Path -Parent $PSScriptRoot
    } else {
        $Root = (Get-Location).Path
    }
}

try {
    $ResolvedRoot = (Resolve-Path -LiteralPath $Root).Path
} catch {
    Write-ToolboxFail "root-missing" "cannot resolve Root=$Root"
}

$Toolbox = Join-Path $ResolvedRoot "scripts\awx_mcp_toolbox.py"
if (-not (Test-Path -LiteralPath $Toolbox)) {
    Write-ToolboxFail "toolbox-missing" "missing scripts\awx_mcp_toolbox.py under Root"
}

$Python = Get-Command python -ErrorAction SilentlyContinue
if ($null -eq $Python) {
    Write-ToolboxFail "python-missing" "python is required for the external MCP toolbox"
}

if ($RuntimeSession) {
    if ([string]::IsNullOrWhiteSpace($InputJson)) { $InputJson = "{}" }
    # Windows PowerShell's native argument marshaller removes embedded JSON
    # quotes. Transport the same UTF-8 config without consuming command stdin.
    $EncodedInput = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($InputJson))
    & $Python.Source $Toolbox $Tool --runtime-session --input-base64 $EncodedInput
    exit $LASTEXITCODE
}

if ([string]::IsNullOrWhiteSpace($InputJson)) {
    $InputJson = [Console]::In.ReadToEnd()
}
if ([string]::IsNullOrWhiteSpace($InputJson)) {
    $InputJson = "{}"
}

$InputJson | & $Python.Source $Toolbox $Tool
exit $LASTEXITCODE
