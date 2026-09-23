[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$CorpusPath,
    [Parameter(Mandatory=$true)][string]$OraclePath,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [switch]$RunLive
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$modulePath = Join-Path $PSScriptRoot 'modules\DesktopOllamaModelTools.psm1'
Import-Module -Name $modulePath -Force -ErrorAction Stop

$resolvedCorpusPath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $CorpusPath -ErrorAction Stop).ProviderPath)
$resolvedOraclePath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $OraclePath -ErrorAction Stop).ProviderPath)
$resolvedOutputPath = [IO.Path]::GetFullPath($OutputPath)
if ([string]::Equals($resolvedOutputPath,$resolvedCorpusPath,[StringComparison]::OrdinalIgnoreCase) -or
    [string]::Equals($resolvedOutputPath,$resolvedOraclePath,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'output-path-alias-invalid'
}

$corpus = Get-Content -LiteralPath $resolvedCorpusPath -Raw -Encoding UTF8 | ConvertFrom-Json
$oracle = Get-Content -LiteralPath $resolvedOraclePath -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-AwxBenchmarkContract -Corpus $corpus -Oracle $oracle

$lease = Enter-AwxBenchmarkLease -OutputPath $resolvedOutputPath
try {
    if ($RunLive) {
        $report = Invoke-AwxLiveBenchmarkReport -Corpus $corpus -Oracle $oracle -CorpusPath $resolvedCorpusPath -OraclePath $resolvedOraclePath -Models @(Get-AwxBenchmarkModelMatrix) -PortfolioTimeoutSec 7200
    } else {
        $report = New-AwxValidateOnlyReport -Corpus $corpus -Oracle $oracle -CorpusPath $resolvedCorpusPath -OraclePath $resolvedOraclePath
    }
    Assert-AwxReportPrivacy $report
    Write-AwxJsonAtomic -Path $resolvedOutputPath -Value $report
    [pscustomobject][ordered]@{
        schemaVersion=[int]2
        mode=[string]$report.mode
        verdict=[string]$report.verdict
        outputSha256=Get-AwxFileSha256 $resolvedOutputPath
        reasonCodes=@($report.reasonCodes)
    }
} finally {
    $lease.Dispose()
}
