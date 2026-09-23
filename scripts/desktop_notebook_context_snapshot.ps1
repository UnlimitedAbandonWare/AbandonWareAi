param(
    [string]$OutputDirectory = 'data/agent-handoff/desktop-context',
    [ValidateSet('Metadata', 'Skip')][string]$DatabaseMode = 'Metadata',
    [string]$ProfileConfig = 'main/resources/application-desktop-gpu-node.yml',
    [ValidateRange(1, 120)][int]$DatabaseTimeoutSeconds = 5,
    [ValidateRange(1, 10000)][int]$MaxMetadataRows = 10000
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ModulePath = Join-Path $PSScriptRoot 'modules\DesktopNotebookContextSnapshot.psm1'
Import-Module $ModulePath -Force

$result = Invoke-AwxDesktopNotebookContextSnapshot `
    -RepoRoot $RepoRoot `
    -OutputDirectory $OutputDirectory `
    -DatabaseMode $DatabaseMode `
    -ProfileConfig $ProfileConfig `
    -DatabaseTimeoutSeconds $DatabaseTimeoutSeconds `
    -MaxMetadataRows $MaxMetadataRows

Write-Output ("decision={0} report={1} sha256={2} mariadb={3} evidenceNeededCount={4}" -f `
    $result.Decision, $result.ReportRelativePath, $result.Sha256, $result.MariaDbDecision, $result.EvidenceNeededCount)
