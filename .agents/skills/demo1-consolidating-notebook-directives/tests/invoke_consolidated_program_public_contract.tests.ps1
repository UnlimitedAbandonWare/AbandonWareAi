[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:failureRows = [Collections.Generic.List[string]]::new()
$script:assertionCount = 0
$controllerPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\scripts\invoke_consolidated_program.ps1')).Path
$task2aModulePath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\scripts\consolidated_program_core.psm1')).Path
$powerShell = (Get-Command powershell.exe -ErrorAction Stop).Source
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-public-contract-red-' + [guid]::NewGuid().ToString('N'))

function Assert-PublicContract {
    param([bool]$Condition, [string]$Message)

    $script:assertionCount++
    if (-not $Condition) {
        $script:failureRows.Add($Message)
    }
}

function Invoke-PublicController {
    param([string]$WorkingDirectory, [string[]]$Arguments)

    $previousPreference = $ErrorActionPreference
    Push-Location -LiteralPath $WorkingDirectory
    try {
        $ErrorActionPreference = 'Continue'
        $outputRows = @(& $script:powerShell -NoProfile -ExecutionPolicy Bypass -File $script:controllerPath @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
        Pop-Location
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Text = (@($outputRows | ForEach-Object { [string]$_ }) -join "`n")
    }
}

function Invoke-DotSourceProbe {
    $escapedControllerPath = $controllerPath.Replace("'", "''")
    $command = @"
try {
    . '$escapedControllerPath'
    `$reason = 'unexpected-success'
} catch {
    `$reason = [string]`$_.Exception.Message
}
`$legacy = @('Invoke-Begin', 'Invoke-Record', 'Invoke-Release', 'Invoke-Retire', 'Remove-EligibleLeavesAllOrNone') | Where-Object { `$null -ne (Get-Command -Name `$_ -ErrorAction SilentlyContinue) }
[ordered]@{ reason = `$reason; legacyFunctionCount = @(`$legacy).Count } | ConvertTo-Json -Compress
exit 1
"@
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $outputRows = @(& $script:powerShell -NoProfile -ExecutionPolicy Bypass -Command $command 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Text = (@($outputRows | ForEach-Object { [string]$_ }) -join "`n") }
}

function Get-PathFingerprint {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return [pscustomobject][ordered]@{ exists = $false; leaf = $false; length = [int64]0; lastWriteTicks = [int64]0; sha256 = $null }
    }
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (-not $item.PSIsContainer) {
        return [pscustomobject][ordered]@{ exists = $true; leaf = $true; length = [int64]$item.Length; lastWriteTicks = [int64]$item.LastWriteTimeUtc.Ticks; sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
    }
    return [pscustomobject][ordered]@{ exists = $true; leaf = $false; length = [int64]0; lastWriteTicks = [int64]$item.LastWriteTimeUtc.Ticks; sha256 = $null }
}

function Test-SameFingerprint {
    param($Left, $Right)
    return (($Left | ConvertTo-Json -Compress) -ceq ($Right | ConvertTo-Json -Compress))
}

function Get-FunctionDefinition {
    param($Ast, [string]$Name)

    $matches = @($Ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst]
    }, $true) | Where-Object { $_.Name -ceq $Name })
    if ($matches.Count -eq 1) {
        return $matches[0]
    }
    return $null
}

function Get-NamedCommands {
    param($Ast, [string]$Name)

    if ($null -eq $Ast) { return @() }
    return @($Ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.CommandAst]
    }, $true) | Where-Object { $_.GetCommandName() -ceq $Name })
}

New-Item -ItemType Directory -Path $testRoot -ErrorAction Stop | Out-Null
try {
    try {
        $cases = @(
            [pscustomobject]@{ name = 'begin'; action = 'Begin'; extra = @('-WorkUnitId', 'WU-A0', '-RunId', 'public-contract-begin') },
            [pscustomobject]@{ name = 'begin-whatif'; action = 'Begin'; extra = @('-WorkUnitId', 'WU-A0', '-RunId', 'public-contract-begin-whatif', '-WhatIf') },
            [pscustomobject]@{ name = 'record'; action = 'Record'; extra = @('-WorkUnitId', 'WU-A0', '-RunId', 'public-contract-record', '-Outcome', 'Green', '-EvidencePath', 'missing-evidence.json') },
            [pscustomobject]@{ name = 'record-whatif'; action = 'Record'; extra = @('-WorkUnitId', 'WU-A0', '-RunId', 'public-contract-record-whatif', '-Outcome', 'Green', '-EvidencePath', 'missing-evidence.json', '-WhatIf') },
            [pscustomobject]@{ name = 'release'; action = 'Release'; extra = @('-RunId', 'public-contract-release') },
            [pscustomobject]@{ name = 'release-whatif'; action = 'Release'; extra = @('-RunId', 'public-contract-release-whatif', '-WhatIf') },
            [pscustomobject]@{ name = 'retire-dry-run'; action = 'Retire'; extra = @('-RunId', 'public-contract-retire-dry') },
            [pscustomobject]@{ name = 'retire-dry-run-whatif'; action = 'Retire'; extra = @('-RunId', 'public-contract-retire-dry-whatif', '-WhatIf') },
            [pscustomobject]@{ name = 'retire-confirm'; action = 'Retire'; extra = @('-RunId', 'public-contract-retire-confirm', '-ConfirmRetirement') },
            [pscustomobject]@{ name = 'retire-confirm-whatif'; action = 'Retire'; extra = @('-RunId', 'public-contract-retire-confirm-whatif', '-ConfirmRetirement', '-WhatIf') }
        )

        foreach ($case in $cases) {
            $caseRoot = Join-Path $testRoot $case.name
            New-Item -ItemType Directory -Path $caseRoot -ErrorAction Stop | Out-Null
            $beforeRoot = Get-Item -LiteralPath $caseRoot -Force
            $beforeWriteTicks = $beforeRoot.LastWriteTimeUtc.Ticks
            Assert-PublicContract (@(Get-ChildItem -LiteralPath $caseRoot -Force).Count -eq 0) "$($case.name): fixture root is not initially empty"

            $arguments = @('-Action', $case.action, '-Root', $caseRoot) + @($case.extra)
            $result = Invoke-PublicController -WorkingDirectory $caseRoot -Arguments $arguments
            $lines = @($result.Text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })

            Assert-PublicContract ($result.ExitCode -ne 0) "$($case.name): public mutator accepted a noncanonical root"
            Assert-PublicContract ($lines.Count -eq 1) "$($case.name): expected exactly one compact JSON output, observed $($lines.Count)"

            $json = $null
            if ($lines.Count -eq 1) {
                try {
                    $json = $lines[0] | ConvertFrom-Json -ErrorAction Stop
                } catch {
                    Assert-PublicContract $false "$($case.name): output is not JSON"
                }
            }
            if ($null -ne $json) {
                $compact = $json | ConvertTo-Json -Compress -Depth 10
                Assert-PublicContract ($lines[0] -ceq $compact) "$($case.name): output is not one compact JSON object"
                Assert-PublicContract ([string]$json.action -ceq $case.action) "$($case.name): response action is not exact"
                Assert-PublicContract ([string]$json.status -ceq 'error') "$($case.name): response status is not error"
                Assert-PublicContract ([string]$json.reason -ceq 'unsupported-root') "$($case.name): fixed-root gate did not fail first (observed '$([string]$json.reason)')"
            }

            $afterRows = @(Get-ChildItem -LiteralPath $caseRoot -Force -Recurse -ErrorAction Stop)
            $afterRoot = Get-Item -LiteralPath $caseRoot -Force
            Assert-PublicContract ($afterRows.Count -eq 0) "$($case.name): public mutator created content in the empty temp root"
            Assert-PublicContract ($afterRoot.LastWriteTimeUtc.Ticks -eq $beforeWriteTicks) "$($case.name): public mutator changed the temp root"
        }

        $canonicalRoot = 'C:\AbandonWare\demo-1\demo-1\src'
        $canonicalProgram = Join-Path $canonicalRoot 'agent-prompts\awx_desktop_notebook_consolidated_source_directive_20260806.md'
        $canonicalState = Join-Path $canonicalRoot 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\program-state.json'
        $canonicalEvents = Join-Path (Split-Path $canonicalState -Parent) 'events.jsonl'
        $explicitCases = @(
            [pscustomobject]@{ name = 'program'; action = 'Release'; argument = '-ProgramPath'; value = (Join-Path $testRoot 'explicit-program.md'); runId = 'public-contract-explicit-program'; extra = @('-RunId', 'public-contract-explicit-program') },
            [pscustomobject]@{ name = 'program-whatif'; action = 'Release'; argument = '-ProgramPath'; value = (Join-Path $testRoot 'explicit-program-whatif.md'); runId = 'public-contract-explicit-program-whatif'; extra = @('-RunId', 'public-contract-explicit-program-whatif', '-WhatIf') },
            [pscustomobject]@{ name = 'state'; action = 'Retire'; argument = '-StatePath'; value = (Join-Path $testRoot 'explicit-state.json'); runId = 'public-contract-explicit-state'; extra = @('-RunId', 'public-contract-explicit-state') },
            [pscustomobject]@{ name = 'state-whatif'; action = 'Retire'; argument = '-StatePath'; value = (Join-Path $testRoot 'explicit-state-whatif.json'); runId = 'public-contract-explicit-state-whatif'; extra = @('-RunId', 'public-contract-explicit-state-whatif', '-WhatIf') },
            [pscustomobject]@{ name = 'contained-program'; action = 'Begin'; argument = '-ProgramPath'; value = 'agent-prompts\not-the-canonical-program.md'; runId = 'public-contract-contained-program'; extra = @('-WorkUnitId', 'WU-A0', '-RunId', 'public-contract-contained-program') },
            [pscustomobject]@{ name = 'contained-program-whatif'; action = 'Begin'; argument = '-ProgramPath'; value = 'agent-prompts\not-the-canonical-program-whatif.md'; runId = 'public-contract-contained-program-whatif'; extra = @('-WorkUnitId', 'WU-A0', '-RunId', 'public-contract-contained-program-whatif', '-WhatIf') },
            [pscustomobject]@{ name = 'contained-state'; action = 'Record'; argument = '-StatePath'; value = (Join-Path $canonicalRoot 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\not-the-canonical-state.json'); runId = 'public-contract-contained-state'; extra = @('-WorkUnitId', 'WU-A0', '-RunId', 'public-contract-contained-state', '-Outcome', 'Green', '-EvidencePath', 'missing-evidence.json') },
            [pscustomobject]@{ name = 'contained-state-whatif'; action = 'Record'; argument = '-StatePath'; value = (Join-Path $canonicalRoot 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\not-the-canonical-state-whatif.json'); runId = 'public-contract-contained-state-whatif'; extra = @('-WorkUnitId', 'WU-A0', '-RunId', 'public-contract-contained-state-whatif', '-Outcome', 'Green', '-EvidencePath', 'missing-evidence.json', '-WhatIf') }
        )
        foreach ($case in $explicitCases) {
            $releaseArtifact = Join-Path (Join-Path (Split-Path $canonicalState -Parent) 'releases') $case.runId
            $beforeExplicit = Get-PathFingerprint $case.value
            $beforeProgram = Get-PathFingerprint $canonicalProgram
            $beforeState = Get-PathFingerprint $canonicalState
            $beforeEvents = Get-PathFingerprint $canonicalEvents
            $beforeArtifact = Get-PathFingerprint $releaseArtifact
            $result = Invoke-PublicController -WorkingDirectory $testRoot -Arguments (@('-Action', $case.action, '-Root', $canonicalRoot, $case.argument, $case.value) + @($case.extra))
            $lines = @($result.Text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            Assert-PublicContract ($result.ExitCode -ne 0) "explicit-$($case.name): canonical root accepted a nondefault path"
            Assert-PublicContract ($lines.Count -eq 1) "explicit-$($case.name): expected one JSON response"
            $json = if ($lines.Count -eq 1) { try { $lines[0] | ConvertFrom-Json -ErrorAction Stop } catch { $null } } else { $null }
            Assert-PublicContract ($null -ne $json -and [string]$json.reason -ceq 'unsupported-root') "explicit-$($case.name): nondefault path did not fail closed as unsupported-root"
            Assert-PublicContract (Test-SameFingerprint $beforeExplicit (Get-PathFingerprint $case.value)) "explicit-$($case.name): rejected path was touched"
            Assert-PublicContract (Test-SameFingerprint $beforeProgram (Get-PathFingerprint $canonicalProgram)) "explicit-$($case.name): canonical markdown changed"
            Assert-PublicContract (Test-SameFingerprint $beforeState (Get-PathFingerprint $canonicalState)) "explicit-$($case.name): default state changed"
            Assert-PublicContract (Test-SameFingerprint $beforeEvents (Get-PathFingerprint $canonicalEvents)) "explicit-$($case.name): default events changed"
            Assert-PublicContract (Test-SameFingerprint $beforeArtifact (Get-PathFingerprint $releaseArtifact)) "explicit-$($case.name): default release artifact changed"
        }

        $disabledCases = @(
            [pscustomobject]@{ name = 'release'; action = 'Release'; extra = @('-RunId', 'public-contract-disabled-release') },
            [pscustomobject]@{ name = 'release-whatif'; action = 'Release'; extra = @('-RunId', 'public-contract-disabled-release-whatif', '-WhatIf') },
            [pscustomobject]@{ name = 'retire'; action = 'Retire'; extra = @('-RunId', 'public-contract-disabled-retire') },
            [pscustomobject]@{ name = 'retire-whatif'; action = 'Retire'; extra = @('-RunId', 'public-contract-disabled-retire-whatif', '-WhatIf') },
            [pscustomobject]@{ name = 'retire-confirm'; action = 'Retire'; extra = @('-RunId', 'public-contract-disabled-retire-confirm', '-ConfirmRetirement') },
            [pscustomobject]@{ name = 'retire-confirm-whatif'; action = 'Retire'; extra = @('-RunId', 'public-contract-disabled-retire-confirm-whatif', '-ConfirmRetirement', '-WhatIf') }
        )
        foreach ($case in $disabledCases) {
            $releaseArtifact = Join-Path (Join-Path (Split-Path $canonicalState -Parent) 'releases') $case.extra[1]
            $beforeProgram = Get-PathFingerprint $canonicalProgram
            $beforeState = Get-PathFingerprint $canonicalState
            $beforeEvents = Get-PathFingerprint $canonicalEvents
            $beforeArtifact = Get-PathFingerprint $releaseArtifact
            $result = Invoke-PublicController -WorkingDirectory $testRoot -Arguments (@('-Action', $case.action, '-Root', $canonicalRoot) + @($case.extra))
            $lines = @($result.Text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            Assert-PublicContract ($result.ExitCode -ne 0) "disabled-$($case.name): disabled public action succeeded"
            Assert-PublicContract ($lines.Count -eq 1) "disabled-$($case.name): expected one JSON response"
            $json = if ($lines.Count -eq 1) { try { $lines[0] | ConvertFrom-Json -ErrorAction Stop } catch { $null } } else { $null }
            Assert-PublicContract ($null -ne $json -and [string]$json.reason -ceq 'action-not-implemented') "disabled-$($case.name): disabled action did not fail closed"
            Assert-PublicContract (Test-SameFingerprint $beforeProgram (Get-PathFingerprint $canonicalProgram)) "disabled-$($case.name): canonical markdown changed"
            Assert-PublicContract (Test-SameFingerprint $beforeState (Get-PathFingerprint $canonicalState)) "disabled-$($case.name): state changed"
            Assert-PublicContract (Test-SameFingerprint $beforeEvents (Get-PathFingerprint $canonicalEvents)) "disabled-$($case.name): events changed"
            Assert-PublicContract (Test-SameFingerprint $beforeArtifact (Get-PathFingerprint $releaseArtifact)) "disabled-$($case.name): release artifact changed"
        }

        $dotSource = Invoke-DotSourceProbe
        $dotLines = @($dotSource.Text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        Assert-PublicContract ($dotSource.ExitCode -ne 0) 'dot-source: controller did not fail'
        Assert-PublicContract ($dotLines.Count -eq 1) "dot-source: expected one JSON response, observed $($dotLines.Count)"
        $dotJson = if ($dotLines.Count -eq 1) { try { $dotLines[0] | ConvertFrom-Json -ErrorAction Stop } catch { $null } } else { $null }
        Assert-PublicContract ($null -ne $dotJson -and [string]$dotJson.reason -ceq 'dot-source-not-supported') 'dot-source: failure reason is not dot-source-not-supported'
        Assert-PublicContract ($null -ne $dotJson -and [int]$dotJson.legacyFunctionCount -eq 0) 'dot-source: legacy function exposure remains in a fresh child session'

        $controllerText = Get-Content -LiteralPath $controllerPath -Raw -ErrorAction Stop
        $tokens = $null
        $parseErrors = $null
        $controllerAst = [Management.Automation.Language.Parser]::ParseFile($controllerPath, [ref]$tokens, [ref]$parseErrors)
        Assert-PublicContract ($parseErrors.Count -eq 0) "static: controller AST has $($parseErrors.Count) parse errors"
        Assert-PublicContract (Test-Path -LiteralPath $task2aModulePath -PathType Leaf) 'static: approved Task2A module is missing'

        $imports = @(Get-NamedCommands $controllerAst 'Import-Module')
        Assert-PublicContract ($imports.Count -eq 1) "static: expected exactly one Task2A Import-Module command, observed $($imports.Count)"
        Assert-PublicContract ($controllerText.Contains('consolidated_program_core.psm1')) 'static: approved Task2A module path is not referenced'
        Assert-PublicContract ($controllerText.IndexOf('dot-source-not-supported', [StringComparison]::Ordinal) -ge 0 -and $controllerText.IndexOf('dot-source-not-supported', [StringComparison]::Ordinal) -lt $controllerText.IndexOf('Import-Module', [StringComparison]::Ordinal)) 'static: dot-source guard is not before module import'

        $main = Get-FunctionDefinition $controllerAst 'Invoke-ControllerMain'
        Assert-PublicContract ($null -ne $main) 'static: public dispatcher function is missing or duplicated'
        $gateDefinition = Get-FunctionDefinition $controllerAst 'Assert-PublicMutationBoundary'
        Assert-PublicContract ($null -ne $gateDefinition) 'static: exact public mutation boundary function is missing or duplicated'

        if ($null -ne $gateDefinition) {
            $gateText = $gateDefinition.Extent.Text
            Assert-PublicContract ($gateText.Contains('$CanonicalRoot')) 'static: mutation boundary does not bind the fixed canonical root'
            Assert-PublicContract ($gateText.Contains('agent-prompts\awx_desktop_notebook_consolidated_source_directive_20260806.md')) 'static: mutation boundary does not bind the exact default program path'
            Assert-PublicContract ($gateText.Contains('data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\program-state.json')) 'static: mutation boundary does not bind the exact default state path'
            Assert-PublicContract ($gateText.Contains('unsupported-root')) 'static: mutation boundary lacks the unsupported-root failure contract'
            Assert-PublicContract ($gateText.Contains('OrdinalIgnoreCase')) 'static: mutation boundary lacks explicit Windows path comparison semantics'
        }

        if ($null -ne $main) {
            $mainText = $main.Extent.Text
            $gateCalls = @(Get-NamedCommands $main 'Assert-PublicMutationBoundary')
            Assert-PublicContract ($gateCalls.Count -eq 1) "static: public dispatcher must call one mutation boundary, observed $($gateCalls.Count)"
            $gateOffset = if ($gateCalls.Count -eq 1) { $gateCalls[0].Extent.StartOffset } else { [int]::MaxValue }

            foreach ($task2aCall in @('Invoke-ConsolidatedProgramBeginCore', 'Invoke-ConsolidatedProgramRecordCore')) {
                $calls = @(Get-NamedCommands $main $task2aCall)
                Assert-PublicContract ($calls.Count -eq 1) "static: public dispatcher must call $task2aCall exactly once"
                if ($calls.Count -eq 1) {
                    Assert-PublicContract ($calls[0].Extent.StartOffset -gt $gateOffset) "static: $task2aCall is reachable before the fixed mutation boundary"
                }
            }

            foreach ($legacyCall in @('Invoke-Begin', 'Invoke-Record', 'Invoke-Release', 'Invoke-Retire')) {
                Assert-PublicContract (@(Get-NamedCommands $main $legacyCall).Count -eq 0) "static: legacy public dispatch remains reachable through $legacyCall"
            }

            $actionNotImplemented = $false
            $actionNotImplementedOffset = -1
            $throws = @($main.FindAll({
                param($node)
                $node -is [Management.Automation.Language.ThrowStatementAst]
            }, $true) | Where-Object { $_.Extent.Text -match "action-not-implemented" })
            foreach ($throwAst in $throws) {
                $ancestor = $throwAst.Parent
                while ($null -ne $ancestor -and $ancestor -isnot [Management.Automation.Language.IfStatementAst]) {
                    $ancestor = $ancestor.Parent
                }
                if ($null -ne $ancestor -and $ancestor.Extent.Text.Contains("'Release'") -and $ancestor.Extent.Text.Contains("'Retire'")) {
                    $actionNotImplemented = $true
                    $actionNotImplementedOffset = $throwAst.Extent.StartOffset
                    break
                }
            }
            Assert-PublicContract $actionNotImplemented 'static: Release and Retire are not jointly fail-closed as action-not-implemented'
            if ($actionNotImplemented) {
                Assert-PublicContract ($actionNotImplementedOffset -gt $gateOffset) 'static: action-not-implemented is reachable before the fixed mutation boundary'
            }
            Assert-PublicContract (-not [regex]::IsMatch($mainText, '(?i)Invoke-(Begin|Record|Release|Retire)\b')) 'static: legacy mutator commands remain in the public dispatcher'
        }

    } catch {
        $script:failureRows.Add(('harness-error: ' + [string]$_.Exception.Message))
    }
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($script:failureRows.Count -gt 0) {
    Write-Output ([ordered]@{
        status = 'RED'
        firstFailure = [string]$script:failureRows[0]
        failureCount = $script:failureRows.Count
        assertionCount = $script:assertionCount
    } | ConvertTo-Json -Compress)
    exit 1
}

Write-Output ([ordered]@{
    status = 'PASS'
    firstFailure = $null
    failureCount = 0
    assertionCount = $script:assertionCount
} | ConvertTo-Json -Compress)
exit 0
