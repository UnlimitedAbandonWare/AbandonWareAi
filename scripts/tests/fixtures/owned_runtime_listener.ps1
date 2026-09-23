param(
    [int]$Port = 0,
    [string]$RunId = "",
    [Parameter(Mandatory = $true)][string]$ReadyPath,
    [string]$DescendantReadyPath = "",
    [string]$ForeignDescendantReadyPath = "",
    [switch]$DescendantOnly
)

$ErrorActionPreference = "Stop"
if ($DescendantOnly) {
    Set-Content -LiteralPath $ReadyPath -Value $PID -Encoding ASCII
    while ($true) {
        Start-Sleep -Milliseconds 250
    }
}
if ($Port -le 0 -or [string]::IsNullOrWhiteSpace($RunId)) {
    throw "listener-requires-port-and-run-id"
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
$listener.Start()
try {
    if (-not [string]::IsNullOrWhiteSpace($DescendantReadyPath)) {
        Start-Process powershell -WindowStyle Hidden -ArgumentList @(
            "-NoProfile", "-File", $PSCommandPath, "-RunId", $RunId,
            "-ReadyPath", $DescendantReadyPath, "-DescendantOnly"
        ) | Out-Null
    }
    if (-not [string]::IsNullOrWhiteSpace($ForeignDescendantReadyPath)) {
        Start-Process powershell -WindowStyle Hidden -ArgumentList @(
            "-NoProfile", "-File", $PSCommandPath,
            "-ReadyPath", $ForeignDescendantReadyPath, "-DescendantOnly"
        ) | Out-Null
    }
    Set-Content -LiteralPath $ReadyPath -Value $RunId -Encoding ASCII
    while ($true) {
        Start-Sleep -Milliseconds 250
    }
} finally {
    $listener.Stop()
}
